// A block clicked, broken or placed from where the bot stands: interact, break and place, and what
// those and the wsr primitives draw as they go.
const { Vec3 } = require('vec3')
const { FACES, full, at, required, deadlineOf, describeItem } = require('./util')

const REACH_ENTITY = 3
// How many times an interact turns to its block: again when what it looks at changed on the way.
const AIMS = 3
// A place the server refused is clicked again after this long, until its deadline (see timingExceptions.txt).
const PLACE_RETRY_MS = 500

// The window a block opens when used, by block name; the ones not here change state instead (doors, levers, buttons...).
const BLOCK_WINDOWS = {
  chest: 'chest', trapped_chest: 'chest', ender_chest: 'chest', furnace: 'furnace', lit_furnace: 'furnace',
  crafting_table: 'crafting_table', enchanting_table: 'enchanting_table', anvil: 'anvil', brewing_stand: 'brewing_stand',
  dispenser: 'dispenser', dropper: 'dropper', hopper: 'hopper', beacon: 'beacon'
}
for (const color of ['white', 'orange', 'magenta', 'light_blue', 'yellow', 'lime', 'pink', 'gray', 'silver', 'cyan', 'purple', 'blue', 'brown', 'green', 'red', 'black']) {
  BLOCK_WINDOWS[`${color}_shulker_box`] = 'shulker_box'
}
const ENTITY_WINDOWS = { villager: 'villager', horse: 'horse', donkey: 'horse', mule: 'horse' }
// A minecart's, by the kind its spawn says (objectData): 1 chest, 5 hopper; the rest open none.
const MINECART_WINDOWS = { 1: 'chest', 5: 'hopper' }
const MINECART_KINDS = ['rideable', 'chest', 'furnace', 'tnt', 'spawner', 'hopper', 'command block']

// minecraft:chest[facing=north,type=single] -> [name, ['facing=north', 'type=single']]
function splitState (state) {
  const bracket = state.indexOf('[')
  if (bracket < 0) return [state, []]
  return [state.slice(0, bracket), state.slice(bracket + 1, -1).split(',')]
}

// The wanted state names the block, and the properties it lists must be the block's.
function matchState (actual, wanted) {
  const [name, props] = splitState(actual)
  const [wantedName, wantedProps] = splitState(full(wanted))
  return name === wantedName && wantedProps.every(p => props.includes(p))
}

module.exports = ctx => {
  const { blockStates, bot, log, eyes, firstBlock, deadline, done, fail, listen, windowType, aimOf, blockAim, checkLook, digMs, guard, holdBestTool, show, turnTo, holdAllowed, occupies, sendVis } = ctx

  function stateAt (pos) {
    const block = bot.blockAt(pos)
    if (!block) throw new Error(`block at ${at(pos)} is not loaded`)
    const { stateName, stateIdOf } = blockStates()
    return stateName(stateIdOf(block))
  }
  // mineflayer keeps no minecart's kind: kept on the entity it made of the spawn, before this runs.
  bot._client.on('spawn_entity', packet => {
    const entity = bot.entities[packet.entityId]
    if (entity?.name === 'minecart') entity.minecart = packet.objectData
  })

  function checkEntityReach (entity) {
    const center = entity.position.offset(0, entity.height / 2, 0)
    const distance = eyes().distanceTo(center)
    if (distance > REACH_ENTITY) throw new Error(`expected entity ${entity.id} within ${REACH_ENTITY} blocks, found it at ${distance.toFixed(2)}`)
    const { hit, hitDistance } = firstBlock(center)
    if (hit && hitDistance < distance - 0.5) {
      throw new Error(`expected line of sight to entity ${entity.id}, found ${full(hit.name)} at ${at(hit.position)} in the way`)
    }
    return center
  }

  // What ends the interaction: a window of the type opens, or the block takes the state.
  function expect (action, spec, ms) {
    if (spec.kind === 'window') {
      const want = full(required(spec.type, 'expect.type'))
      listen(action, bot, 'windowOpen', window => {
        const type = windowType(window)
        if (type === want) done(action)
        else fail(action, `expected window "${want}", found window "${type}"`)
      })
      deadline(action, ms, () => `expected window "${want}" to open, found ${bot.currentWindow ? `window "${windowType(bot.currentWindow)}"` : 'none'}`)
    } else if (spec.kind === 'block') {
      const pos = new Vec3(spec.x, spec.y, spec.z)
      const want = full(required(spec.state, 'expect.state'))
      listen(action, bot, 'blockUpdate', (oldBlock, newBlock) => {
        if (newBlock.position.equals(pos) && matchState(stateAt(pos), want)) done(action)
      })
      deadline(action, ms, () => `expected block at ${at(pos)} to become "${want}", found "${stateAt(pos)}"${action.dug ? ` (its dig: ${action.dug})` : ''}`)
    } else if (spec.kind === 'changed') {
      // Whatever the block was, it is something else now.
      const pos = new Vec3(spec.x, spec.y, spec.z)
      const was = stateAt(pos)
      listen(action, bot, 'blockUpdate', (oldBlock, newBlock) => {
        if (newBlock.position.equals(pos) && stateAt(pos) !== was) done(action)
      })
      deadline(action, ms, () => `expected block at ${at(pos)} to change from "${was}", found "${stateAt(pos)}"`)
    } else if (spec.kind === 'filled') {
      const pos = new Vec3(spec.x, spec.y, spec.z)
      listen(action, bot, 'blockUpdate', (oldBlock, newBlock) => {
        if (newBlock.position.equals(pos) && newBlock.boundingBox !== 'empty') done(action)
      })
      deadline(action, ms, () => `expected a block at ${at(pos)}, found "${stateAt(pos)}"`)
    } else if (spec.kind === 'hurt') {
      listen(action, bot, 'entityHurt', entity => {
        if (entity.id === spec.id) done(action)
      })
      deadline(action, ms, () => `expected entity ${spec.id} to be hurt, found it unhurt`)
    } else {
      throw new Error(`expect of unknown kind ${spec.kind}`)
    }
  }

  async function interact (action, a) {
    const target = required(a.target, 'target')
    const ms = deadlineOf(a)
    if (a.button !== 'left' && a.button !== 'right') throw new Error(`button must be left or right, got ${a.button}`)
    const left = a.button === 'left'

    if (target.kind === 'entity') {
      // By its UUID, which outlives the id the server gives it each time it loads.
      const uuid = required(target.uuid, 'target.uuid').toLowerCase()
      const entity = Object.values(bot.entities).find(e => e.uuid?.toLowerCase() === uuid)
      if (!entity) throw new Error(`expected entity ${uuid}, the bot does not have it loaded`)
      const center = checkEntityReach(entity)
      let spec = a.expect
      if (!spec && left) {
        spec = { kind: 'hurt', id: entity.id }
      } else if (!spec) {
        const minecart = entity.name === 'minecart'
        const type = minecart ? MINECART_WINDOWS[entity.minecart] : ENTITY_WINDOWS[entity.name]
        if (!type) throw new Error(`expected an entity that opens a window, found ${entity.name}${minecart ? ` (${MINECART_KINDS[entity.minecart] ?? `kind ${entity.minecart}`})` : ''}`)
        spec = { kind: 'window', type }
      }
      expect(action, spec, ms)
      if (!await turnTo(action, a, center)) return
      // Not activateEntity: it turns the head again itself, to the entity's feet a block up.
      if (left) bot.attack(entity)
      else bot.useOn(entity)
      return
    }

    if (target.kind !== 'block') throw new Error(`target of unknown kind ${target.kind}`)
    const block = bot.blockAt(new Vec3(target.x, target.y, target.z))
    if (!block) throw new Error(`block at ${at(target)} is not loaded`)
    if (block.name === 'air') throw new Error(`expected a block at ${at(target)}, found air`)
    let aim = blockAim(block, FACES, a.pitchOnly === true)
    // Left breaks it; right opens its window, or changes it when it has none.
    const spec = a.expect ?? (left
      ? { kind: 'block', x: block.position.x, y: block.position.y, z: block.position.z, state: 'minecraft:air' }
      : BLOCK_WINDOWS[block.name]
        ? { kind: 'window', type: BLOCK_WINDOWS[block.name] }
        : { kind: 'changed', x: block.position.x, y: block.position.y, z: block.position.z })
    if (spec.kind === 'block' && matchState(stateAt(block.position), full(spec.state))) {
      throw new Error(`block at ${at(target)} is already "${stateAt(block.position)}": the interaction would show nothing`)
    }
    // A break: 1.5 times its dig (its effects, mining fatigue, in it), 1000 ms at least, whatever the default.
    expect(action, spec, left ? Math.max(1000, digMs(block) * 1.5) : ms)
    // Let go with its effect confirmed and the primitive going on (break_wsrpa: the gravel fell, the
    // walk to end on): it turns the head no more, the pathfinder's from then on.
    let released = false
    action.cleanups.push(() => { released = true })
    if (a.bestTool && (!await holdBestTool(action, block) || released)) return
    // The world can change while the head turns (sand or gravel falling in the way): aimed again from
    // there, up to AIMS times, the block no longer in reach and in sight failing it. Walking as it
    // turns (break_wsrpa), its eyes moved off the ones aimed from: aimed again from there, not counted.
    for (let aims = 1; ; aims++) {
      const from = eyes()
      show(action, { phase: aims === 1 ? 'turning' : 'aiming again', aim: aimOf(block, aim) })
      if (!await turnTo(action, a, aim.point, aim.box) || released) return
      try {
        checkLook(block, aim)
        break
      } catch (e) {
        show(action, { aim: { ...action.vis?.aim, missed: true } })
        const moved = !eyes().equals(from)
        if (moved) aims--
        else if (aims === AIMS) throw e
        log(`${e.message}: aiming again${moved ? ' (moved while turning)' : ''}`)
        // Walking on as it turns (break_wsrpa), out of sight from where it got to: a.lost takes it on.
        try {
          aim = blockAim(block, FACES, a.pitchOnly === true)
        } catch (lost) {
          // Hidden by sand or gravel come down in the way: no walking on into it (under it, a head in it
          // suffocates), failed for the mod to plan again from the world as it is now.
          if (!moved || !a.lost || /hidden by minecraft:(sand|gravel|concrete_powder) /.test(lost.message)) throw lost
          log(`${lost.message}: lost walking on`)
          a.lost()
          return
        }
      }
    }
    // On the face aimed at, and for a use where on it. The dig with no look of its own: the head is
    // on the block already (checkLook), and mineflayer's would turn it again to the face's middle, a
    // tick more each block. Its packet then names the top face, which the server does not check.
    if (left) {
      // Stopped by its own cleanup, the dig's rejection (Digging aborted) is not a failure.
      let stopped = false
      action.cleanups.push(() => {
        if (!bot.targetDigBlock) return
        stopped = true
        bot.stopDigging()
      })
      show(action, { phase: 'digging' })
      // As mineflayer takes the dig, for the deadline's error: how long it waits, with what, from where.
      action.dug = `${bot.digTime(block)} ms by mineflayer, ${digMs(block)} ms with its best tool, held ${describeItem(bot.heldItem)}, ${bot.entity.onGround ? 'on the ground' : 'off the ground'}${bot.entity.isInWater ? ', in water' : ''}, effects ${JSON.stringify(bot.entity.effects)}`
      bot.dig(block, 'ignore', aim.face).catch(e => { if (!stopped) fail(action, e.message) })
    } else {
      // Not activateBlock: it turns the head again itself, to the block's middle. The same click with
      // no look of its own.
      guard(action, bot._genericPlace(block, aim.face, { swingArm: 'right', forceLook: 'ignore', delta: aim.point.minus(block.position) }))
    }
  }

  function breakBlock (action, a) {
    const target = required(a.target, 'target')
    return interact(action, { ...a, button: 'left', bestTool: true, target: { kind: 'block', ...target }, expect: { kind: 'block', ...target, state: 'minecraft:air' } })
  }

  // Puts a block it may place (see holdAllowed) at target, on a neighbour in reach and in sight, turned to it first; a is
  // { target: {x, y, z}, state, turn_speed }.
  async function place (action, a) {
    const target = required(a.target, 'target')
    const pos = new Vec3(target.x, target.y, target.z)
    const ms = deadlineOf(a)
    const there = bot.blockAt(pos)
    if (!there) throw new Error(`block at ${at(pos)} is not loaded`)
    if (there.boundingBox !== 'empty') throw new Error(`expected a free spot at ${at(pos)}, found ${full(there.name)}`)
    // The server takes no block where an entity is, the bot among them: it would wait out its deadline.
    if (occupies(bot.entity.position, pos)) {
      throw new Error(`expected the bot out of ${at(pos)} to place a block there, found it standing at ${bot.entity.position.toString()}`)
    }
    if (!await holdAllowed(action)) return
    const first = placeAgainst(pos)
    if (first.error) throw new Error(first.error)
    expect(action, a.state ? { kind: 'block', x: pos.x, y: pos.y, z: pos.z, state: a.state } : { kind: 'filled', x: pos.x, y: pos.y, z: pos.z }, ms)
    // Refused by the server (a player or a mob in the spot, the world changed on the way), the click
    // goes again PLACE_RETRY_MS later from where the bot is then (place_wsrpa's stretch followed on
    // meanwhile), for as long as the deadline lets it; with no neighbour in reach and in sight then,
    // at the next. The expect and its deadline stay those of the first click.
    let retry = null
    action.cleanups.push(() => clearTimeout(retry))
    const again = message => {
      log(`${message}: clicking again in ${PLACE_RETRY_MS} ms`)
      show(action, { phase: 'clicking again' })
      retry = setTimeout(() => {
        if (action.over) return
        const next = placeAgainst(pos)
        if (next.error) again(next.error)
        else guard(action, click(next.found))
      }, PLACE_RETRY_MS)
    }
    const click = async found => {
      show(action, { phase: 'turning', aim: aimOf(found.reference, found.aim) })
      if (!await turnTo(action, a, found.aim.point, found.aim.box)) return
      show(action, { phase: 'placing' })
      // placeBlock's, with no look of its own (it would turn the head again to the face's middle) and
      // clicked where the head is.
      bot._placeBlockWithOptions(found.reference, found.face, { swingArm: 'right', forceLook: 'ignore', delta: found.aim.point.minus(found.reference.position) }).catch(e => {
        if (action.over) return
        if (e.message.startsWith('Server refused to place ')) again(e.message)
        else fail(action, e.message)
      })
    }
    await click(first.found)
  }

  // The neighbour of pos to place against in reach and in sight, { found: { reference, face, aim } },
  // its face to pos the one the block goes against; { error } with none.
  function placeAgainst (pos) {
    const reasons = []
    for (const face of FACES) {
      const reference = bot.blockAt(pos.plus(face))
      if (!reference || reference.boundingBox !== 'block') continue
      try {
        return { found: { reference, face: face.scaled(-1), aim: blockAim(reference, [face.scaled(-1)]) } }
      } catch (e) {
        reasons.push(e.message)
      }
    }
    return { error: `expected a block next to ${at(pos)} to place against in reach and in sight, found ${reasons.length ? reasons.join('; ') : 'none solid'}` }
  }

  const targetOf = a => {
    const target = required(a.target, 'target')
    return new Vec3(target.x, target.y, target.z)
  }

  // A wsr primitive, or a plain break or place, drawn as it goes (see show) from its target on.
  const drawn = (search, kind) => (action, a) => {
    action.vis = { kind, target: a.targets ? targetOf({ target: a.targets[0] }) : targetOf(a), phase: 'searching', failure: '', aim: null, spot: null, end: a.end ? new Vec3(a.end.x, a.end.y, a.end.z) : null, partial: null, walk: [], run: [], takeover: null }
    sendVis(action.vis)
    return search(action, a, kind)
  }

  return { interact, breakBlock, place, targetOf, drawn }
}

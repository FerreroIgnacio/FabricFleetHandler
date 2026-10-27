// break_wsr and place_wsr: from where the bot stands, else from the spot it gets to moving the least.
const { Vec3 } = require('vec3')
const { goals } = require('mineflayer-pathfinder')
const { REACH_BLOCK, full, at, required } = require('./util')

module.exports = ctx => {
  const { bot, log, around, breakBlock, done, occupies, place, poseSees, posesIn, reachableAfter, seesFrom, show, stepTo, stood, targetOf, walkTo, allowedSlot } = ctx

  // break_wsr and place_wsr: break and place from where the bot stands; else from the spot it gets to
  // moving the least, a walk of radius blocks at most: the pathfinder's own moves (as it walks them),
  // each weighed by how far it takes it (a drop by the blocks it falls), then where in the block it
  // gets to its feet go (see OFFSETS), the nearest first, standing; then crouched, further out over
  // its edges too (see CROUCH_OFFSETS). It steps there by keys it knows stop it short of a fall (see
  // stepTo), crouched from the start for a crouched one. Radius 0: from where it stands, not moving at all.
  // A place where the bot stands moves it out of the spot first, as a spot not seen from there.
  // Failed when no spot there sees the target. a is break's or place's and { radius }; with end
  // it then walks to the block end, failed before breaking or placing when there is no path there
  // from where it does it, the target as it will be (see reachableAfter).
  async function withSearch (action, a, kind) {
    const pos = targetOf(a)
    const block = checkTarget(kind, pos)
    const radius = required(a.radius, 'radius')
    if (!Number.isInteger(radius) || radius < 0) throw new Error(`radius must be a whole number of blocks, got ${radius}`)
    const what = kind === 'break' ? `${full(block.name)} at ${at(pos)}` : `a block next to ${at(pos)} to place against`
    searchWithin(action, radius)
    // Stepping, it is let go at the end, whatever the end.
    action.cleanups.push(() => { if (!bot._client.ended) bot.clearControlStates() })

    const sees = () => seesFrom(kind, pos, bot.entity.position) && !(kind === 'place' && occupies(bot.entity.position, pos))
    const end = a.end && new Vec3(a.end.x, a.end.y, a.end.z)
    // The spot the last walk to found no path to (see sightSpot).
    let unreached = null
    // The block it got to last with the target out of sight from all of it (see below).
    let unseenAt = null
    // Where it was last once landed, its floor gone (see floorGone).
    let landedAt = null
    // The search of the path to end takes ticks, the bot still moving on from its walk: the target
    // lost from where it got to, it looks for a spot again.
    for (;;) {
      // The spot's floor gone under it on the way (dug, by another bot): it lands and looks for one again.
      while (!sees()) {
        if (radius === 0) throw new Error(`expected ${what} in reach and in sight from where the bot stands (radius 0: not moving), found it not`)
        show(action, { phase: 'searching', aim: null })
        const found = await sightSpot(action, kind, pos, radius)
        if (found === undefined) return
        if (typeof found === 'object') {
          // The same spot found again with no path there: failed, not searched for ever.
          if (unreached && unreached.x === found.x && unreached.y === found.y && unreached.z === found.z) {
            throw new Error(`expected a path to ${at(found)}, a spot with ${what} in sight, found none twice from ${stood()}`)
          }
          unreached = found
          continue
        }
        unreached = null
        if (!found) throw new Error(`expected a spot a walk of ${radius} blocks at most from ${stood()} with ${what} in reach and in sight, found none (around ${at(pos)}: ${around(pos)})`)
        if (await floorGone(action)) {
          if (action.over) return
          // Landed where it was, its floor gone still: the same again would go on for ever.
          if (landedAt && landedAt.equals(bot.entity.position)) throw new Error(`expected the bot to land on a floor once its floor was gone, found it still at ${bot.entity.position.toString()} with none`)
          landedAt = bot.entity.position.clone()
          continue
        }
        const feet = bot.entity.position
        const poses = posesIn(feet.floored(), feet.y, feet).filter(p => poseSees(kind, pos, p))
        const pose = poses[0]
        if (!pose) {
          // The world changed on the way (the spot's floor dug, it got to the block under it): a spot
          // looked for again from here; the same block twice, failed.
          if (unseenAt && unseenAt.equals(feet.floored())) throw new Error(`expected ${what} in reach and in sight from somewhere in ${stood()}, found nowhere twice`)
          unseenAt = feet.floored()
          log(`${action.primitive}: ${what} out of sight from all of ${stood()}, where it got to: looking for a spot again`)
          continue
        }
        // Short of a pose with no keys taking it nearer (at an edge): the next of them, the nearest first;
        // none, a spot looked for again (the same block twice, failed).
        for (let i = 0; i < poses.length; i++) {
          try {
            show(action, { phase: 'stepping', spot: { block: poses[i].ground, feet: poses[i].feet, crouch: poses[i].crouch }, walk: [] })
            await stepTo(action, kind, pos, poses[i], what)
            break
          } catch (e) {
            if (await floorGone(action, true)) break
            if (action.over) return
            if (sees()) break
            if (i < poses.length - 1) {
              log(`${action.primitive}: ${e.message}: the next pose of ${poses.length}`)
              continue
            }
            if (unseenAt && unseenAt.equals(bot.entity.position.floored())) throw e
            unseenAt = bot.entity.position.floored()
            log(`${action.primitive}: ${e.message}: no pose of ${stood()} got to, looking for a spot again`)
          }
        }
        if (action.over) return
      }
      if (!end) break
      show(action, { phase: 'searching end' })
      const reachable = await reachableAfter(action, kind, pos, end)
      if (reachable === undefined) return
      if (!reachable) throw new Error(`expected a path from ${stood()} to ${at(end)} once ${kind === 'break' ? 'broken' : 'placed'} ${at(pos)}, found none`)
      if (sees()) break
      log(`${action.primitive}: ${what} out of sight once the path to ${at(end)} was searched, at ${bot.entity.position.toString()}: looking for a spot again`)
    }
    if (end) {
      // Its break or place confirmed (see expect), what it set up is let go and it walks to end.
      action.then = () => {
        for (const cleanup of action.cleanups.splice(0)) cleanup()
        show(action, { phase: 'walking', aim: null })
        walkTo(action, new goals.GoalBlock(end.x, end.y, end.z), at(end), () => done(action))
      }
    }
    if (kind === 'break') await breakBlock(action, a)
    else await place(action, a)
  }

  // Its walks' searches radius blocks out at most, until the primitive ends: a goal gone out of reach
  // as it walks (the world dug round it) is a noPath, not a search of the whole world going on for ever.
  function searchWithin (action, radius) {
    if (bot.pathfinder.searchRadius === radius) return
    bot.pathfinder.searchRadius = radius
    action.cleanups.push(() => { bot.pathfinder.searchRadius = -1 })
  }

  // Whether the floor under the bot is gone (off the ground, or nothing solid under its box, wherever
  // in its block it stands: over an edge it is held up by the block next to it; not so crouched over
  // one): then once it landed.
  async function floorGone (action, crouched = false) {
    if (bot.entity.onGround && (crouched || floorUnder())) return false
    bot.clearControlStates()
    log(`${action.primitive}: its floor gone at ${bot.entity.position.toString()}: looking for a spot again once landed`)
    await new Promise(resolve => {
      const landed = () => {
        if (!bot.entity.onGround && !action.over) return
        bot.removeListener('physicsTick', landed)
        resolve()
      }
      bot.on('physicsTick', landed)
      action.cleanups.push(() => bot.removeListener('physicsTick', landed))
    })
    return true
  }

  // Whether a block under the bot's box, any it stands over, is solid.
  function floorUnder () {
    const p = bot.entity.position
    const half = bot.entity.width / 2
    const y = Math.floor(p.y) - 1
    for (let x = Math.floor(p.x - half); x < p.x + half; x++) {
      for (let z = Math.floor(p.z - half); z < p.z + half; z++) {
        const under = bot.blockAt(new Vec3(x, y, z))
        if (under && under.boundingBox !== 'empty') return true
      }
    }
    return false
  }

  // The block at pos, failed when it is not one to break (kind break) or a free spot to place in with
  // a block it may place (kind place).
  function checkTarget (kind, pos) {
    const block = bot.blockAt(pos)
    if (!block) throw new Error(`block at ${at(pos)} is not loaded`)
    if (kind === 'break' && block.name === 'air') throw new Error(`expected a block at ${at(pos)}, found air`)
    if (kind === 'place' && block.boundingBox !== 'empty') throw new Error(`expected a free spot at ${at(pos)}, found ${full(block.name)}`)
    if (kind === 'place') allowedSlot()
    return block
  }

  // Whether another player's box is in the block b or the one over it (where a bot's feet and head go there).
  function playerIn (b) {
    return Object.values(bot.entities).some(e => e !== bot.entity && e.type === 'player' &&
      e.position.x + e.width / 2 > b.x && e.position.x - e.width / 2 < b.x + 1 &&
      e.position.z + e.width / 2 > b.z && e.position.z - e.width / 2 < b.z + 1 &&
      e.position.y < b.y + 2 && e.position.y + e.height > b.y)
  }

  // A spot where the feet can go somewhere in it that sees the target (see posesIn), with no other
  // player in it (two pushing each other there, neither gets to it), every one as near as any other:
  // the search goes out by its moves' weights alone, the least first.
  class SightGoal extends goals.Goal {
    constructor (kind, pos) {
      super()
      this.kind = kind
      this.pos = pos
      this.x = pos.x
      this.y = pos.y
      this.z = pos.z
      this.known = new Map()
    }

    heuristic () {
      return 0
    }

    isEnd (node) {
      const key = `${node.x},${node.y},${node.z}`
      if (!this.known.has(key)) {
        const middle = new Vec3(node.x + 0.5, node.y, node.z + 0.5)
        const near = this.pos.offset(0.5, 0.5, 0.5).distanceTo(middle.offset(0, 1, 0)) <= REACH_BLOCK + 2
        this.known.set(key, near && !playerIn(node) && posesIn(node, node.y, middle).some(p => poseSees(this.kind, this.pos, p)))
      }
      return this.known.get(key)
    }
  }

  // The bot's moves as the pathfinder has them, each weighed by how far it takes it instead of by its
  // cost (a drop of three blocks as one step there): the search then goes out by distance moved. Its
  // drops of more than a block left out: no jump takes it back up them, to the end it walks to after.
  function distanceMovements () {
    const base = bot.pathfinder.movements
    const movements = Object.create(base)
    movements.getNeighbors = node => base.getNeighbors.call(movements, node).filter(move => move.y >= node.y - 1).map(move => {
      // Its own cost kept: the walk there is searched no further than the path found costs (see sightSpot).
      move.walkCost = move.cost
      move.cost = Math.hypot(move.x - node.x, move.y - node.y, move.z - node.z)
      return move
    })
    return movements
  }

  // Walks to the spot it gets to moving the least, radius blocks at most, where the feet can go
  // somewhere that sees the target (see SightGoal); the block it stands in first. False when there is
  // none; undefined when the primitive is replaced on the way; the spot, { x, y, z }, when the walk
  // there finds no path (the world changed since): the walk searched no further than the path found
  // costs, its own search would go out radius blocks for ever over open ground.
  async function sightSpot (action, kind, pos, radius) {
    const search = bot.pathfinder.getPathFromTo(distanceMovements(), bot.entity.position, new SightGoal(kind, pos), { searchRadius: radius, optimizePath: false })
    let result
    for (;;) {
      result = search.next().value.result
      if (result.status !== 'partial') break
      await new Promise(resolve => setImmediate(resolve))
      if (action.over) return undefined
    }
    if (result.status !== 'success') return false
    if (result.path.length > 0) {
      const end = result.path[result.path.length - 1]
      log(`${action.primitive}: target seen from ${at(end)}, a walk of ${result.cost.toFixed(1)} blocks, walking there`)
      show(action, { phase: 'walking', spot: { block: end, feet: null }, walk: result.path })
      const cost = result.path.reduce((sum, move) => sum + move.walkCost, 0)
      const within = bot.pathfinder.searchRadius
      bot.pathfinder.searchRadius = cost
      const reached = await new Promise(resolve => walkTo(action, new goals.GoalBlock(end.x, end.y, end.z), at(end), () => resolve(true), () => false, () => resolve(false)))
      bot.pathfinder.searchRadius = within
      if (action.over) return undefined
      if (!reached) {
        log(`${action.primitive}: no path to ${at(end)} any more from ${stood()}: looking for a spot again`)
        return { x: Math.floor(end.x), y: Math.floor(end.y), z: Math.floor(end.z) }
      }
    }
    return true
  }

  return { withSearch, searchWithin, checkTarget }
}

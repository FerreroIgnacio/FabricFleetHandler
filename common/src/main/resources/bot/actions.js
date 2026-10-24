// The action primitives of one bot, and its state: idle, doing <primitive>, error <primitive>: <message>.
// Each ends on the event that confirms its effect; the only time in here is a deadline, which only
// ever fails a primitive with an expected condition (interact, break, place, wait_slot) and is
// settled before it by the event. A failure never kills the process: the bot goes to `error` with
// what was expected and what was found, and stays there until the next primitive. A death cancels
// the primitive running: `error <primitive>: canceled by death`.
const { Vec3 } = require('vec3')
const { goals } = require('mineflayer-pathfinder')
const nbt = require('prismarine-nbt')
const { once } = require('events')
const conv = require('mineflayer/lib/conversions')
const { PlayerState } = require('prismarine-physics')

const REACH_BLOCK = 4.5
// The farthest the eyes get from the middle of the block the feet are in, sideways.
const SLACK = Math.SQRT1_2
const REACH_ENTITY = 3
// The step mineflayer's look turns the head by (bot.look): vanilla's mouse at 100% sensitivity.
const LOOK_STEP = conv.fromNotchianPitch(0.15)
const FACES = [new Vec3(0, -1, 0), new Vec3(0, 1, 0), new Vec3(1, 0, 0), new Vec3(-1, 0, 0), new Vec3(0, 0, 1), new Vec3(0, 0, -1)]
const SIDES = ['bottom', 'top', 'east', 'west', 'south', 'north']
const sideOf = face => SIDES[FACES.findIndex(f => f.equals(face))]
const AXES = ['x', 'y', 'z']
// The face a ray enters a block by, by prismarine-world's BlockFace (bottom, top, north, south, west, east).
const ENTRY_FACES = [new Vec3(0, -1, 0), new Vec3(0, 1, 0), new Vec3(0, 0, -1), new Vec3(0, 0, 1), new Vec3(-1, 0, 0), new Vec3(1, 0, 0)]
// Each face of a block is aimed at in a GRID x GRID grid of points.
const GRID = 4
// How many times an interact turns to its block: again when what it looks at changed on the way.
const AIMS = 3
// Where in the block it stands on break_wsr and place_wsr put the feet, along x and along z: a bit off
// the middle, and out over its edges, 0.1 of its box left on the block: the eyes past the edge, to
// see down by it.
const OFFSETS = [0.5, 0.3, 0.7, 0, 1, -0.2, 1.2]
// Further out over its edges crouched, 0.05 of its box left on the block: sneaking keeps it from falling.
const CROUCH_OFFSETS = [-0.25, 1.25]
// The eyes crouched, as 1.12 has them: 0.08 under standing.
const SNEAK_EYE_HEIGHT = 1.54
// Slower than this the bot is still.
const STILL = 0.005
// The most ticks the bot takes to stop once it lets go of the keys, walking: friction stops it in a few.
const STOP_TICKS = 40
// The keys a step to where it sees the target is made of, as many ways as they go.
const KEY_SETS = [[], ['forward'], ['back'], ['left'], ['right'], ['forward', 'left'], ['forward', 'right'], ['back', 'left'], ['back', 'right']]
const CONTROLS = ['forward', 'back', 'left', 'right', 'jump', 'sprint', 'sneak']

// A primitive with an expected condition fails after this long unless the order carries its own deadline.
const DEFAULT_DEADLINE = 5000
// How fast the bot turns its head to look, in rad/s, unless the order carries its own turn_speed.
const DEFAULT_TURN_SPEED = 9

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

const bare = name => name.replace(/^minecraft:/, '')
const full = name => name.includes(':') ? name : `minecraft:${name}`
const at = p => `${p.x},${p.y},${p.z}`

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

function required (value, what) {
  if (value === undefined || value === null) throw new Error(`${what} is required`)
  return value
}

function turnSpeedOf (a) {
  if (a.turn_speed === undefined) return DEFAULT_TURN_SPEED
  if (typeof a.turn_speed !== 'number' || !(a.turn_speed > 0)) throw new Error(`turn_speed must be a positive number of rad/s, got ${a.turn_speed}`)
  return a.turn_speed
}

function deadlineOf (a) {
  if (a.deadline === undefined) return DEFAULT_DEADLINE
  if (!Number.isInteger(a.deadline) || a.deadline <= 0) throw new Error(`${a.action} needs a deadline in ms, got ${a.deadline}`)
  return a.deadline
}

// The area as a goal: done once the bot stands inside it, x y z its centre for the mod to draw.
class AreaGoal extends goals.Goal {
  constructor (area) {
    super()
    this.area = area
    this.x = Math.floor((area.minX + area.maxX) / 2)
    this.y = area.minY
    this.z = Math.floor((area.minZ + area.maxZ) / 2)
  }

  heuristic (node) {
    const a = this.area
    const dx = Math.max(a.minX - node.x, 0, node.x - a.maxX)
    const dy = Math.max(a.minY - node.y, 0, node.y - a.maxY)
    const dz = Math.max(a.minZ - node.z, 0, node.z - a.maxZ)
    return Math.sqrt(dx * dx + dy * dy + dz * dz)
  }

  isEnd (node) {
    const a = this.area
    return node.x >= a.minX && node.x <= a.maxX && node.y >= a.minY && node.y <= a.maxY && node.z >= a.minZ && node.z <= a.maxZ
  }
}

// publish(kind, primitive, message): the state to the mod, kind idle | doing | error. walk(goal):
// the bot's pathfinder takes the goal (see bot.js). trades(window): the villager window's trades, null
// until the server lists them; selectTrade(window, trade): picks one (see bot.js merchant).
module.exports = function actions ({ bot, name, log, publish, walk, blockStates, trades, selectTrade, rawClick }) {
  let current = null
  // The blocks it may place, keyed name@metadata as the mod sends them (see holdAllowed); null until it does.
  let allowedPlace = null
  // Each primitive's number, counting up for the bot: its lines in fleet.log carry it ("#12").
  let lastId = 0

  function windowType (window) {
    const type = typeof window.type === 'string' ? window.type : bot.registry.windows?.[window.type]?.name
    if (!type) throw new Error(`${name}: window of unknown type ${window.type}`)
    return full(type)
  }

  // How long the primitive has run, for its last line.
  const took = action => `${((Date.now() - action.started) / 1000).toFixed(1)}s`

  // A new primitive takes over from the one running: its listeners and deadline gone, its state replaced.
  // Logged with its fields (the job's step it is, "job", among them) and, for the one it replaced, how long that ran.
  function begin (primitive, a) {
    const action = { id: ++lastId, primitive, started: Date.now(), over: false, cleanups: [] }
    if (current) log(`#${current.id} ${current.primitive} replaced by #${action.id} after ${took(current)}`)
    cancel()
    const { action: _, ...fields } = a
    const shown = JSON.stringify(fields)
    log(`#${action.id} ${primitive}${shown === '{}' ? '' : ' ' + (shown.length > 160 ? shown.slice(0, 160) + '...' : shown)}`)
    current = action
    publish('doing', primitive, '')
    return action
  }

  function end (action) {
    action.over = true
    if (current === action) current = null
    for (const cleanup of action.cleanups) cleanup()
  }

  // The bot is gone, or the next primitive comes: nothing is published.
  function cancel () {
    if (current) end(current)
  }

  // action.then, when set: what follows its effect confirmed, the primitive going on.
  function done (action) {
    if (action.over) return
    if (action.then) {
      const then = action.then
      action.then = null
      then()
      return
    }
    end(action)
    log(`#${action.id} ${action.primitive} ok ${took(action)}`)
    publish('idle', '', '')
  }

  function fail (action, message) {
    if (action.over) return
    end(action)
    log(`#${action.id} ${action.primitive} failed after ${took(action)}: ${message}`, 'E')
    publish('error', action.primitive, message)
  }

  function listen (action, emitter, event, handler) {
    emitter.on(event, handler)
    action.cleanups.push(() => emitter.removeListener(event, handler))
  }

  // Fails the action with what describe() says is found, unless an event settles it first.
  function deadline (action, ms, describe) {
    const timer = setTimeout(() => {
      try {
        fail(action, `${describe()} within ${ms} ms`)
      } catch (e) {
        fail(action, `${e.message} (while describing the failure of ${action.primitive})`)
      }
    }, ms)
    action.cleanups.push(() => clearTimeout(timer))
  }

  function stateAt (pos) {
    const block = bot.blockAt(pos)
    if (!block) throw new Error(`block at ${at(pos)} is not loaded`)
    const { stateName, stateIdOf } = blockStates()
    return stateName(stateIdOf(block))
  }

  function describeItem (item) {
    return item ? `${item.count} x ${full(item.name)}` : 'nothing'
  }

  // --- A. movement

  function goto (action, a) {
    // look: a block to stand where one of its faces is in reach and in sight, to interact with it.
    const goal = a.area ? new AreaGoal(a.area)
      : a.look ? new LookGoal(new Vec3(a.look.x, a.look.y, a.look.z), bot.world, { reach: REACH_BLOCK })
        : new goals.GoalBlock(required(a.spot, 'spot').x, a.spot.y, a.spot.z)
    const where = a.area ? `area ${at({ x: a.area.minX, y: a.area.minY, z: a.area.minZ })}..${at({ x: a.area.maxX, y: a.area.maxY, z: a.area.maxZ })}`
      : a.look ? `sight of ${at(a.look)}` : at(a.spot)
    walkTo(action, goal, where, () => done(action), () => {
      // Already in sight as the interact will have it, from the eyes where they are: walked to, the
      // spot is one where they are in reach wherever in it the bot stands (see LookGoal).
      const look = a.look && bot.blockAt(goal.pos)
      if (!look || aimsAt(look, eyes(), FACES, true).aims.length === 0) return false
      log(`goto: already in sight of ${at(a.look)} at ${stood()}`)
      return true
    })
  }

  const stood = () => at({ x: Math.floor(bot.entity.position.x), y: Math.floor(bot.entity.position.y), z: Math.floor(bot.entity.position.z) })

  // Walks to the goal (where, for the errors), then(): once there and landed; there(), when it says so
  // once landed, ends it there without a walk. Failed when the pathfinder finds no path or gives up.
  function walkTo (action, goal, where, then, there = () => false) {
    // Arrived the walk is over; any other end (failed, replaced, stopped, a death) stops it, the bot
    // never left walking to a goal no primitive waits on. Not on a bot gone: its connection is closed.
    let arrived = false
    action.cleanups.push(() => {
      if (arrived || bot._client.ended || bot.pathfinder.goal !== goal) return
      // Stop then no goal: the pathfinder stops at once, not at the next node.
      bot.pathfinder.stop()
      bot.pathfinder.setGoal(null)
    })
    // Standing: on the ground, or in a liquid. A walk can reach its goal in the air (a drop, a jump),
    // and a bot whose block was dug from under it falls: an aim taken in the air is off once it lands,
    // and a dig there takes five times as long. Its start and its end wait for the landing.
    const standing = () => bot.entity.onGround || bot.entity.isInWater || bot.entity.isInLava
    const landed = then => {
      if (standing()) {
        then()
        return
      }
      listen(action, bot, 'move', function onMove () {
        if (!standing()) return
        bot.removeListener('move', onMove)
        then()
      })
    }
    listen(action, bot, 'goal_reached', () => {
      arrived = true
      landed(then)
    })
    // mineflayer-pathfinder can end a walk on its last node with the goal not met there: it stops and
    // waits with its goal set, searching no more and saying nothing (pathUpdated stays set). Told by
    // the bot moving with a path walked and none left; searched again from there, and failed when it
    // ends twice on the same spot.
    let walking = false
    let endedAt = null
    listen(action, bot, 'path_update', result => {
      if (result.status === 'noPath') fail(action, `expected a path to ${where}, found none from ${stood()}${result.reason ? ` (${result.reason})` : ''}`)
      if (result.status === 'success' && result.path.length > 0) walking = true
    })
    // Stuck (the pathfinder's own call: no headway on its path) it searches again from where it is,
    // and can find the same path there for ever (out of water onto a bank): failed when it is stuck
    // twice on the same spot.
    let stuckAt = null
    listen(action, bot, 'path_reset', reason => {
      walking = false
      if (reason !== 'stuck') return
      const here = stood()
      if (stuckAt === here) fail(action, `expected to arrive at ${where}, the walk got stuck at ${here} twice`)
      stuckAt = here
    })
    listen(action, bot, 'move', () => {
      if (!walking || bot.pathfinder.isMoving() || bot.pathfinder.goal !== goal) return
      walking = false
      const here = stood()
      if (endedAt === here) {
        fail(action, `expected to arrive at ${where}, the walk ended at ${here} short of it twice`)
        return
      }
      endedAt = here
      log(`goto: the walk ended at ${here} short of ${where}: searching again`)
      bot.pathfinder.setGoal(goal)
    })
    listen(action, bot, 'path_stop', () => fail(action, `expected to arrive at ${where}, the walk was stopped at ${stood()}`))
    landed(() => {
      if (there()) {
        arrived = true
        then()
        return
      }
      walk(goal)
    })
  }

  // --- B. interact

  // mineflayer turns the head the server sees a step each physics tick, at its yaw and pitch speeds.
  function setTurnSpeed (speed) {
    bot.physics.yawSpeed = speed
    bot.physics.pitchSpeed = speed
  }
  bot.once('spawn', () => setTurnSpeed(DEFAULT_TURN_SPEED))
  // mineflayer keeps no minecart's kind: kept on the entity it made of the spawn, before this runs.
  bot._client.on('spawn_entity', packet => {
    const entity = bot.entities[packet.entityId]
    if (entity?.name === 'minecart') entity.minecart = packet.objectData
  })

  // Turns to look at point at the order's speed (turn_speed), done once the server has seen it get
  // there; false when the primitive was replaced on the way.
  async function turnTo (action, a, point) {
    setTurnSpeed(turnSpeedOf(a))
    action.cleanups.push(() => setTurnSpeed(DEFAULT_TURN_SPEED))
    const { yaw, pitch } = lookTo(eyes(), point)
    await bot.look(yaw, pitch, false)
    return !action.over
  }

  // The yaw and pitch from the eyes at from to point, as bot.lookAt has them but from those eyes (its
  // own are standing ones, crouched or not).
  function lookTo (from, point) {
    const delta = point.minus(from)
    return { yaw: Math.atan2(-delta.x, -delta.z), pitch: Math.atan2(delta.y, Math.sqrt(delta.x * delta.x + delta.z * delta.z)) }
  }

  // Where the head ends turning from the eyes at from to point: bot.look turns it by whole LOOK_STEPs
  // from where it is, so the ray it ends on is off the point by up to half of one each way.
  function landingDir (from, point) {
    const { yaw, pitch } = lookTo(from, point)
    const y = bot.entity.yaw + Math.round((yaw - bot.entity.yaw) / LOOK_STEP) * LOOK_STEP
    const p = bot.entity.pitch + Math.round((pitch - bot.entity.pitch) / LOOK_STEP) * LOOK_STEP
    return dirOf(y, p)
  }

  function dirOf (yaw, pitch) {
    return new Vec3(-Math.sin(yaw) * Math.cos(pitch), Math.sin(pitch), -Math.cos(yaw) * Math.cos(pitch))
  }

  // The first block the ray from the eyes at from along dir gets to, with the face it enters the
  // block at pos by (null when it gets to another).
  function rayInto (pos, shapes, from, dir, distance) {
    let entered = null
    const hit = bot.world.raycast(from, dir, distance, (b, ray) => {
      if (b.position.equals(pos)) {
        const into = ray.intersect(shapes, pos)
        entered = into && ENTRY_FACES[into.face]
        return Boolean(into)
      }
      return Boolean(ray.intersect(b.shapes, b.position))
    })
    return { hit, entered }
  }

  function eyes () {
    return bot.entity.position.offset(0, eyeHeight(bot.getControlState('sneak')), 0)
  }

  // How high the eyes are over the feet, crouched or standing.
  function eyeHeight (crouch) {
    return crouch ? SNEAK_EYE_HEIGHT : bot.entity.eyeHeight
  }

  // The first block along the way to `to` from the eyes, with how far it is.
  function firstBlock (to) {
    const from = eyes()
    const distance = from.distanceTo(to)
    const hit = bot.world.raycast(from, to.minus(from).normalize(), distance + 1)
    if (!hit) return { hit: null, distance }
    const where = hit.intersect ?? hit.position.offset(0.5, 0.5, 0.5)
    return { hit, distance, hitDistance: from.distanceTo(where) }
  }

  // Where the block is clicked: a point of one of its faces (of faces, all six by default) turned to the
  // eyes, in reach and in sight, the safest; { point, face }. In sight: the ray from the eyes gets to the
  // block's shape (see shapeOf) by that face before another block's collision box cuts it. The rest by
  // their collision box, as mineflayer has them: what has none (grass, a torch) is seen through, a fence
  // stands 1.5 high. The safest: the one
  // with most seen points around it in its face's grid, so a turn off by a hair still clicks the block;
  // of those, the closest.
  function blockAim (block, faces = FACES) {
    const { aims, reasons } = aimsAt(block, eyes(), faces)
    if (aims.length === 0) {
      throw new Error(`expected ${full(block.name)} at ${at(block.position)} in reach and in sight, found ${reasons.length ? reasons.join('; ') : 'no face of it turned to the eyes'}`)
    }
    return aims.reduce((a, b) => b.score > a.score || (b.score === a.score && b.distance < a.distance) ? b : a)
  }

  // The points of the faces of the block (of faces) in reach and in sight from eyes at from, as blockAim
  // takes them: { aims: [{ point, face, distance, score }], reasons: why no face is }. Each face turned
  // to the eyes in reach is a GRID x GRID grid of ray targets; when none is seen the cells whose blocker
  // is not a whole block, or differs from a neighbour's (an edge of what hides it, where a gap may be),
  // split in 2 x 2 and are tried again. first: the first point seen, no more (score 0), for a search;
  // reach: how far the eyes reach, the interact's by default.
  function aimsAt (block, from, faces = FACES, first = false, reach = REACH_BLOCK) {
    const shape = shapeOf(block)
    const reasons = []
    const scans = []
    for (const face of faces) {
      const middle = shape.min.plus(shape.max).scaled(0.5)
      for (const k of AXES) if (face[k] !== 0) middle[k] = face[k] > 0 ? shape.max[k] : shape.min[k]
      // Turned to the eyes: they are past its plane.
      if (from.minus(middle).dot(face) <= 0) continue
      const nearest = distanceToFace(shape, face, from)
      if (nearest > reach) {
        reasons.push(`its ${sideOf(face)} face at ${nearest.toFixed(2)} blocks, past ${reach}`)
        continue
      }
      const grid = gridOf(GRID)
      for (let i = 0; i < GRID; i++) {
        for (let j = 0; j < GRID; j++) {
          grid[i][j] = cellAt(block, shape, from, face, GRID, i, j, reach)
          if (first && grid[i][j]?.blocker === null) return { aims: [{ point: grid[i][j].point, face, distance: grid[i][j].distance, score: 0 }], reasons }
        }
      }
      scans.push({ face, grid })
    }
    let aims = first ? [] : scans.flatMap(scan => seenAims(scan.face, scan.grid))
    if (aims.length === 0) {
      for (const scan of scans) {
        scan.fine = gridOf(GRID * 2)
        for (let i = 0; i < GRID; i++) {
          for (let j = 0; j < GRID; j++) {
            if (!worthSplitting(scan.grid, i, j)) continue
            for (const [di, dj] of [[0, 0], [0, 1], [1, 0], [1, 1]]) {
              const cell = cellAt(block, shape, from, scan.face, GRID * 2, 2 * i + di, 2 * j + dj, reach)
              if (first && cell?.blocker === null) return { aims: [{ point: cell.point, face: scan.face, distance: cell.distance, score: 0 }], reasons }
              scan.fine[2 * i + di][2 * j + dj] = cell
            }
          }
        }
      }
      aims = scans.flatMap(scan => seenAims(scan.face, scan.fine))
    }
    if (first) return { aims: [], reasons }
    if (aims.length === 0) {
      for (const scan of scans) reasons.push(`its ${sideOf(scan.face)} face hidden by ${blockersOf(scan.grid)}`)
    }
    return { aims, reasons }
  }

  function gridOf (n) {
    return Array.from({ length: n }, () => new Array(n))
  }

  // What of the block is clicked: its collision boxes cut to its own space (a fence's post, not the 1.5
  // it stands), the whole block when it has none (grass, a torch, a lever: mineflayer has no outline for
  // them); { shapes, from its corner, min, max: the box around them, in the world }.
  function shapeOf (block) {
    const shapes = block.shapes
      .map(s => [Math.max(s[0], 0), Math.max(s[1], 0), Math.max(s[2], 0), Math.min(s[3], 1), Math.min(s[4], 1), Math.min(s[5], 1)])
      .filter(s => s[0] < s[3] && s[1] < s[4] && s[2] < s[5])
    if (shapes.length === 0) shapes.push([0, 0, 0, 1, 1, 1])
    const min = new Vec3(...[0, 1, 2].map(k => Math.min(...shapes.map(s => s[k])))).plus(block.position)
    const max = new Vec3(...[3, 4, 5].map(k => Math.max(...shapes.map(s => s[k])))).plus(block.position)
    return { shapes, min, max }
  }

  // The nearest the eyes at from get to the face of the box around the shape.
  function distanceToFace (shape, face, from) {
    const normal = AXES.find(k => face[k] !== 0)
    const nearest = from.clone()
    for (const k of AXES) nearest[k] = k === normal ? (face[k] > 0 ? shape.max[k] : shape.min[k]) : Math.min(Math.max(from[k], shape.min[k]), shape.max[k])
    return from.distanceTo(nearest)
  }

  // The point (u, v) of the face of the box around the shape, each from 0 to 1 along its two other axes;
  // a hair inside, so the ray gets into the block's space.
  function facePoint (shape, face, u, v) {
    const normal = AXES.find(k => face[k] !== 0)
    const [b, c] = AXES.filter(k => k !== normal)
    const point = shape.min.clone()
    point[normal] = face[normal] > 0 ? shape.max[normal] - 0.001 : shape.min[normal] + 0.001
    point[b] += u * (shape.max[b] - shape.min[b])
    point[c] += v * (shape.max[c] - shape.min[c])
    return point
  }

  // The cell (i, j) of the n x n grid of a face of the box around the shape: where its ray gets to the
  // shape, with what hides it from the eyes (null when seen); the block itself when the ray gets to it
  // by another face (a stair's step from above), or what the head turned there looks at instead
  // (see landingDir). Nothing when past reach, or when the ray goes by the shape (a ladder's box seen
  // askew) and gets to nothing.
  function cellAt (block, shape, from, face, n, i, j, reach) {
    const pos = block.position
    const target = facePoint(shape, face, (i + 0.5) / n, (j + 0.5) / n)
    if (from.distanceTo(target) > reach) return undefined
    let entered = null
    const hit = bot.world.raycast(from, target.minus(from).normalize(), from.distanceTo(target) + 1, (b, ray) => {
      if (!b.position.equals(pos)) return Boolean(ray.intersect(b.shapes, b.position))
      entered = ray.intersect(shape.shapes, pos)
      return Boolean(entered)
    })
    if (!hit) return undefined
    if (!hit.position.equals(pos)) return { point: target, distance: from.distanceTo(target), blocker: hit }
    const distance = from.distanceTo(entered.pos)
    if (distance > reach) return undefined
    if (!ENTRY_FACES[entered.face].equals(face)) return { point: entered.pos, distance, blocker: hit }
    const landing = rayInto(pos, shape.shapes, from, landingDir(from, entered.pos), distance + 1)
    if (!landing.hit) return undefined
    const lands = landing.hit.position.equals(pos) && landing.entered.equals(face)
    return { point: entered.pos, distance, blocker: lands ? null : landing.hit }
  }

  // The seen cells of a grid, each with how many of the 8 around it are seen too.
  function seenAims (face, grid) {
    const aims = []
    for (let i = 0; i < grid.length; i++) {
      for (let j = 0; j < grid.length; j++) {
        if (grid[i][j]?.blocker !== null) continue
        let score = 0
        for (let di = -1; di <= 1; di++) {
          for (let dj = -1; dj <= 1; dj++) {
            if ((di !== 0 || dj !== 0) && grid[i + di]?.[j + dj]?.blocker === null) score++
          }
        }
        aims.push({ point: grid[i][j].point, face, distance: grid[i][j].distance, score })
      }
    }
    return aims
  }

  const wholeBlock = b => b.shapes.length === 1 && b.shapes[0].join() === '0,0,0,1,1,1'

  // A cell none sees through, to split: what hides it is not a whole block, or is not what hides a
  // neighbour (4 around it), or it is on the face's edge (a sliver of it seen past what hides the rest).
  function worthSplitting (grid, i, j) {
    const cell = grid[i][j]
    if (!cell) return false
    if (!wholeBlock(cell.blocker)) return true
    if (i === 0 || j === 0 || i === grid.length - 1 || j === grid.length - 1) return true
    return [[-1, 0], [1, 0], [0, -1], [0, 1]].some(([di, dj]) => {
      const other = grid[i + di]?.[j + dj]
      return other && !other.blocker.position.equals(cell.blocker.position)
    })
  }

  // What hides the cells of a grid, as `minecraft:stone at 1,2,3 (x12)`, the most first.
  function blockersOf (grid) {
    const counts = new Map()
    for (const cell of grid.flat()) {
      if (!cell) continue
      const key = `${full(cell.blocker.name)} at ${at(cell.blocker.position)}`
      counts.set(key, (counts.get(key) ?? 0) + 1)
    }
    return [...counts].sort((a, b) => b[1] - a[1]).slice(0, 3).map(([key, count]) => `${key} (x${count})`).join(', ')
  }

  // What the server will see once turned: the ray along the yaw and pitch the head ended on enters the
  // block by the face aimed at, else the click is not where the aim says.
  function checkLook (block, aim) {
    const pos = block.position
    const { hit, entered } = rayInto(pos, shapeOf(block).shapes, eyes(), dirOf(bot.entity.yaw, bot.entity.pitch), aim.distance + 1)
    if (!hit || !hit.position.equals(pos) || !entered?.equals(aim.face)) {
      throw new Error(`expected the head turned to ${full(block.name)} at ${at(pos)} by its ${sideOf(aim.face)} face, found it looking at ${hit ? `${full(hit.name)} at ${at(hit.position)}` : 'nothing'}${entered ? ` by its ${sideOf(entered)} face` : ''}`)
    }
  }

  // Standing where the block is in reach and in sight as the interact will have it (see blockAim), the
  // eyes over the middle of the spot: GoalLookAtBlock's own end (its spot's corner 1.6 up to the block's
  // corner, within reach) misses spots the interact reaches from, a chest three blocks up among them.
  // In reach by SLACK less: the bot stands anywhere in the spot's block once there, its eyes up to
  // SLACK from its middle, and the interact aims from there.
  class LookGoal extends goals.GoalLookAtBlock {
    isEnd (node) {
      const block = bot.blockAt(this.pos)
      if (!block) return false
      const from = new Vec3(node.x + 0.5, node.y + bot.entity.eyeHeight, node.z + 0.5)
      if (from.distanceTo(this.pos.offset(0.5, 0.5, 0.5)) > REACH_BLOCK + 1) return false
      return aimsAt(block, from, FACES, true, REACH_BLOCK - SLACK).aims.length > 0
    }
  }

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
      deadline(action, ms, () => `expected block at ${at(pos)} to become "${want}", found "${stateAt(pos)}"`)
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

  // The promise of a mineflayer action that fails: the primitive with it.
  function guard (action, promise) {
    Promise.resolve(promise).catch(e => fail(action, `${e.message}`))
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
      if (left) bot.attack(entity)
      else guard(action, bot.activateEntity(entity))
      return
    }

    if (target.kind !== 'block') throw new Error(`target of unknown kind ${target.kind}`)
    const block = bot.blockAt(new Vec3(target.x, target.y, target.z))
    if (!block) throw new Error(`block at ${at(target)} is not loaded`)
    if (block.name === 'air') throw new Error(`expected a block at ${at(target)}, found air`)
    let aim = blockAim(block)
    // Left breaks it; right opens its window, or changes it when it has none.
    const spec = a.expect ?? (left
      ? { kind: 'block', x: block.position.x, y: block.position.y, z: block.position.z, state: 'minecraft:air' }
      : BLOCK_WINDOWS[block.name]
        ? { kind: 'window', type: BLOCK_WINDOWS[block.name] }
        : { kind: 'changed', x: block.position.x, y: block.position.y, z: block.position.z })
    if (spec.kind === 'block' && matchState(stateAt(block.position), full(spec.state))) {
      throw new Error(`block at ${at(target)} is already "${stateAt(block.position)}": the interaction would show nothing`)
    }
    expect(action, spec, ms)
    if (a.bestTool && !await holdBestTool(action, block)) return
    // The world can change while the head turns (sand or gravel falling in the way): aimed again from
    // there, up to AIMS times, the block no longer in reach and in sight failing it.
    for (let aims = 1; ; aims++) {
      if (!await turnTo(action, a, aim.point)) return
      try {
        checkLook(block, aim)
        break
      } catch (e) {
        if (aims === AIMS) throw e
        log(`${e.message}: aiming again`)
        aim = blockAim(block)
      }
    }
    // On the face aimed at, and for a use where on it. The dig with no look of its own: the head is
    // on the block already (checkLook), and mineflayer's would turn it again to the face's middle, a
    // tick more each block. Its packet then names the top face, which the server does not check.
    if (left) {
      action.cleanups.push(() => { if (bot.targetDigBlock) bot.stopDigging() })
      guard(action, bot.dig(block, 'ignore', aim.face))
    } else {
      guard(action, bot.activateBlock(block, aim.face, aim.point.minus(block.position)))
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
    const reasons = []
    let found = null
    for (const face of FACES) {
      const reference = bot.blockAt(pos.plus(face))
      if (!reference || reference.boundingBox !== 'block') continue
      // Its face to the spot, the one the block goes against.
      try {
        found = { reference, face: face.scaled(-1), aim: blockAim(reference, [face.scaled(-1)]) }
        break
      } catch (e) {
        reasons.push(e.message)
      }
    }
    if (!found) throw new Error(`expected a block next to ${at(pos)} to place against in reach and in sight, found ${reasons.length ? reasons.join('; ') : 'none solid'}`)
    expect(action, a.state ? { kind: 'block', x: pos.x, y: pos.y, z: pos.z, state: a.state } : { kind: 'filled', x: pos.x, y: pos.y, z: pos.z }, ms)
    // The middle of the face it goes against, where mineflayer would look (it then turns no more).
    if (!await turnTo(action, a, found.aim.point)) return
    guard(action, bot.placeBlock(found.reference, found.face))
  }

  // Whether the bot's box with its feet there takes some of the block at pos.
  function occupies (feet, pos) {
    const half = bot.entity.width / 2
    return feet.x + half > pos.x && feet.x - half < pos.x + 1 && feet.y + bot.entity.height > pos.y && feet.y < pos.y + 1 && feet.z + half > pos.z && feet.z - half < pos.z + 1
  }

  // --- B2. with search radius

  // break_wsr and place_wsr: break and place from where the bot stands; else from the spot it gets to
  // moving the least, a walk of radius blocks at most: the pathfinder's own moves (as it walks them),
  // each weighed by how far it takes it (a drop by the blocks it falls), then where in the block it
  // gets to its feet go (see OFFSETS), the nearest first, standing; then crouched, further out over
  // its edges too (see CROUCH_OFFSETS). It steps there by keys it knows stop it short of a fall (see
  // stepTo), crouched from the start for a crouched one. Radius 0: from where it stands, not moving at all.
  // A place where the bot stands moves it out of the spot first, as a spot not seen from there.
  // Failed when no spot there sees the target. a is break's or place's and { radius }; with end
  // (break_wsrp, place_wsrp) it then walks to the block end, failed before breaking or placing when
  // there is no path there from where it does it, the target as it will be (see reachableAfter).
  async function withSearch (action, a, kind) {
    const target = required(a.target, 'target')
    const pos = new Vec3(target.x, target.y, target.z)
    const radius = required(a.radius, 'radius')
    if (!Number.isInteger(radius) || radius < 0) throw new Error(`radius must be a whole number of blocks, got ${radius}`)
    const block = bot.blockAt(pos)
    if (!block) throw new Error(`block at ${at(pos)} is not loaded`)
    if (kind === 'break' && block.name === 'air') throw new Error(`expected a block at ${at(pos)}, found air`)
    if (kind === 'place' && block.boundingBox !== 'empty') throw new Error(`expected a free spot at ${at(pos)}, found ${full(block.name)}`)
    if (kind === 'place') allowedSlot()
    const what = kind === 'break' ? `${full(block.name)} at ${at(pos)}` : `a block next to ${at(pos)} to place against`
    // Stepping, it is let go at the end, whatever the end.
    action.cleanups.push(() => { if (!bot._client.ended) bot.clearControlStates() })

    if (!seesFrom(kind, pos, bot.entity.position) || (kind === 'place' && occupies(bot.entity.position, pos))) {
      if (radius === 0) throw new Error(`expected ${what} in reach and in sight from where the bot stands (radius 0: not moving), found it not`)
      const found = await sightSpot(action, kind, pos, radius)
      if (found === undefined) return
      if (!found) throw new Error(`expected a spot a walk of ${radius} blocks at most from ${stood()} with ${what} in reach and in sight, found none (around ${at(pos)}: ${around(pos)})`)
      const feet = bot.entity.position
      const pose = posesIn(feet.floored(), feet.y, feet).find(p => poseSees(kind, pos, p))
      if (!pose) throw new Error(`expected ${what} in reach and in sight from somewhere in ${stood()}, found nowhere`)
      await stepTo(action, kind, pos, pose, what)
      if (action.over) return
    }
    if (a.end) {
      const end = new Vec3(a.end.x, a.end.y, a.end.z)
      const reachable = await reachableAfter(action, kind, pos, end)
      if (reachable === undefined) return
      if (!reachable) throw new Error(`expected a path from ${stood()} to ${at(end)} once ${kind === 'break' ? 'broken' : 'placed'} ${at(pos)}, found none`)
      // Its break or place confirmed (see expect), what it set up is let go and it walks to end.
      action.then = () => {
        for (const cleanup of action.cleanups.splice(0)) cleanup()
        walkTo(action, new goals.GoalBlock(end.x, end.y, end.z), at(end), () => done(action))
      }
    }
    if (kind === 'break') await breakBlock(action, a)
    else await place(action, a)
  }

  // The blocks next to pos, each with its collision box kind, for an error: what the place had to go against.
  function around (pos) {
    return FACES.map(face => {
      const b = bot.blockAt(pos.plus(face))
      return `${at(pos.plus(face))} ${b ? `${full(b.name)} (${b.boundingBox})` : 'not loaded'}`
    }).join(', ')
  }

  // Whether the pathfinder finds a path from where the bot stands to the block end with the block at
  // pos as the break or the place leaves it: air, or a whole block. Undefined when the primitive is
  // replaced on the way.
  async function reachableAfter (action, kind, pos, end) {
    const base = bot.pathfinder.movements
    const Block = require('prismarine-block')(bot.registry)
    const after = new Block(bot.registry.blocksByName[kind === 'break' ? 'air' : 'stone'].id, 0, 0)
    after.position = pos
    const world = Object.create(bot)
    world.blockAt = (p, extra) => p.equals(pos) ? after : bot.blockAt(p, extra)
    const movements = Object.create(base)
    movements.bot = world
    const search = bot.pathfinder.getPathFromTo(movements, bot.entity.position, new goals.GoalBlock(end.x, end.y, end.z))
    let result
    for (;;) {
      result = search.next().value.result
      if (result.status !== 'partial') break
      await new Promise(resolve => setImmediate(resolve))
      if (action.over) return undefined
    }
    return result.status === 'success'
  }

  // Where the feet can go in the block at b, at y (see OFFSETS), standing first, then crouched (see
  // CROUCH_OFFSETS), each the nearest to from first; { feet, off, crouch }.
  function posesIn (b, y, from) {
    const poses = []
    const crouched = OFFSETS.concat(CROUCH_OFFSETS)
    for (const u of crouched) {
      for (const v of crouched) {
        const feet = new Vec3(b.x + u, y, b.z + v)
        const off = u !== 0.5 || v !== 0.5
        if (OFFSETS.includes(u) && OFFSETS.includes(v)) poses.push({ feet, ground: b, off, crouch: false })
        poses.push({ feet, ground: b, off, crouch: true })
      }
    }
    const away = p => Math.hypot(p.feet.x - from.x, p.feet.z - from.z)
    return poses.sort((p, q) => (p.crouch - q.crouch) || away(p) - away(q))
  }

  // Whether the bot fits in the pose (see fits) and sees the target from there.
  function poseSees (kind, pos, pose) {
    return fits(pose, kind === 'place' ? pos : null) && seesFrom(kind, pos, pose.feet, pose.crouch)
  }

  // Whether the bot's box with its feet there is clear of every block's collision box (and of the spot
  // avoid, a block is going there), and, off the middle of its block, that block stands on a whole one.
  function fits (pose, avoid) {
    const half = bot.entity.width / 2
    const min = pose.feet.offset(-half, 1e-6, -half)
    const max = pose.feet.offset(half, bot.entity.height, half)
    for (let x = Math.floor(min.x); x <= Math.floor(max.x - 1e-6); x++) {
      for (let y = Math.floor(min.y); y <= Math.floor(max.y - 1e-6); y++) {
        for (let z = Math.floor(min.z); z <= Math.floor(max.z - 1e-6); z++) {
          if (avoid && avoid.x === x && avoid.y === y && avoid.z === z) return false
          const block = bot.blockAt(new Vec3(x, y, z))
          if (!block) return false
          for (const s of block.shapes) {
            if (s[0] + x < max.x && s[3] + x > min.x && s[1] + y < max.y && s[4] + y > min.y && s[2] + z < max.z && s[5] + z > min.z) return false
          }
        }
      }
    }
    if (!pose.off) return true
    return bot.blockAt(new Vec3(pose.ground.x, Math.floor(pose.feet.y - 1e-3), pose.ground.z))?.boundingBox === 'block'
  }

  // Whether with its feet there the target is in reach and in sight from its eyes: the
  // block to break, or a neighbour to place against by its face to the spot; crouch: the eyes crouched.
  function seesFrom (kind, pos, feet, crouch = false) {
    const from = feet.offset(0, eyeHeight(crouch), 0)
    if (kind === 'break') {
      const block = bot.blockAt(pos)
      return Boolean(block) && aimsAt(block, from, FACES, true).aims.length > 0
    }
    return FACES.some(face => {
      const reference = bot.blockAt(pos.plus(face))
      return reference?.boundingBox === 'block' && aimsAt(reference, from, [face.scaled(-1)], true).aims.length > 0
    })
  }

  // A spot where the feet can go somewhere in it that sees the target (see posesIn), every one as near
  // as any other: the search goes out by its moves' weights alone, the least first.
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
        this.known.set(key, near && posesIn(node, node.y, middle).some(p => poseSees(this.kind, this.pos, p)))
      }
      return this.known.get(key)
    }
  }

  // The bot's moves as the pathfinder has them, each weighed by how far it takes it instead of by its
  // cost (a drop of three blocks as one step there): the search then goes out by distance moved.
  function distanceMovements () {
    const base = bot.pathfinder.movements
    const movements = Object.create(base)
    movements.getNeighbors = node => base.getNeighbors.call(movements, node).map(move => {
      move.cost = Math.hypot(move.x - node.x, move.y - node.y, move.z - node.z)
      return move
    })
    return movements
  }

  // Walks to the spot it gets to moving the least, radius blocks at most, where the feet can go
  // somewhere that sees the target (see SightGoal); the block it stands in first. False when there is
  // none; undefined when the primitive is replaced on the way.
  async function sightSpot (action, kind, pos, radius) {
    const search = bot.pathfinder.getPathFromTo(distanceMovements(), bot.entity.position, new SightGoal(kind, pos), { searchRadius: radius })
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
      await new Promise(resolve => walkTo(action, new goals.GoalBlock(end.x, end.y, end.z), at(end), resolve))
      if (action.over) return undefined
    }
    return true
  }

  const physicsWorld = { getBlock: p => bot.blockAt(p, false) }

  // Where the bot ends still with the keys down for the next tick and none after, as the client's own
  // physics (the bot's, simulatePlayer) has it; null when it leaves the ground on the way: a fall.
  function stopOf (keys) {
    // Crouched, it stays crouched letting go.
    const sneak = bot.getControlState('sneak')
    const control = Object.fromEntries(CONTROLS.map(c => [c, c === 'sneak' ? sneak : keys.includes(c)]))
    const state = new PlayerState(bot, control)
    const none = Object.fromEntries(CONTROLS.map(c => [c, c === 'sneak' && sneak]))
    let still = 0
    for (let tick = 0; tick < STOP_TICKS; tick++) {
      bot.physics.simulatePlayer(state, physicsWorld)
      state.control = none
      if (!state.onGround) return null
      // Still two ticks running: stopped, not turning round.
      if (Math.hypot(state.vel.x, state.vel.z) >= STILL) {
        still = 0
      } else if (++still === 2) {
        return state.pos
      }
    }
    throw new Error(`expected the bot still within ${STOP_TICKS} ticks of letting go of ${keys.join('+') || 'no keys'}, found it moving at ${state.vel.toString()}`)
  }

  // Steps to where in the block it stands it sees the target (pose, the nearest such), by keys alone,
  // the head left where it is, and never into a fall: each tick, once letting go stops it where it sees
  // the target, no keys; else the keys whose stop (see stopOf) is nearest the pose, of those that keep
  // it on the ground, the ones whose stop sees the target first (a tap goes further than a pose a
  // hair off). Done once still there; failed when, still, no keys bring it nearer.
  function stepTo (action, kind, pos, pose, what) {
    return new Promise((resolve, reject) => {
      const avoid = kind === 'place' ? pos : null
      const sees = feet => fits({ feet }, avoid) && seesFrom(kind, pos, feet, pose.crouch)
      // Crouched before the first step: sneaking keeps it on the block out over its edge.
      if (pose.crouch) bot.setControlState('sneak', true)
      const press = keys => {
        for (const key of ['forward', 'back', 'left', 'right']) bot.setControlState(key, keys.includes(key))
      }
      const step = () => {
        // Stopped for good: the physics zeroes a speed under its negligible one, and a drift under STILL
        // still moves the eyes enough to lose a sight at a grazing angle.
        const still = bot.entity.velocity.x === 0 && bot.entity.velocity.z === 0
        // Done where it really stopped, not where letting go was to stop it: a hair short of that loses
        // a sight through a gap. Still and not seeing, keys again.
        if (still && sees(bot.entity.position)) {
          press([])
          bot.removeListener('physicsTick', step)
          resolve()
          return
        }
        const coast = stopOf([])
        if (!still && coast && sees(coast)) {
          press([])
          return
        }
        let best = null
        let bestSees = false
        let nearest = Infinity
        for (const keys of KEY_SETS) {
          const stop = stopOf(keys)
          if (!stop) continue
          const seen = keys.length > 0 && sees(stop)
          const away = Math.hypot(stop.x - pose.feet.x, stop.z - pose.feet.z)
          if ((seen && !bestSees) || (seen === bestSees && away < nearest)) {
            best = keys
            bestSees = seen
            nearest = away
          }
        }
        if (best === null || (best.length === 0 && still)) {
          bot.removeListener('physicsTick', step)
          press([])
          reject(new Error(`expected to step to ${pose.feet.toString()} to see ${what}, found the bot at ${bot.entity.position.toString()} with no keys taking it nearer without a fall`))
          return
        }
        press(best)
      }
      listen(action, bot, 'physicsTick', step)
      step()
    })
  }

  const allowed = item => Boolean(item) && allowedPlace.has(`${full(item.name)}@${item.metadata}`)

  // The slot of the window its slots are on whose item passes test: the hotbar slot in its hand
  // first, then the rest of the hotbar, then the rest of the inventory; undefined with none.
  function slotWith (test) {
    const window = slotWindow()
    const hand = window.hotbarStart + bot.quickBarSlot
    const slots = [hand]
    for (let i = 0; i < 9; i++) slots.push(window.hotbarStart + i)
    for (let s = window.inventoryStart; s < window.hotbarStart; s++) slots.push(s)
    return slots.find(s => test(window.slots[s]))
  }

  // The slot with a block it may place (see slotWith). Failed with none.
  function allowedSlot () {
    if (allowedPlace === null) throw new Error('expected the blocks it may place from the mod, found none sent')
    const slot = slotWith(allowed)
    if (slot === undefined) throw new Error(`expected a block it may place in its inventory (${allowedPlace.size} allowed), found none`)
    return slot
  }

  // A block it may place into its hand (see allowedSlot). False when the primitive was replaced on the way.
  function holdAllowed (action) {
    return hold(action, allowedSlot())
  }

  // The best tool it has to break block into its hand, as the pathfinder picks it (the fastest dig);
  // none, the hand left as it is. False when the primitive was replaced on the way.
  async function holdBestTool (action, block) {
    const best = bot.pathfinder.bestHarvestTool(block)
    if (!best) return true
    const slot = slotWith(item => Boolean(item) && sameItem(item, best))
    if (slot === undefined) throw new Error(`expected ${full(best.name)} in its inventory, found it gone`)
    return hold(action, slot)
  }

  // The item at slot into its hand: taken off the hotbar, or swapped onto the hotbar slot in hand off
  // the rest of the inventory, as the number keys do. False when the primitive was replaced on the way.
  async function hold (action, slot) {
    const window = slotWindow()
    const index = slot - window.hotbarStart
    if (index >= 0 && index <= 8) {
      if (index !== bot.quickBarSlot) bot.setQuickBarSlot(index)
      return true
    }
    const item = window.slots[slot]
    await syncedClick(window, slot, bot.quickBarSlot, 2)
    if (action.over) return false
    const held = window.slots[window.hotbarStart + bot.quickBarSlot]
    if (!held || !sameItem(held, item)) throw new Error(`expected ${full(item.name)} in hand swapped off slot ${slot}, found ${describeItem(held)}`)
    return true
  }

  // --- C. open window

  function openWindow (what) {
    const window = bot.currentWindow
    if (!window) throw new Error(`expected an open window to ${what}, found none`)
    return window
  }

  // Done once the server has it closed: it never says so, but it takes packets in order, so a click it
  // rejects (outside the inventory, carrying an item it does not have) answered means the close came
  // before it. mineflayer's windowClose is its own copy closed, at the moment it sends.
  function closeWindow (action) {
    const window = openWindow('close')
    listen(action, bot, 'windowClose', () => {
      syncedClick(bot.inventory, -999, 0, 0).then(() => done(action), e => fail(action, e.message))
    })
    bot.closeWindow(window)
  }

  // The window the slots of an order are on: the open one, else the bot's inventory.
  function slotWindow () {
    return bot.currentWindow ?? bot.inventory
  }

  function slotOf (window, value, what) {
    const slot = required(value, what)
    if (!Number.isInteger(slot) || slot < 0 || slot >= window.slots.length) throw new Error(`${what} must be a slot of the window (0..${window.slots.length - 1}), got ${slot}`)
    return slot
  }

  const sameItem = (x, y) => x.type === y.type && x.metadata === y.metadata

  // What the server adds to each slot of the window by itself while it runs, an item picked up off the
  // ground landing on one: the slot changes that come in a set_slot, told apart from the clicks' own
  // (mineflayer does those on its copy, the server confirms them without one). A slot the server puts
  // another item on than it held counts too, as what it holds after.
  function serverChanges (action, window) {
    const added = new Map()
    let packet = null
    const before = p => { if (p.windowId === window.id) packet = p }
    const after = () => { packet = null }
    // Around mineflayer's own handler, which sets the slot then and there.
    bot._client.prependListener('set_slot', before)
    bot._client.on('set_slot', after)
    action.cleanups.push(() => {
      bot._client.removeListener('set_slot', before)
      bot._client.removeListener('set_slot', after)
    })
    listen(action, window, 'updateSlot', (slot, oldItem, newItem) => {
      if (!packet) return
      const was = oldItem && newItem && !sameItem(oldItem, newItem) ? 0 : oldItem?.count ?? 0
      added.set(slot, (added.get(slot) ?? 0) + (newItem?.count ?? 0) - was)
    })
    return added
  }

  // Moves the whole stack on slot a.from onto slot a.to, empty or holding the same item with room for it.
  // Whatever the server puts on either meanwhile (an item picked up) is told apart from what was moved.
  async function move (action, a) {
    const window = slotWindow()
    const from = slotOf(window, a.from, 'from')
    const to = slotOf(window, a.to, 'to')
    if (from === to) throw new Error(`from and to are the same slot ${from}`)
    const item = window.slots[from]
    if (!item) throw new Error(`expected an item on slot ${from}, found nothing`)
    const there = window.slots[to]
    if (there && !sameItem(there, item)) throw new Error(`expected slot ${to} empty or holding ${full(item.name)}, found ${describeItem(there)}`)
    const room = item.stackSize - (there?.count ?? 0)
    if (room < item.count) throw new Error(`expected room for ${item.count} of ${full(item.name)} on slot ${to}, found ${room} (faltaron ${item.count - room})`)
    if (window.selectedItem) throw new Error(`expected an empty cursor, found ${describeItem(window.selectedItem)}`)

    const before = there?.count ?? 0
    const added = serverChanges(action, window)
    // A primitive that takes over leaves this one's clicks undone from there on.
    await bot.clickWindow(from, 0, 0)
    if (action.over) return
    // What the click took, an item picked up onto the slot before it among it; from here on what lands on the slot is the server's.
    const moved = window.selectedItem
    if (!moved || !sameItem(moved, item)) throw new Error(`expected ${full(item.name)} on the cursor taken from slot ${from}, found ${describeItem(moved)}`)
    const count = moved.count
    added.delete(from)
    await bot.clickWindow(to, 0, 0)
    if (action.over) return
    if (window.selectedItem) throw new Error(`expected all ${count} of ${full(item.name)} put on slot ${to}, ${describeItem(window.selectedItem)} left on the cursor`)

    const want = before + count + (added.get(to) ?? 0)
    const after = window.slots[to]
    if (!after || !sameItem(after, item) || after.count !== want) {
      throw new Error(`expected ${want} x ${full(item.name)} on slot ${to} (${before} there, ${count} moved, ${added.get(to) ?? 0} the server's), found ${describeItem(after)}`)
    }
    const left = added.get(from) ?? 0
    // A villager's result shows the next trade at once while the inputs still pay one (see bot.js merchant).
    const result = windowType(window) === 'minecraft:villager' && from === 2
    if (!result && (window.slots[from]?.count ?? 0) !== left) {
      throw new Error(`expected slot ${from} ${left ? `holding the ${left} the server put on it` : 'empty'}, found ${describeItem(window.slots[from])}`)
    }
    done(action)
  }

  // What 1.12's server sends after a click it rejects: the transaction refused, then the whole window
  // (window_items, which mineflayer takes) and the cursor (set_slot -1 -1, which it leaves aside: kept
  // here on the open window). The server did the click on its own window all the same.
  let rejections = 0
  let resyncs = 0
  let itemClass
  bot._client.on('transaction', ({ accepted }) => {
    if (!accepted) rejections++
  })
  bot._client.on('set_slot', ({ windowId, slot, item }) => {
    if (windowId !== -1 || slot !== -1) return
    slotWindow().selectedItem = (itemClass ??= require('prismarine-item')(bot.registry)).fromNotch(item)
    resyncs++
    bot.emit('windowResync')
  })

  // A click the server rejected, thrown once its window and cursor are in.
  class Rejected extends Error {}

  // A left (0) or right (1) click of mineflayer's on the slot; one the server rejects throws a Rejected
  // once the server's window and cursor came, the window as the server has it.
  async function clickSlot (slot, button) {
    const rejected = rejections
    const synced = resyncs
    try {
      await bot.clickWindow(slot, button, 0)
    } catch (e) {
      if (rejections === rejected) throw e
      if (resyncs === synced) await once(bot, 'windowResync')
      throw new Rejected(`the server rejected a click on slot ${slot}`)
    }
  }

  // A click (a shift click, mode 1; a throw, mode 4) sent carrying an item no slot holds (127 of them):
  // the server rejects it, its own move done all the same, and sends the whole window and the cursor.
  // The window is then the server's, whatever mineflayer's copy (prismarine-windows) would have made of
  // the move, which is not the server's for most windows (and has no throw); an accepted click the
  // server never corrects.
  const UNHELD = { blockId: 1, itemCount: 127, itemDamage: 0 }
  async function syncedClick (window, slot, button, mode) {
    const synced = resyncs
    try {
      await rawClick(window, slot, button, mode, UNHELD, true)
    } catch (e) {
      if (!e.rejected) throw e
      if (resyncs === synced) await once(bot, 'windowResync')
      return
    }
    throw new Error(`expected the server to reject the click (mode ${mode}) on slot ${slot} carrying ${UNHELD.itemCount} of an item, it took it: the window is not the server's`)
  }
  const shiftClick = (window, slot, button = 0) => syncedClick(window, slot, button, 1)

  // The armor slot (5 to 8) or the offhand (45) of the inventory window an item goes to, as the server
  // sees it (MobEntity's slot for it); null for none.
  function equipmentSlot (item) {
    const n = bare(item.name)
    if (n.endsWith('_helmet') || n === 'skull' || n === 'pumpkin') return 5
    if (n.endsWith('_chestplate') || n === 'elytra') return 6
    if (n.endsWith('_leggings')) return 7
    if (n.endsWith('_boots')) return 8
    if (n === 'shield') return 45
    return null
  }

  // What one of 1.12's server's moves of a shift click does with the slot's stack (its window's
  // transferSlot): [start, end, reverse], the slots it goes into (insertItem); ['one', slot], one of
  // it onto that slot; 'stop', none. null when the server's choice hangs on what the mod does not know
  // (a furnace's smeltables and fuels, a brewing stand's ingredients, a horse's kind) or the slot is a
  // result (taking it crafts, trades or repairs): no shift click then.
  function shiftStep (window, slots, src, item) {
    const c = window.inventoryStart
    const end = window.inventoryEnd
    const hotbar = end - 9
    const fromInventory = src >= c && src < end
    // An inventory slot to the other part of it: the main inventory to the hotbar, the hotbar to the main.
    const swap = src < hotbar ? [hotbar, end, false] : [c, hotbar, false]
    switch (bare(windowType(window))) {
      case 'chest': case 'container': case 'dispenser': case 'dropper': case 'hopper': case 'shulker_box':
        return fromInventory ? [0, c, false] : [c, end, true]
      case 'EntityHorse':
        return fromInventory ? null : [c, end, true]
      case 'crafting_table':
        if (src === 0) return null
        return fromInventory ? swap : [c, end, false]
      case 'furnace':
        if (src === 2) return [c, end, true]
        return fromInventory ? null : [c, end, false]
      case 'brewing_stand':
        return fromInventory ? null : [c, end, true]
      case 'enchanting_table':
        if (!fromInventory) return [c, end, true]
        if (bare(item.name) === 'dye' && item.metadata === 4) return [1, 2, true]
        return slots[0] ? 'stop' : ['one', 0]
      case 'beacon':
        if (!fromInventory) return [c, end, true]
        if (!slots[0] && item.count === 1 && ['iron_ingot', 'gold_ingot', 'emerald', 'diamond'].includes(bare(item.name))) return [0, 1, false]
        return swap
      case 'villager':
      case 'anvil':
        if (src === 2) return null
        if (!fromInventory) return [c, end, false]
        return bare(windowType(window)) === 'villager' ? swap : [0, 2, false]
      case 'inventory': {
        if (src === 0) return null
        if (!fromInventory) return [c, end, false]
        const to = equipmentSlot(item)
        if (to !== null && !slots[to]) return [to, to + 1, false]
        return swap
      }
      default:
        return null
    }
  }

  // How many of an item a slot of the window takes, as the server has it.
  function slotLimit (window, slot) {
    const type = bare(windowType(window))
    if (type === 'inventory' && slot >= 5 && slot <= 8) return 1
    if ((type === 'beacon' || type === 'enchanting_table') && slot === 0) return 1
    if (type === 'brewing_stand' && slot <= 2) return 1
    if (type === 'EntityHorse' && slot <= 1) return 1
    return 64
  }

  // Where 1.12's server puts the stack of slot src shift-clicked, worked out on a copy of the window:
  // its moves one after another while the slot keeps some of it and the last one moved any (onto
  // the same item with room first, from start on, or end back reversed; then on the first empty slot
  // that takes it, no more than the slot does). [slot, n] each; null when shiftStep cannot tell.
  function shiftSpread (window, src) {
    const slots = window.slots.map(it => it && Object.assign(Object.create(Object.getPrototypeOf(it)), it))
    const spread = new Map()
    const add = (slot, n) => spread.set(slot, (spread.get(slot) ?? 0) + n)
    while (slots[src]) {
      const item = slots[src]
      const step = shiftStep(window, slots, src, item)
      if (step === null) return null
      if (step === 'stop') break
      let moved = 0
      if (step[0] === 'one') {
        slots[step[1]] = Object.assign(Object.create(Object.getPrototypeOf(item)), item, { count: 1 })
        moved = 1
      } else {
        const [start, stop, reverse] = step
        const order = Array.from({ length: stop - start }, (_, i) => reverse ? stop - 1 - i : start + i)
        if (item.stackSize > 1) {
          for (const slot of order) {
            const there = slots[slot]
            if (moved === item.count) break
            if (!there || slot === src || !item.constructor.equal(there, item, false)) continue
            const n = Math.min(item.count - moved, item.stackSize - there.count)
            if (n <= 0) continue
            there.count += n
            moved += n
            add(slot, n)
          }
        }
        // A shulker box holds no shulker box.
        const takes = slot => !(bare(windowType(window)) === 'shulker_box' && slot < window.inventoryStart && bare(item.name).endsWith('shulker_box'))
        const empty = moved < item.count ? order.find(slot => !slots[slot] && takes(slot)) : undefined
        if (empty !== undefined) {
          const n = Math.min(item.count - moved, slotLimit(window, empty))
          slots[empty] = Object.assign(Object.create(Object.getPrototypeOf(item)), item, { count: n })
          moved += n
          add(empty, n)
        }
      }
      if (step[0] === 'one') add(step[1], 1)
      if (moved === 0) break
      item.count -= moved
      if (item.count === 0) slots[src] = null
    }
    return [...spread]
  }

  // Moves the items a.item matches (see itemMatcher), or a.items (a list of them: any of them), onto the slots of a.targets, from every other slot of
  // the window (or only a.sources), as much as fits and up to a.count if given: onto the same item with room, or
  // empty slots, in the order given. Targets filling up with sources left over is fine, and so is moving
  // nothing (no room on the targets, none of the items outside them): done with 0 moved, the window maybe
  // changed by another player or bot since whoever sent it looked at it.
  //
  // A click the server rejects (another player or bot changed the window first) is no failure: what the
  // cursor holds goes back where it came from and the moves go on from the window as the server has it.
  // How many it moved is counted on the side that is the bot's own inventory (the targets, else the
  // sources), no one else's clicks touching it. A source whose shift click puts its items on targets only, no more of
  // them than still wanted, goes in one shift click (see shiftSpread), the window the server's after
  // it. A rejection that leaves the window as one before did, since the last
  // move that went through, is no change of anyone else's: it fails. The window closed under it (by the
  // server: the bot moved off, the block broke) fails it there, the click on its way never answered.
  async function itemFill (action, a) {
    const window = slotWindow()
    listen(action, bot, 'windowClose', closed => {
      if (closed === window) fail(action, `the window was closed while moving items, ${describeItem(window.selectedItem)} on the cursor`)
    })
    const specs = a.items === undefined ? [required(a.item, 'item')] : a.items
    if (!Array.isArray(specs) || specs.length === 0) throw new Error(`items must list one or more matches, got ${JSON.stringify(a.items)}`)
    const matchers = specs.map((spec, i) => itemMatcher(spec, a.items === undefined ? 'item' : `items[${i}]`))
    const wanted = { test: item => matchers.some(m => m.test(item)) }
    const name = matchers.map(m => m.describe()).join(' or ')
    const list = (value, what) => {
      if (!Array.isArray(value) || value.length === 0) throw new Error(`${what} must list one or more slots`)
      return [...new Set(value.map((s, i) => slotOf(window, s, `${what}[${i}]`)))]
    }
    const targets = list(a.targets, 'targets')
    const limit = a.count ?? Infinity
    if (limit !== Infinity && (!Number.isInteger(limit) || limit <= 0)) throw new Error(`count must be a positive integer, got ${a.count}`)
    const from = (a.sources === undefined ? window.slots.map((_, i) => i) : list(a.sources, 'sources')).filter(s => !targets.includes(s))
    const sources = () => from.filter(s => wanted.test(window.slots[s]))
    if (window.selectedItem) throw new Error(`expected an empty cursor, found ${describeItem(window.selectedItem)}`)

    const own = s => s >= window.inventoryStart && s < window.inventoryEnd
    const bySources = !targets.every(own) && from.every(own)
    const total = slots => slots.reduce((n, s) => n + (wanted.test(window.slots[s]) ? window.slots[s].count : 0), 0)
    const first = bySources ? total(from) : total(targets)
    const moved = () => bySources ? first - total(from) : total(targets) - first
    // A shift click does the source's moves: all of its items onto targets, no more than still wanted.
    const shiftDoes = src => {
      const spread = shiftSpread(window, src)
      return spread !== null && spread.length > 0 && spread.every(([slot]) => targets.includes(slot)) &&
        spread.reduce((n, [, k]) => n + k, 0) <= limit - moved()
    }
    const state = () => window.slots.map(it => it ? `${it.type}:${it.metadata}:${it.count}` : '').join(',') + '|' + describeItem(window.selectedItem)

    // The next move: from a source onto a target that takes it, no more than still wanted; null with none.
    const nextMove = () => {
      const left = limit - moved()
      if (left <= 0) return null
      for (const src of sources()) {
        for (const tgt of targets) {
          const item = window.slots[src]
          const there = window.slots[tgt]
          if (there && !sameItem(there, item)) continue
          const room = item.stackSize - (there?.count ?? 0)
          const n = Math.min(item.count, room, left)
          if (n > 0) return { src, tgt, n, item }
        }
      }
      return null
    }

    // What the cursor holds after a rejection, back where it came from (or any other slot it came from,
    // or onto a target, being one of the items).
    const putBack = async src => {
      const item = window.selectedItem
      const places = [...new Set([...(src === null ? [] : [src]), ...from, ...(wanted.test(item) ? targets : [])])]
      for (const slot of places) {
        const there = window.slots[slot]
        if (!window.selectedItem) return
        if (there && (!sameItem(there, window.selectedItem) || there.count >= there.stackSize)) continue
        await clickSlot(slot, 0)
        if (action.over) return
      }
      if (window.selectedItem) throw new Error(`expected room to put back ${describeItem(window.selectedItem)} after a rejected click, found none`)
    }

    let src = null
    let rejected = 0
    const seen = new Set()
    while (!action.over) {
      try {
        if (window.selectedItem) await putBack(src)
        if (action.over) return
        const move = nextMove()
        if (!move) break
        const { tgt, n, item } = move
        src = move.src
        if (shiftDoes(src)) {
          await shiftClick(window, src)
          seen.clear()
          continue
        }
        const before = window.slots[tgt]?.count ?? 0
        if (n < Math.min(item.count, item.stackSize - before)) {
          // Part of the stack, the fewest clicks: the whole or half of it (right click) in the hand,
          // then one at a time back onto the source until n are left for the target, or n one at a
          // time onto the target and the rest back.
          const half = Math.ceil(item.count / 2)
          const ways = [
            { button: 0, back: item.count - n },
            { button: 0, onto: n }
          ]
          if (n <= half) ways.push({ button: 1, back: half - n }, { button: 1, onto: n })
          const way = ways.reduce((best, w) => (w.back ?? w.onto) < (best.back ?? best.onto) ? w : best)
          await clickSlot(src, way.button)
          if (action.over) return
          const ones = way.back ?? way.onto
          for (let i = 0; i < ones; i++) {
            await clickSlot(way.back !== undefined ? src : tgt, 1)
            if (action.over) return
          }
          await clickSlot(way.back !== undefined ? tgt : src, 0)
        } else {
          await clickSlot(src, 0)
          if (action.over) return
          await clickSlot(tgt, 0)
          if (action.over) return
          // What does not fit goes back where it came from.
          if (window.selectedItem) await clickSlot(src, 0)
        }
        if (action.over) return
        if (window.selectedItem) throw new Error(`expected an empty cursor after moving ${full(item.name)} from slot ${src} to slot ${tgt}, found ${describeItem(window.selectedItem)}`)
        // On the bot's own slots no one else's clicks add or take.
        const after = window.slots[tgt]
        if (own(tgt) && (!after || !sameItem(after, item) || after.count < before + n)) {
          throw new Error(`expected at least ${before + n} x ${full(item.name)} on slot ${tgt} (${before} there, ${n} moved), found ${describeItem(after)}`)
        }
        seen.clear()
      } catch (e) {
        if (!(e instanceof Rejected)) throw e
        if (action.over) return
        const now = state()
        if (seen.has(now)) throw new Error(`${e.message} on a window as it was at a rejection before, nothing else changing it (moving ${name})`)
        seen.add(now)
        rejected++
        log(`item_fill: ${e.message}, the window changed under it: going on from the server's`)
      }
    }
    if (action.over) return
    const why = moved() > 0 ? '' : sources().length === 0 ? `: no ${name} outside the target slots` : ': no room on the target slots'
    log(`item_fill moved ${moved()} x ${name}${rejected ? ` (${rejected} clicks rejected)` : ''}${why}`)
    done(action)
  }

  // --- item matching

  // A tag of an item's NBT as prismarine-nbt parses it ({ type, value }) the same as another, whatever the
  // order of a compound's keys; undefined (no such tag) only as undefined.
  function sameTag (x, y) {
    if (!x || !y) return x === y
    return x.type === y.type && sameValue(x.type, x.value, y.value)
  }

  function sameValue (type, x, y) {
    if (type === 'compound') {
      const keys = Object.keys(x)
      return keys.length === Object.keys(y).length && keys.every(k => sameTag(x[k], y[k]))
    }
    // A list's value is { type, value: [...] }, each element of the list's type.
    if (type === 'list') return x.type === y.type && x.value.length === y.value.length && x.value.every((v, i) => sameValue(x.type, v, y.value[i]))
    // Byte, int and long arrays, and a long as [high, low]: element by element.
    if (Array.isArray(x)) return Array.isArray(y) && x.length === y.length && x.every((v, i) => String(v) === String(y[i]))
    return x === y
  }

  // An item's NBT as a compound tag; none as an empty one.
  const rootOf = item => item.nbt ?? { type: 'compound', name: '', value: {} }

  // The tag at a path (keys joined by dots) of a compound tag, undefined where nothing is.
  function tagAt (root, path) {
    let tag = root
    for (const key of path.split('.')) {
      if (tag?.type !== 'compound') return undefined
      tag = tag.value[key]
    }
    return tag
  }

  // The compound tag with the tags at paths taken off, the rest as it was.
  function without (root, paths) {
    const value = { ...root.value }
    for (const path of paths) {
      const [key, ...rest] = path.split('.')
      if (!(key in value)) continue
      if (rest.length === 0) delete value[key]
      else if (value[key].type === 'compound') value[key] = without(value[key], [rest.join('.')])
    }
    return { ...root, value }
  }

  // Which items an order means, told from a reference item (spec.nbt, its NBT in hex as the mod has it):
  //   name       that item, by name; none: any item
  //   metadata   that metadata; none: any
  //   match      these paths of the NBT (keys joined by dots) as the reference has them, or both without
  //   ignore     the whole NBT as the reference's but at these paths (exact: none ignored)
  // Neither match nor ignore: the NBT matters not. { test(item), describe() }.
  function itemMatcher (spec, what) {
    if (typeof spec !== 'object' || Array.isArray(spec)) throw new Error(`${what} must be an object (name, metadata, nbt, match, ignore), got ${JSON.stringify(spec)}`)
    const tests = []
    const parts = []
    if (spec.name !== undefined) {
      if (typeof spec.name !== 'string' || !spec.name) throw new Error(`${what}.name must be an item name, got ${spec.name}`)
      const name = bare(spec.name)
      tests.push(item => bare(item.name) === name)
      parts.push(`"${full(name)}"`)
    } else {
      parts.push('any item')
    }
    if (spec.metadata !== undefined) {
      if (!Number.isInteger(spec.metadata)) throw new Error(`${what}.metadata must be an integer, got ${spec.metadata}`)
      tests.push(item => item.metadata === spec.metadata)
      parts.push(`metadata ${spec.metadata}`)
    }
    const paths = (value, field) => {
      if (!Array.isArray(value) || !value.every(p => typeof p === 'string' && p && !p.split('.').includes(''))) {
        throw new Error(`${what}.${field} must list NBT paths (keys joined by dots), got ${JSON.stringify(value)}`)
      }
      return value
    }
    if (spec.match !== undefined && spec.ignore !== undefined) throw new Error(`${what} takes match or ignore, not both`)
    if (spec.match !== undefined || spec.ignore !== undefined) {
      if (typeof spec.nbt !== 'string' || !/^([0-9a-f]{2})*$/i.test(spec.nbt)) throw new Error(`${what}.nbt must be the reference item's NBT in hex, got ${spec.nbt}`)
      const reference = spec.nbt ? nbt.parseUncompressed(Buffer.from(spec.nbt, 'hex')) : rootOf({})
      if (spec.match !== undefined) {
        const match = paths(spec.match, 'match')
        tests.push(item => match.every(p => sameTag(tagAt(rootOf(item), p), tagAt(reference, p))))
        const shown = tag => tag ? JSON.stringify(nbt.simplify(tag)) : 'none'
        if (match.length) parts.push(`with ${match.map(p => `${p} ${shown(tagAt(reference, p))}`).join(', ')}`)
      } else {
        const ignore = paths(spec.ignore, 'ignore')
        const wanted = without(reference, ignore)
        tests.push(item => sameTag(without(rootOf(item), ignore), wanted))
        parts.push(`with NBT ${JSON.stringify(nbt.simplify(wanted))}${ignore.length ? ` but ${ignore.join(', ')}` : ''}`)
      }
    } else if (spec.nbt !== undefined) {
      throw new Error(`${what}.nbt says nothing without match or ignore`)
    }
    const described = parts.join(' ')
    return { test: item => Boolean(item) && tests.every(t => t(item)), describe: () => described }
  }

  // Waits for every slot of a.slots to hold x min or more at once of the item its item matches (see
  // itemMatcher), or of any item without one.
  function waitSlot (action, a) {
    const window = slotWindow()
    const wanted = required(a.slots, 'slots')
    if (!Array.isArray(wanted) || wanted.length === 0) throw new Error('slots must list one or more slots')
    const waits = wanted.map((w, i) => {
      const min = w.min ?? 1
      if (!Number.isInteger(min) || min <= 0) throw new Error(`slots[${i}].min must be a positive integer, got ${min}`)
      const item = itemMatcher(w.item ?? {}, `slots[${i}].item`)
      return { slot: slotOf(window, w.slot, `slots[${i}].slot`), item, min }
    })
    // An output takes as long as it takes: its deadline is the longest of the lot unless the order has one.
    const ms = a.deadline === undefined ? 3600000 : deadlineOf(a)
    const metOne = w => {
      const there = window.slots[w.slot]
      return Boolean(there) && there.count >= w.min && w.item.test(there)
    }
    const describe = () => waits.filter(w => !metOne(w)).map(w => `${w.min} x ${w.item.describe()} on slot ${w.slot}, found ${describeItem(window.slots[w.slot])}`).join('; ')
    if (waits.every(metOne)) {
      done(action)
      return
    }
    listen(action, window, 'updateSlot', () => {
      if (waits.every(metOne)) done(action)
    })
    if (window !== bot.inventory) {
      listen(action, bot, 'windowClose', closed => {
        if (closed === window) fail(action, `the window was closed still missing ${describe()}`)
      })
    }
    deadline(action, ms, () => `missing ${describe()}`)
  }

  // --- D. hand

  // Takes the hotbar slot a.slot, of the window its slots are on, into its hand: done as it is sent,
  // the server confirms no change of hand.
  function hotbar (action, a) {
    const window = slotWindow()
    const slot = slotOf(window, a.slot, 'slot')
    const index = slot - window.hotbarStart
    if (index < 0 || index > 8) throw new Error(`only hotbar items: expected a hotbar slot (${window.hotbarStart}..${window.hotbarStart + 8}), got ${slot}`)
    bot.setQuickBarSlot(index)
    done(action)
  }

  // Picks the trade a.trade of the open villager window, its result worked out from the inputs on it:
  // done as it is sent, the server confirms no pick. A used up trade is picked all the same, as the
  // game does, and fails the primitive: nothing would come of it.
  function selectTradeOf (action, a) {
    const window = openWindow('pick a trade on')
    const type = windowType(window)
    if (type !== 'minecraft:villager') throw new Error(`expected a villager window to pick a trade on, found window "${type}"`)
    const recipes = trades(window)
    if (!recipes) throw new Error('expected the villager\'s trades, the server has not listed them yet')
    const trade = required(a.trade, 'trade')
    if (!Number.isInteger(trade) || trade < 0 || trade >= recipes.length) throw new Error(`trade must be one of the villager's (0..${recipes.length - 1}), got ${trade}`)
    selectTrade(window, trade)
    if (recipes[trade].disabled) {
      fail(action, `expected trade ${trade} in stock, found it used up`)
      return
    }
    done(action)
  }

  // Turns to a.yaw and a.pitch, in degrees as the game has them (yaw 0 south, growing towards west;
  // pitch 90 straight down), at the order's speed: done once the server has seen it get there.
  async function lookAt (action, a) {
    const yaw = required(a.yaw, 'yaw')
    const pitch = required(a.pitch, 'pitch')
    if (!Number.isFinite(yaw)) throw new Error(`yaw must be a number of degrees, got ${a.yaw}`)
    if (!Number.isFinite(pitch) || pitch < -90 || pitch > 90) throw new Error(`pitch must be degrees from -90 to 90, got ${a.pitch}`)
    setTurnSpeed(turnSpeedOf(a))
    action.cleanups.push(() => setTurnSpeed(DEFAULT_TURN_SPEED))
    await bot.look(conv.fromNotchianYaw(yaw), conv.fromNotchianPitch(pitch), false)
    if (!action.over) done(action)
  }

  // Shift-clicks slot a.slot (the open window's, or the inventory's with none): its stack moved where the
  // server puts it (the other part of the window), the window as the server has it after (see
  // syncedClick). An empty slot fails it; the window closed under it too.
  async function shiftMove (action, a) {
    const window = slotWindow()
    listen(action, bot, 'windowClose', closed => {
      if (closed === window) fail(action, 'the window was closed while shift-clicking')
    })
    const slot = slotOf(window, a.slot, 'slot')
    const item = window.slots[slot]
    if (!item) throw new Error(`expected an item on slot ${slot} to shift-click, found none`)
    if (window.selectedItem) throw new Error(`expected an empty cursor to shift-click, found ${describeItem(window.selectedItem)}`)
    await shiftClick(window, slot)
    if (action.over) return
    const left = window.slots[slot]
    log(`shift_move moved ${item.count - (left && sameItem(left, item) ? left.count : 0)} of ${describeItem(item)} off slot ${slot}`)
    done(action)
  }

  // Throws the whole stack of each slot of a.slots (the open window's, or the inventory's with none)
  // where the bot looks, one throw (ctrl+Q) a slot, the window as the server has it after each (see
  // syncedClick). An empty slot is passed by; one still holding its stack after its throw fails it.
  // The window closed under it fails it there.
  async function drop (action, a) {
    const window = slotWindow()
    listen(action, bot, 'windowClose', closed => {
      if (closed === window) fail(action, 'the window was closed while dropping')
    })
    if (!Array.isArray(a.slots) || a.slots.length === 0) throw new Error('slots must list one or more slots')
    const slots = [...new Set(a.slots.map((s, i) => slotOf(window, s, `slots[${i}]`)))]
    if (window.selectedItem) throw new Error(`expected an empty cursor to throw from slots, found ${describeItem(window.selectedItem)}`)
    let dropped = 0
    for (const slot of slots) {
      const item = window.slots[slot]
      if (!item) continue
      await syncedClick(window, slot, 1, 4)
      if (action.over) return
      const left = window.slots[slot]
      if (left && sameItem(left, item) && left.count === item.count) throw new Error(`expected slot ${slot} emptied by its throw, found ${describeItem(left)} still on it`)
      dropped += item.count - (left && sameItem(left, item) ? left.count : 0)
    }
    log(`drop threw ${dropped} items off ${slots.length} slots`)
    done(action)
  }

  // --- E. stop

  // Whatever it was doing gone, as any primitive that comes takes over: a walk stopped, a dig
  // stopped, a wait no longer waited on. Then idle.
  function stop (action) {
    done(action)
  }

  const primitives = { goto, interact, break: breakBlock, place, close_window: closeWindow, move, item_fill: itemFill, wait_slot: waitSlot, hotbar, select_trade: selectTradeOf, look_at: lookAt, drop, shift_move: shiftMove, stop,
    break_wsr: (action, a) => withSearch(action, a, 'break'),
    place_wsr: (action, a) => withSearch(action, a, 'place'),
    break_wsrp: (action, a) => withSearch(action, a, 'break'),
    place_wsrp: (action, a) => withSearch(action, a, 'place')
  }

  // Dying takes what it was doing with it: the primitive canceled by the death, a walk stopped with it.
  bot.on('death', () => {
    if (current) fail(current, 'canceled by death')
  })

  return {
    windowType,
    cancel,
    setAllowedPlace (blocks) {
      allowedPlace = new Set(blocks)
      log(`may place ${allowedPlace.size} blocks`)
    },
    syncedClick,
    // The running primitive's number, for the bot's own lines; 0 for none.
    currentId: () => current ? current.id : 0,

    // An order from the mod: a primitive, run to its end by the events that confirm it. A failure,
    // from the primitive or from what it threw, is the bot's error state, not the process's.
    async run (a) {
      const primitive = primitives[a.action]
      if (!primitive) throw new Error(`unknown action ${a.action} for ${name}`)
      const action = begin(a.action, a)
      try {
        if (!bot.entity) throw new Error('the bot is not in the world yet')
        await primitive(action, a)
      } catch (e) {
        log(e.stack, 'E')
        fail(action, e.message)
      }
    }
  }
}

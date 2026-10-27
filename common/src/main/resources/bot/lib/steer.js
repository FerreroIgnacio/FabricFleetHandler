// Walking by keys, simulated a tick ahead: to a point, along the pathfinder's moves with the head
// kept on a target, a step that stops short of a fall.
const { PlayerState } = require('prismarine-physics')
const { CONTROLS } = require('./util')

// Slower than this the bot is still.
const STILL = 0.005
// The most ticks the bot takes to stop once it lets go of the keys, walking: friction stops it in a few.
const STOP_TICKS = 40
// The keys a step to where it sees the target is made of, as many ways as they go.
const KEY_SETS = [[], ['forward'], ['back'], ['left'], ['right'], ['forward', 'left'], ['forward', 'right'], ['back', 'left'], ['back', 'right']]

module.exports = ctx => {
  const { bot, physicsWorld, aimHead, eyesNext, head, headPoint, lookTo, digTicks, eyeHeight, occupies, seesFrom, fits, listen, movesTo } = ctx

  // The player state as is, its own position and speed, to simulate on from without changing it.
  function copyState (state) {
    const copy = Object.assign(Object.create(Object.getPrototypeOf(state)), state)
    copy.pos = state.pos.clone()
    copy.vel = state.vel.clone()
    copy.control = { ...state.control }
    return copy
  }

  // The keys (of KEY_SETS) whose tick from state takes it nearest to, on the ground; null with none on it.
  function keysTo (state, to) {
    let best = null
    let nearest = Infinity
    for (const keys of KEY_SETS) {
      const s = copyState(state)
      s.control = Object.fromEntries(CONTROLS.map(c => [c, keys.includes(c)]))
      bot.physics.simulatePlayer(s, physicsWorld)
      if (!s.onGround) continue
      const away = Math.hypot(s.pos.x - to.x, s.pos.z - to.z)
      if (away < nearest) {
        best = keys
        nearest = away
      }
    }
    return best
  }

  // The keys (of KEY_SETS) whose tick takes it where it comes to rest (its speed gone,
  // keys up after it) nearest to to, nearer than with none, on the ground in blocks all the way; none
  // (null) at rest with none nearer: as near as it gets.
  function keysWithin (to, blocks) {
    const state = new PlayerState(bot, Object.fromEntries(CONTROLS.map(c => [c, false])))
    // Where it rests after keys, or null when it leaves the ground or block on the way.
    const rest = keys => {
      const s = copyState(state)
      s.control = Object.fromEntries(CONTROLS.map(c => [c, keys.includes(c)]))
      for (let first = true; first || s.vel.x !== 0 || s.vel.z !== 0; first = false) {
        bot.physics.simulatePlayer(s, physicsWorld)
        if (!s.onGround || !blocks.some(b => b.equals(s.pos.floored()))) return null
        s.control = Object.fromEntries(CONTROLS.map(c => [c, false]))
      }
      return Math.hypot(s.pos.x - to.x, s.pos.z - to.z)
    }
    const still = state.vel.x === 0 && state.vel.z === 0
    // Moving still: keys up the first to weigh, nowhere with it leaving block.
    const drift = still ? null : rest([])
    let best = drift === null ? null : []
    let nearest = still ? Math.hypot(state.pos.x - to.x, state.pos.z - to.z) : drift === null ? Infinity : drift
    for (const keys of KEY_SETS) {
      const away = rest(keys)
      if (away !== null && away < nearest) {
        best = keys
        nearest = away
      }
    }
    return best
  }

  // Whether feet are in the move's block, or in any of its cells when it has them (see breakPac's onLine).
  function inMove (m, feet) {
    const f = feet.floored()
    return (m.cells || [m.block]).some(b => b.equals(f))
  }

  // Where the moves take it next, standing at feet: the middle of the first one after the one it is
  // in (following.i on); past the last, that one's, where it stops (with none, following.stand).
  function waypoint (following, feet) {
    const { moves } = following
    const reached = moves.findIndex((m, i) => i >= following.i && inMove(m, feet))
    if (reached !== -1) following.i = reached + 1
    if (following.i < moves.length) return moves[following.i].middle
    return moves.length > 0 ? moves[moves.length - 1].middle : following.stand
  }

  // A tick of following the moves: the keys toward the next (see keysTo), the head kept on the point
  // the break or place turns it to (headPoint) from where the eyes are next tick, on its way there and while
  // the dig is on: aimed from the eyes it started from, it would land off the point the walk moved them
  // from. On its way, only the rest of it moved (see look.Head.aim), no new way once there: a new one
  // each tick the eyes move would never let the turn end; checkLook takes it from there. False,
  // touching nothing, when no keys keep it on the ground (its floor gone).
  function steer (kind, pos, following) {
    const state = new PlayerState(bot, Object.fromEntries(CONTROLS.map(c => [c, false])))
    const keys = keysTo(state, waypoint(following, bot.entity.position))
    if (keys === null) return false
    for (const key of CONTROLS) bot.setControlState(key, keys.includes(key))
    keepHead()
    return true
  }

  // The head kept on the point turned to (headPoint) from where the eyes are on the next tick (see
  // eyesNext), as the bot moves: on its way there and while the dig is on (see steer).
  function keepHead () {
    if (headPoint() && (!head.done || bot.targetDigBlock)) aimHead(lookTo(eyesNext(), headPoint()))
  }

  // Whether following moves from where it is now (see steer), the head turned to the target as
  // the look provider turns it (a copy of the head, see look.Head), keeps it on the ground at its level with the target in sight until the head is
  // on it and the break (its dig time with the tool it will hold) or the place is done; past the last
  // move it stops there.
  function followable (kind, pos, moves) {
    const point = pos.offset(0.5, 0.5, 0.5)
    const ticks = kind === 'break' ? digTicks(bot.blockAt(pos)) : 1
    const y = bot.entity.position.floored().y
    const following = { moves, i: 0, stand: bot.entity.position.clone() }
    const state = new PlayerState(bot, Object.fromEntries(CONTROLS.map(c => [c, false])))
    // The head as the server has it, the rest of its way on (see look.Head); the most random over it
    // (look.NOISE), the longest way: the break or place no later than the bot's does.
    const simulated = head.clone()
    simulated.yaw = bot.entity.yaw
    simulated.pitch = bot.entity.pitch
    let doneTicks = 0
    // The head reaches the target in the widest turn (half round in yaw and in pitch) at most on the
    // slowest way (see look.Head.longest), plus the ticks of the break or place.
    const most = head.longest(Math.hypot(Math.PI, Math.PI)) + ticks + 1
    for (let tick = 0; doneTicks < ticks; tick++) {
      if (tick === most) return false
      const to = waypoint(following, state.pos)
      const look = lookTo(state.pos.offset(0, eyeHeight(false), 0), point)
      state.yaw = look.yaw
      state.pitch = look.pitch
      const keys = keysTo(state, to)
      if (keys === null) return false
      state.control = Object.fromEntries(CONTROLS.map(c => [c, keys.includes(c)]))
      bot.physics.simulatePlayer(state, physicsWorld)
      if (!state.onGround || Math.floor(state.pos.y) !== y) return false
      if (!seesFrom(kind, pos, state.pos) || (kind === 'place' && occupies(state.pos, pos))) return false
      // As steer aims it, then the tick's point.
      simulated.aim(simulated.yaw, simulated.pitch, look, simulated.turnSpeed, () => 1)
      simulated.tick()
      if (simulated.done) doneTicks++
    }
    return true
  }

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

  // The stretch of the pathfinder's path to goal (see movesTo) whose moves see the target from their
  // middle (see seesFrom), at one level and walked alone, nothing broken or placed on them, that is
  // the longest walk; the first of the longest. Each move { block, middle }. None with no path or no
  // move seeing it; undefined when the primitive is replaced on the way.
  async function sightRun (action, kind, pos, goal) {
    const moves = await movesTo(action, goal)
    if (moves === undefined) return undefined
    const sees = m => m.walked && seesFrom(kind, pos, m.middle) && !(kind === 'place' && occupies(m.middle, pos))
    const length = r => r.reduce((sum, m, i) => i === 0 ? 0 : sum + m.middle.distanceTo(r[i - 1].middle), 0)
    let best = []
    let current = []
    for (const m of moves) {
      if (sees(m) && (current.length === 0 || current[0].block.y === m.block.y)) {
        current.push(m)
      } else {
        current = sees(m) ? [m] : []
      }
      if (current.length > 0 && (best.length === 0 || length(current) > length(best))) best = current.slice()
    }
    return best
  }

  return { keysWithin, inMove, steer, keepHead, followable, stepTo, sightRun }
}

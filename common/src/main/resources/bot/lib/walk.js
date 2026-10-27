// Walking to a goal with the pathfinder.
const Move = require('mineflayer-pathfinder/lib/move')
const { FACES, CONTROLS, full, at } = require('./util')

module.exports = ctx => {
  const { bot, log, walk, fail, keysWithin, listen } = ctx

  const stood = () => at({ x: Math.floor(bot.entity.position.x), y: Math.floor(bot.entity.position.y), z: Math.floor(bot.entity.position.z) })

  // Walks to the goal (where, for the errors), then(): once there and landed; there(), when it says so
  // once landed, ends it there without a walk. Failed when the pathfinder finds no path or gives up;
  // with noPath, a path not found is that one's instead, the walk let go.
  function walkTo (action, goal, where, then, there = () => false, noPath = null) {
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
    // Each event its walk's alone: another walk of the same primitive (a wsrpa's search, after its own
    // walk) leaves this one's be.
    // then() once the pathfinder is through with the arrival: it drops its goal right after saying it
    // reached it, a goal set in then() itself (the walk of what comes next) dropped with it, silently.
    listen(action, bot, 'goal_reached', reached => {
      if (reached !== goal) return
      arrived = true
      setImmediate(() => {
        if (!action.over) landed(then)
      })
    })
    // mineflayer-pathfinder can end a walk on its last node with the goal not met there: it stops and
    // waits with its goal set, searching no more and saying nothing (pathUpdated stays set). Told by
    // the bot moving with a path walked and none left; searched again from there, and failed when it
    // ends twice on the same spot.
    let walking = false
    let endedAt = null
    listen(action, bot, 'path_update', result => {
      if (bot.pathfinder.goal !== goal) return
      // Once the pathfinder is through with its update: it marks the path updated after it, a goal
      // set in noPath() itself never searched.
      if (result.status === 'noPath' && noPath) {
        bot.pathfinder.setGoal(null)
        setImmediate(() => {
          if (!action.over) noPath()
        })
        return
      }
      // What it had round it, to tell why: the search's radius, where it stood exactly, its feet's neighbours.
      if (result.status === 'noPath') fail(action, `expected a path to ${where}, found none from ${stood()}${result.reason ? ` (${result.reason})` : ''} (radius ${bot.pathfinder.searchRadius}, at ${bot.entity.position.toString()}${bot.entity.onGround ? '' : ' off the ground'}; round its feet: ${around(bot.entity.position.floored())}; its moves from ${at(searchedFrom)}, where the search started: ${movesFrom(searchedFrom)})`)
      if (result.status === 'success' && result.path.length > 0) walking = true
    })
    // Stuck (the pathfinder's own call: no headway on its path) it searches again from where it is,
    // and can find the same path there for ever (out of water onto a bank): failed when it is stuck
    // twice on the same spot. The first time, the walk let go, it steps by keys to the middle of its
    // block first (see keysWithin), then searches again from there: wedged against a block next to it
    // (sand or gravel fallen onto its path, a corner cut), a path from where it is runs into it again.
    let stuckAt = null
    listen(action, bot, 'path_reset', reason => {
      walking = false
      if (bot.pathfinder.goal !== goal || reason !== 'stuck') return
      const here = stood()
      if (stuckAt === here) {
        fail(action, `expected to arrive at ${where}, the walk got stuck at ${here} twice`)
        return
      }
      stuckAt = here
      const feet = bot.entity.position.floored()
      const middle = feet.offset(0.5, 0, 0.5)
      bot.pathfinder.setGoal(null)
      bot.clearControlStates()
      bot.jumpQueued = false
      action.cleanups.push(() => { if (!bot._client.ended) bot.clearControlStates() })
      listen(action, bot, 'physicsTick', function onTick () {
        const keys = keysWithin(middle, [feet])
        if (keys !== null) {
          for (const key of CONTROLS) bot.setControlState(key, keys.includes(key))
          return
        }
        bot.removeListener('physicsTick', onTick)
        bot.clearControlStates()
        log(`goto: stuck at ${here}, stepped to ${bot.entity.position.toString()}: searching again`)
        searchedFrom = bot.entity.position.floored()
        bot.pathfinder.setGoal(goal)
      })
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
    listen(action, bot, 'path_stop', () => bot.pathfinder.goal === goal && fail(action, `expected to arrive at ${where}, the walk was stopped at ${stood()}`))
    // Where the bot stood as the search started, for its error.
    let searchedFrom = bot.entity.position.floored()
    listen(action, bot, 'path_reset', () => { searchedFrom = bot.entity.position.floored() })
    landed(() => {
      if (there()) {
        arrived = true
        then()
        return
      }
      searchedFrom = bot.entity.position.floored()
      walk(goal)
    })
  }

  // The pathfinder's moves from the block feet, as its search makes them (each to x,y,z at its cost), for an error.
  function movesFrom (feet) {
    const movements = bot.pathfinder.movements
    movements.clearCollisionIndex()
    movements.updateCollisionIndex()
    const moves = movements.getNeighbors(new Move(feet.x, feet.y, feet.z, movements.countScaffoldingItems(), 0))
    return moves.length === 0 ? 'none' : moves.map(m => `${at(m)} at ${m.cost}`).join(', ')
  }

  // The blocks next to pos, each with its collision box kind, for an error: what the place had to go against.
  function around (pos) {
    return FACES.map(face => {
      const b = bot.blockAt(pos.plus(face))
      return `${at(pos.plus(face))} ${b ? `${full(b.name)} (${b.boundingBox})` : 'not loaded'}`
    }).join(', ')
  }

  return { stood, walkTo, around }
}

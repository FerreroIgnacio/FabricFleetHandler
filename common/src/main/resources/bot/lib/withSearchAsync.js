// break_wsrpa and place_wsrpa: on the way to end.
const { Vec3 } = require('vec3')
const { goals } = require('mineflayer-pathfinder')
const { at, required } = require('./util')

module.exports = ctx => {
  const { bot, log, walk, breakBlock, checkTarget, done, followable, guard, listen, occupies, place, searchWithin, seesFrom, show, sightRun, steer, stood, targetOf, walkTo, withSearch } = ctx

  // break_wsrpa and place_wsrpa: walks to the block end as goto, breaking or placing on the way. Of the
  // pathfinder's path there, every move (see sightRun), it does it on the stretch it has the target in
  // sight the longest on, at one level and walked alone: once in its first block with the target in
  // sight (see seesFrom), when following the rest of the stretch by keys alone, the head on the target,
  // keeps it on the ground with the target in sight until the head gets there and the break (its dig
  // time) or the place is done (see followable), the pathfinder lets go of it and it follows the
  // stretch so (see steer) as it breaks or places, stopping at its last move while not done; that
  // confirmed, the pathfinder takes it on to end. It walks to the stretch's first block first, the
  // pathfinder's own path cutting corners past it otherwise, and on to end from there when it did not
  // take over on the way. Arrived at end without it, as break_wsrp and place_wsrp from there. a is theirs.
  async function withSearchAsync (action, a, kind) {
    const pos = targetOf(a)
    checkTarget(kind, pos)
    const end = new Vec3(required(a.end, 'end').x, a.end.y, a.end.z)
    const goal = new goals.GoalBlock(end.x, end.y, end.z)
    searchWithin(action, required(a.radius, 'radius'))
    show(action, { phase: 'searching', aim: null, takeover: null })
    const run = await sightRun(action, kind, pos, goal)
    if (run === undefined) return
    // A place with no stretch in sight: placed from a spot in sight, then on to end (see withSearch),
    // not walked to end first. end may stand on the block placed (a step's top), there only once it
    // is: walked to before, it drops into the spot and the server takes no block there.
    if (kind === 'place' && run.length === 0) {
      log(`${action.primitive}: no stretch on the way to ${at(end)} with the target in sight: placing from a spot in sight, then on to it`)
      await withSearch(action, a, kind)
      return
    }
    show(action, { phase: 'walking', run: run.map(m => m.block) })
    if (run.length > 0) log(`${action.primitive}: target in sight the longest from ${at(run[0].block)} to ${at(run[run.length - 1].block)} on the way to ${at(end)}`)
    action.cleanups.push(() => { if (!bot._client.ended) bot.clearControlStates() })
    // The moves followed while it breaks or places; settled once it did, or arrived without it.
    let following = null
    let settled = false
    // Fell off the stretch while following it: waiting to land to start again from there.
    let fallen = false
    // The goal walked to: the stretch's first block, then end.
    const first = run.length > 0 ? new goals.GoalBlock(run[0].block.x, run[0].block.y, run[0].block.z) : null
    let walking = first ?? goal
    // Arrived at the stretch's first block: a tick there to take over standing, then on to end. Not
    // on its arrival itself: the pathfinder drops any goal set as it says it reached one.
    let atFirst = false
    listen(action, bot, 'physicsTick', () => {
      if (settled) return
      if (fallen) {
        if (!bot.entity.onGround) return
        settled = true
        log(`${action.primitive}: landed at ${stood()} off its stretch: again from here`)
        if (kind === 'break' && bot.blockAt(pos).boundingBox === 'empty') {
          walkTo(action, goal, at(end), () => done(action))
          return
        }
        guard(action, withSearchAsync(action, a, kind))
        return
      }
      if (following) {
        if (steer(kind, pos, following)) return
        // Its floor gone under it (dug, by another bot): the break or place let go, it lands first.
        log(`${action.primitive}: off the ground at ${bot.entity.position.toString()} following its stretch: letting go until it lands`)
        show(action, { phase: 'falling', aim: null, takeover: null })
        for (const cleanup of action.cleanups.splice(following.mark)) cleanup()
        action.then = null
        following = null
        fallen = true
        bot.clearControlStates()
        return
      }
      if ((bot.pathfinder.goal === walking || atFirst) && takeOver()) return
      if (!atFirst) return
      atFirst = false
      walking = goal
      toEnd()
    })
    // Takes over from the pathfinder when on the ground in a block of the stretch with the target in
    // sight and followable from there; whether it did.
    function takeOver () {
      if (!bot.entity.onGround) return false
      const into = run.findIndex(m => m.block.equals(bot.entity.position.floored()))
      if (into === -1) return false
      if (!seesFrom(kind, pos, bot.entity.position) || (kind === 'place' && occupies(bot.entity.position, pos))) return false
      const moves = run.slice(into + 1)
      if (!followable(kind, pos, moves)) return false
      log(`${action.primitive}: target in sight at ${stood()}, ${kind === 'break' ? 'breaking' : 'placing'} it walking on`)
      show(action, { phase: 'following', takeover: bot.entity.position.floored() })
      // What the break or place sets up is let go once confirmed, and the pathfinder takes it on.
      const mark = action.cleanups.length
      following = { moves, i: 0, stand: bot.entity.position.clone(), mark }
      bot.pathfinder.setGoal(null)
      // A jump the pathfinder pressed stays queued with its keys up (mineflayer's jumpQueued): taken
      // on the next tick, it would leave the moves in the air.
      bot.jumpQueued = false
      steer(kind, pos, following)
      action.then = () => {
        for (const cleanup of action.cleanups.splice(mark)) cleanup()
        show(action, { phase: 'walking', aim: null })
        settled = true
        following = null
        if (walking === goal) {
          walk(goal)
          return
        }
        walking = goal
        walkTo(action, goal, at(end), () => done(action), atEnd)
      }
      // The target lost from sight as it turned walking on: let go, on to end, and as the break from there.
      const lost = () => {
        for (const cleanup of action.cleanups.splice(mark)) cleanup()
        show(action, { phase: 'walking', aim: null, takeover: null })
        action.then = null
        following = null
        bot.clearControlStates()
        walking = goal
        toEnd()
      }
      guard(action, kind === 'break' ? breakBlock(action, { ...a, lost }) : place(action, a))
      return true
    }
    // Already in end's block (by its floored feet, as the pathfinder's goal has it): there without a
    // walk, not a tick waiting on the pathfinder to say so.
    const atEnd = () => bot.entity.position.floored().equals(end)
    const toEnd = () => walkTo(action, goal, at(end), () => {
      if (settled) {
        done(action)
        return
      }
      settled = true
      log(`${action.primitive}: at ${at(end)} without the target in sight on the way: as ${action.primitive.slice(0, -1)} from here`)
      guard(action, withSearch(action, a, kind))
    }, atEnd)
    if (!first) {
      toEnd()
      return
    }
    // Already in the stretch's first block: taken over at once, not a walk there and a tick after it.
    if (takeOver()) return
    // No path to the stretch any more (the world dug round it on the way): on to end, and from
    // there as the primitive without its a (see toEnd).
    walkTo(action, first, at(run[0].block), () => {
      if (!following && !settled) atFirst = true
    }, () => false, () => {
      if (following || settled) return
      log(`${action.primitive}: no path to ${at(run[0].block)}, the stretch with the target in sight, any more from ${stood()}: on to ${at(end)}`)
      walking = goal
      toEnd()
    })
  }

  return { withSearchAsync }
}

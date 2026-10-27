const { Vec3 } = require('vec3')
const { goals } = require('mineflayer-pathfinder')
const { REACH_BLOCK, FACES, CONTROLS, full, at, required } = require('../lib/util')

module.exports = ctx => {
  const { bot, log, walk, aimsAt, breakBlock, checkTarget, done, eyeHeight, eyes, fail, followable, guard, inMove, inReach, keepHead, keysWithin, listen, lookTo, movesTo, seesFrom, show, steer, stood, walkTo, drawn } = ctx

  // break_pac: breaks the blocks of targets one after another in that order, walking to the block end
  // as goto and breaking on the way, no search: on the pathfinder's path there (every move, see
  // movesTo), on the ground in a block of it with the next one in sight and its break followable on
  // the moves after it (see followable), the pathfinder lets go and it follows them by keys (see
  // steer) as it breaks, then the next one from where it got to the same way; with none, the
  // pathfinder takes it on to end. The ones left at end are broken from there, standing. Failed at
  // once when one is not a block or out of reach from end wherever in it the feet end (the eyes
  // standing over any of its corners, the farthest a distance to a box gets over the block; whatever
  // is in the way), and when one left at end is out of sight from there. Each break's deadline is
  // break's, from when it starts. With stand, it walks on stand's line across (see lineOf), off it into a
  // column only for one out of sight from it; some left at end, it
  // steps by keys to the point of end or the block next to it across nearest to that one's on stand's
  // line across (see keysWithin) as it breaks them. Done with them all gone and the bot at end,
  // or with stand next to it across (on the line of the last one). a is { targets: [{x, y, z}], end,
  // stand: {x, z}, deadline, turn_speed }.
  async function breakPac (action, a) {
    const targets = required(a.targets, 'targets').map(t => new Vec3(t.x, t.y, t.z))
    const end = new Vec3(required(a.end, 'end').x, a.end.y, a.end.z)
    const stand = a.stand ? new Vec3(a.stand.x, end.y, a.stand.z) : null
    if (stand) stand.line = a.stand.line
    const corners = [0, 1].flatMap(u => [0, 1].map(v => end.offset(u, eyeHeight(false), v)))
    for (const pos of targets) {
      const block = checkTarget('break', pos)
      const far = corners.find(from => !inReach(block, from))
      if (far) throw new Error(`expected ${full(block.name)} at ${at(pos)} in reach from anywhere in ${at(end)}, found it past ${REACH_BLOCK} blocks from the eyes at ${far.toString()}`)
    }
    // With stand (end on the seam of a 2 wide band, see Quarrify's pac), the moves at end's level in a
    // column of the band, the one next to it across open at the feet and the head and floored, are
    // followed on line, across: on the seam (see lineOf). Such
    // a move has the bot in it in either column (cells).
    const across = stand && (Number.isInteger(stand.x) ? 'x' : 'z')
    const along = stand && (across === 'x' ? 'z' : 'x')
    const seam = stand && stand[across]
    // The line of the one of targets breaking or to break next, laid once as it becomes next: the seam.
    // Out of sight from it (the line across in the other column, a block of that one in the way), it
    // is moved into the one's own column, as near as it lets it: its column broken up to it, the eyes
    // in it see it along it.
    let laid = null
    const lineOf = () => {
      if (laid === null || laid.i !== next) {
        const pos = targets[next]
        let line = stand.line
        const feet = bot.entity.position.clone()
        feet[across] = line
        if ((pos[across] === seam - 1 || pos[across] === seam) && !seesFrom('break', pos, feet)) {
          const half = bot.entity.width / 2
          line = Math.min(Math.max(line, pos[across] + half), pos[across] + 1 - half)
        }
        laid = { i: next, line }
      }
      return laid.line
    }
    // Where at end it breaks the next one from: stand moved across to its line.
    const standOf = () => {
      const to = stand.clone()
      to[across] = lineOf()
      return to
    }
    // Whether its feet are in a column of the band at end's level with the block next to it across
    // open and floored: on a move that has cells (see onLine), where it gets across to the line.
    const inBand = () => {
      const feet = bot.entity.position.floored()
      return feet.y === end.y && onLine([{ block: feet, middle: feet.offset(0.5, 0, 0.5) }], seam)[0].cells !== undefined
    }
    // The block next to the block across the seam when it is at end's level in a column of the band
    // and that one is open at the feet and the head and floored; else null.
    const paired = block => {
      if (block.y !== end.y || (block[across] !== seam - 1 && block[across] !== seam)) return null
      const other = block.clone()
      other[across] = block[across] === seam ? seam - 1 : seam
      const open = dy => bot.blockAt(other.offset(0, dy, 0))?.boundingBox === 'empty'
      return open(0) && open(1) && bot.blockAt(other.offset(0, -1, 0))?.boundingBox === 'block' ? other : null
    }
    // The blocks it is at end in: end, and with stand the one next to it across, open and floored.
    const ends = () => {
      if (!stand) return [end]
      const other = paired(end)
      return other ? [end, other] : [end]
    }
    const onLine = (found, line) => {
      if (!stand || found === undefined) return found
      return found.map(m => {
        const other = paired(m.block)
        if (!other) return m
        const middle = m.middle.clone()
        middle[across] = line
        return { ...m, middle, cells: [m.block, other] }
      })
    }
    // The goal to end: with stand, end or the one next to it across (see ends), the walk on the seam.
    class EndGoal extends goals.GoalBlock {
      isEnd (node) {
        return ends().some(b => b.x === node.x && b.y === node.y && b.z === node.z)
      }
    }
    const endGoal = () => stand ? new EndGoal(end.x, end.y, end.z) : new goals.GoalBlock(end.x, end.y, end.z)
    let moves = await movesTo(action, new goals.GoalBlock(end.x, end.y, end.z))
    if (moves === undefined) return
    action.cleanups.push(() => { if (!bot._client.ended) bot.clearControlStates() })
    // The one of targets breaking or to break next.
    let next = 0
    // The goal walked to end by, a new one after a fall; walked: walkTo has it, walk() takes it on again.
    let goal = endGoal()
    let walked = false
    let arrived = false
    let breaking = false
    // The moves followed while it breaks (see steer); fell off them: waiting to land to start again from there.
    let following = null
    let fallen = false
    let landedAt = null
    // In end's block, or with stand the one next to it across (see ends).
    const atEnd = () => ends().some(b => b.equals(bot.entity.position.floored()))
    listen(action, bot, 'physicsTick', () => {
      if (fallen) {
        if (!bot.entity.onGround) return
        fallen = false
        // Off its moves twice onto the same spot, again from there would be the same for ever: failed.
        const here = stood()
        if (landedAt === here) {
          fail(action, `expected to follow its moves to ${at(end)}, fell off them onto ${here} twice`)
          return
        }
        landedAt = here
        log(`${action.primitive}: landed at ${here} off its moves: again from here`)
        guard(action, again())
        return
      }
      if (following) {
        // On the line of the one it breaks now, the seam (see lineOf); past its moves
        // (see waypoint), it stays where it took over moved across to it.
        if (stand) {
          const line = lineOf()
          following.moves = onLine(following.raw, line)
          if (inBand()) following.stand[across] = line
        }
        if (steer('break', targets[next], following)) return
        // Its floor gone under it (dug, by another bot or by its own break): the break let go, it lands first.
        log(`${action.primitive}: off the ground at ${bot.entity.position.toString()} following its moves: letting go until it lands`)
        show(action, { phase: 'falling', aim: null, takeover: null })
        for (const cleanup of action.cleanups.splice(following.mark)) cleanup()
        action.then = null
        following = null
        breaking = false
        fallen = true
        bot.clearControlStates()
        return
      }
      // Not in end's block: arrived, the pathfinder's goal_reached breaks the rest from there (see arrive).
      if (next < targets.length && !breaking && !arrived && walked && bot.pathfinder.goal === goal && !atEnd()) takeOver()
    })
    // Takes over from the pathfinder when on the ground in a block of its moves with the next one in
    // sight and its break followable from there; whether it did.
    function takeOver () {
      if (!bot.entity.onGround) return false
      const pos = targets[next]
      // On its line, with the world as it is now (the columns next to the moves opened by the breaks so far).
      const lined = stand ? onLine(moves, lineOf()) : moves
      const into = lined.findIndex(m => inMove(m, bot.entity.position))
      if (into === -1 || !seesFrom('break', pos, bot.entity.position)) return false
      const rest = lined.slice(into + 1)
      if (!followable('break', pos, rest)) return false
      const raw = moves.slice(into + 1)
      log(`${action.primitive}: ${at(pos)} in sight at ${stood()}, breaking it walking on`)
      show(action, { phase: 'following', takeover: bot.entity.position.floored() })
      const mark = action.cleanups.length
      following = { moves: rest, raw, i: 0, stand: bot.entity.position.clone(), mark }
      bot.pathfinder.setGoal(null)
      // The pathfinder's jump queued let go too (see withSearchAsync's takeOver).
      bot.jumpQueued = false
      steer('break', pos, following)
      breakNext(mark)
      return true
    }
    // The next one's break, what it sets up let go from mark on once confirmed; then the one after it.
    function breakNext (mark) {
      if (arrived) leastTurn()
      const pos = targets[next]
      breaking = true
      show(action, { target: pos })
      action.then = () => {
        for (const cleanup of action.cleanups.splice(mark)) cleanup()
        show(action, { phase: 'walking', aim: null, takeover: null })
        breaking = false
        following = null
        next++
        // All gone at end, or next to it across on the last one's line: there.
        if (next === targets.length && (arrived || (bot.entity.onGround && atEnd()))) {
          done(action)
          return
        }
        if (arrived) {
          breakNext(action.cleanups.length)
          return
        }
        // On the way: the next one taken over at once from where it is, else on to end.
        if (next < targets.length && takeOver()) return
        bot.clearControlStates()
        toEnd()
      }
      // Out of sight from where it walked on to as it turned: let go, on to end, and again from there.
      const lost = () => {
        for (const cleanup of action.cleanups.splice(mark)) cleanup()
        show(action, { phase: 'walking', aim: null, takeover: null })
        action.then = null
        breaking = false
        following = null
        bot.clearControlStates()
        toEnd()
      }
      guard(action, breakBlock(action, { ...a, target: pos, lost: arrived ? undefined : lost }))
    }
    // Standing at end: of the ones left in sight from the eyes, the one the head turns the least to
    // swapped in as next (the order given only where none of them is in sight but it).
    function leastTurn () {
      const from = eyes()
      const { yaw, pitch } = bot.entity
      let best = -1
      let bestTurn = Infinity
      for (let i = next; i < targets.length; i++) {
        const block = bot.blockAt(targets[i])
        if (!block || block.boundingBox === 'empty') continue
        if (aimsAt(block, from, FACES, true).aims.length === 0) continue
        // To its middle: the aim's own point picked by blockAim once it is next.
        const to = lookTo(from, targets[i].offset(0.5, 0.5, 0.5))
        const turn = Math.hypot(Math.atan2(Math.sin(to.yaw - yaw), Math.cos(to.yaw - yaw)), to.pitch - pitch)
        if (turn < bestTurn) {
          best = i
          bestTurn = turn
        }
      }
      if (best > next) [targets[next], targets[best]] = [targets[best], targets[next]]
    }
    function toEnd () {
      if (walked) {
        walk(goal)
        return
      }
      walked = true
      walkTo(action, goal, at(end), arrive, atEnd)
    }
    function arrive () {
      arrived = true
      bot.clearControlStates()
      if (stand && next < targets.length) toStand()
      settle()
    }
    // To the next one's stand (see standOf) by keys, a tick at a time, while one takes it nearer
    // staying at end (see ends), the head kept on its point as it moves (see keepHead).
    function toStand () {
      // The stand it came to rest nearest to, for the log once.
      let rested = null
      listen(action, bot, 'physicsTick', () => {
        if (next === targets.length) return
        const to = standOf()
        const keys = keysWithin(to, ends())
        for (const key of CONTROLS) bot.setControlState(key, keys !== null && keys.includes(key))
        keepHead()
        if (keys !== null || (rested !== null && rested.equals(to))) return
        rested = to
        log(`${action.primitive}: at ${bot.entity.position.toString()} in ${stood()}, the nearest to ${to.toString()}`)
      })
    }
    function settle () {
      if (next === targets.length) {
        done(action)
        return
      }
      log(`${action.primitive}: at ${at(end)} with ${targets.length - next} left: breaking them from here`)
      breakNext(action.cleanups.length)
    }
    // Landed off its moves: a path to end from here, walked anew.
    async function again () {
      const found = await movesTo(action, new goals.GoalBlock(end.x, end.y, end.z))
      if (found === undefined) return
      moves = found
      goal = endGoal()
      walked = false
      toEnd()
    }
    // With stand, the pathfinder's path to end walked on the seam: each node in a column of the band
    // with the one next to it across open and floored (see paired) moved across onto the seam, up to
    // the first that breaks or places. The pathfinder walks to the nodes as they are, its checks its own.
    if (stand) {
      listen(action, bot, 'path_update', result => {
        if (bot.pathfinder.goal !== goal) return
        for (const node of result.path) {
          if (node.toBreak.length > 0 || node.toPlace.length > 0) break
          if (paired(new Vec3(node.x, node.y, node.z).floored())) node[across] = seam
        }
      })
    }
    show(action, { phase: 'walking' })
    if (!atEnd() && takeOver()) return
    toEnd()
  }

  return drawn(breakPac, 'break')
}

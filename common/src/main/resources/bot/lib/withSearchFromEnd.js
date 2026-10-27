// break_wsrp and place_wsrp: from end, or as far as it gets on the way there.
const { Vec3 } = require('vec3')
const { goals } = require('mineflayer-pathfinder')
const Move = require('mineflayer-pathfinder/lib/move')
const { at, required } = require('./util')

module.exports = ctx => {
  const { bot, log, checkTarget, show, targetOf, walkTo, withSearch, afterMovements, searched, stood } = ctx

  // break_wsrp and place_wsrp: walks to the block end first, or, with no path there, as far as it can
  // on the path there the break or the place opens (see furthestAfter); there breaks or places as
  // break_wsr and place_wsr, then walks on to end. a is withSearch's with end.
  async function withSearchFromEnd (action, a, kind) {
    const pos = targetOf(a)
    checkTarget(kind, pos)
    const end = new Vec3(required(a.end, 'end').x, a.end.y, a.end.z)
    const there = await furthestAfter(action, kind, pos, end)
    if (there === undefined) return
    if (!there.equals(end)) show(action, { partial: there })
    if (!there.equals(bot.entity.position.floored())) {
      show(action, { phase: 'walking' })
      if (!there.equals(end)) log(`${action.primitive}: no path to ${at(end)} before the ${kind}, walking to ${at(there)}, as far as it goes on the path after it`)
      await new Promise(resolve => walkTo(action, new goals.GoalBlock(there.x, there.y, there.z), at(there), resolve))
      if (action.over) return
    }
    await withSearch(action, a, kind)
  }

  // The block end when the pathfinder finds a path there; else the last block of the path there with
  // the target broken or placed (see afterMovements) it walks to as the world is: each of its moves one
  // the pathfinder makes from the one before, the first from where the bot stands. Failed when there
  // is no path there either; undefined when the primitive is replaced on the way.
  async function furthestAfter (action, kind, pos, end) {
    const goal = new goals.GoalBlock(end.x, end.y, end.z)
    const now = await searched(action, bot.pathfinder.getPathFromTo(bot.pathfinder.movements, bot.entity.position, goal))
    if (now === undefined) return undefined
    if (now.status === 'success') return end
    // Unoptimized: every move, each one the pathfinder's neighbour of the one before.
    const after = await searched(action, bot.pathfinder.getPathFromTo(afterMovements(kind, pos), bot.entity.position, goal, { optimizePath: false }))
    if (after === undefined) return undefined
    if (after.status !== 'success') throw new Error(`expected a path from ${stood()} to ${at(end)} once ${kind === 'break' ? 'broken' : 'placed'} ${at(pos)}, found none`)
    const base = bot.pathfinder.movements
    const feet = bot.entity.position.floored()
    let from = new Move(feet.x, feet.y, feet.z, base.countScaffoldingItems(), 0)
    for (const move of after.path) {
      if (!base.getNeighbors(from).some(n => n.x === move.x && n.y === move.y && n.z === move.z)) break
      from = move
    }
    return new Vec3(from.x, from.y, from.z)
  }

  return { withSearchFromEnd }
}

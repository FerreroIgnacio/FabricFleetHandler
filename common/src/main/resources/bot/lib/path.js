// The pathfinder's paths, searched a tick's share at a time, and the world as the physics sees it.
const { Vec3 } = require('vec3')
const { goals } = require('mineflayer-pathfinder')

module.exports = ctx => {
  const { bot } = ctx

  const physicsWorld = { getBlock: p => bot.blockAt(p, false) }

  // The result of a path search, run a tick's share at a time; undefined when the primitive is replaced on the way.
  async function searched (action, search) {
    for (;;) {
      const result = search.next().value.result
      if (result.status !== 'partial') return result
      await new Promise(resolve => setImmediate(resolve))
      if (action.over) return undefined
    }
  }

  // The pathfinder's path to goal, unoptimized: every move, as its search makes them, from the block
  // it stands in. Each move { block, middle, walked: nothing broken or placed on it }. None with no
  // path; undefined when the primitive is replaced on the way.
  async function movesTo (action, goal) {
    const result = await searched(action, bot.pathfinder.getPathFromTo(bot.pathfinder.movements, bot.entity.position, goal, { optimizePath: false }))
    if (result === undefined) return undefined
    if (result.status !== 'success') return []
    const feet = bot.entity.position.floored()
    // Its feet's block a move of it only with a floor: crouched over an edge (a place's pose) it is
    // held up by the block next to it, and walked in it falls.
    const floored = bot.blockAt(feet.offset(0, -1, 0))?.boundingBox === 'block'
    return (floored ? [{ x: feet.x, y: feet.y, z: feet.z, toBreak: [], toPlace: [] }] : []).concat(result.path).map(m => ({
      block: new Vec3(m.x, m.y, m.z),
      middle: new Vec3(m.x + 0.5, m.y, m.z + 0.5),
      walked: m.toBreak.length === 0 && m.toPlace.length === 0
    }))
  }

  // Whether the pathfinder finds a path from where the bot stands to the block end with the block at
  // pos as the break or the place leaves it: air, or a whole block. Undefined when the primitive is
  // replaced on the way.
  async function reachableAfter (action, kind, pos, end) {
    const result = await searched(action, bot.pathfinder.getPathFromTo(afterMovements(kind, pos), bot.entity.position, new goals.GoalBlock(end.x, end.y, end.z)))
    return result === undefined ? undefined : result.status === 'success'
  }

  // The pathfinder's moves with the block at pos as the break or the place leaves it: air, or a whole block.
  function afterMovements (kind, pos) {
    const Block = require('prismarine-block')(bot.registry)
    const after = new Block(bot.registry.blocksByName[kind === 'break' ? 'air' : 'stone'].id, 0, 0)
    after.position = pos
    const world = Object.create(bot)
    world.blockAt = (p, extra) => p.equals(pos) ? after : bot.blockAt(p, extra)
    const movements = Object.create(bot.pathfinder.movements)
    movements.bot = world
    return movements
  }

  return { physicsWorld, searched, movesTo, reachableAfter, afterMovements }
}

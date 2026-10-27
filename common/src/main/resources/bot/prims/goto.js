const { Vec3 } = require('vec3')
const { goals } = require('mineflayer-pathfinder')
const { REACH_BLOCK, FACES, at, required } = require('../lib/util')

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
// The farthest the eyes get from the middle of the block the feet are in, sideways.
const SLACK = Math.SQRT1_2

module.exports = ctx => {
  const { bot, log, aimsAt, done, eyes, stood, walkTo } = ctx

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

  return goto
}

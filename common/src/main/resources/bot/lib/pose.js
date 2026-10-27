// Where the bot may stand in a block, and whether it sees a target from there.
const { Vec3 } = require('vec3')
const { FACES } = require('./util')

// Where in the block it stands on break_wsr and place_wsr put the feet, along x and along z: a bit off
// the middle, and out over its edges, 0.1 of its box left on the block: the eyes past the edge, to
// see down by it.
const OFFSETS = [0.5, 0.3, 0.7, 0, 1, -0.2, 1.2]
// Further out over its edges crouched, 0.05 of its box left on the block: sneaking keeps it from falling.
const CROUCH_OFFSETS = [-0.25, 1.25]

module.exports = ctx => {
  const { bot, aimsAt, eyeHeight } = ctx

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

  // Whether the bot's box with its feet there takes some of the block at pos.
  function occupies (feet, pos) {
    const half = bot.entity.width / 2
    return feet.x + half > pos.x && feet.x - half < pos.x + 1 && feet.y + bot.entity.height > pos.y && feet.y < pos.y + 1 && feet.z + half > pos.z && feet.z - half < pos.z + 1
  }

  return { posesIn, poseSees, fits, seesFrom, occupies }
}

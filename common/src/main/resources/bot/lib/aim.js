// Where the eyes are and what they see: the rays from them, the faces of a block in reach and in
// sight, the point of one to click and where the head lands turned to it.
const look = require('../look')
const { Vec3 } = require('vec3')
const { PlayerState } = require('prismarine-physics')
const { REACH_BLOCK, FACES, CONTROLS, full, at } = require('./util')

const SIDES = ['bottom', 'top', 'east', 'west', 'south', 'north']
const sideOf = face => SIDES[FACES.findIndex(f => f.equals(face))]
const AXES = ['x', 'y', 'z']
// The face a ray enters a block by, by prismarine-world's BlockFace (bottom, top, north, south, west, east).
const ENTRY_FACES = [new Vec3(0, -1, 0), new Vec3(0, 1, 0), new Vec3(0, 0, -1), new Vec3(0, 0, 1), new Vec3(-1, 0, 0), new Vec3(1, 0, 0)]
// Each face of a block is aimed at in a GRID x GRID grid of points.
const GRID = 8
// How far the look provider's miss goes along each axis of a face at most, in blocks: 3/5 of one.
const MISS_BOX = 0.6
// The eyes crouched, as 1.12 has them: 0.08 under standing.
const SNEAK_EYE_HEIGHT = 1.54

module.exports = ctx => {
  const { bot, physicsWorld } = ctx

  // The speed to turn to (at point) at, of speed for a box a cell of the face's grid wide (1 / GRID),
  // faster the wider it is: as Fitts's law has it, the turn's time going with log2(turn / width + 1),
  // the width the box's narrowest side as the eyes see it.
  function turnSpeedTo (speed, to, point, box) {
    const turn = Math.hypot(Math.atan2(Math.sin(to.yaw - bot.entity.yaw), Math.cos(to.yaw - bot.entity.yaw)), to.pitch - bot.entity.pitch)
    if (turn === 0) return speed
    const distance = eyes().distanceTo(point)
    const seen = width => 2 * Math.atan(width / 2 / distance)
    const sides = AXES.map(k => box[1][k] - box[0][k]).filter(side => side > 0)
    const difficulty = width => Math.log2(turn / seen(width) + 1)
    return speed * difficulty(1 / GRID) / difficulty(Math.min(...sides))
  }

  // The yaw and pitch from the eyes at from to point, as bot.lookAt has them but from those eyes (its
  // own are standing ones, crouched or not).
  function lookTo (from, point) {
    const delta = point.minus(from)
    return { yaw: Math.atan2(-delta.x, -delta.z), pitch: Math.atan2(delta.y, Math.sqrt(delta.x * delta.x + delta.z * delta.z)) }
  }

  // Where the head ends turning from the eyes at from to point: the look provider turns it by whole
  // LOOK_STEPs from where it is (see look.landing), so the ray it ends on is off the point by up to
  // half of one each way.
  function landingDir (from, point) {
    const { yaw, pitch } = look.landing({ yaw: bot.entity.yaw, pitch: bot.entity.pitch }, lookTo(from, point))
    return dirOf(yaw, pitch)
  }

  function dirOf (yaw, pitch) {
    return new Vec3(-Math.sin(yaw) * Math.cos(pitch), Math.sin(pitch), -Math.cos(yaw) * Math.cos(pitch))
  }

  // The first block the ray from the eyes at from along dir gets to, with the face it enters the
  // block at pos by (null when it gets to another).
  function rayInto (pos, shapes, from, dir, distance) {
    let entered = null
    let point = null
    const hit = bot.world.raycast(from, dir, distance, (b, ray) => {
      if (b.position.equals(pos)) {
        const into = ray.intersect(shapes, pos)
        entered = into && ENTRY_FACES[into.face]
        point = into && into.pos
        return Boolean(into)
      }
      return Boolean(ray.intersect(b.shapes, b.position))
    })
    return { hit, entered, point }
  }

  function eyes () {
    return bot.entity.position.offset(0, eyeHeight(bot.getControlState('sneak')), 0)
  }

  // The eyes on the next tick, its keys as they are: where they are when the head gets to the point
  // turned to then. The head's point of a tick is laid on the one before it, the bot moving in
  // between; laid from the eyes as they are, walking, it lands off the point by that tick's move
  // (checkLook: moved while turning, aimed again).
  function eyesNext () {
    const state = new PlayerState(bot, Object.fromEntries(CONTROLS.map(c => [c, bot.getControlState(c)])))
    bot.physics.simulatePlayer(state, physicsWorld)
    return state.pos.offset(0, eyeHeight(state.control.sneak), 0)
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
  // eyes, in reach and in sight, the least turn of the head away; { point, face }. In sight: the ray from
  // the eyes gets to the block's shape (see shapeOf) by that face before another block's collision box
  // cuts it, the head landing on it too (see cellAt). The rest by their collision box, as mineflayer has
  // them: what has none (grass, a torch) is seen through, a fence stands 1.5 high. The least turn: the
  // smallest angle from where the head looks now to the point, yaw and pitch together; of those, the
  // closest.
  // pitchOnly: the aims the head turns the least sideways to first, then the least up or down (band:
  // across, the bot strafes instead).
  function blockAim (block, faces = FACES, pitchOnly = false) {
    const from = eyes()
    const { aims, reasons } = aimsAt(block, from, faces)
    if (aims.length === 0) {
      throw new Error(`expected ${full(block.name)} at ${at(block.position)} in reach and in sight, found ${reasons.length ? reasons.join('; ') : 'no face of it turned to the eyes'}`)
    }
    const { yaw, pitch } = bot.entity
    const turn = aim => {
      const to = lookTo(from, aim.point)
      return Math.hypot(Math.atan2(Math.sin(to.yaw - yaw), Math.cos(to.yaw - yaw)), to.pitch - pitch)
    }
    const sideways = aim => {
      const to = lookTo(from, aim.point)
      return Math.abs(Math.atan2(Math.sin(to.yaw - yaw), Math.cos(to.yaw - yaw)))
    }
    for (const aim of aims) aim.turn = turn(aim)
    if (pitchOnly) {
      for (const aim of aims) aim.sideways = sideways(aim)
      const least = Math.min(...aims.map(aim => aim.sideways))
      const straight = aims.filter(aim => aim.sideways === least)
      return aimMissed(block, from, straight.reduce((a, b) => b.turn < a.turn || (b.turn === a.turn && b.distance < a.distance) ? b : a), aims)
    }
    return aimMissed(block, from, aims.reduce((a, b) => b.turn < a.turn || (b.turn === a.turn && b.distance < a.distance) ? b : a), aims)
  }

  // The aim off its point by the look provider's miss (see look.miss), where the head lands then: of
  // the seen points touching it on its face's grid and those touching them (aims: blockAim's), the
  // box: the corners [min, max] on the face of their cells, MISS_BOX at most along each axis; from
  // the box's middle the side drawn on the face, the share drawn of the way to the box's edge. Taken
  // where the head lands on its steps (see landingDir): to the other side when that lands off the
  // block, by another face, out of the box or past reach; on the point itself when both do, the miss
  // never costing the click.
  function aimMissed (block, from, aim, aims) {
    const pos = block.position
    const shape = shapeOf(block)
    const shapes = shape.shapes
    const touching = (a, b) => Math.abs(a.i - b.i) <= 1 && Math.abs(a.j - b.j) <= 1
    const region = [aim]
    const left = aims.filter(other => other !== aim && other.face.equals(aim.face))
    for (let k = 0; k < region.length; k++) {
      for (let n = left.length - 1; n >= 0; n--) {
        if (touching(region[k], left[n])) region.push(...left.splice(n, 1))
      }
    }
    const corners = region.flatMap(c => [facePoint(shape, aim.face, c.i / c.n, c.j / c.n), facePoint(shape, aim.face, (c.i + 1) / c.n, (c.j + 1) / c.n)])
    const box = [corners.reduce((a, b) => a.min(b)), corners.reduce((a, b) => a.max(b))]
    const [b, c] = AXES.filter(k => aim.face[k] === 0)
    // No wider than MISS_BOX along each axis of the face: around the point as far as the region lets it.
    for (const k of [b, c]) {
      if (box[1][k] - box[0][k] <= MISS_BOX) continue
      box[0][k] = Math.min(Math.max(aim.point[k] - MISS_BOX / 2, box[0][k]), box[1][k] - MISS_BOX)
      box[1][k] = box[0][k] + MISS_BOX
    }
    const within = p => [b, c].every(k => p[k] >= box[0][k] && p[k] <= box[1][k])
    const { share, side } = look.miss({ yaw: bot.entity.yaw, pitch: bot.entity.pitch }, lookTo(from, aim.point))
    // The box's middle on the face: the miss goes from it, not from the point (on its edge when the
    // point is on the face's, the least turn).
    const middle = aim.point.clone()
    for (const k of [b, c]) middle[k] = (box[0][k] + box[1][k]) / 2
    for (const sign of [1, -1]) {
      const d = { [b]: sign * Math.cos(side), [c]: sign * Math.sin(side) }
      // How far from the middle the box's edge is that way.
      const edge = Math.min(...[b, c].filter(k => d[k] !== 0).map(k => ((d[k] > 0 ? box[1][k] : box[0][k]) - middle[k]) / d[k]))
      if (edge <= 0) continue
      const missed = middle.clone()
      missed[b] += share * edge * d[b]
      missed[c] += share * edge * d[c]
      const lands = rayInto(pos, shapes, from, landingDir(from, missed), REACH_BLOCK + 1)
      if (!lands.hit?.position.equals(pos) || !lands.entered?.equals(aim.face) || !within(lands.point)) continue
      const distance = from.distanceTo(lands.point)
      if (distance <= REACH_BLOCK) return { point: lands.point, face: aim.face, distance, ray: aim.point, box }
    }
    return { ...aim, ray: aim.point, box }
  }

  // The points of the faces of the block (of faces) in reach and in sight from eyes at from, as blockAim
  // takes them: { aims: [{ point, face, distance }], reasons: why no face is }. Each face turned
  // to the eyes in reach is a GRID x GRID grid of ray targets; when none is seen the cells whose blocker
  // is not a whole block, or differs from a neighbour's (an edge of what hides it, where a gap may be),
  // split in 2 x 2 and are tried again. first: the first point seen, no more, for a search;
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
          if (first && grid[i][j]?.blocker === null) return { aims: [{ point: grid[i][j].point, face, distance: grid[i][j].distance }], reasons }
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
              if (first && cell?.blocker === null) return { aims: [{ point: cell.point, face: scan.face, distance: cell.distance }], reasons }
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

  // The seen cells of a grid, with where in it (i, j of n).
  function seenAims (face, grid) {
    const aims = []
    for (let i = 0; i < grid.length; i++) {
      for (let j = 0; j < grid.length; j++) {
        if (grid[i][j]?.blocker !== null) continue
        aims.push({ point: grid[i][j].point, face, distance: grid[i][j].distance, i, j, n: grid.length })
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

  // Whether the box round the block's shape is in reach of the eyes at from, its nearest point (as
  // Quarrify's reaches has it), whatever is in the way.
  function inReach (block, from) {
    const shape = shapeOf(block)
    const nearest = from.clone()
    for (const k of AXES) nearest[k] = Math.min(Math.max(from[k], shape.min[k]), shape.max[k])
    return from.distanceTo(nearest) <= REACH_BLOCK
  }

  // The aim at the block, as show has it, from the eyes now.
  const aimOf = (block, aim) => ({ eyes: eyes(), point: aim.point, ray: aim.ray ?? aim.point, box: aim.box ?? [aim.point, aim.point], block: block.position, face: FACES.findIndex(f => f.equals(aim.face)), missed: false })

  return { turnSpeedTo, lookTo, dirOf, rayInto, eyes, eyesNext, eyeHeight, firstBlock, blockAim, aimsAt, shapeOf, checkLook, inReach, aimOf }
}

const { Vec3 } = require('vec3')
const { goals } = require('mineflayer-pathfinder')
const { REACH_BLOCK, FACES, CONTROLS, full, at, required, deadlineOf, describeItem } = require('../lib/util')

const LIQUIDS = ['water', 'flowing_water', 'lava', 'flowing_lava']
// A band's section, in slices along (see breakBand).
const BAND_DEPTH = 2
const BAND_HEIGHT = 4
// Diagnostics only: a band's gap between two digs logged from this many ticks on, and a physics
// tick counted late this long after the one before (the thread was busy).
const HALT_TICKS = 6
const LATE_MS = 100
// The orders a band's section may be broken in (the band test's fastest), each block {height 1..4
// from the bottom, depth 0..1 (A B, C D), side (0 left: A C, 1 right: B D)}: a slice at a time, the
// far one back from where the near one ends. Each bot breaks its bands in one of them, its own (see
// bandStyleOf).
const BAND_STYLES = {
  'filas-espejo': '4A,4B,3A,3B,2A,2B,1A,1B,1C,1D,2C,2D,3C,3D,4C,4D',
  'serpiente-vuelta': '4A,4B,3B,3A,2A,2B,1B,1A,1C,1D,2D,2C,3C,3D,4D,4C',
  'filas-vuelta': '4A,4B,3A,3B,2A,2B,1A,1B,1D,1C,2D,2C,3D,3C,4D,4C',
  'serpiente-abajo-vuelta': '1A,1B,2B,2A,3A,3B,4B,4A,4C,4D,3D,3C,2C,2D,1D,1C'
}
// The bot's own of BAND_STYLES, by its name: the same each time.
function bandStyleOf (name) {
  let hash = 0
  for (const c of name) hash = (hash * 31 + c.charCodeAt(0)) >>> 0
  const names = Object.keys(BAND_STYLES)
  return names[hash % names.length]
}
// A section's order as codes ("4A,4B,..."): each {height, depth, side}; failed unless each of the 16 once.
function bandOrderOf (codes) {
  const list = codes.split(',')
  const order = list.map(c => {
    if (!/^[1-4][A-D]$/.test(c)) throw new Error(`expected a band order code (height 1..4, block A..D), found ${c} in ${codes}`)
    return [Number(c[0]), Math.floor('ABCD'.indexOf(c[1]) / 2), 'ABCD'.indexOf(c[1]) % 2]
  })
  if (list.length !== 16 || new Set(list).size !== 16) throw new Error(`expected a band order of the 16 blocks once each, found ${codes}`)
  return order
}

module.exports = ctx => {
  const { bot, log, aimHead, aimOf, aimsAt, blockAim, digMs, dirOf, done, eyeHeight, eyes, eyesNext, fail, guard, head, heldMs, holdBestTool, inReach, keysWithin, listen, lookTo, rayInto, sendVis, shapeOf, show, stopHead, walkTo } = ctx

  // band: breaks the box between the corners from and to (2 wide, 4 high) a section at a time, from
  // from's end to to's: along its longer side (x on a tie), a section 2 slices deep (a slice: its
  // blocks at one coordinate along), the last what is left; the floor under its bottom is the one it
  // walks on. Facing along, the columns across are its left (A, C by depth, A the nearest) and
  // right (B, D), the heights 1 (the bottom) to 4; a section's blocks go in a's order. It walks
  // on the seam between the columns (both in reach the same from there), toward the stand of the
  // section past the one it breaks: as far back as the farthest of that one's blocks lets it, in reach
  // of them (see inReach). Its blocks broken at a tick each at most, no confirmation waited on: the
  // head turned to the next, its dig sent the tick the ray from the eyes gets to it in reach (see
  // checkLook), and the head on to the one after it; each confirmed gone by the server within the
  // deadline, a block coming back failing it. Each block one the tool breaks at once (digMs 0), else
  // failed; failed with a liquid in it too. Done with them all gone, at rest nearest the stand past
  // the last section. a is { from: {x, y, z}, to: {x, y, z}, order (codes, see bandOrderOf; the bot's
  // own of BAND_STYLES without), deadline, turn_speed }.
  async function breakBand (action, a) {
    const from = new Vec3(required(a.from, 'from').x, a.from.y, a.from.z)
    const to = new Vec3(required(a.to, 'to').x, a.to.y, a.to.z)
    const min = new Vec3(Math.min(from.x, to.x), Math.min(from.y, to.y), Math.min(from.z, to.z))
    const max = new Vec3(Math.max(from.x, to.x), Math.max(from.y, to.y), Math.max(from.z, to.z))
    const along = max.x - min.x >= max.z - min.z ? 'x' : 'z'
    const across = along === 'x' ? 'z' : 'x'
    if (max[across] - min[across] !== 1 || max.y - min.y !== BAND_HEIGHT - 1) throw new Error(`expected a band 2 wide and ${BAND_HEIGHT} high, found ${at(min)}..${at(max)}`)
    const ms = deadlineOf(a)
    const dir = to[along] >= from[along] ? 1 : -1
    const count = Math.abs(to[along] - from[along]) + 1
    const sections = Math.ceil(count / BAND_DEPTH)
    const y = min.y
    // Facing along dir, its left across: up x facing.
    const facing = new Vec3(0, 0, 0)
    facing[along] = dir
    const leftSide = new Vec3(0, 1, 0).cross(facing)[across] > 0 ? max[across] : min[across]
    const columnOf = side => side === 0 ? leftSide : leftSide === max[across] ? min[across] : max[across]
    const sliceAt = i => from[along] + dir * i
    // The block at u along, c across, height h.
    const cell = (u, c, h) => {
      const v = new Vec3(0, h, 0)
      v[along] = u
      v[across] = c
      return v
    }
    // Section k's blocks in its order, past the band's end left out.
    const style = a.order === undefined ? bandStyleOf(bot.username) : 'order'
    const order = bandOrderOf(a.order ?? BAND_STYLES[style])
    // Each block's code in the order ("4A"), by at(pos): its ticks told by code (see spent).
    const codes = (a.order ?? BAND_STYLES[style]).split(',')
    const codeOf = new Map()
    const blocksOf = k => order
      .map((o, i) => [o, codes[i]])
      .filter(([[, depth]]) => BAND_DEPTH * k + depth < count)
      .map(([[h, depth, side], code]) => {
        const pos = cell(sliceAt(BAND_DEPTH * k + depth), columnOf(side), y + h - 1)
        codeOf.set(at(pos), code)
        return pos
      })
    // The ticks each block of the order took from the one before it, by code, as they went: out of
    // reach (walking up to it), out of sight, or turning to it in reach and in sight. Told at the end.
    const spent = new Map()
    // Since the last dig, for a halt told (HALT_TICKS or more): its ticks by why, the head's ticks,
    // and the physics ticks that came late (the thread busy: more than LATE_MS after the one before).
    let since = { ticks: 0, reach: 0, sight: 0, turn: 0, turning: 0, late: 0 }
    let lastTickAt = null
    const spend = why => {
      since.ticks++
      since[why]++
      if (!head.done) since.turning++
      const code = codeOf.get(at(target.position))
      const s = spent.get(code) ?? { reach: 0, sight: 0, turn: 0, n: 0 }
      s[why]++
      spent.set(code, s)
    }
    // How far back along from the near face of a block of height h the eyes on the seam reach it.
    const eyesY = y + eyeHeight(false)
    const backOf = h => {
      const dy = Math.max(0, y + h - 1 - eyesY, eyesY - (y + h))
      return Math.sqrt(REACH_BLOCK * REACH_BLOCK - dy * dy)
    }
    let back = Infinity
    for (let h = 1; h <= BAND_HEIGHT; h++) back = Math.min(back, backOf(h))
    // Section k's stand along (k === sections: past the last): its farthest slice's near face, back the reach of its worst height.
    const standOf = k => {
      const far = sliceAt(Math.min(BAND_DEPTH * k + BAND_DEPTH - 1, k === sections ? Infinity : count - 1))
      return (dir === 1 ? far : far + 1) - dir * back
    }
    // On the seam: the columns' shared side.
    const seam = max[across]
    const pointOf = k => cell(standOf(k), seam, y)
    // The blocks it walks in: the band's floor level and the blocks before it as far as the first stand.
    const floor = []
    const behind = Math.ceil(back)
    for (let i = -behind; i < count; i++) {
      for (let c = min[across]; c <= max[across]; c++) floor.push(cell(sliceAt(i), c, y))
    }
    action.cleanups.push(() => { if (!bot._client.ended) bot.clearControlStates() })
    action.cleanups.push(stopHead)
    // The section breaking, its blocks left, the one the head goes to.
    let section = 0
    let left = blocksOf(0)
    let target = null
    // Where on it the head goes, once taken (see the tick).
    let aimPoint = null
    let aimed = false
    // Aimed again after landing off it: on the exact point (see the tick).
    let exact = false
    let finished = false
    // Where it was when it last held keys (see stuck).
    let pressedAt = null
    let pressedKeys = null
    // On the ground this tick and the one before: the server's is the last movement packet's, the tick before's.
    let groundedBefore = false
    // Ticks of the band, and the one its last tool change started at (for a dig not confirmed).
    let ticks = 0
    let heldAt = null
    let steady = false
    // At rest at its stand with the target out of reach or sight from where it got to (it rests a hair
    // off the stand, its reach planned to the stand's exact edge): on toward the next stand.
    let pushed = false
    // At the first stand: from there on, toward the next.
    let started = false
    // Taking the best tool in hand, at the first stand.
    let holding = false
    // The digs sent and not yet confirmed gone, by at(pos).
    const sent = new Map()
    const firstAt = Date.now()
    let broken = 0
    // Its own air laid on a block dug (see dig), not the server's confirmation.
    let predicting = false
    // How many times a block it walks on or into changed (see keysTo): of the columns of floor, from
    // under it to the band's top.
    let changes = 0
    const columns = new Set(floor.map(b => `${b.x},${b.z}`))
    listen(action, bot, 'blockUpdate', (oldBlock, newBlock) => {
      const p = newBlock.position
      if (p.y >= y - 1 && p.y <= max.y && columns.has(`${p.x},${p.z}`)) changes++
    })
    listen(action, bot, 'chunkColumnLoad', () => { changes++ })
    // A dig confirmed: the block air; one coming back (the server refused it) fails it.
    listen(action, bot, 'blockUpdate', (oldBlock, newBlock) => {
      const key = at(newBlock.position)
      if (predicting || !sent.has(key)) return
      if (newBlock.boundingBox !== 'empty') {
        fail(action, `expected ${key} of the band gone once its dig was sent, found ${full(newBlock.name)} back`)
        return
      }
      clearTimeout(sent.get(key))
      sent.delete(key)
    })
    // A chunk sent whole (the server's way with many changes in it at once) brings no blockUpdate:
    // the digs in it gone confirmed by it. One still there may be a dig the server has not taken yet
    // when it sent the chunk: left to its blockUpdate or its deadline.
    listen(action, bot, 'chunkColumnLoad', () => {
      for (const [key, timer] of sent) {
        const [x, y, z] = key.split(',').map(Number)
        if (bot.blockAt(new Vec3(x, y, z))?.boundingBox !== 'empty') continue
        clearTimeout(timer)
        sent.delete(key)
      }
    })
    action.cleanups.push(() => { for (const timer of sent.values()) clearTimeout(timer) })
    // What went in and out of its connection, for a dig not confirmed: whether the server got it and answered.
    const wire = { in: 0, lastIn: performance.now(), lastName: null }
    listen(action, bot._client, 'packet', (data, meta) => { wire.in++; wire.lastIn = performance.now(); wire.lastName = meta.name })
    const wireOf = () => {
      const socket = bot._client.socket
      return `(${wire.in} packets in since the band began, the last ${wire.lastName} ${(performance.now() - wire.lastIn).toFixed(0)} ms ago; socket read ${socket.bytesRead} B, wrote ${socket.bytesWritten} B, ${socket.writableLength} B waiting to go out)`
    }
    // The next block to break, past the ones gone or sent and the sections done: null with none left.
    const nextTarget = () => {
      for (;;) {
        while (left.length > 0) {
          const block = bot.blockAt(left[0])
          if (!block) throw new Error(`block at ${at(left[0])} is not loaded`)
          if (LIQUIDS.includes(block.name)) throw new Error(`expected ${at(left[0])} of the band solid or air, found ${block.name}`)
          if (block.boundingBox === 'empty' || sent.has(at(left[0]))) {
            left.shift()
            continue
          }
          if (digMs(block) !== 0) throw new Error(`expected ${full(block.name)} at ${at(left[0])} broken at once with the best tool, found it takes ${digMs(block)} ms`)
          return block
        }
        if (section + 1 === sections) return null
        section++
        left = blocksOf(section)
      }
    }
    // The next one taken as target, the head on to it as soon as it is in reach and in sight.
    function takeNext () {
      const block = nextTarget()
      if (block === null) {
        finished = true
        target = null
        stopHead()
        show(action, { phase: sent.size > 0 ? 'confirming' : 'walking', aim: null })
        return
      }
      target = block
      pushed = false
      aimed = false
      exact = false
      show(action, { target: block.position, phase: 'aiming', aim: null })
    }
    // Its dig sent (face: the packet's, see faceNumber) where the head is now (after the look, see the head's tick), confirmed within ms.
    function dig (block, face) {
      const key = at(block.position)
      // How it was dug, for one not confirmed: what the server weighs the dig by.
      const how = `dug ${full(block.name)} with ${describeItem(bot.heldItem)} in hand, ${heldMs(block, false, steady)} ms with it, ${steady ? 'on the ground' : bot.entity.onGround ? 'on the ground since this tick' : 'off the ground'}, from ${bot.entity.position.toString()}, tick ${ticks} of the band, its last tool change at ${heldAt ?? 'none'}, quick bar slot ${bot.quickBarSlot}`
      sent.set(key, setTimeout(() => fail(action, `expected ${key} of the band gone within ${ms} ms of its dig, found ${full(bot.blockAt(block.position)?.name ?? 'it not loaded')} (${how}) ${wireOf()}`), ms))
      broken++
      // Gone in its own world at once, as the vanilla client has an instant break: the blocks behind
      // it seen and walked to without waiting on the server (one coming back fails the band).
      predicting = true
      bot.world.setBlockStateId(block.position, 0)
      predicting = false
      process.nextTick(() => {
        if (action.over) return
        bot._client.write('block_dig', { status: 0, location: block.position, face })
        bot.swingArm()
      })
    }
    // keysWithin's last answer, the same while the bot, where it goes and the band are (it simulates
    // every key set to rest, the most of a tick's work): { pos, vel, to, changes, keys }.
    let walked = null
    const keysTo = to => {
      const { position, velocity } = bot.entity
      if (walked && walked.changes === changes && walked.to.equals(to) && walked.pos.equals(position) && walked.vel.equals(velocity)) return walked.keys
      walked = { pos: position.clone(), vel: velocity.clone(), to, changes, keys: keysWithin(to, floor) }
      return walked.keys
    }
    // On the way to the target, the ray on another block of the band left, in reach: dug then, the
    // target kept (its turn not started over). The head turns along its way past the blocks next to
    // the one it goes to; each one it gets to on the way is a dig with no turn of its own. Breaking a
    // block of the band before its turn only opens the way and the sight of the rest. One dig a tick:
    // the ray's first block alone.
    let swept = 0
    function sweep (from) {
      const hit = bot.world.raycast(from, dirOf(bot.entity.yaw, bot.entity.pitch), REACH_BLOCK)
      if (!hit || hit.boundingBox !== 'block' || from.distanceTo(hit.intersect) > REACH_BLOCK) return
      const p = hit.position
      if (p.x < min.x || p.x > max.x || p.y < min.y || p.y > max.y || p.z < min.z || p.z > max.z) return
      // Broken at once with what is in hand alone: else the server takes the dig as a start and never answers it.
      if (sent.has(at(p)) || heldMs(hit, false, steady) !== 0) return
      swept++
      const code = codeOf.get(at(p))
      if (code && spent.has(code)) spent.get(code).n++
      since = { ticks: 0, reach: 0, sight: 0, turn: 0, turning: 0, late: 0 }
      show(action, { phase: 'digging' })
      dig(hit, hit.face)
    }
    const walkOn = () => listen(action, bot, 'physicsTick', () => {
      const now = performance.now()
      if (lastTickAt !== null && now - lastTickAt > LATE_MS) since.late++
      lastTickAt = now
      steady = groundedBefore && bot.entity.onGround
      ticks++
      groundedBefore = bot.entity.onGround
      const to = started ? pointOf(finished ? sections : Math.min(section + 1 + (pushed ? 1 : 0), sections)) : pointOf(0)
      // Keys held the tick before and it moved nowhere (sand or gravel come down on its hitbox's edge,
      // which the simulation walks through): its walk stops there.
      // The same keys both ticks: a change of keys can leave it where it was one tick with no wall.
      const keys = keysTo(to)
      const stuck = started && pressedAt !== null && bot.entity.position.equals(pressedAt) && keys !== null && keys.join() === pressedKeys
      for (const key of CONTROLS) bot.setControlState(key, keys !== null && keys.includes(key))
      pressedAt = keys === null ? null : bot.entity.position.clone()
      pressedKeys = keys === null ? null : keys.join()
      if (!started) {
        if (keys !== null) return
        started = true
        log(`band: at the first stand ${bot.entity.position.toString()}, style ${style}`)
        try {
          takeNext()
        } catch (e) {
          fail(action, e.message)
          return
        }
        // The best tool for its first block in hand before any dig (its chunks loaded by now).
        if (target) {
          holding = true
          guard(action, holdBestTool(action, target).then(() => { holding = false }))
        }
      }
      // Diagnostics only: a halt this long told as it goes, what the tick sees.
      if (ticks % 400 === 0) log(`band at tick ${ticks}, ${since.ticks} since its last dig: target ${target ? `${full(bot.blockAt(target.position)?.name)} at ${at(target.position)}` : 'none'}, holding ${holding}, aimed ${aimed}, head done ${head.done}, keys ${JSON.stringify(keys)}, to ${to}, at ${bot.entity.position}, section ${section} of ${sections}, sent ${sent.size}`)
      if (holding) {
        if (target) since.ticks++
        return
      }
      // The target gone meanwhile, not by a dig of its own (another bot's, or what it was fell): the next.
      if (target && bot.blockAt(target.position)?.boundingBox === 'empty') {
        log(`band: ${at(target.position)} gone before its dig, the next taken`)
        try {
          takeNext()
        } catch (e) {
          fail(action, e.message)
          return
        }
      }
      // Its dig the tick the ray gets to it, in reach: the next one taken on the same tick.
      // What the ray got to this tick, for a head on its point and off the target (see below).
      let missedOn = null
      if (target) {
        const from = eyes()
        const reached = inReach(target, from)
        spend(!reached ? 'reach' : aimsAt(target, from, FACES, true).aims.length === 0 ? 'sight' : 'turn')
        if (reached) {
          const { hit, entered, point } = rayInto(target.position, shapeOf(target).shapes, from, dirOf(bot.entity.yaw, bot.entity.pitch), REACH_BLOCK + 1)
          missedOn = hit ? `${full(hit.name)} at ${at(hit.position)}${point ? ` ${from.distanceTo(point).toFixed(2)} away` : ''}` : 'nothing'
          if (!(hit?.position.equals(target.position) && entered && from.distanceTo(point) <= REACH_BLOCK)) sweep(from)
          // Off the ground or in water (the server takes it as no instant break): dug once back on it.
          else if (heldMs(target, true) === 0 && heldMs(target, false, steady) !== 0) missedOn = null
          else {
            missedOn = null
            // Not broken at once with what is in hand (a block of another tool's, one changed since it was
            // planned): failed, as a dig just after a tool change in the band goes unanswered by the server.
            if (heldMs(target, true) !== 0) {
              fail(action, `expected ${full(target.name)} at ${at(target.position)} of the band broken at once with the tool in hand, found ${describeItem(bot.heldItem)} not`)
              return
            }
            spent.get(codeOf.get(at(target.position))).n++
            if (since.ticks >= HALT_TICKS) log(`band halt: ${since.ticks} ticks to ${codeOf.get(at(target.position))} ${at(target.position)} (reach ${since.reach}, sight ${since.sight}, turn ${since.turn}, head turning ${since.turning}, late ticks ${since.late})`)
            since = { ticks: 0, reach: 0, sight: 0, turn: 0, turning: 0, late: 0 }
            show(action, { phase: 'digging' })
            dig(target, faceNumber(entered))
            try {
              takeNext()
            } catch (e) {
              fail(action, e.message)
              return
            }
          }
        }
      }
      // The head on to the target, aimed again from where the eyes are each tick (walking, they move).
      if (target) {
        // Out of reach or of sight still (walking up to it): turned to its middle meanwhile.
        const seen = aimsAt(target, eyes(), FACES, true).aims.length > 0
        // At rest as near as it gets with it out of reach or of sight, no dig of it waited on (a block
        // sent and not yet gone still stands in the way): the order cannot go on.
        if (!seen && keys === null && !pushed && section + 1 < sections) {
          pushed = true
          return
        }
        // Stuck counts with the target out of sight, or off the ground (no dig taken at once by the server).
        if ((!seen && keys === null || stuck && (!seen || !steady)) && sent.size === 0) {
          fail(action, `expected ${full(target.name)} at ${at(target.position)} of the band in reach and in sight from where the walk stops, found it not from ${bot.entity.position.toString()} (order ${a.order ?? style}): ${aimsAt(target, eyes()).reasons.join('; ') || 'no face turned to the eyes'}; walking to ${to.toString()}, in the way ${floor.flatMap(b => [b, b.offset(0, 1, 0)]).filter(b => (b[along] - bot.entity.position[along]) * (to[along] - bot.entity.position[along]) > 0 && bot.blockAt(b)?.boundingBox === 'block').map(b => `${full(bot.blockAt(b).name)} at ${at(b)}`).join(', ') || 'nothing'}`)
          return
        }
        // Its aim taken once, the first tick it is seen (its miss drawn at random, see aimMissed): drawn
        // again each tick, the head's way would start over every tick, never getting there.
        // The head on its point with the ray off the target (the eyes moved since it was taken): taken again.
        if (aimed && head.done && missedOn !== null && keys === null && !pushed && section + 1 < sections) pushed = true
        if (aimed && head.done && missedOn !== null) {
          log(`band: head on its point off ${at(target.position)}, the ray on ${missedOn}: aimed again`)
          aimed = false
          exact = true
        }
        if (seen && !aimed) {
          // Aimed again: on the point seen itself, its miss left out (a miss drawn again might land off
          // again, the head turned anew every tick).
          const aim = blockAim(target, FACES)
          aimPoint = exact ? aim.ray : aim.point
          aimed = true
          show(action, { phase: 'aiming', aim: aimOf(target, aim) })
        } else if (!seen && action.vis.phase !== 'out of sight') {
          show(action, { phase: 'out of sight', aim: null })
        }
        aimHead(lookTo(eyesNext(), aimed ? aimPoint : target.position.offset(0.5, 0.5, 0.5)))
      }
      if (finished && keys === null && sent.size === 0) {
        log(`band: ${style}, ${count} slices of ${at(min)}..${at(max)} gone, ${broken} blocks, ${((Date.now() - firstAt) / 50 / Math.max(1, broken)).toFixed(2)} ticks a block, at ${bot.entity.position.toString()}`)
        log(`band swept: ${swept} of ${broken}`)
        log(`band ticks: ${style} ${[...spent].map(([code, t]) => `${code}=${t.n}/${t.reach}/${t.sight}/${t.turn}`).join(' ')}`)
        done(action)
      }
    })
    action.vis = { kind: 'break', target: blocksOf(0)[0], phase: 'walking', failure: '', aim: null, spot: null, end: null, partial: null, walk: [], run: [], takeover: null }
    sendVis(action.vis)
    // To the band's floor first, the block of the first stand.
    const first = pointOf(0).floored()
    const onFloor = () => {
      const feet = bot.entity.position.floored()
      return floor.some(b => b.equals(feet))
    }
    walkTo(action, new goals.GoalBlock(first.x, first.y, first.z), at(first), walkOn, onFloor)
  }

  // The block_dig packet's face of a face vector (see mineflayer's digging).
  function faceNumber (face) {
    if (face.y !== 0) return face.y > 0 ? 1 : 0
    if (face.z !== 0) return face.z > 0 ? 3 : 2
    return face.x > 0 ? 5 : 4
  }

  return breakBand
}

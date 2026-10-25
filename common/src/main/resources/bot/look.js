'use strict'

// The look provider: how the head turns from where it is to where it looks, a point each physics
// tick, as a hand on a mouse turns it: its speed swelling and easing off (minimum jerk), the way bent
// a little off the straight line, past the target on a long turn and back onto it. Deterministic: the
// same from and to give the same way, its variation drawn from them (see variation); NOISE adds
// random over that. Every point is a whole number of LOOK_STEPs from where the turn starts (vanilla's
// mouse, see bot.look) and the last is where the head lands (see landing).

const conv = require('mineflayer/lib/conversions')

// The step the head turns by: vanilla's mouse at 100% sensitivity.
const LOOK_STEP = conv.fromNotchianPitch(0.15)
// A physics tick, in s.
const TICK = 0.05
// How much random goes over the ways, from 0 (none: dev) up; 1 doubles the bend and the overshoot at most and shakes the points by up to a tenth of the turn.
const NOISE = 1
// How fast a turn goes on average, of the order's turn_speed: drawn in this range a turn.
const SPEED = [0.8, 1.15]
// How far the way bends off the straight line at its middle, of the turn: drawn in this range, to either side.
const BEND = [0.01, 0.06]
// A turn this long or longer, in rad, goes past the target and back: by this much of it, drawn in this range.
const OVERSHOOT_FROM = Math.PI / 18
const OVERSHOOT = [0.03, 0.09]
// The way back from past the target, in ticks: drawn in this range.
const CORRECTION_TICKS = [3, 5]

// The yaw from a to b the short way round, in rad.
function deltaYaw (a, b) {
  return Math.atan2(Math.sin(b - a), Math.cos(b - a))
}

// Where the head ends turning from from to to: whole LOOK_STEPs from from, the nearest to to.
function landing (from, to) {
  return {
    yaw: from.yaw + Math.round(deltaYaw(from.yaw, to.yaw) / LOOK_STEP) * LOOK_STEP,
    pitch: from.pitch + Math.round((to.pitch - from.pitch) / LOOK_STEP) * LOOK_STEP
  }
}

// Numbers in [0, 1) drawn from the turn alone, its from and its landing in LOOK_STEPs (mulberry32
// seeded by their hash): the same turn, the same numbers. salt: another stream of the same turn.
function variation (from, to, salt = 0) {
  let seed = 0x9e3779b9 ^ salt
  for (const n of [from.yaw, from.pitch, to.yaw, to.pitch]) {
    seed = Math.imul(seed ^ Math.round(n / LOOK_STEP), 0x85ebca6b)
    seed ^= seed >>> 13
  }
  return () => {
    seed = (seed + 0x6d2b79f5) | 0
    let t = Math.imul(seed ^ (seed >>> 15), 1 | seed)
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296
  }
}

const between = (range, u) => range[0] + (range[1] - range[0]) * u

// Minimum jerk: the share of a move done at s of its time, its speed 0 at both ends.
const ease = s => s * s * s * (10 - 15 * s + 6 * s * s)

const clampPitch = p => Math.max(-Math.PI / 2, Math.min(Math.PI / 2, p))

// How the head misses to turning from from to to (each { yaw, pitch }, rad): { share: of how far the
// points it can click go, side: the way off to, rad in yaw and pitch }. Any share in [0, 1) can come,
// the small ones the most: the median of three draws, the points nearer the box's middle the likelier
// (falling to none at the box's edge). Drawn from the turn as its way is (see variation); NOISE is
// the chance the share is drawn from random instead. The caller takes it to the block and checks it
// still gets the click (see actions.js aimMissed).
function miss (from, to, random = Math.random) {
  const draw = variation(from, landing(from, to), 1)
  const side = 2 * Math.PI * draw()
  const pick = random() < Math.min(NOISE, 1) ? random : draw
  const share = [pick(), pick(), pick()].sort((a, b) => a - b)[1]
  return { share, side }
}

// The way from from to to (each { yaw, pitch }, rad), one { yaw, pitch } a tick, at turnSpeed (rad/s)
// on average: empty when the head is there already. random: [0, 1) for NOISE (Math.random by default).
function way (from, to, { turnSpeed, random = Math.random }) {
  const end = landing(from, to)
  const dy = deltaYaw(from.yaw, end.yaw)
  const dp = end.pitch - from.pitch
  const angle = Math.hypot(dy, dp)
  if (angle === 0) return []
  const draw = variation(from, end)
  const noise = () => NOISE * random()
  const speed = turnSpeed * between(SPEED, draw())
  // Off the straight line by its normal, to the side drawn.
  const bend = between(BEND, draw()) * (1 + noise()) * (draw() < 0.5 ? -1 : 1) * angle
  const ny = -dp / angle
  const np = dy / angle
  const points = []
  const along = (target, ticks, bent) => {
    const start = points.length ? points[points.length - 1] : from
    const sy = deltaYaw(start.yaw, target.yaw)
    const sp = target.pitch - start.pitch
    for (let i = 1; i <= ticks; i++) {
      const s = i / ticks
      const e = ease(s)
      const arc = bent * Math.sin(Math.PI * s)
      const shake = i < ticks ? (random() - 0.5) * 0.2 * NOISE * angle * Math.sin(Math.PI * s) : 0
      points.push({ yaw: start.yaw + sy * e + ny * (arc + shake), pitch: clampPitch(start.pitch + sp * e + np * (arc + shake)) })
    }
  }
  if (angle >= OVERSHOOT_FROM) {
    const past = between(OVERSHOOT, draw()) * (1 + noise())
    const over = { yaw: end.yaw + dy * past, pitch: clampPitch(end.pitch + dp * past) }
    along(over, Math.max(2, Math.ceil(angle * (1 + past) / (speed * TICK))), bend)
    along(end, Math.round(between(CORRECTION_TICKS, draw())), 0)
  } else {
    along(end, Math.max(1, Math.ceil(angle / (speed * TICK))), bend)
  }
  // On the mouse's steps from from, the last on the landing itself.
  const out = points.map(p => landing(from, p))
  out[out.length - 1] = end
  return out
}

// A head following ways: aim gives it where to look, tick turns it a point. Aimed again on the way
// (the target moving as the bot walks), the rest of its way moves with the target, not started over;
// from the target's landing, a new way.
class Head {
  constructor (yaw, pitch, turnSpeed) {
    this.yaw = yaw
    this.pitch = pitch
    this.turnSpeed = turnSpeed
    this.points = []
    this.goal = null
  }

  clone () {
    const head = new Head(this.yaw, this.pitch, this.turnSpeed)
    head.points = this.points.slice()
    head.goal = this.goal
    return head
  }

  // Whether it is on its goal, nothing left to turn.
  get done () {
    return this.points.length === 0
  }

  // To look at to ({ yaw, pitch }), from where it is (yaw, pitch: the entity's now), at turnSpeed (the last one's when not given).
  aim (yaw, pitch, to, turnSpeed = this.turnSpeed, random) {
    this.yaw = yaw
    this.pitch = pitch
    this.turnSpeed = turnSpeed
    const here = { yaw, pitch }
    if (this.points.length > 0 && this.goal) {
      const goal = landing(here, to)
      const old = landing(here, this.goal)
      const sy = goal.yaw - old.yaw
      const sp = goal.pitch - old.pitch
      this.goal = to
      if (sy === 0 && sp === 0) return
      // Both landings are on its steps (here is a point of its way): the rest stays on them, ending on the new landing.
      this.points = this.points.map(p => ({ yaw: p.yaw + sy, pitch: clampPitch(p.pitch + sp) }))
      return
    }
    this.goal = to
    this.points = way(here, to, { turnSpeed, random })
  }

  // The next point of its way, where the head is then; null with none left.
  tick () {
    const next = this.points.shift()
    if (!next) return null
    this.yaw = next.yaw
    this.pitch = next.pitch
    return next
  }

  stop () {
    this.points = []
    this.goal = null
  }
}

module.exports = { LOOK_STEP, NOISE, landing, miss, way, Head }

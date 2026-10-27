'use strict'

// The look provider: how the head turns from where it is to where it looks, a point each physics
// tick, as a hand on a mouse turns it: the way is a real player's turn (see TRACES), laid from where
// the head is to where it looks and stretched to the time a turn that wide takes. Deterministic: the
// same from and to give the same way, its variation drawn from them (see variation); NOISE adds
// random over that. Every point is a whole number of LOOK_STEPs from where the turn starts (vanilla's
// mouse, see bot.look) and the last is where the head lands (see landing).

const conv = require('mineflayer/lib/conversions')

// The step the head turns by: vanilla's mouse at 100% sensitivity.
const LOOK_STEP = conv.fromNotchianPitch(0.15)
// A physics tick, in s.
const TICK = 0.05
// How much random goes over the ways, from 0 (none: dev) up to 1: the share of the trace's pick drawn
// from random instead of from the turn, and of how far the head's player is from the usual one.
const NOISE = 1
// Real turns, off OpenAI's VPT contractor data (Minecraft at 20 Hz; the isolated turns ending on a
// block being broken or clicked): each one's points but its first and last, the chord from where it
// starts to where it ends laid on (1, 0) (x along it, y off it to its left, of the turn). In bins by
// how wide (from: the narrowest turn in the bin, rad), each sorted by r: how much longer it took than
// its player's usual for a turn that wide.
const TRACES = require('./lookTraces.json')
// A turn's time goes with its angle to this power (the data's, its players' speeds apart).
const EXPONENT = TRACES.exponent
// The usual player's turn_speed: rad/s on a turn 1 rad wide.
const HUMAN_SPEED = 1 / TRACES.seconds
// How much longer than the usual player each of the data's players takes to turn: a head turns as one of them.
const PLAYERS = TRACES.players
// The slowest trace's r.
const SLOWEST = Math.max(...TRACES.bins.map(bin => bin.traces[bin.traces.length - 1].r))

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

const clampPitch = p => Math.max(-Math.PI / 2, Math.min(Math.PI / 2, p))

// How the head misses to turning from from to to (each { yaw, pitch }, rad): { share: of how far the
// points it can click go, side: the way off to, rad in yaw and pitch }. Any share in [0, 1) can come,
// the small ones the most: the median of three draws, the points nearer the box's middle the likelier
// (falling to none at the box's edge). Drawn from the turn as its way is (see variation); NOISE is
// the chance the share is drawn from random instead. The caller takes it to the block and checks it
// still gets the click (see lib/aim.js aimMissed).
function miss (from, to, random = Math.random) {
  const draw = variation(from, landing(from, to), 1)
  const side = 2 * Math.PI * draw()
  const pick = random() < Math.min(NOISE, 1) ? random : draw
  const share = [pick(), pick(), pick()].sort((a, b) => a - b)[1]
  return { share, side }
}

// The ticks a turn angle (rad) wide takes at turnSpeed (rad/s on a turn 1 rad wide), by a hand hand
// times slower than the usual player's on a trace r times slower than its player's usual.
const ticksOf = (angle, turnSpeed, hand, r) => angle ** EXPONENT / turnSpeed * hand * r / TICK

// The most ticks a way angle (rad) wide takes at turnSpeed by hand: on the slowest trace.
function longest (angle, turnSpeed, hand) {
  return Math.max(1, Math.ceil(ticksOf(angle, turnSpeed, hand, SLOWEST)))
}

// The way from from to to (each { yaw, pitch }, rad), one { yaw, pitch } a tick, at turnSpeed (rad/s
// on a turn 1 rad wide, the wider the faster as the data has it, see EXPONENT) by hand (see Head):
// empty when the head is there already. A trace of the turn's bin, drawn (the higher random, the
// slower the trace: 1 the slowest the turn can draw), to either side, its time stretched to the
// turn's. random: [0, 1) for NOISE (Math.random by default).
function way (from, to, { turnSpeed, hand = 1, random = Math.random }) {
  const end = landing(from, to)
  const dy = deltaYaw(from.yaw, end.yaw)
  const dp = end.pitch - from.pitch
  const angle = Math.hypot(dy, dp)
  if (angle === 0) return []
  const draw = variation(from, end)
  let bin = TRACES.bins[0]
  for (const b of TRACES.bins) if (angle >= b.from) bin = b
  const own = draw()
  const pick = own + NOISE * (random() - own)
  const trace = bin.traces[Math.min(bin.traces.length - 1, Math.floor(pick * bin.traces.length))]
  const side = draw() < 0.5 ? -1 : 1
  // The trace's points, from (0, 0) to (1, 0), a tick apart.
  const xs = [0]
  const ys = [0]
  for (let k = 0; k < trace.p.length; k += 2) {
    xs.push(trace.p[k])
    ys.push(trace.p[k + 1])
  }
  xs.push(1)
  ys.push(0)
  const n = xs.length - 1
  const ticks = Math.max(1, Math.round(ticksOf(angle, turnSpeed, hand, trace.r)))
  const points = []
  for (let i = 1; i <= ticks; i++) {
    const s = i / ticks * n
    const k = Math.min(n - 1, Math.floor(s))
    const f = s - k
    const x = xs[k] + (xs[k + 1] - xs[k]) * f
    const y = side * (ys[k] + (ys[k + 1] - ys[k]) * f)
    // Along the turn by x of it, off it to its left (the normal (-dp, dy)) by y of it.
    points.push({ yaw: from.yaw + dy * x - dp * y, pitch: clampPitch(from.pitch + dp * x + dy * y) })
  }
  // On the mouse's steps from from, the last on the landing itself.
  const out = points.map(p => landing(from, p))
  out[out.length - 1] = end
  return out
}

// A head following ways: aim gives it where to look, tick turns it a point. Aimed again on the way
// (the target moving as the bot walks), the rest of its way moves with the target, not started over;
// from the target's landing, a new way. Its hand: how much longer than the usual player it takes to
// turn, one of the data's players (see PLAYERS) drawn when it is made, as far from the usual as NOISE.
class Head {
  constructor (yaw, pitch, turnSpeed, random = Math.random) {
    this.yaw = yaw
    this.pitch = pitch
    this.turnSpeed = turnSpeed
    this.hand = 1 + NOISE * (PLAYERS[Math.floor(random() * PLAYERS.length)] - 1)
    this.points = []
    this.goal = null
  }

  clone () {
    const head = new Head(this.yaw, this.pitch, this.turnSpeed)
    head.hand = this.hand
    head.points = this.points.slice()
    head.goal = this.goal
    return head
  }

  // The most ticks a way angle (rad) wide takes it at turnSpeed (see longest).
  longest (angle, turnSpeed = this.turnSpeed) {
    return longest(angle, turnSpeed, this.hand)
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
    this.points = way(here, to, { turnSpeed, hand: this.hand, random })
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

module.exports = { LOOK_STEP, NOISE, HUMAN_SPEED, landing, miss, way, Head }

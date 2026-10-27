// A primitive's life: begun, held after the running one, shown to the mod as it goes, ended ok or
// failed, its listeners and deadline gone with it (see actions.js).
const { Vec3 } = require('vec3')
const { PHYSICS_TIMESTEP, required } = require('./util')

// How many primitives the bot holds at once, the running one among them: the ones after it run in
// order as each ends (see run). One more sent with all of them held is a crash.
const QUEUE = 3

module.exports = ctx => {
  // ctx.primitives: set by actions.js once the primitives are made, after this (see start).
  const { log, publish, drawBands, draw, bot, name } = ctx

  let current = null
  // The primitives sent while one runs, in order (see run).
  let pending = []
  // Each primitive's number, counting up for the bot: its lines in fleet.log carry it ("#12").
  let lastId = 0
  // Whether the mod draws a primitive's vis now (see show).
  let visShown = false
  // The band boxes the mod draws now, as showBands last sent them.
  let bandsShown = '[]'

  // How long the primitive has run, for its last line: in all, and of its physics ticks how many it
  // spent on each of what the bot did then (see spend).
  const took = action => `${((Date.now() - action.started) / 1000).toFixed(1)}s (${Object.entries(action.ticks).map(([what, ticks]) => `${what} ${(ticks * PHYSICS_TIMESTEP).toFixed(2)}`).join(', ')})`

  // A new primitive takes over from the one running (stop, see run): its listeners and deadline gone, its state replaced.
  // Logged with its fields (the job's step it is, "job", among them) and, for the one it replaced, how long that ran.
  function begin (primitive, a) {
    const action = { id: ++lastId, primitive, args: a, started: Date.now(), over: false, cleanups: [], ticks: { aiming: 0, walking: 0, 'walking digging': 0, digging: 0, still: 0 } }
    if (current) log(`#${current.id} ${current.primitive} replaced by #${action.id} after ${took(current)}`)
    // The ones held stay: this one may be the first of them (see done); a stop drops them (see run).
    if (current) end(current)
    // A failed one's, kept on until now.
    sendVis(null)
    const { action: _, ...fields } = a
    const shown = JSON.stringify(fields)
    log(`#${action.id} ${primitive}${shown === '{}' ? '' : ' ' + (shown.length > 160 ? shown.slice(0, 160) + '...' : shown)}`)
    current = action
    showBands()
    publish('doing', primitive, '')
    return action
  }

  // failure: why it failed, its vis kept on with it until the next primitive; else the vis cleared.
  function end (action, failure = null) {
    action.over = true
    if (current === action) current = null
    for (const cleanup of action.cleanups) cleanup()
    showBands()
    if (action.vis) sendVis(failure === null ? null : { ...action.vis, phase: 'failed', failure })
  }

  // What the mod draws of a wsr primitive (see protocol.vis): action.vis, set up as it starts (see
  // drawn), sent whole on each of its changes as they happen.
  function show (action, changes) {
    if (!action.vis || action.over) return
    Object.assign(action.vis, changes)
    sendVis(action.vis)
  }

  // The boxes of the band primitives running and held, the running one first, each [min, max]: sent
  // to the mod on each change of them (a primitive begun, ended or held, the ones held dropped).
  function showBands () {
    const box = a => {
      const from = required(a.from, 'from')
      const to = required(a.to, 'to')
      return [new Vec3(Math.min(from.x, to.x), Math.min(from.y, to.y), Math.min(from.z, to.z)), new Vec3(Math.max(from.x, to.x), Math.max(from.y, to.y), Math.max(from.z, to.z))]
    }
    const boxes = [...(current && current.primitive === 'band' ? [current.args] : []), ...pending.filter(p => p.action === 'band')].map(box)
    const shown = JSON.stringify(boxes)
    if (shown === bandsShown) return
    bandsShown = shown
    drawBands(boxes)
  }

  function sendVis (vis) {
    if (!vis && !visShown) return
    visShown = Boolean(vis)
    draw(vis)
  }

  // The bot is gone, or a stop comes: nothing is published, the ones held dropped.
  function cancel () {
    pending = []
    if (current) end(current)
    showBands()
  }

  // action.then, when set: what follows its effect confirmed, the primitive going on.
  function done (action) {
    if (action.over) return
    if (action.then) {
      const then = action.then
      action.then = null
      then()
      return
    }
    end(action)
    log(`#${action.id} ${action.primitive} ok ${took(action)}`)
    publish('idle', '', '')
    // The next one held, once this one's handlers are out of the way.
    if (pending.length > 0) queueMicrotask(() => start(pending.shift()))
  }

  function fail (action, message) {
    if (action.over) return
    end(action, message)
    log(`#${action.id} ${action.primitive} failed after ${took(action)}: ${message}`, 'E')
    // The ones held were sent for the world this one was to leave: dropped with it.
    if (pending.length > 0) log(`${pending.length} held dropped: ${pending.map(p => p.action).join(', ')}`, 'W')
    pending = []
    showBands()
    publish('error', action.primitive, message)
  }

  function listen (action, emitter, event, handler) {
    emitter.on(event, handler)
    action.cleanups.push(() => emitter.removeListener(event, handler))
  }

  // Fails the action with what describe() says is found, unless an event settles it first.
  function deadline (action, ms, describe) {
    const timer = setTimeout(() => {
      try {
        fail(action, `${describe()} within ${ms} ms`)
      } catch (e) {
        fail(action, `${e.message} (while describing the failure of ${action.primitive})`)
      }
    }, ms)
    action.cleanups.push(() => clearTimeout(timer))
  }

  // The promise of a mineflayer action that fails: the primitive with it.
  function guard (action, promise) {
    Promise.resolve(promise).catch(e => fail(action, `${e.message}`))
  }

  // The tick counted to the primitive running by what the bot did in it: digging (bot.targetDigBlock),
  // walking or not (its feet moved since the last tick); else turning the head (turned: a point of its
  // way this tick), walking, or still, by the phase it shows (see show) when it has one.
  let lastFeet = null
  function spend (turned) {
    const feet = bot.entity.position.clone()
    const moved = lastFeet !== null && !feet.equals(lastFeet)
    lastFeet = feet
    if (!current) return
    let what = bot.targetDigBlock ? (moved ? 'walking digging' : 'digging') : turned ? 'aiming' : moved ? 'walking' : 'still'
    if (what === 'still' && current.vis) what = `still ${current.vis.phase}`
    current.ticks[what] = (current.ticks[what] ?? 0) + 1
  }

  // Dying takes what it was doing with it: the primitive canceled by the death, a walk stopped with it.
  bot.on('death', () => {
    if (current) fail(current, 'canceled by death')
  })

  // The primitive run to its end by the events that confirm it. A failure, from the primitive or
  // from what it threw, is the bot's error state, not the process's.
  async function start (a) {
    const action = begin(a.action, a)
    try {
      if (!bot.entity) throw new Error('the bot is not in the world yet')
      await ctx.primitives[a.action](action, a)
    } catch (e) {
      log(e.stack, 'E')
      fail(action, e.message)
    }
  }

  // An order from the mod: a primitive, held after the running one and the ones held before it
  // (QUEUE in all), each started as the one before ends ok; a stop takes over at once. One more
  // with QUEUE held is the mod's error: a crash.
  function run (a) {
    if (!ctx.primitives[a.action]) throw new Error(`unknown action ${a.action} for ${name}`)
    if (a.action === 'stop' || !current) {
      if (a.action === 'stop') pending = []
      start(a)
      return
    }
    if (1 + pending.length >= QUEUE) throw new Error(`expected room for ${a.action} among the ${QUEUE} primitives ${name} holds, found ${current.primitive} running and ${pending.map(p => p.action).join(', ')} held`)
    pending.push(a)
    showBands()
    log(`${a.action} held after ${current.primitive}${pending.length > 1 ? ' and ' + pending.slice(0, -1).map(p => p.action).join(', ') : ''}`)
  }

  // The running primitive ended and the ones held dropped, nothing published: the one sent after it takes over.
  function clearQ () {
    if (current || pending.length > 0) log(`queue cleared: ${[current?.primitive, ...pending.map(p => p.action)].filter(Boolean).join(', ')}`)
    cancel()
  }

  // The running primitive's number, for the bot's own lines; 0 for none.
  const currentId = () => current ? current.id : 0

  return { show, showBands, sendVis, cancel, done, fail, listen, deadline, guard, spend, run, clearQ, currentId }
}

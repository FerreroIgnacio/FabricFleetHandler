// Every mineflayer bot in one process, spread over a pool of worker threads (poolThread.js) that
// share their chunk columns (sharedChunks.js), driven by the mod over one socket (see protocol.js).
// The columns' blocks are not sent: they live in columns.bin, mapped by the threads and, for the
// mod's own fleet, the mod.
// Usage: node fleet.js <mod port> <columns.bin>
const net = require('net')
const os = require('os')
const path = require('path')
const { Worker, MessageChannel } = require('worker_threads')
const protocol = require('./protocol')
const states = require('./states')
const { threadProfiler, toText } = require('./profiler')
const { HEADER, SLOTS, SIZE, loadedHeader } = require('./sharedChunks')

const socketPort = Number(process.argv[2])
const columnsFile = process.argv[3]
if (!Number.isInteger(socketPort) || !columnsFile) throw new Error('usage: node fleet.js <mod port> <columns.bin>')

const prof = threadProfiler('node.thread:fleet')

// Bot name -> the thread running it.
const bots = new Map()
// Bot name -> where it is ("server|dimension"), from its spawns; unknown until it is in the world.
const worldOf = new Map()
// The columns in a slot of columns.bin: "server|dimension|cx,cz" -> { header, slot, threads holding
// it, chunk, claim, version, modHolds, ready }.
//
// Held ones (some thread holds them) are what the mod's registry holds: a column is claimed there
// (CLAIM, chunk being its key there, with its slot) when the fleet's first bot gets it, readied
// (READY) once that bot loaded it into the slot and unloaded (UNLOAD) when its last lets it go, once
// for the whole fleet however many bots hold it. `claim` is this load's id there. The mod snapshots
// it to disk on the UNLOAD and releases the slot (RELEASE); `modHolds` until then.
//
// The rest are cached: let go by every bot, or read off their snapshot by the mod (see loadSnapshots).
// They stay until slots run short.
const columns = new Map()
let nextClaim = 1
// Columns the mod is reading off their snapshot: key -> slot.
const loading = new Map()
// Keys the mod has no snapshot of; forgotten once one is unloaded (its snapshot made then).
const noSnapshot = new Set()
// Columns to read off their snapshot once a slot is to spare, in order.
const toLoad = new Set()
// The cached columns, oldest first: the first to be evicted (see makeRoom).
const cached = new Map()
// Slot -> its column, for every column in `columns`.
const slotColumns = new Map()
// Held columns changed this turn of the event loop: key -> a bot that saw it. Sent once the turn is
// done (CHANGED), the mod snapshotting each again.
const changed = new Map()

function sendChanged () {
  for (const [key, bot] of changed) send(protocol.changed(bot, columns.get(key).chunk))
  changed.clear()
}

// Slots kept free for the bots: the cache gives way below this, and no snapshot is read into them.
const RESERVE = 256

// The slots of columns.bin no column has, taken from the end. An evicted column's slot stays out of
// it, its blocks untouched, until the mod released it (RELEASE).
const freeSlots = Array.from({ length: SLOTS }, (_, i) => SLOTS - 1 - i)
// Slots evicted -> { mod: waiting on its RELEASE }.
const evicting = new Map()

// The mod is done with the evicted slot.
function settleSlot (slot) {
  const pending = evicting.get(slot)
  if (!pending) throw new Error(`slot ${slot} settled, but it was not evicted`)
  if (!pending.mod) throw new Error(`the mod released slot ${slot} twice`)
  evicting.delete(slot)
  freeSlots.push(slot)
  loadSnapshots()
}

// A cached column out of its slot, which is free once the mod released it (see settleSlot).
function evict (key, column) {
  if (column.threads.size > 0) throw new Error(`column ${key} evicted while a thread holds it`)
  cached.delete(key)
  columns.delete(key)
  slotColumns.delete(column.slot)
  if (column.modHolds) evicting.set(column.slot, { mod: true })
  else freeSlots.push(column.slot)
}

// The oldest cached columns go until the free slots, with those on their way back, are RESERVE again.
function makeRoom () {
  for (const [key, column] of cached) {
    if (freeSlots.length + evicting.size >= RESERVE) return
    evict(key, column)
  }
}

// A slot for a column a bot got.
function takeSlot (key) {
  if (freeSlots.length === 0) throw new Error(`no free slot in columns.bin for ${key}: all ${SLOTS} are held or being evicted`)
  const slot = freeSlots.pop()
  makeRoom()
  return slot
}

// Columns ("server|dimension|cx,cz") to have in memory off their snapshot, as cached ones. Nothing
// asks for any yet.
function wantSnapshots (keys) {
  for (const key of keys) {
    if (columns.has(key) || noSnapshot.has(key) || loading.has(key)) continue
    toLoad.add(key)
  }
  loadSnapshots()
}

// Asks the mod for the snapshots to read while slots are to spare past RESERVE. Short of them, cached
// columns give way, their slots coming back once the mod released them (settleSlot calls this again).
// With none to give way and none coming back, the rest are given up.
function loadSnapshots () {
  for (const key of toLoad) {
    if (columns.has(key)) {
      toLoad.delete(key)
      continue
    }
    if (freeSlots.length > RESERVE) {
      toLoad.delete(key)
      const slot = freeSlots.pop()
      loading.set(key, slot)
      send(protocol.load(chunkOf(key), slot))
      continue
    }
    if (evicting.size < toLoad.size && cached.size > 0) {
      const [[oldest, column]] = cached
      evict(oldest, column)
      continue
    }
    if (evicting.size > 0 || loading.size > 0) return
    toLoad.clear()
    return
  }
}

// The mod read the column into the slot (version: its snapshot's Minecraft version), or has no snapshot of it (null).
function onLoaded (key, slot, version) {
  if (loading.get(key) !== slot) throw new Error(`the mod loaded ${key} into slot ${slot}, which it was not asked for`)
  loading.delete(key)
  if (version === null) noSnapshot.add(key)
  // No snapshot, or a bot got the column meanwhile: the slot was never anyone's.
  if (version === null || columns.has(key)) {
    freeSlots.push(slot)
  } else {
    const column = { header: loadedHeader(), slot, threads: new Set(), chunk: chunkOf(key), claim: null, version, modHolds: false, ready: true }
    columns.set(key, column)
    slotColumns.set(slot, column)
    cached.set(key, column)
  }
  loadSnapshots()
}

// The mod released the slot of an unloaded column: cached, or evicted already.
function onRelease (slot) {
  if (evicting.has(slot)) {
    settleSlot(slot)
    return
  }
  const column = slotColumns.get(slot)
  if (!column || column.threads.size > 0 || !column.modHolds) throw new Error(`the mod released slot ${slot}, which no unloaded column has`)
  column.modHolds = false
}

// "server|dimension|cx,cz" -> the registry's key: { server, dimension, x, z }.
function chunkOf (key) {
  const parts = key.split('|')
  if (parts.length !== 3) throw new Error(`column key ${key} is not server|dimension|cx,cz`)
  const [x, z] = parts[2].split(',').map(Number)
  if (!Number.isInteger(x) || !Number.isInteger(z)) throw new Error(`column key ${key} has no chunk x,z`)
  return { server: parts[0], dimension: parts[1], x, z }
}

// The serializer pool: threads that parse the columns into their slots, off the pool threads (see
// columnSerializer.js). One for now.
const SERIALIZER_THREADS = 1
const serializers = []
for (let i = 0; i < SERIALIZER_THREADS; i++) {
  const worker = new Worker(path.join(__dirname, 'columnSerializer.js'), { workerData: { index: i, columnsFile } })
  worker.on('message', message => {
    if (message.profile === undefined) throw new Error('a serializer thread sent something besides its profile rows')
    onProfileRows(worker, message.profile, message.rows)
  })
  worker.on('error', err => { throw err })
  worker.on('exit', code => { throw new Error(`serializer thread exited (code ${code})`) })
  serializers.push(worker)
}

// What a pool thread sends, in the order it happened there (see poolThread.js).
function onThreadMessage (thread, message) {
  if (message.profile !== undefined) {
    onProfileRows(thread.worker, message.profile, message.rows)
  } else if (message.acquire !== undefined || message.release !== undefined || message.ready !== undefined || message.changed !== undefined) {
    onChunkRequest(thread, message)
  } else {
    onReport(thread, message)
  }
}

function onChunkRequest (thread, request) {
  if (request.acquire !== undefined) {
    let column = columns.get(request.acquire)
    // Cached (let go before, or off its snapshot): the bot loads it anew, into a slot of its own.
    if (column && column.threads.size === 0) {
      evict(request.acquire, column)
      column = undefined
    }
    const load = !column
    if (load) {
      const slot = takeSlot(request.acquire)
      column = { header: new SharedArrayBuffer(HEADER), slot, threads: new Set(), chunk: chunkOf(request.acquire), claim: nextClaim++, version: request.version, modHolds: true, ready: false }
      columns.set(request.acquire, column)
      slotColumns.set(slot, column)
      send(protocol.claim(request.bot, column.chunk, column.claim, column.slot, request.version))
    }
    column.threads.add(thread)
    // Never waited on: the thread's bots go on until it lands.
    thread.chunkPort.postMessage({ acquired: request.acquire, header: column.header, slot: column.slot, load })
  } else if (request.release !== undefined) {
    const column = columns.get(request.release)
    if (!column || !column.threads.delete(thread)) throw new Error(`a thread released column ${request.release} it does not hold`)
    if (column.threads.size === 0) {
      // Cached: the mod snapshots it and releases the slot.
      cached.set(request.release, column)
      // The mod snapshots it once more as it goes: the changes of this turn not sent yet go with it.
      changed.delete(request.release)
      send(protocol.unload(request.bot, column.chunk))
      send(protocol.columnGone(request.bot, column.chunk))
    }
  } else if (request.ready !== undefined) {
    // Its first bot loaded it into the slot: the mod reads it from here on, and snapshots it.
    const column = columns.get(request.ready)
    if (!column || !column.threads.has(thread)) throw new Error(`a thread readied column ${request.ready} it does not hold`)
    column.ready = true
    noSnapshot.delete(request.ready)
    send(protocol.ready(request.bot, column.chunk, column.claim))
  } else if (request.changed !== undefined) {
    const column = columns.get(request.changed)
    if (!column || !column.threads.has(thread)) throw new Error(`a thread changed column ${request.changed} it does not hold`)
    // Readied later: snapshotted then, change and all.
    if (!column.ready) return
    // Once per column and turn, however many bots and threads saw changes in it.
    if (changed.size === 0) setImmediate(sendChanged)
    changed.set(request.changed, request.bot)
  } else {
    throw new Error('unknown chunk request from a pool thread')
  }
}

function onReport (thread, report) {
  if (report.frames) {
    send(Buffer.from(report.frames))
  } else if (report.world) {
    worldOf.set(report.bot, report.world)
    takeProfile(0, `${report.bot} in ${report.world}`)
  } else if (report.end) {
    bots.delete(report.bot)
    worldOf.delete(report.bot)
    thread.bots--
    if (closing && bots.size === 0) process.exit(0)
    takeProfile(0, `${report.bot} gone`)
  } else {
    throw new Error('unknown report from a pool thread')
  }
}

// The pool: a thread per core at most, each started when a bot needs it
// rather than all at once, so their start (each loads mineflayer and minecraft-data) never takes
// every core together.
const threads = []
const MAX_THREADS = Math.max(1, os.cpus().length - SERIALIZER_THREADS)

function startThread () {
  const { port1, port2 } = new MessageChannel()
  const thread = { bots: 0, chunkPort: port1 }
  // A port to each serializer thread.
  const serializerPorts = serializers.map(worker => {
    const channel = new MessageChannel()
    worker.postMessage({ type: 'client', port: channel.port2 }, [channel.port2])
    return channel.port1
  })
  thread.worker = new Worker(path.join(__dirname, 'poolThread.js'), {
    workerData: { index: threads.length, chunkPort: port2, serializerPorts, columnsFile },
    transferList: [port2, ...serializerPorts]
  })
  thread.worker.on('message', () => { throw new Error('a pool thread wrote to the fleet off its chunk port') })
  thread.worker.on('error', err => { throw err })
  thread.worker.on('exit', code => { throw new Error(`pool thread exited (code ${code})`) })
  port1.on('message', message => onThreadMessage(thread, message))
  threads.push(thread)
  return thread
}

// Profiles being put together: key -> { id, reason, waiting: the threads yet to send their rows,
// rows }. Every thread of the fleet sends its own (see profiler.js), the pool threads their bots'
// too; once all of them did, the profile goes to the mod. `id` is the mod's PROFILE_REQUEST's, 0
// for the fleet's own: a bot in a world or gone.
const profiles = new Map()
let nextProfile = 1

function takeProfile (id, reason) {
  if (closing) return
  const key = nextProfile++
  const workers = [...threads.map(t => t.worker), ...serializers]
  const cpu = process.cpuUsage()
  const memory = process.memoryUsage()
  const machine = os.cpus().reduce((sum, { times }) => {
    sum.all += times.user + times.nice + times.sys + times.idle + times.irq
    sum.busy += times.user + times.nice + times.sys + times.irq
    return sum
  }, { busy: 0, all: 0 })
  const rows = [
    // The whole process: every thread, libuv's and V8's own included.
    ['node', 'cpu.user', 'ms', cpu.user / 1000],
    ['node', 'cpu.system', 'ms', cpu.system / 1000],
    ['node', 'mem.rss', 'B', memory.rss],
    ['node', 'uptime.s', '#', process.uptime()],
    ['node', 'bots', '#', bots.size],
    // The machine it runs on, for what share of it this process is.
    ['node', 'cpu.count', '#', os.cpus().length],
    ['node', 'mem.system.used', 'B', os.totalmem() - os.freemem()],
    ['node', 'mem.system.total', 'B', os.totalmem()],
    // Every core's time, busy and whole: the machine's load, not only this process's.
    ['node', 'cpu.system.busy', 'ms', machine.busy],
    ['node', 'cpu.system.all', 'ms', machine.all],
    // The slots of columns.bin in use: what the shared columns take.
    ['node', 'mem.columns', 'B', (SLOTS - freeSlots.length) * SIZE],
    ['node', 'threads', '#', 1 + workers.length],
    ...prof.rows([
      ['node.thread:fleet', 'columns', '#', columns.size],
      ['node.thread:fleet', 'columns.cached', '#', cached.size],
      ['node.thread:fleet', 'slots.free', '#', freeSlots.length]
    ])
  ]
  profiles.set(key, { id, reason, waiting: new Set(workers), rows })
  for (const worker of workers) worker.postMessage({ type: 'profile', profile: key })
}

// A thread's rows for the profile `key`.
function onProfileRows (worker, key, rows) {
  const profile = profiles.get(key)
  if (!profile || !profile.waiting.delete(worker)) throw new Error(`a thread sent rows for profile ${key}, which does not wait on it`)
  profile.rows.push(...rows)
  finishProfile(key, profile)
}

// Sent to the mod once every thread it waits on sent its rows.
function finishProfile (key, profile) {
  if (profile.waiting.size > 0) return
  profiles.delete(key)
  send(protocol.profile(profile.id, profile.reason, toText(merged(profile.rows))))
}

// The rows of a bot added up into one; those of bots gone are left out.
function merged (rows) {
  const byKey = new Map()
  for (const row of rows) {
    const [scope, name, unit, value] = row
    if (scope.startsWith('node.bot:') && !bots.has(scope.slice('node.bot:'.length))) continue
    const key = `${scope}\t${name}`
    const known = byKey.get(key)
    if (!known) {
      byKey.set(key, [scope, name, unit, value])
      continue
    }
    if (known[2] !== unit) throw new Error(`profile row ${scope} ${name} sent in ${known[2]} and in ${unit}`)
    known[3] += value
  }
  return [...byKey.values()]
}

// Once closing the mod is gone: what the bots still report on their way out has nowhere to go.
let closing = false
let socket = null

const send = frame => {
  if (closing || !socket) return
  socket.write(frame)
}

function onFrame (frame) {
  const message = protocol.decode(frame)
  if (protocol.PRIMITIVES.has(message.type)) {
    // Already gone: its BOT_GONE is on the way to the mod.
    bots.get(message.bot)?.worker.postMessage({ type: 'action', bot: message.bot, action: message.action })
    return
  }
  switch (message.type) {
    case protocol.TYPES.SPAWN: {
      if (bots.has(message.bot)) throw new Error(`a bot named ${message.bot} is already running`)
      // The thread running the fewest bots; a new one while every thread has some and the pool has room.
      const least = threads.reduce((l, t) => !l || t.bots < l.bots ? t : l, null)
      const thread = !least || (least.bots > 0 && threads.length < MAX_THREADS) ? startThread() : least
      thread.bots++
      bots.set(message.bot, thread)
      thread.worker.postMessage({ type: 'spawn', name: message.bot, host: message.host, port: message.port })
      break
    }
    case protocol.TYPES.QUIT:
      // Already gone: its BOT_GONE is on the way to the mod.
      bots.get(message.bot)?.worker.postMessage({ type: 'quit', bot: message.bot })
      break
    case protocol.TYPES.WINDOW_CLICK:
      bots.get(message.bot)?.worker.postMessage({ type: 'click', bot: message.bot, slot: message.slot, button: message.button, mode: message.mode, id: message.id })
      break
    case protocol.TYPES.TRADE_SELECT:
      bots.get(message.bot)?.worker.postMessage({ type: 'trade', bot: message.bot, trade: message.trade })
      break
    case protocol.TYPES.CLEAR_Q:
      bots.get(message.bot)?.worker.postMessage({ type: 'clear_q', bot: message.bot })
      break
    case protocol.TYPES.ALLOWED_PLACE:
      bots.get(message.bot)?.worker.postMessage({ type: 'allowed_place', bot: message.bot, blocks: message.blocks })
      break
    case protocol.TYPES.RELEASE:
      // The mod has the snapshot of an unloaded column: done with its slot.
      onRelease(message.slot)
      break
    case protocol.TYPES.LOADED:
      onLoaded(`${message.key.server}|${message.key.dimension}|${message.key.x},${message.key.z}`, message.slot, message.version)
      break
    case protocol.TYPES.PROFILE_REQUEST:
      takeProfile(message.id, message.reason)
      break
  }
}

// The mod going away (socket or stdin closed) takes every bot with it.
function close () {
  if (closing) return
  closing = true
  if (bots.size === 0) process.exit(0)
  for (const [name, thread] of bots) thread.worker.postMessage({ type: 'quit', bot: name })
}

// The mod's connection, the fleet started with it.
function serve (connection) {
  socket = connection
  closing = false
  connection.setNoDelay(true)
  connection.on('error', err => { throw err })
  // First of all: the names of the state ids the slots hold.
  send(protocol.stateNames(states.legacyNames()))
  connection.on('data', protocol.frames(onFrame))
  connection.on('close', close)
}

serve(net.connect(socketPort, '127.0.0.1'))
// The fleet lives and dies with the mod's process.
process.stdin.on('end', close)
process.stdin.resume()

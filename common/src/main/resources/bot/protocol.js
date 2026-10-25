// The binary frames between the fleet and the mod, mirrored by net.mapmcbot.fleet.FleetProtocol:
// u32 length, u8 type, payload; big-endian; strings are a u16 byte length and UTF-8. Chunk messages
// start with the header: bot, server (host:port), dimension, chunk x, chunk z. The columns' blocks
// never go through here: the mod reads them off their slot of columns.bin (see sharedChunks.js).
const TYPES = {
  // fleet -> mod
  CLAIM: 1,
  READY: 2,
  CHANGED: 3,
  BLOCK_ENTITY_UPDATE: 4,
  UNLOAD: 5,
  BOT_GONE: 6,
  PATH: 7,
  BOT_SPAWNED: 8,
  STATE_NAMES: 9,
  LOAD: 10,
  PROFILE: 11,
  CRASH: 12,
  WELCOME: 13,
  UPDATING: 14,
  UPDATE_FAILED: 15,
  INVENTORY: 24,
  STATE: 26,
  WINDOW: 27,
  CHEST_LID: 30,
  CHEST: 31,
  COLUMN_GONE: 32,
  LOG: 33,
  // mod -> fleet
  RELEASE: 16,
  LOADED: 20,
  SPAWN: 17,
  QUIT: 18,
  PROFILE_REQUEST: 21,
  HELLO: 22,
  WINDOW_CLICK: 25,
  TRADE_SELECT: 29,
  ALLOWED_PLACE: 49,
  // mod -> fleet: the primitives (see actions.js), one type each
  GOTO: 23,
  INTERACT: 34,
  BREAK: 35,
  PLACE: 36,
  CLOSE_WINDOW: 37,
  MOVE: 38,
  ITEM_FILL: 39,
  WAIT_SLOT: 40,
  HOTBAR: 41,
  SELECT_TRADE: 42,
  LOOK_AT: 43,
  DROP: 44,
  SHIFT_MOVE: 45,
  STOP: 46,
  BREAK_WSR: 47,
  PLACE_WSR: 48,
  BREAK_WSRP: 50,
  PLACE_WSRP: 51,
  BREAK_WSRPA: 52,
  PLACE_WSRPA: 53
}

// The primitive each of its types is, as actions.js names it.
const PRIMITIVES = new Map([
  [TYPES.GOTO, 'goto'], [TYPES.INTERACT, 'interact'], [TYPES.BREAK, 'break'], [TYPES.PLACE, 'place'],
  [TYPES.CLOSE_WINDOW, 'close_window'], [TYPES.MOVE, 'move'], [TYPES.ITEM_FILL, 'item_fill'],
  [TYPES.WAIT_SLOT, 'wait_slot'], [TYPES.HOTBAR, 'hotbar'], [TYPES.SELECT_TRADE, 'select_trade'],
  [TYPES.LOOK_AT, 'look_at'], [TYPES.DROP, 'drop'], [TYPES.SHIFT_MOVE, 'shift_move'], [TYPES.STOP, 'stop'],
  [TYPES.BREAK_WSR, 'break_wsr'], [TYPES.PLACE_WSR, 'place_wsr'], [TYPES.BREAK_WSRP, 'break_wsrp'],
  [TYPES.PLACE_WSRP, 'place_wsrp'], [TYPES.BREAK_WSRPA, 'break_wsrpa'], [TYPES.PLACE_WSRPA, 'place_wsrpa']
])

class Writer {
  constructor (type) {
    // The length goes first, filled in by frame(): the frame is put together in one copy.
    this.length = Buffer.allocUnsafe(4)
    this.parts = [this.length]
    this.u8(type)
  }

  u8 (value) {
    const b = Buffer.allocUnsafe(1)
    b.writeUInt8(value)
    this.parts.push(b)
  }

  u16 (value) {
    const b = Buffer.allocUnsafe(2)
    b.writeUInt16BE(value)
    this.parts.push(b)
  }

  i32 (value) {
    const b = Buffer.allocUnsafe(4)
    b.writeInt32BE(value)
    this.parts.push(b)
  }

  f64 (value) {
    const b = Buffer.allocUnsafe(8)
    b.writeDoubleBE(value)
    this.parts.push(b)
  }

  str (value) {
    const bytes = Buffer.from(value, 'utf8')
    if (bytes.length > 0xffff) throw new Error(`string of ${bytes.length} bytes is too long`)
    this.u16(bytes.length)
    this.parts.push(bytes)
  }

  bytes (buffer) {
    this.i32(buffer.length)
    this.parts.push(buffer)
  }

  raw (buffer) {
    this.parts.push(buffer)
  }

  header (bot, key) {
    this.str(bot)
    this.str(key.server)
    this.str(key.dimension)
    this.i32(key.x)
    this.i32(key.z)
  }

  frame () {
    const frame = Buffer.concat(this.parts)
    frame.writeUInt32BE(frame.length - 4, 0)
    return frame
  }
}

class Reader {
  constructor (buffer) {
    this.buffer = buffer
    this.offset = 0
  }

  u8 () {
    return this.buffer.readUInt8(this.offset++)
  }

  u16 () {
    const value = this.buffer.readUInt16BE(this.offset)
    this.offset += 2
    return value
  }

  i32 () {
    const value = this.buffer.readInt32BE(this.offset)
    this.offset += 4
    return value
  }

  f64 () {
    const value = this.buffer.readDoubleBE(this.offset)
    this.offset += 8
    return value
  }

  pos () {
    return { x: this.i32(), y: this.i32(), z: this.i32() }
  }

  ints () {
    const count = this.i32()
    if (count < 0) throw new Error(`bad count ${count}`)
    return Array.from({ length: count }, () => this.i32())
  }

  // An item match as actions.js itemMatcher takes it: u8 name present, u8 metadata present, u8 NBT
  // mode (0 none, 1 match, 2 ignore); then the name, i32 metadata, and with a mode the reference's NBT
  // bytes, i32 path count and each path.
  matcher () {
    const hasName = this.u8()
    const hasMetadata = this.u8()
    const mode = this.u8()
    const spec = {}
    if (hasName) spec.name = this.str()
    if (hasMetadata) spec.metadata = this.i32()
    if (mode > 2) throw new Error(`item match of unknown NBT mode ${mode}`)
    if (mode) {
      spec.nbt = this.bytes().toString('hex')
      const count = this.i32()
      if (count < 0) throw new Error(`bad path count ${count}`)
      spec[mode === 1 ? 'match' : 'ignore'] = Array.from({ length: count }, () => this.str())
    }
    return spec
  }

  // The fields every primitive with them may carry, each a u8 present then its value: i32 deadline in
  // ms, f64 turn speed in rad/s. Set on a only when present.
  deadline (a) {
    if (this.u8()) a.deadline = this.i32()
  }

  turnSpeed (a) {
    if (this.u8()) a.turn_speed = this.f64()
  }

  bytes () {
    const length = this.i32()
    if (length < 0 || this.offset + length > this.buffer.length) throw new Error('truncated bytes')
    const value = this.buffer.subarray(this.offset, this.offset + length)
    this.offset += length
    return value
  }

  str () {
    const length = this.u16()
    if (this.offset + length > this.buffer.length) throw new Error('truncated string')
    const value = this.buffer.toString('utf8', this.offset, this.offset + length)
    this.offset += length
    return value
  }

  key () {
    return { server: this.str(), dimension: this.str(), x: this.i32(), z: this.i32() }
  }

  end (type) {
    if (this.offset !== this.buffer.length) throw new Error(`${this.buffer.length - this.offset} stray bytes after message type ${type}`)
  }
}

// id: this load's claim id; slot: its slot in columns.bin, the mod's until it releases it.
function claim (bot, key, id, slot, mcVersion) {
  const w = new Writer(TYPES.CLAIM)
  w.header(bot, key)
  w.i32(id)
  w.i32(slot)
  w.str(mcVersion)
  return w.frame()
}

// The column claimed as `id` is loaded into its slot.
function ready (bot, key, id) {
  const w = new Writer(TYPES.READY)
  w.header(bot, key)
  w.i32(id)
  return w.frame()
}

// The readied column's blocks or block entities changed: the mod snapshots it again.
function changed (bot, key) {
  const w = new Writer(TYPES.CHANGED)
  w.header(bot, key)
  return w.frame()
}

// Asks the mod to read the column's snapshot into the slot; answered with LOADED. No bot: an empty name.
function load (key, slot) {
  const w = new Writer(TYPES.LOAD)
  w.header('', key)
  w.i32(slot)
  return w.frame()
}

// names: [[state id, name]], every state id a slot may hold. No bot: an empty name.
function stateNames (names) {
  const w = new Writer(TYPES.STATE_NAMES)
  w.str('')
  w.i32(names.length)
  for (const [id, name] of names) {
    w.u16(id)
    w.str(name)
  }
  return w.frame()
}

// entity: { type, nbt: Buffer }, or null when the block entity was removed. Position in world coordinates.
function blockEntityUpdate (bot, key, pos, entity) {
  const w = new Writer(TYPES.BLOCK_ENTITY_UPDATE)
  w.header(bot, key)
  w.i32(pos.x)
  w.i32(pos.y)
  w.i32(pos.z)
  w.u8(entity ? 1 : 0)
  if (entity) {
    w.str(entity.type)
    w.bytes(entity.nbt)
  }
  return w.frame()
}

function unload (bot, key) {
  const w = new Writer(TYPES.UNLOAD)
  w.header(bot, key)
  return w.frame()
}

function botGone (bot) {
  const w = new Writer(TYPES.BOT_GONE)
  w.str(bot)
  return w.frame()
}

function botSpawned (bot) {
  const w = new Writer(TYPES.BOT_SPAWNED)
  w.str(bot)
  return w.frame()
}

// The path the bot is walking: target {x, y, z} and nodes [{x, y, z}]; null when it is not walking one.
function path (bot, target, nodes) {
  const w = new Writer(TYPES.PATH)
  w.str(bot)
  w.u8(target ? 1 : 0)
  if (target) {
    w.i32(target.x)
    w.i32(target.y)
    w.i32(target.z)
    w.i32(nodes.length)
    // One buffer for every node: a path is sent on each of its updates.
    const coords = Buffer.allocUnsafe(nodes.length * 12)
    nodes.forEach((n, i) => {
      coords.writeInt32BE(Math.floor(n.x), i * 12)
      coords.writeInt32BE(Math.floor(n.y), i * 12 + 4)
      coords.writeInt32BE(Math.floor(n.z), i * 12 + 8)
    })
    w.raw(coords)
  }
  return w.frame()
}

// The bot's inventory, as its own window holds it: `held` the hotbar slot in its hand (0-8),
// `lastClick` the id of the mod's last click done (0 before any), the item on its cursor and every
// slot by window id (46 from 1.9, 45 before). An item is u8 present,
// then its name ("minecraft:stone"), i32 count, i32 metadata (0 from 1.13) and i32 NBT length and
// the NBT (named root compound, big-endian; 0 for none). Neutral of the version: the mod makes its
// own item of it.
function inventory (bot, held, lastClick, cursor, slots) {
  const w = new Writer(TYPES.INVENTORY)
  w.str(bot)
  w.u8(held)
  w.i32(lastClick)
  item(w, cursor)
  w.i32(slots.length)
  for (const slot of slots) item(w, slot)
  return w.frame()
}

function item (w, it) {
  w.u8(it ? 1 : 0)
  if (!it) return
  w.str(it.name.includes(':') ? it.name : `minecraft:${it.name}`)
  w.i32(it.count)
  w.i32(it.metadata ?? 0)
  w.bytes(it.nbt)
}

// What the bot is doing: kind 0 idle, 1 doing `primitive`, 2 error in `primitive` with `message`
// (what was expected and what was found), and where it stands then: u8 present, its feet's block
// (i32 x, y, z) and its feet as they are (f64 x, y, z), none before it is in the world. The bot stays
// in an error until its next primitive.
function state (bot, kind, primitive, message, feet) {
  const w = new Writer(TYPES.STATE)
  w.str(bot)
  w.u8(kind)
  w.str(primitive)
  w.bytes(Buffer.from(message, 'utf8'))
  w.u8(feet ? 1 : 0)
  if (feet) {
    w.i32(Math.floor(feet.x))
    w.i32(Math.floor(feet.y))
    w.i32(Math.floor(feet.z))
    w.f64(feet.x)
    w.f64(feet.y)
    w.f64(feet.z)
  }
  return w.frame()
}

// How many players have the chest at x, y, z open, as the server shows its lid: sent as it changes.
function chestLid (bot, pos, viewers) {
  const w = new Writer(TYPES.CHEST_LID)
  w.str(bot)
  w.i32(pos.x)
  w.i32(pos.y)
  w.i32(pos.z)
  w.u8(viewers)
  return w.frame()
}

// A block with an inventory (a chest, trapped chest, hopper, dispenser, dropper, furnace, brewing stand
// or shulker box: its type, "minecraft:chest"...) is at pos (world) in the column, or
// none is any more (type empty). Not a column frame: relays send it too, the mod keeps the chests.
function chest (bot, key, pos, type) {
  const w = new Writer(TYPES.CHEST)
  w.header(bot, key)
  w.i32(pos.x)
  w.i32(pos.y)
  w.i32(pos.z)
  w.str(type)
  return w.frame()
}

// The fleet let the column go: what the mod knows of its chests may go stale from here on. Not a
// column frame: relays send it too.
function columnGone (bot, key) {
  const w = new Writer(TYPES.COLUMN_GONE)
  w.header(bot, key)
  return w.frame()
}

// The window the bot has open, null when none: its type ("minecraft:furnace"), how many of its
// slots are the window's own (the bot's inventory follows them), every slot's item, the item on its cursor,
// its properties (a furnace's burn and cook times) by index and a villager's trades.
function windowState (bot, window) {
  const w = new Writer(TYPES.WINDOW)
  w.str(bot)
  w.u8(window ? 1 : 0)
  if (window) {
    w.str(window.type)
    w.i32(window.containerSlots)
    w.i32(window.slots.length)
    for (const slot of window.slots) item(w, slot)
    item(w, window.cursor)
    w.i32(window.properties.length)
    for (const value of window.properties) w.i32(value)
    w.i32(window.trades.length)
    for (const trade of window.trades) {
      item(w, trade.first)
      item(w, trade.second)
      item(w, trade.result)
      w.u8(trade.disabled ? 1 : 0)
    }
  }
  return w.frame()
}

// The fleet crashed: its error, for the mod to keep. Only a relay sends it (the mod has its own fleet's output). No bot: an empty name.
function crash (text) {
  const w = new Writer(TYPES.CRASH)
  w.str('')
  w.bytes(Buffer.from(text, 'utf8'))
  return w.frame()
}

// What a relay wrote to its output, for the mod to keep (a chunk of lines). Only a relay sends it. No bot: an empty name.
function log (text) {
  const w = new Writer(TYPES.LOG)
  w.str('')
  w.bytes(Buffer.from(text, 'utf8'))
  return w.frame()
}

// A relay's answer to the mod's HELLO: it runs the mod's scripts. No bot: an empty name.
function welcome () {
  const w = new Writer(TYPES.WELCOME)
  w.str('')
  return w.frame()
}

// A relay running other scripts than the mod's is pulling them and restarting; WELCOME or
// UPDATE_FAILED follow. `text` says from what to what. No bot: an empty name.
function updating (text) {
  const w = new Writer(TYPES.UPDATING)
  w.str('')
  w.bytes(Buffer.from(text, 'utf8'))
  return w.frame()
}

// The relay could not get to the mod's scripts (`text` why): it stays up on its own and closes this
// connection. No bot: an empty name.
function updateFailed (text) {
  const w = new Writer(TYPES.UPDATE_FAILED)
  w.str('')
  w.bytes(Buffer.from(text, 'utf8'))
  return w.frame()
}

// The fleet's profile (see profiler.js): `id` the PROFILE_REQUEST's, 0 when the fleet took it on an
// event of its own, `reason` what it was taken for, `text` its rows. No bot: an empty name.
function profile (id, reason, text) {
  const w = new Writer(TYPES.PROFILE)
  w.str('')
  w.i32(id)
  w.str(reason)
  w.bytes(Buffer.from(text, 'utf8'))
  return w.frame()
}

// The frames that carry columns of the mod's world: a relay's world is its own, none of them go to the mod.
const CHUNK_TYPES = new Set([TYPES.CLAIM, TYPES.READY, TYPES.CHANGED, TYPES.BLOCK_ENTITY_UPDATE, TYPES.UNLOAD, TYPES.STATE_NAMES, TYPES.LOAD])

// `buffer` holds whole frames, one after the other (length included); those about columns left out.
// Null when none is left.
function withoutChunkFrames (buffer) {
  const kept = []
  for (let offset = 0; offset < buffer.length;) {
    const end = offset + 4 + buffer.readUInt32BE(offset)
    if (end > buffer.length) throw new Error('truncated frame')
    if (!CHUNK_TYPES.has(buffer[offset + 4])) kept.push(buffer.subarray(offset, end))
    offset = end
  }
  return kept.length === 0 ? null : Buffer.concat(kept)
}

// A mod -> fleet frame (without its length) as an object.
function decode (frame) {
  const r = new Reader(frame)
  const type = r.u8()
  let message
  switch (type) {
    case TYPES.RELEASE:
      message = { type, bot: r.str(), slot: r.i32() }
      break
    case TYPES.LOADED:
      // version: the snapshot's Minecraft version, null when the mod has none.
      message = { type, bot: r.str(), key: r.key(), slot: r.i32() }
      message.version = r.u8() ? r.str() : null
      break
    case TYPES.SPAWN:
      message = { type, bot: r.str(), host: r.str(), port: r.i32() }
      break
    case TYPES.QUIT:
      message = { type, bot: r.str() }
      break
    case TYPES.PROFILE_REQUEST:
      message = { type, bot: r.str(), id: r.i32(), reason: r.str() }
      break
    case TYPES.WINDOW_CLICK:
      // A click on the bot's inventory window, as the player's own client sends one: slot (-999
      // outside), mouse button and mode (0 pickup, 1 quick move, 2 swap, 3 clone, 4 throw, 5 drag,
      // 6 pickup all) and its id, counting up from 1 for each bot.
      message = { type, bot: r.str(), slot: r.i32(), button: r.u8(), mode: r.u8(), id: r.i32() }
      break
    case TYPES.TRADE_SELECT:
      // The villager trade the bot's open window shows, as the game's arrows pick it.
      message = { type, bot: r.str(), trade: r.i32() }
      break
    case TYPES.ALLOWED_PLACE: {
      // The blocks the bot may place, as the mod keys them: name@metadata.
      const bot = r.str()
      const count = r.i32()
      if (count < 0) throw new Error(`bad block count ${count}`)
      message = { type, bot, blocks: Array.from({ length: count }, () => r.str()) }
      break
    }
    case TYPES.HELLO:
      // version: the commit the mod was built at.
      message = { type, bot: r.str(), version: r.str() }
      break
    default:
      if (!PRIMITIVES.has(type)) throw new Error(`unknown message type ${type} from the mod`)
      // A primitive for the bot: action as actions.js takes it.
      message = { type, bot: r.str(), action: primitive(r, type) }
  }
  r.end(type)
  return message
}

// The fields of a primitive's frame after the bot, as the object actions.js runs.
function primitive (r, type) {
  const a = { action: PRIMITIVES.get(type) }
  switch (type) {
    case TYPES.GOTO: {
      // u8 kind: 0 a spot to stand on, 1 an area to stand in (min x, y, z, max x, y, z), 2 a block to stand in sight of.
      const kind = r.u8()
      if (kind === 0) a.spot = r.pos()
      else if (kind === 1) a.area = { minX: r.i32(), minY: r.i32(), minZ: r.i32(), maxX: r.i32(), maxY: r.i32(), maxZ: r.i32() }
      else if (kind === 2) a.look = r.pos()
      else throw new Error(`goto of unknown kind ${kind}`)
      break
    }
    case TYPES.INTERACT: {
      // u8 button (0 left, 1 right), u8 target kind (0 block at i32 x, y, z, 1 entity by its UUID), u8 expect kind
      // (0 none: the interaction's own; 1 window of a type, 2 block taking a state, 3 block changed,
      // 4 block filled, 5 entity hurt by its id) and its fields, deadline, turn speed.
      const button = r.u8()
      if (button > 1) throw new Error(`interact of unknown button ${button}`)
      a.button = button ? 'right' : 'left'
      const target = r.u8()
      if (target === 0) a.target = { kind: 'block', ...r.pos() }
      else if (target === 1) a.target = { kind: 'entity', uuid: r.str() }
      else throw new Error(`interact target of unknown kind ${target}`)
      const expect = r.u8()
      if (expect === 1) a.expect = { kind: 'window', type: r.str() }
      else if (expect === 2) a.expect = { kind: 'block', ...r.pos(), state: r.str() }
      else if (expect === 3) a.expect = { kind: 'changed', ...r.pos() }
      else if (expect === 4) a.expect = { kind: 'filled', ...r.pos() }
      else if (expect === 5) a.expect = { kind: 'hurt', id: r.i32() }
      else if (expect !== 0) throw new Error(`interact expect of unknown kind ${expect}`)
      r.deadline(a)
      r.turnSpeed(a)
      break
    }
    case TYPES.BREAK:
      // i32 x, y, z, deadline, turn speed.
      a.target = r.pos()
      r.deadline(a)
      r.turnSpeed(a)
      break
    case TYPES.BREAK_WSR:
    case TYPES.BREAK_WSRP:
    case TYPES.BREAK_WSRPA:
      // i32 x, y, z, i32 radius, break_wsrp's and break_wsrpa's i32 x, y, z it ends in, deadline, turn speed.
      a.target = r.pos()
      a.radius = r.i32()
      if (type !== TYPES.BREAK_WSR) a.end = r.pos()
      r.deadline(a)
      r.turnSpeed(a)
      break
    case TYPES.PLACE:
    case TYPES.PLACE_WSR:
    case TYPES.PLACE_WSRP:
    case TYPES.PLACE_WSRPA:
      // i32 x, y, z, place_wsr's, place_wsrp's and place_wsrpa's i32 radius, place_wsrp's and place_wsrpa's i32 x, y, z it ends in, u8 state
      // present and the state the block takes, deadline, turn speed.
      a.target = r.pos()
      if (type !== TYPES.PLACE) a.radius = r.i32()
      if (type === TYPES.PLACE_WSRP || type === TYPES.PLACE_WSRPA) a.end = r.pos()
      if (r.u8()) a.state = r.str()
      r.deadline(a)
      r.turnSpeed(a)
      break
    case TYPES.MOVE:
      a.from = r.i32()
      a.to = r.i32()
      break
    case TYPES.ITEM_FILL: {
      // i32 match count and each item match, the target slots, u8 sources present and the source slots,
      // u8 count present and i32 count.
      const count = r.i32()
      if (count < 0) throw new Error(`bad match count ${count}`)
      a.items = Array.from({ length: count }, () => r.matcher())
      a.targets = r.ints()
      if (r.u8()) a.sources = r.ints()
      if (r.u8()) a.count = r.i32()
      break
    }
    case TYPES.WAIT_SLOT: {
      // i32 count, each i32 slot, i32 min, u8 item match present and the match; deadline.
      const count = r.i32()
      if (count < 0) throw new Error(`bad slot count ${count}`)
      a.slots = Array.from({ length: count }, () => {
        const wait = { slot: r.i32(), min: r.i32() }
        if (r.u8()) wait.item = r.matcher()
        return wait
      })
      r.deadline(a)
      break
    }
    case TYPES.HOTBAR:
    case TYPES.SHIFT_MOVE:
      a.slot = r.i32()
      break
    case TYPES.SELECT_TRADE:
      a.trade = r.i32()
      break
    case TYPES.LOOK_AT:
      // f64 yaw, pitch (degrees, as the game has them), turn speed.
      a.yaw = r.f64()
      a.pitch = r.f64()
      r.turnSpeed(a)
      break
    case TYPES.DROP:
      a.slots = r.ints()
      break
  }
  return a
}

// A socket 'data' listener that calls onFrame with each whole frame, length stripped.
function frames (onFrame) {
  let pending = Buffer.alloc(0)
  return chunk => {
    pending = pending.length ? Buffer.concat([pending, chunk]) : chunk
    while (pending.length >= 4) {
      const length = pending.readUInt32BE(0)
      if (length === 0) throw new Error('empty frame from the mod')
      if (pending.length < 4 + length) break
      onFrame(pending.subarray(4, 4 + length))
      pending = pending.subarray(4 + length)
    }
  }
}

module.exports = { TYPES, PRIMITIVES, claim, ready, changed, load, stateNames,blockEntityUpdate, unload, botGone, botSpawned, path, inventory, state, chestLid, chest, columnGone, windowState, profile, crash, log, welcome, updating, updateFailed, withoutChunkFrames, decode, frames }

// What the parts of actions.js share that needs no bot: the reach, the faces, the keys, the order's
// fields, block and item names.
const look = require('../look')
const { Vec3 } = require('vec3')

const REACH_BLOCK = 4.5
const FACES = [new Vec3(0, -1, 0), new Vec3(0, 1, 0), new Vec3(1, 0, 0), new Vec3(-1, 0, 0), new Vec3(0, 0, 1), new Vec3(0, 0, -1)]
const CONTROLS = ['forward', 'back', 'left', 'right', 'jump', 'sprint', 'sneak']
// A physics tick, in seconds, as mineflayer has it.
const PHYSICS_TIMESTEP = 0.05

// A primitive with an expected condition fails after this long unless the order carries its own deadline.
const DEFAULT_DEADLINE = 5000
// How fast the bot turns its head to look unless the order carries its own turn_speed: this many times the usual player (see look.js HUMAN_SPEED).
const LOOK_SPEED = 1.05 * look.HUMAN_SPEED

const bare = name => name.replace(/^minecraft:/, '')
const full = name => name.includes(':') ? name : `minecraft:${name}`
const at = p => `${p.x},${p.y},${p.z}`

function required (value, what) {
  if (value === undefined || value === null) throw new Error(`${what} is required`)
  return value
}

function turnSpeedOf (a) {
  if (a.turn_speed === undefined) return LOOK_SPEED
  if (typeof a.turn_speed !== 'number' || !(a.turn_speed > 0)) throw new Error(`turn_speed must be a positive number of rad/s, got ${a.turn_speed}`)
  return a.turn_speed
}

function deadlineOf (a) {
  if (a.deadline === undefined) return DEFAULT_DEADLINE
  if (!Number.isInteger(a.deadline) || a.deadline <= 0) throw new Error(`${a.action} needs a deadline in ms, got ${a.deadline}`)
  return a.deadline
}

function describeItem (item) {
  return item ? `${item.count} x ${full(item.name)}` : 'nothing'
}

module.exports = { REACH_BLOCK, FACES, CONTROLS, PHYSICS_TIMESTEP, DEFAULT_DEADLINE, LOOK_SPEED, bare, full, at, required, turnSpeedOf, deadlineOf, describeItem }

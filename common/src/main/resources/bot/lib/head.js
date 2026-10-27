// The bot's head: one per bot, turned a point each physics tick along the look provider's ways (see
// look.js) to where it is sent.
const look = require('../look')
const { LOOK_SPEED, turnSpeedOf } = require('./util')

// How fast mineflayer turns the head on its own (the pathfinder's turns as it walks), in rad/s.
const DEFAULT_TURN_SPEED = 9

module.exports = ctx => {
  const { bot, log, spend, eyesNext, lookTo, turnSpeedTo } = ctx

  // The head, turned along the look provider's ways (see look.js) a point each physics tick: the
  // entity's yaw and pitch set to it, sent as they are (mineflayer turns the one it sends toward the
  // entity's by its yaw and pitch speeds, past any turn while a way goes on), the turn's promises
  // resolved on its last point's tick once that is sent: mineflayer sends the look after physicsTick
  // (physics.js tickPhysics, updatePosition), so a click then goes after it, the server seeing it first.
  // Not in physicsTick itself: a click there would go before the look.
  const head = new look.Head(0, 0, LOOK_SPEED)
  // The point the head is turned to, by turnTo, until stopped: kept on it as the bot walks (see steer).
  let headPoint = null
  let headWaiters = []
  function settleHead () {
    const waiters = headWaiters
    headWaiters = []
    for (const resolve of waiters) resolve()
  }
  // The ticks the head has turned on so far, for the benchmark (see turnTo).
  let turned = 0
  bot.on('physicsTick', () => {
    const point = head.tick()
    if (point) turned++
    spend(point !== null)
    if (!point) {
      // mineflayer's own turns again (the pathfinder's as it walks).
      bot.physics.yawSpeed = DEFAULT_TURN_SPEED
      bot.physics.pitchSpeed = DEFAULT_TURN_SPEED
      settleHead()
      return
    }
    bot.physics.yawSpeed = Infinity
    bot.physics.pitchSpeed = Infinity
    bot.entity.yaw = point.yaw
    bot.entity.pitch = point.pitch
    if (head.done) process.nextTick(settleHead)
  })
  bot.once('spawn', () => {
    bot.physics.yawSpeed = DEFAULT_TURN_SPEED
    bot.physics.pitchSpeed = DEFAULT_TURN_SPEED
  })

  // The head on its way to look at to ({ yaw, pitch }) at turnSpeed, from where it is: once there,
  // the server having seen it. Aimed again before, at the new to (see look.Head.aim).
  function aimHead (to, turnSpeed) {
    head.aim(bot.entity.yaw, bot.entity.pitch, to, turnSpeed)
    if (head.done) return Promise.resolve()
    return new Promise(resolve => headWaiters.push(resolve))
  }

  // Its way dropped where it is, and whoever waited on it let go.
  function stopHead () {
    headPoint = null
    head.stop()
    settleHead()
  }

  // Turns to look at point at the order's speed (turn_speed), done once the server has seen it get
  // there; false when the primitive was replaced on the way. box: the miss's on a face (see
  // aimMissed), the turn faster the wider it is (see turnSpeedTo).
  async function turnTo (action, a, point, box) {
    action.cleanups.push(stopHead)
    headPoint = point
    const to = lookTo(eyesNext(), point)
    // How wide the turn and how many ticks it took, for the benchmark (see turned).
    const angle = Math.hypot(Math.atan2(Math.sin(to.yaw - bot.entity.yaw), Math.cos(to.yaw - bot.entity.yaw)), to.pitch - bot.entity.pitch)
    const from = turned
    await aimHead(to, box ? turnSpeedTo(turnSpeedOf(a), to, point, box) : turnSpeedOf(a))
    if (!action.over) log(`turn ${(angle * 180 / Math.PI).toFixed(1)}° in ${turned - from} ticks`)
    return !action.over
  }

  // headPoint: the point turned to, read as it is now (see steer.js keepHead).
  return { head, headPoint: () => headPoint, aimHead, stopHead, turnTo }
}

const conv = require('mineflayer/lib/conversions')
const { required, turnSpeedOf } = require('../lib/util')

module.exports = ctx => {
  const { aimHead, done, stopHead } = ctx

  // Turns to a.yaw and a.pitch, in degrees as the game has them (yaw 0 south, growing towards west;
  // pitch 90 straight down), at the order's speed: done once the server has seen it get there.
  async function lookAt (action, a) {
    const yaw = required(a.yaw, 'yaw')
    const pitch = required(a.pitch, 'pitch')
    if (!Number.isFinite(yaw)) throw new Error(`yaw must be a number of degrees, got ${a.yaw}`)
    if (!Number.isFinite(pitch) || pitch < -90 || pitch > 90) throw new Error(`pitch must be degrees from -90 to 90, got ${a.pitch}`)
    action.cleanups.push(stopHead)
    await aimHead({ yaw: conv.fromNotchianYaw(yaw), pitch: conv.fromNotchianPitch(pitch) }, turnSpeedOf(a))
    if (!action.over) done(action)
  }

  return lookAt
}

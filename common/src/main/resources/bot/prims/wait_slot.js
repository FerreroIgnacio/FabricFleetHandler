const { required, deadlineOf, describeItem } = require('../lib/util')

module.exports = ctx => {
  const { bot, deadline, done, fail, itemMatcher, listen, slotOf, slotWindow } = ctx

  // Waits for every slot of a.slots to hold x min or more at once of the item its item matches (see
  // itemMatcher), or of any item without one.
  function waitSlot (action, a) {
    const window = slotWindow()
    const wanted = required(a.slots, 'slots')
    if (!Array.isArray(wanted) || wanted.length === 0) throw new Error('slots must list one or more slots')
    const waits = wanted.map((w, i) => {
      const min = w.min ?? 1
      if (!Number.isInteger(min) || min <= 0) throw new Error(`slots[${i}].min must be a positive integer, got ${min}`)
      const item = itemMatcher(w.item ?? {}, `slots[${i}].item`)
      return { slot: slotOf(window, w.slot, `slots[${i}].slot`), item, min }
    })
    // An output takes as long as it takes: its deadline is the longest of the lot unless the order has one.
    const ms = a.deadline === undefined ? 3600000 : deadlineOf(a)
    const metOne = w => {
      const there = window.slots[w.slot]
      return Boolean(there) && there.count >= w.min && w.item.test(there)
    }
    const describe = () => waits.filter(w => !metOne(w)).map(w => `${w.min} x ${w.item.describe()} on slot ${w.slot}, found ${describeItem(window.slots[w.slot])}`).join('; ')
    if (waits.every(metOne)) {
      done(action)
      return
    }
    listen(action, window, 'updateSlot', () => {
      if (waits.every(metOne)) done(action)
    })
    if (window !== bot.inventory) {
      listen(action, bot, 'windowClose', closed => {
        if (closed === window) fail(action, `the window was closed still missing ${describe()}`)
      })
    }
    deadline(action, ms, () => `missing ${describe()}`)
  }

  return waitSlot
}

const { describeItem } = require('../lib/util')

module.exports = ctx => {
  const { bot, log, done, fail, listen, sameItem, slotOf, slotWindow, syncedClick } = ctx

  // Throws the whole stack of each slot of a.slots (the open window's, or the inventory's with none)
  // where the bot looks, one throw (ctrl+Q) a slot, the window as the server has it after each (see
  // syncedClick). An empty slot is passed by; one still holding its stack after its throw fails it.
  // The window closed under it fails it there.
  async function drop (action, a) {
    const window = slotWindow()
    listen(action, bot, 'windowClose', closed => {
      if (closed === window) fail(action, 'the window was closed while dropping')
    })
    if (!Array.isArray(a.slots) || a.slots.length === 0) throw new Error('slots must list one or more slots')
    const slots = [...new Set(a.slots.map((s, i) => slotOf(window, s, `slots[${i}]`)))]
    if (window.selectedItem) throw new Error(`expected an empty cursor to throw from slots, found ${describeItem(window.selectedItem)}`)
    let dropped = 0
    for (const slot of slots) {
      const item = window.slots[slot]
      if (!item) continue
      await syncedClick(window, slot, 1, 4)
      if (action.over) return
      const left = window.slots[slot]
      if (left && sameItem(left, item) && left.count === item.count) throw new Error(`expected slot ${slot} emptied by its throw, found ${describeItem(left)} still on it`)
      dropped += item.count - (left && sameItem(left, item) ? left.count : 0)
    }
    log(`drop threw ${dropped} items off ${slots.length} slots`)
    done(action)
  }

  return drop
}

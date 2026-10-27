const { describeItem } = require('../lib/util')

module.exports = ctx => {
  const { bot, log, done, fail, listen, sameItem, shiftClick, slotOf, slotWindow } = ctx

  // Shift-clicks slot a.slot (the open window's, or the inventory's with none): its stack moved where the
  // server puts it (the other part of the window), the window as the server has it after (see
  // syncedClick). An empty slot fails it; the window closed under it too.
  async function shiftMove (action, a) {
    const window = slotWindow()
    listen(action, bot, 'windowClose', closed => {
      if (closed === window) fail(action, 'the window was closed while shift-clicking')
    })
    const slot = slotOf(window, a.slot, 'slot')
    const item = window.slots[slot]
    if (!item) throw new Error(`expected an item on slot ${slot} to shift-click, found none`)
    if (window.selectedItem) throw new Error(`expected an empty cursor to shift-click, found ${describeItem(window.selectedItem)}`)
    await shiftClick(window, slot)
    if (action.over) return
    const left = window.slots[slot]
    log(`shift_move moved ${item.count - (left && sameItem(left, item) ? left.count : 0)} of ${describeItem(item)} off slot ${slot}`)
    done(action)
  }

  return shiftMove
}

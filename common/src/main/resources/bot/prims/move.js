const { full, describeItem } = require('../lib/util')

module.exports = ctx => {
  const { bot, listen, sameItem, done, slotOf, slotWindow, windowType } = ctx

  // What the server adds to each slot of the window by itself while it runs, an item picked up off the
  // ground landing on one: the slot changes that come in a set_slot, told apart from the clicks' own
  // (mineflayer does those on its copy, the server confirms them without one). A slot the server puts
  // another item on than it held counts too, as what it holds after.
  function serverChanges (action, window) {
    const added = new Map()
    let packet = null
    const before = p => { if (p.windowId === window.id) packet = p }
    const after = () => { packet = null }
    // Around mineflayer's own handler, which sets the slot then and there.
    bot._client.prependListener('set_slot', before)
    bot._client.on('set_slot', after)
    action.cleanups.push(() => {
      bot._client.removeListener('set_slot', before)
      bot._client.removeListener('set_slot', after)
    })
    listen(action, window, 'updateSlot', (slot, oldItem, newItem) => {
      if (!packet) return
      const was = oldItem && newItem && !sameItem(oldItem, newItem) ? 0 : oldItem?.count ?? 0
      added.set(slot, (added.get(slot) ?? 0) + (newItem?.count ?? 0) - was)
    })
    return added
  }

  // Moves the whole stack on slot a.from onto slot a.to, empty or holding the same item with room for it.
  // Whatever the server puts on either meanwhile (an item picked up) is told apart from what was moved.
  async function move (action, a) {
    const window = slotWindow()
    const from = slotOf(window, a.from, 'from')
    const to = slotOf(window, a.to, 'to')
    if (from === to) throw new Error(`from and to are the same slot ${from}`)
    const item = window.slots[from]
    if (!item) throw new Error(`expected an item on slot ${from}, found nothing`)
    const there = window.slots[to]
    if (there && !sameItem(there, item)) throw new Error(`expected slot ${to} empty or holding ${full(item.name)}, found ${describeItem(there)}`)
    const room = item.stackSize - (there?.count ?? 0)
    if (room < item.count) throw new Error(`expected room for ${item.count} of ${full(item.name)} on slot ${to}, found ${room} (faltaron ${item.count - room})`)
    if (window.selectedItem) throw new Error(`expected an empty cursor, found ${describeItem(window.selectedItem)}`)

    const before = there?.count ?? 0
    const added = serverChanges(action, window)
    // A primitive that takes over leaves this one's clicks undone from there on.
    await bot.clickWindow(from, 0, 0)
    if (action.over) return
    // What the click took, an item picked up onto the slot before it among it; from here on what lands on the slot is the server's.
    const moved = window.selectedItem
    if (!moved || !sameItem(moved, item)) throw new Error(`expected ${full(item.name)} on the cursor taken from slot ${from}, found ${describeItem(moved)}`)
    const count = moved.count
    added.delete(from)
    await bot.clickWindow(to, 0, 0)
    if (action.over) return
    if (window.selectedItem) throw new Error(`expected all ${count} of ${full(item.name)} put on slot ${to}, ${describeItem(window.selectedItem)} left on the cursor`)

    const want = before + count + (added.get(to) ?? 0)
    const after = window.slots[to]
    if (!after || !sameItem(after, item) || after.count !== want) {
      throw new Error(`expected ${want} x ${full(item.name)} on slot ${to} (${before} there, ${count} moved, ${added.get(to) ?? 0} the server's), found ${describeItem(after)}`)
    }
    const left = added.get(from) ?? 0
    // A villager's result shows the next trade at once while the inputs still pay one (see bot.js merchant).
    const result = windowType(window) === 'minecraft:villager' && from === 2
    if (!result && (window.slots[from]?.count ?? 0) !== left) {
      throw new Error(`expected slot ${from} ${left ? `holding the ${left} the server put on it` : 'empty'}, found ${describeItem(window.slots[from])}`)
    }
    done(action)
  }

  return move
}

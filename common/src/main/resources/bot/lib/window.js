// The open window and its clicks, as the server has them.
const { once } = require('events')
const { full, required } = require('./util')

module.exports = ctx => {
  const { bot, name, rawClick } = ctx

  function windowType (window) {
    const type = typeof window.type === 'string' ? window.type : bot.registry.windows?.[window.type]?.name
    if (!type) throw new Error(`${name}: window of unknown type ${window.type}`)
    return full(type)
  }

  function openWindow (what) {
    const window = bot.currentWindow
    if (!window) throw new Error(`expected an open window to ${what}, found none`)
    return window
  }

  // The window the slots of an order are on: the open one, else the bot's inventory.
  function slotWindow () {
    return bot.currentWindow ?? bot.inventory
  }

  function slotOf (window, value, what) {
    const slot = required(value, what)
    if (!Number.isInteger(slot) || slot < 0 || slot >= window.slots.length) throw new Error(`${what} must be a slot of the window (0..${window.slots.length - 1}), got ${slot}`)
    return slot
  }

  const sameItem = (x, y) => x.type === y.type && x.metadata === y.metadata

  // What 1.12's server sends after a click it rejects: the transaction refused, then the whole window
  // (window_items, which mineflayer takes) and the cursor (set_slot -1 -1, which it leaves aside: kept
  // here on the open window). The server did the click on its own window all the same.
  let rejections = 0
  let resyncs = 0
  let itemClass
  bot._client.on('transaction', ({ accepted }) => {
    if (!accepted) rejections++
  })
  bot._client.on('set_slot', ({ windowId, slot, item }) => {
    if (windowId !== -1 || slot !== -1) return
    slotWindow().selectedItem = (itemClass ??= require('prismarine-item')(bot.registry)).fromNotch(item)
    resyncs++
    bot.emit('windowResync')
  })

  // A click the server rejected, thrown once its window and cursor are in.
  class Rejected extends Error {}

  // A left (0) or right (1) click of mineflayer's on the slot; one the server rejects throws a Rejected
  // once the server's window and cursor came, the window as the server has it.
  async function clickSlot (slot, button) {
    const rejected = rejections
    const synced = resyncs
    try {
      await bot.clickWindow(slot, button, 0)
    } catch (e) {
      if (rejections === rejected) throw e
      if (resyncs === synced) await once(bot, 'windowResync')
      throw new Rejected(`the server rejected a click on slot ${slot}`)
    }
  }

  // A click (a shift click, mode 1; a throw, mode 4) sent carrying an item no slot holds (127 of them):
  // the server rejects it, its own move done all the same, and sends the whole window and the cursor.
  // The window is then the server's, whatever mineflayer's copy (prismarine-windows) would have made of
  // the move, which is not the server's for most windows (and has no throw); an accepted click the
  // server never corrects.
  const UNHELD = { blockId: 1, itemCount: 127, itemDamage: 0 }
  // One at a time: a click the server rejects leaves it taking no click from the bot until the bot
  // confirms the rejection (1.12, NetHandlerPlayServer: not synced until then), so another sent in
  // the meantime (the hold of a primitive that replaced this one's) is dropped with no answer, and
  // waited on for ever. Each waits for the ones before it, whatever their end.
  let clicking = Promise.resolve()
  function syncedClick (window, slot, button, mode) {
    const click = clicking.then(() => syncedClickNow(window, slot, button, mode))
    clicking = click.then(() => {}, () => {})
    return click
  }
  async function syncedClickNow (window, slot, button, mode) {
    const synced = resyncs
    try {
      await rawClick(window, slot, button, mode, UNHELD, true)
    } catch (e) {
      if (!e.rejected) throw e
      if (resyncs === synced) await once(bot, 'windowResync')
      return
    }
    throw new Error(`expected the server to reject the click (mode ${mode}) on slot ${slot} carrying ${UNHELD.itemCount} of an item, it took it: the window is not the server's`)
  }
  const shiftClick = (window, slot, button = 0) => syncedClick(window, slot, button, 1)

  return { windowType, openWindow, slotWindow, slotOf, sameItem, Rejected, clickSlot, syncedClick, shiftClick }
}

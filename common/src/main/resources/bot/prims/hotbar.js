module.exports = ctx => {
  const { bot, done, slotOf, slotWindow } = ctx

  // Takes the hotbar slot a.slot, of the window its slots are on, into its hand: done as it is sent,
  // the server confirms no change of hand.
  function hotbar (action, a) {
    const window = slotWindow()
    const slot = slotOf(window, a.slot, 'slot')
    const index = slot - window.hotbarStart
    if (index < 0 || index > 8) throw new Error(`only hotbar items: expected a hotbar slot (${window.hotbarStart}..${window.hotbarStart + 8}), got ${slot}`)
    bot.setQuickBarSlot(index)
    done(action)
  }

  return hotbar
}

module.exports = ctx => {
  const { bot, done, fail, listen, openWindow, syncedClick } = ctx

  // Done once the server has it closed: it never says so, but it takes packets in order, so a click it
  // rejects (outside the inventory, carrying an item it does not have) answered means the close came
  // before it. mineflayer's windowClose is its own copy closed, at the moment it sends.
  function closeWindow (action) {
    const window = openWindow('close')
    listen(action, bot, 'windowClose', () => {
      syncedClick(bot.inventory, -999, 0, 0).then(() => done(action), e => fail(action, e.message))
    })
    bot.closeWindow(window)
  }

  return closeWindow
}

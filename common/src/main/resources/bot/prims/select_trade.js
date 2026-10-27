const { required } = require('../lib/util')

module.exports = ctx => {
  const { selectTrade, trades, done, fail, openWindow, windowType } = ctx

  // Picks the trade a.trade of the open villager window, its result worked out from the inputs on it:
  // done as it is sent, the server confirms no pick. A used up trade is picked all the same, as the
  // game does, and fails the primitive: nothing would come of it.
  function selectTradeOf (action, a) {
    const window = openWindow('pick a trade on')
    const type = windowType(window)
    if (type !== 'minecraft:villager') throw new Error(`expected a villager window to pick a trade on, found window "${type}"`)
    const recipes = trades(window)
    if (!recipes) throw new Error('expected the villager\'s trades, the server has not listed them yet')
    const trade = required(a.trade, 'trade')
    if (!Number.isInteger(trade) || trade < 0 || trade >= recipes.length) throw new Error(`trade must be one of the villager's (0..${recipes.length - 1}), got ${trade}`)
    selectTrade(window, trade)
    if (recipes[trade].disabled) {
      fail(action, `expected trade ${trade} in stock, found it used up`)
      return
    }
    done(action)
  }

  return selectTradeOf
}

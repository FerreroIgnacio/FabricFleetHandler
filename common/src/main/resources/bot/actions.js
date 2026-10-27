// The action primitives of one bot, and its state: idle, doing <primitive>, error <primitive>: <message>.
// Each ends on the event that confirms its effect; the only time in here is a deadline, which only
// ever fails a primitive with an expected condition (interact, break, place, wait_slot) and is
// settled before it by the event. A failure never kills the process: the bot goes to `error` with
// what was expected and what was found, and stays there until the next primitive. A death cancels
// the primitive running: `error <primitive>: canceled by death`.
// Each primitive is prims/<its name>.js; what they share, lib/ (see actionsSplit.txt).

// The parts of lib/, each made with what the ones before it made (see ctx): in this order.
const PARTS = ['lifecycle', 'path', 'aim', 'head', 'window', 'items', 'hold', 'pose', 'steer', 'walk', 'blocks', 'withSearch', 'withSearchFromEnd', 'withSearchAsync']

// publish(kind, primitive, message): the state to the mod, kind idle | doing | error. walk(goal):
// the bot's pathfinder takes the goal (see bot.js). trades(window): the villager window's trades, null
// until the server lists them; selectTrade(window, trade): picks one (see bot.js merchant).
// draw(vis): what a wsr primitive shows to the mod, null to clear it (see show); drawBands(boxes):
// the band primitives' boxes, running and held (see showBands).
module.exports = function actions ({ bot, name, log, publish, walk, draw, drawBands, blockStates, trades, selectTrade, rawClick }) {
  // What the parts and the primitives take theirs from: these, and what each part made.
  const ctx = { bot, name, log, publish, walk, draw, drawBands, blockStates, trades, selectTrade, rawClick }
  for (const part of PARTS) {
    for (const [key, value] of Object.entries(require(`./lib/${part}`)(ctx))) {
      if (key in ctx) throw new Error(`expected ${key} of lib/${part} to be its own, found it made before it`)
      ctx[key] = value
    }
  }

  ctx.primitives = {
    goto: require('./prims/goto')(ctx),
    interact: require('./prims/interact')(ctx),
    break: require('./prims/break')(ctx),
    place: require('./prims/place')(ctx),
    close_window: require('./prims/close_window')(ctx),
    move: require('./prims/move')(ctx),
    item_fill: require('./prims/item_fill')(ctx),
    wait_slot: require('./prims/wait_slot')(ctx),
    hotbar: require('./prims/hotbar')(ctx),
    select_trade: require('./prims/select_trade')(ctx),
    look_at: require('./prims/look_at')(ctx),
    drop: require('./prims/drop')(ctx),
    shift_move: require('./prims/shift_move')(ctx),
    stop: require('./prims/stop')(ctx),
    break_wsr: require('./prims/break_wsr')(ctx),
    place_wsr: require('./prims/place_wsr')(ctx),
    break_wsrp: require('./prims/break_wsrp')(ctx),
    place_wsrp: require('./prims/place_wsrp')(ctx),
    break_wsrpa: require('./prims/break_wsrpa')(ctx),
    place_wsrpa: require('./prims/place_wsrpa')(ctx),
    break_pac: require('./prims/break_pac')(ctx),
    band: require('./prims/band')(ctx)
  }

  const { windowType, cancel, setAllowedPlace, syncedClick, clearQ, currentId, run } = ctx
  return { windowType, cancel, setAllowedPlace, syncedClick, clearQ, currentId, run }
}

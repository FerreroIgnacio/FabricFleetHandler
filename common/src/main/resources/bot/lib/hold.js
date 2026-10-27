// What the bot holds: a block it may place, the best tool for a block, and how long the dig takes with it.
const { PHYSICS_TIMESTEP, full, describeItem } = require('./util')

// minecraft-data's dig times come out short of the server's at times: a dig planned on the way
// (break_wsrpa) takes this much of its time (an exception, see slackExceptions.txt).
const DIG_SLACK = 1.4

module.exports = ctx => {
  const { bot, log, slotWindow, sameItem, syncedClick } = ctx

  // The blocks it may place, keyed name@metadata as the mod sends them (see holdAllowed); null until it does.
  let allowedPlace = null

  const allowed = item => Boolean(item) && allowedPlace.has(`${full(item.name)}@${item.metadata}`)

  // The slot of the window its slots are on whose item passes test: the hotbar slot in its hand
  // first, then the rest of the hotbar, then the rest of the inventory; undefined with none.
  function slotWith (test) {
    const window = slotWindow()
    const hand = window.hotbarStart + bot.quickBarSlot
    const slots = [hand]
    for (let i = 0; i < 9; i++) slots.push(window.hotbarStart + i)
    for (let s = window.inventoryStart; s < window.hotbarStart; s++) slots.push(s)
    return slots.find(s => test(window.slots[s]))
  }

  // The slot with a block it may place (see slotWith). Failed with none.
  function allowedSlot () {
    if (allowedPlace === null) throw new Error('expected the blocks it may place from the mod, found none sent')
    const slot = slotWith(allowed)
    if (slot === undefined) throw new Error(`expected a block it may place in its inventory (${allowedPlace.size} allowed), found none`)
    return slot
  }

  // A block it may place into its hand (see allowedSlot). False when the primitive was replaced on the way.
  function holdAllowed (action) {
    return hold(action, allowedSlot())
  }

  // The best tool it has to break block into its hand, as the pathfinder picks it (the fastest dig);
  // none, the hand left as it is. False when the primitive was replaced on the way.
  async function holdBestTool (action, block) {
    const best = bot.pathfinder.bestHarvestTool(block)
    if (!best) return true
    // What it holds as fast as the best (a tie: the pick on gravel, any item on dirt) stays in hand:
    // bestHarvestTool takes the first of a tie, off the main inventory before the hotbar, and swapping
    // it in would put the pick out of the hand for the next block.
    const held = bot.heldItem
    if (held && digTimeWith(block, held) <= digTimeWith(block, best)) return true
    const slot = slotWith(item => Boolean(item) && sameItem(item, best))
    if (slot === undefined) throw new Error(`expected ${full(best.name)} in its inventory, found it gone`)
    return hold(action, slot)
  }

  // The item at slot into its hand: taken off the hotbar, or swapped onto the hotbar slot in hand off
  // the rest of the inventory, as the number keys do. False when the primitive was replaced on the way.
  async function hold (action, slot) {
    const window = slotWindow()
    const index = slot - window.hotbarStart
    if (index >= 0 && index <= 8) {
      if (index !== bot.quickBarSlot) bot.setQuickBarSlot(index)
      return true
    }
    const item = window.slots[slot]
    await syncedClick(window, slot, bot.quickBarSlot, 2)
    if (action.over) return false
    const held = window.slots[window.hotbarStart + bot.quickBarSlot]
    if (!held || !sameItem(held, item)) throw new Error(`expected ${full(item.name)} in hand swapped off slot ${slot}, found ${describeItem(held)}`)
    return true
  }

  // The ms the block's dig takes with item in hand, standing on the ground, as bestHarvestTool weighs it.
  function digTimeWith (block, item) {
    return block.digTime(item.type, false, false, false, item.enchants, bot.entity.effects)
  }

  // The ms breaking block takes with the best tool it has in hand (see holdBestTool), on the ground.
  function digMs (block) {
    const best = bot.pathfinder.bestHarvestTool(block)
    return block.digTime(best ? best.type : null, false, false, false, best ? best.enchants : [], bot.entity.effects)
  }

  // The ms breaking block takes with what is in hand now, where it is now: off the ground or in water
  // the server weighs it 5 times slower (a dig it takes as a start, never answered).
  function heldMs (block, grounded = false, onGround = bot.entity.onGround) {
    const held = bot.heldItem
    const off = !grounded && !onGround
    const wet = !grounded && bot.entity.isInWater
    return block.digTime(held ? held.type : null, false, wet, off, held ? held.enchants : [], bot.entity.effects)
  }

  // The ticks of digMs, DIG_SLACK more (an exception, see slackExceptions.txt).
  function digTicks (block) {
    return Math.ceil(digMs(block) * DIG_SLACK / (PHYSICS_TIMESTEP * 1000))
  }

  function setAllowedPlace (blocks) {
    allowedPlace = new Set(blocks)
    log(`may place ${allowedPlace.size} blocks`)
  }

  return { setAllowedPlace, allowedSlot, holdAllowed, holdBestTool, digMs, heldMs, digTicks }
}

const { full, required, describeItem } = require('../lib/util')

module.exports = ctx => {
  const { bot, log, Rejected, clickSlot, done, fail, itemMatcher, listen, sameItem, shiftClick, shiftSpread, slotOf, slotWindow } = ctx

  // Moves the items a.item matches (see itemMatcher), or a.items (a list of them: any of them), onto the slots of a.targets, from every other slot of
  // the window (or only a.sources), as much as fits and up to a.count if given: onto the same item with room, or
  // empty slots, in the order given. Targets filling up with sources left over is fine, and so is moving
  // nothing (no room on the targets, none of the items outside them): done with 0 moved, the window maybe
  // changed by another player or bot since whoever sent it looked at it.
  //
  // A click the server rejects (another player or bot changed the window first) is no failure: what the
  // cursor holds goes back where it came from and the moves go on from the window as the server has it.
  // How many it moved is counted on the side that is the bot's own inventory (the targets, else the
  // sources), no one else's clicks touching it. A source whose shift click puts its items on targets only, no more of
  // them than still wanted, goes in one shift click (see shiftSpread), the window the server's after
  // it. A rejection that leaves the window as one before did, since the last
  // move that went through, is no change of anyone else's: it fails. The window closed under it (by the
  // server: the bot moved off, the block broke) fails it there, the click on its way never answered.
  async function itemFill (action, a) {
    const window = slotWindow()
    listen(action, bot, 'windowClose', closed => {
      if (closed === window) fail(action, `the window was closed while moving items, ${describeItem(window.selectedItem)} on the cursor`)
    })
    const specs = a.items === undefined ? [required(a.item, 'item')] : a.items
    if (!Array.isArray(specs) || specs.length === 0) throw new Error(`items must list one or more matches, got ${JSON.stringify(a.items)}`)
    const matchers = specs.map((spec, i) => itemMatcher(spec, a.items === undefined ? 'item' : `items[${i}]`))
    const wanted = { test: item => matchers.some(m => m.test(item)) }
    const name = matchers.map(m => m.describe()).join(' or ')
    const list = (value, what) => {
      if (!Array.isArray(value) || value.length === 0) throw new Error(`${what} must list one or more slots`)
      return [...new Set(value.map((s, i) => slotOf(window, s, `${what}[${i}]`)))]
    }
    const targets = list(a.targets, 'targets')
    const limit = a.count ?? Infinity
    if (limit !== Infinity && (!Number.isInteger(limit) || limit <= 0)) throw new Error(`count must be a positive integer, got ${a.count}`)
    const from = (a.sources === undefined ? window.slots.map((_, i) => i) : list(a.sources, 'sources')).filter(s => !targets.includes(s))
    const sources = () => from.filter(s => wanted.test(window.slots[s]))
    if (window.selectedItem) throw new Error(`expected an empty cursor, found ${describeItem(window.selectedItem)}`)

    const own = s => s >= window.inventoryStart && s < window.inventoryEnd
    const bySources = !targets.every(own) && from.every(own)
    const total = slots => slots.reduce((n, s) => n + (wanted.test(window.slots[s]) ? window.slots[s].count : 0), 0)
    const first = bySources ? total(from) : total(targets)
    const moved = () => bySources ? first - total(from) : total(targets) - first
    // A shift click does the source's moves: all of its items onto targets, no more than still wanted.
    const shiftDoes = src => {
      const spread = shiftSpread(window, src)
      return spread !== null && spread.length > 0 && spread.every(([slot]) => targets.includes(slot)) &&
        spread.reduce((n, [, k]) => n + k, 0) <= limit - moved()
    }
    const state = () => window.slots.map(it => it ? `${it.type}:${it.metadata}:${it.count}` : '').join(',') + '|' + describeItem(window.selectedItem)

    // The next move: from a source onto a target that takes it, no more than still wanted; null with none.
    const nextMove = () => {
      const left = limit - moved()
      if (left <= 0) return null
      for (const src of sources()) {
        for (const tgt of targets) {
          const item = window.slots[src]
          const there = window.slots[tgt]
          if (there && !sameItem(there, item)) continue
          const room = item.stackSize - (there?.count ?? 0)
          const n = Math.min(item.count, room, left)
          if (n > 0) return { src, tgt, n, item }
        }
      }
      return null
    }

    // What the cursor holds after a rejection, back where it came from (or any other slot it came from,
    // or onto a target, being one of the items).
    const putBack = async src => {
      const item = window.selectedItem
      const places = [...new Set([...(src === null ? [] : [src]), ...from, ...(wanted.test(item) ? targets : [])])]
      for (const slot of places) {
        const there = window.slots[slot]
        if (!window.selectedItem) return
        if (there && (!sameItem(there, window.selectedItem) || there.count >= there.stackSize)) continue
        await clickSlot(slot, 0)
        if (action.over) return
      }
      if (window.selectedItem) throw new Error(`expected room to put back ${describeItem(window.selectedItem)} after a rejected click, found none`)
    }

    let src = null
    let rejected = 0
    const seen = new Set()
    while (!action.over) {
      try {
        if (window.selectedItem) await putBack(src)
        if (action.over) return
        const move = nextMove()
        if (!move) break
        const { tgt, n, item } = move
        src = move.src
        if (shiftDoes(src)) {
          await shiftClick(window, src)
          seen.clear()
          continue
        }
        const before = window.slots[tgt]?.count ?? 0
        if (n < Math.min(item.count, item.stackSize - before)) {
          // Part of the stack, the fewest clicks: the whole or half of it (right click) in the hand,
          // then one at a time back onto the source until n are left for the target, or n one at a
          // time onto the target and the rest back.
          const half = Math.ceil(item.count / 2)
          const ways = [
            { button: 0, back: item.count - n },
            { button: 0, onto: n }
          ]
          if (n <= half) ways.push({ button: 1, back: half - n }, { button: 1, onto: n })
          const way = ways.reduce((best, w) => (w.back ?? w.onto) < (best.back ?? best.onto) ? w : best)
          await clickSlot(src, way.button)
          if (action.over) return
          const ones = way.back ?? way.onto
          for (let i = 0; i < ones; i++) {
            await clickSlot(way.back !== undefined ? src : tgt, 1)
            if (action.over) return
          }
          await clickSlot(way.back !== undefined ? tgt : src, 0)
        } else {
          await clickSlot(src, 0)
          if (action.over) return
          await clickSlot(tgt, 0)
          if (action.over) return
          // What does not fit goes back where it came from.
          if (window.selectedItem) await clickSlot(src, 0)
        }
        if (action.over) return
        if (window.selectedItem) throw new Error(`expected an empty cursor after moving ${full(item.name)} from slot ${src} to slot ${tgt}, found ${describeItem(window.selectedItem)}`)
        // On the bot's own slots no one else's clicks add or take.
        const after = window.slots[tgt]
        if (own(tgt) && (!after || !sameItem(after, item) || after.count < before + n)) {
          throw new Error(`expected at least ${before + n} x ${full(item.name)} on slot ${tgt} (${before} there, ${n} moved), found ${describeItem(after)}`)
        }
        seen.clear()
      } catch (e) {
        if (!(e instanceof Rejected)) throw e
        if (action.over) return
        const now = state()
        if (seen.has(now)) throw new Error(`${e.message} on a window as it was at a rejection before, nothing else changing it (moving ${name})`)
        seen.add(now)
        rejected++
        log(`item_fill: ${e.message}, the window changed under it: going on from the server's`)
      }
    }
    if (action.over) return
    const why = moved() > 0 ? '' : sources().length === 0 ? `: no ${name} outside the target slots` : ': no room on the target slots'
    log(`item_fill moved ${moved()} x ${name}${rejected ? ` (${rejected} clicks rejected)` : ''}${why}`)
    done(action)
  }

  return itemFill
}

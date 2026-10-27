// Items with no click: where the server's shift click takes them, and whether one is what an order asks for.
const nbt = require('prismarine-nbt')
const { bare, full } = require('./util')

module.exports = ctx => {
  const { windowType } = ctx

  // The armor slot (5 to 8) or the offhand (45) of the inventory window an item goes to, as the server
  // sees it (MobEntity's slot for it); null for none.
  function equipmentSlot (item) {
    const n = bare(item.name)
    if (n.endsWith('_helmet') || n === 'skull' || n === 'pumpkin') return 5
    if (n.endsWith('_chestplate') || n === 'elytra') return 6
    if (n.endsWith('_leggings')) return 7
    if (n.endsWith('_boots')) return 8
    if (n === 'shield') return 45
    return null
  }

  // What one of 1.12's server's moves of a shift click does with the slot's stack (its window's
  // transferSlot): [start, end, reverse], the slots it goes into (insertItem); ['one', slot], one of
  // it onto that slot; 'stop', none. null when the server's choice hangs on what the mod does not know
  // (a furnace's smeltables and fuels, a brewing stand's ingredients, a horse's kind) or the slot is a
  // result (taking it crafts, trades or repairs): no shift click then.
  function shiftStep (window, slots, src, item) {
    const c = window.inventoryStart
    const end = window.inventoryEnd
    const hotbar = end - 9
    const fromInventory = src >= c && src < end
    // An inventory slot to the other part of it: the main inventory to the hotbar, the hotbar to the main.
    const swap = src < hotbar ? [hotbar, end, false] : [c, hotbar, false]
    switch (bare(windowType(window))) {
      case 'chest': case 'container': case 'dispenser': case 'dropper': case 'hopper': case 'shulker_box':
        return fromInventory ? [0, c, false] : [c, end, true]
      case 'EntityHorse':
        return fromInventory ? null : [c, end, true]
      case 'crafting_table':
        if (src === 0) return null
        return fromInventory ? swap : [c, end, false]
      case 'furnace':
        if (src === 2) return [c, end, true]
        return fromInventory ? null : [c, end, false]
      case 'brewing_stand':
        return fromInventory ? null : [c, end, true]
      case 'enchanting_table':
        if (!fromInventory) return [c, end, true]
        if (bare(item.name) === 'dye' && item.metadata === 4) return [1, 2, true]
        return slots[0] ? 'stop' : ['one', 0]
      case 'beacon':
        if (!fromInventory) return [c, end, true]
        if (!slots[0] && item.count === 1 && ['iron_ingot', 'gold_ingot', 'emerald', 'diamond'].includes(bare(item.name))) return [0, 1, false]
        return swap
      case 'villager':
      case 'anvil':
        if (src === 2) return null
        if (!fromInventory) return [c, end, false]
        return bare(windowType(window)) === 'villager' ? swap : [0, 2, false]
      case 'inventory': {
        if (src === 0) return null
        if (!fromInventory) return [c, end, false]
        const to = equipmentSlot(item)
        if (to !== null && !slots[to]) return [to, to + 1, false]
        return swap
      }
      default:
        return null
    }
  }

  // How many of an item a slot of the window takes, as the server has it.
  function slotLimit (window, slot) {
    const type = bare(windowType(window))
    if (type === 'inventory' && slot >= 5 && slot <= 8) return 1
    if ((type === 'beacon' || type === 'enchanting_table') && slot === 0) return 1
    if (type === 'brewing_stand' && slot <= 2) return 1
    if (type === 'EntityHorse' && slot <= 1) return 1
    return 64
  }

  // Where 1.12's server puts the stack of slot src shift-clicked, worked out on a copy of the window:
  // its moves one after another while the slot keeps some of it and the last one moved any (onto
  // the same item with room first, from start on, or end back reversed; then on the first empty slot
  // that takes it, no more than the slot does). [slot, n] each; null when shiftStep cannot tell.
  function shiftSpread (window, src) {
    const slots = window.slots.map(it => it && Object.assign(Object.create(Object.getPrototypeOf(it)), it))
    const spread = new Map()
    const add = (slot, n) => spread.set(slot, (spread.get(slot) ?? 0) + n)
    while (slots[src]) {
      const item = slots[src]
      const step = shiftStep(window, slots, src, item)
      if (step === null) return null
      if (step === 'stop') break
      let moved = 0
      if (step[0] === 'one') {
        slots[step[1]] = Object.assign(Object.create(Object.getPrototypeOf(item)), item, { count: 1 })
        moved = 1
      } else {
        const [start, stop, reverse] = step
        const order = Array.from({ length: stop - start }, (_, i) => reverse ? stop - 1 - i : start + i)
        if (item.stackSize > 1) {
          for (const slot of order) {
            const there = slots[slot]
            if (moved === item.count) break
            if (!there || slot === src || !item.constructor.equal(there, item, false)) continue
            const n = Math.min(item.count - moved, item.stackSize - there.count)
            if (n <= 0) continue
            there.count += n
            moved += n
            add(slot, n)
          }
        }
        // A shulker box holds no shulker box.
        const takes = slot => !(bare(windowType(window)) === 'shulker_box' && slot < window.inventoryStart && bare(item.name).endsWith('shulker_box'))
        const empty = moved < item.count ? order.find(slot => !slots[slot] && takes(slot)) : undefined
        if (empty !== undefined) {
          const n = Math.min(item.count - moved, slotLimit(window, empty))
          slots[empty] = Object.assign(Object.create(Object.getPrototypeOf(item)), item, { count: n })
          moved += n
          add(empty, n)
        }
      }
      if (step[0] === 'one') add(step[1], 1)
      if (moved === 0) break
      item.count -= moved
      if (item.count === 0) slots[src] = null
    }
    return [...spread]
  }

  // A tag of an item's NBT as prismarine-nbt parses it ({ type, value }) the same as another, whatever the
  // order of a compound's keys; undefined (no such tag) only as undefined.
  function sameTag (x, y) {
    if (!x || !y) return x === y
    return x.type === y.type && sameValue(x.type, x.value, y.value)
  }

  function sameValue (type, x, y) {
    if (type === 'compound') {
      const keys = Object.keys(x)
      return keys.length === Object.keys(y).length && keys.every(k => sameTag(x[k], y[k]))
    }
    // A list's value is { type, value: [...] }, each element of the list's type.
    if (type === 'list') return x.type === y.type && x.value.length === y.value.length && x.value.every((v, i) => sameValue(x.type, v, y.value[i]))
    // Byte, int and long arrays, and a long as [high, low]: element by element.
    if (Array.isArray(x)) return Array.isArray(y) && x.length === y.length && x.every((v, i) => String(v) === String(y[i]))
    return x === y
  }

  // An item's NBT as a compound tag; none as an empty one.
  const rootOf = item => item.nbt ?? { type: 'compound', name: '', value: {} }

  // The tag at a path (keys joined by dots) of a compound tag, undefined where nothing is.
  function tagAt (root, path) {
    let tag = root
    for (const key of path.split('.')) {
      if (tag?.type !== 'compound') return undefined
      tag = tag.value[key]
    }
    return tag
  }

  // The compound tag with the tags at paths taken off, the rest as it was.
  function without (root, paths) {
    const value = { ...root.value }
    for (const path of paths) {
      const [key, ...rest] = path.split('.')
      if (!(key in value)) continue
      if (rest.length === 0) delete value[key]
      else if (value[key].type === 'compound') value[key] = without(value[key], [rest.join('.')])
    }
    return { ...root, value }
  }

  // Which items an order means, told from a reference item (spec.nbt, its NBT in hex as the mod has it):
  //   name       that item, by name; none: any item
  //   metadata   that metadata; none: any
  //   match      these paths of the NBT (keys joined by dots) as the reference has them, or both without
  //   ignore     the whole NBT as the reference's but at these paths (exact: none ignored)
  // Neither match nor ignore: the NBT matters not. { test(item), describe() }.
  function itemMatcher (spec, what) {
    if (typeof spec !== 'object' || Array.isArray(spec)) throw new Error(`${what} must be an object (name, metadata, nbt, match, ignore), got ${JSON.stringify(spec)}`)
    const tests = []
    const parts = []
    if (spec.name !== undefined) {
      if (typeof spec.name !== 'string' || !spec.name) throw new Error(`${what}.name must be an item name, got ${spec.name}`)
      const name = bare(spec.name)
      tests.push(item => bare(item.name) === name)
      parts.push(`"${full(name)}"`)
    } else {
      parts.push('any item')
    }
    if (spec.metadata !== undefined) {
      if (!Number.isInteger(spec.metadata)) throw new Error(`${what}.metadata must be an integer, got ${spec.metadata}`)
      tests.push(item => item.metadata === spec.metadata)
      parts.push(`metadata ${spec.metadata}`)
    }
    const paths = (value, field) => {
      if (!Array.isArray(value) || !value.every(p => typeof p === 'string' && p && !p.split('.').includes(''))) {
        throw new Error(`${what}.${field} must list NBT paths (keys joined by dots), got ${JSON.stringify(value)}`)
      }
      return value
    }
    if (spec.match !== undefined && spec.ignore !== undefined) throw new Error(`${what} takes match or ignore, not both`)
    if (spec.match !== undefined || spec.ignore !== undefined) {
      if (typeof spec.nbt !== 'string' || !/^([0-9a-f]{2})*$/i.test(spec.nbt)) throw new Error(`${what}.nbt must be the reference item's NBT in hex, got ${spec.nbt}`)
      const reference = spec.nbt ? nbt.parseUncompressed(Buffer.from(spec.nbt, 'hex')) : rootOf({})
      if (spec.match !== undefined) {
        const match = paths(spec.match, 'match')
        tests.push(item => match.every(p => sameTag(tagAt(rootOf(item), p), tagAt(reference, p))))
        const shown = tag => tag ? JSON.stringify(nbt.simplify(tag)) : 'none'
        if (match.length) parts.push(`with ${match.map(p => `${p} ${shown(tagAt(reference, p))}`).join(', ')}`)
      } else {
        const ignore = paths(spec.ignore, 'ignore')
        const wanted = without(reference, ignore)
        tests.push(item => sameTag(without(rootOf(item), ignore), wanted))
        parts.push(`with NBT ${JSON.stringify(nbt.simplify(wanted))}${ignore.length ? ` but ${ignore.join(', ')}` : ''}`)
      }
    } else if (spec.nbt !== undefined) {
      throw new Error(`${what}.nbt says nothing without match or ignore`)
    }
    const described = parts.join(' ')
    return { test: item => Boolean(item) && tests.every(t => t(item)), describe: () => described }
  }

  return { shiftSpread, itemMatcher }
}

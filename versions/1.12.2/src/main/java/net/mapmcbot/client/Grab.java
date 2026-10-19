package net.mapmcbot.client;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.mapmcbot.area.Area;
import net.mapmcbot.area.ChestStore;
import net.mapmcbot.bot.BotInventory;
import net.mapmcbot.bot.BotRegistry;
import net.mapmcbot.bot.BotStatus;
import net.mapmcbot.bot.BotWindow;
import net.mapmcbot.job.JobRunner;
import net.minecraft.item.Item;
import net.minecraft.util.Identifier;

/**
 * A grab: the bot takes count of an item from the chests of an area (every block with an inventory the
 * mod keeps, see ChestTracker; a deposit leaves nothing in a furnace or a brewing stand), a plan: no primitive
 * of the fleet but a series of them the mod resolves: { action: grab, area: its id, item: a match (see ItemFilter),
 * count }. Chest by chest, as many as it takes: it walks next to one, opens it, item fills its
 * inventory from it (no more than is still wanted) and closes it, until it has them all.
 *
 * The chests known clean with the item go first, then the dirty ones, those last seen with it before
 * the rest; none left and still short, it fails. Each part is sent as the one before ends, by the
 * bot's state: nothing timed.
 *
 * The item is a list of matches (items: any of them counts; the old single item still reads), and with
 * full in place of count the grab fills the inventory (the deposit empties it of the item): done when no
 * slot has room left for it (none of it is left), an error if the chests are out before.
 *
 * A deposit ({ action: deposit, area, items, count }) is the grab the other way: the bot leaves count of
 * the item in the chests of the area, a chest with room (an empty slot) at a time; the chests known
 * clean with room go first, then the dirty ones. None left and still holding some, it fails.
 *
 * An index ({ action: index, area }) is the same walk without taking anything: the bot opens each
 * dirty chest of the area (the never seen too) and closes it, the tracker keeping what it saw, until
 * every chest of the area is clean.
 */
final class Grab implements JobRunner.Resolver, BotRegistry.StateListener, BotRegistry.WindowListener, BotRegistry.ActionListener {
	private enum Phase {
		WALK, OPEN, WINDOW, FILL, CLOSE
	}

	private static final class Plan {
		final Area area;
		/** The matches, any of them counting; null for an index. */
		final JsonArray items;
		final int count;
		/** The inventory filled (emptied, a deposit) instead of count. */
		final boolean full;
		final int target;
		final int had;
		/** Whether it leaves the item in the chests instead of taking it from them. */
		final boolean deposit;
		final JobRunner.Listener listener;
		final Set<ChestStore.Chest> visited = new HashSet<ChestStore.Chest>();
		ChestStore.Chest chest;
		/** Set before the plan is shown (status reads it on the render thread): walking to its first chest. */
		volatile Phase phase = Phase.WALK;
		/** A primitive sent and not yet seen going: an idle before that is the end of the one before it, not of this. */
		volatile boolean starting;
		/** The close_window went idle: the chest is left once the window's close comes too. */
		boolean closeIdle;
		/** The window as last seen while one was open, its inventory part what the bot has. */
		BotWindow window;

		Plan(Area area, JsonArray items, int count, boolean full, int had, boolean deposit, JobRunner.Listener listener) {
			this.area = area;
			this.items = items;
			this.count = count;
			this.full = full;
			this.had = had;
			this.deposit = deposit;
			this.target = full ? (deposit ? 0 : Integer.MAX_VALUE) : deposit ? had - count : had + count;
			this.listener = listener;
		}
	}

	/** The blocks a deposit leaves nothing in. */
	private static final Set<String> NO_DEPOSIT = new HashSet<String>(Arrays.asList("minecraft:furnace", "minecraft:lit_furnace", "minecraft:brewing_stand"));

	private final BotRegistry bots;
	private final ChestTracker chests;
	private final Map<String, Plan> plans = new ConcurrentHashMap<String, Plan>();
	/** Why each bot's last grab failed, until its next. */
	private final Map<String, String> errors = new ConcurrentHashMap<String, String>();

	Grab(BotRegistry bots, ChestTracker chests) {
		this.bots = bots;
		this.chests = chests;
		bots.addStateListener(this);
		bots.addWindowListener(this);
		bots.addActionListener(this);
	}

	/** The chests of the area the fleets see, into the store (see ChestTracker#scan). */
	void scanChests(Area area) {
		chests.scan(area);
	}

	/** A chest to show while a bot grabs: the one it is going to (current), or one it may look in after. */
	static final class Highlight {
		final String bot;
		final ChestStore.Chest chest;
		final boolean current;

		Highlight(String bot, ChestStore.Chest chest, boolean current) {
			this.bot = bot;
			this.chest = chest;
			this.current = current;
		}
	}

	/** The chests of every grab going on: each one's current, and the ones still to look in. */
	List<Highlight> highlights() {
		final List<Highlight> out = new ArrayList<Highlight>();

		for (Map.Entry<String, Plan> entry : plans.entrySet()) {
			final Plan plan = entry.getValue();

			if (plan.chest != null) {
				out.add(new Highlight(entry.getKey(), plan.chest, true));
			}

			for (ChestStore.Chest chest : order(plan)) {
				out.add(new Highlight(entry.getKey(), chest, false));
			}
		}

		return out;
	}

	@Override
	public boolean handles(String json) {
		final String action = new JsonParser().parse(json).getAsJsonObject().get("action").getAsString();
		return action.equals("grab") || action.equals("deposit") || action.equals("index");
	}

	/** What the bot's grab is doing, or why it failed; null with none. */
	String status(String bot) {
		final Plan plan = plans.get(bot);

		if (plan != null && plan.items == null) {
			return "index " + plan.area.getName() + ": " + order(plan).size() + " left, " + plan.phase.name().toLowerCase() + (plan.chest == null ? "" : " " + plan.chest);
		}

		if (plan != null) {
			return (plan.deposit ? "deposit " : "grab ") + describe(plan.items) + " " + (plan.window == null ? 0 : Math.abs(held(plan) - plan.had)) + "/" + (plan.full ? "full" : String.valueOf(plan.count)) + ": " + plan.phase.name().toLowerCase() + (plan.chest == null ? "" : " " + plan.chest);
		}

		return errors.containsKey(bot) ? "error " + errors.get(bot) : null;
	}

	@Override
	public void start(String bot, String json, JobRunner.Listener listener) {
		final JsonObject a = new JsonParser().parse(json).getAsJsonObject();
		final String areaId = a.get("area").getAsString();
		Area area = null;

		for (Area one : MapMcBotClient.areas().getAreas()) {
			if (one.getId().equals(areaId)) {
				area = one;
			}
		}

		errors.remove(bot);
		// A grab going on is over, this one in its place.
		takeOver(bot);

		if (area == null) {
			fail(bot, null, listener, a.get("action").getAsString() + ": no area " + areaId);
			return;
		}

		if (a.get("action").getAsString().equals("index")) {
			if (bots.getWindow(bot) != null) {
				fail(bot, null, listener, "index: expected no window open, found " + bots.getWindow(bot).getType());
				return;
			}

			final Plan plan = new Plan(area, null, 0, false, 0, false, listener);

			plans.put(bot, plan);

			chests.scan(area);
			nextChest(bot, plan);
			return;
		}

		final boolean deposit = a.get("action").getAsString().equals("deposit");
		final String name = deposit ? "deposit: " : "grab: ";
		final boolean full = a.has("full") && a.get("full").getAsBoolean();
		final int count = full ? 0 : a.get("count").getAsInt();
		final JsonArray items;

		if (a.has("items")) {
			items = a.getAsJsonArray("items");
		} else {
			items = new JsonArray();
			items.add(a.getAsJsonObject("item"));
		}

		if (items.size() == 0) {
			throw new IllegalArgumentException(name + "needs at least one item to match");
		}

		if (!full && count <= 0) {
			throw new IllegalArgumentException(name + "needs a positive count, got " + count);
		}

		final BotInventory inventory = bots.getInventory(bot);

		if (inventory == null) {
			fail(bot, null, listener, name + "no inventory of " + bot + " yet");
			return;
		}

		if (bots.getWindow(bot) != null) {
			fail(bot, null, listener, name + "expected no window open, found " + bots.getWindow(bot).getType());
			return;
		}

		// The main inventory and the hotbar, as a window's inventory part has them.
		int had = 0;

		for (BotInventory.Item item : inventory.getSlots().subList(9, 45)) {
			if (matches(items, item)) {
				had += item.getCount();
			}
		}

		// Nothing (or not enough) to leave: the deposit is done, not failed.
		if (deposit && had < Math.max(count, 1)) {
			listener.done();
			return;
		}

		// Already full: the grab is done.
		if (!deposit && full && !room(items, inventory.getSlots().subList(9, 45))) {
			listener.done();
			return;
		}

		final Plan plan = new Plan(area, items, count, full, had, deposit, listener);

		plans.put(bot, plan);

		chests.scan(area);
		nextChest(bot, plan);
	}

	/** The next chest to take from, walked to; none left, the grab fails. */
	private void nextChest(String bot, Plan plan) {
		final List<ChestStore.Chest> order = order(plan);

		// Indexed: every chest of the area seen.
		if (order.isEmpty() && plan.items == null) {
			plans.remove(bot);
			plan.listener.done();
			return;
		}

		if (order.isEmpty() && plan.deposit) {
			final int left = plan.window == null ? plan.count : held(plan) - plan.target;
			fail(bot, plan, plan.listener, "deposit: no space in area " + plan.area.getName() + " for " + (plan.window == null && plan.full ? plan.had : left) + " x " + describe(plan.items) + ": none of its chests has room");
			return;
		}

		if (order.isEmpty() && plan.full) {
			final int got = plan.window == null ? 0 : held(plan) - plan.had;
			fail(bot, plan, plan.listener, "grab: expected the inventory filled with " + describe(plan.items) + " from area " + plan.area.getName() + ", found room left after its chests (" + got + " taken)");
			return;
		}

		if (order.isEmpty()) {
			final int got = plan.window == null ? 0 : held(plan) - plan.had;
			fail(bot, plan, plan.listener, "grab: expected " + String.valueOf(plan.count) + " x " + describe(plan.items) + " in area " + plan.area.getName() + ", found " + got + " in its chests");
			return;
		}

		plan.chest = order.get(0);
		plan.visited.add(plan.chest);
		plan.phase = Phase.WALK;
		walk(bot, plan);
	}

	/** The chests still to look in, in the order they would be: clean with the item, dirty with it, the other dirty ones. */
	private static List<ChestStore.Chest> order(Plan plan) {
		final List<ChestStore.Chest> withIt = new ArrayList<ChestStore.Chest>();
		final List<ChestStore.Chest> dirtyWithIt = new ArrayList<ChestStore.Chest>();
		final List<ChestStore.Chest> dirty = new ArrayList<ChestStore.Chest>();

		for (ChestStore.Chest chest : MapMcBotClient.chests().in(plan.area)) {
			if (plan.visited.contains(chest)) {
				continue;
			}

			if (plan.items == null) {
				if (chest.isDirty()) {
					dirty.add(chest);
				}

				continue;
			}

			// A deposit goes where there is room: a clean chest with an empty slot, or one that may have it.
			// Not into a furnace or a brewing stand: their slots take only what they work on.
			if (plan.deposit) {
				if (NO_DEPOSIT.contains(chest.getBlock())) {
					continue;
				}

				if (chest.isDirty()) {
					dirty.add(chest);
				} else if (chest.getContents().contains(null)) {
					withIt.add(chest);
				}

				continue;
			}

			final boolean has = has(chest, plan.items);

			if (!chest.isDirty()) {
				if (has) {
					withIt.add(chest);
				}
			} else if (has) {
				dirtyWithIt.add(chest);
			} else {
				dirty.add(chest);
			}
		}

		final List<ChestStore.Chest> order = new ArrayList<ChestStore.Chest>(withIt);
		order.addAll(dirtyWithIt);
		order.addAll(dirty);
		return order;
	}

	/** To the chest, standing where it can open it. */
	private void walk(String bot, Plan plan) {
		// Standing where a face of it is in reach and in sight, as the interact wants it.
		final int[] block = plan.chest.getBlocks().get(0);
		final JsonObject look = new JsonObject();
		look.addProperty("x", block[0]);
		look.addProperty("y", block[1]);
		look.addProperty("z", block[2]);
		final JsonObject a = new JsonObject();
		a.addProperty("action", "goto");
		a.add("look", look);
		send(bot, plan, a);
	}

	private void send(String bot, Plan plan, JsonObject a) {
		plan.starting = true;
		bots.actionInternal(bot, a.toString());
	}

	@Override
	public void onState(String bot, BotStatus status) {
		final Plan plan = plans.get(bot);

		if (plan == null) {
			return;
		}

		if (status.getKind() == BotStatus.Kind.DOING) {
			plan.starting = false;
			return;
		}

		if (plan.starting && status.getKind() == BotStatus.Kind.IDLE) {
			return;
		}

		if (status.getKind() == BotStatus.Kind.ERROR) {
			fail(bot, plan, plan.listener, name(plan) + status.getPrimitive() + ": " + status.getMessage());
			return;
		}

		switch (plan.phase) {
			case WALK: {
				plan.phase = Phase.OPEN;
				final int[] block = plan.chest.getBlocks().get(0);
				final JsonObject target = new JsonObject();
				target.addProperty("kind", "block");
				target.addProperty("x", block[0]);
				target.addProperty("y", block[1]);
				target.addProperty("z", block[2]);
				final JsonObject a = new JsonObject();
				a.addProperty("action", "interact");
				a.addProperty("button", "right");
				a.add("target", target);
				send(bot, plan, a);
				return;
			}

			case OPEN: {
				// Its window may come after the interact is over.
				plan.phase = Phase.WINDOW;
				final BotWindow window = bots.getWindow(bot);

				if (window != null) {
					fill(bot, plan, window);
				}

				return;
			}

			case FILL: {
				plan.phase = Phase.CLOSE;
				plan.closeIdle = false;
				final JsonObject a = new JsonObject();
				a.addProperty("action", "close_window");
				send(bot, plan, a);
				return;
			}

			case CLOSE:
				plan.closeIdle = true;

				if (bots.getWindow(bot) == null) {
					closed(bot, plan);
				}

				return;

			default:
				throw new IllegalStateException(bot + " grabbing went idle while " + plan.phase);
		}
	}

	@Override
	public void onWindow(String bot, BotWindow window) {
		final Plan plan = plans.get(bot);

		if (plan == null) {
			return;
		}

		// The close's frame may come after the close_window is over: the chest is left on both.
		if (window == null) {
			if (plan.phase == Phase.CLOSE && plan.closeIdle) {
				closed(bot, plan);
			} else if (plan.phase == Phase.WINDOW || plan.phase == Phase.FILL) {
				// Closed by the server before the grab closed it.
				fail(bot, plan, plan.listener, name(plan) + "the server closed " + plan.chest + " while " + plan.phase.name().toLowerCase());
			}

			return;
		}

		plan.window = window;

		if (plan.phase == Phase.WINDOW) {
			fill(bot, plan, window);
		}
	}

	/** The chest closed (the primitive idle and the window gone): done, or on to the next. */
	private void closed(String bot, Plan plan) {
		plan.closeIdle = false;

		if (plan.items != null && (plan.deposit ? held(plan) <= plan.target : plan.full ? !room(plan) : held(plan) >= plan.target)) {
			plans.remove(bot);
			plan.listener.done();
		} else {
			nextChest(bot, plan);
		}
	}

	@Override
	public void onAction(String bot, String json, boolean internal) {
		if (internal) {
			return;
		}

		errors.remove(bot);
		takeOver(bot);
	}

	/**
	 * The bot's grab going on, if any, over: an order from outside (a primitive, another grab started
	 * by hand or by a job) takes the bot over, its own primitives replacing the grab's on the fleet.
	 */
	private void takeOver(String bot) {
		final Plan plan = plans.remove(bot);

		if (plan != null) {
			plan.listener.failed();
		}
	}

	/** The open chest's items it has, into the inventory, no more than still wanted; with none, closed. */
	private void fill(String bot, Plan plan, BotWindow window) {
		// An index only looks: the tracker kept what is in it.
		if (plan.items == null) {
			plan.phase = Phase.FILL;
			onState(bot, BotStatus.IDLE);
			return;
		}

		final int own = window.getContainerSlots();
		final List<BotInventory.Item> slots = window.getSlots();

		if (plan.deposit) {
			deposit(bot, plan, window);
			return;
		}

		final JsonArray sources = new JsonArray();

		for (int i = 0; i < own; i++) {
			if (matches(plan.items, slots.get(i))) {
				sources.add(i);
			}
		}

		// The stacks of it it has first, then the empty slots.
		final JsonArray targets = new JsonArray();

		for (int i = own; i < slots.size(); i++) {
			if (matches(plan.items, slots.get(i))) {
				targets.add(i);
			}
		}

		for (int i = own; i < slots.size(); i++) {
			if (slots.get(i) == null) {
				targets.add(i);
			}
		}

		final int wanted = plan.target - held(plan);

		if (sources.size() == 0 || wanted <= 0) {
			plan.phase = Phase.FILL;
			onState(bot, BotStatus.IDLE);
			return;
		}

		// With the inventory full a full grab is done (on closing); short of count it fails.
		if (!room(plan)) {
			if (!plan.full) {
				fail(bot, plan, plan.listener, "grab: expected room for " + wanted + " more x " + describe(plan.items) + ", found the inventory full");
				return;
			}

			plan.phase = Phase.FILL;
			onState(bot, BotStatus.IDLE);
			return;
		}

		plan.phase = Phase.FILL;
		final JsonObject a = new JsonObject();
		a.addProperty("action", "item_fill");
		a.add("items", plan.items);
		a.add("sources", sources);
		a.add("targets", targets);
		a.addProperty("count", wanted);
		send(bot, plan, a);
	}

	/** The inventory's items the chest has room for, into it, no more than still to leave; with no room or none to leave, closed. */
	private void deposit(String bot, Plan plan, BotWindow window) {
		final int own = window.getContainerSlots();
		final List<BotInventory.Item> slots = window.getSlots();
		final JsonArray sources = new JsonArray();

		for (int i = own; i < slots.size(); i++) {
			if (matches(plan.items, slots.get(i))) {
				sources.add(i);
			}
		}

		// The stacks of it the chest has first, then its empty slots; a chest with no empty slot is passed by.
		final JsonArray targets = new JsonArray();
		boolean empty = false;

		for (int i = 0; i < own; i++) {
			if (matches(plan.items, slots.get(i))) {
				targets.add(i);
			}
		}

		for (int i = 0; i < own; i++) {
			if (slots.get(i) == null) {
				targets.add(i);
				empty = true;
			}
		}

		final int wanted = held(plan) - plan.target;

		if (!empty || sources.size() == 0 || wanted <= 0) {
			plan.phase = Phase.FILL;
			onState(bot, BotStatus.IDLE);
			return;
		}

		plan.phase = Phase.FILL;
		final JsonObject a = new JsonObject();
		a.addProperty("action", "item_fill");
		a.add("items", plan.items);
		a.add("sources", sources);
		a.add("targets", targets);
		a.addProperty("count", wanted);
		send(bot, plan, a);
	}

	/** The plan's name as its failures start: "index: ", "deposit: " or "grab: ". */
	private static String name(Plan plan) {
		return plan.items == null ? "index: " : plan.deposit ? "deposit: " : "grab: ";
	}

	/** Whether any of the matches takes the item. */
	private static boolean matches(JsonArray items, BotInventory.Item item) {
		for (int i = 0; i < items.size(); i++) {
			if (ItemSpec.matches(items.get(i).getAsJsonObject(), item)) {
				return true;
			}
		}

		return false;
	}

	/** The matches for messages: each one's name, joined by slashes. */
	private static String describe(JsonArray items) {
		final StringBuilder out = new StringBuilder();

		for (int i = 0; i < items.size(); i++) {
			out.append(i == 0 ? "" : "/").append(ItemSpec.describe(items.get(i).getAsJsonObject()));
		}

		return out.toString();
	}

	/** How many of the item the bot has, by the window last seen. */
	private static int held(Plan plan) {
		final List<BotInventory.Item> slots = plan.window.getSlots();
		int held = 0;

		for (int i = plan.window.getContainerSlots(); i < slots.size(); i++) {
			if (matches(plan.items, slots.get(i))) {
				held += slots.get(i).getCount();
			}
		}

		return held;
	}

	/** Whether the bot's inventory, by the window last seen, has a slot with room for the item: empty, or a stack of it short of full. */
	private static boolean room(Plan plan) {
		final List<BotInventory.Item> slots = plan.window.getSlots();
		return room(plan.items, slots.subList(plan.window.getContainerSlots(), slots.size()));
	}

	private static boolean room(JsonArray items, List<BotInventory.Item> slots) {
		for (BotInventory.Item item : slots) {
			if (item == null || (matches(items, item) && item.getCount() < maxCount(item))) {
				return true;
			}
		}

		return false;
	}

	/** How many of the item a stack holds. */
	private static int maxCount(BotInventory.Item item) {
		final Item kind = Item.REGISTRY.get(new Identifier(item.getName()));

		if (kind == null) {
			throw new IllegalStateException("no item " + item.getName() + " in the registry");
		}

		return kind.getMaxCount();
	}

	private static boolean has(ChestStore.Chest chest, JsonArray item) {
		if (chest.getContents() == null) {
			return false;
		}

		for (BotInventory.Item there : chest.getContents()) {
			if (matches(item, there)) {
				return true;
			}
		}

		return false;
	}

	private void fail(String bot, Plan plan, JobRunner.Listener listener, String message) {
		if (plan != null) {
			plans.remove(bot, plan);
		}

		errors.put(bot, message);
		listener.failed();
	}
}

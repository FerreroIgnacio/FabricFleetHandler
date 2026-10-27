package net.mapmcbot.bot;

import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import net.mapmcbot.chunk.ChunkKey;
import net.mapmcbot.fleet.FleetSocketHandler;

/**
 * The bots of the fleet and what each is doing, as the fleet reports it; the orders for them go
 * out through here too, so a bot is known from the moment it is asked for.
 *
 * Thread-safe: the channel thread feeds it, anyone may read it.
 *
 * SINGLETON: one per game, as the fleet; a second registry throws.
 */
public final class BotRegistry implements BotListener {
	private static final AtomicBoolean CREATED = new AtomicBoolean();

	private final FleetSocketHandler channel;

	/** The names for the bots: the mod picks them, the fleets are only told. */
	private final NameList names;

	/** The bots spawned and not yet gone. */
	private final Set<String> bots = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

	/** The server each bot spawned and not yet gone joins: as the fleet keys its columns. */
	private final Map<String, InetSocketAddress> serverOf = new ConcurrentHashMap<String, InetSocketAddress>();

	/** The bots in the world (BOT_SPAWNED) and not yet gone. */
	private final Set<String> spawned = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

	/** The path each walking bot is on, from its PATH frames. */
	private final Map<String, int[]> paths = new ConcurrentHashMap<String, int[]>();

	/** What each bot's wsr primitive shows, from its VIS frames. */
	private final Map<String, BotVis> visuals = new ConcurrentHashMap<String, BotVis>();

	/** The band primitives each bot runs and holds, the running one first, from its BANDS frames. */
	private final Map<String, int[][]> bands = new ConcurrentHashMap<String, int[][]>();

	/** What each bot is doing, from its STATE frames. */
	private final Map<String, BotStatus> statuses = new ConcurrentHashMap<String, BotStatus>();

	/** The window each bot has open, from its WINDOW frames. */
	private final Map<String, BotWindow> windows = new ConcurrentHashMap<String, BotWindow>();

	/** Each bot's inventory window, from its INVENTORY frames. */
	private final Map<String, BotInventory> inventories = new ConcurrentHashMap<String, BotInventory>();

	/** Hears each inventory as it comes (the bot window, while it is open); called on the channel's thread. */
	public interface InventoryListener {
		void onInventory(String bot, BotInventory inventory);
	}

	private final CopyOnWriteArrayList<InventoryListener> inventoryListeners = new CopyOnWriteArrayList<InventoryListener>();

	/** Hears each state as it comes; called on the channel's thread. */
	public interface StateListener {
		void onState(String bot, BotStatus status);
	}

	/**
	 * Hears each primitive sent to a bot, once it is on its way; internal when the mod sent it on its own
	 * (a step of the job, a part of a plan), not as an order from outside.
	 */
	public interface ActionListener {
		void onAction(String bot, String json, boolean internal);
	}

	/** Hears each window as it comes, null once closed; called on the channel's thread. */
	public interface WindowListener {
		void onWindow(String bot, BotWindow window);
	}

	/** Hears each chest lid as it moves; called on the channel's thread. */
	public interface LidListener {
		void onChestLid(String bot, int x, int y, int z, int viewers);
	}

	/** Hears the chests the fleets see as they show up or go, and the columns they let go; called on the channel's thread. */
	public interface ChestListener {
		void onChest(String bot, ChunkKey key, int x, int y, int z, String block);

		void onColumnGone(String bot, ChunkKey key);
	}

	private final List<ChestListener> chestListeners = new CopyOnWriteArrayList<ChestListener>();

	public void addChestListener(ChestListener listener) {
		chestListeners.add(listener);
	}

	/** Hears each column as a bot has it in its own world; called on the channel's thread. */
	public interface ColumnListener {
		void onColumnIn(String bot, ChunkKey key);
	}

	private final List<ColumnListener> columnListeners = new CopyOnWriteArrayList<ColumnListener>();

	public void addColumnListener(ColumnListener listener) {
		columnListeners.add(listener);
	}

	public void removeColumnListener(ColumnListener listener) {
		columnListeners.remove(listener);
	}

	private final List<StateListener> stateListeners = new CopyOnWriteArrayList<StateListener>();
	private final List<ActionListener> actionListeners = new CopyOnWriteArrayList<ActionListener>();
	private final List<WindowListener> windowListeners = new CopyOnWriteArrayList<WindowListener>();
	private final List<LidListener> lidListeners = new CopyOnWriteArrayList<LidListener>();

	public void addStateListener(StateListener listener) {
		stateListeners.add(listener);
	}

	public void addActionListener(ActionListener listener) {
		actionListeners.add(listener);
	}

	public void addWindowListener(WindowListener listener) {
		windowListeners.add(listener);
	}

	public void addLidListener(LidListener listener) {
		lidListeners.add(listener);
	}

	/** The id of the last click sent to each bot's inventory window, counting up from 1. */
	private final Map<String, Integer> clicksSent = new ConcurrentHashMap<String, Integer>();

	/** The profiles of the bots: each join is recorded on its bot's. */
	private final BotProfileStore profiles;

	/** The blocks each bot may place: sent to it as it spawns and again each change. */
	private final PlaceBlocks placeBlocks;

	public BotRegistry(FleetSocketHandler channel, NameList names, BotProfileStore profiles, PlaceBlocks placeBlocks) {
		if (!CREATED.compareAndSet(false, true)) {
			throw new IllegalStateException("BotRegistry is a singleton: one was already created");
		}

		this.channel = channel;
		this.names = names;
		this.profiles = profiles;
		this.placeBlocks = placeBlocks;
		channel.setBotListener(this);
		placeBlocks.setListener(() -> {
			for (String bot : spawned) {
				sendAllowedPlace(bot);
			}
		});
	}

	public PlaceBlocks getPlaceBlocks() {
		return placeBlocks;
	}

	/** The blocks the bot may place, on the fleet (see FleetSocketHandler). */
	private void sendAllowedPlace(String bot) {
		if (!bots.contains(bot)) {
			throw new IllegalStateException("no bot named " + bot + " to send the blocks it may place to");
		}

		channel.allowedPlace(bot, placeBlocks.allowed(bot));
	}

	public Set<String> getBots() {
		return Collections.unmodifiableSet(bots);
	}

	/** The bots in the world, ready for orders. */
	public Set<String> getSpawned() {
		return Collections.unmodifiableSet(spawned);
	}

	/** connecting, or what the bot is doing (see BotStatus). */
	public String getStatus(String bot) {
		if (!spawned.contains(bot)) {
			return "connecting";
		}

		return statuses.getOrDefault(bot, BotStatus.IDLE).toString();
	}

	/** What the bot is doing; idle before its first report. */
	public BotStatus getState(String bot) {
		return statuses.getOrDefault(bot, BotStatus.IDLE);
	}

	/** The window the bot has open, null for none. */
	public BotWindow getWindow(String bot) {
		return windows.get(bot);
	}

	/** A primitive for the bot as JSON, on the fleet (see FleetSocketHandler). */
	public void action(String bot, String json) {
		send(bot, json, false);
	}

	/** A primitive the mod sends on its own (see ActionListener). */
	public void actionInternal(String bot, String json) {
		send(bot, json, true);
	}

	private void send(String bot, String json, boolean internal) {
		if (!bots.contains(bot)) {
			throw new IllegalStateException("no bot named " + bot + " to order " + json);
		}

		if (!spawned.contains(bot)) {
			throw new IllegalStateException(bot + " is not in the world yet to order " + json);
		}

		// One from the UI does not chain: what the bot runs or holds goes first.
		if (!internal) {
			channel.clearQ(bot);
		}

		channel.action(bot, json);

		for (ActionListener listener : actionListeners) {
			listener.onAction(bot, json, internal);
		}
	}

	/** The server the bot joins, as the fleet keys its columns: host:port. */
	public String getServer(String bot) {
		final InetSocketAddress server = serverOf.get(bot);

		if (server == null) {
			throw new IllegalStateException("no bot named " + bot + " to tell the server of");
		}

		return server.getHostString() + ":" + server.getPort();
	}

	/** The bot's inventory window as last reported, null before its first. */
	public BotInventory getInventory(String bot) {
		return inventories.get(bot);
	}

	/** A listener of the inventories as they come; added again, it is still heard once. */
	public void addInventoryListener(InventoryListener listener) {
		inventoryListeners.addIfAbsent(listener);
	}

	public void removeInventoryListener(InventoryListener listener) {
		inventoryListeners.remove(listener);
	}

	/** A click on the bot's inventory window, on the fleet (see FleetSocketHandler). */
	public void windowClick(String bot, int slot, int button, int mode) {
		if (!bots.contains(bot)) {
			throw new IllegalStateException("no bot named " + bot + " to click slot " + slot + " of");
		}

		channel.windowClick(bot, slot, button, mode, clicksSent.merge(bot, 1, Integer::sum));
	}

	/** The villager trade the bot's open window shows, on the fleet (see FleetSocketHandler). */
	public void tradeSelect(String bot, int trade) {
		if (!bots.contains(bot)) {
			throw new IllegalStateException("no bot named " + bot + " to pick trade " + trade + " for");
		}

		channel.tradeSelect(bot, trade);
	}

	/**
	 * Whether the inventory has every click sent to the bot done: one older than the clicks after it
	 * would undo what the window already shows of them, until the next one comes.
	 */
	public boolean coversClicks(String bot, BotInventory inventory) {
		return inventory.getLastClick() >= clicksSent.getOrDefault(bot, 0);
	}

	/** The paths being walked, by bot: {target x, y, z, node x, y, z, ...}. */
	public Map<String, int[]> getPaths() {
		return Collections.unmodifiableMap(paths);
	}

	/** What each bot's wsr primitive shows, by bot. */
	public Map<String, BotVis> getVisuals() {
		return Collections.unmodifiableMap(visuals);
	}

	/** The band primitives each bot runs and holds, by bot: {min x, y, z, max x, y, z} each, the running one first. */
	public Map<String, int[][]> getBands() {
		return Collections.unmodifiableMap(bands);
	}

	/**
	 * Starts a bot that joins host:port in the background, named by the name list; returns the name.
	 */
	public synchronized String create(String host, int port) {
		final String name = names.take();

		try {
			if (!bots.add(name)) {
				throw new IllegalStateException("a bot named " + name + " is already running");
			}

			serverOf.put(name, InetSocketAddress.createUnresolved(host, port));
			channel.spawn(name, host, port);
			final BotProfile profile = profiles.get(name);
			profiles.recordJoin(name, host + ":" + port, profile.getDefaultCrackedPassword());
		} catch (RuntimeException e) {
			if (bots.remove(name)) {
				serverOf.remove(name);
				names.give(name);
			}

			throw e;
		}

		return name;
	}

	/** The bot walks to stand at x, y, z, on the fleet. */
	public void gotoSpot(String bot, int x, int y, int z) {
		if (!bots.contains(bot)) {
			throw new IllegalStateException("no bot named " + bot + " to send to " + x + "," + y + "," + z);
		}

		channel.gotoSpot(bot, x, y, z);
	}

	/** Each bot leaves; the fleet unloads each chunk once no bot holds it. The fleets stay up. */
	public synchronized void stopAll() {
		for (String bot : bots) {
			channel.quit(bot);
		}

		clear();
	}

	@Override
	public void onSpawned(String bot) {
		// Before it is ready for orders: a place never finds it without its list.
		sendAllowedPlace(bot);
		spawned.add(bot);
	}

	@Override
	public void onPath(String bot, int[] path) {
		paths.put(bot, path);
	}

	@Override
	public void onInventory(String bot, BotInventory inventory) {
		inventories.put(bot, inventory);

		for (InventoryListener listener : inventoryListeners) {
			listener.onInventory(bot, inventory);
		}
	}

	@Override
	public void onState(String bot, BotStatus status) {
		statuses.put(bot, status);
		for (StateListener listener : stateListeners) {
			listener.onState(bot, status);
		}
	}

	@Override
	public void onWindow(String bot, BotWindow window) {
		if (window == null) {
			windows.remove(bot);
		} else {
			windows.put(bot, window);
		}

		for (WindowListener listener : windowListeners) {
			listener.onWindow(bot, window);
		}
	}

	@Override
	public void onChest(String bot, ChunkKey key, int x, int y, int z, String block) {
		for (ChestListener listener : chestListeners) {
			listener.onChest(bot, key, x, y, z, block);
		}
	}

	@Override
	public void onColumnGone(String bot, ChunkKey key) {
		for (ChestListener listener : chestListeners) {
			listener.onColumnGone(bot, key);
		}
	}

	@Override
	public void onColumnIn(String bot, ChunkKey key) {
		for (ColumnListener listener : columnListeners) {
			listener.onColumnIn(bot, key);
		}
	}

	@Override
	public void onChestLid(String bot, int x, int y, int z, int viewers) {
		for (LidListener listener : lidListeners) {
			listener.onChestLid(bot, x, y, z, viewers);
		}
	}

	@Override
	public void onPathCleared(String bot) {
		paths.remove(bot);
	}

	@Override
	public void onVis(String bot, BotVis vis) {
		if (vis == null) {
			visuals.remove(bot);
		} else {
			visuals.put(bot, vis);
		}
	}

	@Override
	public void onBands(String bot, int[][] boxes) {
		if (boxes.length == 0) {
			bands.remove(bot);
		} else {
			bands.put(bot, boxes);
		}
	}

	@Override
	public void onGone(String bot) {
		if (bots.remove(bot)) {
			names.give(bot);
		}

		serverOf.remove(bot);

		spawned.remove(bot);
		paths.remove(bot);
		visuals.remove(bot);
		bands.remove(bot);
		inventories.remove(bot);
		clicksSent.remove(bot);
		statuses.remove(bot);
		windows.remove(bot);
	}

	/** The fleet is gone: its bots with it. */
	@Override
	public void onClosed() {
		clear();
	}

	private void clear() {
		names.giveAll();
		bots.clear();
		serverOf.clear();
		spawned.clear();
		paths.clear();
		visuals.clear();
		bands.clear();
		inventories.clear();
		clicksSent.clear();
		statuses.clear();
		windows.clear();
	}
}

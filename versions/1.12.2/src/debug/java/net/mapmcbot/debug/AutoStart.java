package net.mapmcbot.debug;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Random;

import net.fabricmc.api.ClientModInitializer;
import net.legacyfabric.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.mapmcbot.area.Area;
import net.mapmcbot.bot.BotInventory;
import net.mapmcbot.bot.BotRegistry;
import net.mapmcbot.client.MapMcBotClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.world.SelectWorldScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.PlayerManager;
import net.minecraft.world.GameMode;

/**
 * Debug only, loaded by runClient alone (its own source set, never in the mod's jar): clicks through
 * Singleplayer and the first world as a player would, opens it to LAN in survival as soon as it is
 * up, then adds the bot profiles' bots on the LAN port. Once every bot has sent its first inventory, it
 * builds the test area (see setUp), puts the bots standing on it (not in it, suffocating), clears their
 * inventories and gives each the PICK and the BLOCKS; once each has those alone, #quarrify of it. The
 * test is the area named in run/mapmcbot/debug/qtest, qtest without it. Removing it is deleting
 * src/debug and its block in build.gradle.
 */
public class AutoStart implements ClientModInitializer {
	/** The LAN's player limit, vanilla's 8 being too few for the player and every bot. */
	private static final int MAX_PLAYERS = 128;

	/** Vanilla button ids: Singleplayer, Play Selected World. */
	private static final int SINGLEPLAYER = 1;
	private static final int PLAY_SELECTED = 1;

	/** The test run without run/mapmcbot/debug/qtest: the area of that name. */
	private static final String DEFAULT_TEST = "qtest";

	/** What each bot digs it with: unbreaking (id 34) 200. */
	private static final String PICK = "minecraft:diamond_pickaxe 1 0 {ench:[{id:34s,lvl:200s}]}";
	/** What each bot places its first steps, supports, fills and bridges with, before it digs any. */
	private static final String BLOCKS = "minecraft:cobblestone 64";
	private static final String[] KIT = {"minecraft:diamond_pickaxe", "minecraft:cobblestone"};

	/** qtest's mix (the rest stone), as {block, data}: boxes of them at random, the same each run (SEED). */
	private static final String[][] MIX = {{"dirt", "0"}, {"gravel", "0"}, {"sand", "0"}, {"cobblestone", "0"}, {"stone", "1"}, {"stone", "3"}, {"stone", "5"}, {"coal_ore", "0"}, {"iron_ore", "0"}, {"gold_ore", "0"}, {"sandstone", "0"}, {"obsidian", "0"}, {"clay", "0"}, {"mossy_cobblestone", "0"}};
	private static final long SEED = 1;
	/** One box of the mix to this many blocks of qtest. */
	private static final int MIX_EVERY = 150;
	/** The widest a box of the mix is along each axis. */
	private static final int MIX_SIZE = 4;

	/** qtest1's liquids, from its lower corner: {block, dx1, dy1, dz1, dx2, dy2, dz2}, the rest stone. */
	private static final Object[][] LIQUIDS = {
		// Across a layer's bottom (layers of 4 from the top, 68).
		{"water", 6, 9, 4, 8, 11, 6},
		{"lava", 13, 4, 9, 14, 5, 10},
		// At the top, open to the air over it.
		{"water", 11, 17, 3, 11, 17, 3},
		{"lava", 3, 15, 12, 3, 15, 12},
		// A column of 6.
		{"water", 16, 1, 3, 16, 6, 3},
		// At the bottom.
		{"lava", 8, 0, 10, 8, 0, 10}};

	/** qtest2's islands, from its lower corner: {block, dx1, dy1, dz1, dx2, dy2, dz2}, the rest air. */
	private static final Object[][] ISLANDS = {
		// Towers of three heights, one the area's.
		{"stone", 4, 0, 4, 9, 11, 9},
		{"dirt", 14, 0, 4, 19, 6, 10},
		{"stone", 5, 0, 15, 11, 9, 20},
		// Up in the air: a slab, a block.
		{"cobblestone", 15, 7, 15, 20, 8, 20},
		{"stone", 12, 10, 12, 12, 10, 12},
		// A pillar of one.
		{"stone", 2, 0, 20, 2, 3, 20}};

	/** How far round a built test (qtest1, qtest2) its stone plate goes, and how high over it the air. */
	private static final int PLATE = 3;
	private static final int CLEAR = 8;

	/** Ticks between two bots joining, and before each click (a screen builds its buttons first). */
	private static final int BOT_GAP_TICKS = 10;
	private static final int SETTLE_TICKS = 10;

	/** JOINING and ARMING go on by the bots' inventories (see onInventory), SETTING by the set up's end, not by ticks. */
	private enum Step { TITLE, PICK_WORLD, PLAY, LOADING, BOTS, JOINING, SETTING, ARMING, DONE }

	private Step step = Step.TITLE;
	/** The area being tested, as named (see DEFAULT_TEST). */
	private String test;
	private int wait = SETTLE_TICKS;
	private int nextBot;
	private int port;
	private final List<String> fleet = new ArrayList<String>();
	/** Each bot's inventory as it comes, onto the client thread (see onInventory). */
	private final BotRegistry.InventoryListener inventoryListener = (bot, inventory) -> MinecraftClient.getInstance().submit(() -> onInventory(MinecraftClient.getInstance(), bot));
	/** The bots whose inventory came since the set up ended: one from before is its last run's, the kit maybe among it. */
	private final Set<String> armed = new HashSet<String>();

	@Override
	public void onInitializeClient() {
		final File named = new File(MinecraftClient.getInstance().runDirectory, "mapmcbot/debug/qtest");

		try {
			test = named.isFile() ? new String(Files.readAllBytes(named.toPath()), StandardCharsets.UTF_8).trim() : DEFAULT_TEST;
		} catch (IOException e) {
			throw new UncheckedIOException("debug autostart could not read " + named, e);
		}

		ClientTickEvents.END_CLIENT_TICK.register(this::tick);
		DebugBridge.start(MinecraftClient.getInstance());
	}

	private void tick(MinecraftClient client) {
		if (step.compareTo(Step.JOINING) >= 0 || --wait > 0) {
			return;
		}

		wait = SETTLE_TICKS;
		final Screen screen = client.currentScreen;

		switch (step) {
			case TITLE:
				if (screen instanceof TitleScreen && click(screen, SINGLEPLAYER)) {
					step = Step.PICK_WORLD;
				}
				break;

			case PICK_WORLD:
				if (screen instanceof SelectWorldScreen) {
					// The first row of the world list, as a click on it: the list starts at y 32.
					clickAt(screen, screen.width / 2, 32 + 4 + 10);
					step = Step.PLAY;
				}
				break;

			case PLAY:
				if (screen instanceof SelectWorldScreen && click(screen, PLAY_SELECTED)) {
					step = Step.LOADING;
				}
				break;

			case LOADING:
				// openToLAN is what Start LAN World calls, and the only thing that hands back the port.
				if (client.world != null && client.player != null && client.getServer() != null && screen == null) {
					final String opened = client.getServer().openToLAN(GameMode.SURVIVAL, true);

					if (opened != null) {
						raisePlayerLimit(client.getServer().getPlayerManager());
						port = Integer.parseInt(opened);
						MapMcBotClient.bots().addInventoryListener(inventoryListener);

						step = Step.BOTS;
					}
				}
				break;

			case BOTS:
				fleet.add(MapMcBotClient.bots().create("localhost", port));
				nextBot++;
				wait = BOT_GAP_TICKS;
				step = nextBot < MapMcBotClient.botProfiles().getProfiles().size() ? Step.BOTS : Step.JOINING;
				break;

			default:
				break;
		}
	}

	/**
	 * A bot's inventory came: once every bot has its first (it comes after its spawn), the set up; once
	 * each has the kit alone (the inventories after the set up, the bots idle), the quarrify.
	 */
	private void onInventory(MinecraftClient client, String from) {
		if (step == Step.JOINING) {
			for (String bot : fleet) {
				if (MapMcBotClient.bots().getInventory(bot) == null) {
					return;
				}
			}

			step = Step.SETTING;
			setUp(client);
		} else if (step == Step.ARMING) {
			armed.add(from);

			for (String bot : fleet) {
				if (!armed.contains(bot) || !kitAlone(MapMcBotClient.bots().getInventory(bot))) {
					return;
				}
			}

			step = Step.DONE;
			MapMcBotClient.bots().removeInventoryListener(inventoryListener);

			if (!MapMcBotClient.command("#quarrify " + test)) {
				throw new IllegalStateException("debug autostart: #quarrify is not a comando");
			}
		}
	}

	/**
	 * On the server thread: the test area built, the bots standing on it with their inventories cleared,
	 * the items lying round killed, the kit given. qtest is filled with stone and MIX; qtest1 and qtest2
	 * get a stone plate PLATE round and under them, air CLEAR over it, and in them stone and LIQUIDS, or
	 * air and ISLANDS, the bots on the plate's corner (qtest2's top is in the air).
	 */
	private void setUp(MinecraftClient client) {
		Area area = null;

		for (Area one : MapMcBotClient.areas().getAreas()) {
			if (one.getName().equals(test)) {
				area = one;
			}
		}

		if (area == null) {
			throw new IllegalStateException("debug autostart: no area named " + test);
		}

		final List<String> fills = new ArrayList<String>();
		final String spawn;

		if (test.equals("qtest")) {
			fills.add(fill(area.getMinX(), area.getMinY(), area.getMinZ(), area.getMaxX(), area.getMaxY(), area.getMaxZ(), "stone", "0"));
			final Random random = new Random(SEED);

			for (long i = area.getVolume() / MIX_EVERY; i > 0; i--) {
				final String[] block = MIX[random.nextInt(MIX.length)];
				final int x = area.getMinX() + random.nextInt(area.getWidth());
				final int y = area.getMinY() + random.nextInt(area.getHeight());
				final int z = area.getMinZ() + random.nextInt(area.getDepth());
				fills.add(fill(x, y, z, Math.min(x + random.nextInt(MIX_SIZE), area.getMaxX()), Math.min(y + random.nextInt(MIX_SIZE), area.getMaxY()), Math.min(z + random.nextInt(MIX_SIZE), area.getMaxZ()), block[0], block[1]));
			}

			spawn = top(area);
		} else if (test.equals("qtest1") || test.equals("qtest2")) {
			final boolean liquids = test.equals("qtest1");
			fills.add(fill(area.getMinX() - PLATE, area.getMinY() - 1, area.getMinZ() - PLATE, area.getMaxX() + PLATE, area.getMaxY(), area.getMaxZ() + PLATE, "stone", "0"));
			fills.add(fill(area.getMinX() - PLATE, area.getMaxY() + 1, area.getMinZ() - PLATE, area.getMaxX() + PLATE, area.getMaxY() + CLEAR, area.getMaxZ() + PLATE, "air", "0"));
			fills.add(fill(area.getMinX(), area.getMinY(), area.getMinZ(), area.getMaxX(), area.getMaxY(), area.getMaxZ(), liquids ? "stone" : "air", "0"));

			for (Object[] box : liquids ? LIQUIDS : ISLANDS) {
				fills.add(fill(area.getMinX() + (Integer) box[1], area.getMinY() + (Integer) box[2], area.getMinZ() + (Integer) box[3], area.getMinX() + (Integer) box[4], area.getMinY() + (Integer) box[5], area.getMinZ() + (Integer) box[6], (String) box[0], "0"));
			}

			spawn = liquids ? top(area) : (area.getMinX() - 1.5) + " " + (area.getMaxY() + 1) + " " + (area.getMinZ() - 1.5);
		} else {
			throw new IllegalStateException("debug autostart: no test " + test + " (qtest, qtest1, qtest2)");
		}

		final List<String> bots = new ArrayList<String>(fleet);
		final MinecraftServer server = client.getServer();
		final int minCx = area.getMinX() - PLATE >> 4;
		final int maxCx = area.getMaxX() + PLATE >> 4;
		final int minCz = area.getMinZ() - PLATE >> 4;
		final int maxCz = area.getMaxZ() + PLATE >> 4;

		server.submit(() -> {
			// Its chunks loaded, a fill there not failing for them: loaded on the spot (generated if need be).
			for (int cx = minCx; cx <= maxCx; cx++) {
				for (int cz = minCz; cz <= maxCz; cz++) {
					server.getWorld(0).getChunk(cx, cz);
				}
			}

			// clear and fill do nothing (0) on an empty inventory or blocks that are so already: not checked.
			for (String bot : bots) {
				run(server, "tp " + bot + " " + spawn, true);
				run(server, "clear " + bot, false);
				// Healed and fed: the server keeps a bot's health from its last run, a fall that hurt it then kills it now.
				run(server, "effect " + bot + " minecraft:instant_health 1 10", true);
				run(server, "effect " + bot + " minecraft:saturation 1 10", true);
				// Mining fatigue I for the whole run: slower breaks, the aim held on the block longer.
				run(server, "effect " + bot + " minecraft:mining_fatigue 1000000 0 true", true);
			}

			for (String fill : fills) {
				run(server, fill, false);
			}

			// The drops of the quarrify before, still lying round it: a bot picking one up never has the kit alone.
			run(server, "kill @e[type=item]", false);

			for (String bot : bots) {
				run(server, "give " + bot + " " + PICK, true);
				run(server, "give " + bot + " " + BLOCKS, true);
			}

			// The inventories from here on are the set up's.
			client.submit(() -> step = Step.ARMING);
		});
	}

	/** The middle of the area's top, standing on it. */
	private static String top(Area area) {
		return (area.getMinX() + area.getMaxX() + 1) / 2.0 + " " + (area.getMaxY() + 1) + " " + (area.getMinZ() + area.getMaxZ() + 1) / 2.0;
	}

	private static String fill(int x1, int y1, int z1, int x2, int y2, int z2, String block, String data) {
		return "fill " + x1 + " " + y1 + " " + z1 + " " + x2 + " " + y2 + " " + z2 + " minecraft:" + block + " " + data;
	}

	private static void run(MinecraftServer server, String command, boolean checked) {
		if (server.getCommandManager().execute(server, command) == 0 && checked) {
			throw new IllegalStateException("debug autostart: /" + command + " failed");
		}
	}

	/** Whether the inventory holds every item of the KIT and nothing else. */
	private static boolean kitAlone(BotInventory inventory) {
		final List<String> kit = new ArrayList<String>();

		for (String name : KIT) {
			kit.add(name);
		}

		final List<String> missing = new ArrayList<String>(kit);

		for (BotInventory.Item item : inventory.getSlots()) {
			if (item != null) {
				if (!kit.contains(item.getName())) {
					return false;
				}

				missing.remove(item.getName());
			}
		}

		return missing.isEmpty();
	}

	/** Vanilla keeps the integrated server at 8 players; its field is protected and set once. */
	private static void raisePlayerLimit(PlayerManager players) {
		try {
			final Field field = PlayerManager.class.getDeclaredField("maxPlayers");
			field.setAccessible(true);
			field.setInt(players, MAX_PLAYERS);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("debug autostart could not raise the player limit", e);
		}
	}

	/** Clicks the screen's button `id` at its middle, if it is there and enabled. */
	private static boolean click(Screen screen, int id) {
		for (ButtonWidget button : buttons(screen)) {
			if (button.id == id && button.visible && button.active) {
				clickAt(screen, button.x + button.getWidth() / 2, button.y + 10);
				return true;
			}
		}

		return false;
	}

	/** A left click at (x, y) on the screen, through its own mouse handling. */
	private static void clickAt(Screen screen, int x, int y) {
		try {
			final Method mouseClicked = Screen.class.getDeclaredMethod("mouseClicked", int.class, int.class, int.class);
			mouseClicked.setAccessible(true);
			mouseClicked.invoke(screen, x, y, 0);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("debug autostart could not click", e);
		}
	}

	@SuppressWarnings("unchecked")
	private static List<ButtonWidget> buttons(Screen screen) {
		try {
			final Field field = Screen.class.getDeclaredField("buttons");
			field.setAccessible(true);
			return (List<ButtonWidget>) field.get(screen);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("debug autostart could not read the buttons", e);
		}
	}
}

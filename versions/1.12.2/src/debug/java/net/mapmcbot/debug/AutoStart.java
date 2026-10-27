package net.mapmcbot.debug;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
 * inventories and gives each the PICK, the SHOVEL and the BLOCKS; once each has those alone, BandTest
 * on it. The test runs when run/mapmcbot/debug/qtest names BAND_TEST; PRIM_TEST instead, PrimTest's
 * own set up and kit, then PrimTest; with any other, or without it, nothing past the bots. Removing it is deleting
 * src/debug and its block in build.gradle.
 */
public class AutoStart implements ClientModInitializer {
	/** The LAN's player limit, vanilla's 8 being too few for the player and every bot. */
	private static final int MAX_PLAYERS = 128;

	/** Vanilla button ids: Singleplayer, Play Selected World. */
	private static final int SINGLEPLAYER = 1;
	private static final int PLAY_SELECTED = 1;

	/** The test of the band primitive (see BandTest): each bot digs a strip of its own, in its own style. */
	private static final String BAND_TEST = "qtest3";
	/** The test of every primitive at once, one a bot (see PrimTest), in the area of that name. */
	private static final String PRIM_TEST = "primtest";
	/** How far apart along z qtest3's strips are: 2 wide, 2 of air between them. */
	private static final int STRIP_STEP = 4;

	/** What each bot digs it with: unbreaking (id 34) 200, efficiency (id 32) 10. */
	static final String PICK = "minecraft:diamond_pickaxe 1 0 {ench:[{id:34s,lvl:200s},{id:32s,lvl:10s}]}";
	/** What each bot digs dirt, gravel, sand and clay with: the PICK's enchantments. */
	static final String SHOVEL = "minecraft:diamond_shovel 1 0 {ench:[{id:34s,lvl:200s},{id:32s,lvl:10s}]}";
	/** What each bot places its first steps, supports, fills and bridges with, before it digs any. */
	static final String BLOCKS = "minecraft:cobblestone 64";
	private static final String[] KIT = {"minecraft:diamond_pickaxe", "minecraft:diamond_shovel", "minecraft:cobblestone"};

	/** How far round the test its stone plate goes, and how high over it the air. */
	private static final int PLATE = 3;
	private static final int CLEAR = 8;

	/** Ticks between two bots joining, and before each click (a screen builds its buttons first). */
	private static final int BOT_GAP_TICKS = 10;
	private static final int SETTLE_TICKS = 10;

	/** JOINING and ARMING go on by the bots' inventories (see onInventory), SETTING by the set up's end, not by ticks. */
	private enum Step { TITLE, PICK_WORLD, PLAY, LOADING, BOTS, JOINING, SETTING, ARMING, DONE }

	private Step step = Step.TITLE;
	/** The area being tested, as named in run/mapmcbot/debug/qtest; null without it. */
	private String test;
	private int wait = SETTLE_TICKS;
	private int nextBot;
	private int port;
	private final List<String> fleet = new ArrayList<String>();
	/** Each bot's inventory as it comes, onto the client thread (see onInventory). */
	private final BotRegistry.InventoryListener inventoryListener = (bot, inventory) -> MinecraftClient.getInstance().submit(() -> onInventory(MinecraftClient.getInstance(), bot));
	/** The bots whose inventory came since the set up ended: one from before is its last run's, the kit maybe among it. */
	private final Set<String> armed = new HashSet<String>();
	/** PRIM_TEST's, null on any other; its columns heard from before the bots join. */
	private PrimTest primTest;
	private PrimTest.Columns primColumns;

	@Override
	public void onInitializeClient() {
		final File named = new File(MinecraftClient.getInstance().runDirectory, "mapmcbot/debug/qtest");

		try {
			test = named.isFile() ? new String(Files.readAllBytes(named.toPath()), StandardCharsets.UTF_8).trim() : null;
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

						if (PRIM_TEST.equals(test)) {
							primColumns = new PrimTest.Columns();
							MapMcBotClient.bots().addColumnListener(primColumns);
						}

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
	 * each has the kit alone (the inventories after the set up, the bots idle), the band test.
	 */
	private void onInventory(MinecraftClient client, String from) {
		if (step == Step.JOINING) {
			for (String bot : fleet) {
				if (MapMcBotClient.bots().getInventory(bot) == null) {
					return;
				}
			}

			if (PRIM_TEST.equals(test)) {
				step = Step.SETTING;
				primTest = new PrimTest(client.getServer(), fleet, area(), primColumns);
				primTest.setUp(() -> client.submit(() -> step = Step.ARMING));
				return;
			}

			if (!BAND_TEST.equals(test)) {
				step = Step.DONE;
				MapMcBotClient.bots().removeInventoryListener(inventoryListener);
				return;
			}

			step = Step.SETTING;
			setUp(client);
		} else if (step == Step.ARMING) {
			armed.add(from);

			for (String bot : fleet) {
				if (!armed.contains(bot) || !kitAlone(MapMcBotClient.bots().getInventory(bot), primTest != null ? primTest.kit(bot) : KIT)) {
					return;
				}
			}

			step = Step.DONE;
			MapMcBotClient.bots().removeInventoryListener(inventoryListener);

			if (primTest != null) {
				primTest.start();
				return;
			}

			final Area area = area();

			for (int i = 0; i < fleet.size(); i++) {
				final int z = area.getMinZ() + STRIP_STEP * i;
				new BandTest(fleet.get(i), area.getMinX(), area.getMaxX(), area.getMinY(), area.getMaxY(), z, z + area.getMaxZ() - area.getMinZ()).start();
			}
		}
	}

	/**
	 * On the server thread: the test area built, the bots standing on it with their inventories cleared,
	 * the items lying round killed, the kit given: a stone plate PLATE round and under it, air CLEAR
	 * over it, a stone strip a bot, each bot in front of its own.
	 */
	private void setUp(MinecraftClient client) {
		final Area area = area();
		final List<String> fills = new ArrayList<String>();
		// Each bot's own spot, in front of its strip.
		final Map<String, String> spawns = new HashMap<String, String>();
		// A strip a bot, the area's copied STRIP_STEP apart along z, each bot in front of its own on its middle line across.
		final int beyondZ = STRIP_STEP * (fleet.size() - 1);
		fills.addAll(fillsAlongZ(area.getMinX() - PLATE, area.getMinY() - 1, area.getMinZ() - PLATE, area.getMaxX() + PLATE, area.getMinY() - 1, area.getMaxZ() + beyondZ + PLATE, "stone"));
		fills.addAll(fillsAlongZ(area.getMinX() - PLATE, area.getMinY(), area.getMinZ() - PLATE, area.getMaxX() + PLATE, area.getMaxY() + CLEAR, area.getMaxZ() + beyondZ + PLATE, "air"));

		for (int i = 0; i < fleet.size(); i++) {
			final int z = area.getMinZ() + STRIP_STEP * i;
			final int maxZ = z + area.getMaxZ() - area.getMinZ();
			fills.add(fill(area.getMinX(), area.getMinY(), z, area.getMaxX(), area.getMaxY(), maxZ, "stone", "0"));
			spawns.put(fleet.get(i), (area.getMinX() - 0.5) + " " + area.getMinY() + " " + (z + maxZ + 1) / 2.0);
		}

		final List<String> bots = new ArrayList<String>(fleet);
		final MinecraftServer server = client.getServer();
		final int minCx = area.getMinX() - PLATE >> 4;
		final int maxCx = area.getMaxX() + PLATE >> 4;
		final int minCz = area.getMinZ() - PLATE >> 4;
		final int maxCz = area.getMaxZ() + beyondZ + PLATE >> 4;

		server.submit(() -> {
			// Its chunks loaded, a fill there not failing for them: loaded on the spot (generated if need be).
			for (int cx = minCx; cx <= maxCx; cx++) {
				for (int cz = minCz; cz <= maxCz; cz++) {
					server.getWorld(0).getChunk(cx, cz);
				}
			}

			// clear and fill do nothing (0) on an empty inventory or blocks that are so already: not checked.
			for (String bot : bots) {
				run(server, "tp " + bot + " " + spawns.get(bot), true);
				run(server, "clear " + bot, false);
				// The effects the server kept from its last run (a mining fatigue among them) gone: 0 with none.
				run(server, "effect " + bot + " clear", false);
				// Healed and fed: the server keeps a bot's health from its last run, a fall that hurt it then kills it now.
				run(server, "effect " + bot + " minecraft:instant_health 1 10", true);
				run(server, "effect " + bot + " minecraft:saturation 1 10", true);
			}

			for (String fill : fills) {
				run(server, fill, false);
			}

			// The drops of the test before, still lying round it: a bot picking one up never has the kit alone.
			run(server, "kill @e[type=item]", false);

			for (String bot : bots) {
				run(server, "give " + bot + " " + PICK, true);
				run(server, "give " + bot + " " + SHOVEL, true);
				run(server, "give " + bot + " " + BLOCKS, true);
			}

			// The inventories from here on are the set up's.
			client.submit(() -> step = Step.ARMING);
		});
	}

	/** The test's area (see test). */
	private Area area() {
		for (Area one : MapMcBotClient.areas().getAreas()) {
			if (one.getName().equals(test)) {
				return one;
			}
		}

		throw new IllegalStateException("debug autostart: no area named " + test);
	}

	/** The most blocks a fill command takes (vanilla's limit). */
	private static final int FILL_LIMIT = 32768;

	/** A box too big for one fill, as fills of as many whole z rows as FILL_LIMIT lets each take. */
	static List<String> fillsAlongZ(int x1, int y1, int z1, int x2, int y2, int z2, String block) {
		final int rows = FILL_LIMIT / ((x2 - x1 + 1) * (y2 - y1 + 1));

		if (rows == 0) {
			throw new IllegalStateException("debug autostart: a z row of " + x1 + " " + y1 + " " + z1 + ".." + x2 + " " + y2 + " " + z2 + " is past the fill limit");
		}

		final List<String> out = new ArrayList<String>();

		for (int z = z1; z <= z2; z += rows) {
			out.add(fill(x1, y1, z, x2, y2, Math.min(z + rows - 1, z2), block, "0"));
		}

		return out;
	}

	static String fill(int x1, int y1, int z1, int x2, int y2, int z2, String block, String data) {
		return "fill " + x1 + " " + y1 + " " + z1 + " " + x2 + " " + y2 + " " + z2 + " minecraft:" + block + " " + data;
	}

	static void run(MinecraftServer server, String command, boolean checked) {
		if (server.getCommandManager().execute(server, command) == 0 && checked) {
			throw new IllegalStateException("debug autostart: /" + command + " failed");
		}
	}

	/** Whether the inventory holds every item of the kit and nothing else. */
	private static boolean kitAlone(BotInventory inventory, String[] names) {
		final List<String> kit = new ArrayList<String>();

		for (String name : names) {
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

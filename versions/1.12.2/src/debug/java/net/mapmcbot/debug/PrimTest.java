package net.mapmcbot.debug;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.IntSupplier;

import net.mapmcbot.area.Area;
import net.mapmcbot.bot.BotRegistry;
import net.mapmcbot.bot.BotStatus;
import net.mapmcbot.bot.BotWindow;
import net.mapmcbot.chunk.ChunkKey;
import net.mapmcbot.client.MapMcBotClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.MinecraftServer;

/**
 * Debug only, primtest's run (see AutoStart): every primitive at once, one a bot, each in its own cell
 * of the test area. A cell is an island CELL wide along z, PITCH from the one before, ROW_CELLS of them a row from the area's min corner on,
 * each row a floor ROW over the one before; the bot standing at its x min facing +x; its blocks, items and entities set up by server commands, then the primitive sent
 * (after the ones it needs first: an interact opening the window close_window closes), and once the bot
 * is idle its effect checked on the server's world. A primitive failed, or its effect missing, is told;
 * once every bot is over, the result. A bot's first step goes once it has every column of its cell in its
 * own world (see Columns): sent before, its blocks are not there yet.
 *
 * Neither thread's queue is ever handed a task from inside the other's: 1.12 holds a queue while it runs
 * its tasks, so a client task submitting to the server while a server task submits to the client is a
 * deadlock. What the server hands the client goes through HANDOFF.
 */
final class PrimTest implements BotRegistry.StateListener {
	/** How wide along z a cell's island is, and how far apart along z the cells are: islands with air between. */
	private static final int CELL = 6;
	private static final int PITCH = CELL + 2;
	/** How far along x a cell goes past its min corner, and how high its air. */
	private static final int LENGTH = 14;
	private static final int CLEAR = 10;
	/** How many cells a row has along z, and how far up a row's floor is from the one before: over its air. */
	private static final int ROW_CELLS = 5;
	private static final int ROW = CLEAR + 2;
	/** Each island's floor, the i-th the i-th bot's: none falls, none powers, none the emerald of a pass. */
	private static final String[] FLOORS = {"stone", "planks", "brick_block", "sandstone", "stonebrick", "quartz_block", "mossy_cobblestone", "obsidian", "end_stone", "nether_brick", "prismarine", "purpur_block", "hardened_clay", "red_nether_brick", "iron_block", "gold_block", "diamond_block", "lapis_block", "coal_block", "bone_block", "glowstone", "sea_lantern", "red_sandstone", "log", "wool"};
	/** The items every bot gets after its case's own: the case's take the first hotbar slots. */
	private static final String[] KIT = {AutoStart.PICK, AutoStart.SHOVEL, AutoStart.BLOCKS};
	private static final String[] KIT_NAMES = {"minecraft:diamond_pickaxe", "minecraft:diamond_shovel", "minecraft:cobblestone"};
	/** Its own thread, holding neither queue: from the server's tasks to the client's (see the class doc). */
	private static final Executor HANDOFF = Executors.newSingleThreadExecutor(task -> {
		final Thread thread = new Thread(task, "prim test handoff");
		thread.setDaemon(true);
		return thread;
	});

	/**
	 * The columns each bot has in its own world, as the fleet tells them (see BotRegistry.ColumnListener):
	 * heard from before the bots join (see AutoStart), so none is missed. Columns a bot let go are not
	 * told and stay in: a bot only waits on its cell's after its tp there, which it has in sight.
	 */
	static final class Columns implements BotRegistry.ColumnListener {
		private final Map<String, Set<Long>> in = new HashMap<String, Set<Long>>();
		private final Map<String, Set<Long>> awaited = new HashMap<String, Set<Long>>();
		private final Map<String, Runnable> then = new HashMap<String, Runnable>();

		@Override
		public void onColumnIn(String bot, ChunkKey key) {
			final Runnable ready;

			synchronized (this) {
				in.computeIfAbsent(bot, b -> new HashSet<Long>()).add(column(key.getX(), key.getZ()));
				ready = settled(bot);
			}

			// Out of the lock: then hands to the client's queue (see whenIn).
			if (ready != null) {
				ready.run();
			}
		}

		/** then once the bot has every one of the columns, at once when it has them now. */
		void whenIn(String bot, Set<Long> columns, Runnable then) {
			final Runnable ready;

			synchronized (this) {
				if (awaited.containsKey(bot)) {
					throw new IllegalStateException("prim test: " + bot + " already waits on its columns");
				}

				awaited.put(bot, columns);
				this.then.put(bot, then);
				ready = settled(bot);
			}

			if (ready != null) {
				ready.run();
			}
		}

		private Runnable settled(String bot) {
			final Set<Long> columns = awaited.get(bot);

			if (columns == null || !in.getOrDefault(bot, new HashSet<Long>()).containsAll(columns)) {
				return null;
			}

			awaited.remove(bot);
			return then.remove(bot);
		}

		static long column(int x, int z) {
			return ((long) x << 32) | (z & 0xffffffffL);
		}
	}

	/** A step of a case: a primitive as JSON; while it runs, a server command run once, or the next step sent. */
	private static final class Step {
		final String json;
		final String whileDoing;
		final boolean chain;

		Step(String json, String whileDoing, boolean chain) {
			this.json = json;
			this.whileDoing = whileDoing;
			this.chain = chain;
		}
	}

	/** The effect of a case checked on the server thread: why it is missing, null with it there. */
	private interface Check {
		String failure(MinecraftServer server, String bot);
	}

	/** One primitive's test, in the cell at (x, y, z), its middle line z. */
	private static final class Case {
		final String primitive;
		final List<String> setUp = new ArrayList<String>();
		final List<String> gives = new ArrayList<String>();
		final List<String> items = new ArrayList<String>();
		final List<Step> steps = new ArrayList<Step>();
		String spawn;
		Check check;
		/** Whether check reads the fleet's copy (the client's), not the server's world. */
		boolean onClient;

		Case(String primitive) {
			this.primitive = primitive;
		}

		Case block(int x, int y, int z, String block) {
			setUp.add("setblock " + x + " " + y + " " + z + " minecraft:" + block);
			return this;
		}

		/** An item given before the kit: the first one on hotbar slot 0 (window slot 36). */
		Case give(String item, int count) {
			gives.add(item + " " + count);
			items.add(item);
			return this;
		}

		Case step(String json) {
			steps.add(new Step(json, null, false));
			return this;
		}

		Case stepWhile(String json, String command) {
			steps.add(new Step(json, command, false));
			return this;
		}

		Case chained(String json) {
			steps.add(new Step(json, null, true));
			return this;
		}

		Case check(Check check) {
			this.check = check;
			return this;
		}

		Case checkOnClient(Check check) {
			this.onClient = true;
			return check(check);
		}
	}

	private final MinecraftServer server;
	private final Area area;
	private final Columns columns;
	/** The columns of each bot's cell, chunk x and z (see Columns.column). */
	private final Map<String, Set<Long>> cells = new HashMap<String, Set<Long>>();
	private final Map<String, Case> cases = new HashMap<String, Case>();
	/** Where each bot's emerald block goes once its case passed: its island's W end, on its middle line. */
	private final Map<String, String> passMarks = new HashMap<String, String>();
	private final List<String> bots = new ArrayList<String>();
	/** Each bot's step running, and whether it was sent and not yet seen going. */
	private final Map<String, Integer> steps = new HashMap<String, Integer>();
	private final Map<String, Boolean> starting = new HashMap<String, Boolean>();
	/** The step whose whileDoing ran, per bot. */
	private final Map<String, Integer> hooked = new HashMap<String, Integer>();
	private final List<String> failed = new ArrayList<String>();
	private int over;

	PrimTest(MinecraftServer server, List<String> fleet, Area area, Columns columns) {
		this.server = server;
		this.area = area;
		this.columns = columns;
		final List<Case> all = cases(area);

		if (fleet.size() < all.size()) {
			throw new IllegalStateException("prim test: " + all.size() + " primitives and " + fleet.size() + " bots, one a bot");
		}

		for (int i = 0; i < all.size(); i++) {
			final Case one = all.get(i);
			final int y = area.getMinY() + ROW * (i / ROW_CELLS);
			final int z = area.getMinZ() + PITCH * (i % ROW_CELLS) + CELL / 2;

			if (one.spawn == null) {
				one.spawn = (area.getMinX() + 0.5) + " " + y + " " + (z + 0.5);
			}

			bots.add(fleet.get(i));
			passMarks.put(fleet.get(i), (area.getMinX() - 2) + " " + y + " " + z);
			cases.put(fleet.get(i), one);
			// The cell's columns: its x min - 2 (the plate's) to LENGTH, its CELL along z.
			final int cellZ = area.getMinZ() + PITCH * (i % ROW_CELLS);
			final Set<Long> cell = new HashSet<Long>();

			for (int cx = (area.getMinX() - 2) >> 4; cx <= (area.getMinX() + LENGTH) >> 4; cx++) {
				for (int cz = cellZ >> 4; cz <= (cellZ + CELL - 1) >> 4; cz++) {
					cell.add(Columns.column(cx, cz));
				}
			}

			cells.put(fleet.get(i), cell);
		}
	}

	/** The cases, the i-th in cell i: on its row's floor y, its middle line z the cell's min z plus CELL / 2. */
	private static List<Case> cases(Area area) {
		final int x = area.getMinX();
		final List<Case> out = new ArrayList<Case>();
		// The floor and the middle line of the case added next.
		final IntSupplier yc = () -> area.getMinY() + ROW * (out.size() / ROW_CELLS);
		final IntSupplier zc = () -> area.getMinZ() + PITCH * (out.size() % ROW_CELLS) + CELL / 2;

		int y = yc.getAsInt();
		int z = zc.getAsInt();
		out.add(new Case("goto").step("{\"action\":\"goto\",\"spot\":" + pos(x + 6, y, z) + "}").check(at(x + 6, y, z)));

		y = yc.getAsInt();
		z = zc.getAsInt();
		out.add(new Case("interact").block(x + 2, y, z, "dirt").step("{\"action\":\"interact\",\"button\":\"left\",\"target\":{\"kind\":\"block\",\"x\":" + (x + 2) + ",\"y\":" + y + ",\"z\":" + z + "}}").check(blockIs(x + 2, y, z, "air")));

		y = yc.getAsInt();
		z = zc.getAsInt();
		out.add(new Case("break").block(x + 2, y, z, "stone").step("{\"action\":\"break\",\"target\":" + pos(x + 2, y, z) + "}").check(blockIs(x + 2, y, z, "air")));

		y = yc.getAsInt();
		z = zc.getAsInt();
		out.add(new Case("place").step("{\"action\":\"place\",\"target\":" + pos(x + 2, y, z) + "}").check(blockIs(x + 2, y, z, "cobblestone")));

		y = yc.getAsInt();
		z = zc.getAsInt();
		out.add(new Case("close_window").block(x + 2, y, z, "chest").step(rightOnBlock(x + 2, y, z)).step("{\"action\":\"close_window\"}").checkOnClient(windowIs(null)));

		out.add(new Case("move").give("minecraft:dirt", 5).step("{\"action\":\"move\",\"from\":36,\"to\":9}").check(holds(9, "dirt", 5)));

		out.add(new Case("item_fill").give("minecraft:dirt", 10).step("{\"action\":\"item_fill\",\"item\":{\"name\":\"minecraft:dirt\"},\"targets\":[20]}").check(holds(20, "dirt", 10)));

		// The kit on window slots 36..38, the dirt given while it waits lands on 39.
		out.add(new Case("wait_slot").stepWhile("{\"action\":\"wait_slot\",\"slots\":[{\"slot\":39,\"min\":5,\"item\":{\"name\":\"minecraft:dirt\"}}]}", "give %s minecraft:dirt 5").check(holds(3, "dirt", 5)));

		out.add(new Case("hotbar").step("{\"action\":\"hotbar\",\"slot\":40}").check(command("testfor %s {SelectedItemSlot:4}", "expected hotbar slot 4 in hand")));

		y = yc.getAsInt();
		z = zc.getAsInt();
		// Its own each run: a villager of a run before, killed in the same tick, still holds its UUID.
		final UUID villager = UUID.randomUUID();
		final Case trade = new Case("select_trade");
		trade.setUp.add("summon minecraft:villager " + (x + 2.5) + " " + y + " " + (z + 0.5) + " {NoAI:1b,Invulnerable:1b,Silent:1b,Profession:0,Career:1,CareerLevel:99,UUIDMost:" + villager.getMostSignificantBits() + "L,UUIDLeast:" + villager.getLeastSignificantBits() + "L,Offers:{Recipes:[{buy:{id:\"minecraft:emerald\",Count:1b,Damage:0s},sell:{id:\"minecraft:wheat\",Count:1b,Damage:0s},maxUses:9999,uses:0,rewardExp:0b}]}}");
		out.add(trade.step("{\"action\":\"interact\",\"button\":\"right\",\"target\":{\"kind\":\"entity\",\"uuid\":\"" + villager + "\"}}").step("{\"action\":\"select_trade\",\"trade\":0}").checkOnClient(windowIs("minecraft:villager")));

		out.add(new Case("look_at").step("{\"action\":\"look_at\",\"yaw\":90,\"pitch\":30}").check(looks(90, 30)));

		y = yc.getAsInt();
		z = zc.getAsInt();
		out.add(new Case("drop").give("minecraft:dirt", 5).step("{\"action\":\"drop\",\"slots\":[36]}").check(all(command("clear %s minecraft:dirt -1 0", null, "expected the dirt thrown, found it still in the inventory"), command("testfor @e[type=item,x=" + x + ",y=" + y + ",z=" + z + ",r=4]", "expected the dirt lying in front of the bot"))));

		out.add(new Case("shift_move").give("minecraft:dirt", 5).step("{\"action\":\"shift_move\",\"slot\":36}").check(holds(9, "dirt", 5)));

		y = yc.getAsInt();
		z = zc.getAsInt();
		out.add(new Case("stop").chained("{\"action\":\"goto\",\"spot\":" + pos(x + LENGTH - 2, y, z) + "}").step("{\"action\":\"stop\"}").check(notAt(x + LENGTH - 2, y, z)));

		y = yc.getAsInt();
		z = zc.getAsInt();
		out.add(new Case("break_wsr").block(x + 6, y, z, "stone").step("{\"action\":\"break_wsr\",\"target\":" + pos(x + 6, y, z) + ",\"radius\":4}").check(blockIs(x + 6, y, z, "air")));

		y = yc.getAsInt();
		z = zc.getAsInt();
		out.add(new Case("place_wsr").step("{\"action\":\"place_wsr\",\"target\":" + pos(x + 6, y, z) + ",\"radius\":4}").check(blockIs(x + 6, y, z, "cobblestone")));

		for (String kind : new String[] {"wsrp", "wsrpa"}) {
			y = yc.getAsInt();
		z = zc.getAsInt();
			out.add(new Case("break_" + kind).block(x + 4, y, z + 1, "stone").step("{\"action\":\"break_" + kind + "\",\"target\":" + pos(x + 4, y, z + 1) + ",\"radius\":2,\"end\":" + pos(x + 8, y, z) + "}").check(all(blockIs(x + 4, y, z + 1, "air"), at(x + 8, y, z))));
	
			y = yc.getAsInt();
		z = zc.getAsInt();
			out.add(new Case("place_" + kind).step("{\"action\":\"place_" + kind + "\",\"target\":" + pos(x + 4, y, z + 1) + ",\"radius\":2,\"end\":" + pos(x + 8, y, z) + "}").check(all(blockIs(x + 4, y, z + 1, "cobblestone"), at(x + 8, y, z))));
			}

		y = yc.getAsInt();
		z = zc.getAsInt();
		// The bot spawned with the first one in sight and reach: break_pac2c fails unless it starts breaking at once.
		final Case pac = new Case("break_pac2c").block(x + 6, y, z + 1, "stone").block(x + 7, y + 1, z - 1, "stone").block(x + 9, y, z + 1, "stone");
		pac.spawn = (x + 4.5) + " " + y + " " + (z + 0.5);
		out.add(pac.step("{\"action\":\"break_pac2c\",\"targets\":[" + pos(x + 6, y, z + 1) + "," + pos(x + 7, y + 1, z - 1) + "," + pos(x + 9, y, z + 1) + "],\"end\":" + pos(x + 8, y, z) + "}")
			.check(all(blockIs(x + 6, y, z + 1, "air"), blockIs(x + 7, y + 1, z - 1, "air"), blockIs(x + 9, y, z + 1, "air"), at(x + 8, y, z))));

		// A band 2 wide along z on the cell's middle line and the one after, the bot in front of it on their seam (see BandTest).
		y = yc.getAsInt();
		z = zc.getAsInt();
		final Case band = new Case("band");
		band.setUp.add(AutoStart.fill(x + 2, y, z, x + 5, y + 3, z + 1, "stone", "0"));
		band.spawn = (x + 1.5) + " " + y + " " + (z + 1);
		out.add(band.step("{\"action\":\"band\",\"from\":" + pos(x + 2, y, z) + ",\"to\":" + pos(x + 5, y + 3, z + 1) + "}").check(command("testforblocks " + (x + 2) + " " + y + " " + z + " " + (x + 5) + " " + (y + 3) + " " + (z + 1) + " " + (x + 2) + " " + (y + CLEAR - 3) + " " + z, "expected the band dug out, found blocks left in it")));

		return out;
	}

	/** The items of the bot's case and the kit, for AutoStart to see it armed. */
	String[] kit(String bot) {
		final List<String> names = new ArrayList<String>(cases.get(bot).items);
		names.addAll(Arrays.asList(KIT_NAMES));
		return names.toArray(new String[0]);
	}

	/** The cells built, the bots in them; on the server thread, then done on it. */
	void setUp(Runnable done) {
		final int minX = area.getMinX() - 2;
		final int maxX = area.getMinX() + LENGTH;
		final int minZ = area.getMinZ();
		final int maxZ = area.getMinZ() + PITCH * (Math.min(ROW_CELLS, bots.size()) - 1) + CELL - 1;
		final int rows = (bots.size() + ROW_CELLS - 1) / ROW_CELLS;

		server.submit(() -> {
			for (int cx = minX >> 4; cx <= maxX >> 4; cx++) {
				for (int cz = minZ >> 4; cz <= maxZ >> 4; cz++) {
					server.getWorld(0).getChunk(cx, cz);
				}
			}

			for (int row = 0; row < rows; row++) {
				final int y = area.getMinY() + ROW * row;

				// Air down to under the floor, so the islands stand apart; then each cell's island.
				for (String fill : AutoStart.fillsAlongZ(minX, y - 1, minZ, maxX, y + CLEAR, maxZ, "air")) {
					AutoStart.run(server, fill, false);
				}

				for (int cell = 0; cell < Math.min(ROW_CELLS, bots.size() - ROW_CELLS * row); cell++) {
					final int index = ROW_CELLS * row + cell;
					final int cellZ = minZ + PITCH * cell;

					if (index >= FLOORS.length) {
						throw new IllegalStateException("prim test: " + bots.size() + " bots and " + FLOORS.length + " floors, one a bot");
					}

					for (String fill : AutoStart.fillsAlongZ(minX, y - 1, cellZ, maxX, y - 1, cellZ + CELL - 1, FLOORS[index])) {
						AutoStart.run(server, fill, false);
					}

					// Its primitive on a sign on the floor's W face, on the middle line (wall_sign 4: facing W).
					AutoStart.run(server, "setblock " + (minX - 1) + " " + (y - 1) + " " + (cellZ + CELL / 2) + " minecraft:wall_sign 4 replace {Text2:\"{\\\"text\\\":\\\"" + cases.get(bots.get(index)).primitive + "\\\"}\"}", true);
				}
			}

			AutoStart.run(server, "kill @e[type=item]", false);
			AutoStart.run(server, "kill @e[type=villager]", false);

			for (String bot : bots) {
				final Case one = cases.get(bot);
				// Facing +x (yaw -90), along the cell.
				AutoStart.run(server, "tp " + bot + " " + one.spawn + " -90 0", true);
				AutoStart.run(server, "clear " + bot, false);
				AutoStart.run(server, "effect " + bot + " clear", false);
				AutoStart.run(server, "effect " + bot + " minecraft:instant_health 1 10", true);
				AutoStart.run(server, "effect " + bot + " minecraft:saturation 1 10", true);

				for (String command : one.setUp) {
					AutoStart.run(server, command, true);
				}
			}

			for (String bot : bots) {
				for (String item : cases.get(bot).gives) {
					AutoStart.run(server, "give " + bot + " " + item, true);
				}

				for (String item : KIT) {
					AutoStart.run(server, "give " + bot + " " + item, true);
				}
			}

			HANDOFF.execute(done);
		});
	}

	/** Every bot's first step sent once it has its cell's columns (see Columns); on the client thread. */
	void start() {
		MapMcBotClient.bots().addStateListener(this);

		for (String bot : bots) {
			columns.whenIn(bot, cells.get(bot), () -> MinecraftClient.getInstance().submit(() -> {
				steps.put(bot, 0);
				send(bot);
			}));
		}
	}

	private void send(String bot) {
		starting.put(bot, true);
		MapMcBotClient.bots().action(bot, cases.get(bot).steps.get(steps.get(bot)).json);
	}

	@Override
	public void onState(String from, BotStatus status) {
		if (!steps.containsKey(from)) {
			return;
		}

		MinecraftClient.getInstance().submit(() -> onClient(from, status));
	}

	private void onClient(String bot, BotStatus status) {
		final Integer at = steps.get(bot);

		if (at == null) {
			return;
		}

		final Case one = cases.get(bot);
		final Step step = one.steps.get(at);

		switch (status.getKind()) {
			case DOING:
				starting.put(bot, false);

				if (!at.equals(hooked.get(bot))) {
					hooked.put(bot, at);

					if (step.whileDoing != null) {
						final String command = String.format(step.whileDoing, bot);
						server.submit(() -> AutoStart.run(server, command, true));
					}

					if (step.chain) {
						steps.put(bot, at + 1);
						send(bot);
					}
				}
				break;

			case ERROR:
				end(bot, status.getPrimitive() + " failed: " + status.getMessage());
				break;

			case IDLE:
				if (starting.get(bot)) {
					break;
				}

				if (at + 1 < one.steps.size()) {
					steps.put(bot, at + 1);
					send(bot);
					break;
				}

				steps.remove(bot);

				if (one.onClient) {
					end(bot, one.check.failure(server, bot));
				} else {
					server.submit(() -> {
						final String failure = one.check.failure(server, bot);
						HANDOFF.execute(() -> MinecraftClient.getInstance().submit(() -> end(bot, failure)));
					});
				}
				break;

			default:
				break;
		}
	}

	private void end(String bot, String failure) {
		steps.remove(bot);
		final String primitive = cases.get(bot).primitive;
		over++;

		if (failure == null) {
			System.out.println("[prim test] " + primitive + " (" + bot + ") ok");
			final String mark = "setblock " + passMarks.get(bot) + " minecraft:emerald_block";
			// From the client's thread: through HANDOFF (see the class doc).
			HANDOFF.execute(() -> server.submit(() -> AutoStart.run(server, mark, true)));
		} else {
			failed.add(primitive);
			System.out.println("[prim test] " + primitive + " (" + bot + ") FAILED: " + failure);
		}

		if (over == bots.size()) {
			System.out.println("[prim test] " + (over - failed.size()) + " of " + over + " ok" + (failed.isEmpty() ? "" : ", failed: " + String.join(", ", failed)));
		}
	}

	private static String pos(int x, int y, int z) {
		return "{\"x\":" + x + ",\"y\":" + y + ",\"z\":" + z + "}";
	}

	private static String rightOnBlock(int x, int y, int z) {
		return "{\"action\":\"interact\",\"button\":\"right\",\"target\":{\"kind\":\"block\",\"x\":" + x + ",\"y\":" + y + ",\"z\":" + z + "}}";
	}

	/** A server command that must find something (%s the bot), or must not with found null. */
	private static Check command(String command, String missing) {
		return command(command, missing, null);
	}

	private static Check command(String command, String missing, String found) {
		return (server, bot) -> {
			final boolean any = server.getCommandManager().execute(server, String.format(command, bot)) > 0;
			return any ? found : missing;
		};
	}

	private static Check blockIs(int x, int y, int z, String block) {
		return command("testforblock " + x + " " + y + " " + z + " minecraft:" + block, "expected minecraft:" + block + " at " + x + " " + y + " " + z);
	}

	/** The player's inventory slot (NBT: hotbar 0..8, main 9..35; window slot 36 + i for hotbar i) holding count of the item. */
	private static Check holds(int slot, String item, int count) {
		return command("testfor %s {Inventory:[{Slot:" + slot + "b,id:\"minecraft:" + item + "\",Count:" + count + "b}]}", "expected " + count + " x minecraft:" + item + " on inventory slot " + slot);
	}

	private static Check at(int x, int y, int z) {
		return (server, bot) -> {
			final PlayerEntity player = server.getWorld(0).getPlayerByName(bot);
			final int[] feet = {(int) Math.floor(player.x), (int) Math.floor(player.y), (int) Math.floor(player.z)};
			return feet[0] == x && feet[1] == y && feet[2] == z ? null : "expected the bot at " + x + " " + y + " " + z + ", found it at " + player.x + " " + player.y + " " + player.z;
		};
	}

	private static Check notAt(int x, int y, int z) {
		return (server, bot) -> at(x, y, z).failure(server, bot) == null ? "expected the walk to " + x + " " + y + " " + z + " stopped, found the bot there" : null;
	}

	private static Check looks(float yaw, float pitch) {
		return (server, bot) -> {
			final PlayerEntity player = server.getWorld(0).getPlayerByName(bot);
			final float wrapped = ((player.yaw % 360) + 360) % 360;
			return wrapped == yaw && player.pitch == pitch ? null : "expected yaw " + yaw + " pitch " + pitch + ", found " + player.yaw + " " + player.pitch;
		};
	}

	/** The bot's window as the fleet has it: of the type, or none with null. */
	private static Check windowIs(String type) {
		return (server, bot) -> {
			final BotWindow window = MapMcBotClient.bots().getWindow(bot);
			final String found = window == null ? null : window.getType();
			return type == null ? found == null ? null : "expected no window open, found " + found : type.equals(found) ? null : "expected window " + type + ", found " + found;
		};
	}

	private static Check all(Check... checks) {
		return (server, bot) -> {
			for (Check check : checks) {
				final String failure = check.failure(server, bot);

				if (failure != null) {
					return failure;
				}
			}

			return null;
		};
	}
}

package net.mapmcbot.client;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.mapmcbot.area.Area;
import net.mapmcbot.bot.BotInventory;
import net.mapmcbot.bot.BotRegistry;
import net.mapmcbot.bot.BotStatus;
import net.mapmcbot.chunk.ChunkKey;
import net.mapmcbot.chunk.ChunkRegistry;
import net.mapmcbot.job.JobRunner;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.BlockWithEntity;
import net.minecraft.block.Blocks;
import net.minecraft.block.FallingBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Identifier;

/**
 * A quarrify: the bot digs out an area, a plan ({ action: quarrify, area: its id }) the mod resolves
 * into primitives, each sent as the one before ends, by the bot's state: nothing timed.
 *
 * It walks first to the nearest spot the pathfinder finds in the area or a block round it, from its
 * bottom up to standing on its top. Where it ends up (its feet, as its state tells) decides the rest,
 * the top being the highest layer with something to dig:
 *  - below the top, it digs the two layers at its feet and head first (a room, whole), then the
 *    staircase up from there to the top, climbing it as it goes;
 *  - then (on the top or over it, right away) the area from the top down, three layers at a time (a
 *    slab): the staircase carved down through the slab, the bot on the slab's floor, and the slab dug
 *    from there, the block nearest to the bot first, one next to what is open to it before the rest.
 * Last, whatever is left (a block fallen into a layer already dug) is dug the same way.
 *
 * The staircase is the 2x2 at its north-east corner (most x, least z): a spiral down its four
 * columns, one step a layer, each the next column round from the one over it, so each column has a
 * step every 4 layers with 3 of air over it. A step that is not solid is placed (with a full block of
 * the hotbar) against a solid neighbour before its layer is dug; with none, on supports placed past
 * it the way it goes (down, or up climbing), each dug with its layer after. One that falls (sand,
 * gravel) is dug and placed again. Narrower than 2x2 or one high there is none: the area is dug a
 * layer at a time from the top, up to 5 high.
 *
 * Each block is broken with the hotbar's item that breaks it fastest, walked in sight of first (the
 * walk ends at once when it is in sight already); the one the bot stands on comes last of its turn.
 */
final class Quarrify implements JobRunner.Resolver, BotRegistry.StateListener, BotRegistry.ActionListener {
	/** The highest an area with no staircase (narrower than 2x2) is dug. */
	private static final int FLAT_HEIGHT = 5;
	/** The layers of a slab: from its floor the bot reaches its feet's, its head's and the one over it. */
	private static final int SLAB = 3;
	/** How high the eyes are over the feet. */
	private static final double EYES = 1.62;
	/** The break's deadline when its dig takes less than half of it (actions.js DEFAULT_DEADLINE), in ms. */
	private static final int DEADLINE = 5000;
	/** Breaks in a row that may lose sight of their block (see onState) before the quarrify fails. */
	private static final int SIGHT_MISSES = 3;

	private static final int[][] FACES = {{0, -1, 0}, {0, 1, 0}, {1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}};

	private enum Phase { APPROACH, ROOM, CLIMB, DESCEND, FLAT, SWEEP }

	/**
	 * A turn of the plan: its steps made solid, then (from where it says to stand) its cells dug, in
	 * order or the nearest to the bot first.
	 */
	private static final class Group {
		final String what;
		final List<int[]> steps = new ArrayList<int[]>();
		/** The way the steps' supports go: -1 down, 1 up. */
		final int way;
		/** The layer to stand on the floor of before the cells, {x, y, z} of the feet; null for anywhere. */
		final int[] stand;
		final List<int[]> cells;
		final boolean nearest;
		int nextStep;
		boolean stood;

		Group(String what, int way, int[] stand, List<int[]> cells, boolean nearest) {
			this.what = what;
			this.way = way;
			this.stand = stand;
			this.cells = cells;
			this.nearest = nearest;
		}
	}

	private static final class Plan {
		final Area area;
		final boolean stairs;
		final JobRunner.Listener listener;
		volatile Phase phase = Phase.APPROACH;
		/** The highest layer with something to dig, as it arrived. */
		int top;
		int roomBottom;
		int roomTop;
		/** The next layer of the staircase to climb. */
		int climbY;
		/** The slab's top layer, and the next of its layers to carve the staircase down through. */
		int slabTop;
		int shaftY;
		/** The next layer to dig, with no staircase. */
		int flatY;
		volatile Group group;
		/** The primitives left of the block it is on, in order. */
		final Deque<JsonObject> queue = new ArrayDeque<JsonObject>();
		/** The block its feet are in, as its last state told. */
		volatile int[] feet;
		/** The hotbar slot in its hand (window numbering), as last sent; -1 before the first. */
		int held;
		/** What is open to the bot: the blocks it dug and the ones it stood in, by key(). */
		final Set<Long> open = new HashSet<Long>();
		volatile int dug;
		/** What it is doing, for the status. */
		volatile String doing = "walk";
		/** A primitive sent and not yet seen going: an idle before that is the end of the one before it, not of this. */
		volatile boolean starting;
		/** The last primitive sent. */
		volatile String sent = "";
		/** Breaks in a row that lost sight of their block. */
		int misses;

		Plan(Area area, boolean stairs, int held, JobRunner.Listener listener) {
			this.area = area;
			this.stairs = stairs;
			this.held = held;
			this.listener = listener;
		}
	}

	private final BotRegistry bots;
	private final ChunkRegistry chunks;
	private final Map<String, Plan> plans = new ConcurrentHashMap<String, Plan>();
	/** Why each bot's last quarrify failed, until its next. */
	private final Map<String, String> errors = new ConcurrentHashMap<String, String>();

	Quarrify(BotRegistry bots, ChunkRegistry chunks) {
		this.bots = bots;
		this.chunks = chunks;
		bots.addStateListener(this);
		bots.addActionListener(this);
	}

	@Override
	public boolean handles(String json) {
		return new JsonParser().parse(json).getAsJsonObject().get("action").getAsString().equals("quarrify");
	}

	/** What the bot's quarrify is doing, or why it failed; null with none. */
	String status(String bot) {
		final Plan plan = plans.get(bot);

		if (plan != null) {
			final Group group = plan.group;
			final Phase phase = plan.phase;
			return "quarrify " + plan.area.getName() + ": " + (group != null ? group.what : phase != null ? phase.name().toLowerCase() : "done") + ", " + plan.dug + " dug, " + plan.doing;
		}

		return errors.containsKey(bot) ? "error " + errors.get(bot) : null;
	}

	@Override
	public void start(String bot, String json, JobRunner.Listener listener) {
		final String areaId = new JsonParser().parse(json).getAsJsonObject().get("area").getAsString();
		Area area = null;

		for (Area one : MapMcBotClient.areas().getAreas()) {
			if (one.getId().equals(areaId)) {
				area = one;
			}
		}

		errors.remove(bot);
		// A quarrify going on is over, this one in its place.
		takeOver(bot);

		if (area == null) {
			fail(bot, null, listener, "quarrify: no area " + areaId);
			return;
		}

		final boolean stairs = area.getWidth() >= 2 && area.getDepth() >= 2 && area.getHeight() >= 2;

		if (!stairs && area.getHeight() > FLAT_HEIGHT) {
			fail(bot, null, listener, "quarrify: an area higher than " + FLAT_HEIGHT + " and narrower than 2x2 is not done yet (" + area.getName() + " is " + area.getWidth() + "x" + area.getDepth() + "x" + area.getHeight() + ")");
			return;
		}

		// The hand as it is not known for sure (a hotbar just sent may not show in the inventory yet): the first dig picks one.
		final Plan plan = new Plan(area, stairs, -1, listener);
		plans.put(bot, plan);

		// The nearest spot in the area or a block round it, at any height from its bottom to standing on its top.
		final JsonObject box = new JsonObject();
		box.addProperty("minX", area.getMinX() - 1);
		box.addProperty("minY", area.getMinY());
		box.addProperty("minZ", area.getMinZ() - 1);
		box.addProperty("maxX", area.getMaxX() + 1);
		box.addProperty("maxY", area.getMaxY() + 1);
		box.addProperty("maxZ", area.getMaxZ() + 1);
		final JsonObject a = new JsonObject();
		a.addProperty("action", "goto");
		a.add("area", box);
		send(bot, plan, a);
	}

	@Override
	public void onState(String bot, BotStatus status) {
		final Plan plan = plans.get(bot);

		if (plan == null) {
			return;
		}

		if (status.hasFeet()) {
			plan.feet = status.getFeet();
		}

		if (status.getKind() == BotStatus.Kind.DOING) {
			plan.starting = false;
			return;
		}

		if (plan.starting && status.getKind() == BotStatus.Kind.IDLE) {
			return;
		}

		if (status.getKind() == BotStatus.Kind.ERROR) {
			final String message = status.getMessage();

			// A break whose block went out of sight as it turned to it (sand or gravel landing in the way):
			// the world moved, not the plan. The next block is picked again from it, a few times in a row.
			if (status.getPrimitive().equals("break") && (message.contains(" in reach and in sight, found ") || message.startsWith("expected the head turned to ")) && plan.misses < SIGHT_MISSES) {
				plan.misses++;
				plan.queue.clear();
				plan.doing = "lost sight of its block, picking again";
			} else {
				fail(bot, plan, plan.listener, "quarrify: " + status.getPrimitive() + ": " + message);
				return;
			}
		} else if (plan.sent.equals("break")) {
			plan.misses = 0;
		}

		final int[] feet = plan.feet;

		if (feet == null) {
			fail(bot, plan, plan.listener, "quarrify: expected where " + bot + " stands with its state, found it not told (a fleet older than the mod?)");
			return;
		}

		plan.open.add(key(feet[0], feet[1], feet[2]));
		plan.open.add(key(feet[0], feet[1] + 1, feet[2]));

		if (plan.phase == Phase.APPROACH && !arrive(bot, plan)) {
			return;
		}

		advance(bot, plan);
	}

	/** Where it arrived decides the turns: a room and the climb below the top, the slabs from there. False when it failed. */
	private boolean arrive(String bot, Plan plan) {
		final Area area = plan.area;
		plan.top = Integer.MIN_VALUE;

		for (int y = area.getMaxY(); y >= area.getMinY() && plan.top == Integer.MIN_VALUE; y--) {
			for (int[] cell : layer(plan, y)) {
				final BlockState state = state(bot, plan, cell[0], y, cell[1]);

				if (state == null) {
					return false;
				}

				if (!air(state)) {
					plan.top = y;
					break;
				}
			}
		}

		if (plan.top == Integer.MIN_VALUE) {
			// Nothing to dig.
			plan.phase = Phase.SWEEP;
			return true;
		}

		final int feet = plan.feet[1];

		if (!plan.stairs) {
			plan.phase = Phase.FLAT;
			plan.flatY = plan.top;
		} else if (feet <= plan.top) {
			plan.phase = Phase.ROOM;
			plan.roomBottom = feet;
			plan.roomTop = Math.min(feet + 1, plan.top);
		} else {
			startDescent(plan);
		}

		return true;
	}

	private static void startDescent(Plan plan) {
		plan.phase = Phase.DESCEND;
		plan.slabTop = plan.top;
		plan.shaftY = plan.top;
	}

	/** The next primitive, the next blocks' worked out as the ones before are over; none left, done. */
	private void advance(String bot, Plan plan) {
		while (plan.queue.isEmpty()) {
			if (!expand(bot, plan)) {
				return;
			}

			if (plan.phase == null) {
				plans.remove(bot, plan);
				plan.listener.done();
				return;
			}
		}

		final JsonObject a = plan.queue.poll();

		// The block to place picked as it is sent: the one before may have used a stack up.
		if (a.get("action").getAsString().equals("hotbar") && !a.has("slot")) {
			final int slot = blockSlot(bot);

			if (slot < 0) {
				fail(bot, plan, plan.listener, "quarrify: expected a full block in the hotbar of " + bot + " to place a step, found none");
				return;
			}

			a.addProperty("slot", slot);
		}

		if (a.get("action").getAsString().equals("hotbar")) {
			plan.held = a.get("slot").getAsInt();
		}

		send(bot, plan, a);
	}

	/** The plan's next block into primitives (or none, it being done already), on to the next turn when its are over; false when it failed. */
	private boolean expand(String bot, Plan plan) {
		Group group = plan.group;

		if (group == null) {
			group = nextGroup(bot, plan);

			if (group == null) {
				// Done (phase null), or failed.
				return plan.phase == null;
			}

			plan.group = group;
		}

		if (group.nextStep < group.steps.size()) {
			final int[] step = group.steps.get(group.nextStep);
			final BlockState state = state(bot, plan, step[0], step[1], step[2]);

			if (state == null) {
				return false;
			}

			// One that falls (sand, gravel) would once the block under it is dug: dug, the step still to make.
			if (solid(state) && state.getBlock() instanceof FallingBlock) {
				return dig(bot, plan, step[0], step[1], step[2]);
			}

			group.nextStep++;
			return step(bot, plan, group.way, step[0], step[1], step[2]);
		}

		if (!group.stood) {
			group.stood = true;

			if (group.stand != null && plan.feet[1] != group.stand[1]) {
				final int[] spot = standSpot(bot, plan, group.stand);

				if (spot != null) {
					final JsonObject a = new JsonObject();
					a.addProperty("action", "goto");
					a.add("spot", position(spot[0], spot[1], spot[2]));
					plan.queue.add(a);
					plan.doing = "down to " + at(spot[0], spot[1], spot[2]);
				}
			}

			return true;
		}

		final int[] cell = group.nearest ? nearest(bot, plan, group) : ordered(bot, plan, group);

		if (cell == null) {
			// Failed when a state was missing, or the turn is over.
			if (plans.get(bot) != plan) {
				return false;
			}

			plan.group = null;
			return true;
		}

		// Kept in the turn: one that is not air again (gravel fallen into it) is dug again.
		return dig(bot, plan, cell[0], cell[1], cell[2]);
	}

	/** The plan's next turn, its phase moved on; null when done (phase null) or failed. */
	private Group nextGroup(String bot, Plan plan) {
		final Area area = plan.area;

		while (true) {
			switch (plan.phase) {
				case ROOM:
					plan.phase = Phase.CLIMB;
					plan.climbY = plan.roomBottom;
					return box(bot, plan, "room " + plan.roomBottom + ".." + plan.roomTop, null, plan.roomBottom, plan.roomTop);

				case CLIMB:
					if (plan.climbY > plan.top) {
						startDescent(plan);
						continue;
					}

					return shaft(plan, plan.climbY++, 1);

				case DESCEND: {
					final int bottom = Math.max(area.getMinY(), plan.slabTop - SLAB + 1);

					if (plan.shaftY >= bottom) {
						return shaft(plan, plan.shaftY--, -1);
					}

					final Group slab = box(bot, plan, "slab " + bottom + ".." + plan.slabTop, new int[] {0, bottom, 0}, bottom, plan.top);

					if (slab != null && bottom - 1 >= area.getMinY()) {
						// It stands on the step of the floor's layer, made solid first.
						slab.steps.add(stepAt(area, bottom - 1));
					}

					plan.slabTop = bottom - 1;
					plan.shaftY = plan.slabTop;

					if (plan.slabTop < area.getMinY()) {
						plan.phase = Phase.SWEEP;
					}

					return slab;
				}

				case FLAT:
					if (plan.flatY < area.getMinY()) {
						plan.phase = Phase.SWEEP;
						continue;
					}

					final int y = plan.flatY--;
					return box(bot, plan, "layer " + y, null, y, y);

				case SWEEP: {
					final Group left = box(bot, plan, "what is left", null, area.getMinY(), area.getMaxY());

					if (left == null || !left.cells.isEmpty()) {
						return left;
					}

					plan.phase = null;
					return null;
				}

				default:
					throw new IllegalStateException("quarrify: no turn after " + plan.phase);
			}
		}
	}

	/**
	 * The turn of a layer of the staircase: its step made solid, the way it goes (-1 down, 1 up), then
	 * its three other blocks, the ones open on the side it comes from first.
	 */
	private static Group shaft(Plan plan, int y, int way) {
		final int[][] cells = cells(plan.area);
		final int k = stepIndex(plan.area, y);
		// Coming down, the one under the step over it last; climbing, the one over the step under it.
		final int[] order = way < 0 ? new int[] {k + 1, k + 2, k + 3} : new int[] {k + 2, k + 3, k + 1};
		final List<int[]> dig = new ArrayList<int[]>();

		for (int i : order) {
			final int[] cell = cells[i % 4];
			dig.add(new int[] {cell[0], y, cell[1]});
		}

		final Group group = new Group((way < 0 ? "stairs down " : "stairs up ") + y, way, null, dig, false);
		group.steps.add(stepAt(plan.area, y));
		return group;
	}

	/** The turn of the blocks to dig in the layers from lo to hi, the nearest first; null when a chunk was not loaded (failed). */
	private Group box(String bot, Plan plan, String what, int[] stand, int lo, int hi) {
		final List<int[]> dig = new ArrayList<int[]>();

		for (int y = hi; y >= lo; y--) {
			for (int[] cell : layer(plan, y)) {
				final BlockState state = state(bot, plan, cell[0], y, cell[1]);

				if (state == null) {
					return null;
				}

				if (!air(state)) {
					dig.add(new int[] {cell[0], y, cell[1]});
				}
			}
		}

		return new Group(what, -1, stand, dig, true);
	}

	/** The next block to dig in order, the one the bot stands on last; null when none is left. */
	private int[] ordered(String bot, Plan plan, Group group) {
		int[] under = null;

		for (int[] cell : group.cells) {
			final BlockState state = state(bot, plan, cell[0], cell[1], cell[2]);

			if (state == null) {
				return null;
			}

			if (air(state)) {
				continue;
			}

			if (!underFeet(plan, cell)) {
				return cell;
			}

			if (under == null) {
				under = cell;
			}
		}

		return under;
	}

	/**
	 * The block to dig nearest to the bot's eyes: of the ones next to what is open to it first, then of
	 * the ones next to air, then the one it stands on, the ones under sand or gravel after all those;
	 * null when none is left, or all are sealed in (failed).
	 */
	private int[] nearest(String bot, Plan plan, Group group) {
		final int[] feet = plan.feet;
		final double ex = feet[0] + 0.5;
		final double ey = feet[1] + EYES;
		final double ez = feet[2] + 0.5;
		Collections.sort(group.cells, new Comparator<int[]>() {
			@Override
			public int compare(int[] a, int[] b) {
				return Double.compare(distance(a, ex, ey, ez), distance(b, ex, ey, ez));
			}
		});

		// Ranks 0 to 2 as above, 3 to 5 the same under a block that falls (it would fall in once this is
		// dug: dug last, from over it first), 6 sealed in.
		final int[][] best = new int[7][];

		for (Iterator<int[]> it = group.cells.iterator(); it.hasNext();) {
			final int[] cell = it.next();
			final BlockState state = state(bot, plan, cell[0], cell[1], cell[2]);

			if (state == null) {
				return null;
			}

			if (air(state)) {
				it.remove();
				continue;
			}

			final int rank;

			if (underFeet(plan, cell)) {
				rank = 2;
			} else {
				// Open: not solid now (a step placed where it dug is solid again), and dug or stood in.
				boolean open = false;
				boolean air = false;

				for (int i = 0; i < FACES.length && !open; i++) {
					final int x = cell[0] + FACES[i][0];
					final int y = cell[1] + FACES[i][1];
					final int z = cell[2] + FACES[i][2];
					final BlockState there = state(bot, plan, x, y, z);

					if (there == null) {
						return null;
					}

					if (!solid(there)) {
						air = true;
						open = plan.open.contains(key(x, y, z));
					}
				}

				rank = open ? 0 : air ? 1 : 6;
			}

			final BlockState over = state(bot, plan, cell[0], cell[1] + 1, cell[2]);

			if (over == null) {
				return null;
			}

			final int ranked = rank < 6 && over.getBlock() instanceof FallingBlock ? rank + 3 : rank;

			if (ranked == 0) {
				return cell;
			}

			if (best[ranked] == null) {
				best[ranked] = cell;
			}
		}

		for (int rank = 1; rank < 6; rank++) {
			if (best[rank] != null) {
				return best[rank];
			}
		}

		if (best[6] != null) {
			// Seen from nowhere: a walk to sight of it would search all the bot reaches, and find none.
			fail(bot, plan, plan.listener, "quarrify: expected one of the " + group.cells.size() + " blocks left of " + group.what + " next to air, to dig it from there, found them all sealed in (" + at(best[6][0], best[6][1], best[6][2]) + " the nearest)");
		}

		return null;
	}

	private static double distance(int[] cell, double x, double y, double z) {
		final double dx = cell[0] + 0.5 - x;
		final double dy = cell[1] + 0.5 - y;
		final double dz = cell[2] + 0.5 - z;
		return dx * dx + dy * dy + dz * dz;
	}

	private static boolean underFeet(Plan plan, int[] cell) {
		final int[] feet = plan.feet;
		return cell[0] == feet[0] && cell[1] == feet[1] - 1 && cell[2] == feet[2];
	}

	/**
	 * Where to stand on the floor of the layer stand[1]: on the step under it, or (at the bottom of the
	 * area, with none) on a block under another column of the staircase; its feet's block and the one
	 * over it free. Null for none such: it stands where it is.
	 */
	private int[] standSpot(String bot, Plan plan, int[] stand) {
		final Area area = plan.area;
		final int y = stand[1];
		final List<int[]> columns = new ArrayList<int[]>();

		if (y - 1 >= area.getMinY()) {
			final int[] step = stepAt(area, y - 1);
			columns.add(new int[] {step[0], step[2]});
		} else {
			final int[] step = stepAt(area, y);

			for (int[] cell : cells(area)) {
				if (cell[0] != step[0] || cell[1] != step[2]) {
					columns.add(cell);
				}
			}
		}

		for (int[] column : columns) {
			final BlockState floor = state(bot, plan, column[0], y - 1, column[1]);
			final BlockState feet = state(bot, plan, column[0], y, column[1]);
			final BlockState head = state(bot, plan, column[0], y + 1, column[1]);

			if (floor == null || feet == null || head == null) {
				return null;
			}

			if (solid(floor) && !solid(feet) && !solid(head)) {
				return new int[] {column[0], y, column[1]};
			}
		}

		return null;
	}

	/** The block walked in sight of and broken, with the hotbar's item that breaks it fastest; air, nothing. False when it failed. */
	private boolean dig(String bot, Plan plan, int x, int y, int z) {
		final BlockState block = state(bot, plan, x, y, z);

		if (block == null) {
			return false;
		}

		if (air(block)) {
			return true;
		}

		final String name = name(block);

		if (block.getMaterial().isFluid()) {
			fail(bot, plan, plan.listener, "quarrify: expected a block to break at " + at(x, y, z) + ", found " + name);
			return false;
		}

		final float hardness = block.getBlock().getHardness(block, null, null);

		if (hardness < 0) {
			fail(bot, plan, plan.listener, "quarrify: expected a block to break at " + at(x, y, z) + ", found " + name + ", which does not break");
			return false;
		}

		final int tool = toolSlot(bot, plan, block);
		final ItemStack stack = BotScreen.stackOf(bots.getInventory(bot).getSlots().get(tool));

		if (hardness > 0 && !harvests(stack, block)) {
			fail(bot, plan, plan.listener, "quarrify: expected an item in the hotbar of " + bot + " that harvests " + name + " at " + at(x, y, z) + ", found none");
			return false;
		}

		if (tool != plan.held) {
			final JsonObject hotbar = new JsonObject();
			hotbar.addProperty("action", "hotbar");
			hotbar.addProperty("slot", tool);
			plan.queue.add(hotbar);
		}

		plan.queue.add(gotoLook(x, y, z));
		final JsonObject a = new JsonObject();
		a.addProperty("action", "break");
		a.add("target", position(x, y, z));
		// Twice its dig and 2 s, when that is past the default: an obsidian one takes longer than it.
		final int ms = hardness == 0 ? 0 : (int) Math.ceil(hardness / speed(stack, block)) * 50;

		if (2 * ms + 2000 > DEADLINE) {
			a.addProperty("deadline", 2 * ms + 2000);
		}

		plan.queue.add(a);
		plan.open.add(key(x, y, z));
		plan.dug++;
		plan.doing = "dig " + at(x, y, z);
		return true;
	}

	/**
	 * The step at the spot made solid: kept when it is, placed when not, against a solid neighbour;
	 * with none, on supports placed past it the way it goes (way: -1 down, 1 up), in the area, up to
	 * one that has.
	 */
	private boolean step(String bot, Plan plan, int way, int x, int y, int z) {
		final BlockState state = state(bot, plan, x, y, z);

		if (state == null) {
			return false;
		}

		if (solid(state)) {
			return true;
		}

		// From the step on: each spot placed on the one past it, the last against the neighbour found.
		final List<int[]> chain = new ArrayList<int[]>();
		int[] against = null;

		for (int at = y; against == null; at += way) {
			// Supports in the area only: its blocks are dug with their layers after; past it they would stay.
			if (at < plan.area.getMinY() || at > plan.area.getMaxY()) {
				fail(bot, plan, plan.listener, "quarrify: expected a solid block next to the step at " + at(x, y, z) + " or to its column " + (way < 0 ? "under" : "over") + " it in the area, found none: the staircase would stand in the air");
				return false;
			}

			chain.add(new int[] {x, at, z});

			for (int[] face : FACES) {
				final BlockState there = state(bot, plan, x + face[0], at + face[1], z + face[2]);

				if (there == null) {
					return false;
				}

				if (solid(there)) {
					against = new int[] {x + face[0], at + face[1], z + face[2]};
					break;
				}
			}
		}

		// Out of the spots first: a block is not placed where the bot stands.
		final int[] feet = plan.feet;

		for (int[] spot : chain) {
			if (spot[0] == feet[0] && spot[2] == feet[2] && (spot[1] == feet[1] || spot[1] == feet[1] + 1)) {
				final int[] aside = aside(bot, plan, chain);

				if (aside == null) {
					return false;
				}

				final JsonObject a = new JsonObject();
				a.addProperty("action", "goto");
				a.add("spot", position(aside[0], aside[1], aside[2]));
				plan.queue.add(a);
				break;
			}
		}

		for (int i = chain.size() - 1; i >= 0; i--) {
			final int[] spot = chain.get(i);
			final int[] reference = i == chain.size() - 1 ? against : chain.get(i + 1);
			final JsonObject hotbar = new JsonObject();
			hotbar.addProperty("action", "hotbar");
			plan.queue.add(hotbar);
			plan.queue.add(gotoLook(reference[0], reference[1], reference[2]));
			final JsonObject a = new JsonObject();
			a.addProperty("action", "place");
			a.add("target", position(spot[0], spot[1], spot[2]));
			plan.queue.add(a);
		}

		plan.doing = "step " + at(x, y, z) + (chain.size() > 1 ? " on " + (chain.size() - 1) + " support(s)" : "");
		return true;
	}

	/**
	 * A block next to the bot's feet, the sides first, to stand in out of the spots: one to stand on
	 * under it, it and the one over it free. Null when there is none, the plan failed with it.
	 */
	private int[] aside(String bot, Plan plan, List<int[]> spots) {
		final int[] feet = plan.feet;
		final int[][] around = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

		for (int[] d : around) {
			final int x = feet[0] + d[0];
			final int z = feet[2] + d[1];
			boolean taken = false;

			for (int[] spot : spots) {
				taken |= spot[0] == x && spot[2] == z && (spot[1] == feet[1] || spot[1] == feet[1] + 1);
			}

			final BlockState floor = state(bot, plan, x, feet[1] - 1, z);
			final BlockState body = state(bot, plan, x, feet[1], z);
			final BlockState head = state(bot, plan, x, feet[1] + 1, z);

			if (floor == null || body == null || head == null) {
				return null;
			}

			if (!taken && solid(floor) && !solid(body) && !solid(head)) {
				return new int[] {x, feet[1], z};
			}
		}

		fail(bot, plan, plan.listener, "quarrify: expected a block next to " + bot + " at " + at(feet[0], feet[1], feet[2]) + " to stand in out of the step to place there, found none");
		return null;
	}

	/** The columns of the layer to dig, {x, z}: all of the area's, its step's left out. */
	private static List<int[]> layer(Plan plan, int y) {
		final Area area = plan.area;
		final int[] step = plan.stairs ? stepAt(area, y) : null;
		final List<int[]> columns = new ArrayList<int[]>();

		for (int z = area.getMinZ(); z <= area.getMaxZ(); z++) {
			for (int x = area.getMinX(); x <= area.getMaxX(); x++) {
				if (step == null || step[0] != x || step[2] != z) {
					columns.add(new int[] {x, z});
				}
			}
		}

		return columns;
	}

	/** The layer's step, {x, y, z}. */
	private static int[] stepAt(Area area, int y) {
		final int[] cell = cells(area)[stepIndex(area, y)];
		return new int[] {cell[0], y, cell[1]};
	}

	/** The layer's step's column, as cells() has them: the corner first at the top, one column round a layer. */
	private static int stepIndex(Area area, int y) {
		return (area.getMaxY() - y) % 4;
	}

	/** The staircase's 2x2, {x, z}, gone round from its north-east corner. */
	private static int[][] cells(Area area) {
		final int east = area.getMaxX();
		final int north = area.getMinZ();
		return new int[][] {{east, north}, {east - 1, north}, {east - 1, north + 1}, {east, north + 1}};
	}

	/**
	 * The hotbar slot (window numbering) whose item breaks the block fastest, the one in hand on a tie:
	 * its speed on the block (efficiency added), a third as fast with the block not harvested by it.
	 */
	private int toolSlot(String bot, Plan plan, BlockState block) {
		final BotInventory inventory = bots.getInventory(bot);

		if (inventory == null) {
			throw new IllegalStateException("quarrify: no inventory of " + bot + " yet");
		}

		final List<BotInventory.Item> slots = inventory.getSlots();
		int best = plan.held;
		float bestSpeed = best < 0 ? -1 : speed(BotScreen.stackOf(slots.get(best)), block);

		for (int slot = 36; slot <= 44; slot++) {
			final float speed = speed(BotScreen.stackOf(slots.get(slot)), block);

			if (speed > bestSpeed) {
				best = slot;
				bestSpeed = speed;
			}
		}

		return best;
	}

	/** How much of the block's hardness the item breaks a tick, as the game has it (on the ground, out of water). */
	private static float speed(ItemStack stack, BlockState block) {
		float speed = stack.getBlockBreakingSpeed(block);

		if (speed > 1) {
			final int efficiency = EnchantmentHelper.getLevel(Enchantments.EFFICIENCY, stack);

			if (efficiency > 0) {
				speed += efficiency * efficiency + 1;
			}
		}

		return harvests(stack, block) ? speed / 30 : speed / 100;
	}

	/** Whether breaking the block with the item drops it: it needs no tool, or the item is one. */
	private static boolean harvests(ItemStack stack, BlockState block) {
		// doesBlockMovement: no tool needed (yarn's name for it).
		return block.getMaterial().doesBlockMovement() || (!stack.isEmpty() && stack.method_11396(block));
	}

	/** The hotbar slot (window numbering) of a full block that does not fall and holds nothing; -1 for none. */
	private int blockSlot(String bot) {
		final BotInventory inventory = bots.getInventory(bot);

		if (inventory == null) {
			throw new IllegalStateException("quarrify: no inventory of " + bot + " yet");
		}

		final List<BotInventory.Item> slots = inventory.getSlots();

		for (int slot = 36; slot <= 44; slot++) {
			final BotInventory.Item item = slots.get(slot);

			if (item == null) {
				continue;
			}

			final Item kind = Item.REGISTRY.get(new Identifier(item.getName()));

			if (kind == null) {
				throw new IllegalStateException("no item " + item.getName() + " in the registry");
			}

			if (!(kind instanceof BlockItem)) {
				continue;
			}

			final Block block = ((BlockItem) kind).getBlock();

			if (block.isFullBlock(block.getDefaultState()) && !(block instanceof FallingBlock) && !(block instanceof BlockWithEntity)) {
				return slot;
			}
		}

		return -1;
	}

	/**
	 * The block's state as the bots hold it, the game's own by its id in the slot (the registry's names
	 * are the flattened ones, grass_block and oak_log, which this version has no blocks of); null when
	 * its chunk is not loaded, the plan failed with it.
	 */
	private BlockState state(String bot, Plan plan, int x, int y, int z) {
		final ChunkKey key = new ChunkKey(bots.getServer(bot), dimension(), x >> 4, z >> 4);

		if (!chunks.isLoaded(key)) {
			fail(bot, plan, plan.listener, "quarrify: the chunk of " + at(x, y, z) + " is not loaded by the bots");
			return null;
		}

		final int id = chunks.getStateId(key, x, y, z);
		final BlockState state = Block.BLOCK_STATES.fromId(id);

		if (state == null) {
			throw new IllegalStateException("quarrify: no block state of id " + (id >> 4) + ":" + (id & 15) + " (" + chunks.getState(key, x, y, z) + ") at " + at(x, y, z) + " in this version");
		}

		return state;
	}

	private static boolean air(BlockState state) {
		return state.getBlock() == Blocks.AIR;
	}

	/** Whether the block stops movement: one to stand on, or to place against. */
	private static boolean solid(BlockState state) {
		return state.getMaterial().blocksMovement();
	}

	private static String name(BlockState state) {
		return Block.REGISTRY.getIdentifier(state.getBlock()).toString();
	}

	private static long key(int x, int y, int z) {
		return ((long) x & 0x3ffffff) << 38 | ((long) y & 0xfff) << 26 | ((long) z & 0x3ffffff);
	}

	/** The dimension being played, as the fleet keys its columns. */
	private static String dimension() {
		return "minecraft:" + MinecraftClient.getInstance().world.dimension.getDimensionType().getName();
	}

	private static JsonObject gotoLook(int x, int y, int z) {
		final JsonObject a = new JsonObject();
		a.addProperty("action", "goto");
		a.add("look", position(x, y, z));
		return a;
	}

	private static JsonObject position(int x, int y, int z) {
		final JsonObject out = new JsonObject();
		out.addProperty("x", x);
		out.addProperty("y", y);
		out.addProperty("z", z);
		return out;
	}

	private static String at(int x, int y, int z) {
		return x + "," + y + "," + z;
	}

	private void send(String bot, Plan plan, JsonObject a) {
		plan.starting = true;
		plan.sent = a.get("action").getAsString();
		bots.actionInternal(bot, a.toString());
	}

	@Override
	public void onAction(String bot, String json, boolean internal) {
		if (internal) {
			return;
		}

		errors.remove(bot);
		takeOver(bot);
	}

	/** The bot's quarrify going on, if any, over: an order from outside takes the bot over. */
	private void takeOver(String bot) {
		final Plan plan = plans.remove(bot);

		if (plan != null) {
			plan.listener.failed();
		}
	}

	private void fail(String bot, Plan plan, JobRunner.Listener listener, String message) {
		if (plan != null && !plans.remove(bot, plan)) {
			// Failed already, or taken over: the first end stands.
			return;
		}

		errors.put(bot, message);
		listener.failed();
	}
}

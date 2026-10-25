package net.mapmcbot.client;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
import net.minecraft.block.Blocks;
import net.minecraft.block.FallingBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.item.ItemStack;

/**
 * A quarrify: bots dig out an area, a plan ({ action: quarrify, area: its id }) the mod resolves into
 * primitives, each sent as the one before ends, by the bot's state: nothing timed. The digs of a layer
 * are planned ahead too, the bot holding them after the one it runs (see ahead).
 *
 * Done for now only for areas wider and deeper than 5 (the rest fail, not done yet): a region with a
 * staircase in each corner, its 2x2, and an active layer, shared by the bots on it. The layers are
 * slabs of LAYER blocks high from the area's top (the lowest one what is left); the active layer is the
 * highest with something to dig (the staircases' steps and the bots' supports left out: the rest of a
 * staircase is dug with its layer, by whichever bot is nearest); once it is empty it goes down to the
 * next with something, never up: a block placed over it is left there. Each time a bot goes off
 * a layer it takes the nearest staircase to where it got to, the ones the others took counting as
 * farther (see nearest), shared with the others there: a block of it one of them is on, it waits for.
 *
 * A bot walks first to the nearest spot the pathfinder finds in the area or a block round it, at any
 * height from its bottom to standing on its top. From there it goes down (or up) its staircase,
 * carving and building it as it goes (break_wsrp and place_wsrp, ending each on the step it goes on to),
 * to stand on the active layer's floor: its feet in the layer's bottom. Nothing under that floor is
 * dug while the layer is active, the staircase's step under it made solid: the floor is what the ends of
 * the others' digs stand on. There it digs the layer with break_wsrpa, ending where it is with the block
 * in reach, else 1 from it (see stand), of the blocks of the least depth (see depth) the nearest to it
 * and farthest from the others (see pick), one no other bot is digging. With none left but the ones
 * other bots dig, it waits on the layer for theirs. The layer empty, it goes down its staircase to the next.
 *
 * A staircase is a spiral down its 2x2, one step a layer, each the next column round from the one over
 * it, so each column has a step every 4 layers with 3 of air over it. A step that is not solid is placed
 * against a solid neighbour; with none, on supports placed along its column (the shortest way, up or
 * down, in the area) up to one that has, broken once the step stands. One that falls (sand, gravel) is
 * dug and placed again. The steps are left once it is done.
 *
 * A liquid (water, lava) is filled, a block placed in it, and dug after as any other; a block that holds
 * one back (the liquid over it or to a side) is left till then, so none ever flows. Filled from the floor,
 * seen over its side or into it by the air over it, a layer with a liquid has its bottom at the highest
 * one: a pocket is filled a height at a time from its top, the block over it dug first.
 *
 * The blocks of a layer off the floor the bot walks on (islands of the area's, apart by air) are bridged
 * to: a block placed in the floor (under the layer's bottom, in the area) at a time toward the nearest spot
 * that sees one, dug with the next layer.
 */
final class Quarrify implements JobRunner.Resolver, BotRegistry.StateListener, BotRegistry.ActionListener {
	/** The narrowest an area with four staircases is, along x and along z: one past two 2x2 and a column between. */
	private static final int CORNERS_MIN = 6;
	/** How many primitives a bot holds at once, the running one among them (actions.js QUEUE): the rest planned ahead (see ahead). */
	private static final int QUEUE = 3;
	/** How high a layer is, in blocks: dug standing on its floor. */
	private static final int LAYER = 4;
	/** The break's deadline when its dig takes less than half of it (actions.js DEFAULT_DEADLINE), in ms. */
	private static final int DEADLINE = 5000;
	/** How high the eyes are over the feet. */
	private static final double EYES = 1.62;
	/** Half the width of a player's hitbox. */
	private static final double HALF_WIDTH = 0.3;
	/** How high a player's hitbox is. */
	private static final double HEIGHT = 1.8;
	/** How far from the block of the layer it digs the bot tries to end its break_wsrpa when out of reach of it, in blocks. */
	private static final int REACH_END = 1;
	/** How far the eyes reach a block (actions.js REACH_BLOCK). */
	private static final double REACH = 4.5;
	/** The first slot of the inventory (window numbering): the tool a break holds is any of it, hotbar included. */
	private static final int INVENTORY_FIRST = 9;
	private static final int INVENTORY_LAST = 44;

	private static final int[][] FACES = {{0, -1, 0}, {0, 1, 0}, {1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}};
	/** The faces a liquid comes in by once the block is gone: over it and its sides (none flows up). */
	private static final int[][] INFLOWS = {{0, 1, 0}, {1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}};
	/** How far along x and z from a liquid at the layer's bottom the eyes on the floor see into it over the blocks next to it, in blocks. */
	private static final int FILL_SEEN = 2;
	/** What pick hands back to do at its block, its 4th: dig it, fill it (a liquid, a block placed in it), or a bridge (a block placed in the floor). */
	private static final int DIG = 0;
	private static final int FILL = 1;
	private static final int BRIDGE = 2;
	/** No liquid next to the block (see dam). */
	private static final int[] NONE = new int[0];

	/** An area being quarrified: its active layer and staircases, shared by its bots. */
	private static final class Region {
		final Area area;
		/** Each corner's staircase, its 2x2 as {x, z}, gone round: the step of the top layer is the first. */
		final int[][][] stairs;
		/** The bots on the region. */
		final Set<String> bots = new LinkedHashSet<String>();
		/** The bottom of the layer being dug, where the feet go; under the area's bottom, done. */
		int active;
		/** The top of the layer being dug. */
		int top;
		/** Whether active was raised to the layer's highest liquid (see active): set once a layer. */
		boolean sized;
		/** The bridges placed (see bridge), by key(): the way back to the staircases of the bots still over the layer. */
		final Set<Long> bridges = new HashSet<Long>();
		/** The blocks each bot is digging or placing, by key(): one a primitive sent (see Sent), none left empty. */
		final Map<String, Set<Long>> claims = new HashMap<String, Set<Long>>();
		/** The bots with nothing to do but what the others are on, until a primitive of theirs ends. */
		final Set<String> waiting = new LinkedHashSet<String>();
		/** The blocks of the active layer at the last pick on it with their depth (see depth), {x, y, z, depth}, drawn on them. */
		List<int[]> depths = new ArrayList<int[]>();

		Region(Area area) {
			this.area = area;
			final int w = area.getMinX();
			final int e = area.getMaxX();
			final int n = area.getMinZ();
			final int s = area.getMaxZ();
			// Each round the same way (x, then z, then back), from the corner.
			stairs = new int[][][] {
				{{e, n}, {e - 1, n}, {e - 1, n + 1}, {e, n + 1}},
				{{w, n}, {w, n + 1}, {w + 1, n + 1}, {w + 1, n}},
				{{w, s}, {w + 1, s}, {w + 1, s - 1}, {w, s - 1}},
				{{e, s}, {e, s - 1}, {e - 1, s - 1}, {e - 1, s}}};
			top = area.getMaxY();
			active = Math.max(top - LAYER + 1, area.getMinY());
		}

		/** Whether the column is of a staircase's 2x2. */
		boolean inStair(int x, int z) {
			for (int[][] stair : stairs) {
				for (int[] cell : stair) {
					if (cell[0] == x && cell[1] == z) {
						return true;
					}
				}
			}

			return false;
		}

		/** Whether a bot of the region is digging or placing at the block, by key(). */
		boolean claimed(long key) {
			for (Set<Long> blocks : claims.values()) {
				if (blocks.contains(key)) {
					return true;
				}
			}

			return false;
		}

		/** The bot's claim of the primitive sent with it over. */
		void unclaim(String bot, Long key) {
			final Set<Long> blocks = claims.get(bot);

			if (key == null || blocks == null) {
				return;
			}

			blocks.remove(key);

			if (blocks.isEmpty()) {
				claims.remove(bot);
			}
		}

		/** Whether the block is the layer's step of a staircase. */
		boolean step(int x, int y, int z) {
			for (int[][] stair : stairs) {
				final int[] cell = stair[stepIndex(area, y)];

				if (cell[0] == x && cell[1] == z) {
					return true;
				}
			}

			return false;
		}
	}

	/** A primitive sent to the bot and not over yet: the one it runs, or one held after it (see actions.js run). */
	private static final class Sent {
		/** The block it walks the bot to (its end), {x, y, z}; null with none. */
		final int[] end;
		/** The block it breaks or places, {x, y, z}; null with none. */
		final int[] target;
		/** The block claimed for it (see claim), by key(); null with none. */
		final Long claim;
		/** Whether it is a dig of the layer picked (see next): the next is planned ahead behind it (see ahead). */
		final boolean layer;

		Sent(int[] end, int[] target, Long claim, boolean layer) {
			this.end = end;
			this.target = target;
			this.claim = claim;
			this.layer = layer;
		}
	}

	private static final class Plan {
		final Area area;
		final JobRunner.Listener listener;
		Region region;
		/** Its staircase, of the region's (shared with the others on it); -1 while it walks to the area. */
		int corner = -1;
		/** Whether it dug on a layer since it took its staircase: off it, it takes the nearest again. */
		boolean dugLayer;
		/** The primitives left of the block it is on, in order. */
		final Deque<JsonObject> queue = new ArrayDeque<JsonObject>();
		/** The block its feet are in, as its last state told. */
		int[] feet;
		/** Its feet as they are, as its last state told: anywhere in that block. */
		double[] position;
		/** The supports placed under or over a step, to break once it stands: {x, y, z, the step's y}. */
		final List<int[]> supports = new ArrayList<int[]>();
		int dug;
		/** What it is doing, for the status. */
		String doing = "walk";
		/** A primitive sent and not yet seen going: an idle before that is the end of the one before it, not of this. */
		boolean starting;
		/** The primitives sent and not over yet, in order: the first the one the bot runs. */
		final Deque<Sent> sent = new ArrayDeque<Sent>();
		/** The block claimed for the primitive sent next, by key(); null with none. */
		Long claiming;
		/** Whether the primitive sent next is a dig of the layer picked (see Sent.layer). */
		boolean picked;
		/**
		 * A stop sent after a failure with more sent behind it: the ones of those that got to the bot after
		 * it failed run then, none of them the plan's any more. Their states are left till the stop's.
		 */
		boolean draining;
		/** Planning ahead (see ahead): its feet in the last one sent's end, the blocks of those sent gone. */
		boolean ahead;
		/** The end or target its last primitive missed and was picked again from (see onState); null after one that ended. */
		int[] unreached;
		/** The blocks of the active layer its last pick had to choose from, drawn green. */
		List<int[]> eligible = new ArrayList<int[]>();

		Plan(Area area, JobRunner.Listener listener) {
			this.area = area;
			this.listener = listener;
		}
	}

	private final BotRegistry bots;
	private final ChunkRegistry chunks;
	private final Map<String, Plan> plans = new HashMap<String, Plan>();
	/** The regions being quarrified, by area id; gone once no bot is on one. */
	private final Map<String, Region> regions = new HashMap<String, Region>();
	/** Why each bot's last quarrify failed, until its next. */
	private final Map<String, String> errors = new HashMap<String, String>();

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

	/** The blocks each quarrify going on had to choose from at its last pick (see pick), each once, to draw. */
	synchronized List<int[]> eligible() {
		final Map<Long, int[]> out = new LinkedHashMap<Long, int[]>();

		for (Plan plan : plans.values()) {
			for (int[] cell : plan.eligible) {
				out.put(key(cell[0], cell[1], cell[2]), cell);
			}
		}

		return new ArrayList<int[]>(out.values());
	}

	/** The blocks of each region's active layer at the last pick on it with their depth, {x, y, z, depth}, to draw. */
	synchronized List<int[]> depths() {
		final List<int[]> out = new ArrayList<int[]>();

		for (Region region : regions.values()) {
			out.addAll(region.depths);
		}

		return out;
	}

	/** What the bot's quarrify is doing, or why it failed; null with none. */
	synchronized String status(String bot) {
		final Plan plan = plans.get(bot);

		if (plan != null) {
			final String layer = plan.region == null ? "" : ", layer " + plan.region.active;
			return "quarrify " + plan.area.getName() + layer + ", " + plan.dug + " dug, " + plan.doing;
		}

		return errors.containsKey(bot) ? "error " + errors.get(bot) : null;
	}

	@Override
	public synchronized void start(String bot, String json, JobRunner.Listener listener) {
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

		if (area.getWidth() < CORNERS_MIN || area.getDepth() < CORNERS_MIN) {
			// TODO the areas of 5x5 or less: fewer staircases, or none.
			fail(bot, null, listener, "quarrify: an area of 5x5 or less is not done yet (" + area.getName() + " is " + area.getWidth() + "x" + area.getDepth() + "x" + area.getHeight() + ")");
			return;
		}

		final Plan plan = new Plan(area, listener);
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
	public synchronized void onState(String bot, BotStatus status) {
		final Plan plan = plans.get(bot);

		if (plan == null) {
			return;
		}

		if (status.hasFeet()) {
			plan.feet = status.getFeet();
			plan.position = status.getPosition();
		}

		if (status.getKind() == BotStatus.Kind.DOING) {
			if (plan.draining && !status.getPrimitive().equals("stop")) {
				return;
			}

			plan.draining = false;
			plan.starting = false;
			return;
		}

		if (plan.draining || plan.starting && status.getKind() == BotStatus.Kind.IDLE) {
			return;
		}

		// The one that ended, the first sent: the bot runs them in order.
		final Sent head = plan.sent.peek();
		final int[] headEnd = head == null ? null : head.end;
		final int[] headTarget = head == null ? null : head.target;

		// A break whose block is gone by the time it starts (sand or gravel falling through it, seen there
		// on the way): nothing to break, picked again from the world as it is. Any other error fails it.
		final String message = status.getMessage();
		boolean gone = status.getKind() == BotStatus.Kind.ERROR && status.getPrimitive().startsWith("break_") && (message.startsWith("expected a block at ") && message.endsWith(", found air") || message.startsWith("block at ") && message.contains(" is already \"minecraft:air\""));

		// No path to its end any more (sand or gravel fallen into it, or on the way): picked again too,
		// once an end; the same end twice in a row, failed.
		// Its target hidden on the way (sand or gravel fallen in front of it): the same, once a target.
		final int[] missed = status.getKind() != BotStatus.Kind.ERROR ? null
				: headEnd != null && message.startsWith("expected a path to " + at(headEnd[0], headEnd[1], headEnd[2]) + ", found none") ? headEnd
				: headTarget != null && status.getPrimitive().startsWith("break_") && message.contains(" at " + at(headTarget[0], headTarget[1], headTarget[2]) + " in reach and in sight, found ") && message.contains(" hidden by ") ? headTarget
				// No spot seeing it (the one there is another bot's, or the world changed on the way).
				: headTarget != null && message.startsWith("expected a spot a walk of ") && message.contains(" at " + at(headTarget[0], headTarget[1], headTarget[2]) + " in reach and in sight, found none") ? headTarget
				: headTarget != null && message.startsWith("expected a spot a walk of ") && message.contains(" next to " + at(headTarget[0], headTarget[1], headTarget[2]) + " to place against in reach and in sight, found none") ? headTarget
				// A place the server took nothing of: something in the spot then (a falling block, a bot).
				: headTarget != null && status.getPrimitive().startsWith("place_") && message.startsWith("Server refused to place ") && message.contains("(" + headTarget[0] + ", " + headTarget[1] + ", " + headTarget[2] + ")") ? headTarget
				: null;

		if (missed != null) {
			gone = plan.unreached == null || !at(plan.unreached, missed);
			plan.unreached = missed.clone();
		} else if (status.getKind() == BotStatus.Kind.IDLE) {
			plan.unreached = null;
		}

		final Region region = plan.region;
		plan.sent.poll();

		if (region != null && head != null) {
			region.unclaim(bot, head.claim);
		}

		// Failed, the bot dropped the ones it held (see actions.js fail); the ones sent that got to it
		// after that run, stopped.
		final boolean behind = status.getKind() == BotStatus.Kind.ERROR && !plan.sent.isEmpty();

		if (status.getKind() == BotStatus.Kind.ERROR) {
			if (region != null) {
				region.claims.remove(bot);
			}

			plan.sent.clear();
		}

		if (gone) {
			System.out.println("[quarrify trace] " + bot + " " + status.getPrimitive() + ": " + status.getMessage() + ": picking again");
			plan.queue.clear();

			if (behind) {
				final JsonObject stop = new JsonObject();
				stop.addProperty("action", "stop");
				send(bot, plan, stop);
				plan.draining = true;
			}
		} else if (status.getKind() == BotStatus.Kind.ERROR) {
			if (behind) {
				bots.actionInternal(bot, "{\"action\":\"stop\"}");
			}

			fail(bot, plan, plan.listener, "quarrify: " + status.getPrimitive() + ": " + status.getMessage());
			return;
		}

		if (plan.feet == null) {
			fail(bot, plan, plan.listener, "quarrify: expected where " + bot + " stands with its state, found it not told (a fleet older than the mod?)");
			return;
		}

		if (plan.corner < 0) {
			arrive(bot, plan);
		}

		// Its primitive over: the bots waiting pick again, after it. Its dig opens the layer to them, its
		// walk down off the layer lets them have the bridges (see leftOut).
		final Region on = plan.region;
		advance(bot, plan);
		wake(on);
	}

	/** At the area: its region joined, the nearest staircase to it taken. */
	private void arrive(String bot, Plan plan) {
		Region region = regions.get(plan.area.getId());

		if (region == null) {
			region = new Region(plan.area);
			regions.put(plan.area.getId(), region);
		}

		plan.region = region;
		region.bots.add(bot);
		plan.corner = nearest(bot, region, plan.feet);
	}

	/**
	 * Of the region's staircases the ones the fewest other bots of it took, the nearest to the feet:
	 * the bots spread evenly over them, none bound to one.
	 */
	private int nearest(String bot, Region region, int[] feet) {
		int best = -1;
		int bestTaken = Integer.MAX_VALUE;
		double bestDistance = Double.MAX_VALUE;

		for (int i = 0; i < region.stairs.length; i++) {
			final int[] cell = region.stairs[i][0];
			int taken = 0;

			for (Map.Entry<String, Plan> other : plans.entrySet()) {
				final Plan them = other.getValue();

				if (!other.getKey().equals(bot) && them.region == region && them.corner == i) {
					taken++;
				}
			}

			final double distance = Math.hypot(cell[0] - feet[0], cell[1] - feet[2]);

			if (taken < bestTaken || taken == bestTaken && distance < bestDistance) {
				best = i;
				bestTaken = taken;
				bestDistance = distance;
			}
		}

		return best;
	}

	/** Whether the block is left out of the layers: a staircase's step, or a support of a bot's on the region. */
	private boolean leftOut(Region region, int x, int y, int z) {
		if (region.step(x, y, z)) {
			return true;
		}

		// A bridge, while a bot is still over the layer (an island it bridged to): its way to its staircase.
		if (region.bridges.contains(key(x, y, z))) {
			for (Plan plan : plans.values()) {
				if (plan.region == region && plan.feet != null && plan.feet[1] > region.active) {
					return true;
				}
			}
		}

		for (Plan plan : plans.values()) {
			if (plan.region == region && supports(plan, new int[] {x, y, z})) {
				return true;
			}
		}

		return false;
	}

	/** The bots waiting on the region's digs, each on to pick again. */
	private void wake(Region region) {
		final List<String> waiting = new ArrayList<String>(region.waiting);
		region.waiting.clear();

		for (String other : waiting) {
			final Plan plan = plans.get(other);

			if (plan != null && plan.region == region) {
				advance(other, plan);
			}
		}
	}

	/**
	 * The next primitive, worked out from the world as the ones sent before are over; the region dug,
	 * done. Then the ones planned ahead behind it (see ahead).
	 */
	private void advance(String bot, Plan plan) {
		if (plan.sent.isEmpty()) {
			if (plan.queue.isEmpty() && !next(bot, plan)) {
				return;
			}

			if (plan.queue.isEmpty()) {
				// Waiting, or done.
				return;
			}

			send(bot, plan, plan.queue.poll());
		}

		ahead(bot, plan);
	}

	/**
	 * The primitives after the one running planned ahead, QUEUE in all, the bot holding them: behind a
	 * dig of the layer with its end, the next dig of the layer picked as next would once those sent are
	 * over, their blocks gone (see state) and its feet in the last one's end, anywhere in it (see
	 * reaches). Anything else (a staircase, a fill, a bridge, a wait, the layer done, sand or gravel over
	 * a block sent) waits for them to end, planned from the world then.
	 */
	private void ahead(String bot, Plan plan) {
		while (plans.get(bot) == plan && !plan.draining && plan.queue.isEmpty() && plan.sent.size() < QUEUE) {
			final Sent last = plan.sent.peekLast();
			final Region region = plan.region;

			if (last == null || !last.layer || last.end == null || region == null || last.end[1] != region.active) {
				return;
			}

			// What falls into a block once dug is not known till it does.
			for (Sent one : plan.sent) {
				if (!one.layer) {
					continue;
				}

				final Boolean falls = falls(bot, plan, one.target[0], one.target[1] + 1, one.target[2]);

				if (falls == null || falls) {
					return;
				}
			}

			// The step under its staircase's next carved, as next has it first (see carve): solid, not one
			// that falls, no support of it left.
			final int below = region.active - 2;

			if (carvable(plan, below)) {
				final int[] column = region.stairs[plan.corner][stepIndex(plan.area, below)];
				final BlockState step = state(bot, plan, column[0], below, column[1]);

				if (step == null || !solid(step) || step.getBlock() instanceof FallingBlock) {
					return;
				}

				for (int[] support : plan.supports) {
					if (support[3] == below) {
						return;
					}
				}
			}

			final int[] feet = plan.feet;
			final double[] position = plan.position;
			int[] cell = null;
			int[] end = null;
			plan.feet = last.end.clone();
			// Its block's middle, for the ranking alone (see cost, stand): its reach from the worst of the block.
			plan.position = new double[] {last.end[0] + 0.5, last.end[1], last.end[2] + 0.5};
			plan.ahead = true;

			try {
				// On a floor of its own there (see footing, walkable): hanging over an edge, where it is held up from is not known.
				final BlockState under = state(bot, plan, last.end[0], last.end[1] - 1, last.end[2]);

				if (under == null || !solid(under) || supported(region, last.end[0], last.end[1] - 1, last.end[2])) {
					return;
				}

				final Choice here = choose(bot, plan, region.active, region.top);

				// Drawn as pick has them (see eligible, depths): the blocks sent gone.
				if (here != null) {
					plan.eligible = here.eligible;
					region.depths = here.depths;
				}

				if (here == null || here.best == null || !here.reached || here.best[3] != DIG) {
					return;
				}

				end = stand(bot, plan, here.best, region.active);

				if (end == null) {
					return;
				}

				cell = here.best;
			} finally {
				plan.feet = feet;
				plan.position = position;
				plan.ahead = false;
			}

			if (plans.get(bot) != plan) {
				return;
			}

			claim(region, plan, bot, key(cell[0], cell[1], cell[2]));
			plan.picked = true;

			if (!dig(bot, plan, cell, end, true)) {
				return;
			}

			send(bot, plan, plan.queue.poll());
		}
	}

	/** The plan's next primitive queued: to the active layer by its staircase, or a block of it. False when it failed or ended. */
	private boolean next(String bot, Plan plan) {
		final Region region = plan.region;
		final Integer active = active(bot, plan);

		if (active == null) {
			return false;
		}

		if (active < plan.area.getMinY()) {
			release(bot, plan);
			plans.remove(bot);
			plan.listener.done();
			return false;
		}

		final int layer = active;
		final int top = region.top;
		final int feet = plan.feet[1];

		if (feet != layer && plan.dugLayer) {
			// Off the layer: by the nearest staircase (see nearest), all carved with the layers.
			plan.corner = nearest(bot, region, plan.feet);
			plan.dugLayer = false;
		}

		if (feet > layer) {
			return down(bot, plan, layer);
		}

		if (feet < layer) {
			return up(bot, plan, layer);
		}

		// The step under its staircase's next first: a bridge out of the 2x2 goes over it (see bridge),
		// it seen from nowhere after.
		final Boolean step = carvable(plan, layer - 2) ? carve(bot, plan, layer - 2, null, -1, true) : Boolean.FALSE;

		if (step == null || step) {
			return step != null;
		}

		plan.dugLayer = true;
		final int[] cell = pick(bot, plan, layer, top);

		if (cell == null) {
			// Waiting on the others' digs, or failed.
			return plans.get(bot) == plan;
		}

		claim(region, plan, bot, key(cell[0], cell[1], cell[2]));

		if (cell[3] != DIG) {
			// Out of the staircases' 2x2: one there is carved with its layer, the staircase the way down.
			if (cell[3] == BRIDGE && !region.inStair(cell[0], cell[2])) {
				region.bridges.add(key(cell[0], cell[1], cell[2]));
			}

			plan.doing = (cell[3] == FILL ? "fill " : "bridge ") + at(cell[0], cell[1], cell[2]);
			return place(bot, plan, cell, null);
		}

		final int[] end = stand(bot, plan, cell, layer);
		plan.picked = true;
		return end != null && dig(bot, plan, cell, end, true);
	}

	/**
	 * The bottom of the region's active layer, gone down past the empty ones: the highest with something
	 * to dig (the steps and supports left out), never over the one it was. A layer with a liquid has its
	 * bottom at the highest of them: a liquid is filled from the floor alone, seen over its side from there
	 * (see fillable). Null when a state was missing (failed).
	 */
	private Integer active(String bot, Plan plan) {
		final Region region = plan.region;
		final Area area = plan.area;

		for (; region.top >= area.getMinY(); region.top = region.active - 1, region.active = Math.max(region.top - LAYER + 1, area.getMinY()), region.sized = false) {
			if (!region.sized) {
				for (int y = region.top; y > region.active; y--) {
					final Boolean liquid = liquidRow(bot, plan, y);

					if (liquid == null) {
						return null;
					}

					if (liquid) {
						region.active = y;
						break;
					}
				}

				region.sized = true;
			}

			for (int y = region.top; y >= region.active; y--) {
				for (int z = area.getMinZ(); z <= area.getMaxZ(); z++) {
					for (int x = area.getMinX(); x <= area.getMaxX(); x++) {
						if (leftOut(region, x, y, z)) {
							continue;
						}

						final BlockState state = state(bot, plan, x, y, z);

						if (state == null) {
							return null;
						}

						if (!air(state)) {
							return region.active;
						}
					}
				}
			}
		}

		return area.getMinY() - 1;
	}

	/** Whether the area has a liquid at the height, the steps and supports left out; null when a state was missing (failed). */
	private Boolean liquidRow(String bot, Plan plan, int y) {
		final Area area = plan.area;

		for (int z = area.getMinZ(); z <= area.getMaxZ(); z++) {
			for (int x = area.getMinX(); x <= area.getMaxX(); x++) {
				if (leftOut(plan.region, x, y, z)) {
					continue;
				}

				final BlockState state = state(bot, plan, x, y, z);

				if (state == null) {
					return null;
				}

				if (liquid(state)) {
					return true;
				}
			}
		}

		return false;
	}

	/**
	 * The block of the bot's layer (bottom..top) to work on, {x, y, z, what} (DIG, FILL or BRIDGE). Of
	 * the ones no other bot is on, a liquid it fills (see fillable) first, the nearest (as below); else
	 * of the ones not holding a liquid back (see dam), seen from the layer
	 * (see seen), the ones whose column (x, z: left while any block of it is) is of the least depth (see depth: the
	 * steps, the supports and the rest of the area count as gone, the ones other bots dig as there) are
	 * the ones to dig; of those the ones with a spot walked to
	 * round them first (see reached), then the one whose distance from the bot's eyes, less the nearest
	 * other bot's of the region, is the least: the bots spread over the layer. With none walked to round
	 * it, a block of the floor to bridge to the rest (see bridge). Null when it waits on the
	 * others' digs, or failed (a state missing, or nothing it
	 * can dig with every other bot of the region waiting: see stalled).
	 */
	private int[] pick(String bot, Plan plan, int bottom, int top) {
		final Region region = plan.region;
		final Area area = plan.area;
		final Choice here = choose(bot, plan, bottom, top);

		if (here == null) {
			return null;
		}

		plan.eligible = here.eligible;
		region.depths = here.depths;

		if (here.best != null && here.reached) {
			return here.best;
		}

		// None with a spot walked to round it: a bridge to the ones off the floor it walks on (another island of the area's).
		final int[] bridge = bridge(bot, plan, bottom, here);

		if (bridge != null || plans.get(bot) != plan) {
			return bridge;
		}

		// None to bridge to either: the others' digs open the floor to them, waited on; with none, from where it stands.
		if (here.best != null && region.claims.isEmpty()) {
			return here.best;
		}

		// No other bot digging, and every one waiting too: none of them ever opens the rest.
		if (region.claims.isEmpty() && stalled(bot, plan)) {
			if (here.held != null) {
				fail(bot, plan, plan.listener, "quarrify: expected the liquid next to " + at(here.held[0], here.held[1], here.held[2]) + " of the layer " + bottom + ".." + top + " of " + area.getName() + " to fill before digging it, found it seen from nowhere it walks to with a block next to it to place against (or out of the area), and every other bot of it waiting");
			} else if (here.sealed != null) {
				fail(bot, plan, plan.listener, "quarrify: expected a block of the layer " + bottom + ".." + top + " of " + area.getName() + " next to something not solid, to dig it from there, found them all sealed in or seen from nowhere it walks to (" + at(here.sealed[0], here.sealed[1], here.sealed[2]) + " one), and every other bot of it waiting");
			} else {
				fail(bot, plan, plan.listener, "quarrify: expected a block of the layer " + bottom + ".." + top + " of " + area.getName() + " to dig, or another bot of it digging or on its way, found none: " + here.left + " left, every bot waiting");
			}

			return null;
		}

		// The others' digs left: picked again as one ends, or as a bot leaves the region.
		region.waiting.add(bot);
		plan.doing = "waiting on the others' digs";
		return null;
	}

	/** What a layer has for a bot (see choose): the block to work on, {x, y, z, what}, and whether a spot walked to is round it, one sealed in, one held for a liquid, how many are left. */
	private static final class Choice {
		int[] best;
		boolean reached;
		int[] sealed;
		/** A block left for the liquid next to it (see dam), not filled yet. */
		int[] held;
		int left;
		List<int[]> eligible;
		List<int[]> depths;
		/** The blocks and liquids of the layer seen from nowhere it walks to, to bridge to (see bridge). */
		List<int[]> unseen;
		/** The spots of the layer it walks on (see walkable). */
		Set<Long> walkable;
	}

	/** The block of the layer (bottom..top) pick takes for the bot; null when a state was missing (failed). */
	private Choice choose(String bot, Plan plan, int bottom, int top) {
		final Region region = plan.region;
		final Area area = plan.area;
		final List<int[]> open = new ArrayList<int[]>();
		final List<int[]> fills = new ArrayList<int[]>();
		final List<int[]> unseen = new ArrayList<int[]>();
		final Set<Long> left = new HashSet<Long>();
		int[] sealed = null;
		int[] held = null;
		final Set<Long> walkable = walkable(bot, plan, bottom);

		if (walkable == null) {
			return null;
		}

		for (int y = top; y >= bottom; y--) {
			for (int z = area.getMinZ(); z <= area.getMaxZ(); z++) {
				for (int x = area.getMinX(); x <= area.getMaxX(); x++) {
					if (leftOut(region, x, y, z)) {
						continue;
					}

					final BlockState state = state(bot, plan, x, y, z);

					if (state == null) {
						return null;
					}

					if (air(state)) {
						continue;
					}

					left.add(key(x, y, z));

					if (region.claimed(key(x, y, z)) || standsOn(bot, region, x, y, z)) {
						continue;
					}

					if (liquid(state)) {
						final Boolean fillable = fillable(bot, plan, x, y, z, bottom, walkable);

						if (fillable == null) {
							return null;
						}

						(fillable ? fills : unseen).add(new int[] {x, y, z});
						continue;
					}

					final int[] dam = dam(bot, plan, x, y, z);

					if (dam == null) {
						return null;
					}

					// Dug, the liquid would come in: left till it is filled.
					if (dam != NONE) {
						held = new int[] {x, y, z};
						continue;
					}

					final Boolean seen = seen(bot, plan, x, y, z, bottom, walkable);

					if (seen == null) {
						return null;
					}

					if (!seen) {
						sealed = new int[] {x, y, z};
						unseen.add(sealed);
						continue;
					}

					open.add(new int[] {x, y, z});
				}
			}
		}

		// The liquids of the floor with the block over them dug: filled at once, a bot falls into the
		// hole otherwise (a pocket's next height, of the next layer). Seen into from the layer by that air.
		for (int z = area.getMinZ(); z <= area.getMaxZ() && bottom - 1 >= area.getMinY(); z++) {
			for (int x = area.getMinX(); x <= area.getMaxX(); x++) {
				final BlockState state = state(bot, plan, x, bottom - 1, z);
				final BlockState over = state == null ? null : state(bot, plan, x, bottom, z);

				if (over == null) {
					return null;
				}

				if (!liquid(state) || !air(over) || leftOut(region, x, bottom - 1, z) || region.claimed(key(x, bottom - 1, z))) {
					continue;
				}

				if (!against(bot, plan, x, bottom - 1, z)) {
					if (plans.get(bot) != plan) {
						return null;
					}

					continue;
				}

				boolean near = false;

				for (int dz = -FILL_SEEN; dz <= FILL_SEEN && !near; dz++) {
					for (int dx = -FILL_SEEN; dx <= FILL_SEEN && !near; dx++) {
						near = (dx != 0 || dz != 0) && walkable.contains(key(x + dx, bottom, z + dz));
					}
				}

				if (near) {
					fills.add(new int[] {x, bottom - 1, z});
				}
			}
		}

		// The depth by columns of the layer: one is left while any of its blocks is, so a column is dug
		// out its whole height before the ones next to it.
		final Set<Long> columns = new HashSet<Long>();

		for (long key : left) {
			columns.add(key(keyX(key), bottom, keyZ(key)));
		}

		final List<int[]> depths = new ArrayList<int[]>();
		int least = Integer.MAX_VALUE;

		for (long key : left) {
			final int x = keyX(key);
			final int y = (int) (key << 26 >> 52);
			final int z = keyZ(key);
			depths.add(new int[] {x, y, z, depth(columns, x, bottom, z)});
		}

		for (int[] cell : open) {
			least = Math.min(least, depth(columns, cell[0], bottom, cell[2]));
		}

		int[] best = null;
		boolean bestReached = false;
		double bestCost = Double.MAX_VALUE;
		final List<int[]> eligibles = new ArrayList<int[]>();

		// A liquid filled first: the blocks it holds back are dug after it.
		for (int[] cell : fills) {
			eligibles.add(cell);
			final double cost = cost(bot, plan, cell);

			if (cost < bestCost) {
				best = cell;
				bestReached = true;
				bestCost = cost;
			}
		}

		for (int[] cell : open) {
			if (best != null && fills.contains(best) || depth(columns, cell[0], bottom, cell[2]) != least) {
				continue;
			}

			eligibles.add(cell);
			final double cost = cost(bot, plan, cell);
			// The ones with a spot walked to round them first (see stand): the others end where the bot stands.
			final boolean reached = reached(walkable, bottom, cell);

			if (reached && !bestReached || reached == bestReached && cost < bestCost) {
				best = cell;
				bestReached = reached;
				bestCost = cost;
			}
		}

		final Choice out = new Choice();
		out.best = best == null ? null : new int[] {best[0], best[1], best[2], fills.contains(best) ? FILL : DIG};
		out.reached = bestReached;
		out.sealed = sealed;
		out.held = held;
		out.left = left.size();
		out.eligible = eligibles;
		out.depths = depths;
		out.unseen = unseen;
		out.walkable = walkable;
		return out;
	}

	/** The distance from the bot's eyes to the block's middle, less the nearest other bot's of the region: the bots spread over the layer. */
	private double cost(String bot, Plan plan, int[] cell) {
		final double ex = plan.position[0];
		final double ey = plan.position[1] + EYES;
		final double ez = plan.position[2];
		final double cx = cell[0] + 0.5;
		final double cy = cell[1] + 0.5;
		final double cz = cell[2] + 0.5;
		double others = Double.MAX_VALUE;

		for (Map.Entry<String, Plan> other : plans.entrySet()) {
			final double[] p = other.getValue().position;

			if (other.getKey().equals(bot) || other.getValue().region != plan.region || p == null) {
				continue;
			}

			others = Math.min(others, Math.sqrt((cx - p[0]) * (cx - p[0]) + (cy - p[1] - EYES) * (cy - p[1] - EYES) + (cz - p[2]) * (cz - p[2])));
		}

		return Math.sqrt((cx - ex) * (cx - ex) + (cy - ey) * (cy - ey) + (cz - ez) * (cz - ez)) - (others == Double.MAX_VALUE ? 0 : others);
	}

	/**
	 * Whether the liquid at the block is filled from where the bot walks on the layer's floor (walkable):
	 * it has a solid block next to it to place against, and a side of it is seen as a block's is (see
	 * seen), or, at the layer's bottom with air over it, a spot of walkable is FILL_SEEN or less from it
	 * along x and z: the eyes there are over the blocks round it, and see into it by that air. Null when
	 * a state was missing (failed).
	 */
	private Boolean fillable(String bot, Plan plan, int x, int y, int z, int bottom, Set<Long> walkable) {
		if (!against(bot, plan, x, y, z)) {
			return plans.get(bot) == plan ? Boolean.FALSE : null;
		}

		final Boolean seen = seen(bot, plan, x, y, z, bottom, walkable);

		if (seen == null || seen || y != bottom) {
			return seen == null ? null : seen;
		}

		final BlockState over = state(bot, plan, x, y + 1, z);

		if (over == null) {
			return null;
		}

		if (!air(over)) {
			return false;
		}

		for (int dz = -FILL_SEEN; dz <= FILL_SEEN; dz++) {
			for (int dx = -FILL_SEEN; dx <= FILL_SEEN; dx++) {
				if ((dx != 0 || dz != 0) && walkable.contains(key(x + dx, bottom, z + dz))) {
					return true;
				}
			}
		}

		return false;
	}

	/**
	 * The liquid the block holds back: next to it over it or to a side (see INFLOWS), the one that comes
	 * in once it is dug, in the area or not. NONE with none; null when a state was missing (failed).
	 */
	private int[] dam(String bot, Plan plan, int x, int y, int z) {
		for (int[] face : INFLOWS) {
			final BlockState there = state(bot, plan, x + face[0], y + face[1], z + face[2]);

			if (there == null) {
				return null;
			}

			if (liquid(there)) {
				return new int[] {x + face[0], y + face[1], z + face[2]};
			}
		}

		return NONE;
	}

	/**
	 * The block of the floor (under the layer's bottom, in the area) to place for the bot to walk on
	 * toward the nearest spot of the layer that would see one of here.unseen: a column next to it to a
	 * side, not solid up to its height; its own under it, over the eyes, not solid up to under it; a
	 * spot FILL_SEEN or less from a liquid of the bottom (see fillable). Out from the spots it walks on
	 * (here.walkable), by the ones with air at the feet and the head not solid, in the area and out of
	 * the staircases' 2x2: the first of those on the way, its floor not solid, gets it. {x, y, z, BRIDGE};
	 * null with none (or another bot's), or when a state was missing (failed).
	 */
	private int[] bridge(String bot, Plan plan, int bottom, Choice here) {
		final Region region = plan.region;
		final Area area = plan.area;
		final Set<Long> targets = new HashSet<Long>();

		for (int[] cell : here.unseen) {
			final int x = cell[0];
			final int y = cell[1];
			final int z = cell[2];
			final BlockState state = state(bot, plan, x, y, z);

			if (state == null) {
				return null;
			}

			if (liquid(state)) {
				if (y == bottom) {
					for (int dz = -FILL_SEEN; dz <= FILL_SEEN; dz++) {
						for (int dx = -FILL_SEEN; dx <= FILL_SEEN; dx++) {
							if (dx != 0 || dz != 0) {
								targets.add(key(x + dx, bottom, z + dz));
							}
						}
					}
				}

				continue;
			}

			for (int[] face : FACES) {
				if (face[1] != 0) {
					continue;
				}

				final Boolean clear = clear(bot, plan, x + face[0], z + face[2], bottom, y);

				if (clear == null) {
					return null;
				}

				if (clear) {
					targets.add(key(x + face[0], bottom, z + face[2]));
				}
			}

			if (y >= bottom + EYES) {
				final Boolean clear = clear(bot, plan, x, z, bottom, y - 1);

				if (clear == null) {
					return null;
				}

				if (clear) {
					targets.add(key(x, bottom, z));
				}
			}
		}

		targets.removeAll(here.walkable);
		System.out.println("[quarrify trace] " + bot + " bridge: " + here.unseen.size() + " unseen, " + targets.size() + " targets, " + here.walkable.size() + " walkable at " + bottom);

		if (targets.isEmpty()) {
			return null;
		}

		// Each spot's the one before it on the way; the ones it walks on their own.
		final Map<Long, Long> before = new HashMap<Long, Long>();
		final Deque<Long> next = new ArrayDeque<Long>();

		for (long key : here.walkable) {
			before.put(key, key);
			next.add(key);
		}

		while (!next.isEmpty()) {
			final long at = next.poll();

			if (targets.contains(at)) {
				// The way there from the spots it walks on, those first.
				final List<Long> way = new ArrayList<Long>();

				for (long cell = at; !here.walkable.contains(cell); cell = before.get(cell)) {
					way.add(0, cell);
				}

				// The first of it with its floor not solid: the ones before it walked on (the world changes
				// as the bots dig, one dug since walkable was worked out among them).
				for (long cell : way) {
					final int[] floor = {keyX(cell), bottom - 1, keyZ(cell), BRIDGE};
					final BlockState under = state(bot, plan, floor[0], floor[1], floor[2]);

					if (under == null) {
						return null;
					}

					if (solid(under)) {
						continue;
					}

					// Placed from the spot before it on the way, the one block it is seen from (by the side of the
					// floor there): with another bot in it, that one gone first (the sight search takes no spot
					// another player is in).
					final long from = before.get(cell);

					// Under the area its bottom layer's floor is not the area's to place in; another bot's, waited on.
					if (floor[1] < area.getMinY() || region.claimed(key(floor[0], floor[1], floor[2])) || occupied(bot, region, keyX(from), bottom, keyZ(from))) {
						System.out.println("[quarrify trace] " + bot + " bridge: " + at(floor[0], floor[1], floor[2]) + " under the area or claimed");
						return null;
					}

					return floor;
				}

				System.out.println("[quarrify trace] " + bot + " bridge: the way to " + at(keyX(at), bottom, keyZ(at)) + " walked on all of it");
				return null;
			}

			for (int[] face : FACES) {
				final int x = keyX(at) + face[0];
				final int z = keyZ(at) + face[2];
				final long key = key(x, bottom, z);

				// Through the staircases' 2x2 too: the corner one stands in has no other way out in the area. A
				// floor there is over its next step, put first (see next), and carved with its layer.
				if (face[1] != 0 || before.containsKey(key) || !area.contains(x, bottom, z)) {
					continue;
				}

				final BlockState feet = state(bot, plan, x, bottom, z);
				final BlockState head = feet == null ? null : state(bot, plan, x, bottom + 1, z);

				if (head == null) {
					return null;
				}

				if (!air(feet) || solid(head)) {
					continue;
				}

				before.put(key, at);
				next.add(key);
			}
		}

		System.out.println("[quarrify trace] " + bot + " bridge: no way to the targets, " + before.size() + " spots gone through");
		return null;
	}

	/** Whether the column, in the area and out of the staircases' 2x2, is not solid from the bottom up to the height; null when a state was missing (failed). */
	private Boolean clear(String bot, Plan plan, int x, int z, int bottom, int to) {
		if (!plan.area.contains(x, bottom, z) || plan.region.inStair(x, z)) {
			return false;
		}

		for (int h = bottom; h <= to; h++) {
			final BlockState there = state(bot, plan, x, h, z);

			if (there == null) {
				return null;
			}

			if (solid(there)) {
				return false;
			}
		}

		return true;
	}

	/**
	 * Whether every other bot quarrifying the plan's area waits on the others' digs (see wake): none of
	 * them has a primitive going, which is what wakes a bot waiting. One still walking to it is on its way.
	 */
	private boolean stalled(String bot, Plan plan) {
		for (Map.Entry<String, Plan> other : plans.entrySet()) {
			final Plan them = other.getValue();

			if (!other.getKey().equals(bot) && them.area.getId().equals(plan.area.getId()) && !plan.region.waiting.contains(other.getKey())) {
				return false;
			}
		}

		return true;
	}

	/**
	 * Whether the layer's block (its bottom at bottom) is seen from where the bot walks on the layer's
	 * floor (walkable): in the area, the column next to it to a side a spot of walkable and not solid
	 * up to its height, or, over the eyes on the floor, its own column one and not solid up to under it;
	 * over it is seen from nowhere on the floor. Null when a state was missing (failed).
	 */
	private Boolean seen(String bot, Plan plan, int x, int y, int z, int bottom, Set<Long> walkable) {
		for (int[] face : FACES) {
			// Its bottom face is seen from under it alone: the block over the eyes on the floor.
			if (face[1] > 0 || face[1] < 0 && y < bottom + EYES || !plan.area.contains(x + face[0], y + face[1], z + face[2])) {
				continue;
			}

			// Just over the bottom, over the block of the bottom next to it (under the eyes): from the column past that one.
			if (face[1] == 0 && y == bottom + 1) {
				final Boolean over = over(bot, plan, x, y, z, face, bottom, walkable);

				if (over == null || over) {
					return over;
				}
			}

			// Seen from there standing in that column, walked to.
			if (!walkable.contains(key(x + face[0], bottom, z + face[2]))) {
				continue;
			}

			final int from = bottom;
			final int to = face[1] < 0 ? y - 1 : y;
			boolean clear = true;

			for (int h = from; h <= to && clear; h++) {
				final BlockState there = state(bot, plan, x + face[0], h, z + face[2]);

				if (there == null) {
					return null;
				}

				clear = !solid(there);
			}

			if (clear) {
				return true;
			}
		}

		return false;
	}

	/**
	 * Whether the block, one over the layer's bottom, is seen by its side to the face from the column two
	 * out that way: the block next to it there not solid, the one under that what it may be (its top
	 * under the eyes on the floor), the column two out walkable and not solid up to the block's height.
	 * Null when a state was missing (failed).
	 */
	private Boolean over(String bot, Plan plan, int x, int y, int z, int[] face, int bottom, Set<Long> walkable) {
		final int nx = x + face[0];
		final int nz = z + face[2];
		final int fx = x + 2 * face[0];
		final int fz = z + 2 * face[2];

		if (!plan.area.contains(fx, bottom, fz) || !walkable.contains(key(fx, bottom, fz))) {
			return false;
		}

		final BlockState next = state(bot, plan, nx, y, nz);

		if (next == null) {
			return null;
		}

		if (solid(next)) {
			return false;
		}

		for (int h = bottom; h <= y; h++) {
			final BlockState there = state(bot, plan, fx, h, fz);

			if (there == null) {
				return null;
			}

			if (solid(there)) {
				return false;
			}
		}

		return true;
	}

	/**
	 * How far the block is from being a local minimum or maximum of the layer's in x or in z: of each
	 * axis the blocks of the layer's left between it and the nearest gone one, the fewer of the two.
	 */
	private static int depth(Set<Long> left, int x, int y, int z) {
		return Math.min(Math.min(run(left, x, y, z, -1, 0), run(left, x, y, z, 1, 0)),
				Math.min(run(left, x, y, z, 0, -1), run(left, x, y, z, 0, 1)));
	}

	/** The blocks of the layer's left past the block along the way, up to the first gone one. */
	private static int run(Set<Long> left, int x, int y, int z, int dx, int dz) {
		int n = 0;

		while (left.contains(key(x + (n + 1) * dx, y, z + (n + 1) * dz))) {
			n++;
		}

		return n;
	}

	/**
	 * Where the bot ends the dig of the block of its layer (bottom y): where it stands with the block in reach (REACH from its eyes) and no other bot's (see taken); else of the spots of the layer it stands on (air at
	 * its feet and head, solid under them) round the block, walked to from its feet (see walkable), out of
	 * the staircases' 2x2 but on their steps, and no other bot's, the one nearest to REACH_END away from
	 * it, the nearest to the bot of those; with none, where it stands.
	 * Null when a state was missing (failed).
	 */
	private int[] stand(String bot, Plan plan, int[] cell, int y) {
		final Set<Long> walkable = walkable(bot, plan, y);

		if (walkable == null) {
			return null;
		}

		final int[] footing = footing(bot, plan, walkable, y);

		if (footing == null) {
			return null;
		}

		// In reach from where it is: it digs it from there.
		if (plan.feet[1] == y && reaches(plan, cell) && !taken(bot, plan.region, footing[0], y, footing[2])) {
			return footing;
		}

		int[] best = null;
		double bestOff = Double.MAX_VALUE;
		double bestBot = Double.MAX_VALUE;

		for (int dz = -REACH_END; dz <= REACH_END; dz++) {
			for (int dx = -REACH_END; dx <= REACH_END; dx++) {
				final int x = cell[0] + dx;
				final int z = cell[2] + dz;

				// In a staircase's 2x2 on what is not its step: carved with the staircase, the floor going.
				if (dx == 0 && dz == 0 || !walkable.contains(key(x, y, z)) || plan.region.inStair(x, z) && !plan.region.step(x, y - 1, z) || taken(bot, plan.region, x, y, z)) {
					continue;
				}

				final Boolean falls = falls(bot, plan, x, y + 2, z);

				if (falls == null) {
					return null;
				}

				// Sand or gravel over it: it comes down into it once what holds it up is dug.
				if (falls) {
					continue;
				}

				final double off = Math.abs(Math.sqrt(dx * dx + dz * dz) - REACH_END);
				final double bx = x + 0.5 - plan.position[0];
				final double bz = z + 0.5 - plan.position[2];
				final double toBot = bx * bx + bz * bz;

				if (off < bestOff || off == bestOff && toBot < bestBot) {
					best = new int[] {x, y, z};
					bestOff = off;
					bestBot = toBot;
				}
			}
		}

		return best == null ? footing : best;
	}

	/**
	 * Whether the block is in reach of the bot's eyes (REACH to its nearest point): from its position;
	 * planned ahead (see ahead), from anywhere in its feet's block, the worst of it.
	 */
	private static boolean reaches(Plan plan, int[] cell) {
		if (!plan.ahead) {
			return reaches(plan.position[0], plan.position[1] + EYES, plan.position[2], cell);
		}

		// The distance to a box grows the farther out on any line: its most over the block is at a corner.
		for (int dz = 0; dz <= 1; dz++) {
			for (int dx = 0; dx <= 1; dx++) {
				if (!reaches(plan.feet[0] + dx, plan.feet[1] + EYES, plan.feet[2] + dz, cell)) {
					return false;
				}
			}
		}

		return true;
	}

	private static boolean reaches(double ex, double ey, double ez, int[] cell) {
		final double nx = Math.max(cell[0], Math.min(ex, cell[0] + 1)) - ex;
		final double ny = Math.max(cell[1], Math.min(ey, cell[1] + 1)) - ey;
		final double nz = Math.max(cell[2], Math.min(ez, cell[2] + 1)) - ez;
		return nx * nx + ny * ny + nz * nz <= REACH * REACH;
	}

	/**
	 * Where the bot stands at the layer's bottom y: its feet's block with a solid floor under it, else
	 * (hanging over an edge by its hitbox) the spot of walkable nearest its feet, the one it is held up by;
	 * its feet's block with none. Null when a state was missing (failed).
	 */
	private int[] footing(String bot, Plan plan, Set<Long> walkable, int y) {
		final BlockState under = state(bot, plan, plan.feet[0], y - 1, plan.feet[2]);

		if (under == null) {
			return null;
		}

		if (solid(under) && !supported(plan.region, plan.feet[0], y - 1, plan.feet[2])) {
			return new int[] {plan.feet[0], y, plan.feet[2]};
		}

		int[] best = plan.feet.clone();
		double bestAway = Double.MAX_VALUE;

		for (long key : walkable) {
			final int x = keyX(key);
			final int z = keyZ(key);

			if (x == plan.feet[0] && z == plan.feet[2]) {
				continue;
			}

			final double dx = x + 0.5 - plan.position[0];
			final double dz = z + 0.5 - plan.position[2];

			if (dx * dx + dz * dz < bestAway) {
				best = new int[] {x, y, z};
				bestAway = dx * dx + dz * dz;
			}
		}

		return best;
	}

	/** Whether the column is one of the staircase's 2x2. */
	private static boolean in(int[][] stair, int x, int z) {
		for (int[] cell : stair) {
			if (cell[0] == x && cell[1] == z) {
				return true;
			}
		}

		return false;
	}

	/** Whether a block that falls (sand, gravel) is in the column from the height up to the layer's top; null when a state was missing (failed). */
	private Boolean falls(String bot, Plan plan, int x, int from, int z) {
		for (int y = from; y <= plan.region.top; y++) {
			final BlockState there = state(bot, plan, x, y, z);

			if (there == null) {
				return null;
			}

			if (there.getBlock() instanceof FallingBlock) {
				return true;
			}
		}

		return false;
	}

	/** Whether another bot of the region has its feet in the block, or its primitive's end there: two pushing each other in one, neither gets to it. */
	private boolean taken(String bot, Region region, int x, int y, int z) {
		for (Map.Entry<String, Plan> other : plans.entrySet()) {
			final Plan them = other.getValue();

			if (other.getKey().equals(bot) || them.region != region) {
				continue;
			}

			if (them.feet != null && them.feet[0] == x && them.feet[1] == y && them.feet[2] == z) {
				return true;
			}

			if (endsAt(them, x, y, z)) {
				return true;
			}
		}

		return false;
	}

	/** Whether another bot of the region has its hitbox (HALF_WIDTH to each side of its position, its feet to its head) in the block or the one over it, or its primitive's end there. */
	private boolean occupied(String bot, Region region, int x, int y, int z) {
		for (Map.Entry<String, Plan> other : plans.entrySet()) {
			final double[] p = other.getValue().position;

			if (other.getKey().equals(bot) || other.getValue().region != region || p == null) {
				continue;
			}

			if (p[0] + HALF_WIDTH > x && p[0] - HALF_WIDTH < x + 1 && p[2] + HALF_WIDTH > z && p[2] - HALF_WIDTH < z + 1 && p[1] < y + 2 && p[1] + HEIGHT > y) {
				return true;
			}

			// On its way there: its state tells where it was, not where its primitives take it.
			if (endsAt(other.getValue(), x, y, z)) {
				return true;
			}

			// Placing the floor under it (a bridge), crouched over the edge of the one before into it.
			final Set<Long> claims = region.claims.get(other.getKey());

			if (claims != null && claims.contains(key(x, y - 1, z))) {
				return true;
			}
		}

		return false;
	}

	/** Whether a primitive the plan sent ends at the block. */
	private static boolean endsAt(Plan plan, int x, int y, int z) {
		for (Sent one : plan.sent) {
			if (one.end != null && one.end[0] == x && one.end[1] == y && one.end[2] == z) {
				return true;
			}
		}

		return false;
	}

	/** Whether a spot of walkable but the block's own is REACH_END or less from it along x and z, at the layer's bottom y. */
	private static boolean reached(Set<Long> walkable, int y, int[] cell) {
		for (int dz = -REACH_END; dz <= REACH_END; dz++) {
			for (int dx = -REACH_END; dx <= REACH_END; dx++) {
				if ((dx != 0 || dz != 0) && walkable.contains(key(cell[0] + dx, y, cell[2] + dz))) {
					return true;
				}
			}
		}

		return false;
	}

	/**
	 * The spots of its layer (bottom y) in the area the bot stands on (air at its feet and head, solid under them)
	 * walked to from its feet one side to the next, by key(); its feet's block among them. In a staircase's
	 * 2x2 too, the ones over what is not its step: the layer is seen and walked to from there as the bot
	 * gets to it, though they are no end to stand on (see stand). Null when a state was missing (failed).
	 */
	private Set<Long> walkable(String bot, Plan plan, int y) {
		final Set<Long> out = new HashSet<Long>();
		final Deque<int[]> next = new ArrayDeque<int[]>();
		final BlockState floor = state(bot, plan, plan.feet[0], y - 1, plan.feet[2]);

		if (floor == null) {
			return null;
		}

		if (solid(floor)) {
			out.add(key(plan.feet[0], y, plan.feet[2]));
			next.add(new int[] {plan.feet[0], plan.feet[2]});
		} else {
			// Over an edge (crouched, a place's pose): from the blocks its hitbox is over that hold it up.
			for (int x = (int) Math.floor(plan.position[0] - HALF_WIDTH); x <= (int) Math.floor(plan.position[0] + HALF_WIDTH); x++) {
				for (int z = (int) Math.floor(plan.position[2] - HALF_WIDTH); z <= (int) Math.floor(plan.position[2] + HALF_WIDTH); z++) {
					final BlockState under = state(bot, plan, x, y - 1, z);

					if (under == null) {
						return null;
					}

					if (solid(under)) {
						out.add(key(x, y, z));
						next.add(new int[] {x, z});
					}
				}
			}

			if (out.isEmpty()) {
				out.add(key(plan.feet[0], y, plan.feet[2]));
				next.add(new int[] {plan.feet[0], plan.feet[2]});
			}
		}

		while (!next.isEmpty()) {
			final int[] at = next.poll();

			for (int[] face : FACES) {
				final int x = at[0] + face[0];
				final int z = at[1] + face[2];

				if (face[1] != 0 || !plan.area.contains(x, y, z) || out.contains(key(x, y, z))) {
					continue;
				}

				final BlockState feet = state(bot, plan, x, y, z);
				final BlockState head = feet == null ? null : state(bot, plan, x, y + 1, z);
				final BlockState under = head == null ? null : state(bot, plan, x, y - 1, z);

				if (under == null) {
					return null;
				}

				// A support is no floor: it is broken once its step stands, whoever is on it.
				if (!air(feet) || solid(head) || !solid(under) || supported(plan.region, x, y - 1, z)) {
					continue;
				}

				out.add(key(x, y, z));
				next.add(new int[] {x, z});
			}
		}

		return out;
	}

	/**
	 * Down the staircase to stand with its feet in the layer's bottom: the layers round its feet carved,
	 * then on the step under them, then each layer under it carved, ending on its step, one a time; of
	 * the one under the layer's floor its step alone.
	 */
	private boolean down(String bot, Plan plan, int active) {
		final int feet = plan.feet[1];

		for (int y = feet + 1; y >= feet - 1; y--) {
			if (y >= active - 1 && carvable(plan, y)) {
				final Boolean queued = carve(bot, plan, y, null, -1, false);

				if (queued == null || queued) {
					return queued != null;
				}
			}
		}

		final int[] on = stepTop(plan, feet - 1);

		if (!at(plan.feet, on)) {
			// Off its staircase: into it at any of its steps from the active layer's floor up, the nearest the
			// walk finds (see into). Its top one may have no way to it any more (the 2x2 round it carved, the
			// layer round that dug), the others are a drop from the layer next to them.
			if (!in(plan.region.stairs[plan.corner], plan.feet[0], plan.feet[2])) {
				// The step under that one first when it stands and is carved, by the spot of the layer next
				// to its column, out of the 2x2, straight on from there: cut across (a walk to anywhere in the
				// 2x2), the head goes under the steps over it.
				final int[] under = stepTop(plan, feet - 2);

				if (carvable(plan, feet - 2)) {
					final BlockState step = state(bot, plan, under[0], under[1] - 1, under[2]);
					final BlockState at = step == null ? null : state(bot, plan, under[0], under[1], under[2]);
					final BlockState head = at == null ? null : state(bot, plan, under[0], under[1] + 1, under[2]);

					if (head == null) {
						return false;
					}

					if (solid(step) && air(at) && !solid(head)) {
						for (int[] face : FACES) {
							final int[] door = {under[0] + face[0], feet, under[2] + face[2]};

							if (face[1] != 0 || plan.region.inStair(door[0], door[2]) || !plan.area.contains(door[0], feet, door[2])) {
								continue;
							}

							final BlockState floor = state(bot, plan, door[0], feet - 1, door[2]);
							final BlockState in = floor == null ? null : state(bot, plan, door[0], feet, door[2]);
							final BlockState over = in == null ? null : state(bot, plan, door[0], feet + 1, door[2]);

							if (over == null) {
								return false;
							}

							if (solid(floor) && air(in) && !solid(over)) {
								return walk(bot, plan, at(plan.feet, door) ? under : door);
							}
						}
					}
				}

				return into(bot, plan, active);
			}

			return walk(bot, plan, on);
		}

		final int[] next = stepTop(plan, feet - 2);

		if (carvable(plan, feet - 2)) {
			// Under the active layer's floor its step alone: the rest is the floor the others' ends stand on.
			final Boolean queued = carve(bot, plan, feet - 2, next, -1, feet - 2 < active);

			if (queued == null || queued) {
				return queued != null;
			}
		}

		return walk(bot, plan, next);
	}

	/**
	 * Up the staircase to stand with its feet in the layer: the layers at its feet and head carved,
	 * then the one over those, ending on the step at its feet, one a time.
	 */
	private boolean up(String bot, Plan plan, int active) {
		final int feet = plan.feet[1];

		for (int y = feet; y <= feet + 1; y++) {
			if (carvable(plan, y)) {
				final Boolean queued = carve(bot, plan, y, null, -1, false);

				if (queued == null || queued) {
					return queued != null;
				}
			}
		}

		final int[] next = stepTop(plan, feet);

		if (carvable(plan, feet + 2)) {
			// The block over the step alone: the head's room to go on to it. The rest of that layer, its
			// step first, is carved from on the step: from here the eyes are under the top of a support
			// placed at the feet's height, the step seen from nowhere to place against it. The room over
			// where it jumps from too, when that is of the staircase. No end: dug from where it stands.
			final int[][] stair = plan.region.stairs[plan.corner];
			// The one it jumps from by the step it stands on, not its feet: those floor an x or z hanging
			// over the step's edge to the next column.
			final int[] column = stair[stepIndex(plan.area, feet)];
			final int[] from = stair[stepIndex(plan.area, feet - 1)];

			for (int[] cell : stair) {
				if ((cell[0] != column[0] || cell[1] != column[1]) && (cell[0] != from[0] || cell[1] != from[1])) {
					continue;
				}

				final int[] head = {cell[0], feet + 2, cell[1]};
				final BlockState there = state(bot, plan, head[0], head[1], head[2]);

				if (there == null) {
					return false;
				}

				if (liquid(there)) {
					plan.doing = "fill " + at(head[0], head[1], head[2]);
					return !claim(bot, plan, head) || place(bot, plan, head, null);
				}

				if (!air(there) && !leftOut(plan.region, head[0], head[1], head[2])) {
					return !claim(bot, plan, head) || dig(bot, plan, head, null);
				}
			}
		}

		return walk(bot, plan, next);
	}

	/** Whether the layer has a staircase to carve: it is in the area. */
	private static boolean carvable(Plan plan, int y) {
		return y >= plan.area.getMinY() && y <= plan.area.getMaxY();
	}

	/**
	 * The next primitive of the layer of its staircase queued: its supports, its step made solid, the
	 * supports broken after, then its three other blocks dug (the column first first, if any), the one
	 * the bot stands on last. With end, each a wsrp ending there; with stepOnly, its step and supports
	 * alone. True when one was queued, false with the layer carved, null when it failed.
	 */
	private Boolean carve(String bot, Plan plan, int y, int[] end, int first, boolean stepOnly) {
		final int[][] stair = plan.region.stairs[plan.corner];
		final int k = stepIndex(plan.area, y);
		final int[] step = {stair[k][0], y, stair[k][1]};
		final BlockState state = state(bot, plan, step[0], step[1], step[2]);

		if (state == null) {
			return null;
		}

		if (solid(state) && state.getBlock() instanceof FallingBlock) {
			final Boolean open = open(bot, plan, step[0], step[1], step[2]);

			if (open == null) {
				return null;
			}

			// It would fall once the block under it is dug: dug, placed again after, from the step over it
			// (see down) before going on to it. One a bot stands on is left (dug, it drops into it), and so
			// is one sealed in (sand fallen round it, a support over it): fallen, it is a step not solid, placed then.
			// By its hitbox, not its feet's block: hanging over an edge it is held up by the step next to it.
			final boolean on = onTop(plan.position, step[0], step[1], step[2]) || standsOn(bot, plan.region, step[0], step[1], step[2]);

			if (open && !on) {
				return !claim(bot, plan, step) || dig(bot, plan, step, end) ? Boolean.TRUE : null;
			}
		}

		if (!solid(state)) {
			return support(bot, plan, step, end) ? Boolean.TRUE : null;
		}

		// This layer's step's supports alone: one of another's is not this one's to break.
		for (int i = plan.supports.size() - 1; i >= 0; i--) {
			if (plan.supports.get(i)[3] != y) {
				continue;
			}

			final int[] support = plan.supports.get(i);
			final BlockState there = state(bot, plan, support[0], support[1], support[2]);

			if (there == null) {
				return null;
			}

			// Another bot on it: broken once it is off (see wake), it falls otherwise.
			if (!air(there) && standsOn(bot, plan.region, support[0], support[1], support[2])) {
				plan.region.waiting.add(bot);
				plan.doing = "waiting for the bots on the support " + at(support[0], support[1], support[2]);
				return Boolean.TRUE;
			}

			plan.supports.remove(i);

			if (!air(there)) {
				return !claim(bot, plan, support) || dig(bot, plan, support, end) ? Boolean.TRUE : null;
			}
		}

		if (stepOnly) {
			return Boolean.FALSE;
		}

		final List<int[]> cells = new ArrayList<int[]>();

		if (first >= 0 && first != k) {
			cells.add(new int[] {stair[first][0], y, stair[first][1]});
		}

		for (int i = 1; i < 4; i++) {
			final int c = (k + i) % 4;

			if (c != first) {
				cells.add(new int[] {stair[c][0], y, stair[c][1]});
			}
		}

		int[] under = null;
		int[] sealed = null;

		for (int[] cell : cells) {
			final BlockState there = state(bot, plan, cell[0], cell[1], cell[2]);

			if (there == null) {
				return null;
			}

			if (air(there) || leftOut(plan.region, cell[0], cell[1], cell[2])) {
				// A support of another layer's step stays till that step stands (see the supports above), another bot's too.
				continue;
			}

			// A liquid filled, dug after as a block; one it holds back filled first, else it comes down the staircase.
			final int[] dam = liquid(there) ? cell : dam(bot, plan, cell[0], cell[1], cell[2]);

			if (dam == null) {
				return null;
			}

			if (dam != NONE) {
				plan.doing = "fill " + at(dam[0], dam[1], dam[2]);
				return !claim(bot, plan, dam) || place(bot, plan, dam, end) ? Boolean.TRUE : null;
			}

			if (cell[0] == plan.feet[0] && cell[1] == plan.feet[1] - 1 && cell[2] == plan.feet[2]) {
				under = cell;
				continue;
			}

			final Boolean open = open(bot, plan, cell[0], cell[1], cell[2]);

			if (open == null) {
				return null;
			}

			// One sealed in is seen from nowhere: after the ones next to it, which open it.
			if (!open) {
				if (sealed == null) {
					sealed = cell;
				}

				continue;
			}

			return !claim(bot, plan, cell) || dig(bot, plan, cell, end) ? Boolean.TRUE : null;
		}

		if (under != null) {
			return !claim(bot, plan, under) || dig(bot, plan, under, end) ? Boolean.TRUE : null;
		}

		if (sealed != null) {
			fail(bot, plan, plan.listener, "quarrify: expected a block of the staircase at layer " + y + " next to something not solid, to dig it from there, found them all sealed in (" + at(sealed[0], sealed[1], sealed[2]) + " one)");
			return null;
		}

		return Boolean.FALSE;
	}

	/**
	 * The step placed against a solid neighbour; with none, a support next to it at its height with
	 * one; with none of those either, the farthest of its supports: the blocks of its column from it,
	 * up or down (the fewer, in the area), to the first with one. False when it failed.
	 */
	private boolean support(String bot, Plan plan, int[] step, int[] end) {
		if (against(bot, plan, step[0], step[1], step[2])) {
			plan.doing = "step " + at(step[0], step[1], step[2]);
			return !claim(bot, plan, step) || place(bot, plan, step, end);
		}

		if (plans.get(bot) != plan) {
			return false;
		}

		int[] best = null;

		// Next to it at its height first: the step goes against its side, seen from over it. One over
		// it is seen by its bottom face alone, from under it, where nothing stands (a staircase in the air).
		// Of those the ones with nothing solid over them first: one under the step the bot stands on is
		// seen from nowhere over it.
		for (int pass = 0; pass < 2 && best == null; pass++) {
			for (int[] face : FACES) {
				final int[] side = {step[0] + face[0], step[1], step[2] + face[2]};

				if (face[1] != 0 || !plan.area.contains(side[0], side[1], side[2])) {
					continue;
				}

				final BlockState there = state(bot, plan, side[0], side[1], side[2]);
				final BlockState over = there == null ? null : state(bot, plan, side[0], side[1] + 1, side[2]);

				if (over == null) {
					return false;
				}

				if (!solid(there) && (pass == 1 || !solid(over)) && against(bot, plan, side[0], side[1], side[2])) {
					best = side;
					break;
				}

				if (plans.get(bot) != plan) {
					return false;
				}
			}
		}

		final boolean beside = best != null;

		for (int way = -1; way <= 1 && !beside; way += 2) {
			for (int y = step[1] + way; y >= plan.area.getMinY() && y <= plan.area.getMaxY(); y += way) {
				if (best != null && Math.abs(y - step[1]) >= Math.abs(best[1] - step[1])) {
					break;
				}

				if (against(bot, plan, step[0], y, step[2])) {
					best = new int[] {step[0], y, step[2]};
					break;
				}

				if (plans.get(bot) != plan) {
					return false;
				}
			}
		}

		if (best == null) {
			fail(bot, plan, plan.listener, "quarrify: expected a solid block next to the step at " + at(step[0], step[1], step[2]) + " or to its column in the area, found none: the staircase would stand in the air");
			return false;
		}

		if (!claim(bot, plan, best)) {
			return true;
		}

		plan.supports.add(new int[] {best[0], best[1], best[2], step[1]});
		plan.doing = "support " + at(best[0], best[1], best[2]) + " for the step " + at(step[0], step[1], step[2]);
		// From where it stands: end is on the step, not there yet.
		return place(bot, plan, best, null);
	}

	/** Whether a support of a bot's on the region stands at the block. */
	private boolean supported(Region region, int x, int y, int z) {
		for (Plan plan : plans.values()) {
			if (plan.region == region && supports(plan, new int[] {x, y, z})) {
				return true;
			}
		}

		return false;
	}

	/** Whether a support of the plan's stands at the cell, to break once its step stands. */
	private static boolean supports(Plan plan, int[] cell) {
		for (int[] support : plan.supports) {
			if (support[0] == cell[0] && support[1] == cell[1] && support[2] == cell[2]) {
				return true;
			}
		}

		return false;
	}

	/**
	 * Whether another bot of the region stands on the block: its hitbox (HALF_WIDTH to each side of its
	 * position) over the block's column, its feet on the block's top or falling from it; or the block is
	 * under the end its primitive walks it to. Dug from under it, it drops off the path it follows.
	 */
	private boolean standsOn(String bot, Region region, int x, int y, int z) {
		for (Map.Entry<String, Plan> other : plans.entrySet()) {
			final double[] p = other.getValue().position;

			if (other.getKey().equals(bot) || other.getValue().region != region || p == null) {
				continue;
			}

			if (endsAt(other.getValue(), x, y + 1, z)) {
				return true;
			}

			if (onTop(p, x, y, z)) {
				return true;
			}
		}

		return false;
	}

	/** Whether feet at the position stand on the block: the hitbox (HALF_WIDTH to each side) over its column, the feet on its top or falling from it. */
	private static boolean onTop(double[] p, int x, int y, int z) {
		return (int) Math.ceil(p[1]) - 1 == y && p[0] + HALF_WIDTH > x && p[0] - HALF_WIDTH < x + 1 && p[2] + HALF_WIDTH > z && p[2] - HALF_WIDTH < z + 1;
	}

	/**
	 * The block taken for the bot's next primitive; false when another bot is on it, the bot waiting
	 * for that one's to end (see wake).
	 */
	private static boolean claim(String bot, Plan plan, int[] cell) {
		final Region region = plan.region;
		final long key = key(cell[0], cell[1], cell[2]);

		for (Map.Entry<String, Set<Long>> claim : region.claims.entrySet()) {
			if (claim.getValue().contains(key) && !claim.getKey().equals(bot)) {
				region.waiting.add(bot);
				plan.doing = "waiting on " + claim.getKey() + " at " + at(cell[0], cell[1], cell[2]);
				return false;
			}
		}

		claim(region, plan, bot, key);
		return true;
	}

	/** The block claimed for the primitive the bot is sent next (see Sent.claim). */
	private static void claim(Region region, Plan plan, String bot, long key) {
		Set<Long> blocks = region.claims.get(bot);

		if (blocks == null) {
			blocks = new HashSet<Long>();
			region.claims.put(bot, blocks);
		}

		blocks.add(key);
		plan.claiming = key;
	}

	/** Whether the block has something not solid next to it, to be seen from; null when a state was missing (failed). */
	private Boolean open(String bot, Plan plan, int x, int y, int z) {
		for (int[] face : FACES) {
			final BlockState there = state(bot, plan, x + face[0], y + face[1], z + face[2]);

			if (there == null) {
				return null;
			}

			if (!solid(there)) {
				return true;
			}
		}

		return false;
	}

	/** Whether the spot has a solid block next to it to place against; false too when a state was missing (failed). */
	private boolean against(String bot, Plan plan, int x, int y, int z) {
		for (int[] face : FACES) {
			final BlockState there = state(bot, plan, x + face[0], y + face[1], z + face[2]);

			if (there == null) {
				return false;
			}

			if (solid(there)) {
				return true;
			}
		}

		return false;
	}

	/** The top of the layer's step of the bot's staircase: where the feet go standing on it, {x, y, z}. */
	private static int[] stepTop(Plan plan, int y) {
		final int[] cell = plan.region.stairs[plan.corner][stepIndex(plan.area, y)];
		return new int[] {cell[0], y + 1, cell[1]};
	}

	/** The layer's step's column of a staircase: the first at the top, one column round a layer. */
	private static int stepIndex(Area area, int y) {
		return Math.floorMod(area.getMaxY() - y, 4);
	}

	/**
	 * A goto into the staircase's 2x2, from the active layer's floor (its feet in its bottom) up to its
	 * feet. Not waited on: one waiting over the layer is left on a pillar as the others dig round it.
	 */
	private boolean into(String bot, Plan plan, int active) {
		int minX = Integer.MAX_VALUE;
		int minZ = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE;
		int maxZ = Integer.MIN_VALUE;

		for (int[] cell : plan.region.stairs[plan.corner]) {
			minX = Math.min(minX, cell[0]);
			minZ = Math.min(minZ, cell[1]);
			maxX = Math.max(maxX, cell[0]);
			maxZ = Math.max(maxZ, cell[1]);
		}

		final JsonObject box = new JsonObject();
		box.addProperty("minX", minX);
		box.addProperty("minY", active);
		box.addProperty("minZ", minZ);
		box.addProperty("maxX", maxX);
		box.addProperty("maxY", plan.feet[1]);
		box.addProperty("maxZ", maxZ);
		final JsonObject a = new JsonObject();
		a.addProperty("action", "goto");
		a.add("area", box);
		plan.queue.add(a);
		plan.doing = "into its staircase";
		return true;
	}

	/**
	 * A goto to the step's top. With another bot of the region in it, or walking there (see taken: a
	 * staircase has room for one a step), waited on (see wake); not so with every other one waiting too,
	 * none of them moving.
	 */
	private boolean walk(String bot, Plan plan, int[] spot) {
		if (taken(bot, plan.region, spot[0], spot[1], spot[2]) && !stalled(bot, plan)) {
			plan.region.waiting.add(bot);
			plan.doing = "waiting for " + at(spot[0], spot[1], spot[2]) + " to be free";
			return true;
		}

		final JsonObject a = new JsonObject();
		a.addProperty("action", "goto");
		a.add("spot", position(spot[0], spot[1], spot[2]));
		plan.queue.add(a);
		plan.doing = "to " + at(spot[0], spot[1], spot[2]);
		return true;
	}

	/** The block broken from a spot it is seen from (break_wsr), with end ending there (break_wsrp). False when it failed. */
	private boolean dig(String bot, Plan plan, int[] cell, int[] end) {
		return dig(bot, plan, cell, end, false);
	}

	/** As dig, with async breaking it on the way to end (break_wsrpa). */
	private boolean dig(String bot, Plan plan, int[] cell, int[] end, boolean async) {
		final int x = cell[0];
		final int y = cell[1];
		final int z = cell[2];
		final BlockState block = state(bot, plan, x, y, z);

		if (block == null) {
			return false;
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

		final ItemStack stack = bestTool(bot, block);

		if (hardness > 0 && !harvests(stack, block)) {
			fail(bot, plan, plan.listener, "quarrify: expected an item in the inventory of " + bot + " that harvests " + name + " at " + at(x, y, z) + ", found none");
			return false;
		}

		// A support at end other than the block (see place), or end on the block (a step that falls, dug
		// to place again): broken from where it stands.
		if (end != null && !async && (!at(end, cell) && leftOut(plan.region, end[0], end[1], end[2]) || end[0] == x && end[1] == y + 1 && end[2] == z)) {
			end = null;
		}

		final JsonObject a = search(plan, end == null ? "break_wsr" : async ? "break_wsrpa" : "break_wsrp", cell, end);
		// Twice its dig and 2 s, when that is past the default: an obsidian one takes longer than it.
		final int ms = hardness == 0 ? 0 : (int) Math.ceil(hardness / speed(stack, block)) * 50;

		if (2 * ms + 2000 > DEADLINE) {
			a.addProperty("deadline", 2 * ms + 2000);
		}

		plan.queue.add(a);
		plan.dug++;
		plan.doing = "dig " + at(x, y, z);

		return true;
	}

	/** A block it may place put at the spot from one it is seen from (place_wsr), with end ending there (place_wsrp). */
	private boolean place(String bot, Plan plan, int[] spot, int[] end) {
		// A support at end, its own or another bot's (one over a step in the air, end its top): placed
		// from where it stands, end walked to once the support is broken.
		if (end != null && leftOut(plan.region, end[0], end[1], end[2])) {
			end = null;
		}

		plan.queue.add(search(plan, end == null ? "place_wsr" : "place_wsrp", spot, end));
		return true;
	}

	/** A wsr or wsrp primitive at the block: its walk to sight of it as far as the area's across, up and down. */
	private static JsonObject search(Plan plan, String action, int[] cell, int[] end) {
		final JsonObject a = new JsonObject();
		a.addProperty("action", action);
		a.add("target", position(cell[0], cell[1], cell[2]));
		a.addProperty("radius", plan.area.getWidth() + plan.area.getDepth() + plan.area.getHeight());

		if (end != null) {
			a.add("end", position(end[0], end[1], end[2]));
		}

		return a;
	}

	/** The inventory's item that breaks the block fastest, as the break picks it: empty for the hand. */
	private ItemStack bestTool(String bot, BlockState block) {
		final BotInventory inventory = bots.getInventory(bot);

		if (inventory == null) {
			throw new IllegalStateException("quarrify: no inventory of " + bot + " yet");
		}

		final List<BotInventory.Item> slots = inventory.getSlots();
		ItemStack best = ItemStack.EMPTY;
		float bestSpeed = speed(best, block);

		for (int slot = INVENTORY_FIRST; slot <= INVENTORY_LAST; slot++) {
			final ItemStack stack = BotScreen.stackOf(slots.get(slot));
			final float speed = speed(stack, block);

			if (speed > bestSpeed) {
				best = stack;
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

	/**
	 * The block's state as the bots hold it, the game's own by its id in the slot (the registry's names
	 * are the flattened ones, grass_block and oak_log, which this version has no blocks of); null when
	 * its chunk is not loaded, the plan failed with it.
	 */
	private BlockState state(String bot, Plan plan, int x, int y, int z) {
		// Planned ahead: the blocks of the digs sent gone.
		if (plan.ahead) {
			for (Sent one : plan.sent) {
				if (one.layer && one.target[0] == x && one.target[1] == y && one.target[2] == z) {
					return Blocks.AIR.getDefaultState();
				}
			}
		}

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

	/** Whether the block is water or lava, still or flowing. */
	private static boolean liquid(BlockState state) {
		return state.getMaterial().isFluid();
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

	private static int keyX(long key) {
		return (int) (key >> 38) << 6 >> 6;
	}

	private static int keyZ(long key) {
		return (int) (key << 38 >> 38);
	}

	private static boolean at(int[] a, int[] b) {
		return a[0] == b[0] && a[1] == b[1] && a[2] == b[2];
	}

	/** The dimension being played, as the fleet keys its columns. */
	private static String dimension() {
		return "minecraft:" + MinecraftClient.getInstance().world.dimension.getDimensionType().getName();
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
		if (plan.sent.isEmpty()) {
			plan.starting = true;
		}

		final int[] target = a.has("target") ? new int[] {a.getAsJsonObject("target").get("x").getAsInt(), a.getAsJsonObject("target").get("y").getAsInt(), a.getAsJsonObject("target").get("z").getAsInt()} : null;
		// A goto's spot is where it ends too.
		final String to = a.has("end") ? "end" : a.has("spot") ? "spot" : null;
		final int[] end = to != null ? new int[] {a.getAsJsonObject(to).get("x").getAsInt(), a.getAsJsonObject(to).get("y").getAsInt(), a.getAsJsonObject(to).get("z").getAsInt()} : null;
		plan.sent.add(new Sent(end, target, plan.claiming, plan.picked && target != null));
		plan.claiming = null;
		plan.picked = false;
		System.out.println("[quarrify trace] " + bot + " feet " + java.util.Arrays.toString(plan.feet) + " " + plan.doing + " (" + plan.sent.size() + " sent) " + a);
		bots.actionInternal(bot, a.toString());
	}

	@Override
	public synchronized void onAction(String bot, String json, boolean internal) {
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
			release(bot, plan);
			plan.listener.failed();
		}
	}

	/** The bot off its region: its staircase free, its dig no more; the region gone with no bot left on it. */
	private void release(String bot, Plan plan) {
		final Region region = plan.region;

		if (region == null) {
			return;
		}

		plan.region = null;
		region.claims.remove(bot);
		region.waiting.remove(bot);

		region.bots.remove(bot);

		// Its claim gone, or its standing in the way: the ones waiting pick again, its leaving told
		// (none of them would be woken by it otherwise).
		if (region.bots.isEmpty()) {
			regions.remove(plan.area.getId(), region);
		} else {
			wake(region);
		}
	}

	private void fail(String bot, Plan plan, JobRunner.Listener listener, String message) {
		if (plan != null && plans.get(bot) != plan) {
			// Failed already, or taken over: the first end stands.
			return;
		}

		if (plan != null) {
			plans.remove(bot);
			release(bot, plan);
		}

		errors.put(bot, message);
		listener.failed();
	}
}

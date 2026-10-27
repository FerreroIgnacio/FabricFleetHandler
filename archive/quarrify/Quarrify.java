package net.mapmcbot.client;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
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
 * carving and building it as it goes (break_wsrpa and place_wsrpa, ending each on the step it goes on to),
 * to stand on the active layer's floor: its feet in the layer's bottom. Nothing under that floor is
 * dug while the layer is active but a staircase carved early (below), the staircase's step under it made
 * solid: the floor is what the ends of the others' digs stand on. There it digs the layer with break_pac by bands, each bot on its own (see
 * band). With none left but the ones other bots dig, it goes down its staircase meanwhile, carving it, to the next layer's bottom (see
 * Plan.early), and waits there for theirs; none of its 2x2 dug from under another bot. The layer empty,
 * the others go down their staircases to the next, straight down the steps carved; one whose staircase
 * another bot is on digs its way down from where it is instead, when that is safe (see drop).
 *
 * A staircase is a spiral down its 2x2, one step a layer, each the next column round from the one over
 * it, so each column has a step every 4 layers with 3 of air over it. A step that is not solid is placed
 * against a solid neighbour; with none, on supports placed along its column (the shortest way, up or
 * down, in the area) up to one that has, broken once the step stands. One that falls (sand, gravel) is
 * dug and placed again. The steps are left once it is done.
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
	/** How far the eyes reach a block (actions.js REACH_BLOCK). */
	private static final double REACH = 4.5;
	/** The first slot of the inventory (window numbering): the tool a break holds is any of it, hotbar included. */
	private static final int INVENTORY_FIRST = 9;
	private static final int INVENTORY_LAST = 44;

	private static final int[][] FACES = {{0, -1, 0}, {0, 1, 0}, {1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}};
	/** The faces a liquid comes in by once the block is gone: over it and its sides (none flows up). */
	private static final int[][] INFLOWS = {{0, 1, 0}, {1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}};
	/**
	 * The order a pac breaks a half of 3 slices in (see pac), {slice, height over the layer's bottom}: the
	 * column toward its end 1..4 bottom up, the one past it 5..8, the farthest 9..12; 3 4 8 2 7 12 6 11 1 5 10 9:
	 * each one's blocks between the eyes and it, in the columns nearer end, broken before it (the one
	 * toward end at the eyes' height (2) before the one past it over them (7), which it hides otherwise).
	 */
	private static final int[][] PAC_ORDER = {{0, 2}, {0, 3}, {1, 3}, {0, 1}, {1, 2}, {2, 3}, {1, 1}, {2, 2}, {0, 0}, {1, 0}, {2, 1}, {2, 0}};
	/** No liquid next to the block (see dam). */
	private static final int[] NONE = new int[0];
	/** The most slices a band primitive takes (see bandRun): the band test's (bandTest.txt). */
	private static final int BAND_SLICES = 8;
	/** How deep a band primitive's section is, in slices (actions.js BAND_DEPTH). */
	private static final int BAND_DEPTH = 2;
	/** Debug: a band's layer dug with band primitives only, a pac where one does not take it failing the plan with why (see pac). */
	private static final boolean BANDS_ONLY = true;

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
		/** The blocks each bot is digging or placing, by key(): one a primitive sent (see Sent), none left empty. */
		final Map<String, Set<Long>> claims = new HashMap<String, Set<Long>>();
		/** The bots with nothing to do but what the others are on, until a primitive of theirs ends. */
		final Set<String> waiting = new LinkedHashSet<String>();
		/** Whether the drops' bands run along x, the area's longer side (x on a tie); else along z (see drop). */
		final boolean alongX;
		/** The line each bot digs on the layer of bandsOf (its bottom), {1 along x else 0, its first coordinate across} (see band). */
		final Map<String, int[]> bands = new HashMap<String, int[]>();
		int bandsOf = Integer.MIN_VALUE;

		Region(Area area) {
			this.area = area;
			final int w = area.getMinX();
			final int e = area.getMaxX();
			final int n = area.getMinZ();
			final int s = area.getMaxZ();
			alongX = e - w >= s - n;
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
		/** The blocks it breaks or places, {x, y, z}, in order; empty with none. */
		final List<int[]> targets;
		/** The blocks claimed for it (see claim), by key(). */
		final Set<Long> claims;
		/** Whether it is a dig of the layer picked (see next): the next is planned ahead behind it (see ahead). */
		final boolean layer;

		Sent(int[] end, List<int[]> targets, Set<Long> claims, boolean layer) {
			this.end = end;
			this.targets = targets;
			this.claims = claims;
			this.layer = layer;
		}

		/** Its last block, null with none. */
		int[] last() {
			return targets.isEmpty() ? null : targets.get(targets.size() - 1);
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
		/**
		 * The next layer's bottom it goes down its staircase to while the others end the active one (see
		 * band), waiting there; null with none. Of the active layer earlyOf: gone once that is.
		 */
		Integer early;
		int earlyOf;
		/** The column {x, z} it digs its way down into the layer of dropOf by (see drop); null with none. */
		int[] drop;
		int dropOf;
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
		/** Why the last band() found no way in, counted by cause, for the executor's window; null while it is not waiting on that. */
		String why;
		/** Why the last pac was no band primitive (see bandRun), for the trace; null with no band looked at. */
		String noBand;
		/** The causes band() met since it started, counted: each a candidate turned down. */
		final Map<String, Integer> tally = new LinkedHashMap<String, Integer>();
		/** What it waited on first since its last primitive was sent, and since when (System.nanoTime), for the trace; null when not waiting. */
		String waited;
		long waitedSince;
		/** A primitive sent and not yet seen going: an idle before that is the end of the one before it, not of this. */
		boolean starting;
		/** The primitives sent and not over yet, in order: the first the one the bot runs. */
		final Deque<Sent> sent = new ArrayDeque<Sent>();
		/** The blocks claimed for the primitive sent next, by key(). */
		final Set<Long> claiming = new HashSet<Long>();
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
		/** The last block of its last pac queued (see pac): where the head is once that is over; null before one. */
		int[] broke;

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

	/** A band of a region's active layer, to draw (see bands): its box, {min x, y, z, max x, y, z}, and the bots digging it. */
	static final class Band {
		final int[] box;
		final List<String> bots;

		Band(int[] box, List<String> bots) {
			this.box = box;
			this.bots = bots;
		}
	}

	/** The bands of each region's active layer (see band), with the bots on each. */
	synchronized List<Band> bands() {
		final List<Band> out = new ArrayList<Band>();

		for (Region region : regions.values()) {
			final Area area = region.area;

			if (region.active < area.getMinY()) {
				continue;
			}

			if (region.bandsOf != region.active) {
				continue;
			}

			for (Map.Entry<String, int[]> one : region.bands.entrySet()) {
				final boolean alongX = one.getValue()[0] == 1;
				final int[] across = columns(area, alongX, one.getValue()[1]);
				final int first = across[0];
				final int last = across[across.length - 1];

				out.add(new Band(alongX
					? new int[] {area.getMinX(), region.active, first, area.getMaxX(), region.top, last}
					: new int[] {first, region.active, area.getMinZ(), last, region.top, area.getMaxZ()}, Collections.singletonList(one.getKey())));
			}
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

	/** The executor's state per bot with a plan, in name order: {bot, what it is doing, why it sees no way in or null}. */
	synchronized List<String[]> executor() {
		final List<String[]> out = new ArrayList<String[]>();

		for (String bot : new java.util.TreeSet<String>(plans.keySet())) {
			final Plan plan = plans.get(bot);
			out.add(new String[] {bot, plan.doing, plan.why});
		}

		return out;
	}

	private static void turnDown(Plan plan, String cause) {
		plan.tally.merge(cause, 1, Integer::sum);
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
		final List<int[]> headTargets = head == null ? new ArrayList<int[]>() : head.targets;

		// A break whose block is gone by the time it starts (sand or gravel falling through it, seen there
		// on the way): nothing to break, picked again from the world as it is. Any other error fails it.
		final String message = status.getMessage();
		boolean gone = status.getKind() == BotStatus.Kind.ERROR && status.getPrimitive().startsWith("break_") && (message.startsWith("expected a block at ") && message.endsWith(", found air") || message.startsWith("block at ") && message.contains(" is already \"minecraft:air\""));

		// No path to its end any more (sand or gravel fallen into it, or on the way): picked again too,
		// once an end; the same end twice in a row, failed.
		// Its target hidden on the way (sand or gravel fallen in front of it): the same, once a target.
		int[] missed = status.getKind() != BotStatus.Kind.ERROR ? null
				: headEnd != null && message.startsWith("expected a path to " + at(headEnd[0], headEnd[1], headEnd[2]) + ", found none") ? headEnd
				: null;

		for (int i = 0; i < headTargets.size() && missed == null && status.getKind() == BotStatus.Kind.ERROR; i++) {
			final int[] headTarget = headTargets.get(i);
			missed = status.getPrimitive().startsWith("break_") && message.contains(" at " + at(headTarget[0], headTarget[1], headTarget[2]) + " in reach and in sight, found ") && message.contains(" hidden by ") ? headTarget
					// No spot seeing it (the one there is another bot's, or the world changed on the way).
					: message.startsWith("expected a spot a walk of ") && message.contains(" at " + at(headTarget[0], headTarget[1], headTarget[2]) + " in reach and in sight, found none") ? headTarget
					// A band's walk cut short by sand or gravel fallen on its floor (planned while it fell).
					: status.getPrimitive().equals("band") && message.contains(" at " + at(headTarget[0], headTarget[1], headTarget[2]) + " of the band in reach and in sight from where the walk stops") && fallenInTheWay(message) ? headTarget
					// A block of the band another tool's (fallen in or changed since it was planned): any of them.
					: status.getPrimitive().equals("band") && message.contains(" of the band broken at once with the tool in hand") ? headTarget
					// A band's path closed by sand or gravel fallen round its feet (planned while it fell).
					: status.getPrimitive().equals("band") && message.startsWith("expected a path to ") && fallenRound(message) ? headTarget
					: message.startsWith("expected a spot a walk of ") && message.contains(" next to " + at(headTarget[0], headTarget[1], headTarget[2]) + " to place against in reach and in sight, found none") ? headTarget
					// A place the server took nothing of: something in the spot then (a falling block, a bot).
					: status.getPrimitive().startsWith("place_") && message.startsWith("Server refused to place ") && message.contains("(" + headTarget[0] + ", " + headTarget[1] + ", " + headTarget[2] + ")") ? headTarget
					: null;
		}

		if (missed != null) {
			// Hidden by sand or gravel (a column of it coming down a block at each dig under it): picked
			// again each time, a block of it less each round.
			gone = plan.unreached == null || !at(plan.unreached, missed) || message.contains(" hidden by ") && (message.contains(" minecraft:sand at ") || message.contains(" minecraft:gravel at ")) || fallenRound(message) || message.contains(" of the band broken at once with the tool in hand");
			plan.unreached = missed.clone();
		} else if (status.getKind() == BotStatus.Kind.IDLE && (headEnd != null || !headTargets.isEmpty())) {
			// Not on a stop's (the one sent after a failure, see behind): the one picked again after it fails the same.
			plan.unreached = null;
		}

		final Region region = plan.region;
		plan.sent.poll();

		if (region != null && head != null) {
			for (long claim : head.claims) {
				region.unclaim(bot, claim);
			}
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
			plan.claiming.clear();

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
		return nearest(bot, region, feet, null, 0);
	}

	/** As nearest, of the staircases with a block of their 2x2 at y among walkable (see walkable) when not null; -1 with none. */
	private int nearest(String bot, Region region, int[] feet, Set<Long> walkable, int y) {
		int best = -1;
		int bestTaken = Integer.MAX_VALUE;
		double bestDistance = Double.MAX_VALUE;

		for (int i = 0; i < region.stairs.length; i++) {
			final int[] cell = region.stairs[i][0];
			int taken = 0;
			boolean walked = walkable == null;

			for (int[] one : region.stairs[i]) {
				walked |= walkable != null && walkable.contains(key(one[0], y, one[1]));
			}

			if (!walked) {
				continue;
			}

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
				if (plan.waited == null) {
					plan.waited = plan.doing;
					plan.waitedSince = System.nanoTime();
					System.out.println("[quarrify trace] " + bot + " feet " + java.util.Arrays.toString(plan.feet) + " waiting: " + plan.doing + (plan.why == null ? "" : " (" + plan.why + ")"));
				}

				return;
			}

			send(bot, plan, plan.queue.poll());
		}

		ahead(bot, plan);
	}

	/**
	 * The primitives after the one running planned ahead, QUEUE in all, the bot holding them: behind a
	 * dig of the layer with its end, the next pac of its bands (see band) as next would once those sent are
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

				for (int[] target : one.targets) {
					final Boolean falls = falls(bot, plan, target[0], target[1] + 1, target[2]);

					if (falls == null || falls) {
						return;
					}
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
			Boolean queued = null;
			plan.feet = last.end.clone();
			// Its block's middle: its reach from the worst of the block.
			plan.position = new double[] {last.end[0] + 0.5, last.end[1], last.end[2] + 0.5};
			plan.ahead = true;

			try {
				// On a floor of its own there (see walkable): hanging over an edge, where it is held up from is not known.
				final BlockState under = state(bot, plan, last.end[0], last.end[1] - 1, last.end[2]);

				if (under == null || !solid(under) || supported(region, last.end[0], last.end[1] - 1, last.end[2])) {
					return;
				}

				queued = band(bot, plan, region.active, region.top);
			} finally {
				plan.feet = feet;
				plan.position = position;
				plan.ahead = false;
			}

			if (plans.get(bot) != plan || queued == null || !queued) {
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

		if (plan.early != null && plan.earlyOf != layer) {
			plan.early = null;
		}

		if (plan.early != null) {
			// Down its staircase early (see band), there waiting for the others to end the layer.
			if (feet > plan.early) {
				return down(bot, plan, plan.early);
			}

			region.waiting.add(bot);
			plan.doing = "waiting at " + plan.early + " for the others to end the layer";
			return true;
		}

		if (feet != layer && plan.dugLayer) {
			// Off the layer: by the nearest staircase (see nearest), all carved with the layers.
			plan.corner = nearest(bot, region, plan.feet);
			plan.dugLayer = false;
		}

		if (feet > layer) {
			// Its staircase another bot's meanwhile: down into the layer from over it (see drop).
			final Boolean dropped = drop(bot, plan, layer);

			if (dropped == null || dropped) {
				return dropped != null;
			}

			return down(bot, plan, layer);
		}

		if (feet < layer) {
			return up(bot, plan, layer);
		}

		// The step under its staircase's next first.
		final boolean waited = region.waiting.contains(bot);
		final Boolean step = carvable(plan, layer - 2) ? carve(bot, plan, layer - 2, null, -1, true) : Boolean.FALSE;

		if (step == null) {
			return false;
		}

		// Waiting on a block of it another bot has or stands on: the layer meanwhile, not a wait; the
		// step carved as next comes back to it after (this first, each time).
		if (step && plan.queue.isEmpty() && !waited && region.waiting.contains(bot)) {
			region.waiting.remove(bot);
		} else if (step) {
			return true;
		}

		plan.dugLayer = true;
		final Boolean banded = band(bot, plan, layer, top);

		if (banded == null) {
			return false;
		}

		// Down its staircase early, waiting on the others' bands, or a pac queued, or waiting.
		if (plan.early != null) {
			return down(bot, plan, plan.early);
		}

		return true;
	}

	/**
	 * The bottom of the region's active layer, gone down past the empty ones: the highest with something
	 * to dig (the steps and supports left out), never over the one it was. A layer with a liquid has its
	 * bottom at the highest of them. Null when a state was missing (failed).
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
	 * The layer (bottom..top) dug with break_pac (see pac), a pac at a time, by bands: each bot on its
	 * own (see Region.bands), the lines 2 wide along the area's longer side (see Region.alongX). The bot's
	 * band till nothing is left of it for it, then the first with no other bot on it, else the first with one
	 * (2 a band at most). True with a pac
	 * queued, waiting, or down its staircase early (Plan.early) with what is left another bot's; false
	 * planning ahead (see ahead) with none. Null when it failed.
	 */
	private Boolean band(String bot, Plan plan, int bottom, int top) {
		final Region region = plan.region;
		final Area area = plan.area;

		if (region.bandsOf != bottom) {
			region.bands.clear();
			region.bandsOf = bottom;
		}

		final Set<Long> walkable = walkable(bot, plan, bottom);

		if (walkable == null) {
			return null;
		}

		plan.tally.clear();

		// Sand or gravel come down on it (its head in it): no face of it turned to the eyes, broken from
		// nowhere there; out to a spot next to it first, the nearest walked to.
		final BlockState overHead = plan.ahead ? null : state(bot, plan, plan.feet[0], plan.feet[1] + 1, plan.feet[2]);

		if (!plan.ahead && overHead == null) {
			return null;
		}

		if (overHead != null && solid(overHead) && plan.feet[1] == bottom) {
			for (int[] face : FACES) {
				final int x = plan.feet[0] + face[0];
				final int z = plan.feet[2] + face[2];

				if (face[1] == 0 && walkable.contains(key(x, bottom, z)) && !taken(bot, region, x, bottom, z)) {
					plan.doing = "out from under " + at(plan.feet[0], plan.feet[1] + 1, plan.feet[2]);
					return walk(bot, plan, new int[] {x, bottom, z}) ? Boolean.TRUE : null;
				}
			}
		}

		final int axis = region.alongX ? 1 : 0;
		final int[] own = region.bands.get(bot);

		if (own != null) {
			final Integer got = pac(bot, plan, region.alongX, own[1], bottom, top, walkable, true);

			if (got == null) {
				return null;
			}

			if (got == 1) {
				return Boolean.TRUE;
			}
		}

		region.bands.remove(bot);
		boolean shut = false;

		// The ones with no other bot on it first, then the ones with one (2 a band at most): its pac away from it (see pac).
		for (int sharing = 0; sharing <= 1; sharing++) {
			for (int c : bandStarts(region.alongX ? area.getMinZ() : area.getMinX(), region.alongX ? area.getMaxZ() : area.getMaxX(), true)) {
				if (own != null && c == own[1]) {
					continue;
				}

				final int on = others(bot, region, axis, c).size();

				if (on != sharing) {
					if (on > 1) {
						turnDown(plan, "band with two other bots on it");
					}

					continue;
				}

				final Integer got = pac(bot, plan, region.alongX, c, bottom, top, walkable, true);

				if (got == null) {
					return null;
				}

				if (got == 1) {
					region.bands.put(bot, new int[] {axis, c});
					return Boolean.TRUE;
				}

				shut |= got == 2;
			}
		}

		// Planning ahead: only a band with a way in from where the sent ones end, else picked again as they end.
		if (plan.ahead) {
			return false;
		}

		// No way into any: a doorway dug into one no other bot is on, from a spot walked to.
		if (shut) {
			final Boolean opened = doorway(bot, plan, walkable, bottom, top);

			if (opened == null || opened) {
				return opened;
			}
		}

		// Every band left another bot's, or no way into the ones left: down its staircase meanwhile to the
		// next layer's bottom, the nearest one of the ones walked to.
		final int stair = nearest(bot, region, plan.feet, walkable, bottom);

		if (stair >= 0 && region.active > area.getMinY()) {
			plan.early = Math.max(region.active - LAYER, area.getMinY());
			plan.earlyOf = region.active;
			plan.corner = stair;
			plan.dugLayer = false;
			return true;
		}

		// Picked again as one ends, or as a bot leaves the region.
		region.waiting.add(bot);
		plan.doing = shut ? "waiting for a way into a band" : "waiting on the others' bands or a way to a staircase";
		plan.why = plan.tally.isEmpty() ? "no band left to look at" : plan.tally.toString().replace("{", "").replace("}", "").replace("=", ": ");
		return true;
	}

	/**
	 * A doorway into a band no other bot is on (see band): the column next to a spot walked to across a
	 * face, out of the staircases' 2x2s, its feet and head blocks of the layer dug from that spot (head
	 * first), the nearest to the bot. True with one sent or waited on, false with none, null when it failed.
	 */
	private Boolean doorway(String bot, Plan plan, Set<Long> walkable, int bottom, int top) {
		final Region region = plan.region;
		final Area area = plan.area;
		int[] cell = null;
		int[] end = null;
		double best = Double.MAX_VALUE;

		for (int z = area.getMinZ(); z <= area.getMaxZ(); z++) {
			for (int x = area.getMinX(); x <= area.getMaxX(); x++) {
				if (!walkable.contains(key(x, bottom, z))) {
					continue;
				}

				for (int[] face : FACES) {
					final int dx = x + face[0];
					final int dz = z + face[2];

					if (face[1] != 0 || !area.contains(dx, bottom, dz)) {
						continue;
					}

					if (region.inStair(dx, dz)) {
						turnDown(plan, "doorway in a staircase");
						continue;
					}

					final int across = region.alongX ? dz : dx;
					final int c = across - Math.floorMod(across - (region.alongX ? area.getMinZ() : area.getMinX()), 2);

					if (!others(bot, region, region.alongX ? 1 : 0, c).isEmpty()) {
						turnDown(plan, "doorway on a band with another bot");
						continue;
					}

					// Nothing that falls over it: dug out under, it comes down into the doorway as the bot walks in.
					final Boolean falls = falls(bot, plan, dx, bottom, dz);

					if (falls == null) {
						return null;
					}

					if (falls) {
						turnDown(plan, "doorway with sand or gravel over it");
						continue;
					}

					for (int y = Math.min(bottom + 1, top); y >= bottom; y--) {
						if (leftOut(region, dx, y, dz) || region.claimed(key(dx, y, dz))) {
							continue;
						}

						final BlockState state = state(bot, plan, dx, y, dz);

						if (state == null) {
							return null;
						}

						if (air(state) || liquid(state)) {
							continue;
						}

						final double bx = x + 0.5 - plan.position[0];
						final double bz = z + 0.5 - plan.position[2];

						if (bx * bx + bz * bz < best) {
							cell = new int[] {dx, y, dz};
							end = new int[] {x, bottom, z};
							best = bx * bx + bz * bz;
						}

						break;
					}
				}
			}
		}

		if (cell == null) {
			turnDown(plan, "doorway with nothing to break");
			return false;
		}

		plan.doing = "doorway " + at(cell[0], cell[1], cell[2]);
		return !claim(bot, plan, cell) || dig(bot, plan, cell, end) ? Boolean.TRUE : null;
	}

	/**
	 * The band c's next break_pac for the bot, queued with queue (see band): from the slice left (any,
	 * mid band too; a slice: its columns across at one coordinate along) and way along whose way in
	 * (see entry) is the nearest to the bot, one in the band over one next to it, its squared distance into away[0], ending there; that way
	 * on, with other bots on it only away from all of them, the slices from there in reach of end wherever in it the feet are (see reachesFrom), a slice with a
	 * column not to break (see column) the last. Three slices at most, 2x3 by the layer: one half across
	 * (the one the bot is on) then the other, each its column toward end (1..4 bottom up) and the ones
	 * past it (5..12) broken in PAC_ORDER, the head turned the least between them; end in the band, each
	 * half broken from the line along its column's middle (see break_pac's stand).
	 * 1 with one (queued with queue); 0 with nothing left in the band; 2 with no way into it (no slice
	 * to break with an end next to it, or none in reach from that end); null when it failed.
	 */
	private Integer pac(String bot, Plan plan, boolean alongX, int c, int bottom, int top, Set<Long> walkable, boolean queue) {
		final Area area = plan.area;
		final int from = alongX ? area.getMinX() : area.getMinZ();
		final int to = alongX ? area.getMaxX() : area.getMaxZ();
		final int[] across = columns(area, alongX, c);
		// Each slice with something left by its coordinate along: each column's blocks to break by their y; null with one not to.
		final Map<Integer, List<Map<Integer, int[]>>> slices = new LinkedHashMap<Integer, List<Map<Integer, int[]>>>();

		for (int u = from; u <= to; u++) {
			final List<Map<Integer, int[]>> slice = new ArrayList<Map<Integer, int[]>>();
			boolean free = true;
			boolean left = false;

			for (int a : across) {
				final List<int[]> blocks = new ArrayList<int[]>();
				final Boolean one = column(bot, plan, alongX ? u : a, alongX ? a : u, bottom, top, blocks);

				if (one == null) {
					return null;
				}

				free &= one;
				left |= !blocks.isEmpty();
				final Map<Integer, int[]> byY = new HashMap<Integer, int[]>();

				for (int[] block : blocks) {
					byY.put(block[1], block);
				}

				slice.add(byY);
			}

			if (!free || left) {
				slices.put(u, free ? slice : null);

				if (!free) {
					turnDown(plan, "slice with a column not to break");
				}
			}
		}

		if (slices.isEmpty()) {
			return 0;
		}

		// The others on it, where along: each digs away from them (see below).
		final List<Integer> others = new ArrayList<Integer>();

		for (String other : others(bot, plan.region, alongX ? 1 : 0, c)) {
			final Plan them = plans.get(other);

			if (them != null && them.feet != null) {
				others.add(alongX ? them.feet[0] : them.feet[2]);
			}
		}

		int start = 0;
		int dir = 1;
		int[] end = null;
		boolean endIn = false;
		double best = Double.MAX_VALUE;
		// BANDS_ONLY: every way in in the band (start, dir, squared distance), each tried with the band primitive, the nearest first.
		final List<double[]> ways = new ArrayList<double[]>();

		// With no way in walked to, none: waited on till the others' digs open one, never dug across to it.
		for (Map.Entry<Integer, List<Map<Integer, int[]>>> one : slices.entrySet()) {
			if (one.getValue() == null) {
				continue;
			}

			final int u = one.getKey();

			for (int way0 = 1; way0 >= -1; way0 -= 2) {
				// Away from every other bot on the band: past them along, digging on away from them.
				boolean apart = true;

				for (int at : others) {
					apart &= (u - at) * way0 > 0;
				}

				if (!apart) {
					turnDown(plan, "slice not away from the other bots");
					continue;
				}

				final int[] in = entry(bot, plan, alongX, c, u, way0, bottom, top, walkable);

				if (in == null) {
					if (plans.get(bot) != plan) {
						return null;
					}

					turnDown(plan, "slice without an entry");
					continue;
				}

				final double bx = in[0] + 0.5 - plan.position[0];
				final double bz = in[2] + 0.5 - plan.position[2];
				// In the band over next to it across, whatever the distance: from the side a pac breaks one column, along it the slices.
				final int inAt = alongX ? in[2] : in[0];
				final boolean inside = inAt >= across[0] && inAt <= across[across.length - 1];

				if (inside) {
					ways.add(new double[] {u, way0, bx * bx + bz * bz});
				}

				if (inside && !endIn || inside == endIn && bx * bx + bz * bz < best) {
					start = u;
					dir = way0;
					end = in;
					endIn = inside;
					best = bx * bx + bz * bz;
				}
			}
		}

		if (end == null) {
			return 2;
		}

		if (!queue) {
			return 1;
		}

		if (BANDS_ONLY) {
			if (across.length != 2 || top - bottom + 1 != LAYER) {
				fail(bot, plan, plan.listener, "quarrify: band " + c + " is no band primitive's (" + (across.length != 2 ? "1 wide" : "not a whole layer") + "), pacs off (BANDS_ONLY)");
				return null;
			}

			Collections.sort(ways, new Comparator<double[]>() {
				@Override
				public int compare(double[] a, double[] b) {
					return Double.compare(a[2], b[2]);
				}
			});
			// Then every other slice to start on, away from the others, the nearest first: an entry's first
			// stand may fall out of the area (one at its edge, digging in), one further in has it walked to.
			final List<double[]> rest = new ArrayList<double[]>();

			for (Map.Entry<Integer, List<Map<Integer, int[]>>> one : slices.entrySet()) {
				if (one.getValue() == null) {
					continue;
				}

				final int u = one.getKey();

				for (int way0 = 1; way0 >= -1; way0 -= 2) {
					boolean apart = true;
					boolean tried = false;

					for (int at : others) {
						apart &= (u - at) * way0 > 0;
					}

					for (double[] way : ways) {
						tried |= way[0] == u && way[1] == way0;
					}

					if (apart && !tried) {
						final double bx = (alongX ? u : across[1]) + 0.5 - plan.position[0];
						final double bz = (alongX ? across[1] : u) + 0.5 - plan.position[2];
						rest.add(new double[] {u, way0, bx * bx + bz * bz});
					}
				}
			}

			Collections.sort(rest, new Comparator<double[]>() {
				@Override
				public int compare(double[] a, double[] b) {
					return Double.compare(a[2], b[2]);
				}
			});
			ways.addAll(rest);
			final List<String> whys = new ArrayList<String>();

			for (double[] way : ways) {
				final int u = (int) way[0];
				final int way0 = (int) way[1];
				final Boolean banded = bandRun(bot, plan, alongX, c, across, slices, u, way0, from, to, bottom, top, walkable);

				if (banded == null) {
					return null;
				}

				if (banded) {
					return 1;
				}

				whys.add("from " + u + (way0 > 0 ? " up" : " down") + ": " + plan.noBand);
			}

			// Planning ahead: picked again from the world as the sent ones end.
			if (plan.ahead) {
				return 2;
			}

			fail(bot, plan, plan.listener, "quarrify: band " + c + " took no band primitive (" + (whys.isEmpty() ? "no way in in the band" : String.join("; ", whys)) + "), pacs off (BANDS_ONLY)");
			return null;
		}

		final int at = alongX ? end[2] : end[0];
		final boolean inBand = at >= across[0] && at <= across[across.length - 1];
		plan.noBand = !inBand ? "end out of the band" : across.length != 2 ? "1 wide" : top - bottom + 1 != LAYER ? "not a whole layer" : null;

		// 2 wide on a whole layer: the band primitive (see bandRun) from start on, when it takes it.
		if (inBand && across.length == 2 && top - bottom + 1 == LAYER) {
			final Boolean banded = bandRun(bot, plan, alongX, c, across, slices, start, dir, from, to, bottom, top, walkable);

			if (banded == null) {
				return null;
			}

			if (banded) {
				return 1;
			}
		}

		// The slices dug, in order: 3 at most, end next to them, the next pac's in reach on the way to its end.
		final List<List<Map<Integer, int[]>>> run = new ArrayList<List<Map<Integer, int[]>>>();

		for (int u = start; u >= from && u <= to && run.size() < 3; u += dir) {
			// Next to each other only: past a slice dug out PAC_ORDER's sight from end
			// no longer holds, the near column hiding the far one's blocks; the rest the next pac's.
			if (!slices.containsKey(u)) {
				break;
			}

			final List<Map<Integer, int[]>> slice = slices.get(u);

			if (slice == null) {
				break;
			}

			boolean reached = true;

			for (Map<Integer, int[]> column : slice) {
				for (int[] block : column.values()) {
					reached &= reachesFrom(end, block);
				}
			}

			// From an end across, out of the band: the slice seen past the column next to it there, nothing of it left (another band's still there hides it).
			for (int h = bottom; !inBand && h <= top && reached; h++) {
				final BlockState state = state(bot, plan, alongX ? u : at, h, alongX ? at : u);

				if (state == null) {
					return null;
				}

				reached = air(state);
			}

			if (!reached) {
				break;
			}

			run.add(slice);

			// A block of it left out (a staircase's step) hides the next from end: the rest the next pac's.
			boolean hides = false;

			for (int a : across) {
				for (int h = bottom; h <= top && !hides; h++) {
					final int x = alongX ? u : a;
					final int z = alongX ? a : u;

					if (leftOut(plan.region, x, h, z)) {
						final BlockState state = state(bot, plan, x, h, z);

						if (state == null) {
							return null;
						}

						hides = !air(state);
					}
				}
			}

			if (hides) {
				break;
			}
		}

		// The half the bot is on first, the other after: break_pac breaks each from its column's line
		// (see its stand), strafing across to it, so the head turns across the band once a pac and not
		// back at the start of the next. On: across, where its feet are; planning ahead, the line of the
		// last one's last block when in the band (where break_pac leaves it), else that one's end. From
		// an end out of the band, the half next to it first, whatever it is on now: it breaks them all
		// from there, the far half hidden behind the near one.
		final Sent before = plan.ahead ? plan.sent.peekLast() : null;
		final int[] lastBlock = before == null ? null : before.last();
		final int lastAcross = lastBlock == null ? Integer.MIN_VALUE : alongX ? lastBlock[2] : lastBlock[0];
		final double side = !inBand ? (alongX ? end[2] : end[0]) + 0.5 : lastAcross >= across[0] && lastAcross <= across[across.length - 1] ? lastAcross + 0.5 : alongX ? plan.position[2] : plan.position[0];
		final int[] halves = across.length == 1 ? new int[] {0} : Math.abs(across[1] + 0.5 - side) < Math.abs(across[0] + 0.5 - side) ? new int[] {1, 0} : new int[] {0, 1};
		boolean any = false;
		// The line through a staircase's 2x2 from end's slice to its last: no stand, its steps over the column next to end across hiding the targets from it.
		boolean stairs = false;

		final int endAlong = alongX ? end[0] : end[2];
		final int lastAlong = start + dir * (run.size() - 1);

		for (int u = Math.min(endAlong, lastAlong); u <= Math.max(endAlong, lastAlong); u++) {
			for (int a : across) {
				stairs |= alongX ? plan.region.inStair(u, a) : plan.region.inStair(a, u);
			}
		}

		// A pac a half: each broken from its column's middle line across (its stand's line), then strafing to the other's.
		for (int half : halves) {
			final List<int[]> targets = new ArrayList<int[]>();

			for (int[] block : PAC_ORDER) {
				if (block[0] < run.size()) {
					final int[] target = run.get(block[0]).get(half).get(bottom + block[1]);

					if (target != null) {
						targets.add(target);
					}
				}
			}

			if (targets.isEmpty()) {
				continue;
			}

			queue(bot, plan, targets, end, "band " + c + (plan.noBand == null ? "" : " (no band: " + plan.noBand + ")"));
			any = true;

			// In a 2 wide band: its seam across, end's middle along, the half's column middle its line.
			if (inBand && across.length == 2 && !stairs) {
				final JsonObject stand = new JsonObject();
				stand.addProperty("x", alongX ? end[0] + 0.5 : across[1]);
				stand.addProperty("z", alongX ? across[1] : end[2] + 0.5);
				stand.addProperty("line", across[half] + 0.5);
				plan.queue.peekLast().add("stand", stand);
			}
		}

		if (!any) {
			return 2;
		}

		return 1;
	}

	/**
	 * The band primitive (actions.js breakBand) over the band c's slices from start on (dir), queued for
	 * the bot: BAND_SLICES at most, up to the first one it does not take (one with a block a pac does
	 * not break, see column; in a staircase's 2x2 or with a block left out; with a block not broken at
	 * once with the best tool). It walks on the layer's floor along the seam from its first stand back
	 * of start (see bandBack) on: every spot from there to start walked to (see walkable). True with
	 * one queued; false with none (3 slices at least: along its longer side, a tie being x); null when
	 * a state was missing (failed).
	 */
	private Boolean bandRun(String bot, Plan plan, boolean alongX, int c, int[] across, Map<Integer, List<Map<Integer, int[]>>> slices, int start, int dir, int from, int to, int bottom, int top, Set<Long> walkable) {
		final Region region = plan.region;
		final double back = bandBack(bottom);
		final List<int[]> targets = new ArrayList<int[]>();
		int last = start;
		// The one tool it breaks them all with at once, its first block's best: a dig just after a tool
		// change in the band went unanswered by the server (quarrifyErrors.txt), so no change in it.
		ItemStack tool = null;

		String stop = null;

		for (int u = start; u >= from && u <= to && Math.abs(u - start) < BAND_SLICES; u += dir) {
			boolean takes = !slices.containsKey(u) || slices.get(u) != null;
			stop = takes ? null : "a column not to break at " + u;

			for (int a : across) {
				final int x = alongX ? u : a;
				final int z = alongX ? a : u;
				if (takes && region.inStair(x, z)) {
					takes = false;
					stop = "a staircase at " + u;
				}

				for (int h = bottom; h <= top && takes; h++) {
					takes = !leftOut(region, x, h, z);

					if (!takes) {
						stop = "a block left out at " + u;
					}
				}

				// Nothing that falls: one dug under it (by its order or swept on the way) brings it down where it was.
				final Boolean falls = takes ? falls(bot, plan, x, bottom, z) : Boolean.FALSE;

				if (falls == null) {
					return null;
				}

				if (takes && falls) {
					takes = false;
					stop = "sand or gravel at " + u;
				}
			}

			final List<int[]> blocks = new ArrayList<int[]>();

			for (int a = 0; takes && slices.containsKey(u) && a < across.length; a++) {
				for (int h = bottom; h <= top && takes; h++) {
					final int[] block = slices.get(u).get(a).get(h);

					if (block == null) {
						continue;
					}

					final Boolean instant = instant(bot, plan, block);

					if (instant == null) {
						return null;
					}

					takes = instant;

					if (!takes) {
						stop = "not broken at once at " + at(block[0], block[1], block[2]);
					}

					final BlockState state = state(bot, plan, block[0], block[1], block[2]);

					if (state == null) {
						return null;
					}

					final float hardness = state.getBlock().getHardness(state, null, null);

					if (takes && hardness > 0) {
						if (tool == null) {
							tool = bestTool(bot, state);
						}

						takes = speed(tool, state) >= hardness;

						if (!takes) {
							stop = "not broken at once with the band's tool at " + at(block[0], block[1], block[2]);
						}
					}

					blocks.add(block);
				}
			}

			if (!takes) {
				break;
			}

			if (!blocks.isEmpty()) {
				targets.addAll(blocks);
				last = u;
			}
		}

		if (targets.isEmpty() || Math.abs(last - start) + 1 < 3) {
			plan.noBand = (Math.abs(last - start) + 1) + " slices, stopped by " + (stop == null ? "the area's end" : stop);
			return false;
		}

		// Its first stand (section 0's: its far slice's near face, back by back) and the spots to it, walked to.
		final int far0 = start + dir;
		final int stand0 = (int) Math.floor((dir == 1 ? far0 : far0 + 1) - dir * back);

		for (int u = stand0; u != start; u += dir) {
			for (int a : across) {
				if (!walkable.contains(key(alongX ? u : a, bottom, alongX ? a : u))) {
					plan.noBand = "first stand's way not walked to at " + at(alongX ? u : a, bottom, alongX ? a : u);
					return false;
				}

				// Nothing over it that falls: on the bot walking under it.
				final Boolean falls = falls(bot, plan, alongX ? u : a, bottom + 2, alongX ? a : u);

				if (falls == null) {
					return null;
				}

				if (falls) {
					plan.noBand = "sand or gravel over its way at " + at(alongX ? u : a, bottom + 2, alongX ? a : u);
					return false;
				}
			}
		}

		// Where it ends: the stand past its last section, on the seam.
		final int count = Math.abs(last - start) + 1;
		final int sections = (count + BAND_DEPTH - 1) / BAND_DEPTH;
		final int farEnd = start + dir * (BAND_DEPTH * sections + BAND_DEPTH - 1);
		final int along = (int) Math.floor((dir == 1 ? farEnd : farEnd + 1) - dir * back);
		final int[] end = alongX ? new int[] {along, bottom, across[1]} : new int[] {across[1], bottom, along};
		final JsonArray list = new JsonArray();

		for (int[] target : targets) {
			claim(region, plan, bot, key(target[0], target[1], target[2]));
			list.add(position(target[0], target[1], target[2]));
		}

		final JsonObject a = new JsonObject();
		a.addProperty("action", "band");
		a.add("from", alongX ? position(start, bottom, across[0]) : position(across[0], bottom, start));
		a.add("to", alongX ? position(last, top, across[1]) : position(across[1], top, last));
		// Not the primitive's: what it breaks, for the claims and the plan ahead (see send).
		a.add("targets", list);
		a.add("end", position(end[0], end[1], end[2]));
		plan.queue.add(a);
		plan.picked = true;
		plan.broke = targets.get(targets.size() - 1);
		plan.dug += targets.size();
		plan.doing = "band " + c + ": " + targets.size() + " in " + count + " slices from " + start;
		return true;
	}

	/** How far back along from a block's near face the eyes on a band's seam reach it, the worst of its heights (actions.js breakBand's back). */
	private static double bandBack(int bottom) {
		final double eyes = bottom + EYES;
		double back = Double.MAX_VALUE;

		for (int h = 1; h <= LAYER; h++) {
			final double dy = Math.max(0, Math.max(bottom + h - 1 - eyes, eyes - (bottom + h)));
			back = Math.min(back, Math.sqrt(REACH * REACH - dy * dy));
		}

		return back;
	}

	/** Whether the block is broken at once with the bot's best tool for it; null when its state was missing (failed). */
	private Boolean instant(String bot, Plan plan, int[] block) {
		final BlockState state = state(bot, plan, block[0], block[1], block[2]);

		if (state == null) {
			return null;
		}

		final float hardness = state.getBlock().getHardness(state, null, null);
		return hardness == 0 || speed(bestTool(bot, state), state) >= hardness;
	}

	/** The band starts across (low..high), 2 apart from low, the last 1 wide on an odd side; from low on, or from high on. */
	private static List<Integer> bandStarts(int low, int high, boolean lowSide) {
		final List<Integer> starts = new ArrayList<Integer>();

		for (int c = low; c <= high; c += 2) {
			starts.add(c);
		}

		if (!lowSide) {
			Collections.reverse(starts);
		}

		return starts;
	}

	/** The columns across of the line starting at c along x (alongX) or z: c and the one after it, in the area. */
	private static int[] columns(Area area, boolean alongX, int c) {
		final int high = alongX ? area.getMaxZ() : area.getMaxX();
		return c + 1 <= high ? new int[] {c, c + 1} : new int[] {c};
	}

	/** The other bots on a line overlapping the one starting at c (axis: 1 along x else 0, see Region.bands). */
	private static List<String> others(String bot, Region region, int axis, int c) {
		final List<String> out = new ArrayList<String>();

		for (Map.Entry<String, int[]> other : region.bands.entrySet()) {
			if (!other.getKey().equals(bot) && other.getValue()[0] == axis && Math.abs(other.getValue()[1] - c) <= 1) {
				out.add(other.getKey());
			}
		}

		return out;
	}

	/** The break_pac of the targets ending at end queued for the bot, them claimed; doing what, for Plan.doing. */
	private void queue(String bot, Plan plan, List<int[]> targets, int[] end, String what) {
		final JsonArray list = new JsonArray();

		for (int[] target : targets) {
			claim(plan.region, plan, bot, key(target[0], target[1], target[2]));
			list.add(position(target[0], target[1], target[2]));
		}

		final JsonObject a = new JsonObject();
		a.addProperty("action", "break_pac");
		a.add("targets", list);
		a.add("end", position(end[0], end[1], end[2]));
		plan.queue.add(a);
		plan.picked = true;
		plan.broke = targets.get(targets.size() - 1);
		plan.dug += targets.size();
		plan.doing = what + ": " + targets.size() + " from " + at(end[0], end[1], end[2]);
	}

	/**
	 * The blocks of the column at x, z of the layer (bottom..top) to break, bottom up, into blocks: not
	 * air, not left out (see leftOut). False with one a pac does not break: a liquid, one that holds one
	 * back (see dam), another bot's (see claimed), one a bot stands on (see standsOn). Null when a state
	 * was missing or one does not break (failed).
	 */
	private Boolean column(String bot, Plan plan, int x, int z, int bottom, int top, List<int[]> blocks) {
		final Region region = plan.region;
		final Set<Long> own = region.claims.get(bot);

		for (int y = bottom; y <= top; y++) {
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

			final long key = key(x, y, z);

			if (liquid(state) || region.claimed(key) && (own == null || !own.contains(key)) || standsOn(bot, region, x, y, z)) {
				return false;
			}

			final int[] dam = dam(bot, plan, x, y, z);

			if (dam == null) {
				return null;
			}

			if (dam != NONE) {
				return false;
			}

			if (!breakable(bot, plan, state, x, y, z)) {
				return null;
			}

			blocks.add(new int[] {x, y, z});
		}

		return true;
	}

	/**
	 * Where a pac into the band c's slice at start ends (dir: the way along the band it digs): a spot of
	 * the slice or one before it in the band, else one next to it across, the nearest to the
	 * bot; one walked to (see walkable), no other bot's (see taken), no staircase's 2x2 but over its
	 * step, nothing that falls over it, one out of the column nothing left over the head to top. Null with none, or when it failed.
	 */
	private int[] entry(String bot, Plan plan, boolean alongX, int c, int start, int dir, int y, int top, Set<Long> walkable) {
		final Region region = plan.region;
		final int[] across = columns(plan.area, alongX, c);
		final int lo = across[0];
		final int hi = across[across.length - 1];
		// Along, across: the start slice's first.
		final int[][] spots = across.length == 1
			? new int[][] {{start, lo}, {start - dir, lo}, {start, lo - 1}, {start, hi + 1}}
			: new int[][] {{start, lo}, {start, hi}, {start - dir, lo}, {start - dir, hi}, {start, lo - 1}, {start, hi + 1}};
		int[] best = null;
		double bestBot = Double.MAX_VALUE;
		boolean bestIn = false;

		for (int i = 0; i < spots.length; i++) {
			// In the band (on its seam once there, see pac) over one next to it across.
			final boolean in = i < spots.length - 2;

			if (bestIn && !in) {
				break;
			}

			final int x = alongX ? spots[i][0] : spots[i][1];
			final int z = alongX ? spots[i][1] : spots[i][0];

			if (!walkable.contains(key(x, y, z))) {
				turnDown(plan, "entry spot not walked to");
				continue;
			}

			if (region.inStair(x, z) && !region.step(x, y - 1, z)) {
				turnDown(plan, "entry spot in a staircase");
				continue;
			}

			if (taken(bot, region, x, y, z)) {
				turnDown(plan, "entry spot taken");
				continue;
			}

			final Boolean falls = falls(bot, plan, x, y + 2, z);

			if (falls == null) {
				return null;
			}

			if (falls) {
				turnDown(plan, "entry spot with sand or gravel over it");
				continue;
			}

			// In the slice before it: the whole of that one open across, its other column hiding the slice from there otherwise.
			if (in && spots[i][0] != start) {
				boolean open = true;

				for (int a : across) {
					for (int up = y; up <= top && open; up++) {
						final BlockState state = state(bot, plan, alongX ? spots[i][0] : a, up, alongX ? a : spots[i][0]);

						if (state == null) {
							return null;
						}

						open = air(state);
					}
				}

				if (!open) {
					turnDown(plan, "entry spot before the slice not open");
					continue;
				}
			}

			// Out of the slice: nothing left over the head, hiding the slice's top from under it.
			boolean clear = true;

			for (int up = y + 2; i >= across.length && up <= top && clear; up++) {
				final BlockState state = state(bot, plan, x, up, z);

				if (state == null) {
					return null;
				}

				clear = air(state);
			}

			if (!clear) {
				turnDown(plan, "entry spot with blocks left over its head");
				continue;
			}

			final double bx = x + 0.5 - plan.position[0];
			final double bz = z + 0.5 - plan.position[2];

			if (bx * bx + bz * bz < bestBot) {
				best = new int[] {x, y, z};
				bestBot = bx * bx + bz * bz;
				bestIn = in;
			}
		}

		return best;
	}

	/** Whether a band's failure lists a block that falls in the way of its walk (see actions.js breakBand). */
	private static boolean fallenRound(String message) {
		return message.contains("round its feet: ") && (message.contains(" minecraft:sand (block)") || message.contains(" minecraft:gravel (block)"));
	}

	private static boolean fallenInTheWay(String message) {
		final int way = message.indexOf("; walking to ");
		return way >= 0 && (message.indexOf("minecraft:sand at ", way) >= 0 || message.indexOf("minecraft:gravel at ", way) >= 0);
	}

	/** Whether the block is in reach from anywhere in the block end the feet are in: from its corners, the farthest a distance to a box gets over it. */
	private static boolean reachesFrom(int[] end, int[] cell) {
		for (int dz = 0; dz <= 1; dz++) {
			for (int dx = 0; dx <= 1; dx++) {
				if (!reaches(end[0] + dx, end[1] + EYES, end[2] + dz, cell)) {
					return false;
				}
			}
		}

		return true;
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
	 * Whether the block is in reach of the bot's eyes (REACH to its nearest point): from its position;
	 * planned ahead (see ahead), from anywhere in its feet's block, the worst of it.
	 */
	private static boolean reaches(Plan plan, int[] cell) {
		if (!plan.ahead) {
			return reaches(plan.position[0], plan.position[1] + EYES, plan.position[2], cell);
		}

		return reachesFrom(plan.feet, cell);
	}

	private static boolean reaches(double ex, double ey, double ez, int[] cell) {
		final double nx = Math.max(cell[0], Math.min(ex, cell[0] + 1)) - ex;
		final double ny = Math.max(cell[1], Math.min(ey, cell[1] + 1)) - ey;
		final double nz = Math.max(cell[2], Math.min(ez, cell[2] + 1)) - ez;
		return nx * nx + ny * ny + nz * nz <= REACH * REACH;
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

	/** Whether a primitive the plan sent ends at the block. */
	private static boolean endsAt(Plan plan, int x, int y, int z) {
		for (Sent one : plan.sent) {
			if (one.end != null && one.end[0] == x && one.end[1] == y && one.end[2] == z) {
				return true;
			}
		}

		return false;
	}

	/**
	 * The spots of its layer (bottom y) in the area the bot stands on (air at its feet and head, solid under them)
	 * walked to from its feet one side to the next, by key(); its feet's block among them. In a staircase's
	 * 2x2 too, the ones over what is not its step: the layer is seen and walked to from there as the bot
	 * gets to it, though they are no end to stand on. Null when a state was missing (failed).
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
		final int[] on = stepTop(plan, feet - 1);
		final BlockState onStep = state(bot, plan, on[0], on[1] - 1, on[2]);
		final BlockState onFeet = onStep == null ? null : state(bot, plan, on[0], on[1], on[2]);
		final BlockState onHead = onFeet == null ? null : state(bot, plan, on[0], on[1] + 1, on[2]);

		if (onHead == null) {
			return false;
		}

		// Broken on the way to the step under it, open to it (break_wsrpa), or where it stands in its
		// 2x2: not from a spot of its own, crouched over the staircase, a walk on after.
		final int[] end = solid(onStep) && air(onFeet) && air(onHead) ? on : in(plan.region.stairs[plan.corner], plan.feet[0], plan.feet[2]) ? plan.feet : null;

		for (int y = feet + 1; y >= feet - 1; y--) {
			if (y >= active - 1 && carvable(plan, y)) {
				final Boolean queued = carve(bot, plan, y, end, -1, false);

				if (queued == null || queued) {
					return queued != null;
				}
			}
		}

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

		// The steps under it carved (another bot's, or its own early): straight to the lowest of them, not a walk a step.
		int[] to = next;

		for (int y = feet - 3; y >= active - 1 && carvable(plan, y); y--) {
			final Boolean done = carved(bot, plan, y, y < active);

			if (done == null) {
				return false;
			}

			if (!done) {
				break;
			}

			to = stepTop(plan, y);
		}

		return walk(bot, plan, to);
	}

	/**
	 * Whether another bot of the region is on the bot's staircase over the active layer, going down it
	 * (carving it, a step a bot): not one gone down early, or digging its way down (see drop).
	 */
	private boolean contended(String bot, Plan plan) {
		for (Map.Entry<String, Plan> other : plans.entrySet()) {
			final Plan them = other.getValue();

			if (!other.getKey().equals(bot) && them.region == plan.region && them.corner == plan.corner && them.feet != null && them.feet[1] > plan.region.active && them.early == null && them.drop == null) {
				return true;
			}
		}

		return false;
	}

	/**
	 * Down into the active layer from over it, its staircase another bot's meanwhile (see contended),
	 * not waited on: at an end of a band no other bot has (see band), the nearest, the column there
	 * walked to on the floor over the layer, then the block under its feet dug from on it, one at a time
	 * down the column to the layer's bottom, a drop onto the next each; the band its own, followed from
	 * there. The column safe (see droppable). Each drop 3 at most (no fall damage): with the blocks under
	 * the top one dug by the others, it is not started. True with a primitive queued or waiting, false
	 * with none (not safe, or not needed: its staircase then), null when it failed.
	 */
	private Boolean drop(String bot, Plan plan, int layer) {
		final Region region = plan.region;
		final int feet = plan.feet[1];

		if (plan.drop != null && plan.dropOf != layer) {
			plan.drop = null;
		}

		if (plan.drop == null) {
			if (feet != region.top + 1 || !contended(bot, plan)) {
				return Boolean.FALSE;
			}

			if (region.bandsOf != layer) {
				region.bands.clear();
				region.bandsOf = layer;
			}

			final Area area = plan.area;
			final int low = region.alongX ? area.getMinZ() : area.getMinX();
			final int high = region.alongX ? area.getMaxZ() : area.getMaxX();
			final int first = region.alongX ? area.getMinX() : area.getMinZ();
			final int last = region.alongX ? area.getMaxX() : area.getMaxZ();
			final Set<Long> walkable = walkable(bot, plan, feet);

			if (walkable == null) {
				return null;
			}

			int[] best = null;
			double bestDistance = Double.MAX_VALUE;

			for (int c : bandStarts(low, high, true)) {
				if (!others(bot, region, region.alongX ? 1 : 0, c).isEmpty()) {
					continue;
				}

				for (int a : columns(area, region.alongX, c)) {
					for (int dir = 1; dir >= -1; dir -= 2) {
						// The band's end out of the staircases' 2x2.
						int along = dir == 1 ? first : last;

						while (along >= first && along <= last && (region.alongX ? region.inStair(along, a) : region.inStair(a, along))) {
							along += dir;
						}

						final int x = region.alongX ? along : a;
						final int z = region.alongX ? a : along;
						final double distance = Math.hypot(x - plan.feet[0], z - plan.feet[2]);

						if (along < first || along > last || distance >= bestDistance || !walkable.contains(key(x, feet, z))) {
							continue;
						}

						final Boolean safe = droppable(bot, plan, x, z, layer);

						if (safe == null) {
							return null;
						}

						if (safe) {
							best = new int[] {x, z, c};
							bestDistance = distance;
						}
					}
				}
			}

			if (best == null) {
				return Boolean.FALSE;
			}

			plan.drop = new int[] {best[0], best[1]};
			plan.dropOf = layer;
			region.bands.put(bot, new int[] {region.alongX ? 1 : 0, best[2]});
		}

		final int x = plan.drop[0];
		final int z = plan.drop[1];

		if (plan.feet[0] != x || plan.feet[2] != z) {
			if (feet != region.top + 1) {
				fail(bot, plan, plan.listener, "quarrify: expected " + bot + " digging its way down at " + at(x, region.top + 1, z) + " on the floor over the layer " + layer + " or in that column, found it at " + at(plan.feet[0], feet, plan.feet[2]));
				return null;
			}

			// To the band's end over the layer, walked on its top.
			final JsonObject a = new JsonObject();
			a.addProperty("action", "goto");
			a.add("spot", position(x, feet, z));
			plan.queue.add(a);
			plan.doing = "to " + at(x, feet, z) + ", to dig its way down into the layer";
			return Boolean.TRUE;
		}

		// What it lands on once the block under its feet is gone (or now, that one gone already): the first solid under that one.
		int land = feet - 2;

		for (; land >= layer - 1; land--) {
			final BlockState there = state(bot, plan, x, land, z);

			if (there == null) {
				return null;
			}

			if (solid(there)) {
				break;
			}
		}

		if (land < layer - 1 || feet - land - 1 > 3) {
			// On the floor over the layer still: its staircase then.
			if (feet == region.top + 1) {
				plan.drop = null;
				region.bands.remove(bot);
				return Boolean.FALSE;
			}

			fail(bot, plan, plan.listener, "quarrify: expected " + bot + " digging its way down at " + at(x, feet, z) + " to land at most 3 under its feet, on the floor of the layer " + layer + " at the lowest, found " + (land < layer - 1 ? "nothing solid down to it" : "the first solid at " + land));
			return null;
		}

		final int[] under = {x, feet - 1, z};
		final BlockState there = state(bot, plan, under[0], under[1], under[2]);

		if (there == null) {
			return null;
		}

		if (!air(there)) {
			if (!claim(bot, plan, under)) {
				return Boolean.TRUE;
			}

			if (!dig(bot, plan, under, null)) {
				return null;
			}

			plan.doing = "down into the layer, dig " + at(under[0], under[1], under[2]);
			return Boolean.TRUE;
		}

		// Gone already (the others' digs, or its hitbox held up by the block next to it): down to what it lands on.
		final JsonObject a = new JsonObject();
		a.addProperty("action", "goto");
		a.add("spot", position(x, land + 1, z));
		plan.queue.add(a);
		plan.doing = "down into the layer, to " + at(x, land + 1, z);
		return Boolean.TRUE;
	}

	/**
	 * Whether the column is safe to dig its way down into the layer by (see drop): its blocks from the
	 * layer's top to its bottom in the area, out of the staircases' 2x2, none left out, none a liquid or
	 * holding one back (see dam), none that does not break, another bot's or one stands on; its top solid
	 * (stood on to start) and the floor under the layer's bottom too. Null when a state was missing (failed).
	 */
	private Boolean droppable(String bot, Plan plan, int x, int z, int layer) {
		final Region region = plan.region;

		if (region.inStair(x, z)) {
			return Boolean.FALSE;
		}

		for (int y = region.top; y >= layer; y--) {
			final BlockState there = state(bot, plan, x, y, z);
			final int[] dam = there == null ? null : dam(bot, plan, x, y, z);

			if (dam == null) {
				return null;
			}

			if (!plan.area.contains(x, y, z) || y == region.top && !solid(there) || leftOut(region, x, y, z) || liquid(there) || dam != NONE || there.getBlock().getHardness(there, null, null) < 0 || region.claimed(key(x, y, z)) || standsOn(bot, region, x, y, z)) {
				return Boolean.FALSE;
			}
		}

		final BlockState floor = state(bot, plan, x, layer - 1, z);

		if (floor == null) {
			return null;
		}

		return solid(floor);
	}

	/**
	 * Whether the layer of the bot's staircase is carved as carve leaves it: its step solid, not one that
	 * falls, no support of it left; its three other blocks gone, but with stepOnly. Null when a state was
	 * missing (failed).
	 */
	private Boolean carved(String bot, Plan plan, int y, boolean stepOnly) {
		final int[][] stair = plan.region.stairs[plan.corner];
		final int k = stepIndex(plan.area, y);
		final BlockState step = state(bot, plan, stair[k][0], y, stair[k][1]);

		if (step == null) {
			return null;
		}

		if (!solid(step) || step.getBlock() instanceof FallingBlock) {
			return false;
		}

		for (int[] support : plan.supports) {
			if (support[3] == y) {
				return false;
			}
		}

		if (stepOnly) {
			return true;
		}

		for (int c = 0; c < 4; c++) {
			if (c == k) {
				continue;
			}

			final BlockState there = state(bot, plan, stair[c][0], y, stair[c][1]);

			if (there == null) {
				return null;
			}

			if (!air(there) && !leftOut(plan.region, stair[c][0], y, stair[c][1])) {
				return false;
			}
		}

		return true;
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

			// Another bot on it (the active layer's floor, carved early): dug once it is off (see wake).
			if (standsOn(bot, plan.region, cell[0], cell[1], cell[2])) {
				plan.region.waiting.add(bot);
				plan.doing = "waiting for the bots on " + at(cell[0], cell[1], cell[2]);
				return Boolean.TRUE;
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
		plan.claiming.add(key);
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

	/** The block broken from a spot it is seen from (break_wsr), with end broken on the way there (break_wsrpa). False when it failed. */
	private boolean dig(String bot, Plan plan, int[] cell, int[] end) {
		// A support at end other than the block (see place), or end on the block (a step that falls, dug
		// to place again): broken from where it stands.
		if (end != null && (!at(end, cell) && leftOut(plan.region, end[0], end[1], end[2]) || end[0] == cell[0] && end[1] == cell[1] + 1 && end[2] == cell[2])) {
			end = null;
		}

		return dig(bot, plan, cell, end, true);
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

		if (!breakable(bot, plan, block, x, y, z)) {
			return false;
		}

		final float hardness = block.getBlock().getHardness(block, null, null);
		final ItemStack stack = bestTool(bot, block);
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

	/** Whether the bot breaks the block, and gets it (an item in its inventory that harvests it); failed when not. */
	private boolean breakable(String bot, Plan plan, BlockState block, int x, int y, int z) {
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

		if (hardness > 0 && !harvests(bestTool(bot, block), block)) {
			fail(bot, plan, plan.listener, "quarrify: expected an item in the inventory of " + bot + " that harvests " + name + " at " + at(x, y, z) + ", found none");
			return false;
		}

		return true;
	}

	/** A block it may place put at the spot from one it is seen from (place_wsr), with end placed on the way there (place_wsrpa). */
	private boolean place(String bot, Plan plan, int[] spot, int[] end) {
		// A support at end, its own or another bot's (one over a step in the air, end its top): placed
		// from where it stands, end walked to once the support is broken.
		if (end != null && leftOut(plan.region, end[0], end[1], end[2])) {
			end = null;
		}

		plan.queue.add(search(plan, end == null ? "place_wsr" : "place_wsrpa", spot, end));
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
				for (int[] target : one.layer ? one.targets : new ArrayList<int[]>()) {
					if (target[0] == x && target[1] == y && target[2] == z) {
						return Blocks.AIR.getDefaultState();
					}
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
		plan.why = null;
		if (plan.sent.isEmpty()) {
			plan.starting = true;
		}

		final List<int[]> targets = new ArrayList<int[]>();

		if (a.has("target")) {
			targets.add(new int[] {a.getAsJsonObject("target").get("x").getAsInt(), a.getAsJsonObject("target").get("y").getAsInt(), a.getAsJsonObject("target").get("z").getAsInt()});
		}

		if (a.has("targets")) {
			for (JsonElement one : a.getAsJsonArray("targets")) {
				targets.add(new int[] {one.getAsJsonObject().get("x").getAsInt(), one.getAsJsonObject().get("y").getAsInt(), one.getAsJsonObject().get("z").getAsInt()});
			}
		}

		// A goto's spot is where it ends too.
		final String to = a.has("end") ? "end" : a.has("spot") ? "spot" : null;
		final int[] end = to != null ? new int[] {a.getAsJsonObject(to).get("x").getAsInt(), a.getAsJsonObject(to).get("y").getAsInt(), a.getAsJsonObject(to).get("z").getAsInt()} : null;
		// With targets, its own claims only: the ones of the primitives queued after it (a pac's other half) stay theirs.
		final Set<Long> claims = new HashSet<Long>();

		if (a.has("targets")) {
			for (int[] target : targets) {
				final long key = key(target[0], target[1], target[2]);

				if (plan.claiming.remove(key)) {
					claims.add(key);
				}
			}
		} else {
			claims.addAll(plan.claiming);
			plan.claiming.clear();
		}

		plan.sent.add(new Sent(end, targets, claims, plan.picked && !targets.isEmpty()));
		plan.picked = false;

		if (plan.waited != null) {
			System.out.println("[quarrify trace] " + bot + " waited " + (System.nanoTime() - plan.waitedSince) / 1000000 + " ms: " + plan.waited);
			plan.waited = null;
		}

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

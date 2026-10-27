package net.mapmcbot.bot;

/**
 * What a bot's wsr primitive (break_wsr, place_wsrpa...) shows as it goes, from its VIS frames: its
 * target, the phase it is in, where it aims, the spot it goes to stand in and the walk there, its end,
 * the stretch it breaks or places walking on; a failed one stays, with why, until the next primitive.
 * Blocks are {x, y, z}, points {x, y, z} anywhere in theirs.
 *
 * Immutable: a new one comes with each change.
 */
public final class BotVis {
	/** The faces by the index the fleet sends (lib/util.js FACES): bottom, top, east, west, south, north. */
	public static final int[][] FACES = {{0, -1, 0}, {0, 1, 0}, {1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}};

	public final boolean place;
	public final int[] target;
	public final String phase;
	/** Why it failed, empty while it has not. */
	public final String failure;
	/** The eyes it aims from, null before it aims. */
	public final double[] eyes;
	public final double[] aimPoint;
	/** The point the look provider's miss goes off from, aimPoint where it lands. */
	public final double[] aimRay;
	/** How far off aimRay the miss goes each side on the face, the region it clicks safely: {min x, y, z, max x, y, z}. */
	public final double[] aimBox;
	/** The block it aims at: the target for a break, the one placed against for a place. */
	public final int[] aimBlock;
	/** The face of aimBlock aimed at, {x, y, z} out of it. */
	public final int[] aimFace;
	/** The head turned there looks at something else (checkLook): aimed again. */
	public final boolean missed;
	/** The block it goes to stand in, null when it stays where it is. */
	public final int[] spot;
	/** Where in spot its feet go, null until it steps there. */
	public final double[] feet;
	public final boolean crouch;
	/** Where it walks to after, null for none. */
	public final int[] end;
	/** As far as it walks toward end first, end having no path yet (break_wsrp and place_wsrp); null for none. */
	public final int[] partial;
	/** The walk to spot it found, its moves' blocks. */
	public final int[][] walk;
	/** The stretch it breaks or places walking on (break_wsrpa and place_wsrpa), its moves' blocks. */
	public final int[][] run;
	/** The block of the stretch it took over from the pathfinder in, null while it has not. */
	public final int[] takeover;

	public BotVis(boolean place, int[] target, String phase, String failure, double[] eyes, double[] aimPoint, double[] aimRay, double[] aimBox, int[] aimBlock, int[] aimFace, boolean missed,
			int[] spot, double[] feet, boolean crouch, int[] end, int[] partial, int[][] walk, int[][] run, int[] takeover) {
		this.place = place;
		this.target = target;
		this.phase = phase;
		this.failure = failure;
		this.eyes = eyes;
		this.aimPoint = aimPoint;
		this.aimRay = aimRay;
		this.aimBox = aimBox;
		this.aimBlock = aimBlock;
		this.aimFace = aimFace;
		this.missed = missed;
		this.spot = spot;
		this.feet = feet;
		this.crouch = crouch;
		this.end = end;
		this.partial = partial;
		this.walk = walk;
		this.run = run;
		this.takeover = takeover;
	}

	public boolean failed() {
		return !failure.isEmpty();
	}
}

package net.mapmcbot.debug;

import com.google.gson.JsonObject;
import net.mapmcbot.bot.BotRegistry;
import net.mapmcbot.bot.BotStatus;
import net.mapmcbot.client.MapMcBotClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * Debug only, qtest3's run (see AutoStart): a bot digs its strip (minX..maxX, minY..maxY,
 * minZ..maxZ) in a straight line along x, a band at a time, its sections broken in its own style
 * (see prims/band.js BAND_STYLES).
 * Each band is planned from what is left of the strip as the one before ends: from the first slice
 * along x with a block in it (a slice: the strip's blocks at one x) on, LENGTH slices at most, up to
 * the last one with a block, the whole width and height; sent as a band primitive. A band failed ends
 * the bot's run with it told, its style's result; the strip dug out, its time told.
 */
final class BandTest implements BotRegistry.StateListener {
	/** The most slices a band takes: whole sections of the band primitive's 2. */
	private static final int LENGTH = 8;
	private final String bot;
	private final int minX;
	private final int maxX;
	private final int minY;
	private final int maxY;
	private final int minZ;
	private final int maxZ;
	/** A band sent and not yet seen going: an idle before that is the end of the one before it. */
	private boolean starting;
	private boolean over;
	private long started;
	private int bands;

	BandTest(String bot, int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
		this.bot = bot;
		this.minX = minX;
		this.maxX = maxX;
		this.minY = minY;
		this.maxY = maxY;
		this.minZ = minZ;
		this.maxZ = maxZ;
	}

	void start() {
		MapMcBotClient.bots().addStateListener(this);
		started = System.nanoTime();
		MinecraftClient.getInstance().getServer().submit(this::next);
	}

	@Override
	public void onState(String from, BotStatus status) {
		if (!from.equals(bot) || over) {
			return;
		}

		switch (status.getKind()) {
			case DOING:
				starting = false;
				break;

			case ERROR:
				over = true;
				System.out.println("[band test] " + bot + " failed on band " + bands + " after " + seconds() + " s: " + status.getMessage());
				break;

			case IDLE:
				if (!starting) {
					MinecraftClient.getInstance().getServer().submit(this::next);
				}
				break;

			default:
				break;
		}
	}

	/**
	 * The next band planned from the world as it is now and sent, or the end told. On the server
	 * thread: its world has every strip's chunks loaded, the client's only those it renders.
	 */
	private void next() {
		final World world = MinecraftClient.getInstance().getServer().getWorld(0);
		Integer first = null;
		Integer last = null;

		for (int x = minX; x <= maxX; x++) {
			if (!empty(world, x)) {
				if (first == null) {
					first = x;
				}

				last = x;
			}
		}

		if (first == null) {
			over = true;
			System.out.println("[band test] " + bot + " dug out in " + seconds() + " s, " + bands + " bands");
			return;
		}

		final int to = Math.min(first + LENGTH - 1, last);
		final JsonObject a = new JsonObject();
		a.addProperty("action", "band");
		a.add("from", position(first, minY, minZ));
		a.add("to", position(to, maxY, maxZ));
		bands++;
		System.out.println("[band test] " + bot + " band x " + first + ".." + to);
		MinecraftClient.getInstance().submit(() -> {
			starting = true;
			MapMcBotClient.bots().action(bot, a.toString());
		});
	}

	private String seconds() {
		return String.format("%.1f", (System.nanoTime() - started) / 1e9);
	}

	/** Whether the slice at x is all air. */
	private boolean empty(World world, int x) {
		for (int y = minY; y <= maxY; y++) {
			for (int z = minZ; z <= maxZ; z++) {
				if (!world.isAir(new BlockPos(x, y, z))) {
					return false;
				}
			}
		}

		return true;
	}

	private static JsonObject position(int x, int y, int z) {
		final JsonObject out = new JsonObject();
		out.addProperty("x", x);
		out.addProperty("y", y);
		out.addProperty("z", z);
		return out;
	}
}

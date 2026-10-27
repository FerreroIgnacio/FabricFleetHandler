package net.mapmcbot.fleet.message;

import static net.mapmcbot.fleet.types.MinecraftProtocolType.*;
import static net.mapmcbot.fleet.types.SocketProtocolType.*;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

import net.mapmcbot.bot.BotListener;
import net.mapmcbot.chunk.ChunkListener;
import net.mapmcbot.profile.ProfileListener;

/** Fleet to mod: the path the bot walks, or none. */
public final class Path extends SocketMessage implements FromFleet {
	public static final int TYPE = 7;

	/** {target x, y, z, node x, y, z, ...}; null when it walks none. */
	public int[] path;

	/** To deserialize. */
	public Path(String bot) {
		super(bot);
	}

	public Path(String bot, int[] path) {
		super(bot);
		this.path = path;
	}

	@Override
	public int type() {
		return TYPE;
	}

	@Override
	public void serialize(DataOutput out) throws IOException {
		BOOL.write(out, path != null);

		if (path != null) {
			I32.write(out, path[0]);
			I32.write(out, path[1]);
			I32.write(out, path[2]);
			I32.write(out, (path.length - 3) / 3);

			for (int i = 3; i < path.length; i++) {
				I32.write(out, path[i]);
			}
		}
	}

	@Override
	public void deserialize(DataInput in) throws IOException {
		if (!BOOL.read(in)) {
			path = null;
			return;
		}

		final int tx = I32.read(in);
		final int ty = I32.read(in);
		final int tz = I32.read(in);
		final int count = I32.read(in);

		if (count < 0) {
			throw new IOException("bad path length " + count);
		}

		path = new int[3 + 3 * count];
		path[0] = tx;
		path[1] = ty;
		path[2] = tz;

		for (int i = 3; i < path.length; i++) {
			path[i] = I32.read(in);
		}
	}

	@Override
	public void dispatch(ChunkListener chunks, BotListener bots, ProfileListener profiles) {
		if (path == null) {
			bots.onPathCleared(bot);
		} else {
			bots.onPath(bot, path);
		}
	}
}

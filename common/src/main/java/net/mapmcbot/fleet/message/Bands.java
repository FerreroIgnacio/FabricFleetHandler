package net.mapmcbot.fleet.message;

import static net.mapmcbot.fleet.types.MinecraftProtocolType.*;
import static net.mapmcbot.fleet.types.SocketProtocolType.*;


import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

import net.mapmcbot.bot.BotListener;
import net.mapmcbot.chunk.ChunkListener;
import net.mapmcbot.profile.ProfileListener;

/** Fleet to mod: the band primitives the bot runs and holds, the running one first (see lib/lifecycle.js showBands); none once there are none. */
public final class Bands extends SocketMessage implements FromFleet {
	public static final int TYPE = 58;

	/** Each {min x, y, z, max x, y, z}. */
	public int[][] boxes;

	/** To deserialize. */
	public Bands(String bot) {
		super(bot);
	}

	public Bands(String bot, int[][] boxes) {
		super(bot);
		this.boxes = boxes;
	}

	@Override
	public int type() {
		return TYPE;
	}

	@Override
	public void serialize(DataOutput out) throws IOException {
		I32.write(out, boxes.length);

		for (int[] box : boxes) {
			BLOCK.write(out, new int[] {box[0], box[1], box[2]});
			BLOCK.write(out, new int[] {box[3], box[4], box[5]});
		}
	}

	@Override
	public void deserialize(DataInput in) throws IOException {
		final int count = I32.read(in);

		if (count < 0) {
			throw new IOException("bad band count " + count);
		}

		boxes = new int[count][];

		for (int i = 0; i < count; i++) {
			final int[] min = BLOCK.read(in);
			final int[] max = BLOCK.read(in);
			boxes[i] = new int[] {min[0], min[1], min[2], max[0], max[1], max[2]};
		}
	}

	@Override
	public void dispatch(ChunkListener chunks, BotListener bots, ProfileListener profiles) {
		bots.onBands(bot, boxes);
	}
}

package net.mapmcbot.fleet.message;

import static net.mapmcbot.fleet.types.MinecraftProtocolType.*;
import static net.mapmcbot.fleet.types.SocketProtocolType.*;


import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

import net.mapmcbot.bot.BotListener;
import net.mapmcbot.bot.BotVis;
import net.mapmcbot.chunk.ChunkListener;
import net.mapmcbot.profile.ProfileListener;

/** Fleet to mod: what a wsr primitive shows now (see BotVis); null once there is nothing to show. */
public final class Vis extends SocketMessage implements FromFleet {
	public static final int TYPE = 54;

	public BotVis vis;

	/** To deserialize. */
	public Vis(String bot) {
		super(bot);
	}

	public Vis(String bot, BotVis vis) {
		super(bot);
		this.vis = vis;
	}

	@Override
	public int type() {
		return TYPE;
	}

	@Override
	public void serialize(DataOutput out) throws IOException {
		BOOL.write(out, vis != null);

		if (vis == null) {
			return;
		}

		BOOL.write(out, vis.place);
		BLOCK.write(out, vis.target);
		STRING.write(out, vis.phase);
		TEXT.write(out, vis.failure);
		BOOL.write(out, vis.eyes != null);

		if (vis.eyes != null) {
			POINT.write(out, vis.eyes);
			POINT.write(out, vis.aimPoint);
			POINT.write(out, vis.aimRay);
			POINT.write(out, new double[] {vis.aimBox[0], vis.aimBox[1], vis.aimBox[2]});
			POINT.write(out, new double[] {vis.aimBox[3], vis.aimBox[4], vis.aimBox[5]});
			BLOCK.write(out, vis.aimBlock);
			U8.write(out, face(vis.aimFace));
			BOOL.write(out, vis.missed);
		}

		OPTIONAL_BLOCK.write(out, vis.spot);

		if (vis.spot != null) {
			BOOL.write(out, vis.feet != null);

			if (vis.feet != null) {
				POINT.write(out, vis.feet);
				BOOL.write(out, vis.crouch);
			}
		}

		OPTIONAL_BLOCK.write(out, vis.end);
		OPTIONAL_BLOCK.write(out, vis.partial);
		BLOCKS.write(out, vis.walk);
		BLOCKS.write(out, vis.run);
		OPTIONAL_BLOCK.write(out, vis.takeover);
	}

	@Override
	public void deserialize(DataInput in) throws IOException {
		if (!BOOL.read(in)) {
			vis = null;
			return;
		}

		final boolean place = BOOL.read(in);
		final int[] target = BLOCK.read(in);
		final String phase = STRING.read(in);
		final String failure = TEXT.read(in);
		final boolean aimed = BOOL.read(in);
		final double[] eyes = aimed ? POINT.read(in) : null;
		final double[] aimPoint = aimed ? POINT.read(in) : null;
		final double[] aimRay = aimed ? POINT.read(in) : null;
		double[] aimBox = null;

		if (aimed) {
			final double[] min = POINT.read(in);
			final double[] max = POINT.read(in);
			aimBox = new double[] {min[0], min[1], min[2], max[0], max[1], max[2]};
		}

		final int[] aimBlock = aimed ? BLOCK.read(in) : null;
		int[] aimFace = null;

		if (aimed) {
			final int face = U8.read(in);

			if (face >= BotVis.FACES.length) {
				throw new IOException("bad face " + face + " in a VIS");
			}

			aimFace = BotVis.FACES[face];
		}

		final boolean missed = aimed && BOOL.read(in);
		final int[] spot = OPTIONAL_BLOCK.read(in);
		final boolean stands = spot != null && BOOL.read(in);
		final double[] feet = stands ? POINT.read(in) : null;
		final boolean crouch = stands && BOOL.read(in);
		final int[] end = OPTIONAL_BLOCK.read(in);
		final int[] partial = OPTIONAL_BLOCK.read(in);
		final int[][] walk = BLOCKS.read(in);
		final int[][] run = BLOCKS.read(in);
		final int[] takeover = OPTIONAL_BLOCK.read(in);
		vis = new BotVis(place, target, phase, failure, eyes, aimPoint, aimRay, aimBox, aimBlock, aimFace, missed, spot, feet, crouch, end, partial, walk, run, takeover);
	}

	@Override
	public void dispatch(ChunkListener chunks, BotListener bots, ProfileListener profiles) {
		bots.onVis(bot, vis);
	}

	/** The face's index in BotVis.FACES. */
	private static int face(int[] face) {
		for (int i = 0; i < BotVis.FACES.length; i++) {
			if (java.util.Arrays.equals(BotVis.FACES[i], face)) {
				return i;
			}
		}

		throw new IllegalArgumentException("no face " + java.util.Arrays.toString(face) + " in BotVis.FACES");
	}
}

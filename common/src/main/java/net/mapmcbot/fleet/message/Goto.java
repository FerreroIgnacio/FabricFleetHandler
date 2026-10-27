package net.mapmcbot.fleet.message;

import static net.mapmcbot.fleet.types.MinecraftProtocolType.*;
import static net.mapmcbot.fleet.types.SocketProtocolType.*;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;


/** Primitive: walk to stand on a block (SPOT), in an area (AREA) or in sight of a block (LOOK). */
public final class Goto extends SocketMessage {
	public static final int TYPE = 23;

	public static final int SPOT = 0;
	public static final int AREA = 1;
	public static final int LOOK = 2;
	
	public int kind;
	/** SPOT, LOOK: {x, y, z}; AREA: {min x, y, z, max x, y, z}. */
	public int[] at;

	/** To deserialize. */
	public Goto(String bot) {
		super(bot);
	}

	public Goto(String bot, int kind, int[] at) {
		super(bot);
		this.kind = kind;
		this.at = at;
	}

	@Override
	public int type() {
		return TYPE;
	}

	@Override
	public void serialize(DataOutput out) throws IOException {
		U8.write(out, kind);

		for (int value : at) {
			I32.write(out, value);
		}
	}

	@Override
	public void deserialize(DataInput in) throws IOException {
		kind = U8.read(in);

		if (kind > LOOK) {
			throw new IOException("bad goto kind " + kind + " for " + bot);
		}

		at = new int[kind == AREA ? 6 : 3];

		for (int i = 0; i < at.length; i++) {
			at[i] = I32.read(in);
		}
	}

	/** To stand on the block. */
	public static Goto spot(String bot, int x, int y, int z) {
		return new Goto(bot, SPOT, new int[] {x, y, z});
	}
}

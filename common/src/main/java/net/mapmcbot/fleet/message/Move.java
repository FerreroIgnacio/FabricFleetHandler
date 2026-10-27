package net.mapmcbot.fleet.message;

import static net.mapmcbot.fleet.types.MinecraftProtocolType.*;
import static net.mapmcbot.fleet.types.SocketProtocolType.*;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;


/** Primitive: move a slot's items to another. */
public final class Move extends SocketMessage {
	public static final int TYPE = 38;

	public int from;
	public int to;

	/** To deserialize. */
	public Move(String bot) {
		super(bot);
	}

	public Move(String bot, int from, int to) {
		super(bot);
		this.from = from;
		this.to = to;
	}

	@Override
	public int type() {
		return TYPE;
	}

	@Override
	public void serialize(DataOutput out) throws IOException {
		I32.write(out, from);
		I32.write(out, to);
	}

	@Override
	public void deserialize(DataInput in) throws IOException {
		from = I32.read(in);
		to = I32.read(in);
	}
}

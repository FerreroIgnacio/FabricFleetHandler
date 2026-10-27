package net.mapmcbot.fleet.message;

import static net.mapmcbot.fleet.types.MinecraftProtocolType.*;
import static net.mapmcbot.fleet.types.SocketProtocolType.*;


import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;


/** Primitive: throw the slots' items. */
public final class Drop extends SocketMessage {
	public static final int TYPE = 44;

	public int[] slots;

	/** To deserialize. */
	public Drop(String bot) {
		super(bot);
	}

	public Drop(String bot, int[] slots) {
		super(bot);
		this.slots = slots;
	}

	@Override
	public int type() {
		return TYPE;
	}

	@Override
	public void serialize(DataOutput out) throws IOException {
		INTS.write(out, slots);
	}

	@Override
	public void deserialize(DataInput in) throws IOException {
		slots = INTS.read(in);
	}
}

package net.mapmcbot.fleet.message;

import static net.mapmcbot.fleet.types.MinecraftProtocolType.*;
import static net.mapmcbot.fleet.types.SocketProtocolType.*;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;


/** Primitive: shift-click the slot. */
public final class ShiftMove extends SocketMessage {
	public static final int TYPE = 45;

	public int slot;

	/** To deserialize. */
	public ShiftMove(String bot) {
		super(bot);
	}

	public ShiftMove(String bot, int slot) {
		super(bot);
		this.slot = slot;
	}

	@Override
	public int type() {
		return TYPE;
	}

	@Override
	public void serialize(DataOutput out) throws IOException {
		I32.write(out, slot);
	}

	@Override
	public void deserialize(DataInput in) throws IOException {
		slot = I32.read(in);
	}
}

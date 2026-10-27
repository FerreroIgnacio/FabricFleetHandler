package net.mapmcbot.fleet.types.socket;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

import net.mapmcbot.fleet.types.SocketProtocolType;

/** A u8, 0 or 1. */
public final class BoolType extends SocketProtocolType<Boolean> {
	@Override
	public Boolean read(DataInput in) throws IOException {
		return in.readBoolean();
	}

	@Override
	public void write(DataOutput out, Boolean value) throws IOException {
		out.writeBoolean(value);
	}
}

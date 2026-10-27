package net.mapmcbot.fleet.types.socket;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

import net.mapmcbot.fleet.types.SocketProtocolType;

public final class I32Type extends SocketProtocolType<Integer> {
	@Override
	public Integer read(DataInput in) throws IOException {
		return in.readInt();
	}

	@Override
	public void write(DataOutput out, Integer value) throws IOException {
		out.writeInt(value);
	}
}

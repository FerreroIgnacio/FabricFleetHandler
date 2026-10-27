package net.mapmcbot.fleet.types.socket;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

import net.mapmcbot.fleet.types.SocketProtocolType;

public final class F64Type extends SocketProtocolType<Double> {
	@Override
	public Double read(DataInput in) throws IOException {
		return in.readDouble();
	}

	@Override
	public void write(DataOutput out, Double value) throws IOException {
		out.writeDouble(value);
	}
}

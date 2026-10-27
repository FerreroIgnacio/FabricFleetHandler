package net.mapmcbot.fleet.types.socket;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

import net.mapmcbot.fleet.types.ProtocolType;
import net.mapmcbot.fleet.types.SocketProtocolType;

/** A u8 present and, if so, the value as its type. */
public final class OptionalType<T> extends SocketProtocolType<T> {
	private final ProtocolType<T> type;

	public OptionalType(ProtocolType<T> type) {
		this.type = type;
	}

	@Override
	public T read(DataInput in) throws IOException {
		return in.readBoolean() ? type.read(in) : null;
	}

	@Override
	public void write(DataOutput out, T value) throws IOException {
		out.writeBoolean(value != null);

		if (value != null) {
			type.write(out, value);
		}
	}
}

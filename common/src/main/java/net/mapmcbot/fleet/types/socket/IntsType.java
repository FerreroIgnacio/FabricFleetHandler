package net.mapmcbot.fleet.types.socket;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

import net.mapmcbot.fleet.types.SocketProtocolType;

/** An i32 count and each i32 (slots). */
public final class IntsType extends SocketProtocolType<int[]> {
	@Override
	public int[] read(DataInput in) throws IOException {
		final int count = in.readInt();

		if (count < 0) {
			throw new IOException("bad int count " + count);
		}

		final int[] values = new int[count];

		for (int i = 0; i < count; i++) {
			values[i] = in.readInt();
		}

		return values;
	}

	@Override
	public void write(DataOutput out, int[] value) throws IOException {
		out.writeInt(value.length);

		for (int one : value) {
			out.writeInt(one);
		}
	}
}

package net.mapmcbot.fleet.types.socket;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

import net.mapmcbot.fleet.types.SocketProtocolType;

import static net.mapmcbot.fleet.types.SocketProtocolType.MAX_FRAME;

/** An i32 length and the bytes. */
public final class BytesType extends SocketProtocolType<byte[]> {
	@Override
	public byte[] read(DataInput in) throws IOException {
		final int length = in.readInt();

		if (length < 0 || length > MAX_FRAME) {
			throw new IOException("bad byte array length " + length);
		}

		final byte[] bytes = new byte[length];
		in.readFully(bytes);
		return bytes;
	}

	@Override
	public void write(DataOutput out, byte[] value) throws IOException {
		out.writeInt(value.length);
		out.write(value);
	}
}

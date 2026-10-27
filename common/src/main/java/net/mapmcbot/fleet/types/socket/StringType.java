package net.mapmcbot.fleet.types.socket;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import net.mapmcbot.fleet.types.SocketProtocolType;

/** A u16 byte length and UTF-8. */
public final class StringType extends SocketProtocolType<String> {
	@Override
	public String read(DataInput in) throws IOException {
		final byte[] bytes = new byte[in.readUnsignedShort()];
		in.readFully(bytes);
		return new String(bytes, StandardCharsets.UTF_8);
	}

	@Override
	public void write(DataOutput out, String value) throws IOException {
		final byte[] bytes = value.getBytes(StandardCharsets.UTF_8);

		if (bytes.length > 0xFFFF) {
			throw new IOException("string of " + bytes.length + " bytes is too long");
		}

		out.writeShort(bytes.length);
		out.write(bytes);
	}
}

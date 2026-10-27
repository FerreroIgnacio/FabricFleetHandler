package net.mapmcbot.fleet.types.socket;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import net.mapmcbot.fleet.types.SocketProtocolType;

import static net.mapmcbot.fleet.types.SocketProtocolType.BYTES;

/** A text as BYTES, UTF-8: one longer than a STRING takes. */
public final class TextType extends SocketProtocolType<String> {
	@Override
	public String read(DataInput in) throws IOException {
		return new String(BYTES.read(in), StandardCharsets.UTF_8);
	}

	@Override
	public void write(DataOutput out, String value) throws IOException {
		BYTES.write(out, value.getBytes(StandardCharsets.UTF_8));
	}
}

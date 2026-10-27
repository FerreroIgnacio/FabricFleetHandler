package net.mapmcbot.fleet.message;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;


/** Relay to mod: the answer to Hello, its commit being the mod's; first of all. */
public final class Welcome extends SocketMessage {
	public static final int TYPE = 13;

	public Welcome(String bot) {
		super(bot);
	}

	@Override
	public int type() {
		return TYPE;
	}

	@Override
	public void serialize(DataOutput out) throws IOException {
	}

	@Override
	public void deserialize(DataInput in) throws IOException {
	}
}

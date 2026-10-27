package net.mapmcbot.fleet.message;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;


/** Mod to fleet: the primitive the bot runs ended and the ones it holds dropped, nothing published (see lib/lifecycle.js run). */
public final class ClearQ extends SocketMessage {
	public static final int TYPE = 55;

	public ClearQ(String bot) {
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

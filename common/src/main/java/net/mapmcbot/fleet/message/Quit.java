package net.mapmcbot.fleet.message;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;


/** Mod to fleet: the bot to leave. */
public final class Quit extends SocketMessage {
	public static final int TYPE = 18;

	public Quit(String bot) {
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

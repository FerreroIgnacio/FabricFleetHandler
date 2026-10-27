package net.mapmcbot.fleet.message;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;


/** Primitive: close the bot's window. */
public final class CloseWindow extends SocketMessage {
	public static final int TYPE = 37;

	public CloseWindow(String bot) {
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

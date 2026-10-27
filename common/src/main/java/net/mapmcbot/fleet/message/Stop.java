package net.mapmcbot.fleet.message;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;


/** Primitive: stop the one it runs, dropping the ones it holds. */
public final class Stop extends SocketMessage {
	public static final int TYPE = 46;

	public Stop(String bot) {
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

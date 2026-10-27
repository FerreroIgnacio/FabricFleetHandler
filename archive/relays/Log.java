package net.mapmcbot.fleet.message;

import static net.mapmcbot.fleet.types.MinecraftProtocolType.*;
import static net.mapmcbot.fleet.types.SocketProtocolType.*;


import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;


/** Relay to mod: what the relay wrote to its output, a chunk of lines, once served. */
public final class Log extends SocketMessage {
	public static final int TYPE = 33;

	public String text;

	/** To deserialize. */
	public Log(String bot) {
		super(bot);
	}

	public Log(String bot, String text) {
		super(bot);
		this.text = text;
	}

	@Override
	public int type() {
		return TYPE;
	}

	@Override
	public void serialize(DataOutput out) throws IOException {
		TEXT.write(out, text);
	}

	@Override
	public void deserialize(DataInput in) throws IOException {
		text = TEXT.read(in);
	}
}

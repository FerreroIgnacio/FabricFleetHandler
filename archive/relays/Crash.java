package net.mapmcbot.fleet.message;

import static net.mapmcbot.fleet.types.MinecraftProtocolType.*;
import static net.mapmcbot.fleet.types.SocketProtocolType.*;


import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;


/** Relay to mod: the relay crashed, with the error text. */
public final class Crash extends SocketMessage {
	public static final int TYPE = 12;

	public String text;

	/** To deserialize. */
	public Crash(String bot) {
		super(bot);
	}

	public Crash(String bot, String text) {
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

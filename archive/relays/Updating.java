package net.mapmcbot.fleet.message;

import static net.mapmcbot.fleet.types.MinecraftProtocolType.*;
import static net.mapmcbot.fleet.types.SocketProtocolType.*;


import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;


/** Relay to mod: the relay is resetting to the mod's commit and restarting; Welcome or UpdateFailed follow. */
public final class Updating extends SocketMessage {
	public static final int TYPE = 14;

	public String text;

	/** To deserialize. */
	public Updating(String bot) {
		super(bot);
	}

	public Updating(String bot, String text) {
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

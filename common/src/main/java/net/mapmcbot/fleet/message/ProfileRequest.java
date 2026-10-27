package net.mapmcbot.fleet.message;

import static net.mapmcbot.fleet.types.MinecraftProtocolType.*;
import static net.mapmcbot.fleet.types.SocketProtocolType.*;


import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;


/** Mod to fleet: a profile, answered with a Profile of the id. */
public final class ProfileRequest extends SocketMessage {
	public static final int TYPE = 21;

	public int id;
	public String reason;

	/** To deserialize. */
	public ProfileRequest(String bot) {
		super(bot);
	}

	public ProfileRequest(String bot, int id, String reason) {
		super(bot);
		this.id = id;
		this.reason = reason;
	}

	@Override
	public int type() {
		return TYPE;
	}

	@Override
	public void serialize(DataOutput out) throws IOException {
		I32.write(out, id);
		STRING.write(out, reason);
	}

	@Override
	public void deserialize(DataInput in) throws IOException {
		id = I32.read(in);
		reason = STRING.read(in);
	}
}

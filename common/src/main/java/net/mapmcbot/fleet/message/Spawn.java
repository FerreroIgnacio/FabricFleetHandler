package net.mapmcbot.fleet.message;

import static net.mapmcbot.fleet.types.MinecraftProtocolType.*;
import static net.mapmcbot.fleet.types.SocketProtocolType.*;


import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;


/** Mod to fleet: a bot to log in to the server. */
public final class Spawn extends SocketMessage {
	public static final int TYPE = 17;

	public String host;
	public int port;

	/** To deserialize. */
	public Spawn(String bot) {
		super(bot);
	}

	public Spawn(String bot, String host, int port) {
		super(bot);
		this.host = host;
		this.port = port;
	}

	@Override
	public int type() {
		return TYPE;
	}

	@Override
	public void serialize(DataOutput out) throws IOException {
		STRING.write(out, host);
		I32.write(out, port);
	}

	@Override
	public void deserialize(DataInput in) throws IOException {
		host = STRING.read(in);
		port = I32.read(in);
	}
}

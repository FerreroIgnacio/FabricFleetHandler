package net.mapmcbot.fleet.message;

import static net.mapmcbot.fleet.types.MinecraftProtocolType.*;
import static net.mapmcbot.fleet.types.SocketProtocolType.*;


import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;


/** Mod to relay: the commit the mod was built at, the first frame; answered with Welcome once it is at it (updating itself first when not) or UpdateFailed. */
public final class Hello extends SocketMessage {
	public static final int TYPE = 22;

	public String commit;

	/** To deserialize. */
	public Hello(String bot) {
		super(bot);
	}

	public Hello(String bot, String commit) {
		super(bot);
		this.commit = commit;
	}

	@Override
	public int type() {
		return TYPE;
	}

	@Override
	public void serialize(DataOutput out) throws IOException {
		STRING.write(out, commit);
	}

	@Override
	public void deserialize(DataInput in) throws IOException {
		commit = STRING.read(in);
	}
}

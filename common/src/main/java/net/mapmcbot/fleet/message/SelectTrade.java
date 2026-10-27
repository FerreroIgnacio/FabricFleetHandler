package net.mapmcbot.fleet.message;

import static net.mapmcbot.fleet.types.MinecraftProtocolType.*;
import static net.mapmcbot.fleet.types.SocketProtocolType.*;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;


/** Primitive: pick the villager trade. */
public final class SelectTrade extends SocketMessage {
	public static final int TYPE = 42;

	public int trade;

	/** To deserialize. */
	public SelectTrade(String bot) {
		super(bot);
	}

	public SelectTrade(String bot, int trade) {
		super(bot);
		this.trade = trade;
	}

	@Override
	public int type() {
		return TYPE;
	}

	@Override
	public void serialize(DataOutput out) throws IOException {
		I32.write(out, trade);
	}

	@Override
	public void deserialize(DataInput in) throws IOException {
		trade = I32.read(in);
	}
}

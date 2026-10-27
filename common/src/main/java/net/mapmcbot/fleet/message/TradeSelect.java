package net.mapmcbot.fleet.message;

import static net.mapmcbot.fleet.types.MinecraftProtocolType.*;
import static net.mapmcbot.fleet.types.SocketProtocolType.*;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;


/** Mod to fleet: the villager trade the bot's open window shows, as the game's arrows pick it. */
public final class TradeSelect extends SocketMessage {
	public static final int TYPE = 29;

	public int trade;

	/** To deserialize. */
	public TradeSelect(String bot) {
		super(bot);
	}

	public TradeSelect(String bot, int trade) {
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

package net.mapmcbot.fleet.message;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

import net.mapmcbot.bot.BotListener;
import net.mapmcbot.chunk.ChunkListener;
import net.mapmcbot.profile.ProfileListener;

/** Fleet to mod: the bot is in the world, ready for orders. */
public final class BotSpawned extends SocketMessage implements FromFleet {
	public static final int TYPE = 8;

	public BotSpawned(String bot) {
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

	@Override
	public void dispatch(ChunkListener chunks, BotListener bots, ProfileListener profiles) {
		bots.onSpawned(bot);
	}
}

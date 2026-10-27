package net.mapmcbot.fleet.message;

import static net.mapmcbot.fleet.types.MinecraftProtocolType.*;
import static net.mapmcbot.fleet.types.SocketProtocolType.*;


import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

import net.mapmcbot.bot.BotListener;
import net.mapmcbot.chunk.ChunkKey;
import net.mapmcbot.chunk.ChunkListener;
import net.mapmcbot.profile.ProfileListener;

/** Fleet to mod: the fleet let the column go. */
public final class ColumnGone extends SocketMessage implements FromFleet {
	public static final int TYPE = 32;

	public ChunkKey key;

	/** To deserialize. */
	public ColumnGone(String bot) {
		super(bot);
	}

	public ColumnGone(String bot, ChunkKey key) {
		super(bot);
		this.key = key;
	}

	@Override
	public int type() {
		return TYPE;
	}

	@Override
	public void serialize(DataOutput out) throws IOException {
		CHUNK_KEY.write(out, key);
	}

	@Override
	public void deserialize(DataInput in) throws IOException {
		key = CHUNK_KEY.read(in);
	}

	@Override
	public void dispatch(ChunkListener chunks, BotListener bots, ProfileListener profiles) {
		bots.onColumnGone(bot, key);
	}
}

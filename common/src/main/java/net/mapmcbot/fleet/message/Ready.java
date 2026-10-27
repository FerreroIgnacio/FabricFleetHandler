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

/** Fleet to mod: the column is in its slot, to be read from now on. */
public final class Ready extends SocketMessage implements FromFleet {
	public static final int TYPE = 2;

	public ChunkKey key;
	public int claim;

	/** To deserialize. */
	public Ready(String bot) {
		super(bot);
	}

	public Ready(String bot, ChunkKey key, int claim) {
		super(bot);
		this.key = key;
		this.claim = claim;
	}

	@Override
	public int type() {
		return TYPE;
	}

	@Override
	public void serialize(DataOutput out) throws IOException {
		CHUNK_KEY.write(out, key);
		I32.write(out, claim);
	}

	@Override
	public void deserialize(DataInput in) throws IOException {
		key = CHUNK_KEY.read(in);
		claim = I32.read(in);
	}

	@Override
	public void dispatch(ChunkListener chunks, BotListener bots, ProfileListener profiles) {
		chunks.onReady(bot, key, claim);
	}
}

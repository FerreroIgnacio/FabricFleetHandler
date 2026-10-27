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

/** Fleet to mod: the fleet's last bot let the column go; its slot waits on a Release. */
public final class Unload extends SocketMessage implements FromFleet {
	public static final int TYPE = 5;

	public ChunkKey key;

	/** To deserialize. */
	public Unload(String bot) {
		super(bot);
	}

	public Unload(String bot, ChunkKey key) {
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
		chunks.onUnload(bot, key);
	}
}

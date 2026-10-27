package net.mapmcbot.fleet.message;

import static net.mapmcbot.fleet.types.MinecraftProtocolType.*;
import static net.mapmcbot.fleet.types.SocketProtocolType.*;


import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

import net.mapmcbot.bot.BotListener;
import net.mapmcbot.chunk.ChunkKey;
import net.mapmcbot.chunk.ChunkRegistry;
import net.mapmcbot.chunk.ChunkListener;
import net.mapmcbot.profile.ProfileListener;

/** Fleet to mod: read the column's snapshot into the free slot; answered with Loaded. */
public final class Load extends SocketMessage implements FromFleet {
	public static final int TYPE = 10;

	public ChunkKey key;
	public int slot;

	/** To deserialize. */
	public Load(String bot) {
		super(bot);
	}

	public Load(String bot, ChunkKey key, int slot) {
		super(bot);
		this.key = key;
		this.slot = slot;
	}

	@Override
	public int type() {
		return TYPE;
	}

	@Override
	public void serialize(DataOutput out) throws IOException {
		CHUNK_KEY.write(out, key);
		I32.write(out, slot);
	}

	@Override
	public void deserialize(DataInput in) throws IOException {
		key = CHUNK_KEY.read(in);
		slot = I32.read(in);

		if (slot < 0 || slot >= ChunkRegistry.COLUMN_SLOTS) {
			throw new IOException(key + " to be loaded into slot " + slot + ", outside 0.." + (ChunkRegistry.COLUMN_SLOTS - 1));
		}
	}

	@Override
	public void dispatch(ChunkListener chunks, BotListener bots, ProfileListener profiles) {
		chunks.onLoadSnapshot(key, slot);
	}
}

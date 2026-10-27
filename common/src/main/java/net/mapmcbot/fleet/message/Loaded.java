package net.mapmcbot.fleet.message;

import static net.mapmcbot.fleet.types.MinecraftProtocolType.*;
import static net.mapmcbot.fleet.types.SocketProtocolType.*;


import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

import net.mapmcbot.chunk.ChunkKey;

/** Mod to fleet: the column's snapshot read into the slot (see Load), with its mcVersion; null when there is none. */
public final class Loaded extends SocketMessage {
	public static final int TYPE = 20;

	public ChunkKey key;
	public int slot;
	public String mcVersion;

	/** To deserialize. */
	public Loaded(String bot) {
		super(bot);
	}

	public Loaded(String bot, ChunkKey key, int slot, String mcVersion) {
		super(bot);
		this.key = key;
		this.slot = slot;
		this.mcVersion = mcVersion;
	}

	@Override
	public int type() {
		return TYPE;
	}

	@Override
	public void serialize(DataOutput out) throws IOException {
		CHUNK_KEY.write(out, key);
		I32.write(out, slot);
		OPTIONAL_STRING.write(out, mcVersion);
	}

	@Override
	public void deserialize(DataInput in) throws IOException {
		key = CHUNK_KEY.read(in);
		slot = I32.read(in);
		mcVersion = OPTIONAL_STRING.read(in);
	}
}

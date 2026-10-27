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

/** Fleet to mod: the fleet's first bot got the column, once for the whole fleet, and loads it into the slot. */
public final class Claim extends SocketMessage implements FromFleet {
	public static final int TYPE = 1;

	public ChunkKey key;
	public int claim;
	public int slot;
	public String mcVersion;

	/** To deserialize. */
	public Claim(String bot) {
		super(bot);
	}

	public Claim(String bot, ChunkKey key, int claim, int slot, String mcVersion) {
		super(bot);
		this.key = key;
		this.claim = claim;
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
		I32.write(out, claim);
		I32.write(out, slot);
		STRING.write(out, mcVersion);
	}

	@Override
	public void deserialize(DataInput in) throws IOException {
		key = CHUNK_KEY.read(in);
		claim = I32.read(in);
		slot = I32.read(in);
		mcVersion = STRING.read(in);

		if (slot < 0 || slot >= ChunkRegistry.COLUMN_SLOTS) {
			throw new IOException(key + " claimed into slot " + slot + ", outside 0.." + (ChunkRegistry.COLUMN_SLOTS - 1));
		}
	}

	@Override
	public void dispatch(ChunkListener chunks, BotListener bots, ProfileListener profiles) {
		chunks.onClaim(bot, key, claim, slot, mcVersion);
	}
}

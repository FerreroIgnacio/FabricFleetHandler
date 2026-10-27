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

/** Fleet to mod: a chest that showed up (its block, "minecraft:chest") or went (empty). */
public final class Chest extends SocketMessage implements FromFleet {
	public static final int TYPE = 31;

	public ChunkKey key;
	public int x;
	public int y;
	public int z;
	public String block;

	/** To deserialize. */
	public Chest(String bot) {
		super(bot);
	}

	public Chest(String bot, ChunkKey key, int x, int y, int z, String block) {
		super(bot);
		this.key = key;
		this.x = x;
		this.y = y;
		this.z = z;
		this.block = block;
	}

	@Override
	public int type() {
		return TYPE;
	}

	@Override
	public void serialize(DataOutput out) throws IOException {
		CHUNK_KEY.write(out, key);
		I32.write(out, x);
		I32.write(out, y);
		I32.write(out, z);
		STRING.write(out, block);
	}

	@Override
	public void deserialize(DataInput in) throws IOException {
		key = CHUNK_KEY.read(in);
		x = I32.read(in);
		y = I32.read(in);
		z = I32.read(in);
		block = STRING.read(in);
	}

	@Override
	public void dispatch(ChunkListener chunks, BotListener bots, ProfileListener profiles) {
		bots.onChest(bot, key, x, y, z, block);
	}
}

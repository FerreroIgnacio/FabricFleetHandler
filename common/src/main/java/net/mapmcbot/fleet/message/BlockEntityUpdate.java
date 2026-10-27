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

/** Fleet to mod: a block entity of the column set (type and NBT) or gone (type null). */
public final class BlockEntityUpdate extends SocketMessage implements FromFleet {
	public static final int TYPE = 4;

	public ChunkKey key;
	public int x;
	public int y;
	public int z;
	/** Null once it is gone. */
	public String entityType;
	public byte[] nbt;

	/** To deserialize. */
	public BlockEntityUpdate(String bot) {
		super(bot);
	}

	public BlockEntityUpdate(String bot, ChunkKey key, int x, int y, int z, String entityType, byte[] nbt) {
		super(bot);
		this.key = key;
		this.x = x;
		this.y = y;
		this.z = z;
		this.entityType = entityType;
		this.nbt = nbt;
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
		BOOL.write(out, entityType != null);

		if (entityType != null) {
			STRING.write(out, entityType);
			BYTES.write(out, nbt);
		}
	}

	@Override
	public void deserialize(DataInput in) throws IOException {
		key = CHUNK_KEY.read(in);
		x = I32.read(in);
		y = I32.read(in);
		z = I32.read(in);

		if (BOOL.read(in)) {
			entityType = STRING.read(in);
			nbt = BYTES.read(in);
		}
	}

	@Override
	public void dispatch(ChunkListener chunks, BotListener bots, ProfileListener profiles) {
		if (entityType != null) {
			chunks.onBlockEntityUpdate(bot, key, x, y, z, entityType, nbt);
		} else {
			chunks.onBlockEntityRemove(bot, key, x, y, z);
		}
	}
}

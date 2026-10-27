package net.mapmcbot.fleet.types;

import net.mapmcbot.bot.BotInventory;
import net.mapmcbot.chunk.ChunkKey;
import net.mapmcbot.fleet.types.minecraft.*;

/** The game's pieces, made of the socket's: chunk keys, blocks, points, items. */
public abstract class MinecraftProtocolType<T> extends ProtocolType<T> {
	/** Server and dimension as STRING, chunk x and z as I32. */
	public static final MinecraftProtocolType<ChunkKey> CHUNK_KEY = new ChunkKeyType();

	/** {x, y, z} as I32. */
	public static final MinecraftProtocolType<int[]> BLOCK = new BlockType();

	/** {x, y, z} as F64. */
	public static final MinecraftProtocolType<double[]> POINT = new PointType();

	/** An i32 count and each BLOCK. */
	public static final MinecraftProtocolType<int[][]> BLOCKS = new BlocksType();

	public static final SocketProtocolType<int[]> OPTIONAL_BLOCK = SocketProtocolType.optional(BLOCK);

	/** An inventory item, null for an empty slot: u8 present, name STRING, count and metadata I32, the NBT as BYTES. */
	public static final MinecraftProtocolType<BotInventory.Item> ITEM = new ItemType();
}

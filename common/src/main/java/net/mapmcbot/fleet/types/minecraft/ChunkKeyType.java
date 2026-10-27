package net.mapmcbot.fleet.types.minecraft;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

import net.mapmcbot.chunk.ChunkKey;
import net.mapmcbot.fleet.types.MinecraftProtocolType;

import static net.mapmcbot.fleet.types.SocketProtocolType.STRING;

/** Server and dimension as STRING, chunk x and z as I32. */
public final class ChunkKeyType extends MinecraftProtocolType<ChunkKey> {
	@Override
	public ChunkKey read(DataInput in) throws IOException {
		return new ChunkKey(STRING.read(in), STRING.read(in), in.readInt(), in.readInt());
	}

	@Override
	public void write(DataOutput out, ChunkKey value) throws IOException {
		STRING.write(out, value.getServer());
		STRING.write(out, value.getDimension());
		out.writeInt(value.getX());
		out.writeInt(value.getZ());
	}
}

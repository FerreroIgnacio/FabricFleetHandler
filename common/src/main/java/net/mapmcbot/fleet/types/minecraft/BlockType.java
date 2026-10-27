package net.mapmcbot.fleet.types.minecraft;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

import net.mapmcbot.fleet.types.MinecraftProtocolType;

/** {x, y, z} as I32. */
public final class BlockType extends MinecraftProtocolType<int[]> {
	@Override
	public int[] read(DataInput in) throws IOException {
		return new int[] {in.readInt(), in.readInt(), in.readInt()};
	}

	@Override
	public void write(DataOutput out, int[] value) throws IOException {
		out.writeInt(value[0]);
		out.writeInt(value[1]);
		out.writeInt(value[2]);
	}
}

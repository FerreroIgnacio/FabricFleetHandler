package net.mapmcbot.fleet.types.minecraft;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

import net.mapmcbot.fleet.types.MinecraftProtocolType;

/** An i32 count and each BLOCK. */
public final class BlocksType extends MinecraftProtocolType<int[][]> {
	@Override
	public int[][] read(DataInput in) throws IOException {
		final int count = in.readInt();

		if (count < 0) {
			throw new IOException("bad block count " + count);
		}

		final int[][] blocks = new int[count][];

		for (int i = 0; i < count; i++) {
			blocks[i] = BLOCK.read(in);
		}

		return blocks;
	}

	@Override
	public void write(DataOutput out, int[][] value) throws IOException {
		out.writeInt(value.length);

		for (int[] block : value) {
			BLOCK.write(out, block);
		}
	}
}

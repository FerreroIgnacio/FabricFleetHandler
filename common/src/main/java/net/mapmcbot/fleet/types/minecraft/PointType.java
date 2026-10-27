package net.mapmcbot.fleet.types.minecraft;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

import net.mapmcbot.fleet.types.MinecraftProtocolType;

/** {x, y, z} as F64. */
public final class PointType extends MinecraftProtocolType<double[]> {
	@Override
	public double[] read(DataInput in) throws IOException {
		return new double[] {in.readDouble(), in.readDouble(), in.readDouble()};
	}

	@Override
	public void write(DataOutput out, double[] value) throws IOException {
		out.writeDouble(value[0]);
		out.writeDouble(value[1]);
		out.writeDouble(value[2]);
	}
}

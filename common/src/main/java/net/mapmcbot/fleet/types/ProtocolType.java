package net.mapmcbot.fleet.types;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

/** A piece messages are made of, big-endian: how a value of it is written and read back. */
public abstract class ProtocolType<T> {
	public abstract T read(DataInput in) throws IOException;

	public abstract void write(DataOutput out, T value) throws IOException;
}

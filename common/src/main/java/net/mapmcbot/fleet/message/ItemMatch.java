package net.mapmcbot.fleet.message;

import static net.mapmcbot.fleet.types.MinecraftProtocolType.*;
import static net.mapmcbot.fleet.types.SocketProtocolType.*;


import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * What an item has to be (see ItemFill, WaitSlot): its name and metadata, null for any; with MATCH
 * the NBT paths that must be the reference's, with IGNORE the ones that may differ.
 */
public final class ItemMatch {
	public static final int NONE = 0;
	public static final int MATCH = 1;
	public static final int IGNORE = 2;

	public final String name;
	public final Integer metadata;
	public final int mode;
	/** The reference's NBT; null with NONE. */
	public final byte[] nbt;
	/** Null with NONE. */
	public final List<String> paths;

	public ItemMatch(String name, Integer metadata, int mode, byte[] nbt, List<String> paths) {
		if (mode != NONE && mode != MATCH && mode != IGNORE) {
			throw new IllegalArgumentException("bad item match mode " + mode);
		}

		if ((mode == NONE) != (nbt == null) || (mode == NONE) != (paths == null)) {
			throw new IllegalArgumentException("an item match takes its NBT and paths with match or ignore, and only then");
		}

		this.name = name;
		this.metadata = metadata;
		this.mode = mode;
		this.nbt = nbt;
		this.paths = paths;
	}

	/** u8 name present, u8 metadata present, u8 mode, the name, i32 metadata; with a mode the NBT as bytes, i32 path count and each path. */
	void write(DataOutput out) throws IOException {
		BOOL.write(out, name != null);
		BOOL.write(out, metadata != null);
		U8.write(out, mode);

		if (name != null) {
			STRING.write(out, name);
		}

		if (metadata != null) {
			I32.write(out, metadata);
		}

		if (mode != NONE) {
			BYTES.write(out, nbt);
			I32.write(out, paths.size());

			for (String path : paths) {
				STRING.write(out, path);
			}
		}
	}

	static ItemMatch read(DataInput in) throws IOException {
		final boolean hasName = BOOL.read(in);
		final boolean hasMetadata = BOOL.read(in);
		final int mode = U8.read(in);
		final String name = hasName ? STRING.read(in) : null;
		final Integer metadata = hasMetadata ? I32.read(in) : null;

		if (mode == NONE) {
			return new ItemMatch(name, metadata, mode, null, null);
		}

		final byte[] nbt = BYTES.read(in);
		final int count = I32.read(in);

		if (count < 0) {
			throw new IOException("bad item match path count " + count);
		}

		final List<String> paths = new ArrayList<String>(count);

		for (int i = 0; i < count; i++) {
			paths.add(STRING.read(in));
		}

		return new ItemMatch(name, metadata, mode, nbt, paths);
	}
}

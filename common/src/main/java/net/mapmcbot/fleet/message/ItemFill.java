package net.mapmcbot.fleet.message;

import static net.mapmcbot.fleet.types.MinecraftProtocolType.*;
import static net.mapmcbot.fleet.types.SocketProtocolType.*;


import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;


/** Primitive: fill the target slots with items matching any of items, from sources (null for any), up to count (null for full). */
public final class ItemFill extends SocketMessage {
	public static final int TYPE = 39;

	public List<ItemMatch> items;
	public int[] targets;
	public int[] sources;
	public Integer count;

	/** To deserialize. */
	public ItemFill(String bot) {
		super(bot);
	}

	public ItemFill(String bot, List<ItemMatch> items, int[] targets, int[] sources, Integer count) {
		super(bot);
		this.items = items;
		this.targets = targets;
		this.sources = sources;
		this.count = count;
	}

	@Override
	public int type() {
		return TYPE;
	}

	@Override
	public void serialize(DataOutput out) throws IOException {
		I32.write(out, items.size());

		for (ItemMatch item : items) {
			item.write(out);
		}

		INTS.write(out, targets);
		BOOL.write(out, sources != null);

		if (sources != null) {
			INTS.write(out, sources);
		}

		OPTIONAL_I32.write(out, count);
	}

	@Override
	public void deserialize(DataInput in) throws IOException {
		final int size = I32.read(in);

		if (size < 0) {
			throw new IOException("bad item match count " + size + " for " + bot);
		}

		items = new ArrayList<ItemMatch>(size);

		for (int i = 0; i < size; i++) {
			items.add(ItemMatch.read(in));
		}

		targets = INTS.read(in);
		sources = BOOL.read(in) ? INTS.read(in) : null;
		count = OPTIONAL_I32.read(in);
	}
}

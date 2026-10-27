package net.mapmcbot.fleet.message;

import static net.mapmcbot.fleet.types.MinecraftProtocolType.*;
import static net.mapmcbot.fleet.types.SocketProtocolType.*;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

/** A slot WaitSlot waits on: at least min of the item (null for any). */
public final class SlotWait {
	public final int slot;
	public final int min;
	public final ItemMatch item;

	public SlotWait(int slot, int min, ItemMatch item) {
		this.slot = slot;
		this.min = min;
		this.item = item;
	}

	void write(DataOutput out) throws IOException {
		I32.write(out, slot);
		I32.write(out, min);
		BOOL.write(out, item != null);

		if (item != null) {
			item.write(out);
		}
	}

	static SlotWait read(DataInput in) throws IOException {
		final int slot = I32.read(in);
		final int min = I32.read(in);
		return new SlotWait(slot, min, BOOL.read(in) ? ItemMatch.read(in) : null);
	}
}

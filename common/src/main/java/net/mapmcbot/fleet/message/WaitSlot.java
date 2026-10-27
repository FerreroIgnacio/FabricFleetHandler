package net.mapmcbot.fleet.message;

import static net.mapmcbot.fleet.types.MinecraftProtocolType.*;
import static net.mapmcbot.fleet.types.SocketProtocolType.*;


import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;


/** Primitive: wait for each slot to hold at least its min of its item. */
public final class WaitSlot extends SocketMessage {
	public static final int TYPE = 40;

	public List<SlotWait> slots;
	/** In ms; null for the bot's own. */
	public Integer deadline;

	/** To deserialize. */
	public WaitSlot(String bot) {
		super(bot);
	}

	public WaitSlot(String bot, List<SlotWait> slots, Integer deadline) {
		super(bot);
		this.slots = slots;
		this.deadline = deadline;
	}

	@Override
	public int type() {
		return TYPE;
	}

	@Override
	public void serialize(DataOutput out) throws IOException {
		I32.write(out, slots.size());

		for (SlotWait slot : slots) {
			slot.write(out);
		}

		OPTIONAL_I32.write(out, deadline);
	}

	@Override
	public void deserialize(DataInput in) throws IOException {
		final int size = I32.read(in);

		if (size < 0) {
			throw new IOException("bad wait slot count " + size + " for " + bot);
		}

		slots = new ArrayList<SlotWait>(size);

		for (int i = 0; i < size; i++) {
			slots.add(SlotWait.read(in));
		}

		deadline = OPTIONAL_I32.read(in);
	}
}

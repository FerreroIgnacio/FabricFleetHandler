package net.mapmcbot.fleet.message;

import static net.mapmcbot.fleet.types.MinecraftProtocolType.*;
import static net.mapmcbot.fleet.types.SocketProtocolType.*;


import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import net.mapmcbot.bot.BotInventory;
import net.mapmcbot.bot.BotListener;
import net.mapmcbot.chunk.ChunkListener;
import net.mapmcbot.profile.ProfileListener;

/** Fleet to mod: the bot's inventory window whole, sent once anything in it changed. */
public final class Inventory extends SocketMessage implements FromFleet {
	public static final int TYPE = 24;

	public BotInventory inventory;

	/** To deserialize. */
	public Inventory(String bot) {
		super(bot);
	}

	public Inventory(String bot, BotInventory inventory) {
		super(bot);
		this.inventory = inventory;
	}

	@Override
	public int type() {
		return TYPE;
	}

	@Override
	public void serialize(DataOutput out) throws IOException {
		U8.write(out, inventory.getHeld());
		I32.write(out, inventory.getLastClick());
		ITEM.write(out, inventory.getCursor());
		I32.write(out, inventory.getSlots().size());

		for (BotInventory.Item item : inventory.getSlots()) {
			ITEM.write(out, item);
		}
	}

	@Override
	public void deserialize(DataInput in) throws IOException {
		final int held = U8.read(in);
		final int lastClick = I32.read(in);
		final BotInventory.Item cursor = ITEM.read(in);
		final int count = I32.read(in);

		if (count < 0 || count > 256) {
			throw new IOException("bad inventory slot count " + count + " for " + bot);
		}

		final List<BotInventory.Item> slots = new ArrayList<BotInventory.Item>(count);

		for (int i = 0; i < count; i++) {
			slots.add(ITEM.read(in));
		}

		inventory = new BotInventory(held, lastClick, cursor, slots);
	}

	@Override
	public void dispatch(ChunkListener chunks, BotListener bots, ProfileListener profiles) {
		bots.onInventory(bot, inventory);
	}
}

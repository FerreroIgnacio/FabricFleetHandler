package net.mapmcbot.fleet.types.minecraft;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

import net.mapmcbot.bot.BotInventory;
import net.mapmcbot.fleet.types.MinecraftProtocolType;

import static net.mapmcbot.fleet.types.SocketProtocolType.STRING;
import static net.mapmcbot.fleet.types.SocketProtocolType.BYTES;

/** An inventory item, null for an empty slot: u8 present, name STRING, count and metadata I32, the NBT as BYTES. */
public final class ItemType extends MinecraftProtocolType<BotInventory.Item> {
	@Override
	public BotInventory.Item read(DataInput in) throws IOException {
		if (!in.readBoolean()) {
			return null;
		}

		return new BotInventory.Item(STRING.read(in), in.readInt(), in.readInt(), BYTES.read(in));
	}

	@Override
	public void write(DataOutput out, BotInventory.Item value) throws IOException {
		out.writeBoolean(value != null);

		if (value != null) {
			STRING.write(out, value.getName());
			out.writeInt(value.getCount());
			out.writeInt(value.getMetadata());
			BYTES.write(out, value.getNbt());
		}
	}
}

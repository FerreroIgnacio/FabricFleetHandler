package net.mapmcbot.fleet.message;

import static net.mapmcbot.fleet.types.MinecraftProtocolType.*;
import static net.mapmcbot.fleet.types.SocketProtocolType.*;


import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;


/** Mod to fleet: the blocks the bot may place, as PlaceBlocks keys them (name@metadata). */
public final class AllowedPlace extends SocketMessage {
	public static final int TYPE = 49;

	public List<String> blocks;

	/** To deserialize. */
	public AllowedPlace(String bot) {
		super(bot);
	}

	public AllowedPlace(String bot, List<String> blocks) {
		super(bot);
		this.blocks = blocks;
	}

	@Override
	public int type() {
		return TYPE;
	}

	@Override
	public void serialize(DataOutput out) throws IOException {
		I32.write(out, blocks.size());

		for (String block : blocks) {
			STRING.write(out, block);
		}
	}

	@Override
	public void deserialize(DataInput in) throws IOException {
		final int count = I32.read(in);

		if (count < 0) {
			throw new IOException("bad block count " + count);
		}

		blocks = new ArrayList<String>(count);

		for (int i = 0; i < count; i++) {
			blocks.add(STRING.read(in));
		}
	}
}

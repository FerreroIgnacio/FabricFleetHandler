package net.mapmcbot.fleet.message;

import static net.mapmcbot.fleet.types.MinecraftProtocolType.*;
import static net.mapmcbot.fleet.types.SocketProtocolType.*;


import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

import net.mapmcbot.bot.BotListener;
import net.mapmcbot.chunk.ChunkListener;
import net.mapmcbot.profile.ProfileListener;

/** Fleet to mod: the name of each state id, once, first of all. */
public final class StateNames extends SocketMessage implements FromFleet {
	public static final int TYPE = 9;

	/** By state id, 0x10000 of them; null for one not named. */
	public String[] names;

	/** To deserialize. */
	public StateNames(String bot) {
		super(bot);
	}

	public StateNames(String bot, String[] names) {
		super(bot);
		this.names = names;
	}

	@Override
	public int type() {
		return TYPE;
	}

	@Override
	public void serialize(DataOutput out) throws IOException {
		int count = 0;

		for (String name : names) {
			count += name == null ? 0 : 1;
		}

		I32.write(out, count);

		for (int id = 0; id < names.length; id++) {
			if (names[id] != null) {
				U16.write(out, id);
				STRING.write(out, names[id]);
			}
		}
	}

	@Override
	public void deserialize(DataInput in) throws IOException {
		final int count = I32.read(in);

		if (count < 0 || count > 0x10000) {
			throw new IOException("bad state name count " + count);
		}

		names = new String[0x10000];

		for (int i = 0; i < count; i++) {
			final int id = U16.read(in);
			final String name = STRING.read(in);

			if (names[id] != null) {
				throw new IOException("state id " + id + " named twice: " + names[id] + ", " + name);
			}

			names[id] = name;
		}
	}

	@Override
	public void dispatch(ChunkListener chunks, BotListener bots, ProfileListener profiles) {
		chunks.onStateNames(names);
	}
}

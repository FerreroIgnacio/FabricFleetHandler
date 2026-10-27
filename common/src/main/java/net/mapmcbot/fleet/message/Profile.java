package net.mapmcbot.fleet.message;

import static net.mapmcbot.fleet.types.MinecraftProtocolType.*;
import static net.mapmcbot.fleet.types.SocketProtocolType.*;


import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

import net.mapmcbot.bot.BotListener;
import net.mapmcbot.chunk.ChunkListener;
import net.mapmcbot.profile.ProfileListener;

/** Fleet to mod: a profile's rows (see bot/profiler.js), for a ProfileRequest of its id (0 when the fleet took it on an event of its own). */
public final class Profile extends SocketMessage implements FromFleet {
	public static final int TYPE = 11;

	public int id;
	public String reason;
	public String text;

	/** To deserialize. */
	public Profile(String bot) {
		super(bot);
	}

	public Profile(String bot, int id, String reason, String text) {
		super(bot);
		this.id = id;
		this.reason = reason;
		this.text = text;
	}

	@Override
	public int type() {
		return TYPE;
	}

	@Override
	public void serialize(DataOutput out) throws IOException {
		I32.write(out, id);
		STRING.write(out, reason);
		TEXT.write(out, text);
	}

	@Override
	public void deserialize(DataInput in) throws IOException {
		id = I32.read(in);
		reason = STRING.read(in);
		text = TEXT.read(in);
	}

	@Override
	public void dispatch(ChunkListener chunks, BotListener bots, ProfileListener profiles) {
		profiles.onProfile(id, reason, text);
	}
}

package net.mapmcbot.fleet.message;

import static net.mapmcbot.fleet.types.MinecraftProtocolType.*;
import static net.mapmcbot.fleet.types.SocketProtocolType.*;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

import net.mapmcbot.bot.BotListener;
import net.mapmcbot.chunk.ChunkListener;
import net.mapmcbot.profile.ProfileListener;

/** Fleet to mod: how many players have the chest there open, sent as its lid moves. */
public final class ChestLid extends SocketMessage implements FromFleet {
	public static final int TYPE = 30;

	public int x;
	public int y;
	public int z;
	public int viewers;

	/** To deserialize. */
	public ChestLid(String bot) {
		super(bot);
	}

	public ChestLid(String bot, int x, int y, int z, int viewers) {
		super(bot);
		this.x = x;
		this.y = y;
		this.z = z;
		this.viewers = viewers;
	}

	@Override
	public int type() {
		return TYPE;
	}

	@Override
	public void serialize(DataOutput out) throws IOException {
		I32.write(out, x);
		I32.write(out, y);
		I32.write(out, z);
		U8.write(out, viewers);
	}

	@Override
	public void deserialize(DataInput in) throws IOException {
		x = I32.read(in);
		y = I32.read(in);
		z = I32.read(in);
		viewers = U8.read(in);
	}

	@Override
	public void dispatch(ChunkListener chunks, BotListener bots, ProfileListener profiles) {
		bots.onChestLid(bot, x, y, z, viewers);
	}
}

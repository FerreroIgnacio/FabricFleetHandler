package net.mapmcbot.fleet.message;

import static net.mapmcbot.fleet.types.MinecraftProtocolType.*;
import static net.mapmcbot.fleet.types.SocketProtocolType.*;


import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

import net.mapmcbot.bot.BotListener;
import net.mapmcbot.bot.BotStatus;
import net.mapmcbot.chunk.ChunkListener;
import net.mapmcbot.profile.ProfileListener;

/** Fleet to mod: what the bot is doing and where it stands then (see BotStatus). */
public final class State extends SocketMessage implements FromFleet {
	public static final int TYPE = 26;

	public BotStatus status;

	/** To deserialize. */
	public State(String bot) {
		super(bot);
	}

	public State(String bot, BotStatus status) {
		super(bot);
		this.status = status;
	}

	@Override
	public int type() {
		return TYPE;
	}

	@Override
	public void serialize(DataOutput out) throws IOException {
		U8.write(out, status.getKind().ordinal());
		STRING.write(out, status.getPrimitive());
		TEXT.write(out, status.getMessage());
		BOOL.write(out, status.hasFeet());

		if (status.hasFeet()) {
			BLOCK.write(out, status.getFeet());
			POINT.write(out, status.getPosition());
			final double[] look = status.getLook();
			F64.write(out, look[0]);
			F64.write(out, look[1]);
		}
	}

	@Override
	public void deserialize(DataInput in) throws IOException {
		final int kind = U8.read(in);
		final String primitive = STRING.read(in);
		final String message = TEXT.read(in);
		final boolean stands = BOOL.read(in);
		final int[] feet = stands ? BLOCK.read(in) : null;
		final double[] position = stands ? POINT.read(in) : null;
		final double[] look = stands ? new double[] {F64.read(in), F64.read(in)} : null;

		if (kind > BotStatus.Kind.values().length - 1) {
			throw new IOException("bad state kind " + kind + " for " + bot);
		}

		status = new BotStatus(BotStatus.Kind.values()[kind], primitive, message, feet, position, look);
	}

	@Override
	public void dispatch(ChunkListener chunks, BotListener bots, ProfileListener profiles) {
		bots.onState(bot, status);
	}
}

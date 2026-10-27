package net.mapmcbot.fleet.message;

import static net.mapmcbot.fleet.types.MinecraftProtocolType.*;
import static net.mapmcbot.fleet.types.SocketProtocolType.*;


import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;


/** Primitive: as BreakWsrp (see actions.js). */
public final class BreakWsrpa extends SocketMessage {
	public static final int TYPE = 52;

	public int[] target;
	public int radius;
	/** The block it ends standing in. */
	public int[] end;
	/** In ms; null for the bot's own. */
	public Integer deadline;
	/** In rad/s; null for the bot's own. */
	public Double turnSpeed;

	/** To deserialize. */
	public BreakWsrpa(String bot) {
		super(bot);
	}

	public BreakWsrpa(String bot, int[] target, int radius, int[] end, Integer deadline, Double turnSpeed) {
		super(bot);
		this.target = target;
		this.radius = radius;
		this.end = end;
		this.deadline = deadline;
		this.turnSpeed = turnSpeed;
	}

	@Override
	public int type() {
		return TYPE;
	}

	@Override
	public void serialize(DataOutput out) throws IOException {
		BLOCK.write(out, target);
		I32.write(out, radius);
		BLOCK.write(out, end);
		OPTIONAL_I32.write(out, deadline);
		OPTIONAL_F64.write(out, turnSpeed);
	}

	@Override
	public void deserialize(DataInput in) throws IOException {
		target = BLOCK.read(in);
		radius = I32.read(in);
		end = BLOCK.read(in);
		deadline = OPTIONAL_I32.read(in);
		turnSpeed = OPTIONAL_F64.read(in);
	}
}

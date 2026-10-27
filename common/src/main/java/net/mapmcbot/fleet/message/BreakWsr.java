package net.mapmcbot.fleet.message;

import static net.mapmcbot.fleet.types.MinecraftProtocolType.*;
import static net.mapmcbot.fleet.types.SocketProtocolType.*;


import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;


/** Primitive: walk to a spot the block is seen from (a walk of radius at most) and break it. */
public final class BreakWsr extends SocketMessage {
	public static final int TYPE = 47;

	public int[] target;
	/** The most the walk to a spot it is seen from costs. */
	public int radius;
	/** In ms; null for the bot's own. */
	public Integer deadline;
	/** In rad/s; null for the bot's own. */
	public Double turnSpeed;

	/** To deserialize. */
	public BreakWsr(String bot) {
		super(bot);
	}

	public BreakWsr(String bot, int[] target, int radius, Integer deadline, Double turnSpeed) {
		super(bot);
		this.target = target;
		this.radius = radius;
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
		OPTIONAL_I32.write(out, deadline);
		OPTIONAL_F64.write(out, turnSpeed);
	}

	@Override
	public void deserialize(DataInput in) throws IOException {
		target = BLOCK.read(in);
		radius = I32.read(in);
		deadline = OPTIONAL_I32.read(in);
		turnSpeed = OPTIONAL_F64.read(in);
	}
}

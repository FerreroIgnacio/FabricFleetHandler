package net.mapmcbot.fleet.message;

import static net.mapmcbot.fleet.types.MinecraftProtocolType.*;
import static net.mapmcbot.fleet.types.SocketProtocolType.*;


import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;


/** Primitive: break the block, from where the bot stands. */
public final class Break extends SocketMessage {
	public static final int TYPE = 35;

	public int[] target;
	/** In ms; null for the bot's own. */
	public Integer deadline;
	/** In rad/s; null for the bot's own. */
	public Double turnSpeed;

	/** To deserialize. */
	public Break(String bot) {
		super(bot);
	}

	public Break(String bot, int[] target, Integer deadline, Double turnSpeed) {
		super(bot);
		this.target = target;
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
		OPTIONAL_I32.write(out, deadline);
		OPTIONAL_F64.write(out, turnSpeed);
	}

	@Override
	public void deserialize(DataInput in) throws IOException {
		target = BLOCK.read(in);
		deadline = OPTIONAL_I32.read(in);
		turnSpeed = OPTIONAL_F64.read(in);
	}
}

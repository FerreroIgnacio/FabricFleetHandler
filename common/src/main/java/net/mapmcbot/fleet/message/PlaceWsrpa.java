package net.mapmcbot.fleet.message;

import static net.mapmcbot.fleet.types.MinecraftProtocolType.*;
import static net.mapmcbot.fleet.types.SocketProtocolType.*;


import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;


/** Primitive: as PlaceWsrp (see actions.js). */
public final class PlaceWsrpa extends SocketMessage {
	public static final int TYPE = 53;

	public int[] target;
	public int radius;
	public int[] end;
	/** Null for any. */
	public String state;
	/** In ms; null for the bot's own. */
	public Integer deadline;
	/** In rad/s; null for the bot's own. */
	public Double turnSpeed;

	/** To deserialize. */
	public PlaceWsrpa(String bot) {
		super(bot);
	}

	public PlaceWsrpa(String bot, int[] target, int radius, int[] end, String state, Integer deadline, Double turnSpeed) {
		super(bot);
		this.target = target;
		this.radius = radius;
		this.end = end;
		this.state = state;
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
		OPTIONAL_STRING.write(out, state);
		OPTIONAL_I32.write(out, deadline);
		OPTIONAL_F64.write(out, turnSpeed);
	}

	@Override
	public void deserialize(DataInput in) throws IOException {
		target = BLOCK.read(in);
		radius = I32.read(in);
		end = BLOCK.read(in);
		state = OPTIONAL_STRING.read(in);
		deadline = OPTIONAL_I32.read(in);
		turnSpeed = OPTIONAL_F64.read(in);
	}
}

package net.mapmcbot.fleet.message;

import static net.mapmcbot.fleet.types.MinecraftProtocolType.*;
import static net.mapmcbot.fleet.types.SocketProtocolType.*;


import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;


/** Primitive: dig the band between the corners, in the order given (see prims/band.js BAND_STYLES; null for the bot's own). */
public final class Band extends SocketMessage {
	public static final int TYPE = 57;

	public int[] from;
	public int[] to;
	public String order;
	/** In ms; null for the bot's own. */
	public Integer deadline;
	/** In rad/s; null for the bot's own. */
	public Double turnSpeed;

	/** To deserialize. */
	public Band(String bot) {
		super(bot);
	}

	public Band(String bot, int[] from, int[] to, String order, Integer deadline, Double turnSpeed) {
		super(bot);
		this.from = from;
		this.to = to;
		this.order = order;
		this.deadline = deadline;
		this.turnSpeed = turnSpeed;
	}

	@Override
	public int type() {
		return TYPE;
	}

	@Override
	public void serialize(DataOutput out) throws IOException {
		BLOCK.write(out, from);
		BLOCK.write(out, to);
		OPTIONAL_STRING.write(out, order);
		OPTIONAL_I32.write(out, deadline);
		OPTIONAL_F64.write(out, turnSpeed);
	}

	@Override
	public void deserialize(DataInput in) throws IOException {
		from = BLOCK.read(in);
		to = BLOCK.read(in);
		order = OPTIONAL_STRING.read(in);
		deadline = OPTIONAL_I32.read(in);
		turnSpeed = OPTIONAL_F64.read(in);
	}
}

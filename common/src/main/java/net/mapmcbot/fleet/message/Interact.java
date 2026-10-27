package net.mapmcbot.fleet.message;

import static net.mapmcbot.fleet.types.MinecraftProtocolType.*;
import static net.mapmcbot.fleet.types.SocketProtocolType.*;


import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;


/** Primitive: a click on a block or an entity, with what it expects of it. */
public final class Interact extends SocketMessage {
	public static final int TYPE = 34;

	/** 0 left, 1 right. */
	public int button;
	/** The block clicked; null for an entity. */
	public int[] block;
	/** The entity clicked, by UUID; null for a block. */
	public String entity;
	/** Null for the interaction's own. */
	public Expect expect;
	/** In ms; null for the bot's own. */
	public Integer deadline;
	/** In rad/s; null for the bot's own. */
	public Double turnSpeed;

	/** To deserialize. */
	public Interact(String bot) {
		super(bot);
	}

	public Interact(String bot, int button, int[] block, String entity, Expect expect, Integer deadline, Double turnSpeed) {
		super(bot);
		this.button = button;
		this.block = block;
		this.entity = entity;
		this.expect = expect;
		this.deadline = deadline;
		this.turnSpeed = turnSpeed;
	}

	@Override
	public int type() {
		return TYPE;
	}

	@Override
	public void serialize(DataOutput out) throws IOException {
		U8.write(out, button);

		if (block != null) {
			U8.write(out, 0);
			BLOCK.write(out, block);
		} else {
			U8.write(out, 1);
			STRING.write(out, entity);
		}

		Expect.write(out, expect);
		OPTIONAL_I32.write(out, deadline);
		OPTIONAL_F64.write(out, turnSpeed);
	}

	@Override
	public void deserialize(DataInput in) throws IOException {
		button = U8.read(in);
		final int target = U8.read(in);

		if (target == 0) {
			block = BLOCK.read(in);
		} else if (target == 1) {
			entity = STRING.read(in);
		} else {
			throw new IOException("bad interact target kind " + target + " for " + bot);
		}

		expect = Expect.read(in);
		deadline = OPTIONAL_I32.read(in);
		turnSpeed = OPTIONAL_F64.read(in);
	}
}

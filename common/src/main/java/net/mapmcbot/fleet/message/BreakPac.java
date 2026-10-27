package net.mapmcbot.fleet.message;

import static net.mapmcbot.fleet.types.MinecraftProtocolType.*;
import static net.mapmcbot.fleet.types.SocketProtocolType.*;


import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;


/** Primitive: break the blocks in order, ending standing in end. */
public final class BreakPac extends SocketMessage {
	public static final int TYPE = 56;

	public int[][] targets;
	public int[] end;
	/** {x, z, line} it stands at on end's seam (see prims/break_pac.js); null for none. */
	public double[] stand;
	/** In ms; null for the bot's own. */
	public Integer deadline;
	/** In rad/s; null for the bot's own. */
	public Double turnSpeed;

	/** To deserialize. */
	public BreakPac(String bot) {
		super(bot);
	}

	public BreakPac(String bot, int[][] targets, int[] end, double[] stand, Integer deadline, Double turnSpeed) {
		super(bot);
		this.targets = targets;
		this.end = end;
		this.stand = stand;
		this.deadline = deadline;
		this.turnSpeed = turnSpeed;
	}

	@Override
	public int type() {
		return TYPE;
	}

	@Override
	public void serialize(DataOutput out) throws IOException {
		BLOCKS.write(out, targets);
		BLOCK.write(out, end);
		BOOL.write(out, stand != null);

		if (stand != null) {
			POINT.write(out, stand);
		}

		OPTIONAL_I32.write(out, deadline);
		OPTIONAL_F64.write(out, turnSpeed);
	}

	@Override
	public void deserialize(DataInput in) throws IOException {
		targets = BLOCKS.read(in);

		if (targets.length == 0) {
			throw new IOException("break_pac of no target for " + bot);
		}

		end = BLOCK.read(in);
		stand = BOOL.read(in) ? POINT.read(in) : null;
		deadline = OPTIONAL_I32.read(in);
		turnSpeed = OPTIONAL_F64.read(in);
	}
}

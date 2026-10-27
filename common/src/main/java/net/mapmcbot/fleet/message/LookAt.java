package net.mapmcbot.fleet.message;

import static net.mapmcbot.fleet.types.MinecraftProtocolType.*;
import static net.mapmcbot.fleet.types.SocketProtocolType.*;


import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;


/** Primitive: turn the head to yaw and pitch (degrees, as the game has them). */
public final class LookAt extends SocketMessage {
	public static final int TYPE = 43;

	public double yaw;
	public double pitch;
	/** In rad/s; null for the bot's own. */
	public Double turnSpeed;

	/** To deserialize. */
	public LookAt(String bot) {
		super(bot);
	}

	public LookAt(String bot, double yaw, double pitch, Double turnSpeed) {
		super(bot);
		this.yaw = yaw;
		this.pitch = pitch;
		this.turnSpeed = turnSpeed;
	}

	@Override
	public int type() {
		return TYPE;
	}

	@Override
	public void serialize(DataOutput out) throws IOException {
		F64.write(out, yaw);
		F64.write(out, pitch);
		OPTIONAL_F64.write(out, turnSpeed);
	}

	@Override
	public void deserialize(DataInput in) throws IOException {
		yaw = F64.read(in);
		pitch = F64.read(in);
		turnSpeed = OPTIONAL_F64.read(in);
	}
}

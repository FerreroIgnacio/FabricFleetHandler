package net.mapmcbot.fleet.message;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

/**
 * A message between the mod and the bot fleet, mirrored by bot/protocol.js: its u8 type, the bot's
 * name ("" for the fleet's own), then its fields. Each type is a class of its own in
 * net.mapmcbot.fleet.message, registered in FleetSocketHandler, which writes and reads the type and
 * the bot (see FleetSocketHandler.readFromSocket).
 */
public abstract class SocketMessage {
	public final String bot;

	protected SocketMessage(String bot) {
		if (bot == null) {
			throw new IllegalArgumentException("a message needs its bot's name (\"\" for the fleet's own)");
		}

		this.bot = bot;
	}

	/** The u8 that is its type on the wire (see ChunkRegistry). */
	public abstract int type();

	/** Its fields, past the type and the bot. */
	public abstract void serialize(DataOutput out) throws IOException;

	/** Its fields from what serialize wrote; one that is not throws. */
	public abstract void deserialize(DataInput in) throws IOException;
}

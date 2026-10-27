package net.mapmcbot.fleet.message;

import static net.mapmcbot.fleet.types.MinecraftProtocolType.*;
import static net.mapmcbot.fleet.types.SocketProtocolType.*;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;


/** Mod to fleet: a click on the bot's window as the player's own client sends one (mode 0 pickup, 1 quick move, 2 swap, 3 clone, 4 throw, 5 drag, 6 pickup all; slot -999 outside); id counts up from 1 for each bot, the Inventory after it says it is done. */
public final class WindowClick extends SocketMessage {
	public static final int TYPE = 25;

	public int slot;
	public int button;
	public int mode;
	public int id;

	/** To deserialize. */
	public WindowClick(String bot) {
		super(bot);
	}

	public WindowClick(String bot, int slot, int button, int mode, int id) {
		super(bot);
		this.slot = slot;
		this.button = button;
		this.mode = mode;
		this.id = id;
	}

	@Override
	public int type() {
		return TYPE;
	}

	@Override
	public void serialize(DataOutput out) throws IOException {
		I32.write(out, slot);
		U8.write(out, button);
		U8.write(out, mode);
		I32.write(out, id);
	}

	@Override
	public void deserialize(DataInput in) throws IOException {
		slot = I32.read(in);
		button = U8.read(in);
		mode = U8.read(in);
		id = I32.read(in);
	}
}

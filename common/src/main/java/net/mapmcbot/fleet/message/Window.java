package net.mapmcbot.fleet.message;

import static net.mapmcbot.fleet.types.MinecraftProtocolType.*;
import static net.mapmcbot.fleet.types.SocketProtocolType.*;


import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import net.mapmcbot.bot.BotInventory;
import net.mapmcbot.bot.BotListener;
import net.mapmcbot.bot.BotWindow;
import net.mapmcbot.chunk.ChunkListener;
import net.mapmcbot.profile.ProfileListener;

/** Fleet to mod: the window the bot has open, whole, sent once anything in it changed; null once closed. */
public final class Window extends SocketMessage implements FromFleet {
	public static final int TYPE = 27;

	public BotWindow window;

	/** To deserialize. */
	public Window(String bot) {
		super(bot);
	}

	public Window(String bot, BotWindow window) {
		super(bot);
		this.window = window;
	}

	@Override
	public int type() {
		return TYPE;
	}

	@Override
	public void serialize(DataOutput out) throws IOException {
		BOOL.write(out, window != null);

		if (window == null) {
			return;
		}

		STRING.write(out, window.getType());
		I32.write(out, window.getContainerSlots());
		I32.write(out, window.getSlots().size());

		for (BotInventory.Item item : window.getSlots()) {
			ITEM.write(out, item);
		}

		ITEM.write(out, window.getCursor());
		INTS.write(out, window.getProperties());
		I32.write(out, window.getTrades().size());

		for (BotWindow.Trade trade : window.getTrades()) {
			ITEM.write(out, trade.getFirst());
			ITEM.write(out, trade.getSecond());
			ITEM.write(out, trade.getResult());
			BOOL.write(out, trade.isDisabled());
		}
	}

	@Override
	public void deserialize(DataInput in) throws IOException {
		if (!BOOL.read(in)) {
			window = null;
			return;
		}

		final String windowType = STRING.read(in);
		final int own = I32.read(in);
		final int count = I32.read(in);

		if (count < 0 || count > 256 || own < 0 || own > count) {
			throw new IOException("bad window of " + own + " own slots in " + count + " for " + bot);
		}

		final List<BotInventory.Item> slots = new ArrayList<BotInventory.Item>(count);

		for (int i = 0; i < count; i++) {
			slots.add(ITEM.read(in));
		}

		final BotInventory.Item cursor = ITEM.read(in);
		final int[] properties = INTS.read(in);

		if (properties.length > 64) {
			throw new IOException("bad window of " + properties.length + " properties for " + bot);
		}

		final int tradeCount = I32.read(in);

		if (tradeCount < 0 || tradeCount > 256) {
			throw new IOException("bad window of " + tradeCount + " trades for " + bot);
		}

		final List<BotWindow.Trade> trades = new ArrayList<BotWindow.Trade>(tradeCount);

		for (int i = 0; i < tradeCount; i++) {
			trades.add(new BotWindow.Trade(ITEM.read(in), ITEM.read(in), ITEM.read(in), BOOL.read(in)));
		}

		window = new BotWindow(windowType, own, cursor, slots, properties, trades);
	}

	@Override
	public void dispatch(ChunkListener chunks, BotListener bots, ProfileListener profiles) {
		bots.onWindow(bot, window);
	}
}

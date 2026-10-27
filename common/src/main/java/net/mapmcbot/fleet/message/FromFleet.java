package net.mapmcbot.fleet.message;

import net.mapmcbot.bot.BotListener;
import net.mapmcbot.chunk.ChunkListener;
import net.mapmcbot.profile.ProfileListener;

/** A message the fleet sends the mod, handed to the listener of its domain (see FleetSocketHandler.readFromSocket). */
public interface FromFleet {
	void dispatch(ChunkListener chunks, BotListener bots, ProfileListener profiles);
}

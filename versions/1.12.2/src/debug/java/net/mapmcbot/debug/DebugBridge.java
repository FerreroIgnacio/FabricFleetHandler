package net.mapmcbot.debug;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;

import net.mapmcbot.area.Area;
import net.mapmcbot.area.AreaStore;
import net.mapmcbot.client.MapMcBotClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.MinecraftServer;

/**
 * Debug only (runClient): a line socket on localhost, its port in run/mapmcbot/debug/port, for
 * driving the game from outside. Each line gets one line back:
 *   #...                      a mod comando, as typed in chat
 *   /...                      a server command, run by the integrated server
 *   pos BOT                   the bot's position as the player's world has it
 *   status BOT                the bot's state
 *   area NAME x1 y1 z1 x2 y2 z2  the area named, made or moved there
 */
final class DebugBridge {
	private DebugBridge() {
	}

	static void start(MinecraftClient client) {
		final ServerSocket server;

		try {
			server = new ServerSocket(0, 4, InetAddress.getLoopbackAddress());
			final File port = new File(client.runDirectory, "mapmcbot/debug/port");
			port.getParentFile().mkdirs();
			Files.write(port.toPath(), String.valueOf(server.getLocalPort()).getBytes(StandardCharsets.UTF_8));
		} catch (IOException e) {
			throw new UncheckedIOException("debug bridge could not listen", e);
		}

		final Thread thread = new Thread(() -> {
			while (true) {
				try (Socket socket = server.accept()) {
					serve(client, socket);
				} catch (IOException e) {
					throw new UncheckedIOException("debug bridge connection failed", e);
				}
			}
		}, "mapmcbot-debug-bridge");
		thread.setDaemon(true);
		thread.start();
	}

	private static void serve(MinecraftClient client, Socket socket) throws IOException {
		final BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
		final Writer out = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8);
		String line;

		while ((line = in.readLine()) != null) {
			final String command = line.trim();
			String reply;

			try {
				reply = onClient(client, () -> run(client, command));
			} catch (RuntimeException e) {
				reply = "error " + e;
			}

			out.write(reply.replace('\n', ' ') + "\n");
			out.flush();
		}
	}

	private static String run(MinecraftClient client, String command) throws Exception {
		if (command.startsWith("#")) {
			return MapMcBotClient.command(command) ? "ok" : "not a comando";
		}

		if (command.startsWith("/")) {
			final MinecraftServer server = client.getServer();

			if (server == null) {
				return "no integrated server";
			}

			final FutureTask<Integer> task = new FutureTask<Integer>(() -> server.getCommandManager().execute(server, command.substring(1)));
			server.submit(task);
			return "ok " + task.get();
		}

		final String[] words = command.split("\\s+");

		if (words[0].equals("pos")) {
			final PlayerEntity entity = client.world.getPlayerByName(words[1]);
			return entity == null ? "not in sight" : entity.x + " " + entity.y + " " + entity.z;
		}

		if (words[0].equals("blocks")) {
			// The blocks of the box, layer by layer from the top: each a letter per x, rows by z.
			final int[] c = new int[6];

			for (int i = 0; i < 6; i++) {
				c[i] = Integer.parseInt(words[1 + i]);
			}

			final java.util.Map<String, Character> letters = new java.util.LinkedHashMap<String, Character>();
			letters.put("minecraft:air", '.');
			final StringBuilder out = new StringBuilder();

			for (int y = Math.max(c[1], c[4]); y >= Math.min(c[1], c[4]); y--) {
				out.append(" y").append(y).append(':');

				for (int z = Math.min(c[2], c[5]); z <= Math.max(c[2], c[5]); z++) {
					out.append(' ');

					for (int x = Math.min(c[0], c[3]); x <= Math.max(c[0], c[3]); x++) {
						final String name = net.minecraft.block.Block.REGISTRY.getIdentifier(client.world.getBlockState(new net.minecraft.util.math.BlockPos(x, y, z)).getBlock()).toString();

						if (!letters.containsKey(name)) {
							letters.put(name, (char) ('a' + letters.size() - 1));
						}

						out.append(letters.get(name));
					}
				}
			}

			return letters + out.toString();
		}

		if (words[0].equals("cstate")) {
			// cstate BOT x y z: the block as the fleet's chunks have it.
			final int x = Integer.parseInt(words[2]);
			final int y = Integer.parseInt(words[3]);
			final int z = Integer.parseInt(words[4]);
			final net.mapmcbot.chunk.ChunkKey key = new net.mapmcbot.chunk.ChunkKey(MapMcBotClient.bots().getServer(words[1]), "minecraft:" + client.world.dimension.getDimensionType().getName(), x >> 4, z >> 4);
			return MapMcBotClient.chunks().isLoaded(key) ? MapMcBotClient.chunks().getState(key, x, y, z) : "not loaded";
		}

		if (words[0].equals("inv")) {
			final net.mapmcbot.bot.BotInventory inventory = MapMcBotClient.bots().getInventory(words[1]);

			if (inventory == null) {
				return "no inventory";
			}

			final StringBuilder out = new StringBuilder("held " + (36 + inventory.getHeld()) + ":");

			for (int slot = 0; slot < inventory.getSlots().size(); slot++) {
				final net.mapmcbot.bot.BotInventory.Item item = inventory.getSlots().get(slot);

				if (item != null) {
					out.append(' ').append(slot).append('=').append(item.getName()).append('x').append(item.getCount());
				}
			}

			return out.toString();
		}

		if (words[0].equals("status")) {
			return String.valueOf(MapMcBotClient.bots().getStatus(words[1]));
		}

		if (words[0].equals("area")) {
			final AreaStore areas = MapMcBotClient.areas();
			final int[] c = new int[6];

			for (int i = 0; i < 6; i++) {
				c[i] = Integer.parseInt(words[2 + i]);
			}

			for (Area area : areas.getAreas()) {
				if (area.getName().equals(words[1])) {
					area.setCorners(c[0], c[1], c[2], c[3], c[4], c[5]);
					areas.markDirty();
					return "moved";
				}
			}

			areas.add(new Area(words[1], 0xffe040, c[0], c[1], c[2], c[3], c[4], c[5]));
			return "made";
		}

		return "unknown: " + command;
	}

	private static String onClient(MinecraftClient client, Callable<String> callable) {
		final FutureTask<String> task = new FutureTask<String>(callable);
		client.submit(task);

		try {
			return task.get();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("debug bridge interrupted", e);
		} catch (ExecutionException e) {
			throw new IllegalStateException(e.getCause().toString(), e.getCause());
		}
	}
}

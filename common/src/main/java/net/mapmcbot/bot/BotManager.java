package net.mapmcbot.bot;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.RandomAccessFile;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

import net.mapmcbot.chunk.ChunkRegistry;

/**
 * The mineflayer bots, all in one node process (the fleet, fleet.js) on a pool of threads sharing their chunks, running from a folder in the
 * run directory. This class only runs the process: the fleet connects back to a local socket, handed
 * to the FleetSocketHandler, which talks over it. The chunks' blocks go through columns.bin in that
 * folder instead, made anew with the manager and mapped by the fleet and the chunk registry.
 *
 * The scripts ship inside the jar and are unpacked there because npm needs a real folder to install
 * into. The first bot started does the setup (unpack, npm install when mineflayer is missing, start
 * the fleet), on its own thread so the game never waits on it.
 *
 * SINGLETON: one fleet per game; a second manager throws.
 */
public final class BotManager {
	private static final AtomicBoolean CREATED = new AtomicBoolean();

	private static final String[] RESOURCES = {"fleet.js", "poolThread.js", "sharedChunks.js", "bot.js", "actions.js", "look.js", "lookTraces.json", "profiler.js", "protocol.js", "states.js", "columnSerializer.js", "package.json", "package-lock.json",
		"lib/util.js", "lib/lifecycle.js", "lib/path.js", "lib/aim.js", "lib/head.js", "lib/window.js", "lib/items.js", "lib/hold.js", "lib/pose.js", "lib/steer.js", "lib/walk.js", "lib/blocks.js", "lib/withSearch.js", "lib/withSearchFromEnd.js", "lib/withSearchAsync.js",
		"prims/goto.js", "prims/interact.js", "prims/break.js", "prims/place.js", "prims/close_window.js", "prims/move.js", "prims/item_fill.js", "prims/wait_slot.js", "prims/hotbar.js", "prims/select_trade.js", "prims/look_at.js", "prims/drop.js", "prims/shift_move.js", "prims/stop.js",
		"prims/break_wsr.js", "prims/place_wsr.js", "prims/break_wsrp.js", "prims/place_wsrp.js", "prims/break_wsrpa.js", "prims/place_wsrpa.js", "prims/break_pac2c.js", "prims/band.js"};

	private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase().contains("win");

	private final File directory;
	private final File columns;

	private volatile boolean closing;
	private volatile Process fleet;

	/** Times in fleet.log: to the millisecond. */
	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

	public BotManager(File directory) {
		if (!CREATED.compareAndSet(false, true)) {
			throw new IllegalStateException("BotManager is a singleton: one was already created");
		}

		this.directory = directory;
		this.columns = new File(directory, "columns.bin");
		createColumns();
	}

	/** The fleet's folder: its scripts and logs. */
	public File directory() {
		return directory;
	}

	/** The columns file the fleet loads every chunk into, one slot each (see ChunkRegistry). */
	public File columns() {
		return columns;
	}

	/** Every slot zeros, the last game's columns gone. */
	private void createColumns() {
		if (!directory.isDirectory() && !directory.mkdirs()) {
			throw new IllegalStateException("cannot create " + directory);
		}

		try {
			Files.deleteIfExists(columns.toPath());

			try (RandomAccessFile file = new RandomAccessFile(columns, "rw")) {
				file.setLength((long) ChunkRegistry.COLUMN_SLOTS * ChunkRegistry.COLUMN_SLOT_SIZE);
			}
		} catch (IOException e) {
			throw new UncheckedIOException("could not create " + columns, e);
		}
	}

	/** Unpacks the scripts, installs the packages when the lock changed, starts the fleet and returns its connection. Once. */
	public synchronized Socket start() throws IOException, InterruptedException {
		if (fleet != null) {
			throw new IllegalStateException("the bot fleet is already started");
		}

		extractScripts();

		// node_modules is the lock's exactly (npm ci): installed again whenever the lock differs from the
		// one it was last installed from, kept beside it once the install went through.
		final File lock = new File(directory, "package-lock.json");
		final File installed = new File(directory, "node_modules/.mapmcbot-lock.json");

		if (!installed.isFile() || !Arrays.equals(Files.readAllBytes(lock.toPath()), Files.readAllBytes(installed.toPath()))) {
			System.out.println("[mapmcbot] installing the bot's packages (npm ci) in " + directory);
			final int exit = new ProcessBuilder(WINDOWS ? "npm.cmd" : "npm", "ci", "--no-audit", "--no-fund")
					.directory(directory).inheritIO().start().waitFor();

			if (exit != 0) {
				throw new IOException("npm ci failed (exit " + exit + ")");
			}

			Files.copy(lock.toPath(), installed.toPath(), StandardCopyOption.REPLACE_EXISTING);
		}

		final ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
		final Process process = new ProcessBuilder("node", "fleet.js", Integer.toString(server.getLocalPort()), columns.getAbsolutePath())
				.directory(directory).redirectErrorStream(true).start();
		pump(process, server);

		final Socket socket;

		// The pump closes the server socket if node dies first, which ends this accept.
		try {
			socket = server.accept();
		} finally {
			server.close();
		}

		fleet = process;
		return socket;
	}

	/**
	 * Fleet output, each line with its time, to fleet.log of the fleet's folder, and all but the bots'
	 * info lines to the game log too (the game log can drown in a map's own output); the fleet exiting
	 * on its own is a failure.
	 * Written out line by line: the fleet says little, and its last words matter most.
	 */
		private void pump(final Process process, final ServerSocket server) {
		final Thread pump = new Thread(() -> {
			try (BufferedReader lines = new BufferedReader(
					new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
					BufferedWriter fleetLog = new BufferedWriter(new OutputStreamWriter(
							new FileOutputStream(new File(directory, "fleet.log"), false), StandardCharsets.UTF_8))) {
				String line;

				while ((line = lines.readLine()) != null) {
					// A bot's own info line ("[name] ...") stays in fleet.log; errors ("E "), warnings ("W ") and the fleet's own reach the game log too.
					if (!line.startsWith("[")) {
						System.out.println("[mapmcbot fleet] " + line);
					}

					fleetLog.write(LocalTime.now().format(TIME) + " " + line);
					fleetLog.newLine();
					fleetLog.flush();
				}

				server.close();
			} catch (IOException e) {
				throw new UncheckedIOException("lost the bot fleet's output", e);
			}

			final int exit;

			try {
				exit = process.waitFor();
			} catch (InterruptedException e) {
				throw new IllegalStateException("interrupted waiting for the bot fleet", e);
			}

			if (!closing) {
				throw new IllegalStateException("the bot fleet exited on its own (exit " + exit + ")");
			}
		}, "mapmcbot-fleet-output");

		pump.setDaemon(true);
		pump.start();
	}

	/** JVM exit: kills the fleet; false when it never started. */
	public boolean stop() {
		closing = true;
		final Process process = fleet;

		if (process == null) {
			return false;
		}

		process.destroy();
		return true;
	}

	/** Overwrites only what changed, so a mod update reaches the scripts without touching the rest. */
	private void extractScripts() throws IOException {
		if (!directory.isDirectory() && !directory.mkdirs()) {
			throw new IOException("cannot create " + directory);
		}

		for (String resource : RESOURCES) {
			final byte[] bytes;

			try (InputStream in = BotManager.class.getResourceAsStream("/bot/" + resource)) {
				if (in == null) {
					throw new IOException("missing /bot/" + resource + " in the jar");
				}

				bytes = readAll(in);
			}

			final File target = new File(directory, resource);

			if (!target.isFile() || !Arrays.equals(Files.readAllBytes(target.toPath()), bytes)) {
				// lib/ and prims/ the first time.
				Files.createDirectories(target.toPath().getParent());
				Files.write(target.toPath(), bytes);
			}
		}
	}

	private static byte[] readAll(InputStream in) throws IOException {
		final ByteArrayOutputStream out = new ByteArrayOutputStream();
		final byte[] buffer = new byte[8192];
		int read;

		while ((read = in.read(buffer)) != -1) {
			out.write(buffer, 0, read);
		}

		return out.toByteArray();
	}
}

package net.mapmcbot.fleet;

import static net.mapmcbot.fleet.types.SocketProtocolType.MAX_FRAME;
import static net.mapmcbot.fleet.types.SocketProtocolType.STRING;
import static net.mapmcbot.fleet.types.SocketProtocolType.U8;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.lang.management.ManagementFactory;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;

import net.mapmcbot.bot.BotListener;
import net.mapmcbot.bot.BotManager;
import net.mapmcbot.chunk.ChunkKey;
import net.mapmcbot.chunk.ChunkListener;
import net.mapmcbot.fleet.message.*;
import net.mapmcbot.profile.ProfileListener;
import net.mapmcbot.profile.ProfileReport;

/**
 * The socket between the mod and the bot fleet, both ways: the orders the mod sends it and, through
 * one listener per domain, what it reports. Mirrored by bot/protocol.js: a frame is a u32 length and
 * that many bytes, a u8 type (see Type), the bot's name and the message's fields (see SocketMessage,
 * its pieces in net.mapmcbot.fleet.types). The fleet's process is the BotManager's; the first bot
 * started has it start the fleet and connect.
 *
 * SINGLETON: one per game, as the fleet; a second handler throws.
 */
public final class FleetSocketHandler {
	/** Every message type, named as protocol.js has it (mirrored by TYPES there: the ids are the wire's, never renumbered). */
	private enum Type {
		CLAIM(Claim.TYPE, Claim::new),
		READY(Ready.TYPE, Ready::new),
		CHANGED(Changed.TYPE, Changed::new),
		BLOCK_ENTITY_UPDATE(BlockEntityUpdate.TYPE, BlockEntityUpdate::new),
		UNLOAD(Unload.TYPE, Unload::new),
		BOT_GONE(BotGone.TYPE, BotGone::new),
		PATH(Path.TYPE, Path::new),
		BOT_SPAWNED(BotSpawned.TYPE, BotSpawned::new),
		STATE_NAMES(StateNames.TYPE, StateNames::new),
		LOAD(Load.TYPE, Load::new),
		PROFILE(Profile.TYPE, Profile::new),
		RELEASE(Release.TYPE, Release::new),
		SPAWN(Spawn.TYPE, Spawn::new),
		QUIT(Quit.TYPE, Quit::new),
		LOADED(Loaded.TYPE, Loaded::new),
		PROFILE_REQUEST(ProfileRequest.TYPE, ProfileRequest::new),
		GOTO(Goto.TYPE, Goto::new),
		INVENTORY(Inventory.TYPE, Inventory::new),
		WINDOW_CLICK(WindowClick.TYPE, WindowClick::new),
		STATE(State.TYPE, State::new),
		WINDOW(Window.TYPE, Window::new),
		TRADE_SELECT(TradeSelect.TYPE, TradeSelect::new),
		CHEST_LID(ChestLid.TYPE, ChestLid::new),
		CHEST(Chest.TYPE, Chest::new),
		COLUMN_GONE(ColumnGone.TYPE, ColumnGone::new),
		COLUMN_IN(ColumnIn.TYPE, ColumnIn::new),
		INTERACT(Interact.TYPE, Interact::new),
		BREAK(Break.TYPE, Break::new),
		PLACE(Place.TYPE, Place::new),
		CLOSE_WINDOW(CloseWindow.TYPE, CloseWindow::new),
		MOVE(Move.TYPE, Move::new),
		ITEM_FILL(ItemFill.TYPE, ItemFill::new),
		WAIT_SLOT(WaitSlot.TYPE, WaitSlot::new),
		HOTBAR(Hotbar.TYPE, Hotbar::new),
		SELECT_TRADE(SelectTrade.TYPE, SelectTrade::new),
		LOOK_AT(LookAt.TYPE, LookAt::new),
		DROP(Drop.TYPE, Drop::new),
		SHIFT_MOVE(ShiftMove.TYPE, ShiftMove::new),
		STOP(Stop.TYPE, Stop::new),
		BREAK_WSR(BreakWsr.TYPE, BreakWsr::new),
		PLACE_WSR(PlaceWsr.TYPE, PlaceWsr::new),
		ALLOWED_PLACE(AllowedPlace.TYPE, AllowedPlace::new),
		BREAK_WSRP(BreakWsrp.TYPE, BreakWsrp::new),
		PLACE_WSRP(PlaceWsrp.TYPE, PlaceWsrp::new),
		BREAK_WSRPA(BreakWsrpa.TYPE, BreakWsrpa::new),
		PLACE_WSRPA(PlaceWsrpa.TYPE, PlaceWsrpa::new),
		VIS(Vis.TYPE, Vis::new),
		CLEAR_Q(ClearQ.TYPE, ClearQ::new),
		BREAK_PAC2C(BreakPac2c.TYPE, BreakPac2c::new),
		BAND(Band.TYPE, Band::new),
		BANDS(Bands.TYPE, Bands::new);

		private static final Type[] BY_ID = new Type[256];

		static {
			for (Type type : values()) {
				if (BY_ID[type.id] != null) {
					throw new IllegalStateException("message types " + BY_ID[type.id] + " and " + type + " share id " + type.id);
				}

				BY_ID[type.id] = type;
			}
		}

		private final int id;
		/** A message of the type, for its bot, to deserialize. */
		private final Function<String, SocketMessage> factory;

		Type(int id, Function<String, SocketMessage> factory) {
			this.id = id;
			this.factory = factory;
		}

		/** The type of the id; an unknown one throws. */
		public static Type of(int id) {
			final Type type = id >= 0 && id < BY_ID.length ? BY_ID[id] : null;

			if (type == null) {
				throw new IllegalArgumentException("unknown message type " + id);
			}

			return type;
		}
	}

	private static final AtomicBoolean CREATED = new AtomicBoolean();

	/** The fleet's process, started by the first bot. */
	private final BotManager fleet;

	private final Object writeLock = new Object();

	private volatile ChunkListener chunkListener;
	private volatile BotListener botListener;
	private volatile ProfileListener profileListener;

	/** HotSpot's: CPU time and allocations of the reader thread, per frame. */
	private final com.sun.management.ThreadMXBean threadBean;

	/** What each bot's frames cost the mod, by bot name; "" for the fleet's own frames. */
	private final Map<String, Traffic> traffic = new ConcurrentHashMap<String, Traffic>();
	private volatile boolean closing;
	private volatile OutputStream out;
	private volatile Thread reader;
	/** Told when the reader dies of an exception, which the frames after it never get past. */
	private volatile Consumer<Throwable> readerFailure;

	/**
	 * Every frame through the socket, one line each, in socket.log of the fleet's folder. Buffered and
	 * written out every SOCKET_LOG_LINES lines, when the fleet goes away and when the game exits, never
	 * per frame: a flush per frame held up the reader and, in send, the write lock. A game killed
	 * outright loses at most the last SOCKET_LOG_LINES lines.
	 */
	private volatile PrintWriter socketLog;

	private static final int SOCKET_LOG_LINES = 1000;

	/** Frames into socket.log since it was last written out; guarded by socketLog. */
	private int socketLogPending;

	/** The chatty frames counted up and not written yet, by way, type and bot, in the order they came; guarded by socketLog. */
	private final Map<String, FrameGroup> frameGroups = new LinkedHashMap<String, FrameGroup>();

	/** Times in socket.log and fleet.log: to the millisecond. */
	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

	public FleetSocketHandler(BotManager fleet) {
		if (!CREATED.compareAndSet(false, true)) {
			throw new IllegalStateException("FleetSocketHandler is a singleton: one was already created");
		}

		if (!(ManagementFactory.getThreadMXBean() instanceof com.sun.management.ThreadMXBean)) {
			throw new IllegalStateException("the profiler reads the CPU and allocations of the fleet's frames through com.sun.management, which this JVM ("
					+ System.getProperty("java.vm.name") + ") does not have");
		}

		threadBean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();

		if (!threadBean.isCurrentThreadCpuTimeSupported() || !threadBean.isThreadAllocatedMemorySupported()) {
			throw new IllegalStateException("this JVM cannot tell the CPU time or the allocations of a thread");
		}

		threadBean.setThreadCpuTimeEnabled(true);
		threadBean.setThreadAllocatedMemoryEnabled(true);

		this.fleet = fleet;
		Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown, "mapmcbot-fleet-shutdown"));
	}

	public void setReaderFailure(Consumer<Throwable> listener) {
		this.readerFailure = listener;
	}

	/** Once, before anything arrives. */
	public void setChunkListener(ChunkListener listener) {
		if (chunkListener != null) {
			throw new IllegalStateException("the bot fleet already has a chunk listener");
		}

		chunkListener = listener;
	}

	/** Once, before anything arrives. */
	public void setBotListener(BotListener listener) {
		if (botListener != null) {
			throw new IllegalStateException("the bot fleet already has a bot listener");
		}

		botListener = listener;
	}

	/** Once, before anything arrives. */
	public void setProfileListener(ProfileListener listener) {
		if (profileListener != null) {
			throw new IllegalStateException("the bot fleet already has a profile listener");
		}

		profileListener = listener;
	}

	/** Whether the fleet is up: its first bot starts it. */
	public boolean isRunning() {
		return out != null && !closing;
	}

	/** Asks the fleet for its profile; it comes back as a PROFILE of the same id. Only while running. */
	public void requestProfile(int id, String reason) {
		send(new ProfileRequest("", id, reason));
	}

	/**
	 * What the mod's side of the socket spent on each bot's frames so far (scope java.bot:name), and
	 * on the fleet's own (scope java.channel): CPU and allocations on the reader thread, frames and bytes.
	 */
	public List<ProfileReport.Row> channelRows() {
		final List<ProfileReport.Row> rows = new ArrayList<ProfileReport.Row>();

		for (Map.Entry<String, Traffic> entry : traffic.entrySet()) {
			final String scope = entry.getKey().isEmpty() ? "java.channel" : "java.bot:" + entry.getKey();
			final Traffic t = entry.getValue();
			rows.add(new ProfileReport.Row(scope, "cpu.frames", ProfileReport.MS, t.cpu.get() / 1e6));
			rows.add(new ProfileReport.Row(scope, "alloc", ProfileReport.BYTES_SO_FAR, t.allocated.get()));
			rows.add(new ProfileReport.Row(scope, "frames.in", ProfileReport.COUNT, t.framesIn.get()));
			rows.add(new ProfileReport.Row(scope, "net.in", ProfileReport.BYTES_SO_FAR, t.bytesIn.get()));
			rows.add(new ProfileReport.Row(scope, "frames.out", ProfileReport.COUNT, t.framesOut.get()));
			rows.add(new ProfileReport.Row(scope, "net.out", ProfileReport.BYTES_SO_FAR, t.bytesOut.get()));
		}

		return rows;
	}

	private Traffic traffic(String bot) {
		return traffic.computeIfAbsent(bot, name -> new Traffic());
	}

	/** In the background: the first bot starts the fleet, on its own thread so the game never waits on it. */
	public void spawn(final String bot, final String host, final int port) {
		final Thread thread = new Thread(() -> {
			try {
				connect();
			} catch (IOException e) {
				throw new UncheckedIOException(bot + " could not start: no bot fleet", e);
			} catch (InterruptedException e) {
				throw new IllegalStateException(bot + " could not start: interrupted", e);
			}

			send(new Spawn(bot, host, port));
		}, "mapmcbot-start-" + bot);

		thread.setDaemon(true);
		thread.start();
	}

	public void quit(String bot) {
		send(new Quit(bot));
	}

	/** A click on the bot's inventory window. */
	public void windowClick(String bot, int slot, int button, int mode, int id) {
		send(new WindowClick(bot, slot, button, mode, id));
	}

	/** The villager trade the bot's open window shows. */
	public void tradeSelect(String bot, int trade) {
		send(new TradeSelect(bot, trade));
	}

	/** The blocks the bot may place. */
	public void allowedPlace(String bot, List<String> blocks) {
		send(new AllowedPlace(bot, blocks));
	}

	/** The bot's primitives dropped, the running one and the ones it holds. */
	public void clearQ(String bot) {
		send(new ClearQ(bot));
	}

	/** A primitive for the bot, as JSON (see Primitives). */
	public void action(String bot, String json) {
		send(Primitives.fromJson(bot, json));
	}

	/** The bot walks to stand at x, y, z. */
	public void gotoSpot(String bot, int x, int y, int z) {
		send(Goto.spot(bot, x, y, z));
	}

	/** Hands an unloaded chunk's slot back to the fleet; `bot` is the claim's, for the logs. */
	public void release(String bot, int slot) {
		send(new Release(bot, slot));
	}

	/** The snapshot asked for is in the slot (mcVersion its version), or there is none (null). */
	public void loaded(ChunkKey key, int slot, String mcVersion) {
		send(new Loaded("", key, slot, mcVersion));
	}

	private void send(SocketMessage message) {
		synchronized (writeLock) {
			if (out == null) {
				throw new IllegalStateException("the bot fleet is not running");
			}

			try {
				final int bytes = writeToSocket(message, out);
				out.flush();
				logFrame("->", message.type(), message.bot, bytes - 4);
				final Traffic t = traffic(message.bot);
				t.framesOut.incrementAndGet();
				t.bytesOut.addAndGet(bytes);
			} catch (IOException e) {
				throw new UncheckedIOException("could not write to the bot fleet", e);
			}
		}

		// Outside the lock: the profile it may ask for is a frame of its own.
		profileListener().onFrame();
	}

	/** Once; synchronized so bots started together wait on the one setup. */
	private synchronized void connect() throws IOException, InterruptedException {
		if (out != null) {
			return;
		}

		final Socket socket = fleet.start();
		socketLog = new PrintWriter(new BufferedWriter(new OutputStreamWriter(new FileOutputStream(new File(fleet.directory(), "socket.log"), false), StandardCharsets.UTF_8), 1 << 16));
		socket.setTcpNoDelay(true);
		final InputStream in = new BufferedInputStream(socket.getInputStream());
		out = new BufferedOutputStream(socket.getOutputStream());

		reader = new Thread(() -> {
			try {
				read(in);
			} catch (RuntimeException e) {
				final Consumer<Throwable> listener = readerFailure;

				if (listener != null) {
					listener.accept(e);
				}

				throw e;
			}
		}, "mapmcbot-fleet-reader");
		reader.setDaemon(true);
		reader.start();
	}

	private void read(InputStream in) {
		IOException failure = null;

		try {
			final CountingInputStream counted = new CountingInputStream(in);
			SocketMessage message;
			long before = 0;

			while ((message = readFromSocket(counted)) != null) {
				final long bytes = counted.count - before;
				before = counted.count;
				// What the frame cost here, the snapshots it makes the chunk registry write included.
				final long cpu = threadBean.getCurrentThreadCpuTime();
				final long allocated = threadBean.getThreadAllocatedBytes(Thread.currentThread().getId());
				logFrame("<-", message.type(), message.bot, (int) bytes - 4);

				if (!(message instanceof FromFleet)) {
					throw new IllegalStateException("unexpected message type " + Type.of(message.type()).name() + " from the bot fleet");
				}

				((FromFleet) message).dispatch(chunkListener(), botListener(), profileListener());
				profileListener().onFrame();
				final String bot = message.bot;

				// Gone: nothing more of it to count.
				if (message instanceof BotGone) {
					traffic.remove(bot);
					continue;
				}

				final Traffic t = traffic(bot);
				t.cpu.addAndGet(threadBean.getCurrentThreadCpuTime() - cpu);
				t.allocated.addAndGet(threadBean.getThreadAllocatedBytes(Thread.currentThread().getId()) - allocated);
				t.framesIn.incrementAndGet();
				t.bytesIn.addAndGet(bytes);
			}
		} catch (IOException e) {
			failure = e;
		}

		flushSocketLog();

		// Every bot went with the fleet, on purpose or not.
		botListener().onClosed();
		chunkListener().onClosed();

		if (closing) {
			return;
		}

		if (failure != null) {
			throw new UncheckedIOException("lost the connection to the bot fleet", failure);
		}

		throw new IllegalStateException("the bot fleet closed its connection");
	}

	/** The bytes read through it so far, to count what each frame from the fleet cost. */
	private static final class CountingInputStream extends FilterInputStream {
		long count;

		CountingInputStream(InputStream in) {
			super(in);
		}

		@Override
		public int read() throws IOException {
			final int b = super.read();

			if (b >= 0) {
				count++;
			}

			return b;
		}

		@Override
		public int read(byte[] b, int off, int len) throws IOException {
			final int n = super.read(b, off, len);

			if (n > 0) {
				count += n;
			}

			return n;
		}
	}

	/** The types that come in floods (chunks, chests, inventories, profiles): socket.log counts them up instead of a line each. */
	private static final Set<Integer> CHATTY = new HashSet<Integer>(Arrays.asList(Claim.TYPE, Ready.TYPE, Changed.TYPE, BlockEntityUpdate.TYPE, Unload.TYPE, Path.TYPE, Load.TYPE, Profile.TYPE, Inventory.TYPE, State.TYPE, Window.TYPE, ChestLid.TYPE, Chest.TYPE, ColumnGone.TYPE, ColumnIn.TYPE, Vis.TYPE, Bands.TYPE, Release.TYPE, Loaded.TYPE, ProfileRequest.TYPE));

	/**
	 * A frame into socket.log, by its type's name: one of a chatty type (see CHATTY) is
	 * counted up with the others of its type, way and bot, written out as a line per group before the
	 * next frame that is not chatty, and whenever socket.log is written out; any other gets a line of
	 * its own. bytes: the frame's without its length.
	 */
	private void logFrame(String direction, int type, String bot, int bytes) {
		final String now = LocalTime.now().format(TIME);

		synchronized (socketLog) {
			if (CHATTY.contains(type)) {
				final String key = direction + " " + Type.of(type).name() + (bot.isEmpty() ? "" : " " + bot);
				FrameGroup group = frameGroups.get(key);

				if (group == null) {
					group = new FrameGroup(now);
					frameGroups.put(key, group);
				}

				group.last = now;
				group.count++;
				group.bytes += bytes;
			} else {
				writeFrameGroups();
				socketLog.println(now + " " + direction + " " + Type.of(type).name() + (bot.isEmpty() ? "" : " " + bot) + " " + size(bytes));
			}

			if (++socketLogPending >= SOCKET_LOG_LINES) {
				flushSocketLog();
			}
		}
	}

	/** The groups counted so far, a line each: from..to, way, type, bot, how many and their bytes. Guarded by socketLog. */
	private void writeFrameGroups() {
		for (Map.Entry<String, FrameGroup> entry : frameGroups.entrySet()) {
			final FrameGroup group = entry.getValue();
			socketLog.println((group.count == 1 ? group.first : group.first + ".." + group.last) + " " + entry.getKey()
					+ (group.count == 1 ? "" : " x" + group.count) + " " + size(group.bytes));
		}

		frameGroups.clear();
	}

	private static String size(long bytes) {
		if (bytes < 1024) {
			return bytes + "B";
		}

		return bytes < 1024 * 1024 ? String.format(Locale.ROOT, "%.1fKB", bytes / 1024.0) : String.format(Locale.ROOT, "%.1fMB", bytes / (1024.0 * 1024));
	}

	/** Writes out what socket.log holds, the frame groups too (checkError flushes); PrintWriter keeps its write errors to itself, so one is thrown here. */
	private void flushSocketLog() {
		synchronized (socketLog) {
			writeFrameGroups();
			socketLogPending = 0;

			if (socketLog.checkError()) {
				throw new UncheckedIOException(new IOException("could not write socket.log in " + fleet.directory()));
			}
		}
	}

	/** Frames of one chatty type, way and bot in a row of socket.log; guarded by socketLog. */
	private static final class FrameGroup {
		private final String first;
		private String last;
		private int count;
		private long bytes;

		private FrameGroup(String first) {
			this.first = first;
		}
	}

	private ChunkListener chunkListener() {
		final ChunkListener current = chunkListener;

		if (current == null) {
			throw new IllegalStateException("the bot fleet has no chunk listener");
		}

		return current;
	}

	private ProfileListener profileListener() {
		final ProfileListener current = profileListener;

		if (current == null) {
			throw new IllegalStateException("the bot fleet has no profile listener");
		}

		return current;
	}

	private BotListener botListener() {
		final BotListener current = botListener;

		if (current == null) {
			throw new IllegalStateException("the bot fleet has no bot listener");
		}

		return current;
	}

	/** JVM exit: kill the fleet and wait for the reader to hand every chunk to its snapshot. */
	private void shutdown() {
		closing = true;

		if (!fleet.stop()) {
			return;
		}

		try {
			reader.join();
		} catch (InterruptedException e) {
			throw new IllegalStateException("interrupted waiting for the bot fleet reader", e);
		}

		flushSocketLog();
	}

	/** What one bot's frames (or the fleet's own) cost the mod so far: reader thread CPU (ns), allocations, frames and bytes. */
	private static final class Traffic {
		final AtomicLong cpu = new AtomicLong();
		final AtomicLong allocated = new AtomicLong();
		final AtomicLong framesIn = new AtomicLong();
		final AtomicLong bytesIn = new AtomicLong();
		final AtomicLong framesOut = new AtomicLong();
		final AtomicLong bytesOut = new AtomicLong();
	}

	/** The next message on the socket, or null when it ends cleanly between frames; stray bytes after one throw. */
	private static SocketMessage readFromSocket(InputStream socket) throws IOException {
		final int first = socket.read();

		if (first < 0) {
			return null;
		}

		final DataInputStream frame = new DataInputStream(socket);
		final int length = first << 24 | frame.readUnsignedByte() << 16 | frame.readUnsignedByte() << 8 | frame.readUnsignedByte();

		if (length <= 0 || length > MAX_FRAME) {
			throw new IOException("bad frame length " + length);
		}

		final byte[] bytes = new byte[length];
		frame.readFully(bytes);
		final DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
		final Type type = Type.of(U8.read(in));
		final SocketMessage message = type.factory.apply(STRING.read(in));
		message.deserialize(in);

		if (in.available() != 0) {
			throw new IOException(in.available() + " stray bytes after a " + type + " from the bot fleet");
		}

		return message;
	}

	/** The message onto the socket as a frame; how many bytes that took, its length included. Not flushed. */
	private static int writeToSocket(SocketMessage message, OutputStream socket) throws IOException {
		final ByteArrayOutputStream body = new ByteArrayOutputStream();
		final DataOutputStream out = new DataOutputStream(body);
		U8.write(out, message.type());
		STRING.write(out, message.bot);
		message.serialize(out);
		final int length = body.size();
		socket.write(length >>> 24);
		socket.write(length >>> 16);
		socket.write(length >>> 8);
		socket.write(length);
		body.writeTo(socket);
		return 4 + length;
	}
}

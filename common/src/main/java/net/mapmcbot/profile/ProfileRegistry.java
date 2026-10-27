package net.mapmcbot.profile;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.lang.management.ThreadInfo;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import net.mapmcbot.fleet.FleetSocketHandler;

/**
 * CPU and memory of the mod (Java) and of the fleet (node), whole, per thread and per bot: a
 * {@link ProfileReport} each time one is taken, kept for the profiler screen and always appended to
 * the log file, whether the screen is open or not.
 *
 * A profile is taken once a second (the one use of time here, see timingExceptions.txt), when someone
 * asks for one (the screen opening, its refresh, the player leaving the world), and when the fleet
 * takes one on an event of its own (a bot in a world or gone):
 * the fleet's part comes from the fleet, the mod's is read as it arrives. With no fleet running,
 * the mod's part alone.
 *
 * Thread-safe: the channel thread feeds it, anyone may read it.
 *
 * SINGLETON: one per game; a second registry throws.
 */
public final class ProfileRegistry implements ProfileListener {
	private static final AtomicBoolean CREATED = new AtomicBoolean();

	/** Reports kept for the screen. */
	private static final int HISTORY = 120;

	/**
	 * Rates are worked out against the newest report at least this much older (ms): two events close
	 * together (a formation sends every bot) would give rates over a few milliseconds, all noise.
	 */
	private static final long MIN_INTERVAL = 1000;

	/** A profile is taken every this many ms (see timingExceptions.txt), unless the one before is still on its way. */
	private static final long PROFILE_EVERY = 1000;

	/** The log is moved to <name>.prev.log (the one there before dropped) on opening and once it grows past this. */
	private static final long MAX_LOG = 64L * 1024 * 1024;

	/** Every this many reports, a line with the threads and bots that took the most CPU. */
	private static final int TOP_EVERY = 10;

	private static final int TOP = 5;

	private static final java.time.format.DateTimeFormatter TIME = java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss");

	private final FleetSocketHandler channel;
	/** Per bot that claimed them: {chunks, block entities, bytes of their NBT} held by the chunk registry. */
	private final Supplier<Map<String, long[]>> chunksByBot;
	/** Reopened on each rotation (see MAX_LOG). */
	private BufferedWriter log;
	private final File logFile;
	/** Bytes (chars, near enough) written to the log since it was opened. */
	private long logSize;
	/** What the log last said of each row that is a reading, by scope and name: one that did not change is left out. */
	private final Map<String, String> lastReading = new java.util.HashMap<String, String>();
	/** Reports written since the last top line (see TOP_EVERY). */
	private int sinceTop;
	private final LinkedList<ProfileReport> history = new LinkedList<ProfileReport>();
	private final com.sun.management.ThreadMXBean threads;
	private final com.sun.management.OperatingSystemMXBean system;

	private int lastRequest;
	/** The request whose profile has not arrived, 0 for none. */
	private volatile int waitingFor;

	public ProfileRegistry(FleetSocketHandler channel, File logFile, Supplier<Map<String, long[]>> chunksByBot) {
		if (!CREATED.compareAndSet(false, true)) {
			throw new IllegalStateException("ProfileRegistry is a singleton: one was already created");
		}

		if (!(ManagementFactory.getThreadMXBean() instanceof com.sun.management.ThreadMXBean)
				|| !(ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean)) {
			throw new IllegalStateException("the profiler reads CPU and allocations through com.sun.management, which this JVM ("
					+ System.getProperty("java.vm.name") + ") does not have");
		}

		threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
		system = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();

		if (!threads.isThreadCpuTimeSupported() || !threads.isThreadAllocatedMemorySupported()) {
			throw new IllegalStateException("this JVM cannot tell the CPU time or the allocations of its threads");
		}

		threads.setThreadCpuTimeEnabled(true);
		threads.setThreadAllocatedMemoryEnabled(true);

		this.channel = channel;
		this.chunksByBot = chunksByBot;
		this.logFile = logFile;

		if (!logFile.getParentFile().isDirectory() && !logFile.getParentFile().mkdirs()) {
			throw new IllegalStateException("cannot create " + logFile.getParentFile());
		}

		openLog();

		channel.setProfileListener(this);

		// Not an executor's: one would keep what the request throws to itself. Thrown here, it ends the
		// thread with its trace, and the profiles with it.
		final Thread every = new Thread(() -> {
			while (true) {
				try {
					Thread.sleep(PROFILE_EVERY);
				} catch (InterruptedException e) {
					throw new IllegalStateException("the profile timer was interrupted", e);
				}

				if (!isWaiting()) {
					request("1 s");
				}
			}
		}, "mapmcbot-profile-timer");
		every.setDaemon(true);
		every.start();
	}

	public File getLogFile() {
		return logFile;
	}

	/** Whether the fleet is up to be profiled too; without it a profile is the mod's alone. */
	public boolean isFleetRunning() {
		return channel.isRunning();
	}

	/** Whether the profile asked for last has not arrived yet. */
	public boolean isWaiting() {
		return waitingFor != 0;
	}

	/** Takes a profile: the fleet's arrives on the channel's thread (see onProfile); without a fleet, the mod's now. */
	public void request(String reason) {
		final int id;

		synchronized (this) {
			if (!channel.isRunning()) {
				record(reason, new ArrayList<ProfileReport.Row>());
				return;
			}

			id = ++lastRequest;
			waitingFor = id;
		}

		channel.requestProfile(id, reason);
	}

	/** The newest profile, null before the first. */
	public synchronized ProfileReport getLatest() {
		return history.isEmpty() ? null : history.getLast();
	}

	/** The profiles so far, oldest first. */
	public synchronized List<ProfileReport> getHistory() {
		return new ArrayList<ProfileReport>(history);
	}

	@Override
	public synchronized void onProfile(int id, String reason, String text) {
		record(reason, ProfileReport.parse(text));

		if (id != 0 && id == waitingFor) {
			waitingFor = 0;
		}
	}

	/** The profiles come once a second (see PROFILE_EVERY), not by frames. */
	@Override
	public void onFrame() {
	}

	private synchronized void record(String reason, List<ProfileReport.Row> rows) {
		rows.addAll(javaRows());
		final long now = System.currentTimeMillis();
		ProfileReport base = history.isEmpty() ? null : history.getFirst();

		for (ProfileReport before : history) {
			if (now - before.getTime() >= MIN_INTERVAL) {
				base = before;
			}
		}

		final ProfileReport report = new ProfileReport(now, reason, rows, base);
		history.add(report);

		if (history.size() > HISTORY) {
			history.removeFirst();
		}

		write(report);
	}

	/** The mod's own rows: the JVM whole, each of its threads, and each bot's frames (see FleetSocketHandler#channelRows). */
	private List<ProfileReport.Row> javaRows() {
		final List<ProfileReport.Row> rows = new ArrayList<ProfileReport.Row>();
		final MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
		final MemoryUsage nonHeap = ManagementFactory.getMemoryMXBean().getNonHeapMemoryUsage();
		long gcCount = 0;
		long gcTime = 0;

		for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
			gcCount += gc.getCollectionCount();
			gcTime += gc.getCollectionTime();
		}

		rows.add(new ProfileReport.Row("java", "cpu", ProfileReport.MS, system.getProcessCpuTime() / 1e6));
		rows.add(new ProfileReport.Row("java", "gc.time", ProfileReport.MS, gcTime));
		rows.add(new ProfileReport.Row("java", "gc.count", ProfileReport.COUNT, gcCount));
		rows.add(new ProfileReport.Row("java", "mem.heapUsed", ProfileReport.BYTES, heap.getUsed()));
		rows.add(new ProfileReport.Row("java", "mem.heapCommitted", ProfileReport.BYTES, heap.getCommitted()));
		rows.add(new ProfileReport.Row("java", "mem.heapMax", ProfileReport.BYTES, heap.getMax()));
		rows.add(new ProfileReport.Row("java", "mem.nonHeapUsed", ProfileReport.BYTES, nonHeap.getUsed()));
		rows.add(new ProfileReport.Row("java", "mem.physicalFree", ProfileReport.BYTES, system.getFreePhysicalMemorySize()));
		rows.add(new ProfileReport.Row("java", "mem.physicalTotal", ProfileReport.BYTES, system.getTotalPhysicalMemorySize()));
		rows.add(new ProfileReport.Row("java", "uptime.s", ProfileReport.NOW, ManagementFactory.getRuntimeMXBean().getUptime() / 1000.0));
		rows.add(new ProfileReport.Row("java", "cores", ProfileReport.NOW, Runtime.getRuntime().availableProcessors()));
		rows.add(new ProfileReport.Row("java", "threads", ProfileReport.NOW, threads.getThreadCount()));

		final long[] ids = threads.getAllThreadIds();
		final ThreadInfo[] infos = threads.getThreadInfo(ids);
		final long[] cpu = threads.getThreadCpuTime(ids);
		final long[] allocated = threads.getThreadAllocatedBytes(ids);

		for (int i = 0; i < ids.length; i++) {
			// Gone between the calls: nothing left of it to count.
			if (infos[i] == null || cpu[i] < 0 || allocated[i] < 0) {
				continue;
			}

			// The id keeps two threads of the same name apart, and each one's rates its own.
			final String scope = "java.thread:" + infos[i].getThreadName().replace('\t', ' ').replace('\n', ' ') + " (" + ids[i] + ")";
			rows.add(new ProfileReport.Row(scope, "cpu", ProfileReport.MS, cpu[i] / 1e6));
			rows.add(new ProfileReport.Row(scope, "alloc", ProfileReport.BYTES_SO_FAR, allocated[i]));
		}

		rows.addAll(channel.channelRows());

		for (Map.Entry<String, long[]> bot : chunksByBot.get().entrySet()) {
			final String scope = "java.bot:" + bot.getKey();
			rows.add(new ProfileReport.Row(scope, "chunks", ProfileReport.NOW, bot.getValue()[0]));
			rows.add(new ProfileReport.Row(scope, "blockEntities", ProfileReport.NOW, bot.getValue()[1]));
			rows.add(new ProfileReport.Row(scope, "mem.blockEntities", ProfileReport.BYTES, bot.getValue()[2]));
		}

		return rows;
	}

	/**
	 * Moves the log there was to <name>.prev.log and starts a new one, its first line saying how to
	 * read it. Each report is then its summary line ("# time reason: ..."), now and then a top line,
	 * and a line per scope with something to say: "time scope name=value ...", where a rate (ms as % of
	 * a core, n and bytes per second) of zero and a reading the log already said are left out; the
	 * first report, with no rates, gives what each count is so far.
	 */
	private void openLog() {
		final File previous = new File(logFile.getParentFile(), logFile.getName().replaceFirst("\\.log$", "") + ".prev.log");

		try {
			if (log != null) {
				log.close();
			}

			if (logFile.isFile()) {
				java.nio.file.Files.move(logFile.toPath(), previous.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			}

			log = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(logFile, false), StandardCharsets.UTF_8));
			logSize = 0;
			lastReading.clear();
			sinceTop = TOP_EVERY;
			line("# session " + LocalDateTime.now().withNano(0) + ": \"time scope name=value ...\"; rates: ms as % of a core, n and bytes per second;"
					+ " B, # and % are readings; zero rates and readings unchanged since the log last said them are left out");
			log.flush();
		} catch (IOException e) {
			throw new UncheckedIOException("could not open " + logFile, e);
		}
	}

	private void line(String text) throws IOException {
		log.write(text);
		log.newLine();
		logSize += text.length() + 1;
	}

	/** Appends the report to the log (see openLog). Flushed: reports are once a second. */
	private void write(ProfileReport report) {
		final String time = time(report);

		try {
			line("# " + time + " " + oneLine(report.getReason()) + ": " + summary(report));

			if (report.hasRates() && ++sinceTop >= TOP_EVERY) {
				sinceTop = 0;
				line("# " + time + " top cpu: " + top(report));
			}

			rows(time, "", report);
			finish();
		} catch (IOException e) {
			throw new UncheckedIOException("could not write " + logFile, e);
		}
	}

	/** Each scope's line, those with nothing left to say left out. */
	private void rows(String time, String prefix, ProfileReport report) throws IOException {
		for (String scope : report.scopes("")) {
			final StringBuilder out = new StringBuilder();

			for (ProfileReport.Row row : report.rows(scope).values()) {
				final String value = shown(report, row);

				if (value == null) {
					continue;
				}

				if (!isCount(row.getUnit()) && value.equals(lastReading.put(prefix + scope + "\t" + row.getName(), value))) {
					continue;
				}

				out.append(' ').append(row.getName()).append('=').append(value);
			}

			if (out.length() > 0) {
				line(time + " " + prefix + scope + out);
			}
		}
	}

	/** Flushes, and rotates once the log grew past MAX_LOG. */
	private void finish() throws IOException {
		log.flush();

		if (logSize > MAX_LOG) {
			openLog();
		}
	}

	/** The scopes (threads and bots, not the processes whole) that took the most CPU since the report before, their ms rows added up. */
	private static String top(ProfileReport report) {
		final List<Map.Entry<String, Double>> scopes = new ArrayList<Map.Entry<String, Double>>();

		for (String scope : report.scopes("")) {
			if (scope.equals("java") || scope.equals("node")) {
				continue;
			}

			double cpu = 0;

			for (ProfileReport.Row row : report.rows(scope).values()) {
				if (row.getUnit().equals(ProfileReport.MS)) {
					cpu += row.getRate();
				}
			}

			if (cpu > 0) {
				scopes.add(new java.util.AbstractMap.SimpleEntry<String, Double>(scope, cpu));
			}
		}

		scopes.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
		final StringBuilder out = new StringBuilder();

		for (int i = 0; i < Math.min(TOP, scopes.size()); i++) {
			out.append(i == 0 ? "" : ", ").append(scopes.get(i).getKey()).append(' ').append(percent(scopes.get(i).getValue()));
		}

		return out.length() == 0 ? "-" : out.toString();
	}

	/** The row as the log says it: its rate when it has one, else its value; null for a zero. */
	private static String shown(ProfileReport report, ProfileReport.Row row) {
		final String unit = row.getUnit();

		if (isCount(unit) && report.hasRates()) {
			final double rate = row.getRate();

			if (unit.equals(ProfileReport.MS)) {
				return Math.abs(rate) < 0.05 ? null : percent(rate);
			}

			if (unit.equals(ProfileReport.COUNT)) {
				return Math.abs(rate) < 0.05 ? null : decimal(rate) + "/s";
			}

			return Math.abs(rate) < 0.5 ? null : bytes(rate) + "/s";
		}

		final double value = row.getValue();

		if (value == 0) {
			return null;
		}

		switch (unit) {
			case ProfileReport.MS:
				return decimal(value) + "ms";
			case ProfileReport.BYTES:
			case ProfileReport.BYTES_SO_FAR:
				return bytes(value);
			case ProfileReport.PERCENT:
				return percent(value);
			default:
				return decimal(value);
		}
	}

	/** Whether the unit only goes up, its rate being what the log says. */
	private static boolean isCount(String unit) {
		return unit.equals(ProfileReport.MS) || unit.equals(ProfileReport.COUNT) || unit.equals(ProfileReport.BYTES_SO_FAR);
	}

	private static String time(ProfileReport report) {
		return LocalDateTime.ofInstant(Instant.ofEpochMilli(report.getTime()), ZoneId.systemDefault()).format(TIME);
	}

	private static String oneLine(String text) {
		return text.replace('\t', ' ').replace('\n', ' ');
	}

	/** Bytes, to K, M or G past a thousand and twenty-four. */
	private static String bytes(double value) {
		final String[] units = {"B", "K", "M", "G"};
		int unit = 0;

		while (Math.abs(value) >= 1024 && unit < units.length - 1) {
			value /= 1024;
			unit++;
		}

		return (unit == 0 ? Long.toString(Math.round(value)) : decimal(value)) + units[unit];
	}

	/** At most one decimal, none when it is whole. */
	private static String decimal(double value) {
		final double rounded = Math.round(value * 10) / 10.0;
		return rounded == Math.rint(rounded) && Math.abs(rounded) < 1e15 ? Long.toString((long) rounded) : String.format(Locale.ROOT, "%.1f", rounded);
	}

	/** The processes whole, in a line: CPU since the report before (on the first, the average since each started), memory now. */
	public static String summary(ProfileReport report) {
		final StringBuilder out = new StringBuilder();
		out.append(String.format(Locale.ROOT, "java cpu %s, heap %s of %s", cpu(report, "java", "cpu"),
				megabytes(report.value("java", "mem.heapUsed")), megabytes(report.value("java", "mem.heapMax"))));

		if (report.row("node", "mem.rss") != null) {
			out.append(String.format(Locale.ROOT, " | node cpu %s, rss %s, bots %d", cpu(report, "node", "cpu.user", "cpu.system"),
					megabytes(report.value("node", "mem.rss")), (int) report.value("node", "bots")));
		} else {
			out.append(" | no fleet");
		}

		if (report.hasRates()) {
			out.append(String.format(Locale.ROOT, " | over %.1f s", report.getInterval() / 1000.0));
		}

		return out.toString();
	}

	/** CPU of a process, the names added up: as % of a core since the report before, or its average since it started ("avg"). */
	private static String cpu(ProfileReport report, String scope, String... names) {
		double value = 0;

		for (String name : names) {
			value += report.hasRates() ? report.rate(scope, name) : report.value(scope, name);
		}

		if (report.hasRates()) {
			return percent(value);
		}

		return percent(value / (report.value(scope, "uptime.s") * 1000) * 100) + " avg";
	}

	private static String percent(double value) {
		return Double.isNaN(value) ? "-" : String.format(Locale.ROOT, "%.1f%%", value);
	}

	private static String megabytes(double bytes) {
		return String.format(Locale.ROOT, "%.0f MB", bytes / (1024 * 1024));
	}
}

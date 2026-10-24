package net.mapmcbot.bot;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The blocks each bot may place (place, place_wsr take one off its inventory into its hand), as items
 * keyed name@metadata ("minecraft:wool@14"), out of the catalog: every block the game has. For now one
 * list for every bot; it is asked for by bot all the same, and sent to each as its own (see
 * FleetProtocol#ALLOWED_PLACE). A whitelist: the file keeps the ones allowed, one key a line,
 * written as they change; with no file yet, every block.
 */
public final class PlaceBlocks {
	private final File file;
	private final List<String> catalog;
	private final Set<String> allowed = new LinkedHashSet<String>();
	/** Told of each change: every bot's list changed, for now. */
	private Runnable listener;

	public PlaceBlocks(File file, List<String> catalog) {
		this.file = file;
		this.catalog = Collections.unmodifiableList(new ArrayList<String>(catalog));
		load();
	}

	/** Every block the game has, as the keys the lists are made of. */
	public List<String> getCatalog() {
		return catalog;
	}

	/** Once, before any change. */
	public void setListener(Runnable listener) {
		this.listener = listener;
	}

	/** The blocks the bot may place, in the catalog's order. */
	public synchronized List<String> allowed(String bot) {
		final List<String> keys = new ArrayList<String>();

		for (String key : catalog) {
			if (allowed.contains(key)) {
				keys.add(key);
			}
		}

		return keys;
	}

	public synchronized boolean isAllowed(String key) {
		return allowed.contains(key);
	}

	/** The block allowed or not, saved and sent to the bots when it changes. */
	public void setAllowed(String key, boolean allowed) {
		if (!catalog.contains(key)) {
			throw new IllegalArgumentException("no block " + key + " in the catalog");
		}

		synchronized (this) {
			if (allowed ? !this.allowed.add(key) : !this.allowed.remove(key)) {
				return;
			}

			save();
		}

		if (listener != null) {
			listener.run();
		}
	}

	private void load() {
		if (!file.isFile()) {
			allowed.addAll(catalog);
			return;
		}

		try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
			String line;

			while ((line = reader.readLine()) != null) {
				// A block this version has not (written by another) is kept all the same.
				if (!line.matches("[a-z0-9_.-]+:[a-z0-9_./-]+@[0-9]+")) {
					throw new IllegalStateException("Malformed block line in " + file + ": " + line);
				}

				allowed.add(line);
			}
		} catch (IOException e) {
			throw new UncheckedIOException("Could not read " + file, e);
		}
	}

	private void save() {
		final File parent = file.getParentFile();

		if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
			throw new IllegalStateException("Could not create " + parent);
		}

		// Written beside it and moved over it: a crash mid-write leaves the last whole file.
		final File temporary = new File(file.getPath() + ".tmp");

		try {
			try (Writer writer = new OutputStreamWriter(new FileOutputStream(temporary), StandardCharsets.UTF_8)) {
				for (String key : allowed) {
					writer.write(key + '\n');
				}
			}

			Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			throw new UncheckedIOException("Could not write " + file, e);
		}
	}
}

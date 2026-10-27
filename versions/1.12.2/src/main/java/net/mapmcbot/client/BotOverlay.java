package net.mapmcbot.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import net.mapmcbot.area.ChestStore;
import net.mapmcbot.bot.BotRegistry;
import net.mapmcbot.profile.ProfileRegistry;
import net.mapmcbot.profile.ProfileReport;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawableHelper;
import net.minecraft.client.util.Window;

/** The bots and their status, top right of the HUD, and a card with a preview of the fleet's profile, top left. */
public final class BotOverlay {
	private static final int MARGIN = 4;
	private static final int CARD_WIDTH = 150;
	private static final int CARD_PADDING = 3;
	private static final int CARD_BACKGROUND = 0xA0101216;
	private static final int DIM = 0xFF8A919C;
	private static final int HIGHLIGHT_ALPHA = 0x80;

	/** Where the fleet's own rows are in its report. */
	private static final String MAIN_SCOPE = "node";

	private BotOverlay() {
	}

	public static void render() {
		final BotRegistry bots = MapMcBotClient.botsOrNull();

		if (bots == null || bots.getBots().isEmpty()) {
			return;
		}

		final MinecraftClient client = MinecraftClient.getInstance();
		final Window window = new Window(client);
		final List<String> names = new ArrayList<String>(bots.getBots());
		Collections.sort(names);

		final String selected = AreaRenderer.selected();
		int y = MARGIN;

		for (String name : names) {
			final String line = client.textRenderer.trimToWidth(name + " " + bots.getStatus(name), window.getWidth() / 2);
			final int x = window.getWidth() - MARGIN - client.textRenderer.getStringWidth(line);

			// The bot of the render mode (see AreaRenderer.nextMode), on its colour.
			if (name.equals(selected)) {
				DrawableHelper.fill(x - 2, y - 1, window.getWidth() - MARGIN + 2, y + client.textRenderer.fontHeight + 1, HIGHLIGHT_ALPHA << 24 | MapMcBotClient.colorOf(name).getRgb());
			}

			client.textRenderer.drawWithShadow(line, x, y, 0xFFFFFF);
			y += client.textRenderer.fontHeight + 2;
		}

		final ProfileRegistry profile = MapMcBotClient.profile();
		final ProfileReport main = profile.getLatest();

		if (main != null) {
			card(client, MARGIN, MARGIN, MAIN_SCOPE, main);
		}
	}

	/** The fleet: its bots, CPU as % of the whole server, the server's RAM in use over its total, and under them the fleet's own. */
	private static int card(MinecraftClient client, int x, int y, String scope, ProfileReport report) {
		final int line = client.textRenderer.fontHeight + 2;
		final double cores = report.value(scope, "cpu.count");
		// ms rates are % of one core: over the server's cores, a share of all of it.
		final double cpu = (report.rate(scope, "cpu.user") + report.rate(scope, "cpu.system")) / cores;
		final double machine = 100 * report.rate(scope, "cpu.system.busy") / report.rate(scope, "cpu.system.all");
		final List<String> lines = new ArrayList<String>();
		lines.add("bots " + MapMcBotClient.bots().getBots().size());
		lines.add("CPU " + (Double.isNaN(cpu) ? "--" : String.format(Locale.ROOT, "%.1f%%", cpu))
			+ " / " + (Double.isNaN(machine) ? "--" : String.format(Locale.ROOT, "%.1f%%", machine)) + " total");
		lines.add("RAM " + bytes(report.value(scope, "mem.system.used")) + " / " + bytes(report.value(scope, "mem.system.total")));
		lines.add("columns " + bytes(report.value(scope, "mem.columns")));

		final ChestStore chests = MapMcBotClient.chests();
		lines.add("chests " + (chests == null ? "--" : bytes(chests.memoryBytes())));
		lines.add("fleet " + bytes(report.value(scope, "mem.rss")));

		final int height = CARD_PADDING * 2 + lines.size() * line - 2;
		DrawableHelper.fill(x, y, x + CARD_WIDTH, y + height, CARD_BACKGROUND);

		for (int i = 0; i < lines.size(); i++) {
			client.textRenderer.drawWithShadow(lines.get(i), x + CARD_PADDING, y + CARD_PADDING + i * line, i == 0 ? 0xFFFFFF : i == lines.size() - 1 ? DIM : 0xE6E8EB);
		}

		return height;
	}

	private static String bytes(double value) {
		if (value < 1L << 20) {
			return String.format(Locale.ROOT, "%.0f KB", value / (1L << 10));
		}

		return value >= 1L << 30 ? String.format(Locale.ROOT, "%.1f GB", value / (1L << 30)) : String.format(Locale.ROOT, "%.0f MB", value / (1L << 20));
	}
}

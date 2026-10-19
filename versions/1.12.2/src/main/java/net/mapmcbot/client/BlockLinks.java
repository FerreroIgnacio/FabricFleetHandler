package net.mapmcbot.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.minecraft.text.ClickEvent;
import net.minecraft.text.LiteralText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;

/**
 * The blocks a chat message names (x,y,z) as links: clicked, the block is highlighted in the world
 * (see AreaRenderer), clicked again it is not. The click runs #highlight X Y Z, which toggles it.
 */
final class BlockLinks {
	private static final Pattern BLOCK = Pattern.compile("(-?\\d+),(-?\\d+),(-?\\d+)");

	private static final Set<BlockPos> HIGHLIGHTED = ConcurrentHashMap.newKeySet();

	private BlockLinks() {
	}

	/** The message with each block in it a link. */
	static Text text(String message) {
		final Text out = new LiteralText("");
		final Matcher matcher = BLOCK.matcher(message);
		int from = 0;

		while (matcher.find()) {
			out.append(message.substring(from, matcher.start()));
			final Text link = new LiteralText(matcher.group());
			final Style style = new Style();
			style.setFormatting(Formatting.AQUA);
			style.setUnderline(true);
			style.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "#highlight " + matcher.group(1) + " " + matcher.group(2) + " " + matcher.group(3)));
			link.setStyle(style);
			out.append(link);
			from = matcher.end();
		}

		out.append(message.substring(from));
		return out;
	}

	/** #highlight X Y Z: the block highlighted, or no longer. */
	static void toggle(String[] words) {
		if (words.length != 4) {
			throw new IllegalArgumentException("#highlight X Y Z");
		}

		final BlockPos pos = new BlockPos(Integer.parseInt(words[1]), Integer.parseInt(words[2]), Integer.parseInt(words[3]));

		if (!HIGHLIGHTED.remove(pos)) {
			HIGHLIGHTED.add(pos);
		}
	}

	static List<BlockPos> highlighted() {
		return new ArrayList<BlockPos>(HIGHLIGHTED);
	}
}

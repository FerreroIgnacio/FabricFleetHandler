package net.mapmcbot.fleet.message;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;


/** A primitive as the JSON the fleet's actions.js runs (its name in "action", its fields beside it) into its message. */
public final class Primitives {
	private Primitives() {
	}

	public static SocketMessage fromJson(String bot, String json) {
		final JsonObject a = new JsonParser().parse(json).getAsJsonObject();
		final String name = field(a, "action").getAsString();

		switch (name) {
			case "goto":
				if (a.has("spot")) {
					return new Goto(bot, Goto.SPOT, pos(field(a, "spot").getAsJsonObject()));
				}

				if (a.has("area")) {
					final JsonObject area = a.getAsJsonObject("area");
					return new Goto(bot, Goto.AREA, new int[] {field(area, "minX").getAsInt(), field(area, "minY").getAsInt(), field(area, "minZ").getAsInt(), field(area, "maxX").getAsInt(), field(area, "maxY").getAsInt(), field(area, "maxZ").getAsInt()});
				}

				if (a.has("look")) {
					return new Goto(bot, Goto.LOOK, pos(a.getAsJsonObject("look")));
				}

				throw new IllegalArgumentException("goto needs a spot, an area or a look: " + json);

			case "interact": {
				final String button = field(a, "button").getAsString();

				if (!button.equals("left") && !button.equals("right")) {
					throw new IllegalArgumentException("button must be left or right: " + json);
				}

				final JsonObject target = field(a, "target").getAsJsonObject();
				final String kind = field(target, "kind").getAsString();

				if (!kind.equals("block") && !kind.equals("entity")) {
					throw new IllegalArgumentException("target of unknown kind " + kind + ": " + json);
				}

				final int[] block = kind.equals("block") ? pos(target) : null;
				final String entity = kind.equals("entity") ? field(target, "uuid").getAsString() : null;
				return new Interact(bot, button.equals("left") ? 0 : 1, block, entity, a.has("expect") ? expect(a.getAsJsonObject("expect"), json) : null, deadline(a), turnSpeed(a));
			}

			case "break":
				return new Break(bot, pos(field(a, "target").getAsJsonObject()), deadline(a), turnSpeed(a));

			case "break_wsr":
				return new BreakWsr(bot, pos(field(a, "target").getAsJsonObject()), field(a, "radius").getAsInt(), deadline(a), turnSpeed(a));

			case "break_wsrp":
				return new BreakWsrp(bot, pos(field(a, "target").getAsJsonObject()), field(a, "radius").getAsInt(), pos(field(a, "end").getAsJsonObject()), deadline(a), turnSpeed(a));

			case "break_wsrpa":
				return new BreakWsrpa(bot, pos(field(a, "target").getAsJsonObject()), field(a, "radius").getAsInt(), pos(field(a, "end").getAsJsonObject()), deadline(a), turnSpeed(a));

			case "break_pac2c": {
				final JsonArray targets = field(a, "targets").getAsJsonArray();

				if (targets.size() == 0) {
					throw new IllegalArgumentException("break_pac2c needs a target at least: " + json);
				}

				final int[][] blocks = new int[targets.size()][];

				for (int i = 0; i < blocks.length; i++) {
					blocks[i] = pos(targets.get(i).getAsJsonObject());
				}

				double[] stand = null;

				if (a.has("stand")) {
					final JsonObject at = a.getAsJsonObject("stand");
					stand = new double[] {field(at, "x").getAsDouble(), field(at, "z").getAsDouble(), field(at, "line").getAsDouble()};
				}

				return new BreakPac2c(bot, blocks, pos(field(a, "end").getAsJsonObject()), stand, deadline(a), turnSpeed(a));
			}

			case "band":
				return new Band(bot, pos(field(a, "from").getAsJsonObject()), pos(field(a, "to").getAsJsonObject()), a.has("order") ? a.get("order").getAsString() : null, deadline(a), turnSpeed(a));

			case "place":
				return new Place(bot, pos(field(a, "target").getAsJsonObject()), state(a), deadline(a), turnSpeed(a));

			case "place_wsr":
				return new PlaceWsr(bot, pos(field(a, "target").getAsJsonObject()), field(a, "radius").getAsInt(), state(a), deadline(a), turnSpeed(a));

			case "place_wsrp":
				return new PlaceWsrp(bot, pos(field(a, "target").getAsJsonObject()), field(a, "radius").getAsInt(), pos(field(a, "end").getAsJsonObject()), state(a), deadline(a), turnSpeed(a));

			case "place_wsrpa":
				return new PlaceWsrpa(bot, pos(field(a, "target").getAsJsonObject()), field(a, "radius").getAsInt(), pos(field(a, "end").getAsJsonObject()), state(a), deadline(a), turnSpeed(a));

			case "move":
				return new Move(bot, field(a, "from").getAsInt(), field(a, "to").getAsInt());

			case "item_fill": {
				final JsonArray items;

				if (a.has("items")) {
					items = a.getAsJsonArray("items");
				} else {
					items = new JsonArray();
					items.add(field(a, "item"));
				}

				final List<ItemMatch> matches = new ArrayList<ItemMatch>(items.size());

				for (JsonElement item : items) {
					matches.add(match(item.getAsJsonObject()));
				}

				return new ItemFill(bot, matches, slots(field(a, "targets").getAsJsonArray()), a.has("sources") ? slots(a.getAsJsonArray("sources")) : null, a.has("count") ? a.get("count").getAsInt() : null);
			}

			case "wait_slot": {
				final JsonArray slots = field(a, "slots").getAsJsonArray();
				final List<SlotWait> waits = new ArrayList<SlotWait>(slots.size());

				for (JsonElement element : slots) {
					final JsonObject wait = element.getAsJsonObject();
					waits.add(new SlotWait(field(wait, "slot").getAsInt(), wait.has("min") ? wait.get("min").getAsInt() : 1, wait.has("item") ? match(wait.getAsJsonObject("item")) : null));
				}

				return new WaitSlot(bot, waits, deadline(a));
			}

			case "hotbar":
				return new Hotbar(bot, field(a, "slot").getAsInt());

			case "shift_move":
				return new ShiftMove(bot, field(a, "slot").getAsInt());

			case "select_trade":
				return new SelectTrade(bot, field(a, "trade").getAsInt());

			case "look_at":
				return new LookAt(bot, field(a, "yaw").getAsDouble(), field(a, "pitch").getAsDouble(), turnSpeed(a));

			case "drop":
				return new Drop(bot, slots(field(a, "slots").getAsJsonArray()));

			case "close_window":
				return new CloseWindow(bot);

			case "stop":
				return new Stop(bot);

			default:
				throw new IllegalArgumentException("no primitive named " + name + " for the fleet");
		}
	}

	private static JsonElement field(JsonObject object, String key) {
		final JsonElement value = object.get(key);

		if (value == null || value.isJsonNull()) {
			throw new IllegalArgumentException(key + " is required in " + object);
		}

		return value;
	}

	private static int[] pos(JsonObject pos) {
		return new int[] {field(pos, "x").getAsInt(), field(pos, "y").getAsInt(), field(pos, "z").getAsInt()};
	}

	private static int[] slots(JsonArray slots) {
		final int[] result = new int[slots.size()];

		for (int i = 0; i < result.length; i++) {
			result[i] = slots.get(i).getAsInt();
		}

		return result;
	}

	private static String state(JsonObject a) {
		return a.has("state") ? a.get("state").getAsString() : null;
	}

	private static Integer deadline(JsonObject a) {
		return a.has("deadline") ? a.get("deadline").getAsInt() : null;
	}

	private static Double turnSpeed(JsonObject a) {
		return a.has("turn_speed") ? a.get("turn_speed").getAsDouble() : null;
	}

	private static Expect expect(JsonObject expect, String json) {
		final String kind = field(expect, "kind").getAsString();

		switch (kind) {
			case "window":
				return Expect.window(field(expect, "type").getAsString());
			case "block":
				return Expect.block(pos(expect), field(expect, "state").getAsString());
			case "changed":
				return Expect.changed(pos(expect));
			case "filled":
				return Expect.filled(pos(expect));
			case "hurt":
				return Expect.hurt(field(expect, "id").getAsInt());
			default:
				throw new IllegalArgumentException("expect of unknown kind " + kind + ": " + json);
		}
	}

	private static ItemMatch match(JsonObject spec) {
		if (spec.has("match") && spec.has("ignore")) {
			throw new IllegalArgumentException("an item match takes match or ignore, not both: " + spec);
		}

		final String paths = spec.has("match") ? "match" : spec.has("ignore") ? "ignore" : null;

		if (paths == null && spec.has("nbt")) {
			throw new IllegalArgumentException("an item match's nbt says nothing without match or ignore: " + spec);
		}

		final String name = spec.has("name") ? spec.get("name").getAsString() : null;
		final Integer metadata = spec.has("metadata") ? spec.get("metadata").getAsInt() : null;

		if (paths == null) {
			return new ItemMatch(name, metadata, ItemMatch.NONE, null, null);
		}

		final List<String> list = new ArrayList<String>();

		for (JsonElement path : spec.getAsJsonArray(paths)) {
			list.add(path.getAsString());
		}

		return new ItemMatch(name, metadata, paths.equals("match") ? ItemMatch.MATCH : ItemMatch.IGNORE, hex(field(spec, "nbt").getAsString()), list);
	}

	private static byte[] hex(String hex) {
		if (hex.length() % 2 != 0) {
			throw new IllegalArgumentException("odd length hex NBT: " + hex);
		}

		final byte[] bytes = new byte[hex.length() / 2];

		for (int i = 0; i < bytes.length; i++) {
			final int high = Character.digit(hex.charAt(2 * i), 16);
			final int low = Character.digit(hex.charAt(2 * i + 1), 16);

			if (high < 0 || low < 0) {
				throw new IllegalArgumentException("not hex NBT: " + hex);
			}

			bytes[i] = (byte) (high << 4 | low);
		}

		return bytes;
	}
}

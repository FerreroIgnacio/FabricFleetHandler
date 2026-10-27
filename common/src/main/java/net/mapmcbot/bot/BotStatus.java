package net.mapmcbot.bot;

/**
 * What a bot is doing, as the fleet reports it (STATE): idle, doing a primitive, or in error in
 * one with the whole message (what was expected, what was found). An error stays until the bot's
 * next primitive; the bot is neither retried nor reset.
 *
 * Immutable: a new one comes with each change.
 */
public final class BotStatus {
	public enum Kind {
		IDLE, DOING, ERROR
	}

	public static final BotStatus IDLE = new BotStatus(Kind.IDLE, "", "", null, null, null);

	private final Kind kind;
	private final String primitive;
	private final String message;
	/** The block its feet were in, {x, y, z}; null when not in the world yet, or not told (IDLE). */
	private final int[] feet;
	/** Its feet as they were, {x, y, z}, anywhere in that block; null with feet. */
	private final double[] position;
	/** Where its head looked, {yaw, pitch} (rad, as mineflayer has them: yaw 0 toward -z); null with feet. */
	private final double[] look;

	public BotStatus(Kind kind, String primitive, String message, int[] feet, double[] position, double[] look) {
		this.kind = kind;
		this.primitive = primitive;
		this.message = message;
		this.feet = feet;
		this.position = position;
		this.look = look;
	}

	public Kind getKind() {
		return kind;
	}

	public String getPrimitive() {
		return primitive;
	}

	public boolean hasFeet() {
		return feet != null;
	}

	/** The block its feet were in as the state changed, {x, y, z}. */
	public int[] getFeet() {
		if (feet == null) {
			throw new IllegalStateException("expected where the bot stands with its state " + this + ", found it not told");
		}

		return feet.clone();
	}

	/** Its feet as they were as the state changed, {x, y, z}: not the middle of their block. */
	public double[] getPosition() {
		if (position == null) {
			throw new IllegalStateException("expected where the bot stands with its state " + this + ", found it not told");
		}

		return position.clone();
	}

	/** Where its head looked as the state changed, {yaw, pitch}. */
	public double[] getLook() {
		if (look == null) {
			throw new IllegalStateException("expected where the bot looks with its state " + this + ", found it not told");
		}

		return look.clone();
	}

	/** Why the primitive failed; empty unless in error. */
	public String getMessage() {
		return message;
	}

	/** idle, doing goto, error goto: expected a path to 1,2,3, found none. */
	@Override
	public String toString() {
		switch (kind) {
			case DOING:
				return "doing " + primitive;
			case ERROR:
				return "error " + primitive + ": " + message;
			default:
				return "idle";
		}
	}
}

package net.mapmcbot.fleet.message;

import net.mapmcbot.fleet.types.MinecraftProtocolType;
import static net.mapmcbot.fleet.types.MinecraftProtocolType.*;
import static net.mapmcbot.fleet.types.SocketProtocolType.*;


import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

/**
 * What an Interact expects of its click, null for the interaction's own: a window of the type
 * opening (WINDOW), the block taking the state (BLOCK), the block changing (CHANGED) or filling
 * (FILLED), the entity of the id hurt (HURT).
 */
public final class Expect {
	public static final int WINDOW = 1;
	public static final int BLOCK = 2;
	public static final int CHANGED = 3;
	public static final int FILLED = 4;
	public static final int HURT = 5;

	public final int kind;
	/** WINDOW's type. */
	public final String window;
	/** BLOCK's, CHANGED's and FILLED's {x, y, z}. */
	public final int[] block;
	/** BLOCK's state. */
	public final String state;
	/** HURT's entity id. */
	public final int entity;

	private Expect(int kind, String window, int[] block, String state, int entity) {
		this.kind = kind;
		this.window = window;
		this.block = block;
		this.state = state;
		this.entity = entity;
	}

	public static Expect window(String type) {
		return new Expect(WINDOW, type, null, null, 0);
	}

	public static Expect block(int[] block, String state) {
		return new Expect(BLOCK, null, block, state, 0);
	}

	public static Expect changed(int[] block) {
		return new Expect(CHANGED, null, block, null, 0);
	}

	public static Expect filled(int[] block) {
		return new Expect(FILLED, null, block, null, 0);
	}

	public static Expect hurt(int entity) {
		return new Expect(HURT, null, null, null, entity);
	}

	/** u8 kind (0 none) and its fields. */
	static void write(DataOutput out, Expect expect) throws IOException {
		if (expect == null) {
			U8.write(out, 0);
			return;
		}

		U8.write(out, expect.kind);

		switch (expect.kind) {
			case WINDOW:
				STRING.write(out, expect.window);
				break;
			case BLOCK:
				MinecraftProtocolType.BLOCK.write(out, expect.block);
				STRING.write(out, expect.state);
				break;
			case CHANGED:
			case FILLED:
				MinecraftProtocolType.BLOCK.write(out, expect.block);
				break;
			case HURT:
				I32.write(out, expect.entity);
				break;
			default:
				throw new IllegalStateException("expect of kind " + expect.kind);
		}
	}

	static Expect read(DataInput in) throws IOException {
		final int kind = U8.read(in);

		switch (kind) {
			case 0:
				return null;
			case WINDOW:
				return window(STRING.read(in));
			case BLOCK:
				return block(MinecraftProtocolType.BLOCK.read(in), STRING.read(in));
			case CHANGED:
				return changed(MinecraftProtocolType.BLOCK.read(in));
			case FILLED:
				return filled(MinecraftProtocolType.BLOCK.read(in));
			case HURT:
				return hurt(I32.read(in));
			default:
				throw new IOException("bad expect kind " + kind);
		}
	}
}

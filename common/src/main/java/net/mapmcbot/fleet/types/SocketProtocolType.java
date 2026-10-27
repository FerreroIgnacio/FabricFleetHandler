package net.mapmcbot.fleet.types;

import net.mapmcbot.fleet.types.socket.*;

/**
 * The socket's own pieces: numbers, strings (a u16 byte length and UTF-8), byte arrays (an i32
 * length and them) and optional fields (a u8 present and the value).
 */
public abstract class SocketProtocolType<T> extends ProtocolType<T> {
	/** The most a frame, or a byte array in one, takes. */
	public static final int MAX_FRAME = 64 * 1024 * 1024;

	public static final SocketProtocolType<Integer> U8 = new U8Type();

	public static final SocketProtocolType<Integer> U16 = new U16Type();

	public static final SocketProtocolType<Integer> I32 = new I32Type();

	public static final SocketProtocolType<Double> F64 = new F64Type();

	/** A u8, 0 or 1. */
	public static final SocketProtocolType<Boolean> BOOL = new BoolType();

	/** A u16 byte length and UTF-8. */
	public static final SocketProtocolType<String> STRING = new StringType();

	/** An i32 length and the bytes. */
	public static final SocketProtocolType<byte[]> BYTES = new BytesType();

	/** A text as BYTES, UTF-8: one longer than a STRING takes. */
	public static final SocketProtocolType<String> TEXT = new TextType();

	/** An i32 count and each i32 (slots). */
	public static final SocketProtocolType<int[]> INTS = new IntsType();

	public static final SocketProtocolType<Integer> OPTIONAL_I32 = optional(I32);
	public static final SocketProtocolType<Double> OPTIONAL_F64 = optional(F64);
	public static final SocketProtocolType<String> OPTIONAL_STRING = optional(STRING);

	/** A u8 present and, when it is, the value; null for none. */
	public static <T> SocketProtocolType<T> optional(ProtocolType<T> type) {
		return new OptionalType<T>(type);
	}
}

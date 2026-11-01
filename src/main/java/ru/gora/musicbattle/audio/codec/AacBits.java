package ru.gora.musicbattle.audio.codec;

/**
 * Reads bits most significant first from a byte array, as AAC and ALAC store them.
 *
 * <p>The array must carry at least {@link #PADDING} zero bytes after the last byte of the packet: reads near the end
 * then never need a bounds check, and a damaged packet that runs past its end reads zeros. Whether that happened is
 * kept, so the decoder can refuse the packet once an element is complete instead of trusting what it read.
 */
final class AacBits {
    /** Zero bytes every buffer handed to {@link #reset} must have after the packet. */
    static final int PADDING = 8;

    private byte[] data = new byte[PADDING];
    private int start;
    private int position;
    private int end;

    /** Starts reading {@code length} bytes at {@code offset}; {@code data} must have the padding behind them. */
    void reset(byte[] data, int offset, int length) {
        this.data = data;
        this.start = offset * 8;
        this.position = start;
        this.end = (offset + length) * 8;
    }

    /** Reads {@code n} bits, 0 to 25. */
    int read(int n) {
        int value = peek(n);
        position += n;
        return value;
    }

    boolean readBit() {
        int p = position;
        int bit = (data[p >>> 3] >>> (7 - (p & 7))) & 1;
        position = p + 1;
        return bit != 0;
    }

    /** The next {@code n} bits, 1 to 25, without consuming them. */
    int peek(int n) {
        int p = position;
        int at = p >>> 3;
        if (at + 4 > data.length) {
            // Only reachable after an overrun so long that the padding is used up: everything past it is zero.
            return 0;
        }
        int word = (data[at] & 0xFF) << 24 | (data[at + 1] & 0xFF) << 16 | (data[at + 2] & 0xFF) << 8
                | (data[at + 3] & 0xFF);
        return (word << (p & 7)) >>> (32 - n);
    }

    void skip(int n) {
        position += n;
    }

    /** Reads up to 32 bits. */
    long readLong(int n) {
        if (n <= 25) {
            return read(n);
        }
        long high = read(n - 16);
        return high << 16 | read(16);
    }

    /** Moves to the next byte boundary, counted from the start of the packet. */
    void alignToByte() {
        int used = (position - start) & 7;
        if (used != 0) {
            position += 8 - used;
        }
    }

    /** Bits read since {@link #reset}. */
    int bitsRead() {
        return position - start;
    }

    /** Bits left before the end of the packet; negative once a read went past it. */
    int bitsLeft() {
        return end - position;
    }

    /** Whether a read went past the end of the packet. */
    boolean overrun() {
        return position > end;
    }

    /** The absolute bit position, for elements that must be re-read or measured. */
    int position() {
        return position;
    }

    void seek(int bitPosition) {
        position = bitPosition;
    }

    byte[] data() {
        return data;
    }
}

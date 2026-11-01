package ru.gora.musicbattle.audio.codec;

import java.util.Arrays;

/**
 * Vorbis's bit packer: values go in least significant bit first, filling each byte from its low bit up, which is the
 * order every Vorbis decoder reads them back in. One instance is reused for every packet of a stream.
 */
final class VorbisEncoderBits {
    private byte[] bytes = new byte[4096];
    private int length;
    /** Bits not yet stored in {@link #bytes}, low bit first. */
    private long pending;
    private int pendingBits;

    void reset() {
        length = 0;
        pending = 0L;
        pendingBits = 0;
    }

    /** Appends the low {@code count} bits of {@code value}, {@code count} at most 32. */
    void write(long value, int count) {
        if (count == 0) {
            return;
        }
        pending |= (value & ((1L << count) - 1L)) << pendingBits;
        pendingBits += count;
        while (pendingBits >= 8) {
            if (length == bytes.length) {
                bytes = Arrays.copyOf(bytes, bytes.length * 2);
            }
            bytes[length++] = (byte) pending;
            pending >>>= 8;
            pendingBits -= 8;
        }
    }

    void writeBytes(byte[] data) {
        for (byte b : data) {
            write(b & 0xFF, 8);
        }
    }

    /** The packet so far, with a partly filled last byte padded with zero bits, as the specification asks. */
    byte[] toByteArray() {
        byte[] out = Arrays.copyOf(bytes, length + (pendingBits > 0 ? 1 : 0));
        if (pendingBits > 0) {
            out[length] = (byte) pending;
        }
        return out;
    }
}

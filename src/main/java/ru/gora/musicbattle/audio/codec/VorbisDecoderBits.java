package ru.gora.musicbattle.audio.codec;

/**
 * Reads the bits of one Vorbis packet, least significant bit first, and decodes Huffman codewords from them.
 *
 * <p>Running off the end of a packet is not an error in Vorbis: an encoder may stop a packet early and the decoder
 * treats everything it did not send as zero. So a read past the end returns 0, a codeword that does not fit returns
 * -1, and from then on {@link #eop} stays set and every later read fails the same way. A codeword that is not in the
 * book is treated the same way, as stb_vorbis and libvorbis both do, so the rest of that packet section is dropped
 * instead of being decoded from the wrong bits.
 */
final class VorbisDecoderBits {
    static final int FAST_BITS = 10;
    static final int FAST_MASK = (1 << FAST_BITS) - 1;

    private byte[] data;
    private int limit;
    private int pos;
    private long acc;
    private int count;
    boolean eop;

    void reset(byte[] data, int offset, int length) {
        this.data = data;
        this.pos = offset;
        this.limit = offset + length;
        this.acc = 0;
        this.count = 0;
        this.eop = false;
    }

    private void refill() {
        while (count <= 56 && pos < limit) {
            acc |= (data[pos++] & 0xFFL) << count;
            count += 8;
        }
    }

    /** Reads {@code n} bits, 0 to 32, as an unsigned value; past the end of the packet it returns 0 and sets eop. */
    int read(int n) {
        if (n == 0) {
            return 0;
        }
        if (count < n) {
            refill();
            if (count < n) {
                exhaust();
                return 0;
            }
        }
        int value = (int) (acc & ((1L << n) - 1));
        acc >>>= n;
        count -= n;
        return value;
    }

    long readUnsigned32() {
        return read(32) & 0xFFFFFFFFL;
    }

    boolean readFlag() {
        return read(1) != 0;
    }

    /** Decodes one codeword and returns its entry number, or -1 at the end of the packet or on a code not in the book. */
    int decode(VorbisDecoderSetup.Codebook book) {
        if (count < 32) {
            refill();
        }
        int entry = book.fast[(int) acc & FAST_MASK];
        if (entry >= 0) {
            int length = entry & 31;
            if (length > count) {
                return exhaust();
            }
            acc >>>= length;
            count -= length;
            return entry >>> 5;
        }
        return decodeLong(book);
    }

    private int decodeLong(VorbisDecoderSetup.Codebook book) {
        int[] codes = book.sortedCodes;
        if (codes.length == 0) {
            return exhaust();
        }
        // The sorted table holds every codeword left-aligned and most significant bit first, so the bits waiting in
        // the accumulator, reversed, sort right after the codeword they start with.
        int code = Integer.reverse((int) acc);
        int low = 0;
        int n = codes.length;
        while (n > 1) {
            int half = n >>> 1;
            int middle = low + half;
            if (Integer.compareUnsigned(codes[middle], code) <= 0) {
                low = middle;
                n -= half;
            } else {
                n = half;
            }
        }
        int length = book.sortedLengths[low];
        if (length > count) {
            return exhaust();
        }
        if (!book.single && ((codes[low] ^ code) >>> (32 - length)) != 0) {
            return exhaust();
        }
        acc >>>= length;
        count -= length;
        return book.sortedEntries[low];
    }

    private int exhaust() {
        eop = true;
        pos = limit;
        acc = 0;
        count = 0;
        return -1;
    }
}

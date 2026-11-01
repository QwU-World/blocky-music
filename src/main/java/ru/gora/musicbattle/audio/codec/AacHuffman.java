package ru.gora.musicbattle.audio.codec;

/**
 * Table-driven decoding of the AAC Huffman codebooks in {@link AacHuffmanData}.
 *
 * <p>Each book becomes a two-level lookup table: the first {@link #FIRST_BITS} bits index the first level, and the
 * few codes longer than that continue in a second-level table chosen by those bits. A symbol therefore costs at most
 * two table reads, and an invalid bit pattern cannot occur because every book is a complete code.
 */
final class AacHuffman {
    private static final int FIRST_BITS = 10;

    /** Index 0 is the scale factor book, 1 to 11 the spectral books. */
    private static final int[][] TABLES = new int[12][];

    static {
        int[][] books = {AacHuffmanData.SCALEFACTOR, AacHuffmanData.BOOK1, AacHuffmanData.BOOK2,
                AacHuffmanData.BOOK3, AacHuffmanData.BOOK4, AacHuffmanData.BOOK5, AacHuffmanData.BOOK6,
                AacHuffmanData.BOOK7, AacHuffmanData.BOOK8, AacHuffmanData.BOOK9, AacHuffmanData.BOOK10,
                AacHuffmanData.BOOK11};
        for (int b = 0; b < books.length; b++) {
            TABLES[b] = build(books[b]);
        }
    }

    private AacHuffman() {
    }

    /**
     * Builds the lookup table. A first-level entry is either a leaf, {@code symbol << 5 | length}, or a link to a
     * second-level table, {@code 0x80000000 | offset << 5 | extraBits}; second-level entries are leaves whose length
     * counts only the bits past the first level.
     */
    private static int[] build(int[] codes) {
        int firstSize = 1 << FIRST_BITS;
        // How many extra bits each first-level prefix of the long codes needs.
        int[] extra = new int[firstSize];
        for (int code : codes) {
            int length = code & 31;
            if (length > FIRST_BITS) {
                int prefix = (code >>> 5) >>> (length - FIRST_BITS);
                extra[prefix] = Math.max(extra[prefix], length - FIRST_BITS);
            }
        }
        int size = firstSize;
        int[] offsets = new int[firstSize];
        for (int prefix = 0; prefix < firstSize; prefix++) {
            if (extra[prefix] > 0) {
                offsets[prefix] = size;
                size += 1 << extra[prefix];
            }
        }
        int[] table = new int[size];
        for (int prefix = 0; prefix < firstSize; prefix++) {
            if (extra[prefix] > 0) {
                table[prefix] = 0x80000000 | offsets[prefix] << 5 | extra[prefix];
            }
        }
        for (int symbol = 0; symbol < codes.length; symbol++) {
            int length = codes[symbol] & 31;
            int code = codes[symbol] >>> 5;
            if (length <= FIRST_BITS) {
                int shift = FIRST_BITS - length;
                int first = code << shift;
                for (int fill = 0; fill < 1 << shift; fill++) {
                    table[first + fill] = symbol << 5 | length;
                }
            } else {
                int prefix = code >>> (length - FIRST_BITS);
                int bits = extra[prefix];
                int rest = code & ((1 << (length - FIRST_BITS)) - 1);
                int shift = bits - (length - FIRST_BITS);
                int first = offsets[prefix] + (rest << shift);
                for (int fill = 0; fill < 1 << shift; fill++) {
                    table[first + fill] = symbol << 5 | (length - FIRST_BITS);
                }
            }
        }
        return table;
    }

    /** Decodes one symbol index of {@code book} (0 for scale factors, 1 to 11 for spectra). */
    static int decode(AacBits bits, int book) {
        int[] table = TABLES[book];
        int entry = table[bits.peek(FIRST_BITS)];
        if (entry >= 0) {
            bits.skip(entry & 31);
            return entry >>> 5;
        }
        bits.skip(FIRST_BITS);
        int extra = entry & 31;
        int second = table[((entry & 0x7FFFFFFF) >>> 5) + bits.peek(extra)];
        bits.skip(second & 31);
        return second >>> 5;
    }

    /** A scale factor difference, -60 to 60. */
    static int scalefactorDelta(AacBits bits) {
        return decode(bits, 0) - 60;
    }
}

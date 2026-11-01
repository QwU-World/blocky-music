package ru.gora.musicbattle.audio.codec;

import java.io.IOException;

/**
 * Where the analysis of one block sends what it decided. The first pass counts, the second writes, and both run the
 * very same analysis code, so the codebooks built from the count fit exactly the symbols the second pass writes.
 */
interface VorbisEncoderSink {
    /** Plain bits: packet flags, mode numbers, the two raw floor values. */
    void bits(int value, int count);

    /** One codebook entry. */
    void symbol(int book, int entry);

    /**
     * The block that was just sent is complete.
     *
     * @param granule how many samples a decoder has returned once it has decoded this packet
     * @param last whether this is the final packet of the stream
     */
    void endPacket(long granule, boolean last) throws IOException;

    /** Counts symbols per codebook entry, and every plain bit, for the first pass. */
    final class Counter implements VorbisEncoderSink {
        final long[][] counts;
        long plainBits;

        Counter(int[] entriesPerBook) {
            counts = new long[entriesPerBook.length][];
            for (int i = 0; i < entriesPerBook.length; i++) {
                counts[i] = new long[entriesPerBook[i]];
            }
        }

        @Override
        public void bits(int value, int count) {
            plainBits += count;
        }

        @Override
        public void symbol(int book, int entry) {
            counts[book][entry]++;
        }

        @Override
        public void endPacket(long granule, boolean last) {
        }
    }
}

package ru.gora.musicbattle.audio.codec;

import java.util.Arrays;

/**
 * Prefix codes for the encoder's codebooks: optimal lengths under a length limit, and the codewords a Vorbis decoder
 * derives from those lengths.
 *
 * <p>A Vorbis setup header stores only each entry's codeword length; the decoder rebuilds the codewords by giving
 * every entry, in entry order, the lowest codeword of its length that is still free. The encoder has to arrive at the
 * same words, so it runs the same rule instead of a textbook canonical assignment.
 */
final class HuffmanLengths {
    private HuffmanLengths() {
    }

    /**
     * Code lengths that minimise the total cost for these weights with no length above {@code maxLength}, by the
     * package-merge algorithm. Every entry gets a code (weights must be positive), and the lengths always fill the code
     * tree exactly, which decoders that reject an over- or under-full tree rely on.
     */
    static int[] lengths(long[] weights, int maxLength) {
        int n = weights.length;
        if (n < 2) {
            throw new IllegalArgumentException("A codebook needs at least two entries");
        }
        if (maxLength >= 31 || (1L << maxLength) < n) {
            throw new IllegalArgumentException("No prefix code of " + n + " entries fits in " + maxLength + " bits");
        }
        Integer[] boxed = new Integer[n];
        for (int i = 0; i < n; i++) {
            if (weights[i] <= 0) {
                throw new IllegalArgumentException("Every entry needs a positive weight");
            }
            boxed[i] = i;
        }
        Arrays.sort(boxed, (a, b) -> Long.compare(weights[a], weights[b]));

        // Node pool: a node is either a leaf (one symbol) or a package of two nodes from the level below.
        int capacity = 2 * n * maxLength + n;
        long[] nodeWeight = new long[capacity];
        int[] left = new int[capacity];
        int[] right = new int[capacity];
        int nodes = 0;
        int[] leaves = new int[n];
        for (int i = 0; i < n; i++) {
            nodeWeight[nodes] = weights[boxed[i]];
            left[nodes] = -1 - boxed[i];
            right[nodes] = -1;
            leaves[i] = nodes++;
        }
        int[] list = leaves.clone();
        int listSize = n;
        for (int level = 1; level < maxLength; level++) {
            int packages = listSize / 2;
            int[] merged = new int[n + packages];
            int li = 0;
            int pi = 0;
            int out = 0;
            int[] packed = new int[packages];
            for (int p = 0; p < packages; p++) {
                int a = list[2 * p];
                int b = list[2 * p + 1];
                nodeWeight[nodes] = nodeWeight[a] + nodeWeight[b];
                left[nodes] = a;
                right[nodes] = b;
                packed[p] = nodes++;
            }
            // Merge by weight; a leaf goes first on a tie, which keeps lengths short for the common symbols.
            while (li < n || pi < packages) {
                if (pi >= packages || (li < n && nodeWeight[leaves[li]] <= nodeWeight[packed[pi]])) {
                    merged[out++] = leaves[li++];
                } else {
                    merged[out++] = packed[pi++];
                }
            }
            list = merged;
            listSize = out;
        }
        int[] lengths = new int[n];
        int[] stack = new int[capacity];
        for (int i = 0; i < 2 * n - 2; i++) {
            int top = 0;
            stack[top++] = list[i];
            while (top > 0) {
                int node = stack[--top];
                if (left[node] < 0) {
                    lengths[-1 - left[node]]++;
                } else {
                    stack[top++] = left[node];
                    stack[top++] = right[node];
                }
            }
        }
        return lengths;
    }

    /**
     * The codeword of every entry, most significant bit first, assigned the way the Vorbis specification decodes them:
     * in entry order, each entry takes the lowest free codeword of its length.
     *
     * @throws IllegalArgumentException when the lengths do not fit in one code tree
     */
    static int[] codewords(int[] lengths) {
        // Free space is kept as a sorted list of taken intervals on a 32-bit line: a codeword of length l covers an
        // aligned interval of 2^(32-l) positions, and "lowest free codeword" is the first aligned gap that fits.
        int n = lengths.length;
        int[] words = new int[n];
        long[] takenStart = new long[n];
        long[] takenEnd = new long[n];
        int taken = 0;
        for (int i = 0; i < n; i++) {
            int len = lengths[i];
            if (len <= 0 || len > 31) {
                throw new IllegalArgumentException("Codeword length out of range: " + len);
            }
            long size = 1L << (32 - len);
            long position = 0L;
            int insertAt = 0;
            boolean placed = false;
            while (!placed) {
                long aligned = (position + size - 1) / size * size;
                if (aligned + size > (1L << 32)) {
                    throw new IllegalArgumentException("Codeword lengths overfill the code tree");
                }
                if (insertAt >= taken || aligned + size <= takenStart[insertAt]) {
                    position = aligned;
                    placed = true;
                } else {
                    position = Math.max(position, takenEnd[insertAt]);
                    insertAt++;
                }
            }
            System.arraycopy(takenStart, insertAt, takenStart, insertAt + 1, taken - insertAt);
            System.arraycopy(takenEnd, insertAt, takenEnd, insertAt + 1, taken - insertAt);
            takenStart[insertAt] = position;
            takenEnd[insertAt] = position + size;
            taken++;
            words[i] = (int) (position >>> (32 - len));
        }
        return words;
    }

    /** The low {@code length} bits of {@code word} in reverse order, so an LSB-first writer sends the top bit first. */
    static int reverse(int word, int length) {
        return Integer.reverse(word) >>> (32 - length);
    }
}

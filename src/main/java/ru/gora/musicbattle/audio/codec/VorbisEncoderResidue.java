package ru.gora.musicbattle.audio.codec;

/**
 * One residue configuration (one per block size): partitions, their classes, and the cascade of VQ books each class
 * sends its values with.
 *
 * <p>Stereo uses residue type 2, which interleaves the two coupled channels into one vector, so a dimension-2 book
 * codes the magnitude and angle of the same frequency together. Mono uses type 1, which means the same thing for one
 * channel; stb_vorbis only takes its fast type-2 path for two channels and treats a mono type-2 vector as twice its
 * real length.
 *
 * <p>A partition (16 coefficients per channel in long blocks, 8 in short) gets the cheapest class its largest value
 * fits in. Small classes use one book of dimension 4 or 2; large values go through a cascade, a coarse book first
 * and finer books for the remainder, because one book able to hold +/-1024 in one step would be enormous. Classes 1
 * and 3 are the sparse twins of 2 and 4, so a partition with a single stray 1 does not pay for a dense one's code.
 */
final class VorbisEncoderResidue {
    static final int CLASSES = 10;
    static final int CLASSWORD_DIMENSIONS = 2;
    static final int MAX_VALUE = 1024;
    /** Per class, per cascade stage: {dimensions, step, largest multiple of the step}. */
    static final int[][][] STAGES = {
        {},
        {{4, 1, 1}},
        {{4, 1, 1}},
        {{4, 1, 2}},
        {{4, 1, 2}},
        {{2, 1, 4}},
        {{2, 1, 8}},
        {{2, 8, 2}, {2, 1, 4}},
        {{2, 16, 4}, {2, 4, 2}, {2, 1, 2}},
        {{2, 128, 8}, {2, 16, 4}, {2, 4, 2}, {2, 1, 2}},
    };

    final int type;
    final int channels;
    final int binsPerPartition;
    final int partitionSize;
    final int partitions;
    final int end;
    final int classBook;
    final int[][] books;
    private final int[] vector;
    private final int[] remainder;
    private final int[] classes;

    VorbisEncoderResidue(int blockSize, int channels, int activeBins, int firstBook) {
        this.type = channels == 2 ? 2 : 1;
        this.channels = channels;
        int bins = blockSize / 2;
        binsPerPartition = blockSize > 256 ? 16 : 8;
        partitionSize = binsPerPartition * channels;
        partitions = Math.max(1, Math.min(bins / binsPerPartition,
                (activeBins + binsPerPartition - 1) / binsPerPartition));
        end = partitions * partitionSize;
        classBook = firstBook;
        books = new int[CLASSES][];
        int next = firstBook + 1;
        for (int c = 0; c < CLASSES; c++) {
            books[c] = new int[STAGES[c].length];
            for (int s = 0; s < STAGES[c].length; s++) {
                books[c][s] = next++;
            }
        }
        vector = new int[end];
        remainder = new int[end];
        classes = new int[partitions + 1];
    }

    /** Entry counts of this residue's books, the classification book first, in book-number order. */
    static int[] bookEntries() {
        int count = 1;
        for (int[][] stage : STAGES) {
            count += stage.length;
        }
        int[] entries = new int[count];
        int at = 0;
        entries[at++] = pow(CLASSES, CLASSWORD_DIMENSIONS);
        for (int[][] stages : STAGES) {
            for (int[] stage : stages) {
                entries[at++] = pow(2 * stage[2] + 1, stage[0]);
            }
        }
        return entries;
    }

    /** Dimensions, values per dimension, step: the lookup of each book, in the order of {@link #bookEntries()}. */
    static int[][] bookShapes() {
        int count = bookEntries().length;
        int[][] shapes = new int[count][];
        int at = 0;
        shapes[at++] = new int[] {CLASSWORD_DIMENSIONS, 0, 0};
        for (int[][] stages : STAGES) {
            for (int[] stage : stages) {
                shapes[at++] = new int[] {stage[0], 2 * stage[2] + 1, stage[1]};
            }
        }
        return shapes;
    }

    /**
     * Sends one block's residue. {@code values[c][k]} are the quantized (and, for stereo, already coupled) values;
     * the residue covers {@code partitions * binsPerPartition} coefficients per channel, which reaches the lowpass.
     */
    void write(int[][] values, VorbisEncoderSink sink) {
        if (type == 2) {
            int[] a = values[0];
            int[] b = values[1];
            for (int k = 0, i = 0; i < end; k++, i += 2) {
                vector[i] = a[k];
                vector[i + 1] = b[k];
            }
        } else {
            System.arraycopy(values[0], 0, vector, 0, end);
        }
        int sparse = partitionSize / 8;
        for (int p = 0; p < partitions; p++) {
            int max = 0;
            int nonzero = 0;
            int from = p * partitionSize;
            for (int i = from; i < from + partitionSize; i++) {
                int v = vector[i];
                if (v > MAX_VALUE) {
                    v = MAX_VALUE;
                } else if (v < -MAX_VALUE) {
                    v = -MAX_VALUE;
                }
                vector[i] = v;
                int m = Math.abs(v);
                if (m > max) {
                    max = m;
                }
                if (m != 0) {
                    nonzero++;
                }
            }
            classes[p] = classify(max, nonzero <= sparse);
        }
        classes[partitions] = 0;
        System.arraycopy(vector, 0, remainder, 0, end);
        for (int pass = 0; pass < 4; pass++) {
            for (int p = 0; p < partitions; p++) {
                if (pass == 0 && p % CLASSWORD_DIMENSIONS == 0) {
                    sink.symbol(classBook, classes[p] * CLASSES + classes[p + 1]);
                }
                int[][] stages = STAGES[classes[p]];
                if (pass >= stages.length) {
                    continue;
                }
                int dims = stages[pass][0];
                int step = stages[pass][1];
                int reach = stages[pass][2];
                int span = 2 * reach + 1;
                int book = books[classes[p]][pass];
                int from = p * partitionSize;
                for (int i = from; i < from + partitionSize; i += dims) {
                    int entry = 0;
                    int scale = 1;
                    for (int d = 0; d < dims; d++) {
                        int r = remainder[i + d];
                        int m = Math.floorDiv(r + (step >> 1), step);
                        if (m > reach) {
                            m = reach;
                        } else if (m < -reach) {
                            m = -reach;
                        }
                        remainder[i + d] = r - m * step;
                        entry += (m + reach) * scale;
                        scale *= span;
                    }
                    sink.symbol(book, entry);
                }
            }
        }
    }

    private static int classify(int max, boolean sparse) {
        if (max == 0) {
            return 0;
        }
        if (max == 1) {
            return sparse ? 1 : 2;
        }
        if (max == 2) {
            return sparse ? 3 : 4;
        }
        if (max <= 4) {
            return 5;
        }
        if (max <= 8) {
            return 6;
        }
        if (max <= 16) {
            return 7;
        }
        return max <= 64 ? 8 : 9;
    }

    /** The residue's part of the setup header. */
    void writeSetup(VorbisEncoderBits w) {
        w.write(type, 16);
        w.write(0, 24);
        w.write(end, 24);
        w.write(partitionSize - 1, 24);
        w.write(CLASSES - 1, 6);
        w.write(classBook, 8);
        for (int c = 0; c < CLASSES; c++) {
            int cascade = (1 << STAGES[c].length) - 1;
            w.write(cascade & 7, 3);
            int high = cascade >> 3;
            w.write(high != 0 ? 1 : 0, 1);
            if (high != 0) {
                w.write(high, 5);
            }
        }
        for (int c = 0; c < CLASSES; c++) {
            for (int s = 0; s < STAGES[c].length; s++) {
                w.write(books[c][s], 8);
            }
        }
    }

    private static int pow(int base, int exponent) {
        int r = 1;
        for (int i = 0; i < exponent; i++) {
            r *= base;
        }
        return r;
    }
}

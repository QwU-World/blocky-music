package ru.gora.musicbattle.audio.codec;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * One floor-1 configuration (one per block size): where its posts sit, how a block's curve is fitted to the masking
 * threshold, how the posts are coded, and the curve a decoder will draw from them.
 *
 * <p>In Vorbis the floor is the quantization step: the decoder multiplies every integer residue value by the floor at
 * that frequency. So the encoder fits the floor to the step size the psychoacoustic model allows, and quantizes the
 * spectrum against the floor exactly as the decoder will render it, integer line drawing included. Any other curve
 * would put the noise somewhere the model did not plan for.
 *
 * <p>Posts are spaced evenly on the Bark scale up to the lowpass, and sent in a breadth-first order of interval
 * midpoints, so each post is predicted from the two posts that bracket it most tightly. A post whose prediction is
 * at most one step below the fit is left unused, which costs a single short code.
 */
final class VorbisEncoderFloor {
    /** Floor-1 multiplier 2: values 0..127, each step 1.09 dB, the whole range 139 dB. */
    static final int MULTIPLIER = 2;
    static final int RANGE = 128;
    static final int CLASS_DIMENSIONS = 3;
    static final int SUBCLASS_BITS = 2;
    /** Entries of the subclass books: none, small corrections, medium, anything. */
    static final int[] SUBCLASS_ENTRIES = {0, 8, 32, 128};
    static final int MASTER_ENTRIES = 1 << (SUBCLASS_BITS * CLASS_DIMENSIONS);

    /** The decoder's floor-1 amplitude table, {@code 1.0649863^(i - 255)}: index 0 is -139.5 dB, 255 is 0 dB. */
    static final float[] INVERSE_DB = new float[256];
    static final float[] INVERSE_DB_RECIPROCAL = new float[256];
    static final float LN_STEP = (float) Math.log(1.0649863);
    static final float LN_FIRST = (float) Math.log(1.0649863e-07);

    static {
        for (int i = 0; i < 256; i++) {
            INVERSE_DB[i] = (float) Math.pow(1.0649863, i - 255);
            INVERSE_DB_RECIPROCAL[i] = 1f / INVERSE_DB[i];
        }
    }

    final int bins;
    final int rangeBits;
    final int values;
    final int partitions;
    /** Post positions in list order: 0, bins, then the coded posts. */
    final int[] x;
    final int[] sortedOrder;
    final int[] lowNeighbor;
    final int[] highNeighbor;
    final int masterBook;
    /** Book per subclass, -1 for subclass 0 (an unused post). */
    final int[] subclassBooks;
    private final int[] line;

    /** A block's floor for one channel. */
    static final class Curve {
        final int[] desired;
        final int[] finalY;
        final int[] coded;
        final boolean[] used;
        /** Posts the rendering check found must be drawn. */
        final boolean[] force;
        /** Rendered floor in table-index units (0..254) per coefficient. */
        final int[] index;

        Curve(int values, int bins) {
            desired = new int[values];
            finalY = new int[values];
            coded = new int[values];
            used = new boolean[values];
            force = new boolean[values];
            index = new int[bins];
        }
    }

    VorbisEncoderFloor(int blockSize, int sampleRate, double lowpassHz, int codedPosts, int firstBook) {
        if (codedPosts % CLASS_DIMENSIONS != 0) {
            throw new IllegalArgumentException("Post count must fill whole partitions");
        }
        bins = blockSize / 2;
        rangeBits = Integer.numberOfTrailingZeros(bins);
        partitions = codedPosts / CLASS_DIMENSIONS;
        if (partitions > 31) {
            throw new IllegalArgumentException("Floor 1 allows at most 31 partitions");
        }
        values = codedPosts + 2;
        int[] positions = postPositions(bins, sampleRate, lowpassHz, codedPosts);
        x = new int[values];
        x[0] = 0;
        x[1] = bins;
        int[] order = midpointOrder(codedPosts);
        for (int i = 0; i < codedPosts; i++) {
            x[2 + i] = positions[order[i]];
        }
        Integer[] boxed = new Integer[values];
        for (int i = 0; i < values; i++) {
            boxed[i] = i;
        }
        Arrays.sort(boxed, (a, b) -> Integer.compare(x[a], x[b]));
        sortedOrder = new int[values];
        for (int i = 0; i < values; i++) {
            sortedOrder[i] = boxed[i];
        }
        lowNeighbor = new int[values];
        highNeighbor = new int[values];
        for (int j = 2; j < values; j++) {
            int low = 0;
            int high = 1;
            for (int i = 0; i < j; i++) {
                if (x[i] < x[j] && x[i] > x[low]) {
                    low = i;
                }
                if (x[i] > x[j] && x[i] < x[high]) {
                    high = i;
                }
            }
            lowNeighbor[j] = low;
            highNeighbor[j] = high;
        }
        masterBook = firstBook;
        subclassBooks = new int[] {-1, firstBook + 1, firstBook + 2, firstBook + 3};
        line = new int[bins + 1];
    }

    /** The books this floor owns, in book-number order from {@code firstBook}. */
    static int[] bookEntries() {
        return new int[] {MASTER_ENTRIES, SUBCLASS_ENTRIES[1], SUBCLASS_ENTRIES[2], SUBCLASS_ENTRIES[3]};
    }

    Curve newCurve() {
        return new Curve(values, bins);
    }

    /**
     * Fits the posts to {@code target}, the highest floor each coefficient may have in table-index units (0..254,
     * fractional), then decides which posts to send and renders the curve the decoder will draw. Only the first
     * {@code active} coefficients matter; above them the residue is zero whatever the floor.
     */
    void fit(float[] target, int active, Curve curve) {
        int[] y = curve.desired;
        for (int j = 0; j < values; j++) {
            int at = Math.min(x[j], bins - 1);
            float t = target[at];
            if (at > 0) {
                t = Math.min(t, target[at - 1]);
            }
            if (at + 1 < bins) {
                t = Math.min(t, target[at + 1]);
            }
            y[j] = clampY((int) Math.floor(t / MULTIPLIER));
        }
        // The straight lines between posts must not rise above the target by more than one table step (0.55 dB)
        // anywhere, or that stretch carries more noise than the model allows. Lowering only ever removes excess, so
        // a few rounds settle it.
        for (int round = 0; round < 8; round++) {
            boolean changed = false;
            for (int s = 0; s + 1 < values; s++) {
                int a = sortedOrder[s];
                int b = sortedOrder[s + 1];
                int x0 = x[a];
                int x1 = Math.min(x[b], active);
                if (x1 <= x0) {
                    continue;
                }
                renderLine(x0, y[a] * MULTIPLIER, x[b], y[b] * MULTIPLIER, line, x0);
                float worst = 1.0f;
                int worstAt = -1;
                for (int k = x0; k < x1; k++) {
                    float excess = line[k - x0] - target[k];
                    if (excess > worst) {
                        worst = excess;
                        worstAt = k;
                    }
                }
                if (worstAt >= 0) {
                    float t = (worstAt - x0) / (float) (x[b] - x0);
                    int which = t < 0.5f ? a : b;
                    float weight = t < 0.5f ? 1f - t : t;
                    int drop = Math.max(1, (int) Math.ceil((worst - 0.5f) / (MULTIPLIER * weight)));
                    int lowered = clampY(y[which] - drop);
                    if (lowered != y[which]) {
                        y[which] = lowered;
                        changed = true;
                    }
                }
            }
            if (!changed) {
                break;
            }
        }
        // A post left unused is not drawn unless a later post that leans on it is sent, so the decoder's line can
        // jump straight over a dip the fit made. Render exactly, and wherever the curve still overshoots, send the
        // posts around that stretch (or lower them if they are already sent), until it fits.
        boolean[] force = curve.force;
        Arrays.fill(force, false);
        for (int attempt = 0; attempt < 12; attempt++) {
            code(curve);
            render(curve);
            boolean changed = false;
            int[] drawn = curve.index;
            int s = 0;
            for (int k = 0; k < active; k++) {
                while (s + 2 < values && x[sortedOrder[s + 1]] <= k) {
                    s++;
                }
                if (drawn[k] <= target[k] + 1.0f) {
                    continue;
                }
                int a = sortedOrder[s];
                int b = sortedOrder[s + 1];
                boolean fixed = false;
                for (int j : new int[] {a, b}) {
                    if (!curve.used[j] && !force[j]) {
                        force[j] = true;
                        fixed = true;
                    }
                }
                if (!fixed) {
                    int j = (k - x[a]) * 2 < (x[b] - x[a]) ? a : b;
                    if (y[j] > 0) {
                        y[j]--;
                        fixed = true;
                    }
                }
                changed |= fixed;
                // One correction per segment per attempt; the next render shows what is left.
                while (k + 1 < active && k + 1 < x[b]) {
                    k++;
                }
            }
            if (!changed) {
                return;
            }
        }
        code(curve);
        render(curve);
    }

    /** Chooses the coded value of every post the way a decoder will read it back. */
    private void code(Curve curve) {
        int[] y = curve.desired;
        int[] finalY = curve.finalY;
        int[] coded = curve.coded;
        boolean[] used = curve.used;
        boolean[] force = curve.force;
        Arrays.fill(used, false);
        finalY[0] = y[0];
        finalY[1] = y[1];
        used[0] = true;
        used[1] = true;
        for (int j = 2; j < values; j++) {
            int low = lowNeighbor[j];
            int high = highNeighbor[j];
            int predicted = predict(x[j], x[low], x[high], finalY[low], finalY[high]);
            int wanted = y[j];
            int diff = wanted - predicted;
            // Leaving a post unused is cheap, but only safe when the prediction is not above the fit: a higher floor
            // means more noise than the model allowed, a slightly lower one only a few more bits. A post the
            // rendering check needs drawn is always sent; one that equals its prediction is sent a step lower,
            // since a coded value of zero would mean unused.
            if (force[j] && diff == 0) {
                wanted = Math.max(0, predicted - 1);
                diff = wanted - predicted;
            }
            if (diff == 0 || (!force[j] && diff == 1)) {
                coded[j] = 0;
                finalY[j] = predicted;
            } else {
                coded[j] = encodeValue(wanted, predicted);
                finalY[j] = wanted;
                used[j] = true;
                used[low] = true;
                used[high] = true;
            }
        }
    }

    /** The coded value whose decoding, against {@code predicted}, gives {@code wanted}. */
    static int encodeValue(int wanted, int predicted) {
        int highRoom = RANGE - predicted;
        int lowRoom = predicted;
        int room = highRoom < lowRoom ? highRoom * 2 : lowRoom * 2;
        int diff = wanted - predicted;
        if (diff > 0) {
            int v = diff * 2;
            return v < room ? v : diff + lowRoom;
        }
        int v = -diff * 2 - 1;
        return v < room ? v : -diff + highRoom - 1;
    }

    /** Floor-1 prediction from the specification: integer interpolation between two posts. */
    static int predict(int at, int x0, int x1, int y0, int y1) {
        int dy = y1 - y0;
        int adx = x1 - x0;
        int err = Math.abs(dy) * (at - x0);
        int off = err / adx;
        return dy < 0 ? y0 - off : y0 + off;
    }

    /** Draws the curve exactly as the specification's floor-1 synthesis does. */
    void render(Curve curve) {
        int[] out = curve.index;
        int lx = 0;
        int ly = curve.finalY[0] * MULTIPLIER;
        for (int s = 1; s < values; s++) {
            int j = sortedOrder[s];
            if (curve.used[j]) {
                int hy = curve.finalY[j] * MULTIPLIER;
                int hx = x[j];
                if (hx > lx) {
                    renderLine(lx, ly, hx, hy, out, 0);
                }
                lx = hx;
                ly = hy;
            }
        }
        for (int k = lx; k < bins; k++) {
            out[k] = ly;
        }
    }

    /**
     * The specification's render_line: Bresenham steps from (x0, y0) towards (x1, y1), writing {@code x0..x1-1}
     * into {@code out[x - origin]}, clipped to the floor's length.
     */
    private void renderLine(int x0, int y0, int x1, int y1, int[] out, int origin) {
        int dy = y1 - y0;
        int adx = x1 - x0;
        int ady = Math.abs(dy);
        int base = dy / adx;
        int sy = dy < 0 ? base - 1 : base + 1;
        int yv = y0;
        int err = 0;
        ady -= Math.abs(base) * adx;
        int end = Math.min(x1, bins);
        if (x0 < end) {
            out[x0 - origin] = yv;
        }
        for (int xv = x0 + 1; xv < end; xv++) {
            err += ady;
            if (err >= adx) {
                err -= adx;
                yv += sy;
            } else {
                yv += base;
            }
            out[xv - origin] = yv;
        }
    }

    /** Sends a non-empty floor: the flag, the two end posts raw, then each partition through its books. */
    void write(Curve curve, VorbisEncoderSink sink) {
        sink.bits(1, 1);
        int rawBits = 32 - Integer.numberOfLeadingZeros(RANGE - 1);
        sink.bits(curve.finalY[0], rawBits);
        sink.bits(curve.finalY[1], rawBits);
        int[] coded = curve.coded;
        for (int p = 0; p < partitions; p++) {
            int base = 2 + p * CLASS_DIMENSIONS;
            int cval = 0;
            for (int k = 0; k < CLASS_DIMENSIONS; k++) {
                cval |= subclass(coded[base + k]) << (SUBCLASS_BITS * k);
            }
            sink.symbol(masterBook, cval);
            for (int k = 0; k < CLASS_DIMENSIONS; k++) {
                int sub = subclass(coded[base + k]);
                if (sub != 0) {
                    sink.symbol(subclassBooks[sub], coded[base + k]);
                }
            }
        }
    }

    private static int subclass(int value) {
        if (value == 0) {
            return 0;
        }
        if (value < SUBCLASS_ENTRIES[1]) {
            return 1;
        }
        return value < SUBCLASS_ENTRIES[2] ? 2 : 3;
    }

    /** The floor's part of the setup header. */
    void writeSetup(VorbisEncoderBits w) {
        w.write(1, 16);
        w.write(partitions, 5);
        for (int p = 0; p < partitions; p++) {
            w.write(0, 4);
        }
        w.write(CLASS_DIMENSIONS - 1, 3);
        w.write(SUBCLASS_BITS, 2);
        w.write(masterBook, 8);
        for (int s = 0; s < (1 << SUBCLASS_BITS); s++) {
            w.write(subclassBooks[s] + 1, 8);
        }
        w.write(MULTIPLIER - 1, 2);
        w.write(rangeBits, 4);
        for (int j = 2; j < values; j++) {
            w.write(x[j], rangeBits);
        }
    }

    private static int clampY(int y) {
        return Math.max(0, Math.min(RANGE - 1, y));
    }

    /**
     * Post positions spaced evenly in Bark between the second coefficient and the lowpass, at least one coefficient
     * apart, strictly inside {@code (0, bins)}.
     */
    static int[] postPositions(int bins, int sampleRate, double lowpassHz, int count) {
        double binHz = sampleRate / (2.0 * bins);
        double topHz = Math.min(lowpassHz, (bins - 1) * binHz);
        double lowHz = Math.max(1.5 * binHz, 40.0);
        double zLow = PsyModel.bark(lowHz);
        double zHigh = PsyModel.bark(Math.max(topHz, lowHz * 2));
        int[] out = new int[count];
        int previous = 0;
        for (int i = 0; i < count; i++) {
            double z = zLow + (zHigh - zLow) * i / (count - 1);
            int bin = (int) Math.round(barkToHz(z) / binHz);
            bin = Math.max(bin, previous + 1);
            out[i] = bin;
            previous = bin;
        }
        // Low sample rates or very short blocks can push the top posts past the end; slide them back down.
        int limit = bins - 1;
        for (int i = count - 1; i >= 0; i--) {
            if (out[i] > limit) {
                out[i] = limit;
            }
            limit = out[i] - 1;
        }
        if (out[0] < 1) {
            throw new IllegalArgumentException("Too many floor posts for " + bins + " coefficients");
        }
        return out;
    }

    /** Inverse of {@link PsyModel#bark}, by bisection: it is only used while building a layout. */
    static double barkToHz(double z) {
        double lo = 0;
        double hi = 200000;
        for (int i = 0; i < 60; i++) {
            double mid = (lo + hi) / 2;
            if (PsyModel.bark(mid) < z) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        return (lo + hi) / 2;
    }

    /** Indices 0..count-1 ordered breadth-first by interval midpoint. */
    static int[] midpointOrder(int count) {
        List<int[]> queue = new ArrayList<>();
        queue.add(new int[] {0, count});
        int[] order = new int[count];
        int n = 0;
        for (int head = 0; head < queue.size(); head++) {
            int lo = queue.get(head)[0];
            int hi = queue.get(head)[1];
            if (lo >= hi) {
                continue;
            }
            int mid = (lo + hi) / 2;
            order[n++] = mid;
            queue.add(new int[] {lo, mid});
            queue.add(new int[] {mid + 1, hi});
        }
        return order;
    }
}

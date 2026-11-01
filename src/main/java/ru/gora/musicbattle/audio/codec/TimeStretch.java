package ru.gora.musicbattle.audio.codec;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Objects;

/**
 * Plays a song faster or slower without changing its pitch, for the 1.5x and 2x battles: the battle scales every note
 * by the same factor, so the audio has to keep to the same straight line through time.
 *
 * <p>The method is WSOLA (waveform-similarity overlap-add, Verhelst and Roelands 1993). The output is built from
 * 46 ms grains of the input, Hann-windowed and overlapped by half, so every join is a crossfade and never a cut. Grain
 * {@code k} is centred on output frame {@code k * hop}; its input centre is searched near {@code k * hop * speed} for
 * the place whose waveform continues the previous grain best, so the crossfade joins two waves in phase instead of
 * cancelling them. The search is limited to 5 ms of output time either side of the straight line, so the music never
 * drifts: a grain's centre is always within 5 ms of where the chart expects it.
 *
 * <p>Onsets (drum hits, plucks, clicks) get special care, because plain WSOLA lets a hit fall near a grain's edge,
 * where the window fades it out or a neighbouring grain plays it a second time. Above about 1.4x, every onset found is
 * given a pair of grains of its own: two grains that continue each other exactly, placed so the onset lands on its
 * ideal output frame ({@code input / speed}, to the sample) and is reproduced whole, attack and all, by a stretch of
 * input played at its own speed. The grains around the pair cannot reach the onset, so it is never doubled. When two
 * onsets would need the same grains, the stronger one keeps its pair. The grain before a pair, when it is free, is
 * chosen to join both its neighbours well.
 *
 * <p>Where two grains still do not line up (noise, cymbals, a reverb tail), their crossfade would dip by 3 dB in the
 * middle, 43 times a second; the level of every crossfade is kept, below and above 1.5 kHz separately
 * ({@link #keepJoinLevel}). That lift never takes a sample past the louder grain's peak in the crossfade, nor past
 * 0.9 of full scale where the plain crossfade stays below that, because Minecraft cuts every sample off at full scale,
 * the copy is encoded once more before it plays, and a loud master would otherwise crackle in lifted crossfades.
 *
 * <p>Measured on three songs against an independent onset detector: the onsets land at a median of 0 ms from
 * {@code input / speed}, 94 to 100 percent of the strong ones within 5 ms (ffmpeg's atempo, which this replaces: 45
 * to 55 percent), and the flutter at the grain rate stays within a few dB of the source's own.
 *
 * <p>The output has exactly {@code round(inputFrames / speed)} frames; the first output frame is the first input frame.
 * Speeds from 0.5 to 4 work; onset pairs are only used when speeding up. Everything is plain float arithmetic and
 * StrictMath, so decoding the same input twice (the Vorbis encoder does) gives the same output twice.
 */
public final class TimeStretch implements PcmStream {
    public static final double MIN_SPEED = 0.5;
    public static final double MAX_SPEED = 4.0;

    /** Grain hop at 44.1 kHz: 1024 frames, 23 ms; the grain is twice that. Scaled with the sample rate. */
    private static final double HOP_SECONDS = 1024.0 / 44100.0;
    /** How far a grain's centre may move from the straight line, in output time. */
    private static final double TOLERANCE_SECONDS = 0.005;
    /** The coarse search runs on a box-filtered signal at about 11 kHz. */
    private static final int COARSE_RATE = 11025;
    /** A nudge towards the straight line, so equally good candidates resolve to the one nearest the chart. */
    private static final float CENTRE_PREFERENCE = 0.02f;

    /** Onset detector block, about 1.5 ms. */
    private static final double BLOCK_SECONDS = 64.0 / 44100.0;
    /** The background an onset is measured against: the 50 ms before it. */
    private static final double HISTORY_SECONDS = 0.05;
    /** No second onset sooner than this after one. */
    private static final double REFRACTORY_SECONDS = 0.03;
    /** How much stronger than its background a block's high-frequency energy must be to count as an onset. */
    private static final double ONSET_RATIO = 5.0;
    /** Nor may it be quieter than this share of the song's running level: a hiss in a quiet bar is not a hit. */
    private static final double ONSET_LEVEL_SHARE = 0.1;
    /** About 3 s: how slowly the song's running level follows the music. */
    private static final double LEVEL_SECONDS = 3.0;
    /** Mean squared first difference below which nothing counts, about -70 dBFS. */
    private static final double ONSET_FLOOR = 1e-7;
    /**
     * The fewest grains from one onset pair's first grain to the next one's: two lets a pair follow the previous one
     * at once. A free grain between them (three) would join them more smoothly in theory, but on real songs at 2x it
     * kept only 91 to 92 percent of the strong onsets within 5 ms against 94 to 96 with two, and the flutter at the
     * grain rate was the same either way.
     */
    private static final int PAIR_SPACING = 2;

    /** The most a crossfade is lifted: 3 dB more than two unrelated equal grains need. */
    private static final double MAX_JOIN_GAIN = 2.0;
    /**
     * Where the plain crossfade is below this, the lift stops here: 0.9 of full scale, about 1 dB under it. The copy
     * is encoded once more before the game plays it, and the encoder's own error pushes a sample that sits right at
     * full scale over it, which the game then cuts off. With the lift allowed up to full scale, 2x copies of loud
     * masters clipped 1.3 to 1.8 times as often as with no lift at all once decoded; with this they clip within about
     * 10 percent of that, for at most 0.3 dB more flutter at the grain rate.
     */
    private static final double LIFT_CEILING = 0.9;
    /**
     * Around a sample the lift would take too far, the lift is held down this long on either side and eased back over
     * as long again, about 1.5 ms: short enough that the rest of the crossfade keeps its level (the flutter at the grain
     * rate measured the same as with the lift uncapped), long enough to be a change of level rather than a clipped
     * wave. Half or twice this measured the same.
     */
    private static final double CAP_EASE_SECONDS = 64.0 / 44100.0;
    /**
     * A sample beyond this is damage, not music: it is 36 dB over full scale, which no song holds, and far below where
     * the sums of squares here could overflow. The Vorbis encoder uses the same bound.
     */
    private static final float MAX_INPUT = 64f;
    /**
     * Where the crossfade's level is judged in two parts. Below it the waveform search lines the grains up, because
     * the bass carries most of the energy it compares; above it two grains are often unrelated even when the bass
     * matches, and need their own correction.
     */
    private static final double SPLIT_HZ = 1500.0;

    private static final int READ_CHUNK = 4096;

    private final PcmStream source;
    private final int channels;
    private final double speed;
    private final int hop;
    private final int window;
    /** Periodic Hann of length {@code window}: neighbouring grains always add up to exactly one. */
    private final float[] hann;
    /** How much each frame of a crossfade matters for the match: where both grains are heard about equally. */
    private final float[] crossWeight;
    private final int tolerance;
    private final int coarseStep;
    private final boolean anchoring;
    private final int block;
    private final int historyBlocks;
    private final int refractoryBlocks;
    private final double levelSmoothing;
    private final int capEase;
    /** Per crossfade frame: the sample the level correction makes, and how much of that change is allowed in. */
    private final double[] lifted;
    private final double[] allowed;
    private final double[] eased;

    // Input, with absolute frame numbers: frame inStart is index 0. Frames before 0 and after the end are silence that
    // is really stored, so the inner loops never test bounds.
    private float[][] in;
    /** The part of each channel above about 1.5 kHz; the rest is {@code in} minus this. */
    private float[][] high;
    /** Per channel: the low-pass's state (two samples in, two out), for the split into {@code high}. */
    private final double[][] splitState;
    private final double[] split;
    /** The channels averaged, which the search and the onset detector look at. */
    private float[] mono;
    /** {@code mono} averaged over {@code coarseStep} frames, for the coarse search. */
    private float[] smooth;
    private long inStart;
    private int inLength;
    private boolean sourceEnded;
    private long sourceFrames;
    private final float[][] readScratch;

    // Onset detector.
    private long nextBlock;
    private final double[] history;
    private int historyNext;
    private int historyCount;
    private double historySum;
    private double level;
    private long lastOnsetBlock = Long.MIN_VALUE / 2;
    private final ArrayDeque<Anchor> anchors = new ArrayDeque<>();

    // Synthesis.
    private long segment;
    private long previousCentre;
    /** Output frames {@code segment*hop - hop} to {@code segment*hop + hop}, before grain {@code segment} is added. */
    private final float[][] accumulator;
    private final float[][] ready;
    private int readyStart;
    private int readyEnd;
    private long outputPosition;
    private long outputTotal = -1;

    /**
     * An onset's pair of grains: grain {@code first} is centred on input {@code centre}, the next on
     * {@code centre + hop}, and together they play input frame {@code onset} at its ideal output frame.
     */
    private record Anchor(long first, long centre, long onset, double strength) {
    }

    private TimeStretch(PcmStream source, double speed) {
        this.source = source;
        this.channels = source.channels();
        this.speed = speed;
        int rate = source.sampleRate();
        this.hop = Math.max(32, (int) Math.round(rate * HOP_SECONDS));
        this.window = 2 * hop;
        this.hann = new float[window];
        for (int j = 0; j < window; j++) {
            hann[j] = (float) (0.5 - 0.5 * StrictMath.cos(2.0 * Math.PI * j / window));
        }
        this.crossWeight = new float[hop];
        for (int j = 0; j < hop; j++) {
            crossWeight[j] = hann[j] * hann[j + hop];
        }
        this.tolerance = (int) Math.max(1L, Math.min(hop, Math.round(rate * TOLERANCE_SECONDS * speed)));
        this.coarseStep = Math.max(1, (int) Math.round(rate / (double) COARSE_RATE));
        // A pair only stays clear of its neighbours when the input skipped per grain exceeds the search range.
        this.anchoring = (speed - 1.0) * hop >= tolerance + hop / 16.0;
        this.block = Math.max(16, (int) Math.round(rate * BLOCK_SECONDS));
        this.historyBlocks = Math.max(4, (int) Math.round(HISTORY_SECONDS * rate / block));
        this.refractoryBlocks = Math.max(1, (int) Math.round(REFRACTORY_SECONDS * rate / block));
        this.levelSmoothing = Math.min(1.0, block / (LEVEL_SECONDS * rate));
        this.history = new double[historyBlocks];
        this.capEase = Math.max(1, (int) Math.round(rate * CAP_EASE_SECONDS));
        this.lifted = new double[hop];
        this.allowed = new double[hop];
        this.eased = new double[hop];

        int lead = hop + tolerance + 2 * block + coarseStep + 8;
        int capacity = Math.max(1 << 15, 8 * window + lead);
        this.in = new float[channels][capacity];
        this.high = new float[channels][capacity];
        this.splitState = new double[channels][4];
        this.split = lowPass(rate, SPLIT_HZ);
        this.mono = new float[capacity];
        this.smooth = new float[capacity];
        this.inStart = -lead;
        this.inLength = lead;
        this.readScratch = new float[channels][READ_CHUNK];
        this.accumulator = new float[channels][window];
        this.ready = new float[channels][hop];
    }

    /**
     * The source played at {@code speed} times its tempo: 2 halves its length. A speed of exactly 1 returns the source
     * itself.
     */
    public static PcmStream wrap(PcmStream source, double speed) {
        Objects.requireNonNull(source, "source");
        if (!(speed >= MIN_SPEED && speed <= MAX_SPEED)) {
            throw new IllegalArgumentException("Speed must be between " + MIN_SPEED + " and " + MAX_SPEED
                    + ", not " + speed);
        }
        return speed == 1.0 ? source : new TimeStretch(source, speed);
    }

    /** The length the output of {@code inputFrames} frames will have. */
    public static long outputFrames(long inputFrames, double speed) {
        return (long) Math.floor(inputFrames / speed + 0.5);
    }

    @Override
    public int sampleRate() {
        return source.sampleRate();
    }

    @Override
    public int channels() {
        return channels;
    }

    @Override
    public long frameCountHint() {
        long hint = source.frameCountHint();
        return hint < 0 ? -1L : outputFrames(hint, speed);
    }

    @Override
    public int read(float[][] out, int offset, int frames) throws IOException {
        if (frames <= 0) {
            return 0;
        }
        int done = 0;
        while (done < frames) {
            if (outputTotal >= 0 && outputPosition >= outputTotal) {
                break;
            }
            if (readyStart == readyEnd) {
                if (!produce()) {
                    break;
                }
                continue;
            }
            int count = Math.min(frames - done, readyEnd - readyStart);
            if (outputTotal >= 0) {
                count = (int) Math.min(count, outputTotal - outputPosition);
            }
            for (int c = 0; c < channels; c++) {
                System.arraycopy(ready[c], readyStart, out[c], offset + done, count);
            }
            readyStart += count;
            outputPosition += count;
            done += count;
        }
        return done == 0 ? -1 : done;
    }

    @Override
    public void close() throws IOException {
        source.close();
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Synthesis

    /**
     * Places the next grain and hands out the output it finishes.
     *
     * @return false once everything up to the output's length has been handed out
     */
    private boolean produce() throws IOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("Time stretching was interrupted.");
        }
        long k = segment;
        long centreOut = k * hop;
        long ideal = Math.round(centreOut * speed);
        // Everything a decision about grain k can depend on: its own search range, the grains of an onset pair at
        // k or k + 1, and every onset that could still claim grain k + 1 or displace the pair there.
        long horizon = Math.max((long) Math.ceil((k + PAIR_SPACING + 1) * hop * speed) + 2L * block,
                ideal + tolerance + hop + coarseStep + 1);
        ensureInput(horizon);
        analyseOnsets(horizon - block);
        if (sourceEnded) {
            outputTotal = outputFrames(sourceFrames, speed);
            if (centreOut - hop >= outputTotal) {
                return false;
            }
        }
        long centre = decide(k, ideal);
        int base = (int) (centre - hop - inStart);
        for (int c = 0; c < channels; c++) {
            float[] acc = accumulator[c];
            float[] input = in[c];
            for (int j = 0; j < window; j++) {
                acc[j] += hann[j] * input[base + j];
            }
            if (k > 0) {
                keepJoinLevel(acc, input, high[c], (int) (previousCentre - inStart), base);
            }
        }
        // Output centreOut - hop up to centreOut is final now: no later grain reaches back that far.
        long finalStart = centreOut - hop;
        int skip = (int) Math.max(0L, -finalStart);
        for (int c = 0; c < channels; c++) {
            float[] acc = accumulator[c];
            System.arraycopy(acc, 0, ready[c], 0, hop);
            System.arraycopy(acc, hop, acc, 0, hop);
            Arrays.fill(acc, hop, window, 0f);
        }
        readyStart = Math.min(skip, hop);
        readyEnd = hop;
        previousCentre = centre;
        segment = k + 1;
        compact(Math.min(centre, Math.round((k + 1) * hop * speed) - tolerance - hop) - 2L * block - coarseStep);
        return true;
    }

    /**
     * Keeps the level of a crossfade between two grains that do not line up. Two waves in phase add up to the same
     * level through a Hann crossfade, but two unrelated ones (noise, cymbals, a reverb tail) dip by 3 dB in its
     * middle, and a dip every grain is heard as a 43 Hz flutter. The crossfade is scaled so its power follows a
     * straight line between the two grains' powers, by how much they are alike: grains in phase are not touched, and
     * a fade into silence stays a fade. Below and above {@link #SPLIT_HZ} this is judged and applied separately,
     * because the bass of two grains can match while their highs do not.
     *
     * <p>The lift is capped where it would take a sample past the louder grain's peak in this crossfade, or past
     * {@link #LIFT_CEILING} where the plain crossfade is below that. A plain crossfade never leaves the range of its two
     * grains, but the lift can add 6 dB, and on a loud master that went over full scale, which Minecraft cuts off flat:
     * before this cap, a 2x copy of one song had clipping distortion louder than -40 dB in 157 of its 1230 50 ms
     * windows, where the song itself had none. Near such a sample the lift is eased down for about 1.5 ms
     * ({@link #CAP_EASE_SECONDS}), so the rest of the crossfade keeps its level and the cap is never a corner in the
     * wave. Out of the stretcher, the copies then clip exactly as much as with no lift at all. Encoded and decoded as
     * the game plays them, the 2x copies of the four loudest test masters clip within about 10 percent of a build
     * without the lift (rabbit_hole 2524 samples against 2340, where a ceiling at full scale left 4294), and the flutter
     * at the grain rate is at most 0.3 dB above the lift's with no ceiling.
     *
     * @param acc    the output from the previous grain's centre on, with both grains added
     * @param input  the channel's input; {@code upper} is its part above the split
     * @param tail   where the previous grain's second half starts in the input
     * @param head   where the new grain's first half starts in the input
     */
    private void keepJoinLevel(float[] acc, float[] input, float[] upper, int tail, int head) {
        double crossLow = 0.0;
        double tailLow = 0.0;
        double headLow = 0.0;
        double crossHigh = 0.0;
        double tailHigh = 0.0;
        double headHigh = 0.0;
        for (int j = 0; j < hop; j++) {
            float w = crossWeight[j];
            float ah = upper[tail + j];
            float bh = upper[head + j];
            float al = input[tail + j] - ah;
            float bl = input[head + j] - bh;
            crossLow += w * al * bl;
            tailLow += w * al * al;
            headLow += w * bl * bl;
            crossHigh += w * ah * bh;
            tailHigh += w * ah * ah;
            headHigh += w * bh * bh;
        }
        double alikeLow = alike(crossLow, tailLow, headLow);
        double alikeHigh = alike(crossHigh, tailHigh, headHigh);
        if (alikeLow >= 0.999 && alikeHigh >= 0.999) {
            return;
        }
        double peak = 0.0;
        for (int j = 0; j < hop; j++) {
            peak = Math.max(peak, Math.max(Math.abs(input[tail + j]), Math.abs(input[head + j])));
        }
        boolean capped = false;
        for (int j = 0; j < hop; j++) {
            double fadeIn = hann[j];
            double fadeOut = hann[j + hop];
            double gainLow = joinGain(fadeIn, fadeOut, tailLow, headLow, alikeLow);
            double gainHigh = joinGain(fadeIn, fadeOut, tailHigh, headHigh, alikeHigh);
            double plain = acc[j];
            allowed[j] = 1.0;
            if (gainLow == 1.0 && gainHigh == 1.0) {
                lifted[j] = plain;
                continue;
            }
            double upperPart = fadeOut * upper[tail + j] + fadeIn * upper[head + j];
            lifted[j] = gainLow * plain + (gainHigh - gainLow) * upperPart;
            // The plain crossfade is always within the grains' range; a sample already over the ceiling there is
            // never pushed further.
            allowed[j] = allowedShare(plain, lifted[j], Math.min(peak, Math.max(LIFT_CEILING, Math.abs(plain))));
            capped |= allowed[j] < 1.0;
        }
        if (!capped) {
            for (int j = 0; j < hop; j++) {
                acc[j] = (float) lifted[j];
            }
            return;
        }
        easeAllowed();
        for (int j = 0; j < hop; j++) {
            double plain = acc[j];
            acc[j] = (float) (plain + allowed[j] * (lifted[j] - plain));
        }
    }

    /**
     * How much of the lift from {@code plain} to {@code lifted} keeps the sample within {@code limit}, from 0 to 1.
     * {@code plain} itself is within it, so the allowed part is everything up to where the lift crosses the limit.
     */
    private static double allowedShare(double plain, double lifted, double limit) {
        double lift = lifted - plain;
        if (lifted > limit && lift > 0.0) {
            return Math.max(0.0, (limit - plain) / lift);
        }
        if (lifted < -limit && lift < 0.0) {
            return Math.max(0.0, (-limit - plain) / lift);
        }
        return 1.0;
    }

    /**
     * Turns the allowed share of the lift into a smooth one, as a look-ahead limiter does: every capped frame pulls the
     * frames within {@link #capEase} of it down to its own share, and a moving average over as many frames rounds the
     * edges. Averaging what was pulled down can never exceed a frame's own share, since that frame lies within the
     * reach of every one averaged; the explicit minimum only guards the ends of the crossfade, where the window
     * reaches past it.
     */
    private void easeAllowed() {
        Arrays.fill(eased, 0, hop, 1.0);
        for (int k = 0; k < hop; k++) {
            double share = allowed[k];
            if (share < 1.0) {
                for (int j = Math.max(0, k - capEase), last = Math.min(hop - 1, k + capEase); j <= last; j++) {
                    eased[j] = Math.min(eased[j], share);
                }
            }
        }
        int width = 2 * capEase + 1;
        double sum = 0.0;
        for (int k = -capEase; k <= capEase; k++) {
            sum += k < 0 || k >= hop ? 1.0 : eased[k];
        }
        for (int j = 0; j < hop; j++) {
            allowed[j] = Math.min(sum / width, allowed[j]);
            int entering = j + capEase + 1;
            int leaving = j - capEase;
            sum += (entering < hop ? eased[entering] : 1.0) - (leaving >= 0 ? eased[leaving] : 1.0);
        }
    }

    /** How alike two pieces are, from 0 (unrelated or opposite) to 1; 1 when either is silent, so it is left alone. */
    private static double alike(double cross, double tailPower, double headPower) {
        if (tailPower <= 1e-20 || headPower <= 1e-20) {
            return 1.0;
        }
        return Math.max(0.0, cross / Math.sqrt(tailPower * headPower));
    }

    /** The factor that brings one frame of a crossfade to the power a straight line between the two grains has. */
    private static double joinGain(double fadeIn, double fadeOut, double tailPower, double headPower, double alike) {
        if (alike >= 0.999) {
            return 1.0;
        }
        double target = fadeOut * tailPower + fadeIn * headPower;
        double actual = fadeOut * fadeOut * tailPower + fadeIn * fadeIn * headPower
                + 2.0 * alike * fadeOut * fadeIn * Math.sqrt(tailPower * headPower);
        return actual > 1e-30 ? Math.min(MAX_JOIN_GAIN, Math.sqrt(target / actual)) : 1.0;
    }

    /** The input centre of grain {@code k}. */
    private long decide(long k, long ideal) {
        if (k == 0) {
            return 0;
        }
        while (!anchors.isEmpty() && anchors.peekFirst().first() + 1 < k) {
            anchors.removeFirst();
        }
        Anchor first = anchors.peekFirst();
        if (first != null && first.first() == k) {
            return first.centre();
        }
        if (first != null && first.first() + 1 == k) {
            return first.centre() + hop;
        }
        Anchor next = first != null && first.first() == k + 1 ? first : null;
        return search(ideal, next);
    }

    /**
     * The best input centre within the tolerance of {@code ideal}: the one whose start continues the previous grain,
     * and, before an onset pair, whose end also leads into the pair. A coarse pass on the smoothed signal finds the
     * neighbourhood, a fine pass at full rate the frame.
     */
    private long search(long ideal, Anchor next) {
        long earliest = ideal - tolerance;
        long latest = ideal + tolerance;
        // The previous grain's second half, which the new grain's first half fades in over.
        int previous = (int) (previousCentre - inStart);
        int anchorHead = next == null ? 0 : (int) (next.centre() - hop - inStart);
        int step = coarseStep;

        double previousEnergy = energy(smooth, previous, step);
        double anchorEnergy = next == null ? 0.0 : energy(smooth, anchorHead, step);
        long best = ideal;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (long candidate = earliest; candidate <= latest; candidate += step) {
            int head = (int) (candidate - hop - inStart);
            double score = similarity(smooth, previous, head, previousEnergy, step);
            if (next != null) {
                score += similarity(smooth, (int) (candidate - inStart), anchorHead, anchorEnergy, step);
            }
            score -= preference(candidate - ideal);
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        if (step == 1) {
            return best;
        }
        previousEnergy = energy(mono, previous, 1);
        anchorEnergy = next == null ? 0.0 : energy(mono, anchorHead, 1);
        long coarse = best;
        bestScore = Double.NEGATIVE_INFINITY;
        for (long candidate = Math.max(earliest, coarse - step + 1); candidate <= Math.min(latest, coarse + step - 1);
                candidate++) {
            int head = (int) (candidate - hop - inStart);
            double score = similarity(mono, previous, head, previousEnergy, 1);
            if (next != null) {
                score += similarity(mono, (int) (candidate - inStart), anchorHead, anchorEnergy, 1);
            }
            score -= preference(candidate - ideal);
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        return best;
    }

    private double preference(long offset) {
        double share = offset / (double) tolerance;
        return CENTRE_PREFERENCE * share * share;
    }

    /** The crossfade-weighted energy of {@code hop} frames from {@code start}, every {@code step}th frame. */
    private double energy(float[] signal, int start, int step) {
        double sum = 0.0;
        for (int j = 0; j < hop; j += step) {
            float v = signal[start + j];
            sum += crossWeight[j] * v * v;
        }
        return sum;
    }

    /**
     * Normalised, crossfade-weighted correlation of {@code hop} frames at {@code fixedStart} (whose energy is given)
     * with {@code hop} frames at {@code movingStart}: 1 for the same wave, 0 for silence or no likeness.
     */
    private double similarity(float[] signal, int fixedStart, int movingStart, double fixedEnergy, int step) {
        double cross = 0.0;
        double movingEnergy = 0.0;
        for (int j = 0; j < hop; j += step) {
            float w = crossWeight[j];
            float a = signal[fixedStart + j];
            float b = signal[movingStart + j];
            cross += w * a * b;
            movingEnergy += w * b * b;
        }
        double norm = fixedEnergy * movingEnergy;
        return norm > 1e-30 ? cross / Math.sqrt(norm) : 0.0;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Onsets

    /** Runs the onset detector over every whole block that ends before {@code limit}. */
    private void analyseOnsets(long limit) {
        long available = inStart + inLength;
        long end = Math.min(limit, available);
        while ((nextBlock + 1) * block <= end) {
            long start = nextBlock * block;
            int base = (int) (start - inStart);
            double energy = 0.0;
            for (int i = 0; i < block; i++) {
                float d = mono[base + i] - mono[base + i - 1];
                energy += d * d;
            }
            double background = historyCount == 0 ? 0.0 : historySum / historyCount;
            if (anchoring && start < sourceEndOrMax()
                    && nextBlock - lastOnsetBlock >= refractoryBlocks
                    && historyCount == historyBlocks
                    && energy > ONSET_RATIO * background
                    && energy > ONSET_FLOOR * block
                    && energy > ONSET_LEVEL_SHARE * level) {
                plan(onsetFrame(base, energy) + inStart, energy - background);
                lastOnsetBlock = nextBlock;
            }
            if (historyCount == historyBlocks) {
                historySum -= history[historyNext];
            } else {
                historyCount++;
            }
            history[historyNext] = energy;
            historySum += energy;
            historyNext = (historyNext + 1) % historyBlocks;
            level += (energy - level) * levelSmoothing;
            nextBlock++;
        }
    }

    private long sourceEndOrMax() {
        return sourceEnded ? sourceFrames : Long.MAX_VALUE;
    }

    /** Where in the block the hit starts: the first frame by which a tenth of the block's energy has arrived. */
    private int onsetFrame(int base, double energy) {
        double sum = 0.0;
        for (int i = 0; i < block; i++) {
            float d = mono[base + i] - mono[base + i - 1];
            sum += d * d;
            if (sum >= 0.1 * energy) {
                return base + i;
            }
        }
        return base;
    }

    /** Gives an onset at input frame {@code onset} its pair of grains, unless a stronger neighbour already has them. */
    private void plan(long onset, double strength) {
        long target = Math.round(onset / speed);
        long first = Math.floorDiv(target, hop);
        // Grain first - 1 must still be undecided: it is searched to lead into the pair. Grain 0 is the exception: it
        // always starts the output at input 0, and it ends before any onset a pair at grain 1 can hold.
        long undecided = first == 1 ? first : first - 1;
        if (first < 1 || undecided < segment) {
            return;
        }
        long centre = onset - target + first * hop;
        Anchor last = anchors.peekLast();
        // Two pairs clash when they would share a grain, or when either pair's grains would reach the other's onset
        // and play it a second time, faintly, a few milliseconds off: possible for onsets a grain or so apart at 1.5x.
        if (last != null && (first < last.first() + PAIR_SPACING || last.onset() >= centre - hop
                || onset < last.centre() + 2L * hop)) {
            if (last.first() - 1 < segment || strength <= last.strength()) {
                return;
            }
            anchors.removeLast();
        }
        anchors.addLast(new Anchor(first, centre, onset, strength));
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Input

    /** Reads until input frames up to (not including) {@code end} are in memory, with silence after the source ends. */
    private void ensureInput(long end) throws IOException {
        while (inStart + inLength < end) {
            if (sourceEnded) {
                int count = (int) Math.min(READ_CHUNK, end - (inStart + inLength));
                reserve(count);
                append(null, count);
                continue;
            }
            int read = source.read(readScratch, 0, READ_CHUNK);
            if (read < 0) {
                sourceEnded = true;
                continue;
            }
            if (read == 0) {
                continue;
            }
            reserve(read);
            append(readScratch, read);
            sourceFrames += read;
        }
    }

    private void reserve(int count) {
        if (inLength + count <= mono.length) {
            return;
        }
        int capacity = Math.max(mono.length * 2, inLength + count);
        for (int c = 0; c < channels; c++) {
            in[c] = Arrays.copyOf(in[c], capacity);
            high[c] = Arrays.copyOf(high[c], capacity);
        }
        mono = Arrays.copyOf(mono, capacity);
        smooth = Arrays.copyOf(smooth, capacity);
    }

    /**
     * Appends {@code count} frames from {@code frames}, or silence when it is null, with every view of them. A sample
     * that is not a number, or beyond {@link #MAX_INPUT}, is damage and is taken as silence: the split's filter and the
     * onset detector carry their state from sample to sample, so one NaN would turn every later crossfade and onset
     * decision of the song into NaN, and one huge spike would raise the running level enough to hide the onsets of the
     * next ten seconds.
     */
    private void append(float[][] frames, int count) {
        float share = 1f / channels;
        for (int i = 0; i < count; i++) {
            int index = inLength + i;
            float sum = 0f;
            for (int c = 0; c < channels; c++) {
                float v = frames == null ? 0f : frames[c][i];
                if (!(Math.abs(v) <= MAX_INPUT)) {
                    v = 0f;
                }
                in[c][index] = v;
                high[c][index] = (float) (v - lowPassStep(splitState[c], v));
                sum += v;
            }
            mono[index] = sum * share;
            // Summed afresh rather than as a running sum, which would drift over a long song.
            float box = 0f;
            for (int t = Math.max(0, index - coarseStep + 1); t <= index; t++) {
                box += mono[t];
            }
            smooth[index] = box / coarseStep;
        }
        inLength += count;
    }

    /** Forgets input before {@code keepFrom} once there is enough of it to be worth moving the rest. */
    private void compact(long keepFrom) {
        // The smoothed view of the next frames still needs the last few before them.
        int drop = (int) Math.min(inLength - (long) coarseStep, keepFrom - inStart);
        if (drop < mono.length / 2) {
            return;
        }
        int keep = inLength - drop;
        for (int c = 0; c < channels; c++) {
            System.arraycopy(in[c], drop, in[c], 0, keep);
            System.arraycopy(high[c], drop, high[c], 0, keep);
        }
        System.arraycopy(mono, drop, mono, 0, keep);
        System.arraycopy(smooth, drop, smooth, 0, keep);
        inStart += drop;
        inLength = keep;
    }

    /** A second-order Butterworth low-pass (the bilinear transform's coefficients b0, b1, b2, a1, a2). */
    private static double[] lowPass(int rate, double hz) {
        double k = StrictMath.tan(Math.PI * Math.min(hz, rate * 0.45) / rate);
        double q = StrictMath.sqrt(0.5);
        double norm = 1.0 / (1.0 + k / q + k * k);
        double b0 = k * k * norm;
        return new double[] {b0, 2.0 * b0, b0, 2.0 * (k * k - 1.0) * norm, (1.0 - k / q + k * k) * norm};
    }

    /** One sample through the split's low-pass; {@code state} holds the last two inputs and outputs. */
    private double lowPassStep(double[] state, double x) {
        double y = split[0] * x + split[1] * state[0] + split[2] * state[1] - split[3] * state[2] - split[4] * state[3];
        state[1] = state[0];
        state[0] = x;
        state[3] = state[2];
        state[2] = y;
        return y;
    }
}

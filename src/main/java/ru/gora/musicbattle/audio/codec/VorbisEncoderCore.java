package ru.gora.musicbattle.audio.codec;

import java.io.IOException;
import java.io.InterruptedIOException;

/**
 * One pass over the song: reads it a little at a time, chooses short or long blocks, and runs every block through
 * transform, masking model, floor, quantizer, stereo coupling and residue, sending the result to a sink. Both passes
 * of the encoder run exactly this code.
 *
 * <p><b>Timing.</b> The first block is a short one centred on sample 0, with 128 zeros before the song. That is what
 * libvorbis does, and the only start every decoder agrees on: JOrbis (Minecraft's decoder) returns nothing for a
 * first short block but, by its code, 448 samples of silence before a first long one, and stb_vorbis drops samples
 * after a first long block followed by a short one. Each block's granule position is its centre, so decoded sample 0 is source sample
 * 0. The last packet is the first block whose centre reaches the end of the song, and its granule position is the
 * song's exact length, which tells decoders where to cut the zeros the last block carries.
 *
 * <p><b>Block switching.</b> A long block spreads its quantization noise evenly over all of its 2048 samples, so a
 * drum hit inside one would be heard smeared: noise before the hit (pre-echo) and a hiss for 20 ms after it, both
 * at nearly the hit's own level. A detector marks attacks (a jump in high-passed energy against the few milliseconds
 * before, located to {@value #SEGMENT} samples), and a long block is only allowed where no attack touches it; short
 * blocks of 256 samples carry the attack, so its noise stays within about 3 ms of it, inside the ear's own
 * temporal masking.
 */
final class VorbisEncoderCore {
    private static final int SEGMENT = 64;
    /** An attack is a segment with this many times the high-passed energy of the 256 samples before it. */
    private static final float ATTACK_RATIO = 8f;
    /** Below this energy per sample and channel (-60 dBFS) nothing counts as an attack. */
    private static final float ATTACK_FLOOR = 1e-6f;
    private static final int HISTORY_SEGMENTS = 4;
    private static final int BUFFER = 16384;
    /** 36 dB over full scale: anything beyond is damage, not music. */
    private static final float SAMPLE_LIMIT = 64f;
    private static final int INTERRUPT_CHECK_BLOCKS = 8;
    /** A floor high enough that the largest residue stays below this, so no value is clipped by the books. */
    private static final float PEAK_RESIDUE = 512f;
    /** The step search moves in 1 dB steps of amplitude. */
    private static final float STEP_DOWN = (float) Math.pow(10, -1.0 / 20);
    /**
     * Values round to the nearer integer only past this fraction: 0.4 rather than 0.5 zeroes a few more small
     * values, and the step search makes up for the extra error with a finer step where it matters. On the test songs
     * that was 2 to 3 percent fewer bits for slightly lower noise against the mask.
     */
    static final float ROUND = 0.4f;
    private static final float STEP_UP = 1f / STEP_DOWN;

    private final VorbisEncoderLayout layout;
    private final int channels;
    private final MdctForward shortMdct = new MdctForward(VorbisEncoderLayout.SHORT_BLOCK);
    private final MdctForward longMdct = new MdctForward(VorbisEncoderLayout.LONG_BLOCK);
    private final PsyModel shortPsy;
    private final PsyModel longPsy;
    private final float[] shortWindow;
    private final float[][][] longWindows;
    private final int shortLowpass;
    private final int longLowpass;

    private final float[] windowed = new float[VorbisEncoderLayout.LONG_BLOCK];
    private final float[][] spectrum;
    private final float[] noise = new float[VorbisEncoderLayout.LONG_BLOCK / 2];
    private final float[] target = new float[VorbisEncoderLayout.LONG_BLOCK / 2];
    private final int[][] quantized;
    private final VorbisEncoderFloor.Curve[] shortCurves;
    private final VorbisEncoderFloor.Curve[] longCurves;
    private final boolean[] floorUsed;
    private final float[][] previousBands;
    private final boolean[] previousValid;

    // Input.
    private PcmStream input;
    private final float[][] buffer;
    private long bufferStart;
    private int buffered;
    private boolean ended;
    private long length;

    // Attack detector.
    private long scannedSegments;
    private final float[] lastSample;
    private final float[] history = new float[HISTORY_SEGMENTS];
    private long[] attacks = new long[64];
    private int attackHead;
    private int attackCount;

    VorbisEncoderCore(VorbisEncoderLayout layout) {
        this.layout = layout;
        this.channels = layout.channels;
        shortPsy = new PsyModel(VorbisEncoderLayout.SHORT_BLOCK, layout.sampleRate, layout.athReferenceDb,
                layout.athCapDb);
        longPsy = new PsyModel(VorbisEncoderLayout.LONG_BLOCK, layout.sampleRate, layout.athReferenceDb,
                layout.athCapDb);
        shortWindow = window(VorbisEncoderLayout.SHORT_BLOCK, false, false);
        longWindows = new float[2][2][];
        for (int p = 0; p < 2; p++) {
            for (int n = 0; n < 2; n++) {
                longWindows[p][n] = window(VorbisEncoderLayout.LONG_BLOCK, p == 1, n == 1);
            }
        }
        shortLowpass = layout.lowpassBin(VorbisEncoderLayout.SHORT_BLOCK);
        longLowpass = layout.lowpassBin(VorbisEncoderLayout.LONG_BLOCK);
        spectrum = new float[channels][VorbisEncoderLayout.LONG_BLOCK / 2];
        quantized = new int[channels][VorbisEncoderLayout.LONG_BLOCK / 2];
        shortCurves = new VorbisEncoderFloor.Curve[channels];
        longCurves = new VorbisEncoderFloor.Curve[channels];
        for (int c = 0; c < channels; c++) {
            shortCurves[c] = layout.shortFloor.newCurve();
            longCurves[c] = layout.longFloor.newCurve();
        }
        floorUsed = new boolean[channels];
        previousBands = new float[channels][longPsy.bands()];
        previousValid = new boolean[channels];
        buffer = new float[channels][BUFFER];
        lastSample = new float[channels];
    }

    /**
     * Encodes the whole stream into {@code sink}.
     *
     * @return the number of frames the source delivered
     */
    long run(PcmStream source, VorbisEncoderSink sink) throws IOException {
        input = source;
        bufferStart = 0;
        buffered = 0;
        ended = false;
        length = 0;
        scannedSegments = 0;
        java.util.Arrays.fill(lastSample, 0f);
        java.util.Arrays.fill(history, 0f);
        attackHead = 0;
        attackCount = 0;
        java.util.Arrays.fill(previousValid, false);

        long centre = 0;
        boolean currentLong = false;
        boolean previousLong = false;
        long blocks = 0;
        while (true) {
            if (++blocks % INTERRUPT_CHECK_BLOCKS == 0 && Thread.currentThread().isInterrupted()) {
                throw new InterruptedIOException("Vorbis encoding was interrupted");
            }
            int quarter = (currentLong ? VorbisEncoderLayout.LONG_BLOCK : VorbisEncoderLayout.SHORT_BLOCK) / 4;
            // Enough input to see attacks inside any long block the next one could be, and its right half.
            fill(centre + quarter + 512 + 576 + 2 * SEGMENT + 1024);
            boolean last = ended && centre >= length;
            boolean nextLong = true;
            if (!last) {
                long longCentre = centre + quarter + 512;
                long longStart = longCentre - (currentLong ? 1024 : 576);
                nextLong = !attackBetween(longStart, longCentre + 576);
                // A short block may not be the last one right after a long block: stb_vorbis has already returned
                // part of the long block's tail by then and could not cut the stream short enough.
                if (!nextLong && currentLong && ended && centre + quarter + 64 >= length) {
                    nextLong = true;
                }
            }
            encodeBlock(centre, currentLong, previousLong, nextLong, sink);
            sink.endPacket(last ? length : centre, last);
            if (last) {
                return length;
            }
            dropAttacksBefore(centre - 4096);
            previousLong = currentLong;
            centre += quarter + (nextLong ? VorbisEncoderLayout.LONG_BLOCK : VorbisEncoderLayout.SHORT_BLOCK) / 4;
            currentLong = nextLong;
        }
    }

    private void encodeBlock(long centre, boolean isLong, boolean previousLong, boolean nextLong,
            VorbisEncoderSink sink) {
        int n = isLong ? VorbisEncoderLayout.LONG_BLOCK : VorbisEncoderLayout.SHORT_BLOCK;
        int bins = n / 2;
        float[] win = isLong ? longWindows[previousLong ? 1 : 0][nextLong ? 1 : 0] : shortWindow;
        MdctForward mdct = isLong ? longMdct : shortMdct;
        PsyModel psy = isLong ? longPsy : shortPsy;
        VorbisEncoderFloor floor = isLong ? layout.longFloor : layout.shortFloor;
        VorbisEncoderFloor.Curve[] curves = isLong ? longCurves : shortCurves;
        int lowpass = isLong ? longLowpass : shortLowpass;
        long first = centre - bins;
        boolean any = false;
        for (int c = 0; c < channels; c++) {
            read(c, first, n, win);
            float[] x = spectrum[c];
            mdct.forward(windowed, x);
            psy.allowedNoise(x, layout.noiseGain, noise, isLong ? previousBands[c] : null, previousValid[c]);
            previousValid[c] = isLong;
            chooseSteps(psy, x, lowpass);
            float edge = target[Math.max(0, lowpass - 1)];
            for (int k = lowpass; k < bins; k++) {
                target[k] = edge;
            }
            VorbisEncoderFloor.Curve curve = curves[c];
            floor.fit(target, lowpass, curve);
            int[] q = quantized[c];
            int[] index = curve.index;
            boolean nonzero = false;
            for (int k = 0; k < lowpass; k++) {
                int idx = Math.max(0, Math.min(255, index[k]));
                float r = x[k] * VorbisEncoderFloor.INVERSE_DB_RECIPROCAL[idx];
                int v = (int) (r + (r >= 0 ? ROUND : -ROUND));
                q[k] = v;
                nonzero |= v != 0;
            }
            for (int k = lowpass; k < bins; k++) {
                q[k] = 0;
            }
            floorUsed[c] = nonzero;
            any |= nonzero;
        }
        if (channels == 2) {
            couple(quantized[0], quantized[1], lowpass);
        }
        sink.bits(0, 1);
        sink.bits(isLong ? 1 : 0, 1);
        if (isLong) {
            sink.bits(previousLong ? 1 : 0, 1);
            sink.bits(nextLong ? 1 : 0, 1);
        }
        for (int c = 0; c < channels; c++) {
            if (floorUsed[c]) {
                floor.write(curves[c], sink);
            } else {
                sink.bits(0, 1);
            }
        }
        if (any) {
            (isLong ? layout.longResidue : layout.shortResidue).write(quantized, sink);
        }
    }

    /**
     * Picks the quantization step of every masking band from the band's actual coefficients, and writes it to
     * {@link #target} in floor-table units.
     *
     * <p>Rounding to a step s leaves about s^2/12 of noise only when the values are large against s. Where the signal
     * is only a few dB above its mask, most coefficients would round to zero and the error would be the signal
     * itself, up to 5 dB above what the band may carry. So each band takes the largest step whose real rounding
     * error stays within its allowed noise, found by stepping down (or up) in 1 dB steps from the s^2/12 guess, the
     * way AAC encoders run their distortion loop. A band quieter than its mask may vanish entirely, so its step is
     * made large enough to round everything to zero.
     */
    private void chooseSteps(PsyModel psy, float[] x, int lowpass) {
        for (int b = 0; b < psy.bands(); b++) {
            int lo = psy.bandStart(b);
            if (lo >= lowpass) {
                break;
            }
            int hi = Math.min(psy.bandEnd(b), lowpass);
            float allowed = 0f;
            float energy = 0f;
            float peak = 0f;
            for (int k = lo; k < hi; k++) {
                allowed += noise[k];
                float v = x[k];
                energy += v * v;
                peak = Math.max(peak, Math.abs(v));
            }
            float step = (float) Math.sqrt(layout.stepToNoise * allowed / (hi - lo));
            if (energy <= allowed) {
                step = Math.max(step, 2.01f * peak);
            } else if (roundingError(x, lo, hi, step) > allowed) {
                for (int i = 0; i < 24 && roundingError(x, lo, hi, step) > allowed; i++) {
                    step *= STEP_DOWN;
                }
            } else {
                for (int i = 0; i < 12; i++) {
                    float larger = step * STEP_UP;
                    if (roundingError(x, lo, hi, larger) > allowed) {
                        break;
                    }
                    step = larger;
                }
            }
            // Never so fine that a residue value would overflow the largest book.
            step = Math.max(step, peak / PEAK_RESIDUE);
            float t = (PsyModel.fastLog(step + 1e-30f) - VorbisEncoderFloor.LN_FIRST) / VorbisEncoderFloor.LN_STEP;
            t = Math.min(254f, t);
            for (int k = lo; k < hi; k++) {
                target[k] = t;
            }
        }
    }

    private static float roundingError(float[] x, int lo, int hi, float step) {
        float inverse = 1f / step;
        float error = 0f;
        for (int k = lo; k < hi; k++) {
            float v = x[k];
            float r = v * inverse;
            int q = (int) (r + (r >= 0 ? ROUND : -ROUND));
            float d = v - q * step;
            error += d * d;
        }
        return error;
    }

    /**
     * Lossless square-polar coupling of two quantized channels: the larger of the two as the magnitude, their
     * difference as the angle. Its exact inverse is what every decoder runs, and for the correlated left and right
     * of most music the angle is mostly zero.
     */
    static void couple(int[] left, int[] right, int bins) {
        for (int k = 0; k < bins; k++) {
            int a = left[k];
            int b = right[k];
            int magnitude;
            int angle;
            if (Math.abs(a) > Math.abs(b)) {
                magnitude = a;
                angle = a > 0 ? a - b : b - a;
            } else {
                magnitude = b;
                angle = b > 0 ? a - b : b - a;
            }
            left[k] = magnitude;
            right[k] = angle;
        }
    }

    /** Copies samples {@code first..first+n} of a channel, times the window, into {@link #windowed}. */
    private void read(int channel, long first, int n, float[] win) {
        float[] src = buffer[channel];
        for (int i = 0; i < n; i++) {
            long at = first + i;
            float s;
            if (at < 0 || (ended && at >= length)) {
                s = 0f;
            } else {
                s = src[(int) (at - bufferStart)];
            }
            windowed[i] = s * win[i];
        }
    }

    /** Reads until the buffer holds sample {@code until - 1} or the song has ended, scanning for attacks. */
    private void fill(long until) throws IOException {
        while (!ended && bufferStart + buffered < until) {
            if (buffered == BUFFER) {
                // Keep the last 6144 samples. A refill only happens while the data ends less than 2752 samples past
                // the current centre, and no block reaches back more than 1024 samples before its centre, so every
                // sample a block can still need survives the shift.
                int keep = 6 * 1024;
                int drop = buffered - keep;
                for (int c = 0; c < channels; c++) {
                    System.arraycopy(buffer[c], drop, buffer[c], 0, keep);
                }
                bufferStart += drop;
                buffered = keep;
            }
            int got = input.read(buffer, buffered, BUFFER - buffered);
            if (got < 0) {
                ended = true;
                length = bufferStart + buffered;
                scan(true);
                return;
            }
            if (got == 0) {
                throw new AudioFormatException("The audio source returned no frames without ending");
            }
            sanitize(buffered, got);
            buffered += got;
            scan(false);
        }
    }

    /**
     * Replaces NaN with silence and clamps infinities and absurd values. A damaged decoder can hand those over, and a
     * single NaN would otherwise poison the attack detector's history and every spectrum it touches.
     */
    private void sanitize(int from, int count) {
        for (int c = 0; c < channels; c++) {
            float[] src = buffer[c];
            for (int i = from; i < from + count; i++) {
                float s = src[i];
                if (!(s >= -SAMPLE_LIMIT && s <= SAMPLE_LIMIT)) {
                    src[i] = s > 0 ? SAMPLE_LIMIT : s < 0 ? -SAMPLE_LIMIT : 0f;
                }
            }
        }
    }

    /** Runs the attack detector over every whole segment that has arrived. */
    private void scan(boolean flushPartial) {
        long available = bufferStart + buffered;
        while (true) {
            long start = scannedSegments * SEGMENT;
            if (start >= available || (!flushPartial && start + SEGMENT > available)) {
                return;
            }
            int count = (int) Math.min(SEGMENT, available - start);
            float energy = 0f;
            for (int c = 0; c < channels; c++) {
                float[] src = buffer[c];
                int at = (int) (start - bufferStart);
                float previous = lastSample[c];
                for (int i = 0; i < count; i++) {
                    float s = src[at + i];
                    float d = s - previous;
                    energy += d * d;
                    previous = s;
                }
                lastSample[c] = previous;
            }
            energy /= count * channels;
            float before = 0f;
            for (float h : history) {
                before += h;
            }
            before /= HISTORY_SEGMENTS;
            if (energy > ATTACK_FLOOR && energy > ATTACK_RATIO * before) {
                addAttack(start);
            }
            history[(int) (scannedSegments % HISTORY_SEGMENTS)] = energy;
            scannedSegments++;
        }
    }

    private void addAttack(long position) {
        if (attackCount == attacks.length) {
            long[] grown = new long[attacks.length * 2];
            for (int i = 0; i < attackCount; i++) {
                grown[i] = attacks[(attackHead + i) % attacks.length];
            }
            attacks = grown;
            attackHead = 0;
        }
        attacks[(attackHead + attackCount) % attacks.length] = position;
        attackCount++;
    }

    private void dropAttacksBefore(long position) {
        while (attackCount > 0 && attacks[attackHead] < position) {
            attackHead = (attackHead + 1) % attacks.length;
            attackCount--;
        }
    }

    /** Whether an attack segment overlaps samples {@code from..to-1}. */
    private boolean attackBetween(long from, long to) {
        for (int i = 0; i < attackCount; i++) {
            long t = attacks[(attackHead + i) % attacks.length];
            if (t + SEGMENT > from && t < to) {
                return true;
            }
        }
        return false;
    }

    /**
     * The Vorbis window of a block, as the specification shapes it: rising and falling slopes of
     * {@code sin(pi/2 sin^2(...))}, half a block long between two long blocks and 128 samples wherever a short
     * block is involved, with zeros and ones filling the rest of a long block that borders a short one.
     */
    static float[] window(int n, boolean previousLong, boolean nextLong) {
        float[] w = new float[n];
        boolean isLong = n > VorbisEncoderLayout.SHORT_BLOCK;
        int shortHalf = VorbisEncoderLayout.SHORT_BLOCK / 2;
        int leftStart;
        int leftLength;
        int rightStart;
        int rightLength;
        if (isLong && !previousLong) {
            leftStart = n / 4 - VorbisEncoderLayout.SHORT_BLOCK / 4;
            leftLength = shortHalf;
        } else {
            leftStart = 0;
            leftLength = n / 2;
        }
        if (isLong && !nextLong) {
            rightStart = n * 3 / 4 - VorbisEncoderLayout.SHORT_BLOCK / 4;
            rightLength = shortHalf;
        } else {
            rightStart = n / 2;
            rightLength = n / 2;
        }
        for (int i = 0; i < n; i++) {
            double v;
            if (i < leftStart) {
                v = 0;
            } else if (i < leftStart + leftLength) {
                double s = Math.sin((i - leftStart + 0.5) / leftLength * Math.PI / 2);
                v = Math.sin(Math.PI / 2 * s * s);
            } else if (i < rightStart) {
                v = 1;
            } else if (i < rightStart + rightLength) {
                double s = Math.sin((i - rightStart + 0.5) / rightLength * Math.PI / 2 + Math.PI / 2);
                v = Math.sin(Math.PI / 2 * s * s);
            } else {
                v = 0;
            }
            w[i] = (float) v;
        }
        return w;
    }
}

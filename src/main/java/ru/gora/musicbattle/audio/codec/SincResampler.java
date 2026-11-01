package ru.gora.musicbattle.audio.codec;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.Arrays;
import java.util.Objects;

/**
 * Brings a song recorded above 48 kHz down to 48 kHz. Minecraft mixes at 44.1 or 48 kHz, so everything above that is
 * inaudible bulk, and a 96 or 192 kHz Ogg would only be bigger and slower to send to every player. Songs at 48 kHz
 * and below are left exactly as they are.
 *
 * <p>A windowed-sinc low-pass (Kaiser window, beta 8.6, 32 zero crossings a side), evaluated at each output
 * sample's exact position in the input. Measured on sines: flat to 20 kHz, -0.8 dB at 21 kHz, -8 dB at 22 kHz, and
 * everything from 24 kHz up, which would fold back as aliases, at -90 dB or lower. The filter is symmetric, so output
 * sample {@code n} sits exactly at input time {@code n * inRate / 48000}: no delay, no shift of the chart. The output
 * has {@code round(inFrames * 48000 / inRate)} frames. About 0.9 s per four minutes of 96 kHz stereo.
 *
 * <p>Common rates (88.2, 96, 176.4, 192 kHz) are a fraction with a small denominator, so every phase of the filter
 * is computed once up front; any other rate interpolates a finely sampled kernel instead.
 *
 * <p>The same filter also brings a stretch of an MP3 whose sample rate changes part way back to the rate the song
 * started at ({@link #to}), up or down; the pass band then ends at 91 % of the lower of the two Nyquist frequencies.
 */
public final class SincResampler implements PcmStream {
    public static final int TARGET_RATE = 48000;

    /** Zero crossings of the sinc on each side of the centre. */
    private static final int ZERO_CROSSINGS = 32;
    /** Where the pass band ends, as a share of the output's Nyquist frequency. */
    private static final double CUTOFF = 0.91;
    private static final double KAISER_BETA = 8.6;
    /** More phases than this and the table would cost megabytes; the interpolated kernel takes over. */
    private static final int MAX_TABLE_PHASES = 4096;
    /** Points per input sample of the interpolated kernel. */
    private static final int KERNEL_OVERSAMPLE = 512;
    private static final int CHUNK = 4096;

    private final PcmStream source;
    private final int channels;
    private final int inRate;
    private final int targetRate;
    /** Output frame n reads input position n * step / phases. */
    private final long step;
    private final long phases;
    /** Taps on each side of the centre, in input samples. */
    private final int half;
    private final int taps;
    /** For a small number of phases: taps for each phase, from input {@code centre - half + 1} upwards. */
    private final float[][] table;
    /** Otherwise: the kernel at KERNEL_OVERSAMPLE points per input sample, from 0 to half. */
    private final float[] kernel;
    private final double kernelScale;

    /** Input frames in memory: absolute index {@code bufferStart} is {@code buffer[c][0]}. */
    private float[][] buffer;
    private long bufferStart;
    private int bufferLength;
    private boolean sourceEnded;
    private long sourceFrames;
    private long outputPosition;
    private long outputFrames = -1;
    private float[][] readScratch;

    private SincResampler(PcmStream source, int targetRate) {
        this.source = source;
        this.channels = source.channels();
        this.inRate = source.sampleRate();
        this.targetRate = targetRate;
        long gcd = gcd(inRate, targetRate);
        this.step = inRate / gcd;
        this.phases = targetRate / gcd;
        // The cutoff in cycles per input sample, and the kernel's half-width in input samples.
        double cutoff = CUTOFF * 0.5 * Math.min(targetRate, inRate) / inRate;
        this.half = (int) Math.ceil(ZERO_CROSSINGS / (2.0 * cutoff));
        this.taps = 2 * half;
        if (phases <= MAX_TABLE_PHASES) {
            table = new float[(int) phases][taps];
            for (int p = 0; p < phases; p++) {
                double fraction = p / (double) phases;
                // Tap t multiplies input (floor(position) - half + 1 + t); its distance from the position is below.
                double sum = 0.0;
                double[] values = new double[taps];
                for (int t = 0; t < taps; t++) {
                    double distance = (t - half + 1) - fraction;
                    values[t] = kernelValue(distance, cutoff, half);
                    sum += values[t];
                }
                // Each phase passes a constant exactly, so the window's ripple cannot turn into a whine at the
                // phase pattern's own rate.
                for (int t = 0; t < taps; t++) {
                    table[p][t] = (float) (values[t] / sum);
                }
            }
            kernel = null;
            kernelScale = 0.0;
        } else {
            table = null;
            kernel = new float[half * KERNEL_OVERSAMPLE + 2];
            for (int i = 0; i < kernel.length; i++) {
                kernel[i] = (float) kernelValue(i / (double) KERNEL_OVERSAMPLE, cutoff, half);
            }
            kernelScale = KERNEL_OVERSAMPLE;
        }
        this.buffer = new float[channels][CHUNK * 4];
        // Input before the start is silence; keeping it in the buffer saves a bounds test per tap.
        this.bufferStart = -half;
        this.bufferLength = half;
        this.readScratch = new float[channels][CHUNK];
    }

    /** The source itself at 48 kHz or below, otherwise a 48 kHz stream of it. */
    public static PcmStream to48k(PcmStream source) {
        Objects.requireNonNull(source, "source");
        return source.sampleRate() > TARGET_RATE ? new SincResampler(source, TARGET_RATE) : source;
    }

    /** A stream of the source at {@code rate}, or the source itself when it is at that rate already. */
    static PcmStream to(PcmStream source, int rate) {
        Objects.requireNonNull(source, "source");
        if (rate <= 0) {
            throw new IllegalArgumentException("rate " + rate);
        }
        return source.sampleRate() == rate ? source : new SincResampler(source, rate);
    }

    private static double kernelValue(double distance, double cutoff, int half) {
        double x = Math.abs(distance);
        if (x >= half) {
            return 0.0;
        }
        double t = 2.0 * cutoff * x;
        double sinc = t < 1e-12 ? 1.0 : StrictMath.sin(Math.PI * t) / (Math.PI * t);
        double r = x / half;
        double window = besselI0(KAISER_BETA * Math.sqrt(1.0 - r * r)) / besselI0(KAISER_BETA);
        return 2.0 * cutoff * sinc * window;
    }

    /** The modified Bessel function of the first kind, order zero, by its power series. */
    private static double besselI0(double x) {
        double sum = 1.0;
        double term = 1.0;
        double quarter = x * x / 4.0;
        for (int k = 1; k < 64; k++) {
            term *= quarter / ((double) k * k);
            sum += term;
            if (term < sum * 1e-17) {
                break;
            }
        }
        return sum;
    }

    private static long gcd(long a, long b) {
        while (b != 0) {
            long t = a % b;
            a = b;
            b = t;
        }
        return a;
    }

    @Override
    public int sampleRate() {
        return targetRate;
    }

    @Override
    public int channels() {
        return channels;
    }

    @Override
    public long frameCountHint() {
        long hint = source.frameCountHint();
        return hint < 0 ? -1L : outputLength(hint);
    }

    private long outputLength(long inputFrames) {
        // round(inputFrames * phases / step), in integers so a long song cannot lose a frame to doubles.
        return (inputFrames * phases + step / 2) / step;
    }

    @Override
    public int read(float[][] out, int offset, int frames) throws IOException {
        if (frames <= 0) {
            return 0;
        }
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("Resampling was interrupted.");
        }
        int done = 0;
        while (done < frames) {
            if (outputFrames >= 0 && outputPosition >= outputFrames) {
                break;
            }
            // The input positions this output frame needs: floor(position) - half + 1 to floor(position) + half.
            long whole = Math.floorDiv(outputPosition * step, phases);
            long needed = whole + half + 1;
            if (!fill(needed)) {
                continue;
            }
            int phase = (int) Math.floorMod(outputPosition * step, phases);
            int first = (int) (whole - half + 1 - bufferStart);
            if (table != null) {
                float[] coefficients = table[phase];
                for (int c = 0; c < channels; c++) {
                    float[] in = buffer[c];
                    float sum = 0f;
                    for (int t = 0; t < taps; t++) {
                        sum += coefficients[t] * in[first + t];
                    }
                    out[c][offset + done] = sum;
                }
            } else {
                double fraction = phase / (double) phases;
                for (int c = 0; c < channels; c++) {
                    out[c][offset + done] = 0f;
                }
                for (int t = 0; t < taps; t++) {
                    double distance = Math.abs((t - half + 1) - fraction) * kernelScale;
                    int index = (int) distance;
                    float weight = (float) (distance - index);
                    float coefficient = index + 1 < kernel.length
                            ? kernel[index] + weight * (kernel[index + 1] - kernel[index])
                            : 0f;
                    for (int c = 0; c < channels; c++) {
                        out[c][offset + done] += coefficient * buffer[c][first + t];
                    }
                }
            }
            outputPosition++;
            done++;
        }
        return done == 0 ? -1 : done;
    }

    /**
     * Makes input up to (not including) {@code needed} available, with silence after the end of the source.
     *
     * @return false when the source has just ended and the caller must check the length again
     */
    private boolean fill(long needed) throws IOException {
        while (bufferStart + bufferLength < needed) {
            compact(needed);
            int room = buffer[0].length - bufferLength;
            if (room < CHUNK) {
                int grown = Math.max(buffer[0].length * 2, bufferLength + CHUNK);
                for (int c = 0; c < channels; c++) {
                    buffer[c] = Arrays.copyOf(buffer[c], grown);
                }
            }
            if (sourceEnded) {
                // Silence past the end, as much as the filter reaches.
                int count = (int) Math.min(CHUNK, needed - (bufferStart + bufferLength));
                for (int c = 0; c < channels; c++) {
                    Arrays.fill(buffer[c], bufferLength, bufferLength + count, 0f);
                }
                bufferLength += count;
                continue;
            }
            int read = source.read(readScratch, 0, CHUNK);
            if (read < 0) {
                sourceEnded = true;
                outputFrames = outputLength(sourceFrames);
                return false;
            }
            for (int c = 0; c < channels; c++) {
                System.arraycopy(readScratch[c], 0, buffer[c], bufferLength, read);
            }
            bufferLength += read;
            sourceFrames += read;
        }
        return true;
    }

    /** Drops input no future output frame reaches back to. */
    private void compact(long needed) {
        long keepFrom = Math.floorDiv(outputPosition * step, phases) - half;
        int drop = (int) Math.min(bufferLength, keepFrom - bufferStart);
        if (drop < buffer[0].length / 2) {
            return;
        }
        for (int c = 0; c < channels; c++) {
            System.arraycopy(buffer[c], drop, buffer[c], 0, bufferLength - drop);
        }
        bufferStart += drop;
        bufferLength -= drop;
    }

    @Override
    public void close() throws IOException {
        source.close();
    }
}

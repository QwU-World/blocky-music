package ru.gora.musicbattle.audio.codec;

import java.io.IOException;

/**
 * Keeps the 1.5x and 2x copies of a loud song from clipping more than the song itself: a look-ahead peak limiter on
 * {@link TimeStretch}'s output, before the copy is Vorbis encoded.
 *
 * <p>Why it is needed: a loud master sits at full scale, and a lossy encoder's output overshoots the samples it was
 * given, so every encoded copy clips somewhat where Minecraft cuts it off at full scale; the 1x conversion too. The
 * stretched copies have a crossfade at every grain join, and a join of two grains that do not line up has more high
 * frequency content than the music around it, which the encoder overshoots more. Measured on loud masters, the
 * finished 2x copies then had 50 ms windows with clipping distortion louder than -40 dB where the source had none. A
 * copy limited to {@link #CEILING} before it is encoded keeps the encoder's overshoot below full scale (the
 * measurements are at the constant).
 *
 * <p>How: where a sample of any channel would exceed the ceiling, the gain of all channels comes down to exactly what
 * keeps it at the ceiling, reached in a straight ramp over the {@link #LOOKAHEAD_SECONDS} before it and recovered
 * over {@link #RELEASE_SECONDS} after it, so the gain never jumps and the channels keep their balance. Nothing else is
 * changed: a song whose copy stays below the ceiling comes out sample for sample as it went in, and the output has
 * exactly as many frames as the input, each in its place (the look-ahead is a delay inside this class only).
 */
public final class PeakLimiter implements PcmStream {
    /**
     * The highest sample the copy is let reach before encoding: -1.5 dBFS. Measured on loud masters (music-like
     * synthetic ones saturated to 0.999, and real ones that clip 0.2 to 19 percent of their samples), the encoded
     * copies then have no 50 ms window with clipping distortion louder than -43 dB, and at most 28 clipped samples in
     * a 20 s copy, where -1 dBFS left up to 400 and windows at -40 dB, and the unlimited copies thousands and windows
     * at -29 dB. A harsher master than any real song (saw chords, kicks and noise driven into a soft clipper, the one
     * tools/NativeAudioChecks builds) still overshoots: 9 to 10 windows near -37 dB, against 80 to 106 unlimited and
     * 160 in its 1x conversion; -3 dBFS would take those away too, but would turn every loud song's copies down.
     */
    static final float CEILING = 0.84139514f;
    static final double LOOKAHEAD_SECONDS = 0.0015;
    static final double RELEASE_SECONDS = 0.060;
    private static final int CHUNK = 4096;

    private final PcmStream source;
    private final int channels;
    private final int lookahead;
    private final float release;
    /** Input frames not yet handed out, from absolute frame {@code start}: ring of size {@code ring[0].length}. */
    private final float[][] ring;
    /** The gain each buffered input frame needs on its own: 1, or the ceiling over its peak. */
    private final float[] need;
    private long start;
    private long end;
    private boolean sourceEnded;
    /** The gain of the last frame handed out. */
    private float gain = 1f;
    /** The last buffered frame that needs a gain below 1, or -1. */
    private long lastOver = -1;
    private final float[][] scratch;

    private PeakLimiter(PcmStream source) {
        this.source = source;
        this.channels = source.channels();
        int rate = Math.max(1, source.sampleRate());
        this.lookahead = Math.max(1, (int) Math.round(LOOKAHEAD_SECONDS * rate));
        // The share of the remaining gain reduction kept from one frame to the next while recovering.
        this.release = (float) StrictMath.exp(-1.0 / (RELEASE_SECONDS * rate));
        int size = Integer.highestOneBit(lookahead + CHUNK) << 1;
        this.ring = new float[channels][size];
        this.need = new float[size];
        this.scratch = new float[channels][CHUNK];
    }

    /** The stream with its peaks held at {@link #CEILING}. */
    public static PcmStream wrap(PcmStream source) {
        return new PeakLimiter(source);
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
        return source.frameCountHint();
    }

    @Override
    public int read(float[][] out, int offset, int frames) throws IOException {
        if (frames <= 0) {
            return 0;
        }
        // Frame j can go out once frame j + lookahead is in, or the source has ended.
        while (!sourceEnded && end - start <= lookahead) {
            fill();
        }
        long ready = sourceEnded ? end - start : end - start - lookahead;
        if (ready <= 0) {
            return -1;
        }
        int n = (int) Math.min(frames, ready);
        int mask = need.length - 1;
        for (int i = 0; i < n; i++) {
            long j = start + i;
            float g;
            if (lastOver < j && gain == 1f) {
                g = 1f;
            } else {
                // The ramp: frame k needing gain r pulls frame j (k - j frames before it) down to at most
                // r + (1 - r) * (k - j) / (lookahead + 1), so the gain is r exactly at k and 1 a lookahead earlier.
                float target = 1f;
                if (lastOver >= j) {
                    long to = Math.min(end - 1, j + lookahead);
                    for (long k = j; k <= to; k++) {
                        float r = need[(int) (k & mask)];
                        if (r < 1f) {
                            float ramp = r + (1f - r) * (k - j) / (lookahead + 1);
                            if (ramp < target) {
                                target = ramp;
                            }
                        }
                    }
                }
                float recovered = 1f - (1f - gain) * release;
                g = Math.min(target, recovered);
                if (g > 1f - 1e-6f && lastOver < j) {
                    g = 1f;
                }
            }
            gain = g;
            int at = (int) (j & mask);
            for (int c = 0; c < channels; c++) {
                out[c][offset + i] = g == 1f ? ring[c][at] : ring[c][at] * g;
            }
        }
        start += n;
        return n;
    }

    private void fill() throws IOException {
        int room = (int) Math.min(CHUNK, need.length - (end - start));
        int n = source.read(scratch, 0, room);
        if (n < 0) {
            sourceEnded = true;
            return;
        }
        int mask = need.length - 1;
        for (int i = 0; i < n; i++) {
            int at = (int) ((end + i) & mask);
            float peak = 0f;
            for (int c = 0; c < channels; c++) {
                float v = scratch[c][i];
                ring[c][at] = v;
                float a = Math.abs(v);
                if (a > peak) {
                    peak = a;
                }
            }
            if (peak > CEILING) {
                need[at] = CEILING / peak;
                lastOver = end + i;
            } else {
                need[at] = 1f;
            }
        }
        end += n;
    }

    @Override
    public void close() throws IOException {
        source.close();
    }
}

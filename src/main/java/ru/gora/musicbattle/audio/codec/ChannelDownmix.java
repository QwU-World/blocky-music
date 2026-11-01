package ru.gora.musicbattle.audio.codec;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.Objects;

/**
 * Folds a song with more than two channels into stereo, because the Vorbis encoder writes one or two and Minecraft
 * plays songs as stereo anyway.
 *
 * <p>The mix is the usual one (ITU-R BS.775, and ffmpeg's default): centre and surrounds at -3 dB into both sides, a
 * rear centre at -6 dB into each, the LFE channel left out. The matrix is then scaled so no output can sum past full
 * scale, as ffmpeg's resampler does, which keeps a loud 5.1 film mix from clipping at the price of being quieter.
 *
 * <p>Formats number their channels differently: Vorbis puts the centre second, WAV and FLAC third with the LFE
 * fourth. A layout this class does not know (more than eight channels) is split by position: even channels to the
 * left, odd ones to the right.
 */
public final class ChannelDownmix implements PcmStream {
    /** Which speaker each channel is, by format. */
    public enum Layout {
        /** The Vorbis I specification's channel mapping 1, section 4.3.9. */
        VORBIS,
        /** WAVE_FORMAT_EXTENSIBLE's default order, which FLAC also uses (RFC 9639 section 9.1.3). */
        WAVE
    }

    private static final float MINUS_3_DB = 0.70710677f;
    private static final float MINUS_6_DB = 0.5f;

    // Speaker roles; each row of a layout table names the role of every channel.
    private static final int L = 0;
    private static final int R = 1;
    private static final int C = 2;
    private static final int LFE = 3;
    private static final int LS = 4;
    private static final int RS = 5;
    private static final int CS = 6;
    private static final int LB = 7;
    private static final int RB = 8;

    private static final int[][] VORBIS_ROLES = {
            null, null, null,
            {L, C, R},
            {L, R, LS, RS},
            {L, C, R, LS, RS},
            {L, C, R, LS, RS, LFE},
            {L, C, R, LS, RS, CS, LFE},
            {L, C, R, LS, RS, LB, RB, LFE},
    };

    private static final int[][] WAVE_ROLES = {
            null, null, null,
            {L, R, C},
            {L, R, LB, RB},
            {L, R, C, LB, RB},
            {L, R, C, LFE, LB, RB},
            {L, R, C, LFE, CS, LS, RS},
            {L, R, C, LFE, LB, RB, LS, RS},
    };

    private final PcmStream source;
    private final float[] left;
    private final float[] right;
    private float[][] scratch = new float[0][];

    private ChannelDownmix(PcmStream source, float[] left, float[] right) {
        this.source = source;
        this.left = left;
        this.right = right;
    }

    /** The source itself when it has one or two channels, otherwise a stereo mix of it. */
    public static PcmStream toStereo(PcmStream source, Layout layout) {
        Objects.requireNonNull(source, "source");
        int channels = source.channels();
        if (channels <= 2) {
            return source;
        }
        float[][] matrix = matrix(channels, layout);
        return new ChannelDownmix(source, matrix[0], matrix[1]);
    }

    /** The left and right coefficient of every input channel, already scaled so neither side can pass full scale. */
    static float[][] matrix(int channels, Layout layout) {
        float[] left = new float[channels];
        float[] right = new float[channels];
        int[][] table = layout == Layout.VORBIS ? VORBIS_ROLES : WAVE_ROLES;
        int[] roles = channels < table.length ? table[channels] : null;
        if (roles == null) {
            for (int c = 0; c < channels; c++) {
                if (c % 2 == 0) {
                    left[c] = 1f;
                } else {
                    right[c] = 1f;
                }
            }
        } else {
            for (int c = 0; c < channels; c++) {
                switch (roles[c]) {
                    case L -> left[c] = 1f;
                    case R -> right[c] = 1f;
                    case C -> {
                        left[c] = MINUS_3_DB;
                        right[c] = MINUS_3_DB;
                    }
                    case LS, LB -> left[c] = MINUS_3_DB;
                    case RS, RB -> right[c] = MINUS_3_DB;
                    case CS -> {
                        left[c] = MINUS_6_DB;
                        right[c] = MINUS_6_DB;
                    }
                    default -> {
                        // LFE: left out, as ffmpeg's default mix does; a sub-bass channel summed into full-range
                        // speakers mostly adds rumble and headroom trouble.
                    }
                }
            }
        }
        float sumLeft = 0f;
        float sumRight = 0f;
        for (int c = 0; c < channels; c++) {
            sumLeft += left[c];
            sumRight += right[c];
        }
        float scale = Math.max(1f, Math.max(sumLeft, sumRight));
        for (int c = 0; c < channels; c++) {
            left[c] /= scale;
            right[c] /= scale;
        }
        return new float[][] {left, right};
    }

    @Override
    public int sampleRate() {
        return source.sampleRate();
    }

    @Override
    public int channels() {
        return 2;
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
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("Downmixing was interrupted.");
        }
        int channels = left.length;
        if (scratch.length != channels || scratch[0].length < frames) {
            scratch = new float[channels][Math.max(frames, 1024)];
        }
        int read = source.read(scratch, 0, frames);
        if (read <= 0) {
            return read;
        }
        float[] outLeft = out[0];
        float[] outRight = out[1];
        for (int i = 0; i < read; i++) {
            outLeft[offset + i] = 0f;
            outRight[offset + i] = 0f;
        }
        for (int c = 0; c < channels; c++) {
            float[] in = scratch[c];
            float l = left[c];
            float r = right[c];
            if (l != 0f) {
                for (int i = 0; i < read; i++) {
                    outLeft[offset + i] += l * in[i];
                }
            }
            if (r != 0f) {
                for (int i = 0; i < read; i++) {
                    outRight[offset + i] += r * in[i];
                }
            }
        }
        return read;
    }

    @Override
    public void close() throws IOException {
        source.close();
    }
}

package ru.gora.musicbattle.audio.codec;

import java.io.IOException;

/**
 * Moves surround channels between Vorbis and WAVE order, for containers that carry codecs of both kinds while their
 * {@link AudioDecoders.Kind} says one order: Opus in MP4 and MPEG-TS (WAVE, as the AAC they mostly carry), and AAC,
 * FLAC, MP3 and PCM in Matroska (Vorbis, as the Opus and Vorbis it mostly carries). Opus and Vorbis use Vorbis order
 * (RFC 7845 section 5.1.1.2: L C R for three channels, then the surrounds, the LFE last); WAVE order is L R C LFE and
 * then the surrounds, which is also how ffmpeg hands out every codec. Mono and stereo are the same in both.
 */
final class ChannelOrder {
    /**
     * For each channel count, the Vorbis-order channel that lands at each WAVE position: 5.1 is L C R Ls Rs LFE in
     * Vorbis order and L R C LFE Ls Rs in WAVE order; 6.1 ends C_back, LFE in Vorbis and LFE, C_back, Ls, Rs in WAVE;
     * 7.1 has side and back pairs in Vorbis order (L C R Ls Rs Lb Rb LFE) and back before side in WAVE.
     */
    private static final int[][] VORBIS_AT_WAVE = {
            {}, {0}, {0, 1}, {0, 2, 1}, {0, 1, 2, 3}, {0, 2, 1, 3, 4}, {0, 2, 1, 5, 3, 4}, {0, 2, 1, 6, 5, 3, 4},
            {0, 2, 1, 7, 5, 6, 3, 4}};

    private ChannelOrder() {
    }

    /** The WAVE position of each Vorbis-order channel, or null when the order is the same. */
    static int[] waveIndexOfVorbis(int channels) {
        if (channels <= 2 || channels > 8) {
            return null;
        }
        int[] vorbisAt = VORBIS_AT_WAVE[channels];
        int[] wave = new int[channels];
        for (int w = 0; w < channels; w++) {
            wave[vorbisAt[w]] = w;
        }
        return wave;
    }

    /** A Vorbis-order stream with its channels in WAVE order; mono and stereo streams are returned as they are. */
    static PcmStream toWaveOrder(PcmStream vorbisOrder) {
        return permuted(vorbisOrder, waveIndexOfVorbis(vorbisOrder.channels()));
    }

    /** A WAVE-order stream with its channels in Vorbis order; mono and stereo streams are returned as they are. */
    static PcmStream toVorbisOrder(PcmStream waveOrder) {
        int channels = waveOrder.channels();
        if (channels <= 2 || channels > 8) {
            return waveOrder;
        }
        // WAVE channel w is Vorbis channel VORBIS_AT_WAVE[w].
        return permuted(waveOrder, VORBIS_AT_WAVE[channels].clone());
    }

    /** A stream whose source channel i is handed out as channel {@code target[i]}; null leaves it as it is. */
    private static PcmStream permuted(PcmStream source, int[] target) {
        if (target == null) {
            return source;
        }
        int[] wave = target;
        PcmStream vorbisOrder = source;
        return new PcmStream() {
            private final float[][] view = new float[wave.length][];

            @Override
            public int sampleRate() {
                return vorbisOrder.sampleRate();
            }

            @Override
            public int channels() {
                return vorbisOrder.channels();
            }

            @Override
            public long frameCountHint() {
                return vorbisOrder.frameCountHint();
            }

            @Override
            public int read(float[][] out, int offset, int frames) throws IOException {
                if (out.length < wave.length) {
                    throw new IllegalArgumentException("The output does not fit " + wave.length + " channels.");
                }
                // Each source channel is written straight into the channel it belongs to: no copy.
                for (int v = 0; v < wave.length; v++) {
                    view[v] = out[wave[v]];
                }
                return vorbisOrder.read(view, offset, frames);
            }

            @Override
            public void close() throws IOException {
                vorbisOrder.close();
            }
        };
    }
}

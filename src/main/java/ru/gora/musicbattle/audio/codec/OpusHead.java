package ru.gora.musicbattle.audio.codec;

/**
 * The Opus identification header (RFC 7845 section 5.1), which Ogg carries as its first packet and Matroska as the
 * track's CodecPrivate: channel count, pre-skip, output gain and the channel mapping.
 *
 * <p>Mapping family 0 (mono or stereo) and family 1 (up to 8 channels in Vorbis order) are what a player must
 * support; ambisonic families and family 255 have no speaker layout a song can be folded from, so they are refused
 * as an unsupported variant rather than as damage.
 */
final class OpusHead {
    final int version;
    final int channels;
    final int preSkip;
    final long inputSampleRate;
    /** Output gain in Q7.8 dB. */
    final int outputGain;
    final int family;
    final int streams;
    final int coupled;
    final int[] mapping;

    private OpusHead(int version, int channels, int preSkip, long inputSampleRate, int outputGain, int family,
                     int streams, int coupled, int[] mapping) {
        this.version = version;
        this.channels = channels;
        this.preSkip = preSkip;
        this.inputSampleRate = inputSampleRate;
        this.outputGain = outputGain;
        this.family = family;
        this.streams = streams;
        this.coupled = coupled;
        this.mapping = mapping;
    }

    /** Whether {@code data[off..]} starts with the "OpusHead" magic. */
    static boolean isOpusHead(byte[] data, int off, int len) {
        return len >= 8 && data[off] == 'O' && data[off + 1] == 'p' && data[off + 2] == 'u' && data[off + 3] == 's'
                && data[off + 4] == 'H' && data[off + 5] == 'e' && data[off + 6] == 'a' && data[off + 7] == 'd';
    }

    /**
     * Parses and validates an identification header.
     *
     * @throws UnsupportedFormatException for a valid header of a variant this package does not play; its format name
     *     says which, such as "Ambisonic Opus"
     * @throws AudioFormatException for a damaged header
     */
    static OpusHead parse(byte[] data, int off, int len) throws AudioFormatException {
        if (!isOpusHead(data, off, len)) {
            throw new AudioFormatException("The Opus identification header is missing.");
        }
        if (len < 19) {
            throw new AudioFormatException("The Opus identification header is cut short.");
        }
        int version = data[off + 8] & 0xFF;
        // Versions 0 to 15 share the major version RFC 7845 defines; 16 and up are incompatible by its section 5.1.
        if (version > 15) {
            throw new UnsupportedFormatException("Opus (header version " + version + ")");
        }
        int channels = data[off + 9] & 0xFF;
        if (channels == 0) {
            throw new AudioFormatException("The Opus header says the stream has no channels.");
        }
        int preSkip = (data[off + 10] & 0xFF) | (data[off + 11] & 0xFF) << 8;
        long inputRate = (data[off + 12] & 0xFFL) | (data[off + 13] & 0xFFL) << 8 | (data[off + 14] & 0xFFL) << 16
                | (data[off + 15] & 0xFFL) << 24;
        int gain = (short) ((data[off + 16] & 0xFF) | (data[off + 17] & 0xFF) << 8);
        int family = data[off + 18] & 0xFF;
        int streams;
        int coupled;
        int[] mapping = new int[channels];
        if (family == 0) {
            if (channels > 2) {
                throw new AudioFormatException("An Opus stream with channel mapping 0 has " + channels + " channels.");
            }
            streams = 1;
            coupled = channels - 1;
            for (int c = 0; c < channels; c++) {
                mapping[c] = c;
            }
        } else {
            if (len < 21 + channels) {
                throw new AudioFormatException("The Opus channel mapping table is cut short.");
            }
            streams = data[off + 19] & 0xFF;
            coupled = data[off + 20] & 0xFF;
            if (streams == 0 || coupled > streams || streams + coupled > 255) {
                throw new AudioFormatException("The Opus channel mapping table is invalid.");
            }
            for (int c = 0; c < channels; c++) {
                mapping[c] = data[off + 21 + c] & 0xFF;
                if (mapping[c] != 255 && mapping[c] >= streams + coupled) {
                    throw new AudioFormatException("The Opus channel mapping table points at a missing stream.");
                }
            }
            if (family == 1) {
                if (channels > 8) {
                    throw new AudioFormatException("An Opus stream with channel mapping 1 has " + channels
                            + " channels.");
                }
            } else if (family == 2 || family == 3) {
                throw new UnsupportedFormatException("Ambisonic Opus");
            } else {
                // Family 255 and the reserved families have no defined channel meaning: RFC 7845 asks
                // general-purpose players not to play them, and there is no layout to fold into stereo.
                throw new UnsupportedFormatException("Opus (channel mapping " + family + ")");
            }
        }
        return new OpusHead(version, channels, preSkip, inputRate, gain, family, streams, coupled, mapping);
    }

    OpusMultistreamDecoder newDecoder() {
        OpusMultistreamDecoder decoder = new OpusMultistreamDecoder(channels, streams, coupled, mapping);
        if (outputGain != 0) {
            decoder.setGain(outputGain);
        }
        return decoder;
    }
}

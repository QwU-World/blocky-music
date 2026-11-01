/*
 * Java port of part of the libopus 1.6.1 decoder. libopus is Copyright (c) Xiph.Org Foundation, Skype Limited, CSIRO,
 * Jean-Marc Valin, Timothy B. Terriberry, Gregory Maxwell, Mark Borgerding, Koen Vos, Arm Limited and the other
 * contributors to libopus, under the BSD-3-Clause licence (its CELT, src/ and include/ files under the two-clause form
 * of it). The copyright notices of the files this follows and the full licence text are in NOTICE.md, under
 * "libopus". QwU World's MIT licence does not cover this file.
 */
package ru.gora.musicbattle.audio.codec;

/**
 * Decodes a multistream Opus packet (RFC 7845 section 5.1.1): several elementary streams, the first ones coupled
 * stereo, the rest mono, each output channel taking one of their channels by the mapping table, or silence for 255.
 * Ported from libopus 1.6.1 src/opus_multistream_decoder.c (opus_multistream_decode_native) and the channel helpers
 * of src/opus_multistream.c. A plain mono or stereo stream is the one-stream case.
 */
final class OpusMultistreamDecoder {
    final int channels;
    final int streams;
    final int coupled;
    private final int[] mapping;
    private final OpusPacketDecoder[] decoders;
    /**
     * One stream whose channels land where they are decoded: every mono and stereo file. The stream then decodes
     * straight into the caller's buffer, skipping the copy that would only move each sample to the same place.
     */
    private final boolean direct;
    private final float[] buf = new float[2 * OpusPacketDecoder.MAX_FRAME_SIZE];
    private final short[] sizes = new short[48];
    private final int[] parsed = new int[3];
    private final int[] packetOffset = new int[1];

    /**
     * @param mapping for each output channel, the decoded channel it plays (0..streams+coupled-1) or 255 for silence
     * @throws IllegalArgumentException when the layout is not one libopus accepts
     */
    OpusMultistreamDecoder(int channels, int streams, int coupled, int[] mapping) {
        if (channels > 255 || channels < 1 || coupled > streams || streams < 1 || coupled < 0
                || streams > 255 - coupled || mapping.length < channels) {
            throw new IllegalArgumentException("Invalid Opus channel layout");
        }
        for (int c = 0; c < channels; c++) {
            if (mapping[c] >= streams + coupled && mapping[c] != 255) {
                throw new IllegalArgumentException("Opus channel " + c + " maps to a stream that does not exist");
            }
        }
        this.channels = channels;
        this.streams = streams;
        this.coupled = coupled;
        this.mapping = mapping.clone();
        this.decoders = new OpusPacketDecoder[streams];
        for (int s = 0; s < streams; s++) {
            decoders[s] = new OpusPacketDecoder(s < coupled ? 2 : 1);
        }
        this.direct = streams == 1 && (channels == 1 ? coupled == 0 && mapping[0] == 0
                : channels == 2 && coupled == 1 && mapping[0] == 0 && mapping[1] == 1);
    }

    /** OPUS_SET_GAIN on every stream: a gain in Q8 dB applied after decoding (the Ogg Opus output gain). */
    void setGain(int gainQ8) {
        for (OpusPacketDecoder d : decoders) {
            d.decodeGain = gainQ8;
        }
    }

    void reset() {
        for (OpusPacketDecoder d : decoders) {
            d.reset();
        }
    }

    /** The XOR of the streams' final range coder states, as opus_multistream_decoder_ctl reports it. */
    int finalRange() {
        int r = 0;
        for (OpusPacketDecoder d : decoders) {
            r ^= d.rangeFinal;
        }
        return r;
    }

    /** The duration of the last packet decoded, in samples per channel. */
    int lastPacketDuration() {
        return decoders[0].lastPacketDuration;
    }

    /**
     * opus_multistream_packet_validate: checks that every stream's packet parses and that they all last the same.
     *
     * @return the packet's duration, or a negative libopus error code
     */
    int validate(byte[] data, int off, int len) {
        int samples = 0;
        for (int s = 0; s < streams; s++) {
            if (len <= 0) {
                return OpusPacketDecoder.OPUS_INVALID_PACKET;
            }
            int count = OpusPacketDecoder.parse(data, off, len, s != streams - 1, sizes, parsed);
            if (count < 0) {
                return count;
            }
            int tmpSamples = OpusPacketDecoder.packetSamples(data, off, parsed[1]);
            if (s != 0 && samples != tmpSamples) {
                return OpusPacketDecoder.OPUS_INVALID_PACKET;
            }
            samples = tmpSamples;
            off += parsed[1];
            len -= parsed[1];
        }
        return samples;
    }

    /**
     * Decodes one packet into {@code pcm[pcmOff ..]}, interleaved by {@link #channels}; a null or empty packet
     * conceals {@code frameSize} samples.
     *
     * @return the samples per channel, or a negative libopus error code
     */
    int decode(byte[] data, int off, int len, float[] pcm, int pcmOff, int frameSize) {
        if (frameSize <= 0) {
            return OpusPacketDecoder.OPUS_BAD_ARG;
        }
        frameSize = Math.min(frameSize, OpusPacketDecoder.MAX_FRAME_SIZE);
        boolean doPlc = data == null || len == 0;
        if (len < 0) {
            return OpusPacketDecoder.OPUS_BAD_ARG;
        }
        if (!doPlc && len < 2 * streams - 1) {
            return OpusPacketDecoder.OPUS_INVALID_PACKET;
        }
        if (!doPlc) {
            int ret = validate(data, off, len);
            if (ret < 0) {
                return ret;
            } else if (ret > frameSize) {
                return OpusPacketDecoder.OPUS_BUFFER_TOO_SMALL;
            }
        }
        if (direct) {
            // The one-stream case below with the copy left out: the same samples in the same places.
            packetOffset[0] = 0;
            return decoders[0].decode(doPlc ? null : data, off, doPlc ? 0 : len, pcm, pcmOff, frameSize, false,
                    packetOffset);
        }
        for (int s = 0; s < streams; s++) {
            OpusPacketDecoder dec = decoders[s];
            if (!doPlc && len <= 0) {
                return OpusPacketDecoder.OPUS_INTERNAL_ERROR;
            }
            packetOffset[0] = 0;
            int ret = dec.decode(doPlc ? null : data, off, doPlc ? 0 : len, buf, 0, frameSize, s != streams - 1,
                    packetOffset);
            if (!doPlc) {
                off += packetOffset[0];
                len -= packetOffset[0];
            }
            if (ret <= 0) {
                return ret;
            }
            frameSize = ret;
            if (s < coupled) {
                // Copy "left" audio to the channel(s) where it belongs, then "right".
                for (int c = 0; c < channels; c++) {
                    if (mapping[c] == s * 2) {
                        copyChannel(pcm, pcmOff, c, buf, 0, 2, frameSize);
                    }
                }
                for (int c = 0; c < channels; c++) {
                    if (mapping[c] == s * 2 + 1) {
                        copyChannel(pcm, pcmOff, c, buf, 1, 2, frameSize);
                    }
                }
            } else {
                // Copy audio to the channel(s) where it belongs.
                for (int c = 0; c < channels; c++) {
                    if (mapping[c] == s + coupled) {
                        copyChannel(pcm, pcmOff, c, buf, 0, 1, frameSize);
                    }
                }
            }
        }
        // Handle muted channels.
        for (int c = 0; c < channels; c++) {
            if (mapping[c] == 255) {
                for (int i = 0; i < frameSize; i++) {
                    pcm[pcmOff + i * channels + c] = 0;
                }
            }
        }
        return frameSize;
    }

    private void copyChannel(float[] dst, int dstOff, int dstChannel, float[] src, int srcOff, int srcStride,
                             int frameSize) {
        for (int i = 0; i < frameSize; i++) {
            dst[dstOff + i * channels + dstChannel] = src[srcOff + i * srcStride];
        }
    }
}

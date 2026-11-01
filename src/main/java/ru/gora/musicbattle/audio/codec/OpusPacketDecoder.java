/*
 * Java port of part of the libopus 1.6.1 decoder. libopus is Copyright (c) Xiph.Org Foundation, Skype Limited, CSIRO,
 * Jean-Marc Valin, Timothy B. Terriberry, Gregory Maxwell, Mark Borgerding, Koen Vos, Arm Limited and the other
 * contributors to libopus, under the BSD-3-Clause licence (its CELT, src/ and include/ files under the two-clause form
 * of it). The copyright notices of the files this follows and the full licence text are in NOTICE.md, under
 * "libopus". QwU World's MIT licence does not cover this file.
 */
package ru.gora.musicbattle.audio.codec;

import java.util.Arrays;

/**
 * Decodes the packets of one Opus elementary stream (mono or stereo) at 48 kHz into interleaved floats: the
 * OpusDecoder of libopus 1.6.1 src/opus_decoder.c (opus_decode_native, opus_decode_frame, smooth_fade) with the
 * packet parsing of src/opus.c (opus_packet_parse_impl). It chooses between SILK, CELT and hybrid per frame, runs
 * the transitions between them (redundant CELT frames, concealed fades), applies the decoder gain, and conceals a
 * missing packet.
 *
 * <p>Like opus_decode_float, it does not soft-clip: samples may leave [-1, 1] slightly, and the caller decides what
 * to do with them. Packet padding is skipped; the extensions it may carry (DRED, QEXT) are ignored, as a default
 * libopus build does.
 */
final class OpusPacketDecoder {
    static final int MODE_SILK_ONLY = 1000;
    static final int MODE_HYBRID = 1001;
    static final int MODE_CELT_ONLY = 1002;
    static final int BANDWIDTH_NARROWBAND = 1101;
    static final int BANDWIDTH_MEDIUMBAND = 1102;
    static final int BANDWIDTH_WIDEBAND = 1103;
    static final int BANDWIDTH_SUPERWIDEBAND = 1104;
    static final int BANDWIDTH_FULLBAND = 1105;
    static final int OPUS_BAD_ARG = -1;
    static final int OPUS_BUFFER_TOO_SMALL = -2;
    static final int OPUS_INTERNAL_ERROR = -3;
    static final int OPUS_INVALID_PACKET = -4;
    /** The most samples one packet can hold: 120 ms at 48 kHz. */
    static final int MAX_FRAME_SIZE = 5760;
    private static final int F20 = 960;
    private static final int F10 = 480;
    private static final int F5 = 240;
    private static final int F2_5 = 120;
    private static final byte[] SILENCE = {(byte) 0xFF, (byte) 0xFF};

    final int channels;
    private final OpusSilkDecoder silk;
    private final OpusCeltDecoder celt;
    /** The decoder gain in Q8 dB (OPUS_SET_GAIN). */
    int decodeGain;

    // Everything below is cleared by reset().
    private int streamChannels;
    private int bandwidth;
    private int mode;
    private int prevMode;
    private int frameSize;
    private boolean prevRedundancy;
    int lastPacketDuration;
    int rangeFinal;

    private final OpusRangeDecoder dec = new OpusRangeDecoder();
    private final float[] pcmTransition;
    private final float[] pcmSilk;
    private final float[] redundantAudio;
    private final short[] sizes = new short[48];
    private final int[] parsed = new int[3];

    OpusPacketDecoder(int channels) {
        if (channels != 1 && channels != 2) {
            throw new IllegalArgumentException("An Opus stream has one or two channels, not " + channels);
        }
        this.channels = channels;
        this.silk = new OpusSilkDecoder(channels);
        this.celt = new OpusCeltDecoder(channels);
        this.pcmTransition = new float[F5 * channels];
        this.pcmSilk = new float[F10 * channels];
        this.redundantAudio = new float[F5 * channels];
        reset();
        prevMode = 0;
        frameSize = 48000 / 400;
    }

    /** OPUS_RESET_STATE. */
    void reset() {
        streamChannels = channels;
        bandwidth = 0;
        mode = 0;
        prevMode = 0;
        prevRedundancy = false;
        lastPacketDuration = 0;
        rangeFinal = 0;
        celt.reset();
        silk.reset();
        frameSize = 48000 / 400;
    }

    static int packetMode(int toc) {
        if ((toc & 0x80) != 0) {
            return MODE_CELT_ONLY;
        } else if ((toc & 0x60) == 0x60) {
            return MODE_HYBRID;
        }
        return MODE_SILK_ONLY;
    }

    static int packetBandwidth(int toc) {
        int bw;
        if ((toc & 0x80) != 0) {
            bw = BANDWIDTH_MEDIUMBAND + ((toc >> 5) & 0x3);
            if (bw == BANDWIDTH_MEDIUMBAND) {
                bw = BANDWIDTH_NARROWBAND;
            }
        } else if ((toc & 0x60) == 0x60) {
            bw = (toc & 0x10) != 0 ? BANDWIDTH_FULLBAND : BANDWIDTH_SUPERWIDEBAND;
        } else {
            bw = BANDWIDTH_NARROWBAND + ((toc >> 5) & 0x3);
        }
        return bw;
    }

    /** opus_packet_get_samples_per_frame at 48 kHz. */
    static int samplesPerFrame(int toc) {
        int audiosize;
        if ((toc & 0x80) != 0) {
            audiosize = (toc >> 3) & 0x3;
            audiosize = (48000 << audiosize) / 400;
        } else if ((toc & 0x60) == 0x60) {
            audiosize = (toc & 0x08) != 0 ? 48000 / 50 : 48000 / 100;
        } else {
            audiosize = (toc >> 3) & 0x3;
            if (audiosize == 3) {
                audiosize = 48000 * 60 / 1000;
            } else {
                audiosize = (48000 << audiosize) / 100;
            }
        }
        return audiosize;
    }

    /**
     * opus_packet_get_nb_samples at 48 kHz: the duration of a packet from its first bytes.
     *
     * @return the samples per channel, or {@link #OPUS_INVALID_PACKET}
     */
    static int packetSamples(byte[] packet, int off, int len) {
        if (len < 1) {
            return OPUS_BAD_ARG;
        }
        int toc = packet[off] & 0xFF;
        int count;
        if ((toc & 0x3) == 0) {
            count = 1;
        } else if ((toc & 0x3) != 3) {
            count = 2;
        } else if (len < 2) {
            return OPUS_INVALID_PACKET;
        } else {
            count = packet[off + 1] & 0x3F;
        }
        int samples = count * samplesPerFrame(toc);
        // Can't have more than 120 ms.
        if (samples * 25 > 48000 * 3) {
            return OPUS_INVALID_PACKET;
        }
        return samples;
    }

    private static int parseSize(byte[] data, int off, int len, short[] size, int index) {
        if (len < 1) {
            size[index] = -1;
            return -1;
        } else if ((data[off] & 0xFF) < 252) {
            size[index] = (short) (data[off] & 0xFF);
            return 1;
        } else if (len < 2) {
            size[index] = -1;
            return -1;
        }
        size[index] = (short) (4 * (data[off + 1] & 0xFF) + (data[off] & 0xFF));
        return 2;
    }

    /**
     * opus_packet_parse_impl: splits a packet into its frames. {@code out} receives the payload offset (relative to
     * {@code off}), the packet length including padding (for self-delimited multistream parsing) and the TOC.
     *
     * @return the number of frames, or {@link #OPUS_INVALID_PACKET}
     */
    static int parse(byte[] data, int off, int len, boolean selfDelimited, short[] size, int[] out) {
        if (len < 0) {
            return OPUS_BAD_ARG;
        }
        if (len == 0) {
            return OPUS_INVALID_PACKET;
        }
        int data0 = off;
        int framesize = samplesPerFrame(data[off] & 0xFF);
        boolean cbr = false;
        int toc = data[off++] & 0xFF;
        len--;
        int lastSize = len;
        int count;
        int pad = 0;
        int bytes;
        switch (toc & 0x3) {
            case 0 -> count = 1;
            case 1 -> {
                // Two CBR frames.
                count = 2;
                cbr = true;
                if (!selfDelimited) {
                    if ((len & 0x1) != 0) {
                        return OPUS_INVALID_PACKET;
                    }
                    lastSize = len / 2;
                    // If last_size doesn't fit in size[0], we'll catch it later.
                    size[0] = (short) lastSize;
                }
            }
            case 2 -> {
                // Two VBR frames.
                count = 2;
                bytes = parseSize(data, off, len, size, 0);
                len -= bytes;
                if (size[0] < 0 || size[0] > len) {
                    return OPUS_INVALID_PACKET;
                }
                off += bytes;
                lastSize = len - size[0];
            }
            default -> {
                // Multiple CBR/VBR frames (from 0 to 120 ms).
                if (len < 1) {
                    return OPUS_INVALID_PACKET;
                }
                // Number of frames encoded in bits 0 to 5.
                int ch = data[off++] & 0xFF;
                count = ch & 0x3F;
                if (count <= 0 || framesize * count > 5760) {
                    return OPUS_INVALID_PACKET;
                }
                len--;
                // Padding flag is bit 6.
                if ((ch & 0x40) != 0) {
                    int p;
                    do {
                        if (len <= 0) {
                            return OPUS_INVALID_PACKET;
                        }
                        p = data[off++] & 0xFF;
                        len--;
                        int tmp = p == 255 ? 254 : p;
                        len -= tmp;
                        pad += tmp;
                    } while (p == 255);
                }
                if (len < 0) {
                    return OPUS_INVALID_PACKET;
                }
                // VBR flag is bit 7.
                cbr = (ch & 0x80) == 0;
                if (!cbr) {
                    // VBR case.
                    lastSize = len;
                    for (int i = 0; i < count - 1; i++) {
                        bytes = parseSize(data, off, len, size, i);
                        len -= bytes;
                        if (size[i] < 0 || size[i] > len) {
                            return OPUS_INVALID_PACKET;
                        }
                        off += bytes;
                        lastSize -= bytes + size[i];
                    }
                    if (lastSize < 0) {
                        return OPUS_INVALID_PACKET;
                    }
                } else if (!selfDelimited) {
                    // CBR case.
                    lastSize = len / count;
                    if (lastSize * count != len) {
                        return OPUS_INVALID_PACKET;
                    }
                    for (int i = 0; i < count - 1; i++) {
                        size[i] = (short) lastSize;
                    }
                }
            }
        }
        // Self-delimited framing has an extra size for the last frame.
        if (selfDelimited) {
            bytes = parseSize(data, off, len, size, count - 1);
            len -= bytes;
            if (size[count - 1] < 0 || size[count - 1] > len) {
                return OPUS_INVALID_PACKET;
            }
            off += bytes;
            // For CBR packets, apply the size to all the frames.
            if (cbr) {
                if (size[count - 1] * count > len) {
                    return OPUS_INVALID_PACKET;
                }
                for (int i = 0; i < count - 1; i++) {
                    size[i] = size[count - 1];
                }
            } else if (bytes + size[count - 1] > lastSize) {
                return OPUS_INVALID_PACKET;
            }
        } else {
            // Because it's not encoded explicitly, it's possible the size of the last packet (or all the packets, for
            // the CBR case) is larger than 1275. Reject them here.
            if (lastSize > 1275) {
                return OPUS_INVALID_PACKET;
            }
            size[count - 1] = (short) lastSize;
        }
        out[0] = off - data0;
        int end = off;
        for (int i = 0; i < count; i++) {
            end += size[i];
        }
        out[1] = pad + (end - data0);
        out[2] = toc;
        return count;
    }

    /**
     * opus_decode_native without FEC: decodes one packet (or conceals one when {@code data} is null) into
     * {@code pcm[pcmOff ..]}, interleaved by {@link #channels}.
     *
     * @param frameSizeMax the room in pcm, in samples per channel; for concealment, the duration to conceal
     * @param selfDelimited true for every stream of a multistream packet except the last
     * @param packetOffset receives the bytes this stream's packet takes, for multistream parsing
     * @return the samples per channel, or a negative libopus error code
     */
    int decode(byte[] data, int off, int len, float[] pcm, int pcmOff, int frameSizeMax, boolean selfDelimited,
               int[] packetOffset) {
        if ((len == 0 || data == null) && frameSizeMax % (48000 / 400) != 0) {
            return OPUS_BAD_ARG;
        }
        if (len == 0 || data == null) {
            int pcmCount = 0;
            do {
                int ret = decodeFrame(null, 0, 0, pcm, pcmOff + pcmCount * channels, frameSizeMax - pcmCount);
                if (ret < 0) {
                    return ret;
                }
                pcmCount += ret;
            } while (pcmCount < frameSizeMax);
            lastPacketDuration = pcmCount;
            return pcmCount;
        } else if (len < 0) {
            return OPUS_BAD_ARG;
        }
        int toc = data[off] & 0xFF;
        int packetMode = packetMode(toc);
        int packetBandwidth = packetBandwidth(toc);
        int packetFrameSize = samplesPerFrame(toc);
        int packetStreamChannels = (toc & 0x4) != 0 ? 2 : 1;
        int count = parse(data, off, len, selfDelimited, sizes, parsed);
        if (count < 0) {
            return count;
        }
        if (packetOffset != null) {
            packetOffset[0] = parsed[1];
        }
        int p = off + parsed[0];
        if (count * packetFrameSize > frameSizeMax) {
            return OPUS_BUFFER_TOO_SMALL;
        }
        // Update the state as the last step to avoid updating it on an invalid packet.
        mode = packetMode;
        bandwidth = packetBandwidth;
        frameSize = packetFrameSize;
        streamChannels = packetStreamChannels;
        int nbSamples = 0;
        for (int i = 0; i < count; i++) {
            int ret = decodeFrame(data, p, sizes[i], pcm, pcmOff + nbSamples * channels, frameSizeMax - nbSamples);
            if (ret < 0) {
                return ret;
            }
            p += sizes[i];
            nbSamples += ret;
        }
        lastPacketDuration = nbSamples;
        return nbSamples;
    }

    /** opus_decode_frame without FEC: one 2.5 to 60 ms frame, or a concealed one when data is null. */
    private int decodeFrame(byte[] data, int off, int len, float[] pcm, int pcmOff, int frameSizeMax) {
        int frameSizeIn = frameSizeMax;
        if (frameSizeIn < F2_5) {
            return OPUS_BUFFER_TOO_SMALL;
        }
        // Limit frame_size to avoid excessive stack allocations.
        frameSizeIn = Math.min(frameSizeIn, 48000 / 25 * 3);
        // Payloads of 1 (2 including ToC) or 0 trigger the PLC/DTX.
        if (len <= 1) {
            data = null;
            // In that case, don't conceal more than what the ToC says.
            frameSizeIn = Math.min(frameSizeIn, frameSize);
        }
        int audiosize;
        int curMode;
        int curBandwidth;
        if (data != null) {
            audiosize = frameSize;
            curMode = mode;
            curBandwidth = bandwidth;
            dec.init(data, off, len);
        } else {
            audiosize = frameSizeIn;
            // Run PLC using last used mode (CELT if we ended with CELT redundancy).
            curMode = prevRedundancy ? MODE_CELT_ONLY : prevMode;
            curBandwidth = 0;
            if (curMode == 0) {
                // If we haven't got any packet yet, all we can do is return zeros.
                Arrays.fill(pcm, pcmOff, pcmOff + audiosize * channels, 0f);
                return audiosize;
            }
            // Avoids trying to run the PLC on sizes other than 2.5 (CELT), 5 (CELT), 10, or 20 (e.g. 12.5 or
            // 30 ms).
            if (audiosize > F20) {
                do {
                    int ret = decodeFrame(null, 0, 0, pcm, pcmOff, Math.min(audiosize, F20));
                    if (ret < 0) {
                        return ret;
                    }
                    pcmOff += ret * channels;
                    audiosize -= ret;
                } while (audiosize > 0);
                return frameSizeIn;
            } else if (audiosize < F20) {
                if (audiosize > F10) {
                    audiosize = F10;
                } else if (curMode != MODE_SILK_ONLY && audiosize > F5 && audiosize < F10) {
                    audiosize = F5;
                }
            }
        }

        // CELT adds on top of SILK in hybrid mode.
        boolean celtAccum = curMode != MODE_CELT_ONLY;
        boolean transition = false;
        if (data != null && prevMode > 0 && ((curMode == MODE_CELT_ONLY && prevMode != MODE_CELT_ONLY && !prevRedundancy)
                || (curMode != MODE_CELT_ONLY && prevMode == MODE_CELT_ONLY))) {
            transition = true;
        }
        if (transition && curMode == MODE_CELT_ONLY) {
            decodeFrame(null, 0, 0, pcmTransition, 0, Math.min(F5, audiosize));
        }
        if (audiosize > frameSizeIn) {
            return OPUS_BAD_ARG;
        }
        int frameSz = audiosize;

        // SILK processing.
        if (curMode != MODE_CELT_ONLY) {
            boolean pcmTooSmall = frameSz < F10;
            float[] out = pcmTooSmall ? pcmSilk : pcm;
            int outOff = pcmTooSmall ? 0 : pcmOff;
            if (prevMode == MODE_CELT_ONLY) {
                silk.reset();
            }
            // The SILK PLC cannot produce frames of less than 10 ms.
            silk.payloadSizeMs = Math.max(10, 1000 * audiosize / 48000);
            if (data != null) {
                silk.nChannelsInternal = streamChannels;
                if (curMode == MODE_SILK_ONLY) {
                    if (curBandwidth == BANDWIDTH_NARROWBAND) {
                        silk.internalSampleRate = 8000;
                    } else if (curBandwidth == BANDWIDTH_MEDIUMBAND) {
                        silk.internalSampleRate = 12000;
                    } else {
                        silk.internalSampleRate = 16000;
                    }
                } else {
                    // Hybrid mode.
                    silk.internalSampleRate = 16000;
                }
            }
            int lostFlag = data == null ? OpusSilkChannel.FLAG_PACKET_LOST : OpusSilkChannel.FLAG_DECODE_NORMAL;
            int decodedSamples = 0;
            do {
                boolean firstFrame = decodedSamples == 0;
                int silkFrameSize;
                try {
                    silkFrameSize = silk.decode(lostFlag, firstFrame, dec, out, outOff);
                } catch (IllegalStateException e) {
                    if (lostFlag == OpusSilkChannel.FLAG_DECODE_NORMAL) {
                        return OPUS_INTERNAL_ERROR;
                    }
                    // PLC failure should not be fatal.
                    silkFrameSize = frameSz;
                    Arrays.fill(out, outOff, outOff + frameSz * channels, 0f);
                }
                outOff += silkFrameSize * channels;
                decodedSamples += silkFrameSize;
            } while (decodedSamples < frameSz);
            if (pcmTooSmall) {
                System.arraycopy(pcmSilk, 0, pcm, pcmOff, frameSz * channels);
            }
        }

        boolean redundancy = false;
        boolean celtToSilk = false;
        int redundancyBytes = 0;
        if (curMode != MODE_CELT_ONLY && data != null
                && dec.tell() + 17 + 20 * (curMode == MODE_HYBRID ? 1 : 0) <= 8 * len) {
            // Check if we have a redundant 0-8 kHz band.
            redundancy = curMode != MODE_HYBRID || dec.decodeBitLogp(12);
            if (redundancy) {
                celtToSilk = dec.decodeBitLogp(1);
                // redundancy_bytes will be at least two, in the non-hybrid case due to the ec_tell() check above.
                redundancyBytes = curMode == MODE_HYBRID ? (int) dec.decodeUint(256) + 2
                        : len - ((dec.tell() + 7) >> 3);
                len -= redundancyBytes;
                // This is a sanity check. It should never happen for a valid packet, so the exact behaviour is not
                // normative.
                if (len * 8 < dec.tell()) {
                    len = 0;
                    redundancyBytes = 0;
                    redundancy = false;
                }
                // Shrink decoder because of raw bits.
                dec.storage -= redundancyBytes;
            }
        }
        int startBand = curMode != MODE_CELT_ONLY ? 17 : 0;
        if (redundancy) {
            transition = false;
        }
        if (transition && curMode != MODE_CELT_ONLY) {
            decodeFrame(null, 0, 0, pcmTransition, 0, Math.min(F5, audiosize));
        }

        if (curBandwidth != 0) {
            int endband = switch (curBandwidth) {
                case BANDWIDTH_NARROWBAND -> 13;
                case BANDWIDTH_MEDIUMBAND, BANDWIDTH_WIDEBAND -> 17;
                case BANDWIDTH_SUPERWIDEBAND -> 19;
                default -> 21;
            };
            celt.end = endband;
        }
        celt.streamChannels = streamChannels;

        int redundantRng = 0;
        // 5 ms redundant frame for CELT->SILK.
        if (redundancy && celtToSilk) {
            // If the previous frame did not use CELT (the first redundancy frame in a transition from SILK may have
            // been lost) then the CELT decoder is stale at this point and the redundancy audio is not useful,
            // however the final range is still needed (for testing), so the redundancy is always decoded but the
            // decoded audio may not be used.
            celt.start = 0;
            celt.decode(data, off + len, redundancyBytes, redundantAudio, 0, F5, null, false);
            redundantRng = celt.rng;
        }
        // MUST be after PLC.
        celt.start = startBand;

        int celtRet = 0;
        if (curMode != MODE_SILK_ONLY) {
            int celtFrameSize = Math.min(F20, frameSz);
            // Make sure to discard any previous CELT state.
            if (curMode != prevMode && prevMode > 0 && !prevRedundancy) {
                celt.reset();
            }
            // Decode CELT.
            celtRet = celt.decode(data, off, len, pcm, pcmOff, celtFrameSize, data == null ? null : dec, celtAccum);
            rangeFinal = celt.rng;
        } else {
            if (!celtAccum) {
                Arrays.fill(pcm, pcmOff, pcmOff + frameSz * channels, 0f);
            }
            // For hybrid -> SILK transitions, we let the CELT MDCT do a fade-out by decoding a silence frame.
            if (prevMode == MODE_HYBRID && !(redundancy && celtToSilk && prevRedundancy)) {
                celt.start = 0;
                celt.decode(SILENCE, 0, 2, pcm, pcmOff, F2_5, null, celtAccum);
            }
            rangeFinal = (int) dec.rng;
        }

        // 5 ms redundant frame for SILK->CELT.
        if (redundancy && !celtToSilk) {
            celt.reset();
            celt.start = 0;
            celt.decode(data, off + len, redundancyBytes, redundantAudio, 0, F5, null, false);
            redundantRng = celt.rng;
            smoothFade(pcm, pcmOff + channels * (frameSz - F2_5), redundantAudio, channels * F2_5, pcm,
                    pcmOff + channels * (frameSz - F2_5), F2_5);
        }
        // 5 ms redundant frame for CELT->SILK; ignore if the previous frame did not use CELT (the first redundancy
        // frame in a transition from SILK may have been lost).
        if (redundancy && celtToSilk && (prevMode != MODE_SILK_ONLY || prevRedundancy)) {
            for (int c = 0; c < channels; c++) {
                for (int i = 0; i < F2_5; i++) {
                    pcm[pcmOff + channels * i + c] = redundantAudio[channels * i + c];
                }
            }
            smoothFade(redundantAudio, channels * F2_5, pcm, pcmOff + channels * F2_5, pcm, pcmOff + channels * F2_5,
                    F2_5);
        }
        if (transition) {
            if (audiosize >= F5) {
                System.arraycopy(pcmTransition, 0, pcm, pcmOff, channels * F2_5);
                smoothFade(pcmTransition, channels * F2_5, pcm, pcmOff + channels * F2_5, pcm,
                        pcmOff + channels * F2_5, F2_5);
            } else {
                // Not enough time to do a clean transition, but we do it anyway. This will not preserve amplitude
                // perfectly and may introduce a bit of temporal aliasing, but it shouldn't be too bad and that's
                // pretty much the best we can do.
                smoothFade(pcmTransition, 0, pcm, pcmOff, pcm, pcmOff, F2_5);
            }
        }

        if (decodeGain != 0) {
            float gain = OpusCeltDecoder.celtExp2(6.48814081e-4f * decodeGain);
            for (int i = 0; i < frameSz * channels; i++) {
                pcm[pcmOff + i] = pcm[pcmOff + i] * gain;
            }
        }

        if (len <= 1) {
            rangeFinal = 0;
        } else {
            rangeFinal ^= redundantRng;
        }
        prevMode = curMode;
        prevRedundancy = redundancy && !celtToSilk;
        return celtRet < 0 ? celtRet : audiosize;
    }

    /** smooth_fade: cross-fades in1 into in2 over {@code overlap} samples with the squared CELT window. */
    private void smoothFade(float[] in1, int in1Off, float[] in2, int in2Off, float[] out, int outOff, int overlap) {
        float[] window = OpusCeltTables.WINDOW;
        for (int c = 0; c < channels; c++) {
            for (int i = 0; i < overlap; i++) {
                float w = window[i];
                w = w * w;
                out[outOff + i * channels + c] = w * in2[in2Off + i * channels + c]
                        + (1.0f - w) * in1[in1Off + i * channels + c];
            }
        }
    }
}

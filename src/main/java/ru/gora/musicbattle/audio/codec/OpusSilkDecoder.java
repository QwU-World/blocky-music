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
 * The SILK decoder of one Opus stream (mono or stereo), ported from libopus 1.6.1 silk/dec_API.c (silk_Decode,
 * silk_ResetDecoder), silk/stereo_decode_pred.c and silk/stereo_MS_to_LR.c: per-packet VAD and LBRR flags, the
 * low-bitrate redundancy that normal decoding skips over, mid/side stereo with its predictor, and resampling of each
 * 10 or 20 ms frame to 48 kHz as floats.
 */
final class OpusSilkDecoder {
    private static final int[] PREV_PITCH_MULT = {6, 4, 3};

    private final OpusSilkChannel[] channel = {new OpusSilkChannel(), new OpusSilkChannel()};
    // stereo_dec_state
    private final short[] predPrevQ13 = new short[2];
    private final short[] sMid = new short[2];
    private final short[] sSide = new short[2];
    private int nChannelsApiPrev;
    private int nChannelsInternalPrev;
    private int prevDecodeOnlyMiddle;

    // silk_DecControlStruct, set by the Opus layer before each call.
    int nChannelsApi;
    int nChannelsInternal;
    int internalSampleRate;
    int payloadSizeMs;
    int prevPitchLag;

    private final int[] msPredQ13 = new int[2];
    private final short[][] out1 = new short[2][OpusSilkChannel.MAX_FRAME_LENGTH + 2];
    private final short[] out2 = new short[960];
    private final short[] lbrrPulses = new short[OpusSilkChannel.MAX_FRAME_LENGTH];
    private final int[] lbrrSum = new int[OpusSilkChannel.MAX_FRAME_LENGTH / 16];
    private final int[] lbrrShifts = new int[OpusSilkChannel.MAX_FRAME_LENGTH / 16];
    private final short[] shell3 = new short[2];
    private final short[] shell2 = new short[4];
    private final short[] shell1 = new short[8];

    OpusSilkDecoder(int apiChannels) {
        nChannelsApi = apiChannels;
        nChannelsInternal = apiChannels;
    }

    /** silk_ResetDecoder. */
    void reset() {
        channel[0].reset();
        channel[1].reset();
        Arrays.fill(predPrevQ13, (short) 0);
        Arrays.fill(sMid, (short) 0);
        Arrays.fill(sSide, (short) 0);
        prevDecodeOnlyMiddle = 0;
    }

    /**
     * silk_Decode: decodes (or conceals) one SILK frame into {@code out[outOff ..]} at 48 kHz, interleaved by
     * {@link #nChannelsApi}.
     *
     * @return the number of 48 kHz samples written per channel
     */
    int decode(int lostFlag, boolean newPacket, OpusRangeDecoder dec, float[] out, int outOff) {
        OpusSilkChannel ch0 = channel[0];
        OpusSilkChannel ch1 = channel[1];
        int decodeOnlyMiddle = 0;
        // Test if first frame in payload.
        if (newPacket) {
            for (int n = 0; n < nChannelsInternal; n++) {
                channel[n].nFramesDecoded = 0;
            }
        }
        // If mono -> stereo transition in bitstream: init state of second channel.
        if (nChannelsInternal > nChannelsInternalPrev) {
            ch1.reset();
        }
        boolean stereoToMono = nChannelsInternal == 1 && nChannelsInternalPrev == 2
                && internalSampleRate == 1000 * ch0.fsKHz;
        if (ch0.nFramesDecoded == 0) {
            for (int n = 0; n < nChannelsInternal; n++) {
                OpusSilkChannel c = channel[n];
                switch (payloadSizeMs) {
                    case 0, 10 -> {
                        // A payload size of 0 means packet loss: use 10 ms.
                        c.nFramesPerPacket = 1;
                        c.nbSubfr = 2;
                    }
                    case 20 -> {
                        c.nFramesPerPacket = 1;
                        c.nbSubfr = 4;
                    }
                    case 40 -> {
                        c.nFramesPerPacket = 2;
                        c.nbSubfr = 4;
                    }
                    case 60 -> {
                        c.nFramesPerPacket = 3;
                        c.nbSubfr = 4;
                    }
                    default -> throw new IllegalStateException("Invalid SILK frame size " + payloadSizeMs);
                }
                int fsKHzDec = (internalSampleRate >> 10) + 1;
                if (fsKHzDec != 8 && fsKHzDec != 12 && fsKHzDec != 16) {
                    throw new IllegalStateException("Invalid SILK sampling rate " + internalSampleRate);
                }
                c.setFs(fsKHzDec, 48000);
            }
        }
        if (nChannelsApi == 2 && nChannelsInternal == 2 && (nChannelsApiPrev == 1 || nChannelsInternalPrev == 1)) {
            Arrays.fill(predPrevQ13, (short) 0);
            Arrays.fill(sSide, (short) 0);
            ch1.resampler.copyFrom(ch0.resampler);
        }
        nChannelsApiPrev = nChannelsApi;
        nChannelsInternalPrev = nChannelsInternal;

        if (lostFlag != OpusSilkChannel.FLAG_PACKET_LOST && ch0.nFramesDecoded == 0) {
            // First decoder call for this payload: decode VAD flags and LBRR flag.
            for (int n = 0; n < nChannelsInternal; n++) {
                OpusSilkChannel c = channel[n];
                for (int i = 0; i < c.nFramesPerPacket; i++) {
                    c.vadFlags[i] = dec.decodeBitLogp(1) ? 1 : 0;
                }
                c.lbrrFlag = dec.decodeBitLogp(1) ? 1 : 0;
            }
            // Decode LBRR flags.
            for (int n = 0; n < nChannelsInternal; n++) {
                OpusSilkChannel c = channel[n];
                Arrays.fill(c.lbrrFlags, 0);
                if (c.lbrrFlag != 0) {
                    if (c.nFramesPerPacket == 1) {
                        c.lbrrFlags[0] = 1;
                    } else {
                        short[] icdf = c.nFramesPerPacket == 2 ? OpusSilkTables.LBRR_FLAGS_2_ICDF
                                : OpusSilkTables.LBRR_FLAGS_3_ICDF;
                        int lbrrSymbol = dec.decodeIcdf(icdf, 8) + 1;
                        for (int i = 0; i < c.nFramesPerPacket; i++) {
                            c.lbrrFlags[i] = (lbrrSymbol >> i) & 1;
                        }
                    }
                }
            }
            if (lostFlag == OpusSilkChannel.FLAG_DECODE_NORMAL) {
                // Regular decoding: skip all LBRR data.
                for (int i = 0; i < ch0.nFramesPerPacket; i++) {
                    for (int n = 0; n < nChannelsInternal; n++) {
                        OpusSilkChannel c = channel[n];
                        if (c.lbrrFlags[i] != 0) {
                            if (nChannelsInternal == 2 && n == 0) {
                                stereoDecodePred(dec, msPredQ13);
                                if (ch1.lbrrFlags[i] == 0) {
                                    decodeOnlyMiddle = dec.decodeIcdf(OpusSilkTables.STEREO_ONLY_CODE_MID_ICDF, 8);
                                }
                            }
                            // Use conditional coding if previous frame available.
                            int condCoding = i > 0 && c.lbrrFlags[i - 1] != 0 ? OpusSilkChannel.CODE_CONDITIONALLY
                                    : OpusSilkChannel.CODE_INDEPENDENTLY;
                            c.decodeIndices(dec, i, true, condCoding);
                            OpusSilkChannel.decodePulses(dec, lbrrPulses, c.signalType, c.quantOffsetType,
                                    c.frameLength, lbrrSum, lbrrShifts, shell3, shell2, shell1);
                        }
                    }
                }
            }
        }

        // Get MS predictor index.
        if (nChannelsInternal == 2) {
            if (lostFlag == OpusSilkChannel.FLAG_DECODE_NORMAL
                    || (lostFlag == OpusSilkChannel.FLAG_DECODE_LBRR && ch0.lbrrFlags[ch0.nFramesDecoded] == 1)) {
                stereoDecodePred(dec, msPredQ13);
                // For LBRR data, decode mid-only flag only if side-channel's LBRR flag is false.
                if ((lostFlag == OpusSilkChannel.FLAG_DECODE_NORMAL && ch1.vadFlags[ch0.nFramesDecoded] == 0)
                        || (lostFlag == OpusSilkChannel.FLAG_DECODE_LBRR && ch1.lbrrFlags[ch0.nFramesDecoded] == 0)) {
                    decodeOnlyMiddle = dec.decodeIcdf(OpusSilkTables.STEREO_ONLY_CODE_MID_ICDF, 8);
                } else {
                    decodeOnlyMiddle = 0;
                }
            } else {
                msPredQ13[0] = predPrevQ13[0];
                msPredQ13[1] = predPrevQ13[1];
            }
        }

        // Reset side channel decoder prediction memory for first frame with side coding.
        if (nChannelsInternal == 2 && decodeOnlyMiddle == 0 && prevDecodeOnlyMiddle == 1) {
            Arrays.fill(ch1.outBuf, (short) 0);
            Arrays.fill(ch1.sLpcQ14Buf, 0);
            ch1.lagPrev = 100;
            ch1.lastGainIndex = 10;
            ch1.prevSignalType = OpusSilkChannel.TYPE_NO_VOICE_ACTIVITY;
            ch1.firstFrameAfterReset = true;
        }

        boolean hasSide;
        if (lostFlag == OpusSilkChannel.FLAG_DECODE_NORMAL) {
            hasSide = decodeOnlyMiddle == 0;
        } else {
            hasSide = prevDecodeOnlyMiddle == 0 || (nChannelsInternal == 2
                    && lostFlag == OpusSilkChannel.FLAG_DECODE_LBRR && ch1.lbrrFlags[ch1.nFramesDecoded] == 1);
        }

        // Call decoder for one frame.
        int nSamplesOutDec = 0;
        for (int n = 0; n < nChannelsInternal; n++) {
            OpusSilkChannel c = channel[n];
            if (n == 0 || hasSide) {
                int frameIndex = ch0.nFramesDecoded - n;
                int condCoding;
                // Use independent coding if no previous frame available.
                if (frameIndex <= 0) {
                    condCoding = OpusSilkChannel.CODE_INDEPENDENTLY;
                } else if (lostFlag == OpusSilkChannel.FLAG_DECODE_LBRR) {
                    condCoding = c.lbrrFlags[frameIndex - 1] != 0 ? OpusSilkChannel.CODE_CONDITIONALLY
                            : OpusSilkChannel.CODE_INDEPENDENTLY;
                } else if (n > 0 && prevDecodeOnlyMiddle != 0) {
                    // If we skipped a side frame in this packet, we don't need LTP scaling; the LTP state is
                    // well-defined.
                    condCoding = OpusSilkChannel.CODE_INDEPENDENTLY_NO_LTP_SCALING;
                } else {
                    condCoding = OpusSilkChannel.CODE_CONDITIONALLY;
                }
                c.decodeFrame(dec, out1[n], 2, lostFlag, condCoding);
                nSamplesOutDec = c.frameLength;
            } else {
                Arrays.fill(out1[n], 2, 2 + nSamplesOutDec, (short) 0);
            }
            c.nFramesDecoded++;
        }

        if (nChannelsApi == 2 && nChannelsInternal == 2) {
            // Convert mid/side to left/right.
            stereoMsToLr(out1[0], out1[1], msPredQ13, ch0.fsKHz, nSamplesOutDec);
        } else {
            // Buffering.
            short[] o = out1[0];
            o[0] = sMid[0];
            o[1] = sMid[1];
            sMid[0] = o[nSamplesOutDec];
            sMid[1] = o[nSamplesOutDec + 1];
        }

        // Number of output samples.
        int nSamplesOut = nSamplesOutDec * 48000 / (ch0.fsKHz * 1000);
        for (int n = 0; n < Math.min(nChannelsApi, nChannelsInternal); n++) {
            // Resample decoded signal to the API sample rate.
            channel[n].resampler.resample(out2, 0, out1[n], 1, nSamplesOutDec);
            // Interleave if stereo output and stereo stream.
            if (nChannelsApi == 2) {
                for (int i = 0; i < nSamplesOut; i++) {
                    out[outOff + n + 2 * i] = out2[i] * (1 / 32768.f);
                }
            } else {
                for (int i = 0; i < nSamplesOut; i++) {
                    out[outOff + i] = out2[i] * (1 / 32768.f);
                }
            }
        }
        // Create two channel output from mono stream.
        if (nChannelsApi == 2 && nChannelsInternal == 1) {
            if (stereoToMono) {
                // Resample right channel for newly collapsed stereo just in case we weren't doing collapsing when
                // switching to mono.
                ch1.resampler.resample(out2, 0, out1[0], 1, nSamplesOutDec);
                for (int i = 0; i < nSamplesOut; i++) {
                    out[outOff + 1 + 2 * i] = out2[i] * (1 / 32768.f);
                }
            } else {
                for (int i = 0; i < nSamplesOut; i++) {
                    out[outOff + 1 + 2 * i] = out[outOff + 2 * i];
                }
            }
        }
        // Export pitch lag, measured at 48 kHz sampling rate.
        if (ch0.prevSignalType == OpusSilkChannel.TYPE_VOICED) {
            prevPitchLag = ch0.lagPrev * PREV_PITCH_MULT[(ch0.fsKHz - 8) >> 2];
        } else {
            prevPitchLag = 0;
        }
        if (lostFlag == OpusSilkChannel.FLAG_PACKET_LOST) {
            // On packet loss, remove the gain clamping to prevent having the energy "bounce back" if we lose
            // packets when the energy is going down.
            for (int i = 0; i < nChannelsInternalPrev; i++) {
                channel[i].lastGainIndex = 10;
            }
        } else {
            prevDecodeOnlyMiddle = decodeOnlyMiddle;
        }
        return nSamplesOut;
    }

    /** silk_stereo_decode_pred. */
    private static void stereoDecodePred(OpusRangeDecoder dec, int[] predQ13) {
        int[][] ix = new int[2][3];
        // Entropy decoding.
        int n = dec.decodeIcdf(OpusSilkTables.STEREO_PRED_JOINT_ICDF, 8);
        ix[0][2] = n / 5;
        ix[1][2] = n - 5 * ix[0][2];
        for (n = 0; n < 2; n++) {
            ix[n][0] = dec.decodeIcdf(OpusSilkTables.UNIFORM3_ICDF, 8);
            ix[n][1] = dec.decodeIcdf(OpusSilkTables.UNIFORM5_ICDF, 8);
        }
        // Dequantize.
        short[] quant = OpusSilkTables.STEREO_PRED_QUANT_Q13;
        int step = (int) (0.5 / 5 * (1L << 16) + 0.5);
        for (n = 0; n < 2; n++) {
            ix[n][0] += 3 * ix[n][2];
            int lowQ13 = quant[ix[n][0]];
            int stepQ13 = OpusSilkChannel.smulwb(quant[ix[n][0] + 1] - lowQ13, step);
            predQ13[n] = OpusSilkChannel.smlabb(lowQ13, stepQ13, 2 * ix[n][1] + 1);
        }
        // Subtract second from first predictor (helps when actually applying these).
        predQ13[0] -= predQ13[1];
    }

    /** silk_stereo_MS_to_LR: undoes the mid/side prediction, interpolating the predictor over 8 ms. */
    private void stereoMsToLr(short[] x1, short[] x2, int[] predQ13, int fsKHz, int frameLength) {
        // Buffering.
        x1[0] = sMid[0];
        x1[1] = sMid[1];
        x2[0] = sSide[0];
        x2[1] = sSide[1];
        sMid[0] = x1[frameLength];
        sMid[1] = x1[frameLength + 1];
        sSide[0] = x2[frameLength];
        sSide[1] = x2[frameLength + 1];
        // Interpolate predictors and add prediction to side channel.
        int pred0Q13 = predPrevQ13[0];
        int pred1Q13 = predPrevQ13[1];
        int denomQ16 = (1 << 16) / (8 * fsKHz);
        int delta0Q13 = OpusSilkChannel.rshiftRound(OpusSilkChannel.smulbb(predQ13[0] - predPrevQ13[0], denomQ16), 16);
        int delta1Q13 = OpusSilkChannel.rshiftRound(OpusSilkChannel.smulbb(predQ13[1] - predPrevQ13[1], denomQ16), 16);
        int n;
        for (n = 0; n < 8 * fsKHz; n++) {
            pred0Q13 += delta0Q13;
            pred1Q13 += delta1Q13;
            int sum = ((x1[n] + x1[n + 2]) + (x1[n + 1] << 1)) << 9;
            sum = OpusSilkChannel.smlawb(x2[n + 1] << 8, sum, pred0Q13);
            sum = OpusSilkChannel.smlawb(sum, x1[n + 1] << 11, pred1Q13);
            x2[n + 1] = (short) OpusSilkChannel.sat16(OpusSilkChannel.rshiftRound(sum, 8));
        }
        pred0Q13 = predQ13[0];
        pred1Q13 = predQ13[1];
        for (n = 8 * fsKHz; n < frameLength; n++) {
            int sum = ((x1[n] + x1[n + 2]) + (x1[n + 1] << 1)) << 9;
            sum = OpusSilkChannel.smlawb(x2[n + 1] << 8, sum, pred0Q13);
            sum = OpusSilkChannel.smlawb(sum, x1[n + 1] << 11, pred1Q13);
            x2[n + 1] = (short) OpusSilkChannel.sat16(OpusSilkChannel.rshiftRound(sum, 8));
        }
        predPrevQ13[0] = (short) predQ13[0];
        predPrevQ13[1] = (short) predQ13[1];
        // Convert to left/right signals.
        for (n = 0; n < frameLength; n++) {
            int sum = x1[n + 1] + x2[n + 1];
            int diff = x1[n + 1] - x2[n + 1];
            x1[n + 1] = (short) OpusSilkChannel.sat16(sum);
            x2[n + 1] = (short) OpusSilkChannel.sat16(diff);
        }
    }
}

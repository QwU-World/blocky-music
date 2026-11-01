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
 * The resampler that brings SILK's 8, 12 or 16 kHz output to 48 kHz, ported from libopus 1.6.1 silk/resampler.c,
 * resampler_private_IIR_FIR.c and resampler_private_up2_HQ.c (decoder side). At 48 kHz every SILK rate goes
 * through the IIR/FIR path: a 2x all-pass upsampler followed by a 12-phase fractional FIR interpolator. Its
 * fixed-point arithmetic, input delay and one-millisecond buffering are those of the reference, so the timing of
 * SILK audio matches libopus to the sample.
 */
final class OpusSilkResampler {
    private static final int ORDER_FIR_12 = 8;
    private static final int MAX_BATCH_SIZE_MS = 10;
    private static final int UP2_HQ_0_0 = 1746;
    private static final int UP2_HQ_0_1 = 14986;
    private static final int UP2_HQ_0_2 = 39083 - 65536;
    private static final int UP2_HQ_1_0 = 6854;
    private static final int UP2_HQ_1_1 = 25769;
    private static final int UP2_HQ_1_2 = 55542 - 65536;
    private static final int USE_COPY = 0;
    private static final int USE_UP2_HQ = 1;
    private static final int USE_IIR_FIR = 2;
    /** Input delay by input rate (8, 12, 16 kHz) and output rate (8, 12, 16, 24, 48, 96 kHz): delay_matrix_dec. */
    private static final int[][] DELAY_MATRIX_DEC = {
            {4, 0, 2, 0, 0, 0},
            {0, 9, 4, 7, 4, 4},
            {0, 3, 12, 7, 7, 7},
    };

    private final int[] sIir = new int[6];
    private final short[] sFir = new short[ORDER_FIR_12];
    private final short[] delayBuf = new short[96];
    private int function;
    private int batchSize;
    private int invRatioQ16;
    private int fsInKHz;
    private int fsOutKHz;
    private int inputDelay;
    /** Scratch for one batch of 2x upsampled input plus the FIR history: 2 * 10 ms at 16 kHz + 8. */
    private final short[] buf = new short[2 * 16 * MAX_BATCH_SIZE_MS + ORDER_FIR_12];

    /** The state after the memset of silk_resampler_init, before it is set up. */
    void clear() {
        Arrays.fill(sIir, 0);
        Arrays.fill(sFir, (short) 0);
        Arrays.fill(delayBuf, (short) 0);
        function = 0;
        batchSize = 0;
        invRatioQ16 = 0;
        fsInKHz = 0;
        fsOutKHz = 0;
        inputDelay = 0;
    }

    /** Copies another channel's resampler, as silk_Decode does when a mono stream turns stereo. */
    void copyFrom(OpusSilkResampler other) {
        System.arraycopy(other.sIir, 0, sIir, 0, sIir.length);
        System.arraycopy(other.sFir, 0, sFir, 0, sFir.length);
        System.arraycopy(other.delayBuf, 0, delayBuf, 0, delayBuf.length);
        function = other.function;
        batchSize = other.batchSize;
        invRatioQ16 = other.invRatioQ16;
        fsInKHz = other.fsInKHz;
        fsOutKHz = other.fsOutKHz;
        inputDelay = other.inputDelay;
    }

    private static int rateId(int r) {
        return Math.min(5, ((((r >> 12) - (r > 16000 ? 1 : 0)) >> (r > 24000 ? 1 : 0)) - 1));
    }

    /** silk_resampler_init for the decoder. */
    void init(int fsHzIn, int fsHzOut) {
        clear();
        if ((fsHzIn != 8000 && fsHzIn != 12000 && fsHzIn != 16000)
                || (fsHzOut != 8000 && fsHzOut != 12000 && fsHzOut != 16000 && fsHzOut != 24000 && fsHzOut != 48000)) {
            throw new IllegalArgumentException("Unsupported SILK resampling " + fsHzIn + " -> " + fsHzOut);
        }
        inputDelay = DELAY_MATRIX_DEC[rateId(fsHzIn)][rateId(fsHzOut)];
        fsInKHz = fsHzIn / 1000;
        fsOutKHz = fsHzOut / 1000;
        // Number of samples processed per batch.
        batchSize = fsInKHz * MAX_BATCH_SIZE_MS;
        // Find resampler with the right sampling ratio.
        int up2x = 0;
        if (fsHzOut > fsHzIn) {
            if (fsHzOut == fsHzIn * 2) {
                // Special case: directly use 2x upsampler.
                function = USE_UP2_HQ;
            } else {
                // Default resampler.
                function = USE_IIR_FIR;
                up2x = 1;
            }
        } else if (fsHzOut < fsHzIn) {
            throw new IllegalArgumentException("SILK never downsamples when decoding at 48 kHz");
        } else {
            // Input and output sampling rates are equal: copy.
            function = USE_COPY;
        }
        // Ratio of input/output samples.
        invRatioQ16 = ((fsHzIn << (14 + up2x)) / fsHzOut) << 2;
        // Make sure the ratio is rounded up.
        while (OpusSilkChannel.smulww(invRatioQ16, fsHzOut) < fsHzIn << up2x) {
            invRatioQ16++;
        }
    }

    /** silk_resampler: resamples {@code inLen} samples (at least 1 ms) from {@code in[inOff]} to {@code out}. */
    void resample(short[] out, int outOff, short[] in, int inOff, int inLen) {
        int nSamples = fsInKHz - inputDelay;
        // Copy to delay buffer.
        System.arraycopy(in, inOff, delayBuf, inputDelay, nSamples);
        switch (function) {
            case USE_UP2_HQ -> {
                up2Hq(sIir, out, outOff, delayBuf, 0, fsInKHz);
                up2Hq(sIir, out, outOff + fsOutKHz, in, inOff + nSamples, inLen - fsInKHz);
            }
            case USE_IIR_FIR -> {
                iirFir(out, outOff, delayBuf, 0, fsInKHz);
                iirFir(out, outOff + fsOutKHz, in, inOff + nSamples, inLen - fsInKHz);
            }
            default -> {
                System.arraycopy(delayBuf, 0, out, outOff, fsInKHz);
                System.arraycopy(in, inOff + nSamples, out, outOff + fsOutKHz, inLen - fsInKHz);
            }
        }
        // Copy to delay buffer.
        System.arraycopy(in, inOff + inLen - inputDelay, delayBuf, 0, inputDelay);
    }

    private void iirFir(short[] out, int outOff, short[] in, int inOff, int inLen) {
        short[] b = buf;
        // Copy buffered samples to start of buffer.
        System.arraycopy(sFir, 0, b, 0, ORDER_FIR_12);
        // Iterate over blocks of frameSizeIn input samples.
        int indexIncrementQ16 = invRatioQ16;
        int nSamplesIn;
        while (true) {
            nSamplesIn = Math.min(inLen, batchSize);
            // Upsample 2x.
            up2Hq(sIir, b, ORDER_FIR_12, in, inOff, nSamplesIn);
            int maxIndexQ16 = nSamplesIn << (16 + 1);
            outOff = interpolate(out, outOff, b, maxIndexQ16, indexIncrementQ16);
            inOff += nSamplesIn;
            inLen -= nSamplesIn;
            if (inLen > 0) {
                // More iterations to do; copy last part of filtered signal to beginning of buffer.
                System.arraycopy(b, nSamplesIn << 1, b, 0, ORDER_FIR_12);
            } else {
                break;
            }
        }
        // Copy last part of filtered signal to the state for the next call.
        System.arraycopy(b, nSamplesIn << 1, sFir, 0, ORDER_FIR_12);
    }

    private static int interpolate(short[] out, int outOff, short[] b, int maxIndexQ16, int indexIncrementQ16) {
        short[] fir = OpusSilkTables.RESAMPLER_FRAC_FIR_12;
        for (int indexQ16 = 0; indexQ16 < maxIndexQ16; indexQ16 += indexIncrementQ16) {
            int tableIndex = OpusSilkChannel.smulwb(indexQ16 & 0xFFFF, 12);
            int p = indexQ16 >> 16;
            int t = tableIndex * 4;
            int u = (11 - tableIndex) * 4;
            int resQ15 = OpusSilkChannel.smulbb(b[p], fir[t]);
            resQ15 = OpusSilkChannel.smlabb(resQ15, b[p + 1], fir[t + 1]);
            resQ15 = OpusSilkChannel.smlabb(resQ15, b[p + 2], fir[t + 2]);
            resQ15 = OpusSilkChannel.smlabb(resQ15, b[p + 3], fir[t + 3]);
            resQ15 = OpusSilkChannel.smlabb(resQ15, b[p + 4], fir[u + 3]);
            resQ15 = OpusSilkChannel.smlabb(resQ15, b[p + 5], fir[u + 2]);
            resQ15 = OpusSilkChannel.smlabb(resQ15, b[p + 6], fir[u + 1]);
            resQ15 = OpusSilkChannel.smlabb(resQ15, b[p + 7], fir[u]);
            out[outOff++] = (short) OpusSilkChannel.sat16(OpusSilkChannel.rshiftRound(resQ15, 15));
        }
        return outOff;
    }

    /** silk_resampler_private_up2_HQ: 2x upsampling with two three-stage all-pass chains. */
    private static void up2Hq(int[] s, short[] out, int outOff, short[] in, int inOff, int len) {
        for (int k = 0; k < len; k++) {
            // Convert to Q10.
            int in32 = in[inOff + k] << 10;
            // First all-pass section for even output sample.
            int y = in32 - s[0];
            int x = OpusSilkChannel.smulwb(y, UP2_HQ_0_0);
            int out321 = s[0] + x;
            s[0] = in32 + x;
            // Second all-pass section for even output sample.
            y = out321 - s[1];
            x = OpusSilkChannel.smulwb(y, UP2_HQ_0_1);
            int out322 = s[1] + x;
            s[1] = out321 + x;
            // Third all-pass section for even output sample.
            y = out322 - s[2];
            x = OpusSilkChannel.smlawb(y, y, UP2_HQ_0_2);
            out321 = s[2] + x;
            s[2] = out322 + x;
            // Apply gain in Q15, convert back to int16 and store to output.
            out[outOff + 2 * k] = (short) OpusSilkChannel.sat16(OpusSilkChannel.rshiftRound(out321, 10));
            // First all-pass section for odd output sample.
            y = in32 - s[3];
            x = OpusSilkChannel.smulwb(y, UP2_HQ_1_0);
            out321 = s[3] + x;
            s[3] = in32 + x;
            // Second all-pass section for odd output sample.
            y = out321 - s[4];
            x = OpusSilkChannel.smulwb(y, UP2_HQ_1_1);
            out322 = s[4] + x;
            s[4] = out321 + x;
            // Third all-pass section for odd output sample.
            y = out322 - s[5];
            x = OpusSilkChannel.smlawb(y, y, UP2_HQ_1_2);
            out321 = s[5] + x;
            s[5] = out322 + x;
            // Apply gain in Q15, convert back to int16 and store to output.
            out[outOff + 2 * k + 1] = (short) OpusSilkChannel.sat16(OpusSilkChannel.rshiftRound(out321, 10));
        }
    }
}

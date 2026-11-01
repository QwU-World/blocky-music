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
 * One channel of the SILK decoder (silk_decoder_state): side information, excitation, LPC/LTP synthesis, packet loss
 * concealment and comfort noise. Ported from libopus 1.6.1 silk/decode_frame.c, decode_indices.c, decode_pulses.c,
 * shell_coder.c, code_signs.c, decode_parameters.c, decode_pitch.c, gain_quant.c, NLSF_decode.c, NLSF_unpack.c,
 * NLSF2A.c, NLSF_stabilize.c, LPC_inv_pred_gain.c, LPC_fit.c, LPC_analysis_filter.c, bwexpander.c,
 * bwexpander_32.c, log2lin.c, sum_sqr_shift.c, sort.c, decode_core.c, PLC.c, CNG.c, decoder_set_fs.c and
 * init_decoder.c, without the 1.5 machine-learning parts (deep PLC, OSCE).
 *
 * <p>SILK is fixed-point even in libopus's float build, so this is integer arithmetic that matches the reference bit
 * for bit. The 64-bit forms of the multiply macros are used, as libopus does on 64-bit machines (OPUS_FAST_INT64),
 * and every store into a C int8 or int16 field keeps its truncation.
 */
final class OpusSilkChannel {
    static final int MAX_LPC_ORDER = 16;
    static final int MAX_FRAME_LENGTH = 320;
    private static final int MAX_NB_SUBFR = 4;
    private static final int LTP_ORDER = 5;
    private static final int MAX_SUB_FRAME_LENGTH = 80;
    static final int TYPE_NO_VOICE_ACTIVITY = 0;
    static final int TYPE_VOICED = 2;
    static final int CODE_INDEPENDENTLY = 0;
    static final int CODE_INDEPENDENTLY_NO_LTP_SCALING = 1;
    static final int CODE_CONDITIONALLY = 2;
    static final int FLAG_DECODE_NORMAL = 0;
    static final int FLAG_PACKET_LOST = 1;
    static final int FLAG_DECODE_LBRR = 2;
    private static final int SHELL_CODEC_FRAME_LENGTH = 16;
    private static final int LOG2_SHELL_CODEC_FRAME_LENGTH = 4;
    private static final int SILK_MAX_PULSES = 16;
    private static final int N_RATE_LEVELS = 10;
    private static final int NLSF_QUANT_MAX_AMPLITUDE = 4;
    private static final int MIN_DELTA_GAIN_QUANT = -4;
    private static final int MAX_DELTA_GAIN_QUANT = 36;
    private static final int N_LEVELS_QGAIN = 64;
    private static final int INV_SCALE_Q16 = (65536 * (((88 - 2) * 128) / 6)) / (N_LEVELS_QGAIN - 1);
    private static final int GAIN_OFFSET = ((2 * 128) / 6 + 16 * 128);
    private static final int QUANT_LEVEL_ADJUST_Q10 = 80;
    private static final int BWE_AFTER_LOSS_Q16 = 63570;
    private static final int MAX_LPC_STABILIZE_ITERATIONS = 16;
    private static final int PE_MIN_LAG_MS = 2;
    private static final int PE_MAX_LAG_MS = 18;
    private static final int NLSF_QUANT_LEVEL_ADJ_Q10 = (int) (0.1 * (1L << 10) + 0.5);
    private static final int A_LIMIT = (int) (0.99975 * (1L << 24) + 0.5);
    private static final int INV_PRED_GAIN_MIN_Q30 = (int) ((1.0f / 1e4f) * (1L << 30) + 0.5);

    // PLC constants (PLC.h and PLC.c).
    private static final int[] HARM_ATT_Q15 = {32440, 31130};
    private static final int[] PLC_RAND_ATTENUATE_V_Q15 = {31130, 26214};
    private static final int[] PLC_RAND_ATTENUATE_UV_Q15 = {32440, 29491};
    private static final int BWE_COEF_Q16 = (int) (0.99 * (1L << 16) + 0.5);
    private static final int V_PITCH_GAIN_START_MIN_Q14 = 11469;
    private static final int V_PITCH_GAIN_START_MAX_Q14 = 15565;
    private static final int MAX_PITCH_LAG_MS = 18;
    private static final int RAND_BUF_SIZE = 128;
    private static final int RAND_BUF_MASK = RAND_BUF_SIZE - 1;
    private static final int LOG2_INV_LPC_GAIN_HIGH_THRES = 3;
    private static final int LOG2_INV_LPC_GAIN_LOW_THRES = 8;
    private static final int PITCH_DRIFT_FAC_Q16 = 655;

    // CNG constants (define.h).
    private static final int CNG_BUF_MASK_MAX = 255;
    private static final int CNG_GAIN_SMTH_Q16 = 4634;
    private static final int CNG_GAIN_SMTH_THRESHOLD_Q16 = 46396;
    private static final int CNG_NLSF_SMTH_Q16 = 16348;

    private static final int[] ORDERING16 = {0, 15, 8, 7, 4, 11, 12, 3, 2, 13, 10, 5, 6, 9, 14, 1};
    private static final int[] ORDERING10 = {0, 9, 6, 3, 4, 5, 8, 1, 2, 7};

    /** A first-stage NLSF codebook and its entropy tables (silk_NLSF_CB_struct). */
    static final class NlsfCodebook {
        final int nVectors;
        final int order;
        final int quantStepSizeQ16;
        final short[] cb1NlsfQ8;
        final short[] cb1WghtQ9;
        final short[] cb1Icdf;
        final short[] predQ8;
        final short[] ecSel;
        final short[] ecIcdf;
        final short[] deltaMinQ15;

        NlsfCodebook(int nVectors, int order, double quantStep, short[] cb1NlsfQ8, short[] cb1WghtQ9, short[] cb1Icdf,
                     short[] predQ8, short[] ecSel, short[] ecIcdf, short[] deltaMinQ15) {
            this.nVectors = nVectors;
            this.order = order;
            this.quantStepSizeQ16 = (short) (int) (quantStep * (1L << 16) + 0.5);
            this.cb1NlsfQ8 = cb1NlsfQ8;
            this.cb1WghtQ9 = cb1WghtQ9;
            this.cb1Icdf = cb1Icdf;
            this.predQ8 = predQ8;
            this.ecSel = ecSel;
            this.ecIcdf = ecIcdf;
            this.deltaMinQ15 = deltaMinQ15;
        }
    }

    static final NlsfCodebook NLSF_CB_NB_MB = new NlsfCodebook(32, 10, 0.18, OpusSilkTables.NLSF_CB1_NB_MB_Q8,
            OpusSilkTables.NLSF_CB1_WGHT_NB_MB_Q9, OpusSilkTables.NLSF_CB1_ICDF_NB_MB, OpusSilkTables.NLSF_PRED_NB_MB_Q8,
            OpusSilkTables.NLSF_CB2_SELECT_NB_MB, OpusSilkTables.NLSF_CB2_ICDF_NB_MB,
            OpusSilkTables.NLSF_DELTA_MIN_NB_MB_Q15);
    static final NlsfCodebook NLSF_CB_WB = new NlsfCodebook(32, 16, 0.15, OpusSilkTables.NLSF_CB1_WB_Q8,
            OpusSilkTables.NLSF_CB1_WGHT_WB_Q9, OpusSilkTables.NLSF_CB1_ICDF_WB, OpusSilkTables.NLSF_PRED_WB_Q8,
            OpusSilkTables.NLSF_CB2_SELECT_WB, OpusSilkTables.NLSF_CB2_ICDF_WB, OpusSilkTables.NLSF_DELTA_MIN_WB_Q15);

    // ---- silk_decoder_state (everything from prev_gain_Q16 on is cleared by reset())
    int prevGainQ16;
    final int[] excQ14 = new int[MAX_FRAME_LENGTH];
    final int[] sLpcQ14Buf = new int[MAX_LPC_ORDER];
    final short[] outBuf = new short[MAX_FRAME_LENGTH + 2 * MAX_SUB_FRAME_LENGTH];
    int lagPrev;
    int lastGainIndex;
    int fsKHz;
    int fsApiHz;
    int nbSubfr;
    int frameLength;
    int subfrLength;
    int ltpMemLength;
    int lpcOrder;
    final short[] prevNlsfQ15 = new short[MAX_LPC_ORDER];
    boolean firstFrameAfterReset;
    short[] pitchLagLowBitsIcdf;
    short[] pitchContourIcdf;
    int nFramesDecoded;
    int nFramesPerPacket;
    int ecPrevSignalType;
    int ecPrevLagIndex;
    final int[] vadFlags = new int[3];
    int lbrrFlag;
    final int[] lbrrFlags = new int[3];
    final OpusSilkResampler resampler = new OpusSilkResampler();
    NlsfCodebook nlsfCb;
    // SideInfoIndices
    final int[] gainsIndices = new int[MAX_NB_SUBFR];
    final int[] ltpIndex = new int[MAX_NB_SUBFR];
    final int[] nlsfIndices = new int[MAX_LPC_ORDER + 1];
    int lagIndex;
    int contourIndex;
    int signalType;
    int quantOffsetType;
    int nlsfInterpCoefQ2;
    int perIndex;
    int ltpScaleIndex;
    int seed;
    // silk_CNG_struct
    final int[] cngExcBufQ14 = new int[MAX_FRAME_LENGTH];
    final short[] cngSmthNlsfQ15 = new short[MAX_LPC_ORDER];
    final int[] cngSynthState = new int[MAX_LPC_ORDER];
    int cngSmthGainQ16;
    int cngRandSeed;
    int cngFsKHz;
    // PLC
    int lossCnt;
    int prevSignalType;
    // silk_PLC_struct
    int plcPitchLQ8;
    final short[] plcLtpCoefQ14 = new short[LTP_ORDER];
    final short[] plcPrevLpcQ12 = new short[MAX_LPC_ORDER];
    boolean plcLastFrameLost;
    int plcRandSeed;
    short plcRandScaleQ14;
    int plcConcEnergy;
    int plcConcEnergyShift;
    short plcPrevLtpScaleQ14;
    final int[] plcPrevGainQ16 = new int[2];
    int plcFsKHz;
    int plcNbSubfr;
    int plcSubfrLength;

    // ---- silk_decoder_control, reused for every frame (C allocates it on the stack uninitialised)
    private final int[] pitchL = new int[MAX_NB_SUBFR];
    private final int[] gainsQ16 = new int[MAX_NB_SUBFR];
    private final short[][] predCoefQ12 = new short[2][MAX_LPC_ORDER];
    private final short[] ltpCoefQ14 = new short[LTP_ORDER * MAX_NB_SUBFR];
    private int ltpScaleQ14;

    // ---- scratch
    private final short[] pulses = new short[MAX_FRAME_LENGTH];
    private final int[] sumPulses = new int[MAX_FRAME_LENGTH / SHELL_CODEC_FRAME_LENGTH];
    private final int[] nLshifts = new int[MAX_FRAME_LENGTH / SHELL_CODEC_FRAME_LENGTH];
    private final short[] ecIx = new short[MAX_LPC_ORDER];
    private final short[] predQ8 = new short[MAX_LPC_ORDER];
    private final short[] resQ10 = new short[MAX_LPC_ORDER];
    private final short[] nlsfQ15 = new short[MAX_LPC_ORDER];
    private final short[] nlsf0Q15 = new short[MAX_LPC_ORDER];
    private final int[] cosLsfQa = new int[MAX_LPC_ORDER];
    private final int[] polyP = new int[MAX_LPC_ORDER / 2 + 1];
    private final int[] polyQ = new int[MAX_LPC_ORDER / 2 + 1];
    private final int[] a32Qa1 = new int[MAX_LPC_ORDER];
    private final int[] aTmpQa = new int[MAX_LPC_ORDER];
    private final short[] sLtp = new short[MAX_FRAME_LENGTH];
    private final int[] sLtpQ15 = new int[2 * MAX_FRAME_LENGTH];
    private final int[] resQ14 = new int[MAX_SUB_FRAME_LENGTH];
    private final int[] sLpcQ14 = new int[MAX_SUB_FRAME_LENGTH + MAX_LPC_ORDER];
    private final short[] aQ12Tmp = new short[MAX_LPC_ORDER];
    private final short[] excBuf = new short[2 * MAX_SUB_FRAME_LENGTH];
    private final int[] cngSigQ14 = new int[MAX_FRAME_LENGTH + MAX_LPC_ORDER];
    private final int[] energyOut = new int[2];
    private final short[] shell3 = new short[2];
    private final short[] shell2 = new short[4];
    private final short[] shell1 = new short[8];

    /** silk_init_decoder. */
    OpusSilkChannel() {
        reset();
    }

    /** silk_reset_decoder: clears the whole state (nothing comes before the reset start without OSCE). */
    void reset() {
        prevGainQ16 = 0;
        Arrays.fill(excQ14, 0);
        Arrays.fill(sLpcQ14Buf, 0);
        Arrays.fill(outBuf, (short) 0);
        lagPrev = 0;
        lastGainIndex = 0;
        fsKHz = 0;
        fsApiHz = 0;
        nbSubfr = 0;
        frameLength = 0;
        subfrLength = 0;
        ltpMemLength = 0;
        lpcOrder = 0;
        Arrays.fill(prevNlsfQ15, (short) 0);
        pitchLagLowBitsIcdf = null;
        pitchContourIcdf = null;
        nFramesDecoded = 0;
        nFramesPerPacket = 0;
        ecPrevSignalType = 0;
        ecPrevLagIndex = 0;
        Arrays.fill(vadFlags, 0);
        lbrrFlag = 0;
        Arrays.fill(lbrrFlags, 0);
        resampler.clear();
        nlsfCb = null;
        Arrays.fill(gainsIndices, 0);
        Arrays.fill(ltpIndex, 0);
        Arrays.fill(nlsfIndices, 0);
        lagIndex = 0;
        contourIndex = 0;
        signalType = 0;
        quantOffsetType = 0;
        nlsfInterpCoefQ2 = 0;
        perIndex = 0;
        ltpScaleIndex = 0;
        seed = 0;
        Arrays.fill(cngExcBufQ14, 0);
        Arrays.fill(cngSmthNlsfQ15, (short) 0);
        Arrays.fill(cngSynthState, 0);
        cngSmthGainQ16 = 0;
        cngRandSeed = 0;
        cngFsKHz = 0;
        lossCnt = 0;
        prevSignalType = 0;
        plcPitchLQ8 = 0;
        Arrays.fill(plcLtpCoefQ14, (short) 0);
        Arrays.fill(plcPrevLpcQ12, (short) 0);
        plcLastFrameLost = false;
        plcRandSeed = 0;
        plcRandScaleQ14 = 0;
        plcConcEnergy = 0;
        plcConcEnergyShift = 0;
        plcPrevLtpScaleQ14 = 0;
        Arrays.fill(plcPrevGainQ16, 0);
        plcFsKHz = 0;
        plcNbSubfr = 0;
        plcSubfrLength = 0;

        // Used to deactivate LSF interpolation.
        firstFrameAfterReset = true;
        prevGainQ16 = 65536;
        cngReset();
        plcReset();
    }

    /** silk_decoder_set_fs. */
    void setFs(int fsKHzNew, int fsApiHzNew) {
        subfrLength = 5 * fsKHzNew;
        int newFrameLength = nbSubfr * subfrLength;
        // Initialize the resampler when switching internal or external sampling frequency.
        if (fsKHz != fsKHzNew || fsApiHz != fsApiHzNew) {
            resampler.init(fsKHzNew * 1000, fsApiHzNew);
            fsApiHz = fsApiHzNew;
        }
        if (fsKHz != fsKHzNew || newFrameLength != frameLength) {
            if (fsKHzNew == 8) {
                pitchContourIcdf = nbSubfr == MAX_NB_SUBFR ? OpusSilkTables.PITCH_CONTOUR_NB_ICDF
                        : OpusSilkTables.PITCH_CONTOUR_10_MS_NB_ICDF;
            } else {
                pitchContourIcdf = nbSubfr == MAX_NB_SUBFR ? OpusSilkTables.PITCH_CONTOUR_ICDF
                        : OpusSilkTables.PITCH_CONTOUR_10_MS_ICDF;
            }
            if (fsKHz != fsKHzNew) {
                ltpMemLength = 20 * fsKHzNew;
                if (fsKHzNew == 8 || fsKHzNew == 12) {
                    lpcOrder = 10;
                    nlsfCb = NLSF_CB_NB_MB;
                } else {
                    lpcOrder = MAX_LPC_ORDER;
                    nlsfCb = NLSF_CB_WB;
                }
                if (fsKHzNew == 16) {
                    pitchLagLowBitsIcdf = OpusSilkTables.UNIFORM8_ICDF;
                } else if (fsKHzNew == 12) {
                    pitchLagLowBitsIcdf = OpusSilkTables.UNIFORM6_ICDF;
                } else {
                    pitchLagLowBitsIcdf = OpusSilkTables.UNIFORM4_ICDF;
                }
                firstFrameAfterReset = true;
                lagPrev = 100;
                lastGainIndex = 10;
                prevSignalType = TYPE_NO_VOICE_ACTIVITY;
                Arrays.fill(outBuf, (short) 0);
                Arrays.fill(sLpcQ14Buf, 0);
            }
            fsKHz = fsKHzNew;
            frameLength = newFrameLength;
        }
    }

    /**
     * silk_decode_frame: decodes (or conceals) one 10 or 20 ms frame into {@code out[outOff ..]}.
     *
     * @param lostFlag {@link #FLAG_DECODE_NORMAL}, {@link #FLAG_PACKET_LOST} or {@link #FLAG_DECODE_LBRR}
     */
    void decodeFrame(OpusRangeDecoder dec, short[] out, int outOff, int lostFlag, int condCoding) {
        int l = frameLength;
        ltpScaleQ14 = 0;
        if (lostFlag == FLAG_DECODE_NORMAL || (lostFlag == FLAG_DECODE_LBRR && lbrrFlags[nFramesDecoded] == 1)) {
            // Decode quantization indices of side info.
            decodeIndices(dec, nFramesDecoded, lostFlag != FLAG_DECODE_NORMAL, condCoding);
            // Decode quantization indices of excitation.
            decodePulses(dec, pulses, signalType, quantOffsetType, frameLength);
            // Decode parameters and pulse signal.
            decodeParameters(condCoding);
            // Run inverse NSQ.
            decodeCore(out, outOff, pulses);
            // Update output buffer.
            int mvLen = ltpMemLength - frameLength;
            System.arraycopy(outBuf, frameLength, outBuf, 0, mvLen);
            System.arraycopy(out, outOff, outBuf, mvLen, frameLength);
            // Update PLC state.
            plc(out, outOff, false);
            lossCnt = 0;
            prevSignalType = signalType;
            // A frame has been decoded without errors.
            firstFrameAfterReset = false;
        } else {
            // Handle packet loss by extrapolation.
            plc(out, outOff, true);
            // Update output buffer.
            int mvLen = ltpMemLength - frameLength;
            System.arraycopy(outBuf, frameLength, outBuf, 0, mvLen);
            System.arraycopy(out, outOff, outBuf, mvLen, frameLength);
        }
        // Comfort noise generation / estimation.
        cng(out, outOff, l);
        // Ensure smooth connection of extrapolated and good frames.
        plcGlueFrames(out, outOff, l);
        // Update some decoder state variables.
        lagPrev = pitchL[nbSubfr - 1];
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Side information

    /** silk_decode_indices. */
    void decodeIndices(OpusRangeDecoder dec, int frameIndex, boolean decodeLbrr, int condCoding) {
        // Decode signal type and quantizer offset.
        int ix;
        if (decodeLbrr || vadFlags[frameIndex] != 0) {
            ix = dec.decodeIcdf(OpusSilkTables.TYPE_OFFSET_VAD_ICDF, 8) + 2;
        } else {
            ix = dec.decodeIcdf(OpusSilkTables.TYPE_OFFSET_NO_VAD_ICDF, 8);
        }
        signalType = (byte) (ix >> 1);
        quantOffsetType = (byte) (ix & 1);

        // Decode gains: the first subframe, conditionally or in two stages (MSBs then 3 LSBs).
        if (condCoding == CODE_CONDITIONALLY) {
            gainsIndices[0] = (byte) dec.decodeIcdf(OpusSilkTables.DELTA_GAIN_ICDF, 8);
        } else {
            gainsIndices[0] = (byte) (dec.decodeIcdf(OpusSilkTables.GAIN_ICDF, signalType * 8, 8) << 3);
            gainsIndices[0] = (byte) (gainsIndices[0] + (byte) dec.decodeIcdf(OpusSilkTables.UNIFORM8_ICDF, 8));
        }
        // Remaining subframes.
        for (int i = 1; i < nbSubfr; i++) {
            gainsIndices[i] = (byte) dec.decodeIcdf(OpusSilkTables.DELTA_GAIN_ICDF, 8);
        }

        // Decode LSF indices.
        NlsfCodebook cb = nlsfCb;
        nlsfIndices[0] = (byte) dec.decodeIcdf(cb.cb1Icdf, (signalType >> 1) * cb.nVectors, 8);
        nlsfUnpack(ecIx, predQ8, cb, nlsfIndices[0]);
        for (int i = 0; i < cb.order; i++) {
            ix = dec.decodeIcdf(cb.ecIcdf, ecIx[i], 8);
            if (ix == 0) {
                ix -= dec.decodeIcdf(OpusSilkTables.NLSF_EXT_ICDF, 8);
            } else if (ix == 2 * NLSF_QUANT_MAX_AMPLITUDE) {
                ix += dec.decodeIcdf(OpusSilkTables.NLSF_EXT_ICDF, 8);
            }
            nlsfIndices[i + 1] = (byte) (ix - NLSF_QUANT_MAX_AMPLITUDE);
        }
        // Decode LSF interpolation factor.
        if (nbSubfr == MAX_NB_SUBFR) {
            nlsfInterpCoefQ2 = (byte) dec.decodeIcdf(OpusSilkTables.NLSF_INTERPOLATION_FACTOR_ICDF, 8);
        } else {
            nlsfInterpCoefQ2 = 4;
        }

        if (signalType == TYPE_VOICED) {
            // Decode pitch lags: get the lag index.
            boolean decodeAbsoluteLagIndex = true;
            if (condCoding == CODE_CONDITIONALLY && ecPrevSignalType == TYPE_VOICED) {
                // Decode delta index.
                int deltaLagIndex = (short) dec.decodeIcdf(OpusSilkTables.PITCH_DELTA_ICDF, 8);
                if (deltaLagIndex > 0) {
                    deltaLagIndex = deltaLagIndex - 9;
                    lagIndex = (short) (ecPrevLagIndex + deltaLagIndex);
                    decodeAbsoluteLagIndex = false;
                }
            }
            if (decodeAbsoluteLagIndex) {
                // Absolute decoding.
                lagIndex = (short) ((short) dec.decodeIcdf(OpusSilkTables.PITCH_LAG_ICDF, 8) * (fsKHz >> 1));
                lagIndex = (short) (lagIndex + (short) dec.decodeIcdf(pitchLagLowBitsIcdf, 8));
            }
            ecPrevLagIndex = lagIndex;
            // Get contour index.
            contourIndex = (byte) dec.decodeIcdf(pitchContourIcdf, 8);
            // Decode LTP gains: the PERIndex value, then one codebook index per subframe.
            perIndex = (byte) dec.decodeIcdf(OpusSilkTables.LTP_PER_INDEX_ICDF, 8);
            short[] gainIcdf = perIndex == 0 ? OpusSilkTables.LTP_GAIN_ICDF_0
                    : perIndex == 1 ? OpusSilkTables.LTP_GAIN_ICDF_1 : OpusSilkTables.LTP_GAIN_ICDF_2;
            for (int k = 0; k < nbSubfr; k++) {
                ltpIndex[k] = (byte) dec.decodeIcdf(gainIcdf, 8);
            }
            // Decode LTP scaling.
            if (condCoding == CODE_INDEPENDENTLY) {
                ltpScaleIndex = (byte) dec.decodeIcdf(OpusSilkTables.LTP_SCALE_ICDF, 8);
            } else {
                ltpScaleIndex = 0;
            }
        }
        ecPrevSignalType = signalType;
        // Decode seed.
        seed = (byte) dec.decodeIcdf(OpusSilkTables.UNIFORM4_ICDF, 8);
    }

    /** silk_decode_pulses: rate level, pulse counts per 16-sample block, shell coding, LSBs and signs. */
    static void decodePulses(OpusRangeDecoder dec, short[] pulses, int signalType, int quantOffsetType,
                             int frameLength, int[] sumPulses, int[] nLshifts, short[] s3, short[] s2, short[] s1) {
        // Decode rate level.
        int rateLevelIndex = dec.decodeIcdf(OpusSilkTables.RATE_LEVELS_ICDF, (signalType >> 1) * 9, 8);
        // Calculate number of shell blocks.
        int iter = frameLength >> LOG2_SHELL_CODEC_FRAME_LENGTH;
        if (iter * SHELL_CODEC_FRAME_LENGTH < frameLength) {
            iter++;
        }
        // Sum-weighted-pulses decoding.
        int cdf = rateLevelIndex * 18;
        for (int i = 0; i < iter; i++) {
            nLshifts[i] = 0;
            sumPulses[i] = dec.decodeIcdf(OpusSilkTables.PULSES_PER_BLOCK_ICDF, cdf, 8);
            // LSB indication.
            while (sumPulses[i] == SILK_MAX_PULSES + 1) {
                nLshifts[i]++;
                // When we've already got 10 LSBs, we shift the table to not allow (SILK_MAX_PULSES + 1).
                sumPulses[i] = dec.decodeIcdf(OpusSilkTables.PULSES_PER_BLOCK_ICDF,
                        (N_RATE_LEVELS - 1) * 18 + (nLshifts[i] == 10 ? 1 : 0), 8);
            }
        }
        // Shell decoding.
        for (int i = 0; i < iter; i++) {
            if (sumPulses[i] > 0) {
                shellDecoder(pulses, i * SHELL_CODEC_FRAME_LENGTH, dec, sumPulses[i], s3, s2, s1);
            } else {
                Arrays.fill(pulses, i * SHELL_CODEC_FRAME_LENGTH, (i + 1) * SHELL_CODEC_FRAME_LENGTH, (short) 0);
            }
        }
        // LSB decoding.
        for (int i = 0; i < iter; i++) {
            if (nLshifts[i] > 0) {
                int nLs = nLshifts[i];
                int p = i * SHELL_CODEC_FRAME_LENGTH;
                for (int k = 0; k < SHELL_CODEC_FRAME_LENGTH; k++) {
                    int absQ = pulses[p + k];
                    for (int j = 0; j < nLs; j++) {
                        absQ = absQ << 1;
                        absQ += dec.decodeIcdf(OpusSilkTables.LSB_ICDF, 8);
                    }
                    pulses[p + k] = (short) absQ;
                }
                // Mark the number of pulses non-zero for sign decoding.
                sumPulses[i] |= nLs << 5;
            }
        }
        // Decode and add signs to pulse signal.
        decodeSigns(dec, pulses, frameLength, signalType, quantOffsetType, sumPulses);
    }

    private void decodePulses(OpusRangeDecoder dec, short[] p, int type, int offsetType, int length) {
        decodePulses(dec, p, type, offsetType, length, sumPulses, nLshifts, shell3, shell2, shell1);
    }

    private static void decodeSplit(short[] child1, int i1, short[] child2, int i2, OpusRangeDecoder dec, int p,
                                    short[] table) {
        if (p > 0) {
            child1[i1] = (short) dec.decodeIcdf(table, OpusSilkTables.SHELL_CODE_TABLE_OFFSETS[p], 8);
            child2[i2] = (short) (p - child1[i1]);
        } else {
            child1[i1] = 0;
            child2[i2] = 0;
        }
    }

    /** silk_shell_decoder: splits a block's pulse count into its 16 positions. */
    private static void shellDecoder(short[] p0, int o, OpusRangeDecoder dec, int pulses4, short[] p3, short[] p2,
                                     short[] p1) {
        short[] t3 = OpusSilkTables.SHELL_CODE_TABLE3;
        short[] t2 = OpusSilkTables.SHELL_CODE_TABLE2;
        short[] t1 = OpusSilkTables.SHELL_CODE_TABLE1;
        short[] t0 = OpusSilkTables.SHELL_CODE_TABLE0;
        decodeSplit(p3, 0, p3, 1, dec, pulses4, t3);
        decodeSplit(p2, 0, p2, 1, dec, p3[0], t2);
        decodeSplit(p1, 0, p1, 1, dec, p2[0], t1);
        decodeSplit(p0, o, p0, o + 1, dec, p1[0], t0);
        decodeSplit(p0, o + 2, p0, o + 3, dec, p1[1], t0);
        decodeSplit(p1, 2, p1, 3, dec, p2[1], t1);
        decodeSplit(p0, o + 4, p0, o + 5, dec, p1[2], t0);
        decodeSplit(p0, o + 6, p0, o + 7, dec, p1[3], t0);
        decodeSplit(p2, 2, p2, 3, dec, p3[1], t2);
        decodeSplit(p1, 4, p1, 5, dec, p2[2], t1);
        decodeSplit(p0, o + 8, p0, o + 9, dec, p1[4], t0);
        decodeSplit(p0, o + 10, p0, o + 11, dec, p1[5], t0);
        decodeSplit(p1, 6, p1, 7, dec, p2[3], t1);
        decodeSplit(p0, o + 12, p0, o + 13, dec, p1[6], t0);
        decodeSplit(p0, o + 14, p0, o + 15, dec, p1[7], t0);
    }

    /** silk_decode_signs. */
    private static void decodeSigns(OpusRangeDecoder dec, short[] pulses, int length, int signalType,
                                    int quantOffsetType, int[] sumPulses) {
        short[] icdf = new short[2];
        int q = 0;
        int i = 7 * (quantOffsetType + (signalType << 1));
        int icdfPtr = i;
        length = (length + SHELL_CODEC_FRAME_LENGTH / 2) >> LOG2_SHELL_CODEC_FRAME_LENGTH;
        for (i = 0; i < length; i++) {
            int p = sumPulses[i];
            if (p > 0) {
                icdf[0] = OpusSilkTables.SIGN_ICDF[icdfPtr + Math.min(p & 0x1F, 6)];
                for (int j = 0; j < SHELL_CODEC_FRAME_LENGTH; j++) {
                    if (pulses[q + j] > 0) {
                        // Attach the sign: silk_dec_map maps 0 to -1 and 1 to 1.
                        pulses[q + j] = (short) (pulses[q + j] * ((dec.decodeIcdf(icdf, 8) << 1) - 1));
                    }
                }
            }
            q += SHELL_CODEC_FRAME_LENGTH;
        }
    }

    /** silk_decode_parameters. */
    private void decodeParameters(int condCoding) {
        // Dequant gains.
        gainsDequant(condCoding == CODE_CONDITIONALLY);
        // Decode NLSFs.
        nlsfDecode(nlsfQ15, nlsfIndices, nlsfCb);
        // Convert NLSF parameters to AR prediction filter coefficients.
        nlsf2a(predCoefQ12[1], nlsfQ15, lpcOrder);
        // If just reset, e.g., because internal Fs changed, do not allow interpolation. This improves the case of
        // packet loss in the first frame after a switch.
        if (firstFrameAfterReset) {
            nlsfInterpCoefQ2 = 4;
        }
        if (nlsfInterpCoefQ2 < 4) {
            // Calculation of the interpolated NLSF0 vector from the interpolation factor, the previous NLSF1, and
            // the current NLSF1.
            for (int i = 0; i < lpcOrder; i++) {
                nlsf0Q15[i] = (short) (prevNlsfQ15[i] + ((nlsfInterpCoefQ2 * (nlsfQ15[i] - prevNlsfQ15[i])) >> 2));
            }
            nlsf2a(predCoefQ12[0], nlsf0Q15, lpcOrder);
        } else {
            // Copy LPC coefficients for first half from second half.
            System.arraycopy(predCoefQ12[1], 0, predCoefQ12[0], 0, lpcOrder);
        }
        System.arraycopy(nlsfQ15, 0, prevNlsfQ15, 0, lpcOrder);
        // After a packet loss do BWE of LPC coefs.
        if (lossCnt != 0) {
            bwexpander(predCoefQ12[0], lpcOrder, BWE_AFTER_LOSS_Q16);
            bwexpander(predCoefQ12[1], lpcOrder, BWE_AFTER_LOSS_Q16);
        }
        if (signalType == TYPE_VOICED) {
            // Decode pitch values.
            decodePitch(lagIndex, contourIndex, pitchL, fsKHz, nbSubfr);
            // Decode codebook index.
            byte[] cbk = perIndex == 0 ? OpusSilkTables.LTP_GAIN_VQ_0
                    : perIndex == 1 ? OpusSilkTables.LTP_GAIN_VQ_1 : OpusSilkTables.LTP_GAIN_VQ_2;
            for (int k = 0; k < nbSubfr; k++) {
                int ix = ltpIndex[k];
                for (int i = 0; i < LTP_ORDER; i++) {
                    ltpCoefQ14[k * LTP_ORDER + i] = (short) (cbk[ix * LTP_ORDER + i] << 7);
                }
            }
            // Decode LTP scaling.
            ltpScaleQ14 = OpusSilkTables.LTP_SCALES_TABLE_Q14[ltpScaleIndex];
        } else {
            Arrays.fill(pitchL, 0, nbSubfr, 0);
            Arrays.fill(ltpCoefQ14, 0, LTP_ORDER * nbSubfr, (short) 0);
            perIndex = 0;
            ltpScaleQ14 = 0;
        }
    }

    /** silk_gains_dequant. */
    private void gainsDequant(boolean conditional) {
        for (int k = 0; k < nbSubfr; k++) {
            if (k == 0 && !conditional) {
                // Gain index is not allowed to go down more than 16 steps (~21.8 dB).
                lastGainIndex = (byte) Math.max(gainsIndices[k], lastGainIndex - 16);
            } else {
                // Delta index.
                int indTmp = gainsIndices[k] + MIN_DELTA_GAIN_QUANT;
                // Accumulate deltas.
                int doubleStepSizeThreshold = 2 * MAX_DELTA_GAIN_QUANT - N_LEVELS_QGAIN + lastGainIndex;
                if (indTmp > doubleStepSizeThreshold) {
                    lastGainIndex = (byte) (lastGainIndex + (indTmp << 1) - doubleStepSizeThreshold);
                } else {
                    lastGainIndex = (byte) (lastGainIndex + indTmp);
                }
            }
            lastGainIndex = (byte) limit(lastGainIndex, 0, N_LEVELS_QGAIN - 1);
            // Scale and convert to linear scale.
            gainsQ16[k] = log2lin(Math.min(smulwb(INV_SCALE_Q16, lastGainIndex) + GAIN_OFFSET, 3967));
        }
    }

    /** silk_decode_pitch. */
    private static void decodePitch(int lagIndex, int contourIndex, int[] pitchLags, int fsKHz, int nbSubfr) {
        byte[] lagCb;
        int cbkSize;
        if (fsKHz == 8) {
            if (nbSubfr == MAX_NB_SUBFR) {
                lagCb = OpusSilkTables.CB_LAGS_STAGE2;
                cbkSize = 11;
            } else {
                lagCb = OpusSilkTables.CB_LAGS_STAGE2_10_MS;
                cbkSize = 3;
            }
        } else {
            if (nbSubfr == MAX_NB_SUBFR) {
                lagCb = OpusSilkTables.CB_LAGS_STAGE3;
                cbkSize = 34;
            } else {
                lagCb = OpusSilkTables.CB_LAGS_STAGE3_10_MS;
                cbkSize = 12;
            }
        }
        int minLag = PE_MIN_LAG_MS * fsKHz;
        int maxLag = PE_MAX_LAG_MS * fsKHz;
        int lag = minLag + lagIndex;
        for (int k = 0; k < nbSubfr; k++) {
            pitchLags[k] = lag + lagCb[k * cbkSize + contourIndex];
            pitchLags[k] = limit(pitchLags[k], minLag, maxLag);
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // NLSF and LPC

    private static void nlsfUnpack(short[] ecIx, short[] predQ8, NlsfCodebook cb, int cb1Index) {
        int ecSel = cb1Index * cb.order / 2;
        for (int i = 0; i < cb.order; i += 2) {
            int entry = cb.ecSel[ecSel++];
            ecIx[i] = (short) (((entry >> 1) & 7) * (2 * NLSF_QUANT_MAX_AMPLITUDE + 1));
            predQ8[i] = cb.predQ8[i + (entry & 1) * (cb.order - 1)];
            ecIx[i + 1] = (short) (((entry >> 5) & 7) * (2 * NLSF_QUANT_MAX_AMPLITUDE + 1));
            predQ8[i + 1] = cb.predQ8[i + ((entry >> 4) & 1) * (cb.order - 1) + 1];
        }
    }

    /** silk_NLSF_decode: first-stage vector plus the predictively coded residual, then stabilisation. */
    private void nlsfDecode(short[] nlsf, int[] indices, NlsfCodebook cb) {
        nlsfUnpack(ecIx, predQ8, cb, indices[0]);
        // Predictive residual dequantizer (silk_NLSF_residual_dequant).
        int outQ10 = 0;
        for (int i = cb.order - 1; i >= 0; i--) {
            int predQ10 = (smulbb(outQ10, predQ8[i])) >> 8;
            outQ10 = indices[i + 1] << 10;
            if (outQ10 > 0) {
                outQ10 = outQ10 - NLSF_QUANT_LEVEL_ADJ_Q10;
            } else if (outQ10 < 0) {
                outQ10 = outQ10 + NLSF_QUANT_LEVEL_ADJ_Q10;
            }
            outQ10 = smlawb(predQ10, outQ10, cb.quantStepSizeQ16);
            resQ10[i] = (short) outQ10;
        }
        // Apply inverse square-rooted weights to first stage and add to output.
        int base = indices[0] * cb.order;
        for (int i = 0; i < cb.order; i++) {
            int tmp = ((resQ10[i] << 14) / cb.cb1WghtQ9[base + i]) + (cb.cb1NlsfQ8[base + i] << 7);
            nlsf[i] = (short) limit(tmp, 0, 32767);
        }
        // NLSF stabilization.
        nlsfStabilize(nlsf, cb.deltaMinQ15, cb.order);
    }

    /** silk_NLSF_stabilize: enforces the minimum spacing between the NLSFs. */
    static void nlsfStabilize(short[] nlsf, short[] deltaMin, int l) {
        int loops;
        for (loops = 0; loops < 20; loops++) {
            // Find smallest distance.
            int minDiff = nlsf[0] - deltaMin[0];
            int idx = 0;
            for (int i = 1; i <= l - 1; i++) {
                int diff = nlsf[i] - (nlsf[i - 1] + deltaMin[i]);
                if (diff < minDiff) {
                    minDiff = diff;
                    idx = i;
                }
            }
            int diff = (1 << 15) - (nlsf[l - 1] + deltaMin[l]);
            if (diff < minDiff) {
                minDiff = diff;
                idx = l;
            }
            // Now check if the smallest distance is non-negative.
            if (minDiff >= 0) {
                return;
            }
            if (idx == 0) {
                // Move away from lower limit.
                nlsf[0] = deltaMin[0];
            } else if (idx == l) {
                // Move away from higher limit.
                nlsf[l - 1] = (short) ((1 << 15) - deltaMin[l]);
            } else {
                // Find the lower extreme for the location of the current center frequency.
                int minCenter = 0;
                for (int k = 0; k < idx; k++) {
                    minCenter += deltaMin[k];
                }
                minCenter += deltaMin[idx] >> 1;
                // Find the upper extreme for the location of the current center frequency.
                int maxCenter = 1 << 15;
                for (int k = l; k > idx; k--) {
                    maxCenter -= deltaMin[k];
                }
                maxCenter -= deltaMin[idx] >> 1;
                // Move apart, sorted by value, keeping the same center frequency.
                short center = (short) limit(rshiftRound(nlsf[idx - 1] + nlsf[idx], 1), minCenter, maxCenter);
                nlsf[idx - 1] = (short) (center - (deltaMin[idx] >> 1));
                nlsf[idx] = (short) (nlsf[idx - 1] + deltaMin[idx]);
            }
        }
        // Safe and simple fall back method, which is less ideal than the above.
        // Insertion sort (fast for already almost sorted arrays).
        for (int i = 1; i < l; i++) {
            int value = nlsf[i];
            int j;
            for (j = i - 1; j >= 0 && value < nlsf[j]; j--) {
                nlsf[j + 1] = nlsf[j];
            }
            nlsf[j + 1] = (short) value;
        }
        // First NLSF should be no less than NDeltaMin[0].
        nlsf[0] = (short) Math.max(nlsf[0], deltaMin[0]);
        // Keep delta_min distance between the NLSFs.
        for (int i = 1; i < l; i++) {
            nlsf[i] = (short) Math.max(nlsf[i], addSat16(nlsf[i - 1], deltaMin[i]));
        }
        // Last NLSF should be no higher than 1 - NDeltaMin[L].
        nlsf[l - 1] = (short) Math.min(nlsf[l - 1], (1 << 15) - deltaMin[l]);
        // Keep NDeltaMin distance between the NLSFs.
        for (int i = l - 2; i >= 0; i--) {
            nlsf[i] = (short) Math.min(nlsf[i], nlsf[i + 1] - deltaMin[i + 1]);
        }
    }

    private static void nlsf2aFindPoly(int[] out, int[] cLsf, int cOff, int dd) {
        out[0] = 1 << 16;
        out[1] = -cLsf[cOff];
        for (int k = 1; k < dd; k++) {
            int ftmp = cLsf[cOff + 2 * k];
            out[k + 1] = (out[k - 1] << 1) - (int) rshiftRound64((long) ftmp * out[k], 16);
            for (int n = k; n > 1; n--) {
                out[n] += out[n - 2] - (int) rshiftRound64((long) ftmp * out[n - 1], 16);
            }
            out[1] -= ftmp;
        }
    }

    /** silk_NLSF2A: NLSFs to monic whitening filter coefficients in Q12, made stable if needed. */
    void nlsf2a(short[] aQ12, short[] nlsf, int d) {
        int[] ordering = d == 16 ? ORDERING16 : ORDERING10;
        // Convert LSFs to 2*cos(LSF), using a piecewise linear curve from the table.
        for (int k = 0; k < d; k++) {
            int fInt = nlsf[k] >> (15 - 7);
            int fFrac = nlsf[k] - (fInt << (15 - 7));
            int cosVal = OpusSilkTables.LSF_COS_TAB_FIX_Q12[fInt];
            int delta = OpusSilkTables.LSF_COS_TAB_FIX_Q12[fInt + 1] - cosVal;
            cosLsfQa[ordering[k]] = rshiftRound((cosVal << 8) + delta * fFrac, 20 - 16);
        }
        int dd = d >> 1;
        // Generate even and odd polynomials using convolution.
        nlsf2aFindPoly(polyP, cosLsfQa, 0, dd);
        nlsf2aFindPoly(polyQ, cosLsfQa, 1, dd);
        // Convert even and odd polynomials to int32 Q12 filter coefs.
        for (int k = 0; k < dd; k++) {
            int ptmp = polyP[k + 1] + polyP[k];
            int qtmp = polyQ[k + 1] - polyQ[k];
            a32Qa1[k] = -qtmp - ptmp;
            a32Qa1[d - k - 1] = qtmp - ptmp;
        }
        // Convert int32 coefficients to Q12 int16 coefs.
        lpcFit(aQ12, a32Qa1, 12, 17, d);
        for (int i = 0; lpcInversePredGain(aQ12, d) == 0 && i < MAX_LPC_STABILIZE_ITERATIONS; i++) {
            // Prediction coefficients are (too close to) unstable; apply bandwidth expansion on the unscaled
            // coefficients, convert to Q12 and measure again.
            bwexpander32(a32Qa1, d, 65536 - (2 << i));
            for (int k = 0; k < d; k++) {
                aQ12[k] = (short) rshiftRound(a32Qa1[k], 17 - 12);
            }
        }
    }

    /** silk_LPC_fit: converts to int16 coefficients, bandwidth-expanding until they fit. */
    private static void lpcFit(short[] aQout, int[] aQin, int qOut, int qIn, int d) {
        int idx = 0;
        int i;
        for (i = 0; i < 10; i++) {
            // Find maximum absolute value and its index.
            int maxabs = 0;
            for (int k = 0; k < d; k++) {
                int absval = abs(aQin[k]);
                if (absval > maxabs) {
                    maxabs = absval;
                    idx = k;
                }
            }
            maxabs = rshiftRound(maxabs, qIn - qOut);
            if (maxabs > 32767) {
                // Reduce magnitude of prediction coefficients.
                maxabs = Math.min(maxabs, 163838);
                int chirpQ16 = (int) (0.999 * (1L << 16) + 0.5) - (((maxabs - 32767) << 14) / ((maxabs * (idx + 1)) >> 2));
                bwexpander32(aQin, d, chirpQ16);
            } else {
                break;
            }
        }
        if (i == 10) {
            // Reached the last iteration, clip the coefficients.
            for (int k = 0; k < d; k++) {
                aQout[k] = (short) sat16(rshiftRound(aQin[k], qIn - qOut));
                aQin[k] = aQout[k] << (qIn - qOut);
            }
        } else {
            for (int k = 0; k < d; k++) {
                aQout[k] = (short) rshiftRound(aQin[k], qIn - qOut);
            }
        }
    }

    /** silk_LPC_inverse_pred_gain: 0 when the filter is (too close to) unstable. */
    int lpcInversePredGain(short[] aQ12, int order) {
        int dcResp = 0;
        // Increase Q domain of the AR coefficients.
        for (int k = 0; k < order; k++) {
            dcResp += aQ12[k];
            aTmpQa[k] = aQ12[k] << (24 - 12);
        }
        // If the DC is unstable, we don't even need to do the full calculations.
        if (dcResp >= 4096) {
            return 0;
        }
        return lpcInversePredGainQa(aTmpQa, order);
    }

    private static int lpcInversePredGainQa(int[] aQa, int order) {
        int invGainQ30 = 1 << 30;
        int k;
        for (k = order - 1; k > 0; k--) {
            // Check for stability.
            if (aQa[k] > A_LIMIT || aQa[k] < -A_LIMIT) {
                return 0;
            }
            // Set RC equal to negated AR coef.
            int rcQ31 = -(aQa[k] << (31 - 24));
            // rc_mult1_Q30 range: [ 1 : 2^30 ]
            int rcMult1Q30 = (1 << 30) - smmul(rcQ31, rcQ31);
            // Update inverse gain; invGain_Q30 range: [ 0 : 2^30 ]
            invGainQ30 = smmul(invGainQ30, rcMult1Q30) << 2;
            if (invGainQ30 < INV_PRED_GAIN_MIN_Q30) {
                return 0;
            }
            // rc_mult2 range: [ 2^30 : silk_int32_MAX ]
            int mult2Q = 32 - clz32(abs(rcMult1Q30));
            int rcMult2 = inverse32VarQ(rcMult1Q30, mult2Q + 30);
            // Update AR coefficient.
            for (int n = 0; n < (k + 1) >> 1; n++) {
                int tmp1 = aQa[n];
                int tmp2 = aQa[k - n - 1];
                long tmp64 = rshiftRound64((long) subSat32(tmp1, mul32FracQ(tmp2, rcQ31, 31)) * rcMult2, mult2Q);
                if (tmp64 > Integer.MAX_VALUE || tmp64 < Integer.MIN_VALUE) {
                    return 0;
                }
                aQa[n] = (int) tmp64;
                tmp64 = rshiftRound64((long) subSat32(tmp2, mul32FracQ(tmp1, rcQ31, 31)) * rcMult2, mult2Q);
                if (tmp64 > Integer.MAX_VALUE || tmp64 < Integer.MIN_VALUE) {
                    return 0;
                }
                aQa[k - n - 1] = (int) tmp64;
            }
        }
        // Check for stability.
        if (aQa[k] > A_LIMIT || aQa[k] < -A_LIMIT) {
            return 0;
        }
        // Set RC equal to negated AR coef.
        int rcQ31 = -(aQa[0] << (31 - 24));
        int rcMult1Q30 = (1 << 30) - smmul(rcQ31, rcQ31);
        invGainQ30 = smmul(invGainQ30, rcMult1Q30) << 2;
        if (invGainQ30 < INV_PRED_GAIN_MIN_Q30) {
            return 0;
        }
        return invGainQ30;
    }

    private static int mul32FracQ(int a32, int b32, int q) {
        return (int) rshiftRound64((long) a32 * b32, q);
    }

    /** silk_bwexpander: chirps an int16 AR filter. */
    static void bwexpander(short[] ar, int d, int chirpQ16) {
        int chirpMinusOneQ16 = chirpQ16 - 65536;
        // Note: silk_SMULWB is not used here because its bias can lead to unstable filters.
        for (int i = 0; i < d - 1; i++) {
            ar[i] = (short) rshiftRound(chirpQ16 * ar[i], 16);
            chirpQ16 += rshiftRound(chirpQ16 * chirpMinusOneQ16, 16);
        }
        ar[d - 1] = (short) rshiftRound(chirpQ16 * ar[d - 1], 16);
    }

    /** silk_bwexpander_32. */
    static void bwexpander32(int[] ar, int d, int chirpQ16) {
        int chirpMinusOneQ16 = chirpQ16 - 65536;
        for (int i = 0; i < d - 1; i++) {
            ar[i] = smulww(chirpQ16, ar[i]);
            chirpQ16 += rshiftRound(chirpQ16 * chirpMinusOneQ16, 16);
        }
        ar[d - 1] = smulww(chirpQ16, ar[d - 1]);
    }

    /** silk_LPC_analysis_filter: the whitening filter, first d outputs zero. */
    private static void lpcAnalysisFilter(short[] out, int outOff, short[] in, int inOff, short[] b, int len, int d) {
        for (int ix = d; ix < len; ix++) {
            int inPtr = inOff + ix - 1;
            int out32Q12 = smulbb(in[inPtr], b[0]);
            // Allowing wrap around so that two wraps can cancel each other. The rare cases where the result wraps
            // around can only be triggered by invalid streams.
            out32Q12 = out32Q12 + in[inPtr - 1] * b[1];
            out32Q12 = out32Q12 + in[inPtr - 2] * b[2];
            out32Q12 = out32Q12 + in[inPtr - 3] * b[3];
            out32Q12 = out32Q12 + in[inPtr - 4] * b[4];
            out32Q12 = out32Q12 + in[inPtr - 5] * b[5];
            for (int j = 6; j < d; j += 2) {
                out32Q12 = out32Q12 + in[inPtr - j] * b[j];
                out32Q12 = out32Q12 + in[inPtr - j - 1] * b[j + 1];
            }
            // Subtract prediction.
            out32Q12 = (in[inPtr + 1] << 12) - out32Q12;
            // Scale to Q0 and saturate.
            int out32 = rshiftRound(out32Q12, 12);
            out[outOff + ix] = (short) sat16(out32);
        }
        // Set first d output samples to zero.
        Arrays.fill(out, outOff, outOff + d, (short) 0);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Synthesis

    /** silk_decode_core: excitation, long-term (pitch) and short-term (LPC) synthesis. */
    private void decodeCore(short[] xq, int xqOff, short[] pulseSignal) {
        int lag = 0;
        int offsetQ10 = OpusSilkTables.QUANTIZATION_OFFSETS_Q10[(signalType >> 1) * 2 + quantOffsetType];
        boolean nlsfInterpolationFlag = nlsfInterpCoefQ2 < 1 << 2;

        // Decode excitation.
        int randSeed = seed;
        for (int i = 0; i < frameLength; i++) {
            randSeed = rand(randSeed);
            excQ14[i] = pulseSignal[i] << 14;
            if (excQ14[i] > 0) {
                excQ14[i] -= QUANT_LEVEL_ADJUST_Q10 << 4;
            } else if (excQ14[i] < 0) {
                excQ14[i] += QUANT_LEVEL_ADJUST_Q10 << 4;
            }
            excQ14[i] += offsetQ10 << 4;
            if (randSeed < 0) {
                excQ14[i] = -excQ14[i];
            }
            randSeed = randSeed + pulseSignal[i];
        }

        // Copy LPC state.
        System.arraycopy(sLpcQ14Buf, 0, sLpcQ14, 0, MAX_LPC_ORDER);
        int pexc = 0;
        int pxq = xqOff;
        int sLtpBufIdx = ltpMemLength;
        // Loop over subframes.
        for (int k = 0; k < nbSubfr; k++) {
            int[] presArr = resQ14;
            int pres = 0;
            short[] aQ12 = predCoefQ12[k >> 1];
            // Preload LPC coefficients to an array (a small performance gain in C).
            System.arraycopy(aQ12, 0, aQ12Tmp, 0, lpcOrder);
            int bQ14 = k * LTP_ORDER;
            int sigType = signalType;
            int gainQ10 = gainsQ16[k] >> 6;
            int invGainQ31 = inverse32VarQ(gainsQ16[k], 47);

            // Calculate gain adjustment factor.
            int gainAdjQ16;
            if (gainsQ16[k] != prevGainQ16) {
                gainAdjQ16 = div32VarQ(prevGainQ16, gainsQ16[k], 16);
                // Scale short term state.
                for (int i = 0; i < MAX_LPC_ORDER; i++) {
                    sLpcQ14[i] = smulww(gainAdjQ16, sLpcQ14[i]);
                }
            } else {
                gainAdjQ16 = 1 << 16;
            }
            // Save inv_gain.
            prevGainQ16 = gainsQ16[k];

            // Avoid abrupt transition from voiced PLC to unvoiced normal decoding.
            if (lossCnt != 0 && prevSignalType == TYPE_VOICED && signalType != TYPE_VOICED && k < MAX_NB_SUBFR / 2) {
                Arrays.fill(ltpCoefQ14, bQ14, bQ14 + LTP_ORDER, (short) 0);
                ltpCoefQ14[bQ14 + LTP_ORDER / 2] = (short) (int) (0.25 * (1 << 14) + 0.5);
                sigType = TYPE_VOICED;
                pitchL[k] = lagPrev;
            }

            if (sigType == TYPE_VOICED) {
                // Voiced.
                lag = pitchL[k];
                // Re-whitening.
                if (k == 0 || (k == 2 && nlsfInterpolationFlag)) {
                    // Rewhiten with new A coefs.
                    int startIdx = ltpMemLength - lag - lpcOrder - LTP_ORDER / 2;
                    if (k == 2) {
                        System.arraycopy(xq, xqOff, outBuf, ltpMemLength, 2 * subfrLength);
                    }
                    lpcAnalysisFilter(sLtp, startIdx, outBuf, startIdx + k * subfrLength, aQ12,
                            ltpMemLength - startIdx, lpcOrder);
                    // After rewhitening the LTP state is unscaled.
                    if (k == 0) {
                        // Do LTP downscaling to reduce inter-packet dependency.
                        invGainQ31 = smulwb(invGainQ31, ltpScaleQ14) << 2;
                    }
                    for (int i = 0; i < lag + LTP_ORDER / 2; i++) {
                        sLtpQ15[sLtpBufIdx - i - 1] = smulwb(invGainQ31, sLtp[ltpMemLength - i - 1]);
                    }
                } else {
                    // Update LTP state when Gain changes.
                    if (gainAdjQ16 != 1 << 16) {
                        for (int i = 0; i < lag + LTP_ORDER / 2; i++) {
                            sLtpQ15[sLtpBufIdx - i - 1] = smulww(gainAdjQ16, sLtpQ15[sLtpBufIdx - i - 1]);
                        }
                    }
                }
            }

            // Long-term prediction.
            if (sigType == TYPE_VOICED) {
                int predLag = sLtpBufIdx - lag + LTP_ORDER / 2;
                short b0 = ltpCoefQ14[bQ14];
                short b1 = ltpCoefQ14[bQ14 + 1];
                short b2 = ltpCoefQ14[bQ14 + 2];
                short b3 = ltpCoefQ14[bQ14 + 3];
                short b4 = ltpCoefQ14[bQ14 + 4];
                for (int i = 0; i < subfrLength; i++) {
                    // Unrolled loop. The 2 avoids introducing a bias because silk_SMLAWB() always rounds to -inf.
                    int ltpPredQ13 = 2;
                    ltpPredQ13 = smlawb(ltpPredQ13, sLtpQ15[predLag], b0);
                    ltpPredQ13 = smlawb(ltpPredQ13, sLtpQ15[predLag - 1], b1);
                    ltpPredQ13 = smlawb(ltpPredQ13, sLtpQ15[predLag - 2], b2);
                    ltpPredQ13 = smlawb(ltpPredQ13, sLtpQ15[predLag - 3], b3);
                    ltpPredQ13 = smlawb(ltpPredQ13, sLtpQ15[predLag - 4], b4);
                    predLag++;
                    // Generate LPC excitation.
                    presArr[i] = excQ14[pexc + i] + (ltpPredQ13 << 1);
                    // Update states.
                    sLtpQ15[sLtpBufIdx] = presArr[i] << 1;
                    sLtpBufIdx++;
                }
            } else {
                presArr = excQ14;
                pres = pexc;
            }

            short[] a = aQ12Tmp;
            for (int i = 0; i < subfrLength; i++) {
                // Short-term prediction. The order/2 avoids introducing a bias because silk_SMLAWB() always rounds
                // to -inf.
                int lpcPredQ10 = lpcOrder >> 1;
                int s = MAX_LPC_ORDER + i;
                lpcPredQ10 = smlawb(lpcPredQ10, sLpcQ14[s - 1], a[0]);
                lpcPredQ10 = smlawb(lpcPredQ10, sLpcQ14[s - 2], a[1]);
                lpcPredQ10 = smlawb(lpcPredQ10, sLpcQ14[s - 3], a[2]);
                lpcPredQ10 = smlawb(lpcPredQ10, sLpcQ14[s - 4], a[3]);
                lpcPredQ10 = smlawb(lpcPredQ10, sLpcQ14[s - 5], a[4]);
                lpcPredQ10 = smlawb(lpcPredQ10, sLpcQ14[s - 6], a[5]);
                lpcPredQ10 = smlawb(lpcPredQ10, sLpcQ14[s - 7], a[6]);
                lpcPredQ10 = smlawb(lpcPredQ10, sLpcQ14[s - 8], a[7]);
                lpcPredQ10 = smlawb(lpcPredQ10, sLpcQ14[s - 9], a[8]);
                lpcPredQ10 = smlawb(lpcPredQ10, sLpcQ14[s - 10], a[9]);
                if (lpcOrder == 16) {
                    lpcPredQ10 = smlawb(lpcPredQ10, sLpcQ14[s - 11], a[10]);
                    lpcPredQ10 = smlawb(lpcPredQ10, sLpcQ14[s - 12], a[11]);
                    lpcPredQ10 = smlawb(lpcPredQ10, sLpcQ14[s - 13], a[12]);
                    lpcPredQ10 = smlawb(lpcPredQ10, sLpcQ14[s - 14], a[13]);
                    lpcPredQ10 = smlawb(lpcPredQ10, sLpcQ14[s - 15], a[14]);
                    lpcPredQ10 = smlawb(lpcPredQ10, sLpcQ14[s - 16], a[15]);
                }
                // Add prediction to LPC excitation.
                sLpcQ14[s] = addSat32(presArr[pres + i], lshiftSat32(lpcPredQ10, 4));
                // Scale with gain.
                xq[pxq + i] = (short) sat16(rshiftRound(smulww(sLpcQ14[s], gainQ10), 8));
            }
            // Update LPC filter state.
            System.arraycopy(sLpcQ14, subfrLength, sLpcQ14, 0, MAX_LPC_ORDER);
            pexc += subfrLength;
            pxq += subfrLength;
        }
        // Save LPC state.
        System.arraycopy(sLpcQ14, 0, sLpcQ14Buf, 0, MAX_LPC_ORDER);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Packet loss concealment (PLC.c)

    private void plcReset() {
        plcPitchLQ8 = frameLength << (8 - 1);
        plcPrevGainQ16[0] = 1 << 16;
        plcPrevGainQ16[1] = 1 << 16;
        plcSubfrLength = 20;
        plcNbSubfr = 2;
    }

    private void plc(short[] frame, int off, boolean lost) {
        // PLC control function.
        if (fsKHz != plcFsKHz) {
            plcReset();
            plcFsKHz = fsKHz;
        }
        if (lost) {
            // Generate signal.
            plcConceal(frame, off);
            lossCnt++;
        } else {
            // Update state.
            plcUpdate();
        }
    }

    private void plcUpdate() {
        // Update parameters used in case of packet loss.
        prevSignalType = signalType;
        int ltpGainQ14 = 0;
        if (signalType == TYPE_VOICED) {
            // Find the parameters for the last subframe which contains a pitch pulse.
            for (int j = 0; j * subfrLength < pitchL[nbSubfr - 1]; j++) {
                if (j == nbSubfr) {
                    break;
                }
                int tempLtpGainQ14 = 0;
                for (int i = 0; i < LTP_ORDER; i++) {
                    tempLtpGainQ14 += ltpCoefQ14[(nbSubfr - 1 - j) * LTP_ORDER + i];
                }
                if (tempLtpGainQ14 > ltpGainQ14) {
                    ltpGainQ14 = tempLtpGainQ14;
                    System.arraycopy(ltpCoefQ14, (nbSubfr - 1 - j) * LTP_ORDER, plcLtpCoefQ14, 0, LTP_ORDER);
                    plcPitchLQ8 = pitchL[nbSubfr - 1 - j] << 8;
                }
            }
            Arrays.fill(plcLtpCoefQ14, (short) 0);
            plcLtpCoefQ14[LTP_ORDER / 2] = (short) ltpGainQ14;
            // Limit LT coefs.
            if (ltpGainQ14 < V_PITCH_GAIN_START_MIN_Q14) {
                int tmp = V_PITCH_GAIN_START_MIN_Q14 << 10;
                int scaleQ10 = tmp / Math.max(ltpGainQ14, 1);
                for (int i = 0; i < LTP_ORDER; i++) {
                    plcLtpCoefQ14[i] = (short) (smulbb(plcLtpCoefQ14[i], scaleQ10) >> 10);
                }
            } else if (ltpGainQ14 > V_PITCH_GAIN_START_MAX_Q14) {
                int tmp = V_PITCH_GAIN_START_MAX_Q14 << 14;
                int scaleQ14 = tmp / Math.max(ltpGainQ14, 1);
                for (int i = 0; i < LTP_ORDER; i++) {
                    plcLtpCoefQ14[i] = (short) (smulbb(plcLtpCoefQ14[i], scaleQ14) >> 14);
                }
            }
        } else {
            plcPitchLQ8 = smulbb(fsKHz, 18) << 8;
            Arrays.fill(plcLtpCoefQ14, (short) 0);
        }
        // Save LPC coefficients.
        System.arraycopy(predCoefQ12[1], 0, plcPrevLpcQ12, 0, lpcOrder);
        plcPrevLtpScaleQ14 = (short) ltpScaleQ14;
        // Save last two gains.
        plcPrevGainQ16[0] = gainsQ16[nbSubfr - 2];
        plcPrevGainQ16[1] = gainsQ16[nbSubfr - 1];
        plcSubfrLength = subfrLength;
        plcNbSubfr = nbSubfr;
    }

    private void plcEnergy(int[] prevGainQ10) {
        // Scale previous excitation signal.
        int p = 0;
        for (int k = 0; k < 2; k++) {
            for (int i = 0; i < subfrLength; i++) {
                excBuf[p + i] = (short) sat16(smulww(excQ14[i + (k + nbSubfr - 2) * subfrLength], prevGainQ10[k]) >> 8);
            }
            p += subfrLength;
        }
        // Find the subframe with lowest energy of the last two and use that as random noise generator.
        sumSqrShift(energyOut, 0, excBuf, 0, subfrLength);
        sumSqrShift(energyOut, 1, excBuf, subfrLength, subfrLength);
    }

    private final int[] plcShifts = new int[2];

    private void plcConceal(short[] frame, int off) {
        int[] prevGainQ10 = {plcPrevGainQ16[0] >> 6, plcPrevGainQ16[1] >> 6};
        if (firstFrameAfterReset) {
            Arrays.fill(plcPrevLpcQ12, (short) 0);
        }
        plcEnergy(prevGainQ10);
        int energy1 = energyOut[0];
        int energy2 = energyOut[1];
        int shift1 = plcShifts[0];
        int shift2 = plcShifts[1];
        int randPtr;
        if ((energy1 >> shift2) < (energy2 >> shift1)) {
            // First sub-frame has lowest energy.
            randPtr = Math.max(0, (plcNbSubfr - 1) * plcSubfrLength - RAND_BUF_SIZE);
        } else {
            // Second sub-frame has lowest energy.
            randPtr = Math.max(0, plcNbSubfr * plcSubfrLength - RAND_BUF_SIZE);
        }
        // Set up gain to random noise component.
        short[] bQ14 = plcLtpCoefQ14;
        short randScaleQ14 = plcRandScaleQ14;
        // Set up attenuation gains.
        int harmGainQ15 = HARM_ATT_Q15[Math.min(1, lossCnt)];
        int randGainQ15;
        if (prevSignalType == TYPE_VOICED) {
            randGainQ15 = PLC_RAND_ATTENUATE_V_Q15[Math.min(1, lossCnt)];
        } else {
            randGainQ15 = PLC_RAND_ATTENUATE_UV_Q15[Math.min(1, lossCnt)];
        }
        // LPC concealment. Apply BWE to previous LPC.
        bwexpander(plcPrevLpcQ12, lpcOrder, BWE_COEF_Q16);
        // Preload LPC coefficients to an array.
        short[] aQ12 = new short[MAX_LPC_ORDER];
        System.arraycopy(plcPrevLpcQ12, 0, aQ12, 0, lpcOrder);
        // First lost frame.
        if (lossCnt == 0) {
            randScaleQ14 = 1 << 14;
            // Reduce random noise gain for voiced frames.
            if (prevSignalType == TYPE_VOICED) {
                for (int i = 0; i < LTP_ORDER; i++) {
                    randScaleQ14 -= bQ14[i];
                }
                randScaleQ14 = (short) Math.max(3277, randScaleQ14);
                randScaleQ14 = (short) (smulbb(randScaleQ14, plcPrevLtpScaleQ14) >> 14);
            } else {
                // Reduce random noise for unvoiced frames with high LPC gain.
                int invGainQ30 = lpcInversePredGain(plcPrevLpcQ12, lpcOrder);
                int downScaleQ30 = Math.min((1 << 30) >> LOG2_INV_LPC_GAIN_HIGH_THRES, invGainQ30);
                downScaleQ30 = Math.max((1 << 30) >> LOG2_INV_LPC_GAIN_LOW_THRES, downScaleQ30);
                downScaleQ30 = downScaleQ30 << LOG2_INV_LPC_GAIN_HIGH_THRES;
                randGainQ15 = smulwb(downScaleQ30, randGainQ15) >> 14;
            }
        }
        int randSeed = plcRandSeed;
        int lag = rshiftRound(plcPitchLQ8, 8);
        int sLtpBufIdx = ltpMemLength;
        // Rewhiten LTP state.
        int idx = ltpMemLength - lag - lpcOrder - LTP_ORDER / 2;
        lpcAnalysisFilter(sLtp, idx, outBuf, idx, aQ12, ltpMemLength - idx, lpcOrder);
        // Scale LTP state.
        int invGainQ30 = inverse32VarQ(plcPrevGainQ16[1], 46);
        invGainQ30 = Math.min(invGainQ30, Integer.MAX_VALUE >> 1);
        int[] sLtpQ14 = sLtpQ15;
        for (int i = idx + lpcOrder; i < ltpMemLength; i++) {
            sLtpQ14[i] = smulwb(invGainQ30, sLtp[i]);
        }
        // LTP synthesis filtering.
        for (int k = 0; k < nbSubfr; k++) {
            int predLag = sLtpBufIdx - lag + LTP_ORDER / 2;
            for (int i = 0; i < subfrLength; i++) {
                // Unrolled loop. The 2 avoids introducing a bias because silk_SMLAWB() always rounds to -inf.
                int ltpPredQ12 = 2;
                ltpPredQ12 = smlawb(ltpPredQ12, sLtpQ14[predLag], bQ14[0]);
                ltpPredQ12 = smlawb(ltpPredQ12, sLtpQ14[predLag - 1], bQ14[1]);
                ltpPredQ12 = smlawb(ltpPredQ12, sLtpQ14[predLag - 2], bQ14[2]);
                ltpPredQ12 = smlawb(ltpPredQ12, sLtpQ14[predLag - 3], bQ14[3]);
                ltpPredQ12 = smlawb(ltpPredQ12, sLtpQ14[predLag - 4], bQ14[4]);
                predLag++;
                // Generate LPC excitation.
                randSeed = rand(randSeed);
                idx = (randSeed >> 25) & RAND_BUF_MASK;
                sLtpQ14[sLtpBufIdx] = smlawb(ltpPredQ12, excQ14[randPtr + idx], randScaleQ14) << 2;
                sLtpBufIdx++;
            }
            // Gradually reduce LTP gain.
            for (int j = 0; j < LTP_ORDER; j++) {
                bQ14[j] = (short) (smulbb(harmGainQ15, bQ14[j]) >> 15);
            }
            // Gradually reduce excitation gain.
            randScaleQ14 = (short) (smulbb(randScaleQ14, randGainQ15) >> 15);
            // Slowly increase pitch lag.
            plcPitchLQ8 = smlawb(plcPitchLQ8, plcPitchLQ8, PITCH_DRIFT_FAC_Q16);
            plcPitchLQ8 = Math.min(plcPitchLQ8, smulbb(MAX_PITCH_LAG_MS, fsKHz) << 8);
            lag = rshiftRound(plcPitchLQ8, 8);
        }
        // LPC synthesis filtering.
        int sLpcPtr = ltpMemLength - MAX_LPC_ORDER;
        // Copy LPC state.
        System.arraycopy(sLpcQ14Buf, 0, sLtpQ14, sLpcPtr, MAX_LPC_ORDER);
        for (int i = 0; i < frameLength; i++) {
            // Partly unrolled. The order/2 avoids introducing a bias because silk_SMLAWB() always rounds to -inf.
            int s = sLpcPtr + MAX_LPC_ORDER + i;
            int lpcPredQ10 = lpcOrder >> 1;
            lpcPredQ10 = smlawb(lpcPredQ10, sLtpQ14[s - 1], aQ12[0]);
            lpcPredQ10 = smlawb(lpcPredQ10, sLtpQ14[s - 2], aQ12[1]);
            lpcPredQ10 = smlawb(lpcPredQ10, sLtpQ14[s - 3], aQ12[2]);
            lpcPredQ10 = smlawb(lpcPredQ10, sLtpQ14[s - 4], aQ12[3]);
            lpcPredQ10 = smlawb(lpcPredQ10, sLtpQ14[s - 5], aQ12[4]);
            lpcPredQ10 = smlawb(lpcPredQ10, sLtpQ14[s - 6], aQ12[5]);
            lpcPredQ10 = smlawb(lpcPredQ10, sLtpQ14[s - 7], aQ12[6]);
            lpcPredQ10 = smlawb(lpcPredQ10, sLtpQ14[s - 8], aQ12[7]);
            lpcPredQ10 = smlawb(lpcPredQ10, sLtpQ14[s - 9], aQ12[8]);
            lpcPredQ10 = smlawb(lpcPredQ10, sLtpQ14[s - 10], aQ12[9]);
            for (int j = 10; j < lpcOrder; j++) {
                lpcPredQ10 = smlawb(lpcPredQ10, sLtpQ14[s - j - 1], aQ12[j]);
            }
            // Add prediction to LPC excitation.
            sLtpQ14[s] = addSat32(sLtpQ14[s], lshiftSat32(lpcPredQ10, 4));
            // Scale with gain.
            frame[off + i] = (short) sat16(sat16(rshiftRound(smulww(sLtpQ14[s], prevGainQ10[1]), 8)));
        }
        // Save LPC state.
        System.arraycopy(sLtpQ14, sLpcPtr + frameLength, sLpcQ14Buf, 0, MAX_LPC_ORDER);
        // Update states.
        plcRandSeed = randSeed;
        plcRandScaleQ14 = randScaleQ14;
        for (int i = 0; i < MAX_NB_SUBFR; i++) {
            pitchL[i] = lag;
        }
    }

    /** silk_PLC_glue_frames: fades a good frame in after concealed ones when its energy jumps. */
    private void plcGlueFrames(short[] frame, int off, int length) {
        if (lossCnt != 0) {
            // Calculate energy in concealed residual.
            sumSqrShift(energyOut, 0, frame, off, length);
            plcConcEnergy = energyOut[0];
            plcConcEnergyShift = plcShifts[0];
            plcLastFrameLost = true;
        } else {
            if (plcLastFrameLost) {
                // Calculate residual in decoded signal if last frame was lost.
                sumSqrShift(energyOut, 0, frame, off, length);
                int energy = energyOut[0];
                int energyShift = plcShifts[0];
                // Normalize energies.
                if (energyShift > plcConcEnergyShift) {
                    plcConcEnergy = plcConcEnergy >> (energyShift - plcConcEnergyShift);
                } else if (energyShift < plcConcEnergyShift) {
                    energy = energy >> (plcConcEnergyShift - energyShift);
                }
                // Fade in the energy difference.
                if (energy > plcConcEnergy) {
                    int lz = clz32(plcConcEnergy);
                    lz = lz - 1;
                    plcConcEnergy = plcConcEnergy << lz;
                    energy = energy >> Math.max(24 - lz, 0);
                    int fracQ24 = plcConcEnergy / Math.max(energy, 1);
                    int gainQ16 = sqrtApprox(fracQ24) << 4;
                    int slopeQ16 = ((1 << 16) - gainQ16) / length;
                    // Make slope 4x steeper to avoid missing onsets after DTX.
                    slopeQ16 = slopeQ16 << 2;
                    for (int i = 0; i < length; i++) {
                        frame[off + i] = (short) smulwb(gainQ16, frame[off + i]);
                        gainQ16 += slopeQ16;
                        if (gainQ16 > 1 << 16) {
                            break;
                        }
                    }
                }
            }
            plcLastFrameLost = false;
        }
    }

    /** silk_sum_sqr_shift: energy with the right shift that keeps it in 32 bits; the shift goes to plcShifts. */
    private void sumSqrShift(int[] energy, int slot, short[] x, int off, int len) {
        // Do a first run with the maximum shift we could have.
        int shft = 31 - clz32(len);
        // Let's be conservative with rounding and start with nrg=len.
        int nrg = len;
        int i;
        for (i = 0; i < len - 1; i += 2) {
            int nrgTmp = x[off + i] * x[off + i];
            nrgTmp = nrgTmp + x[off + i + 1] * x[off + i + 1];
            nrg = nrg + (nrgTmp >>> shft);
        }
        if (i < len) {
            // One sample left to process.
            int nrgTmp = x[off + i] * x[off + i];
            nrg = nrg + (nrgTmp >>> shft);
        }
        // Make sure the result will fit in a 32-bit signed integer with two bits of headroom.
        shft = Math.max(0, shft + 3 - clz32(nrg));
        nrg = 0;
        for (i = 0; i < len - 1; i += 2) {
            int nrgTmp = x[off + i] * x[off + i];
            nrgTmp = nrgTmp + x[off + i + 1] * x[off + i + 1];
            nrg = nrg + (nrgTmp >>> shft);
        }
        if (i < len) {
            // One sample left to process.
            int nrgTmp = x[off + i] * x[off + i];
            nrg = nrg + (nrgTmp >>> shft);
        }
        plcShifts[slot] = shft;
        energy[slot] = nrg;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Comfort noise (CNG.c)

    private void cngReset() {
        int nlsfStepQ15 = 32767 / (lpcOrder + 1);
        int nlsfAccQ15 = 0;
        for (int i = 0; i < lpcOrder; i++) {
            nlsfAccQ15 += nlsfStepQ15;
            cngSmthNlsfQ15[i] = (short) nlsfAccQ15;
        }
        cngSmthGainQ16 = 0;
        cngRandSeed = 3176576;
    }

    private void cng(short[] frame, int off, int length) {
        if (fsKHz != cngFsKHz) {
            // Reset state.
            cngReset();
            cngFsKHz = fsKHz;
        }
        if (lossCnt == 0 && prevSignalType == TYPE_NO_VOICE_ACTIVITY) {
            // Update CNG parameters. Smoothing of LSFs.
            for (int i = 0; i < lpcOrder; i++) {
                cngSmthNlsfQ15[i] = (short) (cngSmthNlsfQ15[i]
                        + smulwb(prevNlsfQ15[i] - cngSmthNlsfQ15[i], CNG_NLSF_SMTH_Q16));
            }
            // Find the subframe with the highest gain.
            int maxGainQ16 = 0;
            int subfr = 0;
            for (int i = 0; i < nbSubfr; i++) {
                if (gainsQ16[i] > maxGainQ16) {
                    maxGainQ16 = gainsQ16[i];
                    subfr = i;
                }
            }
            // Update CNG excitation buffer with excitation from this subframe.
            System.arraycopy(cngExcBufQ14, 0, cngExcBufQ14, subfrLength, (nbSubfr - 1) * subfrLength);
            System.arraycopy(excQ14, subfr * subfrLength, cngExcBufQ14, 0, subfrLength);
            // Smooth gains.
            for (int i = 0; i < nbSubfr; i++) {
                cngSmthGainQ16 += smulwb(gainsQ16[i] - cngSmthGainQ16, CNG_GAIN_SMTH_Q16);
                // If the smoothed gain is 3 dB greater than this subframe's gain, use this subframe's gain to adapt
                // faster.
                if (smulww(cngSmthGainQ16, CNG_GAIN_SMTH_THRESHOLD_Q16) > gainsQ16[i]) {
                    cngSmthGainQ16 = gainsQ16[i];
                }
            }
        }
        // Add CNG when packet is lost or during DTX.
        if (lossCnt != 0) {
            // Generate CNG excitation.
            int gainQ16 = smulww(plcRandScaleQ14, plcPrevGainQ16[1]);
            if (gainQ16 >= (1 << 21) || cngSmthGainQ16 > (1 << 23)) {
                gainQ16 = smultt(gainQ16, gainQ16);
                gainQ16 = smultt(cngSmthGainQ16, cngSmthGainQ16) - (gainQ16 << 5);
                gainQ16 = sqrtApprox(gainQ16) << 16;
            } else {
                gainQ16 = smulww(gainQ16, gainQ16);
                gainQ16 = smulww(cngSmthGainQ16, cngSmthGainQ16) - (gainQ16 << 5);
                gainQ16 = sqrtApprox(gainQ16) << 8;
            }
            int gainQ10 = gainQ16 >> 6;
            // silk_CNG_exc
            int excMask = CNG_BUF_MASK_MAX;
            while (excMask > length) {
                excMask = excMask >> 1;
            }
            int s = cngRandSeed;
            for (int i = 0; i < length; i++) {
                s = rand(s);
                int idx = (s >> 24) & excMask;
                cngSigQ14[MAX_LPC_ORDER + i] = cngExcBufQ14[idx];
            }
            cngRandSeed = s;
            // Convert CNG NLSF to filter representation.
            short[] aQ12 = new short[MAX_LPC_ORDER];
            nlsf2a(aQ12, cngSmthNlsfQ15, lpcOrder);
            // Generate CNG signal, by synthesis filtering.
            System.arraycopy(cngSynthState, 0, cngSigQ14, 0, MAX_LPC_ORDER);
            for (int i = 0; i < length; i++) {
                int p = MAX_LPC_ORDER + i;
                // The order/2 avoids introducing a bias because silk_SMLAWB() always rounds to -inf.
                int lpcPredQ10 = lpcOrder >> 1;
                lpcPredQ10 = smlawb(lpcPredQ10, cngSigQ14[p - 1], aQ12[0]);
                lpcPredQ10 = smlawb(lpcPredQ10, cngSigQ14[p - 2], aQ12[1]);
                lpcPredQ10 = smlawb(lpcPredQ10, cngSigQ14[p - 3], aQ12[2]);
                lpcPredQ10 = smlawb(lpcPredQ10, cngSigQ14[p - 4], aQ12[3]);
                lpcPredQ10 = smlawb(lpcPredQ10, cngSigQ14[p - 5], aQ12[4]);
                lpcPredQ10 = smlawb(lpcPredQ10, cngSigQ14[p - 6], aQ12[5]);
                lpcPredQ10 = smlawb(lpcPredQ10, cngSigQ14[p - 7], aQ12[6]);
                lpcPredQ10 = smlawb(lpcPredQ10, cngSigQ14[p - 8], aQ12[7]);
                lpcPredQ10 = smlawb(lpcPredQ10, cngSigQ14[p - 9], aQ12[8]);
                lpcPredQ10 = smlawb(lpcPredQ10, cngSigQ14[p - 10], aQ12[9]);
                if (lpcOrder == 16) {
                    lpcPredQ10 = smlawb(lpcPredQ10, cngSigQ14[p - 11], aQ12[10]);
                    lpcPredQ10 = smlawb(lpcPredQ10, cngSigQ14[p - 12], aQ12[11]);
                    lpcPredQ10 = smlawb(lpcPredQ10, cngSigQ14[p - 13], aQ12[12]);
                    lpcPredQ10 = smlawb(lpcPredQ10, cngSigQ14[p - 14], aQ12[13]);
                    lpcPredQ10 = smlawb(lpcPredQ10, cngSigQ14[p - 15], aQ12[14]);
                    lpcPredQ10 = smlawb(lpcPredQ10, cngSigQ14[p - 16], aQ12[15]);
                }
                // Update states.
                cngSigQ14[p] = addSat32(cngSigQ14[p], lshiftSat32(lpcPredQ10, 4));
                // Scale with gain and add to input signal.
                frame[off + i] = addSat16(frame[off + i], sat16(rshiftRound(smulww(cngSigQ14[p], gainQ10), 8)));
            }
            System.arraycopy(cngSigQ14, length, cngSynthState, 0, MAX_LPC_ORDER);
        } else {
            Arrays.fill(cngSynthState, 0, lpcOrder, 0);
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Fixed-point helpers (silk/macros.h, SigProc_FIX.h, Inlines.h, log2lin.c), 64-bit forms.

    static int smulwb(int a, int b) {
        return (int) ((a * (long) (short) b) >> 16);
    }

    static int smlawb(int a, int b, int c) {
        return (int) (a + ((b * (long) (short) c) >> 16));
    }

    static int smulbb(int a, int b) {
        return (short) a * (short) b;
    }

    static int smlabb(int a, int b, int c) {
        return a + (short) b * (short) c;
    }

    static int smulww(int a, int b) {
        return (int) (((long) a * b) >> 16);
    }

    static int smultt(int a, int b) {
        return (a >> 16) * (b >> 16);
    }

    static int smmul(int a, int b) {
        return (int) (((long) a * b) >> 32);
    }

    static int rshiftRound(int a, int shift) {
        return shift == 1 ? (a >> 1) + (a & 1) : ((a >> (shift - 1)) + 1) >> 1;
    }

    static long rshiftRound64(long a, int shift) {
        return shift == 1 ? (a >> 1) + (a & 1) : ((a >> (shift - 1)) + 1) >> 1;
    }

    static int sat16(int a) {
        return a > 32767 ? 32767 : (a < -32768 ? -32768 : a);
    }

    static short addSat16(int a, int b) {
        return (short) sat16(a + b);
    }

    static int limit(int a, int l1, int l2) {
        return l1 > l2 ? (a > l1 ? l1 : (a < l2 ? l2 : a)) : (a > l2 ? l2 : (a < l1 ? l1 : a));
    }

    static int lshiftSat32(int a, int shift) {
        return limit(a, Integer.MIN_VALUE >> shift, Integer.MAX_VALUE >> shift) << shift;
    }

    static int addSat32(int a, int b) {
        long r = (long) a + b;
        return r > Integer.MAX_VALUE ? Integer.MAX_VALUE : (r < Integer.MIN_VALUE ? Integer.MIN_VALUE : (int) r);
    }

    static int subSat32(int a, int b) {
        long r = (long) a - b;
        return r > Integer.MAX_VALUE ? Integer.MAX_VALUE : (r < Integer.MIN_VALUE ? Integer.MIN_VALUE : (int) r);
    }

    static int abs(int a) {
        return a > 0 ? a : -a;
    }

    static int clz32(int a) {
        return Integer.numberOfLeadingZeros(a);
    }

    static int rand(int seed) {
        return 907633515 + seed * 196314165;
    }

    private static int ror32(int a32, int rot) {
        if (rot == 0) {
            return a32;
        } else if (rot < 0) {
            int m = -rot;
            return (a32 << m) | (a32 >>> (32 - m));
        }
        return (a32 << (32 - rot)) | (a32 >>> rot);
    }

    /** silk_SQRT_APPROX: a square root good to a few percent. */
    static int sqrtApprox(int x) {
        if (x <= 0) {
            return 0;
        }
        int lz = clz32(x);
        int fracQ7 = ror32(x, 24 - lz) & 0x7f;
        int y = (lz & 1) != 0 ? 32768 : 46214;
        // Get scaling right.
        y >>= lz >> 1;
        // Increment using fractional part of input.
        y = smlawb(y, y, smulbb(213, fracQ7));
        return y;
    }

    /** silk_DIV32_varQ: a good approximation of (a32 << Qres) / b32. */
    static int div32VarQ(int a32, int b32, int qRes) {
        // Compute number of bits head room and normalize inputs.
        int aHeadrm = clz32(abs(a32)) - 1;
        int a32Nrm = a32 << aHeadrm;
        int bHeadrm = clz32(abs(b32)) - 1;
        int b32Nrm = b32 << bHeadrm;
        // Inverse of b32, with 14 bits of precision.
        int b32Inv = (Integer.MAX_VALUE >> 2) / (b32Nrm >> 16);
        // First approximation.
        int result = smulwb(a32Nrm, b32Inv);
        // Compute residual by subtracting product of denominator and first approximation. It's OK to overflow
        // because the final value of a32_nrm should always be small.
        a32Nrm = a32Nrm - (smmul(b32Nrm, result) << 3);
        // Refinement.
        result = smlawb(result, a32Nrm, b32Inv);
        // Convert to Qres domain.
        int lshift = 29 + aHeadrm - bHeadrm - qRes;
        if (lshift < 0) {
            return lshiftSat32(result, -lshift);
        }
        return lshift < 32 ? result >> lshift : 0;
    }

    /** silk_INVERSE32_varQ: a good approximation of (1 << Qres) / b32. */
    static int inverse32VarQ(int b32, int qRes) {
        // Compute number of bits head room and normalize input.
        int bHeadrm = clz32(abs(b32)) - 1;
        int b32Nrm = b32 << bHeadrm;
        // Inverse of b32, with 14 bits of precision.
        int b32Inv = (Integer.MAX_VALUE >> 2) / (b32Nrm >> 16);
        // First approximation.
        int result = b32Inv << 16;
        // Compute residual by subtracting product of denominator and first approximation from one.
        int errQ32 = ((1 << 29) - smulwb(b32Nrm, b32Inv)) << 3;
        // Refinement.
        result = (int) (result + (((long) errQ32 * b32Inv) >> 16));
        // Convert to Qres domain.
        int lshift = 61 - bHeadrm - qRes;
        if (lshift <= 0) {
            return lshiftSat32(result, -lshift);
        }
        return lshift < 32 ? result >> lshift : 0;
    }

    /** silk_log2lin: approximation of 2^(inLog_Q7/128). */
    static int log2lin(int inLogQ7) {
        if (inLogQ7 < 0) {
            return 0;
        } else if (inLogQ7 >= 3967) {
            return Integer.MAX_VALUE;
        }
        int out = 1 << (inLogQ7 >> 7);
        int fracQ7 = inLogQ7 & 0x7F;
        if (inLogQ7 < 2048) {
            // Piece-wise parabolic approximation.
            out = out + ((out * smlawb(fracQ7, smulbb(fracQ7, 128 - fracQ7), -174)) >> 7);
        } else {
            // Piece-wise parabolic approximation.
            out = out + (out >> 7) * smlawb(fracQ7, smulbb(fracQ7, 128 - fracQ7), -174);
        }
        return out;
    }
}

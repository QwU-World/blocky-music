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
 * The CELT layer of an Opus decoder at 48 kHz: one frame of 2.5 to 20 ms per call, mono or stereo. Ported from
 * libopus 1.6.1 celt/celt_decoder.c (celt_decode_with_ec, celt_decode_lost, celt_synthesis, deemphasis, tf_decode,
 * prefilter_and_fold), celt/celt.c (comb_filter), celt/pitch.c and celt/celt_lpc.c (the pitch search and linear
 * prediction of the concealment), float build, without the 1.5 machine-learning concealment (deep PLC, DRED) and
 * without the QEXT extension, which are both off in a default libopus build.
 *
 * <p>Like the C decoder, it keeps 2048 samples of history per channel plus the 120-sample overlap, and writes
 * interleaved floats scaled to [-1, 1] into the caller's buffer, optionally adding to what is there (hybrid mode
 * adds CELT to SILK).
 */
final class OpusCeltDecoder {
    static final int OVERLAP = 120;
    static final int NB_EBANDS = 21;
    private static final int DECODE_BUFFER_SIZE = 2048;
    private static final int MAX_PERIOD = 1024;
    private static final int LPC_ORDER = 24;
    private static final int SHORT_MDCT_SIZE = 120;
    private static final int MAX_LM = 3;
    private static final int PLC_PITCH_LAG_MAX = 720;
    private static final int PLC_PITCH_LAG_MIN = 100;
    private static final int COMBFILTER_MINPERIOD = 15;
    private static final int FRAME_NONE = 0;
    private static final int FRAME_NORMAL = 1;
    private static final int FRAME_PLC_NOISE = 2;
    private static final int FRAME_PLC_PERIODIC = 3;
    private static final int BITRES = OpusRangeDecoder.BITRES;
    private static final int SPREAD_NORMAL = 2;
    private static final float PREEMPH = 0.85000610f;
    private static final float VERY_SMALL = 1e-30f;
    private static final float SIG_TO_RES = 1 / 32768.f;
    static final int OPUS_BAD_ARG = -1;
    static final int OPUS_INTERNAL_ERROR = -3;

    private static final short[] EBANDS = OpusCeltTables.EBANDS;
    private static final float[] WINDOW = OpusCeltTables.WINDOW;
    private static final int[] TRIM_ICDF = {126, 124, 119, 109, 87, 41, 19, 9, 4, 2, 0};
    private static final int[] SPREAD_ICDF = {25, 23, 2, 0};
    private static final int[] TAPSET_ICDF = {2, 1, 0};
    /** tf_select_table: per LM, 4*isTransient + 2*tf_select + per-band flag. */
    private static final int[][] TF_SELECT_TABLE = {
            {0, -1, 0, -1, 0, -1, 0, -1},
            {0, -1, 0, -2, 1, 0, 1, -1},
            {0, -2, 0, -3, 2, 0, 1, -1},
            {0, -2, 0, -3, 3, 0, 1, -1},
    };
    private static final float[][] COMB_GAINS = {
            {0.3066406250f, 0.2170410156f, 0.1296386719f},
            {0.4638671875f, 0.2680664062f, 0.f},
            {0.7998046875f, 0.1000976562f, 0.f},
    };

    final int channels;
    int streamChannels;
    int start;
    int end = NB_EBANDS;
    boolean disableInv;

    // Everything below is cleared by reset(), as OPUS_RESET_STATE clears from rng to the end of the C struct.
    int rng;
    boolean error;
    private int lastPitchIndex;
    private int lossDuration;
    private int plcDuration;
    private int lastFrameType;
    private boolean skipPlc;
    private int postfilterPeriod;
    private int postfilterPeriodOld;
    private float postfilterGain;
    private float postfilterGainOld;
    private int postfilterTapset;
    private int postfilterTapsetOld;
    private boolean prefilterAndFold;
    private final float[] preemphMem = new float[2];
    private final float[][] decodeMem;
    private final float[] oldBandE = new float[2 * NB_EBANDS];
    private final float[] oldLogE = new float[2 * NB_EBANDS];
    private final float[] oldLogE2 = new float[2 * NB_EBANDS];
    private final float[] backgroundLogE = new float[2 * NB_EBANDS];
    private final float[] lpc = new float[2 * LPC_ORDER];

    // Scratch space, so that decoding a frame allocates nothing.
    private final OpusRangeDecoder ownDecoder = new OpusRangeDecoder();
    private final OpusCeltBands bands = new OpusCeltBands();
    private final OpusCeltAllocation.Result alloc = new OpusCeltAllocation.Result();
    private final float[] x = new float[2 * 960];
    private final float[] freq = new float[960];
    private final int[] tfRes = new int[NB_EBANDS];
    private final int[] cap = new int[NB_EBANDS];
    private final int[] offsets = new int[NB_EBANDS];
    private final int[] fineQuant = new int[NB_EBANDS];
    private final int[] pulses = new int[NB_EBANDS];
    private final int[] finePriority = new int[NB_EBANDS];
    private final int[] collapseMasks = new int[2 * NB_EBANDS];
    private final int[] allocScratch = new int[4 * NB_EBANDS];
    private final float[] etmp = new float[OVERLAP];
    private final float[] lpPitchBuf = new float[DECODE_BUFFER_SIZE >> 1];
    private final float[] exc = new float[MAX_PERIOD + LPC_ORDER];
    private final float[] firTmp = new float[MAX_PERIOD];
    private final float[] ac = new float[LPC_ORDER + 1];
    private final float[] lpcMem = new float[LPC_ORDER];
    private final float[] iirY = new float[960 + OVERLAP + LPC_ORDER];
    private final float[] autocorrXx = new float[MAX_PERIOD];
    private final float[] xcorrBuf = new float[(PLC_PITCH_LAG_MAX - PLC_PITCH_LAG_MIN) >> 1];
    private final float[] xLp4 = new float[(DECODE_BUFFER_SIZE - PLC_PITCH_LAG_MAX) >> 2];
    private final float[] yLp4 = new float[(DECODE_BUFFER_SIZE - PLC_PITCH_LAG_MIN) >> 2];
    private final float[] sum4 = new float[4];
    private final int[] bestPitch = new int[2];

    OpusCeltDecoder(int channels) {
        this.channels = channels;
        this.streamChannels = channels;
        this.decodeMem = new float[channels][DECODE_BUFFER_SIZE + OVERLAP];
        // A mono decoder cannot invert the phase of a channel it does not have (DISABLE_UPDATE_DRAFT unset).
        this.disableInv = channels == 1;
        reset();
    }

    /** OPUS_RESET_STATE. */
    void reset() {
        rng = 0;
        error = false;
        lastPitchIndex = 0;
        lossDuration = 0;
        plcDuration = 0;
        postfilterPeriod = 0;
        postfilterPeriodOld = 0;
        postfilterGain = 0;
        postfilterGainOld = 0;
        postfilterTapset = 0;
        postfilterTapsetOld = 0;
        prefilterAndFold = false;
        Arrays.fill(preemphMem, 0);
        for (float[] mem : decodeMem) {
            Arrays.fill(mem, 0);
        }
        Arrays.fill(oldBandE, 0);
        Arrays.fill(oldLogE, -28.f);
        Arrays.fill(oldLogE2, -28.f);
        Arrays.fill(backgroundLogE, 0);
        Arrays.fill(lpc, 0);
        skipPlc = true;
        lastFrameType = FRAME_NONE;
    }

    /**
     * celt_decode_with_ec: decodes one frame of {@code frameSize} samples at 48 kHz into
     * {@code pcm[pcmOff ..]}, interleaved by {@link #channels}. A null {@code data} or a length of at most one byte
     * conceals a lost frame. When {@code dec} is given, the frame continues that range decoder (hybrid mode).
     *
     * @return the number of samples, or a negative libopus error code
     */
    int decode(byte[] data, int dataOff, int len, float[] pcm, int pcmOff, int frameSize, OpusRangeDecoder dec,
               boolean accum) {
        int cc = channels;
        int c = streamChannels;
        int lm;
        for (lm = 0; lm <= MAX_LM; lm++) {
            if (SHORT_MDCT_SIZE << lm == frameSize) {
                break;
            }
        }
        if (lm > MAX_LM) {
            return OPUS_BAD_ARG;
        }
        int m = 1 << lm;
        if (len < 0 || len > 1275 || pcm == null) {
            return OPUS_BAD_ARG;
        }
        int n = m * SHORT_MDCT_SIZE;
        int outSyn = DECODE_BUFFER_SIZE - n;
        int effEnd = Math.min(end, NB_EBANDS);

        if (data == null || len <= 1) {
            decodeLost(n, lm);
            deemphasis(outSyn, pcm, pcmOff, n, cc, accum);
            return frameSize;
        }
        // Check if there are at least two packets received consecutively before turning on the pitch-based PLC.
        if (lossDuration == 0) {
            skipPlc = false;
        }
        if (dec == null) {
            ownDecoder.init(data, dataOff, len);
            dec = ownDecoder;
        }
        if (c == 1) {
            for (int i = 0; i < NB_EBANDS; i++) {
                oldBandE[i] = maxf(oldBandE[i], oldBandE[NB_EBANDS + i]);
            }
        }
        int totalBits = len * 8;
        int tell = dec.tell();
        boolean silence;
        if (tell >= totalBits) {
            silence = true;
        } else if (tell == 1) {
            silence = dec.decodeBitLogp(15);
        } else {
            silence = false;
        }
        if (silence) {
            // Pretend we've read all the remaining bits.
            tell = len * 8;
            dec.nbitsTotal += tell - dec.tell();
        }

        float postfilterGainNew = 0;
        int postfilterPitch = 0;
        int postfilterTapsetNew = 0;
        if (start == 0 && tell + 16 <= totalBits) {
            if (dec.decodeBitLogp(1)) {
                int octave = (int) dec.decodeUint(6);
                postfilterPitch = (16 << octave) + dec.decodeBits(4 + octave) - 1;
                int qg = dec.decodeBits(3);
                if (dec.tell() + 2 <= totalBits) {
                    postfilterTapsetNew = dec.decodeIcdf(TAPSET_ICDF, 2);
                }
                postfilterGainNew = .09375f * (qg + 1);
            }
            tell = dec.tell();
        }

        boolean isTransient;
        if (lm > 0 && tell + 3 <= totalBits) {
            isTransient = dec.decodeBitLogp(3);
            tell = dec.tell();
        } else {
            isTransient = false;
        }
        // Decode the global flags (first symbols in the stream).
        boolean intraEner = tell + 3 <= totalBits && dec.decodeBitLogp(3);
        // If recovering from packet loss, make sure we make the energy prediction safe to reduce the risk of
        // getting loud artifacts.
        if (!intraEner && lossDuration != 0) {
            int ch = 0;
            do {
                float safety = 0;
                int missing = Math.min(10, lossDuration >> lm);
                if (lm == 0) {
                    safety = 1.5f;
                } else if (lm == 1) {
                    safety = .5f;
                }
                for (int i = start; i < end; i++) {
                    int k = ch * NB_EBANDS + i;
                    if (oldBandE[k] < maxf(oldLogE[k], oldLogE2[k])) {
                        // If energy is going down already, continue the trend.
                        float e0 = oldBandE[k];
                        float e1 = oldLogE[k];
                        float e2 = oldLogE2[k];
                        float slope = maxf(e1 - e0, .5f * (e2 - e0));
                        slope = minf(slope, 2.f);
                        e0 -= maxf(0, (1 + missing) * slope);
                        oldBandE[k] = maxf(-20.f, e0);
                    } else {
                        // Otherwise take the min of the last frames.
                        oldBandE[k] = minf(minf(oldBandE[k], oldLogE[k]), oldLogE2[k]);
                    }
                    // Shorter frames have more natural fluctuations -- play it safe.
                    oldBandE[k] -= safety;
                }
            } while (++ch < 2);
        }
        // Get band energies.
        OpusCeltAllocation.unquantCoarseEnergy(start, end, oldBandE, intraEner, dec, c, lm);

        tfDecode(start, end, isTransient, tfRes, lm, dec);

        tell = dec.tell();
        int spreadDecision = SPREAD_NORMAL;
        if (tell + 4 <= totalBits) {
            spreadDecision = dec.decodeIcdf(SPREAD_ICDF, 5);
        }
        OpusCeltAllocation.initCaps(cap, lm, c);

        int dynallocLogp = 6;
        totalBits <<= BITRES;
        tell = dec.tellFrac();
        for (int i = start; i < end; i++) {
            int width = c * (EBANDS[i + 1] - EBANDS[i]) << lm;
            // quanta is 6 bits, but no more than 1 bit/sample and no less than 1/8 bit/sample.
            int quanta = Math.min(width << BITRES, Math.max(6 << BITRES, width));
            int dynallocLoopLogp = dynallocLogp;
            int boost = 0;
            while (tell + (dynallocLoopLogp << BITRES) < totalBits && boost < cap[i]) {
                boolean flag = dec.decodeBitLogp(dynallocLoopLogp);
                tell = dec.tellFrac();
                if (!flag) {
                    break;
                }
                boost += quanta;
                totalBits -= quanta;
                dynallocLoopLogp = 1;
            }
            offsets[i] = boost;
            // Making dynalloc more likely.
            if (boost > 0) {
                dynallocLogp = Math.max(2, dynallocLogp - 1);
            }
        }

        int allocTrim = tell + (6 << BITRES) <= totalBits ? dec.decodeIcdf(TRIM_ICDF, 7) : 5;
        int bits = ((len * 8) << BITRES) - dec.tellFrac() - 1;
        int antiCollapseRsv = isTransient && lm >= 2 && bits >= ((lm + 2) << BITRES) ? (1 << BITRES) : 0;
        bits -= antiCollapseRsv;
        OpusCeltAllocation.compute(start, end, offsets, cap, allocTrim, bits, pulses, fineQuant, finePriority, c, lm,
                dec, alloc, allocScratch);
        OpusCeltAllocation.unquantFineEnergy(start, end, oldBandE, fineQuant, dec, c);

        for (int ch = 0; ch < cc; ch++) {
            System.arraycopy(decodeMem[ch], n, decodeMem[ch], 0, DECODE_BUFFER_SIZE - n + OVERLAP);
        }

        // Decode fixed codebook.
        bands.seed = rng;
        bands.quantAllBands(start, end, x, c == 2 ? n : -1, collapseMasks, pulses, isTransient, spreadDecision,
                alloc.dualStereo, alloc.intensity, tfRes, len * (8 << BITRES) - antiCollapseRsv, alloc.balance, dec,
                lm, alloc.codedBands, disableInv);
        rng = bands.seed;

        boolean antiCollapseOn = false;
        if (antiCollapseRsv > 0) {
            antiCollapseOn = dec.decodeBits(1) != 0;
        }
        OpusCeltAllocation.unquantEnergyFinalise(start, end, oldBandE, fineQuant, finePriority, len * 8 - dec.tell(),
                dec, c);
        if (antiCollapseOn) {
            OpusCeltBands.antiCollapse(x, collapseMasks, lm, c, n, start, end, oldBandE, oldLogE, oldLogE2, pulses,
                    rng);
        }
        if (silence) {
            for (int i = 0; i < c * NB_EBANDS; i++) {
                oldBandE[i] = -28.f;
            }
        }
        if (prefilterAndFold) {
            prefilterAndFold(n);
        }
        synthesis(x, outSyn, oldBandE, start, effEnd, c, cc, isTransient, lm, silence);

        for (int ch = 0; ch < cc; ch++) {
            postfilterPeriod = Math.max(postfilterPeriod, COMBFILTER_MINPERIOD);
            postfilterPeriodOld = Math.max(postfilterPeriodOld, COMBFILTER_MINPERIOD);
            combFilter(decodeMem[ch], outSyn, decodeMem[ch], outSyn, postfilterPeriodOld, postfilterPeriod,
                    SHORT_MDCT_SIZE, postfilterGainOld, postfilterGain, postfilterTapsetOld, postfilterTapset, OVERLAP);
            if (lm != 0) {
                combFilter(decodeMem[ch], outSyn + SHORT_MDCT_SIZE, decodeMem[ch], outSyn + SHORT_MDCT_SIZE,
                        postfilterPeriod, postfilterPitch, n - SHORT_MDCT_SIZE, postfilterGain, postfilterGainNew,
                        postfilterTapset, postfilterTapsetNew, OVERLAP);
            }
        }
        postfilterPeriodOld = postfilterPeriod;
        postfilterGainOld = postfilterGain;
        postfilterTapsetOld = postfilterTapset;
        postfilterPeriod = postfilterPitch;
        postfilterGain = postfilterGainNew;
        postfilterTapset = postfilterTapsetNew;
        if (lm != 0) {
            postfilterPeriodOld = postfilterPeriod;
            postfilterGainOld = postfilterGain;
            postfilterTapsetOld = postfilterTapset;
        }

        if (c == 1) {
            System.arraycopy(oldBandE, 0, oldBandE, NB_EBANDS, NB_EBANDS);
        }
        if (!isTransient) {
            System.arraycopy(oldLogE, 0, oldLogE2, 0, 2 * NB_EBANDS);
            System.arraycopy(oldBandE, 0, oldLogE, 0, 2 * NB_EBANDS);
        } else {
            for (int i = 0; i < 2 * NB_EBANDS; i++) {
                oldLogE[i] = minf(oldLogE[i], oldBandE[i]);
            }
        }
        // In normal circumstances, we only allow the noise floor to increase by up to 2.4 dB/second, but when
        // we're in DTX we give the weight of all missing packets to the update packet.
        float maxBackgroundIncrease = Math.min(160, lossDuration + m) * 0.001f;
        for (int i = 0; i < 2 * NB_EBANDS; i++) {
            backgroundLogE[i] = minf(backgroundLogE[i] + maxBackgroundIncrease, oldBandE[i]);
        }
        // In case start or end were to change.
        for (int ch = 0; ch < 2; ch++) {
            for (int i = 0; i < start; i++) {
                oldBandE[ch * NB_EBANDS + i] = 0;
                oldLogE[ch * NB_EBANDS + i] = -28.f;
                oldLogE2[ch * NB_EBANDS + i] = -28.f;
            }
            for (int i = end; i < NB_EBANDS; i++) {
                oldBandE[ch * NB_EBANDS + i] = 0;
                oldLogE[ch * NB_EBANDS + i] = -28.f;
                oldLogE2[ch * NB_EBANDS + i] = -28.f;
            }
        }
        rng = (int) dec.rng;

        deemphasis(outSyn, pcm, pcmOff, n, cc, accum);
        lossDuration = 0;
        plcDuration = 0;
        lastFrameType = FRAME_NORMAL;
        prefilterAndFold = false;
        if (dec.tell() > 8 * len) {
            return OPUS_INTERNAL_ERROR;
        }
        if (dec.error) {
            error = true;
        }
        return frameSize;
    }

    private static void tfDecode(int start, int end, boolean isTransient, int[] tfRes, int lm, OpusRangeDecoder dec) {
        int budget = dec.storage * 8;
        int tell = dec.tell();
        int logp = isTransient ? 2 : 4;
        int tfSelectRsv = lm > 0 && tell + logp + 1 <= budget ? 1 : 0;
        budget -= tfSelectRsv;
        int tfChanged = 0;
        int curr = 0;
        for (int i = start; i < end; i++) {
            if (tell + logp <= budget) {
                curr ^= dec.decodeBitLogp(logp) ? 1 : 0;
                tell = dec.tell();
                tfChanged |= curr;
            }
            tfRes[i] = curr;
            logp = isTransient ? 4 : 5;
        }
        int tfSelect = 0;
        int t = isTransient ? 4 : 0;
        if (tfSelectRsv != 0 && TF_SELECT_TABLE[lm][t + tfChanged] != TF_SELECT_TABLE[lm][t + 2 + tfChanged]) {
            tfSelect = dec.decodeBitLogp(1) ? 1 : 0;
        }
        for (int i = start; i < end; i++) {
            tfRes[i] = TF_SELECT_TABLE[lm][t + 2 * tfSelect + tfRes[i]];
        }
    }

    /** celt_synthesis: denormalises the bands and runs the inverse MDCTs into the history buffer. */
    private void synthesis(float[] xs, int outSyn, float[] bandE, int startBand, int effEnd, int c, int cc,
                           boolean isTransient, int lm, boolean silence) {
        int n = SHORT_MDCT_SIZE << lm;
        int m = 1 << lm;
        int b;
        int nb;
        int shift;
        if (isTransient) {
            b = m;
            nb = SHORT_MDCT_SIZE;
            shift = MAX_LM;
        } else {
            b = 1;
            nb = SHORT_MDCT_SIZE << lm;
            shift = MAX_LM - lm;
        }
        if (cc == 2 && c == 1) {
            // Copying a mono stream to two channels.
            OpusCeltBands.denormaliseBands(xs, 0, freq, 0, bandE, 0, startBand, effEnd, m, 1, silence);
            // Store a temporary copy in the output buffer because the IMDCT destroys its input.
            int freq2 = outSyn + OVERLAP / 2;
            System.arraycopy(freq, 0, decodeMem[1], freq2, n);
            for (int k = 0; k < b; k++) {
                OpusCeltMdct.backward(decodeMem[1], freq2 + k, decodeMem[0], outSyn + nb * k, shift, b);
            }
            for (int k = 0; k < b; k++) {
                OpusCeltMdct.backward(freq, k, decodeMem[1], outSyn + nb * k, shift, b);
            }
        } else if (cc == 1 && c == 2) {
            // Downmixing a stereo stream to mono.
            int freq2 = outSyn + OVERLAP / 2;
            OpusCeltBands.denormaliseBands(xs, 0, freq, 0, bandE, 0, startBand, effEnd, m, 1, silence);
            // Use the output buffer as temp array before downmixing.
            OpusCeltBands.denormaliseBands(xs, n, decodeMem[0], freq2, bandE, NB_EBANDS, startBand, effEnd, m, 1,
                    silence);
            for (int i = 0; i < n; i++) {
                freq[i] = .5f * freq[i] + .5f * decodeMem[0][freq2 + i];
            }
            for (int k = 0; k < b; k++) {
                OpusCeltMdct.backward(freq, k, decodeMem[0], outSyn + nb * k, shift, b);
            }
        } else {
            // Normal case (mono or stereo).
            int ch = 0;
            do {
                OpusCeltBands.denormaliseBands(xs, ch * n, freq, 0, bandE, ch * NB_EBANDS, startBand, effEnd, m, 1,
                        silence);
                for (int k = 0; k < b; k++) {
                    OpusCeltMdct.backward(freq, k, decodeMem[ch], outSyn + nb * k, shift, b);
                }
            } while (++ch < cc);
        }
        // SATURATE is the identity in the float build.
    }

    /** deemphasis for the 48 kHz case: undoes the encoder's pre-emphasis and scales to [-1, 1]. */
    private void deemphasis(int outSyn, float[] pcm, int pcmOff, int n, int cc, boolean accum) {
        float coef0 = PREEMPH;
        if (cc == 2 && !accum) {
            // The common stereo case, both channels in one loop, as deemphasis_stereo_simple.
            float[] x0 = decodeMem[0];
            float[] x1 = decodeMem[1];
            float m0 = preemphMem[0];
            float m1 = preemphMem[1];
            for (int j = 0; j < n; j++) {
                float tmp0 = x0[outSyn + j] + VERY_SMALL + m0;
                float tmp1 = x1[outSyn + j] + VERY_SMALL + m1;
                m0 = coef0 * tmp0;
                m1 = coef0 * tmp1;
                pcm[pcmOff + 2 * j] = SIG_TO_RES * tmp0;
                pcm[pcmOff + 2 * j + 1] = SIG_TO_RES * tmp1;
            }
            preemphMem[0] = m0;
            preemphMem[1] = m1;
            return;
        }
        int ch = 0;
        do {
            float[] xs = decodeMem[ch];
            float m = preemphMem[ch];
            int y = pcmOff + ch;
            if (accum) {
                for (int j = 0; j < n; j++) {
                    float tmp = xs[outSyn + j] + m + VERY_SMALL;
                    m = coef0 * tmp;
                    pcm[y + j * cc] = pcm[y + j * cc] + SIG_TO_RES * tmp;
                }
            } else {
                for (int j = 0; j < n; j++) {
                    float tmp = xs[outSyn + j] + VERY_SMALL + m;
                    m = coef0 * tmp;
                    pcm[y + j * cc] = SIG_TO_RES * tmp;
                }
            }
            preemphMem[ch] = m;
        } while (++ch < cc);
    }

    /**
     * comb_filter: the pitch post-filter, cross-fading from the old period and gain to the new ones over the
     * overlap. {@code y} may be the same buffer as {@code x}, which makes it recursive exactly as in C.
     */
    private static void combFilter(float[] y, int yOff, float[] x, int xOff, int t0, int t1, int n, float g0,
                                   float g1, int tapset0, int tapset1, int overlap) {
        if (g0 == 0 && g1 == 0) {
            if (x != y || xOff != yOff) {
                System.arraycopy(x, xOff, y, yOff, n);
            }
            return;
        }
        // When the gain is zero, T0 and/or T1 is set to zero. We need to have then be at least 2 to avoid
        // processing garbage data.
        t0 = Math.max(t0, COMBFILTER_MINPERIOD);
        t1 = Math.max(t1, COMBFILTER_MINPERIOD);
        float g00 = g0 * COMB_GAINS[tapset0][0];
        float g01 = g0 * COMB_GAINS[tapset0][1];
        float g02 = g0 * COMB_GAINS[tapset0][2];
        float g10 = g1 * COMB_GAINS[tapset1][0];
        float g11 = g1 * COMB_GAINS[tapset1][1];
        float g12 = g1 * COMB_GAINS[tapset1][2];
        float x1 = x[xOff - t1 + 1];
        float x2 = x[xOff - t1];
        float x3 = x[xOff - t1 - 1];
        float x4 = x[xOff - t1 - 2];
        // If the filter didn't change, we don't need the overlap.
        if (g0 == g1 && t0 == t1 && tapset0 == tapset1) {
            overlap = 0;
        }
        int i;
        for (i = 0; i < overlap; i++) {
            float x0 = x[xOff + i - t1 + 2];
            float f = WINDOW[i] * WINDOW[i];
            y[yOff + i] = x[xOff + i]
                    + ((1.0f - f) * g00) * x[xOff + i - t0]
                    + ((1.0f - f) * g01) * (x[xOff + i - t0 + 1] + x[xOff + i - t0 - 1])
                    + ((1.0f - f) * g02) * (x[xOff + i - t0 + 2] + x[xOff + i - t0 - 2])
                    + (f * g10) * x2
                    + (f * g11) * (x1 + x3)
                    + (f * g12) * (x0 + x4);
            x4 = x3;
            x3 = x2;
            x2 = x1;
            x1 = x0;
        }
        if (g1 == 0) {
            if (x != y || xOff != yOff) {
                System.arraycopy(x, xOff + overlap, y, yOff + overlap, n - overlap);
            }
            return;
        }
        // Compute the part with the constant filter (comb_filter_const_c).
        int yc = yOff + i;
        int xc = xOff + i;
        int nc = n - i;
        x4 = x[xc - t1 - 2];
        x3 = x[xc - t1 - 1];
        x2 = x[xc - t1];
        x1 = x[xc - t1 + 1];
        for (int j = 0; j < nc; j++) {
            float x0 = x[xc + j - t1 + 2];
            y[yc + j] = x[xc + j]
                    + g10 * x2
                    + g11 * (x1 + x3)
                    + g12 * (x0 + x4);
            x4 = x3;
            x3 = x2;
            x2 = x1;
            x1 = x0;
        }
    }

    /**
     * prefilter_and_fold: after a concealed frame, applies the pre-filter to the MDCT overlap so the post-filter
     * can be re-applied after the next frame's overlap, and folds the concealed audio as TDAC would.
     */
    private void prefilterAndFold(int n) {
        for (int ch = 0; ch < channels; ch++) {
            float[] mem = decodeMem[ch];
            combFilter(etmp, 0, mem, DECODE_BUFFER_SIZE - n, postfilterPeriodOld, postfilterPeriod, OVERLAP,
                    -postfilterGainOld, -postfilterGain, postfilterTapsetOld, postfilterTapset, 0);
            // Simulate TDAC on the concealed audio so that it blends with the MDCT of the next frame.
            for (int i = 0; i < OVERLAP / 2; i++) {
                mem[DECODE_BUFFER_SIZE - n + i] = WINDOW[i] * etmp[OVERLAP - 1 - i]
                        + WINDOW[OVERLAP - i - 1] * etmp[i];
            }
        }
    }

    /** celt_decode_lost: conceals a missing frame, by pitch repetition or, after a while, by shaped noise. */
    private void decodeLost(int n, int lm) {
        int c = channels;
        int outSyn = DECODE_BUFFER_SIZE - n;
        int lossDur = lossDuration;
        int startBand = start;
        int currFrameType = FRAME_PLC_PERIODIC;
        if (plcDuration >= 40 || startBand != 0 || skipPlc) {
            currFrameType = FRAME_PLC_NOISE;
        }
        if (currFrameType == FRAME_PLC_NOISE) {
            // Noise-based PLC/CNG.
            int endBand = end;
            int effEnd = Math.max(startBand, Math.min(endBand, NB_EBANDS));
            for (int ch = 0; ch < c; ch++) {
                System.arraycopy(decodeMem[ch], n, decodeMem[ch], 0, DECODE_BUFFER_SIZE - n + OVERLAP);
            }
            if (prefilterAndFold) {
                prefilterAndFold(n);
            }
            // Energy decay.
            float decay = lossDur == 0 ? 1.5f : .5f;
            for (int ch = 0; ch < c; ch++) {
                for (int i = startBand; i < endBand; i++) {
                    int k = ch * NB_EBANDS + i;
                    oldBandE[k] = maxf(backgroundLogE[k], oldBandE[k] - decay);
                }
            }
            int seed = rng;
            for (int ch = 0; ch < c; ch++) {
                for (int i = startBand; i < effEnd; i++) {
                    int boffs = n * ch + (EBANDS[i] << lm);
                    int blen = (EBANDS[i + 1] - EBANDS[i]) << lm;
                    for (int j = 0; j < blen; j++) {
                        seed = OpusCeltBands.lcgRand(seed);
                        x[boffs + j] = (float) (seed >> 20);
                    }
                    OpusCeltBands.renormaliseVector(x, boffs, blen, 1f);
                }
            }
            rng = seed;
            synthesis(x, outSyn, oldBandE, startBand, effEnd, c, c, false, lm, false);
            // Run the postfilter with the last parameters.
            for (int ch = 0; ch < c; ch++) {
                postfilterPeriod = Math.max(postfilterPeriod, COMBFILTER_MINPERIOD);
                postfilterPeriodOld = Math.max(postfilterPeriodOld, COMBFILTER_MINPERIOD);
                combFilter(decodeMem[ch], outSyn, decodeMem[ch], outSyn, postfilterPeriodOld, postfilterPeriod,
                        SHORT_MDCT_SIZE, postfilterGainOld, postfilterGain, postfilterTapsetOld, postfilterTapset,
                        OVERLAP);
                if (lm != 0) {
                    combFilter(decodeMem[ch], outSyn + SHORT_MDCT_SIZE, decodeMem[ch], outSyn + SHORT_MDCT_SIZE,
                            postfilterPeriod, postfilterPeriod, n - SHORT_MDCT_SIZE, postfilterGain, postfilterGain,
                            postfilterTapset, postfilterTapset, OVERLAP);
                }
            }
            postfilterPeriodOld = postfilterPeriod;
            postfilterGainOld = postfilterGain;
            postfilterTapsetOld = postfilterTapset;
            prefilterAndFold = false;
            // Skip regular PLC until we get two consecutive packets.
            skipPlc = true;
        } else {
            // Pitch-based PLC.
            float fade = 1.0f;
            int pitchIndex;
            if (lastFrameType != FRAME_PLC_PERIODIC) {
                lastPitchIndex = pitchIndex = plcPitchSearch(c);
            } else {
                pitchIndex = lastPitchIndex;
                fade = .8f;
            }
            // We want the excitation for 2 pitch periods in order to look for a decaying signal, but we can't
            // get more than MAX_PERIOD.
            int excLength = Math.min(2 * pitchIndex, MAX_PERIOD);
            // exc[] is _exc + LPC_ORDER in C; index e here stands for exc[e - LPC_ORDER].
            for (int ch = 0; ch < c; ch++) {
                float[] buf = decodeMem[ch];
                float s1 = 0;
                for (int i = 0; i < MAX_PERIOD + LPC_ORDER; i++) {
                    exc[i] = buf[DECODE_BUFFER_SIZE - MAX_PERIOD - LPC_ORDER + i];
                }
                if (lastFrameType != FRAME_PLC_PERIODIC) {
                    // Compute LPC coefficients for the last MAX_PERIOD samples before the first loss so we can
                    // work in the excitation-filter domain.
                    autocorr(exc, LPC_ORDER, ac, true, LPC_ORDER, MAX_PERIOD);
                    // Add a noise floor of -40 dB.
                    ac[0] *= 1.0001f;
                    // Use lag windowing to stabilize the Levinson-Durbin recursion.
                    for (int i = 1; i <= LPC_ORDER; i++) {
                        ac[i] -= ac[i] * (0.008f * 0.008f) * i * i;
                    }
                    celtLpc(lpc, ch * LPC_ORDER, ac, LPC_ORDER);
                }
                // Compute the excitation for exc_length samples before the loss. We need the copy because
                // celt_fir() cannot filter in-place.
                celtFir(exc, LPC_ORDER + MAX_PERIOD - excLength, lpc, ch * LPC_ORDER, firTmp, excLength, LPC_ORDER);
                System.arraycopy(firTmp, 0, exc, LPC_ORDER + MAX_PERIOD - excLength, excLength);

                // Check if the waveform is decaying, and if so how fast. We do this to avoid adding energy when
                // concealing in a segment with decaying energy.
                float decay;
                {
                    float e1 = 1;
                    float e2 = 1;
                    int decayLength = excLength >> 1;
                    for (int i = 0; i < decayLength; i++) {
                        float e = exc[LPC_ORDER + MAX_PERIOD - decayLength + i];
                        e1 += e * e;
                        e = exc[LPC_ORDER + MAX_PERIOD - 2 * decayLength + i];
                        e2 += e * e;
                    }
                    e1 = minf(e1, e2);
                    decay = (float) Math.sqrt((float) e1 / e2);
                }

                // Move the decoder memory one frame to the left to give us room to add the data for the new
                // frame. We ignore the overlap that extends past the end of the buffer, because we aren't going
                // to use it.
                System.arraycopy(buf, n, buf, 0, DECODE_BUFFER_SIZE - n);

                // Extrapolate from the end of the excitation with a period of "pitch_index", scaling down each
                // period by an additional factor of "decay".
                int extrapolationOffset = MAX_PERIOD - pitchIndex;
                // We need to extrapolate enough samples to cover a complete MDCT window (including overlap/2
                // samples on both sides).
                int extrapolationLen = n + OVERLAP;
                // We also apply fading if this is not the first loss.
                float attenuation = fade * decay;
                for (int i = 0, j = 0; i < extrapolationLen; i++, j++) {
                    if (j >= pitchIndex) {
                        j -= pitchIndex;
                        attenuation = attenuation * decay;
                    }
                    buf[DECODE_BUFFER_SIZE - n + i] = attenuation * exc[LPC_ORDER + extrapolationOffset + j];
                    // Compute the energy of the previously decoded signal whose excitation we're copying.
                    float tmp = buf[DECODE_BUFFER_SIZE - MAX_PERIOD - n + extrapolationOffset + j];
                    s1 += tmp * tmp;
                }
                // Copy the last decoded samples (prior to the overlap region) to synthesis filter memory so we can
                // have a continuous signal.
                for (int i = 0; i < LPC_ORDER; i++) {
                    lpcMem[i] = buf[DECODE_BUFFER_SIZE - n - 1 - i];
                }
                // Apply the synthesis filter to convert the excitation back into the signal domain.
                celtIir(buf, DECODE_BUFFER_SIZE - n, lpc, ch * LPC_ORDER, extrapolationLen, LPC_ORDER, lpcMem);

                // Check if the synthesis energy is higher than expected, which can happen with the signal changes
                // during our window. If so, attenuate.
                float s2 = 0;
                for (int i = 0; i < extrapolationLen; i++) {
                    float tmp = buf[DECODE_BUFFER_SIZE - n + i];
                    s2 += tmp * tmp;
                }
                // The float test is written this way to catch NaNs in the output of the IIR filter at the same
                // time.
                if (!(s1 > 0.2f * s2)) {
                    for (int i = 0; i < extrapolationLen; i++) {
                        buf[DECODE_BUFFER_SIZE - n + i] = 0;
                    }
                } else if (s1 < s2) {
                    float ratio = (float) Math.sqrt((float) (s1 + 1) / (s2 + 1));
                    for (int i = 0; i < OVERLAP; i++) {
                        float tmpG = 1.0f - WINDOW[i] * (1.0f - ratio);
                        buf[DECODE_BUFFER_SIZE - n + i] = tmpG * buf[DECODE_BUFFER_SIZE - n + i];
                    }
                    for (int i = OVERLAP; i < extrapolationLen; i++) {
                        buf[DECODE_BUFFER_SIZE - n + i] = ratio * buf[DECODE_BUFFER_SIZE - n + i];
                    }
                }
            }
            prefilterAndFold = true;
        }
        // Saturate to something large to avoid wrap-around.
        lossDuration = Math.min(10000, lossDur + (1 << lm));
        plcDuration = Math.min(10000, plcDuration + (1 << lm));
        lastFrameType = currFrameType;
    }

    private int plcPitchSearch(int c) {
        pitchDownsample(decodeMem, lpPitchBuf, DECODE_BUFFER_SIZE >> 1, c, 2);
        int pitchIndex = pitchSearch(lpPitchBuf, PLC_PITCH_LAG_MAX >> 1, lpPitchBuf,
                DECODE_BUFFER_SIZE - PLC_PITCH_LAG_MAX, PLC_PITCH_LAG_MAX - PLC_PITCH_LAG_MIN);
        return PLC_PITCH_LAG_MAX - pitchIndex;
    }

    private void pitchDownsample(float[][] xs, float[] xLp, int len, int c, int factor) {
        float[] lpcd = new float[4];
        float[] lpc2 = new float[5];
        float tmp = 1.0f;
        float c1 = .8f;
        int offset = factor / 2;
        float[] x0 = xs[0];
        for (int i = 1; i < len; i++) {
            xLp[i] = .25f * x0[factor * i - offset] + .25f * x0[factor * i + offset] + .5f * x0[factor * i];
        }
        xLp[0] = .25f * x0[offset] + .5f * x0[0];
        if (c == 2) {
            float[] x1 = xs[1];
            for (int i = 1; i < len; i++) {
                xLp[i] += .25f * x1[factor * i - offset] + .25f * x1[factor * i + offset] + .5f * x1[factor * i];
            }
            xLp[0] += .25f * x1[offset] + .5f * x1[0];
        }
        autocorr(xLp, 0, ac, false, 4, len);
        // Noise floor -40 dB.
        ac[0] *= 1.0001f;
        // Lag windowing.
        for (int i = 1; i <= 4; i++) {
            ac[i] -= ac[i] * (.008f * i) * (.008f * i);
        }
        celtLpc(lpcd, 0, ac, 4);
        for (int i = 0; i < 4; i++) {
            tmp = .9f * tmp;
            lpcd[i] = lpcd[i] * tmp;
        }
        // Add a zero.
        lpc2[0] = lpcd[0] + .8f;
        lpc2[1] = lpcd[1] + c1 * lpcd[0];
        lpc2[2] = lpcd[2] + c1 * lpcd[1];
        lpc2[3] = lpcd[3] + c1 * lpcd[2];
        lpc2[4] = c1 * lpcd[3];
        celtFir5(xLp, lpc2, len);
    }

    private static void celtFir5(float[] xs, float[] num, int n) {
        float num0 = num[0];
        float num1 = num[1];
        float num2 = num[2];
        float num3 = num[3];
        float num4 = num[4];
        float mem0 = 0;
        float mem1 = 0;
        float mem2 = 0;
        float mem3 = 0;
        float mem4 = 0;
        for (int i = 0; i < n; i++) {
            float sum = xs[i];
            sum = sum + num0 * mem0;
            sum = sum + num1 * mem1;
            sum = sum + num2 * mem2;
            sum = sum + num3 * mem3;
            sum = sum + num4 * mem4;
            mem4 = mem3;
            mem3 = mem2;
            mem2 = mem1;
            mem1 = mem0;
            mem0 = xs[i];
            xs[i] = sum;
        }
    }

    /** pitch_search: the best pitch period of {@code x} against {@code y}, coarse then fine. */
    private int pitchSearch(float[] xLp, int xOff, float[] y, int len, int maxPitch) {
        int lag = len + maxPitch;
        for (int j = 0; j < len >> 2; j++) {
            xLp4[j] = xLp[xOff + 2 * j];
        }
        for (int j = 0; j < lag >> 2; j++) {
            yLp4[j] = y[2 * j];
        }
        // Coarse search with 4x decimation.
        pitchXcorr(xLp4, 0, yLp4, 0, xcorrBuf, len >> 2, maxPitch >> 2);
        findBestPitch(xcorrBuf, yLp4, len >> 2, maxPitch >> 2, bestPitch);
        // Finer search with 2x decimation.
        for (int i = 0; i < maxPitch >> 1; i++) {
            xcorrBuf[i] = 0;
            if (Math.abs(i - 2 * bestPitch[0]) > 2 && Math.abs(i - 2 * bestPitch[1]) > 2) {
                continue;
            }
            float sum = OpusCeltBands.innerProd(xLp, xOff, y, i, len >> 1);
            xcorrBuf[i] = maxf(-1, sum);
        }
        findBestPitch(xcorrBuf, y, len >> 1, maxPitch >> 1, bestPitch);
        // Refine by pseudo-interpolation.
        int offset;
        if (bestPitch[0] > 0 && bestPitch[0] < (maxPitch >> 1) - 1) {
            float a = xcorrBuf[bestPitch[0] - 1];
            float b = xcorrBuf[bestPitch[0]];
            float c = xcorrBuf[bestPitch[0] + 1];
            if ((c - a) > .7f * (b - a)) {
                offset = 1;
            } else if ((a - c) > .7f * (b - c)) {
                offset = -1;
            } else {
                offset = 0;
            }
        } else {
            offset = 0;
        }
        return 2 * bestPitch[0] - offset;
    }

    private static void findBestPitch(float[] xcorr, float[] y, int len, int maxPitch, int[] bestPitch) {
        float syy = 1;
        float bestNum0 = -1;
        float bestNum1 = -1;
        float bestDen0 = 0;
        float bestDen1 = 0;
        bestPitch[0] = 0;
        bestPitch[1] = 1;
        for (int j = 0; j < len; j++) {
            syy = syy + y[j] * y[j];
        }
        for (int i = 0; i < maxPitch; i++) {
            if (xcorr[i] > 0) {
                float xcorr16 = xcorr[i];
                // Considering the range of xcorr16, this should avoid both underflows and overflows (inf) when
                // squaring xcorr16.
                xcorr16 *= 1e-12f;
                float num = xcorr16 * xcorr16;
                if (num * bestDen1 > bestNum1 * syy) {
                    if (num * bestDen0 > bestNum0 * syy) {
                        bestNum1 = bestNum0;
                        bestDen1 = bestDen0;
                        bestPitch[1] = bestPitch[0];
                        bestNum0 = num;
                        bestDen0 = syy;
                        bestPitch[0] = i;
                    } else {
                        bestNum1 = num;
                        bestDen1 = syy;
                        bestPitch[1] = i;
                    }
                }
            }
            syy += y[i + len] * y[i + len] - y[i] * y[i];
            syy = maxf(1, syy);
        }
    }

    /** celt_pitch_xcorr_c: correlations of x with y at lags 0..maxPitch-1, four lags at a time. */
    private void pitchXcorr(float[] xs, int xOff, float[] y, int yOff, float[] xcorr, int len, int maxPitch) {
        int i;
        for (i = 0; i < maxPitch - 3; i += 4) {
            sum4[0] = 0;
            sum4[1] = 0;
            sum4[2] = 0;
            sum4[3] = 0;
            xcorrKernel(xs, xOff, y, yOff + i, sum4, len);
            xcorr[i] = sum4[0];
            xcorr[i + 1] = sum4[1];
            xcorr[i + 2] = sum4[2];
            xcorr[i + 3] = sum4[3];
        }
        // In case max_pitch isn't a multiple of 4, do non-unrolled version.
        for (; i < maxPitch; i++) {
            xcorr[i] = OpusCeltBands.innerProd(xs, xOff, y, yOff + i, len);
        }
    }

    /** xcorr_kernel_c: four correlations at once, summed in exactly the C order. */
    private static void xcorrKernel(float[] x, int xp, float[] y, int yp, float[] sum, int len) {
        float y0 = y[yp++];
        float y1 = y[yp++];
        float y2 = y[yp++];
        float y3 = 0;
        float s0 = sum[0];
        float s1 = sum[1];
        float s2 = sum[2];
        float s3 = sum[3];
        int j;
        for (j = 0; j < len - 3; j += 4) {
            float tmp = x[xp++];
            y3 = y[yp++];
            s0 = s0 + tmp * y0;
            s1 = s1 + tmp * y1;
            s2 = s2 + tmp * y2;
            s3 = s3 + tmp * y3;
            tmp = x[xp++];
            y0 = y[yp++];
            s0 = s0 + tmp * y1;
            s1 = s1 + tmp * y2;
            s2 = s2 + tmp * y3;
            s3 = s3 + tmp * y0;
            tmp = x[xp++];
            y1 = y[yp++];
            s0 = s0 + tmp * y2;
            s1 = s1 + tmp * y3;
            s2 = s2 + tmp * y0;
            s3 = s3 + tmp * y1;
            tmp = x[xp++];
            y2 = y[yp++];
            s0 = s0 + tmp * y3;
            s1 = s1 + tmp * y0;
            s2 = s2 + tmp * y1;
            s3 = s3 + tmp * y2;
        }
        if (j++ < len) {
            float tmp = x[xp++];
            y3 = y[yp++];
            s0 = s0 + tmp * y0;
            s1 = s1 + tmp * y1;
            s2 = s2 + tmp * y2;
            s3 = s3 + tmp * y3;
        }
        if (j++ < len) {
            float tmp = x[xp++];
            y0 = y[yp++];
            s0 = s0 + tmp * y1;
            s1 = s1 + tmp * y2;
            s2 = s2 + tmp * y3;
            s3 = s3 + tmp * y0;
        }
        if (j < len) {
            float tmp = x[xp++];
            y1 = y[yp++];
            s0 = s0 + tmp * y2;
            s1 = s1 + tmp * y3;
            s2 = s2 + tmp * y0;
            s3 = s3 + tmp * y1;
        }
        sum[0] = s0;
        sum[1] = s1;
        sum[2] = s2;
        sum[3] = s3;
    }

    /**
     * _celt_autocorr: autocorrelation of {@code n} samples from {@code x[xOff]}, windowed at both ends when
     * {@code windowed}, for lags 0..lag.
     */
    private void autocorr(float[] xs, int xOff, float[] acOut, boolean windowed, int lag, int n) {
        int fastN = n - lag;
        float[] xp;
        int xpOff;
        if (!windowed) {
            xp = xs;
            xpOff = xOff;
        } else {
            System.arraycopy(xs, xOff, autocorrXx, 0, n);
            for (int i = 0; i < OVERLAP; i++) {
                float w = WINDOW[i];
                autocorrXx[i] = xs[xOff + i] * w;
                autocorrXx[n - i - 1] = xs[xOff + n - i - 1] * w;
            }
            xp = autocorrXx;
            xpOff = 0;
        }
        pitchXcorr(xp, xpOff, xp, xpOff, acOut, fastN, lag + 1);
        for (int k = 0; k <= lag; k++) {
            float d = 0;
            for (int i = k + fastN; i < n; i++) {
                d = d + xp[xpOff + i] * xp[xpOff + i - k];
            }
            acOut[k] += d;
        }
    }

    /** _celt_lpc: Levinson-Durbin recursion, float build. */
    private static void celtLpc(float[] lpcOut, int off, float[] acIn, int p) {
        float error = acIn[0];
        for (int i = 0; i < p; i++) {
            lpcOut[off + i] = 0;
        }
        if (acIn[0] > 1e-10f) {
            for (int i = 0; i < p; i++) {
                // Sum up this iteration's reflection coefficient.
                float rr = 0;
                for (int j = 0; j < i; j++) {
                    rr += lpcOut[off + j] * acIn[i - j];
                }
                rr += acIn[i + 1];
                float r = -((float) rr / error);
                // Update LPC coefficients and total error.
                lpcOut[off + i] = r;
                for (int j = 0; j < (i + 1) >> 1; j++) {
                    float tmp1 = lpcOut[off + j];
                    float tmp2 = lpcOut[off + i - 1 - j];
                    lpcOut[off + j] = tmp1 + r * tmp2;
                    lpcOut[off + i - 1 - j] = tmp2 + r * tmp1;
                }
                error = error - (r * r) * error;
                // Bail out once we get 30 dB gain.
                if (error <= .001f * acIn[0]) {
                    break;
                }
            }
        }
    }

    /** celt_fir_c: y = x filtered by the FIR num, reading {@code ord} samples of history before x. */
    private void celtFir(float[] xs, int xOff, float[] num, int numOff, float[] y, int n, int ord) {
        float[] rnum = new float[ord];
        for (int i = 0; i < ord; i++) {
            rnum[i] = num[numOff + ord - i - 1];
        }
        int i;
        for (i = 0; i < n - 3; i += 4) {
            sum4[0] = xs[xOff + i];
            sum4[1] = xs[xOff + i + 1];
            sum4[2] = xs[xOff + i + 2];
            sum4[3] = xs[xOff + i + 3];
            xcorrKernel(rnum, 0, xs, xOff + i - ord, sum4, ord);
            y[i] = sum4[0];
            y[i + 1] = sum4[1];
            y[i + 2] = sum4[2];
            y[i + 3] = sum4[3];
        }
        for (; i < n; i++) {
            float sum = xs[xOff + i];
            for (int j = 0; j < ord; j++) {
                sum = sum + rnum[j] * xs[xOff + i + j - ord];
            }
            y[i] = sum;
        }
    }

    /** celt_iir: the all-pole synthesis filter, in place on {@code xy[off ..]}, unrolled as the C version. */
    private void celtIir(float[] xy, int off, float[] den, int denOff, int n, int ord, float[] mem) {
        float[] rden = new float[ord];
        float[] y = iirY;
        for (int i = 0; i < ord; i++) {
            rden[i] = den[denOff + ord - i - 1];
        }
        int i;
        for (i = 0; i < ord; i++) {
            y[i] = -mem[ord - i - 1];
        }
        for (; i < n + ord; i++) {
            y[i] = 0;
        }
        for (i = 0; i < n - 3; i += 4) {
            // Unroll by 4 as if it were an FIR filter.
            sum4[0] = xy[off + i];
            sum4[1] = xy[off + i + 1];
            sum4[2] = xy[off + i + 2];
            sum4[3] = xy[off + i + 3];
            xcorrKernel(rden, 0, y, i, sum4, ord);
            // Patch up the result to compensate for the fact that this is an IIR.
            y[i + ord] = -sum4[0];
            xy[off + i] = sum4[0];
            sum4[1] = sum4[1] + y[i + ord] * den[denOff];
            y[i + ord + 1] = -sum4[1];
            xy[off + i + 1] = sum4[1];
            sum4[2] = sum4[2] + y[i + ord + 1] * den[denOff];
            sum4[2] = sum4[2] + y[i + ord] * den[denOff + 1];
            y[i + ord + 2] = -sum4[2];
            xy[off + i + 2] = sum4[2];
            sum4[3] = sum4[3] + y[i + ord + 2] * den[denOff];
            sum4[3] = sum4[3] + y[i + ord + 1] * den[denOff + 1];
            sum4[3] = sum4[3] + y[i + ord] * den[denOff + 2];
            y[i + ord + 3] = -sum4[3];
            xy[off + i + 3] = sum4[3];
        }
        for (; i < n; i++) {
            float sum = xy[off + i];
            for (int j = 0; j < ord; j++) {
                sum -= rden[j] * y[i + j];
            }
            y[i + ord] = sum;
            xy[off + i] = sum;
        }
        for (i = 0; i < ord; i++) {
            mem[i] = xy[off + n - i - 1];
        }
    }

    /** The FLOAT_APPROX celt_exp2 of libopus 1.6: a degree-5 polynomial on the fraction, then the exponent bits. */
    static float celtExp2(float x) {
        int integer = (int) Math.floor(x);
        if (integer < -50) {
            return 0;
        }
        float frac = x - integer;
        float r = 9.999999403953552246093750000000e-01f + frac * (6.931530833244323730468750000000e-01f
                + frac * (2.401536107063293457031250000000e-01f
                + frac * (5.582631751894950866699218750000e-02f
                + frac * (8.989339694380760192871093750000e-03f
                + frac * (1.877576694823801517486572265625e-03f)))));
        int bits = (Float.floatToRawIntBits(r) + (integer << 23)) & 0x7fffffff;
        return Float.intBitsToFloat(bits);
    }

    /** MAX32/MAXG of the float build: a plain comparison, unlike Math.max on signed zeros and NaN. */
    static float maxf(float a, float b) {
        return a > b ? a : b;
    }

    static float minf(float a, float b) {
        return a < b ? a : b;
    }
}

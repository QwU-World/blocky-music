package ru.gora.musicbattle.audio.codec;

/**
 * Parametric stereo (HE-AAC v2; ISO/IEC 14496-3, 8.6.4): turns the mono QMF signal of an SBR element into left and
 * right, from inter-channel intensity differences (IID) and coherences (ICC) sent a few times per frame.
 *
 * <p>The processing is a Java port of the floating-point PS decoder in Ittiam's libxaac
 * (decoder/ixheaacd_ps_dec_flt.c: ixheaacd_hyb_anal, ixheaacd_hyb_synth, ixheaacd_esbr_apply_ps,
 * ixheaacd_esbr_ps_de_correlate, ixheaacd_esbr_ps_apply_rotation), Copyright (C) 2018 The Android Open Source
 * Project, Apache License 2.0, https://github.com/ittiam-systems/libxaac at commit
 * 2fbadd57bca46693b7a077bcf1513131106d960c, with its tables in {@link AacPsData}. The bitstream reader is written
 * from the specification. Like libxaac's HE-AAC v2 decoder this is the specification's baseline PS: 20 stereo bands
 * through the hybrid filter bank, 34-band parameters mapped onto them, and the phase parameters (IPD/OPD) of the PS
 * extension skipped by their length. Mixing follows procedure A for icc_mode 0 to 2 and procedure B above, as the
 * specification and ffmpeg do.
 *
 * <p>Steps per frame: the three lowest QMF bands are split by the hybrid filters into 12 channels (two pairs of which
 * are merged, leaving 10), a decorrelated signal is made by fractional-delay all-pass filters with transient ducking
 * (simple delays in the upper bands), and left and right are mixed from the signal and its decorrelated copy with
 * matrices interpolated across each envelope.
 */
final class AacPs {
    private static final int SLOTS = 32;
    private static final int BINS = 20;
    private static final int SUBQMF_GROUPS = 10;
    private static final int GROUPS = 22;
    private static final int HYBRID = 12;
    private static final int FILTER = 13;
    private static final int DELAY = 6;
    private static final int ALL_PASS_BANDS = 23;
    private static final int DEL_ALL_PASS = 2;
    private static final int HIGH_DEL = 14;
    private static final int[] SERIAL_DELAY = {3, 4, 5};
    private static final double PEAK_DECAY = 0.765928338364649;
    private static final double SMOOTH = 0.25;
    private static final int DECAY_CUTOFF = 3;
    private static final double DECAY_SLOPE = 0.05;
    private static final int[] NUM_ENV_FIX = {0, 1, 2, 4};
    private static final int[] BANDS = {10, 20, 34};

    private static final int[][] BOOKS = new int[6][];

    static {
        int[][] codes = {AacSbrData.PS_IID_TIME, AacSbrData.PS_IID_FREQ, AacSbrData.PS_IID_FINE_TIME,
                AacSbrData.PS_IID_FINE_FREQ, AacSbrData.PS_ICC_TIME, AacSbrData.PS_ICC_FREQ};
        for (int i = 0; i < codes.length; i++) {
            BOOKS[i] = AacSbr.HuffmanTable.build(codes[i]);
        }
    }

    // Bitstream.
    private boolean enableIid;
    private boolean enableIcc;
    private int iidMode;
    private int iccMode;
    private boolean iidFine;
    private boolean mixingB;
    private boolean enableExt;
    /** Whether the modes above come from a header read since the start, or since PS data last failed to parse. */
    private boolean headerSeen;
    private boolean present;
    private int numEnv;
    private final int[] border = new int[7];
    private final int[][] iid = new int[6][34];
    private final int[][] icc = new int[6][34];
    private final int[] iidPrev = new int[34];
    private final int[] iccPrev = new int[34];
    private int iidPrevBands = 20;
    private int iccPrevBands = 20;
    /** The last envelope's parameters on the 20 bins, which a frame without PS data or envelopes keeps using. */
    private final int[] iidHold = new int[BINS];
    private final int[] iccHold = new int[BINS];

    // Hybrid filter history: 12 slots per hybrid QMF band.
    private final double[][] histRe = new double[3][FILTER - 1];
    private final double[][] histIm = new double[3][FILTER - 1];
    private final double[] workRe = new double[FILTER - 1 + SLOTS];
    private final double[] workIm = new double[FILTER - 1 + SLOTS];
    private final double[][] hybLRe = new double[SLOTS][HYBRID];
    private final double[][] hybLIm = new double[SLOTS][HYBRID];
    private final double[][] hybRRe = new double[SLOTS][HYBRID];
    private final double[][] hybRIm = new double[SLOTS][HYBRID];

    // Decorrelator.
    private final double[][] subDelayRe = new double[DEL_ALL_PASS][HYBRID];
    private final double[][] subDelayIm = new double[DEL_ALL_PASS][HYBRID];
    private final double[][][] subSerRe = new double[3][5][HYBRID];
    private final double[][][] subSerIm = new double[3][5][HYBRID];
    private final double[][] qmfDelayRe = new double[HIGH_DEL][64];
    private final double[][] qmfDelayIm = new double[HIGH_DEL][64];
    private final double[][][] qmfSerRe = new double[3][5][64];
    private final double[][][] qmfSerIm = new double[3][5][64];
    private final int[] qmfDelayIdx = new int[64];
    private int delayIdx;
    private final int[] serIdx = new int[3];
    private final double[] peakDecay = new double[BINS];
    private final double[] prevNrg = new double[BINS];
    private final double[] prevPeakDiff = new double[BINS];
    private final double[][] power = new double[SLOTS][BINS];
    private final double[][] transientRatio = new double[SLOTS][BINS];

    // Mixing matrices of the previous envelope, per bin.
    private final double[] h11Prev = new double[BINS];
    private final double[] h12Prev = new double[BINS];
    private final double[] h21Prev = new double[BINS];
    private final double[] h22Prev = new double[BINS];
    private final double[] h11 = new double[BINS];
    private final double[] h12 = new double[BINS];
    private final double[] h21 = new double[BINS];
    private final double[] h22 = new double[BINS];

    AacPs() {
        java.util.Arrays.fill(h11Prev, 1);
        java.util.Arrays.fill(h12Prev, 1);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Bitstream (8.4)

    /**
     * Reads ps_data(). Returns false, with the reader moved to the end of {@code bitsLeft}, when the data cannot be read
     * because no PS header has been read yet; otherwise the reader is left anywhere inside its {@code bitsLeft}, which
     * the caller skips to the end. Data that does not parse is damage, and nothing more is read until the next header.
     */
    boolean read(AacBits b, int bitsLeft) throws AudioFormatException {
        try {
            return readData(b, bitsLeft);
        } catch (AudioFormatException | RuntimeException e) {
            // The modes in use may be what made the data unreadable, so they are not trusted again before a header.
            headerSeen = false;
            throw e;
        }
    }

    private boolean readData(AacBits b, int bitsLeft) throws AudioFormatException {
        int start = b.position();
        if (b.readBit()) {
            // The header is read in full and checked before any of it is used: a header with a reserved mode is
            // damage, and keeping half of it would make every later frame read its parameters wrongly.
            boolean newEnableIid = b.readBit();
            int newIidMode = newEnableIid ? b.read(3) : iidMode;
            boolean newEnableIcc = b.readBit();
            int newIccMode = newEnableIcc ? b.read(3) : iccMode;
            boolean newEnableExt = b.readBit();
            if (newEnableIid && newIidMode > 5 || newEnableIcc && newIccMode > 5) {
                throw new AudioFormatException("Reserved PS mode");
            }
            enableIid = newEnableIid;
            iidMode = newIidMode;
            enableIcc = newEnableIcc;
            iccMode = newIccMode;
            enableExt = newEnableExt;
            headerSeen = true;
        } else if (!headerSeen) {
            // How many parameters a frame carries, and how they are coded, is only in the header, which an encoder may
            // send once a second or only at the start (Apple's does it one frame before every SBR header). A decoder
            // that starts part way into a stream, as on a recording of a broadcast, cannot read the data until the
            // next header comes. Read with guessed modes it parses as garbage: the rest of the extension then looks
            // like more extensions, and in a test one of those was taken for a header with a reserved mode.
            b.seek(start + bitsLeft);
            return false;
        }
        iidFine = iidMode > 2;
        mixingB = iccMode > 2;
        int iidBands = BANDS[iidMode % 3];
        int iccBands = BANDS[iccMode % 3];
        boolean frameClass = b.readBit();
        int envIdx = b.read(2);
        numEnv = frameClass ? envIdx + 1 : NUM_ENV_FIX[envIdx];
        if (frameClass) {
            for (int e = 1; e <= numEnv; e++) {
                border[e] = b.read(5) + 1;
            }
        }
        for (int e = 0; e < numEnv; e++) {
            if (enableIid) {
                boolean dt = b.readBit();
                int bookT = iidFine ? 2 : 0;
                int lav = iidFine ? 30 : 14;
                int limit = iidFine ? 15 : 7;
                readParameters(b, iid[e], e == 0 ? iidPrev : iid[e - 1], e == 0 ? iidPrevBands : iidBands, iidBands,
                        dt, BOOKS[dt ? bookT : bookT + 1], lav, -limit, limit);
            }
        }
        for (int e = 0; e < numEnv; e++) {
            if (enableIcc) {
                boolean dt = b.readBit();
                readParameters(b, icc[e], e == 0 ? iccPrev : icc[e - 1], e == 0 ? iccPrevBands : iccBands, iccBands,
                        dt, BOOKS[dt ? 4 : 5], 7, 0, 7);
            }
        }
        if (enableExt) {
            // ps_extension(): only IPD/OPD is defined, which the baseline decoder does not use; skipped by length.
            int count = b.read(4);
            if (count == 15) {
                count += b.read(8);
            }
            b.skip(8 * count);
        }
        if (b.position() - start > bitsLeft) {
            throw new AudioFormatException("PS data runs past its extension");
        }
        if (numEnv > 0) {
            // Disabled parameters are zero, and the next time-coded envelope continues from those zeros.
            if (enableIid) {
                System.arraycopy(iid[numEnv - 1], 0, iidPrev, 0, iidBands);
            } else {
                java.util.Arrays.fill(iidPrev, 0);
            }
            iidPrevBands = iidBands;
            if (enableIcc) {
                System.arraycopy(icc[numEnv - 1], 0, iccPrev, 0, iccBands);
            } else {
                java.util.Arrays.fill(iccPrev, 0);
            }
            iccPrevBands = iccBands;
        }
        // Everything from here works on 20 bins.
        for (int e = 0; e < numEnv; e++) {
            toBins(iid[e], enableIid ? iidBands : 0);
            toBins(icc[e], enableIcc ? iccBands : 0);
        }
        // Borders in slots: envelope e covers [border[e], border[e + 1]).
        if (numEnv == 0) {
            numEnv = 1;
            System.arraycopy(iidHold, 0, iid[0], 0, BINS);
            System.arraycopy(iccHold, 0, icc[0], 0, BINS);
            border[0] = 0;
            border[1] = SLOTS;
        } else if (!frameClass) {
            border[0] = 0;
            for (int e = 1; e < numEnv; e++) {
                border[e] = e * SLOTS / numEnv;
            }
            border[numEnv] = SLOTS;
        } else {
            border[0] = 0;
            if (border[numEnv] < SLOTS) {
                numEnv++;
                border[numEnv] = SLOTS;
                System.arraycopy(iid[numEnv - 2], 0, iid[numEnv - 1], 0, BINS);
                System.arraycopy(icc[numEnv - 2], 0, icc[numEnv - 1], 0, BINS);
            }
            for (int e = 1; e < numEnv; e++) {
                int high = SLOTS - (numEnv - e);
                if (border[e] > high) {
                    border[e] = high;
                } else if (border[e] < border[e - 1] + 1) {
                    border[e] = border[e - 1] + 1;
                }
            }
        }
        System.arraycopy(iid[numEnv - 1], 0, iidHold, 0, BINS);
        System.arraycopy(icc[numEnv - 1], 0, iccHold, 0, BINS);
        present = true;
        return true;
    }

    /** Delta-decodes one envelope's parameters, in time from the previous envelope or along frequency. */
    private static void readParameters(AacBits b, int[] out, int[] prev, int prevBands, int bands, boolean dt,
                                       int[] book, int lav, int min, int max) {
        for (int k = 0; k < bands; k++) {
            int delta = AacSbr.HuffmanTable.decode(b, book) - lav;
            int v;
            if (dt) {
                int from;
                if (prevBands == bands) {
                    from = prev[k];
                } else {
                    // A change of resolution between envelopes: the band of the old resolution covering this one.
                    from = prev[Math.min(prevBands - 1, k * prevBands / bands)];
                }
                v = from + delta;
            } else {
                v = k == 0 ? delta : out[k - 1] + delta;
            }
            out[k] = Math.max(min, Math.min(max, v));
        }
    }

    /** Spreads 10 parameters over the 20 bins, or folds 34 onto them as the baseline decoder does. */
    private static void toBins(int[] p, int bands) {
        if (bands == 0) {
            java.util.Arrays.fill(p, 0, BINS, 0);
        } else if (bands == 10) {
            for (int i = 19; i >= 0; i--) {
                p[i] = p[i >> 1];
            }
        } else if (bands == 34) {
            p[0] = (2 * p[0] + p[1]) / 3;
            p[1] = (p[1] + 2 * p[2]) / 3;
            p[2] = (2 * p[3] + p[4]) / 3;
            p[3] = (p[4] + 2 * p[5]) / 3;
            p[4] = (p[6] + p[7]) / 2;
            p[5] = (p[8] + p[9]) / 2;
            p[6] = p[10];
            p[7] = p[11];
            p[8] = (p[12] + p[13]) / 2;
            p[9] = (p[14] + p[15]) / 2;
            p[10] = p[16];
            p[11] = p[17];
            p[12] = p[18];
            p[13] = p[19];
            p[14] = (p[20] + p[21]) / 2;
            p[15] = (p[22] + p[23]) / 2;
            p[16] = (p[24] + p[25]) / 2;
            p[17] = (p[26] + p[27]) / 2;
            p[18] = (p[28] + p[29] + p[30] + p[31]) / 4;
            p[19] = (p[32] + p[33]) / 2;
        }
    }

    /** Whether the frame just read had PS data; a frame without it holds the last parameters. */
    void noData() {
        if (!present) {
            return;
        }
        numEnv = 1;
        border[0] = 0;
        border[1] = SLOTS;
        System.arraycopy(iidHold, 0, iid[0], 0, BINS);
        System.arraycopy(iccHold, 0, icc[0], 0, BINS);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Processing (ixheaacd_esbr_apply_ps)

    /**
     * Makes left and right from the mono QMF signal.
     *
     * @param re the mono signal, 32 slots of 64 bands, plus 6 more slots of the three lowest bands as look-ahead for
     *     the hybrid filters; becomes the left channel
     * @param rightRe receives the right channel's 32 slots
     * @param usb the first band above the SBR range, where nothing is to be decorrelated
     */
    void apply(double[][] re, double[][] im, double[][] rightRe, double[][] rightIm, int usb) {
        for (int sb = Math.max(usb, 0); sb < 64; sb++) {
            for (int i = 0; i < 3; i++) {
                for (int k = 0; k < SERIAL_DELAY[i]; k++) {
                    qmfSerRe[i][k][sb] = 0;
                    qmfSerIm[i][k][sb] = 0;
                }
            }
            for (int k = 0; k < HIGH_DEL; k++) {
                qmfDelayRe[k][sb] = 0;
                qmfDelayIm[k][sb] = 0;
            }
        }
        hybridAnalysis(re, im);
        for (int k = 0; k < SLOTS; k++) {
            hybLRe[k][3] += hybLRe[k][4];
            hybLIm[k][3] += hybLIm[k][4];
            hybLRe[k][4] = 0;
            hybLIm[k][4] = 0;
            hybLRe[k][2] += hybLRe[k][5];
            hybLIm[k][2] += hybLIm[k][5];
            hybLRe[k][5] = 0;
            hybLIm[k][5] = 0;
        }
        decorrelate(re, im, rightRe, rightIm);
        rotate(re, im, rightRe, rightIm);
        hybridSynthesis(hybLRe, hybLIm, re, im);
        hybridSynthesis(hybRRe, hybRIm, rightRe, rightIm);
    }

    /** Splits QMF bands 0, 1 and 2 into 8, 2 and 2 channels with 13-tap filters centred on each slot. */
    private void hybridAnalysis(double[][] re, double[][] im) {
        int offset = 0;
        for (int band = 0; band < 3; band++) {
            int channels = AacPsData.BAND_RES_HYB20[band];
            System.arraycopy(histRe[band], 0, workRe, 0, FILTER - 1);
            System.arraycopy(histIm[band], 0, workIm, 0, FILTER - 1);
            for (int n = 0; n < SLOTS; n++) {
                workRe[FILTER - 1 + n] = re[n + DELAY][band];
                workIm[FILTER - 1 + n] = im[n + DELAY][band];
            }
            System.arraycopy(workRe, SLOTS, histRe[band], 0, FILTER - 1);
            System.arraycopy(workIm, SLOTS, histIm[band], 0, FILTER - 1);
            if (channels == 8) {
                double[] p = AacPsData.P8_13_20;
                double[] mod = AacPsData.COS_SIN_MOD_8CHANNEL;
                for (int i = 0; i < SLOTS; i++) {
                    for (int q = 0; q < 8; q++) {
                        double r = 0;
                        double m = 0;
                        int t = q * 2 * FILTER;
                        for (int n = 0; n < FILTER; n++, t += 2) {
                            double c = mod[t];
                            double s = mod[t + 1];
                            double xr = workRe[n + i];
                            double xi = workIm[n + i];
                            r += p[n] * (xr * c - xi * s);
                            m += p[n] * (xi * c + xr * s);
                        }
                        hybLRe[i][offset + q] = r;
                        hybLIm[i][offset + q] = m;
                    }
                }
            } else {
                double[] p = AacPsData.P2_13_20;
                double[] mod = AacPsData.COS_MOD_2CHANNEL;
                for (int i = 0; i < SLOTS; i++) {
                    for (int q = 0; q < 2; q++) {
                        double r = 0;
                        double m = 0;
                        int t = q * FILTER;
                        for (int n = 0; n < FILTER; n++, t++) {
                            double c = mod[t];
                            r += p[n] * workRe[n + i] * c;
                            m += p[n] * workIm[n + i] * c;
                        }
                        hybLRe[i][offset + q] = r;
                        hybLIm[i][offset + q] = m;
                    }
                }
            }
            offset += channels;
        }
    }

    private static void hybridSynthesis(double[][] hRe, double[][] hIm, double[][] re, double[][] im) {
        int offset = 0;
        for (int band = 0; band < 3; band++) {
            int channels = AacPsData.BAND_RES_HYB20[band];
            for (int n = 0; n < SLOTS; n++) {
                double r = 0;
                double m = 0;
                for (int k = 0; k < channels; k++) {
                    r += hRe[n][offset + k];
                    m += hIm[n][offset + k];
                }
                re[n][band] = r;
                im[n][band] = m;
            }
            offset += channels;
        }
    }

    private static int bin(int group) {
        return AacPsData.BIN_GROUP_MAP_20[group] & ~0x1000;
    }

    /** ixheaacd_esbr_ps_de_correlate: transient ducking and the all-pass decorrelator. */
    private void decorrelate(double[][] re, double[][] im, double[][] rRe, double[][] rIm) {
        int[] borders = AacPsData.GROUP_BORDERS_20_TBL;
        int from = border[0];
        int to = border[numEnv];
        for (int k = 0; k < SLOTS; k++) {
            java.util.Arrays.fill(power[k], 0);
        }
        for (int gr = 0; gr < SUBQMF_GROUPS; gr++) {
            int b = bin(gr);
            int sb = borders[gr];
            for (int k = from; k < to; k++) {
                double x = hybLRe[k][sb];
                double y = hybLIm[k][sb];
                power[k][b] += x * x + y * y;
            }
        }
        for (int gr = SUBQMF_GROUPS; gr < GROUPS; gr++) {
            int b = bin(gr);
            for (int sb = borders[gr]; sb < borders[gr + 1]; sb++) {
                for (int k = from; k < to; k++) {
                    double x = re[k][sb];
                    double y = im[k][sb];
                    power[k][b] += x * x + y * y;
                }
            }
        }
        for (int b = 0; b < BINS; b++) {
            for (int k = from; k < to; k++) {
                double p = power[k][b];
                peakDecay[b] *= PEAK_DECAY;
                if (peakDecay[b] < p) {
                    peakDecay[b] = p;
                }
                double peakDiff = prevPeakDiff[b] + SMOOTH * (peakDecay[b] - p - prevPeakDiff[b]);
                prevPeakDiff[b] = peakDiff;
                double nrg = prevNrg[b] + SMOOTH * (p - prevNrg[b]);
                prevNrg[b] = nrg;
                transientRatio[k][b] = 1.5 * peakDiff <= nrg ? 1.0 : nrg / (1.5 * peakDiff);
            }
        }
        double[] decay = AacPsData.ALL_PASS_LINK_DECAY_SER;
        int startDelay = delayIdx;
        int[] startSer = serIdx.clone();
        int endDelay = delayIdx;
        int[] endSer = serIdx.clone();
        // Hybrid channels: all-pass, no decay ramp.
        double[] fRe = AacPsData.FRAC_DELAY_PHASE_FAC_QMF_SUB_RE_20;
        double[] fIm = AacPsData.FRAC_DELAY_PHASE_FAC_QMF_SUB_IM_20;
        double[] sRe = AacPsData.FRAC_DELAY_PHASE_FAC_SER_QMF_SUB_RE_20;
        double[] sIm = AacPsData.FRAC_DELAY_PHASE_FAC_SER_QMF_SUB_IM_20;
        for (int gr = 0; gr < SUBQMF_GROUPS; gr++) {
            int sb = borders[gr];
            int b = bin(gr);
            int ld = startDelay;
            int[] ls = startSer.clone();
            for (int k = from; k < to; k++) {
                double inRe = hybLRe[k][sb];
                double inIm = hybLIm[k][sb];
                double r0 = subDelayRe[ld][sb];
                double i0 = subDelayIm[ld][sb];
                subDelayRe[ld][sb] = inRe;
                subDelayIm[ld][sb] = inIm;
                double rr = r0 * fRe[sb] - i0 * fIm[sb];
                double ri = r0 * fIm[sb] + i0 * fRe[sb];
                for (int m = 0; m < 3; m++) {
                    double d0 = subSerRe[m][ls[m]][sb];
                    double d1 = subSerIm[m][ls[m]][sb];
                    double cr = sRe[sb * 3 + m];
                    double ci = sIm[sb * 3 + m];
                    double xr = d0 * cr - d1 * ci - decay[m] * rr;
                    double xi = d0 * ci + d1 * cr - decay[m] * ri;
                    subSerRe[m][ls[m]][sb] = rr + decay[m] * xr;
                    subSerIm[m][ls[m]][sb] = ri + decay[m] * xi;
                    rr = xr;
                    ri = xi;
                }
                double t = transientRatio[k][b];
                hybRRe[k][sb] = t * rr;
                hybRIm[k][sb] = t * ri;
                if (++ld >= DEL_ALL_PASS) {
                    ld = 0;
                }
                for (int m = 0; m < 3; m++) {
                    if (++ls[m] >= SERIAL_DELAY[m]) {
                        ls[m] = 0;
                    }
                }
            }
            endDelay = ld;
            endSer = ls;
        }
        // QMF bands: all-pass below band 23, plain delays above, the decorrelation fading with frequency.
        fRe = AacPsData.QMF_FRACT_DELAY_PHASE_FACTOR_RE;
        fIm = AacPsData.QMF_FRACT_DELAY_PHASE_FACTOR_IM;
        sRe = AacPsData.QMF_SER_FRACT_DELAY_PHASE_FACTOR_RE;
        sIm = AacPsData.QMF_SER_FRACT_DELAY_PHASE_FACTOR_IM;
        int[] delayLength = AacPsData.QMF_DELAY_IDX_TBL;
        for (int gr = SUBQMF_GROUPS; gr < GROUPS; gr++) {
            int b = bin(gr);
            for (int sb = borders[gr]; sb < borders[gr + 1]; sb++) {
                double scale = sb <= DECAY_CUTOFF ? 1.0 : 1.0 + DECAY_CUTOFF * DECAY_SLOPE - DECAY_SLOPE * sb;
                scale = Math.max(scale, 0.0);
                int ld = startDelay;
                int[] ls = startSer.clone();
                boolean plainDelay = sb >= ALL_PASS_BANDS;
                for (int k = from; k < to; k++) {
                    double inRe = re[k][sb];
                    double inIm = im[k][sb];
                    double rr;
                    double ri;
                    if (plainDelay) {
                        int d = qmfDelayIdx[sb];
                        rr = qmfDelayRe[d][sb];
                        ri = qmfDelayIm[d][sb];
                        qmfDelayRe[d][sb] = inRe;
                        qmfDelayIm[d][sb] = inIm;
                        if (++qmfDelayIdx[sb] >= delayLength[sb]) {
                            qmfDelayIdx[sb] = 0;
                        }
                    } else {
                        double r0 = qmfDelayRe[ld][sb];
                        double i0 = qmfDelayIm[ld][sb];
                        qmfDelayRe[ld][sb] = inRe;
                        qmfDelayIm[ld][sb] = inIm;
                        rr = r0 * fRe[sb] - i0 * fIm[sb];
                        ri = r0 * fIm[sb] + i0 * fRe[sb];
                        for (int m = 0; m < 3; m++) {
                            double d0 = qmfSerRe[m][ls[m]][sb];
                            double d1 = qmfSerIm[m][ls[m]][sb];
                            double cr = sRe[sb * 3 + m];
                            double ci = sIm[sb * 3 + m];
                            double a = scale * decay[m];
                            double xr = d0 * cr - d1 * ci - a * rr;
                            double xi = d0 * ci + d1 * cr - a * ri;
                            qmfSerRe[m][ls[m]][sb] = rr + a * xr;
                            qmfSerIm[m][ls[m]][sb] = ri + a * xi;
                            rr = xr;
                            ri = xi;
                        }
                    }
                    double t = transientRatio[k][b];
                    rRe[k][sb] = t * rr;
                    rIm[k][sb] = t * ri;
                    if (++ld >= DEL_ALL_PASS) {
                        ld = 0;
                    }
                    for (int m = 0; m < 3; m++) {
                        if (++ls[m] >= SERIAL_DELAY[m]) {
                            ls[m] = 0;
                        }
                    }
                }
                endDelay = ld;
                endSer = ls;
            }
        }
        delayIdx = endDelay;
        System.arraycopy(endSer, 0, serIdx, 0, 3);
    }

    /** ixheaacd_esbr_ps_apply_rotation: the mixing matrices, interpolated slot by slot within each envelope. */
    private void rotate(double[][] re, double[][] im, double[][] rRe, double[][] rIm) {
        double[] scaleFactors = iidFine ? AacPsData.SCALE_FACTORS_FINE_FLT : AacPsData.SCALE_FACTORS_FLT;
        int steps = iidFine ? 15 : 7;
        int[] quantizedIid = iidFine ? AacPsData.QUANTIZED_IIDS_FINE : AacPsData.QUANTIZED_IIDS;
        int[] borders = AacPsData.GROUP_BORDERS_20_TBL;
        for (int e = 0; e < numEnv; e++) {
            for (int b = 0; b < BINS; b++) {
                // A header can switch the IID quantiser from fine to coarse in a frame without envelopes, which
                // keeps the previous frame's fine values; they are held to the coarse range rather than read past
                // the coarse tables.
                int ii = Math.max(-steps, Math.min(steps, iid[e][b]));
                int ic = Math.max(0, Math.min(7, icc[e][b]));
                if (!mixingB) {
                    double sr = scaleFactors[steps + ii];
                    double sl = scaleFactors[steps - ii];
                    double alpha = AacPsData.ALPHAS[ic];
                    double beta = alpha * (sr - sl) / Math.sqrt(2);
                    h11[b] = sl * StrictMath.cos(beta + alpha);
                    h12[b] = sr * StrictMath.cos(beta - alpha);
                    h21[b] = sl * StrictMath.sin(beta + alpha);
                    h22[b] = sr * StrictMath.sin(beta - alpha);
                } else {
                    double c = StrictMath.pow(10.0, (ii == 0 ? 0 : Integer.signum(ii) * quantizedIid[Math.abs(ii) - 1])
                            / 20.0);
                    double rho = Math.max(AacPsData.QUANTIZED_RHOS[ic], 0.05);
                    double alpha;
                    if (c == 1.0) {
                        alpha = Math.PI / 4;
                    } else {
                        alpha = 0.5 * StrictMath.atan(2 * c * rho / (c * c - 1));
                        if (alpha < 0) {
                            alpha += Math.PI / 2;
                        }
                    }
                    double mu = c + 1 / c;
                    mu = 1 + (4 * rho * rho - 4) / (mu * mu);
                    double gamma = StrictMath.atan(Math.sqrt((1 - Math.sqrt(mu)) / (1 + Math.sqrt(mu))));
                    h11[b] = Math.sqrt(2) * StrictMath.cos(alpha) * StrictMath.cos(gamma);
                    h12[b] = Math.sqrt(2) * StrictMath.sin(alpha) * StrictMath.cos(gamma);
                    h21[b] = Math.sqrt(2) * -StrictMath.sin(alpha) * StrictMath.sin(gamma);
                    h22[b] = Math.sqrt(2) * StrictMath.cos(alpha) * StrictMath.sin(gamma);
                }
            }
            int start = border[e];
            int end = border[e + 1];
            int length = end - start;
            for (int gr = 0; gr < GROUPS; gr++) {
                int b = bin(gr);
                double a11 = h11Prev[b];
                double a12 = h12Prev[b];
                double a21 = h21Prev[b];
                double a22 = h22Prev[b];
                double d11 = (h11[b] - a11) / length;
                double d12 = (h12[b] - a12) / length;
                double d21 = (h21[b] - a21) / length;
                double d22 = (h22[b] - a22) / length;
                boolean hybrid = gr < SUBQMF_GROUPS;
                int lo = borders[gr];
                int hi = hybrid ? lo + 1 : borders[gr + 1];
                double[][] lRe = hybrid ? hybLRe : re;
                double[][] lIm = hybrid ? hybLIm : im;
                double[][] xRe = hybrid ? hybRRe : rRe;
                double[][] xIm = hybrid ? hybRIm : rIm;
                for (int i = start; i < end; i++) {
                    a11 += d11;
                    a12 += d12;
                    a21 += d21;
                    a22 += d22;
                    double[] lr = lRe[i];
                    double[] li = lIm[i];
                    double[] rr = xRe[i];
                    double[] ri = xIm[i];
                    for (int sb = lo; sb < hi; sb++) {
                        double sRe = lr[sb];
                        double sIm = li[sb];
                        double dRe = rr[sb];
                        double dIm = ri[sb];
                        lr[sb] = a11 * sRe + a21 * dRe;
                        li[sb] = a11 * sIm + a21 * dIm;
                        rr[sb] = a12 * sRe + a22 * dRe;
                        ri[sb] = a12 * sIm + a22 * dIm;
                    }
                }
            }
            System.arraycopy(h11, 0, h11Prev, 0, BINS);
            System.arraycopy(h12, 0, h12Prev, 0, BINS);
            System.arraycopy(h21, 0, h21Prev, 0, BINS);
            System.arraycopy(h22, 0, h22Prev, 0, BINS);
        }
    }
}

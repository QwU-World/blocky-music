package ru.gora.musicbattle.audio.codec;

import java.util.Arrays;

/**
 * Spectral band replication (HE-AAC; ISO/IEC 14496-3, 4.6.18) for one channel element: the 32-band QMF analysis of
 * the core output, the SBR bitstream (header, time/frequency grid, envelopes, noise floors, inverse filtering and
 * sinusoids), the HF generator (patching with a second-order LPC "inverse filter" and chirp factors), the HF
 * adjustment (envelope estimation, gains with limiter and boost, smoothing, noise and sinusoid addition) and the
 * 64-band QMF synthesis that doubles the rate.
 *
 * <p>Written from the specification's equations. The tables (QMF prototype, noise table, Huffman codes) are in
 * {@link AacSbrData} with their Apache-2.0 sources. The QMF banks are computed with an FFT instead of the
 * specification's direct sums; the comments at {@link #analysis} and {@link #synthesis} show the equivalence.
 * Arithmetic is double precision; the signal is scaled to the specification's 16-bit range inside, so the small
 * constants in the gain equations (1 in the denominators, 1e-12 in the limiter) mean what they mean there.
 *
 * <p>Before the first SBR header the element is passed through the QMF banks with the crossover at band 32, which
 * gives the core signal at twice the rate and the same delay as the stream will have once SBR starts; ffmpeg does
 * the same, reading the specification's initial kx' as 32.
 */
final class AacSbr {
    private static final int RATE = 2;
    private static final int SLOTS = 16;
    private static final int T_HFGEN = 8;
    private static final int T_HFADJ = 2;
    /** QMF slots kept in the low and high band buffers: the frame's 32 plus the look-back. */
    private static final int BUFFER_SLOTS = 32 + T_HFGEN;
    private static final double SCALE_IN = 32768.0;
    private static final double SCALE_OUT = 1.0 / 32768.0;

    private static final double[] LIMITER_GAINS = {0.70795, 1.0, 1.41254, 1e10};
    private static final double[] SMOOTH = {0.03183050093751, 0.11516383427084, 0.21816949906249, 0.30150283239582,
            0.33333333333333};
    private static final int[][] START_OFFSETS = {
            {-8, -7, -6, -5, -4, -3, -2, -1, 0, 1, 2, 3, 4, 5, 6, 7},          // 16 kHz
            {-5, -4, -3, -2, -1, 0, 1, 2, 3, 4, 5, 6, 7, 9, 11, 13},           // 22.05 kHz
            {-5, -3, -2, -1, 0, 1, 2, 3, 4, 5, 6, 7, 9, 11, 13, 16},           // 24 kHz
            {-6, -4, -2, -1, 0, 1, 2, 3, 4, 5, 6, 7, 9, 11, 13, 16},           // 32 kHz
            {-4, -2, -1, 0, 1, 2, 3, 4, 5, 6, 7, 9, 11, 13, 16, 20},           // 44.1, 48, 64 kHz
            {-2, -1, 0, 1, 2, 3, 4, 5, 6, 7, 9, 11, 13, 16, 20, 24}};          // above 64 kHz

    private static final int[][] BOOKS = new int[10][];

    static {
        int[][] codes = {AacSbrData.ENV_1_5_TIME, AacSbrData.ENV_1_5_FREQ, AacSbrData.BAL_1_5_TIME,
                AacSbrData.BAL_1_5_FREQ, AacSbrData.ENV_3_0_TIME, AacSbrData.ENV_3_0_FREQ, AacSbrData.BAL_3_0_TIME,
                AacSbrData.BAL_3_0_FREQ, AacSbrData.NOISE_TIME, AacSbrData.NOISE_BAL_TIME};
        for (int i = 0; i < codes.length; i++) {
            BOOKS[i] = HuffmanTable.build(codes[i]);
        }
    }

    private static final int ENV_1_5_T = 0;
    private static final int ENV_1_5_F = 1;
    private static final int BAL_1_5_T = 2;
    private static final int BAL_1_5_F = 3;
    private static final int ENV_3_0_T = 4;
    private static final int ENV_3_0_F = 5;
    private static final int BAL_3_0_T = 6;
    private static final int BAL_3_0_F = 7;
    private static final int NOISE_T = 8;
    private static final int NOISE_BAL_T = 9;

    private final int outRate;
    private final boolean psAllowed;
    private AacPs ps;
    private boolean psUsed;
    private boolean psThisFrame;

    // Header.
    private boolean started;
    private int ampResHeader;
    private int startFreq = -1;
    private int stopFreq;
    private int xoverBand;
    private int freqScale;
    private int alterScale;
    private int noiseBands;
    private int limiterBands;
    private int limiterGains;
    private int interpolFreq;
    private int smoothingMode;

    // Frequency tables (4.6.18.3).
    private int k0;
    private int kx = 32;
    private int m;
    private int kxPrev = 32;
    private int mPrev;
    private int nMaster;
    private final int[] fMaster = new int[65];
    private int nHigh;
    private int nLow;
    private int nQ;
    private int nL;
    private final int[] fHigh = new int[65];
    private final int[] fLow = new int[65];
    private final int[] fNoise = new int[6];
    private final int[] fLim = new int[65];
    private int numPatches;
    private final int[] patchSubbands = new int[6];
    private final int[] patchStart = new int[6];

    /** Whether this frame's SBR data was read and is usable. */
    private boolean frameData;
    private boolean coupling;
    private final Channel[] channels = {new Channel(), new Channel()};

    // Scratch, so a frame allocates nothing.
    private final int[] relLeadScratch = new int[4];
    private final int[] relTrailScratch = new int[4];
    private final double[] analysisU = new double[64];
    private final double[] bwScratch = new double[5];
    private final double[][] gainScratch = new double[5][64];
    private final double[][] noiseScratch = new double[5][64];
    private final double[][] sineScratch = new double[5][64];
    private final double[] eCurrScratch = new double[64];
    private final double[] eOrigScratch = new double[64];
    private final double[] qOrigScratch = new double[64];
    private final boolean[] sIndexScratch = new boolean[64];
    private final boolean[] sMappedScratch = new boolean[64];
    private final double[] gFiltScratch = new double[64];
    private final double[] qFiltScratch = new double[64];
    private final double[] alpha0Re = new double[64];
    private final double[] alpha0Im = new double[64];
    private final double[] alpha1Re = new double[64];
    private final double[] alpha1Im = new double[64];
    private final double[][] xHighRe = new double[BUFFER_SLOTS][64];
    private final double[][] xHighIm = new double[BUFFER_SLOTS][64];
    private final double[] fftRe = new double[32];
    private final double[] fftIm = new double[32];
    private final double[] fftRe2 = new double[32];
    private final double[] fftIm2 = new double[32];
    private final double[] windowScratch = new double[64];
    /** The 32 slots the synthesis gets, plus 6 slots of the lowest bands that parametric stereo looks ahead into. */
    private final double[][] synRe = new double[32 + 6][64];
    private final double[][] synIm = new double[32 + 6][64];

    /** Per-channel state, kept from frame to frame. */
    static final class Channel {
        // Grid of the current frame.
        int frameClass;
        int numEnv;
        int numNoise;
        int pointer;
        final int[] freqRes = new int[6];
        final int[] tE = new int[6];
        final int[] tQ = new int[3];
        int lA;
        int ampRes;
        final boolean[] dfEnv = new boolean[5];
        final boolean[] dfNoise = new boolean[2];
        final int[] invfMode = new int[5];
        final int[][] env = new int[5][64];
        final int[][] noise = new int[2][5];
        boolean addHarmonicFlag;
        final boolean[] addHarmonic = new boolean[64];

        // From the previous frame.
        final int[] envPrev = new int[64];
        int resPrev;
        final int[] noisePrev = new int[5];
        final int[] invfModePrev = new int[5];
        final double[] bwPrev = new double[5];
        final boolean[] addHarmonicPrev = new boolean[64];
        boolean addHarmonicFlagPrev;
        int lAPrev = -1;
        int numEnvPrev = 1;
        int indexNoise;
        int indexSine;
        boolean reset = true;
        final double[][] gHistory = new double[4][64];
        final double[][] qHistory = new double[4][64];

        // Dequantised values of the current frame, by envelope and band.
        final double[][] eOrig = new double[5][64];
        final double[][] qOrig = new double[2][5];

        // QMF banks.
        final double[] analysis = new double[2 * 320];
        int analysisAt;
        final double[] synthesis = new double[2 * 1280];
        int synthesisAt;
        final double[][] lowRe = new double[BUFFER_SLOTS][32];
        final double[][] lowIm = new double[BUFFER_SLOTS][32];
        final double[][] yRe = new double[BUFFER_SLOTS][64];
        final double[][] yIm = new double[BUFFER_SLOTS][64];
    }

    /**
     * @param coreRate the core's sampling rate; SBR runs at twice it
     * @param psAllowed whether this element may carry parametric stereo (a mono element of a stream that does not
     *     rule it out)
     */
    AacSbr(int coreRate, boolean psAllowed) {
        this.outRate = coreRate * 2;
        this.psAllowed = psAllowed;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Bitstream (4.4.2.8)

    /**
     * Reads one sbr_extension_data() payload. A payload that does not parse turns SBR off until the next header, as
     * a decoder recovering from a bit error does; the core audio is not affected.
     */
    void readData(AacBits b, boolean crc, int endBit, boolean pair) throws AudioFormatException {
        frameData = false;
        int start = b.position();
        try {
            if (crc) {
                b.skip(10);
            }
            if (b.readBit()) {
                readHeader(b);
            }
            if (!started) {
                return;
            }
            if (pair) {
                readChannelPair(b, endBit);
            } else {
                readSingleChannel(b, endBit);
            }
            if (b.position() > endBit) {
                throw new AudioFormatException("SBR data runs past its element");
            }
            frameData = true;
        } catch (AudioFormatException | RuntimeException e) {
            // RuntimeException covers an index past a table end from damaged data; nothing else throws here.
            started = false;
            startFreq = -1;
            frameData = false;
        }
        b.seek(Math.max(start, endBit));
    }

    private void readHeader(AacBits b) throws AudioFormatException {
        int ampRes = b.read(1);
        int start = b.read(4);
        int stop = b.read(4);
        int xover = b.read(3);
        b.skip(2);
        boolean extra1 = b.readBit();
        boolean extra2 = b.readBit();
        int scale = 2;
        int alter = 1;
        int noise = 2;
        if (extra1) {
            scale = b.read(2);
            alter = b.read(1);
            noise = b.read(2);
        }
        int limBands = 2;
        int limGains = 2;
        int interpol = 1;
        int smoothing = 1;
        if (extra2) {
            limBands = b.read(2);
            limGains = b.read(2);
            interpol = b.read(1);
            smoothing = b.read(1);
        }
        boolean changed = start != startFreq || stop != stopFreq || xover != xoverBand || scale != freqScale
                || alter != alterScale || noise != noiseBands;
        ampResHeader = ampRes;
        limiterBands = limBands;
        limiterGains = limGains;
        interpolFreq = interpol;
        smoothingMode = smoothing;
        if (changed || !started) {
            startFreq = start;
            stopFreq = stop;
            xoverBand = xover;
            freqScale = scale;
            alterScale = alter;
            noiseBands = noise;
            computeFrequencyTables();
            for (Channel c : channels) {
                c.reset = true;
            }
        }
        computeLimiterTable();
        started = true;
    }

    private void readSingleChannel(AacBits b, int endBit) throws AudioFormatException {
        coupling = false;
        if (b.readBit()) {
            b.skip(4);
        }
        Channel c = channels[0];
        readGrid(b, c);
        readDtdf(b, c);
        readInvf(b, c);
        readEnvelope(b, c, false, false);
        readNoise(b, c, false, false);
        c.addHarmonicFlag = b.readBit();
        if (c.addHarmonicFlag) {
            readSinusoids(b, c);
        } else {
            Arrays.fill(c.addHarmonic, false);
        }
        readExtensions(b, endBit, true);
    }

    private void readChannelPair(AacBits b, int endBit) throws AudioFormatException {
        if (b.readBit()) {
            b.skip(8);
        }
        Channel c0 = channels[0];
        Channel c1 = channels[1];
        coupling = b.readBit();
        if (coupling) {
            readGrid(b, c0);
            copyGrid(c0, c1);
            readDtdf(b, c0);
            readDtdf(b, c1);
            readInvf(b, c0);
            System.arraycopy(c0.invfMode, 0, c1.invfMode, 0, 5);
            readEnvelope(b, c0, true, false);
            readNoise(b, c0, true, false);
            readEnvelope(b, c1, true, true);
            readNoise(b, c1, true, true);
        } else {
            readGrid(b, c0);
            readGrid(b, c1);
            readDtdf(b, c0);
            readDtdf(b, c1);
            readInvf(b, c0);
            readInvf(b, c1);
            readEnvelope(b, c0, false, false);
            readEnvelope(b, c1, false, false);
            readNoise(b, c0, false, false);
            readNoise(b, c1, false, false);
        }
        c0.addHarmonicFlag = b.readBit();
        if (c0.addHarmonicFlag) {
            readSinusoids(b, c0);
        } else {
            Arrays.fill(c0.addHarmonic, false);
        }
        c1.addHarmonicFlag = b.readBit();
        if (c1.addHarmonicFlag) {
            readSinusoids(b, c1);
        } else {
            Arrays.fill(c1.addHarmonic, false);
        }
        readExtensions(b, endBit, false);
    }

    /** sbr_extension(): parametric stereo data rides here (extension id 2); anything else is skipped. */
    private void readExtensions(AacBits b, int endBit, boolean single) throws AudioFormatException {
        if (!b.readBit()) {
            return;
        }
        int count = b.read(4);
        if (count == 15) {
            count += b.read(8);
        }
        int bitsLeft = 8 * count;
        int end = b.position() + bitsLeft;
        while (bitsLeft > 7) {
            int id = b.read(2);
            bitsLeft -= 2;
            int before = b.position();
            if (id == 2 && single && psAllowed) {
                if (ps == null) {
                    ps = new AacPs();
                }
                // Until the first PS header the data cannot be read, and both outputs keep the mono signal.
                if (ps.read(b, bitsLeft)) {
                    psUsed = true;
                    psThisFrame = true;
                }
            } else {
                b.skip(bitsLeft);
            }
            bitsLeft -= b.position() - before;
        }
        b.seek(end);
        if (end > endBit) {
            throw new AudioFormatException("SBR extension past its element");
        }
    }

    private static void copyGrid(Channel from, Channel to) {
        to.frameClass = from.frameClass;
        to.numEnv = from.numEnv;
        to.numNoise = from.numNoise;
        to.pointer = from.pointer;
        to.lA = from.lA;
        to.ampRes = from.ampRes;
        System.arraycopy(from.freqRes, 0, to.freqRes, 0, 6);
        System.arraycopy(from.tE, 0, to.tE, 0, 6);
        System.arraycopy(from.tQ, 0, to.tQ, 0, 3);
    }

    /** sbr_grid() and the time borders it implies (4.6.18.3.3). */
    private void readGrid(AacBits b, Channel c) throws AudioFormatException {
        int frameClass = b.read(2);
        int absLead;
        int absTrail;
        int relLead;
        int relTrail;
        int[] relBordLead = relLeadScratch;
        int[] relBordTrail = relTrailScratch;
        int numEnv;
        int pointer = 0;
        switch (frameClass) {
            case 0 -> { // FIXFIX
                numEnv = 1 << b.read(2);
                if (numEnv > 4) {
                    throw new AudioFormatException("SBR FIXFIX frame with " + numEnv + " envelopes");
                }
                int res = b.read(1);
                Arrays.fill(c.freqRes, 0, numEnv, res);
                absLead = 0;
                absTrail = SLOTS;
                relLead = numEnv - 1;
                relTrail = 0;
                for (int i = 0; i < relLead; i++) {
                    relBordLead[i] = SLOTS / numEnv;
                }
            }
            case 1 -> { // FIXVAR
                int varBord1 = b.read(2);
                relTrail = b.read(2);
                numEnv = relTrail + 1;
                for (int i = 0; i < relTrail; i++) {
                    relBordTrail[i] = 2 * b.read(2) + 2;
                }
                pointer = b.read(pointerBits(numEnv));
                for (int i = 0; i < numEnv; i++) {
                    c.freqRes[numEnv - 1 - i] = b.read(1);
                }
                absLead = 0;
                absTrail = varBord1 + SLOTS;
                relLead = 0;
            }
            case 2 -> { // VARFIX
                int varBord0 = b.read(2);
                relLead = b.read(2);
                numEnv = relLead + 1;
                for (int i = 0; i < relLead; i++) {
                    relBordLead[i] = 2 * b.read(2) + 2;
                }
                pointer = b.read(pointerBits(numEnv));
                for (int i = 0; i < numEnv; i++) {
                    c.freqRes[i] = b.read(1);
                }
                absLead = varBord0;
                absTrail = SLOTS;
                relTrail = 0;
            }
            default -> { // VARVAR
                int varBord0 = b.read(2);
                int varBord1 = b.read(2);
                relLead = b.read(2);
                relTrail = b.read(2);
                numEnv = relLead + relTrail + 1;
                if (numEnv > 5) {
                    throw new AudioFormatException("SBR VARVAR frame with " + numEnv + " envelopes");
                }
                for (int i = 0; i < relLead; i++) {
                    relBordLead[i] = 2 * b.read(2) + 2;
                }
                for (int i = 0; i < relTrail; i++) {
                    relBordTrail[i] = 2 * b.read(2) + 2;
                }
                pointer = b.read(pointerBits(numEnv));
                for (int i = 0; i < numEnv; i++) {
                    c.freqRes[i] = b.read(1);
                }
                absLead = varBord0;
                absTrail = varBord1 + SLOTS;
            }
        }
        if (pointer > numEnv + 1) {
            throw new AudioFormatException("SBR envelope pointer out of range");
        }
        c.frameClass = frameClass;
        c.numEnv = numEnv;
        c.pointer = pointer;
        c.numNoise = numEnv > 1 ? 2 : 1;
        c.ampRes = frameClass == 0 && numEnv == 1 ? 0 : ampResHeader;
        int[] t = c.tE;
        t[0] = absLead;
        t[numEnv] = absTrail;
        for (int l = 1; l <= relLead; l++) {
            t[l] = t[l - 1] + relBordLead[l - 1];
        }
        for (int l = numEnv - 1; l > numEnv - 1 - relTrail; l--) {
            t[l] = t[l + 1] - relBordTrail[numEnv - 1 - l];
        }
        for (int l = 0; l < numEnv; l++) {
            if (t[l] >= t[l + 1] || t[l] < 0) {
                throw new AudioFormatException("SBR envelope borders out of order");
            }
        }
        // The transient's envelope (4.6.18.3.3, l_A) and the noise floor borders.
        int lA = -1;
        switch (frameClass) {
            case 1, 3 -> lA = pointer > 0 ? numEnv + 1 - pointer : -1;
            case 2 -> lA = pointer > 1 ? pointer - 1 : -1;
            default -> {
            }
        }
        c.lA = lA;
        c.tQ[0] = t[0];
        if (numEnv == 1) {
            c.tQ[1] = t[1];
        } else {
            int middle;
            switch (frameClass) {
                case 0 -> middle = numEnv / 2;
                case 2 -> middle = pointer == 0 ? 1 : pointer == 1 ? numEnv - 1 : pointer - 1;
                default -> middle = pointer > 1 ? numEnv + 1 - pointer : numEnv - 1;
            }
            if (middle <= 0 || middle >= numEnv) {
                throw new AudioFormatException("SBR noise floor border out of range");
            }
            c.tQ[1] = t[middle];
            c.tQ[2] = t[numEnv];
        }
    }

    private static int pointerBits(int numEnv) {
        // ceil(log2(numEnv + 1))
        return 32 - Integer.numberOfLeadingZeros(numEnv);
    }

    private void readDtdf(AacBits b, Channel c) {
        for (int i = 0; i < c.numEnv; i++) {
            c.dfEnv[i] = b.readBit();
        }
        for (int i = 0; i < c.numNoise; i++) {
            c.dfNoise[i] = b.readBit();
        }
    }

    private void readInvf(AacBits b, Channel c) {
        for (int i = 0; i < nQ; i++) {
            c.invfMode[i] = b.read(2);
        }
    }

    /** sbr_envelope(): delta-coded scale factors, made absolute here (4.6.18.3.4). */
    private void readEnvelope(AacBits b, Channel c, boolean coupled, boolean balance) throws AudioFormatException {
        boolean fine = c.ampRes == 0;
        int tBook;
        int fBook;
        int lav;
        int startBits;
        if (balance) {
            tBook = fine ? BAL_1_5_T : BAL_3_0_T;
            fBook = fine ? BAL_1_5_F : BAL_3_0_F;
            lav = fine ? 24 : 12;
            startBits = fine ? 6 : 5;
        } else {
            tBook = fine ? ENV_1_5_T : ENV_3_0_T;
            fBook = fine ? ENV_1_5_F : ENV_3_0_F;
            lav = fine ? 60 : 31;
            startBits = fine ? 7 : 6;
        }
        // Balance values are sent in steps of two: the pan offsets 24 and 12 sit in the middle of their range.
        int step = balance ? 2 : 1;
        for (int l = 0; l < c.numEnv; l++) {
            int res = c.freqRes[l];
            int bands = res == 1 ? nHigh : nLow;
            int[] e = c.env[l];
            if (!c.dfEnv[l]) {
                e[0] = step * b.read(startBits);
                for (int k = 1; k < bands; k++) {
                    e[k] = e[k - 1] + step * (HuffmanTable.decode(b, BOOKS[fBook]) - lav);
                }
            } else {
                int[] prev = l == 0 ? c.envPrev : c.env[l - 1];
                int prevRes = l == 0 ? c.resPrev : c.freqRes[l - 1];
                for (int k = 0; k < bands; k++) {
                    int delta = step * (HuffmanTable.decode(b, BOOKS[tBook]) - lav);
                    int from;
                    if (res == prevRes) {
                        from = prev[k];
                    } else if (res == 1) {
                        // High resolution from low: the low band that contains this high band's start.
                        int i = 0;
                        while (i + 1 < nLow && fLow[i + 1] <= fHigh[k]) {
                            i++;
                        }
                        from = prev[i];
                    } else {
                        // Low resolution from high: the high band starting where this low band starts.
                        int i = 0;
                        while (i + 1 < nHigh && fHigh[i] != fLow[k]) {
                            i++;
                        }
                        from = prev[i];
                    }
                    e[k] = from + delta;
                }
            }
        }
        if (balance || !coupled) {
            return;
        }
        // Coupled level values are dequantised together with the balance values of the other channel later.
    }

    private void readNoise(AacBits b, Channel c, boolean coupled, boolean balance) throws AudioFormatException {
        int tBook = balance ? NOISE_BAL_T : NOISE_T;
        int fBook = balance ? BAL_3_0_F : ENV_3_0_F;
        int lav = balance ? 12 : 31;
        int step = balance ? 2 : 1;
        for (int l = 0; l < c.numNoise; l++) {
            int[] q = c.noise[l];
            if (!c.dfNoise[l]) {
                q[0] = step * b.read(5);
                for (int k = 1; k < nQ; k++) {
                    q[k] = q[k - 1] + step * (HuffmanTable.decode(b, BOOKS[fBook]) - lav);
                }
            } else {
                int[] prev = l == 0 ? c.noisePrev : c.noise[l - 1];
                for (int k = 0; k < nQ; k++) {
                    q[k] = prev[k] + step * (HuffmanTable.decode(b, BOOKS[tBook]) - lav);
                }
            }
        }
    }

    private void readSinusoids(AacBits b, Channel c) {
        for (int i = 0; i < nHigh; i++) {
            c.addHarmonic[i] = b.readBit();
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Frequency band tables (4.6.18.3.2)

    private void computeFrequencyTables() throws AudioFormatException {
        int fs = outRate;
        int startMin;
        int stopMin;
        if (fs < 32000) {
            startMin = 3000;
            stopMin = 6000;
        } else if (fs < 64000) {
            startMin = 4000;
            stopMin = 8000;
        } else {
            startMin = 5000;
            stopMin = 10000;
        }
        startMin = (int) Math.round(startMin * 128.0 / fs);
        stopMin = (int) Math.round(stopMin * 128.0 / fs);
        int offsetRow = fs <= 16000 ? 0 : fs <= 22050 ? 1 : fs <= 24000 ? 2 : fs <= 32000 ? 3 : fs <= 64000 ? 4 : 5;
        k0 = startMin + START_OFFSETS[offsetRow][startFreq];
        int k2;
        if (stopFreq < 14) {
            int[] dk = new int[13];
            for (int p = 0; p < 13; p++) {
                dk[p] = (int) Math.round(stopMin * StrictMath.pow(64.0 / stopMin, (p + 1) / 13.0))
                        - (int) Math.round(stopMin * StrictMath.pow(64.0 / stopMin, p / 13.0));
            }
            Arrays.sort(dk);
            k2 = stopMin;
            for (int p = 0; p < stopFreq; p++) {
                k2 += dk[p];
            }
            k2 = Math.min(64, k2);
        } else if (stopFreq == 14) {
            k2 = Math.min(64, 2 * k0);
        } else {
            k2 = Math.min(64, 3 * k0);
        }
        int maxSpan = fs <= 32000 ? 48 : fs <= 44100 ? 35 : 32;
        if (k0 < 1 || k2 <= k0 || k2 - k0 > maxSpan) {
            throw new AudioFormatException("SBR frequency range out of bounds");
        }
        // Master table.
        if (freqScale == 0) {
            int dk = alterScale == 0 ? 1 : 2;
            int bands = alterScale == 0 ? 2 * ((k2 - k0) / (dk * 2))
                    : 2 * (int) Math.round((k2 - k0) / (dk * 2.0));
            if (bands <= 0) {
                throw new AudioFormatException("SBR master table is empty");
            }
            int[] vdk = new int[bands];
            Arrays.fill(vdk, dk);
            int diff = k2 - (k0 + bands * dk);
            int incr = diff < 0 ? 1 : -1;
            int k = diff < 0 ? 0 : bands - 1;
            while (diff != 0) {
                vdk[k] -= incr;
                k += incr;
                diff += incr;
            }
            fMaster[0] = k0;
            for (int i = 1; i <= bands; i++) {
                fMaster[i] = fMaster[i - 1] + vdk[i - 1];
            }
            nMaster = bands;
        } else {
            int bandsPerOctave = new int[] {0, 12, 10, 8}[freqScale];
            double warp = alterScale == 1 ? 1.3 : 1.0;
            boolean twoRegions = (double) k2 / k0 > 2.2449;
            int k1 = twoRegions ? 2 * k0 : k2;
            int bands0 = 2 * (int) Math.round(bandsPerOctave * StrictMath.log((double) k1 / k0) / (2 * StrictMath.log(2)));
            if (bands0 <= 0 || bands0 > 64) {
                throw new AudioFormatException("SBR master table is empty");
            }
            int[] vdk0 = new int[bands0];
            for (int k = 0; k < bands0; k++) {
                vdk0[k] = (int) Math.round(k0 * StrictMath.pow((double) k1 / k0, (k + 1) / (double) bands0))
                        - (int) Math.round(k0 * StrictMath.pow((double) k1 / k0, k / (double) bands0));
            }
            Arrays.sort(vdk0);
            fMaster[0] = k0;
            for (int k = 1; k <= bands0; k++) {
                fMaster[k] = fMaster[k - 1] + vdk0[k - 1];
                if (vdk0[k - 1] <= 0) {
                    throw new AudioFormatException("SBR master table band of zero width");
                }
            }
            nMaster = bands0;
            if (twoRegions) {
                int bands1 = 2 * (int) Math.round(bandsPerOctave * StrictMath.log((double) k2 / k1)
                        / (2 * StrictMath.log(2) * warp));
                if (bands1 <= 0 || bands0 + bands1 > 64) {
                    throw new AudioFormatException("SBR master table is empty");
                }
                int[] vdk1 = new int[bands1];
                for (int k = 0; k < bands1; k++) {
                    vdk1[k] = (int) Math.round(k1 * StrictMath.pow((double) k2 / k1, (k + 1) / (double) bands1))
                            - (int) Math.round(k1 * StrictMath.pow((double) k2 / k1, k / (double) bands1));
                }
                int maxVdk0 = vdk0[bands0 - 1];
                int minVdk1 = Arrays.stream(vdk1).min().orElse(0);
                if (minVdk1 < maxVdk0) {
                    Arrays.sort(vdk1);
                    int change = maxVdk0 - vdk1[0];
                    vdk1[0] += change;
                    vdk1[bands1 - 1] -= change;
                }
                Arrays.sort(vdk1);
                for (int k = 1; k <= bands1; k++) {
                    if (vdk1[k - 1] <= 0) {
                        throw new AudioFormatException("SBR master table band of zero width");
                    }
                    fMaster[bands0 + k] = fMaster[bands0 + k - 1] + vdk1[k - 1];
                }
                nMaster = bands0 + bands1;
            }
        }
        if (xoverBand >= nMaster) {
            throw new AudioFormatException("SBR crossover band past the master table");
        }
        nHigh = nMaster - xoverBand;
        for (int i = 0; i <= nHigh; i++) {
            fHigh[i] = fMaster[i + xoverBand];
        }
        nLow = nHigh / 2 + (nHigh & 1);
        fLow[0] = fHigh[0];
        for (int i = 1; i <= nLow; i++) {
            fLow[i] = fHigh[2 * i - (nHigh & 1)];
        }
        int newKx = fHigh[0];
        int newM = fHigh[nHigh] - newKx;
        if (newKx > 32 || newKx + newM > 64 || newM <= 0) {
            throw new AudioFormatException("SBR band limits out of range");
        }
        kx = newKx;
        m = newM;
        nQ = Math.max(1, (int) Math.round(noiseBands * StrictMath.log((double) k2 / kx) / StrictMath.log(2)));
        if (nQ > 5) {
            throw new AudioFormatException("SBR has more than five noise bands");
        }
        int ik = 0;
        fNoise[0] = fLow[0];
        for (int k = 1; k <= nQ; k++) {
            ik += (nLow - ik) / (nQ + 1 - k);
            fNoise[k] = fLow[ik];
        }
        computePatches();
    }

    /** The patches that copy low bands up (4.6.18.6.3). */
    private void computePatches() throws AudioFormatException {
        int msb = k0;
        int usb = kx;
        numPatches = 0;
        int goal = (int) Math.round(2.048e6 / outRate);
        int k;
        if (goal < kx + m) {
            k = 0;
            for (int i = 0; fMaster[i] < goal; i++) {
                k = i + 1;
            }
        } else {
            k = nMaster;
        }
        int sb;
        int rounds = 0;
        do {
            // The specification's loop ends for every header it allows; one that only passed the range checks
            // could keep finding an empty patch, so a bound turns that into a refusal instead of a hang.
            if (++rounds > 64) {
                throw new AudioFormatException("SBR patches do not reach the top band");
            }
            int j = k + 1;
            int odd;
            do {
                j--;
                sb = fMaster[j];
                odd = (sb - 2 + k0) % 2;
            } while (sb > k0 - 1 + msb - odd);
            int bands = Math.max(sb - usb, 0);
            if (numPatches >= 6) {
                throw new AudioFormatException("SBR needs more than five patches");
            }
            patchSubbands[numPatches] = bands;
            patchStart[numPatches] = k0 - odd - bands;
            if (bands > 0) {
                usb = sb;
                msb = sb;
                numPatches++;
            } else {
                msb = kx;
            }
            if (fMaster[k] - sb < 3) {
                k = nMaster;
            }
        } while (sb != kx + m);
        if (numPatches > 1 && patchSubbands[numPatches - 1] < 3) {
            numPatches--;
        }
        if (numPatches > 5 || numPatches == 0) {
            throw new AudioFormatException("SBR patch count out of range");
        }
    }

    /** The limiter bands (4.6.18.3.2.3), from the low table and the patch borders. */
    private void computeLimiterTable() {
        if (limiterBands == 0) {
            fLim[0] = fLow[0];
            fLim[1] = fLow[nLow];
            nL = 1;
            return;
        }
        double perOctave = new double[] {0, 1.2, 2.0, 3.0}[limiterBands];
        int[] borders = new int[6];
        borders[0] = kx;
        for (int i = 1; i <= numPatches; i++) {
            borders[i] = borders[i - 1] + patchSubbands[i - 1];
        }
        int[] table = new int[nLow + numPatches + 1];
        int n = 0;
        for (int i = 0; i <= nLow; i++) {
            table[n++] = fLow[i];
        }
        for (int i = 1; i < numPatches; i++) {
            table[n++] = borders[i];
        }
        Arrays.sort(table, 0, n);
        int count = n - 1;
        int k = 1;
        while (k <= count) {
            double octaves = StrictMath.log((double) table[k] / table[k - 1]) / StrictMath.log(2);
            if (octaves * perOctave < 0.49) {
                if (table[k] == table[k - 1]) {
                    remove(table, k, count + 1);
                    count--;
                    continue;
                }
                if (isBorder(borders, numPatches, table[k])) {
                    if (isBorder(borders, numPatches, table[k - 1])) {
                        k++;
                        continue;
                    }
                    remove(table, k - 1, count + 1);
                    count--;
                    continue;
                }
                remove(table, k, count + 1);
                count--;
                continue;
            }
            k++;
        }
        nL = count;
        System.arraycopy(table, 0, fLim, 0, count + 1);
    }

    private static boolean isBorder(int[] borders, int numPatches, int value) {
        for (int i = 0; i <= numPatches; i++) {
            if (borders[i] == value) {
                return true;
            }
        }
        return false;
    }

    private static void remove(int[] a, int at, int length) {
        System.arraycopy(a, at + 1, a, at, length - at - 1);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Processing

    /** A channel pair: both core channels in, both doubled-rate outputs out. */
    void processPair(float[] coreL, float[] coreR, float[] outL, float[] outR, int offset) {
        boolean data = frameData && started;
        process(channels[0], coreL, outL, offset, data, 0);
        process(channels[1], coreR, outR, offset, data, 1);
        endFrame();
    }

    /**
     * A single channel. With {@code outR} set the element is the mono core of a stream that may carry parametric
     * stereo: without PS data both outputs get the same signal.
     */
    void processMono(float[] core, float[] outL, float[] outR, int offset) {
        boolean data = frameData && started;
        Channel c = channels[0];
        if (outR != null && psUsed) {
            processPs(c, core, outL, outR, offset, data);
        } else {
            process(c, core, outL, offset, data, 0);
            if (outR != null) {
                System.arraycopy(outL, offset, outR, offset, 2048);
            }
        }
        endFrame();
    }

    private void endFrame() {
        kxPrev = kx;
        mPrev = started ? m : 0;
        frameData = false;
        psThisFrame = false;
    }

    private void process(Channel c, float[] core, float[] out, int offset, boolean data, int index) {
        analysis(c, core);
        if (data) {
            if (index == 1 && coupling) {
                // Dequantised together with channel 0.
                dequantizeCoupled(channels[0], c);
            } else if (index == 0 && coupling) {
                dequantizeCoupled(c, channels[1]);
            } else {
                dequantize(c);
            }
            highFrequency(c);
        }
        assemble(c, data);
        synthesis(c, synRe, synIm, out, offset);
        finishChannel(c, data);
    }

    private void processPs(Channel c, float[] core, float[] outL, float[] outR, int offset, boolean data) {
        analysis(c, core);
        if (data) {
            dequantize(c);
            highFrequency(c);
        }
        assemble(c, data);
        // PS works on the assembled QMF bands: left and right come out of one signal. A frame without PS data keeps
        // the last parameters.
        if (!psThisFrame) {
            ps.noData();
        }
        double[][] rRe = psRightRe;
        double[][] rIm = psRightIm;
        for (int l = 32; l < 38; l++) {
            for (int k = 0; k < 3; k++) {
                boolean low = k < kx;
                synRe[l][k] = low ? c.lowRe[l + T_HFADJ][k] : 0;
                synIm[l][k] = low ? c.lowIm[l + T_HFADJ][k] : 0;
            }
        }
        ps.apply(synRe, synIm, rRe, rIm, started ? kx + m : 32);
        synthesis(c, synRe, synIm, outL, offset);
        synthesis(psSynthesis, rRe, rIm, outR, offset);
        finishChannel(c, data);
    }

    private final double[][] psRightRe = new double[32][64];
    private final double[][] psRightIm = new double[32][64];
    private final Channel psSynthesis = new Channel();

    /** Moves the look-back slots to the front for the next frame, and remembers what the next frame refers to. */
    private void finishChannel(Channel c, boolean data) {
        for (int i = 0; i < T_HFGEN; i++) {
            System.arraycopy(c.lowRe[32 + i], 0, c.lowRe[i], 0, 32);
            System.arraycopy(c.lowIm[32 + i], 0, c.lowIm[i], 0, 32);
            System.arraycopy(c.yRe[32 + i], 0, c.yRe[i], 0, 64);
            System.arraycopy(c.yIm[32 + i], 0, c.yIm[i], 0, 64);
        }
        for (int i = T_HFGEN; i < BUFFER_SLOTS; i++) {
            Arrays.fill(c.yRe[i], 0);
            Arrays.fill(c.yIm[i], 0);
        }
        if (!data) {
            return;
        }
        int last = c.numEnv - 1;
        int bands = c.freqRes[last] == 1 ? nHigh : nLow;
        System.arraycopy(c.env[last], 0, c.envPrev, 0, bands);
        c.resPrev = c.freqRes[last];
        System.arraycopy(c.noise[c.numNoise - 1], 0, c.noisePrev, 0, nQ);
        System.arraycopy(c.invfMode, 0, c.invfModePrev, 0, 5);
        System.arraycopy(c.addHarmonic, 0, c.addHarmonicPrev, 0, 64);
        c.addHarmonicFlagPrev = c.addHarmonicFlag;
        c.lAPrev = c.lA;
        c.numEnvPrev = c.numEnv;
        c.reset = false;
    }

    // ----- QMF analysis

    /**
     * The 32-band complex analysis of 1024 core samples (4.6.18.4.1): per slot, 32 new samples enter the 320-sample
     * buffer reversed, z[n] = x[n] c[2n], u[n] = sum over j of z[n + 64 j], and
     * W(k) = 2 sum u[n] exp(i pi/64 (k + 0.5)(2n - 0.5)) for k &lt; 32.
     *
     * <p>That sum is 2 exp(-i pi (k + 0.5)/128) G(k) with G(k) = sum u[n] exp(i 2 pi (k + 0.5) n / 64), an
     * odd-frequency DFT of 64 real values, which a 32-point complex FFT gives (half the work of the 64-point one the
     * sum suggests): pack z[m] = (u[2m] + i u[2m + 1]) exp(i pi m/32) and take Z = FFT32(z). Because u is real,
     * E(31 - k) = conj E(k) for the even samples' transform E and likewise for the odd samples' O, so
     * E(k) = (Z(k) + conj Z(31 - k))/2, O(k) = (Z(k) - conj Z(31 - k))/2i, and G(k) = E(k) + exp(i pi (k + 0.5)/32) O(k).
     * The twiddles are folded into A (for E) and B (for O) below; the factor 2 cancels the halves.
     */
    private void analysis(Channel c, float[] core) {
        double[] x = c.analysis;
        double[] w = ANALYSIS_WINDOW;
        double[] u = analysisU;
        double[] fr = fftRe;
        double[] fi = fftIm;
        for (int l = 0; l < 32; l++) {
            // x is a ring of 320 samples, newest first from analysisAt on, stored twice over (x[k + 320] == x[k]) so
            // the window reads it without wrapping; moving the start back by 32 drops the oldest 32 samples.
            int at = c.analysisAt = (c.analysisAt + 320 - 32) % 320;
            for (int n = 0; n < 32; n++) {
                double v = core[l * 32 + 31 - n] * SCALE_IN;
                x[at + n] = v;
                x[at + 320 + n] = v;
            }
            for (int n = 0; n < 64; n++) {
                u[n] = x[at + n] * w[n] + x[at + n + 64] * w[n + 64] + x[at + n + 128] * w[n + 128]
                        + x[at + n + 192] * w[n + 192] + x[at + n + 256] * w[n + 256];
            }
            for (int m = 0; m < 32; m++) {
                double a = u[2 * m];
                double b = u[2 * m + 1];
                double tc = PACK32_COS[m];
                double ts = PACK32_SIN[m];
                fr[m] = a * tc - b * ts;
                fi[m] = a * ts + b * tc;
            }
            Fft.FFT32.inverse(fr, fi);
            double[] re = c.lowRe[T_HFGEN + l];
            double[] im = c.lowIm[T_HFGEN + l];
            for (int k = 0; k < 32; k++) {
                double zr = fr[k];
                double zi = fi[k];
                double yr = fr[31 - k];
                double yi = fi[31 - k];
                double er = zr + yr;
                double ei = zi - yi;
                double or = zi + yi;
                double oi = yr - zr;
                re[k] = er * A_COS[k] - ei * A_SIN[k] + or * B_COS[k] - oi * B_SIN[k];
                im[k] = er * A_SIN[k] + ei * A_COS[k] + or * B_SIN[k] + oi * B_COS[k];
            }
        }
    }

    /** The analysis takes every other tap of the 640-tap prototype, c[2n]; kept contiguous here. */
    private static final double[] ANALYSIS_WINDOW = new double[320];

    static {
        for (int n = 0; n < 320; n++) {
            ANALYSIS_WINDOW[n] = AacSbrData.QMF_WINDOW[2 * n];
        }
    }

    /** exp(i pi m/32): packs pairs of real samples for the analysis FFT. */
    private static final double[] PACK32_COS = new double[32];
    private static final double[] PACK32_SIN = new double[32];
    /** exp(-i pi (k + 0.5)/128), the analysis output rotation applied to the even samples' transform. */
    private static final double[] A_COS = new double[32];
    private static final double[] A_SIN = new double[32];
    /** exp(i 3 pi (k + 0.5)/128): the same rotation times exp(i pi (k + 0.5)/32), for the odd samples' transform. */
    private static final double[] B_COS = new double[32];
    private static final double[] B_SIN = new double[32];
    /** exp(i pi m/64): the DCT-IV input rotation in the synthesis. */
    private static final double[] DCT_PRE_COS = new double[32];
    private static final double[] DCT_PRE_SIN = new double[32];
    /** exp(i pi (p + 0.25)/64) / 64: the DCT-IV output rotation, with the synthesis' 1/64 folded in. */
    private static final double[] DCT_POST_COS = new double[32];
    private static final double[] DCT_POST_SIN = new double[32];

    static {
        for (int m = 0; m < 32; m++) {
            PACK32_COS[m] = StrictMath.cos(Math.PI * m / 32);
            PACK32_SIN[m] = StrictMath.sin(Math.PI * m / 32);
            double a = -Math.PI * (m + 0.5) / 128;
            A_COS[m] = StrictMath.cos(a);
            A_SIN[m] = StrictMath.sin(a);
            double b = 3 * Math.PI * (m + 0.5) / 128;
            B_COS[m] = StrictMath.cos(b);
            B_SIN[m] = StrictMath.sin(b);
            DCT_PRE_COS[m] = StrictMath.cos(Math.PI * m / 64);
            DCT_PRE_SIN[m] = StrictMath.sin(Math.PI * m / 64);
            double p = Math.PI * (m + 0.25) / 64;
            DCT_POST_COS[m] = StrictMath.cos(p) / 64;
            DCT_POST_SIN[m] = StrictMath.sin(p) / 64;
        }
    }

    // ----- QMF synthesis

    /**
     * The 64-band synthesis of 32 slots into 2048 samples (4.6.18.4.2). Per slot,
     * v[n] = 1/64 sum Re(X(k) exp(i pi/128 (k + 0.5)(2n - 255))) for n &lt; 128; since
     * (k + 0.5)(2n - 255) pi/128 = (k + 0.5)(n + 0.5) pi/64 - (2k + 1) pi, that is -(C(n) - S(n))/64 with
     * C(n) = sum Re X(k) cos(pi/64 (k + 0.5)(n + 0.5)), a DCT-IV of the real parts, and
     * S(n) = sum Im X(k) sin(pi/64 (k + 0.5)(n + 0.5)) = (-1)^n D(n), D being the DCT-IV of the imaginary parts in
     * reverse order. For n = 127 - m the kernels give C(127 - m) = -C(m) and S(127 - m) = S(m), so the upper half is
     * (C(m) + S(m))/64. Each 64-point DCT-IV is a 32-point complex FFT: z[m] = (x[2m] - i x[63 - 2m]) exp(i pi m/64),
     * R(p) = FFT32(z)(p) exp(i pi (p + 0.25)/64), DCT(2p) = Re R(p), DCT(63 - 2p) = Im R(p). Two of them cost under
     * half the 128-point FFT of the zero-padded spectrum used before, and give the same values to rounding.
     *
     * <p>The window then takes v[256 i + j] and v[256 i + 192 + j] times c[128 i + j] and c[128 i + 64 + j] (the
     * g[] of the specification) and sums them into 64 output samples.
     */
    private void synthesis(Channel c, double[][] xr, double[][] xi, float[] out, int offset) {
        double[] v = c.synthesis;
        double[] w = AacSbrData.QMF_WINDOW;
        double[] ar = fftRe;
        double[] ai = fftIm;
        double[] br = fftRe2;
        double[] bi = fftIm2;
        for (int l = 0; l < 32; l++) {
            double[] re = xr[l];
            double[] im = xi[l];
            for (int m = 0; m < 32; m++) {
                double tc = DCT_PRE_COS[m];
                double ts = DCT_PRE_SIN[m];
                // The real parts, x[j] = Re X(j).
                double a = re[2 * m];
                double b = -re[63 - 2 * m];
                ar[m] = a * tc - b * ts;
                ai[m] = a * ts + b * tc;
                // The imaginary parts reversed, x[j] = Im X(63 - j).
                a = im[63 - 2 * m];
                b = -im[2 * m];
                br[m] = a * tc - b * ts;
                bi[m] = a * ts + b * tc;
            }
            Fft.FFT32.inverse(ar, ai);
            Fft.FFT32.inverse(br, bi);
            // v is a ring of 1280 values: the newest 128 start at synthesisAt, older ones follow. It is stored twice
            // over (v[k + 1280] == v[k]), so the window reads the 1280 values from synthesisAt on without wrapping.
            int at = c.synthesisAt = (c.synthesisAt + 1280 - 128) % 1280;
            for (int p = 0; p < 32; p++) {
                double pc = DCT_POST_COS[p];
                double ps = DCT_POST_SIN[p];
                // C(2p), C(63 - 2p) and D(2p), D(63 - 2p), already divided by 64.
                double cEven = ar[p] * pc - ai[p] * ps;
                double cOdd = ar[p] * ps + ai[p] * pc;
                double dEven = br[p] * pc - bi[p] * ps;
                double dOdd = br[p] * ps + bi[p] * pc;
                // n = 2p is even, so S = D; n = 63 - 2p is odd, so S = -D.
                double v0 = dEven - cEven;
                double v1 = cEven + dEven;
                double v2 = -(cOdd + dOdd);
                double v3 = cOdd - dOdd;
                v[at + 2 * p] = v0;
                v[at + 1280 + 2 * p] = v0;
                v[at + 127 - 2 * p] = v1;
                v[at + 1280 + 127 - 2 * p] = v1;
                v[at + 63 - 2 * p] = v2;
                v[at + 1280 + 63 - 2 * p] = v2;
                v[at + 64 + 2 * p] = v3;
                v[at + 1280 + 64 + 2 * p] = v3;
            }
            // The window, one tap pair at a time over all 64 outputs: each output still sums its terms in the same
            // order as a per-sample loop would (0, then i = 0 to 4), so the result is the same, but the inner loops
            // have no dependency from one sample to the next and run several samples at once.
            double[] acc = windowScratch;
            for (int j = 0; j < 64; j++) {
                acc[j] = 0.0 + (v[at + j] * w[j] + v[at + 192 + j] * w[64 + j]);
            }
            for (int i = 1; i < 5; i++) {
                int vo = at + 256 * i;
                int wo = 128 * i;
                for (int j = 0; j < 64; j++) {
                    acc[j] += v[vo + j] * w[wo + j] + v[vo + 192 + j] * w[wo + 64 + j];
                }
            }
            int base = offset + l * 64;
            for (int j = 0; j < 64; j++) {
                out[base + j] = (float) (acc[j] * SCALE_OUT);
            }
        }
    }

    // ----- Dequantisation (4.6.18.3.5)

    private void dequantize(Channel c) {
        double a = c.ampRes == 0 ? 2 : 1;
        for (int l = 0; l < c.numEnv; l++) {
            int bands = c.freqRes[l] == 1 ? nHigh : nLow;
            for (int k = 0; k < bands; k++) {
                c.eOrig[l][k] = 64 * StrictMath.pow(2, c.env[l][k] / a);
            }
        }
        for (int l = 0; l < c.numNoise; l++) {
            for (int k = 0; k < nQ; k++) {
                c.qOrig[l][k] = StrictMath.pow(2, 6 - c.noise[l][k]);
            }
        }
    }

    /** Coupled stereo: channel 0 carries the level, channel 1 the balance (pan offsets 24 and 12). */
    private void dequantizeCoupled(Channel c0, Channel c1) {
        if (c1.eOrig == null) {
            return;
        }
        double a = c0.ampRes == 0 ? 2 : 1;
        double pan = c0.ampRes == 0 ? 24 : 12;
        for (int l = 0; l < c0.numEnv; l++) {
            int bands = c0.freqRes[l] == 1 ? nHigh : nLow;
            for (int k = 0; k < bands; k++) {
                double level = 64 * StrictMath.pow(2, c0.env[l][k] / a + 1);
                double bal = c1.env[l][k];
                c0.eOrig[l][k] = level / (1 + StrictMath.pow(2, (pan - bal) / a));
                c1.eOrig[l][k] = level / (1 + StrictMath.pow(2, (bal - pan) / a));
            }
        }
        for (int l = 0; l < c0.numNoise; l++) {
            for (int k = 0; k < nQ; k++) {
                double level = StrictMath.pow(2, 6 - c0.noise[l][k] + 1);
                double bal = c1.noise[l][k];
                c0.qOrig[l][k] = level / (1 + StrictMath.pow(2, 12 - bal));
                c1.qOrig[l][k] = level / (1 + StrictMath.pow(2, bal - 12));
            }
        }
    }

    // ----- HF generation and adjustment

    private void highFrequency(Channel c) {
        lpc(c);
        double[] bw = chirp(c);
        generate(c, bw);
        adjust(c);
    }

    /** The second-order linear prediction of each low band over the 38 slots before and in this frame. */
    private void lpc(Channel c) {
        for (int k = 0; k < k0; k++) {
            double r01r = 0;
            double r01i = 0;
            double r02r = 0;
            double r02i = 0;
            double r11 = 0;
            double r12r = 0;
            double r12i = 0;
            double r22 = 0;
            for (int n = 0; n < 38; n++) {
                // phi(i, j) = sum X(n + 2 - i) conj(X(n + 2 - j))
                double x0r = c.lowRe[n + 2][k];
                double x0i = c.lowIm[n + 2][k];
                double x1r = c.lowRe[n + 1][k];
                double x1i = c.lowIm[n + 1][k];
                double x2r = c.lowRe[n][k];
                double x2i = c.lowIm[n][k];
                r01r += x0r * x1r + x0i * x1i;
                r01i += x0i * x1r - x0r * x1i;
                r02r += x0r * x2r + x0i * x2i;
                r02i += x0i * x2r - x0r * x2i;
                r11 += x1r * x1r + x1i * x1i;
                r12r += x1r * x2r + x1i * x2i;
                r12i += x1i * x2r - x1r * x2i;
                r22 += x2r * x2r + x2i * x2i;
            }
            double d = r22 * r11 - (r12r * r12r + r12i * r12i) / (1 + 1e-6);
            double a1r = 0;
            double a1i = 0;
            if (d != 0) {
                // alpha1 = (phi01 phi12 - phi02 phi11) / d
                a1r = ((r01r * r12r - r01i * r12i) - r02r * r11) / d;
                a1i = ((r01i * r12r + r01r * r12i) - r02i * r11) / d;
            }
            double a0r = 0;
            double a0i = 0;
            if (r11 != 0) {
                // alpha0 = -(phi01 + alpha1 conj(phi12)) / phi11
                a0r = -(r01r + a1r * r12r + a1i * r12i) / r11;
                a0i = -(r01i + a1i * r12r - a1r * r12i) / r11;
            }
            if (a0r * a0r + a0i * a0i >= 16 || a1r * a1r + a1i * a1i >= 16) {
                a0r = a0i = a1r = a1i = 0;
            }
            alpha0Re[k] = a0r;
            alpha0Im[k] = a0i;
            alpha1Re[k] = a1r;
            alpha1Im[k] = a1i;
        }
    }

    /** The chirp factors per noise band from the inverse filtering levels (4.6.18.6.2). */
    private double[] chirp(Channel c) {
        double[] bw = bwScratch;
        for (int i = 0; i < nQ; i++) {
            int mode = c.invfMode[i];
            int prev = c.reset ? 0 : c.invfModePrev[i];
            double target = switch (mode) {
                case 0 -> prev == 1 ? 0.6 : 0.0;
                case 1 -> prev == 0 ? 0.6 : 0.75;
                case 2 -> 0.9;
                default -> 0.98;
            };
            double old = c.reset ? 0 : c.bwPrev[i];
            double v = target < old ? 0.75 * target + 0.25 * old : 0.90625 * target + 0.09375 * old;
            if (v < 0.015625) {
                v = 0;
            }
            bw[i] = v;
            c.bwPrev[i] = v;
        }
        return bw;
    }

    /** Copies low bands up through the patches, whitened by the chirped inverse filter (4.6.18.6.3). */
    private void generate(Channel c, double[] bw) {
        int from = RATE * c.tE[0];
        int to = RATE * c.tE[c.numEnv];
        for (int l = 0; l < BUFFER_SLOTS; l++) {
            Arrays.fill(xHighRe[l], 0);
            Arrays.fill(xHighIm[l], 0);
        }
        int k = kx;
        for (int p = 0; p < numPatches; p++) {
            for (int x = 0; x < patchSubbands[p]; x++, k++) {
                int src = patchStart[p] + x;
                int g = 0;
                while (g + 1 < nQ && fNoise[g + 1] <= k) {
                    g++;
                }
                double b = bw[g];
                double a0r = b * alpha0Re[src];
                double a0i = b * alpha0Im[src];
                double a1r = b * b * alpha1Re[src];
                double a1i = b * b * alpha1Im[src];
                for (int l = from; l < to; l++) {
                    int i = l + T_HFADJ;
                    double x0r = c.lowRe[i][src];
                    double x0i = c.lowIm[i][src];
                    double x1r = c.lowRe[i - 1][src];
                    double x1i = c.lowIm[i - 1][src];
                    double x2r = c.lowRe[i - 2][src];
                    double x2i = c.lowIm[i - 2][src];
                    xHighRe[i][k] = x0r + a0r * x1r - a0i * x1i + a1r * x2r - a1i * x2i;
                    xHighIm[i][k] = x0i + a0r * x1i + a0i * x1r + a1r * x2i + a1i * x2r;
                }
            }
        }
    }

    /** HF adjustment (4.6.18.7): envelope estimation, gains, limiter, boost, smoothing, noise and sinusoids. */
    private void adjust(Channel c) {
        int numEnv = c.numEnv;
        int lA = c.lA;
        // The previous frame's transient may fall exactly at its end, which makes it this frame's first envelope.
        int lAFromPrevious = c.lAPrev == c.numEnvPrev ? 0 : -1;
        double[][] gain = gainScratch;
        double[][] noiseLevel = noiseScratch;
        double[][] sine = sineScratch;
        for (int l = 0; l < numEnv; l++) {
            int res = c.freqRes[l];
            int[] f = res == 1 ? fHigh : fLow;
            int bands = res == 1 ? nHigh : nLow;
            int from = RATE * c.tE[l];
            int to = RATE * c.tE[l + 1];
            // Noise envelope covering this envelope.
            int qEnv = c.numNoise > 1 && c.tE[l] >= c.tQ[1] ? 1 : 0;
            boolean delta = l != lA && l != lAFromPrevious;
            // Estimated energies of the generated band.
            double[] eCurr = eCurrScratch;
            Arrays.fill(eCurr, 0);
            if (interpolFreq == 1) {
                for (int mm = 0; mm < m; mm++) {
                    double s = 0;
                    for (int i = from; i < to; i++) {
                        double re = xHighRe[i + T_HFADJ][mm + kx];
                        double im = xHighIm[i + T_HFADJ][mm + kx];
                        s += re * re + im * im;
                    }
                    eCurr[mm] = s / (to - from);
                }
            } else {
                for (int p = 0; p < bands; p++) {
                    double s = 0;
                    for (int kk = f[p]; kk < f[p + 1]; kk++) {
                        for (int i = from; i < to; i++) {
                            double re = xHighRe[i + T_HFADJ][kk];
                            double im = xHighIm[i + T_HFADJ][kk];
                            s += re * re + im * im;
                        }
                    }
                    s /= (double) (to - from) * (f[p + 1] - f[p]);
                    for (int kk = f[p]; kk < f[p + 1]; kk++) {
                        eCurr[kk - kx] = s;
                    }
                }
            }
            // Mapped original energies, noise floors and sinusoids.
            double[] eOrig = eOrigScratch;
            double[] qOrig = qOrigScratch;
            boolean[] sIndex = sIndexScratch;
            boolean[] sMapped = sMappedScratch;
            Arrays.fill(eOrig, 0);
            Arrays.fill(qOrig, 0);
            Arrays.fill(sIndex, false);
            Arrays.fill(sMapped, false);
            for (int p = 0; p < bands; p++) {
                for (int kk = f[p]; kk < f[p + 1]; kk++) {
                    eOrig[kk - kx] = c.eOrig[l][p];
                }
            }
            for (int q = 0; q < nQ; q++) {
                for (int kk = fNoise[q]; kk < fNoise[q + 1]; kk++) {
                    qOrig[kk - kx] = c.qOrig[qEnv][q];
                }
            }
            for (int p = 0; p < nHigh; p++) {
                // A sinusoid starts at the transient's envelope (all envelopes when there is none), unless it was
                // already playing at the end of the previous frame.
                if (c.addHarmonic[p] && (l >= lA || c.addHarmonicPrev[p] && !c.reset)) {
                    int mid = (fHigh[p] + fHigh[p + 1]) >> 1;
                    sIndex[mid - kx] = true;
                }
            }
            for (int p = 0; p < bands; p++) {
                boolean any = false;
                for (int kk = f[p]; kk < f[p + 1]; kk++) {
                    any |= sIndex[kk - kx];
                }
                for (int kk = f[p]; kk < f[p + 1]; kk++) {
                    sMapped[kk - kx] = any;
                }
            }
            double[] g = gain[l];
            double[] qm = noiseLevel[l];
            double[] sm = sine[l];
            for (int mm = 0; mm < m; mm++) {
                double eo = eOrig[mm];
                double qo = qOrig[mm];
                double e = eCurr[mm];
                qm[mm] = Math.sqrt(eo * qo / (1 + qo));
                sm[mm] = sIndex[mm] ? Math.sqrt(eo / (1 + qo)) : 0;
                if (!sMapped[mm]) {
                    g[mm] = Math.sqrt(eo / ((1 + e) * (1 + (delta ? qo : 0))));
                } else {
                    g[mm] = Math.sqrt(eo * qo / ((1 + e) * (1 + qo)));
                }
            }
            // Limiter and boost per limiter band.
            double limGain = LIMITER_GAINS[limiterGains];
            for (int b = 0; b < nL; b++) {
                int lo = fLim[b] - kx;
                int hi = fLim[b + 1] - kx;
                double sumO = 0;
                double sumC = 0;
                for (int mm = lo; mm < hi; mm++) {
                    sumO += eOrig[mm];
                    sumC += eCurr[mm];
                }
                double gMax = limGain * Math.sqrt((1e-12 + sumO) / (1e-12 + sumC));
                gMax = Math.min(gMax, 1e5);
                for (int mm = lo; mm < hi; mm++) {
                    if (g[mm] > gMax) {
                        qm[mm] = qm[mm] * gMax / g[mm];
                        g[mm] = gMax;
                    }
                }
                double sumB = 0;
                for (int mm = lo; mm < hi; mm++) {
                    sumB += eCurr[mm] * g[mm] * g[mm] + sm[mm] * sm[mm]
                            + (delta && sm[mm] == 0 ? qm[mm] * qm[mm] : 0);
                }
                double boost = Math.sqrt((1e-12 + sumO) / (1e-12 + sumB));
                boost = Math.min(boost, 1.584893192);
                for (int mm = lo; mm < hi; mm++) {
                    g[mm] *= boost;
                    qm[mm] *= boost;
                    sm[mm] *= boost;
                }
            }
        }
        assembleHigh(c, gain, noiseLevel, sine, lA, lAFromPrevious);
    }

    /** Applies the gains slot by slot with smoothing, and adds noise and sinusoids (4.6.18.7.6). */
    private void assembleHigh(Channel c, double[][] gain, double[][] noiseLevel, double[][] sine, int lA,
                              int lAFromPrevious) {
        boolean smooth = smoothingMode == 0;
        double[][] gh = c.gHistory;
        double[][] qh = c.qHistory;
        if (c.reset) {
            for (int h = 0; h < 4; h++) {
                System.arraycopy(gain[0], 0, gh[h], 0, 64);
                System.arraycopy(noiseLevel[0], 0, qh[h], 0, 64);
            }
        }
        int noiseIndex = c.indexNoise;
        int sineIndex = c.indexSine;
        double[] noise = AacSbrData.NOISE;
        double[] gFilt = gFiltScratch;
        double[] qFilt = qFiltScratch;
        for (int l = 0; l < c.numEnv; l++) {
            boolean isTransient = l == lA || l == lAFromPrevious;
            double[] g = gain[l];
            double[] q = noiseLevel[l];
            double[] s = sine[l];
            for (int i = RATE * c.tE[l]; i < RATE * c.tE[l + 1]; i++) {
                // The history holds the last four slots' gains; this slot's go in after it is used.
                for (int mm = 0; mm < m; mm++) {
                    if (smooth && !isTransient) {
                        double gs = SMOOTH[4] * g[mm];
                        double qs = SMOOTH[4] * q[mm];
                        for (int h = 0; h < 4; h++) {
                            gs += SMOOTH[h] * gh[h][mm];
                            qs += SMOOTH[h] * qh[h][mm];
                        }
                        gFilt[mm] = gs;
                        qFilt[mm] = qs;
                    } else {
                        gFilt[mm] = g[mm];
                        qFilt[mm] = q[mm];
                    }
                }
                double[] tmp = gh[0];
                System.arraycopy(gh, 1, gh, 0, 3);
                gh[3] = tmp;
                System.arraycopy(g, 0, gh[3], 0, 64);
                tmp = qh[0];
                System.arraycopy(qh, 1, qh, 0, 3);
                qh[3] = tmp;
                System.arraycopy(q, 0, qh[3], 0, 64);
                double[] yr = c.yRe[i + T_HFADJ];
                double[] yi = c.yIm[i + T_HFADJ];
                double[] xr = xHighRe[i + T_HFADJ];
                double[] xi = xHighIm[i + T_HFADJ];
                for (int mm = 0; mm < m; mm++) {
                    int k = mm + kx;
                    double re = gFilt[mm] * xr[k];
                    double im = gFilt[mm] * xi[k];
                    noiseIndex = (noiseIndex + 1) & 511;
                    if (s[mm] != 0) {
                        // phi_re = {1, 0, -1, 0}, phi_im = {0, 1, 0, -1}; odd bands flip the imaginary part.
                        double sv = s[mm];
                        switch (sineIndex) {
                            case 0 -> re += sv;
                            case 1 -> im += (k & 1) == 0 ? sv : -sv;
                            case 2 -> re -= sv;
                            default -> im += (k & 1) == 0 ? -sv : sv;
                        }
                    } else if (!isTransient) {
                        re += qFilt[mm] * noise[2 * noiseIndex];
                        im += qFilt[mm] * noise[2 * noiseIndex + 1];
                    }
                    yr[k] = re;
                    yi[k] = im;
                }
                sineIndex = (sineIndex + 1) & 3;
            }
        }
        c.indexNoise = noiseIndex;
        c.indexSine = sineIndex;
    }

    /**
     * The 32 slots the synthesis gets (4.6.18.8): the low bands from the analysis and the adjusted high bands, both
     * t_HFAdj slots back; slots before this frame's first border still belong to the previous frame's band limits.
     */
    private void assemble(Channel c, boolean data) {
        int start = data ? RATE * c.tE[0] : 0;
        for (int l = 0; l < 32; l++) {
            double[] re = synRe[l];
            double[] im = synIm[l];
            int split = l < start ? kxPrev : kx;
            int high = l < start ? kxPrev + mPrev : (data ? kx + m : (started ? kx + m : kx));
            if (!started && !data) {
                split = 32;
                high = 32;
            }
            double[] lr = c.lowRe[l + T_HFADJ];
            double[] li = c.lowIm[l + T_HFADJ];
            for (int k = 0; k < split && k < 32; k++) {
                re[k] = lr[k];
                im[k] = li[k];
            }
            double[] yr = c.yRe[l + T_HFADJ];
            double[] yi = c.yIm[l + T_HFADJ];
            for (int k = split; k < 64; k++) {
                if (k < high) {
                    re[k] = yr[k];
                    im[k] = yi[k];
                } else {
                    re[k] = 0;
                    im[k] = 0;
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------------------

    /** Two-level lookup tables for the SBR and PS Huffman books, in the {@code code << 5 | length} form. */
    static final class HuffmanTable {
        private static final int FIRST = 8;

        private HuffmanTable() {
        }

        static int[] build(int[] codes) {
            int firstSize = 1 << FIRST;
            int[] extra = new int[firstSize];
            for (int code : codes) {
                int length = code & 31;
                if (length > FIRST) {
                    int prefix = (code >>> 5) >>> (length - FIRST);
                    extra[prefix] = Math.max(extra[prefix], length - FIRST);
                }
            }
            int size = firstSize;
            int[] offsets = new int[firstSize];
            for (int p = 0; p < firstSize; p++) {
                if (extra[p] > 0) {
                    offsets[p] = size;
                    size += 1 << extra[p];
                }
            }
            int[] table = new int[size];
            Arrays.fill(table, -1);
            for (int p = 0; p < firstSize; p++) {
                if (extra[p] > 0) {
                    table[p] = 0x80000000 | offsets[p] << 5 | extra[p];
                }
            }
            for (int symbol = 0; symbol < codes.length; symbol++) {
                int length = codes[symbol] & 31;
                int code = codes[symbol] >>> 5;
                if (length <= FIRST) {
                    int shift = FIRST - length;
                    for (int fill = 0; fill < 1 << shift; fill++) {
                        table[(code << shift) + fill] = symbol << 5 | length;
                    }
                } else {
                    int prefix = code >>> (length - FIRST);
                    int bits = extra[prefix];
                    int rest = code & ((1 << (length - FIRST)) - 1);
                    int shift = bits - (length - FIRST);
                    for (int fill = 0; fill < 1 << shift; fill++) {
                        table[offsets[prefix] + (rest << shift) + fill] = symbol << 5 | (length - FIRST);
                    }
                }
            }
            return table;
        }

        static int decode(AacBits b, int[] table) {
            int entry = table[b.peek(FIRST)];
            if (entry >= 0) {
                b.skip(entry & 31);
                return entry >>> 5;
            }
            b.skip(FIRST);
            int second = table[((entry & 0x7FFFFFFF) >>> 5) + b.peek(entry & 31)];
            b.skip(second & 31);
            return second >>> 5;
        }
    }
}

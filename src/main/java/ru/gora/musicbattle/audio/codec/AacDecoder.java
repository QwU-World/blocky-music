package ru.gora.musicbattle.audio.codec;

import java.util.Arrays;

/**
 * Decodes AAC access units (raw_data_block, ISO/IEC 14496-3, 4.4.2.1) into planar float samples: AAC LC with every
 * tool the profile has (long, start, stop and eight-short windows in sine and KBD shapes, scale factors, the eleven
 * spectral codebooks, pulse data, M/S and intensity stereo, perceptual noise substitution and TNS), and SBR and
 * parametric stereo on top of it for HE-AAC and HE-AAC v2.
 *
 * <p>Written from the specification. The Huffman tables and the scale factor band tables come from Apache-2.0
 * encoders and decoders, as {@link AacHuffmanData} and {@link AacTables} say. Main, SSR and LTP profiles, the
 * error-resilient and low-delay object types, 960-sample frames and coupling channel elements are refused with
 * {@link UnsupportedFormatException}: they are not what music files use, and decoding them as LC would
 * play wrong audio.
 *
 * <p>Channels come out in the order ffmpeg's decoder gives them (front left, front right, centre, LFE, back left,
 * back right, then the rest), which for the common layouts is also the WAVE order {@link ChannelDownmix} expects.
 * A mono stream that may carry parametric stereo comes out as two channels, as it does in ffmpeg, because the
 * channel count has to be known before the frame that would tell.
 */
final class AacDecoder {
    private static final int ID_SCE = 0;
    private static final int ID_CPE = 1;
    private static final int ID_CCE = 2;
    private static final int ID_LFE = 3;
    private static final int ID_DSE = 4;
    private static final int ID_PCE = 5;
    private static final int ID_FIL = 6;
    private static final int ID_END = 7;

    static final int ZERO_HCB = 0;
    static final int ESC_HCB = 11;
    static final int NOISE_HCB = 13;
    static final int INTENSITY_HCB2 = 14;
    static final int INTENSITY_HCB = 15;

    private static final int EXT_FILL = 0;
    private static final int EXT_SBR_DATA = 13;
    private static final int EXT_SBR_DATA_CRC = 14;

    /** |q|^(4/3) for the quantised values a codebook escape can reach, plus the largest pulse amplitude. */
    private static final float[] POW43 = new float[8192 + 16];

    /**
     * The gain of a spectral band by its scale factor, 2^((sf - 100) / 4) and 2^-15 more to bring 16-bit full scale
     * to 1. readScaleFactors refuses a scale factor outside 0..255, so the table covers every band that reaches it; it
     * holds exactly what the formula gives, computed once instead of once per band and frame.
     */
    private static final float[] SF_GAIN = new float[256];

    static {
        for (int i = 0; i < POW43.length; i++) {
            POW43[i] = (float) StrictMath.pow(i, 4.0 / 3.0);
        }
        for (int sf = 0; sf < SF_GAIN.length; sf++) {
            SF_GAIN[sf] = (float) StrictMath.pow(2.0, 0.25 * (sf - 100) - 15);
        }
    }

    private final AacConfig config;
    private final int rateIndex;
    private final int[] swbLong;
    private final int[] swbShort;
    private final int coreChannels;
    private final int outputChannels;
    /** Where each element's channels go: by element type, the n-th element of that type in a frame. */
    private final int[][] sceMap;
    private final int[][] cpeMap;
    private final int[][] lfeMap;

    private final AacBits bits = new AacBits();
    private final AacFilterBank filterBank = new AacFilterBank();
    private final Ics left = new Ics();
    private final Ics right = new Ics();
    private final ChannelState[] states;
    private final boolean[] written;
    private final int[] quant = new int[1024];
    private final double[] lpc = new double[33];
    private final double[] parcor = new double[33];
    private int random = 0x1f2e3d4c;

    /** SBR, when the stream has it: one per element that carries audio, made on first use. */
    private AacSbr[] sbr;
    private final boolean sbrEnabled;
    private final int frameLength;
    private boolean sbrSeen;
    private final float[][] core;

    /** Scale factor band layout and side information of one channel in the element being decoded. */
    static final class Ics {
        int windowSequence;
        int windowShape;
        int maxSfb;
        int numWindows;
        int numWindowGroups;
        final int[] groupLength = new int[8];
        int[] swb;
        int numSwb;
        final int[] bandType = new int[8 * 64];
        /** Scale factor, intensity position or noise energy of each band, by band type. */
        final int[] bandValue = new int[8 * 64];
        boolean pulsePresent;
        int pulseCount;
        int pulseStartSfb;
        final int[] pulseOffset = new int[4];
        final int[] pulseAmp = new int[4];
        boolean tnsPresent;
        final int[] tnsFilters = new int[8];
        final int[] tnsCoefRes = new int[8];
        final int[][] tnsLength = new int[8][4];
        final int[][] tnsOrder = new int[8][4];
        final boolean[][] tnsDirection = new boolean[8][4];
        final int[][] tnsCompress = new int[8][4];
        final int[][][] tnsCoef = new int[8][4][32];
        final float[] spec = new float[1024];
        final boolean[] msUsed = new boolean[8 * 64];

        void copyInfo(Ics other) {
            windowSequence = other.windowSequence;
            windowShape = other.windowShape;
            maxSfb = other.maxSfb;
            numWindows = other.numWindows;
            numWindowGroups = other.numWindowGroups;
            System.arraycopy(other.groupLength, 0, groupLength, 0, 8);
            swb = other.swb;
            numSwb = other.numSwb;
        }
    }

    /** What a channel keeps from one frame to the next. */
    private static final class ChannelState {
        final float[] overlap = new float[1024];
        int previousShape;
    }

    AacDecoder(AacConfig config) throws AudioFormatException {
        this(config, config.sbr == 1);
    }

    /**
     * @param withSbr whether to run SBR: true when the configuration signals it, or when the first frame showed it
     */
    AacDecoder(AacConfig config, boolean withSbr) throws AudioFormatException {
        this.config = config;
        switch (config.objectType) {
            case AacConfig.AOT_LC -> {
            }
            case AacConfig.AOT_MAIN -> throw new UnsupportedFormatException("AAC Main");
            case AacConfig.AOT_SSR -> throw new UnsupportedFormatException("AAC SSR");
            case AacConfig.AOT_LTP -> throw new UnsupportedFormatException("AAC LTP");
            case 23 -> throw new UnsupportedFormatException("AAC-LD");
            case 39 -> throw new UnsupportedFormatException("AAC-ELD");
            case 17, 19, 20, 21, 22, 24, 25, 26, 27 -> throw new UnsupportedFormatException("ER AAC");
            case 42 -> throw new UnsupportedFormatException("xHE-AAC");
            default -> throw new UnsupportedFormatException("MPEG-4 audio object type "
                    + config.objectType);
        }
        if (config.frameLength960) {
            throw new UnsupportedFormatException("AAC with 960-sample frames");
        }
        if (config.dependsOnCoreCoder) {
            throw new UnsupportedFormatException("scalable AAC");
        }
        rateIndex = config.rateIndex;
        swbLong = AacTables.SWB_LONG[rateIndex];
        swbShort = AacTables.SWB_SHORT[rateIndex];
        int[][][] maps = channelMaps(config);
        sceMap = maps[0];
        cpeMap = maps[1];
        lfeMap = maps[2];
        coreChannels = config.channels();
        if (coreChannels <= 0 || coreChannels > 8) {
            throw new UnsupportedFormatException(coreChannels <= 0 ? "AAC without a channel layout"
                    : "AAC with " + coreChannels + " channels");
        }
        sbrEnabled = withSbr;
        frameLength = withSbr ? 2048 : 1024;
        // A mono stream with SBR may turn out to carry parametric stereo: two channels from the start.
        boolean psPossible = withSbr && coreChannels == 1 && config.ps != 0;
        outputChannels = psPossible ? 2 : coreChannels;
        states = new ChannelState[coreChannels];
        for (int i = 0; i < coreChannels; i++) {
            states[i] = new ChannelState();
        }
        written = new boolean[coreChannels];
        core = withSbr ? new float[coreChannels][1024] : null;
    }

    int outputChannels() {
        return outputChannels;
    }

    /** Samples per channel each access unit decodes to: 1024, or 2048 with SBR. */
    int frameLength() {
        return frameLength;
    }

    int outputRate() {
        return sbrEnabled ? config.sampleRate * 2 : config.sampleRate;
    }

    /** Whether a frame decoded so far carried SBR data, which is how implicit signalling shows itself. */
    boolean sbrSeen() {
        return sbrSeen;
    }

    /**
     * Decodes one access unit into {@code out[c][offset .. offset + frameLength())}.
     *
     * @param data must carry {@link AacBits#PADDING} readable bytes after the access unit
     * @throws AudioFormatException when the access unit is damaged
     */
    int decode(byte[] data, int offset, int length, float[][] out, int outOffset) throws AudioFormatException {
        bits.reset(data, offset, length);
        Arrays.fill(written, false);
        pending = 0;
        int sce = 0;
        int cpe = 0;
        int lfe = 0;
        int elements = 0;
        int lastAudioChannel = -1;
        boolean lastAudioPair = false;
        while (true) {
            if (bits.bitsLeft() < 3) {
                // A frame that simply stops is how some encoders end a stream; what was decoded stands.
                break;
            }
            int id = bits.read(3);
            if (id == ID_END) {
                break;
            }
            if (++elements > 64) {
                throw damaged("too many elements");
            }
            switch (id) {
                case ID_SCE, ID_LFE -> {
                    bits.skip(4);
                    int[] map = id == ID_SCE ? (sce < sceMap.length ? sceMap[sce] : null)
                            : (lfe < lfeMap.length ? lfeMap[lfe] : null);
                    if (id == ID_SCE) {
                        sce++;
                    } else {
                        lfe++;
                    }
                    decodeIcs(left, false, null);
                    lastAudioChannel = -1;
                    if (map != null) {
                        int ch = map[0];
                        finishChannel(left, ch, out, outOffset);
                        lastAudioChannel = ch;
                        lastAudioPair = false;
                        if (sbrEnabled) {
                            sbrElement(ch, -1);
                        }
                    }
                }
                case ID_CPE -> {
                    bits.skip(4);
                    int[] map = cpe < cpeMap.length ? cpeMap[cpe] : null;
                    cpe++;
                    decodeCpe();
                    lastAudioChannel = -1;
                    if (map != null) {
                        finishChannel(left, map[0], out, outOffset);
                        finishChannel(right, map[1], out, outOffset);
                        lastAudioChannel = map[0];
                        lastAudioPair = true;
                        if (sbrEnabled) {
                            sbrElement(map[0], map[1]);
                        }
                    }
                }
                case ID_CCE -> throw new UnsupportedFormatException("AAC with coupling channels");
                case ID_DSE -> skipDataStream();
                case ID_PCE -> AacConfig.readPce(bits);
                case ID_FIL -> fill(lastAudioChannel, lastAudioPair);
                default -> throw damaged("unknown element");
            }
            if (bits.overrun()) {
                throw damaged("an element runs past the end of the frame");
            }
        }
        // Channels the frame did not carry continue from their last state: silence plus the overlap tail.
        for (int ch = 0; ch < coreChannels; ch++) {
            if (!written[ch]) {
                Arrays.fill(left.spec, 0f);
                left.windowSequence = AacFilterBank.ONLY_LONG;
                left.windowShape = states[ch].previousShape;
                finishChannel(left, ch, out, outOffset);
                if (sbrEnabled) {
                    sbrElement(ch, -1);
                }
            }
        }
        if (sbrEnabled) {
            sbrOutput(out, outOffset);
        }
        bits.alignToByte();
        return Math.min(length, bits.bitsRead() / 8);
    }

    private static AudioFormatException damaged(String what) {
        return new AudioFormatException("Damaged AAC frame: " + what);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Channel layouts

    /**
     * For each element type, which output channels the n-th element of that type in a frame feeds, in ffmpeg's
     * output order. Elements are matched by their order in the frame, not their instance tags, because encoders
     * number the tags inconsistently and the order is what the configurations define.
     */
    private static int[][][] channelMaps(AacConfig c) throws AudioFormatException {
        int[][] sce;
        int[][] cpe;
        int[][] lfe = {};
        switch (c.channelConfiguration) {
            case 0 -> {
                return pceMaps(c.pce);
            }
            case 1 -> {
                sce = new int[][] {{0}};
                cpe = new int[0][];
            }
            case 2 -> {
                sce = new int[0][];
                cpe = new int[][] {{0, 1}};
            }
            case 3 -> {
                sce = new int[][] {{2}};
                cpe = new int[][] {{0, 1}};
            }
            case 4 -> {
                sce = new int[][] {{2}, {3}};
                cpe = new int[][] {{0, 1}};
            }
            case 5 -> {
                sce = new int[][] {{2}};
                cpe = new int[][] {{0, 1}, {3, 4}};
            }
            case 6 -> {
                sce = new int[][] {{2}};
                cpe = new int[][] {{0, 1}, {4, 5}};
                lfe = new int[][] {{3}};
            }
            case 7 -> {
                sce = new int[][] {{2}};
                cpe = new int[][] {{6, 7}, {0, 1}, {4, 5}};
                lfe = new int[][] {{3}};
            }
            case 11 -> {
                sce = new int[][] {{2}, {4}};
                cpe = new int[][] {{0, 1}, {5, 6}};
                lfe = new int[][] {{3}};
            }
            case 12, 14 -> {
                sce = new int[][] {{2}};
                cpe = new int[][] {{0, 1}, {6, 7}, {4, 5}};
                lfe = new int[][] {{3}};
            }
            default -> throw new UnsupportedFormatException("AAC channel configuration "
                    + c.channelConfiguration);
        }
        return new int[][][] {sce, cpe, lfe};
    }

    /**
     * A program config element's layout: front pair, front centre, LFE, back pair and centre, side pair, then any
     * further front pairs, which puts the usual layouts in the WAVE order.
     */
    private static int[][][] pceMaps(AacConfig.Pce p) throws AudioFormatException {
        if (p == null) {
            throw new UnsupportedFormatException("AAC without a channel layout");
        }
        java.util.List<int[]> sce = new java.util.ArrayList<>();
        java.util.List<int[]> cpe = new java.util.ArrayList<>();
        java.util.List<int[]> lfe = new java.util.ArrayList<>();
        // Positions are assigned group by group in the output order, but each element type keeps its order of
        // appearance in the PCE, which is the order the frames carry them in.
        int[] next = {0};
        java.util.Map<String, int[]> slots = new java.util.LinkedHashMap<>();
        boolean frontPairDone = false;
        for (int i = 0; i < p.frontIsCpe.length; i++) {
            if (p.frontIsCpe[i] == 1 && !frontPairDone) {
                slots.put("F" + i, new int[] {next[0]++, next[0]++});
                frontPairDone = true;
            }
        }
        for (int i = 0; i < p.frontIsCpe.length; i++) {
            if (p.frontIsCpe[i] == 0) {
                slots.put("F" + i, new int[] {next[0]++});
            }
        }
        for (int i = 0; i < p.lfeCount; i++) {
            slots.put("L" + i, new int[] {next[0]++});
        }
        for (int i = 0; i < p.backIsCpe.length; i++) {
            slots.put("B" + i, p.backIsCpe[i] == 1 ? new int[] {next[0]++, next[0]++} : new int[] {next[0]++});
        }
        for (int i = 0; i < p.sideIsCpe.length; i++) {
            slots.put("S" + i, p.sideIsCpe[i] == 1 ? new int[] {next[0]++, next[0]++} : new int[] {next[0]++});
        }
        for (int i = 0; i < p.frontIsCpe.length; i++) {
            if (!slots.containsKey("F" + i)) {
                slots.put("F" + i, new int[] {next[0]++, next[0]++});
            }
        }
        for (int i = 0; i < p.frontIsCpe.length; i++) {
            (p.frontIsCpe[i] == 1 ? cpe : sce).add(slots.get("F" + i));
        }
        for (int i = 0; i < p.sideIsCpe.length; i++) {
            (p.sideIsCpe[i] == 1 ? cpe : sce).add(slots.get("S" + i));
        }
        for (int i = 0; i < p.backIsCpe.length; i++) {
            (p.backIsCpe[i] == 1 ? cpe : sce).add(slots.get("B" + i));
        }
        for (int i = 0; i < p.lfeCount; i++) {
            lfe.add(slots.get("L" + i));
        }
        return new int[][][] {sce.toArray(new int[0][]), cpe.toArray(new int[0][]), lfe.toArray(new int[0][])};
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Elements

    private void decodeCpe() throws AudioFormatException {
        boolean commonWindow = bits.readBit();
        int msMaskPresent = 0;
        if (commonWindow) {
            readIcsInfo(left);
            right.copyInfo(left);
            msMaskPresent = bits.read(2);
            if (msMaskPresent == 3) {
                throw damaged("reserved M/S mask mode");
            }
            if (msMaskPresent == 1) {
                for (int g = 0; g < left.numWindowGroups; g++) {
                    for (int sfb = 0; sfb < left.maxSfb; sfb++) {
                        left.msUsed[g * 64 + sfb] = bits.readBit();
                    }
                }
            } else {
                Arrays.fill(left.msUsed, msMaskPresent == 2);
            }
        }
        decodeIcs(left, commonWindow, null);
        decodeIcs(right, commonWindow, commonWindow && msMaskPresent == 1 ? left : null);
        if (commonWindow && msMaskPresent != 0) {
            applyMidSide();
        }
        applyIntensity(msMaskPresent);
    }

    private void skipDataStream() {
        bits.skip(4);
        boolean align = bits.readBit();
        int count = bits.read(8);
        if (count == 255) {
            count += bits.read(8);
        }
        if (align) {
            bits.alignToByte();
        }
        bits.skip(8 * count);
    }

    /** A fill element: SBR data for the element before it, or padding and metadata this decoder does not use. */
    private void fill(int previousChannel, boolean pair) throws AudioFormatException {
        int count = bits.read(4);
        if (count == 15) {
            count += bits.read(8) - 1;
        }
        int end = bits.position() + 8 * count;
        if (count > 0) {
            int type = bits.read(4);
            if ((type == EXT_SBR_DATA || type == EXT_SBR_DATA_CRC) && previousChannel >= 0) {
                sbrSeen = true;
                if (sbrEnabled) {
                    sbrFor(previousChannel).readData(bits, type == EXT_SBR_DATA_CRC, end, pair);
                }
            }
        }
        // Whatever the payload was, the element ends where its count says.
        bits.seek(end);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Individual channel stream

    private void readIcsInfo(Ics ics) throws AudioFormatException {
        if (bits.readBit()) {
            // ics_reserved_bit: ffmpeg reads on, and so do we; it carries nothing.
        }
        ics.windowSequence = bits.read(2);
        ics.windowShape = bits.read(1);
        if (ics.windowSequence == AacFilterBank.EIGHT_SHORT) {
            ics.maxSfb = bits.read(4);
            int grouping = bits.read(7);
            ics.numWindows = 8;
            ics.numWindowGroups = 1;
            ics.groupLength[0] = 1;
            for (int w = 1; w < 8; w++) {
                if ((grouping & (1 << (7 - w))) != 0) {
                    ics.groupLength[ics.numWindowGroups - 1]++;
                } else {
                    ics.groupLength[ics.numWindowGroups++] = 1;
                }
            }
            ics.swb = swbShort;
        } else {
            ics.maxSfb = bits.read(6);
            ics.numWindows = 1;
            ics.numWindowGroups = 1;
            ics.groupLength[0] = 1;
            ics.swb = swbLong;
            if (bits.readBit()) {
                // predictor_data_present: Main and LTP only, which were refused at the start.
                throw damaged("prediction in an AAC LC stream");
            }
        }
        ics.numSwb = ics.swb.length - 1;
        if (ics.maxSfb > ics.numSwb) {
            throw damaged("more scale factor bands than the window has");
        }
    }

    /**
     * Reads one channel's side information and spectrum, and dequantises it into {@code ics.spec}.
     *
     * @param noisePartner the left channel when this is the right one of a pair whose M/S mask is sent band by band:
     *     a band both channels fill with noise and that has its M/S flag set reuses the left channel's noise
     */
    private void decodeIcs(Ics ics, boolean commonWindow, Ics noisePartner) throws AudioFormatException {
        int globalGain = bits.read(8);
        if (!commonWindow) {
            readIcsInfo(ics);
        }
        readSections(ics);
        readScaleFactors(ics, globalGain);
        ics.pulsePresent = bits.readBit();
        if (ics.pulsePresent) {
            if (ics.windowSequence == AacFilterBank.EIGHT_SHORT) {
                throw damaged("pulse data in short windows");
            }
            ics.pulseCount = bits.read(2) + 1;
            ics.pulseStartSfb = bits.read(6);
            if (ics.pulseStartSfb >= ics.numSwb) {
                throw damaged("pulse data past the last band");
            }
            for (int i = 0; i < ics.pulseCount; i++) {
                ics.pulseOffset[i] = bits.read(5);
                ics.pulseAmp[i] = bits.read(4);
            }
        }
        ics.tnsPresent = bits.readBit();
        if (ics.tnsPresent) {
            readTns(ics);
        }
        if (bits.readBit()) {
            throw damaged("gain control data, which only AAC SSR has");
        }
        readSpectrum(ics, noisePartner);
    }

    private void readSections(Ics ics) throws AudioFormatException {
        boolean shortWindows = ics.windowSequence == AacFilterBank.EIGHT_SHORT;
        int sectBits = shortWindows ? 3 : 5;
        int escape = (1 << sectBits) - 1;
        for (int g = 0; g < ics.numWindowGroups; g++) {
            int k = 0;
            while (k < ics.maxSfb) {
                int cb = bits.read(4);
                if (cb == 12) {
                    throw damaged("reserved codebook");
                }
                int length = 0;
                int increment;
                while ((increment = bits.read(sectBits)) == escape) {
                    length += escape;
                    if (bits.overrun()) {
                        throw damaged("section data runs past the end");
                    }
                }
                length += increment;
                if (length == 0 || k + length > ics.maxSfb) {
                    throw damaged("a section outside the bands");
                }
                for (int sfb = k; sfb < k + length; sfb++) {
                    ics.bandType[g * 64 + sfb] = cb;
                }
                k += length;
            }
        }
    }

    private void readScaleFactors(Ics ics, int globalGain) throws AudioFormatException {
        int sf = globalGain;
        int isPosition = 0;
        int noise = globalGain - 90;
        boolean firstNoise = true;
        for (int g = 0; g < ics.numWindowGroups; g++) {
            for (int sfb = 0; sfb < ics.maxSfb; sfb++) {
                int i = g * 64 + sfb;
                switch (ics.bandType[i]) {
                    case ZERO_HCB -> ics.bandValue[i] = 0;
                    case INTENSITY_HCB, INTENSITY_HCB2 -> {
                        isPosition += AacHuffman.scalefactorDelta(bits);
                        ics.bandValue[i] = isPosition;
                    }
                    case NOISE_HCB -> {
                        if (firstNoise) {
                            noise += bits.read(9) - 256;
                            firstNoise = false;
                        } else {
                            noise += AacHuffman.scalefactorDelta(bits);
                        }
                        ics.bandValue[i] = noise;
                    }
                    default -> {
                        sf += AacHuffman.scalefactorDelta(bits);
                        if (sf < 0 || sf > 255) {
                            throw damaged("scale factor out of range");
                        }
                        ics.bandValue[i] = sf;
                    }
                }
            }
        }
    }

    private void readTns(Ics ics) throws AudioFormatException {
        boolean shortWindows = ics.windowSequence == AacFilterBank.EIGHT_SHORT;
        int maxOrder = shortWindows ? 7 : 12;
        for (int w = 0; w < ics.numWindows; w++) {
            int filters = bits.read(shortWindows ? 1 : 2);
            ics.tnsFilters[w] = filters;
            if (filters == 0) {
                continue;
            }
            int coefRes = bits.read(1);
            ics.tnsCoefRes[w] = coefRes;
            for (int f = 0; f < filters; f++) {
                ics.tnsLength[w][f] = bits.read(shortWindows ? 4 : 6);
                int order = bits.read(shortWindows ? 3 : 5);
                if (order > maxOrder) {
                    throw damaged("TNS order " + order + " above the profile's " + maxOrder);
                }
                ics.tnsOrder[w][f] = order;
                if (order > 0) {
                    ics.tnsDirection[w][f] = bits.readBit();
                    int compress = bits.read(1);
                    ics.tnsCompress[w][f] = compress;
                    int coefBits = coefRes + 3 - compress;
                    for (int i = 0; i < order; i++) {
                        int v = bits.read(coefBits);
                        // Two's complement in coefBits bits.
                        ics.tnsCoef[w][f][i] = (v << (32 - coefBits)) >> (32 - coefBits);
                    }
                }
            }
        }
    }

    /** Huffman-decodes the spectrum, applies pulses, and dequantises with each band's scale factor. */
    private void readSpectrum(Ics ics, Ics noisePartner) throws AudioFormatException {
        int[] q = quant;
        Arrays.fill(q, 0);
        float[] spec = ics.spec;
        Arrays.fill(spec, 0f);
        int[] swb = ics.swb;
        int window = 0;
        for (int g = 0; g < ics.numWindowGroups; g++) {
            int groupLength = ics.groupLength[g];
            for (int sfb = 0; sfb < ics.maxSfb; sfb++) {
                int cb = ics.bandType[g * 64 + sfb];
                if (cb == ZERO_HCB || cb >= NOISE_HCB) {
                    continue;
                }
                for (int w = 0; w < groupLength; w++) {
                    int base = (window + w) * 128;
                    int start = base + swb[sfb];
                    int end = base + swb[sfb + 1];
                    readBand(cb, q, start, end);
                }
            }
            window += groupLength;
        }
        if (bits.overrun()) {
            throw damaged("spectral data runs past the end");
        }
        if (ics.pulsePresent) {
            int k = swb[ics.pulseStartSfb];
            for (int i = 0; i < ics.pulseCount; i++) {
                k += ics.pulseOffset[i];
                if (k >= 1024) {
                    throw damaged("a pulse outside the spectrum");
                }
                if (q[k] > 0) {
                    q[k] += ics.pulseAmp[i];
                } else {
                    q[k] -= ics.pulseAmp[i];
                }
            }
        }
        window = 0;
        for (int g = 0; g < ics.numWindowGroups; g++) {
            int groupLength = ics.groupLength[g];
            for (int sfb = 0; sfb < ics.maxSfb; sfb++) {
                int i = g * 64 + sfb;
                int cb = ics.bandType[i];
                if (cb == ZERO_HCB || cb == INTENSITY_HCB || cb == INTENSITY_HCB2) {
                    continue;
                }
                if (cb == NOISE_HCB) {
                    boolean correlated = noisePartner != null && noisePartner.msUsed[i]
                            && noisePartner.bandType[i] == NOISE_HCB;
                    // Both channels' noise in a band with its M/S flag set is one random vector, each channel at
                    // its own energy (4.6.13.3): the left one already holds it at the left energy.
                    float ratio = correlated
                            ? (float) StrictMath.pow(2.0, 0.25 * (ics.bandValue[i] - noisePartner.bandValue[i])) : 0f;
                    for (int w = 0; w < groupLength; w++) {
                        int base = (window + w) * 128;
                        int start = base + swb[sfb];
                        int end = base + swb[sfb + 1];
                        if (correlated) {
                            float[] from = noisePartner.spec;
                            for (int k = start; k < end; k++) {
                                spec[k] = from[k] * ratio;
                            }
                        } else {
                            noise(spec, start, end, ics.bandValue[i]);
                        }
                    }
                    continue;
                }
                float gain = SF_GAIN[ics.bandValue[i]];
                for (int w = 0; w < groupLength; w++) {
                    int base = (window + w) * 128;
                    for (int k = base + swb[sfb], e = base + swb[sfb + 1]; k < e; k++) {
                        int v = q[k];
                        if (v != 0) {
                            spec[k] = v > 0 ? POW43[v] * gain : -POW43[-v] * gain;
                        }
                    }
                }
            }
            window += groupLength;
        }
    }

    private void readBand(int cb, int[] q, int start, int end) throws AudioFormatException {
        switch (cb) {
            case 1, 2 -> {
                for (int k = start; k < end; k += 4) {
                    int s = AacHuffman.decode(bits, cb);
                    q[k] = s / 27 - 1;
                    q[k + 1] = s / 9 % 3 - 1;
                    q[k + 2] = s / 3 % 3 - 1;
                    q[k + 3] = s % 3 - 1;
                }
            }
            case 3, 4 -> {
                for (int k = start; k < end; k += 4) {
                    int s = AacHuffman.decode(bits, cb);
                    q[k] = signed(s / 27);
                    q[k + 1] = signed(s / 9 % 3);
                    q[k + 2] = signed(s / 3 % 3);
                    q[k + 3] = signed(s % 3);
                }
            }
            case 5, 6 -> {
                for (int k = start; k < end; k += 2) {
                    int s = AacHuffman.decode(bits, cb);
                    q[k] = s / 9 - 4;
                    q[k + 1] = s % 9 - 4;
                }
            }
            case 7, 8 -> {
                for (int k = start; k < end; k += 2) {
                    int s = AacHuffman.decode(bits, cb);
                    q[k] = signed(s >> 3);
                    q[k + 1] = signed(s & 7);
                }
            }
            case 9, 10 -> {
                for (int k = start; k < end; k += 2) {
                    int s = AacHuffman.decode(bits, cb);
                    q[k] = signed(s / 13);
                    q[k + 1] = signed(s % 13);
                }
            }
            default -> {
                for (int k = start; k < end; k += 2) {
                    int s = AacHuffman.decode(bits, ESC_HCB);
                    int a = signed(s / 17);
                    int b = signed(s % 17);
                    q[k] = a == 16 || a == -16 ? escape(a) : a;
                    q[k + 1] = b == 16 || b == -16 ? escape(b) : b;
                }
            }
        }
    }

    /** Reads the sign bit that follows a non-zero value of an unsigned codebook. */
    private int signed(int magnitude) {
        if (magnitude == 0) {
            return 0;
        }
        return bits.readBit() ? -magnitude : magnitude;
    }

    /** An escape: N ones, a zero, then an N + 4 bit word; the magnitude is 2^(N+4) plus the word. */
    private int escape(int signed16) throws AudioFormatException {
        int n = 0;
        while (bits.readBit()) {
            if (++n > 8) {
                throw damaged("an escape longer than 13 bits");
            }
        }
        int magnitude = (1 << (n + 4)) + bits.read(n + 4);
        return signed16 < 0 ? -magnitude : magnitude;
    }

    /**
     * Perceptual noise substitution (4.6.13): random values scaled so the band's energy is 2^(noise / 2) (in 16-bit
     * units, hence the 2^-15). The specification leaves the generator open. This one is the common 32-bit linear
     * congruential generator (multiplier 1664525, increment 1013904223), drawn band by band in bitstream order from a
     * fixed seed; with the seed ffmpeg's decoder happens to use, its noise and this one's are the same numbers, which
     * keeps the decode comparable sample by sample. The constants are numbers, not anyone's code.
     */
    private void noise(float[] spec, int start, int end, int energyIndex) {
        double energy = 0;
        for (int k = start; k < end; k++) {
            random = random * 1664525 + 1013904223;
            spec[k] = random;
            energy += (double) spec[k] * spec[k];
        }
        if (energy <= 0) {
            return;
        }
        double scale = StrictMath.pow(2.0, 0.25 * energyIndex - 15) / Math.sqrt(energy);
        for (int k = start; k < end; k++) {
            spec[k] = (float) (spec[k] * scale);
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Stereo tools

    /** M/S (4.6.8.1): left and right are mid plus and minus side, in bands that are neither noise nor intensity. */
    private void applyMidSide() {
        float[] l = left.spec;
        float[] r = right.spec;
        int[] swb = left.swb;
        int window = 0;
        for (int g = 0; g < left.numWindowGroups; g++) {
            for (int sfb = 0; sfb < left.maxSfb; sfb++) {
                int i = g * 64 + sfb;
                if (!left.msUsed[i]) {
                    continue;
                }
                int rt = right.bandType[i];
                if (rt == NOISE_HCB || rt == INTENSITY_HCB || rt == INTENSITY_HCB2 || left.bandType[i] == NOISE_HCB) {
                    continue;
                }
                for (int w = 0; w < left.groupLength[g]; w++) {
                    int base = (window + w) * 128;
                    for (int k = base + swb[sfb], e = base + swb[sfb + 1]; k < e; k++) {
                        float m = l[k];
                        float s = r[k];
                        l[k] = m + s;
                        r[k] = m - s;
                    }
                }
            }
            window += left.groupLength[g];
        }
    }

    /**
     * Intensity stereo (4.6.8.2): the right channel is the left one scaled by 0.5^(position / 4), its sign set by
     * the codebook and inverted where the band's M/S flag is set, but only when the flags are sent band by band
     * (ms_mask_present 1). The specification's invert_intensity() returns 1 for ms_mask_present 2, and so do
     * PacketVideo's OpenCORE decoder and Apple's AudioToolbox, whose decode of such files matches this one to 85 dB.
     * ffmpeg 9 and libxaac invert those bands as well, and on ffmpeg's own encodes the right channel then comes out
     * further from the encoder's input: 10.8 dB SNR against 11.8 dB without the inversion, measured where the
     * signal's two channels were identical.
     */
    private void applyIntensity(int msMaskPresent) {
        float[] l = left.spec;
        float[] r = right.spec;
        int[] swb = right.swb;
        int window = 0;
        for (int g = 0; g < right.numWindowGroups; g++) {
            for (int sfb = 0; sfb < right.maxSfb; sfb++) {
                int i = g * 64 + sfb;
                int cb = right.bandType[i];
                if (cb != INTENSITY_HCB && cb != INTENSITY_HCB2) {
                    continue;
                }
                double sign = cb == INTENSITY_HCB ? 1 : -1;
                if (msMaskPresent == 1 && left.msUsed[i]) {
                    sign = -sign;
                }
                float scale = (float) (sign * StrictMath.pow(0.5, 0.25 * right.bandValue[i]));
                for (int w = 0; w < right.groupLength[g]; w++) {
                    int base = (window + w) * 128;
                    for (int k = base + swb[sfb], e = base + swb[sfb + 1]; k < e; k++) {
                        r[k] = l[k] * scale;
                    }
                }
            }
            window += right.groupLength[g];
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // TNS and synthesis

    private void finishChannel(Ics ics, int channel, float[][] out, int outOffset) throws AudioFormatException {
        if (ics.tnsPresent) {
            applyTns(ics);
        }
        ChannelState state = states[channel];
        float[] target = sbrEnabled ? core[channel] : out[channel];
        int at = sbrEnabled ? 0 : outOffset;
        filterBank.synthesize(ics.spec, ics.windowSequence, ics.windowShape, state.previousShape, state.overlap,
                target, at);
        state.previousShape = ics.windowShape;
        written[channel] = true;
    }

    /** Temporal noise shaping (4.6.9): an all-pole filter run over parts of the spectrum, per window. */
    private void applyTns(Ics ics) {
        boolean shortWindows = ics.windowSequence == AacFilterBank.EIGHT_SHORT;
        int maxBands = Math.min(shortWindows ? AacTables.TNS_MAX_BANDS_SHORT[rateIndex]
                : AacTables.TNS_MAX_BANDS_LONG[rateIndex], ics.maxSfb);
        float[] spec = ics.spec;
        int[] swb = ics.swb;
        for (int w = 0; w < ics.numWindows; w++) {
            int bottom = ics.numSwb;
            for (int f = 0; f < ics.tnsFilters[w]; f++) {
                int top = bottom;
                bottom = Math.max(top - ics.tnsLength[w][f], 0);
                int order = ics.tnsOrder[w][f];
                if (order == 0) {
                    continue;
                }
                tnsCoefficients(order, ics.tnsCoefRes[w] + 3, ics.tnsCoef[w][f]);
                int start = swb[Math.min(bottom, maxBands)];
                int end = swb[Math.min(top, maxBands)];
                int size = end - start;
                if (size <= 0) {
                    continue;
                }
                int inc = 1;
                int k = w * 128 + start;
                if (ics.tnsDirection[w][f]) {
                    inc = -1;
                    k = w * 128 + end - 1;
                }
                for (int m = 0; m < size; m++, k += inc) {
                    double y = spec[k];
                    for (int i = 1, limit = Math.min(m, order); i <= limit; i++) {
                        y -= lpc[i] * spec[k - i * inc];
                    }
                    spec[k] = (float) y;
                }
            }
        }
    }

    /**
     * The filter's LPC coefficients from its transmitted reflection coefficients (4.6.9.3): inverse quantisation
     * by an arcsine-spaced table, then the step-up recursion.
     */
    private void tnsCoefficients(int order, int coefResBits, int[] coef) {
        double iqfac = ((1 << (coefResBits - 1)) - 0.5) / (Math.PI / 2.0);
        double iqfacM = ((1 << (coefResBits - 1)) + 0.5) / (Math.PI / 2.0);
        for (int i = 0; i < order; i++) {
            int c = coef[i];
            parcor[i] = StrictMath.sin(c / (c >= 0 ? iqfac : iqfacM));
        }
        double[] a = lpc;
        double[] b = new double[order + 1];
        a[0] = 1;
        for (int m = 1; m <= order; m++) {
            for (int i = 1; i < m; i++) {
                b[i] = a[i] + parcor[m - 1] * a[m - i];
            }
            for (int i = 1; i < m; i++) {
                a[i] = b[i];
            }
            a[m] = parcor[m - 1];
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // SBR glue

    private AacSbr sbrFor(int channel) {
        if (sbr == null) {
            sbr = new AacSbr[coreChannels];
        }
        if (sbr[channel] == null) {
            sbr[channel] = new AacSbr(config.sampleRate, config.ps != 0 && coreChannels == 1);
        }
        return sbr[channel];
    }

    private final int[] pendingLeft = new int[8];
    private final int[] pendingRight = new int[8];
    private int pending;

    /** Remembers which channels the element just decoded fed, so SBR runs on them once the frame is read. */
    private void sbrElement(int leftChannel, int rightChannel) {
        if (pending < pendingLeft.length) {
            pendingLeft[pending] = leftChannel;
            pendingRight[pending] = rightChannel;
            pending++;
        }
    }

    private void sbrOutput(float[][] out, int outOffset) throws AudioFormatException {
        for (int e = 0; e < pending; e++) {
            int l = pendingLeft[e];
            int r = pendingRight[e];
            AacSbr s = sbrFor(l);
            if (r >= 0) {
                s.processPair(core[l], core[r], out[l], out[r], outOffset);
            } else if (outputChannels == 2 && coreChannels == 1) {
                s.processMono(core[l], out[0], out[1], outOffset);
            } else {
                s.processMono(core[l], out[l], null, outOffset);
            }
        }
        pending = 0;
    }
}

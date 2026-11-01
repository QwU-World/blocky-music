package ru.gora.musicbattle.audio.codec;

/**
 * What an AAC stream says about itself before its first frame: the AudioSpecificConfig of an MP4 file (ISO/IEC
 * 14496-3, 1.6.2.1) or the fixed header of an ADTS frame (1.A.3.2.1).
 *
 * <p>SBR and PS can be signalled three ways, and all three are read: hierarchically (object type 5 or 29 first, the
 * core's object type after it), backward-compatibly (the core's config followed by sync extension 0x2b7 for SBR and
 * 0x548 for PS), or not at all, in which case only the first frame can tell (implicit signalling). An unknown answer
 * is kept as -1 so the decoder can look.
 */
final class AacConfig {
    static final int AOT_MAIN = 1;
    static final int AOT_LC = 2;
    static final int AOT_SSR = 3;
    static final int AOT_LTP = 4;
    static final int AOT_SBR = 5;
    static final int AOT_PS = 29;

    /** The core codec's object type: 2 for AAC LC. */
    int objectType;
    /** The core's sampling rate in Hz, and the index whose tables it uses. */
    int sampleRate;
    int rateIndex;
    /** 1 to 7 and 11 to 14 name a fixed layout; 0 means a program config element gives it. */
    int channelConfiguration;
    /** 1 when the stream has SBR, 0 when it is said not to, -1 when unknown. */
    int sbr = -1;
    /** The same for parametric stereo. */
    int ps = -1;
    /** The output rate SBR doubles to, when signalled. */
    int extensionSampleRate;
    boolean frameLength960;
    boolean dependsOnCoreCoder;
    /** The program config element when channelConfiguration is 0. */
    Pce pce;

    /** A program config element: which elements carry the channels, and where the speakers are. */
    static final class Pce {
        int objectType;
        int rateIndex;
        int[] frontIsCpe = new int[0];
        int[] sideIsCpe = new int[0];
        int[] backIsCpe = new int[0];
        int lfeCount;
        int[] frontTag = new int[0];
        int[] sideTag = new int[0];
        int[] backTag = new int[0];
        int[] lfeTag = new int[0];
        int ccCount;

        int channels() {
            int n = lfeCount;
            for (int c : frontIsCpe) {
                n += c + 1;
            }
            for (int c : sideIsCpe) {
                n += c + 1;
            }
            for (int c : backIsCpe) {
                n += c + 1;
            }
            return n;
        }
    }

    /** The number of output channels the configuration names, before SBR's parametric stereo; 0 if unknown. */
    int channels() {
        return switch (channelConfiguration) {
            case 0 -> pce == null ? 0 : pce.channels();
            case 1, 2, 3, 4, 5, 6 -> channelConfiguration;
            case 7 -> 8;
            case 11 -> 7;
            case 12 -> 8;
            case 13 -> 24;
            case 14 -> 8;
            default -> 0;
        };
    }

    /**
     * Reads an AudioSpecificConfig.
     *
     * @throws AudioFormatException when it is damaged or names no AAC at all
     */
    static AacConfig parse(byte[] data, int offset, int length) throws AudioFormatException {
        if (length <= 0 || length > 1 << 16) {
            throw new AudioFormatException("The AAC configuration is empty or damaged");
        }
        byte[] padded = new byte[length + AacBits.PADDING];
        System.arraycopy(data, offset, padded, 0, length);
        AacBits b = new AacBits();
        b.reset(padded, 0, length);
        AacConfig c = new AacConfig();
        c.objectType = objectType(b);
        c.sampleRate = sampleRate(b, c);
        c.channelConfiguration = b.read(4);
        int extensionType = 0;
        if (c.objectType == AOT_SBR || c.objectType == AOT_PS) {
            extensionType = AOT_SBR;
            c.sbr = 1;
            if (c.objectType == AOT_PS) {
                c.ps = 1;
            }
            c.extensionSampleRate = extensionRate(b);
            c.objectType = objectType(b);
            if (c.objectType == 22) {
                b.skip(4);
            }
        }
        switch (c.objectType) {
            case 1, 2, 3, 4, 6, 7, 17, 19, 20, 21, 22, 23 -> gaSpecificConfig(b, c);
            default -> {
                // Not a general-audio coder: nothing after this can be read, and the decoder refuses it by type.
                return c;
            }
        }
        switch (c.objectType) {
            case 17, 19, 20, 21, 22, 23, 24, 25, 26, 27, 39 -> {
                int epConfig = b.read(2);
                if (epConfig == 2 || epConfig == 3) {
                    // Error protection configuration: nothing an AAC LC decoder uses, and its length is not known
                    // here, so the backward-compatible extension cannot be looked for behind it.
                    return c;
                }
            }
            default -> {
            }
        }
        if (extensionType != AOT_SBR && b.bitsLeft() >= 16) {
            int sync = b.read(11);
            if (sync == 0x2b7) {
                int ext = objectType(b);
                if (ext == AOT_SBR) {
                    c.sbr = b.readBit() ? 1 : 0;
                    if (c.sbr == 1) {
                        c.extensionSampleRate = extensionRate(b);
                        if (b.bitsLeft() >= 12) {
                            if (b.read(11) == 0x548) {
                                c.ps = b.readBit() ? 1 : 0;
                            }
                        }
                    }
                } else if (ext == 22) {
                    c.sbr = b.readBit() ? 1 : 0;
                    if (c.sbr == 1) {
                        c.extensionSampleRate = extensionRate(b);
                    }
                }
            }
        }
        if (b.overrun()) {
            throw new AudioFormatException("The AAC configuration is cut short");
        }
        return c;
    }

    private static int objectType(AacBits b) {
        int type = b.read(5);
        return type == 31 ? 32 + b.read(6) : type;
    }

    private static int sampleRate(AacBits b, AacConfig c) throws AudioFormatException {
        int index = b.read(4);
        int rate;
        if (index == 15) {
            rate = b.read(24);
        } else if (index < AacTables.SAMPLE_RATES.length) {
            rate = AacTables.SAMPLE_RATES[index];
        } else {
            throw new AudioFormatException("The AAC configuration names a reserved sampling rate");
        }
        if (rate < 1000 || rate > 192000) {
            throw new AudioFormatException("The AAC configuration names a sampling rate of " + rate + " Hz");
        }
        c.rateIndex = index == 15 ? AacTables.tableIndex(rate) : index;
        return rate;
    }

    private static int extensionRate(AacBits b) throws AudioFormatException {
        int index = b.read(4);
        if (index == 15) {
            return b.read(24);
        }
        if (index >= AacTables.SAMPLE_RATES.length) {
            throw new AudioFormatException("The AAC configuration names a reserved SBR sampling rate");
        }
        return AacTables.SAMPLE_RATES[index];
    }

    private static void gaSpecificConfig(AacBits b, AacConfig c) throws AudioFormatException {
        c.frameLength960 = b.readBit();
        c.dependsOnCoreCoder = b.readBit();
        if (c.dependsOnCoreCoder) {
            b.skip(14);
        }
        boolean extensionFlag = b.readBit();
        if (c.channelConfiguration == 0) {
            c.pce = readPce(b);
        }
        if (c.objectType == 6 || c.objectType == 20) {
            b.skip(3);
        }
        if (extensionFlag) {
            if (c.objectType == 22) {
                b.skip(16);
            }
            if (c.objectType == 17 || c.objectType == 19 || c.objectType == 20 || c.objectType == 23) {
                b.skip(3);
            }
            b.skip(1);
        }
    }

    /**
     * Reads a program_config_element (4.4.1.1). The byte alignment before its comment is relative to the start of
     * whatever carries it, which here is always the start of the reader's packet.
     */
    static Pce readPce(AacBits b) throws AudioFormatException {
        Pce p = new Pce();
        b.skip(4); // element_instance_tag
        p.objectType = b.read(2);
        p.rateIndex = b.read(4);
        int front = b.read(4);
        int side = b.read(4);
        int back = b.read(4);
        p.lfeCount = b.read(2);
        int assoc = b.read(3);
        p.ccCount = b.read(4);
        if (b.readBit()) {
            b.skip(4); // mono mixdown element
        }
        if (b.readBit()) {
            b.skip(4); // stereo mixdown element
        }
        if (b.readBit()) {
            b.skip(3); // matrix mixdown index and pseudo surround
        }
        p.frontIsCpe = new int[front];
        p.frontTag = new int[front];
        for (int i = 0; i < front; i++) {
            p.frontIsCpe[i] = b.read(1);
            p.frontTag[i] = b.read(4);
        }
        p.sideIsCpe = new int[side];
        p.sideTag = new int[side];
        for (int i = 0; i < side; i++) {
            p.sideIsCpe[i] = b.read(1);
            p.sideTag[i] = b.read(4);
        }
        p.backIsCpe = new int[back];
        p.backTag = new int[back];
        for (int i = 0; i < back; i++) {
            p.backIsCpe[i] = b.read(1);
            p.backTag[i] = b.read(4);
        }
        p.lfeTag = new int[p.lfeCount];
        for (int i = 0; i < p.lfeCount; i++) {
            p.lfeTag[i] = b.read(4);
        }
        b.skip(4 * assoc);
        b.skip(5 * p.ccCount);
        b.alignToByte();
        int comment = b.read(8);
        b.skip(8 * comment);
        if (b.overrun()) {
            throw new AudioFormatException("The AAC program configuration is cut short");
        }
        return p;
    }

    /**
     * The configuration an ADTS frame header gives (1.A.3.2.1). Its 2-bit profile is the object type minus one, and
     * ADTS cannot signal SBR or PS, so both stay unknown.
     *
     * @param h the first 7 bytes of the frame
     */
    static AacConfig fromAdts(byte[] h, int offset) throws AudioFormatException {
        AacConfig c = new AacConfig();
        c.objectType = ((h[offset + 2] & 0xFF) >> 6) + 1;
        int index = ((h[offset + 2] & 0xFF) >> 2) & 15;
        if (index >= AacTables.SAMPLE_RATES.length) {
            throw new AudioFormatException("An ADTS header names a reserved sampling rate");
        }
        c.rateIndex = index;
        c.sampleRate = AacTables.SAMPLE_RATES[index];
        c.channelConfiguration = ((h[offset + 2] & 1) << 2) | ((h[offset + 3] & 0xFF) >> 6);
        return c;
    }
}

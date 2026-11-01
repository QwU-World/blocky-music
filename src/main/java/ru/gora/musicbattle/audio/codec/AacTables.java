package ru.gora.musicbattle.audio.codec;

/**
 * Fixed AAC tables that depend on the sampling rate: the scale factor band offsets of long and short windows and the
 * number of bands TNS may filter (ISO/IEC 14496-3, 4.5.4 and 4.6.9).
 *
 * <p>The band edges and the TNS limits are taken from PacketVideo's OpenCORE AAC decoder (sfb.cpp, get_tns.cpp),
 * Apache License 2.0, Copyright (C) 1998-2009 PacketVideo, as shipped in the Android Open Source Project at tag
 * android-4.0.4_r1 (frameworks/base, media/libstagefright/codecs/aacdec). They are the specification's tables; the
 * leading 0 of each list is added here.
 */
final class AacTables {
    /** The sampling frequency index of the specification, 0 to 12. */
    static final int[] SAMPLE_RATES = {96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025,
            8000, 7350};

    private static final int[] LONG_96 = {0, 4, 8, 12, 16, 20, 24, 28, 32, 36, 40, 44, 48, 52, 56, 64, 72, 80, 88, 96,
            108, 120, 132, 144, 156, 172, 188, 212, 240, 276, 320, 384, 448, 512, 576, 640, 704, 768, 832, 896, 960,
            1024};
    private static final int[] LONG_64 = {0, 4, 8, 12, 16, 20, 24, 28, 32, 36, 40, 44, 48, 52, 56, 64, 72, 80, 88, 100,
            112, 124, 140, 156, 172, 192, 216, 240, 268, 304, 344, 384, 424, 464, 504, 544, 584, 624, 664, 704, 744,
            784, 824, 864, 904, 944, 984, 1024};
    private static final int[] LONG_48 = {0, 4, 8, 12, 16, 20, 24, 28, 32, 36, 40, 48, 56, 64, 72, 80, 88, 96, 108,
            120, 132, 144, 160, 176, 196, 216, 240, 264, 292, 320, 352, 384, 416, 448, 480, 512, 544, 576, 608, 640,
            672, 704, 736, 768, 800, 832, 864, 896, 928, 1024};
    private static final int[] LONG_32 = {0, 4, 8, 12, 16, 20, 24, 28, 32, 36, 40, 48, 56, 64, 72, 80, 88, 96, 108,
            120, 132, 144, 160, 176, 196, 216, 240, 264, 292, 320, 352, 384, 416, 448, 480, 512, 544, 576, 608, 640,
            672, 704, 736, 768, 800, 832, 864, 896, 928, 960, 992, 1024};
    private static final int[] LONG_24 = {0, 4, 8, 12, 16, 20, 24, 28, 32, 36, 40, 44, 52, 60, 68, 76, 84, 92, 100,
            108, 116, 124, 136, 148, 160, 172, 188, 204, 220, 240, 260, 284, 308, 336, 364, 396, 432, 468, 508, 552,
            600, 652, 704, 768, 832, 896, 960, 1024};
    private static final int[] LONG_16 = {0, 8, 16, 24, 32, 40, 48, 56, 64, 72, 80, 88, 100, 112, 124, 136, 148, 160,
            172, 184, 196, 212, 228, 244, 260, 280, 300, 320, 344, 368, 396, 424, 456, 492, 532, 572, 616, 664, 716,
            772, 832, 896, 960, 1024};
    private static final int[] LONG_8 = {0, 12, 24, 36, 48, 60, 72, 84, 96, 108, 120, 132, 144, 156, 172, 188, 204,
            220, 236, 252, 268, 288, 308, 328, 348, 372, 396, 420, 448, 476, 508, 544, 580, 620, 664, 712, 764, 820,
            880, 944, 1024};

    private static final int[] SHORT_64 = {0, 4, 8, 12, 16, 20, 24, 32, 40, 48, 64, 92, 128};
    private static final int[] SHORT_48 = {0, 4, 8, 12, 16, 20, 28, 36, 44, 56, 68, 80, 96, 112, 128};
    private static final int[] SHORT_24 = {0, 4, 8, 12, 16, 20, 24, 28, 36, 44, 52, 64, 76, 92, 108, 128};
    private static final int[] SHORT_16 = {0, 4, 8, 12, 16, 20, 24, 28, 32, 40, 48, 60, 72, 88, 108, 128};
    private static final int[] SHORT_8 = {0, 4, 8, 12, 16, 20, 24, 28, 36, 44, 52, 60, 72, 88, 108, 128};

    /** Band offsets of a long window, by sampling frequency index; the last entry is 1024. */
    static final int[][] SWB_LONG = {LONG_96, LONG_96, LONG_64, LONG_48, LONG_48, LONG_32, LONG_24, LONG_24, LONG_16,
            LONG_16, LONG_16, LONG_8, LONG_8};
    /** Band offsets of a short window, by sampling frequency index; the last entry is 128. */
    static final int[][] SWB_SHORT = {SHORT_64, SHORT_64, SHORT_64, SHORT_48, SHORT_48, SHORT_48, SHORT_24, SHORT_24,
            SHORT_16, SHORT_16, SHORT_16, SHORT_8, SHORT_8};

    /** The highest band TNS filters in a long window (AAC LC), by sampling frequency index. */
    static final int[] TNS_MAX_BANDS_LONG = {31, 31, 34, 40, 42, 51, 46, 46, 42, 42, 42, 39, 39};
    /** The same for short windows. */
    static final int[] TNS_MAX_BANDS_SHORT = {9, 9, 10, 14, 14, 14, 14, 14, 14, 14, 14, 14, 14};

    private AacTables() {
    }

    /**
     * The sampling frequency index whose tables a stream at {@code rate} uses: the index itself for the thirteen
     * standard rates, and for any other rate the band the specification maps it into (ISO/IEC 14496-3, 4.5.3.1,
     * Table 4.82).
     */
    static int tableIndex(int rate) {
        if (rate >= 92017) {
            return 0;
        }
        if (rate >= 75132) {
            return 1;
        }
        if (rate >= 55426) {
            return 2;
        }
        if (rate >= 46009) {
            return 3;
        }
        if (rate >= 37566) {
            return 4;
        }
        if (rate >= 27713) {
            return 5;
        }
        if (rate >= 23004) {
            return 6;
        }
        if (rate >= 18783) {
            return 7;
        }
        if (rate >= 13856) {
            return 8;
        }
        if (rate >= 11502) {
            return 9;
        }
        if (rate >= 9391) {
            return 10;
        }
        return 11;
    }
}

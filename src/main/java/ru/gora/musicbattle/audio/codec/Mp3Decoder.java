package ru.gora.musicbattle.audio.codec;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.util.Arrays;
import java.util.Objects;

/**
 * MPEG audio decoder (MPEG-1, 2 and 2.5; Layer III, and Layer I and II) in plain Java, so importing an osu! map or
 * picking a song in the chart editor no longer needs an ffmpeg the player had to install first.
 *
 * <p>The frame decoder is a port of minimp3 by Lieff (https://github.com/lieff/minimp3, commit
 * ea99364f61c14656440e8d77e9c233ccf3124633, CC0 1.0 public domain dedication). The bit reader, side info, scale
 * factors, Huffman tables, stereo processing, IMDCT and polyphase synthesis keep minimp3's structure and its order of
 * floating point operations, so for the same frames this produces the same samples as minimp3's scalar float build.
 * Where this file differs from minimp3 the comment at that spot says why.
 *
 * <p>Everything around the frames follows what {@code ffmpeg -i song.mp3} does rather than what minimp3_ex does,
 * because the timing of every imported beatmap was measured against ffmpeg's decode: a chart is only in sync when the
 * first sample and the length come out exactly the same. The rules, each one checked against ffmpeg 9 on real files:
 * <ul>
 *   <li>ID3v2 tags at the start are skipped by their declared size (plus the footer of a v2.4 tag); their
 *       unsynchronisation flag changes the tag's content, never its length.</li>
 *   <li>A Xing, Info or VBRI frame is only looked for directly after those tags. It is never decoded. When the tag
 *       says LAME, Lavf or Lavc, the encoder delay plus the decoder's own 529 samples are dropped at the start, and the
 *       padding is dropped from exactly the packets ffmpeg's demuxer marks, which is not always the arithmetic
 *       {@code frames * 1152 - delay - padding} (a zero padding drops nothing at the end at all).</li>
 *   <li>Junk before the first frame is skipped the way ffmpeg's demuxer does: the first of two consecutive frames
 *       within 64 KiB.</li>
 *   <li>After that, frames are cut at the length their header gives. A frame that follows junk is dropped, because
 *       ffmpeg's decoder rejects a packet that does not start with a header; zero bytes are not junk. Tags found in
 *       the middle or at the end (ID3v2, APEv2, ID3v1) count as junk too, but their bodies are skipped whole so a cover
 *       picture cannot be mistaken for frames.</li>
 *   <li>A truncated last frame is decoded with zeros in place of the missing bytes, as ffmpeg does.</li>
 *   <li>A frame whose bit reservoir is not there (the first frame of a cut file) still gives its full length: the
 *       granules whose data is missing are silent, the rest are decoded.</li>
 *   <li>A stream whose sample rate changes part way (two files of different rates joined, a radio stream that
 *       switched) keeps the rate it starts with, as ffmpeg's output does: the frames at another rate are resampled to
 *       it ({@link SincResampler}), so they play at their own speed and pitch and take their own time. ffmpeg also
 *       drops the first frame after such a change ("Header missing"); this decodes it.</li>
 * </ul>
 * Free-format streams (bitrate index 0: every frame the same length, which the header does not give) are read too,
 * as the standard (ISO/IEC 11172-3, 2.4.2.3) and minimp3's {@code mp3d_find_frame} find their frames: the length is
 * the distance from the first frame header to the next one of the same stream, confirmed by a third, and it then holds
 * for every frame, plus the padding slot a frame's header announces. ffmpeg refuses such files ("Failed to find two
 * consecutive MPEG audio frames"), so there the reference is the standard and LAME's own decoder, whose decode of
 * LAME's free-format files this lines up with (same gapless trimming from the Info tag, same length, offset 0).
 */
public final class Mp3Decoder {
    private static final int MAX_FRAME_BYTES = 2881;          // Layer II, 160 kbit/s at 8 kHz, padded
    private static final int FRAME_SLACK = 64;
    private static final int JUNK_SEARCH_BYTES = 64 * 1024;   // ffmpeg's mp3 demuxer looks this far for the first frame
    private static final int ID3V1_SIZE = 128;
    private static final long MAX_LOOKAHEAD = 64L << 20;
    private static final int DECODER_DELAY = 528 + 1;
    private static final float SQRT1_2 = 0.70710677f;

    private Mp3Decoder() {
    }

    /**
     * Whether the first bytes of a file look like MPEG audio: an ID3v2 tag, or a frame header at the start or after
     * zero padding. An ID3v2 tag can also front a FLAC file; the sniffing caller should try FLAC when MP3 refuses.
     */
    public static boolean looksLike(byte[] head) {
        if (head == null) {
            return false;
        }
        if (head.length >= 10 && id3v2Match(head, 0)) {
            return true;
        }
        for (int i = 0; i + 4 <= head.length; i++) {
            if (head[i] != 0) {
                return parserHeaderValid(readInt(head, i));
            }
        }
        return false;
    }

    /** How many bytes {@link #findsFrames} wants: the 64 KiB searched, then room for the frame there and the next header. */
    public static final int SYNC_PROBE_BYTES = JUNK_SEARCH_BYTES + MAX_FRAME_BYTES + 4;

    /**
     * Whether {@link #open} would find MPEG audio in a stream that starts with these bytes: the first of two
     * consecutive matching frames within 64 KiB, the same test it starts decoding with. For a file whose first bytes
     * match no signature, such as an MP3 without a tag behind a stray byte or a long run of zeros, which
     * {@link #looksLike} cannot see past.
     */
    public static boolean findsFrames(byte[] head) {
        if (head == null) {
            return false;
        }
        for (int i = 0; i < JUNK_SEARCH_BYTES && i + 4 <= head.length; i++) {
            int h = readInt(head, i);
            if (!parserHeaderValid(h)) {
                continue;
            }
            int next = i + frameBytes(h);
            if (next + 4 > head.length) {
                continue;
            }
            int h2 = readInt(head, next);
            if (parserHeaderValid(h2) && (h & SAME_STREAM_MASK) == (h2 & SAME_STREAM_MASK)) {
                return true;
            }
        }
        for (int i = 0; i < JUNK_SEARCH_BYTES && i + 4 <= head.length; i++) {
            if (freeFormatBytes(head, i, head.length) > 0) {
                return true;
            }
        }
        return false;
    }

    /** The longest free-format frame, as minimp3 bounds it: Layer III at 640 kbit/s and 32 kHz is shorter. */
    private static final int MAX_FREE_FORMAT_FRAME_BYTES = 2304;

    /** A valid header of a free-format frame: the parser's test, but with bitrate index 0, which it refuses. */
    private static boolean freeHeaderValid(int h) {
        return ((h >>> 12) & 0xF) == 0 && parserHeaderValid((h & ~(0xF << 12)) | 1 << 12);
    }

    private static int paddingBytes(int h) {
        return testPadding(h) ? (isLayer1(h) ? 4 : 1) : 0;
    }

    /**
     * If a free-format frame header at {@code at} is followed by two more of the same stream, each one frame length
     * on (the same length for every frame, plus each frame's padding slot), that length without padding; otherwise 0.
     * Three headers in a row at one distance are not chance; minimp3 asks the same before it believes one.
     */
    private static int freeFormatBytes(byte[] b, int at, int length) {
        if (at + 4 > length) {
            return 0;
        }
        int h = readInt(b, at);
        if (!freeHeaderValid(h)) {
            return 0;
        }
        for (int k = 4; k <= MAX_FREE_FORMAT_FRAME_BYTES + 4 && at + k + 4 <= length; k++) {
            int h2 = readInt(b, at + k);
            if (!freeHeaderValid(h2) || (h & SAME_STREAM_MASK) != (h2 & SAME_STREAM_MASK)) {
                continue;
            }
            int bytes = k - paddingBytes(h);
            if (bytes <= 4 || (isLayer1(h) && (bytes & 3) != 0)) {
                continue;
            }
            int third = at + k + bytes + paddingBytes(h2);
            if (third + 4 <= length) {
                int h3 = readInt(b, third);
                if (freeHeaderValid(h3) && (h & SAME_STREAM_MASK) == (h3 & SAME_STREAM_MASK)) {
                    return bytes;
                }
            }
        }
        return 0;
    }

    /**
     * Opens an MPEG audio stream. The first frame is decoded here, so a file with no audio in it fails now with an
     * {@link AudioFormatException}. On success the returned stream owns {@code in} and closes it; on failure {@code in}
     * is left to the caller.
     */
    public static PcmStream open(InputStream in) throws IOException {
        Objects.requireNonNull(in, "in");
        return SteadyRate.of(new Mp3Stream(in, true, null));
    }

    /**
     * Opens MPEG audio that a container carries as bare frames, such as a WAV file of format 0x55. There ffmpeg looks
     * for no ID3v2 tag and no Xing, Info or VBRI frame: a tag frame is decoded like any other, and nothing is trimmed
     * for gapless playback, so this does neither. Ownership of {@code in} is as in {@link #open}.
     */
    static PcmStream openFrames(InputStream in) throws IOException {
        return openFrames(in, null);
    }

    /**
     * Where a container lost bytes of the stream: the positions in {@code in}'s bytes after which a frame's bit
     * reservoir is not the one its encoder meant.
     */
    interface ReservoirBreaks {
        /** Whether a break lies at or before {@code offset} that no earlier call reported; reports it once. */
        boolean passed(long offset);
    }

    /**
     * As {@link #openFrames(InputStream)}, for a container that loses bytes now and then (a transport stream's lost
     * packets): at each break the bit reservoir starts empty, as after a seek, so the first frames after it decode
     * the granules whose data lies after the break and leave silent the ones whose data came before it, rather than
     * reading the bytes before the break as their own (which gives bursts many times full scale). Lengths do not
     * change.
     */
    static PcmStream openFrames(InputStream in, ReservoirBreaks breaks) throws IOException {
        Objects.requireNonNull(in, "in");
        return SteadyRate.of(new Mp3Stream(in, false, breaks));
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Headers. The int holds the four header bytes big-endian; the names follow minimp3's HDR_* macros.

    private static int h1(int h) {
        return (h >>> 16) & 0xFF;
    }

    private static int h2(int h) {
        return (h >>> 8) & 0xFF;
    }

    private static int h3(int h) {
        return h & 0xFF;
    }

    private static boolean isMono(int h) {
        return (h3(h) & 0xC0) == 0xC0;
    }

    private static boolean isMsStereo(int h) {
        return (h3(h) & 0xE0) == 0x60;
    }

    private static boolean isCrc(int h) {
        return (h1(h) & 1) == 0;
    }

    private static boolean testPadding(int h) {
        return (h2(h) & 2) != 0;
    }

    private static boolean testMpeg1(int h) {
        return (h1(h) & 8) != 0;
    }

    private static boolean testNotMpeg25(int h) {
        return (h1(h) & 0x10) != 0;
    }

    private static boolean testIStereo(int h) {
        return (h3(h) & 0x10) != 0;
    }

    private static boolean testMsStereo(int h) {
        return (h3(h) & 0x20) != 0;
    }

    private static int stereoMode(int h) {
        return (h3(h) >> 6) & 3;
    }

    private static int stereoModeExt(int h) {
        return (h3(h) >> 4) & 3;
    }

    private static int layerBits(int h) {
        return (h1(h) >> 1) & 3;
    }

    private static int bitrateIndex(int h) {
        return h2(h) >> 4;
    }

    private static int sampleRateIndex(int h) {
        return (h2(h) >> 2) & 3;
    }

    private static int mySampleRate(int h) {
        return sampleRateIndex(h) + (((h1(h) >> 3) & 1) + ((h1(h) >> 4) & 1)) * 3;
    }

    private static boolean isFrame576(int h) {
        return (h1(h) & 14) == 2;
    }

    private static boolean isLayer1(int h) {
        return (h1(h) & 6) == 6;
    }

    private static final int[][][] HALFRATE = {
            {{0, 4, 8, 12, 16, 20, 24, 28, 32, 40, 48, 56, 64, 72, 80}, {0, 4, 8, 12, 16, 20, 24, 28, 32, 40, 48, 56, 64, 72, 80},
                    {0, 16, 24, 28, 32, 40, 48, 56, 64, 72, 80, 88, 96, 112, 128}},
            {{0, 16, 20, 24, 28, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160}, {0, 16, 24, 28, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192},
                    {0, 16, 32, 48, 64, 80, 96, 112, 128, 144, 160, 176, 192, 208, 224}},
    };

    private static int bitrateKbps(int h) {
        return 2 * HALFRATE[testMpeg1(h) ? 1 : 0][layerBits(h) - 1][bitrateIndex(h)];
    }

    private static int sampleRateHz(int h) {
        int[] hz = {44100, 48000, 32000};
        return hz[sampleRateIndex(h)] >> (testMpeg1(h) ? 0 : 1) >> (testNotMpeg25(h) ? 0 : 1);
    }

    private static int frameSamples(int h) {
        return isLayer1(h) ? 384 : (1152 >> (isFrame576(h) ? 1 : 0));
    }

    private static int frameBytes(int h) {
        int bytes = frameSamples(h) * bitrateKbps(h) * 125 / sampleRateHz(h);
        if (isLayer1(h)) {
            bytes &= ~3;
        }
        return bytes + (testPadding(h) ? (isLayer1(h) ? 4 : 1) : 0);
    }

    /**
     * The header test of ffmpeg's frame parser: sync, a known version, a layer, a sample rate, and a bitrate other than
     * "bad" and "free format". minimp3 also refuses Layer I and II in MPEG-2.5, which ffmpeg decodes.
     */
    private static boolean parserHeaderValid(int h) {
        if ((h & 0xFFE00000) != 0xFFE00000) {
            return false;
        }
        if ((h & (3 << 19)) == 1 << 19) {
            return false;
        }
        if ((h & (3 << 17)) == 0) {
            return false;
        }
        int bitrate = (h >>> 12) & 0xF;
        if (bitrate == 0xF || bitrate == 0) {
            return false;
        }
        return (h & (3 << 10)) != 3 << 10;
    }

    /** ffmpeg's demuxer compares the first two frames with this mask: the same version, layer, rate and channel mode. */
    private static final int SAME_STREAM_MASK = 0xFFFE0CCF;

    /**
     * The length in bytes of the frame a big-endian header starts, or -1 when ffmpeg's parser would not take it as a
     * header (free format included, whose length no header says). The MPEG-TS reader finds whole frames with it.
     */
    static int parsedFrameBytes(int h) {
        return parserHeaderValid(h) ? frameBytes(h) : -1;
    }

    /** Whether two headers belong to the same stream, by ffmpeg's demuxer's comparison. */
    static boolean sameStream(int a, int b) {
        return (a & SAME_STREAM_MASK) == (b & SAME_STREAM_MASK);
    }

    private static int readInt(byte[] b, int off) {
        return (b[off] & 0xFF) << 24 | (b[off + 1] & 0xFF) << 16 | (b[off + 2] & 0xFF) << 8 | (b[off + 3] & 0xFF);
    }

    /** The ID3v2 header test ffmpeg uses; it does not look at the flags byte, so neither does this. */
    private static boolean id3v2Match(byte[] b, int off) {
        return b[off] == 'I' && b[off + 1] == 'D' && b[off + 2] == '3' && (b[off + 3] & 0xFF) != 0xFF
                && (b[off + 4] & 0xFF) != 0xFF && (b[off + 6] & 0x80) == 0 && (b[off + 7] & 0x80) == 0
                && (b[off + 8] & 0x80) == 0 && (b[off + 9] & 0x80) == 0;
    }

    private static long id3v2Length(byte[] b, int off) {
        long size = (b[off + 6] & 0x7FL) << 21 | (b[off + 7] & 0x7FL) << 14 | (b[off + 8] & 0x7FL) << 7 | (b[off + 9] & 0x7FL);
        size += 10;
        if (b[off + 3] == 4 && (b[off + 5] & 0x10) != 0) {
            size += 10; // ffmpeg only honours the footer flag in version 2.4, the only version that has one
        }
        return size;
    }

    private static void checkInterrupt() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("MP3 decoding interrupted");
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Byte input with look-ahead: the junk search, the Xing probe and the concatenated-file test all peek ahead.

    private static final class Input {
        private final InputStream in;
        private byte[] buf = new byte[1 << 17];
        private int pos;
        private int end;
        private boolean eof;
        private long base;

        Input(InputStream in) {
            this.in = in;
        }

        long position() {
            return base + pos;
        }

        /** Buffers up to {@code n} bytes from the current position and says how many there are. */
        int fill(int n) throws IOException {
            if (end - pos >= n) {
                return n;
            }
            if (!eof) {
                if (pos + n > buf.length) {
                    int have = end - pos;
                    byte[] target = n > buf.length ? new byte[Math.max(n, buf.length * 2)] : buf;
                    System.arraycopy(buf, pos, target, 0, have);
                    buf = target;
                    base += pos;
                    pos = 0;
                    end = have;
                }
                while (end - pos < n) {
                    checkInterrupt();
                    int r = in.read(buf, end, buf.length - end);
                    if (r < 0) {
                        eof = true;
                        break;
                    }
                    if (r == 0) {
                        // A stream may answer 0 instead of blocking; one byte at a time cannot, so this never spins.
                        int b = in.read();
                        if (b < 0) {
                            eof = true;
                            break;
                        }
                        buf[end++] = (byte) b;
                    } else {
                        end += r;
                    }
                }
            }
            return Math.min(n, end - pos);
        }

        int at(int i) {
            return buf[pos + i] & 0xFF;
        }

        /** Big-endian bytes at {@code i}, with zeros past the end of the stream, as a read past the end gives in ffmpeg. */
        long be(int i, int count) throws IOException {
            int have = fill(i + count);
            long v = 0;
            for (int k = 0; k < count; k++) {
                v = v << 8 | (i + k < have ? at(i + k) : 0);
            }
            return v;
        }

        int intAt(int i) {
            return (buf[pos + i] & 0xFF) << 24 | (buf[pos + i + 1] & 0xFF) << 16 | (buf[pos + i + 2] & 0xFF) << 8
                    | (buf[pos + i + 3] & 0xFF);
        }

        void skip(int n) {
            pos += n;
        }

        void skipLong(long n) throws IOException {
            while (n > 0) {
                int have = fill((int) Math.min(n, 1 << 16));
                if (have == 0) {
                    return;
                }
                pos += have;
                n -= have;
            }
        }

        /** Whether the stream has a byte at absolute offset {@code offset}, reading ahead (but not past it) to find out. */
        boolean hasByteAt(long offset) throws IOException {
            long ahead = offset - position();
            if (ahead < 0) {
                return true;
            }
            if (ahead >= MAX_LOOKAHEAD) {
                return false;
            }
            // Grow toward the answer as bytes arrive, so a size field that lies costs no more memory than the file.
            int want = (int) ahead + 1;
            for (int step = 1 << 16; ; step *= 2) {
                int n = (int) Math.min(want, (long) end - pos + step);
                int got = fill(n);
                if (got >= want) {
                    return true;
                }
                if (got < n) {
                    return false;
                }
            }
        }

        void copy(int n, byte[] dst) {
            System.arraycopy(buf, pos, dst, 0, n);
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // The stream: ffmpeg's demuxer, parser and gapless trimming around minimp3's frame decoder.

    private static final class Mp3Stream implements PcmStream {
        private final Input input;
        private final InputStream source;
        private final FrameDecoder decoder = new FrameDecoder();
        /** Where the container lost bytes, or null. */
        private final ReservoirBreaks breaks;
        private final byte[] frame = new byte[MAX_FRAME_BYTES + FRAME_SLACK];
        private final float[][] pcm = new float[2][1152];
        private int sampleRate;
        private int channels;
        private int firstFrameSamples;
        private long frameCountHint = -1;

        private int startSkip;            // samples ffmpeg drops at the start, applied from the first packet
        private int skipRemaining;
        private boolean discardEnabled;   // ffmpeg's first/last discard sample window is set
        private long firstDiscard;
        private long lastDiscard;
        private long concatThreshold = -1; // a byte at this offset means ffmpeg calls the file concatenated
        private boolean concatChecked;
        private long packetStart;
        private boolean firstPacket = true;

        private int avail;
        private int availOff;
        private boolean ended;
        private boolean closed;
        /** The sample rate of the frame whose samples {@link #read} hands out now. */
        private int packetRate;

        /**
         * The sample rate of the samples {@link #read} hands out next, decoding the next frame if need be, or -1 at the
         * end. Each read stays within one frame, so a caller can follow a change of rate.
         */
        int nextRate() throws IOException {
            while (avail == 0) {
                if (ended || !nextPacket()) {
                    ended = true;
                    return -1;
                }
            }
            return packetRate;
        }

        /** For a free-format stream, the length of its frames without padding; 0 for the usual kind. */
        private int freeBytes;
        /** The first free-format header, which every later frame of the stream must match. */
        private int freeHeader;

        /** Whether a header starts a frame of this stream: the parser's test, or a free-format frame of it. */
        private boolean headerValid(int h) {
            return parserHeaderValid(h)
                    || freeBytes > 0 && freeHeaderValid(h) && (h & SAME_STREAM_MASK) == (freeHeader & SAME_STREAM_MASK);
        }

        private int bytesOf(int h) {
            return bitrateIndex(h) == 0 ? freeBytes + paddingBytes(h) : frameBytes(h);
        }

        /**
         * Looks for a free-format stream where the frame search found none: the first free-format header within the
         * search window that two more confirm, as {@link #freeFormatBytes} says.
         */
        private boolean findFreeFormat(int from) throws IOException {
            int have = input.fill(JUNK_SEARCH_BYTES + 3 * (MAX_FREE_FORMAT_FRAME_BYTES + 8));
            byte[] window = new byte[have];
            input.copy(have, window);
            for (int i = from; i < JUNK_SEARCH_BYTES && i + 4 <= have; i++) {
                int bytes = freeFormatBytes(window, i, have);
                if (bytes > 0) {
                    freeBytes = bytes;
                    freeHeader = readInt(window, i);
                    input.skip(i);
                    return true;
                }
            }
            return false;
        }

        Mp3Stream(InputStream in, boolean file, ReservoirBreaks breaks) throws IOException {
            this.source = in;
            this.breaks = breaks;
            this.input = new Input(in);
            if (file) {
                skipLeadingId3v2();
                long off0 = input.position();
                probeInfoFrame(off0);
            }
            findFirstFrame();
            while (avail == 0) {
                if (!nextPacket()) {
                    throw new AudioFormatException("No decodable MPEG audio frames");
                }
            }
        }

        private void skipLeadingId3v2() throws IOException {
            byte[] head = new byte[10];
            while (input.fill(10) == 10) {
                input.copy(10, head);
                if (!id3v2Match(head, 0)) {
                    return;
                }
                input.skipLong(id3v2Length(head, 0));
            }
        }

        /**
         * ffmpeg reads the frame directly after the ID3v2 tags and nowhere else: the Xing or Info tag at the fixed
         * offset its tables give (they ignore a CRC), then the VBRI tag. Without a frame count or a file size it is an
         * ordinary frame and gets decoded.
         */
        private void probeInfoFrame(long off0) throws IOException {
            if (input.fill(4) < 4) {
                return;
            }
            int h = input.intAt(0);
            if ((h & 0xFFE00000) != 0xFFE00000 || (h & (3 << 19)) == 1 << 19 || (h & (3 << 17)) == 0
                    || ((h >>> 12) & 0xF) == 0xF || (h & (3 << 10)) == 3 << 10) {
                return;
            }
            if (layerBits(h) != 1) {
                return;
            }
            boolean lsf = !testMpeg1(h);
            boolean mono = isMono(h);
            int spf = lsf ? 576 : 1152;
            if (bitrateIndex(h) == 0 && freeBytes == 0) {
                // A free-format stream, whose Info tag (LAME writes one) sits in a frame of the stream's own length.
                if (!findFreeFormat(0) || input.position() != off0) {
                    return;
                }
            }
            int tagSize = bitrateIndex(h) == 0 ? (freeBytes > 0 ? bytesOf(h) : 0) : frameBytes(h);
            long frames = 0;
            long headerFileSize = 0;
            boolean sizeFlag = false;
            int x = 4 + (lsf ? (mono ? 9 : 17) : (mono ? 17 : 32));
            long tag = input.be(x, 4);
            boolean gapless = false;
            int startPad = 0;
            int endPad = 0;
            if (tag == 0x58696E67L /* Xing */ || tag == 0x496E666FL /* Info */) {
                long flags = input.be(x + 4, 4);
                int p = x + 8;
                if ((flags & 1) != 0) {
                    frames = input.be(p, 4);
                    p += 4;
                }
                if ((flags & 2) != 0) {
                    headerFileSize = input.be(p, 4);
                    sizeFlag = headerFileSize != 0;
                    p += 4;
                }
                if ((flags & 4) != 0) {
                    p += 100;
                }
                if ((flags & 8) != 0) {
                    p += 4;
                }
                long version = input.be(p, 4);
                long pads = input.be(p + 21, 3);
                if (version == 0x4C414D45L /* LAME */ || version == 0x4C617666L /* Lavf */ || version == 0x4C617663L /* Lavc */) {
                    gapless = true;
                    startPad = (int) (pads >> 12);
                    endPad = (int) (pads & 4095);
                }
            }
            if (input.be(36, 4) == 0x56425249L /* VBRI */ && input.be(40, 2) == 1) {
                headerFileSize = input.be(46, 4);
                frames = input.be(50, 4);
            }
            if (frames == 0 && headerFileSize == 0) {
                return;
            }
            if (sizeFlag) {
                // ffmpeg forgets the frame count, and with it the end trimming, when the file is longer than the tag
                // says by more than a sixteenth: it takes that for two files glued together. Knowing that needs the
                // stream's length, which is only looked for once the end trimming is reached.
                concatThreshold = off0 + 4 + headerFileSize + (headerFileSize >> 4);
            }
            if (gapless) {
                startSkip = startPad + DECODER_DELAY;
                if (frames != 0) {
                    firstDiscard = -endPad + DECODER_DELAY + frames * spf;
                    lastDiscard = frames * spf;
                    discardEnabled = firstDiscard != 0; // ffmpeg reads a zero first discard sample as "none"
                }
            }
            if (frames != 0) {
                long total = frames * spf;
                long end = gapless && firstDiscard < lastDiscard ? firstDiscard : total;
                frameCountHint = Math.max(0, end - startSkip);
            }
            input.skip(Math.min(tagSize, input.fill(tagSize)));
        }

        /** ffmpeg's demuxer starts at the first of two consecutive matching frames within 64 KiB, or gives up on the file. */
        private void findFirstFrame() throws IOException {
            if (freeBytes > 0) {
                // The Info frame of a free-format stream was found where the stream starts.
                return;
            }
            for (int i = 0; i < JUNK_SEARCH_BYTES; i++) {
                if ((i & 0xFFF) == 0) {
                    checkInterrupt();
                }
                if (input.fill(i + 4) < i + 4) {
                    if (findFreeFormat(0)) {
                        return;
                    }
                    throw new AudioFormatException("Not MPEG audio: no two consecutive frames");
                }
                int h = input.intAt(i);
                if (!parserHeaderValid(h)) {
                    continue;
                }
                int next = i + frameBytes(h);
                if (input.fill(next + 4) < next + 4) {
                    continue;
                }
                int h2 = input.intAt(next);
                if (parserHeaderValid(h2) && (h & SAME_STREAM_MASK) == (h2 & SAME_STREAM_MASK)) {
                    input.skip(i);
                    return;
                }
            }
            if (freeBytes == 0) {
                // No frame of the usual kind: a free-format stream, which ffmpeg cannot read, is looked for instead.
                findFreeFormat(0);
            }
        }

        /** Skips a tag at the current position and says whether there was one. */
        private boolean skipTag() throws IOException {
            int have = input.fill(32);
            if (have >= 10 && input.at(0) == 'I' && input.at(1) == 'D' && input.at(2) == '3') {
                byte[] head = new byte[10];
                input.copy(10, head);
                if (id3v2Match(head, 0)) {
                    input.skipLong(id3v2Length(head, 0));
                    return true;
                }
            }
            if (have >= 32 && input.at(0) == 'A' && input.at(1) == 'P' && input.at(2) == 'E' && input.at(3) == 'T'
                    && input.at(4) == 'A' && input.at(5) == 'G' && input.at(6) == 'E' && input.at(7) == 'X') {
                long size = input.at(12) | input.at(13) << 8 | input.at(14) << 16 | (long) input.at(15) << 24;
                boolean isHeader = (input.at(23) & 0x20) != 0;
                input.skipLong(isHeader ? 32 + size : 32);
                return true;
            }
            if (have >= 3 && input.at(0) == 'T' && input.at(1) == 'A' && input.at(2) == 'G'
                    && input.fill(ID3V1_SIZE + 1) == ID3V1_SIZE) {
                input.skip(ID3V1_SIZE);
                return true;
            }
            return false;
        }

        /** Cuts and decodes the next packet the way ffmpeg's parser and decoder would; false at the end of the stream. */
        private boolean nextPacket() throws IOException {
            while (true) {
                checkInterrupt();
                boolean junk = false;
                int h;
                int scanned = 0;
                while (true) {
                    if (input.fill(4) < 4) {
                        return false;
                    }
                    h = input.intAt(0);
                    if (headerValid(h)) {
                        break;
                    }
                    if (skipTag()) {
                        junk = true;
                        continue;
                    }
                    if (input.at(0) != 0) {
                        junk = true;
                    }
                    input.skip(1);
                    if ((++scanned & 0xFFFF) == 0) {
                        checkInterrupt();
                    }
                }
                if (breaks != null && breaks.passed(input.position())) {
                    decoder.emptyReservoir();
                }
                int size = bytesOf(h);
                int have = input.fill(size);
                input.copy(have, frame);
                Arrays.fill(frame, have, size + FRAME_SLACK, (byte) 0);
                input.skip(have);

                int spf = frameSamples(h);
                if (firstFrameSamples == 0) {
                    firstFrameSamples = spf;
                }
                long start = packetStart;
                packetStart += spf;
                boolean first = firstPacket;
                firstPacket = false;
                if (junk) {
                    continue;
                }
                int got;
                try {
                    got = decoder.decodeFrame(frame, size, have, h, pcm);
                } catch (RuntimeException e) {
                    throw new AudioFormatException("Damaged MPEG audio frame", e);
                }
                if (got == 0) {
                    continue;
                }
                int nch = isMono(h) ? 1 : 2;
                if (channels == 0) {
                    channels = nch;
                    sampleRate = sampleRateHz(h);
                }
                packetRate = sampleRateHz(h);
                matchChannels(nch, got);
                if (first) {
                    skipRemaining = startSkip;
                }
                int pad = 0;
                if (discardEnabled) {
                    long duration = Math.max(firstFrameSamples, spf);
                    long endSample = start + duration;
                    if (endSample >= firstDiscard && start < lastDiscard && !concatenated()) {
                        pad = (int) Math.min(endSample - firstDiscard, duration);
                    }
                }
                int s = 0;
                int e = got;
                if (skipRemaining > 0) {
                    if (got <= skipRemaining) {
                        skipRemaining -= got;
                        continue;
                    }
                    s = skipRemaining;
                    skipRemaining = 0;
                }
                if (pad > 0 && pad <= e - s) {
                    if (pad == e - s) {
                        continue;
                    }
                    e -= pad;
                }
                availOff = s;
                avail = e - s;
                return true;
            }
        }

        private boolean concatenated() throws IOException {
            if (concatThreshold < 0) {
                return false;
            }
            if (!concatChecked) {
                concatChecked = true;
                if (input.hasByteAt(concatThreshold)) {
                    discardEnabled = false;
                }
            }
            return !discardEnabled;
        }

        /**
         * The stream keeps the first frame's channel count, as ffmpeg's output does, and a frame that differs is mixed
         * the way ffmpeg's resampler mixes into float: each channel scaled by 1/sqrt(2), whichever way it goes.
         */
        private void matchChannels(int nch, int samples) {
            if (nch == channels) {
                return;
            }
            float[] left = pcm[0];
            float[] right = pcm[1];
            if (channels == 2) {
                for (int i = 0; i < samples; i++) {
                    left[i] = right[i] = left[i] * SQRT1_2;
                }
            } else {
                for (int i = 0; i < samples; i++) {
                    left[i] = left[i] * SQRT1_2 + right[i] * SQRT1_2;
                }
            }
        }

        @Override
        public int sampleRate() {
            return sampleRate;
        }

        @Override
        public int channels() {
            return channels;
        }

        @Override
        public long frameCountHint() {
            return frameCountHint;
        }

        @Override
        public int read(float[][] out, int offset, int frames) throws IOException {
            if (closed) {
                throw new IOException("Stream closed");
            }
            if (frames <= 0) {
                return 0;
            }
            while (avail == 0) {
                if (ended || !nextPacket()) {
                    ended = true;
                    return -1;
                }
            }
            int n = Math.min(frames, avail);
            for (int c = 0; c < channels; c++) {
                System.arraycopy(pcm[c], availOff, out[c], offset, n);
            }
            availOff += n;
            avail -= n;
            return n;
        }

        @Override
        public void close() throws IOException {
            if (!closed) {
                closed = true;
                source.close();
            }
        }
    }

    /**
     * Keeps a stream at the rate its first frame has: a stretch of frames at another rate is resampled to it, each
     * stretch on its own, as from silence to silence, so its samples take exactly their own duration
     * (round(frames * rate / their rate)).
     */
    private static final class SteadyRate implements PcmStream {
        private final Mp3Stream raw;
        private final int rate;
        private PcmStream stretch;

        private SteadyRate(Mp3Stream raw) {
            this.raw = raw;
            this.rate = raw.sampleRate();
        }

        static PcmStream of(Mp3Stream raw) {
            return new SteadyRate(raw);
        }

        @Override
        public int sampleRate() {
            return rate;
        }

        @Override
        public int channels() {
            return raw.channels();
        }

        @Override
        public long frameCountHint() {
            return raw.frameCountHint();
        }

        @Override
        public int read(float[][] out, int offset, int frames) throws IOException {
            if (frames <= 0) {
                return 0;
            }
            while (true) {
                if (stretch != null) {
                    int n = stretch.read(out, offset, frames);
                    if (n > 0) {
                        return n;
                    }
                    stretch = null;
                }
                int next = raw.nextRate();
                if (next < 0) {
                    return -1;
                }
                if (next == rate) {
                    return raw.read(out, offset, frames);
                }
                stretch = SincResampler.to(new Stretch(next), rate);
            }
        }

        /** The frames from here on that are at {@code rate}: ends where a frame at another rate begins. */
        private final class Stretch implements PcmStream {
            private final int stretchRate;

            Stretch(int stretchRate) {
                this.stretchRate = stretchRate;
            }

            @Override
            public int sampleRate() {
                return stretchRate;
            }

            @Override
            public int channels() {
                return raw.channels();
            }

            @Override
            public int read(float[][] out, int offset, int frames) throws IOException {
                return raw.nextRate() == stretchRate ? raw.read(out, offset, frames) : -1;
            }

            @Override
            public void close() {
                // The MP3 stream goes on; it is closed with the whole stream.
            }
        }

        @Override
        public void close() throws IOException {
            raw.close();
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // minimp3's frame decoder.

    private static final int MAX_BITRESERVOIR_BYTES = 511;
    private static final int MAX_L3_FRAME_PAYLOAD_BYTES = 2304;
    /**
     * minimp3 refuses a frame whose granules claim more bits than it holds; ffmpeg decodes it with zeros past the end,
     * which also matters for a truncated last frame. The Huffman decoder reads ahead of its position, so the main data
     * buffer keeps this many zero bytes after the real data instead of refusing.
     */
    private static final int MAINDATA_SLACK = 4096;
    private static final int SHORT_BLOCK_TYPE = 2;
    private static final int STOP_BLOCK_TYPE = 3;
    private static final int MODE_MONO = 3;
    private static final int MODE_JOINT_STEREO = 1;
    private static final int BITS_DEQUANTIZER_OUT = -1;
    private static final int MAX_SCF = 255 + BITS_DEQUANTIZER_OUT * 4 - 210;
    private static final int MAX_SCFI = (MAX_SCF + 3) & ~3;
    private static final float PCM_SCALE = 1.f / 32768.f;

    private static final class Bs {
        byte[] buf;
        int start;
        int pos;
        int limit;

        void init(byte[] data, int offset, int bytes) {
            buf = data;
            start = offset;
            pos = 0;
            limit = bytes * 8;
        }

        int get(int n) {
            int s = pos & 7;
            int shl = n + s;
            int p = start + (pos >> 3);
            if ((pos += n) > limit) {
                return 0;
            }
            int next = buf[p++] & 0xFF & (255 >> s);
            int cache = 0;
            while ((shl -= 8) > 0) {
                cache |= next << shl;
                next = buf[p++] & 0xFF;
            }
            return cache | (next >>> -shl);
        }
    }

    private static final class GrInfo {
        int[] sfbtab;
        int part23Length;
        int bigValues;
        int scalefacCompress;
        int globalGain;
        int blockType;
        int mixedBlockFlag;
        int nLongSfb;
        int nShortSfb;
        final int[] tableSelect = new int[3];
        final int[] regionCount = new int[3];
        final int[] subblockGain = new int[3];
        int preflag;
        int scalefacScale;
        int count1Table;
        int scfsi;
    }

    private static final class L12ScaleInfo {
        final float[] scf = new float[3 * 64];
        int totalBands;
        int stereoBands;
        final int[] bitalloc = new int[64];
        final int[] scfcod = new int[64];
    }

    private static final class FrameDecoder {
        private final float[] mdctOverlap = new float[2 * 9 * 32];
        private final float[] qmfState = new float[15 * 2 * 32];
        private int reserv;
        private final byte[] reservBuf = new byte[MAX_BITRESERVOIR_BYTES];

        private final Bs bs = new Bs();
        private final Bs bsFrame = new Bs();
        private final byte[] maindata = new byte[MAX_BITRESERVOIR_BYTES + MAX_L3_FRAME_PAYLOAD_BYTES + MAINDATA_SLACK];
        private final GrInfo[] grInfo = {new GrInfo(), new GrInfo(), new GrInfo(), new GrInfo()};
        private final float[] grbuf = new float[2 * 576];
        private final float[] scf = new float[40];
        private final float[] syn = new float[(18 + 15) * 2 * 32];
        private final int[] istPos = new int[2 * 39];
        private final int[] iscf = new int[40];
        private final int[] scfSize = new int[4];
        private final int[] maxBand = new int[3];
        private final float[] co = new float[9];
        private final float[] si = new float[9];
        private final float[] co3 = new float[3];
        private final float[] si3 = new float[3];
        private final float[] tmp18 = new float[18];
        private final float[] t32 = new float[32];
        private final float[] a4 = new float[4];
        private final float[] b4 = new float[4];
        private final L12ScaleInfo sci = new L12ScaleInfo();
        /** Where the real main data ends, in bits: short of the frame's length only in a truncated last frame. */
        private int dataEnd;

        /**
         * Decodes one frame of {@code frameLen} bytes, of which the first {@code available} are real and the rest zeros,
         * into planar {@code pcm}; 0 when it cannot.
         */
        int decodeFrame(byte[] frame, int frameLen, int available, int hdr, float[][] pcm) {
            int nch = isMono(hdr) ? 1 : 2;
            float[] dl = pcm[0];
            float[] dr = nch == 2 ? pcm[1] : pcm[0];
            if (layerBits(hdr) == 1) {
                return decodeLayer3(frame, frameLen, available, hdr, nch, dl, dr);
            }
            return decodeLayer12(frame, frameLen, hdr, nch, dl, dr);
        }

        private int decodeLayer3(byte[] frame, int frameLen, int available, int hdr, int nch, float[] dl, float[] dr) {
            Bs bsf = bsFrame;
            bsf.init(frame, 4, frameLen - 4);
            if (isCrc(hdr)) {
                bsf.get(16);
            }
            int mainDataBegin = readSideInfo(bsf, grInfo, hdr);
            if (mainDataBegin < 0 || bsf.pos > bsf.limit) {
                // ffmpeg outputs nothing for this frame but keeps its tail as the next frame's reservoir.
                int keep = Math.min(MAX_BITRESERVOIR_BYTES, Math.max(0, frameLen - 4));
                System.arraycopy(frame, frameLen - keep, reservBuf, 0, keep);
                reserv = keep;
                return 0;
            }
            int frameBytes = (bsf.limit - bsf.pos) / 8;
            int bytesHave = Math.min(reserv, mainDataBegin);
            System.arraycopy(reservBuf, Math.max(0, reserv - mainDataBegin), maindata, 0, bytesHave);
            System.arraycopy(frame, 4 + bsf.pos / 8, maindata, bytesHave, frameBytes);
            int valid = bytesHave + frameBytes;
            Arrays.fill(maindata, valid, valid + MAINDATA_SLACK, (byte) 0);
            bs.init(maindata, 0, valid);
            dataEnd = (bytesHave + Math.max(0, Math.min(frameBytes, available - 4 - bsf.pos / 8))) * 8;

            int granules = testMpeg1(hdr) ? 2 : 1;
            int firstDecoded = 0;
            if (reserv < mainDataBegin) {
                // minimp3 outputs nothing here, which would shift every later sample by a frame against ffmpeg. ffmpeg
                // keeps the frame: the granules whose data began before the reservoir it has are silent, and the rest
                // start where the side info's lengths put them.
                int startBits = (reserv - mainDataBegin) * 8;
                while (firstDecoded < granules && startBits < 0) {
                    for (int ch = 0; ch < nch; ch++) {
                        startBits += grInfo[firstDecoded * nch + ch].part23Length;
                    }
                    firstDecoded++;
                }
                bs.pos = Math.max(0, startBits);
            }
            for (int igr = 0; igr < granules; igr++) {
                Arrays.fill(grbuf, 0f);
                decodeGranule(igr * nch, nch, hdr, igr >= firstDecoded);
                synthGranule(18, nch, dl, dr, 576 * igr);
            }
            saveReservoir();
            return 576 * granules;
        }

        /** Forgets the bit reservoir, as after a seek: bytes the container lost came before the next frame. */
        void emptyReservoir() {
            reserv = 0;
        }

        private void saveReservoir() {
            int pos = (bs.pos + 7) >>> 3;
            int remains = bs.limit / 8 - pos;
            if (remains > MAX_BITRESERVOIR_BYTES) {
                pos += remains - MAX_BITRESERVOIR_BYTES;
                remains = MAX_BITRESERVOIR_BYTES;
            }
            if (remains < 0) {
                // The granules claimed more than the frame held (minimp3 refuses such frames). ffmpeg then keeps the
                // frame's last bytes, like after any other bad frame.
                remains = Math.min(MAX_BITRESERVOIR_BYTES, bs.limit / 8);
                pos = bs.limit / 8 - remains;
            }
            if (remains > 0) {
                System.arraycopy(maindata, pos, reservBuf, 0, remains);
            }
            reserv = remains;
        }

        private int decodeLayer12(byte[] frame, int frameLen, int hdr, int nch, float[] dl, float[] dr) {
            Bs bsf = bsFrame;
            bsf.init(frame, 4, frameLen - 4);
            if (isCrc(hdr)) {
                bsf.get(16);
            }
            readScaleInfo(hdr, bsf, sci);
            Arrays.fill(grbuf, 0f);
            int groupSize = isLayer1(hdr) ? 1 : 3;
            int out = 0;
            for (int i = 0, igr = 0; igr < 3; igr++) {
                i += dequantizeGranule(i, bsf, sci, groupSize);
                if (i == 12) {
                    i = 0;
                    applyScf384(sci, igr);
                    synthGranule(12, nch, dl, dr, out);
                    Arrays.fill(grbuf, 0f);
                    out += 384;
                }
                // minimp3 drops a frame whose allocation reads past its end; ffmpeg decodes it, past the end as zeros.
            }
            return out;
        }

        // ----- Layer III side info and scale factors

        private int readSideInfo(Bs bs, GrInfo[] gr, int hdr) {
            int tables;
            int scfsi = 0;
            int mainDataBegin;
            int srIdx = mySampleRate(hdr);
            srIdx -= srIdx != 0 ? 1 : 0;
            int grCount = isMono(hdr) ? 1 : 2;
            boolean mpeg1 = testMpeg1(hdr);
            if (mpeg1) {
                grCount *= 2;
                mainDataBegin = bs.get(9);
                scfsi = bs.get(7 + grCount);
            } else {
                mainDataBegin = bs.get(8 + grCount) >> grCount;
            }
            int g = 0;
            do {
                GrInfo gi = gr[g];
                if (isMono(hdr)) {
                    scfsi <<= 4;
                }
                gi.part23Length = bs.get(12);
                gi.bigValues = bs.get(9);
                if (gi.bigValues > 288) {
                    return -1;
                }
                gi.globalGain = bs.get(8);
                gi.scalefacCompress = bs.get(mpeg1 ? 4 : 9);
                gi.sfbtab = SCF_LONG[srIdx];
                gi.nLongSfb = 22;
                gi.nShortSfb = 0;
                if (bs.get(1) != 0) {
                    gi.blockType = bs.get(2);
                    if (gi.blockType == 0) {
                        return -1;
                    }
                    gi.mixedBlockFlag = bs.get(1);
                    gi.regionCount[0] = 7;
                    gi.regionCount[1] = 255;
                    if (gi.blockType == SHORT_BLOCK_TYPE) {
                        scfsi &= 0x0F0F;
                        if (gi.mixedBlockFlag == 0) {
                            gi.regionCount[0] = 8;
                            gi.sfbtab = SCF_SHORT[srIdx];
                            gi.nLongSfb = 0;
                            gi.nShortSfb = 39;
                        } else {
                            gi.sfbtab = SCF_MIXED[srIdx];
                            gi.nLongSfb = mpeg1 ? 8 : 6;
                            gi.nShortSfb = 30;
                        }
                    }
                    tables = bs.get(10);
                    tables <<= 5;
                    gi.subblockGain[0] = bs.get(3);
                    gi.subblockGain[1] = bs.get(3);
                    gi.subblockGain[2] = bs.get(3);
                } else {
                    gi.blockType = 0;
                    gi.mixedBlockFlag = 0;
                    tables = bs.get(15);
                    gi.regionCount[0] = bs.get(4);
                    gi.regionCount[1] = bs.get(3);
                    gi.regionCount[2] = 255;
                }
                gi.tableSelect[0] = tables >> 10;
                gi.tableSelect[1] = (tables >> 5) & 31;
                gi.tableSelect[2] = tables & 31;
                gi.preflag = mpeg1 ? bs.get(1) : (gi.scalefacCompress >= 500 ? 1 : 0);
                gi.scalefacScale = bs.get(1);
                gi.count1Table = bs.get(1);
                gi.scfsi = (scfsi >> 12) & 15;
                scfsi <<= 4;
                g++;
            } while (--grCount != 0);
            if (mpeg1) {
                // minimp3 hands the first granule the private bits as scfsi, harmless only while encoders write them as
                // zero. The first granule never reuses scale factors (ffmpeg and the standard set it to 0).
                gr[0].scfsi = 0;
                if (!isMono(hdr)) {
                    gr[1].scfsi = 0;
                }
            }
            // minimp3 also refuses side info whose lengths exceed the frame; ffmpeg does not (see MAINDATA_SLACK).
            return mainDataBegin;
        }

        private void readScalefactors(int[] partition, int partOff, int istOff, Bs bitbuf, int scfsi) {
            int s = 0;
            int ip = istOff;
            for (int i = 0; i < 4 && partition[partOff + i] != 0; i++, scfsi *= 2) {
                int cnt = partition[partOff + i];
                if ((scfsi & 8) != 0) {
                    System.arraycopy(istPos, ip, iscf, s, cnt);
                } else {
                    int bits = scfSize[i];
                    if (bits == 0) {
                        Arrays.fill(iscf, s, s + cnt, 0);
                        Arrays.fill(istPos, ip, ip + cnt, 0);
                    } else {
                        int maxScf = scfsi < 0 ? (1 << bits) - 1 : -1;
                        for (int k = 0; k < cnt; k++) {
                            int v = bitbuf.get(bits);
                            istPos[ip + k] = v == maxScf ? 255 : v;
                            iscf[s + k] = v;
                        }
                    }
                }
                ip += cnt;
                s += cnt;
            }
            iscf[s] = 0;
            iscf[s + 1] = 0;
            iscf[s + 2] = 0;
        }

        private static float ldexpQ2(float y, int expQ2) {
            int e;
            do {
                e = Math.min(30 * 4, expQ2);
                y *= EXPFRAC[e & 3] * (1 << 30 >> (e >> 2));
            } while ((expQ2 -= e) > 0);
            return y;
        }

        private void decodeScalefactors(int hdr, int istOff, Bs bitbuf, GrInfo gr, int ch) {
            int[] partition = SCF_PARTITIONS[(gr.nShortSfb != 0 ? 1 : 0) + (gr.nLongSfb == 0 ? 1 : 0)];
            int partOff = 0;
            int scfShift = gr.scalefacScale + 1;
            int scfsi = gr.scfsi;
            if (testMpeg1(hdr)) {
                int part = SCFC_DECODE[gr.scalefacCompress];
                scfSize[1] = scfSize[0] = part >> 2;
                scfSize[3] = scfSize[2] = part & 3;
            } else {
                int k;
                int modprod;
                int ist = testIStereo(hdr) && ch != 0 ? 1 : 0;
                int sfc = gr.scalefacCompress >> ist;
                for (k = ist * 3 * 4; sfc >= 0; sfc -= modprod, k += 4) {
                    modprod = 1;
                    for (int i = 3; i >= 0; i--) {
                        scfSize[i] = sfc / modprod % MOD[k + i];
                        modprod *= MOD[k + i];
                    }
                }
                partOff += k;
                scfsi = -16;
            }
            readScalefactors(partition, partOff, istOff, bitbuf, scfsi);

            if (gr.nShortSfb != 0) {
                int sh = 3 - scfShift;
                for (int i = 0; i < gr.nShortSfb; i += 3) {
                    iscf[gr.nLongSfb + i] = (iscf[gr.nLongSfb + i] + (gr.subblockGain[0] << sh)) & 0xFF;
                    iscf[gr.nLongSfb + i + 1] = (iscf[gr.nLongSfb + i + 1] + (gr.subblockGain[1] << sh)) & 0xFF;
                    iscf[gr.nLongSfb + i + 2] = (iscf[gr.nLongSfb + i + 2] + (gr.subblockGain[2] << sh)) & 0xFF;
                }
            } else if (gr.preflag != 0) {
                for (int i = 0; i < 10; i++) {
                    iscf[11 + i] = (iscf[11 + i] + PREAMP[i]) & 0xFF;
                }
            }

            int gainExp = gr.globalGain + BITS_DEQUANTIZER_OUT * 4 - 210 - (isMsStereo(hdr) ? 2 : 0);
            float gain = ldexpQ2(1 << (MAX_SCFI / 4), MAX_SCFI - gainExp);
            for (int i = 0; i < gr.nLongSfb + gr.nShortSfb; i++) {
                scf[i] = ldexpQ2(gain, iscf[i] << scfShift);
            }
        }

        // ----- Layer III Huffman decoding

        private static float pow43(int x) {
            float frac;
            int sign;
            int mult = 256;
            if (x < 129) {
                return POW43[16 + x];
            }
            if (x < 1024) {
                mult = 16;
                x <<= 3;
            }
            sign = 2 * x & 64;
            frac = (float) ((x & 63) - sign) / ((x & ~63) + sign);
            return POW43[16 + ((x + sign) >> 6)] * (1.f + frac * ((4.f / 3) + frac * (2.f / 9))) * mult;
        }

        /**
         * L3_huffman, stopping where ffmpeg stops rather than where minimp3 does. The two only differ on a granule
         * whose length in the side info is wrong, which ffmpeg says some encoders write: ffmpeg stops reading pairs at
         * the granule's end (or the data's) and leaves the rest of the spectrum silent where minimp3 reads on into the
         * next granule, and it drops a quadruple whose sign bits ran past the end, which minimp3 keeps.
         */
        private void huffman(int dstOff, Bs bits, GrInfo gr, int layer3grLimit, int dataEnd) {
            final float[] dst = grbuf;
            final byte[] buf = bits.buf;
            final int base = bits.start;
            int d = dstOff;
            int sc = 0;
            float one = 0.0f;
            int ireg = 0;
            int bigValCnt = gr.bigValues;
            final int[] sfb = gr.sfbtab;
            int sf = 0;
            int next = base + bits.pos / 8;
            int cache = (((buf[next] & 0xFF) * 256 + (buf[next + 1] & 0xFF)) * 256 + (buf[next + 2] & 0xFF)) * 256
                    + (buf[next + 3] & 0xFF);
            cache <<= bits.pos & 7;
            int sh = (bits.pos & 7) - 8;
            next += 4;
            int pairs;
            int np;
            final int endPos = Math.min(layer3grLimit, dataEnd);

            bigValues:
            while (bigValCnt > 0) {
                int tabNum = gr.tableSelect[ireg];
                int sfbCnt = gr.regionCount[ireg++];
                int cb = TABINDEX[tabNum];
                int linbits = LINBITS[tabNum];
                do {
                    np = sfb[sf++] / 2;
                    pairs = Math.min(bigValCnt, np);
                    one = scf[sc++];
                    if (pairs <= 0) {
                        // Only the table's end marker gives 0, which side info within its limits never reaches.
                        sf--;
                        bigValCnt = 0;
                        break bigValues;
                    }
                    do {
                        if ((next - base) * 8 - 24 + sh >= endPos) {
                            bits.pos = layer3grLimit;
                            return;
                        }
                        int w = 5;
                        int leaf = TABS[cb + (cache >>> (32 - w))];
                        while (leaf < 0) {
                            cache <<= w;
                            sh += w;
                            w = leaf & 7;
                            leaf = TABS[cb + (cache >>> (32 - w)) - (leaf >> 3)];
                        }
                        cache <<= leaf >> 8;
                        sh += leaf >> 8;
                        for (int j = 0; j < 2; j++, d++, leaf >>= 4) {
                            int lsb = leaf & 0x0F;
                            if (lsb == 15 && linbits != 0) {
                                lsb += cache >>> (32 - linbits);
                                cache <<= linbits;
                                sh += linbits;
                                while (sh >= 0) {
                                    cache |= (buf[next++] & 0xFF) << sh;
                                    sh -= 8;
                                }
                                dst[d] = one * pow43(lsb) * (cache < 0 ? -1 : 1);
                            } else {
                                dst[d] = POW43[16 + lsb - 16 * (cache >>> 31)] * one;
                            }
                            if (lsb != 0) {
                                cache <<= 1;
                                sh += 1;
                            }
                        }
                        while (sh >= 0) {
                            cache |= (buf[next++] & 0xFF) << sh;
                            sh -= 8;
                        }
                    } while (--pairs != 0);
                } while ((bigValCnt -= np) > 0 && --sfbCnt >= 0);
            }

            final int[] count1 = gr.count1Table != 0 ? TAB33 : TAB32;
            boolean quad = false;
            for (np = 1 - bigValCnt; ; d += 4) {
                int pos = (next - base) * 8 - 24 + sh;
                if (pos >= endPos) {
                    if (pos > layer3grLimit && quad) {
                        dst[d - 4] = 0;
                        dst[d - 3] = 0;
                        dst[d - 2] = 0;
                        dst[d - 1] = 0;
                    }
                    break;
                }
                quad = true;
                int leaf = count1[cache >>> 28];
                if ((leaf & 8) == 0) {
                    leaf = count1[(leaf >> 3) + ((cache << 4) >>> (32 - (leaf & 3)))];
                }
                cache <<= leaf & 7;
                sh += leaf & 7;
                if (--np == 0) {
                    np = sfb[sf++] / 2;
                    if (np == 0) {
                        break;
                    }
                    one = scf[sc++];
                }
                if ((leaf & 128) != 0) {
                    dst[d] = cache < 0 ? -one : one;
                    cache <<= 1;
                    sh += 1;
                }
                if ((leaf & 64) != 0) {
                    dst[d + 1] = cache < 0 ? -one : one;
                    cache <<= 1;
                    sh += 1;
                }
                if (--np == 0) {
                    np = sfb[sf++] / 2;
                    if (np == 0) {
                        break;
                    }
                    one = scf[sc++];
                }
                if ((leaf & 32) != 0) {
                    dst[d + 2] = cache < 0 ? -one : one;
                    cache <<= 1;
                    sh += 1;
                }
                if ((leaf & 16) != 0) {
                    dst[d + 3] = cache < 0 ? -one : one;
                    cache <<= 1;
                    sh += 1;
                }
                while (sh >= 0) {
                    cache |= (buf[next++] & 0xFF) << sh;
                    sh -= 8;
                }
            }
            bits.pos = layer3grLimit;
        }

        // ----- Layer III stereo

        private static void midsideStereo(float[] g, int left, int n) {
            int right = left + 576;
            for (int i = 0; i < n; i++) {
                float a = g[left + i];
                float b = g[right + i];
                g[left + i] = a + b;
                g[right + i] = a - b;
            }
        }

        private static void intensityStereoBand(float[] g, int left, int n, float kl, float kr) {
            for (int i = 0; i < n; i++) {
                g[left + i + 576] = g[left + i] * kr;
                g[left + i] = g[left + i] * kl;
            }
        }

        private static void stereoTopBand(float[] g, int right, int[] sfb, int nbands, int[] maxBand) {
            maxBand[0] = maxBand[1] = maxBand[2] = -1;
            for (int i = 0; i < nbands; i++) {
                for (int k = 0; k < sfb[i]; k += 2) {
                    if (g[right + k] != 0 || g[right + k + 1] != 0) {
                        maxBand[i % 3] = i;
                        break;
                    }
                }
                right += sfb[i];
            }
        }

        private void stereoProcess(int left, int istOff, int[] sfb, int hdr, int mpeg2Sh) {
            int maxPos = testMpeg1(hdr) ? 7 : 64;
            for (int i = 0; sfb[i] != 0; i++) {
                int ipos = istPos[istOff + i];
                if (i > maxBand[i % 3] && ipos < maxPos) {
                    float kl;
                    float kr;
                    float s = testMsStereo(hdr) ? 1.41421356f : 1;
                    if (testMpeg1(hdr)) {
                        kl = PAN[2 * ipos];
                        kr = PAN[2 * ipos + 1];
                    } else {
                        kl = 1;
                        kr = ldexpQ2(1, (ipos + 1) >> 1 << mpeg2Sh);
                        if ((ipos & 1) != 0) {
                            kl = kr;
                            kr = 1;
                        }
                    }
                    intensityStereoBand(grbuf, left, sfb[i], kl * s, kr * s);
                } else if (testMsStereo(hdr)) {
                    midsideStereo(grbuf, left, sfb[i]);
                }
                left += sfb[i];
            }
        }

        private void intensityStereo(int istOff, GrInfo gr, GrInfo grRight, int hdr) {
            int nSfb = gr.nLongSfb + gr.nShortSfb;
            int maxBlocks = gr.nShortSfb != 0 ? 3 : 1;
            stereoTopBand(grbuf, 576, gr.sfbtab, nSfb, maxBand);
            if (gr.nLongSfb != 0) {
                maxBand[0] = maxBand[1] = maxBand[2] = Math.max(Math.max(maxBand[0], maxBand[1]), maxBand[2]);
            }
            for (int i = 0; i < maxBlocks; i++) {
                int defaultPos = testMpeg1(hdr) ? 3 : 0;
                int itop = nSfb - maxBlocks + i;
                int prev = itop - maxBlocks;
                istPos[istOff + itop] = maxBand[i] >= prev ? defaultPos : istPos[istOff + prev];
            }
            stereoProcess(0, istOff, gr.sfbtab, hdr, grRight.scalefacCompress & 1);
        }

        // ----- Layer III reordering, antialiasing and IMDCT

        private void reorder(int off, int[] sfb, int sfbOff) {
            float[] g = grbuf;
            float[] scratch = syn;
            int src = off;
            int dst = 0;
            int len;
            for (; (len = sfb[sfbOff]) != 0; sfbOff += 3, src += 2 * len) {
                for (int i = 0; i < len; i++, src++) {
                    scratch[dst++] = g[src];
                    scratch[dst++] = g[src + len];
                    scratch[dst++] = g[src + 2 * len];
                }
            }
            System.arraycopy(scratch, 0, g, off, dst);
        }

        private static void antialias(float[] g, int off, int nbands) {
            for (; nbands > 0; nbands--, off += 18) {
                for (int i = 0; i < 8; i++) {
                    float u = g[off + 18 + i];
                    float d = g[off + 17 - i];
                    g[off + 18 + i] = u * AA0[i] - d * AA1[i];
                    g[off + 17 - i] = u * AA1[i] + d * AA0[i];
                }
            }
        }

        private static void dct3x9(float[] y) {
            float s0;
            float s1;
            float s2;
            float s3;
            float s4;
            float s5;
            float s6;
            float s7;
            float s8;
            float t0;
            float t2;
            float t4;

            s0 = y[0];
            s2 = y[2];
            s4 = y[4];
            s6 = y[6];
            s8 = y[8];
            t0 = s0 + s6 * 0.5f;
            s0 -= s6;
            t4 = (s4 + s2) * 0.93969262f;
            t2 = (s8 + s2) * 0.76604444f;
            s6 = (s4 - s8) * 0.17364818f;
            s4 += s8 - s2;

            s2 = s0 - s4 * 0.5f;
            y[4] = s4 + s0;
            s8 = t0 - t2 + s6;
            s0 = t0 - t4 + t2;
            s4 = t0 + t4 - s6;

            s1 = y[1];
            s3 = y[3];
            s5 = y[5];
            s7 = y[7];

            s3 *= 0.86602540f;
            t0 = (s5 + s1) * 0.98480775f;
            t4 = (s5 - s7) * 0.34202014f;
            t2 = (s1 + s7) * 0.64278761f;
            s1 = (s1 - s5 - s7) * 0.86602540f;

            s5 = t0 - s3 - t2;
            s7 = t4 - s3 - t0;
            s3 = t4 + s3 - t2;

            y[0] = s4 - s7;
            y[1] = s2 + s1;
            y[2] = s0 - s3;
            y[3] = s8 + s5;
            y[5] = s8 - s5;
            y[6] = s0 + s3;
            y[7] = s2 - s1;
            y[8] = s4 + s7;
        }

        private void imdct36(float[] g, int off, float[] overlap, int ovOff, float[] window, int nbands) {
            for (int j = 0; j < nbands; j++, off += 18, ovOff += 9) {
                co[0] = -g[off];
                si[0] = g[off + 17];
                for (int i = 0; i < 4; i++) {
                    si[8 - 2 * i] = g[off + 4 * i + 1] - g[off + 4 * i + 2];
                    co[1 + 2 * i] = g[off + 4 * i + 1] + g[off + 4 * i + 2];
                    si[7 - 2 * i] = g[off + 4 * i + 4] - g[off + 4 * i + 3];
                    co[2 + 2 * i] = -(g[off + 4 * i + 3] + g[off + 4 * i + 4]);
                }
                dct3x9(co);
                dct3x9(si);

                si[1] = -si[1];
                si[3] = -si[3];
                si[5] = -si[5];
                si[7] = -si[7];

                for (int i = 0; i < 9; i++) {
                    float ovl = overlap[ovOff + i];
                    float sum = co[i] * TWID9[9 + i] + si[i] * TWID9[i];
                    overlap[ovOff + i] = co[i] * TWID9[i] - si[i] * TWID9[9 + i];
                    g[off + i] = ovl * window[i] - sum * window[9 + i];
                    g[off + 17 - i] = ovl * window[9 + i] + sum * window[i];
                }
            }
        }

        private static void idct3(float x0, float x1, float x2, float[] dst) {
            float m1 = x1 * 0.86602540f;
            float a1 = x0 - x2 * 0.5f;
            dst[1] = x0 + x2;
            dst[0] = a1 + m1;
            dst[2] = a1 - m1;
        }

        private void imdct12(float[] x, int xo, float[] dst, int dstOff, float[] overlap, int ovOff) {
            idct3(-x[xo], x[xo + 6] + x[xo + 3], x[xo + 12] + x[xo + 9], co3);
            idct3(x[xo + 15], x[xo + 12] - x[xo + 9], x[xo + 6] - x[xo + 3], si3);
            si3[1] = -si3[1];
            for (int i = 0; i < 3; i++) {
                float ovl = overlap[ovOff + i];
                float sum = co3[i] * TWID3[3 + i] + si3[i] * TWID3[i];
                overlap[ovOff + i] = co3[i] * TWID3[i] - si3[i] * TWID3[3 + i];
                dst[dstOff + i] = ovl * TWID3[2 - i] - sum * TWID3[5 - i];
                dst[dstOff + 5 - i] = ovl * TWID3[5 - i] + sum * TWID3[2 - i];
            }
        }

        private void imdctShort(float[] g, int off, float[] overlap, int ovOff, int nbands) {
            for (; nbands > 0; nbands--, ovOff += 9, off += 18) {
                System.arraycopy(g, off, tmp18, 0, 18);
                System.arraycopy(overlap, ovOff, g, off, 6);
                imdct12(tmp18, 0, g, off + 6, overlap, ovOff + 6);
                imdct12(tmp18, 1, g, off + 12, overlap, ovOff + 6);
                imdct12(tmp18, 2, overlap, ovOff, overlap, ovOff + 6);
            }
        }

        private static void changeSign(float[] g, int off) {
            int p = off + 18;
            for (int b = 0; b < 32; b += 2, p += 36) {
                for (int i = 1; i < 18; i += 2) {
                    g[p + i] = -g[p + i];
                }
            }
        }

        private void imdctGranule(int off, int ovOff, int blockType, int nLongBands) {
            if (nLongBands != 0) {
                imdct36(grbuf, off, mdctOverlap, ovOff, MDCT_WINDOW_0, nLongBands);
                off += 18 * nLongBands;
                ovOff += 9 * nLongBands;
            }
            if (blockType == SHORT_BLOCK_TYPE) {
                imdctShort(grbuf, off, mdctOverlap, ovOff, 32 - nLongBands);
            } else {
                imdct36(grbuf, off, mdctOverlap, ovOff, blockType == STOP_BLOCK_TYPE ? MDCT_WINDOW_1 : MDCT_WINDOW_0,
                        32 - nLongBands);
            }
        }

        /** L3_decode for one granule; {@code decodeData} false leaves its spectrum silent (the reservoir was missing). */
        private void decodeGranule(int g0, int nch, int hdr, boolean decodeData) {
            if (decodeData) {
                for (int ch = 0; ch < nch; ch++) {
                    GrInfo gi = grInfo[g0 + ch];
                    int layer3grLimit = bs.pos + gi.part23Length;
                    decodeScalefactors(hdr, ch * 39, bs, gi, ch);
                    huffman(576 * ch, bs, gi, layer3grLimit, dataEnd);
                }
            }
            // minimp3 applies intensity stereo whenever the bit is set; it only means anything in joint stereo, and
            // ffmpeg only applies stereo processing there, so a stray bit in a mono or plain stereo frame is ignored.
            if (nch == 2 && stereoMode(hdr) == MODE_JOINT_STEREO) {
                if (testIStereo(hdr)) {
                    intensityStereo(39, grInfo[g0], grInfo[g0 + 1], hdr);
                } else if (isMsStereo(hdr)) {
                    midsideStereo(grbuf, 0, 576);
                }
            }
            for (int ch = 0; ch < nch; ch++) {
                GrInfo gi = grInfo[g0 + ch];
                int aaBands = 31;
                int nLongBands = (gi.mixedBlockFlag != 0 ? 2 : 0) << (mySampleRate(hdr) == 2 ? 1 : 0);
                if (gi.nShortSfb != 0) {
                    aaBands = nLongBands - 1;
                    reorder(576 * ch + nLongBands * 18, gi.sfbtab, gi.nLongSfb);
                }
                antialias(grbuf, 576 * ch, aaBands);
                imdctGranule(576 * ch, 288 * ch, gi.blockType, nLongBands);
                changeSign(grbuf, 576 * ch);
            }
        }

        // ----- Synthesis filterbank

        private void dctII(int base, int n) {
            final float[] g = grbuf;
            final float[] t = t32;
            for (int k = 0; k < n; k++) {
                int y = base + k;
                for (int i = 0; i < 8; i++) {
                    float x0 = g[y + i * 18];
                    float x1 = g[y + (15 - i) * 18];
                    float x2 = g[y + (16 + i) * 18];
                    float x3 = g[y + (31 - i) * 18];
                    float t0 = x0 + x3;
                    float t1 = x1 + x2;
                    float t2 = (x1 - x2) * SEC[3 * i];
                    float t3 = (x0 - x3) * SEC[3 * i + 1];
                    t[i] = t0 + t1;
                    t[8 + i] = (t0 - t1) * SEC[3 * i + 2];
                    t[16 + i] = t3 + t2;
                    t[24 + i] = (t3 - t2) * SEC[3 * i + 2];
                }
                for (int i = 0, x = 0; i < 4; i++, x += 8) {
                    float x0 = t[x];
                    float x1 = t[x + 1];
                    float x2 = t[x + 2];
                    float x3 = t[x + 3];
                    float x4 = t[x + 4];
                    float x5 = t[x + 5];
                    float x6 = t[x + 6];
                    float x7 = t[x + 7];
                    float xt;
                    xt = x0 - x7;
                    x0 += x7;
                    x7 = x1 - x6;
                    x1 += x6;
                    x6 = x2 - x5;
                    x2 += x5;
                    x5 = x3 - x4;
                    x3 += x4;
                    x4 = x0 - x3;
                    x0 += x3;
                    x3 = x1 - x2;
                    x1 += x2;
                    t[x] = x0 + x1;
                    t[x + 4] = (x0 - x1) * 0.70710677f;
                    x5 = x5 + x6;
                    x6 = (x6 + x7) * 0.70710677f;
                    x7 = x7 + xt;
                    x3 = (x3 + x4) * 0.70710677f;
                    x5 -= x7 * 0.198912367f; // rotate by PI/8
                    x7 += x5 * 0.382683432f;
                    x5 -= x7 * 0.198912367f;
                    x0 = xt - x6;
                    xt += x6;
                    t[x + 1] = (xt + x7) * 0.50979561f;
                    t[x + 2] = (x4 + x3) * 0.54119611f;
                    t[x + 3] = (x0 - x5) * 0.60134488f;
                    t[x + 5] = (x0 + x5) * 0.89997619f;
                    t[x + 6] = (x4 - x3) * 1.30656302f;
                    t[x + 7] = (xt - x7) * 2.56291556f;
                }
                int yy = y;
                for (int i = 0; i < 7; i++, yy += 4 * 18) {
                    g[yy] = t[i];
                    g[yy + 18] = t[16 + i] + t[24 + i] + t[24 + i + 1];
                    g[yy + 36] = t[8 + i] + t[8 + i + 1];
                    g[yy + 54] = t[16 + i + 1] + t[24 + i] + t[24 + i + 1];
                }
                g[yy] = t[7];
                g[yy + 18] = t[16 + 7] + t[24 + 7];
                g[yy + 36] = t[8 + 7];
                g[yy + 54] = t[24 + 7];
            }
        }

        private static void synthPair(float[] pcm, int o, float[] z, int zo) {
            float a;
            a = (z[zo + 14 * 64] - z[zo]) * 29;
            a += (z[zo + 64] + z[zo + 13 * 64]) * 213;
            a += (z[zo + 12 * 64] - z[zo + 2 * 64]) * 459;
            a += (z[zo + 3 * 64] + z[zo + 11 * 64]) * 2037;
            a += (z[zo + 10 * 64] - z[zo + 4 * 64]) * 5153;
            a += (z[zo + 5 * 64] + z[zo + 9 * 64]) * 6574;
            a += (z[zo + 8 * 64] - z[zo + 6 * 64]) * 37489;
            a += z[zo + 7 * 64] * 75038;
            pcm[o] = a * PCM_SCALE;

            zo += 2;
            a = z[zo + 14 * 64] * 104;
            a += z[zo + 12 * 64] * 1567;
            a += z[zo + 10 * 64] * 9727;
            a += z[zo + 8 * 64] * 64019;
            a += z[zo + 6 * 64] * -9975;
            a += z[zo + 4 * 64] * -45;
            a += z[zo + 2 * 64] * 146;
            a += z[zo] * -5;
            pcm[o + 16] = a * PCM_SCALE;
        }

        /** mp3d_synth: 64 samples of both channels; with one channel {@code dl == dr} and the left lanes are written last. */
        private void synth(int xl, float[] dl, float[] dr, int o, int nch, int lb) {
            final float[] g = grbuf;
            final float[] lins = syn;
            final float[] a = a4;
            final float[] b = b4;
            int xr = xl + 576 * (nch - 1);
            int zlin = lb + 15 * 64;
            int w = 0;

            lins[zlin + 4 * 15] = g[xl + 18 * 16];
            lins[zlin + 4 * 15 + 1] = g[xr + 18 * 16];
            lins[zlin + 4 * 15 + 2] = g[xl];
            lins[zlin + 4 * 15 + 3] = g[xr];

            lins[zlin + 4 * 31] = g[xl + 1 + 18 * 16];
            lins[zlin + 4 * 31 + 1] = g[xr + 1 + 18 * 16];
            lins[zlin + 4 * 31 + 2] = g[xl + 1];
            lins[zlin + 4 * 31 + 3] = g[xr + 1];

            synthPair(dr, o, lins, lb + 4 * 15 + 1);
            synthPair(dr, o + 32, lins, lb + 4 * 15 + 64 + 1);
            synthPair(dl, o, lins, lb + 4 * 15);
            synthPair(dl, o + 32, lins, lb + 4 * 15 + 64);

            for (int i = 14; i >= 0; i--) {
                lins[zlin + 4 * i] = g[xl + 18 * (31 - i)];
                lins[zlin + 4 * i + 1] = g[xr + 18 * (31 - i)];
                lins[zlin + 4 * i + 2] = g[xl + 1 + 18 * (31 - i)];
                lins[zlin + 4 * i + 3] = g[xr + 1 + 18 * (31 - i)];
                lins[zlin + 4 * (i + 16)] = g[xl + 1 + 18 * (1 + i)];
                lins[zlin + 4 * (i + 16) + 1] = g[xr + 1 + 18 * (1 + i)];
                lins[zlin + 4 * (i - 16) + 2] = g[xl + 18 * (1 + i)];
                lins[zlin + 4 * (i - 16) + 3] = g[xr + 18 * (1 + i)];

                // S0(0) S2(1) S1(2) S2(3) S1(4) S2(5) S1(6) S2(7)
                for (int k = 0; k < 8; k++) {
                    float w0 = WIN[w++];
                    float w1 = WIN[w++];
                    int vz = zlin + 4 * i - k * 64;
                    int vy = zlin + 4 * i - (15 - k) * 64;
                    if (k == 0) {
                        for (int j = 0; j < 4; j++) {
                            b[j] = lins[vz + j] * w1 + lins[vy + j] * w0;
                            a[j] = lins[vz + j] * w0 - lins[vy + j] * w1;
                        }
                    } else if ((k & 1) != 0) {
                        for (int j = 0; j < 4; j++) {
                            b[j] += lins[vz + j] * w1 + lins[vy + j] * w0;
                            a[j] += lins[vy + j] * w1 - lins[vz + j] * w0;
                        }
                    } else {
                        for (int j = 0; j < 4; j++) {
                            b[j] += lins[vz + j] * w1 + lins[vy + j] * w0;
                            a[j] += lins[vz + j] * w0 - lins[vy + j] * w1;
                        }
                    }
                }

                dr[o + 15 - i] = a[1] * PCM_SCALE;
                dr[o + 17 + i] = b[1] * PCM_SCALE;
                dl[o + 15 - i] = a[0] * PCM_SCALE;
                dl[o + 17 + i] = b[0] * PCM_SCALE;
                dr[o + 47 - i] = a[3] * PCM_SCALE;
                dr[o + 49 + i] = b[3] * PCM_SCALE;
                dl[o + 47 - i] = a[2] * PCM_SCALE;
                dl[o + 49 + i] = b[2] * PCM_SCALE;
            }
        }

        private void synthGranule(int nbands, int nch, float[] dl, float[] dr, int base) {
            for (int i = 0; i < nch; i++) {
                dctII(576 * i, nbands);
            }
            System.arraycopy(qmfState, 0, syn, 0, 15 * 64);
            for (int i = 0; i < nbands; i += 2) {
                synth(i, dl, dr, base + 32 * i, nch, i * 64);
            }
            if (nch == 1) {
                for (int i = 0; i < 15 * 64; i += 2) {
                    qmfState[i] = syn[nbands * 64 + i];
                }
            } else {
                System.arraycopy(syn, nbands * 64, qmfState, 0, 15 * 64);
            }
        }

        // ----- Layer I and II

        private static int[][] subbandAllocTable(int hdr, L12ScaleInfo sci) {
            int mode = stereoMode(hdr);
            int nbands;
            int stereoBands = mode == MODE_MONO ? 0 : mode == MODE_JOINT_STEREO ? (stereoModeExt(hdr) << 2) + 4 : 32;
            int[][] alloc;
            if (isLayer1(hdr)) {
                alloc = ALLOC_L1;
                nbands = 32;
            } else if (!testMpeg1(hdr)) {
                alloc = ALLOC_L2M2;
                nbands = 30;
            } else {
                int srIdx = sampleRateIndex(hdr);
                int kbps = bitrateKbps(hdr) >> (mode != MODE_MONO ? 1 : 0);
                if (kbps == 0) {
                    kbps = 192;
                }
                alloc = ALLOC_L2M1;
                nbands = 27;
                if (kbps < 56) {
                    alloc = ALLOC_L2M1_LOWRATE;
                    nbands = srIdx == 2 ? 12 : 8;
                } else if (kbps >= 96 && srIdx != 1) {
                    nbands = 30;
                }
            }
            sci.totalBands = nbands;
            sci.stereoBands = Math.min(stereoBands, nbands);
            return alloc;
        }

        private static void readScalefactorsL12(Bs bits, int[] pba, int[] scfcod, int bands, float[] scf) {
            int s = 0;
            for (int i = 0; i < bands; i++) {
                float v = 0;
                int ba = pba[i];
                int mask = ba != 0 ? 4 + ((19 >> scfcod[i]) & 3) : 0;
                for (int m = 4; m != 0; m >>= 1) {
                    if ((mask & m) != 0) {
                        int b = bits.get(6);
                        v = DEQ_L12[ba * 3 - 6 + b % 3] * (1 << 21 >> b / 3);
                    }
                    scf[s++] = v;
                }
            }
        }

        private static void readScaleInfo(int hdr, Bs bits, L12ScaleInfo sci) {
            int[][] alloc = subbandAllocTable(hdr, sci);
            int ai = 0;
            int k = 0;
            int baBits = 0;
            int baCode = 0;
            for (int i = 0; i < sci.totalBands; i++) {
                int ba;
                if (i == k) {
                    k += alloc[ai][2];
                    baBits = alloc[ai][1];
                    baCode = alloc[ai][0];
                    ai++;
                }
                ba = BITALLOC_CODE_TAB[baCode + bits.get(baBits)];
                sci.bitalloc[2 * i] = ba;
                if (i < sci.stereoBands) {
                    ba = BITALLOC_CODE_TAB[baCode + bits.get(baBits)];
                }
                sci.bitalloc[2 * i + 1] = sci.stereoBands != 0 ? ba : 0;
            }
            for (int i = 0; i < 2 * sci.totalBands; i++) {
                sci.scfcod[i] = sci.bitalloc[i] != 0 ? (isLayer1(hdr) ? 2 : bits.get(2)) : 6;
            }
            readScalefactorsL12(bits, sci.bitalloc, sci.scfcod, sci.totalBands * 2, sci.scf);
            for (int i = sci.stereoBands; i < sci.totalBands; i++) {
                sci.bitalloc[2 * i + 1] = 0;
            }
        }

        private int dequantizeGranule(int off, Bs bits, L12ScaleInfo sci, int groupSize) {
            int choff = 576;
            for (int j = 0; j < 4; j++) {
                int dst = off + groupSize * j;
                for (int i = 0; i < 2 * sci.totalBands; i++) {
                    int ba = sci.bitalloc[i];
                    if (ba != 0) {
                        if (ba < 17) {
                            int half = (1 << (ba - 1)) - 1;
                            for (int k = 0; k < groupSize; k++) {
                                grbuf[dst + k] = (float) (bits.get(ba) - half);
                            }
                        } else {
                            int mod = (2 << (ba - 17)) + 1;
                            int code = bits.get(mod + 2 - (mod >> 3));
                            for (int k = 0; k < groupSize; k++, code /= mod) {
                                grbuf[dst + k] = (float) (code % mod - mod / 2);
                            }
                        }
                    }
                    dst += choff;
                    choff = 18 - choff;
                }
            }
            return groupSize * 4;
        }

        private void applyScf384(L12ScaleInfo sci, int scfOff) {
            int st = sci.stereoBands * 18;
            System.arraycopy(grbuf, st, grbuf, 576 + st, (sci.totalBands - sci.stereoBands) * 18);
            for (int i = 0, d = 0, s = scfOff; i < sci.totalBands; i++, d += 18, s += 6) {
                for (int k = 0; k < 12; k++) {
                    grbuf[d + k] *= sci.scf[s];
                    grbuf[d + k + 576] *= sci.scf[s + 3];
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // minimp3's tables, unchanged.

    private static final int[][] SCF_LONG = {
            {6, 6, 6, 6, 6, 6, 8, 10, 12, 14, 16, 20, 24, 28, 32, 38, 46, 52, 60, 68, 58, 54, 0},
            {12, 12, 12, 12, 12, 12, 16, 20, 24, 28, 32, 40, 48, 56, 64, 76, 90, 2, 2, 2, 2, 2, 0},
            {6, 6, 6, 6, 6, 6, 8, 10, 12, 14, 16, 20, 24, 28, 32, 38, 46, 52, 60, 68, 58, 54, 0},
            {6, 6, 6, 6, 6, 6, 8, 10, 12, 14, 16, 18, 22, 26, 32, 38, 46, 54, 62, 70, 76, 36, 0},
            {6, 6, 6, 6, 6, 6, 8, 10, 12, 14, 16, 20, 24, 28, 32, 38, 46, 52, 60, 68, 58, 54, 0},
            {4, 4, 4, 4, 4, 4, 6, 6, 8, 8, 10, 12, 16, 20, 24, 28, 34, 42, 50, 54, 76, 158, 0},
            {4, 4, 4, 4, 4, 4, 6, 6, 6, 8, 10, 12, 16, 18, 22, 28, 34, 40, 46, 54, 54, 192, 0},
            {4, 4, 4, 4, 4, 4, 6, 6, 8, 10, 12, 16, 20, 24, 30, 38, 46, 56, 68, 84, 102, 26, 0},
    };

    private static final int[][] SCF_SHORT = {
            {4, 4, 4, 4, 4, 4, 4, 4, 4, 6, 6, 6, 8, 8, 8, 10, 10, 10, 12, 12, 12, 14, 14, 14, 18, 18, 18, 24, 24, 24, 30, 30, 30, 40, 40, 40, 18, 18, 18, 0},
            {8, 8, 8, 8, 8, 8, 8, 8, 8, 12, 12, 12, 16, 16, 16, 20, 20, 20, 24, 24, 24, 28, 28, 28, 36, 36, 36, 2, 2, 2, 2, 2, 2, 2, 2, 2, 26, 26, 26, 0},
            {4, 4, 4, 4, 4, 4, 4, 4, 4, 6, 6, 6, 6, 6, 6, 8, 8, 8, 10, 10, 10, 14, 14, 14, 18, 18, 18, 26, 26, 26, 32, 32, 32, 42, 42, 42, 18, 18, 18, 0},
            {4, 4, 4, 4, 4, 4, 4, 4, 4, 6, 6, 6, 8, 8, 8, 10, 10, 10, 12, 12, 12, 14, 14, 14, 18, 18, 18, 24, 24, 24, 32, 32, 32, 44, 44, 44, 12, 12, 12, 0},
            {4, 4, 4, 4, 4, 4, 4, 4, 4, 6, 6, 6, 8, 8, 8, 10, 10, 10, 12, 12, 12, 14, 14, 14, 18, 18, 18, 24, 24, 24, 30, 30, 30, 40, 40, 40, 18, 18, 18, 0},
            {4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 6, 6, 6, 8, 8, 8, 10, 10, 10, 12, 12, 12, 14, 14, 14, 18, 18, 18, 22, 22, 22, 30, 30, 30, 56, 56, 56, 0},
            {4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 6, 6, 6, 6, 6, 6, 10, 10, 10, 12, 12, 12, 14, 14, 14, 16, 16, 16, 20, 20, 20, 26, 26, 26, 66, 66, 66, 0},
            {4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 6, 6, 6, 8, 8, 8, 12, 12, 12, 16, 16, 16, 20, 20, 20, 26, 26, 26, 34, 34, 34, 42, 42, 42, 12, 12, 12, 0},
    };

    private static final int[][] SCF_MIXED = {
            {6, 6, 6, 6, 6, 6, 6, 6, 6, 8, 8, 8, 10, 10, 10, 12, 12, 12, 14, 14, 14, 18, 18, 18, 24, 24, 24, 30, 30, 30, 40, 40, 40, 18, 18, 18, 0},
            {12, 12, 12, 4, 4, 4, 8, 8, 8, 12, 12, 12, 16, 16, 16, 20, 20, 20, 24, 24, 24, 28, 28, 28, 36, 36, 36, 2, 2, 2, 2, 2, 2, 2, 2, 2, 26, 26, 26, 0},
            {6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 8, 8, 8, 10, 10, 10, 14, 14, 14, 18, 18, 18, 26, 26, 26, 32, 32, 32, 42, 42, 42, 18, 18, 18, 0},
            {6, 6, 6, 6, 6, 6, 6, 6, 6, 8, 8, 8, 10, 10, 10, 12, 12, 12, 14, 14, 14, 18, 18, 18, 24, 24, 24, 32, 32, 32, 44, 44, 44, 12, 12, 12, 0},
            {6, 6, 6, 6, 6, 6, 6, 6, 6, 8, 8, 8, 10, 10, 10, 12, 12, 12, 14, 14, 14, 18, 18, 18, 24, 24, 24, 30, 30, 30, 40, 40, 40, 18, 18, 18, 0},
            {4, 4, 4, 4, 4, 4, 6, 6, 4, 4, 4, 6, 6, 6, 8, 8, 8, 10, 10, 10, 12, 12, 12, 14, 14, 14, 18, 18, 18, 22, 22, 22, 30, 30, 30, 56, 56, 56, 0},
            {4, 4, 4, 4, 4, 4, 6, 6, 4, 4, 4, 6, 6, 6, 6, 6, 6, 10, 10, 10, 12, 12, 12, 14, 14, 14, 16, 16, 16, 20, 20, 20, 26, 26, 26, 66, 66, 66, 0},
            {4, 4, 4, 4, 4, 4, 6, 6, 4, 4, 4, 6, 6, 6, 8, 8, 8, 12, 12, 12, 16, 16, 16, 20, 20, 20, 26, 26, 26, 34, 34, 34, 42, 42, 42, 12, 12, 12, 0},
    };

    private static final int[][] SCF_PARTITIONS = {
            {6, 5, 5, 5, 6, 5, 5, 5, 6, 5, 7, 3, 11, 10, 0, 0, 7, 7, 7, 0, 6, 6, 6, 3, 8, 8, 5, 0},
            {8, 9, 6, 12, 6, 9, 9, 9, 6, 9, 12, 6, 15, 18, 0, 0, 6, 15, 12, 0, 6, 12, 9, 6, 6, 18, 9, 0},
            {9, 9, 6, 12, 9, 9, 9, 9, 9, 9, 12, 6, 18, 18, 0, 0, 12, 12, 12, 0, 12, 9, 9, 6, 15, 12, 9, 0},
    };

    private static final int[] SCFC_DECODE = {0, 1, 2, 3, 12, 5, 6, 7, 9, 10, 11, 13, 14, 15, 18, 19};
    private static final int[] MOD = {5, 5, 4, 4, 5, 5, 4, 1, 4, 3, 1, 1, 5, 6, 6, 1, 4, 4, 4, 1, 4, 3, 1, 1};
    private static final int[] PREAMP = {1, 1, 1, 1, 2, 2, 3, 3, 3, 2};
    private static final float[] EXPFRAC = {9.31322575e-10f, 7.83145814e-10f, 6.58544508e-10f, 5.53767716e-10f};

    private static final float[] POW43 = {
            0f, -1f, -2.519842f, -4.326749f, -6.349604f, -8.549880f, -10.902724f, -13.390518f, -16.000000f, -18.720754f,
            -21.544347f, -24.463781f, -27.473142f, -30.567351f, -33.741992f, -36.993181f, 0f, 1f, 2.519842f, 4.326749f,
            6.349604f, 8.549880f, 10.902724f, 13.390518f, 16.000000f, 18.720754f, 21.544347f, 24.463781f, 27.473142f, 30.567351f,
            33.741992f, 36.993181f, 40.317474f, 43.711787f, 47.173345f, 50.699631f, 54.288352f, 57.937408f, 61.644865f, 65.408941f,
            69.227979f, 73.100443f, 77.024898f, 81.000000f, 85.024491f, 89.097188f, 93.216975f, 97.382800f, 101.593667f, 105.848633f,
            110.146801f, 114.487321f, 118.869381f, 123.292209f, 127.755065f, 132.257246f, 136.798076f, 141.376907f, 145.993119f, 150.646117f,
            155.335327f, 160.060199f, 164.820202f, 169.614826f, 174.443577f, 179.305980f, 184.201575f, 189.129918f, 194.090580f, 199.083145f,
            204.107210f, 209.162385f, 214.248292f, 219.364564f, 224.510845f, 229.686789f, 234.892058f, 240.126328f, 245.389280f, 250.680604f,
            256.000000f, 261.347174f, 266.721841f, 272.123723f, 277.552547f, 283.008049f, 288.489971f, 293.998060f, 299.532071f, 305.091761f,
            310.676898f, 316.287249f, 321.922592f, 327.582707f, 333.267377f, 338.976394f, 344.709550f, 350.466646f, 356.247482f, 362.051866f,
            367.879608f, 373.730522f, 379.604427f, 385.501143f, 391.420496f, 397.362314f, 403.326427f, 409.312672f, 415.320884f, 421.350905f,
            427.402579f, 433.475750f, 439.570269f, 445.685987f, 451.822757f, 457.980436f, 464.158883f, 470.357960f, 476.577530f, 482.817459f,
            489.077615f, 495.357868f, 501.658090f, 507.978156f, 514.317941f, 520.677324f, 527.056184f, 533.454404f, 539.871867f, 546.308458f,
            552.764065f, 559.238575f, 565.731879f, 572.243870f, 578.774440f, 585.323483f, 591.890898f, 598.476581f, 605.080431f, 611.702349f,
            618.342238f, 625.000000f, 631.675540f, 638.368763f, 645.079578f,
    };

    private static final int[] TABS = {
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 785, 785, 785, 785, 784, 784, 784, 784, 513, 513, 513, 513, 513, 513, 513, 513,
            256, 256, 256, 256, 256, 256, 256, 256, 256, 256, 256, 256, 256, 256, 256, 256, -255, 1313, 1298, 1282, 785, 785, 785, 785,
            784, 784, 784, 784, 769, 769, 769, 769, 256, 256, 256, 256, 256, 256, 256, 256, 256, 256, 256, 256, 256, 256, 256, 256,
            290, 288, -255, 1313, 1298, 1282, 769, 769, 769, 769, 529, 529, 529, 529, 529, 529, 529, 529, 528, 528, 528, 528, 528, 528,
            528, 528, 512, 512, 512, 512, 512, 512, 512, 512, 290, 288, -253, -318, -351, -367, 785, 785, 785, 785, 784, 784, 784, 784,
            769, 769, 769, 769, 256, 256, 256, 256, 256, 256, 256, 256, 256, 256, 256, 256, 256, 256, 256, 256, 819, 818, 547, 547,
            275, 275, 275, 275, 561, 560, 515, 546, 289, 274, 288, 258, -254, -287, 1329, 1299, 1314, 1312, 1057, 1057, 1042, 1042, 1026, 1026,
            784, 784, 784, 784, 529, 529, 529, 529, 529, 529, 529, 529, 769, 769, 769, 769, 768, 768, 768, 768, 563, 560, 306, 306,
            291, 259, -252, -413, -477, -542, 1298, -575, 1041, 1041, 784, 784, 784, 784, 769, 769, 769, 769, 256, 256, 256, 256, 256, 256,
            256, 256, 256, 256, 256, 256, 256, 256, 256, 256, -383, -399, 1107, 1092, 1106, 1061, 849, 849, 789, 789, 1104, 1091, 773, 773,
            1076, 1075, 341, 340, 325, 309, 834, 804, 577, 577, 532, 532, 516, 516, 832, 818, 803, 816, 561, 561, 531, 531, 515, 546,
            289, 289, 288, 258, -252, -429, -493, -559, 1057, 1057, 1042, 1042, 529, 529, 529, 529, 529, 529, 529, 529, 784, 784, 784, 784,
            769, 769, 769, 769, 512, 512, 512, 512, 512, 512, 512, 512, -382, 1077, -415, 1106, 1061, 1104, 849, 849, 789, 789, 1091, 1076,
            1029, 1075, 834, 834, 597, 581, 340, 340, 339, 324, 804, 833, 532, 532, 832, 772, 818, 803, 817, 787, 816, 771, 290, 290,
            290, 290, 288, 258, -253, -349, -414, -447, -463, 1329, 1299, -479, 1314, 1312, 1057, 1057, 1042, 1042, 1026, 1026, 785, 785, 785, 785,
            784, 784, 784, 784, 769, 769, 769, 769, 768, 768, 768, 768, -319, 851, 821, -335, 836, 850, 805, 849, 341, 340, 325, 336,
            533, 533, 579, 579, 564, 564, 773, 832, 578, 548, 563, 516, 321, 276, 306, 291, 304, 259, -251, -572, -733, -830, -863, -879,
            1041, 1041, 784, 784, 784, 784, 769, 769, 769, 769, 256, 256, 256, 256, 256, 256, 256, 256, 256, 256, 256, 256, 256, 256,
            256, 256, -511, -527, -543, 1396, 1351, 1381, 1366, 1395, 1335, 1380, -559, 1334, 1138, 1138, 1063, 1063, 1350, 1392, 1031, 1031, 1062, 1062,
            1364, 1363, 1120, 1120, 1333, 1348, 881, 881, 881, 881, 375, 374, 359, 373, 343, 358, 341, 325, 791, 791, 1123, 1122, -703, 1105,
            1045, -719, 865, 865, 790, 790, 774, 774, 1104, 1029, 338, 293, 323, 308, -799, -815, 833, 788, 772, 818, 803, 816, 322, 292,
            307, 320, 561, 531, 515, 546, 289, 274, 288, 258, -251, -525, -605, -685, -765, -831, -846, 1298, 1057, 1057, 1312, 1282, 785, 785,
            785, 785, 784, 784, 784, 784, 769, 769, 769, 769, 512, 512, 512, 512, 512, 512, 512, 512, 1399, 1398, 1383, 1367, 1382, 1396,
            1351, -511, 1381, 1366, 1139, 1139, 1079, 1079, 1124, 1124, 1364, 1349, 1363, 1333, 882, 882, 882, 882, 807, 807, 807, 807, 1094, 1094,
            1136, 1136, 373, 341, 535, 535, 881, 775, 867, 822, 774, -591, 324, 338, -671, 849, 550, 550, 866, 864, 609, 609, 293, 336,
            534, 534, 789, 835, 773, -751, 834, 804, 308, 307, 833, 788, 832, 772, 562, 562, 547, 547, 305, 275, 560, 515, 290, 290,
            -252, -397, -477, -557, -622, -653, -719, -735, -750, 1329, 1299, 1314, 1057, 1057, 1042, 1042, 1312, 1282, 1024, 1024, 785, 785, 785, 785,
            784, 784, 784, 784, 769, 769, 769, 769, -383, 1127, 1141, 1111, 1126, 1140, 1095, 1110, 869, 869, 883, 883, 1079, 1109, 882, 882,
            375, 374, 807, 868, 838, 881, 791, -463, 867, 822, 368, 263, 852, 837, 836, -543, 610, 610, 550, 550, 352, 336, 534, 534,
            865, 774, 851, 821, 850, 805, 593, 533, 579, 564, 773, 832, 578, 578, 548, 548, 577, 577, 307, 276, 306, 291, 516, 560,
            259, 259, -250, -2107, -2507, -2764, -2909, -2974, -3007, -3023, 1041, 1041, 1040, 1040, 769, 769, 769, 769, 256, 256, 256, 256, 256, 256,
            256, 256, 256, 256, 256, 256, 256, 256, 256, 256, -767, -1052, -1213, -1277, -1358, -1405, -1469, -1535, -1550, -1582, -1614, -1647, -1662, -1694,
            -1726, -1759, -1774, -1807, -1822, -1854, -1886, 1565, -1919, -1935, -1951, -1967, 1731, 1730, 1580, 1717, -1983, 1729, 1564, -1999, 1548, -2015, -2031, 1715,
            1595, -2047, 1714, -2063, 1610, -2079, 1609, -2095, 1323, 1323, 1457, 1457, 1307, 1307, 1712, 1547, 1641, 1700, 1699, 1594, 1685, 1625, 1442, 1442,
            1322, 1322, -780, -973, -910, 1279, 1278, 1277, 1262, 1276, 1261, 1275, 1215, 1260, 1229, -959, 974, 974, 989, 989, -943, 735, 478, 478,
            495, 463, 506, 414, -1039, 1003, 958, 1017, 927, 942, 987, 957, 431, 476, 1272, 1167, 1228, -1183, 1256, -1199, 895, 895, 941, 941,
            1242, 1227, 1212, 1135, 1014, 1014, 490, 489, 503, 487, 910, 1013, 985, 925, 863, 894, 970, 955, 1012, 847, -1343, 831, 755, 755,
            984, 909, 428, 366, 754, 559, -1391, 752, 486, 457, 924, 997, 698, 698, 983, 893, 740, 740, 908, 877, 739, 739, 667, 667,
            953, 938, 497, 287, 271, 271, 683, 606, 590, 712, 726, 574, 302, 302, 738, 736, 481, 286, 526, 725, 605, 711, 636, 724,
            696, 651, 589, 681, 666, 710, 364, 467, 573, 695, 466, 466, 301, 465, 379, 379, 709, 604, 665, 679, 316, 316, 634, 633,
            436, 436, 464, 269, 424, 394, 452, 332, 438, 363, 347, 408, 393, 448, 331, 422, 362, 407, 392, 421, 346, 406, 391, 376,
            375, 359, 1441, 1306, -2367, 1290, -2383, 1337, -2399, -2415, 1426, 1321, -2431, 1411, 1336, -2447, -2463, -2479, 1169, 1169, 1049, 1049, 1424, 1289,
            1412, 1352, 1319, -2495, 1154, 1154, 1064, 1064, 1153, 1153, 416, 390, 360, 404, 403, 389, 344, 374, 373, 343, 358, 372, 327, 357,
            342, 311, 356, 326, 1395, 1394, 1137, 1137, 1047, 1047, 1365, 1392, 1287, 1379, 1334, 1364, 1349, 1378, 1318, 1363, 792, 792, 792, 792,
            1152, 1152, 1032, 1032, 1121, 1121, 1046, 1046, 1120, 1120, 1030, 1030, -2895, 1106, 1061, 1104, 849, 849, 789, 789, 1091, 1076, 1029, 1090,
            1060, 1075, 833, 833, 309, 324, 532, 532, 832, 772, 818, 803, 561, 561, 531, 560, 515, 546, 289, 274, 288, 258, -250, -1179,
            -1579, -1836, -1996, -2124, -2253, -2333, -2413, -2477, -2542, -2574, -2607, -2622, -2655, 1314, 1313, 1298, 1312, 1282, 785, 785, 785, 785, 1040, 1040,
            1025, 1025, 768, 768, 768, 768, -766, -798, -830, -862, -895, -911, -927, -943, -959, -975, -991, -1007, -1023, -1039, -1055, -1070, 1724, 1647,
            -1103, -1119, 1631, 1767, 1662, 1738, 1708, 1723, -1135, 1780, 1615, 1779, 1599, 1677, 1646, 1778, 1583, -1151, 1777, 1567, 1737, 1692, 1765, 1722,
            1707, 1630, 1751, 1661, 1764, 1614, 1736, 1676, 1763, 1750, 1645, 1598, 1721, 1691, 1762, 1706, 1582, 1761, 1566, -1167, 1749, 1629, 767, 766,
            751, 765, 494, 494, 735, 764, 719, 749, 734, 763, 447, 447, 748, 718, 477, 506, 431, 491, 446, 476, 461, 505, 415, 430,
            475, 445, 504, 399, 460, 489, 414, 503, 383, 474, 429, 459, 502, 502, 746, 752, 488, 398, 501, 473, 413, 472, 486, 271,
            480, 270, -1439, -1455, 1357, -1471, -1487, -1503, 1341, 1325, -1519, 1489, 1463, 1403, 1309, -1535, 1372, 1448, 1418, 1476, 1356, 1462, 1387, -1551,
            1475, 1340, 1447, 1402, 1386, -1567, 1068, 1068, 1474, 1461, 455, 380, 468, 440, 395, 425, 410, 454, 364, 467, 466, 464, 453, 269,
            409, 448, 268, 432, 1371, 1473, 1432, 1417, 1308, 1460, 1355, 1446, 1459, 1431, 1083, 1083, 1401, 1416, 1458, 1445, 1067, 1067, 1370, 1457,
            1051, 1051, 1291, 1430, 1385, 1444, 1354, 1415, 1400, 1443, 1082, 1082, 1173, 1113, 1186, 1066, 1185, 1050, -1967, 1158, 1128, 1172, 1097, 1171,
            1081, -1983, 1157, 1112, 416, 266, 375, 400, 1170, 1142, 1127, 1065, 793, 793, 1169, 1033, 1156, 1096, 1141, 1111, 1155, 1080, 1126, 1140,
            898, 898, 808, 808, 897, 897, 792, 792, 1095, 1152, 1032, 1125, 1110, 1139, 1079, 1124, 882, 807, 838, 881, 853, 791, -2319, 867,
            368, 263, 822, 852, 837, 866, 806, 865, -2399, 851, 352, 262, 534, 534, 821, 836, 594, 594, 549, 549, 593, 593, 533, 533,
            848, 773, 579, 579, 564, 578, 548, 563, 276, 276, 577, 576, 306, 291, 516, 560, 305, 305, 275, 259, -251, -892, -2058, -2620,
            -2828, -2957, -3023, -3039, 1041, 1041, 1040, 1040, 769, 769, 769, 769, 256, 256, 256, 256, 256, 256, 256, 256, 256, 256, 256, 256,
            256, 256, 256, 256, -511, -527, -543, -559, 1530, -575, -591, 1528, 1527, 1407, 1526, 1391, 1023, 1023, 1023, 1023, 1525, 1375, 1268, 1268,
            1103, 1103, 1087, 1087, 1039, 1039, 1523, -604, 815, 815, 815, 815, 510, 495, 509, 479, 508, 463, 507, 447, 431, 505, 415, 399,
            -734, -782, 1262, -815, 1259, 1244, -831, 1258, 1228, -847, -863, 1196, -879, 1253, 987, 987, 748, -767, 493, 493, 462, 477, 414, 414,
            686, 669, 478, 446, 461, 445, 474, 429, 487, 458, 412, 471, 1266, 1264, 1009, 1009, 799, 799, -1019, -1276, -1452, -1581, -1677, -1757,
            -1821, -1886, -1933, -1997, 1257, 1257, 1483, 1468, 1512, 1422, 1497, 1406, 1467, 1496, 1421, 1510, 1134, 1134, 1225, 1225, 1466, 1451, 1374, 1405,
            1252, 1252, 1358, 1480, 1164, 1164, 1251, 1251, 1238, 1238, 1389, 1465, -1407, 1054, 1101, -1423, 1207, -1439, 830, 830, 1248, 1038, 1237, 1117,
            1223, 1148, 1236, 1208, 411, 426, 395, 410, 379, 269, 1193, 1222, 1132, 1235, 1221, 1116, 976, 976, 1192, 1162, 1177, 1220, 1131, 1191,
            963, 963, -1647, 961, 780, -1663, 558, 558, 994, 993, 437, 408, 393, 407, 829, 978, 813, 797, 947, -1743, 721, 721, 377, 392,
            844, 950, 828, 890, 706, 706, 812, 859, 796, 960, 948, 843, 934, 874, 571, 571, -1919, 690, 555, 689, 421, 346, 539, 539,
            944, 779, 918, 873, 932, 842, 903, 888, 570, 570, 931, 917, 674, 674, -2575, 1562, -2591, 1609, -2607, 1654, 1322, 1322, 1441, 1441,
            1696, 1546, 1683, 1593, 1669, 1624, 1426, 1426, 1321, 1321, 1639, 1680, 1425, 1425, 1305, 1305, 1545, 1668, 1608, 1623, 1667, 1592, 1638, 1666,
            1320, 1320, 1652, 1607, 1409, 1409, 1304, 1304, 1288, 1288, 1664, 1637, 1395, 1395, 1335, 1335, 1622, 1636, 1394, 1394, 1319, 1319, 1606, 1621,
            1392, 1392, 1137, 1137, 1137, 1137, 345, 390, 360, 375, 404, 373, 1047, -2751, -2767, -2783, 1062, 1121, 1046, -2799, 1077, -2815, 1106, 1061,
            789, 789, 1105, 1104, 263, 355, 310, 340, 325, 354, 352, 262, 339, 324, 1091, 1076, 1029, 1090, 1060, 1075, 833, 833, 788, 788,
            1088, 1028, 818, 818, 803, 803, 561, 561, 531, 531, 816, 771, 546, 546, 289, 274, 288, 258, -253, -317, -381, -446, -478, -509,
            1279, 1279, -811, -1179, -1451, -1756, -1900, -2028, -2189, -2253, -2333, -2414, -2445, -2511, -2526, 1313, 1298, -2559, 1041, 1041, 1040, 1040, 1025, 1025,
            1024, 1024, 1022, 1007, 1021, 991, 1020, 975, 1019, 959, 687, 687, 1018, 1017, 671, 671, 655, 655, 1016, 1015, 639, 639, 758, 758,
            623, 623, 757, 607, 756, 591, 755, 575, 754, 559, 543, 543, 1009, 783, -575, -621, -685, -749, 496, -590, 750, 749, 734, 748,
            974, 989, 1003, 958, 988, 973, 1002, 942, 987, 957, 972, 1001, 926, 986, 941, 971, 956, 1000, 910, 985, 925, 999, 894, 970,
            -1071, -1087, -1102, 1390, -1135, 1436, 1509, 1451, 1374, -1151, 1405, 1358, 1480, 1420, -1167, 1507, 1494, 1389, 1342, 1465, 1435, 1450, 1326, 1505,
            1310, 1493, 1373, 1479, 1404, 1492, 1464, 1419, 428, 443, 472, 397, 736, 526, 464, 464, 486, 457, 442, 471, 484, 482, 1357, 1449,
            1434, 1478, 1388, 1491, 1341, 1490, 1325, 1489, 1463, 1403, 1309, 1477, 1372, 1448, 1418, 1433, 1476, 1356, 1462, 1387, -1439, 1475, 1340, 1447,
            1402, 1474, 1324, 1461, 1371, 1473, 269, 448, 1432, 1417, 1308, 1460, -1711, 1459, -1727, 1441, 1099, 1099, 1446, 1386, 1431, 1401, -1743, 1289,
            1083, 1083, 1160, 1160, 1458, 1445, 1067, 1067, 1370, 1457, 1307, 1430, 1129, 1129, 1098, 1098, 268, 432, 267, 416, 266, 400, -1887, 1144,
            1187, 1082, 1173, 1113, 1186, 1066, 1050, 1158, 1128, 1143, 1172, 1097, 1171, 1081, 420, 391, 1157, 1112, 1170, 1142, 1127, 1065, 1169, 1049,
            1156, 1096, 1141, 1111, 1155, 1080, 1126, 1154, 1064, 1153, 1140, 1095, 1048, -2159, 1125, 1110, 1137, -2175, 823, 823, 1139, 1138, 807, 807,
            384, 264, 368, 263, 868, 838, 853, 791, 867, 822, 852, 837, 866, 806, 865, 790, -2319, 851, 821, 836, 352, 262, 850, 805,
            849, -2399, 533, 533, 835, 820, 336, 261, 578, 548, 563, 577, 532, 532, 832, 772, 562, 562, 547, 547, 305, 275, 560, 515,
            290, 290, 288, 258,
    };

    private static final int[] TAB32 = {130, 162, 193, 209, 44, 28, 76, 140, 9, 9, 9, 9, 9, 9, 9, 9, 190, 254, 222, 238,
            126, 94, 157, 157, 109, 61, 173, 205};
    private static final int[] TAB33 = {252, 236, 220, 204, 188, 172, 156, 140, 124, 108, 92, 76, 60, 44, 28, 12};
    private static final int[] TABINDEX = {0, 32, 64, 98, 0, 132, 180, 218, 292, 364, 426, 538, 648, 746, 0, 1126, 1460,
            1460, 1460, 1460, 1460, 1460, 1460, 1460, 1842, 1842, 1842, 1842, 1842, 1842, 1842, 1842};
    private static final int[] LINBITS = {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 2, 3, 4, 6, 8, 10, 13, 4, 5,
            6, 7, 8, 9, 11, 13};

    private static final float[] PAN = {0, 1, 0.21132487f, 0.78867513f, 0.36602540f, 0.63397460f, 0.5f, 0.5f, 0.63397460f,
            0.36602540f, 0.78867513f, 0.21132487f, 1, 0};
    private static final float[] AA0 = {0.85749293f, 0.88174200f, 0.94962865f, 0.98331459f, 0.99551782f, 0.99916056f,
            0.99989920f, 0.99999316f};
    private static final float[] AA1 = {0.51449576f, 0.47173197f, 0.31337745f, 0.18191320f, 0.09457419f, 0.04096558f,
            0.01419856f, 0.00369997f};
    private static final float[] TWID9 = {0.73727734f, 0.79335334f, 0.84339145f, 0.88701083f, 0.92387953f, 0.95371695f,
            0.97629601f, 0.99144486f, 0.99904822f, 0.67559021f, 0.60876143f, 0.53729961f, 0.46174861f, 0.38268343f,
            0.30070580f, 0.21643961f, 0.13052619f, 0.04361938f};
    private static final float[] TWID3 = {0.79335334f, 0.92387953f, 0.99144486f, 0.60876143f, 0.38268343f, 0.13052619f};
    private static final float[] MDCT_WINDOW_0 = {0.99904822f, 0.99144486f, 0.97629601f, 0.95371695f, 0.92387953f,
            0.88701083f, 0.84339145f, 0.79335334f, 0.73727734f, 0.04361938f, 0.13052619f, 0.21643961f, 0.30070580f,
            0.38268343f, 0.46174861f, 0.53729961f, 0.60876143f, 0.67559021f};
    private static final float[] MDCT_WINDOW_1 = {1, 1, 1, 1, 1, 1, 0.99144486f, 0.92387953f, 0.79335334f, 0, 0, 0, 0, 0,
            0, 0.13052619f, 0.38268343f, 0.60876143f};
    private static final float[] SEC = {10.19000816f, 0.50060302f, 0.50241929f, 3.40760851f, 0.50547093f, 0.52249861f,
            2.05778098f, 0.51544732f, 0.56694406f, 1.48416460f, 0.53104258f, 0.64682180f, 1.16943991f, 0.55310392f,
            0.78815460f, 0.97256821f, 0.58293498f, 1.06067765f, 0.83934963f, 0.62250412f, 1.72244716f, 0.74453628f,
            0.67480832f, 5.10114861f};

    private static final float[] WIN = {
            -1, 26, -31, 208, 218, 401, -519, 2063, 2000, 4788, -5517, 7134, 5959, 35640, -39336, 74992,
            -1, 24, -35, 202, 222, 347, -581, 2080, 1952, 4425, -5879, 7640, 5288, 33791, -41176, 74856,
            -1, 21, -38, 196, 225, 294, -645, 2087, 1893, 4063, -6237, 8092, 4561, 31947, -43006, 74630,
            -1, 19, -41, 190, 227, 244, -711, 2085, 1822, 3705, -6589, 8492, 3776, 30112, -44821, 74313,
            -1, 17, -45, 183, 228, 197, -779, 2075, 1739, 3351, -6935, 8840, 2935, 28289, -46617, 73908,
            -1, 16, -49, 176, 228, 153, -848, 2057, 1644, 3004, -7271, 9139, 2037, 26482, -48390, 73415,
            -2, 14, -53, 169, 227, 111, -919, 2032, 1535, 2663, -7597, 9389, 1082, 24694, -50137, 72835,
            -2, 13, -58, 161, 224, 72, -991, 2001, 1414, 2330, -7910, 9592, 70, 22929, -51853, 72169,
            -2, 11, -63, 154, 221, 36, -1064, 1962, 1280, 2006, -8209, 9750, -998, 21189, -53534, 71420,
            -2, 10, -68, 147, 215, 2, -1137, 1919, 1131, 1692, -8491, 9863, -2122, 19478, -55178, 70590,
            -3, 9, -73, 139, 208, -29, -1210, 1870, 970, 1388, -8755, 9935, -3300, 17799, -56778, 69679,
            -3, 8, -79, 132, 200, -57, -1283, 1817, 794, 1095, -8998, 9966, -4533, 16155, -58333, 68692,
            -4, 7, -85, 125, 189, -83, -1356, 1759, 605, 814, -9219, 9959, -5818, 14548, -59838, 67629,
            -4, 7, -91, 117, 177, -106, -1428, 1698, 402, 545, -9416, 9916, -7154, 12980, -61289, 66494,
            -5, 6, -97, 111, 163, -127, -1498, 1634, 185, 288, -9585, 9838, -8540, 11455, -62684, 65290,
    };

    private static final int[][] ALLOC_L1 = {{76, 4, 32}};
    private static final int[][] ALLOC_L2M2 = {{60, 4, 4}, {44, 3, 7}, {44, 2, 19}};
    private static final int[][] ALLOC_L2M1 = {{0, 4, 3}, {16, 4, 8}, {32, 3, 12}, {40, 2, 7}};
    private static final int[][] ALLOC_L2M1_LOWRATE = {{44, 4, 2}, {44, 3, 10}};
    private static final int[] BITALLOC_CODE_TAB = {
            0, 17, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16,
            0, 17, 18, 3, 19, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 16,
            0, 17, 18, 3, 19, 4, 5, 16,
            0, 17, 18, 16,
            0, 17, 18, 19, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15,
            0, 17, 18, 3, 19, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14,
            0, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16,
    };
    private static final float[] DEQ_L12 = {
            9.53674316e-07f / 3, 7.56931807e-07f / 3, 6.00777173e-07f / 3,
            9.53674316e-07f / 7, 7.56931807e-07f / 7, 6.00777173e-07f / 7,
            9.53674316e-07f / 15, 7.56931807e-07f / 15, 6.00777173e-07f / 15,
            9.53674316e-07f / 31, 7.56931807e-07f / 31, 6.00777173e-07f / 31,
            9.53674316e-07f / 63, 7.56931807e-07f / 63, 6.00777173e-07f / 63,
            9.53674316e-07f / 127, 7.56931807e-07f / 127, 6.00777173e-07f / 127,
            9.53674316e-07f / 255, 7.56931807e-07f / 255, 6.00777173e-07f / 255,
            9.53674316e-07f / 511, 7.56931807e-07f / 511, 6.00777173e-07f / 511,
            9.53674316e-07f / 1023, 7.56931807e-07f / 1023, 6.00777173e-07f / 1023,
            9.53674316e-07f / 2047, 7.56931807e-07f / 2047, 6.00777173e-07f / 2047,
            9.53674316e-07f / 4095, 7.56931807e-07f / 4095, 6.00777173e-07f / 4095,
            9.53674316e-07f / 8191, 7.56931807e-07f / 8191, 6.00777173e-07f / 8191,
            9.53674316e-07f / 16383, 7.56931807e-07f / 16383, 6.00777173e-07f / 16383,
            9.53674316e-07f / 32767, 7.56931807e-07f / 32767, 6.00777173e-07f / 32767,
            9.53674316e-07f / 65535, 7.56931807e-07f / 65535, 6.00777173e-07f / 65535,
            9.53674316e-07f / 3, 7.56931807e-07f / 3, 6.00777173e-07f / 3,
            9.53674316e-07f / 5, 7.56931807e-07f / 5, 6.00777173e-07f / 5,
            9.53674316e-07f / 9, 7.56931807e-07f / 9, 6.00777173e-07f / 9,
    };
}

package ru.gora.musicbattle.audio.codec;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.util.Objects;

/**
 * Raw AAC in ADTS frames (.aac files, ISO/IEC 14496-3, 1.A.3.2), decoded by {@link AacDecoder}.
 *
 * <p>The framing follows what {@code ffmpeg -i song.aac} does, because charts are timed against its decode: ID3v2
 * tags at the start are skipped by their size, junk between frames is skipped up to the next valid header, every
 * frame's samples are output (ADTS has no field for encoder delay or padding, so nothing is trimmed), and a last
 * frame cut short by the end of the file is dropped. HE-AAC in ADTS can only be signalled implicitly; the first frame
 * is decoded once to look for SBR data, and the stream is then decoded at twice the core rate, as ffmpeg does.
 */
public final class AdtsDecoder {
    private static final int MAX_FRAME = 8191;
    /** How far past the start, or past a damaged frame, the next frame is looked for. */
    private static final int MAX_RESYNC = 1 << 20;
    /** How much of the start {@link #findsFrames} wants: room for three frames of the largest size ADTS allows. */
    public static final int SYNC_PROBE_BYTES = 3 * MAX_FRAME + 7;
    /**
     * How far into a file {@link #findFrameStart} looks for the first frame of a stream that does not begin with one,
     * as a recording of a broadcast that starts part way into a frame does.
     */
    public static final int SCAN_BYTES = 64 * 1024;

    private AdtsDecoder() {
    }

    /**
     * Whether the first bytes of a file are an ADTS frame header: the 12-bit sync word, layer 0, a sampling rate the
     * specification defines and a frame length that holds the header. When the second frame starts within the head,
     * its header must be valid too. Seven bytes are a weak signature (about one in 10 000 random heads that start
     * with 0xFF pass), so a caller that can read further confirms with {@link #findsFrames}.
     */
    public static boolean looksLike(byte[] head) {
        if (head == null || head.length < 7 || !validHeader(head, 0)) {
            return false;
        }
        int next = frameLength(head, 0);
        return next + 7 > head.length || validHeader(head, next);
    }

    /**
     * Whether the start of a file is ADTS beyond doubt: a frame header at the very start, and the frames it chains to
     * (up to three) each starting with a header of the same stream, or the probe ending exactly where a frame does.
     * Give it the first {@link #SYNC_PROBE_BYTES} bytes, or the whole file when it is shorter.
     */
    public static boolean findsFrames(byte[] probe) {
        return probe != null && findsFrames(probe, 0, probe.length, true);
    }

    /**
     * Where the first frame of an ADTS stream begins in the start of a file that may not begin with one: the first
     * offset below {@code limit} from which a chain of frames is confirmed as {@link #findsFrames} confirms it, or -1.
     * {@link #open} starts at the same frame. A recording of a broadcast often starts part way into a frame, and ffmpeg
     * skips what comes before the first one; this lets a caller believe such a file only where a chain of frames
     * really starts. Past the very start a lone frame that ends with the file is not enough, since junk would pass for
     * one now and then. Give it the first {@code limit + SYNC_PROBE_BYTES} bytes, or the whole file when it is shorter.
     */
    public static int findFrameStart(byte[] probe, int limit) {
        if (probe == null) {
            return -1;
        }
        int last = Math.min(limit, probe.length - 7);
        for (int at = 0; at < last; at++) {
            if ((probe[at] & 0xFF) == 0xFF
                    && findsFrames(probe, at, Math.min(probe.length, at + SYNC_PROBE_BYTES), at == 0)) {
                return at;
            }
        }
        return -1;
    }

    /**
     * {@link #findsFrames(byte[])} on {@code b[from .. to)}, which is either {@link #SYNC_PROBE_BYTES} long or ends where
     * the file does. A single frame that ends exactly with the file counts only at the start of the stream.
     */
    private static boolean findsFrames(byte[] b, int from, int to, boolean atStart) {
        if (to - from < 7 || !validHeader(b, from)) {
            return false;
        }
        int at = from;
        for (int frame = 0; frame < 3; frame++) {
            int length = frameLength(b, at);
            int next = at + length;
            if (next == to) {
                // A file of fewer frames than the probe holds, ending exactly at a frame boundary.
                return frame > 0 || (atStart && to - from < SYNC_PROBE_BYTES);
            }
            if (next + 7 > to) {
                // The next header is past the probe: the frames so far chained cleanly.
                return frame > 0;
            }
            if (!validHeader(b, next) || ((b[next + 2] ^ b[from + 2]) & 0xFD) != 0
                    || ((b[next + 3] ^ b[from + 3]) & 0xC0) != 0) {
                return false;
            }
            at = next;
        }
        return true;
    }

    /**
     * Opens an ADTS stream and decodes its first frame. On success the returned stream owns {@code in} and closes it;
     * on failure {@code in} is left to the caller.
     *
     * @throws UnsupportedFormatException for AAC profiles other than LC (and HE-AAC on top of it)
     * @throws AudioFormatException when there is no ADTS frame to decode
     */
    public static PcmStream open(InputStream in) throws IOException {
        Objects.requireNonNull(in, "in");
        return new AdtsStream(in, false);
    }

    /** How many damaged frames in a row {@link #openTolerant} passes over before it gives up on the stream. */
    static final int MAX_DAMAGED_IN_ROW = 8;

    /**
     * {@link #open}, for a broadcast or recorded MPEG transport stream, where single frames arrive damaged: a frame
     * after the first that fails to decode is dropped, as ffmpeg drops it, rather than failing the whole song. More
     * than {@link #MAX_DAMAGED_IN_ROW} in a row is still an error. A plain .aac file keeps the strict rule.
     */
    static PcmStream openTolerant(InputStream in) throws IOException {
        Objects.requireNonNull(in, "in");
        return new AdtsStream(in, true);
    }

    static boolean validHeader(byte[] b, int at) {
        if ((b[at] & 0xFF) != 0xFF || (b[at + 1] & 0xF6) != 0xF0) {
            return false;
        }
        int rateIndex = ((b[at + 2] & 0xFF) >> 2) & 15;
        if (rateIndex >= AacTables.SAMPLE_RATES.length) {
            return false;
        }
        int headerLength = (b[at + 1] & 1) != 0 ? 7 : 9;
        return frameLength(b, at) >= headerLength + 1;
    }

    static int frameLength(byte[] b, int at) {
        return ((b[at + 3] & 3) << 11) | ((b[at + 4] & 0xFF) << 3) | ((b[at + 5] & 0xFF) >> 5);
    }

    private static final class AdtsStream implements PcmStream {
        private final InputStream in;
        private final byte[] buffer = new byte[1 << 16];
        private int bufferStart;
        private int bufferEnd;
        private boolean eof;
        private boolean closed;

        private final byte[] frame = new byte[MAX_FRAME + AacBits.PADDING];
        private final AacConfig config;
        private AacDecoder decoder;
        private final float[][] pcm;
        private int pcmAt;
        private int pcmEnd;
        private boolean ended;

        private final boolean tolerant;
        private int damagedInRow;

        AdtsStream(InputStream in, boolean tolerant) throws IOException {
            this.in = in;
            this.tolerant = tolerant;
            skipId3v2();
            int length = nextFrame(true);
            if (length < 0) {
                throw new AudioFormatException("No ADTS frame found");
            }
            System.arraycopy(frame, 0, frame0, 0, 7);
            config = AacConfig.fromAdts(frame, 0);
            AacDecoder probe = new AacDecoder(config, false);
            float[][] first = new float[Math.max(2, probe.outputChannels())][2048];
            decodeFrame(probe, length, first);
            if (probe.sbrSeen() && config.sampleRate <= 24000) {
                // Implicit HE-AAC: decode again from the start with SBR, at twice the rate.
                config.sbr = 1;
                probe = new AacDecoder(config, true);
                first = new float[Math.max(2, probe.outputChannels())][2048];
                decodeFrame(probe, length, first);
            }
            decoder = probe;
            pcm = new float[decoder.outputChannels()][decoder.frameLength()];
            for (int c = 0; c < pcm.length; c++) {
                System.arraycopy(first[c], 0, pcm[c], 0, decoder.frameLength());
            }
            pcmAt = 0;
            pcmEnd = decoder.frameLength();
        }

        /** Decodes the raw data block of the frame in {@link #frame}. */
        private void decodeFrame(AacDecoder d, int length, float[][] out) throws AudioFormatException {
            int headerLength = (frame[1] & 1) != 0 ? 7 : 9;
            int blocks = (frame[6] & 3) + 1;
            if (blocks > 1) {
                // Several raw data blocks per frame: ffmpeg refuses these, and so few encoders write them that a
                // decode nobody can compare against is not worth having.
                throw new UnsupportedFormatException("ADTS with several AAC frames per header");
            }
            d.decode(frame, headerLength, length - headerLength, out, 0);
        }

        // ----- byte input

        private int fill(int need) throws IOException {
            if (bufferEnd - bufferStart >= need || eof) {
                return bufferEnd - bufferStart;
            }
            if (bufferStart > 0) {
                System.arraycopy(buffer, bufferStart, buffer, 0, bufferEnd - bufferStart);
                bufferEnd -= bufferStart;
                bufferStart = 0;
            }
            while (bufferEnd < need && !eof) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedIOException("AAC decoding interrupted");
                }
                int n = in.read(buffer, bufferEnd, buffer.length - bufferEnd);
                if (n < 0) {
                    eof = true;
                } else if (n == 0) {
                    int one = in.read();
                    if (one < 0) {
                        eof = true;
                    } else {
                        buffer[bufferEnd++] = (byte) one;
                    }
                } else {
                    bufferEnd += n;
                }
            }
            return bufferEnd - bufferStart;
        }

        private void skipId3v2() throws IOException {
            while (fill(10) >= 10) {
                byte[] b = buffer;
                int p = bufferStart;
                if (b[p] != 'I' || b[p + 1] != 'D' || b[p + 2] != '3' || (b[p + 3] & 0xFF) == 0xFF
                        || (b[p + 4] & 0xFF) == 0xFF || (b[p + 6] | b[p + 7] | b[p + 8] | b[p + 9]) < 0) {
                    return;
                }
                long size = 10L + ((b[p + 6] & 0x7FL) << 21 | (b[p + 7] & 0x7FL) << 14 | (b[p + 8] & 0x7FL) << 7
                        | (b[p + 9] & 0x7FL)) + (b[p + 3] == 4 && (b[p + 5] & 0x10) != 0 ? 10 : 0);
                skipBytes(size);
            }
        }

        private void skipBytes(long n) throws IOException {
            while (n > 0) {
                int available = fill(1);
                if (available == 0) {
                    return;
                }
                int step = (int) Math.min(n, available);
                bufferStart += step;
                n -= step;
            }
        }

        /**
         * Finds the next frame and copies it into {@link #frame}. Junk before it is skipped. The first frame is taken
         * only where {@link #findFrameStart} would find it, at the start of a confirmed chain of frames, so a stray
         * 0xFFF in a tag or in junk is not taken for the stream, and this starts where AudioDecoders.detect() saw the
         * stream start. Returns the frame's length, or -1 at the end.
         */
        private int nextFrame(boolean first) throws IOException {
            long skipped = 0;
            while (true) {
                if (fill(7) < 7) {
                    return -1;
                }
                if (validHeader(buffer, bufferStart)) {
                    int length = frameLength(buffer, bufferStart);
                    int have;
                    boolean confirmed = true;
                    if (first) {
                        have = fill(SYNC_PROBE_BYTES);
                        confirmed = findsFrames(buffer, bufferStart, bufferStart + Math.min(have, SYNC_PROBE_BYTES),
                                skipped == 0);
                    } else {
                        have = fill(length);
                    }
                    if (confirmed && (config == null || sameStream(buffer, bufferStart))) {
                        if (have < length) {
                            // A frame cut short by the end of the file: ffmpeg drops it.
                            bufferStart = bufferEnd;
                            return -1;
                        }
                        System.arraycopy(buffer, bufferStart, frame, 0, length);
                        java.util.Arrays.fill(frame, length, length + AacBits.PADDING, (byte) 0);
                        bufferStart += length;
                        return length;
                    }
                }
                bufferStart++;
                if (++skipped > MAX_RESYNC) {
                    return -1;
                }
                if ((skipped & 0xFFF) == 0 && Thread.currentThread().isInterrupted()) {
                    throw new InterruptedIOException("AAC decoding interrupted");
                }
            }
        }

        /** Whether a header belongs to the stream begun at the start: same profile, rate and channels. */
        private boolean sameStream(byte[] b, int at) {
            return ((b[at + 2] ^ frame0[2]) & 0xFD) == 0 && ((b[at + 3] ^ frame0[3]) & 0xC0) == 0;
        }

        private final byte[] frame0 = new byte[7];

        // ----- PcmStream

        @Override
        public int sampleRate() {
            return decoder.outputRate();
        }

        @Override
        public int channels() {
            return decoder.outputChannels();
        }

        @Override
        public int read(float[][] out, int offset, int frames) throws IOException {
            if (closed) {
                throw new IOException("Stream closed");
            }
            if (frames <= 0) {
                return 0;
            }
            while (pcmAt >= pcmEnd) {
                if (ended) {
                    return -1;
                }
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedIOException("AAC decoding interrupted");
                }
                int length = nextFrame(false);
                if (length < 0) {
                    ended = true;
                    return -1;
                }
                try {
                    decodeFrame(decoder, length, pcm);
                } catch (AudioFormatException e) {
                    // A tool this decoder lacks named part way (coupling channels, say) is damage as well here: the
                    // stream's first frame decoded, and random bytes read as AAC name such tools as often as not.
                    if (!tolerant || ++damagedInRow > MAX_DAMAGED_IN_ROW) {
                        throw e;
                    }
                    // Dropped, as ffmpeg drops a frame it cannot decode; the next one is decoded as usual.
                    continue;
                }
                damagedInRow = 0;
                pcmAt = 0;
                pcmEnd = decoder.frameLength();
            }
            int n = Math.min(frames, pcmEnd - pcmAt);
            for (int c = 0; c < pcm.length; c++) {
                System.arraycopy(pcm[c], pcmAt, out[c], offset, n);
            }
            pcmAt += n;
            return n;
        }

        @Override
        public void close() throws IOException {
            if (!closed) {
                closed = true;
                in.close();
            }
        }
    }
}

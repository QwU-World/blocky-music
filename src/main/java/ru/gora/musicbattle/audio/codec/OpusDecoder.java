package ru.gora.musicbattle.audio.codec;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Objects;

/**
 * Decodes Ogg Opus (RFC 7845) in plain Java: the .opus files of yt-dlp, opusenc and ffmpeg, and Opus in .ogg or .oga.
 * Output is 48 kHz planar float, with the header's pre-skip dropped from the start, the end trimmed by the last page's
 * granule position (RFC 7845 sections 4.2 to 4.4), and the header's output gain applied. This is what ffmpeg gives
 * for the same file, to the sample.
 *
 * <p>The codec is a port of the libopus 1.6.1 decoder ({@link OpusPacketDecoder} and the classes it uses). It decodes
 * the RFC 8251 test vectors bit for bit like a libopus build without fused multiply-adds or SIMD. Mono and stereo
 * (channel mapping family 0) and up to 8 channels in Vorbis order (family 1) are read; a song with more than two
 * channels is folded by {@link ChannelDownmix} with the Vorbis layout. Ambisonic and undefined channel mappings are
 * refused as unsupported, since they have no speaker layout to fold.
 *
 * <p>Only the first logical Opus stream of a file is decoded: pages of other streams (a video track, a skeleton) are
 * checked and skipped, and a chained file stops at the end of its first link. Every page's checksum is verified, and
 * a page missing from the Opus stream, a cut-off page or a packet the decoder refuses is reported as damage, as for
 * Ogg Vorbis, rather than played with a hole in it. A zero-length packet is skipped, as RFC 7845 asks.
 */
public final class OpusDecoder {
    private OpusDecoder() {
    }

    /**
     * Whether the first bytes of a file are an Ogg stream that starts with an Opus header. Every beginning-of-stream
     * page found in {@code head} is looked at, so given a few kilobytes it also finds Opus behind a skeleton or a
     * video stream's first page; given 64 bytes it sees the first page only.
     */
    public static boolean looksLike(byte[] head) {
        if (head == null) {
            return false;
        }
        int pos = 0;
        while (pos + 27 <= head.length && head[pos] == 'O' && head[pos + 1] == 'g' && head[pos + 2] == 'g'
                && head[pos + 3] == 'S' && (head[pos + 5] & OggOpusPackets.FLAG_FIRST) != 0) {
            int segments = head[pos + 26] & 0xFF;
            int body = pos + 27 + segments;
            if (body > head.length) {
                return false;
            }
            if (segments > 0 && OpusHead.isOpusHead(head, body, Math.min(head.length - body, head[pos + 27] & 0xFF))) {
                return true;
            }
            int size = 0;
            for (int i = 0; i < segments; i++) {
                size += head[pos + 27 + i] & 0xFF;
            }
            pos = body + size;
        }
        return false;
    }

    /**
     * Reads the Opus headers and returns a stream of the decoded samples. On success the returned stream owns
     * {@code in} and closes it; on failure {@code in} is left open for the caller.
     *
     * @throws UnsupportedFormatException for an Opus variant this package does not play (ambisonics, an undefined
     *     channel mapping, an incompatible encapsulation version)
     * @throws AudioFormatException when the bytes are not Ogg Opus or are damaged
     */
    public static PcmStream open(InputStream in) throws IOException {
        Objects.requireNonNull(in, "in");
        OggOpusPackets packets = new OggOpusPackets(in);
        try {
            OpusHead head = packets.readHeaders();
            return new OpusPcmStream(packets, head, head.preSkip, "Ogg Opus");
        } catch (RuntimeException e) {
            throw new AudioFormatException("The Ogg Opus headers are damaged.", e);
        }
    }

    /** A new packet buffer's size: a 20 ms stereo packet below 400 kbit/s fits. */
    private static final int INITIAL_PACKET_BYTES = 1024;
    /**
     * The largest buffer a recycled packet keeps. A 120 ms stereo packet at the codec's highest rate is 7.7 kB, so
     * songs never reach it; 5.1 and 7.1 packets of 120 ms at the highest rates (up to 39 kB) only allocate a buffer for
     * each such packet, which costs nothing next to decoding it.
     */
    private static final int RECYCLED_BUFFER_LIMIT = 8 * 1024;

    /** One audio packet, with the samples the container trims from its end. */
    private static final class Packet {
        byte[] data = new byte[INITIAL_PACKET_BYTES];
        int length;
        int duration;
        int trimEnd;
    }

    /** Reads the pages of an Ogg file, follows the first Opus stream and hands out its audio packets. */
    static final class OggOpusPackets implements OpusPcmStream.Packets {
        static final int FLAG_CONTINUED = 1;
        static final int FLAG_FIRST = 2;
        static final int FLAG_LAST = 4;
        /**
         * The largest Ogg packet accepted. A 120 ms packet of 255 streams at the codec's highest rate stays far below
         * this; a bigger one is a damaged or hostile file, and the packet only grows as its pages actually arrive.
         */
        private static final int MAX_PACKET_BYTES = 4 << 20;
        /** Beginning-of-stream pages looked through for the Opus one; more than this is not a song. */
        private static final int MAX_LEADING_FOREIGN_PAGES = 4096;
        private static final int[] CRC_TABLE = new int[256];

        static {
            // The Ogg checksum is CRC-32 with polynomial 0x04c11db7, most significant bit first, starting from zero
            // and without the final inversion that zip uses, so java.util.zip.CRC32 cannot compute it.
            for (int i = 0; i < 256; i++) {
                int r = i << 24;
                for (int j = 0; j < 8; j++) {
                    r = (r & 0x80000000) != 0 ? (r << 1) ^ 0x04c11db7 : r << 1;
                }
                CRC_TABLE[i] = r;
            }
        }

        private final InputStream in;
        private final byte[] buffer = new byte[64 * 1024];
        private int bufferPos;
        private int bufferLimit;
        private long bufferStartOffset;

        // The current page.
        private final byte[] header = new byte[27 + 255];
        private final byte[] body = new byte[255 * 255];
        private int segmentCount;
        private int pageFlags;
        private long pageGranule;
        private int pageSerial;
        private int pageSequence;
        private long pageOffset;

        // The chosen logical stream.
        private int serial;
        private int expectedSequence;
        private boolean streamEnded;
        private boolean dataPagesStarted;
        private boolean firstAudioPage = true;
        /** The granule position of the most recent page that completed a packet (RFC 7845 section 4.4). */
        private long previousGranule;

        // Packet assembly.
        /** Whether the last page read ended in the middle of a packet, so the next one must continue it. */
        private boolean inPacket;
        private Packet partial;
        private boolean skipPartial;
        private final ArrayDeque<Packet> ready = new ArrayDeque<>();
        private final ArrayDeque<Packet> spare = new ArrayDeque<>();
        private final ArrayDeque<Packet> completed = new ArrayDeque<>();
        private Packet current;

        OggOpusPackets(InputStream in) {
            this.in = in;
        }

        /** Finds the first Opus stream, reads its two headers and returns the identification header. */
        OpusHead readHeaders() throws IOException {
            if (!readPage()) {
                throw new AudioFormatException("The file is empty.");
            }
            if ((pageFlags & FLAG_FIRST) == 0) {
                throw new AudioFormatException("The file does not start at the beginning of an Ogg stream.");
            }
            String otherCodec = null;
            for (int pages = 0; ; pages++) {
                // In a multiplexed file every stream announces itself with a beginning-of-stream page before any
                // data page, so only those are looked at.
                if ((pageFlags & FLAG_FIRST) == 0) {
                    throw noOpus(otherCodec);
                }
                int first = segmentCount > 0 ? header[27] & 0xFF : 0;
                if (segmentCount > 0 && OpusHead.isOpusHead(body, 0, first)) {
                    break;
                }
                if (otherCodec == null) {
                    otherCodec = codecName(first);
                }
                checkInterrupt();
                if (pages >= MAX_LEADING_FOREIGN_PAGES || !readPage()) {
                    throw noOpus(otherCodec);
                }
            }
            serial = pageSerial;
            expectedSequence = pageSequence + 1;
            // The identification header must end on its own page (RFC 7845 section 3).
            int idLength = 0;
            int i = 0;
            while (i < segmentCount) {
                int lace = header[27 + i] & 0xFF;
                idLength += lace;
                i++;
                if (lace < 255) {
                    break;
                }
            }
            if ((header[27 + i - 1] & 0xFF) == 255) {
                throw new AudioFormatException("The Opus identification header does not end on its page.");
            }
            OpusHead head = OpusHead.parse(body, 0, idLength);
            // The comment header can carry megabytes of cover art, so it is checked and skipped, never kept.
            if (!skipCommentHeader()) {
                throw new AudioFormatException("The Opus stream ends before its comment header.");
            }
            return head;
        }

        private static AudioFormatException noOpus(String otherCodec) {
            return new AudioFormatException(otherCodec == null
                    ? "The Ogg file holds no Opus audio."
                    : "The Ogg file holds " + otherCodec + " audio, not Opus.");
        }

        /** Names the codec of a foreign first page when it is one players meet, for the message. */
        private String codecName(int length) {
            if (length >= 7 && body[0] == 1 && body[1] == 'v' && body[2] == 'o' && body[3] == 'r' && body[4] == 'b'
                    && body[5] == 'i' && body[6] == 's') {
                return "Vorbis";
            }
            if (length >= 5 && body[0] == 0x7F && body[1] == 'F' && body[2] == 'L' && body[3] == 'A' && body[4] == 'C') {
                return "FLAC";
            }
            if (length >= 8 && new String(body, 0, 8, StandardCharsets.ISO_8859_1).equals("Speex   ")) {
                return "Speex";
            }
            return null;
        }

        /** Reads the comment header packet, which must start with "OpusTags", and drops it. */
        private boolean skipCommentHeader() throws IOException {
            boolean started = false;
            while (true) {
                if (!loadNextOwnPage()) {
                    return false;
                }
                if (!started && (pageFlags & FLAG_CONTINUED) != 0) {
                    throw new AudioFormatException("The Opus comment header page continues a packet that was never"
                            + " started.");
                }
                int pos = 0;
                for (int i = 0; i < segmentCount; i++) {
                    int lace = header[27 + i] & 0xFF;
                    if (!started) {
                        // A packet's first lacing segment holds its first 8 bytes whenever it has that many.
                        if (lace < 8 || body[pos] != 'O' || body[pos + 1] != 'p' || body[pos + 2] != 'u'
                                || body[pos + 3] != 's' || body[pos + 4] != 'T' || body[pos + 5] != 'a'
                                || body[pos + 6] != 'g' || body[pos + 7] != 's') {
                            throw new AudioFormatException("The second Opus header is not the comment header.");
                        }
                        started = true;
                    }
                    pos += lace;
                    if (lace < 255) {
                        inPacket = false;
                        // RFC 7845 says the comment header finishes its page. Packets after it on the same page are
                        // still taken as audio, which is what a muxer that broke the rule meant by them.
                        if (i + 1 < segmentCount) {
                            collectPackets(i + 1, pos);
                        }
                        return true;
                    }
                }
                inPacket = true;
            }
        }

        @Override
        public boolean next() throws IOException {
            if (current != null) {
                recycle(current);
                current = null;
            }
            while (ready.isEmpty()) {
                if (streamEnded) {
                    if (partial != null && !skipPartial) {
                        throw new AudioFormatException("The Ogg Opus stream ends in the middle of a packet.");
                    }
                    return false;
                }
                checkInterrupt();
                if (!loadNextOwnPage()) {
                    streamEnded = true;
                    continue;
                }
                collectPackets(0, 0);
            }
            current = ready.poll();
            return true;
        }

        @Override
        public byte[] data() {
            return current.data;
        }

        @Override
        public int offset() {
            return 0;
        }

        @Override
        public int length() {
            return current.length;
        }

        @Override
        public int trimStart() {
            return 0;
        }

        @Override
        public int trimEnd() {
            return current.trimEnd;
        }

        @Override
        public void close() throws IOException {
            in.close();
        }

        /** Splits the current page into packets from the given lacing segment on, and applies its granule. */
        private void collectPackets(int firstSegment, int firstByte) throws IOException {
            boolean continued = (pageFlags & FLAG_CONTINUED) != 0 && firstSegment == 0;
            if (firstAudioPage && continued && partial == null) {
                // A live stream joined mid-broadcast: RFC 7845 section 3 says not to decode the piece of a packet
                // that continues from a page we never saw.
                skipPartial = true;
                partial = obtain();
            }
            firstAudioPage = false;
            boolean completedAny = false;
            int pos = firstByte;
            for (int i = firstSegment; i < segmentCount; i++) {
                int lace = header[27 + i] & 0xFF;
                if (partial == null) {
                    partial = obtain();
                }
                append(partial, pos, lace);
                pos += lace;
                if (lace < 255) {
                    completedAny = true;
                    if (skipPartial || partial.length == 0) {
                        // The unfinished piece of a joined stream, or a zero-length packet (see OpusPcmStream).
                        skipPartial = false;
                        recycle(partial);
                    } else {
                        partial.duration = Math.max(0, OpusPacketDecoder.packetSamples(partial.data, 0,
                                partial.length));
                        completed.add(partial);
                    }
                    partial = null;
                }
            }
            inPacket = partial != null;
            boolean last = (pageFlags & FLAG_LAST) != 0;
            if (last && pageGranule != -1L && !completed.isEmpty()) {
                // RFC 7845 section 4.4: of the packets completed on the end page, keep only as many samples as its
                // granule position is past the previous page's. An end page without packets ends the stream and
                // nothing else, whatever its granule position says, and samples before the end page are never cut.
                long samples = 0;
                for (Packet p : completed) {
                    samples += p.duration;
                }
                long keep = pageGranule - previousGranule;
                if (keep < samples) {
                    trimFromEnd(samples - Math.max(0L, keep));
                }
            }
            if (completedAny && pageGranule != -1L) {
                previousGranule = pageGranule;
            }
            if (last) {
                streamEnded = true;
            }
            ready.addAll(completed);
            completed.clear();
        }

        private void trimFromEnd(long excess) {
            var it = completed.descendingIterator();
            while (excess > 0 && it.hasNext()) {
                Packet p = it.next();
                int cut = (int) Math.min(excess, p.duration);
                p.trimEnd = cut;
                excess -= cut;
            }
        }

        /** Takes a packet from the pool, most recently returned first, so the same few are reused. */
        private Packet obtain() {
            Packet p = spare.poll();
            if (p == null) {
                p = new Packet();
            }
            p.length = 0;
            p.duration = 0;
            p.trimEnd = 0;
            return p;
        }

        /**
         * Returns a packet to the pool. A page can complete up to 255 packets, so the pool can hold that many, and each
         * one that ever carried a large packet would otherwise keep its large buffer until the stream is closed: a file
         * of large packets interleaved with pages of many small ones could make the pool hold far more than the file.
         * A buffer beyond what any real packet needs goes back to the initial size instead, so the pool holds at most
         * 256 buffers of at most 8 kB, and a large packet costs memory only while it is being assembled and decoded.
         */
        private void recycle(Packet p) {
            if (p.data.length > RECYCLED_BUFFER_LIMIT) {
                p.data = new byte[INITIAL_PACKET_BYTES];
            }
            spare.push(p);
        }

        private void append(Packet p, int from, int length) throws AudioFormatException {
            if (length == 0) {
                return;
            }
            if (p.length + length > MAX_PACKET_BYTES) {
                throw new AudioFormatException("An Opus packet near byte " + pageOffset + " is larger than "
                        + (MAX_PACKET_BYTES >> 20) + " MB.");
            }
            if (p.length + length > p.data.length) {
                // Doubling keeps a packet that spans many pages cheap to assemble; the buffer never grows past the
                // largest packet accepted.
                p.data = Arrays.copyOf(p.data, Math.min(MAX_PACKET_BYTES,
                        Math.max(p.data.length * 2, p.length + length)));
            }
            System.arraycopy(body, from, p.data, p.length, length);
            p.length += length;
        }

        /** Reads the next page of the chosen stream, skipping (but verifying) the pages of other streams. */
        private boolean loadNextOwnPage() throws IOException {
            while (true) {
                checkInterrupt();
                if (!readPage()) {
                    return false;
                }
                boolean beginning = (pageFlags & FLAG_FIRST) != 0;
                if (beginning && dataPagesStarted) {
                    // A beginning-of-stream page after the data has started is the next link of a chained file.
                    return false;
                }
                if (!beginning) {
                    dataPagesStarted = true;
                }
                if (pageSerial != serial) {
                    continue;
                }
                if (beginning) {
                    throw new AudioFormatException("The Opus stream starts twice (page at byte " + pageOffset + ").");
                }
                if (pageSequence != expectedSequence) {
                    throw new AudioFormatException("An Ogg page is missing before byte " + pageOffset
                            + " (expected page " + Integer.toUnsignedString(expectedSequence)
                            + ", found " + Integer.toUnsignedString(pageSequence) + ").");
                }
                expectedSequence = pageSequence + 1;
                boolean continued = (pageFlags & FLAG_CONTINUED) != 0;
                // Only the first audio page may continue a packet we never saw (a stream joined mid-broadcast).
                if (continued != inPacket && !(continued && firstAudioPage && !inPacket)) {
                    throw new AudioFormatException(continued
                            ? "The Ogg page at byte " + pageOffset + " continues a packet that was never started."
                            : "The Ogg page at byte " + pageOffset + " drops the end of the previous packet.");
                }
                return true;
            }
        }

        /**
         * Reads one whole page and verifies its checksum.
         *
         * @return false when the input ends cleanly where a page would start
         */
        private boolean readPage() throws IOException {
            long offset = bufferStartOffset + bufferPos;
            int first = readBytes(header, 0, 27);
            if (first == 0) {
                return false;
            }
            if (first >= 4 && (header[0] != 'O' || header[1] != 'g' || header[2] != 'g' || header[3] != 'S')) {
                throw new AudioFormatException(offset == 0
                        ? "This is not an Ogg file."
                        : "Expected an Ogg page at byte " + offset + " but found other data.");
            }
            if (first < 27) {
                throw cutOff(offset);
            }
            if (header[4] != 0) {
                throw new AudioFormatException("The Ogg page at byte " + offset + " has an unknown version.");
            }
            pageFlags = header[5] & 0xFF;
            pageGranule = readLong(header, 6);
            pageSerial = readInt(header, 14);
            pageSequence = readInt(header, 18);
            int storedCrc = readInt(header, 22);
            segmentCount = header[26] & 0xFF;
            if (readBytes(header, 27, segmentCount) < segmentCount) {
                throw cutOff(offset);
            }
            int bodyLength = 0;
            for (int i = 0; i < segmentCount; i++) {
                bodyLength += header[27 + i] & 0xFF;
            }
            if (readBytes(body, 0, bodyLength) < bodyLength) {
                throw cutOff(offset);
            }
            header[22] = header[23] = header[24] = header[25] = 0;
            int crc = crc(0, header, 0, 27 + segmentCount);
            crc = crc(crc, body, 0, bodyLength);
            if (crc != storedCrc) {
                throw new AudioFormatException("The Ogg page at byte " + offset
                        + " is damaged (its checksum does not match).");
            }
            pageOffset = offset;
            return true;
        }

        private static AudioFormatException cutOff(long offset) {
            return new AudioFormatException("The Ogg stream is cut off in the middle of a page at byte " + offset
                    + ".");
        }

        private int readBytes(byte[] target, int offset, int length) throws IOException {
            int done = 0;
            while (done < length) {
                if (bufferPos == bufferLimit) {
                    bufferStartOffset += bufferLimit;
                    bufferPos = 0;
                    bufferLimit = 0;
                    int read = in.read(buffer, 0, buffer.length);
                    if (read == 0) {
                        // A stream that breaks the contract and returns nothing would spin here forever; the
                        // single-byte read is required to block until it has a byte or the end.
                        int single = in.read();
                        if (single >= 0) {
                            buffer[0] = (byte) single;
                            read = 1;
                        } else {
                            read = -1;
                        }
                    }
                    if (read < 0) {
                        return done;
                    }
                    bufferLimit = read;
                    continue;
                }
                int chunk = Math.min(length - done, bufferLimit - bufferPos);
                System.arraycopy(buffer, bufferPos, target, offset + done, chunk);
                bufferPos += chunk;
                done += chunk;
            }
            return done;
        }

        private static void checkInterrupt() throws InterruptedIOException {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedIOException("Opus decoding was interrupted.");
            }
        }

        static int crc(int crc, byte[] data, int offset, int length) {
            for (int i = offset, end = offset + length; i < end; i++) {
                crc = (crc << 8) ^ CRC_TABLE[((crc >>> 24) ^ data[i]) & 0xFF];
            }
            return crc;
        }

        static int readInt(byte[] data, int offset) {
            return (data[offset] & 0xFF) | (data[offset + 1] & 0xFF) << 8 | (data[offset + 2] & 0xFF) << 16
                    | (data[offset + 3] & 0xFF) << 24;
        }

        static long readLong(byte[] data, int offset) {
            return (readInt(data, offset) & 0xFFFFFFFFL) | (long) readInt(data, offset + 4) << 32;
        }
    }
}

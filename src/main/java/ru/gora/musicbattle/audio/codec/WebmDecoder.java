package ru.gora.musicbattle.audio.codec;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Objects;

/**
 * Decodes Opus audio in WebM and Matroska (.webm, .weba, .mka, .mkv, and .opus files that are really WebM): what
 * yt-dlp and browsers download. The first audio track is decoded; video and other tracks are skipped without being
 * read into memory. Output is 48 kHz planar float, like {@link OpusDecoder}, through the same libopus port.
 *
 * <p>Timing follows the Matroska specification (RFC 9559) and the Opus codec mapping. The track's CodecDelay is
 * dropped from the start; when a muxer left it out or wrote zero, the OpusHead pre-skip is dropped instead, as ffmpeg
 * does. A block's DiscardPadding is cut from its end, or from its start when negative (RFC 9559 section 5.1.3.5.7),
 * as ffmpeg 9 does too, except that ffmpeg drops the DiscardPadding of the track's first block, where it applies the
 * CodecDelay, while this keeps both: a song shorter than one packet after the pre-skip keeps its real length.
 *
 * <p>Blocks are decoded back to back, as ffmpeg outputs them, and their timestamps are not read. A song's timestamps
 * are rounded to the timestamp scale (a millisecond, usually) and some muxers stamp audio from a capture clock, so
 * they wobble around the true position; one damaged cluster timestamp can be seconds off. Filling every apparent gap
 * with packet loss concealment would make such a song longer and add a glitch at each gap, and nothing in the song
 * tells a wobble or a damaged timestamp from a block that is really missing. Back to back, the song is exactly its
 * packets, which is also what the Ogg Opus file of the same packets gives.
 *
 * <p>Supported: an EBML header with DocType "webm" or "matroska", a Segment and Clusters of known or unknown size,
 * SimpleBlock and BlockGroup, and Xiph, EBML and fixed-size lacing. A track in another codec (Vorbis in older WebM,
 * AAC in most .mkv files) and a compressed or encrypted track are refused as unsupported, with the codec named.
 */
public final class WebmDecoder {
    static final int ID_EBML = 0x1A45DFA3;
    static final int ID_DOCTYPE = 0x4282;
    static final int ID_SEGMENT = 0x18538067;
    static final int ID_TRACKS = 0x1654AE6B;
    static final int ID_TRACK_ENTRY = 0xAE;
    static final int ID_TRACK_NUMBER = 0xD7;
    static final int ID_TRACK_TYPE = 0x83;
    static final int ID_CODEC_ID = 0x86;
    static final int ID_CODEC_PRIVATE = 0x63A2;
    static final int ID_CODEC_DELAY = 0x56AA;
    static final int ID_AUDIO = 0xE1;
    static final int ID_SAMPLING_FREQUENCY = 0xB5;
    static final int ID_CHANNELS = 0x9F;
    static final int ID_BIT_DEPTH = 0x6264;
    static final int ID_CONTENT_ENCODINGS = 0x6D80;
    static final int ID_CONTENT_ENCODING = 0x6240;
    static final int ID_CONTENT_ENCRYPTION = 0x5035;
    static final int ID_CLUSTER = 0x1F43B675;
    static final int ID_SIMPLE_BLOCK = 0xA3;
    static final int ID_BLOCK_GROUP = 0xA0;
    static final int ID_BLOCK = 0xA1;
    static final int ID_DISCARD_PADDING = 0x75A2;
    static final int ID_ENCRYPTED_BLOCK = 0xAF;
    /** The elements that may sit directly in a Cluster; anything else ends a Cluster of unknown size. */
    private static final int[] CLUSTER_CHILDREN = {0xE7, 0x5854, 0xA7, 0xAB, 0xA3, 0xA0, 0xAF, 0xEC, 0xBF};
    private static final int TRACK_TYPE_AUDIO = 2;

    private WebmDecoder() {
    }

    /**
     * Whether the first bytes are an EBML header whose DocType is "webm" or "matroska". The codec is only known once
     * the tracks are read, so {@link #open} may still refuse the file as unsupported. The DocType usually sits within
     * the first 40 bytes; a muxer that writes long size fields can push it past 64, so callers pass a few hundred.
     */
    public static boolean looksLike(byte[] head) {
        if (head == null || head.length < 5 || (head[0] & 0xFF) != 0x1A || (head[1] & 0xFF) != 0x45
                || (head[2] & 0xFF) != 0xDF || (head[3] & 0xFF) != 0xA3) {
            return false;
        }
        int pos = 4;
        int sizeLength = vintLength(head[pos] & 0xFF);
        if (sizeLength > 8 || pos + sizeLength > head.length) {
            return false;
        }
        long size = vintValue(head, pos, sizeLength);
        pos += sizeLength;
        long end = size < 0 ? head.length : Math.min(head.length, pos + size);
        // Walk the header's children up to the DocType.
        while (pos < end) {
            int idLength = vintLength(head[pos] & 0xFF);
            if (idLength > 4 || pos + idLength >= end) {
                return false;
            }
            int id = 0;
            for (int i = 0; i < idLength; i++) {
                id = id << 8 | (head[pos + i] & 0xFF);
            }
            pos += idLength;
            int length = vintLength(head[pos] & 0xFF);
            if (length > 8 || pos + length > end) {
                return false;
            }
            long childSize = vintValue(head, pos, length);
            pos += length;
            if (childSize < 0) {
                return false;
            }
            if (id == ID_DOCTYPE) {
                if (childSize > 64 || pos + childSize > head.length) {
                    return false;
                }
                String type = trimNul(new String(head, pos, (int) childSize, StandardCharsets.ISO_8859_1));
                return type.equals("webm") || type.equals("matroska");
            }
            pos += (int) Math.min(childSize, Integer.MAX_VALUE - pos);
        }
        return false;
    }

    /**
     * Reads the WebM headers up to the first cluster and returns a stream of the decoded samples. On success the
     * returned stream owns {@code in} and closes it; on failure {@code in} is left open for the caller.
     *
     * @throws UnsupportedFormatException when the first audio track is not Opus, or is compressed or encrypted; the
     *     format name says what it is, such as "WebM/MKV (AAC)"
     * @throws AudioFormatException when the bytes are not WebM/Matroska, are damaged, or hold no audio track
     */
    public static PcmStream open(InputStream in) throws IOException {
        Objects.requireNonNull(in, "in");
        Packets packets = new Packets(in);
        try {
            Track track = packets.readHeaders();
            if (!track.isOpus()) {
                return MatroskaAudio.open(packets, track);
            }
            OpusHead head = track.opusHead;
            // CodecDelay is the pre-skip in nanoseconds (RFC 9559 section 11.2). When it is missing or zero while the
            // OpusHead asks for a pre-skip, the file contradicts itself; ffmpeg then skips the OpusHead's pre-skip,
            // and so does this, because the encoder wrote that number and a muxer more likely left CodecDelay out
            // than meant it.
            long preSkip = packets.codecDelayNs > 0
                    ? Math.min(Integer.MAX_VALUE, rescaleToSamples(packets.codecDelayNs))
                    : head.preSkip;
            return new OpusPcmStream(packets, head, preSkip, "WebM Opus");
        } catch (RuntimeException e) {
            throw new AudioFormatException("The WebM headers are damaged.", e);
        }
    }

    /** Nanoseconds as 48 kHz samples, rounded to the nearest, without overflowing for any non-negative value. */
    static long rescaleToSamples(long ns) {
        long seconds = ns / 1_000_000_000L;
        long rest = ns % 1_000_000_000L;
        if (seconds > Long.MAX_VALUE / 48000 - 1) {
            return Long.MAX_VALUE;
        }
        return seconds * 48000 + (rest * 48000 + 500_000_000L) / 1_000_000_000L;
    }

    static int vintLength(int first) {
        return first == 0 ? 9 : Integer.numberOfLeadingZeros(first) - 23;
    }

    /** An element size in EBML's variable-length form, or -1 for the reserved all-ones "unknown size". */
    private static long vintValue(byte[] b, int pos, int length) {
        long value = b[pos] & (0xFF >> length);
        boolean allOnes = value == (0xFF >> length);
        for (int i = 1; i < length; i++) {
            value = value << 8 | (b[pos + i] & 0xFF);
            allOnes &= (b[pos + i] & 0xFF) == 0xFF;
        }
        return allOnes ? -1L : value;
    }

    private static String trimNul(String s) {
        int nul = s.indexOf('\0');
        return nul >= 0 ? s.substring(0, nul) : s;
    }

    /** The audio track to decode, as the track list describes it. */
    static final class Track {
        final String codec;
        /** CodecPrivate, or null. */
        final byte[] codecPrivate;
        /** From the Audio element; 0 when it does not say. */
        final double sampleRate;
        final int channels;
        final int bitDepth;
        OpusHead opusHead;

        Track(String codec, byte[] codecPrivate, double sampleRate, int channels, int bitDepth) {
            this.codec = codec;
            this.codecPrivate = codecPrivate;
            this.sampleRate = sampleRate;
            this.channels = channels;
            this.bitDepth = bitDepth;
        }

        boolean isOpus() {
            return codec.equals("A_OPUS");
        }
    }

    /** One laced frame of the chosen track: an Opus packet with its trimming. */
    private static final class Frame {
        byte[] data;
        int offset;
        int length;
        int duration;
        int trimStart;
        int trimEnd;
    }

    /** Reads the EBML element tree as a stream and hands out the frames of the first audio track. */
    static final class Packets implements OpusPcmStream.Packets {
        /** No Opus CodecPrivate comes near this; anything bigger is a damaged or hostile file. */
        private static final int MAX_CODEC_PRIVATE = 64 * 1024;
        /** A block of 256 laced 120 ms packets of 255 streams stays far below this. */
        private static final int MAX_BLOCK_BYTES = 16 << 20;
        private static final int MAX_EBML_HEADER = 4096;
        private static final long UNKNOWN = -1L;

        private final InputStream in;
        private long position;
        private boolean pendingHeader;
        private int pendingId;
        private long pendingSize;
        private long pendingStart;
        private boolean eof;

        private long segmentEnd = UNKNOWN;
        private long clusterEnd = UNKNOWN;
        private boolean inCluster;

        int trackNumber = -1;
        long codecDelayNs = -1L;
        /** Whether the track is Opus, whose frame durations DiscardPadding is spread over. */
        private boolean opus;
        /** For other codecs: the DiscardPadding of the blocks read so far, in nanoseconds. */
        long discardEndNs;

        private byte[] block = new byte[4096];
        private final byte[] skipBuffer = new byte[8192];
        private final ArrayDeque<Frame> frames = new ArrayDeque<>();
        private final ArrayDeque<Frame> spare = new ArrayDeque<>();
        private Frame current;
        private final int[] laceSizes = new int[256];

        Packets(InputStream in) {
            this.in = in;
        }

        Track readHeaders() throws IOException {
            // The EBML header.
            if (!readElementHeader()) {
                throw new AudioFormatException("The file is empty.");
            }
            if (pendingId != ID_EBML) {
                throw new AudioFormatException("This is not a WebM or Matroska file.");
            }
            consumePending();
            if (pendingSize == UNKNOWN || pendingSize > MAX_EBML_HEADER) {
                throw new AudioFormatException("The EBML header is damaged.");
            }
            long headerEnd = position + pendingSize;
            String docType = null;
            while (position < headerEnd) {
                readChildHeader(headerEnd);
                if (pendingId == ID_DOCTYPE) {
                    docType = trimNul(new String(readBytes(checkedSize(256)), StandardCharsets.ISO_8859_1)).trim();
                } else {
                    skip(pendingSize);
                }
            }
            if (docType == null || !(docType.equals("webm") || docType.equals("matroska"))) {
                throw new UnsupportedFormatException("WebM/MKV");
            }
            // The Segment; anything before it at the top level is skipped.
            while (true) {
                checkInterrupt();
                if (!readElementHeader()) {
                    throw new AudioFormatException("The WebM file has no segment.");
                }
                consumePending();
                if (pendingId == ID_SEGMENT) {
                    segmentEnd = pendingSize == UNKNOWN ? UNKNOWN : position + pendingSize;
                    break;
                }
                skipElement();
            }
            // Segment children up to the first cluster.
            Track head = null;
            while (true) {
                checkInterrupt();
                if (!readSegmentChild()) {
                    if (head == null) {
                        throw new AudioFormatException("The WebM file has no audio track.");
                    }
                    // Headers without any cluster: a valid, empty song.
                    eof = true;
                    return head;
                }
                switch (pendingId) {
                    case ID_TRACKS -> {
                        if (head == null) {
                            head = readTracks();
                        } else {
                            skipElement();
                        }
                    }
                    case ID_CLUSTER -> {
                        if (head == null) {
                            throw new AudioFormatException("The WebM file has audio before its track list.");
                        }
                        enterCluster();
                        return head;
                    }
                    default -> skipElement();
                }
            }
        }

        private Track readTracks() throws IOException {
            long end = elementEnd();
            if (end == UNKNOWN) {
                throw new AudioFormatException("The WebM track list has no size.");
            }
            String firstAudioCodec = null;
            boolean encrypted = false;
            boolean compressed = false;
            byte[] priv = null;
            int number = -1;
            long delay = -1L;
            double rate = 0;
            long channels = 0;
            long bitDepth = 0;
            while (position < end) {
                readChildHeader(end);
                if (pendingId != ID_TRACK_ENTRY || firstAudioCodec != null) {
                    skip(pendingSize);
                    continue;
                }
                long entryEnd = position + pendingSize;
                int entryNumber = -1;
                long type = -1;
                String codec = null;
                byte[] entryPrivate = null;
                long entryDelay = -1L;
                boolean entryEncrypted = false;
                boolean entryCompressed = false;
                double entryRate = 0;
                long entryChannels = 0;
                long entryBitDepth = 0;
                while (position < entryEnd) {
                    readChildHeader(entryEnd);
                    switch (pendingId) {
                        case ID_TRACK_NUMBER -> entryNumber = (int) Math.min(Integer.MAX_VALUE,
                                readUnsigned(pendingSize));
                        case ID_TRACK_TYPE -> type = readUnsigned(pendingSize);
                        case ID_CODEC_ID -> codec = trimNul(new String(readBytes(checkedSize(256)),
                                StandardCharsets.ISO_8859_1));
                        case ID_CODEC_PRIVATE -> entryPrivate = readBytes(checkedSize(MAX_CODEC_PRIVATE));
                        case ID_CODEC_DELAY -> entryDelay = readUnsigned(pendingSize);
                        case ID_AUDIO -> {
                            // The sampling frequency, channels and bit depth, which PCM and AAC without a
                            // configuration need; the other codecs say them in their own headers.
                            long audioEnd = position + pendingSize;
                            while (position < audioEnd) {
                                readChildHeader(audioEnd);
                                switch (pendingId) {
                                    case ID_SAMPLING_FREQUENCY -> entryRate = readFloat(pendingSize);
                                    case ID_CHANNELS -> entryChannels = readUnsigned(pendingSize);
                                    case ID_BIT_DEPTH -> entryBitDepth = readUnsigned(pendingSize);
                                    default -> skip(pendingSize);
                                }
                            }
                        }
                        case ID_CONTENT_ENCODINGS -> {
                            // Any ContentEncoding changes the bytes of every frame; encryption says so by its own
                            // element, anything else is compression (header stripping, zlib).
                            long encodingsEnd = position + pendingSize;
                            while (position < encodingsEnd) {
                                readChildHeader(encodingsEnd);
                                if (pendingId == ID_CONTENT_ENCODING) {
                                    long encodingEnd = position + pendingSize;
                                    boolean encryption = false;
                                    while (position < encodingEnd) {
                                        readChildHeader(encodingEnd);
                                        encryption |= pendingId == ID_CONTENT_ENCRYPTION;
                                        skip(pendingSize);
                                    }
                                    entryEncrypted |= encryption;
                                    entryCompressed |= !encryption;
                                } else {
                                    skip(pendingSize);
                                }
                            }
                        }
                        default -> skip(pendingSize);
                    }
                }
                if (type != TRACK_TYPE_AUDIO) {
                    continue;
                }
                firstAudioCodec = codec == null ? "" : codec;
                number = entryNumber;
                priv = entryPrivate;
                delay = entryDelay;
                encrypted = entryEncrypted;
                compressed = entryCompressed;
                rate = entryRate;
                channels = entryChannels;
                bitDepth = entryBitDepth;
            }
            if (firstAudioCodec == null) {
                throw new AudioFormatException("The WebM file has no audio track.");
            }
            if (!MatroskaAudio.reads(firstAudioCodec)) {
                throw new UnsupportedFormatException("WebM/MKV (" + codecName(firstAudioCodec) + ")");
            }
            if (encrypted) {
                throw new UnsupportedFormatException("WebM/MKV (encrypted)");
            }
            if (compressed) {
                throw new UnsupportedFormatException("WebM/MKV (compressed)");
            }
            if (number <= 0) {
                throw new AudioFormatException("The WebM audio track has no track number.");
            }
            Track track = new Track(firstAudioCodec, priv, rate, (int) Math.min(channels, 255),
                    (int) Math.min(bitDepth, 64));
            if (track.isOpus()) {
                if (priv == null) {
                    throw new AudioFormatException("The WebM Opus track has no Opus header.");
                }
                track.opusHead = OpusHead.parse(priv, 0, priv.length);
            }
            opus = track.isOpus();
            trackNumber = number;
            codecDelayNs = delay;
            return track;
        }

        /** A codec ID (RFC 9559 and the Matroska codec registry) as players know the codec. */
        private static String codecName(String codecId) {
            return switch (codecId) {
                case "A_VORBIS" -> "Vorbis";
                case "A_MPEG/L3" -> "MP3";
                case "A_MPEG/L2" -> "MP2";
                case "A_FLAC" -> "FLAC";
                case "A_ALAC" -> "ALAC";
                case "A_AC3" -> "AC-3";
                case "A_EAC3" -> "E-AC-3";
                case "A_TRUEHD" -> "TrueHD";
                case "A_DTS" -> "DTS";
                case "" -> "?";
                default -> codecId.startsWith("A_AAC") ? "AAC" : codecId.startsWith("A_PCM") ? "PCM" : codecId;
            };
        }

        private int checkedSize(int max) throws AudioFormatException {
            if (pendingSize == UNKNOWN || pendingSize > max) {
                throw new AudioFormatException("A WebM header element at byte " + pendingStart + " is too large ("
                        + pendingSize + " bytes).");
            }
            return (int) pendingSize;
        }

        private void enterCluster() {
            inCluster = true;
            clusterEnd = pendingSize == UNKNOWN ? UNKNOWN : position + pendingSize;
        }

        @Override
        public boolean next() throws IOException {
            if (current != null) {
                spare.add(current);
                current = null;
            }
            while (frames.isEmpty()) {
                checkInterrupt();
                if (eof || !readBlocks()) {
                    eof = true;
                    return false;
                }
            }
            current = frames.poll();
            return true;
        }

        @Override
        public byte[] data() {
            return current.data;
        }

        @Override
        public int offset() {
            return current.offset;
        }

        @Override
        public int length() {
            return current.length;
        }

        @Override
        public int trimStart() {
            return current.trimStart;
        }

        @Override
        public int trimEnd() {
            return current.trimEnd;
        }

        @Override
        public void close() throws IOException {
            in.close();
        }

        /** Reads elements until a block of the chosen track yields frames; false at the end of the segment. */
        private boolean readBlocks() throws IOException {
            while (true) {
                checkInterrupt();
                if (inCluster) {
                    if (clusterEnd != UNKNOWN && position >= clusterEnd) {
                        inCluster = false;
                        continue;
                    }
                    if (!readElementHeader()) {
                        return false;
                    }
                    if (clusterEnd == UNKNOWN && !isClusterChild(pendingId)) {
                        // A cluster of unknown size ends where an element that cannot be its child begins.
                        inCluster = false;
                        continue;
                    }
                    consumePending();
                    if (clusterEnd != UNKNOWN && pendingSize != UNKNOWN && position + pendingSize > clusterEnd) {
                        throw new AudioFormatException("A WebM block runs past the end of its cluster at byte "
                                + pendingStart + ".");
                    }
                    switch (pendingId) {
                        case ID_SIMPLE_BLOCK -> {
                            if (readBlock(pendingSize)) {
                                return true;
                            }
                        }
                        case ID_BLOCK_GROUP -> {
                            if (readBlockGroup()) {
                                return true;
                            }
                        }
                        default -> skipElement();
                    }
                } else {
                    if (!readSegmentChild()) {
                        return false;
                    }
                    if (pendingId == ID_CLUSTER) {
                        enterCluster();
                    } else {
                        skipElement();
                    }
                }
            }
        }

        private static boolean isClusterChild(int id) {
            for (int child : CLUSTER_CHILDREN) {
                if (child == id) {
                    return true;
                }
            }
            return false;
        }

        /** Reads the next child of the Segment into the pending header; false at its end. */
        private boolean readSegmentChild() throws IOException {
            if (segmentEnd != UNKNOWN && position >= segmentEnd) {
                return false;
            }
            if (!readElementHeader()) {
                return false;
            }
            if (pendingId == ID_SEGMENT || pendingId == ID_EBML) {
                // A second EBML header or segment after a segment of unknown size: the next file of a
                // concatenation, which is not part of this song.
                return false;
            }
            consumePending();
            if (segmentEnd != UNKNOWN && pendingSize != UNKNOWN && position + pendingSize > segmentEnd) {
                throw new AudioFormatException("A WebM element runs past the end of its segment at byte "
                        + pendingStart + ".");
            }
            return true;
        }

        private boolean readBlockGroup() throws IOException {
            long end = elementEnd();
            if (end == UNKNOWN) {
                throw new AudioFormatException("A WebM block group at byte " + pendingStart + " has no size.");
            }
            long discardPadding = 0L;
            boolean haveBlock = false;
            // DiscardPadding may come after the Block, so the Block's frames are trimmed once the group is read.
            int queuedBefore = frames.size();
            while (position < end) {
                readChildHeader(end);
                if (pendingId == ID_BLOCK && !haveBlock) {
                    haveBlock = true;
                    readBlock(pendingSize);
                } else if (pendingId == ID_DISCARD_PADDING) {
                    discardPadding = readSigned(pendingSize);
                } else if (pendingId == ID_ENCRYPTED_BLOCK) {
                    throw new UnsupportedFormatException("WebM/MKV (encrypted)");
                } else {
                    skip(pendingSize);
                }
            }
            if (frames.size() > queuedBefore && discardPadding != 0) {
                if (opus) {
                    applyDiscardPadding(queuedBefore, discardPadding);
                } else if (discardPadding > 0) {
                    // Other codecs' frames do not say their length before they are decoded; the padding, which a
                    // muxer writes on the last block, is cut from the end of the decoded song (MatroskaAudio).
                    discardEndNs = discardEndNs + discardPadding < 0 ? Long.MAX_VALUE : discardEndNs + discardPadding;
                }
            }
            return frames.size() > queuedBefore;
        }

        /** Spreads a block's DiscardPadding over its frames: from the end when positive, from the start if not. */
        private void applyDiscardPadding(int firstFrame, long paddingNs) {
            long samples = rescaleToSamples(paddingNs == Long.MIN_VALUE ? Long.MAX_VALUE : Math.abs(paddingNs));
            Frame[] queued = frames.toArray(new Frame[0]);
            for (int k = firstFrame; k < queued.length && samples > 0; k++) {
                Frame f = queued[paddingNs > 0 ? queued.length - 1 - (k - firstFrame) : k];
                int cut = (int) Math.min(samples, Math.max(0, f.duration - f.trimEnd - f.trimStart));
                if (paddingNs > 0) {
                    f.trimEnd += cut;
                } else {
                    f.trimStart += cut;
                }
                samples -= cut;
            }
        }

        /**
         * Reads a SimpleBlock or Block of {@code size} bytes. Blocks of other tracks are skipped; frames of the chosen
         * track are queued.
         *
         * @return whether frames were queued
         */
        private boolean readBlock(long size) throws IOException {
            if (size == UNKNOWN || size < 4) {
                throw new AudioFormatException("A WebM block at byte " + pendingStart + " is damaged.");
            }
            long blockEnd = position + size;
            // The track number is an EBML variable-length integer.
            int first = readByte();
            int length = vintLength(first);
            if (length > 8) {
                throw new AudioFormatException("A WebM block at byte " + pendingStart + " is damaged.");
            }
            long track = first & (0xFF >> length);
            for (int i = 1; i < length; i++) {
                track = (track << 8) | readByte();
            }
            if (track != trackNumber) {
                skip(blockEnd - position);
                return false;
            }
            if (size > MAX_BLOCK_BYTES) {
                throw new AudioFormatException("A WebM audio block is larger than " + (MAX_BLOCK_BYTES >> 20) + " MB.");
            }
            // The block's timestamp (two bytes, relative to its cluster's) is not used: blocks play back to back.
            readByte();
            readByte();
            int flags = readByte();
            int payload = (int) (blockEnd - position);
            if (payload < 0) {
                throw new AudioFormatException("A WebM block at byte " + pendingStart + " is damaged.");
            }
            // Every queued frame points into the block buffer, so it is reused only once all are handed out.
            byte[] buf = readPayload(payload, frames.isEmpty());
            int lacing = (flags >> 1) & 3;
            int count;
            int pos = 0;
            if (lacing == 0) {
                count = 1;
                laceSizes[0] = payload;
            } else {
                if (payload < 1) {
                    throw new AudioFormatException("A laced WebM block at byte " + pendingStart + " is damaged.");
                }
                count = (buf[pos++] & 0xFF) + 1;
                long total = 0;
                if (lacing == 1) {
                    // Xiph lacing: each size but the last as a run of 255s and a final byte.
                    for (int i = 0; i < count - 1; i++) {
                        int s = 0;
                        int b;
                        do {
                            if (pos >= payload) {
                                throw new AudioFormatException("A laced WebM block is damaged.");
                            }
                            b = buf[pos++] & 0xFF;
                            s += b;
                        } while (b == 255);
                        laceSizes[i] = s;
                        total += s;
                    }
                } else if (lacing == 3) {
                    // EBML lacing: the first size as a variable-length integer, the others as signed differences.
                    long previous = 0;
                    for (int i = 0; i < count - 1; i++) {
                        if (pos >= payload) {
                            throw new AudioFormatException("A laced WebM block is damaged.");
                        }
                        int l = vintLength(buf[pos] & 0xFF);
                        if (l > 8 || pos + l > payload) {
                            throw new AudioFormatException("A laced WebM block is damaged.");
                        }
                        long v = (buf[pos] & 0xFF) & (0xFF >> l);
                        for (int k = 1; k < l; k++) {
                            v = (v << 8) | (buf[pos + k] & 0xFF);
                        }
                        pos += l;
                        long s = i == 0 ? v : previous + (v - ((1L << (7 * l - 1)) - 1));
                        if (s < 0 || s > payload) {
                            throw new AudioFormatException("A laced WebM block is damaged.");
                        }
                        laceSizes[i] = (int) s;
                        previous = s;
                        total += s;
                    }
                } else {
                    // Fixed-size lacing: the rest of the payload split evenly.
                    int rest = payload - pos;
                    if (rest % count != 0) {
                        throw new AudioFormatException("A fixed-size laced WebM block is damaged.");
                    }
                    for (int i = 0; i < count - 1; i++) {
                        laceSizes[i] = rest / count;
                        total += rest / count;
                    }
                }
                long last = payload - pos - total;
                if (last < 0) {
                    throw new AudioFormatException("A laced WebM block is damaged.");
                }
                laceSizes[count - 1] = (int) last;
            }
            for (int i = 0; i < count; i++) {
                Frame f = spare.poll();
                if (f == null) {
                    f = new Frame();
                }
                f.data = buf;
                f.offset = pos;
                f.length = laceSizes[i];
                // A zero-length frame holds no samples and is skipped (see OpusPcmStream).
                f.duration = f.length == 0 || !opus ? 0
                        : Math.max(0, OpusPacketDecoder.packetSamples(buf, pos, f.length));
                f.trimStart = 0;
                f.trimEnd = 0;
                pos += laceSizes[i];
                frames.add(f);
            }
            return true;
        }

        /**
         * Reads a block's payload, into the reusable block buffer when {@code reuse} is set and into a buffer of its
         * own otherwise. The buffer grows only as the bytes actually arrive, so a damaged size field cannot make it
         * allocate more than the file holds.
         */
        private byte[] readPayload(int payload, boolean reuse) throws IOException {
            byte[] buf = reuse ? block : new byte[Math.min(payload, 4096)];
            int done = 0;
            while (done < payload) {
                int chunk = Math.min(payload - done, 1 << 16);
                if (buf.length < done + chunk) {
                    buf = Arrays.copyOf(buf, Math.min(Math.max(buf.length * 2, done + chunk), payload));
                }
                readFully(buf, done, chunk);
                done += chunk;
            }
            if (reuse) {
                block = buf;
            }
            return buf;
        }

        // ---- EBML primitives

        /** Reads an element header into the pending fields without consuming it; false at the end of the input. */
        private boolean readElementHeader() throws IOException {
            if (pendingHeader) {
                return true;
            }
            long start = position;
            int b = in.read();
            if (b < 0) {
                return false;
            }
            position++;
            int idLength = vintLength(b);
            if (idLength > 4) {
                throw new AudioFormatException("Expected a WebM element at byte " + start + " but found other data.");
            }
            int id = b;
            for (int i = 1; i < idLength; i++) {
                id = (id << 8) | readByte();
            }
            int s = readByte();
            int sizeLength = vintLength(s);
            if (sizeLength > 8) {
                throw new AudioFormatException("A WebM element at byte " + start + " has a damaged size.");
            }
            long size = s & (0xFF >> sizeLength);
            boolean allOnes = size == (0xFF >> sizeLength);
            for (int i = 1; i < sizeLength; i++) {
                int v = readByte();
                size = (size << 8) | v;
                allOnes &= v == 0xFF;
            }
            pendingHeader = true;
            pendingId = id;
            pendingSize = allOnes ? UNKNOWN : size;
            pendingStart = start;
            return true;
        }

        private void consumePending() {
            pendingHeader = false;
        }

        /** Reads a child header that must lie within {@code end}. */
        private void readChildHeader(long end) throws IOException {
            if (!readElementHeader()) {
                throw new AudioFormatException("The WebM file is cut off at byte " + position + ".");
            }
            consumePending();
            if (pendingSize == UNKNOWN || (end != UNKNOWN && pendingSize > end - position)) {
                throw new AudioFormatException("A WebM element at byte " + pendingStart + " runs past its parent.");
            }
        }

        private long elementEnd() {
            return pendingSize == UNKNOWN ? UNKNOWN : position + pendingSize;
        }

        /** Skips the element whose header was just consumed. */
        private void skipElement() throws IOException {
            if (pendingSize == UNKNOWN) {
                throw new AudioFormatException("A WebM element at byte " + pendingStart + " has no size.");
            }
            skip(pendingSize);
        }

        private int readByte() throws IOException {
            int b = in.read();
            if (b < 0) {
                throw new AudioFormatException("The WebM file is cut off at byte " + position + ".");
            }
            position++;
            return b;
        }

        private long readUnsigned(long size) throws IOException {
            if (size > 8) {
                throw new AudioFormatException("A WebM integer at byte " + pendingStart + " is too long.");
            }
            long v = 0;
            for (int i = 0; i < size; i++) {
                v = (v << 8) | readByte();
            }
            return v;
        }

        /** An EBML float element: 4 or 8 bytes, big-endian IEEE 754; 0 for an empty one. */
        private double readFloat(long size) throws IOException {
            if (size == 0) {
                return 0;
            }
            if (size != 4 && size != 8) {
                throw new AudioFormatException("A WebM float at byte " + pendingStart + " has " + size + " bytes.");
            }
            long bits = readUnsigned(size);
            return size == 4 ? Float.intBitsToFloat((int) bits) : Double.longBitsToDouble(bits);
        }

        private long readSigned(long size) throws IOException {
            if (size == 0) {
                return 0;
            }
            long v = readUnsigned(size);
            int shift = 64 - 8 * (int) size;
            return (v << shift) >> shift;
        }

        private byte[] readBytes(int size) throws IOException {
            byte[] b = new byte[size];
            readFully(b, 0, size);
            return b;
        }

        private void readFully(byte[] b, int off, int len) throws IOException {
            int done = 0;
            while (done < len) {
                int n = in.read(b, off + done, len - done);
                if (n < 0) {
                    throw new AudioFormatException("The WebM file is cut off at byte " + (position + done) + ".");
                }
                if (n == 0) {
                    // A stream that returns nothing would spin here; the single-byte read must block or end.
                    int single = in.read();
                    if (single < 0) {
                        throw new AudioFormatException("The WebM file is cut off at byte " + (position + done) + ".");
                    }
                    b[off + done] = (byte) single;
                    n = 1;
                }
                done += n;
            }
            position += len;
        }

        /**
         * Skips n bytes by reading them. InputStream.skip is not used: a file stream skips past its end without a
         * word, so a file cut off inside a skipped element (a video block) would end the song early instead of being
         * reported as cut off.
         */
        private void skip(long n) throws IOException {
            long left = n;
            while (left > 0) {
                checkInterrupt();
                int r = in.read(skipBuffer, 0, (int) Math.min(left, skipBuffer.length));
                if (r < 0) {
                    throw new AudioFormatException("The WebM file is cut off at byte " + (position + n - left) + ".");
                }
                if (r == 0) {
                    int single = in.read();
                    if (single < 0) {
                        throw new AudioFormatException("The WebM file is cut off at byte " + (position + n - left)
                                + ".");
                    }
                    r = 1;
                }
                left -= r;
            }
            position += n;
        }

        private static void checkInterrupt() throws InterruptedIOException {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedIOException("WebM decoding was interrupted.");
            }
        }
    }
}

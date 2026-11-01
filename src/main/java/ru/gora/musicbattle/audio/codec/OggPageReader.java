package ru.gora.musicbattle.audio.codec;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.util.Arrays;

/**
 * Reads the pages of an Ogg stream and puts the packets of one logical Vorbis stream back together.
 *
 * <p>Every page's checksum is verified and every page of the chosen stream must follow the previous one without a gap
 * in its sequence number: a song with a flipped byte or a missing page is reported as damaged instead of being played
 * with a hole in it. Pages that belong to other logical streams (a video track or a skeleton next to the audio) are
 * checked and skipped. Reading stops at the end of the first Vorbis stream; anything chained after it is never read.
 *
 * <p>A stream that simply stops at a page boundary without its end-of-stream flag is accepted, because stb_vorbis,
 * libvorbis and ffmpeg all play such files; a stream that stops in the middle of a page is damaged.
 *
 * <p>Read the way Minecraft reads it ({@link VorbisDecoder.Timeline#MINECRAFT}), two things change. A stream cut off in
 * the middle of a page (an interrupted download) ends at the last whole page, with the packet it was in the middle of
 * dropped, because the game's decoder plays such a file up to there and stops without complaint. And in a file the
 * game can play at all, a page of another logical stream among the audio is refused instead of skipped: the game's
 * decoder stumbles over it, loses part of the song and plays the rest early, which no copy can follow.
 */
final class OggPageReader {
    static final int FLAG_CONTINUED = 1;
    static final int FLAG_FIRST = 2;
    static final int FLAG_LAST = 4;

    /** No sane Vorbis packet comes near this; a bigger one is a damaged or hostile file, not a song. */
    static final int MAX_PACKET_BYTES = 16 << 20;

    /**
     * Pages are skipped and comment headers discarded without being stored, so the only thing a file can make this
     * class allocate is one packet; this bounds how many foreign pages it will read while looking for the Vorbis one.
     */
    private static final int MAX_LEADING_FOREIGN_PAGES = 4096;

    private static final int[] CRC_TABLE = new int[256];

    static {
        // The Ogg checksum is CRC-32 with polynomial 0x04c11db7, fed most significant bit first, starting from zero
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
    /** Whether the stream is read as Minecraft reads it; see the class comment. */
    private final boolean minecraft;
    /**
     * Whether Minecraft could play this file at all: its first page starts the Vorbis stream, and no page of another
     * stream comes before the audio. JOrbis, the game's decoder, gives up on anything else before the first sample.
     */
    private boolean minecraftPlayable;
    private boolean audioStarted;
    /** The granule position of this stream's end-of-stream page once it has been read, or {@link Long#MIN_VALUE}. */
    private long endGranule = Long.MIN_VALUE;
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
    private int lastCompleteSegment;
    private int nextSegment;
    private int bodyPos;
    private boolean pageLoaded;

    // The chosen logical stream.
    private boolean streamChosen;
    private int serial;
    private int expectedSequence;
    private boolean dataPagesStarted;
    private boolean streamEnded;
    private boolean partialPacket;

    // The packet being assembled, and the facts about it that the decoder needs.
    private byte[] packet = new byte[4096];
    private int packetLength;
    private boolean packetKeepWhole;
    private long packetGranule;
    private boolean packetEndsStream;
    /** Where the packet started, so a message about an oversized packet can point at it. */
    private long packetPageOffset;

    OggPageReader(InputStream in, boolean minecraft) {
        this.in = in;
        this.minecraft = minecraft;
    }

    /** Whether Minecraft's own decoder could play this file at all; see {@link #minecraftPlayable}. */
    boolean minecraftPlayable() {
        return minecraftPlayable;
    }

    /** Called once the three Vorbis headers are read: every packet from here on is audio. */
    void audioStarts() {
        audioStarted = true;
    }

    /**
     * The granule position of the end-of-stream page, once a page carrying that flag has been read, or
     * {@link Long#MIN_VALUE} before. Minecraft never plays more samples than this number, counted from its first.
     */
    long endGranule() {
        return endGranule;
    }

    /**
     * Finds the first logical stream whose first packet is a Vorbis identification header. In a multiplexed file
     * every stream announces itself with a beginning-of-stream page before any data page, so only those are looked at.
     */
    void chooseVorbisStream() throws IOException {
        String otherCodec = null;
        if (!readPage()) {
            throw new AudioFormatException("The file is empty.");
        }
        if ((pageFlags & FLAG_FIRST) == 0) {
            throw new AudioFormatException("The file does not start at the beginning of an Ogg stream.");
        }
        for (int pages = 0; ; pages++) {
            if ((pageFlags & FLAG_FIRST) == 0) {
                throw noVorbis(otherCodec);
            }
            if (isVorbisIdentification()) {
                streamChosen = true;
                serial = pageSerial;
                expectedSequence = pageSequence + 1;
                // JOrbis takes the first page of the file for the Vorbis stream; a foreign one before it stops the game.
                minecraftPlayable = pages == 0;
                startPage();
                return;
            }
            if (otherCodec == null) {
                otherCodec = codecName();
            }
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedIOException("Vorbis decoding was interrupted.");
            }
            if (pages >= MAX_LEADING_FOREIGN_PAGES || !readPage()) {
                throw noVorbis(otherCodec);
            }
        }
    }

    /**
     * Finds the first logical stream whose first packet is an Ogg FLAC identification header ({@code 0x7F "FLAC"}), as
     * {@link #chooseVorbisStream} finds a Vorbis one; {@link OggFlacDecoder} reads its packets from there.
     */
    void chooseFlacStream() throws IOException {
        if (!readPage()) {
            throw new AudioFormatException("The file is empty.");
        }
        if ((pageFlags & FLAG_FIRST) == 0) {
            throw new AudioFormatException("The file does not start at the beginning of an Ogg stream.");
        }
        for (int pages = 0; ; pages++) {
            if ((pageFlags & FLAG_FIRST) == 0) {
                throw new AudioFormatException("The Ogg file holds no FLAC audio.");
            }
            if (segmentCount > 0 && startsWith("\u007fFLAC")) {
                streamChosen = true;
                serial = pageSerial;
                expectedSequence = pageSequence + 1;
                startPage();
                return;
            }
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedIOException("FLAC decoding was interrupted.");
            }
            if (pages >= MAX_LEADING_FOREIGN_PAGES || !readPage()) {
                throw new AudioFormatException("The Ogg file holds no FLAC audio.");
            }
        }
    }

    /** Names the codec when it is one players meet, so they know to convert the file rather than retry it. */
    private static AudioFormatException noVorbis(String otherCodec) {
        return new AudioFormatException(otherCodec == null
                ? "The Ogg file holds no Vorbis audio."
                : "The Ogg file holds " + otherCodec + " audio, not Vorbis.");
    }

    /**
     * Assembles the next packet of the chosen stream.
     *
     * @param keepWhole false keeps only the first 64 bytes, for the comment header: it may carry megabytes of cover
     *                  art that nothing here needs, and must not be refused for being big
     * @return false at the end of the logical stream
     */
    boolean nextPacket(boolean keepWhole) throws IOException {
        packetLength = 0;
        packetKeepWhole = keepWhole;
        packetGranule = -1L;
        packetEndsStream = false;
        packetPageOffset = pageOffset;
        while (true) {
            if (!pageLoaded || nextSegment >= segmentCount) {
                if (pageLoaded && (pageFlags & FLAG_LAST) != 0) {
                    streamEnded = true;
                }
                if (streamEnded || !loadNextOwnPage()) {
                    if (partialPacket && !minecraft) {
                        throw new AudioFormatException("The Ogg stream ends in the middle of a packet.");
                    }
                    // JOrbis never sees such a packet whole, so Minecraft plays up to the one before it.
                    return false;
                }
                continue;
            }
            int length = header[27 + nextSegment] & 0xFF;
            append(body, bodyPos, length);
            bodyPos += length;
            nextSegment++;
            if (length < 255) {
                partialPacket = false;
                if (nextSegment - 1 == lastCompleteSegment) {
                    // The granule position of a page belongs to the last packet that is completed on it.
                    packetGranule = pageGranule;
                    packetEndsStream = (pageFlags & FLAG_LAST) != 0;
                }
                return true;
            }
            partialPacket = true;
        }
    }

    byte[] packetData() {
        return packet;
    }

    int packetLength() {
        return packetLength;
    }

    /** The granule position of the page this packet completes, or -1 when a later packet on that page carries it. */
    long packetGranule() {
        return packetGranule;
    }

    /** True for the last packet of a page marked as the end of the stream. */
    boolean packetEndsStream() {
        return packetEndsStream;
    }

    private void append(byte[] source, int offset, int length) throws AudioFormatException {
        if (length == 0) {
            return;
        }
        int limit = packetKeepWhole ? MAX_PACKET_BYTES : 64;
        int kept = Math.min(length, limit - packetLength);
        if (kept < length && packetKeepWhole) {
            throw new AudioFormatException("A Vorbis packet near byte " + packetPageOffset
                    + " is larger than " + (MAX_PACKET_BYTES >> 20) + " MB.");
        }
        if (kept <= 0) {
            return;
        }
        if (packetLength + kept > packet.length) {
            packet = Arrays.copyOf(packet, Math.min(MAX_PACKET_BYTES, Math.max(packet.length * 2, packetLength + kept)));
        }
        System.arraycopy(source, offset, packet, packetLength, kept);
        packetLength += kept;
    }

    private boolean loadNextOwnPage() throws IOException {
        while (true) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedIOException("Vorbis decoding was interrupted.");
            }
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
                if (!audioStarted) {
                    // JOrbis cannot read the headers past another stream's page, so the game never plays this file.
                    minecraftPlayable = false;
                } else if (minecraft && minecraftPlayable) {
                    throw new AudioFormatException("The Ogg page at byte " + pageOffset + " belongs to another stream:"
                            + " Minecraft would skip part of the song there and play the rest early.");
                }
                continue;
            }
            if (beginning) {
                throw new AudioFormatException("The Vorbis stream starts twice (page at byte " + pageOffset + ").");
            }
            if (pageSequence != expectedSequence) {
                throw new AudioFormatException("An Ogg page is missing before byte " + pageOffset
                        + " (expected page " + Integer.toUnsignedString(expectedSequence)
                        + ", found " + Integer.toUnsignedString(pageSequence) + ").");
            }
            expectedSequence = pageSequence + 1;
            if ((pageFlags & FLAG_LAST) != 0) {
                endGranule = pageGranule;
            }
            boolean continued = (pageFlags & FLAG_CONTINUED) != 0;
            if (continued != partialPacket) {
                throw new AudioFormatException(continued
                        ? "The Ogg page at byte " + pageOffset + " continues a packet that was never started."
                        : "The Ogg page at byte " + pageOffset + " drops the end of the previous packet.");
            }
            startPage();
            return true;
        }
    }

    private void startPage() {
        nextSegment = 0;
        bodyPos = 0;
        pageLoaded = true;
        lastCompleteSegment = -1;
        for (int i = segmentCount - 1; i >= 0; i--) {
            if ((header[27 + i] & 0xFF) < 255) {
                lastCompleteSegment = i;
                break;
            }
        }
        if (streamChosen && (pageFlags & FLAG_FIRST) != 0) {
            dataPagesStarted = false;
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
        // JOrbis waits for a whole page before it looks at one, so a file that stops part way through its last page
        // just ends for Minecraft, whatever those last bytes are.
        if (first < 27 && minecraft && audioStarted) {
            return false;
        }
        if (first >= 4 && (header[0] != 'O' || header[1] != 'g' || header[2] != 'g' || header[3] != 'S')) {
            throw new AudioFormatException(offset == 0
                    ? "This is not an Ogg file."
                    : "Expected an Ogg page at byte " + offset + " but found other data.");
        }
        if (first < 27) {
            throw new AudioFormatException("The Ogg stream is cut off in the middle of a page at byte " + offset + ".");
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
            return cutOff(offset);
        }
        int bodyLength = 0;
        for (int i = 0; i < segmentCount; i++) {
            bodyLength += header[27 + i] & 0xFF;
        }
        if (readBytes(body, 0, bodyLength) < bodyLength) {
            return cutOff(offset);
        }
        header[22] = header[23] = header[24] = header[25] = 0;
        int crc = crc(0, header, 0, 27 + segmentCount);
        crc = crc(crc, body, 0, bodyLength);
        if (crc != storedCrc) {
            throw new AudioFormatException("The Ogg page at byte " + offset + " is damaged (its checksum does not match).");
        }
        pageOffset = offset;
        pageLoaded = false;
        return true;
    }

    /**
     * The input ended inside a page. Damage when the specification is followed; for Minecraft the end of the song,
     * once the audio has begun (a file cut inside its headers is one the game cannot play at all).
     */
    private boolean cutOff(long offset) throws AudioFormatException {
        if (minecraft && audioStarted) {
            return false;
        }
        throw new AudioFormatException("The Ogg stream is cut off in the middle of a page at byte " + offset + ".");
    }

    private boolean isVorbisIdentification() {
        int length = header[27] & 0xFF;
        return segmentCount > 0 && length >= 7 && body[0] == 1 && body[1] == 'v' && body[2] == 'o'
                && body[3] == 'r' && body[4] == 'b' && body[5] == 'i' && body[6] == 's';
    }

    private String codecName() {
        if (segmentCount == 0) {
            return null;
        }
        if (startsWith("OpusHead")) {
            return "Opus";
        }
        if (startsWith("\u007fFLAC")) {
            return "FLAC";
        }
        if (startsWith("Speex   ")) {
            return "Speex";
        }
        return null;
    }

    private boolean startsWith(String magic) {
        if ((header[27] & 0xFF) < magic.length()) {
            return false;
        }
        for (int i = 0; i < magic.length(); i++) {
            if (body[i] != (byte) magic.charAt(i)) {
                return false;
            }
        }
        return true;
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

    static int crc(int crc, byte[] data, int offset, int length) {
        for (int i = offset, end = offset + length; i < end; i++) {
            crc = (crc << 8) ^ CRC_TABLE[((crc >>> 24) ^ data[i]) & 0xFF];
        }
        return crc;
    }

    private static int readInt(byte[] data, int offset) {
        return (data[offset] & 0xFF) | (data[offset + 1] & 0xFF) << 8 | (data[offset + 2] & 0xFF) << 16
                | (data[offset + 3] & 0xFF) << 24;
    }

    private static long readLong(byte[] data, int offset) {
        return (readInt(data, offset) & 0xFFFFFFFFL) | (long) readInt(data, offset + 4) << 32;
    }
}

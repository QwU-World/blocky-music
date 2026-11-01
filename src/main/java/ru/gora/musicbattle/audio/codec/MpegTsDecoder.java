package ru.gora.musicbattle.audio.codec;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;

/**
 * Reads the sound out of an MPEG transport stream (ISO/IEC 13818-1): the joined segments of an HLS download (yt-dlp
 * leaves them so, under a .mp4 or .m4a name, when ffmpeg is missing), TV and camcorder recordings, Blu-ray and AVCHD
 * .m2ts files. Packets of 188 bytes, 192 (a four-byte time stamp in front, .m2ts) or 204 (sixteen bytes of error
 * correction after) are read; the program table is found through the PAT, and the first audio stream it lists in a
 * codec this package decodes is handed, PES payload after PES payload, to that codec's decoder:
 * <ul>
 *   <li>AAC in ADTS (stream type 0x0F) to {@link AdtsDecoder}, as one ADTS byte stream. ffmpeg's decode of a transport
 *       stream is exactly the decode of its ADTS payload (measured on every AAC file of this work's corpus);</li>
 *   <li>MPEG audio, Layer I, II or III (stream types 0x03 and 0x04), to {@link Mp3Decoder} as bare frames, the way
 *       ffmpeg decodes MPEG audio from a container: nothing is trimmed, as no gapless tag exists in a TS;</li>
 *   <li>Opus (a private stream registered as "Opus", in the framing of the Opus project's specification for Opus in
 *       MPEG-2 transport streams: an access unit after a control header with optional start and end trims) to the
 *       Opus decoder, with the trims each control header gives.</li>
 * </ul>
 *
 * <p>Timing. Packets of the chosen stream are taken in file order, and PES payloads are joined back to back, as
 * ffmpeg outputs them; time stamps are not read, so a gap between two joined HLS segments is not filled. Opus is the
 * one place where this follows the format rather than ffmpeg 9: ffmpeg ignores the start_trim and end_trim of the Opus
 * control header, so its decode of an Opus TS starts with the encoder's 312-sample pre-skip and ends with the last
 * packet's padding, both of which the Ogg and WebM files of the same packets drop. Here they are dropped, as the
 * specification says, so an Opus TS decodes to the same samples as the Ogg Opus file made from the same packets.
 * Trims inside the song are ignored, as ffmpeg ignores them: pieces cut by ffmpeg's segment muxer and joined carry
 * the pre-skip's start trim again at every join, where the decoder runs on and the samples are the song's own, so a
 * start trim counts only before the first kept sample and an end trim only on the last access unit (see
 * {@link OpusPcmStream#trimOnlyAtTheEdges}).
 *
 * <p>Robustness. A stream may start with junk or part way into a packet, and a damaged sync byte or a cut is passed by
 * looking for the next run of packets; a packet whose PID is not the chosen stream's is skipped without being copied.
 * Opened by path ({@link #open(Path)}), the file is read twice, as ffmpeg reads it: once up to its program table, to
 * learn which stream is the audio, then from the start, so the audio that comes before the first whole program table
 * (a stream cut part way into an HLS segment, or one whose first table is damaged) is kept from its first PES packet
 * on. Read once from a stream ({@link #open(InputStream)}), that audio is lost. Memory is a few PES packets, whatever
 * the length of the file.
 *
 * <p>Damage, as TV recordings and interrupted downloads have it. A lost packet (the continuity counter skips, or a
 * damaged sync byte) leaves a hole in its PES packet; the PES packet is kept with the hole marked, and only the frames
 * the hole runs through are lost: the reader keeps the whole frames before it and takes the next frame after it whose
 * header the following one confirms (ADTS, MPEG audio, the Opus control header). ffmpeg's parsers do the same but hand
 * their decoder the cut frame and what follows up to that header, so ffmpeg loses more around each hole (measured with
 * a packet lost every 997: AAC the same 4096 frames, MP3 1152 where ffmpeg loses 2304, Opus 960 where it loses 4800).
 * MP3's frames after a hole start with an empty bit reservoir, as after a seek, so the granules whose data lay before
 * the hole are silent instead of the bytes before the hole read as theirs (ffmpeg reads them, and bursts to many times
 * full scale there: 2 to 523 times on the test streams with every 5th to 331st packet lost, where this stays within
 * full scale).
 * A frame that arrives damaged without a lost packet is dropped by its decoder, as ffmpeg drops it, rather than failing
 * the song; more than eight in a row is an error. A stream cut off part way ends with its last whole frame.
 *
 * <p>Joined pieces (ffmpeg's segment muxer, HLS) each count their continuity counters from 0: a counter that repeats
 * at a join with another payload is a restart, not one of the standard's duplicate packets (which repeat every byte),
 * so the PES packet it starts is kept.
 */
public final class MpegTsDecoder {
    /** Packet sizes: plain, with a four-byte time stamp in front (.m2ts), with sixteen bytes of Reed-Solomon after. */
    static final int[] PACKET_SIZES = {188, 192, 204};
    private static final int PACKET = 188;
    private static final int SYNC = 0x47;
    /** Packets in a run, each led by the sync byte, that make a transport stream: such a run is not chance. */
    private static final int MIN_PACKETS = 8;
    /** How many packet positions are looked at from a candidate start. */
    private static final int MAX_PACKETS = 64;
    /**
     * How far into a file the first packet is looked for. A recording may start with a packet cut short, or a few
     * packets' worth of zeros or junk; past this the file is not taken for a transport stream.
     */
    public static final int SCAN_BYTES = 64 * 1024;
    /** How much of the start {@link #looksLike} wants: the scanned stretch, then a run of packets from its end. */
    public static final int PROBE_BYTES = SCAN_BYTES + (MAX_PACKETS + 1) * 204;
    /** The program table has to turn up within this much of the stream, or the file holds nothing to play. */
    private static final long MAX_BYTES_BEFORE_TABLE = 16L << 20;
    /** A PES packet of audio is a few kilobytes; one this large is damage or a hostile file. */
    private static final int MAX_PES_BYTES = 4 << 20;
    private static final int PID_PAT = 0;
    private static final int PID_NULL = 0x1FFF;

    private MpegTsDecoder() {
    }

    /**
     * Whether the bytes are the start of an MPEG transport stream: from some offset within {@link #SCAN_BYTES} (a
     * recording may start part way into a packet, or behind junk), the sync byte 0x47 leads at least
     * {@link #MIN_PACKETS} packets of one of the three sizes, with at most one damaged sync byte in eight, and either
     * some stream's continuity counter steps by one between two of its packets or the run is null packets (a
     * constant-rate stream padded with them). The counter keeps out data that only repeats with a packet's length,
     * such as a run of identical silent ADTS frames whose length divides it: all its "packets" are the same, so no
     * counter ever steps. A file shorter than eight packets counts when it is nothing but whole packets from its
     * first byte and holds a program association table.
     *
     * @param probe the first {@link #PROBE_BYTES} bytes, or the whole file when it is shorter
     * @param wholeFile whether {@code probe} is the whole file
     */
    public static boolean looksLike(byte[] probe, boolean wholeFile) {
        return probe != null && findStart(probe, probe.length, wholeFile) != null;
    }

    /** {@link #looksLike} on the first bytes of a stream that may be longer. */
    public static boolean looksLike(byte[] probe) {
        return looksLike(probe, false);
    }

    /** The first packet's offset and the packet size, or null. */
    static int[] findStart(byte[] b, int length, boolean wholeFile) {
        if (wholeFile && length < MIN_PACKETS * PACKET) {
            for (int size : PACKET_SIZES) {
                int first = size == 192 ? 4 : 0;
                if (shortStream(b, length, first, size)) {
                    return new int[] {first, size};
                }
            }
            return null;
        }
        int last = Math.min(SCAN_BYTES, length - 4);
        for (int first = 0; first < last; first++) {
            if ((b[first] & 0xFF) != SYNC) {
                continue;
            }
            for (int size : PACKET_SIZES) {
                if (runFrom(b, length, first, size)) {
                    return new int[] {first, size};
                }
            }
        }
        return null;
    }

    /** Whether a run of packets of {@code size} bytes starts at {@code first}, as {@link #looksLike} describes. */
    private static boolean runFrom(byte[] b, int length, int first, int size) {
        int[] pids = new int[MAX_PACKETS];
        int[] counters = new int[MAX_PACKETS];
        int streams = 0;
        int hits = 0;
        int misses = 0;
        int nulls = 0;
        boolean steps = false;
        for (int k = 0, at = first; k < MAX_PACKETS && at + 4 <= length; k++, at += size) {
            if ((b[at] & 0xFF) != SYNC) {
                misses++;
                if (misses * MIN_PACKETS > Math.max(hits, MIN_PACKETS)) {
                    return false;
                }
                continue;
            }
            hits++;
            int pid = ((b[at + 1] & 0x1F) << 8) | (b[at + 2] & 0xFF);
            if (pid == PID_NULL) {
                nulls++;
            } else if ((b[at + 1] & 0x80) == 0 && (b[at + 3] & 0x10) != 0) {
                // Only a packet that carries a payload counts: a packet marked as damaged in transit says nothing.
                int counter = b[at + 3] & 0x0F;
                int stream = 0;
                while (stream < streams && pids[stream] != pid) {
                    stream++;
                }
                if (stream == streams) {
                    pids[streams++] = pid;
                } else if (counter == ((counters[stream] + 1) & 0x0F)) {
                    steps = true;
                }
                counters[stream] = counter;
            }
            if (hits >= MIN_PACKETS && misses * MIN_PACKETS <= hits && (steps || nulls >= MIN_PACKETS)) {
                return true;
            }
        }
        return false;
    }

    /**
     * A transport stream too short for {@link #runFrom}: every packet position from the first byte to the end of the
     * file holds a sync byte, nothing is left over but a cut last packet, and a program association table is there.
     */
    private static boolean shortStream(byte[] b, int length, int first, int size) {
        int packets = 0;
        boolean pat = false;
        int at = first;
        for (; at + PACKET <= length; at += size) {
            if ((b[at] & 0xFF) != SYNC) {
                return false;
            }
            packets++;
            int pid = ((b[at + 1] & 0x1F) << 8) | (b[at + 2] & 0xFF);
            if (pid == PID_PAT && (b[at + 1] & 0x40) != 0 && (b[at + 3] & 0x10) != 0) {
                int payload = 4 + ((b[at + 3] & 0x20) != 0 ? 1 + (b[at + 4] & 0xFF) : 0);
                if (payload < PACKET) {
                    int table = payload + 1 + (b[at + payload] & 0xFF);
                    pat |= table + 3 <= PACKET && b[at + table] == 0 && (b[at + table + 1] & 0x80) != 0;
                }
            }
        }
        return packets >= 2 && pat && length - at < size;
    }

    /**
     * Opens a transport stream and returns the decoded sound of its first audio stream that this package reads. On
     * success the returned stream owns {@code in} and closes it; on failure {@code in} is left to the caller.
     *
     * @throws UnsupportedFormatException when its audio is in a codec no decoder here reads, named, such as
     *     "AC-3 in MPEG-TS"
     * @throws AudioFormatException when it holds no audio, or is damaged
     */
    public static PcmStream open(InputStream in) throws IOException {
        Objects.requireNonNull(in, "in");
        Demuxer demuxer = new Demuxer(in);
        try {
            demuxer.findAudio();
            return decoder(demuxer);
        } catch (RuntimeException e) {
            throw new AudioFormatException("The MPEG-TS file is damaged.", e);
        }
    }

    /**
     * Opens a transport stream file, reading it twice (see the class comment): the audio before the first whole
     * program table is kept, as ffmpeg keeps it. ID3v2 tags in front are passed. The returned stream owns the file.
     *
     * @throws UnsupportedFormatException when its audio is in a codec no decoder here reads, named
     * @throws AudioFormatException when it holds no audio, or is damaged
     */
    public static PcmStream open(Path file) throws IOException {
        Objects.requireNonNull(file, "file");
        Demuxer probe;
        try (InputStream in = new BufferedInputStream(Files.newInputStream(file), 1 << 16)) {
            skipId3v2(in);
            probe = new Demuxer(in);
            probe.findAudio();
        } catch (RuntimeException e) {
            throw new AudioFormatException("The MPEG-TS file is damaged.", e);
        }
        InputStream in = new BufferedInputStream(Files.newInputStream(file), 1 << 16);
        try {
            skipId3v2(in);
            Demuxer demuxer = new Demuxer(in);
            demuxer.chooseLike(probe);
            return decoder(demuxer);
        } catch (IOException | RuntimeException e) {
            try {
                in.close();
            } catch (IOException suppressed) {
                e.addSuppressed(suppressed);
            }
            if (e instanceof RuntimeException) {
                throw new AudioFormatException("The MPEG-TS file is damaged.", e);
            }
            throw e;
        }
    }

    private static PcmStream decoder(Demuxer demuxer) throws IOException {
        return switch (demuxer.codec) {
            case ADTS -> AdtsDecoder.openTolerant(demuxer.byteStream());
            case MPEG_AUDIO -> {
                FrameStream frames = demuxer.byteStream();
                yield Mp3Decoder.openFrames(frames, frames::passed);
            }
            case OPUS -> opus(demuxer);
        };
    }

    /** Passes ID3v2 tags at the start, as AudioDecoders does for every other format. */
    private static void skipId3v2(InputStream in) throws IOException {
        byte[] h = new byte[10];
        while (true) {
            in.mark(10);
            if (in.readNBytes(h, 0, 10) < 10 || h[0] != 'I' || h[1] != 'D' || h[2] != '3' || (h[3] & 0xFF) == 0xFF
                    || (h[4] & 0xFF) == 0xFF || (h[6] | h[7] | h[8] | h[9]) < 0) {
                in.reset();
                return;
            }
            long size = (h[6] & 0x7FL) << 21 | (h[7] & 0x7FL) << 14 | (h[8] & 0x7FL) << 7 | (h[9] & 0x7FL);
            size += h[3] == 4 && (h[5] & 0x10) != 0 ? 10 : 0;
            in.skipNBytes(size);
        }
    }

    private static PcmStream opus(Demuxer demuxer) throws IOException {
        OpusHead head = OpusHead.parse(demuxer.opusHead, 0, demuxer.opusHead.length);
        // The pre-skip is carried by the first access unit's start trim, not by a header, so none is applied here.
        OpusPcmStream opus = new OpusPcmStream(demuxer.opusPackets(), head, 0, "MPEG-TS Opus");
        // A broadcast or a recording has damaged packets now and then: each is dropped, not the whole song.
        opus.tolerateDamagedPackets();
        // Joined pieces carry a start trim at every join; only the trims at the two ends of the song are followed.
        opus.trimOnlyAtTheEdges();
        return ChannelOrder.toWaveOrder(opus);
    }

    enum Codec {
        ADTS,
        MPEG_AUDIO,
        OPUS
    }

    /** Reads packets, follows the PAT and PMT, and hands out the payload of the chosen elementary stream. */
    static final class Demuxer {
        private final InputStream in;
        private final byte[] buffer = new byte[1 << 17];
        private int start;
        private int end;
        private boolean eof;
        private long consumed;

        private int size;
        private boolean synced;

        private int pmtPid = -1;
        private int audioPid = -1;
        Codec codec;
        byte[] opusHead;
        /** What the program table names when it lists audio in a codec this package does not decode. */
        private String unsupported;
        private final Section pat = new Section();
        private final Section pmt = new Section();

        /** The current PES packet of the chosen stream: its payload so far, after the header. */
        private byte[] pes = new byte[8192];
        private int pesLength;
        private boolean pesOpen;
        private int pesHeaderNeeded;
        /** The continuity counter of the chosen stream's last packet with a payload, to pass a duplicated packet. */
        private int lastCounter = -1;
        /** That packet's first byte after the sync byte and its payload, to tell a duplicate from a restarted counter. */
        private final byte[] lastPayload = new byte[PACKET];
        private int lastPayloadLength = -1;

        /** PES payloads ready to be handed out, as one byte stream: pes[readAt .. pesReady). */
        private int readAt;
        private int ready;
        private boolean finished;
        /**
         * Where bytes of the chosen stream went missing (a lost or damaged packet), as positions in {@link #pes}, in
         * order: the bytes before a hole and the ones from it on were not next to each other in the stream. The
         * readers keep the whole frames on both sides and find the next frame after it, as ffmpeg's parsers do.
         */
        private int[] holes = new int[8];
        private int holeCount;

        Demuxer(InputStream in) {
            this.in = in;
        }

        /** Takes the stream another pass over the same file chose, so the program table need not be read first. */
        void chooseLike(Demuxer probe) {
            codec = probe.codec;
            audioPid = probe.audioPid;
            opusHead = probe.opusHead;
        }

        private int fill(int wanted) throws IOException {
            if (end - start >= wanted || eof) {
                return end - start;
            }
            if (start > 0) {
                System.arraycopy(buffer, start, buffer, 0, end - start);
                end -= start;
                start = 0;
            }
            while (end < wanted && !eof) {
                int n;
                try {
                    n = in.read(buffer, end, buffer.length - end);
                } catch (java.nio.channels.ClosedByInterruptException interrupted) {
                    // A file channel closes itself when its thread is interrupted; the caller is told it was that.
                    InterruptedIOException e = new InterruptedIOException("MPEG-TS decoding interrupted");
                    e.initCause(interrupted);
                    throw e;
                }
                if (n < 0) {
                    eof = true;
                } else if (n == 0) {
                    int one = in.read();
                    if (one < 0) {
                        eof = true;
                    } else {
                        buffer[end++] = (byte) one;
                    }
                } else {
                    end += n;
                }
            }
            return end - start;
        }

        private void advance(int n) {
            start += n;
            consumed += n;
        }

        /** Finds the first packet the way {@link #looksLike} does, then follows the packets from there. */
        private void syncFirst() throws IOException {
            int have = fill(PROBE_BYTES);
            int[] found = findStart(buffer, have, eof);
            if (found == null) {
                throw new AudioFormatException("This is not an MPEG transport stream.");
            }
            advance(found[0]);
            size = found[1];
            synced = true;
        }

        /**
         * Finds the next packet after a damaged sync byte: the next offset from which three packets in a row start
         * with one (fewer at the end of the file). Every step moves forward, so this is bounded by the file's bytes.
         */
        private boolean resync() throws IOException {
            advance(1);
            long scanned = 0;
            while (true) {
                int have = fill(2 * size + PACKET);
                if (have < PACKET) {
                    return false;
                }
                int i = 0;
                boolean found = false;
                for (; i + PACKET <= have; i++) {
                    if ((buffer[start + i] & 0xFF) != SYNC) {
                        continue;
                    }
                    if (i + 2 * size + 1 > have && !eof) {
                        // Not enough bytes yet to confirm this one: it becomes the start, and more is read.
                        break;
                    }
                    boolean second = i + size >= have || (buffer[start + i + size] & 0xFF) == SYNC;
                    boolean third = i + 2 * size >= have || (buffer[start + i + 2 * size] & 0xFF) == SYNC;
                    if (second && third) {
                        found = true;
                        break;
                    }
                }
                advance(i);
                if (found) {
                    return true;
                }
                if (eof && end - start < PACKET) {
                    return false;
                }
                scanned += i;
                if (scanned > 1 << 16) {
                    checkInterrupt();
                    scanned = 0;
                }
            }
        }

        /** Reads the next packet into buffer[start .. start+188) and returns true, or false at the end. */
        private boolean nextPacket() throws IOException {
            if (!synced) {
                syncFirst();
            }
            while (true) {
                if (fill(PACKET) < PACKET) {
                    return false;
                }
                if ((buffer[start] & 0xFF) == SYNC) {
                    return true;
                }
                if (!resync()) {
                    return false;
                }
            }
        }

        private void donePacket() throws IOException {
            // A cut last packet of 192 or 204 bytes still has its 188 bytes; the rest may be missing.
            int have = fill(size);
            advance(Math.min(size, have));
        }

        /** Reads packets until the program table names the audio stream to decode. */
        void findAudio() throws IOException {
            while (codec == null) {
                checkInterrupt();
                if (!nextPacket()) {
                    if (unsupported != null) {
                        throw new UnsupportedFormatException(unsupported);
                    }
                    throw new AudioFormatException(pmtPid < 0 ? "The MPEG-TS file has no program table."
                            : "The MPEG-TS file has no audio stream.");
                }
                if (consumed > MAX_BYTES_BEFORE_TABLE) {
                    if (unsupported != null) {
                        throw new UnsupportedFormatException(unsupported);
                    }
                    throw new AudioFormatException("The MPEG-TS file has no audio stream in its first 16 MB.");
                }
                int at = start;
                int pid = ((buffer[at + 1] & 0x1F) << 8) | (buffer[at + 2] & 0xFF);
                if (pid == PID_PAT) {
                    if (pat.add(buffer, at) && pat.tableId() == 0) {
                        readPat();
                    }
                } else if (pid == pmtPid) {
                    if (pmt.add(buffer, at) && pmt.tableId() == 2) {
                        readPmt();
                    }
                } else if (probe(at, pid)) {
                    // This packet starts the chosen stream's first PES packet: it is read again from here.
                    return;
                }
                donePacket();
            }
        }

        private void readPat() {
            byte[] s = pat.data;
            int length = pat.length;
            // Entries of four bytes between the eight-byte header and the CRC; program 0 points at the network table.
            for (int p = 8; p + 4 <= length - 4; p += 4) {
                int program = (s[p] & 0xFF) << 8 | (s[p + 1] & 0xFF);
                int pid = (s[p + 2] & 0x1F) << 8 | (s[p + 3] & 0xFF);
                if (program != 0) {
                    if (pid != pmtPid) {
                        pmtPid = pid;
                        pmt.reset();
                    }
                    return;
                }
            }
        }

        private void readPmt() throws AudioFormatException {
            byte[] s = pmt.data;
            int length = pmt.length;
            if (length < 16) {
                return;
            }
            int programInfo = (s[10] & 0x0F) << 8 | (s[11] & 0xFF);
            int p = 12 + programInfo;
            String firstUnsupported = null;
            int[] probes = new int[16];
            int probeTotal = 0;
            while (p + 5 <= length - 4) {
                int type = s[p] & 0xFF;
                int pid = (s[p + 1] & 0x1F) << 8 | (s[p + 2] & 0xFF);
                int infoLength = (s[p + 3] & 0x0F) << 8 | (s[p + 4] & 0xFF);
                int info = p + 5;
                int infoEnd = Math.min(length - 4, info + infoLength);
                p = info + infoLength;
                String opusFormat = registration(s, info, infoEnd);
                Codec found = switch (type) {
                    case 0x0F -> Codec.ADTS;
                    case 0x03, 0x04 -> Codec.MPEG_AUDIO;
                    case 0x06 -> "Opus".equals(opusFormat) ? Codec.OPUS : null;
                    default -> null;
                };
                if (found == Codec.OPUS) {
                    opusHead = opusHead(s, info, infoEnd);
                    if (opusHead == null) {
                        firstUnsupported = firstUnsupported != null ? firstUnsupported
                                : "Opus in MPEG-TS (channel mapping)";
                        continue;
                    }
                }
                if (found != null) {
                    codec = found;
                    audioPid = pid;
                    return;
                }
                String name = audioName(type, s, info, infoEnd, opusFormat);
                if (name != null && firstUnsupported == null) {
                    firstUnsupported = name;
                }
                if (name == null && type == 0x06 && probeTotal < probes.length) {
                    probes[probeTotal++] = pid;
                }
            }
            unsupported = firstUnsupported;
            probePids = probes;
            probeCount = probeTotal;
        }

        /**
         * Private streams (type 6) that no descriptor names, whose content decides, as ffmpeg's probing decides: an
         * .m2ts written by ffmpeg in Blu-ray mode lists its AAC that way.
         */
        private int[] probePids = new int[0];
        private int probeCount;

        /**
         * Whether this packet of an unnamed private stream starts a PES packet whose payload begins with an ADTS or
         * MPEG audio header; if so that stream is chosen.
         */
        private boolean probe(int at, int pid) {
            int index = 0;
            while (index < probeCount && probePids[index] != pid) {
                index++;
            }
            if (index == probeCount || (buffer[at + 1] & 0x40) == 0 || (buffer[at + 3] & 0x10) == 0) {
                return false;
            }
            int p = at + 4 + ((buffer[at + 3] & 0x20) != 0 ? 1 + (buffer[at + 4] & 0xFF) : 0);
            int to = at + PACKET;
            if (p + 9 > to || buffer[p] != 0 || buffer[p + 1] != 0 || buffer[p + 2] != 1) {
                return false;
            }
            int es = p + 9 + (buffer[p + 8] & 0xFF);
            if (es + 8 > to) {
                return false;
            }
            byte[] head = Arrays.copyOfRange(buffer, es, to);
            if (AdtsDecoder.validHeader(head, 0)) {
                codec = Codec.ADTS;
            } else if ((head[0] & 0xFF) == 0xFF && Mp3Decoder.looksLike(head)) {
                codec = Codec.MPEG_AUDIO;
            } else {
                return false;
            }
            audioPid = pid;
            return true;
        }

        /** The format identifier of a registration descriptor (tag 5) among the descriptors, or null. */
        private static String registration(byte[] s, int from, int to) {
            for (int d = from; d + 2 <= to; d += 2 + (s[d + 1] & 0xFF)) {
                if ((s[d] & 0xFF) == 0x05 && (s[d + 1] & 0xFF) >= 4 && d + 6 <= to) {
                    return new String(s, d + 2, 4, java.nio.charset.StandardCharsets.ISO_8859_1);
                }
            }
            return null;
        }

        /** A descriptor with this tag among the descriptors, or -1. */
        private static int descriptor(byte[] s, int from, int to, int tag) {
            for (int d = from; d + 2 <= to; d += 2 + (s[d + 1] & 0xFF)) {
                if ((s[d] & 0xFF) == tag) {
                    return d;
                }
            }
            return -1;
        }

        /**
         * An OpusHead for the stream, built from its extension descriptor (tag 0x7F, extension 0x80): the channel
         * configuration code is the channel count for 1 to 8 channels, mapped as RFC 7845's Vorbis channel order with
         * libopus's surround stream layout, which is what the specification's table gives; 0x81 is two channels in
         * two mono streams. Without the descriptor the stream is stereo, as the specification's default. Explicit
         * mappings (0x80) are refused.
         */
        private static byte[] opusHead(byte[] s, int from, int to) {
            int channels = 2;
            boolean dualMono = false;
            int d = descriptor(s, from, to, 0x7F);
            if (d >= 0 && d + 4 <= to && (s[d + 1] & 0xFF) >= 2 && (s[d + 2] & 0xFF) == 0x80) {
                int code = s[d + 3] & 0xFF;
                if (code >= 1 && code <= 8) {
                    channels = code;
                } else if (code == 0x81) {
                    dualMono = true;
                } else {
                    return null;
                }
            }
            int[][] layout = {
                    {}, {1, 0, 0}, {1, 1, 0, 1}, {2, 1, 0, 2, 1}, {2, 2, 0, 1, 2, 3}, {3, 2, 0, 4, 1, 2, 3},
                    {4, 2, 0, 4, 1, 2, 3, 5}, {4, 3, 0, 4, 1, 2, 3, 5, 6}, {5, 3, 0, 6, 1, 2, 3, 4, 5, 7}};
            int family = channels > 2 || dualMono ? 1 : 0;
            int[] row = dualMono ? new int[] {2, 0, 0, 1} : layout[channels];
            byte[] head = new byte[family == 0 ? 19 : 21 + channels];
            System.arraycopy("OpusHead".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1), 0, head, 0, 8);
            head[8] = 1;
            head[9] = (byte) channels;
            // Pre-skip 0 (the start trim carries it), 48 kHz input, no gain.
            head[12] = (byte) 0x80;
            head[13] = (byte) 0xBB;
            head[18] = (byte) family;
            if (family == 1) {
                head[19] = (byte) row[0];
                head[20] = (byte) row[1];
                for (int c = 0; c < channels; c++) {
                    head[21 + c] = (byte) row[2 + c];
                }
            }
            return head;
        }

        /** The name of an audio codec the program table lists that no decoder here reads, or null for video. */
        private static String audioName(int type, byte[] s, int from, int to, String format) {
            String codec = switch (type) {
                case 0x11 -> "AAC (LATM)";
                case 0x81 -> "AC-3";
                case 0x87 -> "E-AC-3";
                case 0x82, 0x85, 0x86, 0x8A -> "DTS";
                case 0x83 -> "TrueHD";
                case 0x84, 0xA1 -> "E-AC-3";
                case 0x80 -> "PCM";
                case 0x1C -> "MPEG-4 audio";
                case 0x06 -> {
                    if (descriptor(s, from, to, 0x6A) >= 0 || "AC-3".equals(format)) {
                        yield "AC-3";
                    }
                    if (descriptor(s, from, to, 0x7A) >= 0 || "EAC3".equals(format)) {
                        yield "E-AC-3";
                    }
                    if (descriptor(s, from, to, 0x7B) >= 0 || (format != null && format.startsWith("DTS"))) {
                        yield "DTS";
                    }
                    if ("BSSD".equals(format)) {
                        yield "PCM";
                    }
                    yield null;
                }
                default -> null;
            };
            return codec == null ? null : codec + " in MPEG-TS";
        }

        /**
         * The payload of the chosen stream's PES packets, joined, as bytes, with the frames a hole cuts taken out (see
         * {@link FrameStream}). Where nothing went missing every byte is handed on as it is.
         */
        FrameStream byteStream() {
            return new FrameStream(this, codec == Codec.ADTS);
        }

        /** Takes the next ready PES payload; false at the end. Its holes are added to {@code holesOut} at {@code base}. */
        int takeChunk(byte[][] into, int at, java.util.function.LongConsumer holesOut, long base) throws IOException {
            while (readAt >= ready) {
                if (!nextPes()) {
                    return -1;
                }
            }
            int n = ready - readAt;
            if (at + n > into[0].length) {
                into[0] = Arrays.copyOf(into[0], Math.max(at + n, into[0].length * 2));
            }
            System.arraycopy(pes, readAt, into[0], at, n);
            for (int i = 0; i < holeCount && holes[i] < ready; i++) {
                if (holes[i] >= readAt) {
                    holesOut.accept(base + holes[i] - readAt);
                }
            }
            readAt = ready;
            return n;
        }

        /**
         * Reads packets until the chosen stream has a whole PES payload ready, in pes[0 .. ready); false at the end.
         * The last PES packet of the file, which no next one closes, is handed out at the end of the file.
         */
        boolean nextPes() throws IOException {
            if (finished) {
                return false;
            }
            // Whatever was handed out is gone; a PES packet in progress moves to the front.
            if (ready > 0) {
                System.arraycopy(pes, ready, pes, 0, pesLength - ready);
                pesLength -= ready;
                pesBegin = Math.max(0, pesBegin - ready);
                // The holes of what was handed out are gone; one where the handed-out part ended starts the rest.
                int kept = 0;
                for (int i = 0; i < holeCount; i++) {
                    if (holes[i] >= ready) {
                        holes[kept++] = holes[i] - ready;
                    }
                }
                holeCount = kept;
                ready = 0;
                readAt = 0;
            }
            while (true) {
                checkInterrupt();
                if (!nextPacket()) {
                    finished = true;
                    if (pesOpen && pesHeaderNeeded == 0 && pesLength > pesBegin) {
                        pesOpen = false;
                        ready = pesLength;
                        return true;
                    }
                    return false;
                }
                int at = start;
                int pid = ((buffer[at + 1] & 0x1F) << 8) | (buffer[at + 2] & 0xFF);
                if (pid == audioPid) {
                    boolean done = audioPacket(at);
                    donePacket();
                    if (done) {
                        return true;
                    }
                } else {
                    donePacket();
                }
            }
        }

        /** Adds one packet of the chosen stream; true when it closed the PES packet before it, now ready. */
        private boolean audioPacket(int at) throws AudioFormatException {
            int flags = buffer[at + 3] & 0xFF;
            if ((buffer[at + 1] & 0x80) != 0 || (flags & 0x10) == 0) {
                // Marked as damaged in transit, or no payload (adaptation field only).
                return false;
            }
            int payload = 4;
            if ((flags & 0x20) != 0) {
                payload += 1 + (buffer[at + 4] & 0xFF);
                if (payload > PACKET) {
                    return false;
                }
            }
            int counter = flags & 0x0F;
            boolean discontinuity = (flags & 0x20) != 0 && (buffer[at + 4] & 0xFF) > 0
                    && (buffer[at + 5] & 0x80) != 0;
            // A duplicate packet, which the standard allows once (ISO/IEC 13818-1, 2.4.3.3): the same counter and every
            // byte of the payload the same. The same counter with another payload is no duplicate but a restart: the
            // pieces of ffmpeg's segment muxer each count from 0, so a join lands on the counter the piece before
            // ended on once in sixteen joins, and dropping that packet would lose the PES packet it starts.
            boolean repeated = counter == lastCounter && !discontinuity && samePayload(at, payload);
            // A packet of the stream went missing (a broken sync byte, a cut, damage in transit) when the counter does
            // not step by one and nothing says the stream was restarted on purpose.
            boolean lost = lastCounter >= 0 && !repeated && !discontinuity && counter != ((lastCounter + 1) & 0x0F);
            lastCounter = counter;
            if (repeated) {
                lastPayloadLength = -1;
                return false;
            }
            lastPayload[0] = buffer[at + 1];
            System.arraycopy(buffer, at + payload, lastPayload, 1, PACKET - payload);
            lastPayloadLength = PACKET - payload;
            boolean startsPes = (buffer[at + 1] & 0x40) != 0;
            boolean closed = false;
            if (lost && pesOpen && !startsPes) {
                if (pesHeaderNeeded != 0) {
                    // The hole is in the PES header itself: nothing says where its payload starts, so it is dropped.
                    dropPes();
                } else {
                    // The PES packet in progress has a hole. Its bytes are kept with the hole marked, so the frames
                    // before and after it survive and only the frame it cuts is lost, as with ffmpeg's parsers.
                    markHole(pesLength);
                }
            }
            if (startsPes) {
                if (pesOpen && pesHeaderNeeded == 0 && pesLength > pesBegin) {
                    // Joined HLS segments restart their counters, so a jump just before a new PES packet only shows
                    // damage when the PES packet before it is shorter than its header said.
                    if (lost && pesDeclared > 0 && pesLength - pesBegin != pesDeclared) {
                        // Its end went missing: kept, with the hole at its end, so its whole frames survive.
                        markHole(pesLength);
                    }
                    closed = true;
                    ready = pesLength;
                }
                pesOpen = true;
                pesBegin = pesLength;
                pesDeclared = 0;
                // The PES header: start code, stream id, length, two flag bytes and the header data length.
                pesHeaderNeeded = -1;
            }
            if (!pesOpen) {
                // The stream was cut part way into a PES packet; its start is gone, and so is the packet.
                gapPending = true;
                return closed;
            }
            int from = at + payload;
            int to = at + PACKET;
            if (pesHeaderNeeded != 0) {
                from = pesHeader(from, to, startsPes);
                if (from < 0) {
                    return closed;
                }
            }
            append(from, to - from);
            return closed;
        }

        /** Whether the packet at {@code at} repeats the start flag and payload of the stream's last packet. */
        private boolean samePayload(int at, int payload) {
            return lastPayloadLength == PACKET - payload && lastPayload[0] == buffer[at + 1]
                    && Arrays.equals(lastPayload, 1, 1 + lastPayloadLength, buffer, at + payload, at + PACKET);
        }

        /** Header bytes of a PES packet that may span packets; kept in a little buffer until it is whole. */
        private final byte[] header = new byte[9 + 255];
        private int headerHave;
        /** Where the PES packet in progress starts in {@link #pes}. */
        private int pesBegin;
        /** The payload bytes its header announces, 0 when it does not say (allowed for video only, but seen). */
        private int pesDeclared;

        private int pesHeader(int from, int to, boolean first) {
            if (first) {
                headerHave = 0;
            }
            while (true) {
                int want = headerWanted();
                if (want < 0) {
                    // Not a PES packet: it is dropped.
                    dropPes();
                    return -1;
                }
                if (headerHave >= want) {
                    pesHeaderNeeded = 0;
                    int length = (header[4] & 0xFF) << 8 | (header[5] & 0xFF);
                    pesDeclared = length == 0 ? 0 : Math.max(0, length - (want - 6));
                    return from;
                }
                if (from >= to) {
                    // The header goes on in the next packet of the stream.
                    return -1;
                }
                int n = Math.min(want - headerHave, to - from);
                System.arraycopy(buffer, from, header, headerHave, n);
                headerHave += n;
                from += n;
            }
        }

        /** How long the PES header is, as far as its bytes so far tell, or -1 when it is not one. */
        private int headerWanted() {
            if (headerHave < 6) {
                return 6;
            }
            if (header[0] != 0 || header[1] != 0 || header[2] != 1) {
                return -1;
            }
            int sid = header[3] & 0xFF;
            // Streams without the optional header: the program stream map, padding, private stream 2 and the like.
            if (sid == 0xBC || sid == 0xBE || sid == 0xBF || sid == 0xF0 || sid == 0xF1 || sid == 0xFF
                    || sid == 0xF2 || sid == 0xF8) {
                return 6;
            }
            if (headerHave < 9) {
                return 9;
            }
            return 9 + (header[8] & 0xFF);
        }

        /** Bytes were dropped from the stream since the last ones kept: the next ones kept start after a hole. */
        private boolean gapPending;
        /** Whether any audio byte has been kept yet: a gap before the first one is just where the file starts. */
        private boolean anyKept;

        /** Drops the PES packet in progress; whatever comes next follows a hole. */
        private void dropPes() {
            pesLength = pesBegin;
            pesOpen = false;
            while (holeCount > 0 && holes[holeCount - 1] > pesBegin) {
                holeCount--;
            }
            gapPending = true;
        }

        private void markHole(int at) {
            if (holeCount > 0 && holes[holeCount - 1] >= at) {
                return;
            }
            if (holeCount == holes.length) {
                holes = Arrays.copyOf(holes, holeCount * 2);
            }
            holes[holeCount++] = at;
        }

        /** The first hole at or after {@code from} and before {@code to}, or -1. */
        int holeIn(int from, int to) {
            for (int i = 0; i < holeCount; i++) {
                if (holes[i] >= from && holes[i] < to) {
                    return holes[i];
                }
                if (holes[i] >= to) {
                    break;
                }
            }
            return -1;
        }

        private void append(int from, int n) throws AudioFormatException {
            if (n <= 0) {
                return;
            }
            if (pesLength + n > pes.length) {
                if (pesLength + n > MAX_PES_BYTES) {
                    throw new AudioFormatException("An MPEG-TS audio packet is longer than " + (MAX_PES_BYTES >> 20)
                            + " MB.");
                }
                pes = Arrays.copyOf(pes, Math.max(pesLength + n, pes.length * 2));
            }
            if (gapPending) {
                gapPending = false;
                if (anyKept) {
                    markHole(pesLength);
                }
            }
            anyKept = true;
            System.arraycopy(buffer, from, pes, pesLength, n);
            pesLength += n;
        }

        /** The Opus access units of the chosen stream, with the trims of their control headers. */
        OpusPcmStream.Packets opusPackets() {
            return new OpusPcmStream.Packets() {
                private int au;
                private int auLength;
                private int trimStart;
                private int trimEnd;
                /** The hole the reader last found its way past, so it is looked for once. */
                private int passedHole = -1;

                @Override
                public boolean next() throws IOException {
                    while (true) {
                        if (readAt >= ready) {
                            passedHole = -1;
                            if (!nextPes()) {
                                return false;
                            }
                        }
                        if (readAt != passedHole && holeIn(readAt, readAt + 1) == readAt) {
                            // The bytes here follow a hole: the next access unit is found by its prefix, checked by
                            // where the next one, the end of the PES packet or the next hole starts.
                            passedHole = readAt;
                            int q = resync(readAt);
                            if (q < 0) {
                                readAt = ready;
                                continue;
                            }
                            readAt = q;
                        }
                        int end = parse(readAt);
                        if (end < 0) {
                            // Not an access unit where one should start, or one that runs past the end of its PES
                            // packet (a file cut off part way, or damage nothing marked): the rest of the PES packet
                            // is lost, never the whole song.
                            readAt = ready;
                            continue;
                        }
                        int hole = holeIn(readAt + 1, end);
                        if (hole >= 0) {
                            // A hole inside this access unit: it is lost, and the reader goes on after the hole.
                            readAt = hole;
                            continue;
                        }
                        readAt = end;
                        return true;
                    }
                }

                /** An access unit at the start of a PES region past a hole, or -1 when there is none to be sure of. */
                private int resync(int from) {
                    for (int q = from; q + 2 <= ready; q++) {
                        if (((pes[q] & 0xFF) << 3 | (pes[q + 1] & 0xFF) >> 5) != 0x3FF) {
                            continue;
                        }
                        int end = parse(q);
                        if (end < 0 || holeIn(q + 1, end) >= 0) {
                            continue;
                        }
                        if (end == ready || holeIn(end, end + 1) == end || (end + 2 <= ready
                                && ((pes[end] & 0xFF) << 3 | (pes[end + 1] & 0xFF) >> 5) == 0x3FF)) {
                            return q;
                        }
                    }
                    return -1;
                }

                /**
                 * Reads the control header of an access unit at {@code p} into the fields: an 11-bit prefix 0x3FF,
                 * three flags, the size in bytes of 255 and the rest, the optional trims (13 bits in 16) and an
                 * extension, then the Opus packet. Returns where the access unit ends, or -1 when there is none at
                 * {@code p} or it does not end inside the PES packet.
                 */
                private int parse(int p) {
                    if (p + 2 > ready || ((pes[p] & 0xFF) << 3 | (pes[p + 1] & 0xFF) >> 5) != 0x3FF) {
                        return -1;
                    }
                    boolean hasStart = (pes[p + 1] & 0x10) != 0;
                    boolean hasEnd = (pes[p + 1] & 0x08) != 0;
                    boolean hasExtension = (pes[p + 1] & 0x04) != 0;
                    p += 2;
                    int length = 0;
                    while (true) {
                        if (p >= ready) {
                            return -1;
                        }
                        int v = pes[p++] & 0xFF;
                        length += v;
                        if (v != 0xFF) {
                            break;
                        }
                    }
                    int start = 0;
                    int endTrim = 0;
                    if (hasStart) {
                        if (p + 2 > ready) {
                            return -1;
                        }
                        start = (pes[p] & 0x1F) << 8 | (pes[p + 1] & 0xFF);
                        p += 2;
                    }
                    if (hasEnd) {
                        if (p + 2 > ready) {
                            return -1;
                        }
                        endTrim = (pes[p] & 0x1F) << 8 | (pes[p + 1] & 0xFF);
                        p += 2;
                    }
                    if (hasExtension) {
                        if (p >= ready) {
                            return -1;
                        }
                        p += 1 + (pes[p] & 0xFF);
                    }
                    if (p + length > ready) {
                        return -1;
                    }
                    au = p;
                    auLength = length;
                    trimStart = start;
                    trimEnd = endTrim;
                    return p + length;
                }

                @Override
                public byte[] data() {
                    return pes;
                }

                @Override
                public int offset() {
                    return au;
                }

                @Override
                public int length() {
                    return auLength;
                }

                @Override
                public int trimStart() {
                    return trimStart;
                }

                @Override
                public int trimEnd() {
                    return trimEnd;
                }

                @Override
                public void close() throws IOException {
                    in.close();
                }
            };
        }
    }

    /**
     * ADTS or MPEG audio frames out of the joined PES payloads, over the holes that lost packets leave. Where no byte
     * went missing, every byte is handed on unchanged, frames and anything else alike. A frame that a hole cuts is
     * left out from its start, and so are the bytes after the hole up to the next frame header that the header after
     * it confirms (or the end, or another hole), which is what ffmpeg's ADTS and MPEG audio parsers resynchronise on;
     * ffmpeg also hands the decoder the cut frame and the bytes up to that header, which its decoder then throws away
     * with the frames around them, so it loses more around each hole. Free-format MP3, whose frames no header
     * measures, is handed on as it is past a hole too, as are bytes after a hole with no confirmed header within
     * {@link #RESYNC_LIMIT} bytes.
     */
    private static final class FrameStream extends InputStream {
        private static final int RESYNC_LIMIT = 1 << 16;
        private final Demuxer demuxer;
        private final boolean adts;
        private final byte[][] data = {new byte[1 << 16]};
        /** data[0][0 .. end) holds the bytes from stream position base on; pos is the next one not yet decided. */
        private long base;
        private int pos;
        private int end;
        private boolean eof;
        private final java.util.ArrayDeque<Long> holes = new java.util.ArrayDeque<>();
        /** Bytes data[0][outFrom .. outTo) are decided and handed out next. */
        private int outFrom;
        private int outTo;
        /**
         * The header of the last whole frame handed on, which a header found past a hole must match (as ffmpeg's MPEG
         * audio parser compares with the header before; the first one may be a LAME Info frame, whose channel mode
         * and flags the song's frames do not share).
         */
        private int reference;
        private boolean haveReference;
        private boolean afterHole;
        /** Bytes handed out so far: the position in this stream of the next byte read. */
        private long handed;
        /** Positions in this stream after which bytes went missing (a hole was passed), for MP3's bit reservoir. */
        private final java.util.ArrayDeque<Long> breaks = new java.util.ArrayDeque<>();

        FrameStream(Demuxer demuxer, boolean adts) {
            this.demuxer = demuxer;
            this.adts = adts;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            return read(one, 0, 1) < 0 ? -1 : one[0] & 0xFF;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (len == 0) {
                return 0;
            }
            while (outFrom >= outTo) {
                if (!decide()) {
                    return -1;
                }
            }
            int n = Math.min(len, outTo - outFrom);
            System.arraycopy(data[0], outFrom, b, off, n);
            outFrom += n;
            handed += n;
            return n;
        }

        /** {@link Mp3Decoder.ReservoirBreaks}: whether bytes went missing at or before {@code offset}, once. */
        boolean passed(long offset) {
            boolean any = false;
            while (!breaks.isEmpty() && breaks.peekFirst() <= offset) {
                breaks.pollFirst();
                any = true;
            }
            return any;
        }

        @Override
        public void close() throws IOException {
            demuxer.in.close();
        }

        /** Makes sure bytes up to pos + n are here unless the stream ends first. */
        private void need(int n) throws IOException {
            while (end - pos < n && !eof) {
                if (pos > (1 << 15) && outFrom >= outTo) {
                    // What was handed out is no longer needed.
                    System.arraycopy(data[0], pos, data[0], 0, end - pos);
                    base += pos;
                    end -= pos;
                    pos = 0;
                    outFrom = 0;
                    outTo = 0;
                }
                long chunkBase = base + end;
                int got = demuxer.takeChunk(data, end, holes::add, chunkBase);
                if (got < 0) {
                    eof = true;
                } else {
                    end += got;
                }
            }
        }

        /** The next hole at or after stream position {@code from}, or Long.MAX_VALUE. */
        private long nextHole(long from) {
            while (!holes.isEmpty() && holes.peekFirst() < from) {
                holes.pollFirst();
            }
            return holes.isEmpty() ? Long.MAX_VALUE : holes.peekFirst();
        }

        /** The frame length at data[0][at], or -1 when no header the stream could have starts there. */
        private int frameAt(int at, boolean matchReference) {
            if (at + 7 > end) {
                return -1;
            }
            byte[] b = data[0];
            if (adts) {
                if (!AdtsDecoder.validHeader(b, at)) {
                    return -1;
                }
                int header = (b[at + 1] & 0x08) << 16 | (b[at + 2] & 0xFD) << 8 | (b[at + 3] & 0xC0);
                if (matchReference && haveReference && header != reference) {
                    return -1;
                }
                return AdtsDecoder.frameLength(b, at);
            }
            int h = (b[at] & 0xFF) << 24 | (b[at + 1] & 0xFF) << 16 | (b[at + 2] & 0xFF) << 8 | (b[at + 3] & 0xFF);
            int length = Mp3Decoder.parsedFrameBytes(h);
            if (length <= 4 || (matchReference && haveReference && !Mp3Decoder.sameStream(h, reference))) {
                return -1;
            }
            return length;
        }

        private void takeReference(int at) {
            byte[] b = data[0];
            haveReference = true;
            reference = adts ? (b[at + 1] & 0x08) << 16 | (b[at + 2] & 0xFD) << 8 | (b[at + 3] & 0xC0)
                    : (b[at] & 0xFF) << 24 | (b[at + 1] & 0xFF) << 16 | (b[at + 2] & 0xFF) << 8 | (b[at + 3] & 0xFF);
        }

        /** Decides the next bytes: hands some out, or drops some. False at the end. */
        private boolean decide() throws IOException {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedIOException("MPEG-TS decoding interrupted");
            }
            need(8);
            if (pos >= end) {
                return false;
            }
            long at = base + pos;
            long hole = nextHole(at);
            if (hole == at) {
                holes.pollFirst();
                afterHole = true;
            }
            if (afterHole) {
                afterHole = false;
                // Everything decided so far has been read: the next byte read is the first one after the hole.
                if (!adts && (breaks.isEmpty() || breaks.peekLast() != handed)) {
                    breaks.addLast(handed);
                }
                resync();
                return true;
            }
            int length = frameAt(pos, false);
            if (length < 0) {
                // Not a frame (or one no header measures): handed on up to the next hole, as the stream had it.
                int to = (int) Math.min(end, nextHole(at + 1) - base);
                hand(to);
                return true;
            }
            need(length);
            // The frame's bytes may have brought in a hole that was not known before they were read.
            hole = nextHole(at);
            if (hole < at + length && hole > at) {
                // A hole cuts this frame: it is left out, up to the hole.
                pos = (int) (hole - base);
                outFrom = outTo = pos;
                return true;
            }
            if (pos + length <= end) {
                takeReference(pos);
            }
            hand(Math.min(end, pos + length));
            return true;
        }

        private void hand(int to) {
            outFrom = pos;
            outTo = to;
            pos = to;
        }

        /** After a hole: drops the bytes before the next confirmed frame header, or hands them on if none is found. */
        private void resync() throws IOException {
            for (int scanned = 0; scanned < RESYNC_LIMIT; scanned++) {
                need(scanned + 8);
                int q = pos + scanned;
                if (q >= end) {
                    // The stream ends with no frame after the hole: what is left is dropped.
                    pos = end;
                    outFrom = outTo = pos;
                    return;
                }
                long qAt = base + q;
                if (scanned > 0 && nextHole(base + pos + 1) <= qAt) {
                    // Another hole before any frame: the search starts again from it.
                    pos = (int) (nextHole(base + pos + 1) - base);
                    holes.pollFirst();
                    scanned = -1;
                    continue;
                }
                int length = frameAt(q, true);
                if (length < 0) {
                    continue;
                }
                int scannedBefore = q - pos;
                need(scannedBefore + length + 8);
                q = pos + scannedBefore;
                long frameEnd = base + q + length;
                long holeAfter = nextHole(base + q + 1);
                if (holeAfter < frameEnd) {
                    continue;
                }
                int e = q + length;
                boolean confirmed = (eof && e == end) || holeAfter == frameEnd || frameAt(e, true) >= 0;
                if (!confirmed) {
                    continue;
                }
                pos = q;
                outFrom = outTo = pos;
                return;
            }
            // No confirmed header near the hole: the bytes are handed on as they are, for the decoder to deal with.
        }
    }

    /** A PSI section (PAT or PMT) assembled from the packets of its PID. */
    private static final class Section {
        final byte[] data = new byte[1024 + 3];
        int length;
        private int have;
        private boolean open;

        void reset() {
            open = false;
            have = 0;
            length = 0;
        }

        int tableId() {
            return data[0] & 0xFF;
        }

        /** Adds a packet; true when a whole section has just been completed. */
        boolean add(byte[] b, int at) {
            int flags = b[at + 3] & 0xFF;
            if ((b[at + 1] & 0x80) != 0 || (flags & 0x10) == 0) {
                return false;
            }
            int p = at + 4;
            if ((flags & 0x20) != 0) {
                p += 1 + (b[at + 4] & 0xFF);
            }
            int to = at + PACKET;
            if (p >= to) {
                return false;
            }
            if ((b[at + 1] & 0x40) != 0) {
                int pointer = b[p] & 0xFF;
                p += 1 + pointer;
                if (p >= to) {
                    return false;
                }
                open = true;
                have = 0;
            } else if (!open) {
                return false;
            }
            int n = Math.min(to - p, data.length - have);
            System.arraycopy(b, p, data, have, n);
            have += n;
            if (have < 3) {
                return false;
            }
            int sectionLength = (data[1] & 0x0F) << 8 | (data[2] & 0xFF);
            if (sectionLength > 1021) {
                open = false;
                return false;
            }
            if (have >= 3 + sectionLength) {
                length = 3 + sectionLength;
                open = false;
                return true;
            }
            return false;
        }
    }

    private static void checkInterrupt() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("MPEG-TS decoding interrupted");
        }
    }
}

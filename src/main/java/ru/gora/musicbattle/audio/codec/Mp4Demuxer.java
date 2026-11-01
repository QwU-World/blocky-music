package ru.gora.musicbattle.audio.codec;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Finds the first audio track of an ISO base media file (MP4, M4A, MOV, 3GP; ISO/IEC 14496-12) and hands out its
 * packets in decode order, with what the codec and the trimming need: the sample entry and its decoder configuration,
 * the timescales, the edit list and iTunes' iTunSMPB gapless tag.
 *
 * <p>Both layouts are read. A plain file is indexed by its sample tables (stts, stsc, stsz or stz2, stco or co64); a
 * fragmented one (moov with mvex, then moof and mdat pairs, as DASH and YouTube's audio streams are stored) by the
 * track fragment runs (tfhd, tfdt, trun) as they come, so neither ever holds more than one packet of audio. Boxes the
 * audio does not need (video tracks, sidx, styp, free, cover art) are skipped by their size.
 *
 * <p>The demuxer knows nothing about codecs: it reports the sample entry's type and its configuration box, and the
 * caller picks a decoder. Opus in MP4 (an 'Opus' entry with a 'dOps' box) arrives the same way and only needs a
 * decoder to plug into {@link Mp4Decoder}.
 */
final class Mp4Demuxer implements Closeable {
    /** The moov box is read whole; past this it is refused rather than letting a damaged size eat the heap. */
    private static final long MAX_MOOV = 64L << 20;
    private static final int MAX_PACKET = 4 << 20;
    private static final long MAX_SAMPLES = 50_000_000L;

    /** The audio track's description. */
    static final class Track {
        int trackId;
        /** The sample entry's four-character code: mp4a, alac, Opus, ... */
        String format = "";
        /** For mp4a, the esds DecoderConfigDescriptor's object type indication; -1 when there is none. */
        int objectTypeIndication = -1;
        /** The decoder configuration: AudioSpecificConfig, the ALAC cookie, the dOps body. */
        byte[] config;
        int entryChannels;
        int entrySampleRate;
        long mediaTimescale;
        long movieTimescale;
        /** Non-empty edits in order, as {media time in media units, duration in movie units}. */
        final List<long[]> edits = new ArrayList<>();
        boolean hasEditList;
        /** iTunSMPB: priming, padding and valid samples, in media units; null when absent. */
        long[] itunSmpb;
        /** The sum of the sample durations, for a plain file; -1 for a fragmented one. */
        long sampleDurationTotal = -1;
        /** The duration of the last sample in the time-to-sample table, in media units; -1 when unknown. */
        long lastSampleDuration = -1;
        /** The media header's duration, in media units; 0 when not given. */
        long mediaDuration;
        long sampleCount;
    }

    private final Input in;
    private final Track track = new Track();
    /** Whether packets now come from movie fragments rather than the sample tables. */
    private boolean fragmented;
    /**
     * Whether a last packet that the end of the file cuts short is handed out with what there is of it (Opus, whose
     * decoder makes a frame of it, as ffmpeg's does) instead of being dropped (AAC and ALAC, whose decoders cannot).
     */
    private boolean keepCutPacket;
    private boolean cutPacketGiven;
    /** Whether the movie has fragments (an mvex box): then the sample tables, if any, describe only its start. */
    private boolean hasFragments;
    /**
     * Where the scan for the first movie fragment starts once the sample tables are used up, in a file that keeps its
     * first samples in the moov and the rest in fragments, as ffmpeg's plain +frag_keyframe output does.
     */
    private long fragmentScanStart = -1;

    // Plain files: the sample tables of the audio track.
    private int[] sizes;
    private int constantSize;
    private long[] chunkOffsets;
    private int[] stscFirstChunk;
    private int[] stscSamplesPerChunk;
    private long sampleIndex;
    private int chunkIndex;
    private int stscIndex;
    private int samplesLeftInChunk;
    private long nextOffset;

    // Fragmented files: the current run of samples.
    private long trexDefaultDuration;
    private long trexDefaultSize;
    /** The track runs of the current fragment that hold this track's samples. */
    private final List<Run> runs = new ArrayList<>();
    private int runIndex;
    /** The next sample of the current run, and where it starts. */
    private long sampleInRun;
    private long runOffset;

    private Mp4Demuxer(Input in) {
        this.in = in;
    }

    Track track() {
        return track;
    }

    /**
     * Whether the movie is fragmented, so the sample tables do not say how long the track is: the packets of a
     * fragment are only known once its moof has been read.
     */
    boolean hasFragments() {
        return hasFragments;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Opening

    /** Reads the boxes up to the audio track's index. On failure the input is left to the caller. */
    static Mp4Demuxer open(InputStream stream) throws IOException {
        return open(new StreamInput(stream));
    }

    static Mp4Demuxer open(SeekableByteChannel channel) throws IOException {
        return open(new ChannelInput(channel));
    }

    private static Mp4Demuxer open(Input in) throws IOException {
        Mp4Demuxer d = new Mp4Demuxer(in);
        d.readHeader();
        return d;
    }

    private void readHeader() throws IOException {
        boolean sawBox = false;
        while (true) {
            checkInterrupt();
            long start = in.position();
            long[] header = readBoxHeader();
            if (header == null) {
                break;
            }
            sawBox = true;
            long size = header[0];
            String type = fourcc((int) header[1]);
            long bodyStart = in.position();
            long end = size < 0 ? -1 : start + size;
            if (type.equals("moov")) {
                byte[] moov;
                if (size < 0) {
                    // Size 0: the moov is the last box and runs to the end of the file, which ISO/IEC 14496-12
                    // allows and ffmpeg reads. Its length is then only known once the file ends.
                    moov = in.readRest(MAX_MOOV);
                    end = in.position();
                } else {
                    if (end - bodyStart > MAX_MOOV) {
                        throw new AudioFormatException("The MP4 index is larger than " + (MAX_MOOV >> 20) + " MiB");
                    }
                    moov = in.readFully((int) (end - bodyStart));
                }
                parseMoov(moov);
                if (hasFragments && !fragmented) {
                    findFirstMdatAfterMoov(end);
                }
                return;
            }
            if (type.equals("mdat") || type.equals("moof")) {
                if (!in.seekable()) {
                    throw new AudioFormatException("The MP4 index comes after the audio; open the file by path");
                }
            }
            if (end < 0) {
                // A last box that runs to the end of the file, and no index before it.
                break;
            }
            in.skipTo(end);
        }
        // A file that starts like an MP4 (an ftyp box, as a cut-off download does) is told it lacks its index; only a
        // file with no box at all is not an MP4.
        throw new AudioFormatException(sawBox ? "The MP4 file has no index (moov box); it may be cut short"
                : "Not an MP4 file");
    }

    /**
     * In a movie that keeps its first samples in the moov and the rest in fragments, notes where the scan for the
     * fragments starts once the sample tables are used up. A file can go back to the end of the moov and skip the mdat
     * by its size. A stream cannot go back, so the mdat after the moov is found now, while the stream still stands
     * before it, and the scan resumes behind it; the stream is left at that mdat's body, where the table samples are.
     */
    private void findFirstMdatAfterMoov(long moovEnd) throws IOException {
        if (in.seekable()) {
            fragmentScanStart = moovEnd;
            return;
        }
        while (true) {
            checkInterrupt();
            long start = in.position();
            long[] header = readBoxHeader();
            if (header == null) {
                fragmentScanStart = Long.MAX_VALUE;
                return;
            }
            String type = fourcc((int) header[1]);
            if (type.equals("mdat")) {
                fragmentScanStart = header[0] < 0 ? Long.MAX_VALUE : start + header[0];
                return;
            }
            if (header[0] < 0 || type.equals("moof")) {
                throw new AudioFormatException("The MP4 audio is stored out of order; open the file by path");
            }
            in.skipTo(start + header[0]);
        }
    }

    /**
     * Reads a box header at the current position: {size, type}, size -1 when the box runs to the end of the file;
     * null at the end of the file.
     */
    private long[] readBoxHeader() throws IOException {
        byte[] h = new byte[8];
        int got = in.read(h, 0, 8);
        if (got <= 0) {
            return null;
        }
        if (got < 8) {
            throw new AudioFormatException("The MP4 file ends inside a box header");
        }
        long size = u32(h, 0);
        int type = (int) u32(h, 4);
        if (size == 1) {
            byte[] l = new byte[8];
            if (in.read(l, 0, 8) < 8) {
                throw new AudioFormatException("The MP4 file ends inside a box header");
            }
            size = u64(l, 0);
            if (size < 16) {
                throw new AudioFormatException("Damaged MP4 box size");
            }
        } else if (size == 0) {
            size = -1;
        } else if (size < 8) {
            throw new AudioFormatException("Damaged MP4 box size");
        }
        return new long[] {size, type};
    }

    // ---------------------------------------------------------------------------------------------------------------
    // moov

    private void parseMoov(byte[] moov) throws IOException {
        Box root = new Box(moov, 0, moov.length);
        long movieTimescale = 0;
        byte[] mvhd = root.child("mvhd");
        if (mvhd != null && mvhd.length >= 20) {
            movieTimescale = mvhd[0] == 1 ? u32(mvhd, 20) : u32(mvhd, 12);
        }
        Box chosen = null;
        String firstOther = null;
        for (Box trak : root.children("trak")) {
            Box mdia = trak.childBox("mdia");
            if (mdia == null) {
                continue;
            }
            byte[] hdlr = mdia.child("hdlr");
            if (hdlr == null || hdlr.length < 12 || !fourcc((int) u32(hdlr, 8)).equals("soun")) {
                continue;
            }
            Box stbl = mdia.path("minf", "stbl");
            byte[] stsd = stbl == null ? null : stbl.child("stsd");
            if (stsd == null || stsd.length < 16) {
                continue;
            }
            chosen = trak;
            break;
        }
        if (chosen == null) {
            throw new UnsupportedFormatException("MP4 without an audio track");
        }
        Track t = track;
        t.movieTimescale = movieTimescale;
        byte[] tkhd = chosen.child("tkhd");
        if (tkhd != null && tkhd.length >= 16) {
            t.trackId = (int) (tkhd[0] == 1 ? u32(tkhd, 20) : u32(tkhd, 12));
        }
        Box mdia = chosen.childBox("mdia");
        byte[] mdhd = mdia.child("mdhd");
        if (mdhd == null || mdhd.length < 20) {
            throw new AudioFormatException("The MP4 audio track has no media header");
        }
        t.mediaTimescale = mdhd[0] == 1 ? u32(mdhd, 20) : u32(mdhd, 12);
        t.mediaDuration = mdhd[0] == 1 ? (mdhd.length >= 32 ? u64(mdhd, 24) : 0) : u32(mdhd, 16);
        if (t.mediaDuration == 0xFFFFFFFFL || t.mediaDuration < 0) {
            t.mediaDuration = 0;
        }
        if (t.mediaTimescale <= 0) {
            throw new AudioFormatException("The MP4 audio track has no timescale");
        }
        Box stbl = mdia.path("minf", "stbl");
        readSampleEntry(new Box(stbl.child("stsd"), 8, stbl.child("stsd").length));
        Box edts = chosen.childBox("edts");
        byte[] elst = edts == null ? null : edts.child("elst");
        if (elst != null) {
            readEditList(elst);
        }
        readItunSmpb(root);
        Box mvex = root.childBox("mvex");
        if (mvex != null) {
            for (Box trex : mvex.children("trex")) {
                byte[] b = trex.body();
                if (b.length >= 24 && u32(b, 4) == t.trackId) {
                    trexDefaultDuration = u32(b, 12);
                    trexDefaultSize = u32(b, 16);
                }
            }
        }
        readSampleTables(stbl);
        hasFragments = mvex != null;
        fragmented = hasFragments && t.sampleCount == 0;
    }

    private void readSampleEntry(Box stsd) throws IOException {
        Box entry = stsd.firstChild();
        if (entry == null) {
            throw new AudioFormatException("The MP4 audio track has no sample description");
        }
        Track t = track;
        t.format = entry.type;
        byte[] e = entry.body();
        // An audio sample entry: 6 reserved, data reference index, then version (QuickTime sound description).
        if (e.length < 28) {
            throw new AudioFormatException("Damaged MP4 audio sample entry");
        }
        int version = u16(e, 8);
        t.entryChannels = u16(e, 16);
        t.entrySampleRate = (int) (u32(e, 24) >>> 16);
        int childrenAt = 28 + (version == 1 ? 16 : version == 2 ? 36 : 0);
        if (version == 2 && e.length >= 64) {
            // QuickTime sound description version 2 keeps the rate as a double and the channel count beside it.
            t.entrySampleRate = (int) Double.longBitsToDouble(u64(e, 32));
            t.entryChannels = (int) u32(e, 40);
        }
        Box children = new Box(e, Math.min(childrenAt, e.length), e.length);
        switch (t.format) {
            case "mp4a" -> {
                byte[] esds = children.child("esds");
                if (esds == null) {
                    Box wave = children.childBox("wave");
                    esds = wave == null ? null : wave.child("esds");
                }
                if (esds != null) {
                    readEsds(esds);
                }
            }
            case "alac" -> {
                byte[] cookie = children.child("alac");
                if (cookie == null) {
                    Box wave = children.childBox("wave");
                    cookie = wave == null ? null : wave.child("alac");
                }
                if (cookie != null && cookie.length >= 4 + 24) {
                    t.config = java.util.Arrays.copyOfRange(cookie, 4, cookie.length);
                }
            }
            case "Opus" -> {
                byte[] dops = children.child("dOps");
                t.config = dops;
            }
            default -> {
            }
        }
    }

    /** The ES descriptor: the DecoderConfigDescriptor's object type and its DecoderSpecificInfo (14496-1, 7.2.6). */
    private void readEsds(byte[] esds) throws AudioFormatException {
        int p = 4; // version and flags
        int[] end = new int[1];
        int tag = descriptor(esds, p, end);
        if (tag != 3) {
            return;
        }
        p = descriptorBody;
        int esEnd = end[0];
        if (p + 3 > esEnd) {
            return;
        }
        int flags = esds[p + 2] & 0xFF;
        p += 3;
        if ((flags & 0x80) != 0) {
            p += 2;
        }
        if ((flags & 0x40) != 0) {
            if (p >= esEnd) {
                return;
            }
            p += 1 + (esds[p] & 0xFF);
        }
        if ((flags & 0x20) != 0) {
            p += 2;
        }
        while (p < esEnd) {
            tag = descriptor(esds, p, end);
            if (tag < 0) {
                return;
            }
            if (tag == 4) {
                int q = descriptorBody;
                int dcEnd = end[0];
                if (q + 13 > dcEnd) {
                    return;
                }
                track.objectTypeIndication = esds[q] & 0xFF;
                q += 13;
                while (q < dcEnd) {
                    int sub = descriptor(esds, q, end);
                    if (sub < 0) {
                        return;
                    }
                    if (sub == 5) {
                        track.config = java.util.Arrays.copyOfRange(esds, descriptorBody, end[0]);
                        return;
                    }
                    q = end[0];
                }
                return;
            }
            p = end[0];
        }
    }

    private int descriptorBody;

    /** Reads a descriptor's tag and size at p; sets descriptorBody and end[0]; -1 if it does not fit. */
    private int descriptor(byte[] b, int p, int[] end) {
        if (p >= b.length) {
            return -1;
        }
        int tag = b[p++] & 0xFF;
        int size = 0;
        for (int i = 0; i < 4; i++) {
            if (p >= b.length) {
                return -1;
            }
            int v = b[p++] & 0xFF;
            size = size << 7 | (v & 0x7F);
            if ((v & 0x80) == 0) {
                break;
            }
        }
        if (size < 0 || p + size > b.length) {
            return -1;
        }
        descriptorBody = p;
        end[0] = p + size;
        return tag;
    }

    private void readEditList(byte[] elst) throws AudioFormatException {
        Track t = track;
        if (elst.length < 8) {
            return;
        }
        int version = elst[0];
        long count = u32(elst, 4);
        int entrySize = version == 1 ? 20 : 12;
        if (count > (elst.length - 8) / entrySize) {
            throw new AudioFormatException("Damaged MP4 edit list");
        }
        t.hasEditList = true;
        for (int i = 0; i < count; i++) {
            int p = 8 + i * entrySize;
            long duration;
            long mediaTime;
            if (version == 1) {
                duration = u64(elst, p);
                mediaTime = u64(elst, p + 8);
            } else {
                duration = u32(elst, p);
                mediaTime = (int) u32(elst, p + 4);
            }
            if (mediaTime == -1) {
                // An empty edit: a pause before the media starts. ffmpeg shifts the start time for it and inserts
                // no samples, so it changes nothing about the decoded audio.
                continue;
            }
            if (mediaTime < 0) {
                throw new AudioFormatException("Damaged MP4 edit list");
            }
            t.edits.add(new long[] {mediaTime, duration});
        }
    }

    /** iTunes' gapless tag in moov/udta/meta/ilst: "---- com.apple.iTunes iTunSMPB" with hex fields. */
    private void readItunSmpb(Box moov) {
        Box udta = moov.childBox("udta");
        Box meta = udta == null ? null : udta.childBox("meta");
        if (meta == null) {
            return;
        }
        // In MP4 meta is a full box (four bytes of version and flags first); QuickTime writes it without them.
        Box inner = meta.body().length >= 8 && fourcc((int) u32(meta.body(), 4)).equals("hdlr")
                ? meta : new Box(meta.body(), Math.min(4, meta.body().length), meta.body().length);
        Box ilst = inner.childBox("ilst");
        if (ilst == null) {
            return;
        }
        for (Box item : ilst.children("----")) {
            byte[] name = item.child("name");
            byte[] data = item.child("data");
            if (name == null || data == null || name.length < 4 || data.length < 8) {
                continue;
            }
            String n = new String(name, 4, name.length - 4, StandardCharsets.ISO_8859_1);
            if (!n.equals("iTunSMPB")) {
                continue;
            }
            String text = new String(data, 8, data.length - 8, StandardCharsets.ISO_8859_1).trim();
            String[] fields = text.split("\\s+");
            if (fields.length < 4) {
                continue;
            }
            try {
                long priming = Long.parseUnsignedLong(fields[1], 16);
                long padding = Long.parseUnsignedLong(fields[2], 16);
                long valid = Long.parseUnsignedLong(fields[3], 16);
                // Values past these bounds are no song's; keeping them out keeps the arithmetic far from overflow.
                if (priming >= 0 && padding >= 0 && valid >= 0 && priming < 1L << 40 && padding < 1L << 40
                        && valid < 1L << 50) {
                    track.itunSmpb = new long[] {priming, padding, valid};
                }
            } catch (NumberFormatException ignored) {
                // A malformed tag is ignored, as ffmpeg ignores it.
            }
        }
    }

    private void readSampleTables(Box stbl) throws AudioFormatException {
        Track t = track;
        byte[] stts = stbl.child("stts");
        byte[] stsc = stbl.child("stsc");
        byte[] stsz = stbl.child("stsz");
        byte[] stz2 = stbl.child("stz2");
        byte[] stco = stbl.child("stco");
        byte[] co64 = stbl.child("co64");
        long count;
        if (stsz != null && stsz.length >= 12) {
            constantSize = (int) Math.min(u32(stsz, 4), Integer.MAX_VALUE);
            count = u32(stsz, 8);
            if (count > MAX_SAMPLES) {
                throw new AudioFormatException("Damaged MP4 sample table");
            }
            if (constantSize == 0) {
                if (12 + 4 * count > stsz.length) {
                    throw new AudioFormatException("Damaged MP4 sample size table");
                }
                sizes = new int[(int) count];
                for (int i = 0; i < count; i++) {
                    sizes[i] = (int) Math.min(u32(stsz, 12 + 4 * i), Integer.MAX_VALUE);
                }
            }
        } else if (stz2 != null && stz2.length >= 12) {
            int fieldSize = stz2[7] & 0xFF;
            count = u32(stz2, 8);
            if (count > MAX_SAMPLES || (fieldSize != 4 && fieldSize != 8 && fieldSize != 16)
                    || 12 + (count * fieldSize + 7) / 8 > stz2.length) {
                throw new AudioFormatException("Damaged MP4 compact sample size table");
            }
            sizes = new int[(int) count];
            for (int i = 0; i < count; i++) {
                sizes[i] = switch (fieldSize) {
                    case 4 -> (stz2[12 + i / 2] >> (i % 2 == 0 ? 4 : 0)) & 15;
                    case 8 -> stz2[12 + i] & 0xFF;
                    default -> u16(stz2, 12 + 2 * i);
                };
            }
        } else {
            count = 0;
        }
        t.sampleCount = count;
        if (count == 0) {
            return;
        }
        if (stco != null && stco.length >= 8) {
            long n = u32(stco, 4);
            if (8 + 4 * n > stco.length) {
                throw new AudioFormatException("Damaged MP4 chunk offset table");
            }
            chunkOffsets = new long[(int) n];
            for (int i = 0; i < n; i++) {
                chunkOffsets[i] = u32(stco, 8 + 4 * i);
            }
        } else if (co64 != null && co64.length >= 8) {
            long n = u32(co64, 4);
            if (8 + 8 * n > co64.length) {
                throw new AudioFormatException("Damaged MP4 chunk offset table");
            }
            chunkOffsets = new long[(int) n];
            for (int i = 0; i < n; i++) {
                chunkOffsets[i] = u64(co64, 8 + 8 * i);
            }
        } else {
            throw new AudioFormatException("The MP4 audio track has no chunk offsets");
        }
        if (stsc == null || stsc.length < 8) {
            throw new AudioFormatException("The MP4 audio track has no sample-to-chunk table");
        }
        long n = u32(stsc, 4);
        if (n == 0 || 8 + 12 * n > stsc.length) {
            throw new AudioFormatException("Damaged MP4 sample-to-chunk table");
        }
        stscFirstChunk = new int[(int) n];
        stscSamplesPerChunk = new int[(int) n];
        for (int i = 0; i < n; i++) {
            stscFirstChunk[i] = (int) Math.min(u32(stsc, 8 + 12 * i), Integer.MAX_VALUE);
            stscSamplesPerChunk[i] = (int) Math.min(u32(stsc, 12 + 12 * i), Integer.MAX_VALUE);
            if (stscFirstChunk[i] < 1 || (i > 0 && stscFirstChunk[i] <= stscFirstChunk[i - 1])) {
                throw new AudioFormatException("Damaged MP4 sample-to-chunk table");
            }
        }
        if (stts != null && stts.length >= 8) {
            long entries = u32(stts, 4);
            if (8 + 8 * entries > stts.length) {
                throw new AudioFormatException("Damaged MP4 time-to-sample table");
            }
            long total = 0;
            for (int i = 0; i < entries; i++) {
                total += u32(stts, 8 + 8 * i) * u32(stts, 12 + 8 * i);
                if (u32(stts, 8 + 8 * i) > 0) {
                    t.lastSampleDuration = u32(stts, 12 + 8 * i);
                }
            }
            t.sampleDurationTotal = total;
        }
        chunkIndex = -1;
        stscIndex = 0;
        samplesLeftInChunk = 0;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Packets

    /**
     * Reads the next packet of the audio track into {@code buffer}, followed by {@link AacBits#PADDING} zero bytes.
     *
     * @return the packet's size, or -1 after the last one
     */
    /** See {@link #keepCutPacket}. */
    void keepCutPacket(boolean keep) {
        keepCutPacket = keep;
    }

    /** Whether the packet last handed out was cut short by the end of the file. */
    boolean lastPacketCut() {
        return cutPacketGiven;
    }

    int nextPacket(PacketBuffer buffer) throws IOException {
        checkInterrupt();
        if (cutPacketGiven) {
            return -1;
        }
        long[] where;
        if (fragmented) {
            where = nextFragmentSample();
        } else {
            where = nextTableSample();
            if (where == null && hasFragments) {
                // The moov held the first samples; the rest follow in fragments.
                fragmented = true;
                nextTopLevel = fragmentScanStart;
                where = nextFragmentSample();
            }
        }
        if (where == null) {
            return -1;
        }
        long offset = where[0];
        if (where[1] < 0 || where[1] > MAX_PACKET) {
            throw new AudioFormatException("An MP4 audio packet claims " + where[1] + " bytes");
        }
        if (offset < 0) {
            throw new AudioFormatException("An MP4 audio packet starts before the file does");
        }
        int size = (int) where[1];
        byte[] data = buffer.ensure(size + AacBits.PADDING);
        if (offset < in.position()) {
            if (!in.seekable()) {
                throw new AudioFormatException("The MP4 audio is stored out of order; open the file by path");
            }
        }
        in.seekTo(offset);
        int got = in.read(data, 0, size);
        if (got < size) {
            if (keepCutPacket && got > 0) {
                // The end of the file cuts this packet short, and nothing follows it.
                cutPacketGiven = true;
                java.util.Arrays.fill(data, got, size + AacBits.PADDING, (byte) 0);
                return got;
            }
            // A packet cut short by the end of the file: what is there is of no use to an AAC or ALAC decoder.
            return -1;
        }
        java.util.Arrays.fill(data, size, size + AacBits.PADDING, (byte) 0);
        return size;
    }

    private long[] nextTableSample() throws AudioFormatException {
        if (sampleIndex >= track.sampleCount || !nextChunkWithSamples()) {
            return null;
        }
        int size = sizes != null ? sizes[(int) sampleIndex] : constantSize;
        long offset = nextOffset;
        nextOffset += size;
        samplesLeftInChunk--;
        sampleIndex++;
        return new long[] {offset, size};
    }

    /**
     * Whether, now that the sample tables are used up, movie fragments follow with more packets of the track. Only
     * asked of a movie with an mvex box whose moov also holds samples: ffmpeg's +frag_keyframe output keeps either
     * every sample in the moov or only the first ones, and the tables cannot tell which. When fragments follow, the
     * first one is read, and the next packets come from it.
     */
    boolean fragmentsFollow() throws IOException {
        if (fragmented) {
            return true;
        }
        if (!hasFragments || sampleIndex < track.sampleCount) {
            return false;
        }
        fragmented = true;
        nextTopLevel = fragmentScanStart;
        return readNextFragment();
    }

    /**
     * A track run, kept as the trun box itself rather than as one entry per sample: a run's sample count is a 32-bit
     * field, and a damaged one must not turn a few bytes of file into millions of objects.
     */
    private static final class Run {
        final long offset;
        final long count;
        final long defaultSize;
        /** The trun body when it lists each sample's size, else null; where the first size is, and the stride. */
        final byte[] table;
        final int sizeAt;
        final int stride;

        Run(long offset, long count, long defaultSize, byte[] table, int sizeAt, int stride) {
            this.offset = offset;
            this.count = count;
            this.defaultSize = defaultSize;
            this.table = table;
            this.sizeAt = sizeAt;
            this.stride = stride;
        }

        long size(long sample) {
            return table == null ? defaultSize : u32(table, sizeAt + (int) (sample * stride));
        }

        /** Whether every sample of the run is empty without the run listing them: no sizes, default size 0. */
        boolean holdsNoData() {
            return table == null && defaultSize == 0;
        }
    }

    /** The next sample of a fragmented file, reading moof boxes as needed. */
    private long[] nextFragmentSample() throws IOException {
        while (true) {
            if (runIndex >= runs.size()) {
                if (!readNextFragment()) {
                    return null;
                }
                continue;
            }
            Run run = runs.get(runIndex);
            if (sampleInRun >= run.count || run.holdsNoData()) {
                // A run that lists no sizes and whose default size is 0 holds only empty samples. Its count is a
                // 32-bit field that costs the file four bytes, so it is passed over in one step: walking it sample by
                // sample let a small file of many such fragments keep open() or one read() busy for hours.
                runIndex++;
                sampleInRun = 0;
                continue;
            }
            if (sampleInRun == 0) {
                runOffset = run.offset;
            }
            long size = run.size(sampleInRun);
            long offset = runOffset;
            runOffset += size;
            sampleInRun++;
            if (size > 0) {
                return new long[] {offset, size};
            }
            // An empty sample holds no frame. Each one listed costs the file four bytes of its run's size table, so
            // this loop is as long as the file; it still answers an interrupt.
            if ((sampleInRun & 0xFFFF) == 0) {
                checkInterrupt();
            }
        }
    }

    /**
     * Passes over the next {@code n} packets without reading them, for a decoder that starts later than the first
     * packet. Runs and chunks of equal sizes are passed in one step each, so the time this takes depends on how many
     * runs and chunks the file has, not on how many samples they claim.
     *
     * @return how many packets were passed, fewer than {@code n} when the track ends first
     */
    long skipPackets(long n) throws IOException {
        long passed = 0;
        while (passed < n) {
            checkInterrupt();
            if (!fragmented) {
                if (sampleIndex >= track.sampleCount || !nextChunkWithSamples()) {
                    if (!hasFragments) {
                        return passed;
                    }
                    // The moov held the first samples; the rest follow in fragments.
                    fragmented = true;
                    nextTopLevel = fragmentScanStart;
                    continue;
                }
                long k = Math.min(n - passed, Math.min(samplesLeftInChunk, track.sampleCount - sampleIndex));
                if (sizes == null) {
                    nextOffset += k * constantSize;
                } else {
                    for (long i = 0; i < k; i++) {
                        nextOffset += sizes[(int) (sampleIndex + i)];
                    }
                }
                samplesLeftInChunk -= (int) k;
                sampleIndex += k;
                passed += k;
                continue;
            }
            if (runIndex >= runs.size()) {
                if (!readNextFragment()) {
                    return passed;
                }
                continue;
            }
            Run run = runs.get(runIndex);
            if (sampleInRun >= run.count || run.holdsNoData()) {
                runIndex++;
                sampleInRun = 0;
                continue;
            }
            if (sampleInRun == 0) {
                runOffset = run.offset;
            }
            if (run.table == null) {
                // Every sample of the run is a packet of the default size.
                long k = Math.min(n - passed, run.count - sampleInRun);
                runOffset += k * run.defaultSize;
                sampleInRun += k;
                passed += k;
            } else {
                // Sizes listed one by one, four bytes of the file each; empty samples are no packets, as in
                // nextFragmentSample.
                long size = run.size(sampleInRun);
                runOffset += size;
                sampleInRun++;
                if (size > 0) {
                    passed++;
                }
            }
        }
        return passed;
    }

    /**
     * Moves to the next chunk of the sample tables that holds samples, as {@link #nextTableSample} does; false when
     * the chunks run out.
     */
    private boolean nextChunkWithSamples() {
        while (samplesLeftInChunk == 0) {
            chunkIndex++;
            if (chunkOffsets == null || chunkIndex >= chunkOffsets.length) {
                return false;
            }
            while (stscIndex + 1 < stscFirstChunk.length && stscFirstChunk[stscIndex + 1] <= chunkIndex + 1) {
                stscIndex++;
            }
            samplesLeftInChunk = stscSamplesPerChunk[stscIndex];
            nextOffset = chunkOffsets[chunkIndex];
        }
        return true;
    }

    /**
     * Moves to the next moof and reads its runs for our track. False at the end. The scan always continues from
     * where the previous fragment's boxes end (the mdat after the moof), not from where the last sample did, because
     * an mdat may hold other tracks' data or padding after ours.
     */
    private boolean readNextFragment() throws IOException {
        runs.clear();
        runIndex = 0;
        sampleInRun = 0;
        if (nextTopLevel < 0) {
            nextTopLevel = in.position();
        }
        while (true) {
            checkInterrupt();
            if (nextTopLevel == Long.MAX_VALUE) {
                // The last box ran to the end of the file: nothing can follow it.
                return false;
            }
            if (nextTopLevel < in.position() && !in.seekable()) {
                // The last fragment's samples ran past its mdat: a stream cannot go back for the boxes in between.
                throw new AudioFormatException("The MP4 fragments overlap; open the file by path");
            }
            in.seekTo(nextTopLevel);
            long start = in.position();
            long[] header = readBoxHeader();
            if (header == null) {
                return false;
            }
            long size = header[0];
            String type = fourcc((int) header[1]);
            if (size < 0) {
                // A last box that runs to the end of the file holds no further fragment headers.
                return false;
            }
            long end = start + size;
            nextTopLevel = end;
            if (!type.equals("moof")) {
                continue;
            }
            if (size > MAX_MOOV) {
                throw new AudioFormatException("An MP4 fragment header is larger than " + (MAX_MOOV >> 20) + " MiB");
            }
            byte[] moof = in.readFully((int) (end - in.position()));
            parseMoof(moof, start);
            // The fragment's data is normally the mdat right after it; the next scan starts behind that mdat.
            long[] next = readBoxHeader();
            if (next != null && fourcc((int) next[1]).equals("mdat")) {
                nextTopLevel = next[0] < 0 ? Long.MAX_VALUE : end + next[0];
            } else if (in.seekable()) {
                in.seekTo(end);
            } else if (next != null) {
                nextTopLevel = next[0] < 0 ? Long.MAX_VALUE : end + next[0];
            }
            if (!runs.isEmpty()) {
                return true;
            }
        }
    }

    private long nextTopLevel = -1;

    private void parseMoof(byte[] moof, long moofStart) throws AudioFormatException {
        Box root = new Box(moof, 0, moof.length);
        long previousEnd = moofStart;
        for (Box traf : root.children("traf")) {
            byte[] tfhd = traf.child("tfhd");
            if (tfhd == null || tfhd.length < 8) {
                continue;
            }
            int flags = (int) (u32(tfhd, 0) & 0xFFFFFF);
            long trackId = u32(tfhd, 4);
            int p = 8;
            long base = (flags & 0x020000) != 0 ? moofStart : previousEnd;
            if ((flags & 0x000001) != 0) {
                if (p + 8 > tfhd.length) {
                    throw new AudioFormatException("Damaged MP4 fragment header");
                }
                base = u64(tfhd, p);
                p += 8;
            }
            if ((flags & 0x000002) != 0) {
                p += 4;
            }
            long defaultDuration = trexDefaultDuration;
            long defaultSize = trexDefaultSize;
            if ((flags & 0x000008) != 0) {
                if (p + 4 > tfhd.length) {
                    throw new AudioFormatException("Damaged MP4 fragment header");
                }
                defaultDuration = u32(tfhd, p);
                p += 4;
            }
            if ((flags & 0x000010) != 0) {
                if (p + 4 > tfhd.length) {
                    throw new AudioFormatException("Damaged MP4 fragment header");
                }
                defaultSize = u32(tfhd, p);
            }
            boolean ours = trackId == track.trackId || track.trackId == 0;
            long dataEnd = base;
            for (Box trun : traf.children("trun")) {
                byte[] r = trun.body();
                if (r.length < 8) {
                    throw new AudioFormatException("Damaged MP4 track run");
                }
                int rf = (int) (u32(r, 0) & 0xFFFFFF);
                long count = u32(r, 4);
                int q = 8;
                long offset = dataEnd;
                if ((rf & 0x000001) != 0) {
                    if (q + 4 > r.length) {
                        throw new AudioFormatException("Damaged MP4 track run");
                    }
                    offset = base + (int) u32(r, q);
                    q += 4;
                }
                if ((rf & 0x000004) != 0) {
                    q += 4;
                }
                int perSample = (((rf & 0x100) != 0) ? 4 : 0) + (((rf & 0x200) != 0) ? 4 : 0)
                        + (((rf & 0x400) != 0) ? 4 : 0) + (((rf & 0x800) != 0) ? 4 : 0);
                if (count > MAX_SAMPLES || (long) q + count * perSample > r.length) {
                    throw new AudioFormatException("Damaged MP4 track run");
                }
                boolean sizes = (rf & 0x200) != 0;
                Run run = new Run(offset, count, defaultSize, sizes ? r : null,
                        q + ((rf & 0x100) != 0 ? 4 : 0), perSample);
                // Where the next run starts when it gives no data offset of its own: right after this one.
                long bytes = 0;
                if (sizes) {
                    for (long i = 0; i < count; i++) {
                        bytes += run.size(i);
                    }
                } else {
                    bytes = count * defaultSize;
                }
                if (ours && count > 0) {
                    runs.add(run);
                }
                dataEnd = offset + bytes;
            }
            previousEnd = dataEnd;
            if (defaultDuration < 0) {
                throw new AudioFormatException("Damaged MP4 fragment header");
            }
        }
    }

    @Override
    public void close() throws IOException {
        in.close();
    }

    private static void checkInterrupt() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("MP4 decoding interrupted");
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Boxes held in memory

    /** A run of boxes inside a byte array, as found in moov and moof. */
    private static final class Box {
        final byte[] data;
        final int start;
        final int end;
        String type = "";

        Box(byte[] data, int start, int end) {
            this.data = data;
            this.start = start;
            this.end = end;
        }

        /** The body of this box. */
        byte[] body() {
            return java.util.Arrays.copyOfRange(data, start, end);
        }

        List<Box> children(String wanted) {
            List<Box> out = new ArrayList<>();
            int p = start;
            while (p + 8 <= end) {
                long size = u32(data, p);
                String type = fourcc((int) u32(data, p + 4));
                int header = 8;
                if (size == 1) {
                    if (p + 16 > end) {
                        break;
                    }
                    size = u64(data, p + 8);
                    header = 16;
                } else if (size == 0) {
                    size = end - p;
                }
                if (size < header || size > end - p) {
                    break;
                }
                if (wanted == null || type.equals(wanted)) {
                    Box b = new Box(data, p + header, (int) (p + size));
                    b.type = type;
                    out.add(b);
                }
                p += (int) size;
            }
            return out;
        }

        Box firstChild() {
            List<Box> all = children(null);
            return all.isEmpty() ? null : all.get(0);
        }

        Box childBox(String type) {
            List<Box> all = children(type);
            return all.isEmpty() ? null : all.get(0);
        }

        byte[] child(String type) {
            Box b = childBox(type);
            return b == null ? null : b.body();
        }

        Box path(String... types) {
            Box b = this;
            for (String t : types) {
                b = b.childBox(t);
                if (b == null) {
                    return null;
                }
            }
            return b;
        }
    }

    static long u32(byte[] b, int p) {
        return (b[p] & 0xFFL) << 24 | (b[p + 1] & 0xFFL) << 16 | (b[p + 2] & 0xFFL) << 8 | (b[p + 3] & 0xFFL);
    }

    static long u64(byte[] b, int p) {
        return u32(b, p) << 32 | u32(b, p + 4);
    }

    static int u16(byte[] b, int p) {
        return (b[p] & 0xFF) << 8 | (b[p + 1] & 0xFF);
    }

    static String fourcc(int v) {
        char[] c = {(char) ((v >>> 24) & 0xFF), (char) ((v >>> 16) & 0xFF), (char) ((v >>> 8) & 0xFF), (char) (v & 0xFF)};
        return new String(c);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Input

    /** A reusable packet buffer that grows to the largest packet seen. */
    static final class PacketBuffer {
        private byte[] data = new byte[4096];

        byte[] ensure(int size) {
            if (data.length < size) {
                data = new byte[Math.max(size, data.length * 2)];
            }
            return data;
        }

        byte[] data() {
            return data;
        }
    }

    /** Where the bytes come from: a stream read once, or a file that can be read anywhere. */
    private abstract static class Input implements Closeable {
        abstract long position();

        abstract boolean seekable();

        /** Reads up to len bytes, as many as there are; returns how many (0 at the end). */
        abstract int read(byte[] b, int off, int len) throws IOException;

        /** Moves forward to an absolute position, or anywhere when seekable. */
        abstract void seekTo(long position) throws IOException;

        void skipTo(long position) throws IOException {
            seekTo(position);
        }

        /**
         * Reads exactly n bytes, growing the array as the bytes arrive, so a size field that lies costs no more memory
         * than the file holds.
         */
        byte[] readFully(int n) throws IOException {
            byte[] out = new byte[Math.min(n, 1 << 16)];
            int got = 0;
            while (got < n) {
                if (got == out.length) {
                    out = java.util.Arrays.copyOf(out, (int) Math.min(n, out.length * 2L));
                }
                int r = read(out, got, out.length - got);
                if (r <= 0) {
                    throw new AudioFormatException("The MP4 file ends inside a box");
                }
                got += r;
            }
            return out;
        }

        /**
         * Reads everything up to the end of the file, growing the array as the bytes arrive, and refuses more than
         * {@code max} bytes, so a box that runs to the end of a large file costs no more memory than that.
         */
        byte[] readRest(long max) throws IOException {
            byte[] out = new byte[1 << 12];
            int got = 0;
            while (true) {
                if (got == out.length) {
                    if (got >= max) {
                        throw new AudioFormatException("The MP4 index is larger than " + (max >> 20) + " MiB");
                    }
                    out = java.util.Arrays.copyOf(out, (int) Math.min(max + 1, out.length * 2L));
                }
                int r = read(out, got, out.length - got);
                if (r <= 0) {
                    return java.util.Arrays.copyOf(out, got);
                }
                got += r;
            }
        }
    }

    private static final class StreamInput extends Input {
        private final InputStream in;
        private long position;
        private final byte[] skipBuffer = new byte[1 << 14];

        StreamInput(InputStream in) {
            this.in = in;
        }

        @Override
        long position() {
            return position;
        }

        @Override
        boolean seekable() {
            return false;
        }

        @Override
        int read(byte[] b, int off, int len) throws IOException {
            int total = 0;
            while (total < len) {
                checkInterrupt();
                int r = in.read(b, off + total, len - total);
                if (r < 0) {
                    break;
                }
                if (r == 0) {
                    int one = in.read();
                    if (one < 0) {
                        break;
                    }
                    b[off + total++] = (byte) one;
                    continue;
                }
                total += r;
            }
            position += total;
            return total;
        }

        @Override
        void seekTo(long target) throws IOException {
            if (target < position) {
                throw new AudioFormatException("The MP4 file would have to be read backwards; open it by path");
            }
            while (position < target) {
                int n = read(skipBuffer, 0, (int) Math.min(skipBuffer.length, target - position));
                if (n <= 0) {
                    return;
                }
            }
        }

        @Override
        public void close() throws IOException {
            in.close();
        }
    }

    private static final class ChannelInput extends Input {
        private final SeekableByteChannel channel;
        private final ByteBuffer buffer = ByteBuffer.allocate(1 << 16);
        private long bufferStart;
        private long position;

        ChannelInput(SeekableByteChannel channel) {
            this.channel = channel;
            buffer.limit(0);
        }

        @Override
        long position() {
            return position;
        }

        @Override
        boolean seekable() {
            return true;
        }

        @Override
        int read(byte[] b, int off, int len) throws IOException {
            try {
                return readFromChannel(b, off, len);
            } catch (java.nio.channels.ClosedByInterruptException interrupted) {
                // A file channel closes itself when its thread is interrupted during a read; the conversion has to
                // see that as the interrupt it is.
                InterruptedIOException stopped = new InterruptedIOException("MP4 decoding interrupted");
                stopped.initCause(interrupted);
                throw stopped;
            }
        }

        private int readFromChannel(byte[] b, int off, int len) throws IOException {
            int total = 0;
            while (total < len) {
                checkInterrupt();
                long inBuffer = position - bufferStart;
                if (inBuffer < 0 || inBuffer >= buffer.limit()) {
                    if (len - total >= buffer.capacity()) {
                        // A large read goes straight to the channel.
                        channel.position(position);
                        int r = channel.read(ByteBuffer.wrap(b, off + total, len - total));
                        if (r <= 0) {
                            break;
                        }
                        total += r;
                        position += r;
                        continue;
                    }
                    channel.position(position);
                    buffer.clear();
                    int r = 0;
                    while (buffer.hasRemaining()) {
                        int n = channel.read(buffer);
                        if (n <= 0) {
                            break;
                        }
                        r += n;
                    }
                    buffer.flip();
                    bufferStart = position;
                    if (r <= 0) {
                        break;
                    }
                    inBuffer = 0;
                }
                int n = (int) Math.min(len - total, buffer.limit() - inBuffer);
                System.arraycopy(buffer.array(), (int) inBuffer, b, off + total, n);
                total += n;
                position += n;
            }
            return total;
        }

        @Override
        void seekTo(long target) {
            position = target;
        }

        @Override
        public void close() throws IOException {
            channel.close();
        }
    }
}

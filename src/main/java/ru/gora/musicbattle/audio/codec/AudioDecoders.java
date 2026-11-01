package ru.gora.musicbattle.audio.codec;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Opens a song with the right decoder, chosen by what the file's first bytes are and never by its name: a renamed file
 * keeps its old content, and an osu! map may call its Ogg song .mp3, so the name is only a guess at what is inside.
 *
 * <p>The mod reads Ogg Vorbis, Ogg Opus, Ogg FLAC, WebM and Matroska (Opus, Vorbis, AAC, MP3, FLAC or PCM in them),
 * MP3 (and MPEG Layer I/II, free format included), FLAC, WAV (PCM, float, G.711, IMA and Microsoft ADPCM, MPEG audio;
 * RIFF, RIFX, RF64), AAC, ALAC and Opus in MP4/M4A/MOV, raw AAC (ADTS), and MPEG transport streams (AAC, MPEG audio or
 * Opus in .ts, .m2ts and the joined segments of an HLS download). Anything else is refused with
 * {@link UnsupportedFormatException}, which names the format when it is one players meet (WMA, AC-3 in a WebM, an MP4
 * or a transport stream, ambisonic Opus), so a caller can tell the player to convert the file rather than that
 * something broke. A file of a supported kind that turns out to be damaged is a plain {@link AudioFormatException}
 * from its decoder.
 */
public final class AudioDecoders {
    /**
     * How much of the start is looked at for a signature. An ID3v2 tag is skipped first, wherever it ends: a tag
     * carrying cover art is often hundreds of kilobytes long, and Ogg, FLAC and WAV can hide behind one as well as MP3.
     */
    private static final int HEAD_BYTES = 64;
    /**
     * How much is read to look further than the first bytes: past another Ogg stream's first page (a skeleton or a video
     * stream can come before the audio), and to the DocType of a WebM file whose muxer wrote long size fields.
     */
    private static final int WIDE_HEAD_BYTES = 64 * 1024;
    /** More tags than this in a row is not a song. */
    private static final int MAX_LEADING_TAGS = 16;
    /** Read size for the decoders: they ask for small pieces, and a file answers each one with a system call. */
    private static final int READ_BUFFER = 1 << 16;
    private AudioDecoders() {
    }

    /** The formats this package decodes. */
    public enum Kind {
        VORBIS(ChannelDownmix.Layout.VORBIS),
        /** Ogg Opus; more than two channels come in Vorbis order (RFC 7845 section 5.1.1.2). */
        OPUS(ChannelDownmix.Layout.VORBIS),
        /** Opus in WebM or Matroska, which carries the same identification header as Ogg Opus. */
        WEBM(ChannelDownmix.Layout.VORBIS),
        MP3(ChannelDownmix.Layout.WAVE),
        FLAC(ChannelDownmix.Layout.WAVE),
        /** FLAC in Ogg (.oga), the same frames as a .flac file; see {@link OggFlacDecoder}. */
        OGG_FLAC(ChannelDownmix.Layout.WAVE),
        WAV(ChannelDownmix.Layout.WAVE),
        /** MP4, M4A, MOV and 3GP: AAC (LC, HE-AAC, HE-AAC v2) or ALAC, read by path because the index may come last. */
        MP4(ChannelDownmix.Layout.WAVE),
        /** Raw AAC in ADTS frames (.aac). */
        ADTS(ChannelDownmix.Layout.WAVE),
        /**
         * An MPEG transport stream (.ts, .m2ts, and the joined segments of an HLS download, which may be named .m4a or
         * .mp4) with AAC, MPEG audio or Opus in it; Opus comes out in WAVE order too.
         */
        TS(ChannelDownmix.Layout.WAVE);

        private final ChannelDownmix.Layout layout;

        Kind(ChannelDownmix.Layout layout) {
            this.layout = layout;
        }

        /** The order of the channels when a file of this kind has more than two. */
        public ChannelDownmix.Layout layout() {
            return layout;
        }
    }

    /**
     * Which decoder reads this file, from its first bytes.
     *
     * @throws UnsupportedFormatException when no decoder here reads it
     * @throws IOException when the file cannot be read
     */
    public static Kind detect(Path file) throws IOException {
        Objects.requireNonNull(file, "file");
        try (SeekableByteChannel channel = Files.newByteChannel(file)) {
            byte[] head = readAt(channel, 0L, HEAD_BYTES);
            long position = 0L;
            byte[] body = head;
            // Tags in a row are passed one after another, as open() and the MP3 decoder pass them.
            for (int tags = 0; tags < MAX_LEADING_TAGS; tags++) {
                long tag = id3v2Length(body);
                if (tag <= 0) {
                    break;
                }
                position += tag;
                body = readAt(channel, position, HEAD_BYTES);
            }
            if (startsWith(body, 0, "\u001A\u0045\u00DF\u00A3")) {
                // EBML: WebM or Matroska when its DocType says so, which a muxer with long size fields can push
                // past the first 64 bytes. Its sound may still be another codec than Opus; confirm() tells.
                if (!WebmDecoder.looksLike(readAt(channel, position, WIDE_HEAD_BYTES))) {
                    throw new UnsupportedFormatException("WebM/MKV");
                }
                confirm(file, position, Kind.WEBM);
                return Kind.WEBM;
            }
            // Seven bytes are a weak signature, so ADTS is believed only once its frames chain up.
            boolean adts = AdtsDecoder.looksLike(body)
                    && AdtsDecoder.findsFrames(readAt(channel, position, AdtsDecoder.SYNC_PROBE_BYTES));
            Kind kind = classify(head, body, position > 0, adts);
            if (kind == null || kind == Kind.MP3 || kind == Kind.ADTS) {
                byte[] probe = readAt(channel, position, MpegTsDecoder.PROBE_BYTES);
                if (MpegTsDecoder.looksLike(probe, position + probe.length >= channel.size())) {
                    // An MPEG transport stream: the segments of an HLS download joined without ffmpeg (such a file
                    // can be named .m4a or .mp4), a TV recording, a camcorder's or a Blu-ray's .m2ts. It is asked for
                    // before any weak verdict is believed: its sound comes in pieces of at most 184 bytes between
                    // packet headers, and a quiet start can fit three ADTS frames into one piece, which the frame
                    // tests here and below would take for raw AAC. Its program table is read now, so a stream whose
                    // sound is in a codec no decoder here reads (AC-3, say) is named at once.
                    confirm(file, position, Kind.TS);
                    return Kind.TS;
                }
            }
            if (kind == Kind.MP3 && adtsFurtherIn(channel, position)) {
                // MP3 was said on weak evidence: a tag, or one MPEG audio header in the first bytes, which the data of
                // an AAC stream holds now and then. Three ADTS frames chained further in say more, and no MPEG audio
                // header can begin such a chain (ADTS has layer 0, which MPEG audio reserves). This is AAC that starts
                // part way into a frame, such as a recording of a broadcast, tagged by its recorder or not.
                kind = Kind.ADTS;
            }
            if (kind == Kind.VORBIS && !firstPacketIsVorbis(body)
                    && OpusDecoder.looksLike(readAt(channel, position, WIDE_HEAD_BYTES))) {
                // The first stream is neither Vorbis nor Opus (a skeleton or a video), and an Opus stream follows.
                kind = Kind.OPUS;
            }
            if (kind == Kind.OPUS) {
                confirm(file, position, kind);
            }
            if (kind != null) {
                return kind;
            }
            // No signature at all. Raw AAC that does not start with a frame, such as a recording of a broadcast that
            // begins part way into one: the ADTS decoder skips what comes before the first frame, as ffmpeg does, and
            // starts where this finds three frames chained. It is asked before MP3, whose two-frame test is the weaker
            // one: it found a false pair in the AAC data of one in 360 cut streams, while no MPEG audio header can
            // begin an ADTS chain.
            if (adtsFurtherIn(channel, position)) {
                return Kind.ADTS;
            }
            // An MP3 without a tag may still start with a stray byte or a run of zeros before its first frame, which
            // ffmpeg and the MP3 decoder look past: the decoder's own test, two consecutive matching frames within
            // 64 KiB, decides before the file is refused.
            if (Mp3Decoder.findsFrames(readAt(channel, position, Mp3Decoder.SYNC_PROBE_BYTES))) {
                return Kind.MP3;
            }
            throw new UnsupportedFormatException(null);
        }
    }

    /** Whether a chain of ADTS frames starts within {@link AdtsDecoder#SCAN_BYTES} of {@code position}. */
    private static boolean adtsFurtherIn(SeekableByteChannel channel, long position) throws IOException {
        byte[] probe = readAt(channel, position, AdtsDecoder.SCAN_BYTES + AdtsDecoder.SYNC_PROBE_BYTES);
        return AdtsDecoder.findFrameStart(probe, AdtsDecoder.SCAN_BYTES) >= 0;
    }

    /**
     * Opens a song for decoding, choosing the decoder by its content. The returned stream owns the file and closes it.
     *
     * @throws UnsupportedFormatException when no decoder here reads it
     * @throws AudioFormatException when it is a supported kind but damaged
     */
    public static PcmStream open(Path file) throws IOException {
        return open(file, detect(file));
    }

    /**
     * Opens a song with the decoder {@link #detect(Path)} already chose. Ogg Vorbis is decoded on the timeline Minecraft
     * plays ({@link VorbisDecoder.Timeline#MINECRAFT}): a copy of a beatmap's song has to line up with the game's own
     * playback of it, sample for sample, and a file the game reads differently from the specification would otherwise
     * come out early or late.
     */
    public static PcmStream open(Path file, Kind kind) throws IOException {
        Objects.requireNonNull(kind, "kind");
        if (kind == Kind.MP4) {
            // The index (moov) of an M4A is often written after its audio, so it is read with random access.
            return Mp4Decoder.open(file);
        }
        if (kind == Kind.TS) {
            // Read twice, as ffmpeg reads it: the program table first, then the audio from the start.
            return MpegTsDecoder.open(file);
        }
        InputStream in = new BufferedInputStream(Files.newInputStream(file), READ_BUFFER);
        try {
            // detect() looked past any ID3v2 tag in front, so every decoder is handed the stream from where it did.
            skipId3v2(in);
            return switch (kind) {
                case VORBIS -> VorbisDecoder.open(in, VorbisDecoder.Timeline.MINECRAFT);
                case OPUS -> OpusDecoder.open(in);
                case WEBM -> WebmDecoder.open(in);
                case MP3 -> Mp3Decoder.open(in);
                case FLAC -> FlacDecoder.open(in);
                case OGG_FLAC -> OggFlacDecoder.open(in);
                case WAV -> WavDecoder.open(in);
                case ADTS -> AdtsDecoder.open(in);
                case MP4, TS -> throw new IllegalStateException(kind + " is opened by path above");
            };
        } catch (IOException | RuntimeException exception) {
            // On failure every decoder leaves the stream to its caller.
            try {
                in.close();
            } catch (IOException suppressed) {
                exception.addSuppressed(suppressed);
            }
            throw exception;
        }
    }

    /**
     * Skips the ID3v2 tags at the start of a stream, as many as there are, so the decoder meets the bytes detect()
     * classified. Taggers put them in front of Ogg and WAV files as readily as MP3 ones, and only the MP3 and FLAC
     * decoders would skip one themselves.
     */
    private static void skipId3v2(InputStream in) throws IOException {
        byte[] head = new byte[10];
        while (true) {
            in.mark(head.length);
            int read = in.readNBytes(head, 0, head.length);
            long length = read == head.length ? id3v2Length(head) : -1L;
            if (length < 0) {
                in.reset();
                return;
            }
            long left = length - head.length;
            while (left > 0) {
                long skipped = in.skip(left);
                if (skipped <= 0) {
                    if (in.read() < 0) {
                        return;
                    }
                    skipped = 1;
                }
                left -= skipped;
            }
        }
    }

    /**
     * Reads a file's headers to find out whether a decoder here plays it. The codec of a WebM file or of a transport
     * stream is only known from its track list or program table, and an Ogg Opus stream may use a channel mapping
     * that has no speakers to fold into stereo; such files are refused here, by name, like every other format players
     * meet, rather than part way through a conversion.
     * Damaged headers are left for the conversion to report with the decoder's own message.
     */
    private static void confirm(Path file, long position, Kind kind) throws IOException {
        try (InputStream in = new BufferedInputStream(Files.newInputStream(file), READ_BUFFER)) {
            in.skipNBytes(position);
            PcmStream stream = switch (kind) {
                case OPUS -> OpusDecoder.open(in);
                case TS -> MpegTsDecoder.open(in);
                default -> WebmDecoder.open(in);
            };
            stream.close();
        } catch (UnsupportedFormatException unsupported) {
            throw unsupported;
        } catch (AudioFormatException damaged) {
            // Reported by the conversion, which opens the file again.
        }
    }

    private static byte[] readAt(SeekableByteChannel channel, long position, int length) throws IOException {
        if (position >= channel.size()) {
            return new byte[0];
        }
        channel.position(position);
        ByteBuffer buffer = ByteBuffer.allocate((int) Math.min(length, channel.size() - position));
        while (buffer.hasRemaining() && channel.read(buffer) > 0) {
            // Keep reading: a channel may hand back fewer bytes than asked for.
        }
        byte[] bytes = new byte[buffer.position()];
        buffer.flip();
        buffer.get(bytes);
        return bytes;
    }

    /**
     * The whole length of an ID3v2 tag at the start, footer included, or -1 when there is none. The same rule as the
     * MP3 and FLAC decoders use to skip it, so the bytes looked at here are the ones they will meet.
     */
    private static long id3v2Length(byte[] b) {
        if (b.length < 10 || b[0] != 'I' || b[1] != 'D' || b[2] != '3' || (b[3] & 0xFF) == 0xFF
                || (b[4] & 0xFF) == 0xFF || (b[6] | b[7] | b[8] | b[9]) < 0) {
            return -1L;
        }
        long size = (b[6] & 0x7FL) << 21 | (b[7] & 0x7FL) << 14 | (b[8] & 0x7FL) << 7 | (b[9] & 0x7FL);
        return 10L + size + (b[3] == 4 && (b[5] & 0x10) != 0 ? 10L : 0L);
    }

    /** The kind the first bytes say, or null when they match no signature at all. */
    private static Kind classify(byte[] head, byte[] body, boolean tagged, boolean adts)
            throws UnsupportedFormatException {
        if (startsWith(body, 0, "OggS")) {
            return oggKind(body);
        }
        if (startsWith(body, 0, "fLaC")) {
            return Kind.FLAC;
        }
        if (WavDecoder.looksLike(body)) {
            return Kind.WAV;
        }
        if (Mp4Decoder.looksLike(body)) {
            return Kind.MP4;
        }
        if (adts) {
            return Kind.ADTS;
        }
        String other = otherFormat(body);
        if (other != null) {
            throw new UnsupportedFormatException(other);
        }
        // An ID3v2 tag is MP3's own: the MP3 decoder skips it and finds the frames behind it, junk and padding
        // included, so a tagged file goes there unless the bytes after the tag said otherwise above.
        if (tagged || Mp3Decoder.looksLike(head)) {
            return Kind.MP3;
        }
        return null;
    }

    /**
     * Ogg is a container. Vorbis, Opus and FLAC are handed to their decoders, Vorbis and Opus also behind another
     * stream's first page; Speex, the other codec players meet in Ogg, is named, so the message says what the file is.
     */
    private static Kind oggKind(byte[] page) throws UnsupportedFormatException {
        if (page.length < 28) {
            return Kind.VORBIS;
        }
        int packet = 27 + (page[26] & 0xFF);
        if (startsWith(page, packet, "OpusHead")) {
            return Kind.OPUS;
        }
        if (startsWith(page, packet, "\u007FFLAC")) {
            // The usual content of an .oga file, and what ffmpeg writes for -c:a flac -f ogg. It was refused by name.
            return Kind.OGG_FLAC;
        }
        if (startsWith(page, packet, "Speex   ")) {
            throw new UnsupportedFormatException("Speex");
        }
        return Kind.VORBIS;
    }

    /** Whether an Ogg page's first packet is a Vorbis identification header. */
    private static boolean firstPacketIsVorbis(byte[] page) {
        return page.length >= 28 && startsWith(page, 27 + (page[26] & 0xFF), "\u0001vorbis");
    }

    /** Names the formats people try to use as songs that the mod cannot read, or returns null. */
    private static String otherFormat(byte[] b) {
        if (startsWith(b, 0, "ADIF")) {
            return "AAC (ADIF)";
        }
        if (b.length >= 4 && (b[0] & 0xFF) == 0x30 && (b[1] & 0xFF) == 0x26 && (b[2] & 0xFF) == 0xB2
                && (b[3] & 0xFF) == 0x75) {
            return "WMA/WMV";
        }
        if (startsWith(b, 0, "RIFF") && startsWith(b, 8, "AVI ")) {
            return "AVI";
        }
        if (startsWith(b, 0, "FORM") && (startsWith(b, 8, "AIFF") || startsWith(b, 8, "AIFC"))) {
            return "AIFF";
        }
        if (startsWith(b, 0, "caff")) {
            return "CAF";
        }
        if (startsWith(b, 0, "#!AMR")) {
            return "AMR";
        }
        if (startsWith(b, 0, "MAC ")) {
            return "Monkey's Audio";
        }
        if (startsWith(b, 0, "wvpk")) {
            return "WavPack";
        }
        if (startsWith(b, 0, "MThd")) {
            return "MIDI";
        }
        if (startsWith(b, 0, "FLV")) {
            return "FLV";
        }
        if (b.length >= 4 && b[0] == 0 && b[1] == 0 && b[2] == 1 && ((b[3] & 0xFF) == 0xBA || (b[3] & 0xFF) == 0xB3)) {
            return "MPEG video";
        }
        return null;
    }

    private static boolean startsWith(byte[] bytes, int offset, String ascii) {
        byte[] expected = ascii.getBytes(StandardCharsets.ISO_8859_1);
        if (offset < 0 || bytes.length < offset + expected.length) {
            return false;
        }
        for (int i = 0; i < expected.length; i++) {
            if (bytes[offset + i] != expected[i]) {
                return false;
            }
        }
        return true;
    }
}

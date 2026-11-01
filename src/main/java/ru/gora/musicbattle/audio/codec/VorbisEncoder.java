package ru.gora.musicbattle.audio.codec;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Encodes PCM to Ogg Vorbis in plain Java, so neither the client nor a dedicated server needs ffmpeg to make the
 * files Minecraft plays.
 *
 * <p>The stream is standard Vorbis I: blocks of 256 and 2048 samples, floor type 1, residue type 2 with lossless
 * square-polar stereo coupling (type 1 for mono), starting and ending the way libvorbis's streams do, and checked
 * against Minecraft's decoder (JOrbis), stb_vorbis (whole-file and pushdata), the mod's VorbisDecoder and FFmpeg.
 *
 * <p>Quality comes from a psychoacoustic model ({@link PsyModel}), a per-band search for the coarsest quantization
 * whose real error stays under the mask, a floor drawn exactly as decoders draw it, and short blocks around every
 * attack so drums do not smear. Speed: about 1.2 s for a four-minute stereo song, both passes, on one thread.
 * Thread-safe: every call builds its own state.
 *
 * <p>Two passes: the first runs the whole analysis and only counts which codebook entries the song uses; the
 * codewords are then built for exactly this song (length-limited Huffman codes); the second pass runs the same
 * analysis again and writes. Keeping counts instead of the analysed song keeps the encoder's own state to a few
 * hundred kilobytes whatever the song's length (a 15-minute song encodes within a 24 MB heap), at the price of
 * reading the source twice. Codewords made for the song itself came out 0.4 to 5.4 percent smaller than ones
 * trained on the other test songs, and a Vorbis file carries its codebooks either way.
 *
 * <p>Timing is sample-exact: decoded sample 0 is source sample 0, and the stream decodes to exactly as many frames as
 * the source delivered, because rhythm charts are timed against this audio to the millisecond.
 */
public final class VorbisEncoder {
    private VorbisEncoder() {
    }

    /** What to encode with. */
    public static final class Settings {
        /**
         * Quality 5 on libvorbis's scale (libvorbis itself defaults to 3, which the mod used through ffmpeg when the
         * player's ffmpeg had libvorbis): about 120 to 225 kbit/s for stereo music at 44.1 kHz on the test songs, less
         * for sparse mixes. Measured per bit it lands between libvorbis's quality 5 and 6.
         */
        public static final float DEFAULT_QUALITY = 5f;
        public static final String DEFAULT_VENDOR = "Blocky Music";
        /** A fixed serial number keeps the same input producing the same bytes. */
        public static final int DEFAULT_SERIAL = 0x426D7573;

        private float quality = DEFAULT_QUALITY;
        private int serialNumber = DEFAULT_SERIAL;
        private String vendor = DEFAULT_VENDOR;
        private final List<String> comments = new ArrayList<>();

        /** Quality on libvorbis's scale, -1 (smallest) to 10 (largest). */
        public Settings quality(float value) {
            if (!(value >= -1f && value <= 10f)) {
                throw new IllegalArgumentException("Vorbis quality must be between -1 and 10, not " + value);
            }
            quality = value;
            return this;
        }

        public Settings serialNumber(int value) {
            serialNumber = value;
            return this;
        }

        public Settings vendor(String value) {
            vendor = value == null ? "" : value;
            return this;
        }

        /**
         * Adds a comment field such as {@code TITLE} or {@code ARTIST}. Field names are ASCII without '=', as the
         * Vorbis comment format requires; the value may be any text.
         */
        public Settings comment(String field, String value) {
            if (field == null || field.isEmpty()) {
                throw new IllegalArgumentException("A comment needs a field name");
            }
            for (int i = 0; i < field.length(); i++) {
                char ch = field.charAt(i);
                if (ch < 0x20 || ch > 0x7D || ch == '=') {
                    throw new IllegalArgumentException("Not a valid Vorbis comment field name: " + field);
                }
            }
            if (value != null) {
                comments.add(field + "=" + value);
            }
            return this;
        }

        public Settings title(String value) {
            return comment("TITLE", value);
        }

        public Settings artist(String value) {
            return comment("ARTIST", value);
        }

        public float quality() {
            return quality;
        }

        public int serialNumber() {
            return serialNumber;
        }

        public String vendor() {
            return vendor;
        }

        public List<String> comments() {
            return List.copyOf(comments);
        }
    }

    /**
     * Encodes everything {@code source} delivers into {@code out}. The source is opened twice; both openings must
     * deliver the same audio. One or two channels at 8 to 192 kHz; the caller downmixes anything wider.
     *
     * @return how many frames the stream holds: as many as the source delivered
     * @throws AudioFormatException when the source's format is outside what this encoder writes
     * @throws java.io.InterruptedIOException when the thread is interrupted; {@code out} then holds a partial file
     */
    public static long encode(PcmSource source, OutputStream out, Settings settings) throws IOException {
        Settings s = settings == null ? new Settings() : settings;
        int channels;
        int sampleRate;
        VorbisEncoderLayout layout;
        VorbisEncoderSink.Counter counter;
        long frames;
        try (PcmStream first = source.open()) {
            channels = first.channels();
            sampleRate = first.sampleRate();
            checkFormat(channels, sampleRate);
            layout = new VorbisEncoderLayout(channels, sampleRate, s.quality());
            counter = new VorbisEncoderSink.Counter(layout.bookEntries);
            frames = new VorbisEncoderCore(layout).run(first, counter);
        }

        int books = layout.bookEntries.length;
        int[][] lengths = new int[books][];
        int[][] codes = new int[books][];
        long dataBits = counter.plainBits;
        for (int b = 0; b < books; b++) {
            long[] count = counter.counts[b];
            long[] weights = new long[count.length];
            for (int e = 0; e < count.length; e++) {
                // Entries the first pass never used still get a (long) code, so the second pass can always write
                // whatever it meets; scaling the real counts keeps that reserve from costing the used entries.
                weights[e] = count[e] * 64 + 1;
            }
            lengths[b] = HuffmanLengths.lengths(weights, VorbisEncoderLayout.MAX_CODEWORD_LENGTH);
            int[] words = HuffmanLengths.codewords(lengths[b]);
            codes[b] = new int[words.length];
            for (int e = 0; e < words.length; e++) {
                codes[b][e] = HuffmanLengths.reverse(words[e], lengths[b][e]);
                dataBits += count[e] * lengths[b][e];
            }
        }
        // The header's nominal bitrate is only a hint for players' displays; under a second of audio it would be
        // dominated by fixed costs and claim absurd rates, so it is left unset (0) there.
        int nominalBitrate = frames >= sampleRate
                ? (int) Math.min(Integer.MAX_VALUE, Math.round(dataBits * (double) sampleRate / frames))
                : 0;

        OggPageWriter pages = new OggPageWriter(out, s.serialNumber());
        pages.packet(identificationHeader(channels, sampleRate, nominalBitrate), 0, false);
        pages.flush();
        pages.packet(commentHeader(s), 0, false);
        pages.packet(layout.setupHeader(lengths), 0, false);
        pages.flush();
        Writer writer = new Writer(pages, lengths, codes);
        try (PcmStream second = source.open()) {
            if (second.channels() != channels || second.sampleRate() != sampleRate) {
                throw new AudioFormatException("The audio source changed its format between the two passes");
            }
            new VorbisEncoderCore(layout).run(second, writer);
        }
        out.flush();
        return frames;
    }

    private static void checkFormat(int channels, int sampleRate) throws AudioFormatException {
        if (channels < 1 || channels > 2) {
            throw new AudioFormatException("The Vorbis encoder takes one or two channels, not " + channels);
        }
        if (sampleRate < 8000 || sampleRate > 192000) {
            throw new AudioFormatException("The Vorbis encoder takes 8 to 192 kHz, not " + sampleRate + " Hz");
        }
    }

    private static byte[] identificationHeader(int channels, int sampleRate, int nominalBitrate) {
        VorbisEncoderBits w = new VorbisEncoderBits();
        w.write(1, 8);
        w.writeBytes("vorbis".getBytes(StandardCharsets.US_ASCII));
        w.write(0, 32);
        w.write(channels, 8);
        w.write(sampleRate, 32);
        w.write(0, 32);
        w.write(nominalBitrate, 32);
        w.write(0, 32);
        w.write(Integer.numberOfTrailingZeros(VorbisEncoderLayout.SHORT_BLOCK), 4);
        w.write(Integer.numberOfTrailingZeros(VorbisEncoderLayout.LONG_BLOCK), 4);
        w.write(1, 1);
        return w.toByteArray();
    }

    private static byte[] commentHeader(Settings s) {
        VorbisEncoderBits w = new VorbisEncoderBits();
        w.write(3, 8);
        w.writeBytes("vorbis".getBytes(StandardCharsets.US_ASCII));
        byte[] vendor = s.vendor().getBytes(StandardCharsets.UTF_8);
        w.write(vendor.length, 32);
        w.writeBytes(vendor);
        List<String> comments = s.comments();
        w.write(comments.size(), 32);
        for (String comment : comments) {
            byte[] bytes = comment.getBytes(StandardCharsets.UTF_8);
            w.write(bytes.length, 32);
            w.writeBytes(bytes);
        }
        w.write(1, 1);
        return w.toByteArray();
    }

    /** The second pass's sink: packs each block's bits and symbols into a packet and hands it to the pages. */
    private static final class Writer implements VorbisEncoderSink {
        private final OggPageWriter pages;
        private final int[][] lengths;
        private final int[][] codes;
        private final VorbisEncoderBits bits = new VorbisEncoderBits();

        Writer(OggPageWriter pages, int[][] lengths, int[][] codes) {
            this.pages = pages;
            this.lengths = lengths;
            this.codes = codes;
        }

        @Override
        public void bits(int value, int count) {
            bits.write(value, count);
        }

        @Override
        public void symbol(int book, int entry) {
            bits.write(codes[book][entry], lengths[book][entry]);
        }

        private boolean firstPacket = true;

        @Override
        public void endPacket(long granule, boolean last) throws IOException {
            pages.packet(bits.toByteArray(), granule, last);
            bits.reset();
            // The first audio packet gets a page of its own. FFmpeg's Ogg reader works out where a stream starts
            // from its first audio page, but skips that when the page also ends the stream, and then shifts a
            // short song by 128 samples and cuts its end; a one-packet first page is never the last one.
            if (firstPacket && !last) {
                pages.flush();
            }
            firstPacket = false;
        }
    }
}

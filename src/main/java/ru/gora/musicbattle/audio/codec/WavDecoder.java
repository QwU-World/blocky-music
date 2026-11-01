package ru.gora.musicbattle.audio.codec;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.util.Arrays;
import java.util.Objects;

/**
 * WAV decoder in plain Java: RIFF WAVE, RF64/BW64 for files past 4 GiB, and RIFX, the big-endian variant (old Mac
 * and Amiga tools, some game files), whose sizes, fields and samples are big-endian.
 *
 * <p>Reads integer PCM in 8, 16, 24 and 32-bit containers (12 and 20-bit samples sit in the next container up, as
 * ffmpeg reads them), IEEE float in 32 and 64 bits, and WAVE_FORMAT_EXTENSIBLE carrying either. Chunks may come in any
 * order and odd-sized ones are padded to an even length; a data chunk whose size is 0 or 0xFFFFFFFF (written while
 * streaming) or larger than the file is read to the end of the stream, and a partial sample frame at the very end is
 * dropped.
 *
 * <p>Samples come out exactly as {@code ffmpeg -i song.wav -f f32le} gives them: unsigned 8-bit as (x - 128) / 128,
 * integers scaled by 2^-(container bits - 1) with 32-bit ones rounded to float first, floats as they are and doubles
 * rounded to float.
 *
 * <p>A WAV file can also carry compressed audio. MPEG audio (format 0x55, which some tools write for MP3 in WAV, and
 * 0x50 for Layer I and II) is decoded by {@link Mp3Decoder} from its bare frames, as ffmpeg decodes it; telephone
 * audio in G.711 mu-law and A-law (0x07, 0x06) is expanded with the standard's tables, as 16-bit samples; IMA ADPCM
 * (0x11, 4 bits a sample) and Microsoft ADPCM (0x02) are decoded block by block ({@link WavAdpcm}), bit for bit as ffmpeg
 * decodes them. Any other
 * codec is an {@link UnsupportedFormatException} that names it, never a damaged file: the file is fine, only nothing
 * here decodes it.
 */
public final class WavDecoder {
    private static final int FORMAT_PCM = 1;
    private static final int FORMAT_FLOAT = 3;
    private static final int FORMAT_ALAW = 6;
    private static final int FORMAT_MULAW = 7;
    private static final int FORMAT_MS_ADPCM = 0x02;
    private static final int FORMAT_IMA_ADPCM = 0x11;
    private static final int FORMAT_MPEG = 0x50;
    private static final int FORMAT_MPEG_LAYER3 = 0x55;
    private static final int FORMAT_EXTENSIBLE = 0xFFFE;
    /** G.711 bytes as 16-bit samples, by the byte. */
    private static final short[] ALAW = new short[256];
    private static final short[] MULAW = new short[256];

    static {
        for (int i = 0; i < 256; i++) {
            ALAW[i] = (short) alaw(i);
            MULAW[i] = (short) mulaw(i);
        }
    }

    /**
     * ITU-T G.711 A-law expansion: every other bit is inverted (0x55) for transmission, then the top bit is the sign
     * (set for positive), three bits the segment and four the step within it. A step is worth 2 in the first two
     * segments and doubles with each one after, and a sample is decoded to the middle of its step. In 16-bit units.
     */
    private static int alaw(int code) {
        int value = code ^ 0x55;
        int step = value & 0x0F;
        int segment = (value >> 4) & 0x07;
        int magnitude = segment == 0 ? (step << 4) + 8 : ((step << 4) + 0x108) << (segment - 1);
        return (value & 0x80) != 0 ? magnitude : -magnitude;
    }

    /**
     * ITU-T G.711 mu-law expansion: the byte is sent inverted, then the top bit is the sign (set for negative), three
     * bits the segment and four the step. The scale is biased by 33 (in 14-bit units, 132 in 16-bit ones) so that
     * every segment doubles the previous one, and the bias is taken off again. In 16-bit units.
     */
    private static int mulaw(int code) {
        int value = ~code & 0xFF;
        int step = value & 0x0F;
        int segment = (value >> 4) & 0x07;
        int magnitude = (((step << 3) + 0x84) << segment) - 0x84;
        return (value & 0x80) != 0 ? -magnitude : magnitude;
    }

    /** What players call the codecs they meet inside WAV files, for the message that the mod does not read them. */
    private static String codecName(int tag) {
        String codec = switch (tag) {
            case 0x0002 -> "MS ADPCM";
            case 0x0011 -> "IMA ADPCM";
            case 0x0031, 0x0032 -> "GSM 6.10";
            case 0x0040, 0x0045, 0x0064 -> "G.726 ADPCM";
            case 0x0161, 0x0162, 0x0163 -> "WMA";
            case 0x00FF, 0x1610, 0x4143 -> "AAC";
            case 0x2000 -> "AC-3";
            case 0x2001 -> "DTS";
            case 0x674F, 0x6750, 0x6751, 0x676F, 0x6770, 0x6771 -> "Vorbis";
            case 0x704F -> "Opus";
            case 0xF1AC -> "FLAC";
            default -> null;
        };
        return codec != null
                ? "WAV with " + codec + " audio"
                : "WAV with audio in codec 0x" + String.format(java.util.Locale.ROOT, "%04X", tag);
    }
    /** A data chunk that comes before "fmt " has to be held until the format is known; past this it is refused. */
    private static final long MAX_HELD_DATA = 256L << 20;
    private static final int MAX_CHANNELS = 64;

    private WavDecoder() {
    }

    /**
     * Whether the first bytes of a file look like WAV: {@code RIFF}, {@code RIFX}, {@code RF64} or {@code BW64}, then
     * {@code WAVE}.
     */
    public static boolean looksLike(byte[] head) {
        if (head == null || head.length < 12) {
            return false;
        }
        boolean riff = head[0] == 'R' && head[1] == 'I' && head[2] == 'F' && (head[3] == 'F' || head[3] == 'X');
        boolean rf64 = head[0] == 'R' && head[1] == 'F' && head[2] == '6' && head[3] == '4';
        boolean bw64 = head[0] == 'B' && head[1] == 'W' && head[2] == '6' && head[3] == '4';
        return (riff || rf64 || bw64) && head[8] == 'W' && head[9] == 'A' && head[10] == 'V' && head[11] == 'E';
    }

    /**
     * Opens a WAV stream and reads its header chunks. On success the returned stream owns {@code in} and closes it; on
     * failure {@code in} is left to the caller.
     */
    public static PcmStream open(InputStream in) throws IOException {
        Objects.requireNonNull(in, "in");
        WavStream wav = new WavStream(in);
        if (wav.format == FORMAT_MPEG || wav.format == FORMAT_MPEG_LAYER3) {
            // The data chunk is bare MPEG frames, which say their own rate and channels.
            return Mp3Decoder.openFrames(wav.dataChunk());
        }
        if (wav.adpcm != null) {
            return wav.adpcm;
        }
        return wav;
    }

    private static void checkInterrupt() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("WAV decoding interrupted");
        }
    }

    private static final class WavStream implements PcmStream {
        private final InputStream source;
        private InputStream in;
        private boolean closed;

        private int format;
        private int channels;
        private int sampleRate;
        private int bytesPerSample;
        private int frameBytes;
        private long remaining;      // data bytes left, or -1 to read to the end of the stream
        private long frameCountHint = -1;
        private final byte[] head = new byte[64];
        private byte[] raw = new byte[0];
        private boolean ended;
        /** RIFX: every size, field and sample is big-endian. */
        private boolean big;
        /** The decoder of an ADPCM data chunk, which reads it in place of {@link #read}. */
        private WavAdpcm adpcm;

        WavStream(InputStream in) throws IOException {
            this.source = in;
            this.in = in;
            readHeader();
        }

        private int readFully(byte[] b, int off, int len) throws IOException {
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
            return total;
        }

        private void skipBytes(long n) throws IOException {
            byte[] scratch = new byte[(int) Math.min(n, 1 << 16)];
            while (n > 0) {
                int got = readFully(scratch, 0, (int) Math.min(n, scratch.length));
                if (got == 0) {
                    return;
                }
                n -= got;
            }
        }

        private int le16(byte[] b, int off) {
            return big ? (b[off] & 0xFF) << 8 | (b[off + 1] & 0xFF) : (b[off] & 0xFF) | (b[off + 1] & 0xFF) << 8;
        }

        private long le32(byte[] b, int off) {
            return big
                    ? (b[off] & 0xFFL) << 24 | (b[off + 1] & 0xFFL) << 16 | (b[off + 2] & 0xFFL) << 8 | (b[off + 3] & 0xFFL)
                    : (b[off] & 0xFFL) | (b[off + 1] & 0xFFL) << 8 | (b[off + 2] & 0xFFL) << 16
                            | (b[off + 3] & 0xFFL) << 24;
        }

        private long le64(byte[] b, int off) {
            return big ? le32(b, off) << 32 | le32(b, off + 4) : le32(b, off) | le32(b, off + 4) << 32;
        }

        private static boolean is(byte[] b, int off, String id) {
            for (int i = 0; i < 4; i++) {
                if (b[off + i] != id.charAt(i)) {
                    return false;
                }
            }
            return true;
        }

        private void readHeader() throws IOException {
            if (readFully(head, 0, 12) < 12 || !looksLike(head)) {
                throw new AudioFormatException("Not a WAV file");
            }
            big = is(head, 0, "RIFX");
            boolean rf64 = !is(head, 0, "RIFF") && !big;
            long ds64DataSize = -1;
            boolean haveFormat = false;
            byte[] heldData = null;
            long heldSize = 0;
            while (true) {
                if (readFully(head, 0, 8) < 8) {
                    break;
                }
                long size = le32(head, 4);
                if (is(head, 0, "ds64")) {
                    byte[] ds = new byte[(int) Math.min(size, 1 << 16)];
                    int got = readFully(ds, 0, ds.length);
                    if (got >= 16) {
                        ds64DataSize = le64(ds, 8);
                    }
                    skipBytes(size - got + (size & 1));
                } else if (is(head, 0, "fmt ")) {
                    if (size < 16 || size > 1 << 16) {
                        throw new AudioFormatException("Damaged WAV format chunk");
                    }
                    byte[] fmt = new byte[(int) size];
                    if (readFully(fmt, 0, fmt.length) < fmt.length) {
                        throw new AudioFormatException("WAV file ends inside its format chunk");
                    }
                    parseFormat(fmt);
                    haveFormat = true;
                    skipBytes(size & 1);
                    if (heldData != null) {
                        break;
                    }
                } else if (is(head, 0, "data")) {
                    long dataSize = size;
                    if (rf64 && size == 0xFFFFFFFFL && ds64DataSize >= 0) {
                        dataSize = ds64DataSize;
                    }
                    // 0 and 0xFFFFFFFF are what a writer that could not seek back leaves; either way, read to the end.
                    boolean streamed = dataSize == 0 || dataSize == 0xFFFFFFFFL && !(rf64 && ds64DataSize >= 0);
                    if (haveFormat) {
                        remaining = streamed ? -1 : dataSize;
                        if (!streamed && frameBytes > 0) {
                            frameCountHint = dataSize / frameBytes;
                        }
                        startAdpcm(streamed ? -1 : dataSize);
                        return;
                    }
                    if (streamed || dataSize > MAX_HELD_DATA) {
                        throw new AudioFormatException("WAV audio comes before its format");
                    }
                    // Read in steps, so a size field that lies costs no more memory than the file holds.
                    heldData = new byte[(int) Math.min(dataSize, 1 << 20)];
                    while (heldSize < dataSize) {
                        if (heldSize == heldData.length) {
                            heldData = Arrays.copyOf(heldData, (int) Math.min(dataSize, heldData.length * 2L));
                        }
                        int got = readFully(heldData, (int) heldSize, heldData.length - (int) heldSize);
                        if (got == 0) {
                            break;
                        }
                        heldSize += got;
                    }
                    if (heldSize < dataSize) {
                        break;
                    }
                    skipBytes(dataSize & 1);
                } else {
                    skipBytes(size + (size & 1));
                }
            }
            if (!haveFormat || heldData == null) {
                throw new AudioFormatException(haveFormat ? "WAV file has no audio" : "WAV file has no format chunk");
            }
            in = new ByteArrayInputStream(heldData, 0, (int) heldSize);
            remaining = heldSize;
            frameCountHint = frameBytes > 0 ? heldSize / frameBytes : -1;
            startAdpcm(heldSize);
        }

        /** The ADPCM fields of the format chunk, kept until the data chunk is found. */
        private int adpcmBlockAlign;
        private int adpcmSamplesPerBlock;
        private short[] msCoefficients;

        private void startAdpcm(long dataSize) {
            if (format == FORMAT_IMA_ADPCM || format == FORMAT_MS_ADPCM) {
                adpcm = new WavAdpcm(dataChunk(), format == FORMAT_MS_ADPCM, channels, sampleRate, adpcmBlockAlign,
                        adpcmSamplesPerBlock, msCoefficients, dataSize);
            }
        }

        private void parseFormat(byte[] fmt) throws AudioFormatException {
            int tag = le16(fmt, 0);
            channels = le16(fmt, 2);
            sampleRate = (int) le32(fmt, 4);
            int bits = le16(fmt, 14);
            if (tag == FORMAT_EXTENSIBLE) {
                if (fmt.length < 40 || le16(fmt, 16) < 22) {
                    throw new AudioFormatException("Damaged WAVE_FORMAT_EXTENSIBLE chunk");
                }
                // The sub-format GUID is the old format tag followed by the fixed KSDATAFORMAT suffix; any other GUID
                // names a codec of its own vendor's.
                // RIFX stores the GUID's three leading fields big-endian like every other number.
                byte[] suffix = big
                        ? new byte[] {0x00, 0x00, 0x00, 0x10, (byte) 0x80, 0x00, 0x00, (byte) 0xAA, 0x00, 0x38,
                                (byte) 0x9B, 0x71}
                        : new byte[] {0x00, 0x00, 0x00, 0x00, 0x10, 0x00, (byte) 0x80, 0x00, 0x00, (byte) 0xAA, 0x00,
                                0x38, (byte) 0x9B, 0x71};
                int from = big ? 28 : 26;
                if (big && (fmt[24] != 0 || fmt[25] != 0)) {
                    throw new UnsupportedFormatException("WAV with audio in an unknown codec");
                }
                for (int i = 0; i < suffix.length; i++) {
                    if (fmt[from + i] != suffix[i]) {
                        throw new UnsupportedFormatException("WAV with audio in an unknown codec");
                    }
                }
                tag = big ? le16(fmt, 26) : le16(fmt, 24);
            }
            if (tag == FORMAT_MPEG || tag == FORMAT_MPEG_LAYER3) {
                // Handed to the MP3 decoder whole; the header's sample size is 0 for these, which is not damage.
                format = tag;
                return;
            }
            if (tag == FORMAT_IMA_ADPCM || tag == FORMAT_MS_ADPCM) {
                parseAdpcm(tag, fmt, bits);
                return;
            }
            boolean companded = tag == FORMAT_ALAW || tag == FORMAT_MULAW;
            if (tag != FORMAT_PCM && tag != FORMAT_FLOAT && !companded) {
                throw new UnsupportedFormatException(codecName(tag));
            }
            if (channels < 1 || channels > MAX_CHANNELS || sampleRate <= 0 || (bits <= 0 && !companded)) {
                throw new AudioFormatException("Damaged WAV format: " + channels + " channels, " + sampleRate + " Hz");
            }
            // G.711 is one byte a sample whatever the header claims, as ffmpeg reads it.
            bytesPerSample = companded ? 1 : (bits + 7) / 8;
            if (tag == FORMAT_PCM) {
                if (bytesPerSample > 4) {
                    throw new AudioFormatException("Unsupported WAV integer size: " + bits + " bits");
                }
            } else if (tag == FORMAT_FLOAT) {
                if (bytesPerSample != 4 && bytesPerSample != 8) {
                    throw new AudioFormatException("Unsupported WAV float size: " + bits + " bits");
                }
            }
            format = tag;
            frameBytes = bytesPerSample * channels;
        }

        /**
         * IMA ADPCM (4 bits a sample, the only depth Windows and ffmpeg write) and Microsoft ADPCM: the block size is
         * the format's block align, and the samples a block holds follow from it. A block align that cannot hold a
         * block's header, or more channels than ADPCM files carry, is damage. Microsoft ADPCM lists its predictor
         * coefficients in the format chunk; the seven standard pairs are used when it lists none.
         */
        private void parseAdpcm(int tag, byte[] fmt, int bits) throws AudioFormatException {
            int blockAlign = le16(fmt, 12);
            int header = tag == FORMAT_IMA_ADPCM ? 4 : 7;
            if (channels < 1 || channels > 8 || sampleRate <= 0) {
                throw new AudioFormatException("Damaged WAV format: " + channels + " channels, " + sampleRate + " Hz");
            }
            if (tag == FORMAT_IMA_ADPCM && bits != 4) {
                throw new UnsupportedFormatException("WAV with IMA ADPCM audio (" + bits + " bits)");
            }
            if (blockAlign < header * channels + (tag == FORMAT_IMA_ADPCM ? 4 * channels : 0)
                    || (tag == FORMAT_IMA_ADPCM && blockAlign % (4 * channels) != 0)) {
                throw new AudioFormatException("Damaged WAV ADPCM format: blocks of " + blockAlign + " bytes");
            }
            adpcmBlockAlign = blockAlign;
            adpcmSamplesPerBlock = tag == FORMAT_IMA_ADPCM
                    ? (blockAlign - 4 * channels) * 2 / channels + 1
                    : (blockAlign - 7 * channels) * 2 / channels + 2;
            if (tag == FORMAT_MS_ADPCM && fmt.length >= 22 && le16(fmt, 16) >= 4) {
                int count = le16(fmt, 20);
                if (count > 0 && count <= 256 && fmt.length >= 22 + 4 * count) {
                    msCoefficients = new short[2 * count];
                    for (int i = 0; i < 2 * count; i++) {
                        msCoefficients[i] = (short) le16(fmt, 22 + 2 * i);
                    }
                }
            }
            format = tag;
            bytesPerSample = 0;
            frameBytes = 0;
        }

        /**
         * The rest of the data chunk as bytes, for a WAV that carries MPEG frames. Closing it closes the file, since
         * the MP3 stream made from it owns it from then on.
         */
        InputStream dataChunk() {
            return new InputStream() {
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
                    if (remaining == 0) {
                        return -1;
                    }
                    int got = in.read(b, off, remaining < 0 ? len : (int) Math.min(len, remaining));
                    if (got > 0 && remaining > 0) {
                        remaining -= got;
                    }
                    return got;
                }

                @Override
                public void close() throws IOException {
                    WavStream.this.close();
                }
            };
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
            if (ended) {
                return -1;
            }
            long want = Math.min((long) frames, Math.max(1, (1 << 20) / frameBytes)) * frameBytes;
            if (remaining >= 0) {
                want = Math.min(want, remaining - remaining % frameBytes);
            }
            if (want < frameBytes) {
                ended = true;
                return -1;
            }
            if (raw.length < want) {
                raw = new byte[(int) want];
            }
            int got = readFully(raw, 0, (int) want);
            int n = got / frameBytes;
            if (got < want) {
                // The stream ended; a partial sample frame there is dropped, as ffmpeg drops it.
                ended = true;
            }
            if (n == 0) {
                return -1;
            }
            convert(out, offset, n);
            if (remaining >= 0) {
                remaining -= (long) n * frameBytes;
            }
            return n;
        }

        private void convert(float[][] out, int offset, int n) {
            byte[] b = raw;
            int ch = channels;
            int p = 0;
            int layout = format == FORMAT_FLOAT ? 10 + bytesPerSample
                    : format == FORMAT_ALAW ? 20 : format == FORMAT_MULAW ? 21 : bytesPerSample;
            if (big && bytesPerSample > 1 && layout < 20) {
                // RIFX: the same samples with their bytes the other way round.
                swapBytes(b, n * ch, bytesPerSample);
            }
            switch (layout) {
                case 20, 21 -> {
                    short[] table = layout == 20 ? ALAW : MULAW;
                    for (int i = 0; i < n; i++) {
                        for (int c = 0; c < ch; c++) {
                            out[c][offset + i] = table[b[p++] & 0xFF] * (1.0f / (1 << 15));
                        }
                    }
                }
                case 1 -> {
                    for (int i = 0; i < n; i++) {
                        for (int c = 0; c < ch; c++) {
                            out[c][offset + i] = ((b[p++] & 0xFF) - 0x80) * (1.0f / (1 << 7));
                        }
                    }
                }
                case 2 -> {
                    for (int i = 0; i < n; i++) {
                        for (int c = 0; c < ch; c++, p += 2) {
                            out[c][offset + i] = (short) ((b[p] & 0xFF) | b[p + 1] << 8) * (1.0f / (1 << 15));
                        }
                    }
                }
                case 3 -> {
                    for (int i = 0; i < n; i++) {
                        for (int c = 0; c < ch; c++, p += 3) {
                            out[c][offset + i] = ((b[p] & 0xFF) | (b[p + 1] & 0xFF) << 8 | b[p + 2] << 16) * (1.0f / (1 << 23));
                        }
                    }
                }
                case 4 -> {
                    for (int i = 0; i < n; i++) {
                        for (int c = 0; c < ch; c++, p += 4) {
                            int v = (b[p] & 0xFF) | (b[p + 1] & 0xFF) << 8 | (b[p + 2] & 0xFF) << 16 | b[p + 3] << 24;
                            out[c][offset + i] = (float) v * (1.0f / (1L << 31));
                        }
                    }
                }
                case 14 -> {
                    for (int i = 0; i < n; i++) {
                        for (int c = 0; c < ch; c++, p += 4) {
                            int v = (b[p] & 0xFF) | (b[p + 1] & 0xFF) << 8 | (b[p + 2] & 0xFF) << 16 | b[p + 3] << 24;
                            out[c][offset + i] = Float.intBitsToFloat(v);
                        }
                    }
                }
                default -> {
                    for (int i = 0; i < n; i++) {
                        for (int c = 0; c < ch; c++, p += 8) {
                            long v = (b[p] & 0xFFL) | (b[p + 1] & 0xFFL) << 8 | (b[p + 2] & 0xFFL) << 16
                                    | (b[p + 3] & 0xFFL) << 24 | (b[p + 4] & 0xFFL) << 32 | (b[p + 5] & 0xFFL) << 40
                                    | (b[p + 6] & 0xFFL) << 48 | (long) b[p + 7] << 56;
                            out[c][offset + i] = (float) Double.longBitsToDouble(v);
                        }
                    }
                }
            }
        }

        private static void swapBytes(byte[] b, int samples, int width) {
            for (int i = 0, p = 0; i < samples; i++, p += width) {
                for (int lo = p, hi = p + width - 1; lo < hi; lo++, hi--) {
                    byte t = b[lo];
                    b[lo] = b[hi];
                    b[hi] = t;
                }
            }
        }

        @Override
        public void close() throws IOException {
            if (!closed) {
                closed = true;
                source.close();
            }
        }
    }
}

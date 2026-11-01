import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import ru.gora.musicbattle.audio.codec.AudioFormatException;
import ru.gora.musicbattle.audio.codec.FlacDecoder;
import ru.gora.musicbattle.audio.codec.Mp3Decoder;
import ru.gora.musicbattle.audio.codec.PcmStream;
import ru.gora.musicbattle.audio.codec.UnsupportedFormatException;
import ru.gora.musicbattle.audio.codec.WavDecoder;

/**
 * The pure-Java MP3, FLAC and WAV decoders in audio/codec, which read the songs players import so that nobody has to
 * install ffmpeg. The script compiles them with no classpath at all, so this also proves they need neither Minecraft
 * nor the client's native libraries.
 *
 * <p>MP3: the fixtures in tools/fixtures/mp3 were made from a synthetic signal (sweeps, clicks for short blocks, noise;
 * tools/fixtures/gen/README.md says how) with ffmpeg 9: libmp3lame for Layer III, its own mp2 encoder for Layer II. The
 * CBR fixture's tag was then relabelled LAME3.100 with its CRC recomputed, so the LAME branch is the literal one. Their
 * expected lengths and probe samples are ffmpeg 9's own decode, because every imported beatmap was timed against
 * ffmpeg: the first sample and the length must match exactly, the samples to within the two decoders' float rounding.
 * The SHA-256 is of minimp3's scalar float build (commit ea99364f) on the same frames, trimmed the same way,
 * which the Java port must reproduce bit for bit. Variants built here from the fixtures (tags, junk, cuts, edited
 * LAME tags) carry lengths measured with ffmpeg on exactly these bytes.
 *
 * <p>FLAC and WAV are lossless, so their fixtures are built here: a small FLAC encoder writes every subframe type and
 * header form the decoder must read, and the decoded samples must equal the encoded ones scaled the way ffmpeg scales
 * them. Damaged input of all three kinds must end in an {@link AudioFormatException} or a clean end of stream, never
 * another exception and never a hang, and an interrupt must surface as an {@link InterruptedIOException}.
 *
 * <p>Not proven here: the integrators' use of these decoders, and anything about the game. With {@code dump <dir>}
 * after the project root, the generated inputs are also written out, for comparing against ffmpeg by hand.
 */
public final class NativeAudioDecoderChecks {
    private static int checks;
    private static final List<String> FAILURES = new ArrayList<>();
    private static Path dumpDir;

    private interface Opener {
        PcmStream open(InputStream in) throws IOException;
    }

    private static final Opener MP3 = Mp3Decoder::open;
    private static final Opener FLAC = FlacDecoder::open;
    private static final Opener WAV = WavDecoder::open;

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args.length > 0 ? args[0] : ".");
        if (args.length > 2 && args[1].equals("dump")) {
            dumpDir = Path.of(args[2]);
            Files.createDirectories(dumpDir);
        }
        Map<String, byte[]> fixtures = loadFixtures(root.resolve("tools/fixtures/mp3"));
        // First, while the JIT is still cold, as it is the first time a player imports a song.
        mp3Speed(fixtures);
        mp3Fixtures(fixtures);
        mp3Variants(fixtures);
        mp3Damage(fixtures);
        flacRoundTrips();
        flacDamage();
        wavCases();
        wavDamage();
        wavCodecs(fixtures);
        sniffing(fixtures);
        mp3Sync(fixtures);
        interrupts(fixtures);

        if (!FAILURES.isEmpty()) {
            System.out.println(FAILURES.size() + " of " + checks + " native audio decoder checks FAILED:");
            for (String failure : FAILURES) {
                System.out.println("  - " + failure);
            }
            System.exit(1);
        }
        System.out.println("Native audio decoder checks passed: " + checks);
        System.exit(0);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Plumbing

    private static void check(boolean ok, String what) {
        checks++;
        if (!ok) {
            FAILURES.add(what);
            System.out.println("FAIL " + what);
        }
    }

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "native-audio-decoder-check");
        t.setDaemon(true);
        return t;
    });

    /** Runs a decode with a deadline: a decoder that loops forever is a failure, and the stuck thread is abandoned. */
    private static <T> T within(int seconds, String what, Callable<T> body) throws Exception {
        Future<T> future = WORKER.submit(body);
        try {
            return future.get(seconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            System.out.println("FAIL " + what + ": no result after " + seconds + " s (a hang)");
            System.exit(1);
            return null;
        } catch (java.util.concurrent.ExecutionException e) {
            if (e.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw e;
        }
    }

    private static void dump(String name, byte[] data) throws IOException {
        if (dumpDir != null) {
            Files.write(dumpDir.resolve(name), data);
        }
    }

    /** Hands out a few bytes at a time, so every refill path of the decoders' buffering runs. */
    private static final class Trickle extends InputStream {
        private final byte[] data;
        private final Random random;
        private int pos;
        boolean closed;

        Trickle(byte[] data, long seed) {
            this.data = data;
            this.random = new Random(seed);
        }

        @Override
        public int read() {
            return pos < data.length ? data[pos++] & 0xFF : -1;
        }

        @Override
        public int read(byte[] b, int off, int len) {
            if (len == 0) {
                return 0;
            }
            if (pos >= data.length) {
                return -1;
            }
            int n = Math.min(Math.min(len, 1 + random.nextInt(random.nextBoolean() ? 9 : 5000)), data.length - pos);
            System.arraycopy(data, pos, b, off, n);
            pos += n;
            return n;
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private static final class Decoded {
        int sampleRate;
        int channels;
        long hint;
        int frames;
        float[][] pcm;
        String sha256;
        boolean closed;
    }

    /** Decodes everything, reading in awkward sizes, and hashes the interleaved little-endian float bits. */
    private static Decoded decode(Opener opener, byte[] data, boolean trickle) throws IOException {
        InputStream in = trickle ? new Trickle(data, data.length) : new ByteArrayInputStream(data);
        Decoded d = new Decoded();
        MessageDigest sha;
        try {
            sha = MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        try (PcmStream s = opener.open(in)) {
            d.sampleRate = s.sampleRate();
            d.channels = s.channels();
            d.hint = s.frameCountHint();
            d.pcm = new float[d.channels][1 << 14];
            int[] sizes = {4096, 1, 7, 1000, 333, 65536};
            float[][] buf = new float[d.channels][65536 + 3];
            ByteBuffer bytes = ByteBuffer.allocate(65536 * d.channels * 4).order(ByteOrder.LITTLE_ENDIAN);
            for (int call = 0; ; call++) {
                int n = s.read(buf, 3, sizes[call % sizes.length]);
                if (n < 0) {
                    break;
                }
                if (n == 0) {
                    throw new IllegalStateException("read returned 0 for a non-empty request");
                }
                if (d.frames + n > d.pcm[0].length) {
                    for (int c = 0; c < d.channels; c++) {
                        d.pcm[c] = Arrays.copyOf(d.pcm[c], Math.max(d.frames + n, d.pcm[c].length * 2));
                    }
                }
                bytes.clear();
                for (int i = 0; i < n; i++) {
                    for (int c = 0; c < d.channels; c++) {
                        d.pcm[c][d.frames + i] = buf[c][3 + i];
                        bytes.putFloat(buf[c][3 + i]);
                    }
                }
                sha.update(bytes.array(), 0, bytes.position());
                d.frames += n;
            }
            if (s.read(buf, 0, 10) != -1) {
                throw new IllegalStateException("read after the end did not stay at the end");
            }
        }
        d.closed = !(in instanceof Trickle t) || t.closed;
        d.sha256 = HexFormat.of().formatHex(sha.digest());
        return d;
    }

    private static boolean samePcm(Decoded a, Decoded b, int from, int to) {
        if (a.channels != b.channels) {
            return false;
        }
        for (int c = 0; c < a.channels; c++) {
            for (int i = from; i < to; i++) {
                if (Float.floatToRawIntBits(a.pcm[c][i]) != Float.floatToRawIntBits(b.pcm[c][i])) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Opens and drains a damaged input: only AudioFormatException may come out, and the answer must come quickly. */
    private static String survive(Opener opener, byte[] data, String what) throws Exception {
        return within(20, what, () -> {
            try {
                Decoded d = decode(opener, data, false);
                return "ok " + d.frames;
            } catch (AudioFormatException e) {
                return "refused";
            } catch (Exception | Error e) {
                check(false, what + ": threw " + e);
                return "threw";
            }
        });
    }

    private static boolean refuses(Opener opener, byte[] data, String what) throws Exception {
        return within(20, what, () -> {
            try (PcmStream s = opener.open(new ByteArrayInputStream(data))) {
                float[][] buf = new float[Math.max(1, s.channels())][4096];
                while (s.read(buf, 0, 4096) > 0) {
                    // drain
                }
                return false;
            } catch (AudioFormatException e) {
                return true;
            } catch (Exception | Error e) {
                System.out.println("  " + what + " threw " + e);
                return false;
            }
        });
    }

    // ---------------------------------------------------------------------------------------------------------------
    // MP3

    private record Mp3Fixture(String file, int rate, int channels, int frames, long hint, String sha256, float tolerance,
                              int[] probes, float[] values) {
    }

    private static final List<Mp3Fixture> MP3_FIXTURES = List.of(
            // CBR 64 kbit/s joint stereo with an Info frame whose LAME tag says delay 576, padding 684.
            new Mp3Fixture("cbr-lame.mp3", 44100, 2, 79380, 79380,
                    "b7f80c4bd07fdf7c583406c23677d299a2950562718241a7089c3a71ac695226", 2e-5f,
                    new int[]{3307, 0, 97, 0, 9922, 1, 12644, 1, 16537, 0, 18874, 0, 23152, 1, 25416, 1, 29767, 0,
                            29256, 0, 36382, 1, 38274, 1, 42997, 0, 43272, 0, 49612, 1, 52617, 1, 56227, 0, 53775, 0,
                            62842, 1, 61322, 1, 69457, 0, 71309, 0, 76072, 1, 72896, 1},
                    new float[]{0.29459384f, 0.4239105f, -0.1183549f, -0.14713402f, -0.2998542f, -0.098262094f,
                            0.11286466f, 0.20667498f, 0.3029758f, 0.13323358f, -0.085467786f, -0.053651817f,
                            -0.3004027f, 0.10151556f, 0.110589676f, -0.1939174f, 0.3016685f, -0.06526752f,
                            -0.118242994f, 0.04570124f, -0.2912288f, 0.16959758f, 0.11394412f, 0.10177873f}),
            // VBR with a Xing frame whose extension says Lavc, which ffmpeg trims like LAME.
            new Mp3Fixture("vbr-xing.mp3", 44100, 2, 79380, 79380,
                    "fa05080c1e49ffadcda9d56c2e54b11d84c6ec03397364f6f4e14c76bd6d7a5d", 2e-5f,
                    new int[]{3307, 0, 27, 0, 9922, 1, 10200, 1, 16537, 0, 18106, 0, 23152, 1, 20941, 1, 29767, 0,
                            32744, 0, 36382, 1, 35783, 1, 42997, 0, 40117, 0, 49612, 1, 47086, 1, 56227, 0, 54058, 0,
                            62842, 1, 62471, 1, 69457, 0, 71310, 0, 76072, 1, 72994, 1},
                    new float[]{0.31545487f, 0.28030816f, -0.11794235f, 0.1578468f, -0.3315361f, 0.19308318f,
                            0.114880584f, 0.22879875f, 0.32267722f, 0.035182677f, -0.13223489f, 0.24069683f,
                            -0.31483787f, -0.02074381f, 0.12662499f, -0.22948077f, 0.3376351f, 0.1418122f,
                            -0.12705815f, -0.1989677f, -0.32593358f, 0.062348608f, 0.11360021f, 0.091596514f}),
            // No tags at all: every frame is audio and nothing is trimmed.
            new Mp3Fixture("notag.mp3", 44100, 2, 80640, -1,
                    "7a398dd93611e1cd5a623df7f00faec7ff3c0d3b5bba4958e4086a4916d21e89", 2e-5f,
                    new int[]{3359, 0, 1202, 0, 10079, 1, 13400, 1, 16799, 0, 14001, 0, 23519, 1, 26521, 1, 30239, 0,
                            26893, 0, 36959, 1, 39379, 1, 43679, 0, 44377, 0, 50399, 1, 53722, 1, 57119, 0, 54880, 0,
                            63839, 1, 62427, 1, 70559, 0, 72414, 0, 77279, 1, 74001, 1},
                    new float[]{-0.038913075f, 0.4239105f, 0.17309101f, -0.17924291f, 0.09681435f, 0.4897454f,
                            -0.2828873f, 0.20667498f, -0.2876215f, -0.32862043f, -0.21914472f, -0.053651817f,
                            0.24067865f, 0.10151556f, -0.19444688f, -0.1939174f, -0.2221552f, -0.06526752f,
                            -0.10339781f, 0.04570124f, -0.19579212f, 0.16959758f, -0.2596106f, 0.10177873f}),
            // Mono MPEG-2 at 22050 Hz: 576-sample frames, so the 1105-sample start trim spans two of them.
            new Mp3Fixture("mono-lsf.mp3", 22050, 1, 39690, 39690,
                    "f4af9831bad21dcb8d025c87e3f0bbabba5c42a0c4e8ec5c4da19a9ca24dade7", 2e-5f,
                    new int[]{1653, 0, 45, 0, 4960, 0, 6448, 0, 8268, 0, 7332, 0, 11575, 0, 12816, 0, 14883, 0, 14628,
                            0, 18190, 0, 19227, 0, 21498, 0, 21898, 0, 24805, 0, 25613, 0, 28113, 0, 29169, 0, 31420, 0,
                            32033, 0, 34728, 0, 35328, 0, 38035, 0, 38430, 0},
                    new float[]{0.3021269f, 0.05613649f, -0.27525702f, 0.4843438f, -0.3197119f, 0.1185546f,
                            0.30472684f, -0.3355861f, 0.28267136f, 0.018023917f, -0.19321492f, -0.18622695f,
                            -0.26618165f, 0.08259934f, 0.29172283f, -0.11192138f, 0.32765108f, 0.14826636f,
                            -0.24376822f, 0.10005073f, -0.30395624f, 0.30631992f, 0.2651843f, -0.029518155f}),
            // MPEG-1 Layer II from ffmpeg's mp2 encoder; ffmpeg's Layer II output is rounded to 16 bits.
            new Mp3Fixture("layer2.mp2", 44100, 2, 52992, -1,
                    "631c617baae560a1310a90170494f5d427eebfc5a296cd830d39de280662604d", 1e-4f,
                    new int[]{2207, 0, 648, 0, 6623, 1, 5728, 1, 11039, 0, 12707, 0, 15455, 1, 15150, 1, 19871, 0,
                            18950, 0, 24287, 1, 26386, 1, 28703, 0, 29240, 0, 33119, 1, 33752, 1, 37535, 0, 38935, 0,
                            41951, 1, 44147, 1, 46367, 0, 48277, 0, 50783, 1, 50307, 1},
                    new float[]{0.0048217773f, -0.27697754f, 0.23736572f, -0.116607666f, 0.33776855f, -0.09259033f,
                            -0.20889282f, 0.33346558f, 0.26065063f, -0.031799316f, 0.1951294f, -0.087768555f,
                            0.1222229f, -0.09109497f, -0.22555542f, 0.067108154f, 0.074798584f, 0.011962891f,
                            0.26049805f, -0.2518921f, 0.17623901f, 0.05935669f, -0.2966919f, 0.045135498f}));

    private static Map<String, byte[]> loadFixtures(Path dir) throws IOException {
        Map<String, byte[]> map = new LinkedHashMap<>();
        for (Mp3Fixture f : MP3_FIXTURES) {
            map.put(f.file(), Files.readAllBytes(dir.resolve(f.file())));
        }
        return map;
    }

    private static void mp3Speed(Map<String, byte[]> fixtures) throws Exception {
        // Four minutes of stereo: the untagged fixture again and again, which ffmpeg also decodes as one stream.
        byte[] one = fixtures.get("notag.mp3");
        int copies = (int) Math.ceil(240.0 * 44100 / 80640);
        ByteArrayOutputStream song = new ByteArrayOutputStream(one.length * copies);
        for (int i = 0; i < copies; i++) {
            song.write(one);
        }
        byte[] data = song.toByteArray();
        long start = System.nanoTime();
        Decoded d = within(30, "four-minute MP3", () -> decode(MP3, data, false));
        double ms = (System.nanoTime() - start) / 1e6;
        System.out.printf("  a %.0f s stereo MP3 decoded in %.0f ms, cold%n", d.frames / 44100.0, ms);
        check(d.frames == 80640 * copies, "the four-minute MP3 decodes whole: " + d.frames);
        check(ms < 1500, "a four-minute stereo MP3 decodes in under 1.5 s (took " + Math.round(ms) + " ms)");
    }

    private static void mp3Fixtures(Map<String, byte[]> fixtures) throws Exception {
        for (Mp3Fixture f : MP3_FIXTURES) {
            String name = f.file();
            Decoded d = decode(MP3, fixtures.get(name), true);
            check(d.sampleRate == f.rate() && d.channels == f.channels(),
                    name + ": " + d.sampleRate + " Hz x" + d.channels + ", expected " + f.rate() + " x" + f.channels());
            check(d.frames == f.frames(), name + ": " + d.frames + " frames like ffmpeg's " + f.frames());
            check(d.hint == f.hint(), name + ": frame count hint " + d.hint + ", expected " + f.hint());
            check(d.sha256.equals(f.sha256()), name + ": bit-exact with minimp3 (sha256 " + d.sha256 + ")");
            check(d.closed, name + ": closing the stream closes the input");
            float worst = 0;
            int[] p = f.probes();
            for (int k = 0; k < f.values().length; k++) {
                int index = p[2 * k];
                int channel = p[2 * k + 1];
                float got = index < d.frames ? d.pcm[channel][index] : Float.NaN;
                worst = Math.max(worst, Math.abs(got - f.values()[k]));
            }
            // Half the probes sit where the wave moves by at least 0.018 per sample, so a shift by a single sample
            // against ffmpeg breaks this by three orders of magnitude.
            check(worst <= f.tolerance(), name + ": samples match ffmpeg's at the probes (worst " + worst + ")");
        }
    }

    // ----- variants built from the fixtures

    private static int id3Size(byte[] d) {
        if (d.length < 10 || d[0] != 'I' || d[1] != 'D' || d[2] != '3') {
            return 0;
        }
        return ((d[6] & 0x7F) << 21 | (d[7] & 0x7F) << 14 | (d[8] & 0x7F) << 7 | (d[9] & 0x7F)) + 10;
    }

    private static final int[][] L3_KBPS = {{0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160},
            {0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320}};

    /** Offsets of the Layer III frames from {@code start}, by their headers' lengths. */
    private static List<Integer> l3Frames(byte[] d, int start) {
        List<Integer> offsets = new ArrayList<>();
        int i = start;
        while (i + 4 <= d.length) {
            int h = (d[i] & 0xFF) << 24 | (d[i + 1] & 0xFF) << 16 | (d[i + 2] & 0xFF) << 8 | (d[i + 3] & 0xFF);
            if ((h & 0xFFE00000) != 0xFFE00000) {
                throw new IllegalStateException("fixture lost sync at " + i);
            }
            int version = (h >>> 19) & 3;
            boolean mpeg1 = version == 3;
            int rate = new int[]{44100, 48000, 32000}[(h >>> 10) & 3] >> (mpeg1 ? 0 : version == 2 ? 1 : 2);
            int size = (mpeg1 ? 144000 : 72000) * L3_KBPS[mpeg1 ? 1 : 0][(h >>> 12) & 15] / rate + ((h >>> 9) & 1);
            offsets.add(i);
            i += size;
        }
        offsets.add(i);
        return offsets;
    }

    private static byte[] cat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) {
            out.writeBytes(p);
        }
        return out.toByteArray();
    }

    private static byte[] slice(byte[] d, int from, int to) {
        return Arrays.copyOfRange(d, from, to);
    }

    /** Bytes that are never 0 (zeros are not junk to ffmpeg) and never 0xFF (no false frame header). */
    private static byte[] junk(int n, long seed) {
        Random r = new Random(seed);
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) {
            b[i] = (byte) (1 + r.nextInt(254));
        }
        return b;
    }

    private static byte[] syncsafe(int n) {
        return new byte[]{(byte) ((n >> 21) & 0x7F), (byte) ((n >> 14) & 0x7F), (byte) ((n >> 7) & 0x7F), (byte) (n & 0x7F)};
    }

    private static byte[] id3v2(int version, int flags, byte[] body) {
        byte[] head = cat(new byte[]{'I', 'D', '3', (byte) version, 0, (byte) flags}, syncsafe(body.length));
        if ((flags & 0x10) != 0) {
            byte[] footer = cat(new byte[]{'3', 'D', 'I', (byte) version, 0, (byte) flags}, syncsafe(body.length));
            return cat(head, body, footer);
        }
        return cat(head, body);
    }

    private static byte[] titleFrame() {
        byte[] text = "Synthetic ÿà title".getBytes(StandardCharsets.ISO_8859_1);
        return cat("TIT2".getBytes(StandardCharsets.US_ASCII), new byte[]{0, 0, 0, (byte) (text.length + 1), 0, 0, 0},
                text, new byte[300]);
    }

    /** Fake frame headers padded with zeros, as cover art could carry by chance. */
    private static byte[] fakeFrames() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < 20; i++) {
            out.writeBytes(new byte[]{(byte) 0xFF, (byte) 0xFB, 0x50, 0x44});
            out.writeBytes(new byte[200]);
        }
        return out.toByteArray();
    }

    private static byte[] apeTag(byte[] value) {
        ByteBuffer item = ByteBuffer.allocate(8 + 18 + value.length).order(ByteOrder.LITTLE_ENDIAN);
        item.putInt(value.length).putInt(2).put("Cover Art (Front)\0".getBytes(StandardCharsets.US_ASCII)).put(value);
        int size = item.capacity() + 32;
        return cat(apeHeader(size, true), item.array(), apeHeader(size, false));
    }

    private static byte[] apeHeader(int size, boolean header) {
        ByteBuffer b = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN);
        b.put("APETAGEX".getBytes(StandardCharsets.US_ASCII)).putInt(2000).putInt(size).putInt(1)
                .putInt(0x80000000 | (header ? 0x20000000 : 0));
        return b.array();
    }

    private static byte[] id3v1() {
        byte[] tag = new byte[128];
        System.arraycopy("TAG".getBytes(StandardCharsets.US_ASCII), 0, tag, 0, 3);
        System.arraycopy("Synthetic".getBytes(StandardCharsets.US_ASCII), 0, tag, 3, 9);
        tag[127] = 12;
        return tag;
    }

    /** The LAME/Xing fields of the cbr-lame fixture's Info frame, edited. */
    private static byte[] editInfo(byte[] fixture, Integer delay, Integer padding, Integer flags, Long bytes,
                                   boolean dropEncoder) {
        byte[] d = fixture.clone();
        int x = id3Size(d) + 4 + 32;
        if (!new String(d, x, 4, StandardCharsets.US_ASCII).equals("Info") && !new String(d, x, 4, StandardCharsets.US_ASCII).equals("Xing")) {
            throw new IllegalStateException("no Info frame");
        }
        int oldFlags = ByteBuffer.wrap(d, x + 4, 4).getInt();
        int lame = x + 8 + ((oldFlags & 1) != 0 ? 4 : 0) + ((oldFlags & 2) != 0 ? 4 : 0) + ((oldFlags & 4) != 0 ? 100 : 0)
                + ((oldFlags & 8) != 0 ? 4 : 0);
        int pads = (d[lame + 21] & 0xFF) << 16 | (d[lame + 22] & 0xFF) << 8 | (d[lame + 23] & 0xFF);
        int newDelay = delay != null ? delay : pads >> 12;
        int newPadding = padding != null ? padding : pads & 4095;
        int v = newDelay << 12 | newPadding;
        d[lame + 21] = (byte) (v >> 16);
        d[lame + 22] = (byte) (v >> 8);
        d[lame + 23] = (byte) v;
        if (dropEncoder) {
            Arrays.fill(d, lame, lame + 9, (byte) 0);
        }
        if (bytes != null) {
            ByteBuffer.wrap(d, x + 12, 4).putInt((int) (long) bytes);
        }
        if (flags != null) {
            ByteBuffer.wrap(d, x + 4, 4).putInt(flags);
        }
        return d;
    }

    /**
     * Inputs derived from the fixtures. A value of type String names the fixture whose decode must come out
     * identical; an Integer is the length ffmpeg 9 gives for exactly these bytes.
     */
    static Map<String, Object[]> mp3VariantCases(Map<String, byte[]> fx) {
        byte[] notag = fx.get("notag.mp3");
        byte[] cbr = fx.get("cbr-lame.mp3");
        byte[] vbr = fx.get("vbr-xing.mp3");
        byte[] mono = fx.get("mono-lsf.mp3");
        List<Integer> nf = l3Frames(notag, 0);
        int cut = nf.get(20);
        int cbrId3 = id3Size(cbr);
        Map<String, Object[]> v = new LinkedHashMap<>();
        // Tags in front and behind are skipped and change nothing.
        v.put("id3v24-footer-unsync+notag", new Object[]{cat(id3v2(4, 0x90, titleFrame()), notag), "notag.mp3"});
        v.put("two-id3v2+cbr-lame", new Object[]{cat(id3v2(3, 0x80, titleFrame()), cbr), "cbr-lame.mp3"});
        v.put("notag+id3v1", new Object[]{cat(notag, id3v1()), "notag.mp3"});
        v.put("cbr-lame+ape+id3v1", new Object[]{cat(cbr, apeTag(new byte[150]), id3v1()), "cbr-lame.mp3"});
        // ffmpeg scans through an APE tag's body and decodes these fake headers as frames (it gives 89903 for the
        // same bytes behind cbr-lame); this decoder skips the body whole, by design.
        v.put("notag+ape-with-fake-frames+id3v1", new Object[]{cat(notag, apeTag(fakeFrames()), id3v1()), "notag.mp3"});
        // A tag that makes the file longer than the Info frame says by more than a sixteenth turns the end trim off,
        // in ffmpeg as here.
        v.put("cbr-lame+big-ape", new Object[]{cat(cbr, apeTag(new byte[3000])), 79535});
        // Junk before the first frame is found past, zeros or not.
        v.put("zeros+notag", new Object[]{cat(new byte[1000], notag), "notag.mp3"});
        v.put("junk+notag", new Object[]{cat(junk(777, 1), notag), "notag.mp3"});
        // ffmpeg only looks for the Info frame right after the ID3v2 tag, so behind junk nothing gets trimmed. The
        // frame itself is still not played: its plain-stereo header differs from the joint-stereo frames after it, so
        // the search for two matching frames starts past it.
        v.put("cbr-lame-junk-before-info", new Object[]{cat(slice(cbr, 0, cbrId3), junk(300, 2), slice(cbr, cbrId3, cbr.length)), 80640});
        // In the middle: zeros are skipped silently, junk costs the frame behind it, and so does a tag.
        v.put("notag-zeros-mid", new Object[]{cat(slice(notag, 0, cut), new byte[100], slice(notag, cut, notag.length)), "notag.mp3"});
        v.put("notag-junk-mid", new Object[]{cat(slice(notag, 0, cut), junk(51, 3), slice(notag, cut, notag.length)), 79488});
        // ffmpeg would scan through this tag's body and decode the fake headers in it as frames; this decoder skips
        // the body, so only the frame behind the tag is lost, as behind any junk. Measured on this decoder by design.
        v.put("notag-id3v2-mid", new Object[]{cat(slice(notag, 0, cut), id3v2(3, 0, fakeFrames()), slice(notag, cut, notag.length)), 79488});
        // A truncated last frame is decoded with zeros in place of the missing bytes.
        v.put("notag-truncated", new Object[]{slice(notag, 0, notag.length - 100), 80640});
        v.put("cbr-lame-truncated", new Object[]{slice(cbr, 0, cbr.length - 150), 79380});
        // The LAME tag's numbers decide the trimming exactly the way ffmpeg's demuxer does.
        v.put("cbr-lame-padding-0", new Object[]{editInfo(cbr, null, 0, null, null, false), 79535});
        v.put("cbr-lame-padding-529", new Object[]{editInfo(cbr, null, 529, null, null, false), 79535});
        v.put("cbr-lame-padding-530", new Object[]{editInfo(cbr, null, 530, null, null, false), 79534});
        v.put("cbr-lame-padding-4095", new Object[]{editInfo(cbr, null, 4095, null, null, false), 75969});
        v.put("cbr-lame-delay-0", new Object[]{editInfo(cbr, 0, null, null, null, false), 79956});
        v.put("cbr-lame-delay-2000", new Object[]{editInfo(cbr, 2000, null, null, null, false), 77956});
        v.put("vbr-xing-no-encoder", new Object[]{editInfo(vbr, null, null, null, null, true), 80640});
        v.put("cbr-lame-info-no-flags", new Object[]{editInfo(cbr, null, null, 0, null, false), 80640});
        // A file longer than its Info frame says by more than a sixteenth looks glued together: no end trim.
        long size = cbr.length - cbrId3;
        v.put("cbr-lame-looks-concatenated", new Object[]{editInfo(cbr, null, null, null, size - size / 8, false), 79535});
        v.put("cbr-lame-size-within-sixteenth", new Object[]{editInfo(cbr, null, null, null, size - size / 20, false), "cbr-lame.mp3"});
        v.put("cbr-lame-twice", new Object[]{cat(cbr, cbr), 160175});
        // A file cut from the middle: the first frame's bit reservoir is missing, and it still counts.
        List<Integer> mf = l3Frames(mono, id3Size(mono));
        v.put("mono-lsf-cut", new Object[]{slice(mono, mf.get(10), mono.length), 35712});
        return v;
    }

    private static void mp3Variants(Map<String, byte[]> fixtures) throws Exception {
        Map<String, Decoded> originals = new LinkedHashMap<>();
        for (String name : List.of("notag.mp3", "cbr-lame.mp3")) {
            originals.put(name, decode(MP3, fixtures.get(name), false));
        }
        for (Map.Entry<String, Object[]> e : mp3VariantCases(fixtures).entrySet()) {
            String name = e.getKey();
            byte[] data = (byte[]) e.getValue()[0];
            Object expected = e.getValue()[1];
            dump(name + ".mp3", data);
            Decoded d = within(20, name, () -> decode(MP3, data, true));
            if (expected instanceof String same) {
                Decoded o = originals.get(same);
                check(d.frames == o.frames && d.sha256.equals(o.sha256),
                        name + ": decodes exactly like " + same + " (" + d.frames + " frames)");
            } else {
                check(d.frames == (Integer) expected, name + ": " + d.frames + " frames, ffmpeg gives " + expected);
            }
        }
        // Behind junk only the next frame is lost: everything before it is untouched.
        byte[] notag = fixtures.get("notag.mp3");
        int cut = l3Frames(notag, 0).get(20);
        Decoded junked = decode(MP3, cat(slice(notag, 0, cut), junk(51, 3), slice(notag, cut, notag.length)), false);
        check(samePcm(junked, originals.get("notag.mp3"), 0, 20 * 1152 - 600),
                "notag-junk-mid: the frames before the junk are untouched");
    }

    private static void mp3Damage(Map<String, byte[]> fixtures) throws Exception {
        check(refuses(MP3, new byte[0], "empty"), "an empty file is an AudioFormatException");
        Random random = new Random(42);
        byte[] noise = new byte[40000];
        random.nextBytes(noise);
        check(refuses(MP3, noise, "noise"), "random bytes are an AudioFormatException");
        check(refuses(MP3, cat(id3v2(3, 0, new byte[100]), new byte[50]), "id3 only"),
                "a lone ID3v2 tag is an AudioFormatException");
        byte[] hugeId3 = cat(new byte[]{'I', 'D', '3', 3, 0, 0}, syncsafe(100_000_000), new byte[100]);
        check(refuses(MP3, hugeId3, "huge id3"), "an ID3v2 tag that claims 100 MB is an AudioFormatException");
        byte[] notag = fixtures.get("notag.mp3");
        List<Integer> nf = l3Frames(notag, 0);
        check(refuses(MP3, slice(notag, 0, nf.get(1)), "one frame"),
                "a single frame is an AudioFormatException, as ffmpeg finds no two consecutive frames");
        check(refuses(MP3, cat(new byte[]{'f', 'L', 'a', 'C'}, new byte[2000]), "flac as mp3"),
                "FLAC bytes given to the MP3 decoder are an AudioFormatException");

        int survived = 0;
        for (int seed = 0; seed < 120; seed++) {
            String fixture = MP3_FIXTURES.get(seed % MP3_FIXTURES.size()).file();
            byte[] mutated = mutate(fixtures.get(fixture), new Random(seed));
            String result = survive(MP3, mutated, "MP3 fuzz seed " + seed);
            if (!result.equals("threw")) {
                survived++;
            }
        }
        check(survived == 120, "damaged MP3s end in a decode or an AudioFormatException: " + survived + "/120");
        byte[] cbr = fixtures.get("cbr-lame.mp3");
        int clean = 0;
        int cuts = 0;
        for (int cut = 0; cut < cbr.length; cut += 97) {
            cuts++;
            if (!survive(MP3, slice(cbr, 0, cut), "MP3 cut at " + cut).equals("threw")) {
                clean++;
            }
        }
        check(clean == cuts, "an MP3 cut anywhere ends cleanly: " + clean + "/" + cuts);
    }

    /** Flips bytes, cuts out and copies in runs, inserts junk and truncates. */
    private static byte[] mutate(byte[] original, Random r) {
        byte[] d = original.clone();
        int kind = r.nextInt(5);
        if (kind == 0 || kind == 4) {
            int flips = 1 + r.nextInt(kind == 4 ? 2000 : 40);
            for (int i = 0; i < flips; i++) {
                d[r.nextInt(d.length)] ^= (byte) (1 << r.nextInt(8));
            }
        }
        if (kind == 1) {
            int at = r.nextInt(d.length);
            int len = r.nextInt(Math.min(3000, d.length - at) + 1);
            d = cat(slice(d, 0, at), slice(d, at + len, d.length));
        }
        if (kind == 2) {
            int at = r.nextInt(d.length);
            byte[] noise = new byte[1 + r.nextInt(5000)];
            r.nextBytes(noise);
            d = cat(slice(d, 0, at), noise, slice(d, at, d.length));
        }
        if (kind == 3) {
            int from = r.nextInt(d.length);
            int to = r.nextInt(d.length);
            int len = Math.min(r.nextInt(4000), Math.min(d.length - from, d.length - to));
            System.arraycopy(original, from, d, to, len);
            d = slice(d, 0, Math.max(1, d.length - r.nextInt(d.length / 2)));
        }
        return d;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // FLAC: a small encoder, then round trips

    private static final class Bits {
        private byte[] buf = new byte[1 << 12];
        private int count;

        private void ensure(int bits) {
            int need = (count + bits + 7) / 8 + 1;
            if (need > buf.length) {
                buf = Arrays.copyOf(buf, Math.max(need, buf.length * 2));
            }
        }

        void put(long value, int n) {
            ensure(n);
            for (int i = n - 1; i >= 0; i--) {
                if (((value >>> i) & 1) != 0) {
                    buf[count >> 3] |= (byte) (0x80 >>> (count & 7));
                }
                count++;
            }
        }

        void unary(long zeros) {
            ensure((int) zeros + 1);
            count += (int) zeros;
            put(1, 1);
        }

        void align() {
            count = (count + 7) & ~7;
        }

        int bytes() {
            return count / 8;
        }

        byte[] toArray() {
            return Arrays.copyOf(buf, (count + 7) / 8);
        }
    }

    private static int crc8(byte[] b, int from, int to) {
        int crc = 0;
        for (int i = from; i < to; i++) {
            crc ^= b[i] & 0xFF;
            for (int k = 0; k < 8; k++) {
                crc = (crc & 0x80) != 0 ? (crc << 1 ^ 0x07) & 0xFF : crc << 1 & 0xFF;
            }
        }
        return crc;
    }

    private static int crc16(byte[] b, int from, int to) {
        int crc = 0;
        for (int i = from; i < to; i++) {
            crc ^= (b[i] & 0xFF) << 8;
            for (int k = 0; k < 8; k++) {
                crc = (crc & 0x8000) != 0 ? (crc << 1 ^ 0x8005) & 0xFFFF : crc << 1 & 0xFFFF;
            }
        }
        return crc;
    }

    /** What one FLAC test stream is made of. Settings that vary per frame cycle through these arrays. */
    private static final class FlacSpec {
        String name;
        int rate = 44100;
        int channels = 2;
        int bps = 16;
        boolean variable;
        int[] blockSizes;          // the frames in order (the last one may be short)
        int[] channelModes = {0};  // 0 = independent, 8 left/side, 9 right/side, 10 mid/side (stereo only)
        int[] kinds = {1, 2, 3};   // 1 verbatim, 2 fixed, 3 LPC; constant is chosen whenever a channel is flat
        int[] lpcOrders = {1, 2, 4, 8, 12, 32};
        int[] rateStyles = {0};    // 0 STREAMINFO, 1 table, 12 kHz, 13 Hz, 14 tens of Hz
        int[] sizeStyles = {1};    // 0 STREAMINFO, 1 table
        boolean explicitBlockSize; // codes 6/7 even where a table code exists
        boolean id3;
        boolean metadata;
        boolean totalUnknown;
        long seed = 1;
    }

    private static final class FlacStreamData {
        byte[] bytes;
        long[][] samples;
        int total;
        List<Integer> frameOffsets = new ArrayList<>();
    }

    private static long[][] flacSignal(FlacSpec s) {
        int total = 0;
        for (int b : s.blockSizes) {
            total += b;
        }
        long[][] x = new long[s.channels][total];
        long max = (1L << (s.bps - 1)) - 1;
        long min = -(1L << (s.bps - 1));
        Random r = new Random(s.seed);
        int at = 0;
        for (int f = 0; f < s.blockSizes.length; f++) {
            int bs = s.blockSizes[f];
            int content = f % 6;
            long dc = (long) ((r.nextDouble() * 2 - 1) * max);
            int wasted = 1 + f % Math.max(1, Math.min(6, s.bps - 2));
            for (int i = 0; i < bs; i++) {
                double t = (at + i) / (double) s.rate;
                for (int c = 0; c < s.channels; c++) {
                    long v;
                    switch (content) {
                        case 1 -> v = 0;
                        case 2 -> v = dc;
                        case 4 -> v = ((i + c) & 1) == 0 ? max : min;
                        default -> {
                            double wave = 0.45 * Math.sin(2 * Math.PI * (220 + 110 * c) * t) + 0.2 * Math.sin(2 * Math.PI * 3100 * t)
                                    + 0.15 * (r.nextDouble() - 0.5);
                            v = Math.round(wave * max);
                        }
                    }
                    if (content == 3) {
                        v &= -(1L << wasted);
                    }
                    if (content == 5 && c > 0) {
                        v = x[0][at + i] - (long) (r.nextGaussian() * 3); // nearly equal channels: side is tiny
                    }
                    x[c][at + i] = Math.max(min, Math.min(max, v));
                }
            }
            at += bs;
        }
        return x;
    }

    private static final int[] BS_TABLE = {0, 192, 576, 1152, 2304, 4608, -1, -1, 256, 512, 1024, 2048, 4096, 8192,
            16384, 32768};
    private static final int[] SR_TABLE = {0, 88200, 176400, 192000, 8000, 16000, 22050, 24000, 32000, 44100, 48000,
            96000};

    private static int tableIndex(int[] table, int value) {
        for (int i = 1; i < table.length; i++) {
            if (table[i] == value) {
                return i;
            }
        }
        return -1;
    }

    private static void utf8(Bits b, long v) {
        if (v < 0x80) {
            b.put(v, 8);
            return;
        }
        int n = v < 0x800 ? 2 : v < 0x10000 ? 3 : v < 0x200000 ? 4 : v < 0x4000000 ? 5 : v < 0x80000000L ? 6 : 7;
        if (n == 7) {
            b.put(0xFE, 8);
        } else {
            b.put(((0xFF00 >> n) & 0xFF) | (v >>> (6 * (n - 1))), 8);
        }
        for (int i = n - 2; i >= 0; i--) {
            b.put(0x80 | ((v >>> (6 * i)) & 0x3F), 8);
        }
    }

    static FlacStreamData encodeFlac(FlacSpec s) {
        FlacStreamData out = new FlacStreamData();
        long[][] x = flacSignal(s);
        out.samples = x;
        out.total = x[0].length;
        ByteArrayOutputStream file = new ByteArrayOutputStream();
        if (s.id3) {
            file.writeBytes(id3v2(4, 0x10, titleFrame()));
        }
        file.writeBytes("fLaC".getBytes(StandardCharsets.US_ASCII));
        int minBs = Integer.MAX_VALUE;
        int maxBs = 0;
        for (int i = 0; i < s.blockSizes.length; i++) {
            maxBs = Math.max(maxBs, s.blockSizes[i]);
            if (i < s.blockSizes.length - 1 || s.blockSizes.length == 1) {
                minBs = Math.min(minBs, s.blockSizes[i]);
            }
        }
        if (!s.variable) {
            minBs = maxBs;
        }
        Bits info = new Bits();
        info.put(minBs, 16);
        info.put(maxBs, 16);
        info.put(0, 24);
        info.put(0, 24);
        info.put(s.rate, 20);
        info.put(s.channels - 1, 3);
        info.put(s.bps - 1, 5);
        info.put(s.totalUnknown ? 0 : out.total, 36);
        info.put(0, 64);
        info.put(0, 64);
        file.write(s.metadata ? 0 : 0x80);
        file.writeBytes(new byte[]{0, 0, 34});
        file.writeBytes(info.toArray());
        if (s.metadata) {
            byte[] comment = "\u0007\0\0\0checker\0\0\0\0".getBytes(StandardCharsets.ISO_8859_1);
            file.writeBytes(new byte[]{4, 0, 0, (byte) comment.length});
            file.writeBytes(comment);
            file.writeBytes(new byte[]{(byte) 0x81, 0, 1, 0});
            file.writeBytes(new byte[256]);
        }
        Random r = new Random(s.seed * 7919);
        int at = 0;
        for (int f = 0; f < s.blockSizes.length; f++) {
            int bs = s.blockSizes[f];
            int mode = s.channels == 2 ? s.channelModes[f % s.channelModes.length] : 0;
            if (mode == 0) {
                mode = s.channels - 1; // independent channels: the code is the channel count minus one
            }
            out.frameOffsets.add(file.size());
            file.writeBytes(encodeFrame(s, x, at, bs, f, mode, r));
            at += bs;
        }
        out.bytes = file.toByteArray();
        return out;
    }

    private static byte[] encodeFrame(FlacSpec s, long[][] x, int at, int bs, int f, int mode, Random r) {
        Bits b = new Bits();
        b.put(0x7FFC, 15);
        b.put(s.variable ? 1 : 0, 1);
        int bsCode = s.explicitBlockSize ? -1 : tableIndex(BS_TABLE, bs);
        if (bsCode < 0) {
            bsCode = bs <= 256 ? 6 : 7;
        }
        int rateStyle = s.rateStyles[f % s.rateStyles.length];
        int srCode = switch (rateStyle) {
            case 1 -> tableIndex(SR_TABLE, s.rate);
            case 12, 13, 14 -> rateStyle;
            default -> 0;
        };
        int sizeStyle = s.sizeStyles[f % s.sizeStyles.length];
        int ssCode = switch (s.bps) {
            case 8 -> 1;
            case 12 -> 2;
            case 16 -> 4;
            case 20 -> 5;
            case 24 -> 6;
            case 32 -> 7;
            default -> 0;
        };
        if (sizeStyle == 0) {
            ssCode = 0;
        }
        b.put(bsCode, 4);
        b.put(srCode, 4);
        b.put(mode, 4);
        b.put(ssCode, 3);
        b.put(0, 1);
        utf8(b, s.variable ? at : f);
        if (bsCode == 6) {
            b.put(bs - 1, 8);
        } else if (bsCode == 7) {
            b.put(bs - 1, 16);
        }
        if (srCode == 12) {
            b.put(s.rate / 1000, 8);
        } else if (srCode == 13) {
            b.put(s.rate, 16);
        } else if (srCode == 14) {
            b.put(s.rate / 10, 16);
        }
        byte[] head = b.toArray();
        b.put(crc8(head, 0, head.length), 8);

        long[][] coded = new long[s.channels][];
        int[] codedBps = new int[s.channels];
        for (int c = 0; c < s.channels; c++) {
            coded[c] = Arrays.copyOfRange(x[c], at, at + bs);
            codedBps[c] = s.bps;
        }
        if (mode >= 8) {
            long[] left = coded[0];
            long[] right = coded[1];
            long[] side = new long[bs];
            long[] mid = new long[bs];
            for (int i = 0; i < bs; i++) {
                side[i] = left[i] - right[i];
                mid[i] = (left[i] + right[i]) >> 1;
            }
            if (mode == 8) {
                coded[1] = side;
                codedBps[1]++;
            } else if (mode == 9) {
                coded[0] = side;
                codedBps[0]++;
            } else {
                coded[0] = mid;
                coded[1] = side;
                codedBps[1]++;
            }
        }
        for (int c = 0; c < s.channels; c++) {
            encodeSubframe(b, s, coded[c], codedBps[c], f * 31 + c, r);
        }
        b.align();
        byte[] frame = b.toArray();
        int crc = crc16(frame, 0, frame.length);
        return cat(frame, new byte[]{(byte) (crc >> 8), (byte) crc});
    }

    private static void encodeSubframe(Bits b, FlacSpec s, long[] v, int bps, int pick, Random r) {
        int bs = v.length;
        boolean flat = true;
        long or = 0;
        for (long x : v) {
            flat &= x == v[0];
            or |= x;
        }
        int wasted = or == 0 ? 0 : Math.min(Long.numberOfTrailingZeros(or), bps - 1);
        long[] w = v;
        if (wasted > 0) {
            w = new long[bs];
            for (int i = 0; i < bs; i++) {
                w[i] = v[i] >> wasted;
            }
        }
        int sbps = bps - wasted;
        int kind = flat ? 0 : s.kinds[pick % s.kinds.length];
        int order = 0;
        if (kind == 2) {
            order = Math.min(pick % 5, bs);
        } else if (kind == 3) {
            order = Math.min(s.lpcOrders[pick % s.lpcOrders.length], Math.min(32, bs));
            if (order == 0) {
                kind = 1;
            }
        }
        long[] res = new long[bs];
        int shift = 0;
        int precision = 0;
        long[] coef = new long[order];
        if (kind == 2) {
            for (int i = order; i < bs; i++) {
                long p = switch (order) {
                    case 0 -> 0;
                    case 1 -> w[i - 1];
                    case 2 -> 2 * w[i - 1] - w[i - 2];
                    case 3 -> 3 * w[i - 1] - 3 * w[i - 2] + w[i - 3];
                    default -> 4 * w[i - 1] - 6 * w[i - 2] + 4 * w[i - 3] - w[i - 4];
                };
                res[i] = w[i] - p;
            }
        } else if (kind == 3) {
            shift = r.nextInt(14);
            int need = 1;
            for (int j = 0; j < order; j++) {
                coef[j] = (1L << shift) / order + (j % 3 == 0 ? r.nextInt(3) - 1 : 0) * (pick % 2 == 0 ? 1 : -1);
                while (coef[j] < -(1L << (need - 1)) || coef[j] >= 1L << (need - 1)) {
                    need++;
                }
            }
            precision = need + r.nextInt(16 - need);
            for (int i = order; i < bs; i++) {
                long sum = 0;
                for (int j = 0; j < order; j++) {
                    sum += coef[j] * w[i - 1 - j];
                }
                res[i] = w[i] - (sum >> shift);
            }
        }
        if (kind >= 2) {
            // RFC 9639 lets a decoder assume residuals fit 32 signed bits; like a real encoder, fall back to verbatim.
            for (int i = order; i < bs; i++) {
                if (res[i] > Integer.MAX_VALUE || res[i] <= Integer.MIN_VALUE) {
                    kind = 1;
                    break;
                }
            }
        }
        b.put(0, 1);
        int type = switch (kind) {
            case 0 -> 0;
            case 1 -> 1;
            case 2 -> 8 + order;
            default -> 31 + order;
        };
        b.put(type, 6);
        if (wasted > 0) {
            b.put(1, 1);
            b.unary(wasted - 1);
        } else {
            b.put(0, 1);
        }
        if (kind == 0) {
            b.put(w[0], sbps);
            return;
        }
        if (kind == 1) {
            for (long x : w) {
                b.put(x, sbps);
            }
            return;
        }
        for (int i = 0; i < order; i++) {
            b.put(w[i], sbps);
        }
        if (kind == 3) {
            b.put(precision - 1, 4);
            b.put(shift, 5);
            for (long c : coef) {
                b.put(c, precision);
            }
        }
        writeResidual(b, res, bs, order, r);
    }

    private static void writeResidual(Bits b, long[] res, int bs, int order, Random r) {
        int maxOrder = 0;
        while (maxOrder < 8 && (bs & ((1 << (maxOrder + 1)) - 1)) == 0 && (bs >> (maxOrder + 1)) >= order
                && (bs >> (maxOrder + 1)) > 0) {
            maxOrder++;
        }
        int partitionOrder = r.nextInt(maxOrder + 1);
        long maxAbs = 0;
        for (int i = order; i < bs; i++) {
            maxAbs = Math.max(maxAbs, Math.abs(res[i]));
        }
        int method = maxAbs >= 1L << 20 || r.nextBoolean() ? 1 : 0;
        b.put(method, 2);
        b.put(partitionOrder, 4);
        int parts = 1 << partitionOrder;
        int partSize = bs >> partitionOrder;
        int i = order;
        for (int p = 0; p < parts; p++) {
            int count = p == 0 ? partSize - order : partSize;
            long sum = 0;
            long partMax = 0;
            for (int j = 0; j < count; j++) {
                sum += Math.abs(res[i + j]) * 2;
                partMax = Math.max(partMax, Math.abs(res[i + j]));
            }
            int rawBits = partMax == 0 ? 0 : 65 - Long.numberOfLeadingZeros(partMax);
            if (rawBits <= 31 && r.nextInt(6) == 0) {
                b.put(method == 0 ? 15 : 31, method == 0 ? 4 : 5);
                b.put(rawBits, 5);
                for (int j = 0; j < count; j++) {
                    b.put(res[i++], rawBits);
                }
                continue;
            }
            long mean = count == 0 ? 0 : sum / count;
            int k = 0;
            while ((mean >> (k + 1)) > 0 && k < (method == 0 ? 14 : 30)) {
                k++;
            }
            b.put(k, method == 0 ? 4 : 5);
            for (int j = 0; j < count; j++) {
                long u = res[i] << 1 ^ res[i] >> 63;
                i++;
                b.unary(u >>> k);
                b.put(u, k);
            }
        }
    }

    private static int[] fixedBlocks(int nominal, int total) {
        int n = (total + nominal - 1) / nominal;
        int[] sizes = new int[n];
        Arrays.fill(sizes, nominal);
        sizes[n - 1] = total - nominal * (n - 1);
        return sizes;
    }

    private static int[] variableBlocks(long seed, int count, int max) {
        Random r = new Random(seed);
        int[] sizes = new int[count];
        for (int i = 0; i < count; i++) {
            sizes[i] = 16 + r.nextInt(max - 15);
        }
        sizes[count - 1] = 1 + r.nextInt(15); // the last block may be shorter than 16
        return sizes;
    }

    private static List<FlacSpec> flacSpecs() {
        List<FlacSpec> specs = new ArrayList<>();
        FlacSpec s;

        s = new FlacSpec();
        s.name = "16-bit stereo, every stereo mode and subframe type";
        s.blockSizes = fixedBlocks(4096, 4096 * 18 + 1000);
        s.channelModes = new int[]{0, 8, 9, 10};
        s.kinds = new int[]{1, 2, 2, 2, 3, 3};
        s.rateStyles = new int[]{0, 1, 13, 14};
        s.sizeStyles = new int[]{0, 1};
        specs.add(s);

        s = new FlacSpec();
        s.name = "24-bit mid/side, LPC up to order 32, 48 kHz";
        s.rate = 48000;
        s.bps = 24;
        s.blockSizes = fixedBlocks(4608, 4608 * 12 + 77);
        s.channelModes = new int[]{10, 8};
        s.kinds = new int[]{3};
        s.lpcOrders = new int[]{32, 31, 17, 3, 1};
        s.rateStyles = new int[]{1, 12};
        s.seed = 2;
        specs.add(s);

        s = new FlacSpec();
        s.name = "8-bit mono at 8 kHz";
        s.rate = 8000;
        s.channels = 1;
        s.bps = 8;
        s.blockSizes = fixedBlocks(576, 576 * 30 + 5);
        s.rateStyles = new int[]{1, 12, 13};
        s.seed = 3;
        specs.add(s);

        for (int bps : new int[]{4, 10, 12, 20}) {
            s = new FlacSpec();
            s.name = bps + "-bit stereo, sample size " + (bps == 12 || bps == 20 ? "in the header" : "from STREAMINFO");
            s.bps = bps;
            s.rate = 22050;
            s.blockSizes = fixedBlocks(1152, 1152 * 20 + 400);
            s.channelModes = new int[]{9, 10, 0, 8};
            s.sizeStyles = new int[]{1, 0};
            s.seed = 10 + bps;
            specs.add(s);
        }

        s = new FlacSpec();
        s.name = "32-bit stereo: a 33-bit side channel";
        s.bps = 32;
        s.rate = 96000;
        s.blockSizes = fixedBlocks(2048, 2048 * 16 + 3);
        s.channelModes = new int[]{8, 9, 10, 0};
        s.kinds = new int[]{1, 2, 3};
        s.lpcOrders = new int[]{2, 8};
        s.seed = 32;
        specs.add(s);

        for (int ch = 3; ch <= 8; ch++) {
            s = new FlacSpec();
            s.name = ch + " channels";
            s.channels = ch;
            s.blockSizes = fixedBlocks(1024, 1024 * 8 + 99);
            s.seed = 100 + ch;
            specs.add(s);
        }

        s = new FlacSpec();
        s.name = "variable block sizes with sample numbers, explicit size codes";
        s.variable = true;
        s.explicitBlockSize = true;
        s.blockSizes = variableBlocks(7, 40, 6000);
        s.channelModes = new int[]{0, 10, 8, 9};
        s.rateStyles = new int[]{0, 1, 13, 14, 12};
        s.rate = 32000;
        s.seed = 7;
        specs.add(s);

        s = new FlacSpec();
        s.name = "variable block sizes, ID3v2 before fLaC, more metadata, no total";
        s.variable = true;
        s.blockSizes = variableBlocks(8, 30, 300);
        s.id3 = true;
        s.metadata = true;
        s.totalUnknown = true;
        s.rate = 37800;
        s.rateStyles = new int[]{0, 13, 14};
        s.seed = 8;
        specs.add(s);

        s = new FlacSpec();
        s.name = "65535-sample blocks";
        s.blockSizes = fixedBlocks(65535, 65535 * 2 + 1);
        s.channelModes = new int[]{10};
        s.seed = 9;
        specs.add(s);

        s = new FlacSpec();
        s.name = "tiny blocks of 16 and 192";
        s.blockSizes = new int[]{192, 192, 192, 192, 192, 192, 16};
        s.variable = true;
        s.seed = 11;
        specs.add(s);
        return specs;
    }

    private static void flacRoundTrips() throws Exception {
        for (FlacSpec s : flacSpecs()) {
            FlacStreamData data = encodeFlac(s);
            dump("flac-" + s.name.replaceAll("[^A-Za-z0-9]+", "-") + ".flac", data.bytes);
            Decoded d = within(30, s.name, () -> decode(FLAC, data.bytes, true));
            check(d.sampleRate == s.rate && d.channels == s.channels, s.name + ": " + d.sampleRate + " Hz x" + d.channels);
            check(d.frames == data.total, s.name + ": " + d.frames + " frames of " + data.total);
            check(d.hint == (s.totalUnknown ? -1 : data.total), s.name + ": frame count hint " + d.hint);
            float scale = 1f / (1L << (s.bps - 1));
            int bad = 0;
            for (int c = 0; c < s.channels && d.frames == data.total; c++) {
                for (int i = 0; i < data.total; i++) {
                    float want = (float) data.samples[c][i] * scale;
                    if (Float.floatToRawIntBits(d.pcm[c][i]) != Float.floatToRawIntBits(want)) {
                        bad++;
                    }
                }
            }
            check(d.frames == data.total && bad == 0, s.name + ": every sample comes back exactly (" + bad + " differ)");
        }
    }

    private static void flacDamage() throws Exception {
        FlacSpec s = flacSpecs().get(0);
        FlacStreamData data = encodeFlac(s);
        Decoded clean = decode(FLAC, data.bytes, false);
        int nominal = 4096;
        List<Integer> frames = data.frameOffsets;

        // One byte broken inside frame 5: that block is silence, every other frame is untouched.
        byte[] broken = data.bytes.clone();
        broken[(frames.get(5) + frames.get(6)) / 2] ^= 0x5A;
        Decoded d = decode(FLAC, broken, false);
        check(d.frames == clean.frames, "FLAC with a broken frame keeps its length: " + d.frames);
        boolean silent = true;
        for (int c = 0; c < 2 && d.frames == clean.frames; c++) {
            for (int i = 5 * nominal; i < 6 * nominal; i++) {
                silent &= d.pcm[c][i] == 0f;
            }
        }
        check(d.frames == clean.frames && silent && samePcm(d, clean, 0, 5 * nominal)
                && samePcm(d, clean, 6 * nominal, clean.frames), "FLAC: only the broken frame turns to silence");

        // Junk between frames is skipped; a missing frame is owed as silence of its length.
        byte[] junked = cat(slice(data.bytes, 0, frames.get(7)), junk(999, 5), slice(data.bytes, frames.get(7), data.bytes.length));
        Decoded j = decode(FLAC, junked, false);
        check(j.frames == clean.frames && samePcm(j, clean, 0, clean.frames), "FLAC: junk between frames is skipped");
        byte[] missing = cat(slice(data.bytes, 0, frames.get(3)), slice(data.bytes, frames.get(4), data.bytes.length));
        Decoded m = decode(FLAC, missing, false);
        check(m.frames == clean.frames && samePcm(m, clean, 0, 3 * nominal) && samePcm(m, clean, 4 * nominal, clean.frames),
                "FLAC: a lost frame becomes silence of its length, so the rest keeps its timing");
        Decoded tail = decode(FLAC, cat(data.bytes, id3v1()), false);
        check(tail.frames == clean.frames && samePcm(tail, clean, 0, clean.frames), "FLAC: an ID3v1 tag after the frames is ignored");

        check(refuses(FLAC, new byte[0], "flac empty"), "FLAC: an empty file is an AudioFormatException");
        check(refuses(FLAC, "fLaC".getBytes(StandardCharsets.US_ASCII), "flac marker only"),
                "FLAC: a marker with no metadata is an AudioFormatException");
        check(refuses(FLAC, slice(data.bytes, 0, frames.get(0)), "flac no frames"),
                "FLAC: metadata without frames is an AudioFormatException");
        byte[] noInfo = data.bytes.clone();
        noInfo[4] = (byte) 0x81; // STREAMINFO relabelled as padding
        check(refuses(FLAC, noInfo, "flac no streaminfo"), "FLAC: no STREAMINFO is an AudioFormatException");
        Random random = new Random(9);
        byte[] noise = new byte[30000];
        random.nextBytes(noise);
        check(refuses(FLAC, cat(slice(data.bytes, 0, frames.get(0)), noise), "flac noise frames"),
                "FLAC: noise where frames should be is an AudioFormatException");

        int survived = 0;
        List<FlacSpec> specs = flacSpecs();
        for (int seed = 0; seed < 80; seed++) {
            byte[] source = encodeFlac(specs.get(seed % specs.size())).bytes;
            if (!survive(FLAC, mutate(source, new Random(1000 + seed)), "FLAC fuzz seed " + seed).equals("threw")) {
                survived++;
            }
        }
        check(survived == 80, "damaged FLACs end in a decode or an AudioFormatException: " + survived + "/80");
        int clean2 = 0;
        int cuts = 0;
        for (int cut = 0; cut < data.bytes.length; cut += 1531) {
            cuts++;
            if (!survive(FLAC, slice(data.bytes, 0, cut), "FLAC cut at " + cut).equals("threw")) {
                clean2++;
            }
        }
        check(clean2 == cuts, "a FLAC cut anywhere ends cleanly: " + clean2 + "/" + cuts);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // WAV

    private static byte[] chunk(String id, byte[] body) {
        ByteBuffer b = ByteBuffer.allocate(8 + body.length + (body.length & 1)).order(ByteOrder.LITTLE_ENDIAN);
        b.put(id.getBytes(StandardCharsets.US_ASCII)).putInt(body.length).put(body);
        return b.array();
    }

    private static byte[] chunkSized(String id, long size, byte[] body) {
        ByteBuffer b = ByteBuffer.allocate(8 + body.length).order(ByteOrder.LITTLE_ENDIAN);
        b.put(id.getBytes(StandardCharsets.US_ASCII)).putInt((int) size).put(body);
        return b.array();
    }

    private static byte[] fmt(int tag, int channels, int rate, int bits) {
        int align = channels * ((bits + 7) / 8);
        ByteBuffer b = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
        b.putShort((short) tag).putShort((short) channels).putInt(rate).putInt(rate * align).putShort((short) align)
                .putShort((short) bits);
        return chunk("fmt ", b.array());
    }

    private static byte[] fmtExtensible(int subTag, int channels, int rate, int bits, int validBits) {
        int align = channels * ((bits + 7) / 8);
        ByteBuffer b = ByteBuffer.allocate(40).order(ByteOrder.LITTLE_ENDIAN);
        b.putShort((short) 0xFFFE).putShort((short) channels).putInt(rate).putInt(rate * align).putShort((short) align)
                .putShort((short) bits).putShort((short) 22).putShort((short) validBits).putInt((1 << channels) - 1)
                .putShort((short) subTag).put(new byte[]{0x00, 0x00, 0x00, 0x00, 0x10, 0x00, (byte) 0x80, 0x00, 0x00,
                        (byte) 0xAA, 0x00, 0x38, (byte) 0x9B, 0x71});
        return chunk("fmt ", b.array());
    }

    private static byte[] riff(String id, byte[]... chunks) {
        byte[] body = cat(chunks);
        ByteBuffer b = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN);
        b.put(id.getBytes(StandardCharsets.US_ASCII)).putInt(id.equals("RIFF") ? body.length + 4 : -1)
                .put("WAVE".getBytes(StandardCharsets.US_ASCII));
        return cat(b.array(), body);
    }

    /** Sample data and the floats ffmpeg makes of it. Kinds: u8, s16, s24, s32, f32, f64; {@code shift} zeroes low bits. */
    private record WavAudio(byte[] data, float[][] expected) {
    }

    private static WavAudio wavAudio(String kind, int channels, int frames, int lowZeroBits, long seed) {
        Random r = new Random(seed);
        int bytes = switch (kind) {
            case "u8" -> 1;
            case "s16" -> 2;
            case "s24" -> 3;
            case "s32", "f32" -> 4;
            default -> 8;
        };
        ByteBuffer b = ByteBuffer.allocate(frames * channels * bytes).order(ByteOrder.LITTLE_ENDIAN);
        float[][] expected = new float[channels][frames];
        for (int i = 0; i < frames; i++) {
            for (int c = 0; c < channels; c++) {
                boolean extreme = i % 50 == 0;
                switch (kind) {
                    case "u8" -> {
                        int v = extreme ? (c == 0 ? 0 : 255) : r.nextInt(256);
                        b.put((byte) v);
                        expected[c][i] = (v - 128) * (1.0f / 128);
                    }
                    case "s16" -> {
                        int v = (extreme ? (c == 0 ? Short.MIN_VALUE : Short.MAX_VALUE) : r.nextInt(65536) - 32768) & -(1 << lowZeroBits);
                        b.putShort((short) v);
                        expected[c][i] = v / 32768f;
                    }
                    case "s24" -> {
                        int v = (extreme ? (c == 0 ? -(1 << 23) : (1 << 23) - 1) : r.nextInt(1 << 24) - (1 << 23)) & -(1 << lowZeroBits);
                        b.put((byte) v).put((byte) (v >> 8)).put((byte) (v >> 16));
                        expected[c][i] = v / 8388608f;
                    }
                    case "s32" -> {
                        int v = extreme ? (c == 0 ? Integer.MIN_VALUE : Integer.MAX_VALUE) : r.nextInt();
                        b.putInt(v);
                        expected[c][i] = (float) v / 2147483648f;
                    }
                    case "f32" -> {
                        float v = i == 1 ? -0.0f : i == 2 ? 1.75f : i == 3 ? Float.MIN_VALUE : (float) (r.nextGaussian() * 0.4);
                        b.putFloat(v);
                        expected[c][i] = v;
                    }
                    default -> {
                        double v = i == 1 ? 1e-300 : i == 2 ? -3.5 : r.nextGaussian() * 0.4;
                        b.putDouble(v);
                        expected[c][i] = (float) v;
                    }
                }
            }
        }
        return new WavAudio(b.array(), expected);
    }

    private static void expectWav(String what, byte[] file, int rate, int channels, float[][] expected, int frames, long hint)
            throws Exception {
        dump("wav-" + what.replaceAll("[^A-Za-z0-9]+", "-") + ".wav", file);
        Decoded d = within(20, what, () -> decode(WAV, file, true));
        check(d.sampleRate == rate && d.channels == channels, "WAV " + what + ": " + d.sampleRate + " Hz x" + d.channels);
        check(d.frames == frames, "WAV " + what + ": " + d.frames + " frames, expected " + frames);
        check(d.hint == hint, "WAV " + what + ": frame count hint " + d.hint + ", expected " + hint);
        int bad = 0;
        for (int c = 0; c < channels && d.frames == frames; c++) {
            for (int i = 0; i < frames; i++) {
                if (Float.floatToRawIntBits(d.pcm[c][i]) != Float.floatToRawIntBits(expected[c][i])) {
                    bad++;
                }
            }
        }
        check(d.frames == frames && bad == 0, "WAV " + what + ": samples exactly as ffmpeg makes them (" + bad + " differ)");
    }

    private static void wavCases() throws Exception {
        int n = 5000;
        String[][] plain = {{"u8", "1", "8000", "8"}, {"s16", "2", "44100", "16"}, {"s24", "2", "48000", "24"},
                {"s32", "1", "96000", "32"}, {"f32", "2", "44100", "32"}, {"f64", "1", "22050", "64"}};
        for (String[] p : plain) {
            int ch = Integer.parseInt(p[1]);
            int rate = Integer.parseInt(p[2]);
            int bits = Integer.parseInt(p[3]);
            WavAudio a = wavAudio(p[0], ch, n, 0, bits);
            int tag = p[0].startsWith("f") ? 3 : 1;
            expectWav(p[0] + " x" + ch, riff("RIFF", fmt(tag, ch, rate, bits), chunk("data", a.data())), rate, ch, a.expected(), n, n);
        }
        // 12 and 20-bit samples sit left-aligned in 16 and 24-bit containers and are read as the container.
        WavAudio s12 = wavAudio("s16", 2, n, 4, 12);
        expectWav("12-bit", riff("RIFF", fmt(1, 2, 44100, 12), chunk("data", s12.data())), 44100, 2, s12.expected(), n, n);
        WavAudio s20 = wavAudio("s24", 2, n, 4, 20);
        expectWav("20-bit", riff("RIFF", fmt(1, 2, 44100, 20), chunk("data", s20.data())), 44100, 2, s20.expected(), n, n);
        WavAudio ext24 = wavAudio("s24", 6, n, 0, 6);
        expectWav("extensible 24-bit 6 channels", riff("RIFF", fmtExtensible(1, 6, 48000, 24, 24), chunk("data", ext24.data())),
                48000, 6, ext24.expected(), n, n);
        WavAudio extF = wavAudio("f32", 2, n, 0, 7);
        expectWav("extensible float", riff("RIFF", fmtExtensible(3, 2, 44100, 32, 32), chunk("data", extF.data())),
                44100, 2, extF.expected(), n, n);

        // Chunks in any order, odd sizes padded, whatever follows the data never read as audio.
        WavAudio a = wavAudio("s16", 2, n, 0, 99);
        byte[] list = chunk("LIST", "INFOISFT\5\0\0\0odd!".getBytes(StandardCharsets.US_ASCII));
        byte[] odd = chunk("bext", new byte[13]);
        byte[] fact = chunk("fact", new byte[]{(byte) n, (byte) (n >> 8), 0, 0});
        expectWav("chunks around fmt and data", riff("RIFF", list, odd, fmt(1, 2, 44100, 16), fact, chunk("JUNK", new byte[7]),
                chunk("data", a.data()), chunk("id3 ", new byte[333])), 44100, 2, a.expected(), n, n);
        expectWav("data before fmt", riff("RIFF", odd, chunk("data", a.data()), list, fmt(1, 2, 44100, 16)), 44100, 2,
                a.expected(), n, n);
        WavAudio oddData = wavAudio("u8", 1, 4999, 0, 5);
        expectWav("odd-sized data then a chunk", riff("RIFF", fmt(1, 1, 8000, 8), chunk("data", oddData.data()),
                chunk("LIST", new byte[40])), 8000, 1, oddData.expected(), 4999, 4999);

        // Sizes a streaming writer leaves behind, and sizes that lie.
        expectWav("data size 0", riff("RIFF", fmt(1, 2, 44100, 16), chunkSized("data", 0, a.data())), 44100, 2, a.expected(), n, -1);
        expectWav("data size 0xFFFFFFFF", riff("RIFF", fmt(1, 2, 44100, 16), chunkSized("data", 0xFFFFFFFFL, a.data())),
                44100, 2, a.expected(), n, -1);
        expectWav("data size past the end", riff("RIFF", fmt(1, 2, 44100, 16), chunkSized("data", n * 4L + 4000, a.data())),
                44100, 2, a.expected(), n, n + 1000);
        expectWav("partial frame at the end", cat(riff("RIFF", fmt(1, 2, 44100, 16), chunkSized("data", n * 4L + 3,
                a.data())), new byte[]{1, 2, 3}), 44100, 2, a.expected(), n, n);
        expectWav("data size not a whole frame", riff("RIFF", fmt(1, 2, 44100, 16), chunkSized("data", n * 4L - 2,
                a.data())), 44100, 2, a.expected(), n - 1, n - 1);

        // RF64 and BW64: the real size is in ds64.
        for (String id : List.of("RF64", "BW64")) {
            ByteBuffer ds = ByteBuffer.allocate(28).order(ByteOrder.LITTLE_ENDIAN);
            ds.putLong(0).putLong(n * 4L).putLong(n).putInt(0);
            expectWav(id, riff(id, chunk("ds64", ds.array()), fmt(1, 2, 44100, 16), chunkSized("data", 0xFFFFFFFFL, a.data()),
                    chunk("LIST", new byte[64])), 44100, 2, a.expected(), n, n);
        }
    }

    private static void wavDamage() throws Exception {
        WavAudio a = wavAudio("s16", 2, 1000, 0, 3);
        byte[] data = chunk("data", a.data());
        Object[][] cases = {
                {"empty", new byte[0]},
                {"not RIFF", cat("RIFX".getBytes(StandardCharsets.US_ASCII), new byte[100])},
                {"RIFF but not WAVE", cat(riff("RIFF", data), new byte[0])},
                {"no fmt", riff("RIFF", data)},
                {"no data", riff("RIFF", fmt(1, 2, 44100, 16))},
                {"fmt too short", riff("RIFF", chunk("fmt ", new byte[10]), data)},
                {"zero channels", riff("RIFF", fmt(1, 0, 44100, 16), data)},
                {"zero sample rate", riff("RIFF", fmt(1, 2, 0, 16), data)},
                {"ADPCM", riff("RIFF", fmt(2, 2, 44100, 4), data)},
                {"16-bit float", riff("RIFF", fmt(3, 2, 44100, 16), data)},
                {"48-bit integers", riff("RIFF", fmt(1, 2, 44100, 48), data)},
                {"unknown extensible GUID", riff("RIFF", fmtExtensible(1, 2, 44100, 16, 16), data)},
                {"truncated fmt", slice(riff("RIFF", fmt(1, 2, 44100, 16), data), 0, 30)},
                {"data before fmt, streamed", riff("RIFF", chunkSized("data", 0, a.data()))},
        };
        // Break the GUID's suffix in the last-but-two case.
        byte[] guid = (byte[]) cases[11][1];
        guid[12 + 8 + 30] ^= 0x01;
        // Make "RIFF but not WAVE" say WAVX.
        byte[] notWave = (byte[]) cases[2][1];
        notWave[11] = 'X';
        for (Object[] c : cases) {
            check(refuses(WAV, (byte[]) c[1], "wav " + c[0]), "WAV " + c[0] + " is an AudioFormatException");
        }
        int survived = 0;
        byte[] source = riff("RIFF", chunk("LIST", new byte[21]), fmt(1, 2, 44100, 16), data);
        for (int seed = 0; seed < 60; seed++) {
            if (!survive(WAV, mutate(source, new Random(2000 + seed)), "WAV fuzz seed " + seed).equals("threw")) {
                survived++;
            }
        }
        check(survived == 60, "damaged WAVs end in a decode or an AudioFormatException: " + survived + "/60");
    }

    /**
     * Compressed audio inside WAV: MPEG frames (format 0x55, and 0x50 for Layer II) are decoded as ffmpeg decodes them,
     * telephone G.711 mu-law and A-law are expanded exactly as ffmpeg expands them, and every other codec is a format
     * the mod does not read, by its name, never a damaged file (the old message called MP3 in WAV "Damaged WAV format").
     */
    private static void wavCodecs(Map<String, byte[]> fixtures) throws Exception {
        // Every byte value once; the SHA-256 is of ffmpeg 9's f32 decode of these very files.
        byte[] all = new byte[256];
        for (int i = 0; i < 256; i++) {
            all[i] = (byte) i;
        }
        String[][] g711 = {{"7", "mu-law", "34371823a5daca165d7cae3c7afa7ea7683ab9176cc8423deaa23c97199adc7f",
                "0:-32124 1:-31100 85:-716 127:0 128:32124 213:716 254:8 255:0"},
                {"6", "A-law", "d4f2bd1be9bad67461eab90762a5ddd77cb74e988c585d4a3a51a7204bdd09ec",
                        "0:-5504 1:-5248 85:-8 127:-848 128:5504 213:8 254:880 255:848"}};
        for (String[] g : g711) {
            byte[] file = riff("RIFF", fmt(Integer.parseInt(g[0]), 1, 8000, 8), chunk("data", all));
            Decoded d = within(20, "WAV " + g[1], () -> decode(WAV, file, true));
            check(d.frames == 256 && d.channels == 1 && d.sampleRate == 8000, "WAV " + g[1] + ": 256 samples at 8 kHz");
            ByteBuffer bytes = ByteBuffer.allocate(4 * 256).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < Math.min(256, d.frames); i++) {
                bytes.putFloat(d.pcm[0][i]);
            }
            String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.array()));
            check(sha.equals(g[2]), "WAV " + g[1] + ": every byte value expands exactly as ffmpeg expands it (" + sha + ")");
            for (String probe : g[3].split(" ")) {
                int at = Integer.parseInt(probe.split(":")[0]);
                int value = Integer.parseInt(probe.split(":")[1]);
                check(d.frames == 256 && Math.round(d.pcm[0][at] * 32768f) == value,
                        "WAV " + g[1] + ": byte " + at + " is " + value);
            }
        }
        // MP3 in WAV: the data chunk is the fixture's frames. ffmpeg decodes these files to the very frames of the MP3
        // itself (80640 of them, measured), the tag-less one and the one that starts with its ID3 and LAME tags alike.
        Decoded reference = decode(MP3, fixtures.get("notag.mp3"), false);
        for (String name : List.of("notag.mp3", "cbr-lame.mp3")) {
            byte[] mp3 = fixtures.get(name);
            ByteBuffer extra = ByteBuffer.allocate(30).order(ByteOrder.LITTLE_ENDIAN);
            extra.putShort((short) 0x55).putShort((short) 2).putInt(44100).putInt(8000).putShort((short) 1)
                    .putShort((short) 0).putShort((short) 12).putShort((short) 1).putInt(2).putShort((short) 417)
                    .putShort((short) 1).putShort((short) 1393);
            byte[] file = riff("RIFF", chunk("fmt ", extra.array()), chunk("data", mp3));
            Decoded d = within(20, "MP3 in WAV " + name, () -> decode(WAV, file, true));
            check(d.frames == 80640 && d.channels == 2 && d.sampleRate == 44100,
                    "MP3 in WAV (" + name + "): " + d.frames + " frames, ffmpeg gives 80640");
            double worst = 0;
            for (int c = 0; c < 2 && d.frames == reference.frames; c++) {
                for (int i = 0; i < d.frames; i++) {
                    worst = Math.max(worst, Math.abs(d.pcm[c][i] - reference.pcm[c][i]));
                }
            }
            check(d.frames == reference.frames && worst < 1e-4, "MP3 in WAV (" + name + ") is the MP3's audio: " + worst);
        }
        byte[] layer2 = fixtures.get("layer2.mp2");
        ByteBuffer mpeg = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
        mpeg.putShort((short) 0x50).putShort((short) 1).putInt(44100).putInt(16000).putShort((short) 1).putShort((short) 0);
        Decoded inWav = within(20, "Layer II in WAV", () -> decode(WAV, riff("RIFF", chunk("fmt ", mpeg.array()),
                chunk("data", layer2)), true));
        Decoded direct = decode(MP3, layer2, false);
        check(inWav.frames > 0 && inWav.channels == direct.channels && inWav.sampleRate == direct.sampleRate,
                "MPEG Layer II in WAV (0x50) decodes: " + inWav.frames + " frames");

        // Codecs nobody here decodes: unsupported, by name.
        byte[] data = chunk("data", new byte[4000]);
        Object[][] unsupported = {
                // IMA ADPCM at 4 bits and Microsoft ADPCM are read now (native-audio-extras); the 3-bit IMA variant
                // and G.726 are not.
                {"3-bit IMA ADPCM", riff("RIFF", fmt(0x11, 2, 44100, 3), data), "WAV with IMA ADPCM audio (3 bits)"},
                {"G.726 ADPCM", riff("RIFF", fmt(0x45, 1, 8000, 4), data), "WAV with G.726 ADPCM audio"},
                {"GSM", riff("RIFF", fmt(0x31, 1, 8000, 0), data), "WAV with GSM 6.10 audio"},
                {"AAC", riff("RIFF", fmt(0xFF, 2, 44100, 16), data), "WAV with AAC audio"},
                {"an unknown codec", riff("RIFF", fmt(0x1234, 2, 44100, 16), data), "WAV with audio in codec 0x1234"},
                {"extensible ADPCM", riff("RIFF", fmtExtensible(0x11, 2, 44100, 3, 3), data),
                        "WAV with IMA ADPCM audio (3 bits)"},
        };
        for (Object[] u : unsupported) {
            String formatName = unsupportedName(WAV, (byte[]) u[1]);
            check(u[2].equals(formatName), "WAV with " + u[0] + " is UnsupportedFormatException(" + u[2] + "), got " + formatName);
        }
        byte[] guid = riff("RIFF", fmtExtensible(1, 2, 44100, 16, 16), data);
        guid[12 + 8 + 30] ^= 0x01;
        check("WAV with audio in an unknown codec".equals(unsupportedName(WAV, guid)),
                "a WAV whose sub-format is another vendor's GUID is unsupported, not damaged");
        check(unsupportedName(WAV, riff("RIFF", fmt(1, 2, 0, 16), data)) == null,
                "a PCM WAV with a sample rate of 0 is still damage");
    }

    /** The format name of the UnsupportedFormatException a file is refused with, or null for any other outcome. */
    private static String unsupportedName(Opener opener, byte[] data) throws Exception {
        return within(20, "unsupported", () -> {
            try (PcmStream s = opener.open(new ByteArrayInputStream(data))) {
                return null;
            } catch (UnsupportedFormatException e) {
                return e.formatName();
            } catch (Exception e) {
                return null;
            }
        });
    }

    /**
     * {@link Mp3Decoder#findsFrames}, which the conversion asks when no signature matches: an MP3 without a tag that
     * starts with a stray byte or a run of zeros is MP3 (ffmpeg plays such files, and so does the decoder); text,
     * noise and AAC are not.
     */
    private static void mp3Sync(Map<String, byte[]> fixtures) throws Exception {
        byte[] mp3 = fixtures.get("notag.mp3");
        byte[] junk = new byte[3000];
        for (int i = 0; i < junk.length; i++) {
            junk[i] = (byte) ((i * 37 + 11) & 0x7F);
        }
        Object[][] cases = {
                {"one stray byte", cat(new byte[]{0x5A}, mp3), true},
                {"3000 bytes of junk", cat(junk, mp3), true},
                {"100 zero bytes", cat(new byte[100], mp3), true},
                {"60000 zero bytes", cat(new byte[60000], mp3), true},
                {"text", "this is not an mp3 file at all\n".repeat(300).getBytes(StandardCharsets.US_ASCII), false},
                {"an ADTS AAC header", cat(new byte[]{(byte) 0xFF, (byte) 0xF1, 0x50, (byte) 0x80}, new byte[4000]), false},
        };
        for (Object[] c : cases) {
            byte[] bytes = (byte[]) c[1];
            boolean found = Mp3Decoder.findsFrames(Arrays.copyOf(bytes, Math.min(bytes.length, Mp3Decoder.SYNC_PROBE_BYTES)));
            check(found == (Boolean) c[2], "MP3 sync on " + c[0] + ": " + found);
            if (found) {
                Decoded d = within(20, "MP3 after " + c[0], () -> decode(MP3, bytes, false));
                check(d.frames == 80640, "and the decoder plays it whole after " + c[0] + ": " + d.frames + " frames");
            }
        }
        Random random = new Random(42);
        int falseAlarms = 0;
        for (int i = 0; i < 200; i++) {
            byte[] noise = new byte[Mp3Decoder.SYNC_PROBE_BYTES];
            random.nextBytes(noise);
            if (Mp3Decoder.findsFrames(noise)) {
                falseAlarms++;
            }
        }
        check(falseAlarms == 0, "random bytes are not taken for MP3: " + falseAlarms + " of 200");
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Sniffing and interrupts

    private static byte[] head(byte[] file) {
        return Arrays.copyOf(file, Math.min(64, file.length));
    }

    private static void sniffing(Map<String, byte[]> fixtures) {
        byte[] flac = encodeFlac(flacSpecs().get(2)).bytes;
        byte[] flacId3 = cat(id3v2(3, 0, new byte[20]), flac);
        byte[] wav = riff("RIFF", fmt(1, 2, 44100, 16), chunk("data", new byte[400]));
        byte[] rf64 = riff("RF64", chunk("ds64", new byte[28]), fmt(1, 2, 44100, 16), chunk("data", new byte[400]));
        byte[] ogg = cat("OggS".getBytes(StandardCharsets.US_ASCII), new byte[60]);
        for (Mp3Fixture f : MP3_FIXTURES) {
            byte[] h = head(fixtures.get(f.file()));
            check(Mp3Decoder.looksLike(h) && !FlacDecoder.looksLike(h) && !WavDecoder.looksLike(h),
                    f.file() + " is sniffed as MP3 only");
        }
        check(Mp3Decoder.looksLike(head(cat(new byte[20], fixtures.get("notag.mp3")))), "MP3 after zero padding is sniffed");
        check(FlacDecoder.looksLike(head(flac)) && !Mp3Decoder.looksLike(head(flac)) && !WavDecoder.looksLike(head(flac)),
                "FLAC is sniffed as FLAC only");
        check(FlacDecoder.looksLike(head(flacId3)), "FLAC behind a short ID3v2 tag is sniffed as FLAC");
        check(WavDecoder.looksLike(head(wav)) && WavDecoder.looksLike(head(rf64)) && !Mp3Decoder.looksLike(head(wav))
                && !FlacDecoder.looksLike(head(wav)), "WAV and RF64 are sniffed as WAV only");
        check(!Mp3Decoder.looksLike(head(ogg)) && !FlacDecoder.looksLike(head(ogg)) && !WavDecoder.looksLike(head(ogg)),
                "Ogg is none of them");
        byte[] adts = {(byte) 0xFF, (byte) 0xF1, 0x50, (byte) 0x80, 0, 0, 0, 0};
        check(!Mp3Decoder.looksLike(adts), "AAC ADTS is not MP3");
        check(!Mp3Decoder.looksLike(new byte[3]) && !FlacDecoder.looksLike(new byte[3]) && !WavDecoder.looksLike(new byte[3])
                && !Mp3Decoder.looksLike(null) && !FlacDecoder.looksLike(null) && !WavDecoder.looksLike(null),
                "short or missing heads are nothing");
    }

    private static void interrupts(Map<String, byte[]> fixtures) throws Exception {
        byte[] mp3 = fixtures.get("cbr-lame.mp3");
        byte[] flac = encodeFlac(flacSpecs().get(0)).bytes;
        WavAudio a = wavAudio("s16", 2, 50000, 0, 1);
        byte[] wav = riff("RIFF", fmt(1, 2, 44100, 16), chunk("data", a.data()));
        Map<String, Object[]> cases = new LinkedHashMap<>();
        cases.put("MP3", new Object[]{MP3, mp3});
        cases.put("FLAC", new Object[]{FLAC, flac});
        cases.put("WAV", new Object[]{WAV, wav});
        for (Map.Entry<String, Object[]> e : cases.entrySet()) {
            Opener opener = (Opener) e.getValue()[0];
            byte[] data = (byte[]) e.getValue()[1];
            String result = within(20, e.getKey() + " interrupt", () -> {
                Thread.currentThread().interrupt();
                try (PcmStream s = opener.open(new Trickle(data, 5))) {
                    return "opened";
                } catch (InterruptedIOException expected) {
                    return "interrupted";
                } finally {
                    Thread.interrupted();
                }
            });
            check(result.equals("interrupted"), e.getKey() + ": an interrupted open throws InterruptedIOException");
            result = within(20, e.getKey() + " interrupt while reading", () -> {
                try (PcmStream s = opener.open(new Trickle(data, 6))) {
                    float[][] buf = new float[s.channels()][512];
                    s.read(buf, 0, 512);
                    Thread.currentThread().interrupt();
                    for (int i = 0; i < 1000; i++) {
                        if (s.read(buf, 0, 512) < 0) {
                            return "ended";
                        }
                    }
                    return "ignored";
                } catch (InterruptedIOException expected) {
                    return "interrupted";
                } finally {
                    Thread.interrupted();
                }
            });
            check(result.equals("interrupted"), e.getKey() + ": an interrupt while reading throws InterruptedIOException");
        }
    }
}

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
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
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import ru.gora.musicbattle.audio.codec.AudioFormatException;
import ru.gora.musicbattle.audio.codec.OpusDecoder;
import ru.gora.musicbattle.audio.codec.PcmStream;
import ru.gora.musicbattle.audio.codec.UnsupportedFormatException;
import ru.gora.musicbattle.audio.codec.WebmDecoder;

/**
 * The pure-Java Opus decoder in audio/codec (a port of the libopus 1.6.1 decoder) with its two containers: Ogg Opus
 * ({@link OpusDecoder}) and WebM/Matroska ({@link WebmDecoder}). The script compiles them with no classpath at all,
 * so this also proves they need neither Minecraft nor any library.
 *
 * <p>The fixtures in tools/fixtures/opus were encoded from synthetic signals only (chords, a bass line, noise-burst
 * drums, clicks, a sweep and silence; no recorded audio; the generators and recipes are in tools/fixtures/gen) with
 * ffmpeg 9.0.1's libopus 1.6.1 encoder, and mode-switch.opus with the libopus encoder itself, forced to change mode,
 * bandwidth, frame size and channel count every few frames, so its packets carry redundant CELT frames in both
 * directions. Between them they hold every mode (SILK narrowband, mediumband and wideband, hybrid, CELT), frame sizes
 * from 2.5 to 120 ms, mono, stereo, 5.1, FEC and DTX. Their expected values come from outside this decoder: the length
 * is what ffmpeg's own decoder and ffmpeg with libopus both give; the SHA-256 is of the scalar libopus 1.6.1 build's
 * output with the same trimming, which the Java port must reproduce bit for bit; the probe samples are Homebrew's
 * libopus (through ffmpeg), which differs from the scalar build by at most 2.2e-6, at the steepest points of the wave,
 * so a shift by a single sample fails by orders of magnitude.
 *
 * <p>Everything else is built here from the fixtures' packets: Ogg and WebM files laid out in every way the readers
 * must follow (page and lacing shapes, multiplexed and chained streams, clusters of unknown size, video tracks,
 * trimming, lost blocks and wobbling timestamps), and damaged or hostile ones, which must end in an
 * {@link AudioFormatException} or a clean end, never another exception, a hang or memory that grows with what the file
 * claims or with how many large packets it holds. Nothing here runs ffmpeg or the
 * game. With {@code dump <dir>} after the project root, the built files are also written out, for comparing against
 * ffmpeg by hand.
 */
public final class NativeOpusDecoderChecks {
    private static int checks;
    private static final List<String> FAILURES = new ArrayList<>();
    private static Path dumpDir;

    private interface Opener {
        PcmStream open(InputStream in) throws IOException;
    }

    private static final Opener OGG = OpusDecoder::open;
    private static final Opener WEBM = WebmDecoder::open;

    /** Homebrew's libopus against the scalar build that the hashes come from: 2.2e-6 at most on every test file. */
    private static final float PROBE_TOLERANCE = 1e-5f;

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args.length > 0 ? args[0] : ".");
        if (args.length > 2 && args[1].equals("dump")) {
            dumpDir = Path.of(args[2]);
            Files.createDirectories(dumpDir);
        }
        Map<String, byte[]> fixtures = loadFixtures(root.resolve("tools/fixtures/opus"));
        // First, while the JIT is still cold, as it is the first time a player converts a song.
        speed(fixtures);
        fixtures(fixtures);
        oggLayouts(fixtures);
        oggTiming(fixtures);
        oggDamage(fixtures);
        webmLayouts(fixtures);
        webmTiming(fixtures);
        webmDamage(fixtures);
        unsupported(fixtures);
        fuzz(fixtures);
        sniffing(fixtures);
        interrupts(fixtures);
        memory(fixtures);

        if (!FAILURES.isEmpty()) {
            System.out.println(FAILURES.size() + " of " + checks + " native Opus decoder checks FAILED:");
            for (String failure : FAILURES) {
                System.out.println("  - " + failure);
            }
            System.exit(1);
        }
        System.out.println("Native Opus decoder checks passed: " + checks);
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
        Thread t = new Thread(r, "native-opus-decoder-check");
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

    /** Hands out a few bytes at a time, so every refill path of the readers runs. */
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
            if (s.read(buf, 0, 0) != 0) {
                throw new IllegalStateException("a read of no frames did not return 0");
            }
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

    /** Whether b holds a's frames [aFrom, aFrom + count) at [bFrom, ...), bit for bit. */
    private static boolean samePcm(Decoded a, int aFrom, Decoded b, int bFrom, int count) {
        if (a.channels != b.channels || aFrom < 0 || bFrom < 0 || aFrom + count > a.frames
                || bFrom + count > b.frames) {
            return false;
        }
        for (int c = 0; c < a.channels; c++) {
            for (int i = 0; i < count; i++) {
                if (Float.floatToRawIntBits(a.pcm[c][aFrom + i]) != Float.floatToRawIntBits(b.pcm[c][bFrom + i])) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean samePcm(Decoded a, Decoded b) {
        return a.frames == b.frames && samePcm(a, 0, b, 0, a.frames);
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

    /** The message of the AudioFormatException a file is refused with, or null when it decodes. */
    private static String refusal(Opener opener, byte[] data, String what) throws Exception {
        return within(20, what, () -> {
            try (PcmStream s = opener.open(new ByteArrayInputStream(data))) {
                float[][] buf = new float[Math.max(1, s.channels())][4096];
                while (s.read(buf, 0, 4096) > 0) {
                    // Drain: damage part way through must surface as well.
                }
                return null;
            } catch (AudioFormatException e) {
                return e.getMessage() == null ? "" : e.getMessage();
            } catch (Exception | Error e) {
                check(false, what + ": threw " + e);
                return null;
            }
        });
    }

    /** The format name of the UnsupportedFormatException a file is refused with, or "-" for any other outcome. */
    private static String unsupportedName(Opener opener, byte[] data) throws Exception {
        return within(20, "unsupported", () -> {
            try (PcmStream s = opener.open(new ByteArrayInputStream(data))) {
                return "-";
            } catch (UnsupportedFormatException e) {
                return String.valueOf(e.formatName());
            } catch (Exception e) {
                return "-";
            }
        });
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

    // ---------------------------------------------------------------------------------------------------------------
    // The fixtures

    private record OpusFixture(String file, int channels, int frames, String sha256, int[] probes, float[] values) {
    }

    // Generated by work/scripts/expect.py in the decoder's scratch tree from ffmpeg 9.0.1 (lengths), the scalar libopus
    // 1.6.1 build (hashes) and Homebrew libopus 1.6.1 through ffmpeg (probes, in Vorbis channel order).
    private static final List<OpusFixture> FIXTURES = List.of(
            // CELT fullband in 2.5 ms frames, mono, 48 kbit/s.
            new OpusFixture("celt-mono-2.5ms.opus", 1, 144000,
                    "b1f3a5cfa95010cde7c5334ca6381fbc0ea63edf9f9904ebb7c523524873f55d",
                    new int[]{120226, 0, 265, 0, 96119, 0, 72180, 0, 48228, 0, 24105, 0, 143996, 0, 132636, 0, 20571, 0,
                            48000, 0, 72000, 0, 143999, 0},
                    new float[]{-0.6528571844100952f, -0.10467386245727539f, 0.4995052218437195f, -0.2670009136199951f,
                            0.3301871418952942f, 0.3780445158481598f, -0.25974488258361816f, 0.06197764724493027f,
                            0.09393838793039322f, -0.3268159031867981f, -0.3927172124385834f, -0.17633545398712158f}),
            // CELT stereo in 120 ms packets: code 3, six 20 ms frames each.
            new OpusFixture("celt-stereo-120ms.opus", 2, 144000,
                    "7a7b0cb91af90be12b22e26ea673e9d2eb056d79c56c5ce719456da53a811dea",
                    new int[]{72108, 1, 48325, 1, 96140, 1, 120194, 1, 24093, 1, 20, 1, 143998, 1, 86867, 1, 20571, 0,
                            48000, 1, 72000, 1, 143999, 1},
                    new float[]{0.7555496096611023f, -0.6904066801071167f, 0.6593337655067444f, 0.049829185009002686f,
                            0.08741676807403564f, 0.4231409430503845f, -0.2821735143661499f, -0.28157904744148254f,
                            0.05862797796726227f, 0.1330445408821106f, -0.1249535083770752f, -0.2060391902923584f}),
            // CELT stereo at 96 kbit/s, what a downloaded song mostly is.
            new OpusFixture("celt-stereo-96k.opus", 2, 144000,
                    "1cba0781e85872275d67d0c0eed50bd225dc438dedb6b7f1841781408dc000b1",
                    new int[]{120041, 1, 96060, 1, 72166, 1, 24040, 1, 48168, 1, 3, 1, 135252, 1, 83021, 1, 20571, 0,
                            48000, 1, 72000, 1, 143999, 1},
                    new float[]{0.7006197571754456f, -0.6686979532241821f, -0.3547953963279724f, 0.302249014377594f,
                            0.013559818267822266f, 0.09002742171287537f, -0.0990903377532959f, 0.21563568711280823f,
                            0.061087384819984436f, 0.11029548943042755f, -0.12224140763282776f, -0.2494482696056366f}),
            // SILK wideband with DTX: a second of digital silence goes out as 48 one-byte packets the decoder fills.
            new OpusFixture("dtx-mono.opus", 1, 144000,
                    "a14915c3c13067905c8267f72df49273504902e33e8c740bf71318de7f85cdc5",
                    new int[]{120419, 0, 24702, 0, 326, 0, 47999, 0, 143806, 0, 34209, 0, 130016, 0, 106572, 0, 20571, 0,
                            48000, 0, 72000, 0, 143999, 0},
                    new float[]{0.515472412109375f, -0.083709716796875f, -0.23626708984375f, 0.1005859375f,
                            -0.16802978515625f, -0.111541748046875f, -0.233795166015625f, 0.17559814453125f,
                            0.29718017578125f, 0.063507080078125f, 0.0f, 0.2830810546875f}),
            // Hybrid fullband stereo with in-band FEC (LBRR data the decoder skips over).
            new OpusFixture("hybrid-fec-stereo.opus", 2, 144000,
                    "8d9adcb5a0e3562bb01654a031d4b7f5da234b74102507efd441c5246cd9a23c",
                    new int[]{48226, 1, 72164, 1, 96385, 1, 24372, 1, 120044, 1, 119, 0, 143996, 1, 83836, 1, 20571, 0,
                            48000, 1, 72000, 1, 143999, 1},
                    new float[]{-0.22763386368751526f, -0.22737830877304077f, -0.1419830322265625f,
                            -0.362199991941452f, -0.042652398347854614f, -0.6961580514907837f, 0.13286900520324707f,
                            -0.18066741526126862f, 0.18847395479679108f, -0.08051644265651703f, 0.09414640069007874f,
                            0.1867755800485611f}),
            // 38 switches between SILK, hybrid and CELT at every bandwidth, 2.5 to 60 ms, some packets mono: redundant
            // CELT frames both ways, plain transitions and the hybrid-to-SILK fade. The last page trims 520 samples.
            new OpusFixture("mode-switch.opus", 2, 142688,
                    "a837868891476b299b7077189aa2538fc3baa63484a1f85badf6f3cf83373672",
                    new int[]{96042, 1, 4413, 1, 72060, 1, 49214, 1, 120292, 1, 24088, 1, 135249, 1, 83018, 1, 20384, 0,
                            47562, 0, 71344, 0, 142687, 1},
                    new float[]{-0.01969611644744873f, 0.11434540152549744f, -0.055206298828125f, 0.3494791090488434f,
                            -0.23443603515625f, 0.12347412109375f, -0.110748291015625f, 0.1982421875f,
                            -0.09769182652235031f, -0.043853759765625f, -0.040863037109375f, -0.05254647135734558f}),
            // SILK mediumband in 60 ms packets.
            new OpusFixture("silk-mb-mono-60ms.opus", 1, 144000,
                    "f675db1f0b40cc04bc0df2ac2acf7eb08e6fed2c3f25b566d1c0ea959bd31ee5",
                    new int[]{48252, 0, 24417, 0, 8, 0, 96105, 0, 72531, 0, 120457, 0, 143998, 0, 87081, 0, 20571, 0,
                            48000, 0, 72000, 0, 143999, 0},
                    new float[]{0.221466064453125f, -0.081085205078125f, 0.244293212890625f, -0.086700439453125f,
                            -0.334136962890625f, 0.28631591796875f, 0.170928955078125f, 0.010894775390625f,
                            0.30694580078125f, -0.18524169921875f, 0.005035400390625f, 0.2015380859375f}),
            // SILK narrowband, 8 kbit/s.
            new OpusFixture("silk-nb-mono.opus", 1, 144000,
                    "fecb1aca0bb67bf5df386a49930aee8229af145cce953156c406c4873256a49b",
                    new int[]{24080, 0, 96159, 0, 48137, 0, 72217, 0, 457, 0, 120000, 0, 82461, 0, 139502, 0, 20571, 0,
                            48000, 0, 72000, 0, 143999, 0},
                    new float[]{0.357025146484375f, 0.156280517578125f, 0.188385009765625f, 0.114776611328125f,
                            -0.288116455078125f, 0.279296875f, -0.153778076171875f, 0.019744873046875f,
                            0.210418701171875f, -0.174591064453125f, 0.1678466796875f, 0.2430419921875f}),
            // SILK wideband stereo (mid/side with the stereo predictor) in 10 ms packets.
            new OpusFixture("silk-wb-stereo-10ms.opus", 2, 144000,
                    "66e46118ab4fe02a8463b8ea76172ddea76e0c5b3c0cf61338e8cf79b9cd1257",
                    new int[]{48355, 1, 96356, 1, 72302, 1, 120307, 1, 24285, 1, 318, 1, 143998, 1, 130016, 1, 20571, 0,
                            48000, 1, 72000, 1, 143999, 1},
                    new float[]{-0.152252197265625f, 0.159942626953125f, 0.061614990234375f, 0.245941162109375f,
                            -0.183380126953125f, -0.091583251953125f, 0.078033447265625f, -0.1749267578125f,
                            0.213958740234375f, -0.12078857421875f, -3.0517578125e-05f, 0.10577392578125f}),
            // ffmpeg's WebM: SeekHead, Void, Tags, Cues, CodecDelay, SeekPreRoll and a last BlockGroup whose
            // DiscardPadding trims 648 samples. Its hash is the scalar libopus decode of the same packets.
            new OpusFixture("stereo.webm", 2, 144000,
                    "8b5240194c09843557625ce515db8cde942bf45fa13a33bc62f30a4fd2fd4dbb",
                    new int[]{96061, 1, 72043, 1, 120041, 1, 24237, 1, 48247, 1, 62, 1, 135252, 1, 86867, 1, 20571, 0,
                            48000, 1, 72000, 1, 143999, 1},
                    new float[]{-0.0018421411514282227f, 0.19991904497146606f, 0.7063049077987671f,
                            -0.14696043729782104f, 0.40333259105682373f, 0.4492643475532532f, -0.09473514556884766f,
                            -0.2938964068889618f, 0.05529869720339775f, 0.07951806485652924f, -0.391190767288208f,
                            -0.2513783276081085f}),
            // 5.1 in channel mapping family 1: four streams, two of them coupled, in Vorbis channel order.
            new OpusFixture("surround51.opus", 6, 96000,
                    "7bb152807bedf5fe25de51fa3d53c802309da4468cc91c657230c3a1cc40dcf4",
                    new int[]{72364, 2, 48078, 2, 24125, 2, 92610, 2, 269, 2, 80817, 2, 14873, 4, 8727, 4, 13714, 3,
                            32000, 5, 48000, 5, 95999, 4},
                    new float[]{0.6202795505523682f, 0.4562147855758667f, -0.4580024480819702f, 0.8098908066749573f,
                            -0.09757207334041595f, 0.15169760584831238f, -0.011432137340307236f, -0.009787138551473618f,
                            0.336247980594635f, 0.007577305659651756f, -0.004914248827844858f, -0.00357082299888134f}));

    private static Map<String, byte[]> loadFixtures(Path dir) throws IOException {
        Map<String, byte[]> map = new LinkedHashMap<>();
        for (OpusFixture f : FIXTURES) {
            map.put(f.file(), Files.readAllBytes(dir.resolve(f.file())));
        }
        return map;
    }

    private static Opener opener(String file) {
        return file.endsWith(".webm") ? WEBM : OGG;
    }

    private static void fixtures(Map<String, byte[]> fixtures) throws Exception {
        for (OpusFixture f : FIXTURES) {
            String name = f.file();
            Decoded d = within(20, name, () -> decode(opener(name), fixtures.get(name), true));
            check(d.sampleRate == 48000 && d.channels == f.channels(),
                    name + ": " + d.sampleRate + " Hz x" + d.channels + ", expected 48000 x" + f.channels());
            check(d.frames == f.frames(), name + ": " + d.frames + " frames like ffmpeg's " + f.frames());
            check(d.hint == -1, name + ": no frame count hint, since the container has none up front: " + d.hint);
            check(d.sha256.equals(f.sha256()), name + ": bit-exact with scalar libopus (sha256 " + d.sha256 + ")");
            check(d.closed, name + ": closing the stream closes the input");
            float worst = 0;
            int[] p = f.probes();
            for (int k = 0; k < f.values().length; k++) {
                int index = p[2 * k];
                int channel = p[2 * k + 1];
                float got = index < d.frames ? d.pcm[channel][index] : Float.NaN;
                worst = Math.max(worst, Math.abs(got - f.values()[k]));
            }
            check(worst <= PROBE_TOLERANCE, name + ": samples match libopus at the probes (worst " + worst + ")");
            Decoded plain = decode(opener(name), fixtures.get(name), false);
            check(plain.sha256.equals(d.sha256), name + ": the same samples whatever sizes the input arrives in");
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Opus packets, read and written by this check's own code rather than the decoder's

    /** Samples in a packet by its TOC (RFC 6716 section 3.1), or -1 for a packet with no valid TOC sequence. */
    private static int opusSamples(byte[] p) {
        if (p.length < 1) {
            return -1;
        }
        int toc = p[0] & 0xFF;
        int config = toc >> 3;
        int perFrame;
        if (config < 12) {
            perFrame = new int[]{480, 960, 1920, 2880}[config & 3];
        } else if (config < 16) {
            perFrame = (config & 1) == 0 ? 480 : 960;
        } else {
            perFrame = 120 << (config & 3);
        }
        int count = switch (toc & 3) {
            case 0 -> 1;
            case 1, 2 -> 2;
            default -> p.length < 2 ? -1 : p[1] & 0x3F;
        };
        return count <= 0 || count * perFrame > 5760 ? -1 : count * perFrame;
    }

    /** An Ogg Opus file taken apart: its identification header, comment header and audio packets. */
    private record OggParts(byte[] head, byte[] tags, List<byte[]> packets) {
        int preSkip() {
            return (head[10] & 0xFF) | (head[11] & 0xFF) << 8;
        }

        int channels() {
            return head[9] & 0xFF;
        }

        long total() {
            long t = 0;
            for (byte[] p : packets) {
                t += Math.max(0, opusSamples(p));
            }
            return t;
        }
    }

    /** The packets of the first logical stream of an Ogg file, by its pages' lacing values. */
    private static OggParts oggParts(byte[] file) {
        List<byte[]> packets = new ArrayList<>();
        ByteArrayOutputStream packet = new ByteArrayOutputStream();
        int pos = 0;
        int serial = -1;
        while (pos + 27 <= file.length) {
            int segments = file[pos + 26] & 0xFF;
            int pageSerial = (file[pos + 14] & 0xFF) | (file[pos + 15] & 0xFF) << 8 | (file[pos + 16] & 0xFF) << 16
                    | (file[pos + 17] & 0xFF) << 24;
            if (serial == -1) {
                serial = pageSerial;
            }
            int body = pos + 27 + segments;
            for (int i = 0; i < segments; i++) {
                int lace = file[pos + 27 + i] & 0xFF;
                if (pageSerial == serial) {
                    packet.write(file, body, lace);
                    if (lace < 255) {
                        packets.add(packet.toByteArray());
                        packet.reset();
                    }
                }
                body += lace;
            }
            pos = body;
        }
        return new OggParts(packets.get(0), packets.get(1), packets.subList(2, packets.size()));
    }

    private static final int[] CRC_TABLE = new int[256];

    static {
        for (int i = 0; i < 256; i++) {
            int r = i << 24;
            for (int j = 0; j < 8; j++) {
                r = (r & 0x80000000) != 0 ? (r << 1) ^ 0x04c11db7 : r << 1;
            }
            CRC_TABLE[i] = r;
        }
    }

    /** Writes Ogg pages the way libogg lays them out, with a few levers for the cases the readers must follow. */
    private static final class OggWriter {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final int serial;
        int sequence;

        OggWriter(int serial) {
            this.serial = serial;
        }

        void page(int flags, long granule, List<Integer> lacing, byte[] body) {
            byte[] page = new byte[27 + lacing.size() + body.length];
            page[0] = 'O';
            page[1] = 'g';
            page[2] = 'g';
            page[3] = 'S';
            page[5] = (byte) flags;
            for (int i = 0; i < 8; i++) {
                page[6 + i] = (byte) (granule >> (8 * i));
            }
            for (int i = 0; i < 4; i++) {
                page[14 + i] = (byte) (serial >> (8 * i));
                page[18 + i] = (byte) (sequence >> (8 * i));
            }
            page[26] = (byte) lacing.size();
            for (int i = 0; i < lacing.size(); i++) {
                page[27 + i] = (byte) (int) lacing.get(i);
            }
            System.arraycopy(body, 0, page, 27 + lacing.size(), body.length);
            int crc = 0;
            for (byte b : page) {
                crc = (crc << 8) ^ CRC_TABLE[((crc >>> 24) ^ b) & 0xFF];
            }
            for (int i = 0; i < 4; i++) {
                page[22 + i] = (byte) (crc >> (8 * i));
            }
            out.writeBytes(page);
            sequence++;
        }

        /** One packet alone on its page (the identification header), or split over pages when it is long. */
        void single(byte[] packet, int flags) {
            packets(List.of(packet), new int[]{0}, flags, 1, 255, 0, null, true, false);
        }

        /**
         * Packets on pages of at most {@code perPage} packets and {@code maxSegments} lacing values, the granule
         * position of each page counting the samples of the packets completed on it from {@code granuleStart}.
         *
         * @param eosGranule the end page's granule position instead of the count, or null
         * @param noEos no end-of-stream flag at all
         * @param emptyEos the end-of-stream flag on an extra page without packets, whose granule is eosGranule
         */
        void packets(List<byte[]> packets, int[] durations, int firstFlags, int perPage, int maxSegments,
                     long granuleStart, Long eosGranule, boolean noEos, boolean emptyEos) {
            List<Integer> lacing = new ArrayList<>();
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            long granule = granuleStart;
            boolean continued = false;
            boolean completedHere = false;
            int onPage = 0;
            int flags = firstFlags;
            for (int k = 0; k < packets.size(); k++) {
                byte[] p = packets.get(k);
                int left = p.length;
                int at = 0;
                while (true) {
                    int lace = Math.min(255, left);
                    lacing.add(lace);
                    body.write(p, at, lace);
                    at += lace;
                    left -= lace;
                    boolean done = lace < 255;
                    if (done) {
                        granule += durations[k];
                        completedHere = true;
                        onPage++;
                    }
                    boolean last = done && k == packets.size() - 1;
                    if (lacing.size() == maxSegments || (done && onPage == perPage) || last) {
                        boolean end = last && !noEos && !emptyEos;
                        long g = completedHere ? (end && eosGranule != null ? eosGranule : granule) : -1L;
                        page(flags | (continued ? 1 : 0) | (end ? 4 : 0), g, lacing, body.toByteArray());
                        flags = 0;
                        continued = !done;
                        completedHere = false;
                        onPage = 0;
                        lacing.clear();
                        body.reset();
                    }
                    if (done) {
                        break;
                    }
                }
            }
            if (emptyEos) {
                page(4, eosGranule != null ? eosGranule : granule, List.of(), new byte[0]);
            }
        }
    }

    /** Builds an Ogg Opus file around packets, with the given identification header. */
    private static byte[] ogg(byte[] head, byte[] tags, List<byte[]> packets, int perPage, int maxSegments,
                              long granuleStart, Long eosGranule, boolean noEos, boolean emptyEos) {
        OggWriter w = new OggWriter(0x0B10C);
        w.single(head, 2);
        w.packets(List.of(tags), new int[]{0}, 0, 1, 255, 0, null, true, false);
        w.packets(packets, durations(packets), 0, perPage, maxSegments, granuleStart, eosGranule, noEos, emptyEos);
        return w.out.toByteArray();
    }

    private static byte[] ogg(OggParts parts, int perPage) {
        return ogg(parts.head(), parts.tags(), parts.packets(), perPage, 255, 0, null, false, false);
    }

    private static int[] durations(List<byte[]> packets) {
        int[] d = new int[packets.size()];
        for (int i = 0; i < d.length; i++) {
            d[i] = Math.max(0, opusSamples(packets.get(i)));
        }
        return d;
    }

    /** A copy of an identification header with another pre-skip, output gain or mapping family. */
    private static byte[] withHead(byte[] head, Integer preSkip, Integer gain) {
        byte[] h = head.clone();
        if (preSkip != null) {
            h[10] = (byte) (int) preSkip;
            h[11] = (byte) (preSkip >> 8);
        }
        if (gain != null) {
            h[16] = (byte) (int) gain;
            h[17] = (byte) (gain >> 8);
        }
        return h;
    }

    /** The decoder's whole output, without pre-skip or end trimming, to compare trimmed decodes against. */
    private static Decoded raw(OggParts parts) throws Exception {
        long total = parts.total();
        byte[] file = ogg(withHead(parts.head(), 0, null), parts.tags(), parts.packets(), 10, 255, 0, total, false,
                false);
        Decoded d = within(20, "raw", () -> decode(OGG, file, false));
        check(d.frames == total, "the untrimmed decode holds every sample of the packets: " + d.frames + " of " + total);
        return d;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Speed and memory: long songs generated on the fly from a fixture's packets

    /** An Ogg Opus stream of a fixture's packets played round and round, made page by page as it is read. */
    private static final class LoopedOgg extends InputStream {
        private final OggParts parts;
        private final int rounds;
        private final int[] durations;
        private final OggWriter writer = new OggWriter(0x5EED);
        private byte[] buffer = new byte[0];
        private int pos;
        private int round = -1;
        private long granule;

        LoopedOgg(OggParts parts, int rounds) {
            this.parts = parts;
            this.rounds = rounds;
            this.durations = durations(parts.packets());
        }

        private boolean fill() {
            if (pos < buffer.length) {
                return true;
            }
            writer.out.reset();
            if (round == -1) {
                writer.single(parts.head(), 2);
                writer.packets(List.of(parts.tags()), new int[]{0}, 0, 1, 255, 0, null, true, false);
            } else if (round < rounds) {
                boolean last = round == rounds - 1;
                writer.packets(parts.packets(), durations, 0, 10, 255, granule, null, !last, false);
                for (int d : durations) {
                    granule += d;
                }
            } else {
                return false;
            }
            round++;
            buffer = writer.out.toByteArray();
            pos = 0;
            return true;
        }

        @Override
        public int read() {
            return fill() ? buffer[pos++] & 0xFF : -1;
        }

        @Override
        public int read(byte[] b, int off, int len) {
            if (len == 0) {
                return 0;
            }
            if (!fill()) {
                return -1;
            }
            int n = Math.min(len, buffer.length - pos);
            System.arraycopy(buffer, pos, b, off, n);
            pos += n;
            return n;
        }
    }

    private static long drain(PcmStream s) throws IOException {
        float[][] buf = new float[s.channels()][4096];
        long frames = 0;
        int n;
        while ((n = s.read(buf, 0, 4096)) > 0) {
            frames += n;
        }
        return frames;
    }

    private static void speed(Map<String, byte[]> fixtures) throws Exception {
        // Four minutes of CELT stereo at 96 kbit/s, the costliest kind of song players bring: the fixture 80 times.
        OggParts parts = oggParts(fixtures.get("celt-stereo-96k.opus"));
        int rounds = 80;
        long expected = parts.total() * rounds - parts.preSkip();
        // The limit is 1.5 s of wall time for four minutes of stereo, on the Mac this was written on. That Mac is often
        // busy with a game while the checks run (load average 10-16), and then a decode both waits for a core and runs
        // on the slower of its two core types: one cold decode of a four-minute song took 463 ms at load 6.7 and
        // 1457 ms at load 14.8, and its thread's CPU time grew with it (458 and 1374 ms), so CPU time is no steadier a
        // measure. As in the AAC check, the cold decode, which happens once per JVM, is judged only while the load
        // average is below half the cores (or unknown, as on Windows); a warm decode is timed up to three times and
        // the best one counts; and a bound of 6 s catches a stall either way.
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        boolean cpuTimed = threads.isCurrentThreadCpuTimeSupported();
        int cores = Runtime.getRuntime().availableProcessors();
        double load = ManagementFactory.getOperatingSystemMXBean().getSystemLoadAverage();
        boolean quiet = load < cores / 2.0;
        System.out.printf(Locale.ROOT, "  load average %.1f on %d cores: the cold limit is %s%n", load, cores,
                quiet ? "judged" : "not judged");
        for (int pass = 0; pass < 2; pass++) {
            String when = pass == 0 ? "cold" : "warm";
            double best = Double.MAX_VALUE;
            for (int run = 0; run < (pass == 0 ? 1 : 3) && best >= 1500; run++) {
                long start = System.nanoTime();
                long[] result = within(30, "four-minute Opus, " + when, () -> {
                    long cpu = cpuTimed ? threads.getCurrentThreadCpuTime() : 0;
                    try (PcmStream s = OpusDecoder.open(new LoopedOgg(parts, rounds))) {
                        long frames = drain(s);
                        return new long[]{frames, cpuTimed ? threads.getCurrentThreadCpuTime() - cpu : -1};
                    }
                });
                double ms = (System.nanoTime() - start) / 1e6;
                System.out.printf(Locale.ROOT, "  a %.0f s stereo Ogg Opus song decoded in %.0f ms (%s of CPU), %s%n",
                        result[0] / 48000.0, ms, result[1] >= 0 ? Math.round(result[1] / 1e6) + " ms" : "unknown",
                        when);
                check(result[0] == expected, "the four-minute song decodes whole: " + result[0] + " of " + expected);
                check(ms < 6000, "a four-minute stereo Opus song decodes without stalling (took " + Math.round(ms)
                        + " ms, " + when + ", load average " + load + ")");
                best = Math.min(best, ms);
            }
            if (pass == 1 || quiet) {
                check(best < 1500, "a four-minute stereo Opus song decodes in under 1.5 s (took " + Math.round(best)
                        + " ms, " + when + ", load average " + load + ")");
            }
        }
    }

    private static long liveHeap() {
        for (int i = 0; i < 2; i++) {
            System.gc();
        }
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    private static void memory(Map<String, byte[]> fixtures) throws Exception {
        // Fifteen minutes: the decoder holds one packet and one 120 ms block of samples at a time, so what it keeps
        // alive must not grow with the song. The heap is measured after collecting, once before the stream is opened
        // and every two minutes of audio while it is open; the largest difference is what the open stream holds.
        // (Measured against the heap after closing instead, the difference drowned in the collector's noise.)
        OggParts parts = oggParts(fixtures.get("celt-stereo-96k.opus"));
        int rounds = 300;
        long[] result = within(60, "fifteen-minute Opus", () -> {
            long frames = 0;
            float[][] buf = new float[2][4096];
            long before = liveHeap();
            long peak = before;
            try (PcmStream s = OpusDecoder.open(new LoopedOgg(parts, rounds))) {
                long nextProbe = 0;
                int n;
                while ((n = s.read(buf, 0, 4096)) > 0) {
                    frames += n;
                    if (frames >= nextProbe) {
                        peak = Math.max(peak, liveHeap());
                        nextProbe = frames + 48000L * 120;
                    }
                }
            }
            return new long[]{frames, peak - before};
        });
        System.out.printf(java.util.Locale.ROOT, "  a %.0f s song: the open stream held %.2f MB of heap at most%n",
                result[0] / 48000.0, result[1] / 1e6);
        check(result[0] == parts.total() * rounds - parts.preSkip(), "the fifteen-minute song decodes whole");
        check(result[1] < 32L << 20, "decoding a fifteen-minute song keeps under 32 MB alive: " + result[1]);
        bigPackets(parts);
    }

    /** A one-frame code 0 packet as a code 3 packet of {@code size} bytes, the rest padding (RFC 6716 3.2.5). */
    private static byte[] padded(byte[] packet, int size) {
        byte[] frame = Arrays.copyOfRange(packet, 1, packet.length);
        // Everything after the frame count byte that is not the frame: the padding length bytes and the padding.
        int pad = size - 2 - frame.length;
        int k = 0;
        while (pad - (k + 1) - 254 * k >= 255) {
            k++;
        }
        int last = pad - (k + 1) - 254 * k;
        ByteArrayOutputStream out = new ByteArrayOutputStream(size);
        out.write((packet[0] & 0xFC) | 3);
        out.write(0x41);
        for (int i = 0; i < k; i++) {
            out.write(255);
        }
        out.write(last);
        out.writeBytes(frame);
        out.writeBytes(new byte[254 * k + last]);
        return out.toByteArray();
    }

    private static void bigPackets(OggParts parts) throws Exception {
        // A valid but hostile Ogg Opus file: a page of 255 one-byte packets, so the reader keeps 255 packet buffers,
        // then 24 packets padded to 512 kB each, which span pages. The reader once grew a different pooled buffer
        // for each big packet and kept them all (a review's 38 MB file of such packets held 81 MB and ran out of a
        // 48 MB heap); what the open stream holds must not grow with them.
        byte[] frame = parts.packets().get(10);
        check((frame[0] & 3) == 0 && opusSamples(frame) == 960, "the big packets are made from a 20 ms code 0 packet");
        List<byte[]> tiny = new ArrayList<>();
        for (int i = 0; i < 255; i++) {
            tiny.add(new byte[]{frame[0]});
        }
        List<byte[]> big = new ArrayList<>();
        for (int i = 0; i < 24; i++) {
            big.add(padded(frame, 512 * 1024));
        }
        check(big.get(0).length == 512 * 1024 && opusSamples(big.get(0)) == 960, "a padded packet is 512 kB of 20 ms");
        OggWriter w = new OggWriter(0xB16);
        w.single(parts.head(), 2);
        w.packets(List.of(parts.tags()), new int[]{0}, 0, 1, 255, 0, null, true, false);
        w.packets(tiny, durations(tiny), 0, 255, 255, 0, null, true, false);
        w.packets(big, durations(big), 0, 1, 255, 255L * 960, null, false, false);
        byte[] file = w.out.toByteArray();
        dump("big-packets.opus", file);
        long[] result = within(60, "big packets", () -> {
            long before = liveHeap();
            long frames;
            long held;
            try (PcmStream s = OpusDecoder.open(new ByteArrayInputStream(file))) {
                frames = drain(s);
                held = liveHeap() - before;
            }
            return new long[]{frames, held};
        });
        System.out.printf(java.util.Locale.ROOT, "  %d packets of 512 kB (a %.1f MB file): the open stream held %.2f MB"
                + " after them%n", big.size(), file.length / 1e6, result[1] / 1e6);
        check(result[0] == (tiny.size() + big.size()) * 960L - parts.preSkip(), "the big-packet file decodes whole: "
                + result[0]);
        check(result[1] < 4L << 20, "24 big packets leave no big buffers behind (held " + result[1] + " bytes)");
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Ogg

    private static void oggLayouts(Map<String, byte[]> fixtures) throws Exception {
        for (String name : List.of("celt-stereo-96k.opus", "mode-switch.opus", "surround51.opus")) {
            byte[] file = fixtures.get(name);
            OggParts parts = oggParts(file);
            Decoded base = decode(OGG, file, false);
            Map<String, byte[]> layouts = new LinkedHashMap<>();
            // The fixture's own end trimming (every encoder pads the last packet), kept in every layout.
            long eos = lastGranule(file);
            long trim = parts.total() - eos;
            layouts.put("one packet per page",
                    ogg(parts.head(), parts.tags(), parts.packets(), 1, 255, 0, eos, false, false));
            layouts.put("pages of 255 lacing values",
                    ogg(parts.head(), parts.tags(), parts.packets(), 1000, 255, 0, eos, false, false));
            layouts.put("packets split over pages of three lacing values",
                    ogg(parts.head(), parts.tags(), parts.packets(), 1000, 3, 0, eos, false, false));
            // A comment header of 300 kB (cover art) over many pages: skipped, never kept.
            byte[] bigTags = cat(parts.tags(), new byte[300_000]);
            layouts.put("a comment header over many pages",
                    ogg(parts.head(), bigTags, parts.packets(), 10, 255, 0, eos, false, false));
            // A stream cropped from a longer one: every granule position is larger by a second (RFC 7845 4.5).
            layouts.put("a cropped stream", ogg(parts.head(), parts.tags(), parts.packets(), 10, 255, 48000,
                    eos + 48000, false, false));
            // Zero-length packets: malformed by RFC 7845 section 3, so discarded (RFC 6716 section 3.4). (ffmpeg 9
            // stops at the first one and loses the rest of the song.)
            List<byte[]> withEmpty = new ArrayList<>(parts.packets());
            for (int at : new int[]{withEmpty.size() - 1, withEmpty.size() / 2, 1, 0}) {
                withEmpty.add(at, new byte[0]);
            }
            layouts.put("zero-length packets", ogg(parts.head(), parts.tags(), withEmpty, 10, 255, 0, eos, false,
                    false));
            // A foreign logical stream announced first and interleaved with the audio, as a video or skeleton is.
            layouts.put("a foreign stream around it", multiplexed(parts, eos));
            // A chained file: the second link is another song and is not part of this one.
            byte[] first = ogg(parts.head(), parts.tags(), parts.packets(), 10, 255, 0, eos, false, false);
            OggWriter second = new OggWriter(0x2222);
            second.single(parts.head(), 2);
            second.packets(List.of(parts.tags()), new int[]{0}, 0, 1, 255, 0, null, true, false);
            second.packets(parts.packets().subList(0, 30), durations(parts.packets().subList(0, 30)), 0, 10, 255, 0,
                    null, false, false);
            layouts.put("a chained file", cat(first, second.out.toByteArray()));
            for (Map.Entry<String, byte[]> e : layouts.entrySet()) {
                dump(name + "." + e.getKey().replace(' ', '-') + ".opus", e.getValue());
                Decoded d = within(20, name + " " + e.getKey(), () -> decode(OGG, e.getValue(), true));
                check(samePcm(base, d), name + " in " + e.getKey() + ": the same samples as the fixture ("
                        + d.frames + " frames of " + base.frames + ")");
            }
            // Without an end-of-stream page nothing says where the song ends, so the last packet plays whole.
            byte[] noEos = ogg(parts.head(), parts.tags(), parts.packets(), 10, 255, 0, null, true, false);
            Decoded open = decode(OGG, noEos, false);
            check(open.frames == base.frames + trim && samePcm(base, 0, open, 0, base.frames),
                    name + " without an end-of-stream flag: the fixture plus the " + trim + " samples it trimmed");
        }
    }

    /** The granule position of a file's last page. */
    private static long lastGranule(byte[] file) {
        List<Integer> pages = pageOffsets(file);
        int last = pages.get(pages.size() - 1);
        long g = 0;
        for (int i = 7; i >= 0; i--) {
            g = g << 8 | (file[last + 6 + i] & 0xFF);
        }
        return g;
    }

    /** The audio's pages with a foreign stream's beginning page first and its data pages in between. */
    private static byte[] multiplexed(OggParts parts, Long eos) {
        // The pages of the two streams alternate; the end page of the audio carries its trimming as in the fixture.
        OggWriter foreign = new OggWriter(0xF0F0);
        OggWriter audio = new OggWriter(0x0B10C);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        foreign.single("XYZ, a stream this check made up".getBytes(StandardCharsets.ISO_8859_1), 2);
        out.writeBytes(foreign.out.toByteArray());
        foreign.out.reset();
        audio.single(parts.head(), 2);
        out.writeBytes(audio.out.toByteArray());
        audio.out.reset();
        audio.packets(List.of(parts.tags()), new int[]{0}, 0, 1, 255, 0, null, true, false);
        out.writeBytes(audio.out.toByteArray());
        audio.out.reset();
        List<byte[]> packets = parts.packets();
        int[] d = durations(packets);
        long granule = 0;
        for (int i = 0; i < packets.size(); i += 10) {
            int end = Math.min(packets.size(), i + 10);
            boolean last = end == packets.size();
            audio.packets(packets.subList(i, end), Arrays.copyOfRange(d, i, end), 0, 10, 255, granule,
                    last ? eos : null, !last, false);
            for (int k = i; k < end; k++) {
                granule += d[k];
            }
            out.writeBytes(audio.out.toByteArray());
            audio.out.reset();
            foreign.packets(List.of(new byte[700 + i]), new int[]{0}, 0, 1, 255, i, null, i + 10 < packets.size(),
                    false);
            out.writeBytes(foreign.out.toByteArray());
            foreign.out.reset();
        }
        return out.toByteArray();
    }

    private static void oggTiming(Map<String, byte[]> fixtures) throws Exception {
        OggParts parts = oggParts(fixtures.get("hybrid-fec-stereo.opus"));
        Decoded raw = raw(parts);
        int preSkip = parts.preSkip();
        long total = parts.total();
        byte[] head = parts.head();
        // Pre-skip: exactly that many samples are dropped from the start, whatever it is (RFC 7845 section 4.2).
        for (int skip : new int[]{0, 1, 312, 959, 960, 3840, 6000}) {
            byte[] file = ogg(withHead(head, skip, null), parts.tags(), parts.packets(), 10, 255, 0, total, false,
                    false);
            Decoded d = decode(OGG, file, false);
            check(d.frames == total - skip && samePcm(raw, skip, d, 0, d.frames),
                    "a pre-skip of " + skip + " drops exactly that many samples: " + d.frames + " frames");
        }
        // End trimming by the last page's granule position (RFC 7845 section 4.4), from the previous page's.
        int lastPage = parts.packets().size() % 10 == 0 ? 10 : parts.packets().size() % 10;
        long beforeLast = total - lastPage * 960L;
        long[][] ends = {
                {total - 1, total - 1 - preSkip},
                {total - 700, total - 700 - preSkip},
                {beforeLast + 1, beforeLast + 1 - preSkip},
                // Smaller than the previous page's: the last page's packets are all cut, and nothing before them.
                {beforeLast - 5000, beforeLast - preSkip},
                // Larger than the samples there are: nothing is cut.
                {total + 5000, total - preSkip},
        };
        for (long[] end : ends) {
            byte[] file = ogg(head, parts.tags(), parts.packets(), 10, 255, 0, end[0], false, false);
            dump("end-granule-" + end[0] + ".opus", file);
            Decoded d = decode(OGG, file, false);
            check(d.frames == end[1] && samePcm(raw, preSkip, d, 0, d.frames),
                    "an end page with granule position " + end[0] + " of " + total + " keeps " + end[1] + " frames: "
                            + d.frames);
        }
        // An end-of-stream page without packets ends the stream and trims nothing, whatever its granule says.
        byte[] emptyEnd = ogg(head, parts.tags(), parts.packets(), 10, 255, 0, total - 3000, false, true);
        Decoded empty = decode(OGG, emptyEnd, false);
        check(empty.frames == total - preSkip, "an empty end page trims nothing: " + empty.frames);
        // The output gain of the header, in Q7.8 dB, is applied to every sample (RFC 7845 section 5.1).
        for (int gain : new int[]{-1536, 256 * 3, -32768}) {
            byte[] file = ogg(withHead(head, null, gain), parts.tags(), parts.packets(), 10, 255, 0, null, false,
                    false);
            Decoded d = decode(OGG, file, false);
            double factor = Math.pow(10, gain / (20.0 * 256));
            double worst = 0;
            for (int c = 0; c < d.channels; c++) {
                for (int i = 0; i < d.frames; i++) {
                    double expected = raw.pcm[c][preSkip + i] * factor;
                    worst = Math.max(worst, Math.abs(d.pcm[c][i] - expected) / (1e-9 + Math.abs(expected) + 1e-3));
                }
            }
            check(d.frames == total - preSkip && worst < 1e-5,
                    "an output gain of " + gain / 256.0 + " dB scales every sample (worst relative error " + worst + ")");
        }
        // A stream joined mid-broadcast: its first audio page continues a packet whose start was never seen, which
        // RFC 7845 section 3 says not to decode; the rest plays.
        OggWriter w = new OggWriter(0x1001);
        w.single(head, 2);
        w.packets(List.of(parts.tags()), new int[]{0}, 0, 1, 255, 0, null, true, false);
        byte[] tail = new byte[300];
        new Random(7).nextBytes(tail);
        List<byte[]> rest = parts.packets().subList(1, parts.packets().size());
        ByteArrayOutputStream joined = new ByteArrayOutputStream();
        joined.writeBytes(w.out.toByteArray());
        w.out.reset();
        List<byte[]> withTail = new ArrayList<>();
        withTail.add(tail);
        withTail.addAll(rest);
        w.packets(withTail, durations(withTail), 1, 10, 255, 0, null, false, false);
        joined.writeBytes(w.out.toByteArray());
        Decoded live = decode(OGG, joined.toByteArray(), false);
        long restTotal = total - durations(parts.packets())[0];
        check(live.frames == restTotal - preSkip, "a stream joined mid-packet skips that packet's piece: " + live.frames);
    }

    private static void oggDamage(Map<String, byte[]> fixtures) throws Exception {
        byte[] file = fixtures.get("celt-stereo-96k.opus");
        check(refusal(OGG, new byte[0], "empty") != null, "an empty file is an AudioFormatException");
        Random random = new Random(42);
        byte[] noise = new byte[40000];
        random.nextBytes(noise);
        check(refusal(OGG, noise, "noise") != null, "random bytes are an AudioFormatException");
        check(refusal(OGG, cat("OggS".getBytes(StandardCharsets.US_ASCII), new byte[40]), "OggS and zeros") != null,
                "an Ogg magic followed by nothing sensible is an AudioFormatException");
        // A flipped byte in a page's body: the page's checksum no longer matches.
        List<Integer> pages = pageOffsets(file);
        int drop = pages.size() / 2;
        byte[] flipped = file.clone();
        flipped[pages.get(drop) + 27 + (file[pages.get(drop) + 26] & 0xFF) + 5] ^= 0x10;
        String crc = refusal(OGG, flipped, "flipped");
        check(crc != null && crc.contains("checksum"), "a flipped byte is caught by the page checksum: " + crc);
        // A page missing from the middle: its sequence number gives it away.
        byte[] missing = cat(slice(file, 0, pages.get(drop)), slice(file, pages.get(drop + 1), file.length));
        String gap = refusal(OGG, missing, "missing page");
        check(gap != null && gap.contains("missing"), "a missing page is reported as one: " + gap);
        // Cut inside a page, the file is damaged; cut between pages, it simply ends (no end-of-stream flag).
        String cut = refusal(OGG, slice(file, 0, pages.get(drop) + 40), "cut in a page");
        check(cut != null && cut.contains("cut off"), "a file cut inside a page is damaged: " + cut);
        int boundary = drop;
        while (endsInPacket(file, pages.get(boundary - 1))) {
            boundary++;
        }
        int at = pages.get(boundary);
        Decoded clean = within(20, "cut between pages", () -> decode(OGG, slice(file, 0, at), false));
        check(clean.frames > 0, "a file cut between two pages decodes up to there: " + clean.frames);
        // A page that claims to continue a packet when the one before it ended, and the reverse.
        OggParts parts = oggParts(file);
        byte[] base = ogg(parts, 10);
        List<Integer> basePages = pageOffsets(base);
        byte[] bogus = base.clone();
        int page = basePages.get(5);
        bogus[page + 5] |= 1;
        rewriteCrc(bogus, page);
        String cont = refusal(OGG, bogus, "bogus continuation");
        check(cont != null && cont.contains("never started"), "a page continuing a packet never started: " + cont);
        // The stream ends in the middle of a packet: its last page ends in a lacing value of 255 and nothing follows.
        byte[] whole = ogg(parts.head(), parts.tags(), List.of(parts.packets().get(0), new byte[600]), 10, 2, 0, null,
                true, false);
        List<Integer> wholePages = pageOffsets(whole);
        byte[] open = slice(whole, 0, wholePages.get(wholePages.size() - 1));
        String partial = refusal(OGG, open, "ends in a packet");
        check(partial != null, "a stream that ends in the middle of a packet is damaged: " + partial);
        // A packet the codec refuses (code 1 with an even length: RFC 6716 rule R3) inside a valid page.
        List<byte[]> bad = new ArrayList<>(parts.packets());
        bad.set(20, new byte[]{(byte) ((bad.get(20)[0] & 0xFC) | 1), 1, 2, 3});
        String refused = refusal(OGG, ogg(parts.head(), parts.tags(), bad, 10, 255, 0, null, false, false), "bad packet");
        check(refused != null && refused.contains("invalid packet"), "a packet the decoder refuses is damage: "
                + refused);
        // Headers that are not Opus or are damaged.
        check(refusal(OGG, vorbisOgg(), "vorbis").contains("Vorbis"),
                "an Ogg Vorbis file is not taken for Opus, and the message says what it holds");
        byte[] noChannels = parts.head().clone();
        noChannels[9] = 0;
        check(refusal(OGG, ogg(noChannels, parts.tags(), parts.packets(), 10, 255, 0, null, false, false),
                "no channels") != null, "an identification header with no channels is damage");
        byte[] shortHead = slice(parts.head(), 0, 15);
        check(refusal(OGG, ogg(shortHead, parts.tags(), parts.packets(), 10, 255, 0, null, false, false),
                "short head") != null, "a cut-short identification header is damage");
        byte[] badTags = parts.tags().clone();
        badTags[0] = 'X';
        check(refusal(OGG, ogg(parts.head(), badTags, parts.packets(), 10, 255, 0, null, false, false),
                "bad tags") != null, "a second header that is not OpusTags is damage");
        byte[] familyZeroThree = parts.head().clone();
        familyZeroThree[9] = 3;
        check(refusal(OGG, ogg(familyZeroThree, parts.tags(), parts.packets(), 10, 255, 0, null, false, false),
                "family 0 with 3 channels") != null, "channel mapping family 0 with three channels is damage");
    }

    /** Whether the page at {@code page} ends in the middle of a packet (its last lacing value is 255). */
    private static boolean endsInPacket(byte[] file, int page) {
        int segments = file[page + 26] & 0xFF;
        return segments > 0 && (file[page + 26 + segments] & 0xFF) == 255;
    }

    private static List<Integer> pageOffsets(byte[] file) {
        List<Integer> offsets = new ArrayList<>();
        int pos = 0;
        while (pos + 27 <= file.length) {
            offsets.add(pos);
            int segments = file[pos + 26] & 0xFF;
            int size = 0;
            for (int i = 0; i < segments; i++) {
                size += file[pos + 27 + i] & 0xFF;
            }
            pos += 27 + segments + size;
        }
        return offsets;
    }

    private static void rewriteCrc(byte[] file, int page) {
        int segments = file[page + 26] & 0xFF;
        int size = 0;
        for (int i = 0; i < segments; i++) {
            size += file[page + 27 + i] & 0xFF;
        }
        int end = page + 27 + segments + size;
        file[page + 22] = file[page + 23] = file[page + 24] = file[page + 25] = 0;
        int crc = 0;
        for (int i = page; i < end; i++) {
            crc = (crc << 8) ^ CRC_TABLE[((crc >>> 24) ^ file[i]) & 0xFF];
        }
        for (int i = 0; i < 4; i++) {
            file[page + 22 + i] = (byte) (crc >> (8 * i));
        }
    }

    /** The first page of an Ogg Vorbis file: enough for the Opus reader to know it is not Opus. */
    private static byte[] vorbisOgg() {
        byte[] id = cat(new byte[]{1, 'v', 'o', 'r', 'b', 'i', 's', 0, 0, 0, 0, 2, (byte) 0x44, (byte) 0xAC, 0, 0},
                new byte[14]);
        OggWriter w = new OggWriter(9);
        w.single(id, 2);
        return w.out.toByteArray();
    }

    // ---------------------------------------------------------------------------------------------------------------
    // WebM

    /** EBML elements with the shortest size field, as muxers write them, or eight-byte ones when asked. */
    private static final class Ebml {
        static boolean longSizes;

        static byte[] id(int id) {
            if (id >= 0x1000000) {
                return new byte[]{(byte) (id >> 24), (byte) (id >> 16), (byte) (id >> 8), (byte) id};
            }
            if (id >= 0x10000) {
                return new byte[]{(byte) (id >> 16), (byte) (id >> 8), (byte) id};
            }
            if (id >= 0x100) {
                return new byte[]{(byte) (id >> 8), (byte) id};
            }
            return new byte[]{(byte) id};
        }

        static byte[] size(long size) {
            int length = 1;
            while (!longSizes && length < 8 && size >= (1L << (7 * length)) - 1) {
                length++;
            }
            if (longSizes) {
                length = 8;
            }
            byte[] b = new byte[length];
            for (int i = 0; i < length; i++) {
                b[length - 1 - i] = (byte) (size >> (8 * i));
            }
            b[0] |= (byte) (0x80 >> (length - 1));
            return b;
        }

        static final byte[] UNKNOWN = {0x01, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF,
                (byte) 0xFF, (byte) 0xFF};

        static byte[] el(int id, byte[]... children) {
            byte[] body = cat(children);
            return cat(id(id), size(body.length), body);
        }

        static byte[] unknown(int id, byte[]... children) {
            return cat(id(id), UNKNOWN, cat(children));
        }

        static byte[] uint(int id, long v) {
            int n = 1;
            while (n < 8 && (v >>> (8 * n)) != 0) {
                n++;
            }
            byte[] d = new byte[n];
            for (int i = 0; i < n; i++) {
                d[i] = (byte) (v >> (8 * (n - 1 - i)));
            }
            return el(id, d);
        }

        static byte[] sint(int id, long v) {
            byte[] d = new byte[8];
            for (int i = 0; i < 8; i++) {
                d[i] = (byte) (v >> (8 * (7 - i)));
            }
            return el(id, d);
        }

        static byte[] str(int id, String s) {
            return el(id, s.getBytes(StandardCharsets.US_ASCII));
        }
    }

    /** How a WebM file is laid out around a list of Opus packets. */
    private static final class WebmSpec {
        String docType = "webm";
        String codec = "A_OPUS";
        byte[] codecPrivate;
        Long codecDelayNs;
        String lacing = "none";
        int perBlock = 1;
        boolean unknownSizes;
        boolean blockGroups;
        boolean videoFirst;
        boolean noAudioTrack;
        boolean encrypted;
        boolean compressed;
        int dropBlock = -1;
        long discardEndNs;
        long discardStartNs;
        /** A negative DiscardPadding of this many nanoseconds on the last block, cut from that block's start. */
        long discardLastStartNs;
        int clusterMs = 1000;
        /** Block timestamps moved by up to this many milliseconds either way, as a capture clock stamps them. */
        int jitterMs;
        /** The cluster whose timestamp is damaged, and by how many milliseconds it is too late. */
        int lateCluster = -1;
        int lateClusterMs;
    }

    private static byte[] block(int track, int timecode, int flags, List<byte[]> frames, String lacing) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write(0x80 | track);
        b.write((timecode >> 8) & 0xFF);
        b.write(timecode & 0xFF);
        int lace = switch (lacing) {
            case "xiph" -> 1;
            case "fixed" -> 2;
            case "ebml" -> 3;
            default -> 0;
        };
        b.write(flags | (lace << 1));
        if (lace != 0) {
            b.write(frames.size() - 1);
            if (lace == 1) {
                for (int i = 0; i < frames.size() - 1; i++) {
                    int s = frames.get(i).length;
                    while (s >= 255) {
                        b.write(255);
                        s -= 255;
                    }
                    b.write(s);
                }
            } else if (lace == 3) {
                long previous = 0;
                for (int i = 0; i < frames.size() - 1; i++) {
                    long s = frames.get(i).length;
                    long v = i == 0 ? s : s - previous + 8191;
                    b.write(0x40 | (int) (v >> 8));
                    b.write((int) v);
                    previous = s;
                }
            }
        }
        for (byte[] f : frames) {
            b.writeBytes(f);
        }
        return b.toByteArray();
    }

    /** A WebM file of the packets; block timestamps are whole milliseconds, cut down like ffmpeg's muxer rounds. */
    private static byte[] webm(byte[] head, List<byte[]> packets, WebmSpec s) {
        byte[] header = Ebml.el(0x1A45DFA3, Ebml.uint(0x4286, 1), Ebml.uint(0x42F7, 1), Ebml.uint(0x42F2, 4),
                Ebml.uint(0x42F3, 8), Ebml.str(0x4282, s.docType), Ebml.uint(0x4287, 4), Ebml.uint(0x4285, 2));
        byte[] info = Ebml.el(0x1549A966, Ebml.uint(0x2AD7B1, 1_000_000), Ebml.str(0x4D80, "check"));
        List<byte[]> entries = new ArrayList<>();
        int audioTrack = s.videoFirst ? 2 : 1;
        if (s.videoFirst) {
            entries.add(Ebml.el(0xAE, Ebml.uint(0xD7, 1), Ebml.uint(0x83, 1), Ebml.str(0x86, "V_VP9"),
                    Ebml.el(0xE0, Ebml.uint(0xB0, 64), Ebml.uint(0xBA, 64))));
        }
        if (!s.noAudioTrack) {
            List<byte[]> audio = new ArrayList<>(List.of(Ebml.uint(0xD7, audioTrack), Ebml.uint(0x83, 2),
                    Ebml.str(0x86, s.codec)));
            if (s.codecDelayNs != null) {
                audio.add(Ebml.uint(0x56AA, s.codecDelayNs));
            }
            audio.add(Ebml.uint(0x56BB, 80_000_000));
            audio.add(Ebml.el(0x63A2, s.codecPrivate != null ? s.codecPrivate : head));
            if (s.encrypted || s.compressed) {
                byte[] inner = s.encrypted ? Ebml.el(0x5035, Ebml.uint(0x47E1, 5)) : Ebml.el(0x5034,
                        Ebml.uint(0x4254, 3));
                audio.add(Ebml.el(0x6D80, Ebml.el(0x6240, Ebml.uint(0x5031, 0), inner)));
            }
            audio.add(Ebml.el(0xE1, Ebml.uint(0x9F, head[9] & 0xFF)));
            entries.add(Ebml.el(0xAE, audio.toArray(new byte[0][])));
        }
        byte[] tracks = Ebml.el(0x1654AE6B, entries.toArray(new byte[0][]));
        ByteArrayOutputStream segment = new ByteArrayOutputStream();
        segment.writeBytes(Ebml.el(0xEC, new byte[40]));
        segment.writeBytes(info);
        segment.writeBytes(tracks);
        long samples = 0;
        int i = 0;
        int blockIndex = 0;
        int clusterIndex = 0;
        Random jitter = new Random(s.jitterMs);
        while (i < packets.size()) {
            long clusterStart = samples / 48;
            ByteArrayOutputStream cluster = new ByteArrayOutputStream();
            cluster.writeBytes(Ebml.uint(0xE7, clusterStart + (clusterIndex == s.lateCluster ? s.lateClusterMs : 0)));
            clusterIndex++;
            while (i < packets.size() && samples / 48 - clusterStart < s.clusterMs) {
                List<byte[]> frames = packets.subList(i, Math.min(packets.size(), i + s.perBlock));
                int relative = (int) (samples / 48 - clusterStart);
                if (s.jitterMs > 0) {
                    relative += jitter.nextInt(2 * s.jitterMs + 1) - s.jitterMs;
                }
                long duration = 0;
                for (byte[] f : frames) {
                    duration += Math.max(0, opusSamples(f));
                }
                boolean first = i == 0;
                boolean last = i + s.perBlock >= packets.size();
                if (s.videoFirst && blockIndex % 3 == 0) {
                    cluster.writeBytes(Ebml.el(0xA3, block(1, relative, 0x80, List.of(new byte[400 + blockIndex]),
                            "none")));
                }
                if (blockIndex != s.dropBlock) {
                    // RFC 9559 section 10.3: a single frame is never laced; fixed-size lacing needs equal frames.
                    String lacing = frames.size() == 1 ? "none" : s.lacing.equals("none") ? "xiph" : s.lacing;
                    if (lacing.equals("fixed") && frames.stream().mapToInt(f -> f.length).distinct().count() > 1) {
                        lacing = "ebml";
                    }
                    byte[] b = block(audioTrack, relative, 0, frames, lacing);
                    boolean endPad = last && s.discardEndNs != 0;
                    boolean startPad = first && s.discardStartNs != 0;
                    boolean lastStartPad = last && s.discardLastStartNs != 0;
                    if (s.blockGroups || endPad || startPad || lastStartPad) {
                        List<byte[]> group = new ArrayList<>();
                        group.add(Ebml.el(0xA1, b));
                        if (endPad) {
                            group.add(Ebml.sint(0x75A2, s.discardEndNs));
                        }
                        if (startPad) {
                            group.add(Ebml.sint(0x75A2, -s.discardStartNs));
                        }
                        if (lastStartPad) {
                            group.add(Ebml.sint(0x75A2, -s.discardLastStartNs));
                        }
                        cluster.writeBytes(Ebml.el(0xA0, group.toArray(new byte[0][])));
                    } else {
                        b[3] |= (byte) 0x80;
                        cluster.writeBytes(Ebml.el(0xA3, b));
                    }
                }
                samples += duration;
                i += s.perBlock;
                blockIndex++;
            }
            segment.writeBytes(s.unknownSizes ? Ebml.unknown(0x1F43B675, cluster.toByteArray())
                    : Ebml.el(0x1F43B675, cluster.toByteArray()));
        }
        segment.writeBytes(Ebml.el(0x1C53BB6B, Ebml.el(0xBB, Ebml.uint(0xB3, 0))));
        byte[] body = segment.toByteArray();
        return cat(header, s.unknownSizes ? Ebml.unknown(0x18538067, body) : Ebml.el(0x18538067, body));
    }

    /** The WebM equivalent of an Ogg fixture: CodecDelay is the pre-skip, DiscardPadding the end trimming. */
    private static WebmSpec equivalent(OggParts parts, long frames) {
        WebmSpec s = new WebmSpec();
        s.codecDelayNs = parts.preSkip() * 1_000_000_000L / 48000;
        long trim = parts.total() - parts.preSkip() - frames;
        s.discardEndNs = (trim * 1_000_000_000L + 47999) / 48000;
        return s;
    }

    private static void webmLayouts(Map<String, byte[]> fixtures) throws Exception {
        for (String name : List.of("celt-stereo-96k.opus", "mode-switch.opus", "silk-mb-mono-60ms.opus",
                "surround51.opus")) {
            byte[] file = fixtures.get(name);
            OggParts parts = oggParts(file);
            Decoded base = decode(OGG, file, false);
            Map<String, WebmSpec> layouts = new LinkedHashMap<>();
            layouts.put("simple blocks", equivalent(parts, base.frames));
            WebmSpec matroska = equivalent(parts, base.frames);
            matroska.docType = "matroska";
            layouts.put("a matroska doctype", matroska);
            for (String lacing : List.of("xiph", "ebml", "fixed")) {
                WebmSpec laced = equivalent(parts, base.frames);
                laced.lacing = lacing;
                laced.perBlock = 4;
                layouts.put(lacing + " lacing", laced);
            }
            WebmSpec unknown = equivalent(parts, base.frames);
            unknown.unknownSizes = true;
            layouts.put("a segment and clusters of unknown size", unknown);
            WebmSpec groups = equivalent(parts, base.frames);
            groups.blockGroups = true;
            layouts.put("block groups", groups);
            WebmSpec video = equivalent(parts, base.frames);
            video.videoFirst = true;
            layouts.put("a video track first and its blocks in between", video);
            WebmSpec small = equivalent(parts, base.frames);
            small.clusterMs = 7;
            layouts.put("a cluster for every block", small);
            for (Map.Entry<String, WebmSpec> e : layouts.entrySet()) {
                byte[] webm = webm(parts.head(), parts.packets(), e.getValue());
                dump(name + "." + e.getKey().replace(' ', '-') + ".webm", webm);
                Decoded d = within(20, name + " " + e.getKey(), () -> decode(WEBM, webm, true));
                check(samePcm(base, d), name + " as WebM with " + e.getKey() + ": the same samples as the Ogg fixture ("
                        + d.frames + " frames of " + base.frames + ")");
            }
            Ebml.longSizes = true;
            byte[] longSizes = webm(parts.head(), parts.packets(), equivalent(parts, base.frames));
            Ebml.longSizes = false;
            Decoded d = decode(WEBM, longSizes, false);
            check(samePcm(base, d), name + " as WebM with eight-byte element sizes: the same samples");
        }
    }

    private static void webmTiming(Map<String, byte[]> fixtures) throws Exception {
        OggParts parts = oggParts(fixtures.get("celt-stereo-96k.opus"));
        Decoded raw = raw(parts);
        long total = parts.total();
        int preSkip = parts.preSkip();
        // Without CodecDelay, or with zero, the OpusHead pre-skip is dropped, as ffmpeg does.
        for (Long delay : new Long[]{null, 0L}) {
            WebmSpec s = new WebmSpec();
            s.codecDelayNs = delay;
            Decoded d = decode(WEBM, webm(parts.head(), parts.packets(), s), false);
            check(d.frames == total - preSkip && samePcm(raw, preSkip, d, 0, d.frames),
                    "CodecDelay " + delay + " falls back to the header's pre-skip: " + d.frames);
        }
        // CodecDelay rules when it is there, as in ffmpeg: 100 and 960 samples.
        for (int delay : new int[]{100, 960}) {
            WebmSpec s = new WebmSpec();
            s.codecDelayNs = (delay * 1_000_000_000L + 47999) / 48000;
            byte[] file = webm(parts.head(), parts.packets(), s);
            dump("codec-delay-" + delay + ".webm", file);
            Decoded d = decode(WEBM, file, false);
            check(d.frames == total - delay && samePcm(raw, delay, d, 0, d.frames),
                    "a CodecDelay of " + delay + " samples drops that many: " + d.frames);
        }
        // DiscardPadding on the last block cuts its end.
        WebmSpec end = new WebmSpec();
        end.codecDelayNs = 6_500_000L;
        end.discardEndNs = 10_000_000L;
        byte[] endFile = webm(parts.head(), parts.packets(), end);
        dump("discard-end-480.webm", endFile);
        Decoded endCut = decode(WEBM, endFile, false);
        check(endCut.frames == total - preSkip - 480 && samePcm(raw, preSkip, endCut, 0, endCut.frames),
                "DiscardPadding of 10 ms on the last block cuts 480 samples from the end: " + endCut.frames);
        // Negative DiscardPadding pads the block's start (RFC 9559 5.1.3.5.7). On the first block it covers the same
        // samples as the pre-skip, so the larger of the two is dropped. (ffmpeg 9.0.1 drops the first block's
        // DiscardPadding and applies only the CodecDelay there: 144 648 frames from this file, 168 more.)
        WebmSpec start = new WebmSpec();
        start.codecDelayNs = 6_500_000L;
        start.discardStartNs = 10_000_000L;
        byte[] startFile = webm(parts.head(), parts.packets(), start);
        dump("discard-start-480.webm", startFile);
        Decoded startCut = decode(WEBM, startFile, false);
        check(startCut.frames == total - 480 && samePcm(raw, 480, startCut, 0, startCut.frames),
                "negative DiscardPadding of 10 ms drops the first 480 samples, the pre-skip within them: "
                        + startCut.frames);
        // On any later block a negative DiscardPadding is cut from that block's start, as ffmpeg 9.0.1 does too.
        WebmSpec lastStart = new WebmSpec();
        lastStart.codecDelayNs = 6_500_000L;
        lastStart.discardLastStartNs = 5_000_000L;
        byte[] lastStartFile = webm(parts.head(), parts.packets(), lastStart);
        dump("discard-last-start-240.webm", lastStartFile);
        Decoded lastCut = decode(WEBM, lastStartFile, false);
        int lastPacket = Math.max(0, opusSamples(parts.packets().get(parts.packets().size() - 1)));
        int beforeLast = (int) (total - lastPacket);
        check(lastCut.frames == total - preSkip - 240 && samePcm(raw, preSkip, lastCut, 0, beforeLast - preSkip)
                        && samePcm(raw, beforeLast + 240, lastCut, beforeLast - preSkip, lastPacket - 240),
                "negative DiscardPadding of 5 ms on the last block drops that block's first 240 samples: "
                        + lastCut.frames);
        // A lost block: the blocks around it play back to back, as in ffmpeg, so the song is one block shorter and
        // nothing is inserted in its place (WebmDecoder says why timestamps are not followed).
        WebmSpec lost = new WebmSpec();
        lost.codecDelayNs = 6_500_000L;
        lost.dropBlock = 50;
        byte[] lostFile = webm(parts.head(), parts.packets(), lost);
        dump("lost-block-50.webm", lostFile);
        Decoded gap = decode(WEBM, lostFile, false);
        check(gap.frames == total - preSkip - 960, "a lost 20 ms block leaves the song 960 samples shorter, as in"
                + " ffmpeg: " + gap.frames);
        check(samePcm(raw, preSkip, gap, 0, 50 * 960 - preSkip), "and everything before the lost block is untouched");
        // Timestamps that wobble a tick or two either way, as a muxer stamping audio from a capture clock writes
        // them, and a cluster whose timestamp is 3 s too late change nothing: the song is its packets, sample for
        // sample. (Filling apparent gaps made a 4-minute song with 1 ms of wobble 3.2 s longer than ffmpeg's.)
        WebmSpec steady = new WebmSpec();
        steady.codecDelayNs = 6_500_000L;
        Decoded steadyDecode = decode(WEBM, webm(parts.head(), parts.packets(), steady), false);
        check(steadyDecode.frames == total - preSkip && samePcm(raw, preSkip, steadyDecode, 0, steadyDecode.frames),
                "exact block timestamps: every sample after the pre-skip");
        for (int wobble : new int[]{1, 2}) {
            WebmSpec wobbly = new WebmSpec();
            wobbly.codecDelayNs = 6_500_000L;
            wobbly.jitterMs = wobble;
            byte[] wobblyFile = webm(parts.head(), parts.packets(), wobbly);
            dump("jitter-" + wobble + "ms.webm", wobblyFile);
            Decoded d = decode(WEBM, wobblyFile, true);
            check(samePcm(steadyDecode, d), "block timestamps off by up to " + wobble + " ms either way change"
                    + " nothing: " + d.frames + " frames");
        }
        WebmSpec late = new WebmSpec();
        late.codecDelayNs = 6_500_000L;
        late.lateCluster = 1;
        late.lateClusterMs = 3000;
        byte[] lateFile = webm(parts.head(), parts.packets(), late);
        dump("late-cluster-3s.webm", lateFile);
        Decoded lateDecode = decode(WEBM, lateFile, false);
        check(samePcm(steadyDecode, lateDecode), "a cluster stamped 3 s late changes nothing: " + lateDecode.frames
                + " frames");
        // 2.5 ms frames in their own blocks: millisecond timestamps are off by up to one, which changes nothing...
        OggParts small = oggParts(fixtures.get("celt-mono-2.5ms.opus"));
        Decoded smallBase = decode(OGG, fixtures.get("celt-mono-2.5ms.opus"), false);
        Decoded jitter = decode(WEBM, webm(small.head(), small.packets(), equivalent(small, smallBase.frames)), false);
        check(samePcm(smallBase, jitter), "2.5 ms blocks with millisecond timestamps play as the Ogg file does");
        // ...and a lost 2.5 ms block is one 2.5 ms block less, wherever it is.
        for (int dropped : new int[]{40, 41, 42, 43}) {
            WebmSpec one = equivalent(small, smallBase.frames);
            one.dropBlock = dropped;
            Decoded d = decode(WEBM, webm(small.head(), small.packets(), one), false);
            check(d.frames == smallBase.frames - 120 && samePcm(smallBase, 0, d, 0, dropped * 120 - small.preSkip()),
                    "a lost 2.5 ms block " + dropped + " leaves 120 samples out and the rest back to back: "
                            + d.frames);
        }
    }

    private static void webmDamage(Map<String, byte[]> fixtures) throws Exception {
        byte[] file = fixtures.get("stereo.webm");
        check(refusal(WEBM, new byte[0], "empty webm") != null, "an empty file is an AudioFormatException");
        check(refusal(WEBM, cat(new byte[]{0x1A, 0x45, (byte) 0xDF, (byte) 0xA3}, new byte[30]), "ebml zeros") != null,
                "an EBML magic followed by zeros is an AudioFormatException");
        check(refusal(WEBM, fixtures.get("celt-stereo-96k.opus"), "ogg as webm") != null,
                "an Ogg file given to the WebM reader is an AudioFormatException");
        String cut = refusal(WEBM, slice(file, 0, file.length / 2), "cut webm");
        check(cut != null && cut.contains("cut off"), "a WebM file cut in a block is damaged: " + cut);
        OggParts parts = oggParts(fixtures.get("celt-stereo-96k.opus"));
        WebmSpec none = new WebmSpec();
        none.noAudioTrack = true;
        none.videoFirst = true;
        String noAudio = refusal(WEBM, webm(parts.head(), parts.packets(), none), "no audio track");
        check(noAudio != null && noAudio.contains("no audio track"), "a WebM video without sound says so: " + noAudio);
        // A block that claims 15 MB in a small file: refused when the bytes run out, never allocated up front.
        WebmSpec unknown = new WebmSpec();
        unknown.unknownSizes = true;
        byte[] start = webm(parts.head(), parts.packets().subList(0, 3), unknown);
        int cues = indexOf(start, new byte[]{0x1C, 0x53, (byte) 0xBB, 0x6B}, 0);
        byte[] huge = cat(slice(start, 0, cues), new byte[]{(byte) 0xA3, 0x10, (byte) 0xF0, 0, 0, (byte) 0x81, 0, 0,
                (byte) 0x80}, new byte[1000]);
        String claimed = refusal(WEBM, huge, "huge block");
        check(claimed != null && claimed.contains("cut off"), "a block claiming 15 MB in a small file is cut off: "
                + claimed);
        // A file cut inside an element the reader skips (here a video block) is cut off too, not a shorter song.
        WebmSpec video = equivalent(parts, 144000);
        video.videoFirst = true;
        byte[] withVideo = webm(parts.head(), parts.packets(), video);
        int videoBlock = indexOf(withVideo, new byte[64], withVideo.length / 2);
        String skipped = refusal(WEBM, slice(withVideo, 0, videoBlock + 32), "cut in a video block");
        check(skipped != null && skipped.contains("cut off"), "a cut inside a skipped video block is reported: "
                + skipped);
    }

    private static int indexOf(byte[] data, byte[] pattern, int from) {
        outer:
        for (int i = from; i + pattern.length <= data.length; i++) {
            for (int k = 0; k < pattern.length; k++) {
                if (data[i + k] != pattern[k]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Variants this package does not play: refused as unsupported, with a name the player can be told

    private static void unsupported(Map<String, byte[]> fixtures) throws Exception {
        OggParts stereo = oggParts(fixtures.get("celt-stereo-96k.opus"));
        OggParts surround = oggParts(fixtures.get("surround51.opus"));
        byte[] ambisonic = surround.head().clone();
        ambisonic[18] = 2;
        check("Ambisonic Opus".equals(unsupportedName(OGG, ogg(ambisonic, surround.tags(), surround.packets(), 10, 255,
                0, null, false, false))), "an ambisonic Opus stream (family 2) is an unsupported variant");
        byte[] family255 = surround.head().clone();
        family255[18] = (byte) 255;
        check("Opus (channel mapping 255)".equals(unsupportedName(OGG, ogg(family255, surround.tags(),
                        surround.packets(), 10, 255, 0, null, false, false))),
                "channel mapping family 255, which has no speaker layout, is an unsupported variant");
        byte[] version16 = stereo.head().clone();
        version16[8] = 16;
        check("Opus (header version 16)".equals(unsupportedName(OGG, ogg(version16, stereo.tags(), stereo.packets(),
                10, 255, 0, null, false, false))), "an incompatible header version is an unsupported variant");
        byte[] version15 = stereo.head().clone();
        version15[8] = 15;
        Decoded minor = decode(OGG, ogg(version15, stereo.tags(), stereo.packets(), 10, 255, 0, null, false, false),
                false);
        check(minor.frames == stereo.total() - stereo.preSkip(), "header version 15 shares the major version and plays");
        Map<String, String> codecs = new LinkedHashMap<>();
        // Vorbis, AAC, FLAC, MP3 and PCM in Matroska are read now (MatroskaAudio; native-audio-extras checks them).
        codecs.put("A_AC3", "WebM/MKV (AC-3)");
        codecs.put("A_DTS", "WebM/MKV (DTS)");
        codecs.put("A_TRUEHD", "WebM/MKV (TrueHD)");
        codecs.put("A_ALAC", "WebM/MKV (ALAC)");
        codecs.put("A_EAC3", "WebM/MKV (E-AC-3)");
        codecs.put("A_SOMETHING", "WebM/MKV (A_SOMETHING)");
        for (Map.Entry<String, String> e : codecs.entrySet()) {
            WebmSpec s = new WebmSpec();
            s.codec = e.getKey();
            String name = unsupportedName(WEBM, webm(stereo.head(), stereo.packets(), s));
            check(e.getValue().equals(name), "a " + e.getKey() + " track is refused as " + e.getValue() + ": " + name);
        }
        WebmSpec encrypted = new WebmSpec();
        encrypted.encrypted = true;
        check("WebM/MKV (encrypted)".equals(unsupportedName(WEBM, webm(stereo.head(), stereo.packets(), encrypted))),
                "an encrypted track is refused as WebM/MKV (encrypted)");
        WebmSpec compressed = new WebmSpec();
        compressed.compressed = true;
        check("WebM/MKV (compressed)".equals(unsupportedName(WEBM, webm(stereo.head(), stereo.packets(), compressed))),
                "a compressed track is refused as WebM/MKV (compressed)");
        WebmSpec doc = new WebmSpec();
        doc.docType = "notwebm";
        check("WebM/MKV".equals(unsupportedName(WEBM, webm(stereo.head(), stereo.packets(), doc))),
                "an EBML file of another DocType is refused as WebM/MKV");
        WebmSpec ambisonicWebm = new WebmSpec();
        ambisonicWebm.codecPrivate = ambisonic;
        check("Ambisonic Opus".equals(unsupportedName(WEBM, webm(surround.head(), surround.packets(), ambisonicWebm))),
                "an ambisonic CodecPrivate is an unsupported variant in WebM too");
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Fuzz: damaged files, and valid files of damaged packets

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

    /** A packet damaged the way a bad encoder or a broken remux would: bits, length, TOC or frame count. */
    private static byte[] mutatePacket(byte[] p, Random r) {
        byte[] d = p.length == 0 ? new byte[]{0} : p.clone();
        switch (r.nextInt(6)) {
            case 0 -> {
                for (int i = 1 + r.nextInt(8); i > 0; i--) {
                    d[r.nextInt(d.length)] ^= (byte) (1 << r.nextInt(8));
                }
            }
            case 1 -> d = slice(d, 0, 1 + r.nextInt(d.length));
            case 2 -> {
                byte[] more = new byte[1 + r.nextInt(300)];
                r.nextBytes(more);
                d = cat(d, more);
            }
            case 3 -> d[0] = (byte) r.nextInt(256);
            case 4 -> {
                d = d.length < 2 ? cat(d, new byte[1]) : d;
                d[0] = (byte) (d[0] | 3);
                d[1] = (byte) r.nextInt(256);
            }
            default -> {
                d = new byte[1 + r.nextInt(r.nextBoolean() ? 3 : 1400)];
                r.nextBytes(d);
            }
        }
        return d;
    }

    private static void fuzz(Map<String, byte[]> fixtures) throws Exception {
        List<String> names = new ArrayList<>(fixtures.keySet());
        int survived = 0;
        int runs = 0;
        for (int seed = 0; seed < 132; seed++) {
            String name = names.get(seed % names.size());
            byte[] mutated = mutate(fixtures.get(name), new Random(seed));
            runs++;
            if (!survive(opener(name), mutated, "fuzz " + name + " seed " + seed).equals("threw")) {
                survived++;
            }
        }
        check(survived == runs, "damaged Opus files end in a decode or an AudioFormatException: " + survived + "/" + runs);
        // Cut anywhere: a clean end or an AudioFormatException.
        int clean = 0;
        int cuts = 0;
        for (String name : List.of("mode-switch.opus", "stereo.webm")) {
            byte[] file = fixtures.get(name);
            for (int cut = 0; cut < file.length; cut += 97) {
                cuts++;
                if (!survive(opener(name), slice(file, 0, cut), name + " cut at " + cut).equals("threw")) {
                    clean++;
                }
            }
        }
        check(clean == cuts, "an Opus file cut anywhere ends cleanly: " + clean + "/" + cuts);
        // Valid containers of damaged packets: the codec itself must take any bytes. A packet it refuses is damage;
        // anything else decodes. libopus decodes the same packets to the same samples (checked against its scalar
        // build in the scratch tree on 150 000 such packets); here it is enough that nothing else happens.
        int packetRuns = 0;
        int packetSurvived = 0;
        for (int seed = 0; seed < 60; seed++) {
            String name = names.get(seed % names.size());
            if (!name.endsWith(".opus")) {
                continue;
            }
            OggParts parts = oggParts(fixtures.get(name));
            Random r = new Random(1000 + seed);
            List<byte[]> packets = new ArrayList<>();
            for (byte[] p : parts.packets()) {
                packets.add(r.nextInt(8) == 0 ? mutatePacket(p, r) : p);
            }
            byte[] file = ogg(parts.head(), parts.tags(), packets, 10, 255, 0, null, false, false);
            packetRuns++;
            if (!survive(OGG, file, "packet fuzz " + name + " seed " + seed).equals("threw")) {
                packetSurvived++;
            }
            WebmSpec s = new WebmSpec();
            s.codecDelayNs = parts.preSkip() * 1_000_000_000L / 48000;
            packetRuns++;
            if (!survive(WEBM, webm(parts.head(), packets, s), "packet fuzz webm " + name + " seed " + seed)
                    .equals("threw")) {
                packetSurvived++;
            }
        }
        check(packetSurvived == packetRuns, "files of damaged packets end in a decode or an AudioFormatException: "
                + packetSurvived + "/" + packetRuns);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Sniffing and interrupts

    private static byte[] head(byte[] file, int length) {
        return Arrays.copyOf(file, Math.min(length, file.length));
    }

    private static void sniffing(Map<String, byte[]> fixtures) {
        for (OpusFixture f : FIXTURES) {
            byte[] h = head(fixtures.get(f.file()), 64);
            boolean webm = f.file().endsWith(".webm");
            check(OpusDecoder.looksLike(h) == !webm && WebmDecoder.looksLike(h) == webm,
                    f.file() + " is sniffed as " + (webm ? "WebM" : "Ogg Opus") + " only, from 64 bytes");
        }
        OggParts parts = oggParts(fixtures.get("celt-stereo-96k.opus"));
        byte[] behind = multiplexed(parts, null);
        check(!OpusDecoder.looksLike(head(behind, 64)) && OpusDecoder.looksLike(head(behind, 4096)),
                "Opus behind another stream's first page is found given a few kilobytes, not given 64 bytes");
        check(!OpusDecoder.looksLike(vorbisOgg()), "Ogg Vorbis is not Opus");
        WebmSpec matroska = new WebmSpec();
        matroska.docType = "matroska";
        check(WebmDecoder.looksLike(head(webm(parts.head(), parts.packets(), matroska), 64)),
                "a Matroska file is sniffed as WebM/Matroska");
        Ebml.longSizes = true;
        byte[] longSizes = webm(parts.head(), parts.packets(), new WebmSpec());
        Ebml.longSizes = false;
        check(!WebmDecoder.looksLike(head(longSizes, 64)) && WebmDecoder.looksLike(head(longSizes, 256)),
                "with eight-byte sizes the DocType lies past 64 bytes, and is found in 256");
        WebmSpec other = new WebmSpec();
        other.docType = "notwebm";
        check(!WebmDecoder.looksLike(head(webm(parts.head(), parts.packets(), other), 64)),
                "an EBML file of another DocType is not WebM");
        byte[] mp3 = {(byte) 0xFF, (byte) 0xFB, (byte) 0x90, 0x64, 0, 0, 0, 0};
        byte[] flac = cat("fLaC".getBytes(StandardCharsets.US_ASCII), new byte[60]);
        byte[] wav = cat("RIFF".getBytes(StandardCharsets.US_ASCII), new byte[4],
                "WAVEfmt ".getBytes(StandardCharsets.US_ASCII), new byte[50]);
        for (byte[] h : List.of(mp3, flac, wav, new byte[3], new byte[64])) {
            check(!OpusDecoder.looksLike(h) && !WebmDecoder.looksLike(h), "other formats and zeros are neither");
        }
        check(!OpusDecoder.looksLike(null) && !WebmDecoder.looksLike(null), "a missing head is neither");
    }

    private static void interrupts(Map<String, byte[]> fixtures) throws Exception {
        for (String name : List.of("celt-stereo-96k.opus", "stereo.webm")) {
            byte[] data = fixtures.get(name);
            Opener opener = opener(name);
            String result = within(20, name + " interrupt", () -> {
                Thread.currentThread().interrupt();
                try (PcmStream s = opener.open(new Trickle(data, 5))) {
                    return drain(s) > 0 ? "decoded" : "empty";
                } catch (InterruptedIOException expected) {
                    return "interrupted";
                } finally {
                    Thread.interrupted();
                }
            });
            check(result.equals("interrupted"), name + ": an interrupted decode throws InterruptedIOException");
            result = within(20, name + " interrupt while reading", () -> {
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
            check(result.equals("interrupted"), name + ": an interrupt while reading throws InterruptedIOException");
        }
        // After a refusal the stream stays refused, and after closing it says so.
        byte[] file = fixtures.get("celt-stereo-96k.opus");
        List<Integer> pages = pageOffsets(file);
        byte[] damaged = file.clone();
        damaged[pages.get(pages.size() / 2) + 40] ^= 1;
        String sticky = within(20, "sticky", () -> {
            try (PcmStream s = OpusDecoder.open(new ByteArrayInputStream(damaged))) {
                float[][] buf = new float[2][4096];
                try {
                    while (s.read(buf, 0, 4096) > 0) {
                        // Up to the damage.
                    }
                    return "no error";
                } catch (AudioFormatException first) {
                    try {
                        s.read(buf, 0, 4096);
                        return "read again";
                    } catch (AudioFormatException again) {
                        return "refused again";
                    }
                }
            }
        });
        check(sticky.equals("refused again"), "a stream that met damage keeps refusing: " + sticky);
        String closed = within(20, "closed", () -> {
            PcmStream s = OpusDecoder.open(new ByteArrayInputStream(file));
            s.close();
            try {
                s.read(new float[2][16], 0, 16);
                return "read";
            } catch (IOException expected) {
                return "refused";
            }
        });
        check(closed.equals("refused"), "a closed stream refuses to read");
    }
}

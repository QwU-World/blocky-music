import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
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
import ru.gora.musicbattle.audio.codec.AdtsDecoder;
import ru.gora.musicbattle.audio.codec.AudioFormatException;
import ru.gora.musicbattle.audio.codec.Mp4Decoder;
import ru.gora.musicbattle.audio.codec.PcmStream;
import ru.gora.musicbattle.audio.codec.UnsupportedFormatException;

/**
 * The pure-Java AAC (LC, HE-AAC, HE-AAC v2), ALAC, MP4/M4A and ADTS decoders in audio/codec, which read the M4A, MP4
 * and .aac songs players import so that nobody has to install ffmpeg. The script compiles them with no classpath at
 * all, so this also proves they need neither Minecraft nor the client's native libraries.
 *
 * <p>Fixtures (tools/fixtures/aac and alac) were encoded from computed signals (sweeps, clicks, noise, silence,
 * drum-like hits; no recorded music; the generators and recipes are in tools/fixtures/gen) with Apple's encoders (afconvert: AAC LC, HE-AAC, HE-AAC v2, ALAC) and ffmpeg 9's
 * (AAC LC with intensity stereo, PNS and TNS; fragmented, DASH and 5.1 layouts; ALAC). Their expected lengths and
 * probe samples are ffmpeg 9's own decode ({@code ffmpeg -i FILE -f f32le -c:a pcm_f32le -}), because charts are timed
 * against it: the length must match exactly and the samples to within the two decoders' float rounding. The one
 * exception is lc-ffmpeg-tools.m4a, whose right channel follows the AAC specification where ffmpeg does not
 * (intensity stereo under ms_mask_present 2, where Apple's decoder agrees with this one), so its probes are on the left
 * channel. The SHA-256 of every decode is this decoder's own, recorded once it was verified, so any change to its
 * arithmetic shows. ALAC is lossless: its decode must give back exactly the integer samples the fixture was encoded
 * from, whose SHA-256 is recorded too.
 *
 * <p>MP4 variants are built here from the fixtures' packets: edit lists, iTunes' gapless tag, the last sample's
 * duration, fragmented and hybrid layouts, 64-bit offsets, compact sizes, QuickTime sound descriptions and damage.
 * The lengths they must decode to were measured with ffmpeg 9 on exactly these bytes (the {@code dump <dir>} argument
 * after the project root writes them out); where this decoder follows the format instead of ffmpeg, the comment says
 * what ffmpeg gives and why. Damaged input must end in an {@link AudioFormatException} or a clean end of stream, never
 * another exception and never a hang, and an interrupt must surface as an {@link InterruptedIOException}. Files whose
 * few bytes claim millions of samples or packets (empty runs, chunks and runs over the same bytes, edit lists that skip
 * nearly all of them) must not keep a single open() or read() busy, because a conversion's time limit is only looked
 * at between reads. Raw AAC that starts part way into a frame, as a recording of a broadcast does, is decoded from its
 * first whole frame; HE-AAC v2 cut so (hev2-music.aac, five seconds of a self-made music-like signal from Apple's
 * encoder) must get its SBR band and parametric stereo back once their headers come.
 *
 * <p>Not proven here: the wiring into AudioDecoders and NativeAudio, and anything about the game.
 */
public final class NativeAacDecoderChecks {
    private static int checks;
    private static final List<String> FAILURES = new ArrayList<>();
    private static Path dumpDir;
    private static Path scratch;
    private static int scratchFiles;

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args.length > 0 ? args[0] : ".");
        if (args.length > 2 && args[1].equals("dump")) {
            dumpDir = Path.of(args[2]);
            Files.createDirectories(dumpDir);
        }
        scratch = root.resolve("build/native-aac-decoder-checks/scratch");
        Files.createDirectories(scratch);
        Map<String, byte[]> fixtures = loadFixtures(root.resolve("tools/fixtures"));
        // First, while the JIT is still cold, as it is the first time a player imports a song.
        speed(fixtures);
        fixtureDecodes(fixtures);
        signalling(fixtures);
        trimming(fixtures);
        layouts(fixtures);
        memory(fixtures);
        refusals(fixtures);
        damage(fixtures);
        sniffing(fixtures);
        stereoAfterCut(root.resolve("tools/fixtures"));
        interrupts(fixtures);
        streamContract(fixtures);

        if (!FAILURES.isEmpty()) {
            System.out.println(FAILURES.size() + " of " + checks + " native AAC decoder checks FAILED:");
            for (String failure : FAILURES) {
                System.out.println("  - " + failure);
            }
            System.exit(1);
        }
        System.out.println("Native AAC decoder checks passed: " + checks);
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
        Thread t = new Thread(r, "native-aac-decoder-check");
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

    /** How a test opens its bytes: an ADTS stream, an MP4 read once as a stream, or an MP4 file opened by path. */
    private enum Way {
        ADTS, MP4_STREAM, MP4_PATH
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

    /** An opened stream and the input it owns, so a test can see whether closing the stream closed the input. */
    private record Opened(PcmStream stream, Trickle trickle, Path file) {
    }

    private static Opened open(Way way, byte[] data, boolean trickle) throws IOException {
        if (way == Way.MP4_PATH) {
            Path file = scratch.resolve("in-" + (scratchFiles++) + ".m4a");
            Files.write(file, data);
            try {
                return new Opened(Mp4Decoder.open(file), null, file);
            } catch (IOException | RuntimeException e) {
                Files.deleteIfExists(file);
                throw e;
            }
        }
        Trickle t = trickle ? new Trickle(data, data.length) : null;
        InputStream in = t != null ? t : new ByteArrayInputStream(data);
        return new Opened(way == Way.ADTS ? AdtsDecoder.open(in) : Mp4Decoder.open(in), t, null);
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
    private static Decoded decode(Way way, byte[] data, boolean trickle) throws IOException {
        Opened o = open(way, data, trickle);
        Decoded d = new Decoded();
        MessageDigest sha = sha256();
        try (PcmStream s = o.stream()) {
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
        } finally {
            if (o.file() != null) {
                // On Windows a file still open cannot be deleted, so this also shows the stream let go of it.
                Files.deleteIfExists(o.file());
            }
        }
        d.closed = o.trickle() == null || o.trickle().closed;
        d.sha256 = HexFormat.of().formatHex(sha.digest());
        return d;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String sha256(byte[] data) {
        return HexFormat.of().formatHex(sha256().digest(data));
    }

    /** Whether two decodes hold the same samples, b's frames [from, to) against a's frames shifted by offset. */
    private static boolean samePcm(Decoded a, int offset, Decoded b, int from, int to) {
        if (a.channels != b.channels || to > b.frames || from + offset < 0 || to + offset > a.frames) {
            return false;
        }
        for (int c = 0; c < a.channels; c++) {
            for (int i = from; i < to; i++) {
                if (Float.floatToRawIntBits(a.pcm[c][i + offset]) != Float.floatToRawIntBits(b.pcm[c][i])) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Opens and drains a damaged input: only AudioFormatException may come out, and the answer must come quickly. */
    private static String survive(Way way, byte[] data, String what) throws Exception {
        return within(20, what, () -> {
            try {
                Decoded d = decode(way, data, false);
                for (float[] channel : d.pcm) {
                    for (int i = 0; i < d.frames; i++) {
                        if (!Float.isFinite(channel[i])) {
                            check(false, what + ": a sample that is not a number");
                            return "threw";
                        }
                    }
                }
                return "ok " + d.frames;
            } catch (AudioFormatException e) {
                return "refused";
            } catch (Exception | Error e) {
                check(false, what + ": threw " + e);
                return "threw";
            }
        });
    }

    /** The exception an input is refused with: its class and message, "ok" for a clean decode, or what else broke. */
    private static String refusal(Way way, byte[] data, String what) throws Exception {
        return within(20, what, () -> {
            try {
                decode(way, data, false);
                return "ok";
            } catch (UnsupportedFormatException e) {
                return "unsupported: " + e.formatName();
            } catch (AudioFormatException e) {
                return "damaged: " + e.getMessage();
            } catch (Exception | Error e) {
                return "threw: " + e;
            }
        });
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Fixtures

    private record Fixture(String file, Way way, int rate, int channels, int frames, long hint, String sha256,
                           float tolerance, int[] probes, float[] values, String pcmSha256, int bits) {
    }

    // The LC fixtures that hold the same encode in three containers share their probes.
    private static final int[] PROBES_LC44 = {1030, 0, 71538, 0, 3037, 0, 7262, 1, 18667, 0, 23078, 1, 31899, 0,
            36154, 1, 47705, 0, 51093, 1, 61763, 0, 67346, 1};
    private static final float[] VALUES_LC44 = {-0.010769079f, -0.011347118f, 0.7447302f, 0.6672248f, 0.6671729f,
            0.8281275f, 0.6206202f, 0.4530308f, -0.43299448f, -0.34669992f, 0.5224828f, 0.55306613f};

    private static final float LC = 2e-5f;
    private static final float SBR = 1e-4f;

    private static final List<Fixture> FIXTURES = List.of(
            // afconvert AAC LC, 96 kbit/s: iTunSMPB says 2112 priming, no edit list.
            new Fixture("aac/lc-afconvert.m4a", Way.MP4_PATH, 44100, 2, 70560, 70560,
                    "0e1176a585deee2b847665657bbde54ee3fc952098cd1d2dcc281c7aef37a9f7", LC,
                    new int[] {11, 0, 70508, 0, 3036, 0, 9322, 1, 17644, 0, 22054, 1, 30874, 0, 41246, 1, 47240, 0,
                            52800, 1, 60822, 0, 66309, 1},
                    new float[] {0.010000425f, -0.011774173f, 0.4567717f, -0.4072854f, 0.650606f, 0.939557f,
                            0.63850653f, -0.4026821f, 0.4250751f, -0.40097803f, 0.51327676f, 0.5731967f}, null, 0),
            // ffmpeg AAC LC, 64 kbit/s, with intensity stereo, PNS and TNS; the moov after the mdat, an edit list.
            new Fixture("aac/lc-ffmpeg-tools.m4a", Way.MP4_PATH, 44100, 2, 70560, 70560,
                    "7eba51b7124f2a783c433824c1959b3ef721772e93fee59935cc81bb898c163a", LC,
                    new int[] {0, 0, 70514, 0, 791, 0, 11385, 0, 17643, 0, 22054, 0, 30875, 0, 41913, 0, 47086, 0,
                            51342, 0, 60769, 0, 66315, 0},
                    new float[] {0.011353707f, -0.010816458f, -0.34151295f, -0.44406044f, 0.6532129f, 0.8383219f,
                            0.6150963f, 0.3329079f, -0.36608735f, 0.3530209f, 0.4938664f, 0.53037876f}, null, 0),
            // ffmpeg, fragmented: an empty moov, then moof and mdat pairs.
            new Fixture("aac/lc-fragmented.m4a", Way.MP4_STREAM, 48000, 2, 77824, -1,
                    "b8c69096ba959b37ceb39acadcc01531ca17022f1959232e674dad4ed7e296a8", LC,
                    new int[] {652, 0, 77767, 0, 3464, 0, 7993, 1, 20227, 0, 25029, 1, 34630, 0, 38981, 1, 49170, 0,
                            55410, 1, 67149, 0, 73198, 1},
                    new float[] {-0.01028384f, -0.0110530285f, 0.61990225f, 0.53221613f, 0.67879933f, 0.8700477f,
                            0.60443896f, 0.4401541f, 0.39409462f, 0.36020544f, 0.56974554f, 0.592219f}, null, 0),
            // ffmpeg +frag_keyframe: the first samples in the moov, the rest in fragments.
            new Fixture("aac/lc-hybrid.m4a", Way.MP4_STREAM, 44100, 2, 71680, -1,
                    "bd8170ebbfb1d8bd05626c075016b2fc09911b11f0c08db79f5a36d1d618fee9", LC, PROBES_LC44, VALUES_LC44,
                    null, 0),
            // ffmpeg +dash+global_sidx: YouTube's layout for audio-only .m4a streams.
            new Fixture("aac/lc-dash.m4a", Way.MP4_STREAM, 44100, 2, 71680, -1,
                    "bd8170ebbfb1d8bd05626c075016b2fc09911b11f0c08db79f5a36d1d618fee9", LC, PROBES_LC44, VALUES_LC44,
                    null, 0),
            // The same audio again, raw in ADTS: every frame is output.
            new Fixture("aac/lc-stereo-44k.aac", Way.ADTS, 44100, 2, 71680, -1,
                    "bd8170ebbfb1d8bd05626c075016b2fc09911b11f0c08db79f5a36d1d618fee9", LC, PROBES_LC44, VALUES_LC44,
                    null, 0),
            new Fixture("aac/lc-mono-22k.aac", Way.ADTS, 22050, 1, 27648, -1,
                    "e44230f7971ce18a14ce8181f696a3123fb1f1181ebb5546717146d0853dbbe2", LC,
                    new int[] {1002, 0, 27647, 0, 2291, 0, 2968, 0, 6348, 0, 9846, 0, 12051, 0, 14256, 0, 18029, 0,
                            20130, 0, 23686, 0, 27119, 0},
                    new float[] {0.010097746f, 0.027245015f, 0.47291628f, -0.48804513f, 0.45236298f, 0.63924235f,
                            0.83816576f, 0.7848161f, -0.51924175f, 0.5412612f, 0.48086143f, -0.45308754f}, null, 0),
            // ffmpeg 5.1: channel configuration 6, decoded in the WAVE order L R C LFE Ls Rs.
            new Fixture("aac/lc-5.1.m4a", Way.MP4_PATH, 48000, 6, 24000, 24000,
                    "ec080218abbfdc7a60f14f56f539b943a7dafe85962d820c57f76c150d8c834c", LC,
                    new int[] {0, 0, 23983, 0, 1912, 0, 3910, 1, 5402, 2, 9207, 3, 10361, 4, 12002, 5, 15016, 0,
                            18966, 1, 19202, 2, 23791, 3},
                    new float[] {-0.025462193f, -0.011423602f, 0.54938793f, -0.56078154f, -0.47983462f,
                            -1.271375E-5f, 0.5047511f, -0.44757327f, 0.5256486f, 0.35957986f, 0.6106564f,
                            -0.004785932f}, null, 0),
            // afconvert HE-AAC (explicit SBR signalling), 48 kbit/s at 48 kHz.
            new Fixture("aac/he-afconvert.m4a", Way.MP4_STREAM, 48000, 2, 76800, 76800,
                    "377c30f8faadf80212cf6b7c2aa5131db44e5333a6d035c5cc30bedbe514c75b", SBR,
                    new int[] {981, 0, 76799, 0, 4406, 0, 8564, 1, 20166, 0, 24965, 1, 34567, 0, 39267, 1, 52590, 0,
                            55116, 1, 67073, 0, 73135, 1},
                    new float[] {0.010185789f, -0.037194937f, -0.4610929f, -0.46282983f, 0.66042954f, 0.8831681f,
                            0.56721365f, -0.4729516f, 0.53787535f, 0.45301607f, 0.503827f, 0.5857083f}, null, 0),
            // The same encoder in ADTS: SBR found in the first frame (implicit signalling), every frame output.
            new Fixture("aac/he-implicit.aac", Way.ADTS, 48000, 2, 83968, -1,
                    "bbfeb63c8247493f7d6cef7e593f89708f94fb562129e86d29f3bed72985d927", SBR,
                    new int[] {5205, 0, 81930, 0, 6653, 0, 12788, 1, 24390, 0, 29189, 1, 33989, 0, 43491, 1, 56814, 0,
                            59340, 1, 71297, 0, 77359, 1},
                    new float[] {0.010185789f, -0.010304733f, -0.45214662f, -0.46282983f, 0.66042954f, 0.8831681f,
                            0.77598f, -0.4729516f, 0.53787535f, 0.45301607f, 0.503827f, 0.5857083f}, null, 0),
            // Mono HE-AAC: two channels come out, as from ffmpeg, since parametric stereo cannot be ruled out.
            new Fixture("aac/he-mono-afconvert.m4a", Way.MP4_STREAM, 48000, 2, 76800, 76800,
                    "7cdd97eacc2741f4f1355a77ebc8862983a5353068826a5a6fdf329ed18960f2", SBR,
                    new int[] {974, 0, 76798, 0, 3580, 0, 11174, 1, 20168, 0, 24966, 1, 34567, 0, 44840, 1, 49855, 0,
                            56951, 1, 67126, 0, 73098, 1},
                    new float[] {0.011037353f, -0.016521512f, -0.46032754f, -0.47268838f, 0.6134076f, 0.83698756f,
                            0.6017539f, 0.505203f, 0.64181906f, 0.4764069f, 0.557717f, 0.59942883f}, null, 0),
            // afconvert HE-AAC v2 (SBR and parametric stereo), 24 kbit/s.
            new Fixture("aac/hev2-afconvert.m4a", Way.MP4_STREAM, 44100, 2, 70560, 70560,
                    "8d113d79d90faf5a158bee9d619a87084f3b7fa47df65f204c3d51311561d005", SBR,
                    new int[] {973, 0, 70558, 0, 3879, 0, 11297, 1, 18605, 0, 23016, 1, 31837, 0, 35833, 1, 46336, 0,
                            51996, 1, 61767, 0, 67280, 1},
                    new float[] {0.01178988f, -0.05661357f, -0.47574347f, -0.53411293f, 0.55582577f, 0.87830395f,
                            0.74243647f, 0.45903382f, -0.32857713f, 0.34204882f, 0.50281906f, 0.56800735f}, null, 0),
            new Fixture("aac/hev2-implicit.aac", Way.ADTS, 44100, 2, 75776, -1,
                    "46488eabeffeb88e2684f50187abf6fd8abaa1c9e4f36d2e48b40ae2f9fe9ca0", SBR,
                    new int[] {5197, 0, 75775, 0, 6670, 0, 14634, 1, 17774, 0, 27240, 1, 31649, 0, 39420, 1, 50560, 0,
                            56220, 1, 65991, 0, 71504, 1},
                    new float[] {0.01178988f, -0.033704292f, 0.47277644f, 0.52747893f, -0.5764083f, 0.87830395f,
                            0.7732437f, -0.4934203f, -0.32857713f, 0.34204882f, 0.50281906f, 0.56800735f}, null, 0),
            // ALAC: the decode must be the encoder's input exactly.
            new Fixture("alac/alac-afconvert-16.m4a", Way.MP4_STREAM, 44100, 2, 19845, 19845,
                    "9d49f6adf2d5bc77e808856ef6b056fae9046e76a20fc3dec236d3344ce4ed49", 0f,
                    new int[] {20, 0, 19832, 0, 646, 0, 3442, 1, 4474, 0, 7680, 1, 9466, 0, 10010, 1, 13166, 0, 14886,
                            1, 17644, 0, 18969, 1},
                    new float[] {0.011413574f, 0.012390137f, 0.22460938f, -0.18988037f, 0.22473145f, 0.18997192f,
                            0.22494507f, -0.18988037f, -0.22488403f, 0.18981934f, 0.3287964f, -0.23260498f},
                    "521b50795b224d11ee9491b7632ceb8d96b2cfb2f4b5bb9e3509ab371f01e220", 16),
            new Fixture("alac/alac-afconvert-24-mono.m4a", Way.MP4_STREAM, 48000, 1, 14400, 14400,
                    "27389ad26916aa93b4afcf5944fc461df98d64ab363016cb62597dde1e17badc", 0f,
                    new int[] {21, 0, 14361, 0, 396, 0, 2052, 0, 3972, 0, 4740, 0, 6660, 0, 7663, 0, 9919, 0, 10303,
                            0, 12799, 0, 13860, 0},
                    new float[] {0.010455847f, -0.012688994f, 0.22496629f, -0.22464609f, -0.22499955f, -0.2247175f,
                            -0.2249794f, 0.22457147f, 0.22457194f, 0.22457206f, 0.2244519f, -0.22499299f},
                    "0aad26833ff8b23ace4477fb2d33dc59eb194b7791cd79c2cc8d5a34193d6bc2", 24),
            new Fixture("alac/alac-ffmpeg-mono.m4a", Way.MP4_PATH, 44100, 1, 17640, 17640,
                    "b96d64e101e5ae9fc874bc1996440b07946bd71ff2d93111252272f16df06e02", 0f,
                    new int[] {27, 0, 17625, 0, 646, 0, 2013, 0, 4474, 0, 6432, 0, 7336, 0, 9466, 0, 11133, 0, 13166,
                            0, 14930, 0, 16059, 0},
                    new float[] {0.0115356445f, 0.011077881f, 0.1796875f, 0.17962646f, 0.17977905f, 0.17999268f,
                            -0.17984009f, 0.17996216f, 0.17996216f, -0.17993164f, -0.17993164f, -0.17996216f},
                    "0fe49387c830d337a3b3ba0c7228c80da7b893db61b543ba1f6dcfd1d3b4200f", 16),
            // ffmpeg's compression_level 0 stores every frame verbatim: the escape path.
            new Fixture("alac/alac-ffmpeg-verbatim.m4a", Way.MP4_PATH, 44100, 2, 2646, 2646,
                    "08526113b75680782908fdb1c1085c03e174c07897e773409f628a039c34166c", 0f,
                    new int[] {18, 0, 2636, 0, 218, 0, 308, 1, 646, 0, 990, 1, 1065, 0, 1431, 1, 1612, 0, 1900, 1,
                            2278, 0, 2388, 1},
                    new float[] {0.012054443f, -0.0132751465f, -0.39935303f, -0.3406372f, 0.40429688f, -0.34146118f,
                            -0.40341187f, -0.3408203f, -0.4034729f, 0.3418579f, 0.4032898f, 0.34106445f},
                    "ba651ec58c75dd7652b02b58a31c52f22a61d8c4991db5f81752762325bd0a49", 16));

    private static Map<String, byte[]> loadFixtures(Path dir) throws IOException {
        Map<String, byte[]> map = new LinkedHashMap<>();
        for (Fixture f : FIXTURES) {
            map.put(f.file(), Files.readAllBytes(dir.resolve(f.file())));
        }
        return map;
    }

    private static boolean isMp4(Fixture f) {
        return f.way() != Way.ADTS;
    }

    private static void fixtureDecodes(Map<String, byte[]> fixtures) throws Exception {
        for (Fixture f : FIXTURES) {
            String name = f.file();
            byte[] data = fixtures.get(name);
            Decoded d = within(20, name, () -> decode(f.way(), data, true));
            check(d.sampleRate == f.rate() && d.channels == f.channels(),
                    name + ": " + d.sampleRate + " Hz x" + d.channels + ", expected " + f.rate() + " x" + f.channels());
            check(d.frames == f.frames(), name + ": " + d.frames + " frames like ffmpeg's " + f.frames());
            check(d.hint == f.hint(), name + ": frame count hint " + d.hint + ", expected " + f.hint());
            check(d.sha256.equals(f.sha256()), name + ": the decode is unchanged (sha256 " + d.sha256 + ")");
            check(d.closed, name + ": closing the stream closes the input");
            float worst = 0;
            int[] p = f.probes();
            for (int k = 0; k < f.values().length; k++) {
                int index = p[2 * k];
                int channel = p[2 * k + 1];
                float v = index < d.frames ? d.pcm[channel][index] : Float.NaN;
                worst = Math.max(worst, Math.abs(v - f.values()[k]));
                if (!(Math.abs(v - f.values()[k]) <= f.tolerance())) {
                    worst = Float.POSITIVE_INFINITY;
                }
            }
            check(worst <= f.tolerance(), name + ": probe samples match ffmpeg's decode within " + f.tolerance()
                    + " (worst " + worst + ")");
            if (f.pcmSha256() != null) {
                check(integerSha(d, f.bits()).equals(f.pcmSha256()),
                        name + ": decodes to exactly the " + f.bits() + "-bit samples it was encoded from");
            }
            // Every MP4 reads the same by path as read once as a stream, when its index comes first.
            if (isMp4(f)) {
                boolean moovFirst = topLevel(data).indexOf("moov") < topLevel(data).indexOf("mdat")
                        || topLevel(data).indexOf("mdat") < 0;
                Way other = f.way() == Way.MP4_PATH ? Way.MP4_STREAM : Way.MP4_PATH;
                if (other == Way.MP4_PATH || moovFirst) {
                    Decoded o = within(20, name + " " + other, () -> decode(other, data, false));
                    check(o.frames == d.frames && o.sha256.equals(d.sha256) && o.hint == d.hint,
                            name + ": decodes the same by path and as a stream");
                } else {
                    String r = refusal(Way.MP4_STREAM, data, name + " as a stream");
                    check(r.startsWith("damaged: ") && r.contains("open the file by path"),
                            name + ": as a stream, an index after the audio is refused with a pointer to the path ("
                                    + r + ")");
                }
            }
        }
    }

    /** The decode as integers of the given width, little-endian and interleaved, hashed. */
    private static String integerSha(Decoded d, int bits) {
        ByteBuffer pcm = ByteBuffer.allocate(d.frames * d.channels * (bits / 8)).order(ByteOrder.LITTLE_ENDIAN);
        double scale = 1L << (bits - 1);
        for (int i = 0; i < d.frames; i++) {
            for (int c = 0; c < d.channels; c++) {
                long v = Math.round(d.pcm[c][i] * scale);
                if (bits == 16) {
                    pcm.putShort((short) v);
                } else {
                    pcm.put((byte) v).put((byte) (v >> 8)).put((byte) (v >> 16));
                }
            }
        }
        return sha256(pcm.array());
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Packets out of the fixtures, and MP4 files built around them

    private static long u32(byte[] b, int p) {
        return (b[p] & 0xFFL) << 24 | (b[p + 1] & 0xFFL) << 16 | (b[p + 2] & 0xFFL) << 8 | (b[p + 3] & 0xFFL);
    }

    private static long u64(byte[] b, int p) {
        return u32(b, p) << 32 | u32(b, p + 4);
    }

    /** The types of the top-level boxes, in order. */
    private static List<String> topLevel(byte[] d) {
        List<String> out = new ArrayList<>();
        int p = 0;
        while (p + 8 <= d.length) {
            long size = u32(d, p);
            String type = new String(d, p + 4, 4, StandardCharsets.ISO_8859_1);
            if (size == 1) {
                size = u64(d, p + 8);
            } else if (size == 0) {
                size = d.length - p;
            }
            if (size < 8 || p + size > d.length) {
                break;
            }
            out.add(type);
            p += (int) size;
        }
        return out;
    }

    /** A box in a byte array: where its body starts, where it ends. */
    private record BoxAt(String type, int body, int end) {
    }

    private static List<BoxAt> children(byte[] d, int from, int to) {
        List<BoxAt> out = new ArrayList<>();
        int p = from;
        while (p + 8 <= to) {
            int size = (int) u32(d, p);
            if (size < 8 || p + size > to) {
                break;
            }
            out.add(new BoxAt(new String(d, p + 4, 4, StandardCharsets.ISO_8859_1), p + 8, p + size));
            p += size;
        }
        return out;
    }

    /** The first box along a path of types, starting at the top level; null when there is none. */
    private static BoxAt find(byte[] d, String... path) {
        int from = 0;
        int to = d.length;
        BoxAt found = null;
        for (String type : path) {
            found = null;
            for (BoxAt b : children(d, from, to)) {
                if (b.type().equals(type)) {
                    found = b;
                    break;
                }
            }
            if (found == null) {
                return null;
            }
            from = found.body();
            to = found.end();
        }
        return found;
    }

    /** The audio packets of a plain MP4 (sample tables in the moov) and its sample entry's configuration box. */
    private record Track(List<byte[]> packets, int[] durations, byte[] configBox, int timescale) {
    }

    private static Track track(byte[] d) {
        BoxAt stbl = find(d, "moov", "trak", "mdia", "minf", "stbl");
        BoxAt mdhd = find(d, "moov", "trak", "mdia", "mdhd");
        int timescale = (int) u32(d, mdhd.body() + 12);
        BoxAt stsz = null;
        BoxAt stco = null;
        BoxAt stsc = null;
        BoxAt stts = null;
        BoxAt stsd = null;
        for (BoxAt b : children(d, stbl.body(), stbl.end())) {
            switch (b.type()) {
                case "stsz" -> stsz = b;
                case "stco" -> stco = b;
                case "stsc" -> stsc = b;
                case "stts" -> stts = b;
                case "stsd" -> stsd = b;
                default -> {
                }
            }
        }
        int count = (int) u32(d, stsz.body() + 8);
        int[] sizes = new int[count];
        for (int i = 0; i < count; i++) {
            sizes[i] = (int) u32(d, stsz.body() + 12 + 4 * i);
        }
        int chunks = (int) u32(d, stco.body() + 4);
        int entries = (int) u32(d, stsc.body() + 4);
        List<byte[]> packets = new ArrayList<>();
        int sample = 0;
        for (int c = 0; c < chunks; c++) {
            int perChunk = 0;
            for (int e = 0; e < entries; e++) {
                if (u32(d, stsc.body() + 8 + 12 * e) <= c + 1) {
                    perChunk = (int) u32(d, stsc.body() + 12 + 12 * e);
                }
            }
            int offset = (int) u32(d, stco.body() + 8 + 4 * c);
            for (int k = 0; k < perChunk && sample < count; k++, sample++) {
                packets.add(Arrays.copyOfRange(d, offset, offset + sizes[sample]));
                offset += sizes[sample];
            }
        }
        int[] durations = new int[count];
        int at = 0;
        for (int e = 0, n = (int) u32(d, stts.body() + 4); e < n; e++) {
            int c = (int) u32(d, stts.body() + 8 + 8 * e);
            int delta = (int) u32(d, stts.body() + 12 + 8 * e);
            for (int k = 0; k < c && at < count; k++) {
                durations[at++] = delta;
            }
        }
        // The sample entry is the first child of stsd (8 bytes of version, flags and count); its own children start
        // after the 28-byte audio sample entry header.
        BoxAt entry = children(d, stsd.body() + 8, stsd.end()).get(0);
        BoxAt config = children(d, entry.body() + 28, entry.end()).get(0);
        return new Track(packets, durations, Arrays.copyOfRange(d, config.body(), config.end()), timescale);
    }

    /** The raw AAC frames of an ADTS stream, headers removed, and the fields of its first header. */
    private record Adts(List<byte[]> frames, int profile, int rateIndex, int channelConfig) {
    }

    private static Adts adts(byte[] d) {
        List<byte[]> frames = new ArrayList<>();
        int p = 0;
        while (p + 7 <= d.length) {
            int length = ((d[p + 3] & 3) << 11) | ((d[p + 4] & 0xFF) << 3) | ((d[p + 5] & 0xFF) >> 5);
            int header = (d[p + 1] & 1) != 0 ? 7 : 9;
            frames.add(Arrays.copyOfRange(d, p + header, p + length));
            p += length;
        }
        return new Adts(frames, (d[2] & 0xFF) >> 6, ((d[2] & 0xFF) >> 2) & 15, ((d[2] & 1) << 2) | ((d[3] & 0xFF) >> 6));
    }

    /** An ADTS stream of the given raw frames: CRC-protected (9-byte headers) or not, several frames per header. */
    private static byte[] toAdts(List<byte[]> frames, int profile, int rateIndex, int channelConfig, boolean crc) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] f : frames) {
            int header = crc ? 9 : 7;
            int length = header + f.length;
            out.write(0xFF);
            out.write(crc ? 0xF0 : 0xF1);
            out.write(profile << 6 | rateIndex << 2 | channelConfig >> 2);
            out.write((channelConfig & 3) << 6 | length >> 11);
            out.write(length >> 3);
            out.write((length & 7) << 5 | 0x1F);
            out.write(0xFC);
            if (crc) {
                // The CRC is not checked by ffmpeg or this decoder; any value will do.
                out.write(0x12);
                out.write(0x34);
            }
            out.writeBytes(f);
        }
        return out.toByteArray();
    }

    private static byte[] cat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) {
            out.writeBytes(p);
        }
        return out.toByteArray();
    }

    private static byte[] be(long v, int bytes) {
        byte[] b = new byte[bytes];
        for (int i = 0; i < bytes; i++) {
            b[i] = (byte) (v >>> (8 * (bytes - 1 - i)));
        }
        return b;
    }

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.ISO_8859_1);
    }

    private static byte[] box(String type, byte[]... parts) {
        byte[] body = cat(parts);
        return cat(be(8 + body.length, 4), ascii(type), body);
    }

    private static byte[] full(String type, int version, int flags, byte[]... parts) {
        return box(type, cat(be((long) version << 24 | flags, 4)), cat(parts));
    }

    /** An MPEG-4 descriptor with its size in the four-byte form encoders write. */
    private static byte[] descriptor(int tag, byte[]... parts) {
        byte[] body = cat(parts);
        int n = body.length;
        return cat(new byte[] {(byte) tag, (byte) (0x80 | n >> 21), (byte) (0x80 | (n >> 14) & 0x7F),
                (byte) (0x80 | (n >> 7) & 0x7F), (byte) (n & 0x7F)}, body);
    }

    /** An AudioSpecificConfig from its bit fields: {value, width, value, width, ...}. */
    private static byte[] bits(int... fields) {
        long acc = 0;
        int n = 0;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < fields.length; i += 2) {
            for (int b = fields[i + 1] - 1; b >= 0; b--) {
                acc = acc << 1 | ((fields[i] >>> b) & 1);
                if (++n == 8) {
                    out.write((int) acc);
                    acc = 0;
                    n = 0;
                }
            }
        }
        if (n > 0) {
            out.write((int) (acc << (8 - n)));
        }
        return out.toByteArray();
    }

    /** A plain AAC AudioSpecificConfig: object type, sampling frequency index, channel configuration. */
    private static byte[] asc(int objectType, int rateIndex, int channels) {
        return bits(objectType, 5, rateIndex, 4, channels, 4, 0, 3);
    }

    /** Everything an MP4 test file is built from. */
    private static final class Mp4 {
        String format = "mp4a";
        /** For mp4a the AudioSpecificConfig; for alac the 24-byte ALACSpecificConfig; for others the box body. */
        byte[] config;
        String configBox = "esds";
        int objectType = 0x40;
        int channels = 2;
        int sampleRate = 44100;
        int sampleSize = 16;
        int timescale = 44100;
        int movieTimescale = 1000;
        List<byte[]> packets;
        int[] durations;
        long mdhdDuration = -1;
        /** {media time, duration in movie units} per edit, or null for no edit list. */
        long[][] edits;
        String smpb;
        boolean moovFirst = true;
        boolean co64;
        boolean stz2;
        boolean largeMdat;
        /** More than 0: fragmented, with this many packets per fragment. */
        int fragmentPackets;
        /** With fragments: how many packets the moov's own tables hold (0 for an empty moov). */
        int moovPackets;
        boolean otherTrackAfter;
        boolean otherTrackBefore;
        boolean audio = true;
        boolean quickTimeV1;
        boolean junkBoxes;
        int chunkPackets = 16;

        Mp4(List<byte[]> packets, int frameDuration) {
            this.packets = packets;
            durations = new int[packets.size()];
            Arrays.fill(durations, frameDuration);
        }

        Mp4 copy() {
            Mp4 m = new Mp4(packets, 0);
            m.format = format;
            m.config = config;
            m.configBox = configBox;
            m.objectType = objectType;
            m.channels = channels;
            m.sampleRate = sampleRate;
            m.sampleSize = sampleSize;
            m.timescale = timescale;
            m.movieTimescale = movieTimescale;
            m.durations = durations.clone();
            m.mdhdDuration = mdhdDuration;
            m.edits = edits;
            m.smpb = smpb;
            m.moovFirst = moovFirst;
            m.co64 = co64;
            m.stz2 = stz2;
            m.largeMdat = largeMdat;
            m.fragmentPackets = fragmentPackets;
            m.moovPackets = moovPackets;
            m.otherTrackAfter = otherTrackAfter;
            m.otherTrackBefore = otherTrackBefore;
            m.audio = audio;
            m.quickTimeV1 = quickTimeV1;
            m.junkBoxes = junkBoxes;
            m.chunkPackets = chunkPackets;
            return m;
        }

        Mp4 with(java.util.function.Consumer<Mp4> change) {
            Mp4 m = copy();
            change.accept(m);
            return m;
        }

        private long total() {
            long t = 0;
            for (int d : durations) {
                t += d;
            }
            return t;
        }

        private byte[] sampleEntry() {
            byte[] configBytes;
            if (format.equals("mp4a")) {
                configBytes = full("esds", 0, 0, descriptor(3, be(1, 2), new byte[] {0},
                        descriptor(4, new byte[] {(byte) objectType, 0x15}, be(0, 3), be(128000, 4), be(128000, 4),
                                descriptor(5, config)),
                        descriptor(6, new byte[] {2})));
            } else if (format.equals("alac")) {
                configBytes = full("alac", 0, 0, config);
            } else {
                configBytes = box(configBox, config);
            }
            byte[] head = cat(new byte[6], be(1, 2), be(quickTimeV1 ? 1 : 0, 2), be(0, 2), be(0, 4), be(channels, 2),
                    be(sampleSize, 2), be(0, 2), be(0, 2), be((long) sampleRate << 16, 4));
            if (quickTimeV1) {
                // QuickTime sound description version 1: four more fields, and the codec boxes inside 'wave'.
                head = cat(head, be(1024, 4), be(0, 4), be(0, 4), be(2, 4));
                configBytes = box("wave", box("frma", ascii(format)), box(format, be(0, 4)), configBytes,
                        new byte[8]);
            }
            return box(format, head, configBytes);
        }

        private byte[] stbl(int from, int to, long base) {
            int n = to - from;
            ByteArrayOutputStream stts = new ByteArrayOutputStream();
            int entries = 0;
            for (int i = from; i < to; ) {
                int j = i;
                while (j < to && durations[j] == durations[i]) {
                    j++;
                }
                stts.writeBytes(be(j - i, 4));
                stts.writeBytes(be(durations[i], 4));
                entries++;
                i = j;
            }
            int chunks = (n + chunkPackets - 1) / chunkPackets;
            byte[] stsc = n == 0 ? be(0, 4) : (n % chunkPackets == 0 || chunks == 1)
                    ? cat(be(1, 4), be(1, 4), be(Math.min(n, chunkPackets), 4), be(1, 4))
                    : cat(be(2, 4), be(1, 4), be(chunkPackets, 4), be(1, 4), be(chunks, 4), be(n % chunkPackets, 4),
                            be(1, 4));
            ByteArrayOutputStream sizes = new ByteArrayOutputStream();
            ByteArrayOutputStream offsets = new ByteArrayOutputStream();
            long offset = base;
            for (int i = from; i < to; i++) {
                if ((i - from) % chunkPackets == 0) {
                    offsets.writeBytes(be(offset, co64 ? 8 : 4));
                }
                sizes.writeBytes(be(packets.get(i).length, stz2 ? 2 : 4));
                offset += packets.get(i).length;
            }
            byte[] sizeBox = stz2 ? full("stz2", 0, 0, be(16, 4), be(n, 4), sizes.toByteArray())
                    : full("stsz", 0, 0, be(0, 4), be(n, 4), sizes.toByteArray());
            byte[] chunkBox = full(co64 ? "co64" : "stco", 0, 0, be(chunks, 4), offsets.toByteArray());
            return box("stbl", full("stsd", 0, 0, be(1, 4), sampleEntry()), full("stts", 0, 0, be(entries, 4),
                    stts.toByteArray()), full("stsc", 0, 0, stsc), sizeBox, chunkBox);
        }

        private byte[] trak(int id, String handler, byte[] stbl, long mediaDuration, boolean withEdits) {
            byte[] edts = withEdits && edits != null ? box("edts", elst()) : new byte[0];
            byte[] media = handler.equals("soun") ? full("smhd", 0, 0, be(0, 4)) : full("nmhd", 0, 0);
            return box("trak", full("tkhd", 0, 7, be(0, 4), be(0, 4), be(id, 4), be(0, 4), be(0, 4), new byte[60]),
                    edts, box("mdia", full("mdhd", 0, 0, be(0, 4), be(0, 4), be(timescale, 4),
                                    be(mediaDuration, 4), be(0x55C4, 2), be(0, 2)),
                            full("hdlr", 0, 0, be(0, 4), ascii(handler), new byte[12], new byte[1]),
                            box("minf", media, box("dinf", full("dref", 0, 0, be(1, 4), full("url ", 0, 1))),
                                    stbl)));
        }

        private byte[] elst() {
            ByteArrayOutputStream e = new ByteArrayOutputStream();
            for (long[] edit : edits) {
                e.writeBytes(be(edit[1], 4));
                e.writeBytes(be(edit[0], 4));
                e.writeBytes(be(0x10000, 4));
            }
            return full("elst", 0, 0, be(edits.length, 4), e.toByteArray());
        }

        private byte[] udta() {
            if (smpb == null) {
                return new byte[0];
            }
            byte[] item = box("----", full("mean", 0, 0, ascii("com.apple.iTunes")), full("name", 0, 0,
                    ascii("iTunSMPB")), box("data", be(1, 4), be(0, 4), ascii(smpb)));
            return box("udta", full("meta", 0, 0, full("hdlr", 0, 0, be(0, 4), ascii("mdir"), ascii("appl"),
                    new byte[8], new byte[1]), box("ilst", item)));
        }

        private byte[] moov(long base) {
            int inMoov = fragmentPackets > 0 ? moovPackets : packets.size();
            long sum = 0;
            for (int i = 0; i < inMoov; i++) {
                sum += durations[i];
            }
            long mediaDuration = mdhdDuration >= 0 ? mdhdDuration : fragmentPackets > 0 ? 0 : sum;
            byte[] audioTrak = trak(audio ? 1 : 1, audio ? "soun" : "vide", stbl(0, inMoov, base), mediaDuration, true);
            Mp4 empty = copy();
            empty.packets = List.of();
            empty.durations = new int[0];
            byte[] other = otherTrackAfter || otherTrackBefore
                    ? empty.trak(2, "hapt", empty.stbl(0, 0, 0), 0, false) : new byte[0];
            byte[] mvex = fragmentPackets > 0 ? box("mvex", full("trex", 0, 0, be(1, 4), be(1, 4), be(0, 4), be(0, 4),
                    be(0, 4))) : new byte[0];
            byte[] junk = junkBoxes ? cat(box("free", new byte[13]), box("xyzw", new byte[5])) : new byte[0];
            long movieDuration = mdhdDuration >= 0 ? mdhdDuration : sum;
            return box("moov", full("mvhd", 0, 0, be(0, 4), be(0, 4), be(movieTimescale, 4),
                            be(movieDuration * movieTimescale / Math.max(1, timescale), 4), be(0x10000, 4), be(0x100, 2),
                            new byte[10], new byte[36], new byte[24], be(3, 4)), junk,
                    otherTrackBefore ? other : new byte[0], audioTrak, otherTrackAfter ? other : new byte[0], mvex,
                    udta());
        }

        private byte[] mdatHeader(long payload) {
            return largeMdat ? cat(be(1, 4), ascii("mdat"), be(16 + payload, 8)) : cat(be(8 + payload, 4), ascii("mdat"));
        }

        byte[] build() {
            byte[] ftyp = box("ftyp", ascii("M4A "), be(0, 4), ascii("M4A "), ascii("mp42"), ascii("isom"));
            byte[] free = junkBoxes ? box("free", new byte[7]) : new byte[0];
            int inMoov = fragmentPackets > 0 ? moovPackets : packets.size();
            ByteArrayOutputStream payload = new ByteArrayOutputStream();
            for (int i = 0; i < inMoov; i++) {
                payload.writeBytes(packets.get(i));
            }
            byte[] mdat = cat(mdatHeader(payload.size()), payload.toByteArray());
            int headerLength = largeMdat ? 16 : 8;
            byte[] head;
            if (moovFirst || fragmentPackets > 0) {
                int moovLength = moov(0).length;
                long base = ftyp.length + free.length + moovLength + headerLength;
                head = cat(ftyp, free, moov(base), inMoov > 0 || fragmentPackets == 0 ? mdat : new byte[0]);
            } else {
                long base = ftyp.length + free.length + headerLength;
                head = cat(ftyp, free, mdat, moov(base));
            }
            if (fragmentPackets <= 0) {
                return head;
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.writeBytes(head);
            long decodeTime = 0;
            for (int i = 0; i < inMoov; i++) {
                decodeTime += durations[i];
            }
            int sequence = 1;
            for (int first = inMoov; first < packets.size(); first += fragmentPackets) {
                int last = Math.min(packets.size(), first + fragmentPackets);
                ByteArrayOutputStream run = new ByteArrayOutputStream();
                ByteArrayOutputStream data = new ByteArrayOutputStream();
                for (int i = first; i < last; i++) {
                    run.writeBytes(be(durations[i], 4));
                    run.writeBytes(be(packets.get(i).length, 4));
                    data.writeBytes(packets.get(i));
                }
                // The trun's data offset counts from the moof (default-base-is-moof): the moof, then the mdat header.
                byte[] moofProbe = moof(sequence, decodeTime, last - first, 0, run.toByteArray());
                byte[] moof = moof(sequence, decodeTime, last - first, moofProbe.length + 8, run.toByteArray());
                out.writeBytes(moof);
                out.writeBytes(cat(be(8 + data.size(), 4), ascii("mdat")));
                out.writeBytes(data.toByteArray());
                for (int i = first; i < last; i++) {
                    decodeTime += durations[i];
                }
                sequence++;
            }
            return out.toByteArray();
        }

        private static byte[] moof(int sequence, long decodeTime, int count, int dataOffset, byte[] run) {
            return box("moof", full("mfhd", 0, 0, be(sequence, 4)), box("traf", full("tfhd", 0, 0x020000, be(1, 4)),
                    full("tfdt", 1, 0, be(decodeTime, 8)), full("trun", 0, 0x000301, be(count, 4), be(dataOffset, 4),
                            run)));
        }
    }

    /** The iTunSMPB text Apple writes: twelve hex fields, the second to fourth priming, remainder and valid samples. */
    private static String smpb(long priming, long remainder, long valid) {
        return String.format(" 00000000 %08X %08X %016X 00000000 00000000 00000000 00000000 00000000 00000000"
                + " 00000000 00000000", priming, remainder, valid);
    }

    /** The building blocks of the variants: LC, HE-AAC and HE-AAC v2 packets, and ALAC. */
    private static final class Sources {
        Adts lc;
        Adts he;
        Adts hev2;
        Track alac;

        Mp4 lc() {
            Mp4 m = new Mp4(lc.frames(), 1024);
            m.config = asc(2, lc.rateIndex(), lc.channelConfig());
            m.timescale = 44100;
            m.sampleRate = 44100;
            return m;
        }

        /** HE-AAC with SBR signalled hierarchically (object type 5, then the core's), the way Apple writes it. */
        Mp4 he() {
            Mp4 m = new Mp4(he.frames(), 1024);
            m.config = bits(5, 5, he.rateIndex(), 4, he.channelConfig(), 4, 3, 4, 2, 5, 0, 3);
            m.timescale = 24000;
            m.sampleRate = 48000;
            return m;
        }

        Mp4 alac() {
            Mp4 m = new Mp4(alac.packets(), 4096);
            m.durations = alac.durations().clone();
            m.format = "alac";
            m.config = Arrays.copyOfRange(alac.configBox(), 4, 28);
            m.timescale = alac.timescale();
            m.sampleRate = alac.timescale();
            return m;
        }
    }

    private static Sources sources(Map<String, byte[]> fixtures) {
        Sources s = new Sources();
        s.lc = adts(fixtures.get("aac/lc-stereo-44k.aac"));
        s.he = adts(fixtures.get("aac/he-implicit.aac"));
        s.hev2 = adts(fixtures.get("aac/hev2-implicit.aac"));
        s.alac = track(fixtures.get("alac/alac-afconvert-16.m4a"));
        return s;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // SBR and PS signalling: hierarchical, backward compatible and implicit must all decode the same audio

    private static void signalling(Map<String, byte[]> fixtures) throws Exception {
        Sources s = sources(fixtures);
        Decoded adtsHe = decode(Way.ADTS, fixtures.get("aac/he-implicit.aac"), false);
        Mp4 hierarchical = s.he();
        // Backward compatible: the core's config, then sync extension 0x2b7, object type 5, SBR present, 48 kHz.
        Mp4 backward = s.he().with(m -> m.config = bits(2, 5, s.he.rateIndex(), 4, s.he.channelConfig(), 4, 0, 3,
                0x2b7, 11, 5, 5, 1, 1, 3, 4));
        // Implicit: nothing in the config, the first frame's SBR data shows it.
        Mp4 implicit = s.he().with(m -> m.config = asc(2, s.he.rateIndex(), s.he.channelConfig()));
        Map<String, Mp4> cases = new LinkedHashMap<>();
        cases.put("hierarchical", hierarchical);
        cases.put("backward compatible", backward);
        cases.put("implicit", implicit);
        for (Map.Entry<String, Mp4> e : cases.entrySet()) {
            byte[] file = e.getValue().build();
            dump("signal-he-" + e.getKey().replace(' ', '-') + ".m4a", file);
            Decoded d = within(20, e.getKey(), () -> decode(Way.MP4_STREAM, file, false));
            check(d.sampleRate == 48000 && d.channels == 2 && d.frames == adtsHe.frames && d.sha256.equals(adtsHe.sha256),
                    "HE-AAC with " + e.getKey() + " SBR signalling decodes like the ADTS stream (" + d.sampleRate
                            + " Hz, " + d.frames + " frames)");
        }
        // HE-AAC v2: object type 29 says SBR and PS; the plain core config leaves both to the first frame.
        Decoded adtsV2 = decode(Way.ADTS, fixtures.get("aac/hev2-implicit.aac"), false);
        Mp4 v2 = new Mp4(s.hev2.frames(), 1024);
        v2.config = bits(29, 5, s.hev2.rateIndex(), 4, 1, 4, 4, 4, 2, 5, 0, 3);
        v2.timescale = 22050;
        v2.channels = 1;
        Mp4 v2Implicit = v2.with(m -> m.config = asc(2, s.hev2.rateIndex(), 1));
        for (Mp4 m : List.of(v2, v2Implicit)) {
            byte[] file = m.build();
            dump("signal-hev2-" + (m == v2 ? "29" : "implicit") + ".m4a", file);
            Decoded d = within(20, "HE-AAC v2", () -> decode(Way.MP4_STREAM, file, false));
            check(d.sampleRate == 44100 && d.channels == 2 && d.frames == adtsV2.frames && d.sha256.equals(adtsV2.sha256),
                    "HE-AAC v2 with " + (m == v2 ? "object type 29" : "implicit") + " signalling decodes like ADTS");
        }
        // A config that says "no SBR" (sync extension with sbr_present 0) keeps the core rate even if SBR data come.
        Mp4 noSbr = s.he().with(m -> m.config = bits(2, 5, s.he.rateIndex(), 4, s.he.channelConfig(), 4, 0, 3,
                0x2b7, 11, 5, 5, 0, 1));
        byte[] noSbrFile = noSbr.build();
        Decoded core = within(20, "no SBR", () -> decode(Way.MP4_STREAM, noSbrFile, false));
        check(core.sampleRate == 24000 && core.frames == s.he.frames().size() * 1024,
                "a config that rules SBR out decodes the core only, at 24 kHz: " + core.sampleRate);
        // Perceptual noise substitution in a channel pair: with ms_mask_present 1 and a band's M/S flag set where
        // both channels are noise, the specification has the right channel reuse the left's random vector
        // (correlated noise, as OpenCORE, FAAD2 and libxaac do; ffmpeg does not). No encoder at hand writes that
        // combination, so the frames are written here: two noise bands at the same energy in both channels.
        for (boolean msUsed : new boolean[] {true, false}) {
            int flag = msUsed ? 1 : 0;
            byte[] frame = bits(1, 3, 0, 4, 1, 1, // CPE, tag 0, common window
                    0, 1, 0, 2, 0, 1, 2, 6, 0, 1, // ics_info: long window, sine, max_sfb 2, no prediction
                    1, 2, flag, 1, flag, 1, // ms_mask_present 1, both bands' flags
                    100, 8, 13, 4, 2, 5, 256, 9, 0, 1, 0, 1, 0, 1, 0, 1, // left: noise section, energies, no tools
                    100, 8, 13, 4, 2, 5, 256, 9, 0, 1, 0, 1, 0, 1, 0, 1, // right: the same
                    7, 3); // END
            byte[] pns = toAdts(java.util.Collections.nCopies(40, frame), 1, 4, 2, false);
            dump("pns-ms" + flag + ".aac", pns);
            Decoded d = decode(Way.ADTS, pns, false);
            boolean same = d.frames == 40 * 1024;
            double energy = 0;
            for (int i = 0; i < d.frames && same; i++) {
                same = Float.floatToRawIntBits(d.pcm[0][i]) == Float.floatToRawIntBits(d.pcm[1][i]);
            }
            for (int i = 0; i < d.frames; i++) {
                energy += d.pcm[0][i] * (double) d.pcm[0][i];
            }
            check(energy > 0 && same == msUsed, "PNS with the M/S flag " + (msUsed ? "set" : "clear")
                    + (msUsed ? " gives both channels the same noise" : " gives each channel its own noise"));
        }
        // CRC-protected ADTS headers decode exactly like unprotected ones.
        byte[] crc = toAdts(s.lc.frames(), s.lc.profile(), s.lc.rateIndex(), s.lc.channelConfig(), true);
        dump("lc-crc.aac", crc);
        Decoded withCrc = decode(Way.ADTS, crc, true);
        Decoded plain = decode(Way.ADTS, fixtures.get("aac/lc-stereo-44k.aac"), false);
        check(withCrc.sha256.equals(plain.sha256), "ADTS with CRC-protected headers decodes like without");
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Trimming: what to skip at the start and where to stop, as ffmpeg 9 reads it

    /**
     * A built file and where its decode must sit in the untrimmed decode of the same packets: skip frames in, length
     * frames long. Measured with ffmpeg 9 on exactly these bytes unless the note says otherwise.
     */
    private record Trim(String name, Mp4 file, Way way, int skip, int length, String note) {
    }

    private static void trimming(Map<String, byte[]> fixtures) throws Exception {
        Sources s = sources(fixtures);
        Mp4 lc = s.lc();
        lc.movieTimescale = 44100;
        int total = s.lc.frames().size() * 1024;
        Mp4 he = s.he();
        int heTotal = s.he.frames().size() * 2048;
        Mp4 alac = s.alac();
        int alacTotal = 0;
        for (int d : alac.durations) {
            alacTotal += d;
        }
        final int alacFrames = alacTotal;
        List<Trim> cases = new ArrayList<>();
        // Edit lists (ffmpeg's own muxer writes media time 1024 and the exact duration).
        cases.add(new Trim("edit 1024", lc.with(m -> m.edits = new long[][] {{1024, total - 2048}}), Way.MP4_STREAM,
                1024, total - 2048, null));
        cases.add(new Trim("edit in milliseconds", lc.with(m -> {
            m.movieTimescale = 1000;
            m.edits = new long[][] {{2112, 1000}};
        }), Way.MP4_STREAM, 2112, 44100, null));
        cases.add(new Trim("edit past the media", lc.with(m -> {
            m.durations[m.durations.length - 1] = 816;
            m.edits = new long[][] {{1024, total + 5000}};
        }), Way.MP4_STREAM, 1024, total - 208 - 1024, null));
        cases.add(new Trim("empty edit first", lc.with(m -> m.edits = new long[][] {{-1, 22050}, {1024, 30000}}),
                Way.MP4_STREAM, 1024, 30000, null));
        cases.add(new Trim("contiguous edits", lc.with(m -> m.edits = new long[][] {{1024, 20000}, {21024, 20000}}),
                Way.MP4_STREAM, 1024, 40000,
                "ffmpeg gives 40480 frames: it cuts edits after the first at packet granularity; the format plays"
                        + " the two edits as one stretch of 40000"));
        cases.add(new Trim("edit of duration 0 in a plain file", lc.with(m -> m.edits = new long[][] {{1024, 0}}),
                Way.MP4_STREAM, 1024, total - 1024,
                "ffmpeg plays nothing; only fragmented files write duration 0, meaning to the end, as read here"));
        // iTunes' gapless tag (afconvert and iTunes write it without an edit list).
        cases.add(new Trim("iTunSMPB", lc.with(m -> m.smpb = smpb(2112, 144, total - 2256)), Way.MP4_STREAM, 2112,
                total - 2256, null));
        cases.add(new Trim("iTunSMPB without priming", lc.with(m -> m.smpb = smpb(0, 1000, total - 1000)),
                Way.MP4_STREAM, 0, total - 1000, null));
        cases.add(new Trim("iTunSMPB with an implausible priming", lc.with(m -> m.smpb = smpb(20000, 144, 9000)),
                Way.MP4_STREAM, 0, total - 144, null));
        cases.add(new Trim("iTunSMPB claiming every sample", lc.with(m -> m.smpb = smpb(2112, 144, total)),
                Way.MP4_STREAM, 2112, total - 2112, null));
        cases.add(new Trim("iTunSMPB whose remainder disagrees", lc.with(m -> m.smpb = smpb(2112, 3000, 60000)),
                Way.MP4_STREAM, 2112, total - 5112, null));
        cases.add(new Trim("iTunSMPB and an edit list", lc.with(m -> {
            m.smpb = smpb(2112, 144, total - 2256);
            m.edits = new long[][] {{1024, 40000}};
        }), Way.MP4_STREAM, 1024, 40000,
                "ffmpeg starts at the tag's 2112 and still ends at the edit's end (41024): the edit list is the"
                        + " container's own word and wins here"));
        cases.add(new Trim("iTunSMPB with a second track after the audio", lc.with(m -> {
            m.smpb = smpb(2112, 144, total - 2256);
            m.otherTrackAfter = true;
        }), Way.MP4_STREAM, 2112, total - 2256,
                "ffmpeg keeps every sample: it gives the moov's tag to the last track it read, here the second one."
                        + " Apple's decoder trims, and so does ffmpeg once that track is removed"));
        cases.add(new Trim("iTunSMPB in HE-AAC, counted at the core rate", he.with(m -> m.smpb = smpb(2112, 552,
                s.he.frames().size() * 1024 - 2664)), Way.MP4_STREAM, 4224, heTotal - 4224 - 1104, null));
        cases.add(new Trim("iTunSMPB priming in ALAC", alac.with(m -> m.smpb = smpb(1000, 3280, 10000)),
                Way.MP4_STREAM, 0, alacFrames - 3280, "ffmpeg skips no priming in ALAC, which has no encoder delay"));
        // Neither: the last sample's duration and the media header's still trim the last frame.
        cases.add(new Trim("last sample shorter", lc.with(m -> m.durations[m.durations.length - 1] = 816),
                Way.MP4_STREAM, 0, total - 208, null));
        cases.add(new Trim("media header 500 short", lc.with(m -> {
            m.durations[m.durations.length - 1] = 816;
            m.mdhdDuration = total - 208 - 500;
        }), Way.MP4_STREAM, 0, total - 708, null));
        cases.add(new Trim("media header a frame short", lc.with(m -> m.mdhdDuration = total - 1100),
                Way.MP4_STREAM, 0, total, null));
        // Fragmented movies.
        Mp4 frag = lc.with(m -> m.fragmentPackets = 16);
        cases.add(new Trim("fragmented", frag, Way.MP4_STREAM, 0, total, null));
        cases.add(new Trim("fragmented, edit to the end", frag.with(m -> m.edits = new long[][] {{1024, 0}}),
                Way.MP4_STREAM, 1024, total - 1024, null));
        cases.add(new Trim("fragmented, edit with a duration", frag.with(m -> m.edits = new long[][] {{1024, 44100}}),
                Way.MP4_STREAM, 1024, 44100,
                "ffmpeg ignores the duration of an edit in a fragmented file (70656 frames); the format does not"));
        cases.add(new Trim("fragments after samples in the moov", lc.with(m -> {
            m.fragmentPackets = 16;
            m.moovPackets = 20;
            m.durations[19] = 816;
        }), Way.MP4_STREAM, 0, total, null));
        cases.add(new Trim("mvex but every sample in the moov", lc.with(m -> {
            m.fragmentPackets = 16;
            m.moovPackets = m.packets.size();
            m.durations[m.durations.length - 1] = 816;
            m.mdhdDuration = total - 208;
        }), Way.MP4_STREAM, 0, total - 208, null));
        cases.add(new Trim("mvex and a media duration of 0", lc.with(m -> {
            m.fragmentPackets = 16;
            m.moovPackets = m.packets.size();
            m.durations[m.durations.length - 1] = 816;
            m.mdhdDuration = 0;
        }), Way.MP4_STREAM, 0, total, null));
        cases.add(new Trim("last sample shorter, media duration unknown", lc.with(m -> {
            m.durations[m.durations.length - 1] = 816;
            m.mdhdDuration = 0xFFFFFFFFL;
        }), Way.MP4_STREAM, 0, total, null));
        // Edits that start the song late. Within a second and a frame of the start ffmpeg decodes from the first
        // packet; so it does for any edit of a fragmented file, and so does this for HE-AAC (see startNearEdit).
        cases.add(new Trim("edit a second and 1023 samples in", lc.with(m -> m.edits = new long[][] {{45123, 20000}}),
                Way.MP4_STREAM, 45123, 20000, null));
        cases.add(new Trim("fragmented, edit 1.2 s in", frag.with(m -> m.edits = new long[][] {{52920, 0}}),
                Way.MP4_STREAM, 52920, total - 52920, null));
        cases.add(new Trim("HE-AAC, edit 1.25 s in", he.with(m -> {
            m.movieTimescale = 48000;
            m.edits = new long[][] {{30000, 20000}};
        }), Way.MP4_STREAM, 60000, 20000,
                "ffmpeg starts its decoder a second before the edit, where SBR cannot yet rebuild envelopes coded"
                        + " against earlier frames; Apple's decoder equals a decode from the first packet"));
        byte[] bareLc = s.lc().build();
        byte[] bareHeFile = s.he().build();
        byte[] bareAlacFile = s.alac().build();
        dump("trim-bare-lc.m4a", bareLc);
        dump("trim-bare-he.m4a", bareHeFile);
        dump("trim-bare-alac.m4a", bareAlacFile);
        Decoded bare = decode(Way.MP4_STREAM, bareLc, false);
        Decoded bareHe = decode(Way.MP4_STREAM, bareHeFile, false);
        Decoded bareAlac = decode(Way.MP4_STREAM, bareAlacFile, false);
        check(bare.frames == total && bareHe.frames == heTotal && bareAlac.frames == alacFrames,
                "files without trimming information decode every sample: " + bare.frames + ", " + bareHe.frames
                        + ", " + bareAlac.frames);
        int index = 0;
        for (Trim t : cases) {
            byte[] file = t.file().build();
            dump("trim-" + (index++) + "-" + t.name().replaceAll("[^A-Za-z0-9]+", "-") + ".m4a", file);
            Decoded d = within(20, t.name(), () -> decode(t.way(), file, true));
            Decoded reference = t.file().format.equals("alac") ? bareAlac : t.file().sampleRate == 48000 ? bareHe : bare;
            boolean lines = d.frames == t.length() && samePcm(reference, t.skip(), d, 0, d.frames);
            check(lines, t.name() + ": " + d.frames + " frames starting " + t.skip() + " in (expected "
                    + t.length() + ")" + (t.note() == null ? "" : "; " + t.note()));
            if (t.way() == Way.MP4_STREAM && t.file().fragmentPackets == 0) {
                long expectedHint = Math.min(t.length(), reference.frames - t.skip());
                check(d.hint == expectedHint, t.name() + ": frame count hint " + d.hint + ", expected "
                        + expectedHint);
            }
        }
        lateEdits(s, lc);
    }

    /**
     * AAC LC and ALAC in a plain file whose edit starts a second and a frame or more in: decoding starts, as ffmpeg's
     * does, at the last packet that starts a second or more before the edit, with a decoder that has seen nothing. That
     * is exactly the decode of the same file without the packets before that one, its edit moved back by as much; ffmpeg
     * 9 gave the same samples as this on files whose noise bands show where it started (measured while the decoder was
     * written; those files are not kept).
     */
    private static void lateEdits(Sources s, Mp4 lc) throws Exception {
        for (int dropped = 1; dropped <= 2; dropped++) {
            int d = dropped;
            long start = 44100 + 1024L * d + (d == 2 ? 1023 : 0);
            Mp4 late = lc.with(m -> m.edits = new long[][] {{start, 20000}});
            Mp4 shorter = lc.with(m -> {
                m.packets = m.packets.subList(d, m.packets.size());
                m.durations = Arrays.copyOfRange(m.durations, d, m.durations.length);
                m.edits = new long[][] {{start - 1024L * d, 20000}};
            });
            dump("trim-late-" + start + ".m4a", late.build());
            Decoded a = within(20, "late edit " + start, () -> decode(Way.MP4_STREAM, late.build(), true));
            Decoded b = within(20, "late edit " + start + " without its first packets",
                    () -> decode(Way.MP4_STREAM, shorter.build(), true));
            check(a.frames == 20000 && a.hint == 20000 && a.sha256.equals(b.sha256), "edit " + start
                    + " samples in: decoding starts at packet " + d + " with a fresh decoder, as ffmpeg's does ("
                    + a.frames + " frames, the same samples as the file without its first " + d + ": "
                    + a.sha256.equals(b.sha256) + ")");
        }
        // ALAC keeps nothing from frame to frame, so a late start gives the very samples of a decode from the start.
        // Full frames only (the source's first four packets, again and again), as every encoder writes them.
        Mp4 alac = s.alac().with(m -> {
            m.packets = repeat(m.packets.subList(0, 4), 16);
            m.durations = new int[m.packets.size()];
            Arrays.fill(m.durations, 4096);
            m.movieTimescale = m.timescale;
        });
        Decoded whole = decode(Way.MP4_STREAM, alac.build(), false);
        long start = 61112;
        Decoded late = within(20, "ALAC late edit", () -> decode(Way.MP4_STREAM, alac.with(m -> m.edits =
                new long[][] {{start, 8000}}).build(), true));
        check(late.frames == 8000 && samePcm(whole, (int) start, late, 0, 8000),
                "ALAC with an edit " + start + " samples in starts late and still gives the samples from there on: "
                        + late.frames + " frames");
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Layouts that must all read the same audio

    private static void layouts(Map<String, byte[]> fixtures) throws Exception {
        Sources s = sources(fixtures);
        Mp4 lc = s.lc();
        Decoded reference = decode(Way.MP4_STREAM, lc.build(), false);
        Map<String, Mp4> cases = new LinkedHashMap<>();
        cases.put("index after the audio (by path)", lc.with(m -> m.moovFirst = false));
        cases.put("64-bit chunk offsets", lc.with(m -> m.co64 = true));
        cases.put("compact sample sizes", lc.with(m -> m.stz2 = true));
        cases.put("a 64-bit mdat size", lc.with(m -> m.largeMdat = true));
        cases.put("QuickTime sound description version 1", lc.with(m -> m.quickTimeV1 = true));
        cases.put("unknown boxes", lc.with(m -> m.junkBoxes = true));
        cases.put("another track first", lc.with(m -> m.otherTrackBefore = true));
        cases.put("one packet per chunk", lc.with(m -> m.chunkPackets = 1));
        cases.put("fragments of one packet", lc.with(m -> m.fragmentPackets = 1));
        for (Map.Entry<String, Mp4> e : cases.entrySet()) {
            byte[] file = e.getValue().build();
            dump("layout-" + e.getKey().replaceAll("[^A-Za-z0-9]+", "-") + ".m4a", file);
            Way way = e.getValue().moovFirst ? Way.MP4_STREAM : Way.MP4_PATH;
            Decoded d = within(20, e.getKey(), () -> decode(way, file, true));
            check(d.frames == reference.frames && d.sha256.equals(reference.sha256),
                    "layout with " + e.getKey() + " reads the same audio");
        }
        // The same packets in an ALAC file with its index after the audio.
        Mp4 alac = s.alac();
        Decoded alacReference = decode(Way.MP4_STREAM, alac.build(), false);
        byte[] alacLast = alac.with(m -> m.moovFirst = false).build();
        Decoded alacPath = decode(Way.MP4_PATH, alacLast, false);
        check(alacPath.sha256.equals(alacReference.sha256), "ALAC with its index after the audio reads the same by path");
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Refusals: formats that are not ours are named, damage is damage

    private static void refusals(Map<String, byte[]> fixtures) throws Exception {
        Sources s = sources(fixtures);
        Mp4 lc = s.lc();
        Map<String, Object[]> cases = new LinkedHashMap<>();
        cases.put("AAC Main", new Object[] {lc.with(m -> m.config = asc(1, 4, 2)), "unsupported: AAC Main"});
        cases.put("AAC LTP", new Object[] {lc.with(m -> m.config = asc(4, 4, 2)), "unsupported: AAC LTP"});
        cases.put("AAC SSR", new Object[] {lc.with(m -> m.config = asc(3, 4, 2)), "unsupported: AAC SSR"});
        cases.put("AAC-LD", new Object[] {lc.with(m -> m.config = bits(23, 5, 4, 4, 2, 4, 0, 3, 0, 2)),
                "unsupported: AAC-LD"});
        cases.put("AAC-ELD", new Object[] {lc.with(m -> m.config = bits(31, 5, 7, 6, 4, 4, 2, 4, 0, 8)),
                "unsupported: AAC-ELD"});
        cases.put("xHE-AAC", new Object[] {lc.with(m -> m.config = bits(31, 5, 10, 6, 4, 4, 2, 4, 0, 8)),
                "unsupported: xHE-AAC"});
        cases.put("960-sample frames", new Object[] {lc.with(m -> m.config = bits(2, 5, 4, 4, 2, 4, 1, 1, 0, 2)),
                "unsupported: AAC with 960-sample frames"});
        cases.put("no channel layout", new Object[] {lc.with(m -> m.config = bits(2, 5, 4, 4, 0, 4, 0, 3, 0, 32)),
                null});
        cases.put("MP3 in MP4", new Object[] {lc.with(m -> m.objectType = 0x6B), "unsupported: MP3 in MP4"});
        // Opus in MP4 is read now (native-audio-extras checks real files): AAC packets under an Opus sample entry
        // are damaged Opus, and a dOps box of a version nobody defined is a variant the decoder does not read.
        cases.put("Opus in MP4", new Object[] {lc.with(m -> {
            m.format = "Opus";
            m.configBox = "dOps";
            m.config = new byte[] {0, 2, 1, 56, 0, 0, (byte) 0xBB, (byte) 0x80, 0, 0, 0};
        }), "damaged: "});
        cases.put("Opus in MP4, dOps version 1", new Object[] {lc.with(m -> {
            m.format = "Opus";
            m.configBox = "dOps";
            m.config = new byte[] {1, 2, 1, 56, 0, 0, (byte) 0xBB, (byte) 0x80, 0, 0, 0};
        }), "unsupported: Opus in MP4 (dOps version 1)"});
        cases.put("AC-3 in MP4", new Object[] {lc.with(m -> {
            m.format = "ac-3";
            m.configBox = "dac3";
            m.config = new byte[] {0x10, 0x3D, (byte) 0xE0};
        }), "unsupported: AC-3"});
        cases.put("FairPlay-protected AAC", new Object[] {lc.with(m -> m.format = "drms"),
                "unsupported: DRM-protected AAC"});
        cases.put("no audio track", new Object[] {lc.with(m -> m.audio = false),
                "unsupported: MP4 without an audio track"});
        cases.put("ALAC version 1", new Object[] {s.alac().with(m -> {
            m.config = m.config.clone();
            m.config[4] = 1;
        }), "unsupported: ALAC version 1"});
        cases.put("ALAC of 12 bits", new Object[] {s.alac().with(m -> {
            m.config = m.config.clone();
            m.config[5] = 12;
        }), "damaged: "});
        for (Map.Entry<String, Object[]> e : cases.entrySet()) {
            byte[] file = ((Mp4) e.getValue()[0]).build();
            dump("refuse-" + e.getKey().replaceAll("[^A-Za-z0-9]+", "-") + ".m4a", file);
            String r = refusal(Way.MP4_STREAM, file, e.getKey());
            String expected = (String) e.getValue()[1];
            boolean ok = expected == null ? r.startsWith("unsupported: ") || r.startsWith("damaged: ")
                    : expected.endsWith(": ") ? r.startsWith(expected) : r.equals(expected);
            check(ok, e.getKey() + " is refused: " + r);
        }
        // ADTS profiles other than LC, and several raw data blocks per header.
        Adts a = s.lc;
        for (int profile : new int[] {0, 2, 3}) {
            byte[] other = toAdts(a.frames(), profile, a.rateIndex(), a.channelConfig(), false);
            String r = refusal(Way.ADTS, other, "ADTS profile " + profile);
            check(r.equals("unsupported: " + new String[] {"AAC Main", "", "AAC SSR", "AAC LTP"}[profile]),
                    "ADTS with profile " + profile + " is refused by name: " + r);
        }
        byte[] blocks = toAdts(a.frames(), a.profile(), a.rateIndex(), a.channelConfig(), false);
        blocks[6] |= 1;
        check(refusal(Way.ADTS, blocks, "ADTS blocks").equals("unsupported: ADTS with several AAC frames per header"),
                "ADTS with several raw data blocks per header is refused by name");
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Damage

    /** Flips bytes, cuts out and copies in runs, inserts junk, truncates, and smashes 32-bit fields in the headers. */
    private static byte[] mutate(byte[] original, Random r) {
        byte[] d = original.clone();
        int kind = r.nextInt(6);
        if (kind == 0 || kind == 4) {
            int flips = 1 + r.nextInt(kind == 4 ? 2000 : 40);
            for (int i = 0; i < flips; i++) {
                d[r.nextInt(d.length)] ^= (byte) (1 << r.nextInt(8));
            }
        }
        if (kind == 1) {
            int at = r.nextInt(d.length);
            int len = r.nextInt(Math.min(3000, d.length - at) + 1);
            d = cat(Arrays.copyOfRange(d, 0, at), Arrays.copyOfRange(d, at + len, d.length));
        }
        if (kind == 2) {
            int at = r.nextInt(d.length);
            byte[] noise = new byte[1 + r.nextInt(5000)];
            r.nextBytes(noise);
            d = cat(Arrays.copyOfRange(d, 0, at), noise, Arrays.copyOfRange(d, at, d.length));
        }
        if (kind == 3) {
            int from = r.nextInt(d.length);
            int to = r.nextInt(d.length);
            int len = Math.min(r.nextInt(4000), Math.min(d.length - from, d.length - to));
            System.arraycopy(original, from, d, to, len);
            d = Arrays.copyOf(d, Math.max(1, d.length - r.nextInt(d.length / 2)));
        }
        if (kind == 5) {
            int words = 1 + r.nextInt(6);
            for (int i = 0; i < words; i++) {
                int at = r.nextInt(Math.max(1, Math.min(d.length, 4096) - 4));
                int v = switch (r.nextInt(5)) {
                    case 0 -> 0;
                    case 1 -> -1;
                    case 2 -> 1;
                    case 3 -> r.nextInt(64);
                    default -> r.nextInt();
                };
                for (int b = 0; b < 4 && at + b < d.length; b++) {
                    d[at + b] = (byte) (v >>> (24 - 8 * b));
                }
            }
        }
        return d;
    }

    private static void damage(Map<String, byte[]> fixtures) throws Exception {
        check(refusal(Way.ADTS, new byte[0], "empty ADTS").startsWith("damaged: "), "an empty .aac is damage");
        check(refusal(Way.MP4_STREAM, new byte[0], "empty MP4").startsWith("damaged: "), "an empty .m4a is damage");
        check(refusal(Way.MP4_PATH, new byte[0], "empty MP4 file").startsWith("damaged: "),
                "an empty .m4a file is damage");
        Random random = new Random(42);
        byte[] noise = new byte[40000];
        random.nextBytes(noise);
        check(refusal(Way.ADTS, noise, "noise as ADTS").startsWith("damaged: "), "random bytes are not ADTS");
        check(refusal(Way.MP4_STREAM, noise, "noise as MP4").startsWith("damaged: "), "random bytes are not MP4");
        // Sizes that would ask for a lot of memory are refused before it is taken.
        byte[] hugeMoov = cat(box("ftyp", ascii("M4A "), be(0, 4)), be(0x7FFFFFF0L, 4), ascii("moov"), new byte[64]);
        check(refusal(Way.MP4_STREAM, hugeMoov, "huge moov").startsWith("damaged: "),
                "a moov that claims 2 GiB is refused");
        Sources s = sources(fixtures);
        byte[] plain = s.lc().build();
        byte[] lying = plain.clone();
        int stsz = indexOf(lying, "stsz");
        System.arraycopy(be(50_000_000, 4), 0, lying, stsz + 16, 4);
        check(refusal(Way.MP4_STREAM, lying, "lying stsz").startsWith("damaged: "),
                "a sample table that claims 50 million entries is refused");
        // A track run whose 32-bit count claims 50 million samples, with sizes listed: more than its box holds.
        byte[] fragmented = s.lc().with(m -> m.fragmentPackets = 16).build();
        byte[] longRun = fragmented.clone();
        int trun = indexOf(longRun, "trun");
        System.arraycopy(be(50_000_000, 4), 0, longRun, trun + 12, 4);
        check(refusal(Way.MP4_STREAM, longRun, "long trun").startsWith("damaged: "),
                "a track run that claims 50 million listed samples is refused");
        // The same count with no sizes listed and no default size: 50 million empty samples, which hold no frames.
        // They must be passed over quickly and hold no memory per sample.
        byte[] emptyRun = longRun.clone();
        System.arraycopy(be(0x000001, 4), 0, emptyRun, trun + 8, 4);
        long started = System.nanoTime();
        String r = refusal(Way.MP4_STREAM, emptyRun, "empty run");
        double ms = (System.nanoTime() - started) / 1e6;
        check((r.equals("ok") || r.startsWith("damaged: ")) && ms < 5000,
                "a run of 50 million empty samples ends in " + Math.round(ms) + " ms: " + r);
        workBoundedByFile(s, fragmented);
        int survived = 0;
        int runs = 0;
        for (int seed = 0; seed < 17 * 8; seed++) {
            Fixture f = FIXTURES.get(seed % FIXTURES.size());
            byte[] mutated = mutate(fixtures.get(f.file()), new Random(seed));
            runs++;
            if (!survive(f.way(), mutated, "fuzz " + f.file() + " seed " + seed).equals("threw")) {
                survived++;
            }
            if (isMp4(f)) {
                runs++;
                if (!survive(Way.MP4_PATH, mutated, "fuzz by path " + f.file() + " seed " + seed).equals("threw")) {
                    survived++;
                }
            }
        }
        check(survived == runs, "damaged files end in a decode or an AudioFormatException: " + survived + "/" + runs);
        int clean = 0;
        int cuts = 0;
        for (Fixture f : FIXTURES) {
            byte[] data = fixtures.get(f.file());
            int step = Math.max(97, data.length / 40);
            for (int cut = 0; cut < data.length; cut += step) {
                cuts++;
                byte[] head = Arrays.copyOf(data, cut);
                Way way = f.way() == Way.MP4_STREAM && cut % 2 == 0 ? Way.MP4_PATH : f.way();
                if (!survive(way, head, f.file() + " cut at " + cut).equals("threw")) {
                    clean++;
                }
            }
        }
        check(clean == cuts, "a file cut anywhere ends cleanly: " + clean + "/" + cuts);
    }

    /**
     * Files whose few bytes claim a great deal of work: no open() or read() may take long over them, because the
     * conversion's time limit is only looked at between reads. Each case once kept one call busy for minutes or hours.
     */
    private static void workBoundedByFile(Sources s, byte[] fragmented) throws Exception {
        // 2000 fragments after the real ones, each a run of 49 999 999 empty samples: 89 ms apiece when they were
        // walked one by one.
        ByteArrayOutputStream empties = new ByteArrayOutputStream();
        empties.writeBytes(fragmented);
        for (int i = 0; i < 2000; i++) {
            empties.writeBytes(box("moof", full("mfhd", 0, 0, be(1000 + i, 4)), box("traf",
                    full("tfhd", 0, 0x020010, be(1, 4), be(0, 4)), full("trun", 0, 0, be(49_999_999, 4)))));
            empties.writeBytes(box("mdat"));
        }
        Decoded real = decode(Way.MP4_STREAM, fragmented, false);
        for (Way way : List.of(Way.MP4_STREAM, Way.MP4_PATH)) {
            long started = System.nanoTime();
            Decoded d = within(20, "empty fragments " + way, () -> decode(way, empties.toByteArray(), false));
            long ms = (System.nanoTime() - started) / 1_000_000;
            check(d.sha256.equals(real.sha256) && ms < 2000, way + ": 2000 fragments of 50 million empty samples each"
                    + " after the audio are passed over in " + ms + " ms, the audio unchanged");
        }
        // Chunks or runs that all point at the same 4096 one-byte packets (each an AAC frame of silence), with an edit
        // list that skips all but the last ten packets: before, one read() decoded every packet up to the edit.
        byte[] lcConfig = asc(2, 4, 2);
        byte[] heConfig = bits(5, 5, 7, 4, 2, 4, 4, 4, 2, 5, 0, 3);
        Object[][] cases = {
                {"plain, 50 million packets in chunks over the same bytes", sameBytesPlain(lcConfig,
                        (50_000_000L - 1 - 10) * 1024), 10240},
                {"fragmented, 2000 runs over the same bytes", sameBytesFragmented(lcConfig, (2000L * 4096 - 10) * 1024),
                        10240},
                {"fragmented HE-AAC, 2000 runs over the same bytes", sameBytesFragmented(heConfig,
                        (2000L * 4096 - 10) * 2048), 20480}};
        for (Object[] c : cases) {
            byte[] file = (byte[]) c[1];
            dump("bounded-" + ((String) c[0]).replaceAll("[^A-Za-z0-9]+", "-") + ".m4a", file);
            long started = System.nanoTime();
            Decoded d = within(20, (String) c[0], () -> decode(Way.MP4_PATH, file, false));
            long ms = (System.nanoTime() - started) / 1_000_000;
            check(d.frames == (int) c[2] && ms < 2000, c[0] + ", edit before the last ten: " + d.frames + " frames in "
                    + ms + " ms");
        }
        // A moov of size 0 is the last box and runs to the end of the file (ISO/IEC 14496-12, 4.2), as ffmpeg reads it.
        byte[] moovLast = s.lc().with(m -> m.moovFirst = false).build();
        byte[] sizeless = moovLast.clone();
        System.arraycopy(be(0, 4), 0, sizeless, find(sizeless, "moov").body() - 8, 4);
        Decoded whole = decode(Way.MP4_PATH, moovLast, false);
        Decoded toEnd = decode(Way.MP4_PATH, sizeless, false);
        check(toEnd.frames == whole.frames && toEnd.sha256.equals(whole.sha256),
                "a moov of size 0 at the end of the file is read to the end: " + toEnd.frames + " frames");
        String r = refusal(Way.MP4_STREAM, sizeless, "moov of size 0 by stream");
        check(r.startsWith("damaged: ") && r.contains("by path"), "and read once as a stream it asks to be opened by"
                + " path, as for any moov after the audio: " + r);
    }

    /** A plain file whose chunks all point at the same 4096 one-byte packets, moov last; the edit as given. */
    private static byte[] sameBytesPlain(byte[] config, long editStart) {
        byte[] ftyp = box("ftyp", ascii("M4A "), be(0, 4), ascii("M4A "), ascii("isom"));
        byte[] region = new byte[4096];
        Arrays.fill(region, (byte) 0xE0);
        long count = 50_000_000L - 1;
        int chunks = (int) ((count + region.length - 1) / region.length);
        ByteArrayOutputStream stco = new ByteArrayOutputStream();
        for (int i = 0; i < chunks; i++) {
            stco.writeBytes(be(ftyp.length + 8, 4));
        }
        byte[] stbl = box("stbl", full("stsd", 0, 0, be(1, 4), mp4a(config)),
                full("stts", 0, 0, be(1, 4), be(count, 4), be(1024, 4)),
                full("stsc", 0, 0, be(1, 4), be(1, 4), be(region.length, 4), be(1, 4)),
                full("stsz", 0, 0, be(1, 4), be(count, 4)), full("stco", 0, 0, be(chunks, 4), stco.toByteArray()));
        return cat(ftyp, box("mdat", region), sameBytesMoov(stbl, editStart, false));
    }

    /** A fragmented file whose 2000 runs all read the same 4096 one-byte packets through their tfhd's base offset. */
    private static byte[] sameBytesFragmented(byte[] config, long editStart) {
        byte[] ftyp = box("ftyp", ascii("M4A "), be(0, 4), ascii("M4A "), ascii("isom"));
        byte[] stbl = box("stbl", full("stsd", 0, 0, be(1, 4), mp4a(config)), full("stts", 0, 0, be(0, 4)),
                full("stsc", 0, 0, be(0, 4)), full("stsz", 0, 0, be(0, 4), be(0, 4)), full("stco", 0, 0, be(0, 4)));
        byte[] moov = sameBytesMoov(stbl, editStart, true);
        byte[] region = new byte[4096];
        Arrays.fill(region, (byte) 0xE0);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(cat(ftyp, moov, box("mdat", region)));
        for (int i = 0; i < 2000; i++) {
            // tfhd: base-data-offset-present and default-sample-size-present; the run lists nothing per sample.
            out.writeBytes(box("moof", full("mfhd", 0, 0, be(i + 1, 4)), box("traf", full("tfhd", 0, 0x000011,
                    be(1, 4), be(ftyp.length + moov.length + 8, 8), be(1, 4)), full("trun", 0, 0, be(region.length, 4)))));
        }
        return out.toByteArray();
    }

    private static byte[] sameBytesMoov(byte[] stbl, long editStart, boolean fragmented) {
        byte[] trak = box("trak", full("tkhd", 0, 7, be(0, 4), be(0, 4), be(1, 4), be(0, 4), be(0, 4), new byte[60]),
                box("edts", full("elst", 1, 0, be(1, 4), be(0, 8), be(editStart, 8), be(0x10000, 4))),
                box("mdia", full("mdhd", 0, 0, be(0, 4), be(0, 4), be(44100, 4), be(0, 4), be(0x55C4, 2), be(0, 2)),
                        full("hdlr", 0, 0, be(0, 4), ascii("soun"), new byte[12], new byte[1]),
                        box("minf", full("smhd", 0, 0, be(0, 4)), box("dinf", full("dref", 0, 0, be(1, 4),
                                full("url ", 0, 1))), stbl)));
        byte[] mvex = fragmented ? box("mvex", full("trex", 0, 0, be(1, 4), be(1, 4), be(1024, 4), be(1, 4), be(0, 4)))
                : new byte[0];
        return box("moov", full("mvhd", 0, 0, be(0, 4), be(0, 4), be(44100, 4), be(0, 4), be(0x10000, 4), be(0x100, 2),
                new byte[10], new byte[36], new byte[24], be(2, 4)), trak, mvex);
    }

    /** An mp4a sample entry for 44.1 kHz stereo AAC with this AudioSpecificConfig. */
    private static byte[] mp4a(byte[] config) {
        byte[] esds = full("esds", 0, 0, descriptor(3, be(1, 2), new byte[] {0}, descriptor(4, new byte[] {0x40, 0x15},
                be(0, 3), be(128000, 4), be(128000, 4), descriptor(5, config)), descriptor(6, new byte[] {2})));
        return box("mp4a", new byte[6], be(1, 2), new byte[8], be(2, 2), be(16, 2), be(0, 4), be(44100L << 16, 4), esds);
    }

    private static int indexOf(byte[] data, String type) {
        byte[] t = ascii(type);
        outer:
        for (int i = 4; i + 4 <= data.length; i++) {
            for (int k = 0; k < 4; k++) {
                if (data[i + k] != t[k]) {
                    continue outer;
                }
            }
            return i - 4;
        }
        return -1;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Sniffing

    private static byte[] head(byte[] file) {
        return Arrays.copyOf(file, Math.min(64, file.length));
    }

    private static void sniffing(Map<String, byte[]> fixtures) throws Exception {
        for (Fixture f : FIXTURES) {
            byte[] h = head(fixtures.get(f.file()));
            boolean mp4 = Mp4Decoder.looksLike(h);
            boolean adts = AdtsDecoder.looksLike(h);
            check(isMp4(f) ? mp4 && !adts : adts && !mp4, f.file() + " is sniffed as " + (isMp4(f) ? "MP4" : "ADTS")
                    + " only");
        }
        byte[] wav = cat(ascii("RIFF"), be(0, 4), ascii("WAVEfmt "), new byte[40]);
        byte[] flac = cat(ascii("fLaC"), new byte[40]);
        byte[] ogg = cat(ascii("OggS"), new byte[60]);
        byte[] id3 = cat(ascii("ID3"), new byte[] {3, 0, 0, 0, 0, 0, 20}, new byte[40]);
        byte[] mp3 = cat(new byte[] {(byte) 0xFF, (byte) 0xFB, (byte) 0x90, 0x64}, new byte[60]);
        byte[] text = "this is not audio at all, just text\n".repeat(3).getBytes(StandardCharsets.US_ASCII);
        for (byte[] other : List.of(wav, flac, ogg, id3, mp3, text)) {
            check(!Mp4Decoder.looksLike(head(other)) && !AdtsDecoder.looksLike(head(other)),
                    "neither claims " + new String(other, 0, 4, StandardCharsets.ISO_8859_1).trim());
        }
        check(Mp4Decoder.looksLike(head(cat(box("free", new byte[4]), box("moov")))),
                "an old QuickTime file that starts with free is MP4");
        check(!Mp4Decoder.looksLike(new byte[3]) && !AdtsDecoder.looksLike(new byte[3]) && !Mp4Decoder.looksLike(null)
                && !AdtsDecoder.looksLike(null), "short or missing heads are nothing");
        // ADTS has only a seven-byte signature: a caller that can read further confirms with findsFrames, which
        // follows the chain of frame lengths.
        for (Fixture f : FIXTURES) {
            if (!isMp4(f)) {
                byte[] data = fixtures.get(f.file());
                check(AdtsDecoder.findsFrames(Arrays.copyOf(data, Math.min(data.length, AdtsDecoder.SYNC_PROBE_BYTES))),
                        f.file() + ": its frames are found");
            }
        }
        byte[] oneFrame = Arrays.copyOf(fixtures.get("aac/lc-mono-22k.aac"), 7 + (((fixtures.get("aac/lc-mono-22k.aac")[3]
                & 3) << 11 | (fixtures.get("aac/lc-mono-22k.aac")[4] & 0xFF) << 3
                | (fixtures.get("aac/lc-mono-22k.aac")[5] & 0xFF) >> 5) - 7));
        check(AdtsDecoder.findsFrames(oneFrame), "a file of a single ADTS frame is found");
        check(!AdtsDecoder.findsFrames(cat(Arrays.copyOf(oneFrame, 7), new byte[4000])),
                "an ADTS header followed by zeros is not taken for a stream");
        Random random = new Random(7);
        int mp4Alarms = 0;
        int adtsAlarms = 0;
        for (int i = 0; i < 2000; i++) {
            byte[] noise = new byte[AdtsDecoder.SYNC_PROBE_BYTES];
            random.nextBytes(noise);
            if (i % 2 == 0) {
                // Half of them start like an ADTS header, the case findsFrames is there for.
                noise[0] = (byte) 0xFF;
                noise[1] = (byte) 0xF1;
            }
            if (Mp4Decoder.looksLike(head(noise))) {
                mp4Alarms++;
            }
            if (AdtsDecoder.findsFrames(noise)) {
                adtsAlarms++;
            }
        }
        check(mp4Alarms == 0 && adtsAlarms == 0, "random bytes are taken for neither: MP4 " + mp4Alarms + ", ADTS "
                + adtsAlarms + " of 2000");
        adtsAfterJunk(fixtures);
    }

    /**
     * Raw AAC that does not start with a frame, as a recording of a broadcast that starts part way into one: the first
     * frame is found where a chain of frames starts (what AudioDecoders.detect() relies on), and the decoder starts at
     * that same frame, past a false start that chains only two headers.
     */
    private static void adtsAfterJunk(Map<String, byte[]> fixtures) throws Exception {
        Fixture clean = FIXTURES.stream().filter(f -> f.file().equals("aac/lc-stereo-44k.aac")).findFirst().orElseThrow();
        byte[] stream = fixtures.get(clean.file());
        int firstFrame = ((stream[3] & 3) << 11) | ((stream[4] & 0xFF) << 3) | ((stream[5] & 0xFF) >> 5);
        Random random = new Random(11);
        byte[] noise = new byte[100];
        random.nextBytes(noise);
        // Two headers of the stream's own kind whose 20-byte frames chain, then zeros: a false start for a decoder
        // that believes a header once the next one fits.
        byte[] fake = Arrays.copyOf(stream, 7);
        fake[3] = (byte) (fake[3] & ~3);
        fake[4] = (byte) (20 >> 3);
        fake[5] = (byte) ((20 & 7) << 5 | (fake[5] & 0x1F));
        byte[] falseStart = cat(fake, new byte[13], fake, new byte[13], new byte[30]);
        byte[] cutFrame = Arrays.copyOfRange(stream, 3, firstFrame);
        Map<String, byte[]> leads = new LinkedHashMap<>();
        leads.put("100 random bytes", noise);
        leads.put("64 zero bytes", new byte[64]);
        leads.put("a false start", falseStart);
        leads.put("the end of a frame", cutFrame);
        for (Map.Entry<String, byte[]> lead : leads.entrySet()) {
            byte[] file = cat(lead.getValue(), stream);
            byte[] probe = Arrays.copyOf(file, Math.min(file.length, AdtsDecoder.SCAN_BYTES + AdtsDecoder.SYNC_PROBE_BYTES));
            int found = AdtsDecoder.findFrameStart(probe, AdtsDecoder.SCAN_BYTES);
            Decoded d = within(20, "ADTS after " + lead.getKey(), () -> decode(Way.ADTS, file, true));
            check(found == lead.getValue().length && d.frames == clean.frames() && d.sha256.equals(clean.sha256()),
                    "ADTS after " + lead.getKey() + " starts at " + found + " (expected " + lead.getValue().length
                            + ") and decodes as without it: " + d.frames + " frames");
        }
        check(AdtsDecoder.findFrameStart(stream, AdtsDecoder.SCAN_BYTES) == 0, "a clean ADTS stream starts at 0");
        // One frame that ends with the file counts at the start of a file, never after junk.
        byte[] lone = Arrays.copyOf(stream, firstFrame);
        check(AdtsDecoder.findFrameStart(lone, AdtsDecoder.SCAN_BYTES) == 0
                        && AdtsDecoder.findFrameStart(cat(noise, lone), AdtsDecoder.SCAN_BYTES) == -1,
                "a lone frame is a stream at the start of a file but not after junk");
        int alarms = 0;
        for (int i = 0; i < 300; i++) {
            byte[] junk = new byte[AdtsDecoder.SCAN_BYTES + AdtsDecoder.SYNC_PROBE_BYTES];
            random.nextBytes(junk);
            for (int at = 0; at + 1 < junk.length; at += 1 + random.nextInt(2000)) {
                // Plenty of sync words, each with a header that passes on its own now and then.
                junk[at] = (byte) 0xFF;
                junk[at + 1] = (byte) 0xF1;
            }
            if (AdtsDecoder.findFrameStart(junk, AdtsDecoder.SCAN_BYTES) >= 0) {
                alarms++;
            }
        }
        check(alarms == 0, "88 KiB of random bytes salted with sync words is never taken for ADTS: " + alarms
                + " of 300");
    }

    /**
     * hev2-music.aac: its length (ffmpeg's too; the decode is 125.3 dB from ffmpeg's at lag 0) and decode, and the
     * decodes of it cut at the bytes in STEREO_CUTS. ffmpeg is no reference for the cuts: it takes the first sync word
     * of the cut frame for a header, and the false frame swallows real ones (2.3 s of the cut at byte 59).
     */
    private static final int HEV2_MUSIC_FRAMES = 227328;
    private static final String HEV2_MUSIC_SHA256 = "447ae49d7fa8953db7f26cb0242a27438bcb377302ec5920a68d93b62982db12";
    private static final int[] STEREO_CUTS = {59, 4000};
    private static final String[] STEREO_CUT_SHA256 = {
            "3ee7d970d97dd8a31f813b85d3366a695c541020d427b30c58ceb713e567e99b",
            "42bdaf412a1ebc71a594596fe0ff5bd171bbeae50f362fd92efc7d9c6ecff070"};

    /**
     * HE-AAC v2 that starts part way into the stream, as a recording of a broadcast does. Apple's encoder sends the
     * parametric stereo header in the frame before every SBR header, about once a second, so a decoder that starts
     * anywhere else meets PS data before a header that says how to read it, and has to pass over it until one comes.
     * Read with guessed modes, as it once was, that data parsed as garbage: each SBR header was followed by an error
     * that turned SBR off again, so the PS header one frame before the next SBR header was never read. The decodes of
     * this fixture cut at bytes 59 and 4000 then kept 18% of the upper band's energy to the end, one of them with a
     * wrong stereo image and the other in mono. Measured against the whole file's decode over the last 1.5 s, aligned
     * by the ends: the SBR band (energy of first differences) and the stereo image (energy of left minus right) must be
     * back, and the samples close; SBR's noise generator runs on from where each decode started, so they are not equal.
     */
    private static void stereoAfterCut(Path fixtureDir) throws Exception {
        byte[] whole = Files.readAllBytes(fixtureDir.resolve("aac/hev2-music.aac"));
        Decoded w = within(20, "hev2-music.aac", () -> decode(Way.ADTS, whole, true));
        check(w.frames == HEV2_MUSIC_FRAMES && w.sha256.equals(HEV2_MUSIC_SHA256),
                "hev2-music.aac decodes as recorded: " + w.frames + " frames, sha256 " + w.sha256);
        // The file starts with a frame, so its frames are where the lengths chain from 0.
        List<Integer> starts = new ArrayList<>();
        for (int p = 0; p + 7 <= whole.length; p += ((whole[p + 3] & 3) << 11) | ((whole[p + 4] & 0xFF) << 3)
                | ((whole[p + 5] & 0xFF) >> 5)) {
            starts.add(p);
        }
        for (int i = 0; i < STEREO_CUTS.length; i++) {
            int cut = STEREO_CUTS[i];
            byte[] rest = Arrays.copyOfRange(whole, cut, whole.length);
            Decoded c = within(20, "hev2-music.aac cut at " + cut, () -> decode(Way.ADTS, rest, true));
            long kept = starts.stream().filter(s -> s >= cut).count();
            check(c.frames == kept * 2048, "hev2-music.aac cut at byte " + cut + " decodes every whole frame after the cut ("
                    + kept + "): " + c.frames + " frames");
            int d = w.frames - c.frames;
            int n = Math.min(c.frames, 66150);
            double signal = 0;
            double error = 0;
            double upperWhole = 0;
            double upperCut = 0;
            double sideWhole = 0;
            double sideCut = 0;
            for (int k = Math.max(1, c.frames - n); k < c.frames && d >= 0; k++) {
                for (int ch = 0; ch < 2; ch++) {
                    double x = w.pcm[ch][k + d];
                    double y = c.pcm[ch][k];
                    signal += x * x;
                    error += (x - y) * (x - y);
                    double dx = x - w.pcm[ch][k + d - 1];
                    double dy = y - c.pcm[ch][k - 1];
                    upperWhole += dx * dx;
                    upperCut += dy * dy;
                }
                double sw = w.pcm[0][k + d] - w.pcm[1][k + d];
                double sc = c.pcm[0][k] - c.pcm[1][k];
                sideWhole += sw * sw;
                sideCut += sc * sc;
            }
            double snr = 10 * Math.log10(signal / Math.max(error, 1e-30));
            double upper = upperCut / Math.max(upperWhole, 1e-30);
            double side = sideCut / Math.max(sideWhole, 1e-30);
            check(snr > 30 && Math.abs(upper - 1) < 0.05 && Math.abs(side - 1) < 0.05, String.format(Locale.ROOT,
                    "hev2-music.aac cut at byte %d has its SBR band and stereo image back by the end (last 1.5 s against"
                            + " the whole decode: %.1f dB, upper band energy x%.3f, side energy x%.3f)", cut, snr, upper,
                    side));
            check(c.sha256.equals(STEREO_CUT_SHA256[i]), "hev2-music.aac cut at byte " + cut
                    + ": the decode is unchanged (sha256 " + c.sha256 + ")");
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Interrupts and the stream contract

    private static void interrupts(Map<String, byte[]> fixtures) throws Exception {
        Map<String, Object[]> cases = new LinkedHashMap<>();
        cases.put("ADTS", new Object[] {Way.ADTS, fixtures.get("aac/he-implicit.aac")});
        cases.put("MP4 stream", new Object[] {Way.MP4_STREAM, fixtures.get("aac/hev2-afconvert.m4a")});
        cases.put("MP4 by path", new Object[] {Way.MP4_PATH, fixtures.get("aac/lc-ffmpeg-tools.m4a")});
        cases.put("ALAC", new Object[] {Way.MP4_PATH, fixtures.get("alac/alac-afconvert-16.m4a")});
        cases.put("fragmented MP4", new Object[] {Way.MP4_STREAM, fixtures.get("aac/lc-fragmented.m4a")});
        for (Map.Entry<String, Object[]> e : cases.entrySet()) {
            Way way = (Way) e.getValue()[0];
            byte[] data = (byte[]) e.getValue()[1];
            // The file is written before the interrupt: writing it is the test's business, not the decoder's.
            Path file = scratch.resolve("interrupt.m4a");
            Files.write(file, data);
            String result = within(20, e.getKey() + " interrupt", () -> {
                Thread.currentThread().interrupt();
                try (PcmStream s = way == Way.MP4_PATH ? Mp4Decoder.open(file)
                        : way == Way.ADTS ? AdtsDecoder.open(new Trickle(data, 5))
                        : Mp4Decoder.open(new Trickle(data, 5))) {
                    return "opened";
                } catch (InterruptedIOException expected) {
                    return "interrupted";
                } finally {
                    Thread.interrupted();
                }
            });
            check(result.equals("interrupted"), e.getKey() + ": an interrupted open throws InterruptedIOException");
            result = within(20, e.getKey() + " interrupt while reading", () -> {
                try (PcmStream s = way == Way.MP4_PATH ? Mp4Decoder.open(file) : open(way, data, true).stream()) {
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
            Files.deleteIfExists(file);
        }
    }

    private static void streamContract(Map<String, byte[]> fixtures) throws Exception {
        for (Way way : Way.values()) {
            byte[] data = fixtures.get(way == Way.ADTS ? "aac/lc-mono-22k.aac" : "alac/alac-afconvert-16.m4a");
            Opened o = open(way, data, false);
            try (PcmStream s = o.stream()) {
                float[][] buf = new float[s.channels()][100];
                check(s.read(buf, 0, 0) == 0, way + ": asking for no frames reads none");
                while (s.read(buf, 0, 100) > 0) {
                    // drain
                }
                check(s.read(buf, 0, 100) == -1 && s.read(buf, 0, 100) == -1, way + ": the end stays the end");
            } finally {
                if (o.file() != null) {
                    Files.deleteIfExists(o.file());
                }
            }
        }
        // A failed open leaves the input to the caller: it is not closed.
        Trickle t = new Trickle(new byte[100], 1);
        try (PcmStream s = AdtsDecoder.open(t)) {
            check(false, "100 zero bytes are not ADTS");
        } catch (AudioFormatException expected) {
            check(!t.closed, "a refused ADTS stream is left open for its caller");
        }
        t = new Trickle(new byte[100], 1);
        try (PcmStream s = Mp4Decoder.open(t)) {
            check(false, "100 zero bytes are not MP4");
        } catch (AudioFormatException expected) {
            check(!t.closed, "a refused MP4 stream is left open for its caller");
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Speed and memory

    private static byte[] repeat(byte[] one, int copies) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(one.length * copies);
        for (int i = 0; i < copies; i++) {
            out.writeBytes(one);
        }
        return out.toByteArray();
    }

    private static <T> List<T> repeat(List<T> one, int copies) {
        List<T> out = new ArrayList<>(one.size() * copies);
        for (int i = 0; i < copies; i++) {
            out.addAll(one);
        }
        return out;
    }

    /** Decodes and counts frames only, the way the encoder will read: as fast as the decoder goes. */
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
        Sources s = sources(fixtures);
        // Four minutes of stereo of each kind: the fixtures again and again, which is still one valid stream.
        Map<String, Object[]> songs = new LinkedHashMap<>();
        songs.put("AAC LC (ADTS)", new Object[] {Way.ADTS, repeat(fixtures.get("aac/lc-stereo-44k.aac"), 148)});
        Mp4 lc = s.lc().with(m -> {
            m.packets = repeat(m.packets, 148);
            m.durations = new int[m.packets.size()];
            Arrays.fill(m.durations, 1024);
        });
        songs.put("AAC LC (M4A by path)", new Object[] {Way.MP4_PATH, lc.build()});
        songs.put("HE-AAC (ADTS)", new Object[] {Way.ADTS, repeat(fixtures.get("aac/he-implicit.aac"), 138)});
        songs.put("HE-AAC v2 (ADTS)", new Object[] {Way.ADTS, repeat(fixtures.get("aac/hev2-implicit.aac"), 140)});
        Mp4 alac = s.alac().with(m -> {
            int[] one = m.durations;
            m.packets = repeat(m.packets, 534);
            m.durations = new int[m.packets.size()];
            for (int i = 0; i < m.durations.length; i++) {
                // Every copy keeps its short last packet, which the ALAC frames themselves say.
                m.durations[i] = one[i % one.length];
            }
        });
        songs.put("ALAC (M4A by path)", new Object[] {Way.MP4_PATH, alac.build()});
        // 1.5 s of wall time for four minutes of stereo, as on the Mac this was written on. That Mac is often busy
        // with a game while the checks run (load average 10-16), and then a decode both waits for a core and may run
        // on the slower of its two core types: the same cold HE-AAC decode took 1273 ms (1130 ms of CPU time) in one
        // run and 1690 ms (1614 ms of CPU time) twenty minutes later. So a warm decode is timed up to three times and
        // the best one counts, and the cold one, which happens once per JVM, is judged only while the load average is
        // below half the cores (or unknown, as on Windows); otherwise it is printed and not judged. The bound of 6 s
        // catches a stall either way.
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        boolean cpuTimed = threads.isCurrentThreadCpuTimeSupported();
        int cores = Runtime.getRuntime().availableProcessors();
        double load = ManagementFactory.getOperatingSystemMXBean().getSystemLoadAverage();
        boolean quiet = load < cores / 2.0;
        System.out.printf(Locale.ROOT, "  load average %.1f on %d cores: the cold limit is %s%n", load, cores,
                quiet ? "judged" : "not judged");
        for (int pass = 0; pass < 2; pass++) {
            String when = pass == 0 ? "cold" : "warm";
            for (Map.Entry<String, Object[]> e : songs.entrySet()) {
                Way way = (Way) e.getValue()[0];
                byte[] data = (byte[]) e.getValue()[1];
                double best = Double.MAX_VALUE;
                for (int run = 0; run < (pass == 0 ? 1 : 3) && best >= 1500; run++) {
                    long start = System.nanoTime();
                    long[] result = within(60, e.getKey(), () -> {
                        long cpu = cpuTimed ? threads.getCurrentThreadCpuTime() : 0;
                        Opened o = open(way, data, false);
                        try (PcmStream stream = o.stream()) {
                            long frames = drain(stream);
                            return new long[] {frames, stream.sampleRate(),
                                    cpuTimed ? threads.getCurrentThreadCpuTime() - cpu : -1};
                        } finally {
                            if (o.file() != null) {
                                Files.deleteIfExists(o.file());
                            }
                        }
                    });
                    double ms = (System.nanoTime() - start) / 1e6;
                    double seconds = result[0] / (double) result[1];
                    System.out.printf(Locale.ROOT, "  %s: %.0f s decoded in %.0f ms (%s of CPU), %s%n", e.getKey(),
                            seconds, ms, result[2] >= 0 ? Math.round(result[2] / 1e6) + " ms" : "unknown", when);
                    check(seconds >= 230 && seconds <= 250, e.getKey() + " is a four-minute song: " + seconds + " s");
                    check(ms < 6000, "a four-minute stereo " + e.getKey() + " decodes without stalling (took "
                            + Math.round(ms) + " ms, " + when + ", load average " + load + ")");
                    best = Math.min(best, ms);
                }
                if (pass == 1 || quiet) {
                    check(best < 1500, "a four-minute stereo " + e.getKey() + " decodes in under 1.5 s (took "
                            + Math.round(best) + " ms, " + when + ", load average " + load + ")");
                }
            }
        }
    }

    private static long liveHeap(MemoryMXBean memory) {
        System.gc();
        System.gc();
        return memory.getHeapMemoryUsage().getUsed();
    }

    /** An endless repetition of one stream, cut after a number of copies: a long song that never sits in memory. */
    private static final class Repeating extends InputStream {
        private final byte[] one;
        private final long copies;
        private long at;

        Repeating(byte[] one, long copies) {
            this.one = one;
            this.copies = copies;
        }

        @Override
        public int read() {
            if (at >= one.length * copies) {
                return -1;
            }
            return one[(int) (at++ % one.length)] & 0xFF;
        }

        @Override
        public int read(byte[] b, int off, int len) {
            long left = one.length * copies - at;
            if (left <= 0) {
                return -1;
            }
            int n = (int) Math.min(len, Math.min(left, one.length - at % one.length));
            System.arraycopy(one, (int) (at % one.length), b, off, n);
            at += n;
            return n;
        }
    }

    private static void memory(Map<String, byte[]> fixtures) throws Exception {
        MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
        Sources s = sources(fixtures);
        // Fifteen minutes of AAC LC in an M4A file, opened by path as the conversion opens it.
        Mp4 lc = s.lc().with(m -> {
            m.packets = repeat(m.packets, 554);
            m.durations = new int[m.packets.size()];
            Arrays.fill(m.durations, 1024);
            m.moovFirst = false;
        });
        Path file = scratch.resolve("fifteen-minutes.m4a");
        Files.write(file, lc.build());
        lc = null;
        Map<String, Callable<PcmStream>> songs = new LinkedHashMap<>();
        songs.put("AAC LC (M4A by path)", () -> Mp4Decoder.open(file));
        byte[] hev2 = fixtures.get("aac/hev2-implicit.aac");
        songs.put("HE-AAC v2 (ADTS stream)", () -> AdtsDecoder.open(new Repeating(hev2, 524)));
        for (Map.Entry<String, Callable<PcmStream>> e : songs.entrySet()) {
            long base = liveHeap(memory);
            long[] result = within(120, e.getKey() + " memory", () -> {
                long peak = 0;
                long frames = 0;
                try (PcmStream stream = e.getValue().call()) {
                    float[][] buf = new float[stream.channels()][4096];
                    long next = 0;
                    int n;
                    while ((n = stream.read(buf, 0, 4096)) > 0) {
                        frames += n;
                        if (frames >= next) {
                            // About every half minute of audio, the heap that is still in use.
                            peak = Math.max(peak, liveHeap(memory) - base);
                            next += 30L * stream.sampleRate();
                        }
                    }
                    peak = Math.max(peak, liveHeap(memory) - base);
                    return new long[] {frames, peak, stream.sampleRate()};
                }
            });
            double minutes = result[0] / (double) result[2] / 60;
            System.out.printf(Locale.ROOT, "  %s: %.1f minutes, peak extra heap %.2f MB%n", e.getKey(), minutes,
                    result[1] / 1e6);
            check(minutes > 14.9, e.getKey() + " is a fifteen-minute song: " + minutes);
            check(result[1] < 32_000_000L, e.getKey() + ": a fifteen-minute song needs under 32 MB of heap ("
                    + result[1] / 1_000_000 + " MB)");
        }
        Files.deleteIfExists(file);
    }
}

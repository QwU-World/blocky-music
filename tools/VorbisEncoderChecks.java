import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import ru.gora.musicbattle.audio.FfmpegVorbis;
import ru.gora.musicbattle.audio.FfmpegVorbis.Encoder;
import ru.gora.musicbattle.client.ClientOsuManiaImporter;
import ru.gora.musicbattle.server.MusicBattleTracks;
import ru.gora.musicbattle.server.MusicBattleTracks.SpeedRefusal;

/**
 * Every audio conversion in the mod goes through FfmpegVorbis. This proves what can be proven without a game:
 *
 * <ul>
 *     <li>the encoder chosen from an "ffmpeg -encoders" listing (libvorbis present, only the native one, neither),
 *     <li>the exact argument lists for each case,
 *     <li>the reason a speed refusal gives the players, and that the lang files carry each one,
 *     <li>the osu! import no longer converts inside the import call,
 *     <li>and, when this machine has ffmpeg, real conversions with every Vorbis encoder it has: each result is an
 *         Ogg Vorbis stream that Minecraft's own decoder (JOrbisAudioStream) reads to the end, stereo, at the
 *         expected length - plain, 1.5x and 2x, through the same createSpeedVariant the server uses.
 * </ul>
 *
 * What it cannot prove: that the decoded samples sound right through a speaker. The harness has no audio device.
 */
public final class VorbisEncoderChecks {
    private static int checks;
    private static final List<String> failures = new ArrayList<>();

    /** The audio part of what Homebrew's ffmpeg 9.0.1 prints: no libvorbis, the native encoder is experimental. */
    private static final String HOMEBREW_9 = """
            Encoders:
             V..... = Video
             A..... = Audio
             S..... = Subtitle
             .F.... = Frame-level multithreading
             ..S... = Slice-level multithreading
             ...X.. = Codec is experimental
             ....B. = Supports draw_horiz_band
             .....D = Supports direct rendering method 1
             ------
             V....D libx264              libx264 H.264 / AVC / MPEG-4 AVC / MPEG-4 part 10 (codec h264)
             A....D aac                  AAC (Advanced Audio Coding)
             A..X.D opus                 Opus
             A....D libopus              libopus Opus (codec opus)
             A..X.D vorbis               Vorbis
             A....D pcm_s16le            PCM signed 16-bit little-endian
            """;

    /** A typical Windows or Linux build of the 4.x era: libvorbis listed after the native one, old flag column. */
    private static final String WITH_LIBVORBIS = """
            Encoders:
             A..... = Audio
             ------
             A..X.. vorbis               Vorbis
             A..... libvorbis            libvorbis (codec vorbis)
             A..... libmp3lame           libmp3lame MP3 (MPEG audio layer 3) (codec mp3)
            """;

    private static final String NO_VORBIS = """
            Encoders:
             A..... = Audio
             ------
             A....D aac                  AAC (Advanced Audio Coding)
             A....D libopus              libopus Opus (codec opus)
             V....D vorbis_lookalike     A video encoder whose name only starts the same
            """;

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args.length > 0 ? args[0] : ".").toAbsolutePath().normalize();
        encoderChoice();
        argumentLists();
        refusalReasons(root);
        osuImportDoesNotConvert(root);
        realConversions();
        if (!failures.isEmpty()) {
            System.out.println(checks + " vorbis checks passed, " + failures.size() + " failed:");
            failures.forEach(failure -> System.out.println("  FAIL " + failure));
            System.exit(1);
        }
        System.out.println(checks + " vorbis encoder checks passed");
    }

    private static void encoderChoice() {
        equal(FfmpegVorbis.chooseEncoder(HOMEBREW_9), Encoder.NATIVE, "Homebrew 9 (no libvorbis) gets the native encoder");
        equal(FfmpegVorbis.chooseEncoder(WITH_LIBVORBIS), Encoder.LIBVORBIS, "libvorbis wins when it is there, in any order");
        equal(FfmpegVorbis.chooseEncoder(WITH_LIBVORBIS.replace(" A..X.. vorbis               Vorbis\n", "")),
                Encoder.LIBVORBIS, "libvorbis alone");
        equal(FfmpegVorbis.chooseEncoder(NO_VORBIS), Encoder.NONE, "no Vorbis encoder at all");
        equal(FfmpegVorbis.chooseEncoder(HOMEBREW_9.substring(0, HOMEBREW_9.indexOf("------"))), Encoder.NONE,
                "the legend alone names no encoder");
        equal(FfmpegVorbis.chooseEncoder(""), Encoder.NONE, "empty listing");
        equal(FfmpegVorbis.chooseEncoder(null), Encoder.NONE, "no listing");
        equal(FfmpegVorbis.chooseEncoder(HOMEBREW_9.replace("\n", "\r\n")), Encoder.NATIVE, "Windows line ends");
        equal(FfmpegVorbis.chooseEncoder(" V....D vorbis   not audio\n"), Encoder.NONE, "only audio encoders count");
    }

    private static void argumentLists() {
        Path source = Path.of("/music/in.mp3");
        Path target = Path.of("/music/.out.ogg.123.part");
        equal(FfmpegVorbis.codecArguments(Encoder.LIBVORBIS), List.of("-c:a", "libvorbis"), "libvorbis codec arguments");
        equal(FfmpegVorbis.codecArguments(Encoder.NATIVE),
                List.of("-c:a", "vorbis", "-strict", "experimental", "-ac", "2"),
                "native encoder: experimental must be allowed, and it only writes stereo");
        try {
            FfmpegVorbis.codecArguments(Encoder.NONE);
            fail("NONE has no codec arguments");
        } catch (IllegalArgumentException expected) {
            checks++;
        }
        equal(FfmpegVorbis.conversionArguments(Encoder.NATIVE, source, target, "atempo=2.0"),
                List.of("-y", "-hide_banner", "-loglevel", "error", "-i", source.toString(), "-vn",
                        "-filter:a", "atempo=2.0",
                        "-c:a", "vorbis", "-strict", "experimental", "-ac", "2",
                        "-f", "ogg", target.toString()),
                "a 2x copy with the native encoder");
        equal(FfmpegVorbis.conversionArguments(Encoder.LIBVORBIS, source, target, null),
                List.of("-y", "-hide_banner", "-loglevel", "error", "-i", source.toString(), "-vn",
                        "-c:a", "libvorbis", "-f", "ogg", target.toString()),
                "a plain import with libvorbis has no filter");
        equal(FfmpegVorbis.conversionArguments(Encoder.LIBVORBIS, source, target, " "),
                FfmpegVorbis.conversionArguments(Encoder.LIBVORBIS, source, target, null), "a blank filter is no filter");
    }

    private static void refusalReasons(Path root) throws IOException {
        equal(SpeedRefusal.of(new FfmpegVorbis.MissingException()), SpeedRefusal.FFMPEG_MISSING, "no ffmpeg");
        equal(SpeedRefusal.of(new IOException("ffmpeg (vorbis) failed with exit code 1")), SpeedRefusal.CONVERSION_FAILED,
                "ffmpeg there, conversion failed");
        equal(SpeedRefusal.of(new FfmpegVorbis.NoEncoderException(Path.of("ffmpeg"))), SpeedRefusal.CONVERSION_FAILED,
                "ffmpeg without any Vorbis encoder is a failed conversion, not a missing ffmpeg");
        equal(SpeedRefusal.of(new RuntimeException("worker died")), SpeedRefusal.CONVERSION_FAILED, "anything else");
        equal(SpeedRefusal.PREPARING.messageKey(), "music_battle.l10n.server.speed_audio_preparing", "still converting");
        equal(SpeedRefusal.FFMPEG_MISSING.messageKey(), "music_battle.l10n.server.speed_audio_fallback", "ffmpeg missing");
        equal(SpeedRefusal.CONVERSION_FAILED.messageKey(), "music_battle.l10n.server.speed_audio_failed", "conversion failed");
        List<String> keys = new ArrayList<>();
        for (SpeedRefusal refusal : SpeedRefusal.values()) {
            check(!keys.contains(refusal.messageKey()), "each refusal names its own reason: " + refusal);
            keys.add(refusal.messageKey());
        }
        keys.add("music_battle.l10n.server.speed_audio_waiting");
        keys.add("music_battle.l10n.editor.import_audio_converting");
        keys.add("music_battle.l10n.editor.import_audio_ready");
        for (String lang : List.of("en_us", "ru_ru")) {
            String text = Files.readString(root.resolve("src/main/resources/assets/music_battle/lang/" + lang + ".json"));
            for (String key : keys) {
                check(text.contains("\"" + key + "\""), lang + " has " + key);
            }
        }
        // The four call sites must not name an encoder of their own again.
        Path src = root.resolve("src/main/java/ru/gora/musicbattle");
        for (String file : List.of("server/MusicBattleTracks.java", "client/ClientAudioSpeedProcessor.java",
                "client/TrackEditorScreen.java", "client/ClientOsuManiaImporter.java", "client/ClientFfmpeg.java",
                "server/DjAudioPreparation.java")) {
            String text = Files.readString(src.resolve(file));
            check(!text.contains("\"libvorbis\"") && !text.contains("-acodec"), file + " leaves the encoder to FfmpegVorbis");
            check(!text.contains("new ProcessBuilder"), file + " starts no ffmpeg of its own");
        }
        String server = Files.readString(src.resolve("audio/FfmpegVorbis.java"));
        check(!server.contains("ru.gora.musicbattle.client") && !server.contains("net.minecraft.client"),
                "the shared helper touches no client class, so a dedicated server can load it");
    }

    /** The import returns at once with the song still to convert; the conversion is its own, later call. */
    private static void osuImportDoesNotConvert(Path root) throws Exception {
        Path game = Files.createTempDirectory("blocky-vorbis-osu-");
        try {
            net.neoforged.fml.loading.FMLPaths.loadAbsolutePaths(game);
            Path folder = Files.createDirectories(game.resolve("maps"));
            Path map = folder.resolve("check.osu");
            Files.writeString(map, """
                    osu file format v14

                    [General]
                    AudioFilename: song.mp3
                    Mode: 3

                    [Metadata]
                    Title:Check
                    Version:Easy

                    [Difficulty]
                    CircleSize:4

                    [TimingPoints]
                    0,500,4,2,0,100,1,0

                    [HitObjects]
                    64,192,1000,1,0,0:0:0:0:
                    192,192,1500,1,0,0:0:0:0:
                    """);
            Files.write(folder.resolve("song.mp3"), "this is not an mp3".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            long started = System.nanoTime();
            var imported = ClientOsuManiaImporter.importChartDeferringSong(map, -1);
            long millis = (System.nanoTime() - started) / 1_000_000L;
            equal(imported.chart().notes().size(), 2, "the notes come in at once");
            equal(imported.audioFile(), "", "no song in the box yet");
            check(imported.audioLeftBehind() == null, "nothing is reported left behind before the conversion ran");
            check(imported.pendingSong() != null, "the song is handed back to convert later");
            check(Files.isRegularFile(imported.pendingSong().original()), "the original is already in music_battle_raw");
            check(imported.pendingSong().original().getParent().equals(game.resolve("music_battle_raw").toAbsolutePath().normalize()),
                    "next to where the Audio button looks");
            check(!Files.exists(imported.pendingSong().target()), "and nothing was converted during the import");
            System.out.println("osu! import without the conversion took " + millis + " ms");
            var song = imported.pendingSong().convert();
            check(song.leftBehind() != null && song.audioFile().isEmpty(), "bytes that are not audio are left behind");
            check(Files.isRegularFile(imported.pendingSong().original()), "and kept for the Audio button");
            System.out.println("osu! song conversion on this machine: "
                    + (song.leftBehind().ffmpegMissing() ? "no ffmpeg" : "ffmpeg failed: " + song.leftBehind().reason()));
        } finally {
            deleteTree(game);
        }
    }

    private static void realConversions() throws Exception {
        Path binary;
        try {
            binary = FfmpegVorbis.locate();
        } catch (FfmpegVorbis.MissingException missing) {
            System.out.println("NOTE: no ffmpeg on this machine; real conversions skipped");
            return;
        }
        Encoder chosen = FfmpegVorbis.encoder(binary);
        String listing = listing(binary);
        System.out.println("ffmpeg " + binary + " -> " + chosen + " (listing says " + FfmpegVorbis.chooseEncoder(listing) + ")");
        equal(chosen, FfmpegVorbis.chooseEncoder(listing), "the probe and the pure choice agree");
        check(FfmpegVorbis.encoder(binary) == chosen, "the answer is remembered");
        List<Encoder> encoders = new ArrayList<>();
        if (listing.lines().anyMatch(line -> line.trim().matches("A\\S{5}\\s+libvorbis\\b.*"))) {
            encoders.add(Encoder.LIBVORBIS);
        }
        if (listing.lines().anyMatch(line -> line.trim().matches("A\\S{5}\\s+vorbis\\b.*"))) {
            encoders.add(Encoder.NATIVE);
        }
        if (encoders.isEmpty()) {
            System.out.println("NOTE: this ffmpeg has no Vorbis encoder; real conversions skipped");
            return;
        }
        Path work = Files.createTempDirectory("blocky-vorbis-");
        try {
            // Mono on purpose: the native encoder refuses anything but stereo unless it is told -ac 2.
            Path wav = work.resolve("source.wav");
            writeWav(wav, 44100, 24.0D);
            for (Encoder encoder : encoders) {
                Path plain = work.resolve("plain_" + encoder + ".ogg");
                long started = System.nanoTime();
                FfmpegVorbis.convert(binary, encoder, wav, plain, null, 60);
                System.out.printf("  %s plain 24 s mono WAV -> OGG in %d ms%n", encoder, (System.nanoTime() - started) / 1_000_000L);
                decodes(plain, 24.0D, encoder + " plain conversion");
                for (float speed : new float[]{1.5F, 2.0F}) {
                    Path sped = work.resolve("sped_" + encoder + "_" + speed + ".ogg");
                    FfmpegVorbis.convert(binary, encoder, plain, sped, speed >= 1.9F ? "atempo=2.0" : "atempo=1.5", 60);
                    decodes(sped, 24.0D / speed, encoder + " x" + speed);
                }
            }
            // The server's own path, with whatever encoder this machine picked: it validates the length itself.
            Path source = work.resolve("track.ogg");
            Files.copy(work.resolve("plain_" + encoders.get(0) + ".ogg"), source);
            Path x2 = work.resolve("track_x2.ogg");
            MusicBattleTracks.createSpeedVariant(source, x2, 2.0F);
            decodes(x2, 12.0D, "createSpeedVariant x2 with " + chosen);
            try (var stream = Files.list(work)) {
                check(stream.noneMatch(path -> path.getFileName().toString().endsWith(".part")),
                        "no temporary file is left behind");
            }
            // The osu! song conversion on a real file: the OGG lands, the original goes.
            Path original = work.resolve("map_song.wav");
            Files.copy(wav, original);
            var song = new ClientOsuManiaImporter.PendingSong(original, work.resolve("map_song.ogg"), ".wav").convert();
            check(song.leftBehind() == null, "a real song converts: " + (song.leftBehind() == null ? "" : song.leftBehind().reason()));
            equal(song.audioFile(), "map_song.ogg", "and its name is what goes in the box");
            check(!Files.exists(original), "the original is removed once converted");
            decodes(work.resolve("map_song.ogg"), 24.0D, "osu! song");
        } finally {
            deleteTree(work);
        }
    }

    /** Decodes the whole file with Minecraft's own Vorbis decoder and checks its shape and length. */
    private static void decodes(Path file, double expectedSeconds, String label) throws IOException {
        check(FfmpegVorbis.isOggVorbis(file), label + ": Ogg Vorbis header");
        long[] floats = {0L};
        int channels;
        float rate;
        try (InputStream input = Files.newInputStream(file);
             net.minecraft.client.sounds.JOrbisAudioStream stream = new net.minecraft.client.sounds.JOrbisAudioStream(input)) {
            channels = stream.getFormat().getChannels();
            rate = stream.getFormat().getSampleRate();
            while (stream.readChunk(value -> floats[0]++)) {
                // Counting is all that is needed: a stream the decoder cannot read throws before the end.
            }
        }
        double seconds = floats[0] / (double) channels / rate;
        System.out.printf("    %s: %d ch, %.0f Hz, %.2f s decoded by JOrbisAudioStream%n", label, channels, rate, seconds);
        equal(channels, 2, label + ": stereo");
        check(Math.abs(seconds - expectedSeconds) <= 0.15D, label + ": length " + seconds + " s, expected " + expectedSeconds);
        double headerSeconds = MusicBattleTracks.readOggDurationTicks(file) / 20.0D;
        check(expectedSeconds < 10.0D || Math.abs(headerSeconds - expectedSeconds) <= 0.1D,
                label + ": the duration the mod reads from the granule matches (" + headerSeconds + " s)");
    }

    private static String listing(Path binary) throws Exception {
        Process process = new ProcessBuilder(binary.toString(), "-hide_banner", "-encoders").redirectErrorStream(true).start();
        String text = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        process.waitFor();
        return text;
    }

    /** A mono 16-bit WAV: a tone that changes pitch every half second, so a wrong tempo would change its length. */
    private static void writeWav(Path path, int rate, double seconds) throws IOException {
        int frames = (int) Math.round(rate * seconds);
        ByteBuffer data = ByteBuffer.allocate(frames * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < frames; i++) {
            double frequency = 330.0D + 110.0D * ((i / (rate / 2)) % 4);
            data.putShort((short) (Math.sin(2.0D * Math.PI * frequency * i / rate) * 12000.0D));
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteBuffer header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        header.put("RIFF".getBytes()).putInt(36 + frames * 2).put("WAVE".getBytes())
                .put("fmt ".getBytes()).putInt(16).putShort((short) 1).putShort((short) 1)
                .putInt(rate).putInt(rate * 2).putShort((short) 2).putShort((short) 16)
                .put("data".getBytes()).putInt(frames * 2);
        out.write(header.array());
        out.write(data.array());
        Files.write(path, out.toByteArray());
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var stream = Files.walk(root)) {
            for (Path path : stream.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static void check(boolean condition, String label) {
        if (condition) {
            checks++;
        } else {
            failures.add(label);
        }
    }

    private static void fail(String label) {
        failures.add(label);
    }

    private static void equal(Object actual, Object expected, String label) {
        check(java.util.Objects.equals(actual, expected), label + (java.util.Objects.equals(actual, expected) ? ""
                : ": expected " + expected + ", got " + actual));
    }
}

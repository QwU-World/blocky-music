import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import ru.gora.musicbattle.audio.NativeAudio;
import ru.gora.musicbattle.audio.codec.AdtsDecoder;
import ru.gora.musicbattle.audio.codec.AudioDecoders;
import ru.gora.musicbattle.audio.codec.AudioFormatException;
import ru.gora.musicbattle.audio.codec.ChannelDownmix;
import ru.gora.musicbattle.audio.codec.PcmStream;
import ru.gora.musicbattle.audio.codec.TimeStretch;
import ru.gora.musicbattle.audio.codec.UnsupportedFormatException;
import ru.gora.musicbattle.client.ClientOsuManiaImporter;
import ru.gora.musicbattle.server.MusicBattleTracks;

/**
 * The mod's own conversion to Ogg Vorbis, end to end, as the battle, the chart editor and the osu! importer use it -
 * with no ffmpeg anywhere:
 *
 * <ul>
 *     <li>a generated WAV (44.1 kHz), a generated FLAC (48 kHz), a 96 kHz 24-bit WAV (resampled to 48 kHz), a 5.1 WAV
 *     (folded to stereo) and the committed MP3 fixture are converted plain, at 1.5x and at 2x;</li>
 *     <li>every result is read back by the mod's VorbisDecoder and by Minecraft's own JOrbisAudioStream: both must
 *     give exactly {@code round(frames / speed)} frames, and the server's isValidSpeedVariant must accept the copy;</li>
 *     <li>the generated songs are click tracks over a soft pad, and every click of every conversion must land within
 *     5 ms of {@code input / speed} (within half a millisecond plain), because the battle scales the notes by the same
 *     factor;</li>
 *     <li>the server's createSpeedVariant and the osu! importer's song conversion, on real files (an M4A, and raw AAC
 *     cut part way into a frame with and without a tag, among them), the importer deciding by content (an MP3 or an
 *     Opus song called .ogg is converted, an MPEG-TS one called .m4a taken for conversion, a WMA one named at
 *     once);</li>
 *     <li>every format the AAC, ALAC and Opus decoders added (AAC LC, HE-AAC and HE-AAC v2 in M4A, fragmented and DASH
 *     M4A, 5.1 AAC, raw ADTS, ALAC, Ogg Opus with SILK, CELT, hybrid and 5.1, Opus in WebM), and those that completed
 *     the converter (MPEG-TS with AAC, MP3 and Opus, HLS segments joined, Blu-ray .m2ts, Opus in MP4, Vorbis, AAC, MP3,
 *     FLAC and PCM in Matroska, IMA and Microsoft ADPCM WAV, free-format MP3): sniffed by content under
 *     a wrong name too, converted plain, at 1.5x and at 2x, read back by JOrbisAudioStream with exactly the decoder's
 *     length and at lag 0 against the decoder's own output;</li>
 *     <li>an interrupt stops a running conversion, keeps the interrupt and leaves no temporary file;</li>
 *     <li>AC-3 in MP4, an MPEG transport stream (whole, as 192-byte packets, behind a tag, cut), WMA, text and an
 *     empty file are refused as formats the mod does not read, a damaged WAV, an M4A cut off after its first box and an
 *     Opus song cut off after its first header as damage, and data that only repeats every 188 bytes is not taken for a
 *     transport stream;</li>
 *     <li>the review's findings, each on the case that showed it: the crossfade lift never takes a sample past the
 *     grains' peak (loud songs clipped); a NaN or an infinity in a float WAV is silence and spoils no later onset; an Ogg
 *     song is converted on the timeline Minecraft plays (a song cut with a stream copy came out early) and one cut off
 *     mid-page converts while damage the game plays out of step from is refused; MP3 behind a stray byte, Ogg and WAV
 *     behind ID3 tags, MP3 in WAV and G.711 convert, ADPCM is unsupported by name; the converted file's permissions;
 *     stale temporary files; an unexpected exception inside the converter reaches its callers as an IOException;</li>
 *     <li>the second review's: the lift stops at 0.9 of full scale (the encoder's error clipped what it left at full
 *     scale); every sped-up copy carries the converter's mark and a plain conversion none; a 5.1 Ogg, which the game
 *     leaves silent, does not count as playable as it is; a song that decodes to no samples is refused;</li>
 *     <li>the source starts no process, the ffmpeg classes are gone, and audio/ uses no Minecraft or LWJGL class;</li>
 *     <li>once the translations are merged: the messages exist in both languages and none mentions ffmpeg.</li>
 * </ul>
 *
 * What it cannot prove: how the stretched songs sound through a speaker. The measurements behind the stretcher's
 * design (onset timing against ffmpeg's atempo on real songs, crossfade flutter) are in DEVELOPMENT.md.
 */
public final class NativeAudioChecks {
    private static int checks;
    private static final List<String> FAILURES = new ArrayList<>();
    private static final float[] SPEEDS = {1.0F, 1.5F, 2.0F};
    private static Method isValidSpeedVariant;

    /** A generated song and where its clicks are, in its own frames. */
    private record Song(String label, Path file, int rate, int channels, int frames, int[] clicks) {
        int outputRate() {
            return Math.min(rate, 48000);
        }

        int outputChannels() {
            return Math.min(channels, 2);
        }

        long outputFrames() {
            return rate > 48000 ? Math.round(frames * 48000.0 / rate) : frames;
        }
    }

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args.length > 0 ? args[0] : ".").toAbsolutePath().normalize();
        isValidSpeedVariant = MusicBattleTracks.class.getDeclaredMethod("isValidSpeedVariant", Path.class, Path.class, float.class);
        isValidSpeedVariant.setAccessible(true);
        Path work = Files.createTempDirectory("blocky-native-audio-");
        try {
            // A throwaway game folder: the osu! importer writes into its music_battle_raw.
            net.neoforged.fml.loading.FMLPaths.loadAbsolutePaths(Files.createDirectories(work.resolve("game")));
            conversions(root, work);
            speedVariants(work);
            osuSongs(root, work);
            refusals(root, work);
            interrupt(work);
            stretchCap();
            loudCopies(work);
            damagedSamples(work);
            minecraftTimeline(root, work);
            formats(root, work);
            newFormats(root, work);
            filesAndFailures(root, work);
            workshopChosenSong(root, work);
        } finally {
            deleteTree(work);
        }
        sources(root);
        translations(root);
        if (!FAILURES.isEmpty()) {
            System.out.println(checks + " native audio checks passed, " + FAILURES.size() + " failed:");
            FAILURES.forEach(failure -> System.out.println("  FAIL " + failure));
            System.exit(1);
        }
        System.out.println(checks + " native audio checks passed");
        System.out.println("NOT COVERED: listening; the conversion timeout (it needs a conversion longer than a second);"
                + " Windows' retry of a move onto an open file");
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Conversions

    private static void conversions(Path root, Path work) throws Exception {
        Path in = Files.createDirectories(work.resolve("in"));
        List<Song> songs = new ArrayList<>();
        songs.add(clickSong("wav-44k-stereo", in.resolve("click.wav"), 44100, 2, 16, 6.5, false));
        songs.add(clickSong("flac-48k-stereo", in.resolve("click.flac"), 48000, 2, 16, 6.5, true));
        songs.add(clickSong("wav-96k-24bit", in.resolve("hires.wav"), 96000, 2, 24, 4.0, false));
        songs.add(clickSong("wav-5.1-48k", in.resolve("surround.wav"), 48000, 6, 16, 4.0, false));
        for (Song song : songs) {
            Path plain = null;
            for (float speed : SPEEDS) {
                Path target = work.resolve("out").resolve(song.label() + "_x" + speed + ".ogg");
                long started = System.nanoTime();
                NativeAudio.convertToVorbis(song.file(), target, speed);
                long millis = (System.nanoTime() - started) / 1_000_000L;
                if (speed == 1.0F) {
                    plain = target;
                }
                Decoded decoded = decode(target);
                String label = song.label() + " x" + speed;
                equal(NativeAudio.speedCopyVersion(target), speed == 1.0F ? 0 : NativeAudio.SPEED_COPY_VERSION,
                        label + ": a sped-up copy carries this build's mark, a plain conversion none");
                long expected = (long) Math.floor(song.outputFrames() / (double) speed + 0.5);
                equal(decoded.rate(), song.outputRate(), label + ": sample rate");
                equal(decoded.channels(), song.outputChannels(), label + ": channels");
                equal((long) decoded.frames(), expected, label + ": exact length");
                equal(jorbisFrames(target), expected, label + ": Minecraft's JOrbisAudioStream reads the same length");
                if (speed > 1.0F) {
                    check(validSpeedVariant(plain, target, speed), label + ": isValidSpeedVariant accepts it");
                }
                double worst = clickTiming(song, decoded, speed, label);
                System.out.printf(Locale.ROOT, "  %-18s x%.1f: %d frames, %d ms, worst click %.2f ms%n",
                        song.label(), speed, decoded.frames(), millis, worst);
            }
        }
        mp3Fixture(root, work);
        try (var leftovers = Files.list(work.resolve("out"))) {
            check(leftovers.noneMatch(path -> path.getFileName().toString().endsWith(".part")), "no temporary file is left");
        }
    }

    /** The committed MP3: the plain copy must be the same audio, the faster ones exactly as long as they should be. */
    private static void mp3Fixture(Path root, Path work) throws Exception {
        Path mp3 = root.resolve("tools/fixtures/mp3/cbr-lame.mp3");
        equal(AudioDecoders.detect(mp3), AudioDecoders.Kind.MP3, "the MP3 fixture is sniffed as MP3");
        Decoded source = decode(mp3);
        Path plain = work.resolve("out").resolve("mp3_x1.ogg");
        for (float speed : SPEEDS) {
            Path target = speed == 1.0F ? plain : work.resolve("out").resolve("mp3_x" + speed + ".ogg");
            NativeAudio.convertToVorbis(mp3, target, speed);
            Decoded decoded = decode(target);
            long expected = (long) Math.floor(source.frames() / (double) speed + 0.5);
            equal((long) decoded.frames(), expected, "MP3 x" + speed + ": exact length");
            equal(jorbisFrames(target), expected, "MP3 x" + speed + ": JOrbisAudioStream length");
            if (speed > 1.0F) {
                check(validSpeedVariant(plain, target, speed), "MP3 x" + speed + ": isValidSpeedVariant accepts it");
            } else {
                double snr = snr(source, decoded);
                System.out.printf(Locale.ROOT, "  MP3 fixture plain: %d frames, %.1f dB against its own decode%n",
                        decoded.frames(), snr);
                check(snr >= 10.0, "MP3 plain: the OGG is the same audio, sample for sample in time (" + snr + " dB)");
            }
        }
    }

    /** Every click must come out where the battle expects it: within 5 ms of input / speed, half a ms plain. */
    private static double clickTiming(Song song, Decoded decoded, float speed, String label) {
        double ratio = decoded.rate() / (double) song.rate();
        double limitMs = speed == 1.0F ? 0.5 : 5.0;
        double worst = 0.0;
        int window = decoded.rate() / 50;
        for (int click : song.clicks()) {
            double ideal = click * ratio / speed;
            int from = (int) Math.max(2, Math.floor(ideal) - window);
            int to = (int) Math.min(decoded.frames() - 1L, (long) Math.ceil(ideal) + window);
            int best = -1;
            double peak = 0.0;
            double sum = 0.0;
            for (int n = from; n <= to; n++) {
                double d2 = 0.0;
                for (float[] channel : decoded.pcm()) {
                    d2 += Math.abs(channel[n] - 2 * channel[n - 1] + channel[n - 2]);
                }
                sum += d2 * d2;
                if (d2 > peak) {
                    peak = d2;
                    best = n;
                }
            }
            double rms = Math.sqrt(sum / Math.max(1, to - from + 1));
            if (best < 0 || peak < 4.0 * rms) {
                fail(label + ": the click at " + click + " is lost (peak " + peak + ", background " + rms + ")");
                continue;
            }
            // A click is +a then -a/2: its second difference peaks one frame after it.
            double errorMs = Math.abs(best - 1 - ideal) * 1000.0 / decoded.rate();
            worst = Math.max(worst, errorMs);
            check(errorMs <= limitMs, label + ": the click at " + click + " lands " + errorMs + " ms from its place");
        }
        return worst;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // The server's and the importer's own paths

    private static void speedVariants(Path work) throws Exception {
        Path folder = Files.createDirectories(work.resolve("track"));
        Path track = folder.resolve("track.ogg");
        NativeAudio.convertToVorbis(work.resolve("in/click.wav"), track, 1.0F);
        // Leftovers of a killed server next to the copy: one twenty minutes old goes, a recent one may be a running
        // conversion's and stays. The rule lives in NativeAudio; the server only asks it for ten minutes here.
        Path oldPart = folder.resolve(".track_x2.ogg.111.part");
        Path recentPart = folder.resolve(".track_x2.ogg.222.part");
        Files.write(oldPart, new byte[]{1});
        Files.write(recentPart, new byte[]{1});
        Files.setLastModifiedTime(oldPart, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() - 1_200_000L));
        for (float speed : new float[]{1.5F, 2.0F}) {
            Path target = folder.resolve(speed == 2.0F ? "track_x2.ogg" : "track_x15.ogg");
            MusicBattleTracks.createSpeedVariant(track, target, speed);
            if (speed == 2.0F) {
                check(!Files.exists(oldPart), "createSpeedVariant deletes a temporary file twenty minutes old");
                check(Files.exists(recentPart), "but keeps a recent one");
                Files.deleteIfExists(recentPart);
            }
            check(Files.isRegularFile(target), "createSpeedVariant x" + speed + " writes the copy");
            check(validSpeedVariant(track, target, speed), "createSpeedVariant x" + speed + ": the server accepts it");
            equal(NativeAudio.speedCopyVersion(target), NativeAudio.SPEED_COPY_VERSION,
                    "createSpeedVariant x" + speed + ": the copy carries this build's mark");
            long modified = Files.getLastModifiedTime(target).toMillis();
            MusicBattleTracks.createSpeedVariant(track, target, speed);
            equal(Files.getLastModifiedTime(target).toMillis(), modified, "a ready copy is not made again (x" + speed + ")");
            double seconds = MusicBattleTracks.readOggDurationTicks(target) / 20.0;
            check(seconds >= 6.5 / speed - 0.05, "the duration read from the copy's granule fits x" + speed + ": " + seconds);
        }
        try (var leftovers = Files.list(folder)) {
            check(leftovers.noneMatch(path -> path.getFileName().toString().endsWith(".part")),
                    "the speed copies leave no temporary file");
        }
    }

    private static void osuSongs(Path root, Path work) throws Exception {
        Path raw = Files.createDirectories(MusicBattleTracks.rawDirectory());
        Path original = raw.resolve("osu_song.wav");
        Files.copy(work.resolve("in/click.wav"), original);
        var song = new ClientOsuManiaImporter.PendingSong(original, raw.resolve("osu_song.ogg"), ".wav").convert();
        check(song.leftBehind() == null, "an osu! map's WAV converts: " + (song.leftBehind() == null ? "" : song.leftBehind().reason()));
        equal(song.audioFile(), "osu_song.ogg", "and its name is what goes in the box");
        check(!Files.exists(original), "the original is removed once converted");
        equal((long) decode(raw.resolve("osu_song.ogg")).frames(), 286650L, "the osu! song keeps its length");

        // An M4A song converts like the others: AAC in MP4, read by path because its index often comes last.
        Path m4a = raw.resolve("osu_m4a.m4a");
        Files.copy(root.resolve("tools/fixtures/aac/lc-afconvert.m4a"), m4a);
        var m4aSong = new ClientOsuManiaImporter.PendingSong(m4a, raw.resolve("osu_m4a.ogg"), ".m4a").convert();
        check(m4aSong.leftBehind() == null, "an osu! map's M4A converts: "
                + (m4aSong.leftBehind() == null ? "" : m4aSong.leftBehind().reason()));
        equal((long) decode(raw.resolve("osu_m4a.ogg")).frames(), 70560L, "the M4A song keeps its length");
        // An MP4 whose sound the mod does not read: only its index says so, so it is found out by the conversion.
        Path ac3 = raw.resolve("osu_ac3.m4a");
        Files.write(ac3, ac3InMp4(root));
        var left = new ClientOsuManiaImporter.PendingSong(ac3, raw.resolve("osu_ac3.ogg"), ".m4a").convert();
        check(left.leftBehind() != null && left.leftBehind().unsupportedFormat(), "an AC-3 song in MP4 is left behind as unsupported");
        check(left.leftBehind() != null && left.leftBehind().reason().isEmpty(), "with no failure to report");
        check(left.leftBehind() != null && left.leftBehind().keptFile().equals("osu_ac3.m4a") && Files.isRegularFile(ac3),
                "and the original is kept for the player to convert");
        // Raw AAC recorded from a broadcast starts part way into a frame, often behind a tag its recorder wrote: it is
        // found by its frames chaining up, not taken for MP3, and converts from its first whole frame (69 of 70 here).
        byte[] adts = Files.readAllBytes(root.resolve("tools/fixtures/aac/lc-stereo-44k.aac"));
        byte[] cutAdts = Arrays.copyOfRange(adts, 100, adts.length);
        for (var cutSong : Map.of("osu_cut.aac", cutAdts, "osu_tagged_cut.aac", concat(id3v2(300), cutAdts)).entrySet()) {
            Path cutFile = raw.resolve(cutSong.getKey());
            Files.write(cutFile, cutSong.getValue());
            equal(AudioDecoders.detect(cutFile), AudioDecoders.Kind.ADTS, cutSong.getKey() + " is raw AAC");
            Path cutOgg = raw.resolve(cutSong.getKey().replace(".aac", ".ogg"));
            NativeAudio.convertToVorbis(cutFile, cutOgg, 1.0F);
            equal((long) decode(cutOgg).frames(), 70656L, cutSong.getKey() + " converts from its first whole frame");
        }

        Path damaged = raw.resolve("osu_damaged.mp3");
        Files.write(damaged, damagedMp3());
        var broken = new ClientOsuManiaImporter.PendingSong(damaged, raw.resolve("osu_damaged.ogg"), ".mp3").convert();
        check(broken.leftBehind() != null && !broken.leftBehind().unsupportedFormat() && !broken.leftBehind().reason().isEmpty(),
                "a damaged MP3 is a failed conversion with its reason, not an unsupported format");

        // A map whose song is called .mp3 but is Ogg Vorbis: copied as the OGG it is, nothing to convert.
        Path maps = Files.createDirectories(work.resolve("maps"));
        Files.copy(work.resolve("track/track.ogg"), maps.resolve("song.mp3"));
        Files.writeString(maps.resolve("check.osu"), """
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
        var imported = ClientOsuManiaImporter.importChartDeferringSong(maps.resolve("check.osu"), -1);
        check(imported.pendingSong() == null && imported.audioLeftBehind() == null,
                "an Ogg Vorbis song named .mp3 needs no conversion");
        check(imported.audioFile().endsWith(".ogg") && NativeAudio.isOggVorbis(raw.resolve(imported.audioFile())),
                "and lands in music_battle_raw as .ogg: " + imported.audioFile());

        // The other way round, decided by content too: an MP3 named .ogg used to be copied as .ogg unconverted, so the
        // beatmap could never play it. It is converted, without ever sitting where its OGG goes.
        String osu = Files.readString(maps.resolve("check.osu"));
        Files.writeString(maps.resolve("named.osu"), osu.replace("song.mp3", "named.ogg").replace("Version:Easy", "Version:Named"));
        Files.copy(root.resolve("tools/fixtures/mp3/cbr-lame.mp3"), maps.resolve("named.ogg"));
        var named = ClientOsuManiaImporter.importChartDeferringSong(maps.resolve("named.osu"), -1);
        check(named.pendingSong() != null && named.audioLeftBehind() == null, "an MP3 named .ogg is converted");
        if (named.pendingSong() != null) {
            var pending = named.pendingSong();
            equal(pending.sourceExtension(), ".mp3", "and is called what its content is");
            check(!pending.original().equals(pending.target()) && !pending.original().getFileName().toString().endsWith(".ogg"),
                    "its bytes wait under a name that is not an .ogg, never the target's: " + pending.original().getFileName());
            check(!Files.exists(pending.target()), "nothing is at the target until the conversion has finished");
            var converted = pending.convert();
            check(converted.leftBehind() == null && NativeAudio.isOggVorbis(pending.target()),
                    "the conversion leaves an Ogg Vorbis song at " + pending.target().getFileName());
            equal(converted.audioFile(), pending.target().getFileName().toString(), "which goes in the box");
            check(!Files.exists(pending.original()), "and the waiting original is gone");
        }

        // An Opus song named .ogg is the Opus it is: called .opus while it waits, then converted.
        Files.writeString(maps.resolve("opus.osu"), osu.replace("song.mp3", "voice.ogg").replace("Version:Easy", "Version:Opus"));
        Files.copy(root.resolve("tools/fixtures/opus/celt-stereo-96k.opus"), maps.resolve("voice.ogg"));
        var opus = ClientOsuManiaImporter.importChartDeferringSong(maps.resolve("opus.osu"), -1);
        check(opus.pendingSong() != null && opus.audioLeftBehind() == null, "an Opus song named .ogg is converted");
        if (opus.pendingSong() != null) {
            var pending = opus.pendingSong();
            equal(pending.sourceExtension(), ".opus", "and is called what its content is");
            var converted = pending.convert();
            check(converted.leftBehind() == null && NativeAudio.isOggVorbis(pending.target()),
                    "the conversion leaves an Ogg Vorbis song at " + pending.target().getFileName());
            equal((long) decode(pending.target()).frames(), 144000L, "the Opus song keeps its length");
        }
        // A WMA song named .ogg: a format the mod does not read, said at once, and kept under a name that says so.
        Files.writeString(maps.resolve("wma.osu"), osu.replace("song.mp3", "music.ogg").replace("Version:Easy", "Version:Wma"));
        Files.write(maps.resolve("music.ogg"), wmaBytes());
        var wma = ClientOsuManiaImporter.importChartDeferringSong(maps.resolve("wma.osu"), -1);
        check(wma.pendingSong() == null && wma.audioLeftBehind() != null && wma.audioLeftBehind().unsupportedFormat(),
                "a WMA song named .ogg is reported as a format the mod does not read, right at the import");
        if (wma.audioLeftBehind() != null) {
            String kept = wma.audioLeftBehind().keptFile();
            check(kept.endsWith(".wma") && Files.isRegularFile(raw.resolve(kept)),
                    "and kept in music_battle_raw as .wma, never as an .ogg the editor would take for a song: " + kept);
        }
        // An MPEG-TS song named .m4a, as the segments of an HLS download joined without ffmpeg can be: taken for
        // conversion like any other song the mod reads, and kept as .ts should the conversion fail.
        Files.writeString(maps.resolve("hls.osu"),
                osu.replace("song.mp3", "stream.m4a").replace("Version:Easy", "Version:Hls"));
        Files.copy(root.resolve("tools/fixtures/aac/lc-silent-intro.ts"), maps.resolve("stream.m4a"));
        var hls = ClientOsuManiaImporter.importChartDeferringSong(maps.resolve("hls.osu"), -1);
        check(hls.pendingSong() != null && hls.audioLeftBehind() == null,
                "an MPEG-TS song named .m4a is taken for conversion at the import");
        if (hls.pendingSong() != null) {
            var pending = hls.pendingSong();
            equal(pending.sourceExtension(), ".ts", "and is called what its content is");
            var converted = pending.convert();
            check(converted.leftBehind() == null && NativeAudio.isOggVorbis(pending.target()),
                    "the conversion leaves an Ogg Vorbis song at " + pending.target().getFileName());
            equal((long) decode(pending.target()).frames(), 40960L, "the MPEG-TS song keeps ffmpeg's length");
        }
        try (var leftovers = Files.list(raw)) {
            check(leftovers.noneMatch(path -> path.getFileName().toString().endsWith(".part")),
                    "the import leaves no temporary file in music_battle_raw");
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Refusals and interrupts

    private static void refusals(Path root, Path work) throws Exception {
        Path folder = Files.createDirectories(work.resolve("refused"));
        refused(folder, "song.m4a", ac3InMp4(root), "AC-3");
        // An M4A cut off after its first box is a damaged song, not a format the mod does not read.
        Path cut = folder.resolve("cut.m4a");
        Files.write(cut, m4aBytes());
        try {
            NativeAudio.convertToVorbis(cut, folder.resolve("cut.ogg"), 1.0F);
            fail("an M4A with no index converts");
        } catch (UnsupportedFormatException wrong) {
            fail("an M4A with no index is reported as an unsupported format: " + wrong.formatName());
        } catch (AudioFormatException expected) {
            check(String.valueOf(expected.getMessage()).contains("moov"),
                    "an M4A with no index is refused as damaged, saying what is missing: " + expected.getMessage());
        }
        // An MPEG transport stream is read as one, never taken for raw AAC: the quiet start of ffmpeg's encoder puts
        // three ADTS frames into one packet (this fixture's fourth), which the search for AAC that starts part way
        // into a frame would believe, and the song would then fail as damaged. Whole and named .m4a (as an HLS download
        // joined without ffmpeg can be), with Blu-ray's 192-byte packets, behind a tag, cut part way into a packet,
        // and cut where that chain of frames starts: each is TS and converts to exactly ffmpeg's length (40 960 frames;
        // the cut at the chain, read from its first whole PES packet on as ffmpeg reads it, 24 576).
        byte[] ts = Files.readAllBytes(root.resolve("tools/fixtures/aac/lc-silent-intro.ts"));
        int chain = adtsChainIn(ts);
        check(chain > 0, "the transport stream holds a chain of ADTS frames inside a packet: " + chain);
        convertsAsTs(folder, "hls.m4a", ts, 40960L);
        convertsAsTs(folder, "camera.m2ts", m2ts(ts), 40960L);
        convertsAsTs(folder, "tagged.ts", concat(id3v2(300), ts), 40960L);
        convertsAsTs(folder, "cut.ts", Arrays.copyOfRange(ts, 100, ts.length), 40960L);
        convertsAsTs(folder, "chain.ts", Arrays.copyOfRange(ts, Math.max(chain, 0), ts.length), 24576L);
        // An audio codec no decoder here reads is named from the program table.
        refused(folder, "ac3.ts", tsWithStreamType(ts, 0x81), "AC-3 in MPEG-TS");
        // Data that only repeats with a packet's length, the sync byte included, is not a transport stream: its
        // packets' counters never step, as in a run of identical silent frames whose length divides the packet's.
        refused(folder, "repeating.bin", repeatedPacket(), null);
        refused(folder, "song.wma", wmaBytes(), "WMA/WMV");
        // An Opus song that stops after its first header is a damaged song, not a format the mod does not read.
        Path lone = folder.resolve("lone.opus");
        Files.write(lone, opusPage());
        try {
            NativeAudio.convertToVorbis(lone, folder.resolve("lone.ogg"), 1.0F);
            fail("an Opus song with only its first header converts");
        } catch (UnsupportedFormatException wrong) {
            fail("an Opus song with only its first header is reported as an unsupported format: " + wrong.formatName());
        } catch (AudioFormatException expected) {
            checks++;
        }
        refused(folder, "notes.mp3", "this is not an mp3".getBytes(StandardCharsets.UTF_8), null);
        refused(folder, "empty.flac", new byte[0], null);
        // A WAV whose data chunk is empty decodes to nothing; the editor used to say "converted" and leave a silent song.
        Path silent = folder.resolve("no-samples.wav");
        Files.write(silent, wavBytes(new float[][]{new float[0], new float[0]}, 44100, 16));
        for (float speed : new float[]{1.0F, 2.0F}) {
            try {
                NativeAudio.convertToVorbis(silent, folder.resolve("no-samples.ogg"), speed);
                fail("a song with no samples converts at x" + speed);
            } catch (UnsupportedFormatException wrong) {
                fail("a song with no samples is reported as an unsupported format");
            } catch (AudioFormatException expected) {
                check(String.valueOf(expected.getMessage()).contains("no sound"),
                        "a song with no samples is refused as holding no sound: " + expected.getMessage());
            }
        }
        Path damaged = folder.resolve("damaged.wav");
        byte[] wav = Arrays.copyOf(wavBytes(new float[][]{new float[100]}, 44100, 16), 60);
        Arrays.fill(wav, 12, 60, (byte) 0x5A);
        Files.write(damaged, wav);
        try {
            NativeAudio.convertToVorbis(damaged, folder.resolve("damaged.ogg"), 1.0F);
            fail("a damaged WAV converts");
        } catch (UnsupportedFormatException wrong) {
            fail("a damaged WAV is reported as an unsupported format");
        } catch (AudioFormatException expected) {
            checks++;
        }
        for (float speed : new float[]{0.25F, 5.0F, Float.NaN}) {
            try {
                NativeAudio.convertToVorbis(work.resolve("in/click.wav"), folder.resolve("bad.ogg"), speed);
                fail("speed " + speed + " is accepted");
            } catch (IllegalArgumentException expected) {
                checks++;
            }
        }
        try (var leftovers = Files.list(folder)) {
            check(leftovers.noneMatch(path -> path.getFileName().toString().endsWith(".ogg")
                    || path.getFileName().toString().endsWith(".part")), "a refused song leaves no file at all");
        }
    }

    /** A transport stream is sniffed as TS and converts, to exactly the frames given, as Minecraft reads the copy. */
    private static void convertsAsTs(Path folder, String name, byte[] bytes, long frames) throws IOException {
        Path file = folder.resolve(name);
        Files.write(file, bytes);
        equal(AudioDecoders.detect(file), AudioDecoders.Kind.TS, name + " is an MPEG transport stream");
        Path ogg = folder.resolve(name + ".converted");
        NativeAudio.convertToVorbis(file, ogg, 1.0F);
        equal(jorbisFrames(ogg), frames, name + " converts to ffmpeg's length");
        Files.delete(ogg);
        Files.delete(file);
    }

    /** The stream with the first stream type of every program table replaced. */
    private static byte[] tsWithStreamType(byte[] ts, int type) {
        byte[] out = ts.clone();
        for (int at = 0; at + 188 <= out.length; at += 188) {
            int pid = (out[at + 1] & 0x1F) << 8 | (out[at + 2] & 0xFF);
            if (pid != 0 && pid != 0x1FFF && pid != 0x11 && (out[at + 1] & 0x40) != 0 && out[at + 4] == 0
                    && out[at + 5] == 2) {
                int programInfo = (out[at + 15] & 0x0F) << 8 | (out[at + 16] & 0xFF);
                out[at + 17 + programInfo] = (byte) type;
            }
        }
        return out;
    }

    private static void refused(Path folder, String name, byte[] bytes, String format) throws IOException {
        Path file = folder.resolve(name);
        Files.write(file, bytes);
        try {
            NativeAudio.convertToVorbis(file, folder.resolve(name + ".ogg"), 1.0F);
            fail(name + " converts");
        } catch (UnsupportedFormatException expected) {
            equal(expected.formatName(), format, name + " is refused, named " + format);
        }
    }

    /** A long conversion is interrupted as the server's shutdown interrupts its worker. */
    private static void interrupt(Path work) throws Exception {
        Path folder = Files.createDirectories(work.resolve("interrupt"));
        Path source = folder.resolve("long.wav");
        int rate = 44100;
        float[] noise = new float[rate * 150];
        long seed = 12345L;
        for (int i = 0; i < noise.length; i++) {
            seed = seed * 6364136223846793005L + 1442695040888963407L;
            noise[i] = 0.3F * ((seed >>> 40) / (float) (1L << 24) - 0.5F) + 0.2F * (float) Math.sin(i * 0.05);
        }
        Files.write(source, wavBytes(new float[][]{noise}, rate, 16));
        Path target = folder.resolve("long_x2.ogg");
        Object[] outcome = new Object[2];
        Thread worker = new Thread(() -> {
            try {
                NativeAudio.convertToVorbis(source, target, 2.0F);
                outcome[0] = "finished";
            } catch (Throwable thrown) {
                outcome[0] = thrown;
            }
            outcome[1] = Thread.currentThread().isInterrupted();
        }, "native-audio-check-worker");
        worker.start();
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (System.nanoTime() < deadline && !hasPart(folder) && worker.isAlive()) {
            Thread.sleep(2);
        }
        Thread.sleep(30);
        long interrupted = System.nanoTime();
        worker.interrupt();
        worker.join(10_000);
        long latency = (System.nanoTime() - interrupted) / 1_000_000L;
        System.out.println("  interrupt: " + (outcome[0] instanceof Throwable thrown ? thrown.getClass().getSimpleName() : outcome[0])
                + " after " + latency + " ms");
        check(!worker.isAlive(), "the interrupted conversion ends");
        check(outcome[0] instanceof IOException, "an interrupt ends the conversion with an IOException, got " + outcome[0]);
        check(Boolean.TRUE.equals(outcome[1]), "the interrupt is still set for the worker's owner");
        check(latency < 2000, "and it stops quickly: " + latency + " ms");
        check(!hasPart(folder), "the temporary file is deleted");
        check(!Files.exists(target), "and nothing is left where the copy would go");
    }

    // ---------------------------------------------------------------------------------------------------------------
    // The review's findings, each with the case that showed it

    /**
     * The 1.5x and 2x copies of a loud master must not clip in the game more than the song does. A master saturated
     * to 0.999 (saw chords, kicks and noise hats driven into a soft clipper: a harsher master than any real song this
     * was measured on) never reaches full scale itself, but every Vorbis encode of it overshoots, the 1x conversion
     * too. The copies overshot as much as the 1x song, and more at their grain joins: before PeakLimiter, 115 703 and
     * 117 701 clipped samples per million and 106 and 80 windows of 50 ms with clipping distortion above -40 dB, where
     * the 1x conversion has 160 479 and 160. Held at -1.5 dBFS between the stretcher and the encoder, the copies clip
     * at most a twentieth as often as the 1x song and in at most a quarter of its loud windows (measured: 1 947 and
     * 2 228 per million, 9 and 10 windows; real loud masters had none above -43 dB). That still left samples over full
     * scale: a real brick-walled 320 kb/s MP3 had 12 and 13 in its copies. Each finished copy is now decoded as the
     * game decodes it and encoded again a fraction of a decibel quieter while it peaks over full scale, so no sample of
     * either copy reaches it (measured here: 0; the brick-walled MP3's copies now peak at -0.17 and -0.51 dBFS).
     */
    private static void loudCopies(Path work) throws Exception {
        int rate = 44100;
        int n = rate * 8;
        float[][] master = new float[2][n];
        long seed = 77;
        for (int i = 0; i < n; i++) {
            double t = i / (double) rate;
            double beat = t % 0.5;
            double kick = beat < 0.12 ? StrictMath.sin(2 * Math.PI * (60 + 200 * StrictMath.exp(-beat * 40)) * beat)
                    * StrictMath.exp(-beat * 18) : 0;
            seed = seed * 6364136223846793005L + 1442695040888963407L;
            double noise = 2.0 * ((seed >>> 11) / (double) (1L << 53)) - 1.0;
            double hat = (t % 0.25) < 0.03 ? noise * StrictMath.exp(-(t % 0.25) * 120) : 0;
            for (int c = 0; c < 2; c++) {
                double saw = 0;
                for (double f : new double[]{110, 138.6, 164.8, 220 * (1 + 0.003 * c)}) {
                    saw += 2 * ((t * f) % 1.0) - 1;
                }
                double x = 3.0 * (0.25 * saw + 0.9 * kick + 0.5 * hat + 0.1 * noise);
                master[c][i] = (float) Math.max(-0.999, Math.min(0.999, StrictMath.tanh(x * 1.2) / StrictMath.tanh(1.2)));
            }
        }
        Path folder = Files.createDirectories(work.resolve("loud"));
        Path wav = folder.resolve("master.wav");
        Files.write(wav, wavBytes(master, rate, 16));
        double[] plain = null;
        for (float speed : new float[]{1.0F, 1.5F, 2.0F}) {
            Path copy = folder.resolve("master_x" + speed + ".ogg");
            NativeAudio.convertToVorbis(wav, copy, speed);
            double[] clipping = clipping(jorbisPcm(copy), rate);
            System.out.printf(Locale.ROOT, "  loud master x%.1f: %.0f clipped samples per million, %.0f windows above -40 dB%n",
                    speed, clipping[0], clipping[1]);
            if (speed == 1.0F) {
                plain = clipping;
                continue;
            }
            check(clipping[0] * 20 <= plain[0], "x" + speed + " copy of a loud master clips at most a twentieth as often"
                    + " as the song (" + clipping[0] + " against " + plain[0] + " per million)");
            check(clipping[0] == 0, "x" + speed + " copy of a loud master has no sample at full scale where the game plays"
                    + " it (" + clipping[0] + " per million)");
            check(clipping[1] * 4 <= plain[1], "x" + speed + " copy of a loud master: at most a quarter of the song's"
                    + " windows with loud clipping (" + clipping[1] + " against " + plain[1] + ")");
        }
    }

    /** Clipped samples per million (|x| >= 1, where the game cuts off), and 50 ms windows clipping above -40 dB. */
    private static double[] clipping(float[][] pcm, int rate) {
        long clipped = 0;
        int loud = 0;
        int window = rate / 20;
        for (int w = 0; (w + 1) * window <= pcm[0].length; w++) {
            double energy = 0;
            double distortion = 0;
            for (float[] channel : pcm) {
                for (int i = w * window; i < (w + 1) * window; i++) {
                    double over = Math.abs(channel[i]) - 1.0;
                    energy += channel[i] * (double) channel[i];
                    if (over >= 0) {
                        clipped++;
                        distortion += over * over;
                    }
                }
            }
            if (distortion > 0 && 10 * Math.log10(distortion / energy) > -40) {
                loud++;
            }
        }
        return new double[]{1e6 * clipped / ((double) pcm[0].length * pcm.length), loud};
    }

    /**
     * The crossfade lift may take no sample past the louder grain's peak in its crossfade, nor past full scale: before,
     * a loud master clipped in the game in its lifted crossfades (a 2x copy of one song had clipping distortion louder
     * than -40 dB in 157 of its 1230 50 ms windows, where the song had none). Uniform noise near full scale is the
     * hardest case, since every grain reaches its peak; the old lift took it to 1.33. Gaussian noise shows the lift
     * still does its work: the level stays within 0.3 dB, where no lift at all loses 1 to 1.2 dB to the crossfades.
     */
    private static void stretchCap() throws IOException {
        int rate = 44100;
        float[][] uniform = noise(rate * 6, 0.95, 1, 99L);
        float[][] gaussian = noise(rate * 6, 0.05, 12, 7L);
        liftCeiling(rate);
        for (double speed : new double[]{1.5, 2.0}) {
            float[][] loud = stretch(uniform, rate, speed);
            float peak = 0f;
            for (float[] channel : loud) {
                for (float value : channel) {
                    peak = Math.max(peak, Math.abs(value));
                }
            }
            check(peak <= 0.95f + 1e-6f, "x" + speed + ": noise peaking at 0.95 stays within its own peak, got " + peak);
            double level = levelDb(stretch(gaussian, rate, speed)) - levelDb(gaussian);
            System.out.printf(Locale.ROOT, "  stretch x%.1f: noise at 0.95 peaks at %.4f; gaussian noise level %+.2f dB%n",
                    speed, peak, level);
            check(Math.abs(level) <= 0.3, "x" + speed + ": the crossfades keep the level of gaussian noise, " + level + " dB");
        }
    }

    /**
     * Below 0.9 of full scale the lift stops at 0.9, even where the louder grain peaks higher: the copy is encoded once
     * more, and the encoder's error took samples the lift had brought right up to full scale over it, so the 2x copies
     * of loud masters clipped 1.3 to 1.8 times as often as with no lift at all once decoded. Noise at 0.8 with a spike
     * at 0.99 every 10 ms gives every crossfade a grain peaking at 0.99: with the lift allowed up to full scale, more
     * samples came out above 0.9 than there are spikes (1239 to 1427 against 600); now only the spikes' own copies do.
     */
    private static void liftCeiling(int rate) throws IOException {
        float[] pcm = new float[rate * 6];
        long seed = 4242L;
        int spikes = 0;
        for (int i = 0; i < pcm.length; i++) {
            seed = seed * 6364136223846793005L + 1442695040888963407L;
            pcm[i] = (float) (0.8 * (2.0 * ((seed >>> 11) / (double) (1L << 53)) - 1.0));
            if (i % 441 == 220) {
                pcm[i] = (i / 441) % 2 == 0 ? 0.99f : -0.99f;
                spikes++;
            }
        }
        for (double speed : new double[]{1.5, 2.0}) {
            int over = 0;
            for (float value : stretch(new float[][]{pcm}, rate, speed)[0]) {
                if (Math.abs(value) > 0.9001f) {
                    over++;
                }
            }
            System.out.printf(Locale.ROOT, "  lift ceiling x%.1f: %d samples above 0.9 for %d spikes%n", speed, over, spikes);
            check(over < spikes, "x" + speed + ": the lift takes no sample above 0.9 that a spike did not bring, "
                    + over + " above it for " + spikes + " spikes");
        }
    }

    /**
     * A sample that is not a number is silence from the decoder on. Before, one NaN in a damaged float WAV spoiled
     * the stretcher's filter and onset history for the rest of the song (in one 2x copy the onsets within 5 ms fell
     * from 100 to 62 percent after it), and an infinity came out of the encoder as a burst at 55 times full scale.
     * Here a float WAV with a NaN, two infinities and an absurd value: every click after them still lands within 5 ms,
     * and the copy is no louder than the clean song's.
     */
    private static void damagedSamples(Path work) throws Exception {
        Path folder = Files.createDirectories(work.resolve("damaged"));
        int rate = 44100;
        Clicks clicks = clickPcm(rate, 2, 8.0);
        float[][] pcm = clicks.pcm();
        int frames = pcm[0].length;
        pcm[0][frames * 4 / 10] = Float.NaN;
        pcm[1][frames * 6 / 10] = Float.POSITIVE_INFINITY;
        pcm[0][frames * 7 / 10] = Float.NEGATIVE_INFINITY;
        pcm[1][frames * 8 / 10] = 1e30f;
        Path source = folder.resolve("damaged.wav");
        Files.write(source, floatWavBytes(pcm, rate));
        Song song = new Song("float WAV with NaN", source, rate, 2, frames, clicks.clicks());
        for (float speed : new float[]{1.0F, 2.0F}) {
            Path target = folder.resolve("damaged_x" + speed + ".ogg");
            NativeAudio.convertToVorbis(source, target, speed);
            Decoded decoded = decode(target);
            float peak = 0f;
            boolean finite = true;
            for (float[] channel : decoded.pcm()) {
                for (float value : channel) {
                    finite &= Float.isFinite(value);
                    peak = Math.max(peak, Math.abs(value));
                }
            }
            check(finite, "damaged samples x" + speed + ": the copy has only numbers");
            check(peak < 1.2f, "damaged samples x" + speed + ": no burst where the damage was, peak " + peak);
            double worst = clickTiming(song, decoded, speed, "damaged samples x" + speed);
            System.out.printf(Locale.ROOT, "  damaged float WAV x%.1f: peak %.3f, worst click %.2f ms%n", speed, peak, worst);
        }
    }

    /**
     * A speed copy of an Ogg song lines up with what Minecraft plays of the song, sample for sample, because the
     * conversion decodes an Ogg source exactly as the game's JOrbisAudioStream does. The fixture was cut from a longer
     * song with ffmpeg's stream copy: the game plays 448 samples of its long first block and the 29121 samples the file
     * asks to drop, which the old converter dropped, so its copies of such a song ran early (185 ms at 1x on a real
     * one). Every noise burst of every copy must land within 5 ms of where the game plays it divided by the speed, half
     * a millisecond plain. A song cut off part way through a page converts (the game plays it up to its last whole
     * page; the old converter refused it, and the battle fell back to 1x); damage the game would play out of step from
     * is refused.
     */
    private static void minecraftTimeline(Path root, Path work) throws Exception {
        Path folder = Files.createDirectories(work.resolve("timeline"));
        Path cut = root.resolve("tools/vorbis-decoder-support/lv-cut-long-first.ogg");
        float[][] game = jorbisPcm(cut);
        equal(game[0].length, 133696, "Minecraft plays the cut song for 133696 frames");
        int[] bursts = bursts(game);
        equal(bursts.length, 5, "the cut song's five noise bursts are found");
        for (float speed : SPEEDS) {
            Path target = folder.resolve("cut_x" + speed + ".ogg");
            NativeAudio.convertToVorbis(cut, target, speed);
            float[][] copy = jorbisPcm(target);
            equal((long) copy[0].length, Math.round(133696 / (double) speed), "cut x" + speed + ": the copy's length");
            double worst = 0.0;
            for (int burst : bursts) {
                double offset = landing(game, burst, copy, speed);
                worst = Math.max(worst, Math.abs(offset));
                check(Math.abs(offset) <= (speed == 1.0F ? 0.5 : 5.0), "cut x" + speed + ": the burst the game plays at "
                        + burst + " lands " + offset + " ms from its place");
            }
            System.out.printf(Locale.ROOT, "  cut song x%.1f: %d frames as the game plays it, worst burst %.2f ms%n",
                    speed, copy[0].length, worst);
        }

        byte[] bytes = Files.readAllBytes(cut);
        Path truncated = folder.resolve("truncated.ogg");
        Files.write(truncated, Arrays.copyOf(bytes, bytes.length * 7 / 10));
        float[][] played = jorbisPcm(truncated);
        Path truncatedCopy = folder.resolve("truncated_x2.ogg");
        NativeAudio.convertToVorbis(truncated, truncatedCopy, 2.0F);
        equal((long) jorbisPcm(truncatedCopy)[0].length, Math.round(played[0].length / 2.0),
                "a song cut off part way through a page converts, as long as the game plays it, halved");

        byte[] damaged = bytes.clone();
        damaged[bytes.length / 2] ^= 0x40;
        Path bad = folder.resolve("bad_checksum.ogg");
        Files.write(bad, damaged);
        Path badCopy = folder.resolve("bad_checksum_x2.ogg");
        try {
            NativeAudio.convertToVorbis(bad, badCopy, 2.0F);
            fail("a song with a damaged page in the middle converts, although the game plays it out of step from there");
        } catch (UnsupportedFormatException wrong) {
            fail("a damaged page is reported as an unsupported format");
        } catch (AudioFormatException expected) {
            checks++;
        }
        check(!Files.exists(badCopy), "and nothing is written for it");
    }

    /**
     * Songs the old detection or decoders refused although ffmpeg plays them, and codecs inside WAV said by name. The
     * MP3 is the committed fixture without a tag; ffmpeg's decode of each was compared when these were written.
     */
    private static void formats(Path root, Path work) throws Exception {
        Path folder = Files.createDirectories(work.resolve("formats"));
        playsAsIs(root, work, folder);
        byte[] mp3 = Files.readAllBytes(root.resolve("tools/fixtures/mp3/notag.mp3"));
        long mp3Frames = decode(root.resolve("tools/fixtures/mp3/notag.mp3")).frames();
        Map<String, byte[]> playable = new java.util.LinkedHashMap<>();
        playable.put("stray-byte.mp3", concat(new byte[]{0x5A}, mp3));
        playable.put("zeros.mp3", concat(new byte[3000], mp3));
        playable.put("junk.mp3", concat("not a tag, not a frame".repeat(100).getBytes(StandardCharsets.US_ASCII), mp3));
        playable.put("id3-then-ogg.ogg", concat(id3v2(700), Files.readAllBytes(work.resolve("track/track.ogg"))));
        playable.put("two-id3-then-ogg.ogg", concat(id3v2(300), id3v2(200), Files.readAllBytes(work.resolve("track/track.ogg"))));
        playable.put("id3-then-wav.wav", concat(id3v2(500), Files.readAllBytes(work.resolve("in/click.wav"))));
        playable.put("mp3-in-wav.wav", mp3InWav(mp3));
        playable.put("mulaw.wav", g711Wav(7, clickPcm(8000, 1, 2.0).pcm()[0]));
        for (Map.Entry<String, byte[]> entry : playable.entrySet()) {
            Path file = folder.resolve(entry.getKey());
            Files.write(file, entry.getValue());
            Path target = folder.resolve(entry.getKey() + ".ogg");
            try {
                NativeAudio.convertToVorbis(file, target, 1.0F);
                check(NativeAudio.isOggVorbis(target), entry.getKey() + " converts");
            } catch (IOException exception) {
                fail(entry.getKey() + " is refused although ffmpeg plays it: " + exception);
            }
        }
        for (String name : List.of("stray-byte.mp3", "zeros.mp3", "junk.mp3", "mp3-in-wav.wav")) {
            Path target = folder.resolve(name + ".ogg");
            if (Files.exists(target)) {
                equal((long) decode(target).frames(), mp3Frames, name + ": the whole MP3, as ffmpeg plays it");
            }
        }
        Path plainOgg = work.resolve("track/track.ogg");
        long oggFrames = jorbisPcm(plainOgg)[0].length;
        for (String name : List.of("id3-then-ogg.ogg", "two-id3-then-ogg.ogg")) {
            Path target = folder.resolve(name + ".ogg");
            if (Files.exists(target)) {
                equal((long) jorbisPcm(target)[0].length, oggFrames, name + ": the song behind the tag, whole");
            }
        }
        // IMA ADPCM at 4 bits a sample is read (the extras fixture, which native-audio-extras checks against ffmpeg);
        // the 3-bit variant, which hardly anything writes, is a format the mod does not read, by name.
        Path ima = root.resolve("tools/fixtures/extras/wav-ima.wav");
        NativeAudio.convertToVorbis(ima, folder.resolve("ima.ogg"), 1.0F);
        equal(jorbisFrames(folder.resolve("ima.ogg")), 38779L, "an IMA ADPCM WAV converts to its whole length");
        Path adpcm = folder.resolve("adpcm.wav");
        Files.write(adpcm, adpcmWav());
        try {
            NativeAudio.convertToVorbis(adpcm, folder.resolve("adpcm.ogg"), 1.0F);
            fail("a 3-bit IMA ADPCM WAV converts");
        } catch (UnsupportedFormatException expected) {
            equal(expected.formatName(), "WAV with IMA ADPCM audio (3 bits)",
                    "a 3-bit IMA ADPCM WAV is a format the mod does not read, by name");
        } catch (AudioFormatException wrong) {
            fail("a 3-bit IMA ADPCM WAV is reported as damaged: " + wrong.getMessage());
        }
        check(!Files.exists(folder.resolve("adpcm.ogg")), "and nothing is written for it");
    }

    /** A committed fixture of a format the AAC, ALAC and Opus decoders added, and what its content must be taken for. */
    private record Fixture(String path, AudioDecoders.Kind kind) {
    }

    /**
     * Every format the AAC, ALAC and Opus decoders added, end to end: each fixture is sniffed by its content, also
     * under a name that says another format, then converted plain, at 1.5x and at 2x, and read back by Minecraft's own
     * JOrbisAudioStream, the decoder the game plays songs with. The plain conversion must be exactly as long as the
     * decoder's own output and line up with it at lag 0 (a conversion that came out early or late by even one frame
     * would move every note of a beatmap made on it); the faster ones must be round(frames / speed) long and pass the
     * server's isValidSpeedVariant. The fixtures are short songs made from synthetic signals (see each decoder's
     * check), so this costs a few seconds.
     */
    private static void newFormats(Path root, Path work) throws Exception {
        Path folder = Files.createDirectories(work.resolve("new-formats"));
        List<Fixture> fixtures = List.of(
                new Fixture("aac/lc-afconvert.m4a", AudioDecoders.Kind.MP4),
                new Fixture("aac/he-afconvert.m4a", AudioDecoders.Kind.MP4),
                new Fixture("aac/hev2-afconvert.m4a", AudioDecoders.Kind.MP4),
                new Fixture("aac/lc-fragmented.m4a", AudioDecoders.Kind.MP4),
                new Fixture("aac/lc-dash.m4a", AudioDecoders.Kind.MP4),
                new Fixture("aac/lc-5.1.m4a", AudioDecoders.Kind.MP4),
                new Fixture("aac/lc-stereo-44k.aac", AudioDecoders.Kind.ADTS),
                new Fixture("aac/lc-mono-22k.aac", AudioDecoders.Kind.ADTS),
                new Fixture("aac/he-implicit.aac", AudioDecoders.Kind.ADTS),
                new Fixture("aac/hev2-implicit.aac", AudioDecoders.Kind.ADTS),
                new Fixture("alac/alac-afconvert-16.m4a", AudioDecoders.Kind.MP4),
                new Fixture("alac/alac-afconvert-24-mono.m4a", AudioDecoders.Kind.MP4),
                new Fixture("opus/celt-stereo-96k.opus", AudioDecoders.Kind.OPUS),
                new Fixture("opus/silk-wb-stereo-10ms.opus", AudioDecoders.Kind.OPUS),
                new Fixture("opus/hybrid-fec-stereo.opus", AudioDecoders.Kind.OPUS),
                new Fixture("opus/surround51.opus", AudioDecoders.Kind.OPUS),
                new Fixture("opus/stereo.webm", AudioDecoders.Kind.WEBM),
                // surround51.opus's packets stream-copied into WebM by ffmpeg 9 (its first second): 48 648 frames, as
                // ffmpeg decodes it, and 87 dB from libopus once ffmpeg's WAVE order is mapped to Opus's Vorbis order.
                new Fixture("opus/surround51.webm", AudioDecoders.Kind.WEBM),
                // The formats that completed the converter (tools/fixtures/extras; native-audio-extras checks each
                // one's decode against ffmpeg's).
                new Fixture("extras/ts-hls-aac.m4a", AudioDecoders.Kind.TS),
                new Fixture("extras/ts-mp3.ts", AudioDecoders.Kind.TS),
                new Fixture("extras/ts-opus.ts", AudioDecoders.Kind.TS),
                new Fixture("extras/ts-opus51.ts", AudioDecoders.Kind.TS),
                new Fixture("extras/ts-aac.m2ts", AudioDecoders.Kind.TS),
                new Fixture("extras/mp4-opus.mp4", AudioDecoders.Kind.MP4),
                new Fixture("extras/mp4-opus51.mp4", AudioDecoders.Kind.MP4),
                new Fixture("extras/mkv-vorbis.webm", AudioDecoders.Kind.WEBM),
                new Fixture("extras/mkv-aac.mka", AudioDecoders.Kind.WEBM),
                new Fixture("extras/mkv-aac51.mka", AudioDecoders.Kind.WEBM),
                new Fixture("extras/mkv-mp3.mka", AudioDecoders.Kind.WEBM),
                new Fixture("extras/mkv-flac.mka", AudioDecoders.Kind.WEBM),
                new Fixture("extras/wav-ima.wav", AudioDecoders.Kind.WAV),
                new Fixture("extras/wav-ms.wav", AudioDecoders.Kind.WAV),
                new Fixture("extras/mp3-free.mp3", AudioDecoders.Kind.MP3));
        // The name never decides: each file is sniffed again under the name of a format it is not, as downloads and
        // osu! maps name them (an M4A called .mp3, raw AAC called .m4a, Opus called .ogg, WebM called .opus).
        Map<AudioDecoders.Kind, String> wrongName = Map.of(AudioDecoders.Kind.MP4, ".mp3", AudioDecoders.Kind.ADTS, ".m4a",
                AudioDecoders.Kind.OPUS, ".ogg", AudioDecoders.Kind.WEBM, ".opus", AudioDecoders.Kind.TS, ".m4a",
                AudioDecoders.Kind.WAV, ".mp3", AudioDecoders.Kind.MP3, ".wav");
        for (Fixture fixture : fixtures) {
            Path source = root.resolve("tools/fixtures").resolve(fixture.path());
            String label = fixture.path();
            equal(AudioDecoders.detect(source), fixture.kind(), label + " is sniffed as " + fixture.kind());
            Path renamed = folder.resolve(label.replace('/', '_') + wrongName.get(fixture.kind()));
            Files.copy(source, renamed);
            equal(AudioDecoders.detect(renamed), fixture.kind(), label + " named " + renamed.getFileName() + " is still "
                    + fixture.kind());
            Decoded reference = decode(source);
            Path plain = folder.resolve(label.replace('/', '_') + "_x1.ogg");
            for (float speed : SPEEDS) {
                Path target = speed == 1.0F ? plain : folder.resolve(label.replace('/', '_') + "_x" + speed + ".ogg");
                NativeAudio.convertToVorbis(renamed, target, speed);
                long expected = (long) Math.floor(reference.frames() / (double) speed + 0.5);
                float[][] game = jorbisPcm(target);
                String at = label + " x" + speed;
                equal((long) game[0].length, expected, at + ": Minecraft's JOrbisAudioStream gets exactly the decoded length");
                equal(game.length, Math.min(reference.channels(), 2), at + ": channels, folded to stereo above two");
                equal(decode(target).rate(), reference.rate(), at + ": the sample rate is kept");
                if (speed > 1.0F) {
                    check(validSpeedVariant(plain, target, speed), at + ": isValidSpeedVariant accepts it");
                    continue;
                }
                // The lag at which the game's samples match the decoder's best: Vorbis is lossy, so a cross-correlation
                // over +-64 frames rather than an exact compare. A song of more than two channels is compared with its
                // decode folded to stereo as NativeAudio folds it.
                Decoded heard = new Decoded(reference.rate(), game.length, game[0].length, game);
                if (reference.channels() <= 2) {
                    int lag = bestLag(reference.pcm()[0], game[0], 64);
                    equal(lag, 0, at + ": lines up with the decoder's own output (lag)");
                    double snr = snr(reference, heard);
                    System.out.printf(Locale.ROOT, "  %-32s %s: %d frames at %d Hz, lag %d, %.1f dB against its decode%n",
                            label, fixture.kind(), game[0].length, reference.rate(), lag, snr);
                    check(snr >= 10.0, at + ": the OGG is the same audio, sample for sample in time (" + snr + " dB)");
                } else {
                    // The channel order is the format's own, not the decoder's choice: MPEG-4 audio comes out in
                    // WAVE order (L R C LFE Ls Rs), Opus in Vorbis order (L C R Ls Rs LFE, RFC 7845 section
                    // 5.1.1.2). Folded in the wrong one, a centre lands in one ear and a surround in the front.
                    ChannelDownmix.Layout layout = fixture.kind().layout();
                    ChannelDownmix.Layout other = layout == ChannelDownmix.Layout.WAVE
                            ? ChannelDownmix.Layout.VORBIS : ChannelDownmix.Layout.WAVE;
                    Decoded folded = fold(reference, layout);
                    int lag = bestLag(folded.pcm()[0], game[0], 64);
                    equal(lag, 0, at + ": lines up with the decoder's own output, folded (lag)");
                    double right = snr(folded, heard);
                    double wrong = snr(fold(reference, other), heard);
                    System.out.printf(Locale.ROOT, "  %-32s %s: %d channels folded, %d frames, lag %d, %.1f dB against"
                            + " its decode folded in %s order (%.1f dB in %s order)%n", label, fixture.kind(),
                            reference.channels(), game[0].length, lag, right, layout, wrong, other);
                    check(right >= 10.0 && right >= wrong + 6.0, at + ": folded to stereo in " + layout + " order ("
                            + right + " dB, " + wrong + " dB in " + other + " order)");
                }
            }
        }
        // A WebM whose sound is not Opus and an Opus song with no speakers to fold (ambisonics) are named by detect()
        // itself, so the osu! importer and the editor say so at once rather than part way through a conversion.
        byte[] webm = Files.readAllBytes(root.resolve("tools/fixtures/opus/stereo.webm"));
        String text = new String(webm, StandardCharsets.ISO_8859_1);
        System.arraycopy("A_EAC3".getBytes(StandardCharsets.US_ASCII), 0, webm, text.indexOf("A_OPUS"), 6);
        refusedAtDetect(folder, "eac3.webm", webm, "WebM/MKV (E-AC-3)");
        refusedAtDetect(folder, "ambisonic.opus", ambisonicOpusPage(), "Ambisonic Opus");
        // One ADTS header, then nothing that chains: seven bytes pass for a header about once in 10 000 random heads,
        // so this is no AAC, and the file is refused unnamed rather than failing later as a damaged AAC song.
        byte[] lone = Arrays.copyOf(Files.readAllBytes(root.resolve("tools/fixtures/aac/lc-stereo-44k.aac")), 8192);
        new java.util.Random(7).nextBytes(lone);
        System.arraycopy(Files.readAllBytes(root.resolve("tools/fixtures/aac/lc-stereo-44k.aac")), 0, lone, 0, 7);
        refusedAtDetect(folder, "lone-header.aac", lone, null);
        // Raw AAC cut part way into a frame whose leftover bytes happen to hold two MPEG audio frames in a row, as the
        // AAC data of about one cut stream in 360 does: the chain of three ADTS frames is the stronger test and is asked
        // first, so the song is AAC and converts from its first whole frame, not a short MP3 that then fails.
        byte[] adts = Files.readAllBytes(root.resolve("tools/fixtures/aac/lc-stereo-44k.aac"));
        byte[] falsePair = concat(new byte[]{0x5A},
                Arrays.copyOf(Files.readAllBytes(root.resolve("tools/fixtures/mp3/notag.mp3")), 1200),
                Arrays.copyOfRange(adts, 100, adts.length));
        Path pair = folder.resolve("false-mp3-pair.aac");
        Files.write(pair, falsePair);
        equal(AudioDecoders.detect(pair), AudioDecoders.Kind.ADTS, "AAC whose cut-off start holds two MPEG frames is ADTS");
        Path pairOgg = folder.resolve("false-mp3-pair.ogg");
        NativeAudio.convertToVorbis(pair, pairOgg, 1.0F);
        equal(jorbisFrames(pairOgg), 70656L, "and converts from its first whole AAC frame");
    }

    /** A song detect() itself must refuse, by name or unnamed (null). */
    private static void refusedAtDetect(Path folder, String name, byte[] bytes, String format) throws IOException {
        Path file = folder.resolve(name);
        Files.write(file, bytes);
        try {
            fail(name + " is taken for " + AudioDecoders.detect(file));
        } catch (UnsupportedFormatException expected) {
            equal(expected.formatName(), format, name + " is refused by detect(), named " + format);
        } catch (AudioFormatException wrong) {
            fail(name + " is reported as damaged by detect(): " + wrong.getMessage());
        }
    }

    /** A decoded song folded to stereo the way NativeAudio folds it, in the given channel order. */
    private static Decoded fold(Decoded song, ChannelDownmix.Layout layout) throws IOException {
        try (PcmStream stream = ChannelDownmix.toStereo(new ArrayStream(song.pcm(), song.rate()), layout)) {
            float[][] out = new float[2][song.frames()];
            float[][] buffer = new float[2][4096];
            int frames = 0;
            int read;
            while (frames < song.frames() && (read = stream.read(buffer, 0, Math.min(4096, song.frames() - frames))) > 0) {
                for (int c = 0; c < 2; c++) {
                    System.arraycopy(buffer[c], 0, out[c], frames, read);
                }
                frames += read;
            }
            return new Decoded(song.rate(), 2, frames, out);
        }
    }

    /**
     * The first Ogg page of an ambisonic Opus song (channel mapping family 2, four channels): a valid header of an
     * Opus variant with no speaker layout to fold into stereo. The page carries its checksum, because the Opus reader
     * checks it and would otherwise report damage before it read the header.
     */
    private static byte[] ambisonicOpusPage() {
        byte[] head = new byte[25];
        ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN).put("OpusHead".getBytes(StandardCharsets.US_ASCII))
                .put((byte) 1).put((byte) 4).putShort((short) 312).putInt(48000).putShort((short) 0).put((byte) 2)
                .put((byte) 4).put((byte) 0).put(new byte[]{0, 1, 2, 3});
        ByteBuffer page = ByteBuffer.allocate(28 + head.length).order(ByteOrder.LITTLE_ENDIAN);
        page.put("OggS".getBytes(StandardCharsets.US_ASCII)).put((byte) 0).put((byte) 2).putLong(0L).putInt(1).putInt(0)
                .putInt(0).put((byte) 1).put((byte) head.length).put(head);
        byte[] bytes = page.array();
        // The Ogg checksum: CRC-32 with polynomial 0x04c11db7, most significant bit first, no inversion.
        int crc = 0;
        for (byte b : bytes) {
            crc ^= (b & 0xFF) << 24;
            for (int i = 0; i < 8; i++) {
                crc = crc < 0 ? (crc << 1) ^ 0x04c11db7 : crc << 1;
            }
        }
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(22, crc);
        return bytes;
    }

    /** The shift of {@code test} against {@code reference}, within {@code range} frames, that correlates best. */
    private static int bestLag(float[] reference, float[] test, int range) {
        int best = 0;
        double bestScore = Double.NEGATIVE_INFINITY;
        int length = Math.min(reference.length, test.length);
        for (int lag = -range; lag <= range; lag++) {
            double score = 0.0;
            for (int i = Math.max(0, -lag); i < length && i + lag < length; i++) {
                score += reference[i] * (double) test[i + lag];
            }
            if (score > bestScore) {
                bestScore = score;
                best = lag;
            }
        }
        return best;
    }

    /**
     * What the converter leaves on disk, and how it fails when it was never meant to. The finished file gets the
     * permissions any new file in its folder gets (Files.createTempFile made it readable by its owner only). Temporary
     * files a killed game left behind go once they are an hour old, and only those. An unexpected exception inside
     * the converter (here a read-only zip file system) reaches the caller as an IOException with the exception as its
     * cause, never unwrapped, and the osu! importer answers it like any other failure instead of throwing.
     */
    /**
     * "Choose audio file" for a Workshop beatmap published without its song (claims-vs-code F5): an Ogg the game plays
     * is taken as it is (exact by checksum, or by length); anything else the mod reads is converted with its own
     * converter, in the mod's temporary folder inside the Minecraft folder, and then checked by length; a song of the
     * wrong length, or a format nobody reads, leaves nothing behind. Every Workshop format the picker offers is one the
     * converter reads.
     */
    private static void workshopChosenSong(Path root, Path work) throws Exception {
        Path in = Files.createDirectories(work.resolve("chosen"));
        Path set = Files.createDirectories(MusicBattleTracks.tracksRootDirectory().resolve("workshop/installed/ws_42"));
        Path target = set.resolve("ws_42.ogg");
        Song wav = clickSong("chosen-wav", in.resolve("song.wav"), 44100, 2, 16, 12.0, false);
        // The fixture writer numbers at most 128 FLAC blocks, so the FLAC song is a little shorter.
        Song flac = clickSong("chosen-flac", in.resolve("song.flac"), 48000, 2, 16, 10.5, true);
        long declaredMs = Math.round(wav.frames() * 1000.0 / wav.rate());
        AtomicConverting converting = new AtomicConverting();
        for (Song song : List.of(wav, flac)) {
            Files.deleteIfExists(target);
            converting.count = 0;
            check(ru.gora.musicbattle.client.workshop.ChosenAudio.needsConversion(song.file()), song.label() + " needs converting first");
            long songMs = Math.round(song.frames() * 1000.0 / song.rate());
            var result = ru.gora.musicbattle.client.workshop.ChosenAudio.adopt(song.file(), target, "0".repeat(64), songMs,
                    converting::ran);
            equal(result.kind(), ru.gora.musicbattle.client.workshop.ChosenAudio.Kind.BY_DURATION,
                    song.label() + " is converted and matched by length");
            check(result.converted() && converting.count == 1, song.label() + ": the screen was told once that it converts");
            check(NativeAudio.playsAsIs(target), song.label() + ": the beatmap's song is now an Ogg the game plays");
            check(Math.abs(decode(target).frames() * 1000L / song.outputRate() - songMs) <= 50, song.label() + " keeps its length");
        }
        // The converted file again, as an Ogg: no conversion, matched by length; and the exact checksum wins.
        Path ogg = Files.copy(target, in.resolve("song.ogg"));
        Files.deleteIfExists(target);
        converting.count = 0;
        var asIs = ru.gora.musicbattle.client.workshop.ChosenAudio.adopt(ogg, target, null, declaredMs, converting::ran);
        check(asIs.kind() == ru.gora.musicbattle.client.workshop.ChosenAudio.Kind.BY_DURATION && !asIs.converted()
                && converting.count == 0, "an Ogg the game plays is taken as it is, by length");
        String sha = ru.gora.musicbattle.client.workshop.InstalledActions.sha256(ogg);
        var exact = ru.gora.musicbattle.client.workshop.ChosenAudio.adopt(ogg, target, sha.toUpperCase(Locale.ROOT), 1L, null);
        check(exact.kind() == ru.gora.musicbattle.client.workshop.ChosenAudio.Kind.EXACT, "an Ogg with the beatmap's checksum is exact");
        check(java.util.Arrays.equals(Files.readAllBytes(ogg), Files.readAllBytes(target)), "and copied byte for byte");

        // The wrong song: converted, measured, refused, and the beatmap's folder is left as it was.
        Files.deleteIfExists(target);
        var wrong = ru.gora.musicbattle.client.workshop.ChosenAudio.adopt(wav.file(), target, null, declaredMs + 30_000L, null);
        check(wrong.kind() == ru.gora.musicbattle.client.workshop.ChosenAudio.Kind.MISMATCH && wrong.converted(), "a song of another length is refused");
        check(!Files.exists(target), "and nothing is written into the beatmap's folder");
        // A file nobody reads: refused as unsupported, nothing written.
        Path junk = Files.write(in.resolve("notes.m4a"), "not a song at all, only words".repeat(40).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var unsupported = ru.gora.musicbattle.client.workshop.ChosenAudio.adopt(junk, target, null, declaredMs, null);
        check(unsupported.kind() == ru.gora.musicbattle.client.workshop.ChosenAudio.Kind.UNSUPPORTED
                || unsupported.kind() == ru.gora.musicbattle.client.workshop.ChosenAudio.Kind.CONVERSION_FAILED,
                "a file that is no song is refused: " + unsupported);
        check(!Files.exists(target), "and nothing is written for it either");

        // Every conversion happened inside the Minecraft folder, and left nothing behind there.
        Path temporary = MusicBattleTracks.temporaryDirectory();
        Path game = net.neoforged.fml.loading.FMLPaths.GAMEDIR.get().toAbsolutePath().normalize();
        check(temporary.startsWith(game), "the mod's temporary folder is inside the Minecraft folder: " + temporary);
        try (var left = Files.list(temporary)) {
            List<Path> rest = left.toList();
            check(rest.isEmpty(), "no converted copy is left in the temporary folder: " + rest);
        }
        try (var left = Files.list(set)) {
            List<Path> rest = left.filter(path -> !path.equals(target)).toList();
            check(rest.isEmpty(), "and no converter part file in the beatmap's folder: " + rest);
        }

        // The picker offers what the converter reads, and the messages say so.
        String files = Files.readString(root.resolve("src/main/java/ru/gora/musicbattle/client/workshop/WorkshopFiles.java"));
        java.util.regex.Matcher audio = java.util.regex.Pattern.compile("AUDIO = \\{([^}]*)\\}").matcher(files);
        check(audio.find(), "the Workshop's song filter is found");
        String editor = Files.readString(root.resolve("src/main/java/ru/gora/musicbattle/client/TrackEditorScreen.java"));
        for (String pattern : audio.group(1).split(",")) {
            String extension = pattern.trim().replace("\"", "").replace("*", "");
            check(editor.contains("\"" + extension + "\""), "the Workshop picker's " + extension + " is one the editor converts too");
        }
        String installed = Files.readString(root.resolve("src/main/java/ru/gora/musicbattle/client/workshop/InstalledActions.java"));
        check(!installed.contains("audio.not_ogg") && installed.contains("ChosenAudio.adopt"),
                "the Workshop no longer tells the player to convert the song elsewhere");
        for (String file : List.of("en_us.json", "ru_ru.json")) {
            Map<String, String> values = new com.google.gson.Gson().fromJson(Files.readString(root.resolve("src/main/resources/assets/music_battle/lang/" + file)),
                    new com.google.gson.reflect.TypeToken<Map<String, String>>() { }.getType());
            for (String key : List.of("converting_title", "converting", "added_converted", "unsupported_title", "unsupported",
                    "unsupported_named", "convert_failed_title", "convert_failed")) {
                check(values.containsKey("screen.music_battle.workshop.audio." + key), file + " has workshop.audio." + key);
            }
            check(!values.containsKey("screen.music_battle.workshop.audio.not_ogg"), file + " no longer asks to convert the song elsewhere");
        }
    }

    /** Counts the "converting" notices. */
    private static final class AtomicConverting {
        int count;

        void ran() {
            count++;
        }
    }

    private static void filesAndFailures(Path root, Path work) throws Exception {
        Path folder = Files.createDirectories(work.resolve("files"));
        Path source = work.resolve("in/click.wav");
        Path target = folder.resolve("song.ogg");
        Path stale = folder.resolve(".song.ogg.1234567890.part");
        Path recent = folder.resolve(".song.ogg.987654321.part");
        Path notOurs = folder.resolve(".notes.part");
        Path userFile = folder.resolve("song.part");
        for (Path file : List.of(stale, recent, notOurs, userFile)) {
            Files.write(file, new byte[]{1});
        }
        long twoHoursAgo = System.currentTimeMillis() - java.util.concurrent.TimeUnit.HOURS.toMillis(2);
        for (Path file : List.of(stale, notOurs, userFile)) {
            Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(twoHoursAgo));
        }
        NativeAudio.convertToVorbis(source, target, 1.0F);
        check(!Files.exists(stale), "a temporary file of a conversion a killed game left two hours ago is deleted");
        check(Files.exists(recent), "a recent one, which may belong to a running conversion, is kept");
        check(Files.exists(notOurs) && Files.exists(userFile), "files that are not the converter's own are never touched");

        if (Files.getFileStore(folder).supportsFileAttributeView("posix")) {
            Path plain = Files.createFile(folder.resolve("plain-new-file"));
            var expected = Files.getPosixFilePermissions(plain);
            var actual = Files.getPosixFilePermissions(target);
            equal(java.nio.file.attribute.PosixFilePermissions.toString(actual),
                    java.nio.file.attribute.PosixFilePermissions.toString(expected),
                    "the converted song has the permissions of any new file in its folder");
        } else {
            System.out.println("  permissions: this file system has no POSIX permissions to compare");
        }

        Path zip = folder.resolve("read-only.zip");
        try (var fs = java.nio.file.FileSystems.newFileSystem(zip, Map.of("create", "true"))) {
            Files.createDirectories(fs.getPath("/out"));
        }
        check(zip.toFile().setWritable(false), "the zip can be made read-only");
        try (var fs = java.nio.file.FileSystems.newFileSystem(zip)) {
            Path unwritable = fs.getPath("/out/song.ogg");
            try {
                NativeAudio.convertToVorbis(source, unwritable, 1.0F);
                fail("a conversion into a read-only file system succeeds");
            } catch (AudioFormatException wrong) {
                fail("an unexpected failure is reported as a damaged song");
            } catch (IOException expected) {
                check(expected.getCause() instanceof RuntimeException,
                        "an unexpected exception reaches the caller as an IOException with it as the cause: " + expected);
            } catch (RuntimeException unwrapped) {
                fail("an unexpected exception escapes the converter unwrapped: " + unwrapped);
            }
            Path raw = Files.createDirectories(MusicBattleTracks.rawDirectory());
            Path original = raw.resolve("unexpected.wav");
            Files.copy(source, original, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            ClientOsuManiaImporter.SongResult result;
            try {
                result = new ClientOsuManiaImporter.PendingSong(original, unwritable, ".wav").convert();
            } catch (RuntimeException escaped) {
                fail("the osu! importer lets an unexpected failure escape: " + escaped);
                result = null;
            }
            check(result != null && result.leftBehind() != null && !result.leftBehind().reason().isEmpty()
                    && !result.leftBehind().unsupportedFormat(), "the osu! importer reports it as a failed conversion");
            check(Files.isRegularFile(original), "and keeps the song for the player");
        } finally {
            zip.toFile().setWritable(true);
        }

        // The editor's Audio button converts on a worker that must always end in a status line.
        String editor = Files.readString(root.resolve("src/main/java/ru/gora/musicbattle/client/TrackEditorScreen.java"));
        int convert = editor.indexOf("private void convertAudioToOgg(");
        check(convert >= 0 && editor.indexOf("catch (IOException | RuntimeException | Error exception)", convert) > convert,
                "the editor's conversion worker catches every failure, so its status line never stays on converting");
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Helpers for the findings above

    /** A song in memory, as a stream. */
    private static final class ArrayStream implements PcmStream {
        private final float[][] pcm;
        private final int rate;
        private int position;

        ArrayStream(float[][] pcm, int rate) {
            this.pcm = pcm;
            this.rate = rate;
        }

        @Override
        public int sampleRate() {
            return rate;
        }

        @Override
        public int channels() {
            return pcm.length;
        }

        @Override
        public int read(float[][] out, int offset, int frames) {
            int count = Math.min(frames, pcm[0].length - position);
            if (count <= 0) {
                return -1;
            }
            for (int c = 0; c < pcm.length; c++) {
                System.arraycopy(pcm[c], position, out[c], offset, count);
            }
            position += count;
            return count;
        }

        @Override
        public void close() {
        }
    }

    private static float[][] stretch(float[][] pcm, int rate, double speed) throws IOException {
        try (PcmStream stream = TimeStretch.wrap(new ArrayStream(pcm, rate), speed)) {
            int channels = stream.channels();
            float[][] out = new float[channels][(int) TimeStretch.outputFrames(pcm[0].length, speed)];
            float[][] buffer = new float[channels][4096];
            int frames = 0;
            int read;
            while ((read = stream.read(buffer, 0, 4096)) > 0) {
                for (int c = 0; c < channels; c++) {
                    System.arraycopy(buffer[c], 0, out[c], frames, read);
                }
                frames += read;
            }
            return out;
        }
    }

    /** Stereo noise, independent per channel: the mean of {@code terms} uniform values, scaled to {@code level}. */
    private static float[][] noise(int frames, double level, int terms, long seed) {
        float[][] out = new float[2][frames];
        for (int i = 0; i < frames; i++) {
            for (int c = 0; c < 2; c++) {
                double sum = 0.0;
                for (int t = 0; t < terms; t++) {
                    seed = seed * 6364136223846793005L + 1442695040888963407L;
                    sum += 2.0 * ((seed >>> 11) / (double) (1L << 53)) - 1.0;
                }
                out[c][i] = (float) (level * (terms == 1 ? sum : sum / 2.0));
            }
        }
        return out;
    }

    private static double levelDb(float[][] pcm) {
        double energy = 0.0;
        for (float[] channel : pcm) {
            for (float value : channel) {
                energy += value * (double) value;
            }
        }
        return 10.0 * Math.log10(energy / (pcm.length * (double) pcm[0].length));
    }

    /** What Minecraft's JOrbisAudioStream plays of a file, channel by channel. */
    private static float[][] jorbisPcm(Path file) throws IOException {
        float[][] holder = {new float[1 << 16]};
        int[] count = {0};
        int channels;
        try (InputStream input = Files.newInputStream(file);
             net.minecraft.client.sounds.JOrbisAudioStream stream = new net.minecraft.client.sounds.JOrbisAudioStream(input)) {
            channels = stream.getFormat().getChannels();
            while (stream.readChunk(value -> {
                if (count[0] == holder[0].length) {
                    holder[0] = Arrays.copyOf(holder[0], holder[0].length * 2);
                }
                holder[0][count[0]++] = value;
            })) {
                // a file the game cannot read throws before its end
            }
        }
        int frames = count[0] / channels;
        float[][] out = new float[channels][frames];
        for (int i = 0; i < frames; i++) {
            for (int c = 0; c < channels; c++) {
                out[c][i] = holder[0][i * channels + c];
            }
        }
        return out;
    }

    /** Where noise bursts start over the cut song's soft chord: a first difference well above the 40 ms before it. */
    private static int[] bursts(float[][] pcm) {
        List<Integer> found = new ArrayList<>();
        float[] left = pcm[0];
        int block = 64;
        double[] energy = new double[left.length / block];
        for (int b = 0; b < energy.length; b++) {
            for (int i = Math.max(1, b * block); i < b * block + block; i++) {
                double d = left[i] - left[i - 1];
                energy[b] += d * d;
            }
        }
        int history = 28;
        int last = -100;
        for (int b = history; b < energy.length - 20; b++) {
            double before = 0.0;
            for (int k = b - history; k < b; k++) {
                before = Math.max(before, energy[k]);
            }
            // The codec's pre-echo raises the blocks just before a burst, so the jump asked for is a modest one.
            if (energy[b] > 8.0 * before && energy[b] > 0.05 && b - last > 100) {
                found.add(b * block);
                last = b;
            }
        }
        return found.stream().mapToInt(Integer::intValue).toArray();
    }

    /**
     * How far from {@code at / speed} the 25 ms of the song from 3 ms before {@code at} land in the copy, by normalised
     * cross-correlation within 12 ms, in milliseconds; NaN-free, and a miss counts as far off.
     */
    private static double landing(float[][] song, int at, float[][] copy, float speed) {
        int rate = 44100;
        int pre = (int) (0.003 * rate);
        int length = (int) (0.025 * rate);
        int search = (int) (0.012 * rate);
        int start = at - pre;
        double ideal = at / (double) speed - pre;
        int centre = (int) Math.round(ideal);
        double songEnergy = 0.0;
        for (int i = 0; i < length; i++) {
            songEnergy += song[0][start + i] * (double) song[0][start + i];
        }
        double best = -2.0;
        int bestAt = centre;
        for (int p = Math.max(0, centre - search); p <= Math.min(copy[0].length - length - 1, centre + search); p++) {
            double cross = 0.0;
            double copyEnergy = 0.0;
            for (int i = 0; i < length; i++) {
                cross += song[0][start + i] * (double) copy[0][p + i];
                copyEnergy += copy[0][p + i] * (double) copy[0][p + i];
            }
            double score = cross / Math.sqrt(songEnergy * copyEnergy + 1e-30);
            if (score > best) {
                best = score;
                bestAt = p;
            }
        }
        return best < 0.8 ? 1000.0 : (bestAt - ideal) * 1000.0 / rate;
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }

    /** An ID3v2.4 tag of {@code size} body bytes, as taggers put in front of any kind of song. */
    private static byte[] id3v2(int size) {
        byte[] tag = new byte[10 + size];
        tag[0] = 'I';
        tag[1] = 'D';
        tag[2] = '3';
        tag[3] = 4;
        tag[6] = (byte) ((size >> 21) & 0x7F);
        tag[7] = (byte) ((size >> 14) & 0x7F);
        tag[8] = (byte) ((size >> 7) & 0x7F);
        tag[9] = (byte) (size & 0x7F);
        return tag;
    }

    private static byte[] wavWithFormat(byte[] format, byte[] data) {
        ByteBuffer out = ByteBuffer.allocate(12 + 8 + format.length + 8 + data.length + (data.length & 1))
                .order(ByteOrder.LITTLE_ENDIAN);
        out.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(out.capacity() - 8)
                .put("WAVE".getBytes(StandardCharsets.US_ASCII)).put("fmt ".getBytes(StandardCharsets.US_ASCII))
                .putInt(format.length).put(format).put("data".getBytes(StandardCharsets.US_ASCII)).putInt(data.length)
                .put(data);
        return out.array();
    }

    /** MP3 frames in a WAV (format 0x55), as some tools write them. */
    private static byte[] mp3InWav(byte[] mp3) {
        ByteBuffer format = ByteBuffer.allocate(30).order(ByteOrder.LITTLE_ENDIAN);
        format.putShort((short) 0x55).putShort((short) 2).putInt(44100).putInt(8000).putShort((short) 1)
                .putShort((short) 0).putShort((short) 12).putShort((short) 1).putInt(2).putShort((short) 417)
                .putShort((short) 1).putShort((short) 1393);
        return wavWithFormat(format.array(), mp3);
    }

    /** One channel of telephone audio, G.711 A-law (6) or mu-law (7), by the standard's encoding rule. */
    private static byte[] g711Wav(int tag, float[] samples) {
        byte[] data = new byte[samples.length];
        for (int i = 0; i < samples.length; i++) {
            int value = (int) Math.max(-32768, Math.min(32767, Math.round(samples[i] * 32767.0)));
            int sign = value < 0 ? 0x80 : 0;
            int magnitude = Math.min(32635, Math.abs(value)) + 0x84;
            int segment = 7;
            for (int mask = 0x4000; (magnitude & mask) == 0 && segment > 0; mask >>= 1) {
                segment--;
            }
            int step = (magnitude >> (segment + 3)) & 0x0F;
            data[i] = (byte) ~(sign | segment << 4 | step);
        }
        ByteBuffer format = ByteBuffer.allocate(18).order(ByteOrder.LITTLE_ENDIAN);
        format.putShort((short) tag).putShort((short) 1).putInt(8000).putInt(8000).putShort((short) 1)
                .putShort((short) 8).putShort((short) 0);
        return wavWithFormat(format.array(), data);
    }

    /** The header of a 3-bit IMA ADPCM WAV and some data: enough to be recognised, nothing here decodes it. */
    private static byte[] adpcmWav() {
        ByteBuffer format = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN);
        format.putShort((short) 0x11).putShort((short) 2).putInt(44100).putInt(33075).putShort((short) 2048)
                .putShort((short) 3).putShort((short) 2).putShort((short) 2721);
        return wavWithFormat(format.array(), new byte[8192]);
    }

    /** A 32-bit float WAV, values as they are: the damaged ones included. */
    private static byte[] floatWavBytes(float[][] pcm, int rate) {
        int channels = pcm.length;
        int frames = pcm[0].length;
        ByteBuffer format = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
        format.putShort((short) 3).putShort((short) channels).putInt(rate).putInt(rate * channels * 4)
                .putShort((short) (channels * 4)).putShort((short) 32);
        ByteBuffer data = ByteBuffer.allocate(frames * channels * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < frames; i++) {
            for (float[] channel : pcm) {
                data.putFloat(channel[i]);
            }
        }
        return wavWithFormat(format.array(), data.array());
    }

    private static boolean hasPart(Path folder) throws IOException {
        try (var files = Files.list(folder)) {
            return files.anyMatch(path -> path.getFileName().toString().endsWith(".part"));
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Source rules and translations

    private static void sources(Path root) throws IOException {
        Path src = root.resolve("src/main/java/ru/gora/musicbattle");
        for (String gone : List.of("audio/FfmpegVorbis.java", "client/ClientFfmpeg.java", "client/ClientAudioSpeedProcessor.java")) {
            check(!Files.exists(src.resolve(gone)), gone + " is gone");
        }
        try (var files = Files.walk(src)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String text = Files.readString(file);
                String name = src.relativize(file).toString().replace('\\', '/');
                check(!text.contains("ProcessBuilder") && !text.matches("(?s).*Runtime\\.getRuntime\\(\\)\\s*\\.exec\\b.*"),
                        name + " starts no external process");
                check(!text.contains("FfmpegVorbis") && !text.contains("ClientFfmpeg"), name + " calls no ffmpeg helper");
                if (name.startsWith("audio/")) {
                    for (String banned : List.of("import net.minecraft", "import com.mojang", "import org.lwjgl",
                            "import net.neoforged", "import ru.gora.musicbattle.client", "import ru.gora.musicbattle.server")) {
                        check(!text.contains(banned), name + " needs no game class (" + banned + "), so a dedicated server loads it");
                    }
                }
            }
        }
    }

    /**
     * The messages the conversion paths show. The translations are merged after the code, so until the new key is in
     * the files this only says so; from then on every key must be in both languages and no line may mention ffmpeg.
     */
    private static void translations(Path root) throws IOException {
        Path lang = root.resolve("src/main/resources/assets/music_battle/lang");
        String english = Files.readString(lang.resolve("en_us.json"));
        if (!english.contains("\"music_battle.l10n.editor.import_audio_unsupported\"")) {
            System.out.println("NOTE: the translations for the native converter are not merged yet; their checks are skipped");
            return;
        }
        List<String> keys = List.of(
                "music_battle.l10n.server.speed_audio_preparing", "music_battle.l10n.server.speed_audio_failed",
                "music_battle.l10n.server.speed_audio_waiting", "music_battle.l10n.editor.import_audio_converting",
                "music_battle.l10n.editor.import_audio_ready", "music_battle.l10n.editor.import_audio_unsupported",
                "music_battle.l10n.editor.import_audio_failed", "music_battle.l10n.editor.audio_unsupported_format",
                "music_battle.l10n.editor.audio_convert_failed", "music_battle.l10n.editor.converting_to_ogg",
                "music_battle.l10n.editor.converted_to_ogg", "music_battle.l10n.editor.audio_unsupported_named");
        for (MusicBattleTracks.SpeedRefusal refusal : MusicBattleTracks.SpeedRefusal.values()) {
            check(keys.contains(refusal.messageKey()), "the refusal " + refusal + " has its message in this list");
        }
        for (String file : List.of("en_us.json", "ru_ru.json")) {
            Map<String, String> values = new com.google.gson.Gson().fromJson(Files.readString(lang.resolve(file)),
                    new com.google.gson.reflect.TypeToken<Map<String, String>>() { }.getType());
            for (String key : keys) {
                check(values.containsKey(key), file + " has " + key);
            }
            values.forEach((key, value) -> check(!value.toLowerCase(Locale.ROOT).contains("ffmpeg"),
                    file + ": " + key + " no longer mentions ffmpeg"));
        }
    }

    /**
     * What the editor and the osu! importer take as it is: Ogg Vorbis from the first byte with one or two channels.
     * Minecraft leaves a 5.1 Ogg silent (its decoder refuses the file), so such a song used to be kept as it was, play
     * at x1.5 and x2 only (the copies are folded to stereo) and stay silent at 1x; now it is converted like an MP3.
     */
    private static void playsAsIs(Path root, Path work, Path folder) throws Exception {
        Path support = root.resolve("tools/vorbis-decoder-support");
        for (String name : List.of("ff-stereo-48k.ogg", "lv-mono-22k-sweep.ogg", "lv-stereo-44k-transients.ogg")) {
            check(NativeAudio.playsAsIs(support.resolve(name)), name + " plays as it is");
        }
        check(NativeAudio.playsAsIs(work.resolve("track/track.ogg")), "the converter's own output plays as it is");
        Path surround = support.resolve("lv-6ch-48k.ogg");
        check(NativeAudio.isOggVorbis(surround) && !NativeAudio.playsAsIs(surround),
                "a 5.1 Ogg Vorbis is Ogg Vorbis, but not one the game plays as it is");
        boolean refused;
        try (InputStream input = Files.newInputStream(surround);
             net.minecraft.client.sounds.JOrbisAudioStream stream = new net.minecraft.client.sounds.JOrbisAudioStream(input)) {
            refused = !stream.readChunk(value -> { });
        } catch (Exception expected) {
            refused = true;
        }
        check(refused, "Minecraft's own decoder does refuse the 5.1 Ogg");
        Path converted = folder.resolve("surround_converted.ogg");
        NativeAudio.convertToVorbis(surround, converted, 1.0F);
        check(NativeAudio.playsAsIs(converted), "converted, the 5.1 Ogg plays as it is");
        Path tagged = folder.resolve("tagged-track.ogg");
        Files.write(tagged, concat(id3v2(300), Files.readAllBytes(work.resolve("track/track.ogg"))));
        check(!NativeAudio.playsAsIs(tagged), "an Ogg behind an ID3 tag does not play as it is");
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Decoding

    private record Decoded(int rate, int channels, int frames, float[][] pcm) {
    }

    private static Decoded decode(Path file) throws IOException {
        try (PcmStream stream = AudioDecoders.open(file)) {
            int channels = stream.channels();
            float[][] pcm = new float[channels][1 << 16];
            float[][] buffer = new float[channels][4096];
            int frames = 0;
            int read;
            while ((read = stream.read(buffer, 0, 4096)) > 0) {
                if (frames + read > pcm[0].length) {
                    for (int c = 0; c < channels; c++) {
                        pcm[c] = Arrays.copyOf(pcm[c], Math.max(frames + read, pcm[c].length * 2));
                    }
                }
                for (int c = 0; c < channels; c++) {
                    System.arraycopy(buffer[c], 0, pcm[c], frames, read);
                }
                frames += read;
            }
            for (int c = 0; c < channels; c++) {
                pcm[c] = Arrays.copyOf(pcm[c], frames);
            }
            return new Decoded(stream.sampleRate(), channels, frames, pcm);
        }
    }

    /** The frames Minecraft's own decoder gives, the one the game plays songs with. */
    private static long jorbisFrames(Path file) throws IOException {
        long[] samples = {0L};
        int channels;
        try (InputStream input = Files.newInputStream(file);
             net.minecraft.client.sounds.JOrbisAudioStream stream = new net.minecraft.client.sounds.JOrbisAudioStream(input)) {
            channels = stream.getFormat().getChannels();
            while (stream.readChunk(value -> samples[0]++)) {
                // Counting is all that is needed: a stream the decoder cannot read throws before the end.
            }
        }
        return samples[0] / channels;
    }

    private static boolean validSpeedVariant(Path source, Path target, float speed) throws Exception {
        return (Boolean) isValidSpeedVariant.invoke(null, source, target, speed);
    }

    private static double snr(Decoded reference, Decoded test) {
        double signal = 0.0;
        double noise = 0.0;
        for (int c = 0; c < reference.channels(); c++) {
            for (int i = 0; i < Math.min(reference.frames(), test.frames()); i++) {
                double d = test.pcm()[c][i] - reference.pcm()[c][i];
                signal += reference.pcm()[c][i] * (double) reference.pcm()[c][i];
                noise += d * d;
            }
        }
        return 10.0 * Math.log10(signal / Math.max(1e-30, noise));
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Generated songs

    /**
     * A click track over a soft two-tone pad: bipolar clicks 280 to 420 ms apart, on every channel but the LFE. The
     * same layout in every format, so a wrong offset, length or speed shows up as clicks in the wrong place.
     */
    private static Song clickSong(String label, Path file, int rate, int channels, int bits, double seconds,
                                  boolean flac) throws IOException {
        Clicks song = clickPcm(rate, channels, seconds);
        Files.write(file, flac ? flacBytes(song.pcm(), rate) : wavBytes(song.pcm(), rate, bits));
        return new Song(label, file, rate, channels, song.pcm()[0].length, song.clicks());
    }

    /** A click track's samples and where its clicks are. */
    private record Clicks(float[][] pcm, int[] clicks) {
    }

    private static Clicks clickPcm(int rate, int channels, double seconds) {
        int frames = (int) Math.round(rate * seconds);
        float[][] pcm = new float[channels][frames];
        for (int i = 0; i < frames; i++) {
            double t = i / (double) rate;
            float pad = (float) (0.06 * Math.sin(2 * Math.PI * 220 * t) + 0.04 * Math.sin(2 * Math.PI * 331 * t + 1.0));
            for (int c = 0; c < channels; c++) {
                pcm[c][i] = pad;
            }
        }
        List<Integer> clicks = new ArrayList<>();
        long seed = rate;
        for (int x = (int) (0.3 * rate); x < frames - rate / 4; ) {
            clicks.add(x);
            for (int c = 0; c < channels; c++) {
                if (channels == 6 && c == 3) {
                    continue;
                }
                pcm[c][x] += 0.7F;
                pcm[c][x + 1] -= 0.35F;
            }
            seed = seed * 6364136223846793005L + 1442695040888963407L;
            x += (int) (rate * (0.28 + 0.14 * ((seed >>> 33) / (double) (1L << 31))));
        }
        return new Clicks(pcm, clicks.stream().mapToInt(Integer::intValue).toArray());
    }

    /** A plain PCM WAV, 16 or 24 bits, any number of channels (more than two in WAVE order). */
    private static byte[] wavBytes(float[][] pcm, int rate, int bits) {
        int channels = pcm.length;
        int frames = pcm[0].length;
        int bytes = bits / 8;
        ByteBuffer out = ByteBuffer.allocate(44 + frames * channels * bytes).order(ByteOrder.LITTLE_ENDIAN);
        out.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + frames * channels * bytes)
                .put("WAVE".getBytes(StandardCharsets.US_ASCII)).put("fmt ".getBytes(StandardCharsets.US_ASCII))
                .putInt(16).putShort((short) 1).putShort((short) channels).putInt(rate)
                .putInt(rate * channels * bytes).putShort((short) (channels * bytes)).putShort((short) bits)
                .put("data".getBytes(StandardCharsets.US_ASCII)).putInt(frames * channels * bytes);
        for (int i = 0; i < frames; i++) {
            for (float[] channel : pcm) {
                int value = quantize(channel[i], bits);
                for (int b = 0; b < bytes; b++) {
                    out.put((byte) (value >> (8 * b)));
                }
            }
        }
        return out.array();
    }

    private static int quantize(float sample, int bits) {
        double scale = (1 << (bits - 1)) - 1;
        return (int) Math.round(Math.max(-1.0, Math.min(1.0, sample)) * scale);
    }

    /**
     * A 16-bit FLAC with fixed 4096-frame blocks of verbatim subframes: the simplest legal stream, enough to prove the
     * converter reads FLAC by its content. One or two independent channels.
     */
    private static byte[] flacBytes(float[][] pcm, int rate) {
        int channels = pcm.length;
        int frames = pcm[0].length;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes("fLaC".getBytes(StandardCharsets.US_ASCII));
        out.write(0x80);
        out.write(0);
        out.write(0);
        out.write(34);
        Bits info = new Bits();
        info.put(4096, 16).put(4096, 16).put(0, 24).put(0, 24).put(rate, 20).put(channels - 1, 3).put(15, 5);
        info.put((int) (((long) frames) >>> 32), 4).put(frames, 32);
        for (int i = 0; i < 16; i++) {
            info.put(0, 8);
        }
        out.writeBytes(info.bytes());
        for (int block = 0, start = 0; start < frames; block++, start += 4096) {
            int size = Math.min(4096, frames - start);
            Bits frame = new Bits();
            frame.put(0xFFF8, 16).put(size == 4096 ? 12 : 7, 4).put(0, 4).put(channels - 1, 4).put(4, 3).put(0, 1);
            if (block >= 128) {
                throw new IllegalArgumentException("the fixture writer only numbers 128 blocks");
            }
            frame.put(block, 8);
            if (size != 4096) {
                frame.put(size - 1, 16);
            }
            frame.put(crc8(frame.bytes()), 8);
            for (int c = 0; c < channels; c++) {
                frame.put(0x02, 8);
                for (int i = 0; i < size; i++) {
                    frame.put(quantize(pcm[c][start + i], 16) & 0xFFFF, 16);
                }
            }
            byte[] body = frame.bytes();
            out.writeBytes(body);
            int crc = crc16(body);
            out.write(crc >> 8);
            out.write(crc & 0xFF);
        }
        return out.toByteArray();
    }

    /** Big-endian bit packing, whole bytes only when read. */
    private static final class Bits {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private long pending;
        private int count;

        Bits put(int value, int bits) {
            for (int i = bits - 1; i >= 0; i--) {
                pending = (pending << 1) | ((value >>> i) & 1);
                if (++count == 8) {
                    out.write((int) pending);
                    pending = 0;
                    count = 0;
                }
            }
            return this;
        }

        byte[] bytes() {
            if (count != 0) {
                throw new IllegalStateException("not byte aligned");
            }
            return out.toByteArray();
        }
    }

    private static int crc8(byte[] bytes) {
        int crc = 0;
        for (byte b : bytes) {
            crc ^= b & 0xFF;
            for (int i = 0; i < 8; i++) {
                crc = (crc & 0x80) != 0 ? ((crc << 1) ^ 0x07) & 0xFF : (crc << 1) & 0xFF;
            }
        }
        return crc;
    }

    private static int crc16(byte[] bytes) {
        int crc = 0;
        for (byte b : bytes) {
            crc ^= (b & 0xFF) << 8;
            for (int i = 0; i < 8; i++) {
                crc = (crc & 0x8000) != 0 ? ((crc << 1) ^ 0x8005) & 0xFFFF : (crc << 1) & 0xFFFF;
            }
        }
        return crc;
    }

    /**
     * An M4A whose sound the mod does not read: the AAC fixture with its sample entry renamed from mp4a to ac-3, so only
     * the file's index says what it holds, as in the AC-3 sound of some video downloads.
     */
    private static byte[] ac3InMp4(Path root) throws IOException {
        byte[] m4a = Files.readAllBytes(root.resolve("tools/fixtures/aac/lc-afconvert.m4a"));
        String text = new String(m4a, StandardCharsets.ISO_8859_1);
        int entry = text.indexOf("mp4a", text.indexOf("stsd"));
        if (entry < 0) {
            throw new IOException("the AAC fixture has no mp4a sample entry");
        }
        System.arraycopy("ac-3".getBytes(StandardCharsets.US_ASCII), 0, m4a, entry, 4);
        return m4a;
    }

    /** Where AudioDecoders' search for AAC that starts part way into a frame would start in these bytes, or -1. */
    private static int adtsChainIn(byte[] bytes) {
        byte[] probe = Arrays.copyOf(bytes, Math.min(bytes.length, AdtsDecoder.SCAN_BYTES + AdtsDecoder.SYNC_PROBE_BYTES));
        return AdtsDecoder.findFrameStart(probe, AdtsDecoder.SCAN_BYTES);
    }

    /** A transport stream as Blu-ray discs and camcorders store it: each 188-byte packet behind a four-byte time stamp. */
    private static byte[] m2ts(byte[] ts) {
        ByteBuffer out = ByteBuffer.allocate(ts.length / 188 * 192);
        for (int at = 0; at + 188 <= ts.length; at += 188) {
            out.putInt(at * 15).put(ts, at, 188);
        }
        return out.array();
    }

    /** One transport stream packet over and over, its counter never stepping: data that only repeats every 188 bytes. */
    private static byte[] repeatedPacket() {
        byte[] packet = new byte[188];
        packet[0] = 0x47;
        packet[1] = 0x01;
        packet[3] = 0x10;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < 70; i++) {
            out.writeBytes(packet);
        }
        return out.toByteArray();
    }

    /** The start of an M4A file cut off after its first box: an ISO media box naming the M4A brand, and nothing else. */
    private static byte[] m4aBytes() {
        byte[] bytes = new byte[64];
        ByteBuffer.wrap(bytes).putInt(32).put("ftypM4A ".getBytes(StandardCharsets.US_ASCII));
        return bytes;
    }

    /** The start of a WMA file: the GUID of an ASF header object. */
    private static byte[] wmaBytes() {
        byte[] bytes = new byte[64];
        byte[] guid = {0x30, 0x26, (byte) 0xB2, 0x75, (byte) 0x8E, 0x66, (byte) 0xCF, 0x11, (byte) 0xA6, (byte) 0xD9, 0x00,
                (byte) 0xAA, 0x00, 0x62, (byte) 0xCE, 0x6C};
        System.arraycopy(guid, 0, bytes, 0, guid.length);
        return bytes;
    }

    /** The first Ogg page of an Opus file: one segment holding the OpusHead packet. */
    private static byte[] opusPage() {
        byte[] head = "OpusHead\u0001\u0002\u0000\u0000\u0080»\u0000\u0000\u0000\u0000\u0000".getBytes(StandardCharsets.ISO_8859_1);
        ByteBuffer page = ByteBuffer.allocate(28 + head.length).order(ByteOrder.LITTLE_ENDIAN);
        page.put("OggS".getBytes(StandardCharsets.US_ASCII)).put((byte) 0).put((byte) 2).putLong(0L).putInt(1).putInt(0)
                .putInt(0).put((byte) 1).put((byte) head.length).put(head);
        return page.array();
    }

    /** An ID3v2 tag followed by bytes that are no MPEG audio: an MP3 by its look, damaged by its content. */
    private static byte[] damagedMp3() {
        byte[] bytes = new byte[4096];
        byte[] tag = {'I', 'D', '3', 3, 0, 0, 0, 0, 0, 0};
        System.arraycopy(tag, 0, bytes, 0, tag.length);
        for (int i = tag.length; i < bytes.length; i++) {
            bytes[i] = (byte) (i * 31 + 7);
        }
        return bytes;
    }

    // ---------------------------------------------------------------------------------------------------------------

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
            FAILURES.add(label);
        }
    }

    private static void fail(String label) {
        FAILURES.add(label);
    }

    private static void equal(Object actual, Object expected, String label) {
        boolean same = java.util.Objects.equals(actual, expected);
        check(same, label + (same ? "" : ": expected " + expected + ", got " + actual));
    }
}

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import ru.gora.musicbattle.audio.NativeAudio;
import ru.gora.musicbattle.server.MusicBattleTracks;
import ru.gora.musicbattle.server.MusicBattleTracks.SpeedRefusal;

/**
 * The vote's speed-audio rules from the beta review, which a modded client could otherwise turn into an endless queue
 * of conversions:
 * <ul>
 *     <li>a vote stops asking for copies once {@link MusicBattleTracks#SPEED_VARIANT_VOTE_QUEUE_LIMIT} are queued,
 *     while the battle's own request at its start is never capped;</li>
 *     <li>stopping the server shuts the worker down and forgets the queued targets;</li>
 *     <li>only a player's first vote in a battle asks for a copy (read from BattleManager, which needs a server);</li>
 *     <li>package writes still queued when a transfer is dropped do not recreate its file (read from the source);</li>
 *     <li>the converter's temporary files that a killed server left next to a song go after ten minutes, by the
 *     converter's own rule, which the server reuses rather than keeping a second copy of (run on files, no
 *     conversion; read from the source);</li>
 *     <li>a copy without this build's mark (an older build's: ffmpeg's atempo, or this converter before its timeline
 *     matched the game's) is stale, so the battle does not play it and it is made again, once; and a song whose
 *     granule positions do not start at zero keeps its correct copy instead of having it rejected as the wrong speed
 *     (real conversions of a four-second song, a few dozen milliseconds each).</li>
 * </ul>
 * For the queue rules the worker is replaced by one that records tasks instead of running them, so no conversion runs
 * there.
 */
public final class SpeedVariantQueueChecks {
    private static int checks;

    /** Takes tasks and keeps them, so the check sees exactly what was queued without a conversion running. */
    private static final class RecordingExecutor extends AbstractExecutorService {
        final List<Runnable> tasks = new ArrayList<>();
        boolean shutdown;

        @Override public void execute(Runnable command) { tasks.add(command); }
        @Override public void shutdown() { shutdown = true; }
        @Override public List<Runnable> shutdownNow() { shutdown = true; return List.copyOf(tasks); }
        @Override public boolean isShutdown() { return shutdown; }
        @Override public boolean isTerminated() { return shutdown; }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return shutdown; }
    }

    public static void main(String[] args) throws Exception {
        Path project = Path.of(args.length > 0 ? args[0] : ".").toAbsolutePath().normalize();
        Path folder = Files.createTempDirectory("blocky-speed-queue");
        try {
            queueCap(folder);
            staleParts(folder);
            staleCopies(Files.createDirectories(folder.resolve("copies")));
        } finally {
            try (var files = Files.walk(folder)) {
                for (Path file : files.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(file);
                }
            }
        }
        sourceRules(project);
        System.out.println("Speed variant queue checks passed: " + checks);
        System.out.println("NOT COVERED: a modded client on a live server (a real conversion interrupted at shutdown is the"
                + " native-audio check's)");
    }

    @SuppressWarnings("unchecked")
    private static void queueCap(Path folder) throws Exception {
        Path audio = folder.resolve("song.ogg");
        Files.write(audio, new byte[] {1, 2, 3});
        MusicBattleTracks.Track track = new MusicBattleTracks.Track("queue_check", "Queue", "", 120, 2400, 1.0F,
                "song.ogg", "", "", false, false, List.of(), List.of(), folder, folder.resolve("queue_check.json"),
                MusicBattleTracks.Source.SERVER);
        Field inFlightField = MusicBattleTracks.class.getDeclaredField("SPEED_VARIANTS_IN_FLIGHT");
        inFlightField.setAccessible(true);
        Set<Path> inFlight = (Set<Path>) inFlightField.get(null);
        Field workerField = MusicBattleTracks.class.getDeclaredField("speedVariantWorker");
        workerField.setAccessible(true);
        RecordingExecutor worker = new RecordingExecutor();
        workerField.set(null, worker);

        int limit = MusicBattleTracks.SPEED_VARIANT_VOTE_QUEUE_LIMIT;
        check(limit == 4, "the vote queue limit is 4, got " + limit);
        for (int i = 0; i < limit; i++) {
            inFlight.add(folder.resolve("busy_" + i + "_x2.ogg"));
        }
        check(MusicBattleTracks.requestSpeedVariant(track, 2.0F) == null, "a skipped vote is no refusal");
        check(worker.tasks.isEmpty(), "a full queue takes no vote request, queued " + worker.tasks.size());
        check(inFlight.size() == limit, "a skipped vote marks nothing as being made");

        MusicBattleTracks.BattleAudio start = MusicBattleTracks.battleAudio(track, 2.0F);
        check(start.refusal() == SpeedRefusal.PREPARING, "the battle start waits for its copy, got " + start.refusal());
        check(worker.tasks.size() == 1, "the battle start queues past the vote limit, queued " + worker.tasks.size());
        check(inFlight.contains(folder.resolve("song_x2.ogg")), "the battle start marks its target as being made");

        inFlight.clear();
        for (int i = 0; i < limit - 1; i++) {
            inFlight.add(folder.resolve("busy_" + i + "_x2.ogg"));
        }
        check(MusicBattleTracks.requestSpeedVariant(track, 1.5F) == null, "a vote below the limit is accepted");
        check(worker.tasks.size() == 2, "a vote below the limit queues its copy, queued " + worker.tasks.size());
        check(inFlight.contains(folder.resolve("song_x15.ogg")), "the vote marks its target as being made");
        check(MusicBattleTracks.requestSpeedVariant(track, 1.5F) == null && worker.tasks.size() == 2,
                "the same target is never queued twice");

        MusicBattleTracks.stopSpeedVariantWorker();
        check(worker.shutdown, "stopping the server shuts the worker down");
        check(workerField.get(null) == null, "the next server starts a fresh worker");
        check(inFlight.isEmpty(), "targets that were only queued are forgotten, left " + inFlight);
    }

    /**
     * A temporary file of a conversion is named {@code .<target>.<number>.part}. Next to a battle's song the server has
     * those older than ten minutes deleted before it converts (no copy takes that long), with the converter's own
     * cleanup; a recent one may belong to a conversion still running for a DJ panel, and names that are not the
     * converter's are never touched.
     */
    private static void staleParts(Path folder) throws Exception {
        Field staleField = MusicBattleTracks.class.getDeclaredField("STALE_PART_MILLIS");
        staleField.setAccessible(true);
        long stale = (Long) staleField.get(null);
        check(stale == TimeUnit.MINUTES.toMillis(10), "a part counts as left behind after ten minutes, got " + stale);
        long old = System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(20);
        Path leftover = folder.resolve(".song_x2.ogg.4242424242.part");
        Path running = folder.resolve(".song_x2.ogg.1717171717.part");
        List<Path> notOurs = List.of(folder.resolve("song_x2.ogg.4242.part"), folder.resolve(".song_x2.ogg.part"),
                folder.resolve(".song_x2.ogg.tmp"), folder.resolve("song.ogg"));
        for (Path file : List.of(leftover, running)) {
            Files.write(file, new byte[]{1});
        }
        for (Path file : notOurs) {
            if (!Files.exists(file)) {
                Files.write(file, new byte[]{1});
            }
            Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(old));
        }
        Files.setLastModifiedTime(leftover, java.nio.file.attribute.FileTime.fromMillis(old));
        NativeAudio.deleteStaleTemporaryFiles(folder, stale);
        check(!Files.exists(leftover), "a part twenty minutes old is deleted");
        check(Files.exists(running), "a recent part is kept for the conversion it may belong to");
        for (Path file : notOurs) {
            check(Files.exists(file), file.getFileName() + " is not the converter's and is kept");
        }
    }

    /**
     * A server keeps the copies it made until the song changes. Copies made before the converter marked them are
     * stale: ffmpeg's atempo put only about half the strong onsets within 5 ms, and this converter, before its decoder
     * followed the game's timeline, made copies that ran early by (start trim + 448) / speed samples. Here such a copy,
     * the right length and newer than the song, is refused by the battle and made again, and the new one is kept.
     */
    @SuppressWarnings("unchecked")
    private static void staleCopies(Path folder) throws Exception {
        java.lang.reflect.Method ready = MusicBattleTracks.class.getDeclaredMethod("speedVariantReady", Path.class,
                Path.class, float.class);
        ready.setAccessible(true);
        int rate = 44100;
        Path song = folder.resolve("song.ogg");
        encode(song, clicks(rate, 4 * rate), rate, null);
        Path copy = folder.resolve("song_x2.ogg");
        MusicBattleTracks.Track track = new MusicBattleTracks.Track("stale_check", "Stale", "", 120, 2400, 1.0F,
                "song.ogg", "", "", false, false, List.of(), List.of(), folder, folder.resolve("stale_check.json"),
                MusicBattleTracks.Source.SERVER);
        String[] marks = {null, NativeAudio.SPEED_COPY_FIELD + "=" + (NativeAudio.SPEED_COPY_VERSION + 1)};
        for (String mark : marks) {
            String label = mark == null ? "an unmarked copy" : "a copy marked " + mark;
            // Exactly half the song, so only the mark tells it from a good copy.
            encode(copy, clicks(rate, 2 * rate), rate, mark);
            Files.setLastModifiedTime(copy, java.nio.file.attribute.FileTime.fromMillis(
                    Files.getLastModifiedTime(song).toMillis() + 60_000L));
            byte[] stale = Files.readAllBytes(copy);
            check(!(Boolean) ready.invoke(null, song, copy, 2.0F), label + " is not ready");
            check(MusicBattleTracks.preparedAudio(track, 2.0F).isEmpty(), label + " is not what a battle plays");
            MusicBattleTracks.createSpeedVariant(song, copy, 2.0F);
            check(!java.util.Arrays.equals(Files.readAllBytes(copy), stale), label + " is made again");
            check(NativeAudio.speedCopyVersion(copy) == NativeAudio.SPEED_COPY_VERSION,
                    "the new copy of " + label + " carries this build's mark");
            check((Boolean) ready.invoke(null, song, copy, 2.0F), "and is ready");
            check(MusicBattleTracks.preparedAudio(track, 2.0F).isPresent(), "and is what a battle plays");
            long modified = Files.getLastModifiedTime(copy).toMillis();
            byte[] made = Files.readAllBytes(copy);
            MusicBattleTracks.createSpeedVariant(song, copy, 2.0F);
            check(Files.getLastModifiedTime(copy).toMillis() == modified && java.util.Arrays.equals(Files.readAllBytes(copy), made),
                    "and it is made only once: the next request keeps it");
        }

        // The granule positions of a recording cut from a broadcast start where the broadcast was, not at zero. The
        // game plays it from its first sample all the same, and the converter's copy is right, but the server measured
        // the song by its last granule position, 30 s too long here, and threw the copy away as the wrong speed.
        Path shifted = folder.resolve("broadcast.ogg");
        Files.write(shifted, shiftGranules(Files.readAllBytes(song), 30L * rate));
        java.lang.reflect.Method info = MusicBattleTracks.class.getDeclaredMethod("readCachedOggInfo", Path.class);
        info.setAccessible(true);
        Object songInfo = info.invoke(null, song);
        Object shiftedInfo = info.invoke(null, shifted);
        java.lang.reflect.Method played = songInfo.getClass().getDeclaredMethod("playedSamples");
        played.setAccessible(true);
        long songLength = (Long) played.invoke(songInfo);
        long shiftedLength = (Long) played.invoke(shiftedInfo);
        check(songLength == 4L * rate, "the song is 4 s long, read " + songLength);
        check(shiftedLength == songLength, "a song whose granules start 30 s in is as long as it plays, read " + shiftedLength);
        Path shiftedCopy = folder.resolve("broadcast_x2.ogg");
        MusicBattleTracks.createSpeedVariant(shifted, shiftedCopy, 2.0F);
        check((Boolean) ready.invoke(null, shifted, shiftedCopy, 2.0F), "its x2 copy is accepted");

        // A stream rip cut with ffmpeg's "-c copy": whole pages from the middle of a song, renumbered, their granule
        // positions as they were. The game plays every packet from the first page on, which the last position does not
        // say (it was 0.15 to 0.40 s off on such cuts), so the server decodes the song once on Minecraft's timeline to
        // measure it, and the x2 copy, made by the converter of exactly what the game plays, is accepted.
        Path rip = folder.resolve("rip.ogg");
        Files.write(rip, dropAudioPages(Files.readAllBytes(song)));
        long ripPlayed = (Long) played.invoke(info.invoke(null, rip));
        long ripDecoded = 0;
        try (java.io.InputStream in = Files.newInputStream(rip);
             ru.gora.musicbattle.audio.codec.PcmStream s = ru.gora.musicbattle.audio.codec.VorbisDecoder.open(in,
                     ru.gora.musicbattle.audio.codec.VorbisDecoder.Timeline.MINECRAFT)) {
            float[][] buffer = new float[s.channels()][4096];
            int n;
            while ((n = s.read(buffer, 0, 4096)) > 0) {
                ripDecoded += n;
            }
        }
        check(ripPlayed == ripDecoded, "a \"-c copy\" cut is as long as the game plays it: read " + ripPlayed
                + ", plays " + ripDecoded);
        Path ripCopy = folder.resolve("rip_x2.ogg");
        MusicBattleTracks.createSpeedVariant(rip, ripCopy, 2.0F);
        check((Boolean) ready.invoke(null, rip, ripCopy, 2.0F), "and its x2 copy is accepted");

        // A song cut off in the middle of a page (an interrupted copy) plays up to its last whole page, and the
        // converter makes its copy of exactly that. The server could not read such a song's end and refused the copy,
        // so every battle on it fell back to 1x. Reading a duration still refuses it: that is also the check that a
        // song arrived whole.
        byte[] whole = Files.readAllBytes(song);
        Path cut = folder.resolve("cut.ogg");
        Files.write(cut, java.util.Arrays.copyOf(whole, middleOfLastPage(whole)));
        Path cutCopy = folder.resolve("cut_x2.ogg");
        MusicBattleTracks.createSpeedVariant(cut, cutCopy, 2.0F);
        check((Boolean) ready.invoke(null, cut, cutCopy, 2.0F), "the x2 copy of a song cut off mid-page is accepted");
        try {
            MusicBattleTracks.readOggDurationTicks(cut);
            check(false, "a song cut off mid-page has no duration, as a download that broke off has none");
        } catch (java.io.IOException expected) {
            checks++;
        }
    }

    /** Where the last page of an Ogg file is half read. */
    private static int middleOfLastPage(byte[] ogg) {
        int at = 0;
        int last = 0;
        while (at + 27 <= ogg.length) {
            int segments = ogg[at + 26] & 0xFF;
            int length = 27 + segments;
            for (int i = 0; i < segments; i++) {
                length += ogg[at + 27 + i] & 0xFF;
            }
            last = at + length / 2;
            at += length;
        }
        return last;
    }

    /** A four-second click track over quiet noise, stereo. */
    private static float[][] clicks(int rate, int frames) {
        float[][] pcm = new float[2][frames];
        long seed = 77L;
        for (int i = 0; i < frames; i++) {
            seed = seed * 6364136223846793005L + 1442695040888963407L;
            float noise = 0.05f * ((seed >>> 40) / (float) (1L << 24) - 0.5f);
            float click = i % (rate / 4) < 40 ? 0.6f : 0f;
            pcm[0][i] = noise + click;
            pcm[1][i] = noise - click;
        }
        return pcm;
    }

    /** Encodes with the mod's encoder, as any song or copy is; {@code comment} is one field to add, or null. */
    private static void encode(Path target, float[][] pcm, int rate, String comment) throws Exception {
        ru.gora.musicbattle.audio.codec.VorbisEncoder.Settings settings = new ru.gora.musicbattle.audio.codec.VorbisEncoder.Settings();
        if (comment != null) {
            int equals = comment.indexOf('=');
            settings.comment(comment.substring(0, equals), comment.substring(equals + 1));
        }
        try (var out = Files.newOutputStream(target)) {
            ru.gora.musicbattle.audio.codec.VorbisEncoder.encode(() -> new ru.gora.musicbattle.audio.codec.PcmStream() {
                int at;

                @Override public int sampleRate() { return rate; }
                @Override public int channels() { return pcm.length; }
                @Override public long frameCountHint() { return pcm[0].length; }
                @Override public int read(float[][] out1, int offset, int frames) {
                    int count = Math.min(frames, pcm[0].length - at);
                    if (count <= 0) {
                        return -1;
                    }
                    for (int c = 0; c < pcm.length; c++) {
                        System.arraycopy(pcm[c], at, out1[c], offset, count);
                    }
                    at += count;
                    return count;
                }
                @Override public void close() { }
            }, out, settings);
        }
    }

    /**
     * The Ogg Vorbis file from its third whole-packet audio page on, as ffmpeg's "-c copy" cuts at a page: the three
     * header pages, then pages renumbered and checksummed again, granule positions untouched. A page that begins with
     * the rest of a packet is not a place to cut, so the cut moves on to the next one that does not.
     */
    private static byte[] dropAudioPages(byte[] ogg) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int at = 0;
        int page = 0;
        int kept = 0;
        long packetsEnded = 0;
        int audioPages = 0;
        boolean cutting = true;
        while (at + 27 <= ogg.length) {
            int segments = ogg[at + 26] & 0xFF;
            int body = 0;
            long endedBefore = packetsEnded;
            for (int i = 0; i < segments; i++) {
                int lace = ogg[at + 27 + i] & 0xFF;
                body += lace;
                if (lace < 255) {
                    packetsEnded++;
                }
            }
            int length = 27 + segments + body;
            boolean header = endedBefore < 3;
            boolean continued = (ogg[at + 5] & 1) != 0;
            if (!header) {
                audioPages++;
                if (cutting && audioPages > 3 && !continued) {
                    cutting = false;
                }
            }
            if (header || !cutting) {
                byte[] copy = java.util.Arrays.copyOfRange(ogg, at, at + length);
                ByteBuffer b = ByteBuffer.wrap(copy).order(ByteOrder.LITTLE_ENDIAN);
                b.putInt(18, kept++);
                b.putInt(22, 0);
                int crc = 0;
                for (byte value : copy) {
                    crc ^= (value & 0xFF) << 24;
                    for (int k = 0; k < 8; k++) {
                        crc = (crc & 0x80000000) != 0 ? (crc << 1) ^ 0x04c11db7 : crc << 1;
                    }
                }
                b.putInt(22, crc);
                out.writeBytes(copy);
            }
            page++;
            at += length;
        }
        return out.toByteArray();
    }

    /** The same Ogg Vorbis file with every audio page's granule position moved {@code by} later, checksums redone. */
    private static byte[] shiftGranules(byte[] ogg, long by) {
        byte[] out = ogg.clone();
        int at = 0;
        long packetsEnded = 0;
        while (at + 27 <= out.length) {
            int segments = out[at + 26] & 0xFF;
            int body = 0;
            long endedBefore = packetsEnded;
            for (int i = 0; i < segments; i++) {
                int lace = out[at + 27 + i] & 0xFF;
                body += lace;
                if (lace < 255) {
                    packetsEnded++;
                }
            }
            int length = 27 + segments + body;
            ByteBuffer page = ByteBuffer.wrap(out, at, length).order(ByteOrder.LITTLE_ENDIAN);
            long granule = page.getLong(at + 6);
            if (endedBefore >= 3 && granule >= 0) {
                page.putLong(at + 6, granule + by);
            }
            page.putInt(at + 22, 0);
            int crc = 0;
            for (int i = at; i < at + length; i++) {
                crc ^= (out[i] & 0xFF) << 24;
                for (int k = 0; k < 8; k++) {
                    crc = (crc & 0x80000000) != 0 ? (crc << 1) ^ 0x04c11db7 : crc << 1;
                }
            }
            page.putInt(at + 22, crc);
            at += length;
        }
        return out;
    }

    private static void sourceRules(Path project) throws Exception {
        Path main = project.resolve("src/main/java/ru/gora/musicbattle");
        String tracks = Files.readString(main.resolve("server/MusicBattleTracks.java"));
        check(tracks.contains("NativeAudio.deleteStaleTemporaryFiles(target.getParent(), STALE_PART_MILLIS);"),
                "createSpeedVariant clears leftovers with the converter's own cleanup");
        check(!tracks.contains("newDirectoryStream"), "and keeps no copy of that cleanup of its own");
        String battle = Files.readString(main.resolve("server/BattleManager.java"));
        int start = battle.indexOf("public static void submitVote(");
        check(start >= 0, "BattleManager.submitVote exists");
        int end = battle.indexOf("\n    }\n", start);
        String submitVote = battle.substring(start, end);
        Matcher put = Pattern.compile("String (\\w+) = battle\\.votes\\.put\\(").matcher(submitVote);
        check(put.find(), "submitVote keeps what votes.put replaced");
        String previous = put.group(1);
        Matcher gated = Pattern.compile("if \\(" + previous + " == null\\) \\{\\s*(//[^\\n]*\\s*)*"
                + "MusicBattleTracks\\.requestSpeedVariant\\(").matcher(submitVote);
        check(gated.find(), "only a first vote asks for a sped-up copy");
        int calls = submitVote.split("requestSpeedVariant\\(", -1).length - 1;
        check(calls == 1, "submitVote asks for a copy in exactly one place, found " + calls);

        String transfers = Files.readString(main.resolve("server/workshop/ServerBeatmapTransfers.java"));
        check(transfers.contains("private volatile boolean dropped;"), "a transfer session knows it was dropped");
        Matcher drop = Pattern.compile("private static void drop\\(Session session\\) \\{\\s*session\\.dropped = true;")
                .matcher(transfers);
        check(drop.find(), "drop() marks the session before it deletes the file");
        Matcher write = Pattern.compile("io\\(\\)\\.execute\\(\\(\\) -> \\{\\s*if \\(session\\.dropped\\) \\{\\s*return;")
                .matcher(transfers);
        check(write.find(), "a queued write of a dropped transfer does nothing");
    }

    private static void check(boolean condition, String label) {
        if (!condition) {
            throw new AssertionError(label);
        }
        checks++;
    }
}

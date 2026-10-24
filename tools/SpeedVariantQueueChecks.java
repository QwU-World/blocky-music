import java.lang.reflect.Field;
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
import ru.gora.musicbattle.server.MusicBattleTracks;
import ru.gora.musicbattle.server.MusicBattleTracks.SpeedRefusal;

/**
 * The vote's speed-audio rules from the beta review, which a modded client could otherwise turn into an endless queue
 * of minute-long ffmpeg runs:
 * <ul>
 *     <li>a vote stops asking for copies once {@link MusicBattleTracks#SPEED_VARIANT_VOTE_QUEUE_LIMIT} are queued,
 *     while the battle's own request at its start is never capped;</li>
 *     <li>stopping the server shuts the worker down and forgets the queued targets;</li>
 *     <li>only a player's first vote in a battle asks for a copy (read from BattleManager, which needs a server);</li>
 *     <li>package writes still queued when a transfer is dropped do not recreate its file (read from the source).</li>
 * </ul>
 * The worker is replaced by one that records tasks instead of running them, so no ffmpeg is ever started.
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
        } finally {
            try (var files = Files.list(folder)) {
                for (Path file : files.toList()) {
                    Files.deleteIfExists(file);
                }
            }
            Files.deleteIfExists(folder);
        }
        sourceRules(project);
        System.out.println("Speed variant queue checks passed: " + checks);
        System.out.println("NOT COVERED: a real ffmpeg interrupted at shutdown and a modded client on a live server");
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

    private static void sourceRules(Path project) throws Exception {
        Path main = project.resolve("src/main/java/ru/gora/musicbattle");
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

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import ru.gora.musicbattle.client.BattleCameraInterpolation;
import ru.gora.musicbattle.client.ClientTrackChart;
import ru.gora.musicbattle.data.BattleClock;
import ru.gora.musicbattle.data.BattleNote;
import ru.gora.musicbattle.data.BattlePlacementMath;
import ru.gora.musicbattle.data.PlacementAnimationClock;
import ru.gora.musicbattle.server.BattleManager;
import ru.gora.musicbattle.server.DjPreloadAudience;

/** Pure regression checks for the battle start clock, note numbering, camera smoothing and DJ preload targeting. */
public final class BattleReliabilityChecks {
    private static final String TRACK_ID = "battle_reliability_chart";
    /** The project's own hard chart, which is what the numbering was first measured on. */
    private static final String AUTHORED_CHART =
            "run/music_battle_tracks/my_tracks/it_s_been_so_long__hard_/it_s_been_so_long__hard_.json";
    private static final float[] VOTED_SPEEDS = {1.0F, 1.5F, 2.0F};

    private static int checks;

    public static void main(String[] args) throws IOException {
        Path projectDir = Path.of(args.length > 0 ? args[0] : ".").toAbsolutePath().normalize();
        Path workDir = Path.of(args.length > 1 ? args[1] : "build/battle-reliability-checks")
                .toAbsolutePath().normalize();
        Files.createDirectories(workDir);

        cameraInterpolatesBetweenClientTicks();
        cameraUsesShortestYawPath();
        idlePanelUsesCurrentSpeakerCoverage();
        heldParticipantsAreTurnedBackToEachOther();
        bothPlayersReachTickZeroTogether();
        placementClipIgnoresClockCorrections();
        everyNoteKeepsItsNumberThroughTheClientReader(projectDir, workDir);
        theOldSortWouldStillHaveReorderedThisChart(projectDir);
        aSharedChartIsNotTooLongForTheClientReader(workDir);
        System.out.println("Battle reliability checks PASS: " + checks);
    }

    /**
     * The one invariant a battle between two people cannot survive without: note number N means the same note on
     * both sides. The server stamps it from the order it builds its runtime notes in and writes that number into
     * the chart; the client reads the chart and judges by it. The client used to sort the notes it had read, by
     * tick, owner and lane - and a shared chart interleaves a pair per source note, so the numbering is
     * note-major while that sort is owner-major. Every hit on a note the sort had moved was then rejected by the
     * server, and came back three seconds later as a miss worth minus twenty-four and the combo.
     *
     * <p>Both sides of the round trip are the real thing: the server's own order, the server's own writer and the
     * client's own reader.
     */
    private static void everyNoteKeepsItsNumberThroughTheClientReader(Path projectDir, Path workDir) throws IOException {
        List<BattleNote> map = sourceMap(projectDir);
        for (float speed : VOTED_SPEEDS) {
            // A duel on a one-sided map, which is the case the owner hit: both players play every note.
            roundTrip(map, false, true, speed, workDir, "a shared chart at x" + speed);
            // A duel on a map authored for two sides, and a solo run, where same-lane notes fold into one.
            roundTrip(map, false, false, speed, workDir, "a two-sided chart at x" + speed);
            roundTrip(map, true, false, speed, workDir, "a solo chart at x" + speed);
        }
    }

    /** One chart, written and read back the way a battle does it, in several file orders. */
    private static void roundTrip(List<BattleNote> map, boolean solo, boolean unified, float speed,
                                  Path workDir, String what) throws IOException {
        List<BattleNote> runtime = BattleManager.runtimeNoteOrder(map, solo, unified, speed);
        check(!runtime.isEmpty(), what + " has notes to judge");

        List<BattleNote> asWritten = readBack(chartFile(workDir, runtime, numbering(runtime), true), runtime.size());
        sameNotes(runtime, asWritten, what + " survives being read back");

        // The exact order the removed comparator produced. The numbers now travel with the notes, so even a file
        // written in that order has to come back as the battle's own numbering.
        List<Integer> sorted = numbering(runtime);
        sorted.sort(Comparator.comparingInt((Integer index) -> runtime.get(index).tick())
                .thenComparingInt(index -> runtime.get(index).owner())
                .thenComparingInt(index -> runtime.get(index).lane()));
        sameNotes(runtime, readBack(chartFile(workDir, runtime, sorted, true), runtime.size()),
                what + " survives a file written in the order the old client sorted into");

        List<Integer> reversed = numbering(runtime);
        java.util.Collections.reverse(reversed);
        sameNotes(runtime, readBack(chartFile(workDir, runtime, reversed, true), runtime.size()),
                what + " survives a file written back to front");

        // A chart from a build that did not number its notes: the file's own order is all there is, so it must be
        // kept exactly as written - no sorting, filtering or merging on top of it.
        List<BattleNote> legacy = readBack(chartFile(workDir, runtime, sorted, false), runtime.size());
        List<BattleNote> expectedLegacy = new ArrayList<>(runtime.size());
        for (int index : sorted) {
            expectedLegacy.add(runtime.get(index));
        }
        sameNotes(expectedLegacy, legacy, what + " without numbers is left in the order it was written in");
    }

    /**
     * Guards the case above against turning into a test of nothing: if the project's chart happened to be in
     * tick, owner and lane order already, writing it that way would prove nothing about the numbering.
     */
    private static void theOldSortWouldStillHaveReorderedThisChart(Path projectDir) throws IOException {
        List<BattleNote> runtime = BattleManager.runtimeNoteOrder(sourceMap(projectDir), false, true, 1.0F);
        List<BattleNote> sorted = new ArrayList<>(runtime);
        sorted.sort(Comparator.comparingInt(BattleNote::tick)
                .thenComparingInt(BattleNote::owner)
                .thenComparingInt(BattleNote::lane));
        int moved = 0;
        for (int index = 0; index < runtime.size(); index++) {
            if (!same(runtime.get(index), sorted.get(index))) {
                moved++;
            }
        }
        check(moved > 0, "the old sort moves " + moved + " of " + runtime.size()
                + " notes on this chart, so the round trip above is testing something");
    }

    /**
     * A shared chart is twice as long as the map it came from, and the client's reader has its own note limit.
     * At the server's limit of 4096 map notes that is 8192 runtime notes, and a reader that stopped at 4096
     * refused the chart outright - no battle at all, rather than a battle with drifting numbers.
     */
    private static void aSharedChartIsNotTooLongForTheClientReader(Path workDir) throws IOException {
        List<BattleNote> map = new ArrayList<>(4096);
        for (int index = 0; index < 4096; index++) {
            map.add(new BattleNote(0, index % 4, 60 + index * 3, index % 5 == 0 ? 8 : 0));
        }
        List<BattleNote> runtime = BattleManager.runtimeNoteOrder(map, false, true, 1.0F);
        check(runtime.size() == 8192, "a full map played by both sides is 8192 runtime notes");
        sameNotes(runtime, readBack(chartFile(workDir, runtime, numbering(runtime), true), runtime.size()),
                "the client reader accepts a chart that long");
    }

    private static List<Integer> numbering(List<BattleNote> runtime) {
        List<Integer> order = new ArrayList<>(runtime.size());
        for (int index = 0; index < runtime.size(); index++) {
            order.add(index);
        }
        return order;
    }

    /** Writes a runtime chart the way the server writes one, with the notes in the given file order. */
    private static Path chartFile(Path workDir, List<BattleNote> runtime, List<Integer> fileOrder, boolean numbered)
            throws IOException {
        JsonObject root = new JsonObject();
        root.addProperty("id", TRACK_ID);
        root.addProperty("bpm", 96);
        root.addProperty("durationTicks", 3556);
        JsonArray notes = new JsonArray();
        for (int index : fileOrder) {
            BattleNote note = runtime.get(index);
            if (numbered) {
                notes.add(BattleManager.chartNote(index, note));
            } else {
                JsonObject plain = new JsonObject();
                plain.addProperty("owner", note.owner());
                plain.addProperty("lane", note.lane());
                plain.addProperty("tick", note.tick());
                plain.addProperty("durationTicks", note.durationTicks());
                notes.add(plain);
            }
        }
        root.add("notes", notes);
        Path file = workDir.resolve("runtime_chart.json");
        Files.writeString(file, root.toString(), StandardCharsets.UTF_8);
        return file;
    }

    private static List<BattleNote> readBack(Path chart, int expectedSize) throws IOException {
        List<BattleNote> notes = ClientTrackChart.read(chart, TRACK_ID).notes();
        check(notes.size() == expectedSize, "the reader returns every note of the chart");
        return notes;
    }

    private static void sameNotes(List<BattleNote> expected, List<BattleNote> actual, String message) {
        int differences = 0;
        for (int index = 0; index < Math.min(expected.size(), actual.size()); index++) {
            if (!same(expected.get(index), actual.get(index))) {
                differences++;
            }
        }
        check(expected.size() == actual.size() && differences == 0,
                message + " (" + differences + " of " + expected.size() + " notes differ)");
    }

    private static boolean same(BattleNote first, BattleNote second) {
        return first.owner() == second.owner() && first.lane() == second.lane()
                && first.tick() == second.tick() && first.durationTicks() == second.durationTicks();
    }

    /**
     * The map the round trip is run on: the project's own hard chart when it is in the working copy, and a
     * stand-in with the same shape - one side, four lanes, chords and holds - when it is not.
     */
    private static List<BattleNote> sourceMap(Path projectDir) throws IOException {
        Path authored = projectDir.resolve(AUTHORED_CHART);
        if (!Files.isRegularFile(authored)) {
            List<BattleNote> stand = new ArrayList<>();
            for (int beat = 0; beat < 120; beat++) {
                int tick = 120 + beat * 7;
                stand.add(new BattleNote(0, beat % 4, tick, beat % 6 == 0 ? 11 : 0));
                if (beat % 5 == 0) {
                    // A chord: two lanes on one tick, which is where an owner-major sort reorders a pair.
                    stand.add(new BattleNote(0, (beat + 2) % 4, tick, 0));
                }
            }
            return stand;
        }
        JsonObject root = JsonParser.parseString(Files.readString(authored, StandardCharsets.UTF_8)).getAsJsonObject();
        JsonArray array = root.getAsJsonArray("notes");
        List<BattleNote> notes = new ArrayList<>(array.size());
        for (JsonElement element : array) {
            JsonObject note = element.getAsJsonObject();
            notes.add(new BattleNote(
                    note.get("owner").getAsInt(),
                    note.get("lane").getAsInt(),
                    note.get("tick").getAsInt(),
                    note.has("durationTicks") ? note.get("durationTicks").getAsInt() : 0));
        }
        check(!notes.isEmpty(), "the authored chart has notes");
        return notes;
    }

    /**
     * One epoch, and a delay per player that cancels their own latency, is what makes a battle start at the same
     * instant for both of them. Before this each client began counting when the packet happened to arrive, which put
     * them several ticks apart - more than the six-tick hit window - so they were judged against different charts.
     */
    private static void bothPlayersReachTickZeroTogether() {
        // A player next door and a player on the other side of the continent.
        int near = BattleClock.oneWayMillis(20);
        int far = BattleClock.oneWayMillis(240);
        check(near == 10 && far == 120, "one-way delay is half the round trip");
        int lead = BattleClock.leadMillis(Math.max(near, far));
        check(lead == 620 || lead == BattleClock.MIN_LEAD_MILLIS, "the lead clears the slowest player");
        check(lead >= BattleClock.MIN_LEAD_MILLIS, "and is never shorter than opening an audio file takes");

        long decidedAt = 1_000_000_000L;
        long epoch = decidedAt + lead * 1_000_000L;
        // Each client starts its own countdown when the packet lands, which is its own delay after the decision.
        long nearStartsAt = decidedAt + near * 1_000_000L + BattleClock.startDelayMillis(lead, near) * 1_000_000L;
        long farStartsAt = decidedAt + far * 1_000_000L + BattleClock.startDelayMillis(lead, far) * 1_000_000L;
        check(nearStartsAt == epoch, "the near player reaches tick zero on the epoch");
        check(farStartsAt == epoch, "and so does the far one");
        check(BattleClock.ticksSince(epoch, epoch) == 0, "tick zero is the epoch");
        check(BattleClock.ticksSince(epoch, epoch - 1) == -1, "the count-in is negative, not clamped");
        check(BattleClock.ticksSince(epoch, epoch + 50_000_000L) == 1, "a tick is fifty milliseconds");

        // A player so far away that the lead cannot cover them still gets a moment to draw the count-in.
        int absurd = BattleClock.oneWayMillis(9_000);
        check(absurd == BattleClock.MAX_ONE_WAY_MILLIS, "a wild latency sample is clamped");
        check(BattleClock.startDelayMillis(BattleClock.MIN_LEAD_MILLIS, 5_000) == BattleClock.MIN_START_DELAY_MILLIS,
                "and never turns into a start in the past");
    }

    /**
     * The camera's placement clip lasts one second and holds at its end, so any jump in the clock it is timed by
     * shows as a stutter. On a server the game time is re-stamped under it more than once inside that second.
     */
    private static void placementClipIgnoresClockCorrections() {
        long placedAt = 1_000L;
        check(PlacementAnimationClock.shouldLatch(Long.MIN_VALUE, placedAt), "the first stamp starts the clip");
        check(!PlacementAnimationClock.shouldLatch(placedAt, placedAt + 3), "a correction of a few ticks is the same placement");
        check(!PlacementAnimationClock.shouldLatch(placedAt, placedAt - 20), "and so is one that arrives early");
        check(PlacementAnimationClock.shouldLatch(placedAt, placedAt + 400), "a placement much later is a new one");

        // Joining a block placed half a second ago must begin the clip half a second in, not at the start.
        long now = 5_000_000_000L;
        long start = PlacementAnimationClock.startNanos(placedAt, placedAt + 10, now);
        check(Math.abs(PlacementAnimationClock.seconds(start, now) - 0.5F) < 1.0E-4F, "the clip joins where it already is");
        check(PlacementAnimationClock.seconds(start, now + 250_000_000L) > 0.74F, "and keeps running from there");
        long fresh = PlacementAnimationClock.startNanos(placedAt, placedAt, now);
        check(PlacementAnimationClock.seconds(fresh, now) == 0.0F, "a placement seen as it happens starts at zero");
        long future = PlacementAnimationClock.startNanos(placedAt, placedAt - 100, now);
        check(PlacementAnimationClock.seconds(future, now) == 0.0F, "a stamp from the future does not run the clip backwards");
    }

    /**
     * Server-side rotation never reaches the client on its own, so a held participant is only
     * re-sent their own rotation once the client has clearly turned away from the opponent.
     */
    private static void heldParticipantsAreTurnedBackToEachOther() {
        float target = 90.0F;
        check(!BattlePlacementMath.rotationDrifted(target, 0, target, 0),
                "a participant already facing the opponent is not corrected");
        check(!BattlePlacementMath.rotationDrifted(target + 2.4F, 0, target, 0),
                "drift below the threshold is left to the client instead of rubber-banding");
        check(BattlePlacementMath.rotationDrifted(target + 2.6F, 0, target, 0),
                "drift above the threshold turns the participant back");
        check(BattlePlacementMath.rotationDrifted(target - 2.6F, 0, target, 0),
                "drift is corrected in both directions");
        check(BattlePlacementMath.rotationDrifted(target, 3.0F, target, 0),
                "pitch drift above the threshold is corrected as well");
        check(!BattlePlacementMath.rotationDrifted(-179.0F, 0, 179.0F, 0),
                "two degrees across the yaw seam is not a 358 degree drift");
        check(BattlePlacementMath.rotationDrifted(-176.0F, 0, 179.0F, 0),
                "a real turn across the yaw seam is still corrected");
    }

    private static void cameraInterpolatesBetweenClientTicks() {
        BattleCameraInterpolation camera = new BattleCameraInterpolation();
        camera.snap(0, 0, 0, 0, 0, 70);
        camera.captureTickStart();
        camera.approach(10, 4, -2, 40, 20, 90, 0.5, 0.5F, 0.5F);
        var start = camera.sample(0.0F);
        var middle = camera.sample(0.5F);
        var end = camera.sample(1.0F);
        check(close(start.x(), 0) && close(end.x(), 5) && close(middle.x(), 2.5),
                "render frames interpolate position between client ticks");
        check(close(start.fov(), 70) && close(end.fov(), 80) && close(middle.fov(), 75),
                "render frames interpolate FOV between client ticks");
    }

    private static void cameraUsesShortestYawPath() {
        BattleCameraInterpolation camera = new BattleCameraInterpolation();
        camera.snap(0, 0, 0, 170, 0, 70);
        camera.captureTickStart();
        camera.approach(0, 0, 0, -170, 0, 70, 1, 0.5F, 1);
        check(Math.abs(Math.abs(camera.sample(1).yaw()) - 180.0F) < 0.001F,
                "camera yaw smoothing crosses the nearest wrapped boundary");
    }

    private static void idlePanelUsesCurrentSpeakerCoverage() {
        UUID live = UUID.randomUUID();
        UUID nearby = UUID.randomUUID();
        check(DjPreloadAudience.choose(true, Set.of(live), Set.of(nearby)).equals(Set.of(live)),
                "active playback keeps its authoritative audience");
        check(DjPreloadAudience.choose(false, Set.of(), Set.of(nearby)).equals(Set.of(nearby)),
                "idle panel preloads for players currently inside speaker coverage");
    }

    private static boolean close(double first, double second) {
        return Math.abs(first - second) < 0.0001;
    }

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
}

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.UUID;
import ru.gora.musicbattle.data.BattleClock;
import ru.gora.musicbattle.data.BattlePauseClock;
import ru.gora.musicbattle.network.BattleActionPayload;

/**
 * Where a mob's hit shows on a player's screen, against the tick it belongs to, however the server's ticks fall.
 *
 * <p>The client side is the real ClientBattleController: acceptAction, the frame start that shows held actions
 * (beginRenderFrame), the client tick's flushes, the punch stamp and the lane flash and burst. The server side is a
 * model of the integrated server on the real BattlePauseClock and BattleClock: it ticks every 50 ms whether or not
 * the game is paused, skips its battle while paused, reads playTicks from the battle's epoch, and sends a mob's hit
 * from the first server tick at which the mob's clock (playTicks + AI_LEAD_TICKS, BattleManager) reaches the tick the
 * mob presses on, stamped with that clock - BattleManager#tickMobAi and #broadcastAction, whose identity with the
 * mob's old timeline tools/BattleAiChecks.java proves on the real code.
 *
 * <p>Swept: the phase of the server's ticks inside a battle tick, single-player pauses of lengths that are not whole
 * ticks (each moves that phase), the tick the mob presses on against its note, the client's clock against the
 * server's, the frame rate and the frames' phase, and how long a packet takes to be handled. A steady network delay
 * is part of the client's clock offset - a client starts its battle clock when the start packet arrives, so it trails
 * the server by that same delay - and the delivery times here are what comes on top of it; the longest stands for a
 * lag spike. A hit that comes in time must show in the first frame at or after its tick, with its punch stamped
 * exactly on that tick and its burst counting down on the battle clock from it; a hit that comes late must show in
 * the first frame after it comes.
 * The same harness with the mob pressing on the first server tick at or after its note and its hits shown on
 * arrival (the code before) is run too, to show what was wrong and that this check sees it.
 *
 * <p>NOT COVERED: real packets and real frames, OpenAL, and a client that draws no frames for a while (its client
 * tick still shows held hits, at tick precision). Those are what the hidden single-player run is for.
 */
public final class MobHitTimingChecks {
    static final double TICK_MS = 50.0D;
    static final double START_MS = 1_000_000.0D;
    static final double LEAD_IN_MS = 2_000.0D;
    static Class<?> controller;
    static Class<?> noteType;
    static Method acceptAction;
    static Method beginRenderFrame;
    static Method showDueActions;
    static Method flushPendingNoteActions;
    static Method flushPendingRemoteStates;
    static Object timedFlashes;
    static Method timedApply;
    static Method timedClear;
    static int aiLead;
    static int checks;
    static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        bind();
        Field lead = Class.forName("ru.gora.musicbattle.server.BattleManager").getDeclaredField("AI_LEAD_TICKS");
        lead.setAccessible(true);
        aiLead = lead.getInt(null);
        check(aiLead >= 2, "a mob plays at least two ticks ahead, lead=" + aiLead);

        double[] serverPhases = {0.0D, 9.3D, 21.7D, 29.9D, 41.4D, 49.95D};
        double[][] pauseSets = {
                {},
                {1513.7D, 3231.2D, 707.9D, 2444.4D},
                {17.3D, 33.1D, 41.9D, 1000.6D},
                {999.9D, 1001.1D, 1049.5D, 1025.0D}};
        int[] aiOffsets = {-1, 0, 1, 2};
        double[] clientOffsets = {-0.3D, -0.1D, 0.1D};
        double[] frameRates = {30.0D, 144.0D};
        double[] framePhases = {0.0D, 0.37D};
        Stats onTime = new Stats();
        Stats late = new Stats();
        Stats before = new Stats();
        int runs = 0;
        for (double phase : serverPhases) {
            for (double[] pauses : pauseSets) {
                for (double fps : frameRates) {
                    for (double framePhase : framePhases) {
                        for (double clientOffset : clientOffsets) {
                            for (int aiOffset : aiOffsets) {
                                // Single player: the packet is handled at the next frame. And a delay 20 ms longer
                                // than the one the client's clock already trails the server by (its clock starts
                                // when the start packet arrives, so a steady delay is in clientOffset, not here).
                                for (double delivery : new double[]{0.3D, 20.0D}) {
                                    Scenario s = new Scenario(aiLead, true, phase, pauses, aiOffset, clientOffset, fps,
                                            framePhase, delivery, 7L + runs);
                                    expectOnTime(s, run(s), onTime);
                                    runs++;
                                }
                            }
                            // A delay that has grown by more than the lead since the start, a lag spike: every hit
                            // comes after its tick. A steady delay, however long, never does this.
                            Scenario spike = new Scenario(aiLead, true, phase, pauses, 0, clientOffset, fps, framePhase,
                                    (aiLead + 1) * TICK_MS + 15.0D, 99L + runs);
                            expectLate(spike, run(spike), late);
                            runs++;
                            // The code before: the mob pressed on the first server tick at or after its note, and
                            // the client showed its hit as it came.
                            Scenario old = new Scenario(0, false, phase, pauses, 0, clientOffset, fps, framePhase, 0.3D,
                                    3L + runs);
                            before.add(old, run(old));
                            runs++;
                        }
                    }
                }
            }
        }
        // That the harness sees the fault: the old way strays past one frame, and moves between pause visits.
        check(before.worstOverFrame > 0.5D, "the old way shows hits up to a tick late; worst past one frame "
                + fmt(before.worstOverFrame) + " ticks");
        check(before.worstSegmentShift > 0.3D, "the old way moves the hits between pause visits by "
                + fmt(before.worstSegmentShift) + " ticks");
        check(onTime.worstSegmentShift <= onTime.worstFrameTicks + 1.0e-3D,
                "held hits do not move between pause visits by more than a frame: " + fmt(onTime.worstSegmentShift));

        System.out.println(String.format(Locale.ROOT,
                "On time (%d hits): shown minus its tick %s..%s ticks (a frame is up to %s), punch stamp off by at most %s,"
                        + " median per pause segment moves by at most %s",
                onTime.hits, fmt(onTime.minLate), fmt(onTime.maxLate), fmt(onTime.worstFrameTicks), fmt(onTime.worstStamp),
                fmt(onTime.worstSegmentShift)));
        System.out.println(String.format(Locale.ROOT,
                "Delay spike (%d hits): every one shown in the first frame after it came; shown minus its tick %s..%s",
                late.hits, fmt(late.minLate), fmt(late.maxLate)));
        System.out.println(String.format(Locale.ROOT,
                "Before (%d hits): shown minus its note %s..%s ticks, up to %s past one frame, median per pause segment"
                        + " moves by up to %s",
                before.hits, fmt(before.minLate), fmt(before.maxLate), fmt(before.worstOverFrame),
                fmt(before.worstSegmentShift)));
        if (!failures.isEmpty()) {
            System.err.println(String.join("\n", failures.subList(0, Math.min(40, failures.size()))));
            throw new AssertionError(failures.size() + " mob hit timing checks failed");
        }
        System.out.println("Mob hit timing checks passed: " + checks + " (" + runs + " runs)");
        System.out.println("NOT COVERED: real packets, frames and OpenAL in the game; see the hidden single-player run");
    }

    /** One battle: how the server ticks, how the game pauses, how the mob plays, how the client draws. */
    record Scenario(int lead, boolean held, double serverPhaseMs, double[] pauses, int aiOffset, double clientOffsetTicks,
                    double fps, double framePhase, double deliveryMs, long seed) {
        double frameMs() {
            return 1000.0D / fps;
        }

        double frameTicks() {
            return frameMs() / TICK_MS;
        }
    }

    /** A hit as the client showed it. */
    record Shown(int note, int noteTick, int intendedTick, int segment, double shownAt, double arrivedAt, float stamp,
                 int burst, int flash) {
    }

    static void expectOnTime(Scenario s, List<Shown> shown, Stats stats) {
        String label = describe(s);
        check(shown.size() == CHART_NOTES, label + ": every hit shows, got " + shown.size());
        for (Shown hit : shown) {
            double late = hit.shownAt() - hit.intendedTick();
            stats.hit(late, s.frameTicks());
            stats.worstStamp = Math.max(stats.worstStamp, Math.abs(hit.stamp() - hit.intendedTick()));
            check(hit.arrivedAt() < hit.intendedTick(), label + ": note " + hit.note() + " arrives before its tick ("
                    + fmt(hit.arrivedAt()) + " for " + hit.intendedTick() + ")");
            check(late >= -1.0e-4D && late < s.frameTicks() + 1.0e-4D, label + ": note " + hit.note()
                    + " shows within a frame of its tick, " + fmt(late) + " ticks late");
            check(Math.abs(hit.stamp() - hit.intendedTick()) < 1.0e-3F, label + ": note " + hit.note()
                    + " punches on its own tick, stamp " + hit.stamp());
            check(hit.burst() == 8 && hit.flash() == 8, label + ": note " + hit.note()
                    + " shows its whole burst and flash, " + hit.burst() + "/" + hit.flash());
        }
        stats.segments(shown);
    }

    static void expectLate(Scenario s, List<Shown> shown, Stats stats) {
        String label = describe(s);
        check(shown.size() == CHART_NOTES, label + ": every late hit shows, got " + shown.size());
        int late = 0;
        for (Shown hit : shown) {
            if (hit.arrivedAt() < hit.intendedTick()) {
                // It came while the game stood paused, its tick not yet reached: it waits for its tick like any other.
                double early = hit.shownAt() - hit.intendedTick();
                check(early >= -1.0e-4D && early < s.frameTicks() + 1.0e-4D, label + ": note " + hit.note()
                        + ", come during a pause, shows within a frame of its tick, " + fmt(early) + " ticks late");
                continue;
            }
            late++;
            stats.hit(hit.shownAt() - hit.intendedTick(), s.frameTicks());
            check(hit.shownAt() >= hit.arrivedAt() && hit.shownAt() - hit.arrivedAt() < s.frameTicks() + 1.0e-4D,
                    label + ": note " + hit.note() + " shows in the first frame after it comes ("
                            + fmt(hit.arrivedAt()) + " -> " + fmt(hit.shownAt()) + ")");
        }
        check(late > CHART_NOTES / 2, label + ": a delay spike this long does deliver most hits late, " + late);
    }

    static final class Stats {
        int hits;
        double minLate = Double.MAX_VALUE;
        double maxLate = -Double.MAX_VALUE;
        double worstFrameTicks;
        double worstOverFrame;
        double worstStamp;
        double worstSegmentShift;

        void hit(double late, double frameTicks) {
            hits++;
            minLate = Math.min(minLate, late);
            maxLate = Math.max(maxLate, late);
            worstFrameTicks = Math.max(worstFrameTicks, frameTicks);
            worstOverFrame = Math.max(worstOverFrame, late - frameTicks);
        }

        void add(Scenario s, List<Shown> shown) {
            for (Shown hit : shown) {
                hit(hit.shownAt() - hit.noteTick(), s.frameTicks());
            }
            segments(shown);
        }

        /** How far the typical lateness moves from one stretch between pauses to the next. */
        void segments(List<Shown> shown) {
            double previous = Double.NaN;
            for (int segment = 0; segment <= 4; segment++) {
                List<Double> lates = new ArrayList<>();
                for (Shown hit : shown) {
                    if (hit.segment() == segment) {
                        lates.add(hit.shownAt() - hit.intendedTick());
                    }
                }
                if (lates.isEmpty()) {
                    continue;
                }
                lates.sort(Double::compare);
                double median = lates.get(lates.size() / 2);
                if (!Double.isNaN(previous)) {
                    worstSegmentShift = Math.max(worstSegmentShift, Math.abs(median - previous));
                }
                previous = median;
            }
        }
    }

    static final int CHART_NOTES = 24;

    /** A mob's chart: taps on every lane, 7 to 18 ticks apart, from tick 60, with room for the four pauses. */
    static int[][] chart(long seed) {
        Random random = new Random(seed);
        int[][] notes = new int[CHART_NOTES][];
        int tick = 60;
        for (int i = 0; i < CHART_NOTES; i++) {
            notes[i] = new int[]{random.nextInt(4), tick};
            tick += 7 + random.nextInt(12);
        }
        return notes;
    }

    /** Battle ticks, on the client's clock, at which the four pauses begin: between notes, as a player would. */
    static final double[] PAUSE_AT_TICKS = {101.37D, 173.81D, 250.06D, 318.52D};

    enum Kind { PAUSE_END, PAUSE_START, SERVER_TICK, CLIENT_TICK, FRAME_START, FRAME_RENDER }

    record Event(double at, Kind kind, int order) {
    }

    static List<Shown> run(Scenario s) throws Exception {
        int[][] chart = chart(s.seed());
        UUID battle = new UUID(s.seed(), 42L);
        resetController(battle, chart);
        BattlePauseClock pauseClock = new BattlePauseClock();
        long serverEpoch = pauseClock.now(nanos(START_MS)) + nanos(LEAD_IN_MS);
        double[] clientEpoch = {START_MS + LEAD_IN_MS - s.clientOffsetTicks() * TICK_MS};
        boolean[] paused = {false};
        double[] pausedAt = {0.0D};

        int lastTick = chart[chart.length - 1][1] + 40;
        double endMs = START_MS + LEAD_IN_MS + lastTick * TICK_MS + Arrays.stream(s.pauses()).sum() + 2000.0D;
        PriorityQueue<Event> events = new PriorityQueue<>(Comparator.comparingDouble(Event::at).thenComparingInt(Event::order));
        int[] order = {0};
        for (double t = START_MS + s.serverPhaseMs(); t < endMs; t += TICK_MS) {
            events.add(new Event(t, Kind.SERVER_TICK, order[0]++));
        }
        for (double t = START_MS + 11.0D; t < endMs; t += TICK_MS) {
            events.add(new Event(t, Kind.CLIENT_TICK, order[0]++));
        }
        for (double t = START_MS + s.framePhase() * s.frameMs(); t < endMs; t += s.frameMs()) {
            events.add(new Event(t, Kind.FRAME_START, order[0]++));
            // A frame's own client ticks run before its drawing starts: a millisecond or two.
            events.add(new Event(t + 1.7D, Kind.FRAME_RENDER, order[0]++));
        }
        for (int i = 0; i < s.pauses().length; i++) {
            double start = clientEpoch[0] + PAUSE_AT_TICKS[i] * TICK_MS + Arrays.stream(s.pauses(), 0, i).sum();
            events.add(new Event(start, Kind.PAUSE_START, order[0]++));
            events.add(new Event(start + s.pauses()[i], Kind.PAUSE_END, order[0]++));
        }

        List<BattleActionPayload> inbox = new ArrayList<>();
        List<Double> arrivedAt = new ArrayList<>();
        boolean[] sent = new boolean[chart.length];
        int[] mobClock = {Integer.MIN_VALUE};
        double[] arrivedClock = new double[chart.length];
        Arrays.fill(arrivedClock, Double.NaN);
        boolean[] recorded = new boolean[chart.length];
        int[] lastHitOnLane = {-1, -1, -1, -1};
        List<Shown> shown = new ArrayList<>();
        int segment = 0;
        int score = 0;
        int combo = 0;

        while (!events.isEmpty()) {
            Event event = events.poll();
            double t = event.at();
            switch (event.kind()) {
                case PAUSE_START -> {
                    paused[0] = true;
                    pausedAt[0] = t;
                    pauseClock.observe(true, nanos(t));
                }
                case PAUSE_END -> {
                    pauseClock.observe(false, nanos(t));
                    paused[0] = false;
                    // The client's clock moves by the whole pause (ClientBattleController#releaseBattleFromGamePause).
                    clientEpoch[0] += t - pausedAt[0];
                    segment++;
                }
                case SERVER_TICK -> {
                    if (pauseClock.paused()) {
                        // The integrated server keeps its 50 ms ticks, but its battle stands still.
                        continue;
                    }
                    int playTicks = BattleClock.ticksSince(serverEpoch, pauseClock.now(nanos(t)));
                    // Now: the mob plays every tick of its own clock in turn, up to playTicks + lead
                    // (Battle#tickParticipants). Before: it read the battle's tick once per server tick and pressed
                    // every note that tick had reached.
                    int target = playTicks + s.lead();
                    int from = s.held() ? (mobClock[0] == Integer.MIN_VALUE ? target : mobClock[0] + 1) : target;
                    for (int mobTick = from; mobTick <= target; mobTick++) {
                        for (int i = 0; i < chart.length; i++) {
                            int pressTick = chart[i][1] + s.aiOffset();
                            if (sent[i] || mobTick < pressTick) {
                                continue;
                            }
                            sent[i] = true;
                            score += 100;
                            combo++;
                            BattleActionPayload payload = new BattleActionPayload(battle, 1, chart[i][0], i, chart[i][1],
                                    true, true, score, combo, 0, score,
                                    s.held() ? mobTick : BattleActionPayload.SHOW_ON_ARRIVAL);
                            inbox.add(payload);
                            arrivedAt.add(t + s.deliveryMs());
                        }
                    }
                    mobClock[0] = Math.max(mobClock[0], target);
                }
                case CLIENT_TICK -> {
                    if (paused[0]) {
                        continue;
                    }
                    // What ClientBattleController#tick does with held and pending hits.
                    float clock = setClock(clientEpoch[0], paused[0], pausedAt[0], t);
                    field("localTicks").setInt(null, (int) Math.floor(clock));
                    showDueActions.invoke(null, held(), false, clock);
                    flushPendingNoteActions.invoke(null, notes(), (int) Math.floor(clock), field("laneFlashes").get(null),
                            field("hitBursts").get(null), false);
                    flushPendingRemoteStates.invoke(null, notes(), false);
                    timedApply.invoke(timedFlashes, clock, field("laneFlashes").get(null), field("hitBursts").get(null));
                }
                case FRAME_START -> {
                    // Packets are handled at the start of the frame after they come.
                    float clock = setClock(clientEpoch[0], paused[0], pausedAt[0], t);
                    while (!inbox.isEmpty() && arrivedAt.get(0) <= t) {
                        BattleActionPayload payload = inbox.remove(0);
                        arrivedAt.remove(0);
                        arrivedClock[payload.noteIndex()] = clock;
                        acceptAction.invoke(null, payload);
                    }
                }
                case FRAME_RENDER -> {
                    float clock = setClock(clientEpoch[0], paused[0], pausedAt[0], t);
                    beginRenderFrame.invoke(null);
                    List<?> notes = notes();
                    int[][] bursts = (int[][]) field("hitBursts").get(null);
                    int[][] flashes = (int[][]) field("laneFlashes").get(null);
                    float stamp = ((float[]) field("animationTriggerTick").get(null))[1];
                    for (int i = 0; i < chart.length; i++) {
                        Object note = notes.get(i);
                        boolean visible = noteField(note, "resolved") || noteField(note, "started");
                        if (recorded[i] || !visible) {
                            continue;
                        }
                        recorded[i] = true;
                        lastHitOnLane[chart[i][0]] = i;
                        shown.add(new Shown(i, chart[i][1], chart[i][1] + s.aiOffset(), segment, clock, arrivedClock[i],
                                stamp, bursts[1][chart[i][0]], flashes[1][chart[i][0]]));
                    }
                    if (s.held()) {
                        // A held hit's burst steps down on the battle clock from its own tick, not on client ticks.
                        for (int lane = 0; lane < 4; lane++) {
                            int last = lastHitOnLane[lane];
                            if (last < 0 || paused[0]) {
                                continue;
                            }
                            int intended = chart[last][1] + s.aiOffset();
                            int expected = Math.max(0, 8 - (int) Math.floor(clock - intended));
                            if (clock >= intended && arrivedClock[last] < intended) {
                                check(bursts[1][lane] == expected, describe(s) + ": lane " + lane + " burst " + bursts[1][lane]
                                        + " at " + fmt(clock) + ", expected " + expected + " counting from " + intended);
                            }
                        }
                    }
                }
            }
        }
        check(held().isEmpty(), describe(s) + ": nothing is left waiting at the end");
        return shown;
    }

    /** Sets the controller's battle clock to wall time {@code t} and returns it, as activeVisualTick reads it. */
    static float setClock(double clientEpochMs, boolean paused, double pausedAtMs, double t) throws Exception {
        long epoch = nanos(clientEpochMs);
        long now = nanos(paused ? pausedAtMs : t);
        field("startAtNanos").setLong(null, epoch);
        // activeVisualTick reads the clock here while it is held; holding it at each instant is how the check steers it.
        field("musicPausedAtNanos").setLong(null, now);
        return (now - epoch) / 50_000_000.0F;
    }

    static void resetController(UUID battle, int[][] chart) throws Exception {
        field("active").setBoolean(null, true);
        field("battleId").set(null, battle);
        field("ownOwner").setInt(null, 0);
        field("leftEntityId").setInt(null, 0);
        field("rightEntityId").setInt(null, 0);
        field("localTicks").setInt(null, -40);
        held().clear();
        timedClear.invoke(timedFlashes);
        for (String name : new String[]{"laneFlashes", "hitBursts"}) {
            for (int[] lanes : (int[][]) field(name).get(null)) {
                Arrays.fill(lanes, 0);
            }
        }
        ((float[]) field("animationTriggerTick").get(null))[1] = -Float.MAX_VALUE;
        @SuppressWarnings("unchecked")
        List<Object> notes = (List<Object>) field("notes").get(null);
        notes.clear();
        Constructor<?> constructor = noteType.getDeclaredConstructor(int.class, int.class, int.class, int.class, int.class);
        constructor.setAccessible(true);
        for (int i = 0; i < chart.length; i++) {
            notes.add(constructor.newInstance(i, 1, chart[i][0], chart[i][1], 0));
        }
    }

    static void bind() throws Exception {
        controller = Class.forName("ru.gora.musicbattle.client.ClientBattleController");
        noteType = Class.forName("ru.gora.musicbattle.client.ClientBattleController$ClientNote");
        acceptAction = controller.getDeclaredMethod("acceptAction", BattleActionPayload.class);
        beginRenderFrame = controller.getDeclaredMethod("beginRenderFrame");
        showDueActions = method("showDueActions", List.class, boolean.class, float.class);
        flushPendingNoteActions = method("flushPendingNoteActions", List.class, int.class, int[][].class, int[][].class,
                boolean.class);
        flushPendingRemoteStates = method("flushPendingRemoteStates", List.class, boolean.class);
        timedFlashes = field("timedLaneFlashes").get(null);
        timedApply = timedFlashes.getClass().getDeclaredMethod("apply", float.class, int[][].class, int[][].class);
        timedApply.setAccessible(true);
        timedClear = timedFlashes.getClass().getDeclaredMethod("clear");
        timedClear.setAccessible(true);
    }

    static Method method(String name, Class<?>... types) throws Exception {
        Method method = controller.getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method;
    }

    static Field field(String name) throws Exception {
        Field field = controller.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    static List<?> held() throws Exception {
        return (List<?>) field("heldActions").get(null);
    }

    static List<?> notes() throws Exception {
        return (List<?>) field("notes").get(null);
    }

    static boolean noteField(Object note, String name) throws Exception {
        Field field = noteType.getDeclaredField(name);
        field.setAccessible(true);
        return field.getBoolean(note);
    }

    static long nanos(double ms) {
        return Math.round(ms * 1_000_000.0D);
    }

    static String describe(Scenario s) {
        return String.format(Locale.ROOT, "lead %d %s, server phase %.2f ms, pauses %s, press %+d, client %+.1f, %.0f fps"
                        + " phase %.2f, delivery %.1f ms", s.lead(), s.held() ? "held" : "on arrival", s.serverPhaseMs(),
                Arrays.toString(s.pauses()), s.aiOffset(), s.clientOffsetTicks(), s.fps(), s.framePhase(), s.deliveryMs());
    }

    static String fmt(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    static void check(boolean condition, String message) {
        checks++;
        if (!condition) {
            failures.add(message);
        }
    }
}

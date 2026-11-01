import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import ru.gora.musicbattle.data.BattleClock;
import ru.gora.musicbattle.data.BattleNote;
import ru.gora.musicbattle.data.BattlePauseClock;
import ru.gora.musicbattle.data.NoteAppearance;
import ru.gora.musicbattle.data.TrackEffect;
import ru.gora.musicbattle.network.BattleActionPayload;
import ru.gora.musicbattle.network.ClientJudgementPayload;
import ru.gora.musicbattle.network.NoteInputPayload;
import ru.gora.musicbattle.network.SpectatorBattlePayload;

/**
 * How a mob's mistakes look on everybody else's screen, end to end, without a game.
 *
 * <p>The server side is the real mob: BattleManager's tickParticipants, tickMobAi, resolveMissedNotes, tickHold,
 * resolveInput and broadcastAction, cut loose from the world the way tools/BattleAiChecks.java does it. Here
 * broadcastAction runs too, with only its packet loop and the armour-stand status windows taken out, so every
 * BattleActionPayload is the very one a client would be sent. The mob plays random charts at four skills - chords,
 * notes one to three ticks apart on a lane, holds - on an integrated server's 50 ms ticks, through single-player
 * pauses (the real BattlePauseClock and BattleClock, as tools/MobHitTimingChecks.java models them). The client side is
 * the real ClientBattleController, as the player the mob fights and as a spectator at once (one controller, two
 * clocks): acceptAction, the frame start that shows held actions, and what a client tick does with them. Every frame
 * both are read the way their panels draw them: a note is drawn while it is unresolved (BattlePanelRenderer.draw,
 * renderWorldPanel), falling on past its button once its tick has gone.
 *
 * <p>Required on both screens, as on one's own panel (ClientBattleController#resolveLocalMissedNotes,
 * #breakOwnComboForMistake):
 * <ul>
 *   <li>a tap the mob misses, or the head of a hold it misses, is never shown as hit and stays drawn until its tick
 *       plus MISS_AFTER_PANEL_EXIT_TICKS - the same number one's own misses are settled by - and is gone in the first
 *       frame at or after it, leaving its lane as it was: no flash, no burst, no flash started on the battle clock;</li>
 *   <li>a hold the mob lets go of is gone in the first frame at or after the tick it let go on, its lane flashes
 *       exactly as one's own broken hold does, without a burst, and the first frame drawn after it shows that flash:
 *       it is counted on the battle clock from the break, so a client tick that shows the break does not count its
 *       brightest step away before anything is drawn, as it would an untimed flash. The burst of the head's hit goes
 *       on exactly as it would have without the break, as on one's own panel, where the break leaves the burst be;</li>
 *   <li>the fighter punches once for each press shown - a tap or a hold's head hit - and never for a miss or a broken
 *       hold: letting go is not a press;</li>
 *   <li>the fighter's held pose holds exactly the lanes where one of its holds is shown held, so a miss settled on a
 *       lane the mob is holding by then does not take its pose down;</li>
 *   <li>every note is drawn exactly until the action that settles it is due, and the combo and score shown are,
 *       frame by frame, the ones of the side's last due action: the server's own sequence, so they never step back.
 *       The score only grows, and the combo only grows or drops to zero, on a frame that shows a miss.</li>
 * </ul>
 * Also: a battle of two mobs (watched only), a delay spike that brings every action after its tick, stalls of two to
 * four ticks in which the client draws nothing and runs no tick while the server goes on, so that actions come due
 * meanwhile and are shown late, and a duel, whose remote player's miss and broken hold come as they arrive, including a
 * hold they took early and let go of before its tick, while its hit was still waiting for that tick. In the duel too:
 * an early hit kept shows its score and combo from its note's tick, and is drawn in the first frame on that tick from
 * its first step, at 30 fps with the client ticks late in each tick too; numbers that waited on a note are never shown
 * after newer ones of that side, nor after a spectator's refresh of the battle, and a fighter who also hears a
 * neighbouring battle keeps each battle's apart; and the other player's flashes that come as they are, in Minecraft's
 * own frame order, are drawn from their first step and count down on the battle clock.
 *
 * <p>One's own side, end to end with the real server's judgement (ownEarlyHoldLetGo, playerHoldRelease,
 * ownReleasesJudgedOnce, ownEarlyTapsLetGo, ownWaitingNotesTakenOnce): a hold taken before its tick and let go of
 * before the tick came is a broken hold on our screen, on the server and on everybody else's, and the server's own
 * rules for a key going up on a hold no judgement settled, and for the fallback that only a hold still held reaches.
 * Every release of ours is judged once, before its key goes up, and the server takes every judgement our client sends,
 * so every screen ends on the same numbers: holds back to back on one lane, taps hit early and let go of before their
 * tick, a lane pressed again while its early hit waits, another lane pressed then, and input offsets that put the input
 * clock up to ten ticks ahead of the one that shows the hits.
 *
 * <p>Run against the code before, this fails: a server that settles a mob's miss on the note's own tick makes the
 * note vanish there, a client without the broken-hold flash leaves the lane on the hit's dying flash, an untimed
 * broken-hold flash is drawn from its second step whenever a client tick shows it, a burst read before the break's tick
 * steps back (and, after a stall, one read at the frame's clock), a missed head settled on a held lane drops the pose,
 * a duel's early hold broken early falls on as a missed head with no flash, or vanishes and takes the combo back, older
 * numbers come back after newer ones, the other player's bad press and hits lose their brightest step, and one's own
 * hold let go of before its tick ends as a hit with its hold score. So does a release that finds a hold already judged
 * complete and judges it again, leaving the next hold to the server; a note whose early hit still waits for its tick
 * being hit a second time, missed, or held against a press on another lane as a bad press; one view's numbers held
 * back by the other battle's; numbers that waited on a note shown over a spectator's refresh; and an early hit of the
 * other player's left to the next client tick.
 *
 * <p>A spectator who walks into hearing range mid-song (lateJoinAtTick, spectatorJoinsDuel): the join the real server
 * makes (Battle#spectatorJoin) brings each side's numbers as of the battle's tick, the notes settled in the hit window
 * before it, the holds being held and a mob's actions from the ticks it has played ahead, so that from the join on the
 * panel is read by every rule above, against the actions due by the join's tick; and a player's hits taken early come
 * on their own ticks. Run against the code before, the notes the mob had played ahead, and those settled shortly
 * before the join, fell past their buttons for the rest of the battle, and a mob's held hold kept its pose. Also: a
 * refresh of the watched battle while a mob's actions wait for their tick keeps its newer numbers
 * (spectatorRefreshWhileHeld), a completion shown as it comes is judged on the battle clock, and one's own early hit
 * flashes once, at the press (ownEarlyHitFlashesOnce). NOT COVERED: real packets, frames and drawing. Those are for
 * the hidden runs.
 */
public final class MobMissChecks {
    static final String MANAGER = "ru/gora/musicbattle/server/BattleManager";
    static final double TICK_MS = 50.0D;
    static final double START_MS = 1_000_000.0D;
    static final double LEAD_IN_MS = 2_000.0D;
    /** Battle ticks, on the player's clock, at which the four pauses begin: anywhere, notes falling or not. */
    static final double[] PAUSE_AT_TICKS = {101.37D, 173.81D, 250.06D, 318.52D};
    /**
     * Stalls, as pairs of the player's battle tick they begin on and how many ticks they last, between the pauses: the
     * client draws nothing and runs no tick while the server and the clocks go on (a garbage collection, a hitch).
     */
    static final double[] STALLS = {134.6D, 2.6D, 211.2D, 3.4D, 287.9D, 2.2D, 346.4D, 4.0D};
    static Class<?> battleType;
    static Class<?> stateType;
    static Class<?> noteType;
    static Class<?> skillType;
    static Class<?> controller;
    static Class<?> clientNoteType;
    static Method tickParticipants;
    static Method acceptAction;
    static Method beginRenderFrame;
    static Method showDueActions;
    static Method flushPendingNoteActions;
    static Method flushPendingRemoteStates;
    static Method breakOwnComboForMistake;
    static Method sanitizeAnimationHeldMasks;
    static Method sendLaneState;
    static Method resolveLocalMissedNotes;
    static Method resolveLocalCompletedHolds;
    static Method takeSpectatorPanel;
    static Method spectatorJoin;
    static Method judgeClient;
    static Method laneInput;
    static Method timedApply;
    static Method timedClear;
    static Method timedTimed;
    static Field timedStartedAt;
    static Field timedFlashTicks;
    static final Map<String, Field> FIELDS = new HashMap<>();
    /** What each action of the current run shows: see {@link #kind}. */
    static final Map<BattleActionPayload, Integer> KINDS = new java.util.IdentityHashMap<>();
    static final int OTHER = 0;
    static final int PRESS = 1;
    static final int BREAK = 2;
    static final int MISS = 3;
    static int lead;
    static int exit;
    static int ownBreakFlash;
    static int burstTicks;
    static int checks;
    static long frames;
    static long missedTaps;
    static long brokenHolds;
    static long brokenFlashesSeen;
    /** Of those, the ones a client tick showed: it counts its flashes down right after, before anything is drawn. */
    static long brokenShownByTick;
    /** Broken holds read in the first frame drawn after them. */
    static long brokenFlashesDrawn;
    /** What has just shown actions, for the count above: a client tick, or a packet or a frame start. */
    static boolean showingInTick;
    /** Missed taps and heads read as they left, with no flashing action on their lane among the ones shown with them. */
    static long missesLeftDark;
    /** Showings read for punches, and the presses among them. */
    static long punchShowings;
    static long pressesPunched;
    /** Broken holds whose burst was read against the one their lane had at the break's tick. */
    static long breakBurstsRead;
    /** Of those, the ones shown a whole tick or more after their tick, by the first showing after a stall. */
    static long lateBreaksRead;
    /** Readings of a hold whose completion came after its end and waits for the next client tick to settle it. */
    static long completionsWaitingForTick;
    /** The tick each screen's client tick last settled waiting note actions on (player, spectator). */
    static final int[] lastFlushTick = new int[2];
    /** Frames times lanes on which a mob was shown holding a hold, its pose read. */
    static long heldLaneFrames;
    static long payloadsSeen;
    /**
     * The battle tick on which the spectator walks into hearing range in the run being made, NaN for one who watches
     * from the start: the server sends it the battle then (Battle#spectatorJoin), as updateSpectators does, after that
     * tick's part of the battle.
     */
    static double lateJoinAtTick = Double.NaN;
    /** Spectators who joined mid-song, and the notes and the actions ahead of the battle their join brought. */
    static long lateJoins;
    static long joinSettledNotes;
    static long joinHeldNotes;
    static long joinLeadActions;
    static final List<String> failures = new ArrayList<>();
    /** The payloads the isolated broadcastAction has made since the last server tick began, in order. */
    static final List<BattleActionPayload> sentNow = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        // As in BattleAiChecks: isolate the battle classes into the output folder, first on the class path, and run the
        // checks in the same JVM before any battle class has been loaded.
        if (args.length == 2) isolate(Path.of(args[0]), Path.of(args[1]));
        bind();
        exit = staticInt(Class.forName(MANAGER.replace('/', '.')), "MISS_AFTER_PANEL_EXIT_TICKS");
        lead = staticInt(Class.forName(MANAGER.replace('/', '.')), "AI_LEAD_TICKS");
        int ownExit = staticInt(controller, "MISS_AFTER_PANEL_EXIT_TICKS");
        check(exit == ownExit, "the server settles a mob's miss " + exit + " ticks after its note, where a client settles"
                + " its player's own (" + ownExit + ")");
        ownBreakFlash = ownBrokenHoldFlash();
        check(ownBreakFlash > 0, "one's own broken hold flashes its lane, " + ownBreakFlash);

        int runs = 0;
        for (String skill : List.of("EASY", "MEDIUM", "HARD", "GOD")) {
            for (long seed = 1; seed <= 2; seed++) {
                for (double serverPhase : new double[]{9.3D, 41.4D}) {
                    for (double[] pauses : new double[][]{{}, {1513.7D, 17.3D, 999.9D, 2444.4D}}) {
                        for (double fps : new double[]{30.0D, 144.0D}) {
                            for (double clientOffset : new double[]{-0.3D, 0.1D}) {
                                for (double delivery : new double[]{0.3D, 20.0D}) {
                                    for (double[] stalls : new double[][]{{}, STALLS}) {
                                        run(new Scenario(skill, seed, false, serverPhase, pauses, clientOffset,
                                                clientOffset + 0.27D, fps, 0.37D, delivery, stalls));
                                        runs++;
                                    }
                                }
                            }
                        }
                    }
                }
                // A delay that has grown by more than the lead since the start: everything comes after its tick.
                for (double[] pauses : new double[][]{{}, {1513.7D, 17.3D, 999.9D, 2444.4D}}) {
                    for (double[] stalls : new double[][]{{}, STALLS}) {
                        run(new Scenario(skill, 10 + seed, false, 21.7D, pauses, 0.1D, -0.2D, 144.0D, 0.0D,
                                (lead + 1) * TICK_MS + 15.0D, stalls));
                        // Two mobs: only watched, both sides on the mob's clock.
                        run(new Scenario(skill, 20 + seed, true, 29.9D, pauses, 0.1D, 0.1D, 60.0D, 0.5D, 20.0D,
                                stalls));
                        runs += 2;
                    }
                }
            }
        }
        // A spectator who walks into hearing range mid-song, on random ticks: with the mob playing ahead, inside holds,
        // after pauses and stalls, one mob or two.
        Random joins = new Random(4242L);
        for (String skill : List.of("EASY", "MEDIUM", "HARD", "GOD")) {
            for (long seed = 1; seed <= 2; seed++) {
                for (boolean twoMobs : new boolean[]{false, true}) {
                    for (double[] pauses : new double[][]{{}, {1513.7D, 17.3D, 999.9D, 2444.4D}}) {
                        for (double[] stalls : new double[][]{{}, STALLS}) {
                            for (int join = 0; join < 3; join++) {
                                lateJoinAtTick = 64 + joins.nextInt(300);
                                double delivery = joins.nextBoolean() ? 20.0D : 0.3D;
                                run(new Scenario(skill, 30 + seed, twoMobs, 9.3D + 31.0D * joins.nextDouble(), pauses,
                                        0.1D, 0.27D, joins.nextBoolean() ? 30.0D : 144.0D, 0.37D, delivery, stalls));
                                runs++;
                            }
                        }
                    }
                }
            }
        }
        lateJoinAtTick = Double.NaN;
        check(lateJoins == 192 && joinSettledNotes > 100 && joinHeldNotes > 10 && joinLeadActions > 100,
                "spectators joined mid-song: " + lateJoins + ", their joins bringing " + joinSettledNotes
                        + " notes settled in the hit window, " + joinHeldNotes + " holds being held and "
                        + joinLeadActions + " actions of a mob ahead of the battle");
        duelRemotePlayer();
        duelEarlyHoldLetGo();
        duelEarlyHitKept();
        duelEarlyHitFirstFrame();
        duelArrivalOrder();
        duelRemoteFlashesTimed();
        ownEarlyHoldLetGo();
        playerHoldRelease();
        ownReleasesJudgedOnce();
        ownEarlyTapsLetGo();
        ownWaitingNotesTakenOnce();
        duelTwoBattles();
        spectatorRefresh();
        spectatorRefreshWhileHeld();
        spectatorJoinsDuel();
        ownEarlyHitFlashesOnce();
        check(missedTaps > 1000 && brokenHolds > 100 && brokenFlashesSeen > 100 && brokenShownByTick > 50
                        && brokenFlashesDrawn > 100 && missesLeftDark > 1000 && breakBurstsRead > 100
                        && lateBreaksRead > 20 && pressesPunched > 10000 && heldLaneFrames > 10000,
                "the charts give the mob plenty to miss: " + missedTaps + " missed taps and heads, " + brokenHolds
                        + " broken holds, " + brokenFlashesSeen + " of their flashes read, " + missesLeftDark
                        + " misses read leaving, " + breakBurstsRead + " break bursts (" + lateBreaksRead
                        + " shown a tick or more late), " + pressesPunched + " presses, " + heldLaneFrames
                        + " held lane frames");

        System.out.println(String.format(Locale.ROOT, "%d runs, %d frames read on two screens, %d actions; %d missed"
                        + " taps and heads drawn until their tick + %d, %d broken holds flashing %d", runs, frames,
                payloadsSeen, missedTaps, exit, brokenHolds, ownBreakFlash));
        System.out.println(String.format(Locale.ROOT, "Broken-hold flashes read as they were set: %d on the two screens,"
                + " %d of them set by a client tick; %d read again in the first frame drawn after them, counted on the"
                + " battle clock from their tick", brokenFlashesSeen, brokenShownByTick, brokenFlashesDrawn));
        System.out.println(String.format(Locale.ROOT, "Misses read leaving their lane as it was: %d; break bursts going"
                        + " on from their tick: %d, %d of them shown a tick or more late after a stall; punches counted in"
                        + " %d showings, %d presses; held pose read on %d lane frames; %d readings of a completed hold"
                        + " waiting for the next client tick", missesLeftDark, breakBurstsRead, lateBreaksRead,
                punchShowings, pressesPunched, heldLaneFrames, completionsWaitingForTick));
        if (!failures.isEmpty()) {
            System.err.println(String.join("\n", failures.subList(0, Math.min(40, failures.size()))));
            throw new AssertionError(failures.size() + " mob miss checks failed");
        }
        System.out.println(String.format(Locale.ROOT, "Spectators joined mid-song: %d, with %d notes settled in the hit"
                + " window, %d holds being held and %d actions of a mob ahead of the battle", lateJoins, joinSettledNotes,
                joinHeldNotes, joinLeadActions));
        System.out.println("Mob miss checks passed: " + checks);
        System.out.println("NOT COVERED: real packets, frames and drawing (hidden run)");
    }

    /** One battle: who plays, how the server ticks and pauses, how the two clients draw and hear of it. */
    record Scenario(String skill, long seed, boolean twoMobs, double serverPhaseMs, double[] pauses, double clientOffset,
                    double spectatorOffset, double fps, double framePhase, double deliveryMs, double[] stalls) {
        double frameMs() {
            return 1000.0D / fps;
        }

        double frameTicks() {
            return frameMs() / TICK_MS;
        }

        String describe() {
            return String.format(Locale.ROOT, "%s seed %d%s, server phase %.1f ms, pauses %s, clients %+.2f/%+.2f, %.0f fps,"
                            + " delivery %.1f ms%s", skill, seed, twoMobs ? " two mobs" : "", serverPhaseMs,
                    Arrays.toString(pauses), clientOffset, spectatorOffset, fps, deliveryMs,
                    stalls.length == 0 ? "" : ", stalls " + Arrays.toString(stalls))
                    + (Double.isNaN(lateJoinAtTick) ? "" : ", spectator joins on " + (int) lateJoinAtTick);
        }

        /**
         * The stalls in wall time, as {start, end} pairs. A stall begins at its tick of the player's clock, which pauses
         * before it have moved on in wall time by their length; none of them falls inside a pause.
         */
        double[][] stallWindows(double playerEpochMs) {
            double[][] windows = new double[stalls.length / 2][];
            for (int i = 0; i < windows.length; i++) {
                double tick = stalls[2 * i];
                double start = playerEpochMs + tick * TICK_MS;
                for (int p = 0; p < pauses.length; p++) {
                    if (PAUSE_AT_TICKS[p] < tick) {
                        start += pauses[p];
                    }
                }
                windows[i] = new double[]{start, start + stalls[2 * i + 1] * TICK_MS};
            }
            return windows;
        }
    }

    enum Kind { PAUSE_END, PAUSE_START, SERVER_TICK, CLIENT_TICK, FRAME_START, FRAME_RENDER }

    record Event(double at, Kind kind, int order) {
    }

    /** A payload on its way, with its place among its side's actions. */
    record Sent(BattleActionPayload payload, double arrivesAt, int sequence) {
    }

    /** The stall wall time {@code t} falls in, or null. */
    static double[] stallAt(double[][] stalls, double t) {
        for (double[] stall : stalls) {
            if (t >= stall[0] && t < stall[1]) {
                return stall;
            }
        }
        return null;
    }

    /** About 70 notes per side from tick 60: 1 to 9 ticks apart, chords, runs on one lane, a quarter of them holds. */
    static List<int[]> chart(Random random, int owner) {
        List<int[]> notes = new ArrayList<>();
        int tick = 60;
        while (notes.size() < 70) {
            int roll = random.nextInt(10);
            if (roll == 0) {
                int first = random.nextInt(4);
                for (int i = 0; i < 2 + random.nextInt(2); i++) {
                    notes.add(new int[]{owner, (first + i) % 4, tick, 0});
                }
            } else if (roll == 1) {
                int lane = random.nextInt(4);
                notes.add(new int[]{owner, lane, tick, 0});
                tick += 1 + random.nextInt(3);
                notes.add(new int[]{owner, lane, tick, 0});
            } else {
                int duration = random.nextInt(4) == 0 ? 4 + random.nextInt(17) : 0;
                notes.add(new int[]{owner, random.nextInt(4), tick, duration});
            }
            tick += 1 + random.nextInt(9);
        }
        return notes;
    }

    static void run(Scenario s) throws Exception {
        String label = s.describe();
        Random random = new Random(s.seed() * 7919L + s.skill().hashCode());
        List<int[]> chart = new ArrayList<>(chart(random, 1));
        if (s.twoMobs()) {
            chart.addAll(chart(random, 0));
            chart.sort(Comparator.comparingInt(note -> note[2]));
        }
        UUID id = new UUID(s.seed(), 77L);
        Object battle = serverBattle(s, id, chart);
        boolean player = !s.twoMobs();
        resetClient(id, chart, player);
        KINDS.clear();
        int notes = chart.size();

        BattlePauseClock pauseClock = new BattlePauseClock();
        long serverEpoch = pauseClock.now(nanos(START_MS)) + nanos(LEAD_IN_MS);
        double[] playerEpoch = {START_MS + LEAD_IN_MS - s.clientOffset() * TICK_MS};
        double[] spectatorEpoch = {START_MS + LEAD_IN_MS - s.spectatorOffset() * TICK_MS};
        boolean paused = false;
        double pausedAt = 0.0D;
        int lastTick = chart.stream().mapToInt(note -> note[2] + note[3]).max().orElse(0) + 40;
        double endMs = START_MS + LEAD_IN_MS + lastTick * TICK_MS + Arrays.stream(s.pauses()).sum() + 2000.0D;
        PriorityQueue<Event> events = new PriorityQueue<>(Comparator.comparingDouble(Event::at).thenComparingInt(Event::order));
        int order = 0;
        double[][] stalls = s.stallWindows(playerEpoch[0]);
        for (double t = START_MS + s.serverPhaseMs(); t < endMs; t += TICK_MS) events.add(new Event(t, Kind.SERVER_TICK, order++));
        // A stalled client runs the client ticks it missed when it comes back, one after another (Minecraft#runTick runs
        // as many as are due, up to ten), before the packets that came meanwhile: everything stopped with it, the thread
        // that reads the network too, so they are queued only after the main thread has picked up again. Nothing is
        // drawn while it stalls. Actions that came before the stall and fell due during it are shown late, at their tick.
        for (double t = START_MS + 11.0D; t < endMs; t += TICK_MS) {
            double[] stall = stallAt(stalls, t);
            events.add(new Event(stall == null ? t : stall[1], Kind.CLIENT_TICK, order++));
        }
        for (double t = START_MS + s.framePhase() * s.frameMs(); t < endMs; t += s.frameMs()) {
            if (stallAt(stalls, t) == null) {
                events.add(new Event(t, Kind.FRAME_START, order++));
            }
            if (stallAt(stalls, t + 1.7D) == null) {
                events.add(new Event(t + 1.7D, Kind.FRAME_RENDER, order++));
            }
        }
        for (int i = 0; i < s.pauses().length; i++) {
            double start = playerEpoch[0] + PAUSE_AT_TICKS[i] * TICK_MS + Arrays.stream(s.pauses(), 0, i).sum();
            events.add(new Event(start, Kind.PAUSE_START, order++));
            events.add(new Event(start + s.pauses()[i], Kind.PAUSE_END, order++));
        }

        List<Sent> inbox = new ArrayList<>();
        // Every action in the order it was made, per side; what each screen has been shown is a prefix of it.
        List<List<BattleActionPayload>> made = List.of(new ArrayList<>(), new ArrayList<>());
        int[] arrived = new int[2];
        // The action that settles each note, by its place among its side's actions; MAX_VALUE until it is made.
        int[] settling = new int[notes];
        Arrays.fill(settling, Integer.MAX_VALUE);
        // Notes whose head was hit and held: a miss that settles one of them is a broken hold, not a missed note.
        boolean[] held = new boolean[notes];
        Screen[] screens = {player ? new Screen("player", false, notes) : null, new Screen("spectator", true, notes)};
        // A spectator who joins mid-song watches nothing until the battle comes to it: the server sends it the battle
        // after its part of the join tick, and it gets every action made after that, none made before.
        boolean late = !Double.isNaN(lateJoinAtTick);
        SpectatorBattlePayload join = null;
        double joinArrivesAt = Double.NaN;
        int[] madeByJoin = new int[2];
        if (late) {
            setStatic("spectatorBattleId", null);
            screens[1].joined = false;
        }

        while (!events.isEmpty()) {
            Event event = events.poll();
            double t = event.at();
            switch (event.kind()) {
                case PAUSE_START -> {
                    paused = true;
                    pausedAt = t;
                    pauseClock.observe(true, nanos(t));
                }
                case PAUSE_END -> {
                    pauseClock.observe(false, nanos(t));
                    paused = false;
                    playerEpoch[0] += t - pausedAt;
                    spectatorEpoch[0] += t - pausedAt;
                }
                case SERVER_TICK -> {
                    if (pauseClock.paused()) {
                        continue;
                    }
                    set(battle, "playTicks", BattleClock.ticksSince(serverEpoch, pauseClock.now(nanos(t))));
                    sentNow.clear();
                    tickParticipants.invoke(battle);
                    for (BattleActionPayload payload : sentNow) {
                        int side = payload.owner();
                        int sequence = made.get(side).size();
                        made.get(side).add(payload);
                        // Before held[] takes this action in: whether it is the first hit of its hold is read from it.
                        KINDS.put(payload, kind(payload, chart, held));
                        if (payload.complete() && payload.noteIndex() >= 0) {
                            settling[payload.noteIndex()] = sequence;
                        } else if (payload.hit() && payload.noteIndex() >= 0) {
                            held[payload.noteIndex()] = true;
                        }
                        inbox.add(new Sent(payload, t + s.deliveryMs(), sequence));
                    }
                    int playTick = (int) get(battle, "playTicks");
                    if (late && join == null && playTick >= lateJoinAtTick) {
                        join = joinPayload(id, battle, chart, playTick);
                        joinArrivesAt = t + s.deliveryMs();
                        madeByJoin = new int[]{made.get(0).size(), made.get(1).size()};
                    }
                }
                case CLIENT_TICK -> {
                    if (paused) {
                        continue;
                    }
                    float playerClock = playerClock(playerEpoch[0], false, 0.0D, t);
                    float spectatorClock = spectatorClock(spectatorEpoch[0], false, 0.0D, t);
                    for (Screen screen : screens) {
                        if (screen != null && screen.joined) {
                            float clock = screen.spectator ? spectatorClock : playerClock;
                            screen.beforeShow();
                            showInClientTick(screen.spectator, clock);
                            showingInTick = true;
                            screen.afterShow(s, label, clock, chart, made, arrived, settling, held);
                            showingInTick = false;
                            finishClientTick(screen.spectator, clock);
                        }
                    }
                }
                case FRAME_START -> {
                    float playerClock = playerClock(playerEpoch[0], paused, pausedAt, t);
                    float spectatorClock = spectatorClock(spectatorEpoch[0], paused, pausedAt, t);
                    while (true) {
                        // The join comes after every action sent before it, and before every one sent after it.
                        if (join != null && !screens[1].joined && joinArrivesAt <= t
                                && (inbox.isEmpty() || inbox.get(0).arrivesAt() > joinArrivesAt)) {
                            spectatorEpoch[0] = (paused ? pausedAt : t) - join.startTick() * TICK_MS;
                            spectatorClock = spectatorClock(spectatorEpoch[0], paused, pausedAt, t);
                            joinMidSong(s, label, screens[1], join, spectatorClock, chart, made, arrived, madeByJoin,
                                    settling, held);
                            continue;
                        }
                        if (inbox.isEmpty() || inbox.get(0).arrivesAt() > t) {
                            break;
                        }
                        Sent sent = inbox.remove(0);
                        int side = sent.payload().owner();
                        arrived[side] = Math.max(arrived[side], sent.sequence() + 1);
                        for (Screen screen : screens) {
                            if (screen != null && screen.joined) {
                                screen.arrivedAt.put(sent.payload(), screen.spectator ? spectatorClock : playerClock);
                                screen.beforeShow();
                            }
                        }
                        acceptAction.invoke(null, sent.payload());
                        payloadsSeen++;
                        for (Screen screen : screens) {
                            if (screen != null && screen.joined) {
                                screen.afterShow(s, label, screen.spectator ? spectatorClock : playerClock, chart, made,
                                        arrived, settling, held);
                            }
                        }
                    }
                }
                case FRAME_RENDER -> {
                    float playerClock = playerClock(playerEpoch[0], paused, pausedAt, t);
                    float spectatorClock = spectatorClock(spectatorEpoch[0], paused, pausedAt, t);
                    for (Screen screen : screens) {
                        if (screen != null && screen.joined) {
                            screen.beforeShow();
                        }
                    }
                    beginRenderFrame.invoke(null);
                    frames++;
                    for (Screen screen : screens) {
                        if (screen != null && screen.joined) {
                            float clock = screen.spectator ? spectatorClock : playerClock;
                            screen.afterShow(s, label, clock, chart, made, arrived, settling, held);
                            screen.drawn(s, label, clock, made, held);
                        }
                    }
                }
            }
        }

        // What the server did, against the rule: a miss is settled, and sent, on the tick its note leaves the panel.
        List<Object> runtimeNotes = (List<Object>) get(battle, "runtimeNotes");
        for (int i = 0; i < notes; i++) {
            Object note = runtimeNotes.get(i);
            int side = chart.get(i)[0];
            check(flag(note, "resolved"), label + ": note " + i + " is settled by the end");
            if (settling[i] == Integer.MAX_VALUE) {
                continue;
            }
            BattleActionPayload last = made.get(side).get(settling[i]);
            if (!last.hit() && !flag(note, "started")) {
                missedTaps++;
                check(last.showAtTick() == chart.get(i)[2] + exit, label + ": the mob's miss of note " + i
                        + " (tick " + chart.get(i)[2] + ") belongs to the tick it leaves the panel on, not "
                        + last.showAtTick());
            } else if (!last.hit()) {
                brokenHolds++;
            }
        }
        for (Screen screen : screens) {
            if (screen != null) {
                check(screen.joined, label + ": " + screen.name + " was sent the battle");
                check(heldList(screen.spectator).isEmpty(), label + ": " + screen.name + " has nothing left waiting");
                for (int i = 0; i < notes; i++) {
                    if (settling[i] != Integer.MAX_VALUE) {
                        check(screen.seenGone[i], label + ", " + screen.name + ": note " + i + " is gone by the end");
                    }
                }
            }
        }
    }

    /** One client's view of the battle: the player the mob fights, or a spectator. */
    static final class Screen {
        final String name;
        final boolean spectator;
        /** How many actions of each side are due on this screen: arrived, and their tick reached. */
        final int[] due = new int[2];
        /** The same as of the last frame drawn, for what a frame newly shows. */
        final int[] dueAtLastFrame = new int[2];
        final int[] shownCombo = new int[2];
        final int[] shownScore = new int[2];
        final boolean[] everStarted;
        final boolean[] seenGone;
        /** The clock on this screen when each action came. */
        final Map<BattleActionPayload, Float> arrivedAt = new java.util.IdentityHashMap<>();
        /**
         * The lanes just before the client last showed actions (beforeShow): flash, burst, and the flash counted on the
         * battle clock - its start, NaN for none, and its length. And each side's punches by then.
         */
        final int[][] flashBefore = new int[2][4];
        final int[][] burstBefore = new int[2][4];
        final float[][] timedStartBefore = new float[2][4];
        final int[][] timedLengthBefore = new int[2][4];
        final int[] punchesBefore = new int[2];
        /** Each broken hold read as it went, with its lane's burst at the break's tick, which the burst goes on from. */
        final Map<BattleActionPayload, Integer> burstAtBreak = new java.util.IdentityHashMap<>();
        /** Whether this screen has the battle: a spectator who joins mid-song has nothing until its join comes. */
        boolean joined = true;
        /** Notes settled before such a spectator's join: they must be gone, but they went before it watched. */
        final boolean[] settledBeforeJoin;

        Screen(String name, boolean spectator, int notes) {
            this.name = name;
            this.spectator = spectator;
            this.everStarted = new boolean[notes];
            this.seenGone = new boolean[notes];
            this.settledBeforeJoin = new boolean[notes];
        }

        /** Notes the lanes and the punches just before the client shows actions, for afterShow to hold them against. */
        void beforeShow() throws Exception {
            int[][] flashes = (int[][]) staticField(spectator ? "spectatorLaneFlashes" : "laneFlashes");
            int[][] bursts = (int[][]) staticField(spectator ? "spectatorHitBursts" : "hitBursts");
            Object timed = staticField(spectator ? "spectatorTimedLaneFlashes" : "timedLaneFlashes");
            float[][] startedAt = (float[][]) timedStartedAt.get(timed);
            int[][] lengths = (int[][]) timedFlashTicks.get(timed);
            for (int owner = 0; owner < 2; owner++) {
                for (int lane = 0; lane < 4; lane++) {
                    flashBefore[owner][lane] = flashes[owner][lane];
                    burstBefore[owner][lane] = bursts[owner][lane];
                    timedStartBefore[owner][lane] = startedAt[owner][lane];
                    timedLengthBefore[owner][lane] = lengths[owner][lane];
                }
                punchesBefore[owner] = punches(spectator, owner);
            }
        }

        /**
         * How a lane the client has just shown a miss on was lit by it, or null when it was left as it was: its flash or
         * burst went up, or a flash was started on the battle clock.
         */
        String litSince(int side, int lane, int[][] flashes, int[][] bursts) throws Exception {
            if (flashes[side][lane] > flashBefore[side][lane]) {
                return "raised its lane's flash from " + flashBefore[side][lane] + " to " + flashes[side][lane];
            }
            if (bursts[side][lane] > burstBefore[side][lane]) {
                return "raised its lane's burst from " + burstBefore[side][lane] + " to " + bursts[side][lane];
            }
            Object timed = staticField(spectator ? "spectatorTimedLaneFlashes" : "timedLaneFlashes");
            float start = ((float[][]) timedStartedAt.get(timed))[side][lane];
            int length = ((int[][]) timedFlashTicks.get(timed))[side][lane];
            if (!Float.isNaN(start) && (Float.isNaN(timedStartBefore[side][lane]) || start != timedStartBefore[side][lane]
                    || length != timedLengthBefore[side][lane])) {
                return "started a flash of " + length + " on the battle clock from " + fmt(start);
            }
            return null;
        }

        /**
         * A lane as a frame drawn at {@code clock} shows it: by its timeline when it is counted on the battle clock
         * (TimedLaneFlashes#apply), otherwise as client ticks have left it. Right after a showing a timed lane holds the
         * values of the tick the action was shown at, which for one shown late is behind the clock.
         */
        int[] drawnAt(int side, int lane, float clock, int[][] flashes, int[][] bursts) throws Exception {
            Object timed = staticField(spectator ? "spectatorTimedLaneFlashes" : "timedLaneFlashes");
            float start = ((float[][]) timedStartedAt.get(timed))[side][lane];
            if (Float.isNaN(start)) {
                return new int[]{flashes[side][lane], bursts[side][lane]};
            }
            int elapsed = Math.max(0, (int) Math.floor(clock - start));
            int length = ((int[][]) timedFlashTicks.get(timed))[side][lane];
            return new int[]{Math.max(0, length - elapsed), Math.max(0, burstTicks - elapsed)};
        }

        /** The tick a broken hold is counted from: its own when it waited for it, or the moment it came after it. */
        float shownFrom(BattleActionPayload payload) {
            Float arrival = arrivedAt.get(payload);
            return arrival == null || arrival < payload.showAtTick() ? payload.showAtTick() : arrival;
        }

        /**
         * Right after the client may have shown actions - a packet handled, a client tick's showDueActions, the start
         * of a frame - and before anything counts a flash down: every note of a mob is drawn exactly until the action
         * that settles it is due, a missed note was never shown held, and a broken hold that has just gone has set its
         * lane's flash as one's own broken hold does, without a burst.
         */
        void afterShow(Scenario s, String label, float clock, List<int[]> chart, List<List<BattleActionPayload>> made,
                       int[] arrived, int[] settling, boolean[] held) throws Exception {
            String where = label + ", " + name + " at " + fmt(clock);
            int[] before = due.clone();
            for (int side = 0; side < 2; side++) {
                List<BattleActionPayload> actions = made.get(side);
                // Arrival and tick both only grow along a side's actions, so the due ones are a prefix of them, and the
                // client shows a side's actions in order: this prefix is what it must have shown.
                while (due[side] < arrived[side] && actions.get(due[side]).showAtTick() <= clock) {
                    due[side]++;
                }
            }
            // One punch for each press just shown, and none for anything else: a miss, a broken hold, a hold's later
            // ticks. Letting go of a hold is no press, and a mob's judgement is its press (triggerJudgementAnimation).
            for (int side = 0; side < 2; side++) {
                int presses = 0;
                for (int k = before[side]; k < due[side]; k++) {
                    presses += KINDS.get(made.get(side).get(k)) == PRESS ? 1 : 0;
                }
                int punched = punches(spectator, side) - punchesBefore[side];
                if (due[side] > before[side]) {
                    punchShowings++;
                    pressesPunched += presses;
                }
                check(punched == presses, where + ": side " + side + " punched " + punched + " times showing "
                        + (due[side] - before[side]) + " actions with " + presses + " presses among them; a miss or a"
                        + " broken hold is no press");
            }
            List<?> notes = clientNotes(spectator);
            int[][] flashes = (int[][]) staticField(spectator ? "spectatorLaneFlashes" : "laneFlashes");
            int[][] bursts = (int[][]) staticField(spectator ? "spectatorHitBursts" : "hitBursts");
            for (int i = 0; i < chart.size(); i++) {
                int side = chart.get(i)[0];
                if (!mobSide(s, side) || seenGone[i] && settling[i] < due[side]) {
                    continue;
                }
                Object note = notes.get(i);
                boolean resolved = noteFlag(note, "resolved");
                everStarted[i] |= noteFlag(note, "started");
                boolean settled = settling[i] < due[side];
                if (resolved != settled) {
                    // An action shown as it came is judged on the battle clock, as a held one is when it is shown: a
                    // hold's completion that comes after the hold's end - after a stall - settles it at once. Judged on
                    // the screen's last client tick, it left the hold started until the next one.
                    if (settled && noteFlag(note, "started") && noteFlag(note, "pendingComplete")) {
                        completionsWaitingForTick++;
                    }
                    check(false, where + ": note " + i + " (tick " + chart.get(i)[2] + ", lane " + chart.get(i)[1]
                            + ") is " + (resolved ? "gone" : "still drawn") + " while the action settling it is "
                            + (settled ? "due" : "not due"));
                }
                if (!resolved || seenGone[i]) {
                    continue;
                }
                seenGone[i] = true;
                if (settledBeforeJoin[i]) {
                    continue;
                }
                BattleActionPayload last = made.get(side).get(settling[i]);
                if (last.hit()) {
                    continue;
                }
                if (!held[i]) {
                    check(!everStarted[i], where + ": note " + i + ", missed, was shown held on its way down");
                    check(clock >= chart.get(i)[2] + exit, where + ": note " + i + " (tick " + chart.get(i)[2]
                            + "), missed, is gone before it has left the panel at " + (chart.get(i)[2] + exit));
                    // It leaves its lane as it was, as one's own miss does (breakOwnComboForMistake without its flash),
                    // unless a press or a broken hold shown with it lights that lane.
                    boolean laneLit = false;
                    for (int k = before[side]; k < due[side]; k++) {
                        BattleActionPayload other = made.get(side).get(k);
                        int kind = KINDS.get(other);
                        laneLit |= other != last && other.lane() == last.lane() && (kind == PRESS || kind == BREAK);
                    }
                    if (!laneLit) {
                        missesLeftDark++;
                        String lit = litSince(side, last.lane(), flashes, bursts);
                        check(lit == null, where + ": note " + i + " (tick " + chart.get(i)[2] + ", lane " + last.lane()
                                + "), missed, " + lit + " as it left; one's own miss leaves its lane as it was");
                    }
                    continue;
                }
                boolean sameLaneHit = false;
                for (int k = before[side]; k < due[side]; k++) {
                    BattleActionPayload other = made.get(side).get(k);
                    sameLaneHit |= other.hit() && other.lane() == last.lane() && other.noteIndex() != last.noteIndex();
                }
                if (!sameLaneHit) {
                    brokenFlashesSeen++;
                    if (showingInTick) {
                        brokenShownByTick++;
                    }
                    // The head's burst goes on as it would have, as one's own break leaves it: from where the lane's
                    // timeline has it at the break's own tick, or where client ticks have counted it to if untimed.
                    // Read as the next frame drawn now would show the lane: a break shown late, after a stall, is
                    // counted from its own tick, so its flash and the burst have run on by the ticks since.
                    int lane = last.lane();
                    float start = shownFrom(last);
                    int burstAt = Float.isNaN(timedStartBefore[side][lane]) ? burstBefore[side][lane]
                            : Math.max(0, burstTicks - Math.max(0, (int) Math.floor(start - timedStartBefore[side][lane])));
                    int late = Math.max(0, (int) Math.floor(clock - start));
                    if (late > 0) {
                        lateBreaksRead++;
                    }
                    int expectedFlash = Math.max(0, ownBreakFlash - late);
                    int expectedBurst = Math.max(0, burstAt - late);
                    burstAtBreak.put(last, burstAt);
                    breakBurstsRead++;
                    int[] lit = drawnAt(side, lane, clock, flashes, bursts);
                    check(lit[0] == expectedFlash && lit[1] == expectedBurst,
                            where + ": note " + i + ", a broken hold shown " + fmt(clock - start) + " ticks after it"
                                    + " broke, is drawn with flash " + lit[0] + " and burst " + lit[1] + "; one's own"
                                    + " break counted from there has " + expectedFlash + " and the head's burst going"
                                    + " on, " + expectedBurst);
                }
            }
        }

        /**
         * A frame as drawn: each mob side's combo and score are those of its last due action, and against the last
         * frame the score has not dropped and the combo has only grown, or dropped to zero with a miss shown. A hold
         * broken since the last frame is drawn with its lane flashing as one's own broken hold is in its first tick,
         * counted on the battle clock from the break's tick, or from the moment it came if it came after it.
         */
        void drawn(Scenario s, String label, float clock, List<List<BattleActionPayload>> made, boolean[] held)
                throws Exception {
            String where = label + ", " + name + " at " + fmt(clock);
            int[][] flashes = (int[][]) staticField(spectator ? "spectatorLaneFlashes" : "laneFlashes");
            int[][] bursts = (int[][]) staticField(spectator ? "spectatorHitBursts" : "hitBursts");
            for (int side = 0; side < 2; side++) {
                for (int k = dueAtLastFrame[side]; k < due[side]; k++) {
                    BattleActionPayload breaking = made.get(side).get(k);
                    if (breaking.hit() || !breaking.complete() || breaking.noteIndex() < 0 || !held[breaking.noteIndex()]) {
                        continue;
                    }
                    boolean laneTouched = false;
                    for (int later = k + 1; later < due[side]; later++) {
                        BattleActionPayload other = made.get(side).get(later);
                        laneTouched |= other.lane() == breaking.lane() && (other.hit() || other.noteIndex() < 0
                                || other.complete() && held[Math.max(0, other.noteIndex())]);
                    }
                    Float arrival = arrivedAt.get(breaking);
                    if (laneTouched || arrival == null) {
                        continue;
                    }
                    float start = arrival < breaking.showAtTick() ? breaking.showAtTick() : arrival;
                    int expected = Math.max(0, ownBreakFlash - (int) Math.floor(clock - start));
                    brokenFlashesDrawn++;
                    check(flashes[side][breaking.lane()] == expected, where + ": the hold broken on lane " + breaking.lane()
                            + " (note " + breaking.noteIndex() + ", tick " + breaking.showAtTick() + ", shown from "
                            + fmt(start) + ") is drawn flashing " + flashes[side][breaking.lane()] + ", one's own would be "
                            + expected);
                    Integer burstAt = burstAtBreak.get(breaking);
                    if (burstAt != null) {
                        int expectedBurst = Math.max(0, burstAt - (int) Math.floor(clock - start));
                        check(bursts[side][breaking.lane()] == expectedBurst, where + ": the hold broken on lane "
                                + breaking.lane() + " (note " + breaking.noteIndex() + ", shown from " + fmt(start)
                                + ") is drawn with burst " + bursts[side][breaking.lane()] + ", the head's own would be "
                                + expectedBurst);
                    }
                }
            }
            // The held pose holds exactly the lanes a mob is shown holding a hold on. The animator bridges draw the pose
            // from these masks, or-ed with the lanes a player's keys hold down; a mob has no keys to add any.
            int[] poses = (int[]) staticField(spectator ? "spectatorAnimationHeldMask" : "animationHeldMask");
            for (int side = 0; side < 2; side++) {
                if (!mobSide(s, side)) {
                    continue;
                }
                int holding = 0;
                for (Object note : clientNotes(spectator)) {
                    if (noteInt(note, "owner") == side && noteInt(note, "durationTicks") > 0 && noteFlag(note, "started")
                            && !noteFlag(note, "resolved")) {
                        holding |= 1 << noteInt(note, "lane");
                    }
                }
                heldLaneFrames += Integer.bitCount(holding);
                check(poses[side] == holding, where + ": side " + side + " is shown holding lanes "
                        + Integer.toBinaryString(holding) + " and its pose holds " + Integer.toBinaryString(poses[side]));
            }
            for (int side = 0; side < 2; side++) {
                if (!mobSide(s, side)) {
                    continue;
                }
                int combo = spectator ? ((int[]) staticField("spectatorCombo"))[side] : staticInt("opponentCombo");
                int score = spectator ? staticInt(side == 0 ? "spectatorLeftScore" : "spectatorRightScore")
                        : staticInt(side == 0 ? "leftScore" : "rightScore");
                BattleActionPayload last = due[side] == 0 ? null : made.get(side).get(due[side] - 1);
                int expectedCombo = last == null ? 0 : last.combo();
                int expectedScore = last == null ? 0 : last.score();
                check(combo == expectedCombo && score == expectedScore, where + ": side " + side + " shows x" + combo
                        + " " + score + ", its last due action says x" + expectedCombo + " " + expectedScore);
                if (combo < shownCombo[side]) {
                    // Only a miss shown in this frame breaks it, and what is left is no more than the hits shown after
                    // that miss: a frame can show a note leaving the panel and a hit on the same tick, x0 then x1.
                    boolean missShown = false;
                    int hitsAfter = 0;
                    for (int k = dueAtLastFrame[side]; k < due[side]; k++) {
                        if (made.get(side).get(k).hit()) {
                            hitsAfter++;
                        } else {
                            missShown = true;
                            hitsAfter = 0;
                        }
                    }
                    check(missShown && combo <= hitsAfter, where + ": side " + side + "'s combo steps back from x"
                            + shownCombo[side] + " to x" + combo + (missShown ? "" : " with no miss shown"));
                }
                check(score >= shownScore[side], where + ": side " + side + "'s score steps back from "
                        + shownScore[side] + " to " + score);
                shownCombo[side] = combo;
                shownScore[side] = score;
                dueAtLastFrame[side] = due[side];
            }
        }
    }

    /**
     * A duel's other player, whose actions the server passes on as they come (SHOW_ON_ARRIVAL): their missed tap,
     * settled by their own client when it has left the panel, and their broken hold.
     */
    static void duelRemotePlayer() throws Exception {
        UUID id = new UUID(9L, 9L);
        List<int[]> chart = List.of(new int[]{1, 2, 100, 0}, new int[]{1, 1, 120, 20});
        resetClient(id, chart, true);
        double epoch = START_MS;
        // Their miss: their own client settles the tap at 100 + exit and it reaches us 2.5 ticks later. Their hold: hit
        // on 120 and let go of on 126, heard of 2 and 1.75 ticks late. The two screens step by quarter ticks, with a
        // client tick on every whole one, so the broken hold comes a quarter tick before a client tick.
        Map<Double, BattleActionPayload> arrivals = Map.of(
                100 + exit + 2.5D, payload(id, 1, 2, 0, 100, false, true, 0, 0),
                122.0D, payload(id, 1, 1, 1, 120, true, false, 100, 1),
                127.75D, payload(id, 1, 1, 1, 120, false, true, 76, 0));
        boolean missDrawn = true;
        boolean missGone = false;
        boolean holdHeld = false;
        int[] breakFlashes = {-1, -1};
        for (double tick = 98.0D; tick <= 131.0D; tick += 0.25D) {
            if (tick == Math.floor(tick)) {
                clientTickAt(epoch, tick);
            }
            BattleActionPayload arriving = arrivals.get(tick);
            if (arriving != null) {
                deliver(epoch, tick, arriving);
            }
            frame(epoch, tick);
            if (tick == 128.0D) {
                // The first frame after the client tick that followed the broken hold: drawn as one's own is.
                breakFlashes[0] = ((int[][]) staticField("laneFlashes"))[1][1];
                breakFlashes[1] = ((int[][]) staticField("spectatorLaneFlashes"))[1][1];
            }
            boolean gone = noteFlag(clientNotes(false).get(0), "resolved") && noteFlag(clientNotes(true).get(0), "resolved");
            if (tick < 100 + exit + 2.5D) {
                missDrawn &= !noteFlag(clientNotes(false).get(0), "resolved") && !noteFlag(clientNotes(true).get(0), "resolved");
            } else if (tick == 100 + exit + 2.5D) {
                missGone = gone && staticInt("opponentCombo") == 0 && ((int[]) staticField("spectatorCombo"))[1] == 0;
            }
            if (tick >= 122.0D && tick < 128.0D) {
                holdHeld |= noteFlag(clientNotes(false).get(1), "started") && noteFlag(clientNotes(true).get(1), "started");
            }
        }
        check(missDrawn, "a duel: the other player's missed tap stays drawn until their miss comes");
        check(missGone, "a duel: their miss takes the note and their combo when it comes");
        check(holdHeld, "a duel: their hold is held");
        check(noteFlag(clientNotes(false).get(1), "resolved") && noteFlag(clientNotes(true).get(1), "resolved"),
                "a duel: their broken hold goes when it comes");
        check(breakFlashes[0] == ownBreakFlash && breakFlashes[1] == ownBreakFlash,
                "a duel: their broken hold is drawn flashing its lane as one's own is, after a client tick too, "
                        + breakFlashes[0] + "/" + breakFlashes[1] + " for " + ownBreakFlash);
    }

    /**
     * A duel's other player takes a hold before its tick - their hit comes early and waits for that tick (pendingHit,
     * see ClientBattleController#applyNoteAction) - and lets go of it before the tick too. Their own screen showed the
     * hit and then the broken hold, so ours must at once, as one's own broken hold: the hold gone, its lane flashing as
     * one's own break does with the hit's burst going on, one punch for the hit and none for letting go, no held pose,
     * and from then on the score and combo the break came with. Once with the break coming while the hold is still on
     * its way down (it used to wait and fall past its button as a missed head, with no flash, until its tick + 7), once
     * on the last tick before the hit would have shown (the hold used to vanish with no flash, and the next client tick
     * put the hit's combo and score back).
     */
    static void duelEarlyHoldLetGo() throws Exception {
        UUID id = new UUID(10L, 10L);
        List<int[]> chart = List.of(new int[]{1, 0, 150, 20}, new int[]{1, 3, 180, 20});
        resetClient(id, chart, true);
        double epoch = START_MS;
        // The hit on 150 comes at 145.25, judged against client tick 145 (before 149, so it waits), and the break at
        // 147.5, judged against 147. The hit on 180 comes at 175.25 and the break at 179.25: after client tick 179 and
        // before tick 180, which would have shown the waiting hit.
        BattleActionPayload[] hits = {payload(id, 1, 0, 0, 150, true, false, 300, 5),
                payload(id, 1, 3, 1, 180, true, false, 400, 6)};
        BattleActionPayload[] breaks = {payload(id, 1, 0, 0, 150, false, true, 276, 0),
                payload(id, 1, 3, 1, 180, false, true, 376, 0)};
        double[] hitAt = {145.25D, 175.25D};
        double[] breakAt = {147.5D, 179.25D};
        int[] shown = {0, 0};
        for (double tick = 144.0D; tick <= 200.0D; tick += 0.25D) {
            if (tick == Math.floor(tick)) {
                clientTickAt(epoch, tick);
            }
            for (int h = 0; h < 2; h++) {
                if (tick == hitAt[h]) {
                    deliver(epoch, tick, hits[h]);
                }
                if (tick != breakAt[h]) {
                    continue;
                }
                int[] punchesBefore = {punches(false, 1), punches(true, 1)};
                deliver(epoch, tick, breaks[h]);
                shown[0] = breaks[h].combo();
                shown[1] = breaks[h].score();
                int lane = chart.get(h)[1];
                for (boolean spectator : new boolean[]{false, true}) {
                    String where = "a duel, " + (spectator ? "spectator" : "player") + ": their hold on " + chart.get(h)[2]
                            + ", taken early and let go of at " + tick;
                    int flash = ((int[][]) staticField(spectator ? "spectatorLaneFlashes" : "laneFlashes"))[1][lane];
                    int burst = ((int[][]) staticField(spectator ? "spectatorHitBursts" : "hitBursts"))[1][lane];
                    int pose = ((int[]) staticField(spectator ? "spectatorAnimationHeldMask" : "animationHeldMask"))[1];
                    check(noteFlag(clientNotes(spectator).get(h), "resolved"), where + ", goes at once");
                    check(flash == ownBreakFlash && burst == burstTicks, where + ", flashes " + flash + " with burst "
                            + burst + "; one's own break flashes " + ownBreakFlash + " over the hit's burst, "
                            + burstTicks + " here");
                    check(punches(spectator, 1) - punchesBefore[spectator ? 1 : 0] == 1, where + ", punches "
                            + (punches(spectator, 1) - punchesBefore[spectator ? 1 : 0]) + " times; once for the hit");
                    check((pose & 1 << lane) == 0, where + ", leaves the held pose on its lane");
                }
            }
            frame(epoch, tick);
            for (boolean spectator : new boolean[]{false, true}) {
                int combo = spectator ? ((int[]) staticField("spectatorCombo"))[1] : staticInt("opponentCombo");
                int score = staticInt(spectator ? "spectatorRightScore" : "rightScore");
                check(combo == shown[0] && score == shown[1], "a duel, " + (spectator ? "spectator" : "player") + " at "
                        + tick + ": their side shows x" + combo + " " + score + ", the last break they let go of says x"
                        + shown[0] + " " + shown[1]);
            }
        }
    }

    /**
     * A duel's other player hits a tap and takes a hold before their ticks, and keeps them: each hit waits for its note's
     * tick (pendingHit) and is shown in the first frame on it (showWaitingHitsDue, from beginRenderFrame), with the score
     * and combo it came with from that very tick, not a tick later when the note is settled, or for the hold, never until
     * its end. And its lane lights there in full: the flash and the burst count from the note's tick on the battle clock,
     * while a client tick that shows the hit and then counts its flashes down drew it from its second step every time.
     * Client ticks on the whole ticks and half a tick after them: the frame on the note's tick shows the hit, as it shows
     * a held mob's, before a client tick half a tick past it would.
     */
    static void duelEarlyHitKept() throws Exception {
        for (double phase : new double[]{0.0D, 0.5D}) {
            duelEarlyHitKept(phase);
        }
    }

    static void duelEarlyHitKept(double phase) throws Exception {
        UUID id = new UUID(11L, (long) (phase * 10));
        List<int[]> chart = List.of(new int[]{1, 2, 100, 0}, new int[]{1, 1, 120, 20});
        resetClient(id, chart, true);
        double epoch = START_MS;
        // The tap's hit comes at 97.25 and the hold's at 117.25, judged against client ticks 96 or 97 and 116 or 117:
        // both wait. The hold is kept, and its completion comes as it is, at 140.25.
        Map<Double, BattleActionPayload> arrivals = Map.of(
                97.25D, payload(id, 1, 2, 0, 100, true, true, 300, 5),
                117.25D, payload(id, 1, 1, 1, 120, true, false, 400, 6),
                140.25D, payload(id, 1, 1, 1, 120, true, true, 420, 6));
        for (double tick = 96.0D; tick <= 145.0D; tick += 0.25D) {
            if (tick - phase == Math.floor(tick - phase)) {
                clientTickAt(epoch, tick);
            }
            BattleActionPayload arriving = arrivals.get(tick);
            if (arriving != null) {
                deliver(epoch, tick, arriving);
            }
            frame(epoch, tick);
            int[] expected = tick < 100.0D ? new int[]{0, 0} : tick < 120.0D ? new int[]{5, 300}
                    : tick < 140.25D ? new int[]{6, 400} : new int[]{6, 420};
            for (boolean spectator : new boolean[]{false, true}) {
                String where = "a duel, client ticks at +" + phase + ", " + (spectator ? "spectator" : "player") + " at "
                        + tick;
                int combo = spectator ? ((int[]) staticField("spectatorCombo"))[1] : staticInt("opponentCombo");
                int score = staticInt(spectator ? "spectatorRightScore" : "rightScore");
                check(combo == expected[0] && score == expected[1], where + ": their side shows x" + combo + " " + score
                        + "; their hit taken early and kept says x" + expected[0] + " " + expected[1] + " from its tick");
                List<?> notes = clientNotes(spectator);
                check(noteFlag(notes.get(0), "resolved") == tick >= 100.0D, where + ": their early tap is "
                        + (noteFlag(notes.get(0), "resolved") ? "gone" : "drawn") + "; it lands in the first frame on its"
                        + " tick, 100");
                // Its completion, coming as it is at 140.25, is judged on the battle clock (140.25, past its end on
                // 140), as the server's is, and settles it at once, whenever the last client tick was: judged on that
                // client tick (139 with the ticks at +0.5), it left the hold started until the next one.
                check(noteFlag(notes.get(1), "started") == (tick >= 120.0D && tick < 140.25D),
                        where + ": their early hold is held from the first frame on its tick, 120, to its completion");
                int[][] flashes = (int[][]) staticField(spectator ? "spectatorLaneFlashes" : "laneFlashes");
                int[][] bursts = (int[][]) staticField(spectator ? "spectatorHitBursts" : "hitBursts");
                for (int[] hit : new int[][]{{2, 100}, {1, 120}}) {
                    if (tick >= hit[1] && tick < hit[1] + 9) {
                        int left = Math.max(0, 8 - stepsSince(epoch, hit[1], tick));
                        check(flashes[1][hit[0]] == left && bursts[1][hit[0]] == left, where + ": lane " + hit[0]
                                + " of their hit on " + hit[1] + " is drawn " + flashes[1][hit[0]] + "/" + bursts[1][hit[0]]
                                + "; counted from its tick it is " + left + "/" + left);
                    }
                }
            }
        }
    }

    /**
     * In Minecraft's own frame order - a frame handles its packets, then the client ticks that are due, then draws - at
     * 30 fps, with the client ticks late in each battle tick (its whole tick + 0.9): a hit of the other player's that came
     * before its note's tick is drawn in the first frame at or after that tick, on the player's screen and the
     * spectator's, its flash and burst from their first step and its score and combo with it. Shown by the next client
     * tick instead, it came up to 0.9 of a tick late, and was drawn from its second step whenever no frame fell between
     * that client tick and the next battle tick. And after a stall of three ticks across the note's tick, in which
     * nothing is drawn and no client tick runs (those it missed run back to back before the next frame), the hit is
     * counted from its note's tick, as a held action's flash is: two steps down in the first frame after the stall, not
     * from its first step as if it had just come.
     */
    static void duelEarlyHitFirstFrame() throws Exception {
        int firstFrames = 0;
        for (double[] stall : new double[][]{{}, {99.4D, 102.4D}}) {
            for (double framePhase = 0.0D; framePhase < 20.0D / 30.0D; framePhase += 0.05D) {
                firstFrames += duelEarlyHitFirstFrame(stall, framePhase);
            }
        }
        check(firstFrames == 56, "a duel at 30 fps: the first frame on each early hit's tick was read, " + firstFrames);
    }

    /** One run of the above: frames from 96 + {@code framePhase}, none in {@code stall}; the first frames read. */
    static int duelEarlyHitFirstFrame(double[] stall, double framePhase) throws Exception {
        UUID id = new UUID(23L + stall.length, Math.round(framePhase * 100));
        List<int[]> chart = List.of(new int[]{1, 2, 100, 0}, new int[]{1, 1, 120, 20});
        resetClient(id, chart, true);
        double epoch = START_MS;
        double[] arrivesAt = {97.3D, 117.3D};
        BattleActionPayload[] hits = {payload(id, 1, 2, 0, 100, true, true, 300, 5),
                payload(id, 1, 1, 1, 120, true, false, 400, 6)};
        boolean[] delivered = new boolean[2];
        boolean[] read = new boolean[2];
        int firstFrames = 0;
        double nextClientTick = 95.9D;
        for (double frame = 96.0D + framePhase; frame < 124.0D; frame += 20.0D / 30.0D) {
            if (stall.length > 0 && frame >= stall[0] && frame < stall[1]) {
                continue;
            }
            for (int h = 0; h < hits.length; h++) {
                if (!delivered[h] && arrivesAt[h] <= frame) {
                    deliver(epoch, frame, hits[h]);
                    delivered[h] = true;
                }
            }
            while (nextClientTick <= frame) {
                clientTickAt(epoch, frame);
                nextClientTick += 1.0D;
            }
            frame(epoch, frame);
            for (int h = 0; h < hits.length; h++) {
                int noteTick = hits[h].noteTick();
                if (frame < noteTick || read[h]) {
                    continue;
                }
                read[h] = true;
                firstFrames++;
                int left = Math.max(0, 8 - stepsSince(epoch, noteTick, frame));
                for (boolean spectator : new boolean[]{false, true}) {
                    String where = "a duel at 30 fps, client ticks at +0.9" + (stall.length > 0 ? ", a stall from "
                            + fmt(stall[0]) + " to " + fmt(stall[1]) : "") + ", " + (spectator ? "spectator" : "player")
                            + ", the first frame at or after " + noteTick + " (" + fmt(frame) + ")";
                    Object note = clientNotes(spectator).get(h);
                    boolean shown = h == 0 ? noteFlag(note, "resolved") : noteFlag(note, "started");
                    int lane = hits[h].lane();
                    int flash = ((int[][]) staticField(spectator ? "spectatorLaneFlashes" : "laneFlashes"))[1][lane];
                    int burst = ((int[][]) staticField(spectator ? "spectatorHitBursts" : "hitBursts"))[1][lane];
                    int combo = spectator ? ((int[]) staticField("spectatorCombo"))[1] : staticInt("opponentCombo");
                    int score = staticInt(spectator ? "spectatorRightScore" : "rightScore");
                    check(shown && flash == left && burst == left && combo == hits[h].combo()
                                    && score == hits[h].score(),
                            where + ": their hit taken early is " + (shown ? "shown" : "still waiting") + ", drawn "
                                    + flash + "/" + burst + " (" + left + "/" + left + " from its tick), x" + combo + " "
                                    + score + " for x" + hits[h].combo() + " " + hits[h].score());
                }
            }
        }
        return firstFrames;
    }

    /**
     * A duel's other player hits two notes before their ticks, the later note first: B (tick 101) with x5, then A
     * (tick 100) with x6. Each hit waits for its own tick, so they are shown in note order, A then B, but the numbers
     * they came with go in the order they came: B's x5 is older than A's x6 and is never shown after it. Once with A
     * waiting too, once with A shown as it came, on 99. Their next action takes over as usual.
     */
    static void duelArrivalOrder() throws Exception {
        for (double aAt : new double[]{98.5D, 99.25D}) {
            UUID id = new UUID(12L, (long) (aAt * 100));
            List<int[]> chart = List.of(new int[]{1, 0, 100, 0}, new int[]{1, 1, 101, 0}, new int[]{1, 2, 105, 0});
            resetClient(id, chart, true);
            double epoch = START_MS;
            Map<Double, BattleActionPayload> arrivals = Map.of(
                    98.25D, payload(id, 1, 1, 1, 101, true, true, 300, 5),
                    aAt, payload(id, 1, 0, 0, 100, true, true, 400, 6),
                    105.25D, payload(id, 1, 2, 2, 105, true, true, 500, 7));
            // A is shown at once when it comes on 99 (a client tick on 99 has passed), or at the client tick on 100.
            double aShown = aAt >= 99.0D ? aAt : 100.0D;
            for (double tick = 97.0D; tick <= 108.0D; tick += 0.25D) {
                if (tick == Math.floor(tick)) {
                    clientTickAt(epoch, tick);
                }
                BattleActionPayload arriving = arrivals.get(tick);
                if (arriving != null) {
                    deliver(epoch, tick, arriving);
                }
                frame(epoch, tick);
                int[] expected = tick < aShown ? new int[]{0, 0} : tick < 105.25D ? new int[]{6, 400} : new int[]{7, 500};
                for (boolean spectator : new boolean[]{false, true}) {
                    int combo = spectator ? ((int[]) staticField("spectatorCombo"))[1] : staticInt("opponentCombo");
                    int score = staticInt(spectator ? "spectatorRightScore" : "rightScore");
                    check(combo == expected[0] && score == expected[1], "a duel, " + (spectator ? "spectator" : "player")
                            + " at " + tick + ", A coming at " + aAt + ": their side shows x" + combo + " " + score
                            + ", their newest action shown says x" + expected[0] + " " + expected[1]);
                }
            }
            for (boolean spectator : new boolean[]{false, true}) {
                check(noteFlag(clientNotes(spectator).get(1), "resolved"), "a duel, A coming at " + aAt
                        + ": B's hit is still shown on its tick");
            }
        }
    }

    /**
     * The other player's flashes that come as they are, in Minecraft's own frame order: a frame handles its packets,
     * then the client ticks that are due, then draws (Minecraft#runTick). A bad press, a hit shown as it came, and a hit
     * that waited for its note's tick are drawn from their first step, as one's own are, whether a client tick falls
     * between the packet and the drawing or not, and count down one step per battle tick from where they were shown.
     * Counted on client ticks, a client tick in between took that step away before anything was drawn.
     */
    static void duelRemoteFlashesTimed() throws Exception {
        UUID id = new UUID(13L, 13L);
        List<int[]> chart = List.of(new int[]{1, 1, 200, 0}, new int[]{1, 3, 230, 0});
        resetClient(id, chart, true);
        double epoch = START_MS;
        // {lane, first step, burst or not, the tick it counts from}: a bad press on lane 2 coming on 180 just before
        // that tick's client tick, a hit on 200 coming on 200 the same way, and a hit on 230 coming on 227.25, which
        // waits for its tick and is shown by the client tick on 230.
        int[][] flashes = {{2, ownBreakFlash, 0, 180}, {1, 8, 1, 200}, {3, 8, 1, 230}};
        Map<Double, BattleActionPayload> arrivals = Map.of(
                180.0D, payload(id, 1, 2, -1, -1, false, false, 0, 0),
                200.0D, payload(id, 1, 1, 0, 200, true, true, 100, 1),
                227.25D, payload(id, 1, 3, 1, 230, true, true, 200, 2));
        int[] read = new int[2];
        for (double tick = 178.0D; tick <= 242.0D; tick += 0.25D) {
            BattleActionPayload arriving = arrivals.get(tick);
            if (arriving != null) {
                deliver(epoch, tick, arriving);
            }
            if (tick == Math.floor(tick)) {
                clientTickAt(epoch, tick);
            }
            frame(epoch, tick);
            for (boolean spectator : new boolean[]{false, true}) {
                int[][] lanes = (int[][]) staticField(spectator ? "spectatorLaneFlashes" : "laneFlashes");
                int[][] bursts = (int[][]) staticField(spectator ? "spectatorHitBursts" : "hitBursts");
                for (int[] flash : flashes) {
                    if (tick < flash[3] || tick >= flash[3] + 10) {
                        continue;
                    }
                    int steps = stepsSince(epoch, flash[3], tick);
                    int expected = Math.max(0, flash[1] - steps);
                    int expectedBurst = flash[2] == 1 ? Math.max(0, 8 - steps) : 0;
                    read[spectator ? 1 : 0]++;
                    check(lanes[1][flash[0]] == expected && bursts[1][flash[0]] == expectedBurst, "a duel, "
                            + (spectator ? "spectator" : "player") + " at " + tick + ": their lane " + flash[0]
                            + " lit on " + flash[3] + " is drawn " + lanes[1][flash[0]] + "/" + bursts[1][flash[0]]
                            + "; counted from there as one's own it is " + expected + "/" + expectedBurst);
                }
            }
        }
        check(read[0] > 100 && read[1] > 100, "a duel: the other player's flashes were read, " + Arrays.toString(read));
    }

    /** The player our own client plays, as the server knows them. */
    static final UUID OWN_PLAYER = new UUID(21L, 21L);
    /** How long an action takes from the server to a screen in the cases below, in ticks. */
    static final double DUEL_DELIVERY_TICKS = 0.15D;

    /** An action on its way to the screens, and whether the client whose judgement it is gets it too. */
    record Outgoing(BattleActionPayload payload, double arrivesAt, boolean toOwnClient) {
    }

    /**
     * One's own hold taken before its tick and let go of before the tick came, end to end: our own client (the real
     * controller's key path, sendLaneState, and the rest of its tick), the real server judging what it sends
     * (judgeClient, laneInput, tickParticipants), and the actions that go out from there, shown to a spectator and, in
     * a second pass, to the other player. Taken two client ticks or more early, the hit waits for its tick
     * (pendingHit), and the release used to find no hold to let go of: no HOLD_BREAK went out, the hold started on its
     * tick with the key up, our client never settled it, and the server, which had started it with the hit, completed
     * it as a hit with its hold score once its fallback ran out (end + 60), on every screen. Now it is a broken hold
     * everywhere, as an ordinary hold let go of early is: on our screen at once, gone, its lane flashing as one's own
     * break does, the combo at zero, a mistake and 24 points less; on the server the same numbers, no hold score, the
     * note missed; on the other screens gone at once with the break's flash and the hit's burst going on. Next to it,
     * an early hold kept to its end (complete, its hold score once), an ordinary hold let go of early (the reference),
     * and a one-tick hold taken early and let go of on the tick before its own, inside the release window: complete.
     */
    static void ownEarlyHoldLetGo() throws Exception {
        UUID id = new UUID(14L, 14L);
        List<int[]> chart = List.of(new int[]{0, 0, 150, 20}, new int[]{0, 1, 200, 20}, new int[]{0, 2, 250, 20},
                new int[]{0, 3, 300, 1});
        // Keys, on our client ticks (whole ticks + 0.25, so a key's tick is the whole tick): down, up.
        int[][] keys = {{0, 147, 148}, {1, 197, 221}, {2, 250, 254}, {3, 297, 299}};
        Object battle = playerBattle(id, chart);
        resetClient(id, chart, true);
        setStatic("ownSpeed", 1.0F);
        Arrays.fill((int[]) staticField("laneHitLockoutUntil"), Integer.MIN_VALUE);
        Arrays.fill((boolean[]) staticField("previousLaneDown"), false);
        @SuppressWarnings("unchecked")
        List<Object> sentToServer = (List<Object>) Class.forName("net.neoforged.neoforge.network.PacketDistributor")
                .getField("SENT").get(null);
        sentToServer.clear();
        double epoch = START_MS;
        List<Outgoing> outgoing = new ArrayList<>();
        List<Object> packets = new ArrayList<>();
        int scoreAfterHit = -1;
        float brokenAt = Float.NaN;
        for (double tick = 140.0D; tick <= 380.0D; tick += 0.25D) {
            // The server's tick, on the battle tick that has begun.
            if (tick == Math.floor(tick)) {
                serverTick(battle, (int) tick, outgoing, tick);
            }
            for (Outgoing out : new ArrayList<>(outgoing)) {
                if (out.arrivesAt() <= tick && out.arrivesAt() > tick - 0.25D) {
                    deliverTo(epoch, tick, out.payload(), out.toOwnClient());
                }
            }
            if (tick - Math.floor(tick) == 0.25D) {
                int whole = (int) Math.floor(tick);
                ownClientTick(epoch, tick, keys);
                float spectatorClock = spectatorClock(epoch, true, epoch + tick * TICK_MS, 0.0D);
                showInClientTick(true, spectatorClock);
                finishClientTick(true, spectatorClock);
                List<Object> now = new ArrayList<>(sentToServer);
                sentToServer.clear();
                packets.addAll(now);
                serverReceives(battle, now, outgoing, tick);
                Object own = clientNotes(false).get(0);
                if (whole == 147) {
                    scoreAfterHit = staticInt("leftScore");
                }
                if (whole == 148) {
                    brokenAt = (float) tick;
                    check(noteFlag(own, "resolved") && !noteFlag(own, "started"), "one's own hold taken at 147 and let go"
                            + " of at 148, before its tick 150, is gone at once as a broken hold");
                    check(((int[][]) staticField("laneFlashes"))[0][0] == ownBreakFlash && staticInt("ownCombo") == 0
                                    && staticInt("ownMistakes") == 1 && staticInt("leftScore") == scoreAfterHit - 24,
                            "and its lane flashes " + ((int[][]) staticField("laneFlashes"))[0][0] + " (one's own break: "
                                    + ownBreakFlash + "), the combo is x" + staticInt("ownCombo") + ", mistakes "
                                    + staticInt("ownMistakes") + ", score " + staticInt("leftScore") + " after "
                                    + scoreAfterHit + " (24 less)");
                    Object note = ((List<?>) get(battle, "runtimeNotes")).get(0);
                    Object side = get(battle, "leftState");
                    check(flag(note, "resolved") && flag(note, "missedResolved") && !flag(note, "hitResolved")
                                    && Array.get(get(side, "activeHolds"), 0) == null
                                    && (int) get(side, "combo") == 0 && (int) get(side, "mistakes") == 1
                                    && (int) get(side, "score") == staticInt("leftScore"),
                            "the server takes the break: the hold missed, combo x" + get(side, "combo") + ", mistakes "
                                    + get(side, "mistakes") + ", score " + get(side, "score"));
                }
                if (whole >= 148) {
                    check(noteFlag(own, "resolved") && !noteFlag(own, "started")
                                    && (((int[]) staticField("animationHeldMask"))[0] & 1) == 0,
                            "one's own hold let go of before its tick never starts later, nor holds the pose, at " + tick);
                }
            }
            frame(epoch, tick);
            if (tick >= brokenAt + DUEL_DELIVERY_TICKS && tick < brokenAt + 1.0D) {
                // The spectator: the break has come.
                Object seen = clientNotes(true).get(0);
                int[][] flashes = (int[][]) staticField("spectatorLaneFlashes");
                check(noteFlag(seen, "resolved") && flashes[0][0] == ownBreakFlash
                                && ((int[]) staticField("spectatorCombo"))[0] == 0
                                && (((int[]) staticField("spectatorAnimationHeldMask"))[0] & 1) == 0,
                        "a spectator sees one's own hold let go of before its tick gone at once at " + tick + ", its lane"
                                + " flashing " + flashes[0][0] + " as a break, x" + ((int[]) staticField("spectatorCombo"))[0]);
            }
            if (tick >= 150.0D && tick < 240.0D) {
                check(!noteFlag(clientNotes(true).get(0), "started"), "a spectator never sees that hold held, at " + tick);
            }
        }
        // What our client sent for that hold: the hit, its key, then the break before the key went up.
        List<String> told = new ArrayList<>();
        for (Object packet : packets) {
            if (packet instanceof ClientJudgementPayload judgement && judgement.noteIndex() == 0) {
                told.add(judgement.kind() == ClientJudgementPayload.NOTE_HIT ? "hit@" + judgement.clientTick()
                        : judgement.kind() == ClientJudgementPayload.HOLD_BREAK ? "break@" + judgement.clientTick()
                        : "kind" + judgement.kind() + "@" + judgement.clientTick());
            } else if (packet instanceof NoteInputPayload input && input.lane() == 0) {
                told.add((input.down() ? "down@" : "up@") + input.clientTick());
            }
        }
        check(told.equals(List.of("hit@147", "down@147", "break@148", "up@148")), "for the hold let go of before its tick"
                + " our client sends the hit, the key, and the break before the key goes up: " + told);
        // What the server sent about it: the hit and the break, and nothing when its fallback would have run (230).
        List<String> actions = new ArrayList<>();
        for (Outgoing out : outgoing) {
            BattleActionPayload action = out.payload();
            if (action.owner() == 0) {
                actions.add(action.noteIndex() + (action.hit() ? " hit" : " miss") + (action.complete() ? " complete" : ""));
            }
        }
        check(actions.equals(List.of("0 hit", "0 miss complete", "1 hit", "1 hit complete", "2 hit",
                        "2 miss complete", "3 hit", "3 hit complete")),
                "the server's actions for one's own side: the early hold broken, the kept one complete, the ordinary"
                        + " break, the one-tick hold complete; no fallback hit: " + actions);
        Object side = get(battle, "leftState");
        check((int) get(side, "score") == staticInt("leftScore") && (int) get(side, "mistakes") == staticInt("ownMistakes")
                        && (int) get(side, "combo") == staticInt("ownCombo") && staticInt("ownMistakes") == 2,
                "the server and our client end on the same score, combo and mistakes: " + get(side, "score") + "/x"
                        + get(side, "combo") + "/" + get(side, "mistakes") + " and " + staticInt("leftScore") + "/x"
                        + staticInt("ownCombo") + "/" + staticInt("ownMistakes") + ", two breaks");

        // The other player's screen: every action of ours comes to them.
        resetClient(id, chart, true);
        setStatic("ownOwner", 1);
        boolean breakSeen = false;
        for (double tick = 140.0D; tick <= 380.0D; tick += 0.25D) {
            for (Outgoing out : outgoing) {
                if (out.arrivesAt() <= tick && out.arrivesAt() > tick - 0.25D) {
                    deliver(epoch, tick, out.payload());
                }
            }
            if (tick == Math.floor(tick)) {
                clientTickAt(epoch, tick);
            }
            frame(epoch, tick);
            Object theirs = clientNotes(false).get(0);
            if (tick >= brokenAt + DUEL_DELIVERY_TICKS && tick < brokenAt + 1.0D) {
                breakSeen = true;
                check(noteFlag(theirs, "resolved") && ((int[][]) staticField("laneFlashes"))[0][0] == ownBreakFlash
                                && staticInt("opponentCombo") == 0 && (((int[]) staticField("animationHeldMask"))[0] & 1) == 0,
                        "the other player sees our hold let go of before its tick gone at once at " + tick
                                + ", its lane flashing " + ((int[][]) staticField("laneFlashes"))[0][0] + ", x"
                                + staticInt("opponentCombo"));
            }
            if (tick >= 150.0D && tick < 240.0D) {
                check(!noteFlag(theirs, "started"), "the other player never sees that hold held, at " + tick);
            }
        }
        check(breakSeen && staticInt("leftScore") == (int) get(side, "score") && staticInt("opponentCombo") == (int) get(side, "combo"),
                "the other player ends on the server's score and combo for us: " + staticInt("leftScore") + " x"
                        + staticInt("opponentCombo"));
        setStatic("ownOwner", 0);
    }

    /**
     * The server's own rules for a player's hold whose key goes up (laneInput) and whose judgement never comes
     * (tickHold's fallback). A client of this build judges every release before its key goes up, so a hold still going
     * when its key goes up is one no judgement will ever settle: let go of before its release window, it is broken at
     * once, as on the client (combo x0, a mistake, 24 points, no hold score, one action to everyone); inside the window
     * it is complete. Only a hold whose key is still down waits for the fallback, which counts it as held to its end:
     * a client that stopped sending, or a lag spike. A HOLD_COMPLETE that comes after the fallback, and the key going up
     * after it, change nothing: the late player is not punished. A judged release followed by its key is settled once.
     */
    static void playerHoldRelease() throws Exception {
        UUID id = new UUID(15L, 15L);
        List<int[]> chart = List.of(new int[]{0, 0, 150, 20}, new int[]{0, 1, 200, 20}, new int[]{0, 2, 250, 20},
                new int[]{0, 3, 300, 20}, new int[]{0, 0, 360, 20}, new int[]{0, 1, 400, 20});
        Object battle = playerBattle(id, chart);
        Object side = get(battle, "leftState");
        List<Outgoing> outgoing = new ArrayList<>();
        int[] sequence = {0};
        // {tick, packet}: what an older client - one that sends no break for a hold it took early - and a lagging one
        // send. A hit is judged with the score and combo the client then has.
        Map<Integer, List<Object>> packets = new HashMap<>();
        java.util.function.BiConsumer<Integer, Object> at = (tick, packet) ->
                packets.computeIfAbsent(tick, key -> new ArrayList<>()).add(packet);
        at.accept(147, judgement(id, sequence, ClientJudgementPayload.NOTE_HIT, 0, 0, 147, 100, 1, 0));
        at.accept(147, new NoteInputPayload(id, 0, 147, true, 0));
        at.accept(148, new NoteInputPayload(id, 0, 148, false, -1));
        at.accept(200, judgement(id, sequence, ClientJudgementPayload.NOTE_HIT, 1, 1, 200, 200, 2, 0));
        at.accept(200, new NoteInputPayload(id, 1, 200, true, 1));
        at.accept(250, judgement(id, sequence, ClientJudgementPayload.NOTE_HIT, 2, 2, 250, 300, 3, 0));
        at.accept(250, new NoteInputPayload(id, 2, 250, true, 2));
        at.accept(340, judgement(id, sequence, ClientJudgementPayload.HOLD_COMPLETE, 2, 2, 271, 320, 3, 0));
        at.accept(341, new NoteInputPayload(id, 2, 271, false, 2));
        at.accept(300, judgement(id, sequence, ClientJudgementPayload.NOTE_HIT, 3, 3, 300, 400, 4, 0));
        at.accept(300, new NoteInputPayload(id, 3, 300, true, 3));
        at.accept(319, new NoteInputPayload(id, 3, 319, false, 3));
        at.accept(360, judgement(id, sequence, ClientJudgementPayload.NOTE_HIT, 0, 4, 360, 500, 5, 0));
        at.accept(360, new NoteInputPayload(id, 0, 360, true, 4));
        at.accept(365, judgement(id, sequence, ClientJudgementPayload.HOLD_BREAK, 0, 4, 365, 476, 0, 1));
        at.accept(365, new NoteInputPayload(id, 0, 365, false, 4));
        at.accept(400, judgement(id, sequence, ClientJudgementPayload.NOTE_HIT, 1, 5, 400, 576, 1, 1));
        at.accept(400, new NoteInputPayload(id, 1, 400, true, 5));
        at.accept(420, judgement(id, sequence, ClientJudgementPayload.HOLD_COMPLETE, 1, 5, 420, 596, 1, 1));
        at.accept(421, new NoteInputPayload(id, 1, 421, false, 5));
        List<Object> notes = (List<Object>) get(battle, "runtimeNotes");
        int[] scoreBefore = new int[1];
        for (int tick = 140; tick <= 500; tick++) {
            List<Object> now = packets.getOrDefault(tick, List.of());
            scoreBefore[0] = (int) get(side, "score");
            int mistakesBefore = (int) get(side, "mistakes");
            int actionsBefore = outgoing.size();
            serverReceives(battle, now, outgoing, tick);
            serverTick(battle, tick, outgoing, tick);
            int made = outgoing.size() - actionsBefore;
            String newest = made == 0 ? "none" : describe(outgoing.get(outgoing.size() - 1));
            switch (tick) {
                case 148 -> check(flag(notes.get(0), "missedResolved") && (int) get(side, "combo") == 0
                                && (int) get(side, "mistakes") == mistakesBefore + 1
                                && (int) get(side, "score") == scoreBefore[0] - 24 && made == 1
                                && newest.equals("0 miss complete to everyone"),
                        "a hold whose key goes up at 148, before its tick 150, with no judgement: broken at once, x"
                                + get(side, "combo") + ", mistakes " + get(side, "mistakes") + ", score "
                                + get(side, "score") + " from " + scoreBefore[0] + ", " + newest);
                case 280 -> check(flag(notes.get(1), "hitResolved") && made == 1 && newest.equals("1 hit complete to everyone")
                                && (int) get(side, "score") == scoreBefore[0] + 20,
                        "a hold whose key is still down, with no judgement by its end + 60: counted as held, "
                                + newest + ", score " + get(side, "score"));
                case 330 -> check(flag(notes.get(2), "hitResolved") && made == 1 && (int) get(side, "score") == scoreBefore[0] + 20,
                        "a lagging client's hold reaches its fallback still down: held, " + newest);
                case 340 -> check(made == 0 && (int) get(side, "score") == scoreBefore[0] && (int) get(side, "mistakes") == 0,
                        "its HOLD_COMPLETE coming after that changes nothing: " + newest + ", score " + get(side, "score"));
                case 341 -> check(made == 0 && (int) get(side, "mistakes") == 0 && (int) get(side, "score") == scoreBefore[0],
                        "nor does its key going up then: no break for the late player, " + newest);
                case 319 -> check(flag(notes.get(3), "hitResolved") && made == 1 && newest.equals("3 hit complete to everyone")
                                && (int) get(side, "score") == scoreBefore[0] + 20,
                        "a hold whose key goes up inside its release window (319, its end 320) with no judgement: complete,"
                                + " " + newest + ", score " + get(side, "score"));
                case 365 -> check(flag(notes.get(4), "missedResolved") && made == 1 && (int) get(side, "mistakes") == 1
                                && newest.equals("4 miss complete to all but its player"),
                        "a judged break and then its key: one break, " + newest + ", mistakes " + get(side, "mistakes"));
                case 421 -> check(made == 0 && flag(notes.get(5), "hitResolved"), "a judged completion and then its key:"
                        + " one completion, " + newest);
                default -> {
                    boolean expected = tick == 147 || tick == 200 || tick == 250 || tick == 300 || tick == 360
                            || tick == 400 || tick == 420;
                    check(made == (expected ? 1 : 0), "at " + tick + " the server sends " + made + " actions for one's own"
                            + " side, " + newest);
                }
            }
        }
        for (Object note : notes) {
            check(flag(note, "resolved"), "every hold of the player is settled by the end");
        }
    }

    /**
     * Every release of ours is judged once, by our own client, before its key goes up. A hold let go of inside its
     * release window is complete at once, its hold score counted, and only waits for its end tick to be settled
     * (pendingComplete): the next release on its lane belongs to the next hold. Two holds back to back on one lane, A
     * let go of inside its window and B taken before its tick on the same tick or the next, then let go of before B's
     * tick: the release found A again, judged it complete a second time with its hold score, and never let go of B,
     * which the server broke at the key-up while our screen kept the combo - at note speed 5, 240 x2 and no mistake on
     * ours against 196 x0 and a mistake on the server's, the spectator's and the other player's. With an input offset of
     * -100 ms the input clock reaches A's end while B is held, two ticks before the client tick that settles A, and A was
     * completed there a second time (resolveLocalCompletedHolds). B is the last note: nothing of ours comes after it to
     * write our numbers over the server's.
     */
    static void ownReleasesJudgedOnce() throws Exception {
        List<int[]> chart = List.of(new int[]{0, 0, 150, 20}, new int[]{0, 0, 171, 10});
        long run = 0;
        for (float speed : new float[]{1.0F, 5.0F}) {
            for (int[] k : new int[][]{{168, 168, 168}, {168, 168, 169}, {169, 169, 169}, {168, 169, 169}}) {
                OwnPlay play = ownPlay(new UUID(16L, run++), chart, new int[][]{{0, 149, k[0]}, {0, k[1], k[2]}}, speed,
                        0, 140.0D, 260.0D);
                String where = "holds back to back on one lane at note speed " + speed + ", A (150, 20 long) let go of on "
                        + k[0] + ", the lane down again on " + k[1] + " and up on " + k[2];
                checkOwnPlay(where, play, chart.size());
                check(play.releases(0) == 1, where + ": A is let go of once, " + play.told());
                if (play.hits(1) > 0) {
                    check(play.releases(1) == 1 && play.told().contains("break#1@" + k[2]), where + ": B, taken before"
                            + " its tick and let go of before it, is broken by our client: " + play.told());
                }
            }
        }
        OwnPlay play = ownPlay(new UUID(16L, run++), chart, new int[][]{{0, 149, 169}, {0, 169, 169}}, 5.0F, 0, 140.0D,
                260.0D);
        check(play.told().equals(List.of("hit#0@149", "down0@149", "complete#0@169", "up0@169", "hit#1@169", "down0@169",
                        "break#1@169", "up0@169")) && Arrays.equals(play.client(), new int[]{196, 0, 1}),
                "A let go of on 169 inside its window, B (171) taken and let go of on 169 at note speed 5: our client"
                        + " completes A once and breaks B before the key goes up, " + play.told() + ", and shows "
                        + Arrays.toString(play.client()) + " as the server does");
        play = ownPlay(new UUID(16L, run), chart, new int[][]{{0, 149, 166}, {0, 166, 168}}, 1.0F, -100, 140.0D, 260.0D);
        String where = "an input offset of -100 ms, A let go of on 166 (input 168, inside its window) and B taken on 166"
                + " and held to 168 (input 170, A's end)";
        checkOwnPlay(where, play, chart.size());
        check(play.told().equals(List.of("hit#0@151", "down0@151", "complete#0@168", "up0@168", "hit#1@168", "down0@168",
                "break#1@170", "up0@170")), where + ": A is completed once, at its release, " + play.told());
    }

    /**
     * Taps hit before their tick, the hit waiting for that tick (pendingHit), and let go of before it, as a quick tap
     * is: plain hits, with no hold judgement, at every early distance the window allows at note speeds 1, 3 and 5.
     * Only a hold is taken at its release while its hit waits (waitingOwnHold); a tap taken there was judged as a hold
     * let go of, a break from three ticks early on (x0, a mistake, 24 points) and a hold completion two ticks early.
     */
    static void ownEarlyTapsLetGo() throws Exception {
        List<int[]> chart = List.of(new int[]{0, 0, 150, 0}, new int[]{0, 1, 170, 0}, new int[]{0, 2, 190, 0});
        long run = 0;
        int waitedAndLetGo = 0;
        for (float speed : new float[]{1.0F, 3.0F, 5.0F}) {
            int window = ru.gora.musicbattle.data.NoteSpeed.earlyHitWindowTicks(speed);
            for (int early = 0; early <= window; early++) {
                for (int held = 0; held <= 2; held++) {
                    int[][] keys = new int[chart.size()][];
                    for (int n = 0; n < keys.length; n++) {
                        keys[n] = new int[]{n, chart.get(n)[2] - early, chart.get(n)[2] - early + held};
                    }
                    OwnPlay play = ownPlay(new UUID(18L, run++), chart, keys, speed, 0, 140.0D, 220.0D);
                    String where = "taps at note speed " + speed + " hit " + early + " ticks early and let go of " + held
                            + " ticks after";
                    checkOwnPlay(where, play, chart.size());
                    check(play.told().stream().noneMatch(t -> t.startsWith("complete#") || t.startsWith("break#"))
                                    && play.client()[1] == chart.size() && play.client()[2] == 0,
                            where + ": three plain hits, x" + play.client()[1] + " and " + play.client()[2]
                                    + " mistakes, " + play.told());
                    if (early >= 2 && held < early) {
                        waitedAndLetGo++;
                    }
                }
            }
        }
        check(waitedAndLetGo >= 10, "taps whose hit waited were let go of before their tick: " + waitedAndLetGo);
    }

    /**
     * A note of ours hit before its tick is hit: only showing it waits for that tick (pendingHit), and the server took
     * it at the hit. So the next press on its lane is for the next note, a press on another lane is not a bad press
     * for it, and it is not missed, however far a large negative input offset puts the input clock ahead of the tick
     * that shows it. Taken as still open, the next press hit it a second time - its score counted twice on our screen,
     * the server turning the second hit down - and the note that press was for went by as a miss: at note speed 1, taps
     * on 150 and 152 pressed on 147 and 149, or at an input offset of -100 ms at note speed 5 too. A bad press our client
     * counted for it, and a miss at an offset of -400 ms or more, were turned down by the server just the same.
     */
    static void ownWaitingNotesTakenOnce() throws Exception {
        long run = 0;
        int pressedAgain = 0;
        for (float speed : new float[]{1.0F, 3.0F, 5.0F}) {
            int window = ru.gora.musicbattle.data.NoteSpeed.earlyHitWindowTicks(speed);
            for (int offset : new int[]{0, -100}) {
                for (int gap : new int[]{2, 3, 4}) {
                    List<int[]> chart = List.of(new int[]{0, 0, 150, 0}, new int[]{0, 0, 150 + gap, 0});
                    int inputAhead = -offset / 50;
                    for (int first = 150 - window - inputAhead; first <= 148 - inputAhead; first++) {
                        for (int second = first + 2; second <= 150 + gap - inputAhead; second++) {
                            OwnPlay play = ownPlay(new UUID(19L, run++), chart,
                                    new int[][]{{0, first, first}, {0, second, second}}, speed, offset, 140.0D, 200.0D);
                            String where = "taps on 150 and " + (150 + gap) + " at note speed " + speed + " and an input"
                                    + " offset of " + offset + " ms, pressed on " + first + " and " + second;
                            checkOwnPlay(where, play, chart.size());
                            check(play.hits(0) <= 1 && play.hits(1) <= 1, where + ": no note is hit twice, "
                                    + play.told());
                            pressedAgain++;
                        }
                    }
                }
            }
        }
        check(pressedAgain > 100, "a lane pressed again while its early hit waited: " + pressedAgain + " plays");
        List<int[]> chart = List.of(new int[]{0, 0, 150, 0}, new int[]{0, 0, 152, 0});
        OwnPlay play = ownPlay(new UUID(19L, run++), chart, new int[][]{{0, 147, 147}, {0, 149, 149}}, 1.0F, 0, 140.0D,
                200.0D);
        check(play.told().equals(List.of("hit#0@147", "down0@147", "up0@147", "hit#1@149", "down0@149", "up0@149"))
                        && Arrays.equals(play.client(), play.server()) && play.client()[1] == 2 && play.client()[2] == 0,
                "taps on 150 and 152 at note speed 1 pressed on 147 and 149: two hits, one each, " + play.told() + ", "
                        + Arrays.toString(play.client()));

        // A press on an empty lane while the early hit waits, then the next note hit on time.
        chart = List.of(new int[]{0, 0, 150, 0}, new int[]{0, 1, 175, 0});
        play = ownPlay(new UUID(19L, run++), chart, new int[][]{{0, 145, 145}, {2, 147, 147}, {1, 175, 175}}, 1.0F, 0,
                140.0D, 220.0D);
        String where = "at note speed 1, a tap on 150 hit on 145 and lane 2, which has no note, pressed on 147";
        checkOwnPlay(where, play, chart.size());
        check(play.told().equals(List.of("hit#0@145", "down0@145", "up0@145", "down2@147", "up2@147", "hit#1@175",
                "down1@175", "up1@175")) && play.client()[1] == 2 && play.client()[2] == 0,
                where + ": no bad press for the note already hit, " + play.told() + ", " + Arrays.toString(play.client()));

        // Input offsets of -500 and -400 ms: the input clock 10 and 8 ticks ahead of the one that shows the hits.
        chart = List.of(new int[]{0, 0, 150, 0}, new int[]{0, 1, 170, 0});
        for (int offset : new int[]{-500, -400}) {
            int ahead = -offset / 50;
            play = ownPlay(new UUID(19L, run++), chart, new int[][]{{0, 146, 146}, {1, 170 - ahead, 170 - ahead}}, 5.0F,
                    offset, 140.0D, 220.0D);
            where = "an input offset of " + offset + " ms, a tap on 150 hit on 146 (input " + (146 + ahead)
                    + ") and one on 170 hit on time on the input clock";
            checkOwnPlay(where, play, chart.size());
            check(play.told().equals(List.of("hit#0@" + (146 + ahead), "down0@" + (146 + ahead), "up0@" + (146 + ahead),
                    "hit#1@170", "down1@170", "up1@170")) && play.client()[1] == 2 && play.client()[2] == 0,
                    where + ": both are hits and neither is missed while its hit waits, " + play.told() + ", "
                            + Arrays.toString(play.client()));
        }
    }

    /**
     * Our screen while we fight one battle (X) and, as a spectator, hear a neighbouring one (Y): ClientBattleController
     * keeps a battle it plays and one it watches side by side, and each view keeps its own record of the newest numbers
     * shown per side (showRemoteState), for one battle's actions say nothing about the other's. X's opponent, side 1,
     * hits a note before its tick and its numbers wait for that tick, while a bad press of Y's side 1 is shown on the
     * spectator's panel: X's waiting numbers still show on their tick. And once X is over, with only the watched battle
     * ticking, as ClientBattleController#tick does: Y's side 1 hits before its tick while X's opponent was shown after
     * that, and Y's waiting numbers still show on their tick. With one record for both, the newer action of the other
     * battle held them back, and that panel kept the older numbers.
     */
    static void duelTwoBattles() throws Exception {
        UUID x = new UUID(20L, 1L);
        UUID y = new UUID(20L, 2L);
        List<int[]> chartX = List.of(new int[]{1, 0, 104, 0}, new int[]{1, 1, 113, 0});
        List<int[]> chartY = List.of(new int[]{1, 2, 116, 0});
        resetClient(x, chartX, true);
        setStatic("spectatorBattleId", y);
        fillNotes(true, chartY);
        double epoch = START_MS;
        Map<Double, BattleActionPayload> arrivals = Map.of(
                101.25D, payload(x, 1, 0, 0, 104, true, true, 300, 5),
                102.25D, payload(y, 1, 3, -1, -1, false, false, 76, 0),
                112.25D, payload(y, 1, 2, 0, 116, true, true, 500, 9),
                113.25D, payload(x, 1, 1, 1, 113, true, true, 400, 6));
        double over = 114.0D;
        for (double tick = 100.0D; tick <= 120.0D; tick += 0.25D) {
            if (tick == over) {
                setStatic("active", false);
            }
            if (tick == Math.floor(tick)) {
                // ClientBattleController#tick runs the battle it plays, and only the one it watches when it plays none.
                boolean spectator = tick >= over;
                float clock = spectator ? spectatorClock(epoch, true, epoch + tick * TICK_MS, 0.0D)
                        : playerClock(epoch, true, epoch + tick * TICK_MS, 0.0D);
                showInClientTick(spectator, clock);
                finishClientTick(spectator, clock);
            }
            BattleActionPayload arriving = arrivals.get(tick);
            if (arriving != null) {
                deliver(epoch, tick, arriving);
            }
            frame(epoch, tick);
            if (tick >= 104.0D && tick < 113.25D) {
                check(staticInt("opponentCombo") == 5 && staticInt("rightScore") == 300, "fighting X and watching Y, at "
                        + tick + ": X's opponent shows x" + staticInt("opponentCombo") + " " + staticInt("rightScore")
                        + "; their hit on 104, taken early, says x5 300, and a bad press of Y's came after it");
            }
            int[] combos = (int[]) staticField("spectatorCombo");
            if (tick >= 102.25D && tick < 116.0D) {
                check(combos[1] == 0 && staticInt("spectatorRightScore") == 76, "fighting X and watching Y, at " + tick
                        + ": Y's side 1 shows x" + combos[1] + " " + staticInt("spectatorRightScore")
                        + "; its bad press says x0 76");
            } else if (tick >= 116.0D) {
                check(combos[1] == 9 && staticInt("spectatorRightScore") == 500, "watching Y once X is over, at " + tick
                        + ": Y's side 1 shows x" + combos[1] + " " + staticInt("spectatorRightScore") + "; its hit on 116,"
                        + " taken early, says x9 500, though X's opponent was shown after it came");
            }
        }
        setStatic("active", true);
    }

    /**
     * A spectator's refresh of the battle it watches (startSpectatorBattle with that battle's id, to a client that plays
     * none) brings the server's numbers as it sent it, after every action that came before it, so they are the newest
     * on show for both sides (showRemoteState). Side 1 hits A (tick 104) and side 0 hits C (tick 106) before their
     * ticks, and their x5 300 and x3 150 wait for those ticks; then the refresh comes with x7 500 for side 1 and x4 220
     * for side 0, counting actions this client never got. At 104 and 106 A and C are shown, and their numbers are not:
     * they are older than the refresh's. Set without a number of its own, the refresh was taken back to the waiting
     * numbers there until the side's next action. Side 1's next, B on 110, shows as usual.
     */
    static void spectatorRefresh() throws Exception {
        UUID id = new UUID(24L, 24L);
        List<int[]> chart = List.of(new int[]{1, 0, 104, 0}, new int[]{1, 1, 110, 0}, new int[]{0, 2, 106, 0});
        resetClient(id, chart, false);
        Method startSpectatorBattle = controller.getDeclaredMethod("startSpectatorBattle", SpectatorBattlePayload.class);
        double epoch = START_MS;
        Map<Double, BattleActionPayload> arrivals = Map.of(
                101.25D, payload(id, 1, 0, 0, 104, true, true, 300, 5),
                101.5D, new BattleActionPayload(id, 0, 2, 2, 106, true, true, 150, 3, 150, 0,
                        BattleActionPayload.SHOW_ON_ARRIVAL),
                109.75D, payload(id, 1, 1, 1, 110, true, true, 600, 8));
        for (double tick = 100.0D; tick <= 112.0D; tick += 0.25D) {
            if (tick == Math.floor(tick)) {
                float clock = spectatorClock(epoch, true, epoch + tick * TICK_MS, 0.0D);
                showInClientTick(true, clock);
                finishClientTick(true, clock);
            }
            BattleActionPayload arriving = arrivals.get(tick);
            if (arriving != null) {
                deliver(epoch, tick, arriving);
            }
            if (tick == 102.25D) {
                spectatorClock(epoch, true, epoch + tick * TICK_MS, 0.0D);
                startSpectatorBattle.invoke(null, spectatorRefresh(id, 220, 4, 500, 7));
            }
            frame(epoch, tick);
            int[] combos = (int[]) staticField("spectatorCombo");
            int[] expected = tick < 102.25D ? new int[]{0, 0, 0, 0} : tick < 109.75D ? new int[]{220, 4, 500, 7}
                    : new int[]{220, 4, 600, 8};
            int[] shown = {staticInt("spectatorLeftScore"), combos[0], staticInt("spectatorRightScore"), combos[1]};
            check(Arrays.equals(shown, expected), "watching a battle refreshed on 102.25, at " + tick + ": side 0 shows x"
                    + shown[1] + " " + shown[0] + " and side 1 x" + shown[3] + " " + shown[2] + "; the newest numbers are"
                    + " x" + expected[1] + " " + expected[0] + " and x" + expected[3] + " " + expected[2] + " (the hits on"
                    + " 104 and 106 came before the refresh, their x5 300 and x3 150 are older)");
            for (int[] hit : new int[][]{{0, 104}, {2, 106}}) {
                // On the controller's own clock, which can read a whole tick a hair early (see stepsSince).
                boolean due = Math.floor(clockReading(epoch, tick)) >= hit[1];
                check(noteFlag(clientNotes(true).get(hit[0]), "resolved") == due, "watching a battle refreshed on"
                        + " 102.25, at " + tick + ": the hit on " + hit[1] + ", taken early, is shown on its tick all the"
                        + " same");
            }
        }
    }

    /**
     * A refresh of the watched battle while a mob's actions are still held for their tick: their hits on 104 and 106
     * came on 102 and 102.5, the refresh on 103 with the numbers after them both, x6 400. The held actions are older than
     * the refresh and are numbered as they came (ClientBattleController.HeldAction), so shown on their ticks they settle
     * their notes and keep the refresh's numbers: numbered as they were shown, after the refresh, the hit on 104 took the
     * side back to x5 300 until 106. Both as an older server refreshes, with its newest numbers, and as this one does, with
     * the numbers of the battle's tick (none yet) and the mob's actions ahead of it, the newest of which this panel shows.
     */
    static void spectatorRefreshWhileHeld() throws Exception {
        for (boolean withLead : new boolean[]{false, true}) {
            UUID id = new UUID(25L, withLead ? 2L : 1L);
            List<int[]> chart = List.of(new int[]{1, 0, 104, 0}, new int[]{1, 1, 106, 0});
            resetClient(id, chart, false);
            Method startSpectatorBattle = controller.getDeclaredMethod("startSpectatorBattle", SpectatorBattlePayload.class);
            double epoch = START_MS;
            BattleActionPayload first = new BattleActionPayload(id, 1, 0, 0, 104, true, true, 300, 5, 0, 300, 104);
            BattleActionPayload second = new BattleActionPayload(id, 1, 1, 1, 106, true, true, 400, 6, 0, 400, 106);
            Map<Double, BattleActionPayload> arrivals = Map.of(102.0D, first, 102.5D, second);
            for (double tick = 100.0D; tick <= 110.0D; tick += 0.25D) {
                if (tick == Math.floor(tick)) {
                    float clock = spectatorClock(epoch, true, epoch + tick * TICK_MS, 0.0D);
                    showInClientTick(true, clock);
                    finishClientTick(true, clock);
                }
                BattleActionPayload arriving = arrivals.get(tick);
                if (arriving != null) {
                    deliver(epoch, tick, arriving);
                }
                if (tick == 103.0D) {
                    spectatorClock(epoch, true, epoch + tick * TICK_MS, 0.0D);
                    SpectatorBattlePayload refresh = withLead ? withLeadActions(spectatorRefresh(id, 0, 0, 0, 0),
                            List.of(first, second)) : spectatorRefresh(id, 0, 0, 400, 6);
                    startSpectatorBattle.invoke(null, refresh);
                }
                frame(epoch, tick);
                int[] combos = (int[]) staticField("spectatorCombo");
                int[] shown = {staticInt("spectatorRightScore"), combos[1]};
                int[] expected = tick < 103.0D ? new int[]{0, 0} : new int[]{400, 6};
                String where = "watching a battle refreshed on 103" + (withLead ? " with the mob's actions ahead" : "")
                        + " while its hits on 104 and 106 wait, at " + tick;
                check(Arrays.equals(shown, expected), where + ": side 1 shows x" + shown[1] + " " + shown[0]
                        + "; the newest numbers are x" + expected[1] + " " + expected[0]);
                for (int[] hit : new int[][]{{0, 104}, {1, 106}}) {
                    boolean due = Math.floor(clockReading(epoch, tick)) >= hit[1];
                    check(noteFlag(clientNotes(true).get(hit[0]), "resolved") == due, where + ": the hit on " + hit[1]
                            + " is shown on its tick");
                }
            }
        }
    }

    /**
     * A spectator who starts watching a duel on 148, as the real server's join (Battle#spectatorJoin) has the player's
     * side then: a tap hit on 140 and one missed on 141, both inside the hit window the panel cannot tell from the
     * chart; a hold from 120 still held; a tap on 150 hit early on 147 and a hold on 154 taken early on 148, both still to
     * come. The settled ones are gone at once, the held one is held with its pose, and the early ones are struck and taken
     * on their own ticks, as their actions would have shown them. Before, the panel had only the held keys: the settled
     * taps fell past their buttons, and so did the early ones, whose actions it never got.
     */
    static void spectatorJoinsDuel() throws Exception {
        UUID id = new UUID(27L, 1L);
        List<int[]> chart = List.of(new int[]{0, 0, 140, 0}, new int[]{0, 1, 120, 40}, new int[]{0, 2, 150, 0},
                new int[]{0, 3, 154, 20}, new int[]{0, 0, 141, 0});
        Object battle = playerBattle(id, chart);
        set(get(battle, "leftState"), "speed", 1.0F);
        List<Outgoing> outgoing = new ArrayList<>();
        int[] sequence = {0};
        Map<Integer, List<Object>> packets = new HashMap<>();
        java.util.function.BiConsumer<Integer, Object> at = (tick, packet) ->
                packets.computeIfAbsent(tick, key -> new ArrayList<>()).add(packet);
        at.accept(120, judgement(id, sequence, ClientJudgementPayload.NOTE_HIT, 1, 1, 120, 100, 1, 0));
        at.accept(120, new NoteInputPayload(id, 1, 120, true, 1));
        at.accept(140, judgement(id, sequence, ClientJudgementPayload.NOTE_HIT, 0, 0, 140, 200, 2, 0));
        at.accept(147, judgement(id, sequence, ClientJudgementPayload.NOTE_HIT, 2, 2, 147, 290, 3, 0));
        at.accept(148, judgement(id, sequence, ClientJudgementPayload.NOTE_MISS, 0, 4, 148, 290, 0, 1));
        at.accept(148, judgement(id, sequence, ClientJudgementPayload.NOTE_HIT, 3, 3, 148, 380, 1, 1));
        at.accept(148, new NoteInputPayload(id, 3, 148, true, 3));
        for (int tick = 110; tick <= 148; tick++) {
            serverReceives(battle, packets.getOrDefault(tick, List.of()), outgoing, tick);
            serverTick(battle, tick, outgoing, tick);
        }
        Object side = get(battle, "leftState");
        int[] server = {(int) get(side, "score"), (int) get(side, "combo")};
        resetClient(id, chart, false);
        setStatic("spectatorBattleId", id);
        setStatic("spectatorLocalTicks", 148);
        double epoch = START_MS;
        spectatorClock(epoch, true, epoch + 148 * TICK_MS, 0.0D);
        SpectatorBattlePayload join = joinPayload(id, battle, chart, 148);
        takeSpectatorPanel.invoke(null, join);
        int punchesAtJoin = punches(true, 0);
        // Up to the end of the hold from 120, which the player keeps held (its completion is still to come).
        for (double tick = 148.0D; tick <= 160.0D; tick += 0.25D) {
            if (tick == Math.floor(tick)) {
                float clock = spectatorClock(epoch, true, epoch + tick * TICK_MS, 0.0D);
                showInClientTick(true, clock);
                finishClientTick(true, clock);
            }
            frame(epoch, tick);
            String where = "a spectator who starts watching a duel on 148, at " + tick;
            List<?> notes = clientNotes(true);
            double clock = Math.floor(clockReading(epoch, tick));
            boolean[] gone = {true, false, clock >= 150, false, true};
            boolean[] heldNow = {false, true, false, clock >= 154, false};
            for (int n = 0; n < chart.size(); n++) {
                check(noteFlag(notes.get(n), "resolved") == gone[n] && noteFlag(notes.get(n), "started") == heldNow[n],
                        where + ": note " + n + " (tick " + chart.get(n)[2] + ") is " + (noteFlag(notes.get(n), "resolved")
                                ? "gone" : noteFlag(notes.get(n), "started") ? "held" : "drawn") + ", it should be "
                                + (gone[n] ? "gone" : heldNow[n] ? "held" : "drawn"));
            }
            int pose = ((int[]) staticField("spectatorAnimationHeldMask"))[0];
            int expectedPose = 0b0010 | (clock >= 154 ? 0b1000 : 0);
            check(pose == expectedPose, where + ": the player's held pose is " + Integer.toBinaryString(pose)
                    + ", it holds " + Integer.toBinaryString(expectedPose));
            int[] shown = {staticInt("spectatorLeftScore"), ((int[]) staticField("spectatorCombo"))[0]};
            check(Arrays.equals(shown, server), where + ": the player's side shows x" + shown[1] + " " + shown[0]
                    + ", the server's numbers are x" + server[1] + " " + server[0]);
            int[][] flashes = (int[][]) staticField("spectatorLaneFlashes");
            if (tick >= 150.0D && tick < 151.0D) {
                check(flashes[0][2] > 0, where + ": the early tap on 150 flashes its lane as it is struck");
            }
        }
        check(punches(true, 0) - punchesAtJoin == 2, "a spectator who starts watching a duel on 148 sees the early tap"
                + " and the early hold struck once each: " + (punches(true, 0) - punchesAtJoin) + " punches");
    }

    /** The same payload with a mob's actions ahead of the battle's tick, as this server sends a join or a refresh. */
    static SpectatorBattlePayload withLeadActions(SpectatorBattlePayload p, List<BattleActionPayload> lead) {
        return new SpectatorBattlePayload(p.battleId(), p.durationTicks(), p.startTick(), p.bpm(), p.speedMultiplier(),
                p.leftEntityId(), p.rightEntityId(), p.cameraEntityId(), p.cameraYaw(), p.cameraPitch(), p.cameraFov(),
                p.leftColor(), p.rightColor(), p.leftPanelOpacity(), p.leftLaneOpacity(), p.leftPanelWidthScale(),
                p.leftPanelHeightScale(), p.leftNoteWidthScale(), p.leftNoteHeightScale(), p.rightPanelOpacity(),
                p.rightLaneOpacity(), p.rightPanelWidthScale(), p.rightPanelHeightScale(), p.rightNoteWidthScale(),
                p.rightNoteHeightScale(), p.leftSpeed(), p.rightSpeed(), p.leftScore(), p.rightScore(), p.leftCombo(),
                p.rightCombo(), p.leftHeldMask(), p.rightHeldMask(), p.notes(), p.effects(), p.leftAppearance(),
                p.rightAppearance(), p.settledNotes(), p.startedNotes(), lead, p.beatOffsetMs());
    }

    /**
     * One's own early hit flashes its lane once, at the press: a tap on 150 and a hold on 170 (10 long), each pressed four
     * ticks early at note speed 1, so that their hits wait for their tick (pendingHit). The lane lights at the press,
     * as for a hit on time, and only counts down after it; at the note's tick the note is struck, or the hold taken,
     * with no second flash or burst and no judged punch, which one's own hit on time never gets either. It used to light
     * up again there, 7/7 after that tick's count, and punch a second time.
     */
    static void ownEarlyHitFlashesOnce() throws Exception {
        UUID id = new UUID(26L, 1L);
        List<int[]> chart = List.of(new int[]{0, 0, 150, 0}, new int[]{0, 1, 170, 10});
        int[][] keys = {{0, 146, 146}, {1, 166, 182}};
        resetClient(id, chart, true);
        setStatic("ownSpeed", 1.0F);
        Arrays.fill((int[]) staticField("laneHitLockoutUntil"), Integer.MIN_VALUE);
        Arrays.fill((boolean[]) staticField("previousLaneDown"), false);
        sentToServer().clear();
        int presses = ((int[]) staticField("animationTriggerSerial"))[0];
        int judged = ((int[]) staticField("animationJudgedSerial"))[0];
        double epoch = START_MS;
        int[] last = {0, 0};
        int[] lit = {0, 0};
        for (double tick = 140.0D; tick <= 190.0D; tick += 0.25D) {
            if (tick - Math.floor(tick) == 0.25D) {
                ownClientTick(epoch, tick, keys);
            }
            frame(epoch, tick);
            int[][] flashes = (int[][]) staticField("laneFlashes");
            int[][] bursts = (int[][]) staticField("hitBursts");
            for (int n = 0; n < 2; n++) {
                int lane = chart.get(n)[1];
                String where = "one's own " + (n == 0 ? "tap on 150" : "hold on 170") + " pressed on " + keys[n][1]
                        + ", at " + tick;
                if (flashes[0][lane] > last[n]) {
                    lit[n]++;
                    check(Math.floor(tick) == keys[n][1], where + ": its lane lights up again, " + last[n] + " to "
                            + flashes[0][lane] + " (burst " + bursts[0][lane] + "); it lit at the press only");
                }
                last[n] = flashes[0][lane];
                // One's own waiting hits are shown by the client tick (here a quarter tick into each tick).
                boolean struck = tick >= chart.get(n)[2] + 0.25D;
                Object note = clientNotes(false).get(n);
                if (n == 0 || tick < 180.0D) {
                    check(noteFlag(note, n == 0 ? "resolved" : "started") == struck, where + ": the note is struck by the"
                            + " client tick on its tick");
                }
            }
        }
        check(lit[0] == 1 && lit[1] == 1, "one's own early tap and hold light their lanes " + lit[0] + " and " + lit[1]
                + " times; once each, at the press");
        check(((int[]) staticField("animationTriggerSerial"))[0] - presses == 2
                        && ((int[]) staticField("animationJudgedSerial"))[0] == judged,
                "one's own two early hits punch " + (((int[]) staticField("animationTriggerSerial"))[0] - presses)
                        + " times at their presses and " + (((int[]) staticField("animationJudgedSerial"))[0] - judged)
                        + " times more when they are struck; once each, at the press");
    }

    /**
     * A refresh of the watched battle with these numbers, everything else as the spectator's panel has it now, so that it
     * changes nothing but the numbers.
     */
    static SpectatorBattlePayload spectatorRefresh(UUID id, int leftScore, int leftCombo, int rightScore, int rightCombo)
            throws Exception {
        float[][] styles = (float[][]) staticField("spectatorPanelStyles");
        int[] heldMasks = (int[]) staticField("spectatorAnimationInputHeldMask");
        NoteAppearance[] appearances = (NoteAppearance[]) staticField("spectatorNoteAppearances");
        @SuppressWarnings("unchecked")
        List<TrackEffect> effects = (List<TrackEffect>) staticField("spectatorEffects");
        return new SpectatorBattlePayload(id, staticInt("spectatorDurationTicks"), staticInt("spectatorLocalTicks"),
                staticInt("spectatorTrackBpm"), (float) staticField("spectatorPlaybackSpeed"),
                staticInt("spectatorLeftEntityId"), staticInt("spectatorRightEntityId"),
                staticInt("spectatorCameraEntityId"), (float) staticField("spectatorCameraYaw"),
                (float) staticField("spectatorCameraPitch"), (float) staticField("spectatorCameraFov"),
                staticInt("spectatorLeftColor"), staticInt("spectatorRightColor"), styles[0][0], styles[0][1],
                styles[0][2], styles[0][3], styles[0][4], styles[0][5], styles[1][0], styles[1][1], styles[1][2],
                styles[1][3], styles[1][4], styles[1][5], (float) staticField("spectatorLeftSpeed"),
                (float) staticField("spectatorRightSpeed"), leftScore, rightScore, leftCombo, rightCombo, heldMasks[0],
                heldMasks[1], List.of(), effects, appearances[0], appearances[1],
                staticInt("spectatorBeatOffsetMs"));
    }

    /** One play of ours against the real server, and the numbers it left on every screen: see ownPlay. */
    record OwnPlay(List<String> told, int rejected, int keyUpSettled, int[] client, int[] server, int[] spectator,
                   int[] opponent, int unresolved) {
        /** How often our client judged that note's hold let go of, complete or broken. */
        int releases(int note) {
            return (int) told.stream().filter(t -> t.startsWith("complete#" + note + "@")
                    || t.startsWith("break#" + note + "@")).count();
        }

        /** How often our client hit that note. */
        int hits(int note) {
            return (int) told.stream().filter(t -> t.startsWith("hit#" + note + "@")).count();
        }
    }

    /**
     * What every play of ours must leave: the server takes every judgement our client sends and never has to settle a
     * hold itself when its key goes up, our screen ends on the server's score, combo and mistakes, so do the spectator's
     * and the other player's screens, and every note is settled.
     */
    static void checkOwnPlay(String where, OwnPlay play, int notes) {
        check(play.rejected() == 0, where + ": the server takes every judgement our client sends, " + play.rejected()
                + " turned down, " + play.told());
        check(play.keyUpSettled() == 0, where + ": our client judges every release before its key goes up; the server"
                + " settled " + play.keyUpSettled() + " at the key, " + play.told());
        check(Arrays.equals(play.client(), play.server()), where + ": our screen ends on the server's numbers, "
                + Arrays.toString(play.client()) + " and " + Arrays.toString(play.server()) + ", " + play.told());
        check(play.spectator()[0] == play.server()[0] && play.spectator()[1] == play.server()[1]
                        && play.opponent()[0] == play.server()[0] && play.opponent()[1] == play.server()[1],
                where + ": the spectator and the other player end on them too, " + Arrays.toString(play.spectator())
                        + " and " + Arrays.toString(play.opponent()));
        check(play.unresolved() == 0, where + ": every note is settled on the server and on our screen, "
                + play.unresolved() + " of " + notes + " are not");
    }

    /**
     * Our own client plays {@code keys} - {lane, down, up} on its local ticks - at note speed {@code speed} and an input
     * offset, against the real server, as ownEarlyHoldLetGo does: the controller's key path and the rest of its tick on
     * every whole tick + 0.25, judgeClient and laneInput for what it sends, tickParticipants on the whole ticks. A
     * spectator sees every action, and in a second pass so does the other player's screen. Counted on the way: the
     * judgements the server turned down, and the holds it settled itself when their key went up.
     */
    static OwnPlay ownPlay(UUID id, List<int[]> chart, int[][] keys, float speed, int inputOffsetMs, double from,
                           double to) throws Exception {
        Object battle = playerBattle(id, chart);
        Object side = get(battle, "leftState");
        set(side, "speed", speed);
        set(get(battle, "rightState"), "speed", speed);
        resetClient(id, chart, true);
        setStatic("ownSpeed", speed);
        Arrays.fill((int[]) staticField("laneHitLockoutUntil"), Integer.MIN_VALUE);
        Arrays.fill((boolean[]) staticField("previousLaneDown"), false);
        List<Object> sentToServer = sentToServer();
        sentToServer.clear();
        double epoch = START_MS;
        List<Outgoing> outgoing = new ArrayList<>();
        List<String> told = new ArrayList<>();
        int rejected = 0;
        int keyUpSettled = 0;
        setInputOffset(inputOffsetMs);
        try {
            for (double tick = from; tick <= to; tick += 0.25D) {
                if (tick == Math.floor(tick)) {
                    serverTick(battle, (int) tick, outgoing, tick);
                }
                for (Outgoing out : new ArrayList<>(outgoing)) {
                    if (out.arrivesAt() <= tick && out.arrivesAt() > tick - 0.25D) {
                        deliverTo(epoch, tick, out.payload(), out.toOwnClient());
                    }
                }
                if (tick - Math.floor(tick) == 0.25D) {
                    ownClientTick(epoch, tick, keys);
                    float spectatorClock = spectatorClock(epoch, true, epoch + tick * TICK_MS, 0.0D);
                    showInClientTick(true, spectatorClock);
                    finishClientTick(true, spectatorClock);
                    List<Object> now = new ArrayList<>(sentToServer);
                    sentToServer.clear();
                    for (Object packet : now) {
                        told.add(told(packet));
                        int before = outgoing.size();
                        serverReceives(battle, List.of(packet), outgoing, tick);
                        if (packet instanceof ClientJudgementPayload judgement
                                && (int) get(side, "lastClientJudgementSequence") != judgement.sequence()) {
                            rejected++;
                        }
                        if (packet instanceof NoteInputPayload && outgoing.size() > before) {
                            keyUpSettled++;
                        }
                    }
                }
                frame(epoch, tick);
            }
        } finally {
            setInputOffset(0);
        }
        int[] client = {staticInt("leftScore"), staticInt("ownCombo"), staticInt("ownMistakes")};
        int[] server = {(int) get(side, "score"), (int) get(side, "combo"), (int) get(side, "mistakes")};
        int[] spectator = {staticInt("spectatorLeftScore"), ((int[]) staticField("spectatorCombo"))[0]};
        int unresolved = 0;
        List<?> serverNotes = (List<?>) get(battle, "runtimeNotes");
        for (int n = 0; n < chart.size(); n++) {
            if (!flag(serverNotes.get(n), "resolved") || !noteFlag(clientNotes(false).get(n), "resolved")) {
                unresolved++;
            }
        }

        resetClient(id, chart, true);
        setStatic("ownOwner", 1);
        try {
            for (double tick = from; tick <= to; tick += 0.25D) {
                for (Outgoing out : outgoing) {
                    if (out.arrivesAt() <= tick && out.arrivesAt() > tick - 0.25D) {
                        deliver(epoch, tick, out.payload());
                    }
                }
                if (tick == Math.floor(tick)) {
                    clientTickAt(epoch, tick);
                }
                frame(epoch, tick);
            }
        } finally {
            setStatic("ownOwner", 0);
        }
        int[] opponent = {staticInt("leftScore"), staticInt("opponentCombo")};
        return new OwnPlay(told, rejected, keyUpSettled, client, server, spectator, opponent, unresolved);
    }

    /** A packet our client sent, as the checks above name it: hit#note@tick, down0@tick and so on. */
    static String told(Object packet) {
        if (packet instanceof ClientJudgementPayload judgement) {
            String kind = switch (judgement.kind()) {
                case ClientJudgementPayload.NOTE_HIT -> "hit";
                case ClientJudgementPayload.HOLD_COMPLETE -> "complete";
                case ClientJudgementPayload.HOLD_BREAK -> "break";
                case ClientJudgementPayload.NOTE_MISS -> "miss";
                case ClientJudgementPayload.BAD_PRESS -> "bad";
                default -> "kind" + judgement.kind();
            };
            return kind + "#" + judgement.noteIndex() + "@" + judgement.clientTick();
        }
        NoteInputPayload input = (NoteInputPayload) packet;
        return (input.down() ? "down" : "up") + input.lane() + "@" + input.clientTick();
    }

    /** What our client has sent since the list was last cleared (the test-only PacketDistributor). */
    @SuppressWarnings("unchecked")
    static List<Object> sentToServer() throws Exception {
        return (List<Object>) Class.forName("net.neoforged.neoforge.network.PacketDistributor").getField("SENT").get(null);
    }

    /** The player's input offset (ClientBattleSettings), by which their keys are judged against the battle clock. */
    static void setInputOffset(int milliseconds) throws Exception {
        Field field = Class.forName("ru.gora.musicbattle.client.ClientBattleSettings").getDeclaredField("inputOffsetMs");
        field.setAccessible(true);
        field.set(null, milliseconds);
    }

    static ClientJudgementPayload judgement(UUID id, int[] sequence, int kind, int lane, int note, int tick, int score,
                                            int combo, int mistakes) {
        return new ClientJudgementPayload(id, ++sequence[0], kind, lane, note, tick, score, combo, mistakes);
    }

    static String describe(Outgoing out) {
        BattleActionPayload action = out.payload();
        return action.noteIndex() + (action.hit() ? " hit" : " miss") + (action.complete() ? " complete" : "")
                + (out.toOwnClient() ? " to everyone" : " to all but its player");
    }

    /** A duel of two players on the isolated battle classes: nothing is played for them, only judged and timed out. */
    static Object playerBattle(UUID id, List<int[]> chart) throws Exception {
        Object battle = create(battleType);
        set(battle, "id", id);
        List<Object> notes = new ArrayList<>();
        Constructor<?> constructor = noteType.getDeclaredConstructor(int.class, BattleNote.class);
        constructor.setAccessible(true);
        for (int[] note : chart) {
            notes.add(constructor.newInstance(notes.size(), new BattleNote(note[0], note[1], note[2], note[3])));
        }
        set(battle, "runtimeNotes", notes);
        set(battle, "leftState", side(0, false));
        set(battle, "rightState", side(1, false));
        set(battle, "aiTicks", Integer.MIN_VALUE);
        set(battle, "durationTicks", 1000);
        leadRecord(battle);
        return battle;
    }

    /** What the server makes of packets from our client, in the order they came: judgements and keys. */
    static void serverReceives(Object battle, List<Object> packets, List<Outgoing> outgoing, double tick) throws Exception {
        for (Object packet : packets) {
            sentNow.clear();
            boolean judged = packet instanceof ClientJudgementPayload;
            if (judged) {
                judgeClient.invoke(null, battle, 0, packet, OWN_PLAYER, "Own");
            } else {
                NoteInputPayload input = (NoteInputPayload) packet;
                laneInput.invoke(null, battle, 0, input.lane(), input.clientTick(), input.down());
            }
            for (BattleActionPayload action : sentNow) {
                // broadcastAction leaves out the player whose judgement it passes on; the server's own go to everyone.
                outgoing.add(new Outgoing(action, tick + DUEL_DELIVERY_TICKS, !judged));
            }
        }
    }

    /** One server tick on battle tick {@code playTick}: the players' holds and misses the server settles itself. */
    static void serverTick(Object battle, int playTick, List<Outgoing> outgoing, double tick) throws Exception {
        set(battle, "playTicks", playTick);
        sentNow.clear();
        tickParticipants.invoke(battle);
        for (BattleActionPayload action : sentNow) {
            outgoing.add(new Outgoing(action, tick + DUEL_DELIVERY_TICKS, true));
        }
    }

    /** Hands an action to the spectator, and to our own battle only when {@code activeView}. */
    static void deliverTo(double epochMs, double tick, BattleActionPayload payload, boolean activeView) throws Exception {
        boolean active = (boolean) staticField("active");
        setStatic("active", active && activeView);
        try {
            deliver(epochMs, tick, payload);
        } finally {
            setStatic("active", active);
        }
    }

    /**
     * Our own client's tick at battle tick {@code tick}, in ClientBattleController#tick's order: due actions, waiting
     * note actions and numbers, one's own misses and completed holds, the held pose, the flashes, and last the keys that
     * changed since the last tick, each through sendLaneState as the key drain sends it.
     */
    static void ownClientTick(double epochMs, double tick, int[][] keys) throws Exception {
        float clock = playerClock(epochMs, true, epochMs + tick * TICK_MS, 0.0D);
        int local = Math.max(0, (int) Math.floor(clock));
        setStatic("localTicks", local);
        showDueActions.invoke(null, heldList(false), false, clock);
        List<?> notes = clientNotes(false);
        int[][] flashes = (int[][]) staticField("laneFlashes");
        int[][] bursts = (int[][]) staticField("hitBursts");
        flushPendingNoteActions.invoke(null, notes, local, flashes, bursts, false);
        lastFlushTick[0] = local;
        flushPendingRemoteStates.invoke(null, notes, false);
        resolveLocalMissedNotes.invoke(null);
        resolveLocalCompletedHolds.invoke(null);
        sanitizeAnimationHeldMasks.invoke(null, notes, staticField("animationHeldMask"), local);
        Object timed = staticField("timedLaneFlashes");
        for (int owner = 0; owner < 2; owner++) {
            for (int lane = 0; lane < 4; lane++) {
                if ((boolean) timedTimed.invoke(timed, owner, lane)) {
                    continue;
                }
                if (flashes[owner][lane] > 0) flashes[owner][lane]--;
                if (bursts[owner][lane] > 0) bursts[owner][lane]--;
            }
        }
        timedApply.invoke(timed, clock, flashes, bursts);
        boolean[] previous = (boolean[]) staticField("previousLaneDown");
        for (int[] key : keys) {
            for (int k = 1; k <= 2; k++) {
                if (key[k] == local) {
                    boolean down = k == 1;
                    sendLaneState.invoke(null, key[0], down);
                    previous[key[0]] = down;
                }
            }
        }
    }

    /**
     * Whole battle ticks from {@code from} to {@code tick} as the controller counts them (TimedLaneFlashes#apply): on
     * its own float clock, which reads a whole tick a hair early where tick x 50 ms in nanoseconds is no float, and from
     * the earlier of the tick and that clock, as a hit shown on its tick starts there (showWaitingHit).
     */
    static int stepsSince(double epochMs, double from, double tick) {
        float start = Math.min((float) from, clockReading(epochMs, from));
        return Math.max(0, (int) Math.floor(clockReading(epochMs, tick) - start));
    }

    /** What the controller's battle clock reads at battle tick {@code tick} (activeVisualTick, spectatorVisualTick). */
    static float clockReading(double epochMs, double tick) {
        return (nanos(epochMs + tick * TICK_MS) - nanos(epochMs)) / 50_000_000.0F;
    }

    static BattleActionPayload payload(UUID id, int owner, int lane, int note, int noteTick, boolean hit,
                                       boolean complete, int score, int combo) {
        return new BattleActionPayload(id, owner, lane, note, noteTick, hit, complete, score, combo, 0, score,
                BattleActionPayload.SHOW_ON_ARRIVAL);
    }

    static void deliver(double epochMs, double tick, BattleActionPayload payload) throws Exception {
        playerClock(epochMs, true, epochMs + tick * TICK_MS, 0.0D);
        spectatorClock(epochMs, true, epochMs + tick * TICK_MS, 0.0D);
        acceptAction.invoke(null, payload);
    }

    static void frame(double epochMs, double tick) throws Exception {
        playerClock(epochMs, true, epochMs + tick * TICK_MS, 0.0D);
        spectatorClock(epochMs, true, epochMs + tick * TICK_MS, 0.0D);
        beginRenderFrame.invoke(null);
    }

    static void clientTickAt(double epochMs, double tick) throws Exception {
        for (boolean spectator : new boolean[]{false, true}) {
            float clock = spectator ? spectatorClock(epochMs, true, epochMs + tick * TICK_MS, 0.0D)
                    : playerClock(epochMs, true, epochMs + tick * TICK_MS, 0.0D);
            showInClientTick(spectator, clock);
            finishClientTick(spectator, clock);
        }
    }

    /**
     * The first half of what ClientBattleController#tick (the player) or #tickSpectatorBattle does with actions in a
     * client tick: the due actions are shown. finishClientTick is the rest, which comes before anything is drawn.
     */
    static void showInClientTick(boolean spectator, float clock) throws Exception {
        setStatic(spectator ? "spectatorLocalTicks" : "localTicks", Math.max(0, (int) Math.floor(clock)));
        showDueActions.invoke(null, heldList(spectator), spectator, clock);
    }

    /**
     * Pending note actions and states, the held poses checked against the holds still on (sanitizeAnimationHeldMasks),
     * and the flashes that count down on client ticks, after them the timed ones.
     */
    static void finishClientTick(boolean spectator, float clock) throws Exception {
        int tick = Math.max(0, (int) Math.floor(clock));
        List<?> notes = clientNotes(spectator);
        int[][] flashes = (int[][]) staticField(spectator ? "spectatorLaneFlashes" : "laneFlashes");
        int[][] bursts = (int[][]) staticField(spectator ? "spectatorHitBursts" : "hitBursts");
        Object timed = staticField(spectator ? "spectatorTimedLaneFlashes" : "timedLaneFlashes");
        flushPendingNoteActions.invoke(null, notes, tick, flashes, bursts, spectator);
        lastFlushTick[spectator ? 1 : 0] = tick;
        flushPendingRemoteStates.invoke(null, notes, spectator);
        sanitizeAnimationHeldMasks.invoke(null, notes,
                staticField(spectator ? "spectatorAnimationHeldMask" : "animationHeldMask"), tick);
        for (int owner = 0; owner < 2; owner++) {
            for (int lane = 0; lane < 4; lane++) {
                if ((boolean) timedTimed.invoke(timed, owner, lane)) {
                    continue;
                }
                if (flashes[owner][lane] > 0) flashes[owner][lane]--;
                if (bursts[owner][lane] > 0) bursts[owner][lane]--;
            }
        }
        timedApply.invoke(timed, clock, flashes, bursts);
    }

    static boolean mobSide(Scenario s, int side) {
        return side == 1 || s.twoMobs();
    }

    /** One's own broken hold, on a clean panel: the flash ClientBattleController#breakOwnComboForMistake gives it. */
    static int ownBrokenHoldFlash() throws Exception {
        resetClient(new UUID(1L, 1L), List.of(), true);
        breakOwnComboForMistake.invoke(null, 2);
        int flash = ((int[][]) staticField("laneFlashes"))[0][2];
        check(((int[][]) staticField("hitBursts"))[0][2] == 0, "one's own broken hold starts no burst");
        return flash;
    }

    static Object serverBattle(Scenario s, UUID id, List<int[]> chart) throws Exception {
        Object battle = create(battleType);
        set(battle, "id", id);
        List<Object> notes = new ArrayList<>();
        Constructor<?> constructor = noteType.getDeclaredConstructor(int.class, BattleNote.class);
        constructor.setAccessible(true);
        for (int[] note : chart) {
            notes.add(constructor.newInstance(notes.size(), new BattleNote(note[0], note[1], note[2], note[3])));
        }
        set(battle, "runtimeNotes", notes);
        set(battle, "random", new Random(s.seed() * 31L + s.skill().hashCode()));
        Field skill = skillType.getDeclaredField(s.skill());
        skill.setAccessible(true);
        set(battle, "mobSkill", skill.get(null));
        set(battle, "leftState", side(0, s.twoMobs()));
        set(battle, "rightState", side(1, true));
        // The isolated battle has no field initialisers: its mob's clock starts unset, as a real battle's does.
        set(battle, "aiTicks", Integer.MIN_VALUE);
        leadRecord(battle);
        return battle;
    }

    /** What a real battle starts with for a mob's actions ahead of it (Battle#leadActions), which the isolated one lacks. */
    static void leadRecord(Object battle) throws Exception {
        set(battle, "leadActions", new java.util.ArrayDeque<BattleActionPayload>());
        set(battle, "dueScore", new int[2]);
        set(battle, "dueCombo", new int[2]);
    }

    static Object side(int owner, boolean ai) throws Exception {
        Object state = create(stateType);
        set(state, "owner", owner);
        set(state, "ai", ai);
        set(state, "speed", 1.0F);
        set(state, "laneDown", new boolean[4]);
        set(state, "laneHitLockoutUntil", new int[4]);
        set(state, "activeHolds", Array.newInstance(noteType, 4));
        return state;
    }

    static void resetClient(UUID id, List<int[]> chart, boolean player) throws Exception {
        setStatic("active", player);
        setStatic("battleId", player ? id : null);
        setStatic("ownOwner", 0);
        setStatic("leftEntityId", 0);
        setStatic("rightEntityId", 0);
        setStatic("localTicks", -40);
        setStatic("leftScore", 0);
        setStatic("rightScore", 0);
        setStatic("opponentCombo", 0);
        setStatic("ownCombo", 0);
        setStatic("ownMistakes", 0);
        setStatic("spectatorBattleId", id);
        setStatic("spectatorLeftEntityId", 0);
        setStatic("spectatorRightEntityId", 0);
        setStatic("spectatorLeftScore", 0);
        setStatic("spectatorRightScore", 0);
        setStatic("spectatorLocalTicks", -40);
        setStatic("spectatorMusicClockStartTick", 0);
        setStatic("spectatorMusicAwaitingStart", false);
        Arrays.fill((int[]) staticField("spectatorCombo"), 0);
        Arrays.fill(lastFlushTick, Integer.MIN_VALUE);
        // A new battle starts with nobody holding anything (resetAnimationLayers).
        for (String masks : new String[]{"animationHeldMask", "animationInputHeldMask", "spectatorAnimationHeldMask",
                "spectatorAnimationInputHeldMask"}) {
            Arrays.fill((int[]) staticField(masks), 0);
        }
        for (boolean spectator : new boolean[]{false, true}) {
            heldList(spectator).clear();
            timedClear.invoke(staticField(spectator ? "spectatorTimedLaneFlashes" : "timedLaneFlashes"));
            for (String name : spectator ? new String[]{"spectatorLaneFlashes", "spectatorHitBursts"}
                    : new String[]{"laneFlashes", "hitBursts"}) {
                for (int[] lanes : (int[][]) staticField(name)) {
                    Arrays.fill(lanes, 0);
                }
            }
            fillNotes(spectator, chart);
        }
    }

    /** Puts {@code chart}'s notes, fresh, on the player's or the spectator's panel. */
    static void fillNotes(boolean spectator, List<int[]> chart) throws Exception {
        @SuppressWarnings("unchecked")
        List<Object> notes = (List<Object>) clientNotes(spectator);
        notes.clear();
        Constructor<?> constructor = clientNoteType.getDeclaredConstructor(int.class, int.class, int.class, int.class, int.class);
        constructor.setAccessible(true);
        for (int i = 0; i < chart.size(); i++) {
            int[] note = chart.get(i);
            notes.add(constructor.newInstance(i, note[0], note[1], note[2], note[3]));
        }
    }

    /**
     * The battle as the server sends it to a player who starts watching on {@code startTick}: the real
     * Battle#spectatorJoin, with the chart, in the SpectatorBattlePayload updateSpectators makes of it.
     */
    static SpectatorBattlePayload joinPayload(UUID id, Object battle, List<int[]> chart, int startTick) throws Exception {
        Object join = spectatorJoin.invoke(battle);
        List<BattleNote> notes = new ArrayList<>();
        for (int[] note : chart) {
            notes.add(new BattleNote(note[0], note[1], note[2], note[3]));
        }
        @SuppressWarnings("unchecked")
        List<Integer> settledNotes = (List<Integer>) joinPart(join, "settledNotes");
        @SuppressWarnings("unchecked")
        List<Integer> startedNotes = (List<Integer>) joinPart(join, "startedNotes");
        @SuppressWarnings("unchecked")
        List<BattleActionPayload> leadActions = (List<BattleActionPayload>) joinPart(join, "leadActions");
        return new SpectatorBattlePayload(id, 1000, startTick, 120, 1.0F, 0, 0, 0, 0.0F, 0.0F, 70.0F, 0, 0,
                1.0F, 1.0F, 1.0F, 1.0F, 1.0F, 1.0F, 1.0F, 1.0F, 1.0F, 1.0F, 1.0F, 1.0F, 1.0F, 1.0F,
                (int) joinPart(join, "leftScore"), (int) joinPart(join, "rightScore"), (int) joinPart(join, "leftCombo"),
                (int) joinPart(join, "rightCombo"), (int) joinPart(join, "leftHeldMask"),
                (int) joinPart(join, "rightHeldMask"), notes, List.of(), NoteAppearance.solid(0),
                NoteAppearance.solid(0), settledNotes, startedNotes, leadActions, 0);
    }

    static Object joinPart(Object join, String name) throws Exception {
        Method accessor = join.getClass().getDeclaredMethod(name);
        accessor.setAccessible(true);
        return accessor.invoke(join);
    }

    /**
     * The spectator's panel takes the battle as ClientBattleController#startSpectatorBattle does for a battle it was not
     * watching (takeSpectatorPanel; the rest of that method reaches for the game), with its clock on the join's tick as
     * it arrives, as a new spectator's clock starts there. From then on the screen is read as one that watched from the
     * start, against the actions due by the join's tick, which the join itself stands for: the actions it brings ahead
     * of the battle are the mob's held ones, every action made before it, and none of them punches or lights anything
     * as the join comes.
     */
    static void joinMidSong(Scenario s, String label, Screen screen, SpectatorBattlePayload join, float clock,
                            List<int[]> chart, List<List<BattleActionPayload>> made, int[] arrived, int[] madeByJoin,
                            int[] settling, boolean[] held) throws Exception {
        String where = label + ", spectator joining at " + fmt(clock);
        screen.beforeShow();
        setStatic("spectatorBattleId", join.battleId());
        setStatic("spectatorLocalTicks", join.startTick());
        // The song is seeked to the join's tick and the clock counts from there; this one's epoch has been moved to
        // stand for both (spectatorClock counts from tick zero).
        setStatic("spectatorMusicClockStartTick", 0);
        takeSpectatorPanel.invoke(null, join);
        for (int side = 0; side < 2; side++) {
            List<BattleActionPayload> actions = made.get(side);
            int due = 0;
            while (due < arrived[side] && actions.get(due).showAtTick() <= join.startTick()) {
                due++;
            }
            check(arrived[side] == madeByJoin[side], where + ": side " + side + "'s " + madeByJoin[side] + " actions made"
                    + " before the join all came before it, and none after it: " + arrived[side]);
            List<BattleActionPayload> ahead = new ArrayList<>();
            for (BattleActionPayload lead : join.leadActions()) {
                if (lead.owner() == side) {
                    ahead.add(lead);
                }
            }
            List<BattleActionPayload> expected = mobSide(s, side) ? actions.subList(due, arrived[side]) : List.of();
            check(ahead.size() == expected.size() && java.util.stream.IntStream.range(0, ahead.size())
                            .allMatch(k -> ahead.get(k) == expected.get(k)), where + ": the join brings side " + side + " "
                    + ahead.size() + " actions ahead of the battle, which has made " + expected.size() + " past "
                    + join.startTick());
            screen.due[side] = due;
            screen.dueAtLastFrame[side] = due;
            BattleActionPayload last = due == 0 ? null : actions.get(due - 1);
            screen.shownCombo[side] = last == null ? 0 : last.combo();
            screen.shownScore[side] = last == null ? 0 : last.score();
        }
        for (BattleActionPayload lead : join.leadActions()) {
            screen.arrivedAt.put(lead, clock);
        }
        for (int i = 0; i < chart.size(); i++) {
            int side = chart.get(i)[0];
            // Settled before the join: read gone like any other (afterShow), but it went before this panel had it.
            screen.settledBeforeJoin[i] = settling[i] < screen.due[side];
        }
        screen.joined = true;
        lateJoins++;
        joinSettledNotes += join.settledNotes().size();
        joinHeldNotes += join.startedNotes().size();
        joinLeadActions += join.leadActions().size();
        check(Arrays.equals((int[]) staticField("spectatorAnimationInputHeldMask"), new int[2])
                        || !s.twoMobs() && ((int[]) staticField("spectatorAnimationInputHeldMask"))[1] == 0,
                where + ": a mob's side comes with no keys held, " + Arrays.toString(
                        (int[]) staticField("spectatorAnimationInputHeldMask")));
        screen.afterShow(s, label, clock, chart, made, arrived, settling, held);
    }

    /** Sets the player's battle clock to wall time {@code t}, held there as MobHitTimingChecks holds it, and returns it. */
    static float playerClock(double epochMs, boolean paused, double pausedAtMs, double t) throws Exception {
        long epoch = nanos(epochMs);
        long now = nanos(paused ? pausedAtMs : t);
        setStatic("startAtNanos", epoch);
        setStatic("musicPausedAtNanos", now);
        return (now - epoch) / 50_000_000.0F;
    }

    /** The same for the spectator's clock, which follows its song from tick zero (spectatorVisualTick). */
    static float spectatorClock(double epochMs, boolean paused, double pausedAtMs, double t) throws Exception {
        long epoch = nanos(epochMs);
        long now = nanos(paused ? pausedAtMs : t);
        setStatic("spectatorMusicStartedAtNanos", epoch);
        setStatic("spectatorMusicPausedAtNanos", now);
        return Math.max(0L, now - epoch) / 50_000_000.0F;
    }

    static void bind() throws Exception {
        battleType = Class.forName(MANAGER.replace('/', '.') + "$Battle");
        stateType = Class.forName(MANAGER.replace('/', '.') + "$ParticipantState");
        noteType = Class.forName(MANAGER.replace('/', '.') + "$RuntimeNote");
        skillType = Class.forName(MANAGER.replace('/', '.') + "$MobSkill");
        tickParticipants = battleType.getDeclaredMethod("tickParticipants");
        tickParticipants.setAccessible(true);
        controller = Class.forName("ru.gora.musicbattle.client.ClientBattleController");
        clientNoteType = Class.forName("ru.gora.musicbattle.client.ClientBattleController$ClientNote");
        acceptAction = controller.getDeclaredMethod("acceptAction", BattleActionPayload.class);
        beginRenderFrame = controller.getDeclaredMethod("beginRenderFrame");
        showDueActions = method("showDueActions", List.class, boolean.class, float.class);
        flushPendingNoteActions = method("flushPendingNoteActions", List.class, int.class, int[][].class, int[][].class,
                boolean.class);
        flushPendingRemoteStates = method("flushPendingRemoteStates", List.class, boolean.class);
        breakOwnComboForMistake = method("breakOwnComboForMistake", int.class);
        sanitizeAnimationHeldMasks = method("sanitizeAnimationHeldMasks", List.class, int[].class, int.class);
        sendLaneState = method("sendLaneState", int.class, boolean.class);
        resolveLocalMissedNotes = method("resolveLocalMissedNotes");
        resolveLocalCompletedHolds = method("resolveLocalCompletedHolds");
        takeSpectatorPanel = method("takeSpectatorPanel", SpectatorBattlePayload.class);
        spectatorJoin = battleType.getDeclaredMethod("spectatorJoin");
        spectatorJoin.setAccessible(true);
        Class<?> manager = Class.forName(MANAGER.replace('/', '.'));
        judgeClient = manager.getDeclaredMethod("judgeClient", battleType, int.class, ClientJudgementPayload.class,
                UUID.class, String.class);
        judgeClient.setAccessible(true);
        laneInput = manager.getDeclaredMethod("laneInput", battleType, int.class, int.class, int.class, boolean.class);
        laneInput.setAccessible(true);
        // One's own keys are judged against the calibrated clock; the settings stay out of the game's config folder.
        Class<?> settings = Class.forName("ru.gora.musicbattle.client.ClientBattleSettings");
        for (String[] value : new String[][]{{"loaded", "true"}, {"inputOffsetMs", "0"}}) {
            Field field = settings.getDeclaredField(value[0]);
            field.setAccessible(true);
            field.set(null, value[0].equals("loaded") ? (Object) Boolean.TRUE : (Object) 0);
        }
        Class<?> timedType = staticField("timedLaneFlashes").getClass();
        timedApply = timedType.getDeclaredMethod("apply", float.class, int[][].class, int[][].class);
        timedApply.setAccessible(true);
        timedClear = timedType.getDeclaredMethod("clear");
        timedClear.setAccessible(true);
        timedTimed = timedType.getDeclaredMethod("timed", int.class, int.class);
        timedTimed.setAccessible(true);
        timedStartedAt = timedType.getDeclaredField("startedAt");
        timedStartedAt.setAccessible(true);
        timedFlashTicks = timedType.getDeclaredField("flashTicks");
        timedFlashTicks.setAccessible(true);
        burstTicks = staticInt(timedType, "BURST_TICKS");
    }

    /**
     * What an action shows on another screen, by ClientBattleController#shouldFlashAction and #breaksStartedHold, read
     * before {@code held} takes the action in: a PRESS (a bad press, a tap hit, a hold's head hit) flashes, bursts and
     * punches; a BREAK (a miss of a hold whose head was hit) flashes as one's own broken hold; a MISS (of a tap, or of a
     * hold at its head) leaves the lane alone; anything else (a hold's later ticks and its end) does nothing to it.
     */
    static int kind(BattleActionPayload payload, List<int[]> chart, boolean[] held) {
        int note = payload.noteIndex();
        if (note < 0) {
            return PRESS;
        }
        if (payload.hit()) {
            return chart.get(note)[3] <= 0 || !payload.complete() && !held[note] ? PRESS : OTHER;
        }
        if (!payload.complete()) {
            return OTHER;
        }
        return held[note] ? BREAK : MISS;
    }

    /** A side's punches on a screen so far: its presses and its judgements (ClientBattleController#trigger). */
    static int punches(boolean spectator, int owner) throws Exception {
        return ((int[]) staticField(spectator ? "spectatorAnimationTriggerSerial" : "animationTriggerSerial"))[owner]
                + ((int[]) staticField(spectator ? "spectatorAnimationJudgedSerial" : "animationJudgedSerial"))[owner];
    }

    /** Called by the isolated broadcastAction with each payload it makes, where it would have gone to the watchers. */
    public static void sent(Object payload) {
        sentNow.add((BattleActionPayload) payload);
    }

    /**
     * The battle classes with only what the mob's part of a battle needs. broadcastAction keeps its own code: the list
     * of watchers is empty, each payload is handed to {@link #sent} as it is made, and the armour-stand status windows
     * (an entity's name, a stand that is not there) are skipped.
     */
    static void isolate(Path compiled, Path output) throws Exception {
        transform(compiled, output, MANAGER, Set.of("resolveInput", "canHitClaimedNote", "shouldBreakComboForBadPress",
                        "earlyHitWindowTicks", "timingScore", "animateParticipant", "broadcastAction", "judgeClient",
                        "laneInput", "resolveHoldRelease", "canAcceptClientHit", "canAcceptClientHold", "namesNoteOnPanel",
                        "heldMask"),
                Set.of("AI_LEAD_TICKS", "MISS_AFTER_PANEL_EXIT_TICKS"), false);
        transform(compiled, output, MANAGER + "$Battle", Set.of("tickMobAi", "tickHold", "resolveMissedNotes", "state",
                        "noteByIndex", "noteByTiming", "clockOf", "settled", "hasActivePauseSkipBlocker", "tickParticipants",
                        "tickMobSide", "tickPlayerSide", "recordLeadAction", "settleLeadActions", "heldAt", "spectatorJoin"),
                Set.of("id", "runtimeNotes", "random", "mobSkill", "leftState", "rightState", "playTicks", "aiTicks",
                        "durationTicks", "noteIndexSkewWarned", "leftStatus", "rightStatus", "leftAnchor", "rightAnchor",
                        "leftEntity", "rightEntity", "leadActions", "dueScore", "dueCombo"), true);
        transform(compiled, output, MANAGER + "$ParticipantState", Set.of(),
                Set.of("owner", "ai", "speed", "laneDown", "laneHitLockoutUntil", "activeHolds", "score", "combo",
                        "maxCombo", "mistakes", "bopTicks", "lastClientJudgementSequence"), true);
    }

    static void transform(Path compiled, Path output, String name, Set<String> methods, Set<String> fields,
                          boolean constructor) throws Exception {
        ClassReader reader = new ClassReader(Files.readAllBytes(compiled.resolve(name + ".class")));
        ClassWriter writer = new ClassWriter(0);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public FieldVisitor visitField(int access, String field, String descriptor, String signature, Object value) {
                return fields.contains(field) ? super.visitField(access & ~Opcodes.ACC_FINAL, field, descriptor, signature, value) : null;
            }

            @Override
            public MethodVisitor visitMethod(int access, String method, String descriptor, String signature, String[] exceptions) {
                if (!methods.contains(method)) return null;
                return new MethodVisitor(Opcodes.ASM9, super.visitMethod(access, method, descriptor, signature, exceptions)) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String target, String desc, boolean iface) {
                        if (owner.equals(MANAGER + "$Battle") && target.equals("watchers")) {
                            super.visitInsn(Opcodes.POP);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/Collections", "emptyList",
                                    "()Ljava/util/List;", false);
                        } else if (owner.equals(MANAGER) && target.equals("displayName")) {
                            super.visitInsn(Opcodes.POP);
                            super.visitLdcInsn("");
                        } else if (owner.equals(MANAGER) && target.equals("updateStatusWindow")) {
                            for (org.objectweb.asm.Type argument : org.objectweb.asm.Type.getArgumentTypes(desc)) {
                                super.visitInsn(argument.getSize() == 2 ? Opcodes.POP2 : Opcodes.POP);
                            }
                        } else {
                            super.visitMethodInsn(opcode, owner, target, desc, iface);
                            if (opcode == Opcodes.INVOKESPECIAL && target.equals("<init>")
                                    && owner.equals("ru/gora/musicbattle/network/BattleActionPayload")) {
                                super.visitInsn(Opcodes.DUP);
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, "MobMissChecks", "sent",
                                        "(Ljava/lang/Object;)V", false);
                            }
                        }
                    }
                };
            }

            @Override
            public void visitEnd() {
                if (constructor) {
                    MethodVisitor init = super.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
                    init.visitCode();
                    init.visitVarInsn(Opcodes.ALOAD, 0);
                    init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
                    init.visitInsn(Opcodes.RETURN);
                    init.visitMaxs(1, 1);
                    init.visitEnd();
                }
                super.visitEnd();
            }
        }, 0);
        Path target = output.resolve(name + ".class");
        Files.createDirectories(target.getParent());
        Files.write(target, writer.toByteArray());
    }

    static Method method(String name, Class<?>... types) throws Exception {
        Method method = controller.getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method;
    }

    static Field controllerField(String name) throws Exception {
        Field field = FIELDS.get(name);
        if (field == null) {
            field = controller.getDeclaredField(name);
            field.setAccessible(true);
            FIELDS.put(name, field);
        }
        return field;
    }

    static Object staticField(String name) throws Exception {
        return controllerField(name).get(null);
    }

    static int staticInt(String name) throws Exception {
        return controllerField(name).getInt(null);
    }

    static void setStatic(String name, Object value) throws Exception {
        controllerField(name).set(null, value);
    }

    static int staticInt(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field.getInt(null);
    }

    static List<?> heldList(boolean spectator) throws Exception {
        return (List<?>) staticField(spectator ? "spectatorHeldActions" : "heldActions");
    }

    static List<?> clientNotes(boolean spectator) throws Exception {
        return (List<?>) staticField(spectator ? "spectatorNotes" : "notes");
    }

    static final Map<String, Field> NOTE_FIELDS = new HashMap<>();

    static boolean noteFlag(Object note, String name) throws Exception {
        return noteField(name).getBoolean(note);
    }

    static int noteInt(Object note, String name) throws Exception {
        return noteField(name).getInt(note);
    }

    static Field noteField(String name) throws Exception {
        Field field = NOTE_FIELDS.get(name);
        if (field == null) {
            field = clientNoteType.getDeclaredField(name);
            field.setAccessible(true);
            NOTE_FIELDS.put(name, field);
        }
        return field;
    }

    static Object create(Class<?> type) throws Exception {
        Constructor<?> constructor = type.getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }

    static Object get(Object object, String name) throws Exception {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }

    static boolean flag(Object object, String name) throws Exception {
        return (boolean) get(object, name);
    }

    static void set(Object object, String name, Object value) throws Exception {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(object, value);
    }

    static long nanos(double ms) {
        return Math.round(ms * 1_000_000.0D);
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

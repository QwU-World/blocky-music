import ru.gora.musicbattle.data.BattleCameraFocus;
import ru.gora.musicbattle.data.BattleClock;
import ru.gora.musicbattle.data.BattlePauseClock;
import ru.gora.musicbattle.data.BattleSkipClock;

/**
 * The pause-skip clock and the battle camera's lean, run as the server and client run them, without a game.
 *
 * <p>The skip half reproduces the reported failure first: both sides read the tick from an epoch, so a skip that only
 * set the counter is undone by the next reading. Then it checks that moving the epoch keeps the jump, and that a
 * client hearing of it late lands on the server's tick rather than behind it.
 *
 * <p>The pause half does the same for a single-player pause menu: the integrated server stops ticking while the client
 * holds its chart back, so the server's battle clock has to stand still too, count-in included, and resume on the tick
 * the client resumes on.
 */
public final class BattleFeelChecks {
    private static final long TICK = 50_000_000L;
    private static int failures;

    public static void main(String[] args) {
        skipSurvivesTheNextTick();
        clientMatchesServerAfterSkip();
        cameraLeansTowardsWhoeverPlays();
        sharedNotesKeepTheCentredShot();
        pauseMenuStopsBothClocks();
        pauseDuringCountInKeepsTheLead();
        unpausedGamesSeeWallTime();
        if (failures > 0) {
            System.err.println(failures + " battle feel check(s) failed.");
            System.exit(1);
        }
        System.out.println("Battle feel checks passed.");
    }

    private static void skipSurvivesTheNextTick() {
        long epoch = 1_000_000_000_000L;
        long now = epoch + 400 * TICK + 17_000_000L;
        int playTicks = BattleClock.ticksSince(epoch, now);
        check(playTicks == 400, "the server is at tick 400 when the vote passes");
        int target = 940;

        // What the server did: write the counter, keep the epoch. One tick later it reads the epoch again.
        long oldEpoch = epoch;
        int oldNext = BattleClock.ticksSince(oldEpoch, now + TICK);
        check(oldNext == 401, "reproduced: without moving the epoch the skip is gone a tick later (" + oldNext + ")");

        long newEpoch = BattleSkipClock.serverEpochAfterSkip(epoch, playTicks, target);
        check(BattleClock.ticksSince(newEpoch, now) == target, "moving the epoch puts the clock on the target now");
        check(BattleClock.ticksSince(newEpoch, now + TICK) == target + 1, "and it stays there on the next tick");
        check(BattleClock.ticksSince(newEpoch, now + 600 * TICK) == target + 600, "and for the rest of the song");
        check(BattleSkipClock.serverEpochAfterSkip(epoch, 500, 400) == epoch, "a skip never moves the clock backwards");
    }

    private static void clientMatchesServerAfterSkip() {
        // The server and the client agree on the instant the battle began; their nanoTime values differ.
        long serverEpoch = 5_000_000_000_000L;
        long clientOffset = -3_210_987_654_321L;
        long clientEpoch = serverEpoch + clientOffset;
        long serverNow = serverEpoch + 1200 * TICK + 31_000_000L;
        int from = BattleClock.ticksSince(serverEpoch, serverNow);
        int target = 1800;
        long serverEpochAfter = BattleSkipClock.serverEpochAfterSkip(serverEpoch, from, target);

        for (int roundTrip : new int[]{0, 40, 120, 300}) {
            int oneWay = BattleClock.oneWayMillis(roundTrip);
            long arrivesServerTime = serverNow + oneWay * 1_000_000L;
            long clientNow = arrivesServerTime + clientOffset;

            // What the client did: set its counter, keep the epoch; the next tick reads the epoch again.
            int oldClient = BattleClock.ticksSince(clientEpoch, clientNow + TICK);
            check(oldClient < target, "reproduced: the client fell back into the rest at " + roundTrip + " ms");

            // The client moves its epoch by the ticks the server jumped, however late the news arrives; the sub-tick
            // arithmetic of the old rebuild is in tools/BattleSkipSyncChecks.java.
            long clientEpochAfter = BattleSkipClock.clientEpochAfterJump(clientEpoch, from, target);
            for (int later : new int[]{1, 20, 400}) {
                int client = BattleClock.ticksSince(clientEpochAfter, clientNow + later * TICK);
                int server = BattleClock.ticksSince(serverEpochAfter, arrivesServerTime + later * TICK);
                check(client == server,
                        "client " + client + " and server " + server + " agree after the skip at " + roundTrip + " ms");
            }
        }
    }

    private static void cameraLeansTowardsWhoeverPlays() {
        BattleCameraFocus focus = new BattleCameraFocus();
        float previous = 0.0F;
        float previousStep = 0.0F;
        int ticksToArrive = -1;
        for (int tick = 0; tick < 200; tick++) {
            focus.tick(true, false, true);
            float step = focus.focus() - previous;
            check(Math.abs(step) <= BattleCameraFocus.MAX_SPEED + 1.0E-6F, "the lean never moves faster than its limit");
            check(Math.abs(step - previousStep) <= 0.012F, "and never changes speed with a jolt (tick " + tick + ")");
            previous = focus.focus();
            previousStep = step;
            if (ticksToArrive < 0 && focus.focus() > 0.98F) {
                ticksToArrive = tick;
            }
        }
        check(focus.focus() == 1.0F, "a right-hand phrase ends with the shot on the right fighter");
        check(ticksToArrive >= 10 && ticksToArrive <= 60, "it arrives in under three seconds, not at once: " + ticksToArrive);

        int swing = 0;
        while (focus.focus() > -0.98F && swing < 400) {
            focus.tick(true, true, false);
            swing++;
        }
        check(swing >= 40, "a full swing to the other side takes at least two seconds: " + swing);

        for (int tick = 0; tick < BattleCameraFocus.HOLD_TICKS; tick++) {
            focus.tick(true, false, false);
        }
        check(focus.focus() < -0.95F, "a short rest between call and answer keeps the lean");
        for (int tick = 0; tick < 200; tick++) {
            focus.tick(true, false, false);
        }
        check(focus.focus() == 0.0F, "a long rest brings the shot back to the middle");
        for (int tick = 0; tick < 200; tick++) {
            focus.tick(true, true, true);
        }
        check(focus.focus() == 0.0F, "both playing at once is the centred shot");

        check(BattleCameraFocus.keepsSideOnCamera(100, 0, 100 - BattleCameraFocus.LOOKAHEAD_TICKS),
                "the camera starts turning before the first note");
        check(!BattleCameraFocus.keepsSideOnCamera(100, 10, 100 + 10 + BattleCameraFocus.TRAIL_TICKS + 1),
                "and lets the phrase go shortly after its last note");
    }

    private static void sharedNotesKeepTheCentredShot() {
        BattleCameraFocus focus = new BattleCameraFocus();
        for (int tick = 0; tick < 100; tick++) {
            focus.tick(false, tick % 2 == 0, true);
            check(focus.focus() == 0.0F, "shared notes and solo never lean");
        }
    }

    private static void pauseMenuStopsBothClocks() {
        BattlePauseClock clock = new BattlePauseClock();
        long wall = 5_000_000_000_000L;
        long serverEpoch = clock.now(wall);
        long clientEpoch = wall;
        long pauseAt = wall + 300 * TICK + 20_000_000L;
        long pausedFor = 30_000_000_000L;
        long resumeAt = pauseAt + pausedFor;

        // What the server did: read the tick from the wall clock, so it comes out of the menu 600 ticks on.
        int oldServer = BattleClock.ticksSince(wall, resumeAt + TICK);
        check(oldServer == 901, "reproduced: a wall-clock server jumps the whole pause (" + oldServer + ")");

        clock.observe(true, pauseAt);
        check(BattleClock.ticksSince(serverEpoch, clock.now(pauseAt + 10 * TICK)) == 300, "the battle clock stands still while paused");
        // The server may tick in the instant after the menu closes, before the client notes the resume: still frozen.
        check(BattleClock.ticksSince(serverEpoch, clock.now(resumeAt)) == 300, "no jump before the client notes the resume");
        clock.observe(false, resumeAt);
        // The client moves its own origin by the same pause (ClientBattleController.tick).
        clientEpoch += resumeAt - pauseAt;
        for (int step = 0; step <= 200; step++) {
            long now = resumeAt + step * TICK + 7_000_000L;
            int server = BattleClock.ticksSince(serverEpoch, clock.now(now));
            int client = BattleClock.ticksSince(clientEpoch, now);
            check(server == client, "server and client agree after the pause at step " + step + " (" + server + " vs " + client + ")");
        }
        check(BattleClock.ticksSince(serverEpoch, clock.now(resumeAt + TICK)) == 301, "the battle resumes on the next tick");
    }

    private static void pauseDuringCountInKeepsTheLead() {
        BattlePauseClock clock = new BattlePauseClock();
        long wall = 7_000_000_000_000L;
        long epoch = clock.now(wall) + 3_000_000_000L;
        clock.observe(true, wall + 1_000_000_000L);
        clock.observe(false, wall + 61_000_000_000L);
        int tick = BattleClock.ticksSince(epoch, clock.now(wall + 61_000_000_000L));
        check(tick == -40, "a pause during the count-in leaves the same two seconds to go (" + tick + ")");
    }

    private static void unpausedGamesSeeWallTime() {
        BattlePauseClock clock = new BattlePauseClock();
        long wall = 9_000_000_000_000L;
        clock.observe(false, wall);
        check(clock.now(wall + 123L) == wall + 123L, "a game that never pauses, a dedicated server's, reads wall time");
        clock.observe(true, wall);
        clock.observe(true, wall + 5 * TICK);
        clock.observe(false, wall + 10 * TICK);
        clock.observe(false, wall + 20 * TICK);
        check(clock.now(wall + 20 * TICK) == wall + 10 * TICK, "repeated reports of the same state count once");
    }

    private static void check(boolean value, String message) {
        if (!value) {
            failures++;
            System.err.println("FAIL: " + message);
        }
    }
}

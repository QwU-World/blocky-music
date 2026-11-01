import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import ru.gora.musicbattle.data.BattlePauseClock;
import ru.gora.musicbattle.data.MusicStreamRetry;
import ru.gora.musicbattle.data.SongDriftAnchor;

/**
 * The battle song kept where it began against the chart through visits to the single-player pause menu.
 *
 * <p>Measured first, in a hidden game on OpenAL Soft's null device with a probe on the real source position: each visit
 * moved the song from -27 to +18 ms against the chart, and the visits added up (24.8 ms off before a pause, 13 ms at the
 * end of 20 visits), because OpenAL pauses and resumes a source at its next mixing step. Here:
 * <ul>
 *   <li>SongDriftAnchor on a model of what the client reads: a song whose offset from the clock jumps by -27..+18 ms at
 *       each visit, read once a client tick (50 ms, jittered) through a mixer that advances in 21.33 ms steps, with the
 *       starved-mixer stalls and the first readings after a resume reading 12-25 ms ahead that the hidden run showed.
 *       The clock is moved by every correction the anchor asks for. Before
 *       each next visit 2.5 s or more later the song is within 8 ms of where it began, within 5 ms in 98.5% of them and
 *       always from 4.5 s on, and within 4.5 ms 20 s after the last; on a steady song it moves the clock seldom (under
 *       twice in ten minutes) and never more than 4 ms, and with 4 or 8 ms of jitter on top of the mixer's steps (more
 *       than the hidden run had) it does not chase the noise (the noise gate); a stall is no drift; a new stream has its
 *       own reference, and a song 300 ms off is left to the drift check's seek;</li>
 *   <li>BattlePauseClock's short hold and step forward: the battle time stands still and goes on by itself, never back,
 *       a pause during a hold is a pause from the hold's start, and nothing moves it during a pause;</li>
 *   <li>the real ClientBattleController's moves of its clock (moveClockForSong, finishClockHold, activeVisualTick)
 *       against the same BattlePauseClock the integrated server reads: both clocks move together;</li>
 *   <li>the same for the spectator clock of a watched battle or DJ booth (moveSpectatorClockForSong,
 *       finishSpectatorClockHold, spectatorVisualTick, stopSpectatorMusic): a watched battle's server time moves with it,
 *       a DJ booth's does not, and a stopped song ends a hold on both.</li>
 * </ul>
 * NOT COVERED: OpenAL itself (the hidden run measures it).
 */
public final class SongDriftChecks {
    static int checks;
    static final List<String> failures = new ArrayList<>();
    static final double STEP = 0.02133D;

    public static void main(String[] args) throws Exception {
        anchorThroughVisits();
        anchorSteady();
        anchorJitter();
        anchorStall();
        anchorNewStream();
        pauseClockHold();
        controllerMovesClock();
        spectatorMovesClock();
        refusedSongComesBack();
        if (!failures.isEmpty()) {
            System.err.println(String.join("\n", failures.subList(0, Math.min(30, failures.size()))));
            throw new AssertionError(failures.size() + " song drift checks failed");
        }
        System.out.println("Song drift checks passed: " + checks);
    }

    /** Mixer position steps: a reading lags the true offset by where the read falls inside the mixer's step. */
    static double reading(double trueOffset, double t, double phase) {
        return trueOffset - ((t + phase) % STEP);
    }

    static void anchorThroughVisits() {
        Random random = new Random(91L);
        double worst = 0.0D;
        double worstEnd = 0.0D;
        double worstLong = 0.0D;
        int segments = 0;
        int within = 0;
        long corrections = 0;
        for (int run = 0; run < 2000; run++) {
            SongDriftAnchor anchor = new SongDriftAnchor();
            Object stream = new Object();
            double phase = random.nextDouble() * STEP;
            double offset = (random.nextDouble() - 0.5D) * 0.06D;
            double start = offset;
            double t = 0.0D;
            double reference = Double.NaN;
            // The first stretch, before any visit: where the start put the song.
            double firstStretch = 3.0D + random.nextDouble() * 2.0D;
            for (int visit = 0; visit <= 20; visit++) {
                double stretch = visit == 0 ? firstStretch : visit == 20 ? 20.0D : 1.5D + random.nextDouble() * 3.0D;
                double end = t + stretch;
                boolean stall = random.nextDouble() < 0.15D;
                double stallAt = t + random.nextDouble() * stretch;
                double stallLength = 0.1D + random.nextDouble() * 0.25D;
                anchor.restart();
                // The first readings after the song resumes read ahead, 12 to 25 ms, as the hidden run showed.
                int ahead = visit == 0 ? 0 : 1 + random.nextInt(3);
                while (t < end) {
                    t += 0.05D + (random.nextDouble() - 0.5D) * 0.02D;
                    double lag = stall && t >= stallAt && t < stallAt + stallLength ? t - stallAt : 0.0D;
                    double lead = ahead-- > 0 ? 0.012D + random.nextDouble() * 0.013D : 0.0D;
                    double correction = anchor.add(stream, reading(offset - lag + lead, t, phase));
                    if (correction != 0.0D) {
                        corrections++;
                        offset -= correction;
                    }
                }
                if (visit == 0) {
                    reference = anchor.reference();
                    check(!Double.isNaN(reference) && Math.abs(reference - (start - STEP / 2.0D)) < 0.003D,
                            "run " + run + ": the reference is where the start put the song, " + ms(reference) + " for "
                                    + ms(start - STEP / 2.0D));
                }
                if (stretch >= 2.5D && visit > 0 && visit < 20) {
                    segments++;
                    double off = Math.abs(offset - start);
                    worst = Math.max(worst, off);
                    within += off <= 0.005D ? 1 : 0;
                    check(off <= 0.008D, "run " + run + ", visit " + visit + ": " + ms(offset - start) + " off the start "
                            + stretch + " s after the visit");
                    if (stretch >= 4.5D) {
                        worstLong = Math.max(worstLong, off);
                        check(off <= 0.005D, "run " + run + ", visit " + visit + ": " + ms(offset - start)
                                + " off the start " + stretch + " s after the visit, a full mean later");
                    }
                }
                if (visit == 20) {
                    worstEnd = Math.max(worstEnd, Math.abs(offset - start));
                    check(Math.abs(offset - start) <= 0.0045D, "run " + run + ": " + ms(offset - start)
                            + " off the start 20 s after the last visit");
                } else {
                    // The visit: the song stops and starts again at the mixer's steps, not at the clock's.
                    offset += -0.027D + random.nextDouble() * 0.045D;
                }
            }
        }
        check(within >= segments * 0.985D, "within 5 ms of the start before the next visit: " + within + " of " + segments);
        System.out.println(String.format(Locale.ROOT, "Through 20 visits, 2000 runs: %d stretches of 2.5 s or more, %.2f%%"
                        + " of them within 5 ms of the start before the next visit, worst %.2f ms; %.2f ms worst after"
                        + " 4.5 s or more; %.2f ms worst 20 s after the last; %d corrections", segments,
                100.0D * within / segments, worst * 1000, worstLong * 1000, worstEnd * 1000, corrections));
    }

    static void anchorSteady() {
        Random random = new Random(17L);
        long corrections = 0;
        double moved = 0.0D;
        double worst = 0.0D;
        for (int run = 0; run < 50; run++) {
            SongDriftAnchor anchor = new SongDriftAnchor();
            Object stream = new Object();
            double phase = random.nextDouble() * STEP;
            double offset = 0.01D;
            for (double t = 0.0D; t < 600.0D; t += 0.05D + (random.nextDouble() - 0.5D) * 0.02D) {
                double correction = anchor.add(stream, reading(offset, t, phase));
                if (correction != 0.0D) {
                    corrections++;
                    moved += Math.abs(correction);
                    offset -= correction;
                }
                if (!Double.isNaN(anchor.reference())) {
                    worst = Math.max(worst, Math.abs(offset - 0.01D));
                }
            }
        }
        double perTenMinutes = corrections / 50.0D;
        check(perTenMinutes <= 2.0D && worst <= 0.004D, "a steady song: " + perTenMinutes + " corrections in ten minutes,"
                + " the clock at most " + ms(worst) + " from where it was");
        System.out.println(String.format(Locale.ROOT, "A steady song: %.2f corrections in ten minutes, %.2f ms moved in"
                + " all, never more than %.2f ms off", perTenMinutes, moved * 1000 / 50.0D, worst * 1000));
    }

    /**
     * A mixer read with a lot of jitter on top of its steps (a busy sound thread): a mean of a few readings is no
     * measure then, and the anchor must wait for one that is, not chase the noise.
     */
    static void anchorJitter() {
        // {extra jitter, the most corrections in ten minutes, the furthest off}; without the noise gate: 21 and 60.
        double[][] cases = {{0.004D, 17.0D, 0.005D}, {0.008D, 45.0D, 0.008D}};
        for (double[] limits : cases) {
            Random random = new Random(29L);
            long corrections = 0;
            double worst = 0.0D;
            for (int run = 0; run < 50; run++) {
                SongDriftAnchor anchor = new SongDriftAnchor();
                Object stream = new Object();
                double phase = random.nextDouble() * STEP;
                double offset = 0.0D;
                for (double t = 0.0D; t < 600.0D; t += 0.05D + (random.nextDouble() - 0.5D) * 0.02D) {
                    double correction = anchor.add(stream, reading(offset, t, phase) + random.nextGaussian() * limits[0]);
                    if (correction != 0.0D) {
                        corrections++;
                        offset -= correction;
                    }
                    if (!Double.isNaN(anchor.reference())) {
                        worst = Math.max(worst, Math.abs(offset));
                    }
                }
            }
            double perTenMinutes = corrections / 50.0D;
            check(perTenMinutes <= limits[1] && worst <= limits[2], "a mixer read with " + ms(limits[0]) + " of jitter: "
                    + perTenMinutes + " corrections in ten minutes, the clock at most " + ms(worst) + " from where it was");
            System.out.println(String.format(Locale.ROOT, "A mixer read with %.0f ms of jitter on top of its steps: %.2f"
                    + " corrections in ten minutes, never more than %.2f ms off", limits[0] * 1000, perTenMinutes,
                    worst * 1000));
        }
    }

    static void anchorStall() {
        SongDriftAnchor anchor = new SongDriftAnchor();
        Object stream = new Object();
        double t = 0.0D;
        for (; t < 3.0D; t += 0.05D) {
            anchor.add(stream, reading(0.0D, t, 0.007D));
        }
        double moved = 0.0D;
        for (; t < 12.0D; t += 0.05D) {
            // A mixer starved for 300 ms every two seconds, catching up after.
            double sinceStall = t % 2.0D;
            double lag = sinceStall < 0.3D ? sinceStall : 0.0D;
            moved += anchor.add(stream, reading(-lag, t, 0.007D));
        }
        check(Math.abs(moved) <= 0.004D, "stalls of a starved mixer move the clock by " + ms(moved) + ", they are no drift");
        // Starved for its first 3.5 s (a world still loading as the song starts): the reference waits for a steady song.
        SongDriftAnchor starved = new SongDriftAnchor();
        moved = 0.0D;
        for (t = 0.0D; t < 12.0D; t += 0.05D) {
            double lag = t < 3.5D ? t : 0.0D;
            moved += starved.add(stream, reading(0.010D - lag, t, 0.007D));
        }
        check(Math.abs(starved.reference() - (0.010D - STEP / 2.0D)) < 0.003D && moved == 0.0D, "a mixer starved as the"
                + " song starts: the reference is " + ms(starved.reference()) + " for " + ms(0.010D - STEP / 2.0D)
                + ", moved " + ms(moved));
    }

    static void anchorNewStream() {
        SongDriftAnchor anchor = new SongDriftAnchor();
        Object first = new Object();
        Object second = new Object();
        double t = 0.0D;
        // Long enough for the first stream's reference to settle (a full window).
        for (; t < 5.5D; t += 0.05D) {
            anchor.add(first, reading(0.0D, t, 0.003D));
        }
        double moved = 0.0D;
        for (; t < 11.5D; t += 0.05D) {
            moved += anchor.add(second, reading(0.040D, t, 0.003D));
        }
        check(moved == 0.0D && Math.abs(anchor.reference() - (0.040D - STEP / 2.0D)) < 0.003D, "a new stream, 40 ms from"
                + " the last, is its own reference (" + ms(anchor.reference()) + ") and moves nothing (" + ms(moved) + ")");
        check(anchor.add(null, 0.5D) == 0.0D && anchor.add(second, Double.NaN) == 0.0D, "no stream or no number, nothing");
        SongDriftAnchor far = new SongDriftAnchor();
        for (t = 0.0D; t < 5.5D; t += 0.05D) {
            far.add(first, reading(0.0D, t, 0.003D));
        }
        moved = 0.0D;
        for (; t < 11.5D; t += 0.05D) {
            moved += far.add(first, reading(0.300D, t, 0.003D));
        }
        check(moved == 0.0D, "a song 300 ms off is for the drift check's seek, not a correction: " + ms(moved));
    }

    static void pauseClockHold() {
        BattlePauseClock clock = new BattlePauseClock();
        long ms = 1_000_000L;
        check(clock.now(1000 * ms) == 1000 * ms, "no pause, wall time");
        clock.holdBriefly(1000 * ms, 30 * ms);
        check(clock.holding() && clock.now(1010 * ms) == 1000 * ms && clock.now(1030 * ms) == 1000 * ms
                && clock.now(1040 * ms) == 1010 * ms, "a 30 ms hold stands still and goes on by itself: "
                + clock.now(1010 * ms) / ms + ", " + clock.now(1040 * ms) / ms);
        clock.observe(false, 1020 * ms);
        check(clock.now(1025 * ms) == 1000 * ms, "a client tick inside the hold does not end it");
        clock.observe(false, 1050 * ms);
        check(!clock.paused() && clock.now(1050 * ms) == 1020 * ms, "the next tick after it lets it go as of its end: "
                + clock.now(1050 * ms) / ms);
        clock.advance(15 * ms);
        check(clock.now(1050 * ms) == 1035 * ms, "a step forward of 15 ms: " + clock.now(1050 * ms) / ms);
        clock.holdBriefly(1100 * ms, 40 * ms);
        clock.observe(true, 1120 * ms);
        check(clock.paused() && !clock.holding() && clock.now(1500 * ms) == 1085 * ms,
                "the game pauses during a hold: a pause from the hold's start, " + clock.now(1500 * ms) / ms);
        clock.advance(10 * ms);
        clock.holdBriefly(1500 * ms, 10 * ms);
        check(clock.now(1500 * ms) == 1085 * ms, "nothing moves the time during a pause");
        clock.observe(false, 1600 * ms);
        check(clock.now(1600 * ms) == 1085 * ms && clock.now(1610 * ms) == 1095 * ms, "it goes on from the pause");
        clock.holdBriefly(1700 * ms, 50 * ms);
        clock.endHold(1720 * ms);
        check(clock.now(1720 * ms) == 1185 * ms && clock.now(1730 * ms) == 1195 * ms, "a hold ended early: "
                + clock.now(1720 * ms) / ms);
        long previous = Long.MIN_VALUE;
        BattlePauseClock monotone = new BattlePauseClock();
        Random random = new Random(3L);
        for (long t = 0; t < 10_000 * ms; t += ms) {
            int roll = random.nextInt(400);
            if (roll == 0) monotone.holdBriefly(t, random.nextInt(60) * ms);
            else if (roll == 1) monotone.advance(random.nextInt(30) * ms);
            else if (roll == 2) monotone.observe(true, t);
            else if (roll < 8) monotone.observe(false, t);
            long now = monotone.now(t);
            check(now >= previous, "the battle time never goes back, at " + t / ms + " ms: " + previous + " then " + now);
            previous = now;
        }
    }

    /**
     * A battle that began with the game muted (master 0) or with Blocky Music's music slider at 0: the engine skipped
     * the song, and the battle played it once, so it stayed silent to the end however soon the volume came up. Played
     * out tick by tick, the song is asked for again exactly once, on the first tick nothing silences it any more.
     */
    static void refusedSongComesBack() {
        check(MusicStreamRetry.silenced(0.0F, 1.0F, 1.0F), "master 0 silences the song");
        check(MusicStreamRetry.silenced(1.0F, 0.0F, 1.0F), "the Players slider at 0 silences it");
        check(MusicStreamRetry.silenced(1.0F, 1.0F, 0.0F), "Blocky Music's slider at 0 silences it");
        check(MusicStreamRetry.silenced(1.0F, 1.0F, Float.NaN), "a volume that is not a number silences it");
        check(!MusicStreamRetry.silenced(0.4F, 0.7F, 0.05F), "any volume above 0 everywhere does not");
        // master, Players, the instance's own volume, per tick: muted for 200 ticks, then turned up.
        float[][] cases = {{0.0F, 1.0F, 1.0F}, {1.0F, 1.0F, 0.0F}, {1.0F, 0.0F, 1.0F}};
        String[] names = {"master", "mod slider", "Players"};
        for (int c = 0; c < cases.length; c++) {
            boolean refused = true;
            boolean refusedSilenced = MusicStreamRetry.silenced(cases[c][0], cases[c][1], cases[c][2]);
            boolean playing = false;
            int plays = 0;
            int firstPlay = -1;
            for (int tick = 1; tick < 600; tick++) {
                boolean silencedNow = tick < 200 && MusicStreamRetry.silenced(cases[c][0], cases[c][1], cases[c][2]);
                if (MusicStreamRetry.retryNow(refused, refusedSilenced, silencedNow, playing)) {
                    plays++;
                    firstPlay = firstPlay < 0 ? tick : firstPlay;
                    // The engine takes a sound once nothing silences it.
                    refused = silencedNow;
                    refusedSilenced = refused;
                    playing = !refused;
                }
            }
            check(plays == 1 && firstPlay == 200, "the " + names[c] + " raised at tick 200 brings the song back once, at 200: "
                    + plays + " play(s), first at " + firstPlay);
        }
        check(!MusicStreamRetry.retryNow(true, true, true, false), "never while the song is still silenced");
        check(!MusicStreamRetry.retryNow(true, false, false, false), "never for a refusal that had another cause");
        check(!MusicStreamRetry.retryNow(false, false, false, true) && !MusicStreamRetry.retryNow(true, true, false, true),
                "never while the song plays");
    }

    static void controllerMovesClock() throws Exception {
        Class<?> controller = Class.forName("ru.gora.musicbattle.client.ClientBattleController");
        Method move = controller.getDeclaredMethod("moveClockForSong", double.class, long.class);
        Method finish = controller.getDeclaredMethod("finishClockHold", long.class);
        Method visual = controller.getDeclaredMethod("activeVisualTick", float.class);
        move.setAccessible(true);
        finish.setAccessible(true);
        visual.setAccessible(true);
        BattlePauseClock server = BattlePauseClock.GAME;
        server.observe(false, System.nanoTime());
        long now = System.nanoTime();
        long epoch = now - 10_000_000_000L;
        set(controller, "startAtNanos", epoch);
        set(controller, "musicStartedAtNanos", epoch);
        set(controller, "musicPausedAtNanos", 0L);
        set(controller, "clockHoldUntilNanos", 0L);
        long serverBefore = server.now(now);
        move.invoke(null, 0.012D, now);
        check((long) get(controller, "startAtNanos") == epoch - 12_000_000L
                        && (long) get(controller, "musicStartedAtNanos") == epoch - 12_000_000L
                        && server.now(now) - serverBefore == 12_000_000L,
                "the song 12 ms ahead: the clock and the server's step 12 ms forward, "
                        + ((long) get(controller, "startAtNanos") - epoch) + ", " + (server.now(now) - serverBefore));
        // Behind by 30 ms: both stand still for 30 ms and go on together.
        // Read before the hold: a reading after it would be later than the hold's start by however long the call took.
        float before = (float) visual.invoke(null, 0.0F);
        long holdAt = System.nanoTime();
        long serverAtHold = server.now(holdAt);
        move.invoke(null, -0.030D, holdAt);
        check((long) get(controller, "musicPausedAtNanos") == holdAt
                        && (long) get(controller, "clockHoldUntilNanos") == holdAt + 30_000_000L && server.holding(),
                "the song 30 ms behind: a 30 ms hold of both clocks");
        float during = (float) visual.invoke(null, 0.0F);
        check(Math.abs(during - (holdAt - (long) get(controller, "startAtNanos")) / 50_000_000.0F) < 1e-3F
                        && during >= before - 1e-3F, "during the hold the clock stands at its start: " + during + " (before it " + before + ")");
        Thread.sleep(45);
        long after = System.nanoTime();
        float ranOut = (float) visual.invoke(null, 0.0F);
        float expected = (after - 30_000_000L - (long) get(controller, "startAtNanos")) / 50_000_000.0F;
        check(Math.abs(ranOut - expected) < 0.05F, "once it has run out the clock goes on, 30 ms behind the wall: "
                + ranOut + " for " + expected);
        finish.invoke(null, after);
        float released = (float) visual.invoke(null, 0.0F);
        long serverAfter = server.now(after);
        check((long) get(controller, "clockHoldUntilNanos") == 0L && (long) get(controller, "musicPausedAtNanos") == 0L
                        && Math.abs(released - expected) < 0.05F,
                "let go, it reads the same: " + released + " for " + expected);
        check(Math.abs((serverAfter - serverAtHold) - (after - holdAt - 30_000_000L)) < 2_000_000L,
                "and the server's clock went on with it: " + (serverAfter - serverAtHold) / 1e6 + " ms for "
                        + (after - holdAt - 30_000_000L) / 1e6);
        // A hold ended early (a seek, the battle's end) ends the server's too, at the same instant.
        long early = System.nanoTime();
        move.invoke(null, -0.050D, early);
        finish.invoke(null, early + 10_000_000L);
        check(!server.holding() && (long) get(controller, "musicPausedAtNanos") == 0L,
                "a hold ended early ends on both clocks");
    }

    static void spectatorMovesClock() throws Exception {
        Class<?> controller = Class.forName("ru.gora.musicbattle.client.ClientBattleController");
        Method move = controller.getDeclaredMethod("moveSpectatorClockForSong", double.class, long.class, boolean.class);
        Method finish = controller.getDeclaredMethod("finishSpectatorClockHold", long.class);
        Method visual = controller.getDeclaredMethod("spectatorVisualTick", float.class);
        Method stop = controller.getDeclaredMethod("stopSpectatorMusic", Class.forName("net.minecraft.client.Minecraft"));
        move.setAccessible(true);
        finish.setAccessible(true);
        visual.setAccessible(true);
        stop.setAccessible(true);
        BattlePauseClock server = BattlePauseClock.GAME;
        server.observe(false, System.nanoTime());
        check(!server.holding(), "the server's battle time runs before the spectator checks");
        long now = System.nanoTime();
        long started = now - 10_000_000_000L;
        set(controller, "spectatorMusicStartedAtNanos", started);
        set(controller, "spectatorMusicClockStartTick", 100);
        set(controller, "spectatorMusicPausedAtNanos", 0L);
        set(controller, "spectatorClockHoldUntilNanos", 0L);
        long serverBefore = server.now(now);
        // A watched battle's song 12 ms ahead: the spectator clock and the server's battle time step 12 ms forward.
        move.invoke(null, 0.012D, now, true);
        check((long) get(controller, "spectatorMusicStartedAtNanos") == started - 12_000_000L
                        && server.now(now) - serverBefore == 12_000_000L,
                "watched battle, song 12 ms ahead: both clocks 12 ms forward, "
                        + ((long) get(controller, "spectatorMusicStartedAtNanos") - started) + ", "
                        + (server.now(now) - serverBefore));
        // A DJ booth's song 8 ms ahead: only the spectator clock.
        long djStarted = (long) get(controller, "spectatorMusicStartedAtNanos");
        long serverDj = server.now(now);
        move.invoke(null, 0.008D, now, false);
        check((long) get(controller, "spectatorMusicStartedAtNanos") == djStarted - 8_000_000L
                        && server.now(now) == serverDj, "DJ booth, song 8 ms ahead: only the spectator clock moves");
        // Behind by 30 ms: both stand still for 30 ms and go on together.
        float before = (float) visual.invoke(null, 0.0F);
        long holdAt = System.nanoTime();
        long serverAtHold = server.now(holdAt);
        move.invoke(null, -0.030D, holdAt, true);
        check((long) get(controller, "spectatorMusicPausedAtNanos") == holdAt
                        && (long) get(controller, "spectatorClockHoldUntilNanos") == holdAt + 30_000_000L
                        && server.holding(), "watched battle, song 30 ms behind: a 30 ms hold of both clocks");
        long startedNow = (long) get(controller, "spectatorMusicStartedAtNanos");
        float during = (float) visual.invoke(null, 0.0F);
        check(Math.abs(during - (100 + (holdAt - startedNow) / 50_000_000.0F)) < 1e-3F && during >= before - 1e-3F,
                "during the hold the spectator clock stands at its start: " + during + " (before it " + before + ")");
        Thread.sleep(45);
        long after = System.nanoTime();
        float ranOut = (float) visual.invoke(null, 0.0F);
        float expected = 100 + (after - 30_000_000L - startedNow) / 50_000_000.0F;
        check(Math.abs(ranOut - expected) < 0.05F, "once it has run out the spectator clock goes on, 30 ms behind: "
                + ranOut + " for " + expected);
        finish.invoke(null, after);
        float released = (float) visual.invoke(null, 0.0F);
        long serverAfter = server.now(after);
        check((long) get(controller, "spectatorClockHoldUntilNanos") == 0L
                        && (long) get(controller, "spectatorMusicPausedAtNanos") == 0L
                        && (long) get(controller, "spectatorMusicStartedAtNanos") == startedNow + 30_000_000L
                        && Math.abs(released - expected) < 0.05F && !server.holding(),
                "let go, the spectator clock reads the same: " + released + " for " + expected);
        check(Math.abs((serverAfter - serverAtHold) - (after - holdAt - 30_000_000L)) < 2_000_000L,
                "and the server's battle time went on with it: " + (serverAfter - serverAtHold) / 1e6 + " ms for "
                        + (after - holdAt - 30_000_000L) / 1e6);
        // A DJ booth's song behind: the spectator clock holds, the server's time runs on.
        long djHold = System.nanoTime();
        long serverDjHold = server.now(djHold);
        move.invoke(null, -0.020D, djHold, false);
        check(!server.holding() && (long) get(controller, "spectatorClockHoldUntilNanos") == djHold + 20_000_000L,
                "DJ booth, song 20 ms behind: only the spectator clock holds");
        long djStartedAt = (long) get(controller, "spectatorMusicStartedAtNanos");
        finish.invoke(null, djHold + 50_000_000L);
        check((long) get(controller, "spectatorMusicStartedAtNanos") == djStartedAt + 20_000_000L
                        && server.now(djHold + 50_000_000L) - serverDjHold == 50_000_000L,
                "a DJ hold let go after it ran out gives back exactly its 20 ms, the server none");
        // The song stopped during a hold (a seek, a new song, the battle's end) ends the hold on both clocks.
        long early = System.nanoTime();
        move.invoke(null, -0.050D, early, true);
        check(server.holding(), "a new 50 ms hold holds the server's time");
        set(controller, "spectatorMusic", null);
        stop.invoke(null, (Object) null);
        check(!server.holding() && (long) get(controller, "spectatorClockHoldUntilNanos") == 0L
                        && (long) get(controller, "spectatorMusicPausedAtNanos") == 0L,
                "a song stopped during a hold ends it on both clocks");
    }

    static Object get(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(null);
    }

    static void set(Class<?> type, String name, Object value) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        field.set(null, value);
    }

    static String ms(double seconds) {
        return String.format(Locale.ROOT, "%.2f ms", seconds * 1000);
    }

    static void check(boolean condition, String message) {
        checks++;
        if (!condition) {
            failures.add(message);
        }
    }
}

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import javax.sound.sampled.AudioFormat;
import net.minecraft.client.sounds.AudioStream;
import ru.gora.musicbattle.client.AlignedStartAudioStream;
import ru.gora.musicbattle.data.BattleClock;
import ru.gora.musicbattle.data.BattlePauseClock;
import ru.gora.musicbattle.data.BattleSkipClock;
import ru.gora.musicbattle.data.MusicStartAlignment;

/**
 * Why the bot fell out of step a couple of seconds after a rest was skipped in a single-player battle, and the rules
 * that keep it in step, run without a game.
 *
 * <p>A hidden single-player client fought the test NPC on a chart with a long rest and skipped it. Before the skip the
 * song sat within half a tick of the battle clock; after it the song was 3.3 ticks behind the clock and the client's
 * clock itself 0.66 ticks behind the server's, so every hit of the bot, which the server times, landed about 200 ms
 * before the beat the player heard - from the first note after the rest, a couple of seconds after the skip. Two
 * causes:
 * <ul>
 *   <li>The client rebuilt its clock from the skip's target tick, as if the server had stood at the very start of that
 *       tick when it sent the news. It stood somewhere inside it, and the news arrives a frame or so later.</li>
 *   <li>The song was started again at the target tick, but an Ogg stream has to decode everything before that point
 *       first, and the battle clock ran on meanwhile: the further into the song, the later the music. The drift check
 *       only re-seeks at four ticks, and a re-seek came out just as late.</li>
 * </ul>
 * The pause menu kept the server and the client together (BattlePauseClock, tools/BattleFeelChecks.java), but not the
 * song: Minecraft stops and starts every sound in the very frame the menu opens and closes, while the battle clock was
 * held and released on the next client tick, so each visit to the menu moved the song against the bot by up to a tick
 * either way, unseen by the drift check. Held at the pause flag instead, at the end of the frame, a visit still moved
 * it by the difference of that frame's frame-rate sleeps, a few milliseconds that added up visit after visit; the
 * clock now stops and starts at the sounds' own instants. {@link #aMenuVisitKeepsTheSongOnTheClock} replays all three
 * frame by frame.
 *
 * <p>Each part reproduces the old arithmetic first, written out here so it does not depend on deleted code, and then
 * runs the real rules: {@link BattleSkipClock}, {@link MusicStartAlignment} and the real
 * {@link AlignedStartAudioStream} over a synthetic PCM stream.
 */
public final class BattleSkipSyncChecks {
    private static final long TICK = 50_000_000L;
    private static final float RATE = 44_100.0F;
    /** How the hidden test client measured its battles: a little ahead of the server before any skip. */
    private static final double CLIENT_LEAD = 0.12D;
    /** Decoding an Ogg song to a seek point, per second of song before it, on the test machine. */
    private static final double DECODE_MILLIS_PER_SECOND = 0.9D;
    /** From a stream's first read to the source starting, on the test machine, once the decoder had warmed up. */
    private static final double START_DELAY_TICKS = 0.1D;
    private static int failures;
    private static int checks;

    public static void main(String[] args) throws IOException {
        clientKeepsItsPlaceThroughTheJump();
        aSeekNoLongerStartsLate();
        theBotLandsOnTheBeatAgain();
        planDropsOrPads();
        startLeadIsLearntAndShrugsOffAStall();
        realStreamDropsTheLateAudio();
        realStreamPadsAnEarlyStart();
        realStreamWithoutAClockPlaysAsAsked();
        aMenuVisitKeepsTheSongOnTheClock();
        if (failures > 0) {
            System.err.println(failures + " of " + checks + " skip sync check(s) failed.");
            System.exit(1);
        }
        System.out.println("Battle skip sync checks passed: " + checks);
        System.out.println("NOT COVERED: OpenAL's own start-up in the game, and the network between server and client");
    }

    /**
     * The client's clock minus the server's, {@code laterTicks} after the news of a skip from tick 2480 to 2740 is
     * handled: {@code old} rebuilds the client's clock from the target, as the client used to, the other moves it by
     * the jump. The vote passed while the server was {@code phase} of the way through its tick, and the client handled
     * the news {@code delayMillis} later.
     */
    private static double clientMinusServerAfterSkip(boolean old, double phase, int delayMillis, int laterTicks) {
        long serverEpoch = 9_000_000_000_000L;
        long clientOffset = 123_456_789_000L;
        long clientEpoch = serverEpoch + clientOffset - Math.round(CLIENT_LEAD * TICK);
        int from = 2_480;
        int to = 2_740;
        long serverNow = serverEpoch + from * TICK + Math.round(phase * TICK);
        check(BattleClock.ticksSince(serverEpoch, serverNow) == from, "the server is on tick " + from);
        long serverEpochAfter = BattleSkipClock.serverEpochAfterSkip(serverEpoch, from, to);
        long serverArrival = serverNow + delayMillis * 1_000_000L;
        long clientArrival = serverArrival + clientOffset;
        // The old client: the arrival instant, less the target, less its one-way delay (none in single player).
        long clientEpochAfter = old ? clientArrival - to * TICK : BattleSkipClock.clientEpochAfterJump(clientEpoch, from, to);
        return ticks(clientEpochAfter, clientArrival + laterTicks * TICK)
                - ticks(serverEpochAfter, serverArrival + laterTicks * TICK);
    }

    private static void clientKeepsItsPlaceThroughTheJump() {
        double worstOld = 0.0D;
        for (double phase : new double[]{0.02D, 0.3D, 0.55D, 0.8D, 0.97D}) {
            for (int delayMillis : new int[]{0, 8, 16, 33}) {
                for (int later : new int[]{0, 20, 2_000}) {
                    worstOld = Math.min(worstOld, clientMinusServerAfterSkip(true, phase, delayMillis, later));
                    double now = clientMinusServerAfterSkip(false, phase, delayMillis, later);
                    check(Math.abs(now - CLIENT_LEAD) < 1.0E-6D, "after the jump the client is still " + CLIENT_LEAD
                            + " ahead (phase " + phase + ", " + delayMillis + " ms): " + now);
                }
            }
        }
        check(worstOld < -0.9D, "reproduced: rebuilt from the target, the client fell up to " + (-worstOld)
                + " ticks behind the server it had been " + CLIENT_LEAD + " ahead of");
    }

    /**
     * Where the song is, minus the battle clock, after it is started again at {@code requested} while the clock reads
     * a third of a tick more. The old stream began at the tick it was asked for, whenever the decode finished; the new
     * one is lined up at its first read for the moment it will be heard, with {@code lead} as that moment's distance.
     */
    private static double songMinusClockAfterSeek(boolean old, int requested, double lead) {
        double clockAtRequest = requested + 0.35D;
        double decodeTicks = requested / 20.0D * DECODE_MILLIS_PER_SECOND / 50.0D;
        double clockAtFirstRead = clockAtRequest + decodeTicks;
        double clockAtStart = clockAtFirstRead + START_DELAY_TICKS;
        if (old) {
            return requested - clockAtStart;
        }
        MusicStartAlignment.Plan plan = MusicStartAlignment.plan(requested, clockAtFirstRead + lead, 1.0D, RATE);
        return plan.startTick() - clockAtStart;
    }

    private static void aSeekNoLongerStartsLate() {
        double skipped = songMinusClockAfterSeek(true, 2_740, 0.0D);
        check(skipped < -2.5D && Math.abs(skipped) < 4.0D, "reproduced: 137 s into the song the old restart left it "
                + (-skipped) + " ticks behind, under the drift check's four");
        double later = songMinusClockAfterSeek(true, 4_800, 0.0D);
        double reseek = songMinusClockAfterSeek(true, 4_820, 0.0D);
        check(later < -4.0D && reseek < -4.0D, "reproduced: 240 s in the drift check re-seeks, and the re-seek is "
                + (-reseek) + " ticks late again");
        for (int requested : new int[]{0, 40, 1_200, 2_740, 4_800}) {
            double heard = songMinusClockAfterSeek(false, requested, START_DELAY_TICKS);
            check(Math.abs(heard) < 0.01D, "a restart " + requested + " ticks in is heard on the clock: " + heard);
            double unled = songMinusClockAfterSeek(false, requested, 0.0D);
            check(Math.abs(unled + START_DELAY_TICKS) < 0.01D,
                    "lined up for the first read alone it would still come out the start delay late: " + unled);
        }
    }

    /**
     * The bot's hit is timed by the server, on the first server tick at or past its note, and shown at once by a
     * single-player client. What the player hears then is the song where the client's clock puts it.
     */
    private static void theBotLandsOnTheBeatAgain() {
        int note = 2_800;
        double serverAtHit = note + 0.4D;
        double oldHeard = serverAtHit + clientMinusServerAfterSkip(true, 0.55D, 16, 60)
                + songMinusClockAfterSeek(true, 2_740, 0.0D);
        double newHeard = serverAtHit + clientMinusServerAfterSkip(false, 0.55D, 16, 60)
                + songMinusClockAfterSeek(false, 2_740, START_DELAY_TICKS);
        check(oldHeard - note < -3.0D, "reproduced: after the skip the old bot hit with the song " + (note - oldHeard)
                + " ticks short of its note, about 200 ms early");
        check(newHeard - note > 0.0D && newHeard - note < 1.0D,
                "now it hits within the server tick of the note that is playing: " + (newHeard - note));
    }

    private static void planDropsOrPads() {
        double frameTicks = 20.0D / RATE;
        MusicStartAlignment.Plan late = MusicStartAlignment.plan(2_740, 2_743.3D, 1.0D, RATE);
        check(late.skipFrames() == Math.round((2_743.3D - 2_740) / 20.0D * RATE) && late.silenceFrames() == 0,
                "a late stream drops the audio the clock has passed: " + late);
        check(Math.abs(late.startTick() - 2_743.3D) < frameTicks, "and its first sample is the clock's tick");
        MusicStartAlignment.Plan early = MusicStartAlignment.plan(0, -1.6D, 1.0D, RATE);
        check(early.silenceFrames() == Math.round(1.6D / 20.0D * RATE) && early.skipFrames() == 0,
                "the opening seek, started before tick zero, opens with silence: " + early);
        check(Math.abs(early.startTick() + 1.6D) < frameTicks, "so the song's first sample is tick zero");
        MusicStartAlignment.Plan sped = MusicStartAlignment.plan(100, 102.0D, 1.5D, RATE);
        check(sped.skipFrames() == Math.round(2.0D / 20.0D * 1.5D * RATE),
                "a source played 1.5 times as fast drops 1.5 times the audio: " + sped);
        check(Math.abs(sped.startTick() - 102.0D) < frameTicks, "and still lands on the clock");
        MusicStartAlignment.Plan close = MusicStartAlignment.plan(100, 100.01D, 1.0D, RATE);
        check(close.skipFrames() == 0 && close.silenceFrames() == 0 && close.startTick() == 100,
                "a stream already on the clock is left alone");
        MusicStartAlignment.Plan jumped = MusicStartAlignment.plan(100, 100 + MusicStartAlignment.MAX_CORRECTION_TICKS + 1,
                1.0D, RATE);
        check(jumped.skipFrames() == 0 && jumped.silenceFrames() == 0,
                "a clock that jumped half a minute while the stream opened is left to the next seek");
        MusicStartAlignment.Plan noClock = MusicStartAlignment.plan(100, Double.NaN, 1.0D, RATE);
        check(noClock.skipFrames() == 0 && noClock.silenceFrames() == 0 && noClock.startTick() == 100,
                "without a clock the stream starts where it was asked to");
        MusicStartAlignment.Plan badRate = MusicStartAlignment.plan(100, 103.0D, 1.0D, 0.0D);
        check(badRate.skipFrames() == 0 && badRate.silenceFrames() == 0, "a stream with no frame rate is left alone");
    }

    /**
     * A session of starts as the hidden test client measured them: the first stream of the session took 0.6 ticks
     * from its first read to the speakers while the decoder warmed up, every later one about a tenth of a tick, and
     * here two stalls of the sound thread are thrown in. Ordinary starts land within a tenth of a tick of the clock,
     * and neither the slow first start nor a stall throws the next one early.
     */
    private static void startLeadIsLearntAndShrugsOffAStall() {
        double lead = MusicStartAlignment.DEFAULT_START_LEAD_TICKS;
        double[] delays = {0.61D, 0.10D, 0.12D, 0.08D, 0.11D, 3.6D, 0.10D, 0.09D, 9.0D, 0.10D, 0.12D, 0.10D};
        double worstAfterFirst = 0.0D;
        for (int i = 0; i < delays.length; i++) {
            double error = lead - delays[i];
            double before = lead;
            lead = MusicStartAlignment.nextStartLead(lead, delays[i]);
            check(lead - before <= 0.05D + 1.0E-9D, "one start raises the lead by a twentieth of a tick at most: "
                    + before + " -> " + lead);
            if (i >= 1 && delays[i] < 1.0D) {
                worstAfterFirst = Math.max(worstAfterFirst, Math.abs(error));
            }
        }
        check(worstAfterFirst < 0.1D, "after the first start, ordinary ones land within a tenth of a tick: " + worstAfterFirst);
        check(Math.abs(lead - 0.1D) < 0.05D, "and the lead settles near the machine's own delay: " + lead);
        // The first law tried: from 0.4, a quarter of the way to anything up to a tick above. The hidden client
        // measured what it did: the restart after a skip came out a third of a tick early.
        double firstLaw = 0.4D + (Math.min(0.61D, 0.4D + 1.0D) - 0.4D) * 0.25D;
        check(firstLaw - 0.1D > 0.3D, "reproduced: a lead learnt that way put the restart after a skip "
                + (firstLaw - 0.1D) + " ticks early");
        double now = MusicStartAlignment.nextStartLead(MusicStartAlignment.DEFAULT_START_LEAD_TICKS, 0.61D);
        check(now - 0.1D < 0.06D, "now the same first start leaves the restart " + (now - 0.1D) + " ticks early");
        check(MusicStartAlignment.nextStartLead(0.5D, Double.NaN) == 0.5D, "a start that was not measured teaches nothing");
        check(MusicStartAlignment.nextStartLead(0.5D, MusicStartAlignment.MAX_START_LEAD_TICKS + 0.1D) == 0.5D,
                "nor does one past the stall limit");
        check(MusicStartAlignment.nextStartLead(Double.NaN, 0.5D) > 0.0D, "a lost lead starts again from the default");
        check(MusicStartAlignment.nextStartLead(0.0D, -3.0D) == 0.0D, "the lead is never negative");
        double slow = MusicStartAlignment.DEFAULT_START_LEAD_TICKS;
        int starts = 0;
        while (slow < 1.9D && starts < 100) {
            slow = MusicStartAlignment.nextStartLead(slow, 2.0D);
            starts++;
        }
        check(starts <= 40, "a machine that really takes two ticks is followed there in " + starts + " starts");
    }

    private static void realStreamDropsTheLateAudio() throws IOException {
        FakeSong song = new FakeSong(RATE, 200_000);
        double[] heard = new double[2];
        long before = System.nanoTime();
        AlignedStartAudioStream stream = new AlignedStartAudioStream(song, 2_740, 1.0F, () -> 2_743.3D,
                (tick, alignedAt) -> {
                    heard[0] = tick;
                    heard[1] = alignedAt;
                });
        ByteBuffer first = stream.read(4_096 + 3);
        check(first.remaining() % FakeSong.FRAME == 0, "every read is whole frames: " + first.remaining());
        long skipped = Math.round((2_743.3D - 2_740) / 20.0D * RATE);
        check(FakeSong.frameAt(first, 0) == skipped, "the first frame heard is the one the clock has reached: "
                + FakeSong.frameAt(first, 0) + " against " + skipped);
        check(Math.abs(heard[0] - (2_740 + skipped / RATE * 20.0D)) < 1.0E-6D, "and the owner hears that tick: " + heard[0]);
        check(heard[1] >= before && heard[1] <= System.nanoTime(), "and when it was lined up");
        long expected = skipped;
        long total = 0;
        ByteBuffer next = first;
        int[] sizes = {1_000, 65_536, 7, 12_345, 4_096};
        for (int i = 0; next != null && next.hasRemaining(); i++) {
            for (int frame = 0; frame < next.remaining() / FakeSong.FRAME; frame++) {
                if (FakeSong.frameAt(next, frame) != expected++) {
                    check(false, "a frame was lost or repeated after frame " + (expected - 1));
                    return;
                }
            }
            total += next.remaining() / FakeSong.FRAME;
            next = stream.read(sizes[i % sizes.length]);
        }
        check(total == 200_000 - skipped, "the rest of the song comes out once, whole: " + total);
    }

    private static void realStreamPadsAnEarlyStart() throws IOException {
        FakeSong song = new FakeSong(RATE, 50_000);
        double[] heard = new double[1];
        AlignedStartAudioStream stream = new AlignedStartAudioStream(song, 0, 1.0F, () -> -1.6D,
                (tick, alignedAt) -> heard[0] = tick);
        long silence = Math.round(1.6D / 20.0D * RATE);
        long zeros = 0;
        long firstSong = -1;
        while (firstSong < 0) {
            ByteBuffer next = stream.read(3_000);
            if (next == null || !next.hasRemaining()) {
                break;
            }
            for (int frame = 0; frame < next.remaining() / FakeSong.FRAME; frame++) {
                long value = FakeSong.frameAt(next, frame);
                if (value < 0) {
                    zeros++;
                } else {
                    firstSong = value;
                    break;
                }
            }
        }
        check(zeros == silence, "an early start opens with exactly the silence it is early by: " + zeros + " of " + silence);
        check(firstSong == 0, "and then the song from its first frame: " + firstSong);
        check(Math.abs(heard[0] + 1.6D) < 20.0D / RATE, "whose first sample is tick -1.6: " + heard[0]);
    }

    private static void realStreamWithoutAClockPlaysAsAsked() throws IOException {
        FakeSong song = new FakeSong(RATE, 10_000);
        double[] heard = {Double.NaN};
        AlignedStartAudioStream stream = new AlignedStartAudioStream(song, 300, 1.0F, () -> Double.NaN,
                (tick, alignedAt) -> heard[0] = tick);
        ByteBuffer first = stream.read(4_096);
        check(FakeSong.frameAt(first, 0) == 0, "with no clock the stream plays from where it was opened");
        check(heard[0] == 300.0D, "and says so: " + heard[0]);
    }

    private static double ticks(long epoch, long now) {
        return (now - epoch) / (double) TICK;
    }

    /** A frame at 60 frames a second, in nanoseconds; client ticks run at the start of a frame, 20 a second. */
    private static final long FRAME = 16_666_667L;
    /** Where in its frame Minecraft handles Esc or the menu's button: in updateDisplay, after the frame is drawn. */
    private static final long INPUT_IN_FRAME = 9_000_000L;

    /** The start of the first frame after {@code instant} that runs a client tick, on frames starting at {@code offset}. */
    private static long nextTickingFrame(long instant, long offset) {
        long frame = Math.floorDiv(instant - offset, FRAME) + 1;
        while (true) {
            long start = offset + frame * FRAME;
            // A tick is due at a frame start once a tick boundary has passed since the frame before it began.
            if (Math.floorDiv(start, TICK) != Math.floorDiv(start - FRAME, TICK)) {
                return start;
            }
            frame++;
        }
    }

    /** When the battle clock is held and released for a visit to the pause menu. */
    private enum MenuHold {
        /** On the first client tick after the pause flag changes, as the client tick did. */
        NEXT_TICK,
        /** At the pause flag itself, ClientPauseChangeEvent at the end of the frame, as last round's fix did. */
        FLAG,
        /**
         * Where Minecraft stops and starts the sounds: the pause menu's setup (ScreenEvent.Init.Post, just before
         * Minecraft#pauseGame pauses them) and its closing onto the game (ScreenEvent.Closing, just before
         * Minecraft#setScreen resumes them), the release applied at the flag as of that instant
         * (ClientBattleController#onScreenShown, #onScreenClosing, #gameResumeInstant).
         */
        SOUND
    }

    /**
     * The song minus the battle clock, in ticks, a second after a visit to the pause menu, as Minecraft orders it:
     * Esc pauses every sound at once (Minecraft#pauseGame), the pause flag and ClientPauseChangeEvent follow at the end
     * of that frame, after its frame-rate sleep of {@code sleepIn}; the menu's button resumes every sound at once
     * (Minecraft#setScreen(null)) and the flag follows {@code sleepOut} later. The real BattlePauseClock is held and
     * released as {@code hold} says.
     */
    private static double songMinusClockAfterMenu(MenuHold hold, long frameOffset, int pauseFrames, long sleepIn,
                                                  long sleepOut) {
        long base = 1_000_000_000_000L;
        long escape = base + frameOffset + 120L * FRAME + INPUT_IN_FRAME;
        long back = escape + pauseFrames * FRAME;
        long pausedFlag = escape + sleepIn;
        long resumedFlag = back + sleepOut;
        BattlePauseClock clock = new BattlePauseClock();
        switch (hold) {
            case NEXT_TICK -> {
                clock.observe(true, nextTickingFrame(pausedFlag, base + frameOffset));
                clock.observe(false, nextTickingFrame(resumedFlag, base + frameOffset));
            }
            case FLAG -> {
                clock.observe(true, pausedFlag);
                clock.observe(false, resumedFlag);
            }
            case SOUND -> {
                clock.observe(true, escape);
                // Applied at the flag, resumedFlag, as of the instant the sounds came back: the server reads nothing
                // in between, since it does not tick while Minecraft#isPaused.
                check(clock.paused() && clock.pausedSince() == escape, "the menu's setup holds the clock at once");
                clock.observe(false, back);
            }
        }
        long later = back + 20L * TICK;
        double song = (later - base) - (back - escape);
        double battle = clock.now(later) - base;
        return (song - battle) / TICK;
    }

    private static void aMenuVisitKeepsTheSongOnTheClock() {
        double oldWorstAhead = 0.0D;
        double oldWorstBehind = 0.0D;
        double flagWorst = 0.0D;
        double soundWorst = 0.0D;
        long[] sleeps = {0L, 3_000_000L, 7_000_000L, 16_000_000L};
        for (long offset = 0L; offset < TICK; offset += 2_500_000L) {
            for (int pauseFrames = 240; pauseFrames < 246; pauseFrames++) {
                for (long sleepIn : sleeps) {
                    for (long sleepOut : sleeps) {
                        double before = songMinusClockAfterMenu(MenuHold.NEXT_TICK, offset, pauseFrames, sleepIn, sleepOut);
                        oldWorstAhead = Math.max(oldWorstAhead, before);
                        oldWorstBehind = Math.min(oldWorstBehind, before);
                        double flag = songMinusClockAfterMenu(MenuHold.FLAG, offset, pauseFrames, sleepIn, sleepOut);
                        flagWorst = Math.max(flagWorst, Math.abs(flag));
                        check(Math.abs(flag - (sleepOut - sleepIn) / (double) TICK) < 1.0E-9D,
                                "held at the pause flag, the song is off by the two frame-rate sleeps' difference alone: "
                                        + flag);
                        double now = songMinusClockAfterMenu(MenuHold.SOUND, offset, pauseFrames, sleepIn, sleepOut);
                        soundWorst = Math.max(soundWorst, Math.abs(now));
                        check(Math.abs(now) < 1.0E-9D, "held and released with the sounds, a visit moves the song by"
                                + " nothing, whatever the frames sleep: " + now + " ticks");
                    }
                }
            }
        }
        System.out.printf("One menu visit, song minus clock: held on the next tick %+.2f..%+.2f ticks, at the flag up to"
                + " %.2f, with the sounds %.2f%n", oldWorstBehind, oldWorstAhead, flagWorst, soundWorst);
        check(oldWorstAhead > 0.6D && oldWorstBehind < -0.6D, "reproduced: held on the next client tick, one menu visit put"
                + " the song up to " + oldWorstAhead + " ticks ahead of the bot or " + (-oldWorstBehind) + " behind");
        check(flagWorst > 0.2D, "reproduced: held at the pause flag, one visit still moved it by up to " + flagWorst
                + " ticks, the difference of two frame-rate sleeps, and the visits add up");
        check(songMinusClockAfterMenu(MenuHold.FLAG, 12_345_678L, 300, 4_000_000L, 4_000_000L) == 0.0D,
                "and by nothing at all when both frames sleep alike");
    }

    private static void check(boolean value, String message) {
        checks++;
        if (!value) {
            failures++;
            System.err.println("FAIL: " + message);
        }
    }

    /**
     * A song of signed 16-bit stereo PCM whose frame {@code i} carries {@code i + 1}, low half on the left and high half
     * on the right, so silence (zero) and every frame can be told apart. Hands out uneven chunks, as a decoder does.
     */
    private static final class FakeSong implements AudioStream {
        static final int FRAME = 4;
        private final AudioFormat format;
        private final int frames;
        private int next;
        private int calls;

        FakeSong(float rate, int frames) {
            this.format = new AudioFormat(rate, 16, 2, true, ByteOrder.nativeOrder() == ByteOrder.BIG_ENDIAN);
            this.frames = frames;
        }

        @Override
        public AudioFormat getFormat() {
            return format;
        }

        @Override
        public ByteBuffer read(int size) {
            if (next >= frames) {
                return null;
            }
            int chunk = calls++ % 3 == 0 ? 1_111 : 2_048;
            int count = Math.min(frames - next, Math.max(1, Math.min(size / FRAME, chunk)));
            ByteBuffer buffer = ByteBuffer.allocateDirect(count * FRAME).order(ByteOrder.nativeOrder());
            for (int i = 0; i < count; i++) {
                int value = next + i + 1;
                buffer.putShort((short) (value & 0xFFFF));
                buffer.putShort((short) ((value >>> 16) & 0xFFFF));
            }
            next += count;
            buffer.flip();
            return buffer;
        }

        /** The song frame at {@code frame} of {@code buffer}, from its position, or -1 for silence. */
        static long frameAt(ByteBuffer buffer, int frame) {
            ByteBuffer view = buffer.duplicate().order(ByteOrder.nativeOrder());
            int at = view.position() + frame * FRAME;
            int value = (view.getShort(at) & 0xFFFF) | ((view.getShort(at + 2) & 0xFFFF) << 16);
            return value - 1L;
        }

        @Override
        public void close() {
        }
    }
}

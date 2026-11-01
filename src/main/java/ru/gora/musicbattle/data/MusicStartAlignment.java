package ru.gora.musicbattle.data;

/**
 * Where a battle song's stream has to begin so that it plays in step with the battle clock from its first sample.
 *
 * <p>Starting the song at a given tick means decoding the whole song up to that tick and throwing it away, because an
 * Ogg stream cannot jump. The decode takes time - about a millisecond for each second of song on a fast machine, far
 * more on a slow one - and the battle clock runs on meanwhile. The stream used to begin exactly where it was asked to
 * when the seek was requested, so it started behind the clock by however long the decode took, and stayed behind for
 * the rest of the song: after skipping a rest 137 seconds into a song a hidden test client measured the music 3.3
 * ticks behind the chart and the bot, which then hit every note about 200 ms before its beat. The once-a-second drift
 * check left it there, because it only re-seeks a song that is four ticks out; and where the delay was over that
 * limit, every correction was a new seek that came out just as late.
 *
 * <p>So the stream is aligned when it is about to play rather than when it was asked for: the clock is read again at
 * its first read, and the stream drops the audio the clock has already passed or, if it is early, opens with the
 * silence it is early by. The battle's opening seek is early by design (the song is started just before tick zero),
 * and is padded the same way, so the first beat lands on tick zero instead of a tick before it.
 *
 * <p>Even that first read comes a little before the song is heard: Minecraft decodes the stream's first four seconds
 * between its first read and starting the source. The clock is therefore read that much ahead (the start lead), and
 * the lead is learnt from each start, because the decode takes as long as the machine needs ({@link #nextStartLead}).
 *
 * <p>Pure arithmetic, checked by tools/BattleSkipSyncChecks.java.
 */
public final class MusicStartAlignment {
    /** Below this many ticks either way the stream starts as it is; the drift check allows four. */
    public static final double TOLERANCE_TICKS = 0.05D;
    /**
     * A correction larger than this many ticks means the clock has jumped (a skip arriving while the stream opened),
     * and that seek is superseded anyway; the stream is left as it was asked for rather than decoding or padding
     * half a minute for nothing.
     */
    public static final double MAX_CORRECTION_TICKS = 20.0D * 30.0D;
    /**
     * How far ahead of the clock a stream is lined up before anything has been measured. Minecraft decodes the first
     * four seconds of a stream between its first read and starting the source; a hidden test client took a tenth of a
     * tick or less for that on every stream but the first of the session, which took 0.6 while the decoder warmed up.
     */
    public static final double DEFAULT_START_LEAD_TICKS = 0.1D;
    /** A start that took longer than this many ticks was a stalled sound thread, not a machine's normal pace. */
    public static final double MAX_START_LEAD_TICKS = 4.0D;
    /** The share of the way from the lead to a new measurement that one start moves it. */
    private static final double LEARNING_RATE = 0.25D;
    /**
     * A measurement counts for at most this much above the lead, so the session's slow first start, or one stall,
     * cannot throw the next start early: a lead learnt from the 0.6-tick first start put the next one, after a skip,
     * a third of a tick ahead of the clock.
     */
    private static final double MAX_RISE_TICKS = 0.2D;

    private MusicStartAlignment() {
    }

    /**
     * The start lead for the next stream, after one that took {@code measuredTicks} from being lined up to starting:
     * a quarter of the way from the old lead towards the measurement, which itself counts for at most
     * {@link #MAX_RISE_TICKS} above the lead, so the lead rises by at most a twentieth of a tick a start and a single
     * slow start barely moves the next one. A measurement that is not a number, or longer than
     * {@link #MAX_START_LEAD_TICKS}, teaches nothing.
     */
    public static double nextStartLead(double leadTicks, double measuredTicks) {
        double lead = Double.isFinite(leadTicks) ? clamp(leadTicks) : DEFAULT_START_LEAD_TICKS;
        if (!Double.isFinite(measuredTicks) || measuredTicks > MAX_START_LEAD_TICKS) {
            return lead;
        }
        double measured = clamp(Math.min(measuredTicks, lead + MAX_RISE_TICKS));
        return clamp(lead + (measured - lead) * LEARNING_RATE);
    }

    private static double clamp(double ticks) {
        return Math.max(0.0D, Math.min(MAX_START_LEAD_TICKS, ticks));
    }

    /**
     * How to start a stream that was opened at {@code requestedTick} when the battle clock now reads {@code clockTick}.
     * {@code pitch} is the playback rate of the source (1 for the battle's own sped-up copies), {@code frameRate} the
     * source's frames per second. A positive {@code skipFrames} drops that much audio first; a positive
     * {@code silenceFrames} plays that much silence first. {@code startTick} is the battle tick the first sample now
     * belongs to.
     */
    public static Plan plan(double requestedTick, double clockTick, double pitch, double frameRate) {
        double safePitch = Double.isFinite(pitch) && pitch > 0.0D ? pitch : 1.0D;
        if (!Double.isFinite(requestedTick) || !Double.isFinite(clockTick) || !Double.isFinite(frameRate) || frameRate <= 0.0D) {
            return new Plan(0L, 0L, requestedTick);
        }
        double lateTicks = clockTick - requestedTick;
        if (Math.abs(lateTicks) < TOLERANCE_TICKS || Math.abs(lateTicks) > MAX_CORRECTION_TICKS) {
            return new Plan(0L, 0L, requestedTick);
        }
        // A battle tick is 1/20 s of the battle; the source moves pitch times as fast through its own audio.
        long frames = Math.round(Math.abs(lateTicks) / 20.0D * safePitch * frameRate);
        if (frames == 0L) {
            return new Plan(0L, 0L, requestedTick);
        }
        double startTick = requestedTick + Math.copySign(frames / frameRate / safePitch * 20.0D, lateTicks);
        return lateTicks > 0.0D ? new Plan(frames, 0L, startTick) : new Plan(0L, frames, startTick);
    }

    /** One stream's start: drop {@code skipFrames}, or play {@code silenceFrames} of silence, then the song. */
    public record Plan(long skipFrames, long silenceFrames, double startTick) {
    }
}

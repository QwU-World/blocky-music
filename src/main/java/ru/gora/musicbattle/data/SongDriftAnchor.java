package ru.gora.musicbattle.data;

import java.util.Arrays;

/**
 * Keeps the battle song where it began against the battle clock, from where OpenAL really is.
 *
 * <p>OpenAL pauses and resumes a source at its own next mixing step, not at the instant it is asked to, and the part of
 * a step between the call and that step is different every time. The battle clock is held and let go at the exact
 * instants (ClientBattleController#onScreenShown, #gameResumeInstant), so every visit to the single-player pause menu
 * moved the song against the chart by the difference of two such parts. Measured in a hidden game on OpenAL Soft's
 * null device: from -27 to +18 ms per visit, 24.8 ms off before a pause within 20 visits and 13 ms at their end, with
 * nothing to bring it back, because the drift check reckons the song's place from the same stamps.
 *
 * <p>So the song's real place is read every client tick (the bytes the source has played), as its offset from the
 * clock in seconds. A single reading moves in steps of the mixer's update (about 21 ms on the null device, a spread of
 * about 6.5 ms), so they are taken as means, of the readings within {@link #OUTLIER_SECONDS} of their median: a mixer
 * starved for a moment (a busy machine) reads far behind for a few ticks and catches up, and is left out. The first mean
 * of {@link #WINDOW} readings of a stream is its reference - where its start put it, which the start's own alignment
 * decides. After that the mean of the last {@link #WINDOW} readings (at least {@link #MIN_READINGS}), when it is further
 * from the reference than three standard errors of the two means' difference and than {@link #THRESHOLD_SECONDS}, is a
 * drift, which the
 * owner takes out by moving the clock; the readings kept are moved with it, so the mean goes on getting sharper: a
 * visit's 20 ms goes in about half a second and the last milliseconds within two, and noise alone seldom moves
 * anything. A pause starts the readings over: readings across one mix the song before and after it.
 *
 * <p>Pure arithmetic over the readings it is given; checked without a game (tools/SongDriftChecks.java).
 */
public final class SongDriftAnchor {
    /** Readings in one mean: four seconds of client ticks, under a millisecond of noise left in it. */
    public static final int WINDOW = 80;
    /** The fewest readings of a stream's first stretch its reference is taken from; it sharpens up to a window. */
    public static final int REFERENCE_READINGS = 40;
    /** Readings dropped after a stream starts or the song resumes, while its first buffers settle. */
    public static final int SETTLE = 4;
    /** The fewest readings a correction is made from. */
    public static final int MIN_READINGS = 8;
    /** Drift below this is left alone: three times the noise of one full mean. */
    public static final double THRESHOLD_SECONDS = 0.003D;
    /** Drift beyond this is taken out from the first {@link #MIN_READINGS}; below it only from more. */
    public static final double QUICK_SECONDS = 0.008D;
    /** Drift beyond this is taken out from half a window; below it only from a full one. */
    public static final double HALF_WINDOW_SECONDS = 0.0045D;
    /** Readings further than this from their median are a starved mixer, not the song's place. */
    public static final double OUTLIER_SECONDS = 0.015D;
    /** Anything further than this is no drift but a stream that went wrong; the seek in the drift check is for that. */
    public static final double LIMIT_SECONDS = 0.120D;

    /** The last readings, oldest first from {@code next} once {@link #WINDOW} have come. */
    private final double[] readings = new double[WINDOW];
    private final double[] sorted = new double[WINDOW];
    private Object stream;
    private double reference = Double.NaN;
    /** The standard error of the reference's own mean: a drift is measured against it, so its noise counts too. */
    private double referenceError;
    /** Whether the reference is settled: its first stretch has ended or filled a window. */
    private boolean referenceSettled;
    private int count;
    private int next;
    private int settle;
    /** Readings since the last correction from a full window: one window between them. */
    private int sinceFine = WINDOW;

    /** Forgets everything: no stream, no reference. */
    public void clear() {
        stream = null;
        reference = Double.NaN;
        referenceError = 0.0D;
        referenceSettled = false;
        restart();
    }

    /** A new mean from here on, keeping the reference: after a pause, or after the clock was moved. */
    public void restart() {
        referenceSettled |= !Double.isNaN(reference);
        count = 0;
        next = 0;
        settle = SETTLE;
    }

    /** The reference of the stream now measured, NaN until its first mean is in. */
    public double reference() {
        return reference;
    }

    /**
     * One reading of {@code stream}: its song is {@code offsetSeconds} ahead of the clock. Returns how far the clock has
     * to move forward to put the song back on its reference (negative: hold it back), or 0 when nothing is to be done.
     * A reading of another stream starts over with that one: a seek has its own start, and its own reference.
     */
    public double add(Object readingStream, double offsetSeconds) {
        if (readingStream == null || !Double.isFinite(offsetSeconds)) {
            return 0.0D;
        }
        if (readingStream != stream) {
            clear();
            stream = readingStream;
        }
        if (settle > 0) {
            settle--;
            return 0.0D;
        }
        sinceFine++;
        readings[next] = offsetSeconds;
        next = (next + 1) % WINDOW;
        count = Math.min(WINDOW, count + 1);
        boolean full = count >= WINDOW;
        if (count >= MIN_READINGS) {
            System.arraycopy(readings, 0, sorted, 0, count);
            Arrays.sort(sorted, 0, count);
            double median = (sorted[(count - 1) / 2] + sorted[count / 2]) / 2.0D;
            double sum = 0.0D;
            double squares = 0.0D;
            int kept = 0;
            for (int i = 0; i < count; i++) {
                if (Math.abs(readings[i] - median) <= OUTLIER_SECONDS) {
                    sum += readings[i];
                    squares += readings[i] * readings[i];
                    kept++;
                }
            }
            double mean = sum / kept;
            double standardError = Math.sqrt(Math.max(0.0D, squares / kept - mean * mean) / kept);
            // Most of the readings have to agree; a mean that is mostly a starved mixer says nothing.
            boolean steady = kept * 4 >= count * 3;
            if (!referenceSettled) {
                if (count >= REFERENCE_READINGS && steady) {
                    reference = mean;
                    referenceError = standardError;
                    referenceSettled = full;
                }
            } else if (steady) {
                double drift = mean - reference;
                // A drift is the difference of two means, each with its own noise.
                double noise = 3.0D * Math.sqrt(standardError * standardError + referenceError * referenceError);
                boolean quick = Math.abs(drift) > Math.max(count >= WINDOW / 2 ? HALF_WINDOW_SECONDS : QUICK_SECONDS,
                        noise);
                boolean fine = full && sinceFine >= WINDOW && Math.abs(drift) > Math.max(THRESHOLD_SECONDS, noise);
                if ((quick || fine) && Math.abs(drift) <= LIMIT_SECONDS) {
                    if (!quick) {
                        sinceFine = 0;
                    }
                    // The readings already taken were against the clock before the move: as if taken after it.
                    for (int i = 0; i < count; i++) {
                        readings[i] -= drift;
                    }
                    return drift;
                }
            }
        }
        if (full && !referenceSettled) {
            // No steady first mean yet (a starved mixer): try again with fresh readings.
            count = 0;
            next = 0;
        }
        return 0.0D;
    }
}

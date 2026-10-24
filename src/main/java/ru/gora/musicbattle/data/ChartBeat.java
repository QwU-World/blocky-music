package ru.gora.musicbattle.data;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Where beat 1 of a chart sits in its song, and the grid that follows from it.
 *
 * <p>Note times stay absolute ticks from the start of the audio. The offset only says where the first beat falls,
 * so the editor's grid and every visual that moves on the beat can line up with a song that has a lead-in; a chart
 * without the field reads as offset 0 and plays exactly as it did before the field existed. Readers in older builds
 * skip the field like any other key they do not know.
 *
 * <p>The grid arithmetic lives here rather than in the editor so that a check can run it without a screen, and so
 * that it stays in whole numbers: a cell's tick is rounded once, from the exact fraction, never from a rounded step.
 */
public final class ChartBeat {
    public static final String OFFSET_FIELD = "offsetMs";
    /**
     * One bar of four beats at the slowest BPM a chart may have (20). The grid repeats every bar, so a larger offset
     * never draws a different grid; the editor folds a longer lead-in into this range.
     */
    public static final int MAX_OFFSET_MS = 12_000;
    public static final int MS_PER_TICK = 50;
    private static final long MS_PER_MINUTE = 60_000L;
    private static final int BEATS_PER_BAR = 4;

    private ChartBeat() {
    }

    public static int clampOffsetMs(long offsetMs) {
        return (int) Math.max(0L, Math.min(MAX_OFFSET_MS, offsetMs));
    }

    /** Lenient like every other chart field: a missing or unreadable value is 0, an out-of-range one is clamped. */
    public static int readOffsetMs(JsonObject root) {
        JsonElement element = root == null ? null : root.get(OFFSET_FIELD);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            return 0;
        }
        double value = element.getAsDouble();
        return Double.isFinite(value) ? clampOffsetMs(Math.round(value)) : 0;
    }

    /** Written only when it is not 0, so a chart that does not use it stays byte for byte what it was. */
    public static void writeOffsetMs(JsonObject root, int offsetMs) {
        int clamped = clampOffsetMs(offsetMs);
        if (clamped != 0) {
            root.addProperty(OFFSET_FIELD, clamped);
        }
    }

    /** A sped-up battle divides every tick by its speed; beat 1 moves the same way. */
    public static int effectiveOffsetMs(int offsetMs, float speedMultiplier) {
        return Math.round(clampOffsetMs(offsetMs) / Math.max(1.0F, speedMultiplier));
    }

    public static double originTicks(int offsetMs) {
        return offsetMs / (double) MS_PER_TICK;
    }

    /** The track time to feed a phase that assumes beat 1 at 0; a negative value is the lead-in before beat 1. */
    public static double beatTicks(double trackTicks, int offsetMs) {
        return trackTicks - originTicks(offsetMs);
    }

    /**
     * "Beat 1 is here": a time anywhere in the song becomes the offset that puts a bar line on it, folded back by
     * whole bars because the grid before beat 1 continues backwards anyway.
     */
    public static int offsetForBeatAt(double timeMs, int bpm) {
        if (!Double.isFinite(timeMs) || timeMs <= 0.0D) {
            return 0;
        }
        double barMs = BEATS_PER_BAR * MS_PER_MINUTE / (double) clampBpm(bpm);
        double folded = timeMs - Math.floor(timeMs / barMs) * barMs;
        long rounded = Math.round(folded);
        return clampOffsetMs(rounded >= Math.round(barMs) ? 0 : rounded);
    }

    /** The whole tick nearest to cell {@code cell} of a grid with {@code cellsPerBeat} cells a beat (halves round up). */
    public static int cellTick(int offsetMs, int bpm, int cellsPerBeat, long cell) {
        long perBeat = (long) clampBpm(bpm) * Math.max(1, cellsPerBeat);
        long numerator = offsetMs * perBeat + MS_PER_MINUTE * cell;
        long denominator = MS_PER_TICK * perBeat;
        return (int) Math.floorDiv(2L * numerator + denominator, 2L * denominator);
    }

    /** The grid cell nearest to an exact time; cells before beat 1 are negative. */
    public static long nearestCell(int offsetMs, int bpm, int cellsPerBeat, double exactTick) {
        return Math.round((exactTick - originTicks(offsetMs)) * clampBpm(bpm) * Math.max(1, cellsPerBeat) * MS_PER_TICK
                / (double) MS_PER_MINUTE);
    }

    /**
     * Pages are counted from 0 at the song's start, but a page boundary falls on beat 1 and then every
     * {@code pageBeats} beats from it, so with an offset page 0 is the part of a page that holds the lead-in.
     */
    public static long pagesBeforeBeatOne(int offsetMs, int bpm, int pageBeats) {
        long span = MS_PER_MINUTE * pageBeats;
        return 1L - Math.ceilDiv((long) clampBpm(bpm) * (MS_PER_TICK / 2 - offsetMs), span);
    }

    /** A page's number counted from the page that starts on beat 1, negative for the lead-in. */
    public static long pageFromBeatOne(int offsetMs, int bpm, int pageBeats, long page) {
        return page - pagesBeforeBeatOne(offsetMs, bpm, pageBeats);
    }

    /** The first whole tick of a page: the one its exact start rounds to, never before the song. */
    public static int pageStart(int offsetMs, int bpm, int pageBeats, long page) {
        long b = clampBpm(bpm);
        long relative = pageFromBeatOne(offsetMs, bpm, pageBeats, page);
        long numerator = offsetMs * b + MS_PER_MINUTE * pageBeats * relative;
        return (int) Math.max(0L, Math.floorDiv(2L * numerator + MS_PER_TICK * b, 2L * MS_PER_TICK * b));
    }

    /** The page whose whole ticks include {@code tick}: the last page that starts at or before it. */
    public static long pageOfTick(int offsetMs, int bpm, int pageBeats, int tick) {
        long b = clampBpm(bpm);
        long span = MS_PER_MINUTE * pageBeats;
        long relative = Math.ceilDiv(b * ((long) MS_PER_TICK * Math.max(0, tick) + MS_PER_TICK / 2 - offsetMs), span) - 1L;
        return Math.max(0L, relative + pagesBeforeBeatOne(offsetMs, bpm, pageBeats));
    }

    /** The exact time at a page's left edge, in ticks; page 0 starts before the song when there is a lead-in. */
    public static double pageOriginTick(int offsetMs, int bpm, int pageBeats, long page) {
        long relative = pageFromBeatOne(offsetMs, bpm, pageBeats, page);
        return (offsetMs + relative * (double) MS_PER_MINUTE * pageBeats / clampBpm(bpm)) / MS_PER_TICK;
    }

    private static int clampBpm(int bpm) {
        return Math.max(20, Math.min(400, bpm));
    }
}

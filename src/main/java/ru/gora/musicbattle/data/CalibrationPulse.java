package ru.gora.musicbattle.data;

import java.util.List;

/** The visual marker and PCM click share this sample-exact calibration schedule. */
public final class CalibrationPulse {
    public static final int SAMPLE_RATE = 44100;
    public static final int FIRST_HIT_MS = 1200;
    public static final int PERIOD_MS = 1500;
    public static final int APPROACH_MS = 1000;

    private CalibrationPulse() { }

    public static long nearestBeat(double elapsedMs) {
        return Math.round((elapsedMs - FIRST_HIT_MS) / PERIOD_MS);
    }

    public static double tapErrorMs(double elapsedMs) {
        return elapsedMs - (FIRST_HIT_MS + nearestBeat(elapsedMs) * PERIOD_MS);
    }

    /** Four-tenths-second hit burst, beginning at the exact line crossing, never before it. */
    public static double hitBurst(double elapsedMs) {
        if (!Double.isFinite(elapsedMs) || elapsedMs < FIRST_HIT_MS) return 0;
        double age = (elapsedMs - FIRST_HIT_MS) % PERIOD_MS;
        return Math.max(0, 1 - age / 400.0);
    }

    public static double median(List<Double> samples) {
        if (samples.isEmpty()) return 0.0;
        double[] sorted = samples.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        int middle = sorted.length / 2;
        return sorted.length % 2 == 0 ? (sorted[middle - 1] + sorted[middle]) / 2 : sorted[middle];
    }

    public static short sample(long frame, int offsetMs) {
        long first = Math.round((FIRST_HIT_MS + Math.max(-500, Math.min(500, offsetMs))) * SAMPLE_RATE / 1000.0);
        if (frame < first) return 0;
        long within = (frame - first) % (SAMPLE_RATE * PERIOD_MS / 1000);
        double seconds = within / (double) SAMPLE_RATE;
        if (seconds >= 0.022) return 0;
        double envelope = Math.min(1, seconds / 0.001) * Math.pow(1.0 - seconds / 0.022, 2);
        return (short) Math.round(12000 * envelope * Math.sin(2 * Math.PI * 1700 * seconds));
    }
}

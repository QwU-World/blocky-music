package ru.gora.musicbattle.data;

/** Pure hardware-calibration math; offsets are milliseconds of real playback time. */
public final class CalibrationTiming {
    public static final int MAX_OFFSET_MS = 500;

    private CalibrationTiming() { }

    public static int clampOffsetMs(int milliseconds) {
        return Math.max(-MAX_OFFSET_MS, Math.min(MAX_OFFSET_MS, milliseconds));
    }

    /** Positive offset delays music while the visual playhead stays unchanged. */
    public static double sourceAudioSeconds(double sourceTicks, double pitch, int audioOffsetMs) {
        double safePitch = Double.isFinite(pitch) && pitch > 0.0D ? pitch : 1.0D;
        double safeTicks = Double.isFinite(sourceTicks) ? Math.max(0.0D, sourceTicks) : 0.0D;
        return safeTicks / 20.0D - clampOffsetMs(audioOffsetMs) * safePitch / 1000.0D;
    }

    /** Positive offset compensates a late input; negative timestamps remain negative. */
    public static int inputTick(double visualTicks, int inputOffsetMs) {
        double safeTicks = Double.isFinite(visualTicks) ? visualTicks : 0.0D;
        return (int) Math.floor(safeTicks - clampOffsetMs(inputOffsetMs) / 50.0D);
    }
}

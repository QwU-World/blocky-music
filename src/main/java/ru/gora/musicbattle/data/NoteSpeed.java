package ru.gora.musicbattle.data;

/** Shared approach and early judgement timing, in 50 ms game ticks. */
public final class NoteSpeed {
    private NoteSpeed() {}
    public static float clamp(float speed) {
        return Float.isFinite(speed) ? Math.max(0.5F, Math.min(20.0F, speed)) : 1.0F;
    }
    public static int leadTicks(float speed) {
        return Math.max(3, Math.round(54.0F / clamp(speed)));
    }
    public static int earlyHitWindowTicks(float speed) {
        return Math.max(1, Math.min(6, Math.round(leadTicks(speed) * 0.16F)));
    }
}

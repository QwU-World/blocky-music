package ru.gora.musicbattle.data;

/** Shared, clock-independent beat math for physical hops and their visual pose. */
public final class MusicBeatMath {
    public static final double HOP_HEIGHT = 0.48D;

    private MusicBeatMath() {
    }

    /** Phase zero is floor contact/takeoff; phase one half is the apex. */
    public static double phase(double trackTicks, double bpm) {
        return wrap(trackTicks * bpm / 1200.0D);
    }

    public static double phaseAt(double anchorPhase, long anchorGameTick, double gameTick, double bpm) {
        return wrap(anchorPhase + (gameTick - anchorGameTick) * bpm / 1200.0D);
    }

    public static double phaseDistance(double first, double second) {
        double distance = Math.abs(wrap(first) - wrap(second));
        return Math.min(distance, 1.0D - distance);
    }

    /** An absolute height, never accumulated frame by frame. */
    public static double hopHeight(double phase) {
        double sine = Math.sin(Math.PI * wrap(phase));
        return HOP_HEIGHT * sine * sine;
    }

    private static double wrap(double value) {
        return Double.isFinite(value) ? value - Math.floor(value) : 0.0D;
    }
}

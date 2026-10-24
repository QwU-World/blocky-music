package ru.gora.musicbattle.data;

import java.util.UUID;

/** Per-entity musical timing and height, shared by physics and the visual pose. */
public record SmileyDancePattern(double phaseOffset, double heightScale) {
    public static final double MAX_HOP_HEIGHT = MusicBeatMath.HOP_HEIGHT * 1.10D;

    public static SmileyDancePattern forEntity(UUID entityId) {
        // UUIDs are persisted and included in spawn packets. Mix both halves so
        // neither runtime entity IDs nor spawn order affect a dancer's style.
        long seed = entityId.getMostSignificantBits() ^ Long.rotateLeft(entityId.getLeastSignificantBits(), 23);
        seed = (seed ^ (seed >>> 30)) * 0xBF58476D1CE4E5B9L;
        seed = (seed ^ (seed >>> 27)) * 0x94D049BB133111EBL;
        seed ^= seed >>> 31;
        double offset = (seed & 3L) * 0.25D;
        double height = 0.85D + ((seed >>> 2) & 15L) * (0.25D / 15.0D);
        return new SmileyDancePattern(offset, height);
    }

    public double phase(double musicPhase) {
        double shifted = (Double.isFinite(musicPhase) ? musicPhase : 0.0D) + phaseOffset;
        return shifted - Math.floor(shifted);
    }

    public double hopHeight(double dancePhase) {
        return MusicBeatMath.hopHeight(dancePhase) * heightScale;
    }

    /** A hit plays exactly one hop; silence never wraps back into another hop. */
    public static double notePhase(double sinceHitTicks, double bpm) {
        if (!Double.isFinite(sinceHitTicks) || !Double.isFinite(bpm) || bpm <= 0 || sinceHitTicks < 0) return -1;
        double phase = sinceHitTicks * bpm / 1200.0;
        return phase < 1.0 ? phase : -1;
    }
}

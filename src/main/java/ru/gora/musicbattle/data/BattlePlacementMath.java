package ru.gora.musicbattle.data;

import java.util.List;
import java.util.Optional;

/** Pure geometry shared by bounded placement search and its regression checks. */
public final class BattlePlacementMath {
    private BattlePlacementMath() {
    }

    public record Bounds(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        public boolean usable() {
            return Double.isFinite(minX) && Double.isFinite(minY) && Double.isFinite(minZ)
                    && Double.isFinite(maxX) && Double.isFinite(maxY) && Double.isFinite(maxZ)
                    && maxX > minX && maxY > minY && maxZ > minZ
                    && maxX - minX <= 32 && maxY - minY <= 32 && maxZ - minZ <= 32;
        }

        public boolean fits(double bottom, double top, double west, double north, double east, double south) {
            return usable() && minY >= bottom && maxY <= top && minX >= west && maxX <= east
                    && minZ >= north && maxZ <= south;
        }

        public boolean intersects(Bounds other) {
            return maxX > other.minX && minX < other.maxX && maxY > other.minY && minY < other.maxY
                    && maxZ > other.minZ && minZ < other.maxZ;
        }
    }

    public record Candidate(double x, double y, double z, Bounds bounds, double cost) {
    }

    public record Pair(Candidate left, Candidate right) {
    }

    /** Distance from the arena centre to the opening frame of a battle camera, automatic or placed. */
    public static final double CAMERA_INTRO_DISTANCE = 1.2D;

    /** Rotation drift, in degrees, a held participant may keep before the server re-sends its own rotation. */
    public static final float ROTATION_CORRECTION_DEGREES = 2.5F;

    private static final double QUARTER_TURN = Math.PI / 4.0D;

    /** Unit horizontal direction away from the arena centre. */
    public record Direction(double x, double z) {
        public static Direction of(double x, double z) {
            double length = Math.sqrt(x * x + z * z);
            return length < 1.0E-9D ? new Direction(1.0D, 0.0D) : new Direction(x / length, z / length);
        }

        public Direction negated() {
            return new Direction(-x, -z);
        }

        public Direction rotated(double angle) {
            double cos = Math.cos(angle);
            double sin = Math.sin(angle);
            return new Direction(x * cos - z * sin, x * sin + z * cos);
        }
    }

    /**
     * Automatic camera directions away from the arena centre, in the order they may be taken.
     * Both sides of the perpendicular bisector come first, then their ±45° rotations, and only
     * then the two directions along the fighter line: a blocked bisector must fall back to a
     * still-centred diagonal before the camera ever ends up behind one of the fighters.
     */
    public static List<Direction> cameraDirections(double bisectorX, double bisectorZ) {
        Direction bisector = Direction.of(bisectorX, bisectorZ);
        Direction opposite = bisector.negated();
        return List.of(
                bisector,
                opposite,
                bisector.rotated(QUARTER_TURN),
                bisector.rotated(-QUARTER_TURN),
                opposite.rotated(QUARTER_TURN),
                opposite.rotated(-QUARTER_TURN),
                new Direction(-bisector.z(), bisector.x()),
                new Direction(bisector.z(), -bisector.x()));
    }

    /** A horizontal offset from the arena centre, in blocks; unlike {@link Direction} it has a length. */
    public record Offset(double x, double z) {
    }

    /**
     * Where a battle camera opens, as an offset from the arena centre: one short step toward the
     * camera's own position, so the two-second intro dollies out along the line from the fighters
     * to the shot. It is the same for the automatic camera and for a placed camera block, so no
     * battle opens beside one fighter and then drifts across the arena.
     *
     * <p>A camera block may stand closer to the centre than that step, or straight above it. It then
     * opens at its own horizontal spot instead of beyond itself, where the intro would fly backwards.</p>
     */
    public static Offset introOffset(double cameraOffsetX, double cameraOffsetZ) {
        double horizontal = Math.hypot(cameraOffsetX, cameraOffsetZ);
        if (horizontal <= CAMERA_INTRO_DISTANCE) {
            return new Offset(cameraOffsetX, cameraOffsetZ);
        }
        double scale = CAMERA_INTRO_DISTANCE / horizontal;
        return new Offset(cameraOffsetX * scale, cameraOffsetZ * scale);
    }

    /**
     * A held participant keeps small rotation drift; server-side rotation is only pushed back to
     * the client once the client has clearly turned away from the opponent.
     */
    public static boolean rotationDrifted(float currentYaw, float currentPitch, float targetYaw, float targetPitch) {
        return Math.abs(wrapDegrees(currentYaw - targetYaw)) > ROTATION_CORRECTION_DEGREES
                || Math.abs(wrapDegrees(currentPitch - targetPitch)) > ROTATION_CORRECTION_DEGREES;
    }

    private static float wrapDegrees(float degrees) {
        float wrapped = degrees % 360.0F;
        if (wrapped >= 180.0F) wrapped -= 360.0F;
        if (wrapped < -180.0F) wrapped += 360.0F;
        return wrapped;
    }

    public static Optional<Pair> reserve(List<Candidate> left, List<Candidate> right) {
        Pair best = null;
        double bestCost = Double.POSITIVE_INFINITY;
        for (Candidate a : left) {
            if (!a.bounds.usable() || !Double.isFinite(a.cost)) continue;
            for (Candidate b : right) {
                double cost = a.cost + b.cost;
                if (cost < bestCost && b.bounds.usable() && !a.bounds.intersects(b.bounds)) {
                    best = new Pair(a, b);
                    bestCost = cost;
                }
            }
        }
        return Optional.ofNullable(best);
    }
}

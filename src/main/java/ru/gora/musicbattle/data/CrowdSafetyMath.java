package ru.gora.musicbattle.data;

import java.util.function.Predicate;
import ru.gora.musicbattle.data.BattlePlacementMath.Bounds;

/** Bounded crowd motion and the lifetime of one displacement-caused falling episode. */
public final class CrowdSafetyMath {
    private static final double PUSH_SPEED = 0.18D;
    // Covers the stopping distance at vanilla's 0.91 horizontal air drag, including ice.
    private static final int PATH_STEPS = 12;

    private CrowdSafetyMath() { }

    public record Velocity(double x, double y, double z) { }

    public static Velocity velocity(double awayX, double awayZ, double verticalSpeed) {
        double length = Math.hypot(awayX, awayZ);
        if (!Double.isFinite(length) || !Double.isFinite(verticalSpeed) || length < 1.0E-8D) return null;
        return new Velocity(awayX / length * PUSH_SPEED, verticalSpeed, awayZ / length * PUSH_SPEED);
    }

    public static boolean canDisplace(boolean onGround, float fallDistance) {
        return onGround && fallDistance == 0.0F;
    }

    public static boolean canDisplace(boolean onGround, float fallDistance, boolean supportedDance) {
        return (onGround || supportedDance) && fallDistance == 0.0F;
    }

    /** Find an outward impulse whose entire stopping corridor fits, including a wall slide. */
    public static Velocity safePush(Bounds body, double awayX, double awayZ, double verticalSpeed,
                                    Predicate<Bounds> clear, Predicate<Bounds> supported) {
        Velocity desired = velocity(awayX, awayZ, verticalSpeed);
        if (desired == null) return null;
        Velocity best = null;
        double bestProgress = 0;
        for (int direction = 0; direction < 3; direction++) {
            double dx = direction == 2 ? 0 : desired.x();
            double dz = direction == 1 ? 0 : desired.z();
            for (int scale = PATH_STEPS; scale > 0; scale--) {
                double x = dx * scale / PATH_STEPS;
                double z = dz * scale / PATH_STEPS;
                double progress = x * desired.x() + z * desired.z();
                if (progress <= bestProgress + 1.0E-9D) continue;
                if (safePath(body, x, z, clear, supported)) {
                    best = new Velocity(x, verticalSpeed, z);
                    bestProgress = progress;
                    break;
                }
            }
        }
        return best;
    }

    public static boolean safePath(Bounds body, double dx, double dz,
                                   Predicate<Bounds> clear, Predicate<Bounds> supported) {
        if (!body.usable() || !Double.isFinite(dx) || !Double.isFinite(dz)
                || Math.hypot(dx, dz) > PUSH_SPEED + 1.0E-8D) return false;
        double endX = dx * PATH_STEPS;
        double endZ = dz * PATH_STEPS;
        Bounds swept = new Bounds(body.minX() + Math.min(0, endX), body.minY(), body.minZ() + Math.min(0, endZ),
                body.maxX() + Math.max(0, endX), body.maxY(), body.maxZ() + Math.max(0, endZ));
        if (!clear.test(swept)) return false;
        for (int step = 0; step <= PATH_STEPS; step++) {
            double x = dx * step;
            double z = dz * step;
            Bounds moved = new Bounds(body.minX() + x, body.minY(), body.minZ() + z,
                    body.maxX() + x, body.maxY(), body.maxZ() + z);
            if (!supported.test(moved)) return false;
        }
        return true;
    }

    public static final class FallGuard {
        private final long appliedTick;
        private boolean active = true;
        private boolean observedMotion;
        private double previousX;
        private double previousY;
        private double previousZ;
        private double previousVerticalSpeed;

        public FallGuard(long appliedTick) { this.appliedTick = appliedTick; }

        /** Called before the next crowd update, after vanilla has moved the entity. */
        public boolean update(long tick, boolean onGround, boolean inLiquid, boolean valid) {
            if (!valid || inLiquid || tick > appliedTick && onGround) active = false;
            return active;
        }

        /** Reject a different movement episode; downward limits follow velocity, not fall duration. */
        public boolean observeMotion(double x, double y, double z, double verticalSpeed, boolean levitating) {
            if (!active) return false;
            if (levitating || !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                    || !Double.isFinite(verticalSpeed)) {
                active = false;
                return false;
            }
            if (observedMotion) {
                double dy = y - previousY;
                // The applied horizontal impulse is 0.18. Allow ordinary small AI/collision
                // corrections, but not relocation; gravity may keep accelerating a long fall.
                double highestStep = Math.max(0.0D, previousVerticalSpeed) + 0.125D;
                double lowestStep = Math.min(0.0D, previousVerticalSpeed) - 0.16D;
                if (Math.hypot(x - previousX, z - previousZ) > 0.5D
                        || dy > highestStep || dy < lowestStep
                        || verticalSpeed > highestStep || verticalSpeed < lowestStep) {
                    active = false;
                    return false;
                }
            }
            observedMotion = true;
            previousX = x;
            previousY = y;
            previousZ = z;
            previousVerticalSpeed = verticalSpeed;
            return true;
        }

        /** Only the first fall impact is forgiven; every other damage source is untouched. */
        public boolean absorb(boolean fallDamage) {
            if (!active || !fallDamage) return false;
            active = false;
            return true;
        }
    }
}

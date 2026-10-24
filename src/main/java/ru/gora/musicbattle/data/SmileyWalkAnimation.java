package ru.gora.musicbattle.data;

/** Distance-driven client visual gait, independent of entity physics and music clocks. */
public final class SmileyWalkAnimation {
    private static final double STRIDE_BLOCKS = 0.64D;
    private static final double MIN_STEP = 0.0001D;
    private static final double MAX_STEP = 0.5D;
    private boolean initialized;
    private double previousX;
    private double previousZ;
    private double phase;
    private double startPhase;
    private double phaseStep;
    private float weight;

    public void tick(double x, double z, boolean grounded, boolean enabled) {
        if (!Double.isFinite(x) || !Double.isFinite(z)) {
            initialized = false;
            rest();
            return;
        }
        double distance = Math.hypot(x - previousX, z - previousZ);
        previousX = x;
        previousZ = z;
        if (!initialized) {
            initialized = true;
            rest();
            return;
        }
        // Keep a current baseline even while music, battle poses, falling or a teleport disable
        // this gait. Resuming must not replay the distance traveled in another animation mode.
        if (!grounded || !enabled || distance <= MIN_STEP || distance > MAX_STEP) {
            rest();
            return;
        }
        startPhase = phase;
        phaseStep = distance / STRIDE_BLOCKS;
        phase = wrap(startPhase + phaseStep);
        weight = (float) Math.min(1.0D, distance / 0.04D);
    }

    public Pose sample(float partialTick) {
        if (weight == 0) return Pose.REST;
        float partial = Float.isFinite(partialTick) ? Math.clamp(partialTick, 0, 1) : 0;
        float renderPhase = (float) wrap(startPhase + phaseStep * partial);
        return new Pose(renderPhase, weight, weight * 0.12F * (float) Math.sin(renderPhase * Math.PI));
    }

    private void rest() {
        phase = startPhase = phaseStep = 0;
        weight = 0;
    }

    private static double wrap(double value) {
        return value - Math.floor(value);
    }

    public record Pose(float phase, float weight, float height) {
        public static final Pose REST = new Pose(0, 0, 0);

        /** Same squash/stretch curve as the ambient and note-hop poses, with a walk weight. */
        public float verticalScale() {
            return 1.0F + weight * (-0.16F + 0.30F * (float) Math.sin(phase * Math.PI));
        }
    }
}

package ru.gora.musicbattle.data;

/**
 * Which fighter the automatic battle camera leans towards, and how gently it gets there.
 *
 * <p>When the two sides play separate parts they take turns, and a shot fixed on the middle leaves whoever is playing
 * at the edge of the frame. The camera therefore leans towards the side whose notes are coming up. It starts a little
 * before the phrase so it has arrived when the first note does, holds through short gaps so a call and its answer do
 * not shake it back and forth, and returns to the middle when both play or the music rests for a while. With shared
 * notes, or alone, the lean stays at zero and the shot is exactly the centred one.
 *
 * <p>The lean is eased with a limited speed and a softened start and stop, so a full swing from one side to the other
 * takes about three seconds and neither begins nor ends with a jolt. Testers found the earlier two-second swing, which
 * reached full speed within a few ticks, read as a jerk on every change of side. Pure arithmetic, checked by
 * tools/BattleFeelChecks.java and tools/BattleCameraChecks.java.
 */
public final class BattleCameraFocus {
    /** How long before a phrase the camera begins to turn, so it has arrived by the first note. */
    public static final int LOOKAHEAD_TICKS = 16;
    /** How long a phrase still counts after its last note ends. */
    public static final int TRAIL_TICKS = 8;
    /** A rest shorter than this keeps the lean; a longer one brings the shot back to the middle. */
    public static final int HOLD_TICKS = 50;
    /** The fastest the lean changes, per tick, across its range of -1 to 1. */
    public static final float MAX_SPEED = 0.04F;
    /** The share of the remaining way the lean wants to cover per tick; it slows down as it arrives. */
    private static final float EASE = 0.08F;
    /** How quickly the speed itself follows that wish: low, so a turn builds up and settles instead of snapping. */
    private static final float ACCELERATION = 0.12F;

    private float focus;
    private float velocity;
    private float target;
    private int idleTicks;

    /** True while a note from {@code tick} lasting {@code duration} ticks keeps its side on camera at {@code now}. */
    public static boolean keepsSideOnCamera(int tick, int duration, int now) {
        return tick - LOOKAHEAD_TICKS <= now && now <= tick + Math.max(0, duration) + TRAIL_TICKS;
    }

    /**
     * Advances one tick. {@code separate} is false for shared notes and solo play, which keep the centred shot;
     * otherwise the flags say which sides have notes on camera now.
     */
    public void tick(boolean separate, boolean leftActive, boolean rightActive) {
        if (!separate || leftActive && rightActive) {
            target = 0.0F;
            idleTicks = 0;
        } else if (leftActive != rightActive) {
            target = leftActive ? -1.0F : 1.0F;
            idleTicks = 0;
        } else if (++idleTicks > HOLD_TICKS) {
            target = 0.0F;
        }
        float desired = clamp((target - focus) * EASE, -MAX_SPEED, MAX_SPEED);
        velocity += (desired - velocity) * ACCELERATION;
        focus = clamp(focus + velocity, -1.0F, 1.0F);
        if (Math.abs(target - focus) < 1.0E-3F && Math.abs(velocity) < 1.0E-3F) {
            focus = target;
            velocity = 0.0F;
        }
    }

    /** -1 is the left fighter, 1 the right one, 0 the middle. */
    public float focus() {
        return focus;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}

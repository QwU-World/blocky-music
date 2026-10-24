package ru.gora.musicbattle.data;

/**
 * Runs the camera's one-shot placement clip off a clock that cannot move backwards.
 *
 * <p>The clip was timed by subtracting the game time the block was placed at from the game time now. On a server
 * neither end of that subtraction is steady: the client's game time is re-stamped from the server, the player who
 * placed the block stamps a predicted time before the authoritative one arrives, and the block entity can be synced
 * twice. Every one of those lands inside the clip's one second, and because it is a hold-at-the-end animation rather
 * than a loop, each jump shows as a stutter in the pose - which is what players saw.
 *
 * <p>So the server's clock is consulted exactly once, to work out how far into the clip this client is joining, and
 * the rest of the clip runs off the local monotonic clock. A later correction of a second or so is recognised as the
 * same placement and ignored; a genuinely new placement, which is always far further away, starts the clip again.
 *
 * <p>Pure arithmetic, checked without a game (tools/BattleReliabilityChecks.java).
 */
public final class PlacementAnimationClock {
    /** A re-stamp within this many ticks is the same placement being corrected, not a new one. */
    public static final long RESTAMP_TOLERANCE_TICKS = 40L;

    private PlacementAnimationClock() {
    }

    /** True when this stamp is a placement the client has not started drawing yet. */
    public static boolean shouldLatch(long latchedGameTime, long placementGameTime) {
        return latchedGameTime == Long.MIN_VALUE
                || Math.abs(placementGameTime - latchedGameTime) > RESTAMP_TOLERANCE_TICKS;
    }

    /** The local instant the clip must look as though it started at, given how old the placement already is. */
    public static long startNanos(long placementGameTime, long currentGameTime, long nowNanos) {
        long behindTicks = Math.max(0L, currentGameTime - placementGameTime);
        return nowNanos - behindTicks * 50_000_000L;
    }

    /** How far into the clip we are, in seconds. */
    public static float seconds(long startNanos, long nowNanos) {
        return Math.max(0.0F, (nowNanos - startNanos) / 1_000_000_000.0F);
    }
}

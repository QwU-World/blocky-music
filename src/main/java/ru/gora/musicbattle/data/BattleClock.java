package ru.gora.musicbattle.data;

/**
 * When a battle begins, expressed so that every player reaches tick zero at the same instant.
 *
 * <p>There used to be no agreed instant at all. The server sent "start", each client began its own count-in when the
 * packet happened to arrive, and the chart then followed whenever that machine's ogg actually began streaming. Packet
 * travel differs by tens of milliseconds, opening an audio file differs by hundreds, and the hit window is six ticks
 * either side - so two players were being judged against charts a few notes apart, which is what made their scores
 * disagree and their opponent's animations look wrong.
 *
 * <p>The server now picks one instant for the battle and tells each player how long to wait for it, subtracting that
 * player's own one-way delay. The lead has to be long enough for the slowest of them, including the time their client
 * needs to open the file, so it is deliberately generous; the audio is steered to the clock afterwards rather than
 * the other way round.
 *
 * <p>Pure arithmetic, so the rule is checked without a server (tools/BattleReliabilityChecks.java).
 */
public final class BattleClock {
    /** The shortest lead worth using: two seconds covers opening an ogg on a slow disk. */
    public static final int MIN_LEAD_MILLIS = 2000;
    /** Headroom above the slowest player's delay, for the same reason. */
    public static final int LEAD_HEADROOM_MILLIS = 500;
    /** However close a player is, they still get a moment to draw the count-in. */
    public static final int MIN_START_DELAY_MILLIS = 250;
    /** A wild latency sample must not push a battle a second into the future. */
    public static final int MAX_ONE_WAY_MILLIS = 1000;

    private BattleClock() {
    }

    /** Half a round trip, clamped into something a battle can be planned around. */
    public static int oneWayMillis(int roundTripMillis) {
        if (roundTripMillis <= 0) {
            return 0;
        }
        return Math.min(roundTripMillis / 2, MAX_ONE_WAY_MILLIS);
    }

    /** How long after the decision the battle starts, for everyone. */
    public static int leadMillis(int worstOneWayMillis) {
        return Math.max(MIN_LEAD_MILLIS, Math.max(0, worstOneWayMillis) + LEAD_HEADROOM_MILLIS);
    }

    /**
     * How long this particular player is told to wait: the lead, less the time the packet spends reaching them.
     * A player further away waits less, and they arrive together.
     */
    public static int startDelayMillis(int leadMillis, int oneWayMillis) {
        return Math.max(MIN_START_DELAY_MILLIS, leadMillis - Math.max(0, oneWayMillis));
    }

    /** Ticks from the epoch, as a whole number; negative during the count-in. */
    public static int ticksSince(long epochNanos, long nowNanos) {
        return (int) Math.floorDiv(nowNanos - epochNanos, 50_000_000L);
    }
}

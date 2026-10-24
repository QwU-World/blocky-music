package ru.gora.musicbattle.data;

/**
 * Moving a battle forward when its players vote to skip a long rest.
 *
 * <p>Both the server and each player's client read the battle's tick from one agreed instant (see
 * {@link BattleClock}) rather than counting ticks. The skip used to write the target tick straight into the counter,
 * and the very next tick read the time from that unchanged instant again: the server fell back to the rest it had
 * just left, and the client's once-a-second drift correction seeked the music back with it. The jump lasted a
 * moment and the vote came round again. A skip therefore has to move the instant itself.
 *
 * <p>Pure arithmetic, so the rule is checked without a server (tools/BattleFeelChecks.java).
 */
public final class BattleSkipClock {
    private static final long TICK_NANOS = 50_000_000L;

    private BattleSkipClock() {
    }

    /**
     * The server's epoch after a skip from {@code fromTick} to {@code toTick}: earlier by exactly the skipped ticks,
     * so the clock keeps its sub-tick phase and the next reading lands on the target. A skip never goes backwards.
     */
    public static long serverEpochAfterSkip(long epochNanos, int fromTick, int toTick) {
        return epochNanos - Math.max(0, toTick - fromTick) * TICK_NANOS;
    }

    /**
     * A client's epoch after it hears of a skip to {@code targetTick}. The server was already on that tick when it
     * sent the news, and the packet took {@code oneWayMillis} to arrive, so the client places itself that far past
     * the target - the same correction the start of the battle makes - instead of trailing the server for the rest
     * of the song. {@code nowNanos} is the instant the client's clock is read at, a held pause included.
     */
    public static long clientEpochAfterSkip(long nowNanos, int targetTick, int oneWayMillis) {
        return nowNanos - Math.max(0, targetTick) * TICK_NANOS - Math.max(0, oneWayMillis) * 1_000_000L;
    }
}

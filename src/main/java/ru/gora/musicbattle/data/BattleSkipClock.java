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
 * <p>The server tells its players the exact jump, from one tick to another, and each client moves its own instant by
 * the same amount ({@link #clientEpochAfterJump}). Where the song has to start again after the jump is a separate
 * matter: see {@link MusicStartAlignment}.
 *
 * <p>Pure arithmetic, so the rule is checked without a server (tools/BattleFeelChecks.java,
 * tools/BattleSkipSyncChecks.java).
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
     * A client's epoch after the server jumped from {@code fromTick} to {@code toTick}: moved by exactly the ticks the
     * server skipped, the same move {@link #serverEpochAfterSkip} makes on the server.
     *
     * <p>The client used to rebuild its clock from the target instead: the arrival instant, less the target, less its
     * one-way delay. That assumes the server stood exactly at the start of the target tick when it sent the news. It
     * stood somewhere inside that tick, and the client takes the packet a frame or so later, so a hidden test client
     * came out of every skip 0.76 ticks behind the server where it had been 0.12 ahead, and the bot's hits arrived
     * early. Moving the client by the same whole number of ticks keeps whatever lead the start of the battle gave it,
     * however late the news arrives.
     */
    public static long clientEpochAfterJump(long epochNanos, int fromTick, int toTick) {
        return serverEpochAfterSkip(epochNanos, fromTick, toTick);
    }
}

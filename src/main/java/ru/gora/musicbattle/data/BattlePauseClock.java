package ru.gora.musicbattle.data;

/**
 * The battle's own time, which stands still while a single-player game sits in its pause menu.
 *
 * <p>The integrated server stops ticking for as long as the client shows a pause screen in a world that is not open
 * to LAN, and the client already held its chart and music back by the length of the pause. The server, though, read
 * its battle tick from {@link System#nanoTime()}: when the menu closed it found itself the whole pause further on,
 * resolved every note in that gap as a miss for the player and a hit for the mob at once, and stayed that far ahead
 * of the music for the rest of the song. Both sides now read the battle time from here instead.
 *
 * <p>Only the client writes ({@link #observe}, every client tick, from {@code Minecraft#isPaused}), and only an
 * integrated server shares its JVM, so a dedicated server - or a client on someone else's server, which never counts
 * as paused - sees plain wall time. While a pause is held the time is frozen at the instant it began; that also covers
 * the moment after the menu closes when the server may tick before the client has noted the resume, so the server
 * picks up on the very tick it stopped at rather than jumping.
 *
 * <p>Pure arithmetic over the instants it is given, so the rule is checked without a game (tools/BattleFeelChecks.java).
 */
public final class BattlePauseClock {
    /** The clock of this JVM: written by the client thread, read by the integrated server thread. */
    public static final BattlePauseClock GAME = new BattlePauseClock();

    private long pausedSinceNanos;
    private boolean paused;
    private long totalPausedNanos;

    /** Records whether the game is paused at {@code nowNanos}; repeating the same state changes nothing. */
    public synchronized void observe(boolean pausedNow, long nowNanos) {
        if (pausedNow && !paused) {
            paused = true;
            pausedSinceNanos = nowNanos;
        } else if (!pausedNow && paused) {
            paused = false;
            totalPausedNanos += Math.max(0L, nowNanos - pausedSinceNanos);
        }
    }

    /** Battle time at the wall instant {@code nowNanos}: wall time less every pause, frozen during one. */
    public synchronized long now(long nowNanos) {
        long effective = paused ? Math.min(nowNanos, pausedSinceNanos) : nowNanos;
        return effective - totalPausedNanos;
    }

    public long now() {
        return now(System.nanoTime());
    }

    public synchronized boolean paused() {
        return paused;
    }
}

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
 * <p>Only the client writes ({@link #observe}: every client tick, from {@code Minecraft#isPaused}, and at the instants
 * the pause menu stops and restarts the sounds, ClientBattleController#onGamePauseChanged), and only an integrated
 * server shares its JVM, so a dedicated server - or a client on someone else's server, which never counts as paused -
 * sees plain wall time. While a pause is held the time is frozen at the instant it began; that also covers
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
    /** A short hold of the battle time with the game running (holdBriefly): until when; 0 for none. */
    private long heldUntilNanos;

    /** Records whether the game is paused at {@code nowNanos}; repeating the same state changes nothing. */
    public synchronized void observe(boolean pausedNow, long nowNanos) {
        endHoldBy(nowNanos);
        if (pausedNow && !paused) {
            paused = true;
            pausedSinceNanos = nowNanos;
        } else if (pausedNow && heldUntilNanos != 0L) {
            // The game pauses during a short hold: the hold becomes the pause, from where it began.
            heldUntilNanos = 0L;
        } else if (!pausedNow && paused && heldUntilNanos == 0L) {
            paused = false;
            totalPausedNanos += Math.max(0L, nowNanos - pausedSinceNanos);
        }
    }

    /** Battle time at the wall instant {@code nowNanos}: wall time less every pause, frozen during one. */
    public synchronized long now(long nowNanos) {
        if (paused && heldUntilNanos != 0L && nowNanos >= heldUntilNanos) {
            // A short hold runs out by itself, whoever reads the time first.
            return nowNanos - (heldUntilNanos - pausedSinceNanos) - totalPausedNanos;
        }
        long effective = paused ? Math.min(nowNanos, pausedSinceNanos) : nowNanos;
        return effective - totalPausedNanos;
    }

    /**
     * Holds the battle time from {@code fromNanos} for {@code lengthNanos} while the game runs on, as the battle song's
     * drift correction does when the song has fallen behind the chart (SongDriftAnchor): the time stands still and then
     * goes on, never back. Nothing happens while the game is paused or another hold is on.
     */
    public synchronized void holdBriefly(long fromNanos, long lengthNanos) {
        if (paused || lengthNanos <= 0L) {
            return;
        }
        paused = true;
        pausedSinceNanos = fromNanos;
        heldUntilNanos = fromNanos + lengthNanos;
    }

    /**
     * Moves the battle time forward by {@code nanos} at once, as the drift correction does when the song has run ahead
     * of the chart. Never backwards; nothing happens while the game is paused or a hold is on.
     */
    public synchronized void advance(long nanos) {
        if (paused || nanos <= 0L) {
            return;
        }
        totalPausedNanos -= nanos;
    }

    /** Ends a short hold at {@code nowNanos}, or when it ran out if that was earlier. */
    public synchronized void endHold(long nowNanos) {
        if (paused && heldUntilNanos != 0L) {
            long end = Math.max(pausedSinceNanos, Math.min(nowNanos, heldUntilNanos));
            paused = false;
            totalPausedNanos += end - pausedSinceNanos;
            heldUntilNanos = 0L;
        }
    }

    private void endHoldBy(long nowNanos) {
        if (paused && heldUntilNanos != 0L && nowNanos >= heldUntilNanos) {
            paused = false;
            totalPausedNanos += heldUntilNanos - pausedSinceNanos;
            heldUntilNanos = 0L;
        }
    }

    public long now() {
        return now(System.nanoTime());
    }

    /** Whether a pause, or a short hold (until the next observe after it has run out), is on. */
    public synchronized boolean paused() {
        return paused;
    }

    /** Whether the time stands still for a short hold rather than for the game's pause. */
    public synchronized boolean holding() {
        return paused && heldUntilNanos != 0L;
    }

    /** The instant the pause now held began; meaningful only while {@link #paused()}. */
    public synchronized long pausedSince() {
        return pausedSinceNanos;
    }
}

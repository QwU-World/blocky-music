package ru.gora.musicbattle.data;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Pure rules of the music vote before a battle: how long it lasts, how its countdown reads and when
 * it closes before the time is up. The server and the vote screen of the same build share these
 * numbers, so the full length never has to travel over the network; only the time left does.
 */
public final class VoteTiming {
    public static final int TICKS_PER_SECOND = 20;
    public static final int VOTE_SECONDS = 60;
    public static final int VOTE_TICKS = VOTE_SECONDS * TICKS_PER_SECOND;
    /** The countdown turns warm once it shows this many seconds or fewer. */
    public static final int FINAL_SECONDS = 10;
    /** Floating-point noise above a whole second must not show the next second up. */
    private static final double SECOND_EPSILON = 1.0E-6D;

    private VoteTiming() { }

    /** Ticks left after {@code elapsedTicks} of voting, between zero and the whole vote. */
    public static int remainingTicks(int elapsedTicks) {
        return clampRemainingTicks((long) VOTE_TICKS - elapsedTicks);
    }

    public static int clampRemainingTicks(long ticks) {
        return (int) Math.max(0L, Math.min(VOTE_TICKS, ticks));
    }

    /**
     * Local countdown between two server syncs: the ticks the server reported, minus the ticks this
     * client has counted since. A clock that seems to run backwards never adds time back.
     */
    public static int countdown(int syncedRemainingTicks, int ticksSinceSync) {
        return clampRemainingTicks((long) clampRemainingTicks(syncedRemainingTicks) - Math.max(0, ticksSinceSync));
    }

    /**
     * A fresh server figure only ever brings the countdown down. The server is authoritative, but a
     * packet is already a little old when it lands, so a higher figure would make the timer tick up.
     */
    public static int resync(int shownRemainingTicks, int serverRemainingTicks) {
        return Math.min(clampRemainingTicks(shownRemainingTicks), clampRemainingTicks(serverRemainingTicks));
    }

    /** Whole seconds the countdown shows, rounded up: 0:00 appears only once the time is really gone. */
    public static int displaySeconds(double remainingSeconds) {
        if (!(remainingSeconds > SECOND_EPSILON)) {
            return 0;
        }
        return (int) Math.min(Integer.MAX_VALUE, Math.ceil(remainingSeconds - SECOND_EPSILON));
    }

    /** {@code m:ss}, for example 1:00 or 0:07. */
    public static String format(double remainingSeconds) {
        int total = displaySeconds(remainingSeconds);
        int seconds = total % 60;
        return total / 60 + (seconds < 10 ? ":0" : ":") + seconds;
    }

    public static String formatTicks(double remainingTicks) {
        return format(remainingTicks / TICKS_PER_SECOND);
    }

    /** Share of the vote still left: 1 when it opens, 0 when it has ended. */
    public static float fraction(double remainingTicks) {
        if (!(remainingTicks > 0.0D)) {
            return 0.0F;
        }
        return (float) Math.min(1.0D, remainingTicks / VOTE_TICKS);
    }

    /** True while the countdown shows ten seconds or fewer, 0:00 included. */
    public static boolean finalStretch(double remainingSeconds) {
        return displaySeconds(remainingSeconds) <= FINAL_SECONDS;
    }

    /**
     * Everyone whose choice the vote waits for. When a player takes part, that is every player in
     * the battle (both in a duel, the one player solo or against a mob) plus a scripted opponent
     * whose choice the server records itself as the vote opens. A battle of two mobs has no player
     * in it: then only the organizer who opened its settings chooses, and without an organizer
     * nobody does. An organizer is never a voter in a battle that has players.
     */
    public static <T> Set<T> expectedVoters(T leftPlayer, T rightPlayer, T scriptedOpponent, T organizer) {
        Set<T> voters = new LinkedHashSet<>();
        if (leftPlayer == null && rightPlayer == null) {
            if (organizer != null) {
                voters.add(organizer);
            }
            return Collections.unmodifiableSet(voters);
        }
        if (leftPlayer != null) {
            voters.add(leftPlayer);
        }
        if (rightPlayer != null) {
            voters.add(rightPlayer);
        }
        if (scriptedOpponent != null) {
            voters.add(scriptedOpponent);
        }
        return Collections.unmodifiableSet(voters);
    }

    /**
     * The vote may close early once every expected voter has a recorded choice. A second choice by
     * the same voter replaces the first and counts once; a choice by anyone else fills no seat. A
     * vote nobody is expected to cast never closes early, only on time.
     */
    public static boolean everyoneVoted(Collection<?> expectedVoters, Collection<?> recordedVoters) {
        return expectedVoters != null && !expectedVoters.isEmpty()
                && recordedVoters != null && recordedVoters.containsAll(expectedVoters);
    }
}

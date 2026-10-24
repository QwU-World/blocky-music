import java.util.List;
import java.util.Set;
import ru.gora.musicbattle.data.VoteTiming;

/**
 * Exercises the vote rules the server and the vote screen both call: the length, the m:ss countdown,
 * the progress bar share, the client re-sync that never adds time back, and who a vote waits for in
 * every kind of battle before it may close early.
 */
public final class VoteTimingChecks {
    private static int checks;

    public static void main(String[] args) {
        length();
        formatting();
        fraction();
        countdownAndResync();
        earlyEnd();
        System.out.println("Vote timing checks passed: " + checks);
        System.out.println("NOT COVERED: the network round trip and the drawn screen; those need Minecraft and two clients");
    }

    private static void length() {
        check(VoteTiming.VOTE_SECONDS == 60, "the vote lasts 60 seconds");
        check(VoteTiming.VOTE_TICKS == 60 * 20, "the vote lasts 1200 server ticks");
        check(VoteTiming.remainingTicks(0) == VoteTiming.VOTE_TICKS, "a new vote has the whole minute left");
        check(VoteTiming.remainingTicks(1199) == 1, "one tick left before the end");
        check(VoteTiming.remainingTicks(1200) == 0, "no time left at the end");
        check(VoteTiming.remainingTicks(5000) == 0, "never below zero after the end");
        check(VoteTiming.remainingTicks(-40) == VoteTiming.VOTE_TICKS, "never above the whole vote");
        check(VoteTiming.remainingTicks(Integer.MIN_VALUE) == VoteTiming.VOTE_TICKS, "no overflow on a broken clock");
    }

    private static void formatting() {
        equal(VoteTiming.format(60), "1:00", "60 s");
        equal(VoteTiming.format(59.99), "1:00", "59.99 s rounds up, so the first second still reads 1:00");
        equal(VoteTiming.format(59.0), "0:59", "59 s");
        equal(VoteTiming.format(10), "0:10", "10 s");
        equal(VoteTiming.format(9.5), "0:10", "9.5 s rounds up");
        equal(VoteTiming.format(9.0), "0:09", "9 s");
        equal(VoteTiming.format(7), "0:07", "seconds are zero-padded");
        equal(VoteTiming.format(0.01), "0:01", "0:00 only once the time is really gone");
        equal(VoteTiming.format(0), "0:00", "0 s");
        equal(VoteTiming.format(-0.5), "0:00", "negative time reads 0:00");
        equal(VoteTiming.format(-90), "0:00", "far negative time reads 0:00");
        equal(VoteTiming.format(Double.NaN), "0:00", "NaN reads 0:00");
        equal(VoteTiming.format(10.0000000001), "0:10", "floating-point noise above a whole second");
        equal(VoteTiming.format(125), "2:05", "minutes and padded seconds");
        equal(VoteTiming.formatTicks(VoteTiming.VOTE_TICKS), "1:00", "a full vote in ticks");
        equal(VoteTiming.formatTicks(1181), "1:00", "59.05 s in ticks");
        equal(VoteTiming.formatTicks(1180), "0:59", "59 s in ticks");
        equal(VoteTiming.formatTicks(1), "0:01", "one tick still reads 0:01");
        equal(VoteTiming.formatTicks(0), "0:00", "zero ticks");

        check(!VoteTiming.finalStretch(60), "a full minute is not the final stretch");
        check(!VoteTiming.finalStretch(10.05), "0:11 is not warm yet");
        check(VoteTiming.finalStretch(10), "0:10 turns warm");
        check(VoteTiming.finalStretch(9.5), "9.5 s is warm");
        check(VoteTiming.finalStretch(0) && VoteTiming.finalStretch(-3), "0:00 stays warm");

        String previous = null;
        for (int ticks = VoteTiming.VOTE_TICKS; ticks >= -20; ticks--) {
            String shown = VoteTiming.formatTicks(ticks);
            check(shown.matches("\\d+:[0-5]\\d"), "m:ss shape at " + ticks + " ticks: " + shown);
            if (previous != null) {
                check(seconds(shown) <= seconds(previous), "the countdown never reads higher as ticks fall: " + ticks);
                check(seconds(previous) - seconds(shown) <= 1, "the countdown never skips a second: " + ticks);
            }
            previous = shown;
        }
    }

    private static void fraction() {
        near(VoteTiming.fraction(VoteTiming.VOTE_TICKS), 1.0F, "full bar when the vote opens");
        near(VoteTiming.fraction(VoteTiming.VOTE_TICKS / 2.0), 0.5F, "half bar halfway");
        near(VoteTiming.fraction(0), 0.0F, "empty bar at the end");
        near(VoteTiming.fraction(-1), 0.0F, "never below empty");
        near(VoteTiming.fraction(VoteTiming.VOTE_TICKS * 3.0), 1.0F, "never above full");
        near(VoteTiming.fraction(Double.NaN), 0.0F, "NaN is empty");
        near(VoteTiming.fraction(Double.POSITIVE_INFINITY), 1.0F, "infinity is full, not NaN");
        float previous = 2.0F;
        for (int ticks = VoteTiming.VOTE_TICKS; ticks >= 0; ticks--) {
            float share = VoteTiming.fraction(ticks);
            check(share >= 0.0F && share <= 1.0F, "bar share in bounds at " + ticks);
            check(share <= previous, "the bar only ever empties: " + ticks);
            previous = share;
        }
    }

    private static void countdownAndResync() {
        check(VoteTiming.countdown(1200, 0) == 1200, "no ticks counted since the sync");
        check(VoteTiming.countdown(1200, 200) == 1000, "counts down on the client's own ticks");
        check(VoteTiming.countdown(100, 500) == 0, "never below zero between syncs");
        check(VoteTiming.countdown(100, -20) == 100, "a clock running backwards adds no time");
        check(VoteTiming.countdown(9000, 0) == VoteTiming.VOTE_TICKS, "a bogus server figure is capped at the whole vote");
        check(VoteTiming.resync(800, 700) == 700, "a lower server figure pulls the countdown down");
        check(VoteTiming.resync(800, 900) == 800, "a higher server figure never makes the timer count back up");
        check(VoteTiming.resync(800, 0) == 0, "a closed vote ends the countdown at once");
        check(VoteTiming.resync(800, -5) == 0, "a negative server figure reads as closed");

        // A client whose ticks run faster or slower than the server's, with packets arriving late,
        // shows a countdown that only falls, stays in bounds and ends at zero once the vote closes.
        for (int clientTicksPerHundred : new int[]{70, 100, 130}) {
            for (int latency : new int[]{0, 3, 17}) {
                int synced = VoteTiming.remainingTicks(0);
                int syncedAt = 0;
                int shown = synced;
                int closeAt = 900;
                for (int clientTick = 0; clientTick <= 1600; clientTick++) {
                    int serverTick = clientTick * 100 / clientTicksPerHundred;
                    int sentAt = serverTick - latency;
                    int local = VoteTiming.countdown(synced, clientTick - syncedAt);
                    if (sentAt > 0 && sentAt % 150 == 0) {
                        synced = VoteTiming.resync(local, VoteTiming.remainingTicks(sentAt));
                        syncedAt = clientTick;
                    }
                    if (sentAt >= closeAt) {
                        synced = VoteTiming.resync(local, 0);
                        syncedAt = clientTick;
                    }
                    int now = VoteTiming.countdown(synced, clientTick - syncedAt);
                    String context = clientTicksPerHundred + "% clock, " + latency + " ticks late, tick " + clientTick;
                    check(now >= 0 && now <= VoteTiming.VOTE_TICKS, "countdown in bounds: " + context);
                    check(now <= shown, "countdown never counts back up: " + context);
                    if (sentAt >= closeAt) {
                        check(now == 0, "countdown is zero once the close has arrived: " + context);
                    }
                    shown = now;
                }
            }
        }
    }

    private static void earlyEnd() {
        String left = "left", right = "right", npc = "npc", organizer = "organizer", stranger = "stranger";

        Set<String> duel = VoteTiming.expectedVoters(left, right, null, null);
        equal(duel, Set.of(left, right), "a duel waits for both players");
        check(!VoteTiming.everyoneVoted(duel, List.of()), "duel: nobody has chosen");
        check(!VoteTiming.everyoneVoted(duel, List.of(left)), "duel: the second player has not chosen");
        check(!VoteTiming.everyoneVoted(duel, List.of(right)), "duel: the first player has not chosen");
        check(!VoteTiming.everyoneVoted(duel, List.of(left, left)), "duel: a duplicate vote fills one seat, not two");
        check(!VoteTiming.everyoneVoted(duel, List.of(left, stranger)), "duel: an outsider's vote fills no seat");
        check(VoteTiming.everyoneVoted(duel, List.of(left, right)), "duel: both chose, close now");
        check(VoteTiming.everyoneVoted(duel, List.of(right, left, left)), "duel: order and repeats do not matter");

        Set<String> versusMob = VoteTiming.expectedVoters(left, null, null, null);
        equal(versusMob, Set.of(left), "against a mob only the player chooses");
        check(!VoteTiming.everyoneVoted(versusMob, List.of()), "against a mob: the player has not chosen");
        check(VoteTiming.everyoneVoted(versusMob, List.of(left)), "against a mob: the player chose, close now");
        check(VoteTiming.everyoneVoted(versusMob, List.of(left, left)), "against a mob: a repeated vote still closes");

        Set<String> solo = VoteTiming.expectedVoters(left, null, null, null);
        equal(solo, Set.of(left), "solo waits for the one player");
        check(!VoteTiming.everyoneVoted(solo, List.of(stranger)), "solo: someone else's vote does not close it");
        check(VoteTiming.everyoneVoted(solo, List.of(left)), "solo: the player chose, close now");

        Set<String> versusNpc = VoteTiming.expectedVoters(left, null, npc, null);
        equal(versusNpc, Set.of(left, npc), "against the test Rap God master: the player and the NPC");
        check(!VoteTiming.everyoneVoted(versusNpc, List.of(npc)), "against the NPC: its vote at open does not close the vote");
        check(VoteTiming.everyoneVoted(versusNpc, List.of(npc, left)), "against the NPC: the player chose too, close now");

        Set<String> mobs = VoteTiming.expectedVoters(null, null, null, organizer);
        equal(mobs, Set.of(organizer), "two mobs: only the organizer chooses");
        check(!VoteTiming.everyoneVoted(mobs, List.of()), "two mobs: the organizer has not chosen");
        check(VoteTiming.everyoneVoted(mobs, List.of(organizer)), "two mobs: the organizer chose, close now");

        Set<String> nobody = VoteTiming.expectedVoters(null, null, null, null);
        check(nobody.isEmpty(), "two mobs without an organizer: nobody chooses");
        check(!VoteTiming.everyoneVoted(nobody, List.of()), "a vote nobody casts only closes on time");
        check(!VoteTiming.everyoneVoted(nobody, List.of(stranger)), "a vote nobody casts ignores stray votes");

        equal(VoteTiming.expectedVoters(left, right, null, organizer), Set.of(left, right),
                "an organizer is not a voter in a battle with players");
        check(!VoteTiming.everyoneVoted(null, List.of(left)) && !VoteTiming.everyoneVoted(duel, null),
                "missing inputs never close a vote");
        try {
            duel.add(stranger);
            check(false, "the expected voters cannot be changed by a caller");
        } catch (UnsupportedOperationException expected) {
            check(true, "the expected voters cannot be changed by a caller");
        }
    }

    private static int seconds(String clock) {
        String[] parts = clock.split(":");
        return Integer.parseInt(parts[0]) * 60 + Integer.parseInt(parts[1]);
    }

    private static void equal(Object actual, Object expected, String message) {
        check(expected.equals(actual), message + ": expected " + expected + ", got " + actual);
    }

    private static void near(float actual, float expected, String message) {
        check(Math.abs(actual - expected) < 1.0E-6F, message + ": expected " + expected + ", got " + actual);
    }

    private static void check(boolean ok, String message) {
        checks++;
        if (!ok) {
            throw new AssertionError(message);
        }
    }
}

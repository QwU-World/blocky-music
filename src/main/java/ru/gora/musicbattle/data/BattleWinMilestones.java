package ru.gora.musicbattle.data;

import java.util.ArrayList;
import java.util.List;

/**
 * The advancements a player earns by the number of battles they have won, and the counts that earn them.
 *
 * <p>Checked against the count after every recorded win with "at least", not "exactly": a player who already had more
 * wins when a milestone was added, or whose count jumped past one, still gets it with their next win.
 *
 * <p>Pure, so tools/BattleRulesChecks.java checks the thresholds without a server.
 */
public final class BattleWinMilestones {
    /** One milestone: the wins it takes and the advancement file under {@code data/music_battle/advancement/}. */
    public record Milestone(int wins, String advancement) {
    }

    public static final List<Milestone> ALL = List.of(
            new Milestone(25, "headliner"),
            new Milestone(42, "meaning_of_life"),
            new Milestone(67, "six_seven"));

    private BattleWinMilestones() {
    }

    /** Every milestone a player with {@code wins} wins has reached, lowest first. */
    public static List<String> reached(int wins) {
        List<String> reached = new ArrayList<>();
        for (Milestone milestone : ALL) {
            if (wins >= milestone.wins()) {
                reached.add(milestone.advancement());
            }
        }
        return reached;
    }
}

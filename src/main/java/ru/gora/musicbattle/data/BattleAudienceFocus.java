package ru.gora.musicbattle.data;

/** Select only a living participant of the spectator's audible battle, never a nearby bystander. */
public final class BattleAudienceFocus {
    private BattleAudienceFocus() { }

    public static int choose(boolean participant, boolean audibleBattle, double leftDistance, double rightDistance) {
        if (participant || !audibleBattle) return -1;
        boolean left = Double.isFinite(leftDistance) && leftDistance >= 0;
        boolean right = Double.isFinite(rightDistance) && rightDistance >= 0;
        if (!left && !right) return -1;
        return left && (!right || leftDistance <= rightDistance) ? 0 : 1;
    }
}

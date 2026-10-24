package ru.gora.musicbattle.data;

/** Physical lane input must drive animation independently of whether a note was hittable. */
public final class BattleAnimationInputChecks {
    public static void main(String[] args) {
        int mask = BattleAnimationInput.updateHeldMask(0, 1, true);
        require(mask == 0b0010, "No-note press did not start its animation hold");
        mask = BattleAnimationInput.updateHeldMask(mask, 3, true);
        require(mask == 0b1010, "A chord press lost one of its held lanes");
        mask = BattleAnimationInput.updateHeldMask(mask, 1, false);
        require(mask == 0b1000, "Releasing an old lane stopped the current animation");
        mask = BattleAnimationInput.updateHeldMask(mask, 3, false);
        require(mask == 0, "Releasing the current lane did not return to idle");
        require(BattleAnimationInput.updateHeldMask(0, -50, true) == 1,
                "Malformed lanes were not safely clamped");
        System.out.println("Battle animation input regression PASS");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

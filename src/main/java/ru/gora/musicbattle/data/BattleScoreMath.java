package ru.gora.musicbattle.data;

/** Shared score calculations used by both client-side prediction and the server authority. */
public final class BattleScoreMath {
    private static final int OSU_COMBO_DIVISOR = 25;

    private BattleScoreMath() {
    }

    /**
     * Applies the combo-only part of osu!standard ScoreV1 to a judgement value.
     *
     * <p>The original formula also includes beatmap difficulty and gameplay-mod
     * multipliers. Blocky Music has neither equivalent, so both are treated as 1.
     * The combo term is based on the combo that existed before this hit.</p>
     */
    public static int comboAdjustedHitScore(int hitValue, int comboBeforeHit) {
        int safeHitValue = Math.max(0, hitValue);
        int comboMultiplier = Math.max(0, comboBeforeHit - 1);
        long numerator = (long) safeHitValue * (OSU_COMBO_DIVISOR + comboMultiplier);
        return (int) Math.min(Integer.MAX_VALUE, numerator / OSU_COMBO_DIVISOR);
    }

    public static int saturatingAdd(int currentScore, int scoreGain) {
        long total = (long) Math.max(0, currentScore) + Math.max(0, scoreGain);
        return (int) Math.min(Integer.MAX_VALUE, total);
    }
}

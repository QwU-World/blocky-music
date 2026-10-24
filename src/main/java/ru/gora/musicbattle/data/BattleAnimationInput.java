package ru.gora.musicbattle.data;

import net.minecraft.util.Mth;

/** Keeps physical lane holds separate from whether a gameplay note was hittable. */
public final class BattleAnimationInput {
    private BattleAnimationInput() {
    }

    public static int updateHeldMask(int currentMask, int lane, boolean down) {
        int laneBit = 1 << Mth.clamp(lane, 0, 3);
        return down ? (currentMask | laneBit) & 15 : currentMask & ~laneBit & 15;
    }
}

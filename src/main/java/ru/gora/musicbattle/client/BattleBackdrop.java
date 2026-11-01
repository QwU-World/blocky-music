package ru.gora.musicbattle.client;

/**
 * The two standard layers a battle draws between the world and its panels: the black veil that dims the world, and
 * the black bars along the top and bottom of the screen. Neither is the CINEMATIC_BARS effect; a beatmap can switch
 * both off in the FX editor, and that switch still wins over everything here.
 *
 * <p>Both used to be fixed: {@code argb(70, 0, 0, 0)} over the world and two bars of alpha 185. Players asked to set
 * them themselves, so the battle HUD, the settings preview and anything else that draws them asks this class how, with
 * the player's settings. The defaults reproduce the old look exactly, alpha for alpha.
 *
 * <p>The amounts are fractions of opaque black, shown in the settings as whole percents. A setting shown as 0% draws
 * nothing at all rather than a veil of alpha 1, so what the slider says is what the screen gets.
 *
 * <p>The dimming stops at {@link #MAX_DIMMING}, 90%. That already darkens the world to a tenth of its brightness, so
 * the notes stand out as clearly as on black, but the stage is still there as a faint picture: the opponent's moves,
 * the battle camera's flight and the beatmap's own effects in the world remain readable. A fully black screen would
 * erase all of that, which a player who slid the setting to its end could easily take for a broken beatmap.
 *
 * <p>Pure arithmetic and a fill callback, without Minecraft, so the tools checks can run it as it is.
 */
final class BattleBackdrop {
    /** The dimming the HUD always drew, {@code argb(70, 0, 0, 0)}, which is still where a new player starts. */
    static final int STANDARD_DIMMING_ALPHA = 70;
    /** The bars' alpha the HUD always drew, 185 of 255. */
    static final int STANDARD_BAR_ALPHA = 185;
    /** The bars' heights in the battle HUD's own units (see {@link BattleHudScale}). */
    static final int TOP_BAR = 36;
    static final int BOTTOM_BAR = 30;
    static final float DEFAULT_DIMMING = STANDARD_DIMMING_ALPHA / 255.0F;
    static final float DEFAULT_BAR_OPACITY = STANDARD_BAR_ALPHA / 255.0F;
    /** The darkest dimming a player can choose; the class comment says why it is not 100%. */
    static final float MAX_DIMMING = 0.9F;

    /** {@code GuiGraphics::fill} in the game; a recording rectangle list in the checks. */
    @FunctionalInterface
    interface Fill {
        void fill(int left, int top, int right, int bottom, int argb);
    }

    private BattleBackdrop() {
    }

    /** The whole percent a fraction is shown as: rounded half up, never negative, 0 for anything that is not a number. */
    static int percent(float fraction) {
        return fraction > 0.0F ? Math.round(Math.min(fraction, 1.0F) * 100.0F) : 0;
    }

    /** The world dimming as an alpha, 0 when the setting shows 0% or is not a number. */
    static int dimmingAlpha(float dimming) {
        float clamped = clampDimming(dimming);
        return percent(clamped) == 0 ? 0 : Math.round(clamped * 255.0F);
    }

    /** The bars' alpha, 0 when they are switched off or their opacity shows 0%. */
    static int barAlpha(boolean shown, float opacity) {
        float clamped = clampOpacity(opacity);
        return !shown || percent(clamped) == 0 ? 0 : Math.round(clamped * 255.0F);
    }

    /** A dimming read from anywhere (the config file, a slider) as one the player could have chosen; NaN is none. */
    static float clampDimming(float dimming) {
        return dimming > 0.0F ? Math.min(dimming, MAX_DIMMING) : 0.0F;
    }

    /** A bar opacity as one the player could have chosen; NaN is none. */
    static float clampOpacity(float opacity) {
        return opacity > 0.0F ? Math.min(opacity, 1.0F) : 0.0F;
    }

    /** Veils a {@code width} by {@code height} canvas, or draws nothing at all at alpha 0. */
    static void dim(Fill fill, int width, int height, int alpha) {
        if (alpha > 0 && width > 0 && height > 0) {
            fill.fill(0, 0, width, height, Math.min(alpha, 255) << 24);
        }
    }

    /** The two bars along the canvas's top and bottom edges, or nothing at alpha 0. */
    static void bars(Fill fill, int width, int height, int alpha) {
        if (alpha <= 0 || width <= 0 || height <= 0) {
            return;
        }
        int colour = Math.min(alpha, 255) << 24;
        fill.fill(0, 0, width, TOP_BAR, colour);
        fill.fill(0, height - BOTTOM_BAR, width, height, colour);
    }
}

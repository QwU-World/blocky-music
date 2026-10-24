package ru.gora.musicbattle.client.ui;

import net.minecraft.client.gui.GuiGraphics;

/**
 * Progress rings and pies (osu! lazer's CircularProgress on the card preview button), batched with the other
 * SDF shapes. The sweep starts at 12 o'clock and runs clockwise with straight ends. Render thread only.
 */
public final class Ring {
    /** osu!'s preview ring: InnerRadius 0.2 of the outer radius. */
    public static final float OSU_PREVIEW_THICKNESS = 0.2F;
    private static final int[] SCRATCH = new int[4];

    private Ring() {
    }

    /**
     * @param thickness ring thickness as a fraction of the outer radius (0.2 thin ring, 1 pie)
     * @param progress  0..1 of a full turn
     */
    public static void draw(GuiGraphics g, float cx, float cy, float outerRadius, float thickness, float progress, int argb) {
        if (!(progress > 0) || !(outerRadius > 0) || (argb >>> 24) == 0) {
            return;
        }
        if (!UiSdf.available()) {
            // Flat fallback: a bar under where the ring would be, as wide as the progress.
            float p = Math.min(1.0F, progress);
            float barHeight = Math.max(1.0F, outerRadius * 0.2F);
            RoundedRect.fallbackFill(g, cx - outerRadius, cy + outerRadius - barHeight,
                    cx - outerRadius + outerRadius * 2 * p, cy + outerRadius, argb);
            return;
        }
        UiSdf.queue(g, cx, cy, outerRadius, outerRadius, argb, SdfMath.ring(SCRATCH, outerRadius, thickness, progress));
    }

    /** A full ring (track behind a progress ring). */
    public static void track(GuiGraphics g, float cx, float cy, float outerRadius, float thickness, int argb) {
        if (!UiSdf.available()) {
            return;
        }
        draw(g, cx, cy, outerRadius, thickness, 1.0F, argb);
    }
}

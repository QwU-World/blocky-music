package ru.gora.musicbattle.client.ui;

import net.minecraft.client.gui.GuiGraphics;

/**
 * Anti-aliased rounded rectangles, strokes, pills and soft shadows, batched through {@link UiSdf}. Float
 * coordinates in GUI units. Without the shader (load failure or "Simple UI") they degrade to square vanilla
 * fills, so a screen stays usable. Render thread only.
 */
public final class RoundedRect {
    private static final int[] SCRATCH = new int[4];

    private RoundedRect() {
    }

    public static void fill(GuiGraphics g, float x, float y, float w, float h, float radius, int argb) {
        if (!(w > 0) || !(h > 0) || (argb >>> 24) == 0) {
            return;
        }
        if (!UiSdf.available()) {
            fallbackFill(g, x, y, x + w, y + h, argb);
            return;
        }
        float hw = w * 0.5F;
        float hh = h * 0.5F;
        UiSdf.queue(g, x + hw, y + hh, hw, hh, argb, SdfMath.fill(SCRATCH, hw, hh, radius));
    }

    /** A stroke of {@code width} drawn inside the rectangle's edge. */
    public static void stroke(GuiGraphics g, float x, float y, float w, float h, float radius, float width, int argb) {
        if (!(w > 0) || !(h > 0) || !(width > 0) || (argb >>> 24) == 0) {
            return;
        }
        if (!UiSdf.available()) {
            float t = Math.max(1.0F, width);
            fallbackFill(g, x, y, x + w, y + t, argb);
            fallbackFill(g, x, y + h - t, x + w, y + h, argb);
            fallbackFill(g, x, y + t, x + t, y + h - t, argb);
            fallbackFill(g, x + w - t, y + t, x + w, y + h - t, argb);
            return;
        }
        float hw = w * 0.5F;
        float hh = h * 0.5F;
        UiSdf.queue(g, x + hw, y + hh, hw, hh, argb, SdfMath.stroke(SCRATCH, hw, hh, radius, width));
    }

    /** Fully rounded ends: status pills, difficulty dots, scrollbar thumbs. */
    public static void pill(GuiGraphics g, float x, float y, float w, float h, int argb) {
        fill(g, x, y, w, h, Math.min(w, h) * 0.5F, argb);
    }

    /** A filled circle. */
    public static void circle(GuiGraphics g, float cx, float cy, float radius, int argb) {
        fill(g, cx - radius, cy - radius, radius * 2, radius * 2, radius, argb);
    }

    /**
     * A soft shadow behind a rectangle: full {@code argb} inside, fading to nothing over {@code softness}
     * units either side of the edge (osu! card hover shadow). Nothing is drawn without the shader.
     */
    public static void shadow(GuiGraphics g, float x, float y, float w, float h, float radius, float softness, int argb) {
        if (!(w > 0) || !(h > 0) || (argb >>> 24) == 0 || !UiSdf.available()) {
            return;
        }
        float hw = w * 0.5F;
        float hh = h * 0.5F;
        UiSdf.queue(g, x + hw, y + hh, hw, hh, argb, SdfMath.shadow(SCRATCH, hw, hh, radius, softness));
    }

    static void fallbackFill(GuiGraphics g, float x1, float y1, float x2, float y2, int argb) {
        int left = Math.round(x1);
        int top = Math.round(y1);
        int right = Math.round(x2);
        int bottom = Math.round(y2);
        if (right > left && bottom > top) {
            g.fill(left, top, right, bottom, argb);
            UiDrawStats.fallback();
        }
    }
}

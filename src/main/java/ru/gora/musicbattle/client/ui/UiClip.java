package ru.gora.musicbattle.client.ui;

import net.minecraft.client.gui.GuiGraphics;

/**
 * Scissor regions for toolkit drawing. Toolkit shapes, icons and text are queued, not drawn, and vanilla only
 * flushes on a scissor change in "managed" mode, so a queued shape would otherwise be drawn with whatever
 * scissor is active at the next flush. These helpers flush first. The rectangle is in screen GUI units and
 * ignores the PoseStack, like vanilla's scissor. Hit-test against your own viewport rectangles:
 * {@code GuiGraphics.containsPointInScissor} checks the outermost region, not the innermost.
 */
public final class UiClip {
    private UiClip() {
    }

    public static void push(GuiGraphics g, float x1, float y1, float x2, float y2) {
        g.flush();
        g.enableScissor((int) Math.floor(x1), (int) Math.floor(y1), (int) Math.ceil(x2), (int) Math.ceil(y2));
    }

    public static void pop(GuiGraphics g) {
        g.flush();
        g.disableScissor();
    }
}

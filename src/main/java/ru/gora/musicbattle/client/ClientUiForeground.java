package ru.gora.musicbattle.client;

import com.mojang.blaze3d.vertex.PoseStack;

/** Editor controls and modal dialogs must stay above the preview's +400 HUD FX layer. */
final class ClientUiForeground {
    private static final float FOREGROUND_Z = 800.0F;
    /**
     * A tip over the editors' controls. Not FOREGROUND_Z: vanilla draws an EditBox's text with a drop shadow and moves
     * the text itself 0.03 forward (Font.SHADOW_OFFSET), so a card in the controls' own layer let a number box's value
     * print through it. Under MODAL_Z, so a dialog drawn over the editor still covers the editor's tip.
     */
    private static final float TIP_Z = 1000.0F;
    private static final float MODAL_Z = 1200.0F;

    private ClientUiForeground() {
    }

    public static void render(PoseStack poses, Runnable flush, Runnable draw) {
        renderAt(poses, flush, draw, FOREGROUND_Z);
    }

    /** A tooltip card over controls drawn with {@link #render}, glyphs and their shadows included. */
    public static void renderTip(PoseStack poses, Runnable flush, Runnable draw) {
        renderAt(poses, flush, draw, TIP_Z);
    }

    /** Called after the parent screen restores its pose; stays above its editor controls. */
    public static void renderModal(PoseStack poses, Runnable flush, Runnable draw) {
        renderAt(poses, flush, draw, MODAL_Z);
    }

    private static void renderAt(PoseStack poses, Runnable flush, Runnable draw, float depth) {
        // Finish parent glyph/widget batches before changing the layer. No global depth
        // disable: preview text and normal controls retain their existing render types.
        flush.run();
        poses.pushPose();
        poses.translate(0.0F, 0.0F, depth);
        try {
            draw.run();
        } finally {
            try {
                flush.run();
            } finally {
                poses.popPose();
            }
        }
    }
}

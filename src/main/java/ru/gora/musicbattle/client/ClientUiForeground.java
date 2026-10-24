package ru.gora.musicbattle.client;

import com.mojang.blaze3d.vertex.PoseStack;

/** Editor controls and modal dialogs must stay above the preview's +400 HUD FX layer. */
final class ClientUiForeground {
    private static final float FOREGROUND_Z = 800.0F;
    private static final float MODAL_Z = 1200.0F;

    private ClientUiForeground() {
    }

    public static void render(PoseStack poses, Runnable flush, Runnable draw) {
        renderAt(poses, flush, draw, FOREGROUND_Z);
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

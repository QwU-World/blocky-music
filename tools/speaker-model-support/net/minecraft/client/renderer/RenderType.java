package net.minecraft.client.renderer;

import net.minecraft.resources.ResourceLocation;

/** Only the GPU buffer selector is stubbed; model loading and vertex transforms run unchanged. */
public final class RenderType {
    public static RenderType entityCutoutNoCull(ResourceLocation texture) {
        String id = texture.toString();
        if (!id.equals("music_battle:textures/block/speaker.png")
                && !id.equals("music_battle:textures/block/dj_panel.png")
                && !id.equals("music_battle:textures/item/speaker_wire.png")) {
            throw new AssertionError("Unexpected custom model texture: " + texture);
        }
        return new RenderType();
    }
}

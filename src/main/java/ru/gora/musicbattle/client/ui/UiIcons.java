package ru.gora.musicbattle.client.ui;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;
import ru.gora.musicbattle.MusicBattleMod;

/**
 * Tinted Lucide icons from three atlases (16, 32 and 64 px cells), sampled LINEAR and batched: all icons of
 * one tier queued between flushes cost one draw call. Uses vanilla's position_tex_color shader, so icons
 * need no custom shader. Render thread only.
 */
public final class UiIcons {
    private static final RenderType[] TYPES = new RenderType[UiIcon.TIERS.length];

    static {
        for (int i = 0; i < UiIcon.TIERS.length; i++) {
            int tier = UiIcon.TIERS[i];
            ResourceLocation atlas = atlas(tier);
            TYPES[i] = RenderType.create(
                    "music_battle_ui_icons_" + tier,
                    DefaultVertexFormat.POSITION_TEX_COLOR,
                    VertexFormat.Mode.QUADS,
                    4096,
                    RenderType.CompositeState.builder()
                            .setShaderState(new RenderStateShard.ShaderStateShard(GameRenderer::getPositionTexColorShader))
                            .setTextureState(new RenderStateShard.TextureStateShard(atlas, true, false))
                            .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                            .setDepthTestState(RenderStateShard.LEQUAL_DEPTH_TEST)
                            .setCullState(RenderStateShard.NO_CULL)
                            .createCompositeState(false));
        }
    }

    private UiIcons() {
    }

    public static ResourceLocation atlas(int tier) {
        return MusicBattleMod.id("textures/gui/workshop/icons_" + tier + ".png");
    }

    static boolean isIconType(RenderType type) {
        for (RenderType candidate : TYPES) {
            if (candidate == type) {
                return true;
            }
        }
        return false;
    }

    /** Queues an icon of {@code size} GUI units at (x, y), tinted with {@code argb}. Nothing is drawn until a flush. */
    public static void draw(GuiGraphics g, UiIcon icon, float x, float y, float size, int argb) {
        if (icon == null || !(size > 0) || (argb >>> 24) < 4) {
            return;
        }
        double pixels = size * Minecraft.getInstance().getWindow().getGuiScale();
        int tier = UiIcon.tierFor(pixels);
        int tierIndex = tier == 16 ? 0 : tier == 32 ? 1 : 2;
        float u0 = icon.uv(tier, 0);
        float v0 = icon.uv(tier, 1);
        float u1 = icon.uv(tier, 2);
        float v1 = icon.uv(tier, 3);
        VertexConsumer vc = g.bufferSource().getBuffer(TYPES[tierIndex]);
        Matrix4f pose = g.pose().last().pose();
        vc.addVertex(pose, x, y + size, 0.0F).setUv(u0, v1).setColor(argb);
        vc.addVertex(pose, x + size, y + size, 0.0F).setUv(u1, v1).setColor(argb);
        vc.addVertex(pose, x + size, y, 0.0F).setUv(u1, v0).setColor(argb);
        vc.addVertex(pose, x, y, 0.0F).setUv(u0, v0).setColor(argb);
    }

    /** Centred on (cx, cy). */
    public static void drawCentered(GuiGraphics g, UiIcon icon, float cx, float cy, float size, int argb) {
        draw(g, icon, cx - size * 0.5F, cy - size * 0.5F, size, argb);
    }
}

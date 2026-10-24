package ru.gora.musicbattle.client.ui;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;
import org.joml.Matrix4f;
import ru.gora.musicbattle.MusicBattleMod;

/**
 * A texture region clipped to a rectangle with per-corner radii, dimmed and faded (covers, headers, avatars).
 * One immediate draw per image, like a vanilla blit, since every cover is its own texture. The call flushes
 * queued shapes and text first so painter's order holds.
 *
 * <p>Without the shader the image is drawn square with vanilla's position_tex_color shader (dim is applied
 * to the vertex colour instead). Render thread only.
 */
public final class RoundedImage {
    public static final ResourceLocation SHADER_ID = MusicBattleMod.id("ui_rounded_image");
    private static volatile ShaderInstance shader;

    private RoundedImage() {
    }

    public static ShaderInstance shader() {
        return shader;
    }

    public static boolean available() {
        return shader != null && !UiSdf.forceFallback();
    }

    /** From {@code ClientModEvents.registerShaders}. Never throws. */
    public static void register(RegisterShadersEvent event) {
        shader = null;
        UiShaderGuard.register("ui_rounded_image",
                () -> new ShaderInstance(event.getResourceProvider(), SHADER_ID, DefaultVertexFormat.POSITION_TEX_COLOR),
                created -> event.registerShader(created, loaded -> shader = loaded),
                failure -> UiSdf.logFailure("ui_rounded_image", failure));
    }

    /** The whole texture, one radius for every corner. */
    public static void draw(GuiGraphics g, ResourceLocation texture, float x, float y, float w, float h,
                            float radius, float dim, float alpha) {
        int colour = Palette.withAlpha(0xFFFFFF, alpha);
        draw(g, texture, x, y, w, h, 0, 0, 1, 1, radius, radius, radius, radius, dim, colour, colour);
    }

    /**
     * The full form.
     *
     * @param u0 v0 u1 v1       texture region (u1 &lt; u0 mirrors)
     * @param rTl rTr rBr rBl   corner radii in GUI units: top-left, top-right, bottom-right, bottom-left
     * @param dim               0..1 towards black (osu! dims a playing thumbnail to 60 %)
     * @param leftArgb rightArgb vertex colours along the left and right edges: tint and alpha, for osu!'s
     *                           card background that fades from 0 to 20 % left to right
     */
    public static void draw(GuiGraphics g, ResourceLocation texture, float x, float y, float w, float h,
                            float u0, float v0, float u1, float v1, float rTl, float rTr, float rBr, float rBl,
                            float dim, int leftArgb, int rightArgb) {
        if (texture == null || !(w > 0) || !(h > 0) || ((leftArgb | rightArgb) >>> 24) == 0) {
            return;
        }
        g.flush();
        ShaderInstance active = available() ? shader : GameRenderer.getPositionTexColorShader();
        if (active == null) {
            return;
        }
        int left = leftArgb;
        int right = rightArgb;
        if (active != shader) {
            left = dimmed(leftArgb, dim);
            right = dimmed(rightArgb, dim);
        } else {
            active.safeGetUniform("RectSize").set(w, h);
            active.safeGetUniform("Radii").set(rTl, rTr, rBr, rBl);
            active.safeGetUniform("UvRect").set(u0, v0, u1, v1);
            active.safeGetUniform("Dim").set(Math.max(0.0F, Math.min(1.0F, dim)));
        }
        ShaderInstance bound = active;
        RenderSystem.setShaderTexture(0, texture);
        RenderSystem.setShader(() -> bound);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        Matrix4f pose = g.pose().last().pose();
        BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        builder.addVertex(pose, x, y + h, 0.0F).setUv(u0, v1).setColor(left);
        builder.addVertex(pose, x + w, y + h, 0.0F).setUv(u1, v1).setColor(right);
        builder.addVertex(pose, x + w, y, 0.0F).setUv(u1, v0).setColor(right);
        builder.addVertex(pose, x, y, 0.0F).setUv(u0, v0).setColor(left);
        BufferUploader.drawWithShader(builder.buildOrThrow());
        RenderSystem.disableBlend();
        UiDrawStats.image();
    }

    private static int dimmed(int argb, float dim) {
        float keep = 1.0F - Math.max(0.0F, Math.min(1.0F, dim));
        int r = Math.round((argb >> 16 & 255) * keep);
        int gr = Math.round((argb >> 8 & 255) * keep);
        int b = Math.round((argb & 255) * keep);
        return (argb & 0xFF000000) | r << 16 | gr << 8 | b;
    }
}

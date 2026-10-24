package ru.gora.musicbattle.client.ui;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;
import org.joml.Matrix4f;
import ru.gora.musicbattle.MusicBattleMod;

/**
 * Batched signed-distance-field shapes for GUI screens: one padded quad per shape, all shapes in one draw
 * call until something else (text, an image, a vanilla fill) ends the batch. The encoding is in
 * {@link SdfMath}; drawing goes through {@link RoundedRect} and {@link Ring}.
 *
 * <p>Nothing is drawn when {@link #queue} returns: the quads sit in the GuiGraphics buffer source until the
 * next {@code graphics.flush()} or render-type switch. Flush before changing the scissor or blitting.
 */
public final class UiSdf {
    public static final VertexFormat FORMAT = VertexFormat.builder()
            .add("Position", VertexFormatElement.POSITION)
            .add("Color", VertexFormatElement.COLOR)
            .add("UV0", VertexFormatElement.UV0)
            .add("UV1", VertexFormatElement.UV1)
            .add("UV2", VertexFormatElement.UV2)
            .build();

    public static final ResourceLocation SHADER_ID = MusicBattleMod.id("ui_sdf");

    private static volatile ShaderInstance shader;
    private static volatile boolean forceFallback;
    private static boolean failureLogged;

    public static final RenderType SHAPES = RenderType.create(
            "music_battle_ui_sdf",
            FORMAT,
            VertexFormat.Mode.QUADS,
            262_144,
            RenderType.CompositeState.builder()
                    .setShaderState(new RenderStateShard.ShaderStateShard(UiSdf::shader))
                    .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                    .setDepthTestState(RenderStateShard.LEQUAL_DEPTH_TEST)
                    .setCullState(RenderStateShard.NO_CULL)
                    .createCompositeState(false));

    private UiSdf() {
    }

    /** The loaded shader, or null when it failed to load (drawing then uses vanilla fills). */
    public static ShaderInstance shader() {
        return shader;
    }

    /** True when shapes are drawn with the SDF shader; false means flat, square vanilla fills. */
    public static boolean available() {
        return shader != null && !forceFallback;
    }

    /** Forces the vanilla fallback, for a "Simple UI" setting or to preview the fallback look. */
    public static void setForceFallback(boolean force) {
        forceFallback = force;
    }

    public static boolean forceFallback() {
        return forceFallback;
    }

    /** From {@code ClientModEvents.registerShaders}. Never throws; a broken shader leaves {@link #shader()} null. */
    public static void register(RegisterShadersEvent event) {
        shader = null;
        UiShaderGuard.register("ui_sdf",
                () -> new ShaderInstance(event.getResourceProvider(), SHADER_ID, FORMAT),
                created -> event.registerShader(created, loaded -> shader = loaded),
                failure -> logFailure("ui_sdf", failure));
    }

    static void logFailure(String name, Throwable failure) {
        if (!failureLogged) {
            failureLogged = true;
            MusicBattleMod.LOGGER.warn("Blocky Music UI shader '{}' failed to load; the Workshop falls back to flat, square shapes.",
                    name, failure);
        } else {
            MusicBattleMod.LOGGER.warn("Blocky Music UI shader '{}' failed to load: {}", name, failure.toString());
        }
    }

    /**
     * Queues one quad centred on (cx, cy) with half extents (halfW, halfH) and packed parameters from
     * {@link SdfMath}. The caller has already checked {@link #available()}.
     */
    static void queue(GuiGraphics graphics, float cx, float cy, float halfW, float halfH, int argb, int[] params) {
        VertexConsumer vc = graphics.bufferSource().getBuffer(SHAPES);
        Matrix4f pose = graphics.pose().last().pose();
        float pad = SdfMath.quadPadding(params);
        float ex = halfW + pad;
        float ey = halfH + pad;
        int a0 = params[0];
        int a1 = params[1];
        int b0 = params[2];
        int b1 = params[3];
        // Same winding as GuiGraphics.fill: (right,bottom) (right,top) (left,top) (left,bottom).
        vc.addVertex(pose, cx + ex, cy + ey, 0.0F).setColor(argb).setUv(ex, ey).setUv1(a0, a1).setUv2(b0, b1);
        vc.addVertex(pose, cx + ex, cy - ey, 0.0F).setColor(argb).setUv(ex, -ey).setUv1(a0, a1).setUv2(b0, b1);
        vc.addVertex(pose, cx - ex, cy - ey, 0.0F).setColor(argb).setUv(-ex, -ey).setUv1(a0, a1).setUv2(b0, b1);
        vc.addVertex(pose, cx - ex, cy + ey, 0.0F).setColor(argb).setUv(-ex, ey).setUv1(a0, a1).setUv2(b0, b1);
        UiDrawStats.shape();
    }
}

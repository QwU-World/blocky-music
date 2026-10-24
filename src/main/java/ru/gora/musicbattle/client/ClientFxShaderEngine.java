package ru.gora.musicbattle.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;
import net.neoforged.neoforge.common.NeoForgeConfig;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import ru.gora.musicbattle.MusicBattleMod;
import ru.gora.musicbattle.data.TrackEffect;
import ru.gora.musicbattle.data.TrackEffectSettings;

/** Ordered fullscreen shader passes used inside the regular FX layer stack. */
public final class ClientFxShaderEngine {
    private static ShaderInstance fishEyeShader;
    private static ShaderInstance pulseShader;
    private static ShaderInstance vignetteShader;
    private static ShaderInstance colorCorrectionShader;
    private static ShaderInstance objectOutlineShader;
    private static ShaderInstance objectDepthCopyShader;
    private static ShaderInstance modelMaskShader;
    private static TextureTarget scratchTarget;
    private static TextureTarget modelMaskTarget;
    private static TextureTarget objectOutlineColorTarget;
    private static TextureTarget objectOutlineDepthTarget;
    private static boolean scratchFailed;
    private static boolean modelMaskFailed;
    private static boolean objectOutlineBlockTargetFailed;
    private static boolean objectOutlineBlocksCaptured;
    private static ObjectOutlineCapture objectOutlineSourceOverride;
    private static final int MAX_OBJECT_OUTLINE_WARPS = 8;

    /** Separate scratch images keep a small monitor from resizing the main-view FX every frame. */
    static final class TargetBuffers {
        private TextureTarget scratch;
        private TextureTarget modelMask;
        private boolean scratchFailed;
        private boolean modelMaskFailed;

        void release() {
            if (scratch != null) scratch.destroyBuffers();
            if (modelMask != null) modelMask.destroyBuffers();
            scratch = null;
            modelMask = null;
            scratchFailed = false;
            modelMaskFailed = false;
        }
    }

    static void withTargetBuffers(TargetBuffers buffers, Runnable render) {
        TextureTarget previousScratch = scratchTarget;
        TextureTarget previousModelMask = modelMaskTarget;
        boolean previousScratchFailed = scratchFailed;
        boolean previousModelMaskFailed = modelMaskFailed;
        scratchTarget = buffers.scratch;
        modelMaskTarget = buffers.modelMask;
        scratchFailed = buffers.scratchFailed;
        modelMaskFailed = buffers.modelMaskFailed;
        try {
            render.run();
        } finally {
            buffers.scratch = scratchTarget;
            buffers.modelMask = modelMaskTarget;
            buffers.scratchFailed = scratchFailed;
            buffers.modelMaskFailed = modelMaskFailed;
            scratchTarget = previousScratch;
            modelMaskTarget = previousModelMask;
            scratchFailed = previousScratchFailed;
            modelMaskFailed = previousModelMaskFailed;
        }
    }

    private ClientFxShaderEngine() {
    }

    public static void registerShaders(RegisterShadersEvent event) throws IOException {
        event.registerShader(new ShaderInstance(event.getResourceProvider(), MusicBattleMod.id("fish_eye"),
                DefaultVertexFormat.BLIT_SCREEN), loaded -> fishEyeShader = loaded);
        event.registerShader(new ShaderInstance(event.getResourceProvider(), MusicBattleMod.id("pulse"),
                DefaultVertexFormat.BLIT_SCREEN), loaded -> pulseShader = loaded);
        event.registerShader(new ShaderInstance(event.getResourceProvider(), MusicBattleMod.id("vignette"),
                DefaultVertexFormat.BLIT_SCREEN), loaded -> vignetteShader = loaded);
        event.registerShader(new ShaderInstance(event.getResourceProvider(), MusicBattleMod.id("color_correction"),
                DefaultVertexFormat.BLIT_SCREEN), loaded -> colorCorrectionShader = loaded);
        event.registerShader(new ShaderInstance(event.getResourceProvider(), MusicBattleMod.id("object_outline"),
                DefaultVertexFormat.BLIT_SCREEN), loaded -> objectOutlineShader = loaded);
        event.registerShader(new ShaderInstance(event.getResourceProvider(), MusicBattleMod.id("object_depth_copy"),
                DefaultVertexFormat.BLIT_SCREEN), loaded -> objectDepthCopyShader = loaded);
        event.registerShader(new ShaderInstance(event.getResourceProvider(), MusicBattleMod.id("fx_model_mask"),
                DefaultVertexFormat.BLIT_SCREEN), loaded -> modelMaskShader = loaded);
    }

    /** Starts a new world frame before any block-only outline source can be captured. */
    public static void beginFrame() {
        objectOutlineBlocksCaptured = false;
    }

    /**
     * Captures the completed block and block-entity layers. The later outline shader receives a
     * separate stencil-derived exclusion mask so living entities never become object contours.
     */
    public static boolean captureObjectOutlineBlocks() {
        return captureObjectOutlineBlocks(null);
    }

    /** Captures block geometry into an isolated source owned by a nested camera render. */
    public static boolean captureObjectOutlineBlocks(ObjectOutlineCapture capture) {
        ShaderInstance depthCopyShader = objectDepthCopyShader;
        Minecraft minecraft = Minecraft.getInstance();
        RenderTarget main = minecraft.getMainRenderTarget();
        if (depthCopyShader == null || main == null || main.width <= 0 || main.height <= 0
                || main.getDepthTextureId() <= 0
                || GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING) != main.frameBufferId) {
            return false;
        }
        int previousRead = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int previousDraw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int[] viewport = new int[4];
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
        boolean depthEnabled = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        boolean blendEnabled = GL11.glIsEnabled(GL11.GL_BLEND);
        boolean cullEnabled = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        boolean scissorEnabled = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        boolean stencilEnabled = GL11.glIsEnabled(GL11.GL_STENCIL_TEST);
        boolean depthWrite = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        float[] clearColor = new float[4];
        GL11.glGetFloatv(GL11.GL_COLOR_CLEAR_VALUE, clearColor);
        int[] scissorBox = new int[4];
        GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, scissorBox);
        ByteBuffer colorWriteMask = BufferUtils.createByteBuffer(4);
        GL11.glGetBooleanv(GL11.GL_COLOR_WRITEMASK, colorWriteMask);
        boolean writeRed = colorWriteMask.get(0) != 0;
        boolean writeGreen = colorWriteMask.get(1) != 0;
        boolean writeBlue = colorWriteMask.get(2) != 0;
        boolean writeAlpha = colorWriteMask.get(3) != 0;
        boolean shaderApplied = false;
        try {
            TextureTarget colorTarget = ensureObjectOutlineTarget(main, false, capture);
            TextureTarget depthTarget = ensureObjectOutlineTarget(main, true, capture);
            if (colorTarget == null || depthTarget == null) return false;

            // Never blit depth here. The main target has a combined depth/stencil texture for
            // player FX, while an ordinary TextureTarget has a depth-only texture. OpenGL
            // rejects cross-format depth blits and leaves the object source empty.
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, main.frameBufferId);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, colorTarget.frameBufferId);
            GL30.glBlitFramebuffer(0, 0, main.width, main.height,
                    0, 0, colorTarget.width, colorTarget.height,
                    GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);

            // Encode block-only depth into a regular colour texture through a shader. This is
            // compatible with both depth-only and combined depth/stencil main targets and does
            // not modify or attach the player/entity stencil buffer.
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, depthTarget.frameBufferId);
            GL11.glViewport(0, 0, depthTarget.viewWidth, depthTarget.viewHeight);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDepthMask(false);
            GL11.glDisable(GL11.GL_BLEND);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glDisable(GL11.GL_STENCIL_TEST);
            GL11.glColorMask(true, true, true, true);
            depthCopyShader.setSampler("DepthSampler", main.getDepthTextureId());
            depthCopyShader.apply();
            shaderApplied = true;
            drawBlitQuad();
            if (capture == null) {
                objectOutlineBlocksCaptured = true;
            } else {
                capture.captured = true;
            }
            return true;
        } catch (RuntimeException exception) {
            if (capture == null) {
                objectOutlineBlockTargetFailed = true;
            } else {
                capture.failed = true;
                capture.captured = false;
            }
            MusicBattleMod.LOGGER.warn(capture == null
                    ? "Unable to capture the Blocky Music block-only outline source"
                    : "Unable to capture the Blocky Music DJ camera block-only outline source", exception);
            return false;
        } finally {
            if (shaderApplied) depthCopyShader.clear();
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, previousRead);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, previousDraw);
            GL11.glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
            GL11.glDepthMask(depthWrite);
            GL11.glColorMask(writeRed, writeGreen, writeBlue, writeAlpha);
            GL11.glClearColor(clearColor[0], clearColor[1], clearColor[2], clearColor[3]);
            if (depthEnabled) GL11.glEnable(GL11.GL_DEPTH_TEST); else GL11.glDisable(GL11.GL_DEPTH_TEST);
            if (blendEnabled) GL11.glEnable(GL11.GL_BLEND); else GL11.glDisable(GL11.GL_BLEND);
            if (cullEnabled) GL11.glEnable(GL11.GL_CULL_FACE); else GL11.glDisable(GL11.GL_CULL_FACE);
            if (scissorEnabled) {
                GL11.glEnable(GL11.GL_SCISSOR_TEST);
                GL11.glScissor(scissorBox[0], scissorBox[1], scissorBox[2], scissorBox[3]);
            } else {
                GL11.glDisable(GL11.GL_SCISSOR_TEST);
            }
            if (stencilEnabled) GL11.glEnable(GL11.GL_STENCIL_TEST); else GL11.glDisable(GL11.GL_STENCIL_TEST);
        }
    }

    public static boolean renderFishEye(TrackEffect effect, float strengthAmount, float visibility) {
        ShaderInstance shader = fishEyeShader;
        if (shader == null || effect == null || strengthAmount <= 0.001F || visibility <= 0.001F) return false;
        Minecraft minecraft = Minecraft.getInstance();
        RenderTarget main = minecraft.getMainRenderTarget();
        if (main == null || main.width <= 0 || main.height <= 0
                || GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING) != main.frameBufferId) {
            return false;
        }
        TextureTarget scratch = captureMain(main);
        if (scratch == null) return false;

        TrackEffectSettings settings = effect.settings();
        shader.setSampler("DiffuseSampler", scratch.getColorTextureId());
        // Iris owns different depth/stencil attachments while a shader pack is active. Keep the
        // normal source mask everywhere else, but avoid cross-attaching an Iris-owned image to
        // our temporary framebuffer. The fullscreen effect itself remains enabled.
        int modelMaskTexture = ClientModelGlowEngine.isIrisShaderPackInUse() ? 0 : buildModelMask(main);
        shader.setSampler("ModelMaskSampler", modelMaskTexture != 0 ? modelMaskTexture : scratch.getColorTextureId());
        shader.safeGetUniform("UseModelMask").set(modelMaskTexture != 0 ? 1.0F : 0.0F);
        shader.safeGetUniform("Strength").set(Mth.clamp(settings.fishEyeStrength() * strengthAmount, 0.0F, 1.5F));
        shader.safeGetUniform("Radius").set(settings.fishEyeRadius());
        // Zoom used to jump straight to its final value while only the curvature respected
        // fade-in/out. Interpolate from the neutral 1x view with the same visibility envelope.
        float visibilityEnvelope = Mth.clamp(visibility, 0.0F, 1.0F);
        shader.safeGetUniform("Zoom").set(Mth.lerp(visibilityEnvelope, 1.0F, settings.fishEyeZoom()));
        shader.safeGetUniform("Center").set(effect.positionX(), 1.0F - effect.positionY());
        shader.safeGetUniform("Opacity").set(effect.opacity());
        shader.safeGetUniform("Chromatic").set(settings.fishEyeChromatic());
        shader.safeGetUniform("EdgeDarkening").set(settings.fishEyeEdgeDarkening());
        shader.safeGetUniform("RemoveBlackEdges").set(settings.fishEyeRemoveBlackEdges() ? 1.0F : 0.0F);
        return drawFullscreen(shader, false);
    }

    /**
     * A real ordered framebuffer pass. Rendering it before or after another fullscreen effect
     * changes which image is sampled, so FX-row ordering composes correctly.
     */
    public static boolean renderPulse(float amount) {
        ShaderInstance shader = pulseShader;
        if (shader == null || amount <= 0.001F) return false;
        Minecraft minecraft = Minecraft.getInstance();
        RenderTarget main = minecraft.getMainRenderTarget();
        if (main == null || main.width <= 0 || main.height <= 0
                || GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING) != main.frameBufferId) {
            return false;
        }
        TextureTarget scratch = captureMain(main);
        if (scratch == null) return false;
        shader.setSampler("DiffuseSampler", scratch.getColorTextureId());
        int modelMaskTexture = ClientModelGlowEngine.isIrisShaderPackInUse() ? 0 : buildModelMask(main);
        shader.setSampler("ModelMaskSampler", modelMaskTexture != 0 ? modelMaskTexture : scratch.getColorTextureId());
        shader.safeGetUniform("UseModelMask").set(modelMaskTexture != 0 ? 1.0F : 0.0F);
        shader.safeGetUniform("Scale").set(1.0F + Mth.clamp(amount, 0.0F, 5.0F) * 0.055F);
        return drawFullscreen(shader, false);
    }

    public static boolean renderVignette(TrackEffect effect, float amount) {
        ShaderInstance shader = vignetteShader;
        if (shader == null || effect == null || amount <= 0.001F) return false;
        TrackEffectSettings settings = effect.settings();
        int color = effect.colorA();
        shader.safeGetUniform("Tint").set(
                ((color >> 16) & 255) / 255.0F,
                ((color >> 8) & 255) / 255.0F,
                (color & 255) / 255.0F
        );
        shader.safeGetUniform("Radius").set(settings.vignetteRadius());
        shader.safeGetUniform("Softness").set(settings.vignetteSoftness());
        shader.safeGetUniform("Roundness").set(settings.vignetteRoundness());
        shader.safeGetUniform("Alpha").set(Mth.clamp(amount * effect.opacity(), 0.0F, 1.0F));
        return drawFullscreen(shader, true);
    }

    public static boolean renderColorCorrection(TrackEffect effect, float amount) {
        ShaderInstance shader = colorCorrectionShader;
        if (shader == null || effect == null || amount <= 0.001F) return false;
        Minecraft minecraft = Minecraft.getInstance();
        RenderTarget main = minecraft.getMainRenderTarget();
        if (main == null || main.width <= 0 || main.height <= 0
                || GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING) != main.frameBufferId) {
            return false;
        }
        TextureTarget scratch = captureMain(main);
        if (scratch == null) return false;

        TrackEffectSettings settings = effect.settings();
        shader.setSampler("DiffuseSampler", scratch.getColorTextureId());
        shader.safeGetUniform("Amount").set(Mth.clamp(amount, 0.0F, 1.0F));
        shader.safeGetUniform("Brightness").set(settings.colorBrightness());
        shader.safeGetUniform("Contrast").set(settings.colorContrast());
        shader.safeGetUniform("Saturation").set(settings.colorSaturation());
        shader.safeGetUniform("Hue").set(settings.colorHue() * ((float)Math.PI / 180.0F));
        shader.safeGetUniform("Gamma").set(settings.colorGamma());
        shader.safeGetUniform("Temperature").set(settings.colorTemperature());
        return drawFullscreen(shader, false);
    }

    public static boolean renderObjectOutline(TrackEffect effect, float amount, int color) {
        return renderObjectOutline(effect, amount, color, List.of());
    }

    public static boolean renderObjectOutline(TrackEffect effect, float amount, int color,
                                              List<ScreenWarp> precedingWarps) {
        ShaderInstance shader = objectOutlineShader;
        if (shader == null || effect == null || amount <= 0.001F) return false;
        Minecraft minecraft = Minecraft.getInstance();
        RenderTarget main = minecraft.getMainRenderTarget();
        ObjectOutlineCapture override = objectOutlineSourceOverride;
        TextureTarget blockColors = override == null ? objectOutlineColorTarget : override.colorTarget;
        TextureTarget blockDepth = override == null ? objectOutlineDepthTarget : override.depthTarget;
        RenderTarget sceneTarget = override == null ? main : override.sceneTarget;
        boolean captured = override == null ? objectOutlineBlocksCaptured : override.captured;
        if (main == null || main.width <= 0 || main.height <= 0
                || !captured || blockColors == null || blockDepth == null || sceneTarget == null
                || blockColors.width <= 0 || blockColors.height <= 0
                || blockDepth.width != blockColors.width || blockDepth.height != blockColors.height
                || sceneTarget.width != blockColors.width || sceneTarget.height != blockColors.height
                || GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING) != main.frameBufferId) {
            return false;
        }
        shader.setSampler("BlockColorSampler", blockColors.getColorTextureId());
        shader.setSampler("BlockDepthSampler", blockDepth.getColorTextureId());
        int exclusionMaskTexture = ClientModelGlowEngine.isIrisShaderPackInUse()
                ? 0 : buildModelMask(main);
        shader.setSampler("ExcludedSampler", exclusionMaskTexture != 0
                ? exclusionMaskTexture : blockDepth.getColorTextureId());
        shader.safeGetUniform("UseExcludedMask").set(exclusionMaskTexture != 0 ? 1.0F : 0.0F);
        int sceneDepthTexture = sceneTarget.getDepthTextureId();
        // Sampling a texture that is attached to the framebuffer currently being written is
        // undefined. The regular path therefore keeps its established stencil protection;
        // only a nested preview samples depth from its separate camera framebuffer.
        boolean useSceneDepth = override != null && sceneDepthTexture > 0;
        shader.setSampler("SceneDepthSampler", useSceneDepth
                ? sceneDepthTexture : blockDepth.getColorTextureId());
        shader.safeGetUniform("UseSceneDepth").set(useSceneDepth ? 1.0F : 0.0F);
        shader.safeGetUniform("InSize").set((float) blockColors.width, (float) blockColors.height);
        shader.safeGetUniform("OutlineColor").set(
                ((color >> 16) & 255) / 255.0F,
                ((color >> 8) & 255) / 255.0F,
                (color & 255) / 255.0F
        );
        shader.safeGetUniform("Alpha").set(Mth.clamp(amount, 0.0F, 1.0F));
        shader.safeGetUniform("Thickness").set(Mth.clamp(effect.outlineThickness(), 0.5F, 5.0F));
        shader.safeGetUniform("GlowStrength").set(Mth.clamp(effect.glowStrength(), 0.0F, 5.0F));
        int warpCount = Math.min(MAX_OBJECT_OUTLINE_WARPS,
                precedingWarps == null ? 0 : precedingWarps.size());
        shader.safeGetUniform("WarpCount").set((float) warpCount);
        for (int index = 0; index < MAX_OBJECT_OUTLINE_WARPS; index++) {
            ScreenWarp warp = index < warpCount ? precedingWarps.get(index) : ScreenWarp.NONE;
            shader.safeGetUniform("WarpA" + index).set(
                    warp.type(), warp.centerX(), warp.centerY(), warp.primary());
            shader.safeGetUniform("WarpB" + index).set(
                    warp.secondary(), warp.tertiary(), warp.opacity(), 0.0F);
        }
        // The source was captured before entities, while this composite runs at the effect's
        // ordered GUI row. Respect the active stencil protection so block edges cannot leak
        // through players, mobs, or highlighted models. Keep main-target alpha unchanged.
        // The full-screen editor has no matching stencil in the real main target, so its
        // camera depth performs occlusion. The live monitor has copied its own matching mask
        // into the compositor target and must retain its ordered model/text protection.
        boolean foreignCameraStencil = override != null && !DjCameraPreviewRenderer.isCompositingLiveMonitor();
        return drawFullscreen(shader, true, false, foreignCameraStencil);
    }

    /** Source-UV transform already applied to the framebuffer before OBJECT_OUTLINE's row. */
    public record ScreenWarp(float type, float centerX, float centerY, float primary,
                             float secondary, float tertiary, float opacity) {
        private static final ScreenWarp NONE = new ScreenWarp(0.0F, 0.5F, 0.5F,
                1.0F, 1.0F, 1.0F, 0.0F);

        public static ScreenWarp pulse(float amount) {
            return new ScreenWarp(1.0F, 0.5F, 0.5F,
                    1.0F + Mth.clamp(amount, 0.0F, 5.0F) * 0.055F,
                    1.0F, 1.0F, 1.0F);
        }

        public static ScreenWarp fishEye(TrackEffect effect, float strengthAmount, float visibility) {
            TrackEffectSettings settings = effect.settings();
            float visibilityEnvelope = Mth.clamp(visibility, 0.0F, 1.0F);
            return new ScreenWarp(2.0F, effect.positionX(), 1.0F - effect.positionY(),
                    Mth.clamp(settings.fishEyeStrength() * strengthAmount, 0.0F, 1.5F),
                    settings.fishEyeRadius(),
                    Mth.lerp(visibilityEnvelope, 1.0F, settings.fishEyeZoom()),
                    Mth.clamp(effect.opacity(), 0.0F, 1.0F));
        }
    }

    /** Creates a reusable source for a nested world render such as the active DJ camera. */
    public static ObjectOutlineCapture createObjectOutlineCapture() {
        return new ObjectOutlineCapture();
    }

    /** Temporarily selects a nested-camera source while the normal ordered FX stack runs. */
    public static void withObjectOutlineSource(ObjectOutlineCapture capture, Runnable render) {
        ObjectOutlineCapture previous = objectOutlineSourceOverride;
        objectOutlineSourceOverride = capture;
        try {
            render.run();
        } finally {
            objectOutlineSourceOverride = previous;
        }
    }

    private static TextureTarget ensureObjectOutlineTarget(RenderTarget main, boolean depthValues,
                                                            ObjectOutlineCapture capture) {
        if (capture != null) {
            return capture.ensureTarget(main, depthValues);
        }
        if (objectOutlineBlockTargetFailed) return null;
        try {
            TextureTarget target = depthValues ? objectOutlineDepthTarget : objectOutlineColorTarget;
            if (target == null) {
                target = new TextureTarget(main.width, main.height, false, Minecraft.ON_OSX);
                if (depthValues) objectOutlineDepthTarget = target; else objectOutlineColorTarget = target;
            } else if (target.width != main.width || target.height != main.height) {
                target.resize(main.width, main.height, Minecraft.ON_OSX);
            }
            return target;
        } catch (RuntimeException exception) {
            objectOutlineBlockTargetFailed = true;
            MusicBattleMod.LOGGER.warn("Unable to allocate the Blocky Music block-only outline target", exception);
            return null;
        }
    }

    /** Owns color/depth snapshots without ever replacing the regular world-frame capture. */
    public static final class ObjectOutlineCapture {
        private TextureTarget colorTarget;
        private TextureTarget depthTarget;
        private RenderTarget sceneTarget;
        private boolean captured;
        private boolean failed;

        public void beginFrame(RenderTarget sceneTarget) {
            this.sceneTarget = sceneTarget;
            this.captured = false;
        }

        public boolean hasFrame() {
            return captured && !failed && colorTarget != null && depthTarget != null && sceneTarget != null;
        }

        public void invalidateScene(RenderTarget sceneTarget) {
            if (this.sceneTarget == sceneTarget) {
                this.sceneTarget = null;
                this.captured = false;
            }
        }

        public void release() {
            if (colorTarget != null) {
                colorTarget.destroyBuffers();
                colorTarget = null;
            }
            if (depthTarget != null) {
                depthTarget.destroyBuffers();
                depthTarget = null;
            }
            sceneTarget = null;
            captured = false;
            failed = false;
        }

        private TextureTarget ensureTarget(RenderTarget source, boolean depthValues) {
            if (failed) return null;
            try {
                TextureTarget target = depthValues ? depthTarget : colorTarget;
                if (target == null) {
                    target = new TextureTarget(source.width, source.height, false, Minecraft.ON_OSX);
                    if (depthValues) depthTarget = target; else colorTarget = target;
                } else if (target.width != source.width || target.height != source.height) {
                    target.resize(source.width, source.height, Minecraft.ON_OSX);
                }
                return target;
            } catch (RuntimeException exception) {
                failed = true;
                captured = false;
                MusicBattleMod.LOGGER.warn(
                        "Unable to allocate the Blocky Music DJ camera block-only outline target", exception);
                return null;
            }
        }
    }

    private static TextureTarget ensureScratch(RenderTarget main) {
        if (scratchFailed) return null;
        boolean depthEnabled = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        try {
            if (scratchTarget == null) {
                scratchTarget = new TextureTarget(main.width, main.height, false, Minecraft.ON_OSX);
            } else if (scratchTarget.width != main.width || scratchTarget.height != main.height) {
                scratchTarget.resize(main.width, main.height, Minecraft.ON_OSX);
            }
            return scratchTarget;
        } catch (RuntimeException exception) {
            scratchFailed = true;
            MusicBattleMod.LOGGER.warn("Unable to allocate the Blocky Music fullscreen FX target", exception);
            return null;
        } finally {
            if (depthEnabled) RenderSystem.enableDepthTest(); else RenderSystem.disableDepthTest();
        }
    }

    private static TextureTarget captureMain(RenderTarget main) {
        TextureTarget scratch = ensureScratch(main);
        if (scratch == null) return null;
        // TextureTarget creation/resizing unbinds its framebuffer.
        main.bindWrite(true);
        int previousRead = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int previousDraw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        boolean scissorEnabled = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        int[] scissorBox = new int[4];
        GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, scissorBox);
        try {
            // glBlitFramebuffer obeys the scissor test. A GUI scissor left by another layer
            // would therefore copy only a rectangle and leave black/uninitialised pixels in
            // the texture sampled by fish-eye/pulse.
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, main.frameBufferId);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, scratch.frameBufferId);
            GL30.glBlitFramebuffer(0, 0, main.width, main.height,
                    0, 0, scratch.width, scratch.height,
                    GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);
        } finally {
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, previousRead);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, previousDraw);
            if (scissorEnabled) {
                GL11.glEnable(GL11.GL_SCISSOR_TEST);
                GL11.glScissor(scissorBox[0], scissorBox[1], scissorBox[2], scissorBox[3]);
            } else {
                GL11.glDisable(GL11.GL_SCISSOR_TEST);
            }
        }
        return scratch;
    }

    /**
     * Converts the highlighted-model stencil bit into a sampleable color mask. Spatial effects
     * need this in addition to destination stencil testing: otherwise a warped source coordinate
     * can pull a character pixel outside its protected silhouette.
     */
    private static int buildModelMask(RenderTarget main) {
        ShaderInstance shader = modelMaskShader;
        int stencilMask = ClientModelGlowEngine.protectedFxStencilMask();
        if (shader == null || stencilMask == 0 || !main.isStencilEnabled() || main.getDepthTextureId() <= 0
                || modelMaskFailed) {
            return 0;
        }
        TextureTarget target = ensureModelMask(main);
        if (target == null) return 0;
        main.bindWrite(true);

        int previousRead = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int previousDraw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int[] viewport = new int[4];
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
        boolean stencilEnabled = GL11.glIsEnabled(GL11.GL_STENCIL_TEST);
        boolean depthEnabled = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        boolean blendEnabled = GL11.glIsEnabled(GL11.GL_BLEND);
        boolean cullEnabled = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        boolean scissorEnabled = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        boolean depthWrite = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        ByteBuffer colorWriteMask = BufferUtils.createByteBuffer(4);
        GL11.glGetBooleanv(GL11.GL_COLOR_WRITEMASK, colorWriteMask);
        boolean writeRed = colorWriteMask.get(0) != 0;
        boolean writeGreen = colorWriteMask.get(1) != 0;
        boolean writeBlue = colorWriteMask.get(2) != 0;
        boolean writeAlpha = colorWriteMask.get(3) != 0;
        int stencilWriteMask = GL11.glGetInteger(GL11.GL_STENCIL_WRITEMASK);
        int stencilFunction = GL11.glGetInteger(GL11.GL_STENCIL_FUNC);
        int stencilReference = GL11.glGetInteger(GL11.GL_STENCIL_REF);
        int stencilValueMask = GL11.glGetInteger(GL11.GL_STENCIL_VALUE_MASK);
        int stencilFail = GL11.glGetInteger(GL11.GL_STENCIL_FAIL);
        int stencilDepthFail = GL11.glGetInteger(GL11.GL_STENCIL_PASS_DEPTH_FAIL);
        int stencilDepthPass = GL11.glGetInteger(GL11.GL_STENCIL_PASS_DEPTH_PASS);
        boolean attachmentAdded = false;
        boolean combinedAttachment = NeoForgeConfig.CLIENT.useCombinedDepthStencilAttachment.get();
        boolean shaderApplied = false;
        try {
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, target.frameBufferId);
            attachDepthStencil(main.getDepthTextureId(), combinedAttachment);
            attachmentAdded = true;
            if (GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER) != GL30.GL_FRAMEBUFFER_COMPLETE) {
                throw new IllegalStateException("highlight mask framebuffer is incomplete");
            }
            GL11.glViewport(0, 0, target.viewWidth, target.viewHeight);
            GL11.glDisable(GL11.GL_STENCIL_TEST);
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glColorMask(true, true, true, true);
            GL30.glClearBufferfv(GL11.GL_COLOR, 0, new float[]{0.0F, 0.0F, 0.0F, 0.0F});

            GL11.glEnable(GL11.GL_STENCIL_TEST);
            GL11.glStencilMask(0x00);
            GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_KEEP);
            RenderSystem.disableDepthTest();
            RenderSystem.depthMask(false);
            RenderSystem.disableBlend();
            RenderSystem.disableCull();
            shader.apply();
            shaderApplied = true;
            for (int bit = 1; bit <= 0x80; bit <<= 1) {
                if ((stencilMask & bit) == 0) continue;
                GL11.glStencilFunc(GL11.GL_EQUAL, bit, bit);
                drawBlitQuad();
            }
            return target.getColorTextureId();
        } catch (RuntimeException exception) {
            modelMaskFailed = true;
            MusicBattleMod.LOGGER.warn("Unable to build the Blocky Music highlighted-model FX mask", exception);
            return 0;
        } finally {
            if (shaderApplied) shader.clear();
            if (attachmentAdded) {
                GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, target.frameBufferId);
                attachDepthStencil(0, combinedAttachment);
            }
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, previousRead);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, previousDraw);
            GL11.glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
            RenderSystem.depthMask(depthWrite);
            if (depthEnabled) RenderSystem.enableDepthTest(); else RenderSystem.disableDepthTest();
            if (blendEnabled) RenderSystem.enableBlend(); else RenderSystem.disableBlend();
            if (cullEnabled) RenderSystem.enableCull(); else RenderSystem.disableCull();
            if (scissorEnabled) GL11.glEnable(GL11.GL_SCISSOR_TEST); else GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glColorMask(writeRed, writeGreen, writeBlue, writeAlpha);
            GL11.glStencilMask(stencilWriteMask);
            GL11.glStencilFunc(stencilFunction, stencilReference, stencilValueMask);
            GL11.glStencilOp(stencilFail, stencilDepthFail, stencilDepthPass);
            if (stencilEnabled) GL11.glEnable(GL11.GL_STENCIL_TEST); else GL11.glDisable(GL11.GL_STENCIL_TEST);
        }
    }

    private static void attachDepthStencil(int textureId, boolean combinedAttachment) {
        if (combinedAttachment) {
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_STENCIL_ATTACHMENT,
                    GL11.GL_TEXTURE_2D, textureId, 0);
        } else {
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT,
                    GL11.GL_TEXTURE_2D, textureId, 0);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_STENCIL_ATTACHMENT,
                    GL11.GL_TEXTURE_2D, textureId, 0);
        }
    }

    private static TextureTarget ensureModelMask(RenderTarget main) {
        boolean depthEnabled = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        try {
            if (modelMaskTarget == null) {
                modelMaskTarget = new TextureTarget(main.width, main.height, false, Minecraft.ON_OSX);
            } else if (modelMaskTarget.width != main.width || modelMaskTarget.height != main.height) {
                modelMaskTarget.resize(main.width, main.height, Minecraft.ON_OSX);
            }
            return modelMaskTarget;
        } catch (RuntimeException exception) {
            modelMaskFailed = true;
            MusicBattleMod.LOGGER.warn("Unable to allocate the Blocky Music highlighted-model FX mask", exception);
            return null;
        } finally {
            if (depthEnabled) RenderSystem.enableDepthTest(); else RenderSystem.disableDepthTest();
        }
    }

    private static void drawBlitQuad() {
        BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.BLIT_SCREEN);
        builder.addVertex(0.0F, 0.0F, 0.0F);
        builder.addVertex(1.0F, 0.0F, 0.0F);
        builder.addVertex(1.0F, 1.0F, 0.0F);
        builder.addVertex(0.0F, 1.0F, 0.0F);
        BufferUploader.draw(builder.buildOrThrow());
    }

    private static boolean drawFullscreen(ShaderInstance shader, boolean blend) {
        // A blended overlay's alpha describes its own coverage, not the scene's opacity.
        // defaultBlendFunc uses ONE/ZERO for alpha: writing it would turn the transparent
        // center of a vignette into a hole in camera images sampled by later FX and the GUI.
        return drawFullscreen(shader, blend, !blend, false);
    }

    private static boolean drawFullscreen(ShaderInstance shader, boolean blend,
                                          boolean writeOutputAlpha, boolean ignoreStencil) {
        boolean depthEnabled = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        boolean blendEnabled = GL11.glIsEnabled(GL11.GL_BLEND);
        boolean cullEnabled = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        boolean scissorEnabled = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        boolean stencilEnabled = GL11.glIsEnabled(GL11.GL_STENCIL_TEST);
        int[] scissorBox = new int[4];
        GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, scissorBox);
        boolean depthWrite = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        int sourceRgb = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB);
        int destinationRgb = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB);
        int sourceAlpha = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA);
        int destinationAlpha = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
        int equationRgb = GL11.glGetInteger(GL20.GL_BLEND_EQUATION_RGB);
        int equationAlpha = GL11.glGetInteger(GL20.GL_BLEND_EQUATION_ALPHA);
        ByteBuffer colorWriteMask = BufferUtils.createByteBuffer(4);
        GL11.glGetBooleanv(GL11.GL_COLOR_WRITEMASK, colorWriteMask);
        boolean writeRed = colorWriteMask.get(0) != 0;
        boolean writeGreen = colorWriteMask.get(1) != 0;
        boolean writeBlue = colorWriteMask.get(2) != 0;
        boolean writeAlpha = colorWriteMask.get(3) != 0;
        boolean applied = false;
        try {
            RenderSystem.disableDepthTest();
            RenderSystem.depthMask(false);
            RenderSystem.disableCull();
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            if (ignoreStencil) GL11.glDisable(GL11.GL_STENCIL_TEST);
            RenderSystem.colorMask(true, true, true, writeOutputAlpha);
            if (blend) {
                RenderSystem.enableBlend();
                GL20.glBlendEquationSeparate(GL14.GL_FUNC_ADD, GL14.GL_FUNC_ADD);
                RenderSystem.defaultBlendFunc();
            } else {
                RenderSystem.disableBlend();
            }
            shader.apply();
            applied = true;
            drawBlitQuad();
            return true;
        } finally {
            if (applied) shader.clear();
            RenderSystem.depthMask(depthWrite);
            RenderSystem.colorMask(writeRed, writeGreen, writeBlue, writeAlpha);
            if (depthEnabled) RenderSystem.enableDepthTest(); else RenderSystem.disableDepthTest();
            if (cullEnabled) RenderSystem.enableCull(); else RenderSystem.disableCull();
            if (scissorEnabled) {
                GL11.glEnable(GL11.GL_SCISSOR_TEST);
                GL11.glScissor(scissorBox[0], scissorBox[1], scissorBox[2], scissorBox[3]);
            } else {
                GL11.glDisable(GL11.GL_SCISSOR_TEST);
            }
            if (blendEnabled) RenderSystem.enableBlend(); else RenderSystem.disableBlend();
            RenderSystem.blendFuncSeparate(sourceRgb, destinationRgb, sourceAlpha, destinationAlpha);
            GL20.glBlendEquationSeparate(equationRgb, equationAlpha);
            if (stencilEnabled) GL11.glEnable(GL11.GL_STENCIL_TEST); else GL11.glDisable(GL11.GL_STENCIL_TEST);
        }
    }
}

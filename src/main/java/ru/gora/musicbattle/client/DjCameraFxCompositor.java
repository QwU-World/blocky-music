package ru.gora.musicbattle.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.shaders.FogShape;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexSorting;
import java.nio.ByteBuffer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.ShaderInstance;
import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

/** Composes the existing ordered FX stack into a monitor-sized image before the GUI draws it. */
final class DjCameraFxCompositor {
    private final ClientFxShaderEngine.TargetBuffers shaderBuffers = new ClientFxShaderEngine.TargetBuffers();
    private TextureTarget target;

    RenderTarget targetFor(RenderTarget scene) {
        if (target == null || target.width != scene.width || target.height != scene.height) {
            if (target != null) target.destroyBuffers();
            target = new TextureTarget(scene.width, scene.height, true, Minecraft.ON_OSX);
            target.enableStencil();
        }
        return target;
    }

    RenderTarget target() {
        return target;
    }

    void render(RenderTarget scene, ClientTrackEffects.EffectState state,
                ClientFxShaderEngine.ObjectOutlineCapture outlineSource) {
        // Both images use the same depth/stencil format. Copying the stencil retains the
        // exact player/entity/highlight masks written during this camera's world render.
        // Start with clear GUI depth: near world fragments must never hide a FLASH/text fill.
        target.bindWrite(true);
        RenderSystem.depthMask(true);
        RenderSystem.colorMask(true, true, true, true);
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
        target.clear(Minecraft.ON_OSX);
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, scene.frameBufferId);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, target.frameBufferId);
        GL30.glBlitFramebuffer(0, 0, scene.width, scene.height, 0, 0, target.width, target.height,
                GL11.GL_COLOR_BUFFER_BIT | GL11.GL_STENCIL_BUFFER_BIT,
                GL11.GL_NEAREST);
        target.bindWrite(true);

        Minecraft minecraft = Minecraft.getInstance();
        int canvasWidth = Math.max(1, minecraft.getWindow().getGuiScaledWidth());
        int canvasHeight = Math.max(1, Math.round(canvasWidth * target.height / (float) target.width));
        GuiGraphics graphics = new GuiGraphics(minecraft, minecraft.renderBuffers().bufferSource()) {
            @Override
            public int guiWidth() {
                return canvasWidth;
            }

            @Override
            public int guiHeight() {
                return canvasHeight;
            }

        };
        RenderSystem.setProjectionMatrix(new Matrix4f().setOrtho(0.0F, canvasWidth,
                canvasHeight, 0.0F, 1000.0F, 21000.0F), VertexSorting.ORTHOGRAPHIC_Z);
        RenderSystem.getModelViewStack().identity().translate(0.0F, 0.0F, -11000.0F);
        RenderSystem.applyModelViewMatrix();
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);

        // There is no battle HUD inside this widget. Run one complete row-ordered pass;
        // splitting it by overlayInterface would reorder rows around a nonexistent HUD.
        ClientFxShaderEngine.withTargetBuffers(shaderBuffers,
                () -> ClientFxShaderEngine.withObjectOutlineSource(outlineSource,
                        () -> ClientTrackEffects.renderOverlay(graphics, state)));
        graphics.flush();
        resolveOpaqueColor(target);
    }

    /** A camera frame is an opaque scene, even if an intervening GUI effect writes coverage alpha. */
    private static void resolveOpaqueColor(RenderTarget target) {
        target.bindWrite(false);
        ByteBuffer colorWrite = BufferUtils.createByteBuffer(4);
        GL11.glGetBooleanv(GL11.GL_COLOR_WRITEMASK, colorWrite);
        boolean scissorEnabled = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        try {
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            RenderSystem.colorMask(false, false, false, true);
            // glClearBuffer obeys the color mask. RGB, depth, and the per-FX stencil masks
            // remain exactly as the ordered compositor produced them; only coverage becomes 1.
            GL30.glClearBufferfv(GL11.GL_COLOR, 0, new float[]{0.0F, 0.0F, 0.0F, 1.0F});
        } finally {
            RenderSystem.colorMask(colorWrite.get(0) != 0, colorWrite.get(1) != 0,
                    colorWrite.get(2) != 0, colorWrite.get(3) != 0);
            if (scissorEnabled) GL11.glEnable(GL11.GL_SCISSOR_TEST);
        }
    }

    void release() {
        if (target != null) target.destroyBuffers();
        target = null;
        shaderBuffers.release();
    }

    /** The nested level pass and GUI shader stack must return the caller's GL state intact. */
    static final class SavedRenderState {
        private final Matrix4f projection = new Matrix4f(RenderSystem.getProjectionMatrix());
        private final VertexSorting sorting = RenderSystem.getVertexSorting();
        private final ShaderInstance shader = RenderSystem.getShader();
        private final float[] shaderColor = RenderSystem.getShaderColor().clone();
        private final float[] fogColor = RenderSystem.getShaderFogColor().clone();
        private final float fogStart = RenderSystem.getShaderFogStart();
        private final float fogEnd = RenderSystem.getShaderFogEnd();
        private final FogShape fogShape = RenderSystem.getShaderFogShape();
        private final int readFramebuffer = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        private final int drawFramebuffer = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        private final int[] viewport = integers(GL11.GL_VIEWPORT);
        private final int[] scissor = integers(GL11.GL_SCISSOR_BOX);
        private final float[] clearColor = floats(GL11.GL_COLOR_CLEAR_VALUE);
        private final boolean scissorEnabled = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        private final boolean depthEnabled = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        private final boolean depthWrite = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        private final int depthFunction = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
        private final boolean blendEnabled = GL11.glIsEnabled(GL11.GL_BLEND);
        private final int sourceRgb = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB);
        private final int destinationRgb = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB);
        private final int sourceAlpha = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA);
        private final int destinationAlpha = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
        private final int equationRgb = GL11.glGetInteger(GL20.GL_BLEND_EQUATION_RGB);
        private final int equationAlpha = GL11.glGetInteger(GL20.GL_BLEND_EQUATION_ALPHA);
        private final boolean cullEnabled = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        private final int cullFace = GL11.glGetInteger(GL11.GL_CULL_FACE_MODE);
        private final boolean stencilEnabled = GL11.glIsEnabled(GL11.GL_STENCIL_TEST);
        private final int stencilWriteMask = GL11.glGetInteger(GL11.GL_STENCIL_WRITEMASK);
        private final int stencilFunction = GL11.glGetInteger(GL11.GL_STENCIL_FUNC);
        private final int stencilReference = GL11.glGetInteger(GL11.GL_STENCIL_REF);
        private final int stencilValueMask = GL11.glGetInteger(GL11.GL_STENCIL_VALUE_MASK);
        private final int stencilFail = GL11.glGetInteger(GL11.GL_STENCIL_FAIL);
        private final int stencilDepthFail = GL11.glGetInteger(GL11.GL_STENCIL_PASS_DEPTH_FAIL);
        private final int stencilDepthPass = GL11.glGetInteger(GL11.GL_STENCIL_PASS_DEPTH_PASS);
        private final int activeTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        private final int[] shaderTextures = new int[12];
        private final boolean[] colorWrite = new boolean[4];

        SavedRenderState() {
            ByteBuffer mask = BufferUtils.createByteBuffer(4);
            GL11.glGetBooleanv(GL11.GL_COLOR_WRITEMASK, mask);
            for (int index = 0; index < colorWrite.length; index++) colorWrite[index] = mask.get(index) != 0;
            for (int index = 0; index < shaderTextures.length; index++) {
                shaderTextures[index] = RenderSystem.getShaderTexture(index);
            }
        }

        void prepareWorldPass() {
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glDisable(GL11.GL_STENCIL_TEST);
            RenderSystem.depthMask(true);
            RenderSystem.colorMask(true, true, true, true);
            RenderSystem.enableDepthTest();
        }

        void restore() {
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readFramebuffer);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, drawFramebuffer);
            RenderSystem.viewport(viewport[0], viewport[1], viewport[2], viewport[3]);
            RenderSystem.setProjectionMatrix(projection, sorting);
            RenderSystem.setShaderColor(shaderColor[0], shaderColor[1], shaderColor[2], shaderColor[3]);
            RenderSystem.setShaderFogColor(fogColor[0], fogColor[1], fogColor[2], fogColor[3]);
            RenderSystem.setShaderFogStart(fogStart);
            RenderSystem.setShaderFogEnd(fogEnd);
            RenderSystem.setShaderFogShape(fogShape);
            if (shader != null) RenderSystem.setShader(() -> shader);
            for (int index = 0; index < shaderTextures.length; index++) {
                RenderSystem.setShaderTexture(index, shaderTextures[index]);
            }
            RenderSystem.activeTexture(activeTexture);
            RenderSystem.clearColor(clearColor[0], clearColor[1], clearColor[2], clearColor[3]);
            RenderSystem.depthMask(depthWrite);
            RenderSystem.depthFunc(depthFunction);
            if (depthEnabled) RenderSystem.enableDepthTest(); else RenderSystem.disableDepthTest();
            if (blendEnabled) RenderSystem.enableBlend(); else RenderSystem.disableBlend();
            RenderSystem.blendFuncSeparate(sourceRgb, destinationRgb, sourceAlpha, destinationAlpha);
            GL20.glBlendEquationSeparate(equationRgb, equationAlpha);
            if (cullEnabled) RenderSystem.enableCull(); else RenderSystem.disableCull();
            GL11.glCullFace(cullFace);
            RenderSystem.colorMask(colorWrite[0], colorWrite[1], colorWrite[2], colorWrite[3]);
            GL11.glStencilMask(stencilWriteMask);
            GL11.glStencilFunc(stencilFunction, stencilReference, stencilValueMask);
            GL11.glStencilOp(stencilFail, stencilDepthFail, stencilDepthPass);
            if (stencilEnabled) GL11.glEnable(GL11.GL_STENCIL_TEST); else GL11.glDisable(GL11.GL_STENCIL_TEST);
            if (scissorEnabled) {
                GL11.glEnable(GL11.GL_SCISSOR_TEST);
                GL11.glScissor(scissor[0], scissor[1], scissor[2], scissor[3]);
            } else {
                GL11.glDisable(GL11.GL_SCISSOR_TEST);
            }
        }

        private static int[] integers(int property) {
            int[] result = new int[4];
            GL11.glGetIntegerv(property, result);
            return result;
        }

        private static float[] floats(int property) {
            float[] result = new float[4];
            GL11.glGetFloatv(property, result);
            return result;
        }
    }
}

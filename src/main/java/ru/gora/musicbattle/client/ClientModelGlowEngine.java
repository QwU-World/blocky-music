package ru.gora.musicbattle.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.OutlineBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.client.StencilManager;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;
import ru.gora.musicbattle.data.TrackEffect;

/**
 * Small geometry-based glow renderer for battle participants.
 *
 * <p>Fresh shell vertices expand along welded normals after model transforms,
 * independently of the entity's or part's pivot. Translucent shells form a 3D
 * falloff. Shells use an inverted-hull pass and are submitted immediately
 * before the corresponding vanilla geometry. The normal skin, armour or cape
 * is therefore guaranteed to cover the centre of its shell even when a model
 * or GUI transform changes face winding.</p>
 */
public final class ClientModelGlowEngine {
    private static final ResourceLocation SHELL_TEXTURE =
            ResourceLocation.withDefaultNamespace("textures/block/white_concrete.png");
    private static final int RESERVED_STENCIL_BIT = StencilManager.reserveBit();
    private static final int STENCIL_MASK = RESERVED_STENCIL_BIT >= 0 && RESERVED_STENCIL_BIT < 8
            ? 1 << RESERVED_STENCIL_BIT : 0;
    private static final int RESERVED_TEXT_STENCIL_BIT = StencilManager.reserveBit();
    private static final int TEXT_STENCIL_MASK = RESERVED_TEXT_STENCIL_BIT >= 0 && RESERVED_TEXT_STENCIL_BIT < 8
            ? 1 << RESERVED_TEXT_STENCIL_BIT : 0;
    private static final int RESERVED_PLAYER_STENCIL_BIT = StencilManager.reserveBit();
    private static final int PLAYER_STENCIL_MASK = RESERVED_PLAYER_STENCIL_BIT >= 0 && RESERVED_PLAYER_STENCIL_BIT < 8
            ? 1 << RESERVED_PLAYER_STENCIL_BIT : 0;
    private static final int RESERVED_ENTITY_STENCIL_BIT = StencilManager.reserveBit();
    private static final int ENTITY_STENCIL_MASK = RESERVED_ENTITY_STENCIL_BIT >= 0 && RESERVED_ENTITY_STENCIL_BIT < 8
            ? 1 << RESERVED_ENTITY_STENCIL_BIT : 0;
    /**
     * A spectator's panels above the fighters. They are the spectator's gameplay panels - hideBlockyMusicHud hides them
     * with the HUD - so an effect that does not cover the interface must not cover them either; drawn in the world,
     * they were under every full-screen effect, and a visualizer took the scores away for its whole section.
     */
    private static final int RESERVED_PANEL_STENCIL_BIT = StencilManager.reserveBit();
    private static final int PANEL_STENCIL_MASK = RESERVED_PANEL_STENCIL_BIT >= 0 && RESERVED_PANEL_STENCIL_BIT < 8
            ? 1 << RESERVED_PANEL_STENCIL_BIT : 0;
    private static boolean mirroredGuiWinding;
    private static boolean renderingGlowShell;
    private static boolean stencilPreparationFailed;
    private static boolean modelMaskWritten;
    private static boolean modelProtectionActive;
    private static boolean textMaskWritten;
    private static boolean playerMaskWritten;
    private static boolean entityMaskWritten;
    private static boolean panelMaskWritten;
    private static int protectedFxStencilMask;
    private static final IrisApiAccess IRIS_API = findIrisApi();
    /** The public no-argument getOriginal() of a buffer source wrapper, or null for any other class. */
    private static final ClassValue<Method> WRAPPED_BUFFER_SOURCE = new ClassValue<>() {
        @Override
        protected Method computeValue(Class<?> type) {
            try {
                Method method = type.getMethod("getOriginal");
                return MultiBufferSource.class.isAssignableFrom(method.getReturnType()) ? method : null;
            } catch (NoSuchMethodException | SecurityException | LinkageError exception) {
                return null;
            }
        }
    };
    private static final ThreadLocal<ArrayDeque<StencilModelContext>> STENCIL_MODEL_CONTEXTS =
            ThreadLocal.withInitial(ArrayDeque::new);
    private static final ThreadLocal<ArrayDeque<EntityFxMaskContext>> ENTITY_FX_MASK_CONTEXTS =
            ThreadLocal.withInitial(ArrayDeque::new);

    private ClientModelGlowEngine() {
    }

    /** Procedurally colored models must also honor a pure-white outline color. */
    public static boolean isRenderingGlowShell() {
        return renderingGlowShell;
    }

    /** Isolates masks owned by a nested camera framebuffer from the normal player-view frame. */
    static IsolatedFrame beginIsolatedFrame() {
        IsolatedFrame frame = new IsolatedFrame();
        try {
            mirroredGuiWinding = false;
            renderingGlowShell = false;
            stencilPreparationFailed = false;
            // The caller has already selected its own framebuffer. Clear only our reserved
            // bits on that image, never the normal world's depth/stencil attachment.
            beginFrame();
            return frame;
        } catch (RuntimeException | Error exception) {
            frame.close();
            throw exception;
        }
    }

    static final class IsolatedFrame implements AutoCloseable {
        private final boolean previousMirroredWinding = mirroredGuiWinding;
        private final boolean previousRenderingShell = renderingGlowShell;
        private final boolean previousPreparationFailed = stencilPreparationFailed;
        private final boolean previousModelMask = modelMaskWritten;
        private final boolean previousProtection = modelProtectionActive;
        private final boolean previousTextMask = textMaskWritten;
        private final boolean previousPlayerMask = playerMaskWritten;
        private final boolean previousEntityMask = entityMaskWritten;
        private final boolean previousPanelMask = panelMaskWritten;
        private final int previousProtectedMask = protectedFxStencilMask;
        private final ArrayDeque<StencilModelContext> previousModelContexts =
                new ArrayDeque<>(STENCIL_MODEL_CONTEXTS.get());
        private final ArrayDeque<EntityFxMaskContext> previousEntityContexts =
                new ArrayDeque<>(ENTITY_FX_MASK_CONTEXTS.get());

        private IsolatedFrame() {
        }

        @Override
        public void close() {
            mirroredGuiWinding = previousMirroredWinding;
            renderingGlowShell = previousRenderingShell;
            stencilPreparationFailed = previousPreparationFailed;
            modelMaskWritten = previousModelMask;
            modelProtectionActive = previousProtection;
            textMaskWritten = previousTextMask;
            playerMaskWritten = previousPlayerMask;
            entityMaskWritten = previousEntityMask;
            panelMaskWritten = previousPanelMask;
            protectedFxStencilMask = previousProtectedMask;
            STENCIL_MODEL_CONTEXTS.get().clear();
            STENCIL_MODEL_CONTEXTS.get().addAll(previousModelContexts);
            ENTITY_FX_MASK_CONTEXTS.get().clear();
            ENTITY_FX_MASK_CONTEXTS.get().addAll(previousEntityContexts);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static void render(Model model, PoseStack pose, MultiBufferSource buffers,
                              TrackEffect effect, int glowColor, float reactive, float visibility,
                              ResourceLocation modelTexture, int packedLight, int entityId) {
        renderGeometry(model, pose, buffers, effect, glowColor, reactive,
                visibility, entityId, (shellPose, shellType, color) ->
                        captureShell(model, shellPose, shellType, color));
    }

    /**
     * Draws the same ordered glow for a separately rendered model part, notably
     * the vanilla player cape. The caller must finish the stencil union after
     * vanilla has submitted the matching part.
     */
    public static void renderPart(ModelPart part, PoseStack pose, MultiBufferSource buffers,
                                  TrackEffect effect, int glowColor, float reactive, float visibility,
                                  int entityId) {
        if (part == null) return;
        renderGeometry(part, pose, buffers, effect, glowColor, reactive,
                visibility, entityId, (shellPose, shellType, color) ->
                        captureShell(part, shellPose, shellType, color));
    }

    private static void renderGeometry(Object identity, PoseStack pose,
                                       MultiBufferSource buffers, TrackEffect effect, int glowColor,
                                       float reactive, float visibility, int entityId,
                                       ShellCapture shellCapture) {
        // Iris renders entities a second time for its shadow map. The battle glow is a
        // screen-space effect and must not flush/change that unrelated framebuffer pass.
        if (isIrisShadowPass()) return;
        // A highlight reaches only as far as its music: spectators and mobs fade out over the last
        // blocks of the audible area and are not lit at all beyond it, whatever the audience lists say.
        float audibleFade = FxAudibleAreaResolver.entityFade(entityId);
        if (audibleFade <= 0.001F) return;
        // Injected before vanilla requests its body buffer, so this cannot
        // invalidate a VertexConsumer already handed to the model.
        flushBuffers(buffers);
        float thickness = Mth.clamp(effect.outlineThickness(), 0.5F, 5.0F);
        float glow = Mth.clamp(effect.glowStrength(), 0.0F, 5.0F);
        int passes = Mth.clamp(1 + Mth.ceil(glow), 1, 6);
        float safeReactive = Mth.clamp(reactive, 0.75F, 2.0F);
        float safeVisibility = Mth.clamp(visibility, 0.0F, 1.0F) * audibleFade;
        RenderType shellType = RenderType.entityTranslucentEmissive(SHELL_TEXTURE);
        // Convert a quarter-block reference radius to render space. In particular, GUI
        // previews scale models into pixels; translation and rotations must not affect
        // the shell radius. abs also preserves mirrored GUI transforms.
        float poseScale = (float) Math.cbrt(Math.abs(pose.last().pose().determinant3x3()));
        if (!Float.isFinite(poseScale) || poseScale <= 0.0F) return;
        boolean stencilWrite = beginOutlineStencilWrite();
        boolean shellRendered = false;
        // Capture animated vertices exactly once for this draw, not once for every glow
        // radius. The capture and weld never survive this invocation or cross entities.
        try (MeshData capture = shellCapture.capture(pose, shellType, 0xFF000000 | (glowColor & 0x00FFFFFF))) {
            if (capture == null) return;
            try (ShellBatch batch = new ShellBatch(capture)) {
                for (int pass = passes - 1; pass >= 0; pass--) {
                    float distance = passes <= 1 ? 0.0F : pass / (float) (passes - 1);
                    // Thickness controls the hard inner rim; glow adds only the
                    // progressively softer outer radius. The ranges are deliberately
                    // wide enough to remain visibly different at GUI scale 3.
                    float hardRim = 0.015F + thickness * 0.018F;
                    float glowSpread = glow * 0.025F;
                    float expansion = Mth.clamp(hardRim + glowSpread * distance * safeReactive, 0.015F, 0.38F);
                    float glow01 = glow / 5.0F;
                    float opacity = Mth.lerp(distance, 0.90F, 0.08F + 0.12F * glow01)
                            * effect.opacity() * safeVisibility;
                    int alpha = Mth.clamp(Math.round(255.0F * opacity), 0, 255);
                    shellRendered |= drawShell(batch.mesh(expansion * 0.25F * poseScale, alpha), shellType);
                }
            }
        } finally {
            if (stencilWrite) endOutlineStencilWrite();
        }
        if (stencilWrite && shellRendered) {
            modelMaskWritten = true;
            beginModelStencilUnion(identity, entityId);
        }
    }

    public static void finishModelStencilUnion(Object model, int entityId, MultiBufferSource buffers) {
        ArrayDeque<StencilModelContext> contexts = STENCIL_MODEL_CONTEXTS.get();
        if (contexts.isEmpty()) return;
        StencilModelContext context = contexts.peek();
        if (context.model() != model || context.entityId() != entityId) return;
        try {
            // Flush only the matching body/layer while it joins its expanded shell
            // in the ordered highlight mask. Unrelated modded layers remain outside
            // this short scope and therefore cannot pollute the reserved bit.
            flushBuffers(buffers);
        } finally {
            contexts.pop();
            resetStencilState();
            if (!contexts.isEmpty()) {
                activateModelStencilUnion();
            } else {
                int entityMask = activeEntityFxMask();
                if (entityMask != 0) activateEntityFxMask(entityMask);
            }
        }
    }

    private static void beginModelStencilUnion(Object model, int entityId) {
        STENCIL_MODEL_CONTEXTS.get().push(new StencilModelContext(model, entityId));
        activateModelStencilUnion();
    }

    private static void activateModelStencilUnion() {
        int writeMask = STENCIL_MASK | activeEntityFxMask();
        GL11.glEnable(GL11.GL_STENCIL_TEST);
        GL11.glStencilMask(writeMask);
        GL11.glStencilFunc(GL11.GL_ALWAYS, writeMask, writeMask);
        GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_REPLACE);
    }

    /** Captures the vanilla body and render layers for per-FX player/entity ordering. */
    public static void beginEntityFxMask(LivingEntity entity, MultiBufferSource buffers, boolean capture) {
        int requestedMask = !capture || isIrisShadowPass() ? 0
                : entity instanceof Player ? PLAYER_STENCIL_MASK : ENTITY_STENCIL_MASK;
        ArrayDeque<EntityFxMaskContext> contexts = ENTITY_FX_MASK_CONTEXTS.get();
        int outerMask = contexts.isEmpty() ? 0 : contexts.peek().mask();
        int mask = requestedMask != 0 && canWriteMainStencil() ? requestedMask : 0;
        if (mask != 0 || outerMask != 0) {
            // Submit geometry belonging to the previous entity before changing or
            // suspending its stencil bit. This is also required for nested renderers.
            flushBuffers(buffers);
            if (mask != 0 && !canWriteMainStencil()) mask = 0;
        }
        boolean touchedStencil = mask != 0 || outerMask != 0;
        contexts.push(new EntityFxMaskContext(entity.getId(), mask, touchedStencil));
        if (mask == 0) {
            // A nested entity that is not captured must not inherit the outer entity's bit.
            if (outerMask != 0) resetStencilState();
            return;
        }
        activateEntityFxMask(mask);
    }

    public static void finishEntityFxMask(LivingEntity entity, MultiBufferSource buffers) {
        ArrayDeque<EntityFxMaskContext> contexts = ENTITY_FX_MASK_CONTEXTS.get();
        if (contexts.isEmpty()) return;
        EntityFxMaskContext context = contexts.peek();
        // Invisible/null-render-type nested entities never reached beginEntityFxMask.
        if (context.entityId() != entity.getId()) return;
        contexts.pop();
        int mask = context.mask();
        if (context.touchedStencil()) {
            // Flush the nested/current entity under its own state before restoring
            // the outer renderer. Otherwise deferred geometry inherits a foreign bit.
            flushBuffers(buffers);
        }
        if (mask != 0 && canWriteMainStencil()) {
            if (canWriteMainStencil()) {
                if (mask == PLAYER_STENCIL_MASK) playerMaskWritten = true;
                if (mask == ENTITY_STENCIL_MASK) entityMaskWritten = true;
            }
        }
        if (context.touchedStencil()) resetStencilState();
        if (!contexts.isEmpty() && contexts.peek().mask() != 0) {
            activateEntityFxMask(contexts.peek().mask());
        }
    }

    private static int activeEntityFxMask() {
        ArrayDeque<EntityFxMaskContext> contexts = ENTITY_FX_MASK_CONTEXTS.get();
        return contexts.isEmpty() ? 0 : contexts.peek().mask();
    }

    private static void activateEntityFxMask(int mask) {
        GL11.glEnable(GL11.GL_STENCIL_TEST);
        GL11.glStencilMask(mask);
        GL11.glStencilFunc(GL11.GL_ALWAYS, mask, mask);
        GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_REPLACE);
    }

    private static void flushBuffers(MultiBufferSource buffers) {
        MultiBufferSource source = unwrapBufferSource(buffers);
        if (source instanceof MultiBufferSource.BufferSource bufferSource) {
            bufferSource.endBatch();
            return;
        }
        if (source instanceof OutlineBufferSource outlineBufferSource) {
            // OutlineBufferSource owns one private outline batch and delegates normal
            // entity geometry to Minecraft's main BufferSource. Both must be submitted
            // while the intended stencil state is still active.
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft != null && minecraft.renderBuffers() != null) {
                minecraft.renderBuffers().bufferSource().endBatch();
            }
            outlineBufferSource.endOutlineBatch();
        }
    }

    /**
     * The buffer source a wrapper passes its geometry on to. While a shader pack is on, Iris hands every entity
     * renderer a BufferSourceWrapper, a plain MultiBufferSource that only tags each render type with the entity
     * being drawn. It cannot be flushed itself, so the body of a player or mob was drawn only after its stencil bit
     * had been switched off again: the block outline then traced every entity under a shader pack. The wrapped
     * source is read through the wrapper's own getOriginal(), found by reflection so that Iris stays optional.
     */
    private static MultiBufferSource unwrapBufferSource(MultiBufferSource buffers) {
        MultiBufferSource source = buffers;
        // A wrapper may wrap another one; a handful of steps is plenty and rules out a loop.
        for (int depth = 0; depth < 4 && source != null
                && !(source instanceof MultiBufferSource.BufferSource)
                && !(source instanceof OutlineBufferSource); depth++) {
            Method original = WRAPPED_BUFFER_SOURCE.get(source.getClass());
            if (original == null) break;
            try {
                Object wrapped = original.invoke(source);
                if (!(wrapped instanceof MultiBufferSource next) || next == source) break;
                source = next;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
                break;
            }
        }
        return source;
    }

    public static void prepareStencilTarget() {
        if (STENCIL_MASK == 0 || stencilPreparationFailed) return;
        var target = net.minecraft.client.Minecraft.getInstance().getMainRenderTarget();
        if (target == null || target.isStencilEnabled()) return;
        try {
            target.enableStencil();
        } catch (RuntimeException exception) {
            stencilPreparationFailed = true;
            ru.gora.musicbattle.MusicBattleMod.LOGGER.warn("Unable to enable the Blocky Music outline stencil layer", exception);
        }
    }

    public static void beginFrame() {
        modelMaskWritten = false;
        modelProtectionActive = false;
        textMaskWritten = false;
        playerMaskWritten = false;
        entityMaskWritten = false;
        panelMaskWritten = false;
        protectedFxStencilMask = 0;
        STENCIL_MODEL_CONTEXTS.get().clear();
        ENTITY_FX_MASK_CONTEXTS.get().clear();
        int clearMask = STENCIL_MASK | TEXT_STENCIL_MASK | PLAYER_STENCIL_MASK | ENTITY_STENCIL_MASK | PANEL_STENCIL_MASK;
        if (clearMask == 0 || !stencilTargetReady()) return;
        var target = net.minecraft.client.Minecraft.getInstance().getMainRenderTarget();
        int previousFramebuffer = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        boolean scissorEnabled = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        int[] scissorBox = new int[4];
        GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, scissorBox);
        boolean stencilEnabled = GL11.glIsEnabled(GL11.GL_STENCIL_TEST);
        int stencilWriteMask = GL11.glGetInteger(GL11.GL_STENCIL_WRITEMASK);
        int stencilClearValue = GL11.glGetInteger(GL11.GL_STENCIL_CLEAR_VALUE);
        try {
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, target.frameBufferId);
            // glClear obeys GL_SCISSOR_TEST. Iris, Veil and HUD batching may legitimately
            // leave a tiled scissor active at AFTER_SKY; clearing through that tile leaves
            // old reserved bits behind and the FX compositor later treats the stale rectangle
            // as a highlighted model. Always clear our bits across the complete target.
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glEnable(GL11.GL_STENCIL_TEST);
            GL11.glStencilMask(clearMask);
            GL11.glClearStencil(0);
            GL11.glClear(GL11.GL_STENCIL_BUFFER_BIT);
        } finally {
            GL11.glClearStencil(stencilClearValue);
            GL11.glStencilMask(stencilWriteMask);
            if (stencilEnabled) GL11.glEnable(GL11.GL_STENCIL_TEST); else GL11.glDisable(GL11.GL_STENCIL_TEST);
            if (scissorEnabled) {
                GL11.glEnable(GL11.GL_SCISSOR_TEST);
                GL11.glScissor(scissorBox[0], scissorBox[1], scissorBox[2], scissorBox[3]);
            } else {
                GL11.glDisable(GL11.GL_SCISSOR_TEST);
            }
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, previousFramebuffer);
        }
    }

    public static boolean hasModelHighlightMask() {
        return modelMaskWritten && stencilReady();
    }

    public static void preserveHighlightedModels(boolean preserve) {
        setFxProtection(preserve, false);
    }

    static boolean isModelProtectionActive() {
        return modelProtectionActive && hasModelHighlightMask();
    }

    public static boolean hasTextFxMask() {
        return textMaskWritten && TEXT_STENCIL_MASK != 0 && stencilTargetReady();
    }

    public static boolean beginTextFxMaskWrite() {
        if (TEXT_STENCIL_MASK == 0 || !stencilTargetReady()) return false;
        var target = net.minecraft.client.Minecraft.getInstance().getMainRenderTarget();
        if (GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING) != target.frameBufferId) return false;
        int preservedMask = protectedFxStencilMask & ~TEXT_STENCIL_MASK;
        GL11.glEnable(GL11.GL_STENCIL_TEST);
        GL11.glStencilMask(TEXT_STENCIL_MASK);
        GL11.glStencilFunc(preservedMask == 0 ? GL11.GL_ALWAYS : GL11.GL_EQUAL,
                TEXT_STENCIL_MASK, preservedMask);
        GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_REPLACE);
        return true;
    }

    public static void endTextFxMaskWrite() {
        textMaskWritten = true;
        applyProtectedFxStencilState();
    }

    public static void setFxProtection(boolean preserveModel, boolean preserveText) {
        setFxProtection(preserveModel, preserveText, false, false);
    }

    public static void setFxProtection(boolean preserveModel, boolean preserveText,
                                       boolean preservePlayers, boolean preserveEntities) {
        setFxProtection(preserveModel, preserveText, preservePlayers, preserveEntities, false);
    }

    public static void setFxProtection(boolean preserveModel, boolean preserveText,
                                       boolean preservePlayers, boolean preserveEntities, boolean preservePanels) {
        modelProtectionActive = preserveModel && hasModelHighlightMask();
        protectedFxStencilMask = (modelProtectionActive ? STENCIL_MASK : 0)
                | (preserveText && hasTextFxMask() ? TEXT_STENCIL_MASK : 0)
                | (preservePlayers && hasPlayerFxMask() ? PLAYER_STENCIL_MASK : 0)
                | (preserveEntities && hasEntityFxMask() ? ENTITY_STENCIL_MASK : 0)
                | (preservePanels && hasPanelFxMask() ? PANEL_STENCIL_MASK : 0);
        applyProtectedFxStencilState();
    }

    public static boolean hasPanelFxMask() {
        return panelMaskWritten && PANEL_STENCIL_MASK != 0 && stencilTargetReady();
    }

    /**
     * Marks what is drawn next as a spectator's world panel, on the main target only (a camera's own target keeps its
     * panels under its effects as it composites them). False when nothing is marked; then {@link #endPanelFxMaskWrite}
     * is not called.
     */
    public static boolean beginPanelFxMaskWrite() {
        if (PANEL_STENCIL_MASK == 0 || !canWriteMainStencil()) return false;
        GL11.glEnable(GL11.GL_STENCIL_TEST);
        GL11.glStencilMask(PANEL_STENCIL_MASK);
        GL11.glStencilFunc(GL11.GL_ALWAYS, PANEL_STENCIL_MASK, PANEL_STENCIL_MASK);
        GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_REPLACE);
        return true;
    }

    public static void endPanelFxMaskWrite() {
        panelMaskWritten = true;
        resetStencilState();
    }

    public static boolean hasPlayerFxMask() {
        return playerMaskWritten && PLAYER_STENCIL_MASK != 0 && stencilTargetReady();
    }

    public static boolean hasEntityFxMask() {
        return entityMaskWritten && ENTITY_STENCIL_MASK != 0 && stencilTargetReady();
    }

    static int protectedFxStencilMask() {
        return protectedFxStencilMask;
    }

    private static void applyProtectedFxStencilState() {
        if (protectedFxStencilMask == 0) {
            resetStencilState();
            return;
        }
        GL11.glEnable(GL11.GL_STENCIL_TEST);
        GL11.glStencilMask(0x00);
        GL11.glStencilFunc(GL11.GL_EQUAL, 0, protectedFxStencilMask);
        GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_KEEP);
    }

    private static boolean beginOutlineStencilWrite() {
        if (!stencilReady()) return false;
        var target = net.minecraft.client.Minecraft.getInstance().getMainRenderTarget();
        if (GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING) != target.frameBufferId) return false;
        int writeMask = STENCIL_MASK | activeEntityFxMask();
        GL11.glEnable(GL11.GL_STENCIL_TEST);
        GL11.glStencilMask(writeMask);
        GL11.glStencilFunc(GL11.GL_ALWAYS, writeMask, writeMask);
        GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_REPLACE);
        return true;
    }

    private static void endOutlineStencilWrite() {
        resetStencilState();
    }

    private static void resetStencilState() {
        GL11.glStencilMask(0xFF);
        GL11.glStencilFunc(GL11.GL_ALWAYS, 0, 0xFF);
        GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_KEEP);
        GL11.glDisable(GL11.GL_STENCIL_TEST);
    }

    private static boolean stencilReady() {
        return STENCIL_MASK != 0 && stencilTargetReady();
    }

    private static boolean stencilTargetReady() {
        var target = net.minecraft.client.Minecraft.getInstance().getMainRenderTarget();
        return !stencilPreparationFailed && target != null && target.isStencilEnabled();
    }

    private static boolean canWriteMainStencil() {
        var target = net.minecraft.client.Minecraft.getInstance().getMainRenderTarget();
        return stencilTargetReady() && target != null
                && GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING) == target.frameBufferId;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static MeshData captureShell(Model model, PoseStack pose, RenderType shellType, int color) {
        BufferBuilder builder = Tesselator.getInstance().begin(shellType.mode(), shellType.format());
        boolean previousShell = renderingGlowShell;
        renderingGlowShell = true;
        try {
            model.renderToBuffer(pose, builder, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, color);
        } finally {
            renderingGlowShell = previousShell;
        }
        return builder.build();
    }

    private static MeshData captureShell(ModelPart part, PoseStack pose, RenderType shellType, int color) {
        BufferBuilder builder = Tesselator.getInstance().begin(shellType.mode(), shellType.format());
        part.render(pose, builder, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, color);
        return builder.build();
    }

    private static boolean drawShell(MeshData mesh, RenderType shellType) {
        try (mesh) {
            boolean cullEnabled = GL11.glIsEnabled(GL11.GL_CULL_FACE);
            int cullFace = GL11.glGetInteger(GL11.GL_CULL_FACE_MODE);
            shellType.setupRenderState();
            RenderSystem.enableCull();
            GL11.glCullFace(mirroredGuiWinding ? GL11.GL_BACK : GL11.GL_FRONT);
            try {
                BufferUploader.drawWithShader(mesh);
            } finally {
                shellType.clearRenderState();
                GL11.glCullFace(cullFace);
                if (cullEnabled) RenderSystem.enableCull(); else RenderSystem.disableCull();
            }
        }
        return true;
    }

    /** One animated capture, one weld, one bounded native scratch buffer for all radii. */
    static final class ShellBatch implements AutoCloseable {
        private final MeshData capture;
        private final GlowMeshExpansion.Prepared expansion;
        private final ByteBufferBuilder scratch;

        ShellBatch(MeshData capture) {
            this.capture = capture;
            var format = capture.drawState().format();
            expansion = GlowMeshExpansion.prepare(capture.vertexBuffer(), capture.drawState().vertexCount(),
                    format.getVertexSize(), format.getOffset(VertexFormatElement.POSITION),
                    format.getOffset(VertexFormatElement.NORMAL));
            scratch = new ByteBufferBuilder(capture.vertexBuffer().remaining());
        }

        MeshData mesh(float radius, int alpha) {
            ByteBuffer source = capture.vertexBuffer();
            long destination = scratch.reserve(source.remaining());
            MemoryUtil.memCopy(MemoryUtil.memAddress(source), destination, source.remaining());
            MeshData mesh = new MeshData(scratch.build(), capture.drawState());
            try {
                ByteBuffer vertices = mesh.vertexBuffer();
                expansion.apply(vertices, radius);
                var format = mesh.drawState().format();
                int color = format.getOffset(VertexFormatElement.COLOR);
                if (color >= 0) {
                    int stride = format.getVertexSize();
                    for (int i = 0; i < mesh.drawState().vertexCount(); i++) {
                        int index = i * stride + color + 3;
                        vertices.put(index, (byte) Math.round((vertices.get(index) & 255) * (alpha / 255.0F)));
                    }
                }
                return mesh;
            } catch (RuntimeException | Error exception) {
                mesh.close();
                throw exception;
            }
        }

        @Override
        public void close() {
            scratch.close();
        }
    }

    public static void beginMirroredGuiRender() {
        mirroredGuiWinding = true;
    }

    public static void endMirroredGuiRender() {
        mirroredGuiWinding = false;
    }

    private static IrisApiAccess findIrisApi() {
        try {
            Class<?> type = Class.forName("net.irisshaders.iris.api.v0.IrisApi", false,
                    ClientModelGlowEngine.class.getClassLoader());
            Object instance = type.getMethod("getInstance").invoke(null);
            return new IrisApiAccess(instance, type.getMethod("isRenderingShadowPass"),
                    type.getMethod("isShaderPackInUse"));
        } catch (ReflectiveOperationException | LinkageError ignored) {
            return null;
        }
    }

    static boolean isIrisShadowPass() {
        return invokeIrisBoolean(IRIS_API == null ? null : IRIS_API.shadowPass());
    }

    static boolean isIrisShaderPackInUse() {
        return invokeIrisBoolean(IRIS_API == null ? null : IRIS_API.shaderPackInUse());
    }

    private static boolean invokeIrisBoolean(Method method) {
        if (IRIS_API == null || method == null) return false;
        try {
            return Boolean.TRUE.equals(method.invoke(IRIS_API.instance()));
        } catch (ReflectiveOperationException | LinkageError ignored) {
            return false;
        }
    }

    @FunctionalInterface
    private interface ShellCapture {
        MeshData capture(PoseStack pose, RenderType shellType, int color);
    }

    private record StencilModelContext(Object model, int entityId) {
    }

    private record EntityFxMaskContext(int entityId, int mask, boolean touchedStencil) {
    }

    private record IrisApiAccess(Object instance, Method shadowPass, Method shaderPackInUse) {
    }
}

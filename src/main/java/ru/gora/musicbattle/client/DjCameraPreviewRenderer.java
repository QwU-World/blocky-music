package ru.gora.musicbattle.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexSorting;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import ru.gora.musicbattle.MusicBattleMod;

/**
 * Isolated, low-resolution world pass for the DJ monitor. It never changes
 * Minecraft's camera entity, so the listener position and live music stay put.
 */
public final class DjCameraPreviewRenderer {
    /**
     * How large the monitor's own world pass may be, in real pixels.
     *
     * It used to be 384x216 whatever the screen was, which on a 1080p window with the editor filling it meant every
     * pixel was blown up five times: the soft, blocky picture with stair-stepped edges that made judging an effect
     * hard. A budget in real pixels keeps it sharp at any window size, and the thirty-times-a-second throttle below
     * is what keeps the cost of it in hand.
     */
    private static final int MAX_TARGET_WIDTH = 1280;
    private static final int MAX_TARGET_HEIGHT = 720;
    /** Below this it stops being a picture of anything. */
    private static final int MIN_TARGET_WIDTH = 256;
    private static final int MIN_TARGET_HEIGHT = 144;
    private static final long LIVE_RENDER_INTERVAL_NANOS = 33_000_000L;
    private static final long THUMBNAIL_RENDER_INTERVAL_NANOS = 80_000_000L;
    private static final int MAX_CACHED_THUMBNAILS = 16;

    private static final PreviewCamera CAMERA = new PreviewCamera();
    private static final ClientFxShaderEngine.ObjectOutlineCapture OBJECT_OUTLINE_CAPTURE =
            ClientFxShaderEngine.createObjectOutlineCapture();
    private static final DjCameraFxCompositor LIVE_COMPOSITOR = new DjCameraFxCompositor();
    private static TextureTarget target;
    private static RenderTarget activeRenderTarget;
    private static final Map<Long, Thumbnail> thumbnails = new LinkedHashMap<>();
    private static boolean rendering;
    private static boolean capturingObjectOutline;
    private static boolean compositingLiveMonitor;
    private static boolean lastFrameWasLiveMonitor;
    private static ClientTrackEffects.EffectState activeEffectState;
    private static Set<Integer> activePlayerEntityIds = Set.of();
    private static Set<Integer> activeMobEntityIds = Set.of();
    private static long lastRenderNanos;
    private static boolean renderFailed;
    private static boolean loggedFailure;
    private static Method irisShadersInUse;
    private static Object irisApi;
    private static boolean irisProbeDone;

    private DjCameraPreviewRenderer() {
    }

    /** Used only by the client mixin while the nested world pass is active. */
    public static RenderTarget redirectedMainTarget() {
        return rendering ? activeRenderTarget : null;
    }

    public static boolean isRenderingPreview() {
        return rendering;
    }

    /** True only for the monitor pass with its own stencil attachment and ordered compositor. */
    public static boolean isCompositingLiveMonitor() {
        return rendering && compositingLiveMonitor;
    }

    static Set<Integer> activePlayerEntityIds() {
        return isCompositingLiveMonitor() ? activePlayerEntityIds : Set.of();
    }

    static Set<Integer> activeMobEntityIds() {
        return isCompositingLiveMonitor() ? activeMobEntityIds : Set.of();
    }

    /** Draft FX visible to render hooks only while the isolated camera pass is active. */
    static ClientTrackEffects.EffectState activeEffectState() {
        return rendering ? activeEffectState : null;
    }

    /** Called only from the nested render-stage event after solid/cutout blocks are present. */
    public static void captureObjectOutlineBlocks() {
        if (rendering && capturingObjectOutline) {
            ClientFxShaderEngine.captureObjectOutlineBlocks(OBJECT_OUTLINE_CAPTURE);
        }
    }

    public static boolean hasObjectOutlineSource() {
        return OBJECT_OUTLINE_CAPTURE.hasFrame();
    }

    /** Keeps OBJECT_OUTLINE in its regular globalRow while sourcing it from the active camera. */
    public static void withObjectOutlineSource(Runnable render) {
        // Select the isolated source even before its first successful frame. Falling back to
        // the regular player-camera capture would display a convincing but completely wrong
        // contour while the active camera is still loading.
        ClientFxShaderEngine.withObjectOutlineSource(OBJECT_OUTLINE_CAPTURE, render);
    }

    public static Status update(Snapshot snapshot, int displayWidth, int displayHeight, float partialTick) {
        return update(snapshot, displayWidth, displayHeight, partialTick, null);
    }

    public static Status update(Snapshot snapshot, int displayWidth, int displayHeight, float partialTick,
                                ClientTrackEffects.EffectState effectState) {
        return update(snapshot, displayWidth, displayHeight, partialTick, effectState, false, Set.of(), Set.of());
    }

    public static Status updateLiveMonitor(Snapshot snapshot, int displayWidth, int displayHeight,
                                           float partialTick, ClientTrackEffects.EffectState effectState,
                                           Set<Integer> playerEntityIds, Set<Integer> mobEntityIds) {
        return update(snapshot, displayWidth, displayHeight, partialTick, effectState, true,
                playerEntityIds, mobEntityIds);
    }

    private static Status update(Snapshot snapshot, int displayWidth, int displayHeight, float partialTick,
                                 ClientTrackEffects.EffectState effectState, boolean liveMonitor,
                                 Set<Integer> playerEntityIds, Set<Integer> mobEntityIds) {
        Minecraft minecraft = Minecraft.getInstance();
        if (snapshot == null || !snapshot.available()) {
            return Status.CAMERA_UNAVAILABLE;
        }
        if (minecraft.level == null || minecraft.player == null) {
            return Status.WORLD_UNAVAILABLE;
        }
        if (shaderPackInUse()) {
            return Status.SHADERS_ACTIVE;
        }
        BlockPos cameraChunk = BlockPos.containing(snapshot.position());
        if (!minecraft.level.hasChunkAt(cameraChunk)) {
            return Status.CHUNK_UNAVAILABLE;
        }
        int safeDisplayWidth = Math.max(1, displayWidth);
        int safeDisplayHeight = Math.max(1, displayHeight);
        // Use one scale for both axes. Independent clamping produced a
        // framebuffer with a different aspect ratio from the on-screen live
        // monitor and visibly stretched the camera image on wide GUIs.
        // The widget's size is in GUI units; the pixels it actually covers are that times the GUI scale. Rendering
        // at the GUI scale is what makes the picture sharp, and the budget is what keeps it affordable.
        double guiScale = Math.max(1.0D, minecraft.getWindow().getGuiScale());
        float targetScale = (float) Math.min(guiScale, Math.min(
                MAX_TARGET_WIDTH / (double) safeDisplayWidth,
                MAX_TARGET_HEIGHT / (double) safeDisplayHeight));
        int targetWidth = Math.max(MIN_TARGET_WIDTH, Math.round(safeDisplayWidth * targetScale));
        int targetHeight = Math.max(MIN_TARGET_HEIGHT, Math.round(safeDisplayHeight * targetScale));
        ensureTarget(targetWidth, targetHeight);
        if (target == null || renderFailed) {
            return Status.RENDER_FAILED;
        }

        long now = System.nanoTime();
        // Camera transforms arrive at 10 Hz, but entities, music and FX advance every frame.
        // Do not freeze that scene between camera packets. Gallery cards keep their own budget.
        if (lastFrameWasLiveMonitor == liveMonitor && now - lastRenderNanos < LIVE_RENDER_INTERVAL_NANOS) {
            return Status.READY;
        }

        if (!render(snapshot, target, partialTick, effectState, liveMonitor, playerEntityIds, mobEntityIds)) {
            return Status.RENDER_FAILED;
        }
        lastFrameWasLiveMonitor = liveMonitor;
        return Status.READY;
    }

    /**
     * Updates one cached gallery card. The caller deliberately advances only
     * one key at a time; the shared render budget below additionally guarantees
     * that a grid can never trigger one nested world pass per card per frame.
     */
    public static Status updateThumbnail(long key, Snapshot snapshot, int displayWidth, int displayHeight,
                                         float partialTick) {
        Status availability = validateSnapshot(snapshot);
        if (availability != Status.READY) {
            return availability;
        }
        int targetWidth = Mth.clamp(displayWidth, 96, 224);
        int targetHeight = Mth.clamp(displayHeight, 54, 126);
        Thumbnail thumbnail = ensureThumbnail(key, targetWidth, targetHeight);
        if (thumbnail == null || renderFailed) {
            return Status.RENDER_FAILED;
        }
        long now = System.nanoTime();
        if (thumbnail.hasFrame && (snapshot.serverGameTime() == thumbnail.serverGameTime
                || now - lastRenderNanos < THUMBNAIL_RENDER_INTERVAL_NANOS)) {
            return Status.READY;
        }
        if (now - lastRenderNanos < THUMBNAIL_RENDER_INTERVAL_NANOS) {
            return Status.READY;
        }
        if (!render(snapshot, thumbnail.target, partialTick, null, false, Set.of(), Set.of())) {
            return Status.RENDER_FAILED;
        }
        thumbnail.hasFrame = true;
        thumbnail.serverGameTime = snapshot.serverGameTime();
        return Status.READY;
    }

    public static boolean hasThumbnail(long key) {
        Thumbnail thumbnail = thumbnails.get(key);
        return thumbnail != null && thumbnail.hasFrame;
    }

    public static void drawThumbnail(GuiGraphics graphics, long key, int x, int y, int width, int height) {
        Thumbnail thumbnail = thumbnails.get(key);
        if (thumbnail == null || !thumbnail.hasFrame) {
            return;
        }
        drawTarget(graphics, thumbnail.target, x, y, width, height);
    }

    public static void releaseThumbnails() {
        for (Thumbnail thumbnail : thumbnails.values()) {
            thumbnail.target.destroyBuffers();
        }
        thumbnails.clear();
    }

    private static Status validateSnapshot(Snapshot snapshot) {
        Minecraft minecraft = Minecraft.getInstance();
        if (snapshot == null || !snapshot.available()) {
            return Status.CAMERA_UNAVAILABLE;
        }
        if (minecraft.level == null || minecraft.player == null) {
            return Status.WORLD_UNAVAILABLE;
        }
        if (shaderPackInUse()) {
            return Status.SHADERS_ACTIVE;
        }
        if (!minecraft.level.hasChunkAt(BlockPos.containing(snapshot.position()))) {
            return Status.CHUNK_UNAVAILABLE;
        }
        return Status.READY;
    }

    private static boolean render(Snapshot snapshot, RenderTarget destination, float partialTick,
                                  ClientTrackEffects.EffectState effectState, boolean liveMonitor,
                                  Set<Integer> playerEntityIds, Set<Integer> mobEntityIds) {
        Minecraft minecraft = Minecraft.getInstance();
        long now = System.nanoTime();

        RenderSystem.assertOnRenderThread();
        // Submit any already queued GUI vertices before redirecting getMainRenderTarget().
        minecraft.renderBuffers().bufferSource().endBatch();
        DjCameraFxCompositor.SavedRenderState savedState = new DjCameraFxCompositor.SavedRenderState();
        var modelView = RenderSystem.getModelViewStack();
        modelView.pushMatrix();
        var entityDispatcher = minecraft.getEntityRenderDispatcher();
        Camera previousEntityCamera = entityDispatcher.camera;
        Entity previousCrosshairEntity = entityDispatcher.crosshairPickEntity;
        Quaternionf previousEntityOrientation = new Quaternionf(entityDispatcher.cameraOrientation());
        var blockDispatcher = minecraft.getBlockEntityRenderDispatcher();
        var previousBlockLevel = blockDispatcher.level;
        Camera previousBlockCamera = blockDispatcher.camera;
        var previousBlockHit = blockDispatcher.cameraHitResult;
        minecraft.getProfiler().push("dj_camera_preview");
        rendering = true;
        activeRenderTarget = destination;
        activeEffectState = effectState;
        activePlayerEntityIds = playerEntityIds;
        activeMobEntityIds = mobEntityIds;
        compositingLiveMonitor = liveMonitor;
        capturingObjectOutline = destination == target;
        if (capturingObjectOutline) {
            OBJECT_OUTLINE_CAPTURE.beginFrame(destination);
        }
        ClientModelGlowEngine.IsolatedFrame isolatedMasks = null;
        try {
            if (liveMonitor && !destination.isStencilEnabled()) {
                destination.enableStencil();
            }
            savedState.prepareWorldPass();
            // Every camera owns a depth buffer. Reusing it without a clear can
            // reject fresh geometry after the shot moves and leave ghosted or
            // missing pieces in both the live monitor and gallery thumbnails.
            destination.clear(Minecraft.ON_OSX);
            destination.bindWrite(true);
            isolatedMasks = ClientModelGlowEngine.beginIsolatedFrame();
            Entity cameraEntity = minecraft.getCameraEntity() != null
                    ? minecraft.getCameraEntity() : minecraft.player;
            // Detached=true keeps the local DJ/player visible if their body is
            // inside the shot. setPreviewTransform below replaces the temporary
            // third-person offset with the exact block-camera transform.
            CAMERA.setup(minecraft.level, cameraEntity, true, false, partialTick);
            float shakeYaw = effectState == null ? 0.0F : ClientTrackEffects.shakeYaw(effectState);
            float shakePitch = effectState == null ? 0.0F : ClientTrackEffects.shakePitch(effectState);
            float shakeRoll = effectState == null ? 0.0F : ClientTrackEffects.shakeRoll(effectState);
            CAMERA.setPreviewTransform(snapshot.position(), snapshot.yaw() + shakeYaw,
                    snapshot.pitch() + shakePitch, shakeRoll);

            float aspect = (float) destination.width / (float) Math.max(1, destination.height);
            float pulseFov = effectState == null ? 0.0F
                    : Mth.clamp(effectState.cameraPulseBeat(), 0.0F, 5.0F) * 8.0F;
            float fov = Mth.clamp(snapshot.fov() + pulseFov, 30.0F, 110.0F);
            Matrix4f projection = new Matrix4f().perspective(
                    fov * Mth.DEG_TO_RAD, aspect, 0.05F,
                    Math.max(256.0F, minecraft.gameRenderer.getDepthFar()));
            RenderSystem.setProjectionMatrix(projection, VertexSorting.DISTANCE_TO_ORIGIN);
            Quaternionf inverseRotation = CAMERA.rotation().conjugate(new Quaternionf());
            Matrix4f view = new Matrix4f().rotation(inverseRotation);
            float cullFov = Math.max(fov, minecraft.options.fov().get().floatValue());
            Matrix4f cullProjection = new Matrix4f().perspective(
                    cullFov * Mth.DEG_TO_RAD, aspect, 0.05F,
                    Math.max(256.0F, minecraft.gameRenderer.getDepthFar()));
            minecraft.levelRenderer.prepareCullFrustum(CAMERA.getPosition(), view, cullProjection);
            minecraft.levelRenderer.renderLevel(DeltaTracker.ONE, false, CAMERA,
                    minecraft.gameRenderer, minecraft.gameRenderer.lightTexture(), view, projection);
            minecraft.renderBuffers().bufferSource().endBatch();
            if (liveMonitor && effectState != null) {
                // Keep raw scene/depth separate: OBJECT_OUTLINE samples that depth while the
                // ordered shaders write the composited image. The main GUI is never a target.
                activeRenderTarget = LIVE_COMPOSITOR.targetFor(destination);
                LIVE_COMPOSITOR.render(destination, effectState, OBJECT_OUTLINE_CAPTURE);
            }
            lastRenderNanos = now;
        } catch (Throwable throwable) {
            renderFailed = true;
            if (!loggedFailure) {
                loggedFailure = true;
                MusicBattleMod.LOGGER.warn("Unable to render isolated DJ camera monitor; disabling it for this screen.", throwable);
            }
        } finally {
            if (isolatedMasks != null) isolatedMasks.close();
            rendering = false;
            capturingObjectOutline = false;
            compositingLiveMonitor = false;
            activeRenderTarget = null;
            activeEffectState = null;
            activePlayerEntityIds = Set.of();
            activeMobEntityIds = Set.of();
            minecraft.getProfiler().pop();
            modelView.popMatrix();
            RenderSystem.applyModelViewMatrix();
            if (previousEntityCamera != null) {
                entityDispatcher.prepare(minecraft.level, previousEntityCamera, previousCrosshairEntity);
                entityDispatcher.overrideCameraOrientation(previousEntityOrientation);
            }
            blockDispatcher.prepare(previousBlockLevel, previousBlockCamera, previousBlockHit);
            Lighting.setupFor3DItems();
            savedState.restore();
        }
        return !renderFailed;
    }

    public static void draw(GuiGraphics graphics, int x, int y, int width, int height) {
        if (target == null || renderFailed) {
            return;
        }
        drawTarget(graphics, target, x, y, width, height);
    }

    public static void drawLiveMonitor(GuiGraphics graphics, int x, int y, int width, int height) {
        if (target == null || renderFailed) return;
        RenderTarget composite = LIVE_COMPOSITOR.target();
        drawTarget(graphics, composite != null && lastFrameWasLiveMonitor ? composite : target,
                x, y, width, height);
    }

    private static void drawTarget(GuiGraphics graphics, RenderTarget source,
                                   int x, int y, int width, int height) {
        graphics.flush();
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.setShaderTexture(0, source.getColorTextureId());
        Matrix4f matrix = graphics.pose().last().pose();
        BufferBuilder buffer = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
        // Render targets use a bottom-left texture origin, hence the vertical flip.
        buffer.addVertex(matrix, x, y, 0.0F).setUv(0.0F, 1.0F);
        buffer.addVertex(matrix, x, y + height, 0.0F).setUv(0.0F, 0.0F);
        buffer.addVertex(matrix, x + width, y + height, 0.0F).setUv(1.0F, 0.0F);
        buffer.addVertex(matrix, x + width, y, 0.0F).setUv(1.0F, 1.0F);
        BufferUploader.drawWithShader(buffer.buildOrThrow());
    }

    /** Releases native GL buffers on screen close and resets failures for the next open. */
    public static void release() {
        if (target != null) {
            OBJECT_OUTLINE_CAPTURE.invalidateScene(target);
            target.destroyBuffers();
            target = null;
        }
        OBJECT_OUTLINE_CAPTURE.release();
        LIVE_COMPOSITOR.release();
        lastRenderNanos = 0L;
        renderFailed = false;
        loggedFailure = false;
        rendering = false;
        capturingObjectOutline = false;
        compositingLiveMonitor = false;
        lastFrameWasLiveMonitor = false;
        activeRenderTarget = null;
        activeEffectState = null;
        activePlayerEntityIds = Set.of();
        activeMobEntityIds = Set.of();
        releaseThumbnails();
    }

    private static void ensureTarget(int width, int height) {
        if (target != null && target.width == width && target.height == height) {
            return;
        }
        DjCameraFxCompositor.SavedRenderState savedState = new DjCameraFxCompositor.SavedRenderState();
        try {
            if (target != null) {
                OBJECT_OUTLINE_CAPTURE.invalidateScene(target);
                target.destroyBuffers();
            }
            target = new TextureTarget(width, height, true, Minecraft.ON_OSX);
            target.setClearColor(0.02F, 0.025F, 0.035F, 1.0F);
            // Smoothed rather than blocky when the monitor is drawn larger than the pass behind it.
            target.setFilterMode(com.mojang.blaze3d.platform.GlConst.GL_LINEAR);
            renderFailed = false;
            lastRenderNanos = 0L;
            lastFrameWasLiveMonitor = false;
        } catch (RuntimeException exception) {
            target = null;
            renderFailed = true;
            if (!loggedFailure) {
                loggedFailure = true;
                MusicBattleMod.LOGGER.warn("Unable to allocate DJ camera monitor framebuffer.", exception);
            }
        } finally {
            savedState.restore();
        }
    }

    private static Thumbnail ensureThumbnail(long key, int width, int height) {
        Thumbnail existing = thumbnails.get(key);
        if (existing != null && existing.target.width == width && existing.target.height == height) {
            return existing;
        }
        DjCameraFxCompositor.SavedRenderState savedState = new DjCameraFxCompositor.SavedRenderState();
        try {
            if (existing != null) {
                existing.target.destroyBuffers();
                thumbnails.remove(key);
            }
            while (thumbnails.size() >= MAX_CACHED_THUMBNAILS) {
                Long oldest = thumbnails.keySet().iterator().next();
                thumbnails.remove(oldest).target.destroyBuffers();
            }
            TextureTarget thumbnailTarget = new TextureTarget(width, height, true, Minecraft.ON_OSX);
            thumbnailTarget.setClearColor(0.02F, 0.025F, 0.035F, 1.0F);
            Thumbnail created = new Thumbnail(thumbnailTarget);
            thumbnails.put(key, created);
            return created;
        } catch (RuntimeException exception) {
            if (!loggedFailure) {
                loggedFailure = true;
                MusicBattleMod.LOGGER.warn("Unable to allocate a DJ camera gallery thumbnail.", exception);
            }
            return null;
        } finally {
            savedState.restore();
        }
    }

    private static boolean shaderPackInUse() {
        if (!ModList.get().isLoaded("iris") && !ModList.get().isLoaded("oculus")) {
            return false;
        }
        if (!irisProbeDone) {
            irisProbeDone = true;
            try {
                Class<?> apiClass = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
                irisApi = apiClass.getMethod("getInstance").invoke(null);
                irisShadersInUse = apiClass.getMethod("isShaderPackInUse");
            } catch (ReflectiveOperationException exception) {
                // Unknown shader-pipeline version: nested world rendering is not safe.
                return true;
            }
        }
        if (irisApi == null || irisShadersInUse == null) {
            return true;
        }
        try {
            return Boolean.TRUE.equals(irisShadersInUse.invoke(irisApi));
        } catch (ReflectiveOperationException exception) {
            return true;
        }
    }

    public record Snapshot(boolean available, long serverGameTime, Vec3 position,
                           float yaw, float pitch, float fov) {
    }

    public enum Status {
        READY,
        CAMERA_UNAVAILABLE,
        WORLD_UNAVAILABLE,
        CHUNK_UNAVAILABLE,
        SHADERS_ACTIVE,
        RENDER_FAILED
    }

    private static final class Thumbnail {
        private final TextureTarget target;
        private boolean hasFrame;
        private long serverGameTime = Long.MIN_VALUE;

        private Thumbnail(TextureTarget target) {
            this.target = target;
        }
    }

    private static final class PreviewCamera extends Camera {
        private void setPreviewTransform(Vec3 position, float yaw, float pitch, float roll) {
            setPosition(position);
            setRotation(Mth.wrapDegrees(yaw), Mth.clamp(pitch, -89.0F, 89.0F), roll);
        }
    }
}

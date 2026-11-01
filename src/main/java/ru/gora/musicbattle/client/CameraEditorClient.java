package ru.gora.musicbattle.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.systems.RenderSystem;
import java.util.List;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RenderArmEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderHandEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL11;
import ru.gora.musicbattle.client.ui.Palette;
import ru.gora.musicbattle.client.ui.RoundedRect;
import ru.gora.musicbattle.client.ui.TextStyle;
import ru.gora.musicbattle.client.ui.TextStyles;
import ru.gora.musicbattle.network.CameraFollowModePayload;
import ru.gora.musicbattle.network.CameraFollowStatePayload;
import ru.gora.musicbattle.network.CloseCameraEditorPayload;
import ru.gora.musicbattle.network.CameraEditorClosedPayload;
import ru.gora.musicbattle.network.OpenCameraEditorPayload;
import ru.gora.musicbattle.network.UpdateCameraEditorPayload;
import ru.gora.musicbattle.data.CameraCollision;

public final class CameraEditorClient {
    private static final float ROTATION_DEGREES_PER_TICK = 1.25F;
    /** The hint panel: its widest, its distance from the screen's top and edges, and its inner padding. */
    private static final float HINT_MAX_WIDTH = 440.0F;
    private static final float HINT_MARGIN = 8.0F;
    private static final float HINT_PADDING = 10.0F;
    private static final float HINT_PILL_HEIGHT = 15.0F;
    private static final float HINT_PILL_PADDING = 7.0F;
    private static final float HINT_GAP = 4.0F;
    private static boolean active;
    private static OpenCameraEditorPayload session;
    private static Vec3 position = Vec3.ZERO;
    private static boolean resetPending;
    private static Vec3 playerPosition = Vec3.ZERO;
    private static float playerYaw;
    private static float playerPitch;
    private static float yaw;
    private static float pitch;
    private static float fov = 70.0F;
    private static Vec3 lastSentPosition = Vec3.ZERO;
    private static float lastSentYaw;
    private static float lastSentPitch;
    private static float lastSentFov = 70.0F;
    private static int returnCameraEntityId;
    private static UUID returnCameraEntityUuid;
    private static boolean resumeSoundsAfterCancelledPause;
    private static boolean finishKeyDown;
    private static boolean resetKeyDown;
    private static boolean followKeyDown;
    /** The camera block's follow mode as the server last confirmed it. */
    private static boolean followPlayers;
    /** A switch was asked for and the server has not answered yet; the panel shows the mode dimmed meanwhile. */
    private static boolean followPending;
    private static boolean mouseModeKeyDown;
    private static boolean mouseLookEnabled = true;
    private static boolean mousePositionValid;
    private static double lastMouseX;
    private static double lastMouseY;
    private static Screen returnScreen;

    private CameraEditorClient() {
    }

    public static void open(OpenCameraEditorPayload payload) {
        Minecraft minecraft = Minecraft.getInstance();
        if (payload.resetResponse()) {
            if (!active || session == null || session.cameraEntityId() != payload.cameraEntityId()
                    || !session.blockPos().equals(payload.blockPos())) {
                return;
            }
            // A placement reset updates the same camera; keep the return screen,
            // player lock and mouse/cursor mode belonging to this editor session.
            session = payload;
            applyTransform(payload);
            resetPending = false;
            resetMousePosition(minecraft);
            updateLocalCamera(minecraft);
            return;
        }
        if (!active) {
            Entity returnCamera = minecraft.getCameraEntity();
            returnCameraEntityId = returnCamera == null ? 0 : returnCamera.getId();
            returnCameraEntityUuid = returnCamera == null ? null : returnCamera.getUUID();
            returnScreen = minecraft.screen instanceof DjCameraGalleryScreen gallery
                    ? gallery : null;
        }
        active = true;
        session = payload;
        resetPending = false;
        // The server sends the block's follow mode right after this payload; until then nothing is claimed.
        followPlayers = false;
        followPending = true;
        applyTransform(payload);
        minecraft.setScreen(null);
        if (minecraft.player != null) {
            playerPosition = new Vec3(payload.playerX(), payload.playerY(), payload.playerZ());
            playerYaw = payload.playerYaw();
            playerPitch = payload.playerPitch();
            freezePlayer(minecraft);
        }
        long window = minecraft.getWindow().getWindow();
        finishKeyDown = InputConstants.isKeyDown(window, GLFW.GLFW_KEY_ENTER)
                || InputConstants.isKeyDown(window, GLFW.GLFW_KEY_KP_ENTER);
        resetKeyDown = InputConstants.isKeyDown(window, GLFW.GLFW_KEY_R);
        followKeyDown = InputConstants.isKeyDown(window, GLFW.GLFW_KEY_F);
        mouseModeKeyDown = keyDown(minecraft, MusicBattleKeys.CAMERA_EDITOR_MOUSE_MODE);
        mouseLookEnabled = true;
        applyMouseMode(minecraft);
        ensureCamera(minecraft);
    }

    private static void applyTransform(OpenCameraEditorPayload payload) {
        position = new Vec3(payload.x(), payload.y(), payload.z());
        yaw = payload.yaw();
        pitch = payload.pitch();
        fov = payload.fov();
        lastSentPosition = position;
        lastSentYaw = yaw;
        lastSentPitch = pitch;
        lastSentFov = fov;
    }

    public static void closeFromServer(CameraEditorClosedPayload payload) {
        if (!active || session == null || !session.blockPos().equals(payload.blockPos())) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        // A forced close can mean that the linked panel, camera, or dimension
        // no longer exists. Do not reopen a stale gallery from that world.
        returnScreen = null;
        clear(minecraft);
        if (minecraft.player != null) {
            minecraft.player.displayClientMessage(Component.translatable(
                    "message.music_battle.camera_editor.closed_by_server"), true);
        }
    }

    /** The server's word on the follow mode of the camera being set up. */
    public static void acceptFollowState(CameraFollowStatePayload payload) {
        if (!active || session == null || !session.blockPos().equals(payload.blockPos())) {
            return;
        }
        followPlayers = payload.followPlayers();
        followPending = false;
    }

    public static boolean isActive() {
        return active;
    }

    /** Drops a client-only editor session without sending packets into a new connection/world. */
    public static void resetForWorldTransition() {
        resumeSoundsAfterCancelledPause = false;
        returnCameraEntityId = 0;
        returnCameraEntityUuid = null;
        returnScreen = null;
        clear(Minecraft.getInstance());
        position = Vec3.ZERO;
        yaw = 0.0F;
        pitch = 0.0F;
        fov = 70.0F;
        finishKeyDown = false;
        resetKeyDown = false;
        followKeyDown = false;
        followPlayers = false;
        followPending = false;
        mouseModeKeyDown = false;
        mouseLookEnabled = true;
        mousePositionValid = false;
    }

    public static void preTick() {
        if (!active) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        suppressPlayerMovement(minecraft);
        freezePlayer(minecraft);
    }

    public static void tick() {
        Minecraft minecraft = Minecraft.getInstance();
        if (resumeSoundsAfterCancelledPause) {
            // Minecraft#pauseGame pauses SoundManager *after* ScreenEvent.Opening
            // returns. The pause screen is canceled while leaving this editor, so
            // the normal battle pause lifecycle never observes a paused game and
            // cannot resume it. Resume on the following client tick without
            // replacing/reseeking the active Blocky Music sound instance.
            resumeSoundsAfterCancelledPause = false;
            minecraft.getSoundManager().resume();
        }
        if (!active) {
            return;
        }
        if (minecraft.player == null || minecraft.level == null || session == null) {
            clear(minecraft);
            return;
        }
        ensureCamera(minecraft);
        if (minecraft.screen != null || resetPending) {
            return;
        }

        long window = minecraft.getWindow().getWindow();
        // Read the physical binding as well as the KeyMapping state. The editor's
        // input-isolation pass deliberately clears ordinary mappings every tick;
        // relying only on KeyMapping#isDown made the mouse-mode switch impossible.
        boolean mouseModeDown = keyDown(minecraft, MusicBattleKeys.CAMERA_EDITOR_MOUSE_MODE);
        if (mouseModeDown && !mouseModeKeyDown) {
            mouseLookEnabled = !mouseLookEnabled;
            applyMouseMode(minecraft);
        }
        mouseModeKeyDown = mouseModeDown;
        maintainMouseMode(minecraft);
        updateMouseLook(minecraft);

        boolean forwardDown = keyDown(minecraft, minecraft.options.keyUp);
        boolean backwardDown = keyDown(minecraft, minecraft.options.keyDown);
        boolean rightDown = keyDown(minecraft, minecraft.options.keyRight);
        boolean leftDown = keyDown(minecraft, minecraft.options.keyLeft);
        boolean jumpDown = keyDown(minecraft, minecraft.options.keyJump);
        boolean descendDown = keyDown(minecraft, minecraft.options.keyShift);
        boolean fast = keyDown(minecraft, minecraft.options.keySprint);
        double speed = fast ? 0.28D : 0.11D;
        float rotationSpeed = ROTATION_DEGREES_PER_TICK * (fast ? 1.8F : 1.0F);
        if (InputConstants.isKeyDown(window, GLFW.GLFW_KEY_LEFT)) {
            yaw = Mth.wrapDegrees(yaw - rotationSpeed);
        }
        if (InputConstants.isKeyDown(window, GLFW.GLFW_KEY_RIGHT)) {
            yaw = Mth.wrapDegrees(yaw + rotationSpeed);
        }
        if (InputConstants.isKeyDown(window, GLFW.GLFW_KEY_UP)) {
            pitch = Mth.clamp(pitch - rotationSpeed, -89.0F, 89.0F);
        }
        if (InputConstants.isKeyDown(window, GLFW.GLFW_KEY_DOWN)) {
            pitch = Mth.clamp(pitch + rotationSpeed, -89.0F, 89.0F);
        }
        Vec3 forward = Vec3.directionFromRotation(0.0F, yaw).multiply(1.0D, 0.0D, 1.0D).normalize();
        Vec3 right = new Vec3(-forward.z, 0.0D, forward.x);
        Vec3 movement = Vec3.ZERO;
        if (forwardDown) {
            movement = movement.add(forward);
        }
        if (backwardDown) {
            movement = movement.subtract(forward);
        }
        if (rightDown) {
            movement = movement.add(right);
        }
        if (leftDown) {
            movement = movement.subtract(right);
        }
        if (jumpDown) {
            movement = movement.add(0.0D, 1.0D, 0.0D);
        }
        if (descendDown) {
            movement = movement.add(0.0D, -1.0D, 0.0D);
        }
        suppressPlayerMovement(minecraft);
        freezePlayer(minecraft);
        if (movement.lengthSqr() > 0.0D) {
            Vec3 desiredPosition = position.add(movement.normalize().scale(speed));
            position = CameraCollision.resolve(minecraft.level, minecraft.getCameraEntity(), position, desiredPosition);
        }

        boolean followDown = InputConstants.isKeyDown(window, GLFW.GLFW_KEY_F);
        if (followDown && !followKeyDown && !followPending) {
            // F, like R and Enter, is read as a physical key: the editor clears every ordinary key mapping each tick
            // (ClientInputIsolation), so F does not also swap the player's hands while the camera is being set up.
            followPending = true;
            PacketDistributor.sendToServer(new CameraFollowModePayload(session.blockPos(), !followPlayers));
        }
        followKeyDown = followDown;

        boolean resetDown = InputConstants.isKeyDown(window, GLFW.GLFW_KEY_R);
        if (resetDown && !resetKeyDown) {
            resetKeyDown = true;
            resetPending = true;
            PacketDistributor.sendToServer(new UpdateCameraEditorPayload(
                    session.blockPos(), position.x, position.y, position.z, yaw, pitch, fov, true));
            return;
        }
        resetKeyDown = resetDown;

        updateLocalCamera(minecraft);
        sendUpdate();

        boolean finishDown = InputConstants.isKeyDown(window, GLFW.GLFW_KEY_ENTER)
                || InputConstants.isKeyDown(window, GLFW.GLFW_KEY_KP_ENTER);
        if (finishDown && !finishKeyDown) {
            close();
        }
        finishKeyDown = finishDown;
    }

    public static void onMouseScroll(InputEvent.MouseScrollingEvent event) {
        if (!active || event.getScrollDeltaY() == 0.0D) {
            return;
        }
        if (!resetPending) {
            fov = Mth.clamp(fov + (float) event.getScrollDeltaY() * 2.0F, 30.0F, 110.0F);
        }
        event.setCanceled(true);
    }

    public static void onScreenOpening(ScreenEvent.Opening event) {
        if (active && event.getNewScreen() instanceof PauseScreen) {
            Minecraft minecraft = Minecraft.getInstance();
            resumeSoundsAfterCancelledPause = minecraft.getSingleplayerServer() != null
                    && !minecraft.getSingleplayerServer().isPublished();
            close();
            event.setCanceled(true);
        }
    }

    public static boolean applyCameraAngles(ViewportEvent.ComputeCameraAngles event) {
        if (!active) {
            return false;
        }
        updateMouseLook(Minecraft.getInstance());
        event.setYaw(yaw);
        event.setPitch(pitch);
        event.setRoll(0.0F);
        return true;
    }

    /**
     * The editor payload stores the optical camera position, not the feet of
     * its temporary ArmorStand. CameraMixin applies this after vanilla setup
     * so the editor, DJ preview and audience view all use the same transform.
     */
    public static Vec3 cameraPositionOverride() {
        return active ? position : null;
    }

    public static boolean applyFov(ViewportEvent.ComputeFov event) {
        if (!active) {
            return false;
        }
        event.setFOV(fov);
        return true;
    }

    /**
     * The setup hints, drawn last in the GUI over a cleared depth buffer.
     *
     * <p>Everything else in the GUI is drawn in front of this event's depth: each HUD layer sits 200 units nearer than
     * the one before, and an FX row that covers the interface another 400. A DJ set or a battle nearby therefore hid
     * the hints behind its flash, vignette or cinema bars even though they were drawn after them. With the depth
     * cleared they lie on top of all of it; nothing in the GUI is drawn after this event.
     */
    public static void render(RenderGuiEvent.Post event) {
        if (!active) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        GuiGraphics graphics = event.getGuiGraphics();
        graphics.flush();
        RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);
        drawHints(graphics, minecraft.getWindow().getGuiScaledWidth());
        graphics.flush();
    }

    /** The hint panel in the Workshop's look: a dark rounded card, the title, the keys, and the two modes as pills. */
    private static void drawHints(GuiGraphics g, int screenWidth) {
        float width = Math.min(HINT_MAX_WIDTH, screenWidth - HINT_MARGIN * 2.0F);
        float inner = width - HINT_PADDING * 2.0F;
        if (inner <= 0.0F) {
            return;
        }
        float x = (screenWidth - width) * 0.5F;
        float top = HINT_MARGIN;
        Component title = TextStyles.translated("hud.music_battle.camera_editor.title", TextStyle.TITLE,
                Integer.toString(Math.round(fov)));
        List<FormattedCharSequence> move = TextStyles.wrap(
                TextStyles.translated("hud.music_battle.camera_editor.guide_move", TextStyle.CAPTION), inner);
        List<FormattedCharSequence> actions = TextStyles.wrap(TextStyles.styled(Component.translatable(
                "hud.music_battle.camera_editor.guide_actions",
                MusicBattleKeys.CAMERA_EDITOR_MOUSE_MODE.getTranslatedKeyMessage()), TextStyle.CAPTION), inner);
        Component mouseMode = TextStyles.styled(Component.translatable("hud.music_battle.camera_editor.mouse_mode",
                Component.translatable(mouseLookEnabled
                        ? "hud.music_battle.camera_editor.mouse_look"
                        : "hud.music_battle.camera_editor.cursor_free")), TextStyle.LABEL);
        Component follow = TextStyles.styled(Component.translatable("hud.music_battle.camera_editor.follow",
                Component.translatable(followPlayers
                        ? "hud.music_battle.camera_editor.follow_on"
                        : "hud.music_battle.camera_editor.follow_off")), TextStyle.LABEL);
        List<FormattedCharSequence> followHint = TextStyles.wrap(TextStyles.translated(followPlayers
                ? "hud.music_battle.camera_editor.follow_hint_on"
                : "hud.music_battle.camera_editor.follow_hint_off", TextStyle.CAPTION), inner);

        float caption = TextStyle.CAPTION.lineHeight();
        float mouseWidth = pillWidth(mouseMode, true, inner);
        float followWidth = pillWidth(follow, false, inner);
        boolean pillsShareRow = mouseWidth + HINT_GAP + followWidth <= inner;
        float pillsHeight = pillsShareRow ? HINT_PILL_HEIGHT : HINT_PILL_HEIGHT * 2.0F + HINT_GAP;
        float height = HINT_PADDING + TextStyle.TITLE.lineHeight() + HINT_GAP
                + (move.size() + actions.size()) * caption + HINT_GAP * 2.0F
                + pillsHeight + HINT_GAP + followHint.size() * caption + HINT_PADDING;

        RoundedRect.shadow(g, x, top, width, height, Palette.RADIUS_LG, 10.0F, Palette.SHADOW_LIFTED);
        RoundedRect.fill(g, x, top, width, height, Palette.RADIUS_LG, Palette.withAlpha(Palette.BG_1, 0.94F));
        RoundedRect.stroke(g, x, top, width, height, Palette.RADIUS_LG, 1.0F, Palette.LINE);
        float y = top + HINT_PADDING;
        RoundedRect.pill(g, x + HINT_PADDING, y + 1.0F, 3.0F, TextStyle.TITLE.capHeight(), Palette.ACCENT);
        TextStyles.queue(g, TextStyles.ellipsize(title, inner - 8.0F), x + HINT_PADDING + 8.0F, y, Palette.TEXT_1);
        y += TextStyle.TITLE.lineHeight() + HINT_GAP;
        y += TextStyles.queueLines(g, move, x + HINT_PADDING, y, caption, Palette.TEXT_2);
        y += TextStyles.queueLines(g, actions, x + HINT_PADDING, y, caption, Palette.TEXT_3);
        y += HINT_GAP * 2.0F;
        float pillX = x + HINT_PADDING;
        drawPill(g, pillX, y, mouseWidth, mouseMode, Palette.BG_3, Palette.TEXT_2,
                mouseLookEnabled ? Palette.ACCENT : Palette.WARN);
        if (pillsShareRow) {
            pillX += mouseWidth + HINT_GAP;
        } else {
            y += HINT_PILL_HEIGHT + HINT_GAP;
        }
        float followAlpha = followPending ? 0.55F : 1.0F;
        drawPill(g, pillX, y, followWidth, follow,
                Palette.fade(followPlayers ? Palette.ACCENT_STRONG : Palette.BG_3, followAlpha),
                Palette.fade(followPlayers ? Palette.TEXT_1 : Palette.TEXT_2, followAlpha), 0);
        y += HINT_PILL_HEIGHT + HINT_GAP;
        TextStyles.queueLines(g, followHint, x + HINT_PADDING, y, caption, Palette.TEXT_3);
    }

    private static float pillWidth(Component label, boolean dot, float maxWidth) {
        float width = TextStyles.width(label) + HINT_PILL_PADDING * 2.0F + (dot ? 9.0F : 0.0F);
        return Math.min(width, maxWidth);
    }

    /** A rounded pill with its label, and a small coloured dot in front when {@code dotColor} is not zero. */
    private static void drawPill(GuiGraphics g, float x, float y, float width, Component label,
                                 int background, int textColor, int dotColor) {
        RoundedRect.pill(g, x, y, width, HINT_PILL_HEIGHT, background);
        float textX = x + HINT_PILL_PADDING;
        if (dotColor != 0) {
            RoundedRect.circle(g, textX + 2.5F, y + HINT_PILL_HEIGHT * 0.5F, 2.5F, dotColor);
            textX += 9.0F;
        }
        float room = x + width - HINT_PILL_PADDING - textX;
        TextStyles.queue(g, TextStyles.ellipsize(label, room), textX,
                TextStyles.centeredCapY(TextStyle.LABEL, y, HINT_PILL_HEIGHT), textColor);
    }

    public static void hideHand(RenderHandEvent event) {
        if (active) {
            event.setCanceled(true);
        }
    }

    public static void hideArm(RenderArmEvent event) {
        if (active) {
            event.setCanceled(true);
        }
    }

    private static void sendUpdate() {
        sendUpdate(false);
    }

    private static void sendUpdate(boolean force) {
        if (!active || session == null || resetPending) {
            return;
        }
        if (!force
                && position.distanceToSqr(lastSentPosition) < 1.0E-8D
                && Math.abs(Mth.wrapDegrees(yaw - lastSentYaw)) < 1.0E-4F
                && Math.abs(pitch - lastSentPitch) < 1.0E-4F
                && Math.abs(fov - lastSentFov) < 1.0E-4F) {
            return;
        }
        PacketDistributor.sendToServer(new UpdateCameraEditorPayload(
                session.blockPos(),
                position.x,
                position.y,
                position.z,
                yaw,
                pitch,
                fov,
                false
        ));
        lastSentPosition = position;
        lastSentYaw = yaw;
        lastSentPitch = pitch;
        lastSentFov = fov;
    }

    private static boolean keyDown(Minecraft minecraft, net.minecraft.client.KeyMapping key) {
        InputConstants.Key bound = key.getKey();
        long window = minecraft.getWindow().getWindow();
        if (bound.getType() == InputConstants.Type.MOUSE) {
            return GLFW.glfwGetMouseButton(window, bound.getValue()) == GLFW.GLFW_PRESS;
        }
        return key.isDown() || InputConstants.isKeyDown(window, bound.getValue());
    }

    private static void applyMouseMode(Minecraft minecraft) {
        if (mouseLookEnabled) {
            if (!minecraft.mouseHandler.isMouseGrabbed()) {
                minecraft.mouseHandler.grabMouse();
            }
        } else if (minecraft.mouseHandler.isMouseGrabbed()) {
            minecraft.mouseHandler.releaseMouse();
        }
        resetMousePosition(minecraft);
    }

    private static void maintainMouseMode(Minecraft minecraft) {
        if (mouseLookEnabled != minecraft.mouseHandler.isMouseGrabbed()) {
            applyMouseMode(minecraft);
        }
    }

    private static void resetMousePosition(Minecraft minecraft) {
        lastMouseX = minecraft.mouseHandler.xpos();
        lastMouseY = minecraft.mouseHandler.ypos();
        mousePositionValid = true;
    }

    private static void updateMouseLook(Minecraft minecraft) {
        double mouseX = minecraft.mouseHandler.xpos();
        double mouseY = minecraft.mouseHandler.ypos();
        if (!mousePositionValid) {
            lastMouseX = mouseX;
            lastMouseY = mouseY;
            mousePositionValid = true;
            return;
        }
        double deltaX = mouseX - lastMouseX;
        double deltaY = mouseY - lastMouseY;
        lastMouseX = mouseX;
        lastMouseY = mouseY;
        if (!active || resetPending || !mouseLookEnabled || !minecraft.mouseHandler.isMouseGrabbed()) {
            return;
        }

        // Match vanilla's non-cinematic mouse sensitivity curve. Sampling the
        // raw cursor position is independent of which entity supplies the view,
        // unlike reading rotation back from the frozen local player.
        double sensitivity = minecraft.options.sensitivity().get() * 0.6000000238418579D
                + 0.20000000298023224D;
        double turnScale = sensitivity * sensitivity * sensitivity * 8.0D * 0.15D;
        double pitchDirection = minecraft.options.invertYMouse().get() ? -1.0D : 1.0D;
        yaw = Mth.wrapDegrees(yaw + (float) (deltaX * turnScale));
        pitch = Mth.clamp(pitch + (float) (deltaY * turnScale * pitchDirection), -89.0F, 89.0F);
    }

    private static void suppressPlayerMovement(Minecraft minecraft) {
        minecraft.options.keyUp.setDown(false);
        minecraft.options.keyDown.setDown(false);
        minecraft.options.keyLeft.setDown(false);
        minecraft.options.keyRight.setDown(false);
        minecraft.options.keyJump.setDown(false);
        minecraft.options.keyShift.setDown(false);
        minecraft.options.keySprint.setDown(false);
    }

    private static void freezePlayer(Minecraft minecraft) {
        if (minecraft.player == null) {
            return;
        }
        minecraft.player.setDeltaMovement(Vec3.ZERO);
        minecraft.player.setPos(playerPosition);
        minecraft.player.setYRot(playerYaw);
        minecraft.player.setXRot(playerPitch);
        minecraft.player.setYHeadRot(playerYaw);
        minecraft.player.setYBodyRot(playerYaw);
        minecraft.player.setOldPosAndRot();
    }

    private static void ensureCamera(Minecraft minecraft) {
        if (!active || session == null || minecraft.level == null) {
            return;
        }
        Entity camera = minecraft.level.getEntity(session.cameraEntityId());
        if (camera != null) {
            if (minecraft.getCameraEntity() != camera) {
                minecraft.setCameraEntity(camera);
            }
            updateLocalCamera(minecraft);
        }
    }

    private static void updateLocalCamera(Minecraft minecraft) {
        if (session == null || minecraft.level == null) {
            return;
        }
        Entity camera = minecraft.level.getEntity(session.cameraEntityId());
        if (camera == null) {
            return;
        }
        camera.setPos(position);
        camera.setYRot(yaw);
        camera.setXRot(pitch);
        camera.yRotO = yaw;
        camera.xRotO = pitch;
    }

    private static void close() {
        Minecraft minecraft = Minecraft.getInstance();
        if (active && session != null) {
            sendUpdate(true);
            PacketDistributor.sendToServer(new CloseCameraEditorPayload(session.blockPos()));
        }
        clear(minecraft);
    }

    private static void clear(Minecraft minecraft) {
        Screen requestedReturnScreen = returnScreen;
        returnScreen = null;
        active = false;
        session = null;
        resetPending = false;
        lastSentPosition = Vec3.ZERO;
        playerPosition = Vec3.ZERO;
        playerYaw = 0.0F;
        playerPitch = 0.0F;
        Entity returnCamera = minecraft.level == null || returnCameraEntityId <= 0
                ? null
                : minecraft.level.getEntity(returnCameraEntityId);
        returnCameraEntityId = 0;
        boolean validReturnCamera = returnCamera != null
                && !returnCamera.isRemoved()
                && returnCamera.level() == minecraft.level
                && returnCamera.getUUID().equals(returnCameraEntityUuid);
        returnCameraEntityUuid = null;
        if (validReturnCamera) {
            minecraft.setCameraEntity(returnCamera);
        } else if (minecraft.player != null) {
            minecraft.setCameraEntity(minecraft.player);
        }
        if (requestedReturnScreen instanceof DjCameraGalleryScreen gallery
                && minecraft.player != null && minecraft.level != null) {
            minecraft.setScreen(gallery);
            gallery.refreshAfterCameraEditor();
        } else if (requestedReturnScreen instanceof DjPanelScreen panel
                && minecraft.player != null && minecraft.level != null) {
            minecraft.setScreen(panel);
            panel.refreshAfterCameraEditor();
        } else if (minecraft.player != null && minecraft.level != null
                && minecraft.screen == null && !minecraft.mouseHandler.isMouseGrabbed()) {
            minecraft.mouseHandler.grabMouse();
        }
        mouseLookEnabled = true;
        mouseModeKeyDown = false;
        mousePositionValid = false;
    }
}

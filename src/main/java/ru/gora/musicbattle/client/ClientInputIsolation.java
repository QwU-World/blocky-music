package ru.gora.musicbattle.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.lwjgl.glfw.GLFW;

/** Keeps editor and participant input from leaking into ordinary mod key mappings. */
public final class ClientInputIsolation {
    private ClientInputIsolation() {
    }

    public static boolean handleBattleKey(int keyCode, int scanCode, int action) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!ClientBattleController.blocksParticipantInput() || minecraft.screen != null || isAllowedSystemKey(keyCode)) {
            return false;
        }

        InputConstants.Key pressedKey = InputConstants.getKey(keyCode, scanCode);
        boolean down = action != GLFW.GLFW_RELEASE;
        if (!isShiftKey(keyCode)) {
            for (KeyMapping lane : MusicBattleKeys.lanes()) {
                if (lane.getKey().equals(pressedKey)) {
                    lane.setDown(down);
                }
            }
        }
        return true;
    }

    public static boolean handleBattleMouseButton(int button, int action) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!ClientBattleController.blocksParticipantInput() || minecraft.screen != null) {
            return false;
        }

        InputConstants.Key pressedKey = InputConstants.Type.MOUSE.getOrCreate(button);
        boolean down = action != GLFW.GLFW_RELEASE;
        for (KeyMapping lane : MusicBattleKeys.lanes()) {
            if (lane.getKey().equals(pressedKey)) {
                lane.setDown(down);
            }
        }
        return true;
    }

    public static boolean blockBattleMouseWheel() {
        Minecraft minecraft = Minecraft.getInstance();
        return ClientBattleController.blocksParticipantInput() && minecraft.screen == null;
    }

    public static boolean blockBattleCameraTurn() {
        return ClientBattleController.blocksParticipantInput();
    }

    public static boolean suppressGlobalKeyEvent(int keyCode) {
        return (ClientBattleController.blocksParticipantInput() || editorScreenActive())
                && !isAllowedSystemKey(keyCode);
    }

    public static boolean suppressGlobalMouseButtonEvent() {
        return ClientBattleController.blocksParticipantInput() || editorScreenActive();
    }

    public static void tick() {
        boolean battle = ClientBattleController.blocksParticipantInput();
        boolean editor = editorScreenActive();
        if (!battle && !editor) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        for (KeyMapping mapping : minecraft.options.keyMappings) {
            InputConstants.Key key = mapping.getKey();
            boolean shift = key.getType() == InputConstants.Type.KEYSYM && isShiftKey(key.getValue());
            boolean keepBattleLane = battle && !shift && isBattleLane(mapping);
            boolean keepCameraEditorControl = CameraEditorClient.isActive()
                    && mapping == MusicBattleKeys.CAMERA_EDITOR_MOUSE_MODE;
            if (!keepBattleLane && !keepCameraEditorControl && !isAllowedSystemKey(key)) {
                mapping.setDown(false);
                while (mapping.consumeClick()) {
                    // Discard clicks queued before or during the battle.
                }
            }
        }
        if (battle) {
            minecraft.options.keyShift.setDown(false);
            if (minecraft.player != null) {
                minecraft.player.setShiftKeyDown(false);
            }
        }
    }

    private static boolean editorScreenActive() {
        if (CameraEditorClient.isActive()) {
            return true;
        }
        Screen screen = Minecraft.getInstance().screen;
        return screen instanceof TrackLibraryScreen
                || screen instanceof MusicBattleCalibrationScreen
                || screen instanceof TrackEditorScreen
                || screen instanceof TrackEffectEditScreen
                || screen instanceof TrackGameplayPreviewScreen
                || screen instanceof PublishTrackConfirmScreen
                || screen instanceof DeleteLocalTrackConfirmScreen
                || screen instanceof DeletePublishedTrackConfirmScreen
                || screen instanceof DjPanelScreen
                || screen instanceof DjPanelOwnedScreen
                || screen instanceof DjCameraGalleryScreen
                || screen instanceof ru.gora.musicbattle.client.workshop.WorkshopUi;
    }

    private static boolean isBattleLane(KeyMapping mapping) {
        for (KeyMapping lane : MusicBattleKeys.lanes()) {
            if (mapping == lane) {
                return true;
            }
        }
        return false;
    }

    private static boolean isAllowedSystemKey(InputConstants.Key key) {
        return key.getType() == InputConstants.Type.KEYSYM && isAllowedSystemKey(key.getValue());
    }

    private static boolean isAllowedSystemKey(int keyCode) {
        return keyCode == GLFW.GLFW_KEY_ESCAPE
                || keyCode >= GLFW.GLFW_KEY_F1 && keyCode <= GLFW.GLFW_KEY_F25;
    }

    private static boolean isShiftKey(int keyCode) {
        return keyCode == GLFW.GLFW_KEY_LEFT_SHIFT || keyCode == GLFW.GLFW_KEY_RIGHT_SHIFT;
    }
}

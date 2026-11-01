package ru.gora.musicbattle.client;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.lwjgl.glfw.GLFW;

/** Keeps editor and participant input from leaking into ordinary mod key mappings. */
public final class ClientInputIsolation {
    /**
     * Every key and mouse button that is down, from the window's own events, with or without a screen open. A screen
     * takes the key events and lets go of every key mapping as it opens (KeyMapping.releaseAll), and on macOS closing
     * it does not read them back, so after the pause menu only this still knows which lane keys were held through it
     * (ClientBattleController#resumeLanesAfterGamePause).
     */
    private static final Set<InputConstants.Key> HELD = new HashSet<>();

    private ClientInputIsolation() {
    }

    public static boolean handleBattleKey(int keyCode, int scanCode, int action) {
        Minecraft minecraft = Minecraft.getInstance();
        InputConstants.Key pressedKey = InputConstants.getKey(keyCode, scanCode);
        boolean down = action != GLFW.GLFW_RELEASE;
        noteHeld(pressedKey, down);
        if (!ClientBattleController.blocksParticipantInput() || minecraft.screen != null || isAllowedSystemKey(keyCode)) {
            return false;
        }

        if (!isShiftKey(keyCode)) {
            laneKeyEvent(pressedKey, action);
        }
        return true;
    }

    /**
     * A key event while the battle has the keys. A repeat - the system sending a held key again and again - is never
     * a press and changes nothing: it used to set the lane down like a press, which was harmless while the lane was
     * already down, but a key that went down in the pause menu and is still held after it is up by design
     * (ClientBattleController#resumeLanesAfterGamePause), and its first repeat then pressed the lane by itself.
     */
    static void laneKeyEvent(InputConstants.Key key, int action) {
        if (action == GLFW.GLFW_REPEAT) {
            return;
        }
        setLanes(key, action != GLFW.GLFW_RELEASE);
    }

    public static boolean handleBattleMouseButton(int button, int action) {
        Minecraft minecraft = Minecraft.getInstance();
        InputConstants.Key pressedButton = InputConstants.Type.MOUSE.getOrCreate(button);
        boolean down = action != GLFW.GLFW_RELEASE;
        noteHeld(pressedButton, down);
        if (!ClientBattleController.blocksParticipantInput() || minecraft.screen != null) {
            return false;
        }

        setLanes(pressedButton, down);
        return true;
    }

    /** Whether this key or mouse button is down now, by the window's events (see {@link #HELD}). */
    public static boolean isHeld(InputConstants.Key key) {
        return key != null && HELD.contains(key);
    }

    private static void noteHeld(InputConstants.Key key, boolean down) {
        if (down) {
            HELD.add(key);
        } else {
            HELD.remove(key);
        }
    }

    /**
     * Sets the lanes bound to this key, and counts a key going down as a press the moment it happens. The battle
     * reads the lanes only once per client tick, and a tap shorter than a tick would otherwise never be read at all.
     * A held key's repeat events never come here (laneKeyEvent).
     */
    private static void setLanes(InputConstants.Key key, boolean down) {
        KeyMapping[] lanes = MusicBattleKeys.lanes();
        for (int lane = 0; lane < lanes.length; lane++) {
            if (lanes[lane].getKey().equals(key)) {
                BattleLaneLatch.LANES.keyChanged(lane, lanes[lane].isDown(), down);
                lanes[lane].setDown(down);
            }
        }
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

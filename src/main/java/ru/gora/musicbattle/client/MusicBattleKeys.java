package ru.gora.musicbattle.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.client.settings.KeyModifier;
import org.lwjgl.glfw.GLFW;

public final class MusicBattleKeys {
    public static final String CATEGORY = "key.categories.music_battle";

    public static final KeyMapping LANE_LEFT = new KeyMapping(
            "key.music_battle.lane_left",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_LEFT,
            CATEGORY
    );
    public static final KeyMapping LANE_DOWN = new KeyMapping(
            "key.music_battle.lane_down",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_DOWN,
            CATEGORY
    );
    public static final KeyMapping LANE_UP = new KeyMapping(
            "key.music_battle.lane_up",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_UP,
            CATEGORY
    );
    public static final KeyMapping LANE_RIGHT = new KeyMapping(
            "key.music_battle.lane_right",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_RIGHT,
            CATEGORY
    );
    public static final KeyMapping TOGGLE_SPECTATOR_CAMERA = new KeyMapping(
            "key.music_battle.toggle_spectator_camera",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_V,
            CATEGORY
    );
    public static final KeyMapping CAMERA_EDITOR_MOUSE_MODE = new KeyMapping(
            "key.music_battle.camera_editor_mouse_mode",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_M,
            CATEGORY
    );

    /**
     * Opens the Workshop; Alt+B by default (Option+B on macOS), rebindable (architect decision for wave M2b). Alt keeps
     * clear of vanilla's hard-coded Ctrl/Cmd+B narrator shortcut, so no input mixin is needed.
     */
    public static final KeyMapping OPEN_WORKSHOP = new KeyMapping(
            "key.music_battle.open_workshop",
            KeyConflictContext.IN_GAME,
            KeyModifier.ALT,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_B,
            CATEGORY
    );

    private static final KeyMapping[] LANES = {LANE_LEFT, LANE_DOWN, LANE_UP, LANE_RIGHT};

    private MusicBattleKeys() {
    }

    public static KeyMapping[] lanes() {
        return LANES;
    }

    public static void register(RegisterKeyMappingsEvent event) {
        for (KeyMapping mapping : LANES) {
            event.register(mapping);
        }
        event.register(TOGGLE_SPECTATOR_CAMERA);
        event.register(CAMERA_EDITOR_MOUSE_MODE);
        event.register(OPEN_WORKSHOP);
    }
}

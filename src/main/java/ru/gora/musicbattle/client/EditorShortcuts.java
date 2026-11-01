package ru.gora.musicbattle.client;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.function.IntPredicate;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

/**
 * The modifier keys of the chart editor's and the FX editor's shortcuts (Ctrl+A, C, V, Z, Shift+Z and the Ctrl press
 * that selects on the grid).
 *
 * <p>Ctrl works on every system, and on a Mac Cmd works too. Minecraft's own {@code Screen.hasControlDown()} means Cmd
 * on a Mac and nothing else, so a Mac player pressing the physical Ctrl+A the guide names got nothing, while the
 * physical Ctrl+click on the grid did select (see {@code TrackEditorScreen.macControlClick}): one feature answered to two
 * different keys. On Windows and Linux the Windows/Super key is not taken, as in vanilla.
 *
 * <p>The key state is read through one seam so editor-editing can drive the screens' real keyPressed and mouseClicked
 * with a stubbed keyboard on either platform, without a game window.
 */
public final class EditorShortcuts {
    private static IntPredicate keyDown;
    private static Boolean mac;

    private EditorShortcuts() {
    }

    /** Ctrl, or on a Mac Cmd as well: the modifier of the editors' letter shortcuts and of a selecting left press. */
    public static boolean shortcutDown() {
        return controlDown() || onMac() && (down(GLFW.GLFW_KEY_LEFT_SUPER) || down(GLFW.GLFW_KEY_RIGHT_SUPER));
    }

    /** The physical Ctrl key, whatever the system calls it. */
    public static boolean controlDown() {
        return down(GLFW.GLFW_KEY_LEFT_CONTROL) || down(GLFW.GLFW_KEY_RIGHT_CONTROL);
    }

    public static boolean shiftDown() {
        return down(GLFW.GLFW_KEY_LEFT_SHIFT) || down(GLFW.GLFW_KEY_RIGHT_SHIFT);
    }

    public static boolean onMac() {
        return mac != null ? mac : Minecraft.ON_OSX;
    }

    /**
     * For editor-editing only: the system and the keys held, in place of the game window's. {@code null} keys go back
     * to the live keyboard.
     */
    public static void stubForChecks(boolean onMac, IntPredicate keysDown) {
        mac = keysDown == null ? null : onMac;
        keyDown = keysDown;
    }

    private static boolean down(int key) {
        IntPredicate stub = keyDown;
        if (stub != null) {
            return stub.test(key);
        }
        return InputConstants.isKeyDown(Minecraft.getInstance().getWindow().getWindow(), key);
    }
}

package ru.gora.musicbattle.client.ui;

import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

/**
 * Hand and I-beam mouse cursors. Minecraft 1.21.1 has no cursor API, so this sets GLFW standard cursors
 * directly. A screen requests a cursor while rendering ({@link #request}) and applies it once at the end
 * ({@link #apply}); {@link UiLayer} resets it when the screen closes, and {@link UiClientEvents} resets it
 * every frame no Workshop screen is open, so a hand cursor can never leak into vanilla menus or the game.
 * Render thread only.
 */
public final class HandCursor {
    public enum Kind {
        ARROW, HAND, IBEAM
    }

    private static long hand;
    private static long ibeam;
    private static Kind current = Kind.ARROW;
    private static Kind requested = Kind.ARROW;

    private HandCursor() {
    }

    /** Start of a frame: nothing hovered yet. */
    public static void beginFrame() {
        requested = Kind.ARROW;
    }

    /** Hovering something clickable (HAND) or a text field (IBEAM). The last request in a frame wins. */
    public static void request(Kind kind) {
        requested = kind == null ? Kind.ARROW : kind;
    }

    /** End of a frame: switch the OS cursor if the request changed. */
    public static void apply() {
        set(requested);
    }

    /** Back to the arrow immediately. */
    public static void reset() {
        requested = Kind.ARROW;
        set(Kind.ARROW);
    }

    public static Kind current() {
        return current;
    }

    private static void set(Kind kind) {
        if (kind == current) {
            return;
        }
        long window = Minecraft.getInstance().getWindow().getWindow();
        if (window == 0L) {
            return;
        }
        long handle = 0L;
        if (kind == Kind.HAND) {
            if (hand == 0L) {
                hand = GLFW.glfwCreateStandardCursor(GLFW.GLFW_HAND_CURSOR);
            }
            handle = hand;
        } else if (kind == Kind.IBEAM) {
            if (ibeam == 0L) {
                ibeam = GLFW.glfwCreateStandardCursor(GLFW.GLFW_IBEAM_CURSOR);
            }
            handle = ibeam;
        }
        GLFW.glfwSetCursor(window, handle); // 0 restores the default arrow
        current = kind;
    }
}

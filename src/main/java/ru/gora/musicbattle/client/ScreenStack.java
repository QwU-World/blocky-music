package ru.gora.musicbattle.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/**
 * Finds one of the mod's screens whether it is the one on top or the one underneath.
 *
 * <p>Server messages are aimed at a screen ("the vote for battle X", "the panel at this block"), and the client used
 * to look only at {@code Minecraft.screen}. Open anything over it - the Workshop, a dialog, a confirmation - and the
 * message went nowhere: the vote never closed when the battle began, the panel never saw the track change. The chain
 * of {@link ParentedScreen#musicBattleParent()} is walked instead, so a screen keeps receiving what belongs to it
 * while the player is one step deeper in.
 *
 * <p>The walk is depth-capped: a parent chain is built by the mod itself, but a cycle from a future mistake must not
 * hang the client thread.
 */
public final class ScreenStack {
    /** Deeper than any stack the mod builds; a cycle stops here instead of spinning. */
    private static final int MAX_DEPTH = 8;

    private ScreenStack() {
    }

    /** The topmost screen of this type in the open stack, or null when there is none. */
    public static <T> T find(Class<T> type) {
        return find(Minecraft.getInstance().screen, type);
    }

    /** The topmost screen of this type at or below {@code from}, or null. */
    public static <T> T find(Screen from, Class<T> type) {
        Screen screen = from;
        for (int depth = 0; screen != null && depth < MAX_DEPTH; depth++) {
            if (type.isInstance(screen)) {
                return type.cast(screen);
            }
            screen = screen instanceof ParentedScreen parented ? parented.musicBattleParent() : null;
        }
        return null;
    }

    /** True when a screen of this type is open, on top or underneath. */
    public static boolean isOpen(Class<?> type) {
        return find(type) != null;
    }
}

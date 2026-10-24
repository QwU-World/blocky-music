package ru.gora.musicbattle.client;

import net.minecraft.client.gui.screens.Screen;

/**
 * A screen of this mod that was opened on top of another one and remembers what it came from.
 *
 * <p>The server finds the screen it needs to talk to by looking at whatever the player has open: "is this the vote
 * screen for that battle", "is this the panel for that DJ rack". That works until a second screen is opened over the
 * first, and then the message is silently dropped - the vote screen never hears that the battle started, the DJ panel
 * never hears that the track changed. It is why the Workshop could not be opened from anywhere inside a battle
 * without breaking it.
 *
 * <p>Implementing this lets {@link ru.gora.musicbattle.client.ScreenStack} find the screen underneath, so the mod's
 * own screens can be stacked the way the rest of Minecraft stacks its own.
 */
public interface ParentedScreen {
    /** The screen this one was opened from, or null when it was opened from the world. */
    Screen musicBattleParent();
}

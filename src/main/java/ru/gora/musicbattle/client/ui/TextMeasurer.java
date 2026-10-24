package ru.gora.musicbattle.client.ui;

/**
 * Width of a line of text in a Workshop style, in GUI units. Layout code measures through this interface so
 * layout checks can run without Minecraft (for example with {@code java.awt.Font} advances of the bundled
 * TTFs); in game it is {@link TextStyles#measurer()}.
 */
@FunctionalInterface
public interface TextMeasurer {
    float width(String text, TextStyle style);
}

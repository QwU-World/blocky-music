package ru.gora.musicbattle.client;

import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;

/**
 * The battle HUD keeps the size it was designed at, whatever the player's GUI scale is.
 *
 * <p>Everything in a battle - the lanes, the notes, the score, the combo, the effects - was drawn in Minecraft's
 * GUI units, so its size on screen was whatever the GUI scale said. The mod was built and tuned at GUI scale 3, and
 * at any other setting the playfield came out a different size from the one the charts were timed and aimed for.
 * The menus are a different matter: those are read, not played, and they should follow the player's setting like
 * every other menu in the game.
 *
 * <p>So the HUD gets a coordinate space of its own. One scale is pushed for the whole layer, and the canvas the HUD
 * lays itself out in is the GUI canvas converted into that space - the drawing code keeps its numbers, and one of
 * its units always covers the same number of real pixels. Because the factor is exactly {@code 3 / guiScale} and
 * Minecraft's fonts and widgets are nearest-filtered bitmaps, the result is what scale 3 looked like.
 *
 * <p>Small windows are the one exception: three is not always a scale the window can host, and forcing it would push
 * the playfield off the edges. There the largest scale that fits is used instead, which is exactly what Minecraft
 * itself would have chosen.
 *
 * <p>The arithmetic is pure and lives in {@link #frame(int, int, double, double)} so it can be checked without a
 * window (tools/HudScaleChecks.java).
 */
public final class BattleHudScale {
    /** The GUI scale the battle HUD was drawn at. */
    public static final double REFERENCE_SCALE = 3.0D;

    /**
     * The HUD's own space: {@code factor} converts one of its units into GUI units, and {@code width}/{@code height}
     * are the canvas to lay it out in.
     */
    public record Frame(float factor, int width, int height) {
        /** True when the player's setting already matches the design scale and nothing has to be pushed. */
        public boolean identity() {
            return Math.abs(factor - 1.0F) < 1.0E-4F;
        }
    }

    private BattleHudScale() {
    }

    /** The HUD space for the canvas this frame is being drawn into. */
    public static Frame frame(int guiWidth, int guiHeight) {
        Minecraft minecraft = Minecraft.getInstance();
        Window window = minecraft == null ? null : minecraft.getWindow();
        if (window == null) {
            return new Frame(1.0F, Math.max(1, guiWidth), Math.max(1, guiHeight));
        }
        double largestThatFits = window.getWidth() <= 0 || window.getHeight() <= 0
                ? REFERENCE_SCALE
                : Math.max(1, window.calculateScale(0, false));
        return frame(guiWidth, guiHeight, window.getGuiScale(), largestThatFits);
    }

    /**
     * The same rule, with the two things a window would have told us passed in.
     *
     * @param guiWidth        the canvas Minecraft handed the HUD, in GUI units
     * @param guiHeight       likewise
     * @param guiScale        what the player's setting resolved to
     * @param largestThatFits the largest scale this window can host, so a small one is not pushed off its own edges
     */
    public static Frame frame(int guiWidth, int guiHeight, double guiScale, double largestThatFits) {
        if (guiScale <= 0.0D || guiWidth <= 0 || guiHeight <= 0) {
            return new Frame(1.0F, Math.max(1, guiWidth), Math.max(1, guiHeight));
        }
        double design = Math.min(REFERENCE_SCALE, Math.max(1.0D, largestThatFits));
        float factor = (float) (design / guiScale);
        if (Math.abs(factor - 1.0F) < 1.0E-4F) {
            return new Frame(1.0F, guiWidth, guiHeight);
        }
        // Rounded up, the way Minecraft rounds its own canvas up: a fill that covers the whole HUD canvas must still
        // reach the edge of the screen rather than leave a seam of a pixel along it.
        int width = (int) Math.ceil(guiWidth / (double) factor);
        int height = (int) Math.ceil(guiHeight / (double) factor);
        return new Frame(factor, Math.max(1, width), Math.max(1, height));
    }
}

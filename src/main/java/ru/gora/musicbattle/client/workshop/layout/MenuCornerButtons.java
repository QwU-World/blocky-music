package ru.gora.musicbattle.client.workshop.layout;

/**
 * Where the mod's own buttons sit on vanilla's menus: "BM", the way into the mod's settings, in the top-left corner of
 * the title, pause and options screens, and "Workshop" in the top-right corner of the title screen and just right of
 * "BM" in the pause menu.
 *
 * <p>Both live in the strip above everything vanilla draws there. The title screen's logo starts 30 units down and its
 * buttons a quarter of the way down, the pause menu is a column in the middle, and the version and copyright lines
 * sit on the bottom edge, so a 20-unit row at the very top stays free at every GUI scale. The {@code menu-buttons}
 * check proves that against vanilla's own formulas for every screen size the game can produce, which is why the
 * numbers live here and not inline in the event handlers.
 */
public final class MenuCornerButtons {
    public static final int HEIGHT = 20;
    public static final int SETTINGS_WIDTH = 34;
    public static final int WORKSHOP_WIDTH = 96;
    private static final int MARGIN = 6;

    private MenuCornerButtons() {
    }

    /** The "BM" button. It does not depend on the screen size, so it is where players learned to find it. */
    public static Rect settings() {
        return Rect.of(MARGIN, 8, SETTINGS_WIDTH, HEIGHT);
    }

    /**
     * The pause menu's "Workshop" button. Toasts are drawn over the pause menu's top-right corner, so it sits beside
     * "BM" instead, still above the menu's title.
     */
    public static Rect pauseWorkshop() {
        Rect settings = settings();
        return Rect.of(settings.right() + 4, settings.y(), WORKSHOP_WIDTH, HEIGHT);
    }

    /** The title screen's "Workshop" button, against the right edge. */
    public static Rect workshop(int screenWidth) {
        return Rect.of(screenWidth - WORKSHOP_WIDTH - MARGIN, MARGIN, WORKSHOP_WIDTH, HEIGHT);
    }
}

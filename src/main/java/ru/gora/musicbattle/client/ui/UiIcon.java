package ru.gora.musicbattle.client.ui;

/**
 * Icons in the Workshop atlases ({@code textures/gui/workshop/icons_16|32|64.png}), in atlas order. Lucide
 * (ISC), rasterised by {@code tools/icons/build-icons.mjs}; the ui-toolkit check compares this order with
 * {@code icons.json}.
 */
public enum UiIcon {
    PLAY, PAUSE, DOWNLOAD, UPLOAD, HEART, HEART_FILLED, STAR, STAR_FILLED,
    MESSAGE_CIRCLE, SEARCH, EXTERNAL_LINK, LINK, COPY, X, CHECK, CHEVRON_DOWN,
    CHEVRON_LEFT, CHEVRON_RIGHT, ARROW_LEFT, MUSIC, USER_ROUND, SETTINGS, BELL, FLAG,
    TRASH, FOLDER_OPEN, REFRESH, LOADER, INFO, ALERT, EYE_OFF, FILTER;

    /** Cells per atlas row. */
    public static final int COLUMNS = 8;
    /** Atlas tiers: icon pixel size per cell. */
    public static final int[] TIERS = {16, 32, 64};
    private static final int ROWS = (values().length + COLUMNS - 1) / COLUMNS;

    /** Name in icons.json. */
    public String atlasName() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }

    public static int rows() {
        return ROWS;
    }

    /** Gutter around each icon in a tier's atlas: size / 8 transparent pixels on every side. */
    public static int gutter(int tier) {
        return tier / 8;
    }

    public static int cell(int tier) {
        return tier + 2 * gutter(tier);
    }

    public static int atlasWidth(int tier) {
        return cell(tier) * COLUMNS;
    }

    public static int atlasHeight(int tier) {
        return cell(tier) * rows();
    }

    /**
     * Smallest tier with at least as many pixels as the icon covers on screen, so the atlas is only ever
     * minified by less than 2x (LINEAR, no mipmaps). Larger icons use the 64 px tier.
     */
    public static int tierFor(double physicalPixels) {
        for (int tier : TIERS) {
            if (physicalPixels <= tier) {
                return tier;
            }
        }
        return TIERS[TIERS.length - 1];
    }

    /** Texture coordinates {u0, v0, u1, v1} of this icon in a tier's atlas (a new array). */
    public float[] uv(int tier) {
        return new float[] {uv(tier, 0), uv(tier, 1), uv(tier, 2), uv(tier, 3)};
    }

    /** One texture coordinate without allocating: component 0 u0, 1 v0, 2 u1, 3 v1. */
    public float uv(int tier, int component) {
        int index = ordinal();
        int cell = cell(tier);
        int gutter = gutter(tier);
        return switch (component) {
            case 0 -> ((index % COLUMNS) * cell + gutter) / (float) atlasWidth(tier);
            case 1 -> ((index / COLUMNS) * cell + gutter) / (float) atlasHeight(tier);
            case 2 -> ((index % COLUMNS) * cell + gutter + tier) / (float) atlasWidth(tier);
            default -> ((index / COLUMNS) * cell + gutter + tier) / (float) atlasHeight(tier);
        };
    }
}

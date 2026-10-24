package ru.gora.musicbattle.client.ui;

/**
 * Workshop text styles and their TrueType font tiers. Pure data: {@link TextStyles} turns these into Minecraft
 * font ids. Keep in step with {@code tools/fonts/build-fonts.py} (the ui-toolkit check compares the font
 * JSONs with {@link #fontPath}, {@link #size} and {@link #shiftY}).
 *
 * <p>Minecraft samples glyph textures with NEAREST filtering, so a glyph is crisp only when the provider's
 * oversample equals the physical pixels per GUI unit. There is one font JSON per style and integer tier 1..9,
 * and text is never pose-scaled.
 */
public enum TextStyle {
    /** Screen headings, Nunito 800. */
    DISPLAY("display", "nunito-extrabold", 800, 16.0F),
    /** Card and section titles, Nunito 700 (osu! card title 18 px). */
    TITLE("title", "nunito-bold", 700, 12.5F),
    /** Body text, labels, buttons, Nunito 600 (osu! artist line 14 px). */
    BODY("body", "nunito-semibold", 600, 10.0F),
    /** Body size in Nunito 700: active tabs, comment authors, card action links (osu! draws these bold). */
    STRONG("strong", "nunito-bold", 700, 10.0F),
    /** Secondary lines, dates, counters, Nunito 600 (osu! "mapped by" 11 px). */
    CAPTION("caption", "nunito-semibold", 600, 8.0F),
    /** Caption size in Nunito 700: the active filter option and sort tab, comment actions (osu! bolds the active value). */
    LABEL("label", "nunito-bold", 700, 8.0F),
    /** Upper-case status pills and badges, Nunito 800. */
    PILL("pill", "nunito-extrabold", 800, 7.5F);

    public static final int MIN_TIER = 1;
    public static final int MAX_TIER = 9;
    /** Nunito OS/2 sCapHeight / unitsPerEm (705 / 1000), measured by build-fonts.py. */
    public static final float CAP_HEIGHT_EM = 0.705F;
    /** Nunito hhea ascender and descender / unitsPerEm (1011 and 353). */
    public static final float ASCENT_EM = 1.011F;
    public static final float DESCENT_EM = 0.353F;
    /** Minecraft places a glyph's top at {@code y + 7 - bearingTop} (SheetGlyphInfo.getTop). */
    static final float MINECRAFT_ASCENT = 7.0F;

    private final String id;
    private final String fontFile;
    private final int weight;
    private final float size;

    TextStyle(String id, String fontFile, int weight, float size) {
        this.id = id;
        this.fontFile = fontFile;
        this.weight = weight;
        this.size = size;
    }

    public String id() {
        return id;
    }

    /** TTF file stem under {@code assets/music_battle/font/}. */
    public String fontFile() {
        return fontFile;
    }

    public int weight() {
        return weight;
    }

    /** Em size in GUI units. */
    public float size() {
        return size;
    }

    /** Height of capital letters in GUI units. Text drawn at y has its cap line at y. */
    public float capHeight() {
        return CAP_HEIGHT_EM * size;
    }

    /** Distance from the draw y to the baseline. */
    public float baseline() {
        return capHeight();
    }

    /** Default line advance for wrapped text: the font's ascender plus descender (CSS line-height: normal). */
    public float lineHeight() {
        return (ASCENT_EM + DESCENT_EM) * size;
    }

    /** The TTF provider's {@code shift[1]}: moves the cap line from y + 7 to y. Rounded to 0.01 like the build. */
    public float shiftY() {
        return Math.round((CAP_HEIGHT_EM * size - MINECRAFT_ASCENT) * 100.0F) / 100.0F;
    }

    /** Font JSON path without namespace and extension: {@code ui_<style>_x<tier>}. */
    public String fontPath(int tier) {
        return "ui_" + id + "_x" + clampTier(tier);
    }

    /**
     * The oversample tier for text drawn at this GUI scale and pose scale: physical pixels per GUI unit,
     * rounded, clamped to 1..9. Pose scale should be 1; animated or scaled text is not supported.
     */
    public static int tier(double guiScale, double poseScale) {
        double pixels = guiScale * poseScale;
        if (!(pixels > 0.0) || Double.isInfinite(pixels)) {
            return MIN_TIER;
        }
        return clampTier((int) Math.round(pixels));
    }

    static int clampTier(int tier) {
        return Math.max(MIN_TIER, Math.min(MAX_TIER, tier));
    }
}

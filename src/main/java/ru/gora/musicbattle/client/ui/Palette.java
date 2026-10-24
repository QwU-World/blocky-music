package ru.gora.musicbattle.client.ui;

/**
 * Blocky Music Workshop design tokens as ARGB, mirrored from the website's {@code public/css/tokens.css}
 * (spec 03 section 3, with the contrast adjustments from the 17 September 2026 review) at the default hue
 * 265. The ui-toolkit check recomputes every constant from the HSL values below and, when the website
 * repository sits next to this one, from tokens.css itself.
 *
 * <p>Conversion: CSS {@code hsl()} to sRGB, each channel rounded half up to 8 bits.
 */
public final class Palette {
    /** {@code --hue}. */
    public static final int HUE = 265;

    public static final int BG_0 = 0xFF110F14;          // hsl(265 14% 7%)   body, Workshop backdrop
    public static final int BG_1 = 0xFF1B1820;          // hsl(265 13% 11%)  page panels, header
    public static final int BG_2 = 0xFF25222B;          // hsl(265 12% 15%)  sections, search panel, toolbar
    public static final int BG_3 = 0xFF322D39;          // hsl(265 11% 20%)  cards, inputs
    public static final int BG_4 = 0xFF413C49;          // hsl(265 10% 26%)  hover surfaces, active tab
    public static final int LINE = 0xFF50495A;          // hsl(265 10% 32%)  borders, separators
    public static final int TEXT_1 = 0xFFFAF8FC;        // hsl(265 40% 98%)  primary text
    public static final int TEXT_2 = 0xFFDAD1E5;        // hsl(265 28% 86%)  secondary text
    public static final int TEXT_3 = 0xFFB0A5C0;        // hsl(265 18% 70%)  tertiary, inactive filters
    public static final int TEXT_MUTE = 0xFF9C92AA;     // hsl(265 12% 62%)  dates, counters (BG_0..BG_3 only)
    public static final int ACCENT = 0xFFBE8FFF;        // hsl(265 100% 78%) active tab bar, focus, progress ring
    public static final int ACCENT_STRONG = 0xFF8349D4; // hsl(265 62% 56%)  primary buttons
    public static final int ACCENT_DEEP = 0xFF5D2E9E;   // hsl(265 55% 40%)  pressed, selection
    public static final int LINK = 0xFFC7B0E8;          // hsl(265 55% 80%)
    public static final int ON_PILL = 0xFF231D2B;       // hsl(265 20% 14%)  text on coloured pills

    public static final int ST_FEATURED = 0xFFFFD152;   // hsl(44 100% 66%)
    public static final int ST_COMMUNITY = 0xFF5AD8AA;  // hsl(158 62% 60%)
    public static final int ST_WIP = 0xFFFF985C;        // hsl(22 100% 68%)
    public static final int ST_PENDING = 0xFF6FBEF6;    // hsl(205 88% 70%)
    public static final int ST_HIDDEN = 0xFF928B9C;     // hsl(265 8% 58%)
    public static final int BADGE_LIBRARY = 0xFF51E0F6; // hsl(188 90% 64%)
    public static final int BADGE_EXPLICIT = 0xFFFAAD42; // hsl(35 95% 62%)
    public static final int BADGE_AI = 0xFFCE9DE7;      // hsl(280 60% 76%)
    public static final int WARN = 0xFFFFB829;          // hsl(40 100% 58%)
    public static final int DANGER = 0xFFF04251;        // hsl(355 85% 60%)
    public static final int OK = 0xFF8ADA2F;            // hsl(88 70% 52%)
    public static final int RATING_NEG = 0xFFFFB829;    // hsl(40 100% 58%)
    public static final int RATING_BAR = 0xFF409CDD;    // hsl(205 70% 56%)

    /** {@code --shadow}: hsl(265 30% 2% / .35). */
    public static final int SHADOW = 0x59050407;
    /** {@code --shadow-lifted}: hsl(265 30% 2% / .45). */
    public static final int SHADOW_LIFTED = 0x73050407;

    /** One CSS px of the website / one osu! px at 1080p, in GUI units at GUI scale 2 (research section 1). */
    public static final float PX = 0.7F;
    /** {@code --r-sm}, {@code --r-md}, {@code --r-lg} (4, 8, 12 px). */
    public static final float RADIUS_SM = 4 * PX;
    public static final float RADIUS_MD = 8 * PX;
    public static final float RADIUS_LG = 12 * PX;
    /** {@code --s-1} .. {@code --s-16} (4 .. 64 px). */
    public static final float SPACE_1 = 4 * PX;
    public static final float SPACE_2 = 8 * PX;
    public static final float SPACE_3 = 12 * PX;
    public static final float SPACE_4 = 16 * PX;
    public static final float SPACE_5 = 20 * PX;
    public static final float SPACE_6 = 24 * PX;
    public static final float SPACE_8 = 32 * PX;
    /** {@code --t-hover}, {@code --t-card}, {@code --t-bar}, {@code --t-fade}, milliseconds. */
    public static final int T_HOVER = 120;
    public static final int T_CARD = 360;
    public static final int T_BAR = 250;
    public static final int T_FADE = 200;

    private Palette() {
    }

    /** CSS {@code hsl(h s% l%)} as opaque ARGB. */
    public static int hsl(double hue, double saturationPercent, double lightnessPercent) {
        return hsla(hue, saturationPercent, lightnessPercent, 1.0);
    }

    /** CSS {@code hsl(h s% l% / a)} as ARGB (CSS Color 4 algorithm, channels rounded half up). */
    public static int hsla(double hue, double saturationPercent, double lightnessPercent, double alpha) {
        double s = clamp(saturationPercent / 100.0);
        double l = clamp(lightnessPercent / 100.0);
        double h = ((hue % 360.0) + 360.0) % 360.0;
        int r = channel(hslChannel(0, h, s, l));
        int g = channel(hslChannel(8, h, s, l));
        int b = channel(hslChannel(4, h, s, l));
        return channel(alpha) << 24 | r << 16 | g << 8 | b;
    }

    private static double hslChannel(int n, double h, double s, double l) {
        double k = (n + h / 30.0) % 12.0;
        double a = s * Math.min(l, 1.0 - l);
        return l - a * Math.max(-1.0, Math.min(Math.min(k - 3.0, 9.0 - k), 1.0));
    }

    static int channel(double unit) {
        return (int) Math.floor(clamp(unit) * 255.0 + 0.5);
    }

    private static double clamp(double v) {
        return v != v ? 0.0 : Math.max(0.0, Math.min(1.0, v));
    }

    /** Same colour with alpha replaced (0..1). */
    public static int withAlpha(int argb, float alpha) {
        return channel(alpha) << 24 | (argb & 0xFFFFFF);
    }

    /** Same colour with its alpha multiplied (0..1), for fades. */
    public static int fade(int argb, float factor) {
        int a = argb >>> 24;
        return (int) Math.floor(a * clamp(factor) + 0.5) << 24 | (argb & 0xFFFFFF);
    }

    /** Straight per-channel sRGB blend, t = 0 gives {@code from}. For hover tints, not for the spectrum. */
    public static int mix(int from, int to, float t) {
        double u = clamp(t);
        int a = (int) Math.floor((from >>> 24) + ((to >>> 24) - (from >>> 24)) * u + 0.5);
        int r = (int) Math.floor((from >> 16 & 255) + ((to >> 16 & 255) - (from >> 16 & 255)) * u + 0.5);
        int g = (int) Math.floor((from >> 8 & 255) + ((to >> 8 & 255) - (from >> 8 & 255)) * u + 0.5);
        int b = (int) Math.floor((from & 255) + ((to & 255) - (from & 255)) * u + 0.5);
        return a << 24 | r << 16 | g << 8 | b;
    }
}

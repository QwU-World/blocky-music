package ru.gora.musicbattle.client.ui;

import java.util.Locale;

/**
 * Difficulty colours by star rating, mirroring the website's {@code client/spectrum.ts} (re-exported as
 * {@code src/shared/spectrum.ts}): the same stops, the same maths, the same pill and dot rules.
 * The ui-toolkit check compares this class with the website's generated {@code spectrum.css} when the website
 * repository sits next to this one.
 *
 * <p>Colour: clamp stars to the first and last stop, find the segment, interpolate linearly in linear light (sRGB
 * transfer function of IEC 61966-2-1), convert back and round each channel half up to 8 bits. The website draws
 * colours in 0.1-star buckets ({@link #bucket}); {@link #displayArgb} gives that bucketed colour.
 */
public final class Spectrum {
    /** Stop positions in stars; the last stop holds for everything above it ("10+"). */
    static final double[] STOPS = {0.0, 1.5, 2.5, 3.5, 4.5, 5.5, 6.5, 7.5, 8.5, 10.0};
    /** Stop colours, RGB. */
    static final int[] COLOURS = {
            0x7FB2FF, 0x56D6FF, 0x52EFC0, 0xA8EE5C, 0xFFE25E, 0xFFA25A, 0xFF6470, 0xD65AD8, 0x8363FF, 0x2A1A66
    };
    /** The star pill's dark ink and the dark ★ glyph: hsl(265 20% 12%). */
    public static final int GLYPH_DARK = 0xFF1E1825;
    /** The star pill's inner capsule behind the number: hsl(265 20% 12% / .85); its text is {@link Palette#TEXT_1}. */
    public static final int PILL_INNER = 0xD91E1825;
    /** Dots under 3:1 contrast against their surface, and every dot from this rating, get a 1 px ring. */
    public static final double RING_FROM = 9.5;
    public static final double RING_MIN_CONTRAST = 3.0;
    /** Highest website colour bucket: 12.0 stars. */
    public static final int MAX_BUCKET = 120;

    private Spectrum() {
    }

    public static int stopCount() {
        return STOPS.length;
    }

    public static double stopStars(int index) {
        return STOPS[index];
    }

    public static int stopRgb(int index) {
        return COLOURS[index];
    }

    /** Opaque ARGB colour for a star rating. NaN, infinities and negatives count as 0 (website: Number.isFinite). */
    public static int argb(double stars) {
        return 0xFF000000 | rgb(stars);
    }

    public static int rgb(double stars) {
        double s = Double.isFinite(stars) ? Math.max(0.0, stars) : 0.0;
        int last = STOPS.length - 1;
        if (s >= STOPS[last]) {
            return COLOURS[last];
        }
        for (int i = 1; i < STOPS.length; i++) {
            if (s <= STOPS[i]) {
                double t = (s - STOPS[i - 1]) / (STOPS[i] - STOPS[i - 1]);
                int a = COLOURS[i - 1];
                int b = COLOURS[i];
                return mixChannel(a >> 16 & 255, b >> 16 & 255, t) << 16
                        | mixChannel(a >> 8 & 255, b >> 8 & 255, t) << 8
                        | mixChannel(a & 255, b & 255, t);
            }
        }
        return COLOURS[last];
    }

    /** The website's colour class bucket for a rating: floor(stars * 10), 0..120 ({@code .sc-<n>}). */
    public static int bucket(double stars) {
        double s = Double.isFinite(stars) ? stars : 0.0;
        return (int) Math.max(0, Math.min(MAX_BUCKET, Math.floor(s * 10.0 + 1e-9)));
    }

    /** The colour the website shows for a rating (its 0.1-star bucket). Use this next to website-styled data. */
    public static int displayArgb(double stars) {
        return argb(bucket(stars) / 10.0);
    }

    /** True when the ★ glyph on a star pill of this rating is dark ({@link #GLYPH_DARK}), else {@link Palette#TEXT_1}. */
    public static boolean glyphIsDark(double stars) {
        int colour = rgb(stars);
        return contrast(colour, GLYPH_DARK) >= contrast(colour, Palette.TEXT_1);
    }

    public static int glyphArgb(double stars) {
        return glyphIsDark(stars) ? GLYPH_DARK : Palette.TEXT_1;
    }

    /** True when a dot, disc or swatch of this rating needs a 1 px {@link Palette#TEXT_3} ring on {@code surfaceArgb}. */
    public static boolean needsRing(double stars, int surfaceArgb) {
        return stars >= RING_FROM || contrast(rgb(stars), surfaceArgb) < RING_MIN_CONTRAST;
    }

    /** {@link #needsRing(double, int)} on cards ({@link Palette#BG_3}). */
    public static boolean needsRing(double stars) {
        return needsRing(stars, Palette.BG_3);
    }

    /** Star rating as shown: floored to two decimals, never rounded up ("6.9999" shows 6.99), Locale.ROOT. */
    public static String formatStars(double stars) {
        double value = Math.floor(stars * 100.0 + 1e-9) / 100.0;
        return String.format(Locale.ROOT, "%.2f", value);
    }

    /** WCAG relative luminance of an RGB (or ARGB; alpha ignored) colour. */
    public static double luminance(int rgb) {
        return 0.2126 * toLinear(rgb >> 16 & 255) + 0.7152 * toLinear(rgb >> 8 & 255) + 0.0722 * toLinear(rgb & 255);
    }

    /** WCAG contrast ratio of two colours, 1..21. */
    public static double contrast(int a, int b) {
        double la = luminance(a);
        double lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    private static int mixChannel(int from, int to, double t) {
        double lin = toLinear(from) + (toLinear(to) - toLinear(from)) * t;
        long rounded = Math.round(fromLinear(lin));
        return (int) Math.max(0, Math.min(255, rounded));
    }

    /** Channel 0..255 to linear light 0..1. */
    static double toLinear(int channel) {
        double v = channel / 255.0;
        return v <= 0.04045 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
    }

    /** Linear light to a channel value 0..255, not yet rounded. */
    static double fromLinear(double v) {
        return 255.0 * (v <= 0.0031308 ? v * 12.92 : 1.055 * Math.pow(v, 1.0 / 2.4) - 0.055);
    }
}

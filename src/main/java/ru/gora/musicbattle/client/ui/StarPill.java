package ru.gora.musicbattle.client.ui;

import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.FormattedCharSequence;

/**
 * The website's star pill and difficulty dot ({@code .star-pill}, {@code .dot} in beatmaps.css) in GUI units:
 * a pill in the rating's colour holding a ★ in the better-contrast ink and the value in a dark inner capsule;
 * dots of 3.5 x 7 with a {@link Palette#TEXT_3} ring when they would disappear against their surface.
 * Shapes and text are separate calls so grids can batch them in passes. Render thread only.
 */
public final class StarPill {
    public static final float HEIGHT = TextStyle.PILL.capHeight() + 5.6F;
    public static final float DOT_WIDTH = 3.5F;
    public static final float DOT_HEIGHT = 7.0F;
    /** Dot advance: width plus osu!'s 1 px spacing. */
    public static final float DOT_STEP = 4.5F;
    private static final float PAD_LEFT = 7 * Palette.PX;
    private static final float PAD = 2 * Palette.PX;
    private static final float GAP = 3 * Palette.PX;
    private static final float INNER_PAD_X = 6 * Palette.PX;
    private static final float RING = 1 * Palette.PX;
    private static final Map<Long, Layout> CACHE = new LinkedHashMap<>(64, 0.75F, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, Layout> eldest) {
            return size() > 256;
        }
    };

    private StarPill() {
    }

    private record Layout(FormattedCharSequence glyph, float glyphWidth, FormattedCharSequence value, float valueWidth) {
        float width() {
            return PAD_LEFT + glyphWidth + GAP + INNER_PAD_X * 2 + valueWidth + PAD;
        }
    }

    private static Layout layout(double stars) {
        int tier = TextStyles.tier();
        long hundredths = (long) Math.floor(stars * 100.0 + 1e-9);
        long key = hundredths * 16 + tier;
        Layout layout = CACHE.get(key);
        if (layout == null) {
            FormattedCharSequence glyph = TextStyles.text("★", TextStyle.PILL).getVisualOrderText();
            FormattedCharSequence value = TextStyles.text(Spectrum.formatStars(stars), TextStyle.PILL).getVisualOrderText();
            layout = new Layout(glyph, TextStyles.width(glyph), value, TextStyles.width(value));
            CACHE.put(key, layout);
        }
        return layout;
    }

    public static float width(double stars) {
        return layout(stars).width();
    }

    /** Queues the pill background and inner capsule. */
    public static void shapes(GuiGraphics g, float x, float y, double stars) {
        Layout layout = layout(stars);
        RoundedRect.pill(g, x, y, layout.width(), HEIGHT, Spectrum.displayArgb(stars));
        float capsuleX = x + PAD_LEFT + layout.glyphWidth + GAP;
        RoundedRect.pill(g, capsuleX, y + PAD, INNER_PAD_X * 2 + layout.valueWidth, HEIGHT - PAD * 2, Spectrum.PILL_INNER);
    }

    /** Queues the ★ and the value. */
    public static void text(GuiGraphics g, float x, float y, double stars) {
        Layout layout = layout(stars);
        float textY = TextStyles.centeredCapY(TextStyle.PILL, y, HEIGHT);
        TextStyles.queue(g, layout.glyph, x + PAD_LEFT, textY, Spectrum.glyphArgb(stars));
        TextStyles.queue(g, layout.value, x + PAD_LEFT + layout.glyphWidth + GAP + INNER_PAD_X, textY, Palette.TEXT_1);
    }

    /** One difficulty dot at (x, y), ringed when it needs contrast against {@code surfaceArgb}. */
    public static void dot(GuiGraphics g, float x, float y, double stars, int surfaceArgb) {
        if (Spectrum.needsRing(stars, surfaceArgb)) {
            RoundedRect.pill(g, x - RING, y - RING, DOT_WIDTH + RING * 2, DOT_HEIGHT + RING * 2, Palette.TEXT_3);
        }
        RoundedRect.pill(g, x, y, DOT_WIDTH, DOT_HEIGHT, Spectrum.displayArgb(stars));
    }
}

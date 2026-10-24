package ru.gora.musicbattle.client.ui;

import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import ru.gora.musicbattle.MusicBattleMod;

/**
 * Minecraft side of the Workshop text styles: font ids per oversample tier, measuring that respects the
 * style's font ({@code Font.width(String)} does not), ellipsis and wrapping with our own line height, and
 * queued drawing that does not flush. Render thread only.
 *
 * <p>Rules (research section 4): no pose scaling of text; no bold style (it smears by one GUI unit) and no
 * shadow; draw user text as components so bidi reordering applies; skip text whose alpha is below 4, which
 * Minecraft would otherwise draw opaque. Edit boxes keep the vanilla font, because {@code EditBox} measures
 * its caret with it; see {@link #editBox}.
 */
public final class TextStyles {
    private static final ResourceLocation[][] IDS = new ResourceLocation[TextStyle.values().length][TextStyle.MAX_TIER + 1];
    private static final Style[][] STYLES = new Style[TextStyle.values().length][TextStyle.MAX_TIER + 1];
    private static final String ELLIPSIS = "…";

    static {
        for (TextStyle style : TextStyle.values()) {
            for (int tier = TextStyle.MIN_TIER; tier <= TextStyle.MAX_TIER; tier++) {
                ResourceLocation id = MusicBattleMod.id(style.fontPath(tier));
                IDS[style.ordinal()][tier] = id;
                STYLES[style.ordinal()][tier] = Style.EMPTY.withFont(id);
            }
        }
    }

    private TextStyles() {
    }

    public static Font font() {
        return Minecraft.getInstance().font;
    }

    public static double guiScale() {
        return Minecraft.getInstance().getWindow().getGuiScale();
    }

    /** Oversample tier for unscaled text at the current GUI scale. */
    public static int tier() {
        return TextStyle.tier(guiScale(), 1.0);
    }

    public static ResourceLocation fontId(TextStyle style, int tier) {
        return IDS[style.ordinal()][TextStyle.clampTier(tier)];
    }

    /** The style's font at the current tier. Cached; safe to call per frame. */
    public static Style style(TextStyle style) {
        return STYLES[style.ordinal()][tier()];
    }

    public static MutableComponent text(String text, TextStyle style) {
        return Component.literal(text == null ? "" : text).withStyle(style(style));
    }

    /** A translated line in a style; arguments are plain strings (format numbers with Locale.ROOT first). */
    public static MutableComponent translated(String key, TextStyle style, Object... args) {
        return Component.translatable(key, args).withStyle(style(style));
    }

    /** Re-fonts any component (its parts that set no font of their own inherit this one). */
    public static MutableComponent styled(Component component, TextStyle style) {
        return component.copy().withStyle(style(style));
    }

    public static float width(FormattedText text) {
        return font().getSplitter().stringWidth(text);
    }

    public static float width(FormattedCharSequence text) {
        return font().getSplitter().stringWidth(text);
    }

    /** Measures with Minecraft's font system; the in-game {@link TextMeasurer}. */
    public static TextMeasurer measurer() {
        return (text, style) -> width(text(text, style));
    }

    /** One line, cut with an ellipsis in the same font when wider than {@code maxWidth}. */
    public static FormattedCharSequence ellipsize(Component text, float maxWidth) {
        Font font = font();
        if (font.getSplitter().stringWidth(text) <= maxWidth) {
            return text.getVisualOrderText();
        }
        Component ellipsis = Component.literal(ELLIPSIS).withStyle(text.getStyle());
        int room = (int) Math.floor(maxWidth - font.getSplitter().stringWidth(ellipsis));
        if (room <= 0) {
            return ellipsis.getVisualOrderText();
        }
        FormattedText head = font.substrByWidth(text, room);
        return Language.getInstance().getVisualOrder(FormattedText.composite(head, ellipsis));
    }

    /** Word-wrapped lines; lay them out with {@link TextStyle#lineHeight()}, not Minecraft's 9. */
    public static List<FormattedCharSequence> wrap(Component text, float maxWidth) {
        return font().split(text, Math.max(1, (int) Math.floor(maxWidth)));
    }

    /** Rounds a GUI coordinate to whole physical pixels, so TTF glyphs land on the pixel grid. */
    public static float snap(float value) {
        double scale = guiScale();
        return (float) (Math.round(value * scale) / scale);
    }

    /** Queues text with its cap line at y. Not drawn until the next flush or render-type switch. */
    public static void queue(GuiGraphics g, FormattedCharSequence text, float x, float y, int argb) {
        if (text == null || (argb >>> 24) < 4) {
            return;
        }
        font().drawInBatch(text, snap(x), snap(y), argb, false, g.pose().last().pose(), g.bufferSource(),
                Font.DisplayMode.NORMAL, 0, LightTexture.FULL_BRIGHT);
    }

    public static void queue(GuiGraphics g, Component text, float x, float y, int argb) {
        queue(g, text.getVisualOrderText(), x, y, argb);
    }

    public static void queueCentered(GuiGraphics g, FormattedCharSequence text, float centerX, float y, int argb) {
        queue(g, text, centerX - width(text) * 0.5F, y, argb);
    }

    public static void queueRight(GuiGraphics g, FormattedCharSequence text, float right, float y, int argb) {
        queue(g, text, right - width(text), y, argb);
    }

    /** Queues wrapped lines; returns the block height ({@code lines * lineHeight}). */
    public static float queueLines(GuiGraphics g, List<FormattedCharSequence> lines, float x, float y, float lineHeight, int argb) {
        for (int i = 0; i < lines.size(); i++) {
            queue(g, lines.get(i), x, y + i * lineHeight, argb);
        }
        return lines.size() * lineHeight;
    }

    /** y that vertically centres a style's capital letters in a box (pills, buttons). */
    public static float centeredCapY(TextStyle style, float boxY, float boxHeight) {
        return boxY + (boxHeight - style.capHeight()) * 0.5F;
    }

    /**
     * An edit box that keeps the vanilla font (its caret and selection are measured with it) but drops the
     * vanilla border and shadow, so the screen can draw its own rounded field around it.
     */
    public static EditBox editBox(int x, int y, int width, int height, Component narration) {
        EditBox box = new EditBox(font(), x, y, width, height, narration);
        box.setBordered(false);
        box.setTextShadow(false);
        box.setTextColor(Palette.TEXT_1);
        box.setTextColorUneditable(Palette.TEXT_MUTE);
        return box;
    }

    /**
     * A single line of text whose ellipsized form is cached until the text, the width, the GUI scale tier or
     * the language changes. Build one per card or label, not per frame.
     */
    public static final class Label {
        private Component source;
        private final TextStyle style;
        private int cachedTier = -1;
        private float cachedMaxWidth = Float.NaN;
        private Language cachedLanguage;
        private FormattedCharSequence line;
        private float width;

        public Label(Component text, TextStyle style) {
            this.source = text;
            this.style = style;
        }

        public static Label of(String text, TextStyle style) {
            return new Label(Component.literal(text == null ? "" : text), style);
        }

        public void set(Component text) {
            if (!text.equals(source)) {
                source = text;
                cachedTier = -1;
            }
        }

        public TextStyle style() {
            return style;
        }

        public FormattedCharSequence line(float maxWidth) {
            int tier = tier();
            Language language = Language.getInstance();
            if (tier != cachedTier || maxWidth != cachedMaxWidth || language != cachedLanguage) {
                cachedTier = tier;
                cachedMaxWidth = maxWidth;
                cachedLanguage = language;
                Component styled = source.copy().withStyle(STYLES[style.ordinal()][tier]);
                line = ellipsize(styled, maxWidth);
                width = TextStyles.width(line);
            }
            return line;
        }

        /** Width of the last computed line. */
        public float width() {
            return width;
        }

        public void queue(GuiGraphics g, float x, float y, float maxWidth, int argb) {
            TextStyles.queue(g, line(maxWidth), x, y, argb);
        }
    }
}

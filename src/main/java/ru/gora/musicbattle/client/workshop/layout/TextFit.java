package ru.gora.musicbattle.client.workshop.layout;

import java.util.ArrayList;
import java.util.List;
import ru.gora.musicbattle.client.ui.TextMeasurer;
import ru.gora.musicbattle.client.ui.TextStyle;

/**
 * Cutting and wrapping text to a width with the very font the screen draws with ({@link TextMeasurer} is Minecraft's
 * font in game and the bundled Nunito TTFs in the layout check). Every string a Workshop screen draws goes through
 * here first, so a layout can never overflow its box.
 */
public final class TextFit {
    public static final String ELLIPSIS = "…";
    /** Ink below the cap line (descenders) as a fraction of the em size; used for text boxes. */
    public static final float DESCENDER_EM = 0.21F;

    private TextFit() {
    }

    public static float width(TextMeasurer measurer, String text, TextStyle style) {
        return text == null || text.isEmpty() ? 0F : measurer.width(text, style);
    }

    /** The height of a text box that holds capitals and descenders of this style. */
    public static float boxHeight(TextStyle style) {
        return style.capHeight() + DESCENDER_EM * style.size();
    }

    /** One line, cut with an ellipsis when it is wider than {@code maxWidth}. */
    public static String ellipsize(TextMeasurer measurer, String text, TextStyle style, float maxWidth) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        if (maxWidth <= 0) {
            return "";
        }
        if (width(measurer, text, style) <= maxWidth) {
            return text;
        }
        float ellipsis = width(measurer, ELLIPSIS, style);
        if (ellipsis > maxWidth) {
            return "";
        }
        int low = 0;
        int high = text.length();
        while (low < high) {
            int mid = (low + high + 1) >>> 1;
            if (width(measurer, text.substring(0, mid), style) + ellipsis <= maxWidth) {
                low = mid;
            } else {
                high = mid - 1;
            }
        }
        while (low > 0 && Character.isHighSurrogate(text.charAt(low - 1))) {
            low--;
        }
        return text.substring(0, low).stripTrailing() + ELLIPSIS;
    }

    /** Word wrapping; words longer than the width are broken by character. Newlines start a new line. */
    public static List<String> wrap(TextMeasurer measurer, String text, TextStyle style, float maxWidth) {
        List<String> lines = new ArrayList<>();
        if (text == null || text.isEmpty() || maxWidth <= 0) {
            return lines;
        }
        for (String paragraph : text.split("\n", -1)) {
            wrapParagraph(measurer, paragraph, style, maxWidth, lines);
        }
        return lines;
    }

    /** Wrapping with a line cap; the last line ends with an ellipsis when text was left over. */
    public static List<String> wrap(TextMeasurer measurer, String text, TextStyle style, float maxWidth, int maxLines) {
        List<String> all = wrap(measurer, text, style, maxWidth);
        if (maxLines <= 0 || all.size() <= maxLines) {
            return all;
        }
        List<String> cut = new ArrayList<>(all.subList(0, maxLines));
        String last = cut.get(maxLines - 1);
        cut.set(maxLines - 1, ellipsize(measurer, last + " " + ELLIPSIS, style, maxWidth));
        return cut;
    }

    private static void wrapParagraph(TextMeasurer measurer, String paragraph, TextStyle style, float maxWidth, List<String> out) {
        if (paragraph.isEmpty()) {
            out.add("");
            return;
        }
        StringBuilder line = new StringBuilder();
        int index = 0;
        while (index < paragraph.length()) {
            int space = paragraph.indexOf(' ', index);
            String word = space < 0 ? paragraph.substring(index) : paragraph.substring(index, space + 1);
            index = space < 0 ? paragraph.length() : space + 1;
            if (line.length() == 0 && width(measurer, word.stripTrailing(), style) > maxWidth) {
                // A single word longer than the line: break it by character.
                String rest = word;
                while (!rest.isEmpty()) {
                    int fit = fitChars(measurer, rest, style, maxWidth);
                    if (fit <= 0) {
                        fit = 1;
                    }
                    out.add(rest.substring(0, fit));
                    rest = rest.substring(fit);
                    if (width(measurer, rest.stripTrailing(), style) <= maxWidth) {
                        line.append(rest);
                        break;
                    }
                }
                continue;
            }
            if (width(measurer, (line + word).stripTrailing(), style) > maxWidth && line.length() > 0) {
                out.add(line.toString().stripTrailing());
                line.setLength(0);
            }
            line.append(word);
        }
        if (line.length() > 0 || out.isEmpty()) {
            out.add(line.toString().stripTrailing());
        }
    }

    private static int fitChars(TextMeasurer measurer, String text, TextStyle style, float maxWidth) {
        int low = 0;
        int high = text.length();
        while (low < high) {
            int mid = (low + high + 1) >>> 1;
            if (width(measurer, text.substring(0, mid), style) <= maxWidth) {
                low = mid;
            } else {
                high = mid - 1;
            }
        }
        while (low > 0 && Character.isHighSurrogate(text.charAt(low - 1))) {
            low--;
        }
        return low;
    }
}

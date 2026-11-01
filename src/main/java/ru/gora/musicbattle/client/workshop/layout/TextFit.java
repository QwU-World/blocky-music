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
    /**
     * How much wider than its room a string may measure and still count as fitting. Layouts size a box to its text
     * and hand the text back the box less its padding, and that float round trip ({@code (w + 6) - 6}) can come out a
     * hair under {@code w}: with glyph advances in thirds, fifths and sevenths of a unit at GUI scales 3, 5, 6, 7 and
     * 9, chips that were measured to fit came out cut ("Any" as "A…" at GUI scale 5, "UPDATE" at 7). A hundredth of a
     * unit is under a tenth of a pixel at the largest GUI scale, so nothing that passes this can be seen to overflow.
     */
    public static final float FIT_EPSILON = 0.01F;

    private TextFit() {
    }

    public static float width(TextMeasurer measurer, String text, TextStyle style) {
        return text == null || text.isEmpty() ? 0F : measurer.width(text, style);
    }

    /** Whether {@code text} fits {@code maxWidth}, allowing for the float round trip of {@link #FIT_EPSILON}. */
    public static boolean fits(TextMeasurer measurer, String text, TextStyle style, float maxWidth) {
        return width(measurer, text, style) <= maxWidth + FIT_EPSILON;
    }

    /**
     * Whether wrapping {@code text} to {@code maxWidth} has to break a word between its letters: a word (text between
     * spaces or newlines) wider than the line. {@link #wrap} breaks such a word by character, which is right for a
     * pasted link and wrong for the interface's own words ("Откровенн" / "ый контент").
     */
    public static boolean breaksWord(TextMeasurer measurer, String text, TextStyle style, float maxWidth) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        for (String word : text.split("[ \n]+")) {
            if (!word.isEmpty() && !fits(measurer, word, style, maxWidth)) {
                return true;
            }
        }
        return false;
    }

    /**
     * As many whole parts of {@code text} - the pieces between {@code separator}s - as fit {@code maxWidth}, from the
     * start: a line of facts ("1.2K downloads  88 favourites  5 days ago") loses its last facts rather than ending in
     * half a word. When even the first part does not fit, the whole text comes back for the caller to cut as before.
     */
    public static String wholeParts(TextMeasurer measurer, String text, String separator, TextStyle style, float maxWidth) {
        if (text == null || fits(measurer, text, style, maxWidth)) {
            return text;
        }
        String best = null;
        int from = 0;
        while (true) {
            int at = text.indexOf(separator, from);
            if (at < 0) {
                break;
            }
            String head = text.substring(0, at).stripTrailing();
            if (!head.isEmpty() && fits(measurer, head, style, maxWidth)) {
                best = head;
            }
            from = at + separator.length();
        }
        return best == null ? text : best;
    }

    /** The widest word of {@code text} (text between spaces or newlines): the narrowest line it wraps into whole. */
    public static float widestWord(TextMeasurer measurer, String text, TextStyle style) {
        float widest = 0F;
        if (text == null || text.isEmpty()) {
            return widest;
        }
        for (String word : text.split("[ \n]+")) {
            widest = Math.max(widest, width(measurer, word, style));
        }
        return widest;
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
        if (fits(measurer, text, style, maxWidth)) {
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
        return cap(measurer, wrap(measurer, text, style, maxWidth), style, maxWidth, maxLines);
    }

    /** Wrapped lines held to {@code maxLines}; the last kept line ends with an ellipsis when lines were left over. */
    public static List<String> cap(TextMeasurer measurer, List<String> all, TextStyle style, float maxWidth, int maxLines) {
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
            // The line so far goes first when this word does not fit after it. Breaking a too-long word was only tried
            // at the start of a line, so a link or a hash that followed other words got a line of its own uncut and
            // ran past the box ("SHA-256 0123…cdef" on the beatmap page).
            if (line.length() > 0 && !fits(measurer, (line + word).stripTrailing(), style, maxWidth)) {
                out.add(line.toString().stripTrailing());
                line.setLength(0);
            }
            if (line.length() == 0 && !fits(measurer, word.stripTrailing(), style, maxWidth)) {
                // A single word longer than the line: break it by character.
                String rest = word;
                while (!rest.isEmpty()) {
                    int fit = fitChars(measurer, rest, style, maxWidth);
                    if (fit <= 0) {
                        fit = 1;
                    }
                    out.add(rest.substring(0, fit));
                    rest = rest.substring(fit);
                    if (fits(measurer, rest.stripTrailing(), style, maxWidth)) {
                        line.append(rest);
                        break;
                    }
                }
                continue;
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
            if (fits(measurer, text.substring(0, mid), style, maxWidth)) {
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

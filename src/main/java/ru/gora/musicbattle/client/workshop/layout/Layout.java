package ru.gora.musicbattle.client.workshop.layout;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import ru.gora.musicbattle.client.ui.TextMeasurer;
import ru.gora.musicbattle.client.ui.TextStyle;

/**
 * The result of laying a Workshop view out: named boxes with the text that goes in them, in the order they are drawn.
 * The screens read boxes by name and draw into them; the {@code workshop-screens} check walks the same boxes and
 * proves nothing overlaps, nothing leaves its parent and no text is wider than its box, at five window sizes in two
 * languages.
 *
 * <p>Rules the check relies on:
 * <ul>
 *   <li>every box names its {@code parent} (the empty name means the view itself) and must lie inside it;</li>
 *   <li>boxes with the same parent must not overlap unless one of them is marked {@code floating} (a dropdown, a
 *       tooltip) or is a {@link Kind#PANEL} (backgrounds hold the others);</li>
 *   <li>{@link Kind#TEXT} boxes hold an already fitted string: its measured width never exceeds the box.</li>
 * </ul>
 */
public final class Layout {
    public enum Kind {
        /** A background surface other boxes sit on. */
        PANEL,
        TEXT,
        BUTTON,
        /** A text field the player types in. */
        FIELD,
        ICON,
        /** Status pills, badges, difficulty dots. */
        PILL,
        /** Covers, avatars, heads. */
        IMAGE,
        /** Progress and rating bars. */
        BAR,
        /** A reserved area a screen fills itself (card grid, comment list, scroll content). */
        SLOT
    }

    /** One laid-out box. {@code text} is null unless {@code kind} is {@link Kind#TEXT}. */
    public record Box(String id, String parent, Kind kind, Rect rect, String text, TextStyle style, boolean floating) {
    }

    private final List<Box> boxes = new ArrayList<>();
    private final Map<String, Box> byId = new LinkedHashMap<>();
    private final TextMeasurer measurer;
    private final Tr tr;
    private float contentHeight;

    public Layout(TextMeasurer measurer, Tr tr) {
        this.measurer = measurer;
        this.tr = tr;
    }

    public TextMeasurer measurer() {
        return measurer;
    }

    public Tr tr() {
        return tr;
    }

    public List<Box> boxes() {
        return boxes;
    }

    /** Total height of the scrollable content of this view (0 when it does not scroll). */
    public float contentHeight() {
        return contentHeight;
    }

    public void setContentHeight(float value) {
        contentHeight = Math.max(0F, value);
    }

    public Box add(String id, String parent, Kind kind, Rect rect) {
        return put(new Box(id, parent, kind, rect, null, null, false));
    }

    public Box addFloating(String id, String parent, Kind kind, Rect rect) {
        return put(new Box(id, parent, kind, rect, null, null, true));
    }

    /** A text box holding {@code text} cut to {@code rect.w}; returns the box with the fitted string. */
    public Box text(String id, String parent, Rect rect, String text, TextStyle style) {
        String fitted = TextFit.ellipsize(measurer, text, style, rect.w());
        return put(new Box(id, parent, Kind.TEXT, rect, fitted, style, false));
    }

    /** A text box that is allowed to overlap its siblings (labels drawn over a cover or a bar). */
    public Box floatingText(String id, String parent, Rect rect, String text, TextStyle style) {
        String fitted = TextFit.ellipsize(measurer, text, style, rect.w());
        return put(new Box(id, parent, Kind.TEXT, rect, fitted, style, true));
    }

    /** A text box sized to its content, cut to {@code maxWidth}; the box starts at (x, y) on the cap line. */
    public Box label(String id, String parent, float x, float y, float maxWidth, String text, TextStyle style) {
        String fitted = TextFit.ellipsize(measurer, text, style, maxWidth);
        float width = TextFit.width(measurer, fitted, style);
        return put(new Box(id, parent, Kind.TEXT, new Rect(x, y, width, TextFit.boxHeight(style)), fitted, style, false));
    }

    public Box labelRight(String id, String parent, float right, float y, float maxWidth, String text, TextStyle style) {
        String fitted = TextFit.ellipsize(measurer, text, style, maxWidth);
        float width = TextFit.width(measurer, fitted, style);
        return put(new Box(id, parent, Kind.TEXT, new Rect(right - width, y, width, TextFit.boxHeight(style)), fitted, style, false));
    }

    public Box labelCentered(String id, String parent, float centerX, float y, float maxWidth, String text, TextStyle style) {
        String fitted = TextFit.ellipsize(measurer, text, style, maxWidth);
        float width = TextFit.width(measurer, fitted, style);
        return put(new Box(id, parent, Kind.TEXT, new Rect(centerX - width * 0.5F, y, width, TextFit.boxHeight(style)), fitted, style, false));
    }

    /** Wrapped text; returns the block height. Lines are named {@code id + ".0"}, {@code id + ".1"}, … */
    public float paragraph(String id, String parent, float x, float y, float maxWidth, String text, TextStyle style, float lineHeight,
                           int maxLines) {
        List<String> lines = TextFit.wrap(measurer, text, style, maxWidth, maxLines);
        for (int i = 0; i < lines.size(); i++) {
            float lineY = y + i * lineHeight;
            String line = lines.get(i);
            float width = TextFit.width(measurer, line, style);
            put(new Box(id + "." + i, parent, Kind.TEXT, new Rect(x, lineY, Math.min(width, maxWidth), TextFit.boxHeight(style)),
                    line, style, false));
        }
        return lines.size() * lineHeight;
    }

    /** Replaces the rectangle of a box already laid out (a panel whose height is only known after its contents). */
    public Box resize(String id, Rect rect) {
        Box old = byId.get(id);
        if (old == null) {
            return null;
        }
        Box updated = new Box(old.id(), old.parent(), old.kind(), rect, old.text(), old.style(), old.floating());
        boxes.set(boxes.indexOf(old), updated);
        byId.put(id, updated);
        return updated;
    }

    public Box put(Box box) {
        boxes.add(box);
        byId.put(box.id(), box);
        return box;
    }

    public boolean has(String id) {
        return byId.containsKey(id);
    }

    public Box box(String id) {
        return byId.get(id);
    }

    /** The box's rectangle, or {@link Rect#EMPTY} when the view did not lay it out (hidden states). */
    public Rect rect(String id) {
        Box box = byId.get(id);
        return box == null ? Rect.EMPTY : box.rect();
    }

    public String text(String id) {
        Box box = byId.get(id);
        return box == null || box.text() == null ? "" : box.text();
    }

    /** Boxes whose id starts with {@code prefix}, in layout order (list rows, filter options). */
    public List<Box> group(String prefix) {
        List<Box> out = new ArrayList<>();
        for (Box box : boxes) {
            if (box.id().startsWith(prefix)) {
                out.add(box);
            }
        }
        return out;
    }
}

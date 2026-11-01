package ru.gora.musicbattle.client.ui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import ru.gora.musicbattle.client.workshop.WorkshopDraw;
import ru.gora.musicbattle.client.workshop.layout.Rect;
import ru.gora.musicbattle.client.workshop.layout.TextFit;

/**
 * How a slider looks, in one place.
 *
 * <p>Seven screens carry their own {@code AbstractSliderButton} subclass, and each drew its own square track and
 * handle in its own shade of teal - the toolkit never had a slider, so everybody wrote one. The behaviour of those
 * seven differs for good reasons (what they clamp, what they format, what they save), so this is the drawing only:
 * a track, the filled part, a knob and the label, in the shape and colours the Workshop uses. A screen keeps its
 * own subclass and calls {@link #paint} from {@code renderWidget}.
 *
 * <p>A labelled slider reads the way the Workshop's form slider and the speaker dialog's rows do: the name on the left
 * and the value on the right, in a line of captions along the top of the field, and the track with its round knob
 * under that line. The label used to be centred on the track with a white handle sliding across it, and wherever the
 * handle stopped it hid the glyphs under it: at the default 73% on a 320x240 window "Bar opacity: 73%" lost its "%",
 * the Russian bar opacity a letter, and on the DJ panel the labels sat under their handles. Now the knob never enters
 * the line of text, at any value and in every field height the mod gives a slider from 14 units up (see
 * {@link #layout}), and the value is never the part that is cut when a name is long. A slider without a label keeps
 * its track in the middle of the field. The slider-labels check measures the knob against the glyph boxes of every
 * slider label in both languages, at every value, with the game's own font metrics, and runs paint itself with its
 * drawing recorded, so that what is drawn is what it measured.
 *
 * <p>It is deliberately not a widget of its own. Making it one would mean changing what every screen puts in its
 * {@code renderables} and what the regression harnesses reflect on, for no gain today.
 */
public final class Slider {
    /** Room inside the field's left and right edges before the label's name and value. */
    public static final float PAD_X = 6F;
    /**
     * Where the knob's centre is at the two ends of its travel, in from the field's edges. Every slider of the mod is a
     * vanilla AbstractSliderButton, which reads a click or a drag at x as the value (x - (left + 4)) / (width - 8): the
     * centre of its own 8-unit handle. With the knob's centre exactly there, the knob stays under the pointer while it is
     * dragged, and a click on its middle leaves the value as it is. The knob used to travel 6 units in from the edges, up
     * to 2 units off the pointer at the ends, so that a click on the middle of a knob near the start of the DJ panel's
     * timeline moved the song on by about a second.
     */
    public static final float POINTER_INSET = 4F;
    /** Between the knob at either end of its travel and the field's edge: the width of the hover outline. */
    public static final float KNOB_EDGE = 1F;
    /** The largest knob, the one that keeps {@link #KNOB_EDGE} from the field's edges at both ends: 6 units. */
    public static final float KNOB_MAX = (POINTER_INSET - KNOB_EDGE) * 2F;
    /** The smallest knob, which only a field shorter than 14 units gets. */
    public static final float KNOB_MIN = 3F;
    /**
     * How far the label's glyphs reach below its cap line: the descenders of the caption face, 7.5 units at GUI scale
     * 2 where FreeType hints them lowest (7.0 to 7.33 at the other scales).
     */
    public static final float INK_BELOW = 7.5F;
    /** Between the lowest descender and the top of the knob. */
    public static final float INK_GAP = 0.6F;
    /** Between the knob and the field's lower edge. */
    public static final float KNOB_BOTTOM = 0.8F;
    /** The least room between the name and the value on the label's line. */
    public static final float NAME_GAP = 6F;

    private Slider() {
    }

    /**
     * Where the parts of one slider sit. The label's cap line is {@code labelY} (NaN without a label); the track and the
     * knob are centred on {@code knobY}, and the knob's centre travels from {@code trackLeft} at 0 to
     * {@code trackRight} at 1.
     */
    public record Layout(Rect field, boolean labelled, float labelY, float trackLeft, float trackRight, float knobY,
                         float knobRadius, float trackThickness) {
        /** The knob's centre for a value from 0 to 1. */
        public float knobX(double value) {
            return trackLeft + (float) clamp(value) * (trackRight - trackLeft);
        }

        /** The knob's box at a value. */
        public Rect knob(double value) {
            float centre = knobX(value);
            return new Rect(centre - knobRadius, knobY - knobRadius, knobRadius * 2F, knobRadius * 2F);
        }

        public Rect track() {
            return new Rect(trackLeft, knobY - trackThickness * 0.5F, Math.max(0F, trackRight - trackLeft), trackThickness);
        }

        public float labelLeft() {
            return field.x() + PAD_X;
        }

        public float labelRight() {
            return field.right() - PAD_X;
        }
    }

    /**
     * A label's two halves as they are drawn: the name from the left edge and the value against the right one, both in
     * captions on the cap line {@code y}. Either may have been cut with an ellipsis to the room it has; the value only
     * when it is wider than the whole line on its own.
     */
    public record Label(String name, float nameX, String value, float valueX, float y) {
    }

    /**
     * The slider's geometry in a field. With a label, the cap line sits one unit from the top in a field under 18 units
     * and two units down from 18 (whole units, so the text never moves when it is snapped to the pixel grid); the knob
     * fills what is left under the descenders, up to {@link #KNOB_MAX}, and the track runs through its middle. That
     * gives a 6-unit knob in fields from 16 units up and 4.1 in the 14-unit rows the DJ panel uses on a 240-unit window.
     * Without a label the knob and the track are centred in the field. Either way the knob's centre travels from
     * {@link #POINTER_INSET} in from the left edge to as far in from the right, where vanilla reads the pointer.
     */
    public static Layout layout(Rect field, boolean labelled) {
        float knob;
        float knobY;
        float labelY = Float.NaN;
        if (labelled) {
            labelY = field.y() + (field.h() >= 18F ? 2F : 1F);
            float top = labelY + INK_BELOW + INK_GAP;
            float bottom = field.bottom() - KNOB_BOTTOM;
            knob = Math.max(KNOB_MIN, Math.min(KNOB_MAX, bottom - top));
            // Centred in the room under the text; a field too short for the smallest knob keeps it on the bottom edge.
            knobY = bottom - top >= knob ? (top + bottom) * 0.5F : bottom - knob * 0.5F;
        } else {
            knob = Math.max(KNOB_MIN, Math.min(KNOB_MAX, field.h() - 6F));
            knobY = field.centerY();
        }
        float radius = knob * 0.5F;
        float thickness = Math.max(1.5F, Math.min(3F, knob * 0.375F));
        float left = field.x() + POINTER_INSET;
        float right = Math.max(left, field.right() - POINTER_INSET);
        return new Layout(field, labelled, labelY, left, right, knobY, radius, thickness);
    }

    /**
     * Splits a caller's label into the name and the value at its last {@code ": "} ("Bar opacity: 73%" is the name "Bar
     * opacity" and the value "73%"), the way every slider label of the mod is written in both languages. A label
     * without one is all name. The value keeps its full width while the line has room for it; the name is cut first.
     */
    public static Label label(TextMeasurer measurer, String text, Layout layout) {
        String name = text == null ? "" : text;
        String value = "";
        int split = name.lastIndexOf(": ");
        if (split > 0) {
            value = name.substring(split + 2).strip();
            name = name.substring(0, split).strip();
        }
        float left = layout.labelLeft();
        float right = layout.labelRight();
        float room = Math.max(0F, right - left);
        String shownValue = TextFit.ellipsize(measurer, value, TextStyle.CAPTION, room);
        float valueWidth = TextFit.width(measurer, shownValue, TextStyle.CAPTION);
        float nameRoom = shownValue.isEmpty() ? room : room - valueWidth - NAME_GAP;
        String shownName = TextFit.ellipsize(measurer, name, TextStyle.CAPTION, nameRoom);
        return new Label(shownName, left, shownValue, right - valueWidth, layout.labelY());
    }

    /**
     * Draws one slider.
     *
     * @param value 0 to 1, the fraction the knob sits at
     * @param label already formatted by the caller, because each screen words its own value differently; empty for a
     *              slider whose screen shows the value elsewhere
     */
    public static void paint(GuiGraphics graphics, Rect rect, double value, Component label,
                             boolean hovered, boolean active) {
        if (rect.empty()) {
            return;
        }
        boolean lit = hovered && active;
        if (lit) {
            HandCursor.request(HandCursor.Kind.HAND);
        }
        WorkshopDraw.panel(graphics, rect, Palette.RADIUS_SM, active ? Palette.BG_3 : Palette.fade(Palette.BG_3, 0.5F));
        if (lit) {
            WorkshopDraw.outline(graphics, rect, Palette.RADIUS_SM, 1.0F, Palette.fade(Palette.ACCENT, 0.45F));
        }

        String text = label == null ? "" : label.getString();
        Layout layout = layout(rect, !text.isEmpty());
        Rect track = layout.track();
        float round = track.h() * 0.5F;
        WorkshopDraw.panel(graphics, track, round, active ? Palette.BG_0 : Palette.fade(Palette.BG_0, 0.6F));
        float knobX = layout.knobX(value);
        float filled = knobX - track.x();
        if (filled > 0.5F) {
            WorkshopDraw.panel(graphics, Rect.of(track.x(), track.y(), Math.max(track.h(), filled), track.h()), round,
                    active ? Palette.ACCENT_STRONG : Palette.fade(Palette.ACCENT_STRONG, 0.5F));
        }
        float radius = layout.knobRadius();
        RoundedRect.circle(graphics, knobX, layout.knobY(), radius, active ? Palette.TEXT_1 : Palette.TEXT_MUTE);
        if (lit) {
            // A ring inside the knob's own edge: the knob answers the pointer without growing towards the text.
            RoundedRect.stroke(graphics, knobX - radius, layout.knobY() - radius, radius * 2F, radius * 2F, radius,
                    Math.min(1.2F, radius * 0.4F), Palette.ACCENT);
        }

        if (layout.labelled()) {
            Label shown = label(TextStyles.measurer(), text, layout);
            TextStyles.queue(graphics, TextStyles.text(shown.name(), TextStyle.CAPTION).getVisualOrderText(),
                    shown.nameX(), shown.y(), !active ? Palette.TEXT_MUTE : lit ? Palette.TEXT_1 : Palette.TEXT_2);
            TextStyles.queue(graphics, TextStyles.text(shown.value(), TextStyle.CAPTION).getVisualOrderText(),
                    shown.valueX(), shown.y(), active ? Palette.TEXT_1 : Palette.TEXT_MUTE);
        }
    }

    private static double clamp(double value) {
        return Double.isNaN(value) ? 0.0D : Math.max(0.0D, Math.min(1.0D, value));
    }
}

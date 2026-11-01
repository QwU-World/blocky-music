package ru.gora.musicbattle.client.workshop.layout;

import java.util.ArrayList;
import java.util.List;
import ru.gora.musicbattle.client.ui.TextMeasurer;
import ru.gora.musicbattle.client.ui.TextStyle;

/**
 * Where a Workshop dialog's words and buttons go (WorkshopDialogScreen: consent, the rules' acceptance, install, report,
 * the beatmap's more menu, confirmations and messages). Pure, so the {@code workshop-text} check lays out every dialog's
 * words with the game's own metrics at every window size and GUI scale.
 *
 * <p>Every line of the interface's words is whole: the heading, each choice, the field's label and the status line wrap
 * at spaces instead of ending in an ellipsis. The rules' acceptance used to cut both of its boxes on every window -
 * "I have read what is above and accept the Wo…" - so the player ticked words they could not read; the consent's
 * Russian heading lost "к сети" below 362 units, and the report's reasons ran off their line. Buttons keep their
 * labels: links that do not fit beside each other are stacked, as the actions already were.
 */
public final class DialogLayout {
    public static final float MAX_PANEL_WIDTH = 380F;
    public static final float PAD = 12F;
    public static final float GAP = 6F;
    /** The one-line choice row; a choice that wraps grows by a body line per line. */
    public static final float CHOICE_HEIGHT = 12F;
    /** Between one choice's row and the next. */
    public static final float CHOICE_GAP = 2F;
    public static final float CHOICE_BOX = 8F;
    public static final float CHOICE_TEXT_GAP = 5F;
    /**
     * How far above the body's top its text area begins. A line's ink rises over its cap line - Nunito's «Й» by 3 units
     * at GUI scale 1 and «Ё» by 2, «б», «К» and brackets by half a unit at scale 2, every capital by a sixth at scale 6 -
     * and snapping the line to the scale's pixels lifts it by up to half a pixel more. The first line's cap line is the
     * body's top, which the clip used to start at, so those tops could be shaved off. The body's padding under the
     * heading's rule leaves room for this.
     */
    public static final float INK_ABOVE_CAP = 4F;
    public static final float FIELD_HEIGHT = 34F;
    /** Room before the heading for its icon, and the heading's cap line under the panel's top edge. */
    public static final float HEADING_ICON = 17F;
    public static final float HEADING_TOP = PAD + 2F;
    /** The heading's rule is this far under the panel's top on a one-line heading, as it always was. */
    public static final float HEADING_HEIGHT = 30F;
    public static final float BUTTON_HEIGHT = 20F;
    /** WorkshopButton's own measures: padding either side of its label, its icon and the gap after it, its slack. */
    public static final float BUTTON_PAD_X = 10F;
    public static final float BUTTON_ICON = 9F + 4F;
    public static final float BUTTON_SLACK = 4F;
    public static final float ACTION_MIN = 64F;
    /** At most this many lines of a heading or a status; past that the player's own words in them are cut. */
    public static final int HEADING_LINES = 3;
    public static final int STATUS_LINES = 3;

    /** A button's words and whether it carries an icon. */
    public record Button(String label, boolean icon) {
    }

    /** What a dialog says and offers. Strings are already translated; {@code fieldLabel} and {@code status} may be empty. */
    public record State(String title, boolean headingIcon, List<String> choices, boolean field, String fieldLabel,
                        String status, List<Button> links, List<Button> actions) {
    }

    /**
     * The laid-out dialog, relative to the panel's top left: the heading's lines, each choice's lines, the field label's
     * lines and the status lines in their faces, and each link's and action's box. Heights are what the screen needs to
     * size the panel; the body (paragraphs, choices, field) is laid out by the screen, which scrolls it.
     */
    public record Result(float panelWidth, float textWidth, Layout layout, List<String> heading, float headingHeight,
                         List<List<String>> choices, List<Float> choiceHeights, List<String> fieldLabel, List<String> status,
                         float statusHeight, List<Rect> links, List<Rect> actions, float buttonsHeight) {
    }

    private DialogLayout() {
    }

    public static float panelWidth(float screenWidth) {
        return Math.min(MAX_PANEL_WIDTH, screenWidth - 24F);
    }

    /** The room a dialog keeps from the window's top and bottom edges. */
    public static final float WINDOW_MARGIN = 12F;
    /** The body keeps at least this much height beside pinned buttons, as it always did. */
    public static final float MIN_BODY = 24F;

    /**
     * The dialog's frame in a window: the body's height, the panel's, and whether the buttons are pinned under the body
     * or scroll with it, {@code buttonsTop} into the scrolled content, which is then {@code contentHeight} tall.
     */
    public record Frame(boolean buttonsScroll, float bodyHeight, float panelHeight, float buttonsTop, float contentHeight) {
    }

    /**
     * Pinned buttons under the body while they leave it {@link #MIN_BODY} (or all of a shorter body) inside the window;
     * otherwise the buttons scroll with the body. The beatmap's more menu stacks up to nine actions, and on a window
     * 240 units tall they ran off its bottom edge, the last ones out of reach.
     */
    public static Frame frame(float screenHeight, float bodyContent, Result fitted) {
        float fixed = fitted.headingHeight() + fitted.statusHeight() + PAD * 3;
        float room = screenHeight - WINDOW_MARGIN * 2;
        float buttons = fitted.buttonsHeight();
        if (buttons <= 0F || fixed + buttons + Math.min(bodyContent, MIN_BODY) <= room) {
            float body = Math.min(bodyContent, Math.max(MIN_BODY, room - fixed - buttons));
            return new Frame(false, body, fixed + body + buttons, 0F, bodyContent);
        }
        float buttonsTop = bodyContent > 0F ? bodyContent + GAP : 0F;
        float content = buttonsTop + buttons;
        float body = Math.min(content, Math.max(MIN_BODY, room - fixed));
        return new Frame(true, body, fixed + body, buttonsTop, content);
    }

    /**
     * Where the body puts its parts before it scrolls, from the body's top: each choice's row, the field's label, and
     * the height of it all, which ends at the foot of the last part - a paragraph's last line box, the last choice's row
     * or the descent of its last line, whichever is lower, or the field.
     *
     * <p>The body used to end a paragraph gap (6 units) above the end of its content, but the choices are only
     * {@link #CHOICE_GAP} apart, so it ended 4 units into the last choice: the rules' acceptance lost the bottom of
     * "publish here." / «…что я тут публикую» on every window, and since the scroll range was 4 units short as well,
     * not even scrolling showed it.
     */
    public record Body(List<Float> choiceTops, float fieldTop, float contentHeight) {
    }

    /**
     * The body's layout: {@code paragraphLines} is how many lines the game's splitter made of each paragraph, which come
     * first, each a {@link #GAP} after the one before; then the choices of {@code fitted}, then the field if there is one.
     */
    public static Body body(List<Integer> paragraphLines, Result fitted, boolean field) {
        float y = 0F;
        float foot = 0F;
        for (int lines : paragraphLines) {
            foot = y + lines * TextStyle.BODY.lineHeight();
            y = foot + GAP;
        }
        List<Float> choiceTops = new ArrayList<>();
        for (int i = 0; i < fitted.choiceHeights().size(); i++) {
            choiceTops.add(y);
            float height = fitted.choiceHeights().get(i);
            int lastLine = Math.max(0, fitted.choices().get(i).size() - 1);
            foot = Math.max(y + height, lineFoot(choiceLineY(y, lastLine), TextStyle.BODY));
            y += height + CHOICE_GAP;
        }
        float fieldTop = 0F;
        if (field) {
            fieldTop = y + GAP;
            foot = fieldTop + fitted.fieldLabel().size() * TextStyle.CAPTION.lineHeight() + FIELD_HEIGHT;
        }
        return new Body(choiceTops, fieldTop, foot);
    }

    /** The cap line of line {@code line} of a choice whose row starts at {@code rowY}; the first sits beside its box. */
    public static float choiceLineY(float rowY, int line) {
        return rowY + (CHOICE_HEIGHT - TextStyle.BODY.capHeight()) * 0.5F + line * TextStyle.BODY.lineHeight();
    }

    /** The lowest a line drawn with its cap line at {@code capY} reaches: the face's descent under its baseline. */
    public static float lineFoot(float capY, TextStyle style) {
        return capY + style.capHeight() + TextStyle.DESCENT_EM * style.size();
    }

    /** The width a button asks for, as WorkshopButton.preferredWidth measures it. */
    public static float preferredWidth(TextMeasurer measurer, Button button) {
        return (float) Math.ceil(TextFit.width(measurer, button.label(), TextStyle.BODY) + BUTTON_PAD_X * 2
                + (button.icon() ? BUTTON_ICON : 0F) + BUTTON_SLACK);
    }

    public static Result compute(float screenWidth, TextMeasurer measurer, Tr tr, State state) {
        Layout layout = new Layout(measurer, tr);
        float panelWidth = panelWidth(screenWidth);
        float textWidth = panelWidth - PAD * 2;
        float headingX = PAD + (state.headingIcon() ? HEADING_ICON : 0F);
        List<String> heading = lines(layout, "dialog.heading", state.title(), TextStyle.TITLE, panelWidth - PAD - headingX,
                HEADING_LINES);
        // A heading of more lines pushes its rule down by a line each.
        float headingHeight = HEADING_HEIGHT + Math.max(0, heading.size() - 1) * TextStyle.TITLE.lineHeight();

        List<List<String>> choices = new ArrayList<>();
        List<Float> choiceHeights = new ArrayList<>();
        for (int i = 0; i < state.choices().size(); i++) {
            List<String> choice = lines(layout, "dialog.choice." + i, state.choices().get(i), TextStyle.BODY,
                    textWidth - CHOICE_BOX - CHOICE_TEXT_GAP, 0);
            choices.add(choice);
            choiceHeights.add(CHOICE_HEIGHT + Math.max(0, choice.size() - 1) * TextStyle.BODY.lineHeight());
        }
        List<String> fieldLabel = state.field()
                ? lines(layout, "dialog.field.label", state.fieldLabel(), TextStyle.CAPTION, textWidth, 0) : List.of();
        List<String> status = lines(layout, "dialog.status", state.status(), TextStyle.CAPTION, textWidth, STATUS_LINES);
        float statusHeight = status.isEmpty() ? 0F : status.size() * TextStyle.CAPTION.lineHeight() + 4F;

        List<Rect> links = new ArrayList<>();
        List<Rect> actions = new ArrayList<>();
        float buttonsHeight = buttons(measurer, state, textWidth, links, actions);
        for (int i = 0; i < links.size(); i++) {
            button(layout, "dialog.link." + i, state.links().get(i), links.get(i));
        }
        for (int i = 0; i < actions.size(); i++) {
            button(layout, "dialog.action." + i, state.actions().get(i), actions.get(i));
        }
        return new Result(panelWidth, textWidth, layout, heading, headingHeight, choices, choiceHeights, fieldLabel, status,
                statusHeight, links, actions, buttonsHeight);
    }

    /**
     * Links on the left and actions on the right of one row while everything fits it; otherwise the links on a row of
     * their own above the actions - side by side while they fit, stacked full width when not - and the actions in a row
     * right-aligned, or stacked full width. Rects are relative to the buttons' top left; returns their height.
     */
    private static float buttons(TextMeasurer measurer, State state, float inner, List<Rect> links, List<Rect> actions) {
        float actionsWidth = 0F;
        for (Button button : state.actions()) {
            actionsWidth += Math.max(ACTION_MIN, preferredWidth(measurer, button)) + GAP;
        }
        float linksWidth = 0F;
        for (Button button : state.links()) {
            linksWidth += preferredWidth(measurer, button) + GAP;
        }
        if (state.links().isEmpty() && state.actions().isEmpty()) {
            return 0F;
        }
        if (actionsWidth + linksWidth <= inner + GAP + TextFit.FIT_EPSILON) {
            row(measurer, state.links(), links, 0F, 0F, false);
            row(measurer, state.actions(), actions, inner, 0F, true);
            return BUTTON_HEIGHT;
        }
        float y = 0F;
        if (!state.links().isEmpty()) {
            if (linksWidth <= inner + GAP + TextFit.FIT_EPSILON) {
                row(measurer, state.links(), links, 0F, y, false);
                y += BUTTON_HEIGHT + GAP;
            } else {
                // Side by side the links cut their labels ("Читать правила со…" at 320 units): one to a row instead.
                for (Button ignored : state.links()) {
                    links.add(new Rect(0F, y, inner, BUTTON_HEIGHT));
                    y += BUTTON_HEIGHT + GAP;
                }
            }
        }
        if (!state.actions().isEmpty()) {
            if (actionsWidth <= inner + GAP + TextFit.FIT_EPSILON) {
                row(measurer, state.actions(), actions, inner, y, true);
                y += BUTTON_HEIGHT + GAP;
            } else {
                for (Button ignored : state.actions()) {
                    actions.add(new Rect(0F, y, inner, BUTTON_HEIGHT));
                    y += BUTTON_HEIGHT + GAP;
                }
            }
        }
        return y - GAP;
    }

    /**
     * One row of buttons at their preferred widths: links from {@code edge} rightwards, actions (at least
     * {@link #ACTION_MIN} wide) leftwards from {@code edge}, in their order.
     */
    private static void row(TextMeasurer measurer, List<Button> buttons, List<Rect> out, float edge, float y, boolean actions) {
        if (!actions) {
            float x = edge;
            for (Button button : buttons) {
                float w = preferredWidth(measurer, button);
                out.add(new Rect(x, y, w, BUTTON_HEIGHT));
                x += w + GAP;
            }
            return;
        }
        List<Rect> row = new ArrayList<>();
        float right = edge;
        for (int i = buttons.size() - 1; i >= 0; i--) {
            float w = Math.max(ACTION_MIN, preferredWidth(measurer, buttons.get(i)));
            row.add(0, new Rect(right - w, y, w, BUTTON_HEIGHT));
            right -= w + GAP;
        }
        out.addAll(row);
    }

    /** A button's label box, so the check reads a cut label like any other. */
    private static void button(Layout layout, String id, Button button, Rect rect) {
        float room = rect.w() - BUTTON_PAD_X * 2 - (button.icon() ? BUTTON_ICON : 0F);
        layout.label(id, "", rect.x() + BUTTON_PAD_X, 0F, Math.max(1F, room), button.label(), TextStyle.BODY);
    }

    /** {@code text} wrapped to {@code width} at spaces, {@code maxLines} at most (0 for no cap), noting any cut. */
    private static List<String> lines(Layout layout, String id, String text, TextStyle style, float width, int maxLines) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        int before = layout.boxes().size();
        layout.paragraph(id, "", 0F, 0F, width, text, style, style.lineHeight(), maxLines);
        List<String> out = new ArrayList<>();
        for (Layout.Box box : layout.boxes().subList(before, layout.boxes().size())) {
            out.add(box.text());
        }
        return out;
    }
}

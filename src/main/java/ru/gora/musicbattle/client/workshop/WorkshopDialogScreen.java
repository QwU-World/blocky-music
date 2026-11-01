package ru.gora.musicbattle.client.workshop;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.lwjgl.glfw.GLFW;
import ru.gora.musicbattle.client.MusicBattleVoteScreen;
import ru.gora.musicbattle.client.ScreenStack;
import ru.gora.musicbattle.client.ui.Motion;
import ru.gora.musicbattle.client.ui.Palette;
import ru.gora.musicbattle.client.ui.RoundedRect;
import ru.gora.musicbattle.client.ui.TextField;
import ru.gora.musicbattle.client.ui.TextStyle;
import ru.gora.musicbattle.client.ui.TextStyles;
import ru.gora.musicbattle.client.ui.UiClip;
import ru.gora.musicbattle.client.ui.UiIcon;
import ru.gora.musicbattle.client.ui.UiIcons;
import ru.gora.musicbattle.client.workshop.layout.DialogLayout;

/**
 * A centred Workshop dialog drawn with the UI toolkit: heading, wrapped paragraphs that scroll when the window is
 * short, link buttons on the left and action buttons on the right (they stack on narrow screens). Enter runs the
 * primary action, Esc the cancel action. Consent, install confirmation and error messages build on it; the other
 * Workshop screens reuse it for report and confirm dialogs.
 */
public abstract class WorkshopDialogScreen extends ru.gora.musicbattle.client.ui.UiLayer implements WorkshopUi,
        ru.gora.musicbattle.client.ParentedScreen {
    private static final float CHOICE_HEIGHT = DialogLayout.CHOICE_HEIGHT;
    private static final float FIELD_HEIGHT = DialogLayout.FIELD_HEIGHT;
    private static final float PAD = DialogLayout.PAD;
    private static final float GAP = DialogLayout.GAP;

    protected final Screen parent;
    private final UiIcon headingIcon;
    private final int headingColour;
    private final List<Component> paragraphs = new ArrayList<>();
    private final List<WorkshopButton> links = new ArrayList<>();
    private final List<WorkshopButton> actions = new ArrayList<>();
    private final Motion.SmoothScroll scroll = new Motion.SmoothScroll();
    private final List<Choice> choices = new ArrayList<>();
    private TextField field;
    private Component fieldLabel = Component.empty();
    private WorkshopButton primary;
    private Runnable cancelAction;
    private Component status = Component.empty();
    private int statusColour = Palette.TEXT_MUTE;

    private float panelX;
    private float panelY;
    private float panelWidth;
    private float panelHeight;
    private float bodyTop;
    private float bodyHeight;
    private float contentHeight;
    private List<List<FormattedCharSequence>> wrapped = List.of();
    /** Where the choices and the field sit in the body, and how tall it is; see {@link DialogLayout#body}. */
    private DialogLayout.Body body;
    /** The heading, choices, field label, status and buttons as DialogLayout places them; see {@link #layout()}. */
    private DialogLayout.Result fitted;
    /** The panel and body heights for this window, and whether the buttons scroll with the body. */
    private DialogLayout.Frame frame;

    @Override
    public Screen musicBattleParent() {
        return parent;
    }

    /**
     * A dialog opened over a battle's map picker must not pause single-player: the vote clock and the battle behind
     * it run on the server tick, and a pause there froze the countdown while the dialog was up. Anywhere else a dialog
     * pauses like every menu.
     */
    @Override
    public boolean isPauseScreen() {
        return ScreenStack.find(parent, MusicBattleVoteScreen.class) == null;
    }

    protected WorkshopDialogScreen(Screen parent, Component heading, UiIcon headingIcon, int headingColour) {
        super(heading);
        this.parent = parent;
        this.headingIcon = headingIcon;
        this.headingColour = headingColour;
    }

    /** One option of a dialog that asks the player to choose (the report reasons). */
    private record Choice(Component label, BooleanSupplier selected, Runnable action) {
    }

    /** Adds a radio option under the paragraphs. */
    protected void addChoice(Component label, BooleanSupplier selected, Runnable action) {
        choices.add(new Choice(label, selected, action));
    }

    /** Adds the single text field of the dialog (details of a report). */
    protected TextField addField(Component label, boolean multiline, int maxLength) {
        field = multiline ? TextField.multiLine(maxLength) : TextField.singleLine();
        field.setMaxLength(maxLength);
        fieldLabel = label == null ? Component.empty() : label;
        return field;
    }

    protected TextField field() {
        return field;
    }

    protected void addParagraph(Component text) {
        paragraphs.add(text);
    }

    protected void clearParagraphs() {
        paragraphs.clear();
    }

    protected WorkshopButton addLink(Component label, UiIcon icon, Runnable action) {
        WorkshopButton button = new WorkshopButton(label, WorkshopButton.Kind.QUIET, icon, action);
        links.add(button);
        return button;
    }

    protected WorkshopButton addAction(Component label, WorkshopButton.Kind kind, UiIcon icon, Runnable action) {
        WorkshopButton button = new WorkshopButton(label, kind, icon, action);
        actions.add(button);
        if (kind == WorkshopButton.Kind.PRIMARY && primary == null) {
            primary = button;
        }
        return button;
    }

    protected void setCancelAction(Runnable action) {
        cancelAction = action;
    }

    protected void setStatus(Component text, int colour) {
        status = text == null ? Component.empty() : text;
        statusColour = colour;
    }

    @Override
    protected void layout() {
        List<String> choiceLabels = new ArrayList<>();
        for (Choice choice : choices) {
            choiceLabels.add(choice.label().getString());
        }
        fitted = DialogLayout.compute(this.width, ru.gora.musicbattle.client.ui.TextStyles.measurer(), WorkshopTr.INSTANCE,
                new DialogLayout.State(this.title.getString(), headingIcon != null, choiceLabels, field != null,
                        fieldLabel.getString(), status.getString(), buttons(links), buttons(actions)));
        panelWidth = fitted.panelWidth();
        float textWidth = fitted.textWidth();
        List<List<FormattedCharSequence>> lines = new ArrayList<>();
        List<Integer> lineCounts = new ArrayList<>();
        for (Component paragraph : paragraphs) {
            List<FormattedCharSequence> split = TextStyles.wrap(TextStyles.styled(paragraph, TextStyle.BODY), textWidth);
            lines.add(split);
            lineCounts.add(split.size());
        }
        wrapped = lines;
        body = DialogLayout.body(lineCounts, fitted, field != null);
        frame = DialogLayout.frame(this.height, body.contentHeight(), fitted);
        contentHeight = frame.contentHeight();
        bodyHeight = frame.bodyHeight();
        panelHeight = frame.panelHeight();
        panelX = (this.width - panelWidth) * 0.5F;
        panelY = Math.max(DialogLayout.WINDOW_MARGIN, (this.height - panelHeight) * 0.5F);
        bodyTop = panelY + fitted.headingHeight() + PAD;
        scroll.setExtent(Math.max(0.0F, contentHeight - bodyHeight));
        placeButtons(scroll.snapped(guiScale()));
    }

    /**
     * Puts the buttons where DialogLayout placed them: pinned at the panel's foot, or in the body at the current scroll
     * when the window is too short for them to be pinned.
     */
    private void placeButtons(float offset) {
        float top = frame.buttonsScroll() ? bodyTop - offset + frame.buttonsTop() : panelY + panelHeight - PAD - fitted.buttonsHeight();
        place(links, fitted.links(), top);
        place(actions, fitted.actions(), top);
    }

    /** Whether a point is inside the body's visible part, where buttons that scroll with it can be seen and pressed. */
    private boolean insideBody(double mouseX, double mouseY) {
        return mouseX >= panelX + PAD && mouseX < panelX + panelWidth - PAD && mouseY >= bodyTop && mouseY < bodyTop + bodyHeight;
    }

    /** The words and icons of buttons, as DialogLayout measures them. */
    private static List<DialogLayout.Button> buttons(List<WorkshopButton> buttons) {
        List<DialogLayout.Button> out = new ArrayList<>();
        for (WorkshopButton button : buttons) {
            out.add(new DialogLayout.Button(button.label().getString(), button.hasIcon()));
        }
        return out;
    }

    /** Puts buttons where DialogLayout placed them, under the body. */
    private void place(List<WorkshopButton> buttons, List<ru.gora.musicbattle.client.workshop.layout.Rect> rects, float top) {
        for (int i = 0; i < buttons.size() && i < rects.size(); i++) {
            ru.gora.musicbattle.client.workshop.layout.Rect rect = rects.get(i);
            buttons.get(i).setBounds(panelX + PAD + rect.x(), top + rect.y(), rect.w(), rect.h());
        }
    }

    /** The y of a choice's row in the body, before scrolling. */
    private float choiceTop(int index) {
        return body.choiceTops().get(index);
    }

    /** Re-runs layout after the text or buttons changed. */
    protected void relayout() {
        if (this.width > 0) {
            layout();
        }
    }

    @Override
    protected void renderUi(GuiGraphics g, double mouseX, double mouseY, float partialTick) {
        onFrame();
        double scale = guiScale();
        scroll.update();
        float offset = scroll.snapped(scale);
        RoundedRect.shadow(g, panelX, panelY + 2.0F, panelWidth, panelHeight, Palette.RADIUS_LG, 12.0F, Palette.SHADOW_LIFTED);
        RoundedRect.fill(g, panelX, panelY, panelWidth, panelHeight, Palette.RADIUS_LG, Palette.BG_1);
        RoundedRect.stroke(g, panelX, panelY, panelWidth, panelHeight, Palette.RADIUS_LG, 1.0F, Palette.LINE);

        float headingX = panelX + PAD;
        if (headingIcon != null) {
            UiIcons.draw(g, headingIcon, headingX, panelY + PAD + 1.0F, 12.0F, headingColour);
            headingX += DialogLayout.HEADING_ICON;
        }
        for (int i = 0; i < fitted.heading().size(); i++) {
            TextStyles.queue(g, TextStyles.text(fitted.heading().get(i), TextStyle.TITLE).getVisualOrderText(), headingX,
                    panelY + DialogLayout.HEADING_TOP + i * TextStyle.TITLE.lineHeight(), Palette.TEXT_1);
        }
        RoundedRect.fill(g, panelX + PAD, panelY + fitted.headingHeight(), panelWidth - PAD * 2, 1.0F, 0, Palette.LINE);

        // The clip starts above the first line's cap line by as much as a glyph's ink can rise over it.
        UiClip.push(g, panelX + PAD, bodyTop - DialogLayout.INK_ABOVE_CAP, panelX + panelWidth - PAD, bodyTop + bodyHeight);
        float y = bodyTop - offset;
        for (List<FormattedCharSequence> lines : wrapped) {
            y += TextStyles.queueLines(g, lines, panelX + PAD, y, TextStyle.BODY.lineHeight(), Palette.TEXT_2) + GAP;
        }
        for (int i = 0; i < choices.size(); i++) {
            Choice choice = choices.get(i);
            float rowY = bodyTop - offset + choiceTop(i);
            float rowHeight = fitted.choiceHeights().get(i);
            boolean selected = choice.selected().getAsBoolean();
            boolean hovered = mouseX >= panelX + PAD && mouseX < panelX + panelWidth - PAD && mouseY >= rowY
                    && mouseY < rowY + rowHeight && mouseY >= bodyTop && mouseY < bodyTop + bodyHeight;
            if (hovered) {
                ru.gora.musicbattle.client.ui.HandCursor.request(ru.gora.musicbattle.client.ui.HandCursor.Kind.HAND);
            }
            float box = 8.0F;
            RoundedRect.fill(g, panelX + PAD, rowY + (CHOICE_HEIGHT - box) * 0.5F, box, box, box * 0.5F, Palette.BG_3);
            RoundedRect.stroke(g, panelX + PAD, rowY + (CHOICE_HEIGHT - box) * 0.5F, box, box, box * 0.5F, 1.0F,
                    selected ? Palette.ACCENT : Palette.LINE);
            if (selected) {
                RoundedRect.circle(g, panelX + PAD + box * 0.5F, rowY + CHOICE_HEIGHT * 0.5F, box * 0.27F, Palette.ACCENT);
            }
            // Every line of the choice, the box beside its first: the rules' acceptance is two long sentences.
            List<String> lines = fitted.choices().get(i);
            for (int line = 0; line < lines.size(); line++) {
                TextStyles.queue(g, TextStyles.text(lines.get(line), TextStyle.BODY).getVisualOrderText(),
                        panelX + PAD + box + DialogLayout.CHOICE_TEXT_GAP, DialogLayout.choiceLineY(rowY, line),
                        selected || hovered ? Palette.TEXT_1 : Palette.TEXT_2);
            }
        }
        if (field != null) {
            float labelY = bodyTop - offset + body.fieldTop();
            List<String> label = fitted.fieldLabel();
            for (int line = 0; line < label.size(); line++) {
                TextStyles.queue(g, TextStyles.text(label.get(line), TextStyle.CAPTION).getVisualOrderText(), panelX + PAD,
                        labelY + line * TextStyle.CAPTION.lineHeight(), Palette.TEXT_MUTE);
            }
            field.setBounds(panelX + PAD, labelY + label.size() * TextStyle.CAPTION.lineHeight(), panelWidth - PAD * 2,
                    FIELD_HEIGHT);
            field.render(g, mouseX, mouseY);
        }
        placeButtons(offset);
        if (frame.buttonsScroll()) {
            // Drawn in the body's clip; the pointer counts only where the body shows them.
            double buttonX = insideBody(mouseX, mouseY) ? mouseX : -1.0D;
            double buttonY = insideBody(mouseX, mouseY) ? mouseY : -1.0D;
            for (WorkshopButton button : links) {
                button.render(g, buttonX, buttonY);
            }
            for (WorkshopButton button : actions) {
                button.render(g, buttonX, buttonY);
            }
        }
        UiClip.pop(g);
        if (scroll.extent() > 0.5) {
            float track = bodyHeight;
            float thumb = Math.max(12.0F, track * bodyHeight / Math.max(bodyHeight, contentHeight));
            float thumbY = bodyTop + (track - thumb) * (float) (scroll.current() / scroll.extent());
            RoundedRect.pill(g, panelX + panelWidth - PAD * 0.5F - 1.5F, thumbY, 3.0F, thumb, Palette.withAlpha(Palette.TEXT_3, 0.5F));
        }

        if (!fitted.status().isEmpty()) {
            float statusY = bodyTop + bodyHeight + PAD * 0.5F;
            for (int line = 0; line < fitted.status().size(); line++) {
                TextStyles.queue(g, TextStyles.text(fitted.status().get(line), TextStyle.CAPTION).getVisualOrderText(),
                        panelX + PAD, statusY + line * TextStyle.CAPTION.lineHeight(), statusColour);
            }
        }
        if (!frame.buttonsScroll()) {
            for (WorkshopButton button : links) {
                button.render(g, mouseX, mouseY);
            }
            for (WorkshopButton button : actions) {
                button.render(g, mouseX, mouseY);
            }
        }
    }

    /** Called at the start of every frame (state polling). */
    protected void onFrame() {
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            if (field != null && field.mouseClicked(mouseX, mouseY, button)) {
                return true;
            }
            float offset = scroll.snapped(guiScale());
            for (int i = 0; i < choices.size(); i++) {
                float rowY = bodyTop - offset + choiceTop(i);
                if (mouseX >= panelX + PAD && mouseX < panelX + panelWidth - PAD && mouseY >= rowY
                        && mouseY < rowY + fitted.choiceHeights().get(i) && mouseY >= bodyTop && mouseY < bodyTop + bodyHeight) {
                    choices.get(i).action().run();
                    relayout();
                    return true;
                }
            }
            // Buttons that scroll with the body are pressed only where the body shows them.
            placeButtons(offset);
            boolean reachable = !frame.buttonsScroll() || insideBody(mouseX, mouseY);
            for (WorkshopButton b : actions) {
                if (reachable && b.click(mouseX, mouseY)) {
                    return true;
                }
            }
            for (WorkshopButton b : links) {
                if (reachable && b.click(mouseX, mouseY)) {
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scroll.extent() > 0) {
            scroll.onWheel(scrollY);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean charTyped(char character, int modifiers) {
        if (field != null && field.charTyped(character, modifiers)) {
            return true;
        }
        return super.charTyped(character, modifiers);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (field != null && field.focused() && keyCode != GLFW.GLFW_KEY_ESCAPE && field.keyPressed(keyCode, scanCode, modifiers)) {
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            if (cancelAction != null) {
                cancelAction.run();
            } else {
                onClose();
            }
            return true;
        }
        if ((keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) && primary != null) {
            primary.activate();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void onClose() {
        ru.gora.musicbattle.client.ui.HandCursor.reset();
        if (minecraft != null) {
            minecraft.setScreen(parent);
        }
    }
}

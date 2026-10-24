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

/**
 * A centred Workshop dialog drawn with the UI toolkit: heading, wrapped paragraphs that scroll when the window is
 * short, link buttons on the left and action buttons on the right (they stack on narrow screens). Enter runs the
 * primary action, Esc the cancel action. Consent, install confirmation and error messages build on it; the M2b
 * screens reuse it for report and confirm dialogs.
 */
public abstract class WorkshopDialogScreen extends ru.gora.musicbattle.client.ui.UiLayer implements WorkshopUi,
        ru.gora.musicbattle.client.ParentedScreen {
    private static final float MAX_PANEL_WIDTH = 380.0F;
    private static final float CHOICE_HEIGHT = 12.0F;
    private static final float FIELD_HEIGHT = 34.0F;
    private static final float PAD = 12.0F;
    private static final float GAP = 6.0F;

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
    private float choicesTop;
    private float fieldTop;
    private List<List<FormattedCharSequence>> wrapped = List.of();

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

    /** One option of a dialog that asks the player to choose (the report reasons, spec 04 section 6.2). */
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
        panelWidth = Math.min(MAX_PANEL_WIDTH, this.width - 24.0F);
        float textWidth = panelWidth - PAD * 2;
        List<List<FormattedCharSequence>> lines = new ArrayList<>();
        float content = 0.0F;
        for (Component paragraph : paragraphs) {
            List<FormattedCharSequence> split = TextStyles.wrap(TextStyles.styled(paragraph, TextStyle.BODY), textWidth);
            lines.add(split);
            content += split.size() * TextStyle.BODY.lineHeight() + GAP;
        }
        wrapped = lines;
        choicesTop = content;
        content += choices.size() * (CHOICE_HEIGHT + 2.0F);
        if (field != null) {
            fieldTop = content + GAP;
            content = fieldTop + TextStyle.CAPTION.lineHeight() + FIELD_HEIGHT + GAP;
        }
        contentHeight = Math.max(0.0F, content - GAP);

        float headingHeight = 30.0F;
        float statusHeight = status.getString().isEmpty() ? 0.0F : TextStyle.CAPTION.lineHeight() + 4.0F;
        float buttonsHeight = layoutButtons(textWidth, 0.0F, true);
        float maxBody = Math.max(24.0F, this.height - 24.0F - headingHeight - buttonsHeight - statusHeight - PAD * 3);
        bodyHeight = Math.min(contentHeight, maxBody);
        panelHeight = headingHeight + bodyHeight + statusHeight + buttonsHeight + PAD * 3;
        panelX = (this.width - panelWidth) * 0.5F;
        panelY = Math.max(12.0F, (this.height - panelHeight) * 0.5F);
        bodyTop = panelY + headingHeight + PAD;
        layoutButtons(textWidth, panelY + panelHeight - PAD - buttonsHeight, false);
        scroll.setExtent(Math.max(0.0F, contentHeight - bodyHeight));
    }

    /** Lays buttons out from {@code top}; returns the height they need. */
    private float layoutButtons(float innerWidth, float top, boolean measureOnly) {
        float left = panelX + PAD;
        float actionsWidth = 0.0F;
        for (WorkshopButton button : actions) {
            actionsWidth += Math.max(64.0F, button.preferredWidth()) + GAP;
        }
        float linksWidth = 0.0F;
        for (WorkshopButton button : links) {
            linksWidth += button.preferredWidth() + GAP;
        }
        boolean oneRow = actionsWidth + linksWidth <= innerWidth + GAP;
        boolean stackActions = actionsWidth > innerWidth + GAP;
        float height;
        if (oneRow) {
            height = WorkshopButton.HEIGHT;
        } else if (!stackActions) {
            height = WorkshopButton.HEIGHT * 2 + GAP;
        } else {
            height = WorkshopButton.HEIGHT * (actions.size() + (links.isEmpty() ? 0 : 1)) + GAP * (actions.size() - (links.isEmpty() ? 1 : 0));
        }
        if (measureOnly) {
            return height;
        }
        float y = top;
        float x = left;
        if (!links.isEmpty()) {
            float each = oneRow ? 0.0F : (innerWidth - GAP * (links.size() - 1)) / links.size();
            for (WorkshopButton button : links) {
                float w = oneRow ? button.preferredWidth() : each;
                button.setBounds(x, y, w, WorkshopButton.HEIGHT);
                x += w + GAP;
            }
            if (!oneRow) {
                y += WorkshopButton.HEIGHT + GAP;
            }
        }
        if (stackActions) {
            for (WorkshopButton button : actions) {
                button.setBounds(left, y, innerWidth, WorkshopButton.HEIGHT);
                y += WorkshopButton.HEIGHT + GAP;
            }
        } else {
            float right = left + innerWidth;
            for (int i = actions.size() - 1; i >= 0; i--) {
                WorkshopButton button = actions.get(i);
                float w = Math.max(64.0F, button.preferredWidth());
                button.setBounds(right - w, y, w, WorkshopButton.HEIGHT);
                right -= w + GAP;
            }
        }
        return height;
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
            headingX += 17.0F;
        }
        TextStyles.queue(g, TextStyles.ellipsize(TextStyles.styled(this.title, TextStyle.TITLE), panelX + panelWidth - PAD - headingX),
                headingX, panelY + PAD + 2.0F, Palette.TEXT_1);
        RoundedRect.fill(g, panelX + PAD, panelY + 30.0F, panelWidth - PAD * 2, 1.0F, 0, Palette.LINE);

        UiClip.push(g, panelX + PAD, bodyTop, panelX + panelWidth - PAD, bodyTop + bodyHeight);
        float y = bodyTop - offset;
        for (List<FormattedCharSequence> lines : wrapped) {
            y += TextStyles.queueLines(g, lines, panelX + PAD, y, TextStyle.BODY.lineHeight(), Palette.TEXT_2) + GAP;
        }
        float choiceY = bodyTop - offset + choicesTop;
        for (int i = 0; i < choices.size(); i++) {
            Choice choice = choices.get(i);
            float rowY = choiceY + i * (CHOICE_HEIGHT + 2.0F);
            boolean selected = choice.selected().getAsBoolean();
            boolean hovered = mouseX >= panelX + PAD && mouseX < panelX + panelWidth - PAD && mouseY >= rowY
                    && mouseY < rowY + CHOICE_HEIGHT && mouseY >= bodyTop && mouseY < bodyTop + bodyHeight;
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
            TextStyles.queue(g, TextStyles.ellipsize(TextStyles.styled(choice.label(), TextStyle.BODY),
                            panelWidth - PAD * 2 - box - 5.0F), panelX + PAD + box + 5.0F,
                    TextStyles.centeredCapY(TextStyle.BODY, rowY, CHOICE_HEIGHT), selected || hovered ? Palette.TEXT_1 : Palette.TEXT_2);
        }
        if (field != null) {
            float labelY = bodyTop - offset + fieldTop;
            TextStyles.queue(g, TextStyles.ellipsize(TextStyles.styled(fieldLabel, TextStyle.CAPTION), panelWidth - PAD * 2),
                    panelX + PAD, labelY, Palette.TEXT_MUTE);
            field.setBounds(panelX + PAD, labelY + TextStyle.CAPTION.lineHeight(), panelWidth - PAD * 2, FIELD_HEIGHT);
            field.render(g, mouseX, mouseY);
        }
        UiClip.pop(g);
        if (scroll.extent() > 0.5) {
            float track = bodyHeight;
            float thumb = Math.max(12.0F, track * bodyHeight / Math.max(bodyHeight, contentHeight));
            float thumbY = bodyTop + (track - thumb) * (float) (scroll.current() / scroll.extent());
            RoundedRect.pill(g, panelX + panelWidth - PAD * 0.5F - 1.5F, thumbY, 3.0F, thumb, Palette.withAlpha(Palette.TEXT_3, 0.5F));
        }

        if (!status.getString().isEmpty()) {
            float statusY = bodyTop + bodyHeight + PAD * 0.5F;
            TextStyles.queue(g, TextStyles.ellipsize(TextStyles.styled(status, TextStyle.CAPTION), panelWidth - PAD * 2),
                    panelX + PAD, statusY, statusColour);
        }
        for (WorkshopButton button : links) {
            button.render(g, mouseX, mouseY);
        }
        for (WorkshopButton button : actions) {
            button.render(g, mouseX, mouseY);
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
            float choiceY = bodyTop - offset + choicesTop;
            for (int i = 0; i < choices.size(); i++) {
                float rowY = choiceY + i * (CHOICE_HEIGHT + 2.0F);
                if (mouseX >= panelX + PAD && mouseX < panelX + panelWidth - PAD && mouseY >= rowY && mouseY < rowY + CHOICE_HEIGHT
                        && mouseY >= bodyTop && mouseY < bodyTop + bodyHeight) {
                    choices.get(i).action().run();
                    relayout();
                    return true;
                }
            }
            for (WorkshopButton b : actions) {
                if (b.click(mouseX, mouseY)) {
                    return true;
                }
            }
            for (WorkshopButton b : links) {
                if (b.click(mouseX, mouseY)) {
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

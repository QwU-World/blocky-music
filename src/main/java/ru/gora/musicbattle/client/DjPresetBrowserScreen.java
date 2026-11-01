package ru.gora.musicbattle.client;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import ru.gora.musicbattle.client.ui.Palette;
import ru.gora.musicbattle.client.ui.RoundedRect;
import ru.gora.musicbattle.client.ui.TextMeasurer;
import ru.gora.musicbattle.client.ui.TextStyle;
import ru.gora.musicbattle.client.ui.TextStyles;
import ru.gora.musicbattle.client.ui.UiIcon;
import ru.gora.musicbattle.client.workshop.WorkshopDraw;
import ru.gora.musicbattle.client.workshop.layout.Metrics;
import ru.gora.musicbattle.client.workshop.layout.Rect;
import ru.gora.musicbattle.client.workshop.layout.TextFit;

/**
 * Small shared browser used by the local DJ-FX, equalizer and audio preset libraries.
 *
 * <p>Drawn as one of the toolkit's dialogs, like the colour picker and the DJ screens restyled before it: the raised
 * rounded panel with its heading, the presets as rounded rows with a rail that lights under the pointer and in the accent
 * when chosen, the Workshop's scrollbar and empty panel, and every word in Nunito. It was a flat slab with a cyan rule
 * along its top and the bitmap font, which cut its buttons' words on a narrow window. Every control is where it always
 * was: the name field under the heading, the list, the line that answers an action, and the four buttons along the
 * bottom, which now take the widths their words need.
 */
final class DjPresetBrowserScreen extends Screen implements DjPanelOwnedScreen {
    private static final int ROW_HEIGHT = 22;
    /** The heading's cap line and the folder hint's, from the panel's top; the name field starts at 43. */
    private static final int TITLE_Y = 9;
    private static final int HINT_Y = 25;
    private static final int PANEL_PADDING = 14;

    private final Screen parent;
    private final Component hint;
    private final PresetActions actions;
    private final Runnable returnCallback;
    private final List<String> entries = new ArrayList<>();
    private EditBox nameBox;
    private Button saveButton;
    private Button loadButton;
    private Button deleteButton;
    private int selectedIndex = -1;
    private int scroll;
    /**
     * The answer to the last action: its key and the preset's name (or the error's detail) it names. Kept apart so the
     * name, which is the player's own and can be any length, is what gets shortened, never the sentence around it.
     */
    private String statusKey = "";
    private String statusArgument = "";
    private int statusColor = Palette.TEXT_3;
    private PendingAction pendingAction = PendingAction.NONE;
    private String pendingName = "";

    DjPresetBrowserScreen(Screen parent, Component title, Component hint,
                          PresetActions actions, Runnable returnCallback) {
        super(title);
        this.parent = parent;
        this.hint = hint;
        this.actions = actions;
        this.returnCallback = returnCallback;
    }

    @Override
    protected void init() {
        String retainedName = nameBox == null ? "" : nameBox.getValue();
        int left = panelLeft();
        int top = panelTop();
        int panelWidth = panelWidth();
        int contentWidth = panelWidth - 28;

        nameBox = addRenderableWidget(new ru.gora.musicbattle.client.ui.FancyEditBox(font, left + 14, top + 43,
                contentWidth, 20, Component.translatable("screen.music_battle.dj_presets.name")));
        nameBox.setMaxLength(ClientDjPresetStorage.MAX_DISPLAY_NAME_CODE_POINTS);
        nameBox.setHint(Component.translatable("screen.music_battle.dj_presets.name_hint"));
        nameBox.setValue(retainedName);
        nameBox.setResponder(ignored -> {
            clearPendingAction();
            refreshButtons();
        });

        int gap = 5;
        int actionY = panelTop() + panelHeight() - 30;
        Buttons buttons = Buttons.arrange(TextStyles.measurer(), contentWidth, gap, List.of(
                List.of(text("screen.music_battle.dj_presets.save"), text("screen.music_battle.dj_presets.overwrite_confirm")),
                List.of(text("screen.music_battle.dj_presets.load")),
                List.of(text("screen.music_battle.dj_presets.delete"), text("screen.music_battle.dj_presets.delete_confirm")),
                List.of(text("screen.music_battle.dj_presets.back"))));
        int[] x = buttons.offsets(left + PANEL_PADDING, gap);
        int buttonY = actionY + (20 - buttons.height()) / 2;
        saveButton = addRenderableWidget(FancyButton.create(
                Component.translatable("screen.music_battle.dj_presets.save"), ignored -> save(),
                x[0], buttonY, buttons.widths()[0], buttons.height()));
        loadButton = addRenderableWidget(FancyButton.create(
                Component.translatable("screen.music_battle.dj_presets.load"), ignored -> load(),
                x[1], buttonY, buttons.widths()[1], buttons.height()));
        deleteButton = addRenderableWidget(FancyButton.create(
                Component.translatable("screen.music_battle.dj_presets.delete"), ignored -> delete(),
                x[2], buttonY, buttons.widths()[2], buttons.height()).danger());
        // Delete is the one that removes something, so it wears the toolkit's danger colour; none is the dialog's single
        // purpose, so none is primary.
        addRenderableWidget(FancyButton.create(
                Component.translatable("screen.music_battle.dj_presets.back"), ignored -> onClose(),
                x[3], buttonY, buttons.widths()[3], buttons.height()));

        refreshEntries(retainedName);
        refreshButtons();
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, Palette.withAlpha(Palette.BG_0, 0.95F));
        int left = panelLeft();
        int top = panelTop();
        int panelWidth = panelWidth();
        int panelHeight = panelHeight();
        float content = panelWidth - PANEL_PADDING * 2.0F;
        // The toolkit's dialog (WorkshopDialogScreen): the raised panel with a hairline border and a soft shadow.
        RoundedRect.shadow(graphics, left, top + 2.0F, panelWidth, panelHeight, Palette.RADIUS_LG, 12.0F,
                Palette.SHADOW_LIFTED);
        RoundedRect.fill(graphics, left, top, panelWidth, panelHeight, Palette.RADIUS_LG, Palette.BG_1);
        RoundedRect.stroke(graphics, left, top, panelWidth, panelHeight, Palette.RADIUS_LG, 1.0F, Palette.LINE);
        TextStyles.queueCentered(graphics, TextStyles.ellipsize(TextStyles.styled(title, TextStyle.TITLE), content),
                left + panelWidth / 2.0F, top + TITLE_Y, Palette.TEXT_1);
        // Where the files live is a note, not a heading: the quiet caption face, cut only by a folder name too long for
        // the panel (both languages' sentences fit whole from a 320-unit window up).
        TextStyles.queueCentered(graphics, TextStyles.ellipsize(TextStyles.styled(hint, TextStyle.CAPTION), content),
                left + panelWidth / 2.0F, top + HINT_Y, Palette.TEXT_MUTE);
        drawEntries(graphics, mouseX, mouseY);
        if (!statusKey.isEmpty()) {
            String status = Status.fit(TextStyles.measurer(), text(statusKey), statusArgument, content);
            TextStyles.queueCentered(graphics, TextStyles.text(status, TextStyle.CAPTION).getVisualOrderText(),
                    left + panelWidth / 2.0F, panelTop() + panelHeight() - 43, statusColor);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    /**
     * The presets as the playlist's effects list draws its rows: rounded, with a rail on the left that lights under the
     * pointer, the chosen one in the accent, inside a sunken well of the page colour.
     */
    private void drawEntries(GuiGraphics graphics, int mouseX, int mouseY) {
        int left = panelLeft() + PANEL_PADDING;
        int right = panelLeft() + panelWidth() - PANEL_PADDING;
        int top = listTop();
        int bottom = listBottom();
        RoundedRect.fill(graphics, left - 2, top - 2, right - left + 4, bottom - top + 4, Palette.RADIUS_MD, Palette.BG_0);
        RoundedRect.stroke(graphics, left - 2, top - 2, right - left + 4, bottom - top + 4, Palette.RADIUS_MD, 1.0F,
                Palette.withAlpha(Palette.LINE, 0.6F));
        int visibleRows = visibleRows();
        scroll = Mth.clamp(scroll, 0, Math.max(0, entries.size() - visibleRows));
        boolean scrolls = entries.size() > visibleRows;
        // A list that scrolls keeps its rows clear of the scrollbar on the right.
        int rowRight = scrolls ? right - 6 : right;
        for (int row = 0; row < visibleRows; row++) {
            int index = scroll + row;
            if (index >= entries.size()) {
                break;
            }
            int y = top + row * ROW_HEIGHT;
            boolean hovered = mouseX >= left && mouseX < rowRight && mouseY >= y && mouseY < y + ROW_HEIGHT - 2;
            boolean selected = index == selectedIndex;
            int fill = selected ? Palette.mix(Palette.BG_3, Palette.ACCENT_DEEP, 0.85F) : hovered ? Palette.BG_3 : Palette.BG_2;
            RoundedRect.fill(graphics, left, y, rowRight - left, ROW_HEIGHT - 2, Palette.RADIUS_SM, fill);
            if (selected) {
                RoundedRect.stroke(graphics, left, y, rowRight - left, ROW_HEIGHT - 2, Palette.RADIUS_SM, 1.0F, Palette.ACCENT);
            }
            RoundedRect.pill(graphics, left + 1.5F, y + 4, 2.5F, ROW_HEIGHT - 10,
                    selected ? Palette.ACCENT : hovered ? Palette.mix(Palette.LINE, Palette.ACCENT, 0.6F) : Palette.LINE);
            if (hovered) {
                ru.gora.musicbattle.client.ui.HandCursor.request(ru.gora.musicbattle.client.ui.HandCursor.Kind.HAND);
            }
            // A preset's name is the player's own: it is cut to the row, with an ellipsis, when it is longer.
            String name = TextFit.ellipsize(TextStyles.measurer(), entries.get(index), TextStyle.BODY, rowRight - left - 18.0F);
            TextStyles.queue(graphics, TextStyles.text(name, TextStyle.BODY).getVisualOrderText(), left + 10,
                    TextStyles.centeredCapY(TextStyle.BODY, y, ROW_HEIGHT - 2), Palette.TEXT_1);
        }
        if (scrolls) {
            WorkshopDraw.scrollbar(graphics, new Rect(left, top, right - left, visibleRows * ROW_HEIGHT - 2),
                    scroll * ROW_HEIGHT, entries.size() * ROW_HEIGHT, false);
        }
        if (entries.isEmpty()) {
            // The Workshop's empty state: an icon over the sentence, centred in the well.
            List<String> lines = TextFit.wrap(TextStyles.measurer(), text("screen.music_battle.dj_presets.empty"),
                    TextStyle.BODY, right - left - 16.0F);
            float block = 18 + lines.size() * Metrics.lineHeight(TextStyle.BODY);
            float y = top + Math.max(2.0F, (bottom - top - block) / 2.0F);
            WorkshopDraw.icon(graphics, new Rect((left + right) / 2.0F - 6, y, 12, 12), UiIcon.FOLDER_OPEN,
                    Palette.withAlpha(Palette.TEXT_MUTE, 0.8F));
            y += 18;
            for (String line : lines) {
                TextStyles.queueCentered(graphics, TextStyles.text(line, TextStyle.BODY).getVisualOrderText(),
                        (left + right) / 2.0F, y, Palette.TEXT_3);
                y += Metrics.lineHeight(TextStyle.BODY);
            }
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && mouseX >= panelLeft() + 14
                && mouseX < panelLeft() + panelWidth() - 14
                && mouseY >= listTop() && mouseY < listBottom()) {
            int index = scroll + (int) ((mouseY - listTop()) / ROW_HEIGHT);
            if (index >= 0 && index < entries.size()) {
                selectedIndex = index;
                nameBox.setValue(entries.get(index));
                clearPendingAction();
                setStatus("", "", Palette.TEXT_3);
                refreshButtons();
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (mouseX >= panelLeft() + 14 && mouseX < panelLeft() + panelWidth() - 14
                && mouseY >= listTop() && mouseY < listBottom()) {
            int oldScroll = scroll;
            scroll = Mth.clamp(scroll - (int) Math.signum(scrollY),
                    0, Math.max(0, entries.size() - visibleRows()));
            return oldScroll != scroll || super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 257 || keyCode == 335) {
            save();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void onClose() {
        if (minecraft != null) {
            minecraft.setScreen(parent);
            returnCallback.run();
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public DjPanelScreen owningDjPanel() {
        if (parent instanceof DjPanelScreen panel) {
            return panel;
        }
        return parent instanceof DjPanelOwnedScreen nested ? nested.owningDjPanel() : null;
    }

    private void save() {
        String name = sanitizeEnteredName();
        if (name.isEmpty()) {
            return;
        }
        setNameBoxValue(name);
        if (containsName(name) && !isPending(PendingAction.OVERWRITE, name)) {
            pendingAction = PendingAction.OVERWRITE;
            pendingName = name;
            setStatus("screen.music_battle.dj_presets.confirm_overwrite", name, Palette.WARN);
            refreshButtons();
            return;
        }
        try {
            actions.save(name);
            clearPendingAction();
            setStatus("screen.music_battle.dj_presets.saved", name, Palette.OK);
            refreshEntries(name);
        } catch (IOException | RuntimeException exception) {
            showError(exception);
        }
    }

    private void load() {
        String name = sanitizeEnteredName();
        if (name.isEmpty() || !containsName(name)) {
            return;
        }
        setNameBoxValue(name);
        try {
            actions.load(name);
            clearPendingAction();
            setStatus("screen.music_battle.dj_presets.loaded", name, Palette.OK);
            onClose();
        } catch (IOException | RuntimeException exception) {
            showError(exception);
        }
    }

    private void delete() {
        String name = sanitizeEnteredName();
        if (name.isEmpty() || !containsName(name)) {
            return;
        }
        setNameBoxValue(name);
        if (!isPending(PendingAction.DELETE, name)) {
            pendingAction = PendingAction.DELETE;
            pendingName = name;
            setStatus("screen.music_battle.dj_presets.confirm_delete", name, Palette.WARN);
            refreshButtons();
            return;
        }
        try {
            actions.delete(name);
            clearPendingAction();
            setStatus("screen.music_battle.dj_presets.deleted", name, Palette.WARN);
            nameBox.setValue("");
            refreshEntries("");
        } catch (IOException | RuntimeException exception) {
            showError(exception);
        }
    }

    private void refreshEntries(String selectName) {
        try {
            entries.clear();
            entries.addAll(actions.list());
            entries.sort(String.CASE_INSENSITIVE_ORDER);
            selectedIndex = indexOf(selectName);
            if (selectedIndex >= 0 && nameBox != null) {
                setNameBoxValue(entries.get(selectedIndex));
            }
            ensureSelectedVisible();
        } catch (IOException | RuntimeException exception) {
            entries.clear();
            selectedIndex = -1;
            showError(exception);
        }
        refreshButtons();
    }

    private void showError(Exception exception) {
        String detail = exception.getMessage();
        if (detail == null || detail.isBlank()) {
            detail = exception.getClass().getSimpleName();
        }
        // The detail is cut where it is drawn, in the face it is drawn in, like a preset's name.
        setStatus("screen.music_battle.dj_presets.error", detail, Palette.DANGER);
    }

    private void setStatus(String key, String argument, int colour) {
        statusKey = key;
        statusArgument = argument == null ? "" : argument;
        statusColor = colour;
    }

    /** A line of the language as its raw template, as ClientTranslations gives it. */
    private static String text(String key) {
        return ClientTranslations.text(key);
    }

    private void refreshButtons() {
        String name = sanitizeEnteredName();
        boolean valid = !name.isEmpty();
        boolean exists = valid && containsName(name);
        if (saveButton != null) {
            saveButton.active = valid;
            saveButton.setMessage(Component.translatable(
                    isPending(PendingAction.OVERWRITE, name)
                            ? "screen.music_battle.dj_presets.overwrite_confirm"
                            : "screen.music_battle.dj_presets.save"));
        }
        if (loadButton != null) loadButton.active = exists;
        if (deleteButton != null) {
            deleteButton.active = exists;
            deleteButton.setMessage(Component.translatable(
                    isPending(PendingAction.DELETE, name)
                            ? "screen.music_battle.dj_presets.delete_confirm"
                            : "screen.music_battle.dj_presets.delete"));
        }
    }

    private String enteredName() {
        return nameBox == null ? "" : nameBox.getValue().strip();
    }

    private String sanitizeEnteredName() {
        try {
            String entered = enteredName();
            return entered.isEmpty() ? "" : ClientDjPresetStorage.sanitizeDisplayName(entered);
        } catch (IllegalArgumentException exception) {
            return "";
        }
    }

    private void setNameBoxValue(String value) {
        if (nameBox != null && !nameBox.getValue().equals(value)) {
            nameBox.setValue(value);
        }
    }

    private void clearPendingAction() {
        pendingAction = PendingAction.NONE;
        pendingName = "";
    }

    private boolean isPending(PendingAction action, String name) {
        return pendingAction == action && ClientDjPresetStorage.samePresetName(pendingName, name);
    }

    private boolean containsName(String name) {
        return indexOf(name) >= 0;
    }

    private int indexOf(String name) {
        if (name == null || name.isBlank()) {
            return -1;
        }
        for (int index = 0; index < entries.size(); index++) {
            if (ClientDjPresetStorage.samePresetName(entries.get(index), name)) {
                return index;
            }
        }
        return -1;
    }

    private void ensureSelectedVisible() {
        if (selectedIndex < 0) {
            scroll = Mth.clamp(scroll, 0, Math.max(0, entries.size() - visibleRows()));
            return;
        }
        if (selectedIndex < scroll) scroll = selectedIndex;
        if (selectedIndex >= scroll + visibleRows()) scroll = selectedIndex - visibleRows() + 1;
    }

    private int panelWidth() { return Math.min(520, Math.max(280, width - 24)); }
    private int panelHeight() { return Math.min(350, Math.max(210, height - 24)); }
    private int panelLeft() { return (width - panelWidth()) / 2; }
    private int panelTop() { return (height - panelHeight()) / 2; }
    private int listTop() { return panelTop() + 72; }
    private int listBottom() { return panelTop() + panelHeight() - 55; }
    private int visibleRows() { return Math.max(1, (listBottom() - listTop()) / ROW_HEIGHT); }

    /**
     * The four buttons along the bottom: equal while every word fits an equal share, as they always were, else each as
     * wide as its longest word needs (Save also reads "Overwrite?", Delete "Delete?") with the room left shared out, and
     * in captions on 16-unit buttons only where even that does not fit in the body face. Pure, for the presets check.
     */
    record Buttons(int[] widths, int height) {
        /** FancyButton's room for its label: the width less ten. */
        static final float PAD = 10.0F;

        static Buttons arrange(TextMeasurer measurer, int contentWidth, int gap, List<List<String>> labels) {
            for (int height : new int[]{20, 16}) {
                TextStyle face = height >= 18 ? TextStyle.BODY : TextStyle.CAPTION;
                int count = labels.size();
                int[] needs = new int[count];
                int total = gap * (count - 1);
                int widest = 0;
                for (int index = 0; index < count; index++) {
                    float need = 0.0F;
                    for (String label : labels.get(index)) {
                        need = Math.max(need, TextFit.width(measurer, label, face));
                    }
                    needs[index] = (int) Math.ceil(need + PAD + 0.05F);
                    total += needs[index];
                    widest = Math.max(widest, needs[index]);
                }
                int share = (contentWidth - gap * (count - 1)) / count;
                if (widest <= share) {
                    int[] widths = new int[count];
                    java.util.Arrays.fill(widths, share);
                    widths[count - 1] = contentWidth - gap * (count - 1) - share * (count - 1);
                    return new Buttons(widths, height);
                }
                if (total <= contentWidth) {
                    int extra = (contentWidth - total) / count;
                    int[] widths = new int[count];
                    for (int index = 0; index < count; index++) {
                        widths[index] = needs[index] + extra;
                    }
                    widths[count - 1] += contentWidth - total - extra * count;
                    return new Buttons(widths, height);
                }
            }
            int[] widths = new int[labels.size()];
            java.util.Arrays.fill(widths, (contentWidth - gap * (labels.size() - 1)) / labels.size());
            return new Buttons(widths, 16);
        }

        int[] offsets(int left, int gap) {
            int[] offsets = new int[widths.length];
            int x = left;
            for (int index = 0; index < widths.length; index++) {
                offsets[index] = x;
                x += widths[index] + gap;
            }
            return offsets;
        }
    }

    /** The answer line: its sentence whole, and the name inside it cut with an ellipsis when the line is too long. */
    static final class Status {
        private Status() {
        }

        /**
         * The template is the language's raw line, substituted here as Minecraft substitutes it (DjPanelScreen.Words):
         * {@code %s}, a numbered {@code %1$s} from a language pack, and {@code %%}.
         */
        static String fit(TextMeasurer measurer, String template, String argument, float width) {
            String whole = DjPanelScreen.Words.substitute(template, argument);
            if (TextFit.width(measurer, whole, TextStyle.CAPTION) <= width) {
                return whole;
            }
            // Half a unit to spare: the sentence measured whole is not exactly the sum of its parts, and a name cut to the
            // last hair of the room sent the line over it and onto the fallback that cuts the sentence's end.
            float frame = TextFit.width(measurer, DjPanelScreen.Words.substitute(template, ""), TextStyle.CAPTION);
            String name = TextFit.ellipsize(measurer, argument, TextStyle.CAPTION, width - frame - 0.5F);
            String shortened = DjPanelScreen.Words.substitute(template, name);
            return TextFit.width(measurer, shortened, TextStyle.CAPTION) <= width ? shortened
                    : TextFit.ellipsize(measurer, whole, TextStyle.CAPTION, width);
        }
    }

    interface PresetActions {
        List<String> list() throws IOException;
        void save(String name) throws IOException;
        void load(String name) throws IOException;
        void delete(String name) throws IOException;
    }

    private enum PendingAction {
        NONE,
        OVERWRITE,
        DELETE
    }
}

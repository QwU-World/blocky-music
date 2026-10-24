package ru.gora.musicbattle.client;

import java.io.IOException;
import ru.gora.musicbattle.client.ui.Palette;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/** Small shared browser used by the local DJ-FX and equalizer preset libraries. */
final class DjPresetBrowserScreen extends Screen implements DjPanelOwnedScreen {
    private static final int ROW_HEIGHT = 22;

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
    private Component status = Component.empty();
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
        int actionWidth = (contentWidth - gap * 3) / 4;
        int actionY = panelTop() + panelHeight() - 30;
        saveButton = addRenderableWidget(FancyButton.create(
                Component.translatable("screen.music_battle.dj_presets.save"), ignored -> save(),
                left + 14, actionY, actionWidth, 20));
        loadButton = addRenderableWidget(FancyButton.create(
                Component.translatable("screen.music_battle.dj_presets.load"), ignored -> load(),
                left + 14 + actionWidth + gap, actionY, actionWidth, 20));
        deleteButton = addRenderableWidget(FancyButton.create(
                Component.translatable("screen.music_battle.dj_presets.delete"), ignored -> delete(),
                left + 14 + (actionWidth + gap) * 2, actionY, actionWidth, 20));
        addRenderableWidget(FancyButton.create(
                Component.translatable("screen.music_battle.dj_presets.back"), ignored -> onClose(),
                left + 14 + (actionWidth + gap) * 3, actionY, actionWidth, 20));

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
        graphics.fill(left, top, left + panelWidth, top + panelHeight, Palette.BG_1);
        graphics.fill(left, top, left + panelWidth, top + 3, Palette.ACCENT);
        graphics.drawCenteredString(font, title, left + panelWidth / 2, top + 10, Palette.TEXT_1);
        graphics.drawCenteredString(font, hint, left + panelWidth / 2, top + 27, Palette.TEXT_2);
        drawEntries(graphics, mouseX, mouseY);
        if (!status.getString().isEmpty()) {
            graphics.drawCenteredString(font, status, left + panelWidth / 2,
                    panelTop() + panelHeight() - 43, statusColor);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void drawEntries(GuiGraphics graphics, int mouseX, int mouseY) {
        int left = panelLeft() + 14;
        int right = panelLeft() + panelWidth() - 14;
        int top = listTop();
        int bottom = listBottom();
        graphics.fill(left - 2, top - 2, right + 2, bottom + 2, Palette.BG_0);
        int visibleRows = visibleRows();
        scroll = Mth.clamp(scroll, 0, Math.max(0, entries.size() - visibleRows));
        for (int row = 0; row < visibleRows; row++) {
            int index = scroll + row;
            if (index >= entries.size()) {
                break;
            }
            int y = top + row * ROW_HEIGHT;
            boolean hovered = mouseX >= left && mouseX < right
                    && mouseY >= y && mouseY < y + ROW_HEIGHT - 2;
            int background = index == selectedIndex ? Palette.withAlpha(Palette.ACCENT_DEEP, 0.8F)
                    : hovered ? Palette.withAlpha(Palette.BG_4, 0.67F) : Palette.withAlpha(Palette.BG_3, 0.6F);
            graphics.fill(left, y, right, y + ROW_HEIGHT - 2, background);
            graphics.fill(left, y, left + 3, y + ROW_HEIGHT - 2,
                    index == selectedIndex ? Palette.ACCENT : Palette.ACCENT_STRONG);
            String fitted = font.plainSubstrByWidth(entries.get(index), Math.max(12, right - left - 20));
            graphics.drawString(font, fitted, left + 9, y + 6, Palette.TEXT_1, false);
        }
        if (entries.isEmpty()) {
            graphics.drawCenteredString(font,
                    Component.translatable("screen.music_battle.dj_presets.empty"),
                    (left + right) / 2,
                    top + Math.max(4, (bottom - top - font.lineHeight) / 2), Palette.TEXT_3);
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
                status = Component.empty();
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
            status = Component.translatable("screen.music_battle.dj_presets.confirm_overwrite", name);
            statusColor = Palette.WARN;
            refreshButtons();
            return;
        }
        try {
            actions.save(name);
            clearPendingAction();
            status = Component.translatable("screen.music_battle.dj_presets.saved", name);
            statusColor = Palette.OK;
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
            status = Component.translatable("screen.music_battle.dj_presets.loaded", name);
            statusColor = Palette.OK;
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
            status = Component.translatable("screen.music_battle.dj_presets.confirm_delete", name);
            statusColor = Palette.WARN;
            refreshButtons();
            return;
        }
        try {
            actions.delete(name);
            clearPendingAction();
            status = Component.translatable("screen.music_battle.dj_presets.deleted", name);
            statusColor = Palette.WARN;
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
        status = Component.translatable("screen.music_battle.dj_presets.error",
                font == null ? detail : font.plainSubstrByWidth(detail, Math.max(80, panelWidth() - 80)));
        statusColor = Palette.DANGER;
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

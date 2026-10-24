package ru.gora.musicbattle.client;

import java.io.IOException;
import ru.gora.musicbattle.client.ui.Palette;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import ru.gora.musicbattle.data.DjVisualEffectConfig;
import ru.gora.musicbattle.data.DjVisualEffects;
import ru.gora.musicbattle.data.TrackEffect;

/**
 * DJ visual mixer. Every edit feeds the DJ's own client-side monitor, but nothing reaches the
 * session until Apply: viewers keep the last applied set while the DJ auditions a draft.
 */
public final class DjVisualFxScreen extends Screen implements DjPanelOwnedScreen {
    private static final int ROW_HEIGHT = 21;

    private final Screen parent;
    private final Consumer<State> applyCallback;
    private final ClientDjVisualFxPreview preview = new ClientDjVisualFxPreview();
    private final List<DjVisualEffectConfig> draft = new ArrayList<>();
    private final int sourceBpm;
    private final float playbackSpeed;
    private final State initialState;
    private java.util.UUID playlistEntryId;
    private boolean trackFxEnabled;
    private boolean djFxEnabled;
    private boolean previewEnabled = true;
    private int selectedIndex = -1;
    private int listScroll;
    private TypeSelectionMode typeSelectionMode;
    private State lastPublishedState;
    private boolean livePreviewPublished;
    private boolean finished;

    private Button trackFxButton;
    private Button djFxButton;
    private Button previewButton;
    private Button addButton;
    private Button typeButton;
    private Button settingsButton;
    private Button enabledButton;
    private Button removeButton;
    private Button upButton;
    private Button downButton;

    public DjVisualFxScreen(Screen parent, boolean trackFxEnabled, boolean djFxEnabled,
                            List<DjVisualEffectConfig> effects, int sourceBpm, float playbackSpeed,
                            Consumer<State> applyCallback) {
        super(Component.translatable("screen.music_battle.dj_visual_fx.title"));
        this.parent = parent;
        this.trackFxEnabled = trackFxEnabled;
        this.djFxEnabled = djFxEnabled;
        this.draft.addAll(DjVisualEffects.sanitizeConfigs(effects));
        this.sourceBpm = Math.max(20, sourceBpm);
        this.playbackSpeed = Mth.clamp(playbackSpeed, 1.0F, 2.0F);
        this.applyCallback = applyCallback;
        this.initialState = currentState();
        this.lastPublishedState = initialState;
        if (!draft.isEmpty()) selectedIndex = 0;
        updatePreview();
    }

    public DjVisualFxScreen(Screen parent, boolean trackFxEnabled, boolean djFxEnabled,
                            List<DjVisualEffectConfig> effects, int sourceBpm, float playbackSpeed,
                            Consumer<State> applyCallback, java.util.UUID playlistEntryId) {
        this(parent, trackFxEnabled, djFxEnabled, effects, sourceBpm, playbackSpeed, applyCallback);
        this.playlistEntryId = playlistEntryId;
    }

    @Override
    protected void init() {
        int left = panelLeft();
        int top = panelTop();
        int width = panelWidth();
        int gap = 5;
        int third = (width - 28 - gap * 2) / 3;
        int toggleY = top + 30;
        trackFxButton = addRenderableWidget(FancyButton.create(trackFxLabel(), ignored -> {
            // Chart FX are not part of the local draft monitor; this toggle only travels on Apply.
            trackFxEnabled = !trackFxEnabled;
            refreshButtons();
        }, left + 14, toggleY, third, 20));
        djFxButton = addRenderableWidget(FancyButton.create(djFxLabel(), ignored -> {
            djFxEnabled = !djFxEnabled;
            updatePreview();
            refreshButtons();
        }, left + 14 + third + gap, toggleY, third, 20));
        previewButton = addRenderableWidget(FancyButton.create(previewLabel(), ignored -> {
            previewEnabled = !previewEnabled;
            updatePreview();
            refreshButtons();
        }, left + 14 + (third + gap) * 2, toggleY, third, 20));

        int controlY = controlsY();
        int controlWidth = Math.max(34, (width - 28 - gap * 6) / 7);
        int x = left + 14;
        addButton = addRenderableWidget(FancyButton.create(
                Component.translatable("screen.music_battle.dj_visual_fx.add"),
                ignored -> openTypeSelector(TypeSelectionMode.ADD),
                x, controlY, controlWidth, 20));
        x += controlWidth + gap;
        typeButton = addRenderableWidget(FancyButton.create(
                Component.translatable("screen.music_battle.dj_visual_fx.type"),
                ignored -> openTypeSelector(TypeSelectionMode.REPLACE),
                x, controlY, controlWidth, 20));
        x += controlWidth + gap;
        settingsButton = addRenderableWidget(FancyButton.create(
                Component.translatable("screen.music_battle.dj_visual_fx.settings"), ignored -> openSettings(),
                x, controlY, controlWidth, 20));
        x += controlWidth + gap;
        enabledButton = addRenderableWidget(FancyButton.create(enabledLabel(), ignored -> toggleSelected(),
                x, controlY, controlWidth, 20));
        x += controlWidth + gap;
        removeButton = addRenderableWidget(FancyButton.create(
                Component.translatable("screen.music_battle.dj_visual_fx.remove"), ignored -> removeSelected(),
                x, controlY, controlWidth, 20));
        x += controlWidth + gap;
        upButton = addRenderableWidget(FancyButton.create(Component.literal("↑"), ignored -> moveSelected(-1),
                x, controlY, controlWidth, 20));
        upButton.setTooltip(Tooltip.create(Component.translatable("screen.music_battle.dj_visual_fx.move_up")));
        x += controlWidth + gap;
        downButton = addRenderableWidget(FancyButton.create(Component.literal("↓"), ignored -> moveSelected(1),
                x, controlY, controlWidth, 20));
        downButton.setTooltip(Tooltip.create(Component.translatable("screen.music_battle.dj_visual_fx.move_down")));

        int bottomY = panelTop() + panelHeight() - 30;
        int bottomGap = 5;
        int bottomWidth = (width - 28 - bottomGap * 2) / 3;
        addRenderableWidget(FancyButton.create(
                Component.translatable("screen.music_battle.dj_visual_fx.presets"),
                ignored -> openPresets(), left + 14, bottomY, bottomWidth, 22));
        addRenderableWidget(FancyButton.create(Component.translatable("screen.music_battle.dj_visual_fx.apply"),
                ignored -> apply(), left + 14 + bottomWidth + bottomGap,
                bottomY, bottomWidth, 22));
        addRenderableWidget(FancyButton.create(Component.translatable("screen.music_battle.dj_visual_fx.cancel"),
                ignored -> onClose(), left + 14 + (bottomWidth + bottomGap) * 2,
                bottomY, bottomWidth, 22));
        refreshButtons();
    }

    @Override
    public void tick() {
        super.tick();
        DjPanelScreen owner = owningDjPanel();
        if (owner != null) {
            if (playlistEntryId != null && (!playlistEntryId.equals(owner.activePlaylistEntryId())
                    || owner.activePlaylistEntry() == null
                    || owner.activePlaylistEntry().fxMode() != ru.gora.musicbattle.data.DjPlaylistEntry.FxMode.DJ)) {
                // The queue moved on while this editor was open. Half-dialled settings are the DJ's work, so they
                // are applied to the rack on the way out rather than thrown away, and the rack is what the next
                // row plays.
                State carried = currentState();
                if (!carried.equals(initialState)) {
                    owner.applyVisualFxFromEditor(carried);
                }
                finished = true;
                onClose();
                return;
            }
            owner.tickCameraPreviewRequest();
        }
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        ClientTrackEffects.EffectState previewState = preview.sample();
        DjPanelScreen owner = owningDjPanel();
        boolean cameraVisible = owner != null
                && owner.renderActiveCameraPreview(graphics, 0, 0, width, height, partialTick, previewState);
        if (cameraVisible) {
            // Keep controls readable while retaining enough world detail to judge
            // the actual effect from the selected broadcast camera.
            graphics.fill(0, 0, width, height, Palette.withAlpha(Palette.BG_0, 0.28F));
        } else {
            // Opaque, not almost: the five per cent that showed through was the live effect stack still running
            // underneath, so the DJ saw the version they were replacing behind the one they were editing.
            graphics.fill(0, 0, width, height, Palette.BG_0);
        }
        if (cameraVisible) {
            DjCameraPreviewRenderer.withObjectOutlineSource(
                    () -> preview.renderBelowInterface(graphics, previewState));
        } else {
            preview.renderBelowInterface(graphics, previewState);
        }
        int left = panelLeft();
        int top = panelTop();
        int panelWidth = panelWidth();
        int panelHeight = panelHeight();
        graphics.fill(left, top, left + panelWidth, top + panelHeight, Palette.withAlpha(Palette.BG_1, 0.91F));
        graphics.fill(left, top, left + panelWidth, top + 3, Palette.ACCENT);
        graphics.drawCenteredString(font, title, left + panelWidth / 2, top + 10, Palette.TEXT_1);
        graphics.drawString(font, Component.translatable("screen.music_battle.dj_visual_fx.order_hint"),
                left + 14, listTop() - 11, Palette.TEXT_3, false);
        drawList(graphics, mouseX, mouseY);
        super.render(graphics, mouseX, mouseY, partialTick);
        if (cameraVisible) {
            DjCameraPreviewRenderer.withObjectOutlineSource(
                    () -> preview.renderAboveInterface(graphics, previewState));
        } else {
            preview.renderAboveInterface(graphics, previewState);
        }
        if (typeSelectionMode != null) {
            ClientUiForeground.render(graphics.pose(), graphics::flush,
                    () -> drawTypeSelector(graphics, mouseX, mouseY));
        }
    }

    private void drawList(GuiGraphics graphics, int mouseX, int mouseY) {
        int left = panelLeft() + 14;
        int right = panelLeft() + panelWidth() - 14;
        int top = listTop();
        int bottom = listBottom();
        graphics.fill(left - 2, top - 2, right + 2, bottom + 2, Palette.BG_0);
        int visibleRows = visibleRows();
        listScroll = Mth.clamp(listScroll, 0, Math.max(0, draft.size() - visibleRows));
        for (int row = 0; row < visibleRows; row++) {
            int index = listScroll + row;
            if (index >= draft.size()) break;
            DjVisualEffectConfig config = draft.get(index);
            int y = top + row * ROW_HEIGHT;
            boolean hovered = mouseX >= left && mouseX < right && mouseY >= y && mouseY < y + ROW_HEIGHT - 2;
            int background = index == selectedIndex ? Palette.withAlpha(Palette.ACCENT_DEEP, 0.8F) : hovered ? Palette.withAlpha(Palette.BG_4, 0.67F) : Palette.withAlpha(Palette.BG_3, 0.6F);
            graphics.fill(left, y, right, y + ROW_HEIGHT - 2, background);
            int marker = config.enabled() ? Palette.OK : Palette.LINE;
            graphics.fill(left, y, left + 3, y + ROW_HEIGHT - 2, marker);
            Component name = effectListName(index);
            graphics.drawString(font, name, left + 9, y + 6,
                    config.enabled() ? Palette.TEXT_1 : Palette.TEXT_MUTE, false);
            String order = Integer.toString(index + 1);
            graphics.drawString(font, order, right - font.width(order) - 6, y + 6, Palette.TEXT_2, false);
        }
        if (draft.isEmpty()) {
            graphics.drawCenteredString(font, Component.translatable("screen.music_battle.dj_visual_fx.empty"),
                    (left + right) / 2, top + Math.max(5, (bottom - top - font.lineHeight) / 2), Palette.TEXT_MUTE);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (typeSelectionMode != null) {
            if (button == 0) {
                int typeIndex = effectTypeAt(mouseX, mouseY);
                if (typeIndex >= 0) {
                    selectEffectType(DjVisualEffects.supportedTypes().get(typeIndex));
                } else if (!insideTypeSelector(mouseX, mouseY)) {
                    typeSelectionMode = null;
                }
            } else if (button == 1) {
                typeSelectionMode = null;
            }
            return true;
        }
        if (button == 0 && mouseX >= panelLeft() + 14 && mouseX < panelLeft() + panelWidth() - 14
                && mouseY >= listTop() && mouseY < listBottom()) {
            int row = (int) ((mouseY - listTop()) / ROW_HEIGHT);
            int index = listScroll + row;
            if (index >= 0 && index < draft.size()) {
                selectedIndex = index;
                refreshButtons();
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (typeSelectionMode != null) return true;
        if (mouseX >= panelLeft() + 14 && mouseX < panelLeft() + panelWidth() - 14
                && mouseY >= listTop() && mouseY < listBottom()) {
            listScroll = Mth.clamp(listScroll - (int) Math.signum(scrollY),
                    0, Math.max(0, draft.size() - visibleRows()));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (typeSelectionMode != null) {
            if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) typeSelectionMode = null;
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void openTypeSelector(TypeSelectionMode mode) {
        if (mode == TypeSelectionMode.ADD && draft.size() >= DjVisualEffectConfig.MAX_CONFIGS) return;
        if (mode == TypeSelectionMode.REPLACE && !hasSelection()) return;
        typeSelectionMode = mode;
    }

    private void selectEffectType(TrackEffect.Type type) {
        TypeSelectionMode mode = typeSelectionMode;
        typeSelectionMode = null;
        if (mode == TypeSelectionMode.ADD) {
            if (draft.size() >= DjVisualEffectConfig.MAX_CONFIGS) return;
            draft.add(new DjVisualEffectConfig(nextInstanceId(type), true,
                    ClientEffectToolSettings.defaults(type)));
            selectedIndex = draft.size() - 1;
            ensureSelectedVisible();
        } else if (mode == TypeSelectionMode.REPLACE && hasSelection()) {
            DjVisualEffectConfig current = draft.get(selectedIndex);
            if (current.effect().type() == type) return;
            draft.set(selectedIndex, new DjVisualEffectConfig(current.presetId(), current.enabled(),
                    ClientEffectToolSettings.defaults(type)));
        } else {
            return;
        }
        updatePreview();
        refreshButtons();
    }

    private void openSettings() {
        if (!hasSelection() || minecraft == null) return;
        int editingIndex = selectedIndex;
        DjVisualEffectConfig config = draft.get(editingIndex);
        TrackEffectEditHost editHost = new TrackEffectEditHost() {
            @Override
            public Screen parentScreen() {
                return DjVisualFxScreen.this;
            }

            @Override
            public Path effectPreviewAudioPath() {
                return null;
            }

            @Override public boolean managesPreviewAudio() { return false; }

            @Override public boolean supportsLivePreview() { return true; }

            @Override
            public void previewEffect(TrackEffect original, TrackEffect edited, String placementMode) {
                if (editingIndex >= 0 && editingIndex < draft.size()) {
                    DjVisualEffectConfig previous = draft.get(editingIndex);
                    draft.set(editingIndex, previous.withEffect(edited));
                    selectedIndex = editingIndex;
                    // Inspector drafts stay in this screen's monitor; viewers see them on Apply.
                    updatePreview();
                }
            }

            @Override
            public void cancelEffectPreview(TrackEffect original, TrackEffect initial, String placementMode) {
                if (editingIndex >= 0 && editingIndex < draft.size()) {
                    draft.set(editingIndex, config);
                    selectedIndex = editingIndex;
                    updatePreview();
                }
            }

            @Override
            public void saveEffect(TrackEffect original, TrackEffect edited, String placementMode) {
                if (editingIndex >= 0 && editingIndex < draft.size()) {
                    DjVisualEffectConfig previous = draft.get(editingIndex);
                    draft.set(editingIndex, previous.withEffect(edited));
                    selectedIndex = editingIndex;
                    updatePreview();
                }
            }

            @Override
            public void deleteEffect(TrackEffect original) {
                if (editingIndex >= 0 && editingIndex < draft.size()) {
                    draft.remove(editingIndex);
                    selectedIndex = draft.isEmpty() ? -1 : Math.min(editingIndex, draft.size() - 1);
                    updatePreview();
                }
            }

            @Override public boolean showBrushActions() { return false; }
            @Override public boolean showTimelineControls() { return false; }
            @Override public boolean allowTypeChange() { return false; }
            @Override public boolean persistTemplateOnClose() { return false; }
            @Override public int previewBpm() { return effectiveBpm(); }
            @Override public float previewPlaybackSpeed() { return playbackSpeed; }
            @Override public Component inspectorTitle(TrackEffect original) {
                return Component.translatable("screen.music_battle.dj_visual_fx.settings_title");
            }
        };
        minecraft.setScreen(new TrackEffectEditScreen(editHost, config.effect(), config.effect(), "range"));
    }

    private void openPresets() {
        if (minecraft == null) return;
        preview.reset();
        minecraft.setScreen(new DjPresetBrowserScreen(
                this,
                Component.translatable("screen.music_battle.dj_presets.effects_title"),
                Component.translatable("screen.music_battle.dj_presets.folder_hint"),
                new DjPresetBrowserScreen.PresetActions() {
                    @Override
                    public List<String> list() {
                        return ClientDjPresetStorage.listEffectSets().stream()
                                .map(ClientDjPresetStorage.EffectSetPreset::name)
                                .toList();
                    }

                    @Override
                    public void save(String name) throws IOException {
                        if (!ClientDjPresetStorage.saveEffectSet(
                                name, trackFxEnabled, djFxEnabled, draft)) {
                            throw new IOException("Unable to save DJ effect preset");
                        }
                    }

                    @Override
                    public void load(String name) throws IOException {
                        ClientDjPresetStorage.EffectSetPreset preset =
                                ClientDjPresetStorage.loadEffectSet(name)
                                        .orElseThrow(() -> new IOException(
                                                "DJ effect preset is unavailable"));
                        ClientDjPresetStorage.EffectApplication application =
                                preset.applyToCurrentModes(trackFxEnabled, djFxEnabled);
                        trackFxEnabled = application.trackFxEnabled();
                        djFxEnabled = application.djFxEnabled();
                        draft.clear();
                        draft.addAll(application.configs());
                        selectedIndex = draft.isEmpty() ? -1 : 0;
                        listScroll = 0;
                    }

                    @Override
                    public void delete(String name) throws IOException {
                        if (!ClientDjPresetStorage.deleteEffectSet(name)) {
                            throw new IOException("Unable to delete DJ effect preset");
                        }
                    }
                },
                () -> {
                    updatePreview();
                    refreshButtons();
                }));
    }

    private void toggleSelected() {
        if (!hasSelection()) return;
        DjVisualEffectConfig config = draft.get(selectedIndex);
        draft.set(selectedIndex, config.withEnabled(!config.enabled()));
        updatePreview();
        refreshButtons();
    }

    private void removeSelected() {
        if (!hasSelection()) return;
        draft.remove(selectedIndex);
        selectedIndex = draft.isEmpty() ? -1 : Math.min(selectedIndex, draft.size() - 1);
        ensureSelectedVisible();
        updatePreview();
        refreshButtons();
    }

    private void moveSelected(int direction) {
        if (!hasSelection()) return;
        int target = selectedIndex + direction;
        if (target < 0 || target >= draft.size()) return;
        DjVisualEffectConfig moved = draft.remove(selectedIndex);
        draft.add(target, moved);
        selectedIndex = target;
        ensureSelectedVisible();
        updatePreview();
        refreshButtons();
    }

    private void apply() {
        preview.reset();
        publishCurrentState(true);
        finished = true;
        if (minecraft != null) minecraft.setScreen(parent);
    }

    @Override
    public void onClose() {
        preview.reset();
        if (!finished && livePreviewPublished && !initialState.equals(lastPublishedState)) {
            applyCallback.accept(initialState);
            lastPublishedState = initialState;
        }
        finished = true;
        if (minecraft != null) minecraft.setScreen(parent);
    }

    private void updatePreview() {
        preview.setDraft(draft, effectiveBpm(), playbackSpeed);
        // The local overlay toggle remains independent from the DJ-FX master. Live publication
        // is driven by the canonical State below, never by this editor-only visibility switch.
        preview.setEnabled(previewEnabled);
    }

    private State currentState() {
        return new State(trackFxEnabled, djFxEnabled, DjVisualEffects.sanitizeConfigs(draft));
    }

    /** Apply is the only caller that forces publication; Cancel reuses it to roll back. */
    private void publishCurrentState(boolean force) {
        State state = currentState();
        if (!force && state.equals(lastPublishedState)) {
            return;
        }
        applyCallback.accept(state);
        lastPublishedState = state;
        if (!state.equals(initialState)) {
            livePreviewPublished = true;
        }
    }

    private void refreshButtons() {
        if (trackFxButton != null) trackFxButton.setMessage(trackFxLabel());
        if (djFxButton != null) djFxButton.setMessage(djFxLabel());
        if (previewButton != null) previewButton.setMessage(previewLabel());
        boolean selected = hasSelection();
        if (typeButton != null) typeButton.active = selected;
        if (settingsButton != null) settingsButton.active = selected;
        if (enabledButton != null) {
            enabledButton.active = selected;
            enabledButton.setMessage(enabledLabel());
        }
        if (removeButton != null) removeButton.active = selected;
        if (upButton != null) upButton.active = selected && selectedIndex > 0;
        if (downButton != null) downButton.active = selected && selectedIndex + 1 < draft.size();
        if (addButton != null) addButton.active = draft.size() < DjVisualEffectConfig.MAX_CONFIGS;
    }

    private Component trackFxLabel() {
        return toggle("screen.music_battle.dj_visual_fx.track_fx", trackFxEnabled);
    }

    private Component djFxLabel() {
        return toggle("screen.music_battle.dj_visual_fx.dj_fx", djFxEnabled);
    }

    private Component previewLabel() {
        return toggle("screen.music_battle.dj_visual_fx.preview", previewEnabled);
    }

    private Component enabledLabel() {
        return toggle("screen.music_battle.dj_visual_fx.enabled",
                hasSelection() && draft.get(selectedIndex).enabled());
    }

    private static Component toggle(String key, boolean enabled) {
        return Component.translatable(key).append(enabled ? " [ON]" : " [OFF]");
    }

    private boolean hasSelection() {
        return selectedIndex >= 0 && selectedIndex < draft.size();
    }

    private int effectiveBpm() {
        return Mth.clamp(Math.round(sourceBpm * playbackSpeed), 20, 800);
    }

    private void ensureSelectedVisible() {
        int rows = visibleRows();
        if (selectedIndex < listScroll) listScroll = selectedIndex;
        if (selectedIndex >= listScroll + rows) listScroll = selectedIndex - rows + 1;
        listScroll = Math.max(0, listScroll);
    }

    private int panelWidth() { return Math.min(620, Math.max(300, width - 24)); }
    private int panelHeight() { return Math.min(350, Math.max(210, height - 24)); }
    private int panelLeft() { return (width - panelWidth()) / 2; }
    private int panelTop() { return (height - panelHeight()) / 2; }
    private int listTop() { return panelTop() + 71; }
    private int controlsY() { return panelTop() + panelHeight() - 56; }
    private int listBottom() { return controlsY() - 7; }
    private int visibleRows() { return Math.max(1, (listBottom() - listTop()) / ROW_HEIGHT); }

    private Component effectListName(int index) {
        TrackEffect.Type type = draft.get(index).effect().type();
        int total = 0;
        int ordinal = 0;
        for (int cursor = 0; cursor < draft.size(); cursor++) {
            if (draft.get(cursor).effect().type() != type) continue;
            total++;
            if (cursor <= index) ordinal++;
        }
        Component name = Component.translatable(type.translationKey());
        return total > 1 ? name.copy().append(" #" + ordinal) : name;
    }

    private String nextInstanceId(TrackEffect.Type type) {
        String base = type.id();
        if (!usesPresetId(base)) return base;
        for (int ordinal = 2; ordinal <= DjVisualEffectConfig.MAX_CONFIGS + 1; ordinal++) {
            String suffix = "_" + ordinal;
            String candidate = base.substring(0, Math.min(base.length(), 32 - suffix.length())) + suffix;
            if (!usesPresetId(candidate)) return candidate;
        }
        return base.substring(0, Math.min(base.length(), 29)) + "_fx";
    }

    private boolean usesPresetId(String presetId) {
        return draft.stream().anyMatch(config -> config.presetId().equals(presetId));
    }

    private void drawTypeSelector(GuiGraphics graphics, int mouseX, int mouseY) {
        int left = typeSelectorLeft();
        int top = typeSelectorTop();
        int selectorWidth = typeSelectorWidth();
        int selectorHeight = typeSelectorHeight();
        graphics.fill(0, 0, width, height, Palette.withAlpha(Palette.BG_0, 0.9F));
        graphics.fill(left, top, left + selectorWidth, top + selectorHeight, Palette.BG_1);
        graphics.fill(left, top, left + selectorWidth, top + 3, Palette.ACCENT);
        Component heading = typeSelectionMode == TypeSelectionMode.ADD
                ? Component.translatable("screen.music_battle.dj_visual_fx.add")
                : Component.translatable("screen.music_battle.dj_visual_fx.type");
        graphics.drawCenteredString(font, heading.copy().append(" FX"), width / 2, top + 12, Palette.TEXT_1);

        TrackEffect.Type selectedType = typeSelectionMode == TypeSelectionMode.REPLACE && hasSelection()
                ? draft.get(selectedIndex).effect().type() : null;
        List<TrackEffect.Type> types = DjVisualEffects.supportedTypes();
        int columns = typeSelectorColumns();
        int cardWidth = typeSelectorCardWidth();
        int cardHeight = typeSelectorCardHeight();
        for (int index = 0; index < types.size(); index++) {
            TrackEffect.Type candidate = types.get(index);
            int x = left + 12 + (index % columns) * (cardWidth + 5);
            int y = top + 34 + (index / columns) * (cardHeight + 4);
            boolean hovered = mouseX >= x && mouseX < x + cardWidth
                    && mouseY >= y && mouseY < y + cardHeight;
            boolean selected = candidate == selectedType;
            int border = selected ? Palette.ACCENT : hovered ? Palette.LINK : Palette.LINE;
            graphics.fill(x, y, x + cardWidth, y + cardHeight, border);
            graphics.fill(x + 2, y + 2, x + cardWidth - 2, y + cardHeight - 2,
                    hovered ? Palette.BG_4 : Palette.BG_3);
            graphics.fill(x + 2, y + 2, x + 7, y + cardHeight - 2, candidate.color());
            String name = font.plainSubstrByWidth(
                    Component.translatable(candidate.translationKey()).getString(), cardWidth - 18);
            int nameY = cardHeight >= 34 ? y + 5 : y + Math.max(4, (cardHeight - font.lineHeight) / 2);
            graphics.drawString(font, name, x + 12, nameY,
                    selected ? Palette.TEXT_2 : Palette.TEXT_1, false);
            if (cardHeight >= 34) {
                String description = font.plainSubstrByWidth(
                        Component.translatable(candidate.translationKey() + ".description").getString(),
                        cardWidth - 18);
                graphics.drawString(font, description, x + 12, y + 20, Palette.TEXT_3, false);
            }
        }
        graphics.drawCenteredString(font, Component.literal("Esc"), width / 2,
                top + selectorHeight - 12, Palette.TEXT_MUTE);
    }

    private int effectTypeAt(double mouseX, double mouseY) {
        int columns = typeSelectorColumns();
        int cardWidth = typeSelectorCardWidth();
        int cardHeight = typeSelectorCardHeight();
        int left = typeSelectorLeft();
        int top = typeSelectorTop();
        List<TrackEffect.Type> types = DjVisualEffects.supportedTypes();
        for (int index = 0; index < types.size(); index++) {
            int x = left + 12 + (index % columns) * (cardWidth + 5);
            int y = top + 34 + (index / columns) * (cardHeight + 4);
            if (mouseX >= x && mouseX < x + cardWidth && mouseY >= y && mouseY < y + cardHeight) {
                return index;
            }
        }
        return -1;
    }

    private boolean insideTypeSelector(double mouseX, double mouseY) {
        int left = typeSelectorLeft();
        int top = typeSelectorTop();
        return mouseX >= left && mouseX < left + typeSelectorWidth()
                && mouseY >= top && mouseY < top + typeSelectorHeight();
    }

    private int typeSelectorColumns() {
        return width >= 520 || height < 390 ? 3 : 2;
    }

    private int typeSelectorRows() {
        return (DjVisualEffects.supportedTypes().size() + typeSelectorColumns() - 1) / typeSelectorColumns();
    }

    private int typeSelectorWidth() {
        int desired = typeSelectorColumns() == 3 ? 620 : 430;
        return Math.min(desired, Math.max(180, width - 20));
    }

    private int typeSelectorCardWidth() {
        int columns = typeSelectorColumns();
        return Math.max(45, (typeSelectorWidth() - 24 - (columns - 1) * 5) / columns);
    }

    private int typeSelectorCardHeight() {
        int rows = typeSelectorRows();
        int available = Math.max(100, height - 16) - 50 - (rows - 1) * 4;
        return Mth.clamp(available / rows, 18, 42);
    }

    private int typeSelectorHeight() {
        int rows = typeSelectorRows();
        return 50 + rows * typeSelectorCardHeight() + (rows - 1) * 4;
    }

    private int typeSelectorLeft() { return (width - typeSelectorWidth()) / 2; }
    private int typeSelectorTop() { return Math.max(4, (height - typeSelectorHeight()) / 2); }

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

    public record State(boolean trackFxEnabled, boolean djFxEnabled,
                        List<DjVisualEffectConfig> effects) {
        public State {
            effects = DjVisualEffects.sanitizeConfigs(effects);
        }
    }

    private enum TypeSelectionMode {
        ADD,
        REPLACE
    }
}

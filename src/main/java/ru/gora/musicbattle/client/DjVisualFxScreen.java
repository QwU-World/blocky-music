package ru.gora.musicbattle.client;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipPositioner;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import ru.gora.musicbattle.client.ui.Palette;
import ru.gora.musicbattle.client.ui.RoundedRect;
import ru.gora.musicbattle.client.ui.TextMeasurer;
import ru.gora.musicbattle.client.ui.TextStyle;
import ru.gora.musicbattle.client.ui.TextStyles;
import ru.gora.musicbattle.client.ui.UiClip;
import ru.gora.musicbattle.client.ui.UiIcon;
import ru.gora.musicbattle.client.ui.UiTooltip;
import ru.gora.musicbattle.client.workshop.WorkshopDraw;
import ru.gora.musicbattle.client.workshop.layout.Metrics;
import ru.gora.musicbattle.client.workshop.layout.Rect;
import ru.gora.musicbattle.client.workshop.layout.TextFit;
import ru.gora.musicbattle.data.DjVisualEffectConfig;
import ru.gora.musicbattle.data.DjVisualEffects;
import ru.gora.musicbattle.data.TrackEffect;

/**
 * DJ visual mixer. Every edit feeds the DJ's own client-side monitor, but nothing reaches the
 * session until Apply: viewers keep the last applied set while the DJ auditions a draft.
 *
 * <p>It is drawn in the toolkit's look, as the DJ panel it opens from: a raised panel with the title in Nunito, three
 * switches with their check boxes (it said "[ON]" and "[OFF]" after the words), the order hint wrapped inside the
 * panel above the list (it ran off the panel's right edge), the list's rows as rounded rows, the arrows as the
 * toolkit's chevrons (Nunito has no ↑ and ↓, so Minecraft drew them in its bitmap font), and the type picker as cards
 * with each effect's name whole. {@link FxLayout} and {@link PickerLayout} set it out by the text's own widths; the
 * dj-screens-kit check measures every word of it in English and Russian, from 320x240 up and at GUI scales 1-9.
 */
public final class DjVisualFxScreen extends Screen implements DjPanelOwnedScreen {
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
    /** How far the type picker's cards are scrolled, in GUI units; a picker opens at its top. */
    private int pickerScroll;
    private TypeSelectionMode typeSelectionMode;
    private State lastPublishedState;
    private boolean livePreviewPublished;
    private boolean finished;
    private FxLayout layout;
    private PickerLayout picker;

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
    /**
     * The hints of the two arrows, drawn as the toolkit's card (UiTooltip) like the DJ panel's, rather than in vanilla's
     * purple frame and bitmap font. A new holder comes with every build of the widgets.
     */
    private UiTooltip.Deferred tooltips = new UiTooltip.Deferred();

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
        tooltips = new UiTooltip.Deferred();
        layout = FxLayout.compute(width, height, TextStyles.measurer(), DjPanelScreen.Words.game());
        picker = null;
        trackFxButton = addRenderableWidget(KitButton.toggle(
                Component.translatable("screen.music_battle.dj_visual_fx.track_fx"), ignored -> {
                    // Chart FX are not part of the local draft monitor; this toggle only travels on Apply.
                    trackFxEnabled = !trackFxEnabled;
                    refreshButtons();
                }, layout.rect(FxLayout.Control.TRACK_FX), () -> trackFxEnabled));
        djFxButton = addRenderableWidget(KitButton.toggle(
                Component.translatable("screen.music_battle.dj_visual_fx.dj_fx"), ignored -> {
                    djFxEnabled = !djFxEnabled;
                    updatePreview();
                    refreshButtons();
                }, layout.rect(FxLayout.Control.DJ_FX), () -> djFxEnabled));
        previewButton = addRenderableWidget(KitButton.toggle(
                Component.translatable("screen.music_battle.dj_visual_fx.preview"), ignored -> {
                    previewEnabled = !previewEnabled;
                    updatePreview();
                    refreshButtons();
                }, layout.rect(FxLayout.Control.PREVIEW), () -> previewEnabled));

        addButton = addRenderableWidget(KitButton.text(Component.translatable("screen.music_battle.dj_visual_fx.add"),
                ignored -> openTypeSelector(TypeSelectionMode.ADD), layout.rect(FxLayout.Control.ADD),
                WorkshopDraw.ButtonStyle.SECONDARY));
        typeButton = addRenderableWidget(KitButton.text(Component.translatable("screen.music_battle.dj_visual_fx.type"),
                ignored -> openTypeSelector(TypeSelectionMode.REPLACE), layout.rect(FxLayout.Control.TYPE),
                WorkshopDraw.ButtonStyle.SECONDARY));
        settingsButton = addRenderableWidget(KitButton.text(
                Component.translatable("screen.music_battle.dj_visual_fx.settings"), ignored -> openSettings(),
                layout.rect(FxLayout.Control.SETTINGS), WorkshopDraw.ButtonStyle.SECONDARY));
        enabledButton = addRenderableWidget(KitButton.toggle(
                Component.translatable("screen.music_battle.dj_visual_fx.enabled"), ignored -> toggleSelected(),
                layout.rect(FxLayout.Control.ENABLED), () -> hasSelection() && draft.get(selectedIndex).enabled()));
        removeButton = addRenderableWidget(KitButton.text(
                Component.translatable("screen.music_battle.dj_visual_fx.remove"), ignored -> removeSelected(),
                layout.rect(FxLayout.Control.REMOVE), WorkshopDraw.ButtonStyle.DANGER));
        upButton = addRenderableWidget(KitButton.icon(Component.translatable("screen.music_battle.dj_visual_fx.move_up"),
                ignored -> moveSelected(-1), layout.rect(FxLayout.Control.UP), UiIcon.CHEVRON_DOWN, true));
        upButton.setTooltip(tooltips.create(Component.translatable("screen.music_battle.dj_visual_fx.move_up")));
        downButton = addRenderableWidget(KitButton.icon(
                Component.translatable("screen.music_battle.dj_visual_fx.move_down"), ignored -> moveSelected(1),
                layout.rect(FxLayout.Control.DOWN), UiIcon.CHEVRON_DOWN, false));
        downButton.setTooltip(tooltips.create(Component.translatable("screen.music_battle.dj_visual_fx.move_down")));

        addRenderableWidget(KitButton.text(Component.translatable("screen.music_battle.dj_visual_fx.presets"),
                ignored -> openPresets(), layout.rect(FxLayout.Control.PRESETS), WorkshopDraw.ButtonStyle.SECONDARY));
        addRenderableWidget(KitButton.text(Component.translatable("screen.music_battle.dj_visual_fx.apply"),
                ignored -> apply(), layout.rect(FxLayout.Control.APPLY), WorkshopDraw.ButtonStyle.PRIMARY));
        addRenderableWidget(KitButton.text(Component.translatable("screen.music_battle.dj_visual_fx.cancel"),
                ignored -> onClose(), layout.rect(FxLayout.Control.CANCEL), WorkshopDraw.ButtonStyle.SECONDARY));
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
        // Keep controls readable while retaining enough world detail to judge the effect: the selected broadcast
        // camera when there is one, the world around the DJ when there is not, under the same light wash. This was an
        // opaque fill for a while, to hide the running effect stack showing through behind the draft; that stack is
        // now silent for whoever has this editor open (ClientBattleController.authoringDjFx), so the fill only hid the
        // world the draft is meant to be judged against. The list keeps its own near-opaque panel, so it stays legible.
        graphics.fill(0, 0, width, height, Palette.withAlpha(Palette.BG_0, 0.28F));
        if (cameraVisible) {
            DjCameraPreviewRenderer.withObjectOutlineSource(
                    () -> preview.renderBelowInterface(graphics, previewState));
        } else {
            preview.renderBelowInterface(graphics, previewState);
        }
        FxLayout layout = layout();
        Rect panel = layout.panel();
        RoundedRect.shadow(graphics, panel.x(), panel.y() + 2.0F, panel.w(), panel.h(), Palette.RADIUS_LG, 12.0F,
                Palette.SHADOW_LIFTED);
        RoundedRect.fill(graphics, panel.x(), panel.y(), panel.w(), panel.h(), Palette.RADIUS_LG,
                Palette.withAlpha(Palette.BG_1, 0.91F));
        RoundedRect.stroke(graphics, panel.x(), panel.y(), panel.w(), panel.h(), Palette.RADIUS_LG, 1.0F, Palette.LINE);
        TextStyles.queueCentered(graphics, TextStyles.styled(title, layout.titleFace()).getVisualOrderText(),
                panel.centerX(), layout.titleY(), Palette.TEXT_1);
        RoundedRect.fill(graphics, panel.x() + FxLayout.PAD, layout.separatorY(), panel.w() - FxLayout.PAD * 2, 1.0F, 0,
                Palette.LINE);
        float hintY = layout.hintY();
        for (String line : layout.hint()) {
            TextStyles.queue(graphics, TextStyles.text(line, TextStyle.CAPTION).getVisualOrderText(),
                    panel.x() + FxLayout.PAD, hintY, Palette.TEXT_3);
            hintY += Metrics.lineHeight(TextStyle.CAPTION);
        }
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
        // Last, over the widgets and over the preview's FX that lie above the interface (+400), in the layer of the
        // editors' controls.
        if (!tooltips.placement(TextStyles.measurer(), mouseX, mouseY, width, height, getFocused()).empty()) {
            ClientUiForeground.render(graphics.pose(), graphics::flush,
                    () -> tooltips.draw(graphics, mouseX, mouseY, width, height, getFocused()));
        }
    }

    /**
     * A tooltip made by {@link #tooltips} is drawn by this screen in the toolkit's look; any other stays vanilla's. While
     * the type selector is open none is taken: its veil covers the buttons, and vanilla's tip, four hundred up, lay under
     * the veil's eight hundred and never showed either.
     */
    @Override
    public void setTooltipForNextRenderPass(Tooltip tooltip, ClientTooltipPositioner positioner, boolean focused) {
        if (typeSelectionMode != null) {
            return;
        }
        if (!tooltips.offer(tooltip, positioner)) {
            super.setTooltipForNextRenderPass(tooltip, positioner, focused);
        }
    }

    /** The draft's effects as the toolkit's rows: the selected one in the accent, a pill that is lit while it is on. */
    private void drawList(GuiGraphics graphics, int mouseX, int mouseY) {
        FxLayout layout = layout();
        Rect list = layout.list();
        RoundedRect.fill(graphics, list.x() - 2, list.y() - 2, list.w() + 4, list.h() + 4, Palette.RADIUS_MD,
                Palette.withAlpha(Palette.BG_0, 0.7F));
        int visibleRows = layout.visibleRows();
        listScroll = Mth.clamp(listScroll, 0, Math.max(0, draft.size() - visibleRows));
        int rowHeight = layout.rowHeight();
        float right = list.right() - (draft.size() > visibleRows ? 6 : 0);
        for (int row = 0; row < visibleRows; row++) {
            int index = listScroll + row;
            if (index >= draft.size()) break;
            DjVisualEffectConfig config = draft.get(index);
            float y = list.y() + row * rowHeight;
            float rowBottom = y + rowHeight - 2;
            boolean hovered = typeSelectionMode == null && mouseX >= list.x() && mouseX < right && mouseY >= y
                    && mouseY < rowBottom;
            boolean selected = index == selectedIndex;
            int background = selected ? Palette.mix(Palette.BG_3, Palette.ACCENT_DEEP, 0.85F)
                    : hovered ? Palette.BG_3 : Palette.withAlpha(Palette.BG_2, 0.9F);
            RoundedRect.fill(graphics, list.x(), y, right - list.x(), rowHeight - 2, Palette.RADIUS_SM, background);
            if (selected) {
                RoundedRect.stroke(graphics, list.x(), y, right - list.x(), rowHeight - 2, Palette.RADIUS_SM, 1.0F,
                        Palette.ACCENT);
            }
            RoundedRect.pill(graphics, list.x() + 3, y + 4, 2.5F, rowHeight - 10, config.enabled() ? Palette.OK : Palette.LINE);
            TextStyle face = layout.rowFace();
            String order = Integer.toString(index + 1);
            float orderWidth = TextFit.width(TextStyles.measurer(), order, TextStyle.CAPTION);
            float nameRoom = right - 6 - orderWidth - 8 - (list.x() + FxLayout.ROW_TEXT);
            String name = TextFit.ellipsize(TextStyles.measurer(), effectListName(index).getString(), face, nameRoom);
            float textY = TextStyles.centeredCapY(face, y, rowHeight - 2);
            TextStyles.queue(graphics, TextStyles.text(name, face).getVisualOrderText(), list.x() + FxLayout.ROW_TEXT,
                    textY, config.enabled() ? Palette.TEXT_1 : Palette.TEXT_MUTE);
            TextStyles.queueRight(graphics, TextStyles.text(order, TextStyle.CAPTION).getVisualOrderText(), right - 6,
                    TextStyles.centeredCapY(TextStyle.CAPTION, y, rowHeight - 2), Palette.TEXT_3);
        }
        if (draft.size() > visibleRows) {
            WorkshopDraw.scrollbar(graphics, new Rect(list.x(), list.y(), list.w(), visibleRows * rowHeight - 2),
                    listScroll * rowHeight, draft.size() * rowHeight - 2, false);
        }
        if (draft.isEmpty()) {
            String message = Component.translatable("screen.music_battle.dj_visual_fx.empty").getString();
            DjPanelScreen.PanelLayout.Block block = FxLayout.emptyBlock(TextStyles.measurer(), message, list);
            if (block == null) {
                block = new DjPanelScreen.PanelLayout.Block(TextStyle.CAPTION, TextFit.wrap(TextStyles.measurer(), message,
                        TextStyle.CAPTION, list.w() - 12, 2));
            }
            float y = list.y() + Math.max(4.0F, (list.h() - block.height()) / 2.0F);
            for (String line : block.lines()) {
                TextStyles.queueCentered(graphics, TextStyles.text(line, block.face()).getVisualOrderText(), list.centerX(),
                        y, Palette.TEXT_MUTE);
                y += Metrics.lineHeight(block.face());
            }
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
        Rect list = layout().list();
        if (button == 0 && list.contains(mouseX, mouseY)) {
            int row = (int) ((mouseY - list.y()) / layout().rowHeight());
            int index = listScroll + row;
            if (row < layout().visibleRows() && index >= 0 && index < draft.size()) {
                selectedIndex = index;
                refreshButtons();
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (typeSelectionMode != null) {
            PickerLayout picker = picker();
            pickerScroll = Mth.clamp(pickerScroll - (int) Math.round(scrollY * picker.step()), 0, picker.scrollMax());
            return true;
        }
        if (layout().list().contains(mouseX, mouseY)) {
            listScroll = Mth.clamp(listScroll - (int) Math.signum(scrollY),
                    0, Math.max(0, draft.size() - layout().visibleRows()));
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
        pickerScroll = 0;
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
        boolean selected = hasSelection();
        if (typeButton != null) typeButton.active = selected;
        if (settingsButton != null) settingsButton.active = selected;
        if (enabledButton != null) enabledButton.active = selected;
        if (removeButton != null) removeButton.active = selected;
        if (upButton != null) upButton.active = selected && selectedIndex > 0;
        if (downButton != null) downButton.active = selected && selectedIndex + 1 < draft.size();
        if (addButton != null) addButton.active = draft.size() < DjVisualEffectConfig.MAX_CONFIGS;
    }

    private boolean hasSelection() {
        return selectedIndex >= 0 && selectedIndex < draft.size();
    }

    private int effectiveBpm() {
        return Mth.clamp(Math.round(sourceBpm * playbackSpeed), 20, 800);
    }


    private void ensureSelectedVisible() {
        int rows = layout().visibleRows();
        if (selectedIndex < listScroll) listScroll = selectedIndex;
        if (selectedIndex >= listScroll + rows) listScroll = selectedIndex - rows + 1;
        listScroll = Math.max(0, listScroll);
    }

    /** The layout of the window as it is now; init sets it, and a screen not yet shown lays itself out on demand. */
    private FxLayout layout() {
        if (layout == null || layout.width() != width || layout.height() != height) {
            layout = FxLayout.compute(width, height, TextStyles.measurer(), DjPanelScreen.Words.game());
        }
        return layout;
    }

    /** The type picker's layout for the window, made the first time it is drawn or pointed at. */
    private PickerLayout picker() {
        if (picker == null || picker.width() != width || picker.height() != height) {
            picker = PickerLayout.compute(width, height, TextStyles.measurer(), DjPanelScreen.Words.game(),
                    DjVisualEffects.supportedTypes());
        }
        return picker;
    }

    /**
     * The type picker over a dark veil: a raised panel with what it is for, the effects as cards with their colour, name
     * and, where every description fits two lines, the description under it, scrolled when they do not all fit, and how
     * to leave at its foot. The heading used to be the button's words with " FX" after them, "+ FX FX".
     */
    private void drawTypeSelector(GuiGraphics graphics, int mouseX, int mouseY) {
        PickerLayout picker = picker();
        pickerScroll = Mth.clamp(pickerScroll, 0, picker.scrollMax());
        Rect panel = picker.panel();
        graphics.fill(0, 0, width, height, Palette.withAlpha(Palette.BG_0, 0.9F));
        RoundedRect.shadow(graphics, panel.x(), panel.y() + 2.0F, panel.w(), panel.h(), Palette.RADIUS_LG, 12.0F,
                Palette.SHADOW_LIFTED);
        RoundedRect.fill(graphics, panel.x(), panel.y(), panel.w(), panel.h(), Palette.RADIUS_LG, Palette.BG_1);
        RoundedRect.stroke(graphics, panel.x(), panel.y(), panel.w(), panel.h(), Palette.RADIUS_LG, 1.0F, Palette.LINE);
        String heading = Component.translatable(typeSelectionMode == TypeSelectionMode.ADD
                ? PickerLayout.ADD_HEADING : PickerLayout.TYPE_HEADING).getString();
        TextStyles.queueCentered(graphics, TextStyles.text(heading, picker.headingFace()).getVisualOrderText(),
                panel.centerX(), picker.headingY(), Palette.TEXT_1);
        RoundedRect.fill(graphics, panel.x() + PickerLayout.PAD, picker.separatorY(), panel.w() - PickerLayout.PAD * 2,
                1.0F, 0, Palette.LINE);

        TrackEffect.Type selectedType = typeSelectionMode == TypeSelectionMode.REPLACE && hasSelection()
                ? draft.get(selectedIndex).effect().type() : null;
        List<TrackEffect.Type> types = DjVisualEffects.supportedTypes();
        Rect view = picker.view();
        boolean inView = view.contains(mouseX, mouseY);
        UiClip.push(graphics, view.x() - 2, view.y(), view.right() + 2, view.bottom());
        for (int index = 0; index < types.size(); index++) {
            Rect card = picker.card(index, pickerScroll);
            if (card.bottom() < view.y() || card.y() > view.bottom()) {
                continue;
            }
            TrackEffect.Type candidate = types.get(index);
            boolean hovered = inView && card.contains(mouseX, mouseY);
            boolean selected = candidate == selectedType;
            int fill = selected ? Palette.mix(Palette.BG_3, Palette.ACCENT_DEEP, 0.85F) : hovered ? Palette.BG_4 : Palette.BG_3;
            RoundedRect.fill(graphics, card.x(), card.y(), card.w(), card.h(), Palette.RADIUS_SM, fill);
            if (selected || hovered) {
                RoundedRect.stroke(graphics, card.x(), card.y(), card.w(), card.h(), Palette.RADIUS_SM, 1.0F,
                        selected ? Palette.ACCENT : Palette.mix(Palette.LINE, Palette.ACCENT, 0.6F));
            }
            RoundedRect.pill(graphics, card.x() + 4, card.y() + 4, 3.0F, card.h() - 8, 0xFF000000 | candidate.color());
            float textX = card.x() + PickerLayout.TEXT_LEFT;
            float room = card.w() - PickerLayout.TEXT_LEFT - PickerLayout.TEXT_RIGHT;
            String name = Component.translatable(candidate.translationKey()).getString();
            float nameY = picker.descriptions() ? card.y() + 6 : TextStyles.centeredCapY(PickerLayout.NAME_FACE, card.y(), card.h());
            TextStyles.queue(graphics, TextStyles.text(TextFit.ellipsize(TextStyles.measurer(), name, PickerLayout.NAME_FACE,
                    room), PickerLayout.NAME_FACE).getVisualOrderText(), textX, nameY, Palette.TEXT_1);
            if (picker.descriptions()) {
                String description = Component.translatable(candidate.translationKey() + ".description").getString();
                float y = nameY + PickerLayout.NAME_FACE.capHeight() + 5;
                for (String line : TextFit.wrap(TextStyles.measurer(), description, TextStyle.CAPTION, room, 2)) {
                    TextStyles.queue(graphics, TextStyles.text(line, TextStyle.CAPTION).getVisualOrderText(), textX, y,
                            Palette.TEXT_3);
                    y += Metrics.lineHeight(TextStyle.CAPTION);
                }
            }
        }
        UiClip.pop(graphics);
        WorkshopDraw.scrollbar(graphics, new Rect(view.x(), view.y(), view.w() + 6, view.h()), pickerScroll,
                view.h() + picker.scrollMax(), false);
        TextStyles.queueCentered(graphics, TextStyles.text(Component.translatable(PickerLayout.LEAVE_HINT).getString(),
                TextStyle.CAPTION).getVisualOrderText(), panel.centerX(), picker.footY(), Palette.TEXT_MUTE);
    }

    private int effectTypeAt(double mouseX, double mouseY) {
        PickerLayout picker = picker();
        if (!picker.view().contains(mouseX, mouseY)) {
            return -1;
        }
        List<TrackEffect.Type> types = DjVisualEffects.supportedTypes();
        for (int index = 0; index < types.size(); index++) {
            if (picker.card(index, pickerScroll).contains(mouseX, mouseY)) {
                return index;
            }
        }
        return -1;
    }

    private boolean insideTypeSelector(double mouseX, double mouseY) {
        return picker().panel().contains(mouseX, mouseY);
    }

    /**
     * Where the list's parts sit, in GUI units, and how each of its words is set: a panel up to 620 by 360 inside the
     * window, the title over a rule, the three switches, the order hint in captions wrapped inside the panel, the list,
     * the selected effect's controls and the panel's own actions at its foot. Each row of buttons takes its first
     * arrangement in which every label fits whole: one line, else the lines below. Pure, like the DJ panel's layout.
     */
    static final class FxLayout {
        static final int PAD = 10;
        static final int GAP = 5;
        /** The list's text starts here, past the row's on/off pill. */
        static final float ROW_TEXT = 11.0F;
        /** An effect's name at its longest: a type's name with the number the eighth copy of it gets. */
        static final String MOST_COPIES = " #" + DjVisualEffectConfig.MAX_CONFIGS;
        static final String KEY = "screen.music_battle.dj_visual_fx.";

        enum Control { TRACK_FX, DJ_FX, PREVIEW, ADD, TYPE, SETTINGS, ENABLED, REMOVE, UP, DOWN, PRESETS, APPLY, CANCEL }

        /** How a control shows: words, a switch's check box and words, or an icon. */
        enum Kind { TEXT, SWITCH, ICON }

        private record Cell(Control control, Kind kind, String key) { }

        private static final List<List<List<Cell>>> SWITCHES = List.of(
                List.of(List.of(toggle(Control.TRACK_FX, "track_fx"), toggle(Control.DJ_FX, "dj_fx"),
                        toggle(Control.PREVIEW, "preview"))),
                List.of(List.of(toggle(Control.TRACK_FX, "track_fx"), toggle(Control.DJ_FX, "dj_fx")),
                        List.of(toggle(Control.PREVIEW, "preview"))));
        private static final List<List<List<Cell>>> CONTROLS = List.of(
                List.of(List.of(text(Control.ADD, "add"), text(Control.TYPE, "type"), text(Control.SETTINGS, "settings"),
                        toggle(Control.ENABLED, "enabled"), text(Control.REMOVE, "remove"), icon(Control.UP),
                        icon(Control.DOWN))),
                List.of(List.of(text(Control.ADD, "add"), text(Control.TYPE, "type"), text(Control.SETTINGS, "settings"),
                        text(Control.REMOVE, "remove")),
                        List.of(toggle(Control.ENABLED, "enabled"), icon(Control.UP), icon(Control.DOWN))),
                List.of(List.of(text(Control.ADD, "add"), text(Control.TYPE, "type")),
                        List.of(text(Control.SETTINGS, "settings"), text(Control.REMOVE, "remove")),
                        List.of(toggle(Control.ENABLED, "enabled"), icon(Control.UP), icon(Control.DOWN))));
        private static final List<List<List<Cell>>> ACTIONS = List.of(
                List.of(List.of(text(Control.PRESETS, "presets"), text(Control.APPLY, "apply"), text(Control.CANCEL, "cancel"))),
                List.of(List.of(text(Control.PRESETS, "presets")),
                        List.of(text(Control.APPLY, "apply"), text(Control.CANCEL, "cancel"))));

        private final int width;
        private final int height;
        private final Rect panel;
        private final TextStyle titleFace;
        private final float titleY;
        private final float separatorY;
        private final List<String> hint;
        private final float hintY;
        private final Rect list;
        private final int rowHeight;
        private final TextStyle rowFace;
        private final Map<Control, Rect> controls;
        private final Map<Control, Kind> kinds;
        private final List<String> problems;

        private FxLayout(int width, int height, Rect panel, TextStyle titleFace, float titleY, float separatorY,
                         List<String> hint, float hintY, Rect list, int rowHeight, TextStyle rowFace,
                         Map<Control, Rect> controls, Map<Control, Kind> kinds, List<String> problems) {
            this.width = width;
            this.height = height;
            this.panel = panel;
            this.titleFace = titleFace;
            this.titleY = titleY;
            this.separatorY = separatorY;
            this.hint = List.copyOf(hint);
            this.hintY = hintY;
            this.list = list;
            this.rowHeight = rowHeight;
            this.rowFace = rowFace;
            this.controls = controls;
            this.kinds = kinds;
            this.problems = List.copyOf(problems);
        }

        static FxLayout compute(int width, int height, TextMeasurer measurer, DjPanelScreen.Words words) {
            List<String> problems = new ArrayList<>();
            int panelWidth = Math.min(620, width - 16);
            int panelHeight = Math.min(360, height - 12);
            Rect panel = new Rect((width - panelWidth) / 2, (height - panelHeight) / 2, panelWidth, panelHeight);
            float inner = panelWidth - PAD * 2;
            float left = panel.x() + PAD;
            int buttonHeight = height < 300 ? 18 : 20;
            int header = height < 300 ? 24 : 28;

            String title = words.get(KEY + "title");
            TextStyle titleFace = TextFit.width(measurer, title, TextStyle.TITLE) <= inner ? TextStyle.TITLE : TextStyle.STRONG;
            if (TextFit.width(measurer, title, titleFace) > inner) {
                problems.add("the title is wider than the panel");
            }
            float titleY = panel.y() + (header - titleFace.capHeight()) / 2.0F;
            float separatorY = panel.y() + header;

            Map<Control, Rect> controls = new EnumMap<>(Control.class);
            Map<Control, Kind> kinds = new EnumMap<>(Control.class);
            float y = separatorY + 6;
            y = rows(SWITCHES, measurer, words, left, inner, y, buttonHeight, controls, kinds, problems, "switches");

            List<String> hint = TextFit.wrap(measurer, words.get(KEY + "order_hint"), TextStyle.CAPTION, inner);
            for (String word : words.get(KEY + "order_hint").split(" ")) {
                if (TextFit.width(measurer, word, TextStyle.CAPTION) > inner) {
                    problems.add("a word of the order hint is wider than the panel");
                }
            }
            float hintY = y + 3;
            float listTop = hintY + hint.size() * Metrics.lineHeight(TextStyle.CAPTION) + 5;

            // The panel's own actions at its foot, the selected effect's controls above them.
            float bottom = panel.bottom() - PAD;
            int actionLines = lines(ACTIONS, measurer, words, inner, buttonHeight);
            float actionsTop = bottom - actionLines * buttonHeight - (actionLines - 1) * 4;
            rows(ACTIONS, measurer, words, left, inner, actionsTop, buttonHeight, controls, kinds, problems, "actions");
            int controlLines = lines(CONTROLS, measurer, words, inner, buttonHeight);
            float controlsTop = actionsTop - 6 - controlLines * buttonHeight - (controlLines - 1) * 4;
            rows(CONTROLS, measurer, words, left, inner, controlsTop, buttonHeight, controls, kinds, problems, "controls");

            int rowHeight = buttonHeight + 2;
            Rect list = new Rect(left, listTop, inner, Math.max(0, controlsTop - 6 - listTop));
            if (list.h() < rowHeight * 2) {
                problems.add("the list shows fewer than two rows, " + list.h());
            }
            TextStyle rowFace = KitButton.face(buttonHeight);
            FxLayout layout = new FxLayout(width, height, panel, titleFace, titleY, separatorY, hint, hintY, list, rowHeight,
                    rowFace, controls, kinds, problems);
            return layout;
        }

        /** The width a list row leaves an effect's name: past the pill, before the order number and the scrollbar. */
        float nameRoom(TextMeasurer measurer) {
            return list.w() - 6 - TextFit.width(measurer, "8", TextStyle.CAPTION) - 8 - ROW_TEXT - 6;
        }

        /** The empty list's message, in the body face or the caption, whole. */
        static DjPanelScreen.PanelLayout.Block emptyBlock(TextMeasurer measurer, String text, Rect list) {
            return DjPanelScreen.PanelLayout.block(measurer, text, list.w() - 12, list.h() - 4, TextStyle.BODY,
                    TextStyle.CAPTION);
        }

        /** How many lines the first arrangement that fits takes. */
        private static int lines(List<List<List<Cell>>> variants, TextMeasurer measurer, DjPanelScreen.Words words,
                                 float inner, int height) {
            for (List<List<Cell>> variant : variants) {
                if (fits(variant, measurer, words, inner, height)) {
                    return variant.size();
                }
            }
            return variants.getLast().size();
        }

        private static boolean fits(List<List<Cell>> variant, TextMeasurer measurer, DjPanelScreen.Words words, float inner,
                                    int height) {
            for (List<Cell> line : variant) {
                if (widths(line, measurer, words, inner, height) == null) {
                    return false;
                }
            }
            return true;
        }

        /** Places the first arrangement that fits from {@code top}, and returns the y under it. */
        private static float rows(List<List<List<Cell>>> variants, TextMeasurer measurer, DjPanelScreen.Words words,
                                  float left, float inner, float top, int height, Map<Control, Rect> controls,
                                  Map<Control, Kind> kinds, List<String> problems, String what) {
            List<List<Cell>> chosen = variants.getLast();
            boolean found = false;
            for (List<List<Cell>> variant : variants) {
                if (fits(variant, measurer, words, inner, height)) {
                    chosen = variant;
                    found = true;
                    break;
                }
            }
            if (!found) {
                problems.add("no arrangement of the " + what + " fits the panel's width");
            }
            float y = top;
            for (List<Cell> line : chosen) {
                int[] widths = widths(line, measurer, words, inner, height);
                if (widths == null) {
                    widths = new int[line.size()];
                    java.util.Arrays.fill(widths, Math.max(1, ((int) inner - GAP * (line.size() - 1)) / line.size()));
                }
                float x = left;
                for (int index = 0; index < line.size(); index++) {
                    Cell cell = line.get(index);
                    controls.put(cell.control(), new Rect(Math.round(x), Math.round(y), widths[index], height));
                    kinds.put(cell.control(), cell.kind());
                    x += widths[index] + GAP;
                }
                y += height + 4;
            }
            return y - 4;
        }

        /**
         * Whole widths for a line's cells across {@code inner}, or null when their words need more: the room left over
         * goes to the cells with words, one width for all of them when every label allows it.
         */
        private static int[] widths(List<Cell> line, TextMeasurer measurer, DjPanelScreen.Words words, float inner,
                                    int height) {
            int count = line.size();
            int[] needs = new int[count];
            int growers = 0;
            int widest = 0;
            int total = 0;
            for (int index = 0; index < count; index++) {
                Cell cell = line.get(index);
                needs[index] = need(cell, measurer, words, height);
                total += needs[index];
                if (cell.kind() != Kind.ICON) {
                    growers++;
                    widest = Math.max(widest, needs[index]);
                }
            }
            int room = (int) Math.floor(inner) - GAP * (count - 1);
            if (total > room) {
                return null;
            }
            int[] equal = needs.clone();
            int equalTotal = 0;
            for (int index = 0; index < count; index++) {
                if (line.get(index).kind() != Kind.ICON) {
                    equal[index] = widest;
                }
                equalTotal += equal[index];
            }
            int[] widths = equalTotal <= room ? equal : needs.clone();
            int used = 0;
            for (int width : widths) {
                used += width;
            }
            int share = growers == 0 ? 0 : (room - used) / growers;
            int extra = growers == 0 ? 0 : (room - used) % growers;
            int seen = 0;
            for (int index = 0; index < count; index++) {
                if (line.get(index).kind() != Kind.ICON) {
                    widths[index] += share + (seen++ >= growers - extra ? 1 : 0);
                }
            }
            return widths;
        }

        private static int need(Cell cell, TextMeasurer measurer, DjPanelScreen.Words words, int height) {
            return switch (cell.kind()) {
                case ICON -> KitButton.ICON_BUTTON;
                case TEXT -> KitButton.need(measurer, words.get(KEY + cell.key()), height, false);
                case SWITCH -> KitButton.need(measurer, words.get(KEY + cell.key()), height, true);
            };
        }

        /** The words of a control, as the layout measured them; empty for an icon. */
        static String words(Control control, DjPanelScreen.Words words) {
            for (List<List<List<Cell>>> rows : List.of(SWITCHES, CONTROLS, ACTIONS)) {
                for (List<Cell> line : rows.getFirst()) {
                    for (Cell cell : line) {
                        if (cell.control() == control) {
                            return cell.key() == null ? "" : words.get(KEY + cell.key());
                        }
                    }
                }
            }
            return "";
        }

        private static Cell text(Control control, String key) {
            return new Cell(control, Kind.TEXT, key);
        }

        private static Cell toggle(Control control, String key) {
            return new Cell(control, Kind.SWITCH, key);
        }

        private static Cell icon(Control control) {
            return new Cell(control, Kind.ICON, null);
        }

        int width() { return width; }
        int height() { return height; }
        Rect panel() { return panel; }
        TextStyle titleFace() { return titleFace; }
        float titleY() { return titleY; }
        float separatorY() { return separatorY; }
        List<String> hint() { return hint; }
        float hintY() { return hintY; }
        Rect list() { return list; }
        int rowHeight() { return rowHeight; }
        TextStyle rowFace() { return rowFace; }
        int visibleRows() { return Math.max(1, (int) ((list.h() + 2) / rowHeight)); }
        Rect rect(Control control) { return controls.get(control); }
        Kind kind(Control control) { return kinds.get(control); }
        Map<Control, Rect> controls() { return controls; }
        List<String> problems() { return problems; }
    }

    /**
     * The type picker: a panel inside the window with its heading over a rule, the effects as cards in as many columns
     * (up to three) as leave the widest name whole, a description under each name where every description fits two
     * lines of its card, the cards scrolling between the rule and the foot line when they do not all fit. Pure.
     */
    static final class PickerLayout {
        static final int PAD = 12;
        static final int GAP = 5;
        static final float TEXT_LEFT = 13.0F;
        static final float TEXT_RIGHT = 6.0F;
        static final TextStyle NAME_FACE = TextStyle.BODY;
        static final String ADD_HEADING = "screen.music_battle.dj_visual_fx.choose_add";
        static final String TYPE_HEADING = "screen.music_battle.dj_visual_fx.choose_type";
        static final String LEAVE_HINT = "screen.music_battle.dj_visual_fx.choose_leave";

        private final int width;
        private final int height;
        private final Rect panel;
        private final TextStyle headingFace;
        private final float headingY;
        private final float separatorY;
        private final Rect view;
        private final float footY;
        private final int columns;
        private final float cardWidth;
        private final float cardHeight;
        private final boolean descriptions;
        private final int count;
        private final List<String> problems;

        private PickerLayout(int width, int height, Rect panel, TextStyle headingFace, float headingY, float separatorY,
                             Rect view, float footY, int columns, float cardWidth, float cardHeight, boolean descriptions,
                             int count, List<String> problems) {
            this.width = width;
            this.height = height;
            this.panel = panel;
            this.headingFace = headingFace;
            this.headingY = headingY;
            this.separatorY = separatorY;
            this.view = view;
            this.footY = footY;
            this.columns = columns;
            this.cardWidth = cardWidth;
            this.cardHeight = cardHeight;
            this.descriptions = descriptions;
            this.count = count;
            this.problems = List.copyOf(problems);
        }

        static PickerLayout compute(int width, int height, TextMeasurer measurer, DjPanelScreen.Words words,
                                    List<TrackEffect.Type> types) {
            List<String> problems = new ArrayList<>();
            int panelWidth = Math.min(620, width - 16);
            float inner = panelWidth - PAD * 2;
            float widestName = 0.0F;
            for (TrackEffect.Type type : types) {
                widestName = Math.max(widestName, TextFit.width(measurer, words.get(type.translationKey()), NAME_FACE));
            }
            int columns = 1;
            for (int candidate = 3; candidate >= 1; candidate--) {
                if (cardWidth(inner, candidate) - TEXT_LEFT - TEXT_RIGHT >= widestName) {
                    columns = candidate;
                    break;
                }
            }
            float cardWidth = cardWidth(inner, columns);
            float room = cardWidth - TEXT_LEFT - TEXT_RIGHT;
            if (widestName > room) {
                problems.add("an effect's name is wider than a card");
            }
            boolean descriptions = true;
            for (TrackEffect.Type type : types) {
                String description = words.get(type.translationKey() + ".description");
                descriptions &= DjPanelScreen.PanelLayout.block(measurer, description, room,
                        2 * Metrics.lineHeight(TextStyle.CAPTION), TextStyle.CAPTION) != null;
            }
            float cardHeight = descriptions ? 6 + NAME_FACE.capHeight() + 5 + 2 * Metrics.lineHeight(TextStyle.CAPTION) + 3
                    : 22.0F;
            int rows = (types.size() + columns - 1) / columns;
            float content = rows * cardHeight + (rows - 1) * GAP;
            int header = height < 300 ? 24 : 28;
            int foot = 16;
            float panelHeight = Math.min(height - 12, header + 6 + content + 6 + foot);
            Rect panel = new Rect((width - panelWidth) / 2.0F, (height - panelHeight) / 2.0F, panelWidth, panelHeight);
            String add = words.get(ADD_HEADING);
            String change = words.get(TYPE_HEADING);
            float widestHeading = Math.max(TextFit.width(measurer, add, TextStyle.TITLE),
                    TextFit.width(measurer, change, TextStyle.TITLE));
            TextStyle headingFace = widestHeading <= inner ? TextStyle.TITLE : TextStyle.STRONG;
            if (Math.max(TextFit.width(measurer, add, headingFace), TextFit.width(measurer, change, headingFace)) > inner) {
                problems.add("the picker's heading is wider than its panel");
            }
            if (TextFit.width(measurer, words.get(LEAVE_HINT), TextStyle.CAPTION) > inner) {
                problems.add("the picker's foot line is wider than its panel");
            }
            float headingY = panel.y() + (header - headingFace.capHeight()) / 2.0F;
            float separatorY = panel.y() + header;
            Rect view = new Rect(panel.x() + PAD, separatorY + 6, inner, Math.max(0, panel.h() - header - 12 - foot));
            if (view.h() < cardHeight) {
                problems.add("a whole card does not fit the picker");
            }
            float footY = panel.bottom() - foot + (foot - TextStyle.CAPTION.capHeight()) / 2.0F - 2;
            return new PickerLayout(width, height, panel, headingFace, headingY, separatorY, view, footY, columns, cardWidth,
                    cardHeight, descriptions, types.size(), problems);
        }

        private static float cardWidth(float inner, int columns) {
            return (float) Math.floor((inner - 6 - GAP * (columns - 1)) / columns);
        }

        Rect card(int index, int scroll) {
            int column = index % columns;
            int row = index / columns;
            return new Rect(view.x() + column * (cardWidth + GAP), view.y() + row * (cardHeight + GAP) - scroll, cardWidth,
                    cardHeight);
        }

        int scrollMax() {
            int rows = (count + columns - 1) / columns;
            return Math.max(0, (int) Math.ceil(rows * cardHeight + (rows - 1) * GAP - view.h()));
        }

        /** A notch of the wheel: a row of cards. */
        int step() {
            return Math.round(cardHeight + GAP);
        }

        int width() { return width; }
        int height() { return height; }
        Rect panel() { return panel; }
        TextStyle headingFace() { return headingFace; }
        float headingY() { return headingY; }
        float separatorY() { return separatorY; }
        Rect view() { return view; }
        float footY() { return footY; }
        int columns() { return columns; }
        float cardWidth() { return cardWidth; }
        boolean descriptions() { return descriptions; }
        List<String> problems() { return problems; }
    }

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

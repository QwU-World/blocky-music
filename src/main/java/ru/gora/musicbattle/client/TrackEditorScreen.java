package ru.gora.musicbattle.client;

import com.mojang.blaze3d.platform.NativeImage;
import ru.gora.musicbattle.client.ui.Palette;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import ru.gora.musicbattle.client.ui.RoundedRect;
import ru.gora.musicbattle.client.ui.TextField;
import ru.gora.musicbattle.client.ui.TextStyle;
import ru.gora.musicbattle.client.ui.TextStyles;
import ru.gora.musicbattle.client.ui.UiClip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;
import ru.gora.musicbattle.data.BattleNote;
import ru.gora.musicbattle.data.ChartBeat;
import ru.gora.musicbattle.data.TrackEffect;
import ru.gora.musicbattle.server.MusicBattleTracks;

public final class TrackEditorScreen extends Screen {
    /** Historical page width: changing snap precision must never zoom or repage a chart. */
    private static final int BASE_GRID_COLUMNS = 32;
    private static final int STANDARD_GRID_DIVISION = 4;
    /** A chart's clock: whole 50 ms ticks, so a beat is 1200 / BPM ticks, counted from the chart's first-beat offset. */
    private static final int TICKS_PER_MINUTE = 1200;
    private static final int PAGE_BEATS = BASE_GRID_COLUMNS / STANDARD_GRID_DIVISION;
    private static final int NOTE_LANES = 4;
    private static final int NOTE_HANDLE_OVERHANG = 3;
    /** How far from the playhead, in GUI pixels either side of its middle, a press still grabs it. */
    private static final double PLAYHEAD_GRAB_TOLERANCE = 3.5D;
    private static final int NOTE_RESIZE_GRAB_WIDTH = 10;
    /** Normal quarter grid and one FL-Studio-like half-step between every old line. */
    private static final int[] GRID_DIVISIONS = {4, 8};
    /** The grid the player last picked; a new editor starts on it, so closing and reopening keeps 1/8. */
    private static int lastGridDivisionIndex;
    private static final int FX_LANES = TrackEffect.FX_ROWS_PER_LAYER;
    /** Height of the bar that holds the title, Exit and Save; the editor scrolls beneath it. */
    private static final int TOP_BAR = 30;
    private static final int SCROLL_STEP = 24;
    private final Screen parent;
    /** Labels of the form fields, placed by init() beside the fields they name. */
    private final List<FieldLabel> fieldLabels = new ArrayList<>();
    private Button exitButton;
    private Button saveButton;
    /** How far the editor is scrolled under the top bar, in GUI pixels; non-zero only where it does not fit. */
    private int scrollY;
    private final MusicBattleTracks.Track initialTrack;
    private String savedTrackId;
    /** What the last save wrote, or what was opened; {@code null} until the first layout has filled the boxes. */
    private DocumentState savedState;
    private final List<BattleNote> notes = new ArrayList<>();
    private final List<TrackEffect> effects = new ArrayList<>();
    private EditorField idBox;
    private EditorField titleBox;
    private EditorField descriptionBox;
    private EditorField bpmBox;
    private EditorField offsetBox;
    private VolumeSlider volumeSlider;
    private EditorField audioBox;
    private EditorField previewBox;
    private EditorField importBox;
    private Button ownerButton;
    private Button playButton;
    private Button fxPlayButton;
    private Button audioSelectButton;
    private Button previewSelectButton;
    private Button fnfSelectButton;
    private Button osuDifficultyButton;
    private Button effectTypeButton;
    private Button effectModeButton;
    private Button effectDurationButton;
    private Button effectIntensityButton;
    private Button flashSoftnessButton;
    private Button stageBarsButton;
    private Button standardDimmingButton;
    private Button gridDivisionButton;
    private final List<ClientOsuManiaImporter.OsuDifficulty> osuDifficulties = new ArrayList<>();
    /** Counts chart imports, so a song finishing its conversion knows whether its notes are still the ones shown. */
    private int songImportGeneration;
    private int selectedOsuDifficulty = -1;
    private int owner;
    private int pageStartTick;
    private int gridDivisionIndex;
    private int previewTick;
    private int previewDurationTicks;
    private boolean previewPlaying;
    private SoundInstance previewSound;
    private ResourceLocation previewTexture;
    private String previewTextureFile = "";
    /** The preview box text whose picture last failed to load, so a broken image is not decoded every frame. */
    private String previewTextureFailed = "";
    private boolean initialTrackLoaded;
    private float editorVolume;
    private String status = "";
    private int statusTicks;
    private TrackEffect.Type effectType = TrackEffect.Type.FLASH;
    /** Zero-based absolute FX row shown at the top of the six-row viewport. */
    private int firstVisibleFxRow;
    private TrackEffect effectTemplate = new TrackEffect(TrackEffect.Type.FLASH, 0, 0, 0, 1.0F, 0.75F);
    private EffectPlacementMode effectMode = EffectPlacementMode.POINT;
    private int effectDurationIndex = 2;
    private int effectIntensityIndex = 1;
    private int flashSoftnessIndex = 3;
    private int pendingEffectStartTick = -1;
    private int pendingEffectLane = -1;
    private EditorPage editorPage = EditorPage.NOTES;
    private String draftId;
    private String draftTitle;
    private String draftDescription;
    private String draftBpm;
    private String draftOffset;
    private String draftAudio;
    private String draftPreview;
    private String draftImport;
    private boolean disableStageBars;
    private boolean hideStandardDimming;
    private static final int[] EFFECT_DURATION_STEPS = {1, 2, 4, 8, 16, 32};
    private static final float[] EFFECT_INTENSITIES = {0.5F, 1.0F, 1.5F, 2.0F};
    private static final float[] FLASH_SOFTNESS = {0.0F, 0.25F, 0.5F, 0.75F, 1.0F};
    private static final long EFFECT_TOOLTIP_DELAY_NANOS = 3_000_000_000L;
    private long previewStartedAtNanos;
    private volatile long previewAudioStartedSignalNanos;
    private int previewStartedAtTick;
    private float lastPreviewEffectTick;
    private List<BattleNote> previewNoteQueue = List.of();
    private int previewNoteCursor;
    private final long[] previewLaneHitNanos = new long[NOTE_LANES];
    private final int[] previewLaneHitOwner = new int[NOTE_LANES];
    private final int[] previewLaneHitTick = new int[NOTE_LANES];
    private int timelineRevision;
    private int cachedTimelineRevision = -1;
    private int cachedPageStartTick = -1;
    private int cachedPageTicks = -1;
    private int cachedFirstVisibleFxRow = -1;
    private List<BattleNote> cachedPageNotes = List.of();
    private List<TrackEffect> cachedPageEffects = List.of();
    private final Set<BattleNote> selectedNotes = new HashSet<>();
    private final Set<TrackEffect> selectedEffects = new HashSet<>();
    private final Deque<EditorSnapshot> undoHistory = new ArrayDeque<>();
    private final Deque<EditorSnapshot> redoHistory = new ArrayDeque<>();
    private ClipboardData clipboard = new ClipboardData(List.of(), List.of(), 0);
    private int draggedEffectIndex = -1;
    private DragMode effectDragMode = DragMode.MOVE;
    private int dragGrabOffsetTicks;
    private int dragFixedTick;
    private double dragStartedMouseX;
    private double dragStartedMouseY;
    private boolean effectWasDragged;
    private boolean dragPageTurnArmed = true;
    private boolean dragHistoryCaptured;
    private BattleNote draggedNote;
    private DragMode noteDragMode = DragMode.MOVE;
    private int noteDragGrabOffsetTicks;
    private int noteDragFixedTick;
    private double noteDragStartedMouseX;
    private double noteDragStartedMouseY;
    private boolean noteWasDragged;
    private boolean pendingNotePlacement;
    private double pendingNoteMouseX;
    private double pendingNoteMouseY;
    private int pendingNoteButton;
    private boolean timelineScrubbing;
    private boolean resumePreviewAfterScrub;
    /** A left press on the playhead: a drag moves the play position, a still release is an ordinary grid click. */
    private boolean playheadPressPending;
    private double playheadPressX;
    private double playheadPressY;
    private boolean playheadDragging;
    /** The hand cursor this screen set itself; HandCursor only serves Workshop layers and resets it every frame. */
    private static long playheadHandCursor;
    private boolean playheadCursorShown;
    private TrackEffect effectTooltipHoverEffect;
    private int effectTooltipHoverIndex = -1;
    private double effectTooltipMouseX = Double.NaN;
    private double effectTooltipMouseY = Double.NaN;
    private long effectTooltipStillSinceNanos;

    public TrackEditorScreen(Screen parent) {
        this(parent, null);
    }

    public TrackEditorScreen(Screen parent, MusicBattleTracks.Track initialTrack) {
        super(Component.translatable("music_battle.editor"));
        this.parent = parent;
        this.initialTrack = initialTrack;
        this.savedTrackId = initialTrack == null ? "" : initialTrack.id();
        this.editorVolume = initialTrack == null ? 1.0F : initialTrack.volume();
        this.disableStageBars = initialTrack != null && initialTrack.disableStageBars();
        this.hideStandardDimming = initialTrack != null && initialTrack.hideStandardDimming();
        this.effectType = ClientEffectToolSettings.lastType();
        this.effectTemplate = ClientEffectToolSettings.preset(effectType);
        this.effectMode = parseEffectMode(ClientEffectToolSettings.placementMode(effectType));
        this.gridDivisionIndex = lastGridDivisionIndex;
    }

    @Override
    protected void init() {
        // init() also runs on a window resize and on every page scroll; keep what the player has typed.
        snapshotDraft();
        fieldLabels.clear();
        scrollY = Mth.clamp(scrollY, 0, maxScroll());
        EditorLayout layout = layout();
        String audioValue = draftAudio != null ? draftAudio : defaultAssetPath(initialTrack == null ? "" : initialTrack.audioFile());
        String previewValue = draftPreview != null ? draftPreview : defaultAssetPath(initialTrack == null ? "" : initialTrack.previewFile());
        String importValue = draftImport == null ? "" : draftImport;

        FormSlots slots = formSlots(layout);
        if (slots != null) {
            // The wide form names the ID by what voters see; the narrow one has half a row for it.
            String idLabel = layout.twoColumns ? "music_battle.l10n.editor.text_025" : "music_battle.l10n.editor.text_032";
            String descriptionLabel = layout.twoColumns ? "music_battle.l10n.editor.text_027" : "music_battle.l10n.editor.text_033";
            idBox = addBox(slots.id(), Component.translatable(idLabel), "music_battle.l10n.editor.hint_track_id", defaultId());
            titleBox = addBox(slots.title(), Component.translatable("music_battle.l10n.editor.text_026"), "music_battle.l10n.editor.hint_title", defaultTitle());
            descriptionBox = addBox(slots.description(), Component.translatable(descriptionLabel), "music_battle.l10n.editor.hint_description", defaultDescription());
            bpmBox = addBox(slots.bpm(), Component.literal("BPM"), "music_battle.l10n.editor.hint_bpm", defaultBpm());
            offsetBox = addBox(slots.offset(), Component.translatable("music_battle.l10n.editor.offset"), "music_battle.l10n.editor.hint_offset", defaultOffset());
            offsetBox.setTooltip(Tooltip.create(Component.translatable("music_battle.l10n.editor.offset_tooltip")));
            FieldSlot beatOne = slots.beatOne();
            Button beatOneButton = this.addRenderableWidget(FancyButton.create(Component.translatable("music_battle.l10n.editor.beat_one"),
                    button -> setBeatOneAtPlayhead(), beatOne.x(), beatOne.y(), beatOne.width(), 18));
            beatOneButton.setTooltip(Tooltip.create(Component.translatable("music_battle.l10n.editor.beat_one_tooltip")));
            volumeSlider = this.addRenderableWidget(new VolumeSlider(slots.volume().x(), slots.volume().y(), slots.volume().width(), 18));
            audioBox = addBox(slots.audio(), Component.translatable("music_battle.l10n.editor.text_029"), "music_battle.l10n.editor.hint_audio", audioValue);
            previewBox = addBox(slots.preview(), Component.translatable("music_battle.l10n.editor.text_030"), "music_battle.l10n.editor.hint_preview", previewValue);
            importBox = addBox(slots.chart(), Component.translatable("music_battle.l10n.editor.text_031"), "music_battle.l10n.editor.hint_chart", importValue);
        }

        loadInitialTrackNotes();
        if (savedState == null && idBox != null) {
            savedState = currentState();
        }

        addControlButtons(layout);
        updateFlashSoftnessVisibility();
        // Anything scrolled up under the top bar is hidden, so a click on the bar never reaches a control beneath it.
        for (GuiEventListener child : this.children()) {
            if (child instanceof AbstractWidget widget && (widget.getY() < TOP_BAR || widget.getY() >= this.height)) {
                widget.visible = false;
            }
        }

        int topButtonWidth = Mth.clamp(this.width / 7, 64, 104);
        int topButtonY = (TOP_BAR - 18) / 2;
        exitButton = this.addRenderableWidget(FancyButton.create(Component.translatable("music_battle.l10n.editor.button_exit"), button -> this.onClose(), 8, topButtonY, topButtonWidth, 18));
        saveButton = this.addRenderableWidget(FancyButton.create(Component.translatable("music_battle.l10n.editor.button_save"), button -> saveTrack(), this.width - topButtonWidth - 8, topButtonY, topButtonWidth, 18));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        alignPageToBpm();
        float renderTick = previewPlaying ? currentPreviewTick() : previewTick;
        if (previewPlaying && previewAudioStartedSignalNanos > 0L) {
            triggerPreviewNoteEffects(renderTick);
        }
        ClientTrackEffects.EffectState effectState = previewPlaying
                ? ClientTrackEffects.sample(effects, renderTick, bpm())
                : ClientTrackEffects.sample(List.of(), -1.0F);
        graphics.pose().pushPose();
        try {
            graphics.pose().translate(ClientTrackEffects.shakePixelsX(effectState), ClientTrackEffects.shakePixelsY(effectState), 0.0F);
            drawEditorBackground(graphics);
            if (previewPlaying) {
                ClientTrackEffects.renderOverlay(graphics, effectState);
            }
            // overlayInterface describes the gameplay HUD, never the editor's controls.
            ClientUiForeground.render(graphics.pose(), graphics::flush, () -> {
                // Everything below the top bar scrolls, so it is clipped to the area under the bar.
                UiClip.push(graphics, 0, TOP_BAR, this.width, this.height);
                drawPanels(graphics);
                drawLabels(graphics);
                if (editorPage == EditorPage.NOTES) {
                    drawPreviewImage(graphics);
                }
                drawGrid(graphics);
                if (editorPage == EditorPage.NOTES) {
                    renderHoveredNoteHandles(graphics, mouseX, mouseY);
                }
                int contentMouseY = mouseY < TOP_BAR ? -1 : mouseY;
                for (Renderable renderable : this.renderables) {
                    if (renderable != exitButton && renderable != saveButton) {
                        renderable.render(graphics, mouseX, contentMouseY, partialTick);
                    }
                }
                UiClip.pop(graphics);
                drawTopBar(graphics, mouseX, mouseY, partialTick);
                drawScrollbar(graphics);
                drawStatus(graphics);
            });
        } finally {
            graphics.pose().popPose();
        }
        if (editorPage == EditorPage.FX) {
            ClientUiForeground.render(graphics.pose(), graphics::flush,
                    () -> renderEffectTooltip(graphics, mouseX, mouseY));
        }
        // The Leave dialog renders this screen behind itself; only the screen on top may change the cursor.
        if (this.minecraft != null && this.minecraft.screen == this) {
            showPlayheadCursor(playheadDragging || (draggedNote == null && draggedEffectIndex < 0 && !timelineScrubbing
                    && overPlayhead(mouseX, mouseY) && (editorPage == EditorPage.FX
                            ? hoveredEffectInfo(mouseX, mouseY) == null : hoveredNoteInfo(mouseX, mouseY) == null)));
        }
    }

    @Override
    public void removed() {
        showPlayheadCursor(false);
        super.removed();
    }

    @Override
    public void tick() {
        super.tick();
        if (statusTicks > 0) {
            statusTicks--;
            if (statusTicks == 0) {
                status = "";
            }
        }
        if (!previewPlaying) {
            return;
        }

        refreshPreviewVolume();
        if (previewStartedAtNanos <= 0L) {
            long audioSignal = previewAudioStartedSignalNanos;
            if (audioSignal <= 0L) {
                return;
            }
            previewStartedAtNanos = audioSignal;
            setStatus("OK: " + I18n.get("music_battle.l10n.editor.audio_started", previewStartedAtTick));
        }

        float exactTick = currentPreviewTick();
        triggerPreviewNoteEffects(exactTick);
        previewTick = Math.max(0, Mth.floor(exactTick));
        if (previewDurationTicks > 0 && previewTick >= previewDurationTicks
                + Math.max(0, ClientBattleSettings.audioOffsetMs()) / 50.0F) {
            stopPreview(false);
            return;
        }

        int end = pageStartTick + pageTicks();
        if (draggedEffectIndex < 0 && draggedNote == null
                && (previewTick < pageStartTick || previewTick >= end)) {
            pageStartTick = pageStartForTick(previewTick);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        resetEffectTooltipDwell();
        // A click anywhere else ends typing, so the grid's keys (Delete, arrows, Ctrl+Z) work right after it.
        if (this.getFocused() instanceof EditorField field && !field.isMouseOver(mouseX, mouseY)) {
            this.setFocused(null);
        }
        // The grid can scroll up under the top bar; there only the bar's own buttons may answer.
        if (mouseY < TOP_BAR) {
            return super.mouseClicked(mouseX, mouseY, button);
        }
        if (button == 0 && insideTimelineScrubber(mouseX, mouseY)) {
            beginTimelineScrub(mouseX);
            return true;
        }
        if (button == 0 && hasControlDown()) {
            if (editorPage == EditorPage.FX) {
                HoveredEffect hovered = hoveredEffectInfo(mouseX, mouseY);
                if (hovered != null) {
                    toggleSelection(selectedEffects, hovered.effect());
                    return true;
                }
            } else {
                HoveredNote hovered = hoveredNoteInfo(mouseX, mouseY);
                if (hovered != null) {
                    toggleSelection(selectedNotes, hovered.note());
                    return true;
                }
            }
        }
        if (editorPage == EditorPage.FX && button == 0) {
            HoveredEffect hovered = hoveredEffectInfo(mouseX, mouseY);
            if (hovered != null) {
                beginEffectDrag(hovered, mouseX, mouseY);
                return true;
            }
        }
        if (editorPage == EditorPage.NOTES && button == 0) {
            HoveredNote hovered = hoveredNoteInfo(mouseX, mouseY);
            if (hovered != null) {
                if (!selectedNotes.contains(hovered.note())) {
                    selectedNotes.clear();
                    selectedEffects.clear();
                    selectedNotes.add(hovered.note());
                }
                beginNoteSelectionDrag(hovered, mouseX, mouseY);
                return true;
            }
        }
        // Notes and effects were checked above and win over the line lying across them, as the playhead is drawn
        // beneath them; what is left near the line is empty grid, where only movement tells a drag from a click.
        if (button == 0 && overPlayhead(mouseX, mouseY)) {
            pendingNotePlacement = false;
            playheadPressPending = true;
            playheadPressX = mouseX;
            playheadPressY = mouseY;
            return true;
        }
        if (editorPage == EditorPage.NOTES && insideGrid(mouseX, mouseY)) {
            pendingNotePlacement = true;
            pendingNoteMouseX = mouseX;
            pendingNoteMouseY = mouseY;
            pendingNoteButton = button;
            return true;
        }
        if (insideGrid(mouseX, mouseY)) {
            pushUndoSnapshot();
        }
        if (toggleGridNote(mouseX, mouseY, button)) {
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        resetEffectTooltipDwell();
        Grid grid = grid();
        boolean overFxRows = editorPage == EditorPage.FX
                && mouseX >= grid.x - 52 && mouseX < grid.x + grid.width
                && mouseY >= grid.y && mouseY < grid.y + grid.height;
        if (!overFxRows || scrollY == 0.0D || draggedEffectIndex >= 0) {
            return scrollPage(scrollY) || super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        }
        int direction = scrollY > 0.0D ? -1 : 1;
        int maximumFirstRow = TrackEffect.MAX_FX_ROWS - FX_LANES;
        int next = Mth.clamp(firstVisibleFxRow + direction, 0, maximumFirstRow);
        if (next == firstVisibleFxRow) {
            return true;
        }
        firstVisibleFxRow = next;
        selectedEffects.removeIf(effect -> !isFxRowVisible(effect));
        pendingEffectStartTick = -1;
        pendingEffectLane = -1;
        cachedFirstVisibleFxRow = -1;
        setStatus(I18n.get("music_battle.l10n.editor.visible_rows", firstVisibleFxRow + 1, firstVisibleFxRow + FX_LANES));
        return true;
    }

    /** Scrolls the whole editor on a window too short for it; widgets are rebuilt at their new places. */
    private boolean scrollPage(double wheel) {
        int max = maxScroll();
        if (max <= 0 || wheel == 0.0D || timelineScrubbing || playheadDragging || playheadPressPending
                || draggedNote != null || draggedEffectIndex >= 0) {
            return false;
        }
        int next = Mth.clamp(this.scrollY - (int) Math.round(wheel * SCROLL_STEP), 0, max);
        if (next != this.scrollY) {
            this.scrollY = next;
            pendingNotePlacement = false;
            rebuildWidgets();
        }
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (this.getFocused() instanceof EditorField field && field.isFocused()) {
            return super.keyPressed(keyCode, scanCode, modifiers);
        }
        if (hasControlDown() && keyCode == GLFW.GLFW_KEY_Z) {
            if (hasShiftDown()) {
                redo();
            } else {
                undo();
            }
            return true;
        }
        if (hasControlDown() && keyCode == GLFW.GLFW_KEY_C) {
            copySelection();
            return true;
        }
        if (hasControlDown() && keyCode == GLFW.GLFW_KEY_V) {
            pasteSelection();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_DELETE) {
            deleteSelection();
            return true;
        }
        if (!selectedNotes.isEmpty() || !selectedEffects.isEmpty()) {
            if (keyCode == GLFW.GLFW_KEY_LEFT || keyCode == GLFW.GLFW_KEY_RIGHT || keyCode == GLFW.GLFW_KEY_UP || keyCode == GLFW.GLFW_KEY_DOWN) {
                // The earliest selected item steps to the neighbouring line and the rest keep their distance to it; a
                // fixed step would walk off a fine grid whose cells are not all the same width.
                int anchor = Math.min(selectedNotes.stream().mapToInt(BattleNote::tick).min().orElse(Integer.MAX_VALUE),
                        selectedEffects.stream().filter(this::isFxRowVisible).mapToInt(TrackEffect::tick).min().orElse(Integer.MAX_VALUE));
                int tickDelta = anchor == Integer.MAX_VALUE ? 0
                        : keyCode == GLFW.GLFW_KEY_LEFT ? gridTickBefore(anchor) - anchor
                        : keyCode == GLFW.GLFW_KEY_RIGHT ? gridTickAfter(anchor) - anchor : 0;
                int laneDelta = keyCode == GLFW.GLFW_KEY_UP ? -1 : keyCode == GLFW.GLFW_KEY_DOWN ? 1 : 0;
                moveSelection(tickDelta, laneDelta);
                return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        resetEffectTooltipDwell();
        if (button == 0 && timelineScrubbing) {
            updateTimelineScrub(mouseX);
            return true;
        }
        if (button == 0 && playheadPressPending
                && Math.hypot(mouseX - playheadPressX, mouseY - playheadPressY) >= 3.0D) {
            playheadPressPending = false;
            beginPlayheadDrag();
        }
        if (button == 0 && playheadDragging) {
            updatePlayheadDrag(mouseX);
            return true;
        }
        if (button == 0 && playheadPressPending) {
            return true;
        }
        if (pendingNotePlacement && Math.hypot(mouseX - pendingNoteMouseX, mouseY - pendingNoteMouseY) >= 3.0D) {
            pendingNotePlacement = false;
            setStatus(I18n.get("music_battle.l10n.editor.text_001"));
            return true;
        }
        if (button == 0 && draggedNote != null) {
            updateNoteSelectionDrag(mouseX, mouseY);
            return true;
        }
        if (button == 0 && draggedEffectIndex >= 0) {
            updateEffectDrag(mouseX, mouseY);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        resetEffectTooltipDwell();
        if (button == 0 && timelineScrubbing) {
            finishTimelineScrub(mouseX);
            return true;
        }
        if (button == 0 && playheadDragging) {
            finishPlayheadDrag();
            return true;
        }
        if (button == 0 && playheadPressPending) {
            // It never moved: the same click the grid gets anywhere else, a note or an effect toggled under it.
            playheadPressPending = false;
            if (insideGrid(playheadPressX, playheadPressY)) {
                pushUndoSnapshot();
            }
            toggleGridNote(playheadPressX, playheadPressY, 0);
            return true;
        }
        if (pendingNotePlacement && button == pendingNoteButton) {
            boolean place = Math.hypot(mouseX - pendingNoteMouseX, mouseY - pendingNoteMouseY) < 3.0D;
            double clickX = pendingNoteMouseX;
            double clickY = pendingNoteMouseY;
            pendingNotePlacement = false;
            if (place) {
                pushUndoSnapshot();
                toggleGridNote(clickX, clickY, button);
            }
            return true;
        }
        if (button == 0 && draggedNote != null) {
            clearNoteSelectionDrag();
            return true;
        }
        if (button == 0 && draggedEffectIndex >= 0) {
            int index = draggedEffectIndex;
            boolean openEditor = !effectWasDragged;
            clearEffectDrag();
            if (openEditor && index >= 0 && index < effects.size()) {
                this.minecraft.setScreen(new TrackEffectEditScreen(this, effects.get(index), effects.get(index), "concrete"));
            }
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public void mouseMoved(double mouseX, double mouseY) {
        resetEffectTooltipDwell();
        super.mouseMoved(mouseX, mouseY);
    }

    /**
     * Exit and Escape both come here. Unsaved work is asked about first, the way the Workshop asks about typed text:
     * leaving used to drop a whole chart without a word, and a player who has just imported a map and pressed Escape
     * to get out of a text box is exactly who loses it.
     */
    @Override
    public void onClose() {
        if (hasUnsavedChanges()) {
            snapshotDraft();
            stopPreview(false);
            this.minecraft.setScreen(new LeaveDialog(this));
            return;
        }
        closeWithoutSaving();
    }

    /** Save, and leave only if the save went through; a failed save keeps the editor open with its error. */
    private void saveAndLeave() {
        saveTrack();
        if (hasUnsavedChanges()) {
            this.minecraft.setScreen(this);
            return;
        }
        closeWithoutSaving();
    }

    /**
     * The Workshop's dialog, drawn over the dimmed editor rather than a blank page so the player can see what they
     * are about to lose, and with a way out that keeps it: Save and leave.
     */
    private static final class LeaveDialog extends ru.gora.musicbattle.client.workshop.WorkshopDialogScreen {
        private final TrackEditorScreen editor;

        private LeaveDialog(TrackEditorScreen editor) {
            super(editor, Component.translatable("music_battle.l10n.editor.discard_title"),
                    ru.gora.musicbattle.client.ui.UiIcon.ALERT, Palette.WARN);
            this.editor = editor;
            addParagraph(Component.translatable("music_battle.l10n.editor.discard_body"));
            Runnable cancel = this::onClose;
            addAction(net.minecraft.network.chat.CommonComponents.GUI_CANCEL,
                    ru.gora.musicbattle.client.workshop.WorkshopButton.Kind.SECONDARY, null, cancel);
            addAction(Component.translatable("music_battle.l10n.editor.discard_confirm"),
                    ru.gora.musicbattle.client.workshop.WorkshopButton.Kind.DANGER,
                    ru.gora.musicbattle.client.ui.UiIcon.TRASH, editor::closeWithoutSaving);
            addAction(Component.translatable("music_battle.l10n.editor.discard_save"),
                    ru.gora.musicbattle.client.workshop.WorkshopButton.Kind.PRIMARY,
                    ru.gora.musicbattle.client.ui.UiIcon.CHECK, editor::saveAndLeave);
            setCancelAction(cancel);
        }

        @Override
        public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            if (editor.width != this.width || editor.height != this.height) {
                editor.init(this.minecraft, this.width, this.height);
            }
            editor.render(graphics, -1, -1, partialTick);
            graphics.flush();
            // The editor draws its panels on a raised layer (ClientUiForeground, z 800), and depth testing kept them
            // in front of anything drawn later at the screen's own depth - this dialog included, which is why the
            // warning showed up behind the editor. Clearing depth puts the whole editor behind the dialog.
            com.mojang.blaze3d.systems.RenderSystem.clear(org.lwjgl.opengl.GL11.GL_DEPTH_BUFFER_BIT,
                    net.minecraft.client.Minecraft.ON_OSX);
            graphics.fill(0, 0, this.width, this.height, 0xA0080610);
        }
    }

    private void closeWithoutSaving() {
        stopPreview(true);
        releasePreviewTexture();
        this.minecraft.setScreen(parent);
    }

    /** Whether anything a save would write differs from the last save, or from what was opened. */
    private boolean hasUnsavedChanges() {
        return savedState != null && !savedState.equals(currentState());
    }

    /** Everything Save writes, so two states compare equal exactly when saving would change nothing. */
    private DocumentState currentState() {
        snapshotDraft();
        return new DocumentState(List.copyOf(notes), List.copyOf(effects),
                List.of(clean(draftId), clean(draftTitle), clean(draftDescription), clean(draftBpm), Integer.toString(offsetMs()),
                        ClientTrackExporter.cleanPathInput(draftAudio), ClientTrackExporter.cleanPathInput(draftPreview)),
                volume(), disableStageBars, hideStandardDimming);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private EditorField addBox(FieldSlot slot, Component label, String hintKey, String value) {
        return addBox(slot.x(), slot.y(), slot.width(), label, hintKey, value);
    }

    private EditorField addBox(int x, int y, int width, Component label, String hintKey, String value) {
        EditorField box = new EditorField(x, y, width, 18, label, Component.translatable(hintKey));
        box.setValue(value);
        fieldLabels.add(new FieldLabel(label, x, y, width));
        return this.addRenderableWidget(box);
    }

    private static void setBoxValueAtStart(EditorField box, String value) {
        box.setValue(value);
    }

    private void loadInitialTrackNotes() {
        if (initialTrack == null || initialTrackLoaded) {
            return;
        }
        initialTrackLoaded = true;
        notes.clear();
        notes.addAll(initialTrack.notes());
        effects.clear();
        effects.addAll(initialTrack.effects());
        invalidateTimelineCache();
        previewDurationTicks = initialTrack.durationTicks();
        int onDisk = notesOnDisk(initialTrack);
        if (onDisk > notes.size()) {
            // The loader keeps the first MAX_NOTES notes of a chart, so the end of this one is not here, and saving
            // would make that final. Before, nothing said so.
            setStatus("WARN: " + I18n.get("music_battle.l10n.editor.opened_truncated", onDisk, notes.size()));
            return;
        }
        setStatus("OK: " + I18n.get("music_battle.l10n.editor.opened", initialTrack.title()));
    }

    /** How many notes the chart file itself lists, which is more than were loaded only for an over-long chart. */
    private static int notesOnDisk(MusicBattleTracks.Track track) {
        if (track.notes().size() < ClientTrackExporter.MAX_NOTES) {
            return track.notes().size();
        }
        try {
            Path definition = MusicBattleTracks.definitionPath(track).orElse(null);
            if (definition == null) {
                return track.notes().size();
            }
            com.google.gson.JsonElement root = com.google.gson.JsonParser.parseString(Files.readString(definition));
            com.google.gson.JsonElement listed = root.isJsonObject() ? root.getAsJsonObject().get("notes") : null;
            return listed != null && listed.isJsonArray() ? listed.getAsJsonArray().size() : track.notes().size();
        } catch (IOException | RuntimeException exception) {
            return track.notes().size();
        }
    }

    private String defaultId() {
        return draftId != null ? draftId : initialTrack == null ? "my_song" : initialTrack.id();
    }

    private String defaultTitle() {
        return draftTitle != null ? draftTitle : initialTrack == null ? I18n.get("music_battle.l10n.editor.default_title") : initialTrack.title();
    }

    private String defaultDescription() {
        return draftDescription != null ? draftDescription : initialTrack == null ? I18n.get("music_battle.l10n.editor.default_description") : initialTrack.description();
    }

    private String defaultOffset() {
        return draftOffset != null ? draftOffset : Integer.toString(initialTrack == null ? 0 : initialTrack.offsetMs());
    }

    /**
     * Puts beat 1 where the playhead stands, so the grid's bar lines fall on the music from there; the offset is
     * folded back by whole bars because the grid also runs on backwards from beat 1.
     */
    private void setBeatOneAtPlayhead() {
        float tick = previewPlaying ? currentPreviewTick() : previewTick;
        int offset = ChartBeat.offsetForBeatAt(tick * (double) ChartBeat.MS_PER_TICK, bpm());
        if (offsetBox != null) {
            offsetBox.setValue(Integer.toString(offset));
        }
        draftOffset = Integer.toString(offset);
        alignPageToBpm();
        invalidateTimelineCache();
    }

    private String defaultBpm() {
        return draftBpm != null ? draftBpm : Integer.toString(initialTrack == null ? 120 : initialTrack.bpm());
    }

    private String defaultAssetPath(String fileName) {
        return fileName == null ? "" : fileName;
    }

    private void addControlButtons(EditorLayout layout) {
        int left = layout.left;
        int y = layout.controlsY;
        int buttonHeight = layout.buttonHeight;
        int rowStep = layout.rowStep;
        if (layout.wideControls) {
            int gap = layout.compactHeight ? 6 : 10;
            int col = Math.max(64, (layout.panelWidth - gap * 5) / 6);
            int[] x = new int[6];
            for (int i = 0; i < x.length; i++) {
                x[i] = left + i * (col + gap);
            }
            if (editorPage == EditorPage.NOTES) {
                addOwnerButton(x[0], y, col, buttonHeight);
                addTimelinePageButton(x[1], y, col, buttonHeight, -1);
                addTimelinePageButton(x[2], y, col, buttonHeight, 1);
                audioSelectButton = this.addRenderableWidget(FancyButton.create(Component.translatable("music_battle.l10n.editor.button_audio"), button -> chooseAudio(audioBox), x[3], y, col, buttonHeight));
                previewSelectButton = this.addRenderableWidget(FancyButton.create(Component.translatable("music_battle.l10n.editor.button_image"), button -> choosePreview(previewBox), x[4], y, col, buttonHeight));
                fnfSelectButton = this.addRenderableWidget(FancyButton.create(Component.translatable("music_battle.l10n.editor.button_chart"), button -> chooseChart(importBox), x[5], y, col, buttonHeight));
                y += rowStep;
                this.addRenderableWidget(FancyButton.create(Component.translatable("music_battle.l10n.editor.button_load"), button -> loadPreview(), x[0], y, col, buttonHeight));
                playButton = this.addRenderableWidget(FancyButton.create(Component.translatable(previewPlaying ? "music_battle.l10n.editor.button_stop" : "music_battle.l10n.editor.button_play"), button -> togglePreview(), x[1], y, col, buttonHeight));
                osuDifficultyButton = this.addRenderableWidget(FancyButton.create(osuDifficultyLabel(), button -> cycleOsuDifficulty(), x[2], y, col, buttonHeight));
                this.addRenderableWidget(FancyButton.create(Component.translatable("music_battle.l10n.editor.button_import"), button -> importChart(), x[3], y, col, buttonHeight));
                this.addRenderableWidget(FancyButton.create(Component.translatable("music_battle.l10n.editor.button_game_preview"), button -> startGameplayPreview(), x[4], y, col, buttonHeight));
                this.addRenderableWidget(FancyButton.create(Component.literal("P1 ↔ P2"), button -> convertSelectedNoteOwners(), x[5], y, col, buttonHeight));
                y += rowStep;
                this.addRenderableWidget(FancyButton.create(Component.translatable("music_battle.l10n.editor.text_002"), button -> switchEditorPage(EditorPage.FX), x[0], y, col * 2 + gap, buttonHeight));
                gridDivisionButton = this.addRenderableWidget(createGridDivisionButton(x[2], y, col * 2 + gap, buttonHeight));
                this.addRenderableWidget(FancyButton.create(Component.translatable("music_battle.l10n.editor.text_003"), button -> clearSelection(), x[4], y, col * 2 + gap, buttonHeight));
            } else {
                this.addRenderableWidget(FancyButton.create(Component.translatable("music_battle.l10n.editor.text_004"), button -> switchEditorPage(EditorPage.NOTES), x[0], y, col, buttonHeight));
                addTimelinePageButton(x[1], y, col, buttonHeight, -1);
                addTimelinePageButton(x[2], y, col, buttonHeight, 1);
                this.addRenderableWidget(FancyButton.create(Component.translatable("music_battle.l10n.editor.button_load"), button -> loadPreview(), x[3], y, col, buttonHeight));
                fxPlayButton = this.addRenderableWidget(FancyButton.create(Component.translatable(previewPlaying ? "music_battle.l10n.editor.button_stop" : "music_battle.l10n.editor.button_play"), button -> togglePreview(), x[4], y, col, buttonHeight));
                effectModeButton = this.addRenderableWidget(FancyButton.create(effectModeLabel(), button -> cycleEffectMode(), x[5], y, col, buttonHeight));
                y += rowStep;
                this.addRenderableWidget(FancyButton.create(Component.translatable("music_battle.l10n.editor.text_005"), button -> openEffectTemplateEditor(), x[0], y, col * 4 + gap * 3, buttonHeight));
                this.addRenderableWidget(FancyButton.create(Component.translatable("music_battle.l10n.editor.button_game_preview"), button -> startGameplayPreview(), x[4], y, col * 2 + gap, buttonHeight));
                y += rowStep;
                stageBarsButton = this.addRenderableWidget(FancyButton.create(stageBarsLabel(), button -> toggleStageBars(), x[0], y, col * 2 + gap, buttonHeight));
                standardDimmingButton = this.addRenderableWidget(FancyButton.create(standardDimmingLabel(), button -> toggleStandardDimming(), x[2], y, col * 2 + gap, buttonHeight));
                gridDivisionButton = this.addRenderableWidget(createGridDivisionButton(x[4], y, col, buttonHeight));
                this.addRenderableWidget(FancyButton.create(Component.translatable("music_battle.l10n.editor.text_006"), button -> clearSelection(), x[5], y, col, buttonHeight));
            }
            return;
        }

        int gap = 6;
        int third = Math.max(48, (layout.panelWidth - gap * 2) / 3);
        if (editorPage == EditorPage.NOTES) {
            // File work first, under the fields it fills; play and paging last, right above the grid, so a scrolled
            // small window shows the transport and the notes together.
            int wide = third * 2 + gap;
            audioSelectButton = this.addRenderableWidget(FancyButton.create(Component.translatable("music_battle.l10n.editor.button_audio"), button -> chooseAudio(audioBox), left, y, third, buttonHeight));
            previewSelectButton = this.addRenderableWidget(FancyButton.create(Component.translatable("music_battle.l10n.editor.button_image"), button -> choosePreview(previewBox), left + third + gap, y, third, buttonHeight));
            fnfSelectButton = this.addRenderableWidget(FancyButton.create(Component.translatable("music_battle.l10n.editor.button_chart"), button -> chooseChart(importBox), left + (third + gap) * 2, y, third, buttonHeight));
            y += rowStep;
            this.addRenderableWidget(FancyButton.create(Component.translatable("music_battle.l10n.editor.button_load"), button -> loadPreview(), left, y, third, buttonHeight));
            this.addRenderableWidget(FancyButton.create(Component.translatable("music_battle.l10n.editor.button_import"), button -> importChart(), left + third + gap, y, third, buttonHeight));
            this.addRenderableWidget(FancyButton.create(Component.translatable("music_battle.l10n.editor.button_game_preview"), button -> startGameplayPreview(), left + (third + gap) * 2, y, third, buttonHeight));
            y += rowStep;
            osuDifficultyButton = this.addRenderableWidget(FancyButton.create(osuDifficultyLabel(), button -> cycleOsuDifficulty(), left, y, wide, buttonHeight));
            this.addRenderableWidget(FancyButton.create(Component.literal("P1 ↔ P2"), button -> convertSelectedNoteOwners(), left + wide + gap, y, third, buttonHeight));
            y += rowStep;
            this.addRenderableWidget(FancyButton.create(Component.translatable("music_battle.l10n.editor.text_007"), button -> switchEditorPage(EditorPage.FX), left, y, wide, buttonHeight));
            gridDivisionButton = this.addRenderableWidget(createGridDivisionButton(left + wide + gap, y, third, buttonHeight));
            y += rowStep;
            addOwnerButton(left, y, third, buttonHeight);
            this.addRenderableWidget(FancyButton.create(Component.translatable("music_battle.l10n.editor.text_003"), button -> clearSelection(), left + third + gap, y, wide, buttonHeight));
            y += rowStep;
            addTimelinePageButton(left, y, third, buttonHeight, -1);
            playButton = this.addRenderableWidget(FancyButton.create(Component.translatable(previewPlaying ? "music_battle.l10n.editor.button_stop" : "music_battle.l10n.editor.button_play"), button -> togglePreview(), left + third + gap, y, third, buttonHeight));
            addTimelinePageButton(left + (third + gap) * 2, y, third, buttonHeight, 1);
        } else {
            this.addRenderableWidget(FancyButton.create(Component.translatable("music_battle.l10n.editor.text_004"), button -> switchEditorPage(EditorPage.NOTES), left, y, third, buttonHeight));
            addTimelinePageButton(left + third + gap, y, third, buttonHeight, -1);
            addTimelinePageButton(left + (third + gap) * 2, y, third, buttonHeight, 1);
            y += rowStep;
            this.addRenderableWidget(FancyButton.create(Component.translatable("music_battle.l10n.editor.button_load"), button -> loadPreview(), left, y, third, buttonHeight));
            fxPlayButton = this.addRenderableWidget(FancyButton.create(Component.translatable(previewPlaying ? "music_battle.l10n.editor.button_stop" : "music_battle.l10n.editor.button_play"), button -> togglePreview(), left + third + gap, y, third, buttonHeight));
            effectModeButton = this.addRenderableWidget(FancyButton.create(effectModeLabel(), button -> cycleEffectMode(), left + (third + gap) * 2, y, third, buttonHeight));
            y += rowStep;
            this.addRenderableWidget(FancyButton.create(Component.translatable("music_battle.l10n.editor.text_005"), button -> openEffectTemplateEditor(), left, y, third, buttonHeight));
            this.addRenderableWidget(FancyButton.create(Component.translatable("music_battle.l10n.editor.button_game_preview"), button -> startGameplayPreview(), left + third + gap, y, third * 2 + gap, buttonHeight));
            y += rowStep;
            stageBarsButton = this.addRenderableWidget(FancyButton.create(stageBarsLabel(), button -> toggleStageBars(), left, y, third, buttonHeight));
            standardDimmingButton = this.addRenderableWidget(FancyButton.create(standardDimmingLabel(), button -> toggleStandardDimming(), left + third + gap, y, third * 2 + gap, buttonHeight));
            y += rowStep;
            gridDivisionButton = this.addRenderableWidget(createGridDivisionButton(left, y, third, buttonHeight));
            this.addRenderableWidget(FancyButton.create(Component.translatable("music_battle.l10n.editor.text_003"), button -> clearSelection(), left + third + gap, y, third * 2 + gap, buttonHeight));
        }
    }

    private void toggleStageBars() {
        disableStageBars = !disableStageBars;
        if (stageBarsButton != null) stageBarsButton.setMessage(stageBarsLabel());
    }

    private void cycleGridDivision() {
        gridDivisionIndex = (gridDivisionIndex + 1) % GRID_DIVISIONS.length;
        lastGridDivisionIndex = gridDivisionIndex;
        pendingNotePlacement = false;
        pendingEffectStartTick = -1;
        pendingEffectLane = -1;
        clearNoteSelectionDrag();
        clearEffectDrag();
        invalidateTimelineCache();
        if (gridDivisionButton != null) {
            gridDivisionButton.setMessage(gridDivisionLabel());
            gridDivisionButton.setTooltip(gridDivisionTooltip());
        }
        setStatus(gridDivision() == STANDARD_GRID_DIVISION
                ? I18n.get("music_battle.l10n.editor.text_008")
                : I18n.get("music_battle.l10n.editor.text_009"));
    }

    private FancyButton createGridDivisionButton(int x, int y, int width, int height) {
        FancyButton button = FancyButton.create(gridDivisionLabel(), pressed -> cycleGridDivision(), x, y, width, height);
        button.setTooltip(gridDivisionTooltip());
        return button;
    }

    /** The label has room for "1/4" only; the tooltip says what that is a fraction of and what a click switches to. */
    private Tooltip gridDivisionTooltip() {
        int next = GRID_DIVISIONS[(Mth.clamp(gridDivisionIndex, 0, GRID_DIVISIONS.length - 1) + 1) % GRID_DIVISIONS.length];
        return Tooltip.create(Component.translatable("music_battle.l10n.editor.grid_tooltip", gridDivision(), next));
    }

    private int gridDivision() {
        return GRID_DIVISIONS[Mth.clamp(gridDivisionIndex, 0, GRID_DIVISIONS.length - 1)];
    }

    private Component gridDivisionLabel() {
        return Component.translatable(gridDivision() == STANDARD_GRID_DIVISION ? "music_battle.l10n.editor.text_010" : "music_battle.l10n.editor.text_011");
    }

    private void clearSelection() {
        int cleared = selectedNotes.size() + selectedEffects.size();
        selectedNotes.clear();
        selectedEffects.clear();
        pendingNotePlacement = false;
        pendingEffectStartTick = -1;
        pendingEffectLane = -1;
        clearNoteSelectionDrag();
        clearEffectDrag();
        setStatus(cleared > 0 ? I18n.get("music_battle.l10n.editor.text_012") : I18n.get("music_battle.l10n.editor.text_013"));
    }

    private Component stageBarsLabel() {
        return Component.translatable(disableStageBars
                ? "music_battle.l10n.editor.text_014"
                : "music_battle.l10n.editor.text_015");
    }

    private void toggleStandardDimming() {
        hideStandardDimming = !hideStandardDimming;
        if (standardDimmingButton != null) standardDimmingButton.setMessage(standardDimmingLabel());
    }

    private Component standardDimmingLabel() {
        return Component.translatable(hideStandardDimming
                ? "music_battle.l10n.editor.text_016"
                : "music_battle.l10n.editor.text_017");
    }

    private void openEffectTemplateEditor() {
        this.minecraft.setScreen(new TrackEffectEditScreen(this, null,
                moveEffectToAbsoluteRow(effectTemplate, firstVisibleFxRow),
                effectMode.name().toLowerCase(java.util.Locale.ROOT)));
    }

    private static int absoluteFxRow(TrackEffect effect) {
        return effect.layer() * FX_LANES + effect.lane();
    }

    private boolean isFxRowVisible(TrackEffect effect) {
        int row = absoluteFxRow(effect);
        return row >= firstVisibleFxRow && row < firstVisibleFxRow + FX_LANES;
    }

    private int absoluteFxRowAtViewportLane(int viewportLane) {
        return Mth.clamp(firstVisibleFxRow + viewportLane, 0, TrackEffect.MAX_FX_ROWS - 1);
    }

    private static TrackEffect moveEffectToAbsoluteRow(TrackEffect effect, int absoluteRow) {
        int row = Mth.clamp(absoluteRow, 0, TrackEffect.MAX_FX_ROWS - 1);
        return effect.withLayer(row / FX_LANES)
                .withTimeline(row % FX_LANES, effect.tick(), effect.durationTicks());
    }

    private void addEffectButtons(int[] x, int y, int width, int height) {
        effectTypeButton = this.addRenderableWidget(FancyButton.create(effectTypeLabel(), button -> cycleEffectType(), x[0], y, width, height));
        effectModeButton = this.addRenderableWidget(FancyButton.create(effectModeLabel(), button -> cycleEffectMode(), x[1], y, width, height));
        effectDurationButton = this.addRenderableWidget(FancyButton.create(effectDurationLabel(), button -> cycleEffectDuration(), x[2], y, width, height));
        effectIntensityButton = this.addRenderableWidget(FancyButton.create(effectIntensityLabel(), button -> cycleEffectIntensity(), x[3], y, width, height));
        flashSoftnessButton = this.addRenderableWidget(FancyButton.create(flashSoftnessLabel(), button -> cycleFlashSoftness(), x[4], y, width, height));
    }

    private void addTimelinePageButton(int x, int y, int width, int height, int direction) {
        this.addRenderableWidget(FancyButton.create(Component.translatable(direction < 0 ? "music_battle.l10n.editor.page_previous" : "music_battle.l10n.editor.page_next"), button -> {
            pageStartTick = neighbourPageStart(direction);
            if (!previewPlaying) previewTick = pageStartTick;
        }, x, y, width, height));
    }

    private void switchEditorPage(EditorPage page) {
        if (editorPage == page) {
            return;
        }
        snapshotDraft();
        editorPage = page;
        selectedNotes.clear();
        selectedEffects.clear();
        pendingNotePlacement = false;
        pendingEffectStartTick = -1;
        pendingEffectLane = -1;
        if (page == EditorPage.FX) {
            setStatus(I18n.get("music_battle.l10n.editor.text_018"));
        }
        this.minecraft.setScreen(this);
    }

    private void snapshotDraft() {
        if (idBox != null) {
            draftId = idBox.getValue();
            draftTitle = titleBox.getValue();
            draftDescription = descriptionBox.getValue();
            draftBpm = bpmBox.getValue();
            draftOffset = offsetBox.getValue();
            draftAudio = audioBox.getValue();
            draftPreview = previewBox.getValue();
            draftImport = importBox.getValue();
        }
    }

    private void cycleEffectType() {
        TrackEffect.Type[] values = TrackEffect.Type.values();
        effectType = values[(effectType.ordinal() + 1) % values.length];
        effectTemplate = ClientEffectToolSettings.preset(effectType);
        effectMode = parseEffectMode(ClientEffectToolSettings.placementMode(effectType));
        pendingEffectStartTick = -1;
        pendingEffectLane = -1;
        effectTypeButton.setMessage(effectTypeLabel());
        if (effectModeButton != null) effectModeButton.setMessage(effectModeLabel());
        updateFlashSoftnessVisibility();
    }

    private void cycleEffectMode() {
        EffectPlacementMode[] values = EffectPlacementMode.values();
        effectMode = values[(effectMode.ordinal() + 1) % values.length];
        pendingEffectStartTick = -1;
        pendingEffectLane = -1;
        effectModeButton.setMessage(effectModeLabel());
        ClientEffectToolSettings.savePreset(effectTemplate, effectMode.name().toLowerCase(java.util.Locale.ROOT));
    }

    private void cycleEffectDuration() {
        effectDurationIndex = (effectDurationIndex + 1) % EFFECT_DURATION_STEPS.length;
        effectDurationButton.setMessage(effectDurationLabel());
    }

    private void cycleEffectIntensity() {
        effectIntensityIndex = (effectIntensityIndex + 1) % EFFECT_INTENSITIES.length;
        effectIntensityButton.setMessage(effectIntensityLabel());
    }

    private void cycleFlashSoftness() {
        flashSoftnessIndex = (flashSoftnessIndex + 1) % FLASH_SOFTNESS.length;
        if (flashSoftnessButton != null) {
            flashSoftnessButton.setMessage(flashSoftnessLabel());
        }
    }

    private void updateFlashSoftnessVisibility() {
        if (flashSoftnessButton != null) {
            boolean visible = editorPage == EditorPage.FX && effectType == TrackEffect.Type.FLASH;
            flashSoftnessButton.visible = visible;
            flashSoftnessButton.active = visible;
        }
    }

    private void clearEffects() {
        if (!effects.isEmpty()) {
            pushUndoSnapshot();
        }
        effects.clear();
        selectedEffects.clear();
        pendingEffectStartTick = -1;
        pendingEffectLane = -1;
        invalidateTimelineCache();
        setStatus(I18n.get("music_battle.l10n.editor.text_019"));
    }

    private Component effectTypeLabel() {
        return Component.literal("FX: " + effectName(effectType));
    }

    private Component effectModeLabel() {
        return Component.translatable("music_battle.l10n.editor.mode", Component.translatable(effectMode.displayName));
    }

    private Component effectDurationLabel() {
        int steps = EFFECT_DURATION_STEPS[effectDurationIndex];
        String beats = steps == 1 ? "1/4" : steps == 2 ? "1/2" : Integer.toString(steps / 4);
        return Component.translatable("music_battle.l10n.editor.length_beats", beats);
    }

    private Component effectIntensityLabel() {
        return Component.translatable("music_battle.l10n.editor.text_023",
                String.format(java.util.Locale.ROOT, "%.1f", EFFECT_INTENSITIES[effectIntensityIndex]));
    }

    private Component flashSoftnessLabel() {
        return Component.translatable("music_battle.l10n.editor.softness", Math.round(FLASH_SOFTNESS[flashSoftnessIndex] * 100.0F));
    }

    private void addOwnerButton(int x, int y, int width, int height) {
        ownerButton = this.addRenderableWidget(FancyButton.create(ownerLabel(), button -> {
            owner = owner == 0 ? 1 : 0;
            selectedNotes.clear();
            ownerButton.setMessage(ownerLabel());
        }, x, y, width, height));
    }

    private void addPageButtons(int x, int y, int width, int height) {
        this.addRenderableWidget(FancyButton.create(Component.translatable("music_battle.l10n.editor.page_previous"), button -> {
            pageStartTick = neighbourPageStart(-1);
            if (!previewPlaying) previewTick = pageStartTick;
        }, x, y, width, height));
        this.addRenderableWidget(FancyButton.create(Component.translatable("music_battle.l10n.editor.page_next"), button -> {
            pageStartTick = neighbourPageStart(1);
            if (!previewPlaying) previewTick = pageStartTick;
        }, x + width + 6, y, width, height));
    }

    private void drawEditorBackground(GuiGraphics graphics) {
        graphics.fill(0, 0, this.width, this.height, Palette.BG_0);
    }

    /** The form/controls card and the timeline card, in the toolkit's rounded panels. */
    private void drawPanels(GuiGraphics graphics) {
        EditorLayout layout = layout();
        int pad = layout.pad;
        int panelTop = editorPage == EditorPage.NOTES ? layout.formTop - pad : layout.controlsY - pad;
        int panelBottom = layout.controlsY + layout.controlsHeight + pad;
        RoundedRect.fill(graphics, layout.left - pad, panelTop, layout.panelWidth + pad * 2, panelBottom - panelTop,
                Palette.RADIUS_LG, Palette.BG_1);
        Grid grid = grid();
        int timelineLeft = editorPage == EditorPage.FX ? grid.x - 54 : grid.x - pad;
        RoundedRect.fill(graphics, timelineLeft, grid.y - 32, grid.x + grid.width + pad - timelineLeft, grid.height + 32 + pad,
                Palette.RADIUS_LG, Palette.BG_1);
        // The panels are queued shapes; draw them now so the grid's plain fills land on top of them, not under.
        graphics.flush();
    }

    private void drawTopBar(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, this.width, TOP_BAR, Palette.BG_1);
        graphics.fill(0, TOP_BAR - 1, this.width, TOP_BAR, Palette.LINE);
        int sideSpace = exitButton == null ? 8 : exitButton.getX() + exitButton.getWidth() + 8;
        float titleWidth = Math.max(20, this.width - sideSpace * 2);
        TextStyles.queueCentered(graphics, TextStyles.ellipsize(TextStyles.styled(this.title, TextStyle.TITLE), titleWidth),
                this.width / 2.0F, TextStyles.centeredCapY(TextStyle.TITLE, 0, TOP_BAR), Palette.TEXT_1);
        if (exitButton != null) {
            exitButton.render(graphics, mouseX, mouseY, partialTick);
        }
        if (saveButton != null) {
            saveButton.render(graphics, mouseX, mouseY, partialTick);
        }
    }

    /** A thin thumb on the right edge, shown only when the editor is taller than the window. */
    private void drawScrollbar(GuiGraphics graphics) {
        int max = maxScroll();
        if (max <= 0) {
            return;
        }
        int trackTop = TOP_BAR + 2;
        int trackHeight = this.height - trackTop - 2;
        int thumbHeight = Math.max(16, Math.round(trackHeight * (this.height - TOP_BAR) / (float) (this.height - TOP_BAR + max)));
        int thumbY = trackTop + Math.round((trackHeight - thumbHeight) * (scrollY / (float) max));
        RoundedRect.fill(graphics, this.width - 5, trackTop, 3, trackHeight, 1.5F, Palette.withAlpha(Palette.BG_3, 0.8F));
        RoundedRect.fill(graphics, this.width - 5, thumbY, 3, thumbHeight, 1.5F, Palette.TEXT_3);
    }

    private void drawLabels(GuiGraphics graphics) {
        if (editorPage == EditorPage.FX) {
            return;
        }
        float labelLift = layout().compactHeight ? 8.5F : 9.5F;
        for (FieldLabel label : fieldLabels) {
            TextStyles.queue(graphics, TextStyles.ellipsize(TextStyles.styled(label.text(), TextStyle.CAPTION), label.width()),
                    label.x(), label.boxY() - labelLift, Palette.TEXT_2);
        }
        if (volumeSlider != null && volumeSlider.visible) {
            // The value sits above the slider, at its right end, where the knob can never cover it.
            net.minecraft.util.FormattedCharSequence value = TextStyles.text(
                    String.format(java.util.Locale.ROOT, "%.2f", volume()), TextStyle.LABEL).getVisualOrderText();
            float valueWidth = TextStyles.width(value);
            float y = volumeSlider.getY() - labelLift;
            TextStyles.queue(graphics, TextStyles.ellipsize(TextStyles.translated("music_battle.l10n.editor.text_028", TextStyle.CAPTION),
                    volumeSlider.getWidth() - valueWidth - 4), volumeSlider.getX(), y, Palette.TEXT_2);
            TextStyles.queueRight(graphics, value, volumeSlider.getX() + volumeSlider.getWidth(), y, Palette.ACCENT);
        }
    }

    private void drawStatus(GuiGraphics graphics) {
        if (status.isBlank()) {
            return;
        }
        int maxWidth = Math.max(80, Math.min(this.width - 28, 560));
        // Wrapped rather than cut: the messages that explain what to do (a song that needs ffmpeg, a chart that is
        // too long) are the ones that do not fit on one line, and their second half is the useful part.
        List<net.minecraft.util.FormattedCharSequence> lines = TextStyles.wrap(TextStyles.text(status, TextStyle.BODY), maxWidth);
        if (lines.size() > 4) {
            lines = lines.subList(0, 4);
        }
        float textWidth = 0;
        for (net.minecraft.util.FormattedCharSequence line : lines) {
            textWidth = Math.max(textWidth, TextStyles.width(line));
        }
        float lineHeight = TextStyle.BODY.lineHeight();
        float boxWidth = Math.min(maxWidth, textWidth) + 16;
        float boxHeight = lines.size() * lineHeight + 10;
        float x = this.width / 2.0F - boxWidth / 2.0F;
        float y = this.height - 8 - boxHeight;
        int colour = status.startsWith("OK") ? 0xFF80FFB0 : status.startsWith("WARN") ? Palette.WARN : Palette.DANGER;
        RoundedRect.fill(graphics, x, y, boxWidth, boxHeight, Palette.RADIUS_MD, 0xEE101820);
        RoundedRect.stroke(graphics, x, y, boxWidth, boxHeight, Palette.RADIUS_MD, 1.0F, Palette.LINE);
        for (int i = 0; i < lines.size(); i++) {
            TextStyles.queueCentered(graphics, lines.get(i), this.width / 2.0F,
                    y + 5 + i * lineHeight + (lineHeight - TextStyle.BODY.capHeight()) * 0.5F, colour);
        }
    }

    private void setStatus(String message) {
        status = message == null ? "" : message;
        // Six seconds for a short line, longer for one that takes reading, so an explanation is not gone half-read.
        statusTicks = status.isBlank() ? 0 : 20 * Mth.clamp(status.length() / 18, 6, 20);
    }

    private void drawGrid(GuiGraphics graphics) {
        Grid grid = grid();
        if (editorPage == EditorPage.FX) {
            drawEffectLayerLabels(graphics, grid);
        }
        graphics.fill(grid.x, grid.y, grid.x + grid.width, grid.y + grid.height, editorPage == EditorPage.FX ? 0xEE030407 : 0xAA06080C);
        // With a first-beat offset the first page begins before the song does; that stretch is shaded, not editable.
        if (pageOriginTick() < 0.0D) {
            int songStartX = gridLineX(grid, -pageOriginTick() / pageSpanTicks());
            graphics.fill(grid.x, grid.y, songStartX, grid.y + grid.height, 0x99000000);
        }
        int columns = gridColumns();
        int columnWidth = Math.max(1, grid.width / columns);
        int visibleLanes = editorPage == EditorPage.FX ? FX_LANES : NOTE_LANES;
        int laneHeight = Math.max(1, grid.height / visibleLanes);
        // Lines stand at the exact beat fractions, evenly spaced, so they follow the music at any BPM; notes are
        // drawn on the line they were placed on (timelineTickX). Bar lines are the brightest, then beats.
        int precision = gridPrecisionMultiplier();
        int beatColumns = STANDARD_GRID_DIVISION * precision;
        for (int column = 0; column <= columns; column++) {
            int color = column % (4 * beatColumns) == 0 ? 0xDD7FB4CC
                    : column % beatColumns == 0 ? 0xAA5A8CA0
                    : column % precision == 0 ? 0x77405058 : 0x4437444C;
            int x = gridLineX(grid, column / (double) columns);
            graphics.fill(x, grid.y, x + 1, grid.y + grid.height, color);
        }
        for (int lane = 0; lane <= visibleLanes; lane++) {
            int y = grid.y + lane * laneHeight;
            graphics.fill(grid.x, y, grid.x + grid.width, y + 1, 0x88607080);
        }
        // Keep the playhead behind notes. When it was drawn over their handles it
        // looked interactive inside the grid, while the click actually resized the
        // note underneath it.
        drawPlayhead(graphics, grid);
        drawPreviewNoteFeedback(graphics, grid, laneHeight, false);
        int step = stepTicks();
        ensureTimelineCache();
        if (editorPage == EditorPage.NOTES) {
            // Draw the active party first, then tint it with the translucent
            // opposite party. Equal P1/P2 positions therefore remain visibly
            // shared while mouse editing still targets only the active party.
            for (int pass = 0; pass < 2; pass++) {
                boolean activePass = pass == 0;
                for (BattleNote note : cachedPageNotes) {
                    if ((note.owner() == owner) != activePass) {
                        continue;
                    }
                    drawTimelineNote(graphics, note, grid, laneHeight, step, activePass);
                }
            }
        } else {
            for (BattleNote note : cachedPageNotes) {
                drawTimelineNote(graphics, note, grid, laneHeight, step, false);
            }
        }
        drawPreviewNoteFeedback(graphics, grid, laneHeight, true);

        if (editorPage == EditorPage.FX) {
            drawEffects(graphics, grid, columnWidth, laneHeight);
        }

        drawTimelineScrubber(graphics, grid);
        String preview = I18n.get(previewPlaying ? "music_battle.l10n.editor.preview_playing" : "music_battle.l10n.editor.preview_stopped", previewTick);
        TextStyles.queue(graphics, TextStyles.ellipsize(TextStyles.translated("music_battle.l10n.editor.timeline_info",
                TextStyle.CAPTION, pageStartTick, exactStepLabel(), preview), grid.width), grid.x, grid.y - 25, Palette.TEXT_2);
    }

    /** The real cell length, 3.125 at 96 BPM: showing the rounded 3 would hide why lines and ticks differ. */
    private String exactStepLabel() {
        return new java.math.BigDecimal(exactCellTicks(gridPrecisionMultiplier()))
                .setScale(3, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    private void drawTimelineNote(GuiGraphics graphics, BattleNote note, Grid grid,
                                  int laneHeight, int step, boolean activeParty) {
        int noteEndTick = note.tick() + note.durationTicks();
        if (noteEndTick < pageStartTick || note.tick() >= pageStartTick + pageTicks()) {
            return;
        }
        int displayLane = editorPage == EditorPage.FX ? note.lane() - firstVisibleFxRow : note.lane();
        int visibleLaneCount = editorPage == EditorPage.FX ? FX_LANES : NOTE_LANES;
        if (displayLane < 0 || displayLane >= visibleLaneCount) {
            return;
        }
        NoteBounds bounds = noteBounds(note, grid, laneHeight, step, displayLane);
        int x = bounds.headLeft();
        int y = bounds.top();
        int color = editorPage == EditorPage.FX
                ? (note.owner() == 0 ? 0x4455DDF5 : 0x44FF6680)
                : activeParty
                ? (note.owner() == 0 ? 0xFF55DDF5 : Palette.DANGER)
                : (note.owner() == 0 ? 0x5555DDF5 : 0x55FF6680);
        if (note.durationTicks() > 0) {
            int tailColor = editorPage == EditorPage.FX || !activeParty
                    ? (note.owner() == 0 ? 0x3355DDF5 : 0x33FF6680)
                    : (note.owner() == 0 ? 0x9955DDF5 : 0x99FF6680);
            graphics.fill(bounds.left(), y + laneHeight / 2 - 2,
                    Math.max(bounds.left() + 2, bounds.right()), y + laneHeight / 2 + 3, tailColor);
        }
        if (bounds.headVisible()) {
            graphics.fill(x, y, bounds.headRight(), bounds.bottom(), color);
        }
        if (selectedNotes.contains(note)) {
            drawNoteHandles(graphics, bounds);
        }
        if (editorPage == EditorPage.NOTES && bounds.headVisible()) {
            int textColor = activeParty ? Palette.BG_0 : 0x99101018;
            graphics.drawCenteredString(this.font, note.owner() == 0 ? "P1" : "P2",
                    x + (bounds.headRight() - x) / 2, y + 4, textColor);
        }
        if (editorPage == EditorPage.NOTES && note.durationTicks() > 0 && bounds.headVisible()) {
            graphics.drawString(this.font, "H", bounds.headRight() - 9, bounds.bottom() - 9,
                    activeParty ? Palette.BG_0 : 0x99101018, false);
        }
    }

    private void drawNoteHandles(GuiGraphics graphics, NoteBounds bounds) {
        graphics.fill(bounds.left() - NOTE_HANDLE_OVERHANG, bounds.top() - NOTE_HANDLE_OVERHANG,
                bounds.right() + NOTE_HANDLE_OVERHANG, bounds.top(), 0xFFFFFF55);
        graphics.fill(bounds.left() - NOTE_HANDLE_OVERHANG, bounds.bottom(),
                bounds.right() + NOTE_HANDLE_OVERHANG, bounds.bottom() + NOTE_HANDLE_OVERHANG, 0xFFFFFF55);
        if (bounds.leftEdgeVisible()) {
            graphics.fill(bounds.left() - NOTE_HANDLE_OVERHANG, bounds.top(),
                    bounds.left() + NOTE_HANDLE_OVERHANG, bounds.bottom(), 0xFFFFFF55);
        }
        if (bounds.rightEdgeVisible()) {
            graphics.fill(bounds.right() - NOTE_HANDLE_OVERHANG, bounds.top(),
                    bounds.right() + NOTE_HANDLE_OVERHANG, bounds.bottom(), 0xFFFFFF55);
        }
    }

    private void drawPreviewNoteFeedback(GuiGraphics graphics, Grid grid, int laneHeight, boolean foreground) {
        if (!previewPlaying) {
            return;
        }
        long now = System.nanoTime();
        for (int lane = 0; lane < NOTE_LANES; lane++) {
            long started = previewLaneHitNanos[lane];
            if (started <= 0L) {
                continue;
            }
            float progress = (now - started) / 280_000_000.0F;
            if (progress < 0.0F || progress >= 1.0F) {
                continue;
            }
            int displayLane = editorPage == EditorPage.FX ? lane - firstVisibleFxRow : lane;
            int visibleLaneCount = editorPage == EditorPage.FX ? FX_LANES : NOTE_LANES;
            if (displayLane < 0 || displayLane >= visibleLaneCount) {
                continue;
            }
            int rgb = previewLaneHitOwner[lane] == 0 ? 0x0055DDF5 : 0x00FF6680;
            int laneY = grid.y + displayLane * laneHeight;
            if (!foreground) {
                int alpha = Math.round((1.0F - progress) * 105.0F);
                graphics.fill(grid.x, laneY + 1, grid.x + grid.width, laneY + laneHeight, (alpha << 24) | rgb);
                continue;
            }
            int tick = previewLaneHitTick[lane];
            if (tick < pageStartTick || tick >= pageStartTick + pageTicks()) {
                continue;
            }
            int centerX = (timelineTickX(grid, tick) + timelineTickX(grid, tick + stepTicks())) / 2;
            int centerY = laneY + laneHeight / 2;
            int radius = 4 + Math.round(progress * Math.max(8, laneHeight / 2.0F));
            int thickness = 2;
            int alpha = Math.round((1.0F - progress) * 230.0F);
            int color = (alpha << 24) | rgb;
            graphics.fill(centerX - radius, centerY - radius, centerX + radius, centerY - radius + thickness, color);
            graphics.fill(centerX - radius, centerY + radius - thickness, centerX + radius, centerY + radius, color);
            graphics.fill(centerX - radius, centerY - radius, centerX - radius + thickness, centerY + radius, color);
            graphics.fill(centerX + radius - thickness, centerY - radius, centerX + radius, centerY + radius, color);
        }
    }

    private void drawEffects(GuiGraphics graphics, Grid grid, int columnWidth, int laneHeight) {
        int step = stepTicks();
        int pageEnd = pageStartTick + pageTicks();
        ensureTimelineCache();
        for (TrackEffect effect : cachedPageEffects) {
            int displayEnd = effect.durationTicks() > 0 ? effect.tick() + effect.durationTicks() : effect.tick() + step;
            if (displayEnd < pageStartTick || effect.tick() >= pageEnd) {
                continue;
            }
            EffectBounds bounds = effectBounds(effect, grid, laneHeight, step);
            int x = bounds.left();
            int right = bounds.right();
            int subY = bounds.top();
            int bottom = bounds.bottom();
            graphics.fill(x, subY, Math.max(x + 3, right), bottom, effect.type().color());
            if (selectedEffects.contains(effect)) {
                graphics.fill(x - 2, subY - 2, Math.max(x + 3, right) + 2, subY, 0xFFFFFF55);
                graphics.fill(x - 2, bottom, Math.max(x + 3, right) + 2, bottom + 2, 0xFFFFFF55);
                graphics.fill(x - 2, subY, x, bottom, 0xFFFFFF55);
                graphics.fill(Math.max(x + 3, right), subY, Math.max(x + 3, right) + 2, bottom, 0xFFFFFF55);
            }
            if (columnWidth >= 12) {
                graphics.drawString(this.font, effectName(effect.type()), x + 2, subY + 2, Palette.BG_0, false);
            }
        }
        if (pendingEffectStartTick >= 0 && pendingEffectLane >= 0 && pendingEffectStartTick >= pageStartTick && pendingEffectStartTick < pageEnd) {
            int x = timelineTickX(grid, pendingEffectStartTick);
            int viewportLane = pendingEffectLane - firstVisibleFxRow;
            if (viewportLane >= 0 && viewportLane < FX_LANES) {
                int y = grid.y + viewportLane * laneHeight;
                graphics.fill(x - 1, y, x + 2, y + laneHeight, 0xFFFFFF55);
            }
        }
    }

    private void drawEffectLayerLabels(GuiGraphics graphics, Grid grid) {
        int laneHeight = Math.max(1, grid.height / FX_LANES);
        for (int lane = 0; lane < FX_LANES; lane++) {
            int top = grid.y + lane * laneHeight;
            int color = lane % 2 == 0 ? 0xCC253744 : 0xCC1C2B36;
            graphics.fill(grid.x - 48, top + 1, grid.x - 4, top + laneHeight, color);
            int globalRow = firstVisibleFxRow + lane + 1;
            TextStyles.queueCentered(graphics, TextStyles.translated("music_battle.l10n.editor.row", TextStyle.CAPTION, globalRow).getVisualOrderText(),
                    grid.x - 26, TextStyles.centeredCapY(TextStyle.CAPTION, top, laneHeight), Palette.TEXT_1);
        }
        int trackX = grid.x - 3;
        graphics.fill(trackX, grid.y, trackX + 2, grid.y + grid.height, 0xAA101820);
        int thumbHeight = Math.max(12, Math.round(grid.height * (FX_LANES / (float) TrackEffect.MAX_FX_ROWS)));
        int travel = Math.max(0, grid.height - thumbHeight);
        int maximumFirstRow = TrackEffect.MAX_FX_ROWS - FX_LANES;
        int thumbY = grid.y + Math.round(travel * firstVisibleFxRow / (float) Math.max(1, maximumFirstRow));
        graphics.fill(trackX, thumbY, trackX + 2, thumbY + thumbHeight, 0xFFE3A6FF);
    }

    private void renderEffectTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
        HoveredEffect hovered = hoveredEffectInfo(mouseX, mouseY);
        if (hovered == null) {
            resetEffectTooltipDwell();
            return;
        }
        TrackEffect effect = hovered.effect();
        drawEffectHandles(graphics, hovered.bounds());
        if (draggedEffectIndex >= 0 || !effectTooltipReady(hovered, mouseX, mouseY)) {
            return;
        }
        List<String> lines = new ArrayList<>();
        lines.add("FX: " + effectName(effect.type()));
        lines.add(I18n.get("music_battle.l10n.editor.row_detail", absoluteFxRow(effect) + 1));
        lines.add(I18n.get("music_battle.l10n.editor.text_036", String.format(java.util.Locale.ROOT, "%d", effect.tick()), String.format(java.util.Locale.ROOT, "%.2f", effect.tick() / 20.0F)));
        if (effect.durationTicks() <= 0) {
            lines.add(I18n.get("music_battle.l10n.editor.text_037"));
        } else {
            lines.add(I18n.get("music_battle.l10n.editor.text_038", String.format(java.util.Locale.ROOT, "%d", effect.durationTicks()), String.format(java.util.Locale.ROOT, "%.2f", effect.durationTicks() / 20.0F)));
        }
        if (effect.type() != TrackEffect.Type.HIDE_BATTLE_PANELS && effect.type() != TrackEffect.Type.HIDE_PARTICLES_HAND) {
            if (effect.type() != TrackEffect.Type.PLAYER_HIGHLIGHT) {
                lines.add(String.format(java.util.Locale.ROOT, "%s: %.1fx", effectIntensityName(effect.type()), effect.intensity()));
            }
            lines.add(I18n.get("music_battle.l10n.editor.text_039") + Math.round(effect.opacity() * 100.0F) + "%"
                    + (effect.beatReactive() ? effect.type() == TrackEffect.Type.OBJECT_OUTLINE
                    ? I18n.get("music_battle.l10n.editor.text_040") : I18n.get("music_battle.l10n.editor.text_041") : ""));
            lines.add(I18n.get("music_battle.l10n.editor.text_042") + fadeDescription(
                    effect.settings().fadeInEnabled(), effect.settings().fadeInTicks()));
            lines.add(I18n.get("music_battle.l10n.editor.text_043") + fadeDescription(
                    effect.settings().fadeOutEnabled(), effect.settings().fadeOutTicks()));
            if (effect.type() != TrackEffect.Type.TEXT && effect.type() != TrackEffect.Type.SHAKE
                    && effect.type() != TrackEffect.Type.OBJECT_OUTLINE) {
                lines.add(effect.settings().overlayText() ? I18n.get("music_battle.l10n.editor.text_044") : I18n.get("music_battle.l10n.editor.text_045"));
            }
            if (effect.type() != TrackEffect.Type.OBJECT_OUTLINE) {
                lines.add(effect.settings().overlayInterface() ? I18n.get("music_battle.l10n.editor.text_046") : I18n.get("music_battle.l10n.editor.text_047"));
                lines.add(effect.settings().overlayPlayers()
                        ? I18n.get("music_battle.l10n.editor.text_048") : I18n.get("music_battle.l10n.editor.text_049"));
                lines.add(effect.settings().overlayEntities()
                        ? I18n.get("music_battle.l10n.editor.text_050") : I18n.get("music_battle.l10n.editor.text_051"));
            }
        }
        if (effect.type() == TrackEffect.Type.FLASH) {
            lines.add(I18n.get("music_battle.l10n.editor.softness", Math.round(effect.softness() * 100.0F)));
        }
        if (effect.type() == TrackEffect.Type.PULSE || effect.type() == TrackEffect.Type.VISUALIZER
                || effect.type() == TrackEffect.Type.PIXEL_FIRE || effect.type() == TrackEffect.Type.STARS) {
            lines.add(I18n.get("music_battle.l10n.editor.text_052", String.format(java.util.Locale.ROOT, "%06X", effect.colorA() & 0xFFFFFF), String.format(java.util.Locale.ROOT, "%06X", effect.colorB() & 0xFFFFFF)));
        }
        if (effect.type() == TrackEffect.Type.TEXT) {
            lines.add(I18n.get("music_battle.l10n.editor.text", this.font.plainSubstrByWidth(effect.text(), 180)));
            lines.add(I18n.get("music_battle.l10n.editor.text_054", String.format(java.util.Locale.ROOT, "%.2f", effect.positionX()), String.format(java.util.Locale.ROOT, "%.2f", effect.positionY()), effect.animation(), effect.exitAnimation(), String.format(java.util.Locale.ROOT, "%.1f", effect.textSpeed())));
        }
        if (effect.type() == TrackEffect.Type.PLAYER_HIGHLIGHT) {
            lines.add(I18n.get("music_battle.l10n.editor.variant", effect.variant()));
            lines.add(I18n.get("music_battle.l10n.editor.text_055", String.format(java.util.Locale.ROOT, "%.1f", effect.outlineThickness()), String.format(java.util.Locale.ROOT, "%.1f", effect.glowStrength())));
            lines.add(effect.settings().highlightSpectators()
                    ? I18n.get("music_battle.l10n.editor.text_056", String.format(java.util.Locale.ROOT, "%06X", effect.settings().highlightSpectatorColor() & 0xFFFFFF))
                    : I18n.get("music_battle.l10n.editor.text_057"));
            lines.add(effect.settings().highlightEntities()
                    ? I18n.get("music_battle.l10n.editor.text_058", String.format(java.util.Locale.ROOT, "%06X", effect.settings().highlightEntityColor() & 0xFFFFFF))
                    : I18n.get("music_battle.l10n.editor.text_059"));
            lines.add(I18n.get("music_battle.l10n.editor.text_060") + (effect.settings().highlightModelGlow() ? I18n.get("music_battle.l10n.editor.text_061") : I18n.get("music_battle.l10n.editor.text_062")));
        }
        if (effect.type() == TrackEffect.Type.OBJECT_OUTLINE) {
            lines.add(I18n.get("music_battle.l10n.editor.text_063", String.format(java.util.Locale.ROOT, "%.1f", effect.outlineThickness()), String.format(java.util.Locale.ROOT, "%.1f", effect.glowStrength())));
            if (effect.objectOutlinePalette().size() >= 2) {
                lines.add(I18n.get("music_battle.l10n.editor.text_064", String.format(java.util.Locale.ROOT, "%d", effect.objectOutlinePalette().size()), String.format(java.util.Locale.ROOT, "%.2f", effect.settings().objectOutlineColorSpeed())));
            } else {
                lines.add(I18n.get("music_battle.l10n.editor.text_065", String.format(java.util.Locale.ROOT, "%06X", effect.colorA() & 0xFFFFFF)));
            }
        }
        if (effect.type() == TrackEffect.Type.VISUALIZER) {
            lines.add(I18n.get("music_battle.l10n.editor.text_066", visualizerPositionName(effect.settings().visualizerPosition()), String.format(java.util.Locale.ROOT, "%+.2f", effect.settings().visualizerOffset())));
            lines.add(I18n.get("music_battle.l10n.editor.text_067", String.format(java.util.Locale.ROOT, "%.2f", effect.positionX()), String.format(java.util.Locale.ROOT, "%.2f", effect.positionY())));
        }
        if (effect.type() == TrackEffect.Type.OLD_TV) {
            var settings = effect.settings();
            lines.add(I18n.get("music_battle.l10n.editor.text_068", String.format(java.util.Locale.ROOT, "%.0f", settings.tvScanlineSpacing()), String.format(java.util.Locale.ROOT, "%.0f", settings.tvScanlineOpacity() * 100.0F), String.format(java.util.Locale.ROOT, "%.0f", settings.tvFlickerAmount() * 100.0F)));
            lines.add(I18n.get("music_battle.l10n.editor.text_069", String.format(java.util.Locale.ROOT, "%.0f", settings.tvNoiseAmount() * 100.0F), String.format(java.util.Locale.ROOT, "%.0f", settings.tvNoiseOpacity() * 100.0F)));
            lines.add(settings.tvSweepEnabled()
                    ? I18n.get("music_battle.l10n.editor.text_070", String.format(java.util.Locale.ROOT, "%.0f", settings.tvSweepWidth()), String.format(java.util.Locale.ROOT, "%.0f", settings.tvSweepOpacity() * 100.0F), String.format(java.util.Locale.ROOT, "%.1f", settings.tvSweepSpeed()))
                    : I18n.get("music_battle.l10n.editor.text_071"));
        }
        if (effect.type() == TrackEffect.Type.VIGNETTE) {
            var settings = effect.settings();
            lines.add(I18n.get("music_battle.l10n.editor.text_072", String.format(java.util.Locale.ROOT, "%.2f", settings.vignetteRadius()), String.format(java.util.Locale.ROOT, "%.2f", settings.vignetteSoftness()), String.format(java.util.Locale.ROOT, "%.2f", settings.vignetteRoundness()), String.format(java.util.Locale.ROOT, "%06X", effect.colorA() & 0xFFFFFF)));
        }
        if (effect.type() == TrackEffect.Type.FISH_EYE) {
            var settings = effect.settings();
            lines.add(I18n.get("music_battle.l10n.editor.text_073", String.format(java.util.Locale.ROOT, "%.2f", settings.fishEyeStrength()), String.format(java.util.Locale.ROOT, "%.2f", settings.fishEyeRadius()), String.format(java.util.Locale.ROOT, "%.2f", settings.fishEyeZoom())));
            lines.add(I18n.get("music_battle.l10n.editor.text_074", String.format(java.util.Locale.ROOT, "%.2f", settings.fishEyeChromatic()), String.format(java.util.Locale.ROOT, "%.2f", settings.fishEyeEdgeDarkening())));
            lines.add(I18n.get("music_battle.l10n.editor.text_075", String.format(java.util.Locale.ROOT, "%.2f", effect.positionX()), String.format(java.util.Locale.ROOT, "%.2f", effect.positionY())));
        }
        if (effect.type() == TrackEffect.Type.CINEMATIC_BARS) {
            var settings = effect.settings();
            lines.add(I18n.get("music_battle.l10n.editor.text_076", String.format(java.util.Locale.ROOT, "%.0f", settings.cinematicTopHeight() * 100.0F), String.format(java.util.Locale.ROOT, "%.0f", settings.cinematicBottomHeight() * 100.0F), String.format(java.util.Locale.ROOT, "%+.0f", settings.cinematicCenterOffset() * 100.0F)));
            lines.add(I18n.get("music_battle.l10n.editor.text_077", String.format(java.util.Locale.ROOT, "%.1f", settings.cinematicFeather() * 100.0F), String.format(java.util.Locale.ROOT, "%.0f", settings.cinematicShadow() * 100.0F), settings.cinematicAnimated() ? I18n.get("music_battle.l10n.editor.text_078") : I18n.get("music_battle.l10n.editor.text_079"), String.format(java.util.Locale.ROOT, "%.1f", settings.cinematicAnimationSpeed())));
        }
        if (effect.type() == TrackEffect.Type.COLOR_CORRECTION) {
            var settings = effect.settings();
            lines.add(I18n.get("music_battle.l10n.editor.text_080", String.format(java.util.Locale.ROOT, "%+.2f", settings.colorBrightness()), String.format(java.util.Locale.ROOT, "%.2f", settings.colorContrast()), String.format(java.util.Locale.ROOT, "%.2f", settings.colorSaturation())));
            lines.add(I18n.get("music_battle.l10n.editor.text_081", String.format(java.util.Locale.ROOT, "%+.0f", settings.colorHue()), String.format(java.util.Locale.ROOT, "%.2f", settings.colorGamma()), String.format(java.util.Locale.ROOT, "%+.2f", settings.colorTemperature())));
        }
        if (effect.type() == TrackEffect.Type.STARS) {
            var settings = effect.settings();
            lines.add(I18n.get("music_battle.l10n.editor.text_082", String.format(java.util.Locale.ROOT, "%.2f", settings.starSize()), String.format(java.util.Locale.ROOT, "%.2f", settings.starDensity()), String.format(java.util.Locale.ROOT, "%.2f", settings.starBrightness())));
            lines.add(I18n.get("music_battle.l10n.editor.text_083", String.format(java.util.Locale.ROOT, "%.2f", settings.starTwinkleSpeed()), String.format(java.util.Locale.ROOT, "%+.2f", settings.starDriftSpeed()), String.format(java.util.Locale.ROOT, "%.0f", settings.starIridescence() * 100.0F)));
            lines.add(I18n.get("music_battle.l10n.editor.direction", starDirectionName(settings.starDirection())));
            lines.add(I18n.get("music_battle.l10n.editor.text_085", String.format(java.util.Locale.ROOT, "%.2f", effect.positionX()), String.format(java.util.Locale.ROOT, "%.2f", effect.positionY()), String.format(java.util.Locale.ROOT, "%.2f", settings.starSpread())));
        }
        int tooltipWidth = lines.stream().mapToInt(this.font::width).max().orElse(80) + 12;
        int tooltipHeight = lines.size() * 11 + 8;
        int x = Math.min(mouseX + 12, this.width - tooltipWidth - 4);
        int y = Math.min(mouseY + 12, this.height - tooltipHeight - 4);
        x = Math.max(4, x);
        y = Math.max(4, y);
        graphics.fill(x - 1, y - 1, x + tooltipWidth + 1, y + tooltipHeight + 1, 0xFF8D62A8);
        graphics.fill(x, y, x + tooltipWidth, y + tooltipHeight, 0xF218121F);
        for (int i = 0; i < lines.size(); i++) {
            graphics.drawString(this.font, lines.get(i), x + 6, y + 5 + i * 11, i == 0 ? 0xFFE3A6FF : Palette.TEXT_1, false);
        }
    }

    private static String effectIntensityName(TrackEffect.Type type) {
        return switch (type) {
            case FLASH -> I18n.get("music_battle.l10n.editor.text_086");
            case OLD_TV -> I18n.get("music_battle.l10n.editor.text_087");
            case FISH_EYE -> I18n.get("music_battle.l10n.editor.text_088");
            case CINEMATIC_BARS -> I18n.get("music_battle.l10n.editor.text_089");
            case SHAKE -> I18n.get("music_battle.l10n.editor.text_090");
            case PULSE -> I18n.get("music_battle.l10n.editor.text_091");
            case VISUALIZER -> I18n.get("music_battle.l10n.editor.text_092");
            case TEXT -> I18n.get("music_battle.l10n.editor.text_093");
            case PIXEL_FIRE -> I18n.get("music_battle.l10n.editor.text_094");
            case STARS -> I18n.get("music_battle.l10n.editor.text_095");
            case COLOR_CORRECTION -> I18n.get("music_battle.l10n.editor.text_096");
            case VIGNETTE -> I18n.get("music_battle.l10n.editor.text_097");
            case PLAYER_HIGHLIGHT -> "";
            case OBJECT_OUTLINE -> I18n.get("music_battle.l10n.editor.text_099");
            case HIDE_BATTLE_PANELS, HIDE_PARTICLES_HAND -> "";
        };
    }

    private static String effectName(TrackEffect.Type type) {
        return Component.translatable(type.translationKey()).getString();
    }

    private static String fadeDescription(boolean enabled, float ticks) {
        if (!enabled) return I18n.get("music_battle.l10n.editor.text_062");
        return ticks <= 0.0F ? I18n.get("music_battle.l10n.editor.text_100") : I18n.get("music_battle.l10n.editor.text_101", String.format(java.util.Locale.ROOT, "%.1f", ticks));
    }

    private static String visualizerPositionName(String value) {
        return switch (value == null ? "bottom" : value) {
            case "top" -> I18n.get("music_battle.l10n.editor.text_102");
            case "left" -> I18n.get("music_battle.l10n.editor.text_103");
            case "right" -> I18n.get("music_battle.l10n.editor.text_104");
            default -> I18n.get("music_battle.l10n.editor.text_105");
        };
    }

    private static String starDirectionName(String value) {
        String normalized = value == null ? "down_right" : value;
        return Component.translatable("effect.music_battle.stars.direction." + normalized).getString();
    }

    private boolean effectTooltipReady(HoveredEffect hovered, double mouseX, double mouseY) {
        long now = System.nanoTime();
        boolean sameEffect = effectTooltipHoverIndex == hovered.index()
                && effectTooltipHoverEffect == hovered.effect();
        boolean samePosition = Double.compare(effectTooltipMouseX, mouseX) == 0
                && Double.compare(effectTooltipMouseY, mouseY) == 0;
        if (!sameEffect || !samePosition || effectTooltipStillSinceNanos <= 0L) {
            effectTooltipHoverEffect = hovered.effect();
            effectTooltipHoverIndex = hovered.index();
            effectTooltipMouseX = mouseX;
            effectTooltipMouseY = mouseY;
            effectTooltipStillSinceNanos = now;
            return false;
        }
        return now - effectTooltipStillSinceNanos >= EFFECT_TOOLTIP_DELAY_NANOS;
    }

    private void resetEffectTooltipDwell() {
        effectTooltipHoverEffect = null;
        effectTooltipHoverIndex = -1;
        effectTooltipMouseX = Double.NaN;
        effectTooltipMouseY = Double.NaN;
        effectTooltipStillSinceNanos = 0L;
    }

    private TrackEffect hoveredEffect(double mouseX, double mouseY) {
        HoveredEffect hovered = hoveredEffectInfo(mouseX, mouseY);
        return hovered == null ? null : hovered.effect();
    }

    private HoveredEffect hoveredEffectInfo(double mouseX, double mouseY) {
        Grid grid = grid();
        if (mouseX < grid.x || mouseX >= grid.x + grid.width || mouseY < grid.y || mouseY >= grid.y + grid.height) {
            return null;
        }
        int laneHeight = Math.max(1, grid.height / FX_LANES);
        int lane = Mth.clamp((int) ((mouseY - grid.y) / laneHeight), 0, FX_LANES - 1);
        int absoluteRow = absoluteFxRowAtViewportLane(lane);
        int step = stepTicks();
        ensureTimelineCache();
        for (int i = cachedPageEffects.size() - 1; i >= 0; i--) {
            TrackEffect effect = cachedPageEffects.get(i);
            if (absoluteFxRow(effect) != absoluteRow) {
                continue;
            }
            EffectBounds bounds = effectBounds(effect, grid, laneHeight, step);
            if (mouseX >= bounds.left() && mouseX <= bounds.right() && mouseY >= bounds.top() && mouseY <= bounds.bottom()) {
                return new HoveredEffect(effect, effects.indexOf(effect), bounds);
            }
        }
        return null;
    }

    private EffectBounds effectBounds(TrackEffect effect, Grid grid, int laneHeight, int step) {
        int displayEnd = effect.durationTicks() > 0 ? effect.tick() + effect.durationTicks() : effect.tick() + step;
        // Effects use the same tick-to-pixel mapping as notes, so both sit on the exact grid lines.
        int startX = timelineTickX(grid, effect.tick());
        int minimumEnd = Math.min(grid.x + grid.width, startX + Math.round(0.65F * grid.width / gridColumns()));
        int left = startX + 2;
        int right = Math.max(minimumEnd, timelineTickX(grid, displayEnd)) - 2;
        int laneTop = grid.y + (absoluteFxRow(effect) - firstVisibleFxRow) * laneHeight;
        boolean leftEdgeVisible = effect.tick() >= pageStartTick && effect.tick() < pageStartTick + pageTicks();
        boolean rightEdgeVisible = effect.durationTicks() > 0 && displayEnd > pageStartTick && displayEnd <= pageStartTick + pageTicks();
        return new EffectBounds(left, laneTop + 4, Math.max(left + 3, right), Math.min(grid.y + grid.height - 3, laneTop + laneHeight - 4), leftEdgeVisible, rightEdgeVisible);
    }

    private void drawEffectHandles(GuiGraphics graphics, EffectBounds bounds) {
        int handleWidth = 3;
        graphics.fill(bounds.left() - 1, bounds.top() - 1, bounds.right() + 1, bounds.top() + 1, 0xFFFFFFFF);
        graphics.fill(bounds.left() - 1, bounds.bottom() - 1, bounds.right() + 1, bounds.bottom() + 1, 0xFFFFFFFF);
        if (bounds.leftEdgeVisible()) {
            graphics.fill(bounds.left() - 1, bounds.top(), bounds.left() + handleWidth, bounds.bottom(), 0xFFFFE36A);
        }
        if (bounds.rightEdgeVisible()) {
            graphics.fill(bounds.right() - handleWidth, bounds.top(), bounds.right() + 1, bounds.bottom(), 0xFFFFE36A);
        }
    }

    private void beginEffectDrag(HoveredEffect hovered, double mouseX, double mouseY) {
        timelineScrubbing = false;
        resumePreviewAfterScrub = false;
        draggedEffectIndex = hovered.index();
        dragStartedMouseX = mouseX;
        dragStartedMouseY = mouseY;
        effectWasDragged = false;
        dragHistoryCaptured = false;
        dragPageTurnArmed = true;
        TrackEffect effect = hovered.effect();
        int edgeWidth = Math.max(4, Math.min(8, (hovered.bounds().right() - hovered.bounds().left()) / 4));
        if (effect.durationTicks() <= 0) {
            effectDragMode = DragMode.MOVE;
            dragGrabOffsetTicks = cursorTimelineTick(mouseX) - effect.tick();
        } else if (hovered.bounds().leftEdgeVisible() && mouseX <= hovered.bounds().left() + edgeWidth) {
            effectDragMode = DragMode.RESIZE_LEFT;
            dragFixedTick = effect.tick() + Math.max(stepTicks(), effect.durationTicks());
        } else if (hovered.bounds().rightEdgeVisible() && mouseX >= hovered.bounds().right() - edgeWidth) {
            effectDragMode = DragMode.RESIZE_RIGHT;
            dragFixedTick = effect.tick();
        } else {
            effectDragMode = DragMode.MOVE;
            dragGrabOffsetTicks = cursorTimelineTick(mouseX) - effect.tick();
        }
    }

    private void updateEffectDrag(double mouseX, double mouseY) {
        if (draggedEffectIndex < 0 || draggedEffectIndex >= effects.size()) {
            clearEffectDrag();
            return;
        }
        if (!effectWasDragged && Math.hypot(mouseX - dragStartedMouseX, mouseY - dragStartedMouseY) < 3.0D) {
            return;
        }
        effectWasDragged = true;
        if (!dragHistoryCaptured) {
            pushUndoSnapshot();
            dragHistoryCaptured = true;
        }
        double effectiveMouseX = turnTimelinePageWhileDragging(mouseX);
        TrackEffect previous = effects.get(draggedEffectIndex);
        int cursorTick = cursorTimelineTick(effectiveMouseX);
        int snap = stepTicks();
        int targetRow = absoluteFxRow(previous);
        Grid grid = grid();
        if (mouseY >= grid.y && mouseY < grid.y + grid.height) {
            int viewportLane = Mth.clamp((int) ((mouseY - grid.y) / Math.max(1, grid.height / FX_LANES)), 0, FX_LANES - 1);
            targetRow = absoluteFxRowAtViewportLane(viewportLane);
        }
        TrackEffect relocated = moveEffectToAbsoluteRow(previous, targetRow);
        TrackEffect updated;
        if (effectDragMode == DragMode.RESIZE_LEFT) {
            int start = Mth.clamp(cursorTick, 0, Math.max(0, dragFixedTick - snap));
            updated = relocated.withTimeline(relocated.lane(), start, Math.max(snap, dragFixedTick - start));
        } else if (effectDragMode == DragMode.RESIZE_RIGHT) {
            int end = Math.max(dragFixedTick + snap, cursorTick);
            updated = relocated.withTimeline(relocated.lane(), dragFixedTick, end - dragFixedTick);
        } else {
            int start = snapTimelineTick(Math.max(0, cursorTick - dragGrabOffsetTicks));
            updated = relocated.withTimeline(relocated.lane(), start, previous.durationTicks());
        }
        if (effectDragMode == DragMode.MOVE && selectedEffects.contains(previous) && selectedEffects.size() > 1) {
            int tickDelta = updated.tick() - previous.tick();
            int rowDelta = absoluteFxRow(updated) - absoluteFxRow(previous);
            Set<TrackEffect> movedSelection = new HashSet<>();
            for (int i = 0; i < effects.size(); i++) {
                TrackEffect candidate = effects.get(i);
                if (!selectedEffects.contains(candidate)) {
                    continue;
                }
                TrackEffect moved = moveEffectToAbsoluteRow(candidate,
                        Mth.clamp(absoluteFxRow(candidate) + rowDelta, 0, TrackEffect.MAX_FX_ROWS - 1));
                moved = moved.withTimeline(moved.lane(), snapTimelineTick(Math.max(0, candidate.tick() + tickDelta)), candidate.durationTicks());
                effects.set(i, moved);
                movedSelection.add(moved);
                if (candidate.equals(previous)) {
                    draggedEffectIndex = i;
                }
            }
            selectedEffects.clear();
            selectedEffects.addAll(movedSelection);
        } else {
            effects.set(draggedEffectIndex, updated);
            if (selectedEffects.remove(previous)) {
                selectedEffects.add(updated);
            }
        }
        invalidateTimelineCache();
    }

    private int cursorTimelineTick(double mouseX) {
        return snapExactTick(cursorExactTick(mouseX));
    }

    /** The exact time under the mouse; the page starts between ticks whenever eight beats are not whole ticks. */
    private double cursorExactTick(double mouseX) {
        Grid grid = grid();
        double progress = Mth.clamp((mouseX - grid.x) / Math.max(1.0D, grid.width), 0.0D, 1.0D);
        return pageOriginTick() + progress * pageSpanTicks();
    }

    private int snapTimelineTick(int rawTick) {
        return snapExactTick(rawTick);
    }

    private int snapExactTick(double exactTick) {
        int precision = gridPrecisionMultiplier();
        return Math.max(0, cellTick(nearestCell(exactTick, precision), precision));
    }

    /** The lines are evenly spaced at the exact beat fractions, so the cell under the mouse is plain arithmetic. */
    private int gridColumnAt(Grid grid, double mouseX) {
        double progress = Mth.clamp((mouseX - grid.x) / Math.max(1.0D, grid.width), 0.0D, 0.999999D);
        return Mth.clamp((int) Math.floor(progress * gridColumns()), 0, gridColumns() - 1);
    }

    /** The first grid line after a tick; snapping only ever rises with its input, so the first that passes is next. */
    private int gridTickAfter(int tick) {
        for (int candidate = tick + 1; ; candidate++) {
            int snapped = snapTimelineTick(candidate);
            if (snapped > tick) {
                return snapped;
            }
        }
    }

    private int gridTickBefore(int tick) {
        for (int candidate = tick - 1; candidate > 0; candidate--) {
            int snapped = snapTimelineTick(candidate);
            if (snapped < tick) {
                return snapped;
            }
        }
        return 0;
    }

    private int gridTickAtColumn(int column) {
        int clamped = Mth.clamp(column, 0, gridColumns());
        int tick = Math.max(0, cellTick(pageFromBeatOne() * gridColumns() + clamped, gridPrecisionMultiplier()));
        // Above 300 BPM the last fine cell rounds up to the next page's first tick, where a note placed here would
        // vanish from this page; only the closing line itself may stand there.
        return clamped < gridColumns() ? Math.min(tick, pageStartTick + pageTicks() - 1) : tick;
    }

    /**
     * A whole tick that is the rounding of a fine-grid line is drawn on that line, so a note placed on the grid sits
     * on its line although the chart keeps it up to half a tick away; any other time, such as a playing playhead, is
     * drawn where it really is. Above 200 BPM two fine lines can round to one tick; the active grid's line wins.
     */
    private int timelineTickX(Grid grid, float tick) {
        if (tick == Math.floor(tick)) {
            int finest = GRID_DIVISIONS[GRID_DIVISIONS.length - 1] / STANDARD_GRID_DIVISION;
            for (int precision : new int[] {gridPrecisionMultiplier(), finest}) {
                long cell = nearestCell(tick, precision);
                if (cellTick(cell, precision) == (int) tick) {
                    long pageCells = (long) BASE_GRID_COLUMNS * precision;
                    return gridLineX(grid, (cell - pageFromBeatOne() * pageCells) / (double) pageCells);
                }
            }
        }
        return gridLineX(grid, (tick - pageOriginTick()) / pageSpanTicks());
    }

    /** Lines and the notes on them share this rounding, so a note never lands a pixel beside its line. */
    private int gridLineX(Grid grid, double progress) {
        return grid.x + (int) Math.round(Mth.clamp(progress, 0.0D, 1.0D) * grid.width);
    }

    private double turnTimelinePageWhileDragging(double mouseX) {
        Grid grid = grid();
        int direction = mouseX <= grid.x + 3 ? -1 : mouseX >= grid.x + grid.width - 3 ? 1 : 0;
        if (direction == 0) {
            dragPageTurnArmed = true;
            return mouseX;
        }
        if (!dragPageTurnArmed || (direction < 0 && pageStartTick == 0)) {
            return mouseX;
        }
        pageStartTick = neighbourPageStart(direction);
        dragPageTurnArmed = false;
        double targetGuiX = direction > 0 ? grid.x : grid.x + grid.width;
        warpCursorToGuiX(targetGuiX);
        return targetGuiX;
    }

    private void warpCursorToGuiX(double guiX) {
        var window = this.minecraft.getWindow();
        double nativeX = guiX * window.getScreenWidth() / Math.max(1.0D, window.getGuiScaledWidth());
        nativeX = Mth.clamp(nativeX, 0.0D, Math.max(0.0D, window.getScreenWidth() - 1.0D));
        GLFW.glfwSetCursorPos(window.getWindow(), nativeX, this.minecraft.mouseHandler.ypos());
    }

    private void clearEffectDrag() {
        draggedEffectIndex = -1;
        effectWasDragged = false;
        dragHistoryCaptured = false;
        dragPageTurnArmed = true;
    }

    private boolean insideGrid(double mouseX, double mouseY) {
        Grid grid = grid();
        return mouseX >= grid.x && mouseX < grid.x + grid.width && mouseY >= grid.y && mouseY < grid.y + grid.height;
    }

    private HoveredNote hoveredNoteInfo(double mouseX, double mouseY) {
        Grid grid = grid();
        boolean insideBodyGrid = insideGrid(mouseX, mouseY);
        boolean insideHandleEnvelope = mouseX >= grid.x - NOTE_HANDLE_OVERHANG
                && mouseX <= grid.x + grid.width + NOTE_HANDLE_OVERHANG
                && mouseY >= grid.y - NOTE_HANDLE_OVERHANG
                && mouseY <= grid.y + grid.height + NOTE_HANDLE_OVERHANG;
        if (!insideBodyGrid && !insideHandleEnvelope) {
            return null;
        }
        int laneHeight = Math.max(1, grid.height / NOTE_LANES);
        int lane = Mth.clamp((int) ((mouseY - grid.y) / laneHeight), 0, NOTE_LANES - 1);
        int step = stepTicks();
        ensureTimelineCache();
        // Prefer the actual painted body first. Expanded handle hitboxes are only
        // considered afterwards, so adjacent notes cannot steal each other's clicks.
        if (insideBodyGrid) {
            for (int i = cachedPageNotes.size() - 1; i >= 0; i--) {
                BattleNote note = cachedPageNotes.get(i);
                if (note.owner() != owner || note.lane() != lane) continue;
                NoteBounds bounds = noteBounds(note, grid, laneHeight, step);
                if (noteBodyContains(note, bounds, laneHeight, mouseX, mouseY)) {
                    return new HoveredNote(note, bounds);
                }
            }
        }
        for (int i = cachedPageNotes.size() - 1; i >= 0; i--) {
            BattleNote note = cachedPageNotes.get(i);
            if (note.owner() != owner || note.lane() != lane || note.durationTicks() <= 0
                    || !selectedNotes.contains(note)) continue;
            NoteBounds bounds = noteBounds(note, grid, laneHeight, step);
            if (noteHandlesContain(bounds, mouseX, mouseY)) {
                return new HoveredNote(note, bounds);
            }
        }
        return null;
    }

    private static boolean noteBodyContains(BattleNote note, NoteBounds bounds, int laneHeight,
                                            double mouseX, double mouseY) {
        boolean overHead = bounds.headVisible()
                && insideRectangle(mouseX, mouseY, bounds.headLeft(), bounds.top(), bounds.headRight(), bounds.bottom());
        if (overHead || note.durationTicks() <= 0) {
            return overHead;
        }
        int tailTop = bounds.top() + laneHeight / 2 - 2;
        int tailBottom = bounds.top() + laneHeight / 2 + 3;
        return insideRectangle(mouseX, mouseY, bounds.left(), tailTop, bounds.right(), tailBottom);
    }

    private static boolean noteHandlesContain(NoteBounds bounds, double mouseX, double mouseY) {
        boolean horizontal = insideRectangle(mouseX, mouseY,
                bounds.left() - NOTE_HANDLE_OVERHANG, bounds.top() - NOTE_HANDLE_OVERHANG,
                bounds.right() + NOTE_HANDLE_OVERHANG, bounds.top())
                || insideRectangle(mouseX, mouseY,
                bounds.left() - NOTE_HANDLE_OVERHANG, bounds.bottom(),
                bounds.right() + NOTE_HANDLE_OVERHANG, bounds.bottom() + NOTE_HANDLE_OVERHANG);
        boolean left = bounds.leftEdgeVisible() && insideRectangle(mouseX, mouseY,
                bounds.left() - NOTE_HANDLE_OVERHANG, bounds.top(),
                bounds.left() + NOTE_HANDLE_OVERHANG, bounds.bottom());
        boolean right = bounds.rightEdgeVisible() && insideRectangle(mouseX, mouseY,
                bounds.right() - NOTE_HANDLE_OVERHANG, bounds.top(),
                bounds.right() + NOTE_HANDLE_OVERHANG, bounds.bottom());
        return horizontal || left || right;
    }

    private static boolean insideRectangle(double x, double y, int left, int top, int right, int bottom) {
        return x >= left && x < right && y >= top && y < bottom;
    }

    private NoteBounds noteBounds(BattleNote note, Grid grid, int laneHeight, int step) {
        return noteBounds(note, grid, laneHeight, step, note.lane());
    }

    private NoteBounds noteBounds(BattleNote note, Grid grid, int laneHeight, int step,
                                  int displayLane) {
        int pageEnd = pageStartTick + pageTicks();
        // A fine grid's cells alternate in width (2 and 1 ticks at 120 BPM); a tap note on a line fills its own cell
        // and stops at the next line instead of spilling into the neighbour's.
        int headEnd = note.tick() + step;
        if (snapTimelineTick(note.tick()) == note.tick()) {
            headEnd = Math.min(headEnd, gridTickAfter(note.tick()));
        }
        int displayEnd = note.durationTicks() > 0 ? note.tick() + note.durationTicks() : headEnd;
        int exactStart = timelineTickX(grid, note.tick());
        int exactEnd = timelineTickX(grid, displayEnd);
        int nextStep = timelineTickX(grid, headEnd);
        int headWidth = Math.max(1, nextStep - exactStart);
        int left = Math.max(grid.x + 1, exactStart + (note.durationTicks() > 0 ? headWidth / 2 : 1));
        int right = Math.min(grid.x + grid.width - 1, Math.max(exactStart + headWidth - 3, exactEnd - 1));
        boolean headVisible = note.tick() >= pageStartTick && note.tick() < pageEnd;
        int headLeft = Math.max(grid.x + 3, exactStart + 3);
        int headRight = Math.min(grid.x + grid.width - 2, headLeft + Math.max(3, headWidth - 5));
        if (headVisible) {
            left = Math.min(left, headLeft);
            right = Math.max(right, headRight);
        }
        int top = grid.y + displayLane * laneHeight + 3;
        int bottom = top + laneHeight - 5;
        boolean leftEdgeVisible = note.durationTicks() > 0 && headVisible;
        boolean rightEdgeVisible = note.durationTicks() > 0 && displayEnd > pageStartTick && displayEnd <= pageEnd;
        return new NoteBounds(left, Math.max(left + 2, right), top, bottom, headLeft, headRight,
                headVisible, leftEdgeVisible, rightEdgeVisible);
    }

    private void beginNoteSelectionDrag(HoveredNote hovered, double mouseX, double mouseY) {
        BattleNote note = hovered.note();
        NoteBounds bounds = hovered.bounds();
        draggedNote = note;
        timelineScrubbing = false;
        resumePreviewAfterScrub = false;
        double leftDistance = bounds.leftEdgeVisible() ? Math.abs(mouseX - bounds.left()) : Double.POSITIVE_INFINITY;
        double rightDistance = bounds.rightEdgeVisible() ? Math.abs(mouseX - bounds.right()) : Double.POSITIVE_INFINITY;
        if (note.durationTicks() > 0 && Math.min(leftDistance, rightDistance) <= NOTE_RESIZE_GRAB_WIDTH) {
            noteDragMode = leftDistance <= rightDistance ? DragMode.RESIZE_LEFT : DragMode.RESIZE_RIGHT;
            noteDragFixedTick = noteDragMode == DragMode.RESIZE_LEFT
                    ? note.tick() + Math.max(stepTicks(), note.durationTicks())
                    : note.tick();
        } else {
            noteDragMode = DragMode.MOVE;
            noteDragGrabOffsetTicks = cursorTimelineTick(mouseX) - note.tick();
        }
        noteDragStartedMouseX = mouseX;
        noteDragStartedMouseY = mouseY;
        noteWasDragged = false;
        dragHistoryCaptured = false;
        dragPageTurnArmed = true;
    }

    private void renderHoveredNoteHandles(GuiGraphics graphics, double mouseX, double mouseY) {
        HoveredNote hovered = hoveredNoteInfo(mouseX, mouseY);
        if (hovered != null && hovered.note().durationTicks() > 0) {
            drawNoteHandles(graphics, hovered.bounds());
        }
    }

    private void updateNoteSelectionDrag(double mouseX, double mouseY) {
        if (draggedNote == null || !selectedNotes.contains(draggedNote)) {
            clearNoteSelectionDrag();
            return;
        }
        if (!noteWasDragged && Math.hypot(mouseX - noteDragStartedMouseX, mouseY - noteDragStartedMouseY) < 3.0D) {
            return;
        }
        noteWasDragged = true;
        double effectiveMouseX = turnTimelinePageWhileDragging(mouseX);
        Grid grid = grid();
        int cursorTick = cursorTimelineTick(effectiveMouseX);
        if (noteDragMode != DragMode.MOVE) {
            int snap = stepTicks();
            BattleNote previous = draggedNote;
            BattleNote resized;
            if (noteDragMode == DragMode.RESIZE_LEFT) {
                int start = Mth.clamp(cursorTick, 0, Math.max(0, noteDragFixedTick - snap));
                resized = new BattleNote(previous.owner(), previous.lane(), start,
                        Math.max(snap, noteDragFixedTick - start));
            } else {
                int end = Math.max(noteDragFixedTick + snap, cursorTick);
                resized = new BattleNote(previous.owner(), previous.lane(), noteDragFixedTick,
                        end - noteDragFixedTick);
            }
            if (!notesFit(List.of(resized), Set.of(previous))) {
                setStatus(I18n.get("music_battle.l10n.editor.text_106"));
                return;
            }
            if (!dragHistoryCaptured) {
                pushUndoSnapshot();
                dragHistoryCaptured = true;
            }
            int index = notes.indexOf(previous);
            if (index >= 0) {
                notes.set(index, resized);
            }
            selectedNotes.remove(previous);
            selectedNotes.add(resized);
            draggedNote = resized;
            invalidateTimelineCache();
            return;
        }
        int targetTick = snapTimelineTick(Math.max(0, cursorTick - noteDragGrabOffsetTicks));
        int targetLane = draggedNote.lane();
        if (mouseY >= grid.y && mouseY < grid.y + grid.height) {
            targetLane = Mth.clamp((int) ((mouseY - grid.y) / Math.max(1, grid.height / NOTE_LANES)), 0, NOTE_LANES - 1);
        }
        int tickDelta = targetTick - draggedNote.tick();
        int laneDelta = targetLane - draggedNote.lane();
        List<BattleNote> originals = notes.stream().filter(selectedNotes::contains).toList();
        List<BattleNote> replacements = originals.stream()
                .map(candidate -> new BattleNote(candidate.owner(), Mth.clamp(candidate.lane() + laneDelta, 0, 3),
                        snapTimelineTick(Math.max(0, candidate.tick() + tickDelta)), candidate.durationTicks()))
                .toList();
        if (!notesFit(replacements, new HashSet<>(originals))) {
            setStatus(I18n.get("music_battle.l10n.editor.text_107"));
            return;
        }
        if (!dragHistoryCaptured) {
            pushUndoSnapshot();
            dragHistoryCaptured = true;
        }
        Set<BattleNote> moved = new HashSet<>(replacements);
        BattleNote newAnchor = null;
        for (int index = 0; index < originals.size(); index++) {
            BattleNote candidate = originals.get(index);
            BattleNote replacement = replacements.get(index);
            int noteIndex = notes.indexOf(candidate);
            if (noteIndex >= 0) notes.set(noteIndex, replacement);
            if (candidate.equals(draggedNote)) newAnchor = replacement;
        }
        selectedNotes.clear();
        selectedNotes.addAll(moved);
        draggedNote = newAnchor;
        invalidateTimelineCache();
    }

    private void clearNoteSelectionDrag() {
        draggedNote = null;
        noteDragMode = DragMode.MOVE;
        noteWasDragged = false;
        dragHistoryCaptured = false;
        dragPageTurnArmed = true;
    }

    private boolean notesFit(List<BattleNote> candidates, Set<BattleNote> ignored) {
        for (int first = 0; first < candidates.size(); first++) {
            BattleNote candidate = candidates.get(first);
            for (BattleNote existing : notes) {
                if (!ignored.contains(existing) && notesOverlap(candidate, existing)) return false;
            }
            for (int second = first + 1; second < candidates.size(); second++) {
                if (notesOverlap(candidate, candidates.get(second))) return false;
            }
        }
        return true;
    }

    private boolean notesOverlap(BattleNote first, BattleNote second) {
        if (first.owner() != second.owner() || first.lane() != second.lane()) return false;
        int firstEnd = first.tick() + Math.max(stepTicks(), first.durationTicks());
        int secondEnd = second.tick() + Math.max(stepTicks(), second.durationTicks());
        return first.tick() < secondEnd && second.tick() < firstEnd;
    }

    private static <T> void toggleSelection(Set<T> selection, T value) {
        if (!selection.add(value)) {
            selection.remove(value);
        }
    }

    private void pushUndoSnapshot() {
        EditorSnapshot snapshot = snapshotTimeline();
        if (!undoHistory.isEmpty() && undoHistory.peekLast().equals(snapshot)) {
            return;
        }
        undoHistory.addLast(snapshot);
        while (undoHistory.size() > 100) {
            undoHistory.removeFirst();
        }
        redoHistory.clear();
    }

    private EditorSnapshot snapshotTimeline() {
        return new EditorSnapshot(List.copyOf(notes), List.copyOf(effects));
    }

    private void restoreSnapshot(EditorSnapshot snapshot) {
        notes.clear();
        notes.addAll(snapshot.notes());
        effects.clear();
        effects.addAll(snapshot.effects());
        selectedNotes.clear();
        selectedEffects.clear();
        pendingEffectStartTick = -1;
        pendingEffectLane = -1;
        invalidateTimelineCache();
    }

    private void undo() {
        if (undoHistory.isEmpty()) {
            setStatus(I18n.get("music_battle.l10n.editor.text_108"));
            return;
        }
        redoHistory.addLast(snapshotTimeline());
        restoreSnapshot(undoHistory.removeLast());
        setStatus("OK: Ctrl+Z");
    }

    private void redo() {
        if (redoHistory.isEmpty()) {
            setStatus(I18n.get("music_battle.l10n.editor.text_109"));
            return;
        }
        undoHistory.addLast(snapshotTimeline());
        restoreSnapshot(redoHistory.removeLast());
        setStatus("OK: Shift+Ctrl+Z");
    }

    private void copySelection() {
        selectedEffects.removeIf(effect -> !isFxRowVisible(effect));
        if (selectedNotes.isEmpty() && selectedEffects.isEmpty()) {
            setStatus(I18n.get("music_battle.l10n.editor.text_110"));
            return;
        }
        int minTick = Integer.MAX_VALUE;
        for (BattleNote note : selectedNotes) minTick = Math.min(minTick, note.tick());
        for (TrackEffect effect : selectedEffects) minTick = Math.min(minTick, effect.tick());
        int origin = minTick == Integer.MAX_VALUE ? 0 : minTick;
        List<BattleNote> copiedNotes = selectedNotes.stream()
                .map(note -> new BattleNote(note.owner(), note.lane(), note.tick() - origin, note.durationTicks()))
                .toList();
        List<TrackEffect> copiedEffects = selectedEffects.stream()
                .map(effect -> effect.withTimeline(effect.lane(), effect.tick() - origin, effect.durationTicks()))
                .toList();
        int sourceFxRow = selectedEffects.stream().mapToInt(TrackEditorScreen::absoluteFxRow).min().orElse(0);
        clipboard = new ClipboardData(copiedNotes, copiedEffects, sourceFxRow);
        setStatus(I18n.get("music_battle.l10n.editor.copied", copiedNotes.size() + copiedEffects.size()));
    }

    private void pasteSelection() {
        if (clipboard.notes().isEmpty() && clipboard.effects().isEmpty()) {
            setStatus(I18n.get("music_battle.l10n.editor.text_113"));
            return;
        }
        List<BattleNote> candidates = clipboard.notes().stream()
                .map(note -> new BattleNote(note.owner(), note.lane(), pageStartTick + note.tick(), note.durationTicks()))
                .toList();
        if (!notesFit(candidates, Set.of())) {
            setStatus(I18n.get("music_battle.l10n.editor.text_114"));
            return;
        }
        pushUndoSnapshot();
        selectedNotes.clear();
        selectedEffects.clear();
        int targetTick = pageStartTick;
        for (BattleNote pasted : candidates) {
            notes.add(pasted);
            selectedNotes.add(pasted);
        }
        for (TrackEffect effect : clipboard.effects()) {
            int targetRow = Mth.clamp(firstVisibleFxRow + absoluteFxRow(effect) - clipboard.sourceFxRow(),
                    0, TrackEffect.MAX_FX_ROWS - 1);
            TrackEffect pasted = moveEffectToAbsoluteRow(effect, targetRow);
            pasted = pasted.withTimeline(pasted.lane(), targetTick + effect.tick(), effect.durationTicks());
            effects.add(pasted);
            selectedEffects.add(pasted);
        }
        invalidateTimelineCache();
        setStatus(I18n.get("music_battle.l10n.editor.text_115"));
    }

    private void deleteSelection() {
        selectedEffects.removeIf(effect -> !isFxRowVisible(effect));
        if (selectedNotes.isEmpty() && selectedEffects.isEmpty()) {
            return;
        }
        pushUndoSnapshot();
        int count = selectedNotes.size() + selectedEffects.size();
        notes.removeAll(selectedNotes);
        effects.removeAll(selectedEffects);
        selectedNotes.clear();
        selectedEffects.clear();
        invalidateTimelineCache();
        setStatus(I18n.get("music_battle.l10n.editor.deleted", count));
    }

    private void convertSelectedNoteOwners() {
        if (selectedNotes.isEmpty()) {
            setStatus(I18n.get("music_battle.l10n.editor.text_117"));
            return;
        }
        List<BattleNote> originals = notes.stream().filter(selectedNotes::contains).toList();
        List<BattleNote> converted = originals.stream()
                .map(note -> new BattleNote(1 - Mth.clamp(note.owner(), 0, 1), note.lane(), note.tick(), note.durationTicks()))
                .toList();
        if (!notesFit(converted, new HashSet<>(originals))) {
            setStatus(I18n.get("music_battle.l10n.editor.text_118"));
            return;
        }
        pushUndoSnapshot();
        for (int index = 0; index < originals.size(); index++) {
            int noteIndex = notes.indexOf(originals.get(index));
            if (noteIndex >= 0) {
                notes.set(noteIndex, converted.get(index));
            }
        }
        selectedNotes.clear();
        selectedNotes.addAll(converted);
        invalidateTimelineCache();
        setStatus(I18n.get("music_battle.l10n.editor.converted", converted.size()));
    }

    private void moveSelection(int tickDelta, int laneDelta) {
        selectedEffects.removeIf(effect -> !isFxRowVisible(effect));
        List<BattleNote> originalNotes = notes.stream().filter(selectedNotes::contains).toList();
        List<BattleNote> replacementNotes = originalNotes.stream()
                .map(note -> new BattleNote(note.owner(), Mth.clamp(note.lane() + laneDelta, 0, 3),
                        Math.max(0, note.tick() + tickDelta), note.durationTicks()))
                .toList();
        if (!notesFit(replacementNotes, new HashSet<>(originalNotes))) {
            setStatus(I18n.get("music_battle.l10n.editor.text_107"));
            return;
        }
        pushUndoSnapshot();
        Set<BattleNote> movedNotes = new HashSet<>(replacementNotes);
        for (int index = 0; index < originalNotes.size(); index++) {
            int noteIndex = notes.indexOf(originalNotes.get(index));
            if (noteIndex >= 0) notes.set(noteIndex, replacementNotes.get(index));
        }
        Set<TrackEffect> movedEffects = new HashSet<>();
        for (int i = 0; i < effects.size(); i++) {
            TrackEffect effect = effects.get(i);
            if (!selectedEffects.contains(effect)) continue;
            TrackEffect moved = moveEffectToAbsoluteRow(effect,
                    Mth.clamp(absoluteFxRow(effect) + laneDelta, 0, TrackEffect.MAX_FX_ROWS - 1));
            moved = moved.withTimeline(moved.lane(), Math.max(0, effect.tick() + tickDelta), effect.durationTicks());
            effects.set(i, moved);
            movedEffects.add(moved);
        }
        selectedNotes.clear(); selectedNotes.addAll(movedNotes);
        selectedEffects.clear(); selectedEffects.addAll(movedEffects);
        keepEffectSelectionInView(movedEffects);
        invalidateTimelineCache();
        setStatus(I18n.get("music_battle.l10n.editor.text_120"));
    }

    private void keepEffectSelectionInView(Set<TrackEffect> movedEffects) {
        if (editorPage != EditorPage.FX || movedEffects.isEmpty()) return;
        int minimum = movedEffects.stream().mapToInt(TrackEditorScreen::absoluteFxRow).min().orElse(firstVisibleFxRow);
        int maximum = movedEffects.stream().mapToInt(TrackEditorScreen::absoluteFxRow).max().orElse(firstVisibleFxRow);
        int next = firstVisibleFxRow;
        if (minimum < next) {
            next = minimum;
        } else if (maximum >= next + FX_LANES) {
            next = maximum - FX_LANES + 1;
        }
        next = Mth.clamp(next, 0, TrackEffect.MAX_FX_ROWS - FX_LANES);
        if (next != firstVisibleFxRow) {
            firstVisibleFxRow = next;
            cachedFirstVisibleFxRow = -1;
        }
    }

    void replaceEffect(TrackEffect original, TrackEffect replacement) {
        int index = effects.indexOf(original);
        if (index >= 0) {
            pushUndoSnapshot();
            effects.set(index, replacement);
            invalidateTimelineCache();
            setStatus(I18n.get("music_battle.l10n.editor.text_121"));
        }
    }

    void configureEffectTemplate(TrackEffect template, String placementMode) {
        effectTemplate = template;
        effectType = template.type();
        if ("range".equals(placementMode)) {
            effectMode = EffectPlacementMode.RANGE;
        } else if ("length".equals(placementMode)) {
            effectMode = EffectPlacementMode.LENGTH;
        } else {
            effectMode = EffectPlacementMode.POINT;
        }
        ClientEffectToolSettings.savePreset(template, placementMode);
        setStatus(I18n.get("music_battle.l10n.editor.template_updated", effectName(template.type())));
    }

    void refreshEffectBrush(TrackEffect.Type type) {
        effectType = type;
        effectTemplate = ClientEffectToolSettings.preset(type);
        effectMode = parseEffectMode(ClientEffectToolSettings.placementMode(type));
        pendingEffectStartTick = -1;
        pendingEffectLane = -1;
        setStatus(I18n.get("music_battle.l10n.editor.brush_updated", effectName(type)));
    }

    void removeEffect(TrackEffect effect) {
        pushUndoSnapshot();
        if (effects.remove(effect)) {
            invalidateTimelineCache();
            setStatus(I18n.get("music_battle.l10n.editor.text_126"));
        }
    }

    private boolean toggleGridNote(double mouseX, double mouseY, int button) {
        Grid grid = grid();
        if (mouseX < grid.x || mouseX >= grid.x + grid.width || mouseY < grid.y || mouseY >= grid.y + grid.height) {
            return false;
        }

        int laneCount = editorPage == EditorPage.FX ? FX_LANES : NOTE_LANES;
        int laneHeight = Math.max(1, grid.height / laneCount);
        int column = gridColumnAt(grid, mouseX);
        int lane = Mth.clamp((int) ((mouseY - grid.y) / laneHeight), 0, laneCount - 1);
        if (editorPage == EditorPage.FX) {
            return toggleGridEffect(column, lane, button);
        }
        int tick = gridTickAtColumn(column);

        int holdStep = hasShiftDown() ? stepTicks() * 4 : stepTicks();
        for (Iterator<BattleNote> iterator = notes.iterator(); iterator.hasNext(); ) {
            BattleNote note = iterator.next();
            if (note.owner() == owner && note.lane() == lane && note.tick() == tick) {
                if (button == 1) {
                    BattleNote extended = new BattleNote(owner, lane, tick, note.durationTicks() + holdStep);
                    if (!notesFit(List.of(extended), Set.of(note))) {
                        setStatus(I18n.get("music_battle.l10n.editor.text_127"));
                        return true;
                    }
                    iterator.remove();
                    notes.add(extended);
                } else {
                    iterator.remove();
                }
                invalidateTimelineCache();
                return true;
            }
        }
        BattleNote added = new BattleNote(owner, lane, tick, button == 1 ? holdStep : 0);
        if (!notesFit(List.of(added), Set.of())) {
            setStatus(I18n.get("music_battle.l10n.editor.text_128"));
            return true;
        }
        notes.add(added);
        invalidateTimelineCache();
        return true;
    }

    private boolean toggleGridEffect(int column, int lane, int button) {
        int tick = gridTickAtColumn(column);
        int absoluteRow = absoluteFxRowAtViewportLane(lane);
        if (button == 1) {
            TrackEffect selected = null;
            ensureTimelineCache();
            for (TrackEffect effect : cachedPageEffects) {
                boolean startsHere = effect.tick() >= tick && effect.tick() < tick + stepTicks();
                boolean coversHere = effect.durationTicks() > 0 && tick >= effect.tick() && tick <= effect.tick() + effect.durationTicks();
                if (absoluteFxRow(effect) == absoluteRow && (startsHere || coversHere)) {
                    selected = effect;
                }
            }
            if (selected != null) {
                effects.remove(selected);
                invalidateTimelineCache();
                setStatus(I18n.get("music_battle.l10n.editor.text_126"));
            }
            return true;
        }
        if (button != 0) {
            return true;
        }
        if (effects.size() >= TrackEffect.MAX_EFFECTS) {
            setStatus(I18n.get("music_battle.l10n.editor.max_effects", TrackEffect.MAX_EFFECTS));
            return true;
        }
        if (effectMode == EffectPlacementMode.RANGE) {
            if (pendingEffectStartTick < 0) {
                pendingEffectStartTick = tick;
                pendingEffectLane = absoluteRow;
                setStatus(I18n.get("music_battle.l10n.editor.range_start", tick));
                return true;
            }
            int start = Math.min(pendingEffectStartTick, tick);
            int duration = Math.max(stepTicks(), Math.abs(tick - pendingEffectStartTick));
            TrackEffect added = moveEffectToAbsoluteRow(effectTemplate, pendingEffectLane);
            effects.add(added.withTimeline(added.lane(), start, duration));
            invalidateTimelineCache();
            pendingEffectStartTick = -1;
            pendingEffectLane = -1;
            setStatus(I18n.get("music_battle.l10n.editor.range_added"));
            return true;
        }
        int duration = effectMode == EffectPlacementMode.LENGTH
                ? Math.max(stepTicks(), effectTemplate.durationTicks())
                : 0;
        TrackEffect added = moveEffectToAbsoluteRow(effectTemplate, absoluteRow);
        effects.add(added.withTimeline(added.lane(), tick, duration));
        invalidateTimelineCache();
        setStatus(I18n.get("music_battle.l10n.editor.effect_added", effectName(effectType)));
        return true;
    }

    private float selectedEffectSoftness() {
        return effectType == TrackEffect.Type.FLASH ? FLASH_SOFTNESS[flashSoftnessIndex] : 0.65F;
    }

    private void invalidateTimelineCache() {
        timelineRevision++;
        if (previewPlaying) {
            float current = currentPreviewTick();
            previewNoteQueue = notes.stream()
                    .sorted(Comparator.comparingInt(BattleNote::tick).thenComparingInt(BattleNote::lane).thenComparingInt(BattleNote::owner))
                    .toList();
            previewNoteCursor = 0;
            while (previewNoteCursor < previewNoteQueue.size() && previewNoteQueue.get(previewNoteCursor).tick() <= current) {
                previewNoteCursor++;
            }
            lastPreviewEffectTick = current;
        }
    }

    private void ensureTimelineCache() {
        int pageLength = pageTicks();
        if (cachedTimelineRevision == timelineRevision && cachedPageStartTick == pageStartTick
                && cachedPageTicks == pageLength && cachedFirstVisibleFxRow == firstVisibleFxRow) {
            return;
        }
        int pageEnd = pageStartTick + pageLength;
        cachedPageNotes = notes.stream()
                .filter(note -> note.tick() + note.durationTicks() >= pageStartTick && note.tick() < pageEnd)
                .sorted(Comparator.comparingInt(BattleNote::tick).thenComparingInt(BattleNote::lane).thenComparingInt(BattleNote::owner))
                .toList();
        int pointLength = stepTicks();
        cachedPageEffects = effects.stream()
                .filter(effect -> effect.tick() + (effect.durationTicks() > 0 ? effect.durationTicks() : pointLength) >= pageStartTick && effect.tick() < pageEnd)
                // Only the six rows currently inside the FX viewport enter the visual cache.
                // Hidden rows remain in the chart data and preview playback, but cost no grid
                // draw calls, bounds calculations or hover checks.
                .filter(this::isFxRowVisible)
                .sorted(Comparator.comparingInt(TrackEffect::layer).thenComparingInt(TrackEffect::tick).thenComparingInt(TrackEffect::lane).thenComparing(effect -> effect.type().id()))
                .toList();
        cachedTimelineRevision = timelineRevision;
        cachedPageStartTick = pageStartTick;
        cachedPageTicks = pageLength;
        cachedFirstVisibleFxRow = firstVisibleFxRow;
    }

    private void drawPlayhead(GuiGraphics graphics, Grid grid) {
        float playheadTick = previewPlaying ? currentPreviewTick() : previewTick;
        if (playheadTick < pageStartTick || playheadTick >= pageStartTick + pageTicks()) {
            return;
        }
        int x = timelineTickX(grid, playheadTick);
        graphics.fill(x - 1, grid.y - 2, x + 2, grid.y + grid.height + 2, 0xFFFFF06A);
    }

    private void drawTimelineScrubber(GuiGraphics graphics, Grid grid) {
        int top = grid.y - 12;
        int bottom = grid.y - 5;
        int endTick = timelineEndTick();
        graphics.fill(grid.x, top, grid.x + grid.width, bottom, 0xFF091117);

        float pageStartProgress = Mth.clamp(pageStartTick / (float) endTick, 0.0F, 1.0F);
        float pageEndProgress = Mth.clamp((pageStartTick + pageTicks()) / (float) endTick, 0.0F, 1.0F);
        int pageLeft = grid.x + Math.round(grid.width * pageStartProgress);
        int pageRight = grid.x + Math.round(grid.width * pageEndProgress);
        graphics.fill(pageLeft, top + 1, Math.max(pageLeft + 1, pageRight), bottom - 1, 0x884AA8C4);

        float currentTick = previewPlaying ? currentPreviewTick() : previewTick;
        int handleX = grid.x + Math.round(grid.width * Mth.clamp(currentTick / endTick, 0.0F, 1.0F));
        graphics.fill(grid.x, top + 2, handleX, bottom - 2, 0xFF55CFEA);
        graphics.fill(handleX - 2, top - 2, handleX + 3, bottom + 2, 0xFFFFF06A);
    }

    private boolean insideTimelineScrubber(double mouseX, double mouseY) {
        Grid grid = grid();
        return mouseX >= grid.x && mouseX <= grid.x + grid.width
                && mouseY >= grid.y - 16 && mouseY <= grid.y - 2;
    }

    private void beginTimelineScrub(double mouseX) {
        pendingNotePlacement = false;
        clearNoteSelectionDrag();
        clearEffectDrag();
        resumePreviewAfterScrub = previewPlaying;
        if (previewPlaying) {
            stopPreview(false);
        }
        timelineScrubbing = true;
        updateTimelineScrub(mouseX);
    }

    private void updateTimelineScrub(double mouseX) {
        Grid grid = grid();
        double progress = Mth.clamp((mouseX - grid.x) / Math.max(1.0D, grid.width), 0.0D, 1.0D);
        previewTick = Mth.clamp((int) Math.round(progress * timelineEndTick()), 0, Math.max(0, timelineEndTick() - 1));
        pageStartTick = pageStartForTick(previewTick);
    }

    private void finishTimelineScrub(double mouseX) {
        updateTimelineScrub(mouseX);
        timelineScrubbing = false;
        boolean resume = resumePreviewAfterScrub;
        resumePreviewAfterScrub = false;
        if (resume) {
            togglePreview();
        } else {
            setStatus(I18n.get("music_battle.l10n.editor.preview_position", previewTick));
        }
    }

    /**
     * Within a few pixels of the yellow line, over the grid or the sliver above it that the line overhangs. The
     * timeline bar above that is the scrubber's, so a press there still jumps the whole song.
     */
    private boolean overPlayhead(double mouseX, double mouseY) {
        if (mouseY < TOP_BAR) {
            return false;
        }
        float playheadTick = previewPlaying ? currentPreviewTick() : previewTick;
        if (playheadTick < pageStartTick || playheadTick >= pageStartTick + pageTicks()) {
            return false;
        }
        Grid grid = grid();
        // The line is drawn from x - 1 to x + 2, so its middle is half a pixel right of x.
        double lineCenter = timelineTickX(grid, playheadTick) + 0.5D;
        return Math.abs(mouseX - lineCenter) <= PLAYHEAD_GRAB_TOLERANCE
                && mouseY > grid.y - 2 && mouseY < grid.y + grid.height + 2;
    }

    /** Silent while the line moves, as with the scrubber: restarting the stream on every pixel would stutter. */
    private void beginPlayheadDrag() {
        pendingNotePlacement = false;
        playheadDragging = true;
        resumePreviewAfterScrub = previewPlaying;
        if (previewPlaying) {
            stopPreview(false);
        }
        // A press at the grid's edge must leave it before the page turns, or grabbing the line at a page start
        // would flip back a page at once.
        dragPageTurnArmed = false;
    }

    /** Snaps to the grid step; Shift places it on any tick, the way Shift frees other timeline moves from the grid. */
    private void updatePlayheadDrag(double mouseX) {
        double effectiveMouseX = turnTimelinePageWhileDragging(mouseX);
        double exactTick = cursorExactTick(effectiveMouseX);
        int tick = hasShiftDown() ? (int) Math.round(exactTick) : snapExactTick(exactTick);
        int pageEnd = pageStartTick + pageTicks();
        if (tick >= pageEnd) {
            // The page's own end is the next page's start, where this page would no longer draw the line.
            tick = hasShiftDown() ? pageEnd - 1 : gridTickAtColumn(gridColumns() - 1);
        }
        previewTick = Math.max(pageStartTick, tick);
    }

    private void finishPlayheadDrag() {
        playheadDragging = false;
        dragPageTurnArmed = true;
        boolean resume = resumePreviewAfterScrub;
        resumePreviewAfterScrub = false;
        if (resume) {
            togglePreview();
        } else {
            setStatus(I18n.get("music_battle.l10n.editor.preview_position", previewTick));
        }
    }

    private void showPlayheadCursor(boolean hand) {
        if (hand == playheadCursorShown || this.minecraft == null) {
            return;
        }
        long window = this.minecraft.getWindow().getWindow();
        if (window == 0L) {
            return;
        }
        if (hand && playheadHandCursor == 0L) {
            playheadHandCursor = GLFW.glfwCreateStandardCursor(GLFW.GLFW_HAND_CURSOR);
        }
        // Zero restores the arrow.
        GLFW.glfwSetCursor(window, hand ? playheadHandCursor : 0L);
        playheadCursorShown = hand;
    }

    private int timelineEndTick() {
        int noteEnd = notes.stream().mapToInt(note -> note.tick() + Math.max(stepTicks(), note.durationTicks())).max().orElse(0);
        int effectEnd = effects.stream().mapToInt(effect -> effect.tick() + Math.max(stepTicks(), effect.durationTicks())).max().orElse(0);
        return Math.max(pageTicks(), Math.max(previewDurationTicks, Math.max(noteEnd, effectEnd)));
    }

    private void loadPreview() {
        stopPreview(false);
        try {
            Path path = audioPath();
            previewDurationTicks = MusicBattleTracks.readOggDurationTicks(path);
            previewTick = 0;
            pageStartTick = 0;
            setStatus("OK: " + I18n.get("music_battle.l10n.editor.ogg_loaded", previewDurationTicks / 20));
        } catch (IOException exception) {
            setStatus("ERR: " + exception.getMessage());
        }
    }

    private void togglePreview() {
        if (previewPlaying) {
            stopPreview(false);
            return;
        }

        try {
            Path path = audioPath();
            if (previewDurationTicks <= 0) {
                previewDurationTicks = MusicBattleTracks.readOggDurationTicks(path);
            }
            previewTick = Mth.clamp(previewTick, 0, Math.max(0, previewDurationTicks - 1));
            ClientAudioAnalyzer.activate(path);
            previewStartedAtTick = previewTick;
            previewStartedAtNanos = 0L;
            previewAudioStartedSignalNanos = 0L;
            preparePreviewNoteQueue(previewTick);
            previewPlaying = true;
            previewSound = new ExternalOggSoundInstance("editor_preview", path.getFileName().toString(), path, previewTick,
                    true, 0.0D, 0.0D, 0.0D, SoundInstance.Attenuation.NONE, effectivePreviewVolume());
            this.minecraft.getSoundManager().play(previewSound);
            updatePlayButtons("music_battle.l10n.editor.button_stop");
            setStatus("OK: " + I18n.get("music_battle.l10n.editor.audio_loading", previewTick));
        } catch (IOException exception) {
            setStatus("ERR: " + exception.getMessage());
        }
    }

    private void startGameplayPreview() {
        snapshotDraft();
        stopPreview(false);
        try {
            Path path = audioPath();
            if (previewDurationTicks <= 0) {
                previewDurationTicks = MusicBattleTracks.readOggDurationTicks(path);
            }
            int startTick = Mth.clamp(pageStartTick, 0, Math.max(0, previewDurationTicks - 1));
            Path previewPath = null;
            if (draftPreview != null && !draftPreview.isBlank()) {
                Path candidate = resolveRawOrAbsolute(draftPreview);
                if (candidate != null && Files.isRegularFile(candidate)) {
                    previewPath = candidate;
                }
            }
            this.minecraft.setScreen(new TrackGameplayPreviewScreen(
                    this,
                    path,
                    previewPath,
                    draftTitle == null || draftTitle.isBlank() ? I18n.get("music_battle.l10n.editor.gameplay_preview_title") : draftTitle,
                    bpm(),
                    volume(),
                    previewDurationTicks,
                    startTick,
                    owner,
                    List.copyOf(notes),
                    List.copyOf(effects),
                    disableStageBars,
                    hideStandardDimming
            ));
        } catch (IOException exception) {
            setStatus("ERR: " + exception.getMessage());
        }
    }

    private void stopPreview(boolean reset) {
        if (previewPlaying) {
            previewTick = Math.max(0, Mth.floor(currentPreviewTick()));
        }
        if (previewSound != null) {
            if (previewSound instanceof ExternalOggSoundInstance external) {
                external.requestStop();
            }
            this.minecraft.getSoundManager().stop(previewSound);
            previewSound = null;
        }
        previewPlaying = false;
        previewStartedAtNanos = 0L;
        previewAudioStartedSignalNanos = 0L;
        previewNoteQueue = List.of();
        previewNoteCursor = 0;
        if (reset) {
            previewTick = 0;
        }
        updatePlayButtons("music_battle.l10n.editor.button_play");
    }

    private float currentPreviewTick() {
        long startedAt = previewAudioStartedSignalNanos;
        if (!previewPlaying || startedAt <= 0L) {
            return previewTick;
        }
        // Use the audio-thread signal immediately, including frames before Screen.tick().
        // PCM already applies calibration; shifting this clock too would cancel that offset.
        long elapsed = Math.max(0L, System.nanoTime() - startedAt);
        return previewStartedAtTick + elapsed / 50_000_000.0F;
    }

    static void onStreamingSoundStarted(SoundInstance startedSound) {
        if (Minecraft.getInstance().screen instanceof TrackEditorScreen editor && editor.previewSound == startedSound) {
            editor.previewAudioStartedSignalNanos = System.nanoTime();
        }
    }

    private void preparePreviewNoteQueue(int startTick) {
        previewNoteQueue = notes.stream()
                .sorted(Comparator.comparingInt(BattleNote::tick).thenComparingInt(BattleNote::lane).thenComparingInt(BattleNote::owner))
                .toList();
        previewNoteCursor = 0;
        while (previewNoteCursor < previewNoteQueue.size() && previewNoteQueue.get(previewNoteCursor).tick() < startTick) {
            previewNoteCursor++;
        }
        lastPreviewEffectTick = startTick - 0.01F;
        java.util.Arrays.fill(previewLaneHitNanos, 0L);
    }

    private void triggerPreviewNoteEffects(float exactTick) {
        long now = System.nanoTime();
        while (previewNoteCursor < previewNoteQueue.size()) {
            BattleNote note = previewNoteQueue.get(previewNoteCursor);
            if (note.tick() > exactTick) {
                break;
            }
            previewNoteCursor++;
            if (note.tick() <= lastPreviewEffectTick) {
                continue;
            }
            int lane = Mth.clamp(note.lane(), 0, NOTE_LANES - 1);
            previewLaneHitNanos[lane] = now;
            previewLaneHitOwner[lane] = Mth.clamp(note.owner(), 0, 1);
            previewLaneHitTick[lane] = note.tick();
        }
        lastPreviewEffectTick = exactTick;
    }

    private void updatePlayButtons(String labelKey) {
        if (playButton != null) {
            playButton.setMessage(Component.translatable(labelKey));
        }
        if (fxPlayButton != null) {
            fxPlayButton.setMessage(Component.translatable(labelKey));
        }
    }

    private void setEditorVolume(float volume, boolean restartPreview) {
        editorVolume = Mth.clamp(volume, 0.0F, 2.0F);
        if (volumeSlider != null) {
            volumeSlider.setVolume(editorVolume);
        }
        if (previewSound instanceof ExternalOggSoundInstance external) {
            external.setVolume(effectivePreviewVolume());
        }
    }

    private float effectivePreviewVolume() {
        return Mth.clamp(volume() * ClientBattleSettings.musicVolume(), 0.0F, 4.0F);
    }

    private void refreshPreviewVolume() {
        if (previewSound instanceof ExternalOggSoundInstance external) {
            external.setVolume(effectivePreviewVolume());
        }
    }

    static void refreshOpenPreviewVolume() {
        if (Minecraft.getInstance().screen instanceof TrackEditorScreen editor) {
            editor.refreshPreviewVolume();
        }
    }

    static void refreshOpenPreviewCalibration() {
        if (Minecraft.getInstance().screen instanceof TrackEditorScreen editor && editor.previewPlaying) {
            editor.stopPreview(false);
            editor.togglePreview();
        }
    }

    private Path audioPath() throws IOException {
        String value = audioBox != null ? audioBox.getValue() : draftAudio;
        String raw = ClientTrackExporter.cleanPathInput(value);
        if (raw.isBlank()) {
            throw new ClientTrackExporter.Problem("music_battle.l10n.editor.ogg_path_empty");
        }
        Path path = requireRawOrAbsolute(raw);
        if (!Files.isRegularFile(path)) {
            throw new ClientTrackExporter.Problem("music_battle.l10n.editor.ogg_not_found", MusicBattleTracks.displayPath(path));
        }
        if (!path.getFileName().toString().toLowerCase().endsWith(".ogg")) {
            throw new ClientTrackExporter.Problem("music_battle.l10n.editor.audio_not_ogg");
        }
        return path;
    }

    Path effectPreviewAudioPath() {
        try {
            return audioPath();
        } catch (IOException ignored) {
            return null;
        }
    }

    private void drawPreviewImage(GuiGraphics graphics) {
        EditorLayout layout = layout();
        int size = layout.previewSize;
        if (size <= 0) {
            return;
        }
        if (previewBox == null || !previewBox.visible) {
            return;
        }
        // Beside the picture's path: the wide form gives it a card at the panel's right edge, the narrow one a
        // thumbnail at the end of the field.
        int x = layout.twoColumns ? layout.left + layout.panelWidth - size : previewBox.getX() + previewBox.getWidth() + 4;
        int y = previewBox.getY();
        ResourceLocation texture = previewTexture();
        if (texture != null) {
            graphics.blit(texture, x, y, 0.0F, 0.0F, size, size, size, size);
        } else {
            RoundedRect.fill(graphics, x, y, size, size, Palette.RADIUS_SM, Palette.BG_3);
            ru.gora.musicbattle.client.ui.UiIcons.drawCentered(graphics, ru.gora.musicbattle.client.ui.UiIcon.MUSIC,
                    x + size / 2.0F, y + size / 2.0F, Math.min(12.0F, size * 0.6F), Palette.TEXT_MUTE);
        }
    }

    private ResourceLocation previewTexture() {
        String value = previewBox == null ? "" : previewBox.getValue().trim();
        if (value.isBlank()) {
            releasePreviewTexture();
            return null;
        }
        if (previewTexture != null && value.equals(previewTextureFile)) {
            return previewTexture;
        }
        if (value.equals(previewTextureFailed)) {
            return null;
        }
        releasePreviewTexture();
        try {
            Path path = resolveRawOrAbsolute(value);
            if (path == null || !Files.isRegularFile(path)) {
                return null;
            }
            NativeImage image = ClientPreviewProcessor.loadNativeImage(path);
            previewTexture = ru.gora.musicbattle.MusicBattleMod.id("editor_preview/" + Math.abs(value.hashCode()));
            previewTextureFile = value;
            this.minecraft.getTextureManager().register(previewTexture, new DynamicTexture(image));
            return previewTexture;
        } catch (IOException | RuntimeException exception) {
            // This runs inside render, where anything thrown crashes the game, and ImageIO's readers throw unchecked
            // exceptions on damaged pictures. A file that would not decode is not decoded again on every frame.
            previewTextureFailed = value;
            return null;
        }
    }

    private void choosePreview(EditorField target) {
        try {
            Path directory = MusicBattleTracks.rawDirectory();
            Files.createDirectories(directory);
            List<Path> files;
            try (var stream = Files.list(directory)) {
                files = stream.filter(Files::isRegularFile)
                        .filter(ClientPreviewProcessor::isSupported)
                        .sorted(Comparator.comparing(path -> path.getFileName().toString(), String.CASE_INSENSITIVE_ORDER))
                        .toList();
            }
            if (files.isEmpty()) {
                setStatus("ERR: " + I18n.get("music_battle.l10n.editor.no_raw_files", "PNG/JPG/JPEG/GIF/BMP", directory));
                return;
            }
            String current = target.getValue() == null ? "" : target.getValue().trim();
            int index = -1;
            for (int i = 0; i < files.size(); i++) {
                if (files.get(i).getFileName().toString().equalsIgnoreCase(current)) index = i;
            }
            Path chosen = files.get((index + 1) % files.size());
            setBoxValueAtStart(target, chosen.getFileName().toString());
            releasePreviewTexture();
            String gifNotice = chosen.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".gif")
                    ? I18n.get("music_battle.l10n.editor.text_136")
                    : "";
            setStatus(I18n.get("music_battle.l10n.editor.image_selected", chosen.getFileName(), gifNotice));
        } catch (IOException exception) {
            setStatus("ERR: " + exception.getMessage());
        }
    }

    private void releasePreviewTexture() {
        if (previewTexture != null && this.minecraft != null) {
            this.minecraft.getTextureManager().release(previewTexture);
        }
        previewTexture = null;
        previewTextureFile = "";
        previewTextureFailed = "";
    }

    private void importChart() {
        stopPreview(false);
        try {
            String typed = ClientTrackExporter.cleanPathInput(importBox.getValue());
            if (typed.isEmpty()) {
                throw new ClientTrackExporter.Problem("music_battle.l10n.editor.chart_path_empty");
            }
            Path path = requireRawOrAbsolute(typed);
            if (!Files.isRegularFile(path)) {
                throw new ClientTrackExporter.Problem("music_battle.l10n.editor.chart_not_found", MusicBattleTracks.displayPath(path));
            }
            String lower = path.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
            ClientFnfChartImporter.ImportedChart chart;
            String importedAudio = "";
            String importedPreview = "";
            ClientOsuManiaImporter.AudioLeftBehind audioLeftBehind = null;
            ClientOsuManiaImporter.PendingSong pendingSong = null;
            if (lower.endsWith(".json")) {
                chart = ClientFnfChartImporter.importChart(path.toString());
            } else {
                if (osuDifficulties.isEmpty()) {
                    refreshOsuDifficulties();
                }
                int difficultyIndex = selectedOsuDifficulty >= 0 && selectedOsuDifficulty < osuDifficulties.size()
                        ? osuDifficulties.get(selectedOsuDifficulty).index()
                        : selectedOsuDifficulty;
                // The song is converted after this returns, on a worker: ffmpeg used to run right here, in the click,
                // and froze the game for the whole conversion.
                ClientOsuManiaImporter.ImportedOsuChart imported = ClientOsuManiaImporter.importChartDeferringSong(path, difficultyIndex);
                chart = imported.chart();
                importedAudio = imported.audioFile();
                importedPreview = imported.previewFile();
                audioLeftBehind = imported.audioLeftBehind();
                pendingSong = imported.pendingSong();
            }
            // Any import replaces the notes, so a song still converting for an earlier one must not land in the box.
            int importGeneration = ++songImportGeneration;
            setBoxValueAtStart(idBox, chart.id());
            setBoxValueAtStart(titleBox, chart.title());
            setBoxValueAtStart(descriptionBox, chart.description());
            setBoxValueAtStart(bpmBox, Integer.toString(chart.bpm()));
            // An imported chart carries no first beat of its own; one left from the previous song would misplace the grid.
            setBoxValueAtStart(offsetBox, "0");
            setEditorVolume(1.0F, false);
            if (!importedAudio.isBlank()) {
                setBoxValueAtStart(audioBox, importedAudio);
            } else if (audioLeftBehind != null || pendingSong != null) {
                // The song box would otherwise still hold the previous chart's song, which would then be saved and
                // played under these notes.
                setBoxValueAtStart(audioBox, "");
            }
            if (!importedPreview.isBlank()) {
                setBoxValueAtStart(previewBox, importedPreview);
            }
            pushUndoSnapshot();
            notes.clear();
            notes.addAll(chart.notes());
            effects.clear();
            pendingEffectStartTick = -1;
            pendingEffectLane = -1;
            invalidateTimelineCache();
            previewTick = 0;
            pageStartTick = 0;
            int lastTick = notes.stream().mapToInt(note -> note.tick() + note.durationTicks()).max().orElse(0);
            previewDurationTicks = Math.max(previewDurationTicks, lastTick + 5 * 20);
            long holds = notes.stream().filter(note -> note.durationTicks() > 0).count();
            int over = ClientTrackExporter.notesOverLimit(notes.size());
            if (audioLeftBehind != null) {
                setStatus("WARN: " + audioLeftBehindStatus(notes.size(), audioLeftBehind));
            } else if (pendingSong != null) {
                String converting = I18n.get("music_battle.l10n.editor.import_audio_converting", notes.size(),
                        pendingSong.sourceExtension().replace(".", "").toUpperCase(java.util.Locale.ROOT));
                setStatus(over > 0
                        ? "WARN: " + converting + " " + I18n.get("music_battle.l10n.editor.too_many_notes_import",
                                notes.size(), ClientTrackExporter.MAX_NOTES, over)
                        : "OK: " + converting);
                convertImportedSong(pendingSong, importGeneration, notes.size());
            } else if (over > 0) {
                setStatus("WARN: " + I18n.get("music_battle.l10n.editor.too_many_notes_import",
                        notes.size(), ClientTrackExporter.MAX_NOTES, over));
            } else {
                setStatus("OK: " + I18n.get("music_battle.l10n.editor.imported", notes.size(), holds, audioImportStatus(importedAudio), previewImportStatus(importedPreview)));
            }
        } catch (IOException | RuntimeException exception) {
            // The importers read files from anywhere, and anything a broken file throws must end up here rather than
            // in the button handler, where Minecraft turns it into a crash that takes the unsaved chart with it.
            setStatus("ERR: " + MusicBattleTracks.safeMessage(exception));
        }
    }

    /**
     * Converts the song of an imported osu! map on a worker and puts it in the song box when it is ready, unless
     * another import has replaced the notes meanwhile or the player has already chosen a song for them.
     */
    private void convertImportedSong(ClientOsuManiaImporter.PendingSong pendingSong, int importGeneration, int noteCount) {
        net.minecraft.Util.nonCriticalIoPool().execute(() -> {
            ClientOsuManiaImporter.SongResult song = pendingSong.convert();
            Minecraft.getInstance().execute(() -> {
                if (importGeneration != songImportGeneration) {
                    return;
                }
                if (song.leftBehind() != null) {
                    setStatus("WARN: " + audioLeftBehindStatus(noteCount, song.leftBehind()));
                    return;
                }
                String typed = audioBox == null || audioBox.getValue() == null ? "" : audioBox.getValue().trim();
                if (typed.isEmpty()) {
                    setBoxValueAtStart(audioBox, song.audioFile());
                }
                setStatus("OK: " + I18n.get("music_battle.l10n.editor.import_audio_ready", song.audioFile()));
            });
        });
    }

    /** What happened to the song of an imported map that was not OGG, and what the player can do about it. */
    private static String audioLeftBehindStatus(int noteCount, ClientOsuManiaImporter.AudioLeftBehind left) {
        String format = left.sourceExtension().replace(".", "").toUpperCase(java.util.Locale.ROOT);
        String kept = left.keptFile().isBlank() ? "-" : left.keptFile();
        String text = left.ffmpegMissing()
                ? I18n.get("music_battle.l10n.editor.import_audio_needs_ffmpeg", noteCount, format, kept)
                : I18n.get("music_battle.l10n.editor.import_audio_failed", noteCount, format, left.reason(), kept);
        int over = ClientTrackExporter.notesOverLimit(noteCount);
        return over <= 0 ? text : text + " " + I18n.get("music_battle.l10n.editor.too_many_notes_import",
                noteCount, ClientTrackExporter.MAX_NOTES, over);
    }

    private void saveTrack() {
        snapshotDraft();
        try {
            Path output = ClientTrackExporter.exportRenamed(
                    savedTrackId,
                    draftId,
                    draftTitle,
                    draftDescription,
                    bpm(),
                    offsetMs(),
                    volume(),
                    draftAudio,
                    draftPreview,
                    disableStageBars,
                    hideStandardDimming,
                    notes,
                    effects
            );
            savedTrackId = ClientTrackExporter.normalizeId(draftId);
            Path savedDirectory = MusicBattleTracks.tracksDirectory().resolve(savedTrackId);
            if (draftAudio != null && !draftAudio.isBlank()) {
                draftAudio = savedDirectory.resolve(savedTrackId + ".ogg").toString();
                if (audioBox != null) audioBox.setValue(draftAudio);
            }
            if (draftPreview != null && !draftPreview.isBlank()) {
                draftPreview = savedDirectory.resolve(savedTrackId + "_preview.png").toString();
                if (previewBox != null) previewBox.setValue(draftPreview);
            }
            // music_battle_raw is the user's source/import folder. Saving an
            // autonomous track must never delete files from it, including the
            // OGG/PNG extracted from an osu archive.
            savedState = currentState();
            setStatus("OK: " + I18n.get("music_battle.l10n.editor.saved", output.getFileName()));
        } catch (IOException | RuntimeException exception) {
            setStatus("ERR: " + MusicBattleTracks.safeMessage(exception));
        }
    }

    private String audioImportStatus(String importedAudio) {
        if (importedAudio.isBlank()) {
            return "";
        }
        if (!importedAudio.toLowerCase().endsWith(".ogg")) {
            return I18n.get("music_battle.l10n.editor.import_audio_convert_suffix", importedAudio);
        }
        return I18n.get("music_battle.l10n.editor.import_audio_suffix", importedAudio);
    }

    private String previewImportStatus(String importedPreview) {
        return importedPreview.isBlank() ? "" : I18n.get("music_battle.l10n.editor.import_preview_suffix", importedPreview);
    }

    private void deleteTrack() {
        try {
            pushUndoSnapshot();
            ClientTrackExporter.deleteTrack(idBox.getValue());
            setStatus("OK: " + I18n.get("music_battle.l10n.editor.track_deleted", idBox.getValue()));
            notes.clear();
            effects.clear();
            pendingEffectStartTick = -1;
            pendingEffectLane = -1;
            invalidateTimelineCache();
        } catch (IOException exception) {
            setStatus("ERR: " + exception.getMessage());
        }
    }

    private int bpm() {
        try {
            String value = bpmBox != null ? bpmBox.getValue() : draftBpm;
            return Mth.clamp(Integer.parseInt(value == null ? "120" : value.trim()), 20, 400);
        } catch (NumberFormatException exception) {
            return 120;
        }
    }

    /** An empty or unreadable field is no offset, as in a chart that never had one. */
    private int offsetMs() {
        try {
            String value = offsetBox != null ? offsetBox.getValue() : draftOffset;
            return value == null || value.isBlank() ? 0 : ChartBeat.clampOffsetMs(Long.parseLong(value.trim()));
        } catch (NumberFormatException exception) {
            return 0;
        }
    }

    private float volume() {
        return Mth.clamp(editorVolume, 0.0F, 2.0F);
    }

    /** What a player is likely to drop in, beyond Ogg. Converted on the spot, so the editor only ever holds Ogg. */
    private static final List<String> AUDIO_EXTENSIONS = List.of(".ogg", ".mp3", ".wav", ".flac", ".m4a", ".aac", ".opus");

    /**
     * Picks the next audio file in the raw folder, converting it to Ogg first when it is anything else.
     *
     * The editor's preview player, its duration reader and the effect analyser all open the file straight from this
     * box, and every one of them speaks Ogg Vorbis only - which is why the picker used to show nothing but .ogg and
     * an mp3 dropped in the folder was simply invisible. Now it is picked up and converted, once, into the same
     * folder, and what lands in the box is the converted file.
     */
    private void chooseAudio(EditorField target) {
        try {
            List<Path> files = new ArrayList<>();
            for (String extension : AUDIO_EXTENSIONS) {
                files.addAll(rawFiles(extension));
            }
            files.sort(Comparator.comparing(path -> path.getFileName().toString(), String.CASE_INSENSITIVE_ORDER));
            // A source next to its own conversion would otherwise show up twice.
            List<Path> offered = new ArrayList<>();
            for (Path file : files) {
                String name = file.getFileName().toString();
                boolean convertedAlready = !name.toLowerCase(java.util.Locale.ROOT).endsWith(".ogg")
                        && Files.isRegularFile(file.resolveSibling(stripExtension(name) + ".ogg"));
                if (!convertedAlready) {
                    offered.add(file);
                }
            }
            if (offered.isEmpty()) {
                setStatus("ERR: " + I18n.get("music_battle.l10n.editor.no_audio_files", MusicBattleTracks.rawDirectory()));
                return;
            }
            String current = target.getValue() == null ? "" : target.getValue().trim();
            int index = -1;
            for (int i = 0; i < offered.size(); i++) {
                if (offered.get(i).getFileName().toString().equalsIgnoreCase(current)) {
                    index = i;
                    break;
                }
            }
            Path chosen = offered.get((index + 1) % offered.size());
            if (chosen.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".ogg")) {
                setBoxValueAtStart(target, chosen.getFileName().toString());
                refreshOsuDifficulties();
                setStatus("OK: " + I18n.get("music_battle.l10n.editor.selected", chosen.getFileName()));
                return;
            }
            convertAudioToOgg(target, chosen);
        } catch (IOException exception) {
            setStatus("ERR: " + exception.getMessage());
        }
    }

    /** Converts one file in the background, so a long encode never freezes the editor. */
    private void convertAudioToOgg(EditorField target, Path source) {
        Path converted = source.resolveSibling(stripExtension(source.getFileName().toString()) + ".ogg");
        setStatus("... " + I18n.get("music_battle.l10n.editor.converting_to_ogg", source.getFileName()));
        net.minecraft.Util.nonCriticalIoPool().execute(() -> {
            String error = null;
            try {
                ClientFfmpeg.convertToVorbis(source, converted, null, 120);
            } catch (IOException exception) {
                error = exception.getMessage();
            }
            String failure = error;
            Minecraft.getInstance().execute(() -> {
                if (failure != null) {
                    setStatus("ERR: " + failure);
                    return;
                }
                setBoxValueAtStart(target, converted.getFileName().toString());
                refreshOsuDifficulties();
                setStatus("OK: " + I18n.get("music_battle.l10n.editor.converted_to_ogg", converted.getFileName()));
            });
        });
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot <= 0 ? name : name.substring(0, dot);
    }

    private void chooseRaw(EditorField target, String extension) {
        try {
            List<Path> files = rawFiles(extension);
            if (files.isEmpty()) {
                setStatus("ERR: " + I18n.get("music_battle.l10n.editor.no_raw_files", extension, MusicBattleTracks.rawDirectory()));
                return;
            }
            String current = target.getValue() == null ? "" : target.getValue().trim();
            int index = -1;
            for (int i = 0; i < files.size(); i++) {
                if (files.get(i).getFileName().toString().equalsIgnoreCase(current)) {
                    index = i;
                    break;
                }
            }
            Path chosen = files.get((index + 1) % files.size());
            setBoxValueAtStart(target, chosen.getFileName().toString());
            refreshOsuDifficulties();
            setStatus("OK: " + I18n.get("music_battle.l10n.editor.selected", chosen.getFileName()));
        } catch (IOException exception) {
            setStatus("ERR: " + exception.getMessage());
        }
    }

    private void refreshOsuDifficulties() {
        osuDifficulties.clear();
        selectedOsuDifficulty = -1;
        try {
            String value = importBox.getValue() == null ? "" : importBox.getValue().trim();
            if (value.isBlank()) {
                updateOsuDifficultyButton();
                return;
            }
            Path path = resolveRawOrAbsolute(value);
            Path name = path == null ? null : path.getFileName();
            String lower = name == null ? "" : name.toString().toLowerCase(java.util.Locale.ROOT);
            if (lower.endsWith(".osu") || lower.endsWith(".osz")) {
                osuDifficulties.addAll(ClientOsuManiaImporter.listDifficulties(path));
                if (!osuDifficulties.isEmpty()) {
                    selectedOsuDifficulty = 0;
                }
            }
        } catch (IOException | RuntimeException exception) {
            // A damaged map is the player's file, not a reason to crash out of the button that picked it.
            setStatus("ERR: " + MusicBattleTracks.safeMessage(exception));
        }
        updateOsuDifficultyButton();
    }

    private void cycleOsuDifficulty() {
        if (osuDifficulties.isEmpty()) {
            refreshOsuDifficulties();
        }
        if (osuDifficulties.isEmpty()) {
            setStatus("ERR: " + I18n.get("music_battle.l10n.editor.osu_select_first"));
            return;
        }
        selectedOsuDifficulty = (selectedOsuDifficulty + 1) % osuDifficulties.size();
        updateOsuDifficultyButton();
        ClientOsuManiaImporter.OsuDifficulty difficulty = osuDifficulties.get(selectedOsuDifficulty);
        setStatus("OK: " + I18n.get("music_battle.l10n.editor.osu_difficulty", difficulty.title(), difficulty.noteCount()));
    }

    private void updateOsuDifficultyButton() {
        if (osuDifficultyButton != null) {
            osuDifficultyButton.setMessage(osuDifficultyLabel());
        }
    }

    private Component osuDifficultyLabel() {
        if (osuDifficulties.isEmpty() || selectedOsuDifficulty < 0 || selectedOsuDifficulty >= osuDifficulties.size()) {
            return Component.translatable("music_battle.l10n.editor.osu_difficulty_auto");
        }
        ClientOsuManiaImporter.OsuDifficulty difficulty = osuDifficulties.get(selectedOsuDifficulty);
        int maxWidth = osuDifficultyButton == null ? 320 : Math.max(48, osuDifficultyButton.getWidth() - 12);
        return Component.literal(this.font.plainSubstrByWidth("Osu: " + difficulty.title(), maxWidth));
    }

    private void chooseChart(EditorField target) {
        try {
            List<Path> files = new ArrayList<>();
            files.addAll(rawFiles(".json"));
            files.addAll(rawFiles(".osu"));
            files.addAll(rawFiles(".osz"));
            files.sort(Comparator.comparing(path -> path.getFileName().toString(), String.CASE_INSENSITIVE_ORDER));
            if (files.isEmpty()) {
                setStatus("ERR: " + I18n.get("music_battle.l10n.editor.no_raw_files", ".json/.osu/.osz", MusicBattleTracks.rawDirectory()));
                return;
            }
            String current = target.getValue() == null ? "" : target.getValue().trim();
            int index = -1;
            for (int i = 0; i < files.size(); i++) {
                if (files.get(i).getFileName().toString().equalsIgnoreCase(current)) {
                    index = i;
                    break;
                }
            }
            Path chosen = files.get((index + 1) % files.size());
            setBoxValueAtStart(target, chosen.getFileName().toString());
            refreshOsuDifficulties();
            setStatus("OK: " + I18n.get("music_battle.l10n.editor.selected", chosen.getFileName()));
        } catch (IOException exception) {
            setStatus("ERR: " + exception.getMessage());
        }
    }

    private List<Path> rawFiles(String extension) throws IOException {
        Path directory = MusicBattleTracks.rawDirectory();
        Files.createDirectories(directory);
        try (var stream = Files.list(directory)) {
            return stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase().endsWith(extension))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString(), String.CASE_INSENSITIVE_ORDER))
                    .toList();
        }
    }

    /**
     * The file a box points at, or {@code null} when what is typed there cannot be a file at all: a web address, or a
     * name this system's file system rejects. Every caller treats {@code null} as "not a file". The preview box goes
     * through here on every frame, and on Windows a pasted URL or a quoted path used to throw out of the render loop
     * and crash the game with the chart unsaved.
     */
    private Path resolveRawOrAbsolute(String typed) {
        String value = ClientTrackExporter.cleanPathInput(typed);
        Path path = ClientTrackExporter.parsePath(value);
        if (path == null) {
            return null;
        }
        if (!path.isAbsolute()) {
            Path raw = MusicBattleTracks.rawDirectory().resolve(path).toAbsolutePath().normalize();
            if (Files.exists(raw)) {
                return raw;
            }

            // Tracks are stored as <catalog>/<track id>/<asset>.  The editor used
            // to resolve a relative asset only against the old flat catalog root,
            // so an existing track could be published and played by the battle
            // loader while its editor preview still reported a missing OGG.
            Path trackAsset = resolveInitialTrackAsset(value);
            if (trackAsset != null) {
                return trackAsset;
            }
            path = MusicBattleTracks.tracksDirectory().resolve(path);
        }
        return path.toAbsolutePath().normalize();
    }

    /** {@link #resolveRawOrAbsolute}, for a click that needs a file: the reason it is not one becomes the error. */
    private Path requireRawOrAbsolute(String typed) throws IOException {
        Path path = resolveRawOrAbsolute(typed);
        if (path == null) {
            ClientTrackExporter.requirePath(ClientTrackExporter.cleanPathInput(typed));
            throw new ClientTrackExporter.Problem("music_battle.l10n.editor.path_unusable", typed);
        }
        return path;
    }

    private Path resolveInitialTrackAsset(String value) {
        if (initialTrack == null || value == null || value.isBlank()) {
            return null;
        }
        if (value.equals(initialTrack.audioFile())) {
            Path audio = MusicBattleTracks.audioPath(initialTrack).orElse(null);
            if (audio != null) {
                return audio;
            }
        }
        if (value.equals(initialTrack.previewFile())) {
            Path preview = MusicBattleTracks.previewPath(initialTrack).orElse(null);
            if (preview != null) {
                return preview;
            }
        }

        Path source = initialTrack.sourceDirectory().toAbsolutePath().normalize();
        Path definitionParent = initialTrack.definitionPath().toAbsolutePath().normalize().getParent();
        if (definitionParent != null) {
            Path parsed = ClientTrackExporter.parsePath(value);
            if (parsed == null) {
                return null;
            }
            Path besideDefinition = definitionParent.resolve(parsed).toAbsolutePath().normalize();
            if (besideDefinition.startsWith(source) && Files.isRegularFile(besideDefinition)) {
                return besideDefinition;
            }
        }
        return null;
    }

    /** The active cell's length rounded to whole ticks, for lengths only: positions come from the exact grid. */
    private int stepTicks() {
        return Math.max(1, (int) Math.round(exactCellTicks(gridPrecisionMultiplier())));
    }

    /** A cell is a quarter beat (an eighth on the fine grid); at 96 BPM a beat is 12.5 ticks and a cell 3.125. */
    private double exactCellTicks(int precision) {
        return TICKS_PER_MINUTE / (double) (bpm() * STANDARD_GRID_DIVISION * precision);
    }

    /**
     * The tick a cell's line belongs to, counting cells from the song's start. A chart holds whole ticks only, so
     * this is the nearest one (halves round up): at most 25 ms from the beat, well inside every hit window.
     */
    private int cellTick(long cell, int precision) {
        return ChartBeat.cellTick(offsetMs(), bpm(), STANDARD_GRID_DIVISION * precision, cell);
    }

    /** The nearest cell line to an exact time, on the active grid. */
    private long nearestCell(double exactTick, int precision) {
        return ChartBeat.nearestCell(offsetMs(), bpm(), STANDARD_GRID_DIVISION * precision, exactTick);
    }

    /**
     * A page is eight beats long, which is seldom a whole number of ticks (75 at 128 BPM, 68.57 at 140), so page n
     * covers the exact time from n * 9600 / BPM ticks, and holds the whole ticks that round into that span. Paging by
     * a rounded length instead would slide the bar lines a little further off the page edge on every page.
     */
    private int pageStart(long page) {
        return ChartBeat.pageStart(offsetMs(), bpm(), PAGE_BEATS, page);
    }

    private long pageOfTick(int tick) {
        return ChartBeat.pageOfTick(offsetMs(), bpm(), PAGE_BEATS, tick);
    }

    private long pageIndex() {
        return pageOfTick(pageStartTick);
    }

    /** The page counted from the one that starts on beat 1, which is where the grid's cell numbers start too. */
    private long pageFromBeatOne() {
        return ChartBeat.pageFromBeatOne(offsetMs(), bpm(), PAGE_BEATS, pageIndex());
    }

    private int pageStartForTick(int tick) {
        return pageStart(pageOfTick(tick));
    }

    private int neighbourPageStart(int direction) {
        return pageStart(Math.max(0L, pageIndex() + direction));
    }

    /** Whole ticks on this page; it differs by one tick between pages whenever eight beats are not whole ticks. */
    private int pageTicks() {
        long page = pageIndex();
        return pageStart(page + 1) - pageStart(page);
    }

    /** The exact time at the page's left edge, which may fall between two ticks. */
    private double pageOriginTick() {
        return ChartBeat.pageOriginTick(offsetMs(), bpm(), PAGE_BEATS, pageIndex());
    }

    private double pageSpanTicks() {
        return TICKS_PER_MINUTE * (double) PAGE_BEATS / bpm();
    }

    /** A BPM or offset typed into its field moves the page boundaries, so the page moves onto the one that holds it. */
    private void alignPageToBpm() {
        int aligned = pageStartForTick(pageStartTick);
        if (aligned != pageStartTick) {
            pageStartTick = aligned;
            invalidateTimelineCache();
        }
    }

    private int gridPrecisionMultiplier() {
        return Math.max(1, gridDivision() / STANDARD_GRID_DIVISION);
    }

    private int gridColumns() {
        return BASE_GRID_COLUMNS * gridPrecisionMultiplier();
    }

    private Grid grid() {
        EditorLayout layout = layout();
        int width = Math.min(this.width - 20, Math.max(Math.min(320, this.width - 20), layout.panelWidth));
        int x = this.width / 2 - width / 2;
        if (editorPage == EditorPage.FX && width > 140) {
            x += 52;
            width -= 52;
        }
        return new Grid(x, layout.gridY, width, layout.gridHeight);
    }

    private Component ownerLabel() {
        return Component.translatable("music_battle.l10n.editor.owner", owner == 0 ? "P1" : "P2");
    }

    private record Grid(int x, int y, int width, int height) {
    }

    private record EffectBounds(int left, int top, int right, int bottom, boolean leftEdgeVisible, boolean rightEdgeVisible) {
    }

    private record HoveredEffect(TrackEffect effect, int index, EffectBounds bounds) {
    }

    private record HoveredNote(BattleNote note, NoteBounds bounds) {
    }

    private record DocumentState(List<BattleNote> notes, List<TrackEffect> effects, List<String> fields, float volume,
                                 boolean disableStageBars, boolean hideStandardDimming) {
    }

    private record EditorSnapshot(List<BattleNote> notes, List<TrackEffect> effects) {
    }

    private record ClipboardData(List<BattleNote> notes, List<TrackEffect> effects, int sourceFxRow) {
    }

    private EditorLayout layout() {
        int maxPanelWidth = this.width >= 1200 ? 1220 : 920;
        int panelWidth = Mth.clamp(this.width - 40, 240, maxPanelWidth);
        boolean compactHeight = this.height < 500;
        boolean twoColumns = panelWidth >= 520;
        int gap = compactHeight ? 8 : 18;
        int columnWidth = twoColumns ? (panelWidth - gap) / 2 : panelWidth;
        int rows = editorPage == EditorPage.FX ? 0 : twoColumns ? 3 : 4;
        int rowHeight = compactHeight ? 31 : 34;
        int pad = compactHeight ? 6 : 10;
        int formTop = TOP_BAR + pad + 4 - scrollY;
        int controlsY = formTop + rows * rowHeight + (rows > 0 ? 2 : 0);
        int left = this.width / 2 - panelWidth / 2;
        // Six buttons a row as soon as they can carry their labels: three rows of controls instead of six.
        boolean wideControls = panelWidth >= 560;
        int buttonHeight = compactHeight ? 18 : 20;
        int rowStep = compactHeight ? 21 : 24;
        int controlRows = wideControls ? 3 : editorPage == EditorPage.FX ? 5 : 6;
        int controlsHeight = controlRows * rowStep - (rowStep - buttonHeight);
        int previewSize = editorPage != EditorPage.NOTES ? 0
                : twoColumns ? (compactHeight ? 42 : 56) : panelWidth >= 300 ? 18 : 0;
        int gridY = controlsY + controlsHeight + pad + (compactHeight ? 6 : 10) + 32;
        // The grid keeps one height however far the page is scrolled, sized so it fits the window whole.
        int gridHeight = Mth.clamp(this.height - TOP_BAR - 32 - pad * 2 - (compactHeight ? 6 : 10), 64, compactHeight ? 136 : 230);
        int contentBottom = gridY + scrollY + gridHeight + pad + 4;
        return new EditorLayout(left, panelWidth, columnWidth, gap, formTop, controlsY, controlsHeight, previewSize,
                twoColumns, compactHeight, wideControls, rowHeight, pad, buttonHeight, rowStep, gridY, gridHeight, contentBottom);
    }

    /**
     * Where each form field goes, or {@code null} on the FX page, which has no form. Kept apart from init() so the
     * editor-layout check can prove that every label fits its field at the sizes players use.
     */
    private FormSlots formSlots(EditorLayout layout) {
        if (editorPage != EditorPage.NOTES) {
            return null;
        }
        int rowHeight = layout.rowHeight;
        int boxY = layout.formTop + (layout.compactHeight ? 10 : 11);
        int left = layout.left;
        // BPM holds three digits and the slider only a knob, so the audio path gets the rest of the row: its label
        // names the folder and was the one cut off at 1280x720.
        int bpmWidth = 56;
        // The offset field carries the one-press "beat 1 here" button right after it.
        int offsetWidth = 50;
        int beatOneWidth = 20;
        int offsetBlock = offsetWidth + 2 + beatOneWidth;
        int volumeX = bpmWidth + 6;
        int volumeWidth = 90;
        int audioX = volumeX + volumeWidth + 8;
        if (layout.twoColumns) {
            int right = left + layout.columnWidth + layout.gap;
            // The picture card starts on the second row, so only the two rows beside it give up their width.
            int previewReserve = layout.previewSize > 0 ? layout.previewSize + 10 : 0;
            int rightInputWidth = Math.max(120, layout.columnWidth - previewReserve);
            // The BPM row has no width to spare at 640x360 (the audio label needs all of it), so here the offset ends the
            // description row instead.
            int offsetX = left + layout.columnWidth - offsetBlock;
            return new FormSlots(
                    new FieldSlot(left, boxY, layout.columnWidth),
                    new FieldSlot(left, boxY + rowHeight, layout.columnWidth),
                    new FieldSlot(left, boxY + rowHeight * 2, layout.columnWidth - offsetBlock - 6),
                    new FieldSlot(right, boxY, bpmWidth),
                    new FieldSlot(right + volumeX, boxY, volumeWidth),
                    new FieldSlot(right + audioX, boxY, Math.max(90, layout.columnWidth - audioX)),
                    new FieldSlot(right, boxY + rowHeight, rightInputWidth),
                    new FieldSlot(right, boxY + rowHeight * 2, rightInputWidth),
                    new FieldSlot(offsetX, boxY + rowHeight * 2, offsetWidth),
                    new FieldSlot(offsetX + offsetWidth + 2, boxY + rowHeight * 2, beatOneWidth));
        }
        // Four rows instead of six: on a small window every row the form saves is a row of the note grid.
        int half = (layout.panelWidth - layout.gap) / 2;
        int rightHalf = left + half + layout.gap;
        int otherHalf = layout.panelWidth - half - layout.gap;
        int previewReserve = layout.previewSize > 0 ? layout.previewSize + 6 : 0;
        // In one column the offset sits beside the BPM it belongs to; the audio path keeps the rest of the row.
        int offsetX = bpmWidth + 4;
        volumeX = offsetX + offsetBlock + 6;
        audioX = volumeX + volumeWidth + 8;
        return new FormSlots(
                new FieldSlot(left, boxY, half),
                new FieldSlot(rightHalf, boxY, otherHalf),
                new FieldSlot(left, boxY + rowHeight, layout.panelWidth),
                new FieldSlot(left, boxY + rowHeight * 2, bpmWidth),
                new FieldSlot(left + volumeX, boxY + rowHeight * 2, volumeWidth),
                new FieldSlot(left + audioX, boxY + rowHeight * 2, layout.panelWidth - audioX),
                new FieldSlot(left, boxY + rowHeight * 3, Math.max(80, half - previewReserve)),
                new FieldSlot(rightHalf, boxY + rowHeight * 3, otherHalf),
                new FieldSlot(left + offsetX, boxY + rowHeight * 2, offsetWidth),
                new FieldSlot(left + offsetX + offsetWidth + 2, boxY + rowHeight * 2, beatOneWidth));
    }

    private record FieldSlot(int x, int y, int width) {
    }

    private record FormSlots(FieldSlot id, FieldSlot title, FieldSlot description, FieldSlot bpm, FieldSlot volume,
                             FieldSlot audio, FieldSlot preview, FieldSlot chart, FieldSlot offset, FieldSlot beatOne) {
    }

    /** How far the page can scroll: zero whenever the whole editor fits the window. */
    private int maxScroll() {
        return Math.max(0, layout().contentBottom - this.height);
    }

    private record EditorLayout(
            int left,
            int panelWidth,
            int columnWidth,
            int gap,
            int formTop,
            int controlsY,
            int controlsHeight,
            int previewSize,
            boolean twoColumns,
            boolean compactHeight,
            boolean wideControls,
            int rowHeight,
            int pad,
            int buttonHeight,
            int rowStep,
            int gridY,
            int gridHeight,
            int contentBottom
    ) {
    }

    private record FieldLabel(Component text, int x, int boxY, int width) {
    }

    /**
     * A form field drawn and edited by the toolkit's {@link TextField}, so the editor's text is the same smooth font
     * as its buttons and sits centred in its box. It is a widget only so the screen's focus, Tab order and click
     * routing treat it like any other control.
     */
    private static final class EditorField extends AbstractWidget {
        private final TextField field = TextField.singleLine();

        private EditorField(int x, int y, int width, int height, Component label, Component hint) {
            super(x, y, width, height, label);
            field.setMaxLength(512);
            field.setPlaceholder(hint.getString());
            field.setBounds(x, y, width, height);
        }

        String getValue() {
            return field.value();
        }

        /** Shows the start of the text: a long path is recognised by its beginning, not by its extension. */
        void setValue(String value) {
            String text = value == null ? "" : value;
            if (!text.equals(field.value())) {
                // Emptying first brings the caret back to the start, which TextField keeps otherwise.
                field.setValue("");
                field.setValue(text);
            }
        }

        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            field.setBounds(getX(), getY(), getWidth(), getHeight());
            field.render(graphics, mouseX, mouseY);
            if (!isFocused() && isHovered()) {
                RoundedRect.stroke(graphics, getX(), getY(), getWidth(), getHeight(), Palette.RADIUS_SM, 1.0F,
                        Palette.fade(Palette.ACCENT, 0.45F));
            }
        }

        @Override
        public void setFocused(boolean focused) {
            super.setFocused(focused);
            field.setFocused(focused);
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (!this.active || !this.visible || !isMouseOver(mouseX, mouseY)) {
                return false;
            }
            field.setFocused(true);
            field.mouseClicked(mouseX, mouseY, button);
            return button == 0;
        }

        @Override
        public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
            return field.mouseDragged(mouseX, mouseY, button);
        }

        @Override
        public boolean mouseReleased(double mouseX, double mouseY, int button) {
            return false;
        }

        @Override
        public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
            return isFocused() && field.keyPressed(keyCode, scanCode, modifiers);
        }

        @Override
        public boolean charTyped(char character, int modifiers) {
            return isFocused() && field.charTyped(character, modifiers);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            output.add(NarratedElementType.TITLE, Component.translatable("narration.edit_box", getMessage()));
        }
    }

    private enum EffectPlacementMode {
        POINT("music_battle.l10n.editor.text_138"),
        LENGTH("music_battle.l10n.editor.text_139"),
        RANGE("music_battle.l10n.editor.text_140");

        private final String displayName;

        EffectPlacementMode(String displayName) {
            this.displayName = displayName;
        }
    }

    private static EffectPlacementMode parseEffectMode(String value) {
        if ("length".equals(value)) return EffectPlacementMode.LENGTH;
        if ("range".equals(value)) return EffectPlacementMode.RANGE;
        return EffectPlacementMode.POINT;
    }

    private enum EditorPage {
        NOTES,
        FX
    }

    private enum DragMode {
        MOVE,
        RESIZE_LEFT,
        RESIZE_RIGHT
    }

    private record NoteBounds(int left, int right, int top, int bottom,
                              int headLeft, int headRight, boolean headVisible,
                              boolean leftEdgeVisible, boolean rightEdgeVisible) {
    }

    private final class VolumeSlider extends AbstractSliderButton {
        private VolumeSlider(int x, int y, int width, int height) {
            super(x, y, width, height, Component.empty(), editorVolume / 2.0D);
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.translatable("music_battle.l10n.editor.volume", String.format(java.util.Locale.ROOT, "%.2f", valueToVolume())));
        }

        @Override
        protected void applyValue() {
            editorVolume = valueToVolume();
            refreshPreviewVolume();
        }

        @Override
        public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            int x = getX();
            int y = getY();
            int width = getWidth();
            int height = getHeight();
            // The same rounded field as the text boxes beside it; the value is drawn in the label row above.
            RoundedRect.fill(graphics, x, y, width, height, Palette.RADIUS_SM, Palette.BG_3);
            if (isFocused() || isHovered()) {
                RoundedRect.stroke(graphics, x, y, width, height, Palette.RADIUS_SM, 1.0F,
                        isFocused() ? Palette.ACCENT : Palette.fade(Palette.ACCENT, 0.45F));
            }
            float trackLeft = x + 7;
            float trackWidth = width - 14;
            float centerY = y + height / 2.0F;
            float knobX = trackLeft + trackWidth * (float) this.value;
            RoundedRect.fill(graphics, trackLeft, centerY - 1.5F, trackWidth, 3, 1.5F, Palette.BG_1);
            RoundedRect.fill(graphics, trackLeft, centerY - 1.5F, Math.max(3, knobX - trackLeft), 3, 1.5F, Palette.ACCENT);
            RoundedRect.fill(graphics, knobX - 4, centerY - 4, 8, 8, 4, Palette.TEXT_1);
        }

        private float valueToVolume() {
            return Mth.clamp((float) (this.value * 2.0D), 0.0F, 2.0F);
        }

        private void setVolume(float volume) {
            this.value = Mth.clamp(volume, 0.0F, 2.0F) / 2.0D;
            updateMessage();
        }
    }
}

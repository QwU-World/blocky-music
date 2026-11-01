package ru.gora.musicbattle.client;

import com.mojang.blaze3d.platform.NativeImage;
import ru.gora.musicbattle.client.ui.Palette;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
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
import ru.gora.musicbattle.client.ui.UiTooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipPositioner;
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
    /** The beat-1 field's label; the editor-layout check measures it across the field and its "1" button. */
    private static final String OFFSET_LABEL = "music_battle.l10n.editor.beat_one_at";
    /** The form's fixed field widths: BPM holds three digits, the offset a few, and the volume slider only a knob. */
    private static final int BPM_WIDTH = 56;
    private static final int OFFSET_WIDTH = 50;
    private static final int BEAT_ONE_WIDTH = 20;
    private static final int VOLUME_WIDTH = 90;
    /** Where the audio path starts in the wide form's right column and in the narrow form's BPM row. */
    private static final int WIDE_AUDIO_X = BPM_WIDTH + 6 + VOLUME_WIDTH + 8;
    private static final int NARROW_AUDIO_X = BPM_WIDTH + 4 + OFFSET_WIDTH + 2 + BEAT_ONE_WIDTH + 6 + VOLUME_WIDTH + 8;
    /**
     * The narrowest the audio path's field may be: it holds its label ("Audio from music_battle_raw") and its hint in
     * either language at every GUI scale. Below it the narrow form gives the path a row of its own, and the wide form
     * stays one column. editor-layout measures every label and hint against its field with the game's own metrics.
     */
    private static final int AUDIO_FIELD_MIN = 116;
    /** P1 blue and P2 red, the sides the battle's default panels colour that way; one hue each in every state. */
    private static final int P1_COLOR = 0xFF55DDF5;
    private static final int P2_COLOR = 0xFFFF6680;
    /** Selection outlines and the hold grab bars; gold, so they never read as either player's colour. */
    private static final int SELECTION_COLOR = Palette.ST_FEATURED;
    /**
     * The line of beat 1 and the "Bar 1" over it: the accent, bright among the grey bar lines. Not yellow, which is
     * the playhead's colour, so the line that only marks where bar 1 starts is never taken for the one that can be
     * dragged.
     */
    private static final int BEAT_ONE_COLOR = Palette.ACCENT;
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
    private StatusLevel statusLevel = StatusLevel.INFO;
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
    private ChartEdits.Clipboard clipboard = ChartEdits.Clipboard.EMPTY;
    /** The fine line the copied group's first item last stood on: where it was copied from, then each paste of it. */
    private long clipboardAnchor;
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
    /** A Ctrl press on the grid: a still release toggles what is under it, a drag draws a selection rectangle. */
    private boolean selectPressPending;
    /**
     * The button that press came with, so its drag and release are recognised: the left button, or on a Mac the right
     * one, because Minecraft turns a physical Ctrl + left press there into a right click (see macControlClick).
     */
    private int selectPressButton;
    private boolean selectDragging;
    /** Ctrl+Shift adds the rectangle to what was selected; Ctrl alone replaces the selection with it. */
    private boolean selectAdds;
    private double selectPressX;
    private double selectPressY;
    private double selectDragX;
    private double selectDragY;
    /** The editor as the Ctrl press found it: the selection a rectangle adds to, and the step Ctrl+Z returns to. */
    private EditorSnapshot selectionBefore;
    /** The beat-1 offset the player was last told about, so a typed change can say what it did; -1 before the first. */
    private int shownOffsetMs = -1;
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
    /**
     * The hints of the beat-1 field, its "1" button and the grid button, drawn in the toolkit's look; see
     * {@link #setTooltipForNextRenderPass}. Each is made once and kept: init() runs again on every page scroll, and a
     * new vanilla tooltip per run would pile up in the holder.
     */
    private final UiTooltip.Deferred tooltips = new UiTooltip.Deferred();
    private Tooltip beatOneAtTooltip;
    private Tooltip beatOneTooltip;
    private final Tooltip[] gridTooltips = new Tooltip[GRID_DIVISIONS.length];
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
            // The field and its "1" button are one control, so their label names what both set and spans them both.
            FieldSlot beatOne = slots.beatOne();
            offsetBox = addBox(slots.offset(), Component.translatable(OFFSET_LABEL), "music_battle.l10n.editor.hint_offset", defaultOffset());
            fieldLabels.set(fieldLabels.size() - 1, new FieldLabel(Component.translatable(OFFSET_LABEL), slots.offset().x(),
                    slots.offset().y(), beatOne.x() + beatOne.width() - slots.offset().x()));
            if (beatOneAtTooltip == null) {
                beatOneAtTooltip = tooltips.create(Component.translatable("music_battle.l10n.editor.beat_one_at_hint"));
                beatOneTooltip = tooltips.create(Component.translatable("music_battle.l10n.editor.beat_one_hint"));
            }
            offsetBox.setTooltip(beatOneAtTooltip);
            Button beatOneButton = this.addRenderableWidget(FancyButton.create(Component.translatable("music_battle.l10n.editor.beat_one"),
                    button -> setBeatOneAtPlayhead(), beatOne.x(), beatOne.y(), beatOne.width(), 18));
            beatOneButton.setTooltip(beatOneTooltip);
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
                ? ClientTrackEffects.sample(effects, renderTick, bpm(), 1.0F, 1.0F,
                        (float) ChartBeat.originTicks(offsetMs()))
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
                if (editorPage == EditorPage.NOTES && !selectDragging) {
                    renderHoveredNoteHandles(graphics, mouseX, mouseY);
                }
                drawSelectionRectangle(graphics);
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
        // Both tips go in the tip layer, above the controls' own: in it the BPM and beat-1 boxes' shadowed values,
        // drawn 0.03 forward, printed through a card lying over them.
        if (editorPage == EditorPage.FX) {
            ClientUiForeground.renderTip(graphics.pose(), graphics::flush,
                    () -> renderEffectTooltip(graphics, mouseX, mouseY));
        }
        // The hints of the beat-1 controls and the grid button, after everything so the card lies over the controls.
        ClientUiForeground.renderTip(graphics.pose(), graphics::flush,
                () -> tooltips.draw(graphics, mouseX, mouseY, this.width, this.height, getFocused()));
        // The Leave dialog renders this screen behind itself; only the screen on top may change the cursor.
        if (this.minecraft != null && this.minecraft.screen == this) {
            showPlayheadCursor(playheadDragging || (draggedNote == null && draggedEffectIndex < 0 && !timelineScrubbing
                    && !selectPressPending && overPlayhead(mouseX, mouseY) && (editorPage == EditorPage.FX
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
        reportTypedOffset();
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
            setStatus(StatusLevel.OK, I18n.get("music_battle.l10n.editor.audio_started", previewStartedAtTick));
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
        // Ctrl (on a Mac Ctrl or Cmd) with the left button selects: a click toggles what is under it, a drag draws a
        // rectangle.
        if ((button == 0 && EditorShortcuts.shortcutDown() || macControlClick(button)) && overSelectionArea(mouseX, mouseY)) {
            beginSelectionPress(mouseX, mouseY, button);
            return true;
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
                    changeSelection(() -> {
                        selectedNotes.clear();
                        selectedEffects.clear();
                        selectedNotes.add(hovered.note());
                    });
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
        if (toggleGridNote(mouseX, mouseY, button)) {
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    /** The grid, or a selected hold's handles where they overhang it: where a Ctrl press selects rather than clicks. */
    private boolean overSelectionArea(double mouseX, double mouseY) {
        return insideGrid(mouseX, mouseY) || (editorPage == EditorPage.FX
                ? hoveredEffectInfo(mouseX, mouseY) != null : hoveredNoteInfo(mouseX, mouseY) != null);
    }

    /**
     * On a Mac, Minecraft hands a screen a physical Ctrl + left press as a right click (MouseHandler's fake right
     * button), and Screen.hasControlDown() there means Cmd. Without this, Ctrl+click turned a note into a hold and
     * Ctrl+drag selected nothing, so a Mac player could select only with Cmd. Ctrl held at the press is what makes it
     * a selection, the same physical key the guide names and the one the letter shortcuts take (EditorShortcuts).
     */
    private static boolean macControlClick(int button) {
        return button == GLFW.GLFW_MOUSE_BUTTON_RIGHT && EditorShortcuts.onMac() && EditorShortcuts.controlDown();
    }

    private void beginSelectionPress(double mouseX, double mouseY, int button) {
        pendingNotePlacement = false;
        clearNoteSelectionDrag();
        clearEffectDrag();
        selectPressPending = true;
        selectPressButton = button;
        selectDragging = false;
        selectAdds = EditorShortcuts.shiftDown();
        selectPressX = mouseX;
        selectPressY = mouseY;
        selectDragX = mouseX;
        selectDragY = mouseY;
        selectionBefore = snapshotTimeline();
    }

    /** The rectangle replaces the selection, or with Shift joins it, live while the mouse moves. */
    private void applySelectionRectangle() {
        double left = Math.min(selectPressX, selectDragX);
        double right = Math.max(selectPressX, selectDragX);
        double top = Math.min(selectPressY, selectDragY);
        double bottom = Math.max(selectPressY, selectDragY);
        selectedNotes.clear();
        selectedEffects.clear();
        if (selectAdds && selectionBefore != null) {
            selectedNotes.addAll(selectionBefore.selectedNotes());
            selectedEffects.addAll(selectionBefore.selectedEffects());
        }
        if (editorPage == EditorPage.FX) {
            selectedEffects.addAll(effectsInRectangle(left, top, right, bottom));
        } else {
            selectedNotes.addAll(notesInRectangle(left, top, right, bottom));
        }
    }

    /**
     * A still Ctrl click toggles the note or effect under it, as before; a drag keeps what its rectangle took. Either
     * way the selection it replaced becomes a Ctrl+Z step, so a wrong selection is undone like a wrong edit.
     */
    private void finishSelectionPress(double mouseX, double mouseY) {
        EditorSnapshot before = selectionBefore;
        if (selectDragging) {
            selectDragX = mouseX;
            selectDragY = mouseY;
            applySelectionRectangle();
        } else if (editorPage == EditorPage.FX) {
            HoveredEffect hovered = hoveredEffectInfo(selectPressX, selectPressY);
            if (hovered != null) {
                toggleSelection(selectedEffects, hovered.effect());
            }
        } else {
            HoveredNote hovered = hoveredNoteInfo(selectPressX, selectPressY);
            if (hovered != null) {
                toggleSelection(selectedNotes, hovered.note());
            }
        }
        boolean dragged = selectDragging;
        clearSelectionPress();
        if (before != null && selectionDiffers(before)) {
            pushUndoSnapshot(before);
        }
        if (dragged) {
            setStatus(StatusLevel.OK, I18n.get("music_battle.l10n.editor.selected_count", selectedNotes.size() + selectedEffects.size()));
        }
    }

    private void clearSelectionPress() {
        selectPressPending = false;
        selectDragging = false;
        selectAdds = false;
        selectionBefore = null;
    }

    /**
     * The edited player's notes whose drawn shape the rectangle touches, a hold anywhere from its press to its release:
     * what is seen is what is taken. The other player's notes, drawn faintly for reference, are never taken.
     */
    private Set<BattleNote> notesInRectangle(double left, double top, double right, double bottom) {
        Grid grid = grid();
        int laneHeight = Math.max(1, grid.height / NOTE_LANES);
        int step = stepTicks();
        ensureTimelineCache();
        Set<BattleNote> inside = new HashSet<>();
        for (BattleNote note : cachedPageNotes) {
            if (note.owner() != owner) {
                continue;
            }
            NoteBounds bounds = noteBounds(note, grid, laneHeight, step);
            if (bounds.left() <= right && bounds.right() >= left && bounds.top() <= bottom && bounds.bottom() >= top) {
                inside.add(note);
            }
        }
        return inside;
    }

    /** The effects in the visible rows whose drawn bar meets the rectangle. */
    private Set<TrackEffect> effectsInRectangle(double left, double top, double right, double bottom) {
        Grid grid = grid();
        int laneHeight = Math.max(1, grid.height / FX_LANES);
        int step = stepTicks();
        ensureTimelineCache();
        Set<TrackEffect> inside = new HashSet<>();
        for (TrackEffect effect : cachedPageEffects) {
            EffectBounds bounds = effectBounds(effect, grid, laneHeight, step);
            if (bounds.left() <= right && bounds.right() >= left && bounds.top() <= bottom && bounds.bottom() >= top) {
                inside.add(effect);
            }
        }
        return inside;
    }

    private void drawSelectionRectangle(GuiGraphics graphics) {
        if (!selectDragging) {
            return;
        }
        Grid grid = grid();
        float left = (float) Mth.clamp(Math.min(selectPressX, selectDragX), grid.x, grid.x + grid.width);
        float right = (float) Mth.clamp(Math.max(selectPressX, selectDragX), grid.x, grid.x + grid.width);
        float top = (float) Mth.clamp(Math.min(selectPressY, selectDragY), grid.y, grid.y + grid.height);
        float bottom = (float) Mth.clamp(Math.max(selectPressY, selectDragY), grid.y, grid.y + grid.height);
        if (right - left < 1.0F || bottom - top < 1.0F) {
            return;
        }
        RoundedRect.fill(graphics, left, top, right - left, bottom - top, Palette.RADIUS_SM, Palette.withAlpha(Palette.ACCENT, 0.16F));
        RoundedRect.stroke(graphics, left, top, right - left, bottom - top, Palette.RADIUS_SM, 1.0F, Palette.ACCENT);
    }

    /** Ctrl+A: every note of the player being edited that starts on this page, or every effect in the visible rows. */
    private void selectPage() {
        int pageEnd = pageStartTick + pageTicks();
        changeSelection(() -> {
            selectedNotes.clear();
            selectedEffects.clear();
            if (editorPage == EditorPage.FX) {
                ensureTimelineCache();
                for (TrackEffect effect : cachedPageEffects) {
                    if (effect.tick() >= pageStartTick && effect.tick() < pageEnd) {
                        selectedEffects.add(effect);
                    }
                }
            } else {
                selectedNotes.addAll(ChartEdits.pageSelection(notes, owner, pageStartTick, pageEnd));
            }
        });
        setStatus(StatusLevel.OK, I18n.get("music_battle.l10n.editor.selected_page", selectedNotes.size() + selectedEffects.size()));
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
        setStatus(StatusLevel.OK, I18n.get("music_battle.l10n.editor.visible_rows", firstVisibleFxRow + 1, firstVisibleFxRow + FX_LANES));
        return true;
    }

    /** Scrolls the whole editor on a window too short for it; widgets are rebuilt at their new places. */
    private boolean scrollPage(double wheel) {
        int max = maxScroll();
        if (max <= 0 || wheel == 0.0D || timelineScrubbing || playheadDragging || playheadPressPending
                || draggedNote != null || draggedEffectIndex >= 0 || selectPressPending) {
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
        // Ctrl on every system, and on a Mac Cmd too: Screen.hasControlDown() there is Cmd only, and the guide says Ctrl.
        boolean shortcut = EditorShortcuts.shortcutDown();
        if (shortcut && keyCode == GLFW.GLFW_KEY_Z) {
            if (EditorShortcuts.shiftDown()) {
                redo();
            } else {
                undo();
            }
            return true;
        }
        if (shortcut && keyCode == GLFW.GLFW_KEY_C) {
            copySelection();
            return true;
        }
        if (shortcut && keyCode == GLFW.GLFW_KEY_V) {
            pasteSelection();
            return true;
        }
        if (shortcut && keyCode == GLFW.GLFW_KEY_A) {
            selectPage();
            return true;
        }
        // Backspace too: a click no longer removes a note, so the key is the way to, and a Mac laptop's "delete" key
        // sends Backspace (its forward Delete needs fn). A focused text field took the key above, before this.
        if (keyCode == GLFW.GLFW_KEY_DELETE || keyCode == GLFW.GLFW_KEY_BACKSPACE) {
            deleteSelection();
            return true;
        }
        if (!selectedNotes.isEmpty() || !selectedEffects.isEmpty()) {
            if (keyCode == GLFW.GLFW_KEY_LEFT || keyCode == GLFW.GLFW_KEY_RIGHT || keyCode == GLFW.GLFW_KEY_UP || keyCode == GLFW.GLFW_KEY_DOWN) {
                int lineSteps = keyCode == GLFW.GLFW_KEY_LEFT ? -1 : keyCode == GLFW.GLFW_KEY_RIGHT ? 1 : 0;
                int laneDelta = keyCode == GLFW.GLFW_KEY_UP ? -1 : keyCode == GLFW.GLFW_KEY_DOWN ? 1 : 0;
                moveSelection(lineSteps, laneDelta);
                return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        resetEffectTooltipDwell();
        if (selectPressPending && button == selectPressButton) {
            if (!selectDragging && Math.hypot(mouseX - selectPressX, mouseY - selectPressY) >= 3.0D) {
                selectDragging = true;
            }
            if (selectDragging) {
                selectDragX = mouseX;
                selectDragY = mouseY;
                applySelectionRectangle();
            }
            return true;
        }
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
            setStatus(StatusLevel.OK, I18n.get("music_battle.l10n.editor.text_001"));
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
        if (selectPressPending && button == selectPressButton) {
            finishSelectionPress(mouseX, mouseY);
            return true;
        }
        if (button == 0 && timelineScrubbing) {
            finishTimelineScrub(mouseX);
            return true;
        }
        if (button == 0 && playheadDragging) {
            finishPlayheadDrag();
            return true;
        }
        if (button == 0 && playheadPressPending) {
            // It never moved: the same click the grid gets anywhere else, a note or an effect placed under it.
            playheadPressPending = false;
            toggleGridNote(playheadPressX, playheadPressY, 0);
            return true;
        }
        if (pendingNotePlacement && button == pendingNoteButton) {
            boolean place = Math.hypot(mouseX - pendingNoteMouseX, mouseY - pendingNoteMouseY) < 3.0D;
            double clickX = pendingNoteMouseX;
            double clickY = pendingNoteMouseY;
            pendingNotePlacement = false;
            if (place) {
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
        /** True while the editor draws itself behind the dialog; see {@link #setTooltipForNextRenderPass}. */
        private boolean drawingEditor;

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
            behind(() -> editor.render(graphics, -1, -1, partialTick));
            graphics.flush();
            // The editor draws its panels on a raised layer (ClientUiForeground, z 800), and depth testing kept them
            // in front of anything drawn later at the screen's own depth - this dialog included, which is why the
            // warning showed up behind the editor. Clearing depth puts the whole editor behind the dialog.
            com.mojang.blaze3d.systems.RenderSystem.clear(org.lwjgl.opengl.GL11.GL_DEPTH_BUFFER_BIT,
                    net.minecraft.client.Minecraft.ON_OSX);
            graphics.fill(0, 0, this.width, this.height, 0xA0080610);
        }

        /** Runs {@code draw} as the editor behind the dialog: any tip its widgets ask for meanwhile is dropped. */
        void behind(Runnable draw) {
            drawingEditor = true;
            try {
                draw.run();
            } finally {
                drawingEditor = false;
            }
        }

        /**
         * A widget asks the screen on top for its tip, and with the dialog open that is this dialog, not the editor. The
         * editor is drawn with the pointer away (-1, -1), but a control the keyboard had reached (Tab, then Escape to
         * leave) still counts as focused, so its hint came up in vanilla's frame over the dialog's warning. Behind the
         * dialog the editor shows no hints; the dialog's own tips are untouched.
         */
        @Override
        public void setTooltipForNextRenderPass(Tooltip tooltip, ClientTooltipPositioner positioner, boolean focused) {
            if (!drawingEditor) {
                super.setTooltipForNextRenderPass(tooltip, positioner, focused);
            }
        }

        @Override
        public void setTooltipForNextRenderPass(List<net.minecraft.util.FormattedCharSequence> tooltip,
                                                ClientTooltipPositioner positioner, boolean focused) {
            if (!drawingEditor) {
                super.setTooltipForNextRenderPass(tooltip, positioner, focused);
            }
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
            setStatus(StatusLevel.WARN, I18n.get("music_battle.l10n.editor.opened_truncated", onDisk, notes.size()));
            return;
        }
        setStatus(StatusLevel.OK, I18n.get("music_battle.l10n.editor.opened", initialTrack.title()));
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
     * Puts beat 1 where the playhead stands, so bar 1 starts there and the grid's bar lines fall on the music from
     * there. It used to fold the time into the first bar, which kept the grid but put bar 1 a few bars before the
     * place the player had chosen; now the time is kept as it is unless a chart cannot store it (past 12 s). The
     * page turns to the one that starts on beat 1, so the bar-1 line is in sight, and the status says that
     * only the grid moved.
     */
    private void setBeatOneAtPlayhead() {
        float tick = previewPlaying ? currentPreviewTick() : previewTick;
        double wantedMs = tick * (double) ChartBeat.MS_PER_TICK;
        int offset = ChartBeat.storableOffsetMs(wantedMs, bpm());
        if (offsetBox != null) {
            offsetBox.setValue(Integer.toString(offset));
        }
        draftOffset = Integer.toString(offset);
        shownOffsetMs = offset;
        alignPageToBpm();
        pageStartTick = pageStartForTick(Math.max(0, Math.round(tick)));
        invalidateTimelineCache();
        if (Math.abs(wantedMs - offset) < 1.0D) {
            setStatus(StatusLevel.OK, I18n.get("music_battle.l10n.editor.beat_one_set", seconds(offset)));
        } else {
            setStatus(StatusLevel.WARN, I18n.get("music_battle.l10n.editor.offset_folded", Math.round(wantedMs), offset));
        }
    }

    /**
     * Says what a beat-1 value typed into the field did, once it settles on a new number: a field that silently moved
     * the whole grid under the notes was what made the offset look broken.
     */
    private void reportTypedOffset() {
        int offset = offsetMs();
        if (offset == shownOffsetMs) {
            return;
        }
        boolean first = shownOffsetMs < 0;
        shownOffsetMs = offset;
        if (first || offsetBox == null || !offsetBox.isFocused()) {
            return;
        }
        String typed = offsetBox.getValue() == null ? "" : offsetBox.getValue().trim();
        Double typedMs = parseOffset(typed);
        if (typedMs == null) {
            setStatus(StatusLevel.WARN, I18n.get("music_battle.l10n.editor.offset_unreadable", typed));
        } else if (Math.abs(typedMs - offset) >= 1.0D) {
            setStatus(StatusLevel.WARN, I18n.get("music_battle.l10n.editor.offset_folded", Math.round(typedMs), offset));
        } else {
            setStatus(StatusLevel.OK, I18n.get("music_battle.l10n.editor.offset_set", seconds(offset)));
        }
    }

    /** Milliseconds as seconds with three decimals, the way the status messages show beat 1. */
    private static String seconds(int milliseconds) {
        return String.format(java.util.Locale.ROOT, "%.3f", milliseconds / 1000.0D);
    }

    /** What the beat-1 field holds, or {@code null} when it holds something that is not a number. */
    private static Double parseOffset(String value) {
        if (value == null || value.isBlank()) {
            return 0.0D;
        }
        try {
            double parsed = Double.parseDouble(value.trim());
            return Double.isFinite(parsed) ? parsed : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private String defaultBpm() {
        return draftBpm != null ? draftBpm : Integer.toString(initialTrack == null ? 120 : initialTrack.bpm());
    }

    private String defaultAssetPath(String fileName) {
        return fileName == null ? "" : fileName;
    }

    private void addControlButtons(EditorLayout layout) {
        for (ControlSlot slot : controlSlots(layout)) {
            addControl(slot);
        }
    }

    /**
     * One button of the controls panel: what it does, by id, and where it sits. {@link #controlSlots} places them and
     * {@link #controlLabels} says every label each can show, so editor-layout measures the words against the very
     * widths the screen builds.
     */
    private record ControlSlot(String id, int x, int y, int width, int height) {
    }

    /**
     * Where the controls go: six columns on a wide panel, thirds on a narrow one. The two FX switches and the osu!
     * difficulty take the widest cells there are - their labels are the longest - and on a narrow panel each switch
     * takes a whole row. In a third, "Disable default bars" was cut in English up to 417 units and "Отключить
     * стандартные полосы" up to 617, and in a sixth "Сложность osu: авто" from 600 to 761.
     */
    private List<ControlSlot> controlSlots(EditorLayout layout) {
        List<ControlSlot> out = new ArrayList<>();
        int left = layout.left;
        int y = layout.controlsY;
        int h = layout.buttonHeight;
        int rowStep = layout.rowStep;
        if (layout.wideControls) {
            int gap = layout.compactHeight ? 6 : 10;
            int col = Math.max(64, (layout.panelWidth - gap * 5) / 6);
            int[] x = new int[6];
            for (int i = 0; i < x.length; i++) {
                x[i] = left + i * (col + gap);
            }
            int two = col * 2 + gap;
            int three = col * 3 + gap * 2;
            if (editorPage == EditorPage.NOTES) {
                String[] first = {"owner", "page_previous", "page_next", "audio", "image", "chart"};
                for (int i = 0; i < first.length; i++) {
                    out.add(new ControlSlot(first[i], x[i], y, col, h));
                }
                y += rowStep;
                String[] second = {"load", "play", "import", "game_preview", "owners_swap", "grid"};
                for (int i = 0; i < second.length; i++) {
                    out.add(new ControlSlot(second[i], x[i], y, col, h));
                }
                y += rowStep;
                out.add(new ControlSlot("fx_page", x[0], y, two, h));
                out.add(new ControlSlot("osu_difficulty", x[2], y, two, h));
                out.add(new ControlSlot("clear_selection", x[4], y, two, h));
            } else {
                String[] first = {"notes_page", "page_previous", "page_next", "load", "fx_play", "effect_mode"};
                for (int i = 0; i < first.length; i++) {
                    out.add(new ControlSlot(first[i], x[i], y, col, h));
                }
                y += rowStep;
                out.add(new ControlSlot("fx_brush", x[0], y, two, h));
                out.add(new ControlSlot("game_preview", x[2], y, two, h));
                out.add(new ControlSlot("grid", x[4], y, col, h));
                out.add(new ControlSlot("clear", x[5], y, col, h));
                y += rowStep;
                out.add(new ControlSlot("stage_bars", x[0], y, three, h));
                out.add(new ControlSlot("standard_dimming", x[3], y, three, h));
            }
            return out;
        }

        int gap = 6;
        int third = Math.max(48, (layout.panelWidth - gap * 2) / 3);
        int wide = third * 2 + gap;
        int full = third * 3 + gap * 2;
        int[] x = {left, left + third + gap, left + (third + gap) * 2};
        if (editorPage == EditorPage.NOTES) {
            // File work first, under the fields it fills; play and paging last, right above the grid, so a scrolled
            // small window shows the transport and the notes together.
            out.add(new ControlSlot("audio", x[0], y, third, h));
            out.add(new ControlSlot("image", x[1], y, third, h));
            out.add(new ControlSlot("chart", x[2], y, third, h));
            y += rowStep;
            out.add(new ControlSlot("load", x[0], y, third, h));
            out.add(new ControlSlot("import", x[1], y, third, h));
            out.add(new ControlSlot("game_preview", x[2], y, third, h));
            y += rowStep;
            out.add(new ControlSlot("osu_difficulty", x[0], y, wide, h));
            out.add(new ControlSlot("owners_swap", x[2], y, third, h));
            y += rowStep;
            out.add(new ControlSlot("fx_page", x[0], y, wide, h));
            out.add(new ControlSlot("grid", x[2], y, third, h));
            y += rowStep;
            out.add(new ControlSlot("owner", x[0], y, third, h));
            out.add(new ControlSlot("clear_selection", x[1], y, wide, h));
            y += rowStep;
            out.add(new ControlSlot("page_previous", x[0], y, third, h));
            out.add(new ControlSlot("play", x[1], y, third, h));
            out.add(new ControlSlot("page_next", x[2], y, third, h));
        } else {
            out.add(new ControlSlot("notes_page", x[0], y, third, h));
            out.add(new ControlSlot("page_previous", x[1], y, third, h));
            out.add(new ControlSlot("page_next", x[2], y, third, h));
            y += rowStep;
            out.add(new ControlSlot("load", x[0], y, third, h));
            out.add(new ControlSlot("fx_play", x[1], y, third, h));
            out.add(new ControlSlot("effect_mode", x[2], y, third, h));
            y += rowStep;
            out.add(new ControlSlot("fx_brush", x[0], y, third, h));
            out.add(new ControlSlot("game_preview", x[1], y, wide, h));
            y += rowStep;
            out.add(new ControlSlot("stage_bars", x[0], y, full, h));
            y += rowStep;
            out.add(new ControlSlot("standard_dimming", x[0], y, full, h));
            y += rowStep;
            out.add(new ControlSlot("grid", x[0], y, third, h));
            out.add(new ControlSlot("clear_selection", x[1], y, wide, h));
        }
        return out;
    }

    /** Every label a control can show, in all its states; the osu! difficulty's chosen title is the player's own. */
    private static List<Component> controlLabels(String id) {
        String e = "music_battle.l10n.editor.";
        return switch (id) {
            case "owner" -> List.of(Component.translatable(e + "owner", "P1"), Component.translatable(e + "owner", "P2"));
            case "page_previous" -> List.of(Component.translatable(e + "page_previous"));
            case "page_next" -> List.of(Component.translatable(e + "page_next"));
            case "audio" -> List.of(Component.translatable(e + "button_audio"));
            case "image" -> List.of(Component.translatable(e + "button_image"));
            case "chart" -> List.of(Component.translatable(e + "button_chart"));
            case "load" -> List.of(Component.translatable(e + "button_load"));
            case "play", "fx_play" -> List.of(Component.translatable(e + "button_play"), Component.translatable(e + "button_stop"));
            case "import" -> List.of(Component.translatable(e + "button_import"));
            case "game_preview" -> List.of(Component.translatable(e + "button_game_preview"));
            case "owners_swap" -> List.of(Component.literal("P1 \u2194 P2"));
            case "grid" -> List.of(Component.translatable(e + "text_010"), Component.translatable(e + "text_011"));
            case "fx_page" -> List.of(Component.translatable(e + "text_002"), Component.translatable(e + "text_007"));
            case "osu_difficulty" -> List.of(Component.translatable(e + "osu_difficulty_auto"));
            case "clear_selection" -> List.of(Component.translatable(e + "text_003"));
            case "notes_page" -> List.of(Component.translatable(e + "text_004"));
            case "effect_mode" -> List.of(Component.translatable(e + "mode", Component.translatable(e + "text_138")),
                    Component.translatable(e + "mode", Component.translatable(e + "text_139")),
                    Component.translatable(e + "mode", Component.translatable(e + "text_140")));
            case "fx_brush" -> List.of(Component.translatable(e + "text_005"));
            case "clear" -> List.of(Component.translatable(e + "text_006"));
            case "stage_bars" -> List.of(Component.translatable(e + "text_014"), Component.translatable(e + "text_015"));
            case "standard_dimming" -> List.of(Component.translatable(e + "text_016"), Component.translatable(e + "text_017"));
            default -> throw new IllegalArgumentException("no control " + id);
        };
    }

    /** Builds the control a slot names, in the state the editor is in. */
    private void addControl(ControlSlot slot) {
        int x = slot.x();
        int y = slot.y();
        int w = slot.width();
        int h = slot.height();
        String e = "music_battle.l10n.editor.";
        String play = previewPlaying ? e + "button_stop" : e + "button_play";
        switch (slot.id()) {
            case "owner" -> addOwnerButton(x, y, w, h);
            case "page_previous" -> addTimelinePageButton(x, y, w, h, -1);
            case "page_next" -> addTimelinePageButton(x, y, w, h, 1);
            case "audio" -> audioSelectButton = this.addRenderableWidget(FancyButton.create(
                    Component.translatable(e + "button_audio"), button -> chooseAudio(audioBox), x, y, w, h));
            case "image" -> previewSelectButton = this.addRenderableWidget(FancyButton.create(
                    Component.translatable(e + "button_image"), button -> choosePreview(previewBox), x, y, w, h));
            case "chart" -> fnfSelectButton = this.addRenderableWidget(FancyButton.create(
                    Component.translatable(e + "button_chart"), button -> chooseChart(importBox), x, y, w, h));
            case "load" -> this.addRenderableWidget(FancyButton.create(Component.translatable(e + "button_load"),
                    button -> loadPreview(), x, y, w, h));
            case "play" -> playButton = this.addRenderableWidget(FancyButton.create(Component.translatable(play),
                    button -> togglePreview(), x, y, w, h));
            case "fx_play" -> fxPlayButton = this.addRenderableWidget(FancyButton.create(Component.translatable(play),
                    button -> togglePreview(), x, y, w, h));
            case "import" -> this.addRenderableWidget(FancyButton.create(Component.translatable(e + "button_import"),
                    button -> importChart(), x, y, w, h));
            case "game_preview" -> this.addRenderableWidget(FancyButton.create(Component.translatable(e + "button_game_preview"),
                    button -> startGameplayPreview(), x, y, w, h));
            case "owners_swap" -> this.addRenderableWidget(FancyButton.create(Component.literal("P1 \u2194 P2"),
                    button -> convertSelectedNoteOwners(), x, y, w, h));
            case "grid" -> gridDivisionButton = this.addRenderableWidget(createGridDivisionButton(x, y, w, h));
            case "fx_page" -> this.addRenderableWidget(FancyButton.create(Component.translatable(
                    layoutWideControls() ? e + "text_002" : e + "text_007"), button -> switchEditorPage(EditorPage.FX), x, y, w, h));
            case "osu_difficulty" -> osuDifficultyButton = this.addRenderableWidget(FancyButton.create(osuDifficultyLabel(),
                    button -> cycleOsuDifficulty(), x, y, w, h));
            case "clear_selection" -> this.addRenderableWidget(FancyButton.create(Component.translatable(e + "text_003"),
                    button -> clearSelection(), x, y, w, h));
            case "notes_page" -> this.addRenderableWidget(FancyButton.create(Component.translatable(e + "text_004"),
                    button -> switchEditorPage(EditorPage.NOTES), x, y, w, h));
            case "effect_mode" -> effectModeButton = this.addRenderableWidget(FancyButton.create(effectModeLabel(),
                    button -> cycleEffectMode(), x, y, w, h));
            case "fx_brush" -> this.addRenderableWidget(FancyButton.create(Component.translatable(e + "text_005"),
                    button -> openEffectTemplateEditor(), x, y, w, h));
            case "clear" -> this.addRenderableWidget(FancyButton.create(Component.translatable(e + "text_006"),
                    button -> clearSelection(), x, y, w, h));
            case "stage_bars" -> stageBarsButton = this.addRenderableWidget(FancyButton.create(stageBarsLabel(),
                    button -> toggleStageBars(), x, y, w, h));
            case "standard_dimming" -> standardDimmingButton = this.addRenderableWidget(FancyButton.create(standardDimmingLabel(),
                    button -> toggleStandardDimming(), x, y, w, h));
            default -> throw new IllegalArgumentException("no control " + slot.id());
        }
    }

    private boolean layoutWideControls() {
        return layout().wideControls;
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
        setStatus(StatusLevel.OK, gridDivision() == STANDARD_GRID_DIVISION
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
        int index = Mth.clamp(gridDivisionIndex, 0, GRID_DIVISIONS.length - 1);
        if (gridTooltips[index] == null) {
            int next = GRID_DIVISIONS[(index + 1) % GRID_DIVISIONS.length];
            gridTooltips[index] = tooltips.create(Component.translatable("music_battle.l10n.editor.grid_tooltip", gridDivision(), next));
        }
        return gridTooltips[index];
    }

    /**
     * A tooltip made by {@link #tooltips} is drawn by this screen in the toolkit's look, over every control; any other
     * stays vanilla's. The editor's own hints were the vanilla purple frame in the pixel font, the last ones in it.
     */
    @Override
    public void setTooltipForNextRenderPass(Tooltip tooltip, ClientTooltipPositioner positioner, boolean focused) {
        if (!tooltips.offer(tooltip, positioner)) {
            super.setTooltipForNextRenderPass(tooltip, positioner, focused);
        }
    }

    private int gridDivision() {
        return GRID_DIVISIONS[Mth.clamp(gridDivisionIndex, 0, GRID_DIVISIONS.length - 1)];
    }

    private Component gridDivisionLabel() {
        return Component.translatable(gridDivision() == STANDARD_GRID_DIVISION ? "music_battle.l10n.editor.text_010" : "music_battle.l10n.editor.text_011");
    }

    private void clearSelection() {
        int cleared = selectedNotes.size() + selectedEffects.size();
        changeSelection(() -> {
            selectedNotes.clear();
            selectedEffects.clear();
        });
        pendingNotePlacement = false;
        pendingEffectStartTick = -1;
        pendingEffectLane = -1;
        clearNoteSelectionDrag();
        clearEffectDrag();
        setStatus(StatusLevel.OK, cleared > 0 ? I18n.get("music_battle.l10n.editor.text_012") : I18n.get("music_battle.l10n.editor.text_013"));
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
            setStatus(StatusLevel.OK, I18n.get("music_battle.l10n.editor.text_018"));
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
        setStatus(StatusLevel.OK, I18n.get("music_battle.l10n.editor.text_019"));
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
            net.minecraft.util.FormattedCharSequence value = TextStyles.text(volumeText(), TextStyle.LABEL).getVisualOrderText();
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
        // Wrapped rather than cut: the messages that explain what to do (a song in a format the mod cannot read, a
        // chart that is too long) are the ones that do not fit on one line, and their second half is the useful part.
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
        int colour = switch (statusLevel) {
            case OK -> 0xFF80FFB0;
            case INFO -> Palette.TEXT_1;
            case WARN -> Palette.WARN;
            case ERROR -> Palette.DANGER;
        };
        RoundedRect.fill(graphics, x, y, boxWidth, boxHeight, Palette.RADIUS_MD, Palette.withAlpha(Palette.BG_2, 0.95F));
        RoundedRect.stroke(graphics, x, y, boxWidth, boxHeight, Palette.RADIUS_MD, 1.0F, Palette.LINE);
        for (int i = 0; i < lines.size(); i++) {
            TextStyles.queueCentered(graphics, lines.get(i), this.width / 2.0F,
                    y + 5 + i * lineHeight + (lineHeight - TextStyle.BODY.capHeight()) * 0.5F, colour);
        }
    }

    /**
     * How a status line reads: the level gives its colour, and the line itself carries no "OK:", "WARN:" or "ERR:" in
     * any language (they used to be English words at the start of every Russian line). A level word still found at the
     * start of a text, from a language file merged from elsewhere, is taken as the level's mark and left out.
     */
    private enum StatusLevel {
        OK, INFO, WARN, ERROR
    }

    /** "OK:", "WARN:" or "ERR:" at the start of a line: a level mark, never shown. */
    private static final java.util.regex.Pattern STATUS_MARK = java.util.regex.Pattern.compile("^\\s*(?:OK|WARN|ERR)\\s*:\\s*");

    /** The line as shown: without a level mark. */
    static String statusText(String message) {
        return message == null ? "" : STATUS_MARK.matcher(message).replaceFirst("");
    }

    private void setStatus(StatusLevel level, String message) {
        statusLevel = level;
        status = statusText(message);
        // Six seconds for a short line, longer for one that takes reading, so an explanation is not gone half-read.
        statusTicks = status.isBlank() ? 0 : 20 * Mth.clamp(status.length() / 18, 6, 20);
    }

    private void drawGrid(GuiGraphics graphics) {
        Grid grid = grid();
        if (editorPage == EditorPage.FX) {
            drawEffectLayerLabels(graphics, grid);
        }
        graphics.fill(grid.x, grid.y, grid.x + grid.width, grid.y + grid.height,
                Palette.withAlpha(Palette.BG_0, editorPage == EditorPage.FX ? 0.96F : 0.88F));
        int columns = gridColumns();
        int columnWidth = Math.max(1, grid.width / columns);
        int visibleLanes = editorPage == EditorPage.FX ? FX_LANES : NOTE_LANES;
        int laneHeight = Math.max(1, grid.height / visibleLanes);
        // Lines stand at the exact beat fractions, evenly spaced, so they follow the music at any BPM; notes are
        // drawn on the line they were placed on (timelineTickX). Bar lines are the brightest, then beats, and the line
        // of beat 1 itself is the accent: it is where the beat-1 field and its "1" button put bar 1.
        int precision = gridPrecisionMultiplier();
        int beatColumns = STANDARD_GRID_DIVISION * precision;
        long firstCell = pageFromBeatOne() * columns;
        for (int column = 0; column <= columns; column++) {
            int x = gridLineX(grid, column / (double) columns);
            if (firstCell + column == 0L) {
                graphics.fill(x, grid.y, x + 2, grid.y + grid.height, BEAT_ONE_COLOR);
                continue;
            }
            int color = column % (ChartBeat.BEATS_PER_BAR * beatColumns) == 0 ? Palette.withAlpha(Palette.TEXT_3, 0.8F)
                    : column % beatColumns == 0 ? Palette.withAlpha(Palette.TEXT_MUTE, 0.45F)
                    : column % precision == 0 ? Palette.withAlpha(Palette.LINE, 0.9F) : Palette.withAlpha(Palette.LINE, 0.45F);
            graphics.fill(x, grid.y, x + 1, grid.y + grid.height, color);
        }
        for (int lane = 0; lane <= visibleLanes; lane++) {
            int y = grid.y + lane * laneHeight;
            graphics.fill(grid.x, y, grid.x + grid.width, y + 1, Palette.withAlpha(Palette.LINE, 0.6F));
        }
        // With a first-beat offset the first page begins before the song does. That stretch is shaded over its lines,
        // so they read as out of use, and labelled, because a page that is mostly dark for no stated reason is what
        // made the offset look broken. The label goes on last: drawn under the lines, it had them cut through its
        // letters.
        if (pageOriginTick() < 0.0D) {
            int songStartX = gridLineX(grid, -pageOriginTick() / pageSpanTicks());
            graphics.fill(grid.x, grid.y, songStartX, grid.y + grid.height, 0xB3050407);
            net.minecraft.util.FormattedCharSequence beforeSong = TextStyles.translated(
                    "music_battle.l10n.editor.before_song", TextStyle.CAPTION).getVisualOrderText();
            if (TextStyles.width(beforeSong) + 8 <= songStartX - grid.x) {
                TextStyles.queueCentered(graphics, beforeSong, (grid.x + songStartX) / 2.0F,
                        TextStyles.centeredCapY(TextStyle.CAPTION, grid.y, grid.height), Palette.TEXT_MUTE);
            }
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
        drawTimelineHeader(graphics, grid);
    }

    /**
     * Above the grid: the number of each bar that starts on this page, counted from beat 1 (bar 1 in the accent, over
     * the accent line), and on the right the grid step and where the playhead stands. The page's own tick used to
     * lead this line; bar numbers are what shows the offset at work, since moving beat 1 renumbers the bars and moves
     * no note.
     */
    private void drawTimelineHeader(GuiGraphics graphics, Grid grid) {
        int columns = gridColumns();
        int barColumns = ChartBeat.BEATS_PER_BAR * STANDARD_GRID_DIVISION * gridPrecisionMultiplier();
        long firstCell = pageFromBeatOne() * columns;
        float y = grid.y - 25;
        float labelsRight = grid.x;
        float infoRight = grid.x + grid.width;
        for (int column = 0; column <= columns; column += barColumns) {
            long bar = ChartBeat.barOfCell(firstCell + column, STANDARD_GRID_DIVISION * gridPrecisionMultiplier());
            if (bar < 1L) {
                continue;
            }
            boolean beatOne = bar == 1L;
            net.minecraft.util.FormattedCharSequence label = TextStyles.translated("music_battle.l10n.editor.bar",
                    beatOne ? TextStyle.LABEL : TextStyle.CAPTION, bar).getVisualOrderText();
            float x = gridLineX(grid, column / (double) columns);
            if (column == columns) {
                // The page's closing line is the next page's first: on the lead-in page it is beat 1, named from its left.
                if (beatOne) {
                    TextStyles.queueRight(graphics, label, x - 3, y, BEAT_ONE_COLOR);
                    infoRight = x - 3 - TextStyles.width(label) - 8;
                }
                continue;
            }
            TextStyles.queue(graphics, label, x + 3, y, beatOne ? BEAT_ONE_COLOR : Palette.TEXT_2);
            labelsRight = Math.max(labelsRight, x + 3 + TextStyles.width(label));
        }
        String preview = I18n.get(previewPlaying ? "music_battle.l10n.editor.preview_playing" : "music_battle.l10n.editor.preview_stopped", previewTick);
        String info = timelineInfo(TextStyles.measurer(), I18n.get("music_battle.l10n.editor.timeline_step", exactStepLabel(), preview),
                infoRight - labelsRight - 10);
        if (info != null) {
            TextStyles.queueRight(graphics, TextStyles.text(info, TextStyle.CAPTION).getVisualOrderText(), infoRight, y, Palette.TEXT_3);
        }
    }

    /** Between the timeline header's facts in every language: the grid step, then where the playhead stands. */
    static final String TIMELINE_INFO_SEPARATOR = " · ";

    /**
     * What the timeline header's right end shows in {@code room}: its facts, the grid step and where the playhead
     * stands, as many of them as fit whole, or nothing. Cut with an ellipsis, a narrow editor read "Step: 2.5 ticks ·
     * stoppe…" and "Шаг в тиках: 2.5 · сто…".
     */
    static String timelineInfo(ru.gora.musicbattle.client.ui.TextMeasurer measurer, String text, float room) {
        String shown = ru.gora.musicbattle.client.workshop.layout.TextFit.wholeParts(measurer, text, TIMELINE_INFO_SEPARATOR,
                TextStyle.CAPTION, room);
        return shown != null && !shown.isEmpty()
                && ru.gora.musicbattle.client.workshop.layout.TextFit.fits(measurer, shown, TextStyle.CAPTION, room) ? shown : null;
    }

    /** The real cell length, 3.125 at 96 BPM: showing the rounded 3 would hide why lines and ticks differ. */
    private String exactStepLabel() {
        return new java.math.BigDecimal(exactCellTicks(gridPrecisionMultiplier()))
                .setScale(3, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    /**
     * A note is a rounded box from its line to the next. A hold is two boxes - the press, filled, and the release,
     * hollow - joined by its tail, because the battle releases a hold on the line its tail reaches: drawn only up to
     * that line, as before, every hold looked one cell shorter in the editor than it plays.
     */
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
        int base = note.owner() == 0 ? P1_COLOR : P2_COLOR;
        // The other player's part is a faint reference behind the active one, and on the FX page both are.
        float strength = editorPage == EditorPage.FX ? 0.27F : activeParty ? 1.0F : 0.33F;
        int color = Palette.withAlpha(base, strength);
        int top = bounds.top();
        int height = bounds.bottom() - bounds.top();
        if (note.durationTicks() > 0) {
            int from = bounds.headVisible() ? (bounds.headLeft() + bounds.headRight()) / 2 : grid.x;
            int to = bounds.releaseVisible() ? (bounds.releaseLeft() + bounds.releaseRight()) / 2 : grid.x + grid.width;
            int middle = top + height / 2;
            graphics.fill(from, middle - 2, Math.max(from + 1, to), middle + 3, Palette.withAlpha(base, strength * 0.6F));
            if (bounds.releaseVisible()) {
                float width = bounds.releaseRight() - bounds.releaseLeft();
                RoundedRect.fill(graphics, bounds.releaseLeft(), top, width, height, Palette.RADIUS_SM,
                        Palette.withAlpha(Palette.mix(Palette.BG_0, base, 0.35F), strength));
                RoundedRect.stroke(graphics, bounds.releaseLeft(), top, width, height, Palette.RADIUS_SM, 1.5F, color);
            }
        }
        if (bounds.headVisible()) {
            RoundedRect.fill(graphics, bounds.headLeft(), top, bounds.headRight() - bounds.headLeft(), height,
                    Palette.RADIUS_SM, color);
        }
        if (selectedNotes.contains(note)) {
            drawNoteHandles(graphics, bounds, true);
        }
        if (editorPage == EditorPage.NOTES && bounds.headVisible()) {
            net.minecraft.util.FormattedCharSequence label = TextStyles.text(note.owner() == 0 ? "P1" : "P2",
                    TextStyle.LABEL).getVisualOrderText();
            // A fine grid at a fast tempo leaves a box narrower than its label; the colour still says whose note it is.
            if (TextStyles.width(label) + 2 <= bounds.headRight() - bounds.headLeft()) {
                TextStyles.queueCentered(graphics, label, (bounds.headLeft() + bounds.headRight()) / 2.0F,
                        TextStyles.centeredCapY(TextStyle.LABEL, top, height),
                        activeParty ? Palette.BG_0 : Palette.withAlpha(Palette.BG_0, 0.6F));
            }
        }
    }

    /**
     * A selected note gets a gold outline; a hold also gets grab bars on the edges that resize it, which a hovered
     * hold shows on their own so the player can see where to take it.
     */
    private void drawNoteHandles(GuiGraphics graphics, NoteBounds bounds, boolean selected) {
        float top = bounds.top();
        float height = bounds.bottom() - bounds.top();
        if (selected) {
            RoundedRect.stroke(graphics, bounds.left() - 2, top - 2, bounds.right() - bounds.left() + 4, height + 4,
                    Palette.RADIUS_SM + 1.0F, 1.5F, SELECTION_COLOR);
        }
        int bar = selected ? SELECTION_COLOR : Palette.withAlpha(SELECTION_COLOR, 0.7F);
        if (bounds.leftEdgeVisible()) {
            RoundedRect.fill(graphics, bounds.left() - NOTE_HANDLE_OVERHANG, top, NOTE_HANDLE_OVERHANG, height, 1.5F, bar);
        }
        if (bounds.rightEdgeVisible()) {
            RoundedRect.fill(graphics, bounds.right(), top, NOTE_HANDLE_OVERHANG, height, 1.5F, bar);
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
            int subY = bounds.top();
            float width = Math.max(3, bounds.right() - x);
            float height = bounds.bottom() - subY;
            int color = effect.type().color();
            boolean dark = luminance(color) < 0.25F;
            // The rounded bars and gold outline of the note page, so a selection made with Ctrl+A or the Ctrl+drag
            // rectangle reads the same on both pages. A dark effect (cinematic bars are black) gets a rim, or it would
            // vanish into the grid behind it.
            RoundedRect.fill(graphics, x, subY, width, height, Palette.RADIUS_SM, color);
            if (dark) {
                RoundedRect.stroke(graphics, x, subY, width, height, Palette.RADIUS_SM, 1.0F, Palette.withAlpha(Palette.TEXT_3, 0.6F));
            }
            if (selectedEffects.contains(effect)) {
                RoundedRect.stroke(graphics, x - 2, subY - 2, width + 4, height + 4, Palette.RADIUS_SM + 1.0F, 1.5F,
                        SELECTION_COLOR);
            }
            if (columnWidth >= 12 && width > 12) {
                net.minecraft.util.FormattedCharSequence name = TextStyles.ellipsize(
                        TextStyles.text(effectName(effect.type()), TextStyle.CAPTION), width - 5);
                TextStyles.queue(graphics, name, x + 3, TextStyles.centeredCapY(TextStyle.CAPTION, subY, height),
                        dark ? Palette.TEXT_1 : Palette.BG_0);
            }
        }
        if (pendingEffectStartTick >= 0 && pendingEffectLane >= 0 && pendingEffectStartTick >= pageStartTick && pendingEffectStartTick < pageEnd) {
            int x = timelineTickX(grid, pendingEffectStartTick);
            int viewportLane = pendingEffectLane - firstVisibleFxRow;
            if (viewportLane >= 0 && viewportLane < FX_LANES) {
                int y = grid.y + viewportLane * laneHeight;
                RoundedRect.fill(graphics, x - 1, y + 2, 3, laneHeight - 3, 1.5F, SELECTION_COLOR);
            }
        }
    }

    /** Relative luminance of a colour, 0 for black to 1 for white, to pick a label that stays readable on it. */
    private static float luminance(int argb) {
        float red = ((argb >> 16) & 0xFF) / 255.0F;
        float green = ((argb >> 8) & 0xFF) / 255.0F;
        float blue = (argb & 0xFF) / 255.0F;
        return 0.2126F * red + 0.7152F * green + 0.0722F * blue;
    }

    private void drawEffectLayerLabels(GuiGraphics graphics, Grid grid) {
        int laneHeight = Math.max(1, grid.height / FX_LANES);
        for (int lane = 0; lane < FX_LANES; lane++) {
            int top = grid.y + lane * laneHeight;
            int color = lane % 2 == 0 ? Palette.withAlpha(Palette.BG_3, 0.85F) : Palette.withAlpha(Palette.BG_2, 0.85F);
            graphics.fill(grid.x - 48, top + 1, grid.x - 4, top + laneHeight, color);
            int globalRow = firstVisibleFxRow + lane + 1;
            TextStyles.queueCentered(graphics, TextStyles.translated("music_battle.l10n.editor.row", TextStyle.CAPTION, globalRow).getVisualOrderText(),
                    grid.x - 26, TextStyles.centeredCapY(TextStyle.CAPTION, top, laneHeight), Palette.TEXT_1);
        }
        int trackX = grid.x - 3;
        graphics.fill(trackX, grid.y, trackX + 2, grid.y + grid.height, Palette.withAlpha(Palette.BG_0, 0.7F));
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

    /** The hovered effect: a light rim, and on a ranged effect the gold grab bars of the ends that resize it. */
    private void drawEffectHandles(GuiGraphics graphics, EffectBounds bounds) {
        float width = Math.max(3, bounds.right() - bounds.left());
        float height = bounds.bottom() - bounds.top();
        RoundedRect.stroke(graphics, bounds.left() - 1, bounds.top() - 1, width + 2, height + 2, Palette.RADIUS_SM, 1.0F,
                Palette.TEXT_1);
        int bar = Palette.withAlpha(SELECTION_COLOR, 0.9F);
        if (bounds.leftEdgeVisible()) {
            RoundedRect.fill(graphics, bounds.left() - 1, bounds.top(), 4, height, 1.5F, bar);
        }
        if (bounds.rightEdgeVisible()) {
            RoundedRect.fill(graphics, bounds.left() + width - 3, bounds.top(), 4, height, 1.5F, bar);
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
     * drawn where it really is. Above 150 BPM two eighth lines can round to one tick: the active grid's line wins, and
     * of two lines on one grid the coarser (a beat, a half, a quarter), the same line copying and moving count from.
     */
    private int timelineTickX(Grid grid, float tick) {
        if (tick == Math.floor(tick)) {
            int finest = GRID_DIVISIONS[GRID_DIVISIONS.length - 1] / STANDARD_GRID_DIVISION;
            for (int precision : new int[] {gridPrecisionMultiplier(), finest}) {
                long cell = ChartBeat.lineOf(offsetMs(), bpm(), STANDARD_GRID_DIVISION * precision, (int) tick);
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
        // A note owns its whole box, from its line to the next and the full height of its lane, a hold every box from
        // its press to its release. A press a few pixels from the painted shape used to fall through to the grid,
        // whose click on an occupied line removed the note: clicking a note's edge deleted it. Neighbouring boxes do
        // not overlap, so no note steals another's click; the handles of a selected hold, which reach past its box,
        // are tried only after every box.
        if (insideBodyGrid) {
            for (int i = cachedPageNotes.size() - 1; i >= 0; i--) {
                BattleNote note = cachedPageNotes.get(i);
                if (note.owner() != owner || note.lane() != lane) continue;
                NoteBounds bounds = noteBounds(note, grid, laneHeight, step);
                if (insideRectangle(mouseX, mouseY, bounds.hitLeft(), bounds.hitTop(), bounds.hitRight(), bounds.hitBottom())) {
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

    /**
     * Where a note is drawn and where it can be grabbed. A tap is one box, from its line to the next line; a hold is
     * a box on its press line, a box on its release line and the tail between, so it covers its release line the way
     * the battle does, which releases it there.
     */
    private NoteBounds noteBounds(BattleNote note, Grid grid, int laneHeight, int step,
                                  int displayLane) {
        int pageEnd = pageStartTick + pageTicks();
        boolean hold = note.durationTicks() > 0;
        int release = note.tick() + note.durationTicks();
        int headLine = timelineTickX(grid, note.tick());
        int headNext = Math.max(headLine + 1, timelineTickX(grid, boxEndTick(note.tick(), step)));
        int releaseLine = timelineTickX(grid, release);
        int releaseNext = Math.max(releaseLine + 1, timelineTickX(grid, boxEndTick(release, step)));
        boolean headVisible = note.tick() >= pageStartTick && note.tick() < pageEnd;
        boolean releaseVisible = hold && release >= pageStartTick && release < pageEnd;
        int headLeft = Math.max(grid.x + 3, headLine + 3);
        int headRight = Math.min(grid.x + grid.width - 2, headLeft + Math.max(3, headNext - headLine - 5));
        int releaseLeft = Math.max(grid.x + 3, releaseLine + 3);
        int releaseRight = Math.min(grid.x + grid.width - 2, releaseLeft + Math.max(3, releaseNext - releaseLine - 5));
        int left = headVisible ? headLeft : grid.x + 1;
        int right = !hold ? headRight : releaseVisible ? releaseRight : grid.x + grid.width - 1;
        int laneTop = grid.y + displayLane * laneHeight;
        int top = laneTop + 3;
        int bottom = top + laneHeight - 5;
        int hitLeft = headVisible ? headLine : grid.x;
        int hitRight = !hold ? headNext : releaseVisible ? releaseNext : grid.x + grid.width;
        return new NoteBounds(left, Math.max(left + 2, right), top, bottom, headLeft, headRight, headVisible,
                releaseLeft, releaseRight, releaseVisible, hitLeft, hitRight, laneTop + 1, laneTop + laneHeight,
                hold && headVisible, releaseVisible);
    }

    /**
     * Where the box of a note on {@code tick} ends: at the next line of the grid on show, and never more than a cell
     * on. A fine grid's cells alternate in width (1 and 2 ticks at 120 BPM), and a box on a line stops at the next one
     * instead of spilling into the neighbour's. A note between the lines on show - put on 1/8 and viewed on 1/4, or
     * imported - also stops at the next line: a whole cell from it reached into that line's cell, and a click meant
     * for a new note there grabbed this one instead.
     */
    private int boxEndTick(int tick, int step) {
        return Math.min(tick + step, gridTickAfter(tick));
    }

    private void beginNoteSelectionDrag(HoveredNote hovered, double mouseX, double mouseY) {
        BattleNote note = hovered.note();
        NoteBounds bounds = hovered.bounds();
        draggedNote = note;
        timelineScrubbing = false;
        resumePreviewAfterScrub = false;
        double leftDistance = bounds.leftEdgeVisible() ? Math.abs(mouseX - bounds.left()) : Double.POSITIVE_INFINITY;
        double rightDistance = bounds.rightEdgeVisible() ? Math.abs(mouseX - bounds.right()) : Double.POSITIVE_INFINITY;
        // The ends of a hold resize it and its middle moves it; on a short hold the ends shrink, so it keeps a middle.
        int grab = Math.max(3, Math.min(NOTE_RESIZE_GRAB_WIDTH, (bounds.right() - bounds.left()) / 4));
        if (note.durationTicks() > 0 && Math.min(leftDistance, rightDistance) <= grab) {
            noteDragMode = leftDistance <= rightDistance ? DragMode.RESIZE_LEFT : DragMode.RESIZE_RIGHT;
            noteDragFixedTick = noteDragMode == DragMode.RESIZE_LEFT ? note.tick() + note.durationTicks() : note.tick();
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
        if (hovered != null && hovered.note().durationTicks() > 0 && !selectedNotes.contains(hovered.note())) {
            drawNoteHandles(graphics, hovered.bounds(), false);
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
        if (noteDragMode != DragMode.MOVE) {
            resizeDraggedNote(effectiveMouseX);
            return;
        }
        Grid grid = grid();
        int targetTick = snapTimelineTick(Math.max(0, cursorTimelineTick(effectiveMouseX) - noteDragGrabOffsetTicks));
        int targetLane = draggedNote.lane();
        if (mouseY >= grid.y && mouseY < grid.y + grid.height) {
            targetLane = Mth.clamp((int) ((mouseY - grid.y) / Math.max(1, grid.height / NOTE_LANES)), 0, NOTE_LANES - 1);
        }
        int laneDelta = targetLane - draggedNote.lane();
        // The selection moves by whole lines of the eighth-beat grid, counted from the line of the note in the hand,
        // so every note keeps its place in the beat: lines are not all the same number of ticks apart, and moving
        // every note by the hand's tick distance slid the others off their lines.
        long cells = ChartEdits.fineCell(targetTick, offsetMs(), bpm()) - ChartEdits.fineCell(draggedNote.tick(), offsetMs(), bpm());
        if (cells == 0L && laneDelta == 0) {
            return;
        }
        List<BattleNote> originals = notes.stream().filter(selectedNotes::contains).toList();
        List<BattleNote> replacements = ChartEdits.shiftNotes(originals, cells, laneDelta, offsetMs(), bpm());
        if (replacements != null && originals.size() == 1 && replacements.get(0).tick() != targetTick) {
            // A single note off the grid (from an import) goes onto the line it is dropped on.
            BattleNote moved = replacements.get(0);
            int release = moved.tick() + moved.durationTicks();
            replacements = List.of(new BattleNote(moved.owner(), moved.lane(), targetTick,
                    moved.durationTicks() > 0 ? Math.max(1, release - targetTick) : 0));
        }
        if (replacements == null) {
            setStatus(StatusLevel.ERROR, I18n.get("music_battle.l10n.editor.move_before_start"));
            return;
        }
        if (!ChartEdits.fits(notes, originals, replacements)) {
            setStatus(StatusLevel.ERROR, I18n.get("music_battle.l10n.editor.text_107"));
            return;
        }
        if (!dragHistoryCaptured) {
            pushUndoSnapshot();
            dragHistoryCaptured = true;
        }
        BattleNote newAnchor = null;
        for (int index = 0; index < originals.size(); index++) {
            BattleNote candidate = originals.get(index);
            BattleNote replacement = replacements.get(index);
            int noteIndex = notes.indexOf(candidate);
            if (noteIndex >= 0) notes.set(noteIndex, replacement);
            if (candidate.equals(draggedNote)) newAnchor = replacement;
        }
        selectedNotes.clear();
        selectedNotes.addAll(replacements);
        draggedNote = newAnchor;
        invalidateTimelineCache();
    }

    /**
     * Dragging an end of a hold moves that end's box to the box under the mouse. The release box used to follow the
     * line nearest the mouse, which with the release now drawn as a box of its own left it a box past the pointer.
     */
    private void resizeDraggedNote(double effectiveMouseX) {
        BattleNote previous = draggedNote;
        int precision = gridPrecisionMultiplier();
        long cell = ChartBeat.cellAtOrBefore(offsetMs(), bpm(), STANDARD_GRID_DIVISION * precision,
                cursorExactTick(effectiveMouseX));
        int boxTick = Math.max(0, cellTick(cell, precision));
        BattleNote resized;
        if (noteDragMode == DragMode.RESIZE_LEFT) {
            // The press stays at least one line before the release, which stays where it is.
            int start = Math.min(boxTick, gridTickBefore(noteDragFixedTick));
            resized = new BattleNote(previous.owner(), previous.lane(), start, noteDragFixedTick - start);
        } else {
            int release = Math.max(boxTick, gridTickAfter(noteDragFixedTick));
            resized = new BattleNote(previous.owner(), previous.lane(), noteDragFixedTick, release - noteDragFixedTick);
        }
        if (resized.equals(previous)) {
            return;
        }
        if (!ChartEdits.fits(notes, List.of(previous), List.of(resized))) {
            setStatus(StatusLevel.ERROR, I18n.get("music_battle.l10n.editor.text_106"));
            return;
        }
        if (!dragHistoryCaptured) {
            pushUndoSnapshot();
            dragHistoryCaptured = true;
        }
        replaceNote(previous, resized);
        draggedNote = resized;
    }

    private void clearNoteSelectionDrag() {
        draggedNote = null;
        noteDragMode = DragMode.MOVE;
        noteWasDragged = false;
        dragHistoryCaptured = false;
        dragPageTurnArmed = true;
    }

    /** Puts {@code replacement} where {@code original} was in the chart and in the selection. */
    private void replaceNote(BattleNote original, BattleNote replacement) {
        int index = notes.indexOf(original);
        if (index >= 0) {
            notes.set(index, replacement);
        }
        if (selectedNotes.remove(original)) {
            selectedNotes.add(replacement);
        }
        invalidateTimelineCache();
    }

    private static <T> void toggleSelection(Set<T> selection, T value) {
        if (!selection.add(value)) {
            selection.remove(value);
        }
    }

    private void pushUndoSnapshot() {
        pushUndoSnapshot(snapshotTimeline());
    }

    /** Records a state for Ctrl+Z to return to: the notes, the effects and what was selected. */
    private void pushUndoSnapshot(EditorSnapshot snapshot) {
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
        return new EditorSnapshot(List.copyOf(notes), List.copyOf(effects), Set.copyOf(selectedNotes), Set.copyOf(selectedEffects));
    }

    /**
     * Runs a change to the selection and, when it changed anything, makes the selection before it a Ctrl+Z step, so a
     * wrong selection is undone like a wrong edit.
     */
    private void changeSelection(Runnable change) {
        EditorSnapshot before = snapshotTimeline();
        change.run();
        if (selectionDiffers(before)) {
            pushUndoSnapshot(before);
        }
    }

    private boolean selectionDiffers(EditorSnapshot snapshot) {
        return !snapshot.selectedNotes().equals(selectedNotes) || !snapshot.selectedEffects().equals(selectedEffects);
    }

    /**
     * Brings back the notes, the effects and the selection of a step. Only the kind of selection this page edits
     * returns: notes selected on the note page would otherwise move with the arrow keys on the FX page, unseen. For
     * the same reason only the edited player's notes come back selected: a step taken while the other part was edited
     * would otherwise leave its notes selected, faint, under Delete and the arrow keys.
     */
    private void restoreSnapshot(EditorSnapshot snapshot) {
        notes.clear();
        notes.addAll(snapshot.notes());
        effects.clear();
        effects.addAll(snapshot.effects());
        selectedNotes.clear();
        selectedEffects.clear();
        if (editorPage == EditorPage.FX) {
            snapshot.selectedEffects().stream().filter(this::isFxRowVisible).forEach(selectedEffects::add);
        } else {
            snapshot.selectedNotes().stream().filter(note -> note.owner() == owner).forEach(selectedNotes::add);
        }
        clearNoteSelectionDrag();
        clearEffectDrag();
        pendingEffectStartTick = -1;
        pendingEffectLane = -1;
        invalidateTimelineCache();
    }

    private void undo() {
        if (undoHistory.isEmpty()) {
            setStatus(StatusLevel.ERROR, I18n.get("music_battle.l10n.editor.text_108"));
            return;
        }
        EditorSnapshot current = snapshotTimeline();
        redoHistory.addLast(current);
        restoreSnapshot(undoHistory.removeLast());
        showStep(current);
        setStatus(StatusLevel.OK, I18n.get("music_battle.l10n.editor.undone"));
    }

    private void redo() {
        if (redoHistory.isEmpty()) {
            setStatus(StatusLevel.ERROR, I18n.get("music_battle.l10n.editor.text_109"));
            return;
        }
        EditorSnapshot current = snapshotTimeline();
        undoHistory.addLast(current);
        restoreSnapshot(redoHistory.removeLast());
        showStep(current);
        setStatus(StatusLevel.OK, I18n.get("music_battle.l10n.editor.redone"));
    }

    /**
     * After Ctrl+Z or Shift+Ctrl+Z the page shows what the step changed: the notes and effects it put back or took
     * away, or, for a step that changed only the selection, the selection it brought back. The page stays when any of
     * that is already on it and otherwise turns to the earliest. Turning to the selection after every step, as at
     * first, took the player away from a note they had just placed whenever what was selected before lay elsewhere.
     */
    private void showStep(EditorSnapshot before) {
        List<Integer> changed = new ArrayList<>();
        Set<BattleNote> notesBefore = new HashSet<>(before.notes());
        Set<BattleNote> notesAfter = new HashSet<>(notes);
        before.notes().stream().filter(note -> !notesAfter.contains(note)).forEach(note -> changed.add(note.tick()));
        notes.stream().filter(note -> !notesBefore.contains(note)).forEach(note -> changed.add(note.tick()));
        Set<TrackEffect> effectsBefore = new HashSet<>(before.effects());
        Set<TrackEffect> effectsAfter = new HashSet<>(effects);
        before.effects().stream().filter(effect -> !effectsAfter.contains(effect)).forEach(effect -> changed.add(effect.tick()));
        effects.stream().filter(effect -> !effectsBefore.contains(effect)).forEach(effect -> changed.add(effect.tick()));
        if (changed.isEmpty()) {
            selectedNotes.forEach(note -> changed.add(note.tick()));
            selectedEffects.forEach(effect -> changed.add(effect.tick()));
        }
        int pageEnd = pageStartTick + pageTicks();
        if (changed.isEmpty() || changed.stream().anyMatch(tick -> tick >= pageStartTick && tick < pageEnd)) {
            return;
        }
        pageStartTick = pageStartForTick(changed.stream().mapToInt(Integer::intValue).min().orElse(pageStartTick));
        if (!previewPlaying) {
            previewTick = pageStartTick;
        }
    }

    /**
     * Copies the selection by grid line, not by tick, so a paste puts every note back on a line: the clipboard used to
     * keep tick distances, and lines are not all the same number of ticks apart (3.125 ticks a cell at 96 BPM).
     */
    private void copySelection() {
        selectedEffects.removeIf(effect -> !isFxRowVisible(effect));
        if (selectedNotes.isEmpty() && selectedEffects.isEmpty()) {
            setStatus(StatusLevel.ERROR, I18n.get("music_battle.l10n.editor.select_first"));
            return;
        }
        clipboard = ChartEdits.copy(selectedNotes, selectedEffects, offsetMs(), bpm());
        clipboardAnchor = clipboard.firstCell();
        setStatus(StatusLevel.OK, I18n.get("music_battle.l10n.editor.copied", clipboard.size()));
    }

    /**
     * Pastes the copied group into free space (see {@link ChartEdits#pasteCell}): next to its last copy when that is
     * on this page, so Ctrl+V again and again lays copies one after another, otherwise from the start of this page.
     * It used to paste at the start of the page every time, which was on top of the group itself or of the last
     * paste, and was refused. The page turns to the paste when it lands further on.
     */
    private void pasteSelection() {
        if (clipboard.isEmpty()) {
            setStatus(StatusLevel.ERROR, I18n.get("music_battle.l10n.editor.text_113"));
            return;
        }
        long cell = ChartEdits.pasteCell(notes, effects, clipboard, pasteSearchStart(), firstVisibleFxRow, offsetMs(), bpm());
        List<BattleNote> pastedNotes = ChartEdits.placeNotes(clipboard, cell, offsetMs(), bpm());
        List<TrackEffect> pastedEffects = ChartEdits.placeEffects(clipboard, cell, firstVisibleFxRow, offsetMs(), bpm());
        if (effects.size() + pastedEffects.size() > TrackEffect.MAX_EFFECTS) {
            setStatus(StatusLevel.ERROR, I18n.get("music_battle.l10n.editor.max_effects", TrackEffect.MAX_EFFECTS));
            return;
        }
        pushUndoSnapshot();
        notes.addAll(pastedNotes);
        effects.addAll(pastedEffects);
        selectedNotes.clear();
        selectedNotes.addAll(pastedNotes);
        selectedEffects.clear();
        selectedEffects.addAll(pastedEffects);
        clipboardAnchor = cell;
        invalidateTimelineCache();
        int first = Math.min(pastedNotes.stream().mapToInt(BattleNote::tick).min().orElse(Integer.MAX_VALUE),
                pastedEffects.stream().mapToInt(TrackEffect::tick).min().orElse(Integer.MAX_VALUE));
        if (first != Integer.MAX_VALUE && (first < pageStartTick || first >= pageStartTick + pageTicks())) {
            pageStartTick = pageStartForTick(first);
            if (!previewPlaying) {
                previewTick = pageStartTick;
            }
        }
        long cellsPerBar = (long) ChartBeat.BEATS_PER_BAR * ChartEdits.FINE_CELLS_PER_BEAT;
        String beat = new java.math.BigDecimal(Math.floorMod(cell, cellsPerBar) / (double) ChartEdits.FINE_CELLS_PER_BEAT + 1.0D)
                .stripTrailingZeros().toPlainString();
        setStatus(StatusLevel.OK, I18n.get("music_battle.l10n.editor.pasted_at", clipboard.size(),
                ChartBeat.barOfCell(cell, ChartEdits.FINE_CELLS_PER_BEAT), beat));
    }

    /**
     * Where Ctrl+V starts looking: the group's last place when it is on this page, so a paste follows the one before
     * it; otherwise the first line of this page that has the group's place in the beat, so a group goes where the
     * player is looking.
     */
    private long pasteSearchStart() {
        int anchorTick = ChartEdits.fineTick(clipboardAnchor, offsetMs(), bpm());
        if (anchorTick >= pageStartTick && anchorTick < pageStartTick + pageTicks()) {
            return clipboardAnchor;
        }
        return pageFromBeatOne() * PAGE_BEATS * ChartEdits.FINE_CELLS_PER_BEAT + clipboard.phase();
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
        setStatus(StatusLevel.OK, I18n.get("music_battle.l10n.editor.deleted", count));
    }

    private void convertSelectedNoteOwners() {
        if (selectedNotes.isEmpty()) {
            setStatus(StatusLevel.ERROR, I18n.get("music_battle.l10n.editor.select_first"));
            return;
        }
        List<BattleNote> originals = notes.stream().filter(selectedNotes::contains).toList();
        List<BattleNote> converted = originals.stream()
                .map(note -> new BattleNote(1 - Mth.clamp(note.owner(), 0, 1), note.lane(), note.tick(), note.durationTicks()))
                .toList();
        if (!ChartEdits.fits(notes, originals, converted)) {
            setStatus(StatusLevel.ERROR, I18n.get("music_battle.l10n.editor.text_118"));
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
        setStatus(StatusLevel.OK, I18n.get("music_battle.l10n.editor.converted", converted.size()));
    }

    /**
     * The arrow keys: left and right move the selection by one line of the grid on show, counted in eighth-beat lines
     * so every note keeps its place in the beat. They used to move everything by the tick length of the first note's
     * cell, and cells are not all the same length (3, 3, 3 and 4 ticks at 96 BPM), so other notes left their lines.
     */
    private void moveSelection(int lineSteps, int laneDelta) {
        selectedEffects.removeIf(effect -> !isFxRowVisible(effect));
        long cells = (long) lineSteps * (ChartEdits.FINE_CELLS_PER_BEAT / (STANDARD_GRID_DIVISION * gridPrecisionMultiplier()));
        List<BattleNote> originalNotes = notes.stream().filter(selectedNotes::contains).toList();
        List<TrackEffect> originalEffects = effects.stream().filter(selectedEffects::contains).toList();
        List<BattleNote> replacementNotes = ChartEdits.shiftNotes(originalNotes, cells, laneDelta, offsetMs(), bpm());
        List<TrackEffect> replacementEffects = ChartEdits.shiftEffects(originalEffects, cells, laneDelta, offsetMs(), bpm());
        if (replacementNotes == null || replacementEffects == null) {
            setStatus(StatusLevel.ERROR, I18n.get("music_battle.l10n.editor.move_before_start"));
            return;
        }
        if (!ChartEdits.fits(notes, originalNotes, replacementNotes)) {
            setStatus(StatusLevel.ERROR, I18n.get("music_battle.l10n.editor.text_107"));
            return;
        }
        pushUndoSnapshot();
        for (int index = 0; index < originalNotes.size(); index++) {
            int noteIndex = notes.indexOf(originalNotes.get(index));
            if (noteIndex >= 0) notes.set(noteIndex, replacementNotes.get(index));
        }
        for (int index = 0; index < originalEffects.size(); index++) {
            int effectIndex = effects.indexOf(originalEffects.get(index));
            if (effectIndex >= 0) effects.set(effectIndex, replacementEffects.get(index));
        }
        selectedNotes.clear();
        selectedNotes.addAll(replacementNotes);
        selectedEffects.clear();
        selectedEffects.addAll(replacementEffects);
        keepEffectSelectionInView(selectedEffects);
        invalidateTimelineCache();
        setStatus(StatusLevel.OK, I18n.get("music_battle.l10n.editor.text_120"));
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
            setStatus(StatusLevel.OK, I18n.get("music_battle.l10n.editor.text_121"));
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
        setStatus(StatusLevel.OK, I18n.get("music_battle.l10n.editor.template_updated", effectName(template.type())));
    }

    void refreshEffectBrush(TrackEffect.Type type) {
        effectType = type;
        effectTemplate = ClientEffectToolSettings.preset(type);
        effectMode = parseEffectMode(ClientEffectToolSettings.placementMode(type));
        pendingEffectStartTick = -1;
        pendingEffectLane = -1;
        setStatus(StatusLevel.OK, I18n.get("music_battle.l10n.editor.brush_updated", effectName(type)));
    }

    void removeEffect(TrackEffect effect) {
        pushUndoSnapshot();
        if (effects.remove(effect)) {
            invalidateTimelineCache();
            setStatus(StatusLevel.OK, I18n.get("music_battle.l10n.editor.text_126"));
        }
    }

    /**
     * A click on the grid that no note or effect took. On the note page the left button places a note and never
     * removes one - removing on a click is what made a press on a note's edge delete it - and the right button makes
     * a long note, or turns the long note under it back into a normal one. A click that changes anything is one Ctrl+Z
     * step; one that is refused leaves no step behind.
     */
    private boolean toggleGridNote(double mouseX, double mouseY, int button) {
        Grid grid = grid();
        if (mouseX < grid.x || mouseX >= grid.x + grid.width || mouseY < grid.y || mouseY >= grid.y + grid.height) {
            return false;
        }
        EditorSnapshot before = snapshotTimeline();
        int laneCount = editorPage == EditorPage.FX ? FX_LANES : NOTE_LANES;
        int laneHeight = Math.max(1, grid.height / laneCount);
        int column = gridColumnAt(grid, mouseX);
        int lane = Mth.clamp((int) ((mouseY - grid.y) / laneHeight), 0, laneCount - 1);
        boolean beforeSong = columnBeforeSong(column);
        if (editorPage == EditorPage.FX) {
            if (beforeSong) {
                setStatus(StatusLevel.ERROR, I18n.get("music_battle.l10n.editor.before_song_click"));
            } else {
                toggleGridEffect(column, lane, button);
            }
        } else if (button == 1) {
            toggleHold(mouseX, mouseY, gridTickAtColumn(column), lane, hasShiftDown() ? 4 : 1, beforeSong);
        } else if (button == 0) {
            if (beforeSong) {
                setStatus(StatusLevel.ERROR, I18n.get("music_battle.l10n.editor.before_song_click"));
            } else {
                placeNote(gridTickAtColumn(column), lane);
            }
        }
        if (!before.equals(snapshotTimeline())) {
            pushUndoSnapshot(before);
        }
        return true;
    }

    /**
     * Whether a column's line comes before the song starts, which happens only on the shaded part of the page that
     * leads in to a later beat 1. Its tick would be clamped onto the song's first tick, so a click anywhere in the
     * shade used to put a note at the very start of the song, far from the line that was clicked.
     */
    private boolean columnBeforeSong(int column) {
        long cell = pageFromBeatOne() * gridColumns() + Mth.clamp(column, 0, gridColumns());
        return cellTick(cell, gridPrecisionMultiplier()) < 0;
    }

    private void placeNote(int tick, int lane) {
        BattleNote existing = noteAt(lane, tick);
        if (existing != null) {
            // The note's own box takes every click on it, so a click reaching here is on another line that rounds to
            // its tick, which happens on the 1/8 grid above 150 BPM. A beatmap holds one note per tick and lane; the
            // click selects that note rather than stacking a second one or removing it.
            selectedNotes.clear();
            selectedEffects.clear();
            selectedNotes.add(existing);
            setStatus(StatusLevel.WARN, I18n.get("music_battle.l10n.editor.same_tick"));
            return;
        }
        BattleNote added = new BattleNote(owner, lane, tick, 0);
        if (!ChartEdits.fits(notes, List.of(added))) {
            setStatus(StatusLevel.ERROR, I18n.get("music_battle.l10n.editor.text_128"));
            return;
        }
        notes.add(added);
        invalidateTimelineCache();
    }

    /**
     * The right button: over a long note it becomes a normal note again; over a normal one, or on an empty line, the
     * note becomes long, released one line on (four with Shift). It used to make a long note one cell longer on every
     * click and had no way back, and a click on a long note's tail missed it, since only its press line counted. Before
     * the song starts only a note drawn under the mouse can be changed; nothing new is made there.
     */
    private void toggleHold(double mouseX, double mouseY, int tick, int lane, int lines, boolean beforeSong) {
        HoveredNote hovered = hoveredNoteInfo(mouseX, mouseY);
        BattleNote target = hovered != null ? hovered.note() : beforeSong ? null : noteAt(lane, tick);
        if (target == null && beforeSong) {
            setStatus(StatusLevel.ERROR, I18n.get("music_battle.l10n.editor.before_song_click"));
            return;
        }
        if (target != null) {
            BattleNote changed = new BattleNote(target.owner(), target.lane(), target.tick(),
                    target.durationTicks() > 0 ? 0 : holdLength(target.tick(), lines));
            if (!ChartEdits.fits(notes, List.of(target), List.of(changed))) {
                setStatus(StatusLevel.ERROR, I18n.get("music_battle.l10n.editor.text_127"));
                return;
            }
            replaceNote(target, changed);
            setStatus(StatusLevel.OK, I18n.get(changed.durationTicks() > 0
                    ? "music_battle.l10n.editor.hold_made" : "music_battle.l10n.editor.hold_removed"));
            return;
        }
        BattleNote added = new BattleNote(owner, lane, tick, holdLength(tick, lines));
        if (!ChartEdits.fits(notes, List.of(added))) {
            setStatus(StatusLevel.ERROR, I18n.get("music_battle.l10n.editor.text_127"));
            return;
        }
        notes.add(added);
        invalidateTimelineCache();
    }

    /** A hold pressed on {@code tick} and released {@code lines} lines of the grid on show later, on a line. */
    private int holdLength(int tick, int lines) {
        return ChartBeat.lineAfter(offsetMs(), bpm(), STANDARD_GRID_DIVISION * gridPrecisionMultiplier(), tick, lines) - tick;
    }

    /** The note of the player being edited pressed on this tick in this lane, if there is one. */
    private BattleNote noteAt(int lane, int tick) {
        for (BattleNote note : notes) {
            if (note.owner() == owner && note.lane() == lane && note.tick() == tick) {
                return note;
            }
        }
        return null;
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
                setStatus(StatusLevel.OK, I18n.get("music_battle.l10n.editor.text_126"));
            }
            return true;
        }
        if (button != 0) {
            return true;
        }
        if (effects.size() >= TrackEffect.MAX_EFFECTS) {
            setStatus(StatusLevel.ERROR, I18n.get("music_battle.l10n.editor.max_effects", TrackEffect.MAX_EFFECTS));
            return true;
        }
        if (effectMode == EffectPlacementMode.RANGE) {
            if (pendingEffectStartTick < 0) {
                pendingEffectStartTick = tick;
                pendingEffectLane = absoluteRow;
                setStatus(StatusLevel.OK, I18n.get("music_battle.l10n.editor.range_start", tick));
                return true;
            }
            int start = Math.min(pendingEffectStartTick, tick);
            int duration = Math.max(stepTicks(), Math.abs(tick - pendingEffectStartTick));
            TrackEffect added = moveEffectToAbsoluteRow(effectTemplate, pendingEffectLane);
            effects.add(added.withTimeline(added.lane(), start, duration));
            invalidateTimelineCache();
            pendingEffectStartTick = -1;
            pendingEffectLane = -1;
            setStatus(StatusLevel.OK, I18n.get("music_battle.l10n.editor.range_added"));
            return true;
        }
        int duration = effectMode == EffectPlacementMode.LENGTH
                ? Math.max(stepTicks(), effectTemplate.durationTicks())
                : 0;
        TrackEffect added = moveEffectToAbsoluteRow(effectTemplate, absoluteRow);
        effects.add(added.withTimeline(added.lane(), tick, duration));
        invalidateTimelineCache();
        setStatus(StatusLevel.OK, I18n.get("music_battle.l10n.editor.effect_added", effectName(effectType)));
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
        graphics.fill(grid.x, top, grid.x + grid.width, bottom, Palette.BG_3);

        float pageStartProgress = Mth.clamp(pageStartTick / (float) endTick, 0.0F, 1.0F);
        float pageEndProgress = Mth.clamp((pageStartTick + pageTicks()) / (float) endTick, 0.0F, 1.0F);
        int pageLeft = grid.x + Math.round(grid.width * pageStartProgress);
        int pageRight = grid.x + Math.round(grid.width * pageEndProgress);
        graphics.fill(pageLeft, top + 1, Math.max(pageLeft + 1, pageRight), bottom - 1, Palette.withAlpha(Palette.ACCENT, 0.35F));

        float currentTick = previewPlaying ? currentPreviewTick() : previewTick;
        int handleX = grid.x + Math.round(grid.width * Mth.clamp(currentTick / endTick, 0.0F, 1.0F));
        graphics.fill(grid.x, top + 2, handleX, bottom - 2, Palette.ACCENT_STRONG);
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
            setStatus(StatusLevel.OK, I18n.get("music_battle.l10n.editor.preview_position", previewTick));
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
            setStatus(StatusLevel.OK, I18n.get("music_battle.l10n.editor.preview_position", previewTick));
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
            setStatus(StatusLevel.OK, I18n.get("music_battle.l10n.editor.ogg_loaded", previewDurationTicks / 20));
        } catch (IOException exception) {
            setStatus(StatusLevel.ERROR, exception.getMessage());
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
            setStatus(StatusLevel.OK, I18n.get("music_battle.l10n.editor.audio_loading", previewTick));
        } catch (IOException exception) {
            setStatus(StatusLevel.ERROR, exception.getMessage());
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
                    offsetMs(),
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
            setStatus(StatusLevel.ERROR, exception.getMessage());
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
                setStatus(StatusLevel.ERROR, I18n.get("music_battle.l10n.editor.no_raw_files", "PNG/JPG/JPEG/GIF/BMP", directory));
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
            setStatus(StatusLevel.OK, I18n.get("music_battle.l10n.editor.image_selected", chosen.getFileName(), gifNotice));
        } catch (IOException exception) {
            setStatus(StatusLevel.ERROR, exception.getMessage());
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
                // The song is converted after this returns, on a worker: converting it right here, in the click, froze
                // the game for the whole conversion.
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
            // The import's own report says what happened, so the reset is not announced as a typed change.
            setBoxValueAtStart(offsetBox, "0");
            shownOffsetMs = 0;
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
                setStatus(StatusLevel.WARN, audioLeftBehindStatus(notes.size(), audioLeftBehind));
            } else if (pendingSong != null) {
                String converting = I18n.get("music_battle.l10n.editor.import_audio_converting", notes.size(),
                        pendingSong.sourceExtension().replace(".", "").toUpperCase(java.util.Locale.ROOT));
                if (over > 0) {
                    setStatus(StatusLevel.WARN, converting + " " + I18n.get("music_battle.l10n.editor.too_many_notes_import",
                            notes.size(), ClientTrackExporter.MAX_NOTES, over));
                } else {
                    setStatus(StatusLevel.OK, converting);
                }
                convertImportedSong(pendingSong, importGeneration, notes.size());
            } else if (over > 0) {
                setStatus(StatusLevel.WARN, I18n.get("music_battle.l10n.editor.too_many_notes_import",
                        notes.size(), ClientTrackExporter.MAX_NOTES, over));
            } else {
                setStatus(StatusLevel.OK, I18n.get("music_battle.l10n.editor.imported", notes.size(), holds, audioImportStatus(importedAudio), previewImportStatus(importedPreview)));
            }
        } catch (IOException | RuntimeException exception) {
            // The importers read files from anywhere, and anything a broken file throws must end up here rather than
            // in the button handler, where Minecraft turns it into a crash that takes the unsaved chart with it.
            setStatus(StatusLevel.ERROR, MusicBattleTracks.safeMessage(exception));
        }
    }

    /**
     * Converts the song of an imported osu! map on a worker and puts it in the song box when it is ready, unless
     * another import has replaced the notes meanwhile or the player has already chosen a song for them.
     */
    private void convertImportedSong(ClientOsuManiaImporter.PendingSong pendingSong, int importGeneration, int noteCount) {
        net.minecraft.Util.nonCriticalIoPool().execute(() -> {
            ClientOsuManiaImporter.SongResult converted;
            Error fatal = null;
            try {
                converted = pendingSong.convert();
            } catch (RuntimeException | Error thrown) {
                // convert() answers every failure it knows; this is the one it cannot, and the status line must still
                // stop saying "converting".
                ru.gora.musicbattle.MusicBattleMod.LOGGER.warn("Blocky Music could not convert the song of an osu! map.", thrown);
                converted = new ClientOsuManiaImporter.SongResult("", new ClientOsuManiaImporter.AudioLeftBehind(
                        "", pendingSong.sourceExtension(), false, thrown.getClass().getSimpleName()));
                fatal = thrown instanceof Error error ? error : null;
            }
            ClientOsuManiaImporter.SongResult song = converted;
            Minecraft.getInstance().execute(() -> {
                if (importGeneration != songImportGeneration) {
                    return;
                }
                if (song.leftBehind() != null) {
                    setStatus(StatusLevel.WARN, audioLeftBehindStatus(noteCount, song.leftBehind()));
                    return;
                }
                String typed = audioBox == null || audioBox.getValue() == null ? "" : audioBox.getValue().trim();
                if (typed.isEmpty()) {
                    setBoxValueAtStart(audioBox, song.audioFile());
                }
                setStatus(StatusLevel.OK, I18n.get("music_battle.l10n.editor.import_audio_ready", song.audioFile()));
            });
            if (fatal != null) {
                // The status line is answered; an Error still ends this worker as it would have.
                throw fatal;
            }
        });
    }

    /** What happened to the song of an imported map that was not OGG, and what the player can do about it. */
    private static String audioLeftBehindStatus(int noteCount, ClientOsuManiaImporter.AudioLeftBehind left) {
        String format = left.sourceExtension().replace(".", "").toUpperCase(java.util.Locale.ROOT);
        String kept = left.keptFile().isBlank() ? "-" : left.keptFile();
        // An unsupported song is named by its file, not its extension: a file called .mp3 may be anything at all.
        String text = left.unsupportedFormat()
                ? I18n.get("music_battle.l10n.editor.import_audio_unsupported", noteCount, kept)
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
            // The boxes name the saved copies the way the mod shows any path, below the Minecraft folder: they showed
            // the absolute path, every folder above the game included.
            if (draftAudio != null && !draftAudio.isBlank()) {
                draftAudio = ClientTrackExporter.boxValueOf(savedDirectory.resolve(savedTrackId + ".ogg"));
                if (audioBox != null) audioBox.setValue(draftAudio);
            }
            if (draftPreview != null && !draftPreview.isBlank()) {
                draftPreview = ClientTrackExporter.boxValueOf(savedDirectory.resolve(savedTrackId + "_preview.png"));
                if (previewBox != null) previewBox.setValue(draftPreview);
            }
            // music_battle_raw is the user's source/import folder. Saving an
            // autonomous track must never delete files from it, including the
            // OGG/PNG extracted from an osu archive.
            savedState = currentState();
            setStatus(StatusLevel.OK, I18n.get("music_battle.l10n.editor.saved", output.getFileName()));
        } catch (IOException | RuntimeException exception) {
            setStatus(StatusLevel.ERROR, MusicBattleTracks.safeMessage(exception));
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
            setStatus(StatusLevel.OK, I18n.get("music_battle.l10n.editor.track_deleted", idBox.getValue()));
            notes.clear();
            effects.clear();
            pendingEffectStartTick = -1;
            pendingEffectLane = -1;
            invalidateTimelineCache();
        } catch (IOException exception) {
            setStatus(StatusLevel.ERROR, exception.getMessage());
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

    /**
     * An empty or unreadable field is no offset, as in a chart that never had one. A value a chart cannot store moves
     * by whole bars instead of being clamped: 15000 at 120 BPM used to become 12000, a different grid, and is now
     * 11000, the same grid one storable bar earlier.
     */
    private int offsetMs() {
        Double typed = parseOffset(offsetBox != null ? offsetBox.getValue() : draftOffset);
        return typed == null ? 0 : ChartBeat.storableOffsetMs(typed, bpm());
    }

    private float volume() {
        return Mth.clamp(editorVolume, 0.0F, 2.0F);
    }

    /** The tempo and beat 1 the chart has as typed, for the effect editor's preview (TrackEffectEditScreen.editorHost). */
    int chartBpm() {
        return bpm();
    }

    int chartOffsetMs() {
        return offsetMs();
    }

    /**
     * The number in the volume row: the slider's own value while there is a slider, so it is the knob's at every frame
     * of a drag and can never disagree with it. The row used to print the saved volume, which a drag updates through
     * applyValue, but a knob moved any other way (a session that set the slider directly) kept the old number beside it.
     */
    private String volumeText() {
        float shown = volumeSlider != null ? volumeSlider.valueToVolume() : volume();
        return String.format(java.util.Locale.ROOT, "%.2f", shown);
    }

    /**
     * What a player is likely to drop in, beyond Ogg: MP3 (and MP2), WAV, FLAC, M4A, AAC, the sound of MP4, MOV and 3GP
     * videos, iPhone ringtones (.m4r), audiobooks (.m4b), Opus (also as .oga), WebM, the audio-only WebM and Matroska
     * files browsers and downloaders save (.weba, .mka), Matroska videos (.mkv), and MPEG transport streams: HLS
     * downloads and TV recordings (.ts), camcorder and Blu-ray files (.m2ts, .mts). They are converted on the spot, so
     * the editor only ever holds Ogg.
     */
    private static final List<String> AUDIO_EXTENSIONS = List.of(".ogg", ".mp3", ".mp2", ".wav", ".flac", ".m4a", ".aac",
            ".mp4", ".mov", ".3gp", ".m4r", ".m4b", ".opus", ".oga", ".webm", ".weba", ".mka", ".mkv", ".ts", ".m2ts",
            ".mts");
    /** How long one song may take to convert; a four-minute MP3 takes about a second. */
    private static final int AUDIO_CONVERSION_TIMEOUT_SECONDS = 120;
    /**
     * The file the Audio button last failed to convert, and what the song box held then. The next press continues
     * after that file: the box still names the song before it, and the button would otherwise pick the same failing
     * file on every press and never reach the ones behind it.
     */
    private String failedAudioPick = "";
    private String failedAudioPickBox = "";

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
                Path conversion = conversionOf(file);
                if (conversion == null || !Files.isRegularFile(conversion)) {
                    offered.add(file);
                }
            }
            if (offered.isEmpty()) {
                setStatus(StatusLevel.ERROR, I18n.get("music_battle.l10n.editor.no_audio_files", MusicBattleTracks.rawDirectory()));
                return;
            }
            String current = target.getValue() == null ? "" : target.getValue().trim();
            if (!failedAudioPick.isEmpty() && current.equals(failedAudioPickBox)) {
                current = failedAudioPick;
            }
            int index = -1;
            for (int i = 0; i < offered.size(); i++) {
                if (offered.get(i).getFileName().toString().equalsIgnoreCase(current)) {
                    index = i;
                    break;
                }
            }
            Path chosen = offered.get((index + 1) % offered.size());
            failedAudioPick = "";
            Path conversion = conversionOf(chosen);
            if (conversion == null) {
                setBoxValueAtStart(target, chosen.getFileName().toString());
                refreshOsuDifficulties();
                setStatus(StatusLevel.OK, I18n.get("music_battle.l10n.editor.selected", chosen.getFileName()));
                return;
            }
            convertAudioToOgg(target, chosen, conversion);
        } catch (IOException exception) {
            setStatus(StatusLevel.ERROR, exception.getMessage());
        }
    }

    /**
     * Where a file's conversion to Ogg Vorbis goes, or null when the game plays it as it is (Ogg Vorbis, one or two
     * channels). Decided by the file's first bytes, not its name: an MP3 or an Opus song called .ogg (some downloaders
     * and old imports name them so), or a 5.1 Ogg the game leaves silent, would otherwise go into the box as it is, and
     * the beatmap could never play it. Its conversion cannot share its name, so it gets one of its own.
     */
    private static Path conversionOf(Path file) {
        String name = file.getFileName().toString();
        if (!name.toLowerCase(java.util.Locale.ROOT).endsWith(".ogg")) {
            return file.resolveSibling(stripExtension(name) + ".ogg");
        }
        return ru.gora.musicbattle.audio.NativeAudio.playsAsIs(file)
                ? null
                : file.resolveSibling(stripExtension(name) + "_converted.ogg");
    }

    /**
     * Converts one file in the background with the mod's own converter, so a long encode never freezes the editor.
     * A format the mod cannot read gets its own message, which says what it does read and where to convert the rest.
     * Every way the conversion can end, an unexpected exception included, ends in a status line, never in a
     * "converting" that stays for good.
     */
    private void convertAudioToOgg(EditorField target, Path source, Path converted) {
        setStatus(StatusLevel.INFO, I18n.get("music_battle.l10n.editor.converting_to_ogg", source.getFileName()));
        net.minecraft.Util.nonCriticalIoPool().execute(() -> {
            Throwable error = null;
            try {
                ru.gora.musicbattle.audio.NativeAudio.convertToVorbis(source, converted, 1.0F, AUDIO_CONVERSION_TIMEOUT_SECONDS);
            } catch (IOException | RuntimeException | Error exception) {
                error = exception;
            }
            Throwable failure = error;
            boolean unsupported = failure instanceof ru.gora.musicbattle.audio.codec.UnsupportedFormatException;
            // What the file turned out to be, such as WMA: a file named .ogg is told it "is not in a format the mod
            // reads" when the mod reads OGG, which only makes sense once the message says what is inside.
            String formatName = failure instanceof ru.gora.musicbattle.audio.codec.UnsupportedFormatException named
                    ? named.formatName() : null;
            if (failure != null && !unsupported) {
                ru.gora.musicbattle.MusicBattleMod.LOGGER.warn("Blocky Music could not convert '{}' to OGG.",
                        MusicBattleTracks.displayPath(source), failure);
            }
            Minecraft.getInstance().execute(() -> {
                if (failure != null) {
                    String name = source.getFileName().toString();
                    failedAudioPick = name;
                    failedAudioPickBox = target.getValue() == null ? "" : target.getValue().trim();
                    setStatus(StatusLevel.ERROR, (!unsupported
                            ? I18n.get("music_battle.l10n.editor.audio_convert_failed", name,
                                    MusicBattleTracks.safeMessage(failure))
                            : formatName != null
                            ? I18n.get("music_battle.l10n.editor.audio_unsupported_named", name, formatName)
                            : I18n.get("music_battle.l10n.editor.audio_unsupported_format", name)));
                    return;
                }
                setBoxValueAtStart(target, converted.getFileName().toString());
                refreshOsuDifficulties();
                setStatus(StatusLevel.OK, I18n.get("music_battle.l10n.editor.converted_to_ogg", converted.getFileName()));
            });
            if (failure instanceof Error fatal) {
                // The status line is answered; an Error still ends this worker as it would have.
                throw fatal;
            }
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
                setStatus(StatusLevel.ERROR, I18n.get("music_battle.l10n.editor.no_raw_files", extension, MusicBattleTracks.rawDirectory()));
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
            setStatus(StatusLevel.OK, I18n.get("music_battle.l10n.editor.selected", chosen.getFileName()));
        } catch (IOException exception) {
            setStatus(StatusLevel.ERROR, exception.getMessage());
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
            setStatus(StatusLevel.ERROR, MusicBattleTracks.safeMessage(exception));
        }
        updateOsuDifficultyButton();
    }

    private void cycleOsuDifficulty() {
        if (osuDifficulties.isEmpty()) {
            refreshOsuDifficulties();
        }
        if (osuDifficulties.isEmpty()) {
            setStatus(StatusLevel.ERROR, I18n.get("music_battle.l10n.editor.osu_select_first"));
            return;
        }
        selectedOsuDifficulty = (selectedOsuDifficulty + 1) % osuDifficulties.size();
        updateOsuDifficultyButton();
        ClientOsuManiaImporter.OsuDifficulty difficulty = osuDifficulties.get(selectedOsuDifficulty);
        setStatus(StatusLevel.OK, I18n.get("music_battle.l10n.editor.osu_difficulty", difficulty.title(), difficulty.noteCount()));
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
        return Component.literal(this.font.plainSubstrByWidth(
                I18n.get("music_battle.l10n.editor.osu_difficulty_button", difficulty.title()), maxWidth));
    }

    private void chooseChart(EditorField target) {
        try {
            List<Path> files = new ArrayList<>();
            files.addAll(rawFiles(".json"));
            files.addAll(rawFiles(".osu"));
            files.addAll(rawFiles(".osz"));
            files.sort(Comparator.comparing(path -> path.getFileName().toString(), String.CASE_INSENSITIVE_ORDER));
            if (files.isEmpty()) {
                setStatus(StatusLevel.ERROR, I18n.get("music_battle.l10n.editor.no_raw_files", ".json/.osu/.osz", MusicBattleTracks.rawDirectory()));
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
            setStatus(StatusLevel.OK, I18n.get("music_battle.l10n.editor.selected", chosen.getFileName()));
        } catch (IOException exception) {
            setStatus(StatusLevel.ERROR, exception.getMessage());
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
            // A saved song or picture shows below the Minecraft folder (ClientTrackExporter.boxValueOf).
            Path shown = MusicBattleTracks.gameFolderFile(path);
            if (shown != null) {
                return shown;
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

    /** One Ctrl+Z step: the notes and effects, and what was selected, since a selection is undone like an edit. */
    private record EditorSnapshot(List<BattleNote> notes, List<TrackEffect> effects,
                                  Set<BattleNote> selectedNotes, Set<TrackEffect> selectedEffects) {
    }

    /**
     * The timeline's editing rules, kept apart from the screen so the editor checks can run them without one: which
     * notes collide, what Ctrl+A takes, how a copied group keeps its rhythm, where Ctrl+V puts it and how a selection
     * moves.
     *
     * <p>Places are counted in lines of the eighth-beat grid, the finer of the editor's two, because every line of
     * either grid is one of its lines: a group copied while 1/8 is showing keeps its eighths when it is pasted under
     * 1/4. A note that sits between lines (from an import) keeps its distance from its nearest line, in ticks, so a
     * note placed on the grid always lands on the grid again.
     */
    public static final class ChartEdits {
        /** The finest grid the editor offers, an eighth of a beat. */
        public static final int FINE_CELLS_PER_BEAT = 8;
        /** A bound on how many beats Ctrl+V walks; the walk stops long before, past the chart's last note. */
        private static final int MAX_PASTE_STEPS = 1_000_000;
        private static final Comparator<BattleNote> NOTE_ORDER = Comparator.comparingInt(BattleNote::tick)
                .thenComparingInt(BattleNote::lane).thenComparingInt(BattleNote::owner);

        private ChartEdits() {
        }

        /**
         * The eighth line a tick stands on: the coarsest line through it, so a note on a beat stays on beats when it is
         * moved or pasted by whole beats, even where eighths are finer than a tick (above 150 BPM); otherwise the
         * nearest eighth.
         */
        public static long fineCell(int tick, int offsetMs, int bpm) {
            return ChartBeat.lineOf(offsetMs, bpm, FINE_CELLS_PER_BEAT, tick);
        }

        /** The tick an eighth line stands on, as the grid rounds it. */
        public static int fineTick(long cell, int offsetMs, int bpm) {
            return ChartBeat.cellTick(offsetMs, bpm, FINE_CELLS_PER_BEAT, cell);
        }

        /**
         * One tick past the last one a note holds. A tap holds its own tick only, so taps on neighbouring lines never
         * collide however the lines round. The editor used to measure a tap by the rounded cell length - 2 ticks for
         * the 1.5625-tick eighth at 96 BPM, 3 for the 2.727-tick quarter at 110 BPM - while neighbouring lines there
         * are 1 or 2 (2 or 3) ticks apart, so the line after a note looked taken and notes could not be placed in a
         * row. A hold holds every tick from its press to its release, both included, since its release is drawn as a
         * box of its own and a note there would sit on it.
         */
        public static int occupiedUntil(BattleNote note) {
            return note.tick() + note.durationTicks() + 1;
        }

        public static boolean overlap(BattleNote first, BattleNote second) {
            return first.owner() == second.owner() && first.lane() == second.lane()
                    && first.tick() < occupiedUntil(second) && second.tick() < occupiedUntil(first);
        }

        /** Whether new notes can join the chart: none may collide with a note there or with another of them. */
        public static boolean fits(List<BattleNote> chart, List<BattleNote> added) {
            return fits(chart, List.of(), added);
        }

        /**
         * Whether {@code replacements} can take the places of {@code originals}, one for one in the same order. None
         * may collide with a note that stays. Two of them may collide only if their originals already did, so a pair
         * an older editor or an import left touching can still be moved together, and no move makes a new collision.
         */
        public static boolean fits(List<BattleNote> chart, List<BattleNote> originals, List<BattleNote> replacements) {
            Set<BattleNote> leaving = new HashSet<>(originals);
            for (int first = 0; first < replacements.size(); first++) {
                BattleNote candidate = replacements.get(first);
                for (BattleNote staying : chart) {
                    if (!leaving.contains(staying) && overlap(candidate, staying)) {
                        return false;
                    }
                }
                for (int second = first + 1; second < replacements.size(); second++) {
                    boolean touchedBefore = second < originals.size() && overlap(originals.get(first), originals.get(second));
                    if (!touchedBefore && overlap(candidate, replacements.get(second))) {
                        return false;
                    }
                }
            }
            return true;
        }

        /** Ctrl+A: the notes of the player being edited that are pressed on the page. */
        public static List<BattleNote> pageSelection(List<BattleNote> chart, int owner, int pageStart, int pageEnd) {
            return chart.stream()
                    .filter(note -> note.owner() == owner && note.tick() >= pageStart && note.tick() < pageEnd)
                    .toList();
        }

        /**
         * A copied note: its press and release counted in eighth lines from the group's first line, each with how many
         * ticks it sat off its line (0 for a note placed on the grid).
         */
        public record CopiedNote(int owner, int lane, long cell, int offset, boolean hold, long releaseCell,
                                 int releaseOffset) {
        }

        /** A copied effect, measured the same way; {@code row} counts down from the group's top row. */
        public record CopiedEffect(TrackEffect effect, int row, long cell, int offset, boolean ranged, long endCell,
                                   int endOffset) {
        }

        /** What Ctrl+C took, and the eighth line its first item stood on. */
        public record Clipboard(List<CopiedNote> notes, List<CopiedEffect> effects, long firstCell) {
            public static final Clipboard EMPTY = new Clipboard(List.of(), List.of(), 0L);

            public boolean isEmpty() {
                return notes.isEmpty() && effects.isEmpty();
            }

            public int size() {
                return notes.size() + effects.size();
            }

            /** The group's place in its beat, in eighth lines: a group copied from the off-beat is pasted on the off-beat. */
            public int phase() {
                return (int) Math.floorMod(firstCell, (long) FINE_CELLS_PER_BEAT);
            }
        }

        /** Ctrl+C, in time order, so a paste adds the notes in the order a chart lists them. */
        public static Clipboard copy(Collection<BattleNote> notes, Collection<TrackEffect> effects, int offsetMs, int bpm) {
            long first = Long.MAX_VALUE;
            for (BattleNote note : notes) {
                first = Math.min(first, fineCell(note.tick(), offsetMs, bpm));
            }
            for (TrackEffect effect : effects) {
                first = Math.min(first, fineCell(effect.tick(), offsetMs, bpm));
            }
            if (first == Long.MAX_VALUE) {
                return Clipboard.EMPTY;
            }
            List<CopiedNote> copiedNotes = new ArrayList<>(notes.size());
            for (BattleNote note : notes.stream().sorted(NOTE_ORDER).toList()) {
                long cell = fineCell(note.tick(), offsetMs, bpm);
                int release = note.tick() + note.durationTicks();
                long releaseCell = fineCell(release, offsetMs, bpm);
                copiedNotes.add(new CopiedNote(note.owner(), note.lane(), cell - first, note.tick() - fineTick(cell, offsetMs, bpm),
                        note.durationTicks() > 0, releaseCell - first, release - fineTick(releaseCell, offsetMs, bpm)));
            }
            int topRow = effects.stream().mapToInt(TrackEditorScreen::absoluteFxRow).min().orElse(0);
            List<CopiedEffect> copiedEffects = new ArrayList<>(effects.size());
            for (TrackEffect effect : effects.stream().sorted(Comparator.comparingInt(TrackEffect::tick)
                    .thenComparingInt(TrackEditorScreen::absoluteFxRow)).toList()) {
                long cell = fineCell(effect.tick(), offsetMs, bpm);
                int end = effect.tick() + effect.durationTicks();
                long endCell = fineCell(end, offsetMs, bpm);
                copiedEffects.add(new CopiedEffect(effect, absoluteFxRow(effect) - topRow, cell - first,
                        effect.tick() - fineTick(cell, offsetMs, bpm), effect.durationTicks() > 0, endCell - first,
                        end - fineTick(endCell, offsetMs, bpm)));
            }
            return new Clipboard(List.copyOf(copiedNotes), List.copyOf(copiedEffects), first);
        }

        /** The notes a group adds with its first line on {@code cell}. */
        public static List<BattleNote> placeNotes(Clipboard clipboard, long cell, int offsetMs, int bpm) {
            List<BattleNote> placed = new ArrayList<>(clipboard.notes().size());
            for (CopiedNote note : clipboard.notes()) {
                int start = fineTick(cell + note.cell(), offsetMs, bpm) + note.offset();
                int release = fineTick(cell + note.releaseCell(), offsetMs, bpm) + note.releaseOffset();
                placed.add(new BattleNote(note.owner(), note.lane(), start, note.hold() ? Math.max(1, release - start) : 0));
            }
            return placed;
        }

        /** The effects a group adds with its first line on {@code cell} and its top row on {@code firstRow}. */
        public static List<TrackEffect> placeEffects(Clipboard clipboard, long cell, int firstRow, int offsetMs, int bpm) {
            List<TrackEffect> placed = new ArrayList<>(clipboard.effects().size());
            for (CopiedEffect copied : clipboard.effects()) {
                int start = fineTick(cell + copied.cell(), offsetMs, bpm) + copied.offset();
                int end = fineTick(cell + copied.endCell(), offsetMs, bpm) + copied.endOffset();
                TrackEffect moved = moveEffectToAbsoluteRow(copied.effect(), firstRow + copied.row());
                placed.add(moved.withTimeline(moved.lane(), start, copied.ranged() ? Math.max(1, end - start) : 0));
            }
            return placed;
        }

        /**
         * Where Ctrl+V puts a group: its first line goes on {@code fromCell} (a line with the group's place in the beat)
         * or a whole number of beats later - the first such place where every item lands in the song and the group's
         * stretch of time, from its first press to its last release, holds nothing of the same players (for effects:
         * of the same rows). Pasted where it was copied, a group therefore lands right after itself and the next paste
         * after that one, and groups never interleave, which testing note against note would allow.
         */
        public static long pasteCell(List<BattleNote> chartNotes, List<TrackEffect> chartEffects, Clipboard clipboard,
                                     long fromCell, int firstRow, int offsetMs, int bpm) {
            Set<Integer> owners = new HashSet<>();
            clipboard.notes().forEach(note -> owners.add(note.owner()));
            Set<Integer> rows = new HashSet<>();
            clipboard.effects().forEach(effect -> rows.add(Math.max(0, Math.min(TrackEffect.MAX_FX_ROWS - 1, firstRow + effect.row()))));
            // Past this tick nothing of those players or rows lies, so the first place that starts there is free.
            int busyUntil = 0;
            for (BattleNote note : chartNotes) {
                if (owners.contains(note.owner())) {
                    busyUntil = Math.max(busyUntil, occupiedUntil(note));
                }
            }
            for (TrackEffect effect : chartEffects) {
                if (rows.contains(absoluteFxRow(effect))) {
                    busyUntil = Math.max(busyUntil, effectUntil(effect));
                }
            }
            long cell = fromCell;
            for (int step = 0; step < MAX_PASTE_STEPS; step++, cell += FINE_CELLS_PER_BEAT) {
                int start = Integer.MAX_VALUE;
                int end = Integer.MIN_VALUE;
                boolean inSong = true;
                for (CopiedNote note : clipboard.notes()) {
                    int press = fineTick(cell + note.cell(), offsetMs, bpm) + note.offset();
                    int release = note.hold() ? Math.max(press + 1, fineTick(cell + note.releaseCell(), offsetMs, bpm)
                            + note.releaseOffset()) : press;
                    inSong &= press >= 0;
                    start = Math.min(start, press);
                    end = Math.max(end, release + 1);
                }
                for (CopiedEffect effect : clipboard.effects()) {
                    int from = fineTick(cell + effect.cell(), offsetMs, bpm) + effect.offset();
                    int until = effect.ranged() ? Math.max(from + 1, fineTick(cell + effect.endCell(), offsetMs, bpm)
                            + effect.endOffset()) : from + 1;
                    inSong &= from >= 0;
                    start = Math.min(start, from);
                    end = Math.max(end, until);
                }
                if (inSong && (start >= busyUntil || free(chartNotes, chartEffects, owners, rows, start, end))) {
                    return cell;
                }
            }
            return cell;
        }

        private static boolean free(List<BattleNote> chartNotes, List<TrackEffect> chartEffects, Set<Integer> owners,
                                    Set<Integer> rows, int start, int end) {
            for (BattleNote note : chartNotes) {
                if (owners.contains(note.owner()) && note.tick() < end && occupiedUntil(note) > start) {
                    return false;
                }
            }
            for (TrackEffect effect : chartEffects) {
                if (rows.contains(absoluteFxRow(effect)) && effect.tick() < end && effectUntil(effect) > start) {
                    return false;
                }
            }
            return true;
        }

        private static int effectUntil(TrackEffect effect) {
            return effect.tick() + Math.max(1, effect.durationTicks());
        }

        /** A tick moved by whole eighth lines: its line moves, and its distance from that line stays. */
        public static int shiftTick(int tick, long cells, int offsetMs, int bpm) {
            long cell = fineCell(tick, offsetMs, bpm);
            return fineTick(cell + cells, offsetMs, bpm) + (tick - fineTick(cell, offsetMs, bpm));
        }

        /**
         * Notes moved by whole eighth lines and by lanes, in the order given; a hold's release moves line for line with
         * its press. {@code null} when one would start before the song: those used to be squeezed onto tick 0.
         */
        public static List<BattleNote> shiftNotes(List<BattleNote> notes, long cells, int laneDelta, int offsetMs, int bpm) {
            List<BattleNote> moved = new ArrayList<>(notes.size());
            for (BattleNote note : notes) {
                int start = shiftTick(note.tick(), cells, offsetMs, bpm);
                if (start < 0) {
                    return null;
                }
                int duration = note.durationTicks() > 0
                        ? Math.max(1, shiftTick(note.tick() + note.durationTicks(), cells, offsetMs, bpm) - start) : 0;
                int lane = Math.max(0, Math.min(NOTE_LANES - 1, note.lane() + laneDelta));
                moved.add(new BattleNote(note.owner(), lane, start, duration));
            }
            return moved;
        }

        /** Effects moved the same way, by eighth lines and by rows; {@code null} when one would start before the song. */
        public static List<TrackEffect> shiftEffects(List<TrackEffect> effects, long cells, int rowDelta, int offsetMs, int bpm) {
            List<TrackEffect> moved = new ArrayList<>(effects.size());
            for (TrackEffect effect : effects) {
                int start = shiftTick(effect.tick(), cells, offsetMs, bpm);
                if (start < 0) {
                    return null;
                }
                int duration = effect.durationTicks() > 0
                        ? Math.max(1, shiftTick(effect.tick() + effect.durationTicks(), cells, offsetMs, bpm) - start) : 0;
                TrackEffect onRow = moveEffectToAbsoluteRow(effect, absoluteFxRow(effect) + rowDelta);
                moved.add(onRow.withTimeline(onRow.lane(), start, duration));
            }
            return moved;
        }
    }

    private EditorLayout layout() {
        int maxPanelWidth = this.width >= 1200 ? 1220 : 920;
        int panelWidth = Mth.clamp(this.width - 40, 240, maxPanelWidth);
        boolean compactHeight = this.height < 500;
        int gap = compactHeight ? 8 : 18;
        // Two columns only where the audio path, after the BPM and the volume slider, keeps a field that holds its
        // label: from 520 units it came out 96 wide and cut "Audio from music_battle_raw".
        boolean twoColumns = panelWidth >= 520 && (panelWidth - gap) / 2 - WIDE_AUDIO_X >= AUDIO_FIELD_MIN;
        int columnWidth = twoColumns ? (panelWidth - gap) / 2 : panelWidth;
        // The narrow form gives the audio path a row of its own when the rest of the BPM row is too short for it: at
        // 320 units it was 44 wide, "Музыка и…" over a hint nobody could read.
        boolean audioRow = !twoColumns && panelWidth - NARROW_AUDIO_X < AUDIO_FIELD_MIN;
        int rows = editorPage == EditorPage.FX ? 0 : twoColumns ? 3 : audioRow ? 5 : 4;
        int rowHeight = compactHeight ? 31 : 34;
        int pad = compactHeight ? 6 : 10;
        int formTop = TOP_BAR + pad + 4 - scrollY;
        int controlsY = formTop + rows * rowHeight + (rows > 0 ? 2 : 0);
        int left = this.width / 2 - panelWidth / 2;
        // Six buttons a row as soon as they can carry their labels: three rows of controls instead of six. At 560 a
        // sixth of a tall window's panel was 85 units, and "Режим: 2 точки" did not fit it.
        boolean wideControls = panelWidth >= 580;
        int buttonHeight = compactHeight ? 18 : 20;
        int rowStep = compactHeight ? 21 : 24;
        int controlRows = wideControls ? 3 : 6;
        int controlsHeight = controlRows * rowStep - (rowStep - buttonHeight);
        int previewSize = editorPage != EditorPage.NOTES ? 0
                : twoColumns ? (compactHeight ? 42 : 56) : panelWidth >= 300 ? 18 : 0;
        int gridY = controlsY + controlsHeight + pad + (compactHeight ? 6 : 10) + 32;
        // The grid keeps one height however far the page is scrolled, sized so it fits the window whole.
        int gridHeight = Mth.clamp(this.height - TOP_BAR - 32 - pad * 2 - (compactHeight ? 6 : 10), 64, compactHeight ? 136 : 230);
        int contentBottom = gridY + scrollY + gridHeight + pad + 4;
        return new EditorLayout(left, panelWidth, columnWidth, gap, formTop, controlsY, controlsHeight, previewSize,
                twoColumns, audioRow, compactHeight, wideControls, rowHeight, pad, buttonHeight, rowStep, gridY, gridHeight,
                contentBottom);
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
        int bpmWidth = BPM_WIDTH;
        // The offset field carries the one-press "beat 1 here" button right after it.
        int offsetWidth = OFFSET_WIDTH;
        int beatOneWidth = BEAT_ONE_WIDTH;
        int offsetBlock = offsetWidth + 2 + beatOneWidth;
        int volumeX = bpmWidth + 6;
        int volumeWidth = VOLUME_WIDTH;
        int audioX = WIDE_AUDIO_X;
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
        // In one column the offset sits beside the BPM it belongs to; the audio path keeps the rest of the row, or a
        // row of its own under it when that rest is too short (layout().audioRow).
        int offsetX = bpmWidth + 4;
        volumeX = offsetX + offsetBlock + 6;
        audioX = NARROW_AUDIO_X;
        FieldSlot audio = layout.audioRow
                ? new FieldSlot(left, boxY + rowHeight * 3, layout.panelWidth)
                : new FieldSlot(left + audioX, boxY + rowHeight * 2, layout.panelWidth - audioX);
        int mediaRow = layout.audioRow ? 4 : 3;
        return new FormSlots(
                new FieldSlot(left, boxY, half),
                new FieldSlot(rightHalf, boxY, otherHalf),
                new FieldSlot(left, boxY + rowHeight, layout.panelWidth),
                new FieldSlot(left, boxY + rowHeight * 2, bpmWidth),
                new FieldSlot(left + volumeX, boxY + rowHeight * 2, volumeWidth),
                audio,
                new FieldSlot(left, boxY + rowHeight * mediaRow, Math.max(80, half - previewReserve)),
                new FieldSlot(rightHalf, boxY + rowHeight * mediaRow, otherHalf),
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
            boolean audioRow,
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

    /**
     * A note on the page: {@code left..right} is all that is drawn of it, {@code head*} its press box, {@code release*}
     * a hold's release box, and {@code hit*} the area that takes a click on it, its boxes edge to edge in a lane's full
     * height. The edge flags say which ends of a hold can be grabbed to resize it.
     */
    private record NoteBounds(int left, int right, int top, int bottom,
                              int headLeft, int headRight, boolean headVisible,
                              int releaseLeft, int releaseRight, boolean releaseVisible,
                              int hitLeft, int hitRight, int hitTop, int hitBottom,
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
            // Drawn by the toolkit, like every other slider in the mod, without its label: the value is drawn in the
            // label row above. Its knob then sits where vanilla reads the pointer; this slider's own knob ran 3 units
            // further in at both ends, so a click on it moved the volume.
            ru.gora.musicbattle.client.ui.Slider.paint(graphics, ru.gora.musicbattle.client.workshop.layout.Rect.of(getX(),
                    getY(), getWidth(), getHeight()), this.value, Component.empty(), isHoveredOrFocused(), this.active);
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

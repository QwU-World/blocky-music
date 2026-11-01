package ru.gora.musicbattle.client;

import static ru.gora.musicbattle.client.ClientTranslations.text;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.InputConstants;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.neoforged.fml.loading.FMLPaths;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;
import ru.gora.musicbattle.MusicBattleMod;
import ru.gora.musicbattle.client.ui.HandCursor;
import ru.gora.musicbattle.client.ui.Palette;
import ru.gora.musicbattle.client.ui.RoundedRect;
import ru.gora.musicbattle.client.ui.TextMeasurer;
import ru.gora.musicbattle.client.ui.TextStyle;
import ru.gora.musicbattle.client.ui.TextStyles;
import ru.gora.musicbattle.client.ui.UiIcon;
import ru.gora.musicbattle.client.workshop.WorkshopDraw;
import ru.gora.musicbattle.client.workshop.layout.Rect;
import ru.gora.musicbattle.client.workshop.layout.TextFit;
import ru.gora.musicbattle.data.BattleNote;
import ru.gora.musicbattle.data.CalibrationTiming;
import ru.gora.musicbattle.data.TrackEffect;

/** Local, network-free battle sandbox launched from the editor's current page. */
public final class TrackGameplayPreviewScreen extends Screen {
    private static final int EARLY_FAIL_TICKS = 11;
    private static final int SAME_LANE_HIT_LOCKOUT_TICKS = 2;
    private static final int HOLD_RELEASE_EARLY_TICKS = 2;
    private static final String[] DEFAULT_LABELS = {"<", "v", "^", ">"};
    private static final float FOREGROUND_Z = 1000.0F;
    /** The corner buttons: Back to the editor on the left and, without a world, the background colour on the right. */
    private static final int CORNER_BUTTON_WIDTH = 104;
    private static final int CORNER_MARGIN = 8;
    /** Between a corner button and the header card. */
    private static final int CORNER_GAP = 6;
    /** The narrowest header card the corner buttons make room for; see {@link #corners}. */
    static final int CARD_MIN = 120;
    /**
     * A label keeps this much room from its button's edges (the room a 104-unit Back leaves the longest Russian label
     * at GUI scale 1); a swatch sits {@code SWATCH_PAD} in from the edge and from its label.
     */
    static final int LABEL_PAD = 6;
    static final int SWATCH_PAD = 5;
    /** The arrow in front of the short Back, and its gap to the word. */
    private static final float ARROW = 9F;
    private static final float ARROW_GAP = 4F;
    private static final Component BACK = Component.translatable("music_battle.l10n.editor.preview_back");
    /** Vanilla's own "Back", which every language the game ships has: the narrow form of {@link #BACK}. */
    private static final Component BACK_SHORT = Component.translatable("gui.back");
    private static final Component BACKGROUND = Component.translatable("music_battle.l10n.editor.preview_background");
    /** Where the background colour is kept between sessions, next to the mod's other client settings. */
    private static final String SETTINGS_FILE = "music_battle-editor-preview.json";
    private static final int DEFAULT_BACKGROUND = Palette.BG_0;
    /** The fill behind a preview with no world to show; {@code null} until it is first read from the settings file. */
    private static Integer backgroundColor;
    private final Screen parent;
    private final Path audioPath;
    private final Path previewPath;
    private final String trackTitle;
    private final int bpm;
    /**
     * Beat 1 on the preview's clock, in ticks: the chart's offset, as the battle has it. The pulse, the combo's beat, the
     * cover's beat, the poses and every beat effect count from here, as they do in the battle this previews.
     */
    private final float beatOrigin;
    private final float volume;
    private final int durationTicks;
    private final int startTick;
    private final int owner;
    private final List<PreviewNote> notes;
    private final List<TrackEffect> effects;
    private final boolean disableStageBars;
    private final boolean hideStandardDimming;
    private final boolean[] laneDown = new boolean[4];
    private final int[][] laneFlashes = new int[2][4];
    private final int[][] hitBursts = new int[2][4];
    private final int[] laneHitLockoutUntil = new int[4];
    private final int[] scores = new int[2];
    private final int[] combos = new int[2];
    private SoundInstance sound;
    private long startedAtNanos;
    private int clockStartTick;
    private boolean soundSubmitted;
    private boolean clockStarted;
    private volatile long audioStartedSignalNanos;
    private boolean finished;
    private int misses;
    private int animationTriggerSerial;
    private int animationTriggerLane = -1;
    private int animationHeldMask;
    /** The colour picker is open over this screen, which keeps playing behind it: leaving for it must not stop the song. */
    private boolean choosingBackground;
    /** The corner buttons and the header card for this window, from {@link #corners}; set on every init. */
    private Corners corners;

    public TrackGameplayPreviewScreen(Screen parent, Path audioPath, Path previewPath, String trackTitle, int bpm,
                                      int offsetMs, float volume,
                                      int durationTicks, int startTick, int owner, List<BattleNote> sourceNotes,
                                      List<TrackEffect> effects, boolean disableStageBars, boolean hideStandardDimming) {
        super(Component.literal(text("music_battle.l10n.client.116")));
        this.parent = parent;
        this.audioPath = audioPath;
        this.previewPath = previewPath;
        this.trackTitle = trackTitle;
        this.bpm = Mth.clamp(bpm, 20, 400);
        this.beatOrigin = (float) ru.gora.musicbattle.data.ChartBeat.originTicks(
                ru.gora.musicbattle.data.ChartBeat.clampOffsetMs(offsetMs));
        this.volume = Mth.clamp(volume, 0.0F, 2.0F);
        this.durationTicks = Math.max(1, durationTicks);
        this.startTick = Math.max(0, startTick);
        this.clockStartTick = this.startTick;
        this.owner = Mth.clamp(owner, 0, 1);
        this.effects = List.copyOf(effects);
        this.disableStageBars = disableStageBars;
        this.hideStandardDimming = hideStandardDimming;
        this.notes = sourceNotes.stream()
                .sorted(Comparator.comparingInt(BattleNote::tick).thenComparingInt(BattleNote::lane))
                .map(note -> new PreviewNote(note.owner(), note.lane(), note.tick(), note.durationTicks(), note.tick() < this.startTick))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    }

    @Override
    protected void init() {
        choosingBackground = false;
        corners = corners(this.width, TextStyles.measurer(), BACK.getString(), BACK_SHORT.getString(),
                BACKGROUND.getString());
        this.addRenderableWidget(new BackButton(CORNER_MARGIN, 7, corners.buttonWidth(), 20, corners.compact()));
        if (this.minecraft.level == null) {
            // Opposite Back, so the battle header between them stays clear of both at any window size.
            this.addRenderableWidget(new BackgroundButton(this.width - CORNER_MARGIN - corners.buttonWidth(), 7,
                    corners.buttonWidth(), 20, corners.backgroundLabel()));
        }
        if (!soundSubmitted) {
            ClientAudioAnalyzer.activate(audioPath);
            sound = new ExternalOggSoundInstance("editor_gameplay_preview", audioPath.getFileName().toString(), audioPath,
                    startTick, true, 0.0D, 0.0D, 0.0D, SoundInstance.Attenuation.NONE, effectiveVolume());
            this.minecraft.getSoundManager().play(sound);
            soundSubmitted = true;
        }
    }

    @Override
    public void tick() {
        super.tick();
        refreshSoundVolume();
        if (!clockStarted) {
            if (audioStartedSignalNanos > 0L) {
                startedAtNanos = audioStartedSignalNanos;
                clockStarted = true;
            }
            return;
        }
        if (finished) {
            return;
        }
        float time = currentTick();
        int gameplayTick = Math.max(0, Mth.floor(time));
        int inputTick = CalibrationTiming.inputTick(time, ClientBattleSettings.inputOffsetMs());
        for (int panelOwner = 0; panelOwner < 2; panelOwner++) {
            for (int lane = 0; lane < 4; lane++) {
                if (laneFlashes[panelOwner][lane] > 0) {
                    laneFlashes[panelOwner][lane]--;
                }
                if (hitBursts[panelOwner][lane] > 0) {
                    hitBursts[panelOwner][lane]--;
                }
            }
        }
        for (PreviewNote note : notes) {
            if (note.resolved) {
                continue;
            }
            if (note.owner != owner) {
                tickPreviewOpponent(note, gameplayTick);
            } else if (note.started && note.durationTicks > 0 && inputTick >= note.tick + note.durationTicks
                    && laneDown[note.lane]) {
                completeHold(note);
            } else if (!note.started && inputTick - note.tick >= BattlePanelRenderer.MISS_AFTER_PANEL_EXIT_TICKS) {
                resolveMiss(note, false);
            }
        }
        if (time >= durationTicks + Math.max(0,
                Math.max(ClientBattleSettings.audioOffsetMs(), ClientBattleSettings.inputOffsetMs())) / 50.0F) {
            finished = true;
            stopSound();
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        float time = currentTick();
        // Sampled here rather than through sampleAt: render reads only fields and static calls, which keeps it testable
        // by the fx-visibility probe that runs a copy of this method.
        ClientTrackEffects.EffectState state = ClientTrackEffects.sample(effects, time, bpm, 1.0F, 1.0F, beatOrigin);
        if (this.minecraft.level == null) {
            // Opened from the title screen there is no world to draw behind the battle, and the menus' panorama comes
            // from renderBackground, which this screen leaves empty for the world's sake: there was no background at
            // all. The player's colour stands in for the world.
            graphics.fill(0, 0, this.width, this.height, background());
        }
        drawGameplayScene(graphics, !state.hidesBlockyMusicHud());
        graphics.flush();

        // InventoryScreen renders the preview entity at Z=50 and flushes its
        // own 3D batch. Keep every subsequent GUI/effect pass on an explicit
        // foreground layer so the entity depth buffer can never cover it.
        graphics.pose().pushPose();
        graphics.pose().translate(0.0F, 0.0F, FOREGROUND_Z);
        if (!state.hidesBlockyMusicHud() && !disableStageBars) {
            drawStageBars(graphics);
        }
        ClientTrackEffects.renderBelowInterface(graphics, state);
        graphics.flush();
        if (!state.hidesBlockyMusicHud()) drawGameplayHud(graphics, time);
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.flush();
        ClientTrackEffects.renderAboveInterface(graphics, state);
        graphics.flush();
        graphics.pose().popPose();
    }

    /**
     * The world behind the preview is dimmed and barred as this player's battle does it (BattleBackdrop with the
     * player's settings), so the preview shows the beatmap as this player will see it; the beatmap's own switches
     * (hideStandardDimming, disableStageBars) still win, because the conditions around both calls stay as they were.
     */
    private void drawGameplayScene(GuiGraphics graphics, boolean showHud) {
        if (showHud && !hideStandardDimming) {
            BattleBackdrop.dim(graphics::fill, this.width, this.height, ClientBattleSettings.worldDimmingAlpha());
        }
        drawPreviewPlayer(graphics);
    }

    private void drawStageBars(GuiGraphics graphics) {
        BattleBackdrop.bars(graphics::fill, this.width, this.height, ClientBattleSettings.stageBarAlpha());
    }

    private void drawGameplayHud(GuiGraphics graphics, float time) {
        drawBattlePanel(graphics, time, 0);
        drawBattlePanel(graphics, time, 1);
        drawBattleHeader(graphics, time);
        if (!clockStarted) {
            graphics.drawCenteredString(this.font, text("music_battle.l10n.client.117"), this.width / 2, 40, 0xFFFFE78A);
        } else if (finished) {
            // Names the button by its label, which used to be the untranslated "Exit Preview".
            graphics.drawCenteredString(this.font, Component.translatable("music_battle.l10n.editor.preview_finished",
                    Component.translatable("music_battle.l10n.editor.preview_back")), this.width / 2, this.height - 20, 0xFFFFE78A);
        } else {
            graphics.drawCenteredString(this.font, text("music_battle.l10n.client.119") + formatTime(startTick), this.width / 2, this.height - 20, 0xFFB9D7E5);
        }
    }

    private void drawBattlePanel(GuiGraphics graphics, float time, int panelOwner) {
        boolean own = panelOwner == owner;
        int color = own ? ClientBattleSettings.panelColor() : (panelOwner == 0 ? 0xFF37BFE8 : 0xFFFF5C7A);
        String name = own && this.minecraft.player != null
                ? this.minecraft.player.getName().getString()
                : panelOwner == 0 ? "P1" : "P2";
        String[] labels = own
                ? ClientBattleSettings.keyLabels().toArray(String[]::new)
                : DEFAULT_LABELS;
        BattlePanelRenderer.draw(graphics, this.font, this.width, this.height, time, bpm, beatOrigin, 1.0F, notes,
                new BattlePanelRenderer.Panel(
                        panelOwner,
                        panelOwner,
                        own,
                        name,
                        ClientBattleSettings.noteSpeed(),
                        color,
                        ClientBattleSettings.panelOpacity(),
                        ClientBattleSettings.laneOpacity(),
                        ClientBattleSettings.panelWidthScale(),
                        ClientBattleSettings.panelHeightScale(),
                        ClientBattleSettings.noteWidthScale(),
                        ClientBattleSettings.noteHeightScale(),
                        scores[panelOwner],
                        combos[panelOwner],
                        laneFlashes[panelOwner],
                        hitBursts[panelOwner],
                        labels,
                        own ? ClientBattleSettings.appearance() : ru.gora.musicbattle.data.NoteAppearance.solid(color)
                ));
    }

    private void drawPreviewPlayer(GuiGraphics graphics) {
        if (this.minecraft.player == null) return;
        int center = this.width / 2;
        int bottom = this.height - 34;
        int top = Math.max(40, bottom - 146);
        graphics.enableScissor(center - 58, top, center + 58, bottom);
        Quaternionf pose = new Quaternionf().rotateZ((float) Math.PI);
        Quaternionf camera = new Quaternionf().rotateX(0.05F);
        pose.mul(camera);
        float body = this.minecraft.player.yBodyRot;
        float yaw = this.minecraft.player.getYRot();
        float pitch = this.minecraft.player.getXRot();
        float headOld = this.minecraft.player.yHeadRotO;
        float head = this.minecraft.player.yHeadRot;
        this.minecraft.player.yBodyRot = 90.0F;
        this.minecraft.player.setYRot(90.0F);
        this.minecraft.player.setXRot(0.0F);
        this.minecraft.player.yHeadRotO = 90.0F;
        this.minecraft.player.yHeadRot = 90.0F;
        float entityScale = this.minecraft.player.getScale();
        Vector3f translate = new Vector3f(0.0F, this.minecraft.player.getBbHeight() / 2.0F + 0.0625F * entityScale, 0.0F);
        ClientModelGlowEngine.beginMirroredGuiRender();
        try {
            InventoryScreen.renderEntityInInventory(graphics, center, (top + bottom) / 2.0F, 52.0F / entityScale,
                    translate, pose, camera, this.minecraft.player);
        } finally {
            ClientModelGlowEngine.endMirroredGuiRender();
        }
        this.minecraft.player.yBodyRot = body;
        this.minecraft.player.setYRot(yaw);
        this.minecraft.player.setXRot(pitch);
        this.minecraft.player.yHeadRotO = headOld;
        this.minecraft.player.yHeadRot = head;
        graphics.disableScissor();
    }

    /**
     * The battle's own header card (BattlePanelRenderer.drawHeader), but only as wide as the room between the corner
     * buttons (see {@link #corners}): at the GUI scales laptops choose (about 430 GUI pixels across) the battle's width
     * ran under Back, and the title is cut to the card rather than running under the buttons. The preview has no
     * count-in, so the card never shows the READY pill.
     */
    private void drawBattleHeader(GuiGraphics graphics, float time) {
        float progress = Mth.clamp(Math.max(0.0F, time) / durationTicks, 0.0F, 1.0F);
        int remaining = Math.max(0, (durationTicks - Math.max(0, (int) time) + 19) / 20);
        BattlePanelRenderer.drawHeader(graphics, corners.card(),
                new BattlePanelRenderer.Header(trackTitle, ClientPreviewTextures.get("editor", previewPath), progress,
                        remaining, 0, beatPulse(time), 1.0F));
        graphics.flush();
    }

    /**
     * The corner buttons and the header card between them: both buttons {@code buttonWidth} wide, Back from the left
     * edge and Background from the right, and the card centred in the room left between them.
     *
     * @param compact Back shows the arrow and the short word; Background shows its label only if {@code backgroundLabel}
     */
    record Corners(int buttonWidth, boolean compact, boolean backgroundLabel, BattlePanelRenderer.HeaderLayout card) {
    }

    /**
     * How the corner buttons and the card share a screen this wide. The buttons take what the card leaves them at its
     * {@link #CARD_MIN}, up to 104 units, so from 356 units up they are 104 and the card grows; below that the card
     * keeps its minimum and the buttons shrink. Once they are too narrow for their whole labels, Back shows the arrow
     * and the short "Back" and Background its swatch, with its label only if that fits beside it, at the same width:
     * only the words change there, not the card. On a window too narrow even for that, the buttons stay as wide as the
     * arrow and the short word need and the card takes the rest. Widening the window never narrows the card: the narrow
     * form used to size the buttons to its words, and the card was up to 87 units wider just before the whole labels
     * came in than just after. Below 356 the card used to keep its 120 units and run under both buttons, which drew
     * over it. The two buttons stay one width, so the card stays centred over the battle. Pure: the labels are measured
     * through {@code measurer}, for battle-panel-geometry.
     */
    static Corners corners(int width, TextMeasurer measurer, String back, String backShort, String background) {
        int share = Math.min(CORNER_BUTTON_WIDTH, (width - (CORNER_MARGIN + CORNER_GAP) * 2 - CARD_MIN) / 2);
        float labels = Math.max(backWidth(measurer, back, false), backgroundWidth(measurer, background));
        boolean compact = share < labels;
        int narrowest = Math.min(CORNER_BUTTON_WIDTH, (int) Math.ceil(backWidth(measurer, backShort, true)));
        int button = compact ? Math.max(share, narrowest) : share;
        boolean backgroundLabel = backgroundWidth(measurer, background) <= button;
        int card = Math.max(0, Math.min(cardRoom(width, button), BattlePanelRenderer.HeaderLayout.MAX_WIDTH));
        return new Corners(button, compact, backgroundLabel, BattlePanelRenderer.HeaderLayout.centred(width, card));
    }

    /** The card's room between two buttons this wide, with the margins and the gaps on both sides. */
    private static int cardRoom(int width, int button) {
        return width - (CORNER_MARGIN + button + CORNER_GAP) * 2;
    }

    /** What Back needs: its whole label, or the arrow and the short word, with {@link #LABEL_PAD} either side. */
    static float backWidth(TextMeasurer measurer, String label, boolean compact) {
        float text = TextFit.width(measurer, label, TextStyle.BODY);
        return text + LABEL_PAD * 2 + (compact ? ARROW + ARROW_GAP : 0F);
    }

    /** What Background needs to show its swatch and its whole label. */
    static float backgroundWidth(TextMeasurer measurer, String label) {
        return SWATCH_PAD + swatch() + SWATCH_PAD + TextFit.width(measurer, label, TextStyle.BODY) + LABEL_PAD;
    }

    /** The colour swatch of a 20-unit button. */
    private static float swatch() {
        return 20 - 8;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            this.onClose();
            return true;
        }
        for (int lane = 0; lane < 4; lane++) {
            if (MusicBattleKeys.lanes()[lane].getKey().equals(InputConstants.getKey(keyCode, scanCode))) {
                pressLane(lane);
                return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean keyReleased(int keyCode, int scanCode, int modifiers) {
        for (int lane = 0; lane < 4; lane++) {
            if (MusicBattleKeys.lanes()[lane].getKey().equals(InputConstants.getKey(keyCode, scanCode))) {
                releaseLane(lane);
                return true;
            }
        }
        return super.keyReleased(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        InputConstants.Key mouseKey = InputConstants.Type.MOUSE.getOrCreate(button);
        for (int lane = 0; lane < 4; lane++) {
            if (MusicBattleKeys.lanes()[lane].getKey().equals(mouseKey)) {
                pressLane(lane);
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        InputConstants.Key mouseKey = InputConstants.Type.MOUSE.getOrCreate(button);
        for (int lane = 0; lane < 4; lane++) {
            if (MusicBattleKeys.lanes()[lane].getKey().equals(mouseKey)) {
                releaseLane(lane);
                return true;
            }
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    private void pressLane(int lane) {
        if (laneDown[lane] || finished) {
            return;
        }
        laneDown[lane] = true;
        animationTriggerLane = lane;
        animationTriggerSerial++;
        laneFlashes[owner][lane] = 6;
        int inputTick = CalibrationTiming.inputTick(currentTick(), ClientBattleSettings.inputOffsetMs());
        if (inputTick < laneHitLockoutUntil[lane]) {
            return;
        }
        PreviewNote best = null;
        int bestDistance = Math.max(BattlePanelRenderer.MISS_AFTER_PANEL_EXIT_TICKS,
                BattlePanelRenderer.EARLY_HIT_WINDOW_TICKS) + 1;
        int earlyHitWindow = BattlePanelRenderer.earlyHitWindowTicks(ClientBattleSettings.noteSpeed());
        for (PreviewNote note : notes) {
            if (note.owner != owner || note.resolved || note.started || note.lane != lane) {
                continue;
            }
            int earlyDistance = note.tick - inputTick;
            int lateDistance = inputTick - note.tick;
            boolean hittable = earlyDistance >= 0
                    ? earlyDistance <= earlyHitWindow
                    : lateDistance <= BattlePanelRenderer.MISS_AFTER_PANEL_EXIT_TICKS;
            int distance = Math.abs(earlyDistance);
            if (hittable && distance < bestDistance) {
                best = note;
                bestDistance = distance;
            }
        }
        if (best == null) {
            if (shouldBreakComboForBadPress(lane, inputTick)) {
                breakComboForMistake(lane, true);
            }
            return;
        }
        registerNoteHit(best, inputTick);
        laneHitLockoutUntil[lane] = inputTick + SAME_LANE_HIT_LOCKOUT_TICKS;
    }

    private void releaseLane(int lane) {
        laneDown[lane] = false;
        animationHeldMask &= ~(1 << lane);
        int inputTick = CalibrationTiming.inputTick(currentTick(), ClientBattleSettings.inputOffsetMs());
        for (PreviewNote note : notes) {
            if (note.owner == owner && !note.resolved && note.started && note.lane == lane) {
                if (inputTick >= note.tick + note.durationTicks - HOLD_RELEASE_EARLY_TICKS) {
                    completeHold(note);
                } else {
                    note.resolved = true;
                    note.started = false;
                    breakComboForMistake(lane, true);
                }
                return;
            }
        }
    }

    private void registerNoteHit(PreviewNote note, int inputTick) {
        if (note.resolved) {
            return;
        }
        combos[note.owner]++;
        scores[note.owner] += timingScore(note.tick, inputTick) + Math.min(50, combos[note.owner] * 2);
        note.started = note.durationTicks > 0;
        note.resolved = note.durationTicks <= 0;
        if (note.owner == owner && note.started) {
            animationHeldMask |= 1 << note.lane;
        }
        laneFlashes[note.owner][note.lane] = 8;
        hitBursts[note.owner][note.lane] = 8;
    }

    private void completeHold(PreviewNote note) {
        if (note.resolved) {
            return;
        }
        note.resolved = true;
        note.started = false;
        scores[note.owner] += Math.max(20, note.durationTicks / 2);
        if (note.owner == owner) {
            animationHeldMask &= ~(1 << note.lane);
        }
    }

    private void resolveMiss(PreviewNote note, boolean flashLane) {
        if (note.resolved) {
            return;
        }
        note.resolved = true;
        note.started = false;
        breakComboForMistake(note.lane, flashLane);
    }

    private void breakComboForMistake(int lane, boolean flashLane) {
        combos[owner] = 0;
        scores[owner] = Math.max(0, scores[owner] - 24);
        misses++;
        if (flashLane) {
            laneFlashes[owner][lane] = 4;
        }
    }

    private boolean shouldBreakComboForBadPress(int pressedLane, int inputTick) {
        int earlyHitWindow = BattlePanelRenderer.earlyHitWindowTicks(ClientBattleSettings.noteSpeed());
        for (PreviewNote note : notes) {
            if (note.resolved || note.started || note.owner != owner) {
                continue;
            }
            int earlyDistance = note.tick - inputTick;
            int lateDistance = inputTick - note.tick;
            if (note.lane == pressedLane) {
                if (earlyDistance > earlyHitWindow && earlyDistance <= earlyHitWindow + EARLY_FAIL_TICKS) {
                    return true;
                }
            } else if (earlyDistance <= earlyHitWindow
                    && lateDistance <= BattlePanelRenderer.MISS_AFTER_PANEL_EXIT_TICKS) {
                return true;
            }
        }
        return false;
    }

    private void tickPreviewOpponent(PreviewNote note, int gameplayTick) {
        if (!note.started && gameplayTick >= note.tick) {
            registerNoteHit(note, note.tick);
        }
        if (!note.resolved && note.started && gameplayTick >= note.tick + note.durationTicks) {
            completeHold(note);
        }
    }

    private static int timingScore(int noteTick, int inputTick) {
        int earlyDistance = noteTick - inputTick;
        int lateDistance = inputTick - noteTick;
        int distance = Math.abs(noteTick - inputTick);
        if (distance <= 2) {
            return 100;
        }
        if (earlyDistance > 0 && earlyDistance <= 3) {
            return 78;
        }
        if (earlyDistance > 0) {
            return 48;
        }
        if (lateDistance <= 6) {
            return 70;
        }
        if (lateDistance <= 14) {
            return 48;
        }
        return 26;
    }

    private float currentTick() {
        if (!clockStarted || finished) {
            return finished ? durationTicks : clockStartTick;
        }
        return clockStartTick + Math.max(0L, System.nanoTime() - startedAtNanos) / 50_000_000.0F;
    }

    ClientTrackEffects.EffectState currentEffectState() {
        return sampleAt(currentTick());
    }

    ClientTrackEffects.EffectState effectStateAt(float tick) {
        return sampleAt(tick);
    }

    private ClientTrackEffects.EffectState sampleAt(float tick) {
        return ClientTrackEffects.sample(effects, tick, bpm, 1.0F, 1.0F, beatOrigin);
    }

    float previewBeatOrigin() { return beatOrigin; }

    int previewOwner() { return owner; }

    int previewAnimationTriggerSerial() { return animationTriggerSerial; }

    int previewAnimationTriggerLane() { return animationTriggerLane; }

    /**
     * Every lane the pose counts as held, as the battle hands it to the animation: the hold notes being sung and the
     * lane keys that are down (ClientBattleController.applyPlayerAnimation, heldMasks | inputHeldMasks). The preview
     * used to pass the hold notes alone, so a key held on a tap note, or on no note, let the body fall back to idle
     * once its press's window ran out, and a held lane did not take the body back after a tap on another lane, where
     * the battle keeps a held key's pose for as long as it is held. Presses need nothing more: every trigger here is a
     * key going down and nothing is judged, which is what the overload the preview calls says.
     */
    int previewAnimationHeldMask() { return heldLanes(animationHeldMask, laneDown); }

    /** The hold-note lanes and the lanes whose key is down, as one mask of the four lanes; pure for preview-animation. */
    static int heldLanes(int holdNoteLanes, boolean[] keysDown) {
        int mask = holdNoteLanes & 15;
        for (int lane = 0; lane < keysDown.length && lane < 4; lane++) {
            if (keysDown[lane]) {
                mask |= 1 << lane;
            }
        }
        return mask;
    }

    int previewBpm() { return bpm; }

    float previewTrackTick() { return currentTick(); }

    static void onStreamingSoundStarted(SoundInstance startedSound) {
        if (net.minecraft.client.Minecraft.getInstance().screen instanceof TrackGameplayPreviewScreen preview
                && preview.sound == startedSound) {
            preview.audioStartedSignalNanos = System.nanoTime();
        }
    }

    private float effectiveVolume() {
        return Mth.clamp(volume * ClientBattleSettings.musicVolume(), 0.0F, 4.0F);
    }

    private void refreshSoundVolume() {
        if (sound instanceof ExternalOggSoundInstance external) {
            external.setVolume(effectiveVolume());
        }
    }

    static void refreshOpenPreviewVolume() {
        if (net.minecraft.client.Minecraft.getInstance().screen instanceof TrackGameplayPreviewScreen preview) {
            preview.refreshSoundVolume();
        }
    }

    static void refreshOpenPreviewCalibration() {
        if (net.minecraft.client.Minecraft.getInstance().screen instanceof TrackGameplayPreviewScreen preview
                && preview.sound != null && !preview.finished) {
            int targetTick = Math.max(0, Mth.floor(preview.currentTick()));
            preview.stopSound();
            preview.clockStartTick = targetTick;
            preview.clockStarted = false;
            preview.audioStartedSignalNanos = 0L;
            preview.sound = new ExternalOggSoundInstance("editor_gameplay_preview",
                    preview.audioPath.getFileName().toString(), preview.audioPath,
                    targetTick, true, 0.0D, 0.0D, 0.0D, SoundInstance.Attenuation.NONE, preview.effectiveVolume());
            preview.minecraft.getSoundManager().play(preview.sound);
        }
    }

    double currentFovOffset() {
        float time = currentTick();
        ClientTrackEffects.EffectState state = sampleAt(time);
        return beatPulse(time) * (2.8D + state.pulse() * 8.0D);
    }

    private float beatPulse(float time) {
        if (time < 0.0F) {
            return 0.0F;
        }
        float beatTicks = 20.0F * 60.0F / bpm;
        float sinceBeatOne = time - beatOrigin;
        float within = beatOrigin == 0.0F ? time % beatTicks
                : sinceBeatOne - (float) Math.floor(sinceBeatOne / beatTicks) * beatTicks;
        return Mth.clamp(1.0F - within / beatTicks * 4.0F, 0.0F, 1.0F);
    }

    private static String formatTime(int tick) {
        int seconds = Math.max(0, tick) / 20;
        return String.format(java.util.Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60);
    }

    private void stopSound() {
        if (sound != null && this.minecraft != null) {
            if (sound instanceof ExternalOggSoundInstance external) {
                external.requestStop();
            }
            this.minecraft.getSoundManager().stop(sound);
            sound = null;
        }
    }

    @Override
    public void onClose() {
        stopSound();
        if (this.minecraft.player != null) ClientPlayerAnimatorBridge.stopBattleAnimation(this.minecraft.player);
        this.minecraft.setScreen(parent);
    }

    @Override
    public void removed() {
        // The colour picker draws and ticks this screen behind itself, so the song plays on while a colour is chosen.
        if (!choosingBackground) {
            stopSound();
        }
        super.removed();
    }

    /** The fill behind a preview with no world to show, as the player last chose it. */
    static int background() {
        if (backgroundColor == null) {
            backgroundColor = readBackground();
        }
        return backgroundColor;
    }

    /** A missing or damaged settings file is the default colour, never a reason the preview cannot open. */
    private static int readBackground() {
        try {
            Path file = settingsFile();
            if (Files.isRegularFile(file)) {
                JsonElement root = JsonParser.parseString(Files.readString(file));
                JsonElement value = root.isJsonObject() ? root.getAsJsonObject().get("background") : null;
                if (value != null && value.isJsonPrimitive()) {
                    String hex = value.getAsString().trim();
                    hex = hex.startsWith("#") ? hex.substring(1) : hex;
                    if (hex.length() == 6) {
                        return 0xFF000000 | Integer.parseInt(hex, 16);
                    }
                }
            }
        } catch (IOException | RuntimeException exception) {
            MusicBattleMod.LOGGER.warn("Editor preview background unreadable, using the default: {}", exception.toString());
        }
        return DEFAULT_BACKGROUND;
    }

    private static void saveBackground(int color) {
        backgroundColor = 0xFF000000 | color;
        try {
            Path file = settingsFile();
            Files.createDirectories(file.getParent());
            JsonObject root = new JsonObject();
            root.addProperty("background", String.format(Locale.ROOT, "#%06X", color & 0xFFFFFF));
            Files.writeString(file, root.toString());
        } catch (IOException | RuntimeException exception) {
            // The colour still applies for this session; only remembering it failed.
            MusicBattleMod.LOGGER.warn("Could not save the editor preview background: {}", exception.toString());
        }
    }

    private static Path settingsFile() {
        return FMLPaths.CONFIGDIR.get().resolve(SETTINGS_FILE).toAbsolutePath().normalize();
    }

    /**
     * Back to the editor, drawn as FancyButton draws a button. On a screen too narrow for the whole label (see
     * {@link #corners}) it shows the toolkit's arrow and the short "Back" instead; the narrator still reads the whole
     * label either way.
     */
    private final class BackButton extends AbstractButton {
        private final boolean compact;

        private BackButton(int x, int y, int width, int height, boolean compact) {
            super(x, y, width, height, BACK);
            this.compact = compact;
        }

        @Override
        public void onPress() {
            onClose();
        }

        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            boolean hovered = isHoveredOrFocused();
            if (hovered && this.active) {
                HandCursor.request(HandCursor.Kind.HAND);
            }
            Rect rect = Rect.of(getX(), getY(), getWidth(), getHeight());
            WorkshopDraw.button(graphics, rect, WorkshopDraw.ButtonStyle.SECONDARY, hovered, this.active);
            int colour = !this.active ? Palette.TEXT_MUTE : hovered ? Palette.TEXT_1 : Palette.TEXT_2;
            float capY = TextStyles.centeredCapY(TextStyle.BODY, rect.y(), rect.h());
            String label = (compact ? BACK_SHORT : getMessage()).getString();
            float room = Math.max(4F, rect.w() - LABEL_PAD * 2 - (compact ? ARROW + ARROW_GAP : 0F));
            String fitted = TextFit.ellipsize(TextStyles.measurer(), label, TextStyle.BODY, room);
            float textWidth = TextFit.width(TextStyles.measurer(), fitted, TextStyle.BODY);
            float left = rect.centerX() - (textWidth + (compact ? ARROW + ARROW_GAP : 0F)) * 0.5F;
            if (compact) {
                WorkshopDraw.icon(graphics, new Rect(left, rect.centerY() - ARROW * 0.5F, ARROW, ARROW), UiIcon.ARROW_LEFT,
                        colour);
                left += ARROW + ARROW_GAP;
            }
            TextStyles.queue(graphics, TextStyles.text(fitted, TextStyle.BODY).getVisualOrderText(), left, capY, colour);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }

    /**
     * The background colour as a swatch beside its name; a press opens the exact colour picker, which shows each colour
     * on this preview live behind itself. Offered only where no world is drawn, the one case the colour is used.
     */
    private final class BackgroundButton extends AbstractButton {
        /** False on a screen too narrow for the label: the swatch alone, centred; the narrator still reads the label. */
        private final boolean labelled;

        private BackgroundButton(int x, int y, int width, int height, boolean labelled) {
            super(x, y, width, height, BACKGROUND);
            this.labelled = labelled;
        }

        @Override
        public void onPress() {
            choosingBackground = true;
            minecraft.setScreen(new ExactColorPickerScreen(TrackGameplayPreviewScreen.this,
                    Component.translatable("music_battle.l10n.editor.preview_background_title"), background(),
                    color -> backgroundColor = 0xFF000000 | color, TrackGameplayPreviewScreen::saveBackground));
        }

        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            boolean hovered = isHoveredOrFocused();
            if (hovered && this.active) {
                HandCursor.request(HandCursor.Kind.HAND);
            }
            WorkshopDraw.button(graphics, Rect.of(getX(), getY(), getWidth(), getHeight()), WorkshopDraw.ButtonStyle.SECONDARY,
                    hovered, this.active);
            float swatch = getHeight() - 8;
            float swatchX = labelled ? getX() + SWATCH_PAD : getX() + (getWidth() - swatch) * 0.5F;
            float swatchY = getY() + 4;
            RoundedRect.fill(graphics, swatchX, swatchY, swatch, swatch, Palette.RADIUS_SM, background());
            RoundedRect.stroke(graphics, swatchX, swatchY, swatch, swatch, Palette.RADIUS_SM, 1.0F, Palette.TEXT_2);
            if (labelled) {
                float textLeft = swatchX + swatch + SWATCH_PAD;
                TextStyles.queue(graphics, TextStyles.ellipsize(TextStyles.styled(getMessage(), TextStyle.BODY),
                        getX() + getWidth() - LABEL_PAD - textLeft), textLeft,
                        TextStyles.centeredCapY(TextStyle.BODY, getY(), getHeight()), hovered ? Palette.TEXT_1 : Palette.TEXT_2);
            }
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Keep the live world crisp behind the battle HUD; never submit vanilla's menu blur.
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static final class PreviewNote implements BattlePanelRenderer.NoteView {
        private final int owner;
        private final int lane;
        private final int tick;
        private final int durationTicks;
        private boolean started;
        private boolean resolved;

        private PreviewNote(int owner, int lane, int tick, int durationTicks, boolean resolved) {
            this.owner = Mth.clamp(owner, 0, 1);
            this.lane = Mth.clamp(lane, 0, 3);
            this.tick = Math.max(0, tick);
            this.durationTicks = Math.max(0, durationTicks);
            this.resolved = resolved;
        }

        @Override
        public int owner() {
            return owner;
        }

        @Override
        public int lane() {
            return lane;
        }

        @Override
        public int tick() {
            return tick;
        }

        @Override
        public int durationTicks() {
            return durationTicks;
        }

        @Override
        public boolean started() {
            return started;
        }

        @Override
        public boolean resolved() {
            return resolved;
        }
    }
}

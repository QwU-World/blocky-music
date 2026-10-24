package ru.gora.musicbattle.client;

import static ru.gora.musicbattle.client.ClientTranslations.text;

import com.mojang.blaze3d.platform.InputConstants;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;
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
    private final Screen parent;
    private final Path audioPath;
    private final Path previewPath;
    private final String trackTitle;
    private final int bpm;
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

    public TrackGameplayPreviewScreen(Screen parent, Path audioPath, Path previewPath, String trackTitle, int bpm, float volume,
                                      int durationTicks, int startTick, int owner, List<BattleNote> sourceNotes,
                                      List<TrackEffect> effects, boolean disableStageBars, boolean hideStandardDimming) {
        super(Component.literal(text("music_battle.l10n.client.116")));
        this.parent = parent;
        this.audioPath = audioPath;
        this.previewPath = previewPath;
        this.trackTitle = trackTitle;
        this.bpm = Mth.clamp(bpm, 20, 400);
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
        this.addRenderableWidget(FancyButton.create(Component.literal("Exit Preview"), button -> this.onClose(), 8, 7, 104, 20));
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
        ClientTrackEffects.EffectState state = ClientTrackEffects.sample(effects, time, bpm);
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

    private void drawGameplayScene(GuiGraphics graphics, boolean showHud) {
        if (showHud && !hideStandardDimming) {
            graphics.fill(0, 0, this.width, this.height, argb(70, 0, 0, 0));
        }
        drawPreviewPlayer(graphics);
    }

    private void drawStageBars(GuiGraphics graphics) {
        graphics.fill(0, 0, this.width, 36, argb(185, 0, 0, 0));
        graphics.fill(0, this.height - 30, this.width, this.height, argb(185, 0, 0, 0));
    }

    private void drawGameplayHud(GuiGraphics graphics, float time) {
        drawBattlePanel(graphics, time, 0);
        drawBattlePanel(graphics, time, 1);
        drawBattleHeader(graphics, time);
        if (!clockStarted) {
            graphics.drawCenteredString(this.font, text("music_battle.l10n.client.117"), this.width / 2, 40, 0xFFFFE78A);
        } else if (finished) {
            graphics.drawCenteredString(this.font, text("music_battle.l10n.client.118"), this.width / 2, this.height - 20, 0xFFFFE78A);
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
        BattlePanelRenderer.draw(graphics, this.font, this.width, this.height, time, bpm, 1.0F, notes,
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

    private void drawBattleHeader(GuiGraphics graphics, float time) {
        int barWidth = Math.min(this.width - 80, 420);
        int x = this.width / 2 - barWidth / 2;
        int y = 12;
        float progress = Mth.clamp(Math.max(0.0F, time) / durationTicks, 0.0F, 1.0F);
        graphics.fill(x, y, x + barWidth, y + 24, argb(150, 8, 10, 16));
        graphics.fill(x, y + 22, x + Math.round(barWidth * progress), y + 24, argb(230, 90, 210, 235));
        net.minecraft.resources.ResourceLocation preview = ClientPreviewTextures.get("editor", previewPath);
        if (preview != null) {
            int baseSize = 10;
            int size = Math.round(baseSize * (1.0F + beatPulse(time) * 0.20F));
            int titleLeft = this.width / 2 - this.font.width(trackTitle) / 2;
            int imageX = titleLeft - 4 - size;
            int imageY = y + 2 - (size - baseSize) / 2;
            graphics.fill(imageX - 1, imageY - 1, imageX + size + 1, imageY + size + 1, 0xDDFFFFFF);
            graphics.blit(preview, imageX, imageY, 0.0F, 0.0F, size, size, size, size);
        }
        graphics.drawCenteredString(this.font, trackTitle, this.width / 2, y + 4, 0xFFFFFFFF);
        int remaining = Math.max(0, (durationTicks - Math.max(0, (int) time) + 19) / 20);
        graphics.drawCenteredString(this.font, String.format(java.util.Locale.ROOT, "%02d:%02d", remaining / 60, remaining % 60), this.width / 2, y + 14, 0xFFB8DCE8);
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
        return ClientTrackEffects.sample(effects, currentTick(), bpm);
    }

    ClientTrackEffects.EffectState effectStateAt(float tick) {
        return ClientTrackEffects.sample(effects, tick, bpm);
    }

    int previewOwner() { return owner; }

    int previewAnimationTriggerSerial() { return animationTriggerSerial; }

    int previewAnimationTriggerLane() { return animationTriggerLane; }

    int previewAnimationHeldMask() { return animationHeldMask; }

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
        ClientTrackEffects.EffectState state = ClientTrackEffects.sample(effects, time, bpm);
        return beatPulse(time) * (2.8D + state.pulse() * 8.0D);
    }

    private float beatPulse(float time) {
        if (time < 0.0F) {
            return 0.0F;
        }
        float beatTicks = 20.0F * 60.0F / bpm;
        return Mth.clamp(1.0F - (time % beatTicks) / beatTicks * 4.0F, 0.0F, 1.0F);
    }

    private static String formatTime(int tick) {
        int seconds = Math.max(0, tick) / 20;
        return String.format(java.util.Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60);
    }

    private static int argb(int alpha, int red, int green, int blue) {
        return (Mth.clamp(alpha, 0, 255) << 24) | (red << 16) | (green << 8) | blue;
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
        stopSound();
        super.removed();
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

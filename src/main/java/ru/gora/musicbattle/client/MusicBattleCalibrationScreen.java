package ru.gora.musicbattle.client;

import java.util.ArrayList;
import ru.gora.musicbattle.client.ui.Palette;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundSource;
import org.lwjgl.glfw.GLFW;
import ru.gora.musicbattle.data.CalibrationPulse;

/** Local hardware calibration: audio-to-visual alignment and independent input correction. */
public final class MusicBattleCalibrationScreen extends Screen {
    /**
     * The cyan the approaching note and the hit line are drawn in.
     *
     * Deliberately not the interface accent: this is the one screen where a player judges a few pixels of timing by
     * eye, the colour was picked for that, and calibration-colors exists to keep it from drifting with a redesign.
     */
    private static final int CALIBRATION_LINE = 0xFF52D6EC;
    /** The green of "hit". */
    private static final int CALIBRATION_HIT = 0xFF81F7BC;
    /** The amber of "early" and "late". */
    private static final int CALIBRATION_OFF = 0xFFFFCE79;
    private final MusicBattleSettingsScreen parent;
    private final List<Double> samples = new ArrayList<>();
    private final Set<Integer> heldKeys = new HashSet<>();
    private CalibrationSoundInstance metronome;
    private OffsetSlider audioSlider;
    private OffsetSlider inputSlider;
    private Button applyMeasured;
    private long lastTapBeat = Long.MIN_VALUE;
    private double lastError = Double.NaN;
    private double lastTapElapsedMs = -1000;
    private int controlWidth;
    private int inputY;
    private int statsY;
    private int helpY;
    private int controlScroll;
    private final List<Control> controls = new ArrayList<>();
    private record Control(AbstractWidget widget, int baseY) { }

    public MusicBattleCalibrationScreen(MusicBattleSettingsScreen parent) {
        super(tr("title"));
        this.parent = parent;
    }

    @Override protected void init() {
        controls.clear();
        controlScroll = 0;
        int tabWidth = Math.min(150, (width - 24) / 2);
        addRenderableWidget(FancyButton.create(tr("general"), b -> onClose(), width / 2 - tabWidth - 2, 7, tabWidth, 20));
        Button selected = addRenderableWidget(FancyButton.create(tr("title"), b -> { }, width / 2 + 2, 7, tabWidth, 20));
        selected.active = false;
        controlWidth = Math.min(320, width * 55 / 100 - 20);
        inputY = height < 300 ? 98 : 114;
        audioSlider = addOffsetRow(52, true);
        inputSlider = addOffsetRow(inputY + 12, false);
        int actionY = inputY + 52;
        applyMeasured = addControl(FancyButton.create(tr("apply"), b -> {
            if (samples.size() >= 5) {
                ClientBattleSettings.setInputOffsetMs((int) Math.round(CalibrationPulse.median(samples)));
                inputSlider.refresh();
            }
        }, 12, actionY, controlWidth, 18));
        addControl(FancyButton.create(tr("reset"), b -> {
            ClientBattleSettings.setAudioOffsetMs(0);
            ClientBattleSettings.setInputOffsetMs(0);
            audioSlider.refresh();
            inputSlider.refresh();
            restart();
        }, 12, actionY + 22, controlWidth, 18));
        helpY = actionY + 46;
        statsY = helpY + font.split(tr("apply_help"), controlWidth).size() * font.lineHeight + 7;
        positionControls();
        addRenderableWidget(FancyButton.create(tr("restart"), b -> restart(), 12, height - 28, controlWidth, 20));
        int previewX = controlWidth + 28;
        addRenderableWidget(FancyButton.create(Component.translatable("gui.done"), b -> onClose(),
                previewX, height - 28, width - previewX - 12, 20));
        restart();
    }

    private OffsetSlider addOffsetRow(int y, boolean audio) {
        OffsetSlider slider = addControl(new OffsetSlider(34, y, controlWidth - 44, audio));
        addControl(FancyButton.create(Component.literal("−"), b -> slider.adjust(-1), 12, y, 18, 20));
        addControl(FancyButton.create(Component.literal("+"), b -> slider.adjust(1), controlWidth - 6, y, 18, 20));
        slider.setTooltip(Tooltip.create(tr(audio ? "audio_help" : "input_help")));
        return slider;
    }

    private <T extends AbstractWidget> T addControl(T widget) {
        controls.add(new Control(widget, widget.getY()));
        return addWidget(widget);
    }

    private int maxControlScroll() { return Math.max(0, statsY + 28 - (height - 38)); }

    private void positionControls() {
        for (Control control : controls) {
            AbstractWidget widget = control.widget();
            widget.setY(control.baseY() - controlScroll);
            widget.visible = widget.getY() >= 36 && widget.getY() + widget.getHeight() <= height - 38;
            if (!widget.visible && getFocused() == widget) {
                setFocused(null);
                setDragging(false);
            }
        }
    }

    @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        if (x <= controlWidth + 20 && y >= 36 && y < height - 34) {
            controlScroll = Math.max(0, Math.min(maxControlScroll(), controlScroll - (int) Math.round(vertical * 22)));
            positionControls();
            return true;
        }
        return super.mouseScrolled(x, y, horizontal, vertical);
    }

    private void restart() {
        stopSound();
        samples.clear();
        heldKeys.clear();
        lastTapBeat = Long.MIN_VALUE;
        lastError = Double.NaN;
        lastTapElapsedMs = -1000;
        metronome = new CalibrationSoundInstance(ClientBattleSettings.audioOffsetMs());
        minecraft.getSoundManager().play(metronome);
    }

    private void stopSound() {
        if (metronome != null) {
            metronome.requestStop();
            minecraft.getSoundManager().stop(metronome);
            metronome = null;
        }
    }

    @Override public void removed() { stopSound(); heldKeys.clear(); }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { minecraft.setScreen(parent); }

    @Override public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_SPACE) {
            if (heldKeys.add(keyCode)) recordTap();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override public boolean keyReleased(int keyCode, int scanCode, int modifiers) {
        heldKeys.remove(keyCode);
        return keyCode == GLFW.GLFW_KEY_SPACE || super.keyReleased(keyCode, scanCode, modifiers);
    }

    private void recordTap() {
        double elapsed = metronome == null ? -1 : metronome.elapsedMs();
        if (elapsed < 0) return;
        long beat = CalibrationPulse.nearestBeat(elapsed);
        double error = CalibrationPulse.tapErrorMs(elapsed);
        if (beat < 0 || beat == lastTapBeat || Math.abs(error) > 500) return;
        lastTapBeat = beat;
        lastError = error;
        lastTapElapsedMs = elapsed;
        samples.add(error);
        if (samples.size() > 24) samples.removeFirst();
    }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, Palette.BG_0);
        graphics.fill(0, 0, width, 32, Palette.BG_1);
        graphics.fill(0, height - 34, width, height, Palette.BG_1);
        graphics.enableScissor(0, 36, controlWidth + 22, height - 36);
        graphics.pose().pushPose();
        graphics.pose().translate(0, -controlScroll, 0);
        graphics.drawString(font, tr("audio"), 12, 40, Palette.TEXT_2, false);
        graphics.drawString(font, tr("input"), 12, inputY, Palette.TEXT_2, false);
        graphics.drawWordWrap(font, tr("audio_sign"), 12, 76, controlWidth, Palette.TEXT_3);
        graphics.drawWordWrap(font, tr("input_sign"), 12, inputY + 36, controlWidth, Palette.TEXT_3);
        graphics.drawWordWrap(font, tr("apply_help"), 12, helpY, controlWidth, Palette.TEXT_2);
        applyMeasured.active = samples.size() >= 5;
        Component count = tr("samples", samples.size(), Math.round(CalibrationPulse.median(samples)));
        graphics.drawString(font, font.plainSubstrByWidth(count.getString(), controlWidth), 12, statsY, 0xFFDDDBA0, false);
        if (!Double.isNaN(lastError)) {
            graphics.drawString(font, tr("corrected", Math.round(lastError - ClientBattleSettings.inputOffsetMs())),
                    12, statsY + 12, Palette.TEXT_2, false);
        }
        graphics.pose().popPose();
        for (Control control : controls) control.widget().render(graphics, mouseX, mouseY, partialTick);
        graphics.disableScissor();
        if (maxControlScroll() > 0) {
            int track = height - 78;
            int thumb = Math.max(12, track * track / (track + maxControlScroll()));
            int thumbY = 38 + (track - thumb) * controlScroll / maxControlScroll();
            graphics.fill(controlWidth + 16, 38, controlWidth + 18, height - 40, Palette.BG_3);
            graphics.fill(controlWidth + 16, thumbY, controlWidth + 18, thumbY + thumb, Palette.ACCENT);
        }
        drawNote(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void drawNote(GuiGraphics graphics) {
        int x = controlWidth + 28;
        int right = width - 12;
        int top = 42;
        int bottom = Math.max(top + 54, height - 90);
        int center = (x + right) / 2;
        int hitY = bottom - 20;
        int laneColor = ClientBattleSettings.appearance().color(0);
        int laneHighlight = lighten(laneColor);
        graphics.fill(x, top, right, bottom, 0xFF0A1018);
        graphics.fill(center - 24, top, center + 24, bottom, Palette.BG_1);
        double elapsed = metronome == null ? -1 : metronome.elapsedMs();
        float burst = (float) CalibrationPulse.hitBurst(elapsed);
        // The calibration line keeps its own cyan rather than taking the interface accent: this screen is where a
        // player judges a few pixels of timing against it, and calibration-colors guards it for that reason.
        graphics.fill(x + 6, hitY - 1, right - 6, hitY + 3, burst > 0 ? 0xFFFFFFFF : CALIBRATION_LINE);
        if (elapsed >= 0) {
            long beat = Math.max(0, (long) Math.ceil((elapsed - CalibrationPulse.FIRST_HIT_MS - 180) / CalibrationPulse.PERIOD_MS));
            double hit = CalibrationPulse.FIRST_HIT_MS + beat * CalibrationPulse.PERIOD_MS;
            double progress = (elapsed - hit + CalibrationPulse.APPROACH_MS) / CalibrationPulse.APPROACH_MS;
            if (progress >= 0 && progress < 1) {
                int noteY = (int) Math.round(top + 8 + progress * (hitY - top - 8));
                graphics.enableScissor(x, top, right, bottom);
                graphics.fill(center - 18, noteY - 4, center + 18, noteY + 4, laneColor);
                graphics.fill(center - 16, noteY - 3, center + 16, noteY - 1, laneHighlight);
                graphics.disableScissor();
            }
        }
        graphics.enableScissor(x, top, right, bottom);
        if (burst > 0) {
            // Match gameplay's lane flash + expanding hit burst; leave the timing reference
            // automatic so input correction never moves the visible/audio calibration target.
            int alpha = Math.round(200 * burst);
            int spread = Math.round((1 - burst) * 14);
            graphics.fill(center - 24, top, center + 24, bottom,
                    (Math.round(85 * burst) << 24) | (laneColor & 0x00FFFFFF));
            graphics.fill(center - 22 - spread, hitY - 6 - spread, center + 22 + spread, hitY + 7 + spread,
                    (alpha << 24) | (laneHighlight & 0x00FFFFFF));
            graphics.fill(center - 18, hitY - 3, center + 18, hitY + 5,
                    (Math.round(255 * burst) << 24) | 0xFFFFFF);
            for (int index = 0; index < 8; index++) {
                double angle = index * Math.PI / 4;
                double distance = 12 + (1 - burst) * 28;
                int particleX = center + (int) Math.round(Math.cos(angle) * distance);
                int particleY = hitY + (int) Math.round(Math.sin(angle) * distance * 0.65);
                graphics.fill(particleX - 1, particleY - 1, particleX + 2, particleY + 2,
                        (alpha << 24) | 0xFFFFFF);
            }
        }
        double tapAge = elapsed - lastTapElapsedMs;
        if (elapsed >= 0 && tapAge >= 0 && tapAge < 400 && !Double.isNaN(lastError)) {
            double corrected = lastError - ClientBattleSettings.inputOffsetMs();
            boolean accurate = Math.abs(corrected) <= 50;
            String feedback = accurate ? "hit" : corrected < 0 ? "early" : "late";
            // "hit", "early" and "late" keep their own green and amber: the words and the colours are one signal,
            // and calibration-colors checks both together.
            int feedbackColor = accurate ? CALIBRATION_HIT : CALIBRATION_OFF;
            graphics.drawCenteredString(font, tr(feedback), center, Math.max(top + 5, hitY - 33), feedbackColor);
        }
        graphics.disableScissor();
        boolean muted = ClientBattleSettings.musicVolume() <= 0
                || minecraft.options.getSoundSourceVolume(SoundSource.MASTER) <= 0
                || minecraft.options.getSoundSourceVolume(SoundSource.PLAYERS) <= 0;
        Component status = tr(muted ? "muted" : elapsed < 0 ? "waiting" : "tap_hint");
        graphics.drawWordWrap(font, status, x, bottom + 6, right - x, muted ? Palette.WARN : Palette.TEXT_2);
    }

    private static int lighten(int color) {
        return 0xFF000000
                | Math.min(255, ((color >>> 16) & 0xFF) + 95) << 16
                | Math.min(255, ((color >>> 8) & 0xFF) + 95) << 8
                | Math.min(255, (color & 0xFF) + 95);
    }

    @Override public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) { }
    private static Component tr(String key, Object... args) {
        return Component.translatable("music_battle.calibration." + key, args);
    }

    private final class OffsetSlider extends AbstractSliderButton {
        @Override public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            // Drawn by the toolkit, like every other slider in the mod; what the value means stays this screen's.
            ru.gora.musicbattle.client.ui.Slider.paint(graphics,
                    ru.gora.musicbattle.client.workshop.layout.Rect.of(getX(), getY(), getWidth(), getHeight()),
                    value, getMessage(), isHoveredOrFocused(), active);
        }

        private final boolean audio;
        private OffsetSlider(int x, int y, int width, boolean audio) {
            super(x, y, width, 20, Component.empty(), ((audio ? ClientBattleSettings.audioOffsetMs() : ClientBattleSettings.inputOffsetMs()) + 500) / 1000.0);
            this.audio = audio;
            updateMessage();
        }
        private int offset() { return (int) Math.round(value * 1000) - 500; }
        private void refresh() {
            value = ((audio ? ClientBattleSettings.audioOffsetMs() : ClientBattleSettings.inputOffsetMs()) + 500) / 1000.0;
            updateMessage();
        }
        private void adjust(int delta) {
            value = Math.max(0, Math.min(1, (offset() + delta + 500) / 1000.0));
            applyValue();
            updateMessage();
        }
        @Override protected void updateMessage() { setMessage(tr("milliseconds", (offset() >= 0 ? "+" : "") + offset())); }
        @Override protected void applyValue() {
            if (audio) {
                if (ClientBattleSettings.audioOffsetMs() == offset()) return;
                ClientBattleSettings.setAudioOffsetMs(offset());
                restart();
            } else {
                ClientBattleSettings.setInputOffsetMs(offset());
            }
        }
    }
}

package ru.gora.musicbattle.client;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundSource;
import org.lwjgl.glfw.GLFW;
import ru.gora.musicbattle.client.ui.HandCursor;
import ru.gora.musicbattle.client.ui.Palette;
import ru.gora.musicbattle.client.ui.RoundedRect;
import ru.gora.musicbattle.client.ui.Slider;
import ru.gora.musicbattle.client.ui.TextMeasurer;
import ru.gora.musicbattle.client.ui.TextStyle;
import ru.gora.musicbattle.client.ui.TextStyles;
import ru.gora.musicbattle.client.ui.UiClip;
import ru.gora.musicbattle.client.ui.UiIcon;
import ru.gora.musicbattle.client.ui.UiLayer;
import ru.gora.musicbattle.client.ui.UiTooltip;
import ru.gora.musicbattle.client.workshop.WorkshopDraw;
import ru.gora.musicbattle.client.workshop.layout.Metrics;
import ru.gora.musicbattle.client.workshop.layout.Rect;
import ru.gora.musicbattle.client.workshop.layout.TextFit;
import ru.gora.musicbattle.data.CalibrationPulse;

/**
 * Local hardware calibration: audio-to-visual alignment and independent input correction.
 *
 * <p>The frame around the test is the Workshop's: the settings tabs in a header bar, each offset in a rounded card with
 * its own heading, Nunito text instead of the bitmap font, the toolkit's scrollbar, and help that opens in a toolkit
 * panel rather than the vanilla tooltip with its blue border. Inside the test, what mirrors a battle looks like the
 * battle - the note and the hit flash take the player's first lane colour, as that lane's notes and burst do - and the
 * rest is drawn in the Workshop's tokens; see {@link #drawNote}.
 */
public final class MusicBattleCalibrationScreen extends UiLayer {
    /**
     * The hit line the note crosses. It was the first interface's cyan, which nothing in a battle is (a battle's hit
     * targets are drawn in the lane colours), and it was the last blue left on this screen. It is now the toolkit's
     * secondary text tone: a light neutral that stands clear of the dark stage and of any lane colour a note can have,
     * the default lavender included, so a player still judges the crossing against a sharp line.
     */
    private static final int CALIBRATION_LINE = Palette.TEXT_2;
    /** The green of "hit": the toolkit's success colour. */
    private static final int CALIBRATION_HIT = Palette.OK;
    /** The amber of "early" and "late": the toolkit's warning colour. */
    private static final int CALIBRATION_OFF = Palette.WARN;
    /** The header bar the two tabs sit in. */
    private static final int HEADER = 32;
    /** The footer bar under both columns. */
    private static final int FOOTER = 34;
    /**
     * Where the scrolling column begins and ends: a control is shown only while it lies wholly between
     * {@code COLUMN_TOP} and {@code height - COLUMN_BOTTOM}. dj-workflow scrolls a bare instance of this screen and
     * expects exactly these bounds.
     */
    private static final int COLUMN_TOP = 36;
    private static final int COLUMN_BOTTOM = 38;
    /**
     * The test's frame: it starts {@code STAGE_GAP} right of the column and {@code STAGE_MARGIN} short of the window's
     * edge, runs from {@code STAGE_TOP} to {@link #stageFoot} above the bottom (at least {@code STAGE_FOOT}), and is
     * never shorter than {@code STAGE_MIN_HEIGHT}. calibration-colors measures the note and the line against exactly
     * this frame; the probe that replays drawNote is handed the foot as one of the screen's fields.
     */
    private static final int STAGE_TOP = 42;
    private static final int STAGE_GAP = 28;
    private static final int STAGE_MARGIN = 12;
    private static final int STAGE_FOOT = 90;
    private static final int STAGE_MIN_HEIGHT = 54;
    /** From the tap count's line to the median's. */
    private static final int TAPS_ROW = 12;
    /** From the median's line to the last tap's error. */
    private static final int STATS_ROW = 11;
    private static final int CARD_PAD = 6;
    private static final int CARD_GAP = 6;
    /** The height of a slider row and of the column's buttons. */
    private static final int ROW = 20;
    /** From a card's top to its slider row: the padding and the heading line. */
    private static final int CARD_HEADING = 20;
    /** The stage under the note, a shade darker than the cards so the lane and the note stand out of it. */
    private static final int STAGE = Palette.mix(Palette.BG_0, Palette.BG_1, 0.5F);

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
    /**
     * How far above the window's bottom the stage ends: {@code STAGE_FOOT}, or more when the longest note under the
     * stage needs more room in this language at this width (see {@link Fit#stageFoot}).
     */
    private int stageFoot = STAGE_FOOT;
    /** The face of the offset in each card's heading, and the width of the widest offset in it; see {@link Fit#valueStyle}. */
    private TextStyle valueStyle = TextStyle.STRONG;
    private float widestValue;
    /**
     * The median's line, under the tap count: the last line always in the column, so the column's scroll ends a little
     * below it (dj-workflow pins that end, 28 units under this line).
     */
    private int statsY;
    private int controlScroll;
    private Section audioSection;
    private Section inputSection;
    private int measureTop;
    private int helpY;
    private List<String> helpLines = List.of();
    /** The help panel this frame asked for, drawn over everything once the widgets are down; null when none. */
    private Component hint;
    private double hintX;
    private double hintY;
    /** The slider the keyboard brought the help up for, which the card is centred under; null for the pointer's help. */
    private Rect hintWidget;
    /** What the pointer rests on while its help shows (the info icon or the slider), which the card keeps clear of. */
    private Rect hintHovered;
    private final List<Control> controls = new ArrayList<>();
    private record Control(AbstractWidget widget, int baseY) { }

    /**
     * One offset's card: where it starts, its wrapped sign line and where it ends, the help behind its icon, and the
     * slider whose value its heading shows.
     */
    private record Section(int top, Component title, Component help, int signY, List<String> sign, int bottom,
                           OffsetSlider slider) { }

    public MusicBattleCalibrationScreen(MusicBattleSettingsScreen parent) {
        super(tr("title"));
        this.parent = parent;
    }

    @Override
    protected void layout() {
        controls.clear();
        controlScroll = 0;
        hint = null;
        hintWidget = null;
        hintHovered = null;
        int tabWidth = Math.min(150, (width - 24) / 2);
        addRenderableWidget(new TabButton(tr("general"), b -> onClose(), width / 2 - tabWidth - 2, 7, tabWidth, 20, false));
        addRenderableWidget(new TabButton(tr("title"), b -> { }, width / 2 + 2, 7, tabWidth, 20, true));
        controlWidth = Fit.controlWidth(width);
        int innerX = 12 + CARD_PAD;
        int innerWidth = controlWidth - CARD_PAD * 2;
        List<String> titles = List.of(tr("audio").getString(), tr("input").getString());
        List<String> values = List.of(tr("milliseconds", "+500").getString(), tr("milliseconds", "-500").getString());
        valueStyle = Fit.valueStyle(TextStyles.measurer(), titles, values, innerWidth);
        widestValue = Fit.valueWidth(TextStyles.measurer(), values, valueStyle);

        audioSlider = addOffsetRow(STAGE_TOP + CARD_HEADING, innerX, innerWidth, true);
        audioSection = section(STAGE_TOP, tr("audio"), tr("audio_help"), tr("audio_sign"), innerWidth, audioSlider);
        int inputTop = audioSection.bottom() + CARD_GAP;
        inputSlider = addOffsetRow(inputTop + CARD_HEADING, innerX, innerWidth, false);
        inputSection = section(inputTop, tr("input"), tr("input_help"), tr("input_sign"), innerWidth, inputSlider);

        int actionY = inputSection.bottom() + CARD_GAP + 2;
        // Applying the measurement is what the tapping is for, so it is the one primary action; it stays dim until
        // there are enough taps to take a median of.
        applyMeasured = addControl(FancyButton.create(tr("apply"), b -> {
            if (samples.size() >= 5) {
                ClientBattleSettings.setInputOffsetMs((int) Math.round(CalibrationPulse.median(samples)));
                inputSlider.refresh();
            }
        }, 12, actionY, controlWidth, ROW).primary());
        addControl(FancyButton.create(tr("reset"), b -> {
            ClientBattleSettings.setAudioOffsetMs(0);
            ClientBattleSettings.setInputOffsetMs(0);
            audioSlider.refresh();
            inputSlider.refresh();
            restart();
        }, 12, actionY + ROW + 4, controlWidth, ROW));

        measureTop = actionY + (ROW + 4) * 2 + 2;
        helpY = measureTop + CARD_PAD + 1;
        helpLines = TextFit.wrap(TextStyles.measurer(), tr("apply_help").getString(), TextStyle.CAPTION, innerWidth);
        statsY = helpY + Math.round(helpLines.size() * Metrics.lineHeight(TextStyle.CAPTION)) + 9 + TAPS_ROW;
        positionControls();
        // The stage gives up height to the note under it rather than have the note cut its words off: on a narrow stage
        // the muted warning needs more caption lines than the standard foot leaves room for, in Russian most of all.
        stageFoot = Fit.stageFoot(TextStyles.measurer(), List.of(tr("muted").getString(), tr("waiting").getString(),
                tr("tap_hint").getString()), statusTextWidth(), height);

        Component restartLabel = tr("restart");
        Component doneLabel = Component.translatable("gui.done");
        int footerY = height - FOOTER + (FOOTER - ROW) / 2;
        addRenderableWidget(FancyButton.create(restartLabel, b -> restart(), 12, footerY,
                footerButtonWidth(restartLabel, controlWidth), ROW));
        int stageRight = width - STAGE_MARGIN;
        int doneWidth = footerButtonWidth(doneLabel, stageRight - controlWidth - STAGE_GAP);
        addRenderableWidget(FancyButton.create(doneLabel, b -> onClose(), stageRight - doneWidth, footerY, doneWidth, ROW));
        restart();
    }

    /** A footer button as wide as its label wants, never past its column, and never so small it looks like a chip. */
    private static int footerButtonWidth(Component label, int room) {
        float wanted = TextFit.width(TextStyles.measurer(), label.getString(), TextStyle.BODY) + 36F;
        return Math.max(24, Math.min(room, Math.round(Math.max(96F, wanted))));
    }

    private Section section(int top, Component title, Component help, Component sign, int innerWidth,
                            OffsetSlider slider) {
        int signY = top + CARD_HEADING + ROW + 6;
        List<String> lines = TextFit.wrap(TextStyles.measurer(), sign.getString(), TextStyle.CAPTION, innerWidth);
        int bottom = signY + Math.round(Math.max(1, lines.size()) * Metrics.lineHeight(TextStyle.CAPTION)) + CARD_PAD - 1;
        return new Section(top, title, help, signY, lines, bottom, slider);
    }

    private OffsetSlider addOffsetRow(int y, int x, int width, boolean audio) {
        OffsetSlider slider = addControl(new OffsetSlider(x + 22, y, width - 44, audio));
        addControl(FancyButton.create(Component.literal("−"), b -> slider.adjust(-1), x, y, 18, ROW));
        addControl(FancyButton.create(Component.literal("+"), b -> slider.adjust(1), x + width - 18, y, 18, ROW));
        return slider;
    }

    private <T extends AbstractWidget> T addControl(T widget) {
        controls.add(new Control(widget, widget.getY()));
        return addWidget(widget);
    }

    private int maxControlScroll() { return Math.max(0, statsY + 28 - (height - COLUMN_BOTTOM)); }

    private void positionControls() {
        for (Control control : controls) {
            AbstractWidget widget = control.widget();
            widget.setY(control.baseY() - controlScroll);
            widget.visible = widget.getY() >= COLUMN_TOP && widget.getY() + widget.getHeight() <= height - COLUMN_BOTTOM;
            if (!widget.visible && getFocused() == widget) {
                setFocused(null);
                setDragging(false);
            }
        }
    }

    @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        if (x <= controlWidth + 20 && y >= COLUMN_TOP && y < height - FOOTER) {
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

    @Override public void removed() {
        stopSound();
        heldKeys.clear();
        super.removed();
    }

    @Override public boolean isPauseScreen() { return false; }

    @Override public void onClose() {
        HandCursor.reset();
        minecraft.setScreen(parent);
    }

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

    // ---------------------------------------------------------------- rendering

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        // After the widgets, so the help is never under a button; renderUi decided whether there is any.
        if (hint != null) {
            drawHint(graphics);
            graphics.flush();
        }
    }

    @Override
    protected void renderUi(GuiGraphics graphics, double mouseX, double mouseY, float partialTick) {
        hint = null;
        hintX = mouseX;
        hintY = mouseY;
        hintWidget = null;
        hintHovered = null;
        drawBars(graphics);
        drawColumn(graphics, mouseX, mouseY, partialTick);
        drawStage(graphics);
        // The test draws with plain fills, which go out at once; everything queued so far has to go first.
        graphics.flush();
        drawNote(graphics);
        drawStatus(graphics);
    }

    /** The header and footer bars; the tabs and the footer buttons are widgets and land on top of them. */
    private void drawBars(GuiGraphics graphics) {
        RoundedRect.fill(graphics, 0, 0, width, HEADER, 0, Palette.BG_1);
        RoundedRect.fill(graphics, 0, HEADER - 1, width, 1, 0, Palette.LINE);
        RoundedRect.fill(graphics, 0, height - FOOTER, width, FOOTER, 0, Palette.BG_1);
        RoundedRect.fill(graphics, 0, height - FOOTER, width, 1, 0, Palette.LINE);
    }

    private void drawColumn(GuiGraphics graphics, double mouseX, double mouseY, float partialTick) {
        UiClip.push(graphics, 0, COLUMN_TOP, controlWidth + 22, height - COLUMN_TOP);
        drawSection(graphics, audioSection, mouseX, mouseY);
        drawSection(graphics, inputSection, mouseX, mouseY);
        drawMeasurement(graphics);
        applyMeasured.active = samples.size() >= 5;
        for (Control control : controls) {
            control.widget().render(graphics, (int) mouseX, (int) mouseY, partialTick);
        }
        UiClip.pop(graphics);
        if (!isDragging()) {
            hintFor(audioSlider, audioSection);
            hintFor(inputSlider, inputSection);
        }
        if (maxControlScroll() > 0) {
            // The same thumb the Workshop lists use, in the gutter between the column and the stage.
            WorkshopDraw.scrollbar(graphics, new Rect(12, COLUMN_TOP + 2, controlWidth + 7, height - 78),
                    controlScroll, height - 78 + maxControlScroll(), false);
        }
    }

    /**
     * A card with its heading, the info icon that opens its help, the offset itself on the right of the heading, and
     * the line that says which way the sign goes.
     *
     * <p>The value lives in the heading rather than on the slider: the toolkit centres a slider's label on its track,
     * and an offset rests at zero, in the middle, which put the handle on top of the number in its usual state. The
     * title keeps the room the widest value would leave it, so it does not move while the slider is dragged, and the
     * value is in the smaller label face on the narrow windows where a Russian title would not fit beside it otherwise
     * (see {@link Fit#valueStyle}).
     */
    private void drawSection(GuiGraphics graphics, Section section, double mouseX, double mouseY) {
        float top = section.top() - controlScroll;
        RoundedRect.fill(graphics, 12, top, controlWidth, section.bottom() - section.top(), Palette.RADIUS_MD, Palette.BG_2);
        float innerX = 12 + CARD_PAD;
        float innerWidth = controlWidth - CARD_PAD * 2;
        float titleY = top + CARD_PAD + 1;
        String value = section.slider().getMessage().getString();
        // On the title's baseline, whichever face the value is in.
        TextStyles.queueRight(graphics, TextStyles.text(value, valueStyle).getVisualOrderText(), innerX + innerWidth,
                titleY + TextStyle.STRONG.capHeight() - valueStyle.capHeight(), Palette.TEXT_1);
        float titleRoom = Fit.headingRoom(innerWidth, widestValue);
        String title = TextFit.ellipsize(TextStyles.measurer(), section.title().getString(), TextStyle.STRONG, titleRoom);
        line(graphics, title, TextStyle.STRONG, innerX, titleY, titleRoom, Palette.TEXT_1);
        float iconX = innerX + TextFit.width(TextStyles.measurer(), title, TextStyle.STRONG) + Metrics.GAP_SM + 1;
        Rect info = new Rect(iconX, titleY + TextStyle.STRONG.capHeight() * 0.5F - Metrics.ICON * 0.5F,
                Metrics.ICON, Metrics.ICON);
        boolean overInfo = inColumn(mouseY) && info.inset(-3, -3).contains(mouseX, mouseY);
        if (overInfo) {
            hint = section.help();
            hintHovered = info.inset(-3, -3);
        }
        WorkshopDraw.icon(graphics, info, UiIcon.INFO, overInfo ? Palette.ACCENT : Palette.TEXT_MUTE);
        float y = section.signY() - controlScroll;
        for (String sign : section.sign()) {
            line(graphics, sign, TextStyle.CAPTION, innerX, y, innerWidth, Palette.TEXT_MUTE);
            y += Metrics.lineHeight(TextStyle.CAPTION);
        }
    }

    /**
     * How to measure, then what has been measured: the tap count with five dots for the five taps it needs, the median
     * under it, and the last tap's corrected error.
     *
     * <p>The count and the median used to share one line, and in Russian ("Замеры: 12 · медиана -123 мс") that line was
     * cut off beside the dots on any window narrower than 386 to 408 units, depending on the GUI scale. Each figure has
     * its own line now, and the calibration-layout check measures all three in both languages at every width from 320
     * up and at every GUI scale.
     */
    private void drawMeasurement(GuiGraphics graphics) {
        float top = measureTop - controlScroll;
        float medianY = statsY - controlScroll;
        float stats = medianY - TAPS_ROW;
        float correctedY = medianY + STATS_ROW;
        // The corrected line exists only after a tap; until then the card ends under the median, not under a gap.
        float bottom = (Double.isNaN(lastError) ? medianY : correctedY) + 11;
        RoundedRect.fill(graphics, 12, top, controlWidth, bottom - top, Palette.RADIUS_MD, Palette.BG_2);
        float innerX = 12 + CARD_PAD;
        float innerWidth = controlWidth - CARD_PAD * 2;
        float y = helpY - controlScroll;
        for (String help : helpLines) {
            line(graphics, help, TextStyle.CAPTION, innerX, y, innerWidth, Palette.TEXT_3);
            y += Metrics.lineHeight(TextStyle.CAPTION);
        }
        RoundedRect.fill(graphics, innerX, stats - 5, innerWidth, 1, 0, Palette.withAlpha(Palette.LINE, 0.7F));

        int taps = samples.size();
        float dotsLeft = innerX + innerWidth - Fit.DOTS_WIDTH;
        for (int index = 0; index < 5; index++) {
            RoundedRect.circle(graphics, dotsLeft + Fit.DOT + index * Fit.DOT_STEP, stats + 3.5F, Fit.DOT,
                    index < taps ? Palette.ACCENT : Palette.BG_4);
        }
        line(graphics, tr("taps", taps).getString(), TextStyle.STRONG, innerX, stats, Fit.tapsRoom(innerWidth),
                taps >= 5 ? Palette.TEXT_1 : Palette.TEXT_2);
        line(graphics, tr("median", Math.round(CalibrationPulse.median(samples))).getString(), TextStyle.CAPTION, innerX,
                medianY, innerWidth, Palette.TEXT_2);
        if (!Double.isNaN(lastError)) {
            line(graphics, tr("corrected", Math.round(lastError - ClientBattleSettings.inputOffsetMs())).getString(),
                    TextStyle.CAPTION, innerX, correctedY, innerWidth, Palette.TEXT_MUTE);
        }
    }

    /** The slider's help, while the pointer rests on it or the keyboard is on it; never in the middle of a drag. */
    private void hintFor(OffsetSlider slider, Section section) {
        if (slider == null || !slider.visible) {
            return;
        }
        boolean keyboard = slider.isFocused() && minecraft.getLastInputType().isKeyboard();
        if (slider.isHovered() || keyboard) {
            hint = section.help();
            Rect bounds = Rect.of(slider.getX(), slider.getY(), slider.getWidth(), slider.getHeight());
            hintWidget = keyboard && !slider.isHovered() ? bounds : null;
            hintHovered = slider.isHovered() ? bounds : null;
        }
    }

    private boolean inColumn(double mouseY) {
        return mouseY >= COLUMN_TOP && mouseY < height - COLUMN_BOTTOM;
    }

    /**
     * The surface under the test, in the same frame {@link #drawNote} computes: a darker stage than the cards, with
     * the one lane down its middle.
     */
    private void drawStage(GuiGraphics graphics) {
        int x = controlWidth + STAGE_GAP;
        int right = width - STAGE_MARGIN;
        int bottom = Math.max(STAGE_TOP + STAGE_MIN_HEIGHT, height - stageFoot);
        int center = (x + right) / 2;
        float stageHeight = bottom - STAGE_TOP;
        RoundedRect.fill(graphics, x, STAGE_TOP, right - x, stageHeight, Palette.RADIUS_MD, STAGE);
        RoundedRect.fill(graphics, center - 24, STAGE_TOP + 1, 48, stageHeight - 2, 0, Palette.BG_2);
        RoundedRect.fill(graphics, center - 24, STAGE_TOP + 1, 1, stageHeight - 2, 0, Palette.withAlpha(Palette.LINE, 0.55F));
        RoundedRect.fill(graphics, center + 23, STAGE_TOP + 1, 1, stageHeight - 2, 0, Palette.withAlpha(Palette.LINE, 0.55F));
        RoundedRect.stroke(graphics, x, STAGE_TOP, right - x, stageHeight, Palette.RADIUS_MD, 1F,
                Palette.withAlpha(Palette.LINE, 0.75F));
    }

    /**
     * The test itself: the line, the note, the flash and the judgement word. The note and the flash mirror a battle and
     * take the player's first lane colour, as that lane's notes and hit burst do; the line and the words are the
     * toolkit's (text, success and warning tokens). calibration-colors replays this very method to prove both.
     *
     * <p>That replay is why it looks the way it does. The check copies the method into a probe with only this screen's
     * {@code controlWidth}, {@code width}, {@code height}, {@code stageFoot}, {@code metronome} and tap fields, and
     * records plain fills; so this draws with {@code graphics.fill} alone, works its frame out from those instead of
     * asking {@link #drawStage}, and calls nothing of this class but {@link #tr}, {@link #lighten} and
     * {@link #drawFeedback}, which the probe swaps, and the public {@link Fit#noteY}, which it runs as it is.
     */
    private void drawNote(GuiGraphics graphics) {
        int x = controlWidth + STAGE_GAP;
        int right = width - STAGE_MARGIN;
        int top = STAGE_TOP;
        int bottom = Math.max(top + STAGE_MIN_HEIGHT, height - stageFoot);
        int center = (x + right) / 2;
        int hitY = bottom - 20;
        int laneColor = ClientBattleSettings.appearance().color(0);
        int laneHighlight = lighten(laneColor);
        double elapsed = metronome == null ? -1 : metronome.elapsedMs();
        float burst = (float) CalibrationPulse.hitBurst(elapsed);
        // A neutral light line rather than the interface accent, which is the default first lane colour: the note has
        // to stay distinct from the line it crosses. It flashes white with the hit, like the burst around it.
        graphics.fill(x + 6, hitY - 1, right - 6, hitY + 3, burst > 0 ? 0xFFFFFFFF : CALIBRATION_LINE);
        int noteY = Fit.noteY(elapsed, top, hitY);
        if (noteY != Fit.NO_NOTE) {
            graphics.enableScissor(x, top, right, bottom);
            graphics.fill(center - 18, noteY - Fit.NOTE_HALF, center + 18, noteY + Fit.NOTE_HALF, laneColor);
            graphics.fill(center - 16, noteY - 3, center + 16, noteY - 1, laneHighlight);
            graphics.disableScissor();
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
            // "hit" is green and "early" and "late" amber, from the toolkit's success and warning tokens: the words and
            // the colours are one signal, and calibration-colors checks both together.
            int feedbackColor = accurate ? CALIBRATION_HIT : CALIBRATION_OFF;
            // Under the hit line, the one part of the lane no note ever reaches. The word used to stand 33 units above
            // the line, clamped to the stage's top: a note approaching during the 400 ms it shows passes through every
            // other height of the lane, and on a 240-unit window the word sat right on the next note.
            drawFeedback(graphics, tr(feedback), center, hitY + Fit.FEEDBACK_BELOW_LINE, feedbackColor);
        }
        graphics.disableScissor();
    }

    /**
     * The judgement word in a chip under the hit line: the toolkit's bold face on a dark pill edged in the word's own
     * colour, so it stays readable over the hit flash it often lands on. It is its own method so calibration-colors can
     * catch the word and its colour on their way out of drawNote: the check replays drawNote with no font and no window,
     * and swaps this call for one that records instead of drawing.
     *
     * @param top the chip's top edge, {@link Fit#FEEDBACK_BELOW_LINE} under the hit line
     */
    private static void drawFeedback(GuiGraphics graphics, Component word, float centerX, float top, int colour) {
        Component styled = TextStyles.styled(word, Fit.FEEDBACK_STYLE);
        Fit.Chip chip = Fit.feedbackChip(TextStyles.measurer(), word.getString(), centerX, top);
        Rect box = chip.box();
        RoundedRect.pill(graphics, box.x(), box.y(), box.w(), box.h(), Palette.withAlpha(Palette.BG_1, 0.92F));
        RoundedRect.stroke(graphics, box.x(), box.y(), box.w(), box.h(), box.h() * 0.5F, 1F, Palette.fade(colour, 0.55F));
        TextStyles.queue(graphics, styled.getVisualOrderText(), chip.textX(), chip.textY(), colour);
        // Out now rather than at the next flush, so the scissor drawNote holds around the stage still applies to it.
        graphics.flush();
    }

    /**
     * What the test is waiting for, under the stage, as one of the guide's notes: an amber warning when nothing can be
     * heard, a turning ring while the sound starts, and the instruction once it plays.
     */
    private void drawStatus(GuiGraphics graphics) {
        double elapsed = metronome == null ? -1 : metronome.elapsedMs();
        boolean muted = ClientBattleSettings.musicVolume() <= 0
                || minecraft.options.getSoundSourceVolume(SoundSource.MASTER) <= 0
                || minecraft.options.getSoundSourceVolume(SoundSource.PLAYERS) <= 0;
        boolean waiting = !muted && elapsed < 0;
        Component status = tr(muted ? "muted" : waiting ? "waiting" : "tap_hint");
        int x = controlWidth + STAGE_GAP;
        int right = width - STAGE_MARGIN;
        float top = Math.max(STAGE_TOP + STAGE_MIN_HEIGHT, height - stageFoot) + Fit.STATUS_BELOW_STAGE;
        float room = height - Fit.STATUS_ABOVE_FOOTER - top;
        float textX = x + 8 + Metrics.ICON + Metrics.GAP;
        float textWidth = statusTextWidth();
        // Body text while it fits; a smaller face on a narrow stage rather than an instruction cut off mid-sentence.
        Fit.Note note = Fit.status(TextStyles.measurer(), status.getString(), textWidth, room);
        TextStyle style = note.style();
        List<String> lines = note.lines();
        if (lines.isEmpty() || room < 12) {
            return;
        }
        float lineHeight = Metrics.lineHeight(style);
        float noteHeight = Math.min(room, note.height());
        int accent = muted ? Palette.WARN : Palette.ACCENT;
        RoundedRect.fill(graphics, x, top, right - x, noteHeight, Palette.RADIUS_SM, Palette.mix(Palette.BG_2, accent, 0.10F));
        RoundedRect.pill(graphics, x + 1, top + 2, 2, Math.max(2, noteHeight - 4), accent);
        float iconY = top + 5 + style.capHeight() * 0.5F - Metrics.ICON * 0.5F;
        if (waiting) {
            WorkshopDraw.spinner(graphics, x + 8 + Metrics.ICON * 0.5F, iconY + Metrics.ICON * 0.5F, 3.5F, accent);
        } else {
            WorkshopDraw.icon(graphics, new Rect(x + 8, iconY, Metrics.ICON, Metrics.ICON),
                    muted ? UiIcon.ALERT : UiIcon.INFO, accent);
        }
        float y = top + 5;
        for (String text : lines) {
            line(graphics, text, style, textX, y, textWidth, muted ? Palette.TEXT_1 : Palette.TEXT_2);
            y += lineHeight;
        }
    }

    private float statusTextWidth() {
        return Fit.statusTextWidth(width, controlWidth);
    }

    /**
     * The screen's text fitting, without the screen, so tools/CalibrationLayoutChecks runs these very rules in both
     * languages with the game's own glyph metrics: the column's width, the room of a card's heading and of the
     * measurement's figures, and how the note under the stage is set and how much height the stage leaves it. Nothing
     * here touches the screen, so the check can load this class without the screen's.
     *
     * <p>It is public for one reason: calibration-colors replays drawNote from a probe class outside this package, and
     * drawNote asks {@link #noteY} where the note is.
     */
    public static final class Fit {
        /** {@link #noteY} when no note is on the stage. */
        public static final int NO_NOTE = Integer.MIN_VALUE;
        /** The note is drawn this far above and below its centre row. */
        public static final int NOTE_HALF = 4;
        /**
         * The judgement word's chip starts this far under the hit line. The line covers the rows from one above
         * {@code hitY} to two below it, and a note, whose centre never passes {@code hitY}, reaches at most three
         * below it; the chip keeps a row clear of both.
         */
        public static final int FEEDBACK_BELOW_LINE = 5;
        /** The word's face: the body size in bold, the largest that fits a chip into the 20 units under the line. */
        static final TextStyle FEEDBACK_STYLE = TextStyle.STRONG;
        static final float CHIP_PAD_X = 7F;
        /** Half a unit short of the stage's border under it, so the chip does not run into it. */
        static final float CHIP_HEIGHT = 13.5F;
        /**
         * From the chip's top to the word's cap line: the capitals near its middle, and the deepest descender (the
         * Russian De) still half a unit inside it where GUI scale 1 snaps the line half a unit down.
         */
        static final float CHIP_CAP_TOP = 2.75F;
        static final int MIN_CONTROL_WIDTH = 164;
        /** The five tap dots on the first line of the figures: 2.2-unit dots, 7 units apart. */
        static final float DOT = 2.2F;
        static final float DOT_STEP = 7F;
        static final float DOTS_WIDTH = DOT_STEP * 4 + DOT * 2;
        /** The note under the stage stays 4 units above the footer and starts 8 units under the stage. */
        static final int STATUS_ABOVE_FOOTER = FOOTER + 4;
        static final int STATUS_BELOW_STAGE = 8;

        /** A note's face, its lines and the height of the panel they need. */
        record Note(TextStyle style, List<String> lines, float height) {
        }

        /** The judgement word's chip and where the word's cap line starts in it. */
        record Chip(Rect box, float textX, float textY) {
        }

        private Fit() {
        }

        /**
         * The centre row of the test's note at a moment of the metronome, or {@link #NO_NOTE}: each note comes down the
         * lane from 8 units under the stage's top to the hit line over the {@code APPROACH_MS} before its beat, and is
         * gone from the beat on. Its centre is never below {@code hitY}.
         */
        public static int noteY(double elapsed, int top, int hitY) {
            if (elapsed < 0) {
                return NO_NOTE;
            }
            long beat = Math.max(0, (long) Math.ceil((elapsed - CalibrationPulse.FIRST_HIT_MS - 180) / CalibrationPulse.PERIOD_MS));
            double hit = CalibrationPulse.FIRST_HIT_MS + beat * CalibrationPulse.PERIOD_MS;
            double progress = (elapsed - hit + CalibrationPulse.APPROACH_MS) / CalibrationPulse.APPROACH_MS;
            if (progress < 0 || progress >= 1) {
                return NO_NOTE;
            }
            return (int) Math.round(top + 8 + progress * (hitY - top - 8));
        }

        /** The chip for a judgement word centred on {@code centerX}, its top at {@code top}. */
        static Chip feedbackChip(TextMeasurer measurer, String word, float centerX, float top) {
            float textWidth = TextFit.width(measurer, word, FEEDBACK_STYLE);
            float width = textWidth + CHIP_PAD_X * 2F;
            return new Chip(new Rect(centerX - width * 0.5F, top, width, CHIP_HEIGHT), centerX - textWidth * 0.5F,
                    top + CHIP_CAP_TOP);
        }

        /**
         * The column of cards and buttons: 55% of the window less a margin, at most 320 units, and never under 164.
         * Below 164 a card's Russian heading ("Поправка нажатия", its info icon and "-500 мс") cannot fit on one line
         * at GUI scale 1, where the game's hinted glyphs are widest; the floor only matters below 335 units.
         */
        static int controlWidth(int width) {
            return Math.max(MIN_CONTROL_WIDTH, Math.min(320, width * 55 / 100 - 20));
        }

        /** The width inside a card. */
        static float innerWidth(int controlWidth) {
            return controlWidth - CARD_PAD * 2;
        }

        /**
         * What a card's title may take beside its info icon and its value: the icon follows the title at
         * {@code GAP_SM + 1}, as drawSection places it, and keeps a full gap from the value.
         */
        static float headingRoom(float innerWidth, float valueWidth) {
            return innerWidth - valueWidth - Metrics.GAP_SM - 1 - Metrics.ICON - Metrics.GAP;
        }

        /**
         * The face of the offset in the card headings: the title's own bold face while every title fits beside the
         * widest offset in it, the smaller bold label face otherwise. Decided for the window, from the widest offset
         * (-500 or +500), so it does not change while a slider is dragged.
         */
        static TextStyle valueStyle(TextMeasurer measurer, List<String> titles, List<String> values, float innerWidth) {
            for (TextStyle style : new TextStyle[]{TextStyle.STRONG, TextStyle.LABEL}) {
                float room = headingRoom(innerWidth, valueWidth(measurer, values, style));
                boolean fits = true;
                for (String title : titles) {
                    fits &= TextFit.width(measurer, title, TextStyle.STRONG) <= room;
                }
                if (fits) {
                    return style;
                }
            }
            return TextStyle.LABEL;
        }

        static float valueWidth(TextMeasurer measurer, List<String> values, TextStyle style) {
            float widest = 0F;
            for (String value : values) {
                widest = Math.max(widest, TextFit.width(measurer, value, style));
            }
            return widest;
        }

        /** How wide the note under the stage may set its lines: the stage's width less its icon column and padding. */
        static float statusTextWidth(int width, int controlWidth) {
            float textX = controlWidth + STAGE_GAP + 8 + Metrics.ICON + Metrics.GAP;
            return width - STAGE_MARGIN - 6 - textX;
        }

        /** What the tap count may take on its line, beside the dots. */
        static float tapsRoom(float innerWidth) {
            return innerWidth - DOTS_WIDTH - Metrics.GAP;
        }

        /** Body text while it fits the room; the caption face otherwise, and cut only if even that does not fit. */
        static Note status(TextMeasurer measurer, String text, float width, float room) {
            TextStyle style = TextStyle.BODY;
            List<String> lines = TextFit.wrap(measurer, text, style, width);
            if (lines.size() > linesThatFit(style, room)) {
                style = TextStyle.CAPTION;
                lines = TextFit.wrap(measurer, text, style, width, linesThatFit(style, room));
            }
            return new Note(style, lines, height(style, lines.size()));
        }

        /** The panel around {@code lines} lines of a face: five units of padding above and below. */
        static float height(TextStyle style, int lines) {
            return (Math.max(1, lines) - 1) * Metrics.lineHeight(style) + TextFit.boxHeight(style) + 10;
        }

        static int linesThatFit(TextStyle style, float room) {
            return Math.max(1, (int) Math.floor((room - 10 - TextFit.boxHeight(style)) / Metrics.lineHeight(style)) + 1);
        }

        /**
         * How far above the window's bottom the stage ends: {@code STAGE_FOOT} while the longest of the notes fits under
         * it, in body text or in captions; otherwise as much more as that note needs in captions, but never so much that
         * the stage falls under its minimum height.
         */
        static int stageFoot(TextMeasurer measurer, List<String> texts, float width, int height) {
            float room = STAGE_FOOT - STATUS_ABOVE_FOOTER - STATUS_BELOW_STAGE;
            float needed = 0F;
            for (String text : texts) {
                float body = height(TextStyle.BODY, TextFit.wrap(measurer, text, TextStyle.BODY, width).size());
                float caption = height(TextStyle.CAPTION, TextFit.wrap(measurer, text, TextStyle.CAPTION, width).size());
                needed = Math.max(needed, body <= room ? body : caption);
            }
            int foot = Math.max(STAGE_FOOT, (int) Math.ceil(needed) + STATUS_ABOVE_FOOTER + STATUS_BELOW_STAGE);
            return Math.min(foot, Math.max(STAGE_FOOT, height - STAGE_TOP - STAGE_MIN_HEIGHT));
        }
    }

    /**
     * The help of a card or slider in the toolkit's tooltip card, kept inside the window: beside the pointer, or centred
     * under the slider the keyboard is on, as UiTooltip.Deferred places a widget's tip.
     */
    private void drawHint(GuiGraphics graphics) {
        if (hintWidget != null) {
            UiTooltip.draw(graphics, UiTooltip.placeUnder(TextStyles.measurer(), hint.getString(), hintWidget, width, height));
        } else {
            UiTooltip.draw(graphics, hint, hintX, hintY, hintHovered, width, height);
        }
    }

    /** One line of toolkit text, cut with an ellipsis to the room it has. */
    private static void line(GuiGraphics graphics, String text, TextStyle style, float x, float y, float maxWidth,
                             int colour) {
        if (!(maxWidth > 0)) {
            return;
        }
        String fitted = TextFit.ellipsize(TextStyles.measurer(), text, style, maxWidth);
        if (!fitted.isEmpty()) {
            TextStyles.queue(graphics, TextStyles.text(fitted, style).getVisualOrderText(), x, y, colour);
        }
    }

    private static int lighten(int color) {
        return 0xFF000000
                | Math.min(255, ((color >>> 16) & 0xFF) + 95) << 16
                | Math.min(255, ((color >>> 8) & 0xFF) + 95) << 8
                | Math.min(255, (color & 0xFF) + 95);
    }

    private static Component tr(String key, Object... args) {
        return Component.translatable("music_battle.calibration." + key, args);
    }

    /**
     * One of the two tabs in the header. The current one is drawn the way the Workshop draws its active tab - a raised
     * fill with an accent rule on the bar's lower edge - instead of as a disabled button, which is what a vanilla
     * button that cannot be pressed would say about it.
     */
    private static final class TabButton extends Button {
        private final boolean selected;

        private TabButton(Component label, OnPress onPress, int x, int y, int width, int height, boolean selected) {
            super(x, y, width, height, label, onPress, DEFAULT_NARRATION);
            this.selected = selected;
            this.active = !selected;
        }

        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            Rect rect = Rect.of(getX(), getY(), getWidth(), getHeight());
            boolean hovered = this.active && isHoveredOrFocused();
            if (hovered) {
                HandCursor.request(HandCursor.Kind.HAND);
            }
            if (selected) {
                RoundedRect.fill(graphics, rect.x(), rect.y(), rect.w(), rect.h(), Palette.RADIUS_SM, Palette.BG_3);
                RoundedRect.fill(graphics, rect.x() + 3, HEADER - 1.5F, rect.w() - 6, 1.5F, 0.75F, Palette.ACCENT);
            } else {
                // The hover of a quiet FancyButton, so these tabs answer the pointer like the track library's do.
                WorkshopDraw.button(graphics, rect, WorkshopDraw.ButtonStyle.QUIET, hovered, true);
                if (hovered) {
                    WorkshopDraw.outline(graphics, rect, Palette.RADIUS_SM, 1.0F, Palette.fade(Palette.ACCENT, 0.5F));
                }
            }
            TextStyle style = selected ? TextStyle.STRONG : TextStyle.BODY;
            line(graphics, getMessage().getString(), style,
                    rect.centerX() - Math.min(rect.w() - 10, TextFit.width(TextStyles.measurer(), getMessage().getString(), style)) * 0.5F,
                    TextStyles.centeredCapY(style, rect.y(), rect.h()), rect.w() - 10,
                    selected || hovered ? Palette.TEXT_1 : Palette.TEXT_2);
        }
    }

    private final class OffsetSlider extends AbstractSliderButton {
        private final boolean audio;

        private OffsetSlider(int x, int y, int width, boolean audio) {
            super(x, y, width, ROW, Component.empty(), ((audio ? ClientBattleSettings.audioOffsetMs() : ClientBattleSettings.inputOffsetMs()) + 500) / 1000.0);
            this.audio = audio;
            updateMessage();
        }

        @Override public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            // Drawn by the toolkit, like every other slider in the mod, but without its label: the card's heading
            // shows the value, where the handle cannot cover it (see drawSection).
            Slider.paint(graphics, Rect.of(getX(), getY(), getWidth(), getHeight()), value, Component.empty(),
                    isHoveredOrFocused(), active);
        }

        /** The help the vanilla tooltip used to carry is still read out, now that it is drawn by the screen instead. */
        @Override public void updateWidgetNarration(NarrationElementOutput output) {
            super.updateWidgetNarration(output);
            output.add(NarratedElementType.HINT, tr(audio ? "audio_help" : "input_help"));
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

package ru.gora.musicbattle.client;

import static ru.gora.musicbattle.client.ClientTranslations.text;

import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.IllegalFormatException;
import java.util.List;
import java.util.Locale;
import java.util.function.UnaryOperator;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;
import ru.gora.musicbattle.client.ui.Palette;
import ru.gora.musicbattle.client.ui.RoundedRect;
import ru.gora.musicbattle.client.ui.Slider;
import ru.gora.musicbattle.client.ui.TextMeasurer;
import ru.gora.musicbattle.client.ui.TextStyle;
import ru.gora.musicbattle.client.ui.TextStyles;
import ru.gora.musicbattle.client.ui.UiIcon;
import ru.gora.musicbattle.client.ui.UiLayer;
import ru.gora.musicbattle.client.workshop.WorkshopDraw;
import ru.gora.musicbattle.client.workshop.layout.Rect;
import ru.gora.musicbattle.client.workshop.layout.TextFit;
import ru.gora.musicbattle.network.ConfigureSpeakerPayload;
import ru.gora.musicbattle.network.OpenSpeakerConfigPayload;

/**
 * A speaker's own settings: how far it carries, how loud it plays, and a three-band equaliser.
 *
 * <p>It used to be a square teal slab with a cyan rule along its top and the vanilla font, from before the mod had a
 * look of its own. It is now drawn as one of the Workshop's dialogs - the same raised panel, heading and separator as
 * the confirmations in the track library - with the sliders grouped under two small headings, Cancel and Save on the
 * right, and Enter for Save the way every toolkit dialog takes it.
 *
 * <p>Each slider's name and value sit on a line above its track rather than on it. The toolkit centres a slider's
 * label on the track, and four of these five rest in the middle - 100% volume, 0 dB on every band - which put the
 * handle over the number in exactly the state a player opens the screen in.
 *
 * <p>The world stays faintly visible behind it, as it did before: the screen is opened by clicking a speaker, and
 * the speaker is what it talks about.
 */
public final class SpeakerConfigScreen extends UiLayer {
    /** As wide as a Workshop dialog, so three equaliser sliders still read side by side. */
    private static final float MAX_PANEL_WIDTH = 380F;
    private static final int PAD = 12;
    private static final int GAP = 6;
    /** Heading row; the separator sits on its lower edge, where the Workshop dialogs draw theirs. */
    private static final int HEADING = 30;
    /** A slider's name and value, above its track. */
    private static final int VALUE_ROW = 12;
    private static final int SLIDER_HEIGHT = 16;
    private static final int BUTTON_HEIGHT = 20;
    /** A section heading and the room under it before its first slider. */
    private static final int SECTION = 13;
    /** Workshop dialogs never shrink an action below this, so Cancel and Save stay a matched pair. */
    private static final float MIN_BUTTON_WIDTH = 64F;

    private final OpenSpeakerConfigPayload payload;
    private final List<SettingSlider> sliders = new ArrayList<>();
    private float radius;
    private float volume;
    private float lowGainDb;
    private float midGainDb;
    private float highGainDb;
    private Rect panel = Rect.EMPTY;
    private int soundHeadingY;
    private int equalizerHeadingY;

    public SpeakerConfigScreen(OpenSpeakerConfigPayload payload) {
        super(Component.literal(text("music_battle.l10n.client.109")));
        this.payload = payload;
        this.radius = Mth.clamp(payload.radius(), 2.0F, 64.0F);
        this.volume = Mth.clamp(payload.volume(), 0.0F, 2.0F);
        this.lowGainDb = Mth.clamp(payload.lowGainDb(), -12.0F, 12.0F);
        this.midGainDb = Mth.clamp(payload.midGainDb(), -12.0F, 12.0F);
        this.highGainDb = Mth.clamp(payload.highGainDb(), -12.0F, 12.0F);
    }

    @Override
    protected void layout() {
        sliders.clear();
        int slider = VALUE_ROW + SLIDER_HEIGHT;
        String[] bands = {text("music_battle.l10n.client.112"), text("music_battle.l10n.client.113"),
                text("music_battle.l10n.client.114")};
        int panelWidth = panelWidth(this.width, bandText(TextStyles.measurer(), bands, playerLocale(),
                ClientTranslations::text));
        int inner = panelWidth - PAD * 2;
        int panelHeight = HEADING + PAD + SECTION + slider + GAP + 2 + slider + PAD
                + SECTION + slider + PAD + 2 + BUTTON_HEIGHT + PAD;
        int left = (this.width - panelWidth) / 2;
        int top = Math.max(6, (this.height - panelHeight) / 2);
        panel = new Rect(left, top, panelWidth, panelHeight);
        int x = left + PAD;

        // The sliders keep the values they had when the window was resized: they are read from the fields, which the
        // sliders write as they move, not from the payload the screen was opened with.
        soundHeadingY = top + HEADING + PAD;
        int y = soundHeadingY + SECTION + VALUE_ROW;
        addSlider(new SettingSlider(x, y, inner, text("music_battle.l10n.client.110"), radius, 2.0F, 64.0F,
                value -> radius = value, Unit.BLOCKS));
        y += SLIDER_HEIGHT + GAP + 2 + VALUE_ROW;
        addSlider(new SettingSlider(x, y, inner, text("music_battle.l10n.client.111"), volume, 0.0F, 2.0F,
                value -> volume = value, Unit.PERCENT));

        equalizerHeadingY = y + SLIDER_HEIGHT + PAD;
        y = equalizerHeadingY + SECTION + VALUE_ROW;
        int band = (inner - GAP * 2) / 3;
        // The last band takes what the division left over, so the row ends exactly on the panel's inner edge.
        int lastBand = inner - (band + GAP) * 2;
        addSlider(new SettingSlider(x, y, band, bands[0], lowGainDb, -12.0F, 12.0F,
                value -> lowGainDb = value, Unit.DECIBELS));
        addSlider(new SettingSlider(x + band + GAP, y, band, bands[1], midGainDb, -12.0F, 12.0F,
                value -> midGainDb = value, Unit.DECIBELS));
        addSlider(new SettingSlider(x + (band + GAP) * 2, y, lastBand, bands[2], highGainDb,
                -12.0F, 12.0F, value -> highGainDb = value, Unit.DECIBELS));

        Component saveLabel = Component.literal(text("music_battle.l10n.client.100"));
        Component cancelLabel = Component.literal(text("music_battle.l10n.client.044"));
        int buttonsY = top + panelHeight - PAD - BUTTON_HEIGHT;
        // One width for both, taken from the longer label, so Cancel and Save read as a pair in either language; never
        // more than half the row, which only a very narrow window would ask of them.
        int buttonWidth = Math.round(Math.min((inner - GAP) / 2F,
                Math.max(MIN_BUTTON_WIDTH, Math.max(labelWidth(saveLabel), labelWidth(cancelLabel)) + 28F)));
        int saveX = left + panelWidth - PAD - buttonWidth;
        addRenderableWidget(FancyButton.create(cancelLabel, button -> onClose(),
                saveX - GAP - buttonWidth, buttonsY, buttonWidth, BUTTON_HEIGHT));
        addRenderableWidget(FancyButton.create(saveLabel, button -> save(), saveX, buttonsY, buttonWidth, BUTTON_HEIGHT)
                .primary());
    }

    private void addSlider(SettingSlider slider) {
        sliders.add(addRenderableWidget(slider));
    }

    /**
     * The dialog's width: a Workshop dialog's, at most 380 and 12 from each side of the window, but never so narrow that
     * an equaliser band cannot show its name beside its widest value. In a 320-unit window that width leaves a band 86
     * units, and "Средние" beside "+12,0 дБ" needs 90, so the name was cut to "Сред…" at the ends of the range; the
     * dialog then takes up to 6 units of each margin instead. Pure, for the ui-leftovers check.
     *
     * @param bandText the most a band's line needs: its name, the gap and its widest value (see {@link #bandText})
     */
    static int panelWidth(int windowWidth, float bandText) {
        int workshop = Math.round(Math.min(MAX_PANEL_WIDTH, windowWidth - 24F));
        int needed = (int) Math.ceil(bandText) * 3 + GAP * 2 + PAD * 2;
        return Math.max(workshop, Math.min(needed, windowWidth - 12));
    }

    /** The widest band line: the longest name, the gap, and the value at either end of the range, whichever is wider. */
    static float bandText(TextMeasurer measurer, String[] names, Locale locale, UnaryOperator<String> templates) {
        float widest = 0F;
        for (String name : names) {
            float value = 0F;
            for (float end : new float[]{-12.0F, 12.0F}) {
                String shown = valuePart(message(Unit.DECIBELS, name, end, locale, templates), name);
                value = Math.max(value, TextFit.width(measurer, shown, TextStyle.BODY));
            }
            widest = Math.max(widest, TextFit.width(measurer, name, TextStyle.BODY) + GAP + value);
        }
        return widest;
    }

    /** What a 20 unit FancyButton draws its label in: the body face. */
    private static float labelWidth(Component label) {
        return TextFit.width(TextStyles.measurer(), label.getString(), TextStyle.BODY);
    }

    /**
     * A dim of the page colour over the world rather than the toolkit's opaque page: the speaker this edits is right
     * behind the dialog, and seeing it is part of knowing which one is open.
     */
    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, this.width, this.height, Palette.withAlpha(Palette.BG_0, 0.82F));
    }

    @Override
    protected void renderUi(GuiGraphics graphics, double mouseX, double mouseY, float partialTick) {
        if (panel.empty()) {
            return;
        }
        RoundedRect.shadow(graphics, panel.x(), panel.y() + 2F, panel.w(), panel.h(), Palette.RADIUS_LG, 12F,
                Palette.SHADOW_LIFTED);
        RoundedRect.fill(graphics, panel.x(), panel.y(), panel.w(), panel.h(), Palette.RADIUS_LG, Palette.BG_1);
        RoundedRect.stroke(graphics, panel.x(), panel.y(), panel.w(), panel.h(), Palette.RADIUS_LG, 1F, Palette.LINE);

        float headingX = panel.x() + PAD;
        WorkshopDraw.icon(graphics, new Rect(headingX, panel.y() + PAD + 1F, 12F, 12F), UiIcon.SETTINGS, Palette.ACCENT);
        headingX += 17F;
        label(graphics, this.title.getString(), TextStyle.TITLE, headingX, panel.y() + PAD + 2F,
                panel.right() - PAD - headingX, Palette.TEXT_1);
        RoundedRect.fill(graphics, panel.x() + PAD, panel.y() + HEADING, panel.w() - PAD * 2, 1F, 0, Palette.LINE);

        float sectionWidth = panel.w() - PAD * 2;
        label(graphics, Component.translatable("screen.music_battle.speaker.sound").getString(), TextStyle.LABEL,
                panel.x() + PAD, soundHeadingY, sectionWidth, Palette.TEXT_3);
        label(graphics, Component.translatable("screen.music_battle.speaker.equalizer").getString(), TextStyle.LABEL,
                panel.x() + PAD, equalizerHeadingY, sectionWidth, Palette.TEXT_3);
        for (SettingSlider slider : sliders) {
            drawValueRow(graphics, slider);
        }
    }

    /** The slider's name on the left and its value on the right, on the line above the track. */
    private static void drawValueRow(GuiGraphics graphics, SettingSlider slider) {
        float y = slider.getY() - VALUE_ROW + 1;
        float right = slider.getX() + slider.getWidth();
        String value = slider.valueText();
        float valueWidth = TextFit.width(TextStyles.measurer(), value, TextStyle.BODY);
        boolean lit = slider.isHoveredOrFocused();
        TextStyles.queueRight(graphics, TextStyles.text(value, TextStyle.BODY).getVisualOrderText(), right, y,
                Palette.TEXT_1);
        label(graphics, slider.name(), TextStyle.BODY, slider.getX(), y, slider.getWidth() - valueWidth - GAP,
                lit ? Palette.TEXT_1 : Palette.TEXT_2);
    }

    private static void label(GuiGraphics graphics, String value, TextStyle style, float x, float y, float maxWidth,
                              int colour) {
        if (!(maxWidth > 0)) {
            return;
        }
        String fitted = TextFit.ellipsize(TextStyles.measurer(), value, style, maxWidth);
        if (!fitted.isEmpty()) {
            TextStyles.queue(graphics, TextStyles.text(fitted, style).getVisualOrderText(), x, y, colour);
        }
    }

    /**
     * Enter saves, as the primary action of every toolkit dialog does; Esc still closes without saving.
     *
     * <p>The one exception is a button the keyboard is on: Enter presses that button, so tabbing to Cancel and
     * pressing Enter cancels. A slider does not get the key, although vanilla would have it switch the slider's arrow
     * keys on and off: a slider keeps the focus after it is dragged, and Enter after dragging means save.
     */
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        boolean enter = keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER;
        if (enter && !(getFocused() instanceof Button)) {
            save();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private void save() {
        PacketDistributor.sendToServer(new ConfigureSpeakerPayload(payload.pos(), radius, volume,
                lowGainDb, midGainDb, highGainDb));
        onClose();
    }

    private interface Setter {
        void set(float value);
    }

    private static final class SettingSlider extends AbstractSliderButton {
        private final String label;
        private final float minimum;
        private final float maximum;
        private final Setter setter;
        private final Unit unit;

        private SettingSlider(int x, int y, int width, String label, float current,
                              float minimum, float maximum, Setter setter, Unit unit) {
            super(x, y, width, SLIDER_HEIGHT, Component.empty(), (current - minimum) / (maximum - minimum));
            this.label = label;
            this.minimum = minimum;
            this.maximum = maximum;
            this.setter = setter;
            this.unit = unit;
            updateMessage();
        }

        @Override
        public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            // Drawn by the toolkit, like every other slider in the mod, without its label: the line above the track
            // carries the name and the value (see drawValueRow), where the handle cannot cover them.
            Slider.paint(graphics, Rect.of(getX(), getY(), getWidth(), getHeight()), value, Component.empty(),
                    isHoveredOrFocused(), active);
        }

        private float setting() {
            return Mth.clamp((float) (minimum + value * (maximum - minimum)), minimum, maximum);
        }

        private String name() {
            return label;
        }

        /** The value alone, with its unit (see {@link #valuePart}). */
        private String valueText() {
            return valuePart(getMessage().getString(), label);
        }

        @Override
        protected void updateMessage() {
            // The whole sentence stays the widget's message, so narration still reads the name with the value.
            setMessage(Component.literal(message(unit, label, setting(), playerLocale(), ClientTranslations::text)));
        }

        @Override
        protected void applyValue() {
            setter.set(setting());
        }
    }

    enum Unit { BLOCKS, PERCENT, DECIBELS }

    /**
     * The value of a slider's message, with its unit. Every unit goes through a translated template that carries it,
     * and every one of those templates reads "name: value", so the name is cut off its front.
     */
    static String valuePart(String message, String label) {
        String prefix = label + ": ";
        return message.startsWith(prefix) ? message.substring(prefix.length()) : message;
    }

    /**
     * A slider's "name: value unit" as the player's language writes it: the template, and with it the unit, from the
     * language file ("Низкие: +1,5 дБ", "Bass: +1.5 dB"), and the number with that language's decimal separator. The
     * unit used to be a literal " dB" and the number always had a point, so the Russian dialog read "+1.5 dB". Pure, for
     * the ui-leftovers check; {@code templates} is the raw language lookup.
     */
    static String message(Unit unit, String label, float setting, Locale locale, UnaryOperator<String> templates) {
        String key = switch (unit) {
            case BLOCKS -> "music_battle.l10n.client.115";
            case PERCENT -> "screen.music_battle.speaker.percent";
            case DECIBELS -> "screen.music_battle.speaker.decibels";
        };
        String number = switch (unit) {
            case BLOCKS -> decimal(setting, false, locale);
            case PERCENT -> String.format(Locale.ROOT, "%.0f", setting * 100.0F);
            case DECIBELS -> decimal(setting, true, locale);
        };
        try {
            return String.format(Locale.ROOT, templates.apply(key), label, number);
        } catch (IllegalFormatException damaged) {
            // A resource pack's template with a stray % would otherwise throw out of every frame that moves a slider.
            return label + ": " + number;
        }
    }

    /**
     * One decimal, with the decimal separator of {@code locale}: a comma in Russian, German or French, a point in
     * English. Only the separator is taken from the language: for Arabic or Bengali Java would also switch the digits
     * and the separator to ones Nunito does not have, which the game would draw from its bitmap font in the middle of
     * the number, so the digits stay those of every other number on screen.
     */
    static String decimal(float value, boolean signed, Locale locale) {
        String text = String.format(Locale.ROOT, signed ? "%+.1f" : "%.1f", value);
        if (text.equals("-0.0")) {
            // A value a hair under zero rounds to zero, which has no sign of its own to show.
            text = signed ? "+0.0" : "0.0";
        }
        return DecimalFormatSymbols.getInstance(locale).getDecimalSeparator() == ',' ? text.replace('.', ',') : text;
    }

    /**
     * The game's language as a Java locale, for its decimal separator; the root locale before the options are read.
     * The DJ panel's speaker sliders use it too, so the same setting reads the same in both places.
     */
    static Locale playerLocale() {
        Minecraft minecraft = Minecraft.getInstance();
        String code = minecraft == null || minecraft.options == null ? null : minecraft.options.languageCode;
        return code == null || code.isBlank() ? Locale.ROOT : Locale.forLanguageTag(code.replace('_', '-'));
    }
}

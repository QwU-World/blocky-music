package ru.gora.musicbattle.client;

import java.util.Locale;
import java.util.Objects;
import java.util.function.IntConsumer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;
import ru.gora.musicbattle.client.ui.FancyEditBox;
import ru.gora.musicbattle.client.ui.Palette;
import ru.gora.musicbattle.client.ui.RoundedRect;
import ru.gora.musicbattle.client.ui.TextStyle;
import ru.gora.musicbattle.client.ui.TextStyles;
import ru.gora.musicbattle.data.ColorPickerMath;

/**
 * Standalone exact RGB/HSV color picker shared by the effect editor (a beatmap's effects and a DJ's), the note colours
 * in the battle settings and the gameplay preview's background.
 *
 * <p>It used to be a square teal slab: a flat panel with a cyan rule along its top, white frames around the colour
 * field, and the bitmap font. It is now one of the toolkit's dialogs - the raised rounded panel, the heading over a
 * hairline, quiet labels, rounded frames and ring markers - with every control where it always was, so a player who
 * knows where to click still finds it there.
 */
public final class ExactColorPickerScreen extends Screen implements DjPanelOwnedScreen {
    private static final int PANEL_MARGIN = 14;
    private static final int HUE_WIDTH = 14;
    private static final int CONTROL_MIN_WIDTH = 110;
    /** The heading's cap line and the hairline under it, from the panel's top; the controls start at 34. */
    private static final int TITLE_Y = 8;
    private static final int RULE_Y = 26;
    /**
     * The page colour over the screen the picker was opened from, at the dim it always had. That screen keeps drawing
     * underneath, and on most of them it is where the colour being chosen shows live, so it stays in sight.
     */
    private static final int SCRIM = Palette.withAlpha(Palette.BG_0, 0.72F);
    /** The white of the markers and their dark halo, which keeps them visible over a light and a dark colour alike. */
    private static final int MARKER = 0xFFFFFFFF;
    private static final int MARKER_HALO = 0x8C000000;

    private final Screen parent;
    private final IntConsumer onPreview;
    private final IntConsumer onApply;
    private final int initialColor;

    private EditBox hexBox;
    private EditBox redBox;
    private EditBox greenBox;
    private EditBox blueBox;
    private int color;
    private float hue;
    private float saturation;
    private float value;
    private boolean updatingFields;
    private boolean applied;
    private DragTarget dragTarget = DragTarget.NONE;

    public ExactColorPickerScreen(Screen parent, Component title, int initialColor, IntConsumer onApply) {
        this(parent, title, initialColor, ignored -> { }, onApply);
    }

    public ExactColorPickerScreen(Screen parent, Component title, int initialColor,
                                  IntConsumer onPreview, IntConsumer onApply) {
        super(Objects.requireNonNull(title, "title"));
        this.parent = Objects.requireNonNull(parent, "parent");
        this.onPreview = Objects.requireNonNull(onPreview, "onPreview");
        this.onApply = Objects.requireNonNull(onApply, "onApply");
        this.color = opaque(initialColor);
        this.initialColor = this.color;
        updateHsvFromColor(false);
    }

    @Override
    protected void init() {
        dragTarget = DragTarget.NONE;
        int controlsX = controlsX();
        int controlsWidth = controlsWidth();
        int top = pickerTop();

        hexBox = addRenderableWidget(new FancyEditBox(font, controlsX, top + 13,
                controlsWidth, 20, Component.translatable("screen.music_battle.color_picker.hex")));
        hexBox.setMaxLength(7);
        hexBox.setFilter(ExactColorPickerScreen::isPartialHex);

        int channelGap = 4;
        int channelWidth = Math.max(24, (controlsWidth - channelGap * 2) / 3);
        int blueX = controlsX + (channelWidth + channelGap) * 2;
        int blueWidth = Math.max(24, controlsX + controlsWidth - blueX);
        redBox = addRenderableWidget(channelBox(controlsX, top + 54, channelWidth, "red"));
        greenBox = addRenderableWidget(channelBox(
                controlsX + channelWidth + channelGap, top + 54, channelWidth, "green"));
        blueBox = addRenderableWidget(channelBox(blueX, top + 54, blueWidth, "blue"));

        hexBox.setResponder(this::onHexChanged);
        redBox.setResponder(ignored -> onChannelChanged(redBox, 16));
        greenBox.setResponder(ignored -> onChannelChanged(greenBox, 8));
        blueBox.setResponder(ignored -> onChannelChanged(blueBox, 0));
        syncFields();

        int buttonGap = 6;
        int buttonWidth = (panelWidth() - PANEL_MARGIN * 2 - buttonGap) / 2;
        int buttonY = panelTop() + panelHeight() - 28;
        // Done is what the picker is for, so it is the dialog's one primary action, as Save is in the toolkit's.
        addRenderableWidget(FancyButton.create(CommonComponents.GUI_DONE,
                ignored -> applyAndClose(), panelLeft() + PANEL_MARGIN, buttonY, buttonWidth, 20).primary());
        addRenderableWidget(FancyButton.create(CommonComponents.GUI_CANCEL,
                ignored -> onClose(), panelLeft() + PANEL_MARGIN + buttonWidth + buttonGap,
                buttonY, buttonWidth, 20));
    }

    private EditBox channelBox(int x, int y, int width, String channel) {
        EditBox box = new FancyEditBox(font, x, y, width, 20, channelLabel(channel));
        box.setMaxLength(3);
        box.setFilter(ExactColorPickerScreen::isPartialChannel);
        return box;
    }

    /** "R", "G" or "B", through the language files like every other word on screen. */
    private static Component channelLabel(String channel) {
        return Component.translatable("screen.music_battle.color_picker." + channel);
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    }

    @Override
    public void tick() {
        super.tick();
        parent.tick();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        parent.render(graphics, -1, -1, partialTick);
        ClientUiForeground.renderModal(graphics.pose(), graphics::flush,
                () -> renderPicker(graphics, mouseX, mouseY, partialTick));
    }

    private void renderPicker(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, SCRIM);
        int left = panelLeft();
        int top = panelTop();
        int panelWidth = panelWidth();
        int panelHeight = panelHeight();
        // The toolkit's dialog: the raised panel, its heading and the hairline under it (WorkshopDialogScreen).
        RoundedRect.shadow(graphics, left, top + 2.0F, panelWidth, panelHeight, Palette.RADIUS_LG, 12.0F,
                Palette.SHADOW_LIFTED);
        RoundedRect.fill(graphics, left, top, panelWidth, panelHeight, Palette.RADIUS_LG, Palette.BG_1);
        RoundedRect.stroke(graphics, left, top, panelWidth, panelHeight, Palette.RADIUS_LG, 1.0F, Palette.LINE);
        // The title names what is being coloured, a lane or an effect setting, and can be long: it is cut to the
        // panel rather than run past its edges.
        TextStyles.queueCentered(graphics, TextStyles.ellipsize(TextStyles.styled(title, TextStyle.TITLE),
                panelWidth - PANEL_MARGIN * 2.0F), left + panelWidth / 2.0F, top + TITLE_Y, Palette.TEXT_1);
        RoundedRect.fill(graphics, left + PANEL_MARGIN, top + RULE_Y, panelWidth - PANEL_MARGIN * 2.0F, 1.0F, 0.0F,
                Palette.LINE);
        // The colour field and the hue strip are plain fills, which go out as they are drawn: what is queued so far
        // goes first, so that the panel is under them.
        graphics.flush();
        drawSaturationValueSquare(graphics);
        drawHueStrip(graphics);
        drawControlLabelsAndSwatch(graphics);
        // The frames, markers, labels and swatch are queued; they go out before the widgets, which draw on top.
        graphics.flush();
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void drawSaturationValueSquare(GuiGraphics graphics) {
        int x = pickerLeft();
        int y = pickerTop();
        int size = pickerSize();
        int denominator = Math.max(1, size - 1);
        for (int column = 0; column < size; column++) {
            float columnSaturation = column / (float) denominator;
            int columnColor = 0xFF000000 | ColorPickerMath.hsvToRgb(hue, columnSaturation, 1.0F);
            graphics.fill(x + column, y, x + column + 1, y + size, columnColor);
        }
        graphics.fillGradient(x, y, x + size, y + size, 0x00000000, 0xFF000000);
        // Drawn over the field, so that its rounded corners also round off the field's own.
        RoundedRect.stroke(graphics, x - 1.0F, y - 1.0F, size + 2.0F, size + 2.0F, Palette.RADIUS_SM, 1.0F,
                Palette.LINE);

        // A ring around the chosen point, filled with the colour itself, rather than a crosshair over it.
        float markerX = x + Math.round(saturation * denominator) + 0.5F;
        float markerY = y + Math.round((1.0F - value) * denominator) + 0.5F;
        RoundedRect.stroke(graphics, markerX - 6.0F, markerY - 6.0F, 12.0F, 12.0F, 6.0F, 1.0F, MARKER_HALO);
        RoundedRect.stroke(graphics, markerX - 5.0F, markerY - 5.0F, 10.0F, 10.0F, 5.0F, 1.5F, MARKER);
        RoundedRect.circle(graphics, markerX, markerY, 3.5F, color);
    }

    private void drawHueStrip(GuiGraphics graphics) {
        int x = hueLeft();
        int y = pickerTop();
        int size = pickerSize();
        int denominator = Math.max(1, size - 1);
        for (int row = 0; row < size; row++) {
            int rowColor = 0xFF000000
                    | ColorPickerMath.hsvToRgb(row / (float) denominator, 1.0F, 1.0F);
            graphics.fill(x, y + row, x + HUE_WIDTH, y + row + 1, rowColor);
        }
        RoundedRect.stroke(graphics, x - 1.0F, y - 1.0F, HUE_WIDTH + 2.0F, size + 2.0F, Palette.RADIUS_SM, 1.0F,
                Palette.LINE);
        // A rounded handle across the strip, open in the middle so the hue it marks shows through.
        float markerY = y + Math.round(hue * denominator) + 0.5F;
        RoundedRect.stroke(graphics, x - 3.0F, markerY - 3.5F, HUE_WIDTH + 6.0F, 7.0F, 3.5F, 1.0F, MARKER_HALO);
        RoundedRect.stroke(graphics, x - 2.0F, markerY - 2.5F, HUE_WIDTH + 4.0F, 5.0F, 2.5F, 1.5F, MARKER);
    }

    private void drawControlLabelsAndSwatch(GuiGraphics graphics) {
        int x = controlsX();
        int y = pickerTop();
        int controlsWidth = controlsWidth();
        int channelGap = 4;
        int channelWidth = Math.max(24, (controlsWidth - channelGap * 2) / 3);
        int blueX = x + (channelWidth + channelGap) * 2;

        // Each label's cap line sits ten units over the control it names; the quiet face of a form's section labels.
        label(graphics, Component.translatable("screen.music_battle.color_picker.hex"), x, y + 3, controlsWidth);
        label(graphics, channelLabel("red"), x, y + 44, channelWidth);
        label(graphics, channelLabel("green"), x + channelWidth + channelGap, y + 44, channelWidth);
        label(graphics, channelLabel("blue"), blueX, y + 44, Math.max(24, x + controlsWidth - blueX));
        label(graphics, Component.translatable("music_battle.l10n.client.055"), x, y + 84, controlsWidth);

        int swatchTop = y + 94;
        int swatchBottom = Math.max(swatchTop + 20,
                Math.min(y + pickerSize(), panelTop() + panelHeight() - 40));
        int swatchHeight = swatchBottom - swatchTop;
        RoundedRect.fill(graphics, x, swatchTop, controlsWidth, swatchHeight, Palette.RADIUS_SM, color);
        RoundedRect.stroke(graphics, x, swatchTop, controlsWidth, swatchHeight, Palette.RADIUS_SM, 1.0F, Palette.LINE);
        int textColor = valueForText(color) > 0.55F ? Palette.ON_PILL : Palette.TEXT_1;
        TextStyles.queueCentered(graphics, TextStyles.text(formatHex(color), TextStyle.STRONG).getVisualOrderText(),
                x + controlsWidth / 2.0F, TextStyles.centeredCapY(TextStyle.STRONG, swatchTop, swatchHeight), textColor);
    }

    /** A control's name in the toolkit's small label face, cut to the control's width. */
    private static void label(GuiGraphics graphics, Component text, float x, float y, float maxWidth) {
        TextStyles.queue(graphics, TextStyles.ellipsize(TextStyles.styled(text, TextStyle.LABEL), maxWidth), x, y,
                Palette.TEXT_3);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && inside(mouseX, mouseY,
                pickerLeft(), pickerTop(), pickerSize(), pickerSize())) {
            dragTarget = DragTarget.SATURATION_VALUE;
            updateSaturationValue(mouseX, mouseY);
            return true;
        }
        if (button == 0 && inside(mouseX, mouseY,
                hueLeft(), pickerTop(), HUE_WIDTH, pickerSize())) {
            dragTarget = DragTarget.HUE;
            updateHue(mouseY);
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (button == 0 && dragTarget == DragTarget.SATURATION_VALUE) {
            updateSaturationValue(mouseX, mouseY);
            return true;
        }
        if (button == 0 && dragTarget == DragTarget.HUE) {
            updateHue(mouseY);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0 && dragTarget != DragTarget.NONE) {
            dragTarget = DragTarget.NONE;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    /**
     * Esc cancels and Enter applies, as in every toolkit dialog, except that Enter on a button the keyboard is on
     * presses that button: tabbing to Cancel and pressing Enter used to apply the colour anyway.
     */
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            onClose();
            return true;
        }
        boolean enter = keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER;
        if (enter && !(getFocused() instanceof Button)) {
            applyAndClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void onClose() {
        if (minecraft != null) {
            if (!applied) onPreview.accept(initialColor);
            minecraft.setScreen(parent);
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

    private void applyAndClose() {
        if (minecraft == null) {
            return;
        }
        int applied = opaque(color);
        this.applied = true;
        onApply.accept(applied);
        minecraft.setScreen(parent);
    }

    private void updateSaturationValue(double mouseX, double mouseY) {
        int denominator = Math.max(1, pickerSize() - 1);
        saturation = Mth.clamp((float) ((mouseX - pickerLeft()) / denominator), 0.0F, 1.0F);
        value = 1.0F - Mth.clamp((float) ((mouseY - pickerTop()) / denominator), 0.0F, 1.0F);
        updateColorFromHsv();
    }

    private void updateHue(double mouseY) {
        int denominator = Math.max(1, pickerSize() - 1);
        hue = Mth.clamp((float) ((mouseY - pickerTop()) / denominator), 0.0F, 1.0F);
        updateColorFromHsv();
    }

    private void updateColorFromHsv() {
        color = 0xFF000000 | ColorPickerMath.hsvToRgb(hue, saturation, value);
        syncFields();
        onPreview.accept(color);
    }

    private void onHexChanged(String entered) {
        if (updatingFields) {
            return;
        }
        String digits = entered.startsWith("#") ? entered.substring(1) : entered;
        if (digits.length() != 6) {
            return;
        }
        try {
            setColorFromRgb(Integer.parseInt(digits, 16));
        } catch (NumberFormatException ignored) {
        }
    }

    private void onChannelChanged(EditBox box, int shift) {
        if (updatingFields || box == null || box.getValue().isEmpty()) {
            return;
        }
        try {
            int channel = Mth.clamp(Integer.parseInt(box.getValue()), 0, 255);
            int rgb = (color & 0x00FFFFFF & ~(0xFF << shift)) | (channel << shift);
            setColorFromRgb(rgb);
        } catch (NumberFormatException ignored) {
        }
    }

    private void setColorFromRgb(int rgb) {
        color = opaque(rgb);
        updateHsvFromColor(true);
        syncFields();
        onPreview.accept(color);
    }

    private void updateHsvFromColor(boolean preserveHueForGray) {
        float red = ((color >>> 16) & 0xFF) / 255.0F;
        float green = ((color >>> 8) & 0xFF) / 255.0F;
        float blue = (color & 0xFF) / 255.0F;
        float max = Math.max(red, Math.max(green, blue));
        float min = Math.min(red, Math.min(green, blue));
        float delta = max - min;

        if (delta > 0.0F) {
            float nextHue;
            if (max == red) {
                nextHue = ((green - blue) / delta) % 6.0F;
            } else if (max == green) {
                nextHue = (blue - red) / delta + 2.0F;
            } else {
                nextHue = (red - green) / delta + 4.0F;
            }
            nextHue /= 6.0F;
            if (nextHue < 0.0F) {
                nextHue += 1.0F;
            }
            hue = nextHue;
        } else if (!preserveHueForGray) {
            hue = 0.0F;
        }
        saturation = max <= 0.0F ? 0.0F : delta / max;
        value = max;
    }

    private void syncFields() {
        if (hexBox == null || redBox == null || greenBox == null || blueBox == null) {
            return;
        }
        updatingFields = true;
        try {
            hexBox.setValue(formatHex(color));
            redBox.setValue(Integer.toString((color >>> 16) & 0xFF));
            greenBox.setValue(Integer.toString((color >>> 8) & 0xFF));
            blueBox.setValue(Integer.toString(color & 0xFF));
        } finally {
            updatingFields = false;
        }
    }

    private static boolean isPartialHex(String value) {
        if (value == null || value.length() > 7) {
            return false;
        }
        int start = value.startsWith("#") ? 1 : 0;
        if (start == 0 && value.length() > 6) {
            return false;
        }
        for (int index = start; index < value.length(); index++) {
            if (Character.digit(value.charAt(index), 16) < 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean isPartialChannel(String value) {
        if (value == null || value.length() > 3) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            if (!Character.isDigit(value.charAt(index))) {
                return false;
            }
        }
        return true;
    }

    private static boolean inside(double mouseX, double mouseY,
                                  int x, int y, int width, int height) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }

    private static int opaque(int color) {
        return 0xFF000000 | (color & 0x00FFFFFF);
    }

    private static String formatHex(int color) {
        return String.format(Locale.ROOT, "#%06X", color & 0x00FFFFFF);
    }

    private static float valueForText(int color) {
        float red = ((color >>> 16) & 0xFF) / 255.0F;
        float green = ((color >>> 8) & 0xFF) / 255.0F;
        float blue = (color & 0xFF) / 255.0F;
        return red * 0.299F + green * 0.587F + blue * 0.114F;
    }

    private int panelWidth() {
        return Math.max(1, Math.min(460, width - 16));
    }

    private int panelHeight() {
        return Math.max(1, Math.min(300, height - 16));
    }

    private int panelLeft() {
        return (width - panelWidth()) / 2;
    }

    private int panelTop() {
        return (height - panelHeight()) / 2;
    }

    private int pickerLeft() {
        return panelLeft() + PANEL_MARGIN;
    }

    private int pickerTop() {
        return panelTop() + 34;
    }

    private int pickerSize() {
        int contentWidth = panelWidth() - PANEL_MARGIN * 2;
        int widthLimit = contentWidth - HUE_WIDTH - 18 - CONTROL_MIN_WIDTH;
        int heightLimit = panelHeight() - 94;
        return Mth.clamp(Math.min(190, Math.min(widthLimit, heightLimit)), 72, 190);
    }

    private int hueLeft() {
        return pickerLeft() + pickerSize() + 8;
    }

    private int controlsX() {
        return hueLeft() + HUE_WIDTH + 10;
    }

    private int controlsWidth() {
        return Math.max(72, panelLeft() + panelWidth() - PANEL_MARGIN - controlsX());
    }

    private enum DragTarget {
        NONE,
        SATURATION_VALUE,
        HUE
    }
}

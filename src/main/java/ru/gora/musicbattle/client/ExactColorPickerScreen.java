package ru.gora.musicbattle.client;

import static ru.gora.musicbattle.client.ClientTranslations.text;

import java.util.Locale;
import java.util.Objects;
import java.util.function.IntConsumer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import ru.gora.musicbattle.data.ColorPickerMath;

/** Standalone exact RGB/HSV color picker shared by track and DJ effect editors. */
public final class ExactColorPickerScreen extends Screen implements DjPanelOwnedScreen {
    private static final int PANEL_MARGIN = 14;
    private static final int HUE_WIDTH = 14;
    private static final int CONTROL_MIN_WIDTH = 110;

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

        hexBox = addRenderableWidget(new ru.gora.musicbattle.client.ui.FancyEditBox(font, controlsX, top + 13,
                controlsWidth, 20, Component.literal("HEX")));
        hexBox.setMaxLength(7);
        hexBox.setFilter(ExactColorPickerScreen::isPartialHex);

        int channelGap = 4;
        int channelWidth = Math.max(24, (controlsWidth - channelGap * 2) / 3);
        int blueX = controlsX + (channelWidth + channelGap) * 2;
        int blueWidth = Math.max(24, controlsX + controlsWidth - blueX);
        redBox = addRenderableWidget(channelBox(controlsX, top + 54, channelWidth, "R"));
        greenBox = addRenderableWidget(channelBox(
                controlsX + channelWidth + channelGap, top + 54, channelWidth, "G"));
        blueBox = addRenderableWidget(channelBox(blueX, top + 54, blueWidth, "B"));

        hexBox.setResponder(this::onHexChanged);
        redBox.setResponder(ignored -> onChannelChanged(redBox, 16));
        greenBox.setResponder(ignored -> onChannelChanged(greenBox, 8));
        blueBox.setResponder(ignored -> onChannelChanged(blueBox, 0));
        syncFields();

        int buttonGap = 6;
        int buttonWidth = (panelWidth() - PANEL_MARGIN * 2 - buttonGap) / 2;
        int buttonY = panelTop() + panelHeight() - 28;
        addRenderableWidget(FancyButton.create(CommonComponents.GUI_DONE,
                ignored -> applyAndClose(), panelLeft() + PANEL_MARGIN, buttonY, buttonWidth, 20));
        addRenderableWidget(FancyButton.create(CommonComponents.GUI_CANCEL,
                ignored -> onClose(), panelLeft() + PANEL_MARGIN + buttonWidth + buttonGap,
                buttonY, buttonWidth, 20));
    }

    private EditBox channelBox(int x, int y, int width, String label) {
        EditBox box = new ru.gora.musicbattle.client.ui.FancyEditBox(font, x, y, width, 20, Component.literal(label));
        box.setMaxLength(3);
        box.setFilter(ExactColorPickerScreen::isPartialChannel);
        return box;
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
        graphics.fill(0, 0, width, height, 0xB8080C12);
        int left = panelLeft();
        int top = panelTop();
        int panelWidth = panelWidth();
        int panelHeight = panelHeight();
        graphics.fill(left, top, left + panelWidth, top + panelHeight, 0xFF12232D);
        graphics.fill(left, top, left + panelWidth, top + 3, 0xFF55D8F4);
        graphics.drawCenteredString(font, title, left + panelWidth / 2, top + 10, 0xFFFFFFFF);

        drawSaturationValueSquare(graphics);
        drawHueStrip(graphics);
        drawControlLabelsAndSwatch(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void drawSaturationValueSquare(GuiGraphics graphics) {
        int x = pickerLeft();
        int y = pickerTop();
        int size = pickerSize();
        graphics.fill(x - 1, y - 1, x + size + 1, y + size + 1, 0xFFE8F6FF);
        int denominator = Math.max(1, size - 1);
        for (int column = 0; column < size; column++) {
            float columnSaturation = column / (float) denominator;
            int columnColor = 0xFF000000 | ColorPickerMath.hsvToRgb(hue, columnSaturation, 1.0F);
            graphics.fill(x + column, y, x + column + 1, y + size, columnColor);
        }
        graphics.fillGradient(x, y, x + size, y + size, 0x00000000, 0xFF000000);

        int markerX = x + Math.round(saturation * denominator);
        int markerY = y + Math.round((1.0F - value) * denominator);
        graphics.fill(markerX - 4, markerY, markerX + 5, markerY + 1, 0xFF000000);
        graphics.fill(markerX, markerY - 4, markerX + 1, markerY + 5, 0xFF000000);
        graphics.fill(markerX - 3, markerY, markerX + 4, markerY + 1, 0xFFFFFFFF);
        graphics.fill(markerX, markerY - 3, markerX + 1, markerY + 4, 0xFFFFFFFF);
    }

    private void drawHueStrip(GuiGraphics graphics) {
        int x = hueLeft();
        int y = pickerTop();
        int size = pickerSize();
        graphics.fill(x - 1, y - 1, x + HUE_WIDTH + 1, y + size + 1, 0xFFE8F6FF);
        int denominator = Math.max(1, size - 1);
        for (int row = 0; row < size; row++) {
            int rowColor = 0xFF000000
                    | ColorPickerMath.hsvToRgb(row / (float) denominator, 1.0F, 1.0F);
            graphics.fill(x, y + row, x + HUE_WIDTH, y + row + 1, rowColor);
        }
        int markerY = y + Math.round(hue * denominator);
        graphics.fill(x - 3, markerY - 1, x + HUE_WIDTH + 3, markerY + 2, 0xFF000000);
        graphics.fill(x - 2, markerY, x + HUE_WIDTH + 2, markerY + 1, 0xFFFFFFFF);
    }

    private void drawControlLabelsAndSwatch(GuiGraphics graphics) {
        int x = controlsX();
        int y = pickerTop();
        int controlsWidth = controlsWidth();
        int channelGap = 4;
        int channelWidth = Math.max(24, (controlsWidth - channelGap * 2) / 3);
        int blueX = x + (channelWidth + channelGap) * 2;

        graphics.drawString(font, "HEX", x, y + 2, 0xFFB9D8E4, false);
        graphics.drawString(font, "R", x, y + 43, 0xFFFF8A8A, false);
        graphics.drawString(font, "G", x + channelWidth + channelGap, y + 43, 0xFF72EFA6, false);
        graphics.drawString(font, "B", blueX, y + 43, 0xFF77C7FF, false);
        graphics.drawString(font, Component.literal(text("music_battle.l10n.client.055")), x, y + 82, 0xFFB9D8E4, false);

        int swatchTop = y + 94;
        int swatchBottom = Math.max(swatchTop + 20,
                Math.min(y + pickerSize(), panelTop() + panelHeight() - 40));
        graphics.fill(x - 1, swatchTop - 1,
                x + controlsWidth + 1, swatchBottom + 1, 0xFFE8F6FF);
        graphics.fill(x, swatchTop, x + controlsWidth, swatchBottom, color);
        String value = formatHex(color);
        int textColor = valueForText(color) > 0.55F ? 0xFF101820 : 0xFFFFFFFF;
        graphics.drawCenteredString(font, value, x + controlsWidth / 2,
                swatchTop + Math.max(1, (swatchBottom - swatchTop - 8) / 2), textColor);
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

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
            onClose();
            return true;
        }
        if (keyCode == 257 || keyCode == 335) {
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

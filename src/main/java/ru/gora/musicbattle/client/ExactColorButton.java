package ru.gora.musicbattle.client;

import java.util.Locale;
import ru.gora.musicbattle.client.ui.Palette;
import java.util.Objects;
import java.util.function.IntConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** A compact color swatch button backed by {@link ExactColorPickerScreen}. */
public final class ExactColorButton extends AbstractButton {
    private final Screen parent;
    private final Component pickerTitle;
    private final IntConsumer onApply;
    private int color;

    public ExactColorButton(Screen parent, int x, int y, int width, int height,
                            Component pickerTitle, int initialColor, IntConsumer onApply) {
        super(x, y, width, height, Component.empty());
        this.parent = Objects.requireNonNull(parent, "parent");
        this.pickerTitle = Objects.requireNonNull(pickerTitle, "pickerTitle");
        this.onApply = Objects.requireNonNull(onApply, "onApply");
        setColor(initialColor);
    }

    public int color() {
        return color;
    }

    public void setColor(int color) {
        this.color = 0xFF000000 | (color & 0x00FFFFFF);
        setMessage(pickerTitle.copy().append(Component.literal(": " + hex(this.color))));
    }

    @Override
    public void onPress() {
        int originalColor = color;
        Minecraft.getInstance().setScreen(new ExactColorPickerScreen(
                parent,
                pickerTitle,
                color,
                this::setColor,
                selectedColor -> {
                    // The picker previews directly through this button. Restore the pre-open
                    // value before the commit callback so the parent can record one clean undo.
                    setColor(originalColor);
                    if (selectedColor != originalColor) onApply.accept(selectedColor);
                    setColor(selectedColor);
                }
        ));
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        int left = getX();
        int top = getY();
        int right = left + getWidth();
        int bottom = top + getHeight();
        int background = !active ? Palette.BG_3
                : isHoveredOrFocused() ? Palette.BG_4 : Palette.BG_3;
        int edge = !active ? 0xFF4B5862
                : isHoveredOrFocused() ? Palette.ACCENT : Palette.ACCENT_STRONG;

        graphics.fill(left, top, right, bottom, background);
        graphics.fill(left, top, right, top + 1, edge);
        graphics.fill(left, bottom - 1, right, bottom, Palette.BG_0);

        int swatchSize = Math.max(4, Math.min(getHeight() - 6, Math.max(12, getWidth() / 4)));
        int swatchLeft = left + 3;
        int swatchTop = top + (getHeight() - swatchSize) / 2;
        graphics.fill(swatchLeft - 1, swatchTop - 1,
                swatchLeft + swatchSize + 1, swatchTop + swatchSize + 1, Palette.TEXT_1);
        graphics.fill(swatchLeft, swatchTop,
                swatchLeft + swatchSize, swatchTop + swatchSize, color);

        Minecraft minecraft = Minecraft.getInstance();
        String value = hex(color);
        int textLeft = swatchLeft + swatchSize + 4;
        int textRight = right - 3;
        if (textRight > textLeft) {
            String fitted = minecraft.font.plainSubstrByWidth(value, textRight - textLeft);
            graphics.drawCenteredString(minecraft.font, fitted,
                    (textLeft + textRight) / 2, top + (getHeight() - 8) / 2,
                    active ? 0xFFFFFFFF : Palette.TEXT_MUTE);
        }
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        defaultButtonNarrationText(output);
    }

    private static String hex(int color) {
        return String.format(Locale.ROOT, "#%06X", color & 0x00FFFFFF);
    }
}

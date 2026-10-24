package ru.gora.musicbattle.client.ui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import ru.gora.musicbattle.client.workshop.WorkshopDraw;
import ru.gora.musicbattle.client.workshop.layout.Rect;

/**
 * A text field in the Workshop's shape, for the screens outside it.
 *
 * <p>Ten screens put a vanilla {@code EditBox} on the screen: a square grey border, a grey ground, and the bitmap
 * font. Beside a Workshop screen it reads as a different program. This keeps everything the vanilla widget does -
 * the caret, the selection, the suggestion, the scrolling, the keyboard - and changes the box it is drawn in: a
 * rounded panel from the palette, a border that lights up when the field has focus, and the hand cursor over it.
 *
 * <p>It is a subclass rather than a replacement so a screen can swap it in by changing one word, and so anything
 * that reflects on an {@code EditBox} still finds one.
 */
public final class FancyEditBox extends EditBox {
    /** How far the text sits inside the frame on the left and right, the same inset vanilla gives a bordered box. */
    private static final int TEXT_INSET = 4;

    public FancyEditBox(Font font, int x, int y, int width, int height, Component message) {
        super(font, x, y, width, height, message);
        // The frame is ours; the widget's own is a square grey rectangle.
        setBordered(false);
        setTextColor(Palette.TEXT_1);
        setTextColorUneditable(Palette.TEXT_MUTE);
    }

    @Override
    public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (!this.isVisible()) {
            return;
        }
        Rect rect = Rect.of(getX(), getY(), getWidth(), getHeight());
        boolean hovered = WorkshopDraw.hover(rect, mouseX, mouseY);
        if (hovered && this.isActive()) {
            HandCursor.request(HandCursor.Kind.IBEAM);
        }
        WorkshopDraw.panel(graphics, rect, Palette.RADIUS_SM, Palette.BG_3);
        int border = this.isFocused() ? Palette.ACCENT : hovered ? Palette.fade(Palette.ACCENT, 0.45F) : Palette.LINE;
        WorkshopDraw.outline(graphics, rect, Palette.RADIUS_SM, 1.0F, border);
        // The text itself, the caret and the selection are the widget's own work. With its border turned off the
        // vanilla widget draws them at the very top-left corner of the box, so one shift moves all three together:
        // clear of the frame on the left, and centred vertically the way a bordered box centres them.
        graphics.pose().pushPose();
        graphics.pose().translate((float) TEXT_INSET, (float) textOffsetY(), 0.0F);
        super.renderWidget(graphics, mouseX, mouseY, partialTick);
        graphics.pose().popPose();
    }

    @Override
    public void onClick(double mouseX, double mouseY) {
        // The text is drawn shifted right by the inset, so a click has to be shifted back or the caret would land
        // a few pixels to the right of where the player pressed.
        super.onClick(mouseX - TEXT_INSET, mouseY);
    }

    @Override
    public int getInnerWidth() {
        // Without its border the widget would let the text run the full width, under the frame on the right.
        return Math.max(0, getWidth() - TEXT_INSET * 2);
    }

    /** The vertical shift that centres the eight-pixel text line in the box, as vanilla does for a bordered box. */
    private int textOffsetY() {
        return Math.max(0, (getHeight() - 8) / 2);
    }
}

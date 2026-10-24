package ru.gora.musicbattle.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import ru.gora.musicbattle.client.ui.HandCursor;
import ru.gora.musicbattle.client.ui.Palette;
import ru.gora.musicbattle.client.ui.TextStyle;
import ru.gora.musicbattle.client.ui.TextStyles;
import ru.gora.musicbattle.client.workshop.WorkshopDraw;
import ru.gora.musicbattle.client.workshop.layout.Rect;

/**
 * The mod's button, everywhere outside the Workshop screens.
 *
 * <p>It used to be a square teal slab with a bright top edge and the vanilla bitmap font, from before the Workshop
 * and the website had a look of their own. Twenty screens use it, so this one class is where most of the mod stops
 * being a different product from the site: the same rounded shape, the same palette and the same typeface the
 * Workshop draws with, and the hand cursor every other clickable thing already asks for.
 *
 * <p>It stays a vanilla {@link Button}. That matters more than it looks: the screens keep it in {@code renderables},
 * keyboard focus keeps working, and the regression checks that walk {@code screen.children()} looking for widgets
 * keep finding it.
 *
 * <p>A button may be marked {@link #primary()} - the one thing the screen is for - or {@link #danger()}, for
 * something that deletes. Everything else is quiet, which is what most of them should be.
 */
public final class FancyButton extends Button {
    private WorkshopDraw.ButtonStyle style = WorkshopDraw.ButtonStyle.SECONDARY;

    private FancyButton(Builder builder) {
        super(builder);
    }

    public static FancyButton create(Component message, OnPress onPress, int x, int y, int width, int height) {
        return (FancyButton) Button.builder(message, onPress).bounds(x, y, width, height).build(FancyButton::new);
    }

    /** The action this screen exists for. One per screen, at most. */
    public FancyButton primary() {
        style = WorkshopDraw.ButtonStyle.PRIMARY;
        return this;
    }

    /** Removes something. */
    public FancyButton danger() {
        style = WorkshopDraw.ButtonStyle.DANGER;
        return this;
    }

    /** No fill until it is pointed at: for rows of small controls where a slab each would be noise. */
    public FancyButton quiet() {
        style = WorkshopDraw.ButtonStyle.QUIET;
        return this;
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        Rect rect = Rect.of(getX(), getY(), getWidth(), getHeight());
        boolean hovered = this.isHoveredOrFocused();
        if (hovered && this.active) {
            HandCursor.request(HandCursor.Kind.HAND);
        }
        WorkshopDraw.button(graphics, rect, style, hovered, this.active);
        if (style == WorkshopDraw.ButtonStyle.QUIET && hovered && this.active) {
            WorkshopDraw.outline(graphics, rect, Palette.RADIUS_SM, 1.0F, Palette.fade(Palette.ACCENT, 0.5F));
        }

        // The label is cut to the button rather than squeezed: a scaled bitmap font was the other half of why these
        // looked homemade next to the Workshop.
        TextStyle textStyle = getHeight() >= 18 ? TextStyle.BODY : TextStyle.CAPTION;
        int colour = !this.active ? Palette.TEXT_MUTE
                : style == WorkshopDraw.ButtonStyle.PRIMARY ? Palette.TEXT_1
                : hovered ? Palette.TEXT_1 : Palette.TEXT_2;
        float room = Math.max(4, getWidth() - 10);
        String label = ru.gora.musicbattle.client.workshop.layout.TextFit.ellipsize(
                TextStyles.measurer(), getMessage().getString(), textStyle, room);
        TextStyles.queueCentered(graphics,
                TextStyles.text(label, textStyle).getVisualOrderText(),
                getX() + getWidth() / 2.0F,
                getY() + (getHeight() - textStyle.capHeight()) / 2.0F,
                colour);
    }
}

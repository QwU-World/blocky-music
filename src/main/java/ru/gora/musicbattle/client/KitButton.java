package ru.gora.musicbattle.client;

import java.util.function.BooleanSupplier;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import ru.gora.musicbattle.client.ui.HandCursor;
import ru.gora.musicbattle.client.ui.Palette;
import ru.gora.musicbattle.client.ui.TextMeasurer;
import ru.gora.musicbattle.client.ui.TextStyle;
import ru.gora.musicbattle.client.ui.TextStyles;
import ru.gora.musicbattle.client.ui.UiIcon;
import ru.gora.musicbattle.client.workshop.WorkshopDraw;
import ru.gora.musicbattle.client.workshop.layout.Rect;
import ru.gora.musicbattle.client.workshop.layout.TextFit;

/**
 * The toolkit's button for the DJ's camera gallery and visual FX list, drawn as {@link FancyButton} draws the mod's
 * buttons: the Workshop's rounded surface and the label in Nunito, in the body face from 18 units high and the caption
 * below. It adds what those two screens need: one of the toolkit's icons in place of words (the FX list's arrows; the
 * atlas has no up chevron, so up is the down one turned over), and a switch's check box in front of its words, read as
 * it draws, with the switch's state in its narration. The screens used to spell these with "[ON]" and "[OFF]" after the
 * words and with arrows Nunito does not have, which Minecraft drew in its bitmap font.
 *
 * <p>The label is never cut where the screen's layout measured it with {@link #need}; the cut is only a guard for a
 * language the layout checks never ran with.
 */
final class KitButton extends Button {
    /** A label gets the button's width less this, as FancyButton gives it. */
    static final float PAD = 10.0F;
    /** The check box in front of a switch's words, and the gap after it. */
    static final float CHECK = 8.0F;
    static final float CHECK_GAP = 4.0F;
    /** The side of an icon button, and of its icon at most. */
    static final int ICON_BUTTON = 20;

    private final WorkshopDraw.ButtonStyle style;
    private final UiIcon icon;
    private final boolean flipped;
    private final BooleanSupplier on;

    private KitButton(Component message, OnPress press, Rect rect, WorkshopDraw.ButtonStyle style, UiIcon icon,
                      boolean flipped, BooleanSupplier on) {
        super(Math.round(rect.x()), Math.round(rect.y()), Math.round(rect.w()), Math.round(rect.h()), message, press,
                DEFAULT_NARRATION);
        this.style = style;
        this.icon = icon;
        this.flipped = flipped;
        this.on = on;
    }

    /** Words on the toolkit's surface: secondary, the screen's one primary action, or a danger. */
    static KitButton text(Component message, OnPress press, Rect rect, WorkshopDraw.ButtonStyle style) {
        return new KitButton(message, press, rect, style, null, false, null);
    }

    /** An icon in place of words; the words stay its narration, and the screen gives it a tooltip. */
    static KitButton icon(Component message, OnPress press, Rect rect, UiIcon icon, boolean flipped) {
        return new KitButton(message, press, rect, WorkshopDraw.ButtonStyle.SECONDARY, icon, flipped, null);
    }

    /** A switch: the check box, ticked while {@code on} says so, and its words. */
    static KitButton toggle(Component message, OnPress press, Rect rect, BooleanSupplier on) {
        return new KitButton(message, press, rect, WorkshopDraw.ButtonStyle.SECONDARY, null, false, on);
    }

    /** The face a button of this height sets its words in, as FancyButton picks it. */
    static TextStyle face(float height) {
        return height >= 18 ? TextStyle.BODY : TextStyle.CAPTION;
    }

    /**
     * The width a button of {@code height} needs for {@code label} whole: the label in its face, the check box of a
     * switch, and the padding, rounded up to a whole unit with a hair to spare (the button sums the same widths in another
     * order).
     */
    static int need(TextMeasurer measurer, String label, float height, boolean toggle) {
        float need = TextFit.width(measurer, label, face(height)) + PAD + (toggle ? CHECK + CHECK_GAP : 0.0F);
        return (int) Math.ceil(need + 0.05F);
    }

    boolean isToggle() {
        return on != null;
    }

    UiIcon icon() {
        return icon;
    }

    WorkshopDraw.ButtonStyle style() {
        return style;
    }

    /** A switch is read with its state, as vanilla's option buttons are: "DJ FX: ON". */
    @Override
    protected MutableComponent createNarrationMessage() {
        return on == null ? super.createNarrationMessage()
                : AbstractWidget.wrapDefaultNarrationMessage(CommonComponents.optionStatus(getMessage(), on.getAsBoolean()));
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        Rect rect = Rect.of(getX(), getY(), getWidth(), getHeight());
        boolean hovered = isHoveredOrFocused();
        if (hovered && active) {
            HandCursor.request(HandCursor.Kind.HAND);
        }
        WorkshopDraw.button(graphics, rect, style, hovered, active);
        if (style == WorkshopDraw.ButtonStyle.QUIET && hovered && active) {
            WorkshopDraw.outline(graphics, rect, Palette.RADIUS_SM, 1.0F, Palette.fade(Palette.ACCENT, 0.5F));
        }
        int colour = !active ? Palette.TEXT_MUTE
                : style == WorkshopDraw.ButtonStyle.PRIMARY || hovered ? Palette.TEXT_1 : Palette.TEXT_2;
        if (icon != null) {
            float size = Math.max(6.0F, Math.min(10.0F, getHeight() - 6.0F));
            Rect box = new Rect(rect.centerX() - size / 2.0F, rect.centerY() - size / 2.0F, size, size);
            if (flipped) {
                graphics.pose().pushPose();
                graphics.pose().translate(rect.centerX(), rect.centerY(), 0.0F);
                graphics.pose().mulPose(com.mojang.math.Axis.ZP.rotationDegrees(180.0F));
                graphics.pose().translate(-rect.centerX(), -rect.centerY(), 0.0F);
                WorkshopDraw.icon(graphics, box, icon, colour);
                graphics.pose().popPose();
            } else {
                WorkshopDraw.icon(graphics, box, icon, colour);
            }
            return;
        }
        TextStyle face = face(getHeight());
        String text = getMessage().getString();
        float mark = on == null ? 0.0F : CHECK + CHECK_GAP;
        float room = getWidth() - PAD - mark;
        String shown = TextFit.width(TextStyles.measurer(), text, face) <= room ? text
                : TextFit.ellipsize(TextStyles.measurer(), text, face, room);
        float left = rect.centerX() - (mark + TextFit.width(TextStyles.measurer(), shown, face)) / 2.0F;
        if (on != null) {
            WorkshopDraw.check(graphics, new Rect(left, rect.centerY() - CHECK / 2.0F, CHECK, CHECK), on.getAsBoolean(),
                    false, active);
        }
        TextStyles.queue(graphics, TextStyles.text(shown, face).getVisualOrderText(), left + mark,
                TextStyles.centeredCapY(face, rect.y(), rect.h()), colour);
    }
}

package ru.gora.musicbattle.client;

import java.util.Locale;
import ru.gora.musicbattle.client.ui.HandCursor;
import ru.gora.musicbattle.client.ui.Palette;
import ru.gora.musicbattle.client.ui.RoundedRect;
import ru.gora.musicbattle.client.ui.TextMeasurer;
import ru.gora.musicbattle.client.ui.TextStyle;
import ru.gora.musicbattle.client.ui.TextStyles;
import ru.gora.musicbattle.client.workshop.WorkshopDraw;
import ru.gora.musicbattle.client.workshop.layout.Rect;
import ru.gora.musicbattle.client.workshop.layout.TextFit;
import java.util.Objects;
import java.util.function.IntConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * A compact color swatch button backed by {@link ExactColorPickerScreen}, drawn like the FX editor's other buttons and the
 * settings' colour buttons: the toolkit's button surface, a rounded swatch with a faint outline at its left, and the
 * colour's "#RRGGBB" beside it in the toolkit's face. It used to be a square slab of the first look with the code in
 * Minecraft's bitmap font, the one control of the inspector still drawn that way.
 */
public final class ExactColorButton extends AbstractButton {
    /** The swatch's inset from the button's edges, the gap from the swatch to the code, and the code's right margin. */
    static final float INSET = 4.0F;
    static final float CODE_GAP = 4.0F;
    static final float RIGHT_PAD = 5.0F;
    private static final String HEX_DIGITS = "0123456789ABCDEF";

    private final Screen parent;
    private final Component pickerTitle;
    private final IntConsumer onApply;
    private int color;
    /** The code's face, worked out again only when the button's size or the GUI scale's glyph tier changes. */
    private TextStyle codeFace;
    private int codeFaceWidth = -1;
    private int codeFaceHeight = -1;
    private int codeFaceTier = -1;

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
        Rect rect = Rect.of(getX(), getY(), getWidth(), getHeight());
        boolean hovered = isHoveredOrFocused();
        if (hovered && active) {
            HandCursor.request(HandCursor.Kind.HAND);
        }
        WorkshopDraw.button(graphics, rect, WorkshopDraw.ButtonStyle.SECONDARY, hovered, active);
        // The swatch keeps a faint outline so that a colour as dark as the button still reads, and fades with the
        // button when it is switched off (a highlight colour while its highlight is off).
        float swatch = swatch(getHeight());
        float swatchY = rect.y() + (rect.h() - swatch) / 2.0F;
        RoundedRect.fill(graphics, rect.x() + INSET, swatchY, swatch, swatch, Palette.RADIUS_SM * 0.75F,
                active ? color : Palette.fade(color, 0.45F));
        RoundedRect.stroke(graphics, rect.x() + INSET, swatchY, swatch, swatch, Palette.RADIUS_SM * 0.75F, 1.0F,
                Palette.withAlpha(Palette.TEXT_1, 0.22F));
        TextStyle face = codeFace();
        float left = rect.x() + INSET + swatch + CODE_GAP;
        float room = codeRoom(getWidth(), getHeight());
        // The inspector never makes this button narrower than the widest code needs in captions (COLOR_MIN); cutting the
        // code is only a guard for a size it was never given.
        String code = TextFit.ellipsize(TextStyles.measurer(), hex(color), face, room);
        TextStyles.queueCentered(graphics, TextStyles.text(code, face).getVisualOrderText(), left + room / 2.0F,
                TextStyles.centeredCapY(face, rect.y(), rect.h()),
                !active ? Palette.TEXT_MUTE : hovered ? Palette.TEXT_1 : Palette.TEXT_2);
    }

    private TextStyle codeFace() {
        int tier = TextStyles.tier();
        if (codeFace == null || codeFaceWidth != getWidth() || codeFaceHeight != getHeight() || codeFaceTier != tier) {
            codeFace = codeFace(TextStyles.measurer(), getWidth(), getHeight());
            codeFaceWidth = getWidth();
            codeFaceHeight = getHeight();
            codeFaceTier = tier;
        }
        return codeFace;
    }

    /** The swatch's side in a button this tall: the height less its inset above and below. */
    static float swatch(int height) {
        return Math.max(4.0F, height - INSET * 2.0F);
    }

    /** The room the code has between the swatch and the right margin of a button this size. */
    static float codeRoom(int width, int height) {
        return width - INSET - swatch(height) - CODE_GAP - RIGHT_PAD;
    }

    /**
     * The code's face in a button this size: body, as the inspector's buttons have from 18 units up, where the widest code
     * there is fits beside the swatch, else captions. It is decided by the widest code rather than the colour shown, so
     * the face does not change while the picker drags the colour.
     */
    static TextStyle codeFace(TextMeasurer measurer, int width, int height) {
        return height >= 18 && TextFit.width(measurer, widestCode(measurer, TextStyle.BODY), TextStyle.BODY)
                <= codeRoom(width, height) ? TextStyle.BODY : TextStyle.CAPTION;
    }

    /** The widest "#RRGGBB" in a face: the face's widest hex digit six times, as Minecraft's font adds up advances. */
    static String widestCode(TextMeasurer measurer, TextStyle face) {
        char widest = HEX_DIGITS.charAt(0);
        for (int index = 1; index < HEX_DIGITS.length(); index++) {
            char digit = HEX_DIGITS.charAt(index);
            if (TextFit.width(measurer, String.valueOf(digit), face) > TextFit.width(measurer, String.valueOf(widest), face)) {
                widest = digit;
            }
        }
        return "#" + String.valueOf(widest).repeat(6);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        defaultButtonNarrationText(output);
    }

    static String hex(int color) {
        return String.format(Locale.ROOT, "#%06X", color & 0x00FFFFFF);
    }
}

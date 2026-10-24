package ru.gora.musicbattle.client.workshop;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;
import ru.gora.musicbattle.client.ui.HandCursor;
import ru.gora.musicbattle.client.ui.Motion;
import ru.gora.musicbattle.client.ui.Palette;
import ru.gora.musicbattle.client.ui.RoundedRect;
import ru.gora.musicbattle.client.ui.TextStyle;
import ru.gora.musicbattle.client.ui.TextStyles;
import ru.gora.musicbattle.client.ui.UiIcon;
import ru.gora.musicbattle.client.ui.UiIcons;

/**
 * A rounded Workshop button drawn with the UI toolkit (not a vanilla widget): label in {@link TextStyle#BODY},
 * optional icon, hover tint animated over {@link Palette#T_HOVER}. The owning screen lays it out, renders it and
 * forwards clicks. Render thread only.
 */
public final class WorkshopButton {
    public enum Kind {
        PRIMARY, SECONDARY, DANGER, QUIET
    }

    public static final float HEIGHT = 20.0F;
    private static final float PAD_X = 10.0F;
    private static final float ICON = 9.0F;
    /** The least side padding a label may be squeezed to before it is cut. */
    private static final float MIN_PAD_X = 4.0F;
    /** Extra width a button asks for beyond its text, so an exact fit never rounds into an ellipsis. */
    private static final float FIT_SLACK = 4.0F;

    private Component label;
    private final Kind kind;
    private final UiIcon icon;
    private final Runnable action;
    private final Motion.Tween hover = new Motion.Tween(0.0);
    private boolean enabled = true;
    private boolean visible = true;
    private float x;
    private float y;
    private float width;
    private float height = HEIGHT;

    public WorkshopButton(Component label, Kind kind, UiIcon icon, Runnable action) {
        this.label = label;
        this.kind = kind;
        this.icon = icon;
        this.action = action;
    }

    public void setLabel(Component value) {
        label = value;
    }

    public void setEnabled(boolean value) {
        enabled = value;
    }

    public void setVisible(boolean value) {
        visible = value;
    }

    public boolean visible() {
        return visible;
    }

    /** Width that fits the label and icon. */
    public float preferredWidth() {
        float text = TextStyles.width(TextStyles.styled(label, TextStyle.BODY));
        // A few units of slack: sized exactly to its text, a button lost the label's last character to the ellipsis
        // at some GUI scales ("I'm Player..." on both side buttons, so the player could not tell 1 from 2).
        return (float) Math.ceil(text + PAD_X * 2 + (icon == null ? 0 : ICON + 4.0F) + FIT_SLACK);
    }

    public void setBounds(float x, float y, float width, float height) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
    }

    public float x() {
        return x;
    }

    public float y() {
        return y;
    }

    public float width() {
        return width;
    }

    public float height() {
        return height;
    }

    public boolean contains(double mouseX, double mouseY) {
        return visible && mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }

    public void render(GuiGraphics g, double mouseX, double mouseY) {
        if (!visible || width <= 0) {
            return;
        }
        boolean over = enabled && contains(mouseX, mouseY);
        if (over) {
            HandCursor.request(HandCursor.Kind.HAND);
        }
        if ((hover.target() > 0.5) != over) {
            hover.animateTo(over ? 1.0 : 0.0, Palette.T_HOVER, Motion.Easing.OUT_QUAD);
        }
        float t = hover.value();
        int base = switch (kind) {
            case PRIMARY -> Palette.ACCENT_STRONG;
            case DANGER -> Palette.mix(Palette.BG_3, Palette.DANGER, 0.55F);
            case SECONDARY -> Palette.BG_3;
            case QUIET -> Palette.withAlpha(Palette.BG_3, 0.0F);
        };
        int hot = switch (kind) {
            case PRIMARY -> Palette.mix(Palette.ACCENT_STRONG, Palette.ACCENT, 0.35F);
            case DANGER -> Palette.mix(Palette.BG_3, Palette.DANGER, 0.8F);
            case SECONDARY, QUIET -> Palette.BG_4;
        };
        int fill = Palette.mix(base, hot, t);
        if (!enabled) {
            fill = Palette.fade(fill, 0.45F);
        }
        RoundedRect.fill(g, x, y, width, height, Palette.RADIUS_MD, fill);
        int textColour = enabled ? (kind == Kind.QUIET ? Palette.mix(Palette.TEXT_2, Palette.TEXT_1, t) : Palette.TEXT_1) : Palette.TEXT_MUTE;
        float iconRoom = icon == null ? 0.0F : ICON + 4.0F;
        // The full label wins over the padding: a label that fits once the side padding is trimmed is drawn whole,
        // and only a label that cannot fit at all is cut. A cut label on a button is a guess the player has to make.
        float room = Math.max(4.0F, width - PAD_X * 2 - iconRoom);
        float tightRoom = Math.max(4.0F, width - MIN_PAD_X * 2 - iconRoom);
        FormattedCharSequence full = TextStyles.styled(label, TextStyle.BODY).getVisualOrderText();
        FormattedCharSequence line = TextStyles.width(full) <= tightRoom ? full
                : TextStyles.ellipsize(TextStyles.styled(label, TextStyle.BODY), room);
        float textWidth = TextStyles.width(line);
        float left = x + (width - textWidth - iconRoom) * 0.5F;
        if (icon != null) {
            UiIcons.draw(g, icon, left, y + (height - ICON) * 0.5F, ICON, textColour);
        }
        TextStyles.queue(g, line, left + iconRoom, TextStyles.centeredCapY(TextStyle.BODY, y, height), textColour);
    }

    /** Runs the action when the click hits an enabled button. */
    public boolean click(double mouseX, double mouseY) {
        if (!enabled || !contains(mouseX, mouseY)) {
            return false;
        }
        activate();
        return true;
    }

    public void activate() {
        if (!enabled || !visible) {
            return;
        }
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
        action.run();
    }
}

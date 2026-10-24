package ru.gora.musicbattle.client.ui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import ru.gora.musicbattle.client.workshop.WorkshopDraw;
import ru.gora.musicbattle.client.workshop.layout.Rect;
import ru.gora.musicbattle.client.workshop.layout.TextFit;

/**
 * How a slider looks, in one place.
 *
 * <p>Seven screens carry their own {@code AbstractSliderButton} subclass, and each drew its own square track and
 * handle in its own shade of teal - the toolkit never had a slider, so everybody wrote one. The behaviour of those
 * seven differs for good reasons (what they clamp, what they format, what they save), so this is the drawing only:
 * a track, the filled part, a handle and the label, in the shape and colours the Workshop uses. A screen keeps its
 * own subclass and calls {@link #paint} from {@code renderWidget}.
 *
 * <p>It is deliberately not a widget of its own. Making it one would mean changing what every screen puts in its
 * {@code renderables} and what the regression harnesses reflect on, for no gain today.
 */
public final class Slider {
    /** How thick the track is, whatever the widget's height. */
    private static final float TRACK = 4.0F;
    /** Half the handle's width. */
    private static final float HANDLE = 2.5F;

    private Slider() {
    }

    /**
     * Draws one slider.
     *
     * @param value 0 to 1, the fraction the handle sits at
     * @param label already formatted by the caller, because each screen words its own value differently
     */
    public static void paint(GuiGraphics graphics, Rect rect, double value, Component label,
                             boolean hovered, boolean active) {
        if (rect.empty()) {
            return;
        }
        if (hovered && active) {
            HandCursor.request(HandCursor.Kind.HAND);
        }
        WorkshopDraw.panel(graphics, rect, Palette.RADIUS_SM, active ? Palette.BG_3 : Palette.fade(Palette.BG_3, 0.5F));
        if (hovered && active) {
            WorkshopDraw.outline(graphics, rect, Palette.RADIUS_SM, 1.0F, Palette.fade(Palette.ACCENT, 0.45F));
        }

        float inset = 5.0F;
        float trackY = rect.y() + (rect.h() - TRACK) / 2.0F;
        Rect track = Rect.of(rect.x() + inset, trackY, Math.max(1.0F, rect.w() - inset * 2), TRACK);
        WorkshopDraw.panel(graphics, track, TRACK / 2.0F, Palette.BG_0);
        float filled = (float) Math.max(0.0D, Math.min(1.0D, value)) * track.w();
        if (filled > 0.5F) {
            WorkshopDraw.panel(graphics, Rect.of(track.x(), track.y(), filled, track.h()), TRACK / 2.0F,
                    active ? Palette.ACCENT_STRONG : Palette.fade(Palette.ACCENT_STRONG, 0.5F));
        }
        float handleX = track.x() + filled;
        WorkshopDraw.panel(graphics,
                Rect.of(handleX - HANDLE, rect.y() + 2, HANDLE * 2, Math.max(2.0F, rect.h() - 4)),
                HANDLE, active ? Palette.TEXT_1 : Palette.TEXT_MUTE);

        // The label sits over the track rather than beside it, the way the Workshop's own rows read.
        String text = TextFit.ellipsize(TextStyles.measurer(), label.getString(), TextStyle.CAPTION,
                Math.max(4.0F, rect.w() - 12));
        TextStyles.queueCentered(graphics, TextStyles.text(text, TextStyle.CAPTION).getVisualOrderText(),
                rect.centerX(), rect.y() + (rect.h() - TextStyle.CAPTION.capHeight()) / 2.0F,
                active ? Palette.TEXT_1 : Palette.TEXT_MUTE);
    }
}

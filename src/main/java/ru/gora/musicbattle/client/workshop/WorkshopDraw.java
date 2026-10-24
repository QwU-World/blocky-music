package ru.gora.musicbattle.client.workshop;

import com.mojang.math.Axis;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import ru.gora.musicbattle.client.ui.HandCursor;
import ru.gora.musicbattle.client.ui.Palette;
import ru.gora.musicbattle.client.ui.Ring;
import ru.gora.musicbattle.client.ui.RoundedRect;
import ru.gora.musicbattle.client.ui.TextStyle;
import ru.gora.musicbattle.client.ui.TextStyles;
import ru.gora.musicbattle.client.ui.UiIcon;
import ru.gora.musicbattle.client.ui.UiIcons;
import ru.gora.musicbattle.client.workshop.layout.Layout;
import ru.gora.musicbattle.client.workshop.layout.Rect;

/**
 * Drawing for the Workshop screens: every helper takes the rectangles the pure layout produced, so what the
 * {@code workshop-screens} check measures is what the game draws. Render thread only.
 */
public final class WorkshopDraw {
    public enum ButtonStyle {
        PRIMARY, SECONDARY, QUIET, DANGER, TAB
    }

    private WorkshopDraw() {
    }

    public static boolean hover(Rect rect, double mouseX, double mouseY) {
        return !rect.empty() && rect.contains(mouseX, mouseY);
    }

    /** Hover test that also asks for the hand cursor. */
    public static boolean hot(Rect rect, double mouseX, double mouseY) {
        boolean over = hover(rect, mouseX, mouseY);
        if (over) {
            HandCursor.request(HandCursor.Kind.HAND);
        }
        return over;
    }

    public static void panel(GuiGraphics g, Rect rect, float radius, int colour) {
        RoundedRect.fill(g, rect.x(), rect.y(), rect.w(), rect.h(), radius, colour);
    }

    public static void outline(GuiGraphics g, Rect rect, float radius, float width, int colour) {
        RoundedRect.stroke(g, rect.x(), rect.y(), rect.w(), rect.h(), radius, width, colour);
    }

    public static void pill(GuiGraphics g, Rect rect, int colour) {
        RoundedRect.pill(g, rect.x(), rect.y(), rect.w(), rect.h(), colour);
    }

    public static void icon(GuiGraphics g, Rect rect, UiIcon icon, int colour) {
        float size = Math.min(rect.w(), rect.h());
        UiIcons.draw(g, icon, rect.x() + (rect.w() - size) * 0.5F, rect.y() + (rect.h() - size) * 0.5F, size, colour);
    }

    /** A button surface; the label is a separate text box of the layout. */
    public static void button(GuiGraphics g, Rect rect, ButtonStyle style, boolean hovered, boolean enabled) {
        if (rect.empty()) {
            return;
        }
        int base = switch (style) {
            case PRIMARY -> Palette.ACCENT_STRONG;
            case DANGER -> Palette.mix(Palette.BG_3, Palette.DANGER, 0.55F);
            case SECONDARY -> Palette.BG_3;
            case TAB -> Palette.withAlpha(Palette.BG_3, 0F);
            case QUIET -> Palette.withAlpha(Palette.BG_3, 0F);
        };
        int hot = switch (style) {
            case PRIMARY -> Palette.mix(Palette.ACCENT_STRONG, Palette.ACCENT, 0.35F);
            case DANGER -> Palette.mix(Palette.BG_3, Palette.DANGER, 0.8F);
            default -> Palette.BG_4;
        };
        int fill = hovered && enabled ? hot : base;
        if (!enabled) {
            fill = Palette.fade(fill, 0.45F);
        }
        RoundedRect.fill(g, rect.x(), rect.y(), rect.w(), rect.h(), Palette.RADIUS_SM, fill);
    }

    /** Text of a laid-out box (already cut to width). */
    public static void text(GuiGraphics g, Layout.Box box, int colour) {
        if (box == null || box.text() == null || box.text().isEmpty()) {
            return;
        }
        TextStyle style = box.style() == null ? TextStyle.BODY : box.style();
        TextStyles.queue(g, TextStyles.text(box.text(), style).getVisualOrderText(), box.rect().x(), box.rect().y(), colour);
    }

    public static void text(GuiGraphics g, Layout layout, String id, int colour) {
        text(g, layout.box(id), colour);
    }

    /** Every text box of a wrapped paragraph ({@code id + ".0"}, …). */
    public static void paragraph(GuiGraphics g, Layout layout, String id, int colour) {
        for (Layout.Box box : layout.group(id + ".")) {
            text(g, box, colour);
        }
    }

    public static void bar(GuiGraphics g, Rect rect, float fraction, int track, int fill) {
        RoundedRect.pill(g, rect.x(), rect.y(), rect.w(), rect.h(), track);
        float clamped = Math.max(0F, Math.min(1F, fraction));
        if (clamped > 0) {
            RoundedRect.pill(g, rect.x(), rect.y(), Math.max(rect.h(), rect.w() * clamped), rect.h(), fill);
        }
    }

    /** A checkbox or radio mark. */
    public static void check(GuiGraphics g, Rect rect, boolean on, boolean radio, boolean enabled) {
        int border = enabled ? Palette.LINE : Palette.fade(Palette.LINE, 0.5F);
        if (radio) {
            RoundedRect.circle(g, rect.centerX(), rect.centerY(), rect.w() * 0.5F, Palette.BG_3);
            RoundedRect.stroke(g, rect.x(), rect.y(), rect.w(), rect.h(), rect.w() * 0.5F, 1F, border);
            if (on) {
                RoundedRect.circle(g, rect.centerX(), rect.centerY(), rect.w() * 0.27F, Palette.ACCENT);
            }
            return;
        }
        RoundedRect.fill(g, rect.x(), rect.y(), rect.w(), rect.h(), Palette.RADIUS_SM * 0.7F, on ? Palette.ACCENT_STRONG : Palette.BG_3);
        RoundedRect.stroke(g, rect.x(), rect.y(), rect.w(), rect.h(), Palette.RADIUS_SM * 0.7F, 1F, on ? Palette.ACCENT : border);
        if (on) {
            icon(g, rect.inset(1.5F, 1.5F), UiIcon.CHECK, Palette.TEXT_1);
        }
    }

    /** The turning ring the Workshop uses for "loading" (no spinner icon in the atlas). */
    public static void spinner(GuiGraphics g, float centerX, float centerY, float radius, int colour) {
        double seconds = Util.getNanos() / 1_000_000_000.0;
        g.pose().pushPose();
        g.pose().translate(centerX, centerY, 0.0F);
        g.pose().mulPose(Axis.ZP.rotationDegrees((float) (seconds * 360.0 % 360.0)));
        Ring.draw(g, 0.0F, 0.0F, radius, 0.3F, 0.3F, colour);
        g.pose().popPose();
    }

    /** A stop square (the icon atlas has play and pause only). */
    public static void stopSquare(GuiGraphics g, float centerX, float centerY, float size, int colour) {
        RoundedRect.fill(g, centerX - size * 0.5F, centerY - size * 0.5F, size, size, 1.2F, colour);
    }

    /** The card-size toggle squares. */
    public static void cardSizeIcon(GuiGraphics g, Rect rect, boolean extra, int colour) {
        float pad = 3F;
        Rect inner = rect.inset(pad, pad);
        if (extra) {
            float half = (inner.w() - 1) * 0.5F;
            RoundedRect.fill(g, inner.x(), inner.y(), half, inner.h(), 0.8F, colour);
            RoundedRect.fill(g, inner.x() + half + 1, inner.y(), half, inner.h(), 0.8F, colour);
        } else {
            float rowHeight = (inner.h() - 2) / 3F;
            for (int i = 0; i < 3; i++) {
                RoundedRect.fill(g, inner.x(), inner.y() + i * (rowHeight + 1), inner.w(), rowHeight, 0.8F, colour);
            }
        }
    }

    /** A vertical scrollbar for a viewport; returns the thumb rectangle (empty when nothing scrolls). */
    public static Rect scrollbar(GuiGraphics g, Rect viewport, double scroll, float contentHeight, boolean dragging) {
        float extent = Math.max(0F, contentHeight - viewport.h());
        if (extent <= 0.5F) {
            return Rect.EMPTY;
        }
        float trackX = viewport.right() - 3F;
        RoundedRect.pill(g, trackX, viewport.y(), 3F, viewport.h(), Palette.withAlpha(Palette.BG_2, 0.85F));
        float thumbHeight = Math.max(14F, viewport.h() * viewport.h() / Math.max(1F, contentHeight));
        float thumbY = viewport.y() + (viewport.h() - thumbHeight) * (float) Math.max(0, Math.min(1, scroll / extent));
        RoundedRect.pill(g, trackX, thumbY, 3F, thumbHeight, dragging ? Palette.ACCENT : Palette.LINE);
        return new Rect(trackX, thumbY, 3F, thumbHeight);
    }

    /** Status pill colour for a workshop status code. */
    public static int statusColour(String status) {
        return switch (status == null ? "" : status) {
            case "featured" -> Palette.ST_FEATURED;
            case "community" -> Palette.ST_COMMUNITY;
            case "wip" -> Palette.ST_WIP;
            case "pending" -> Palette.ST_PENDING;
            default -> Palette.ST_HIDDEN;
        };
    }

    public static int badgeColour(String badgeKey) {
        return switch (badgeKey) {
            case "library" -> Palette.BADGE_LIBRARY;
            case "explicit" -> Palette.BADGE_EXPLICIT;
            case "ai" -> Palette.BADGE_AI;
            case "update" -> Palette.WARN;
            case "withdrawn", "audio_removed" -> Palette.DANGER;
            default -> Palette.TEXT_3;
        };
    }

    /** Tone colours of form notes. */
    public static int toneColour(String tone) {
        return switch (tone == null ? "" : tone) {
            case "warn" -> Palette.WARN;
            case "danger" -> Palette.DANGER;
            case "ok" -> Palette.OK;
            default -> Palette.TEXT_3;
        };
    }
}

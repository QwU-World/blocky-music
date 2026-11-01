package ru.gora.musicbattle.client.ui;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.layouts.LayoutElement;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.screens.inventory.tooltip.BelowOrAboveWidgetTooltipPositioner;
import net.minecraft.client.gui.screens.inventory.tooltip.MenuTooltipPositioner;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipPositioner;
import net.minecraft.network.chat.Component;
import ru.gora.musicbattle.client.workshop.layout.Metrics;
import ru.gora.musicbattle.client.workshop.layout.Rect;
import ru.gora.musicbattle.client.workshop.layout.TextFit;

/**
 * The toolkit's tooltip: a raised card in the inputs' shade with a hairline border and a soft shadow, the text in
 * captions, beside the pointer (or centred under a widget the keyboard reached) and always inside the window. The
 * calibration screen drew its help this way first, in place of the vanilla tooltip with its purple frame and bitmap
 * font; this is that panel, for every screen.
 *
 * <p>{@link #place} and {@link #placeUnder} are pure, so the checks measure the very panel that is drawn.
 * {@link Deferred} lets a screen keep a vanilla {@code Tooltip} on a widget it cannot subclass, such as a FancyButton -
 * the widget still decides when the tip shows (under the pointer, or on keyboard focus) and still has the hint read out
 * by the narrator - while the screen draws it in this look.
 */
public final class UiTooltip {
    /** The widest a tooltip gets, and how far it stays from the window's edges. */
    public static final float MAX_WIDTH = 230F;
    public static final float MARGIN = 4F;
    /** Around the text inside the card. */
    public static final float PAD_X = 8F;
    public static final float PAD_Y = 6F;
    /** From the pointer to the card's corner, and above the pointer when the card does not fit below it. */
    public static final float OFFSET_X = 10F;
    public static final float OFFSET_Y = 12F;
    public static final float ABOVE_GAP = 6F;
    /** Between a widget the keyboard reached and its card, under it or over it, as vanilla keeps its own tip off it. */
    public static final float WIDGET_GAP = 4F;
    /** Between a control the pointer is on and its card, which keeps clear of the control (vanilla's tip does too). */
    public static final float HOVER_GAP = 2F;

    private UiTooltip() {
    }

    /** A card: its box, its lines in captions and their spacing. */
    public record Placement(Rect panel, List<String> lines, float lineHeight) {
        public boolean empty() {
            return lines.isEmpty();
        }
    }

    /**
     * Where the card for {@code text} goes for a pointer at ({@code anchorX}, {@code anchorY}) in a window of this size:
     * below and right of the pointer, moved left to stay inside the window, and above the pointer when it would run
     * past the bottom.
     */
    public static Placement place(TextMeasurer measurer, String text, double anchorX, double anchorY, int width,
                                  int height) {
        Placement card = card(measurer, text, width);
        if (card.empty()) {
            return card;
        }
        Rect panel = card.panel();
        float x = (float) Math.max(MARGIN, Math.min(width - MARGIN - panel.w(), anchorX + OFFSET_X));
        float y = (float) (anchorY + OFFSET_Y);
        if (y + panel.h() > height - MARGIN) {
            y = (float) Math.max(MARGIN, anchorY - panel.h() - ABOVE_GAP);
        }
        return new Placement(new Rect(x, y, panel.w(), panel.h()), card.lines(), card.lineHeight());
    }

    /**
     * Where the card for {@code text} goes for a pointer resting on a control: as {@link #place}, but never over the
     * control itself. Below the pointer the card starts under the control's bottom; where it would run past the bottom of
     * the window it goes over the control, its bottom {@link #HOVER_GAP} above the control's top. A card that flipped
     * above the pointer used to hang its bottom 6 units above the pointer, so on a 20-unit button with the pointer in its
     * lower half it covered the top of the very button it explained. Only where neither side has room (a control nearly
     * as tall as the window) does it fall back to {@link #place}. A null or empty {@code hovered} is {@link #place}.
     */
    public static Placement place(TextMeasurer measurer, String text, double anchorX, double anchorY, Rect hovered,
                                  int width, int height) {
        Placement plain = place(measurer, text, anchorX, anchorY, width, height);
        if (plain.empty() || hovered == null || hovered.w() <= 0F || hovered.h() <= 0F) {
            return plain;
        }
        Rect panel = plain.panel();
        float below = (float) Math.max(anchorY + OFFSET_Y, hovered.bottom() + HOVER_GAP);
        float above = (float) Math.min(anchorY - panel.h() - ABOVE_GAP, hovered.y() - HOVER_GAP - panel.h());
        float y;
        if (below + panel.h() <= height - MARGIN) {
            y = below;
        } else if (above >= MARGIN) {
            y = above;
        } else {
            return plain;
        }
        return new Placement(new Rect(panel.x(), y, panel.w(), panel.h()), plain.lines(), plain.lineHeight());
    }

    /**
     * As {@link #place(TextMeasurer, String, double, double, Rect, int, int)} for a pointer on a small control inside a
     * bigger one, such as a library card's "×": the card keeps off all of {@code outer} where either side of it has
     * room, and otherwise at least off {@code inner}, the control under the pointer. A null {@code inner} leaves only
     * {@code outer}.
     */
    public static Placement place(TextMeasurer measurer, String text, double anchorX, double anchorY, Rect outer,
                                  Rect inner, int width, int height) {
        Placement placed = place(measurer, text, anchorX, anchorY, outer, width, height);
        if (inner == null || inner.empty() || outer == null || outer.empty() || placed.empty()
                || !placed.panel().overlaps(outer, 0F)) {
            return placed;
        }
        return place(measurer, text, anchorX, anchorY, inner, width, height);
    }

    /**
     * Where the card for {@code text} goes for a widget the keyboard reached, which the pointer is not on: centred
     * under the widget, moved sideways to stay inside the window, and over it when it would run past the bottom, so it
     * reads as the widget's and leaves the widget itself in view. A pointer-style card from the widget's middle started
     * {@link #OFFSET_X} right of it and hung off to the right of a wide button.
     */
    public static Placement placeUnder(TextMeasurer measurer, String text, Rect widget, int width, int height) {
        Placement card = card(measurer, text, width);
        if (card.empty()) {
            return card;
        }
        Rect panel = card.panel();
        float x = Math.max(MARGIN, Math.min(width - MARGIN - panel.w(), widget.centerX() - panel.w() * 0.5F));
        float y = widget.bottom() + WIDGET_GAP;
        if (y + panel.h() > height - MARGIN) {
            y = Math.max(MARGIN, widget.y() - WIDGET_GAP - panel.h());
        }
        return new Placement(new Rect(x, y, panel.w(), panel.h()), card.lines(), card.lineHeight());
    }

    /** The card's lines and size for a window this wide, at the window's corner. */
    private static Placement card(TextMeasurer measurer, String text, int width) {
        float maxWidth = Math.min(MAX_WIDTH, width - MARGIN * 4F);
        List<String> lines = TextFit.wrap(measurer, text, TextStyle.CAPTION, maxWidth - PAD_X * 2F);
        float lineHeight = Metrics.lineHeight(TextStyle.CAPTION);
        if (lines.isEmpty()) {
            return new Placement(Rect.EMPTY, lines, lineHeight);
        }
        float textWidth = 0F;
        for (String line : lines) {
            textWidth = Math.max(textWidth, TextFit.width(measurer, line, TextStyle.CAPTION));
        }
        float panelWidth = textWidth + PAD_X * 2F;
        float panelHeight = (lines.size() - 1) * lineHeight + TextFit.boxHeight(TextStyle.CAPTION) + PAD_Y * 2F;
        return new Placement(new Rect(0F, 0F, panelWidth, panelHeight), lines, lineHeight);
    }

    /** Draws a tooltip for a pointer at ({@code anchorX}, {@code anchorY}); the caller flushes afterwards. */
    public static void draw(GuiGraphics graphics, Component text, double anchorX, double anchorY, int width, int height) {
        draw(graphics, place(TextStyles.measurer(), text.getString(), anchorX, anchorY, width, height));
    }

    /** Draws a tooltip for a pointer resting on {@code hovered}, kept off that control; the caller flushes afterwards. */
    public static void draw(GuiGraphics graphics, Component text, double anchorX, double anchorY, Rect hovered, int width,
                            int height) {
        draw(graphics, place(TextStyles.measurer(), text.getString(), anchorX, anchorY, hovered, width, height));
    }

    public static void draw(GuiGraphics graphics, Placement placement) {
        if (placement.empty()) {
            return;
        }
        Rect panel = placement.panel();
        RoundedRect.shadow(graphics, panel.x(), panel.y() + 2F, panel.w(), panel.h(), Palette.RADIUS_MD, 8F,
                Palette.SHADOW_LIFTED);
        RoundedRect.fill(graphics, panel.x(), panel.y(), panel.w(), panel.h(), Palette.RADIUS_MD, Palette.BG_3);
        RoundedRect.stroke(graphics, panel.x(), panel.y(), panel.w(), panel.h(), Palette.RADIUS_MD, 1F, Palette.LINE);
        float lineY = panel.y() + PAD_Y;
        for (String line : placement.lines()) {
            TextStyles.queue(graphics, TextStyles.text(line, TextStyle.CAPTION).getVisualOrderText(), panel.x() + PAD_X,
                    lineY, Palette.TEXT_1);
            lineY += placement.lineHeight();
        }
    }

    /**
     * Vanilla widget tooltips drawn in the toolkit's look. Make the tooltip with {@link #create}, set it on the widget
     * as usual, let the screen's {@code setTooltipForNextRenderPass(Tooltip, ClientTooltipPositioner, boolean)} hand it
     * to {@link #offer} first, and call {@link #draw} once the screen has rendered, so the card lies over every widget.
     */
    public static final class Deferred {
        private final Map<Tooltip, Component> texts = new IdentityHashMap<>();
        private Component pending;
        private boolean keyboard;
        /** The widget whose tip was offered, from vanilla's positioner, so the pointer's card keeps off it. */
        private Rect widget;

        /** A vanilla tooltip this holder recognises; its text is also what the narrator reads as the widget's hint. */
        public Tooltip create(Component text) {
            Tooltip tooltip = Tooltip.create(text);
            texts.put(tooltip, text);
            return tooltip;
        }

        /**
         * Takes a tooltip a widget asked to show this frame, if this holder made it. Anything else is left to vanilla.
         * A widget the keyboard reached, rather than the pointer, asks with vanilla's below-or-above positioner.
         */
        public boolean offer(Tooltip tooltip, ClientTooltipPositioner positioner) {
            Component text = texts.get(tooltip);
            if (text == null) {
                return false;
            }
            pending = text;
            keyboard = positioner instanceof BelowOrAboveWidgetTooltipPositioner;
            widget = widgetOf(positioner);
            return true;
        }

        /**
         * Where the card for what was offered this frame goes: beside the pointer, or centred under the focused widget
         * ({@link #placeUnder}) when the keyboard brought the tip up, as the calibration screen's help does. The focused
         * widget is looked for down through the containers that hold it. Pure, for ui-leftovers; an empty placement when
         * nothing was offered.
         */
        public Placement placement(TextMeasurer measurer, double mouseX, double mouseY, int width, int height,
                                   GuiEventListener focused) {
            if (pending == null) {
                return new Placement(Rect.EMPTY, List.of(), Metrics.lineHeight(TextStyle.CAPTION));
            }
            LayoutElement widget = keyboard ? focusedWidget(focused) : null;
            if (widget != null) {
                return placeUnder(measurer, pending.getString(),
                        Rect.of(widget.getX(), widget.getY(), widget.getWidth(), widget.getHeight()), width, height);
            }
            return place(measurer, pending.getString(), mouseX, mouseY, this.widget, width, height);
        }

        /** Draws what was offered this frame where {@link #placement} puts it, and forgets it. */
        public void draw(GuiGraphics graphics, double mouseX, double mouseY, int width, int height,
                         GuiEventListener focused) {
            Placement placement = placement(TextStyles.measurer(), mouseX, mouseY, width, height, focused);
            pending = null;
            widget = null;
            if (placement.empty()) {
                return;
            }
            UiTooltip.draw(graphics, placement);
            graphics.flush();
        }

        /**
         * The widget a vanilla positioner was made for: AbstractWidget hands every tooltip a MenuTooltipPositioner (the
         * pointer) or a BelowOrAboveWidgetTooltipPositioner (the keyboard) holding the widget's rectangle. Read by
         * reflection, as the runtime has Mojang's names; null for any other positioner or if the field is ever renamed,
         * and the card is then the plain pointer card.
         */
        private static Rect widgetOf(ClientTooltipPositioner positioner) {
            java.lang.reflect.Field field = positioner == null ? null : RECTANGLE_FIELDS.get(positioner.getClass());
            if (field == null) {
                return null;
            }
            try {
                Object value = field.get(positioner);
                return value instanceof ScreenRectangle rect
                        ? Rect.of(rect.left(), rect.top(), rect.width(), rect.height()) : null;
            } catch (ReflectiveOperationException | RuntimeException unreadable) {
                return null;
            }
        }

        private static final ClassValue<java.lang.reflect.Field> RECTANGLE_FIELDS = new ClassValue<>() {
            @Override
            protected java.lang.reflect.Field computeValue(Class<?> type) {
                if (type != MenuTooltipPositioner.class && type != BelowOrAboveWidgetTooltipPositioner.class) {
                    return null;
                }
                for (java.lang.reflect.Field field : type.getDeclaredFields()) {
                    if (field.getType() == ScreenRectangle.class && !java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                        try {
                            field.setAccessible(true);
                            return field;
                        } catch (RuntimeException closed) {
                            return null;
                        }
                    }
                }
                return null;
            }
        };

        /**
         * The innermost focused element under {@code focused} that has a place on the screen: a screen's getFocused() is
         * its own child, which may be a list or a panel holding the widget whose tip this is.
         */
        private static LayoutElement focusedWidget(GuiEventListener focused) {
            LayoutElement widget = focused instanceof LayoutElement placed ? placed : null;
            GuiEventListener element = focused;
            for (int depth = 0; depth < 16 && element instanceof ContainerEventHandler container
                    && container.getFocused() != null && container.getFocused() != element; depth++) {
                element = container.getFocused();
                if (element instanceof LayoutElement placed) {
                    widget = placed;
                }
            }
            return widget;
        }
    }
}

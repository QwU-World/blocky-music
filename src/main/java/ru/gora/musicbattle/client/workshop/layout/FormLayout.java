package ru.gora.musicbattle.client.workshop.layout;

import java.util.List;
import ru.gora.musicbattle.client.ui.TextMeasurer;
import ru.gora.musicbattle.client.ui.TextStyle;

/**
 * Lays a {@link Form} out in a column: every item gets its boxes ({@code <id>}, {@code <id>.label}, {@code <id>.value},
 * {@code <id>.bar}, …) so the screen draws and hit-tests by id and the layout check measures the same rectangles.
 * Scrolls vertically; the caller clips to its viewport and adds the scroll offset.
 */
public final class FormLayout {
    public static final String PANEL = "form.panel";

    private FormLayout() {
    }

    public static Layout compute(Rect area, TextMeasurer measurer, Tr tr, List<Form.Item> items) {
        Layout layout = new Layout(measurer, tr);
        boolean compact = area.w() < Metrics.COMPACT_WIDTH;
        float pad = compact ? Metrics.PAD_SM : Metrics.PAD;
        float width = area.w();
        float inner = width - pad * 2;
        float buttonH = compact ? Metrics.BUTTON_H_SM : Metrics.BUTTON_H;
        float y = pad;
        layout.add(PANEL, "", Layout.Kind.PANEL, new Rect(0, 0, width, area.h()));

        for (Form.Item item : items) {
            String id = item.id();
            if (item instanceof Form.Heading heading) {
                y += layout.paragraph(id, PANEL, pad, y, inner, heading.text(), TextStyle.STRONG,
                        Metrics.lineHeight(TextStyle.STRONG), 3) + Metrics.GAP_SM;
            } else if (item instanceof Form.Paragraph paragraph) {
                y += layout.paragraph(id, PANEL, pad, y, inner, paragraph.text(), TextStyle.BODY,
                        Metrics.lineHeight(TextStyle.BODY), 30) + Metrics.GAP_SM;
            } else if (item instanceof Form.Note note) {
                y += layout.paragraph(id, PANEL, pad, y, inner, note.text(), TextStyle.CAPTION,
                        Metrics.lineHeight(TextStyle.CAPTION), 20) + Metrics.GAP_SM;
            } else if (item instanceof Form.Field field) {
                if (field.label() != null && !field.label().isEmpty()) {
                    layout.label(id + ".label", PANEL, pad, y, inner, field.label(), TextStyle.CAPTION);
                    y += TextFit.boxHeight(TextStyle.CAPTION) + 2;
                }
                float height = Math.max(buttonH, field.lines() * Metrics.lineHeight(TextStyle.BODY) + 6);
                layout.add(id, PANEL, Layout.Kind.FIELD, new Rect(pad, y, inner, height));
                y += height + Metrics.GAP_SM;
            } else if (item instanceof Form.Toggle toggle) {
                float box = Math.min(Metrics.ICON, buttonH - 4);
                Rect row = new Rect(pad, y, inner, 0);
                layout.add(id, PANEL, Layout.Kind.BUTTON, row.withHeight(box));
                layout.add(id + ".box", id, Layout.Kind.ICON, new Rect(pad, y, box, box));
                float height = layout.paragraph(id + ".label", id, pad + box + 4, y, inner - box - 4, toggle.label(),
                        TextStyle.CAPTION, Metrics.lineHeight(TextStyle.CAPTION), 4);
                float rowHeight = Math.max(box, height);
                layout.resize(id, row.withHeight(rowHeight));
                y += rowHeight + Metrics.GAP_SM;
            } else if (item instanceof Form.Choice choice) {
                float box = Math.min(Metrics.ICON, buttonH - 4);
                Rect row = new Rect(pad, y, inner, 0);
                layout.add(id, PANEL, Layout.Kind.BUTTON, row.withHeight(box));
                layout.add(id + ".box", id, Layout.Kind.ICON, new Rect(pad, y, box, box));
                float height = layout.paragraph(id + ".label", id, pad + box + 4, y, inner - box - 4, choice.label(),
                        TextStyle.BODY, Metrics.lineHeight(TextStyle.BODY), 3);
                if (choice.detail() != null && !choice.detail().isEmpty()) {
                    height += layout.paragraph(id + ".detail", id, pad + box + 4, y + height, inner - box - 4, choice.detail(),
                            TextStyle.CAPTION, Metrics.lineHeight(TextStyle.CAPTION), 3);
                }
                float rowHeight = Math.max(box, height);
                layout.resize(id, row.withHeight(rowHeight));
                y += rowHeight + Metrics.GAP_SM;
            } else if (item instanceof Form.Row row) {
                float labelWidth = Math.min(inner * 0.55F, TextFit.width(measurer, row.label(), TextStyle.CAPTION));
                layout.label(id + ".label", PANEL, pad, y, labelWidth, row.label(), TextStyle.CAPTION);
                layout.labelRight(id + ".value", PANEL, pad + inner, y, Math.max(20F, inner - labelWidth - 6), row.value(),
                        TextStyle.CAPTION);
                y += TextFit.boxHeight(TextStyle.CAPTION) + Metrics.GAP_SM;
            } else if (item instanceof Form.Buttons buttons) {
                y = buttonRow(layout, measurer, id, buttons.buttons(), pad, inner, y, buttonH) + Metrics.GAP_SM;
            } else if (item instanceof Form.Stage stage) {
                Rect row = new Rect(pad, y, inner, buttonH);
                layout.add(id, PANEL, Layout.Kind.PANEL, row);
                layout.add(id + ".icon", id, Layout.Kind.ICON,
                        new Rect(pad, y + (buttonH - Metrics.ICON) * 0.5F, Metrics.ICON, Metrics.ICON));
                float barWidth = Math.min(inner * 0.4F, 90F);
                float labelRoom = inner - Metrics.ICON - 6 - barWidth - 4;
                layout.label(id + ".label", id, pad + Metrics.ICON + 4, ShellLayout.capY(TextStyle.BODY, y, buttonH),
                        Math.max(20F, labelRoom), stage.label(), TextStyle.BODY);
                layout.add(id + ".bar", id, Layout.Kind.BAR,
                        new Rect(pad + inner - barWidth, y + (buttonH - 5) * 0.5F, barWidth, 5));
                y += buttonH;
                if (stage.detail() != null && !stage.detail().isEmpty()) {
                    y += layout.paragraph(id + ".detail", PANEL, pad + Metrics.ICON + 4, y, inner - Metrics.ICON - 4,
                            stage.detail(), TextStyle.CAPTION, Metrics.lineHeight(TextStyle.CAPTION), 3);
                }
                y += Metrics.GAP_SM;
            } else if (item instanceof Form.Code code) {
                float height = TextFit.boxHeight(TextStyle.DISPLAY) + 8;
                layout.add(id, PANEL, Layout.Kind.PANEL, new Rect(pad, y, inner, height));
                layout.labelCentered(id + ".text", id, pad + inner * 0.5F, y + 4, inner - 8, code.text(), TextStyle.DISPLAY);
                y += height + Metrics.GAP_SM;
            } else if (item instanceof Form.Slider slider) {
                layout.label(id + ".label", PANEL, pad, y, inner * 0.6F, slider.label(), TextStyle.CAPTION);
                layout.labelRight(id + ".value", PANEL, pad + inner, y, inner * 0.35F, slider.value(), TextStyle.CAPTION);
                y += TextFit.boxHeight(TextStyle.CAPTION) + 2;
                layout.add(id, PANEL, Layout.Kind.BAR, new Rect(pad, y, inner, 6));
                y += 6 + Metrics.GAP_SM;
            } else if (item instanceof Form.Avatar avatar) {
                float size = compact ? Metrics.AVATAR_SM : Metrics.AVATAR;
                layout.add(id, PANEL, Layout.Kind.IMAGE, new Rect(pad, y, size, size));
                float textX = pad + size + 5;
                layout.label(id + ".name", PANEL, textX, y, inner - size - 5, avatar.name(), TextStyle.TITLE);
                if (avatar.detail() != null && !avatar.detail().isEmpty()) {
                    layout.label(id + ".detail", PANEL, textX, y + TextStyle.TITLE.capHeight() + 3, inner - size - 5,
                            avatar.detail(), TextStyle.CAPTION);
                }
                y += Math.max(size, TextStyle.TITLE.capHeight() + 3 + TextFit.boxHeight(TextStyle.CAPTION)) + Metrics.GAP_SM;
            } else if (item instanceof Form.Profile profile) {
                // A card, not a row: a picture large enough to recognise, the name beside it, and the numbers along
                // the bottom in their own band, so the eye reads person first and figures second.
                float avatar = compact ? Metrics.AVATAR : Metrics.AVATAR * 1.7F;
                float cardPad = compact ? Metrics.PAD_SM + 1 : Metrics.PAD;
                float textX = pad + cardPad + avatar + (compact ? 5 : 7);
                float textW = Math.max(20, inner - cardPad * 2 - avatar - (compact ? 5 : 7));
                float top = y;
                float textY = y + cardPad;
                layout.add(id, PANEL, Layout.Kind.PANEL, new Rect(pad, y, inner, 0));
                layout.add(id + ".avatar", id, Layout.Kind.IMAGE, new Rect(pad + cardPad, y + cardPad, avatar, avatar));
                TextStyle nameStyle = compact ? TextStyle.STRONG : TextStyle.TITLE;
                layout.label(id + ".name", id, textX, textY, textW, profile.name(), nameStyle);
                textY += TextFit.boxHeight(nameStyle) + 1;
                if (profile.handle() != null && !profile.handle().isEmpty()) {
                    layout.label(id + ".handle", id, textX, textY, textW, profile.handle(), TextStyle.CAPTION);
                    textY += TextFit.boxHeight(TextStyle.CAPTION) + 2;
                }
                float chipX = textX;
                float chipH = Metrics.BADGE_H;
                for (int i = 0; i < profile.chips().size(); i++) {
                    String chip = profile.chips().get(i).text();
                    float chipW = TextFit.width(layout.measurer(), chip, TextStyle.PILL) + 8;
                    if (chipX + chipW > textX + textW && chipX > textX) {
                        chipX = textX;
                        textY += chipH + 2;
                    }
                    layout.add(id + ".chip." + i, id, Layout.Kind.PANEL, new Rect(chipX, textY, chipW, chipH));
                    layout.labelCentered(id + ".chip." + i + ".text", id + ".chip." + i, chipX + chipW / 2,
                            textY + (chipH - TextFit.boxHeight(TextStyle.PILL)) / 2 + 1, chipW, chip, TextStyle.PILL);
                    chipX += chipW + 3;
                }
                if (!profile.chips().isEmpty()) {
                    textY += chipH + 2;
                }
                if (profile.since() != null && !profile.since().isEmpty()) {
                    layout.label(id + ".since", id, textX, textY, textW, profile.since(), TextStyle.CAPTION);
                    textY += TextFit.boxHeight(TextStyle.CAPTION);
                }
                float headBottom = Math.max(y + cardPad + avatar, textY) + cardPad;
                if (!profile.stats().isEmpty()) {
                    layout.add(id + ".stats", id, Layout.Kind.PANEL, new Rect(pad, headBottom, inner, 0));
                    int columns = profile.stats().size();
                    float columnW = inner / columns;
                    float statY = headBottom + (compact ? 3 : 4);
                    for (int i = 0; i < columns; i++) {
                        Form.Stat stat = profile.stats().get(i);
                        float centerX = pad + columnW * i + columnW / 2;
                        layout.labelCentered(id + ".stat." + i + ".value", id + ".stats", centerX, statY, columnW - 4,
                                stat.value(), TextStyle.STRONG);
                        layout.labelCentered(id + ".stat." + i + ".label", id + ".stats", centerX,
                                statY + TextFit.boxHeight(TextStyle.STRONG) + 1, columnW - 4, stat.label(), TextStyle.CAPTION);
                    }
                    float statsH = (compact ? 3 : 4) * 2 + TextFit.boxHeight(TextStyle.STRONG) + 1
                            + TextFit.boxHeight(TextStyle.CAPTION);
                    layout.resize(id + ".stats", new Rect(pad, headBottom, inner, statsH));
                    headBottom += statsH;
                }
                layout.resize(id, new Rect(pad, top, inner, headBottom - top));
                y = headBottom + Metrics.GAP;
            } else if (item instanceof Form.Separator) {
                layout.add(id, PANEL, Layout.Kind.PANEL, new Rect(pad, y + 2, inner, 1));
                y += 5;
            }
        }
        layout.setContentHeight(y + pad);
        // The panel is the scrolling column itself, so it is as tall as its content, not as the viewport.
        layout.resize(PANEL, new Rect(0, 0, width, Math.max(area.h(), y + pad)));
        return layout;
    }

    /** Buttons side by side while they fit, otherwise stacked full width. */
    private static float buttonRow(Layout layout, TextMeasurer measurer, String id, List<Form.Button> buttons, float pad,
                                   float inner, float y, float buttonH) {
        float needed = 0;
        for (Form.Button button : buttons) {
            needed += TextFit.width(measurer, button.label(), TextStyle.BODY) + 22 + Metrics.GAP_SM;
        }
        boolean stacked = needed - Metrics.GAP_SM > inner;
        float x = pad;
        for (Form.Button button : buttons) {
            float width = stacked ? inner : Math.min(inner, TextFit.width(measurer, button.label(), TextStyle.BODY) + 22);
            Rect box = new Rect(x, y, width, buttonH);
            layout.add(button.id(), FormLayout.PANEL, Layout.Kind.BUTTON, box);
            layout.labelCentered(button.id() + ".text", button.id(), box.centerX(),
                    ShellLayout.capY(TextStyle.BODY, box.y(), buttonH), width - 8, button.label(), TextStyle.BODY);
            if (stacked) {
                y += buttonH + Metrics.GAP_SM;
            } else {
                x = box.right() + Metrics.GAP_SM;
            }
        }
        return stacked ? y - Metrics.GAP_SM : y + buttonH;
    }
}

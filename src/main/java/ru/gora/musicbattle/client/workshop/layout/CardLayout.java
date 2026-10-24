package ru.gora.musicbattle.client.workshop.layout;

import java.util.List;
import ru.gora.musicbattle.client.ui.TextMeasurer;
import ru.gora.musicbattle.client.ui.TextStyle;

/**
 * One beatmap card (lazer's normal and extra cards, research {@code osu-lazer-ui.md} section 2.2): square thumbnail
 * with the play button and the preview ring, a body that overlaps it by the corner radius, title / artist / mapper
 * lines with badges, the hover statistics row, the info row with the status pill and the difficulty dots, and the
 * button strip on the right that widens on hover. The expanded state adds the chart dropdown under the card.
 */
public final class CardLayout {
    /** What a card shows. Strings are already translated; {@code stars} drives the difficulty dots. */
    public record State(boolean extra, boolean hover, boolean expanded, boolean installedTab, String title, String artist,
                        String mapper, String source, String statusLabel, List<String> badges, List<Double> stars,
                        String stats, String rightBadge, boolean externalAudio, boolean hasFx, int dropdownRows,
                        boolean downloading) {
    }

    private CardLayout() {
    }

    public static Layout compute(Rect card, TextMeasurer measurer, Tr tr, State state) {
        Layout layout = new Layout(measurer, tr);
        layout.add("card", "", Layout.Kind.PANEL, card);
        float height = card.h();
        float radius = 6F;
        Rect thumb = new Rect(card.x(), card.y(), height, height);
        layout.add("card.thumb", "card", Layout.Kind.IMAGE, thumb);
        float bodyX = card.x() + height - radius;
        Rect body = new Rect(bodyX, card.y(), card.right() - bodyX, height);
        layout.add("card.body", "card", Layout.Kind.PANEL, body);

        float stripWidth = state.hover() || state.expanded() ? Metrics.CARD_STRIP_HOVER : Metrics.CARD_STRIP;
        Rect strip = new Rect(body.right() - stripWidth, body.y(), stripWidth, height);
        layout.add("card.strip", "card.body", Layout.Kind.PANEL, strip);
        if (state.hover() || state.expanded()) {
            float half = height * 0.5F;
            layout.add("card.favourite", "card.strip", Layout.Kind.BUTTON, new Rect(strip.x(), strip.y(), stripWidth, half));
            layout.add("card.install", "card.strip", Layout.Kind.BUTTON, new Rect(strip.x(), strip.y() + half, stripWidth, half));
        }

        // Thumbnail overlay: play/stop button and the icon pills of the top-left corner.
        float playSize = Math.min(26F, height * 0.46F);
        layout.add("card.play", "card.thumb", Layout.Kind.BUTTON,
                new Rect(thumb.centerX() - playSize * 0.5F, thumb.centerY() - playSize * 0.5F, playSize, playSize));
        if (state.hasFx()) {
            layout.add("card.fx", "card.thumb", Layout.Kind.ICON, new Rect(thumb.x() + 3, thumb.y() + 3, Metrics.ICON_SM, Metrics.ICON_SM));
        }

        float textX = card.x() + height + 5;
        float textRight = strip.x() - 5;
        float room = Math.max(24F, textRight - textX);
        float y = card.y() + (state.extra() ? 5 : 4);

        float pillHeight = Metrics.PILL_H;
        float infoY = card.bottom() - pillHeight - 4;
        float statsHeight = TextFit.boxHeight(TextStyle.CAPTION);
        float statsY = infoY - statsHeight - 2;

        // The right badge shares the title's line, so the title and its own badges stop short of it; they used to
        // run underneath it, and a long title came out with the badge printed over its last word.
        boolean rightBadge = state.rightBadge() != null && !state.rightBadge().isEmpty();
        float rightBadgeWidth = rightBadge
                ? Math.min(room * 0.7F, TextFit.width(measurer, state.rightBadge(), TextStyle.PILL) + 8) : 0F;
        float titleRight = rightBadge ? textRight - rightBadgeWidth - 3 : textRight;
        float titleRoom = Math.max(16F, titleRight - textX);

        // Title line with the badges right of it.
        float badgeRoom = 0;
        for (String badge : state.badges()) {
            badgeRoom += TextFit.width(measurer, badge, TextStyle.PILL) + 10;
        }
        badgeRoom = Math.min(badgeRoom, titleRoom * 0.5F);
        layout.label("card.title", "card.body", textX, y, Math.max(16F, titleRoom - badgeRoom - 3), state.title(), TextStyle.TITLE);
        float badgeX = layout.rect("card.title").right() + 4;
        int badgeIndex = 0;
        for (String badge : state.badges()) {
            float badgeWidth = TextFit.width(measurer, badge, TextStyle.PILL) + 8;
            if (badgeX + badgeWidth > titleRight) {
                break;
            }
            Rect box = new Rect(badgeX, y - 1, badgeWidth, Metrics.BADGE_H);
            layout.add("card.badge." + badgeIndex, "card.body", Layout.Kind.PILL, box);
            layout.labelCentered("card.badge." + badgeIndex + ".text", "card.badge." + badgeIndex, box.centerX(),
                    ShellLayout.capY(TextStyle.PILL, box.y(), Metrics.BADGE_H), badgeWidth - 4, badge, TextStyle.PILL);
            badgeX = box.right() + 3;
            badgeIndex++;
        }
        y += TextStyle.TITLE.capHeight() + 4;
        layout.label("card.artist", "card.body", textX, y, room, tr.get(WorkshopStrings.KEY + "card.by", state.artist()), TextStyle.BODY);
        y += TextStyle.BODY.capHeight() + 3;
        if (state.extra() && state.source() != null && !state.source().isEmpty()) {
            layout.label("card.source", "card.body", textX, y, room, state.source(), TextStyle.CAPTION);
            y += TextStyle.CAPTION.capHeight() + 3;
        }
        if (y + TextStyle.CAPTION.capHeight() < statsY) {
            layout.label("card.mapper", "card.body", textX, y, room, state.mapper(), TextStyle.CAPTION);
        }

        if (state.downloading()) {
            // While downloading the bottom block gives way to the progress bar (lazer).
            layout.add("card.progress", "card.body", Layout.Kind.BAR, new Rect(textX, infoY + pillHeight * 0.4F, room, 4));
        } else {
            if (state.hover() || state.extra()) {
                layout.label("card.stats", "card.body", textX, statsY, room, state.stats(), TextStyle.CAPTION);
            }
            float pillWidth = Math.min(room * 0.6F, TextFit.width(measurer, state.statusLabel(), TextStyle.PILL) + 8);
            Rect pill = new Rect(textX, infoY, pillWidth, pillHeight);
            layout.add("card.status", "card.body", Layout.Kind.PILL, pill);
            layout.labelCentered("card.status.text", "card.status", pill.centerX(),
                    ShellLayout.capY(TextStyle.PILL, pill.y(), pillHeight), pillWidth - 4, state.statusLabel(), TextStyle.PILL);

            float dotsX = pill.right() + 4;
            float dotsRoom = textRight - dotsX - (state.externalAudio() ? Metrics.ICON_SM + 3 : 0);
            int maxDots = (int) Math.floor(dotsRoom / Metrics.DOT_STEP);
            if (state.stars().size() > 12 || state.stars().size() > maxDots) {
                String count = tr.get(WorkshopStrings.KEY + "card.charts", String.valueOf(state.stars().size()));
                layout.label("card.charts", "card.body", dotsX, ShellLayout.capY(TextStyle.CAPTION, infoY, pillHeight),
                        Math.max(10F, dotsRoom), count, TextStyle.CAPTION);
            } else {
                for (int i = 0; i < state.stars().size(); i++) {
                    layout.add("card.dot." + i, "card.body", Layout.Kind.PILL,
                            new Rect(dotsX + i * Metrics.DOT_STEP, infoY + (pillHeight - 7) * 0.5F, 3.5F, 7F));
                }
            }
            if (state.externalAudio()) {
                layout.add("card.audio", "card.body", Layout.Kind.ICON,
                        new Rect(textRight - Metrics.ICON_SM, infoY + (pillHeight - Metrics.ICON_SM) * 0.5F, Metrics.ICON_SM, Metrics.ICON_SM));
            }
        }

        if (rightBadge) {
            float badgeWidth = rightBadgeWidth;
            Rect box = new Rect(textRight - badgeWidth, card.y() + 3, badgeWidth, Metrics.BADGE_H);
            layout.addFloating("card.right_badge", "card.body", Layout.Kind.PILL, box);
            layout.floatingText("card.right_badge.text", "card.right_badge",
                    new Rect(box.x() + 4, ShellLayout.capY(TextStyle.PILL, box.y(), Metrics.BADGE_H), badgeWidth - 8,
                            TextFit.boxHeight(TextStyle.PILL)), state.rightBadge(), TextStyle.PILL);
        }

        if (state.expanded() && state.dropdownRows() > 0) {
            float rows = Math.min(state.dropdownRows() * Metrics.CARD_DROPDOWN_ROW + 6, Metrics.CARD_DROPDOWN_MAX);
            layout.addFloating("card.dropdown", "", Layout.Kind.PANEL, new Rect(card.x(), card.bottom(), card.w(), rows));
        }
        return layout;
    }

    /** Row rectangle inside the chart dropdown. */
    public static Rect dropdownRow(Rect dropdown, int index) {
        return new Rect(dropdown.x() + 6, dropdown.y() + 3 + index * Metrics.CARD_DROPDOWN_ROW, dropdown.w() - 12,
                Metrics.CARD_DROPDOWN_ROW);
    }
}

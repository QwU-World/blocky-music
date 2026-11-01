package ru.gora.musicbattle.client.workshop.layout;

import java.util.List;
import ru.gora.musicbattle.client.ui.TextMeasurer;
import ru.gora.musicbattle.client.ui.TextStyle;

/**
 * One beatmap card (lazer's normal and extra cards): square thumbnail
 * with the play button and the preview ring, a body that overlaps it by the corner radius, title / artist / mapper
 * lines with badges, the hover statistics row, the info row with the status pill and the difficulty dots, and the
 * button strip on the right that widens on hover, into room the words always leave for it. The expanded state adds the
 * chart dropdown under the card.
 */
public final class CardLayout {
    /** What separates the stats line's parts (downloads, favourites, date); a part is dropped whole, never cut. */
    public static final String STATS_SEPARATOR = WorkshopStrings.STATS_SEPARATOR;
    /** How many letters of a title its badges leave it at the least ({@link #titleMin}). */
    public static final int TITLE_MIN_LETTERS = 8;

    /** What a card shows. Strings are already translated; {@code stars} drives the difficulty dots. */
    public record State(boolean extra, boolean hover, boolean expanded, boolean installedTab, String title, String artist,
                        String mapper, String source, String statusLabel, List<String> badges, List<Double> stars,
                        String stats, String rightBadge, boolean externalAudio, boolean hasFx, int dropdownRows,
                        boolean downloading) {
    }

    private CardLayout() {
    }

    /**
     * How wide the run after a title is with its first {@code shown} badges, and the "+n" chip for the rest if any are
     * left: 4 units after the title, then each pill with 3 units between them.
     */
    public static float badgesRun(TextMeasurer measurer, List<String> badges, int shown) {
        float run = 0F;
        int pills = 0;
        for (int index = 0; index < shown; index++) {
            run += TextFit.width(measurer, badges.get(index), TextStyle.PILL) + 8;
            pills++;
        }
        if (shown < badges.size()) {
            run += TextFit.width(measurer, "+" + (badges.size() - shown), TextStyle.PILL) + 8;
            pills++;
        }
        return pills == 0 ? 0F : run + 4 + 3 * (pills - 1);
    }

    /**
     * The room a card's title keeps however many badges follow it: its first eight letters and the ellipsis, enough
     * to tell one beatmap from another in a row of cards, or the whole title when that is narrower. The two are
     * compared by width, not by the number of letters: a title the minimum would show whole ("Gurenge", or "Lagtrain!"
     * whose ninth letter is narrower than the ellipsis) has no ellipsis to make room for. Reserving one anyway sent a
     * badge that fits beside "Gurenge" into the "+1" chip (LIBRARY at 504x360, БИБЛИОТЕКА beside "Lagtrain" at 510x360).
     */
    public static float titleMin(TextMeasurer measurer, String title) {
        if (title == null || title.isEmpty()) {
            return 0F;
        }
        String eight = title.substring(0, title.offsetByCodePoints(0, Math.min(TITLE_MIN_LETTERS, title.codePointCount(0, title.length()))));
        return Math.min(TextFit.width(measurer, title, TextStyle.TITLE),
                TextFit.width(measurer, eight, TextStyle.TITLE) + TextFit.width(measurer, TextFit.ELLIPSIS, TextStyle.TITLE));
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
        // The words end where the widened strip begins, hovered or not, so a hover adds the strip's buttons and the
        // stats line and moves nothing else. They used to end at the strip's current edge: on hover the title's
        // ellipsis moved 11 units left, and the last badge after it (LIBRARY / БИБЛИОТЕКА) appeared or disappeared.
        float textRight = body.right() - Metrics.CARD_STRIP_HOVER - 5;
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

        // Title line with the badges right of it. The title gives way to its badges, down to titleMin: each badge says
        // something the title cannot (explicit, made with AI, from the library), and a title is still recognised by
        // its first eight letters. Badges that do not fit beside a title that short are counted in a "+2" chip after
        // the ones that do, as the difficulty dots count theirs. The badges used to take at most half of the line, so
        // a third badge that did not fit in that half was left out without a sign (БИБЛИОТЕКА at 640x360 in Russian)
        // while the title kept its half.
        List<String> badges = state.badges();
        float titleMinRoom = Math.min(titleRoom, titleMin(measurer, state.title()));
        int fit = badges.size();
        while (fit > 0 && titleMinRoom + badgesRun(measurer, badges, fit) > titleRoom + 0.01F) {
            fit--;
        }
        if (fit == 0 && titleMinRoom + badgesRun(measurer, badges, 0) > titleRoom + 0.01F) {
            // Not even the chip fits beside the minimum, so the title gives way for it below. Badges that take no more
            // room than the chip would are shown instead of it: the title loses no more letters, and "AI" says more
            // than "+1" in its place ("Exact… +1" on a narrow card with a long right badge).
            float chip = badgesRun(measurer, badges, 0);
            for (int shown = badges.size(); shown > 0; shown--) {
                if (badgesRun(measurer, badges, shown) <= chip + 0.01F) {
                    fit = shown;
                    break;
                }
            }
        }
        float run = badgesRun(measurer, badges, fit);
        // The title takes the rest of the line, which the loop above left at titleMin or more. Only when not even the
        // chip fits beside that minimum (a narrow card whose right badge is long) does the title give way for the chip,
        // or for the badges no wider than it, down to sixteen units: a count of badges says more than three more letters.
        float titleWidth = Math.max(16F, titleRoom - run);
        layout.label("card.title", "card.body", textX, y, titleWidth, state.title(), TextStyle.TITLE);
        float badgeX = layout.rect("card.title").right() + 4;
        for (int index = 0; index <= fit && index < badges.size(); index++) {
            boolean more = index == fit;
            String badge = more ? "+" + (badges.size() - fit) : badges.get(index);
            String id = more ? "card.badge.more" : "card.badge." + index;
            float badgeWidth = TextFit.width(measurer, badge, TextStyle.PILL) + 8;
            // A hair of float rounding is no reason to drop a badge the title gave way for.
            if (badgeX + badgeWidth > titleRight + 0.01F) {
                break;
            }
            Rect box = new Rect(badgeX, y - 1, badgeWidth, Metrics.BADGE_H);
            layout.add(id, "card.body", Layout.Kind.PILL, box);
            layout.labelCentered(id + ".text", id, box.centerX(),
                    ShellLayout.capY(TextStyle.PILL, box.y(), Metrics.BADGE_H), badgeWidth - 4, badge, TextStyle.PILL);
            badgeX = box.right() + 3;
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
                // Downloads, favourites and the date, as many of them whole as the line holds: the line was cut
                // mid-word ("5 days a…") on every card narrower than its words.
                layout.label("card.stats", "card.body", textX, statsY, room,
                        TextFit.wholeParts(measurer, state.stats(), STATS_SEPARATOR, TextStyle.CAPTION, room), TextStyle.CAPTION);
            }
            // The status pill may take seven tenths of the line, the dots giving way to a count after it: the narrowest
            // extra card has 135 units, and «РЕКОМЕНДУЕМАЯ» needs 90 of them at GUI scale 1.
            float pillWidth = Math.min(room * 0.7F, TextFit.width(measurer, state.statusLabel(), TextStyle.PILL) + 8);
            Rect pill = new Rect(textX, infoY, pillWidth, pillHeight);
            layout.add("card.status", "card.body", Layout.Kind.PILL, pill);
            layout.labelCentered("card.status.text", "card.status", pill.centerX(),
                    ShellLayout.capY(TextStyle.PILL, pill.y(), pillHeight), pillWidth - 4, state.statusLabel(), TextStyle.PILL);

            float dotsX = pill.right() + 4;
            float dotsRoom = textRight - dotsX - (state.externalAudio() ? Metrics.ICON_SM + 3 : 0);
            int maxDots = (int) Math.floor(dotsRoom / Metrics.DOT_STEP);
            int charts = state.stars().size();
            String count = charts > 12 || charts > maxDots
                    ? tr.get(WorkshopStrings.KEY + "card.charts", String.valueOf(charts)) : null;
            if (count != null && TextFit.fits(measurer, count, TextStyle.CAPTION, dotsRoom)) {
                layout.label("card.charts", "card.body", dotsX, ShellLayout.capY(TextStyle.CAPTION, infoY, pillHeight),
                        dotsRoom, count, TextStyle.CAPTION);
            } else {
                // Without room for "14 difficulties" the first dots are drawn and a count of the rest follows them,
                // "+9", rather than the words cut to "14 diffi…".
                int shown = Math.min(charts, maxDots);
                String more = "";
                while (shown < charts && shown > 0) {
                    more = "+" + (charts - shown);
                    if (shown * Metrics.DOT_STEP + 1 + TextFit.width(measurer, more, TextStyle.CAPTION) <= dotsRoom) {
                        break;
                    }
                    shown--;
                }
                if (shown < charts) {
                    more = "+" + (charts - shown);
                }
                for (int i = 0; i < shown; i++) {
                    layout.add("card.dot." + i, "card.body", Layout.Kind.PILL,
                            new Rect(dotsX + i * Metrics.DOT_STEP, infoY + (pillHeight - 7) * 0.5F, 3.5F, 7F));
                }
                if (!more.isEmpty()) {
                    layout.label("card.charts", "card.body", dotsX + shown * Metrics.DOT_STEP + (shown > 0 ? 1 : 0),
                            ShellLayout.capY(TextStyle.CAPTION, infoY, pillHeight),
                            Math.max(0F, dotsRoom - shown * Metrics.DOT_STEP - (shown > 0 ? 1 : 0)), more, TextStyle.CAPTION);
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

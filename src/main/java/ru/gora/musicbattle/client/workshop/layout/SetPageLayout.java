package ru.gora.musicbattle.client.workshop.layout;

import java.util.List;
import ru.gora.musicbattle.client.ui.TextMeasurer;
import ru.gora.musicbattle.client.ui.TextStyle;

/**
 * The set page (spec 06 section 6.2, lazer's {@code BeatmapSetOverlay}): a header over the cover with the chart
 * tiles, title, artist, mapper card, availability banner and the action buttons; a right column with the status pill,
 * the preview bar, basic stats, attribute bars and the user-rating box; then the info section with description,
 * metadata and the charts table. Laid out in the scroll container's coordinates; comments follow underneath.
 *
 * <p>Below {@link Metrics#TWO_COLUMN_WIDTH} the right column moves under the header and the info columns stack.
 */
public final class SetPageLayout {
    public static final String HEADER = "set.header";
    public static final String RIGHT = "set.right";
    public static final String INFO = "set.info";

    public record ChartRow(String key, String name, double stars, String notes, String holds, String effects, String length) {
    }

    public record Stat(String label, String value) {
    }

    public record Attribute(String label, String value, float fraction) {
    }

    /** A badge over the title. The key ("explicit", "ai", "library") is what the cards colour theirs by. */
    public record Badge(String key, String label) {
    }

    /**
     * Everything the page shows; the screen prepares the strings so the layout stays pure.
     *
     * <p>{@code canInstall}, {@code cannotRate} and {@code descriptionSpoiler} carry the site's own answers: a set it
     * will not serve, a rating it will not take with the reason it gave, and a description it flagged for spoilers.
     */
    public record State(String title, String artist, List<Badge> badges, String statusLabel, String chartName,
                        List<Double> chartStars, String mapper, String submitted, String updated, String counters,
                        String availability, String installLabel, String installSub, boolean canInstall, boolean favourited,
                        boolean canFavourite, boolean installing, float installProgress, String description,
                        boolean descriptionSpoiler, String credits, String featuredNote,
                        List<Stat> metadata, List<Stat> basicStats, List<Attribute> attributes, String ratingAverage,
                        List<Integer> ratingHistogram, String ratingNegative, String ratingPositive, Integer myRating,
                        boolean canRate, String cannotRate, String audioLine, List<ChartRow> charts, boolean local,
                        boolean previewPlaying, int selectedChart) {
    }

    private SetPageLayout() {
    }

    /** Moves the boxes laid out in {@code [from, to)} down by {@code dy}. */
    private static void sink(Layout layout, int from, int to, float dy) {
        List<Layout.Box> boxes = List.copyOf(layout.boxes().subList(from, to));
        for (Layout.Box box : boxes) {
            Rect rect = box.rect();
            layout.resize(box.id(), new Rect(rect.x(), rect.y() + dy, rect.w(), rect.h()));
        }
    }

    public static Layout compute(Rect content, TextMeasurer measurer, Tr tr, State state) {
        Layout layout = new Layout(measurer, tr);
        float width = content.w();
        boolean compact = width < Metrics.COMPACT_WIDTH;
        boolean twoColumns = width >= Metrics.TWO_COLUMN_WIDTH;
        float pad = compact ? Metrics.PAD_SM : Metrics.PAD;
        float buttonH = compact ? Metrics.BUTTON_H_SM : Metrics.BUTTON_H;
        float rightWidth = twoColumns ? Math.min(200F, Math.max(150F, width * 0.34F)) : width;
        float leftWidth = twoColumns ? width - rightWidth - Metrics.GAP : width;
        float y = 0;

        // ── header ─────────────────────────────────────────────────────────────────────────────────────────
        float headerTop = y;
        layout.add(HEADER, "", Layout.Kind.PANEL, new Rect(0, headerTop, width, buttonH));
        int headerFirst = layout.boxes().size();
        float left = pad;
        float leftInner = leftWidth - pad * 2;
        float hy = y + pad;

        float tile = compact ? 20F : 26F;
        float tileX = left;
        for (int i = 0; i < state.chartStars().size(); i++) {
            if (tileX + tile > left + leftInner) {
                tileX = left;
                hy += tile + 2;
            }
            layout.add("set.tile." + i, HEADER, Layout.Kind.BUTTON, new Rect(tileX, hy, tile, tile));
            tileX += tile + 2;
        }
        if (!state.chartStars().isEmpty()) {
            hy += tile + 3;
        }
        layout.label("set.chartname", HEADER, left, hy, leftInner, state.chartName(), TextStyle.STRONG);
        hy += TextStyle.STRONG.capHeight() + 3;
        layout.label("set.counters", HEADER, left, hy, leftInner, state.counters(), TextStyle.CAPTION);
        hy += TextStyle.CAPTION.capHeight() + 5;

        float titleHeight = layout.paragraph("set.title", HEADER, left, hy, leftInner, state.title(), TextStyle.DISPLAY,
                Metrics.lineHeight(TextStyle.DISPLAY), 2);
        hy += titleHeight + 2;
        float badgeX = left;
        for (int i = 0; i < state.badges().size(); i++) {
            String badge = state.badges().get(i).label();
            float badgeWidth = Math.min(leftInner, TextFit.width(measurer, badge, TextStyle.PILL) + 8);
            if (badgeX + badgeWidth > left + leftInner) {
                break;
            }
            Rect box = new Rect(badgeX, hy, badgeWidth, Metrics.BADGE_H);
            layout.add("set.badge." + i, HEADER, Layout.Kind.PILL, box);
            layout.labelCentered("set.badge." + i + ".text", "set.badge." + i, box.centerX(),
                    ShellLayout.capY(TextStyle.PILL, box.y(), Metrics.BADGE_H), badgeWidth - 4, badge, TextStyle.PILL);
            badgeX = box.right() + 3;
        }
        if (!state.badges().isEmpty()) {
            hy += Metrics.BADGE_H + 3;
        }
        layout.label("set.artist", HEADER, left, hy, leftInner, state.artist(), TextStyle.TITLE);
        hy += TextStyle.TITLE.capHeight() + 6;

        float avatar = compact ? 18F : Metrics.AVATAR;
        layout.add("set.avatar", HEADER, Layout.Kind.IMAGE, new Rect(left, hy, avatar, avatar));
        float mapperX = left + avatar + 5;
        float mapperRoom = Math.max(30F, leftInner - avatar - 5);
        layout.label("set.mapper", HEADER, mapperX, hy, mapperRoom, state.mapper(), TextStyle.CAPTION);
        layout.label("set.submitted", HEADER, mapperX, hy + TextStyle.CAPTION.capHeight() + 3, mapperRoom, state.submitted(),
                TextStyle.CAPTION);
        float mapperBottom = hy + Math.max(avatar, (TextStyle.CAPTION.capHeight() + 3) * 2 + TextStyle.CAPTION.capHeight());
        if (state.updated() != null && !state.updated().isEmpty()) {
            layout.label("set.updated", HEADER, mapperX, hy + (TextStyle.CAPTION.capHeight() + 3) * 2, mapperRoom, state.updated(),
                    TextStyle.CAPTION);
        }
        hy = mapperBottom + 4;

        if (state.availability() != null && !state.availability().isEmpty()) {
            float bannerTop = hy;
            layout.add("set.banner", HEADER, Layout.Kind.PANEL, new Rect(left, bannerTop, leftInner, buttonH));
            float bannerHeight = layout.paragraph("set.banner.text", "set.banner", left + 4, bannerTop + 3, leftInner - 8,
                    state.availability(), TextStyle.CAPTION, Metrics.lineHeight(TextStyle.CAPTION), 3);
            layout.resize("set.banner", new Rect(left, bannerTop, leftInner, bannerHeight + 6));
            hy = bannerTop + bannerHeight + 6 + 4;
        }

        // Buttons: favourite, install, open in browser, more. A primary button with a second line (the picker's
        // "Choose this map" over the difficulty) is as tall as both lines need; a fixed height cut the second one off
        // at small windows and at GUI scale 3.
        boolean installTwoLines = state.installSub() != null && !state.installSub().isEmpty();
        float actionH = Math.max(buttonH, compact ? 18F : 22F);
        if (installTwoLines) {
            actionH = Math.max(actionH, (float) Math.ceil(3 + TextStyle.BODY.capHeight() + 2
                    + TextFit.boxHeight(TextStyle.CAPTION) + 3));
        }
        float square = actionH;
        float bx = left;
        layout.add("set.favourite", HEADER, Layout.Kind.BUTTON, new Rect(bx, hy, square, actionH));
        bx += square + Metrics.GAP_SM;
        float installWidth = Math.min(Math.max(70F, leftInner * 0.45F), leftInner - (square + Metrics.GAP_SM) * 3);
        Rect install = new Rect(bx, hy, Math.max(50F, installWidth), actionH);
        layout.add("set.install", HEADER, Layout.Kind.BUTTON, install);
        float installTextY = state.installSub() == null || state.installSub().isEmpty()
                ? ShellLayout.capY(TextStyle.BODY, install.y(), actionH)
                : install.y() + 3;
        layout.label("set.install.text", "set.install", install.x() + 5, installTextY, install.w() - 10, state.installLabel(),
                TextStyle.BODY);
        if (state.installSub() != null && !state.installSub().isEmpty()) {
            layout.label("set.install.sub", "set.install", install.x() + 5, install.y() + 3 + TextStyle.BODY.capHeight() + 2,
                    install.w() - 10, state.installSub(), TextStyle.CAPTION);
        }
        if (state.installing()) {
            layout.add("set.install.bar", "set.install", Layout.Kind.BAR,
                    new Rect(install.x() + 1, install.bottom() - 3, install.w() - 2, 2));
        }
        bx = install.right() + Metrics.GAP_SM;
        layout.add("set.browser", HEADER, Layout.Kind.BUTTON, new Rect(bx, hy, square, actionH));
        bx += square + Metrics.GAP_SM;
        layout.add("set.more", HEADER, Layout.Kind.BUTTON, new Rect(bx, hy, square, actionH));
        hy += actionH + pad;
        float headerHeight = hy - headerTop;
        int headerEnd = layout.boxes().size();

        // ── right column ───────────────────────────────────────────────────────────────────────────────────
        float rightX = twoColumns ? leftWidth + Metrics.GAP : 0;
        float rightTop = twoColumns ? headerTop + pad : hy;
        float ry = rightTop;
        float rightInner = rightWidth - (twoColumns ? pad : pad * 2);
        float rightLeft = rightX + (twoColumns ? 0 : pad);
        layout.add(RIGHT, "", Layout.Kind.PANEL, new Rect(rightX, rightTop, rightWidth, buttonH));

        float pillWidth = Math.min(rightInner, TextFit.width(measurer, state.statusLabel(), TextStyle.PILL) + 24);
        Rect statusPill = new Rect(rightLeft + rightInner - pillWidth, ry, pillWidth, Metrics.PILL_H + 3);
        layout.add("set.status", RIGHT, Layout.Kind.PILL, statusPill);
        layout.labelCentered("set.status.text", "set.status", statusPill.centerX(),
                ShellLayout.capY(TextStyle.PILL, statusPill.y(), statusPill.h()), pillWidth - 6, state.statusLabel(), TextStyle.PILL);
        ry = statusPill.bottom() + Metrics.GAP_SM;

        Rect preview = new Rect(rightLeft, ry, rightInner, Math.max(16F, buttonH));
        layout.add("set.preview", RIGHT, Layout.Kind.BUTTON, preview);
        layout.add("set.preview.bar", "set.preview", Layout.Kind.BAR, new Rect(preview.x(), preview.bottom() - 2, preview.w(), 2));
        ry = preview.bottom() + Metrics.GAP_SM;

        int statColumns = rightInner >= 150 ? 4 : 2;
        float statWidth = rightInner / statColumns;
        for (int i = 0; i < state.basicStats().size(); i++) {
            Stat stat = state.basicStats().get(i);
            int column = i % statColumns;
            int row = i / statColumns;
            float sx = rightLeft + column * statWidth;
            float sy = ry + row * (TextFit.boxHeight(TextStyle.CAPTION) + TextFit.boxHeight(TextStyle.LABEL) + 4);
            layout.label("set.basic." + i + ".label", RIGHT, sx, sy, statWidth - 3, stat.label(), TextStyle.CAPTION);
            layout.label("set.basic." + i + ".value", RIGHT, sx, sy + TextFit.boxHeight(TextStyle.CAPTION) + 1, statWidth - 3,
                    stat.value(), TextStyle.LABEL);
        }
        int statRows = (state.basicStats().size() + statColumns - 1) / statColumns;
        ry += statRows * (TextFit.boxHeight(TextStyle.CAPTION) + TextFit.boxHeight(TextStyle.LABEL) + 4) + Metrics.GAP_SM;

        float nameColumn = Math.min(54F, rightInner * 0.36F);
        float valueColumn = 24F;
        for (int i = 0; i < state.attributes().size(); i++) {
            Attribute attribute = state.attributes().get(i);
            float ay = ry + i * (Metrics.OPTION_H + 1);
            layout.label("set.attr." + i + ".name", RIGHT, rightLeft, ShellLayout.capY(TextStyle.CAPTION, ay, Metrics.OPTION_H),
                    nameColumn, attribute.label(), TextStyle.CAPTION);
            float barX = rightLeft + nameColumn + 3;
            float barWidth = Math.max(10F, rightInner - nameColumn - valueColumn - 6);
            layout.add("set.attr." + i + ".bar", RIGHT, Layout.Kind.BAR, new Rect(barX, ay + Metrics.OPTION_H * 0.5F - 2, barWidth, 4));
            layout.labelRight("set.attr." + i + ".value", RIGHT, rightLeft + rightInner,
                    ShellLayout.capY(TextStyle.CAPTION, ay, Metrics.OPTION_H), valueColumn, attribute.value(), TextStyle.CAPTION);
        }
        ry += state.attributes().size() * (Metrics.OPTION_H + 1) + Metrics.GAP_SM;

        // User rating: average, the split bar with its counts, the spread histogram and the 10-segment input.
        float ratingTop = ry;
        layout.add("set.rating", RIGHT, Layout.Kind.PANEL, new Rect(rightLeft, ratingTop, rightInner, 10));
        float rty = ratingTop + 4;
        layout.labelCentered("set.rating.title", "set.rating", rightLeft + rightInner * 0.5F, rty, rightInner - 6,
                tr.get(WorkshopStrings.KEY + "set.rating", state.ratingAverage()), TextStyle.CAPTION);
        rty += TextFit.boxHeight(TextStyle.CAPTION) + 3;
        layout.add("set.rating.bar", "set.rating", Layout.Kind.BAR, new Rect(rightLeft + 3, rty, rightInner - 6, 4));
        rty += 6;
        layout.label("set.rating.negative", "set.rating", rightLeft + 3, rty, rightInner * 0.4F, state.ratingNegative(),
                TextStyle.CAPTION);
        layout.labelRight("set.rating.positive", "set.rating", rightLeft + rightInner - 3, rty, rightInner * 0.4F,
                state.ratingPositive(), TextStyle.CAPTION);
        rty += TextFit.boxHeight(TextStyle.CAPTION) + 3;
        layout.labelCentered("set.rating.spread", "set.rating", rightLeft + rightInner * 0.5F, rty, rightInner - 6,
                tr.get(WorkshopStrings.KEY + "set.rating_spread"), TextStyle.CAPTION);
        rty += TextFit.boxHeight(TextStyle.CAPTION) + 3;
        float histogramHeight = compact ? 14F : 20F;
        float barStep = (rightInner - 6) / 10F;
        for (int i = 0; i < 10; i++) {
            layout.add("set.rating.hist." + i, "set.rating", Layout.Kind.BAR,
                    new Rect(rightLeft + 3 + i * barStep, rty, Math.max(1F, barStep - 1), histogramHeight));
        }
        rty += histogramHeight + 3;
        if (state.canRate()) {
            layout.label("set.rating.mine", "set.rating", rightLeft + 3, rty, rightInner - 6,
                    state.myRating() == null ? tr.get(WorkshopStrings.KEY + "set.rate_hint")
                            : tr.get(WorkshopStrings.KEY + "set.your_rating", String.valueOf(state.myRating())), TextStyle.CAPTION);
            rty += TextFit.boxHeight(TextStyle.CAPTION) + 2;
            float segment = (rightInner - 6) / 10F;
            for (int i = 0; i < 10; i++) {
                layout.add("set.rate." + (i + 1), "set.rating", Layout.Kind.BUTTON,
                        new Rect(rightLeft + 3 + i * segment, rty, Math.max(1F, segment - 1), Metrics.OPTION_H));
            }
            rty += Metrics.OPTION_H + 2;
            if (state.myRating() != null) {
                layout.label("set.rate.remove", "set.rating", rightLeft + 3, rty, rightInner - 6,
                        tr.get(WorkshopStrings.KEY + "set.remove_rating"), TextStyle.LABEL);
                rty += TextFit.boxHeight(TextStyle.LABEL) + 2;
            }
        } else if (state.cannotRate() != null && !state.cannotRate().isEmpty()) {
            // The site refuses a rating with a reason of its own - sign in, your own set, an account that is too new -
            // and the box used to drop its input without a word, so the reader was left guessing what went wrong.
            rty += layout.paragraph("set.rating.cannot", "set.rating", rightLeft + 3, rty, rightInner - 6,
                    state.cannotRate(), TextStyle.CAPTION, Metrics.lineHeight(TextStyle.CAPTION), 3) + 2;
        }
        layout.resize("set.rating", new Rect(rightLeft, ratingTop, rightInner, rty - ratingTop + 3));
        ry = rty + 3 + pad;
        layout.resize(RIGHT, new Rect(rightX, rightTop, rightWidth, ry - rightTop));
        if (twoColumns && ry > headerTop + headerHeight) {
            // The cover runs the full height of the right column beside it, and what the header holds sinks to its
            // foot: a header that ended at its own buttons left a black band under the cover as tall as the rest of
            // the stats, and the page read as two frames that did not line up.
            float sink = ry - headerTop - headerHeight;
            sink(layout, headerFirst, headerEnd, sink);
            headerHeight += sink;
        }
        layout.resize(HEADER, new Rect(0, headerTop, twoColumns ? leftWidth : width, headerHeight));
        y = Math.max(headerTop + headerHeight, ry) + Metrics.GAP;

        // ── info section ───────────────────────────────────────────────────────────────────────────────────
        float infoTop = y;
        layout.add(INFO, "", Layout.Kind.PANEL, new Rect(0, infoTop, width, buttonH));
        float iy = infoTop + pad;
        float metaWidth = twoColumns ? Math.min(170F, width * 0.3F) : width - pad * 2;
        float descWidth = twoColumns ? width - metaWidth - pad * 3 : width - pad * 2;
        layout.label("set.info.description", INFO, pad, iy, descWidth, tr.get(WorkshopStrings.KEY + "set.description"),
                TextStyle.STRONG);
        float dy = iy + TextStyle.STRONG.capHeight() + 4;
        if (state.descriptionSpoiler()) {
            // A description the mapper flagged for spoilers stays folded until the reader asks for it; showing it
            // outright is the one thing the flag exists to prevent.
            dy += layout.paragraph("set.spoiler.note", INFO, pad, dy, descWidth,
                    tr.get(WorkshopStrings.KEY + "set.spoiler_note"), TextStyle.CAPTION,
                    Metrics.lineHeight(TextStyle.CAPTION), 2) + 3;
            String reveal = tr.get(WorkshopStrings.KEY + "set.spoiler_reveal");
            float revealWidth = Math.min(descWidth, TextFit.width(measurer, reveal, TextStyle.CAPTION) + 16);
            Rect revealBox = new Rect(pad, dy, Math.max(30F, revealWidth), Metrics.OPTION_H + 2);
            layout.add("set.spoiler.reveal", INFO, Layout.Kind.BUTTON, revealBox);
            layout.labelCentered("set.spoiler.reveal.text", "set.spoiler.reveal", revealBox.centerX(),
                    ShellLayout.capY(TextStyle.CAPTION, revealBox.y(), revealBox.h()), revealBox.w() - 8, reveal,
                    TextStyle.CAPTION);
            dy = revealBox.bottom();
        } else {
            dy += layout.paragraph("set.desc", INFO, pad, dy, descWidth,
                    state.description() == null || state.description().isBlank()
                            ? tr.get(WorkshopStrings.KEY + "set.no_description") : state.description(),
                    TextStyle.BODY, Metrics.lineHeight(TextStyle.BODY), 40);
        }
        if (state.featuredNote() != null && !state.featuredNote().isEmpty()) {
            dy += Metrics.GAP_SM;
            dy += layout.paragraph("set.featured_note", INFO, pad, dy, descWidth, state.featuredNote(), TextStyle.CAPTION,
                    Metrics.lineHeight(TextStyle.CAPTION), 6);
        }
        if (state.credits() != null && !state.credits().isEmpty()) {
            dy += Metrics.GAP_SM;
            layout.label("set.credits.title", INFO, pad, dy, descWidth, tr.get(WorkshopStrings.KEY + "set.credits"), TextStyle.STRONG);
            dy += TextStyle.STRONG.capHeight() + 3;
            dy += layout.paragraph("set.credits", INFO, pad, dy, descWidth, state.credits(), TextStyle.CAPTION,
                    Metrics.lineHeight(TextStyle.CAPTION), 8);
        }

        float my = twoColumns ? iy : dy + Metrics.GAP;
        float metaX = twoColumns ? pad + descWidth + pad : pad;
        for (int i = 0; i < state.metadata().size(); i++) {
            Stat entry = state.metadata().get(i);
            layout.label("set.meta." + i + ".label", INFO, metaX, my, metaWidth, entry.label(), TextStyle.LABEL);
            my += TextFit.boxHeight(TextStyle.LABEL) + 1;
            my += layout.paragraph("set.meta." + i + ".value", INFO, metaX, my, metaWidth, entry.value(), TextStyle.CAPTION,
                    Metrics.lineHeight(TextStyle.CAPTION), 4) + 3;
        }
        if (state.audioLine() != null && !state.audioLine().isEmpty()) {
            layout.label("set.audio.label", INFO, metaX, my, metaWidth, tr.get(WorkshopStrings.KEY + "set.audio"), TextStyle.LABEL);
            my += TextFit.boxHeight(TextStyle.LABEL) + 1;
            my += layout.paragraph("set.audio", INFO, metaX, my, metaWidth, state.audioLine(), TextStyle.CAPTION,
                    Metrics.lineHeight(TextStyle.CAPTION), 4) + 3;
        }

        float chartsY = Math.max(dy, my) + Metrics.GAP;
        layout.label("set.charts.title", INFO, pad, chartsY, width - pad * 2, tr.get(WorkshopStrings.KEY + "set.charts"),
                TextStyle.STRONG);
        chartsY += TextStyle.STRONG.capHeight() + 4;
        float rowH = Metrics.OPTION_H + 3;
        float starWidth = 42F;
        float numberWidth = compact ? 0F : 34F;
        for (int i = 0; i < state.charts().size(); i++) {
            ChartRow chart = state.charts().get(i);
            Rect row = new Rect(pad, chartsY + i * rowH, width - pad * 2, rowH);
            layout.add("set.chart." + i, INFO, Layout.Kind.BUTTON, row);
            layout.add("set.chart." + i + ".star", "set.chart." + i, Layout.Kind.PILL,
                    new Rect(row.x() + 2, row.y() + 1, starWidth, rowH - 3));
            float nameRoom = Math.max(20F, row.w() - starWidth - 8 - numberWidth * 3);
            layout.label("set.chart." + i + ".name", "set.chart." + i, row.x() + starWidth + 6,
                    ShellLayout.capY(TextStyle.CAPTION, row.y(), rowH), nameRoom, chart.name(), TextStyle.CAPTION);
            if (numberWidth > 0) {
                float nx = row.right() - numberWidth * 3;
                layout.label("set.chart." + i + ".notes", "set.chart." + i, nx,
                        ShellLayout.capY(TextStyle.CAPTION, row.y(), rowH), numberWidth - 2, chart.notes(), TextStyle.CAPTION);
                layout.label("set.chart." + i + ".holds", "set.chart." + i, nx + numberWidth,
                        ShellLayout.capY(TextStyle.CAPTION, row.y(), rowH), numberWidth - 2, chart.holds(), TextStyle.CAPTION);
                layout.label("set.chart." + i + ".length", "set.chart." + i, nx + numberWidth * 2,
                        ShellLayout.capY(TextStyle.CAPTION, row.y(), rowH), numberWidth - 2, chart.length(), TextStyle.CAPTION);
            }
        }
        float infoBottom = chartsY + state.charts().size() * rowH + pad;
        layout.resize(INFO, new Rect(0, infoTop, width, infoBottom - infoTop));
        layout.setContentHeight(infoBottom);
        return layout;
    }
}

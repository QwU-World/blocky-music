package ru.gora.musicbattle.client.workshop.layout;

import ru.gora.musicbattle.client.ui.TextMeasurer;
import ru.gora.musicbattle.client.ui.TextStyle;

/**
 * A card for a track the server offers outside the Workshop's own cards - one its operator put there, one a player
 * published with the mod's editor, or a Workshop set the server's beatmap list did not send - as a battle's map picker
 * draws it on the Server tab, in the same grid as the Workshop's cards.
 *
 * <p>It keeps {@link CardLayout}'s footprint, the thumbnail square and the body that overlaps it, so both kinds sit in
 * one grid as one family. Its lines are the ones such a track actually has: who published it, the track's own
 * description where there is room, and an info row with the quiet badge saying what it is in the place a Workshop card
 * keeps its status pill, followed by the tempo and the length. There is nothing to install, rate or favourite, so
 * the whole card is the one button, and a chevron at its right edge says so on hover.
 */
public final class TrackCardLayout {
    /** What a card shows. Strings are already translated; empty ones are left out. */
    public record State(boolean extra, boolean hover, String title, String byline, String detail, String description,
                        String badge) {
        public State {
            title = title == null ? "" : title;
            byline = byline == null ? "" : byline;
            detail = detail == null ? "" : detail;
            description = description == null ? "" : description;
            badge = badge == null ? "" : badge;
        }
    }

    /** Between the parts of the info row's detail ("BPM 174  ·  4:22"); a part that does not fit is left out whole. */
    public static final String DETAIL_SEPARATOR = "  \u00b7  ";
    /** Matches {@link CardLayout}: the body overlaps the thumbnail by this much, under its rounded corner. */
    private static final float BODY_OVERLAP = 6F;
    private static final float TEXT_INSET = 5F;

    private TrackCardLayout() {
    }

    public static Layout compute(Rect card, TextMeasurer measurer, Tr tr, State state) {
        Layout layout = new Layout(measurer, tr);
        layout.add("track", "", Layout.Kind.PANEL, card);
        float height = card.h();
        Rect thumb = new Rect(card.x(), card.y(), height, height);
        layout.add("track.thumb", "track", Layout.Kind.IMAGE, thumb);
        // What the thumbnail shows while the track has no picture, or until the server has sent it.
        float glyph = Math.min(16F, height * 0.32F);
        layout.add("track.thumb.icon", "track.thumb", Layout.Kind.ICON,
                new Rect(thumb.centerX() - glyph * 0.5F, thumb.centerY() - glyph * 0.5F, glyph, glyph));
        float bodyX = card.x() + height - BODY_OVERLAP;
        Rect body = new Rect(bodyX, card.y(), card.right() - bodyX, height);
        layout.add("track.body", "track", Layout.Kind.PANEL, body);

        float chevron = Metrics.ICON;
        Rect choose = new Rect(card.right() - TEXT_INSET - chevron, card.centerY() - chevron * 0.5F, chevron, chevron);
        layout.add("track.choose", "track.body", Layout.Kind.ICON, choose);

        float textX = card.x() + height + TEXT_INSET;
        float textRight = choose.x() - Metrics.GAP_SM;
        float room = Math.max(24F, textRight - textX);
        float pillHeight = Metrics.PILL_H;
        float infoY = card.bottom() - pillHeight - 4F;
        // Text above the info row has to end this far over it, as on the Workshop's cards.
        float textLimit = infoY - 2F;

        float y = card.y() + (state.extra() ? 5F : 4F);
        layout.label("track.title", "track.body", textX, y, room, state.title(), TextStyle.TITLE);
        y += TextStyle.TITLE.capHeight() + 4F;
        // The byline is set like the mapper line of a Workshop card, so the two kinds read alike in one grid.
        if (!state.byline().isEmpty() && y + TextFit.boxHeight(TextStyle.CAPTION) <= textLimit) {
            layout.label("track.byline", "track.body", textX, y, room, state.byline(), TextStyle.CAPTION);
            y += TextStyle.CAPTION.capHeight() + 4F;
        }
        if (!state.description().isEmpty()) {
            float lineHeight = Metrics.lineHeight(TextStyle.CAPTION);
            int fit = (int) Math.floor((textLimit - y - TextFit.boxHeight(TextStyle.CAPTION)) / lineHeight + 0.001F) + 1;
            int lines = Math.min(fit, state.extra() ? 3 : 2);
            if (lines > 0) {
                layout.paragraph("track.desc", "track.body", textX, y, room, state.description(), TextStyle.CAPTION,
                        lineHeight, lines);
            }
        }

        float detailX = textX;
        if (!state.badge().isEmpty()) {
            float badgeWidth = Math.min(room * 0.6F, TextFit.width(measurer, state.badge(), TextStyle.PILL) + 8F);
            Rect pill = new Rect(textX, infoY, badgeWidth, pillHeight);
            layout.add("track.badge", "track.body", Layout.Kind.PILL, pill);
            layout.labelCentered("track.badge.text", "track.badge", pill.centerX(),
                    ShellLayout.capY(TextStyle.PILL, pill.y(), pillHeight), badgeWidth - 4F, state.badge(), TextStyle.PILL);
            detailX = pill.right() + 4F;
        }
        if (!state.detail().isEmpty() && textRight - detailX > 12F) {
            // The tempo and the length, as many of them whole as there is room for after the pill.
            layout.label("track.detail", "track.body", detailX, ShellLayout.capY(TextStyle.CAPTION, infoY, pillHeight),
                    textRight - detailX, TextFit.wholeParts(measurer, state.detail(), DETAIL_SEPARATOR, TextStyle.CAPTION,
                            textRight - detailX), TextStyle.CAPTION);
        }
        return layout;
    }
}

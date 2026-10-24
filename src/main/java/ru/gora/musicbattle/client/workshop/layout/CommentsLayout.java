package ru.gora.musicbattle.client.workshop.layout;

import java.util.List;
import ru.gora.musicbattle.client.ui.TextMeasurer;
import ru.gora.musicbattle.client.ui.TextStyle;

/**
 * The comments section (spec 06 section 6.3, osu-web layout in research section 4): the counter header with the
 * Workshop / Server comments buttons, the composer, the sort bar, the rows with their vote pill in the left gutter,
 * avatar, author line with badges, body, footer actions and replies, and the "Show more" footer.
 *
 * <p>Replies are indented one step; a row whose replies did not all fit in the page carries a control for the rest,
 * and deeper than two levels it carries "Continue in browser" instead (the API loads two levels with the page).
 */
public final class CommentsLayout {
    public static final String HEADER = "comments.header";
    public static final String COMPOSER = "comments.composer";
    public static final String SORT = "comments.sort";
    public static final String LIST = "comments.list";
    public static final List<String> SORTS = List.of("new", "old", "top");

    /**
     * One comment as the screen wants it drawn. {@code repliesShown} is how many of its replies are rows of their own
     * below it and {@code repliesTotal} how many it has in all: the two differ whenever the page brought only the
     * first few, and that difference is the whole reason the "more replies" control exists.
     */
    public record Row(long id, int depth, String author, List<String> badges, String parent, String body, String time,
                      int votes, boolean voted, boolean canVote, boolean canReply, boolean canDelete, boolean canReport,
                      int repliesShown, int repliesTotal, boolean deleted, boolean deeper, String hidden) {
        /** A comment the author removed, a moderator removed, or one held back while it is looked at. */
        public boolean withoutBody() {
            return deleted || hidden != null;
        }

        /** Replies this comment has that are not on screen; the control below it offers exactly these. */
        public int repliesLeft() {
            return Math.max(0, repliesTotal - repliesShown);
        }
    }

    /**
     * {@code rulesPending} is the one-time acceptance of the Workshop Terms and the Community Rules (Terms section
     * 3.1) as {@code /me} reported it: the composer then carries a quiet line offering it, instead of letting the
     * first comment run into the server's refusal.
     *
     * <p>{@code note} is the quiet line under the composer that says where these comments live, {@code replyingTo}
     * the name the next post answers (null for a fresh comment), and {@code bodyLength} / {@code bodyLimit} the
     * counter beside the Post button, because the two lists do not take the same length of text.
     */
    public record State(long total, String sort, boolean canPost, String cannotPostReason, boolean signedIn, List<Row> rows,
                        boolean hasMore, long remaining, boolean loading, boolean serverComments, boolean serverActive,
                        String composerHint, float composerHeight, boolean rulesPending, String note, String replyingTo,
                        int bodyLength, int bodyLimit) {
    }

    private CommentsLayout() {
    }

    public static Layout compute(Rect content, TextMeasurer measurer, Tr tr, State state) {
        Layout layout = new Layout(measurer, tr);
        float width = content.w();
        boolean compact = width < Metrics.COMPACT_WIDTH;
        float pad = compact ? Metrics.PAD_SM : Metrics.PAD;
        float buttonH = compact ? Metrics.BUTTON_H_SM : Metrics.BUTTON_H;
        float y = 0;

        // ── header ─────────────────────────────────────────────────────────────────────────────────────────
        float headerHeight = buttonH + 6;
        layout.add(HEADER, "", Layout.Kind.PANEL, new Rect(0, y, width, headerHeight));
        // The counter belongs to the list actually on screen. It used to sit on the Workshop tab whatever was open,
        // so the server's own number was read out as "Workshop comments 12" the whole time the server list was up.
        String workshopLabel = state.serverActive()
                ? tr.get(WorkshopStrings.KEY + "comments.workshop_tab")
                : tr.get(WorkshopStrings.KEY + "comments.workshop", WorkshopStrings.count(state.total()));
        String serverLabel = state.serverActive()
                ? tr.get(WorkshopStrings.KEY + "comments.server_count", WorkshopStrings.count(state.total()))
                : tr.get(WorkshopStrings.KEY + "comments.server");
        float tabsRoom = Math.max(0F, width - pad * 2 - Metrics.GAP_SM);
        float workshopWidth = Math.min(tabsRoom * 0.55F, TextFit.width(measurer, workshopLabel, TextStyle.BODY) + 14);
        Rect workshopTab = new Rect(pad, y + 3, workshopWidth, buttonH);
        layout.add("comments.tab.workshop", HEADER, Layout.Kind.BUTTON, workshopTab);
        layout.labelCentered("comments.tab.workshop.text", "comments.tab.workshop", workshopTab.centerX(),
                ShellLayout.capY(TextStyle.BODY, workshopTab.y(), buttonH), workshopWidth - 6, workshopLabel, TextStyle.BODY);
        // Whatever the first tab did not take: with a count on it the second label grew, and a share of the width
        // that ignored the first tab could push it off the panel.
        float serverWidth = Math.min(Math.max(0F, tabsRoom - workshopWidth),
                TextFit.width(measurer, serverLabel, TextStyle.CAPTION) + 14);
        Rect serverTab = new Rect(workshopTab.right() + Metrics.GAP_SM, y + 3, serverWidth, buttonH);
        layout.add("comments.tab.server", HEADER, Layout.Kind.BUTTON, serverTab);
        layout.labelCentered("comments.tab.server.text", "comments.tab.server", serverTab.centerX(),
                ShellLayout.capY(TextStyle.CAPTION, serverTab.y(), buttonH), serverWidth - 6, serverLabel, TextStyle.CAPTION);
        y += headerHeight + Metrics.GAP_SM;

        // ── composer ───────────────────────────────────────────────────────────────────────────────────────
        float composerTop = y;
        float avatar = compact ? Metrics.AVATAR_SM : Metrics.AVATAR;
        layout.add(COMPOSER, "", Layout.Kind.PANEL, new Rect(0, composerTop, width, buttonH));
        float composerBottom;
        if (state.signedIn() && state.canPost()) {
            float rowY = composerTop + 2;
            if (state.replyingTo() != null && !state.replyingTo().isEmpty()) {
                // Answering someone used to be a state with no picture at all: the next post silently became a reply
                // and stayed one. The chip says whose reply it is and the button beside it takes the mode off again.
                String replying = tr.get(WorkshopStrings.KEY + "comments.replying_to", state.replyingTo());
                float cancelWidth = Metrics.OPTION_H;
                float chipWidth = Math.min(Math.max(0F, width - pad * 2 - cancelWidth - Metrics.GAP_SM),
                        TextFit.width(measurer, replying, TextStyle.CAPTION) + 12);
                Rect chip = new Rect(pad, rowY, chipWidth, Metrics.OPTION_H);
                layout.add("comments.composer.replying", COMPOSER, Layout.Kind.PILL, chip);
                layout.labelCentered("comments.composer.replying.text", "comments.composer.replying", chip.centerX(),
                        ShellLayout.capY(TextStyle.CAPTION, chip.y(), Metrics.OPTION_H), chipWidth - 8, replying,
                        TextStyle.CAPTION);
                layout.add("comments.composer.replying.cancel", COMPOSER, Layout.Kind.BUTTON,
                        new Rect(chip.right() + Metrics.GAP_SM, rowY, cancelWidth, Metrics.OPTION_H));
                rowY = chip.bottom() + Metrics.GAP_SM;
            }
            layout.add("comments.composer.avatar", COMPOSER, Layout.Kind.IMAGE, new Rect(pad, rowY, avatar, avatar));
            float fieldX = pad + avatar + Metrics.GAP_SM;
            float fieldHeight = Math.max(buttonH, state.composerHeight());
            Rect field = new Rect(fieldX, rowY, width - fieldX - pad, fieldHeight);
            layout.add("comments.composer.field", COMPOSER, Layout.Kind.FIELD, field);
            String post = tr.get(WorkshopStrings.KEY + "comments.post");
            float postWidth = Math.min(field.w(), TextFit.width(measurer, post, TextStyle.BODY) + 18);
            Rect postButton = new Rect(field.right() - postWidth, field.bottom() + 3, postWidth, buttonH);
            layout.add("comments.composer.post", COMPOSER, Layout.Kind.BUTTON, postButton);
            layout.labelCentered("comments.composer.post.text", "comments.composer.post", postButton.centerX(),
                    ShellLayout.capY(TextStyle.BODY, postButton.y(), buttonH), postWidth - 6, post, TextStyle.BODY);
            float footerY = postButton.y() + (buttonH - TextStyle.CAPTION.capHeight()) * 0.5F;
            float footerRight = postButton.x() - Metrics.GAP_SM;
            if (state.bodyLength() > 0 && state.bodyLimit() > 0) {
                // The two lists do not take the same length of text, and the shorter limit used to show up only as a
                // server-side cut, so the counter states which one is in force while the player is still typing.
                String counter = tr.get(WorkshopStrings.KEY + "comments.length", WorkshopStrings.count(state.bodyLength()),
                        WorkshopStrings.count(state.bodyLimit()));
                layout.labelRight("comments.composer.count", COMPOSER, footerRight, footerY,
                        Math.max(0F, footerRight - fieldX), counter, TextStyle.CAPTION);
                footerRight = layout.rect("comments.composer.count").x() - Metrics.GAP_SM;
            }
            layout.label("comments.composer.hint", COMPOSER, fieldX, footerY, Math.max(0F, footerRight - fieldX),
                    tr.get(WorkshopStrings.KEY + "comments.hint"), TextStyle.CAPTION);
            composerBottom = postButton.bottom();
            if (state.rulesPending()) {
                String accept = tr.get(WorkshopStrings.KEY + "comments.rules_pending");
                float acceptWidth = Math.min(width - pad * 2, TextFit.width(measurer, accept, TextStyle.CAPTION) + 20);
                Rect acceptButton = new Rect(pad, composerBottom + Metrics.GAP_SM, acceptWidth, buttonH);
                layout.add("comments.composer.rules", COMPOSER, Layout.Kind.BUTTON, acceptButton);
                layout.labelCentered("comments.composer.rules.text", "comments.composer.rules", acceptButton.centerX(),
                        ShellLayout.capY(TextStyle.CAPTION, acceptButton.y(), buttonH), acceptWidth - 6, accept, TextStyle.CAPTION);
                composerBottom = acceptButton.bottom();
            }
        } else if (state.serverActive() || state.signedIn()) {
            // A refusal is a sentence, not a button. The Workshop's sign-in cannot open a server's comments, and for
            // someone already signed in it cannot open these either: "Comments are closed here." used to be a button
            // that left the page for the account screen, which answers nothing the line said. Whoever is signed in
            // reaches the account from the chip in the title bar anyway, and the refusals that ask for a fresh
            // sign-in say so in their own words.
            String label = state.cannotPostReason() != null && !state.cannotPostReason().isEmpty()
                    ? state.cannotPostReason()
                    : tr.get(WorkshopStrings.KEY + (state.serverActive() ? "comments.server_cannot_post" : "comments.closed"));
            composerBottom = composerTop + 2 + layout.paragraph("comments.composer.closed", COMPOSER, pad, composerTop + 2,
                    width - pad * 2, label, TextStyle.CAPTION, Metrics.lineHeight(TextStyle.CAPTION), 3);
        } else {
            String label = tr.get(WorkshopStrings.KEY + "comments.sign_in");
            Rect button = new Rect(pad, composerTop + 2, Math.min(width - pad * 2,
                    TextFit.width(measurer, label, TextStyle.BODY) + 20), buttonH);
            layout.add("comments.composer.signin", COMPOSER, Layout.Kind.BUTTON, button);
            layout.labelCentered("comments.composer.signin.text", "comments.composer.signin", button.centerX(),
                    ShellLayout.capY(TextStyle.BODY, button.y(), buttonH), button.w() - 6, label, TextStyle.BODY);
            composerBottom = button.bottom();
        }
        if (state.note() != null && !state.note().isEmpty()) {
            float noteY = composerBottom + Metrics.GAP_SM;
            composerBottom = noteY + layout.paragraph("comments.composer.note", COMPOSER, pad, noteY, width - pad * 2,
                    state.note(), TextStyle.CAPTION, Metrics.lineHeight(TextStyle.CAPTION), 2);
        }
        layout.resize(COMPOSER, new Rect(0, composerTop, width, composerBottom - composerTop + 3));
        y = composerBottom + Metrics.GAP_SM + 3;

        // ── sort bar ───────────────────────────────────────────────────────────────────────────────────────
        float sortTop = y;
        layout.add(SORT, "", Layout.Kind.PANEL, new Rect(0, sortTop, width, Metrics.OPTION_H + 4));
        float sx = pad;
        String sortBy = tr.get(WorkshopStrings.KEY + "sort.by");
        layout.label("comments.sort.label", SORT, sx, ShellLayout.capY(TextStyle.CAPTION, sortTop + 2, Metrics.OPTION_H),
                60, sortBy, TextStyle.CAPTION);
        sx += Math.min(60F, TextFit.width(measurer, sortBy, TextStyle.CAPTION)) + Metrics.OPTION_GAP;
        for (String sort : SORTS) {
            boolean active = sort.equals(state.sort());
            TextStyle style = active ? TextStyle.LABEL : TextStyle.CAPTION;
            String label = tr.get(WorkshopStrings.KEY + "comments.sort." + sort);
            float optionWidth = Math.min(width - pad - sx, TextFit.width(measurer, label, style) + 8);
            Rect box = new Rect(sx, sortTop + 2, optionWidth, Metrics.OPTION_H);
            layout.add("comments.sort." + sort, SORT, Layout.Kind.BUTTON, box);
            layout.labelCentered("comments.sort." + sort + ".text", "comments.sort." + sort, box.centerX(),
                    ShellLayout.capY(style, box.y(), Metrics.OPTION_H), optionWidth - 4, label, style);
            sx = box.right() + Metrics.OPTION_GAP;
        }
        y = sortTop + Metrics.OPTION_H + 4 + Metrics.GAP_SM;

        // ── rows ───────────────────────────────────────────────────────────────────────────────────────────
        float listTop = y;
        layout.add(LIST, "", Layout.Kind.PANEL, new Rect(0, listTop, width, 0));
        if (state.rows().isEmpty()) {
            layout.label("comments.empty", LIST, pad, y + 4, width - pad * 2,
                    tr.get(WorkshopStrings.KEY + (state.loading() ? "comments.loading" : "comments.empty")), TextStyle.CAPTION);
            y += TextFit.boxHeight(TextStyle.CAPTION) + 8;
        }
        for (int i = 0; i < state.rows().size(); i++) {
            y = row(layout, measurer, tr, state.rows().get(i), i, pad, width, y, compact);
        }
        layout.resize(LIST, new Rect(0, listTop, width, Math.max(0F, y - listTop)));

        if (state.hasMore()) {
            String label = tr.get(WorkshopStrings.KEY + "comments.show_more", WorkshopStrings.count(state.remaining()));
            float showWidth = Math.min(width - pad * 2, TextFit.width(measurer, label, TextStyle.CAPTION) + 24);
            Rect more = new Rect((width - showWidth) * 0.5F, y + 2, showWidth, buttonH);
            layout.add("comments.more", "", Layout.Kind.BUTTON, more);
            layout.labelCentered("comments.more.text", "comments.more", more.centerX(),
                    ShellLayout.capY(TextStyle.CAPTION, more.y(), buttonH), showWidth - 6, label, TextStyle.CAPTION);
            y = more.bottom() + Metrics.GAP_SM;
        }
        layout.setContentHeight(y);
        return layout;
    }

    private static float row(Layout layout, TextMeasurer measurer, Tr tr, Row row, int index, float pad, float width, float y,
                             boolean compact) {
        String id = "comments.row." + index;
        float indent = pad + Math.min(row.depth(), 2) * Metrics.COMMENT_INDENT;
        float gutter = compact ? 16F : Metrics.COMMENT_GUTTER;
        float avatar = compact ? Metrics.AVATAR_SM : Metrics.AVATAR;
        float rowTop = y;
        layout.add(id, "", Layout.Kind.PANEL, new Rect(indent, rowTop, width - indent - pad, Metrics.OPTION_H));

        float voteWidth = Math.min(gutter, 20F);
        layout.add(id + ".vote", id, Layout.Kind.BUTTON, new Rect(indent, rowTop + 2, voteWidth, Metrics.OPTION_H));
        layout.labelCentered(id + ".vote.text", id + ".vote", indent + voteWidth * 0.5F,
                ShellLayout.capY(TextStyle.CAPTION, rowTop + 2, Metrics.OPTION_H), voteWidth - 2, "+" + row.votes(),
                TextStyle.CAPTION);
        float avatarX = indent + voteWidth + 3;
        layout.add(id + ".avatar", id, Layout.Kind.IMAGE, new Rect(avatarX, rowTop + 2, avatar, avatar));
        float textX = avatarX + avatar + 4;
        float textRoom = Math.max(30F, width - pad - textX);
        float ty = rowTop + 2;

        float authorRoom = textRoom;
        layout.label(id + ".author", id, textX, ty, authorRoom * 0.6F, row.author(), TextStyle.STRONG);
        float bx = layout.rect(id + ".author").right() + 3;
        for (int b = 0; b < row.badges().size(); b++) {
            String badge = row.badges().get(b);
            float badgeWidth = TextFit.width(measurer, badge, TextStyle.PILL) + 6;
            if (bx + badgeWidth > textX + textRoom) {
                break;
            }
            Rect box = new Rect(bx, ty - 1, badgeWidth, Metrics.BADGE_H);
            layout.add(id + ".badge." + b, id, Layout.Kind.PILL, box);
            layout.labelCentered(id + ".badge." + b + ".text", id + ".badge." + b, box.centerX(),
                    ShellLayout.capY(TextStyle.PILL, box.y(), Metrics.BADGE_H), badgeWidth - 3, badge, TextStyle.PILL);
            bx = box.right() + 2;
        }
        if (row.parent() != null && !row.parent().isEmpty() && bx + 20 < textX + textRoom) {
            layout.label(id + ".parent", id, bx, ty, textX + textRoom - bx, row.parent(), TextStyle.CAPTION);
        }
        ty += TextStyle.STRONG.capHeight() + 4;

        // The site sends no text at all for a comment it is hiding, so a row that said nothing was drawn blank
        // with only a name and a time above it. Each case says what happened instead.
        String body = row.deleted() ? tr.get(WorkshopStrings.KEY + "comments.deleted")
                : "moderator".equals(row.hidden()) ? tr.get(WorkshopStrings.KEY + "comments.hidden_staff")
                : row.hidden() != null ? tr.get(WorkshopStrings.KEY + "comments.hidden_review")
                : row.body();
        ty += layout.paragraph(id + ".body", id, textX, ty, textRoom, body, TextStyle.BODY, Metrics.lineHeight(TextStyle.BODY), 12);
        ty += 2;

        float ax = textX;
        layout.label(id + ".time", id, ax, ty, textRoom * 0.5F, row.time(), TextStyle.CAPTION);
        ax = layout.rect(id + ".time").right() + Metrics.OPTION_GAP;
        if (row.canReply()) {
            ax = action(layout, measurer, id, ".reply", tr.get(WorkshopStrings.KEY + "comments.reply"), ax, ty, textX + textRoom);
        }
        if (row.canReport()) {
            ax = action(layout, measurer, id, ".report", tr.get(WorkshopStrings.KEY + "comments.report"), ax, ty, textX + textRoom);
        }
        if (row.canDelete()) {
            ax = action(layout, measurer, id, ".delete", tr.get(WorkshopStrings.KEY + "comments.delete"), ax, ty, textX + textRoom);
        }
        if (row.deeper()) {
            action(layout, measurer, id, ".continue", tr.get(WorkshopStrings.KEY + "comments.continue"), ax, ty, textX + textRoom);
        }
        ty += TextFit.boxHeight(TextStyle.LABEL) + 3;

        // Only the replies that are not already rows below this one. The pill used to count the replies that were
        // right there and do nothing when pressed, which made it a control over something already on screen.
        if (row.repliesLeft() > 0 && !row.deeper()) {
            String label = WorkshopStrings.plural(tr, WorkshopStrings.KEY + "comments.more_replies", row.repliesLeft());
            float repliesWidth = Math.min(textRoom, TextFit.width(measurer, label, TextStyle.CAPTION) + 16);
            Rect box = new Rect(textX, ty, repliesWidth, Metrics.OPTION_H);
            layout.add(id + ".replies", id, Layout.Kind.BUTTON, box);
            layout.labelCentered(id + ".replies.text", id + ".replies", box.centerX(),
                    ShellLayout.capY(TextStyle.CAPTION, box.y(), Metrics.OPTION_H), repliesWidth - 4, label, TextStyle.CAPTION);
            ty += Metrics.OPTION_H + 2;
        }
        float bottom = ty + 3;
        layout.resize(id, new Rect(indent, rowTop, width - indent - pad, bottom - rowTop));
        return bottom;
    }

    private static float action(Layout layout, TextMeasurer measurer, String id, String suffix, String label, float x, float y,
                                float right) {
        float width = TextFit.width(measurer, label, TextStyle.LABEL);
        if (x + width > right) {
            return x;
        }
        layout.label(id + suffix, id, x, y, width, label, TextStyle.LABEL);
        return x + width + Metrics.OPTION_GAP;
    }
}

package ru.gora.musicbattle.client.workshop;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import ru.gora.musicbattle.client.ui.CoverCache;
import ru.gora.musicbattle.client.ui.HandCursor;
import ru.gora.musicbattle.client.ui.Palette;
import ru.gora.musicbattle.client.ui.RoundedImage;
import ru.gora.musicbattle.client.ui.TextField;
import ru.gora.musicbattle.client.ui.TextStyle;
import ru.gora.musicbattle.client.ui.TextStyles;
import ru.gora.musicbattle.client.ui.UiIcon;
import ru.gora.musicbattle.client.workshop.layout.CommentsLayout;
import ru.gora.musicbattle.client.workshop.layout.HtmlText;
import ru.gora.musicbattle.client.workshop.layout.Layout;
import ru.gora.musicbattle.client.workshop.layout.Rect;
import ru.gora.musicbattle.client.workshop.layout.TextFit;
import ru.gora.musicbattle.client.workshop.layout.WorkshopStrings;
import ru.gora.musicbattle.workshop.api.CancelToken;
import ru.gora.musicbattle.workshop.api.WorkshopApiException;
import ru.gora.musicbattle.workshop.api.WorkshopDtos;

/**
 * The comments of a set inside the set page: the counter header with the Workshop and Server
 * comments buttons, the composer for signed-in players (Ctrl/Cmd+Enter posts), the New / Old / Top sort, the rows with
 * vote pill, avatar, badges, plain-text body with its links, replies to depth two and "Show more".
 *
 * <p>Server comments come from the Minecraft server: the button is drawn disabled until {@link WorkshopServerHooks}
 * has one.
 */
public final class CommentsSection implements ServerWorkshopClient.ServerCommentsView {
    private static final int MAX_BODY = 5000;
    /** The server's own comments are 1 to 500 characters. */
    private static final int MAX_SERVER_BODY = 500;
    /** Bumping this shows the disclaimer again (owner's decision). */
    private static final int SERVER_NOTICE_VERSION = 1;
    private static final String SERVER_NOTICE_KEY = "serverCommentsNoticeVersion";
    /** Give up on a page the Minecraft server never sends; its hook has no failure path of its own. */
    private static final long SERVER_TIMEOUT_MS = 15_000L;
    /** How many sets keep a draft; a reader rarely has more than one comment on the go. */
    private static final int MAX_DRAFTS = 8;
    /**
     * What is half-written, by set, for as long as the game runs. The close button in the title bar ends the whole
     * Workshop without a word, and the question Escape raises counts only the characters the page was seen to take,
     * so a comment that was pasted in goes with it. Reopening the set finds the text still there.
     */
    private static final Map<String, String> DRAFTS = new LinkedHashMap<>();

    private final WorkshopScreen screen;
    private final WorkshopClient client;
    private final long setId;
    private final TextField composer = TextField.multiLine(MAX_BODY);
    private final Map<Long, WorkshopDtos.CommentUser> users = new HashMap<>();
    /** Comment id to the id of whoever wrote it: a drawn row knows only the comment it belongs to. */
    private final Map<Long, Long> rowAuthors = new HashMap<>();
    /** Per comment: every link it holds, as the words that were drawn plus where they go. */
    private final Map<Long, List<HtmlText.Link>> bodyLinks = new LinkedHashMap<>();
    private final Set<Long> votes = new HashSet<>();
    /** Comments whose vote is in flight: a second press changes nothing until the first answer lands. */
    private final Set<Long> voting = new HashSet<>();
    /** Where the next page of one comment's replies starts; the site sends these with the page it answers. */
    private final Map<Long, String> replyCursors = new HashMap<>();
    private final Set<Long> repliesLoading = new HashSet<>();
    /** Comments the site has no further replies for, so their control goes away instead of asking again for nothing. */
    private final Set<Long> repliesDone = new HashSet<>();

    private List<CommentsLayout.Row> rows = List.of();
    private List<WorkshopDtos.Comment> loaded = new ArrayList<>();
    /**
     * The replies that came with the page. They used to live only as a parameter of the rebuild, so anything that
     * rebuilt the rows without a fresh page - voting did - dropped every reply off the screen until a reload.
     */
    private List<WorkshopDtos.Comment> included = new ArrayList<>();
    private WorkshopDtos.CommentsMeta meta;
    private String sort = "new";
    private String cursor;
    private boolean hasMore;
    private long remaining;
    private long total;
    private boolean loading;
    private long ownerId;
    private Long replyTo;
    /** The name on the chip over the composer while {@link #replyTo} is set. */
    private String replyToAuthor;
    private Layout layout;
    private Rect area = Rect.EMPTY;
    private float top;
    private Runnable onResize = () -> {
    };

    /** The same section can show the Workshop's comments or the ones stored on this Minecraft server. */
    private boolean serverMode;
    private final String serverKey;
    private List<ru.gora.musicbattle.network.ServerCommentsPagePayload.Row> serverRows = List.of();
    private final Map<Long, ru.gora.musicbattle.client.ui.SkinHead.Author> serverAuthors = new HashMap<>();
    private long serverTotal;
    private long serverBeforeId;
    private boolean serverCanPost;
    private boolean serverCanModerate;
    private boolean serverOnlineMode = true;
    private String serverNote = "";
    /** Set while this view is waiting for the answer to its own post, so only a post empties the composer. */
    private boolean serverPostPending;
    /** When that post was sent, so an answer that never comes does not strand the flag; see {@link #expireServerWaits}. */
    private long serverPostAtMs;
    /** When the server's page was asked for, so a request nobody answers does not leave the list loading for good. */
    private long serverRequestedMs;
    /** Whether a page from this Minecraft server has arrived, so its refusals are answers rather than guesses. */
    private boolean serverPageSeen;

    /** Answers that came back from the network and have still to reach the render thread; see {@link #deliver}. */
    private final Queue<Runnable> pending = new ConcurrentLinkedQueue<>();

    public CommentsSection(WorkshopScreen screen, WorkshopClient client, long setId) {
        this(screen, client, setId, "ws_" + setId, false);
    }

    /** With an explicit key and start list: the vote screen and the track library open {@code track_<id>} rows. */
    /**
     * A track made in the server's editor was never published to the Workshop, so asking the site for its comments
     * only returns an error; its page keeps to the server's own comments.
     */
    private boolean hasWorkshopComments() {
        return serverKey == null || !serverKey.startsWith("track_");
    }

    public CommentsSection(WorkshopScreen screen, WorkshopClient client, long setId, String serverKey, boolean serverFirst) {
        this.screen = screen;
        this.client = client;
        this.setId = setId;
        this.serverKey = serverKey;
        this.serverMode = serverFirst && WorkshopServerHooks.serverCommentsAvailable();
        composer.setOnSubmit(this::post);
        // The section is measured from the composer's own height, and nothing else re-measures while someone types, so
        // a comment of several lines used to be written into a box one line tall. The draft is kept here too, where
        // every change passes.
        composer.setOnChange(() -> {
            rememberDraft();
            onResize.run();
        });
        String draft = DRAFTS.get(serverKey);
        if (draft != null) {
            composer.setValue(draft);
        }
        applyComposerMode();
    }

    /** Keeps what is written where a page opened again can find it; an empty composer keeps nothing. */
    private void rememberDraft() {
        if (composer.value().isBlank()) {
            DRAFTS.remove(serverKey);
            return;
        }
        DRAFTS.put(serverKey, composer.value());
        while (DRAFTS.size() > MAX_DRAFTS) {
            var oldest = DRAFTS.keySet().iterator();
            oldest.next();
            oldest.remove();
        }
    }

    /**
     * Hands an answer back to this section.
     *
     * <p>The link confirmation a comment's own link raises is a vanilla screen over the Workshop, not one of its
     * dialogs, and an answer that arrived under one used to be dropped on the spot; because {@code loading} stays set,
     * the list then refused to ask for that page again. The section holds its own answers here and applies them from
     * the page's frames, so each one reaches the list that asked whatever was on top when it came in.
     */
    private void deliver(Runnable work) {
        pending.add(work);
        screen.later(this::pump);
    }

    /** Applies whatever came back while something else was on top. Render thread; the set page calls it each frame. */
    public void pump() {
        Runnable work;
        while ((work = pending.poll()) != null) {
            work.run();
        }
    }

    /**
     * The set page closing. A section in server mode is the registered receiver of the server's comment pages, and a
     * page nobody can see must not keep receiving them.
     */
    public void onPageClosed() {
        if (serverMode) {
            ServerWorkshopClient.setCommentsView(null);
        }
    }

    /**
     * The composer follows the list it posts to. The field took 5000 characters whichever list was open, and the
     * server takes 500, so a long comment was accepted, sent and cut in half on the way.
     */
    private void applyComposerMode() {
        // A post left in flight on the other list will never be answered to this view, and its flag would otherwise
        // spend itself on the first delete or report that is. The same goes for a page request: its answer belongs to
        // the list that is leaving, and the flag would keep the arriving list from ever asking for one of its own.
        serverPostPending = false;
        serverPostAtMs = 0;
        loading = false;
        serverRequestedMs = 0;
        serverPageSeen = false;
        composer.setMaxLength(serverMode ? MAX_SERVER_BODY : MAX_BODY);
        // Cuts a draft carried over from the other list right away, where the counter shows it, rather than on the
        // way out where nobody sees it happen.
        composer.setValue(composer.value());
        composer.setPlaceholder(WorkshopTr.INSTANCE.get(WorkshopStrings.KEY
                + (serverMode ? "comments.server_placeholder" : "comments.placeholder")));
    }

    public void setOwner(long value) {
        ownerId = value;
    }

    /** Lays the section out inside {@code area} (the set page's content column) at {@code topY}; returns its height. */
    public float layout(Rect area, float topY) {
        this.area = area;
        this.top = topY;
        CommentsLayout.State state = new CommentsLayout.State(serverMode ? serverTotal : total, sort,
                // Neither list knows its own rules before its first page, and the composer is offered until one says
                // no, as the Workshop's always has been. The server's list opened on "You cannot post here." instead,
                // every single time, for as long as the round trip to the server took.
                serverMode ? !serverPageSeen || serverCanPost : meta == null || meta.canPost(),
                serverMode ? serverCannotPostReason() : meta == null ? null : reason(meta.cannotPostReason()),
                serverMode || client.session().signedIn(), rows, serverMode ? serverBeforeId > 0 : hasMore,
                serverMode ? Math.max(0L, serverTotal - serverRows.size()) : remaining,
                loading, WorkshopServerHooks.serverCommentsAvailable(), serverMode,
                WorkshopTr.INSTANCE.get(WorkshopStrings.KEY + (serverMode ? "comments.server_placeholder" : "comments.placeholder")),
                composer.preferredHeight(20F, 60F), rulesPending(), serverMode ? serverNoteText() : null,
                replyTo == null ? null : replyToAuthor, composer.value().length(),
                serverMode ? MAX_SERVER_BODY : MAX_BODY, hasWorkshopComments());
        layout = CommentsLayout.compute(new Rect(0, 0, area.w(), area.h()), TextStyles.measurer(), WorkshopTr.INSTANCE, state);
        return layout.contentHeight();
    }

    /**
     * Why the composer is closed, in words.
     *
     * The server answers with its own vocabulary here - sign_in, locked, silenced - which is not the vocabulary of
     * its error codes, so two of them had no text at all and reached the player as "Something went wrong (sign_in)".
     * These get their own strings, phrased for the person looking at an empty composer, and anything unexpected
     * still falls back to the error text rather than nothing.
     */
    private String reason(String code) {
        if (code == null) {
            return null;
        }
        String key = WorkshopStrings.KEY + "comments.cannot_post." + code;
        if (net.minecraft.locale.Language.getInstance().has(key)) {
            return WorkshopTr.INSTANCE.get(key);
        }
        return WorkshopText.error(code).getString();
    }

    /**
     * Terms section 3.1: the Workshop comments ask for a one-time acceptance. The server's own comments do not, and
     * neither does browsing, so the line appears only under a Workshop composer the player can otherwise use.
     */
    private boolean rulesPending() {
        return !serverMode && client.session().signedIn() && client.participationPending();
    }

    /** Asks {@code /me} once whether the acceptance is still owed; the page re-measures only if the answer changed. */
    private void checkRules() {
        if (serverMode || !client.session().signedIn() || !client.networkAllowed()) {
            return;
        }
        boolean before = client.participationPending();
        client.ensureParticipation(() -> deliver(() -> {
            if (client.participationPending() != before) {
                onResize.run();
            }
        }));
    }

    /** The set page re-measures itself with this when the section grew or shrank outside a layout pass. */
    public void setOnResize(Runnable value) {
        onResize = value == null ? () -> {
        } : value;
    }

    public float height() {
        return layout == null ? 0F : layout.contentHeight();
    }

    // ---------------------------------------------------------------- rendering

    public void render(GuiGraphics g, float scrollOffset, double mouseX, double mouseY) {
        expireServerWaits();
        if (layout == null) {
            return;
        }
        float baseY = area.y() + top - scrollOffset;
        WorkshopDraw.panel(g, new Rect(area.x(), baseY, area.w(), layout.contentHeight()), Palette.RADIUS_MD,
                Palette.withAlpha(Palette.BG_1, 0.85F));

        // Which tab is filled in says which list is on screen; it used to be the Workshop one whichever was open.
        Rect workshopTab = shift(layout.rect("comments.tab.workshop"), baseY);
        boolean workshopEnabled = hasWorkshopComments();
        if (!workshopTab.empty()) {
            boolean workshopHovered = workshopEnabled && serverMode && WorkshopDraw.hot(workshopTab, mouseX, mouseY);
            WorkshopDraw.button(g, workshopTab, serverMode ? WorkshopDraw.ButtonStyle.QUIET : WorkshopDraw.ButtonStyle.SECONDARY,
                    workshopHovered, workshopEnabled);
            drawText(g, "comments.tab.workshop.text", baseY,
                    !workshopEnabled ? Palette.TEXT_MUTE : serverMode ? Palette.TEXT_2 : Palette.TEXT_1);
        }
        Rect serverTab = shift(layout.rect("comments.tab.server"), baseY);
        boolean serverEnabled = WorkshopServerHooks.serverCommentsAvailable();
        boolean serverHovered = serverEnabled && !serverMode && WorkshopDraw.hot(serverTab, mouseX, mouseY);
        WorkshopDraw.button(g, serverTab, serverMode ? WorkshopDraw.ButtonStyle.SECONDARY : WorkshopDraw.ButtonStyle.QUIET,
                serverHovered, serverEnabled);
        drawText(g, "comments.tab.server.text", baseY,
                !serverEnabled ? Palette.TEXT_MUTE : serverMode ? Palette.TEXT_1 : Palette.TEXT_2);

        Rect replying = shift(layout.rect("comments.composer.replying"), baseY);
        if (!replying.empty()) {
            WorkshopDraw.pill(g, replying, Palette.withAlpha(Palette.ACCENT_DEEP, 0.7F));
            drawText(g, "comments.composer.replying.text", baseY, Palette.TEXT_1);
            Rect cancel = shift(layout.rect("comments.composer.replying.cancel"), baseY);
            boolean cancelHovered = WorkshopDraw.hot(cancel, mouseX, mouseY);
            WorkshopDraw.pill(g, cancel, cancelHovered ? Palette.BG_4 : Palette.BG_3);
            WorkshopDraw.icon(g, cancel.inset(cancel.w() * 0.3F, cancel.h() * 0.3F), UiIcon.X,
                    cancelHovered ? Palette.TEXT_1 : Palette.TEXT_3);
        }
        Rect composerField = layout.rect("comments.composer.field");
        if (!composerField.empty()) {
            Rect avatar = shift(layout.rect("comments.composer.avatar"), baseY);
            drawAvatar(g, avatar, client.session().state().account() == null ? null : client.session().state().account().avatarUrl());
            composer.setBounds(area.x() + composerField.x(), baseY + composerField.y(), composerField.w(), composerField.h());
            composer.render(g, mouseX, mouseY);
            Rect post = shift(layout.rect("comments.composer.post"), baseY);
            boolean postHovered = WorkshopDraw.hot(post, mouseX, mouseY);
            // Dimmed while the server has still to answer the last post, which is the only sign the player gets
            // that it went: the answer arrives as a whole new page, and until then a lit button invites a second one.
            WorkshopDraw.button(g, post, WorkshopDraw.ButtonStyle.PRIMARY, postHovered,
                    !composer.value().isBlank() && !serverPostPending);
            drawText(g, "comments.composer.post.text", baseY, Palette.TEXT_1);
            drawText(g, "comments.composer.hint", baseY, Palette.TEXT_MUTE);
            int limit = serverMode ? MAX_SERVER_BODY : MAX_BODY;
            drawText(g, "comments.composer.count", baseY,
                    composer.value().length() >= limit ? Palette.WARN : Palette.TEXT_MUTE);
        }
        for (Layout.Box box : layout.group("comments.composer.closed.")) {
            drawBox(g, box, baseY, Palette.TEXT_3);
        }
        for (Layout.Box box : layout.group("comments.composer.note.")) {
            drawBox(g, box, baseY, Palette.TEXT_MUTE);
        }
        Rect rules = shift(layout.rect("comments.composer.rules"), baseY);
        if (!rules.empty()) {
            boolean hovered = WorkshopDraw.hot(rules, mouseX, mouseY);
            WorkshopDraw.button(g, rules, WorkshopDraw.ButtonStyle.QUIET, hovered, true);
            drawText(g, "comments.composer.rules.text", baseY, hovered ? Palette.TEXT_1 : Palette.TEXT_2);
        }
        Rect signIn = shift(layout.rect("comments.composer.signin"), baseY);
        if (!signIn.empty()) {
            boolean hovered = WorkshopDraw.hot(signIn, mouseX, mouseY);
            WorkshopDraw.button(g, signIn, WorkshopDraw.ButtonStyle.SECONDARY, hovered, true);
            drawText(g, "comments.composer.signin.text", baseY, Palette.TEXT_1);
        }

        drawText(g, "comments.sort.label", baseY, Palette.TEXT_MUTE);
        for (String option : CommentsLayout.SORTS) {
            Rect rect = shift(layout.rect("comments.sort." + option), baseY);
            boolean active = option.equals(sort);
            boolean hovered = WorkshopDraw.hot(rect, mouseX, mouseY);
            if (active || hovered) {
                WorkshopDraw.panel(g, rect, Palette.RADIUS_SM, active ? Palette.BG_3 : Palette.withAlpha(Palette.BG_3, 0.6F));
            }
            drawText(g, "comments.sort." + option + ".text", baseY, active ? Palette.TEXT_1 : Palette.TEXT_3);
        }

        drawText(g, "comments.empty", baseY, Palette.TEXT_MUTE);
        for (int i = 0; i < rows.size(); i++) {
            renderRow(g, i, rows.get(i), baseY, mouseX, mouseY);
        }
        Rect more = shift(layout.rect("comments.more"), baseY);
        if (!more.empty()) {
            boolean hovered = WorkshopDraw.hot(more, mouseX, mouseY);
            WorkshopDraw.button(g, more, WorkshopDraw.ButtonStyle.SECONDARY, hovered, true);
            drawText(g, "comments.more.text", baseY, Palette.TEXT_2);
        }
    }

    private void renderRow(GuiGraphics g, int index, CommentsLayout.Row row, float baseY, double mouseX, double mouseY) {
        String id = "comments.row." + index;
        Rect rowRect = shift(layout.rect(id), baseY);
        if (rowRect.bottom() < area.y() - 20 || rowRect.y() > area.bottom() + 20) {
            return;
        }
        Rect vote = shift(layout.rect(id + ".vote"), baseY);
        boolean voted = row.voted();
        boolean voteHovered = !serverMode && WorkshopDraw.hot(vote, mouseX, mouseY);
        boolean votePending = voting.contains(row.id());
        WorkshopDraw.pill(g, vote, voted ? Palette.OK : votePending ? Palette.BG_3 : Palette.BG_0);
        if (voteHovered) {
            WorkshopDraw.outline(g, vote, vote.h() * 0.5F, 1F, Palette.OK);
        }
        drawText(g, id + ".vote.text", baseY, voted ? Palette.ON_PILL : Palette.TEXT_2);
        Rect avatar = shift(layout.rect(id + ".avatar"), baseY);
        if (serverMode) {
            // A signed skin when the server had one, otherwise by name, otherwise Steve.
            ru.gora.musicbattle.client.ui.SkinHead.draw(g, serverAuthors.get(row.id()), client.networkAllowed(),
                    avatar.x(), avatar.y(), avatar.w(), avatar.w() * 0.25F);
        } else {
            // The row carries the comment id, and `users` is keyed by the person who wrote it. Looking one up
            // with the other found nobody, so every avatar fell through to the placeholder while the name beside
            // it was right, which is how it was reported: names fine, pictures missing.
            Long authorId = rowAuthors.get(row.id());
            WorkshopDtos.CommentUser user = authorId == null ? null : users.get(authorId);
            drawAvatar(g, avatar, user == null ? null : user.avatarUrl());
        }
        drawText(g, id + ".author", baseY, Palette.TEXT_1);
        for (int b = 0; b < row.badges().size(); b++) {
            Rect badge = shift(layout.rect(id + ".badge." + b), baseY);
            if (badge.empty()) {
                break;
            }
            WorkshopDraw.pill(g, badge, Palette.BG_3);
            drawText(g, id + ".badge." + b + ".text", baseY, Palette.BADGE_LIBRARY);
        }
        drawText(g, id + ".parent", baseY, Palette.TEXT_MUTE);
        List<HtmlText.Link> links = bodyLinks.getOrDefault(row.id(), List.of());
        for (Layout.Box box : layout.group(id + ".body.")) {
            drawBodyLine(g, box, baseY, row.withoutBody() ? Palette.TEXT_MUTE : Palette.TEXT_2, links, mouseX, mouseY);
        }
        drawText(g, id + ".time", baseY, Palette.TEXT_MUTE);
        for (String action : List.of(".reply", ".report", ".delete", ".continue")) {
            Rect rect = shift(layout.rect(id + action), baseY);
            if (rect.empty()) {
                continue;
            }
            boolean hovered = WorkshopDraw.hot(rect, mouseX, mouseY);
            drawText(g, id + action, baseY, hovered ? Palette.TEXT_1 : Palette.LINK);
        }
        Rect replies = shift(layout.rect(id + ".replies"), baseY);
        if (!replies.empty()) {
            boolean pending = repliesLoading.contains(row.id());
            boolean hovered = !pending && WorkshopDraw.hot(replies, mouseX, mouseY);
            WorkshopDraw.pill(g, replies, pending ? Palette.BG_2 : hovered ? Palette.BG_4 : Palette.BG_3);
            drawText(g, id + ".replies.text", baseY, pending ? Palette.TEXT_MUTE : Palette.TEXT_2);
        }
        if (row.depth() == 0 && index < rows.size() - 1) {
            WorkshopDraw.panel(g, new Rect(rowRect.x(), rowRect.bottom() - 1, rowRect.w(), 1), 0,
                    Palette.withAlpha(Palette.LINE, 0.5F));
        }
    }

    private void drawAvatar(GuiGraphics g, Rect rect, String url) {
        if (rect.empty()) {
            return;
        }
        var texture = client.networkAllowed() && url != null && !url.isEmpty() ? CoverCache.get(url) : null;
        if (texture != null) {
            RoundedImage.draw(g, texture, rect.x(), rect.y(), rect.w(), rect.h(), rect.w() * 0.5F, 0F, 1F);
        } else {
            WorkshopDraw.panel(g, rect, rect.w() * 0.5F, Palette.BG_3);
            WorkshopDraw.icon(g, rect.inset(rect.w() * 0.25F, rect.h() * 0.25F), UiIcon.USER_ROUND, Palette.TEXT_3);
        }
    }

    /**
     * One wrapped line of a comment body, with the addresses in it picked out in the link colour. Drawing the line
     * in pieces keeps the link where the word is, which is what makes it clickable on its own: the whole body used
     * to be one target that opened the first link of the comment wherever it was pressed.
     */
    private void drawBodyLine(GuiGraphics g, Layout.Box box, float baseY, int colour, List<HtmlText.Link> links,
                              double mouseX, double mouseY) {
        if (box == null || box.text() == null || box.text().isEmpty()) {
            return;
        }
        Rect rect = shift(box.rect(), baseY);
        if (rect.bottom() < area.y() - 10 || rect.y() > area.bottom() + 10) {
            return;
        }
        String text = box.text();
        TextStyle style = box.style() == null ? TextStyle.BODY : box.style();
        List<LinkRun> runs = linkRuns(text, links);
        if (runs.isEmpty()) {
            drawSegment(g, text, 0, text.length(), style, rect, colour);
            return;
        }
        boolean onLine = rect.contains(mouseX, mouseY);
        int at = 0;
        for (LinkRun run : runs) {
            drawSegment(g, text, at, run.start(), style, rect, colour);
            boolean hovered = onLine && withinRun(rect, style, text, run, mouseX);
            if (hovered) {
                HandCursor.request(HandCursor.Kind.HAND);
            }
            at = drawSegment(g, text, run.start(), run.end(), style, rect, hovered ? Palette.TEXT_1 : Palette.LINK);
        }
        drawSegment(g, text, at, text.length(), style, rect, colour);
    }

    /** Draws {@code text[from, to)} at its own place in the line; returns {@code to} so runs can be chained. */
    private int drawSegment(GuiGraphics g, String text, int from, int to, TextStyle style, Rect rect, int colour) {
        if (to <= from) {
            return from;
        }
        float x = rect.x() + TextFit.width(TextStyles.measurer(), text.substring(0, from), style);
        TextStyles.queue(g, TextStyles.text(text.substring(from, to), style).getVisualOrderText(), x, rect.y(), colour);
        return to;
    }

    private void drawText(GuiGraphics g, String id, float baseY, int colour) {
        drawBox(g, layout.box(id), baseY, colour);
    }

    private void drawBox(GuiGraphics g, Layout.Box box, float baseY, int colour) {
        if (box == null || box.text() == null || box.text().isEmpty()) {
            return;
        }
        Rect rect = shift(box.rect(), baseY);
        if (rect.bottom() < area.y() - 10 || rect.y() > area.bottom() + 10) {
            return;
        }
        TextStyles.queue(g, TextStyles.text(box.text(), box.style() == null ? TextStyle.BODY : box.style()).getVisualOrderText(),
                rect.x(), rect.y(), colour);
    }

    private Rect shift(Rect rect, float baseY) {
        return rect.empty() ? rect : new Rect(area.x() + rect.x(), baseY + rect.y(), rect.w(), rect.h());
    }

    // ---------------------------------------------------------------- links inside a body

    /** A word of one drawn line that stands for a link of the comment, and the address it opens. */
    private record LinkRun(int start, int end, String url) {
    }

    /** Punctuation a sentence puts after an address; it is not part of it. */
    private static final String TRAILING = ".,;:!?)]}>\"'»…";

    /**
     * The words of a line that are links, in order. The body arrives as plain text with the addresses it held beside
     * it (the site sends sanitised HTML and {@code HtmlText} keeps the {@code href}s), so a word is a link when one
     * of those addresses is the word, starts with it - the site shortens long ones - or contains it, which is how a
     * long address broken over two lines is recognised.
     */
    private static List<LinkRun> linkRuns(String line, List<HtmlText.Link> links) {
        List<LinkRun> runs = new ArrayList<>();
        if (line == null || line.isEmpty() || links.isEmpty()) {
            return runs;
        }
        int at = 0;
        while (at < line.length()) {
            while (at < line.length() && Character.isWhitespace(line.charAt(at))) {
                at++;
            }
            int start = at;
            while (at < line.length() && !Character.isWhitespace(line.charAt(at))) {
                at++;
            }
            int end = at;
            while (end > start && TRAILING.indexOf(line.charAt(end - 1)) >= 0) {
                end--;
            }
            String url = end > start ? linkOf(line.substring(start, end), links) : null;
            if (url != null) {
                runs.add(new LinkRun(start, end, url));
            }
        }
        return runs;
    }

    /**
     * Where the word under the pointer goes, if anywhere.
     *
     * The words on screen are the words the writer used, and only an address written out in full is its own label.
     * So a word is matched against what each link was written as first; the address is tried afterwards, because a
     * long address can be broken across lines and the word is then only the start of it.
     */
    private static String linkOf(String word, List<HtmlText.Link> links) {
        if (word.length() < 4) {
            return null;
        }
        for (HtmlText.Link link : links) {
            if (link.text().equals(word)) {
                return link.href();
            }
        }
        // A label of several words: any of them opens it, which is what a reader expects of an underlined phrase.
        for (HtmlText.Link link : links) {
            if (word.length() >= 4 && containsWord(link.text(), word)) {
                return link.href();
            }
        }
        for (HtmlText.Link link : links) {
            if (link.href().equals(word) || link.href().startsWith(word) && word.length() >= 8) {
                return link.href();
            }
        }
        return null;
    }

    /** Whether {@code word} is one of the space-separated words of {@code text}. */
    private static boolean containsWord(String text, String word) {
        for (String part : text.split("\\s+")) {
            if (part.equals(word)) {
                return true;
            }
        }
        return false;
    }

    /** Whether {@code mouseX} is over this run, measured with the very font the line was drawn in. */
    private static boolean withinRun(Rect line, TextStyle style, String text, LinkRun run, double mouseX) {
        float start = line.x() + TextFit.width(TextStyles.measurer(), text.substring(0, run.start()), style);
        float end = line.x() + TextFit.width(TextStyles.measurer(), text.substring(0, run.end()), style);
        return mouseX >= start && mouseX < end;
    }

    // ---------------------------------------------------------------- input

    public boolean mouseClicked(double mouseX, double mouseY, int button, float scrollOffset) {
        if (layout == null || button != GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            return false;
        }
        float baseY = area.y() + top - scrollOffset;
        if (composer.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        if (hit("comments.tab.workshop", baseY, mouseX, mouseY) && serverMode && hasWorkshopComments()) {
            serverMode = false;
            applyComposerMode();
            ServerWorkshopClient.setCommentsView(null);
            loaded = new ArrayList<>();
            included = new ArrayList<>();
            rebuildRows();
            reload();
            return true;
        }
        if (hit("comments.tab.server", baseY, mouseX, mouseY) && WorkshopServerHooks.serverCommentsAvailable()
                && !serverMode) {
            openServerComments();
            return true;
        }
        if (hit("comments.composer.replying.cancel", baseY, mouseX, mouseY)) {
            cancelReply();
            return true;
        }
        if (hit("comments.composer.post", baseY, mouseX, mouseY)) {
            post();
            return true;
        }
        if (hit("comments.composer.signin", baseY, mouseX, mouseY)) {
            screen.openAccount();
            return true;
        }
        if (hit("comments.composer.rules", baseY, mouseX, mouseY)) {
            screen.openRulesAcceptance(() -> onResize.run());
            return true;
        }
        for (String option : CommentsLayout.SORTS) {
            if (hit("comments.sort." + option, baseY, mouseX, mouseY)) {
                sort = option;
                reload();
                return true;
            }
        }
        if (hit("comments.more", baseY, mouseX, mouseY)) {
            if (serverMode) {
                requestServerPage(serverBeforeId);
            } else {
                loadPage(cursor);
            }
            return true;
        }
        for (int i = 0; i < rows.size(); i++) {
            String id = "comments.row." + i;
            CommentsLayout.Row row = rows.get(i);
            if (hit(id + ".vote", baseY, mouseX, mouseY)) {
                // Not gated on canVote: a signed-out press must reach the sign-in screen rather than do nothing.
                vote(row);
                return true;
            }
            if (hit(id + ".reply", baseY, mouseX, mouseY)) {
                replyTo = row.id();
                replyToAuthor = row.author();
                composer.setFocused(true);
                // The chip over the composer is new height, so the page has to be measured again to make room.
                onResize.run();
                return true;
            }
            if (hit(id + ".report", baseY, mouseX, mouseY)) {
                if (serverMode) {
                    WorkshopServerHooks.ServerComments hook = WorkshopServerHooks.serverComments();
                    if (hook != null) {
                        hook.report(serverKey, row.id(), "in_game", error -> {
                        });
                        screen.showMessage(Component.translatable(WorkshopStrings.KEY + "comments.server_reported"),
                                Component.translatable(WorkshopStrings.KEY + "comments.server_reported_detail"));
                    }
                    return true;
                }
                screen.showDialog(WorkshopReportScreen.forComment(screen, client, row.id(), row.author()));
                return true;
            }
            if (hit(id + ".delete", baseY, mouseX, mouseY)) {
                confirmDelete(row);
                return true;
            }
            if (hit(id + ".continue", baseY, mouseX, mouseY)) {
                openInBrowser(row.id());
                return true;
            }
            if (hit(id + ".replies", baseY, mouseX, mouseY)) {
                loadReplies(row);
                return true;
            }
            // Only the word pressed, not the paragraph around it: a press anywhere in the body used to open the
            // comment's first link, however far from it the press was.
            List<HtmlText.Link> links = bodyLinks.getOrDefault(row.id(), List.of());
            for (Layout.Box box : layout.group(id + ".body.")) {
                Rect line = shift(box.rect(), baseY);
                if (!line.contains(mouseX, mouseY) || box.text() == null) {
                    continue;
                }
                TextStyle style = box.style() == null ? TextStyle.BODY : box.style();
                for (LinkRun run : linkRuns(box.text(), links)) {
                    if (withinRun(line, style, box.text(), run, mouseX)) {
                        ru.gora.musicbattle.client.ui.LinkActions.openUntrusted(screen, run.url());
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean hit(String id, float baseY, double mouseX, double mouseY) {
        return shift(layout.rect(id), baseY).contains(mouseX, mouseY);
    }

    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // Escape in the composer steps out of reply mode first; only a composer with nothing to cancel lets it
        // through to the screen, which closes the page with it.
        if (keyCode == GLFW.GLFW_KEY_ESCAPE && composer.focused() && replyTo != null) {
            cancelReply();
            return true;
        }
        return composer.focused() && composer.keyPressed(keyCode, scanCode, modifiers);
    }

    /** Back to writing a comment of one's own. */
    private void cancelReply() {
        if (replyTo == null) {
            return;
        }
        replyTo = null;
        replyToAuthor = null;
        onResize.run();
    }

    public boolean charTyped(char character, int modifiers) {
        return composer.focused() && composer.charTyped(character, modifiers);
    }

    public boolean composerFocused() {
        return composer.focused();
    }

    // ---------------------------------------------------------------- data

    public void reload() {
        // The comment being answered may not survive the new page, and a reply mode nobody can see is how the next
        // comment ends up under someone else's.
        cancelReply();
        if (serverMode) {
            requestServerPage(0);
            return;
        }
        loaded.clear();
        cursor = null;
        loadPage(null);
    }

    // ---------------------------------------------------------------- server comments

    /**
     * Opens the server's own comments. The first time on this client it shows the disclaimer the owner asked for
     * (stored on this server only, the operator is responsible, names are unverified on an offline-mode server,
     * reports go to the operators) and remembers the answer in {@code config/music_battle-workshop.json}.
     */
    private void openServerComments() {
        if (client.config().number(SERVER_NOTICE_KEY, 0) < SERVER_NOTICE_VERSION) {
            screen.showDialog(WorkshopConfirmScreen.confirm(screen,
                    Component.translatable(WorkshopStrings.KEY + "comments.server_notice_title"),
                    Component.translatable(WorkshopStrings.KEY + "comments.server_notice_body"),
                    Component.translatable(WorkshopStrings.KEY + "comments.server_notice_ok"), () -> {
                        try {
                            client.config().setNumber(SERVER_NOTICE_KEY, SERVER_NOTICE_VERSION);
                        } catch (java.io.IOException ignored) {
                            // The notice simply shows again next time; nothing else depends on it.
                        }
                        enterServerMode();
                    }));
            return;
        }
        enterServerMode();
    }

    private void enterServerMode() {
        serverMode = true;
        applyComposerMode();
        cancelReply();
        ServerWorkshopClient.setCommentsView(this);
        requestServerPage(0);
    }

    private void requestServerPage(long beforeId) {
        WorkshopServerHooks.ServerComments hook = WorkshopServerHooks.serverComments();
        if (hook == null) {
            return;
        }
        if (beforeId <= 0) {
            serverRows = List.of();
            serverAuthors.clear();
        }
        loading = true;
        serverRequestedMs = System.currentTimeMillis();
        hook.request(serverKey, beforeId <= 0 ? null : beforeId, page -> {
        }, error -> {
            loading = false;
            serverRequestedMs = 0;
        });
    }

    /**
     * Answers the Minecraft server never sends. {@code ServerWorkshopClient} posts a page request and keeps no failure
     * path - it drops the error callback - so an answer lost to a feature that went away, a refused permission or a
     * dropped connection left "Loading comments…" on screen for good, and the control for the next page with it. A
     * post waits on the same socket and strands its own flag the same way, and that flag would then spend itself on
     * the first delete or report that does come back, emptying the composer under the player's hands. After the
     * timeout each says so once and leaves a state the player can act on; the text stays, because a post nobody
     * answered may never have been stored.
     */
    private void expireServerWaits() {
        long now = System.currentTimeMillis();
        if (loading && serverRequestedMs != 0L && now - serverRequestedMs >= SERVER_TIMEOUT_MS) {
            serverRequestedMs = 0L;
            loading = false;
            noServerAnswer();
            onResize.run();
        }
        if (serverPostPending && serverPostAtMs != 0L && now - serverPostAtMs >= SERVER_TIMEOUT_MS) {
            serverPostAtMs = 0L;
            serverPostPending = false;
            noServerAnswer();
        }
    }

    private void noServerAnswer() {
        screen.showMessage(Component.translatable(WorkshopStrings.KEY + "error_title"),
                Component.translatable(WorkshopStrings.KEY + "comments.server_no_answer"));
    }

    @Override
    public void acceptPage(ru.gora.musicbattle.network.ServerCommentsPagePayload payload) {
        if (!payload.key().equals(serverKey)) {
            return;
        }
        loading = false;
        serverRequestedMs = 0L;
        serverPageSeen = true;
        serverTotal = payload.total();
        serverBeforeId = ServerCommentRows.nextBeforeId(serverRows, payload.comments(), payload.nextBeforeId(), serverBeforeId);
        serverCanPost = payload.canPost();
        serverCanModerate = payload.canModerate();
        serverOnlineMode = payload.onlineMode();
        serverNote = payload.note();
        // Two answers to the first page (a Workshop set's page asks twice when it is reopened) used to list every
        // comment twice; rows are kept once by id, newest first.
        serverRows = ServerCommentRows.merge(serverRows, payload.comments());
        rebuildServerRows();
    }

    @Override
    public void acceptResult(ru.gora.musicbattle.network.ServerCommentResultPayload payload) {
        if (!payload.key().equals(serverKey)) {
            return;
        }
        // Delete and report answer with the same payload as a post. Emptying the composer on any of them threw away
        // whatever was half-written the moment a comment was deleted or reported, so only a post of ours does it.
        boolean posted = serverPostPending;
        serverPostPending = false;
        serverPostAtMs = 0L;
        if (!payload.success()) {
            return;
        }
        if (posted) {
            composer.clear();
        }
        requestServerPage(0);
    }

    private void rebuildServerRows() {
        List<CommentsLayout.Row> out = new ArrayList<>(serverRows.size());
        serverAuthors.clear();
        for (var row : serverRows) {
            serverAuthors.put(row.id(), new ru.gora.musicbattle.client.ui.SkinHead.Author(row.authorUuid(),
                    row.authorName(), row.textures().isEmpty() ? null : row.textures(),
                    row.signature().isEmpty() ? null : row.signature()));
            out.add(new CommentsLayout.Row(row.id(), 0, row.authorName(), List.of(), null,
                    row.deleted() ? WorkshopTr.INSTANCE.get(WorkshopStrings.KEY + "comments.server_deleted") : row.text(),
                    WorkshopStrings.relative(WorkshopTr.INSTANCE, java.time.Instant.ofEpochMilli(row.createdAt()).toString(),
                            System.currentTimeMillis()),
                    0, false, false, false, !row.deleted() && (row.own() || serverCanModerate),
                    !row.deleted() && !row.own(), 0, 0, row.deleted(), false, null));
        }
        rows = List.copyOf(out);
        // Rows that were never measured draw as nothing: the layout is built from the list, so it has to be rebuilt
        // whenever the list changes. This used to be an empty runnable.
        onResize.run();
    }

    /**
     * The line under the composer: where these comments live, plus the offline-mode hint. It used to be handed over
     * as the reason the composer was closed, so it was drawn as a button labelled "Stored on this server only" that
     * opened the Workshop sign-in, and it never appeared at all while posting was allowed.
     */
    private String serverNoteText() {
        String note = WorkshopTr.INSTANCE.get(WorkshopStrings.KEY + "comments.server_local_only");
        return serverOnlineMode ? note
                : note + " " + WorkshopTr.INSTANCE.get(WorkshopStrings.KEY + "comments.server_names_unverified");
    }

    /** Why this server's composer is closed: its own refusal code when it sent one, otherwise a plain sentence. */
    private String serverCannotPostReason() {
        return serverNote.isEmpty() ? WorkshopTr.INSTANCE.get(WorkshopStrings.KEY + "comments.server_cannot_post")
                : WorkshopText.error(serverNote).getString();
    }

    private void loadPage(String pageCursor) {
        if (loading || !client.networkAllowed()) {
            return;
        }
        loading = true;
        screen.run(() -> {
            try {
                WorkshopDtos.CommentsPage page = client.session().signedIn()
                        ? client.session().authorized(api -> api.comments("beatmapset", setId, 0, sort, pageCursor, CancelToken.none()))
                        : client.api().comments("beatmapset", setId, 0, sort, pageCursor, CancelToken.none());
                deliver(() -> applyPage(page, pageCursor == null));
            } catch (WorkshopApiException exception) {
                deliver(() -> {
                    loading = false;
                    if (!WorkshopApiException.NOT_SIGNED_IN.equals(exception.code())) {
                        screen.showError(exception.code());
                    }
                });
            }
        });
    }

    private void applyPage(WorkshopDtos.CommentsPage page, boolean reset) {
        if (serverMode) {
            // The reader moved to the server's list while this page was on its way; writing it in would replace the
            // rows of the list they are looking at with the ones of the list they left.
            return;
        }
        loading = false;
        if (reset) {
            loaded = new ArrayList<>();
            included = new ArrayList<>();
            users.clear();
            votes.clear();
            voting.clear();
            bodyLinks.clear();
            replyCursors.clear();
            repliesLoading.clear();
            repliesDone.clear();
        }
        meta = page.meta();
        total = page.total();
        cursor = page.cursor();
        hasMore = page.hasMore();
        remaining = page.remaining();
        if (page.users() != null) {
            for (WorkshopDtos.CommentUser user : page.users()) {
                users.put(user.id(), user);
            }
        }
        if (page.userVotes() != null) {
            votes.addAll(page.userVotes());
        }
        List<WorkshopDtos.Comment> all = new ArrayList<>();
        if (page.pinned() != null) {
            all.addAll(page.pinned());
        }
        if (page.comments() != null) {
            all.addAll(page.comments());
        }
        loaded.addAll(all);
        if (page.included() != null) {
            included.addAll(page.included());
        }
        rememberReplyCursors(page);
        rebuildRows();
        checkRules();
    }

    /** Where each comment's replies carry on, as the page reports it; the map is keyed by the comment id as text. */
    private void rememberReplyCursors(WorkshopDtos.CommentsPage page) {
        if (page.replyCursors() == null) {
            return;
        }
        for (Map.Entry<String, String> entry : page.replyCursors().entrySet()) {
            if (entry.getValue() == null || entry.getValue().isEmpty()) {
                continue;
            }
            try {
                replyCursors.put(Long.parseLong(entry.getKey().trim()), entry.getValue());
            } catch (NumberFormatException ignored) {
                // A key that is not a comment id is not ours to use; the row simply asks from the start instead.
            }
        }
    }

    /** Flattens the page into rows: top level, then its replies two levels deep (the API loads no more). */
    private void rebuildRows() {
        Map<Long, List<WorkshopDtos.Comment>> children = new LinkedHashMap<>();
        for (WorkshopDtos.Comment comment : included) {
            children.computeIfAbsent(comment.parentId() == null ? 0L : comment.parentId(), id -> new ArrayList<>()).add(comment);
        }
        rowAuthors.clear();
        List<CommentsLayout.Row> out = new ArrayList<>();
        for (WorkshopDtos.Comment comment : loaded) {
            addRow(out, comment, 0, children);
        }
        rows = List.copyOf(out);
        // A page that arrives after the section was last measured has to be measured now, or none of it is drawn.
        onResize.run();
    }

    private void addRow(List<CommentsLayout.Row> out, WorkshopDtos.Comment comment, int depth,
                        Map<Long, List<WorkshopDtos.Comment>> children) {
        WorkshopDtos.CommentUser user = comment.userId() == null ? null : users.get(comment.userId());
        if (comment.userId() != null) {
            rowAuthors.put(comment.id(), comment.userId());
        }
        HtmlText.Result body = HtmlText.parse(comment.bodyHtml());
        bodyLinks.put(comment.id(), body.anchors());
        List<String> badges = new ArrayList<>();
        if (comment.userId() != null && comment.userId() == ownerId) {
            badges.add(WorkshopStrings.upper(WorkshopTr.INSTANCE.get(WorkshopStrings.KEY + "comments.badge.mapper")));
        }
        if (user != null && user.badges() != null) {
            for (String badge : user.badges()) {
                // In the player's language; a role the site adds later is left out rather than shown as its code.
                String role = WorkshopStrings.roleBadge(WorkshopTr.INSTANCE, badge);
                if (role != null) {
                    badges.add(role);
                }
            }
        }
        WorkshopDtos.CommentCan can = comment.can();
        boolean own = client.session().state().account() != null && comment.userId() != null
                && client.session().state().account().id() == comment.userId();
        String parentName = null;
        if (comment.parentId() != null && depth > 0) {
            // A parent the page did not bring used to read "↩ ?", which names nobody; a reply whose parent is a
            // reply is the usual case of it, and those live in `included` rather than in the top level.
            WorkshopDtos.Comment parent = find(comment.parentId());
            WorkshopDtos.CommentUser parentUser = parent == null || parent.userId() == null ? null : users.get(parent.userId());
            if (parentUser != null) {
                parentName = "↩ " + parentUser.username();
            } else if (parent != null && parent.userId() == null) {
                parentName = "↩ " + WorkshopTr.INSTANCE.get(WorkshopStrings.KEY + "comments.deleted_user");
            }
        }
        List<WorkshopDtos.Comment> replies = children.getOrDefault(comment.id(), List.of());
        boolean deeper = depth >= 2 && comment.repliesCount() > 0;
        // The rows below already show as many replies as the page brought; the rest are what the control offers, and
        // a comment the site has nothing more for offers none at all.
        int repliesTotal = repliesDone.contains(comment.id()) ? replies.size()
                : Math.max(replies.size(), comment.repliesCount());
        out.add(new CommentsLayout.Row(comment.id(), depth,
                user == null ? WorkshopTr.INSTANCE.get(WorkshopStrings.KEY + "comments.deleted_user") : user.username(), badges,
                parentName, body.text(),
                WorkshopStrings.relative(WorkshopTr.INSTANCE, comment.createdAt(), System.currentTimeMillis()),
                comment.votesCount(), votes.contains(comment.id()), can != null && can.vote() && !own,
                can != null && can.reply() && depth < 2, can != null && can.delete(),
                can != null && can.report() && !own, replies.size(), repliesTotal, comment.deleted(), deeper,
                comment.hidden()));
        if (depth < 2) {
            for (WorkshopDtos.Comment reply : replies) {
                addRow(out, reply, depth + 1, children);
            }
        }
    }

    private WorkshopDtos.Comment find(long id) {
        for (WorkshopDtos.Comment comment : loaded) {
            if (comment.id() == id) {
                return comment;
            }
        }
        for (WorkshopDtos.Comment comment : included) {
            if (comment.id() == id) {
                return comment;
            }
        }
        return null;
    }

    /**
     * The next page of one comment's replies ({@code GET /comments} with a {@code parentId}). A page brings only the
     * first few replies of each comment, and the rest had no way at all of reaching the screen.
     */
    private void loadReplies(CommentsLayout.Row row) {
        if (serverMode || !client.networkAllowed() || !repliesLoading.add(row.id())) {
            return;
        }
        long parent = row.id();
        String pageCursor = replyCursors.get(parent);
        screen.run(() -> {
            try {
                WorkshopDtos.CommentsPage page = client.session().signedIn()
                        ? client.session().authorized(api -> api.comments("beatmapset", setId, parent, sort, pageCursor,
                                CancelToken.none()))
                        : client.api().comments("beatmapset", setId, parent, sort, pageCursor, CancelToken.none());
                deliver(() -> applyReplies(parent, page));
            } catch (WorkshopApiException exception) {
                deliver(() -> {
                    repliesLoading.remove(parent);
                    if (!WorkshopApiException.NOT_SIGNED_IN.equals(exception.code())) {
                        screen.showError(exception.code());
                    }
                });
            }
        });
    }

    private void applyReplies(long parent, WorkshopDtos.CommentsPage page) {
        repliesLoading.remove(parent);
        if (serverMode) {
            // These replies belong to the Workshop list the reader left; see applyPage.
            return;
        }
        if (page.users() != null) {
            for (WorkshopDtos.CommentUser user : page.users()) {
                users.put(user.id(), user);
            }
        }
        if (page.userVotes() != null) {
            votes.addAll(page.userVotes());
        }
        Set<Long> known = new HashSet<>();
        for (WorkshopDtos.Comment comment : loaded) {
            known.add(comment.id());
        }
        for (WorkshopDtos.Comment comment : included) {
            known.add(comment.id());
        }
        // The replies themselves, then whatever they in turn had room for: both are descendants this page can place,
        // and without a cursor the site answers with the first replies again, which are already rows here.
        int added = merge(page.comments(), known) + merge(page.included(), known);
        if (page.hasMore() && page.cursor() != null && !page.cursor().isEmpty() && added > 0) {
            replyCursors.put(parent, page.cursor());
        } else {
            replyCursors.remove(parent);
            repliesDone.add(parent);
        }
        rememberReplyCursors(page);
        rebuildRows();
    }

    /** Adds the comments of {@code page} this view does not hold yet; returns how many were new. */
    private int merge(List<WorkshopDtos.Comment> comments, Set<Long> known) {
        if (comments == null) {
            return 0;
        }
        int added = 0;
        for (WorkshopDtos.Comment comment : comments) {
            if (known.add(comment.id())) {
                included.add(comment);
                added++;
            }
        }
        return added;
    }

    private void post() {
        String body = composer.value().trim();
        if (body.isEmpty()) {
            return;
        }
        if (serverMode) {
            WorkshopServerHooks.ServerComments hook = WorkshopServerHooks.serverComments();
            if (hook == null) {
                return;
            }
            if (serverPostPending) {
                // One post at a time. A press before the answer sent the whole comment again, and the server either
                // refused it as too fast - telling the player off for a comment they pressed once - or, where the
                // operator set no wait between comments, stored it twice.
                return;
            }
            serverPostPending = true;
            serverPostAtMs = System.currentTimeMillis();
            hook.post(serverKey, body.length() > MAX_SERVER_BODY ? body.substring(0, MAX_SERVER_BODY) : body, error -> {
                if (error != null && !error.isEmpty()) {
                    // A refused post keeps its text: the answer arrives as a failure and clears the flag with it.
                    serverPostPending = false;
                    serverPostAtMs = 0L;
                }
            });
            return;
        }
        if (!client.session().signedIn()) {
            screen.openAccount();
            return;
        }
        Long parent = replyTo;
        screen.run(() -> {
            try {
                client.session().authorized(api -> api.comment(setId, parent, body, CancelToken.none()));
                deliver(() -> {
                    composer.clear();
                    reload();
                });
            } catch (WorkshopApiException exception) {
                // Terms section 3.1: the first comment asks for the acceptance, then posts itself.
                if (!screen.handleRulesRefusal(exception.code(), this::post)) {
                    screen.showError(exception.code());
                }
            }
        });
    }

    /** Whether this comment is the viewer's own, which is the one refusal the client can be sure of. */
    private boolean own(long commentId) {
        Long authorId = rowAuthors.get(commentId);
        var account = client.session().state().account();
        return account != null && authorId != null && account.id() == authorId;
    }

    /** A comment lives either in the top level or among the replies; the new count belongs wherever it is. */
    private void applyVoteCount(long commentId, int count) {
        for (int i = 0; i < loaded.size(); i++) {
            if (loaded.get(i).id() == commentId) {
                loaded.set(i, withVotes(loaded.get(i), count));
            }
        }
        for (int i = 0; i < included.size(); i++) {
            if (included.get(i).id() == commentId) {
                included.set(i, withVotes(included.get(i), count));
            }
        }
    }

    private void vote(CommentsLayout.Row row) {
        if (serverMode) {
            return;
        }
        if (!client.session().signedIn()) {
            screen.openAccount();
            return;
        }
        // Only the one case this side genuinely knows. Everything else - silenced, restricted, comments closed, or
        // simply a page fetched before signing in - is the server's to explain, and guessing "your own comment"
        // here was wrong for all of them.
        if (own(row.id())) {
            screen.showError("own_comment");
            return;
        }
        if (!voting.add(row.id())) {
            return;
        }
        boolean wanted = !row.voted();
        screen.run(() -> {
            try {
                WorkshopDtos.VoteResult result = client.session()
                        .authorized(api -> api.voteComment(row.id(), wanted, CancelToken.none()));
                deliver(() -> {
                    if (result.voted()) {
                        votes.add(row.id());
                    } else {
                        votes.remove(row.id());
                    }
                    applyVoteCount(row.id(), result.votesCount());
                    voting.remove(row.id());
                    rebuildRows();
                });
            } catch (WorkshopApiException exception) {
                deliver(() -> voting.remove(row.id()));
                // A vote on a comment is participation too: the acceptance is asked for once, then the vote goes.
                if (!screen.handleRulesRefusal(exception.code(), () -> vote(row))) {
                    screen.showError(exception.code());
                }
            }
        });
    }

    private static WorkshopDtos.Comment withVotes(WorkshopDtos.Comment comment, int count) {
        return new WorkshopDtos.Comment(comment.id(), comment.parentId(), comment.commentableType(), comment.commentableId(),
                comment.userId(), comment.bodyHtml(), comment.bodyMd(), comment.pinned(), count, comment.repliesCount(),
                comment.createdAt(), comment.editedAt(), comment.editedById(), comment.deleted(), comment.deletedAt(),
                comment.hidden(), comment.can());
    }

    private void confirmDelete(CommentsLayout.Row row) {
        if (serverMode) {
            WorkshopServerHooks.ServerComments hook = WorkshopServerHooks.serverComments();
            if (hook == null) {
                return;
            }
            screen.showDialog(WorkshopConfirmScreen.danger(screen,
                    Component.translatable(WorkshopStrings.KEY + "comments.delete_title"),
                    Component.translatable(WorkshopStrings.KEY + "comments.server_delete_body"),
                    Component.translatable(WorkshopStrings.KEY + "comments.delete"),
                    () -> hook.delete(serverKey, row.id(), error -> {
                    })));
            return;
        }
        screen.showDialog(WorkshopConfirmScreen.danger(screen,
                Component.translatable(WorkshopStrings.KEY + "comments.delete_title"),
                Component.translatable(WorkshopStrings.KEY + "comments.delete_body"),
                Component.translatable(WorkshopStrings.KEY + "comments.delete"), () -> screen.run(() -> {
                    try {
                        client.session().authorized(api -> api.deleteComment(row.id(), CancelToken.none()));
                        deliver(this::reload);
                    } catch (WorkshopApiException exception) {
                        screen.showError(exception.code());
                    }
                })));
    }

    private void openInBrowser(long commentId) {
        ru.gora.musicbattle.client.ui.LinkActions.openTrusted(screen, client.api().pageUrl("/comments/" + commentId));
    }
}

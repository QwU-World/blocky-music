import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.awt.Font;
import java.awt.font.FontRenderContext;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import ru.gora.musicbattle.client.ui.TextMeasurer;
import ru.gora.musicbattle.client.ui.TextStyle;
import ru.gora.musicbattle.client.workshop.layout.AccountForm;
import ru.gora.musicbattle.client.workshop.layout.CardLayout;
import ru.gora.musicbattle.client.workshop.layout.CommentsLayout;
import ru.gora.musicbattle.client.workshop.layout.Filters;
import ru.gora.musicbattle.client.workshop.layout.Form;
import ru.gora.musicbattle.client.workshop.layout.FormLayout;
import ru.gora.musicbattle.client.workshop.layout.Layout;
import ru.gora.musicbattle.client.workshop.layout.ListingLayout;
import ru.gora.musicbattle.client.workshop.layout.Metrics;
import ru.gora.musicbattle.client.workshop.layout.Rect;
import ru.gora.musicbattle.client.workshop.layout.SetPageLayout;
import ru.gora.musicbattle.client.workshop.layout.ShellLayout;
import ru.gora.musicbattle.client.workshop.layout.TextFit;
import ru.gora.musicbattle.client.workshop.layout.Tr;
import ru.gora.musicbattle.client.workshop.layout.WorkshopStrings;

/**
 * The `workshop-screens` regression: the production layout of the Workshop's listing, set page,
 * comments and account view at 320x240, 426x240, 640x360, 854x480 and 1280x720, in English and
 * Russian, with no overlapping boxes, nothing leaving its parent and no text wider than the box it sits in.
 *
 * <p>Off-GPU like `VoteLayoutChecks`: the layout classes are pure, and text is measured with the bundled Nunito TTFs
 * through java.awt instead of Minecraft's font system (AWT is a little wider than Minecraft's TTF provider, so a
 * layout that passes here has room in game).
 */
public final class WorkshopScreensChecks {
    private static final int[][] SIZES = {{320, 240}, {426, 240}, {640, 360}, {854, 480}, {1280, 720}};
    private static final String[] LANGUAGES = {"en_us", "ru_ru"};
    private static final float SLACK = 0.75F;

    private static Path root;
    private static int checks;
    private static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        root = Path.of(args.length > 0 ? args[0] : ".");
        AwtMeasurer measurer = new AwtMeasurer(root.resolve("src/main/resources/assets/music_battle/font"));
        for (String language : LANGUAGES) {
            LangTr tr = new LangTr(root.resolve("src/main/resources/assets/music_battle/lang/" + language + ".json"));
            for (int[] size : SIZES) {
                String context = language + " " + size[0] + "x" + size[1];
                shell(measurer, tr, size[0], size[1], context);
                listing(measurer, tr, size[0], size[1], context);
                cards(measurer, tr, size[0], size[1], context);
                setPage(measurer, tr, size[0], size[1], context);
                comments(measurer, tr, size[0], size[1], context);
                account(measurer, tr, size[0], size[1], context);
            }
        }
        localisation();
        noCyrillicInJava();
        if (!failures.isEmpty()) {
            for (String failure : failures.subList(0, Math.min(25, failures.size()))) {
                System.out.println("FAIL " + failure);
            }
            throw new AssertionError(failures.size() + " / " + checks + " Workshop screen layout failures");
        }
        System.out.println("Workshop screens checks passed: " + checks
                + " (listing, set page, comments, account; 5 sizes, 2 languages)");
        System.out.println("NOT COVERED: real Minecraft font rasterisation, GPU output and input handling");
    }

    // ---------------------------------------------------------------- views

    private static void shell(TextMeasurer measurer, Tr tr, int width, int height, String context) {
        for (ShellLayout.Tab tab : ShellLayout.Tab.values()) {
            for (boolean serverTab : new boolean[] {false, true}) {
                for (String account : new String[] {null, "dragon4ik_the_mapper"}) {
                    ShellLayout.State state = new ShellLayout.State(tab, serverTab, account, tab == ShellLayout.Tab.SERVER,
                            tab == ShellLayout.Tab.SERVER ? tr.get(WorkshopStrings.KEY + "title.set") : null);
                    Layout layout = ShellLayout.compute(width, height, measurer, tr, state);
                    verify(layout, measurer, new Rect(0, 0, width, height), context + " shell " + tab + " server=" + serverTab,
                            true);
                    Rect content = layout.rect(ShellLayout.CONTENT);
                    check(content.w() > 40 && content.h() > 40, context + " shell content area too small: " + content);
                    check(layout.rect("shell.close").right() <= layout.rect(ShellLayout.PANEL).right() + SLACK,
                            context + " close button outside the panel");
                    check(layout.rect(ShellLayout.NOTICE).empty(), context + " a shell without a notice lays one out");
                }
            }
        }
        // A battle's map picker: the vote's clock in the title, and on its Server tab the notice that the battle's
        // list was cut short, read before the first card and never under it.
        for (String title : new String[] {tr.get(WorkshopStrings.KEY + "title.pick") + "  \u00b7  0:42",
                tr.get(WorkshopStrings.KEY + "title.set") + "  \u00b7  0:07"}) {
            for (String notice : new String[] {tr.get("screen.music_battle.vote.truncated"),
                    "A notice long enough to need its second line on every window the Workshop is drawn in, and then some"
                            + " more words, so that the strip has to cut it rather than run under the cards."}) {
                ShellLayout.State state = new ShellLayout.State(ShellLayout.Tab.SERVER, true, "dragon4ik_the_mapper",
                        false, title, notice);
                Layout layout = ShellLayout.compute(width, height, measurer, tr, state);
                String where = context + " picker shell notice=" + notice.length();
                verify(layout, measurer, new Rect(0, 0, width, height), where, true);
                Rect strip = layout.rect(ShellLayout.NOTICE);
                Rect content = layout.rect(ShellLayout.CONTENT);
                check(!strip.empty() && !layout.rect(ShellLayout.NOTICE + ".text.0").empty(), where + " the notice is gone");
                check(strip.y() >= layout.rect(ShellLayout.TABS).bottom() - SLACK, where + " the notice is not under the tabs");
                check(content.y() >= strip.bottom() - SLACK, where + " the list starts under the notice");
                check(content.w() > 40 && content.h() > 40, where + " the notice leaves no room for the list: " + content);
                check(layout.group(ShellLayout.NOTICE + ".text.").size() <= 2, where + " the notice takes more than two lines");
            }
        }
        ShellLayout.State plain = new ShellLayout.State(ShellLayout.Tab.BROWSE, false, null, false, null);
        check(plain.equals(new ShellLayout.State(ShellLayout.Tab.BROWSE, false, null, false, null, null)),
                context + " the shell's old constructor no longer means 'no notice'");
    }

    private static void listing(TextMeasurer measurer, Tr tr, int width, int height, String context) {
        Rect content = contentArea(measurer, tr, width, height);
        for (boolean installed : new boolean[] {false, true}) {
            for (boolean more : new boolean[] {false, true}) {
                for (boolean hasMore : new boolean[] {false, true}) {
                    for (String status : new String[] {"", "empty", "no_matches", "offline", "consent", "loading"}) {
                        int cards = status.equals("empty") || status.equals("no_matches") ? 0 : 9;
                        ListingLayout.State state = new ListingLayout.State(installed, "neurofunk", "community",
                                new LinkedHashSet<>(List.of("library")), 7, 3, "included", new LinkedHashSet<>(List.of("fx")), true,
                                "rating", false, more, false, true, "title", 42L, status, status.isEmpty() ? "" : tr.get(
                                        WorkshopStrings.KEY + "state.offline"), cards, status.equals("loading"), hasMore);
                        Layout layout = ListingLayout.compute(content, measurer, tr, state);
                        verify(layout, measurer, new Rect(0, 0, content.w(), Math.max(content.h(), layout.contentHeight())),
                                context + " listing installed=" + installed + " more=" + more + " hasMore=" + hasMore
                                        + " state=" + status, false);
                        check(layout.rect("listing.search.field").w() > 30, context + " search field too narrow");
                        check(layout.contentHeight() > 0, context + " listing has no content height");
                        // A listing with results always says how many of them are loaded and whether more can arrive.
                        check(cards == 0 || !layout.text("listing.results").isEmpty(),
                                context + " listing with cards but no result line");
                        // Advanced filters are on in this state, so a collapsed panel must not read like a plain one.
                        check(installed || more
                                        || !layout.text("listing.filters.more.text").equals(tr.get(WorkshopStrings.KEY + "filter.more")),
                                context + " collapsed filters hide that they are still filtering");
                    }
                }
            }
        }
    }

    private static void cards(TextMeasurer measurer, Tr tr, int width, int height, String context) {
        Rect content = contentArea(measurer, tr, width, height);
        int columns = ListingLayout.columns(content.w());
        float cardWidth = (content.w() - Metrics.CARD_GAP * (columns - 1)) / columns;
        for (boolean extra : new boolean[] {false, true}) {
            for (boolean hover : new boolean[] {false, true}) {
                for (boolean downloading : new boolean[] {false, true}) {
                    CardLayout.State state = new CardLayout.State(extra, hover, hover, false,
                            "An extremely long beatmap title that has to be cut", "Neko Hacker feat. Nanahira",
                            tr.get(WorkshopStrings.KEY + "card.mapped_by", "dragon4ik"), "Original soundtrack",
                            WorkshopStrings.status(tr, "featured"),
                            List.of(WorkshopStrings.upper(tr.get(WorkshopStrings.KEY + "badge.explicit")),
                                    WorkshopStrings.upper(tr.get(WorkshopStrings.KEY + "badge.library"))),
                            List.of(1.2, 2.5, 3.9, 5.6, 7.2), "1.2K downloads  88 favourites  5 days ago",
                            hover ? WorkshopStrings.upper(tr.get(WorkshopStrings.KEY + "badge.update")) : null, true, true,
                            hover ? 5 : 0, downloading);
                    Rect rect = new Rect(0, 0, cardWidth, extra ? Metrics.CARD_EXTRA_H : Metrics.CARD_NORMAL_H);
                    Layout layout = CardLayout.compute(rect, measurer, tr, state);
                    verify(layout, measurer, new Rect(-1, -1, cardWidth + 2, rect.h() + Metrics.CARD_DROPDOWN_MAX + 2),
                            context + " card extra=" + extra + " hover=" + hover + " downloading=" + downloading, false);
                    check(layout.rect("card.title").w() > 8, context + " card title has no room");
                    Rect rightBadge = layout.rect("card.right_badge");
                    if (!rightBadge.empty()) {
                        // The right badge sits on the title's line, so the title and its own badges must stop short.
                        check(layout.rect("card.title").right() <= rightBadge.x(), context + " card title runs under the right badge");
                        for (int i = 0; !layout.rect("card.badge." + i).empty(); i++) {
                            check(layout.rect("card.badge." + i).right() <= rightBadge.x(),
                                    context + " card badge " + i + " runs under the right badge");
                        }
                    }
                    if (!downloading) {
                        check(!layout.rect("card.status").empty(), context + " card without a status pill");
                    }
                }
            }
        }
    }

    private static void setPage(TextMeasurer measurer, Tr tr, int width, int height, String context) {
        Rect content = contentArea(measurer, tr, width, height);
        for (boolean rateable : new boolean[] {false, true}) {
            // `refused` is the page of a set the site will not serve any more: its Install button is drawn disabled
            // with no size under it, the rating box carries the refusal in place of its input, and a description
            // flagged for spoilers is folded away behind a reveal button instead of being shown outright.
            for (boolean refused : new boolean[] {false, true}) {
                SetPageLayout.State state = new SetPageLayout.State(
                        "Organic's Decay (Cut Ver.) \u2014 an unusually long title for the layout",
                        "XenjeS feat. a very long artist name",
                        List.of(new SetPageLayout.Badge("explicit", "EXPLICIT"), new SetPageLayout.Badge("ai", "AI"),
                                new SetPageLayout.Badge("library", "LIBRARY")),
                        WorkshopStrings.status(tr, "featured"),
                        "Insane", List.of(1.4, 2.8, 4.2, 6.7), tr.get(WorkshopStrings.KEY + "card.mapped_by", "dragon4ik"),
                        tr.get(WorkshopStrings.KEY + "set.submitted", "3 months ago"),
                        tr.get(WorkshopStrings.KEY + "set.updated", "5 days ago"),
                        WorkshopStrings.plural(tr, WorkshopStrings.KEY + "card.downloads", 1) + "   "
                                + WorkshopStrings.plural(tr, WorkshopStrings.KEY + "card.favourites", 1),
                        tr.get(WorkshopStrings.DATA + "availability.audio_removed"),
                        tr.get(WorkshopStrings.KEY + (refused ? "set.unavailable" : "set.install")),
                        refused ? null : "7.0 MiB", !refused, true, true, false, 0F,
                        "A long description that wraps over several lines so the info section has to grow, with a link "
                                + "https://blocky-music.qwu.world/beatmapsets/42 inside it.",
                        refused,
                        "Thanks to everyone who tested this map before release.", "Featured because the chart is excellent.",
                        List.of(new SetPageLayout.Stat(tr.get(WorkshopStrings.KEY + "set.genre"),
                                        WorkshopStrings.genre(tr, 7)),
                                new SetPageLayout.Stat(tr.get(WorkshopStrings.KEY + "set.language"),
                                        WorkshopStrings.language(tr, 13)),
                                new SetPageLayout.Stat(tr.get(WorkshopStrings.KEY + "set.tags"), "neurofunk dnb drum and bass")),
                        List.of(new SetPageLayout.Stat(tr.get(WorkshopStrings.KEY + "set.length"), "4:22"),
                                new SetPageLayout.Stat(tr.get(WorkshopStrings.KEY + "set.bpm"), "174"),
                                new SetPageLayout.Stat(tr.get(WorkshopStrings.KEY + "set.notes"), "1079"),
                                new SetPageLayout.Stat(tr.get(WorkshopStrings.KEY + "set.holds"), "83")),
                        List.of(new SetPageLayout.Attribute(tr.get(WorkshopStrings.KEY + "set.attr.stars"), "6.72", 0.67F),
                                new SetPageLayout.Attribute(tr.get(WorkshopStrings.KEY + "set.attr.density"), "9.8", 0.5F),
                                new SetPageLayout.Attribute(tr.get(WorkshopStrings.KEY + "set.attr.holds"), "12%", 0.12F),
                                new SetPageLayout.Attribute(tr.get(WorkshopStrings.KEY + "set.attr.effects"), "12", 0.24F)),
                        "8.21", List.of(0, 0, 1, 0, 2, 1, 3, 4, 2, 1), "4", "10", rateable ? 7 : null, rateable,
                        rateable ? null : tr.get(WorkshopStrings.KEY + "set.cannot.own_set"),
                        tr.get(WorkshopStrings.KEY + "set.audio.hosted", "4:22"),
                        List.of(new SetPageLayout.ChartRow("easy", "Easy", 1.4, "320", "12", "4", "4:22"),
                                new SetPageLayout.ChartRow("insane", "Insane with a long difficulty name", 6.7, "1079", "83", "12",
                                        "4:22")),
                        false, false, 1);
                Layout layout = SetPageLayout.compute(content, measurer, tr, state);
                verify(layout, measurer, new Rect(0, 0, content.w(), Math.max(content.h(), layout.contentHeight())),
                        context + " set page rateable=" + rateable + " refused=" + refused, false);
                check(layout.contentHeight() > 60, context + " set page too short");
                check(refused != layout.rect("set.spoiler.reveal").empty(),
                        context + " a spoiler description has no reveal button");
                check(refused == (layout.box("set.desc.0") == null),
                        context + " a spoiler description is laid out anyway");
                check(rateable == (layout.box("set.rating.cannot.0") == null),
                        context + " a closed rating box does not say why");
                // The primary button's second line ("Choose this map" over the difficulty, Install over the size)
                // sits wholly inside it, with its descenders clear of the bottom edge.
                Rect install = layout.rect("set.install");
                Rect sub = layout.rect("set.install.sub");
                check(sub.empty() || sub.bottom() + 1F <= install.bottom(),
                        context + " the install button cuts its second line: " + sub + " in " + install);
                // In two columns the cover behind the header runs down beside the whole right column; a header
                // shorter than it left a black band under the cover.
                Rect right = layout.rect(SetPageLayout.RIGHT);
                Rect header = layout.rect(SetPageLayout.HEADER);
                if (right.x() > 0F) {
                    check(header.bottom() + 0.5F >= right.bottom(),
                            context + " the header ends above the right column: " + header + " vs " + right);
                }
            }
        }
    }

    private static void comments(TextMeasurer measurer, Tr tr, int width, int height, String context) {
        Rect content = contentArea(measurer, tr, width, height);
        List<CommentsLayout.Row> rows = new ArrayList<>();
        for (int depth = 0; depth <= 2; depth++) {
            // Two replies of the top comment are rows of their own and three more are still on the site: that is the
            // difference the "more replies" control offers, and it is the only reason the control is laid out.
            rows.add(new CommentsLayout.Row(100 + depth, depth, "a_very_long_player_name",
                    List.of(WorkshopStrings.upper(tr.get(WorkshopStrings.KEY + "comments.badge.mapper")), "MOD"),
                    depth == 0 ? null : "↩ someone_else",
                    "A comment body that is long enough to wrap over several lines in every language, with a link to "
                            + "https://qwu.world and some more words after it.",
                    WorkshopStrings.relative(tr, "2026-09-10T10:00:00Z", 1_760_000_000_000L), 12, depth == 1, true, true,
                    depth == 0, true, depth == 0 ? 2 : 0, depth == 0 ? 5 : 0, false, depth == 2, null));
        }
        // A comment a moderator removed and one held back for review: both arrive from the site with no body at all,
        // and used to be laid out as a name, a time and nothing between them.
        rows.add(new CommentsLayout.Row(200, 0, "removed_by_staff", List.of(), null, "",
                WorkshopStrings.relative(tr, "2026-09-11T10:00:00Z", 1_760_000_000_000L), 0, false, false, false, false,
                false, 0, 0, false, false, "moderator"));
        rows.add(new CommentsLayout.Row(201, 1, "held_for_review", List.of(), "↩ removed_by_staff", "",
                WorkshopStrings.relative(tr, "2026-09-11T11:00:00Z", 1_760_000_000_000L), 0, false, false, false, false,
                false, 0, 0, false, false, "pending_review"));
        for (boolean signedIn : new boolean[] {false, true}) {
            // Signed in, the composer also carries the line offering the one-time acceptance (Terms section 3.1), the
            // chip naming who is being answered and the counter for the length this list takes.
            CommentsLayout.State state = new CommentsLayout.State(21, "new", signedIn, signedIn ? null : "sign_in", signedIn,
                    rows, true, 7, false, true, false, tr.get(WorkshopStrings.KEY + "comments.placeholder"), 24F, signedIn,
                    null, signedIn ? "a_very_long_player_name" : null, signedIn ? 4800 : 0, 5000);
            Layout layout = CommentsLayout.compute(new Rect(0, 0, content.w(), content.h()), measurer, tr, state);
            verify(layout, measurer, new Rect(0, 0, content.w(), Math.max(content.h(), layout.contentHeight())),
                    context + " comments signedIn=" + signedIn, false);
            check(signedIn != layout.rect("comments.composer.replying.cancel").empty(),
                    context + " reply mode has no way out of it");
        }
        // The server's own comments: the count belongs to the tab that is open, the line saying
        // where they live is a sentence under the composer, and a closed composer is not a Workshop sign-in button.
        for (boolean canPost : new boolean[] {false, true}) {
            CommentsLayout.State state = new CommentsLayout.State(12, "new", canPost,
                    canPost ? null : tr.get(WorkshopStrings.KEY + "comments.server_cannot_post"), true, rows, true, 4, false,
                    true, true, tr.get(WorkshopStrings.KEY + "comments.server_placeholder"), 24F, false,
                    tr.get(WorkshopStrings.KEY + "comments.server_local_only") + " "
                            + tr.get(WorkshopStrings.KEY + "comments.server_names_unverified"),
                    null, canPost ? 490 : 0, 500);
            Layout layout = CommentsLayout.compute(new Rect(0, 0, content.w(), content.h()), measurer, tr, state);
            verify(layout, measurer, new Rect(0, 0, content.w(), Math.max(content.h(), layout.contentHeight())),
                    context + " server comments canPost=" + canPost, false);
            check(layout.rect("comments.composer.signin").empty(),
                    context + " the server's comments offer a Workshop sign-in");
            check(layout.box("comments.composer.note.0") != null,
                    context + " the server's comments do not say where they live");
        }
    }

    private static void account(TextMeasurer measurer, Tr tr, int width, int height, String context) {
        Rect content = contentArea(measurer, tr, width, height);
        // The profile card as the server answers it: a curator with maps, favourites and comments behind them, and a
        // cover to sit behind the name. The card is measured in both widths, like everything else here.
        AccountForm.Card card = new AccountForm.Card(
                List.of(new Form.Chip("mod", tr.get(WorkshopStrings.KEY + "account.role.mod")),
                        new Form.Chip("curator", tr.get(WorkshopStrings.KEY + "account.role.curator"))),
                tr.get(WorkshopStrings.KEY + "account.profile.since", "September 2026"),
                List.of(new Form.Stat("14", tr.get(WorkshopStrings.KEY + "account.stat.maps")),
                        new Form.Stat("3", tr.get(WorkshopStrings.KEY + "account.stat.featured")),
                        new Form.Stat("1284", tr.get(WorkshopStrings.KEY + "account.stat.favourites")),
                        new Form.Stat("96", tr.get(WorkshopStrings.KEY + "account.stat.comments"))),
                true);
        for (AccountForm.Mode mode : AccountForm.Mode.values()) {
          for (boolean network : new boolean[] {true, false}) {
            AccountForm.State state = new AccountForm.State(mode, true, true, "BDFG-HJKL",
                    tr.get(WorkshopStrings.KEY + "account.waiting"), tr.get(WorkshopStrings.KEY + "account.not_linked"),
                    "dragon4ik", "Dragon4ik the mapper",
                    List.of(new Form.Row("account.mc.1", "Dragon4ik", tr.get(WorkshopStrings.KEY + "account.link.auto"))),
                    true, true, List.of("Your beatmap \"Organic's Decay\" was approved.", "ohAngel replied to your comment."),
                    tr.get(WorkshopStrings.KEY + "account.standing.strikes", "1"), 2, false,
                    // The rules row with both of its buttons: accepted a while ago, and one of the documents has moved.
                    tr.get(WorkshopStrings.KEY + "account.rules.on",
                            WorkshopStrings.relative(tr, "2026-09-10T10:00:00Z", 1_760_000_000_000L)),
                    tr.get(WorkshopStrings.KEY + "account.rules.changed_with",
                            "Version 2 spells out what happens after a takedown."),
                    true, card, network);
            Layout layout = FormLayout.compute(content, measurer, tr, AccountForm.build(tr, state));
            String where = context + " account " + mode + " network=" + network;
            verify(layout, measurer, new Rect(0, 0, content.w(), Math.max(content.h(), layout.contentHeight())), where, false);
            // The Network access switch (claims-vs-code F1): after Allow it is the way to turn access off again, so it is
            // on the page signed in and signed out, in both states, with its whole label and its note.
            boolean offered = mode == AccountForm.Mode.SIGNED_OUT || mode == AccountForm.Mode.SIGNED_IN;
            check(layout.has(AccountForm.NETWORK) == offered, where + " the Network access switch "
                    + (offered ? "is missing" : "shows during a code or link wait"));
            if (offered) {
                Rect toggle = layout.rect(AccountForm.NETWORK);
                check(!toggle.empty() && toggle.w() > 40, where + " the Network access switch has no room: " + toggle);
                check(!layout.rect("account.network.heading.0").empty() && !layout.rect("account.network.note.0").empty(),
                        where + " the Network access heading or note is gone");
                String label = tr.get(WorkshopStrings.KEY + "account.network.toggle");
                float room = layout.rect(AccountForm.NETWORK).w() - layout.rect(AccountForm.NETWORK + ".box").w() - 4;
                int lines = layout.group(AccountForm.NETWORK + ".label").size();
                check(lines >= 1 && lines <= 2, where + " the switch label takes " + lines + " lines");
                check(measurer.width(label, TextStyle.CAPTION) <= room * lines + SLACK, where + " the switch label does not fit");
            }
          }
        }
    }

    private static Rect contentArea(TextMeasurer measurer, Tr tr, int width, int height) {
        Layout shell = ShellLayout.compute(width, height, measurer, tr,
                new ShellLayout.State(ShellLayout.Tab.BROWSE, false, "dragon4ik", false, null));
        return shell.rect(ShellLayout.CONTENT);
    }

    // ---------------------------------------------------------------- the rules

    /**
     * Every box lies inside its parent and inside the view, no two siblings overlap, and every string fits the box it
     * was measured for.
     */
    private static void verify(Layout layout, TextMeasurer measurer, Rect view, String context, boolean strictHeight) {
        List<Layout.Box> boxes = layout.boxes();
        Map<String, Rect> byId = new HashMap<>();
        for (Layout.Box box : boxes) {
            byId.put(box.id(), box.rect());
        }
        for (Layout.Box box : boxes) {
            Rect rect = box.rect();
            check(rect.w() >= 0 && rect.h() >= 0, context + " negative size: " + box.id());
            check(rect.x() >= view.x() - SLACK && rect.right() <= view.right() + SLACK,
                    context + " box leaves the view horizontally: " + box.id() + " " + rect + " in " + view);
            if (strictHeight) {
                check(rect.y() >= view.y() - SLACK && rect.bottom() <= view.bottom() + SLACK,
                        context + " box leaves the view vertically: " + box.id() + " " + rect);
            }
            if (!box.parent().isEmpty()) {
                Rect parent = byId.get(box.parent());
                check(parent != null, context + " unknown parent " + box.parent() + " of " + box.id());
                if (parent != null) {
                    check(rect.inside(parent, SLACK + 1F),
                            context + " box outside its parent: " + box.id() + " " + rect + " not in " + box.parent() + " " + parent);
                }
            }
            if (box.kind() == Layout.Kind.TEXT && box.text() != null && !box.text().isEmpty()) {
                float measured = TextFit.width(measurer, box.text(), box.style());
                check(measured <= rect.w() + 0.75F,
                        context + " text wider than its box: " + box.id() + " '" + box.text() + "' " + measured + " > " + rect.w());
                check(rect.h() + 0.01F >= box.style().capHeight(),
                        context + " text box shorter than its cap height: " + box.id());
            }
        }
        for (int i = 0; i < boxes.size(); i++) {
            Layout.Box a = boxes.get(i);
            if (skipOverlap(a)) {
                continue;
            }
            for (int j = i + 1; j < boxes.size(); j++) {
                Layout.Box b = boxes.get(j);
                if (skipOverlap(b) || !a.parent().equals(b.parent())) {
                    continue;
                }
                check(!a.rect().overlaps(b.rect(), SLACK),
                        context + " overlap: " + a.id() + " " + a.rect() + " and " + b.id() + " " + b.rect());
            }
        }
    }

    private static boolean skipOverlap(Layout.Box box) {
        return box.floating() || box.kind() == Layout.Kind.PANEL || box.kind() == Layout.Kind.SLOT;
    }

    // ---------------------------------------------------------------- localisation and source rules

    /** Every Workshop key exists in both languages with the same placeholders, and Russian is not left in English. */
    private static void localisation() throws Exception {
        JsonObject en = json(root.resolve("src/main/resources/assets/music_battle/lang/en_us.json"));
        JsonObject ru = json(root.resolve("src/main/resources/assets/music_battle/lang/ru_ru.json"));
        Pattern placeholder = Pattern.compile("%(?:\\d+\\$)?[+\\-0-9.]*[sdfX%]");
        int workshopKeys = 0;
        for (Map.Entry<String, JsonElement> entry : en.entrySet()) {
            String key = entry.getKey();
            if (!key.startsWith(WorkshopStrings.KEY) && !key.startsWith(WorkshopStrings.DATA)) {
                continue;
            }
            workshopKeys++;
            check(ru.has(key), "missing Russian translation: " + key);
            if (!ru.has(key)) {
                continue;
            }
            String english = entry.getValue().getAsString();
            String russian = ru.get(key).getAsString();
            check(!english.matches("(?s).*[\\u0410-\\u044F\\u0401\\u0451].*"), "Russian text in the English value: " + key);
            check(placeholders(placeholder, english).equals(placeholders(placeholder, russian)),
                    "placeholder mismatch: " + key + " (" + english + " / " + russian + ")");
            if (!key.endsWith("plural_rule")) {
                check(!russian.equals(english) || english.length() <= 3 || english.matches("[0-9A-Za-z .%#-]{0,6}"),
                        "Russian value is the English one: " + key + " = " + russian);
            }
        }
        check(workshopKeys > 300, "the Workshop keys are there (" + workshopKeys + ")");
        check("ru".equals(ru.get(WorkshopStrings.KEY + "plural_rule").getAsString()), "Russian plural rule is set");
    }

    private static Set<String> placeholders(Pattern pattern, String text) {
        Set<String> out = new LinkedHashSet<>();
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            out.add(matcher.group());
        }
        return out;
    }

    /** No Cyrillic in Java literals. */
    private static void noCyrillicInJava() throws Exception {
        List<Path> roots = List.of(root.resolve("src/main/java/ru/gora/musicbattle/client/workshop"),
                root.resolve("src/main/java/ru/gora/musicbattle/client/ui"),
                root.resolve("src/main/java/ru/gora/musicbattle/workshop"));
        // Comments are not UI text: the audit strips them before looking for Cyrillic, and so do we.
        Pattern comments = Pattern.compile("(?s)//[^\\n]*|/\\*.*?\\*/");
        Pattern literal = Pattern.compile("\"(?:\\\\.|[^\"\\\\])*\"");
        for (Path directory : roots) {
            try (var stream = Files.walk(directory)) {
                for (Path file : stream.filter(p -> p.toString().endsWith(".java")).toList()) {
                    String source = comments.matcher(Files.readString(file, StandardCharsets.UTF_8)).replaceAll(" ");
                    Matcher matcher = literal.matcher(source);
                    while (matcher.find()) {
                        String value = matcher.group();
                        check(!value.matches("(?s).*[\\u0410-\\u044F\\u0401\\u0451].*"),
                                "Cyrillic in a Java literal: " + file.getFileName() + " " + value);
                    }
                }
            }
        }
    }

    private static JsonObject json(Path path) throws Exception {
        return JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private static void check(boolean value, String message) {
        checks++;
        if (!value) {
            failures.add(message);
        }
    }

    // ---------------------------------------------------------------- measuring

    /** The bundled Nunito faces through java.awt: the same files Minecraft rasterises, at the same sizes. */
    private static final class AwtMeasurer implements TextMeasurer {
        private final Map<String, Font> faces = new HashMap<>();
        private final Map<String, Float> cache = new HashMap<>();
        private final FontRenderContext context = new FontRenderContext(null, true, true);

        AwtMeasurer(Path fontDirectory) throws Exception {
            for (TextStyle style : TextStyle.values()) {
                Font base = faces.get(style.fontFile());
                if (base == null) {
                    base = Font.createFont(Font.TRUETYPE_FONT, fontDirectory.resolve(style.fontFile() + ".ttf").toFile());
                    faces.put(style.fontFile(), base);
                }
                faces.put(style.name(), base.deriveFont(style.size()));
            }
        }

        @Override
        public float width(String text, TextStyle style) {
            if (text == null || text.isEmpty()) {
                return 0F;
            }
            String key = style.name() + " " + text;
            Float cached = cache.get(key);
            if (cached != null) {
                return cached;
            }
            float width = (float) faces.get(style.name()).getStringBounds(text, context).getWidth();
            cache.put(key, width);
            return width;
        }
    }

    /** Minecraft's language file as the screens see it, with {@code %s} arguments substituted. */
    private static final class LangTr implements Tr {
        private final Map<String, String> strings = new HashMap<>();

        LangTr(Path file) throws Exception {
            JsonObject object = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
                strings.put(entry.getKey(), entry.getValue().getAsString());
            }
        }

        @Override
        public String get(String key, Object... args) {
            String value = strings.get(key);
            if (value == null) {
                return key;
            }
            if (args.length == 0) {
                return value;
            }
            try {
                return String.format(Locale.ROOT, value, args);
            } catch (RuntimeException exception) {
                return value;
            }
        }
    }
}

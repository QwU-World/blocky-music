package ru.gora.musicbattle.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Pattern;
import ru.gora.musicbattle.client.ui.TextMeasurer;
import ru.gora.musicbattle.client.ui.TextStyle;
import ru.gora.musicbattle.client.workshop.layout.AccountForm;
import ru.gora.musicbattle.client.workshop.layout.CardLayout;
import ru.gora.musicbattle.client.workshop.layout.CommentsLayout;
import ru.gora.musicbattle.client.workshop.layout.DialogLayout;
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
import ru.gora.musicbattle.client.workshop.layout.TrackCardLayout;
import ru.gora.musicbattle.client.workshop.layout.Tr;
import ru.gora.musicbattle.client.workshop.layout.UploadLayout;
import ru.gora.musicbattle.client.workshop.layout.WorkshopStrings;

/**
 * The {@code workshop-text} regression: every word the Workshop's own interface draws - its tabs, filter rows and
 * chips, sort options, buttons, pills, headings and labels - is drawn whole, in English and Russian, on every window
 * from 320x240 up and at every GUI scale. Text is measured as Minecraft measures it: its FreeType glyph provider on the
 * bundled Nunito faces at the oversample tier of each GUI scale (GameTextMeasurer), so a label that is a hundredth of a
 * unit too wide at one scale is caught at that scale.
 *
 * <p>The layouts note every string they had to shorten ({@link Layout#cuts()}): cut with an ellipsis, wrapped past its
 * line cap, or broken between two letters because one word was wider than its line. Only the players' own words may be
 * cut - a beatmap's title, an artist, a name, a comment, a file name - and {@link #PLAYER_TEXT} lists those boxes, each
 * with its reason. Any other cut fails, with the window, the GUI scale and the words. Every screen is laid out in the
 * states that change its words: each tab and filter option active and inactive (the active one is bold and wider),
 * every sort, install label, status pill, account mode and upload step, every dialog with its choices, buttons and
 * status lines, and the counters at their widest. The words a view draws outside its layout are held to their room
 * too: the search field's and the comment box's placeholders, and the note across the preview bar of a beatmap whose
 * clip cannot play. English also has to call the feature "Workshop", with a capital. Beyond the words' widths: a dialog's
 * body shows its first and last lines whole and its text field to its bottom edge, their ink measured as the game draws
 * it, and its buttons stay inside the panel; a card hovered is the card at rest plus its strip's buttons and stats line,
 * and counts each of its numbers in its own plural form; a commenter keeps every badge, the name giving way first and
 * no further than they need, and a reply always says whom it answers; and no string about Featured borrows the word
 * for Favourites.
 *
 * <p>The layouts below the shell depend only on the content area's width and on whether the window is compact, so
 * each of those is laid out once per distinct content width; the shell and the upload wizard's pinned bar are laid out
 * for every window, and the dialogs for every width up to the one their panel stops growing at. NOT COVERED: drawing in
 * the game (the screens draw exactly the strings laid out here), a dialog's paragraphs (the game's own splitter wraps
 * them, at spaces), and glyphs Nunito lacks, which the game draws from its default font and this check measures as
 * {@link #MISSING_GLYPH} wide.
 */
public final class WorkshopTextChecks {
    /** A generous width for a code point the bundled face lacks (Minecraft falls back to its own font for those). */
    private static final float MISSING_GLYPH = 9F;
    private static final int[] HEIGHTS = {240, 299, 300, 360};
    /** The keys of every right badge CardRenderer.rightBadgeLabel can give a card. */
    private static final List<String> RIGHT_BADGES = List.of("update", "withdrawn", "audio_removed", "needs_audio",
            ru.gora.musicbattle.client.workshop.CardRenderer.KIND_WORKSHOP);
    /** The checks' tally, merged from every GUI scale and language swept. */
    private static final Tally TOTAL = new Tally();

    /**
     * Boxes that hold what players wrote or named, and only those: a cut here is the honest answer to a title longer
     * than its card. Everything else in the Workshop is the interface's own words and must never be cut.
     */
    private static final Map<Pattern, String> PLAYER_TEXT = new LinkedHashMap<>();

    static {
        player("card\\.title", "a beatmap's title");
        player("card\\.artist", "the artist's name after \"by\"");
        player("card\\.source", "the song's source, as the mapper typed it");
        player("card\\.mapper", "the mapper's name after \"mapped by\"");
        player("set\\.title", "a beatmap's title, two lines at most");
        player("set\\.artist", "the artist's name");
        player("set\\.chartname", "a difficulty's name, as the mapper typed it");
        player("set\\.mapper", "the mapper's name after \"mapped by\"");
        player("set\\.desc", "the mapper's description");
        player("set\\.credits", "the mapper's credits");
        player("set\\.featured_note", "the curator's note");
        player("set\\.meta\\.\\d+\\.value", "the mapper's source and tags");
        player("set\\.chart\\.\\d+\\.name", "a difficulty's name");
        player("comments\\.row\\.\\d+\\.author", "a commenter's name");
        player("comments\\.row\\.\\d+\\.parent", "the name of the commenter a reply answers");
        player("comments\\.row\\.\\d+\\.body", "a comment, which may hold a link longer than its line");
        player("comments\\.composer\\.replying\\.text", "the name the next post answers");
        player("account\\.(card|avatar)\\.(name|handle|detail)", "the account's display name and handle");
        player("account\\.minecraft\\.\\d+\\.label", "a linked Minecraft name");
        player("account\\.notification\\.\\d+", "a notification the site wrote, which may name a beatmap");
        player("shell\\.account\\.name", "the signed-in account's name");
        player("chart\\.\\d+\\.(title|meta)", "a track's title and its id, as the player named them");
        player("audio\\.name", "the audio file's name");
        player("cover\\.name", "the cover file's name");
        player("select\\.(genre|language)\\.value", "the chosen genre or language, data of the beatmap");
        player("hp\\.(title|artist)", "the beatmap's title and artist on the summary card");
        player("result\\.(title|artist)", "the beatmap's title and artist on the result card");
        player("stage\\.\\d+\\.detail\\.\\d+", "a file name and its byte counts while it uploads");
        player("track\\.(title|byline|desc)", "a server track's title, publisher and description");
    }

    private static void player(String id, String why) {
        PLAYER_TEXT.put(Pattern.compile(id), why);
    }

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args.length > 0 ? args[0] : ".").toAbsolutePath().normalize();
        Path fonts = root.resolve("src/main/resources/assets/music_battle/font");
        Path lang = root.resolve("src/main/resources/assets/music_battle/lang");
        Map<String, LangTr> languages = new LinkedHashMap<>();
        languages.put("en", new LangTr(lang.resolve("en_us.json")));
        languages.put("ru", new LangTr(lang.resolve("ru_ru.json")));
        detectorWorks(TOTAL);
        GameTextMeasurer first = new GameTextMeasurer(fonts, TextStyle.MIN_TIER);
        for (Map.Entry<String, LangTr> language : languages.entrySet()) {
            glyphs(TOTAL, first, language.getKey(), language.getValue());
        }
        workshopIsAName(TOTAL, languages.get("en"));
        for (Map.Entry<String, LangTr> language : languages.entrySet()) {
            rolesTranslated(TOTAL, language.getKey(), language.getValue());
        }
        commentsUseRoleWords(TOTAL, root);
        for (Map.Entry<String, LangTr> language : languages.entrySet()) {
            badgeColours(TOTAL, language.getKey(), language.getValue());
        }
        badgesByKey(TOTAL, root);
        for (Map.Entry<String, LangTr> language : languages.entrySet()) {
            cardsCarryEveryRightBadge(TOTAL, language.getKey(), language.getValue());
            cardStatsCounted(TOTAL, language.getKey(), language.getValue());
            featuredIsNotFavourites(TOTAL, language.getKey(), language.getValue());
            serverBeatmapPage(TOTAL, language.getKey(), language.getValue(),
                    measurer(new GameTextMeasurer(fonts, 2)));
        }
        cardsCountInPlurals(TOTAL, root);
        dialogsUseTheirBody(TOTAL, root);
        // Each GUI scale and language is swept on its own measurer, a few at a time: one after another took minutes.
        // Six threads at most, so the check leaves the machine room for whatever else runs on it.
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, Math.min(6, Runtime.getRuntime().availableProcessors() / 2)));
        List<Future<Tally>> sweeps = new ArrayList<>();
        for (int tier = TextStyle.MIN_TIER; tier <= TextStyle.MAX_TIER; tier++) {
            int scale = tier;
            for (Map.Entry<String, LangTr> language : languages.entrySet()) {
                sweeps.add(pool.submit(() -> {
                    Tally tally = new Tally();
                    sweep(tally, measurer(new GameTextMeasurer(fonts, scale)), new Ink(new GlyphInk(fonts, scale), language.getValue()),
                            language.getValue(), language.getKey() + " at GUI scale " + scale);
                    return tally;
                }));
            }
        }
        pool.shutdown();
        for (Future<Tally> sweep : sweeps) {
            TOTAL.add(sweep.get());
        }
        System.out.println("Workshop text: " + TOTAL.layouts + " layouts, " + TOTAL.checks + " checks");
        TOTAL.failures.entrySet().stream().limit(80).forEach(entry -> System.err.println("FAIL: " + entry.getKey()
                + "\n    first " + entry.getValue() + ", " + TOTAL.repeats.get(entry.getKey()) + " times"));
        if (!TOTAL.failures.isEmpty()) {
            throw new AssertionError(TOTAL.failures.size() + " distinct failures in "
                    + TOTAL.repeats.values().stream().mapToInt(Integer::intValue).sum() + " failed checks");
        }
        System.out.println("Workshop text PASS: the shell, Browse, Installed, Server beatmaps, cards, the beatmap page, comments, "
                + "the account, every upload step and every dialog, the fields' placeholders and the preview bar's note, en and ru, "
                + "windows 320x240 to 2560x1440, GUI scales 1-9");
        System.out.println("NOT COVERED: the game's drawing, a dialog's paragraphs (wrapped by the game's own splitter), "
                + "glyphs Nunito lacks (measured as " + MISSING_GLYPH + ")");
    }

    /** Checks run, layouts made and each distinct failure once, with the first window it showed on and its count. */
    private static final class Tally {
        long checks;
        long layouts;
        final Map<String, String> failures = new LinkedHashMap<>();
        final Map<String, Integer> repeats = new HashMap<>();

        void require(boolean condition, String where, String what) {
            checks++;
            if (!condition) {
                failures.putIfAbsent(what, where);
                repeats.merge(what, 1, Integer::sum);
            }
        }

        void add(Tally other) {
            checks += other.checks;
            layouts += other.layouts;
            other.failures.forEach(failures::putIfAbsent);
            other.repeats.forEach((what, count) -> repeats.merge(what, count, Integer::sum));
        }
    }

    // ---------------------------------------------------------------- the sweep

    /** Every window from 320x240: each width to 1280, then every fifth to 2560, at heights either side of compact. */
    private static List<int[]> windows() {
        List<int[]> out = new ArrayList<>();
        for (int width = Integer.getInteger("minw", 320); width <= Integer.getInteger("maxw", 2560); width += width < 1280 ? 1 : 5) {
            for (int height : HEIGHTS) {
                out.add(new int[] {width, height});
            }
        }
        return out;
    }

    private static void sweep(Tally tally, TextMeasurer measurer, Ink ink, LangTr tr, String where) {
        Scenarios scenarios = new Scenarios(tr);
        Set<String> seen = new HashSet<>();
        for (int[] window : windows()) {
            int width = window[0];
            int height = window[1];
            String at = where + " " + width + "x" + height;
            for (ShellLayout.State state : scenarios.shells) {
                verify(tally, ShellLayout.compute(width, height, measurer, tr, state), at + " shell " + state.tab()
                        + (state.serverTab() ? " +server" : "") + (state.back() ? " back" : "") + " '" + state.title() + "'");
            }
            // A dialog's panel depends only on the window's width, and is the same from 404 units up.
            if (seen.add("dialog " + Math.min(width, 420))) {
                for (DialogLayout.State state : scenarios.dialogs) {
                    DialogLayout.Result dialog = DialogLayout.compute(width, measurer, tr, state);
                    String context = at + " dialog '" + state.title() + "'";
                    verify(tally, dialog.layout(), context);
                    buttonsInside(tally, dialog, context);
                    frames(tally, ink, dialog, state.field(), context);
                }
            }
            Rect content = ShellLayout.compute(width, height, measurer, tr, scenarios.shells.get(0)).rect(ShellLayout.CONTENT);
            boolean compact = Metrics.compact(width, height);
            // The upload page and its pinned bar are laid out in the content area, whose height decides how much of
            // it scrolls; everything else depends only on the area's width and on compactness.
            boolean uploadCompact = Metrics.compact(content.w(), content.h());
            for (Variant variant : scenarios.uploads) {
                if (!seen.add("upload " + content.w() + " " + uploadCompact + " " + variant.name())) {
                    continue;
                }
                UploadLayout.Page page = UploadLayout.compute(content.w(), content.h(), measurer, tr, variant.state());
                verify(tally, page.content(), at + " upload " + variant.name());
                verify(tally, page.bar(), at + " upload bar " + variant.name());
            }
            if (!seen.add(content.w() + " " + compact)) {
                continue;
            }
            for (ListingLayout.State state : scenarios.listings) {
                Layout layout = ListingLayout.compute(content, measurer, tr, state);
                verify(tally, layout, at + " listing " + describe(state));
                placeholder(tally, measurer, layout, "listing.search.field", "listing.search.icon",
                        tr.get(WorkshopStrings.KEY + (state.installed() ? "search.installed" : "search.hint")),
                        at + " listing " + describe(state), "listing");
            }
            int columns = ListingLayout.columns(content.w());
            float cardWidth = (content.w() - Metrics.CARD_GAP * (columns - 1)) / columns;
            for (CardLayout.State state : scenarios.cards) {
                Rect card = new Rect(0, 0, cardWidth, state.extra() ? Metrics.CARD_EXTRA_H : Metrics.CARD_NORMAL_H);
                String context = at + " card '" + state.statusLabel() + "' " + state.badges() + " " + state.rightBadge()
                        + (state.hover() ? " hovered" : "");
                Layout layout = CardLayout.compute(card, measurer, tr, state);
                verify(tally, layout, context);
                if (!state.hover()) {
                    sameOnHover(tally, layout, CardLayout.compute(card, measurer, tr, hovered(state)), context);
                }
                cardKeepsBadges(tally, layout, state, context);
            }
            for (TrackCardLayout.State state : scenarios.tracks) {
                Rect card = new Rect(0, 0, cardWidth, state.extra() ? Metrics.CARD_EXTRA_H : Metrics.CARD_NORMAL_H);
                verify(tally, TrackCardLayout.compute(card, measurer, tr, state), at + " server track " + state.badge());
            }
            Rect page = new Rect(0, 0, content.w(), content.h());
            for (SetPageLayout.State state : scenarios.setPages) {
                Layout layout = SetPageLayout.compute(page, measurer, tr, state);
                String context = at + " beatmap page '" + state.installLabel() + "' " + state.statusLabel();
                verify(tally, layout, context);
                // SetPageView writes why a preview cannot play across the preview bar itself, cut to the bar less 8.
                Rect preview = layout.rect("set.preview");
                for (String note : preview.empty() ? List.<String>of() : scenarios.previewNotes) {
                    tally.require(TextFit.fits(measurer, note, TextStyle.CAPTION, preview.w() - 8F), context,
                            "beatmap: the preview bar's note '" + note + "' is cut (set.preview)");
                }
            }
            for (CommentsLayout.State state : scenarios.comments) {
                Layout layout = CommentsLayout.compute(page, measurer, tr, state);
                verify(tally, layout, at + " comments sort=" + state.sort() + " signedIn=" + state.signedIn());
                placeholder(tally, measurer, layout, "comments.composer.field", null, state.composerHint(),
                        at + " comments sort=" + state.sort() + " signedIn=" + state.signedIn(), "comments");
                // A row whose actions do not all fit after its time drops them: that is a missing button, not a cut
                // word, and it must not happen to a row of a page this wide.
                for (int index = 0; index < state.rows().size(); index++) {
                    CommentsLayout.Row row = state.rows().get(index);
                    String id = "comments.row." + index;
                    tally.require(!row.canReply() || layout.has(id + ".reply"), at, "comments: a row loses Reply");
                    tally.require(!row.canReport() || layout.has(id + ".report"), at, "comments: a row loses Report");
                    tally.require(!row.canDelete() || layout.has(id + ".delete"), at, "comments: a row loses Delete");
                    tally.require(!row.deeper() || layout.has(id + ".continue"), at, "comments: a row loses Continue in browser");
                    badgesKept(tally, layout, row, id, at + " comments sort=" + state.sort());
                }
            }
            for (List<Form.Item> form : scenarios.accounts) {
                verify(tally, FormLayout.compute(page, measurer, tr, form), at + " account " + form.get(0).id());
            }
        }
    }

    /** No cut outside the players' own words, and every word of a box inside it. */
    private static void verify(Tally tally, Layout layout, String where) {
        tally.layouts++;
        for (Layout.Cut cut : layout.cuts()) {
            String why = playerText(cut.id());
            if (why == null && onlyIdentifiersBroken(layout.measurer(), cut)) {
                why = "an identifier (a link, a hash) wider than its line, wrapped by character with nothing left out";
            }
            tally.require(why != null, where + ", drawn as '" + cut.shown().replace("\n", " | ") + "'", view(where) + ": '"
                    + cut.text().replace("\n", "\\n") + "' is cut (" + cut.id().replaceAll("\\.\\d+(?=\\.|$)", ".#") + ")");
        }
        for (Layout.Box box : layout.boxes()) {
            if (box.kind() == Layout.Kind.TEXT && box.text() != null && !box.text().isEmpty()) {
                float measured = TextFit.width(layout.measurer(), box.text(), box.style());
                tally.require(measured <= box.rect().w() + TextFit.FIT_EPSILON + 0.001F, where, view(where) + ": '" + box.text()
                        + "' is wider than its box (" + box.id().replaceAll("\\.\\d+(?=\\.|$)", ".#") + ")");
            }
        }
    }

    /** The same card with the pointer over it. */
    private static CardLayout.State hovered(CardLayout.State state) {
        return new CardLayout.State(state.extra(), true, state.expanded(), state.installedTab(), state.title(), state.artist(),
                state.mapper(), state.source(), state.statusLabel(), state.badges(), state.stars(), state.stats(),
                state.rightBadge(), state.externalAudio(), state.hasFx(), state.dropdownRows(), state.downloading());
    }

    /**
     * A card's hover only adds to it - the strip's buttons and, on a normal card, the stats line - and moves nothing:
     * every other box, the title with its ellipsis and badges, the right badge, artist, mapper, status pill and dots,
     * keeps its place and its words. The words used to end at the strip's current edge, so on hover the title's
     * ellipsis moved and the last title badge (LIBRARY / БИБЛИОТЕКА) came and went.
     */
    private static void sameOnHover(Tally tally, Layout rest, Layout hover, String where) {
        Set<String> hoverOnly = Set.of("card.strip", "card.favourite", "card.install", "card.stats");
        Map<String, Layout.Box> hovered = new HashMap<>();
        for (Layout.Box box : hover.boxes()) {
            hovered.put(box.id(), box);
        }
        for (Layout.Box box : rest.boxes()) {
            if (hoverOnly.contains(box.id())) {
                continue;
            }
            Layout.Box other = hovered.remove(box.id());
            tally.require(other != null && other.rect().equals(box.rect()) && java.util.Objects.equals(other.text(), box.text()),
                    where + ": '" + box.text() + "' " + box.rect() + " on hover " + (other == null ? "gone" : "'" + other.text()
                            + "' " + other.rect()), "card: hovering moves or changes " + box.id().replaceAll("\\.\\d+(?=\\.|$)", ".#"));
        }
        hovered.keySet().removeAll(hoverOnly);
        tally.require(hovered.isEmpty(), where + ": " + hovered.keySet(), "card: hovering adds boxes besides the strip's "
                + "buttons and the stats line");
    }

    /**
     * A card never leaves a title badge out without a sign: the title gives way to its badges first, down to its first
     * eight letters and the ellipsis (or the whole title, or the whole line, if narrower), and no further than they
     * need; the badges that still do not fit are counted in a "+n" chip after the ones that do, and a badge goes into
     * the chip only when it could not be shown beside a title that short. When not even the chip fits beside that
     * minimum (a narrow extra card with a long right badge), the title gives way for the chip alone, or for the badges
     * themselves where they take no more room than the chip. Everything stays on the title's line. The badges used to
     * take at most half of the line, so with a long title a third badge that did not fit in that half was left out
     * without a sign: БИБЛИОТЕКА at 640x360 in Russian, while the title kept 84 units. Then the minimum kept room for an
     * ellipsis that a title shown whole never shows, and «Gurenge EXPLICIT AI +1» hid LIBRARY that fitted. Whole or cut
     * is decided by width, not by counting letters: "Lagtrain!" is nine letters, but no wider than "Lagtrain…".
     */
    private static void cardKeepsBadges(Tally tally, Layout layout, CardLayout.State state, String where) {
        TextMeasurer measurer = layout.measurer();
        List<String> badges = state.badges();
        int shown = 0;
        while (layout.has("card.badge." + shown)) {
            tally.require(shown < badges.size() && layout.text("card.badge." + shown + ".text").equals(badges.get(shown)), where
                    + ", badge " + shown + " reads '" + layout.text("card.badge." + shown + ".text") + "'", "cards: a title badge is out of order");
            shown++;
        }
        boolean more = layout.has("card.badge.more");
        tally.require(shown == badges.size() || more && layout.text("card.badge.more.text").equals("+" + (badges.size() - shown)),
                where + ", '" + layout.text("card.title") + "' with " + badges.subList(0, Math.min(shown, badges.size()))
                        + (more ? " and '" + layout.text("card.badge.more.text") + "'" : ""),
                "cards: a title badge is left out without a sign");
        tally.require(!more || shown < badges.size(), where, "cards: a card counts badges it has shown");
        Rect title = layout.rect("card.title");
        Rect right = layout.rect("card.right_badge");
        // The title's line ends at the right badge, or where the hovered strip begins (CardLayout's textRight).
        float titleRight = right.empty() ? layout.rect("card.body").right() - Metrics.CARD_STRIP_HOVER - 5 : right.x() - 3;
        float titleRoom = titleRight - title.x();
        // The 8 is written here rather than read from CardLayout.TITLE_MIN_LETTERS, so that it is pinned; the widths of
        // the run are summed here from the badges' words, apart from CardLayout.badgesRun.
        String name = state.title();
        String eight = name.substring(0, name.offsetByCodePoints(0, Math.min(8, name.codePointCount(0, name.length()))));
        float min = Math.min(titleRoom, Math.min(TextFit.width(measurer, name, TextStyle.TITLE),
                TextFit.width(measurer, eight, TextStyle.TITLE) + TextFit.width(measurer, TextFit.ELLIPSIS, TextStyle.TITLE)));
        // The "+n" chip alone after the title, for every badge, and every badge without the chip.
        float chipAlone = badges.isEmpty() ? 0F : 4F + TextFit.width(measurer, "+" + badges.size(), TextStyle.PILL) + 8;
        float allBadges = 0F;
        for (int b = 0; b < badges.size(); b++) {
            allBadges += (b == 0 ? 4F : 3F) + TextFit.width(measurer, badges.get(b), TextStyle.PILL) + 8;
        }
        // The chip stands alone only for badges wider than it: in its place they would cost the title no more letters.
        tally.require(!(more && shown == 0) || allBadges > chipAlone + 0.01F, where + ", '" + layout.text("card.title")
                + "' with '+" + badges.size() + "' though " + badges + " are no wider", "cards: a card shows the chip alone "
                + "where its badges fit in the chip's place");
        float run = 0F;
        List<String> pills = new ArrayList<>(badges.subList(0, Math.min(shown, badges.size())));
        if (more) {
            pills.add("+" + (badges.size() - shown));
        }
        float x = title.right() + 4;
        for (int b = 0; b < pills.size(); b++) {
            Rect badge = layout.rect(b < shown ? "card.badge." + b : "card.badge.more");
            float width = TextFit.width(measurer, pills.get(b), TextStyle.PILL) + 8;
            tally.require(Math.abs(badge.x() - x) <= 0.02F && Math.abs(badge.w() - width) <= 0.02F && badge.right() <= titleRight + 0.02F
                            && Math.abs(badge.y() - (title.y() - 1)) <= 0.02F, where + ", '" + pills.get(b) + "' at " + badge
                            + ", not from " + x + " " + width + " wide on the title's line ending at " + titleRight,
                    "cards: a title badge is not where the title's line puts it");
            x += width + 3;
            run += (b == 0 ? 4F : 3F) + width;
        }
        if (more) {
            // One badge more, with the chip counting one fewer (or none), would not have fitted beside the title's
            // minimum.
            float wider = 4F + TextFit.width(measurer, badges.get(shown), TextStyle.PILL) + 8;
            for (int b = 0; b < shown; b++) {
                wider += 3F + TextFit.width(measurer, badges.get(b), TextStyle.PILL) + 8;
            }
            if (shown + 1 < badges.size()) {
                wider += 3F + TextFit.width(measurer, "+" + (badges.size() - shown - 1), TextStyle.PILL) + 8;
            }
            tally.require(min + wider > titleRoom + 0.01F, where + ", '" + layout.text("card.title") + "' beside " + pills
                    + " though " + (shown + 1) + " badges fit beside " + min, "cards: a title badge is counted though it fits");
        }
        for (Layout.Cut cut : layout.cuts()) {
            if (cut.id().equals("card.title")) {
                // Below its minimum only when not even the chip fits beside it, and then only for the chip alone or
                // for badges no wider than the chip.
                tally.require(cut.width() >= min - 0.01F || min + chipAlone > titleRoom + 0.01F && run <= chipAlone + 0.01F,
                        where + ", '" + cut.shown() + "' in " + cut.width() + " under " + min + " beside " + pills,
                        "cards: a title gives way to its badges below its minimum");
                tally.require(cut.width() + run >= titleRoom - 0.02F, where + ", '" + cut.shown() + "' in " + cut.width()
                        + " beside badges " + run + " wide on a line " + titleRoom + " wide",
                        "cards: a title gives way further than its badges need");
            }
        }
    }

    /**
     * A commenter's badges are all shown: the name gives way to them first, down to {@link CommentsLayout#NAME_MIN} (48)
     * or its first six letters and the ellipsis, whichever is wider (or its three fifths of a line, if shorter), and no
     * further than they need; badges that still do not fit go on under it, inside the text column, clear of the name,
     * of each other and of the reply's "↩ name", and above the comment's first line. With three roles and a long name, a
     * 320-wide window used to drop «КУРАТОР» without a sign while the name kept three fifths of the line, and a name of
     * wide letters beside four badges kept five of them at GUI scale 1 ("someo…").
     *
     * <p>A reply always says whom it answers: its "↩ name" follows the last badge when it fits there whole and otherwise
     * starts a line of its own, so it is cut only when it is wider than the whole column; it stays inside the column,
     * clear of the name and the badges, and above the comment's first line. Once the name gave way to every badge, the
     * marker found under 20 units left after them and was left out, so a staff member's reply at 320x240 read
     * «a_very_l… АВТОР АДМИН МОДЕРАТОР КУРАТОР» and nothing more.
     */
    private static void badgesKept(Tally tally, Layout layout, CommentsLayout.Row row, String id, String where) {
        int shown = 0;
        while (layout.has(id + ".badge." + shown)) {
            shown++;
        }
        tally.require(shown == row.badges().size(), where + ", '" + row.author() + "' shown as '" + layout.text(id + ".author")
                + "' with " + row.badges().subList(0, Math.min(shown, row.badges().size())), "comments: a commenter's badge is dropped");
        Rect author = layout.rect(id + ".author");
        float textRoom = layout.rect(id).right() - author.x();
        // The least a name keeps: 48 units, about eight letters, or its first six letters and the ellipsis where those
        // are wider. The 48 is written here rather than read from CommentsLayout.NAME_MIN, so that it is pinned.
        String name = row.author();
        String six = name.substring(0, name.offsetByCodePoints(0, Math.min(6, name.codePointCount(0, name.length()))));
        float nameMin = Math.min(textRoom * 0.6F, Math.max(48F, TextFit.width(layout.measurer(), six, TextStyle.STRONG)
                + TextFit.width(layout.measurer(), TextFit.ELLIPSIS, TextStyle.STRONG)));
        // The badges' run: each badge and the gap after it, as the row lays them after the name and 3 units of space.
        float badgesWidth = 0F;
        for (int b = 0; b < shown; b++) {
            badgesWidth += layout.rect(id + ".badge." + b).w() + CommentsLayout.BADGE_GAP;
        }
        for (Layout.Cut cut : layout.cuts()) {
            if (cut.id().equals(id + ".author")) {
                // A name cut for its badges still reads as a name: its room is the minimum at least, and that minimum
                // shows six letters of it or more.
                String kept = cut.shown().endsWith(TextFit.ELLIPSIS)
                        ? cut.shown().substring(0, cut.shown().length() - TextFit.ELLIPSIS.length()) : cut.shown();
                tally.require(cut.width() >= nameMin - 0.01F && kept.codePointCount(0, kept.length()) >= 6, where + ", '"
                        + cut.shown() + "' in " + cut.width(), "comments: a commenter's name gives way below its minimum");
                // And it gives way no further than its badges need: it keeps its three fifths of the line, or its room
                // and the badges after it fill the line to the column's end.
                tally.require(cut.width() >= textRoom * 0.6F - 0.01F
                                || cut.width() + 3F + badgesWidth - CommentsLayout.BADGE_GAP >= textRoom - 0.01F,
                        where + ", '" + cut.shown() + "' in " + cut.width() + " beside badges " + badgesWidth + " wide in a column "
                                + textRoom + " wide", "comments: a commenter's name gives way further than its badges need");
            }
        }
        List<Rect> header = new ArrayList<>(List.of(author));
        boolean reply = row.parent() != null && !row.parent().isEmpty();
        tally.require(!reply || layout.has(id + ".parent"), where + ", '" + layout.text(id + ".author") + "' with "
                + row.badges() + " answering '" + row.parent() + "'", "comments: a reply leaves out whom it answers");
        Rect parent = layout.rect(id + ".parent");
        if (reply && layout.has(id + ".parent")) {
            header.add(parent);
            String marker = layout.text(id + ".parent");
            tally.require(parent.x() >= author.x() - 0.01F && parent.right() <= author.x() + textRoom + 0.01F, where + ", " + parent
                    + " in a column from " + author.x() + " " + textRoom + " wide", "comments: a reply's \"↩ name\" runs out of the text column");
            tally.require(!parent.overlaps(author, 0.01F), where + ", " + parent + " and the name " + author,
                    "comments: a reply's \"↩ name\" overlaps the commenter's name");
            tally.require(marker.equals(row.parent()) || Math.abs(parent.x() - author.x()) <= 0.01F, where + ", drawn as '" + marker
                            + "' at " + parent.x() + " after '" + layout.text(id + ".author") + "' with " + row.badges(),
                    "comments: a reply's \"↩ name\" is cut beside the badges instead of starting a line of its own");
            // Where it would follow the last badge (the badges' gap after it) or the name (3 units after it), on that
            // line. It starts a line of its own only when it does not fit there whole, and otherwise stands right there.
            Rect last = shown > 0 ? layout.rect(id + ".badge." + (shown - 1)) : author;
            float after = last.right() + (shown > 0 ? CommentsLayout.BADGE_GAP : 3F);
            boolean fitsAfter = TextFit.fits(layout.measurer(), row.parent(), TextStyle.CAPTION, author.x() + textRoom - after);
            boolean ownLine = Math.abs(parent.x() - author.x()) <= 0.01F && parent.y() > last.y() + 1F;
            tally.require(ownLine ? !fitsAfter : Math.abs(parent.x() - after) <= 0.02F && Math.abs(parent.y() - (last == author
                            ? author.y() : last.y() + 1)) <= 0.02F, where + ", '" + marker + "' at " + parent + " after " + last
                            + (fitsAfter ? ", where it fits whole" : ""), ownLine
                            ? "comments: a reply's \"↩ name\" starts a line of its own though it fits after the badges"
                            : "comments: a reply's \"↩ name\" is not where it follows the badges");
        }
        float bottom = author.bottom();
        for (int b = 0; b < shown; b++) {
            Rect badge = layout.rect(id + ".badge." + b);
            if (badge.y() > author.y()) {
                // A badge goes under the name only once the name has given way as far as it may.
                tally.require(author.w() <= nameMin + 0.01F, where + ", '"
                                + layout.text(id + ".author") + "' " + author.w() + " wide over " + row.badges().get(b),
                        "comments: a badge goes under the name while the name could still give way");
            }
            tally.require(badge.x() >= author.x() - 0.01F && badge.right() <= author.x() + textRoom + 0.01F, where + ", " + badge,
                    "comments: a commenter's badge runs out of the text column");
            for (Rect other : header) {
                tally.require(!badge.overlaps(other, 0.01F), where + ", " + badge + " and " + other,
                        "comments: a commenter's badge overlaps the name, another badge or the reply's name");
            }
            header.add(badge);
            bottom = Math.max(bottom, badge.bottom());
        }
        Rect body = layout.rect(id + ".body.0");
        tally.require(body.empty() || body.y() >= bottom, where + ", body at " + body.y() + " under badges down to " + bottom,
                "comments: a comment's first line runs into its author's badges");
        if (reply && layout.has(id + ".parent")) {
            tally.require(body.empty() || body.y() >= parent.bottom() - 0.01F, where + ", body at " + body.y() + " under '"
                    + layout.text(id + ".parent") + "' down to " + parent.bottom(), "comments: a comment's first line runs into its \"↩ name\"");
        }
    }

    /**
     * Every dialog stays inside the window with every button reachable, on windows from 240 units tall, with no body
     * text up to a long one: the buttons are pinned under the body while that leaves the body its few lines, and
     * otherwise scroll with the body, after all of it. The more menu's stacked actions used to run off a 240-unit window.
     *
     * <p>And the body's text area holds every line it shows whole, as the game draws it: the panel centred as the screen
     * centres it, the scroll offset and each line snapped to the scale's pixels, the clip widened to whole units as
     * UiClip widens it, and each glyph's ink from the game's own FreeType provider (GlyphInk). Scrolled to the end, the
     * last choice's last line ends inside the clip, descenders and all: the body used to end 4 units into it, so the
     * rules' acceptance read "publish here." with its feet cut off on every window. At the top, the first line keeps
     * the parts of its letters that rise over the cap line («Й», «б», brackets), whatever words a paragraph starts with.
     */
    private static void frames(Tally tally, Ink ink, DialogLayout.Result dialog, boolean field, String where) {
        float scale = ink.glyphs.tier;
        int choices = dialog.choices().size();
        String first = choices > 0 && !dialog.choices().get(0).isEmpty() ? dialog.choices().get(0).get(0) : null;
        List<String> lastChoice = choices > 0 ? dialog.choices().get(choices - 1) : List.of();
        String last = lastChoice.isEmpty() ? null : lastChoice.get(lastChoice.size() - 1);
        for (int height : new int[] {240, 270, 300, 360, 480, 720}) {
            for (int lines : new int[] {0, 1, 3, 8, 30}) {
                DialogLayout.Body body = DialogLayout.body(lines == 0 ? List.of() : List.of(lines), dialog, field);
                DialogLayout.Frame frame = DialogLayout.frame(height, body.contentHeight(), dialog);
                String at = where + " in a window " + height + " tall with " + lines + " lines of text";
                tally.require(frame.panelHeight() <= height - DialogLayout.WINDOW_MARGIN * 2 + 0.01F, at,
                        "dialog: the panel runs off a window " + height + " tall");
                tally.require(frame.buttonsScroll()
                                ? frame.buttonsTop() >= body.contentHeight()
                                && frame.contentHeight() >= frame.buttonsTop() + dialog.buttonsHeight() - 0.01F
                                : frame.bodyHeight() >= Math.min(body.contentHeight(), DialogLayout.MIN_BODY) - 0.01F,
                        at, "dialog: the buttons cover the body or leave it no room");
                // WorkshopDialogScreen.layout: the panel centred in the window, the body under the heading's rule.
                float panelY = Math.max(DialogLayout.WINDOW_MARGIN, (height - frame.panelHeight()) * 0.5F);
                float bodyTop = panelY + dialog.headingHeight() + DialogLayout.PAD;
                float clipTop = (float) Math.floor(bodyTop - DialogLayout.INK_ABOVE_CAP);
                float clipBottom = (float) Math.ceil(bodyTop + frame.bodyHeight());
                if (lines > 0) {
                    tally.require(ink.glyphs.snap(bodyTop) - ink.rise >= clipTop - 0.001F, at,
                            "dialog: the tops of a paragraph's first line are clipped (" + ink.risesMost + " rises "
                                    + ink.rise + " over the cap line)");
                } else if (first != null) {
                    float y = bodyTop + DialogLayout.choiceLineY(body.choiceTops().get(0), 0);
                    tally.require(ink.glyphs.snap(y) + ink.bounds(first)[0] >= clipTop - 0.001F, at,
                            "dialog: the tops of the first choice's first line are clipped");
                }
                if (last != null) {
                    // Scrolled to the end, as the player scrolls to read the last box; with nothing to scroll, at rest.
                    double extent = Math.max(0.0, frame.contentHeight() - frame.bodyHeight());
                    float offset = (float) (Math.round(extent * scale) / (double) scale);
                    float y = bodyTop - offset + DialogLayout.choiceLineY(body.choiceTops().get(choices - 1), lastChoice.size() - 1);
                    float bottom = ink.glyphs.snap(y) + ink.bounds(last)[1];
                    tally.require(bottom <= clipBottom + 0.001F, at + ", drawn as '" + last + "' down to " + bottom
                                    + " in a clip ending at " + clipBottom,
                            "dialog: the last choice's last line is cut at the bottom of the body");
                }
                if (field) {
                    // The field is the body's last part, under its label's lines, as the screen places it. The body's
                    // content, which sets how far it scrolls, reaches the field's bottom edge; and scrolled to the end, or
                    // at rest when nothing scrolls, that edge ends inside the clip. A body that ended short of it would
                    // shave the field's foot off and leave no scroll to show it. The field is a shape drawn where it
                    // falls, not snapped like a line of text, so its edge is the pixel boundary nearest to it: the scroll
                    // offset is rounded to the nearest pixel, and the under-half-pixel fringe that leaves past the body's
                    // foot is all the clip may take.
                    float foot = body.fieldTop() + dialog.fieldLabel().size() * TextStyle.CAPTION.lineHeight() + DialogLayout.FIELD_HEIGHT;
                    tally.require(body.contentHeight() >= foot - 0.001F, at + ", the body's content " + body.contentHeight()
                            + " tall over a field ending at " + foot, "dialog: the body's content ends above its text field's bottom");
                    double extent = Math.max(0.0, frame.contentHeight() - frame.bodyHeight());
                    float offset = (float) (Math.round(extent * scale) / (double) scale);
                    float fieldBottom = bodyTop - offset + foot;
                    float edge = (float) (Math.round(fieldBottom * scale) / (double) scale);
                    tally.require(edge <= clipBottom + 0.001F, at + ", the field down to " + fieldBottom + " (its edge at " + edge
                            + ") in a clip ending at " + clipBottom, "dialog: the text field's bottom is cut at the end of the body");
                }
            }
        }
    }

    /**
     * Every link and action of a dialog lies inside the panel's text column, apart from every other: links that do not
     * fit beside each other are stacked, and so are actions, rather than run past the panel's edge. Each label fitting
     * its own button, which {@link #verify} measures, does not show that.
     */
    private static void buttonsInside(Tally tally, DialogLayout.Result dialog, String where) {
        List<Rect> buttons = new ArrayList<>(dialog.links());
        buttons.addAll(dialog.actions());
        for (int i = 0; i < buttons.size(); i++) {
            Rect button = buttons.get(i);
            tally.require(button.x() >= -0.01F && button.right() <= dialog.textWidth() + 0.01F, where + ", " + button
                    + " in a column " + dialog.textWidth() + " wide", "dialog: a button runs past the panel's edge");
            for (int j = 0; j < i; j++) {
                tally.require(!button.overlaps(buttons.get(j), 0.01F), where, "dialog: two buttons overlap");
            }
        }
    }

    /**
     * The ink of the body's text at one GUI scale: each line's reach above its cap line and below it, and for a line
     * whose words this check does not know (a paragraph, which the game's own splitter wraps), the most any glyph a
     * dialog can show rises - every letter of the Workshop's strings in this language and every Latin and Cyrillic
     * letter a beatmap's title or a name may bring.
     */
    private static final class Ink {
        final GlyphInk glyphs;
        final float rise;
        final String risesMost;
        private final Map<String, float[]> cache = new HashMap<>();

        Ink(GlyphInk glyphs, LangTr tr) {
            this.glyphs = glyphs;
            Set<Integer> codePoints = new java.util.TreeSet<>();
            for (int c = 0x21; c < 0x7F; c++) {
                codePoints.add(c);
            }
            for (int c = 0x410; c <= 0x451; c++) {
                codePoints.add(c);
            }
            for (Map.Entry<String, String> entry : tr.strings.entrySet()) {
                if (entry.getKey().startsWith(WorkshopStrings.KEY) || entry.getKey().startsWith(WorkshopStrings.DATA)) {
                    entry.getValue().codePoints().forEach(codePoints::add);
                }
            }
            float most = 0F;
            String which = "";
            for (int codePoint : codePoints) {
                String glyph = new String(Character.toChars(codePoint));
                float[] box;
                try {
                    box = bounds(glyph);
                } catch (AssertionError missing) {
                    continue;
                }
                if (-box[0] > most) {
                    most = -box[0];
                    which = glyph;
                }
            }
            rise = most;
            risesMost = which;
        }

        /** The ink's top and bottom of {@code text} drawn with its cap line at 0 (zero when it has no ink). */
        float[] bounds(String text) {
            return cache.computeIfAbsent(text, key -> {
                float[] all = GlyphInk.bounds(glyphs.line(key, TextStyle.BODY, 0F, 0F));
                return all == null ? new float[] {0F, 0F} : new float[] {all[1], all[3]};
            });
        }
    }

    /**
     * A field's placeholder, which the field draws itself and so no layout box holds: TextField writes it on one line
     * from 4 units in and clips it 1 unit inside the field's edge, and the search field's magnifier sits at its right
     * end. A hint wider than that room is sliced through a letter by the clip, so it has to fit.
     */
    private static void placeholder(Tally tally, TextMeasurer measurer, Layout layout, String fieldId, String iconId, String hint,
                                    String where, String view) {
        if (hint == null || hint.isEmpty() || !layout.has(fieldId)) {
            return;
        }
        Rect field = layout.rect(fieldId);
        float right = iconId != null && layout.has(iconId) ? layout.rect(iconId).x() - 2F : field.right() - 1F;
        tally.require(TextFit.fits(measurer, hint, TextStyle.BODY, right - (field.x() + 4F)), where,
                view + ": the placeholder '" + hint + "' does not fit its field (" + fieldId + ")");
    }

    /**
     * Whether a cut only broke identifiers: every line is there and nothing was shortened, and each word wider than the
     * line is a link or a hash (twelve characters or more with a digit or a slash), which has no other way onto a narrow
     * line. A word of the interface ("Откровенный") never passes.
     */
    private static boolean onlyIdentifiersBroken(TextMeasurer measurer, Layout.Cut cut) {
        if (!cut.text().replaceAll("\\s+", "").equals(cut.shown().replaceAll("\\s+", ""))) {
            return false;
        }
        boolean broken = false;
        for (String word : cut.text().split("[ \n]+")) {
            if (!TextFit.fits(measurer, word, cut.style(), cut.width())) {
                if (word.length() < 12 || !word.matches(".*[0-9/].*")) {
                    return false;
                }
                broken = true;
            }
        }
        return broken;
    }

    /** The view a failure is in, from its context ("ru at GUI scale 3 320x240 listing browse ..." gives "listing"). */
    private static String view(String where) {
        String[] words = where.split(" ");
        return words.length > 6 ? words[6] + (words.length > 7 && words[6].equals("upload") && words[7].equals("bar") ? " bar" : "")
                : where;
    }

    private static String playerText(String id) {
        for (Map.Entry<Pattern, String> entry : PLAYER_TEXT.entrySet()) {
            if (entry.getKey().matcher(id).matches()) {
                return entry.getValue();
            }
        }
        return null;
    }

    /**
     * The cut detector itself: a label in too small a box, a paragraph past its cap and a word wider than its line are
     * each reported, and a label that fits to the last hundredth of a unit is not.
     */
    private static void detectorWorks(Tally tally) {
        TextMeasurer fixed = (text, style) -> text.length() * 5F;
        Layout layout = new Layout(fixed, (key, arguments) -> key);
        layout.label("probe.cut", "", 0, 0, 22F, "abcdefgh", TextStyle.BODY);
        layout.label("probe.whole", "", 0, 0, 39.995F, "abcdefgh", TextStyle.BODY);
        layout.paragraph("probe.lines", "", 0, 0, 30F, "aaa bbb ccc ddd", TextStyle.BODY, 10F, 2);
        layout.paragraph("probe.word", "", 0, 0, 30F, "aaaaaaaa b", TextStyle.BODY, 10F, 5);
        layout.paragraph("probe.spaces", "", 0, 0, 30F, "aaa bbb ccc", TextStyle.BODY, 10F, 5);
        Set<String> cut = new HashSet<>();
        for (Layout.Cut each : layout.cuts()) {
            cut.add(each.id());
        }
        tally.require(cut.equals(Set.of("probe.cut", "probe.lines", "probe.word")), "", "the cut detector reports a cut label, a capped "
                + "paragraph and a broken word, and not a label within " + TextFit.FIT_EPSILON + " or text wrapped at spaces: "
                + cut);
    }

    /**
     * English names the feature "Workshop", with a capital, in every string a player reads, as Russian writes
     * «Мастерская»: 31 strings (mostly the service's errors) said "the workshop" next to 65 that said "the Workshop".
     * A config key that starts with it ("workshop.allowServerDownloads") is the one lower-case use, being a key.
     */
    private static void workshopIsAName(Tally tally, LangTr english) {
        Pattern lower = Pattern.compile("\\bworkshop\\b(?!\\.[A-Za-z])");
        for (Map.Entry<String, String> entry : new TreeMap<>(english.strings).entrySet()) {
            tally.require(!lower.matcher(entry.getValue()).find(), "", "en: " + entry.getKey() + " writes 'workshop' in lower case: '"
                    + entry.getValue() + "'");
        }
    }

    /**
     * A commenter's role badge is the account view's word for the role in the player's language, upper case: the site's
     * codes ("admin", "mod", "curator", any case) map to {@code account.role.*}, and a code this version does not know
     * gives no badge rather than the code itself.
     */
    private static void rolesTranslated(Tally tally, String code, LangTr tr) {
        for (String role : WorkshopStrings.COMMENT_ROLES) {
            String word = WorkshopStrings.upper(tr.get(WorkshopStrings.KEY + "account.role." + role));
            tally.require(word.equals(WorkshopStrings.roleBadge(tr, role))
                            && word.equals(WorkshopStrings.roleBadge(tr, role.toUpperCase(Locale.ROOT))), "",
                    code + ": the comment badge for '" + role + "' is not the account view's word '" + word + "': '"
                            + WorkshopStrings.roleBadge(tr, role) + "'");
        }
        tally.require(WorkshopStrings.roleBadge(tr, "staff") == null && WorkshopStrings.roleBadge(tr, null) == null, "",
                code + ": a role this version does not know still gets a badge");
        if (tr.russianPlurals()) {
            tally.require(!WorkshopStrings.roleBadge(tr, "mod").equals("MOD"), "", code + ": the moderator badge is in English");
        }
    }

    /** The comments view takes its badges from {@link WorkshopStrings#roleBadge}, not from the site's codes. */
    private static void commentsUseRoleWords(Tally tally, Path root) throws Exception {
        String source = Files.readString(root.resolve("src/main/java/ru/gora/musicbattle/client/workshop/CommentsSection.java"),
                StandardCharsets.UTF_8);
        tally.require(source.contains("WorkshopStrings.roleBadge(WorkshopTr.INSTANCE, badge)") && !source.contains("badge.toUpperCase("),
                "", "comments: the author's badges are the site's codes, not the player's language (CommentsSection)");
    }

    /**
     * A card's right badge is named by its key, which gives both its words and its colour: "update" is the warning
     * colour and "withdrawn" and "audio_removed" the danger colour in every language. The listing used to hand the card
     * the upper-case words and the colour was looked up from those, so in Russian "ОБНОВЛЕНИЕ" matched nothing.
     */
    private static void badgeColours(Tally tally, String code, LangTr tr) {
        Map<String, Integer> colours = new LinkedHashMap<>();
        colours.put("update", ru.gora.musicbattle.client.ui.Palette.WARN);
        colours.put("withdrawn", ru.gora.musicbattle.client.ui.Palette.DANGER);
        colours.put("audio_removed", ru.gora.musicbattle.client.ui.Palette.DANGER);
        colours.put("needs_audio", ru.gora.musicbattle.client.ui.Palette.TEXT_3);
        colours.put(ru.gora.musicbattle.client.workshop.CardRenderer.KIND_WORKSHOP, ru.gora.musicbattle.client.ui.Palette.TEXT_3);
        for (Map.Entry<String, Integer> entry : colours.entrySet()) {
            String key = entry.getKey();
            String words = WorkshopStrings.upper(tr.get(WorkshopStrings.KEY
                    + (key.equals(ru.gora.musicbattle.client.workshop.CardRenderer.KIND_WORKSHOP) ? "card." : "badge.") + key));
            tally.require(words.equals(ru.gora.musicbattle.client.workshop.CardRenderer.rightBadgeLabel(tr, key)), "",
                    code + ": the right badge '" + key + "' does not read '" + words + "'");
            tally.require(ru.gora.musicbattle.client.workshop.WorkshopDraw.badgeColour(key) == entry.getValue(), "",
                    "the right badge '" + key + "' is not drawn in its colour");
        }
    }

    /** The listing hands a card its badge's key, never its words, and the card colours the badge by that key. */
    private static void badgesByKey(Tally tally, Path root) throws Exception {
        Path workshop = root.resolve("src/main/java/ru/gora/musicbattle/client/workshop");
        String listing = Files.readString(workshop.resolve("ListingView.java"), StandardCharsets.UTF_8);
        int start = listing.indexOf("private String rightBadge(");
        int end = listing.indexOf("private Rect shifted(", start);
        String badge = start < 0 || end < 0 ? "" : listing.substring(start, end);
        tally.require(badge.contains("return \"needs_audio\";") && badge.contains("return \"update\";")
                        && badge.contains("CardRenderer.KIND_WORKSHOP") && !badge.contains("WorkshopStrings.upper("), "",
                "listing: a card's right badge is handed over as words, not as its key (ListingView.rightBadge)");
        String renderer = Files.readString(workshop.resolve("CardRenderer.java"), StandardCharsets.UTF_8);
        tally.require(renderer.contains("WorkshopDraw.badgeColour(context.rightBadge())")
                        && renderer.contains("rightBadgeLabel(tr, context.rightBadge())"), "",
                "card: the right badge's words or colour do not come from its key (CardRenderer)");
    }

    /** The card scenarios lay out every right badge a card can carry, each on a card of its own. */
    private static void cardsCarryEveryRightBadge(Tally tally, String code, LangTr tr) {
        Set<String> laidOut = new Scenarios(tr).cardRightBadges;
        for (String key : RIGHT_BADGES) {
            String words = ru.gora.musicbattle.client.workshop.CardRenderer.rightBadgeLabel(tr, key);
            tally.require(laidOut.contains(words), "", code + ": no card of this check carries the right badge '" + key
                    + "' (" + words + ")");
        }
    }

    /**
     * A card counts its downloads and favourites in their plural forms, as the beatmap page does: "1 download",
     * "2 downloads", «1 загрузка», «3 загрузки», «1234 загрузки», «5 загрузок», «21 загрузка». The forms are worked out
     * here on their own, from the counts. Every count is paired with every other, so the two parts never share a number
     * or a form by chance: each part has to carry its own count, in the form that count takes, in its own place. With
     * one count for both, a card that printed its downloads' number as its favourites, or swapped the two, still passed.
     */
    private static void cardStatsCounted(Tally tally, String code, LangTr tr) {
        long now = 1_760_000_000_000L;
        long[] counts = {0, 1, 2, 4, 5, 11, 12, 14, 21, 22, 25, 101, 111, 888, 1234, 12_345};
        for (long downloads : counts) {
            for (long favourites : counts) {
                String expected = counted(tr, "card.downloads", downloads) + CardLayout.STATS_SEPARATOR
                        + counted(tr, "card.favourites", favourites);
                String stats = WorkshopStrings.cardStats(tr, downloads, favourites, null, now);
                tally.require(stats.equals(expected), "", code + ": a card with " + downloads + " downloads and " + favourites
                        + " favourites reads '" + stats + "', not '" + expected + "'");
                String dated = WorkshopStrings.cardStats(tr, downloads, favourites, "2026-09-10T10:00:00Z", now);
                tally.require(dated.equals(expected + CardLayout.STATS_SEPARATOR + WorkshopStrings.relative(tr, "2026-09-10T10:00:00Z", now)),
                        "", code + ": a card's stats line leaves out or misplaces its date: '" + dated + "'");
            }
        }
    }

    /**
     * The page of a server beatmap (a {@code track_<id>} card of the Server tab) is built from the server's list and shows
     * what that list knows: the card's BPM and length, its description, its kind and who made it. It used to be built
     * from the Workshop's stand-in and read BPM 0, length 0:00, COMMUNITY, "0 downloads 0 favourites", an empty
     * "mapped by" under a blank avatar, the title twice and a difficulty row of 0.00 stars and 0:00, while the card on
     * the same screen said BPM 96 and 3:00. None of the Workshop's own parts may be on it.
     */
    private static void serverBeatmapPage(Tally tally, String code, LangTr tr, TextMeasurer measurer) {
        String description = "Original song By: The Living Thombstone. Map By: Spaidy";
        String byline = tr.get(WorkshopStrings.KEY + "card.mapped_by", "Spaidy");
        String install = tr.get(WorkshopStrings.KEY + "set.server_track");
        SetPageLayout.State full = SetPageLayout.serverTrack(tr, "It's Been So Long [Hard]", "server", "SERVER", byline, 96,
                180_000L, description, install);
        SetPageLayout.State bare = SetPageLayout.serverTrack(tr, "Practice", "players", "PLAYERS", "", 0, 61_000L, "", install);
        List<String> workshopOnly = List.of("set.status", "set.counters", "set.chartname", "set.rating", "set.attr.0.bar",
                "set.tile.0", "set.favourite", "set.browser", "set.more", "set.preview", "set.avatar", "set.submitted",
                "set.charts.title", "set.chart.0");
        for (int width : new int[] {300, 640, 940}) {
            Layout layout = SetPageLayout.compute(new Rect(0, 0, width, 400), measurer, tr, full);
            String where = code + " server beatmap page " + width;
            tally.require("3:00".equals(layout.text("set.basic.0.value")), where,
                    code + ": a server beatmap's page shows length '" + layout.text("set.basic.0.value") + "', not its 3:00");
            tally.require("96".equals(layout.text("set.basic.1.value")), where,
                    code + ": a server beatmap's page shows BPM '" + layout.text("set.basic.1.value") + "', not its 96");
            tally.require(!layout.has("set.basic.2.value"), where, code + ": a server beatmap's page counts notes it does not know");
            StringBuilder shown = new StringBuilder();
            for (Layout.Box box : layout.group("set.desc.")) {
                shown.append(box.text() == null ? "" : box.text()).append(' ');
            }
            tally.require(shown.toString().replaceAll("\\s+", " ").trim().equals(description), where,
                    code + ": a server beatmap's page leaves out its description: '" + shown.toString().trim() + "'");
            tally.require(byline.equals(layout.text("set.mapper")), where, code + ": a server beatmap's page loses its maker");
            tally.require("SERVER".equals(layout.text("set.badge.0.text")), where,
                    code + ": a server beatmap's page loses its kind");
            tally.require(install.equals(layout.text("set.install.text")), where,
                    code + ": a server beatmap's page loses 'On this server'");
            for (String id : workshopOnly) {
                tally.require(!layout.has(id), where, code + ": a server beatmap's page draws the Workshop's " + id);
            }
            Layout plain = SetPageLayout.compute(new Rect(0, 0, width, 400), measurer, tr, bare);
            tally.require("1:01".equals(plain.text("set.basic.0.value")) && !plain.has("set.basic.1.value"), where,
                    code + ": a server beatmap with no known BPM shows a BPM of 0");
            tally.require(!plain.has("set.mapper"), where, code + ": a server beatmap with no maker shows an empty 'mapped by'");
            // Its comments are the server's alone: the Workshop's tab stood there greyed out with nothing behind it.
            for (boolean workshopTab : new boolean[] {false, true}) {
                Layout comments = CommentsLayout.compute(new Rect(0, 0, width, 400), measurer, tr, new CommentsLayout.State(1,
                        "new", true, null, true, List.of(), false, 0, false, true, true, "hint", 20F, false, "note", null, 0,
                        500, workshopTab));
                tally.require(comments.has("comments.tab.workshop") == workshopTab, where,
                        code + ": the Workshop comments tab " + (workshopTab ? "is missing from a set's page"
                                : "is on a server beatmap's page"));
                tally.require(comments.has("comments.tab.server") && (workshopTab
                                || comments.rect("comments.tab.server").x() < 12F), where,
                        code + ": the server comments tab is missing or does not move into the Workshop tab's place");
            }
        }
    }

    /** {@code n} in the plural form of {@code key} it takes, the rule written out here apart from WorkshopStrings.plural. */
    private static String counted(LangTr tr, String key, long n) {
        String form;
        if (tr.russianPlurals()) {
            form = n % 10 == 1 && n % 100 != 11 ? "one" : n % 10 >= 2 && n % 10 <= 4 && (n % 100 < 12 || n % 100 > 14) ? "few" : "many";
        } else {
            form = n == 1 ? "one" : "many";
        }
        return tr.get(WorkshopStrings.KEY + key + "." + form, WorkshopStrings.count(n));
    }

    /**
     * Featured (a curator's pick) and Favourites (the player's own list) are two things and read as two: no string about
     * Featured uses the word Favourites is made of. Russian used «Избранное» for both, so the category row read
     * «Избранные … Избранное» side by side, the sort row «Избранным … Избранному», and a featured beatmap's page said it
     * was «в избранном».
     */
    private static void featuredIsNotFavourites(Tally tally, String code, LangTr tr) {
        String favourites = tr.get(WorkshopStrings.KEY + "filter.category.favourites").toLowerCase(Locale.ROOT);
        // The word's stem, the part its forms share ("favour", «избран»).
        String stem = favourites.substring(0, Math.min(6, favourites.length()));
        for (String key : List.of(WorkshopStrings.DATA + "status.featured", WorkshopStrings.KEY + "filter.category.featured",
                WorkshopStrings.KEY + "sort.featured", WorkshopStrings.KEY + "set.featured", WorkshopStrings.DATA
                        + "error.featured_locked", WorkshopStrings.KEY + "account.stat.featured")) {
            tally.require(!tr.get(key).toLowerCase(Locale.ROOT).contains(stem), "", code + ": " + key + " says Featured with "
                    + "the word for Favourites: '" + tr.get(key) + "'");
        }
    }

    /**
     * The dialog screen draws what {@link #frames} measures, statement for statement (spacing aside): its panel, body,
     * frame and scroll range from DialogLayout, its clip from DialogLayout.INK_ABOVE_CAP above the body's top to the
     * body's foot, each choice's lines at DialogLayout.choiceLineY of its row, and the field under its label's lines,
     * DialogLayout.FIELD_HEIGHT tall. A screen that drew a choice's lines a little lower than that, or the field
     * elsewhere, used to pass while frames measured lines and a field the game does not draw.
     */
    private static void dialogsUseTheirBody(Tally tally, Path root) throws Exception {
        String screen = Files.readString(root.resolve("src/main/java/ru/gora/musicbattle/client/workshop/WorkshopDialogScreen.java"),
                StandardCharsets.UTF_8).replaceAll("\\s+", " ");
        for (String statement : List.of(
                "private static final float FIELD_HEIGHT = DialogLayout.FIELD_HEIGHT;",
                "body = DialogLayout.body(lineCounts, fitted, field != null);",
                "frame = DialogLayout.frame(this.height, body.contentHeight(), fitted);",
                "panelY = Math.max(DialogLayout.WINDOW_MARGIN, (this.height - panelHeight) * 0.5F);",
                "bodyTop = panelY + fitted.headingHeight() + PAD;",
                "scroll.setExtent(Math.max(0.0F, contentHeight - bodyHeight));",
                "UiClip.push(g, panelX + PAD, bodyTop - DialogLayout.INK_ABOVE_CAP, panelX + panelWidth - PAD, bodyTop + bodyHeight);",
                "float rowY = bodyTop - offset + choiceTop(i);",
                "panelX + PAD + box + DialogLayout.CHOICE_TEXT_GAP, DialogLayout.choiceLineY(rowY, line), selected",
                "float labelY = bodyTop - offset + body.fieldTop();",
                "field.setBounds(panelX + PAD, labelY + label.size() * TextStyle.CAPTION.lineHeight(), panelWidth - PAD * 2, FIELD_HEIGHT);")) {
            tally.require(screen.contains(statement), "", "dialog: WorkshopDialogScreen does not lay out, draw or clip its body as "
                    + "DialogLayout measures it: no '" + statement + "'");
        }
    }

    /** The card draws its stats line from WorkshopStrings.cardStats, not from the count-blind CardModel.statsLine. */
    private static void cardsCountInPlurals(Tally tally, Path root) throws Exception {
        String renderer = Files.readString(root.resolve("src/main/java/ru/gora/musicbattle/client/workshop/CardRenderer.java"),
                StandardCharsets.UTF_8);
        tally.require(renderer.contains("WorkshopStrings.cardStats(tr, model.downloads, model.favourites, model.updatedAt,")
                        && !renderer.contains("statsLine("), "",
                "card: the stats line does not count in plural forms (CardRenderer.layout)");
        // The chip that counts the badges left off the title's line is drawn, pill and words, like the badges.
        String flat = renderer.replaceAll("\\s+", " ");
        tally.require(flat.contains("Rect moreBadges = layout.rect(\"card.badge.more\");")
                        && flat.contains("WorkshopDraw.pill(g, moreBadges, Palette.BG_1);")
                        && flat.contains("WorkshopDraw.text(g, layout, \"card.badge.more.text\", Palette.TEXT_3);"), "",
                "card: CardRenderer does not draw the chip that counts the title badges left off its line");
    }

    /** Every Workshop string, in both languages, is drawn from Nunito: none falls back to the game's pixel font. */
    private static void glyphs(Tally tally, GameTextMeasurer game, String code, LangTr tr) {
        for (Map.Entry<String, String> entry : new TreeMap<>(tr.strings).entrySet()) {
            if (!entry.getKey().startsWith(WorkshopStrings.KEY) && !entry.getKey().startsWith(WorkshopStrings.DATA)) {
                continue;
            }
            String text = entry.getValue().replaceAll("%(\\d+\\$)?[sd]", "");
            boolean drawable = true;
            for (int codePoint : text.codePoints().toArray()) {
                try {
                    game.width(new String(Character.toChars(codePoint)), TextStyle.BODY);
                } catch (AssertionError missing) {
                    drawable = false;
                }
            }
            tally.require(drawable, "", code + ": " + entry.getKey() + " has a glyph Nunito lacks: '" + entry.getValue() + "'");
        }
    }

    /** Minecraft's widths, with a code point the face lacks counted as {@link #MISSING_GLYPH} (players type anything). */
    private static TextMeasurer measurer(GameTextMeasurer game) {
        Map<String, Float> cache = new HashMap<>();
        return (text, style) -> {
            if (text == null || text.isEmpty()) {
                return 0F;
            }
            String key = style.ordinal() + "\u0000" + text;
            Float known = cache.get(key);
            if (known != null) {
                return known;
            }
            float width;
            try {
                width = game.width(text, style);
            } catch (AssertionError missing) {
                width = 0F;
                for (int codePoint : text.codePoints().toArray()) {
                    try {
                        width += game.width(new String(Character.toChars(codePoint)), style);
                    } catch (AssertionError again) {
                        width += MISSING_GLYPH;
                    }
                }
            }
            cache.put(key, width);
            return width;
        };
    }

    private static String describe(ListingLayout.State state) {
        return (state.installed() ? "installed" : "browse") + (state.moreFilters() ? " more" : "") + " sort=" + state.sort()
                + "/" + state.installedSort() + " category=" + state.category() + " genre=" + state.genre() + " language="
                + state.language() + " audio=" + state.audio() + " state=" + state.status();
    }

    // ---------------------------------------------------------------- the states

    private record Variant(String name, UploadLayout.State state) {
    }

    /** Every state of every view that changes the interface's words, built once per language. */
    private static final class Scenarios {
        final List<ShellLayout.State> shells = new ArrayList<>();
        final List<ListingLayout.State> listings = new ArrayList<>();
        final List<CardLayout.State> cards = new ArrayList<>();
        /** The right badges the cards above carry, so the check can prove each one is laid out on a card. */
        final Set<String> cardRightBadges = new LinkedHashSet<>();
        final List<TrackCardLayout.State> tracks = new ArrayList<>();
        final List<SetPageLayout.State> setPages = new ArrayList<>();
        final List<CommentsLayout.State> comments = new ArrayList<>();
        final List<List<Form.Item>> accounts = new ArrayList<>();
        final List<Variant> uploads = new ArrayList<>();
        final List<DialogLayout.State> dialogs = new ArrayList<>();
        /**
         * What SetPageView writes across the preview bar when the clip cannot play: offline, none, lost on the way, or
         * arrived and not playable.
         */
        final List<String> previewNotes = new ArrayList<>();
        private final LangTr tr;

        Scenarios(LangTr tr) {
            this.tr = tr;
            for (String note : List.of("set.preview_offline", "set.preview_none", "set.preview_failed",
                    "set.preview_unplayable")) {
                previewNotes.add(k(note));
            }
            shells();
            listings();
            cards();
            tracks();
            setPages();
            comments();
            accounts();
            uploads();
            dialogs();
        }

        /**
         * Every Workshop dialog with the words its screen gives it: the consent, the rules' acceptance with both boxes,
         * the report with every reason, the beatmap's more menu with all its actions, install, the confirmations and the
         * messages, and every status line they show. Vanilla's "Cancel" and "OK" are written in, as the game's own
         * language files are not on this check's path.
         */
        private void dialogs() {
            boolean russian = tr.russianPlurals();
            DialogLayout.Button cancel = new DialogLayout.Button(russian ? "\u041e\u0442\u043c\u0435\u043d\u0430" : "Cancel", false);
            DialogLayout.Button ok = new DialogLayout.Button(russian ? "\u041e\u041a" : "OK", false);
            String beatmap = "Organic's Decay (Cut Ver.)";
            List<String> errors = new ArrayList<>();
            for (String key : tr.strings.keySet()) {
                if (key.startsWith(WorkshopStrings.DATA + "error.")) {
                    errors.add(tr.strings.get(key));
                }
            }
            List<String> consentStatus = new ArrayList<>(List.of("", k("consent.save_failed")));
            for (String status : consentStatus) {
                dialogs.add(new DialogLayout.State(k("consent.title"), true, List.of(), false, "", status,
                        List.of(new DialogLayout.Button(k("consent.terms"), true), new DialogLayout.Button(k("consent.privacy"), true)),
                        List.of(new DialogLayout.Button(k("consent.decline"), false), new DialogLayout.Button(k("consent.allow"), true))));
            }
            List<String> acceptStatus = new ArrayList<>(List.of("", k("accept.error"), k("accept.sign_in"), k("accept.sending")));
            acceptStatus.addAll(errors);
            for (String status : acceptStatus) {
                dialogs.add(new DialogLayout.State(k("accept.title"), true,
                        List.of(k("accept.terms", "2026-09-01", "2"), k("accept.age")), false, "", status,
                        List.of(new DialogLayout.Button(k("accept.read_terms"), true),
                                new DialogLayout.Button(k("accept.read_community"), true)),
                        List.of(cancel, new DialogLayout.Button(k("accept.continue"), true))));
            }
            List<String> reasons = new ArrayList<>();
            for (String key : tr.strings.keySet()) {
                if (key.startsWith(WorkshopStrings.DATA + "report.reason.")) {
                    reasons.add(tr.strings.get(key));
                }
            }
            for (String status : List.of("", k("report.sign_in"), k("report.details_required"), k("report.sending"))) {
                dialogs.add(new DialogLayout.State(k("report.title", beatmap), true, reasons, true, k("report.details"), status,
                        List.of(new DialogLayout.Button(k("report.legal"), true)),
                        List.of(cancel, new DialogLayout.Button(k("report.send"), true))));
            }
            List<DialogLayout.Button> more = new ArrayList<>();
            for (String action : List.of("more.report", "more.copy_link", "more.update", "more.open_folder", "more.copy_to_tracks",
                    "more.choose_audio", "more.add_to_server", "more.delete", "more.remove_from_server")) {
                more.add(new DialogLayout.Button(k(action), true));
            }
            more.add(cancel);
            dialogs.add(new DialogLayout.State(k("more.title", beatmap), true, List.of(), false, "", "", List.of(), more));
            dialogs.add(new DialogLayout.State(k("more.title", beatmap), true, List.of(), false, "", "", List.of(),
                    List.of(more.get(0), more.get(1), cancel)));
            for (String install : List.of("install.install", "install.replace")) {
                dialogs.add(new DialogLayout.State(k("install.title"), true, List.of(), false, "", "", List.of(),
                        List.of(cancel, new DialogLayout.Button(k(install), true))));
            }
            // The confirmations, each with its own button, and the messages, which carry OK alone.
            String[][] confirmations = {{"delete.title", "delete.confirm"}, {"server.remove_title", "server.remove_confirm"},
                    {"discard.title", "discard.confirm"}, {"comments.delete_title", "delete.confirm"},
                    {"account.sign_out_title", "account.sign_out"}, {"account.link_confirm_title", "account.link_confirm"}};
            for (String[] confirmation : confirmations) {
                dialogs.add(new DialogLayout.State(k(confirmation[0]), true, List.of(), false, "", "", List.of(),
                        List.of(cancel, new DialogLayout.Button(k(confirmation[1]), false))));
            }
            for (String title : List.of("install.failed_title", "file.refused_title", "audio.converting_title", "audio.unsupported_title", "audio.convert_failed_title", "audio.mismatch_title",
                    "audio.added_title", "report.sent_title", "drop.osz_title", "copy.done", "more.copied", "error_title",
                    "comments.server_notice_title", "server.title", "delete.done", "delete.missing", "transfer.refused")) {
                dialogs.add(new DialogLayout.State(k(title), true, List.of(), false, "", "", List.of(), List.of(ok)));
            }
            dialogs.add(new DialogLayout.State(k("install.invalid_title", "organic_decay.blockymap"), true, List.of(), false, "",
                    "", List.of(), List.of(ok)));
        }

        private String k(String key, Object... arguments) {
            return tr.get(WorkshopStrings.KEY + key, arguments);
        }

        private void shells() {
            // The first is the plain Browse shell the content area is taken from. Then every tab active, with and
            // without the server's tab, and every title the shell carries beside a back button and a long name.
            shells.add(new ShellLayout.State(ShellLayout.Tab.BROWSE, false, null, false, null));
            for (ShellLayout.Tab tab : ShellLayout.Tab.values()) {
                for (boolean serverTab : new boolean[] {false, true}) {
                    if (tab != ShellLayout.Tab.SERVER || serverTab) {
                        shells.add(new ShellLayout.State(tab, serverTab, null, false, null));
                    }
                }
            }
            for (String title : List.of("title.set", "title.account", "title.upload", "server.title", "title.listing")) {
                shells.add(new ShellLayout.State(ShellLayout.Tab.BROWSE, true, "Abcdefghijklmnop", true, k(title)));
            }
            shells.add(new ShellLayout.State(ShellLayout.Tab.SERVER, true, "Abcdefghijklmnop", false,
                    k("title.pick") + "  \u00b7  0:42", tr.get("screen.music_battle.vote.truncated")));
        }

        private void listings() {
            List<Filters.Option> categories = Filters.categories(tr);
            // Each single-choice row runs through all its options, so every option is drawn once in its active face;
            // the multi-choice rows are all on and all off by turns.
            for (int step = 0; step < 15; step++) {
                for (boolean more : new boolean[] {false, true}) {
                    Set<String> general = step % 2 == 0 ? new LinkedHashSet<>(List.of("library", "server")) : Set.of();
                    Set<String> extra = step % 2 == 0 ? new LinkedHashSet<>(List.of("fx", "twoside")) : Set.of();
                    String sort = Filters.SORTS.get(step % Filters.SORTS.size());
                    String query = sort.equals("relevance") ? "neurofunk" : "";
                    String category = categories.get(step % categories.size()).id();
                    listings.add(new ListingLayout.State(false, query, category, general, step % 15, (step + 7) % 15,
                            List.of("any", "included", "external").get(step % 3), extra, step % 2 == 1, sort, step % 2 == 0,
                            more, false, true, "title", step == 3 ? 4242L : null, "", "", 9, false, step % 2 == 0));
                }
            }
            String[] statuses = {"empty", "no_matches", "offline", "error", "consent", "loading", "sign_in"};
            for (String status : statuses) {
                listings.add(new ListingLayout.State(false, "", "any", Set.of(), 0, 0, "any", Set.of(), false, "updated", false,
                        false, false, false, "title", null, status, k("state.consent"), status.equals("loading") ? 9 : 0,
                        status.equals("loading"), true));
            }
            for (String sort : Filters.INSTALLED_SORTS) {
                for (String status : new String[] {"", "installed_empty"}) {
                    listings.add(new ListingLayout.State(true, "", "any", Set.of(), 0, 0, "any", Set.of(), false, "updated", false,
                            false, false, false, sort, null, status, "", status.isEmpty() ? 9 : 0, false, false));
                }
            }
        }

        private void cards() {
            String stats = WorkshopStrings.cardStats(tr, 1234, 888, "2026-09-10T10:00:00Z", 1_760_000_000_000L);
            List<String> allBadges = List.of(WorkshopStrings.upper(k("badge.explicit")), WorkshopStrings.upper(k("badge.ai")),
                    WorkshopStrings.upper(k("badge.library")));
            List<String> rightBadges = new ArrayList<>();
            rightBadges.add(null);
            // Every right badge CardRenderer gives a card, by its key: the installed copy's state, or a Workshop set's
            // kind among a battle's server tracks.
            for (String key : RIGHT_BADGES) {
                rightBadges.add(ru.gora.musicbattle.client.workshop.CardRenderer.rightBadgeLabel(tr, key));
            }
            List<String> statuses = List.of("featured", "community", "wip", "pending", "hidden");
            // Each status pill and each right badge at least once in every card form, the two paired off rather than
            // multiplied (the pill and the right badge are sized apart from each other), as many cards as the longer
            // list: pairing them over the five statuses alone never laid out the sixth badge, "Workshop".
            int count = Math.max(statuses.size(), rightBadges.size());
            for (boolean extra : new boolean[] {false, true}) {
                for (boolean hover : new boolean[] {false, true}) {
                    for (int index = 0; index < count; index++) {
                        List<Double> dots = new ArrayList<>();
                        for (int i = 0; i < (index % 2 == 0 ? 14 : 5); i++) {
                            dots.add(1.0 + i * 0.5);
                        }
                        String rightBadge = rightBadges.get(index % rightBadges.size());
                        if (rightBadge != null) {
                            cardRightBadges.add(rightBadge);
                        }
                        cards.add(new CardLayout.State(extra, hover, false, false,
                                "An extremely long beatmap title that has to be cut", "Neko Hacker feat. Nanahira",
                                k("card.mapped_by", "a_long_mapper_name"), "Original soundtrack of something",
                                WorkshopStrings.status(tr, statuses.get(index % statuses.size())), allBadges, dots, stats,
                                rightBadge, index % 2 == 0, index % 3 == 0, 0, false));
                    }
                }
            }
            // Short titles are shown whole, so their badges must not wait on room for an ellipsis the title never
            // shows: titles of eight letters or fewer, and nine-letter ones whose ninth letter is narrower than the
            // ellipsis ("Lagtrain!", "Лагтрейн!"), which the minimum also keeps whole. Nine-letter titles whose ninth
            // letter is wider than the ellipsis ("Lagtrainw", "Лагтрейнж") are the other side of the same rule: the
            // minimum cuts them to eight letters and the ellipsis, so a rule that counted letters instead of comparing
            // widths would hold back room for their whole title and send a badge that fits into the chip. Each comes
            // with one badge or two, beside every right badge, in both card forms. The hovered card is laid out by
            // sameOnHover from each of these.
            String explicit = allBadges.get(0);
            String ai = allBadges.get(1);
            String library = allBadges.get(2);
            for (String title : List.of("A", "Fade", "Gurenge", "Lagtrain", "Exactly8", "Lagtrain!", "Лагтрейн!", "Lagtrainw",
                    "Лагтрейнж")) {
                for (List<String> badges : List.of(allBadges, List.of(ai), List.of(explicit, ai), List.of(ai, library))) {
                    for (String rightBadge : rightBadges) {
                        for (boolean extra : new boolean[] {false, true}) {
                            cards.add(new CardLayout.State(extra, false, false, false, title, "Kanaria",
                                    k("card.mapped_by", "mapper"), "Original", WorkshopStrings.status(tr, "community"), badges,
                                    List.of(2.0, 3.5, 5.0), stats, rightBadge, false, false, 0, false));
                        }
                    }
                }
            }
        }

        private void tracks() {
            // The pills ListingView.trackState gives: the Workshop's own word for its kind, the vote's for the others.
            for (String chip : List.of(WorkshopStrings.KEY + "card.kind.workshop", "screen.music_battle.vote.chip.server",
                    "screen.music_battle.vote.chip.players")) {
                for (boolean extra : new boolean[] {false, true}) {
                    tracks.add(new TrackCardLayout.State(extra, extra, "A track title somebody typed into the editor",
                            k("card.mapped_by", "a_long_mapper_name"), k("set.bpm") + " 174" + TrackCardLayout.DETAIL_SEPARATOR
                            + "12:04", k("card.charts", "12"), WorkshopStrings.upper(tr.get(chip))));
                }
            }
        }

        private void setPages() {
            List<String> installs = List.of("set.install", "set.installed", "set.installing", "set.server_track",
                    "set.downloading", "set.update", "set.unavailable", "set.retry", "set.choose");
            List<String> statuses = List.of("featured", "community", "wip", "pending", "hidden");
            List<String> cannot = new ArrayList<>();
            for (String key : tr.strings.keySet()) {
                if (key.startsWith(WorkshopStrings.KEY + "set.cannot.")) {
                    cannot.add(tr.strings.get(key));
                }
            }
            for (int index = 0; index < installs.size(); index++) {
                String install = installs.get(index);
                boolean choose = install.equals("set.choose");
                for (int variant = 0; variant < 2; variant++) {
                    boolean rateable = variant == 0;
                    setPages.add(new SetPageLayout.State(
                            "Organic's Decay (Cut Ver.) — an unusually long title for the layout",
                            "XenjeS feat. a very long artist name",
                            List.of(new SetPageLayout.Badge("explicit", WorkshopStrings.upper(k("badge.explicit"))),
                                    new SetPageLayout.Badge("ai", WorkshopStrings.upper(k("badge.ai"))),
                                    new SetPageLayout.Badge("library", WorkshopStrings.upper(k("badge.library")))),
                            WorkshopStrings.status(tr, statuses.get(index % statuses.size())), "Insane with a long name",
                            List.of(1.4, 2.8, 4.2, 6.7), k("card.mapped_by", "a_long_mapper_name"),
                            k("set.submitted", WorkshopStrings.relative(tr, "2026-01-10T10:00:00Z", 1_760_000_000_000L)),
                            k(variant == 0 ? "set.updated" : "set.featured",
                                    WorkshopStrings.relative(tr, "2026-09-10T10:00:00Z", 1_760_000_000_000L)),
                            WorkshopStrings.plural(tr, WorkshopStrings.KEY + "card.downloads", 1234) + "   "
                                    + WorkshopStrings.plural(tr, WorkshopStrings.KEY + "card.favourites", 888),
                            variant == 0 ? tr.get(WorkshopStrings.DATA + "availability.audio_removed") : k("set.local_mode"),
                            k(install), choose ? "Insane with a long name" : variant == 0 ? "7.0 MiB" : null,
                            !install.equals("set.unavailable"), variant == 1, true, install.equals("set.downloading"), 0.4F,
                            "A long description that wraps over several lines, with a link "
                                    + "https://blocky-music.qwu.world/beatmapsets/42 inside it.",
                            variant == 1, "Thanks to everyone who tested this map.", "Featured because the chart is excellent.",
                            List.of(new SetPageLayout.Stat(k("set.source"), "Original soundtrack"),
                                    new SetPageLayout.Stat(k("set.genre"), WorkshopStrings.genre(tr, 7)),
                                    new SetPageLayout.Stat(k("set.language"), WorkshopStrings.language(tr, 13)),
                                    new SetPageLayout.Stat(k("set.tags"), "neurofunk dnb drum and bass")),
                            List.of(new SetPageLayout.Stat(k("set.length"), "12:04"), new SetPageLayout.Stat(k("set.bpm"), "174"),
                                    new SetPageLayout.Stat(k("set.notes"), "10790"), new SetPageLayout.Stat(k("set.holds"), "8300")),
                            List.of(new SetPageLayout.Attribute(k("set.attr.stars"), "16.72", 0.67F),
                                    new SetPageLayout.Attribute(k("set.attr.density"), "19.8", 0.5F),
                                    new SetPageLayout.Attribute(k("set.attr.holds"), "100%", 1F),
                                    new SetPageLayout.Attribute(k("set.attr.effects"), "128", 0.24F)),
                            "8.21", List.of(0, 0, 1, 0, 2, 1, 3, 4, 2, 1), "1234", "5678", rateable ? (index % 2 == 0 ? 7 : null)
                                    : null, rateable, rateable ? null : cannot.get(index % cannot.size()),
                            variant == 0 ? k("set.audio.hosted", "12:04") : k("set.audio.external", "12:04", "7.0 MiB",
                                    "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"),
                            List.of(new SetPageLayout.ChartRow("easy", "Easy", 1.4, "320", "12", "4", "4:22"),
                                    new SetPageLayout.ChartRow("insane", "Insane with a long difficulty name", 6.7, "10790",
                                            "8300", "128", "12:04")),
                            variant == 1, false, 1));
                }
            }
            // A server beatmap's page, with a long title, maker and description, and with nothing known about it.
            setPages.add(SetPageLayout.serverTrack(tr, "Organic's Decay (Cut Ver.) — an unusually long title for the layout",
                    "players", WorkshopStrings.upper(tr.get("screen.music_battle.vote.chip.players")),
                    k("card.mapped_by", "a_long_mapper_name"), 174, 724_000L,
                    "A long description that wraps over several lines, written in the editor by whoever made it.",
                    k("set.server_track")));
            setPages.add(SetPageLayout.serverTrack(tr, "Title", "server",
                    WorkshopStrings.upper(tr.get("screen.music_battle.vote.chip.server")), "", 0, 0L, "", k("set.server_track")));
            // Every reason a rating is refused, in the box that says it.
            for (String reason : cannot) {
                setPages.add(new SetPageLayout.State("Title", "Artist", List.of(), WorkshopStrings.status(tr, "community"),
                        "Easy", List.of(1.0), "", "", "", "", "", k("set.install"), null, true, false, true, false, 0F, "",
                        false, "", "", List.of(), List.of(), List.of(), "—", List.of(0, 0, 0, 0, 0, 0, 0, 0, 0, 0), "0", "0",
                        null, false, reason, "", List.of(), false, false, 0));
            }
        }

        private void comments() {
            List<CommentsLayout.Row> rows = new ArrayList<>();
            int[] votes = {0, 12, 999, 1234};
            for (int depth = 0; depth <= 2; depth++) {
                rows.add(new CommentsLayout.Row(100 + depth, depth, "a_very_long_player_name",
                        List.of(WorkshopStrings.upper(k("comments.badge.mapper")), WorkshopStrings.roleBadge(tr, "admin"),
                                WorkshopStrings.roleBadge(tr, "mod"), WorkshopStrings.roleBadge(tr, "curator")),
                        depth == 0 ? null : "↩ someone_else",
                        "A comment body that is long enough to wrap over several lines in every language, with a link to "
                                + "https://qwu.world and some more words after it.",
                        WorkshopStrings.relative(tr, "2026-09-10T10:00:00Z", 1_760_000_000_000L), votes[depth], depth == 1,
                        true, true, true, true, depth == 0 ? 2 : 0, depth == 0 ? 25 : 0, false, depth == 2, null));
            }
            rows.add(new CommentsLayout.Row(103, 0, "removed_by_staff", List.of(), null, "",
                    WorkshopStrings.relative(tr, "2026-08-11T10:00:00Z", 1_760_000_000_000L), 1234, false, false, false,
                    false, false, 0, 0, false, false, "moderator"));
            rows.add(new CommentsLayout.Row(104, 1, "held_for_review", List.of(), "↩ removed_by_staff", "",
                    WorkshopStrings.relative(tr, "2026-08-11T11:00:00Z", 1_760_000_000_000L), 0, false, false, false, false,
                    false, 0, 0, false, false, "pending_review"));
            rows.add(new CommentsLayout.Row(105, 0, "gone", List.of(), null, "",
                    WorkshopStrings.relative(tr, "2025-08-11T11:00:00Z", 1_760_000_000_000L), 3, false, false, false, false,
                    false, 0, 0, true, false, null));
            List<String> closed = new ArrayList<>();
            closed.add(null);
            for (String key : tr.strings.keySet()) {
                if (key.startsWith(WorkshopStrings.KEY + "comments.cannot_post.")) {
                    closed.add(tr.strings.get(key));
                }
            }
            for (String sort : CommentsLayout.SORTS) {
                for (boolean signedIn : new boolean[] {false, true}) {
                    for (boolean server : new boolean[] {false, true}) {
                        comments.add(new CommentsLayout.State(12345, sort, signedIn, null, signedIn, rows, true, 12345, false,
                                true, server, k(server ? "comments.server_placeholder" : "comments.placeholder"), 24F,
                                signedIn && !server, server ? k("comments.server_local_only") + " "
                                + k("comments.server_names_unverified") : null, signedIn ? "a_very_long_player_name" : null,
                                signedIn ? 4800 : 0, 5000));
                    }
                }
            }
            for (String reason : closed) {
                comments.add(new CommentsLayout.State(0, "new", false, reason, true, List.of(), false, 0, false, false, false,
                        k("comments.placeholder"), 24F, false, null, null, 0, 0));
            }
            comments.add(new CommentsLayout.State(0, "top", false, null, false, List.of(), false, 0, true, false, false,
                    k("comments.placeholder"), 24F, false, null, null, 0, 0));
            // Replies by commenters with no badge up to all four, short and long names, answering a short name, a long
            // one and a deleted account, at both reply depths: each has to say whom it answers, after its badges or on
            // a line of its own, wherever the name gives way.
            String mapper = WorkshopStrings.upper(k("comments.badge.mapper"));
            String admin = WorkshopStrings.roleBadge(tr, "admin");
            String mod = WorkshopStrings.roleBadge(tr, "mod");
            String curator = WorkshopStrings.roleBadge(tr, "curator");
            List<CommentsLayout.Row> replies = new ArrayList<>();
            long replyId = 200;
            for (String name : List.of("bob", "someone_else", "a_very_long_player_name")) {
                for (List<String> badges : List.of(List.<String>of(), List.of(mod), List.of(mapper, mod), List.of(admin, mod, curator),
                        List.of(mapper, admin, mod, curator))) {
                    for (String parent : List.of("↩ fourth", "↩ a_very_long_player_name", "↩ " + k("comments.deleted_user"))) {
                        for (int depth = 1; depth <= 2; depth++) {
                            replies.add(new CommentsLayout.Row(replyId++, depth, name, badges, parent, "A reply.",
                                    WorkshopStrings.relative(tr, "2026-09-10T10:00:00Z", 1_760_000_000_000L), 3, false, true,
                                    depth < 2, false, true, 0, 0, false, false, null));
                        }
                    }
                }
            }
            comments.add(new CommentsLayout.State(replies.size(), "new", false, null, false, replies, false, 0, false, true, false,
                    k("comments.placeholder"), 24F, false, null, null, 0, 0));
        }

        private void accounts() {
            AccountForm.Card card = new AccountForm.Card(
                    List.of(new Form.Chip("admin", k("account.role.admin")), new Form.Chip("mod", k("account.role.mod")),
                            new Form.Chip("curator", k("account.role.curator"))),
                    k("account.profile.since", "September 2026"),
                    List.of(new Form.Stat("9999", k("account.stat.maps")), new Form.Stat("999", k("account.stat.featured")),
                            new Form.Stat("12.3K", k("account.stat.favourites")),
                            new Form.Stat("9999", k("account.stat.comments"))),
                    true);
            for (AccountForm.Mode mode : AccountForm.Mode.values()) {
                for (boolean full : new boolean[] {false, true}) {
                    AccountForm.State state = new AccountForm.State(mode, full, full, "BDFG-HJKL",
                            k(full ? "account.waiting" : "account.checking"), full ? k("account.auto_disabled") : null,
                            "Abcdefghijklmnop", "A display name somebody chose",
                            List.of(new Form.Row("account.mc.1", "Abcdefghijklmnop", k(full ? "account.link.auto"
                                    : "account.link.manual"))),
                            full, full, full ? List.of("Your beatmap \"Organic's Decay\" was approved.") : List.of(),
                            full ? k("account.standing.restricted") + " " + k("account.standing.strikes", "3") : null, 12,
                            full, full ? k("account.rules.on", WorkshopStrings.relative(tr, "2026-09-10T10:00:00Z",
                            1_760_000_000_000L)) : k("account.rules.none"),
                            full ? k("account.rules.changed") : null, full, full ? card : null, full);
                    accounts.add(AccountForm.build(tr, state));
                }
            }
        }

        private void uploads() {
            String k = WorkshopStrings.KEY + "wizard.";
            List<UploadLayout.Chart> two = List.of(
                    new UploadLayout.Chart("Organic's Decay", tr.get(k + "track.detail", "organic_decay", "4:22", "1079"), true),
                    new UploadLayout.Chart("Organic's Decay (Easy)", tr.get(k + "track.detail", "organic_easy", "4:22", "320"),
                            false));
            List<UploadLayout.Chart> many = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                many.add(new UploadLayout.Chart("A rather long track title that will not fit on one line of a small window #" + i,
                        tr.get(k + "track.detail", "a_very_long_track_identifier_" + i, "12:04", "4096"), i % 2 == 0));
            }
            Map<String, String> text = new LinkedHashMap<>();
            text.put("title", "Organic's Decay");
            text.put("artist", "XenjeS");
            text.put("genre", WorkshopStrings.genre(tr, 7));
            text.put("language", WorkshopStrings.language(tr, 13));
            text.put("audio.file", "organic_decay_final_master_v3.ogg");
            text.put("cover.name", "preview.png");
            Map<String, String> blank = new LinkedHashMap<>(text);
            blank.put("title", "");
            blank.put("artist", "");
            blank.put("audio.file", "");
            Map<String, Float> heights = Map.of("wizard.description", 120F, "wizard.credits", 44F);
            Set<String> ticked = new HashSet<>(Set.of("accept.terms", "flag.explicit", "confirm.rights"));
            List<UploadLayout.Issue> none = List.of();
            List<UploadLayout.Issue> issues = List.of(new UploadLayout.Issue(tr.get(k + "problem.no_charts"), false),
                    new UploadLayout.Issue(tr.get(k + "problem.versions_unknown"), true));
            Map<String, String> errors = Map.of("title", tr.get(k + "problem.title"), "artist", tr.get(k + "problem.artist"));
            List<UploadLayout.Stage> running = List.of(
                    new UploadLayout.Stage(tr.get(k + "stage.preparing"), UploadLayout.StageState.DONE, List.of(), -1),
                    new UploadLayout.Stage(tr.get(k + "stage.uploading"), UploadLayout.StageState.RUNNING,
                            List.of("organic_decay.json  12.0 KiB / 30.0 KiB"), 0.4F),
                    new UploadLayout.Stage(tr.get(k + "stage.processing"), UploadLayout.StageState.PENDING, List.of(), -1),
                    new UploadLayout.Stage(tr.get(k + "stage.finishing"), UploadLayout.StageState.PENDING, List.of(), -1));
            List<UploadLayout.Stage> failed = List.of(
                    new UploadLayout.Stage(tr.get(k + "stage.preparing"), UploadLayout.StageState.DONE, List.of(), -1),
                    new UploadLayout.Stage(tr.get(k + "stage.uploading"), UploadLayout.StageState.FAILED, List.of(), -1),
                    new UploadLayout.Stage(tr.get(k + "stage.processing"), UploadLayout.StageState.CANCELLED, List.of(), -1),
                    new UploadLayout.Stage(tr.get(k + "stage.finishing"), UploadLayout.StageState.PENDING, List.of(), -1));
            String failure = tr.get(k + "problem.title");
            for (boolean update : new boolean[] {false, true}) {
                String u = update ? " update" : "";
                add("rules" + u, UploadLayout.Step.RULES, update, two, "upload", "preview", true, text, heights, ticked,
                        "community", null, "", List.of(), null, null, false, false, none, Map.of());
                add("rules changed busy" + u, UploadLayout.Step.RULES, update, two, "upload", "preview", true, text, heights,
                        ticked, "community", tr.get(WorkshopStrings.KEY + "account.rules.changed"), "", List.of(), null, null,
                        false, true, issues, Map.of());
                add("files" + u, UploadLayout.Step.FILES, update, two, "upload", "preview", true, text, heights, ticked,
                        "community", null, "", List.of(), null, null, false, false, none, Map.of());
                add("files empty" + u, UploadLayout.Step.FILES, update, List.of(), "upload", "none", false, blank, heights,
                        ticked, "community", null, "", List.of(), null, null, false, false, issues, Map.of());
                add("files many external" + u, UploadLayout.Step.FILES, update, many, "external", "file", false, text, heights,
                        ticked, "community", null, "", List.of(), null, null, false, false, none, Map.of());
                add("details" + u, UploadLayout.Step.DETAILS, update, two, "upload", "preview", true, text, Map.of(), ticked,
                        "community", null, "", List.of(), null, null, false, false, none, Map.of());
                add("details errors" + u, UploadLayout.Step.DETAILS, update, two, "upload", "preview", true, blank, heights,
                        ticked, "community", null, "", List.of(), null, null, false, false, issues, errors);
                add("publish" + u, UploadLayout.Step.PUBLISH, update, two, "upload", "preview", true, text, heights, ticked,
                        "community", null, "2 days ago", List.of(), null, null, false, false, none, Map.of());
                add("publish wip blank" + u, UploadLayout.Step.PUBLISH, update, many, "external", "none", false, blank,
                        heights, Set.of(), "wip", null, "", List.of(), null, null, false, false, issues, Map.of());
                add("progress" + u, UploadLayout.Step.PROGRESS, update, two, "upload", "preview", true, text, heights, ticked,
                        "community", null, "", running, null, null, true, true, none, Map.of());
                add("progress failed" + u, UploadLayout.Step.PROGRESS, update, two, "upload", "preview", true, text, heights,
                        ticked, "community", null, "", failed, failure, null, false, false, none, Map.of());
                for (String status : new String[] {"community", "wip", "pending"}) {
                    add("result " + status + u, UploadLayout.Step.PROGRESS, update, two, "upload", "preview", true, text,
                            heights, ticked, status, null, "", failed, null, status, false, false, none, Map.of());
                }
            }
        }

        private void add(String name, UploadLayout.Step step, boolean update, List<UploadLayout.Chart> charts, String audio,
                         String cover, boolean coverImage, Map<String, String> text, Map<String, Float> heights,
                         Set<String> ticked, String target, String rulesChanged, String acceptedOn,
                         List<UploadLayout.Stage> stages, String failure, String result, boolean running, boolean busy,
                         List<UploadLayout.Issue> issues, Map<String, String> errors) {
            uploads.add(new Variant(name, new UploadLayout.State(step, true, update, "#4242", charts,
                    "music_battle_tracks/my_tracks", audio, cover, coverImage, text, heights, ticked, target, 95_000L, 262_000L,
                    false, rulesChanged, "2026-09-01", "2", acceptedOn, stages, failure, result, running, busy, issues, errors,
                    ticked.contains("accept.terms"), true)));
        }
    }

    /** A language file as the screens read it, with {@code %s} arguments put in. */
    private static final class LangTr implements Tr {
        final Map<String, String> strings = new LinkedHashMap<>();
        private final boolean russian;

        LangTr(Path file) throws Exception {
            for (Map.Entry<String, JsonElement> entry : JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8))
                    .getAsJsonObject().entrySet()) {
                strings.put(entry.getKey(), entry.getValue().getAsString());
            }
            russian = "ru".equals(strings.get(WorkshopStrings.KEY + "plural_rule"));
        }

        @Override
        public String get(String key, Object... arguments) {
            String value = strings.get(key);
            if (value == null) {
                throw new AssertionError("missing language key " + key);
            }
            if (arguments.length == 0) {
                return value;
            }
            return String.format(Locale.ROOT, value, arguments);
        }

        @Override
        public boolean russianPlurals() {
            return russian;
        }
    }
}

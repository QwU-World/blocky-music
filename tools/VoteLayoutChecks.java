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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import ru.gora.musicbattle.client.ui.TextMeasurer;
import ru.gora.musicbattle.client.ui.TextStyle;
import ru.gora.musicbattle.client.workshop.layout.BattleSetup;
import ru.gora.musicbattle.client.workshop.layout.BattleSetupLayout;
import ru.gora.musicbattle.client.workshop.layout.BattleTracks;
import ru.gora.musicbattle.client.workshop.layout.CardLayout;
import ru.gora.musicbattle.client.workshop.layout.Layout;
import ru.gora.musicbattle.client.workshop.layout.ListingLayout;
import ru.gora.musicbattle.client.workshop.layout.Metrics;
import ru.gora.musicbattle.client.workshop.layout.Rect;
import ru.gora.musicbattle.client.workshop.layout.ShellLayout;
import ru.gora.musicbattle.client.workshop.layout.TextFit;
import ru.gora.musicbattle.client.workshop.layout.Tr;
import ru.gora.musicbattle.client.workshop.layout.TrackCardLayout;
import ru.gora.musicbattle.client.workshop.layout.WorkshopStrings;

/**
 * The {@code vote-layout} regression: the battle setup screen at 320x240, 426x240, 640x360, 854x480 and 1280x720, in
 * English and Russian, solo, against another player and against a mob, with the map slot empty, filled, downloading,
 * held, refused and holding one of the server's own tracks; every reason a map can be refused, laid out whole; the
 * rules that decide where a chosen map stands; the battle's track list the Workshop picker shows; and the cards the
 * picker draws for the server's own tracks.
 *
 * <p>It used to run the screen itself and capture {@code graphics.fill} and {@code drawString} through a proxy. The
 * screen now draws rounded shapes and TrueType text through the Workshop's toolkit, which that proxy cannot see at
 * all: the assertions would have passed by measuring nothing. So the geometry moved into {@link BattleSetupLayout}
 * and the choices into {@link BattleSetup}, and this measures those - with the bundled Nunito faces through
 * java.awt, like {@code workshop-screens} - and presses the very rectangles the screen hit-tests.
 */
public final class VoteLayoutChecks {
    private static final int[][] SIZES = {{320, 240}, {426, 240}, {640, 360}, {854, 480}, {960, 540}, {1280, 720}};
    private static final String[] LANGUAGES = {"en_us", "ru_ru"};
    private static final float SLACK = 0.75F;
    private static final String SETUP_KEY = "screen.music_battle.vote.setup.";
    private static final String SCREEN = "src/main/java/ru/gora/musicbattle/client/MusicBattleVoteScreen.java";
    /**
     * Keys this change adds, with the words proposed for them. They are measured from here until the language files
     * carry them, so a note that would not fit at 320 units fails now rather than after the translation lands.
     */
    private static final Map<String, String[]> PROPOSED = Map.of(
            SETUP_KEY + "mobs", new String[] {"%s vs %s", "%s \u043f\u0440\u043e\u0442\u0438\u0432 %s"},
            "screen.music_battle.workshop.picker.hint", new String[] {"Click a map to choose it",
                    "\u041d\u0430\u0436\u043c\u0438\u0442\u0435 \u043d\u0430 \u043a\u0430\u0440\u0442\u0443, \u0447\u0442\u043e\u0431\u044b \u0432\u044b\u0431\u0440\u0430\u0442\u044c \u0435\u0451"},
            SETUP_KEY + "mapper", new String[] {"Mapped by", "\u0410\u0432\u0442\u043e\u0440 \u043a\u0430\u0440\u0442\u044b"},
            SETUP_KEY + "checking", new String[] {"Checking this map with the Workshop\u2026",
                    "\u041f\u0440\u043e\u0432\u0435\u0440\u044f\u0435\u043c \u043a\u0430\u0440\u0442\u0443 \u0432 \u041c\u0430\u0441\u0442\u0435\u0440\u0441\u043a\u043e\u0439\u2026"},
            SETUP_KEY + "own_audio", new String[] {
                    "This map plays a song you add yourself, and that file cannot be sent to the server. Choose another map.",
                    "\u041f\u0435\u0441\u043d\u044e \u043a \u044d\u0442\u043e\u0439 \u043a\u0430\u0440\u0442\u0435 \u0432\u044b \u0434\u043e\u0431\u0430\u0432\u043b\u044f\u0435\u0442\u0435 \u0441\u0430\u043c\u0438, \u0430 \u0442\u0430\u043a\u043e\u0439 \u0444\u0430\u0439\u043b \u043d\u0435\u043b\u044c\u0437\u044f \u043f\u0435\u0440\u0435\u0434\u0430\u0442\u044c \u0441\u0435\u0440\u0432\u0435\u0440\u0443. \u0412\u044b\u0431\u0435\u0440\u0438\u0442\u0435 \u0434\u0440\u0443\u0433\u0443\u044e \u043a\u0430\u0440\u0442\u0443."});

    /** The three battles the vote screen is opened for; each offers a different set of settings rows. */
    private enum Battle {
        SOLO(true, false), DUEL(false, false), MOB(false, true);

        private final boolean solo;
        private final boolean mob;

        Battle(boolean solo, boolean mob) {
            this.solo = solo;
            this.mob = mob;
        }

        BattleSetup setup() {
            return new BattleSetup(solo, mob, "TEST_RAP_GOD_MASTER");
        }
    }

    /**
     * How far the chosen map is, which is what the slot has to say: whether the vote can go out, whether the slot
     * names a problem, and whether it carries the download's progress.
     */
    private enum Stage {
        EMPTY(false, false, false),
        CHOSEN(true, false, false),
        FETCHING(true, false, true),
        HELD(false, false, true),
        CHECKING(true, false, false),
        // A failed download is still a problem the slot names, but the vote stays on: its press tries again.
        BROKEN(true, true, false),
        OWN_AUDIO(false, true, false),
        SERVER_TRACK(true, false, false),
        PLAYERS_TRACK(true, false, false);

        private final boolean confirmable;
        private final boolean problem;
        private final boolean bar;

        Stage(boolean confirmable, boolean problem, boolean bar) {
            this.confirmable = confirmable;
            this.problem = problem;
            this.bar = bar;
        }
    }

    private static Path root;
    private static int checks;
    private static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        root = Path.of(args.length > 0 ? args[0] : ".");
        AwtMeasurer measurer = new AwtMeasurer(root.resolve("src/main/resources/assets/music_battle/font"));
        for (int l = 0; l < LANGUAGES.length; l++) {
            String language = LANGUAGES[l];
            LangTr tr = new LangTr(root.resolve("src/main/resources/assets/music_battle/lang/" + language + ".json"), l);
            for (int[] size : SIZES) {
                for (Battle battle : Battle.values()) {
                    String context = language + " " + size[0] + "x" + size[1] + " " + battle;
                    for (Stage stage : Stage.values()) {
                        frame(measurer, tr, size[0], size[1], battle, stage, context + " " + stage);
                    }
                    inputs(measurer, tr, size[0], size[1], battle, context);
                    words(measurer, tr, size[0], size[1], battle, context);
                }
                slotContent(measurer, tr, size[0], size[1], language + " " + size[0] + "x" + size[1]);
                notes(measurer, tr, size[0], size[1], language + " " + size[0] + "x" + size[1]);
                warning(measurer, tr, size[0], size[1], language + " " + size[0] + "x" + size[1]);
                trackCards(measurer, tr, size[0], size[1], language + " " + size[0] + "x" + size[1]);
                pickerChrome(measurer, tr, size[0], size[1], language + " " + size[0] + "x" + size[1]);
            }
        }
        decisions();
        heldVotes();
        battleTracks();
        screenSource();
        if (!failures.isEmpty()) {
            for (String failure : failures.subList(0, Math.min(25, failures.size()))) {
                System.out.println("FAIL " + failure);
            }
            throw new AssertionError(failures.size() + " / " + checks + " vote screen failures");
        }
        System.out.println("PASS: " + checks + " battle setup layout, input, decision and picker-list assertions,"
                + " two languages, six sizes, solo/duel/mob");
        System.out.println("NOT COVERED: real Minecraft font rasterisation, GPU output, the Workshop picker screen and"
                + " its network answers");
    }

    // ---------------------------------------------------------------- the frame

    /** One state of the screen: every box inside its parent, nothing overlapping, no text wider than its box. */
    private static void frame(TextMeasurer measurer, Tr tr, int width, int height, Battle battle, Stage stage,
                              String context) {
        BattleSetup setup = battle.setup();
        stage(setup, stage);
        BattleSetupLayout.State state = state(setup, tr, stage != Stage.EMPTY, stage != Stage.EMPTY);
        Layout layout = BattleSetupLayout.compute(width, height, measurer, tr, state);
        verify(layout, measurer, new Rect(0, 0, width, height), context);

        Rect panel = layout.rect(BattleSetupLayout.PANEL);
        Rect titleBar = layout.rect(BattleSetupLayout.TITLE_BAR);
        Rect timer = layout.rect(BattleSetupLayout.TIMER);
        Rect left = layout.rect(BattleSetupLayout.LEFT);
        Rect map = layout.rect(BattleSetupLayout.MAP);
        Rect slot = layout.rect(BattleSetupLayout.SLOT);
        Rect footer = layout.rect(BattleSetupLayout.FOOTER);
        // The bands of the screen in reading order; none of them is a panel the no-overlap rule covers.
        check(timer.y() >= titleBar.bottom() - SLACK, context + " the countdown sits on the title bar");
        check(left.y() >= timer.bottom() - SLACK && map.y() >= timer.bottom() - SLACK,
                context + " the body sits on the countdown");
        check(left.bottom() <= footer.y() + SLACK && map.bottom() <= footer.y() + SLACK,
                context + " the body runs into the footer");
        check(footer.bottom() <= height + SLACK, context + " the footer is off the screen");
        check(panel.w() <= width + SLACK && panel.right() <= width + SLACK, context + " the panel is wider than the screen");
        check(slot.w() >= 40F && slot.h() >= 24F, context + " the map slot is too small to press: " + slot);
        check(!layout.rect(BattleSetupLayout.TIMER_BAR).empty(),
                context + " the vote countdown is gone, and a player battle is still a vote");
        check(!layout.rect(BattleSetupLayout.CONFIRM).empty() && !layout.rect(BattleSetupLayout.CANCEL).empty(),
                context + " the footer lost an action");

        // The rows this battle offers, and only those.
        check(has(layout, BattleSetup.ROW_NOTES) == (battle != Battle.SOLO),
                context + " the note mode belongs to a player battle only");
        check(has(layout, BattleSetup.ROW_DEADLY) == (battle != Battle.SOLO),
                context + " the deadly party belongs to a player battle only");
        check(has(layout, BattleSetup.ROW_SKILL) == (battle == Battle.MOB),
                context + " the mob skill belongs to a mob battle only");
        check(has(layout, BattleSetup.ROW_SPEED), context + " every battle offers the speed");
        boolean serverTrack = stage == Stage.SERVER_TRACK || stage == Stage.PLAYERS_TRACK;
        // Every row the battle offers has to be on screen: a row that does not fit is a setting nobody can change.
        for (BattleSetupLayout.Row row : state.rows()) {
            check(has(layout, row.id()), context + " the " + row.id() + " row was dropped for want of room");
        }

        if (stage == Stage.EMPTY) {
            check(!state.confirmEnabled(), context + " the confirm action is live with no map chosen");
            check(state.slot().empty() && !layout.rect("setup.map.empty.0").empty(),
                    context + " the empty slot does not invite anything");
            check(layout.rect(BattleSetupLayout.SLOT_BAR).empty(), context + " an empty slot shows a progress bar");
        } else {
            check(state.confirmEnabled() == stage.confirmable,
                    context + " the confirm action does not follow the map's state");
            check(!layout.rect("setup.map.title.0").empty(), context + " the slot does not name the chosen map");
            if (serverTrack) {
                // One of the server's own tracks has one chart, so there is no difficulty to name or to choose.
                check(layout.rect("setup.map.difficulty").empty() && !has(layout, BattleSetup.ROW_DIFFICULTY),
                        context + " one of the server's own tracks offers a difficulty it does not have");
                check(!layout.rect("setup.map.badge").empty(),
                        context + " the slot does not say the track is the server's or a player's");
            } else {
                check(!layout.rect("setup.map.difficulty").empty(), context + " the slot does not say which difficulty");
                check(!layout.rect("setup.map.badge").empty() || map.w() < BattleSetupLayout.TWO_COLUMN_WIDTH * 0.4F,
                        context + " the slot does not say where the map came from");
            }
            String note = state.slot().note();
            if (!note.isEmpty()) {
                check(!layout.rect("setup.map.note.0").empty(), context + " the slot dropped its note: " + note);
            }
        }
        check(layout.rect(BattleSetupLayout.SLOT_BAR).empty() == !stage.bar,
                context + " the download progress is not in the slot");
        check(state.slot().problem() == stage.problem, context + " a map that cannot be used does not say so");
        if (stage == Stage.HELD) {
            // The press was taken: the slot says the vote waits for the map, over the download's own line.
            check(state.slot().note().equals(tr.get(SETUP_KEY + "waiting")),
                    context + " a held vote does not say it is held: " + state.slot().note());
        }
        // The other player's card is what keeps this a vote rather than a menu. A 240 unit window gives it up on
        // purpose, where the countdown alone has to say that somebody else is choosing too. A mob never votes, so a
        // mob battle has no such card, even when the screen hands one in.
        check(state.voterTitle().isEmpty() == (battle != Battle.DUEL),
                context + " the other voter is named in a battle without one, or missing from a player battle");
        if (battle != Battle.DUEL) {
            check(layout.rect(BattleSetupLayout.VOTER).empty(), context + " a battle without a voter shows a voter's card");
        } else if (height > 240) {
            check(!layout.rect(BattleSetupLayout.VOTER).empty(),
                    context + " the other voter's card is gone from a player battle");
        }
    }

    /**
     * Every state the slot can be in, in the busiest battle the screen has, with every line of its note laid out: a
     * refusal is the one thing the slot has to say, and a note cut short says half of why.
     */
    private static void notes(TextMeasurer measurer, Tr tr, int width, int height, String context) {
        for (BattleSetup.Fetch fetch : BattleSetup.Fetch.values()) {
            for (boolean longTitle : new boolean[] {false, true}) {
                BattleSetup setup = Battle.MOB.setup();
                setup.setPick(longTitle ? pick() : new BattleSetup.Pick(42L, 1, "", "", "Ok", "", null,
                        List.of("easy", "hard"), List.of("Easy", "Hard"), 1, BattleSetup.Origin.INSTALLED));
                setup.setFetch(fetch, fetch == BattleSetup.Fetch.FAILED ? "music_battle.workshop.error.network_error" : "");
                BattleSetupLayout.State state = state(setup, tr, true, true);
                Layout layout = BattleSetupLayout.compute(width, height, measurer, tr, state);
                String where = context + " " + fetch + (longTitle ? " long title" : "");
                verify(layout, measurer, new Rect(0, 0, width, height), where);
                String note = state.slot().note();
                check(fetch == BattleSetup.Fetch.READY || !note.isEmpty(), where + " the state has nothing to say");
                if (note.isEmpty()) {
                    continue;
                }
                check(!note.startsWith("screen.") && !note.startsWith("music_battle."), where + " the note is a key: " + note);
                Rect slot = layout.rect(BattleSetupLayout.SLOT);
                float inner = slot.w() - (Metrics.compact(width, height) ? Metrics.PAD_SM : Metrics.PAD) * 2;
                int wanted = TextFit.wrap(measurer, note, TextStyle.CAPTION, inner).size();
                int laid = layout.group("setup.map.note.").size();
                check(wanted <= 3, where + " the note needs " + wanted + " lines in a slot " + inner + " wide: " + note);
                check(laid == Math.min(3, wanted), where + " the note shows " + laid + " of its " + wanted + " lines: " + note);
                check(state.confirmEnabled() == (!fetch.problem() || fetch == BattleSetup.Fetch.FAILED),
                        where + " the vote does not follow the state");
                check(state.slot().problem() == fetch.problem(), where + " the slot does not say this is a problem");
            }
        }
        // The downloader's own reason replaces the general line, and only a failure carries one.
        BattleSetup setup = Battle.SOLO.setup();
        setup.setPick(pick());
        setup.setFetch(BattleSetup.Fetch.FAILED, "music_battle.workshop.error.consent_required");
        check(state(setup, tr, false, true).slot().note().equals(tr.get("music_battle.workshop.error.consent_required")),
                context + " a failed download does not say why it failed");
        setup.setFetch(BattleSetup.Fetch.REFUSED, "music_battle.workshop.error.consent_required");
        check(state(setup, tr, false, true).slot().note().equals(tr.get(SETUP_KEY + "not_allowed")),
                context + " a refusal took a download failure's reason");
    }

    /**
     * The warning about other players' work: whole sentences at every size and in both languages, and at 640x360 the
     * very rectangles it has always had, which is where {@code vote-warning} presses it.
     */
    private static void warning(TextMeasurer measurer, Tr tr, int width, int height, String context) {
        BattleSetupLayout.Warning words = BattleSetup.warning(tr);
        Layout layout = BattleSetupLayout.warning(width, height, measurer, tr, words);
        String where = context + " warning";
        verify(layout, measurer, new Rect(0, 0, width, height), where);
        List<String> texts = new ArrayList<>();
        texts.add(words.heading());
        texts.addAll(words.lines());
        for (int i = 0; i < texts.size(); i++) {
            StringBuilder shown = new StringBuilder();
            for (Layout.Box box : layout.group("warning.line." + i + ".")) {
                shown.append(shown.length() == 0 ? "" : " ").append(box.text());
            }
            check(shown.toString().replaceAll("\\s+", " ").strip().equals(texts.get(i).replaceAll("\\s+", " ").strip()),
                    where + " line " + i + " lost words: '" + shown + "' of '" + texts.get(i) + "'");
        }
        for (String id : new String[] {BattleSetupLayout.WARNING_TOGGLE, BattleSetupLayout.WARNING_ACCEPT,
                BattleSetupLayout.WARNING_CANCEL}) {
            check(!layout.rect(id).empty(), where + " " + id + " is gone");
        }
        if (width == 640 && height == 360 && tr.get("music_battle.l10n.client.051").equals("Back")) {
            check(layout.rect(BattleSetupLayout.WARNING).equals(new Rect(60F, 89F, 520F, 182F)),
                    where + " the panel moved: " + layout.rect(BattleSetupLayout.WARNING));
            check(layout.rect(BattleSetupLayout.WARNING_TOGGLE).equals(new Rect(78F, 198F, 484F, 19F)),
                    where + " the checkbox row moved: " + layout.rect(BattleSetupLayout.WARNING_TOGGLE));
            check(layout.rect(BattleSetupLayout.WARNING_CHECKBOX).equals(new Rect(78F, 201F, 13F, 13F)),
                    where + " the checkbox moved: " + layout.rect(BattleSetupLayout.WARNING_CHECKBOX));
            check(layout.rect(BattleSetupLayout.WARNING_ACCEPT).equals(new Rect(124F, 233F, 190F, 24F))
                            && layout.rect(BattleSetupLayout.WARNING_CANCEL).equals(new Rect(326F, 233F, 190F, 24F)),
                    where + " the buttons moved: " + layout.rect(BattleSetupLayout.WARNING_ACCEPT) + " "
                            + layout.rect(BattleSetupLayout.WARNING_CANCEL));
        }
    }

    /** Every control answers to a press on the rectangle the screen drew it in, and refuses what it may not do. */
    private static void inputs(TextMeasurer measurer, Tr tr, int width, int height, Battle battle, String context) {
        BattleSetup setup = battle.setup();
        Layout layout = BattleSetupLayout.compute(width, height, measurer, tr, state(setup, tr, false, false));
        check(press(setup, layout, BattleSetupLayout.SLOT, context) == BattleSetup.Action.OPEN_PICKER,
                context + " the map slot does not open the picker");
        check(press(setup, layout, BattleSetupLayout.CONFIRM, context) == BattleSetup.Action.NONE,
                context + " the confirm action votes with no map chosen");
        check(press(setup, layout, BattleSetupLayout.CANCEL, context) == BattleSetup.Action.CANCEL,
                context + " the cancel action does not leave the battle");

        // The speed is the one row every battle has, so it is the one every size has to be able to press.
        float before = setup.speedMultiplier();
        layout = reframe(measurer, tr, width, height, setup);
        option(setup, layout, BattleSetup.ROW_SPEED, "x2", context);
        check(setup.speedMultiplier() == 2.0F && before == 1.0F, context + " the speed does not follow its button");
        layout = reframe(measurer, tr, width, height, setup);
        option(setup, layout, BattleSetup.ROW_SPEED, "x15", context);
        check(setup.speedMultiplier() == 1.5F, context + " the speed does not go back down");

        if (battle != Battle.SOLO) {
            layout = reframe(measurer, tr, width, height, setup);
            option(setup, layout, BattleSetup.ROW_NOTES, "shared", context);
            check(setup.unifiedNotes(), context + " the note mode does not follow its button");
            layout = reframe(measurer, tr, width, height, setup);
            option(setup, layout, BattleSetup.ROW_NOTES, "separate", context);
            check(!setup.unifiedNotes(), context + " the note mode does not go back");
            layout = reframe(measurer, tr, width, height, setup);
            option(setup, layout, BattleSetup.ROW_DEADLY, "on", context);
            check(setup.deadlyParty(), context + " the deadly party does not follow its button");
        } else {
            check(setup.press(BattleSetupLayout.optionId(BattleSetup.ROW_NOTES, "shared")) == BattleSetup.Action.NONE
                            && !setup.unifiedNotes(),
                    context + " a solo battle takes a note mode it does not offer");
            check(setup.press(BattleSetupLayout.optionId(BattleSetup.ROW_DEADLY, "on")) == BattleSetup.Action.NONE
                            && !setup.deadlyParty(),
                    context + " a solo battle takes a deadly party it does not offer");
        }
        if (battle == Battle.MOB) {
            check(setup.mobSkillIndex() >= BattleSetup.DEADLY_MIN_SKILL,
                    context + " the deadly party left the mob a skill that cannot keep a player alive");
            layout = reframe(measurer, tr, width, height, setup);
            int raised = setup.mobSkillIndex();
            option(setup, layout, BattleSetup.ROW_SKILL, "easy", context);
            check(setup.mobSkillIndex() == raised, context + " a deadly party accepts an easy mob");
            layout = reframe(measurer, tr, width, height, setup);
            option(setup, layout, BattleSetup.ROW_DEADLY, "off", context);
            layout = reframe(measurer, tr, width, height, setup);
            option(setup, layout, BattleSetup.ROW_SKILL, "easy", context);
            check(BattleSetup.SKILL_IDS.get(setup.mobSkillIndex()).equals("easy"),
                    context + " the mob skill does not follow its button");
        }

        // A chosen map: the difficulty becomes a row of its own, and the confirm action comes alive.
        setup.setPick(pick());
        layout = reframe(measurer, tr, width, height, setup);
        check(has(layout, BattleSetup.ROW_DIFFICULTY), context + " a map with three charts offers no difficulty");
        option(setup, layout, BattleSetup.ROW_DIFFICULTY, "2", context);
        check(setup.pick().chartKey().equals("insane") && setup.pick().difficulty().equals("Insane"),
                context + " the difficulty does not follow its button");
        layout = reframe(measurer, tr, width, height, setup);
        check(press(setup, layout, BattleSetupLayout.CONFIRM, context) == BattleSetup.Action.CONFIRM,
                context + " a chosen map does not let the vote out");
        setup.setFetch(BattleSetup.Fetch.WORKING);
        layout = reframe(measurer, tr, width, height, setup);
        check(press(setup, layout, BattleSetupLayout.CONFIRM, context) == BattleSetup.Action.CONFIRM,
                context + " the confirm action refuses a map that is still coming instead of waiting for it");
        // The screen holds that press; from then on the button is off and the slot says why.
        setup.setWaitingForMap(true);
        layout = reframe(measurer, tr, width, height, setup);
        check(setup.waitingForMap() && press(setup, layout, BattleSetupLayout.CONFIRM, context) == BattleSetup.Action.NONE,
                context + " a held vote can be pressed again");
        check(state(setup, tr, true, true).slot().note().equals(tr.get(SETUP_KEY + "waiting")),
                context + " a held vote does not say so over the download");
        setup.setFetch(BattleSetup.Fetch.FAILED);
        check(!setup.waitingForMap(), context + " a held vote outlives a map that cannot come");
        // A download that failed is tried again by the vote itself rather than by choosing the same map once more.
        layout = reframe(measurer, tr, width, height, setup);
        check(press(setup, layout, BattleSetupLayout.CONFIRM, context) == BattleSetup.Action.CONFIRM,
                context + " the confirm action does not retry a failed download");
        setup.setFetch(BattleSetup.Fetch.FAILED, "music_battle.workshop.error.consent_required");
        layout = reframe(measurer, tr, width, height, setup);
        check(press(setup, layout, BattleSetupLayout.CONFIRM, context) == BattleSetup.Action.NONE,
                context + " the confirm action retries a download the Workshop may not make");
        setup.setFetch(BattleSetup.Fetch.OWN_AUDIO);
        layout = reframe(measurer, tr, width, height, setup);
        check(press(setup, layout, BattleSetupLayout.CONFIRM, context) == BattleSetup.Action.NONE,
                context + " the confirm action votes for a map whose song cannot reach the server");

        // One of the server's own tracks is voted for by its id and has nothing to download.
        setup.setPick(BattleSetup.Pick.track("server_song", "Server Song", "", BattleSetup.Origin.SERVER));
        layout = reframe(measurer, tr, width, height, setup);
        check(!has(layout, BattleSetup.ROW_DIFFICULTY), context + " a server track offers a difficulty row");
        check(press(setup, layout, BattleSetupLayout.CONFIRM, context) == BattleSetup.Action.CONFIRM,
                context + " one of the server's own tracks cannot be voted for");

        BattleSetupLayout.State state = state(setup, tr, true, true);
        check(state.confirmLabel().equals(tr.get(SETUP_KEY + (battle == Battle.DUEL ? "vote" : "start"))),
                context + " the confirm action does not say what it does");
    }

    /**
     * Every settings name and every option is read whole wherever the window has two columns: the names used to sit
     * in a fixed 76 units, which cut "Deadly party" in Russian with half the row free, and five mob skills in one line
     * were cut to their first syllables at 854x480. A battle between two mobs is named as the two mobs, not as the
     * player against both of them.
     */
    private static void words(TextMeasurer measurer, Tr tr, int width, int height, Battle battle, String context) {
        BattleSetup setup = battle.setup();
        stage(setup, Stage.CHOSEN);
        BattleSetupLayout.State state = state(setup, tr, true, true);
        Layout layout = BattleSetupLayout.compute(width, height, measurer, tr, state);
        if (width >= 426) {
            for (BattleSetupLayout.Row row : state.rows()) {
                String label = layout.text(BattleSetupLayout.rowId(row.id()) + ".label");
                check(row.label().equals(label), context + " the " + row.id() + " name is cut: '" + label + "'");
                for (BattleSetupLayout.Option option : row.options()) {
                    String text = layout.text(BattleSetupLayout.optionId(row.id(), option.id()) + ".text");
                    check(option.label().equals(text), context + " the option " + option.label() + " reads '" + text + "'");
                }
            }
        }
        if (battle == Battle.MOB) {
            String title = new BattleSetup(false, true, "Zombie\nSkeleton").state(tr,
                    new BattleSetup.Frame(600, false, null, false)).title();
            check(title.equals(tr.get(SETUP_KEY + "mobs", "Zombie", "Skeleton")),
                    context + " a battle between two mobs is titled '" + title + "'");
            String single = new BattleSetup(false, true, "Zombie").state(tr,
                    new BattleSetup.Frame(600, false, null, false)).title();
            check(single.equals(tr.get(SETUP_KEY + "versus", "Zombie")),
                    context + " a battle against one mob is titled '" + single + "'");
            String named = new BattleSetup(false, true, "Cat vs Dog").state(tr,
                    new BattleSetup.Frame(600, false, null, false)).title();
            check(named.equals(tr.get(SETUP_KEY + "versus", "Cat vs Dog")),
                    context + " a mob named 'Cat vs Dog' is split into two: '" + named + "'");
        }
    }

    /**
     * A chosen map fills its slot: in a window with the room, a server track with no cover still shows the art box
     * (the screen's placeholder) and the facts row with its tempo, length and maker, and the rows sit on cards; the
     * facts never push the note out, which {@link #notes} already holds every state to.
     */
    private static void slotContent(TextMeasurer measurer, Tr tr, int width, int height, String context) {
        for (Battle battle : Battle.values()) {
            BattleSetup setup = battle.setup();
            setup.setPick(BattleSetup.Pick.track("server_song", "Neon Rush (Extended Mix)", "", BattleSetup.Origin.SERVER));
            BattleSetupLayout.State state = setup.state(tr, new BattleSetup.Frame(600, false, null, false,
                    new BattleSetup.Details(128, 95_000L, "Alexander_The_Great")));
            check(state.slot().stats().size() == 3, context + " " + battle + " the slot lost a fact: " + state.slot().stats());
            Layout layout = BattleSetupLayout.compute(width, height, measurer, tr, state);
            verify(layout, measurer, new Rect(0, 0, width, height), context + " " + battle + " facts");
            if (width >= 854 && height >= 480) {
                Rect art = layout.rect(BattleSetupLayout.COVER);
                check(art.w() >= 120F, context + " " + battle + " a map without a cover leaves the slot bare: " + art);
                check(!layout.rect(BattleSetupLayout.STATS).empty() && !layout.rect(BattleSetupLayout.STATS + ".2.value").empty(),
                        context + " " + battle + " the slot does not give the map's tempo, length and maker");
                for (BattleSetupLayout.Row row : state.rows()) {
                    check(!layout.rect(BattleSetupLayout.rowId(row.id()) + BattleSetupLayout.CARD).empty(),
                            context + " " + battle + " the " + row.id() + " row is not on a card in a roomy window");
                }
            }
            BattleSetupLayout.State bare = setup.state(tr, new BattleSetup.Frame(600, false, null, false));
            check(bare.slot().stats().isEmpty(), context + " " + battle + " the slot invents facts it was not given");
        }
    }

    /** Presses the box with this id, through the hit test the screen uses, and returns what the screen must do. */
    private static BattleSetup.Action press(BattleSetup setup, Layout layout, String id, String context) {
        Rect rect = layout.rect(id);
        check(!rect.empty(), context + " nothing was laid out for " + id);
        if (rect.empty()) {
            return BattleSetup.Action.NONE;
        }
        String hit = BattleSetupLayout.hit(layout, rect.centerX(), rect.centerY());
        check(hit.equals(id), context + " a press on " + id + " lands on " + (hit.isEmpty() ? "nothing" : hit));
        return setup.press(hit);
    }

    private static void option(BattleSetup setup, Layout layout, String row, String option, String context) {
        press(setup, layout, BattleSetupLayout.optionId(row, option), context);
    }

    private static Layout reframe(TextMeasurer measurer, Tr tr, int width, int height, BattleSetup setup) {
        return BattleSetupLayout.compute(width, height, measurer, tr,
                state(setup, tr, setup.pick() != null, true));
    }

    /** Half the vote gone; {@code chose} is whether the other player has answered yet, as their card has to say. */
    private static BattleSetupLayout.State state(BattleSetup setup, Tr tr, boolean cover, boolean chose) {
        BattleSetup.Voter voter = setup.solo() ? null
                : new BattleSetup.Voter("TEST_RAP_GOD_MASTER", chose ? "It's Been So Long [Hard]" : "",
                        chose ? "unified | x1.5" : "");
        return setup.state(tr, new BattleSetup.Frame(600, false, voter, cover));
    }

    private static void stage(BattleSetup setup, Stage stage) {
        switch (stage) {
            case EMPTY -> {
            }
            case CHOSEN -> setup.setPick(pick());
            case FETCHING -> {
                setup.setPick(pick());
                setup.setFetch(BattleSetup.Fetch.WORKING);
            }
            case HELD -> {
                setup.setPick(pick());
                setup.setFetch(BattleSetup.Fetch.WORKING);
                setup.setWaitingForMap(true);
            }
            case CHECKING -> {
                setup.setPick(pick());
                setup.setFetch(BattleSetup.Fetch.CHECKING);
            }
            case BROKEN -> {
                setup.setPick(pick());
                setup.setFetch(BattleSetup.Fetch.FAILED);
            }
            case OWN_AUDIO -> {
                setup.setPick(pick());
                setup.setFetch(BattleSetup.Fetch.OWN_AUDIO);
            }
            case SERVER_TRACK -> setup.setPick(BattleSetup.Pick.track("server_song",
                    "A Server Track With A Title Long Enough To Wrap", "", BattleSetup.Origin.SERVER));
            case PLAYERS_TRACK -> setup.setPick(BattleSetup.Pick.track("players_song", "Steve's Banger",
                    "Published by: Some_Player_Name", BattleSetup.Origin.PLAYERS));
        }
    }

    /** A set with three charts, the long names and the long title a real Workshop set can have. */
    private static BattleSetup.Pick pick() {
        return BattleSetup.Pick.workshop(4242L, 7, "a".repeat(64), "It's Been So Long (Extended Nightcore Edit)",
                "The Living Tombstone", "https://blocky-music.qwu.world/covers/4242.png",
                List.of("easy", "hard", "insane"), List.of("Easy", "Hard", "Insane"), 0,
                BattleSetup.Origin.WORKSHOP);
    }

    private static boolean has(Layout layout, String row) {
        return !layout.rect(BattleSetupLayout.rowId(row)).empty();
    }

    // ---------------------------------------------------------------- where a chosen map stands

    /**
     * The rules of {@link BattleSetup#decide}. The reasons that are facts about a copy - a song the player adds, a
     * withdrawn set, a song taken down, a missing signature - stop it with no download, because a fresh download
     * leaves each of them as it is; a copy missing its own song file is downloaded again, and one nobody has checked
     * lately is checked.
     */
    private static void decisions() {
        BattleSetup.Facts download = facts(false, true, false, false, false, false, BattleSetup.Verdict.CHECK_FIRST,
                false, "", true, false);
        decide("a map the battle lists", facts(true, false, false, false, false, false, BattleSetup.Verdict.CHECK_FIRST,
                false, "", false, false), true, BattleSetup.Fetch.READY, BattleSetup.Step.NONE);
        decide("a player's map on a server that refuses them", facts(false, false, true, false, true, true,
                BattleSetup.Verdict.ALLOWED, false, "", true, false), true, BattleSetup.Fetch.REFUSED, BattleSetup.Step.NONE);
        decide("a map nobody has here, when chosen", download, true, BattleSetup.Fetch.WORKING, BattleSetup.Step.DOWNLOAD);
        decide("a map nobody has here, polled", download, false, BattleSetup.Fetch.FAILED, BattleSetup.Step.NONE);
        decide("a download on its way", facts(false, true, false, false, false, false, BattleSetup.Verdict.CHECK_FIRST,
                true, "", true, false), true, BattleSetup.Fetch.WORKING, BattleSetup.Step.NONE);
        decide("a copy that may be handed over", facts(false, true, true, false, true, true,
                BattleSetup.Verdict.ALLOWED, false, "", true, false), true, BattleSetup.Fetch.READY, BattleSetup.Step.NONE);
        for (boolean present : new boolean[] {false, true}) {
            // The song is the player's own file whether or not they have added it yet, and no copy changes that.
            for (boolean start : new boolean[] {false, true}) {
                decide("a set whose song the player adds" + (present ? ", added" : ""), facts(false, true, true, true,
                        present, true, BattleSetup.Verdict.ALLOWED, false, "", true, false), start,
                        BattleSetup.Fetch.OWN_AUDIO, BattleSetup.Step.NONE);
            }
        }
        decide("a withdrawn set", facts(false, true, true, false, true, true, BattleSetup.Verdict.WITHDRAWN, false, "",
                true, false), true, BattleSetup.Fetch.WITHDRAWN, BattleSetup.Step.NONE);
        decide("a set whose song was taken down", facts(false, true, true, false, true, true,
                BattleSetup.Verdict.AUDIO_REMOVED, false, "", true, false), true, BattleSetup.Fetch.AUDIO_REMOVED,
                BattleSetup.Step.NONE);
        decide("an unchecked copy, when chosen", facts(false, true, true, false, true, true,
                BattleSetup.Verdict.CHECK_FIRST, false, "", true, false), true, BattleSetup.Fetch.CHECKING,
                BattleSetup.Step.CHECK);
        decide("an unchecked copy while it is checked", facts(false, true, true, false, true, true,
                BattleSetup.Verdict.CHECK_FIRST, false, "", true, true), false, BattleSetup.Fetch.CHECKING,
                BattleSetup.Step.NONE);
        decide("an unchecked copy after the check", facts(false, true, true, false, true, true,
                BattleSetup.Verdict.CHECK_FIRST, false, "", true, false), false, BattleSetup.Fetch.UNCHECKED,
                BattleSetup.Step.NONE);
        decide("an unchecked copy offline", facts(false, true, true, false, true, true,
                BattleSetup.Verdict.CHECK_FIRST, false, "", false, false), true, BattleSetup.Fetch.UNCHECKED,
                BattleSetup.Step.NONE);
        // A download of the same revision installs as the copy already there and writes no signature, so none starts.
        decide("an unsigned copy", facts(false, true, true, false, true, false, BattleSetup.Verdict.ALLOWED, false, "",
                true, false), true, BattleSetup.Fetch.UNSIGNED, BattleSetup.Step.NONE);
        decide("an unsigned copy offline", facts(false, true, true, false, true, false, BattleSetup.Verdict.ALLOWED,
                false, "", false, false), true, BattleSetup.Fetch.UNSIGNED, BattleSetup.Step.NONE);
        decide("a copy missing its own song, which a download brings back", facts(false, true, true, false, false, true,
                BattleSetup.Verdict.ALLOWED, false, "", true, false), true, BattleSetup.Fetch.WORKING,
                BattleSetup.Step.DOWNLOAD);
        BattleSetup.Decision failed = BattleSetup.decide(facts(false, true, false, false, false, false,
                BattleSetup.Verdict.CHECK_FIRST, false, "disk_full", true, false), false);
        check(failed.fetch() == BattleSetup.Fetch.FAILED
                        && failed.failureKey().equals("music_battle.workshop.error.disk_full"),
                "a failed download does not carry the downloader's reason: " + failed);
        BattleSetup.Decision offline = BattleSetup.decide(facts(false, true, false, false, false, false,
                BattleSetup.Verdict.CHECK_FIRST, false, "", false, false), true);
        check(offline.fetch() == BattleSetup.Fetch.FAILED && offline.step() == BattleSetup.Step.NONE
                        && offline.failureKey().equals("music_battle.workshop.error.consent_required"),
                "a map that needs the network does not say the network is off: " + offline);
    }

    private static BattleSetup.Facts facts(boolean serverHas, boolean playerMaps, boolean installed, boolean ownAudio,
                                           boolean audioPresent, boolean signed, BattleSetup.Verdict verdict,
                                           boolean downloading, String failure, boolean network, boolean checking) {
        return new BattleSetup.Facts(serverHas, playerMaps, installed, ownAudio, audioPresent, signed, verdict,
                downloading, failure, network, checking);
    }

    /**
     * A vote pressed while its map is still coming is held and goes out once, when the map is ready, however many
     * places hear that it is - the screen's tick, the download's end, the Workshop check - and never after the player
     * pressed Cancel or the battle closed. The screen claims the vote through {@link BattleSetup#claimVote} right
     * before it sends anything, so these are the rules its payloads follow.
     */
    private static void heldVotes() {
        BattleSetup setup = Battle.DUEL.setup();
        setup.setPick(pick());
        setup.setFetch(BattleSetup.Fetch.WORKING);
        setup.setWaitingForMap(true);
        check(setup.waitingForMap() && !setup.heldVoteDue(), "a held vote is due before its map arrived");
        setup.setFetch(BattleSetup.Fetch.CHECKING);
        check(setup.waitingForMap() && !setup.heldVoteDue(), "a held vote is due while its map is being checked");
        setup.setFetch(BattleSetup.Fetch.READY);
        check(setup.heldVoteDue(), "a held vote is not released when its map is ready");
        check(setup.claimVote(), "the released vote cannot be claimed");
        check(setup.voted() && !setup.waitingForMap() && !setup.heldVoteDue(), "a sent vote is still held");
        check(!setup.claimVote(), "a held vote released twice - by the tick and by the download - goes out twice");
        setup.setWaitingForMap(true);
        check(!setup.waitingForMap() && !setup.confirmEnabled(), "a vote can be pressed again after it went out");

        BattleSetup cancelled = Battle.DUEL.setup();
        cancelled.setPick(pick());
        cancelled.setFetch(BattleSetup.Fetch.WORKING);
        cancelled.setWaitingForMap(true);
        cancelled.end();
        check(!cancelled.waitingForMap(), "a held vote survives Cancel");
        cancelled.setFetch(BattleSetup.Fetch.READY);
        check(!cancelled.heldVoteDue() && !cancelled.claimVote(), "a held vote goes out after the player pressed Cancel");
        cancelled.setWaitingForMap(true);
        check(!cancelled.waitingForMap() && !cancelled.confirmEnabled(), "a vote can be held after Cancel");

        // The battle closing while the map downloads is the same end from the server's side.
        BattleSetup closed = Battle.SOLO.setup();
        closed.setPick(pick());
        closed.setFetch(BattleSetup.Fetch.WORKING);
        closed.setWaitingForMap(true);
        closed.end();
        closed.setFetch(BattleSetup.Fetch.READY);
        check(!closed.heldVoteDue() && !closed.claimVote() && !closed.voted(),
                "a held vote goes out after the battle closed");

        // A pressed vote whose map then fails is let go; the vote comes back on and its press downloads again.
        BattleSetup failed = Battle.DUEL.setup();
        failed.setPick(pick());
        failed.setFetch(BattleSetup.Fetch.WORKING);
        failed.setWaitingForMap(true);
        failed.setFetch(BattleSetup.Fetch.FAILED, "music_battle.workshop.error.network_error");
        check(!failed.waitingForMap() && !failed.heldVoteDue(), "a held vote outlives a failed download");
        check(failed.retryable() && failed.confirmEnabled(), "a failed download cannot be tried again from the vote");
        failed.setFetch(BattleSetup.Fetch.WORKING);
        failed.setWaitingForMap(true);
        failed.setFetch(BattleSetup.Fetch.READY);
        check(failed.heldVoteDue() && failed.claimVote() && !failed.claimVote(),
                "a vote held over a retried download does not go out exactly once");

        BattleSetup gone = Battle.DUEL.setup();
        gone.setPick(pick());
        gone.setFetch(BattleSetup.Fetch.FAILED, "music_battle.workshop.error.unavailable");
        check(!gone.retryable() && !gone.confirmEnabled(), "a set the Workshop withdrew is downloaded again and again");
        gone.setFetch(BattleSetup.Fetch.FAILED, "music_battle.workshop.error.consent_required");
        check(!gone.retryable(), "a download is retried before the player let the Workshop online");
        gone.setPick(BattleSetup.Pick.track("missing", "Missing", "", BattleSetup.Origin.SERVER));
        gone.setFetch(BattleSetup.Fetch.FAILED);
        check(!gone.retryable() && !gone.confirmEnabled(), "a server track the battle never offered is retried");
        gone.setFetch(BattleSetup.Fetch.OWN_AUDIO);
        check(!gone.retryable(), "a refusal a download cannot change is offered as a retry");
    }

    private static void decide(String what, BattleSetup.Facts facts, boolean start, BattleSetup.Fetch fetch,
                               BattleSetup.Step step) {
        BattleSetup.Decision decision = BattleSetup.decide(facts, start);
        check(decision.fetch() == fetch && decision.step() == step,
                what + (start ? " (start)" : " (poll)") + " is " + decision + ", wanted " + fetch + " " + step);
    }

    // ---------------------------------------------------------------- the battle's track list

    /**
     * What the picker's Server tab lists from a vote payload: the Workshop charts grouped by set, the server's own and
     * its players' tracks one entry each, ids the card grid cannot mistake for a set's, and the vote id of a chart.
     */
    private static void battleTracks() {
        List<BattleTracks.Track> rows = List.of(
                row("ws_42_easy", BattleTracks.Kind.WORKSHOP, 42L, "Neon", "", ""),
                row("server_song", BattleTracks.Kind.SERVER, 0L, "Server Song", "", ""),
                row("ws_42_hard", BattleTracks.Kind.WORKSHOP, 42L, "Neon", "", ""),
                row("published", BattleTracks.Kind.PLAYERS, 0L, "Banger", "Remix of a classic", "Steve"),
                row("ws_7_x", BattleTracks.Kind.WORKSHOP, 0L, "Lost Set", "", ""),
                row("ws_9_normal", BattleTracks.Kind.WORKSHOP, 9L, "Other", "", ""));
        BattleTracks tracks = new BattleTracks(rows, true);
        check(tracks.truncated(), "the battle's list forgot it was cut short");
        check(tracks.hasPlayerTracks(), "a player's track does not raise the warning about players' work");
        check(!new BattleTracks(List.of(rows.get(0), rows.get(1)), false).hasPlayerTracks(),
                "a battle with no player's track asks for the warning about players' work");
        check(tracks.entries().size() == 5, "the battle's list has " + tracks.entries().size() + " entries, wanted 5");
        BattleTracks.Entry neon = tracks.workshopSet(42L);
        check(neon != null && neon.chartKeys().equals(List.of("easy", "hard")),
                "a Workshop set's charts are not one entry: " + neon);
        check(tracks.entries().get(0) == neon && tracks.entries().get(1).trackId().equals("server_song"),
                "the battle's list is not in the order the server sent");
        check("ws_42_hard".equals(tracks.voteId(42L, "hard")), "a listed chart has no vote id");
        check(tracks.voteId(42L, "insane") == null && tracks.voteId(5L, "easy") == null,
                "a chart the battle does not list has a vote id");
        check(tracks.hasTrack("published") && tracks.hasTrack("server_song") && !tracks.hasTrack("ws_42_easy"),
                "the server's own tracks are not found by their ids");
        // A Workshop row that names no set is still a track the vote knows, so it stays as one of the server's own.
        check(tracks.hasTrack("ws_7_x"), "a Workshop row without a set was dropped");
        Set<Long> ids = new HashSet<>();
        for (BattleTracks.Entry entry : tracks.entries()) {
            check(entry.cardId() < 0, "an entry's card id could be a Workshop set's: " + entry.cardId());
            check(ids.add(entry.cardId()), "two entries share a card id: " + entry.cardId());
            check(tracks.entry(entry.cardId()) == entry, "an entry cannot be found by its card id");
        }
        check(tracks.entry(42L) == null, "a Workshop set id is taken for one of the battle's own entries");
        BattleTracks.Entry published = tracks.entry(tracks.entries().get(2).cardId());
        check(published.kind() == BattleTracks.Kind.PLAYERS, "the entries lost their kinds");
        check(BattleTracks.matches(published, "steve") && BattleTracks.matches(published, "CLASSIC")
                        && BattleTracks.matches(published, "") && !BattleTracks.matches(published, "neon"),
                "the battle's own entries do not answer a search by title, description or publisher");
        check(BattleTracks.chartKeyOf("ws_42_hard", 42L).equals("hard") && BattleTracks.chartKeyOf("ws_42_", 42L).isEmpty()
                        && BattleTracks.chartKeyOf("ws_42_hard", 4L).isEmpty() && BattleTracks.chartKeyOf("x", 0L).isEmpty(),
                "a Workshop chart's key is not read from its id");
        check(BattleTracks.NONE.isEmpty() && !BattleTracks.NONE.truncated(), "the empty list is not empty");
    }

    private static BattleTracks.Track row(String id, BattleTracks.Kind kind, long setId, String title, String description,
                                          String publishedBy) {
        String key = kind == BattleTracks.Kind.WORKSHOP ? BattleTracks.chartKeyOf(id, setId) : "";
        return new BattleTracks.Track(id, kind, setId, key, title, description, publishedBy, "mapper", "", 140, 2400,
                id + "_preview.png");
    }

    // ---------------------------------------------------------------- the picker's track cards

    /**
     * The Server tab as the picker: the Installed tab's Import and Open inbox buttons and its "Installed" sort are gone,
     * the sort bar and the grid move up into their room, and nothing left overlaps. The hint that a click chooses is
     * there wherever the sort bar has the width for it.
     */
    private static void pickerChrome(TextMeasurer measurer, Tr tr, int width, int height, String context) {
        Layout shell = ShellLayout.compute(width, height, measurer, tr, new ShellLayout.State(ShellLayout.Tab.SERVER, true,
                null, false, tr.get(WorkshopStrings.KEY + "title.pick") + "  \u00b7  0:42", ""));
        Rect content = shell.rect(ShellLayout.CONTENT);
        ListingLayout.State state = new ListingLayout.State(true, "", "any", new java.util.LinkedHashSet<>(), 0, 0, "any",
                new java.util.LinkedHashSet<>(), false, null, true, false, false, true, "title", null, "", "", 9, false,
                false);
        Layout full = ListingLayout.compute(content, measurer, tr, state);
        Layout picker = BattleTracks.pickerChrome(full);
        String where = context + " picker chrome";
        verify(picker, measurer, new Rect(0, 0, content.w(), Math.max(content.h(), picker.contentHeight())), where);
        check(picker.rect("listing.tool.import").empty() && picker.rect("listing.tool.inbox").empty(),
                where + " the picker still offers to import or open the inbox");
        check(picker.rect("listing.sort.installed").empty() && !picker.rect("listing.sort.title").empty(),
                where + " the picker still sorts by install date, or lost its other sorts");
        check(picker.rect(ListingLayout.SORT_BAR).y() < full.rect(ListingLayout.SORT_BAR).y() - 10F,
                where + " the sort bar did not move up into the tools' room");
        check(picker.contentHeight() < full.contentHeight(), where + " the list did not gain the tools' height");
        if (content.w() >= 400F) {
            check(!picker.rect("listing.picker.hint").empty(), where + " the picker does not say a click chooses");
        }
    }

    /**
     * The cards of the server's own tracks, at the width the picker's grid gives them: they keep the Workshop card's
     * footprint, so the two kinds share one grid, and every line fits the card it is in.
     */
    private static void trackCards(TextMeasurer measurer, Tr tr, int width, int height, String context) {
        Layout shell = ShellLayout.compute(width, height, measurer, tr, new ShellLayout.State(ShellLayout.Tab.SERVER, true,
                null, false, tr.get(WorkshopStrings.KEY + "title.pick") + "  ·  0:42",
                tr.get("screen.music_battle.vote.truncated")));
        Rect content = shell.rect(ShellLayout.CONTENT);
        int columns = ListingLayout.columns(content.w());
        float cardWidth = (content.w() - Metrics.CARD_GAP * (columns - 1)) / columns;
        String[] badges = {"screen.music_battle.vote.chip.server", "screen.music_battle.vote.chip.players",
                "screen.music_battle.vote.chip.workshop"};
        for (boolean extra : new boolean[] {false, true}) {
            for (boolean hover : new boolean[] {false, true}) {
                for (String badgeKey : badges) {
                    for (boolean full : new boolean[] {false, true}) {
                        String where = context + " track card extra=" + extra + " hover=" + hover + " " + badgeKey
                                + (full ? " full" : " bare");
                        Rect rect = new Rect(0, 0, cardWidth, extra ? Metrics.CARD_EXTRA_H : Metrics.CARD_NORMAL_H);
                        TrackCardLayout.State state = new TrackCardLayout.State(extra, hover,
                                full ? "A server track whose title goes on far longer than any card is wide" : "Song",
                                full ? tr.get("music_battle.l10n.client.093") + "A_Very_Long_Player_Name" : "",
                                tr.get(WorkshopStrings.KEY + "set.bpm") + " 174  ·  4:22",
                                full ? "Imported from an FNF chart, with a description of several sentences that goes on"
                                        + " for longer than a card has room for." : "",
                                WorkshopStrings.upper(tr.get(badgeKey)));
                        Layout layout = TrackCardLayout.compute(rect, measurer, tr, state);
                        verify(layout, measurer, new Rect(-1, -1, cardWidth + 2, rect.h() + 2), where);
                        check(!layout.rect("track.title").empty() && layout.rect("track.title").w() > 8,
                                where + " the title has no room");
                        check(!layout.rect("track.badge").empty(), where + " the card does not say what it is");
                        check(!layout.rect("track.detail").empty(), where + " the tempo and length are gone");
                        if (full) {
                            check(!layout.rect("track.byline").empty(), where + " the publisher is gone");
                            check(!layout.rect("track.desc.0").empty(), where + " the description is gone");
                        }
                        CardLayout.State workshop = new CardLayout.State(extra, hover, false, false, "Song", "Artist",
                                "mapped by x", "", "COMMUNITY", List.of(), List.of(1.0), "", null, false, false, 0, false);
                        Layout card = CardLayout.compute(rect, measurer, tr, workshop);
                        check(layout.rect("track.thumb").equals(card.rect("card.thumb"))
                                        && layout.rect("track.body").equals(card.rect("card.body")),
                                where + " a track card does not keep the Workshop card's footprint");
                    }
                }
            }
        }
        // The notice the picker's Server tab carries when the battle's list was cut short.
        verify(shell, measurer, new Rect(0, 0, width, height), context + " picker shell");
        check(!shell.rect(ShellLayout.NOTICE).empty() && !shell.rect(ShellLayout.NOTICE + ".text.0").empty(),
                context + " the picker does not say the battle's list was cut short");
        check(content.y() >= shell.rect(ShellLayout.NOTICE).bottom() - SLACK,
                context + " the list starts under the notice");
    }

    // ---------------------------------------------------------------- the screen's own source

    /**
     * What the screen may no longer do and must still do. The flows behind these lines need a server and a window,
     * so the check watches the wiring rather than claiming to have voted.
     */
    private static void screenSource() throws Exception {
        String source = Files.readString(root.resolve(SCREEN), StandardCharsets.UTF_8);
        check(!source.contains("drawCategoryMenu") && !source.contains("drawTrackCards"),
                "the category menu and the track grid are still in the vote screen");
        check(source.contains("BattleSetupLayout.hit(") && source.contains("setup.press("),
                "the vote screen does not route its input through the pure model");
        check(source.contains("BattleSetup.decide("), "the vote screen decides where a map stands by itself");
        check(source.contains("WorkshopScreen.openPicker("), "the map slot does not open the Workshop as the picker");
        check(source.contains("new VoteTrackPayload("), "the ordinary vote for a track the server has is gone");
        check(source.contains("new VoteInstalledPayload(") && source.contains("rememberVoteUpload("),
                "the installed-map vote and its upload are gone");
        check(source.contains("new SurrenderPayload("), "leaving the setup screen no longer leaves the battle");
        // The held-vote rules above hold for the screen only if it sends through the gate and ends it on every exit.
        check(count(source, "setup.claimVote()") == 2 && count(source, "sendToServer(new Vote") == 2,
                "a vote payload is sent without claiming the vote first");
        check(source.indexOf("setup.claimVote()") < source.indexOf("sendToServer(new VoteTrackPayload(")
                        && source.lastIndexOf("setup.claimVote()") < source.indexOf("sendToServer(new VoteInstalledPayload("),
                "the vote is claimed after it was sent");
        check(count(source, "setup.end();") >= 2, "Cancel or the battle closing no longer ends the held vote");
        check(source.contains(".addListener(") && source.contains(".removeListener("),
                "a held vote waits for its download on the screen's tick alone, which stops under the picker");
        check(source.contains("ClientBattleSettings.hideUserTrackWarning()"),
                "the warning about other players' work no longer reads its saved answer");
        check(source.contains("tracks.hasPlayerTracks()"),
                "the warning about other players' work no longer waits for a player's track");
        noCyrillicInLiterals(source, "MusicBattleVoteScreen.java");
        for (String name : new String[] {"BattleSetup.java", "BattleSetupLayout.java", "BattleTracks.java",
                "TrackCardLayout.java", "ShellLayout.java"}) {
            noCyrillicInLiterals(Files.readString(
                    root.resolve("src/main/java/ru/gora/musicbattle/client/workshop/layout/" + name),
                    StandardCharsets.UTF_8), name);
        }
    }

    private static int count(String source, String needle) {
        int found = 0;
        for (int at = source.indexOf(needle); at >= 0; at = source.indexOf(needle, at + needle.length())) {
            found++;
        }
        return found;
    }

    /** Player-visible text is a key in both language files, never a literal. */
    private static void noCyrillicInLiterals(String source, String name) {
        String stripped = Pattern.compile("(?s)//[^\n]*|/\\*.*?\\*/").matcher(source).replaceAll(" ");
        Matcher literal = Pattern.compile("\"(?:\\\\.|[^\"\\\\])*\"").matcher(stripped);
        while (literal.find()) {
            check(!literal.group().matches("(?s).*[\\u0410-\\u044F\\u0401\\u0451].*"),
                    "Cyrillic in a Java literal: " + name + " " + literal.group());
        }
    }

    // ---------------------------------------------------------------- measuring

    private static void verify(Layout layout, TextMeasurer measurer, Rect view, String context) {
        List<Layout.Box> boxes = layout.boxes();
        Map<String, Rect> byId = new HashMap<>();
        for (Layout.Box box : boxes) {
            byId.put(box.id(), box.rect());
        }
        for (Layout.Box box : boxes) {
            Rect rect = box.rect();
            check(rect.w() >= 0 && rect.h() >= 0, context + " negative size: " + box.id());
            check(rect.inside(view, SLACK), context + " box leaves the screen: " + box.id() + " " + rect);
            if (!box.parent().isEmpty()) {
                Rect parent = byId.get(box.parent());
                check(parent != null, context + " unknown parent " + box.parent() + " of " + box.id());
                if (parent != null) {
                    check(rect.inside(parent, SLACK + 1F), context + " box outside its parent: " + box.id() + " "
                            + rect + " not in " + box.parent() + " " + parent);
                }
            }
            if (box.kind() == Layout.Kind.TEXT && box.text() != null && !box.text().isEmpty()) {
                float measured = TextFit.width(measurer, box.text(), box.style());
                check(measured <= rect.w() + SLACK, context + " text wider than its box: " + box.id() + " '"
                        + box.text() + "' " + measured + " > " + rect.w());
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

    private static void check(boolean value, String message) {
        checks++;
        if (!value) {
            failures.add(message);
        }
    }

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
            String key = style.name() + "\u0000" + text;
            Float cached = cache.get(key);
            if (cached != null) {
                return cached;
            }
            float width = (float) faces.get(style.name()).getStringBounds(text, context).getWidth();
            cache.put(key, width);
            return width;
        }
    }

    /**
     * Minecraft's language file as the screen sees it, with {@code %s} arguments substituted; a key this change adds
     * reads its proposed words until the file carries it.
     */
    private static final class LangTr implements Tr {
        private final Map<String, String> strings = new HashMap<>();

        LangTr(Path file, int language) throws Exception {
            JsonObject object = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
                strings.put(entry.getKey(), entry.getValue().getAsString());
            }
            for (Map.Entry<String, String[]> entry : PROPOSED.entrySet()) {
                strings.putIfAbsent(entry.getKey(), entry.getValue()[language]);
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
            } catch (RuntimeException notAFormat) {
                return value;
            }
        }
    }
}

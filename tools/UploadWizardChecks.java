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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import ru.gora.musicbattle.client.ui.TextMeasurer;
import ru.gora.musicbattle.client.ui.TextStyle;
import ru.gora.musicbattle.client.workshop.layout.Layout;
import ru.gora.musicbattle.client.workshop.layout.Metrics;
import ru.gora.musicbattle.client.workshop.layout.Rect;
import ru.gora.musicbattle.client.workshop.layout.ShellLayout;
import ru.gora.musicbattle.client.workshop.layout.TextFit;
import ru.gora.musicbattle.client.workshop.layout.Tr;
import ru.gora.musicbattle.client.workshop.layout.UploadLayout;
import ru.gora.musicbattle.client.workshop.layout.WorkshopStrings;

/**
 * The `upload-wizard` regression: the production layout of the in-game upload wizard ({@link UploadLayout}), every
 * step and the states the site has (empty, filled, errors next to their fields, uploading, failed, done), inside the
 * Workshop's real content area from a 320x240 GUI up to 1280x720, which covers 854x480 at GUI scale 2 and 1920x1080
 * at scale 3, in English and Russian. Nothing may overlap a sibling, leave its parent or the view, or be wider than
 * its box; the pinned bar's buttons must not overlap and, from 854x480 at scale 2 up, never cut their labels.
 *
 * <p>Off-GPU like `workshop-screens`: text is measured with the bundled Nunito TTFs through java.awt.
 */
public final class UploadWizardChecks {
    private static final int[][] SIZES = {{320, 240}, {427, 240}, {480, 270}, {640, 360}, {960, 540}, {1280, 720}};
    private static final String[] LANGUAGES = {"en_us", "ru_ru"};
    private static final float SLACK = 0.75F;

    private static int checks;
    private static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args.length > 0 ? args[0] : ".");
        AwtMeasurer measurer = new AwtMeasurer(root.resolve("src/main/resources/assets/music_battle/font"));
        int pages = 0;
        for (String language : LANGUAGES) {
            LangTr tr = new LangTr(root.resolve("src/main/resources/assets/music_battle/lang/" + language + ".json"));
            for (int[] size : SIZES) {
                Layout shell = ShellLayout.compute(size[0], size[1], measurer, tr,
                        new ShellLayout.State(ShellLayout.Tab.BROWSE, false, "dragon4ik", false, null));
                Rect area = shell.rect(ShellLayout.CONTENT);
                boolean roomy = size[0] >= 427;
                for (Variant variant : variants(tr)) {
                    UploadLayout.Page page = UploadLayout.compute(area.w(), area.h(), measurer, tr, variant.state());
                    verifyPage(page, measurer, area, language + " " + size[0] + "x" + size[1] + " " + variant.name(), roomy, tr);
                    pages++;
                }
            }
            keys(tr, language);
        }
        if (!failures.isEmpty()) {
            for (String failure : failures.subList(0, Math.min(Integer.getInteger("fails", 30), failures.size()))) {
                System.out.println("FAIL " + failure);
            }
            throw new AssertionError(failures.size() + " / " + checks + " upload wizard layout failures");
        }
        System.out.println("Upload wizard checks passed: " + checks + " over " + pages
                + " pages (every step and state, 6 sizes, 2 languages)");
        System.out.println("NOT COVERED: real Minecraft font rasterisation, GPU output and input handling");
    }

    private record Variant(String name, UploadLayout.State state) {
    }

    private static List<Variant> variants(Tr tr) {
        String k = WorkshopStrings.KEY + "wizard.";
        List<Variant> out = new ArrayList<>();
        List<UploadLayout.Chart> two = List.of(
                new UploadLayout.Chart("Organic's Decay", tr.get(k + "track.detail", "organic_decay", "4:22", "1079"), true),
                new UploadLayout.Chart("Organic's Decay (Easy)", tr.get(k + "track.detail", "organic_easy", "4:22", "320"), false));
        List<UploadLayout.Chart> many = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
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
        Map<String, String> noErrors = Map.of();
        Map<String, String> errors = Map.of("title", tr.get(k + "problem.title"), "artist", tr.get(k + "problem.artist"));
        List<UploadLayout.Stage> running = List.of(
                new UploadLayout.Stage(tr.get(k + "stage.preparing"), UploadLayout.StageState.DONE, List.of(), -1),
                new UploadLayout.Stage(tr.get(k + "stage.uploading"), UploadLayout.StageState.RUNNING,
                        List.of("organic_decay.json  12.0 KiB / 30.0 KiB", "organic_decay_final_master_v3.ogg  1.2 MiB / 7.9 MiB"), 0.4F),
                new UploadLayout.Stage(tr.get(k + "stage.processing"), UploadLayout.StageState.PENDING, List.of(), -1),
                new UploadLayout.Stage(tr.get(k + "stage.finishing"), UploadLayout.StageState.PENDING, List.of(), -1));
        List<UploadLayout.Stage> failed = List.of(
                new UploadLayout.Stage(tr.get(k + "stage.preparing"), UploadLayout.StageState.DONE, List.of(), -1),
                new UploadLayout.Stage(tr.get(k + "stage.uploading"), UploadLayout.StageState.FAILED, List.of(), -1),
                new UploadLayout.Stage(tr.get(k + "stage.processing"), UploadLayout.StageState.PENDING, List.of(), -1),
                new UploadLayout.Stage(tr.get(k + "stage.finishing"), UploadLayout.StageState.PENDING, List.of(), -1));
        String failure = "Audio must be at most 15 minutes long. A long server message wraps onto a second line here.";
        for (boolean update : new boolean[] {false, true}) {
            String u = update ? " update" : "";
            out.add(new Variant("rules" + u, state(UploadLayout.Step.RULES, update, two, "upload", "preview", true, text, heights,
                    ticked, "community", null, "", List.of(), null, null, false, false, none, noErrors)));
            out.add(new Variant("rules changed busy" + u, state(UploadLayout.Step.RULES, update, two, "upload", "preview", true,
                    text, heights, ticked, "community", "Version 2 spells out what happens after a takedown.", "", List.of(), null,
                    null, false, true, issues, noErrors)));
            out.add(new Variant("files" + u, state(UploadLayout.Step.FILES, update, two, "upload", "preview", true, text, heights,
                    ticked, "community", null, "", List.of(), null, null, false, false, none, noErrors)));
            out.add(new Variant("files empty" + u, state(UploadLayout.Step.FILES, update, List.of(), "upload", "none", false, blank,
                    heights, ticked, "community", null, "", List.of(), null, null, false, false, issues, noErrors)));
            out.add(new Variant("files many external" + u, state(UploadLayout.Step.FILES, update, many, "external", "file", false,
                    text, heights, ticked, "community", null, "", List.of(), null, null, false, false, none, noErrors)));
            out.add(new Variant("files no audio" + u, state(UploadLayout.Step.FILES, update, two, "upload", "preview", false, blank,
                    heights, ticked, "community", null, "", List.of(), null, null, false, false, none, noErrors)));
            out.add(new Variant("details" + u, state(UploadLayout.Step.DETAILS, update, two, "upload", "preview", true, text,
                    Map.of(), ticked, "community", null, "", List.of(), null, null, false, false, none, noErrors)));
            out.add(new Variant("details errors" + u, state(UploadLayout.Step.DETAILS, update, two, "upload", "preview", true, blank,
                    heights, ticked, "community", null, "", List.of(), null, null, false, false, issues, errors)));
            out.add(new Variant("publish" + u, state(UploadLayout.Step.PUBLISH, update, two, "upload", "preview", true, text, heights,
                    ticked, "community", null, "2 days ago", List.of(), null, null, false, false, none, noErrors)));
            out.add(new Variant("publish wip blank" + u, state(UploadLayout.Step.PUBLISH, update, many, "external", "none", false,
                    blank, heights, Set.of(), "wip", null, "", List.of(), null, null, false, false, issues, noErrors)));
            out.add(new Variant("progress" + u, state(UploadLayout.Step.PROGRESS, update, two, "upload", "preview", true, text,
                    heights, ticked, "community", null, "", running, null, null, true, true, none, noErrors)));
            out.add(new Variant("progress failed" + u, state(UploadLayout.Step.PROGRESS, update, two, "upload", "preview", true,
                    text, heights, ticked, "community", null, "", failed, failure, null, false, false, none, noErrors)));
            for (String status : new String[] {"community", "wip", "pending"}) {
                out.add(new Variant("result " + status + u, state(UploadLayout.Step.PROGRESS, update, two, "upload", "preview",
                        true, text, heights, ticked, status, null, "", failed, null, status, false, false, none, noErrors)));
            }
        }
        return out;
    }

    private static UploadLayout.State state(UploadLayout.Step step, boolean update, List<UploadLayout.Chart> charts, String audio,
                                            String cover, boolean coverImage, Map<String, String> text, Map<String, Float> heights,
                                            Set<String> ticked, String target, String rulesChanged, String acceptedOn,
                                            List<UploadLayout.Stage> stages, String failure, String result, boolean running,
                                            boolean busy, List<UploadLayout.Issue> issues, Map<String, String> errors) {
        return new UploadLayout.State(step, true, update, "#4242", charts, "music_battle_tracks/my_tracks", audio, cover, coverImage,
                text, heights, ticked, target, 95_000L, 262_000L, false, rulesChanged, "2026-09-01", "2", acceptedOn, stages,
                failure, result, running, busy, issues, errors, ticked.contains("accept.terms"), true);
    }

    // ---------------------------------------------------------------- the rules

    private static void verifyPage(UploadLayout.Page page, TextMeasurer measurer, Rect area, String context, boolean roomy, Tr tr) {
        Rect viewport = page.viewport();
        check(viewport.h() >= 60F, context + " the page has no room to scroll in: " + viewport);
        Layout content = page.content();
        verify(content, measurer, new Rect(0, 0, viewport.w() - Metrics.SCROLLBAR + SLACK, Math.max(viewport.h(), content.contentHeight())),
                context + " content", false);
        verify(page.bar(), measurer, new Rect(0, 0, area.w(), area.h()), context + " bar", true);
        Rect barRect = page.bar().rect("bar");
        check(Math.abs(barRect.y() - viewport.bottom()) < 0.01F, context + " the bar does not start where the page ends");
        for (Layout.Box box : content.boxes()) {
            check(box.rect().bottom() <= content.contentHeight() + SLACK, context + " below the scroll extent: " + box.id());
            if (box.kind() == Layout.Kind.FIELD) {
                check(box.rect().w() >= 30F && box.rect().h() >= 14F, context + " a field too small to type in: " + box.id() + " " + box.rect());
            }
        }
        Set<String> seen = new HashSet<>();
        for (String id : page.clicks()) {
            check(seen.add(id), context + " a click target twice: " + id);
            Rect rect = id.startsWith("bar.") ? page.bar().rect(id) : content.rect(id);
            check(!rect.empty(), context + " a click target with no box: " + id);
            check(rect.h() >= 6F, context + " a click target too small to hit: " + id + " " + rect);
        }
        int primaries = 0;
        for (Layout.Box box : page.bar().boxes()) {
            if (box.kind() == Layout.Kind.BUTTON) {
                check(box.rect().w() >= 24F, context + " a squeezed button: " + box.id() + " " + box.rect());
                primaries += "primary".equals(UploadLayout.buttonStyle(box.id())) ? 1 : 0;
            }
            if (roomy && box.kind() == Layout.Kind.TEXT) {
                check(!cut(tr, box.text()), context + " a cut button label: " + box.id() + " '" + box.text() + "'");
            }
        }
        check(primaries <= 1, context + " more than one primary action");
        if (roomy) {
            for (Layout.Box box : content.boxes()) {
                if (box.id().startsWith("step.title") || box.id().startsWith("head.")) {
                    check(!cut(tr, box.text()), context + " a cut heading: " + box.id() + " '" + box.text() + "'");
                }
            }
            for (Layout.Box box : content.group("rail.")) {
                if (box.kind() == Layout.Kind.TEXT) {
                    check(!cut(tr, box.text()), context + " a cut step name: " + box.id());
                }
            }
            for (Layout.Box box : content.boxes()) {
                if (box.kind() == Layout.Kind.TEXT && (box.id().contains(".label") || box.id().startsWith("select."))
                        && !box.id().startsWith("chart.")) {
                    check(!cut(tr, box.text()), context + " a cut label: " + box.id() + " '" + box.text() + "'");
                }
            }
        }
        // The rail is one row and every step of it is on screen.
        float railY = -1;
        for (Layout.Box box : content.boxes()) {
            if (box.id().matches("rail\\.[a-z]+") ) {
                check(railY < 0 || Math.abs(box.rect().y() - railY) < 0.01F, context + " the rail wrapped");
                railY = box.rect().y();
            }
        }
        check(railY >= 0, context + " no step rail");
    }

    /** Text the layout shortened: it ends in an ellipsis that is not part of the translation itself ("Publishing…"). */
    private static boolean cut(Tr tr, String text) {
        return text != null && text.endsWith(TextFit.ELLIPSIS) && !((LangTr) tr).values().contains(text);
    }

    /**
     * Every box lies inside its parent and inside the view, no two siblings overlap, and every string fits the box it
     * was measured for (the rules of `workshop-screens`).
     */
    private static void verify(Layout layout, TextMeasurer measurer, Rect view, String context, boolean strictHeight) {
        List<Layout.Box> boxes = layout.boxes();
        Map<String, Rect> byId = new HashMap<>();
        for (Layout.Box box : boxes) {
            check(!byId.containsKey(box.id()), context + " two boxes named " + box.id());
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
            }
        }
        for (int i = 0; i < boxes.size(); i++) {
            Layout.Box a = boxes.get(i);
            if (a.floating() || a.kind() == Layout.Kind.PANEL) {
                continue;
            }
            for (int j = i + 1; j < boxes.size(); j++) {
                Layout.Box b = boxes.get(j);
                if (b.floating() || b.kind() == Layout.Kind.PANEL || !a.parent().equals(b.parent())) {
                    continue;
                }
                check(!a.rect().overlaps(b.rect(), SLACK),
                        context + " overlap: " + a.id() + " " + a.rect() + " and " + b.id() + " " + b.rect());
            }
        }
        // Panels that are siblings (cards, rows, sections) must not overlap each other either.
        for (int i = 0; i < boxes.size(); i++) {
            Layout.Box a = boxes.get(i);
            if (a.kind() != Layout.Kind.PANEL || a.parent().isEmpty() && a.id().equals("panel")) {
                continue;
            }
            for (int j = i + 1; j < boxes.size(); j++) {
                Layout.Box b = boxes.get(j);
                if (b.kind() != Layout.Kind.PANEL || !a.parent().equals(b.parent()) || b.id().startsWith(a.id() + ".")
                        || a.id().startsWith(b.id() + ".")) {
                    continue;
                }
                check(!a.rect().overlaps(b.rect(), SLACK),
                        context + " panels overlap: " + a.id() + " " + a.rect() + " and " + b.id() + " " + b.rect());
            }
        }
    }

    /** Every key the wizard reads exists in this language. */
    private static void keys(LangTr tr, String language) {
        String k = WorkshopStrings.KEY + "wizard.";
        List<String> needed = new ArrayList<>(List.of("page.eyebrow", "page.title", "page.update_title", "page.lead",
                "page.update_lead", "step.intro", "step.files", "step.details", "step.publish", "accept.lead", "intro.changed",
                "intro.confirm", "accept.terms", "accept.age", "accept.read_rules", "accept.view", "accept.busy", "understood",
                "files.charts", "files.where", "files.next", "tracks.empty", "difficulty", "chart_key", "audio", "audio.upload",
                "audio.upload.hint", "audio.external", "audio.external.hint", "audio.from_charts", "audio.external.text",
                "problem.no_audio", "cover", "cover.upload", "cover.upload.hint", "cover.none", "cover.none.hint", "cover.choose",
                "cover.missing", "title", "artist", "source", "source.hint", "genre", "language", "tags", "tags.hint", "tags.help",
                "description", "description.hint", "preview.label", "preview.hint", "preview.play", "preview.stop", "flags.label",
                "credits", "credits.hint", "changelog", "changelog.hint", "details.next", "publish.target", "target.community",
                "target.community.hint", "target.wip", "target.wip.hint", "accepted_on", "confirm.rights", "publish",
                "publish.update", "untitled", "progress.title", "progress.failed", "progress.back_to_edit", "progress.another",
                "result.community", "result.wip", "result.pending", "open_set", "install", "back"));
        for (String card : UploadLayout.CARDS) {
            for (String part : List.of("title", "text", "link")) {
                needed.add("accept.card." + card + "." + part);
            }
        }
        for (String flag : UploadLayout.FLAGS) {
            needed.add("flag." + flag);
            needed.add("flag." + flag + ".hint");
        }
        for (String key : needed) {
            check(tr.has(k + key), language + " missing " + k + key);
        }
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

        java.util.Collection<String> values() {
            return strings.values();
        }

        boolean has(String key) {
            return strings.containsKey(key);
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

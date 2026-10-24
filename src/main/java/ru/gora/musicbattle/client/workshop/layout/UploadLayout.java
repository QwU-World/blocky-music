package ru.gora.musicbattle.client.workshop.layout;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import ru.gora.musicbattle.client.ui.TextMeasurer;
import ru.gora.musicbattle.client.ui.TextStyle;
import ru.gora.musicbattle.client.workshop.layout.Layout.Kind;

/**
 * The in-game upload wizard laid out like the website's {@code /upload} page ({@code src/web/uploads/pages.ts} and
 * {@code public/css/modules/uploads.css} in the Workshop repository): the eyebrow, title and lead, the numbered step
 * rail, one panel holding the current step with its sections, choice cards, check cards and fields, the issues list,
 * and the stage rows with the result. Sizes are the site's CSS pixels times {@code Palette.PX}.
 *
 * <p>Two things differ on purpose. The Back and Next buttons sit in a bar pinned under the page instead of at the end
 * of the panel, because the Workshop's content area is often under 200 units tall and the player should never have to
 * scroll to find the way on. And the Files step lists the player's own tracks instead of a drop zone, because the game
 * already knows where they are.
 *
 * <p>Pure: no Minecraft. {@link #compute} returns the scrolling content (relative to the top of the viewport at scroll
 * 0), the pinned bar (relative to the view) and the ids the player can click; the {@code upload-wizard} check proves
 * that nothing overlaps or overflows at every size and in both languages.
 */
public final class UploadLayout {
    public enum Step {
        RULES, FILES, DETAILS, PUBLISH, PROGRESS
    }

    public enum StageState {
        PENDING, RUNNING, DONE, FAILED, CANCELLED
    }

    /** One of the player's tracks offered as a chart; {@code meta} is its id, length and note count. */
    public record Chart(String title, String meta, boolean selected) {
    }

    /** A row of the publishing pipeline; {@code fraction} is below 0 when the stage has no byte progress. */
    public record Stage(String label, StageState state, List<String> detail, float fraction) {
    }

    /** A message of the issues list: an error the player must fix, or a warning that only informs. */
    public record Issue(String text, boolean warning) {
    }

    /**
     * Everything the page shows. {@code text} holds the display strings that are data rather than translations
     * (title, artist, genre and language labels, the audio file and cover names); {@code fieldHeights} the height
     * each multi-line field wants for its text; {@code ticked} every ticked box by id ({@code flag.<key>},
     * {@code accept.<key>}, {@code confirm.rights}); {@code fieldErrors} a message under the field it concerns.
     */
    public record State(Step step, boolean needsRules, boolean update, String setLabel, List<Chart> charts, String tracksFolder,
                        String audioMode, String coverMode, boolean coverImage, Map<String, String> text,
                        Map<String, Float> fieldHeights, Set<String> ticked, String target, long previewStartMs, long durationMs,
                        boolean previewPlaying, String rulesChanged, String termsVersion, String rulesVersion, String acceptedOn,
                        List<Stage> stages, String failure, String resultStatus, boolean running, boolean busy,
                        List<Issue> issues, Map<String, String> fieldErrors, boolean canAccept, boolean canPublish) {
        String text(String key) {
            String value = text.get(key);
            return value == null ? "" : value;
        }

        boolean ticked(String id) {
            return ticked.contains(id);
        }
    }

    /** The laid-out page: {@code content} scrolls inside {@code viewport}, {@code bar} stays put under it. */
    public record Page(Layout content, Layout bar, Rect viewport, List<String> clicks) {
    }

    /** The six summary cards of the acceptance step, in the site's order: key, then the page each links to. */
    public static final List<String> CARDS = List.of("rights", "marks", "ai", "takedown", "reports", "docs");
    /** The required confirmations of the acceptance, in the order the site asks them; the server records which were ticked. */
    public static final List<String> ACCEPTANCE = List.of("terms", "age");
    public static final List<String> FLAGS = List.of("explicit", "flashing", "aiAudio", "aiCover", "aiChart", "spoiler");
    /** The single-line and multi-line text fields of the Details step, by id. */
    public static final List<String> MULTILINE = List.of("description", "credits", "changelog");
    /** The preview window the cards play, as on the site. */
    public static final long PREVIEW_MS = 30_000L;

    private static final String K = WorkshopStrings.KEY + "wizard.";
    private static final float PX = 0.7F;
    private static final float S1 = 4 * PX;
    private static final float S2 = 8 * PX;
    private static final float S3 = 12 * PX;
    private static final float S4 = 16 * PX;
    private static final float S5 = 20 * PX;
    private static final float S6 = 24 * PX;
    /** The site's {@code .upw} column, 880 px. */
    private static final float MAX_COLUMN = 880 * PX;
    /** Below this panel width the site's 640 px media query applies: tighter padding, one-column grids. */
    private static final float NARROW = 448F;
    private static final float CHECK = 10F;
    private static final float FIELD_H = 17F;
    private static final float SELECT_H = 17F;

    private final TextMeasurer measurer;
    private final Tr tr;
    private final State state;
    private final Layout content;
    private final Layout bar;
    private final List<String> clicks = new ArrayList<>();
    private float innerX;
    private float innerW;

    private UploadLayout(TextMeasurer measurer, Tr tr, State state) {
        this.measurer = measurer;
        this.tr = tr;
        this.state = state;
        this.content = new Layout(measurer, tr);
        this.bar = new Layout(measurer, tr);
    }

    public static Page compute(float width, float height, TextMeasurer measurer, Tr tr, State state) {
        return new UploadLayout(measurer, tr, state).run(width, height);
    }

    /** The steps the rail shows, in order: the acceptance only while the account still owes it, as on the site. */
    public static List<Step> railSteps(boolean needsRules) {
        return needsRules ? List.of(Step.RULES, Step.FILES, Step.DETAILS, Step.PUBLISH) : List.of(Step.FILES, Step.DETAILS, Step.PUBLISH);
    }

    public static String stepLabel(Tr tr, Step step) {
        return switch (step) {
            case RULES -> tr.get(K + "step.intro");
            case FILES -> tr.get(K + "step.files");
            case DETAILS -> tr.get(K + "step.details");
            case PUBLISH -> tr.get(K + "step.publish");
            case PROGRESS -> tr.get(K + "progress.title");
        };
    }

    private Page run(float width, float height) {
        boolean compact = Metrics.compact(width, height);
        float buttonH = compact ? 16F : 19F;
        float barPad = compact ? 4F : 6F;
        float barH = buttonH + barPad * 2;
        Rect viewport = new Rect(0, 0, width, Math.max(20F, height - barH));
        float outer = width < NARROW ? 6F : 10F;
        float column = Math.min(width - outer * 2 - Metrics.SCROLLBAR, MAX_COLUMN);
        float columnX = (width - column) * 0.5F;

        float y = compact ? 8F : S6;
        y = header(columnX, column, y);
        y = rail(columnX, column, y + S4);

        boolean narrow = column < NARROW;
        float padX = narrow ? S4 : 32 * PX;
        float padTop = narrow ? S5 : S6;
        float panelTop = y + S4;
        content.add("panel", "", Kind.PANEL, new Rect(columnX, panelTop, column, 10));
        innerX = columnX + padX;
        innerW = column - padX * 2;
        float cy = panelTop + padTop;
        cy += paragraph("step.title", "panel", innerX, cy, innerW, stepTitle(), TextStyle.DISPLAY) + S2;
        cy = switch (state.step()) {
            case RULES -> rules(cy);
            case FILES -> files(cy);
            case DETAILS -> details(cy);
            case PUBLISH -> publish(cy);
            case PROGRESS -> progress(cy);
        };
        cy = issues(cy);
        float panelBottom = cy + (narrow ? S5 : 32 * PX) - S2;
        content.resize("panel", new Rect(columnX, panelTop, column, panelBottom - panelTop));
        content.setContentHeight(panelBottom + S6);

        actions(width, height, barH, buttonH, columnX, column, padX);
        return new Page(content, bar, viewport, List.copyOf(clicks));
    }

    // ---------------------------------------------------------------- frame

    private float header(float x, float w, float y) {
        float center = x + w * 0.5F;
        content.labelCentered("head.eyebrow", "", center, y, w, WorkshopStrings.upper(tr.get(K + "page.eyebrow")), TextStyle.PILL);
        y += TextFit.boxHeight(TextStyle.PILL) + S1;
        String title = state.update() ? tr.get(K + "page.update_title", state.setLabel()) : tr.get(K + "page.title");
        content.labelCentered("head.title", "", center, y, w, title, TextStyle.DISPLAY);
        y += TextFit.boxHeight(TextStyle.DISPLAY) + S2;
        String lead = state.update() ? tr.get(K + "page.update_lead") : tr.get(K + "page.lead");
        List<String> lines = TextFit.wrap(measurer, lead, TextStyle.BODY, w, 3);
        for (int i = 0; i < lines.size(); i++) {
            content.labelCentered("head.lead." + i, "", center, y, w, lines.get(i), TextStyle.BODY);
            y += Metrics.lineHeight(TextStyle.BODY);
        }
        return y;
    }

    /**
     * The step rail: a pill per step with its number. Like the site under 640 px, when every label does not fit
     * only the current step keeps its label; the others show their number.
     */
    private float rail(float x, float w, float y) {
        List<Step> steps = railSteps(state.needsRules());
        float pillH = 15F;
        float num = 11F;
        float gap = S2;
        TextStyle style = TextStyle.LABEL;
        float full = 0;
        for (Step step : steps) {
            full += pillWidth(stepLabel(tr, step), style, pillH, num, true);
        }
        full += gap * (steps.size() - 1);
        boolean labels = full <= w;
        float total = 0;
        for (Step step : steps) {
            total += pillWidth(stepLabel(tr, step), style, pillH, num, labels || step == state.step());
        }
        total += gap * (steps.size() - 1);
        float px = x + Math.max(0, (w - total) * 0.5F);
        for (int i = 0; i < steps.size(); i++) {
            Step step = steps.get(i);
            boolean showLabel = labels || step == state.step();
            float pw = Math.min(w, pillWidth(stepLabel(tr, step), style, pillH, num, showLabel));
            String id = "rail." + step.name().toLowerCase(java.util.Locale.ROOT);
            content.add(id, "", Kind.PANEL, new Rect(px, y, pw, pillH));
            Rect circle = new Rect(px + (pillH - num) * 0.5F, y + (pillH - num) * 0.5F, num, num);
            content.add(id + ".num", id, Kind.ICON, circle);
            String digit = String.valueOf(i + 1);
            content.labelCentered(id + ".digit", id + ".num", circle.centerX(), circle.y() + (num - style.capHeight()) * 0.5F, num, digit,
                    style);
            if (showLabel) {
                float lx = circle.right() + S2;
                content.label(id + ".label", id, lx, y + (pillH - style.capHeight()) * 0.5F, px + pw - lx - 10 * PX * 0.8F,
                        stepLabel(tr, step), style);
            }
            px += pw + gap;
        }
        return y + pillH;
    }

    private float pillWidth(String label, TextStyle style, float pillH, float num, boolean withLabel) {
        float base = pillH;
        return withLabel ? base + S2 + TextFit.width(measurer, label, style) + 14 * PX * 0.8F : base;
    }

    private String stepTitle() {
        if (state.step() != Step.PROGRESS) {
            return stepLabel(tr, state.step());
        }
        if (state.resultStatus() != null) {
            return switch (state.resultStatus()) {
                case "pending" -> tr.get(K + "result.pending");
                case "wip" -> tr.get(K + "result.wip");
                default -> tr.get(K + "result.community");
            };
        }
        return state.failure() != null ? tr.get(K + "progress.failed") : tr.get(K + "progress.title");
    }

    // ---------------------------------------------------------------- step 1: the acceptance

    private float rules(float y) {
        y += paragraph("rules.lead", "panel", innerX, y, innerW, tr.get(K + "accept.lead"), TextStyle.BODY) + S5 - S2;
        if (state.rulesChanged() != null) {
            String text = state.rulesChanged().isEmpty() ? tr.get(K + "intro.changed")
                    : tr.get(WorkshopStrings.KEY + "accept.changed_with", state.rulesChanged());
            y = banner("rules.changed", y, text) + S4;
        }
        float gap = S3;
        int columns = Math.max(1, Math.min(3, (int) ((innerW + gap) / (220 * PX + gap))));
        float cardW = (innerW - gap * (columns - 1)) / columns;
        float pad = S4 * 0.8F;
        for (int row = 0; row * columns < CARDS.size(); row++) {
            float rowH = 0;
            for (int c = 0; c < columns && row * columns + c < CARDS.size(); c++) {
                rowH = Math.max(rowH, cardHeight(CARDS.get(row * columns + c), cardW - pad * 2, pad));
            }
            for (int c = 0; c < columns && row * columns + c < CARDS.size(); c++) {
                card(CARDS.get(row * columns + c), innerX + c * (cardW + gap), y, cardW, rowH, pad);
            }
            y += rowH + gap;
        }
        y += S5 - gap;
        content.label("rules.legend", "panel", innerX, y, innerW, tr.get(K + "intro.confirm"), TextStyle.TITLE);
        y += TextFit.boxHeight(TextStyle.TITLE) + S3;
        for (String key : ACCEPTANCE) {
            String label = key.equals("terms")
                    ? tr.get(K + "accept.terms", state.termsVersion(), state.rulesVersion())
                    : tr.get(K + "accept.age");
            y = acceptRow(key, y, label) + S2;
        }
        return y - S2;
    }

    private float cardHeight(String key, float textW, float pad) {
        float h = pad + 20 * PX + S1 + S1;
        h += TextFit.wrap(measurer, tr.get(K + "accept.card." + key + ".title"), TextStyle.STRONG, textW).size()
                * Metrics.lineHeight(TextStyle.STRONG);
        h += S1;
        h += TextFit.wrap(measurer, tr.get(K + "accept.card." + key + ".text"), TextStyle.CAPTION, textW).size()
                * lineHeight(TextStyle.CAPTION);
        h += S2 + TextFit.boxHeight(TextStyle.LABEL) + pad;
        return h;
    }

    private void card(String key, float x, float y, float w, float h, float pad) {
        String id = "card." + key;
        content.add(id, "panel", Kind.PANEL, new Rect(x, y, w, h));
        float icon = 20 * PX;
        content.add(id + ".icon", id, Kind.ICON, new Rect(x + pad, y + pad, icon, icon));
        float cy = y + pad + icon + S1 + S1;
        float textW = w - pad * 2;
        cy += paragraph(id + ".title", id, x + pad, cy, textW, tr.get(K + "accept.card." + key + ".title"), TextStyle.STRONG,
                Metrics.lineHeight(TextStyle.STRONG)) + S1;
        paragraph(id + ".text", id, x + pad, cy, textW, tr.get(K + "accept.card." + key + ".text"), TextStyle.CAPTION,
                lineHeight(TextStyle.CAPTION));
        // The link sits at the bottom of the card, as the site's margin-top: auto puts it.
        float linkY = y + h - pad - TextFit.boxHeight(TextStyle.LABEL);
        float iconSize = TextStyle.LABEL.capHeight() + 1;
        Layout.Box link = content.label(id + ".link", id, x + pad, linkY, textW - iconSize - S1,
                tr.get(K + "accept.card." + key + ".link"), TextStyle.LABEL);
        content.add(id + ".link.icon", id, Kind.ICON, new Rect(link.rect().right() + S1, linkY - 0.5F, iconSize, iconSize));
        clicks.add(id + ".link");
    }

    /** One confirmation of the acceptance: a whole-row checkbox, and for the terms the links to both documents. */
    private float acceptRow(String key, float y, String label) {
        String id = "accept." + key;
        float padX = S4 * 0.8F;
        float padY = S3 * 0.8F;
        content.add(id, "panel", Kind.PANEL, new Rect(innerX, y, innerW, 10));
        content.add(id + ".box", id, Kind.ICON, new Rect(innerX + padX, y + padY, CHECK, CHECK));
        float tx = innerX + padX + CHECK + S3;
        float tw = innerX + innerW - padX - tx;
        float cy = y + padY + 1;
        cy += paragraph(id + ".label", id, tx, cy, tw, label, TextStyle.BODY, lineHeight(TextStyle.BODY));
        if (key.equals("terms")) {
            Layout.Box terms = content.label(id + ".read_terms", id, tx, cy, tw * 0.5F - S2,
                    tr.get(WorkshopStrings.KEY + "accept.read_terms"), TextStyle.LABEL);
            content.label(id + ".read_rules", id, terms.rect().right() + S3, cy, tx + tw - terms.rect().right() - S3,
                    tr.get(K + "accept.read_rules"), TextStyle.LABEL);
            clicks.add(id + ".read_terms");
            clicks.add(id + ".read_rules");
            cy += lineHeight(TextStyle.LABEL);
        }
        float h = Math.max(cy - y + padY - 2, CHECK + padY * 2);
        content.resize(id, new Rect(innerX, y, innerW, h));
        clicks.add(id);
        return y + h;
    }

    // ---------------------------------------------------------------- step 2: files

    private float files(float y) {
        y = sectionLine("files.line.charts", y);
        String count = state.charts().isEmpty() ? "" : String.valueOf(selectedCount());
        Layout.Box title = content.label("files.charts", "panel", innerX, y, innerW * 0.7F, tr.get(K + "files.charts"),
                TextStyle.TITLE);
        if (!count.isEmpty()) {
            content.label("files.count", "panel", title.rect().right() + S2,
                    y + TextStyle.TITLE.capHeight() - TextStyle.LABEL.capHeight(), 30, count, TextStyle.LABEL);
        }
        y += TextFit.boxHeight(TextStyle.TITLE) + S2;
        y += paragraph("files.where", "panel", innerX, y, innerW, tr.get(K + "files.where", state.tracksFolder()),
                TextStyle.CAPTION, lineHeight(TextStyle.CAPTION)) + S2;
        if (state.charts().isEmpty()) {
            y += paragraph("files.empty", "panel", innerX, y, innerW, tr.get(K + "tracks.empty"), TextStyle.BODY,
                    lineHeight(TextStyle.BODY));
        }
        for (int i = 0; i < state.charts().size(); i++) {
            y = chartRow(i, state.charts().get(i), y) + S2;
        }
        y -= S2;

        y = sectionLine("files.line.audio", y + S6);
        y = sectionTitle("files.audio", y, tr.get(K + "audio"));
        y = choice("audio.upload", y, innerX, innerW, tr.get(K + "audio.upload"), tr.get(K + "audio.upload.hint"),
                "upload".equals(state.audioMode())) + S2;
        y = choice("audio.external", y, innerX, innerW, tr.get(K + "audio.external"), tr.get(K + "audio.external.hint"),
                "external".equals(state.audioMode()));
        y = audioPanel(y + S3);

        y = sectionLine("files.line.cover", y + S6);
        y = sectionTitle("files.cover", y, tr.get(K + "cover"));
        boolean upload = !"none".equals(state.coverMode());
        y = choice("cover.upload", y, innerX, innerW, tr.get(K + "cover.upload"), tr.get(K + "cover.upload.hint"), upload) + S2;
        y = choice("cover.none", y, innerX, innerW, tr.get(K + "cover.none"), tr.get(K + "cover.none.hint"), !upload);
        if (upload) {
            y = coverPanel(y + S3);
        }
        return y;
    }

    private int selectedCount() {
        int count = 0;
        for (Chart chart : state.charts()) {
            if (chart.selected()) {
                count++;
            }
        }
        return count;
    }

    /**
     * One track as the site's chart row: the left stripe turns green once it is part of the map, and a ticked track
     * opens the two inputs the site has, the difficulty name and the key.
     */
    private float chartRow(int index, Chart chart, float y) {
        String id = "chart." + index;
        float pad = S3 * 0.8F;
        content.add(id, "panel", Kind.PANEL, new Rect(innerX, y, innerW, 10));
        content.add(id + ".stripe", id, Kind.PANEL, new Rect(innerX, y, 2, 10));
        float bx = innerX + pad + 2;
        float tx = bx + CHECK + S3;
        float tw = innerX + innerW - pad - tx;
        content.add(id + ".box", id, Kind.ICON, new Rect(bx, y + pad, CHECK, CHECK));
        float cy = y + pad + (CHECK - TextStyle.STRONG.capHeight()) * 0.5F;
        content.label(id + ".title", id, tx, cy, tw, chart.title(), TextStyle.STRONG);
        cy += lineHeight(TextStyle.STRONG);
        content.label(id + ".meta", id, tx, cy, tw, chart.meta(), TextStyle.CAPTION);
        cy += TextFit.boxHeight(TextStyle.CAPTION);
        if (chart.selected()) {
            cy += S2;
            float keyW = Math.min(140 * PX, tw * 0.4F);
            float nameW = tw - keyW - S2;
            content.label(id + ".difficulty.label", id, tx, cy, nameW, tr.get(K + "difficulty"), TextStyle.LABEL);
            content.label(id + ".key.label", id, tx + nameW + S2, cy, keyW, tr.get(K + "chart_key"), TextStyle.LABEL);
            cy += TextFit.boxHeight(TextStyle.LABEL) + S1;
            content.add("wizard.track." + index + ".difficulty", id, Kind.FIELD, new Rect(tx, cy, nameW, FIELD_H));
            content.add("wizard.track." + index + ".key", id, Kind.FIELD, new Rect(tx + nameW + S2, cy, keyW, FIELD_H));
            cy += FIELD_H;
        }
        float h = cy - y + pad;
        content.resize(id, new Rect(innerX, y, innerW, h));
        content.resize(id + ".stripe", new Rect(innerX, y, 2, h));
        clicks.add(id);
        return y + h;
    }

    private float audioPanel(float y) {
        String id = "audio.panel";
        float pad = S4 * 0.8F;
        content.add(id, "panel", Kind.PANEL, new Rect(innerX, y, innerW, 10));
        float cy = y + pad;
        float tw = innerW - pad * 2;
        String file = state.text("audio.file");
        if ("external".equals(state.audioMode())) {
            cy += paragraph(id + ".text", id, innerX + pad, cy, tw, tr.get(K + "audio.external.text"), TextStyle.BODY,
                    lineHeight(TextStyle.BODY));
        } else if (file.isEmpty()) {
            cy += paragraph(id + ".missing", id, innerX + pad, cy, tw, tr.get(K + "problem.no_audio"), TextStyle.LABEL,
                    lineHeight(TextStyle.LABEL));
        } else {
            float icon = 15 * PX + 2;
            content.add(id + ".icon", id, Kind.ICON, new Rect(innerX + pad, cy, icon, icon));
            float tx = innerX + pad + icon + S3;
            content.label(id + ".name", id, tx, cy, innerX + innerW - pad - tx, file, TextStyle.STRONG);
            cy += lineHeight(TextStyle.STRONG);
            cy += paragraph(id + ".from", id, tx, cy, innerX + innerW - pad - tx, tr.get(K + "audio.from_charts", file),
                    TextStyle.CAPTION, lineHeight(TextStyle.CAPTION));
        }
        float h = cy - y + pad - 2;
        content.resize(id, new Rect(innerX, y, innerW, h));
        return y + h;
    }

    /** The cover subpanel: the image the map will carry, at the site's 48:14 strip, and "Choose image". */
    private float coverPanel(float y) {
        String id = "cover.panel";
        float pad = S4 * 0.8F;
        content.add(id, "panel", Kind.PANEL, new Rect(innerX, y, innerW, 10));
        float cy = y + pad;
        float tw = innerW - pad * 2;
        if (state.coverImage()) {
            float imageW = Math.min(tw, 480 * PX);
            content.add(id + ".image", id, Kind.IMAGE, new Rect(innerX + pad, cy, imageW, imageW * 14F / 48F));
            cy += imageW * 14F / 48F + S2;
            String name = state.text("cover.name");
            if (!name.isEmpty()) {
                content.label(id + ".name", id, innerX + pad, cy, tw, name, TextStyle.CAPTION);
                cy += lineHeight(TextStyle.CAPTION);
            }
        } else {
            cy += paragraph(id + ".missing", id, innerX + pad, cy, tw, tr.get(K + "cover.missing"), TextStyle.LABEL,
                    lineHeight(TextStyle.LABEL));
        }
        cy += S1;
        cy = smallButton(id + ".choose", id, innerX + pad, cy, tw, tr.get(K + "cover.choose"));
        float h = cy - y + pad;
        content.resize(id, new Rect(innerX, y, innerW, h));
        clicks.add(id + ".choose");
        return y + h;
    }

    // ---------------------------------------------------------------- step 3: details

    private float details(float y) {
        boolean twoColumns = innerW >= 300F;
        float gap = S4;
        float half = (innerW - gap) * 0.5F;
        if (twoColumns) {
            float a = field("title", innerX, y, half, tr.get(K + "title"), null);
            float b = field("artist", innerX + half + gap, y, half, tr.get(K + "artist"), null);
            y = Math.max(a, b);
        } else {
            y = field("title", innerX, y, innerW, tr.get(K + "title"), null);
            y = field("artist", innerX, y, innerW, tr.get(K + "artist"), null);
        }
        y = field("source", innerX, y, innerW, tr.get(K + "source"), tr.get(K + "source.hint"));
        if (twoColumns) {
            float a = select("genre", innerX, y, half, tr.get(K + "genre"));
            float b = select("language", innerX + half + gap, y, half, tr.get(K + "language"));
            y = Math.max(a, b);
        } else {
            y = select("genre", innerX, y, innerW, tr.get(K + "genre"));
            y = select("language", innerX, y, innerW, tr.get(K + "language"));
        }
        y = field("tags", innerX, y, innerW, tr.get(K + "tags"), tr.get(K + "tags.help"));
        y = field("description", innerX, y, innerW, tr.get(K + "description"), tr.get(K + "description.hint"));
        y = previewPoint(y + S2) + S5;
        content.label("flags.legend", "panel", innerX, y, innerW, tr.get(K + "flags.label"), TextStyle.LABEL);
        y += TextFit.boxHeight(TextStyle.LABEL) + S2;
        int columns = innerW >= 2 * 240 * PX + S2 ? 2 : 1;
        float cardW = (innerW - S2 * (columns - 1)) / columns;
        for (int row = 0; row * columns < FLAGS.size(); row++) {
            float rowH = 0;
            for (int c = 0; c < columns && row * columns + c < FLAGS.size(); c++) {
                String key = FLAGS.get(row * columns + c);
                rowH = Math.max(rowH, checkHeight(cardW, tr.get(K + "flag." + key), tr.get(K + "flag." + key + ".hint")));
            }
            for (int c = 0; c < columns && row * columns + c < FLAGS.size(); c++) {
                String key = FLAGS.get(row * columns + c);
                check("flag." + key, innerX + c * (cardW + S2), y, cardW, rowH, tr.get(K + "flag." + key),
                        tr.get(K + "flag." + key + ".hint"));
            }
            y += rowH + S2;
        }
        y += S4 - S2;
        y = field("credits", innerX, y, innerW, tr.get(K + "credits"), tr.get(K + "credits.hint"));
        if (state.update()) {
            y = field("changelog", innerX, y, innerW, tr.get(K + "changelog"), tr.get(K + "changelog.hint"));
        }
        return y - S4;
    }

    /**
     * A site field: the label, the input, then the hint, or in its place the error that concerns this field (the
     * site's {@code field__error}). Multi-line fields take the height their text wants.
     */
    private float field(String name, float x, float y, float w, String label, String hint) {
        String id = "wizard." + name;
        content.label(id + ".label", "panel", x, y, w, label, TextStyle.LABEL);
        y += TextFit.boxHeight(TextStyle.LABEL) + S1;
        Float wanted = state.fieldHeights().get(id);
        float h = MULTILINE.contains(name) ? Math.max(name.equals("description") ? 48F : 30F, wanted == null ? 0F : wanted) : FIELD_H;
        content.add(id, "panel", Kind.FIELD, new Rect(x, y, w, h));
        y += h + S1;
        String error = state.fieldErrors().get(name);
        if (error != null && !error.isEmpty()) {
            y += paragraph(id + ".error", "panel", x, y, w, error, TextStyle.LABEL, lineHeight(TextStyle.LABEL));
        } else if (hint != null && !hint.isEmpty()) {
            y += paragraph(id + ".hint", "panel", x, y, w, hint, TextStyle.CAPTION, lineHeight(TextStyle.CAPTION));
        }
        return y + S4 - S1;
    }

    /** The site's select, drawn as a box with the value between a back and a forward arrow that step through it. */
    private float select(String name, float x, float y, float w, String label) {
        String id = "select." + name;
        content.label(id + ".label", "panel", x, y, w, label, TextStyle.LABEL);
        y += TextFit.boxHeight(TextStyle.LABEL) + S1;
        content.add(id, "panel", Kind.PANEL, new Rect(x, y, w, SELECT_H));
        float arrow = SELECT_H;
        content.add(id + ".prev", id, Kind.BUTTON, new Rect(x, y, arrow, SELECT_H));
        content.add(id + ".next", id, Kind.BUTTON, new Rect(x + w - arrow, y, arrow, SELECT_H));
        content.label(id + ".value", id, x + arrow, y + (SELECT_H - TextStyle.BODY.capHeight()) * 0.5F, w - arrow * 2,
                state.text(name), TextStyle.BODY);
        clicks.add(id + ".prev");
        clicks.add(id + ".next");
        return y + SELECT_H + S4;
    }

    /** The preview point: the label and the window's time, the strip with the 30 seconds, then Play and the hint. */
    private float previewPoint(float y) {
        String id = "pp";
        String range = WorkshopStrings.duration(state.previewStartMs()) + " – "
                + WorkshopStrings.duration(Math.min(Math.max(state.durationMs(), PREVIEW_MS), state.previewStartMs() + PREVIEW_MS));
        Layout.Box time = content.labelRight(id + ".time", "panel", innerX + innerW, y, innerW * 0.4F, range, TextStyle.LABEL);
        content.label(id + ".label", "panel", innerX, y, time.rect().x() - innerX - S3, tr.get(K + "preview.label"), TextStyle.LABEL);
        y += TextFit.boxHeight(TextStyle.LABEL) + S2;
        float stripH = 72 * PX * 0.75F;
        content.add(id + ".strip", "panel", Kind.PANEL, new Rect(innerX, y, innerW, stripH));
        float duration = Math.max(1F, Math.max(state.durationMs(), PREVIEW_MS));
        float windowW = Math.max(6F, innerW * Math.min(1F, PREVIEW_MS / duration));
        float start = Math.min(1F, state.previewStartMs() / duration);
        float windowX = Math.min(innerX + innerW - windowW, innerX + innerW * start);
        content.add(id + ".window", id + ".strip", Kind.BAR, new Rect(windowX, y, windowW, stripH));
        clicks.add(id + ".strip");
        y += stripH + S2;
        String play = state.previewPlaying() ? tr.get(K + "preview.stop") : tr.get(K + "preview.play");
        float playW = TextFit.width(measurer, play, TextStyle.LABEL) + S3 * 2 + 9;
        Rect button = new Rect(innerX, y, Math.min(innerW, playW), 14F);
        content.add(id + ".play", "panel", Kind.BUTTON, button);
        content.label(id + ".play.text", id + ".play", button.x() + S3 + 9, button.y() + (14F - TextStyle.LABEL.capHeight()) * 0.5F,
                button.w() - S3 * 2 - 9, play, TextStyle.LABEL);
        clicks.add(id + ".play");
        float hx = button.right() + S3;
        float hintW = innerX + innerW - hx;
        List<String> hint = TextFit.wrap(measurer, tr.get(K + "preview.hint"), TextStyle.CAPTION, Math.max(1, hintW));
        if (hintW >= 90 && hint.size() <= 2) {
            float hy = button.y() + (14F - hint.size() * lineHeight(TextStyle.CAPTION) + 2) * 0.5F;
            paragraph(id + ".hint", "panel", hx, Math.max(button.y(), hy), hintW, tr.get(K + "preview.hint"), TextStyle.CAPTION,
                    lineHeight(TextStyle.CAPTION));
            return y + 14F;
        }
        y += 14F + S1;
        return y + paragraph(id + ".hint", "panel", innerX, y, innerW, tr.get(K + "preview.hint"), TextStyle.CAPTION,
                lineHeight(TextStyle.CAPTION));
    }

    private float checkHeight(float w, String label, String hint) {
        float padX = S3 * 0.8F;
        float tw = w - padX * 2 - CHECK - S3;
        float h = S2 * 2;
        h += TextFit.wrap(measurer, label, TextStyle.STRONG, tw).size() * lineHeight(TextStyle.STRONG);
        if (hint != null) {
            h += TextFit.wrap(measurer, hint, TextStyle.CAPTION, tw).size() * lineHeight(TextStyle.CAPTION);
        }
        // The last line needs only its own ink, not the gap to a next line.
        TextStyle last = hint != null ? TextStyle.CAPTION : TextStyle.STRONG;
        h -= lineHeight(last) - TextFit.boxHeight(last);
        return Math.max(h + 1, CHECK + S2 * 2);
    }

    /** The site's {@code upw-check}: a checkbox card with a bold label and an optional hint. */
    private void check(String id, float x, float y, float w, float h, String label, String hint) {
        float padX = S3 * 0.8F;
        content.add(id, "panel", Kind.PANEL, new Rect(x, y, w, h));
        content.add(id + ".box", id, Kind.ICON, new Rect(x + padX, y + S2 + 0.5F, CHECK, CHECK));
        float tx = x + padX + CHECK + S3;
        float tw = x + w - padX - tx;
        float cy = y + S2 + 1;
        cy += paragraph(id + ".label", id, tx, cy, tw, label, TextStyle.STRONG, lineHeight(TextStyle.STRONG));
        if (hint != null) {
            paragraph(id + ".hint", id, tx, cy, tw, hint, TextStyle.CAPTION, lineHeight(TextStyle.CAPTION));
        }
        clicks.add(id);
    }

    // ---------------------------------------------------------------- step 4: publish

    private float publish(float y) {
        y = headerPreview("hp", y, innerW) + S6;
        content.label("publish.target", "panel", innerX, y, innerW, tr.get(K + "publish.target"), TextStyle.LABEL);
        y += TextFit.boxHeight(TextStyle.LABEL) + S2;
        boolean row = innerW >= 2 * 220 * PX + S2;
        if (row) {
            float w = (innerW - S2) * 0.5F;
            float hint = Math.max(choiceHeight(w, tr.get(K + "target.community"), tr.get(K + "target.community.hint")),
                    choiceHeight(w, tr.get(K + "target.wip"), tr.get(K + "target.wip.hint")));
            choice("target.community", y, innerX, w, tr.get(K + "target.community"), tr.get(K + "target.community.hint"),
                    "community".equals(state.target()), hint);
            choice("target.wip", y, innerX + w + S2, w, tr.get(K + "target.wip"), tr.get(K + "target.wip.hint"),
                    "wip".equals(state.target()), hint);
            y += hint;
        } else {
            y = choice("target.community", y, innerX, innerW, tr.get(K + "target.community"), tr.get(K + "target.community.hint"),
                    "community".equals(state.target())) + S2;
            y = choice("target.wip", y, innerX, innerW, tr.get(K + "target.wip"), tr.get(K + "target.wip.hint"),
                    "wip".equals(state.target()));
        }
        if (state.acceptedOn() != null && !state.acceptedOn().isEmpty()) {
            y += S4;
            float icon = TextStyle.CAPTION.capHeight() + 2;
            content.add("accepted.icon", "panel", Kind.ICON, new Rect(innerX, y - 1, icon, icon));
            String view = tr.get(K + "accept.view");
            float viewW = TextFit.width(measurer, view, TextStyle.LABEL);
            Layout.Box line = content.label("accepted.text", "panel", innerX + icon + S2, y,
                    innerW - icon - S2 - viewW - S2, tr.get(K + "accepted_on", state.acceptedOn()), TextStyle.CAPTION);
            content.label("accepted.view", "panel", line.rect().right() + S2, y, viewW, view, TextStyle.LABEL);
            clicks.add("accepted.view");
            y += TextFit.boxHeight(TextStyle.LABEL);
        }
        y += S4;
        String label = tr.get(K + "confirm.rights");
        float h = checkHeight(innerW, label, null);
        check("confirm.rights", innerX, y, innerW, h, label, null);
        return y + h;
    }

    /**
     * The site's header preview: the cover behind a dark gradient, the status pill, the title, the artist and a disc
     * per chart. The result screen shows the same card for the map that went live.
     */
    private float headerPreview(String id, float y, float w) {
        float pad = S5;
        float discs = Math.max(1, selectedCount());
        String title = state.text("title").isBlank() ? tr.get(K + "untitled") : state.text("title");
        String artist = state.text("artist");
        float inner = TextFit.boxHeight(TextStyle.PILL) + 4 + S2 + TextFit.boxHeight(TextStyle.DISPLAY) + 2
                + (artist.isBlank() ? 0 : lineHeight(TextStyle.TITLE)) + S2 + 8;
        float h = Math.max(160 * PX * 0.75F, inner + pad + S4);
        content.add(id, "panel", Kind.PANEL, new Rect(innerX, y, w, h));
        if (state.coverImage()) {
            content.addFloating(id + ".image", id, Kind.IMAGE, new Rect(innerX, y, w, h));
        }
        float cy = y + h - S4 - inner;
        String status = state.step() == Step.PROGRESS && state.resultStatus() != null ? state.resultStatus() : state.target();
        String pill = WorkshopStrings.status(tr, status);
        float pillW = TextFit.width(measurer, pill, TextStyle.PILL) + 8;
        Rect pillRect = new Rect(innerX + pad, cy, Math.min(pillW, w - pad * 2), TextFit.boxHeight(TextStyle.PILL) + 4);
        content.add(id + ".pill", id, Kind.PILL, pillRect);
        content.floatingText(id + ".pill.text", id, new Rect(pillRect.x() + 4, pillRect.y() + 2, pillRect.w() - 8,
                TextFit.boxHeight(TextStyle.PILL)), pill, TextStyle.PILL);
        cy += pillRect.h() + S2;
        content.label(id + ".title", id, innerX + pad, cy, w - pad * 2, title, TextStyle.DISPLAY);
        cy += TextFit.boxHeight(TextStyle.DISPLAY) + 2;
        if (!artist.isBlank()) {
            content.label(id + ".artist", id, innerX + pad, cy, w - pad * 2, artist, TextStyle.TITLE);
            cy += lineHeight(TextStyle.TITLE);
        }
        cy += S2;
        int shown = (int) Math.min(discs, Math.floor((w - pad * 2 + S1) / (8 + S1)));
        for (int i = 0; i < shown; i++) {
            content.add(id + ".disc." + i, id, Kind.ICON, new Rect(innerX + pad + i * (8 + S1), cy, 8, 8));
        }
        return y + h;
    }

    // ---------------------------------------------------------------- progress and the result

    private float progress(float y) {
        y += S3;
        for (int i = 0; i < state.stages().size(); i++) {
            y = stageRow(i, state.stages().get(i), y) + S1;
        }
        y -= S1;
        if (state.failure() != null) {
            y += S5;
            float pad = S4 * 0.8F;
            content.add("failure", "panel", Kind.PANEL, new Rect(innerX, y, innerW, 10));
            float h = paragraph("failure.text", "failure", innerX + pad, y + pad, innerW - pad * 2, state.failure(), TextStyle.STRONG,
                    lineHeight(TextStyle.STRONG)) + pad * 2 - 2;
            content.resize("failure", new Rect(innerX, y, innerW, h));
            y += h;
        }
        if (state.resultStatus() != null) {
            y = headerPreview("result", y + S6, innerW);
        }
        return y;
    }

    private float stageRow(int index, Stage stage, float y) {
        String id = "stage." + index;
        float padX = S4 * 0.8F;
        float padY = S3 * 0.8F;
        content.add(id, "panel", Kind.PANEL, new Rect(innerX, y, innerW, 10));
        float icon = 11F;
        float textW = innerW - padX * 2 - icon - S3;
        float cy = y + padY;
        content.label(id + ".label", id, innerX + padX, cy + (icon - TextStyle.STRONG.capHeight()) * 0.5F, textW, stage.label(),
                TextStyle.STRONG);
        content.add(id + ".icon", id, Kind.ICON, new Rect(innerX + innerW - padX - icon, cy, icon, icon));
        cy += icon;
        if (stage.state() == StageState.RUNNING && stage.fraction() >= 0) {
            cy += S2;
            content.add(id + ".bar", id, Kind.BAR, new Rect(innerX + padX, cy, innerW - padX * 2, 4));
            cy += 4;
        }
        if (stage.detail() != null) {
            for (int line = 0; line < stage.detail().size(); line++) {
                cy += S1;
                content.label(id + ".detail." + line, id, innerX + padX, cy, innerW - padX * 2, stage.detail().get(line),
                        TextStyle.CAPTION);
                cy += TextFit.boxHeight(TextStyle.CAPTION);
            }
        }
        float h = cy - y + padY;
        content.resize(id, new Rect(innerX, y, innerW, h));
        return y + h;
    }

    // ---------------------------------------------------------------- shared pieces

    private float issues(float y) {
        if (state.issues().isEmpty()) {
            return y;
        }
        y += S4;
        for (int i = 0; i < state.issues().size(); i++) {
            Issue issue = state.issues().get(i);
            String id = "issue." + i;
            float padX = S3 * 0.8F;
            float padY = S2;
            float icon = TextStyle.LABEL.capHeight() + 2;
            content.add(id, "panel", Kind.PANEL, new Rect(innerX, y, innerW, 10));
            content.add(id + ".icon", id, Kind.ICON, new Rect(innerX + padX, y + padY - 1, icon, icon));
            float tx = innerX + padX + icon + S2;
            float h = paragraph(id + ".text", id, tx, y + padY, innerX + innerW - padX - tx, issue.text(), TextStyle.LABEL,
                    lineHeight(TextStyle.LABEL)) + padY * 2 - 2;
            content.resize(id, new Rect(innerX, y, innerW, h));
            y += h + S1;
        }
        return y - S1;
    }

    private float banner(String id, float y, String text) {
        float pad = S3 * 0.8F;
        float icon = TextStyle.LABEL.capHeight() + 2;
        content.add(id, "panel", Kind.PANEL, new Rect(innerX, y, innerW, 10));
        content.add(id + ".icon", id, Kind.ICON, new Rect(innerX + pad, y + pad - 1, icon, icon));
        float tx = innerX + pad + icon + S2;
        float h = paragraph(id + ".text", id, tx, y + pad, innerX + innerW - pad - tx, text, TextStyle.LABEL,
                lineHeight(TextStyle.LABEL)) + pad * 2 - 2;
        content.resize(id, new Rect(innerX, y, innerW, h));
        return y + h;
    }

    /** The site's section rule: a 1 px line, then the section below it. */
    private float sectionLine(String id, float y) {
        content.add(id, "panel", Kind.PANEL, new Rect(innerX, y + S2, innerW, 1));
        return y + S2 + 1 + S5 - S2;
    }

    private float sectionTitle(String id, float y, String text) {
        content.label(id, "panel", innerX, y, innerW, text, TextStyle.TITLE);
        return y + TextFit.boxHeight(TextStyle.TITLE) + S3;
    }

    private float choiceHeight(float w, String label, String hint) {
        float padX = S4 * 0.8F;
        float tw = w - padX * 2 - CHECK - S3;
        float h = S3 * 0.8F * 2;
        h += lineHeight(TextStyle.STRONG) * TextFit.wrap(measurer, label, TextStyle.STRONG, tw).size();
        boolean hinted = hint != null && !hint.isEmpty();
        if (hinted) {
            h += 1 + lineHeight(TextStyle.CAPTION) * TextFit.wrap(measurer, hint, TextStyle.CAPTION, tw).size();
        }
        // The last line needs only its own ink, not the gap to a next line.
        TextStyle last = hinted ? TextStyle.CAPTION : TextStyle.STRONG;
        return h - (lineHeight(last) - TextFit.boxHeight(last)) + 1;
    }

    private float choice(String id, float y, float x, float w, String label, String hint, boolean selected) {
        float h = choiceHeight(w, label, hint);
        choice(id, y, x, w, label, hint, selected, h);
        return y + h;
    }

    /** The site's {@code upw-choice__opt}: a radio card with a bold label over a muted hint. */
    private void choice(String id, float y, float x, float w, String label, String hint, boolean selected, float h) {
        String box = "choice." + id;
        float padX = S4 * 0.8F;
        float padY = S3 * 0.8F;
        content.add(box, "panel", Kind.PANEL, new Rect(x, y, w, h));
        content.add(box + ".radio", box, Kind.ICON, new Rect(x + padX, y + padY + 0.5F, CHECK, CHECK));
        float tx = x + padX + CHECK + S3;
        float tw = x + w - padX - tx;
        float cy = y + padY + 1;
        cy += paragraph(box + ".label", box, tx, cy, tw, label, TextStyle.STRONG, lineHeight(TextStyle.STRONG));
        if (hint != null && !hint.isEmpty()) {
            paragraph(box + ".hint", box, tx, cy + 1, tw, hint, TextStyle.CAPTION, lineHeight(TextStyle.CAPTION));
        }
        clicks.add(box);
    }

    private float smallButton(String id, String parent, float x, float y, float maxW, String text) {
        float w = Math.min(maxW, TextFit.width(measurer, text, TextStyle.LABEL) + S3 * 2);
        float h = 14F;
        content.add(id, parent, Kind.BUTTON, new Rect(x, y, w, h));
        content.label(id + ".text", id, x + S3, y + (h - TextStyle.LABEL.capHeight()) * 0.5F, w - S3 * 2, text, TextStyle.LABEL);
        return y + h;
    }

    private float paragraph(String id, String parent, float x, float y, float w, String text, TextStyle style) {
        return paragraph(id, parent, x, y, w, text, style, lineHeight(style));
    }

    private float paragraph(String id, String parent, float x, float y, float w, String text, TextStyle style, float lineHeight) {
        float h = content.paragraph(id, parent, x, y, w, text, style, lineHeight, 0);
        return h == 0 ? 0 : h - (lineHeight - TextFit.boxHeight(style));
    }

    /** The site's body line height (1.45 to 1.5) in our units. */
    private static float lineHeight(TextStyle style) {
        return Math.max(Metrics.lineHeight(style), style.size() * 1.3F);
    }

    // ---------------------------------------------------------------- the pinned bar

    /**
     * The step's buttons, as the site's {@code upw-actions}: the quiet way back on the left, the one primary action
     * on the right at the site's 200 px minimum while there is room.
     */
    private void actions(float width, float height, float barH, float buttonH, float columnX, float column, float padX) {
        float top = height - barH;
        bar.add("bar", "", Kind.PANEL, new Rect(0, top, width, barH));
        float left = columnX + Math.min(padX, 12F);
        float right = columnX + column - Math.min(padX, 12F);
        float by = top + (barH - buttonH) * 0.5F;
        List<String[]> leftButtons = new ArrayList<>();
        List<String[]> rightButtons = new ArrayList<>();
        switch (state.step()) {
            case RULES -> rightButtons.add(new String[] {"bar.next", state.busy() ? tr.get(K + "accept.busy") : tr.get(K + "understood"),
                    "primary"});
            case FILES -> {
                leftButtons.add(new String[] {"bar.back", state.update() ? tr.get(WorkshopStrings.KEY + "cancel") : tr.get(K + "back"),
                        "ghost"});
                rightButtons.add(new String[] {"bar.next", tr.get(K + "files.next"), "primary"});
            }
            case DETAILS -> {
                leftButtons.add(new String[] {"bar.back", tr.get(K + "back"), "ghost"});
                rightButtons.add(new String[] {"bar.next", tr.get(K + "details.next"), "primary"});
            }
            case PUBLISH -> {
                leftButtons.add(new String[] {"bar.back", tr.get(K + "back"), "ghost"});
                rightButtons.add(new String[] {"bar.publish", state.update() ? tr.get(K + "publish.update") : tr.get(K + "publish"),
                        "primary"});
            }
            case PROGRESS -> {
                if (state.resultStatus() != null) {
                    leftButtons.add(new String[] {"bar.another", tr.get(K + "progress.another"), "ghost"});
                    rightButtons.add(new String[] {"bar.install", tr.get(K + "install"), "secondary"});
                    rightButtons.add(new String[] {"bar.open", tr.get(K + "open_set"), "primary"});
                } else if (state.failure() != null) {
                    leftButtons.add(new String[] {"bar.close", tr.get(WorkshopStrings.KEY + "close"), "ghost"});
                    rightButtons.add(new String[] {"bar.edit", tr.get(K + "progress.back_to_edit"), "primary"});
                } else if (state.running()) {
                    leftButtons.add(new String[] {"bar.cancel", tr.get(WorkshopStrings.KEY + "cancel"), "ghost"});
                }
            }
        }
        float gap = S3;
        float available = right - left;
        float pad = buttonH >= 19F ? S6 : S4;
        List<String[]> all = new ArrayList<>(leftButtons);
        all.addAll(rightButtons);
        float[] widths = new float[all.size()];
        float natural = gap * Math.max(0, all.size() - 1) + (leftButtons.isEmpty() || rightButtons.isEmpty() ? 0 : gap);
        for (int i = 0; i < all.size(); i++) {
            TextStyle style = "primary".equals(all.get(i)[2]) ? TextStyle.STRONG : TextStyle.BODY;
            widths[i] = TextFit.width(measurer, all.get(i)[1], style) + pad * 2;
            natural += widths[i];
        }
        // The site's 200 px minimum for the big buttons, only while it leaves the row room.
        float minPrimary = Math.min(200 * PX * 0.8F, available * 0.45F);
        for (int i = 0; i < all.size(); i++) {
            if ("primary".equals(all.get(i)[2]) && widths[i] < minPrimary && natural + (minPrimary - widths[i]) <= available) {
                natural += minPrimary - widths[i];
                widths[i] = minPrimary;
            }
        }
        if (natural > available) {
            float scale = (available - gap * Math.max(0, all.size())) / Math.max(1F, natural - gap * Math.max(0, all.size()));
            for (int i = 0; i < widths.length; i++) {
                widths[i] *= scale;
            }
        }
        float x = left;
        for (int i = 0; i < leftButtons.size(); i++) {
            button(all.get(i), x, by, widths[i], buttonH);
            x += widths[i] + gap;
        }
        x = right;
        for (int i = all.size() - 1; i >= leftButtons.size(); i--) {
            x -= widths[i];
            button(all.get(i), x, by, widths[i], buttonH);
            x -= gap;
        }
    }

    /** How a bar button is drawn: the step's one primary action, Install as a secondary, the rest quiet. */
    public static String buttonStyle(String id) {
        return switch (id) {
            case "bar.next", "bar.publish", "bar.open", "bar.edit" -> "primary";
            case "bar.install" -> "secondary";
            default -> "ghost";
        };
    }

    private void button(String[] spec, float x, float y, float w, float h) {
        String id = spec[0];
        TextStyle style = "primary".equals(spec[2]) ? TextStyle.STRONG : TextStyle.BODY;
        bar.add(id, "bar", Kind.BUTTON, new Rect(x, y, w, h));
        bar.labelCentered(id + ".text", id, x + w * 0.5F, y + (h - style.capHeight()) * 0.5F, Math.max(1, w - 6), spec[1], style);
        clicks.add(id);
    }
}

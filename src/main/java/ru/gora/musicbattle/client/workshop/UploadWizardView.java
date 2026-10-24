package ru.gora.musicbattle.client.workshop;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import ru.gora.musicbattle.MusicBattleMod;
import ru.gora.musicbattle.client.ui.CoverCache;
import ru.gora.musicbattle.client.ui.HandCursor;
import ru.gora.musicbattle.client.ui.LinkActions;
import ru.gora.musicbattle.client.ui.Motion;
import ru.gora.musicbattle.client.ui.Palette;
import ru.gora.musicbattle.client.ui.PreviewPlayer;
import ru.gora.musicbattle.client.ui.RoundedImage;
import ru.gora.musicbattle.client.ui.RoundedRect;
import ru.gora.musicbattle.client.ui.TextField;
import ru.gora.musicbattle.client.ui.TextStyle;
import ru.gora.musicbattle.client.ui.TextStyles;
import ru.gora.musicbattle.client.ui.UiClip;
import ru.gora.musicbattle.client.ui.UiIcon;
import ru.gora.musicbattle.client.workshop.layout.Filters;
import ru.gora.musicbattle.client.workshop.layout.Layout;
import ru.gora.musicbattle.client.workshop.layout.Rect;
import ru.gora.musicbattle.client.workshop.layout.UploadLayout;
import ru.gora.musicbattle.client.workshop.layout.WorkshopStrings;
import ru.gora.musicbattle.data.BattleNote;
import ru.gora.musicbattle.server.MusicBattleTracks;
import ru.gora.musicbattle.workshop.api.CancelToken;
import ru.gora.musicbattle.workshop.api.WorkshopApiException;
import ru.gora.musicbattle.workshop.api.WorkshopDtos;
import ru.gora.musicbattle.workshop.api.WorkshopUploader;

/**
 * The in-game upload wizard, drawn like the website's {@code /upload} page (spec 06 section 6.5): the step rail, then
 * "Before you upload" while the account still owes the acceptance, Files (which of the player's tracks become charts,
 * the audio and the cover), Details, Publish with the rights confirmation, and the stage rows with the result. All
 * geometry comes from the pure {@link UploadLayout}; this class keeps the player's work, draws the boxes, routes
 * input and runs the upload.
 */
public final class UploadWizardView implements WorkshopScreen.Overlay {
    private static final String K = WorkshopStrings.KEY + "wizard.";
    private static final String TRACKS_FOLDER = "music_battle_tracks/my_tracks";
    private static final List<String> TEXT_FIELDS = List.of("wizard.title", "wizard.artist", "wizard.source", "wizard.tags",
            "wizard.description", "wizard.credits", "wizard.changelog");
    /** The pages behind each summary card's link, as the site links them. */
    private static final Map<String, String> CARD_LINKS = Map.of("rights", "/rules/upload#rights", "marks", "/rules/upload#marks",
            "ai", "/rules/upload#marks", "takedown", "/legal/copyright#strikes", "reports", "/rules/moderation#ladder",
            "docs", "/rules/upload");
    /** The site's error and failure text colours, hsl(355 90% 76%) and hsl(355 90% 78%). */
    private static final int ERROR_TEXT = Palette.hsl(355, 90, 76);
    private static final int FAILURE_TEXT = Palette.hsl(355, 90, 78);

    private final WorkshopScreen screen;
    private final WorkshopClient client;
    private final Long updateSetId;
    private final List<MusicBattleTracks.Track> tracks = new ArrayList<>();
    private final Map<String, Boolean> selected = new LinkedHashMap<>();
    private final Map<String, String> difficulties = new LinkedHashMap<>();
    private final Map<String, String> keys = new LinkedHashMap<>();
    private final Set<String> ticked = new HashSet<>();
    private final Map<String, String> fields = new LinkedHashMap<>();
    /** Every text field the player has seen, by id; they live as long as the wizard so a step change loses nothing. */
    private final Map<String, TextField> inputs = new LinkedHashMap<>();
    private final Motion.SmoothScroll scroll = new Motion.SmoothScroll();

    private UploadLayout.Step step = UploadLayout.Step.RULES;
    private String audioMode = "upload";
    private String coverMode = "preview";
    private Path coverFile;
    private String target = "community";
    private WorkshopDtos.Rules rules = WorkshopDtos.Rules.UNKNOWN;
    private boolean acceptingRules;
    private long previewStartMs;
    private long durationMs = 60_000;
    private List<UploadLayout.Issue> issues = List.of();
    private Map<String, String> fieldErrors = Map.of();
    private String error;
    private String resultStatus;
    private Long resultSetId;
    private WorkshopUploader uploader;
    private WorkshopUploader.Snapshot snapshot;
    /** The wizard's work as it was when it opened, so leaving asks only about what the player changed since. */
    private String openedWith = "";
    private Rect area = Rect.EMPTY;
    private UploadLayout.Page page;
    private boolean draggingWindow;
    private boolean shownPlaying;

    public UploadWizardView(WorkshopScreen screen, WorkshopClient client, Long updateSetId) {
        this.screen = screen;
        this.client = client;
        this.updateSetId = updateSetId;
        fields.put("wizard.genre.id", "1");
        fields.put("wizard.language.id", "1");
        loadTracks();
        if (updateSetId != null) {
            step = UploadLayout.Step.FILES;
        }
        rebuild();
        openedWith = work();
        loadRules();
    }

    /** Opens the wizard with one local track already ticked ("Publish to Workshop" in the track library). */
    public void preselect(String trackId) {
        selected.put(trackId, true);
        MusicBattleTracks.Track track = track(trackId);
        if (track != null) {
            input("wizard.title").setValue(track.title());
            durationMs = track.durationTicks() * 50L;
        }
        step = firstStep();
        rebuild();
        openedWith = work();
    }

    /**
     * Whether leaving now throws away work. A finished upload loses nothing, so Escape over its result used to ask
     * "discard?" over a set that was already on the Workshop; a running one is cancelled by leaving, so it asks.
     * Otherwise the answer is whether the fields, the chosen tracks or the chosen cover differ from what the wizard
     * opened with: a track ticked by "Publish to Workshop" is not something the player would be sorry to lose.
     */
    @Override
    public boolean hasUnsavedText(boolean typed) {
        if (resultStatus != null || snapshot != null && snapshot.succeeded()) {
            return false;
        }
        if (uploader != null && snapshot != null && snapshot.running()) {
            return true;
        }
        return !work().equals(openedWith);
    }

    /** The player's work in one comparable line: non-blank fields, ticked tracks, chart names and keys, the cover. */
    private String work() {
        captureFields();
        java.util.TreeMap<String, String> parts = new java.util.TreeMap<>();
        fields.forEach((id, value) -> {
            if (value != null && !value.isBlank() && !id.endsWith(".id")) {
                parts.put("field:" + id, value.strip());
            }
        });
        selected.forEach((id, on) -> {
            if (Boolean.TRUE.equals(on)) {
                parts.put("track:" + id, "");
            }
        });
        // A chart name and key the player never touched are the track's own defaults, and a refreshed track list adds
        // those for every new track; only an edited one is work.
        difficulties.forEach((id, value) -> {
            MusicBattleTracks.Track track = track(id);
            if (track == null || !value.equals(track.title())) {
                parts.put("difficulty:" + id, value);
            }
        });
        keys.forEach((id, value) -> {
            if (!value.equals(safeKey(id))) {
                parts.put("key:" + id, value);
            }
        });
        parts.put("cover", coverFile == null ? "" : coverFile.toString());
        return parts.toString();
    }

    @Override
    public Component title() {
        return Component.translatable(WorkshopStrings.KEY + "title.upload");
    }

    @Override
    public void setArea(Rect value) {
        area = value;
        relayout();
    }

    @Override
    public void onClose() {
        if (uploader != null) {
            uploader.cancel();
        }
        PreviewPlayer.stop();
    }

    private void loadTracks() {
        tracks.clear();
        tracks.addAll(MusicBattleTracks.loadTracks());
        for (MusicBattleTracks.Track track : tracks) {
            difficulties.putIfAbsent(track.id(), track.title());
            keys.putIfAbsent(track.id(), safeKey(track.id()));
        }
    }

    private static String safeKey(String id) {
        String key = id.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_\\-]", "");
        return key.isEmpty() ? "chart" : key.substring(0, Math.min(32, key.length()));
    }

    private MusicBattleTracks.Track track(String id) {
        for (MusicBattleTracks.Track track : tracks) {
            if (track.id().equals(id)) {
                return track;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- the page model

    /** The text field with this id, created on first use with the site's limits. */
    private TextField input(String id) {
        return inputs.computeIfAbsent(id, key -> {
            boolean multi = key.equals("wizard.description") || key.equals("wizard.credits") || key.equals("wizard.changelog");
            TextField created = multi ? TextField.multiLine(5000) : TextField.singleLine();
            created.setMaxLength(multi ? 5000 : key.endsWith(".key") ? 32 : 200);
            if (key.equals("wizard.tags")) {
                created.setPlaceholder(WorkshopTr.INSTANCE.get(K + "tags.hint"));
            } else if (key.endsWith(".difficulty")) {
                created.setPlaceholder(WorkshopTr.INSTANCE.get(K + "difficulty.hint"));
            } else if (key.endsWith(".key")) {
                created.setPlaceholder(WorkshopTr.INSTANCE.get(K + "chart_key.hint"));
            }
            String start = initialValue(key);
            if (!start.isEmpty()) {
                created.setValue(start);
            }
            return created;
        });
    }

    private String initialValue(String id) {
        if (id.startsWith("wizard.track.")) {
            String[] parts = id.split("\\.");
            int index = parse(parts[2], -1);
            if (index >= 0 && index < tracks.size()) {
                String trackId = tracks.get(index).id();
                return id.endsWith(".key") ? keys.getOrDefault(trackId, safeKey(trackId))
                        : difficulties.getOrDefault(trackId, tracks.get(index).title());
            }
            return "";
        }
        return fields.getOrDefault(id, "");
    }

    private void rebuild() {
        relayout();
    }

    private void relayout() {
        if (area.empty()) {
            return;
        }
        float before = page == null ? 0F : page.content().contentHeight();
        page = UploadLayout.compute(area.w(), area.h(), TextStyles.measurer(), WorkshopTr.INSTANCE, state());
        Rect viewport = page.viewport();
        scroll.setExtent(Math.max(0, page.content().contentHeight() - viewport.h()));
        for (Layout.Box box : page.content().boxes()) {
            if (box.kind() == Layout.Kind.FIELD) {
                Rect rect = box.rect();
                input(box.id()).setBounds(area.x() + rect.x(), area.y() + rect.y(), rect.w(), rect.h());
            }
        }
        if (before != page.content().contentHeight()) {
            scroll.setExtent(Math.max(0, page.content().contentHeight() - viewport.h()));
        }
    }

    private UploadLayout.State state() {
        List<UploadLayout.Chart> charts = new ArrayList<>();
        for (MusicBattleTracks.Track track : tracks) {
            String meta = WorkshopTr.INSTANCE.get(K + "track.detail", track.id(),
                    WorkshopStrings.duration(track.durationTicks() * 50L), String.valueOf(track.notes().size()));
            charts.add(new UploadLayout.Chart(track.title(), meta, Boolean.TRUE.equals(selected.get(track.id()))));
        }
        Map<String, String> text = new LinkedHashMap<>();
        text.put("title", value("wizard.title").strip());
        text.put("artist", value("wizard.artist").strip());
        text.put("genre", WorkshopStrings.genre(WorkshopTr.INSTANCE, genreId()));
        text.put("language", WorkshopStrings.language(WorkshopTr.INSTANCE, languageId()));
        Path audio = audioFile();
        text.put("audio.file", audio == null ? "" : String.valueOf(audio.getFileName()));
        Path cover = coverPath();
        text.put("cover.name", cover == null ? "" : String.valueOf(cover.getFileName()));
        Map<String, Float> heights = new LinkedHashMap<>();
        for (String id : List.of("wizard.description", "wizard.credits", "wizard.changelog")) {
            TextField field = inputs.get(id);
            if (field != null && field.width() > 0) {
                heights.put(id, field.preferredHeight(0F, 160F));
            }
        }
        String terms = termsVersion();
        String rulesV = rulesVersion();
        String acceptedOn = rules.acceptedAt() == null ? ""
                : WorkshopStrings.relative(WorkshopTr.INSTANCE, rules.acceptedAt(), System.currentTimeMillis());
        Set<String> on = new HashSet<>(ticked);
        boolean running = uploader != null && snapshot != null && snapshot.running();
        return new UploadLayout.State(step, needsRules(), updateSetId != null, updateSetId == null ? "" : "#" + updateSetId, charts,
                TRACKS_FOLDER, audioMode, coverMode, cover != null, text, heights, on, target, previewStartMs, durationMs,
                PreviewPlayer.isCurrent("wizard_preview"), rules.needsReaccept() ? (rules.changes() == null ? "" : rules.changes()) : null,
                terms == null ? "?" : terms, rulesV == null ? "?" : rulesV, acceptedOn, stageRows(), error, resultStatus,
                running, acceptingRules || running, issues, fieldErrors, allAccepted() && !acceptingRules, canPublish());
    }

    /**
     * The document versions the acceptance is recorded against.
     *
     * These used to fall back to "1" when the site's meta could not be fetched - it is read once per session and a
     * failure is never retried - and the server then refused the acceptance as outdated, with nothing the player
     * could do about it. The account's own state carries the versions it was told about, which is a better answer,
     * and when neither is known the acceptance is not sent at all rather than sent wrong.
     */
    private String termsVersion() {
        if (rules != null && rules != WorkshopDtos.Rules.UNKNOWN && rules.termsVersion() != null
                && !rules.termsVersion().isBlank()) {
            return rules.termsVersion();
        }
        var meta = client.meta();
        return meta == null ? null : meta.termsVersion();
    }

    /** The Upload Rules version: `/meta`'s `rulesVersion` is the Community Rules, which the acceptance also names. */
    private String rulesVersion() {
        if (rules != null && rules != WorkshopDtos.Rules.UNKNOWN && rules.rulesVersion() != null
                && !rules.rulesVersion().isBlank()) {
            return rules.rulesVersion();
        }
        var meta = client.meta();
        return meta == null ? null : meta.uploadRulesVersion();
    }

    /** The acceptance step is shown only while this account still owes one (owner request: never ask twice). */
    private UploadLayout.Step firstStep() {
        return rules.accepted() ? UploadLayout.Step.FILES : UploadLayout.Step.RULES;
    }

    /** The rail lists "Before you upload" while it is owed or being shown, exactly as the site builds its steps. */
    private boolean needsRules() {
        return !rules.accepted() || step == UploadLayout.Step.RULES;
    }

    private boolean allAccepted() {
        for (String key : UploadLayout.ACCEPTANCE) {
            if (!ticked.contains("accept." + key)) {
                return false;
            }
        }
        return true;
    }

    /** Reads the acceptance state of the signed-in account, so a person who accepted never sees the panel again. */
    private void loadRules() {
        if (!client.session().signedIn() || !client.networkAllowed()) {
            return;
        }
        screen.run(() -> {
            try {
                WorkshopDtos.Me me = client.session().authorized(api -> api.me(CancelToken.none()));
                WorkshopDtos.Rules loaded = me.rules() == null ? WorkshopDtos.Rules.UNKNOWN : me.rules();
                // The same answer carries the lighter acceptance; the comment composer reads it from here.
                client.rememberParticipation(me.participation());
                screen.later(() -> {
                    rules = loaded;
                    // An update is an upload too: a person who still owes the acceptance is asked here as well.
                    if (step == UploadLayout.Step.RULES && loaded.accepted()) {
                        go(UploadLayout.Step.FILES);
                    } else if (step == UploadLayout.Step.FILES && !loaded.accepted()) {
                        go(UploadLayout.Step.RULES);
                    }
                    rebuild();
                });
            } catch (WorkshopApiException exception) {
                MusicBattleMod.LOGGER.debug("Workshop rules state unavailable: {}", exception.code());
            }
        });
    }

    /**
     * Records the acceptance and only then opens the rest of the wizard: the server refuses a submit until the account
     * has accepted, so letting the player through first would only fail later with a map already prepared.
     */
    private void acceptRules() {
        if (acceptingRules || !allAccepted()) {
            issues = List.of(new UploadLayout.Issue(WorkshopTr.INSTANCE.get(K + "accept.error"), false));
            rebuild();
            return;
        }
        String terms = termsVersion();
        String rulesV = rulesVersion();
        if (terms == null || rulesV == null) {
            // Without the versions there is nothing honest to record the acceptance against, and guessing "1" was
            // refused as outdated every time with no way forward. Say so, and try the site's meta again.
            issues = List.of(new UploadLayout.Issue(WorkshopTr.INSTANCE.get(K + "problem.versions_unknown"), false));
            rebuild();
            client.refreshMeta();
            return;
        }
        acceptingRules = true;
        issues = List.of();
        rebuild();
        screen.run(() -> {
            try {
                WorkshopDtos.Rules state = client.session()
                        .authorized(api -> api.acceptRules("upload", terms, rulesV, UploadLayout.ACCEPTANCE, CancelToken.none()));
                // These two boxes cover the lighter acceptance too, so the cached answer is stale: drop it and let the
                // next screen that cares read the truth from /me instead of offering an acceptance already given.
                client.forgetParticipation();
                screen.later(() -> {
                    acceptingRules = false;
                    rules = state;
                    go(UploadLayout.Step.FILES);
                    rebuild();
                });
            } catch (WorkshopApiException exception) {
                screen.later(() -> {
                    acceptingRules = false;
                    issues = List.of(new UploadLayout.Issue(WorkshopText.error(exception.code()).getString(), false));
                    rebuild();
                });
            }
        });
    }

    private int genreId() {
        return parse(fields.getOrDefault("wizard.genre.id", "1"), 1);
    }

    private int languageId() {
        return parse(fields.getOrDefault("wizard.language.id", "1"), 1);
    }

    private static int parse(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private List<UploadLayout.Stage> stageRows() {
        if (snapshot == null) {
            return List.of();
        }
        List<UploadLayout.Stage> rows = new ArrayList<>();
        for (WorkshopUploader.Stage stage : WorkshopUploader.Stage.values()) {
            UploadLayout.StageState state = switch (snapshot.state(stage)) {
                case DONE -> UploadLayout.StageState.DONE;
                case RUNNING -> UploadLayout.StageState.RUNNING;
                case FAILED -> UploadLayout.StageState.FAILED;
                case CANCELLED -> UploadLayout.StageState.CANCELLED;
                default -> UploadLayout.StageState.PENDING;
            };
            List<String> detail = new ArrayList<>();
            float fraction = -1;
            if (stage == WorkshopUploader.Stage.UPLOADING && state == UploadLayout.StageState.RUNNING) {
                fraction = snapshot.uploadFraction();
                for (WorkshopUploader.SlotProgress slot : snapshot.slots()) {
                    detail.add(slot.fileName() + "  " + WorkshopStrings.size(slot.sent()) + " / "
                            + WorkshopStrings.size(Math.max(0, slot.total())));
                }
            }
            String key = stage.name().toLowerCase(Locale.ROOT);
            rows.add(new UploadLayout.Stage(WorkshopTr.INSTANCE.get(K + "stage." + key), state, detail, fraction));
        }
        return rows;
    }

    private boolean canPublish() {
        return ticked.contains("confirm.rights") && !selectedTracks().isEmpty() && !value("wizard.title").isBlank()
                && !value("wizard.artist").isBlank();
    }

    private String value(String id) {
        TextField field = inputs.get(id);
        return field != null ? field.value() : fields.getOrDefault(id, "");
    }

    private List<MusicBattleTracks.Track> selectedTracks() {
        List<MusicBattleTracks.Track> out = new ArrayList<>();
        for (MusicBattleTracks.Track track : tracks) {
            if (Boolean.TRUE.equals(selected.get(track.id()))) {
                out.add(track);
            }
        }
        return out;
    }

    /** The song that goes up with the map: the first chosen track's audio, as the upload has always taken it. */
    private Path audioFile() {
        return selectedTracks().stream().map(MusicBattleTracks::audioPath).filter(Optional::isPresent).map(Optional::get)
                .findFirst().orElse(null);
    }

    /** The image that becomes the cover: a chosen file, else the first chosen track's preview image. */
    private Path coverPath() {
        if ("none".equals(coverMode)) {
            return null;
        }
        if ("file".equals(coverMode) && coverFile != null) {
            return coverFile;
        }
        Path preview = selectedTracks().stream().map(MusicBattleTracks::previewPath).filter(Optional::isPresent)
                .map(Optional::get).findFirst().orElse(null);
        return preview != null && Files.isRegularFile(preview, LinkOption.NOFOLLOW_LINKS) ? preview : null;
    }

    private void go(UploadLayout.Step next) {
        captureFields();
        step = next;
        scroll.scrollTo(0, false);
    }

    // ---------------------------------------------------------------- rendering

    @Override
    public void render(GuiGraphics g, double mouseX, double mouseY, float partialTick) {
        if (uploader != null) {
            WorkshopUploader.Snapshot latest = uploader.snapshot();
            if (snapshot == null || latest.running() != snapshot.running() || latest.uploadFraction() != snapshot.uploadFraction()
                    || !latest.stages().equals(snapshot.stages()) || latest.succeeded() != snapshot.succeeded()) {
                snapshot = latest;
                onSnapshot(latest);
            }
        }
        boolean playing = PreviewPlayer.isCurrent("wizard_preview");
        if (page == null || playing != shownPlaying) {
            // The Play button turns into Stop and back when the 30 seconds end on their own.
            shownPlaying = playing;
            relayout();
            if (page == null) {
                return;
            }
        }
        scroll.update();
        float offset = scroll.snapped(TextStyles.guiScale());
        Rect viewport = screenRect(page.viewport(), 0);
        UiClip.push(g, viewport.x(), viewport.y(), viewport.right(), viewport.bottom());
        for (Layout.Box box : page.content().boxes()) {
            Rect rect = screenRect(box.rect(), offset);
            if (rect.bottom() < viewport.y() || rect.y() > viewport.bottom()) {
                continue;
            }
            drawBox(g, box, rect, viewport.contains(mouseX, mouseY) ? mouseX : -1, mouseY);
        }
        UiClip.pop(g);
        WorkshopDraw.scrollbar(g, viewport, offset, page.content().contentHeight(), false);
        // Text fields come after the pass, so their caret sits above the cards they are on.
        for (Layout.Box box : page.content().boxes()) {
            if (box.kind() != Layout.Kind.FIELD) {
                continue;
            }
            Rect rect = screenRect(box.rect(), offset);
            TextField field = input(box.id());
            field.setBounds(rect.x(), rect.y(), rect.w(), rect.h());
            if (rect.bottom() > viewport.y() && rect.y() < viewport.bottom()) {
                UiClip.push(g, viewport.x(), viewport.y(), viewport.right(), viewport.bottom());
                field.render(g, mouseX, mouseY);
                if (fieldErrors.containsKey(box.id().substring("wizard.".length())) && !field.focused()) {
                    RoundedRect.stroke(g, rect.x(), rect.y(), rect.w(), rect.h(), Palette.RADIUS_SM, 1F, Palette.DANGER);
                }
                UiClip.pop(g);
            }
        }
        drawBar(g, mouseX, mouseY);
    }

    private Rect screenRect(Rect rect, float offset) {
        return rect.empty() ? rect : new Rect(area.x() + rect.x(), area.y() + rect.y() - offset, rect.w(), rect.h());
    }

    private void drawBox(GuiGraphics g, Layout.Box box, Rect rect, double mouseX, double mouseY) {
        String id = box.id();
        if (box.kind() == Layout.Kind.TEXT) {
            text(g, box, rect, textColour(id, mouseX, mouseY));
            return;
        }
        if (box.kind() == Layout.Kind.FIELD) {
            return;
        }
        boolean hover = rect.contains(mouseX, mouseY) && page.clicks().contains(id);
        if (hover) {
            HandCursor.request(HandCursor.Kind.HAND);
        }
        if (id.equals("panel")) {
            RoundedRect.shadow(g, rect.x(), rect.y() + 4, rect.w(), rect.h(), Palette.RADIUS_LG, 12F, Palette.SHADOW_LIFTED);
            RoundedRect.fill(g, rect.x(), rect.y(), rect.w(), rect.h(), Palette.RADIUS_LG, Palette.BG_1);
        } else if (id.startsWith("rail.")) {
            drawRail(g, id, rect);
        } else if (id.startsWith("card.")) {
            if (id.endsWith(".link.icon")) {
                WorkshopDraw.icon(g, rect, UiIcon.EXTERNAL_LINK, Palette.LINK);
            } else if (id.endsWith(".icon")) {
                RoundedRect.fill(g, rect.x(), rect.y(), rect.w(), rect.h(), Palette.RADIUS_MD, Palette.hsla(265, 62, 56, 0.16));
                WorkshopDraw.icon(g, rect.inset(rect.w() * 0.22F, rect.h() * 0.22F), cardIcon(id), Palette.ACCENT);
            } else {
                RoundedRect.fill(g, rect.x(), rect.y(), rect.w(), rect.h(), Palette.RADIUS_LG, Palette.BG_2);
                RoundedRect.stroke(g, rect.x(), rect.y(), rect.w(), rect.h(), Palette.RADIUS_LG, 0.8F, Palette.hsla(265, 10, 32, 0.5));
            }
        } else if (id.startsWith("accept.") || id.startsWith("flag.") || id.equals("confirm.rights") || id.startsWith("confirm.rights.")) {
            String owner = id.endsWith(".box") ? id.substring(0, id.length() - 4) : id;
            if (id.endsWith(".box")) {
                checkbox(g, rect, ticked.contains(owner));
            } else {
                boolean on = ticked.contains(id);
                RoundedRect.fill(g, rect.x(), rect.y(), rect.w(), rect.h(), Palette.RADIUS_MD, hover ? Palette.BG_3 : Palette.BG_2);
                if (on && id.startsWith("accept.")) {
                    RoundedRect.stroke(g, rect.x(), rect.y(), rect.w(), rect.h(), Palette.RADIUS_MD, 0.8F, Palette.hsla(265, 62, 56, 0.45));
                }
            }
        } else if (id.startsWith("chart.")) {
            int index = parse(id.split("\\.")[1], -1);
            boolean on = index >= 0 && index < tracks.size() && Boolean.TRUE.equals(selected.get(tracks.get(index).id()));
            if (id.endsWith(".stripe")) {
                RoundedRect.fill(g, rect.x(), rect.y(), rect.w(), rect.h(), 1F, on ? Palette.OK : Palette.BG_4);
            } else if (id.endsWith(".box")) {
                checkbox(g, rect, on);
            } else {
                RoundedRect.fill(g, rect.x(), rect.y(), rect.w(), rect.h(), Palette.RADIUS_MD, hover ? Palette.BG_3 : Palette.BG_2);
            }
        } else if (id.startsWith("choice.")) {
            boolean on = choiceSelected(id.endsWith(".radio") ? id.substring(0, id.length() - 6) : id);
            if (id.endsWith(".radio")) {
                RoundedRect.circle(g, rect.centerX(), rect.centerY(), rect.w() * 0.5F, Palette.BG_0);
                RoundedRect.stroke(g, rect.x(), rect.y(), rect.w(), rect.h(), rect.w() * 0.5F, 1.2F, on ? Palette.ACCENT_STRONG : Palette.LINE);
                if (on) {
                    RoundedRect.circle(g, rect.centerX(), rect.centerY(), rect.w() * 0.25F, Palette.ACCENT);
                }
            } else {
                RoundedRect.fill(g, rect.x(), rect.y(), rect.w(), rect.h(), Palette.RADIUS_MD, on || hover ? Palette.BG_3 : Palette.BG_2);
                if (on) {
                    RoundedRect.stroke(g, rect.x(), rect.y(), rect.w(), rect.h(), Palette.RADIUS_MD, 0.8F, Palette.ACCENT_STRONG);
                }
            }
        } else if (id.equals("audio.panel.icon")) {
            WorkshopDraw.icon(g, rect, UiIcon.MUSIC, Palette.ACCENT);
        } else if (id.equals("cover.panel.image")) {
            ResourceLocation texture = CoverCache.getFile(coverPath());
            RoundedRect.fill(g, rect.x(), rect.y(), rect.w(), rect.h(), Palette.RADIUS_MD, Palette.BG_3);
            if (texture != null) {
                RoundedImage.draw(g, texture, rect.x(), rect.y(), rect.w(), rect.h(), 0F, 0.5F - rect.h() / rect.w() * 0.5F, 1F,
                        0.5F + rect.h() / rect.w() * 0.5F, Palette.RADIUS_MD, Palette.RADIUS_MD, Palette.RADIUS_MD, Palette.RADIUS_MD,
                        0F, 0xFFFFFFFF, 0xFFFFFFFF);
            }
        } else if (id.equals("cover.panel.choose") || id.equals("pp.play")) {
            RoundedRect.fill(g, rect.x(), rect.y(), rect.w(), rect.h(), Palette.RADIUS_SM, hover ? Palette.BG_4 : Palette.BG_3);
            if (id.equals("pp.play")) {
                float size = rect.h() * 0.5F;
                if (PreviewPlayer.isCurrent("wizard_preview")) {
                    WorkshopDraw.stopSquare(g, rect.x() + 4 + size * 0.5F, rect.centerY(), size * 0.8F, Palette.TEXT_1);
                } else {
                    WorkshopDraw.icon(g, new Rect(rect.x() + 4, rect.centerY() - size * 0.5F, size, size), UiIcon.PLAY, Palette.TEXT_1);
                }
            }
        } else if (id.endsWith(".panel") || id.equals("pp.strip")) {
            RoundedRect.fill(g, rect.x(), rect.y(), rect.w(), rect.h(), Palette.RADIUS_MD, Palette.BG_2);
            if (id.equals("pp.strip")) {
                RoundedRect.stroke(g, rect.x(), rect.y(), rect.w(), rect.h(), Palette.RADIUS_MD, 0.8F, Palette.BG_3);
                drawDensity(g, rect);
            }
        } else if (id.equals("pp.window")) {
            RoundedRect.fill(g, rect.x(), rect.y(), rect.w(), rect.h(), Palette.RADIUS_SM, Palette.hsla(265, 100, 78, 0.12));
            RoundedRect.stroke(g, rect.x(), rect.y(), rect.w(), rect.h(), Palette.RADIUS_SM, 1.4F, Palette.ACCENT);
            float handle = Math.min(17F, rect.h() * 0.5F);
            RoundedRect.pill(g, rect.x() - 1.4F, rect.centerY() - handle * 0.5F, 2.8F, handle, Palette.ACCENT);
            RoundedRect.pill(g, rect.right() - 1.4F, rect.centerY() - handle * 0.5F, 2.8F, handle, Palette.ACCENT);
        } else if (id.startsWith("select.")) {
            if (id.endsWith(".prev") || id.endsWith(".next")) {
                WorkshopDraw.icon(g, rect.inset(rect.w() * 0.3F, rect.h() * 0.3F),
                        id.endsWith(".prev") ? UiIcon.CHEVRON_LEFT : UiIcon.CHEVRON_RIGHT, hover ? Palette.TEXT_1 : Palette.TEXT_3);
            } else {
                RoundedRect.fill(g, rect.x(), rect.y(), rect.w(), rect.h(), Palette.RADIUS_SM, Palette.BG_3);
            }
        } else if (id.startsWith("files.line.")) {
            RoundedRect.fill(g, rect.x(), rect.y(), rect.w(), rect.h(), 0F, Palette.BG_3);
        } else if (id.equals("hp") || id.equals("result")) {
            drawHeaderPreview(g, rect);
        } else if (id.endsWith(".pill")) {
            RoundedRect.pill(g, rect.x(), rect.y(), rect.w(), rect.h(), WorkshopDraw.statusColour(previewStatus()));
        } else if (id.contains(".disc.")) {
            RoundedRect.circle(g, rect.centerX(), rect.centerY(), rect.w() * 0.5F, Palette.ST_FEATURED);
        } else if (id.equals("accepted.icon")) {
            WorkshopDraw.icon(g, rect, UiIcon.CHECK, Palette.OK);
        } else if (id.startsWith("stage.")) {
            drawStage(g, id, rect);
        } else if (id.equals("failure")) {
            RoundedRect.fill(g, rect.x(), rect.y(), rect.w(), rect.h(), Palette.RADIUS_MD, Palette.hsla(355, 85, 60, 0.1));
        } else if (id.startsWith("issue.") || id.startsWith("rules.changed")) {
            boolean warning = id.startsWith("rules.changed") || issueWarning(id);
            int tone = warning ? Palette.WARN : ERROR_TEXT;
            if (id.endsWith(".icon")) {
                WorkshopDraw.icon(g, rect, warning ? UiIcon.ALERT : UiIcon.X, tone);
            } else {
                RoundedRect.fill(g, rect.x(), rect.y(), rect.w(), rect.h(), Palette.RADIUS_SM,
                        warning ? Palette.hsla(40, 100, 58, 0.1) : Palette.hsla(355, 85, 60, 0.12));
            }
        }
    }

    private void drawRail(GuiGraphics g, String id, Rect rect) {
        String[] parts = id.split("\\.");
        UploadLayout.Step railStep = UploadLayout.Step.valueOf(parts[1].toUpperCase(Locale.ROOT));
        boolean current = railStep == step;
        boolean done = step == UploadLayout.Step.PROGRESS || railStep.ordinal() < step.ordinal();
        if (parts.length == 2) {
            RoundedRect.pill(g, rect.x(), rect.y(), rect.w(), rect.h(), current ? Palette.BG_3 : Palette.BG_2);
            if (current) {
                RoundedRect.stroke(g, rect.x(), rect.y(), rect.w(), rect.h(), rect.h() * 0.5F, 0.8F, Palette.ACCENT_STRONG);
            }
        } else if (parts[2].equals("num")) {
            RoundedRect.circle(g, rect.centerX(), rect.centerY(), rect.w() * 0.5F,
                    current ? Palette.ACCENT_STRONG : done ? Palette.OK : Palette.BG_4);
        }
    }

    private int railTextColour(String id) {
        String[] parts = id.split("\\.");
        UploadLayout.Step railStep = UploadLayout.Step.valueOf(parts[1].toUpperCase(Locale.ROOT));
        boolean current = railStep == step;
        boolean done = step == UploadLayout.Step.PROGRESS || railStep.ordinal() < step.ordinal();
        if (parts[2].equals("digit")) {
            return current ? Palette.TEXT_1 : done ? Palette.ON_PILL : Palette.TEXT_2;
        }
        return current ? Palette.TEXT_1 : done ? Palette.TEXT_2 : Palette.TEXT_3;
    }

    private static UiIcon cardIcon(String id) {
        return switch (id.split("\\.")[1]) {
            case "rights" -> UiIcon.CHECK;
            case "marks" -> UiIcon.EYE_OFF;
            case "ai" -> UiIcon.STAR;
            case "takedown" -> UiIcon.ALERT;
            case "reports" -> UiIcon.FLAG;
            default -> UiIcon.FOLDER_OPEN;
        };
    }

    /** The site's styled checkbox: a dark well with a 2 px line, filled with the accent and a tick when on. */
    private static void checkbox(GuiGraphics g, Rect rect, boolean on) {
        float radius = rect.w() * 0.3F;
        RoundedRect.fill(g, rect.x(), rect.y(), rect.w(), rect.h(), radius, on ? Palette.ACCENT_STRONG : Palette.BG_0);
        RoundedRect.stroke(g, rect.x(), rect.y(), rect.w(), rect.h(), radius, 1.4F, on ? Palette.ACCENT_STRONG : Palette.LINE);
        if (on) {
            WorkshopDraw.icon(g, rect.inset(1.6F, 1.6F), UiIcon.CHECK, Palette.TEXT_1);
        }
    }

    /**
     * The site draws the song's waveform here; the game has no decoded audio at hand, so the strip shows where the
     * chart's notes fall instead, which says the same thing to a mapper: where the busy part is.
     */
    private void drawDensity(GuiGraphics g, Rect rect) {
        List<MusicBattleTracks.Track> chosen = selectedTracks();
        if (chosen.isEmpty() || durationMs <= 0) {
            return;
        }
        int buckets = Math.max(8, (int) (rect.w() / 3F));
        int[] counts = new int[buckets];
        int max = 1;
        for (BattleNote note : chosen.get(0).notes()) {
            int bucket = (int) Math.min(buckets - 1, Math.max(0, note.tick() * 50L * buckets / durationMs));
            max = Math.max(max, ++counts[bucket]);
        }
        float step = rect.w() / buckets;
        for (int i = 0; i < buckets; i++) {
            if (counts[i] == 0) {
                continue;
            }
            float h = Math.max(1.5F, (rect.h() - 8) * counts[i] / max);
            RoundedRect.fill(g, rect.x() + i * step + step * 0.25F, rect.centerY() - h * 0.5F, Math.max(1F, step * 0.5F), h, 0.6F,
                    Palette.withAlpha(Palette.ACCENT, 0.55F));
        }
    }

    private void drawHeaderPreview(GuiGraphics g, Rect rect) {
        RoundedRect.fill(g, rect.x(), rect.y(), rect.w(), rect.h(), Palette.RADIUS_LG, Palette.BG_3);
        ResourceLocation texture = CoverCache.getFile(coverPath());
        if (texture != null) {
            float v = Math.min(0.5F, rect.h() / rect.w() * 0.5F * (16F / 9F));
            // The site darkens the cover with a gradient so the title reads over any picture; the image shader has no
            // vertical gradient, so the whole cover is dimmed to the gradient's middle instead.
            RoundedImage.draw(g, texture, rect.x(), rect.y(), rect.w(), rect.h(), 0F, 0.5F - v, 1F, 0.5F + v,
                    Palette.RADIUS_LG, Palette.RADIUS_LG, Palette.RADIUS_LG, Palette.RADIUS_LG, 0.62F, 0xFFFFFFFF, 0xFFFFFFFF);
        } else {
            // The site's placeholder cover: the accent washed over the card.
            RoundedRect.fill(g, rect.x(), rect.y(), rect.w(), rect.h(), Palette.RADIUS_LG, Palette.hsla(265, 55, 40, 0.28));
        }
    }

    private void drawStage(GuiGraphics g, String id, Rect rect) {
        String[] parts = id.split("\\.");
        int index = parse(parts[1], -1);
        List<UploadLayout.Stage> stages = stageRows();
        if (index < 0 || index >= stages.size()) {
            return;
        }
        UploadLayout.Stage stage = stages.get(index);
        if (parts.length == 2) {
            boolean active = stage.state() == UploadLayout.StageState.RUNNING;
            RoundedRect.fill(g, rect.x(), rect.y(), rect.w(), rect.h(), Palette.RADIUS_MD, active ? Palette.BG_3 : Palette.BG_2);
            if (stage.state() == UploadLayout.StageState.FAILED) {
                RoundedRect.fill(g, rect.x(), rect.y(), 2F, rect.h(), 1F, Palette.DANGER);
            }
        } else if (parts[2].equals("icon")) {
            switch (stage.state()) {
                case RUNNING -> WorkshopDraw.spinner(g, rect.centerX(), rect.centerY(), rect.w() * 0.5F, Palette.ST_WIP);
                case DONE -> WorkshopDraw.icon(g, rect, UiIcon.CHECK, Palette.OK);
                case FAILED -> WorkshopDraw.icon(g, rect, UiIcon.X, Palette.DANGER);
                case CANCELLED -> WorkshopDraw.icon(g, rect, UiIcon.X, Palette.TEXT_MUTE);
                default -> {
                    // A stage that has not started shows nothing, as on the site.
                }
            }
        } else if (parts[2].equals("bar")) {
            WorkshopDraw.bar(g, rect, stage.fraction(), Palette.BG_4, Palette.ACCENT);
        }
    }

    private boolean issueWarning(String id) {
        int index = parse(id.split("\\.")[1], -1);
        return index >= 0 && index < issues.size() && issues.get(index).warning();
    }

    private String previewStatus() {
        return step == UploadLayout.Step.PROGRESS && resultStatus != null ? resultStatus : target;
    }

    private boolean choiceSelected(String id) {
        return switch (id) {
            case "choice.audio.upload" -> "upload".equals(audioMode);
            case "choice.audio.external" -> "external".equals(audioMode);
            case "choice.cover.upload" -> !"none".equals(coverMode);
            case "choice.cover.none" -> "none".equals(coverMode);
            case "choice.target.community" -> "community".equals(target);
            case "choice.target.wip" -> "wip".equals(target);
            default -> false;
        };
    }

    private int textColour(String id, double mouseX, double mouseY) {
        if (id.startsWith("rail.")) {
            return railTextColour(id);
        }
        if (id.equals("head.eyebrow")) {
            return Palette.ACCENT;
        }
        if (id.contains(".error.") || id.startsWith("audio.panel.missing") || id.startsWith("cover.panel.missing")) {
            return Palette.DANGER;
        }
        if (id.startsWith("wizard.") && id.endsWith(".label")) {
            return Palette.TEXT_2;
        }
        if (id.endsWith(".link") || id.endsWith(".read_terms") || id.endsWith(".read_rules") || id.equals("accepted.view")) {
            return Palette.LINK;
        }
        if (id.startsWith("step.title") || id.startsWith("head.title") || id.equals("rules.legend") || id.equals("files.charts")
                || id.equals("files.audio") || id.equals("files.cover") || id.endsWith(".value") || id.contains(".title")
                || id.contains("choice.") && id.contains(".label") || id.startsWith("flag.") && id.contains(".label")
                || id.startsWith("confirm.rights.label") || id.equals("audio.panel.name") || id.endsWith(".text") && (id.startsWith("cover.panel")
                || id.startsWith("pp.play"))) {
            return Palette.TEXT_1;
        }
        if (id.startsWith("accept.") && id.contains(".label")) {
            return ticked.contains(id.substring(0, id.indexOf(".label"))) ? Palette.TEXT_1 : Palette.TEXT_2;
        }
        if (id.startsWith("rules.changed")) {
            return Palette.WARN;
        }
        if (id.startsWith("issue.")) {
            return issueWarning(id) ? Palette.WARN : ERROR_TEXT;
        }
        if (id.startsWith("failure.")) {
            return FAILURE_TEXT;
        }
        if (id.endsWith(".pill.text")) {
            return Palette.ON_PILL;
        }
        if (id.startsWith("stage.")) {
            if (id.contains(".detail.")) {
                return Palette.TEXT_MUTE;
            }
            int index = parse(id.split("\\.")[1], -1);
            List<UploadLayout.Stage> stages = stageRows();
            UploadLayout.StageState state = index >= 0 && index < stages.size() ? stages.get(index).state() : null;
            return state == UploadLayout.StageState.RUNNING || state == UploadLayout.StageState.FAILED ? Palette.TEXT_1
                    : state == UploadLayout.StageState.DONE ? Palette.TEXT_2 : Palette.TEXT_3;
        }
        if (id.contains(".hint") || id.startsWith("files.where") || id.startsWith("files.empty") || id.equals("files.count")
                || id.endsWith(".meta") || id.contains(".from.") || id.equals("cover.panel.name") || id.equals("accepted.text")
                || id.startsWith("chart.") && id.endsWith(".label")) {
            return id.contains(".hint") && !id.startsWith("choice.") && !id.startsWith("flag.") ? Palette.TEXT_MUTE : Palette.TEXT_3;
        }
        if (id.equals("hp.artist") || id.equals("result.artist")) {
            return Palette.TEXT_2;
        }
        return Palette.TEXT_2;
    }

    private static void text(GuiGraphics g, Layout.Box box, Rect rect, int colour) {
        if (box.text() == null || box.text().isEmpty()) {
            return;
        }
        TextStyle style = box.style() == null ? TextStyle.BODY : box.style();
        TextStyles.queue(g, TextStyles.text(box.text(), style).getVisualOrderText(), rect.x(), rect.y(), colour);
    }

    private void drawBar(GuiGraphics g, double mouseX, double mouseY) {
        for (Layout.Box box : page.bar().boxes()) {
            Rect rect = screenRect(box.rect(), 0);
            if (box.id().equals("bar")) {
                RoundedRect.fill(g, rect.x(), rect.y(), rect.w(), rect.h(), 0F, Palette.BG_1);
                RoundedRect.fill(g, rect.x(), rect.y(), rect.w(), 1F, 0F, Palette.BG_3);
                continue;
            }
            String id = box.id().endsWith(".text") ? box.id().substring(0, box.id().length() - 5) : box.id();
            boolean enabled = barEnabled(id);
            String style = UploadLayout.buttonStyle(id);
            Rect button = screenRect(page.bar().rect(id), 0);
            boolean hover = enabled && button.contains(mouseX, mouseY);
            if (box.kind() == Layout.Kind.TEXT) {
                int colour = "ghost".equals(style) && !hover ? Palette.TEXT_2 : Palette.TEXT_1;
                text(g, box, rect, enabled ? colour : Palette.fade(colour, 0.5F));
                continue;
            }
            if (hover) {
                HandCursor.request(HandCursor.Kind.HAND);
            }
            switch (style) {
                case "primary" -> {
                    int fill = hover ? Palette.hsl(265, 62, 62) : Palette.ACCENT_STRONG;
                    RoundedRect.fill(g, rect.x(), rect.y(), rect.w(), rect.h(), Palette.RADIUS_MD, enabled ? fill : Palette.fade(fill, 0.5F));
                }
                case "secondary" -> RoundedRect.fill(g, rect.x(), rect.y(), rect.w(), rect.h(), Palette.RADIUS_MD,
                        hover ? Palette.BG_4 : Palette.BG_3);
                default -> RoundedRect.stroke(g, rect.x(), rect.y(), rect.w(), rect.h(), Palette.RADIUS_MD, 0.8F,
                        hover ? Palette.ACCENT : Palette.LINE);
            }
        }
    }

    private boolean barEnabled(String id) {
        return switch (id) {
            case "bar.next" -> step != UploadLayout.Step.RULES || allAccepted() && !acceptingRules;
            case "bar.publish" -> canPublish() && uploader == null;
            default -> true;
        };
    }

    private void onSnapshot(WorkshopUploader.Snapshot latest) {
        boolean ended = error == null && resultStatus == null;
        if (latest.errorCode() != null) {
            error = WorkshopText.error(latest.errorCode()).getString();
            String field = latest.errorField();
            if (field != null && TEXT_FIELDS.contains("wizard." + field)) {
                fieldErrors = Map.of(field, error);
            }
        }
        if (latest.succeeded() && latest.result() != null) {
            resultSetId = latest.result().setId();
            String status = latest.result().status();
            resultStatus = status == null || status.isBlank() ? target : status.toLowerCase(Locale.ROOT);
        }
        rebuild();
        if (ended && (error != null || resultStatus != null)) {
            // The failure and the result sit under the stage rows; on a short window they would start off screen.
            scroll.scrollTo(scroll.extent(), true);
        }
    }

    // ---------------------------------------------------------------- actions

    private void onAction(String id, double mouseX) {
        screen.click();
        if (id.startsWith("card.") && id.endsWith(".link")) {
            String path = CARD_LINKS.getOrDefault(id.split("\\.")[1], "/rules/upload");
            LinkActions.openTrusted(screen, client.api().pageUrl(path));
            return;
        }
        if (id.endsWith(".read_terms")) {
            LinkActions.openTrusted(screen, client.api().pageUrl("/legal/terms"));
            return;
        }
        if (id.endsWith(".read_rules") || id.equals("accepted.view")) {
            LinkActions.openTrusted(screen, client.api().pageUrl("/rules/upload"));
            return;
        }
        if (id.startsWith("accept.") || id.startsWith("flag.") || id.equals("confirm.rights")) {
            if (!ticked.remove(id)) {
                ticked.add(id);
            }
            if (id.startsWith("accept.")) {
                issues = List.of();
            }
            rebuild();
            return;
        }
        if (id.startsWith("chart.")) {
            int index = parse(id.substring("chart.".length()), -1);
            if (index >= 0 && index < tracks.size()) {
                MusicBattleTracks.Track track = tracks.get(index);
                selected.put(track.id(), !Boolean.TRUE.equals(selected.get(track.id())));
                if (value("wizard.title").isBlank()) {
                    input("wizard.title").setValue(track.title());
                }
                durationMs = Math.max(durationMs, track.durationTicks() * 50L);
                issues = List.of();
                rebuild();
            }
            return;
        }
        switch (id) {
            case "bar.next" -> next();
            case "bar.back" -> back();
            case "choice.audio.upload", "choice.audio.external" -> {
                audioMode = id.endsWith("external") ? "external" : "upload";
                rebuild();
            }
            case "choice.cover.upload" -> {
                coverMode = coverFile != null ? "file" : "preview";
                rebuild();
            }
            case "choice.cover.none" -> {
                coverMode = "none";
                rebuild();
            }
            case "cover.panel.choose" -> {
                Path picked = WorkshopFiles.chooseImage();
                if (picked != null) {
                    coverFile = picked;
                    coverMode = "file";
                }
                rebuild();
            }
            case "select.genre.prev", "select.genre.next" -> {
                fields.put("wizard.genre.id", String.valueOf(cycle(genreId(), Filters.GENRES, id.endsWith("next"))));
                rebuild();
            }
            case "select.language.prev", "select.language.next" -> {
                fields.put("wizard.language.id", String.valueOf(cycle(languageId(), Filters.LANGUAGES, id.endsWith("next"))));
                rebuild();
            }
            case "pp.strip" -> {
                movePreview(mouseX);
                draggingWindow = true;
            }
            case "pp.play" -> {
                if (PreviewPlayer.isCurrent("wizard_preview")) {
                    PreviewPlayer.stop();
                } else {
                    playPreview();
                }
                rebuild();
            }
            case "choice.target.community", "choice.target.wip" -> {
                target = id.endsWith("wip") ? "wip" : "community";
                rebuild();
            }
            case "bar.publish" -> publish();
            case "bar.cancel" -> {
                if (uploader != null) {
                    uploader.cancel();
                }
            }
            case "bar.open" -> {
                if (resultSetId != null) {
                    screen.closeOverlay();
                    screen.openSetPage(resultSetId, null);
                }
            }
            case "bar.install" -> {
                if (resultSetId != null) {
                    client.downloader().enqueue(resultSetId);
                }
            }
            case "bar.another" -> {
                screen.closeOverlay();
                screen.openUpload(null);
            }
            case "bar.edit" -> {
                uploader = null;
                snapshot = null;
                error = null;
                go(UploadLayout.Step.DETAILS);
                rebuild();
            }
            case "bar.close" -> screen.closeOverlay();
            default -> {
                // Text fields handle themselves.
            }
        }
    }

    /** Puts the 30-second window under the pointer, centred on it, as dragging the site's window does. */
    private void movePreview(double mouseX) {
        Rect strip = screenRect(page.content().rect("pp.strip"), 0);
        if (strip.empty() || durationMs <= 0) {
            return;
        }
        double fraction = Math.max(0, Math.min(1, (mouseX - strip.x()) / Math.max(1, strip.w())));
        long start = (long) (fraction * durationMs) - UploadLayout.PREVIEW_MS / 2;
        long latest = Math.max(0, durationMs - UploadLayout.PREVIEW_MS);
        previewStartMs = Math.max(0, Math.min(latest, start / 1000 * 1000));
        rebuild();
    }

    private static int cycle(int current, int[] values, boolean forward) {
        int index = 0;
        for (int i = 0; i < values.length; i++) {
            if (values[i] == current) {
                index = i;
                break;
            }
        }
        index = Math.floorMod(index + (forward ? 1 : -1), values.length);
        return values[index];
    }

    private void playPreview() {
        Path audio = audioFile();
        if (audio != null) {
            PreviewPlayer.play("wizard_preview", audio, UploadLayout.PREVIEW_MS, client.previewVolume());
        }
    }

    private void next() {
        switch (step) {
            case RULES -> acceptRules();
            case FILES -> {
                List<UploadLayout.Issue> found = filesProblems();
                issues = found;
                if (found.isEmpty()) {
                    go(UploadLayout.Step.DETAILS);
                }
                rebuild();
            }
            case DETAILS -> {
                captureFields();
                fieldErrors = detailsErrors();
                issues = List.of();
                if (fieldErrors.isEmpty()) {
                    go(UploadLayout.Step.PUBLISH);
                } else {
                    rebuild();
                    scrollToFirstError();
                    return;
                }
                rebuild();
            }
            default -> {
                // Publish has its own button; the progress step has no Next.
            }
        }
    }

    private void back() {
        issues = List.of();
        switch (step) {
            case FILES -> {
                if (rules.accepted() || updateSetId != null) {
                    screen.closeOverlay();
                    return;
                }
                go(UploadLayout.Step.RULES);
            }
            case DETAILS -> go(UploadLayout.Step.FILES);
            case PUBLISH -> go(UploadLayout.Step.DETAILS);
            default -> {
                screen.closeOverlay();
                return;
            }
        }
        rebuild();
    }

    private List<UploadLayout.Issue> filesProblems() {
        List<UploadLayout.Issue> out = new ArrayList<>();
        if (selectedTracks().isEmpty()) {
            out.add(new UploadLayout.Issue(WorkshopTr.INSTANCE.get(K + "problem.no_charts"), false));
        } else {
            if ("upload".equals(audioMode) && audioFile() == null) {
                out.add(new UploadLayout.Issue(WorkshopTr.INSTANCE.get(K + "problem.no_audio"), false));
            }
            if (!"none".equals(coverMode) && coverPath() == null) {
                out.add(new UploadLayout.Issue(WorkshopTr.INSTANCE.get(K + "cover.missing"), false));
            }
        }
        return out;
    }

    private Map<String, String> detailsErrors() {
        Map<String, String> out = new LinkedHashMap<>();
        if (value("wizard.title").isBlank()) {
            out.put("title", WorkshopTr.INSTANCE.get(K + "problem.title"));
        }
        if (value("wizard.artist").isBlank()) {
            out.put("artist", WorkshopTr.INSTANCE.get(K + "problem.artist"));
        }
        return out;
    }

    private void scrollToFirstError() {
        for (String name : fieldErrors.keySet()) {
            Rect rect = page.content().rect("wizard." + name + ".label");
            if (!rect.empty()) {
                scroll.scrollTo(Math.max(0, rect.y() - 8), true);
                input("wizard." + name).setFocused(true);
                return;
            }
        }
    }

    /** Copies what the player typed into the model, so it survives a step change and a refreshed track list. */
    private void captureFields() {
        for (String id : TEXT_FIELDS) {
            TextField field = inputs.get(id);
            if (field != null) {
                fields.put(id, field.value());
            }
        }
        for (int i = 0; i < tracks.size(); i++) {
            TextField difficulty = inputs.get("wizard.track." + i + ".difficulty");
            TextField key = inputs.get("wizard.track." + i + ".key");
            if (difficulty != null && !difficulty.value().isBlank()) {
                difficulties.put(tracks.get(i).id(), difficulty.value());
            }
            if (key != null && !key.value().isBlank()) {
                keys.put(tracks.get(i).id(), safeKey(key.value()));
            }
        }
    }

    // ---------------------------------------------------------------- publishing

    private void publish() {
        captureFields();
        List<UploadLayout.Issue> found = new ArrayList<>(filesProblems());
        fieldErrors = detailsErrors();
        for (String message : fieldErrors.values()) {
            found.add(new UploadLayout.Issue(message, false));
        }
        issues = found;
        if (!found.isEmpty() || !canPublish()) {
            rebuild();
            return;
        }
        List<MusicBattleTracks.Track> chosen = selectedTracks();
        PreviewPlayer.stop();
        go(UploadLayout.Step.PROGRESS);
        error = null;
        resultStatus = null;
        fieldErrors = Map.of();
        rebuild();
        screen.run(() -> {
            try {
                WorkshopUploader.Request request = buildRequest(chosen);
                WorkshopUploader running = new WorkshopUploader(client.session(), System::currentTimeMillis, Thread::sleep);
                uploader = running;
                snapshot = running.snapshot();
                screen.later(this::rebuild);
                running.run(request);
                screen.later(() -> {
                    snapshot = running.snapshot();
                    onSnapshot(snapshot);
                    screen.onCatalogChanged();
                });
            } catch (Exception exception) {
                MusicBattleMod.LOGGER.warn("Workshop upload failed: {}", MusicBattleTracks.safeMessage(exception));
                screen.later(() -> {
                    error = WorkshopText.error("internal_error").getString();
                    rebuild();
                });
            }
        });
    }

    private WorkshopUploader.Request buildRequest(List<MusicBattleTracks.Track> chosen) throws java.io.IOException {
        JsonObject metadata = new JsonObject();
        metadata.addProperty("title", fields.getOrDefault("wizard.title", "").trim());
        metadata.addProperty("artist", fields.getOrDefault("wizard.artist", "").trim());
        metadata.addProperty("source", fields.getOrDefault("wizard.source", "").trim());
        JsonArray tags = new JsonArray();
        for (String tag : fields.getOrDefault("wizard.tags", "").split("[,\\s]+")) {
            if (!tag.isBlank()) {
                tags.add(tag.trim().toLowerCase(Locale.ROOT));
            }
        }
        metadata.add("tags", tags);
        metadata.addProperty("genre", genreId());
        metadata.addProperty("language", languageId());
        metadata.addProperty("descriptionMd", fields.getOrDefault("wizard.description", ""));
        metadata.addProperty("previewStartMs", previewStartMs);
        metadata.addProperty("targetStatus", target);
        metadata.addProperty("changelog", fields.getOrDefault("wizard.changelog", ""));
        metadata.addProperty("credits", fields.getOrDefault("wizard.credits", ""));
        JsonObject flagsJson = new JsonObject();
        for (String flag : UploadLayout.FLAGS) {
            flagsJson.addProperty(flag, ticked.contains("flag." + flag));
        }
        metadata.add("flags", flagsJson);

        List<WorkshopUploader.SlotFile> files = new ArrayList<>();
        JsonArray charts = new JsonArray();
        for (MusicBattleTracks.Track track : chosen) {
            String key = keys.getOrDefault(track.id(), safeKey(track.id()));
            JsonObject chart = new JsonObject();
            chart.addProperty("key", key);
            chart.addProperty("name", difficulties.getOrDefault(track.id(), track.title()));
            chart.addProperty("from", "upload");
            charts.add(chart);
            files.add(new WorkshopUploader.SlotFile("chart:" + key, track.definitionPath(), "application/json"));
        }
        metadata.add("charts", charts);

        JsonObject audio = new JsonObject();
        Path audioFile = chosen.stream().map(MusicBattleTracks::audioPath).filter(Optional::isPresent).map(Optional::get)
                .findFirst().orElse(null);
        if ("external".equals(audioMode)) {
            audio.addProperty("mode", "external");
            if (audioFile != null) {
                JsonObject declared = new JsonObject();
                declared.addProperty("sha256", InstalledActions.sha256(audioFile));
                declared.addProperty("size", Files.size(audioFile));
                declared.addProperty("durationMs", MusicBattleTracks.readOggDurationTicks(audioFile) * 50L);
                audio.add("declared", declared);
            }
        } else {
            audio.addProperty("mode", "upload");
            if (audioFile != null) {
                files.add(new WorkshopUploader.SlotFile("audio", audioFile, "audio/ogg"));
            }
        }
        metadata.add("audio", audio);

        JsonObject cover = new JsonObject();
        Path coverPath = "file".equals(coverMode) ? coverFile
                : "preview".equals(coverMode) ? chosen.stream().map(MusicBattleTracks::previewPath)
                .filter(Optional::isPresent).map(Optional::get).findFirst().orElse(null) : null;
        if (coverPath != null && Files.isRegularFile(coverPath, LinkOption.NOFOLLOW_LINKS)) {
            cover.addProperty("mode", "upload");
            files.add(new WorkshopUploader.SlotFile("cover", coverPath, contentType(coverPath)));
        } else {
            cover.addProperty("mode", "none");
        }
        metadata.add("cover", cover);
        return new WorkshopUploader.Request(updateSetId, files, metadata, termsVersion());
    }

    private static String contentType(Path file) {
        String name = String.valueOf(file.getFileName()).toLowerCase(Locale.ROOT);
        if (name.endsWith(".png")) {
            return "image/png";
        }
        if (name.endsWith(".webp")) {
            return "image/webp";
        }
        return "image/jpeg";
    }

    // ---------------------------------------------------------------- input

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (page == null) {
            return false;
        }
        Rect viewport = screenRect(page.viewport(), 0);
        boolean inBar = screenRect(page.bar().rect("bar"), 0).contains(mouseX, mouseY);
        if (!viewport.contains(mouseX, mouseY) && !inBar) {
            unfocus();
            return false;
        }
        // A drag moves the preview window only when it started on the strip; every new press ends the last one.
        draggingWindow = false;
        if (button != 0) {
            return false;
        }
        if (inBar) {
            unfocus();
            for (String id : page.clicks()) {
                if (id.startsWith("bar.") && barEnabled(id) && screenRect(page.bar().rect(id), 0).contains(mouseX, mouseY)) {
                    onAction(id, mouseX);
                    return true;
                }
            }
            return true;
        }
        float offset = scroll.snapped(TextStyles.guiScale());
        boolean handled = false;
        for (Layout.Box box : page.content().boxes()) {
            if (box.kind() == Layout.Kind.FIELD) {
                handled |= input(box.id()).mouseClicked(mouseX, mouseY, button);
            }
        }
        for (Map.Entry<String, TextField> entry : inputs.entrySet()) {
            if (!page.content().has(entry.getKey())) {
                entry.getValue().setFocused(false);
            }
        }
        if (handled) {
            return true;
        }
        for (String id : page.clicks()) {
            if (!id.startsWith("bar.") && screenRect(page.content().rect(id), offset).contains(mouseX, mouseY)) {
                onAction(id, mouseX);
                return true;
            }
        }
        return true;
    }

    private void unfocus() {
        for (TextField field : inputs.values()) {
            field.setFocused(false);
        }
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button) {
        if (page == null) {
            return false;
        }
        if (draggingWindow) {
            movePreview(mouseX);
            return true;
        }
        for (Layout.Box box : page.content().boxes()) {
            if (box.kind() == Layout.Kind.FIELD && input(box.id()).mouseDragged(mouseX, mouseY, button)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        if (page == null || !screenRect(page.viewport(), 0).contains(mouseX, mouseY) || scroll.extent() <= 0) {
            draggingWindow = false;
            return false;
        }
        scroll.onWheel(amount);
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        draggingWindow = false;
        for (Map.Entry<String, TextField> entry : inputs.entrySet()) {
            TextField field = entry.getValue();
            if (field.focused() && page != null && page.content().has(entry.getKey()) && field.keyPressed(keyCode, scanCode, modifiers)) {
                afterTyping(entry.getKey());
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean charTyped(char character, int modifiers) {
        for (Map.Entry<String, TextField> entry : inputs.entrySet()) {
            TextField field = entry.getValue();
            if (field.focused() && page != null && page.content().has(entry.getKey()) && field.charTyped(character, modifiers)) {
                afterTyping(entry.getKey());
                return true;
            }
        }
        return false;
    }

    /**
     * A typed character can grow a multi-line field and answers the error under its field, so the page is measured
     * again; the previews on the Publish step read the same values.
     */
    private void afterTyping(String id) {
        String name = id.startsWith("wizard.") ? id.substring("wizard.".length()) : id;
        if (fieldErrors.containsKey(name) && !value(id).isBlank()) {
            Map<String, String> left = new LinkedHashMap<>(fieldErrors);
            left.remove(name);
            fieldErrors = left;
        }
        relayout();
    }
}

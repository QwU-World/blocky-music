package ru.gora.musicbattle.client.workshop;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Supplier;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.glfw.GLFW;
import ru.gora.musicbattle.client.ui.CoverCache;
import ru.gora.musicbattle.client.ui.LinkActions;
import ru.gora.musicbattle.client.ui.Motion;
import ru.gora.musicbattle.client.ui.Palette;
import ru.gora.musicbattle.client.ui.PreviewPlayer;
import ru.gora.musicbattle.client.ui.RoundedImage;
import ru.gora.musicbattle.client.ui.RoundedRect;
import ru.gora.musicbattle.client.ui.Spectrum;
import ru.gora.musicbattle.client.ui.StarPill;
import ru.gora.musicbattle.client.ui.TextStyle;
import ru.gora.musicbattle.client.ui.TextStyles;
import ru.gora.musicbattle.client.ui.UiClip;
import ru.gora.musicbattle.client.ui.UiIcon;
import ru.gora.musicbattle.client.workshop.layout.Layout;
import ru.gora.musicbattle.client.workshop.layout.HtmlText;
import ru.gora.musicbattle.client.workshop.layout.Rect;
import ru.gora.musicbattle.client.workshop.layout.SetPageLayout;
import ru.gora.musicbattle.client.workshop.layout.WorkshopStrings;
import ru.gora.musicbattle.workshop.WorkshopCatalog;
import ru.gora.musicbattle.workshop.api.CancelToken;
import ru.gora.musicbattle.workshop.api.WorkshopApiException;
import ru.gora.musicbattle.workshop.api.WorkshopDownloader;
import ru.gora.musicbattle.workshop.api.WorkshopDtos;

/**
 * The set page: header over the cover with the chart tiles, title, artist, mapper card,
 * availability banner and the buttons (♥, Install, Open in browser, ⋯), the right column with the status pill,
 * preview bar, basic stats, attribute bars and the user-rating box, the info section, the charts table and the
 * comments. It slides over the listing and the listing keeps its state.
 *
 * <p>Without the network (or offline) the page opens in local mode from the installed card sidecar, so an installed
 * set always has a page.
 */
public final class SetPageView implements WorkshopScreen.Overlay {
    /** How long a preview clip may be on its way before the bar says it is not coming. */
    private static final long PREVIEW_WAIT_MS = 20_000L;

    private final WorkshopScreen screen;
    private final WorkshopClient client;
    private final long setId;
    private final CommentsSection comments;
    /**
     * The key of the Minecraft server's own comment thread when the page was opened from the Server tab
     * ({@code ws_<setId>} or {@code track_<trackId>}), or null for an ordinary Workshop page.
     */
    private final String serverKey;
    /** What the server's list says about one of its own beatmaps; null on every other page. */
    private final ru.gora.musicbattle.client.workshop.layout.BattleTracks.Track serverRow;
    /** The picture the server keeps for this map, for a page whose map the Workshop has no cover of; may be null. */
    private final Supplier<ResourceLocation> serverPicture;
    private final Motion.SmoothScroll scroll = new Motion.SmoothScroll();

    private CardModel card;
    private WorkshopDtos.SetDetail detail;
    private WorkshopDtos.RatingSummary rating;
    private Layout layout;
    private Rect area = Rect.EMPTY;
    private int selectedChart;
    private boolean loading;
    private boolean localMode;
    private String error;
    private float commentsTop;
    private SetPageLayout.State frameState;
    /** The heart's own answer, so one toggle does not reload the page and take the comment thread with it. */
    private Boolean favouriteOverride;
    private long favouriteCountOverride = -1L;
    /** A spoiler description stays folded until the reader asks for it, for as long as the page is open. */
    private boolean spoilerRevealed;
    /** Answers that came back from the network and have still to reach the render thread; see {@link #deliver}. */
    private final Queue<Runnable> pending = new ConcurrentLinkedQueue<>();
    /** When the clip was asked for, so one that never arrives says so instead of leaving the bar as it was. */
    private long previewRequestedMs;
    /** This page pressed Play and has not heard back that the clip failed or that the press was taken back. */
    private boolean previewAsked;
    /** Why there is nothing to hear, once that is known: the bar carries this line in place of the play button. */
    private String previewProblem;
    /** The press that asked for the network, kept until the consent dialog answers; see {@link #askNetwork}. */
    private Runnable afterConsent;

    public SetPageView(WorkshopScreen screen, WorkshopClient client, long setId, CardModel model) {
        this(screen, client, setId, model, null, null);
    }

    /**
     * A page opened from the Server tab: it starts on the server's own comments, whose authors are drawn with their
     * skin heads. A track made in the editor has a negative card id and no Workshop set behind it, so its page is
     * built from the card alone and never asks the site about it.
     */
    public SetPageView(WorkshopScreen screen, WorkshopClient client, long setId, CardModel model, String serverKey,
                       Supplier<ResourceLocation> serverPicture) {
        this(screen, client, setId, model, serverKey, serverPicture, null);
    }

    /** A server beatmap's page, built from its row of the server's list; see {@link SetPageLayout#serverTrack}. */
    public SetPageView(WorkshopScreen screen, WorkshopClient client, long setId, CardModel model, String serverKey,
                       Supplier<ResourceLocation> serverPicture,
                       ru.gora.musicbattle.client.workshop.layout.BattleTracks.Track serverRow) {
        this.serverRow = serverRow;
        this.screen = screen;
        this.client = client;
        this.setId = setId;
        this.card = model;
        this.serverKey = serverKey;
        this.serverPicture = serverPicture;
        this.comments = serverKey == null ? new CommentsSection(screen, client, setId)
                : new CommentsSection(screen, client, setId, serverKey, true);
        // The section can grow between layout passes (the rules line, a loaded page), and the page owns the scrollbar.
        this.comments.setOnResize(this::relayout);
        if (serverComments()) {
            // A section that starts on the server's list never registers itself the way switching to it does, and the
            // server's pages are delivered only to the registered view, so without this it would wait on a spinner.
            ServerWorkshopClient.setCommentsView(comments);
            comments.reload();
        }
        load();
    }

    /** Whether this is one of the server's editor-made tracks, which the Workshop knows nothing about. */
    private boolean serverTrack() {
        return serverKey != null && setId <= 0;
    }

    /** Whether the page shows the server's comments, which need no answer from the Workshop to be drawn. */
    private boolean serverComments() {
        return serverKey != null && WorkshopServerHooks.serverCommentsAvailable();
    }

    /** Whether the comment section is on the page: once the set has loaded, or at once for the server's own thread. */
    private boolean showsComments() {
        return detail != null || serverComments();
    }

    @Override
    public Component title() {
        return Component.translatable(WorkshopStrings.KEY + "title.set");
    }

    @Override
    public void setArea(Rect value) {
        area = value;
        pump();
        // Coming back from the account screen: what this page may offer - rating, favourite, the composer - was
        // decided by the server for whoever was signed in when it loaded. Signing in while it was open used to
        // leave the whole page in its guest state until it was closed and opened again. Allowing the network is the
        // other answer that arrives this way, and it decides more still: a page built from the installed copy alone
        // kept its offline banner, its blank cover and no comments at all for as long as it stayed open.
        if (loadedSessionVersion != client.session().stateVersion() || loadedWithNetwork != client.networkAllowed()) {
            load();
        }
        // Whatever the press that asked for the network was for, now that the answer is in. A refusal drops it: the
        // player turned down this very action, so it must not happen behind the dialog that asked.
        Runnable asked = afterConsent;
        afterConsent = null;
        if (asked != null && client.networkAllowed()) {
            asked.run();
        }
        relayout();
    }

    @Override
    public void onClose() {
        PreviewPlayer.stop();
        comments.onPageClosed();
    }

    /**
     * Hands an answer back to this page.
     *
     * <p>The link confirmation "Open in browser" raises is a vanilla screen over the Workshop, not one of its dialogs,
     * and an answer that arrived under one used to be dropped on the spot: {@code loading} stayed set and the page
     * stayed on its spinner until it was closed and opened again. The page holds its own answers here and applies
     * them from its own frames, so each one reaches the view that asked whatever was on top when it came in.
     */
    private void deliver(Runnable work) {
        pending.add(work);
        screen.later(this::pump);
    }

    /** Applies whatever came back while something else was on top. Render thread only. */
    private void pump() {
        Runnable work;
        while ((work = pending.poll()) != null) {
            work.run();
        }
    }

    // ---------------------------------------------------------------- data

    private void load() {
        loadedSessionVersion = client.session().stateVersion();
        loadedWithNetwork = client.networkAllowed();
        // The bar's reason was about the page as it stood; one being fetched again has no answer about the clip yet.
        previewProblem = null;
        WorkshopCatalog.InstalledSet installed = client.catalog().bySetId(setId).orElse(null);
        if (card == null && installed != null) {
            card = CardModel.ofInstalled(installed);
        }
        if (serverTrack()) {
            // Nothing to fetch: the card the Server tab built is all there is to know, and it is not an offline copy.
            localMode = false;
            error = null;
            return;
        }
        if (!client.networkAllowed()) {
            localMode = card != null;
            error = card == null ? WorkshopApiException.CONSENT_REQUIRED : null;
            return;
        }
        loading = true;
        screen.run(() -> {
            try {
                WorkshopDtos.SetDetail loaded = client.session().signedIn()
                        ? client.session().authorized(api -> api.set(setId, CancelToken.none()))
                        : client.api().set(setId, CancelToken.none());
                deliver(() -> {
                    loading = false;
                    detail = loaded;
                    // A freshly loaded page is the truth again, so the heart stops answering from its own memory.
                    favouriteOverride = null;
                    favouriteCountOverride = -1L;
                    localMode = false;
                    error = null;
                    comments.setOwner(loaded.mapper() == null ? 0 : loaded.mapper().id());
                    comments.reload();
                    relayout();
                });
            } catch (WorkshopApiException exception) {
                deliver(() -> {
                    loading = false;
                    localMode = card != null;
                    error = exception.code();
                    relayout();
                });
            }
        });
        if (client.session().signedIn()) {
            screen.run(() -> {
                try {
                    WorkshopDtos.RatingSummary summary = client.session()
                            .authorized(api -> api.rating(setId, CancelToken.none()));
                    deliver(() -> {
                        rating = summary;
                        relayout();
                    });
                } catch (WorkshopApiException ignored) {
                    // The rating box then shows the values of the set detail only.
                }
            });
        }
    }

    // ---------------------------------------------------------------- layout

    /** Which sign-in the page was loaded for; see {@link #setArea}. */
    private long loadedSessionVersion = -1L;
    /** Whether the network was allowed when the page was loaded; see {@link #setArea}. */
    private boolean loadedWithNetwork;

    private void relayout() {
        if (area.empty()) {
            return;
        }
        frameState = state();
        layout = SetPageLayout.compute(new Rect(0, 0, area.w(), area.h()), TextStyles.measurer(), WorkshopTr.INSTANCE, frameState);
        commentsTop = layout.contentHeight() + 6;
        float commentsHeight = showsComments() ? comments.layout(area, commentsTop) : 0F;
        scroll.setExtent(Math.max(0, layout.contentHeight() + commentsHeight + 10 - area.h()));
    }

    private SetPageLayout.State state() {
        WorkshopTr tr = WorkshopTr.INSTANCE;
        if (serverTrack() && serverRow != null) {
            // Everything the Workshop would say about a set is missing for a server beatmap, and the page used to draw
            // it all anyway from a stand-in card: BPM 0, length 0:00, a COMMUNITY pill and "0 downloads".
            return SetPageLayout.serverTrack(tr, serverRow.title(), serverRow.kind().name().toLowerCase(Locale.ROOT),
                    ListingView.kindLabel(tr, serverRow.kind()), ListingView.byline(tr, serverRow), serverRow.bpm(),
                    serverRow.durationTicks() * 50L, serverRow.description(), tr.get(WorkshopStrings.KEY + "set.server_track"));
        }
        String title = detail != null ? detail.title() : card != null ? card.title : "…";
        String artist = detail != null ? detail.artist() : card != null ? card.artist : "";
        List<SetPageLayout.Badge> badges = new ArrayList<>();
        boolean explicit = detail != null && detail.flags() != null ? detail.flags().explicit() : card != null && card.explicit;
        if (explicit) {
            badges.add(badge(tr, "explicit"));
        }
        // The listing card declares generated content and the page used to drop that badge, so a set stopped saying so
        // at the very moment the reader opened it to decide whether to install it.
        boolean ai = detail != null && detail.flags() != null
                ? detail.flags().aiAudio() || detail.flags().aiCover() || detail.flags().aiChart()
                : card != null && card.ai;
        if (ai) {
            badges.add(badge(tr, "ai"));
        }
        if (detail != null && detail.library() != null || card != null && card.library) {
            badges.add(badge(tr, "library"));
        }
        String status = detail != null ? detail.status() : card != null ? card.status : "community";
        List<Double> stars = new ArrayList<>();
        List<SetPageLayout.ChartRow> charts = new ArrayList<>();
        if (detail != null && detail.charts() != null) {
            for (WorkshopDtos.ChartDetail chart : detail.charts()) {
                stars.add(chart.stars());
                charts.add(new SetPageLayout.ChartRow(chart.key(), chart.name(), chart.stars(),
                        WorkshopStrings.count(chart.noteCount()), WorkshopStrings.count(chart.holdCount()),
                        WorkshopStrings.count(chart.effectCount()), WorkshopStrings.duration(chart.durationMs())));
            }
        } else if (card != null) {
            stars.addAll(card.stars);
            for (int i = 0; i < card.chartNames.size(); i++) {
                charts.add(new SetPageLayout.ChartRow(i < card.chartKeys.size() ? card.chartKeys.get(i) : "",
                        card.chartNames.get(i), i < card.stars.size() ? card.stars.get(i) : 0, "", "", "",
                        WorkshopStrings.duration(card.durationMs)));
            }
        }
        int chartIndex = Math.max(0, Math.min(stars.size() - 1, selectedChart));
        String chartName = charts.isEmpty() ? "" : charts.get(Math.min(chartIndex, charts.size() - 1)).name();
        long downloads = detail != null && detail.counts() != null ? detail.counts().downloads() : card != null ? card.downloads : 0;
        long favourites = favouriteCount();
        // One download and one favourite are counted in words, not "1 favourites"; Russian needs its third form too.
        String counters = WorkshopStrings.plural(tr, WorkshopStrings.KEY + "card.downloads", downloads) + "   "
                + WorkshopStrings.plural(tr, WorkshopStrings.KEY + "card.favourites", favourites);
        String mapper = tr.get(WorkshopStrings.KEY + "card.mapped_by",
                detail != null && detail.mapper() != null ? detail.mapper().username() : card != null ? card.mapperName : "?");
        String submitted = detail == null ? "" : tr.get(WorkshopStrings.KEY + "set.submitted",
                WorkshopStrings.relative(tr, detail.submittedAt(), System.currentTimeMillis()));
        String updated = detail == null ? "" : detail.featuredAt() != null
                ? tr.get(WorkshopStrings.KEY + "set.featured", WorkshopStrings.relative(tr, detail.featuredAt(), System.currentTimeMillis()))
                : tr.get(WorkshopStrings.KEY + "set.updated", WorkshopStrings.relative(tr, detail.updatedAt(), System.currentTimeMillis()));

        String availability = availabilityText();
        WorkshopDownloader.Status downloadStatus = client.downloader().status(setId);
        boolean canInstall = installAllowed(downloadStatus);
        // A set the server will not serve says so on the button itself instead of offering an Install that fails.
        String installLabel = canInstall || downloadStatus.state() == WorkshopDownloader.State.UNAVAILABLE
                ? installLabel(downloadStatus) : tr.get(WorkshopStrings.KEY + "set.unavailable");
        String installSub = installSub(downloadStatus);
        if (serverTrack()) {
            // A track made in the editor is played from the server and never installed; the button says where it is.
            canInstall = false;
            installLabel = tr.get(WorkshopStrings.KEY + "set.server_track");
            installSub = "";
        }
        // In a battle's map picker the primary button chooses this map for the battle; the Workshop says what it reads.
        WorkshopScreen.PrimaryAction primary = screen.primaryAction(setId, card, detail, chartIndex);
        if (primary != null) {
            canInstall = primary.enabled();
            installLabel = primary.label();
            installSub = primary.sub();
        }
        boolean favourited = favourited();

        String description = detail != null ? HtmlText.plain(detail.descriptionHtml()) : "";
        boolean spoiler = !spoilerRevealed && !description.isBlank()
                && detail != null && detail.flags() != null && detail.flags().spoiler();
        String credits = detail != null ? HtmlText.plain(detail.creditsHtml()) : "";
        String featuredNote = detail != null && detail.featured() != null ? detail.featured().note() : null;

        List<SetPageLayout.Stat> metadata = new ArrayList<>();
        if (detail != null) {
            if (detail.source() != null && !detail.source().isEmpty()) {
                metadata.add(new SetPageLayout.Stat(tr.get(WorkshopStrings.KEY + "set.source"), detail.source()));
            }
            metadata.add(new SetPageLayout.Stat(tr.get(WorkshopStrings.KEY + "set.genre"), WorkshopStrings.genre(tr, detail.genre())));
            metadata.add(new SetPageLayout.Stat(tr.get(WorkshopStrings.KEY + "set.language"),
                    WorkshopStrings.language(tr, detail.language())));
            if (detail.tags() != null && !detail.tags().isEmpty()) {
                metadata.add(new SetPageLayout.Stat(tr.get(WorkshopStrings.KEY + "set.tags"), String.join(" ", detail.tags())));
            }
        }

        List<SetPageLayout.Stat> basic = new ArrayList<>();
        WorkshopDtos.ChartDetail chart = detail != null && detail.charts() != null && !detail.charts().isEmpty()
                ? detail.charts().get(Math.min(chartIndex, detail.charts().size() - 1)) : null;
        basic.add(new SetPageLayout.Stat(tr.get(WorkshopStrings.KEY + "set.length"),
                WorkshopStrings.duration(chart != null ? chart.durationMs() : card != null ? card.durationMs : 0)));
        basic.add(new SetPageLayout.Stat(tr.get(WorkshopStrings.KEY + "set.bpm"),
                String.format(Locale.ROOT, "%.0f", chart != null ? chart.bpm() : card != null ? card.bpm : 0)));
        basic.add(new SetPageLayout.Stat(tr.get(WorkshopStrings.KEY + "set.notes"),
                WorkshopStrings.count(chart == null ? 0 : chart.noteCount())));
        basic.add(new SetPageLayout.Stat(tr.get(WorkshopStrings.KEY + "set.holds"),
                WorkshopStrings.count(chart == null ? 0 : chart.holdCount())));

        List<SetPageLayout.Attribute> attributes = new ArrayList<>();
        double starValue = chart != null ? chart.stars() : stars.isEmpty() ? 0 : stars.get(Math.min(chartIndex, stars.size() - 1));
        attributes.add(new SetPageLayout.Attribute(tr.get(WorkshopStrings.KEY + "set.attr.stars"),
                WorkshopStrings.stars(starValue), (float) Math.min(1, starValue / 10.0)));
        double density = chart != null && chart.peakNps() != null ? chart.peakNps() : 0;
        attributes.add(new SetPageLayout.Attribute(tr.get(WorkshopStrings.KEY + "set.attr.density"),
                String.format(Locale.ROOT, "%.1f", density), (float) Math.min(1, density / 20.0)));
        double holdShare = chart != null && chart.noteCount() > 0 ? (double) chart.holdCount() / chart.noteCount() : 0;
        attributes.add(new SetPageLayout.Attribute(tr.get(WorkshopStrings.KEY + "set.attr.holds"),
                WorkshopStrings.percent(holdShare), (float) holdShare));
        double effects = chart != null ? chart.effectCount() : 0;
        attributes.add(new SetPageLayout.Attribute(tr.get(WorkshopStrings.KEY + "set.attr.effects"),
                WorkshopStrings.count((long) effects), (float) Math.min(1, effects / 50.0)));

        List<Integer> histogram = rating != null && rating.hist() != null ? rating.hist()
                : detail != null && detail.ratingHist() != null ? detail.ratingHist() : List.of();
        Double average = rating != null ? rating.average() : detail != null ? detail.rating() : null;
        long negative = 0;
        long positive = 0;
        for (int i = 0; i < histogram.size(); i++) {
            if (i < 5) {
                negative += histogram.get(i);
            } else {
                positive += histogram.get(i);
            }
        }
        Integer mine = rating != null ? rating.mine() : detail != null && detail.viewer() != null ? detail.viewer().rating() : null;
        boolean canRate = detail != null && detail.can() != null && Boolean.TRUE.equals(detail.can().get("rate"));
        // Only a loaded page knows why the rating is closed; offline it stays silent rather than inventing a reason.
        String cannotRate = canRate || detail == null ? null : refusal("rate").getString();

        String audioLine = audioLine();
        return new SetPageLayout.State(title, artist, badges, WorkshopStrings.status(tr, status), chartName, stars, mapper,
                submitted, updated, counters, availability, installLabel, installSub, canInstall, favourited,
                canFavourite(),
                CardRenderer.downloading(downloadStatus), downloadStatus.progress(), description, spoiler, credits,
                featuredNote, metadata, basic, attributes, WorkshopStrings.rating(average), histogram,
                WorkshopStrings.count(negative), WorkshopStrings.count(positive), mine, canRate, cannotRate, audioLine,
                charts, localMode, false, chartIndex);
    }

    /** A badge of the header; the key is the one the cards colour theirs by, so both screens agree. */
    private static SetPageLayout.Badge badge(WorkshopTr tr, String key) {
        return new SetPageLayout.Badge(key, WorkshopStrings.upper(tr.get(WorkshopStrings.KEY + "badge." + key)));
    }

    private boolean favourited() {
        if (favouriteOverride != null) {
            return favouriteOverride;
        }
        return detail != null && detail.viewer() != null ? detail.viewer().favourited() : card != null && card.favourited;
    }

    private long favouriteCount() {
        if (favouriteCountOverride >= 0) {
            return favouriteCountOverride;
        }
        return detail != null && detail.counts() != null ? detail.counts().favourites() : card != null ? card.favourites : 0;
    }

    private boolean canFavourite() {
        if (serverTrack()) {
            return false;
        }
        return detail == null || detail.can() == null || Boolean.TRUE.equals(detail.can().get("favourite"));
    }

    /**
     * Whether the Install button leads anywhere. The site answers with {@code can.download} and a download block that
     * is null once a set is withdrawn or deleted; the page used to read neither and offered a primary button for a
     * package the server answers with 410. What is already downloaded stays actionable: cancelling a transfer and
     * opening an installed set need nothing from the server.
     */
    private boolean installAllowed(WorkshopDownloader.Status status) {
        boolean local = status.state() == WorkshopDownloader.State.INSTALLED
                || status.state() == WorkshopDownloader.State.DOWNLOADING
                || status.state() == WorkshopDownloader.State.QUEUED
                || status.state() == WorkshopDownloader.State.INSTALLING;
        return local || serverOffersDownload();
    }

    private boolean serverOffersDownload() {
        if (detail == null) {
            // Local or offline mode: there is no answer to obey, and the downloader reports its own failure.
            return true;
        }
        if (detail.can() != null && !Boolean.TRUE.equals(detail.can().get("download"))) {
            return false;
        }
        return detail.download() != null;
    }

    /**
     * Why the site refuses an action, in words.
     *
     * <p>Its {@code cannot} map speaks a vocabulary of its own - unavailable, own_set, account_too_new - which is not
     * the vocabulary of the error codes, so each code gets its own line, a missing entry falls back to a general one
     * for that action, and an unexpected code still reaches the player as error text rather than as nothing.
     */
    private Component refusal(String action) {
        String code = detail == null || detail.cannot() == null ? null : detail.cannot().get(action);
        if (code == null || code.isEmpty()) {
            return Component.translatable(WorkshopStrings.KEY + "set.cannot." + action);
        }
        String key = WorkshopStrings.KEY + "set.cannot." + code;
        return Language.getInstance().has(key) ? Component.translatable(key) : WorkshopText.error(code);
    }

    private void showRefusal(String action) {
        screen.showMessage(Component.translatable(WorkshopStrings.KEY + "error_title"), refusal(action));
    }

    private String availabilityText() {
        if (detail != null && detail.availabilityInfo() != null && detail.availabilityInfo().state() != null
                && !"ok".equals(detail.availabilityInfo().state())) {
            return WorkshopTr.INSTANCE.get(WorkshopStrings.DATA + "availability." + detail.availabilityInfo().state());
        }
        if (localMode) {
            return WorkshopTr.INSTANCE.get(WorkshopStrings.KEY + "set.local_mode");
        }
        if (error != null) {
            return WorkshopText.error(error).getString();
        }
        return null;
    }

    private String audioLine() {
        if (detail == null || detail.audio() == null) {
            return card != null && card.externalAudio()
                    ? WorkshopTr.INSTANCE.get(WorkshopStrings.KEY + "set.audio.external_short") : null;
        }
        WorkshopDtos.AudioInfo audio = detail.audio();
        if ("external".equals(audio.mode())) {
            return WorkshopTr.INSTANCE.get(WorkshopStrings.KEY + "set.audio.external", WorkshopStrings.duration(audio.durationMs()),
                    WorkshopStrings.size(audio.size()), shortHash(audio.sha256()));
        }
        return WorkshopTr.INSTANCE.get(WorkshopStrings.KEY + "set.audio.hosted", WorkshopStrings.duration(audio.durationMs()));
    }

    private static String shortHash(String sha) {
        return sha == null || sha.length() < 12 ? "" : sha.substring(0, 12) + "…";
    }

    private String installLabel(WorkshopDownloader.Status status) {
        WorkshopTr tr = WorkshopTr.INSTANCE;
        return switch (status.state()) {
            case INSTALLED -> tr.get(WorkshopStrings.KEY + "set.installed");
            case UPDATE_AVAILABLE -> tr.get(WorkshopStrings.KEY + "set.update");
            case DOWNLOADING, QUEUED -> tr.get(WorkshopStrings.KEY + "set.downloading");
            case INSTALLING -> tr.get(WorkshopStrings.KEY + "set.installing");
            case UNAVAILABLE -> tr.get(WorkshopStrings.KEY + "set.unavailable");
            case FAILED -> tr.get(WorkshopStrings.KEY + "set.retry");
            default -> tr.get(WorkshopStrings.KEY + "set.install");
        };
    }

    private String installSub(WorkshopDownloader.Status status) {
        if (status.state() == WorkshopDownloader.State.DOWNLOADING && status.totalBytes() > 0) {
            return WorkshopStrings.size(status.receivedBytes()) + " / " + WorkshopStrings.size(status.totalBytes());
        }
        if (detail != null && detail.download() != null && detail.download().size() > 0) {
            return WorkshopStrings.size(detail.download().size());
        }
        return null;
    }

    // ---------------------------------------------------------------- rendering

    @Override
    public void render(GuiGraphics g, double mouseX, double mouseY, float partialTick) {
        // Answers that came back while a dialog or a link confirmation was on top; the comments keep their own queue.
        pump();
        comments.pump();
        if (layout == null) {
            relayout();
            if (layout == null) {
                return;
            }
        }
        // The page follows install progress and ratings, but the expensive text wrapping only runs when something
        // actually changed (the state is a record, so equality is deep).
        SetPageLayout.State next = state();
        if (!next.equals(frameState)) {
            frameState = next;
            layout = SetPageLayout.compute(new Rect(0, 0, area.w(), area.h()), TextStyles.measurer(), WorkshopTr.INSTANCE, next);
            commentsTop = layout.contentHeight() + 6;
            if (showsComments()) {
                comments.layout(area, commentsTop);
            }
            scroll.setExtent(Math.max(0, layout.contentHeight() + comments.height() + 10 - area.h()));
        }
        scroll.update();
        float offset = scroll.snapped(TextStyles.guiScale());
        UiClip.push(g, area.x(), area.y(), area.right(), area.bottom());

        Rect header = shift(layout.rect(SetPageLayout.HEADER), offset);
        ResourceLocation cover = CardRenderer.texture(card == null ? null : card.localCover,
                client.networkAllowed() ? coverUrl() : null);
        if (cover == null && serverPicture != null) {
            cover = serverPicture.get();
        }
        if (cover != null) {
            RoundedImage.draw(g, cover, area.x(), header.y(), area.w(), header.h(), 0F, 0.15F, 1F, 0.85F,
                    Palette.RADIUS_MD, Palette.RADIUS_MD, 0F, 0F, 0.45F, 0x99FFFFFF, 0x66FFFFFF);
        } else {
            WorkshopDraw.panel(g, new Rect(area.x(), header.y(), area.w(), header.h()), Palette.RADIUS_MD, Palette.BG_2);
        }
        WorkshopDraw.panel(g, new Rect(area.x(), header.y(), area.w(), header.h()), Palette.RADIUS_MD,
                Palette.withAlpha(Palette.BG_0, 0.55F));

        for (int i = 0; i < 32; i++) {
            Rect tile = shift(layout.rect("set.tile." + i), offset);
            if (tile.empty()) {
                break;
            }
            boolean hovered = WorkshopDraw.hot(tile, mouseX, mouseY);
            double stars = i < layoutStars().size() ? layoutStars().get(i) : 0;
            RoundedRect.circle(g, tile.centerX(), tile.centerY(), tile.w() * 0.42F, Spectrum.displayArgb(stars));
            if (i == selectedChart || hovered) {
                WorkshopDraw.outline(g, tile, tile.w() * 0.5F, 1.4F, i == selectedChart ? Palette.ACCENT : Palette.TEXT_3);
            }
        }
        drawText(g, "set.chartname", offset, Palette.TEXT_1);
        drawText(g, "set.counters", offset, Palette.TEXT_2);
        drawParagraph(g, "set.title", offset, Palette.TEXT_1);
        List<SetPageLayout.Badge> badges = frameState.badges();
        for (int i = 0; i < badges.size(); i++) {
            Rect badge = shift(layout.rect("set.badge." + i), offset);
            if (badge.empty()) {
                break;
            }
            WorkshopDraw.pill(g, badge, Palette.BG_1);
            drawText(g, "set.badge." + i + ".text", offset, WorkshopDraw.badgeColour(badges.get(i).key()));
        }
        drawText(g, "set.artist", offset, Palette.TEXT_2);

        Rect avatar = shift(layout.rect("set.avatar"), offset);
        String avatarUrl = detail != null && detail.mapper() != null ? detail.mapper().avatarUrl() : null;
        var avatarTexture = client.networkAllowed() && avatarUrl != null ? CoverCache.get(avatarUrl) : null;
        if (avatar.empty()) {
            // A server beatmap's page has no mapper card.
        } else if (avatarTexture != null) {
            RoundedImage.draw(g, avatarTexture, avatar.x(), avatar.y(), avatar.w(), avatar.h(), avatar.w() * 0.5F, 0F, 1F);
        } else {
            WorkshopDraw.panel(g, avatar, avatar.w() * 0.5F, Palette.BG_3);
            WorkshopDraw.icon(g, avatar.inset(3, 3), UiIcon.USER_ROUND, Palette.TEXT_3);
        }
        drawText(g, "set.mapper", offset, Palette.TEXT_2);
        drawText(g, "set.submitted", offset, Palette.TEXT_MUTE);
        drawText(g, "set.updated", offset, Palette.TEXT_MUTE);

        Rect banner = shift(layout.rect("set.banner"), offset);
        if (!banner.empty()) {
            WorkshopDraw.panel(g, banner, Palette.RADIUS_SM, Palette.withAlpha(Palette.BG_0, 0.75F));
            drawParagraph(g, "set.banner.text", offset, Palette.WARN);
        }

        boolean canFavourite = frameState.canFavourite();
        drawButton(g, "set.favourite", offset, mouseX, mouseY, WorkshopDraw.ButtonStyle.SECONDARY, canFavourite);
        Rect favourite = shift(layout.rect("set.favourite"), offset);
        boolean favourited = frameState.favourited();
        int heartColour = favourited ? Palette.DANGER : Palette.TEXT_2;
        if (!favourite.empty()) {
            WorkshopDraw.icon(g, favourite.inset(favourite.w() * 0.28F, favourite.h() * 0.28F),
                    favourited ? UiIcon.HEART_FILLED : UiIcon.HEART,
                    canFavourite ? heartColour : Palette.fade(heartColour, 0.45F));
        }
        boolean canInstall = frameState.canInstall();
        drawButton(g, "set.install", offset, mouseX, mouseY, WorkshopDraw.ButtonStyle.PRIMARY, canInstall);
        int installColour = canInstall ? Palette.TEXT_1 : Palette.fade(Palette.TEXT_1, 0.55F);
        drawText(g, "set.install.text", offset, installColour);
        drawText(g, "set.install.sub", offset, Palette.fade(installColour, 0.75F));
        Rect installBar = shift(layout.rect("set.install.bar"), offset);
        if (!installBar.empty()) {
            WorkshopDraw.bar(g, installBar, client.downloader().status(setId).progress(), Palette.BG_0, Palette.ACCENT);
        }
        drawButton(g, "set.browser", offset, mouseX, mouseY, WorkshopDraw.ButtonStyle.SECONDARY);
        Rect browser = shift(layout.rect("set.browser"), offset);
        if (!browser.empty()) {
            WorkshopDraw.icon(g, browser.inset(browser.w() * 0.28F, browser.h() * 0.28F), UiIcon.EXTERNAL_LINK, Palette.TEXT_2);
        }
        drawButton(g, "set.more", offset, mouseX, mouseY, WorkshopDraw.ButtonStyle.SECONDARY);
        Rect more = shift(layout.rect("set.more"), offset);
        if (!more.empty()) {
            WorkshopDraw.icon(g, more.inset(more.w() * 0.28F, more.h() * 0.28F), UiIcon.SETTINGS, Palette.TEXT_2);
        }

        // Right column.
        Rect right = shift(layout.rect(SetPageLayout.RIGHT), offset);
        WorkshopDraw.panel(g, right, Palette.RADIUS_MD, Palette.withAlpha(Palette.BG_1, 0.8F));
        Rect statusPill = shift(layout.rect("set.status"), offset);
        if (!statusPill.empty()) {
            WorkshopDraw.pill(g, statusPill, WorkshopDraw.statusColour(detail != null ? detail.status() : card != null ? card.status : ""));
        }
        drawText(g, "set.status.text", offset, Palette.ON_PILL);
        Rect preview = shift(layout.rect("set.preview"), offset);
        // A server beatmap's page has no preview bar; drawn from its empty box, the play icon would sit in a corner.
        if (!preview.empty()) {
            boolean previewHovered = WorkshopDraw.hot(preview, mouseX, mouseY);
            WorkshopDraw.panel(g, preview, Palette.RADIUS_SM, previewHovered ? Palette.BG_3 : Palette.withAlpha(Palette.BG_0, 0.5F));
            String previewKey = CardRenderer.previewKey(setId);
            boolean playing = PreviewPlayer.isPlaying(previewKey);
            if (previewWaiting(previewKey)) {
                // Fetching the clip, or decoding it: both used to draw the idle play button, so pressing it looked like it
                // had done nothing at all for as long as the download took.
                WorkshopDraw.spinner(g, preview.centerX(), preview.centerY(), 5F, Palette.ACCENT);
            } else if (playing) {
                WorkshopDraw.stopSquare(g, preview.centerX(), preview.centerY(), 6F, Palette.ACCENT);
                WorkshopDraw.bar(g, shift(layout.rect("set.preview.bar"), offset), PreviewPlayer.progress(previewKey),
                        Palette.BG_0, Palette.ACCENT);
            } else if (previewProblem != null) {
                // The clip is not coming. A play button that answers nothing is the control lying about what it has, so
                // the bar carries the reason instead; pressing it again still tries.
                TextStyles.queueCentered(g, TextStyles.ellipsize(TextStyles.text(previewProblem, TextStyle.CAPTION),
                                preview.w() - 8), preview.centerX(),
                        TextStyles.centeredCapY(TextStyle.CAPTION, preview.y(), preview.h()), Palette.TEXT_MUTE);
            } else {
                WorkshopDraw.icon(g, new Rect(preview.centerX() - 4, preview.centerY() - 4, 8, 8), UiIcon.PLAY,
                        previewHovered ? Palette.ACCENT : Palette.TEXT_2);
            }
        }
        for (int i = 0; i < 8; i++) {
            drawText(g, "set.basic." + i + ".label", offset, Palette.TEXT_MUTE);
            drawText(g, "set.basic." + i + ".value", offset, Palette.ST_FEATURED);
        }
        List<SetPageLayout.Attribute> attributes = frameState.attributes();
        for (int i = 0; i < attributes.size(); i++) {
            drawText(g, "set.attr." + i + ".name", offset, Palette.TEXT_3);
            Rect bar = shift(layout.rect("set.attr." + i + ".bar"), offset);
            WorkshopDraw.bar(g, bar, attributes.get(i).fraction(), Palette.withAlpha(Palette.TEXT_1, 0.25F),
                    i == attributes.size() - 1 ? Palette.ST_FEATURED : Palette.TEXT_1);
            drawText(g, "set.attr." + i + ".value", offset, Palette.TEXT_2);
        }
        Rect ratingBox = shift(layout.rect("set.rating"), offset);
        if (!ratingBox.empty()) {
            WorkshopDraw.panel(g, ratingBox, Palette.RADIUS_SM, Palette.withAlpha(Palette.BG_0, 0.5F));
        }
        drawText(g, "set.rating.title", offset, Palette.TEXT_2);
        List<Integer> histogram = frameState.ratingHistogram();
        long total = 0;
        long negative = 0;
        for (int i = 0; i < histogram.size(); i++) {
            total += histogram.get(i);
            if (i < 5) {
                negative += histogram.get(i);
            }
        }
        Rect ratingBar = shift(layout.rect("set.rating.bar"), offset);
        if (!ratingBar.empty()) {
            WorkshopDraw.bar(g, ratingBar, total == 0 ? 0F : (float) negative / total, Palette.ACCENT, Palette.RATING_NEG);
        }
        drawText(g, "set.rating.negative", offset, Palette.RATING_NEG);
        drawText(g, "set.rating.positive", offset, Palette.ACCENT);
        drawText(g, "set.rating.spread", offset, Palette.TEXT_MUTE);
        int max = 1;
        for (int value : histogram) {
            max = Math.max(max, value);
        }
        for (int i = 0; i < 10; i++) {
            Rect bar = shift(layout.rect("set.rating.hist." + i), offset);
            if (bar.empty()) {
                break;
            }
            float fraction = i < histogram.size() ? (float) histogram.get(i) / max : 0F;
            float height = Math.max(1.5F, bar.h() * fraction);
            WorkshopDraw.panel(g, new Rect(bar.x(), bar.bottom() - height, bar.w(), height), 0.8F, Palette.RATING_BAR);
        }
        drawText(g, "set.rating.mine", offset, Palette.TEXT_2);
        drawParagraph(g, "set.rating.cannot", offset, Palette.TEXT_MUTE);
        Integer mine = frameState.myRating();
        for (int i = 1; i <= 10; i++) {
            Rect segment = shift(layout.rect("set.rate." + i), offset);
            if (segment.empty()) {
                break;
            }
            boolean hovered = WorkshopDraw.hot(segment, mouseX, mouseY);
            boolean filled = mine != null && mine >= i;
            WorkshopDraw.panel(g, segment, 1F, filled ? Palette.ACCENT_STRONG : hovered ? Palette.BG_4 : Palette.BG_3);
        }
        drawText(g, "set.rate.remove", offset, Palette.LINK);

        // Info section.
        Rect info = shift(layout.rect(SetPageLayout.INFO), offset);
        WorkshopDraw.panel(g, info, Palette.RADIUS_MD, Palette.withAlpha(Palette.BG_1, 0.85F));
        drawText(g, "set.info.description", offset, Palette.TEXT_1);
        drawParagraph(g, "set.desc", offset, Palette.TEXT_2);
        drawParagraph(g, "set.spoiler.note", offset, Palette.WARN);
        drawButton(g, "set.spoiler.reveal", offset, mouseX, mouseY, WorkshopDraw.ButtonStyle.SECONDARY);
        drawText(g, "set.spoiler.reveal.text", offset, Palette.TEXT_1);
        drawParagraph(g, "set.featured_note", offset, Palette.ST_FEATURED);
        drawText(g, "set.credits.title", offset, Palette.TEXT_1);
        drawParagraph(g, "set.credits", offset, Palette.TEXT_3);
        for (int i = 0; i < 8; i++) {
            drawText(g, "set.meta." + i + ".label", offset, Palette.TEXT_1);
            drawParagraph(g, "set.meta." + i + ".value", offset, Palette.TEXT_3);
        }
        drawText(g, "set.audio.label", offset, Palette.TEXT_1);
        drawParagraph(g, "set.audio", offset, Palette.TEXT_3);
        drawText(g, "set.charts.title", offset, Palette.TEXT_1);
        List<SetPageLayout.ChartRow> charts = frameState.charts();
        for (int i = 0; i < charts.size(); i++) {
            Rect row = shift(layout.rect("set.chart." + i), offset);
            if (row.empty()) {
                break;
            }
            boolean hovered = WorkshopDraw.hot(row, mouseX, mouseY);
            if (hovered || i == selectedChart) {
                WorkshopDraw.panel(g, row, Palette.RADIUS_SM, i == selectedChart ? Palette.BG_3 : Palette.withAlpha(Palette.BG_3, 0.6F));
            }
            Rect star = shift(layout.rect("set.chart." + i + ".star"), offset);
            StarPill.shapes(g, star.x(), star.y() + (star.h() - StarPill.HEIGHT) * 0.5F, charts.get(i).stars());
            StarPill.text(g, star.x(), star.y() + (star.h() - StarPill.HEIGHT) * 0.5F, charts.get(i).stars());
            drawText(g, "set.chart." + i + ".name", offset, Palette.TEXT_2);
            drawText(g, "set.chart." + i + ".notes", offset, Palette.TEXT_MUTE);
            drawText(g, "set.chart." + i + ".holds", offset, Palette.TEXT_MUTE);
            drawText(g, "set.chart." + i + ".length", offset, Palette.TEXT_MUTE);
        }

        if (showsComments()) {
            comments.render(g, offset, mouseX, mouseY);
        }
        if (detail == null && loading) {
            WorkshopDraw.spinner(g, area.centerX(), area.y() + 20, 6F, Palette.ACCENT);
        }
        UiClip.pop(g);
        WorkshopDraw.scrollbar(g, area, offset, layout.contentHeight() + comments.height() + 10, false);
    }

    private List<Double> layoutStars() {
        return frameState == null ? List.of() : frameState.chartStars();
    }

    private String coverUrl() {
        if (detail != null && detail.covers() != null) {
            return detail.covers().cover() != null ? detail.covers().cover() : detail.covers().card();
        }
        return card == null ? null : card.bodyCoverUrl;
    }

    private void drawButton(GuiGraphics g, String id, float offset, double mouseX, double mouseY, WorkshopDraw.ButtonStyle style) {
        drawButton(g, id, offset, mouseX, mouseY, style, true);
    }

    /** A disabled button still answers a click (it explains itself), but it does not offer the hand cursor. */
    private void drawButton(GuiGraphics g, String id, float offset, double mouseX, double mouseY, WorkshopDraw.ButtonStyle style,
                            boolean enabled) {
        Rect rect = shift(layout.rect(id), offset);
        if (rect.empty()) {
            return;
        }
        boolean hovered = enabled ? WorkshopDraw.hot(rect, mouseX, mouseY) : WorkshopDraw.hover(rect, mouseX, mouseY);
        WorkshopDraw.button(g, rect, style, hovered, enabled);
    }

    private void drawText(GuiGraphics g, String id, float offset, int colour) {
        Layout.Box box = layout.box(id);
        if (box == null || box.text() == null || box.text().isEmpty()) {
            return;
        }
        Rect rect = shift(box.rect(), offset);
        if (rect.bottom() < area.y() - 10 || rect.y() > area.bottom() + 10) {
            return;
        }
        TextStyles.queue(g, TextStyles.text(box.text(), box.style() == null ? TextStyle.BODY : box.style()).getVisualOrderText(),
                rect.x(), rect.y(), colour);
    }

    private void drawParagraph(GuiGraphics g, String id, float offset, int colour) {
        for (Layout.Box box : layout.group(id + ".")) {
            drawText(g, box.id(), offset, colour);
        }
    }

    private Rect shift(Rect rect, float offset) {
        return rect.empty() ? rect : new Rect(area.x() + rect.x(), area.y() + rect.y() - offset, rect.w(), rect.h());
    }

    // ---------------------------------------------------------------- input

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (layout == null || button != GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            return false;
        }
        float offset = scroll.snapped(TextStyles.guiScale());
        if (comments.mouseClicked(mouseX, mouseY, button, offset)) {
            return true;
        }
        for (int i = 0; i < 32; i++) {
            if (hit("set.tile." + i, offset, mouseX, mouseY)) {
                selectedChart = i;
                screen.click();
                return true;
            }
        }
        for (int i = 0; i < 32; i++) {
            if (hit("set.chart." + i, offset, mouseX, mouseY)) {
                selectedChart = i;
                screen.click();
                return true;
            }
        }
        if (serverTrack()) {
            // Every control below asks the Workshop about this set id, and an editor-made track's negative id names
            // nothing there, so they stay quiet instead of failing with the site's error.
            return false;
        }
        if (hit("set.spoiler.reveal", offset, mouseX, mouseY)) {
            spoilerRevealed = true;
            screen.click();
            relayout();
            return true;
        }
        if (hit("set.favourite", offset, mouseX, mouseY)) {
            toggleFavourite();
            return true;
        }
        if (hit("set.install", offset, mouseX, mouseY)) {
            WorkshopScreen.PrimaryAction primary = screen.primaryAction(setId, card, detail, selectedChart);
            if (primary != null) {
                primary.press().run();
            } else {
                install();
            }
            return true;
        }
        if (hit("set.browser", offset, mouseX, mouseY)) {
            LinkActions.openTrusted(screen, client.api().pageUrl("/beatmapsets/" + setId));
            return true;
        }
        if (hit("set.more", offset, mouseX, mouseY)) {
            openMore();
            return true;
        }
        if (hit("set.preview", offset, mouseX, mouseY)) {
            togglePreview();
            return true;
        }
        for (int i = 1; i <= 10; i++) {
            if (hit("set.rate." + i, offset, mouseX, mouseY)) {
                rate(i);
                return true;
            }
        }
        if (hit("set.rate.remove", offset, mouseX, mouseY)) {
            rate(null);
            return true;
        }
        return false;
    }

    private boolean hit(String id, float offset, double mouseX, double mouseY) {
        return shift(layout.rect(id), offset).contains(mouseX, mouseY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        if (!area.contains(mouseX, mouseY)) {
            return false;
        }
        scroll.onWheel(amount);
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        return comments.keyPressed(keyCode, scanCode, modifiers);
    }

    /**
     * Nothing on this page is lost by leaving it: the comment composer keeps its draft on every keystroke and gives it
     * back when the page opens again. So the "discard what you typed?" question would be asked over text that is not
     * being discarded, and after a comment was posted it was asked over an empty box.
     */
    @Override
    public boolean hasUnsavedText(boolean typed) {
        return false;
    }

    @Override
    public boolean charTyped(char character, int modifiers) {
        return comments.charTyped(character, modifiers);
    }

    // ---------------------------------------------------------------- actions

    private void togglePreview() {
        String key = CardRenderer.previewKey(setId);
        if (PreviewPlayer.isPlaying(key) || screen.previewFetching(setId)) {
            // Stop also cancels a clip still on its way, so pressing the spinner does what it shows.
            screen.stopPreview();
            previewRequestedMs = 0L;
            previewAsked = false;
            return;
        }
        previewProblem = null;
        // A failure left over from an earlier press belongs to that press.
        screen.takePreviewFailure(setId);
        PreviewPlayer.takeFailure(key);
        previewAsked = true;
        if (card != null && CardModel.fileExists(card.localPreview)) {
            previewRequestedMs = 0L;
            PreviewPlayer.play(key, card.localPreview, card.previewDurationMs > 0 ? card.previewDurationMs
                    : PreviewPlayer.DEFAULT_DURATION_MS, client.previewVolume());
            return;
        }
        if (!client.networkAllowed()) {
            // Install answers this press with the consent dialog, and so does this one: a play button that does
            // nothing at all while the network is off is the control lying about what it has. The line is set behind
            // the dialog as well, so a refusal still leaves the reason on the bar.
            previewProblem = WorkshopTr.INSTANCE.get(WorkshopStrings.KEY + "set.preview_offline");
            askNetwork(this::togglePreview);
            return;
        }
        String url = detail != null && detail.preview() != null ? detail.preview().ogg() : card == null ? null : card.previewUrl;
        long duration = detail != null && detail.preview() != null ? detail.preview().durationMs()
                : card == null ? PreviewPlayer.DEFAULT_DURATION_MS : card.previewDurationMs;
        if (url == null || url.isEmpty()) {
            // Nothing has said there is a clip. While the page itself is still on its way there is no answer yet,
            // and its own spinner says so, so the bar keeps quiet rather than denying a preview it may still get.
            previewProblem = loading ? null : WorkshopTr.INSTANCE.get(WorkshopStrings.KEY + "set.preview_none");
            return;
        }
        previewRequestedMs = System.currentTimeMillis();
        screen.fetchPreview(setId, url, duration);
    }

    /**
     * Whether the clip is on its way: the fetch this page asked for has not reached the player yet, or it has and the
     * decoder has not started the channel. A failed download ({@code WorkshopScreen.takePreviewFailure}) or a clip the
     * player could not play ({@code PreviewPlayer.takeFailure}) becomes a reason on the bar on the frame it is known;
     * the 20-second wait is left only for a request that never answers at all.
     */
    private boolean previewWaiting(String key) {
        if (PreviewPlayer.isPlaying(key)) {
            // The clip arrived; only the decoder can still be behind. A clip that beat the wait by a hair leaves a
            // reason behind it, and the bar would carry "it did not arrive" again the moment the music stopped.
            previewRequestedMs = 0L;
            previewProblem = null;
            return PreviewPlayer.isLoading(key);
        }
        // The download failed, or the clip came and could not be played: say so now rather than spin out the wait.
        // Both answers are read every frame, so either one ends the spinner on the frame it arrives.
        boolean fetchFailed = previewAsked && screen.takePreviewFailure(setId);
        boolean playFailed = previewAsked && PreviewPlayer.takeFailure(key);
        if (fetchFailed || playFailed) {
            previewAsked = false;
            previewRequestedMs = 0L;
            // A clip that came and would not play did arrive: it is told apart from one that never came.
            previewProblem = WorkshopTr.INSTANCE.get(WorkshopStrings.KEY
                    + (fetchFailed ? "set.preview_failed" : "set.preview_unplayable"));
            return false;
        }
        if (previewRequestedMs == 0L) {
            return false;
        }
        if (System.currentTimeMillis() - previewRequestedMs > PREVIEW_WAIT_MS) {
            previewRequestedMs = 0L;
            previewProblem = WorkshopTr.INSTANCE.get(WorkshopStrings.KEY + "set.preview_failed");
            return false;
        }
        return true;
    }

    private void toggleFavourite() {
        if (!client.session().signedIn()) {
            screen.openAccount();
            return;
        }
        if (!canFavourite()) {
            showRefusal("favourite");
            return;
        }
        boolean wanted = !favourited();
        screen.run(() -> {
            try {
                WorkshopDtos.FavouriteState result = client.session()
                        .authorized(api -> api.favourite(setId, wanted, CancelToken.none()));
                deliver(() -> applyFavourite(result));
            } catch (WorkshopApiException exception) {
                screen.showError(exception.code());
            }
        });
    }

    /**
     * The heart's answer, applied where it belongs. Reloading the whole page for one favourite threw away the loaded
     * comment thread with it - the reader watched the discussion collapse for pressing a heart - so the endpoint's own
     * count and flag are kept here, and written into the card the listing holds behind the page so both agree.
     */
    private void applyFavourite(WorkshopDtos.FavouriteState result) {
        favouriteOverride = result.favourited();
        favouriteCountOverride = Math.max(0L, result.count());
        if (card != null) {
            card.favourited = favouriteOverride;
            card.favourites = favouriteCountOverride;
        }
        relayout();
    }

    /**
     * Asks for the network, remembering what the press was for.
     *
     * <p>The consent dialog answers to the Workshop, not to this page, so allowing it used to leave the page exactly
     * as it was with the press spent on the question: Install had to be pressed a second time, and the first press
     * looked like it had failed. {@link #setArea} runs the remembered press once the answer is in.
     */
    private void askNetwork(Runnable retry) {
        afterConsent = retry;
        screen.openConsent();
    }

    private void install() {
        WorkshopDownloader.Status status = client.downloader().status(setId);
        switch (status.state()) {
            case QUEUED, DOWNLOADING -> client.downloader().cancel(setId);
            case INSTALLED -> screen.onCatalogChanged();
            case UNAVAILABLE -> screen.showError(WorkshopApiException.UNAVAILABLE);
            default -> {
                // The site already said it will not hand this package out; its reason beats a download that comes
                // back as a refusal a moment later.
                if (!serverOffersDownload()) {
                    showRefusal("download");
                    return;
                }
                if (!client.networkAllowed()) {
                    askNetwork(this::install);
                    return;
                }
                client.downloader().enqueue(setId);
            }
        }
    }

    private void rate(Integer value) {
        if (!client.session().signedIn()) {
            screen.openAccount();
            return;
        }
        screen.run(() -> {
            try {
                WorkshopDtos.RatingSummary summary = client.session().authorized(api -> api.rate(setId, value, CancelToken.none()));
                deliver(() -> {
                    rating = summary;
                    relayout();
                });
            } catch (WorkshopApiException exception) {
                if (!screen.handleRulesRefusal(exception.code(), () -> rate(value))) {
                    screen.showError(exception.code());
                }
            }
        });
    }

    /** The ⋯ menu: report, copy link, delete the local copy, open the set folder. */
    private void openMore() {
        WorkshopCatalog.InstalledSet installed = client.catalog().bySetId(setId).orElse(null);
        boolean canUpdate = detail != null && detail.can() != null && Boolean.TRUE.equals(detail.can().get("updateContent"));
        WorkshopMoreScreen dialog = new WorkshopMoreScreen(screen, client, setId,
                detail != null ? detail.title() : card != null ? card.title : "#" + setId, installed != null,
                card != null && card.needsAudio(), canUpdate);
        screen.showDialog(dialog);
    }
}

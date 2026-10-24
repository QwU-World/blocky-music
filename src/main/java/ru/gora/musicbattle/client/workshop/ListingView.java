package ru.gora.musicbattle.client.workshop;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.glfw.GLFW;
import ru.gora.musicbattle.client.ui.CardGrid;
import ru.gora.musicbattle.client.ui.CoverCache;
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
import ru.gora.musicbattle.client.workshop.layout.BattleSetup;
import ru.gora.musicbattle.client.workshop.layout.BattleTracks;
import ru.gora.musicbattle.client.workshop.layout.CardLayout;
import ru.gora.musicbattle.client.workshop.layout.Filters;
import ru.gora.musicbattle.client.workshop.layout.Layout;
import ru.gora.musicbattle.client.workshop.layout.ListingLayout;
import ru.gora.musicbattle.client.workshop.layout.Metrics;
import ru.gora.musicbattle.client.workshop.layout.Rect;
import ru.gora.musicbattle.client.workshop.layout.TrackCardLayout;
import ru.gora.musicbattle.client.workshop.layout.WorkshopStrings;
import ru.gora.musicbattle.network.ServerBeatmapsPagePayload;
import ru.gora.musicbattle.workshop.WorkshopCatalog;
import ru.gora.musicbattle.workshop.api.CancelToken;
import ru.gora.musicbattle.workshop.api.WorkshopApi;
import ru.gora.musicbattle.workshop.api.WorkshopApiException;
import ru.gora.musicbattle.workshop.api.WorkshopDownloader;
import ru.gora.musicbattle.workshop.api.WorkshopDtos;

/**
 * The Browse and Installed tabs (spec 06 section 6.1): the search panel with the website's filter rows, the sort bar,
 * a virtualised {@link CardGrid} of cards with previews and install states, infinite scrolling by cursor and the
 * loading, empty, offline and consent states.
 *
 * <p>Requests are debounced (500 ms for typing, 100 ms for a filter), one at a time, and the next page is asked for
 * when the scroll comes within two rows of the end and at least 500 ms after the last one (lazer's rules).
 *
 * <p>In a battle's map picker every card's primary action is Choose: the card, its strip button, a difficulty in its
 * dropdown and Enter all hand the map to the battle, and the Server tab adds the tracks the server offers outside the
 * Workshop as cards of their own ({@link TrackCardLayout}).
 */
public final class ListingView {
    private static final long TEXT_DEBOUNCE_MS = 500;
    private static final long FILTER_DEBOUNCE_MS = 100;
    private static final long PAGE_INTERVAL_MS = 500;
    private static final long COVER_DELAY_MS = 350;
    private static final long EXPAND_DELAY_MS = 100;
    /** The strip button that takes the install button's place on a Workshop card in a battle's picker. */
    private static final String CHOOSE = "card.choose";
    /** How long the Server tab outside a battle reuses the server's track list before asking for it again. */
    private static final long SERVER_TRACKS_REFRESH_MS = 10_000L;
    /** How long a battle's picker waits for the Minecraft server's beatmap list before it shows the sets itself. */
    private static final long BATTLE_WAIT_MS = 3000L;

    private final WorkshopScreen screen;
    private final WorkshopClient client;
    private final boolean installed;
    /** The Server beatmaps tab (spec 06 section 9.1): the cards come from the Minecraft server, not the website. */
    private final boolean serverTab;
    private final TextField search = TextField.singleLine();
    private final Motion.SmoothScroll scroll = new Motion.SmoothScroll();
    private CardGrid grid;
    private final Map<Long, CardState> states = new HashMap<>();
    private final AtomicInteger generation = new AtomicInteger();

    private String category = Filters.CATEGORY_DEFAULT;
    private final Set<String> general = new LinkedHashSet<>();
    private int genre;
    private int language;
    private String audio = Filters.AUDIO_ANY;
    private final Set<String> extra = new LinkedHashSet<>();
    private boolean explicit;
    private String sort;
    private boolean sortAscending;
    private boolean moreFilters;
    private boolean extraCards;
    private String installedSort = "title";

    private List<CardModel> cards = new ArrayList<>();
    private String cursor;
    private boolean loading;
    private boolean hasMore;
    private String status = "";
    private String statusDetail = "";
    private Long directSetId;
    private long pendingSearchAt;
    private long lastPageAt;
    /**
     * Not before this instant may another page be asked for.
     *
     * A page that failed used to leave the clock at the last page that succeeded, so the "nearly at the end of the
     * list" test was true again on the very next frame: one lost connection turned into a request every frame, for
     * as long as the player left the screen open. Each failure now waits longer than the last, and a refusal the
     * site will keep refusing stops the paging altogether and offers the retry button instead.
     */
    private long nextPageAt;
    private int pageFailures;
    private Layout layout;
    private Rect area = Rect.EMPTY;
    private int focusIndex = -1;
    private long expandedSetId;
    private long hoverSinceNanos;
    private long hoveredSetId;
    private boolean draggingScrollbar;
    /** When a battle's picker asked the server for its beatmap list, or 0 once it answered; and the search it was for. */
    private long battleAskedAtMs;
    /**
     * The Server tab outside a battle: every track the server offers, when that list was last asked for (0 for never),
     * and the beatmap page of the last search, or null while it is on its way.
     */
    private BattleTracks serverTracks = BattleTracks.NONE;
    private long serverTracksAskedAtMs;
    private List<CardModel> serverPage;
    private String serverQuery = "";
    private String battleQuery = "";

    public ListingView(WorkshopScreen screen, WorkshopClient client, boolean installed) {
        this(screen, client, installed, false);
    }

    public ListingView(WorkshopScreen screen, WorkshopClient client, boolean installed, boolean serverTab) {
        this.screen = screen;
        this.client = client;
        this.installed = installed;
        this.serverTab = serverTab;
        this.grid = new CardGrid(Metrics.CARD_MIN_W, Metrics.CARD_NORMAL_H, Metrics.CARD_GAP, Metrics.CARD_MAX_COLUMNS);
        search.setMaxLength(200);
        search.setPlaceholder(WorkshopTr.INSTANCE.get(WorkshopStrings.KEY + (installed ? "search.installed" : "search.hint")));
        search.setOnChange(() -> {
            directSetId = null;
            scheduleSearch(TEXT_DEBOUNCE_MS);
        });
        search.setOnSubmit(this::onSearchSubmit);
        moreFilters = false;
        // The tabs that sort their own list open on Title A to Z, so their arrow starts pointing up; Browse opens on
        // the website's descending default (the newest, the most downloaded first).
        sortAscending = installed || serverTab;
    }

    /** Per-card animation and cover timing. */
    private static final class CardState {
        final Motion.Tween hover = new Motion.Tween(0.0);
        boolean hovered;
        long visibleSinceNanos;
        Layout layout;
    }

    public boolean installedTab() {
        return installed;
    }

    public TextField searchField() {
        return search;
    }

    public List<CardModel> cards() {
        return cards;
    }

    public void setArea(Rect value) {
        area = value;
        relayout();
    }

    public void focusSearch() {
        search.setFocused(true);
    }

    /** Opens the view (tab switch or screen open): first load, catalogue refresh. */
    public void onShow() {
        if (serverTab) {
            reloadServer();
        } else if (installed) {
            reloadInstalled();
        } else if (cards.isEmpty() && !loading) {
            scheduleSearch(0);
        }
        relayout();
    }

    public void onHide() {
        stopPreview();
    }

    // ---------------------------------------------------------------- layout

    public void relayout() {
        if (area.empty()) {
            return;
        }
        ListingLayout.State state = state();
        layout = ListingLayout.compute(area, TextStyles.measurer(), WorkshopTr.INSTANCE, state);
        if (serverTab && screen.picker() != null) {
            layout = BattleTracks.pickerChrome(layout);
        }
        Rect field = layout.rect("listing.search.field");
        search.setBounds(area.x() + field.x(), area.y() + field.y(), field.w(), field.h());
        Rect gridRect = layout.rect(ListingLayout.GRID);
        float wanted = extraCards ? Metrics.CARD_EXTRA_H : Metrics.CARD_NORMAL_H;
        if (grid.cardHeight() != wanted) {
            grid = new CardGrid(Metrics.CARD_MIN_W, wanted, Metrics.CARD_GAP, Metrics.CARD_MAX_COLUMNS);
        }
        grid.layout(area.x(), area.y(), area.w(), area.h(), cards.size());
        scroll.setExtent(Math.max(0, layout.contentHeight() - area.h()));
        this.gridTop = gridRect.y();
        this.cardHeight = extraCards ? Metrics.CARD_EXTRA_H : Metrics.CARD_NORMAL_H;
    }

    private float gridTop;
    private float cardHeight = Metrics.CARD_NORMAL_H;


    private ListingLayout.State state() {
        return new ListingLayout.State(installed || serverTab, search.value(), category, general, genre, language, audio, extra, explicit,
                sort, sortAscending, moreFilters, extraCards, WorkshopServerHooks.serverBeatmapsAvailable(), installedSort,
                directSetId, status, statusDetail, cards.size(), loading, hasMore);
    }

    // ---------------------------------------------------------------- rendering

    public void render(GuiGraphics g, double mouseX, double mouseY, float partialTick) {
        if (layout == null) {
            relayout();
            if (layout == null) {
                return;
            }
        }
        maybeRunScheduledSearch();
        maybeStopWaitingForServer();
        scroll.update();
        float offset = scroll.snapped(TextStyles.guiScale());
        float gridScroll = offset - gridTop;

        UiClip.push(g, area.x(), area.y(), area.right(), area.bottom());
        drawChrome(g, offset, mouseX, mouseY);
        drawCards(g, offset, gridScroll, mouseX, mouseY, partialTick);
        UiClip.pop(g);
        WorkshopDraw.scrollbar(g, area, offset, layout.contentHeight(), draggingScrollbar);
        maybeLoadMore(gridScroll);
    }

    private void drawChrome(GuiGraphics g, float offset, double mouseX, double mouseY) {
        Rect searchPanel = shifted(layout.rect(ListingLayout.SEARCH), offset);
        WorkshopDraw.panel(g, searchPanel, Palette.RADIUS_MD, Palette.BG_2);
        drawSearchPanelCover(g, searchPanel);
        Rect sortBar = shifted(layout.rect(ListingLayout.SORT_BAR), offset);
        WorkshopDraw.panel(g, sortBar, Palette.RADIUS_SM, Palette.withAlpha(Palette.BG_2, 0.75F));

        for (Layout.Box box : layout.boxes()) {
            String id = box.id();
            if (box.kind() == Layout.Kind.TEXT || box.kind() == Layout.Kind.PANEL) {
                continue;
            }
            Rect rect = shifted(box.rect(), offset);
            if (id.startsWith("listing.filters.") && box.kind() == Layout.Kind.BUTTON) {
                boolean hovered = WorkshopDraw.hot(rect, mouseX, mouseY);
                boolean active = id.endsWith(".more") ? moreFilters : activeOption(id);
                if (active || hovered) {
                    WorkshopDraw.pill(g, rect, active ? Palette.mix(Palette.BG_4, Palette.ACCENT_DEEP, 0.45F) : Palette.BG_3);
                }
            } else if (id.startsWith("listing.filters.") && box.kind() == Layout.Kind.ICON && id.endsWith(".clear")) {
                // The ⊗ of an active multi-select option (lazer's filter pill).
                WorkshopDraw.icon(g, rect, UiIcon.X, Palette.TEXT_1);
            } else if (id.startsWith("listing.sort.") && box.kind() == Layout.Kind.BUTTON) {
                boolean hovered = WorkshopDraw.hot(rect, mouseX, mouseY);
                boolean active = id.equals("listing.sort." + effectiveSort());
                if (active || hovered) {
                    WorkshopDraw.panel(g, rect, Palette.RADIUS_SM, active ? Palette.BG_4 : Palette.BG_3);
                }
            } else if (id.equals("listing.cards.normal") || id.equals("listing.cards.extra")) {
                boolean active = id.endsWith("extra") == extraCards;
                boolean hovered = WorkshopDraw.hot(rect, mouseX, mouseY);
                WorkshopDraw.panel(g, rect, Palette.RADIUS_SM, active || hovered ? Palette.BG_4 : Palette.BG_3);
                WorkshopDraw.cardSizeIcon(g, rect, id.endsWith("extra"), active ? Palette.ACCENT : Palette.TEXT_3);
            } else if (id.equals("listing.tool.import") || id.equals("listing.tool.inbox") || id.equals("listing.direct")
                    || id.equals("listing.state.action")) {
                boolean hovered = WorkshopDraw.hot(rect, mouseX, mouseY);
                WorkshopDraw.button(g, rect, WorkshopDraw.ButtonStyle.SECONDARY, hovered, true);
            }
        }

        for (Layout.Box box : layout.boxes()) {
            if (box.kind() != Layout.Kind.TEXT) {
                continue;
            }
            Rect rect = shifted(box.rect(), offset);
            if (rect.bottom() < area.y() - 10 || rect.y() > area.bottom() + 10) {
                continue;
            }
            int colour = textColour(box.id());
            TextStyles.queue(g, TextStyles.text(box.text(), box.style() == null ? TextStyle.BODY : box.style()).getVisualOrderText(),
                    rect.x(), rect.y(), colour);
        }

        Rect icon = shifted(layout.rect("listing.search.icon"), offset);
        WorkshopDraw.icon(g, icon, loading ? UiIcon.LOADER : UiIcon.SEARCH, loading ? Palette.ACCENT : Palette.TEXT_MUTE);
        Rect caret = shifted(layout.rect("listing.sort." + effectiveSort() + ".caret"), offset);
        if (!caret.empty()) {
            // The icon atlas has no chevron pointing up, so the ascending one is the down chevron mirrored about the
            // caret's middle. A sideways arrow left the player guessing which end of the list they were looking at.
            if (sortAscending) {
                g.pose().pushPose();
                g.pose().translate(0F, caret.centerY(), 0F);
                g.pose().scale(1F, -1F, 1F);
                g.pose().translate(0F, -caret.centerY(), 0F);
            }
            WorkshopDraw.icon(g, caret, UiIcon.CHEVRON_DOWN, Palette.ACCENT);
            if (sortAscending) {
                g.pose().popPose();
            }
        }
        // The search field draws itself (caret, selection) at its current offset.
        Rect field = layout.rect("listing.search.field");
        search.setBounds(area.x() + field.x(), area.y() + field.y() - offset, field.w(), field.h());
        if (field.y() - offset + field.h() > 0) {
            search.render(g, mouseX, mouseY);
        }
    }

    /** The cover of the first result sits behind the search panel at 10 % alpha, like lazer's listing. */
    private void drawSearchPanelCover(GuiGraphics g, Rect panel) {
        if (cards.isEmpty() || panel.empty()) {
            return;
        }
        CardModel first = cards.get(0);
        var texture = CardRenderer.texture(first.localCover, client.networkAllowed() ? first.bodyCoverUrl : null);
        if (texture == null) {
            return;
        }
        ru.gora.musicbattle.client.ui.RoundedImage.draw(g, texture, panel.x(), panel.y(), panel.w(), panel.h(),
                0F, 0.25F, 1F, 0.75F, Palette.RADIUS_MD, Palette.RADIUS_MD, Palette.RADIUS_MD, Palette.RADIUS_MD, 0.2F,
                0x1AFFFFFF, 0x1AFFFFFF);
    }

    private int textColour(String id) {
        if (id.startsWith("listing.filters.") && id.endsWith(".text")) {
            return activeOption(id.substring(0, id.length() - 5)) ? Palette.TEXT_1 : Palette.TEXT_3;
        }
        if (id.startsWith("listing.sort.") && id.endsWith(".text")) {
            return id.equals("listing.sort." + effectiveSort() + ".text") ? Palette.TEXT_1 : Palette.TEXT_3;
        }
        if (id.startsWith("listing.state.title")) {
            return Palette.TEXT_1;
        }
        if (id.equals("listing.picker.hint")) {
            return Palette.ACCENT;
        }
        if (id.startsWith("listing.state.detail") || id.equals("listing.results")) {
            return Palette.TEXT_MUTE;
        }
        if (id.endsWith(".label")) {
            return Palette.TEXT_MUTE;
        }
        return Palette.TEXT_2;
    }

    private boolean activeOption(String buttonId) {
        if (buttonId.equals("listing.filters.more")) {
            // Collapsed advanced rows go on filtering, so the button stays lit while any of them is set.
            return moreFilters || state().advancedFilters() > 0;
        }
        int optIndex = buttonId.indexOf(".opt.");
        if (optIndex < 0) {
            return false;
        }
        String rowId = buttonId.substring("listing.filters.".length(), optIndex);
        String optionId = buttonId.substring(optIndex + 5);
        for (Filters.Row row : Filters.rows(WorkshopTr.INSTANCE, WorkshopServerHooks.serverBeatmapsAvailable())) {
            if (row.id().equals(rowId)) {
                return ListingLayout.isActive(state(), row, optionId);
            }
        }
        return false;
    }

    private void drawCards(GuiGraphics g, float offset, float gridScroll, double mouseX, double mouseY, float partialTick) {
        if (cards.isEmpty()) {
            return;
        }
        grid.setCount(cards.size());
        int first = grid.firstVisible(gridScroll);
        int end = grid.endPrepared(gridScroll, 1);
        long now = Util.getNanos();
        int hoverIndex = hitCard(mouseX, mouseY, gridScroll);
        WorkshopScreen.Picker picker = screen.picker();
        List<Layout> layouts = new ArrayList<>();
        List<CardModel> visible = new ArrayList<>();
        List<CardRenderer.Context> contexts = new ArrayList<>();
        List<TrackCard> trackCards = new ArrayList<>();

        for (int i = first; i < end && i < cards.size(); i++) {
            CardModel model = cards.get(i);
            CardState state = states.computeIfAbsent(model.setId, id -> new CardState());
            boolean hovered = i == hoverIndex || overOwnDropdown(model, state, mouseX, mouseY);
            if (hovered != state.hovered) {
                state.hovered = hovered;
                state.hover.animateTo(hovered ? 1.0 : 0.0, hovered ? Palette.T_CARD : Palette.T_CARD / 3.0,
                        Motion.Easing.OUT_QUINT, hovered ? 60.0 : 0.0);
                if (hovered) {
                    hoveredSetId = model.setId;
                    hoverSinceNanos = now;
                } else if (expandedSetId == model.setId) {
                    expandedSetId = 0;
                }
            }
            if (state.visibleSinceNanos == 0) {
                state.visibleSinceNanos = now;
            }
            Rect rect = new Rect(grid.cardX(i), grid.cardY(i, gridScroll), grid.cardWidth(), cardHeight);
            BattleTracks.Entry entry = listedTracks(picker).entry(model.setId);
            if (entry != null) {
                Layout trackLayout = TrackCardLayout.compute(rect, TextStyles.measurer(), WorkshopTr.INSTANCE,
                        trackState(entry, hovered));
                state.layout = trackLayout;
                trackCards.add(new TrackCard(trackLayout, entry, hovered, state.hover.value(), state));
                continue;
            }
            WorkshopDownloader.Status downloadStatus = client.downloader().status(model.setId);
            boolean playing = PreviewPlayer.isPlaying(CardRenderer.previewKey(model.setId));
            CardRenderer.Context context = new CardRenderer.Context(extraCards, hovered, expandedSetId == model.setId, installed,
                    System.currentTimeMillis(), downloadStatus, playing,
                    playing && PreviewPlayer.isLoading(CardRenderer.previewKey(model.setId)), state.hover.value(),
                    rightBadge(model, downloadStatus));
            Layout cardLayout = CardRenderer.layout(rect, model, context, WorkshopTr.INSTANCE);
            if (picker != null) {
                chooseInsteadOfInstall(cardLayout);
            }
            state.layout = cardLayout;
            layouts.add(cardLayout);
            visible.add(model);
            contexts.add(context);
        }
        for (TrackCard card : trackCards) {
            drawTrackShapes(g, card);
        }
        for (int i = 0; i < layouts.size(); i++) {
            CardRenderer.drawShapes(g, layouts.get(i), visible.get(i), contexts.get(i));
        }
        List<Boolean> pictured = new ArrayList<>(trackCards.size());
        for (TrackCard card : trackCards) {
            boolean ready = (now - card.state().visibleSinceNanos) / 1_000_000L >= COVER_DELAY_MS;
            pictured.add(ready && drawTrackPicture(g, card, picker));
        }
        for (int i = 0; i < layouts.size(); i++) {
            CardState state = states.get(visible.get(i).setId);
            boolean ready = state != null && (now - state.visibleSinceNanos) / 1_000_000L >= COVER_DELAY_MS;
            if (ready) {
                CardRenderer.drawImages(g, layouts.get(i), visible.get(i), contexts.get(i), client.networkAllowed());
                if (picker != null || serverTab) {
                    drawPickerCover(g, layouts.get(i), visible.get(i), contexts.get(i), picker);
                }
            }
        }
        for (int i = 0; i < trackCards.size(); i++) {
            drawTrackOverlay(g, trackCards.get(i), pictured.get(i), mouseX, mouseY);
        }
        for (int i = 0; i < layouts.size(); i++) {
            CardRenderer.drawOverlay(g, layouts.get(i), visible.get(i), contexts.get(i), mouseX, mouseY, WorkshopTr.INSTANCE);
            Rect choose = layouts.get(i).rect(CHOOSE);
            if (!choose.empty()) {
                boolean hot = WorkshopDraw.hot(choose, mouseX, mouseY);
                WorkshopDraw.icon(g, choose.inset(choose.w() * 0.25F, choose.h() * 0.32F), UiIcon.CHEVRON_RIGHT,
                        hot ? Palette.ACCENT : Palette.TEXT_2);
            }
        }
        // The expanded card's dropdown is drawn last so it covers the row below.
        for (int i = 0; i < layouts.size(); i++) {
            if (contexts.get(i).expanded()) {
                CardRenderer.drawDropdown(g, layouts.get(i), visible.get(i), mouseX, mouseY);
            }
        }
        if (hoverIndex >= 0 && expandedSetId == 0 && hoveredSetId == cards.get(hoverIndex).setId
                && (now - hoverSinceNanos) / 1_000_000L > EXPAND_DELAY_MS) {
            CardState state = states.get(cards.get(hoverIndex).setId);
            if (state != null && state.layout != null) {
                Rect info = state.layout.rect("card.status");
                if (!info.empty() && mouseY >= info.y() - 2 && mouseY <= info.bottom() + 2) {
                    expandedSetId = cards.get(hoverIndex).setId;
                }
            }
        }
    }

    /** A card of the battle's own entries as this frame draws it. */
    private record TrackCard(Layout layout, BattleTracks.Entry entry, boolean hovered, float hover, CardState state) {
    }

    /**
     * In a battle's picker a Workshop card's install button becomes its choose button. The whole card already chooses,
     * and a download arrow on it promised that the press would do something else.
     */
    private static void chooseInsteadOfInstall(Layout card) {
        Rect install = card.rect("card.install");
        if (install.empty()) {
            return;
        }
        card.resize("card.install", Rect.EMPTY);
        card.add(CHOOSE, "card.strip", Layout.Kind.BUTTON, install);
    }

    /** What a track card says: the quiet badge of its kind, who published it, its tempo and length, its description. */
    private TrackCardLayout.State trackState(BattleTracks.Entry entry, boolean hovered) {
        WorkshopTr tr = WorkshopTr.INSTANCE;
        BattleTracks.Track row = entry.first();
        String badge = WorkshopStrings.upper(tr.get(switch (entry.kind()) {
            case WORKSHOP -> "screen.music_battle.vote.chip.workshop";
            case SERVER -> "screen.music_battle.vote.chip.server";
            case PLAYERS -> "screen.music_battle.vote.chip.players";
        }));
        // One byline for every kind, in the words and the type the Workshop's own cards use for their mapper: the
        // cards used to say "Published by: X", "mapped by x" or nothing at all depending on where a track came from.
        String maker = entry.kind() == BattleTracks.Kind.PLAYERS || row.mapper().isBlank() ? row.publishedBy() : row.mapper();
        String byline = maker.isBlank() ? "" : tr.get(WorkshopStrings.KEY + "card.mapped_by", maker);
        String length = WorkshopStrings.duration(row.durationTicks() * 50L);
        String detail = row.bpm() > 0 ? tr.get(WorkshopStrings.KEY + "set.bpm") + " " + row.bpm() + "  ·  " + length : length;
        String description = entry.kind() != BattleTracks.Kind.WORKSHOP ? row.description()
                : entry.rows().size() > 1 ? tr.get(WorkshopStrings.KEY + "card.charts", String.valueOf(entry.rows().size()))
                : "";
        return new TrackCardLayout.State(extraCards, hovered, entry.title(), byline, detail, description, badge);
    }

    private void drawTrackShapes(GuiGraphics g, TrackCard card) {
        Rect box = card.layout().rect("track");
        Rect body = card.layout().rect("track.body");
        if (card.hover() > 0.01F) {
            RoundedRect.shadow(g, box.x(), box.y() + 2, box.w(), box.h(), Palette.RADIUS_MD, 7F,
                    Palette.fade(Palette.SHADOW_LIFTED, card.hover()));
        }
        RoundedRect.fill(g, box.x(), box.y(), box.w(), box.h(), Palette.RADIUS_MD,
                Palette.mix(Palette.BG_2, Palette.ACCENT_DEEP, 0.18F));
        RoundedRect.fill(g, body.x(), body.y(), body.w(), body.h(), Palette.RADIUS_MD,
                Palette.mix(Palette.BG_3, Palette.BG_4, card.hover()));
    }

    /** The picture the server keeps for a track, once it is here; returns whether one was drawn. */
    private boolean drawTrackPicture(GuiGraphics g, TrackCard card, WorkshopScreen.Picker picker) {
        ResourceLocation texture = trackPicture(card.entry(), picker);
        if (texture == null) {
            return false;
        }
        Rect thumb = card.layout().rect("track.thumb");
        RoundedImage.draw(g, texture, thumb.x(), thumb.y(), thumb.w(), thumb.h(), 0F, 0F, 1F, 1F,
                Palette.RADIUS_MD, 0F, 0F, Palette.RADIUS_MD, card.hovered() ? 0.25F : 0F, 0xFFFFFFFF, 0xFFFFFFFF);
        return true;
    }

    private ResourceLocation trackPicture(BattleTracks.Entry entry, WorkshopScreen.Picker picker) {
        if (entry.kind() == BattleTracks.Kind.WORKSHOP) {
            WorkshopCatalog.InstalledSet set = client.catalog().bySetId(entry.setId()).orElse(null);
            if (set != null && set.coverPresent()) {
                return CoverCache.getFile(set.coverFile());
            }
        }
        return serverPicture(entry.first(), picker);
    }

    /**
     * The picture the server keeps for one of its rows: a battle's picker already asks for its own, and the Server tab
     * outside a battle asks through {@link ServerWorkshopClient#trackPicture}, so its cards are no longer blank.
     */
    private ResourceLocation serverPicture(BattleTracks.Track row, WorkshopScreen.Picker picker) {
        if (row == null) {
            return null;
        }
        if (picker != null) {
            return picker.preview().apply(row);
        }
        return serverTab ? ServerWorkshopClient.trackPicture(row) : null;
    }

    /** A Workshop set's picture on the server: the installed copy's own cover first, then the one the server keeps. */
    private ResourceLocation serverSetPicture(long setId, WorkshopScreen.Picker picker) {
        WorkshopCatalog.InstalledSet set = client.catalog().bySetId(setId).orElse(null);
        if (set != null && set.coverPresent()) {
            return CoverCache.getFile(set.coverFile());
        }
        BattleTracks.Entry listed = listedTracks(picker).workshopSet(setId);
        return listed == null ? null : serverPicture(listed.first(), picker);
    }

    private void drawTrackOverlay(GuiGraphics g, TrackCard card, boolean pictured, double mouseX, double mouseY) {
        Layout layout = card.layout();
        if (!pictured) {
            WorkshopDraw.icon(g, layout.rect("track.thumb.icon"), UiIcon.MUSIC, Palette.withAlpha(Palette.TEXT_MUTE, 0.7F));
        }
        Rect badge = layout.rect("track.badge");
        if (!badge.empty()) {
            WorkshopDraw.pill(g, badge, Palette.BG_1);
            WorkshopDraw.text(g, layout, "track.badge.text", Palette.TEXT_3);
        }
        WorkshopDraw.text(g, layout, "track.title", Palette.TEXT_1);
        WorkshopDraw.text(g, layout, "track.byline", Palette.TEXT_2);
        WorkshopDraw.paragraph(g, layout, "track.desc", Palette.TEXT_MUTE);
        WorkshopDraw.text(g, layout, "track.detail", Palette.TEXT_3);
        // In a picker the whole card is the button, so it asks for the hand and shows where the press goes; in the
        // Server tab the press opens the track's page with the server's comments. Anywhere else it does nothing, and
        // the arrow would promise otherwise.
        if ((screen.picking() || serverTab) && card.hovered() && WorkshopDraw.hot(layout.rect("track"), mouseX, mouseY)) {
            WorkshopDraw.icon(g, layout.rect("track.choose"), UiIcon.CHEVRON_RIGHT, Palette.ACCENT);
        }
    }

    /**
     * A picture for a Workshop card of the picker or the Server tab that brought none. The server's beatmap list sends
     * no cover at all, so its cards were blank squares; the installed copy's own cover comes first, then the one the
     * server keeps.
     */
    private void drawPickerCover(GuiGraphics g, Layout layout, CardModel model, CardRenderer.Context context,
                                 WorkshopScreen.Picker picker) {
        boolean drawn = CardModel.fileExists(model.localCover)
                || client.networkAllowed() && model.coverUrl != null && !model.coverUrl.isEmpty();
        if (drawn) {
            return;
        }
        ResourceLocation texture = serverSetPicture(model.setId, picker);
        if (texture == null) {
            return;
        }
        Rect thumb = layout.rect("card.thumb");
        float dim = context.playing() ? 0.6F : context.hover() ? 0.45F : 0F;
        RoundedImage.draw(g, texture, thumb.x(), thumb.y(), thumb.w(), thumb.h(), 0F, 0F, 1F, 1F,
                Palette.RADIUS_MD, 0F, 0F, Palette.RADIUS_MD, dim, 0xFFFFFFFF, 0xFFFFFFFF);
    }

    /** Which difficulty row of an open dropdown is under the pointer, as an index into the card's charts, or -1. */
    private static int dropdownRow(Rect dropdown, CardModel model, double mouseX, double mouseY) {
        int rows = (int) Math.min(model.chartNames.size(), Math.floor((dropdown.h() - 6) / Metrics.CARD_DROPDOWN_ROW));
        for (int i = 0; i < rows; i++) {
            if (CardLayout.dropdownRow(dropdown, i).contains(mouseX, mouseY)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Whether the pointer is inside the chart dropdown this card opened. The dropdown hangs below the card, where the
     * grid hit test finds nothing, so reaching for a difficulty row read as leaving the card and shut the dropdown
     * before the click could land on it.
     */
    private boolean overOwnDropdown(CardModel model, CardState state, double mouseX, double mouseY) {
        return expandedSetId == model.setId && state.layout != null && area.contains(mouseX, mouseY)
                && state.layout.rect("card.dropdown").contains(mouseX, mouseY);
    }

    private String rightBadge(CardModel model, WorkshopDownloader.Status status) {
        if (!installed) {
            // A battle's Server tab mixes the server's Workshop sets with its own tracks, and those say their kind on
            // their card; a Workshop set says it the same way rather than being the one card without a word.
            return serverTab && screen.picking() && model.setId > 0
                    ? WorkshopStrings.upper(WorkshopTr.INSTANCE.get("screen.music_battle.vote.chip.workshop")) : null;
        }
        var entry = client.installedCheck().entry(model.setId).orElse(null);
        if (entry != null && entry.withdrawn()) {
            return WorkshopStrings.upper(WorkshopTr.INSTANCE.get(WorkshopStrings.KEY
                    + ("audio_removed".equals(entry.state()) ? "badge.audio_removed" : "badge.withdrawn")));
        }
        if (model.needsAudio()) {
            return WorkshopStrings.upper(WorkshopTr.INSTANCE.get(WorkshopStrings.KEY + "badge.needs_audio"));
        }
        if (status != null && status.state() == WorkshopDownloader.State.UPDATE_AVAILABLE) {
            return WorkshopStrings.upper(WorkshopTr.INSTANCE.get(WorkshopStrings.KEY + "badge.update"));
        }
        return null;
    }

    private Rect shifted(Rect rect, float offset) {
        return rect.empty() ? rect : new Rect(area.x() + rect.x(), area.y() + rect.y() - offset, rect.w(), rect.h());
    }

    private int hitCard(double mouseX, double mouseY, float gridScroll) {
        if (!area.contains(mouseX, mouseY)) {
            return -1;
        }
        int index = grid.hitTest(mouseX, mouseY, gridScroll);
        return index >= 0 && index < cards.size() ? index : -1;
    }

    // ---------------------------------------------------------------- input

    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (layout == null || !area.contains(mouseX, mouseY)) {
            search.setFocused(false);
            return false;
        }
        if (search.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        search.setFocused(false);
        if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT && serverTab) {
            int hit = hitCard(mouseX, mouseY, scroll.snapped(TextStyles.guiScale()) - gridTop);
            return hit >= 0 && serverCardAction(cards.get(hit));
        }
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            return false;
        }
        float offset = scroll.snapped(TextStyles.guiScale());
        float gridScroll = offset - gridTop;

        // Cards first: the expanded dropdown sits above the chrome underneath it.
        int index = hitCard(mouseX, mouseY, gridScroll);
        for (CardModel model : cards) {
            CardState state = states.get(model.setId);
            if (state == null || state.layout == null) {
                continue;
            }
            if (expandedSetId == model.setId && !state.layout.rect("card.dropdown").empty()
                    && state.layout.rect("card.dropdown").contains(mouseX, mouseY)) {
                if (screen.picking()) {
                    // A row of the dropdown is a difficulty, so in a battle's picker it chooses the map on that one.
                    screen.choose(model, Math.max(0, dropdownRow(state.layout.rect("card.dropdown"), model, mouseX, mouseY)));
                } else {
                    openSet(model);
                }
                return true;
            }
        }
        if (index >= 0) {
            CardModel model = cards.get(index);
            CardState state = states.get(model.setId);
            Layout cardLayout = state == null ? null : state.layout;
            if (cardLayout != null) {
                if (cardLayout.rect("card.thumb").contains(mouseX, mouseY)) {
                    togglePreview(model);
                    return true;
                }
                if (WorkshopDraw.hover(cardLayout.rect("card.favourite"), mouseX, mouseY)) {
                    toggleFavourite(model);
                    return true;
                }
                if (WorkshopDraw.hover(cardLayout.rect("card.install"), mouseX, mouseY)) {
                    installAction(model);
                    return true;
                }
                if (WorkshopDraw.hover(cardLayout.rect(CHOOSE), mouseX, mouseY)) {
                    screen.choose(model, 0);
                    return true;
                }
            }
            openSet(model);
            return true;
        }

        for (Layout.Box box : layout.boxes()) {
            if (box.kind() != Layout.Kind.BUTTON) {
                continue;
            }
            if (!shifted(box.rect(), offset).contains(mouseX, mouseY)) {
                continue;
            }
            return onButton(box.id());
        }
        return false;
    }

    private boolean onButton(String id) {
        screen.click();
        if (id.equals("listing.filters.more")) {
            moreFilters = !moreFilters;
            relayout();
            return true;
        }
        if (id.startsWith("listing.filters.")) {
            int optIndex = id.indexOf(".opt.");
            if (optIndex > 0) {
                toggleFilter(id.substring("listing.filters.".length(), optIndex), id.substring(optIndex + 5));
                return true;
            }
        }
        if (id.startsWith("listing.sort.") && !id.endsWith(".caret") && !id.endsWith(".text") && !id.endsWith(".label")) {
            String field = id.substring("listing.sort.".length());
            if (field.equals(effectiveSort())) {
                sortAscending = !sortAscending;
            } else {
                sort = field;
                installedSort = field;
                // A newly picked field starts in the direction players read it in: names from A, dates and star
                // ratings from the top. The website's own fields all start descending, as its listing does.
                sortAscending = installed && ("title".equals(field) || "artist".equals(field));
            }
            if (installed || serverTab) {
                sortLocally();
                restartList();
                relayout();
            } else {
                scheduleSearch(FILTER_DEBOUNCE_MS);
            }
            return true;
        }
        switch (id) {
            case "listing.cards.normal", "listing.cards.extra" -> {
                extraCards = id.endsWith("extra");
                relayout();
                return true;
            }
            case "listing.tool.import" -> {
                screen.importPackage();
                return true;
            }
            case "listing.tool.inbox" -> {
                screen.openInboxFolder();
                return true;
            }
            case "listing.direct" -> {
                if (directSetId != null) {
                    screen.openSetPage(directSetId, null);
                }
                return true;
            }
            case "listing.state.action" -> {
                stateAction();
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    private void stateAction() {
        switch (status) {
            case "consent" -> screen.openConsent();
            case "empty", "no_matches" -> clearFilters();
            case "sign_in" -> screen.openAccount();
            default -> {
                if (serverTab) {
                    reloadServer();
                } else if (installed) {
                    reloadInstalled();
                } else {
                    scheduleSearch(0);
                }
            }
        }
    }

    private void clearFilters() {
        category = Filters.CATEGORY_DEFAULT;
        general.clear();
        extra.clear();
        genre = 0;
        language = 0;
        audio = Filters.AUDIO_ANY;
        search.setValue("");
        scheduleSearch(0);
    }

    private void toggleFilter(String rowId, String optionId) {
        switch (rowId) {
            case "general" -> {
                if (!general.remove(optionId)) {
                    general.add(optionId);
                }
            }
            case "category" -> {
                if (("favourites".equals(optionId) || "mine".equals(optionId)) && !client.session().signedIn()) {
                    screen.openAccount();
                    return;
                }
                category = optionId.equals(category) ? Filters.CATEGORY_DEFAULT : optionId;
                sort = null;
            }
            case "genre" -> genre = optionId.equals(String.valueOf(genre)) ? 0 : parse(optionId);
            case "language" -> language = optionId.equals(String.valueOf(language)) ? 0 : parse(optionId);
            case "audio" -> audio = optionId.equals(audio) ? Filters.AUDIO_ANY : optionId;
            case "extra" -> {
                if (!extra.remove(optionId)) {
                    extra.add(optionId);
                }
            }
            case "explicit" -> explicit = "show".equals(optionId);
            default -> {
                return;
            }
        }
        if (serverTab) {
            restartList();
            reloadServer();
        } else if (installed) {
            restartList();
            reloadInstalled();
        } else {
            scheduleSearch(FILTER_DEBOUNCE_MS);
        }
    }

    private static int parse(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        if (!area.contains(mouseX, mouseY)) {
            return false;
        }
        scroll.onWheel(amount);
        return true;
    }

    public boolean mouseDragged(double mouseX, double mouseY, int button) {
        return search.mouseDragged(mouseX, mouseY, button);
    }

    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (search.focused() && search.keyPressed(keyCode, scanCode, modifiers)) {
            return true;
        }
        switch (keyCode) {
            case GLFW.GLFW_KEY_RIGHT, GLFW.GLFW_KEY_DOWN, GLFW.GLFW_KEY_LEFT, GLFW.GLFW_KEY_UP -> {
                if (cards.isEmpty()) {
                    return false;
                }
                int step = keyCode == GLFW.GLFW_KEY_RIGHT ? 1 : keyCode == GLFW.GLFW_KEY_LEFT ? -1
                        : keyCode == GLFW.GLFW_KEY_DOWN ? grid.columns() : -grid.columns();
                focusIndex = Math.max(0, Math.min(cards.size() - 1, focusIndex < 0 ? 0 : focusIndex + step));
                scrollToCard(focusIndex);
                return true;
            }
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
                if (focusIndex >= 0 && focusIndex < cards.size()) {
                    openSet(cards.get(focusIndex));
                    return true;
                }
                return false;
            }
            default -> {
                return false;
            }
        }
    }

    public boolean charTyped(char character, int modifiers) {
        if (!search.focused() && character != ' ') {
            // Typing anywhere in the listing goes to the search box (lazer).
            search.setFocused(true);
        }
        return search.charTyped(character, modifiers);
    }

    private void scrollToCard(int index) {
        float cardY = gridTop + grid.row(index) * (cardHeight + Metrics.CARD_GAP);
        double target = scroll.target();
        if (cardY < target) {
            scroll.scrollTo(cardY, true);
        } else if (cardY + cardHeight > target + area.h()) {
            scroll.scrollTo(cardY + cardHeight - area.h(), true);
        }
    }

    // ---------------------------------------------------------------- actions

    private void openSet(CardModel model) {
        if (serverTab && !screen.picking()) {
            openServerPage(model);
            return;
        }
        // A negative id is one of the battle's own tracks, which has no Workshop page outside the Server tab.
        if (model.setId < 0 && !screen.picking()) {
            return;
        }
        screen.openSetPage(model.setId, model);
    }

    /**
     * A map of the Server tab opens on the server's own comments: a track made in the editor under its
     * {@code track_<id>} thread, a Workshop set on the server under {@code ws_<setId>}. A set listed only by the
     * server's track list has a stand-in card with a negative id, so its page is opened by the set's real id and
     * builds itself from the installed copy or the Workshop.
     */
    private void openServerPage(CardModel model) {
        BattleTracks tracks = listedTracks(null);
        BattleTracks.Entry entry = tracks.entry(model.setId);
        if (entry != null && entry.kind() != BattleTracks.Kind.WORKSHOP) {
            BattleTracks.Track row = entry.first();
            screen.openServerPage(model.setId, model, "track_" + row.id(), () -> ServerWorkshopClient.trackPicture(row));
            return;
        }
        long setId = entry != null ? entry.setId() : model.setId;
        if (setId <= 0) {
            return;
        }
        screen.openServerPage(setId, entry != null ? null : model, "ws_" + setId, () -> serverSetPicture(setId, null));
    }

    private void togglePreview(CardModel model) {
        String key = CardRenderer.previewKey(model.setId);
        if (PreviewPlayer.isPlaying(key)) {
            PreviewPlayer.stop();
            return;
        }
        PreviewPlayer.stop();
        float volume = client.previewVolume();
        if (CardModel.fileExists(model.localPreview)) {
            PreviewPlayer.play(key, model.localPreview, model.previewDurationMs > 0 ? model.previewDurationMs
                    : PreviewPlayer.DEFAULT_DURATION_MS, volume);
            return;
        }
        if (model.previewUrl == null || !client.networkAllowed()) {
            return;
        }
        screen.fetchPreview(model.setId, model.previewUrl, model.previewDurationMs);
    }

    public void stopPreview() {
        PreviewPlayer.stop();
    }

    private void toggleFavourite(CardModel model) {
        if (!client.session().signedIn()) {
            screen.openAccount();
            return;
        }
        boolean wanted = !model.favourited;
        screen.run(() -> {
            try {
                client.session().authorized(api -> api.favourite(model.setId, wanted, CancelToken.none()));
                screen.later(() -> replaceCard(model.setId, wanted));
            } catch (WorkshopApiException exception) {
                screen.showError(exception.code());
            }
        });
    }

    private void replaceCard(long setId, boolean favourited) {
        for (CardModel model : cards) {
            if (model.setId == setId && model.favourited != favourited) {
                model.favourited = favourited;
                model.favourites = Math.max(0, model.favourites + (favourited ? 1 : -1));
                return;
            }
        }
    }

    /** Right-click on a Server beatmaps card removes it from the server, for a player who may manage them. */
    private boolean serverCardAction(CardModel model) {
        WorkshopServerHooks.ServerBeatmaps hook = WorkshopServerHooks.serverBeatmaps();
        // The battle's own entries are not server beatmaps, and their negative ids mean nothing to the server.
        if (!serverTab || hook == null || !hook.canManage() || model.setId <= 0) {
            return false;
        }
        screen.showDialog(WorkshopConfirmScreen.danger(screen,
                net.minecraft.network.chat.Component.translatable(WorkshopStrings.KEY + "server.remove_title"),
                net.minecraft.network.chat.Component.translatable(WorkshopStrings.KEY + "server.remove_body", model.title),
                net.minecraft.network.chat.Component.translatable(WorkshopStrings.KEY + "server.remove_confirm"),
                () -> hook.remove(model.setId, error -> reloadServer())));
        return true;
    }

    private void installAction(CardModel model) {
        if (screen.picking()) {
            screen.choose(model, 0);
            return;
        }
        WorkshopDownloader.Status status = client.downloader().status(model.setId);
        switch (status.state()) {
            case QUEUED, DOWNLOADING -> client.downloader().cancel(model.setId);
            case INSTALLED -> screen.openSetPage(model.setId, model);
            case UNAVAILABLE -> screen.showError(WorkshopApiException.UNAVAILABLE);
            default -> {
                if (!client.networkAllowed()) {
                    screen.openConsent();
                    return;
                }
                client.downloader().enqueue(model.setId);
            }
        }
    }

    // ---------------------------------------------------------------- data

    private void scheduleSearch(long delayMs) {
        restartList();
        pendingSearchAt = System.currentTimeMillis() + delayMs;
        cursor = null;
        if (delayMs == 0) {
            maybeRunScheduledSearch();
        }
    }

    /**
     * A different query, filter or sort order is a different list, so it starts at the top. A plain refresh of the
     * same list (an install finishing, the catalogue changing) deliberately does not call this: the player would lose
     * the row they were looking at.
     */
    private void restartList() {
        scroll.scrollTo(0, true);
        focusIndex = -1;
        expandedSetId = 0;
    }

    private void maybeRunScheduledSearch() {
        if (pendingSearchAt == 0 || System.currentTimeMillis() < pendingSearchAt) {
            return;
        }
        if (!serverTab && !installed && loading) {
            // A request is already out. Waiting a moment and trying again keeps the typed query: clearing the timer
            // here dropped it outright, so a search typed while the first page was still loading did nothing at all.
            pendingSearchAt = System.currentTimeMillis() + 60;
            return;
        }
        pendingSearchAt = 0;
        if (serverTab) {
            reloadServer();
        } else if (installed) {
            reloadInstalled();
        } else {
            runSearch(true);
        }
    }

    /**
     * The Server beatmaps tab (spec 06 section 9.1): one page from the Minecraft server through
     * {@link WorkshopServerHooks}, with the same cards as Browse. Nothing here touches the website.
     */
    public void reloadServer() {
        WorkshopServerHooks.ServerBeatmaps hook = WorkshopServerHooks.serverBeatmaps();
        WorkshopScreen.Picker picker = screen.picker();
        if (picker != null) {
            reloadBattle(picker.tracks(), hook != null && hook.available() ? hook : null);
            return;
        }
        if (hook == null || !hook.available()) {
            cards = List.of();
            status = "server_unavailable";
            statusDetail = "";
            relayout();
            return;
        }
        String query = search.value().trim();
        int token = generation.incrementAndGet();
        serverPage = null;
        serverQuery = query;
        // The server's whole track list changes only when someone publishes or removes a track, so a search reuses it
        // for a while instead of carrying hundreds of rows over the network on every keystroke.
        long now = System.currentTimeMillis();
        if (serverTracksAskedAtMs == 0L || now - serverTracksAskedAtMs > SERVER_TRACKS_REFRESH_MS) {
            serverTracksAskedAtMs = now;
            hook.requestTracks(tracks -> {
                serverTracks = tracks;
                // The list may land after a newer search went out; it belongs to whichever search is current.
                if (screen.picker() == null) {
                    showServer(serverQuery);
                }
            });
        }
        showServer(query);
        hook.request(null, query, page -> {
            if (token == generation.get()) {
                serverPage = page;
                showServer(query);
            }
        }, error -> {
            if (token != generation.get()) {
                return;
            }
            serverPage = List.of();
            showServer(query);
            if (cards.isEmpty()) {
                status = "offline";
                statusDetail = error == null ? "" : error;
                relayout();
            }
        });
    }

    /**
     * The Server tab outside a battle: the server's Workshop sets as their own cards from its beatmap list, then every
     * other track it offers - its operator's and the ones its players made in the editor - as the battle picker's
     * track cards. The beatmap list alone left a server whose maps were made in the editor with an empty tab.
     */
    private void showServer(String query) {
        List<CardModel> page = serverPage;
        loading = page == null;
        List<CardModel> next = new ArrayList<>(page == null ? List.of() : page);
        Set<Long> covered = new HashSet<>();
        for (CardModel card : next) {
            covered.add(card.setId);
        }
        for (BattleTracks.Entry entry : serverTracks.entries()) {
            // A set's own card comes with the beatmap list; until it is here the set waits rather than flashing a
            // bare stand-in that the real card would replace a moment later.
            if (entry.kind() == BattleTracks.Kind.WORKSHOP && (page == null || covered.contains(entry.setId()))) {
                continue;
            }
            if (BattleTracks.matches(entry, query)) {
                next.add(placeholder(entry));
            }
        }
        cards = next;
        sortLocally();
        cursor = null;
        hasMore = false;
        status = !cards.isEmpty() ? "" : loading ? "loading" : query.isEmpty() ? "server_empty" : "no_matches";
        statusDetail = "";
        Set<Long> ids = new HashSet<>();
        for (CardModel card : cards) {
            ids.add(card.setId);
        }
        states.keySet().retainAll(ids);
        relayout();
    }

    /** The tracks the Server tab lists as track cards: a picker's battle list, or the server's own list outside one. */
    private BattleTracks listedTracks(WorkshopScreen.Picker picker) {
        if (picker != null) {
            return picker.tracks();
        }
        return serverTab ? serverTracks : BattleTracks.NONE;
    }

    /**
     * The Server tab of a battle's picker: every track the battle can vote for. The server's Workshop sets come from
     * its beatmap list as they always do, but only those the battle lists, because a set the vote does not know would
     * turn the choice into a refusal. Whatever that list does not bring - the server's own tracks, its players' tracks,
     * and sets past its first page - comes from the battle's own list, which is here at once and needs no answer, so
     * the tab is never empty while the server's answer is on its way.
     */
    private void reloadBattle(BattleTracks tracks, WorkshopServerHooks.ServerBeatmaps hook) {
        String query = search.value().trim();
        int token = generation.incrementAndGet();
        if (hook == null) {
            showBattle(tracks, query, List.of());
            return;
        }
        // The server's own tracks show at once; its sets wait a moment for their proper cards, so the grid does not
        // flash a bare card for each of them just before the real one replaces it.
        battleQuery = query;
        battleAskedAtMs = System.currentTimeMillis();
        showBattle(tracks, query, null);
        hook.request(null, query, page -> {
            if (token == generation.get()) {
                battleAskedAtMs = 0L;
                showBattle(tracks, query, page);
            }
        }, error -> {
            if (token == generation.get()) {
                battleAskedAtMs = 0L;
                showBattle(tracks, query, List.of());
            }
        });
    }

    /**
     * The battle's list did not answer in time. The Minecraft server's page has no failure path of its own, so the
     * sets it would have brought are shown from the battle's list instead of being kept from the player for good.
     */
    private void maybeStopWaitingForServer() {
        WorkshopScreen.Picker picker = screen.picker();
        if (battleAskedAtMs == 0L || picker == null || System.currentTimeMillis() - battleAskedAtMs < BATTLE_WAIT_MS) {
            return;
        }
        battleAskedAtMs = 0L;
        generation.incrementAndGet();
        showBattle(picker.tracks(), battleQuery, List.of());
    }

    /** {@code page} is the server's beatmap list, or null while it is still on its way. */
    private void showBattle(BattleTracks tracks, String query, List<CardModel> page) {
        boolean waiting = page == null;
        loading = waiting;
        List<CardModel> next = new ArrayList<>();
        Set<Long> covered = new HashSet<>();
        for (CardModel card : waiting ? List.<CardModel>of() : page) {
            if (tracks.workshopSet(card.setId) != null && covered.add(card.setId)) {
                next.add(card);
            }
        }
        for (BattleTracks.Entry entry : tracks.entries()) {
            boolean set = entry.kind() == BattleTracks.Kind.WORKSHOP;
            if (set && (waiting || covered.contains(entry.setId()))) {
                continue;
            }
            if (BattleTracks.matches(entry, query)) {
                next.add(placeholder(entry));
            }
        }
        cards = next;
        sortLocally();
        cursor = null;
        // A list the server could not send whole is not "everything"; the notice over it says what is missing.
        hasMore = tracks.truncated();
        status = !cards.isEmpty() ? "" : waiting ? "loading" : query.isEmpty() ? "server_empty" : "no_matches";
        statusDetail = "";
        Set<Long> ids = new HashSet<>();
        for (CardModel card : cards) {
            ids.add(card.setId);
        }
        states.keySet().retainAll(ids);
        relayout();
    }

    /**
     * The card grid's stand-in for one of the battle's own entries. Its id is the entry's negative card id, so the
     * grid keeps its hover and its picture apart from every Workshop set; its title and publisher are what sorting and
     * the keyboard need. It is never drawn as a Workshop card.
     */
    private static CardModel placeholder(BattleTracks.Entry entry) {
        BattleTracks.Track row = entry.first();
        List<ServerBeatmapsPagePayload.Chart> charts = new ArrayList<>();
        for (String key : entry.chartKeys()) {
            charts.add(new ServerBeatmapsPagePayload.Chart(key, BattleSetup.keyAsName(key), 0D));
        }
        if (charts.isEmpty()) {
            charts.add(new ServerBeatmapsPagePayload.Chart("track", row.title(), 0D));
        }
        String by = entry.kind() == BattleTracks.Kind.PLAYERS ? row.publishedBy() : row.mapper();
        return CardModel.ofServerBeatmap(new ServerBeatmapsPagePayload.Item(entry.cardId(), 0, row.contentSha256(),
                row.title(), "", by, charts, false, false, "", 0L, false, false));
    }

    private void onSearchSubmit() {
        String value = search.value().trim();
        Long direct = parseSetReference(value);
        if (direct != null) {
            // Only the set's own page knows its charts, so even in a battle's picker a set found by its number opens
            // there; the page's primary button is then Choose (WorkshopScreen.primaryAction) rather than Install.
            screen.openSetPage(direct, null);
            return;
        }
        scheduleSearch(0);
    }

    /** A set link or {@code #42} opens the set; a bare number only offers a row (review of 06 section 6.1). */
    public Long parseSetReference(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        String text = value.trim();
        if (text.startsWith("#")) {
            return positive(text.substring(1));
        }
        int index = text.toLowerCase(Locale.ROOT).indexOf("/beatmapsets/");
        if (index >= 0) {
            String rest = text.substring(index + "/beatmapsets/".length());
            int slash = rest.indexOf('/');
            int hash = rest.indexOf('#');
            int cut = Math.min(slash < 0 ? rest.length() : slash, hash < 0 ? rest.length() : hash);
            return positive(rest.substring(0, cut));
        }
        return null;
    }

    private static Long positive(String value) {
        try {
            long id = Long.parseLong(value.trim());
            return id > 0 ? id : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String effectiveSort() {
        return state().effectiveSort();
    }

    private void runSearch(boolean reset) {
        if (!client.networkAllowed()) {
            cards = List.of();
            status = "consent";
            statusDetail = "";
            relayout();
            return;
        }
        if (loading) {
            return;
        }
        String query = search.value().trim();
        Long bare = positive(query);
        directSetId = bare != null && bare > 0 ? bare : null;
        int token = generation.incrementAndGet();
        if (reset) {
            // lazer stops a preview the listing owns whenever a new search starts.
            PreviewPlayer.stop();
        }
        loading = true;
        status = cards.isEmpty() ? "loading" : status;
        String requestCursor = reset ? null : cursor;
        WorkshopApi.SearchQuery search = new WorkshopApi.SearchQuery(query.isEmpty() ? null : query, category,
                Filters.ordered(general, "library", "server"), genre > 0 ? genre : null, language > 0 ? language : null,
                Filters.AUDIO_ANY.equals(audio) ? null : audio, Filters.ordered(extra, "fx", "twoside"), explicit,
                effectiveSort() + (sortAscending ? "_asc" : "_desc"), requestCursor, null);
        screen.run(() -> {
            try {
                WorkshopDtos.SearchPage page = client.session().signedIn()
                        ? client.session().authorized(api -> api.search(search, CancelToken.none()))
                        : client.api().search(search, CancelToken.none());
                screen.later(() -> applyPage(token, page, reset));
            } catch (WorkshopApiException exception) {
                screen.later(() -> failPage(token, exception));
            }
        });
    }

    private void applyPage(int token, WorkshopDtos.SearchPage page, boolean reset) {
        if (token != generation.get()) {
            return;
        }
        loading = false;
        lastPageAt = System.currentTimeMillis();
        // A page that arrived clears whatever the last failure was waiting out.
        nextPageAt = 0L;
        pageFailures = 0;
        List<CardModel> next = reset ? new ArrayList<>() : new ArrayList<>(cards);
        Set<Long> seen = new LinkedHashSet<>();
        next.forEach(card -> seen.add(card.setId));
        if (page.items() != null) {
            for (WorkshopDtos.SetCard card : page.items()) {
                if (seen.add(card.id())) {
                    next.add(CardModel.of(card));
                }
            }
        }
        cards = next;
        cursor = page.cursor();
        hasMore = cursor != null && !cursor.isEmpty();
        status = cards.isEmpty() ? "empty" : "";
        statusDetail = "";
        states.keySet().retainAll(seen);
        relayout();
    }

    private void failPage(int token, WorkshopApiException exception) {
        if (token != generation.get()) {
            return;
        }
        loading = false;
        lastPageAt = System.currentTimeMillis();
        long waitMillis = Math.max(exception.retryAfterSeconds() * 1000L,
                Math.min(60_000L, 2000L << Math.min(pageFailures, 5)));
        pageFailures++;
        nextPageAt = lastPageAt + waitMillis;
        if (cards.isEmpty()) {
            status = WorkshopApiException.CONSENT_REQUIRED.equals(exception.code()) ? "consent"
                    : WorkshopApiException.NOT_SIGNED_IN.equals(exception.code()) ? "sign_in" : "offline";
            statusDetail = WorkshopText.error(exception.code()).getString();
        } else {
            screen.showError(exception.code());
        }
        relayout();
    }

    private void maybeLoadMore(float gridScroll) {
        // The Server tab is one answer from the Minecraft server; its "more" only says the list is not complete.
        if (installed || serverTab || loading || !hasMore || cards.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastPageAt < PAGE_INTERVAL_MS || now < nextPageAt) {
            return;
        }
        float remaining = grid.contentHeight() - (gridScroll + area.h());
        if (remaining <= (cardHeight + Metrics.CARD_GAP) * 2) {
            runSearch(false);
        }
    }

    /** Installed tab: the local catalogue, filtered by the text search and sorted locally. */
    public void reloadInstalled() {
        client.io().execute(() -> {
            client.catalog().refresh();
            // Warms the remembered installed-check answers off the render thread (the cards read them per frame).
            client.installedCheck().entries();
            List<CardModel> models = new ArrayList<>();
            for (WorkshopCatalog.InstalledSet set : client.catalog().all()) {
                models.add(CardModel.ofInstalled(set));
            }
            screen.later(() -> {
                cards = models;
                filterInstalled();
                // An empty list means two different things here, and offering "install some beatmaps" to a player
                // whose search simply matched nothing hid the reason the list went blank.
                status = !cards.isEmpty() ? "" : models.isEmpty() ? "installed_empty" : "no_matches";
                statusDetail = "";
                relayout();
            });
        });
    }

    private void filterInstalled() {
        String query = search.value().trim().toLowerCase(Locale.ROOT);
        if (!query.isEmpty()) {
            List<CardModel> filtered = new ArrayList<>();
            for (CardModel model : cards) {
                if (model.title.toLowerCase(Locale.ROOT).contains(query) || model.artist.toLowerCase(Locale.ROOT).contains(query)
                        || model.mapperName.toLowerCase(Locale.ROOT).contains(query)) {
                    filtered.add(model);
                }
            }
            cards = filtered;
        }
        sortLocally();
    }

    /**
     * Orders a list the client holds itself: the Installed catalogue, and the Server tab's page, whose sort bar used
     * to be drawn and read but never acted on, because only the website's tab turns a sort field into a request.
     */
    private void sortLocally() {
        Comparator<CardModel> comparator = switch (installedSort) {
            case "artist" -> Comparator.comparing((CardModel model) -> model.artist.toLowerCase(Locale.ROOT));
            case "installed" -> Comparator.comparingLong((CardModel model) -> model.installedAtMillis);
            case "difficulty" -> Comparator.comparingDouble(CardModel::topStars);
            default -> Comparator.comparing((CardModel model) -> model.title.toLowerCase(Locale.ROOT));
        };
        // Every comparator above reads its field ascending, so the caret the sort bar draws is the order the player
        // gets: the direction used to be inverted for the other fields and dropped outright for "Installed".
        if (!sortAscending) {
            comparator = comparator.reversed();
        }
        if ("difficulty".equals(installedSort)) {
            // Sets without the offline card sidecar have no stars at all (spec 08 A5.5); they sort after the rated
            // ones in both directions instead of passing themselves off as the easiest maps in the list.
            comparator = Comparator.comparingInt((CardModel model) -> model.topStars() > 0 ? 0 : 1).thenComparing(comparator);
        }
        List<CardModel> sorted = new ArrayList<>(cards);
        sorted.sort(comparator.thenComparing(model -> model.title.toLowerCase(Locale.ROOT)));
        cards = sorted;
    }

    /** The screen calls this after an install, delete or installed-check so the cards show the new state. */
    public void onCatalogChanged() {
        if (serverTab) {
            reloadServer();
        } else if (installed) {
            reloadInstalled();
        }
    }
}

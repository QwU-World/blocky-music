package ru.gora.musicbattle.client;

import static ru.gora.musicbattle.client.ClientTranslations.text;

import com.mojang.blaze3d.platform.NativeImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import ru.gora.musicbattle.MusicBattleMod;
import ru.gora.musicbattle.client.ui.CoverCache;
import ru.gora.musicbattle.client.ui.FancyEditBox;
import ru.gora.musicbattle.client.ui.HandCursor;
import ru.gora.musicbattle.client.ui.Motion;
import ru.gora.musicbattle.client.ui.Palette;
import ru.gora.musicbattle.client.ui.RoundedImage;
import ru.gora.musicbattle.client.ui.RoundedRect;
import ru.gora.musicbattle.client.ui.TextStyle;
import ru.gora.musicbattle.client.ui.TextStyles;
import ru.gora.musicbattle.client.ui.UiIcon;
import ru.gora.musicbattle.client.ui.UiLayer;
import ru.gora.musicbattle.client.workshop.ServerWorkshopClient;
import ru.gora.musicbattle.client.workshop.WorkshopClient;
import ru.gora.musicbattle.client.workshop.WorkshopDraw;
import ru.gora.musicbattle.client.workshop.WorkshopScreen;
import ru.gora.musicbattle.client.workshop.layout.BattleTracks;
import ru.gora.musicbattle.client.workshop.layout.Metrics;
import ru.gora.musicbattle.client.workshop.layout.Rect;
import ru.gora.musicbattle.client.workshop.layout.TextFit;
import ru.gora.musicbattle.client.workshop.layout.TrackLibraryLayout;
import ru.gora.musicbattle.network.PublishedTracksPayload;
import ru.gora.musicbattle.server.MusicBattleTracks;
import ru.gora.musicbattle.workshop.WorkshopCatalog;

/**
 * The track library: the player's own tracks, what they published to this server, and the beatmaps installed from the
 * Workshop, each a card with its cover, its numbers and its actions.
 *
 * <p>It is the Workshop's listing doing the same job, so it is drawn with the same toolkit: {@link UiLayer} for the
 * opaque backdrop and the cursor, {@link Palette} and {@link TextStyles} instead of teal literals and the bitmap font,
 * rounded cards that lift under the pointer, and the Workshop's scrollbar. The geometry lives in
 * {@link TrackLibraryLayout} so the buttons and the cards they belong to are laid out once, together.
 */
public final class TrackLibraryScreen extends UiLayer implements ru.gora.musicbattle.client.workshop.WorkshopDropTarget {
    private final Screen parent;
    private List<MusicBattleTracks.Track> tracks = List.of();
    private List<PublishedTracksPayload.Entry> publishedTracks = List.of();
    private List<WorkshopCatalog.InstalledSet> installedSets = List.of();
    private final Map<String, ResourceLocation> previewTextures = new HashMap<>();
    private final Set<String> requestedPreviews = new HashSet<>();
    /** Local pictures that are missing or could not be read, so a published card can fall back to the server's copy. */
    private final Set<String> failedPreviews = new HashSet<>();
    private final List<Button> editButtons = new ArrayList<>();
    private final List<Button> publishButtons = new ArrayList<>();
    private final List<Button> copyButtons = new ArrayList<>();
    private final List<Button> deleteLocalButtons = new ArrayList<>();
    private final List<Button> deleteServerButtons = new ArrayList<>();
    private final List<Button> openWorkshopButtons = new ArrayList<>();
    /** One per visible row, so a card lifts and settles instead of snapping when the pointer crosses it. */
    private final List<Motion.Tween> rowHover = new ArrayList<>();
    private Button createButton;
    private EditBox searchBox;
    private TrackLibraryLayout frame;
    /** The tabs as wide as their labels need: the layout's equal thirds cut "Опубликованные карты" at 320x240. */
    private Tabs.Strip tabStrip;
    /** Where the leftmost action of a row starts on each tab; the text of a card stops there. */
    private float localActionsLeft;
    private float publishedActionsLeft;
    private float installedActionsLeft;
    private LibraryTab activeTab = LibraryTab.LOCAL;
    private int scrollOffset;
    private int previewGeneration;
    private String localActionStatus = "";
    private boolean localActionOk;
    private String searchQuery = "";

    public TrackLibraryScreen(Screen parent) {
        super(Component.translatable("music_battle.editor"));
        this.parent = parent;
    }

    @Override
    protected void layout() {
        String retainedSearch = searchBox == null ? searchQuery : searchBox.getValue();
        resetPreviewCache();
        this.tracks = new ArrayList<>(MusicBattleTracks.loadTracks());
        this.tracks.sort(Comparator.comparing(MusicBattleTracks.Track::title, String.CASE_INSENSITIVE_ORDER));
        this.publishedTracks = ClientTrackPublisher.publishedTracks();
        ClientTrackPublisher.requestPublishedTracks();
        this.installedSets = WorkshopClient.get().catalog().all();

        String createLabel = text("music_battle.l10n.client.122");
        String backLabel = Component.translatable("gui.back").getString();
        this.frame = TrackLibraryLayout.compute(this.width, this.height, TextStyles.measurer(), createLabel, backLabel);
        // Before any widget is built: setting the search box's value below fires its responder, which walks these.
        editButtons.clear();
        publishButtons.clear();
        openWorkshopButtons.clear();
        copyButtons.clear();
        deleteLocalButtons.clear();
        deleteServerButtons.clear();
        rowHover.clear();

        // The tabs stay vanilla buttons: keyboard focus keeps working and the screen paints the active one itself.
        List<String> tabLabels = List.of(text("music_battle.l10n.client.120"), text("music_battle.l10n.client.121"),
                Component.translatable("screen.music_battle.library.installed").getString());
        tabStrip = Tabs.arrange(frame, TextStyles.measurer(), tabLabels);
        List<Rect> tabs = tabStrip.tabs();
        addWidget(Component.literal(tabLabels.get(0)), button -> switchTab(LibraryTab.LOCAL), tabs.get(0)).quiet();
        addWidget(Component.literal(tabLabels.get(1)), button -> switchTab(LibraryTab.PUBLISHED), tabs.get(1)).quiet();
        addWidget(Component.literal(tabLabels.get(2)), button -> switchTab(LibraryTab.INSTALLED), tabs.get(2)).quiet();

        Rect field = frame.search();
        // The edit box keeps the vanilla font, which is what measures its caret; the rounded field around it is ours,
        // and the box is placed on the line where its text belongs rather than filling the field.
        int boxY = Math.round(field.y() + (field.h() - 8) * 0.5F);
        searchBox = TextStyles.editBox(Math.round(field.x()) + 7, boxY,
                Math.round(field.w() - Metrics.ICON - 16), Math.round(field.bottom()) - boxY,
                Component.translatable("screen.music_battle.track_search"));
        searchBox.setMaxLength(80);
        // A plain EditBox draws a hint without a colour of its own in the typed-text colour, near white here, so the
        // hint gets the muted colour FancyEditBox gives every hint.
        searchBox.setHint(FancyEditBox.mutedHint(Component.translatable("screen.music_battle.track_search")));
        searchBox.setValue(retainedSearch == null ? "" : retainedSearch);
        searchBox.setResponder(value -> {
            searchQuery = value == null ? "" : value;
            scrollOffset = 0;
            updateButtons();
        });
        this.addRenderableWidget(searchBox);
        createButton = addWidget(Component.literal(createLabel),
                button -> this.minecraft.setScreen(new TrackEditorScreen(this)), frame.create()).primary();

        scrollOffset = Math.max(0, Math.min(maxScroll(), scrollOffset));
        float buttonHeight = frame.buttonHeight();
        Component editLabel = Component.translatable("screen.music_battle.library.edit");
        Component publishLabel = Component.literal(text("music_battle.l10n.client.061"));
        Component deleteLabel = Component.literal(text("music_battle.l10n.client.123"));
        Component copyLabel = Component.literal(text("music_battle.l10n.client.124"));
        Component deleteServerLabel = Component.literal(text("music_battle.l10n.client.050"));
        Component workshopLabel = Component.translatable("screen.music_battle.library.open_workshop");
        float[] localWidths = {
                actionWidth(copyLabel, buttonHeight),
                actionWidth(deleteLabel, buttonHeight),
                actionWidth(publishLabel, buttonHeight),
                actionWidth(editLabel, buttonHeight)
        };
        float[] publishedWidths = {actionWidth(deleteServerLabel, buttonHeight)};
        float[] installedWidths = {actionWidth(workshopLabel, buttonHeight)};
        for (int row = 0; row < frame.rowsPerPage(); row++) {
            int visibleRow = row;
            Rect rect = frame.row(row);
            List<Rect> local = frame.actions(rect, localWidths);
            copyButtons.add(addWidget(copyLabel, button -> copyVisibleTrack(visibleRow), local.get(0)));
            deleteLocalButtons.add(addWidget(deleteLabel, button -> deleteLocalVisibleTrack(visibleRow), local.get(1)).danger());
            publishButtons.add(addWidget(publishLabel, button -> publishVisibleTrack(visibleRow), local.get(2)));
            editButtons.add(addWidget(editLabel, button -> editVisibleTrack(visibleRow), local.get(3)));
            Rect published = frame.actions(rect, publishedWidths).get(0);
            deleteServerButtons.add(addWidget(deleteServerLabel, button -> deletePublishedVisibleTrack(visibleRow), published).danger());
            Rect installed = frame.actions(rect, installedWidths).get(0);
            openWorkshopButtons.add(addWidget(workshopLabel, button -> openInstalledVisible(visibleRow), installed));
            rowHover.add(new Motion.Tween(0.0));
            if (row == 0) {
                localActionsLeft = local.get(0).x();
                publishedActionsLeft = published.x();
                installedActionsLeft = installed.x();
            }
        }
        updateButtons();

        addWidget(Component.literal(backLabel), button -> this.onClose(), frame.back());
    }

    private FancyButton addWidget(Component label, Button.OnPress action, Rect rect) {
        return this.addRenderableWidget(FancyButton.create(label, action, Math.round(rect.x()), Math.round(rect.y()),
                Math.round(rect.w()), Math.round(rect.h())));
    }

    private static float actionWidth(Component label, float buttonHeight) {
        return TrackLibraryLayout.actionWidth(TextStyles.measurer(), label.getString(), buttonHeight);
    }

    @Override
    protected void renderUi(GuiGraphics graphics, double mouseX, double mouseY, float partialTick) {
        if (frame == null) {
            return;
        }
        refreshPublishedSnapshot();
        updateButtons();
        drawHeader(graphics);
        drawTabs(graphics);
        drawSearchField(graphics, mouseX, mouseY);
        drawRows(graphics, mouseX, mouseY);
        drawFooter(graphics);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (entryCount() <= rowsPerPage()) {
            return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        }
        scrollOffset = Math.max(0, Math.min(maxScroll(), scrollOffset - (int) Math.signum(scrollY)));
        updateButtons();
        return true;
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(parent);
    }

    /** Workshop packages dropped on the library install through the Workshop flow. */
    @Override
    public void onFilesDrop(List<Path> paths) {
        ru.gora.musicbattle.client.workshop.WorkshopDrops.onFilesDropped(this, paths);
    }

    @Override
    public void removed() {
        previewGeneration++;
        releasePreviewTextures();
        super.removed();
    }

    // ---------------------------------------------------------------- chrome

    private void drawHeader(GuiGraphics graphics) {
        Rect header = frame.header();
        Rect column = frame.column();
        RoundedRect.fill(graphics, 0, 0, this.width, header.h(), 0, Palette.BG_1);
        RoundedRect.fill(graphics, 0, header.bottom() - 1, this.width, 1, 0, Palette.LINE);
        Rect icon = new Rect(column.x(), header.centerY() - Metrics.ICON * 0.5F, Metrics.ICON, Metrics.ICON);
        WorkshopDraw.icon(graphics, icon, UiIcon.MUSIC, Palette.ACCENT);

        String subtitle = subtitle();
        float subtitleWidth = Math.min(column.w() * 0.55F,
                TextFit.width(TextStyles.measurer(), subtitle, TextStyle.CAPTION));
        float titleLeft = icon.right() + Metrics.GAP_SM + 1;
        line(graphics, Component.translatable("screen.music_battle.library.title").getString(), TextStyle.TITLE,
                titleLeft, TextStyles.centeredCapY(TextStyle.TITLE, 0, header.h()),
                Math.max(20F, column.right() - subtitleWidth - Metrics.GAP - titleLeft), Palette.TEXT_1);
        lineRight(graphics, subtitle, TextStyle.CAPTION, column.right(),
                TextStyles.centeredCapY(TextStyle.CAPTION, 0, header.h()), subtitleWidth, Palette.TEXT_MUTE);
    }

    /** What the header says about the tab: where its files live, or how many publishing slots are left. */
    private String subtitle() {
        if (activeTab == LibraryTab.PUBLISHED) {
            return text("music_battle.l10n.client.126") + ClientTrackPublisher.usedPublishedSlots()
                    + " / " + ClientTrackPublisher.maxPublishedSlots();
        }
        Path directory = activeTab == LibraryTab.INSTALLED
                ? WorkshopClient.get().catalog().installedDirectory()
                : MusicBattleTracks.tracksDirectory();
        return text("music_battle.l10n.client.125") + shorten(MusicBattleTracks.displayPath(directory));
    }

    private void drawTabs(GuiGraphics graphics) {
        Rect bar = frame.tabBar();
        RoundedRect.fill(graphics, 0, bar.y(), this.width, bar.h(), 0, Palette.BG_2);
        RoundedRect.fill(graphics, 0, bar.bottom() - 1, this.width, 1, 0, Palette.LINE);
        LibraryTab[] order = LibraryTab.values();
        List<Rect> tabs = tabStrip.tabs();
        for (int i = 0; i < tabs.size() && i < order.length; i++) {
            if (order[i] != activeTab) {
                continue;
            }
            Rect tab = tabs.get(i);
            RoundedRect.fill(graphics, tab.x(), tab.y(), tab.w(), tab.h(), Palette.RADIUS_SM, Palette.BG_3);
            // The accent sits on the bar's own edge, below the button, where a hover fill cannot cover it.
            RoundedRect.fill(graphics, tab.x() + 3, bar.bottom() - 1.5F, tab.w() - 6, 1.5F, 0.75F, Palette.ACCENT);
        }
    }

    private void drawSearchField(GuiGraphics graphics, double mouseX, double mouseY) {
        Rect field = frame.search();
        boolean focused = searchBox != null && searchBox.isFocused();
        boolean over = field.contains(mouseX, mouseY);
        if (over) {
            HandCursor.request(HandCursor.Kind.IBEAM);
        }
        RoundedRect.fill(graphics, field.x(), field.y(), field.w(), field.h(), Palette.RADIUS_SM, Palette.BG_3);
        RoundedRect.stroke(graphics, field.x(), field.y(), field.w(), field.h(), Palette.RADIUS_SM, 1F,
                focused ? Palette.ACCENT : over ? Palette.LINE : Palette.withAlpha(Palette.LINE, 0.5F));
        WorkshopDraw.icon(graphics, frame.searchIcon(), UiIcon.SEARCH, focused ? Palette.ACCENT : Palette.TEXT_MUTE);
    }

    private void drawFooter(GuiGraphics graphics) {
        Rect footer = frame.footer();
        RoundedRect.fill(graphics, 0, footer.y(), this.width, footer.h(), 0, Palette.BG_1);
        RoundedRect.fill(graphics, 0, footer.y(), this.width, 1, 0, Palette.LINE);
        Rect status = frame.status();
        String publishStatus = ClientTrackPublisher.status();
        // Publishing owns the status line while it has something to say; the copy and delete results wait their turn
        // rather than being drawn on top of it, which is what the two of them used to do.
        if (!publishStatus.isBlank()) {
            int colour = ClientTrackPublisher.succeeded() ? Palette.OK
                    : ClientTrackPublisher.isBusy() ? Palette.TEXT_2 : Palette.DANGER;
            line(graphics, publishStatus, TextStyle.CAPTION, status.x(), status.y(), status.w(), colour);
            if (ClientTrackPublisher.isBusy()) {
                WorkshopDraw.bar(graphics, frame.progress(), ClientTrackPublisher.progress(), Palette.BG_0, Palette.ACCENT);
            }
        } else if (activeTab == LibraryTab.LOCAL && !localActionStatus.isBlank()) {
            line(graphics, localActionStatus, TextStyle.CAPTION, status.x(), status.y(), status.w(),
                    localActionOk ? Palette.OK : Palette.DANGER);
        }
    }

    // ---------------------------------------------------------------- rows

    private void drawRows(GuiGraphics graphics, double mouseX, double mouseY) {
        int count = entryCount();
        if (count == 0) {
            drawEmptyState(graphics);
            return;
        }
        List<MusicBattleTracks.Track> localEntries = visibleLocalTracks();
        List<PublishedTracksPayload.Entry> publishedEntries = visiblePublishedTracks();
        List<WorkshopCatalog.InstalledSet> installedEntries = visibleInstalledSets();
        int start = scrollOffset;
        int end = Math.min(count, start + frame.rowsPerPage());
        float[] hover = new float[Math.max(0, end - start)];
        for (int i = start; i < end; i++) {
            Rect row = frame.row(i - start);
            hover[i - start] = hoverAmount(i - start, row.contains(mouseX, mouseY));
        }

        // Three passes, like the Workshop's card grid: every shape, then every cover, then all of the text and icons.
        // Covers draw immediately and flush what is queued, so anything drawn under one has to be queued before it.
        for (int i = start; i < end; i++) {
            drawRowShape(graphics, frame.row(i - start), hover[i - start]);
        }
        for (int i = start; i < end; i++) {
            Rect cover = frame.cover(frame.row(i - start));
            switch (activeTab) {
                case LOCAL -> drawCover(graphics, cover, previewTexture(localEntries.get(i)), UiIcon.MUSIC);
                case INSTALLED -> drawCover(graphics, cover, installedCover(installedEntries.get(i)), UiIcon.FOLDER_OPEN);
                case PUBLISHED -> drawCover(graphics, cover, publishedCover(publishedEntries.get(i)), UiIcon.UPLOAD);
            }
        }
        for (int i = start; i < end; i++) {
            Rect row = frame.row(i - start);
            switch (activeTab) {
                case LOCAL -> drawLocalRow(graphics, row, localEntries.get(i));
                case INSTALLED -> drawInstalledRow(graphics, row, installedEntries.get(i));
                case PUBLISHED -> drawPublishedRow(graphics, row, publishedEntries.get(i));
            }
        }
        WorkshopDraw.scrollbar(graphics, frame.scrollbar(), frame.scrollPixels(scrollOffset),
                frame.list().h() + frame.scrollPixels(maxScroll()), false);
    }

    /** Eases this row's hover towards the pointer; call once a frame, before the row is drawn. */
    private float hoverAmount(int visibleRow, boolean hovered) {
        if (visibleRow < 0 || visibleRow >= rowHover.size()) {
            return 0F;
        }
        Motion.Tween tween = rowHover.get(visibleRow);
        tween.animateTo(hovered ? 1.0 : 0.0, hovered ? Palette.T_CARD : Palette.T_CARD / 3.0, Motion.Easing.OUT_QUINT);
        return tween.value();
    }

    private void drawRowShape(GuiGraphics graphics, Rect row, float hover) {
        if (hover > 0.01F) {
            RoundedRect.shadow(graphics, row.x(), row.y() + 2, row.w(), row.h(), Palette.RADIUS_MD, 7F,
                    Palette.fade(Palette.SHADOW_LIFTED, hover));
        }
        RoundedRect.fill(graphics, row.x(), row.y(), row.w(), row.h(), Palette.RADIUS_MD,
                Palette.mix(Palette.BG_2, Palette.BG_3, hover));
        // The rail on the left edge lights up under the pointer, the way a lazer card lights its strip.
        RoundedRect.pill(graphics, row.x() + 1.5F, row.y() + 7, 2.5F, Math.max(4F, row.h() - 14),
                Palette.mix(Palette.LINE, Palette.ACCENT, hover));
    }

    private void drawCover(GuiGraphics graphics, Rect cover, ResourceLocation texture, UiIcon placeholder) {
        if (texture != null) {
            RoundedImage.draw(graphics, texture, cover.x(), cover.y(), cover.w(), cover.h(), Palette.RADIUS_SM, 0F, 1F);
            return;
        }
        RoundedRect.fill(graphics, cover.x(), cover.y(), cover.w(), cover.h(), Palette.RADIUS_SM, Palette.BG_3);
        WorkshopDraw.icon(graphics, cover.inset(cover.w() * 0.3F, cover.h() * 0.3F), placeholder,
                Palette.withAlpha(Palette.TEXT_MUTE, 0.7F));
    }

    private void drawLocalRow(GuiGraphics graphics, Rect row, MusicBattleTracks.Track track) {
        String stats = Component.translatable("screen.music_battle.library.track_stats", track.id(),
                String.valueOf(track.bpm()), String.format(Locale.ROOT, "%.2f", track.volume())).getString();
        String description = track.description().isBlank() ? text("music_battle.l10n.client.092") : track.description();
        drawRowText(graphics, row, localActionsLeft, track.title(), stats, description);
    }

    private void drawPublishedRow(GuiGraphics graphics, Rect row, PublishedTracksPayload.Entry track) {
        String stats = Component.translatable("screen.music_battle.library.published_stats", track.publishedId(),
                String.valueOf(track.bpm())).getString();
        String description = track.description().isBlank() ? text("music_battle.l10n.client.092") : track.description();
        drawRowText(graphics, row, publishedActionsLeft, track.title(), stats, description);
    }

    /** One installed Workshop beatmap: title, artist, mapper and its difficulties. */
    private void drawInstalledRow(GuiGraphics graphics, Rect row, WorkshopCatalog.InstalledSet set) {
        var manifest = set.manifest();
        String artist = Component.translatable("screen.music_battle.library.installed_artist", manifest.artist(),
                manifest.mapper().name()).getString();
        StringBuilder charts = new StringBuilder();
        for (var chart : manifest.charts()) {
            if (charts.length() > 0) {
                charts.append(", ");
            }
            charts.append(chart.difficulty());
        }
        String difficulties = Component.translatable("screen.music_battle.library.installed_charts",
                String.valueOf(manifest.charts().size()), charts.toString()).getString();
        drawRowText(graphics, row, installedActionsLeft, manifest.title(), artist, difficulties);
    }

    /**
     * The lines of a card, centred against the room the actions leave: the title, the numbers under it and the
     * description, which wraps to a second line when the row is tall enough and is dropped entirely on a narrow
     * window, where the buttons sit under the text and there is only height for the two lines that identify the card.
     */
    private void drawRowText(GuiGraphics graphics, Rect row, float actionsLeft, String title, String stats, String body) {
        float left = frame.cover(row).right() + Metrics.GAP + 2;
        boolean stacked = frame.stackedActions();
        float right = stacked ? row.right() - frame.pad() : actionsLeft - Metrics.GAP;
        float width = Math.max(20F, right - left);
        float lineHeight = Metrics.lineHeight(TextStyle.CAPTION);
        List<String> lines = stacked ? List.of()
                : TextFit.wrap(TextStyles.measurer(), body, TextStyle.CAPTION, width, row.h() >= 74 ? 2 : 1);
        float block = TextStyle.TITLE.capHeight() + 6 + TextStyle.CAPTION.capHeight()
                + (lines.isEmpty() ? 0 : 6 + lines.size() * lineHeight);
        float y = row.y() + Math.max(5F, (frame.textBottom(row) - row.y() - block) * 0.5F);
        line(graphics, title, TextStyle.TITLE, left, y, width, Palette.TEXT_1);
        y += TextStyle.TITLE.capHeight() + 6;
        line(graphics, stats, TextStyle.CAPTION, left, y, width, Palette.TEXT_MUTE);
        y += TextStyle.CAPTION.capHeight() + 6;
        for (String descriptionLine : lines) {
            line(graphics, descriptionLine, TextStyle.CAPTION, left, y, width, Palette.TEXT_3);
            y += lineHeight;
        }
    }

    /** Nothing to show: the reason, with the icon that goes with it, on the Workshop's empty panel. */
    private void drawEmptyState(GuiGraphics graphics) {
        Rect list = frame.list();
        Rect panel = new Rect(list.x(), list.y(), list.w(), Math.min(list.h(), 96F));
        RoundedRect.fill(graphics, panel.x(), panel.y(), panel.w(), panel.h(), Palette.RADIUS_MD, Palette.BG_1);
        RoundedRect.stroke(graphics, panel.x(), panel.y(), panel.w(), panel.h(), Palette.RADIUS_MD, 1F,
                Palette.withAlpha(Palette.LINE, 0.6F));
        boolean searching = !normalizedSearchQuery().isEmpty();
        boolean loading = !searching && activeTab == LibraryTab.PUBLISHED && ClientTrackPublisher.publishedTracksLoading();
        float iconCenterY = panel.centerY() - 12;
        if (loading) {
            WorkshopDraw.spinner(graphics, panel.centerX(), iconCenterY, 6F, Palette.ACCENT);
        } else {
            UiIcon icon = searching ? UiIcon.SEARCH : activeTab == LibraryTab.INSTALLED ? UiIcon.FOLDER_OPEN : UiIcon.MUSIC;
            WorkshopDraw.icon(graphics, new Rect(panel.centerX() - 7, iconCenterY - 7, 14, 14), icon,
                    Palette.withAlpha(Palette.TEXT_MUTE, 0.8F));
        }
        String message = searching
                ? Component.translatable("screen.music_battle.track_search_empty").getString()
                : activeTab == LibraryTab.PUBLISHED
                ? (ClientTrackPublisher.publishedTracksLoading() ? text("music_battle.l10n.client.127") : text("music_battle.l10n.client.128"))
                : activeTab == LibraryTab.INSTALLED
                ? Component.translatable("screen.music_battle.library.installed_empty").getString()
                : text("music_battle.l10n.client.129");
        float width = Math.max(40F, panel.w() - 32);
        float y = panel.centerY() + 2;
        for (String messageLine : TextFit.wrap(TextStyles.measurer(), message, TextStyle.BODY, width, 2)) {
            TextStyles.queueCentered(graphics, TextStyles.text(messageLine, TextStyle.BODY).getVisualOrderText(),
                    panel.centerX(), y, Palette.TEXT_2);
            y += Metrics.lineHeight(TextStyle.BODY);
        }
    }

    private void line(GuiGraphics graphics, String value, TextStyle style, float x, float y, float maxWidth, int colour) {
        String fitted = TextFit.ellipsize(TextStyles.measurer(), value, style, maxWidth);
        if (fitted.isEmpty()) {
            return;
        }
        TextStyles.queue(graphics, TextStyles.text(fitted, style).getVisualOrderText(), x, y, colour);
    }

    private void lineRight(GuiGraphics graphics, String value, TextStyle style, float right, float y, float maxWidth,
                           int colour) {
        String fitted = TextFit.ellipsize(TextStyles.measurer(), value, style, maxWidth);
        if (fitted.isEmpty()) {
            return;
        }
        TextStyles.queueRight(graphics, TextStyles.text(fitted, style).getVisualOrderText(), right, y, colour);
    }

    // ---------------------------------------------------------------- covers

    /** The cover a Workshop install already has on disk, through the shared cover cache. */
    private ResourceLocation installedCover(WorkshopCatalog.InstalledSet set) {
        return set.coverPresent() ? CoverCache.getFile(set.coverFile()) : null;
    }

    /**
     * The cover of a beatmap this player published to the server. The server's list does not say what picture each
     * one has, which is why this tab drew the same upload icon on every card.
     *
     * <p>A published beatmap keeps the id of the beatmap it was published from, so the first place to look is this
     * library: the same picture the My beatmaps tab shows, from disk, shared with that tab's cache and without asking
     * the server for anything. Only a beatmap whose source is not here - published from another computer, or deleted
     * locally since - or whose source has no usable picture asks the server, the way the Workshop's Server tab asks for
     * the picture of any beatmap it lists: once per beatmap per server, and the answer is kept on disk in the server's
     * asset cache.
     */
    private ResourceLocation publishedCover(PublishedTracksPayload.Entry entry) {
        MusicBattleTracks.Track source = localTrack(entry.sourceTrackId());
        if (source != null && !source.previewFile().isBlank() && !failedPreviews.contains(previewKey(source))) {
            return previewTexture(source);
        }
        // Every cover this mod writes is <id>_preview.png, and the server names a published copy's after the id it
        // publishes it under; the asset cache keeps only that last path segment, so this is all it needs to find it.
        BattleTracks.Track row = new BattleTracks.Track(entry.publishedId(), BattleTracks.Kind.PLAYERS, 0L, "",
                entry.title(), entry.description(), "", "", "", entry.bpm(), 0, entry.publishedId() + "_preview.png");
        return ServerWorkshopClient.trackPicture(row);
    }

    /** The local beatmap with exactly this id, from the unfiltered list: a search must not hide a published cover. */
    private MusicBattleTracks.Track localTrack(String id) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        for (MusicBattleTracks.Track track : tracks) {
            if (track.id().equals(id)) {
                return track;
            }
        }
        return null;
    }

    private ResourceLocation previewTexture(MusicBattleTracks.Track track) {
        if (track.previewFile() == null || track.previewFile().isBlank()) {
            return null;
        }
        String key = previewKey(track);
        ResourceLocation cached = previewTextures.get(key);
        if (cached != null) {
            return cached;
        }
        if (requestedPreviews.add(key)) {
            // A picture the beatmap names but the disk does not have counts as one that failed to load: otherwise a
            // published card would wait on it for good instead of asking the server for its own copy.
            MusicBattleTracks.previewPath(track).ifPresentOrElse(path -> requestTexture(key, track.id(), path),
                    () -> failedPreviews.add(key));
        }
        return null;
    }

    private void requestTexture(String key, String trackId, Path path) {
        int generation = previewGeneration;
        Minecraft client = this.minecraft == null ? Minecraft.getInstance() : this.minecraft;
        CompletableFuture.supplyAsync(() -> loadImage(path)).thenAccept(image -> client.execute(() -> {
            if (image == null) {
                // Remembered so the Published tab can ask the server for its copy instead of waiting on this one.
                if (generation == previewGeneration) {
                    failedPreviews.add(key);
                }
                return;
            }
            if (generation != previewGeneration || client.screen != this) {
                image.close();
                return;
            }
            String safeTrackId = trackId.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9_\\-]", "_");
            ResourceLocation location = MusicBattleMod.id("library_preview/" + safeTrackId + "_" + Integer.toUnsignedString(key.hashCode(), 36));
            client.getTextureManager().register(location, new DynamicTexture(image));
            previewTextures.put(key, location);
        }));
    }

    private NativeImage loadImage(Path path) {
        try (InputStream input = Files.newInputStream(path)) {
            return NativeImage.read(input);
        } catch (IOException exception) {
            return null;
        }
    }

    // ---------------------------------------------------------------- actions

    private void editVisibleTrack(int visibleRow) {
        int index = scrollOffset + visibleRow;
        List<MusicBattleTracks.Track> entries = visibleLocalTracks();
        if (index >= 0 && index < entries.size()) {
            this.minecraft.setScreen(new TrackEditorScreen(this, entries.get(index)));
        }
    }

    private void publishVisibleTrack(int visibleRow) {
        int index = scrollOffset + visibleRow;
        List<MusicBattleTracks.Track> entries = visibleLocalTracks();
        if (index < 0 || index >= entries.size()) {
            return;
        }
        // Two destinations: the Minecraft server this client is on, and the Workshop.
        this.minecraft.setScreen(new PublishTargetScreen(this, entries.get(index)));
    }

    /** Opens the Workshop's upload wizard with this track ticked. */
    void publishToWorkshop(MusicBattleTracks.Track track) {
        WorkshopScreen.openUploadFor(this, track.id());
    }

    /** Opens the Workshop's set page of an installed set (Installed tab). */
    private void openInstalledVisible(int visibleRow) {
        int index = scrollOffset + visibleRow;
        List<WorkshopCatalog.InstalledSet> entries = visibleInstalledSets();
        if (index >= 0 && index < entries.size()) {
            WorkshopScreen.openSet(this, entries.get(index).setId());
        }
    }

    boolean publishToServerAllowed() {
        return ClientTrackPublisher.canPublish() && !ClientTrackPublisher.isBusy();
    }

    void publishToServer(MusicBattleTracks.Track track) {
        this.minecraft.setScreen(new PublishTrackConfirmScreen(this, track));
    }

    private void copyVisibleTrack(int visibleRow) {
        int index = scrollOffset + visibleRow;
        List<MusicBattleTracks.Track> entries = visibleLocalTracks();
        if (index < 0 || index >= entries.size()) {
            return;
        }
        MusicBattleTracks.Track source = entries.get(index);
        try {
            String copyId = nextCopyId(source.id());
            String audio = source.audioFile().isBlank() ? "" : MusicBattleTracks.audioPath(source)
                    .orElseThrow(() -> new IOException(text("music_battle.l10n.client.130")))
                    .toString();
            String preview = source.previewFile().isBlank() ? "" : MusicBattleTracks.previewPath(source)
                    .orElseThrow(() -> new IOException(text("music_battle.l10n.client.131")))
                    .toString();
            ClientTrackExporter.export(copyId, source.title() + text("music_battle.l10n.client.132"), source.description(),
                    source.bpm(), source.offsetMs(), source.volume(), audio, preview,
                    source.disableStageBars(), source.hideStandardDimming(), source.notes(), source.effects());
            localActionStatus = text("music_battle.l10n.client.133") + copyId + text("music_battle.l10n.client.134");
            localActionOk = true;
            tracks = new ArrayList<>(MusicBattleTracks.loadTracks());
            tracks.sort(Comparator.comparing(MusicBattleTracks.Track::title, String.CASE_INSENSITIVE_ORDER));
            resetPreviewCache();
            rebuildWidgets();
        } catch (IOException exception) {
            localActionStatus = Component.translatable("screen.music_battle.library.error",
                    MusicBattleTracks.safeMessage(exception)).getString();
            localActionOk = false;
        }
    }

    private String nextCopyId(String sourceId) {
        String base = ClientTrackExporter.normalizeId(sourceId + "_copy");
        Set<String> existing = new HashSet<>();
        for (MusicBattleTracks.Track track : tracks) {
            existing.add(track.id().toLowerCase(java.util.Locale.ROOT));
        }
        String candidate = base;
        int suffix = 2;
        while (existing.contains(candidate.toLowerCase(java.util.Locale.ROOT))
                || Files.exists(MusicBattleTracks.tracksDirectory().resolve(candidate))) {
            candidate = ClientTrackExporter.normalizeId(base + "_" + suffix++);
        }
        return candidate;
    }

    private void deleteLocalVisibleTrack(int visibleRow) {
        int index = scrollOffset + visibleRow;
        List<MusicBattleTracks.Track> entries = visibleLocalTracks();
        if (index >= 0 && index < entries.size()) {
            this.minecraft.setScreen(new DeleteLocalTrackConfirmScreen(this, entries.get(index)));
        }
    }

    private void deletePublishedVisibleTrack(int visibleRow) {
        int index = scrollOffset + visibleRow;
        List<PublishedTracksPayload.Entry> entries = visiblePublishedTracks();
        if (index >= 0 && index < entries.size() && !ClientTrackPublisher.isBusy()) {
            PublishedTracksPayload.Entry track = entries.get(index);
            this.minecraft.setScreen(new DeletePublishedTrackConfirmScreen(this, track.title(), track.sourceTrackId()));
        }
    }

    private void updateButtons() {
        if (createButton != null) {
            createButton.visible = activeTab == LibraryTab.LOCAL;
            createButton.active = createButton.visible;
        }
        int localCount = visibleLocalTracks().size();
        int publishedCount = visiblePublishedTracks().size();
        int installedCount = visibleInstalledSets().size();
        for (int row = 0; row < editButtons.size(); row++) {
            boolean localTrack = activeTab == LibraryTab.LOCAL && scrollOffset + row < localCount;
            boolean publishedTrack = activeTab == LibraryTab.PUBLISHED && scrollOffset + row < publishedCount;
            Button button = editButtons.get(row);
            button.visible = localTrack;
            button.active = localTrack;
            Button publish = publishButtons.get(row);
            publish.visible = localTrack;
            publish.active = localTrack;
            Button deleteLocal = deleteLocalButtons.get(row);
            deleteLocal.visible = localTrack;
            deleteLocal.active = localTrack;
            Button copy = copyButtons.get(row);
            copy.visible = localTrack;
            copy.active = localTrack;
            Button deleteServer = deleteServerButtons.get(row);
            deleteServer.visible = publishedTrack;
            deleteServer.active = publishedTrack && !ClientTrackPublisher.isBusy();
            boolean installedSet = activeTab == LibraryTab.INSTALLED && scrollOffset + row < installedCount;
            Button workshop = openWorkshopButtons.get(row);
            workshop.visible = installedSet;
            workshop.active = installedSet;
        }
    }

    private void switchTab(LibraryTab tab) {
        if (activeTab == tab) {
            if (tab == LibraryTab.PUBLISHED) ClientTrackPublisher.requestPublishedTracks();
            return;
        }
        activeTab = tab;
        scrollOffset = 0;
        if (tab == LibraryTab.PUBLISHED) ClientTrackPublisher.requestPublishedTracks();
        if (tab == LibraryTab.INSTALLED) {
            WorkshopClient client = WorkshopClient.get();
            client.io().execute(() -> {
                client.catalog().refresh();
                Minecraft.getInstance().execute(() -> {
                    installedSets = client.catalog().all();
                    updateButtons();
                });
            });
        }
        updateButtons();
    }

    private void refreshPublishedSnapshot() {
        List<PublishedTracksPayload.Entry> latest = ClientTrackPublisher.publishedTracks();
        if (publishedTracks != latest) {
            publishedTracks = latest;
            scrollOffset = Math.max(0, Math.min(maxScroll(), scrollOffset));
        }
    }

    private int entryCount() {
        return switch (activeTab) {
            case LOCAL -> visibleLocalTracks().size();
            case PUBLISHED -> visiblePublishedTracks().size();
            case INSTALLED -> visibleInstalledSets().size();
        };
    }

    private List<WorkshopCatalog.InstalledSet> visibleInstalledSets() {
        String query = normalizedSearchQuery();
        if (query.isEmpty()) {
            return installedSets;
        }
        return installedSets.stream()
                .filter(set -> searchable(set.manifest().title()).contains(query)
                        || searchable(set.manifest().artist()).contains(query)
                        || searchable(set.manifest().mapper().name()).contains(query))
                .toList();
    }

    private List<MusicBattleTracks.Track> visibleLocalTracks() {
        String query = normalizedSearchQuery();
        if (query.isEmpty()) {
            return tracks;
        }
        return tracks.stream()
                .filter(track -> searchable(track.title()).contains(query)
                        || searchable(track.id()).contains(query))
                .toList();
    }

    private List<PublishedTracksPayload.Entry> visiblePublishedTracks() {
        String query = normalizedSearchQuery();
        if (query.isEmpty()) {
            return publishedTracks;
        }
        return publishedTracks.stream()
                .filter(track -> searchable(track.title()).contains(query)
                        || searchable(track.sourceTrackId()).contains(query)
                        || searchable(track.publishedId()).contains(query))
                .toList();
    }

    private String normalizedSearchQuery() {
        return searchable(searchBox == null ? searchQuery : searchBox.getValue());
    }

    private static String searchable(String value) {
        return value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
    }

    private String previewKey(MusicBattleTracks.Track track) {
        return track.id() + '\0' + track.previewFile();
    }

    private void resetPreviewCache() {
        previewGeneration++;
        releasePreviewTextures();
        requestedPreviews.clear();
        failedPreviews.clear();
    }

    private void releasePreviewTextures() {
        if (this.minecraft != null) {
            for (ResourceLocation location : previewTextures.values()) {
                this.minecraft.getTextureManager().release(location);
            }
        }
        previewTextures.clear();
    }

    private int rowsPerPage() {
        return frame == null ? 1 : frame.rowsPerPage();
    }

    private int maxScroll() {
        return Math.max(0, entryCount() - rowsPerPage());
    }

    private String shorten(String text) {
        int max = Math.max(24, this.width / 6);
        if (text.length() <= max) {
            return text;
        }
        return "..." + text.substring(text.length() - max + 3);
    }

    private enum LibraryTab {
        LOCAL,
        PUBLISHED,
        /** Beatmaps installed from the Workshop (client-only catalogue). */
        INSTALLED
    }

    /**
     * The tabs across the content column, sized the way SettingsScreenLayout.nav sizes the settings tabs: each as wide as
     * its label needs in the face its button draws it in, plus padding. While every label fits an equal share the three
     * keep the equal widths the strip always had; past that each tab takes its own need and the room left is shared, so
     * the strip still spans the column. A bar tall enough for the body face that cannot hold the labels in it sets them
     * in captions, the face of a compact bar, as the settings tabs drop to captions before they wrap.
     *
     * <p>The layout's equal thirds cut the Russian "Опубликованные карты" to "Опубликованные кар…" on a window narrower
     * than 355 units. A label is cut only when even captions at their own widths overflow the column, which neither
     * shipped language does from 320 units up: the library-tabs check measures that with Minecraft's own glyph metrics.
     * A second row would need TrackLibraryLayout to grow the bar. Pure, so the check runs it without a window.
     */
    static final class Tabs {
        /** Room either side of a label: more than a button's own five units, so the hover fill reads as a tab. */
        static final float PAD = 7.0F;
        /** The height under which FancyButton draws its label in captions, and the caption tabs' height in a tall bar. */
        static final float BODY_HEIGHT = 18.0F;
        static final float CAPTION_HEIGHT = 16.0F;

        /** The tabs' rectangles, the face their labels are drawn in, and whether a label had to be cut after all. */
        record Strip(List<Rect> tabs, TextStyle face, boolean cut) {
        }

        private Tabs() {
        }

        static Strip arrange(TrackLibraryLayout frame, ru.gora.musicbattle.client.ui.TextMeasurer measurer, List<String> labels) {
            Rect column = frame.column();
            float y = frame.tabBar().y() + 1;
            float height = frame.tabBar().h() - 2;
            Strip strip = fit(column, y, height, measurer, labels, false);
            if (strip == null && height >= BODY_HEIGHT) {
                strip = fit(column, y + (height - CAPTION_HEIGHT) * 0.5F, CAPTION_HEIGHT, measurer, labels, false);
            }
            if (strip == null) {
                strip = fit(column, y, Math.min(height, CAPTION_HEIGHT), measurer, labels, true);
            }
            return strip;
        }

        /** The face FancyButton draws a label in at a height. */
        static TextStyle face(float height) {
            return height >= BODY_HEIGHT ? TextStyle.BODY : TextStyle.CAPTION;
        }

        /** One row of tabs at this height, or null when their labels need more than the column; with {@code cut}, equal shares. */
        private static Strip fit(Rect column, float y, float height, ru.gora.musicbattle.client.ui.TextMeasurer measurer,
                                 List<String> labels, boolean cut) {
            int count = labels.size();
            TextStyle face = face(height);
            float gaps = Metrics.GAP_SM * (count - 1);
            float[] needs = new float[count];
            float total = gaps;
            float widest = 0F;
            for (int index = 0; index < count; index++) {
                needs[index] = (float) Math.ceil(TextFit.width(measurer, labels.get(index), face) + PAD * 2 + 0.05F);
                total += needs[index];
                widest = Math.max(widest, needs[index]);
            }
            float share = (column.w() - gaps) / count;
            float[] widths = new float[count];
            if (widest <= share || cut) {
                java.util.Arrays.fill(widths, share);
            } else if (total <= column.w()) {
                float extra = (column.w() - total) / count;
                for (int index = 0; index < count; index++) {
                    widths[index] = needs[index] + extra;
                }
            } else {
                return null;
            }
            List<Rect> tabs = new ArrayList<>(count);
            float x = column.x();
            for (float width : widths) {
                tabs.add(new Rect(x, y, width, height));
                x += width + Metrics.GAP_SM;
            }
            return new Strip(tabs, face, cut && total > column.w());
        }
    }
}

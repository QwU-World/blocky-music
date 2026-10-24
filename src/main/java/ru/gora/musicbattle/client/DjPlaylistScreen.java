package ru.gora.musicbattle.client;

import java.util.ArrayList;
import ru.gora.musicbattle.client.ui.Palette;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.network.PacketDistributor;
import ru.gora.musicbattle.data.DjPlaylistEntry;
import ru.gora.musicbattle.data.TrackSummary;
import ru.gora.musicbattle.network.ConfigureDjPlaylistPayload;

/** Server-backed playlist editor. Duplicate tracks are identified by stable entry UUIDs. */
final class DjPlaylistScreen extends Screen implements DjPanelOwnedScreen {
    private static final int ROW_HEIGHT = 22;
    /** How long a line about a Workshop pick stays under the title. */
    private static final long STATUS_MS = 12_000L;
    /**
     * The latest word about a Workshop pick. It is kept here rather than on one screen because the answer may arrive
     * while the picker or another DJ screen is on top, and the playlist should still say it when the DJ comes back.
     */
    private static Component workshopStatus;
    private static boolean workshopStatusProblem;
    private static long workshopStatusUntilMs;
    /** The Workshop pick on its way to the server or last refused, and the panel it was picked for. */
    private static ru.gora.musicbattle.client.workshop.layout.BattleSetup.Pick workshopPick;
    private static net.minecraft.core.BlockPos workshopPickPanel;
    /**
     * Whether the line is a refusal of {@link #workshopPick}. Such a line stays until the DJ retries or removes it: a
     * refusal that faded on its own left the DJ unsure whether the map was still coming.
     */
    private static boolean workshopRetryable;

    private final DjPanelScreen parent;
    private final List<DjPlaylistEntry> entries = new ArrayList<>();
    private final List<TrackSummary> tracks = new ArrayList<>();
    private int selectedIndex = -1;
    private int trackIndex;
    private int scroll;
    private long seenRevision = Long.MIN_VALUE;
    private boolean autoAdvance;
    private int repeatMode;
    private int cueSourceTicks;
    private boolean updatingCueText;
    private boolean clearArmed;
    private boolean selectLastAfterSync;
    private int refreshCooldown;
    /** Where the refusal line's Retry and Remove were drawn last frame; empty when they were not. */
    private int retryLeft, retryRight, removeLeft, removeRight, linksTop = Integer.MIN_VALUE;

    private Button trackButton;
    private Button addButton;
    private Button workshopButton;
    private Button removeButton;
    private Button upButton;
    private Button downButton;
    private Button playButton;
    private Button clearButton;
    private Button autoButton;
    private Button repeatButton;
    private Button applyCueButton;
    private final List<Button> fxModeButtons = new ArrayList<>();
    private CueSlider cueSlider;
    private CueField secondsBox;

    DjPlaylistScreen(DjPanelScreen parent) {
        super(Component.translatable("screen.music_battle.dj_playlist.title"));
        this.parent = parent;
        syncFromParent(true);
    }

    @Override
    protected void init() {
        int left = panelLeft();
        int contentWidth = panelWidth() - 24;
        int top = panelTop() + 34;
        int buttonHeight = 20;
        int addWidth = Math.min(92, Math.max(62, contentWidth / 5));
        int workshopWidth = Math.min(110, Math.max(70, contentWidth / 4));
        int trackWidth = contentWidth - addWidth - workshopWidth - 10;
        trackButton = addRenderableWidget(FancyButton.create(trackLabel(), ignored -> openTrackPicker(),
                left + 12, top, trackWidth, buttonHeight));
        addButton = addRenderableWidget(FancyButton.create(
                Component.translatable("screen.music_battle.dj_playlist.add"), ignored -> addEntry(),
                left + 12 + trackWidth + 5, top, addWidth, buttonHeight));
        workshopButton = addRenderableWidget(FancyButton.create(
                Component.translatable("screen.music_battle.dj_playlist.workshop"), ignored -> openWorkshop(),
                left + 12 + trackWidth + addWidth + 10, top, workshopWidth, buttonHeight));

        int cueY = listBottom() + 8;
        int secondsWidth = Math.min(72, Math.max(54, contentWidth / 7));
        int applyWidth = Math.min(78, Math.max(54, contentWidth / 7));
        int sliderWidth = Math.max(60, contentWidth - secondsWidth - applyWidth - 10);
        cueSlider = addRenderableWidget(new CueSlider(left + 12, cueY, sliderWidth, buttonHeight));
        secondsBox = addRenderableWidget(new CueField(left + 12 + sliderWidth + 5, cueY, secondsWidth, buttonHeight));
        applyCueButton = addRenderableWidget(FancyButton.create(
                Component.translatable("screen.music_battle.dj_playlist.apply_cue"), ignored -> applyCue(),
                left + 12 + sliderWidth + secondsWidth + 10, cueY, applyWidth, buttonHeight));

        // Three rows under the cue: the selected entry's effects, then editing the list, then playing it. Nine
        // buttons on one line left Russian at GUI scale 3 with "Игр...", "Пов..." and "Очи...", and the band the third
        // row now takes was empty anyway.
        int gap = 4;
        int rowStep = buttonHeight + gap;
        int playY = panelTop() + panelHeight() - 30;
        int editY = playY - rowStep;
        int fxY = editY - rowStep;
        fxModeButtons.clear();
        int modeWidth = (contentWidth - gap * 2) / 3;
        for (int i = 0; i < 3; i++) {
            final int mode = i;
            fxModeButtons.add(addRenderableWidget(FancyButton.create(
                    Component.translatable("screen.music_battle.dj_playlist.fx_" + new String[]{"track","dj","none"}[i]),
                    ignored -> chooseFxMode(mode), left + 12 + i * (modeWidth + gap), fxY,
                    i == 2 ? contentWidth - 2 * (modeWidth + gap) : modeWidth, buttonHeight)));
        }
        // The arrows are not lang keys; a key the language lacks reads as itself, which is all they need here.
        int[] edit = shareRow(contentWidth, gap, new String[][]{{"↑"}, {"↓"}, {"×"},
                {"screen.music_battle.dj_playlist.clear", "screen.music_battle.dj_playlist.clear_confirm"}});
        int x = left + 12;
        upButton = addRenderableWidget(FancyButton.create(Component.literal("↑"), ignored -> move(-1),
                x, editY, edit[0], buttonHeight));
        x += edit[0] + gap;
        downButton = addRenderableWidget(FancyButton.create(Component.literal("↓"), ignored -> move(1),
                x, editY, edit[1], buttonHeight));
        x += edit[1] + gap;
        removeButton = addRenderableWidget(FancyButton.create(Component.literal("×"), ignored -> remove(),
                x, editY, edit[2], buttonHeight));
        x += edit[2] + gap;
        clearButton = addRenderableWidget(FancyButton.create(
                Component.translatable("screen.music_battle.dj_playlist.clear"), ignored -> clear(),
                x, editY, edit[3], buttonHeight));

        int[] play = shareRow(contentWidth, gap, new String[][]{
                {"screen.music_battle.dj_playlist.play"},
                {"screen.music_battle.dj_playlist.auto_on", "screen.music_battle.dj_playlist.auto_off"},
                {"screen.music_battle.dj_playlist.repeat_off", "screen.music_battle.dj_playlist.repeat_one",
                        "screen.music_battle.dj_playlist.repeat_all"},
                {"screen.music_battle.dj_playlist.back"}});
        x = left + 12;
        playButton = addRenderableWidget(FancyButton.create(
                Component.translatable("screen.music_battle.dj_playlist.play"), ignored -> play(),
                x, playY, play[0], buttonHeight));
        x += play[0] + gap;
        autoButton = addRenderableWidget(FancyButton.create(autoLabel(), ignored -> toggleAuto(),
                x, playY, play[1], buttonHeight));
        x += play[1] + gap;
        repeatButton = addRenderableWidget(FancyButton.create(repeatLabel(), ignored -> toggleRepeat(),
                x, playY, play[2], buttonHeight));
        x += play[2] + gap;
        addRenderableWidget(FancyButton.create(Component.translatable("screen.music_battle.dj_playlist.back"),
                ignored -> onClose(), x, playY, play[3], buttonHeight));
        loadCueWidgets();
        refreshButtons();
    }

    @Override
    public void tick() {
        super.tick();
        if (seenRevision != parent.playlistRevision()) {
            syncFromParent(false);
        }
        if (--refreshCooldown <= 0) {
            send(ConfigureDjPlaylistPayload.Action.REFRESH, null, "", 0, -1, autoAdvance);
            refreshCooldown = 20;
        }
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, Palette.withAlpha(Palette.BG_0, 0.95F));
        int left = panelLeft();
        int top = panelTop();
        graphics.fill(left, top, left + panelWidth(), top + panelHeight(), Palette.BG_1);
        graphics.fill(left, top, left + panelWidth(), top + 3, Palette.ACCENT);
        graphics.drawCenteredString(font, title, left + panelWidth() / 2, top + 11, Palette.TEXT_1);
        linksTop = Integer.MIN_VALUE;
        if (workshopStatus != null && workshopRetryable) {
            drawRefusalLine(graphics, mouseX, mouseY, left, top + 22);
        } else if (workshopStatus != null && System.currentTimeMillis() < workshopStatusUntilMs) {
            graphics.drawCenteredString(font, fit(workshopStatus.getString(), panelWidth() - 24),
                    left + panelWidth() / 2, top + 22, workshopStatusProblem ? Palette.DANGER : Palette.OK);
        }
        drawEntries(graphics, mouseX, mouseY);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    /** The refusal on the left, then Retry and Remove as text links at the right edge, where the line has room. */
    private void drawRefusalLine(GuiGraphics graphics, int mouseX, int mouseY, int left, int y) {
        Component retry = Component.translatable("screen.music_battle.dj_playlist.workshop.retry");
        Component remove = Component.translatable("screen.music_battle.dj_playlist.workshop.remove");
        removeRight = left + panelWidth() - 12;
        removeLeft = removeRight - font.width(remove);
        retryRight = removeLeft - 10;
        retryLeft = retryRight - font.width(retry);
        linksTop = y;
        graphics.drawString(font, fit(workshopStatus.getString(), retryLeft - 10 - (left + 12)), left + 12, y,
                Palette.DANGER, false);
        boolean overRetry = overLink(mouseX, mouseY, retryLeft, retryRight);
        boolean overRemove = overLink(mouseX, mouseY, removeLeft, removeRight);
        graphics.drawString(font, overRetry ? retry.copy().withStyle(net.minecraft.ChatFormatting.UNDERLINE) : retry,
                retryLeft, y, overRetry ? Palette.TEXT_1 : Palette.LINK, false);
        graphics.drawString(font, overRemove ? remove.copy().withStyle(net.minecraft.ChatFormatting.UNDERLINE) : remove,
                removeLeft, y, overRemove ? Palette.TEXT_1 : Palette.TEXT_3, false);
    }

    private boolean overLink(double mouseX, double mouseY, int linkLeft, int linkRight) {
        return linksTop != Integer.MIN_VALUE && mouseX >= linkLeft - 2 && mouseX < linkRight + 2
                && mouseY >= linksTop - 2 && mouseY < linksTop + font.lineHeight + 1;
    }

    /** Sends the refused pick again by the same door, or drops the line when the DJ would rather not. */
    private void retryWorkshopPick() {
        ru.gora.musicbattle.client.workshop.layout.BattleSetup.Pick pick = workshopPick;
        workshopRetryable = false;
        workshopStatus = null;
        if (pick == null || !parent.playlistPanelPos().equals(workshopPickPanel)) return;
        chooseFromWorkshop(pick);
    }

    private static void removeWorkshopLine() {
        workshopRetryable = false;
        workshopStatus = null;
        workshopPick = null;
    }

    private void drawEntries(GuiGraphics graphics, int mouseX, int mouseY) {
        int left = panelLeft() + 12;
        int right = panelLeft() + panelWidth() - 12;
        int top = listTop();
        int bottom = listBottom();
        graphics.fill(left - 2, top - 2, right + 2, bottom + 2, Palette.BG_0);
        int rows = visibleRows();
        scroll = Mth.clamp(scroll, 0, Math.max(0, entries.size() - rows));
        UUID activeId = parent.activePlaylistEntryId();
        for (int row = 0; row < rows; row++) {
            int index = scroll + row;
            if (index >= entries.size()) break;
            DjPlaylistEntry entry = entries.get(index);
            int y = top + row * ROW_HEIGHT;
            boolean hovered = mouseX >= left && mouseX < right && mouseY >= y && mouseY < y + ROW_HEIGHT - 2;
            boolean active = entry.entryId().equals(activeId);
            int color = index == selectedIndex ? Palette.withAlpha(Palette.ACCENT_DEEP, 0.8F) : hovered ? Palette.withAlpha(Palette.BG_4, 0.67F) : Palette.withAlpha(Palette.BG_3, 0.6F);
            graphics.fill(left, y, right, y + ROW_HEIGHT - 2, color);
            graphics.fill(left, y, left + 3, y + ROW_HEIGHT - 2,
                    active ? Palette.OK : index == selectedIndex ? Palette.ACCENT : Palette.ACCENT_STRONG);
            String title = track(entry.trackId()) == null ? entry.trackId() : track(entry.trackId()).title();
            String line = (index + 1) + ". " + title + "  @ " + formatTicks(entry.startSourceTicks());
            if (active) line = "▶ " + line;
            graphics.drawString(font, fit(line, right - left - 14), left + 8, y + 6, Palette.TEXT_1, false);
        }
        if (entries.isEmpty()) {
            graphics.drawCenteredString(font, Component.translatable("screen.music_battle.dj_playlist.empty"),
                    (left + right) / 2, top + Math.max(5, (bottom - top - font.lineHeight) / 2), Palette.TEXT_3);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && workshopRetryable && overLink(mouseX, mouseY, retryLeft, retryRight)) {
            retryWorkshopPick();
            return true;
        }
        if (button == 0 && workshopRetryable && overLink(mouseX, mouseY, removeLeft, removeRight)) {
            removeWorkshopLine();
            return true;
        }
        if (button == 0 && mouseX >= panelLeft() + 12 && mouseX < panelLeft() + panelWidth() - 12
                && mouseY >= listTop() && mouseY < listBottom()) {
            int index = scroll + (int) ((mouseY - listTop()) / ROW_HEIGHT);
            if (index >= 0 && index < entries.size()) {
                selectedIndex = index;
                clearArmed = false;
                loadCueWidgets();
                refreshButtons();
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (mouseX >= panelLeft() + 12 && mouseX < panelLeft() + panelWidth() - 12
                && mouseY >= listTop() && mouseY < listBottom()) {
            scroll = Mth.clamp(scroll - (int) Math.signum(scrollY),
                    0, Math.max(0, entries.size() - visibleRows()));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    private void syncFromParent(boolean initial) {
        UUID selectedId = selectedEntry() == null ? null : selectedEntry().entryId();
        entries.clear();
        entries.addAll(parent.playlistEntries());
        tracks.clear();
        tracks.addAll(parent.playlistTracks());
        autoAdvance = parent.playlistAutoAdvance();
        repeatMode = Mth.clamp(parent.playlistRepeat(), 0, 2);
        seenRevision = parent.playlistRevision();
        if (selectLastAfterSync && !entries.isEmpty()) {
            selectedIndex = entries.size() - 1;
            selectLastAfterSync = false;
        } else if (selectedId != null) {
            selectedIndex = indexOf(selectedId);
        } else if (initial && !entries.isEmpty()) {
            selectedIndex = 0;
        }
        selectedIndex = entries.isEmpty() ? -1 : Mth.clamp(selectedIndex, 0, entries.size() - 1);
        if (initial && !tracks.isEmpty()) {
            String selectedTrackId = parent.playlistSelectedTrackId();
            for (int index = 0; index < tracks.size(); index++) {
                if (tracks.get(index).id().equals(selectedTrackId)) {
                    trackIndex = index;
                    break;
                }
            }
        }
        trackIndex = tracks.isEmpty() ? 0 : Mth.clamp(trackIndex, 0, tracks.size() - 1);
        if (trackButton != null) trackButton.setMessage(trackLabel());
        if (autoButton != null) autoButton.setMessage(autoLabel());
        if (repeatButton != null) repeatButton.setMessage(repeatLabel());
        loadCueWidgets();
        refreshButtons();
    }

    private void openTrackPicker() {
        if (minecraft == null) return;
        minecraft.setScreen(new DjPlaylistTrackPickerScreen(this, parent, chosen -> {
            for (int i=0;i<tracks.size();i++) if(tracks.get(i).id().equals(chosen.id())) { trackIndex=i; break; }
        }));
    }

    /**
     * The Workshop as this playlist's picker: Browse, Installed and the Server tab with every track this panel lists.
     * A choice lands in the playlist like the Add button's, so from there it is an ordinary row with FX, mixer, speed
     * and the audience preload of any server track.
     */
    private void openWorkshop() {
        if (minecraft == null) return;
        ru.gora.musicbattle.client.workshop.WorkshopScreen.openDjPicker(this,
                new ru.gora.musicbattle.client.workshop.WorkshopScreen.Picker(pickerTracks(tracks),
                        this::chooseFromWorkshop, null, this::pickerPreview));
    }

    /**
     * A map the server already plays is queued by its id at once. Anything else has to reach the server first, which
     * is the Workshop's business; this screen only hears how it goes.
     */
    /**
     * The Workshop picker opened from the panel's own track library. Backing out returns to the panel; a pick opens
     * the playlist, because that is where the song is added and where its download or refusal is reported.
     */
    static void openWorkshopFromPanel(DjPanelScreen panel) {
        DjPlaylistScreen playlist = new DjPlaylistScreen(panel);
        ru.gora.musicbattle.client.workshop.WorkshopScreen.openDjPicker(panel,
                new ru.gora.musicbattle.client.workshop.WorkshopScreen.Picker(pickerTracks(playlist.tracks), pick -> {
                    // The picker may stay open long enough for the server to change the playlist underneath it.
                    playlist.syncFromParent(false);
                    net.minecraft.client.Minecraft.getInstance().setScreen(playlist);
                    playlist.chooseFromWorkshop(pick);
                }, null, playlist::pickerPreview));
    }

    private void chooseFromWorkshop(ru.gora.musicbattle.client.workshop.layout.BattleSetup.Pick pick) {
        if (pick == null) return;
        // A new pick replaces any refusal still on the line, so its Retry never resends an older map.
        workshopPick = null;
        workshopRetryable = false;
        if (entries.size() >= DjPlaylistEntry.MAX_ENTRIES) {
            workshopStatus(Component.translatable("screen.music_battle.dj_playlist.workshop.full"), true, null);
            return;
        }
        if (pick.serverTrack()) {
            queueTrack(pick.trackId());
            return;
        }
        if (pick.setId() <= 0 || pick.chartKey().isEmpty()) return;
        String chartId = ru.gora.musicbattle.workshop.WorkshopLayout.chartId(pick.setId(), pick.chartKey());
        if (track(chartId) != null) {
            queueTrack(chartId);
            return;
        }
        workshopPick = pick;
        workshopPickPanel = parent.playlistPanelPos();
        ru.gora.musicbattle.client.workshop.WorkshopScreen.bringToDj(parent.playlistPanelPos(), pick,
                (status, problem) -> workshopStatus(status, problem, null));
    }

    private void queueTrack(String trackId) {
        selectLastAfterSync = true;
        send(ConfigureDjPlaylistPayload.Action.ADD, null, trackId, 0, -1, autoAdvance);
    }

    /**
     * Shows a line about a Workshop pick under the title. {@code addedTo} names the panel whose playlist just gained
     * the chart, so an open playlist for it selects the new last row and asks for the fresh list now instead of
     * on its next one-second refresh.
     */
    static void workshopStatus(Component message, boolean problem, net.minecraft.core.BlockPos addedTo) {
        workshopStatus = message;
        workshopStatusProblem = problem;
        workshopStatusUntilMs = System.currentTimeMillis() + STATUS_MS;
        // A failed download of the current pick can be retried like a refusal; news of success ends the pick.
        workshopRetryable = problem && workshopPick != null;
        if (!problem && addedTo != null) workshopPick = null;
        if (addedTo != null && net.minecraft.client.Minecraft.getInstance().screen instanceof DjPlaylistScreen open
                && addedTo.equals(open.parent.playlistPanelPos())) {
            open.selectLastAfterSync = true;
            open.refreshCooldown = 0;
        }
    }

    /** The server will ask for the DJ's installed copy of the pick: the line says it is on its way. */
    static void workshopSending(long setId) {
        ru.gora.musicbattle.client.workshop.layout.BattleSetup.Pick pick = workshopPick;
        if (pick == null || pick.setId() != setId) return;
        workshopStatus(Component.translatable("screen.music_battle.dj_playlist.workshop.sending", pick.title()),
                false, null);
    }

    /**
     * The server refused a Workshop pick, possibly long after it said the map could come. A refusal of the current
     * pick offers Retry and Remove; one about another set is only shown.
     */
    static void workshopRefused(Component message, long setId) {
        if (workshopPick != null && workshopPick.setId() != setId) workshopPick = null;
        workshopStatus(message, true, null);
    }

    /** The panel's tracks as the picker's Server tab lists them, the way a battle's vote list is read. */
    private static ru.gora.musicbattle.client.workshop.layout.BattleTracks pickerTracks(List<TrackSummary> tracks) {
        List<ru.gora.musicbattle.client.workshop.layout.BattleTracks.Track> rows = new ArrayList<>(tracks.size());
        for (TrackSummary track : tracks) {
            ru.gora.musicbattle.client.workshop.layout.BattleTracks.Kind kind = switch (track.source()) {
                case WORKSHOP -> ru.gora.musicbattle.client.workshop.layout.BattleTracks.Kind.WORKSHOP;
                case USER -> ru.gora.musicbattle.client.workshop.layout.BattleTracks.Kind.PLAYERS;
                case SERVER -> ru.gora.musicbattle.client.workshop.layout.BattleTracks.Kind.SERVER;
                case INSTALLED -> null;
            };
            if (kind == null) continue;
            String chartKey = kind == ru.gora.musicbattle.client.workshop.layout.BattleTracks.Kind.WORKSHOP
                    ? ru.gora.musicbattle.client.workshop.layout.BattleTracks.chartKeyOf(track.id(), track.setId()) : "";
            rows.add(new ru.gora.musicbattle.client.workshop.layout.BattleTracks.Track(track.id(), kind, track.setId(),
                    chartKey, track.title(), track.description(), track.publishedBy(), track.mapper(),
                    track.contentSha256(), track.bpm(), track.durationTicks(), track.previewFile()));
        }
        return new ru.gora.musicbattle.client.workshop.layout.BattleTracks(rows, false);
    }

    private net.minecraft.resources.ResourceLocation pickerPreview(
            ru.gora.musicbattle.client.workshop.layout.BattleTracks.Track row) {
        TrackSummary summary = row == null ? null : track(row.id());
        return summary == null ? null : parent.previewTexture(summary);
    }

    private void chooseFxMode(int mode) {
        DjPlaylistEntry entry = selectedEntry();
        if (entry == null) return;
        ConfigureDjPlaylistPayload.Action action = new ConfigureDjPlaylistPayload.Action[]{
                ConfigureDjPlaylistPayload.Action.FX_TRACK, ConfigureDjPlaylistPayload.Action.FX_DJ,
                ConfigureDjPlaylistPayload.Action.FX_NONE}[mode];
        send(action, entry.entryId(), "", 0, -1, autoAdvance);
    }

    private void addEntry() {
        if (tracks.isEmpty() || entries.size() >= DjPlaylistEntry.MAX_ENTRIES) return;
        TrackSummary track = tracks.get(trackIndex);
        selectLastAfterSync = true;
        // Cue controls edit the selected queue row. A newly chosen track must not silently
        // inherit that row's timestamp; add at zero, select it, then edit its exact cue.
        send(ConfigureDjPlaylistPayload.Action.ADD, null, track.id(), 0, -1, autoAdvance);
    }

    private void remove() {
        DjPlaylistEntry entry = selectedEntry();
        if (entry == null) return;
        send(ConfigureDjPlaylistPayload.Action.REMOVE, entry.entryId(), "", 0, -1, autoAdvance);
    }

    private void move(int delta) {
        DjPlaylistEntry entry = selectedEntry();
        if (entry == null) return;
        int target = Mth.clamp(selectedIndex + delta, 0, entries.size() - 1);
        if (target == selectedIndex) return;
        entries.remove(selectedIndex);
        entries.add(target, entry);
        selectedIndex = target;
        refreshButtons();
        send(ConfigureDjPlaylistPayload.Action.MOVE, entry.entryId(), "", 0, target, autoAdvance);
    }

    private void play() {
        DjPlaylistEntry entry = selectedEntry();
        if (entry == null) return;
        applyCue();
        send(ConfigureDjPlaylistPayload.Action.PLAY_ENTRY, entry.entryId(), "", 0, -1, autoAdvance);
    }

    private void toggleAuto() {
        autoAdvance = !autoAdvance;
        autoButton.setMessage(autoLabel());
        send(ConfigureDjPlaylistPayload.Action.SET_AUTO_ADVANCE, null, "", 0, -1, autoAdvance);
    }

    private void toggleRepeat() {
        repeatMode = (repeatMode + 1) % 3;
        repeatButton.setMessage(repeatLabel());
        send(ConfigureDjPlaylistPayload.Action.SET_REPEAT, null, "", 0, -1, autoAdvance);
    }

    private void clear() {
        if (entries.isEmpty()) return;
        if (!clearArmed) {
            clearArmed = true;
            clearButton.setMessage(Component.translatable("screen.music_battle.dj_playlist.clear_confirm"));
            return;
        }
        clearArmed = false;
        send(ConfigureDjPlaylistPayload.Action.CLEAR, null, "", 0, -1, autoAdvance);
    }

    private void applyCue() {
        DjPlaylistEntry entry = selectedEntry();
        if (entry == null) return;
        TrackSummary track = track(entry.trackId());
        int maximum = track == null ? Integer.MAX_VALUE : Math.max(0, track.durationTicks() - 1);
        cueSourceTicks = Mth.clamp(cueSourceTicks, 0, maximum);
        if (cueSlider != null) cueSlider.setCue(cueSourceTicks);
        if (secondsBox != null) {
            updatingCueText = true;
            secondsBox.setValue(String.format(Locale.ROOT, "%.2f", cueSourceTicks / 20.0F));
            updatingCueText = false;
        }
        send(ConfigureDjPlaylistPayload.Action.SET_CUE, entry.entryId(), "",
                cueSourceTicks, -1, autoAdvance);
    }

    private void acceptSecondsText(String value) {
        if (updatingCueText) return;
        try {
            double seconds = Double.parseDouble(value.replace(',', '.'));
            if (!Double.isFinite(seconds) || seconds < 0.0D) return;
            TrackSummary track = selectedEntry() == null ? null : track(selectedEntry().trackId());
            int maximum = track == null ? Integer.MAX_VALUE : Math.max(0, track.durationTicks() - 1);
            cueSourceTicks = Mth.clamp((int) Math.min(Integer.MAX_VALUE, Math.round(seconds * 20.0D)), 0, maximum);
            if (cueSlider != null) cueSlider.setCue(cueSourceTicks);
        } catch (RuntimeException ignored) {
        }
    }

    private void loadCueWidgets() {
        DjPlaylistEntry entry = selectedEntry();
        cueSourceTicks = entry == null ? 0 : entry.startSourceTicks();
        if (cueSlider != null) cueSlider.setCue(cueSourceTicks);
        if (secondsBox != null) {
            updatingCueText = true;
            secondsBox.setValue(String.format(Locale.ROOT, "%.2f", cueSourceTicks / 20.0F));
            updatingCueText = false;
        }
    }

    private void refreshButtons() {
        boolean selected = selectedEntry() != null;
        for (int i=0;i<fxModeButtons.size();i++) {
            Button button=fxModeButtons.get(i); button.active=selected;
            boolean on=selected && selectedEntry().fxMode().ordinal()==i+1;
            button.setMessage(Component.literal(on ? "✓ " : "").append(Component.translatable(
                    "screen.music_battle.dj_playlist.fx_"+new String[]{"track","dj","none"}[i])));
        }
        if (addButton != null) addButton.active = !tracks.isEmpty() && entries.size() < DjPlaylistEntry.MAX_ENTRIES;
        if (workshopButton != null) workshopButton.active = entries.size() < DjPlaylistEntry.MAX_ENTRIES;
        if (removeButton != null) removeButton.active = selected;
        if (upButton != null) upButton.active = selected && selectedIndex > 0;
        if (downButton != null) downButton.active = selected && selectedIndex + 1 < entries.size();
        if (playButton != null) playButton.active = selected;
        if (applyCueButton != null) applyCueButton.active = selected;
        if (cueSlider != null) cueSlider.active = selected;
        if (secondsBox != null) secondsBox.setEditable(selected);
        if (clearButton != null) {
            clearButton.active = !entries.isEmpty();
            if (!clearArmed) clearButton.setMessage(Component.translatable("screen.music_battle.dj_playlist.clear"));
        }
    }

    private DjPlaylistEntry selectedEntry() {
        return selectedIndex >= 0 && selectedIndex < entries.size() ? entries.get(selectedIndex) : null;
    }

    private int indexOf(UUID entryId) {
        for (int index = 0; index < entries.size(); index++) {
            if (entries.get(index).entryId().equals(entryId)) return index;
        }
        return -1;
    }

    private TrackSummary track(String trackId) {
        return tracks.stream().filter(track -> track.id().equals(trackId)).findFirst().orElse(null);
    }

    private Component trackLabel() {
        if (tracks.isEmpty()) return Component.translatable("screen.music_battle.dj_playlist.no_tracks");
        TrackSummary track = tracks.get(Mth.clamp(trackIndex, 0, tracks.size() - 1));
        return Component.literal(track.title());
    }

    private Component autoLabel() {
        return Component.translatable(autoAdvance
                ? "screen.music_battle.dj_playlist.auto_on" : "screen.music_battle.dj_playlist.auto_off");
    }

    private Component repeatLabel() {
        return Component.translatable("screen.music_battle.dj_playlist.repeat_"
                + new String[]{"off", "one", "all"}[Mth.clamp(repeatMode, 0, 2)]);
    }

    private String fit(String text, int maximumWidth) {
        if (font.width(text) <= maximumWidth) return text;
        return font.plainSubstrByWidth(text, Math.max(8, maximumWidth - font.width("…"))) + "…";
    }

    /**
     * Widths for one row of buttons that fill {@code room}: each first gets its longest label in the buttons' own
     * typeface, so a toggle does not grow past its neighbours when it changes word, and what is left is shared
     * evenly. Only a window narrower than every label together squeezes them, in proportion.
     */
    private static int[] shareRow(int room, int gap, String[][] labels) {
        int[] widths = new int[labels.length];
        int free = room - gap * (labels.length - 1);
        int needed = 0;
        for (int i = 0; i < labels.length; i++) {
            float widest = 0F;
            for (String key : labels[i]) {
                widest = Math.max(widest, ru.gora.musicbattle.client.ui.TextStyles.measurer().width(
                        Component.translatable(key).getString(), ru.gora.musicbattle.client.ui.TextStyle.BODY));
            }
            // FancyButton leaves its label the button less ten; two more keep a rounding step from cutting it.
            widths[i] = (int) Math.ceil(widest) + 12;
            needed += widths[i];
        }
        int used = 0;
        for (int i = 0; i < widths.length; i++) {
            widths[i] = needed > free ? widths[i] * free / needed : widths[i] + (free - needed) / widths.length;
            used += widths[i];
        }
        widths[widths.length - 1] += free - used;
        return widths;
    }

    private void send(ConfigureDjPlaylistPayload.Action action, UUID entryId, String trackId,
                      int startSourceTicks, int targetIndex, boolean autoAdvance) {
        PacketDistributor.sendToServer(new ConfigureDjPlaylistPayload(parent.playlistPanelPos(), action,
                entryId, trackId, startSourceTicks, targetIndex, autoAdvance, repeatMode));
    }

    @Override
    public void onClose() {
        if (minecraft != null) minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public DjPanelScreen owningDjPanel() {
        return parent;
    }

    private int panelWidth() { return Math.min(660, Math.max(300, width - 24)); }
    private int panelHeight() { return Math.min(430, Math.max(230, height - 24)); }
    private int panelLeft() { return (width - panelWidth()) / 2; }
    private int panelTop() { return (height - panelHeight()) / 2; }
    private int listTop() { return panelTop() + 64; }
    private int listBottom() { return panelTop() + panelHeight() - 112; }
    private int visibleRows() { return Math.max(1, (listBottom() - listTop()) / ROW_HEIGHT); }

    private static String formatTicks(int ticks) {
        int seconds = Math.max(0, ticks) / 20;
        return String.format(Locale.ROOT, "%02d:%02d.%02d", seconds / 60, seconds % 60,
                Math.round((Math.max(0, ticks) % 20) * 5.0F));
    }

    private final class CueSlider extends AbstractSliderButton {
        @Override public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            // Drawn by the toolkit, like every other slider in the mod; what the value means stays this screen's.
            ru.gora.musicbattle.client.ui.Slider.paint(graphics,
                    ru.gora.musicbattle.client.workshop.layout.Rect.of(getX(), getY(), getWidth(), getHeight()),
                    value, getMessage(), isHoveredOrFocused(), active);
        }

        private int maximum = 1;

        private CueSlider(int x, int y, int width, int height) {
            super(x, y, width, height, Component.empty(), 0.0D);
            updateMessage();
        }

        private void setCue(int cue) {
            DjPlaylistEntry entry = selectedEntry();
            TrackSummary track = entry == null ? null : track(entry.trackId());
            maximum = Math.max(1, track == null ? cue + 1 : track.durationTicks() - 1);
            value = Mth.clamp(cue / (double) maximum, 0.0D, 1.0D);
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.translatable("screen.music_battle.dj_playlist.cue", formatTicks(cueSourceTicks)));
        }

        @Override
        protected void applyValue() {
            cueSourceTicks = Mth.clamp((int) Math.round(value * maximum), 0, maximum);
            updateMessage();
            if (secondsBox != null) {
                updatingCueText = true;
                secondsBox.setValue(String.format(Locale.ROOT, "%.2f", cueSourceTicks / 20.0F));
                updatingCueText = false;
            }
        }
    }
    /**
     * The cue in seconds, drawn and edited by the toolkit's {@link ru.gora.musicbattle.client.ui.TextField} so the
     * number is the buttons' typeface, centred in a box as tall as the slider beside it. The vanilla field this
     * replaces drew "0.00" in the bitmap font against the top of its box. It is a widget so focus, Tab and clicks
     * reach it like any other control on the screen.
     */
    private final class CueField extends AbstractWidget {
        private final ru.gora.musicbattle.client.ui.TextField field =
                ru.gora.musicbattle.client.ui.TextField.singleLine();

        private CueField(int x, int y, int width, int height) {
            super(x, y, width, height, Component.translatable("screen.music_battle.dj_playlist.seconds"));
            field.setMaxLength(12);
            field.setPlaceholder(Component.translatable("screen.music_battle.dj_playlist.seconds_hint").getString());
            field.setBounds(x, y, width, height);
            field.setOnChange(() -> acceptSecondsText(field.value()));
            field.setOnSubmit(DjPlaylistScreen.this::applyCue);
        }

        void setValue(String value) {
            field.setValue(value);
        }

        void setEditable(boolean editable) {
            active = editable;
            field.setEditable(editable);
            if (!editable) setFocused(false);
        }

        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            field.setBounds(getX(), getY(), getWidth(), getHeight());
            field.render(graphics, mouseX, mouseY);
            if (!isFocused() && isHovered() && active) {
                ru.gora.musicbattle.client.ui.RoundedRect.stroke(graphics, getX(), getY(), getWidth(), getHeight(),
                        Palette.RADIUS_SM, 1.0F, Palette.fade(Palette.ACCENT, 0.45F));
            }
        }

        @Override
        public void setFocused(boolean focused) {
            super.setFocused(focused);
            field.setFocused(focused);
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (!active || !visible || !isMouseOver(mouseX, mouseY)) return false;
            field.setFocused(true);
            field.mouseClicked(mouseX, mouseY, button);
            return button == 0;
        }

        @Override
        public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
            return field.mouseDragged(mouseX, mouseY, button);
        }

        @Override
        public boolean mouseReleased(double mouseX, double mouseY, int button) {
            return false;
        }

        @Override
        public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
            return isFocused() && field.keyPressed(keyCode, scanCode, modifiers);
        }

        @Override
        public boolean charTyped(char character, int modifiers) {
            return isFocused() && field.charTyped(character, modifiers);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            output.add(NarratedElementType.TITLE, Component.translatable("narration.edit_box", getMessage()));
        }
    }
}

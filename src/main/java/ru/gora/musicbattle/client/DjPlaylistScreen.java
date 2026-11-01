package ru.gora.musicbattle.client;

import java.util.ArrayList;
import ru.gora.musicbattle.client.ui.Palette;
import ru.gora.musicbattle.client.ui.RoundedRect;
import ru.gora.musicbattle.client.ui.TextStyle;
import ru.gora.musicbattle.client.ui.TextStyles;
import ru.gora.musicbattle.client.ui.UiIcon;
import ru.gora.musicbattle.client.workshop.WorkshopDraw;
import ru.gora.musicbattle.client.workshop.layout.Metrics;
import ru.gora.musicbattle.client.workshop.layout.Rect;
import ru.gora.musicbattle.client.workshop.layout.TextFit;
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

    private final DjPanelScreen parent;
    private final List<DjPlaylistEntry> entries = new ArrayList<>();
    /** Every map the panel knows, for the titles and lengths of the rows. */
    private final List<TrackSummary> tracks = new ArrayList<>();
    /**
     * The booth's own list, which is where the playlist's Add takes maps from. Maps reach that list through "+" on the
     * panel; this screen never puts one there, and "+" never puts one here.
     */
    private final List<TrackSummary> library = new ArrayList<>();
    private int selectedIndex = -1;
    private int trackIndex;
    private int scroll;
    private long seenRevision = Long.MIN_VALUE;
    /** The panel's {@code libraryRevision} when {@link #library} was last copied from it. */
    private int seenLibraryRevision = Integer.MIN_VALUE;
    private boolean autoAdvance;
    private int repeatMode;
    private int cueSourceTicks;
    private boolean updatingCueText;
    private boolean clearArmed;
    private boolean selectLastAfterSync;
    private int refreshCooldown;

    private Button trackButton;
    private Button addButton;
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
        // The map to queue, chosen from the booth's list, and the playlist's own Add. A Workshop button used to sit
        // here too; Workshop maps now join the list through "+" on the panel, and only Add puts one in the queue.
        int addWidth = Math.min(110, Math.max(70, contentWidth / 4));
        int trackWidth = contentWidth - addWidth - 5;
        trackButton = addRenderableWidget(FancyButton.create(trackLabel(), ignored -> openTrackPicker(),
                left + 12, top, trackWidth, buttonHeight));
        addButton = addRenderableWidget(FancyButton.create(
                Component.translatable("screen.music_battle.dj_playlist.add"), ignored -> addEntry(),
                left + 12 + trackWidth + 5, top, addWidth, buttonHeight)).primary();

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
        } else if (seenLibraryRevision != parent.libraryRevision()) {
            // The booth's list moves on its own clock: a "+" pick can land while this screen is open.
            syncLibraryFromParent();
        }
        if (--refreshCooldown <= 0) {
            send(ConfigureDjPlaylistPayload.Action.REFRESH, null, "", 0, -1, autoAdvance);
            refreshCooldown = 20;
        }
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    }

    /**
     * The toolkit's card, like the panel's library and the chooser this screen opens: a rounded panel with its title
     * beside the music icon, and the queue as rounded rows. It was a square box with a pink bar and the bitmap font,
     * the last of the DJ screens to look like that.
     */
    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, Palette.withAlpha(Palette.BG_0, 0.95F));
        int left = panelLeft();
        int top = panelTop();
        RoundedRect.fill(graphics, left, top, panelWidth(), panelHeight(), Palette.RADIUS_MD, Palette.BG_1);
        RoundedRect.stroke(graphics, left, top, panelWidth(), panelHeight(), Palette.RADIUS_MD, 1.0F,
                Palette.withAlpha(Palette.LINE, 0.6F));
        Rect icon = new Rect(left + 14, top + 11, Metrics.ICON, Metrics.ICON);
        WorkshopDraw.icon(graphics, icon, UiIcon.MUSIC, Palette.ACCENT);
        line(graphics, title.getString(), TextStyle.TITLE, icon.right() + Metrics.GAP_SM + 2,
                TextStyles.centeredCapY(TextStyle.TITLE, icon.y(), icon.h()), panelWidth() - 40, Palette.TEXT_1);
        drawEntries(graphics, mouseX, mouseY);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void drawEntries(GuiGraphics graphics, int mouseX, int mouseY) {
        int left = panelLeft() + 12;
        int right = panelLeft() + panelWidth() - 12;
        int top = listTop();
        int bottom = listBottom();
        RoundedRect.fill(graphics, left - 2, top - 2, right - left + 4, bottom - top + 4, Palette.RADIUS_SM,
                Palette.BG_0);
        int rows = visibleRows();
        scroll = Mth.clamp(scroll, 0, Math.max(0, entries.size() - rows));
        boolean scrolls = entries.size() > rows;
        // A scrolling queue keeps its rows clear of the scrollbar on the right.
        int rowRight = scrolls ? right - 6 : right;
        UUID activeId = parent.activePlaylistEntryId();
        for (int row = 0; row < rows; row++) {
            int index = scroll + row;
            if (index >= entries.size()) break;
            DjPlaylistEntry entry = entries.get(index);
            int y = top + row * ROW_HEIGHT;
            float rowHeight = ROW_HEIGHT - 2;
            boolean hovered = mouseX >= left && mouseX < rowRight && mouseY >= y && mouseY < y + rowHeight;
            boolean active = entry.entryId().equals(activeId);
            boolean selected = index == selectedIndex;
            int fill = selected ? Palette.mix(Palette.BG_3, Palette.ACCENT_DEEP, 0.85F)
                    : hovered ? Palette.BG_3 : Palette.BG_2;
            RoundedRect.fill(graphics, left, y, rowRight - left, rowHeight, Palette.RADIUS_SM, fill);
            if (selected) {
                RoundedRect.stroke(graphics, left, y, rowRight - left, rowHeight, Palette.RADIUS_SM, 1.0F,
                        Palette.ACCENT);
            }
            RoundedRect.pill(graphics, left + 1.5F, y + 4, 2.5F, rowHeight - 8, active ? Palette.OK
                    : selected ? Palette.ACCENT : hovered ? Palette.mix(Palette.LINE, Palette.ACCENT, 0.6F) : Palette.LINE);
            float textX = left + 10;
            if (active) {
                // The row on the deck carries the play mark in the colour of the rail beside it.
                WorkshopDraw.icon(graphics, new Rect(textX, y + (rowHeight - Metrics.ICON) / 2.0F, Metrics.ICON,
                        Metrics.ICON), UiIcon.PLAY, Palette.OK);
                textX += Metrics.ICON + 4;
            }
            String cue = formatTicks(entry.startSourceTicks());
            float cueWidth = TextStyles.measurer().width(cue, TextStyle.CAPTION);
            float cueY = TextStyles.centeredCapY(TextStyle.CAPTION, y, rowHeight);
            TextStyles.queueRight(graphics, TextStyles.text(cue, TextStyle.CAPTION).getVisualOrderText(),
                    rowRight - 8, cueY, Palette.TEXT_MUTE);
            TrackSummary track = track(entry.trackId());
            String name = (index + 1) + ".  " + (track == null ? entry.trackId() : track.title());
            line(graphics, name, TextStyle.BODY, textX, TextStyles.centeredCapY(TextStyle.BODY, y, rowHeight),
                    rowRight - 8 - cueWidth - 10 - textX, track == null ? Palette.TEXT_MUTE : Palette.TEXT_1);
        }
        if (scrolls) {
            WorkshopDraw.scrollbar(graphics, new Rect(left, top, right - left, bottom - top), scroll * ROW_HEIGHT,
                    entries.size() * ROW_HEIGHT, false);
        }
        if (entries.isEmpty()) {
            float centerX = (left + right) / 2.0F;
            // With nothing on the booth's list there is nothing Add could queue, so the empty queue says where maps
            // come from instead of telling the DJ to add some.
            String key = library.isEmpty() ? "screen.music_battle.dj_playlist.picker_empty"
                    : "screen.music_battle.dj_playlist.empty";
            List<String> lines = TextFit.wrap(TextStyles.measurer(), Component.translatable(key).getString(),
                    TextStyle.BODY, right - left - 24.0F, 2);
            float blockHeight = 20 + lines.size() * Metrics.lineHeight(TextStyle.BODY);
            float iconY = top + Math.max(4.0F, (bottom - top - blockHeight) / 2.0F);
            WorkshopDraw.icon(graphics, new Rect(centerX - 7, iconY, 14, 14), UiIcon.MUSIC,
                    Palette.withAlpha(Palette.TEXT_MUTE, 0.8F));
            float textY = iconY + 20;
            for (String messageLine : lines) {
                TextStyles.queueCentered(graphics, TextStyles.text(messageLine, TextStyle.BODY).getVisualOrderText(),
                        centerX, textY, Palette.TEXT_2);
                textY += Metrics.lineHeight(TextStyle.BODY);
            }
        }
    }

    private static void line(GuiGraphics graphics, String value, TextStyle style, float x, float y, float maxWidth,
                             int colour) {
        String fitted = TextFit.ellipsize(TextStyles.measurer(), value == null ? "" : value, style, maxWidth);
        if (!fitted.isEmpty()) {
            TextStyles.queue(graphics, TextStyles.text(fitted, style).getVisualOrderText(), x, y, colour);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
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
        String chosenId = chosenTrackId();
        library.clear();
        library.addAll(parent.libraryTracks());
        seenLibraryRevision = parent.libraryRevision();
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
        // On opening, the panel's own selection is the natural first choice.
        chooseLibraryTrack(initial ? parent.playlistSelectedTrackId() : chosenId);
        if (autoButton != null) autoButton.setMessage(autoLabel());
        if (repeatButton != null) repeatButton.setMessage(repeatLabel());
        loadCueWidgets();
        refreshButtons();
    }

    /**
     * Re-reads the booth's list alone. The queue is as it was, so the cue being typed for the selected row stays where
     * it is: a full sync reloads the cue widgets from the row, and a map landing mid-edit would have undone the typing.
     */
    private void syncLibraryFromParent() {
        String chosenId = chosenTrackId();
        // The rows' titles come from the same refresh, so a map the server just gained is named in the queue too.
        tracks.clear();
        tracks.addAll(parent.playlistTracks());
        library.clear();
        library.addAll(parent.libraryTracks());
        seenLibraryRevision = parent.libraryRevision();
        chooseLibraryTrack(chosenId);
        refreshButtons();
    }

    /** The map Add would queue now, or empty when the booth's list is. */
    private String chosenTrackId() {
        return trackIndex >= 0 && trackIndex < library.size() ? library.get(trackIndex).id() : "";
    }

    /**
     * Points Add at {@code trackId} when the booth's list holds it. Otherwise the choice keeps its place in the list,
     * so a map that left the list hands the choice to its neighbour rather than to the top of the list.
     */
    private void chooseLibraryTrack(String trackId) {
        for (int index = 0; index < library.size(); index++) {
            if (library.get(index).id().equals(trackId)) {
                trackIndex = index;
                break;
            }
        }
        trackIndex = library.isEmpty() ? 0 : Mth.clamp(trackIndex, 0, library.size() - 1);
        if (trackButton != null) trackButton.setMessage(trackLabel());
    }

    private void openTrackPicker() {
        if (minecraft == null) return;
        minecraft.setScreen(new DjPlaylistTrackPickerScreen(this, parent, chosenTrackId(), chosen -> {
            // The chooser lists the panel's current list, which may be newer than this screen's copy.
            if (seenLibraryRevision != parent.libraryRevision()) {
                syncLibraryFromParent();
            }
            chooseLibraryTrack(chosen.id());
        }));
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
        if (library.isEmpty() || entries.size() >= DjPlaylistEntry.MAX_ENTRIES) return;
        TrackSummary track = library.get(Mth.clamp(trackIndex, 0, library.size() - 1));
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
        // The chooser stays pressable with an empty list: its empty page says where maps come from.
        if (addButton != null) addButton.active = !library.isEmpty() && entries.size() < DjPlaylistEntry.MAX_ENTRIES;
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
        if (library.isEmpty()) return Component.translatable("screen.music_battle.dj_playlist.no_tracks");
        TrackSummary track = library.get(Mth.clamp(trackIndex, 0, library.size() - 1));
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

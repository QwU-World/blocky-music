package ru.gora.musicbattle.client;

import com.mojang.blaze3d.platform.NativeImage;
import ru.gora.musicbattle.client.ui.Palette;
import ru.gora.musicbattle.client.ui.RoundedImage;
import ru.gora.musicbattle.client.ui.RoundedRect;
import ru.gora.musicbattle.client.ui.TextMeasurer;
import ru.gora.musicbattle.client.ui.TextStyle;
import ru.gora.musicbattle.client.ui.TextStyles;
import ru.gora.musicbattle.client.ui.UiClip;
import ru.gora.musicbattle.client.ui.UiIcon;
import ru.gora.musicbattle.client.ui.UiSdf;
import ru.gora.musicbattle.client.ui.UiTooltip;
import ru.gora.musicbattle.client.workshop.WorkshopDraw;
import ru.gora.musicbattle.client.workshop.layout.Metrics;
import ru.gora.musicbattle.client.workshop.layout.Rect;
import ru.gora.musicbattle.client.workshop.layout.TextFit;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipPositioner;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import ru.gora.musicbattle.MusicBattleMod;
import ru.gora.musicbattle.data.DjVisualEffectConfig;
import ru.gora.musicbattle.data.DjVisualEffects;
import ru.gora.musicbattle.data.DjPlaylistEntry;
import ru.gora.musicbattle.data.DjPlaylistMixer;
import ru.gora.musicbattle.network.EditDjPlaylistMixerPayload;
import ru.gora.musicbattle.data.TrackSummary;
import ru.gora.musicbattle.data.TrackEffect;
import ru.gora.musicbattle.network.ConfigureDjPanelPayload;
import ru.gora.musicbattle.network.DjTrackPreviewRequestPayload;
import ru.gora.musicbattle.network.DjCameraPreviewPayload;
import ru.gora.musicbattle.network.DjCameraPreviewRequestPayload;
import ru.gora.musicbattle.network.OpenDjPanelPayload;

/** Full live-mixer interface for one persistent DJ installation. */
public final class DjPanelScreen extends Screen {
    /** How long a line about a "+" pick stays above the library. */
    private static final long WORKSHOP_STATUS_MS = 12_000L;
    private static final int CARD_HEIGHT = PanelLayout.CARD_HEIGHT;
    private static final int CARD_GAP = PanelLayout.CARD_GAP;
    private static final float REMOVE_SIZE = PanelLayout.REMOVE_SIZE;
    /**
     * The page a narrow panel shows, kept between openings: a DJ who left the panel on the mixer finds it there again.
     * A panel wide enough for both columns ignores it.
     */
    private static PanelLayout.Page page = PanelLayout.Page.LIBRARY;

    /**
     * The latest word about a "+" pick. It is kept here rather than on one screen because the answer can arrive long
     * after the pick, once an upload has landed, and the panel should still say it when the DJ comes back to it.
     */
    private static Component workshopStatus;
    private static boolean workshopStatusProblem;
    private static long workshopStatusUntilMs;
    /** The pick on its way to the server or last refused, and the panel it was picked at. */
    private static ru.gora.musicbattle.client.workshop.layout.BattleSetup.Pick workshopPick;
    private static BlockPos workshopPickPanel;
    /**
     * Whether the line is a refusal of {@link #workshopPick}. Such a line stays until the DJ retries or dismisses it: a
     * refusal that faded on its own left the DJ unsure whether the map was still coming.
     */
    private static boolean workshopRetryable;

    private final BlockPos panelPos;
    /** Every map the server described: lookups for the deck, the playlist's rows and the "+" picker's Server tab. */
    private final List<TrackSummary> tracks = new ArrayList<>();
    /** The booth's own beatmap list, in its order: what the library shows. An id may name a map the server lacks. */
    private final List<String> library = new ArrayList<>();
    /** Positions in {@link #library} whose title matches the search: the cards drawn. */
    private final List<Integer> filteredTrackIndices = new ArrayList<>();
    private final List<OpenDjPanelPayload.SpeakerEntry> speakers = new ArrayList<>();
    private final List<OpenDjPanelPayload.CameraEntry> cameras = new ArrayList<>();
    private final List<DjPlaylistEntry> playlist = new ArrayList<>();
    private final Map<String, ResourceLocation> previewTextures = new HashMap<>();
    /** Source-audio cue points for tracks selected while another track is playing. */
    private final Map<String, Integer> nextTrackCueSourceTicks = new HashMap<>();
    private final Set<String> requestedPreviewIds = new HashSet<>();

    private int trackIndex;
    private int speakerIndex;
    private int cameraIndex = -1;
    private int trackScroll;
    private String searchQuery = "";
    private boolean applyToAll;
    private float radius = 14.0F;
    private float volume = 1.0F;
    private float lowGainDb;
    private float midGainDb;
    private float highGainDb;

    private boolean playing;
    private boolean paused;
    private String loadedTrackId = "";
    private int playbackPositionTicks;
    private int playbackDurationTicks;
    private float playbackSpeed = 1.0F;
    private long playbackStateNanos;
    /** Authoritative visual session, separate from optimistic controls / the selected next track. */
    private List<TrackEffect> monitorVisualEffects = List.of();
    private UUID monitorPlaybackId;
    private String monitorTrackId = "";
    private int monitorVisualBpm = 120;
    /** Beat 1 of the monitored song on the monitor's clock, in ms at its speed; its beat visuals count from there. */
    private int monitorVisualOffsetMs;
    private float monitorVisualSpeed = 1.0F;
    private int monitorVisualPositionTicks;
    private long monitorVisualStateNanos;
    private boolean monitorVisualPlaying;
    private boolean monitorReleasing;
    private final ClientDjFxTransition monitorTransitions = new ClientDjFxTransition();
    private Set<Integer> monitorPlayerEntityIds = Set.of();
    private Set<Integer> monitorMobEntityIds = Set.of();

    private boolean dspEnabled;
    private boolean reverbEnabled;
    private float reverbWet;
    private float reverbRoom;
    private float reverbDamping;
    private boolean echoEnabled;
    private float echoWet;
    private float echoTimeMs = 250.0F;
    private float echoFeedback;
    private boolean lowPassEnabled;
    private float lowPassCutoffHz = 20_000.0F;
    private boolean trackVisualFxEnabled = true;
    private boolean djVisualFxEnabled;
    private List<DjVisualEffectConfig> djVisualEffects = List.of();
    private UUID activePlaylistEntryId;
    private boolean playlistAutoAdvance = true;
    private int playlistRepeat;
    private long playlistRevision;

    private Button speakerButton;
    private Button speakerModeButton;
    private Button unlinkButton;
    private Button playButton;
    private Button pauseButton;
    private Button restartButton;
    private Button speedButton;
    private Button dspButton;
    private Button reverbButton;
    private Button echoButton;
    private Button lowPassButton;
    private Button cameraButton;
    private Button cameraPreviousButton;
    private Button cameraNextButton;
    private Button unlinkCameraButton;
    private Button visualFxButton;
    private Button equalizerPresetsButton;
    private Button audioPresetsButton;
    private Button resetSpeakersButton;
    /** Repeat lived only inside the playlist; it is a transport control and belongs next to play and speed. */
    private Button repeatButton;
    private Button playlistButton;
    private Button preloadAudienceButton;
    private SettingSlider radiusSlider;
    private SettingSlider volumeSlider;
    private SettingSlider lowSlider;
    private SettingSlider midSlider;
    private SettingSlider highSlider;
    private SettingSlider reverbWetSlider;
    private SettingSlider reverbRoomSlider;
    private SettingSlider reverbDampingSlider;
    private SettingSlider echoWetSlider;
    private SettingSlider echoTimeSlider;
    private SettingSlider echoFeedbackSlider;
    private SettingSlider lowPassSlider;
    private SettingSlider timelineSlider;
    private EditBox searchBox;
    /** The tab of the page a narrow panel shows, or null while both columns show. */
    private PageTab selectedTab;
    /** Play or Pause in the header of a narrow panel's library page, or null where that page does not show. */
    private PanelButton libraryPlayButton;
    /** Where everything sits for the current window and language; set in init, read by drawing and the mouse. */
    private PanelLayout layout;
    /**
     * The mixer's widgets with the y the layout gave them, in the order they were made. They are not Screen renderables:
     * {@link #renderMixer} draws them inside the mixer's view, moved up by {@link #mixerScroll}, so a short window keeps
     * the camera, the library and the mixer on one screen and scrolls the mixer instead of splitting the panel in two.
     */
    private final Map<net.minecraft.client.gui.components.AbstractWidget, Integer> mixerWidgets = new java.util.LinkedHashMap<>();
    /** How far the mixer's column is scrolled, from 0 to the layout's mixerScrollMax; kept across a resize. */
    private int mixerScroll;
    /**
     * The panel's tooltips, drawn as the toolkit's card (UiTooltip) in place of vanilla's purple frame and bitmap font. A
     * new holder comes with every build of the widgets in {@link #init}, so it does not keep the tips of widgets a page
     * switch or a resize threw away.
     */
    private UiTooltip.Deferred tooltips = new UiTooltip.Deferred();
    /**
     * Play's and Pause's tips for the library page's one button, and the speaker button's tip while no speaker is
     * connected. They are made once a build and swapped as the state changes: the state is refreshed every second, and a
     * new tip each time would pile up in the holder for as long as the panel stays open.
     */
    private Tooltip playTip;
    private Tooltip pauseTip;
    private Tooltip noSpeakersTip;
    /** What the library says under the pointer this frame (a card's "×", its author, a cut status line), or null. */
    private Component pointerHint;
    /**
     * What that hint is about, which its card keeps off: the card under the pointer (cut to the library's view), or the
     * cut status line; {@link #pointerHintInner} is the "×" when the pointer is on it, kept clear where the whole card
     * cannot be.
     */
    private Rect pointerHintOver;
    private Rect pointerHintInner;

    private boolean speakerUpdatePending;
    private int speakerUpdateCooldown;
    private boolean panelFxUpdatePending;
    private int panelFxUpdateCooldown;
    private int pendingSeekTicks = -1;
    private String pendingSeekTrackId = "";
    private int seekDebounceTicks;
    private long speedRequestSequence;
    private long pendingSpeedRequestRevision;
    private int previewRequestCooldown;
    private int cameraPreviewRequestCooldown;
    private int serverStateRefreshCooldown = 20;
    private int preserveLocalSpeakerTicks;
    private int preserveLocalPanelFxTicks;
    /**
     * Ticks during which a panel refresh may not overwrite the visual effects this screen just applied.
     *
     * The panel asks the server for a fresh snapshot once a second, and the answer to a request made *before* an
     * apply arrives *after* it. Without this the mirror silently went back to the old list, the editor was then
     * seeded from that stale list, and the next apply wrote the old effects back to the server - which is what
     * "sometimes it does not save" was. The DSP block above has had the same guard for the same reason.
     */
    private int preserveLocalVisualFxTicks;
    private DjCameraPreviewRenderer.Snapshot cameraSnapshot;
    private DjCameraPreviewRenderer.Status cameraPreviewStatus = DjCameraPreviewRenderer.Status.CAMERA_UNAVAILABLE;
    private long acceptedCameraServerTime = Long.MIN_VALUE;
    /** Where the refusal line's Retry and Dismiss were drawn last frame; {@code linksTop} is NaN when they were not. */
    private float retryLeft;
    private float retryRight;
    private float dismissLeft;
    private float dismissRight;
    private float linksTop = Float.NaN;
    /**
     * A map just put on the list with "+", to scroll into view once the server's list carries it. A new card lands at
     * the end of the list, which on a long list is below the fold, and the DJ was left wondering whether it arrived.
     */
    private String revealTrackId = "";
    /** When the reveal was asked for: one the server's lists never confirm is let go after a few seconds. */
    private long revealRequestedMs;
    /** The title to announce as added once the server's list confirms the map, or null when nothing is owed. */
    private String revealAnnounce;
    /** The card shown by a reveal, outlined for a moment so the eye finds it. */
    private String revealedTrackId = "";
    private long revealedAtMs;
    /**
     * Moves whenever the maps the booth's list resolves to change. The playlist reads the list from this screen and
     * watches this number, because the list changes without the playlist's own revision moving: a "+" pick that lands
     * while the playlist is open, or a map the server gains or loses. Without it the playlist kept its old list, and
     * its chooser, which reads the fresh one, could pick a map that Add then could not find.
     */
    private int libraryRevision;

    public DjPanelScreen(OpenDjPanelPayload payload) {
        super(Component.translatable("screen.music_battle.dj_panel.title"));
        this.panelPos = payload.panelPos();
        applyServerState(payload, true);
    }

    /**
     * The server's answer to a "+" pick. It can come long after the pick, once an upload has landed, when the DJ may
     * be anywhere; so an outcome is also a toast, and the panel's library line says it when the panel is open.
     */
    public static void acceptWorkshopAddResult(ru.gora.musicbattle.network.DjWorkshopAddResultPayload payload) {
        Component title = Component.translatable("screen.music_battle.dj_panel.title");
        switch (payload.verdict()) {
            case ru.gora.musicbattle.network.DjWorkshopAddResultPayload.UPLOAD ->
                    ru.gora.musicbattle.client.workshop.WorkshopScreen.djUploadApproved(payload.setId());
            case ru.gora.musicbattle.network.DjWorkshopAddResultPayload.SEND_INSTALLED -> {
                // The server asks for this client's copy next, as for a battle vote, and the transfer only says
                // "need": the set it means has to be named here first. A set that is not installed fails its
                // repack, which cancels the transfer, and the server answers that on the library line.
                var installed = ru.gora.musicbattle.client.workshop.WorkshopClient.get().catalog()
                        .bySetId(payload.setId()).orElse(null);
                ru.gora.musicbattle.client.workshop.ServerWorkshopClient.rememberVoteUpload(payload.setId(),
                        installed == null ? 0 : installed.revision(),
                        installed == null ? "" : installed.contentSha256());
                workshopSending(payload.setId());
            }
            case ru.gora.musicbattle.network.DjWorkshopAddResultPayload.ADDED -> {
                ru.gora.musicbattle.client.workshop.WorkshopScreen.djAnswered(payload.setId());
                ru.gora.musicbattle.client.workshop.layout.BattleSetup.Pick pick = workshopPick;
                DjPanelScreen open = openPanelFor(payload.panelPos());
                if (open != null && pick != null && pick.setId() == payload.setId() && !pick.chartKey().isEmpty()) {
                    open.reveal(ru.gora.musicbattle.workshop.WorkshopLayout.chartId(pick.setId(), pick.chartKey()));
                }
                workshopStatus(payload.message(), false, payload.panelPos());
                ru.gora.musicbattle.client.workshop.WorkshopToasts.show(title, payload.message());
            }
            default -> {
                ru.gora.musicbattle.client.workshop.WorkshopScreen.djAnswered(payload.setId());
                workshopRefused(payload.message(), payload.setId());
                ru.gora.musicbattle.client.workshop.WorkshopToasts.problem(title, payload.message());
            }
        }
    }

    /**
     * Shows a line about a "+" pick above the library. {@code addedTo} names the panel whose list just gained the
     * map, so an open panel for it asks for the fresh list now instead of on its next one-second refresh.
     */
    static void workshopStatus(Component message, boolean problem, BlockPos addedTo) {
        workshopStatus = message;
        workshopStatusProblem = problem;
        workshopStatusUntilMs = System.currentTimeMillis() + WORKSHOP_STATUS_MS;
        // A failed download of the current pick can be retried like a refusal; news of success ends the pick.
        workshopRetryable = problem && workshopPick != null;
        if (!problem && addedTo != null) {
            workshopPick = null;
        }
        DjPanelScreen open = addedTo == null ? null : openPanelFor(addedTo);
        if (open != null) {
            open.serverStateRefreshCooldown = 0;
        }
    }

    /** The open panel for this block, whether it is on top, under the Workshop picker or under one of its editors. */
    private static DjPanelScreen openPanelFor(BlockPos pos) {
        DjPanelScreen panel = ScreenStack.find(DjPanelScreen.class);
        if (panel == null && Minecraft.getInstance().screen instanceof DjPanelOwnedScreen nested) {
            panel = nested.owningDjPanel();
        }
        return panel != null && panel.panelPos.equals(pos) ? panel : null;
    }

    /** The server will ask for the DJ's installed copy of the pick: the line says it is on its way. */
    static void workshopSending(long setId) {
        ru.gora.musicbattle.client.workshop.layout.BattleSetup.Pick pick = workshopPick;
        if (pick == null || pick.setId() != setId) {
            return;
        }
        workshopStatus(Component.translatable("screen.music_battle.dj_playlist.workshop.sending", pick.title()),
                false, null);
    }

    /**
     * The server refused a "+" pick, possibly long after it said the map could come. A refusal of the current pick
     * offers Retry and Dismiss; one about another set is only shown.
     */
    static void workshopRefused(Component message, long setId) {
        if (workshopPick != null && workshopPick.setId() != setId) {
            workshopPick = null;
        }
        workshopStatus(message, true, null);
    }

    public boolean isForPanel(BlockPos pos) {
        return panelPos.equals(pos);
    }

    BlockPos playlistPanelPos() {
        return panelPos;
    }

    List<TrackSummary> playlistTracks() {
        return List.copyOf(tracks);
    }

    /** See {@link #libraryRevision}: a screen holding a copy of {@link #libraryTracks()} re-reads it when this moves. */
    int libraryRevision() {
        return libraryRevision;
    }

    /** The maps on the booth's own list that the server can play, in the list's order: what the playlist adds from. */
    List<TrackSummary> libraryTracks() {
        List<TrackSummary> resolved = new ArrayList<>();
        for (String trackId : library) {
            int index = findTrack(trackId);
            if (index >= 0) {
                resolved.add(tracks.get(index));
            }
        }
        return resolved;
    }

    String playlistSelectedTrackId() {
        return selectedTrackId();
    }

    List<DjPlaylistEntry> playlistEntries() {
        return List.copyOf(playlist);
    }

    UUID activePlaylistEntryId() {
        return activePlaylistEntryId;
    }

    DjPlaylistEntry activePlaylistEntry() {
        return playlist.stream().filter(entry -> entry.entryId().equals(activePlaylistEntryId)).findFirst().orElse(null);
    }

    boolean playlistAutoAdvance() {
        return playlistAutoAdvance;
    }

    int playlistRepeat() {
        return playlistRepeat;
    }

    long playlistRevision() {
        return playlistRevision;
    }

    float playlistPlaybackSpeed() {
        return playbackSpeed;
    }

    /** Refreshes the open mixer without replacing the Screen and losing drag state. */
    public void applyServerState(OpenDjPanelPayload payload) {
        applyServerState(payload, false);
    }

    private void applyServerState(OpenDjPanelPayload payload, boolean initial) {
        serverStateRefreshCooldown = 20;
        if (!panelPos.equals(payload.panelPos())) {
            return;
        }
        String selectedId = payload.selectedTrackId() == null ? "" : payload.selectedTrackId();
        BlockPos previousCameraPos = selectedCameraPos();
        List<TrackSummary> libraryBefore = libraryTracks();
        tracks.clear();
        tracks.addAll(payload.tracks());
        library.clear();
        library.addAll(payload.library());
        if (!libraryBefore.equals(libraryTracks())) {
            libraryRevision++;
        }
        rebuildTrackFilter();
        speakers.clear();
        speakers.addAll(payload.speakers());
        cameras.clear();
        cameras.addAll(payload.cameras());
        int serverCameraIndex = payload.activeCameraIndex();
        cameraIndex = serverCameraIndex >= 0 && serverCameraIndex < cameras.size()
                ? serverCameraIndex : cameras.isEmpty() ? -1 : 0;
        OpenDjPanelPayload.CameraEntry selectedCamera = selectedCamera();
        if (selectedCamera == null) {
            cameraSnapshot = null;
            acceptedCameraServerTime = Long.MIN_VALUE;
            cameraPreviewStatus = DjCameraPreviewRenderer.Status.CAMERA_UNAVAILABLE;
        } else if (initial || !selectedCamera.pos().equals(previousCameraPos) || cameraSnapshot == null) {
            cameraSnapshot = cameraSnapshot(selectedCamera, -1L);
            acceptedCameraServerTime = Long.MIN_VALUE;
            cameraPreviewRequestCooldown = 0;
        }
        if (payload.playing() && monitorVisualPlaying
                && !java.util.Objects.equals(monitorPlaybackId, payload.playbackId())) {
            float oldTick = monitorVisualPositionTicks + Math.max(0L, System.nanoTime()-monitorVisualStateNanos)/50_000_000f;
            int oldDuration = tracks.stream().filter(t->t.id().equals(monitorTrackId)).mapToInt(TrackSummary::durationTicks).findFirst().orElse(0);
            if (oldDuration > 0 && oldTick >= oldDuration/monitorVisualSpeed) {
                monitorTransitions.capture(monitorVisualEffects,oldTick,monitorVisualBpm,monitorVisualSpeed,
                        monitorBeatOrigin(),System.nanoTime());
            }
        }
        playing = payload.playing();
        paused = payload.paused();
        loadedTrackId = payload.activeTrackId() == null ? "" : payload.activeTrackId();
        boolean release = payload.paused() && payload.trackDurationTicks() > 0
                && payload.activeVisualPositionTicks() >= payload.trackDurationTicks();
        boolean continuingRelease = release && monitorReleasing && loadedTrackId.equals(monitorTrackId);
        monitorVisualEffects = release
                ? ru.gora.musicbattle.data.DjFxRelease.at(payload.activeVisualEffects(), payload.trackDurationTicks(), payload.activeVisualSpeed())
                : List.copyOf(payload.activeVisualEffects());
        monitorPlaybackId = payload.playbackId();
        monitorTrackId = loadedTrackId;
        monitorVisualBpm = Math.max(20, payload.activeVisualBpm());
        monitorVisualOffsetMs = payload.activeVisualOffsetMs();
        monitorVisualSpeed = normalizeSpeed(payload.activeVisualSpeed());
        monitorVisualPositionTicks = Math.max(0, payload.activeVisualPositionTicks());
        if (!continuingRelease) monitorVisualStateNanos = System.nanoTime();
        monitorReleasing = release;
        monitorVisualPlaying = payload.playing() || release;
        monitorPlayerEntityIds = Set.copyOf(payload.activePlayerEntityIds());
        monitorMobEntityIds = Set.copyOf(payload.activeMobEntityIds());
        float responseSpeed = normalizeSpeed(payload.playbackSpeed());
        boolean acceptSpeedResponse = initial || pendingSpeedRequestRevision == 0L
                || payload.speedResponseRevision() == pendingSpeedRequestRevision;
        if (acceptSpeedResponse) {
            playbackSpeed = responseSpeed;
            playbackPositionTicks = Math.max(0, payload.playbackPositionTicks());
            playbackDurationTicks = Math.max(0, payload.trackDurationTicks());
            if (payload.speedResponseRevision() == pendingSpeedRequestRevision) {
                pendingSpeedRequestRevision = 0L;
            }
        } else {
            // Server-to-client and client-to-server packets can cross in flight:
            // keep the newest optimistic speed when this full-state response was
            // produced for an older click, while still advancing its source-time.
            int sourcePosition = Math.max(0, Math.round(payload.playbackPositionTicks() * responseSpeed));
            playbackPositionTicks = Math.max(0, Math.round(sourcePosition / playbackSpeed));
            int sourceDuration = loadedTrackSourceDuration();
            playbackDurationTicks = sourceDuration > 0
                    ? scaledDuration(sourceDuration, playbackSpeed)
                    : Math.max(0, Math.round(payload.trackDurationTicks() * responseSpeed / playbackSpeed));
        }
        playbackStateNanos = System.nanoTime();
        if (initial || preserveLocalPanelFxTicks <= 0) {
            dspEnabled = payload.dspEnabled();
            reverbEnabled = payload.reverbEnabled();
            reverbWet = payload.reverbWet();
            reverbRoom = payload.reverbRoom();
            reverbDamping = payload.reverbDamping();
            echoEnabled = payload.echoEnabled();
            echoWet = payload.echoWet();
            echoTimeMs = payload.echoTimeMs();
            echoFeedback = payload.echoFeedback();
            lowPassEnabled = payload.lowPassEnabled();
            lowPassCutoffHz = payload.lowPassCutoffHz();
        }
        if (initial || preserveLocalVisualFxTicks <= 0) {
            trackVisualFxEnabled = payload.trackVisualFxEnabled();
            djVisualFxEnabled = payload.djVisualFxEnabled();
            djVisualEffects = DjVisualEffects.sanitizeConfigs(payload.djVisualEffects());
        }
        playlist.clear();
        playlist.addAll(payload.playlist());
        activePlaylistEntryId = payload.activePlaylistEntryId();
        playlistAutoAdvance = payload.playlistAutoAdvance();
        playlistRepeat = payload.playlistRepeat();
        if (repeatButton != null) {
            repeatButton.setMessage(repeatLabel());
        }
        playlistRevision = payload.playlistRevision();

        // Nothing selected is a real state now that the library holds only the DJ's own maps: an empty booth has
        // nothing to play, and guessing the first map on the server would aim Play at a song the panel does not show.
        trackIndex = findTrack(selectedId.isBlank() ? loadedTrackId : selectedId);
        applyToAll = payload.editAllSpeakers();
        speakerIndex = payload.activeSpeakerIndex() >= 0 && payload.activeSpeakerIndex() < speakers.size()
                ? payload.activeSpeakerIndex() : Mth.clamp(speakerIndex, 0, Math.max(0, speakers.size() - 1));
        if (initial || preserveLocalSpeakerTicks <= 0) {
            loadSpeakerSettings();
        } else if (!speakers.isEmpty()) {
            if (applyToAll) {
                for (int index = 0; index < speakers.size(); index++) replaceSpeaker(index);
            } else {
                replaceSpeaker(speakerIndex);
            }
        }
        if (minecraft != null && minecraft.screen == this) {
            refreshWidgetsFromState();
        }
        revealPendingTrack();
    }

    /**
     * Scrolls a map that has just joined the list into view and marks it, once the list from the server holds it. A
     * search that would hide the new card is cleared: the DJ has just asked for this map and should see it.
     */
    private void revealPendingTrack() {
        if (revealTrackId.isEmpty() || height <= 0) {
            return;
        }
        if (!library.contains(revealTrackId)) {
            // A refused add never shows up, and the server has said why; without this its card would be scrolled to
            // whenever it was added later.
            if (System.currentTimeMillis() - revealRequestedMs > 5_000L) {
                revealTrackId = "";
                revealAnnounce = null;
            }
            return;
        }
        if (revealAnnounce != null) {
            workshopStatus(Component.translatable("screen.music_battle.dj_panel.library_added", revealAnnounce),
                    false, null);
            revealAnnounce = null;
        }
        int slot = filteredSlotOf(revealTrackId);
        if (slot < 0 && searchBox != null) {
            searchBox.setValue("");
            slot = filteredSlotOf(revealTrackId);
        }
        if (slot < 0) {
            return;
        }
        int cardTop = slot / libraryColumns() * (cardHeight() + CARD_GAP);
        int viewport = layout().trackBottom() - trackTop();
        if (cardTop < trackScroll) {
            trackScroll = cardTop;
        } else if (cardTop + cardHeight() > trackScroll + viewport) {
            trackScroll = cardTop + cardHeight() - viewport;
        }
        revealedTrackId = revealTrackId;
        revealedAtMs = System.currentTimeMillis();
        revealTrackId = "";
    }

    /** Asks for {@code trackId}'s card to be scrolled into view and marked once the panel's list holds it. */
    private void reveal(String trackId) {
        revealTrackId = trackId;
        revealRequestedMs = System.currentTimeMillis();
        revealAnnounce = null;
    }

    private int filteredSlotOf(String trackId) {
        for (int slot = 0; slot < filteredTrackIndices.size(); slot++) {
            if (library.get(filteredTrackIndices.get(slot)).equals(trackId)) {
                return slot;
            }
        }
        return -1;
    }

    public void acceptCameraPreview(DjCameraPreviewPayload payload) {
        OpenDjPanelPayload.CameraEntry selected = selectedCamera();
        if (!panelPos.equals(payload.panelPos()) || selected == null
                || !selected.pos().equals(payload.cameraPos())
                || payload.serverGameTime() < acceptedCameraServerTime
                || !Double.isFinite(payload.x()) || !Double.isFinite(payload.y()) || !Double.isFinite(payload.z())
                || !Float.isFinite(payload.yaw()) || !Float.isFinite(payload.pitch()) || !Float.isFinite(payload.fov())) {
            return;
        }
        acceptedCameraServerTime = payload.serverGameTime();
        cameraSnapshot = new DjCameraPreviewRenderer.Snapshot(
                payload.available(), payload.serverGameTime(), new Vec3(payload.x(), payload.y(), payload.z()),
                Mth.wrapDegrees(payload.yaw()), Mth.clamp(payload.pitch(), -89.0F, 89.0F),
                Mth.clamp(payload.fov(), 30.0F, 110.0F));
    }

    @Override
    protected void init() {
        layout = PanelLayout.compute(width, height, TextStyles.measurer(), Words.game());
        newTooltips();
        // A page that is not shown has no widgets, so none of the old ones may linger in a field and be updated.
        speakerButton = speakerModeButton = unlinkButton = playButton = pauseButton = restartButton = speedButton = null;
        dspButton = reverbButton = echoButton = lowPassButton = cameraButton = cameraPreviousButton = cameraNextButton = null;
        unlinkCameraButton = visualFxButton = equalizerPresetsButton = audioPresetsButton = resetSpeakersButton = null;
        repeatButton = playlistButton = preloadAudienceButton = null;
        radiusSlider = volumeSlider = lowSlider = midSlider = highSlider = null;
        reverbWetSlider = reverbRoomSlider = reverbDampingSlider = echoWetSlider = echoTimeSlider = null;
        echoFeedbackSlider = lowPassSlider = timelineSlider = null;
        searchBox = null;
        selectedTab = null;
        libraryPlayButton = null;
        mixerWidgets.clear();
        if (layout.paged()) {
            addPageTabs();
        }
        if (libraryShown()) {
            addLibraryControls();
        }
        if (mixerShown()) {
            addMixerControls();
        }
        scrollMixer(mixerScroll);
        updateControls();
    }

    /**
     * Adds a control's widget: a library control as one of the screen's renderables, a mixer control to
     * {@link #mixerWidgets}, which takes the pointer and the keyboard like any widget and is drawn by {@link #renderMixer}.
     */
    private <T extends net.minecraft.client.gui.components.AbstractWidget> T addControl(PanelLayout.Control control, T widget) {
        if (control.library()) {
            return addRenderableWidget(widget);
        }
        addWidget(widget);
        mixerWidgets.put(widget, widget.getY());
        return widget;
    }

    /** Scrolls the mixer's column to {@code scroll}, kept inside its range, and moves its widgets there. */
    private void scrollMixer(int scroll) {
        mixerScroll = Mth.clamp(scroll, 0, layout().mixerScrollMax());
        mixerWidgets.forEach((widget, y) -> widget.setY(y - mixerScroll));
    }

    /** Whether the pointer is over the mixer's column where its scrolled rows are cut off: above or below its view. */
    private boolean outsideMixerView(double mouseX, double mouseY) {
        PanelLayout layout = layout();
        return mixerShown() && layout.mixerScrollMax() > 0 && mouseX >= layout.mixerColumn().x() - 2
                && (mouseY < layout.mixerViewTop() || mouseY >= layout.mixerViewBottom());
    }

    /**
     * The keyboard's focus on a mixer control scrolls it into the view, whole: Tab reaches every row of a scrolled mixer.
     * A pointer leaves the scroll alone; it can only press what it sees.
     */
    @Override
    public void setFocused(net.minecraft.client.gui.components.events.GuiEventListener listener) {
        super.setFocused(listener);
        if (listener instanceof net.minecraft.client.gui.components.AbstractWidget widget && mixerWidgets.containsKey(widget)
                && minecraft != null && minecraft.getLastInputType().isKeyboard()) {
            int y = mixerWidgets.get(widget);
            int top = layout().mixerViewTop() + 1;
            int bottom = layout().mixerViewBottom() - 1;
            if (y - mixerScroll < top) {
                scrollMixer(y - top);
            } else if (y + widget.getHeight() - mixerScroll > bottom) {
                scrollMixer(y + widget.getHeight() - bottom);
            }
        }
    }

    /** A new tooltip holder for a new build of the widgets, with the tips that are swapped as the state changes. */
    private void newTooltips() {
        tooltips = new UiTooltip.Deferred();
        playTip = tooltips.create(Component.translatable("screen.music_battle.dj_panel.play_resume"));
        pauseTip = tooltips.create(Component.translatable("screen.music_battle.dj_panel.pause"));
        noSpeakersTip = tooltips.create(Component.translatable("screen.music_battle.dj_panel.no_speakers"));
    }

    /** The two tabs of a narrow panel; switching rebuilds the screen with the other page's widgets. */
    private void addPageTabs() {
        PanelLayout.Page[] pages = PanelLayout.Page.values();
        float ruleY = layout.header() - 1.5F;
        for (int index = 0; index < pages.length; index++) {
            PanelLayout.Page target = pages[index];
            boolean selected = target == page;
            PageTab tab = addRenderableWidget(new PageTab(Component.translatable(PanelLayout.PAGE_KEYS.get(index)), button -> {
                if (page != target) {
                    flushAllPending();
                    page = target;
                    rebuildWidgets();
                }
            }, layout.tabs().get(index), selected, layout.tabFace(selected), ruleY));
            if (selected) {
                selectedTab = tab;
            }
        }
    }

    /**
     * Where the keyboard's focus lands when the panel is built, which switching pages does too: on a panel of two pages, the
     * tab of the page now showing, as a row of tabs keeps it. The game's own choice is the first control, the Library tab,
     * so Enter on Mixer opened the mixer with the focus back on Library, and a second Enter went back to the library. A
     * pointer leaves the focus alone, as everywhere.
     */
    @Override
    protected void setInitialFocus() {
        if (selectedTab != null && minecraft != null && minecraft.getLastInputType().isKeyboard()) {
            setInitialFocus(selectedTab);
            return;
        }
        super.setInitialFocus();
    }

    /** The camera's row and the library's search row: the left column, or the library page. */
    private void addLibraryControls() {
        cameraPreviousButton = button(PanelLayout.Control.CAMERA_PREVIOUS, Component.literal("<"), button -> cycleCamera(-1));
        cameraButton = button(PanelLayout.Control.CAMERA, cameraLabel(), button -> openCameraGallery());
        cameraButton.setTooltip(tooltips.create(
                Component.translatable("screen.music_battle.dj_camera_gallery.open_tooltip")));
        cameraNextButton = button(PanelLayout.Control.CAMERA_NEXT, Component.literal(">"), button -> cycleCamera(1));
        unlinkCameraButton = button(PanelLayout.Control.UNLINK_CAMERA,
                Component.translatable("screen.music_battle.dj_panel.unlink_camera"), button -> unlinkCamera());

        Rect field = layout.placed(PanelLayout.Control.SEARCH).rect();
        searchBox = addRenderableWidget(new ru.gora.musicbattle.client.ui.FancyEditBox(font, Math.round(field.x()),
                Math.round(field.y()), Math.round(field.w()), Math.round(field.h()),
                Component.translatable("screen.music_battle.dj_panel.search")));
        searchBox.setMaxLength(128);
        searchBox.setHint(searchHint(searchBox.getInnerWidth()));
        searchBox.setValue(searchQuery);
        searchBox.setResponder(value -> {
            searchQuery = value == null ? "" : value;
            trackScroll = 0;
            rebuildTrackFilter();
            updateControls();
        });
        // "+" sits beside the search because it is what fills this library: a map picked in the Workshop, or on its
        // Server tab, joins the booth's list and nothing else.
        Button workshopButton = button(PanelLayout.Control.WORKSHOP,
                Component.translatable("screen.music_battle.dj_panel.workshop"), button -> openWorkshop());
        workshopButton.setTooltip(tooltips.create(
                Component.translatable("screen.music_battle.dj_panel.library_add_tooltip")));
        preloadAudienceButton = button(PanelLayout.Control.PRELOAD,
                Component.translatable("screen.music_battle.dj_panel.preload_audience"), button -> preloadSelectedTrack());
        preloadAudienceButton.setTooltip(tooltips.create(
                Component.translatable("screen.music_battle.dj_panel.preload_audience_tooltip")));
        if (layout.paged()) {
            // The library page of a narrow panel plays the card just picked, pauses, and closes, without the mixer page.
            libraryPlayButton = button(PanelLayout.Control.LIBRARY_PLAY,
                    Component.translatable("screen.music_battle.dj_panel.play_resume"),
                    button -> { if (playing) pausePlayback(); else playResume(); });
            button(PanelLayout.Control.LIBRARY_CLOSE, Component.translatable("screen.music_battle.dj_panel.close"),
                    button -> onClose());
        }
    }

    /**
     * The search field's hint, in the vanilla font the edit box draws it with: the hint, else the field's shorter name,
     * else nothing. Vanilla draws a hint whole, past the field's edge onto the buttons beside it when it is longer.
     */
    private Component searchHint(int innerWidth) {
        Component hint = Component.translatable("screen.music_battle.dj_panel.search_hint");
        if (font.width(hint) <= innerWidth) {
            return hint;
        }
        Component name = Component.translatable("screen.music_battle.dj_panel.search");
        return font.width(name) <= innerWidth ? name : Component.empty();
    }

    /** The mixer: the right column, or the mixer page. */
    private void addMixerControls() {
        speakerModeButton = button(PanelLayout.Control.MODE, speakerModeLabel(), button -> toggleSpeakerMode());
        button(PanelLayout.Control.SPEAKER_PREVIOUS, Component.literal("<"), button -> cycleSpeaker(-1));
        speakerButton = button(PanelLayout.Control.SPEAKER, speakerLabel(), button -> cycleSpeaker(1));
        button(PanelLayout.Control.SPEAKER_NEXT, Component.literal(">"), button -> cycleSpeaker(1));
        // Disconnecting acts on the speaker the row has selected, so it sits at the row's end like the camera's "x".
        unlinkButton = button(PanelLayout.Control.UNLINK_SPEAKER,
                Component.translatable("screen.music_battle.dj_panel.unlink_speaker"), button -> unlinkSpeaker());
        refreshSpeakerTooltip();

        radiusSlider = slider(PanelLayout.Control.RANGE, radius, value -> { radius = value; queueSpeakerUpdate(); });
        resetSpeakersButton = button(PanelLayout.Control.RESET,
                Component.translatable("screen.music_battle.dj_panel.reset_speakers"), button -> resetSpeakers());
        resetSpeakersButton.setTooltip(tooltips.create(
                Component.translatable("screen.music_battle.dj_panel.reset_speakers_tooltip")));
        volumeSlider = slider(PanelLayout.Control.VOLUME, volume, value -> { volume = value; queueSpeakerUpdate(); });
        lowSlider = slider(PanelLayout.Control.LOW, lowGainDb, value -> { lowGainDb = value; queueSpeakerUpdate(); });
        midSlider = slider(PanelLayout.Control.MID, midGainDb, value -> { midGainDb = value; queueSpeakerUpdate(); });
        highSlider = slider(PanelLayout.Control.HIGH, highGainDb, value -> { highGainDb = value; queueSpeakerUpdate(); });
        equalizerPresetsButton = button(PanelLayout.Control.EQ_PRESETS,
                Component.translatable("screen.music_battle.dj_panel.eq_presets"), button -> openEqualizerPresets());
        equalizerPresetsButton.setTooltip(tooltips.create(
                Component.translatable("screen.music_battle.dj_panel.eq_presets_tooltip")));

        dspButton = button(PanelLayout.Control.DSP, toggleLabel("screen.music_battle.dj_panel.dsp"),
                button -> { dspEnabled = !dspEnabled; queuePanelFxUpdate(); });
        reverbButton = button(PanelLayout.Control.REVERB, toggleLabel("screen.music_battle.dj_panel.reverb"),
                button -> { reverbEnabled = !reverbEnabled; queuePanelFxUpdate(); });
        echoButton = button(PanelLayout.Control.ECHO, toggleLabel("screen.music_battle.dj_panel.echo"),
                button -> { echoEnabled = !echoEnabled; queuePanelFxUpdate(); });
        lowPassButton = button(PanelLayout.Control.LOW_PASS, toggleLabel("screen.music_battle.dj_panel.low_pass"),
                button -> { lowPassEnabled = !lowPassEnabled; queuePanelFxUpdate(); });

        reverbWetSlider = slider(PanelLayout.Control.REVERB_WET, reverbWet, value -> { reverbWet = value; queuePanelFxUpdate(); });
        reverbRoomSlider = slider(PanelLayout.Control.REVERB_ROOM, reverbRoom, value -> { reverbRoom = value; queuePanelFxUpdate(); });
        reverbDampingSlider = slider(PanelLayout.Control.REVERB_DAMPING, reverbDamping,
                value -> { reverbDamping = value; queuePanelFxUpdate(); });
        echoWetSlider = slider(PanelLayout.Control.ECHO_WET, echoWet, value -> { echoWet = value; queuePanelFxUpdate(); });
        echoTimeSlider = slider(PanelLayout.Control.ECHO_TIME, echoTimeMs, value -> { echoTimeMs = value; queuePanelFxUpdate(); });
        echoFeedbackSlider = slider(PanelLayout.Control.ECHO_FEEDBACK, echoFeedback,
                value -> { echoFeedback = value; queuePanelFxUpdate(); });
        lowPassSlider = slider(PanelLayout.Control.CUTOFF, lowPassCutoffHz,
                value -> { lowPassCutoffHz = value; queuePanelFxUpdate(); });
        audioPresetsButton = button(PanelLayout.Control.AUDIO_PRESETS,
                Component.translatable("screen.music_battle.dj_panel.audio_presets"), button -> openAudioPresets());
        audioPresetsButton.setTooltip(tooltips.create(
                Component.translatable("screen.music_battle.dj_panel.audio_presets_tooltip")));

        PanelLayout.Placed timeline = layout.placed(PanelLayout.Control.TIMELINE);
        PanelLayout.SliderSpec timelineSpec = PanelLayout.slider(PanelLayout.Control.TIMELINE);
        timelineSlider = addControl(PanelLayout.Control.TIMELINE, new SettingSlider(timeline.rect(), timelineSpec.key(), timelineDisplayPosition(),
                0.0F, Math.max(20.0F, selectedTimelineDuration()), value -> queueSeek(Math.round(value)), timelineSpec.unit(),
                true));

        playButton = button(PanelLayout.Control.PLAY, Component.translatable("screen.music_battle.dj_panel.play_resume"),
                button -> playResume());
        pauseButton = button(PanelLayout.Control.PAUSE, Component.translatable("screen.music_battle.dj_panel.pause"),
                button -> pausePlayback());
        restartButton = button(PanelLayout.Control.RESTART, Component.translatable("screen.music_battle.dj_panel.restart"),
                button -> restart());
        speedButton = button(PanelLayout.Control.SPEED, speedLabel(), button -> cycleSpeed());
        repeatButton = button(PanelLayout.Control.REPEAT, repeatLabel(), button -> cycleRepeat());
        repeatButton.setTooltip(tooltips.create(Component.translatable("screen.music_battle.dj_panel.repeat_tooltip")));

        visualFxButton = button(PanelLayout.Control.VISUAL_FX, Component.translatable("screen.music_battle.dj_panel.visual_fx"),
                button -> openVisualFx());
        playlistButton = button(PanelLayout.Control.PLAYLIST, Component.translatable("screen.music_battle.dj_panel.playlist"),
                button -> openPlaylist());
        button(PanelLayout.Control.CLOSE, Component.translatable("screen.music_battle.dj_panel.close"), button -> onClose());
    }

    /**
     * A button where the layout placed the control and in the form it chose: its words, the check box of a switch, or
     * an icon. A compact button keeps its words as its narration and its tooltip; a caller may set a fuller tooltip.
     */
    private PanelButton button(PanelLayout.Control control, Component message, Button.OnPress press) {
        PanelLayout.Placed placed = layout.placed(control);
        UiIcon icon = placed.kind() == PanelLayout.Kind.ICON ? icon(control) : null;
        String glyph = placed.kind() == PanelLayout.Kind.GLYPH ? PanelLayout.ADD_GLYPH : null;
        java.util.function.BooleanSupplier on = placed.kind() == PanelLayout.Kind.SWITCH ? switchState(control) : null;
        PanelButton button = addControl(control, new PanelButton(message, press, placed.rect(), icon,
                icon != null && struck(control), glyph, on));
        if ((icon != null || glyph != null) && !message.getString().isBlank() && !message.getString().equals("<")
                && !message.getString().equals(">")) {
            button.setTooltip(tooltips.create(message));
        }
        return button;
    }

    /**
     * The icon a compact control shows. Close is the cross; a disconnect is the link struck through ({@link #struck}). Both
     * disconnects used to be the same cross, and on the library page of a narrow panel the header's Close sits right
     * above the camera row's disconnect: one glyph for closing the panel and for cutting a camera off the booth.
     */
    static UiIcon icon(PanelLayout.Control control) {
        return switch (control) {
            case SPEAKER_PREVIOUS, CAMERA_PREVIOUS -> UiIcon.CHEVRON_LEFT;
            case SPEAKER_NEXT, CAMERA_NEXT -> UiIcon.CHEVRON_RIGHT;
            case LIBRARY_CLOSE -> UiIcon.X;
            case UNLINK_SPEAKER, UNLINK_CAMERA -> UiIcon.LINK;
            case RESET -> UiIcon.REFRESH;
            case EQ_PRESETS -> UiIcon.STAR;
            case AUDIO_PRESETS -> UiIcon.MUSIC;
            case PRELOAD -> UiIcon.DOWNLOAD;
            case PLAY, LIBRARY_PLAY -> UiIcon.PLAY;
            case PAUSE -> UiIcon.PAUSE;
            default -> UiIcon.INFO;
        };
    }

    /**
     * Whether a control's icon is struck through, as Lucide marks a thing switched off (EYE_OFF): the two disconnects,
     * whose link is cut. The atlas has no unlink icon of its own.
     */
    static boolean struck(PanelLayout.Control control) {
        return control == PanelLayout.Control.UNLINK_SPEAKER || control == PanelLayout.Control.UNLINK_CAMERA;
    }

    /** What a switch's check box shows: read every frame, so it follows the rack as the server confirms it. */
    private java.util.function.BooleanSupplier switchState(PanelLayout.Control control) {
        return switch (control) {
            case DSP -> () -> dspEnabled;
            case REVERB -> () -> reverbEnabled;
            case ECHO -> () -> echoEnabled;
            case LOW_PASS -> () -> lowPassEnabled;
            default -> () -> false;
        };
    }

    private SettingSlider slider(PanelLayout.Control control, float current, Setter setter) {
        PanelLayout.SliderSpec spec = PanelLayout.slider(control);
        return addControl(control, new SettingSlider(layout.placed(control).rect(), spec.key(), current, spec.minimum(),
                spec.maximum(), setter, spec.unit(), false));
    }

    /**
     * Without a speaker the speaker's button says only that there is none; how to connect one, the longer sentence that
     * used to be cut there, is its tooltip.
     */
    private void refreshSpeakerTooltip() {
        if (speakerButton != null) {
            speakerButton.setTooltip(speakers.isEmpty() ? noSpeakersTip : null);
        }
    }

    private boolean libraryShown() {
        return layout == null || !layout.paged() || page == PanelLayout.Page.LIBRARY;
    }

    private boolean mixerShown() {
        return layout == null || !layout.paged() || page == PanelLayout.Page.MIXER;
    }

    @Override
    public void tick() {
        super.tick();
        if (speakerUpdateCooldown > 0) speakerUpdateCooldown--;
        if (panelFxUpdateCooldown > 0) panelFxUpdateCooldown--;
        if (previewRequestCooldown > 0) previewRequestCooldown--;
        tickCameraPreviewRequest();
        if (preserveLocalSpeakerTicks > 0) preserveLocalSpeakerTicks--;
        if (preserveLocalPanelFxTicks > 0) preserveLocalPanelFxTicks--;
        if (preserveLocalVisualFxTicks > 0) preserveLocalVisualFxTicks--;
        if (speakerUpdatePending && speakerUpdateCooldown <= 0) flushSpeakerUpdate();
        if (panelFxUpdatePending && panelFxUpdateCooldown <= 0) flushPanelFxUpdate();
        if (pendingSeekTicks >= 0 && --seekDebounceTicks <= 0) flushSeek();
        if (timelineSlider != null && pendingSeekTicks < 0 && !timelineSlider.isEditing()) {
            timelineSlider.setRange(0.0F, Math.max(20.0F, selectedTimelineDuration()));
            timelineSlider.setSetting(timelineDisplayPosition());
        }
    }

    /** Keeps the active camera snapshot fresh while a nested DJ editor owns this panel. */
    void tickCameraPreviewRequest() {
        if (--serverStateRefreshCooldown <= 0) {
            // Natural playlist transitions happen server-side without a requesting player.
            // Polling once per second keeps the open panel and nested FX editors authoritative
            // without reopening them or overwriting their pending live controls.
            send(ConfigureDjPanelPayload.Action.REFRESH, BlockPos.ZERO, "", false, 0);
            serverStateRefreshCooldown = 20;
        }
        if (cameraPreviewRequestCooldown > 0) {
            cameraPreviewRequestCooldown--;
        }
        OpenDjPanelPayload.CameraEntry camera = selectedCamera();
        if (cameraPreviewRequestCooldown <= 0 && camera != null) {
            PacketDistributor.sendToServer(new DjCameraPreviewRequestPayload(panelPos, camera.pos()));
            cameraPreviewRequestCooldown = 2;
        }
    }

    /** Draws the isolated active-camera world pass for DJ child screens. */
    boolean renderActiveCameraPreview(GuiGraphics graphics, int x, int y, int previewWidth,
                                      int previewHeight, float partialTick) {
        graphics.flush();
        cameraPreviewStatus = DjCameraPreviewRenderer.updateLiveMonitor(cameraSnapshot, previewWidth, previewHeight,
                partialTick, monitorEffectState(partialTick), monitorPlayerEntityIds, monitorMobEntityIds);
        if (cameraPreviewStatus != DjCameraPreviewRenderer.Status.READY) return false;
        DjCameraPreviewRenderer.draw(graphics, x, y, previewWidth, previewHeight);
        return true;
    }

    boolean renderActiveCameraPreview(GuiGraphics graphics, int x, int y, int previewWidth,
                                      int previewHeight, float partialTick,
                                      ClientTrackEffects.EffectState effectState) {
        graphics.flush();
        cameraPreviewStatus = DjCameraPreviewRenderer.update(
                cameraSnapshot, previewWidth, previewHeight, partialTick, effectState);
        if (cameraPreviewStatus != DjCameraPreviewRenderer.Status.READY) {
            return false;
        }
        DjCameraPreviewRenderer.draw(graphics, x, y, previewWidth, previewHeight);
        return true;
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        PanelLayout layout = layout();
        boolean library = libraryShown();
        pointerHint = null;
        pointerHintOver = null;
        pointerHintInner = null;
        if (library) {
            Rect monitor = layout.monitor();
            graphics.flush();
            cameraPreviewStatus = DjCameraPreviewRenderer.updateLiveMonitor(cameraSnapshot, Math.round(monitor.w()),
                    Math.round(monitor.h()), partialTick, monitorEffectState(partialTick), monitorPlayerEntityIds,
                    monitorMobEntityIds);
        }
        graphics.fill(0, 0, width, height, Palette.withAlpha(Palette.BG_0, 0.95F));
        Component heading = layout.titleFace() == null ? null : TextStyles.styled(title, layout.titleFace());
        if (!layout.paged()) {
            int divider = layout.divider();
            graphics.fill(divider, 0, width, height, Palette.BG_1);
            graphics.fill(divider, 0, divider + 2, height, Palette.ACCENT);
            TextStyles.queueCentered(graphics, heading.getVisualOrderText(), divider + (width - divider) / 2.0F,
                    layout.titleY(), Palette.TEXT_1);
            line(graphics, Component.translatable(PanelLayout.MONITOR_HEADING).getString(), TextStyle.LABEL,
                    layout.libraryColumn().x() + 2, layout.headingY(), layout.libraryColumn().w() - 4, Palette.TEXT_2);
        } else {
            // The tabs sit in a header bar like the settings screen's. Its right end holds the title on the mixer page,
            // and on the library page what is playing beside that page's Play or Pause and Close.
            RoundedRect.fill(graphics, 0, 0, width, layout.header(), 0, Palette.BG_1);
            RoundedRect.fill(graphics, 0, layout.header() - 1, width, 1, 0, Palette.LINE);
            if (library) {
                Rect now = layout.nowPlaying();
                TextStyle face = layout.nowPlayingFace();
                String state = headerStateText(face, now.w());
                TextStyles.queueRight(graphics, TextStyles.text(state, face).getVisualOrderText(), now.right(),
                        TextStyles.centeredCapY(face, now.y(), now.h()), playing ? Palette.OK : Palette.TEXT_3);
            } else if (heading != null) {
                TextStyles.queueRight(graphics, heading.getVisualOrderText(), width - PanelLayout.RIGHT_MARGIN,
                        layout.titleY(), Palette.TEXT_1);
            }
        }
        if (library) {
            drawCameraMonitor(graphics);
            drawLibraryHeader(graphics, mouseX, mouseY);
            drawLibrary(graphics, mouseX, mouseY);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
        if (mixerShown()) {
            renderMixer(graphics, mouseX, mouseY, partialTick);
        }
        drawTooltip(graphics, mouseX, mouseY);
    }

    /**
     * The mixer's rows and the line saying what plays under them, scrolled by {@link #mixerScroll}. Where the window is too
     * short for them they are cut to the mixer's view, a pointer outside the view hovers none of them, and a scrollbar at
     * the column's right edge shows where the view is.
     */
    private void renderMixer(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        PanelLayout layout = layout();
        Rect mixer = layout.mixerColumn();
        int top = layout.mixerViewTop();
        int bottom = layout.mixerViewBottom();
        boolean scrolls = layout.mixerScrollMax() > 0;
        boolean inView = !scrolls || mouseY >= top && mouseY < bottom;
        int pointerX = inView ? mouseX : -10_000;
        int pointerY = inView ? mouseY : -10_000;
        if (scrolls) {
            UiClip.push(graphics, mixer.x() - 2, top, mixer.right() + 2, bottom);
        }
        String state = playbackStateText(TextStyle.BODY, mixer.w());
        TextStyles.queueCentered(graphics, TextStyles.text(state, TextStyle.BODY).getVisualOrderText(),
                mixer.centerX(), layout.stateY() + 1 - mixerScroll, playing ? Palette.OK : Palette.TEXT_3);
        for (net.minecraft.client.gui.components.AbstractWidget widget : mixerWidgets.keySet()) {
            widget.render(graphics, pointerX, pointerY, partialTick);
        }
        if (scrolls) {
            UiClip.pop(graphics);
            WorkshopDraw.scrollbar(graphics, new Rect(mixer.x(), top, mixer.w() + 8, bottom - top), mixerScroll,
                    (bottom - top) + layout.mixerScrollMax(), false);
        }
    }

    /** A tooltip the panel's holder made is drawn by {@link #drawTooltip} as the toolkit's card; any other stays vanilla's. */
    @Override
    public void setTooltipForNextRenderPass(Tooltip tooltip, ClientTooltipPositioner positioner, boolean focused) {
        if (!tooltips.offer(tooltip, positioner)) {
            super.setTooltipForNextRenderPass(tooltip, positioner, focused);
        }
    }

    /**
     * The toolkit's card for what was asked about this frame, after every widget so it lies over them: a widget's own tip
     * (under the pointer, or centred under it when the keyboard reached it), else what the library says under the pointer.
     * It goes up in the foreground layer the editors' controls use, so nothing the live monitor draws covers it.
     */
    private void drawTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
        Component hint = pointerHint;
        Rect over = pointerHintOver;
        Rect inner = pointerHintInner;
        pointerHint = null;
        pointerHintOver = null;
        pointerHintInner = null;
        boolean widgetTip = !tooltips.placement(TextStyles.measurer(), mouseX, mouseY, width, height, getFocused()).empty();
        if (!widgetTip && hint == null) {
            return;
        }
        ClientUiForeground.render(graphics.pose(), graphics::flush, () -> {
            if (widgetTip) {
                tooltips.draw(graphics, mouseX, mouseY, width, height, getFocused());
            } else {
                UiTooltip.draw(graphics, hintPlacement(TextStyles.measurer(), hint.getString(), mouseX, mouseY, over, inner,
                        width, height));
            }
        });
    }

    /**
     * Where the card for the library's own hint goes: beside the pointer, never over the card, the "×" or the status line
     * it explains. It used to be the plain pointer card: below the pointer it began 12 units down and, flipped
     * above it near the window's bottom, ended 6 units over it, so with the pointer low on the 14-unit "×" it covered the
     * button and the library card it was about. Pure, for ui-leftovers.
     */
    static UiTooltip.Placement hintPlacement(TextMeasurer measurer, String hint,
                                             double mouseX, double mouseY, Rect over, Rect inner, int width, int height) {
        return UiTooltip.place(measurer, hint, mouseX, mouseY, over, inner, width, height);
    }

    /** The layout of the window as it is now; init sets it, and a panel not yet shown lays itself out on demand. */
    private PanelLayout layout() {
        if (layout == null || layout.width() != width || layout.height() != height) {
            layout = PanelLayout.compute(width, height, TextStyles.measurer(), Words.game());
        }
        return layout;
    }

    private void drawCameraMonitor(GuiGraphics graphics) {
        Rect monitor = layout().monitor();
        float x = monitor.x();
        float y = monitor.y();
        int monitorWidth = Math.round(monitor.w());
        int monitorHeight = Math.round(monitor.h());
        RoundedRect.fill(graphics, x - 1, y - 1, monitorWidth + 2, monitorHeight + 2, Palette.RADIUS_SM, Palette.BG_0);
        RoundedRect.stroke(graphics, x - 1, y - 1, monitorWidth + 2, monitorHeight + 2, Palette.RADIUS_SM, 1.0F,
                Palette.ACCENT);
        if (cameraPreviewStatus == DjCameraPreviewRenderer.Status.READY) {
            DjCameraPreviewRenderer.drawLiveMonitor(graphics, Math.round(x), Math.round(y), monitorWidth, monitorHeight);
            drawLiveBadge(graphics, monitor);
            return;
        }
        String key = switch (cameraPreviewStatus) {
            case CAMERA_UNAVAILABLE -> cameras.isEmpty()
                    ? "screen.music_battle.dj_panel.camera_link_hint"
                    : "screen.music_battle.dj_panel.camera_offline";
            case WORLD_UNAVAILABLE -> "screen.music_battle.dj_panel.camera_world_unavailable";
            case CHUNK_UNAVAILABLE -> "screen.music_battle.dj_panel.camera_chunk_unavailable";
            case SHADERS_ACTIVE -> "screen.music_battle.dj_panel.camera_shaders_active";
            case RENDER_FAILED -> "screen.music_battle.dj_panel.camera_render_failed";
            case READY -> "screen.music_battle.dj_panel.camera_link_hint";
        };
        String message = Component.translatable(key).getString();
        PanelLayout.Block block = PanelLayout.monitorBlock(TextStyles.measurer(), message, monitor);
        if (block == null) {
            // Only a language the layout check never measured lands here; its words are cut rather than spilled.
            block = new PanelLayout.Block(TextStyle.CAPTION, TextFit.wrap(TextStyles.measurer(), message, TextStyle.CAPTION,
                    Math.max(24, monitorWidth - 12), Math.max(1, (int) ((monitorHeight - 6)
                            / Metrics.lineHeight(TextStyle.CAPTION)))));
        }
        float lineHeight = Metrics.lineHeight(block.face());
        float drawY = y + Math.max(3.0F, (monitorHeight - block.height()) / 2.0F + 1);
        for (String monitorLine : block.lines()) {
            TextStyles.queueCentered(graphics, TextStyles.text(monitorLine, block.face()).getVisualOrderText(),
                    x + monitorWidth / 2.0F, drawY, Palette.TEXT_3);
            drawY += lineHeight;
        }
    }

    /**
     * The monitor's on-air mark over the picture: a red dot and LIVE on a dark pill at the top left, and the camera's
     * position at the top right when there is room, both in the toolkit's faces. It was a black strip with the bitmap
     * font in a pink of the first look.
     */
    private void drawLiveBadge(GuiGraphics graphics, Rect monitor) {
        Component live = Component.translatable("screen.music_battle.dj_panel.camera_live_badge");
        float textWidth = TextStyles.width(TextStyles.styled(live, TextStyle.PILL));
        float pillHeight = TextStyle.PILL.capHeight() + 6.0F;
        float pillWidth = 4.0F + 5.0F + 3.0F + textWidth + 5.0F;
        float pillX = monitor.x() + 3.0F;
        float pillY = monitor.y() + 3.0F;
        RoundedRect.pill(graphics, pillX, pillY, pillWidth, pillHeight, Palette.withAlpha(Palette.BG_0, 0.72F));
        RoundedRect.circle(graphics, pillX + 4.0F + 2.5F, pillY + pillHeight / 2.0F, 2.5F, Palette.DANGER);
        TextStyles.queue(graphics, TextStyles.styled(live, TextStyle.PILL).getVisualOrderText(), pillX + 12.0F,
                TextStyles.centeredCapY(TextStyle.PILL, pillY, pillHeight), Palette.TEXT_1);
        OpenDjPanelPayload.CameraEntry camera = selectedCamera();
        float room = monitor.right() - 3.0F - (pillX + pillWidth + 8.0F) - 10.0F;
        if (camera != null && room >= 40.0F) {
            String coords = TextFit.ellipsize(TextStyles.measurer(), camera.pos().toShortString(), TextStyle.CAPTION, room);
            float coordsWidth = TextFit.width(TextStyles.measurer(), coords, TextStyle.CAPTION);
            float coordsX = monitor.right() - 3.0F - coordsWidth - 10.0F;
            RoundedRect.pill(graphics, coordsX, pillY, coordsWidth + 10.0F, pillHeight,
                    Palette.withAlpha(Palette.BG_0, 0.72F));
            TextStyles.queue(graphics, TextStyles.text(coords, TextStyle.CAPTION).getVisualOrderText(), coordsX + 5.0F,
                    TextStyles.centeredCapY(TextStyle.CAPTION, pillY, pillHeight), Palette.TEXT_2);
        }
    }

    /**
     * The line above the library: its name, or while there is news about a "+" pick, that news. A refusal of the
     * current pick keeps Retry and Dismiss at the right end until the DJ answers it.
     */
    private void drawLibraryHeader(GuiGraphics graphics, int mouseX, int mouseY) {
        Rect column = layout().libraryColumn();
        float left = column.x() + 2;
        float right = column.right();
        float y = layout().libraryHeadingY();
        linksTop = Float.NaN;
        boolean current = workshopStatus != null && panelPos.equals(workshopPickPanel);
        if (current && workshopRetryable) {
            Component retry = Component.translatable("screen.music_battle.dj_playlist.workshop.retry");
            Component dismiss = Component.translatable("screen.music_battle.dj_panel.library_dismiss");
            dismissRight = right;
            dismissLeft = dismissRight - TextStyles.width(TextStyles.styled(dismiss, TextStyle.LABEL));
            retryRight = dismissLeft - 10;
            retryLeft = retryRight - TextStyles.width(TextStyles.styled(retry, TextStyle.LABEL));
            linksTop = y;
            statusLine(graphics, mouseX, mouseY, left, y, retryLeft - 10 - left, Palette.DANGER);
            boolean overRetry = overLink(mouseX, mouseY, retryLeft, retryRight);
            boolean overDismiss = overLink(mouseX, mouseY, dismissLeft, dismissRight);
            TextStyles.queue(graphics, TextStyles.styled(retry, TextStyle.LABEL).getVisualOrderText(), retryLeft, y,
                    overRetry ? Palette.TEXT_1 : Palette.LINK);
            TextStyles.queue(graphics, TextStyles.styled(dismiss, TextStyle.LABEL).getVisualOrderText(), dismissLeft,
                    y, overDismiss ? Palette.TEXT_1 : Palette.TEXT_3);
            return;
        }
        if (current && System.currentTimeMillis() < workshopStatusUntilMs) {
            statusLine(graphics, mouseX, mouseY, left, y, right - left,
                    workshopStatusProblem ? Palette.DANGER : Palette.OK);
            return;
        }
        // The layout picked the heading that fits the column whole: the name and how to use it, or the name alone.
        line(graphics, Component.translatable(layout().libraryHeadingKey()).getString(), TextStyle.LABEL, left, y,
                right - left, Palette.TEXT_2);
    }

    /**
     * The news about a pick, on one line. A refusal is often longer than the library is wide, and its reason is the
     * part that gets cut, so the whole of it shows as a tooltip under the pointer.
     */
    private void statusLine(GuiGraphics graphics, int mouseX, int mouseY, float left, float y, float maxWidth,
                            int colour) {
        String text = workshopStatus.getString();
        line(graphics, text, TextStyle.CAPTION, left, y + 1, maxWidth, colour);
        boolean cut = TextStyles.measurer().width(text, TextStyle.CAPTION) > maxWidth;
        if (cut && mouseX >= left && mouseX < left + maxWidth && mouseY >= y - 2
                && mouseY < y + TextStyle.CAPTION.capHeight() + 4) {
            pointerHint = workshopStatus;
            pointerHintOver = new Rect(left, y - 2, maxWidth, TextStyle.CAPTION.capHeight() + 6);
        }
    }

    private boolean overLink(double mouseX, double mouseY, float linkLeft, float linkRight) {
        return !Float.isNaN(linksTop) && mouseX >= linkLeft - 2 && mouseX < linkRight + 2
                && mouseY >= linksTop - 3 && mouseY < linksTop + TextStyle.LABEL.capHeight() + 3;
    }

    /**
     * The booth's own maps as cards, in the toolkit's look: rounded, lifted under the pointer, the selected one in the
     * accent, each with its "×" to take it off the list. Three passes like the Workshop's grid, because a cover draws
     * immediately and flushes what is queued: every shape, then every cover, then all text and icons.
     */
    private void drawLibrary(GuiGraphics graphics, int mouseX, int mouseY) {
        int left = Math.round(layout().libraryColumn().x());
        int top = trackTop();
        int bottom = layout().trackBottom();
        int areaWidth = libraryAreaWidth();
        if (filteredTrackIndices.isEmpty()) {
            drawLibraryEmpty(graphics, left, top, areaWidth, bottom);
            return;
        }
        trackScroll = Mth.clamp(trackScroll, 0, maxLibraryScroll());
        List<Integer> slots = new ArrayList<>();
        for (int slot = 0; slot < filteredTrackIndices.size(); slot++) {
            float y = cardY(slot);
            if (y + cardHeight() >= top && y <= bottom) {
                slots.add(slot);
            }
        }
        boolean pointerInside = mouseX >= left && mouseX < left + areaWidth && mouseY >= top && mouseY < bottom;
        UiClip.push(graphics, left, top, left + areaWidth, bottom);
        List<String> visibleIds = new ArrayList<>();
        int hoveredSlot = -1;
        boolean hoveredRemove = false;
        for (int slot : slots) {
            String trackId = library.get(filteredTrackIndices.get(slot));
            TrackSummary track = summary(trackId);
            float x = cardX(slot);
            float y = cardY(slot);
            boolean over = pointerInside && mouseX >= x && mouseX < x + cardWidth() && mouseY >= y
                    && mouseY < y + cardHeight();
            if (over) {
                hoveredSlot = slot;
                hoveredRemove = removeRect(x, y).contains(mouseX, mouseY);
            }
            boolean selected = track != null && trackId.equals(selectedTrackId());
            int fill = track == null ? Palette.BG_1
                    : selected ? Palette.mix(Palette.BG_3, Palette.ACCENT_DEEP, 0.85F)
                    : over ? Palette.BG_3 : Palette.BG_2;
            if (over && track != null) {
                RoundedRect.shadow(graphics, x, y + 2, cardWidth(), cardHeight(), Palette.RADIUS_MD, 7.0F,
                        Palette.SHADOW_LIFTED);
            }
            RoundedRect.fill(graphics, x, y, cardWidth(), cardHeight(), Palette.RADIUS_MD, fill);
            if (selected) {
                RoundedRect.stroke(graphics, x, y, cardWidth(), cardHeight(), Palette.RADIUS_MD, 1.0F, Palette.ACCENT);
            }
            float reveal = revealStrength(trackId);
            if (reveal > 0.0F) {
                RoundedRect.stroke(graphics, x, y, cardWidth(), cardHeight(), Palette.RADIUS_MD, 2.0F,
                        Palette.withAlpha(Palette.ACCENT_STRONG, reveal));
            }
            // The rail on the left edge lights up under the pointer, the way the track library's rows do.
            RoundedRect.pill(graphics, x + 1.5F, y + 8, 2.5F, cardHeight() - 16,
                    selected ? Palette.ACCENT : over ? Palette.mix(Palette.LINE, Palette.ACCENT, 0.6F) : Palette.LINE);
            if (over && hoveredRemove) {
                Rect remove = removeRect(x, y);
                RoundedRect.fill(graphics, remove.x(), remove.y(), remove.w(), remove.h(), Palette.RADIUS_SM,
                        Palette.mix(Palette.BG_3, Palette.DANGER, 0.55F));
            }
        }
        for (int slot : slots) {
            TrackSummary track = summary(library.get(filteredTrackIndices.get(slot)));
            float x = cardX(slot) + 5;
            float y = cardY(slot) + 5;
            ResourceLocation cover = track == null ? null : previewTexture(track);
            if (cover != null) {
                RoundedImage.draw(graphics, cover, x, y, cover(), cover(), Palette.RADIUS_SM, 0.0F, 1.0F);
            } else {
                RoundedRect.fill(graphics, x, y, cover(), cover(), Palette.RADIUS_SM, Palette.BG_3);
            }
        }
        for (int slot : slots) {
            String trackId = library.get(filteredTrackIndices.get(slot));
            TrackSummary track = summary(trackId);
            float x = cardX(slot);
            float y = cardY(slot);
            if (track != null) {
                visibleIds.add(trackId);
            }
            if (track == null || previewTexture(track) == null) {
                Rect cover = new Rect(x + 5, y + 5, cover(), cover());
                WorkshopDraw.icon(graphics, cover.inset(cover() * 0.3F, cover() * 0.3F),
                        track == null ? UiIcon.ALERT : UiIcon.MUSIC, Palette.withAlpha(Palette.TEXT_MUTE, 0.7F));
            }
            Rect remove = removeRect(x, y);
            boolean removeHot = slot == hoveredSlot && hoveredRemove;
            WorkshopDraw.icon(graphics, remove.inset(3.0F, 3.0F), UiIcon.X,
                    removeHot ? Palette.TEXT_1 : Palette.withAlpha(Palette.TEXT_MUTE, 0.85F));
            float textX = x + 5 + cover() + 8;
            float textWidth = Math.max(20.0F, remove.x() - 4 - textX);
            boolean compact = cardHeight() < CARD_HEIGHT;
            float lineGap = compact ? 4 : 6;
            float lineY = y + (compact ? 6 : 9);
            if (track == null) {
                line(graphics, trackId, TextStyle.TITLE, textX, lineY, textWidth, Palette.TEXT_MUTE);
                // Why the card cannot play takes two lines where one is too short for it; the layout made sure two are
                // enough, and it is a sentence the DJ needs whole.
                String missing = Component.translatable("screen.music_battle.dj_panel.library_missing").getString();
                PanelLayout.Block block = PanelLayout.missingBlock(TextStyles.measurer(), missing, textWidth);
                List<String> missingLines = block != null ? block.lines()
                        : TextFit.wrap(TextStyles.measurer(), missing, TextStyle.CAPTION, textWidth, 2);
                float missingY = lineY + TextStyle.TITLE.capHeight() + lineGap + 1;
                for (String missingLine : missingLines) {
                    line(graphics, missingLine, TextStyle.CAPTION, textX, missingY, textWidth, Palette.WARN);
                    missingY += Metrics.lineHeight(TextStyle.CAPTION);
                }
                continue;
            }
            line(graphics, track.title(), TextStyle.TITLE, textX, lineY, textWidth, Palette.TEXT_1);
            lineY += TextStyle.TITLE.capHeight() + lineGap + 1;
            line(graphics, "BPM " + track.bpm() + "  ·  " + formatTicks(track.durationTicks()), TextStyle.CAPTION,
                    textX, lineY, textWidth, Palette.TEXT_MUTE);
            lineY += TextStyle.CAPTION.capHeight() + lineGap;
            String author = track.publishedBy().isBlank()
                    ? Component.translatable("screen.music_battle.dj_panel.builtin").getString()
                    : Component.translatable("screen.music_battle.dj_panel.published_by", track.publishedBy()).getString();
            line(graphics, author, TextStyle.CAPTION, textX, lineY, textWidth, Palette.TEXT_3);
            if (!compact) {
                lineY += TextStyle.CAPTION.capHeight() + lineGap;
                line(graphics, track.description(), TextStyle.CAPTION, textX, lineY, textWidth, Palette.TEXT_3);
            }
        }
        UiClip.pop(graphics);
        WorkshopDraw.scrollbar(graphics, new Rect(left, top, areaWidth + 6, bottom - top), trackScroll,
                (bottom - top) + maxLibraryScroll(), false);
        requestVisiblePreviews(visibleIds);
        // Deferred to the end of the frame (drawTooltip), so the mixer's buttons drawn after the library never cover it.
        // Each kept off the card it is about, as far as the library shows it, and the "×" off its own button.
        Rect hoveredCard = hoveredSlot < 0 ? null : new Rect(cardX(hoveredSlot), cardY(hoveredSlot), cardWidth(),
                cardHeight()).intersect(new Rect(left, top, areaWidth, bottom - top));
        if (hoveredSlot >= 0 && hoveredRemove) {
            pointerHint = Component.translatable("screen.music_battle.dj_panel.library_remove");
            pointerHintOver = hoveredCard;
            pointerHintInner = removeRect(cardX(hoveredSlot), cardY(hoveredSlot));
        } else if (hoveredSlot >= 0) {
            TrackSummary hovered = summary(library.get(filteredTrackIndices.get(hoveredSlot)));
            if (hovered != null && !hovered.publishedBy().isBlank()) {
                pointerHint = Component.translatable("screen.music_battle.dj_panel.published_by", hovered.publishedBy());
                pointerHintOver = hoveredCard;
            }
        }
    }

    /** Nothing to show: why, with the icon that goes with it, on the Workshop's empty panel. */
    private void drawLibraryEmpty(GuiGraphics graphics, int left, int top, int areaWidth, int bottom) {
        float panelHeight = PanelLayout.emptyPanelHeight(top, bottom);
        if (panelHeight < 24) {
            return;
        }
        RoundedRect.fill(graphics, left, top, areaWidth, panelHeight, Palette.RADIUS_MD, Palette.BG_1);
        RoundedRect.stroke(graphics, left, top, areaWidth, panelHeight, Palette.RADIUS_MD, 1.0F,
                Palette.withAlpha(Palette.LINE, 0.6F));
        boolean searching = !searchQuery.isBlank() && !library.isEmpty();
        float centerX = left + areaWidth / 2.0F;
        String message = Component.translatable(searching ? "screen.music_battle.dj_panel.no_search_results"
                : "screen.music_battle.dj_panel.library_empty").getString();
        // As many lines as the message needs, in the body face or, where the panel is short, the caption: in Russian at
        // GUI scale 3 a cap of two lines once cut the hint about "+" before it said where maps come from. The layout
        // check makes sure the panel holds every line. The icon and the lines are centred together.
        PanelLayout.Block block = PanelLayout.emptyBlock(TextStyles.measurer(), message, areaWidth, panelHeight);
        if (block == null) {
            block = new PanelLayout.Block(TextStyle.CAPTION, TextFit.wrap(TextStyles.measurer(), message, TextStyle.CAPTION,
                    areaWidth - 24.0F, Math.max(1, (int) ((panelHeight - 28) / Metrics.lineHeight(TextStyle.CAPTION)))));
        }
        float blockHeight = 20 + block.height();
        float iconY = top + Math.max(4.0F, (panelHeight - blockHeight) / 2.0F);
        WorkshopDraw.icon(graphics, new Rect(centerX - 7, iconY, 14, 14), searching ? UiIcon.SEARCH : UiIcon.MUSIC,
                Palette.withAlpha(Palette.TEXT_MUTE, 0.8F));
        float y = iconY + 20;
        for (String messageLine : block.lines()) {
            TextStyles.queueCentered(graphics, TextStyles.text(messageLine, block.face()).getVisualOrderText(),
                    centerX, y, Palette.TEXT_2);
            y += Metrics.lineHeight(block.face());
        }
    }

    private void line(GuiGraphics graphics, String value, TextStyle style, float x, float y, float maxWidth, int colour) {
        String fitted = TextFit.ellipsize(TextStyles.measurer(), value == null ? "" : value, style, maxWidth);
        if (!fitted.isEmpty()) {
            TextStyles.queue(graphics, TextStyles.text(fitted, style).getVisualOrderText(), x, y, colour);
        }
    }

    private TrackSummary summary(String trackId) {
        int index = findTrack(trackId);
        return index >= 0 ? tracks.get(index) : null;
    }

    /** The server's fallback selection, mirrored: the song on the deck, else the first playable card, else none. */
    private int fallbackTrackIndex() {
        int loaded = findTrack(loadedTrackId);
        if (loaded >= 0) {
            return loaded;
        }
        for (String trackId : library) {
            int index = findTrack(trackId);
            if (index >= 0) {
                return index;
            }
        }
        return -1;
    }

    /** How strongly a card just revealed by "+" is outlined: full at first, gone after a second and a half. */
    private float revealStrength(String trackId) {
        if (!trackId.equals(revealedTrackId)) {
            return 0.0F;
        }
        float age = (System.currentTimeMillis() - revealedAtMs) / 1500.0F;
        return age >= 1.0F ? 0.0F : 1.0F - age * age;
    }

    private int libraryAreaWidth() {
        return Math.round(layout().libraryColumn().w());
    }

    private int libraryColumns() {
        return layout().columns();
    }

    private float cardWidth() {
        int columns = libraryColumns();
        return (libraryAreaWidth() - CARD_GAP * (columns - 1)) / (float) columns;
    }

    /** Full cards while two of them fit the library's height, compact ones below that. */
    private int cardHeight() {
        return layout().cardHeight();
    }

    /** The cover fills the card's height inside its five-unit margin. */
    private int cover() {
        return cardHeight() - 10;
    }

    private float cardX(int slot) {
        return layout().libraryColumn().x() + slot % libraryColumns() * (cardWidth() + CARD_GAP);
    }

    private float cardY(int slot) {
        return trackTop() + slot / libraryColumns() * (cardHeight() + CARD_GAP) - trackScroll;
    }

    private Rect removeRect(float cardX, float cardY) {
        return new Rect(cardX + cardWidth() - REMOVE_SIZE - 5, cardY + 5, REMOVE_SIZE, REMOVE_SIZE);
    }

    private int maxLibraryScroll() {
        int rows = (filteredTrackIndices.size() + libraryColumns() - 1) / libraryColumns();
        return Math.max(0, rows * (cardHeight() + CARD_GAP) - CARD_GAP - (layout().trackBottom() - trackTop()));
    }

    /** The card slot under the pointer, or -1; only inside the library's own viewport, and only while it is shown. */
    private int librarySlotAt(double mouseX, double mouseY) {
        Rect column = layout().libraryColumn();
        if (!libraryShown() || mouseX < column.x() || mouseX >= column.right() || mouseY < trackTop()
                || mouseY >= layout().trackBottom()) {
            return -1;
        }
        for (int slot = 0; slot < filteredTrackIndices.size(); slot++) {
            float x = cardX(slot);
            float y = cardY(slot);
            if (mouseX >= x && mouseX < x + cardWidth() && mouseY >= y && mouseY < y + cardHeight()) {
                return slot;
            }
        }
        return -1;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && libraryShown() && layout().monitor().contains(mouseX, mouseY)) {
            openCameraGallery();
            return true;
        }
        if (button == 0 && libraryShown() && workshopRetryable && overLink(mouseX, mouseY, retryLeft, retryRight)) {
            retryWorkshopPick();
            return true;
        }
        if (button == 0 && libraryShown() && workshopRetryable && overLink(mouseX, mouseY, dismissLeft, dismissRight)) {
            dismissWorkshopLine();
            return true;
        }
        int slot = button == 0 ? librarySlotAt(mouseX, mouseY) : -1;
        if (slot >= 0) {
            String trackId = library.get(filteredTrackIndices.get(slot));
            if (removeRect(cardX(slot), cardY(slot)).contains(mouseX, mouseY)) {
                // Only the list changes: the map's playlist rows and the song on the deck stay where they are.
                send(ConfigureDjPanelPayload.Action.LIBRARY_REMOVE, BlockPos.ZERO, trackId, false, 0);
                library.remove(trackId);
                libraryRevision++;
                if (trackId.equals(selectedTrackId()) && !trackId.equals(loadedTrackId)) {
                    // The server moves the selection the same way; doing it here keeps Play from aiming at the card
                    // that just went until its answer arrives.
                    trackIndex = fallbackTrackIndex();
                }
                rebuildTrackFilter();
                updateControls();
                return true;
            }
            int index = findTrack(trackId);
            if (index >= 0) {
                // Commit a drag on the currently loaded track before the card
                // selection changes the timeline into next-track cue mode.
                flushSeek();
                trackIndex = index;
                send(ConfigureDjPanelPayload.Action.SELECT_TRACK, BlockPos.ZERO, selectedTrackId(), false, 0);
                updateControls();
            }
            return true;
        }
        if (outsideMixerView(mouseX, mouseY)) {
            // A mixer row scrolled under the title or past the bottom is not there to press: only the rest is.
            for (net.minecraft.client.gui.components.events.GuiEventListener child : children()) {
                if (!mixerWidgets.containsKey(child) && child.mouseClicked(mouseX, mouseY, button)) {
                    setFocused(child);
                    if (button == 0) {
                        setDragging(true);
                    }
                    return true;
                }
            }
            return false;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        Rect column = layout().libraryColumn();
        if (libraryShown() && mouseX < column.right() + 8 && mouseY >= trackTop()) {
            trackScroll = Math.max(0, trackScroll - (int) Math.round(scrollY * 36.0D));
            return true;
        }
        PanelLayout layout = layout();
        if (mixerShown() && layout.mixerScrollMax() > 0 && mouseX >= layout.mixerColumn().x() - 8
                && mouseY >= layout.mixerViewTop() && mouseY < layout.mixerViewBottom()) {
            // A notch of the wheel moves the mixer by one of its rows.
            scrollMixer(mixerScroll - (int) Math.round(scrollY * layout.mixerStep()));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        flushAllPending();
        super.onClose();
    }

    @Override
    public void removed() {
        super.removed();
        DjCameraPreviewRenderer.release();
        for (ResourceLocation texture : previewTextures.values()) {
            Minecraft.getInstance().getTextureManager().release(texture);
        }
        previewTextures.clear();
        requestedPreviewIds.clear();
    }

    private void toggleSpeakerMode() {
        // Commit with the mode in which the drag happened. Otherwise switching
        // to "all" during the debounce window would unexpectedly fan a
        // single-speaker edit out to every connected speaker.
        flushSpeakerUpdate();
        applyToAll = !applyToAll;
        send(ConfigureDjPanelPayload.Action.SET_EDIT_ALL, BlockPos.ZERO, "", applyToAll, 0);
        speakerModeButton.setMessage(speakerModeLabel());
        updateControls();
    }

    private void cycleCamera(int delta) {
        if (cameras.isEmpty()) {
            return;
        }
        selectCameraFromGallery(Math.floorMod(Math.max(0, cameraIndex) + delta, cameras.size()));
    }

    private void openCameraGallery() {
        if (minecraft == null) {
            return;
        }
        flushAllPending();
        minecraft.setScreen(new DjCameraGalleryScreen(this, panelPos, List.copyOf(cameras), cameraIndex));
    }

    void selectCameraFromGallery(int index) {
        if (index < 0 || index >= cameras.size()) {
            return;
        }
        cameraIndex = index;
        OpenDjPanelPayload.CameraEntry camera = selectedCamera();
        cameraSnapshot = cameraSnapshot(camera, -1L);
        acceptedCameraServerTime = Long.MIN_VALUE;
        cameraPreviewRequestCooldown = 0;
        if (cameraButton != null) {
            cameraButton.setMessage(cameraLabel());
        }
        if (camera != null) {
            send(ConfigureDjPanelPayload.Action.SELECT_CAMERA, camera.pos(), "", false, 0);
        }
        updateControls();
    }

    void unlinkCameraFromGallery(int index) {
        if (index < 0 || index >= cameras.size()) {
            return;
        }
        cameraIndex = index;
        unlinkCamera();
    }

    void refreshAfterCameraEditor() {
        send(ConfigureDjPanelPayload.Action.REFRESH, BlockPos.ZERO, "", false, 0);
    }

    private void unlinkCamera() {
        OpenDjPanelPayload.CameraEntry camera = selectedCamera();
        if (camera == null) {
            return;
        }
        send(ConfigureDjPanelPayload.Action.UNLINK_CAMERA, camera.pos(), "", false, 0);
    }

    private void cycleSpeaker(int delta) {
        if (speakers.isEmpty() || applyToAll) return;
        flushSpeakerUpdate();
        speakerIndex = Math.floorMod(speakerIndex + delta, speakers.size());
        loadSpeakerSettings();
        refreshSpeakerSliders();
        speakerButton.setMessage(speakerLabel());
        send(ConfigureDjPanelPayload.Action.SELECT_SPEAKER, speakers.get(speakerIndex).pos(), "", false, 0);
    }

    private void loadSpeakerSettings() {
        if (speakers.isEmpty()) {
            radius = 14.0F;
            volume = 1.0F;
            lowGainDb = midGainDb = highGainDb = 0.0F;
            speakerIndex = 0;
            return;
        }
        speakerIndex = Mth.clamp(speakerIndex, 0, speakers.size() - 1);
        OpenDjPanelPayload.SpeakerEntry speaker = speakers.get(speakerIndex);
        radius = speaker.radius();
        volume = speaker.volume();
        lowGainDb = speaker.lowGainDb();
        midGainDb = speaker.midGainDb();
        highGainDb = speaker.highGainDb();
    }

    private void queueSpeakerUpdate() {
        if (speakers.isEmpty()) return;
        speakerUpdatePending = true;
        if (speakerUpdateCooldown <= 0) speakerUpdateCooldown = 1;
    }

    private void flushSpeakerUpdate() {
        if (!speakerUpdatePending || speakers.isEmpty()) return;
        speakerUpdatePending = false;
        speakerUpdateCooldown = 1;
        preserveLocalSpeakerTicks = 10;
        send(ConfigureDjPanelPayload.Action.LIVE_SPEAKER,
                applyToAll ? BlockPos.ZERO : speakers.get(speakerIndex).pos(), "", applyToAll, 0);
        if (applyToAll) {
            for (int index = 0; index < speakers.size(); index++) replaceSpeaker(index);
        } else {
            replaceSpeaker(speakerIndex);
        }
    }

    private void replaceSpeaker(int index) {
        OpenDjPanelPayload.SpeakerEntry old = speakers.get(index);
        speakers.set(index, new OpenDjPanelPayload.SpeakerEntry(old.pos(), radius, volume,
                lowGainDb, midGainDb, highGainDb));
    }

    private void queuePanelFxUpdate() {
        panelFxUpdatePending = true;
        if (panelFxUpdateCooldown <= 0) panelFxUpdateCooldown = 1;
    }

    private void flushPanelFxUpdate() {
        if (!panelFxUpdatePending) return;
        panelFxUpdatePending = false;
        panelFxUpdateCooldown = 1;
        preserveLocalPanelFxTicks = 10;
        send(ConfigureDjPanelPayload.Action.LIVE_PANEL_FX, BlockPos.ZERO, "", false, 0);
    }

    private void queueSeek(int ticks) {
        String selected = selectedTrackId();
        if (selected.isBlank()) return;
        int target = Mth.clamp(ticks, 0, Math.max(0, selectedTimelineDuration()));
        // Remember source time even for an optimistically loaded track. During
        // asynchronous preparation loadedTrackId already points at B, and a
        // second PLAY must use the newest dragged cue rather than its old value.
        int sourceTicks = Math.max(0, Math.round(target * playbackSpeed));
        nextTrackCueSourceTicks.put(selected, sourceTicks);
        pendingSeekTrackId = selected;
        pendingSeekTicks = target;
        seekDebounceTicks = 5;
    }

    private void flushSeek() {
        if (pendingSeekTicks < 0) return;
        int target = pendingSeekTicks;
        String targetTrackId = pendingSeekTrackId.isBlank() ? loadedTrackId : pendingSeekTrackId;
        pendingSeekTicks = -1;
        pendingSeekTrackId = "";
        if (targetTrackId.equals(loadedTrackId)) {
            playbackPositionTicks = target;
            playbackStateNanos = System.nanoTime();
        }
        send(ConfigureDjPanelPayload.Action.SEEK, BlockPos.ZERO, targetTrackId, false, target);
    }

    private void playResume() {
        if (selectedTrack() == null || speakers.isEmpty()) return;
        flushAllPending();
        String selected = selectedTrackId();
        // For the already-active track the server resumes its authoritative
        // position. For an optimistically loaded next track this retains its cue
        // even when PLAY is pressed twice before preparation completes.
        int startTicks = selectedCuePosition(selected);
        send(ConfigureDjPanelPayload.Action.PLAY_RESUME, BlockPos.ZERO, selected, false, startTicks);
        if (!selected.equals(loadedTrackId)) playbackPositionTicks = startTicks;
        loadedTrackId = selected;
        playbackDurationTicks = scaledDuration(selectedTrack().durationTicks(), playbackSpeed);
        playing = true;
        paused = false;
        playbackStateNanos = System.nanoTime();
        updateControls();
    }

    private void pausePlayback() {
        flushAllPending();
        send(ConfigureDjPanelPayload.Action.PAUSE, BlockPos.ZERO, "", false, 0);
        playbackPositionTicks = currentPlaybackPosition();
        playing = false;
        paused = !loadedTrackId.isBlank();
        playbackStateNanos = System.nanoTime();
        updateControls();
    }

    private void restart() {
        if (selectedTrack() == null || speakers.isEmpty()) return;
        flushAllPending();
        String selected = selectedTrackId();
        send(ConfigureDjPanelPayload.Action.RESTART, BlockPos.ZERO, selected, false, 0);
        loadedTrackId = selected;
        playbackDurationTicks = scaledDuration(selectedTrack().durationTicks(), playbackSpeed);
        playbackPositionTicks = 0;
        playing = true;
        paused = false;
        playbackStateNanos = System.nanoTime();
        updateControls();
    }

    /** Off, this track, the whole queue. The same three the playlist screen cycles, and the same packet. */
    private void cycleRepeat() {
        playlistRepeat = (playlistRepeat + 1) % 3;
        if (repeatButton != null) {
            repeatButton.setMessage(repeatLabel());
        }
        PacketDistributor.sendToServer(new ru.gora.musicbattle.network.ConfigureDjPlaylistPayload(panelPos,
                ru.gora.musicbattle.network.ConfigureDjPlaylistPayload.Action.SET_REPEAT, null, "", 0, -1,
                playlistAutoAdvance, playlistRepeat));
    }

    private Component repeatLabel() {
        return Component.translatable("screen.music_battle.dj_playlist.repeat_"
                + new String[]{"off", "one", "all"}[Mth.clamp(playlistRepeat, 0, 2)]);
    }

    private void cycleSpeed() {
        flushAllPending();
        float previousSpeed = playbackSpeed;
        float nextSpeed = previousSpeed < 1.25F ? 1.5F : previousSpeed < 1.75F ? 2.0F : 1.0F;
        int sourcePosition = Math.max(0, Math.round(currentPlaybackPosition() * previousSpeed));
        playbackSpeed = nextSpeed;
        playbackPositionTicks = Math.max(0, Math.round(sourcePosition / playbackSpeed));
        playbackDurationTicks = loadedTrackSourceDuration() <= 0
                ? 0 : scaledDuration(loadedTrackSourceDuration(), playbackSpeed);
        playbackPositionTicks = Mth.clamp(playbackPositionTicks, 0, Math.max(0, currentPlaybackDuration()));
        playbackStateNanos = System.nanoTime();
        speedRequestSequence = speedRequestSequence == Long.MAX_VALUE ? 1L : speedRequestSequence + 1L;
        pendingSpeedRequestRevision = speedRequestSequence;
        send(ConfigureDjPanelPayload.Action.SET_SPEED, BlockPos.ZERO, "", false, 0);
        if (speedButton != null) {
            speedButton.setMessage(speedLabel());
        }
        refreshWidgetsFromState();
    }

    private void unlinkSpeaker() {
        if (speakers.isEmpty() || applyToAll) return;
        flushSpeakerUpdate();
        send(ConfigureDjPanelPayload.Action.UNLINK_SPEAKER, speakers.get(speakerIndex).pos(), "", false, 0);
    }

    private void refresh() {
        flushAllPending();
        send(ConfigureDjPanelPayload.Action.REFRESH, BlockPos.ZERO, "", false, 0);
    }

    private void openVisualFx() {
        flushAllPending();
        DjPlaylistEntry entry = activePlaylistEntry();
        if (entry != null && entry.fxMode() == DjPlaylistEntry.FxMode.DJ && !entry.usesLegacyDjEditor()) {
            int bpm = tracks.stream().filter(track -> track.id().equals(entry.trackId()))
                    .mapToInt(TrackSummary::bpm).findFirst().orElse(120);
            minecraft.setScreen(new DjVisualFxScreen(this, entry.mixer().trackFxEnabled(),
                    entry.mixer().djFxEnabled(), entry.mixer().effects(), bpm, playbackSpeed, state -> {
                if (!entry.entryId().equals(activePlaylistEntryId)) return;
                // The edit is offered against what the server last confirmed, not against what this screen hoped
                // it had stored. The old code moved its own copy forward on every send without waiting for an
                // answer, so one refused edit made every later Apply from the same open editor refuse too - the
                // "it saves, and then it stops saving" shape of the report. The compare still protects against
                // another DJ having changed the row since the last refresh, which is what it is for.
                DjPlaylistEntry live = activePlaylistEntry();
                if (live == null || !live.entryId().equals(entry.entryId()) || live.mixer() == null) return;
                DjPlaylistMixer replacement = new DjPlaylistMixer(state.trackFxEnabled(), state.djFxEnabled(), state.effects());
                PacketDistributor.sendToServer(new EditDjPlaylistMixerPayload(panelPos, entry.entryId(), live.mixer(), replacement));
            }, entry.entryId()));
            return;
        }
        if (entry != null && entry.fxMode() != DjPlaylistEntry.FxMode.INHERIT) {
            if (entry.usesLegacyDjEditor() && minecraft.player != null) {
                minecraft.player.displayClientMessage(Component.translatable("screen.music_battle.dj_playlist.legacy_large_fx"), false);
            }
            minecraft.setScreen(new DjPlaylistEffectsScreen(this, entry));
            return;
        }
        int bpm = selectedTrack() == null ? 120 : selectedTrack().bpm();
        this.minecraft.setScreen(new DjVisualFxScreen(this,
                trackVisualFxEnabled, djVisualFxEnabled, djVisualEffects,
                bpm, playbackSpeed, this::applyVisualFx));
    }

    private void openEqualizerPresets() {
        if (minecraft == null) {
            return;
        }
        flushSpeakerUpdate();
        minecraft.setScreen(new DjPresetBrowserScreen(
                this,
                Component.translatable("screen.music_battle.dj_presets.equalizer_title"),
                Component.translatable("screen.music_battle.dj_presets.folder_hint"),
                new DjPresetBrowserScreen.PresetActions() {
                    @Override
                    public List<String> list() {
                        return ClientDjPresetStorage.listEqualizers().stream()
                                .map(ClientDjPresetStorage.EqualizerPreset::name)
                                .toList();
                    }

                    @Override
                    public void save(String name) throws IOException {
                        if (!ClientDjPresetStorage.saveEqualizer(
                                name, lowGainDb, midGainDb, highGainDb)) {
                            throw new IOException("Unable to save equalizer preset");
                        }
                    }

                    @Override
                    public void load(String name) throws IOException {
                        ClientDjPresetStorage.EqualizerPreset preset =
                                ClientDjPresetStorage.loadEqualizer(name)
                                        .orElseThrow(() -> new IOException(
                                                "Equalizer preset is unavailable"));
                        lowGainDb = preset.lowGainDb();
                        midGainDb = preset.midGainDb();
                        highGainDb = preset.highGainDb();
                        refreshSpeakerSliders();
                        queueSpeakerUpdate();
                        // A late panel refresh must not overwrite a freshly
                        // loaded preset during the normal three-tick debounce.
                        flushSpeakerUpdate();
                    }

                    @Override
                    public void delete(String name) throws IOException {
                        if (!ClientDjPresetStorage.deleteEqualizer(name)) {
                            throw new IOException("Unable to delete equalizer preset");
                        }
                    }
                },
                () -> {
                    refreshSpeakerSliders();
                    updateControls();
                }));
    }

    private void openAudioPresets() {
        if (minecraft == null || speakers.isEmpty()) {
            return;
        }
        flushAllPending();
        minecraft.setScreen(new DjPresetBrowserScreen(
                this,
                Component.translatable("screen.music_battle.dj_presets.audio_title"),
                Component.translatable("screen.music_battle.dj_presets.folder_hint"),
                new DjPresetBrowserScreen.PresetActions() {
                    @Override
                    public List<String> list() {
                        return ClientDjPresetStorage.listAudioPresets().stream()
                                .map(ClientDjPresetStorage.AudioPreset::name)
                                .toList();
                    }

                    @Override
                    public void save(String name) throws IOException {
                        if (!ClientDjPresetStorage.saveAudioPreset(name,
                                radius, volume, lowGainDb, midGainDb, highGainDb,
                                dspEnabled, reverbEnabled, reverbWet, reverbRoom, reverbDamping,
                                echoEnabled, echoWet, echoTimeMs, echoFeedback,
                                lowPassEnabled, lowPassCutoffHz)) {
                            throw new IOException("Unable to save DJ audio preset");
                        }
                    }

                    @Override
                    public void load(String name) throws IOException {
                        ClientDjPresetStorage.AudioPreset preset = ClientDjPresetStorage.loadAudioPreset(name)
                                .orElseThrow(() -> new IOException("DJ audio preset is unavailable"));
                        radius = preset.radius();
                        volume = preset.volume();
                        lowGainDb = preset.lowGainDb();
                        midGainDb = preset.midGainDb();
                        highGainDb = preset.highGainDb();
                        dspEnabled = preset.dspEnabled();
                        reverbEnabled = preset.reverbEnabled();
                        reverbWet = preset.reverbWet();
                        reverbRoom = preset.reverbRoom();
                        reverbDamping = preset.reverbDamping();
                        echoEnabled = preset.echoEnabled();
                        echoWet = preset.echoWet();
                        echoTimeMs = preset.echoTimeMs();
                        echoFeedback = preset.echoFeedback();
                        lowPassEnabled = preset.lowPassEnabled();
                        lowPassCutoffHz = preset.lowPassCutoffHz();
                        speakerUpdatePending = false;
                        panelFxUpdatePending = false;
                        preserveLocalSpeakerTicks = 10;
                        preserveLocalPanelFxTicks = 10;
                        if (applyToAll) {
                            for (int index = 0; index < speakers.size(); index++) replaceSpeaker(index);
                        } else {
                            replaceSpeaker(speakerIndex);
                        }
                        refreshWidgetsFromState();
                        send(ConfigureDjPanelPayload.Action.APPLY_AUDIO_PRESET,
                                applyToAll ? BlockPos.ZERO : speakers.get(speakerIndex).pos(),
                                "", applyToAll, 0);
                    }

                    @Override
                    public void delete(String name) throws IOException {
                        if (!ClientDjPresetStorage.deleteAudioPreset(name)) {
                            throw new IOException("Unable to delete DJ audio preset");
                        }
                    }
                },
                () -> {
                    refreshWidgetsFromState();
                    updateControls();
                }));
    }

    private void resetSpeakers() {
        if (speakers.isEmpty()) {
            return;
        }
        speakerUpdatePending = false;
        speakerUpdateCooldown = 0;
        radius = 14.0F;
        volume = 1.0F;
        lowGainDb = midGainDb = highGainDb = 0.0F;
        speakerUpdatePending = false;
        preserveLocalSpeakerTicks = 10;
        if (applyToAll) {
            for (int index = 0; index < speakers.size(); index++) replaceSpeaker(index);
        } else {
            replaceSpeaker(speakerIndex);
        }
        refreshSpeakerSliders();
        send(ConfigureDjPanelPayload.Action.RESET_SPEAKERS,
                applyToAll ? BlockPos.ZERO : speakers.get(speakerIndex).pos(), "", applyToAll, 0);
    }

    /**
     * "+": the Workshop as this booth's map picker - Browse, Installed, and the Server tab with every map the server
     * offers. The pick joins the booth's list and nothing else. It used to land in the playlist as well, which queued
     * songs the DJ had only meant to have at hand; the playlist has its own Add button for that.
     */
    private void openWorkshop() {
        if (minecraft == null) {
            return;
        }
        flushAllPending();
        ru.gora.musicbattle.client.workshop.WorkshopScreen.openDjPicker(this,
                new ru.gora.musicbattle.client.workshop.WorkshopScreen.Picker(pickerTracks(tracks),
                        this::chooseFromWorkshop, null, this::pickerPreview));
    }

    /**
     * A map the server already plays joins the list by its id at once. Anything else has to reach the server first,
     * which is the Workshop's business; the panel only hears how it goes and says so above the library.
     */
    private void chooseFromWorkshop(ru.gora.musicbattle.client.workshop.layout.BattleSetup.Pick pick) {
        if (pick == null) {
            return;
        }
        // A new pick replaces any refusal still on the line, so its Retry never resends an older map.
        workshopPick = null;
        workshopRetryable = false;
        workshopPickPanel = panelPos;
        String trackId = pick.serverTrack() ? pick.trackId()
                : pick.setId() > 0 && !pick.chartKey().isEmpty()
                ? ru.gora.musicbattle.workshop.WorkshopLayout.chartId(pick.setId(), pick.chartKey()) : "";
        if (trackId.isEmpty()) {
            return;
        }
        if (library.contains(trackId)) {
            // Already at hand: say so and show the card rather than sending a request that changes nothing.
            workshopStatus(Component.translatable("screen.music_battle.dj_panel.library_already", pick.title()),
                    false, null);
            reveal(trackId);
            revealPendingTrack();
            return;
        }
        if (library.size() >= ru.gora.musicbattle.block.entity.DjPanelBlockEntity.MAX_LIBRARY_TRACKS) {
            workshopStatus(Component.translatable("screen.music_battle.dj_panel.library_full"), true, null);
            return;
        }
        if (pick.serverTrack() || findTrack(trackId) >= 0) {
            send(ConfigureDjPanelPayload.Action.LIBRARY_ADD, BlockPos.ZERO, trackId, false, 0);
            // The card appears with the panel's answer to the add, which scrolls it into view and only then says it
            // was added; a refusal is the server's to explain.
            reveal(trackId);
            revealAnnounce = pick.title();
            return;
        }
        workshopPick = pick;
        ru.gora.musicbattle.client.workshop.WorkshopScreen.bringToDj(panelPos, pick,
                (status, problem) -> workshopStatus(status, problem, null));
    }

    /** Sends the refused pick again by the same door. */
    private void retryWorkshopPick() {
        ru.gora.musicbattle.client.workshop.layout.BattleSetup.Pick pick = workshopPick;
        dismissWorkshopLine();
        if (pick != null && panelPos.equals(workshopPickPanel)) {
            chooseFromWorkshop(pick);
        }
    }

    private static void dismissWorkshopLine() {
        workshopRetryable = false;
        workshopStatus = null;
        workshopPick = null;
    }

    /** The server's maps as the picker's Server tab lists them, the way a battle's vote list is read. */
    private static ru.gora.musicbattle.client.workshop.layout.BattleTracks pickerTracks(List<TrackSummary> summaries) {
        List<ru.gora.musicbattle.client.workshop.layout.BattleTracks.Track> rows = new ArrayList<>(summaries.size());
        for (TrackSummary track : summaries) {
            ru.gora.musicbattle.client.workshop.layout.BattleTracks.Kind kind = switch (track.source()) {
                case WORKSHOP -> ru.gora.musicbattle.client.workshop.layout.BattleTracks.Kind.WORKSHOP;
                case USER -> ru.gora.musicbattle.client.workshop.layout.BattleTracks.Kind.PLAYERS;
                case SERVER -> ru.gora.musicbattle.client.workshop.layout.BattleTracks.Kind.SERVER;
                case INSTALLED -> null;
            };
            if (kind == null) {
                continue;
            }
            String chartKey = kind == ru.gora.musicbattle.client.workshop.layout.BattleTracks.Kind.WORKSHOP
                    ? ru.gora.musicbattle.client.workshop.layout.BattleTracks.chartKeyOf(track.id(), track.setId()) : "";
            rows.add(new ru.gora.musicbattle.client.workshop.layout.BattleTracks.Track(track.id(), kind, track.setId(),
                    chartKey, track.title(), track.description(), track.publishedBy(), track.mapper(),
                    track.contentSha256(), track.bpm(), track.durationTicks(), track.previewFile()));
        }
        return new ru.gora.musicbattle.client.workshop.layout.BattleTracks(rows, false);
    }

    private ResourceLocation pickerPreview(ru.gora.musicbattle.client.workshop.layout.BattleTracks.Track row) {
        TrackSummary summary = row == null ? null : summary(row.id());
        return summary == null ? null : previewTexture(summary);
    }

    private void openPlaylist() {
        if (minecraft == null) {
            return;
        }
        flushAllPending();
        minecraft.setScreen(new DjPlaylistScreen(this));
    }

    /**
     * Applies an editor's settings to the rack itself.
     *
     * Used when the queue advances under an open editor: the row it was editing is gone, and the DJ's work belongs
     * to the rack rather than to a row that is no longer playing.
     */
    public void applyVisualFxFromEditor(DjVisualFxScreen.State state) {
        applyVisualFx(state);
    }

    private void applyVisualFx(DjVisualFxScreen.State state) {
        trackVisualFxEnabled = state.trackFxEnabled();
        djVisualFxEnabled = state.djFxEnabled();
        djVisualEffects = DjVisualEffects.sanitizeConfigs(state.effects());
        // Long enough to outlast the once-a-second refresh and the round trip it crossed.
        preserveLocalVisualFxTicks = 25;
        send(ConfigureDjPanelPayload.Action.APPLY_VISUAL_FX, BlockPos.ZERO, "", false, 0);
    }

    private void preloadSelectedTrack() {
        if (tracks.isEmpty()) {
            return;
        }
        String selected = selectedTrackId();
        if (selected.isBlank() || selected.equals(loadedTrackId)) {
            return;
        }
        send(ConfigureDjPanelPayload.Action.PRELOAD_AUDIENCE, BlockPos.ZERO, selected, false, 0);
    }

    private void flushAllPending() {
        flushSpeakerUpdate();
        flushPanelFxUpdate();
        flushSeek();
    }

    private void send(ConfigureDjPanelPayload.Action action, BlockPos targetPos,
                      String trackId, boolean all, int seekTicks) {
        long requestRevision = action == ConfigureDjPanelPayload.Action.SET_SPEED
                ? pendingSpeedRequestRevision : 0L;
        PacketDistributor.sendToServer(new ConfigureDjPanelPayload(
                panelPos, action, targetPos, radius, volume, lowGainDb, midGainDb, highGainDb,
                trackId, all, seekTicks, playbackSpeed, requestRevision,
                dspEnabled, reverbEnabled, reverbWet, reverbRoom, reverbDamping,
                echoEnabled, echoWet, echoTimeMs, echoFeedback,
                lowPassEnabled, lowPassCutoffHz,
                trackVisualFxEnabled, djVisualFxEnabled, djVisualEffects));
    }

    private void updateControls() {
        boolean hasSelection = !selectedTrackId().isBlank();
        boolean selectedVisible = searchQuery.isBlank() || selectionShown();
        boolean hasSpeakers = !speakers.isEmpty();
        boolean hasLoaded = !loadedTrackId.isBlank();
        if (playButton != null) playButton.active = hasSelection && hasSpeakers && selectedVisible;
        if (libraryPlayButton != null) {
            // One button on the library page: Pause while a song plays, else Play with the same rule as the mixer's.
            Component action = Component.translatable(playing ? "screen.music_battle.dj_panel.pause"
                    : "screen.music_battle.dj_panel.play_resume");
            libraryPlayButton.active = playing || hasSelection && hasSpeakers && selectedVisible;
            libraryPlayButton.icon(playing ? UiIcon.PAUSE : UiIcon.PLAY);
            libraryPlayButton.setMessage(action);
            libraryPlayButton.setTooltip(playing ? pauseTip : playTip);
        }
        // Restart starts the selected map from its top, so it needs what Play needs; with an empty list it did nothing.
        if (restartButton != null) restartButton.active = hasSelection && hasSpeakers && selectedVisible;
        if (pauseButton != null) pauseButton.active = playing;
        if (preloadAudienceButton != null) {
            preloadAudienceButton.active = canPreloadAudience(
                    hasSelection, selectedVisible, selectedTrackId(), loadedTrackId);
        }
        if (unlinkButton != null) unlinkButton.active = hasSpeakers && !applyToAll;
        if (speakerButton != null) speakerButton.active = hasSpeakers && !applyToAll;
        if (speakerModeButton != null) speakerModeButton.active = hasSpeakers;
        boolean hasCameras = !cameras.isEmpty();
        if (cameraButton != null) cameraButton.active = hasCameras;
        if (cameraPreviousButton != null) cameraPreviousButton.active = hasCameras && cameras.size() > 1;
        if (cameraNextButton != null) cameraNextButton.active = hasCameras && cameras.size() > 1;
        if (unlinkCameraButton != null) unlinkCameraButton.active = hasCameras;
        SettingSlider[] speakerSliders = {radiusSlider, volumeSlider, lowSlider, midSlider, highSlider};
        for (SettingSlider slider : speakerSliders) {
            if (slider != null) slider.active = hasSpeakers;
        }
        if (equalizerPresetsButton != null) equalizerPresetsButton.active = hasSpeakers;
        if (audioPresetsButton != null) audioPresetsButton.active = hasSpeakers;
        if (resetSpeakersButton != null) resetSpeakersButton.active = hasSpeakers;
        // The playlist opens even with an empty list: its queue may still hold rows to reorder, cue or remove.
        if (playlistButton != null) playlistButton.active = true;
        if (timelineSlider != null) timelineSlider.active = hasSelection || hasLoaded;
        if (speedButton != null) speedButton.active = true;
    }

    /** Whether the selected map's card is among those the search leaves on screen. */
    private boolean selectionShown() {
        String selected = selectedTrackId();
        for (int position : filteredTrackIndices) {
            if (library.get(position).equals(selected)) {
                return true;
            }
        }
        return false;
    }

    static boolean canPreloadAudience(boolean hasTracks, boolean selectedVisible,
                                      String selectedTrackId, String loadedTrackId) {
        return hasTracks && selectedVisible && selectedTrackId != null && !selectedTrackId.isBlank()
                && !selectedTrackId.equals(loadedTrackId == null ? "" : loadedTrackId);
    }

    private void refreshWidgetsFromState() {
        refreshSpeakerSliders();
        if (speakerButton != null) speakerButton.setMessage(speakerLabel());
        refreshSpeakerTooltip();
        if (speakerModeButton != null) speakerModeButton.setMessage(speakerModeLabel());
        if (cameraButton != null) cameraButton.setMessage(cameraLabel());
        if (speedButton != null) speedButton.setMessage(speedLabel());
        set(reverbWetSlider, reverbWet);
        set(reverbRoomSlider, reverbRoom);
        set(reverbDampingSlider, reverbDamping);
        set(echoWetSlider, echoWet);
        set(echoTimeSlider, echoTimeMs);
        set(echoFeedbackSlider, echoFeedback);
        set(lowPassSlider, lowPassCutoffHz);
        updateControls();
    }

    private void refreshSpeakerSliders() {
        set(radiusSlider, radius);
        set(volumeSlider, volume);
        set(lowSlider, lowGainDb);
        set(midSlider, midGainDb);
        set(highSlider, highGainDb);
    }

    private static void set(SettingSlider slider, float value) {
        if (slider != null) slider.setSetting(value);
    }

    /**
     * A switch's words alone: whether it is on is its check box, which reads the rack's state as it draws. The words used
     * to end in a check mark that Nunito does not have, drawn in Minecraft's bitmap font.
     */
    private static Component toggleLabel(String key) {
        return Component.translatable(key);
    }

    private Component speakerModeLabel() {
        return Component.translatable(applyToAll
                ? "screen.music_battle.dj_panel.edit_all"
                : "screen.music_battle.dj_panel.edit_one");
    }

    private Component speakerLabel() {
        // The button says only that there is none; how to connect one is its tooltip (refreshSpeakerTooltip).
        if (speakers.isEmpty()) return Component.translatable("screen.music_battle.dj_panel.no_speakers_short");
        if (applyToAll) return Component.translatable("screen.music_battle.dj_panel.all_speakers", speakers.size());
        OpenDjPanelPayload.SpeakerEntry speaker = speakers.get(Mth.clamp(speakerIndex, 0, speakers.size() - 1));
        return Component.translatable("screen.music_battle.dj_panel.speaker_entry",
                speakerIndex + 1, speakers.size(), speaker.pos().toShortString());
    }

    private Component cameraLabel() {
        OpenDjPanelPayload.CameraEntry camera = selectedCamera();
        if (camera == null) {
            return Component.translatable("screen.music_battle.dj_panel.no_cameras");
        }
        return Component.translatable("screen.music_battle.dj_panel.camera_entry",
                cameraIndex + 1, cameras.size(), camera.pos().toShortString());
    }

    /** What is playing, under the mixer: the state's words and the times whole, the title cut if it must be. */
    private String playbackStateText(TextStyle face, float width) {
        if (loadedTrackId.isBlank()) {
            return TextFit.ellipsize(TextStyles.measurer(),
                    Component.translatable("screen.music_battle.dj_panel.no_track_loaded").getString(), face, width);
        }
        String key = playing ? "screen.music_battle.dj_panel.playing"
                : paused ? "screen.music_battle.dj_panel.paused" : "screen.music_battle.dj_panel.stopped";
        return PanelLayout.stateLine(TextStyles.measurer(), ClientTranslations.text(key), loadedTitle(),
                formatTicks(currentPlaybackPosition()) + " / " + formatTicks(currentPlaybackDuration()), face, width);
    }

    /**
     * What is playing, in the library page's header: the song's title and where it is, nothing while no song is loaded.
     * Play or Pause beside it and its colour say whether it plays; the state's words and the length are on the mixer
     * page, and with them the line did not fit the header of a 320-unit window.
     */
    private String headerStateText(TextStyle face, float width) {
        if (loadedTrackId.isBlank()) {
            return "";
        }
        return PanelLayout.stateLine(TextStyles.measurer(), "%s", loadedTitle(), formatTicks(currentPlaybackPosition()),
                face, width);
    }

    private String loadedTitle() {
        return tracks.stream().filter(track -> track.id().equals(loadedTrackId)).map(TrackSummary::title).findFirst()
                .orElse(loadedTrackId);
    }

    private int currentPlaybackPosition() {
        long elapsed = playing ? Math.max(0L, System.nanoTime() - playbackStateNanos) : 0L;
        int ticks = playbackPositionTicks + (int) Math.min(Integer.MAX_VALUE, elapsed / 50_000_000L);
        return Mth.clamp(ticks, 0, Math.max(0, currentPlaybackDuration()));
    }

    private ClientTrackEffects.EffectState monitorEffectState(float partialTick) {
        float elapsed = monitorVisualPlaying
                ? Math.max(0L, System.nanoTime() - monitorVisualStateNanos) / 50_000_000.0F : 0.0F;
        float time = monitorVisualPositionTicks + elapsed;
        // The matching audible session supplies the same smooth clock as world FX. A DJ
        // outside the speakers' range still has all chart + DJ effects in the panel payload.
        if (monitorVisualPlaying) {
            time = ClientBattleController.djCameraVisualTick(monitorPlaybackId, time, partialTick);
        }
        // Song completion is not a paused FX clock: the release keeps advancing.
        return monitorTransitions.attach(ClientTrackEffects.sample(monitorVisualEffects, time, monitorVisualBpm,
                monitorVisualSpeed, 1.0F, monitorBeatOrigin()), System.nanoTime());
    }

    private float monitorBeatOrigin() {
        return (float) ru.gora.musicbattle.data.ChartBeat.originTicks(monitorVisualOffsetMs);
    }

    private int currentPlaybackDuration() {
        if (playbackDurationTicks > 0 && !loadedTrackId.isBlank()) return playbackDurationTicks;
        int sourceDuration = loadedTrackSourceDuration();
        return sourceDuration > 0 ? scaledDuration(sourceDuration, playbackSpeed) : 20;
    }

    private int selectedTimelineDuration() {
        if (selectedTrackId().equals(loadedTrackId)) {
            return currentPlaybackDuration();
        }
        int sourceDuration = selectedTrack() == null ? 0 : selectedTrack().durationTicks();
        return sourceDuration > 0 ? scaledDuration(sourceDuration, playbackSpeed) : 20;
    }

    private int timelineDisplayPosition() {
        String selected = selectedTrackId();
        return selected.equals(loadedTrackId) ? currentPlaybackPosition() : selectedCuePosition(selected);
    }

    private int selectedCuePosition(String trackId) {
        if (trackId == null || trackId.isBlank()) {
            return 0;
        }
        int sourceTicks = nextTrackCueSourceTicks.getOrDefault(trackId, 0);
        int runtimeTicks = Math.max(0, Math.round(sourceTicks / playbackSpeed));
        return Mth.clamp(runtimeTicks, 0, Math.max(0, selectedTimelineDuration()));
    }

    private int loadedTrackSourceDuration() {
        return tracks.stream().filter(track -> track.id().equals(loadedTrackId))
                .mapToInt(TrackSummary::durationTicks).findFirst().orElse(0);
    }

    /** The map the transport works on, or null when nothing is selected. */
    private TrackSummary selectedTrack() {
        return trackIndex >= 0 && trackIndex < tracks.size() ? tracks.get(trackIndex) : null;
    }

    private String selectedTrackId() {
        TrackSummary selected = selectedTrack();
        return selected == null ? "" : selected.id();
    }

    private OpenDjPanelPayload.CameraEntry selectedCamera() {
        return cameraIndex >= 0 && cameraIndex < cameras.size() ? cameras.get(cameraIndex) : null;
    }

    private BlockPos selectedCameraPos() {
        OpenDjPanelPayload.CameraEntry camera = selectedCamera();
        return camera == null ? null : camera.pos();
    }

    private static DjCameraPreviewRenderer.Snapshot cameraSnapshot(OpenDjPanelPayload.CameraEntry camera,
                                                                    long serverGameTime) {
        if (camera == null) {
            return null;
        }
        return new DjCameraPreviewRenderer.Snapshot(camera.available(), serverGameTime,
                new Vec3(camera.x(), camera.y(), camera.z()),
                Mth.wrapDegrees(camera.yaw()), Mth.clamp(camera.pitch(), -89.0F, 89.0F),
                Mth.clamp(camera.fov(), 30.0F, 110.0F));
    }

    private int findTrack(String trackId) {
        for (int index = 0; index < tracks.size(); index++) {
            if (tracks.get(index).id().equals(trackId)) return index;
        }
        return -1;
    }

    /** The positions on the booth's list whose title matches the search; a map the server lacks is matched by id. */
    private void rebuildTrackFilter() {
        filteredTrackIndices.clear();
        String query = searchQuery == null ? "" : searchQuery.strip().toLowerCase(Locale.ROOT);
        for (int position = 0; position < library.size(); position++) {
            TrackSummary track = summary(library.get(position));
            String title = track == null ? library.get(position) : track.title();
            if (query.isEmpty() || (title != null && title.toLowerCase(Locale.ROOT).contains(query))) {
                filteredTrackIndices.add(position);
            }
        }
    }

    private Component speedLabel() {
        String shown = playbackSpeed >= 1.9F ? "2" : playbackSpeed >= 1.4F ? "1.5" : "1";
        return Component.translatable("screen.music_battle.dj_panel.speed", shown);
    }

    private int trackTop() {
        return layout().trackTop();
    }

    ResourceLocation previewTexture(TrackSummary track) {
        if (track.previewFile().isBlank()) return null;
        ResourceLocation cached = previewTextures.get(track.id());
        if (cached != null) return cached;
        return ClientTrackAssets.cachedAsset(track.id(), track.previewFile())
                .map(path -> loadTexture(track.id(), path)).orElse(null);
    }

    private ResourceLocation loadTexture(String trackId, Path path) {
        try (InputStream input = Files.newInputStream(path)) {
            NativeImage image = NativeImage.read(input);
            String safeId = trackId.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "_");
            ResourceLocation location = MusicBattleMod.id("dj_preview/" + safeId);
            Minecraft.getInstance().getTextureManager().register(location, new DynamicTexture(image));
            previewTextures.put(trackId, location);
            return location;
        } catch (IOException exception) {
            return null;
        }
    }

    void requestVisiblePreviews(List<String> visibleIds) {
        if (previewRequestCooldown > 0) return;
        List<String> missing = new ArrayList<>();
        for (String id : visibleIds) {
            if (missing.size() >= 8 || requestedPreviewIds.contains(id)) continue;
            TrackSummary track = tracks.stream().filter(candidate -> candidate.id().equals(id)).findFirst().orElse(null);
            if (track == null || track.previewFile().isBlank()
                    || ClientTrackAssets.cachedAsset(track.id(), track.previewFile()).isPresent()
                    || ClientTrackAssets.isTransferActive(track.id(), "preview", track.previewFile())) {
                requestedPreviewIds.add(id);
                continue;
            }
            requestedPreviewIds.add(id);
            missing.add(id);
        }
        if (!missing.isEmpty()) {
            PacketDistributor.sendToServer(new DjTrackPreviewRequestPayload(panelPos, List.copyOf(missing)));
            previewRequestCooldown = 3;
        }
    }

    private static String formatTicks(int ticks) {
        return PanelLayout.formatTicks(ticks);
    }

    private static float normalizeSpeed(float speed) {
        if (!Float.isFinite(speed)) return 1.0F;
        if (Math.abs(speed - 2.0F) < 0.25F) return 2.0F;
        if (Math.abs(speed - 1.5F) < 0.25F) return 1.5F;
        return 1.0F;
    }

    private static int scaledDuration(int sourceTicks, float speed) {
        return Math.max(0, Math.round(Math.max(0, sourceTicks) / normalizeSpeed(speed)));
    }

    private interface Setter { void set(float value); }

    private static final class SettingSlider extends AbstractSliderButton {
        @Override public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            // Drawn by the toolkit, like every other slider in the mod; what the value means stays this screen's.
            ru.gora.musicbattle.client.ui.Slider.paint(graphics,
                    ru.gora.musicbattle.client.workshop.layout.Rect.of(getX(), getY(), getWidth(), getHeight()),
                    value, getMessage(), isHoveredOrFocused(), active);
        }

        private final String translationKey;
        private float minimum;
        private float maximum;
        private final Setter setter;
        private final Unit unit;
        private final boolean commitOnRelease;
        private boolean editing;
        private boolean releasePending;

        private SettingSlider(Rect rect, String translationKey, float current, float minimum, float maximum,
                              Setter setter, Unit unit, boolean commitOnRelease) {
            this(Math.round(rect.x()), Math.round(rect.y()), Math.round(rect.w()), Math.round(rect.h()), translationKey,
                    current, minimum, maximum, setter, unit, commitOnRelease);
        }

        private SettingSlider(int x, int y, int width, int height, String translationKey, float current,
                              float minimum, float maximum, Setter setter, Unit unit,
                              boolean commitOnRelease) {
            super(x, y, width, height, Component.empty(),
                    Mth.clamp((current - minimum) / Math.max(0.0001F, maximum - minimum), 0.0F, 1.0F));
            this.translationKey = translationKey;
            this.minimum = minimum;
            this.maximum = maximum;
            this.setter = setter;
            this.unit = unit;
            this.commitOnRelease = commitOnRelease;
            updateMessage();
        }

        private boolean isEditing() {
            return editing;
        }

        private float setting() {
            return Mth.clamp((float) (minimum + value * (maximum - minimum)), minimum, maximum);
        }

        private void setSetting(float setting) {
            value = Mth.clamp((setting - minimum) / Math.max(0.0001F, maximum - minimum), 0.0F, 1.0F);
            updateMessage();
        }

        private void setRange(float minimum, float maximum) {
            float current = setting();
            this.minimum = minimum;
            this.maximum = Math.max(minimum + 0.0001F, maximum);
            setSetting(current);
        }

        @Override
        protected void updateMessage() {
            // The range and the EQ gains are written as the speaker dialog writes them (a comma in Russian), so one
            // speaker setting does not read "+1,5 дБ" in one place and "+1.5 дБ" in the other.
            String shown = switch (unit) {
                case PERCENT -> String.format(Locale.ROOT, "%.0f", setting() * 100.0F);
                case DECIBELS -> SpeakerConfigScreen.decimal(setting(), true, SpeakerConfigScreen.playerLocale());
                case NUMBER -> SpeakerConfigScreen.decimal(setting(), false, SpeakerConfigScreen.playerLocale());
                case MILLISECONDS -> String.format(Locale.ROOT, "%.0f", setting());
                case HERTZ -> String.format(Locale.ROOT, "%.0f", setting());
                case TIME -> formatTicks(Math.round(setting()));
            };
            setMessage(Component.translatable(translationKey, shown));
        }

        @Override
        protected void applyValue() {
            if (commitOnRelease) {
                releasePending = true;
            } else {
                setter.set(setting());
            }
        }

        @Override
        public void onClick(double mouseX, double mouseY) {
            editing = commitOnRelease;
            super.onClick(mouseX, mouseY);
        }

        @Override
        public void onRelease(double mouseX, double mouseY) {
            if (commitOnRelease && releasePending) {
                setter.set(setting());
                releasePending = false;
            }
            editing = false;
            super.onRelease(mouseX, mouseY);
        }

        @Override
        public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
            boolean handled = super.keyPressed(keyCode, scanCode, modifiers);
            if (handled && commitOnRelease && releasePending) {
                setter.set(setting());
                releasePending = false;
            }
            return handled;
        }
    }

    enum Unit { NUMBER, PERCENT, DECIBELS, MILLISECONDS, HERTZ, TIME }

    /**
     * A button of this panel, drawn the way FancyButton draws the mod's buttons: the toolkit's rounded surface and the label
     * in Nunito, in the face FancyButton picks for the height (body from 18 units, caption below). It differs in two ways.
     * It never cuts its label, because {@link PanelLayout} has already found the label its whole width. And it can show
     * what a compact or a switch needs: one of the toolkit's icons in place of the words, which then stay its narration
     * and its tooltip, or the Workshop's check box in front of the words, ticked while the switch is on. The old buttons
     * spelled these with symbols Nunito does not have (a check mark, ↺, ♫), and Minecraft drew those in its bitmap font.
     */
    private static final class PanelButton extends Button {
        /** Final but for the library page's Play, which turns into Pause while a song plays. */
        private UiIcon icon;
        /** Whether the icon is struck through: a disconnect ({@link DjPanelScreen#struck}). */
        private final boolean struck;
        private final String glyph;
        private final java.util.function.BooleanSupplier on;

        private PanelButton(Component message, OnPress onPress, Rect rect, UiIcon icon, boolean struck, String glyph,
                            java.util.function.BooleanSupplier on) {
            super(Math.round(rect.x()), Math.round(rect.y()), Math.round(rect.w()), Math.round(rect.h()), message, onPress,
                    DEFAULT_NARRATION);
            this.icon = icon;
            this.struck = struck;
            this.glyph = glyph;
            this.on = on;
        }

        private void icon(UiIcon icon) {
            this.icon = icon;
        }

        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            Rect rect = Rect.of(getX(), getY(), getWidth(), getHeight());
            boolean hovered = isHoveredOrFocused();
            if (hovered && active) {
                ru.gora.musicbattle.client.ui.HandCursor.request(ru.gora.musicbattle.client.ui.HandCursor.Kind.HAND);
            }
            WorkshopDraw.button(graphics, rect, WorkshopDraw.ButtonStyle.SECONDARY, hovered, active);
            int colour = !active ? Palette.TEXT_MUTE : hovered ? Palette.TEXT_1 : Palette.TEXT_2;
            if (icon != null) {
                float size = Math.max(6.0F, Math.min(10.0F, getHeight() - 6.0F));
                Rect box = new Rect(rect.centerX() - size / 2.0F, rect.centerY() - size / 2.0F, size, size);
                WorkshopDraw.icon(graphics, box, icon, colour);
                if (struck) {
                    // The gap is the button's own surface, as WorkshopDraw.button fills it; a disabled button's surface
                    // is see-through, so there the slash goes over the dimmed link without a gap.
                    strike(graphics, box, !active ? 0 : hovered ? Palette.BG_4 : Palette.BG_3, colour);
                }
                return;
            }
            TextStyle face = PanelLayout.face(getHeight());
            String text = glyph != null ? glyph : getMessage().getString();
            float mark = on == null ? 0.0F : PanelLayout.CHECK + PanelLayout.CHECK_GAP;
            float room = getWidth() - PanelLayout.BUTTON_PAD - mark;
            // The layout measured this label and gave it its whole width; cutting it is only a guard for a language or a
            // font the layout was never run with.
            String shown = TextFit.width(TextStyles.measurer(), text, face) <= room ? text
                    : TextFit.ellipsize(TextStyles.measurer(), text, face, room);
            float left = rect.centerX() - (mark + TextFit.width(TextStyles.measurer(), shown, face)) / 2.0F;
            if (on != null) {
                WorkshopDraw.check(graphics, new Rect(left, rect.centerY() - PanelLayout.CHECK / 2.0F, PanelLayout.CHECK,
                        PanelLayout.CHECK), on.getAsBoolean(), false, active);
            }
            TextStyles.queue(graphics, TextStyles.text(shown, face).getVisualOrderText(), left + mark,
                    TextStyles.centeredCapY(face, rect.y(), rect.h()), colour);
        }

        /**
         * Lucide's "off" slash across an icon, as on EYE_OFF: from (2, 2) to (22, 22) of the icon's 24-unit grid with the
         * grid's stroke of 2 and round ends, over a wider band of {@code surface} that cuts the shape under it (none when
         * {@code surface} is 0). Icons and shapes are separate batches, so the icon is flushed out first and the slash
         * lands on top of it.
         *
         * <p>Without the shader (it failed to load, or Simple UI is on) the toolkit's shapes are vanilla's square fills,
         * which round every edge to a whole unit. Lucide's stroke is under one unit on these icons and rounded to nothing:
         * a disabled disconnect showed the whole link, the glyph for connecting, and an enabled one a cut link with no
         * slash. There the slash is one unit thick with one unit of the surface either side, on whole units that the
         * rounding keeps as they are.
         */
        private static void strike(GuiGraphics graphics, Rect icon, int surface, int colour) {
            graphics.flush();
            boolean smooth = UiSdf.available();
            float stroke = smooth ? icon.w() * 2.0F / 24.0F : 1.0F;
            float cut = smooth ? stroke * 2.7F : stroke + 2.0F;
            float length = icon.w() * 20.0F / 24.0F * (float) Math.sqrt(2.0) + stroke;
            // Across the slash, its middle: none for the shader, which draws any width where it is asked to. The fallback
            // draws the slash from 0 to 1 and the gap from -1 to 2, so the rounding keeps both, and then moves them back
            // half a unit, so the slash still runs through the icon's centre.
            float middle = smooth ? 0.0F : 0.5F;
            graphics.pose().pushPose();
            graphics.pose().translate(icon.centerX(), icon.centerY(), 0.0F);
            graphics.pose().mulPose(com.mojang.math.Axis.ZP.rotationDegrees(45.0F));
            graphics.pose().translate(0.0F, -middle, 0.0F);
            if (surface != 0) {
                RoundedRect.pill(graphics, -length / 2.0F, middle - cut / 2.0F, length, cut, surface);
            }
            RoundedRect.pill(graphics, -length / 2.0F, middle - stroke / 2.0F, length, stroke, colour);
            graphics.pose().popPose();
        }
    }

    /**
     * One of the two tabs of a narrow panel, drawn as the settings screen draws its tabs: the open one raised with the
     * accent rule under it, the other quiet until pointed at, each label in the face the layout measured it in. The
     * keyboard's focus shows on either: the other tab lights as under the pointer, and the open one, which a pointer never
     * changes, takes the accent outline a focused field has (FancyEditBox).
     */
    private static final class PageTab extends Button {
        private final boolean selected;
        private final TextStyle face;
        private final float ruleY;

        private PageTab(Component message, OnPress onPress, Rect rect, boolean selected, TextStyle face, float ruleY) {
            super(Math.round(rect.x()), Math.round(rect.y()), Math.round(rect.w()), Math.round(rect.h()), message, onPress,
                    DEFAULT_NARRATION);
            this.selected = selected;
            this.face = face;
            this.ruleY = ruleY;
        }

        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            Rect rect = Rect.of(getX(), getY(), getWidth(), getHeight());
            boolean hovered = !selected && isHoveredOrFocused();
            if (hovered) {
                ru.gora.musicbattle.client.ui.HandCursor.request(ru.gora.musicbattle.client.ui.HandCursor.Kind.HAND);
            }
            if (selected) {
                RoundedRect.fill(graphics, rect.x(), rect.y(), rect.w(), rect.h(), Palette.RADIUS_SM, Palette.BG_3);
                RoundedRect.fill(graphics, rect.x() + 3, ruleY, rect.w() - 6, 1.5F, 0.75F, Palette.ACCENT);
                if (isFocused()) {
                    WorkshopDraw.outline(graphics, rect, Palette.RADIUS_SM, 1.0F, Palette.ACCENT);
                }
            } else {
                WorkshopDraw.button(graphics, rect, WorkshopDraw.ButtonStyle.QUIET, hovered, true);
                if (hovered) {
                    WorkshopDraw.outline(graphics, rect, Palette.RADIUS_SM, 1.0F, Palette.fade(Palette.ACCENT, 0.5F));
                }
            }
            TextStyles.queueCentered(graphics, TextStyles.text(getMessage().getString(), face).getVisualOrderText(),
                    rect.centerX(), TextStyles.centeredCapY(face, rect.y(), rect.h()),
                    selected || hovered ? Palette.TEXT_1 : Palette.TEXT_2);
        }
    }

    /** The panel's words as the language has them: the game's language in play, a language file in the check. */
    record Words(java.util.function.UnaryOperator<String> templates, Locale locale) {
        static Words game() {
            return new Words(ClientTranslations::text, SpeakerConfigScreen.playerLocale());
        }

        String get(String key) {
            return templates.apply(key);
        }

        String format(String key, String... arguments) {
            return substitute(get(key), arguments);
        }

        /**
         * Minecraft's substitution for a language template, as TranslatableContents does it: {@code %s} takes the next
         * argument, {@code %2$s} the second whatever the order, and {@code %%} is a percent sign; anything else stays as
         * written. A language pack may number its placeholders to put the words in its own order; the panel and the preset
         * browser used to substitute {@code %s} alone and printed such a placeholder as it stood.
         */
        static String substitute(String template, String... arguments) {
            StringBuilder out = new StringBuilder(template.length() + 16);
            int next = 0;
            for (int index = 0; index < template.length(); index++) {
                char letter = template.charAt(index);
                if (letter == '%' && index + 1 < template.length()) {
                    char kind = template.charAt(index + 1);
                    if (kind == '%' || kind == 's') {
                        out.append(kind == '%' ? "%" : next < arguments.length ? arguments[next] : "");
                        next += kind == 's' ? 1 : 0;
                        index++;
                        continue;
                    }
                    int digits = index + 1;
                    while (digits < template.length() && digits - index <= 2 && Character.isDigit(template.charAt(digits))) {
                        digits++;
                    }
                    if (digits > index + 1 && digits + 1 < template.length() && template.charAt(digits) == '$'
                            && template.charAt(digits + 1) == 's') {
                        int position = Integer.parseInt(template.substring(index + 1, digits)) - 1;
                        out.append(position >= 0 && position < arguments.length ? arguments[position] : "");
                        index = digits + 1;
                        continue;
                    }
                }
                out.append(letter);
            }
            return out.toString();
        }
    }

    /**
     * Where the panel's parts sit, in GUI units, and how each of its words is set: the camera monitor and the booth's
     * library in a column on the left and the mixer on the right, or, on a window too small to hold both with every word
     * whole, one of the two at a time behind two tabs.
     *
     * <p>Every row of the mixer has a few arrangements, from the one that reads best to the most compact: all its words on
     * one line; its sliders at the widths their own labels need; one of the toolkit's icons for a button that also has a
     * tooltip; two lines. The first arrangement in which every label fits whole is taken. When the rows then do not fit the
     * window's height, each row takes its arrangement with the fewest lines, and when even that does not fit, the mixer's
     * column scrolls under the title: both columns stay on one screen. Only when a column is too narrow for its own words
     * does the panel turn into the two pages, where each gets the whole width (a page's mixer scrolls the same way). It
     * used to turn into the pages for the height alone, which on a 427x240 window, GUI scale 2 or 3 on a laptop, was the
     * most common case and hid the camera and the library behind a tab whenever the DJ touched the mixer. A label
     * is never cut and a word never broken: the dj-panel-layout check sets the panel out in English and Russian at every
     * window from 320x240 up and at every GUI scale, with Minecraft's own glyph metrics, and fails on the first word that
     * would not fit.
     *
     * <p>The rows used to be fixed fractions of the column - quarters, fifths - with each label cut to whatever room that
     * left: at 640x360 in Russian "Сброс коло…", "Играть /…", "Средние: +0,0…". Pure, like SettingsScreenLayout: no
     * Minecraft calls, so the check runs it without a window.
     */
    static final class PanelLayout {
        /** A button's label gets its width less ten units, as FancyButton gives it. */
        static final float BUTTON_PAD = 10.0F;
        /**
         * Slider.paint sets a label as its name on the left and its value on the right of one caption line, six units in
         * from each edge, with at least six units between the two; a label must fit that whole. Splitting at ": " is the
         * toolkit's own rule, and a label measured this way also fits a slider that centres the whole label instead.
         */
        static final float SLIDER_PAD = 12.0F;
        static final float SLIDER_NAME_GAP = 6.0F;
        /** What a measured need is rounded up past; see {@link #need}. */
        static final float SLACK = 0.05F;
        /** The check box in front of a switch's word, and the gap after it. */
        static final float CHECK = 8.0F;
        static final float CHECK_GAP = 4.0F;
        static final int MARGIN = 12;
        static final int RIGHT_MARGIN = 14;
        static final int GAP = 5;
        static final int CAMERA_GAP = 3;
        static final int ICON_BUTTON = 20;
        /** The narrowest the search field gets beside its two buttons: room for a few typed letters. */
        static final int FIELD_MIN = 56;
        static final int CARD_HEIGHT = 74;
        /**
         * A card where the library cannot show two full ones - a short window, or GUI scale 3 - drops its description line
         * and shrinks its cover, so the DJ still sees more than one map at a time.
         */
        static final int COMPACT_CARD_HEIGHT = 43;
        static final int CARD_GAP = 6;
        static final float REMOVE_SIZE = 14.0F;
        /** The library lays its cards out in two columns from this width. */
        static final int TWO_CARD_COLUMNS = 430;
        /** The line under the mixer that says what is playing: its gap above and its own height. */
        static final int STATE_ROOM = 14;
        /** The least the library page's header leaves the playing song's title beside its position. */
        static final float NOW_PLAYING_TITLE = 30.0F;
        /** The compact form of "+ Workshop": the plus alone, a glyph Nunito has. */
        static final String ADD_GLYPH = "+";
        /** The heading of the left column, over the camera row. */
        static final String MONITOR_HEADING = "screen.music_battle.dj_panel.camera_monitor";
        /** What the monitor says when there is no picture to show, one key for each reason. */
        static final List<String> MONITOR_KEYS = List.of("screen.music_battle.dj_panel.camera_link_hint",
                "screen.music_battle.dj_panel.camera_offline", "screen.music_battle.dj_panel.camera_world_unavailable",
                "screen.music_battle.dj_panel.camera_chunk_unavailable", "screen.music_battle.dj_panel.camera_shaders_active",
                "screen.music_battle.dj_panel.camera_render_failed");
        /** The library's two empty states: no map on the list at all, and none matching the search. */
        static final List<String> EMPTY_KEYS = List.of("screen.music_battle.dj_panel.library_empty",
                "screen.music_battle.dj_panel.no_search_results");
        static final String LIBRARY_HEADING = "screen.music_battle.dj_panel.track_library";
        static final String LIBRARY_HEADING_SHORT = "screen.music_battle.dj_panel.track_library_short";
        static final List<String> PAGE_KEYS = List.of("screen.music_battle.dj_panel.page_library",
                "screen.music_battle.dj_panel.page_mixer");

        enum Page { LIBRARY, MIXER }

        enum Control {
            MODE, SPEAKER_PREVIOUS, SPEAKER, SPEAKER_NEXT, UNLINK_SPEAKER, RANGE, RESET, VOLUME, LOW, MID, HIGH, EQ_PRESETS,
            DSP, REVERB, ECHO, LOW_PASS, REVERB_WET, REVERB_ROOM, REVERB_DAMPING, ECHO_WET, ECHO_TIME, ECHO_FEEDBACK, CUTOFF,
            AUDIO_PRESETS, TIMELINE, PLAY, PAUSE, RESTART, SPEED, REPEAT, VISUAL_FX, PLAYLIST, CLOSE,
            CAMERA_PREVIOUS, CAMERA, CAMERA_NEXT, UNLINK_CAMERA, SEARCH, WORKSHOP, PRELOAD,
            /** Play or Pause and Close in the header of a narrow panel's library page. */
            LIBRARY_PLAY, LIBRARY_CLOSE;

            /** Whether the control belongs to the left column (the library page), rather than to the mixer. */
            boolean library() {
                return ordinal() >= CAMERA_PREVIOUS.ordinal();
            }
        }

        /** How a control shows: its words, its words behind a check box, an icon, a short glyph, a slider, a text field. */
        enum Kind { TEXT, SWITCH, ICON, GLYPH, SLIDER, FIELD }

        record Placed(Rect rect, Kind kind) { }

        /** A slider's language key, unit and range: the screen builds its sliders from these too. */
        record SliderSpec(String key, Unit unit, float minimum, float maximum) { }

        /** A paragraph set in a box: the face it takes and its lines, none of them a broken word. */
        record Block(TextStyle face, List<String> lines) {
            float height() {
                return lines.size() * Metrics.lineHeight(face);
            }
        }

        private record Cell(Control control, Kind kind, boolean grow) { }

        /** One line of cells; {@code equal} lines give their growing cells one width when every label allows it. */
        private record Line(List<Cell> cells, boolean equal) { }

        private record Variant(List<Line> lines) { }

        /** The mixer's rows, each with its arrangements from the one that reads best to the most compact. */
        private static final List<List<Variant>> MIXER = List.of(
                group(lines(row(fixed(Control.MODE, Kind.TEXT), fixed(Control.SPEAKER_PREVIOUS, Kind.ICON),
                                grow(Control.SPEAKER, Kind.TEXT), fixed(Control.SPEAKER_NEXT, Kind.ICON),
                                fixed(Control.UNLINK_SPEAKER, Kind.ICON))),
                        lines(row(grow(Control.MODE, Kind.TEXT), fixed(Control.UNLINK_SPEAKER, Kind.ICON)),
                                row(fixed(Control.SPEAKER_PREVIOUS, Kind.ICON), grow(Control.SPEAKER, Kind.TEXT),
                                        fixed(Control.SPEAKER_NEXT, Kind.ICON)))),
                group(lines(row(grow(Control.RANGE, Kind.SLIDER), fixed(Control.RESET, Kind.TEXT))),
                        lines(row(grow(Control.RANGE, Kind.SLIDER), fixed(Control.RESET, Kind.ICON))),
                        lines(row(grow(Control.RANGE, Kind.SLIDER)), row(grow(Control.RESET, Kind.TEXT)))),
                group(lines(row(grow(Control.VOLUME, Kind.SLIDER)))),
                group(lines(row(grow(Control.LOW, Kind.SLIDER), grow(Control.MID, Kind.SLIDER), grow(Control.HIGH, Kind.SLIDER),
                                fixed(Control.EQ_PRESETS, Kind.TEXT))),
                        lines(row(grow(Control.LOW, Kind.SLIDER), grow(Control.MID, Kind.SLIDER), grow(Control.HIGH, Kind.SLIDER),
                                fixed(Control.EQ_PRESETS, Kind.ICON))),
                        lines(row(grow(Control.LOW, Kind.SLIDER), grow(Control.MID, Kind.SLIDER), grow(Control.HIGH, Kind.SLIDER)),
                                row(grow(Control.EQ_PRESETS, Kind.TEXT))),
                        lines(row(grow(Control.LOW, Kind.SLIDER), grow(Control.MID, Kind.SLIDER)),
                                row(grow(Control.HIGH, Kind.SLIDER), fixed(Control.EQ_PRESETS, Kind.TEXT)))),
                group(lines(row(grow(Control.DSP, Kind.SWITCH), grow(Control.REVERB, Kind.SWITCH), grow(Control.ECHO, Kind.SWITCH),
                                grow(Control.LOW_PASS, Kind.SWITCH))),
                        lines(row(grow(Control.DSP, Kind.SWITCH), grow(Control.REVERB, Kind.SWITCH)),
                                row(grow(Control.ECHO, Kind.SWITCH), grow(Control.LOW_PASS, Kind.SWITCH)))),
                group(lines(row(grow(Control.REVERB_WET, Kind.SLIDER), grow(Control.REVERB_ROOM, Kind.SLIDER),
                                grow(Control.REVERB_DAMPING, Kind.SLIDER))),
                        lines(row(grow(Control.REVERB_WET, Kind.SLIDER), grow(Control.REVERB_ROOM, Kind.SLIDER)),
                                row(grow(Control.REVERB_DAMPING, Kind.SLIDER)))),
                group(lines(row(grow(Control.ECHO_WET, Kind.SLIDER), grow(Control.ECHO_TIME, Kind.SLIDER),
                                grow(Control.ECHO_FEEDBACK, Kind.SLIDER))),
                        lines(row(grow(Control.ECHO_WET, Kind.SLIDER), grow(Control.ECHO_TIME, Kind.SLIDER)),
                                row(grow(Control.ECHO_FEEDBACK, Kind.SLIDER)))),
                group(lines(row(grow(Control.CUTOFF, Kind.SLIDER), fixed(Control.AUDIO_PRESETS, Kind.TEXT))),
                        lines(row(grow(Control.CUTOFF, Kind.SLIDER), fixed(Control.AUDIO_PRESETS, Kind.ICON))),
                        lines(row(grow(Control.CUTOFF, Kind.SLIDER)), row(grow(Control.AUDIO_PRESETS, Kind.TEXT)))),
                group(lines(row(grow(Control.TIMELINE, Kind.SLIDER)))),
                // Play and Pause give up their words before the transport takes a second line only when the window is
                // too short for that line: the icons are universal, and both keep their words as a tooltip.
                group(lines(row(grow(Control.PLAY, Kind.TEXT), grow(Control.PAUSE, Kind.TEXT), grow(Control.RESTART, Kind.TEXT),
                                grow(Control.SPEED, Kind.TEXT), grow(Control.REPEAT, Kind.TEXT))),
                        lines(row(grow(Control.PLAY, Kind.TEXT), grow(Control.PAUSE, Kind.TEXT), grow(Control.RESTART, Kind.TEXT)),
                                row(grow(Control.SPEED, Kind.TEXT), grow(Control.REPEAT, Kind.TEXT))),
                        lines(row(fixed(Control.PLAY, Kind.ICON), fixed(Control.PAUSE, Kind.ICON), grow(Control.RESTART, Kind.TEXT),
                                grow(Control.SPEED, Kind.TEXT), grow(Control.REPEAT, Kind.TEXT))),
                        lines(row(fixed(Control.PLAY, Kind.ICON), fixed(Control.PAUSE, Kind.ICON), grow(Control.RESTART, Kind.TEXT)),
                                row(grow(Control.SPEED, Kind.TEXT), grow(Control.REPEAT, Kind.TEXT)))),
                group(lines(row(grow(Control.VISUAL_FX, Kind.TEXT), grow(Control.PLAYLIST, Kind.TEXT), grow(Control.CLOSE, Kind.TEXT))),
                        lines(row(grow(Control.VISUAL_FX, Kind.TEXT), grow(Control.PLAYLIST, Kind.TEXT)),
                                row(grow(Control.CLOSE, Kind.TEXT)))));
        /** The camera's row: its arrows and its unlink as icons, its name taking what is left. */
        private static final List<Variant> CAMERA_ROW = group(lines(new Line(List.of(fixed(Control.CAMERA_PREVIOUS, Kind.ICON),
                grow(Control.CAMERA, Kind.TEXT), fixed(Control.CAMERA_NEXT, Kind.ICON), fixed(Control.UNLINK_CAMERA, Kind.ICON)),
                false)));
        /** The search field and the library's two buttons, which shorten to "+" and an icon before the field gets short. */
        private static final List<Variant> SEARCH_ROW = group(
                lines(row(grow(Control.SEARCH, Kind.FIELD), fixed(Control.WORKSHOP, Kind.TEXT), fixed(Control.PRELOAD, Kind.TEXT))),
                lines(row(grow(Control.SEARCH, Kind.FIELD), fixed(Control.WORKSHOP, Kind.GLYPH), fixed(Control.PRELOAD, Kind.TEXT))),
                lines(row(grow(Control.SEARCH, Kind.FIELD), fixed(Control.WORKSHOP, Kind.GLYPH), fixed(Control.PRELOAD, Kind.ICON))));

        private final int width;
        private final int height;
        private final boolean paged;
        private final int rowHeight;
        private final int divider;
        private final Rect libraryColumn;
        private final Rect mixerColumn;
        private final Map<Control, Placed> controls;
        private final float stateY;
        private final int mixerViewTop;
        private final int mixerViewBottom;
        private final int mixerStep;
        private final List<Rect> tabs;
        private final TextStyle tabFace;
        private final TextStyle tabSelectedFace;
        private final int header;
        private final TextStyle titleFace;
        private final float titleY;
        private final Rect nowPlaying;
        private final TextStyle nowPlayingFace;
        private final float headingY;
        private final Rect monitor;
        private final float libraryHeadingY;
        private final String libraryHeadingKey;
        private final int searchTop;
        private final int searchHeight;
        private final int trackTop;
        private final int trackBottom;
        private final int cardHeight;
        private final int columns;
        private final List<String> problems;

        private PanelLayout(Draft draft) {
            this.width = draft.width;
            this.height = draft.height;
            this.paged = draft.paged;
            this.rowHeight = draft.rowHeight;
            this.divider = draft.divider;
            this.libraryColumn = draft.libraryColumn;
            this.mixerColumn = draft.mixerColumn;
            this.controls = new java.util.EnumMap<>(draft.controls);
            this.stateY = draft.stateY;
            this.mixerViewTop = draft.mixerViewTop;
            this.mixerViewBottom = draft.mixerViewBottom;
            this.mixerStep = draft.mixerStep;
            this.tabs = List.copyOf(draft.tabs);
            this.tabFace = draft.tabFace;
            this.tabSelectedFace = draft.tabSelectedFace;
            this.header = draft.header;
            this.titleFace = draft.titleFace;
            this.titleY = draft.titleY;
            this.nowPlaying = draft.nowPlaying;
            this.nowPlayingFace = draft.nowPlayingFace;
            this.headingY = draft.headingY;
            this.monitor = draft.monitor;
            this.libraryHeadingY = draft.libraryHeadingY;
            this.libraryHeadingKey = draft.libraryHeadingKey;
            this.searchTop = draft.searchTop;
            this.searchHeight = draft.searchHeight;
            this.trackTop = draft.trackTop;
            this.trackBottom = draft.trackBottom;
            this.cardHeight = draft.cardHeight;
            this.columns = draft.columns;
            this.problems = List.copyOf(draft.problems);
        }

        /**
         * The panel for a window: both columns when every word fits them whole, with the mixer scrolling where the window
         * is too short for it, else the two pages. Only a width makes the pages: a height never adds a problem.
         */
        static PanelLayout compute(int width, int height, TextMeasurer measurer, Words words) {
            PanelLayout columns = build(width, height, measurer, words, false);
            return columns.problems.isEmpty() ? columns : build(width, height, measurer, words, true);
        }

        /** One of the two shapes, with every reason it does not fit; the check reads the reasons. */
        static PanelLayout build(int width, int height, TextMeasurer measurer, Words words, boolean paged) {
            Draft draft = new Draft(width, height, measurer, words, paged);
            if (!paged) {
                int left = mixerLeft(width);
                draft.divider = left - 8;
                draft.libraryColumn = new Rect(MARGIN, 0, Math.max(64, left - 30), height);
                draft.mixerColumn = new Rect(left, 0, Math.max(96, width - left - RIGHT_MARGIN), height);
                // A short window starts the mixer rows high, so its heading takes the smaller bold style above them.
                draft.titleFace = height < 280 ? TextStyle.STRONG : TextStyle.TITLE;
                draft.titleY = height < 220 ? 2 : height < 280 ? 6 : 11;
                if (TextFit.width(measurer, words.get("screen.music_battle.dj_panel.title"), draft.titleFace)
                        > width - draft.divider - 16) {
                    draft.problems.add("the title is wider than the mixer's panel");
                }
                draft.headingY = (height < 220 ? 1 : 10) + 3;
                if (TextFit.width(measurer, words.get(MONITOR_HEADING), TextStyle.LABEL) > draft.libraryColumn.w() - 4) {
                    draft.problems.add("the monitor's heading is wider than its column");
                }
                library(draft, height < 220 ? 13 : 28);
                mixer(draft, mixerTop(height));
            } else {
                tabs(draft);
                Rect page = new Rect(MARGIN, 0, Math.max(96, width - MARGIN - RIGHT_MARGIN), height);
                draft.libraryColumn = page;
                draft.mixerColumn = page;
                draft.headingY = Float.NaN;
                library(draft, draft.header + 4);
                mixer(draft, draft.header + 4);
            }
            return new PanelLayout(draft);
        }

        /** The two tabs, sized to their labels in their bold face, and the title beside them when it fits. */
        private static void tabs(Draft draft) {
            boolean small = draft.rowHeight < 18;
            draft.tabFace = small ? TextStyle.CAPTION : TextStyle.BODY;
            draft.tabSelectedFace = small ? TextStyle.LABEL : TextStyle.STRONG;
            int tabHeight = small ? 16 : 20;
            int pad = small ? 7 : 9;
            float x = MARGIN;
            for (String key : PAGE_KEYS) {
                String label = draft.words.get(key);
                int tabWidth = (int) Math.ceil(Math.max(TextFit.width(draft.measurer, label, draft.tabFace),
                        TextFit.width(draft.measurer, label, draft.tabSelectedFace))) + pad * 2;
                draft.tabs.add(new Rect(x, 4, tabWidth, tabHeight));
                x += tabWidth + 4;
            }
            draft.header = 4 + tabHeight + 4;
            float room = draft.width - RIGHT_MARGIN - (x + 8);
            String title = draft.words.get("screen.music_battle.dj_panel.title");
            draft.titleFace = TextFit.width(draft.measurer, title, TextStyle.TITLE) <= room ? TextStyle.TITLE
                    : TextFit.width(draft.measurer, title, TextStyle.STRONG) <= room ? TextStyle.STRONG : null;
            draft.titleY = capY(draft.titleFace == null ? TextStyle.STRONG : draft.titleFace, 4, tabHeight);
            if (x - 4 > draft.width - RIGHT_MARGIN) {
                draft.problems.add("the page tabs are wider than the window");
            }
            // The library page's end of the header, where the mixer page keeps the title: what is playing, Play or Pause,
            // and Close. A card picked on that page could only be played from the other page, and the page said nothing
            // of what was playing; the mixer page has all three, under its rows and in them.
            float right = draft.width - RIGHT_MARGIN;
            Rect close = new Rect(right - tabHeight, 4, tabHeight, tabHeight);
            Rect play = new Rect(close.x() - CAMERA_GAP - tabHeight, 4, tabHeight, tabHeight);
            draft.controls.put(Control.LIBRARY_CLOSE, new Placed(close, Kind.ICON));
            draft.controls.put(Control.LIBRARY_PLAY, new Placed(play, Kind.ICON));
            draft.nowPlayingFace = TextStyle.CAPTION;
            draft.nowPlaying = new Rect(x + 4, 4, Math.max(0, play.x() - 8 - (x + 4)), tabHeight);
            if (NOW_PLAYING_TITLE + TextFit.width(draft.measurer, "  " + formatTicks(99 * 1200 + 59 * 20),
                    draft.nowPlayingFace) > draft.nowPlaying.w()) {
                draft.problems.add("the library page's header has no room to say what is playing");
            }
        }

        /**
         * The line that says what is playing, in a width: the state's words and the times stay whole, and only the
         * beatmap's title inside them is cut when the line is too long. The line used to be cut at its end, which took the
         * times first.
         */
        static String stateLine(TextMeasurer measurer, String template, String title, String times, TextStyle face,
                                float width) {
            String whole = Words.substitute(template, title) + "  " + times;
            if (TextFit.width(measurer, whole, face) <= width) {
                return whole;
            }
            float frame = TextFit.width(measurer, Words.substitute(template, "") + "  " + times, face);
            String cut = TextFit.ellipsize(measurer, title, face, width - frame - 0.5F);
            String shortened = Words.substitute(template, cut) + "  " + times;
            return !cut.isEmpty() && TextFit.width(measurer, shortened, face) <= width ? shortened
                    : TextFit.ellipsize(measurer, whole, face, width);
        }

        /** TextStyles.centeredCapY without Minecraft: the y that centres a face's capitals in a box. */
        private static float capY(TextStyle face, float boxY, float boxHeight) {
            return boxY + (boxHeight - face.capHeight()) * 0.5F;
        }

        /** The left column, or the library page: the camera's row and monitor, the search row and the cards. */
        private static void library(Draft draft, int cameraY) {
            Rect column = draft.libraryColumn;
            int cameraHeight = Math.min(18, draft.rowHeight);
            if (!place(draft, CAMERA_ROW, column, cameraY, cameraHeight, CAMERA_GAP)) {
                draft.problems.add("the camera's row does not fit its column");
            }
            int monitorTop = cameraY + cameraHeight + 4;
            int monitorHeight = draft.height < 220 ? Math.min(42, Math.max(32, draft.height / 5))
                    : clamp(Math.round(column.w() * 9.0F / 16.0F), 44, draft.height < 280 ? 58 : 92);
            draft.monitor = new Rect(column.x(), monitorTop, column.w(), monitorHeight);
            for (String key : MONITOR_KEYS) {
                if (monitorBlock(draft.measurer, draft.words.get(key), draft.monitor) == null) {
                    draft.problems.add("the monitor's message " + key + " does not fit the monitor");
                }
            }
            draft.searchTop = monitorTop + monitorHeight + 13;
            draft.searchHeight = Math.min(18, Math.max(12, draft.rowHeight));
            draft.libraryHeadingY = draft.searchTop - 10;
            float headingRoom = column.w() - 2;
            draft.libraryHeadingKey = TextFit.width(draft.measurer, draft.words.get(LIBRARY_HEADING), TextStyle.LABEL) <= headingRoom
                    ? LIBRARY_HEADING : LIBRARY_HEADING_SHORT;
            if (TextFit.width(draft.measurer, draft.words.get(draft.libraryHeadingKey), TextStyle.LABEL) > headingRoom) {
                draft.problems.add("the library's heading does not fit its column");
            }
            if (!place(draft, SEARCH_ROW, column, draft.searchTop, draft.searchHeight, GAP)) {
                draft.problems.add("the search row does not fit its column");
            }
            draft.trackTop = draft.searchTop + draft.searchHeight + 4;
            draft.trackBottom = draft.height - 12;
            draft.columns = column.w() >= TWO_CARD_COLUMNS ? 2 : 1;
            draft.cardHeight = draft.trackBottom - draft.trackTop >= CARD_HEIGHT * 2 + CARD_GAP ? CARD_HEIGHT : COMPACT_CARD_HEIGHT;
            if (draft.cardHeight == CARD_HEIGHT && !cardWordsFit(draft, cardTextWidth(column.w(), draft.columns, CARD_HEIGHT))) {
                // A full card's cover leaves its lines too little room in a narrow column: the compact card, with the
                // smaller cover, keeps both columns where the full one would have turned the panel into its pages.
                draft.cardHeight = COMPACT_CARD_HEIGHT;
            }
            float textWidth = cardTextWidth(column.w(), draft.columns, draft.cardHeight);
            if (TextFit.width(draft.measurer, draft.words.get("screen.music_battle.dj_panel.builtin"), TextStyle.CAPTION) > textWidth) {
                draft.problems.add("a card's 'built-in beatmap' does not fit the card");
            }
            if (missingBlock(draft.measurer, draft.words.get("screen.music_battle.dj_panel.library_missing"), textWidth) == null) {
                draft.problems.add("a card's 'not on this server' does not fit two lines of the card");
            }
            float panelHeight = emptyPanelHeight(draft.trackTop, draft.trackBottom);
            for (String key : EMPTY_KEYS) {
                if (panelHeight >= 24 && emptyBlock(draft.measurer, draft.words.get(key), column.w(), panelHeight) == null) {
                    draft.problems.add("the empty library's " + key + " does not fit its panel");
                }
            }
        }

        /** Whether a card's own words fit its lines: "built-in beatmap", and "not on this server" in two lines. */
        private static boolean cardWordsFit(Draft draft, float textWidth) {
            return TextFit.width(draft.measurer, draft.words.get("screen.music_battle.dj_panel.builtin"), TextStyle.CAPTION)
                    <= textWidth && missingBlock(draft.measurer, draft.words.get("screen.music_battle.dj_panel.library_missing"),
                    textWidth) != null;
        }

        /**
         * The mixer: every row in its first arrangement that fits its width, the most compact ones when height runs out, and
         * when even those are taller than the window, a column that scrolls between {@code top} and the window's bottom.
         * The rows are placed where they sit with the column scrolled to its top; the screen moves them by its scroll.
         */
        private static void mixer(Draft draft, int top) {
            Rect column = draft.mixerColumn;
            int step = step(draft.height);
            int bottom = draft.height - 2;
            int[] choice = new int[MIXER.size()];
            for (int group = 0; group < MIXER.size(); group++) {
                choice[group] = -1;
                for (int variant = 0; variant < MIXER.get(group).size() && choice[group] < 0; variant++) {
                    if (fits(draft, MIXER.get(group).get(variant), column.w())) {
                        choice[group] = variant;
                    }
                }
                if (choice[group] < 0) {
                    draft.problems.add("no arrangement of the mixer's row " + (group + 1) + " fits its width");
                    choice[group] = MIXER.get(group).size() - 1;
                }
            }
            int lines = lines(choice);
            if (top + step * lines + STATE_ROOM > bottom) {
                for (int group = 0; group < MIXER.size(); group++) {
                    for (int variant = 0; variant < MIXER.get(group).size(); variant++) {
                        int fewer = MIXER.get(group).get(variant).lines().size();
                        if (fewer < MIXER.get(group).get(choice[group]).lines().size()
                                && fits(draft, MIXER.get(group).get(variant), column.w())) {
                            choice[group] = variant;
                        }
                    }
                }
                lines = lines(choice);
            }
            if (draft.paged && top + step * lines + STATE_ROOM > bottom) {
                // The page has nothing else to give up, so its rows close up to a unit apart before it scrolls.
                step = draft.rowHeight + 1;
            }
            // Rows that still do not fit scroll; the view starts a unit above the first row, under the title or the tabs.
            draft.mixerViewTop = top - 1;
            draft.mixerViewBottom = bottom;
            draft.mixerStep = step;
            int line = 0;
            for (int group = 0; group < MIXER.size(); group++) {
                for (Line row : MIXER.get(group).get(choice[group]).lines()) {
                    placeLine(draft, row, column.x(), column.w(), top + step * line, draft.rowHeight, GAP);
                    line++;
                }
            }
            draft.stateY = top + step * lines;
        }

        private static int lines(int[] choice) {
            int lines = 0;
            for (int group = 0; group < MIXER.size(); group++) {
                lines += MIXER.get(group).get(choice[group]).lines().size();
            }
            return lines;
        }

        /** Places the first arrangement of a one-line row that fits; false when none does. */
        private static boolean place(Draft draft, List<Variant> variants, Rect column, int y, int height, int gap) {
            for (Variant variant : variants) {
                if (widths(draft, variant.lines().getFirst(), column.w(), height, gap) != null) {
                    placeLine(draft, variant.lines().getFirst(), column.x(), column.w(), y, height, gap);
                    return true;
                }
            }
            placeLine(draft, variants.getLast().lines().getFirst(), column.x(), column.w(), y, height, gap);
            return false;
        }

        private static boolean fits(Draft draft, Variant variant, float width) {
            for (Line line : variant.lines()) {
                if (widths(draft, line, width, draft.rowHeight, GAP) == null) {
                    return false;
                }
            }
            return true;
        }

        private static void placeLine(Draft draft, Line line, float x, float width, int y, int height, int gap) {
            int[] widths = widths(draft, line, width, height, gap);
            if (widths == null) {
                // Nothing fits: share the width out evenly so the controls at least stay inside the column. The
                // arrangement has already been reported as a problem.
                widths = new int[line.cells().size()];
                java.util.Arrays.fill(widths, Math.max(1, ((int) width - gap * (widths.length - 1)) / widths.length));
            }
            int left = Math.round(x);
            for (int index = 0; index < widths.length; index++) {
                Cell cell = line.cells().get(index);
                draft.controls.put(cell.control(), new Placed(new Rect(left, y, widths[index], height), cell.kind()));
                left += widths[index] + gap;
            }
        }

        /**
         * Whole widths for a line's cells across {@code width}, or null when their labels need more. The room left over
         * goes to the growing cells; an equal line first tries one width for all of them, then their own widths.
         */
        private static int[] widths(Draft draft, Line line, float width, int height, int gap) {
            int count = line.cells().size();
            int[] needs = new int[count];
            int growers = 0;
            int widest = 0;
            for (int index = 0; index < count; index++) {
                Cell cell = line.cells().get(index);
                needs[index] = need(draft, cell, height);
                if (cell.grow()) {
                    growers++;
                    widest = Math.max(widest, needs[index]);
                }
            }
            int room = (int) Math.floor(width) - gap * (count - 1);
            if (line.equal() && growers > 1) {
                int[] equal = needs.clone();
                for (int index = 0; index < count; index++) {
                    if (line.cells().get(index).grow()) {
                        equal[index] = widest;
                    }
                }
                int[] spread = spread(line, equal, room, growers);
                if (spread != null) {
                    return spread;
                }
            }
            return spread(line, needs, room, growers);
        }

        private static int[] spread(Line line, int[] needs, int room, int growers) {
            int total = 0;
            for (int need : needs) {
                total += need;
            }
            if (total > room) {
                return null;
            }
            int[] widths = needs.clone();
            int share = growers == 0 ? 0 : (room - total) / growers;
            int extra = growers == 0 ? 0 : (room - total) % growers;
            int seen = 0;
            for (int index = 0; index < widths.length; index++) {
                if (line.cells().get(index).grow()) {
                    widths[index] += share + (seen++ >= growers - extra ? 1 : 0);
                }
            }
            return widths;
        }

        /**
         * The width a cell needs for every text it can show, in the face its height gives it, rounded up to a whole unit.
         * A need that comes out a whole number exactly gets one unit more: the button or the slider sums the same widths
         * in another order, and a hair less room than the text cut a label by its last letter.
         */
        private static int need(Draft draft, Cell cell, int height) {
            TextStyle face = face(height);
            float need = switch (cell.kind()) {
                case TEXT -> widest(draft.measurer, texts(cell.control(), draft.words), face) + BUTTON_PAD;
                case SWITCH -> widest(draft.measurer, texts(cell.control(), draft.words), face) + CHECK + CHECK_GAP
                        + BUTTON_PAD;
                case ICON -> ICON_BUTTON;
                case GLYPH -> Math.max(ICON_BUTTON, TextFit.width(draft.measurer, ADD_GLYPH, face) + BUTTON_PAD);
                case SLIDER -> {
                    float widest = 0.0F;
                    for (String label : sliderTexts(cell.control(), draft.words)) {
                        widest = Math.max(widest, sliderNeed(draft.measurer, label));
                    }
                    yield widest;
                }
                case FIELD -> FIELD_MIN;
            };
            return cell.kind() == Kind.ICON || cell.kind() == Kind.FIELD ? (int) need : (int) Math.ceil(need + SLACK);
        }

        /** The width a slider needs to show this label whole: its name and its value apart, inside the padding. */
        static float sliderNeed(TextMeasurer measurer, String label) {
            int split = label.lastIndexOf(": ");
            if (split <= 0) {
                return TextFit.width(measurer, label, TextStyle.CAPTION) + SLIDER_PAD;
            }
            return TextFit.width(measurer, label.substring(0, split).strip(), TextStyle.CAPTION) + SLIDER_NAME_GAP
                    + TextFit.width(measurer, label.substring(split + 2).strip(), TextStyle.CAPTION) + SLIDER_PAD;
        }

        static float widest(TextMeasurer measurer, List<String> texts, TextStyle face) {
            float widest = 0.0F;
            for (String text : texts) {
                widest = Math.max(widest, TextFit.width(measurer, text, face));
            }
            return widest;
        }

        /** Every text a button can show in the panel's language; a slider's are {@link #sliderTexts}. */
        static List<String> texts(Control control, Words words) {
            String panel = "screen.music_battle.dj_panel.";
            return switch (control) {
                case MODE -> List.of(words.get(panel + "edit_one"), words.get(panel + "edit_all"));
                // The coordinates of one speaker are data and may be cut; the words for none and for all are not.
                case SPEAKER -> List.of(words.get(panel + "no_speakers_short"), words.format(panel + "all_speakers",
                        Integer.toString(ru.gora.musicbattle.block.entity.DjPanelBlockEntity.MAX_LINKED_SPEAKERS)));
                case UNLINK_SPEAKER -> List.of(words.get(panel + "unlink_speaker"));
                case RESET -> List.of(words.get(panel + "reset_speakers"));
                case EQ_PRESETS -> List.of(words.get(panel + "eq_presets"));
                case DSP -> List.of(words.get(panel + "dsp"));
                case REVERB -> List.of(words.get(panel + "reverb"));
                case ECHO -> List.of(words.get(panel + "echo"));
                case LOW_PASS -> List.of(words.get(panel + "low_pass"));
                case AUDIO_PRESETS -> List.of(words.get(panel + "audio_presets"));
                case PLAY -> List.of(words.get(panel + "play_resume"));
                case PAUSE -> List.of(words.get(panel + "pause"));
                case RESTART -> List.of(words.get(panel + "restart"));
                case SPEED -> List.of(words.format(panel + "speed", "1"), words.format(panel + "speed", "1.5"),
                        words.format(panel + "speed", "2"));
                case REPEAT -> List.of(words.get("screen.music_battle.dj_playlist.repeat_off"),
                        words.get("screen.music_battle.dj_playlist.repeat_one"),
                        words.get("screen.music_battle.dj_playlist.repeat_all"));
                case VISUAL_FX -> List.of(words.get(panel + "visual_fx"));
                case PLAYLIST -> List.of(words.get(panel + "playlist"));
                case CLOSE -> List.of(words.get(panel + "close"));
                case CAMERA -> List.of(words.get(panel + "no_cameras"));
                case UNLINK_CAMERA -> List.of(words.get(panel + "unlink_camera"));
                case WORKSHOP -> List.of(words.get(panel + "workshop"));
                case PRELOAD -> List.of(words.get(panel + "preload_audience"));
                default -> List.of();
            };
        }

        /** A slider's key, unit and range, or null for a control that is not a slider. */
        static SliderSpec slider(Control control) {
            String panel = "screen.music_battle.dj_panel.";
            return switch (control) {
                case RANGE -> new SliderSpec(panel + "radius", Unit.NUMBER, 2.0F, 64.0F);
                case VOLUME -> new SliderSpec(panel + "volume", Unit.PERCENT, 0.0F, 2.0F);
                case LOW -> new SliderSpec(panel + "eq_low", Unit.DECIBELS, -12.0F, 12.0F);
                case MID -> new SliderSpec(panel + "eq_mid", Unit.DECIBELS, -12.0F, 12.0F);
                case HIGH -> new SliderSpec(panel + "eq_high", Unit.DECIBELS, -12.0F, 12.0F);
                case REVERB_WET -> new SliderSpec(panel + "reverb_wet", Unit.PERCENT, 0.0F, 1.0F);
                case REVERB_ROOM -> new SliderSpec(panel + "reverb_room", Unit.PERCENT, 0.0F, 1.0F);
                case REVERB_DAMPING -> new SliderSpec(panel + "reverb_damping", Unit.PERCENT, 0.0F, 1.0F);
                case ECHO_WET -> new SliderSpec(panel + "echo_wet", Unit.PERCENT, 0.0F, 0.75F);
                case ECHO_TIME -> new SliderSpec(panel + "echo_time", Unit.MILLISECONDS, 40.0F, 1000.0F);
                case ECHO_FEEDBACK -> new SliderSpec(panel + "echo_feedback", Unit.PERCENT, 0.0F, 0.85F);
                case CUTOFF -> new SliderSpec(panel + "low_pass_cutoff", Unit.HERTZ, 200.0F, 20_000.0F);
                // The timeline's range is the beatmap's length; every minute and second is two digits of one width.
                case TIMELINE -> new SliderSpec(panel + "timeline", Unit.TIME, 0.0F, 0.0F);
                default -> null;
            };
        }

        /**
         * The labels a slider shows at the ends of its range. Nunito's digits are all one width at every scale, so the
         * widest label is the one with the most digits and the plus sign, which an end of the range has.
         */
        static List<String> sliderTexts(Control control, Words words) {
            SliderSpec spec = slider(control);
            return List.of(words.format(spec.key(), value(spec.unit(), spec.minimum(), words.locale())),
                    words.format(spec.key(), value(spec.unit(), spec.maximum(), words.locale())));
        }

        /** A slider's number as SettingSlider.updateMessage writes it. */
        static String value(Unit unit, float setting, Locale locale) {
            return switch (unit) {
                case PERCENT -> String.format(Locale.ROOT, "%.0f", setting * 100.0F);
                case DECIBELS -> SpeakerConfigScreen.decimal(setting, true, locale);
                case NUMBER -> SpeakerConfigScreen.decimal(setting, false, locale);
                case MILLISECONDS, HERTZ -> String.format(Locale.ROOT, "%.0f", setting);
                case TIME -> formatTicks(Math.round(setting));
            };
        }

        static String formatTicks(int ticks) {
            int seconds = Math.max(0, ticks) / 20;
            return String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60);
        }

        /** The face buttons take at a height, as FancyButton picks it. */
        static TextStyle face(float height) {
            return height >= 18 ? TextStyle.BODY : TextStyle.CAPTION;
        }

        static int rowHeight(int height) {
            if (height < 220) return 12;
            if (height < 280) return 14;
            if (height < 360) return 16;
            return height < 500 ? 18 : 20;
        }

        static int step(int height) {
            if (height < 220) return 14;
            if (height < 280) return 16;
            if (height < 360) return 18;
            return rowHeight(height) + 4;
        }

        static int mixerTop(int height) {
            if (height < 220) return 12;
            if (height < 280) return 18;
            if (height < 360) return 24;
            return 34;
        }

        /** Where the mixer's column starts when both columns are shown. */
        static int mixerLeft(int width) {
            int minimum = Math.min(260, Math.max(128, width / 3));
            int maximum = Math.max(minimum, width - 192);
            return clamp((int) (width * 0.48F), minimum, maximum);
        }

        /** The width a card leaves its lines: between the cover and the remove button. */
        static float cardTextWidth(float columnWidth, int columns, int cardHeight) {
            float cardWidth = (columnWidth - CARD_GAP * (columns - 1)) / columns;
            return cardWidth - (cardHeight - 10) - 8 - 5 - 4 - REMOVE_SIZE - 5;
        }

        static float emptyPanelHeight(int trackTop, int trackBottom) {
            return Math.min(trackBottom - trackTop, 84);
        }

        /** The monitor's message without a picture: the body face when its lines fit, else the caption, centred inside. */
        static Block monitorBlock(TextMeasurer measurer, String text, Rect monitor) {
            float width = Math.max(24.0F, monitor.w() - 12);
            float height = monitor.h() - 6;
            return monitor.h() < 50 ? block(measurer, text, width, height, TextStyle.CAPTION)
                    : block(measurer, text, width, height, TextStyle.BODY, TextStyle.CAPTION);
        }

        /** "Not on this server" under a missing map's id: two caption lines at most. */
        static Block missingBlock(TextMeasurer measurer, String text, float width) {
            return block(measurer, text, width, 2 * Metrics.lineHeight(TextStyle.CAPTION), TextStyle.CAPTION);
        }

        /** The empty library's message under its icon, in the body face or the caption. */
        static Block emptyBlock(TextMeasurer measurer, String text, float columnWidth, float panelHeight) {
            return block(measurer, text, columnWidth - 24.0F, panelHeight - 28.0F, TextStyle.BODY, TextStyle.CAPTION);
        }

        /** The first face in which the text wraps into the box without a broken word, or null when none does. */
        static Block block(TextMeasurer measurer, String text, float width, float height, TextStyle... faces) {
            for (TextStyle face : faces) {
                boolean whole = true;
                for (String word : text.split(" ")) {
                    whole &= TextFit.width(measurer, word, face) <= width + 0.01F;
                }
                if (!whole) {
                    continue;
                }
                Block block = new Block(face, TextFit.wrap(measurer, text, face, width));
                if (block.height() <= height + 0.01F) {
                    return block;
                }
            }
            return null;
        }

        private static int clamp(int value, int minimum, int maximum) {
            return Math.max(minimum, Math.min(maximum, value));
        }

        private static Cell fixed(Control control, Kind kind) {
            return new Cell(control, kind, false);
        }

        private static Cell grow(Control control, Kind kind) {
            return new Cell(control, kind, true);
        }

        private static Line row(Cell... cells) {
            return new Line(List.of(cells), true);
        }

        private static Variant lines(Line... lines) {
            return new Variant(List.of(lines));
        }

        private static List<Variant> group(Variant... variants) {
            return List.of(variants);
        }

        boolean paged() { return paged; }
        int width() { return width; }
        int height() { return height; }
        int rowHeight() { return rowHeight; }
        int divider() { return divider; }
        Rect libraryColumn() { return libraryColumn; }
        Rect mixerColumn() { return mixerColumn; }
        Map<Control, Placed> controls() { return controls; }
        Placed placed(Control control) { return controls.get(control); }
        float stateY() { return stateY; }
        /** The top of the mixer's view: the scrolled rows are drawn and take the pointer only between this and the bottom. */
        int mixerViewTop() { return mixerViewTop; }
        int mixerViewBottom() { return mixerViewBottom; }
        /** From one row of the mixer to the next: the scroll a notch of the wheel moves. */
        int mixerStep() { return mixerStep; }
        /** Where the mixer's last line, the one saying what plays, ends, with the column scrolled to its top. */
        int mixerContentBottom() { return Math.round(stateY) + STATE_ROOM; }
        /** How far the mixer's column scrolls: 0 where the window holds it whole. */
        int mixerScrollMax() { return Math.max(0, mixerContentBottom() - mixerViewBottom); }
        List<Rect> tabs() { return tabs; }
        TextStyle tabFace(boolean selected) { return selected ? tabSelectedFace : tabFace; }
        int header() { return header; }
        /** The title's face, or null on a page too narrow to show it beside the tabs. */
        TextStyle titleFace() { return titleFace; }
        float titleY() { return titleY; }
        /** Where a narrow panel's library page says what is playing, in the header; empty while both columns show. */
        Rect nowPlaying() { return nowPlaying; }
        TextStyle nowPlayingFace() { return nowPlayingFace; }
        /** The monitor heading's y, or NaN when the panel shows its pages and the heading has no place. */
        float headingY() { return headingY; }
        Rect monitor() { return monitor; }
        float libraryHeadingY() { return libraryHeadingY; }
        String libraryHeadingKey() { return libraryHeadingKey; }
        int searchTop() { return searchTop; }
        int searchHeight() { return searchHeight; }
        int trackTop() { return trackTop; }
        int trackBottom() { return trackBottom; }
        int cardHeight() { return cardHeight; }
        int columns() { return columns; }
        List<String> problems() { return problems; }

        /** The mutable state of a layout being built. */
        private static final class Draft {
            final int width;
            final int height;
            final TextMeasurer measurer;
            final Words words;
            final boolean paged;
            final int rowHeight;
            final Map<Control, Placed> controls = new java.util.EnumMap<>(Control.class);
            final List<Rect> tabs = new ArrayList<>();
            final List<String> problems = new ArrayList<>();
            int divider = -1;
            Rect libraryColumn = Rect.EMPTY;
            Rect mixerColumn = Rect.EMPTY;
            float stateY;
            int mixerViewTop;
            int mixerViewBottom;
            int mixerStep;
            TextStyle tabFace = TextStyle.BODY;
            TextStyle tabSelectedFace = TextStyle.STRONG;
            int header;
            TextStyle titleFace;
            float titleY;
            Rect nowPlaying = Rect.EMPTY;
            TextStyle nowPlayingFace = TextStyle.CAPTION;
            float headingY = Float.NaN;
            Rect monitor = Rect.EMPTY;
            float libraryHeadingY;
            String libraryHeadingKey = LIBRARY_HEADING;
            int searchTop;
            int searchHeight;
            int trackTop;
            int trackBottom;
            int cardHeight = CARD_HEIGHT;
            int columns = 1;

            Draft(int width, int height, TextMeasurer measurer, Words words, boolean paged) {
                this.width = width;
                this.height = height;
                this.measurer = measurer;
                this.words = words;
                this.paged = paged;
                this.rowHeight = PanelLayout.rowHeight(height);
            }
        }
    }
}

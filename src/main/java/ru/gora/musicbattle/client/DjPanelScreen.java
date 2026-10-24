package ru.gora.musicbattle.client;

import com.mojang.blaze3d.platform.NativeImage;
import ru.gora.musicbattle.client.ui.Palette;
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
    private final BlockPos panelPos;
    private final List<TrackSummary> tracks = new ArrayList<>();
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

    public DjPanelScreen(OpenDjPanelPayload payload) {
        super(Component.translatable("screen.music_battle.dj_panel.title"));
        this.panelPos = payload.panelPos();
        applyServerState(payload, true);
    }

    /**
     * The server's answer to a Workshop pick from the playlist. It can come long after the pick, once an upload has
     * landed, when the DJ may be anywhere; so an outcome is also a toast, and the playlist shows it when open.
     */
    public static void acceptWorkshopAddResult(ru.gora.musicbattle.network.DjWorkshopAddResultPayload payload) {
        Component title = Component.translatable("screen.music_battle.dj_playlist.title");
        switch (payload.verdict()) {
            case ru.gora.musicbattle.network.DjWorkshopAddResultPayload.UPLOAD ->
                    ru.gora.musicbattle.client.workshop.WorkshopScreen.djUploadApproved(payload.setId());
            case ru.gora.musicbattle.network.DjWorkshopAddResultPayload.SEND_INSTALLED -> {
                // The server asks for this client's copy next, as for a battle vote, and the transfer only says
                // "need": the set it means has to be named here first. A set that is not installed fails its
                // repack, which cancels the transfer, and the server answers that on the playlist line.
                var installed = ru.gora.musicbattle.client.workshop.WorkshopClient.get().catalog()
                        .bySetId(payload.setId()).orElse(null);
                ru.gora.musicbattle.client.workshop.ServerWorkshopClient.rememberVoteUpload(payload.setId(),
                        installed == null ? 0 : installed.revision(),
                        installed == null ? "" : installed.contentSha256());
                DjPlaylistScreen.workshopSending(payload.setId());
            }
            case ru.gora.musicbattle.network.DjWorkshopAddResultPayload.ADDED -> {
                ru.gora.musicbattle.client.workshop.WorkshopScreen.djAnswered(payload.setId());
                DjPlaylistScreen.workshopStatus(payload.message(), false, payload.panelPos());
                ru.gora.musicbattle.client.workshop.WorkshopToasts.show(title, payload.message());
            }
            default -> {
                ru.gora.musicbattle.client.workshop.WorkshopScreen.djAnswered(payload.setId());
                DjPlaylistScreen.workshopRefused(payload.message(), payload.setId());
                ru.gora.musicbattle.client.workshop.WorkshopToasts.problem(title, payload.message());
            }
        }
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
        tracks.clear();
        tracks.addAll(payload.tracks());
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
                monitorTransitions.capture(monitorVisualEffects,oldTick,monitorVisualBpm,monitorVisualSpeed,System.nanoTime());
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

        int selected = findTrack(selectedId.isBlank() ? loadedTrackId : selectedId);
        trackIndex = selected >= 0 ? selected : Mth.clamp(trackIndex, 0, Math.max(0, tracks.size() - 1));
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
        int cameraLeft = 12;
        int cameraWidth = Math.max(64, mixerLeft() - 30);
        int cameraY = cameraControlY();
        int cameraRowHeight = Math.min(18, layoutRowHeight());
        int cameraArrowWidth = Math.min(20, Math.max(14, cameraWidth / 7));
        int cameraUnlinkWidth = Math.min(32, Math.max(18, cameraWidth / 5));
        int cameraLabelWidth = Math.max(18,
                cameraWidth - cameraArrowWidth * 2 - cameraUnlinkWidth - 9);
        cameraPreviousButton = addRenderableWidget(FancyButton.create(Component.literal("<"), button -> cycleCamera(-1),
                cameraLeft, cameraY, cameraArrowWidth, cameraRowHeight));
        cameraButton = addRenderableWidget(FancyButton.create(cameraLabel(), button -> openCameraGallery(),
                cameraLeft + cameraArrowWidth + 3, cameraY, cameraLabelWidth, cameraRowHeight));
        cameraButton.setTooltip(Tooltip.create(
                Component.translatable("screen.music_battle.dj_camera_gallery.open_tooltip")));
        cameraNextButton = addRenderableWidget(FancyButton.create(Component.literal(">"), button -> cycleCamera(1),
                cameraLeft + cameraArrowWidth + cameraLabelWidth + 6, cameraY,
                cameraArrowWidth, cameraRowHeight));
        unlinkCameraButton = addRenderableWidget(FancyButton.create(Component.literal("×"), button -> unlinkCamera(),
                cameraLeft + cameraWidth - cameraUnlinkWidth, cameraY,
                cameraUnlinkWidth, cameraRowHeight));
        unlinkCameraButton.setTooltip(Tooltip.create(
                Component.translatable("screen.music_battle.dj_panel.unlink_camera")));

        int searchAreaWidth = Math.max(64, mixerLeft() - 30);
        boolean compactPreloadButton = searchAreaWidth < 220;
        int preloadWidth = compactPreloadButton ? 32 : Math.min(120, Math.max(86, searchAreaWidth / 3));
        // The Workshop sits beside the search because this library is where a DJ looks for a song; tucked into the
        // playlist screen alone, players never found it. A narrow library keeps the search usable with a "+" button.
        boolean compactWorkshopButton = searchAreaWidth < 300;
        int workshopWidth = compactWorkshopButton ? 32 : Math.min(120, Math.max(80, searchAreaWidth / 4));
        int searchWidth = Math.max(27, searchAreaWidth - preloadWidth - workshopWidth - 10);
        searchBox = addRenderableWidget(new ru.gora.musicbattle.client.ui.FancyEditBox(font, 12, searchTop(), searchWidth,
                searchHeight(), Component.translatable("screen.music_battle.dj_panel.search")));
        searchBox.setMaxLength(128);
        searchBox.setHint(Component.translatable("screen.music_battle.dj_panel.search_hint"));
        searchBox.setValue(searchQuery);
        searchBox.setResponder(value -> {
            searchQuery = value == null ? "" : value;
            trackScroll = 0;
            rebuildTrackFilter();
            updateControls();
        });
        preloadAudienceButton = addRenderableWidget(FancyButton.create(
                compactPreloadButton ? Component.literal("↓")
                        : Component.translatable("screen.music_battle.dj_panel.preload_audience"),
                button -> preloadSelectedTrack(), 12 + searchWidth + workshopWidth + 10, searchTop(),
                preloadWidth, searchHeight()));
        Button workshopButton = addRenderableWidget(FancyButton.create(
                compactWorkshopButton ? Component.literal("+")
                        : Component.translatable("screen.music_battle.dj_panel.workshop"),
                button -> openWorkshop(), 12 + searchWidth + 5, searchTop(), workshopWidth, searchHeight()));
        workshopButton.setTooltip(Tooltip.create(
                Component.translatable("screen.music_battle.dj_panel.workshop_tooltip")));
        preloadAudienceButton.setTooltip(Tooltip.create(
                Component.translatable("screen.music_battle.dj_panel.preload_audience_tooltip")));

        int x = mixerLeft();
        int panelWidth = mixerWidth();
        int top = layoutTop();
        int rowHeight = layoutRowHeight();
        int step = layoutStep();

        int modeWidth = Math.max(68, panelWidth / 3);
        int arrowWidth = 22;
        int speakerX = x + modeWidth + arrowWidth + 8;
        int speakerWidth = Math.max(16, panelWidth - modeWidth - arrowWidth * 2 - 12);
        speakerModeButton = addRenderableWidget(FancyButton.create(speakerModeLabel(), button -> toggleSpeakerMode(),
                x, top, modeWidth, rowHeight));
        addRenderableWidget(FancyButton.create(Component.literal("<"), button -> cycleSpeaker(-1),
                x + modeWidth + 4, top, arrowWidth, rowHeight));
        speakerButton = addRenderableWidget(FancyButton.create(speakerLabel(), button -> cycleSpeaker(1),
                speakerX, top, speakerWidth, rowHeight));
        addRenderableWidget(FancyButton.create(Component.literal(">"), button -> cycleSpeaker(1),
                x + panelWidth - arrowWidth, top, arrowWidth, rowHeight));

        int resetWidth = panelWidth >= 300 ? Math.min(88, panelWidth / 4) : 30;
        int radiusWidth = Math.max(45, panelWidth - resetWidth - 5);
        radiusSlider = addRenderableWidget(setting(x, top + step, radiusWidth, rowHeight,
                "screen.music_battle.dj_panel.radius", radius, 2.0F, 64.0F,
                value -> { radius = value; queueSpeakerUpdate(); }, Unit.NUMBER));
        resetSpeakersButton = addRenderableWidget(FancyButton.create(
                panelWidth >= 300 ? Component.translatable("screen.music_battle.dj_panel.reset_speakers")
                        : Component.literal("↺"),
                button -> resetSpeakers(), x + radiusWidth + 5, top + step, resetWidth, rowHeight));
        resetSpeakersButton.setTooltip(Tooltip.create(
                Component.translatable("screen.music_battle.dj_panel.reset_speakers_tooltip")));
        volumeSlider = addRenderableWidget(setting(x, top + step * 2, panelWidth, rowHeight,
                "screen.music_battle.dj_panel.volume", volume, 0.0F, 2.0F,
                value -> { volume = value; queueSpeakerUpdate(); }, Unit.PERCENT));
        int equalizerPresetWidth = panelWidth >= 300
                ? Math.min(96, Math.max(72, panelWidth / 5))
                : Math.min(32, Math.max(20, panelWidth / 5));
        int equalizerSlidersWidth = Math.max(45, panelWidth - equalizerPresetWidth - 5);
        int equalizerThird = Math.max(12, (equalizerSlidersWidth - 10) / 3);
        lowSlider = addRenderableWidget(setting(x, top + step * 3, equalizerThird, rowHeight,
                "screen.music_battle.dj_panel.eq_low", lowGainDb, -12.0F, 12.0F,
                value -> { lowGainDb = value; queueSpeakerUpdate(); }, Unit.DECIBELS));
        midSlider = addRenderableWidget(setting(x + equalizerThird + 5, top + step * 3, equalizerThird, rowHeight,
                "screen.music_battle.dj_panel.eq_mid", midGainDb, -12.0F, 12.0F,
                value -> { midGainDb = value; queueSpeakerUpdate(); }, Unit.DECIBELS));
        highSlider = addRenderableWidget(setting(x + (equalizerThird + 5) * 2, top + step * 3, equalizerThird, rowHeight,
                "screen.music_battle.dj_panel.eq_high", highGainDb, -12.0F, 12.0F,
                value -> { highGainDb = value; queueSpeakerUpdate(); }, Unit.DECIBELS));
        equalizerPresetsButton = addRenderableWidget(FancyButton.create(
                panelWidth >= 300
                        ? Component.translatable("screen.music_battle.dj_panel.eq_presets")
                        : Component.literal("★"),
                button -> openEqualizerPresets(),
                x + equalizerSlidersWidth + 5, top + step * 3,
                equalizerPresetWidth, rowHeight));
        equalizerPresetsButton.setTooltip(Tooltip.create(
                Component.translatable("screen.music_battle.dj_panel.eq_presets_tooltip")));

        int third = (panelWidth - 10) / 3;
        int quarter = (panelWidth - 15) / 4;
        dspButton = addRenderableWidget(FancyButton.create(toggleLabel("screen.music_battle.dj_panel.dsp", dspEnabled),
                button -> { dspEnabled = !dspEnabled; queuePanelFxUpdate(); refreshToggleLabels(); },
                x, top + step * 4, quarter, rowHeight));
        reverbButton = addRenderableWidget(FancyButton.create(toggleLabel("screen.music_battle.dj_panel.reverb", reverbEnabled),
                button -> { reverbEnabled = !reverbEnabled; queuePanelFxUpdate(); refreshToggleLabels(); },
                x + quarter + 5, top + step * 4, quarter, rowHeight));
        echoButton = addRenderableWidget(FancyButton.create(toggleLabel("screen.music_battle.dj_panel.echo", echoEnabled),
                button -> { echoEnabled = !echoEnabled; queuePanelFxUpdate(); refreshToggleLabels(); },
                x + (quarter + 5) * 2, top + step * 4, quarter, rowHeight));
        lowPassButton = addRenderableWidget(FancyButton.create(toggleLabel("screen.music_battle.dj_panel.low_pass", lowPassEnabled),
                button -> { lowPassEnabled = !lowPassEnabled; queuePanelFxUpdate(); refreshToggleLabels(); },
                x + (quarter + 5) * 3, top + step * 4, quarter, rowHeight));

        reverbWetSlider = addRenderableWidget(setting(x, top + step * 5, third, rowHeight,
                "screen.music_battle.dj_panel.reverb_wet", reverbWet, 0.0F, 1.0F,
                value -> { reverbWet = value; queuePanelFxUpdate(); }, Unit.PERCENT));
        reverbRoomSlider = addRenderableWidget(setting(x + third + 5, top + step * 5, third, rowHeight,
                "screen.music_battle.dj_panel.reverb_room", reverbRoom, 0.0F, 1.0F,
                value -> { reverbRoom = value; queuePanelFxUpdate(); }, Unit.PERCENT));
        reverbDampingSlider = addRenderableWidget(setting(x + (third + 5) * 2, top + step * 5, third, rowHeight,
                "screen.music_battle.dj_panel.reverb_damping", reverbDamping, 0.0F, 1.0F,
                value -> { reverbDamping = value; queuePanelFxUpdate(); }, Unit.PERCENT));

        echoWetSlider = addRenderableWidget(setting(x, top + step * 6, third, rowHeight,
                "screen.music_battle.dj_panel.echo_wet", echoWet, 0.0F, 0.75F,
                value -> { echoWet = value; queuePanelFxUpdate(); }, Unit.PERCENT));
        echoTimeSlider = addRenderableWidget(setting(x + third + 5, top + step * 6, third, rowHeight,
                "screen.music_battle.dj_panel.echo_time", echoTimeMs, 40.0F, 1000.0F,
                value -> { echoTimeMs = value; queuePanelFxUpdate(); }, Unit.MILLISECONDS));
        echoFeedbackSlider = addRenderableWidget(setting(x + (third + 5) * 2, top + step * 6, third, rowHeight,
                "screen.music_battle.dj_panel.echo_feedback", echoFeedback, 0.0F, 0.85F,
                value -> { echoFeedback = value; queuePanelFxUpdate(); }, Unit.PERCENT));
        int audioPresetWidth = panelWidth >= 300 ? Math.min(104, panelWidth / 3) : 34;
        int lowPassWidth = Math.max(45, panelWidth - audioPresetWidth - 5);
        lowPassSlider = addRenderableWidget(setting(x, top + step * 7, lowPassWidth, rowHeight,
                "screen.music_battle.dj_panel.low_pass_cutoff", lowPassCutoffHz, 200.0F, 20_000.0F,
                value -> { lowPassCutoffHz = value; queuePanelFxUpdate(); }, Unit.HERTZ));
        audioPresetsButton = addRenderableWidget(FancyButton.create(
                panelWidth >= 300 ? Component.translatable("screen.music_battle.dj_panel.audio_presets")
                        : Component.literal("♫"),
                button -> openAudioPresets(), x + lowPassWidth + 5, top + step * 7,
                audioPresetWidth, rowHeight));
        audioPresetsButton.setTooltip(Tooltip.create(
                Component.translatable("screen.music_battle.dj_panel.audio_presets_tooltip")));

        int timelineY = top + step * 8;
        timelineSlider = addRenderableWidget(setting(x, timelineY, panelWidth, rowHeight,
                "screen.music_battle.dj_panel.timeline", timelineDisplayPosition(),
                0.0F, Math.max(20.0F, selectedTimelineDuration()),
                value -> queueSeek(Math.round(value)), Unit.TIME, true));

        int buttonY = top + step * 9;
        int playbackWidth = (panelWidth - 20) / 5;
        playButton = addRenderableWidget(FancyButton.create(Component.translatable("screen.music_battle.dj_panel.play_resume"),
                button -> playResume(), x, buttonY, playbackWidth, rowHeight));
        pauseButton = addRenderableWidget(FancyButton.create(Component.translatable("screen.music_battle.dj_panel.pause"),
                button -> pausePlayback(), x + playbackWidth + 5, buttonY, playbackWidth, rowHeight));
        addRenderableWidget(FancyButton.create(Component.translatable("screen.music_battle.dj_panel.restart"),
                button -> restart(), x + (playbackWidth + 5) * 2, buttonY, playbackWidth, rowHeight));
        speedButton = addRenderableWidget(FancyButton.create(speedLabel(), button -> cycleSpeed(),
                x + (playbackWidth + 5) * 3, buttonY, playbackWidth, rowHeight));
        repeatButton = addRenderableWidget(FancyButton.create(repeatLabel(), button -> cycleRepeat(),
                x + (playbackWidth + 5) * 4, buttonY, playbackWidth, rowHeight));
        repeatButton.setTooltip(Tooltip.create(Component.translatable("screen.music_battle.dj_panel.repeat_tooltip")));

        int bottomY = Math.min(height - rowHeight - 8, top + step * 10);
        int bottomWidth = (panelWidth - 15) / 4;
        unlinkButton = addRenderableWidget(FancyButton.create(Component.translatable("screen.music_battle.dj_panel.unlink_speaker"),
                button -> unlinkSpeaker(), x, bottomY, bottomWidth, rowHeight));
        visualFxButton = addRenderableWidget(FancyButton.create(
                Component.translatable("screen.music_battle.dj_panel.visual_fx"),
                button -> openVisualFx(), x + bottomWidth + 5, bottomY, bottomWidth, rowHeight));
        playlistButton = addRenderableWidget(FancyButton.create(
                Component.translatable("screen.music_battle.dj_panel.playlist"),
                button -> openPlaylist(), x + (bottomWidth + 5) * 2, bottomY, bottomWidth, rowHeight));
        addRenderableWidget(FancyButton.create(Component.translatable("screen.music_battle.dj_panel.close"),
                button -> onClose(), x + (bottomWidth + 5) * 3, bottomY, bottomWidth, rowHeight));
        updateControls();
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
        int monitorWidth = Math.max(64, mixerLeft() - 30);
        int monitorHeight = cameraPreviewHeight();
        graphics.flush();
        cameraPreviewStatus = DjCameraPreviewRenderer.updateLiveMonitor(
                cameraSnapshot, monitorWidth, monitorHeight, partialTick, monitorEffectState(partialTick),
                monitorPlayerEntityIds, monitorMobEntityIds);
        graphics.fill(0, 0, width, height, Palette.withAlpha(Palette.BG_0, 0.95F));
        int divider = mixerLeft() - 8;
        graphics.fill(divider, 0, width, height, Palette.BG_1);
        graphics.fill(divider, 0, divider + 2, height, Palette.ACCENT);
        int headerY = height < 220 ? 1 : 10;
        graphics.drawCenteredString(font, title, divider + (width - divider) / 2, headerY, Palette.TEXT_1);
        String monitorTitle = Component.translatable("screen.music_battle.dj_panel.camera_monitor").getString();
        graphics.drawString(font, fit(monitorTitle, Math.max(24, mixerLeft() - 34)),
                14, headerY + 2, Palette.TEXT_2, false);
        drawCameraMonitor(graphics);
        graphics.drawString(font, Component.translatable("screen.music_battle.dj_panel.track_library"),
                14, searchTop() - 11, Palette.TEXT_2, false);
        drawTrackCards(graphics, mouseX, mouseY);

        String state = playbackStateText();
        int stateY = Math.max(12, Math.min(height - 18, layoutTop() + layoutStep() * 11));
        graphics.drawCenteredString(font, font.plainSubstrByWidth(state, mixerWidth()),
                mixerLeft() + mixerWidth() / 2, stateY, playing ? Palette.OK : Palette.TEXT_3);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void drawCameraMonitor(GuiGraphics graphics) {
        int x = 12;
        int y = cameraPreviewTop();
        int monitorWidth = Math.max(64, mixerLeft() - 30);
        int monitorHeight = cameraPreviewHeight();
        graphics.fill(x - 1, y - 1, x + monitorWidth + 1, y + monitorHeight + 1, Palette.ACCENT);
        graphics.fill(x, y, x + monitorWidth, y + monitorHeight, Palette.BG_0);
        if (cameraPreviewStatus == DjCameraPreviewRenderer.Status.READY) {
            DjCameraPreviewRenderer.drawLiveMonitor(graphics, x, y, monitorWidth, monitorHeight);
            graphics.fill(x, y, x + monitorWidth, y + 10, 0x88000000);
            graphics.drawString(font, Component.translatable("screen.music_battle.dj_panel.camera_live"),
                    x + 4, y + 1, 0xFFFF77D4, false);
            OpenDjPanelPayload.CameraEntry camera = selectedCamera();
            if (camera != null && monitorWidth >= 110) {
                String coords = camera.pos().toShortString();
                graphics.drawString(font, fit(coords, monitorWidth / 2),
                        x + monitorWidth - font.width(fit(coords, monitorWidth / 2)) - 3,
                        y + 1, Palette.TEXT_2, false);
            }
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
            case READY -> "screen.music_battle.dj_panel.camera_live";
        };
        Component status = Component.translatable(key);
        List<net.minecraft.util.FormattedCharSequence> lines = font.split(status, Math.max(24, monitorWidth - 10));
        int lineHeight = font.lineHeight + 2;
        int drawY = y + Math.max(4, (monitorHeight - lines.size() * lineHeight) / 2);
        for (net.minecraft.util.FormattedCharSequence line : lines) {
            graphics.drawCenteredString(font, line, x + monitorWidth / 2, drawY, Palette.TEXT_3);
            drawY += lineHeight;
        }
    }

    private void drawTrackCards(GuiGraphics graphics, int mouseX, int mouseY) {
        int left = 12;
        int top = trackTop();
        int areaWidth = mixerLeft() - 30;
        int bottom = height - 12;
        if (filteredTrackIndices.isEmpty()) {
            String key = tracks.isEmpty() ? "screen.music_battle.dj_panel.no_tracks"
                    : "screen.music_battle.dj_panel.no_search_results";
            graphics.drawCenteredString(font, Component.translatable(key),
                    left + areaWidth / 2, top + 24, Palette.TEXT_2);
            return;
        }
        int columns = areaWidth >= 430 ? 2 : 1;
        int gap = 6;
        int cardWidth = (areaWidth - gap * (columns - 1)) / columns;
        int cardHeight = 78;
        int rows = (filteredTrackIndices.size() + columns - 1) / columns;
        int maxScroll = Math.max(0, rows * (cardHeight + gap) - (bottom - top));
        trackScroll = Mth.clamp(trackScroll, 0, maxScroll);
        List<String> visibleIds = new ArrayList<>();
        TrackSummary hovered = null;
        graphics.enableScissor(left, top, left + areaWidth, bottom);
        for (int slot = 0; slot < filteredTrackIndices.size(); slot++) {
            int index = filteredTrackIndices.get(slot);
            int x = left + slot % columns * (cardWidth + gap);
            int y = top + slot / columns * (cardHeight + gap) - trackScroll;
            if (y + cardHeight < top || y > bottom) continue;
            TrackSummary track = tracks.get(index);
            visibleIds.add(track.id());
            boolean selected = index == trackIndex;
            boolean over = mouseX >= x && mouseX <= x + cardWidth && mouseY >= y && mouseY <= y + cardHeight;
            if (over) hovered = track;
            graphics.fill(x, y, x + cardWidth, y + cardHeight,
                    selected ? Palette.ACCENT_DEEP : over ? Palette.BG_4 : Palette.BG_3);
            graphics.fill(x, y, x + cardWidth, y + (selected ? 3 : 2),
                    selected ? Palette.ACCENT : over ? Palette.LINK : Palette.LINE);
            int image = Math.min(68, Math.max(32, Math.min(cardHeight - 10, cardWidth / 3)));
            ResourceLocation preview = previewTexture(track);
            if (preview != null) {
                graphics.blit(preview, x + 5, y + 5, 0.0F, 0.0F, image, image, image, image);
            } else {
                graphics.fill(x + 5, y + 5, x + 5 + image, y + 5 + image, Palette.BG_2);
                graphics.drawCenteredString(font, "NO IMG", x + 5 + image / 2, y + image / 2, Palette.TEXT_MUTE);
            }
            int textX = x + image + 11;
            int textWidth = Math.max(24, cardWidth - image - 16);
            graphics.drawString(font, fit(track.title(), textWidth), textX, y + 8, Palette.TEXT_1, false);
            graphics.drawString(font, "BPM " + track.bpm() + "  •  " + formatTicks(track.durationTicks()),
                    textX, y + 22, Palette.TEXT_2, false);
            String author = track.publishedBy().isBlank()
                    ? Component.translatable("screen.music_battle.dj_panel.builtin").getString()
                    : track.publishedBy();
            graphics.drawString(font, fit(Component.translatable("screen.music_battle.dj_panel.published_by", author).getString(), textWidth),
                    textX, y + 36, Palette.WARN, false);
            graphics.drawString(font, fit(track.description(), textWidth), textX, y + 52, Palette.TEXT_3, false);
        }
        graphics.disableScissor();
        requestVisiblePreviews(visibleIds);
        if (hovered != null && !hovered.publishedBy().isBlank()) {
            graphics.renderTooltip(font, Component.translatable("screen.music_battle.dj_panel.published_by", hovered.publishedBy()),
                    mouseX, mouseY);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int monitorX = 12;
        int monitorY = cameraPreviewTop();
        int monitorWidth = Math.max(64, mixerLeft() - 30);
        int monitorHeight = cameraPreviewHeight();
        if (button == 0
                && mouseX >= monitorX && mouseX < monitorX + monitorWidth
                && mouseY >= monitorY && mouseY < monitorY + monitorHeight) {
            openCameraGallery();
            return true;
        }
        int trackTop = trackTop();
        if (button == 0 && mouseX >= 12 && mouseX < mixerLeft() - 8 && mouseY >= trackTop && mouseY <= height - 12) {
            int areaWidth = mixerLeft() - 30;
            int columns = areaWidth >= 430 ? 2 : 1;
            int gap = 6;
            int cardWidth = (areaWidth - gap * (columns - 1)) / columns;
            int cardHeight = 78;
            int column = (int) (mouseX - 12) / (cardWidth + gap);
            int row = (int) (mouseY - trackTop + trackScroll) / (cardHeight + gap);
            int withinX = (int) (mouseX - 12) % (cardWidth + gap);
            int withinY = (int) (mouseY - trackTop + trackScroll) % (cardHeight + gap);
            int slot = row * columns + column;
            if (column < columns && withinX < cardWidth && withinY < cardHeight
                    && slot >= 0 && slot < filteredTrackIndices.size()) {
                // Commit a drag on the currently loaded track before the card
                // selection changes the timeline into next-track cue mode.
                flushSeek();
                trackIndex = filteredTrackIndices.get(slot);
                send(ConfigureDjPanelPayload.Action.SELECT_TRACK, BlockPos.ZERO, selectedTrackId(), false, 0);
                updateControls();
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (mouseX < mixerLeft() - 8 && mouseY >= trackTop()) {
            trackScroll = Math.max(0, trackScroll - (int) Math.round(scrollY * 36.0D));
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
        if (tracks.isEmpty() || speakers.isEmpty()) return;
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
        if (tracks.isEmpty() || speakers.isEmpty()) return;
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
        int bpm = tracks.isEmpty() ? 120 : selectedTrack().bpm();
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

    private void openWorkshop() {
        if (minecraft == null) {
            return;
        }
        flushAllPending();
        DjPlaylistScreen.openWorkshopFromPanel(this);
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
        boolean hasTracks = !tracks.isEmpty();
        boolean selectedVisible = searchQuery.isBlank() || filteredTrackIndices.contains(trackIndex);
        boolean hasSpeakers = !speakers.isEmpty();
        boolean hasLoaded = !loadedTrackId.isBlank();
        if (playButton != null) playButton.active = hasTracks && hasSpeakers && selectedVisible;
        if (pauseButton != null) pauseButton.active = playing;
        if (preloadAudienceButton != null) {
            preloadAudienceButton.active = canPreloadAudience(
                    hasTracks, selectedVisible, selectedTrackId(), loadedTrackId);
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
        if (playlistButton != null) playlistButton.active = hasTracks;
        if (timelineSlider != null) timelineSlider.active = hasTracks;
        if (speedButton != null) speedButton.active = true;
        refreshToggleLabels();
    }

    static boolean canPreloadAudience(boolean hasTracks, boolean selectedVisible,
                                      String selectedTrackId, String loadedTrackId) {
        return hasTracks && selectedVisible && selectedTrackId != null && !selectedTrackId.isBlank()
                && !selectedTrackId.equals(loadedTrackId == null ? "" : loadedTrackId);
    }

    private void refreshWidgetsFromState() {
        refreshSpeakerSliders();
        if (speakerButton != null) speakerButton.setMessage(speakerLabel());
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

    private void refreshToggleLabels() {
        if (dspButton != null) dspButton.setMessage(toggleLabel("screen.music_battle.dj_panel.dsp", dspEnabled));
        if (reverbButton != null) reverbButton.setMessage(toggleLabel("screen.music_battle.dj_panel.reverb", reverbEnabled));
        if (echoButton != null) echoButton.setMessage(toggleLabel("screen.music_battle.dj_panel.echo", echoEnabled));
        if (lowPassButton != null) lowPassButton.setMessage(toggleLabel("screen.music_battle.dj_panel.low_pass", lowPassEnabled));
    }

    private static Component toggleLabel(String key, boolean enabled) {
        return Component.translatable(key).append(enabled ? " ✓" : "");
    }

    private Component speakerModeLabel() {
        return Component.translatable(applyToAll
                ? "screen.music_battle.dj_panel.edit_all"
                : "screen.music_battle.dj_panel.edit_one");
    }

    private Component speakerLabel() {
        if (speakers.isEmpty()) return Component.translatable("screen.music_battle.dj_panel.no_speakers");
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

    private String playbackStateText() {
        if (loadedTrackId.isBlank()) {
            return Component.translatable("screen.music_battle.dj_panel.no_track_loaded").getString();
        }
        String trackTitle = tracks.stream().filter(track -> track.id().equals(loadedTrackId))
                .map(TrackSummary::title).findFirst().orElse(loadedTrackId);
        String key = playing ? "screen.music_battle.dj_panel.playing"
                : paused ? "screen.music_battle.dj_panel.paused" : "screen.music_battle.dj_panel.stopped";
        return Component.translatable(key, trackTitle).getString() + "  "
                + formatTicks(currentPlaybackPosition()) + " / " + formatTicks(currentPlaybackDuration());
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
        return monitorTransitions.attach(ClientTrackEffects.sample(monitorVisualEffects, time, monitorVisualBpm, monitorVisualSpeed),System.nanoTime());
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
        int sourceDuration = tracks.isEmpty() ? 0 : selectedTrack().durationTicks();
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

    private TrackSummary selectedTrack() {
        return tracks.get(Mth.clamp(trackIndex, 0, tracks.size() - 1));
    }

    private String selectedTrackId() {
        return tracks.isEmpty() ? "" : selectedTrack().id();
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

    private void rebuildTrackFilter() {
        filteredTrackIndices.clear();
        String query = searchQuery == null ? "" : searchQuery.strip().toLowerCase(Locale.ROOT);
        for (int index = 0; index < tracks.size(); index++) {
            String title = tracks.get(index).title();
            if (query.isEmpty() || (title != null && title.toLowerCase(Locale.ROOT).contains(query))) {
                filteredTrackIndices.add(index);
            }
        }
    }

    private Component speedLabel() {
        String shown = playbackSpeed >= 1.9F ? "2" : playbackSpeed >= 1.4F ? "1.5" : "1";
        return Component.translatable("screen.music_battle.dj_panel.speed", shown);
    }

    private int mixerLeft() {
        int minimum = Math.min(260, Math.max(128, width / 3));
        int maximum = Math.max(minimum, width - 192);
        return Mth.clamp((int) (width * 0.48F), minimum, maximum);
    }

    private int cameraControlY() {
        return height < 220 ? 13 : 28;
    }

    private int cameraPreviewTop() {
        return cameraControlY() + Math.min(18, layoutRowHeight()) + 4;
    }

    private int cameraPreviewHeight() {
        int areaWidth = Math.max(64, mixerLeft() - 30);
        if (height < 220) {
            return Math.min(42, Math.max(32, height / 5));
        }
        int preferred = Math.round(areaWidth * 9.0F / 16.0F);
        return Mth.clamp(preferred, 44, height < 280 ? 58 : 92);
    }

    private int searchTop() {
        return cameraPreviewTop() + cameraPreviewHeight() + 13;
    }

    private int searchHeight() {
        return Math.min(18, Math.max(12, layoutRowHeight()));
    }

    private int trackTop() {
        return searchTop() + searchHeight() + 4;
    }

    private int mixerWidth() {
        return Math.max(96, width - mixerLeft() - 14);
    }

    private int layoutTop() {
        if (height < 220) return 12;
        if (height < 280) return 18;
        if (height < 360) return 24;
        return 34;
    }

    private int layoutRowHeight() {
        if (height < 220) return 12;
        if (height < 280) return 14;
        if (height < 360) return 16;
        return height < 500 ? 18 : 20;
    }

    private int layoutStep() {
        if (height < 220) return 14;
        if (height < 280) return 16;
        if (height < 360) return 18;
        return layoutRowHeight() + 4;
    }

    private String fit(String text, int width) {
        String safe = text == null || text.isBlank() ? "—" : text;
        if (font.width(safe) <= width) return safe;
        return font.plainSubstrByWidth(safe, Math.max(8, width - font.width("…"))) + "…";
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
        int seconds = Math.max(0, ticks) / 20;
        return String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60);
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

    private SettingSlider setting(int x, int y, int width, int height, String key, float current,
                                  float min, float max, Setter setter, Unit unit) {
        return setting(x, y, width, height, key, current, min, max, setter, unit, false);
    }

    private SettingSlider setting(int x, int y, int width, int height, String key, float current,
                                  float min, float max, Setter setter, Unit unit,
                                  boolean commitOnRelease) {
        return new SettingSlider(x, y, width, height, key, current, min, max, setter, unit, commitOnRelease);
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
            String shown = switch (unit) {
                case PERCENT -> String.format(Locale.ROOT, "%.0f", setting() * 100.0F);
                case DECIBELS -> String.format(Locale.ROOT, "%+.1f", setting());
                case NUMBER -> String.format(Locale.ROOT, "%.1f", setting());
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

    private enum Unit { NUMBER, PERCENT, DECIBELS, MILLISECONDS, HERTZ, TIME }
}

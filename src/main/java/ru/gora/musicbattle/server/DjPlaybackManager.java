package ru.gora.musicbattle.server;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import ru.gora.musicbattle.MusicBattleMod;
import ru.gora.musicbattle.block.entity.BattleCameraBlockEntity;
import ru.gora.musicbattle.block.entity.DjPanelBlockEntity;
import ru.gora.musicbattle.block.entity.SpeakerBlockEntity;
import ru.gora.musicbattle.data.DjVisualEffects;
import ru.gora.musicbattle.data.DjVisualEffectConfig;
import ru.gora.musicbattle.data.DjPlaylistEntry;
import ru.gora.musicbattle.data.TrackEffect;
import ru.gora.musicbattle.network.BattleAudiencePayload;
import ru.gora.musicbattle.network.ConfigureDjPanelPayload;
import ru.gora.musicbattle.network.ConfigureDjPlaylistPayload;
import ru.gora.musicbattle.network.DjAssetReadyPayload;
import ru.gora.musicbattle.network.DjAudienceCameraPayload;
import ru.gora.musicbattle.network.DjCameraPreviewPayload;
import ru.gora.musicbattle.network.DjPreloadAnswerPayload;
import ru.gora.musicbattle.network.DjPreloadOfferPayload;
import ru.gora.musicbattle.network.OpenDjPanelPayload;
import ru.gora.musicbattle.network.SpectatorBattlePayload;
import ru.gora.musicbattle.network.SpectatorMusicPayload;
import ru.gora.musicbattle.network.SpectatorStopPayload;
import ru.gora.musicbattle.network.TrackAssetChunkPayload;

/** Server-authoritative standalone music/FX sessions controlled by DJ panels. */
public final class DjPlaybackManager {
    private static final int ASSET_CHUNK_BYTES = 60 * 1024;
    private static final int ASSET_CHUNKS_PER_TICK = 8;
    private static final long ASSET_TRANSFER_TIMEOUT_TICKS = 1_200L;
    private static final long ASSET_ACK_TIMEOUT_TICKS = 200L;
    private static final long ASSET_RETRY_DELAY_TICKS = 100L;
    private static final long MAX_AUDIO_BYTES = 96L * 1024L * 1024L;
    private static final long MAX_PREVIEW_BYTES = 8L * 1024L * 1024L;
    private static final double PANEL_USE_DISTANCE_SQUARED = 8.0D * 8.0D;
    private static final java.util.function.Predicate<DjPlaylistEntry> PLAYABLE_PLAYLIST_ENTRY =
            entry -> trackById(entry.trackId()).isPresent();
    private static final Map<ServerLevel, Set<BlockPos>> LOADED_PANELS = new WeakHashMap<>();
    private static final Map<ServerLevel, ru.gora.musicbattle.data.ExplicitCameraSelection<BlockPos, BlockPos>> EXPLICIT_CAMERAS = new WeakHashMap<>();
    private static final Map<UUID, SessionAudience> AUDIENCES = new HashMap<>();
    private static final Map<Path, AssetFingerprint> ASSET_FINGERPRINTS = new HashMap<>();
    private static final ArrayDeque<PendingAssetTransfer> ASSET_TRANSFER_QUEUE = new ArrayDeque<>();
    private static final Map<PreviewRequestKey, Long> PREVIEW_REQUEST_AFTER = new HashMap<>();
    private static final Map<PreviewRequestKey, Long> CAMERA_PREVIEW_REQUEST_AFTER = new HashMap<>();
    private static final Map<LiveControlKey, Long> LIVE_CONTROL_AFTER = new HashMap<>();
    private static final Map<LiveControlKey, ConfigureDjPanelPayload> DEFERRED_LIVE_CONTROL = new HashMap<>();
    private static final Map<PanelKey, PendingAudioIntent> PENDING_AUDIO_INTENTS = new HashMap<>();
    private static final Map<SpeedRequestKey, Long> SPEED_REQUEST_REVISIONS = new HashMap<>();
    private static final Map<PanelKey, PreloadPlan> PRELOAD_PLANS = new HashMap<>();
    private static final long PRELOAD_ANSWER_TIMEOUT_TICKS = 40L;
    private static final long PRELOAD_RETRY_DELAY_TICKS = 40L;
    private static boolean audienceRefreshRequested;
    /**
     * Workshop charts a DJ chose that this server is still receiving, through "Add to server" or into the cache, one
     * per DJ. The transfer itself is {@code ServerBeatmapTransfers}; this only remembers which playlist the chart goes
     * into once the set is here, so the DJ does not have to pick it a second time after the upload.
     */
    private static final Map<UUID, PendingWorkshopAdd> PENDING_WORKSHOP_ADDS = new HashMap<>();
    /**
     * How long a chosen chart waits for its set. A refused upload reports back ({@link #workshopTransferFailed}), but
     * one the client never starts does not, and a transfer that stalls is dropped by {@code ServerBeatmapTransfers} after a minute of silence, so ten minutes
     * covers a large package on a slow link without keeping a stale wish forever.
     */
    private static final long WORKSHOP_ADD_TIMEOUT_TICKS = 20L * 60L * 10L;

    /**
     * Charts of sets a DJ brought into the server's cache, by chart id. Like a vote's cached set they stay out of the
     * catalogue that battles and other players browse, but a DJ panel has to find them by id for as long as they
     * are in a playlist, so they are remembered here until the server stops ({@link #trackById}).
     */
    private static final Map<String, MusicBattleTracks.Track> CACHED_WORKSHOP_TRACKS = new HashMap<>();

    private record PendingWorkshopAdd(ResourceKey<Level> dimension, BlockPos panelPos, long setId,
                                      String contentSha256, String chartKey, long expiresAtTick) {
    }

    private DjPlaybackManager() {
    }

    /** Reads only prepared live sessions; sampling never prepares audio or sends packets. */
    static void appendMusicMobSources(ServerLevel level, List<MusicMobRhythm.Source> sources) {
        for (Map.Entry<UUID, SessionAudience> entry : AUDIENCES.entrySet()) {
            SessionAudience session = entry.getValue();
            if (session.level != level || !level.hasChunkAt(session.panelPos)
                    || !(level.getBlockEntity(session.panelPos) instanceof DjPanelBlockEntity panel)
                    || !panel.isPlaying() || !entry.getKey().equals(panel.getPlaybackId())) {
                continue;
            }
            MusicBattleTracks.Track track = session.trackSnapshot;
            if (!track.id().equals(panel.getActiveTrackId())
                    || !trackById(track.id()).filter(track::equals).isPresent()) {
                continue;
            }
            float speed = normalizeSpeed(panel.getPlaybackSpeed());
            int elapsed = panel.getPlaybackPositionTicks(level.getGameTime());
            if (elapsed >= scaledTick(track.durationTicks(), speed)) {
                continue;
            }
            MusicMobRhythm.Beat beat = new MusicMobRhythm.Beat(
                    entry.getKey(), elapsed, track.bpm() * (double) speed);
            for (BlockPos speakerPos : panel.getLinkedSpeakers()) {
                if (validLinkedSpeaker(level, panel, speakerPos)
                        && level.getBlockEntity(speakerPos) instanceof SpeakerBlockEntity speaker) {
                    MusicMobRhythm.addSource(sources, beat,
                            Vec3.atCenterOf(speakerPos).add(0.0D, 0.65D, 0.0D),
                            speaker.getSoundRadius(), track.volume() * speaker.getVolume());
                }
            }
        }
    }

    public static void register(ServerLevel level, BlockPos pos) {
        LOADED_PANELS.computeIfAbsent(level, ignored -> new HashSet<>()).add(pos.immutable());
    }

    public static void unregister(ServerLevel level, BlockPos pos) {
        var cameraSelections = EXPLICIT_CAMERAS.get(level);
        if (cameraSelections != null) cameraSelections.remove(pos);
        invalidateAudioIntent(level, pos);
        SPEED_REQUEST_REVISIONS.keySet().removeIf(key -> key.dimension.equals(level.dimension())
                && key.panelPos == pos.asLong());
        Set<BlockPos> panels = LOADED_PANELS.get(level);
        if (panels != null) {
            panels.remove(pos);
            if (panels.isEmpty()) {
                LOADED_PANELS.remove(level);
            }
        }
        DjAudiencePreloadQueue.cancelPanel(level.dimension(), pos);
        stopAudienceForPanel(level, pos);
    }

    public static void destroyPanel(ServerLevel level, BlockPos pos) {
        if (level.getBlockEntity(pos) instanceof DjPanelBlockEntity panel) {
            UUID playbackId = panel.getPlaybackId();
            if (playbackId != null) {
                stopAudience(level.getServer(), playbackId);
            }
            for (BlockPos speakerPos : panel.getLinkedSpeakers()) {
                if (level.hasChunkAt(speakerPos)
                        && level.getBlockEntity(speakerPos) instanceof SpeakerBlockEntity speaker
                        && speaker.isControlledBy(pos)) {
                    speaker.setDjController(null);
                }
            }
            panel.stopPlayback();
        }
        unregister(level, pos);
    }

    public static void openPanel(ServerPlayer player, BlockPos panelPos) {
        if (!validPanelAccess(player, panelPos)
                || !(player.level().getBlockEntity(panelPos) instanceof DjPanelBlockEntity panel)) {
            return;
        }
        pruneConnections(player.serverLevel(), panel);
        // Old DJ snapshots had no chart half. Capture it once, before an editor can enable it.
        for (DjPlaylistEntry entry : List.copyOf(panel.getPlaylist())) {
            if (entry.fxMode() == DjPlaylistEntry.FxMode.DJ && !entry.mixerChartCaptured()) {
                trackById(entry.trackId()).ifPresent(track ->
                        panel.replacePlaylistEntry(new DjPlaylistEntry(entry.entryId(), entry.trackId(),
                                entry.startSourceTicks(), entry.fxMode(), entry.effects(), entry.mixer(), track.effects(), true)));
            }
        }
        trackById(panel.getSelectedTrackId()).ifPresent(DjPlaybackManager::warmSpeedVariants);
        List<OpenDjPanelPayload.SpeakerEntry> speakers = panel.getLinkedSpeakers().stream()
                .filter(player.serverLevel()::hasChunkAt)
                .map(pos -> player.serverLevel().getBlockEntity(pos) instanceof SpeakerBlockEntity speaker
                        && speaker.isControlledBy(panelPos)
                        ? new OpenDjPanelPayload.SpeakerEntry(pos, speaker.getSoundRadius(), speaker.getVolume(),
                                speaker.getLowGainDb(), speaker.getMidGainDb(), speaker.getHighGainDb()) : null)
                .filter(java.util.Objects::nonNull)
                .sorted(Comparator.comparingLong(entry -> entry.pos().asLong()))
                .toList();
        int activeSpeakerIndex = -1;
        BlockPos selectedSpeakerPos = panel.getSelectedSpeakerPos();
        for (int index = 0; index < speakers.size(); index++) {
            if (speakers.get(index).pos().equals(selectedSpeakerPos)) {
                activeSpeakerIndex = index;
                break;
            }
        }
        if (activeSpeakerIndex < 0 && !speakers.isEmpty()) {
            activeSpeakerIndex = 0;
            panel.selectSpeaker(speakers.getFirst().pos());
        }
        List<OpenDjPanelPayload.CameraEntry> cameras = panel.getLinkedCameras().stream()
                .sorted(Comparator.comparingLong(BlockPos::asLong))
                .map(pos -> {
                    if (player.serverLevel().hasChunkAt(pos)
                            && player.serverLevel().getBlockEntity(pos) instanceof BattleCameraBlockEntity camera) {
                        Vec3 position = camera.getCameraPosition();
                        return new OpenDjPanelPayload.CameraEntry(pos, true,
                                position.x, position.y, position.z,
                                camera.getCameraYaw(), camera.getCameraPitch(), camera.getCameraFov());
                    }
                    Vec3 position = Vec3.atCenterOf(pos);
                    return new OpenDjPanelPayload.CameraEntry(pos, false,
                            position.x, position.y, position.z, 0.0F, 0.0F,
                            BattleCameraBlockEntity.DEFAULT_FOV);
                })
                .toList();
        int activeCameraIndex = -1;
        BlockPos selectedCameraPos = panel.getSelectedCameraPos();
        for (int index = 0; index < cameras.size(); index++) {
            if (cameras.get(index).pos().equals(selectedCameraPos)) {
                activeCameraIndex = index;
                break;
            }
        }
        List<MusicBattleTracks.Track> availableTracks = djTracks();
        String selectedTrackId = panel.getSelectedTrackId();
        if (trackById(selectedTrackId).isEmpty()) {
            selectedTrackId = trackById(panel.getActiveTrackId()).isPresent()
                    ? panel.getActiveTrackId()
                    : availableTracks.isEmpty() ? "" : availableTracks.getFirst().id();
            panel.selectTrack(selectedTrackId);
        }
        float speed = normalizeSpeed(panel.getPlaybackSpeed());
        PendingAudioIntent pendingAudio = PENDING_AUDIO_INTENTS.get(panelKey(player.serverLevel(), panelPos));
        boolean validPendingAudio = pendingAudio != null && pendingAudio.matches(panel);
        float displayedSpeed = validPendingAudio
                ? normalizeSpeed(pendingAudio.requestedSpeed) : speed;
        Optional<MusicBattleTracks.Track> loadedTrack = trackById(panel.getActiveTrackId());
        int duration = loadedTrack.map(track -> scaledTick(track.durationTicks(), speed)).orElse(0);
        int position = Mth.clamp(panel.getPlaybackPositionTicks(player.level().getGameTime()), 0, Math.max(0, duration));
        if (validPendingAudio && pendingAudio.kind == AudioIntentKind.SPEED_CHANGE) {
            int sourceDuration = loadedTrack.map(MusicBattleTracks.Track::durationTicks).orElse(0);
            int sourcePosition = Math.max(0, Math.round(position * speed));
            duration = scaledTick(sourceDuration, displayedSpeed);
            position = Mth.clamp(Math.round(sourcePosition / displayedSpeed), 0, Math.max(0, duration));
        }
        long speedResponseRevision = SPEED_REQUEST_REVISIONS.getOrDefault(
                new SpeedRequestKey(player.getUUID(), player.level().dimension(), panelPos.asLong()), 0L);
        SessionAudience monitorSession = AUDIENCES.get(panel.getPlaybackId());
        boolean monitorActive = panel.isPlaying() && monitorSession != null
                && monitorSession.level == player.serverLevel() && monitorSession.panelPos.equals(panelPos)
                && loadedTrack.filter(monitorSession.trackSnapshot::equals).isPresent()
                && panel.getPlaybackPositionTicks(player.level().getGameTime())
                < scaledTick(monitorSession.trackSnapshot.durationTicks(), speed);
        DjAudienceTargets monitorTargets = monitorActive
                ? collectDjAudienceTargets(player.serverLevel(), validSpeakers(player.serverLevel(), panel),
                        monitorSession.audiblePlayers)
                : new DjAudienceTargets(List.of(), List.of());
        if (panel.isPlaybackPaused() && loadedTrack.isPresent()) {
            // Pause retains the visual playhead but intentionally removes the audio session.
            // Reconstruct its speaker-scoped visual targets even when first opened while paused.
            List<BlockPos> pausedSpeakers = validSpeakers(player.serverLevel(), panel);
            Set<UUID> pausedPlayerIds = new HashSet<>();
            for (ServerPlayer candidate : player.serverLevel().players()) {
                if (candidate.isAlive() && !BattleManager.hasActiveBattleContext(candidate.getUUID())
                        && loudestSource(player.serverLevel(), candidate, pausedSpeakers, null) != null) {
                    pausedPlayerIds.add(candidate.getUUID());
                }
            }
            monitorTargets = collectDjAudienceTargets(player.serverLevel(), pausedSpeakers, pausedPlayerIds);
        }
        PacketDistributor.sendToPlayer(player, new OpenDjPanelPayload(
                panelPos,
                djSummaries(availableTracks),
                speakers,
                activeSpeakerIndex,
                panel.isEditingAllSpeakers(),
                cameras,
                activeCameraIndex,
                panel.isPlaying(),
                panel.getActiveTrackId(),
                selectedTrackId,
                displayedSpeed,
                speedResponseRevision,
                panel.isPlaybackPaused(),
                position,
                duration,
                panel.isDspEnabled(),
                panel.isReverbEnabled(), panel.getReverbWet(), panel.getReverbRoom(), panel.getReverbDamping(),
                panel.isEchoEnabled(), panel.getEchoWet(), panel.getEchoTimeMs(), panel.getEchoFeedback(),
                panel.isLowPassEnabled(), panel.getLowPassCutoffHz(),
                panel.isTrackVisualFxEnabled(), panel.isDjVisualFxEnabled(), panel.getDjVisualEffects(),
                panel.getPlaylist(), panel.getActivePlaylistEntryId(),
                panel.isPlaylistAutoAdvance(), panel.getPlaylistRepeat().ordinal(),
                panel.getPlaylistRevision(),
                loadedTrack.filter(track -> panel.isPlaying() || panel.isPlaybackPaused())
                        .map(track -> composedEffects(panel, track)).orElse(List.of()),
                loadedTrack.map(track -> effectiveBpm(track.bpm(), speed)).orElse(120),
                panel.getPlaybackId(), speed,
                Math.max(0, panel.getPlaybackPositionTicks(player.level().getGameTime())),
                monitorTargets.playerEntityIds(), monitorTargets.mobEntityIds(),
                availableTracks.size() > OpenDjPanelPayload.MAX_TRACKS
        ));
    }

    /**
     * The picker's rows with the workshop identity filled in (spec 06 section 9.4), withdrawn server beatmaps left
     * out, capped at what the payload carries.
     */
    private static List<ru.gora.musicbattle.data.TrackSummary> djSummaries(List<MusicBattleTracks.Track> tracks) {
        List<ru.gora.musicbattle.data.TrackSummary> summaries = new ArrayList<>();
        for (MusicBattleTracks.Track track : tracks) {
            if (summaries.size() >= OpenDjPanelPayload.MAX_TRACKS) {
                break;
            }
            if (ru.gora.musicbattle.server.workshop.ServerBeatmaps.hiddenFromVotes(track)) {
                continue;
            }
            summaries.add(ru.gora.musicbattle.server.workshop.ServerBeatmaps.summary(track));
        }
        return List.copyOf(summaries);
    }

    /** Returns a transform only for a camera explicitly linked to this nearby panel. */
    public static void requestCameraPreview(ServerPlayer player, BlockPos panelPos, BlockPos cameraPos) {
        if (!validPanelAccess(player, panelPos)
                || !(player.level().getBlockEntity(panelPos) instanceof DjPanelBlockEntity panel)
                || cameraPos == null
                || !panel.getLinkedCameras().contains(cameraPos)) {
            return;
        }
        long now = player.level().getGameTime();
        PreviewRequestKey key = new PreviewRequestKey(player.getUUID(), player.level().dimension(), panelPos.asLong());
        if (now < CAMERA_PREVIEW_REQUEST_AFTER.getOrDefault(key, 0L)) {
            return;
        }
        CAMERA_PREVIEW_REQUEST_AFTER.put(key, now + 2L);

        boolean available = validLinkedCamera(player.serverLevel(), panel, cameraPos);
        Vec3 position = Vec3.atCenterOf(cameraPos);
        float yaw = 0.0F;
        float pitch = 0.0F;
        float fov = BattleCameraBlockEntity.DEFAULT_FOV;
        if (available) {
            BattleCameraBlockEntity camera = (BattleCameraBlockEntity) player.level().getBlockEntity(cameraPos);
            position = camera.getCameraPosition();
            yaw = camera.getCameraYaw();
            pitch = camera.getCameraPitch();
            fov = camera.getCameraFov();
        }
        PacketDistributor.sendToPlayer(player, new DjCameraPreviewPayload(
                panelPos, cameraPos, available, now,
                position.x, position.y, position.z, yaw, pitch, fov));
    }

    /** Sends only explicitly requested, visible DJ thumbnails after validating panel access. */
    public static void requestTrackPreviews(ServerPlayer player, BlockPos panelPos, List<String> trackIds) {
        if (!validPanelAccess(player, panelPos) || trackIds == null || trackIds.isEmpty()) {
            return;
        }
        long now = player.level().getGameTime();
        PreviewRequestKey key = new PreviewRequestKey(player.getUUID(), player.level().dimension(), panelPos.asLong());
        if (now < PREVIEW_REQUEST_AFTER.getOrDefault(key, 0L)) {
            return;
        }
        PREVIEW_REQUEST_AFTER.put(key, now + 2L);
        Set<String> unique = new HashSet<>();
        int accepted = 0;
        for (String trackId : trackIds) {
            if (accepted >= 8 || trackId == null || !unique.add(trackId)) {
                continue;
            }
            Optional<MusicBattleTracks.Track> track = trackById(trackId);
            if (track.isEmpty() || track.get().previewFile().isBlank()) {
                continue;
            }
            Optional<Path> path = MusicBattleTracks.previewPath(track.get());
            if (path.isEmpty() || !validPreviewAsset(path.get())) {
                continue;
            }
            accepted++;
            sendPreviewAssetAsync(player, player.serverLevel(), panelPos, track.get(), path.get());
        }
    }

    public static void handleAction(ServerPlayer player, ConfigureDjPanelPayload payload) {
        BlockPos panelPos = payload.panelPos();
        if (!validPanelAccess(player, panelPos)
                || !(player.level().getBlockEntity(panelPos) instanceof DjPanelBlockEntity panel)) {
            return;
        }
        ServerLevel level = player.serverLevel();
        pruneConnections(level, panel);
        boolean refreshScreen = true;
        switch (payload.action()) {
            case LIVE_SPEAKER -> {
                refreshScreen = false;
                if (allowLiveControl(player, panelPos, false)) {
                    configureSpeakersLive(player, panel, payload);
                } else {
                    deferLiveControl(player, panelPos, false, payload);
                }
            }
            case UNLINK_SPEAKER -> unlinkSpeaker(player, panel, payload.targetPos());
            case PLAY_RESUME -> {
                startPlayback(player, panel, payload.trackId(), false,
                        payload.seekTicks(), payload.playbackSpeed(), null);
                MusicBattleAdvancements.grant(player, "behind_the_decks");
            }
            case PAUSE -> {
                pausePlayback(level, panel);
            }
            case RESTART -> {
                startPlayback(player, panel, payload.trackId(), true, 0, payload.playbackSpeed(), null);
                MusicBattleAdvancements.grant(player, "behind_the_decks");
            }
            case SEEK -> {
                refreshScreen = false;
                seekPlayback(player, panel, payload.trackId(), payload.seekTicks(), payload.playbackSpeed());
            }
            case REFRESH -> { }
            case LIVE_PANEL_FX -> {
                refreshScreen = false;
                if (allowLiveControl(player, panelPos, true)) {
                    configurePanelFxLive(panel, payload);
                } else {
                    deferLiveControl(player, panelPos, true, payload);
                }
            }
            case SELECT_CAMERA -> {
                // Camera cards and the quick arrows already update the local
                // selection. Reopening DjPanelScreen here would tear down the
                // gallery after its very first click.
                refreshScreen = false;
                selectCamera(player, panel, payload.targetPos());
            }
            case UNLINK_CAMERA -> unlinkCamera(player, panel, payload.targetPos());
            case SELECT_SPEAKER -> {
                if (validLinkedSpeaker(level, panel, payload.targetPos())) {
                    panel.selectSpeaker(payload.targetPos());
                }
            }
            case SET_EDIT_ALL -> panel.setEditingAllSpeakers(payload.applyToAll());
            case SELECT_TRACK -> {
                refreshScreen = false;
                Optional<MusicBattleTracks.Track> selected = trackById(payload.trackId());
                if (selected.isPresent()) {
                    invalidatePlaybackStartIntent(level, panel.getBlockPos());
                    panel.selectTrack(selected.get().id());
                    warmSpeedVariants(selected.get());
                }
            }
            case SET_SPEED -> {
                // A first-time tempo variant is prepared asynchronously. Keep
                // the optimistic client state until the completion callback
                // sends the authoritative value instead of immediately
                // repainting the old speed over the button.
                refreshScreen = false;
                SPEED_REQUEST_REVISIONS.put(new SpeedRequestKey(player.getUUID(), level.dimension(),
                        panelPos.asLong()), Math.max(0L, payload.requestRevision()));
                setPlaybackSpeed(player, panel, payload.playbackSpeed());
            }
            case APPLY_VISUAL_FX -> {
                // The visual editor uses this action as live preview. Reopening
                // the parent screen for every draft would close the editor.
                refreshScreen = false;
                panel.setVisualEffects(
                        payload.trackVisualFxEnabled(), payload.djVisualFxEnabled(), payload.djVisualEffects(),
                        panel.getPlaybackPositionTicks(level.getGameTime()));
                requestAudienceRefresh();
            }
            case PRELOAD_AUDIENCE -> {
                refreshScreen = false;
                requestAudiencePreload(player, panel, payload.trackId());
            }
            case APPLY_AUDIO_PRESET -> {
                discardDeferredLiveControl(player, panelPos, false);
                discardDeferredLiveControl(player, panelPos, true);
                applyAudioPreset(player, panel, payload);
            }
            case RESET_SPEAKERS -> {
                discardDeferredLiveControl(player, panelPos, false);
                resetSpeakers(player, panel, payload);
            }
        }
        if (refreshScreen) {
            openPanel(player, panelPos);
        }
    }

    public static void handlePlaylistAction(ServerPlayer player, ConfigureDjPlaylistPayload payload) {
        BlockPos panelPos = payload.panelPos();
        if (!validPanelAccess(player, panelPos)
                || !(player.level().getBlockEntity(panelPos) instanceof DjPanelBlockEntity panel)) {
            return;
        }
        ServerLevel level = player.serverLevel();
        pruneConnections(level, panel);
        switch (payload.action()) {
            // A queued row follows the rack, and is composed from the track's own chart effects when it plays.
            // It used to be frozen to a copy of the chart taken at the moment it was queued, which is why a DJ's
            // live edits were thrown away the instant the next song began: the row simply played its own old copy.
            case ADD -> trackById(payload.trackId()).ifPresent(track ->
                    panel.addPlaylistEntry(track.id(), clampSourceCue(track, payload.startSourceTicks())));
            case REMOVE -> {
                invalidatePlaylistIntent(level, panelPos, payload.entryId());
                panel.removePlaylistEntry(payload.entryId());
            }
            case MOVE -> panel.movePlaylistEntry(payload.entryId(), payload.targetIndex());
            case CLEAR -> {
                invalidatePlaylistIntent(level, panelPos, null);
                panel.clearPlaylist();
            }
            case SET_CUE -> {
                DjPlaylistEntry entry = panel.findPlaylistEntry(payload.entryId());
                if (entry != null) {
                    trackById(entry.trackId()).ifPresent(track -> {
                        int sourceCue = clampSourceCue(track, payload.startSourceTicks());
                        panel.setPlaylistEntryCue(entry.entryId(), sourceCue);
                        PendingAudioIntent pending = PENDING_AUDIO_INTENTS.get(panelKey(level, panelPos));
                        if (pending != null && entry.entryId().equals(pending.playlistEntryId)
                                && pending.kind == AudioIntentKind.PLAYBACK_START && pending.matches(panel)) {
                            float speed = normalizeSpeed(pending.requestedSpeed);
                            queuePlaybackStart(level, panel, track, false,
                                    Math.max(0, Math.round(sourceCue / speed)), speed,
                                    player, false, entry.entryId());
                        }
                    });
                }
            }
            case PLAY_ENTRY -> {
                DjPlaylistEntry entry = panel.findPlaylistEntry(payload.entryId());
                if (entry != null) {
                    trackById(entry.trackId()).ifPresent(track -> {
                        float speed = normalizeSpeed(panel.getPlaybackSpeed());
                        int runtimeStart = Math.max(0, Math.round(
                                clampSourceCue(track, entry.startSourceTicks()) / speed));
                        startPlayback(player, panel, track.id(), false, runtimeStart, speed, entry.entryId());
                    });
                }
            }
            case SET_AUTO_ADVANCE -> panel.setPlaylistAutoAdvance(payload.autoAdvance());
            case SET_REPEAT -> panel.setPlaylistRepeat(
                    DjPanelBlockEntity.PlaylistRepeat.byOrdinal(payload.repeatMode()));
            case FX_TRACK, FX_DJ, FX_NONE -> {
                DjPlaylistEntry entry = panel.findPlaylistEntry(payload.entryId());
                if (entry != null) {
                    DjPlaylistEntry.FxMode mode = payload.action() == ConfigureDjPlaylistPayload.Action.FX_TRACK
                            ? DjPlaylistEntry.FxMode.TRACK : payload.action() == ConfigureDjPlaylistPayload.Action.FX_DJ
                            ? DjPlaylistEntry.FxMode.DJ : DjPlaylistEntry.FxMode.NONE;
                    List<TrackEffect> effects = mode == DjPlaylistEntry.FxMode.TRACK
                            ? trackById(entry.trackId()).map(MusicBattleTracks.Track::effects).orElse(List.of())
                            : mode == DjPlaylistEntry.FxMode.DJ ? panel.getDjVisualEffects().stream()
                            .filter(DjVisualEffectConfig::enabled).map(DjVisualEffectConfig::effect).toList() : List.of();
                    if (entry.fxMode() != mode) {
                        if (mode == DjPlaylistEntry.FxMode.DJ) {
                            var mixer = new ru.gora.musicbattle.data.DjPlaylistMixer(false, true, panel.getDjVisualEffects());
                            var chart = trackById(entry.trackId()).map(MusicBattleTracks.Track::effects).orElse(List.of());
                            panel.replacePlaylistEntry(entry.withMixer(mixer, chart));
                        } else panel.setPlaylistEntryEffects(entry.entryId(), mode, effects);
                    }
                    requestAudienceRefresh();
                }
            }
            case REFRESH -> { }
        }
        openPanel(player, panelPos);
    }

    /**
     * A track a DJ panel may play: the server catalogue first, then a Workshop chart a DJ brought into the cache. The
     * catalogue wins, so a set that has since been added to the server plays from its listed copy.
     */
    public static Optional<MusicBattleTracks.Track> trackById(String id) {
        Optional<MusicBattleTracks.Track> listed = MusicBattleTracks.byId(id);
        return listed.isPresent() || id == null ? listed : Optional.ofNullable(CACHED_WORKSHOP_TRACKS.get(id));
    }

    /** The panel's track list: the catalogue, then the cached Workshop charts the catalogue does not already hold. */
    private static List<MusicBattleTracks.Track> djTracks() {
        List<MusicBattleTracks.Track> listed = MusicBattleTracks.availableTracks();
        if (CACHED_WORKSHOP_TRACKS.isEmpty()) {
            return listed;
        }
        List<MusicBattleTracks.Track> tracks = new ArrayList<>(listed);
        for (MusicBattleTracks.Track cached : CACHED_WORKSHOP_TRACKS.values()) {
            if (MusicBattleTracks.byId(cached.id()).isEmpty()) {
                tracks.add(cached);
            }
        }
        return tracks;
    }

    /**
     * A chart of a Workshop set chosen in the DJ's Workshop picker. A set this server already holds joins the playlist
     * at once, exactly as a server track would, because it is one: its charts are in the catalogue. A set it does not
     * hold comes through "Add to server" when the DJ has map rights, so it is listed for everyone afterwards; anyone
     * else brings their installed copy into the cache the way a battle vote does, under the same server setting.
     * A refusal names its reason. Either way the chart is added once the set is here ({@link #tickPendingWorkshopAdds},
     * {@link #workshopCached}).
     */
    public static void handleWorkshopAdd(ServerPlayer player, ru.gora.musicbattle.network.DjWorkshopAddPayload payload) {
        BlockPos panelPos = payload.panelPos();
        if (!validPanelAccess(player, panelPos)
                || !(player.level().getBlockEntity(panelPos) instanceof DjPanelBlockEntity panel)) {
            return;
        }
        long setId = payload.setId();
        String chartKey = payload.chartKey();
        if (setId <= 0 || !ru.gora.musicbattle.workshop.WorkshopLayout.chartKeyValid(chartKey)) {
            refuseWorkshopAdd(player, panelPos, setId,
                    Component.translatable("music_battle.l10n.server.track_unavailable"));
            return;
        }
        if (panel.getPlaylist().size() >= DjPlaylistEntry.MAX_ENTRIES) {
            refuseWorkshopAdd(player, panelPos, setId,
                    Component.translatable("screen.music_battle.dj_playlist.workshop.full"));
            return;
        }
        Optional<ru.gora.musicbattle.server.workshop.ServerBeatmaps.Entry> held =
                ru.gora.musicbattle.server.workshop.ServerBeatmaps.bySetId(setId);
        if (held.isPresent() && held.get().withdrawn()) {
            refuseWorkshopAdd(player, panelPos, setId, Component.translatable("music_battle.workshop.error.withdrawn"));
            return;
        }
        Optional<MusicBattleTracks.Track> chart = workshopChart(setId, chartKey);
        if (chart.isPresent()) {
            // The server's copy plays even when the DJ picked another revision of the set: it is the copy every
            // listener can be sent, and swapping it would need the same rights as adding it.
            addWorkshopChart(player, panel, panelPos, chart.get());
            openPanel(player, panelPos);
            return;
        }
        String contentSha256 = payload.contentSha256() == null ? "" : payload.contentSha256();
        boolean bound = ru.gora.musicbattle.server.workshop.ServerBeatmaps.bound();
        boolean mayManage = ru.gora.musicbattle.server.workshop.WorkshopPermissions.canManageServerBeatmaps(player);
        boolean addToServer = bound && mayManage;
        if (!addToServer && !MusicBattleServerConfig.allowPlayerMaps()) {
            refuseWorkshopAdd(player, panelPos, setId, Component.translatable(mayManage
                    ? "music_battle.workshop.error.server_beatmaps_disabled"
                    : "screen.music_battle.dj_playlist.workshop.no_permission"));
            return;
        }
        if (payload.revision() <= 0 || !contentSha256.matches("[0-9a-f]{64}")) {
            refuseWorkshopAdd(player, panelPos, setId,
                    Component.translatable("music_battle.workshop.error.invalid_package"));
            return;
        }
        if (!addToServer) {
            Optional<MusicBattleTracks.Track> cached =
                    ru.gora.musicbattle.server.workshop.ServerBeatmapTransfers.findChart(setId, contentSha256, chartKey);
            if (cached.isPresent()) {
                // Cached earlier for a battle vote or another DJ: no transfer at all, as a vote would do.
                rememberCachedCharts(setId, contentSha256);
                addWorkshopChart(player, panel, panelPos, cached.get());
                openPanel(player, panelPos);
                return;
            }
            if (held.isPresent()) {
                // The listed copy lacks this chart; a second revision beside it would play under the same id.
                refuseWorkshopAdd(player, panelPos, setId, Component.translatable(
                        held.get().contentSha256().equals(contentSha256)
                                ? "music_battle.l10n.server.track_unavailable"
                                : "music_battle.l10n.server.installed_vote_other_revision"));
                return;
            }
        }
        PENDING_WORKSHOP_ADDS.put(player.getUUID(), new PendingWorkshopAdd(player.level().dimension(), panelPos,
                setId, contentSha256, chartKey, player.server.getTickCount() + WORKSHOP_ADD_TIMEOUT_TICKS));
        if (addToServer) {
            PacketDistributor.sendToPlayer(player, new ru.gora.musicbattle.network.DjWorkshopAddResultPayload(panelPos,
                    setId, ru.gora.musicbattle.network.DjWorkshopAddResultPayload.UPLOAD, Component.empty()));
            return;
        }
        // The answer goes first so the client knows which installed set to repack when the transfer asks for it;
        // both travel the same connection and are handled in order.
        PacketDistributor.sendToPlayer(player, new ru.gora.musicbattle.network.DjWorkshopAddResultPayload(panelPos,
                setId, ru.gora.musicbattle.network.DjWorkshopAddResultPayload.SEND_INSTALLED, Component.empty()));
        ru.gora.musicbattle.server.workshop.ServerBeatmapTransfers.djInstalled(player, setId, payload.revision(),
                contentSha256);
    }

    /**
     * A DJ's set arrived in the cache: its charts become playable on DJ panels and the chosen one joins the playlist.
     * Called by {@code ServerBeatmapTransfers} on the server thread.
     */
    public static void workshopCached(ServerPlayer player, long setId, String contentSha256) {
        rememberCachedCharts(setId, contentSha256);
        PendingWorkshopAdd wish = PENDING_WORKSHOP_ADDS.get(player.getUUID());
        if (wish == null || wish.setId() != setId) {
            return;
        }
        PENDING_WORKSHOP_ADDS.remove(player.getUUID());
        completeWorkshopAdd(player.server, player, wish);
    }

    /**
     * The transfer of a set a DJ is waiting for was refused or given up. Without this the playlist line kept saying
     * the map was on its way until it faded, and the DJ could neither retry nor tell what went wrong. Called by
     * {@code ServerBeatmapTransfers} for every refusal; one about a set no DJ pick waits for is ignored.
     */
    public static void workshopTransferFailed(ServerPlayer player, long setId, Component reason) {
        PendingWorkshopAdd wish = player == null ? null : PENDING_WORKSHOP_ADDS.get(player.getUUID());
        if (wish == null || wish.setId() != setId) {
            return;
        }
        PENDING_WORKSHOP_ADDS.remove(player.getUUID());
        refuseWorkshopAdd(player, wish.panelPos(), setId, reason);
    }

    private static void rememberCachedCharts(long setId, String contentSha256) {
        for (MusicBattleTracks.Track track
                : ru.gora.musicbattle.server.workshop.ServerBeatmapTransfers.cachedCharts(setId, contentSha256)) {
            CACHED_WORKSHOP_TRACKS.put(track.id(), track);
        }
    }

    /**
     * Adds every chosen chart whose set has arrived since. Nothing in the transfer calls back here, so the set
     * appearing in the catalogue is the signal: that also covers a set another player added in the meantime.
     */
    private static void tickPendingWorkshopAdds(MinecraftServer server) {
        long now = server.getTickCount();
        var iterator = PENDING_WORKSHOP_ADDS.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, PendingWorkshopAdd> pending = iterator.next();
            PendingWorkshopAdd wish = pending.getValue();
            if (now > wish.expiresAtTick()) {
                iterator.remove();
                continue;
            }
            if (!ru.gora.musicbattle.server.workshop.ServerBeatmaps.has(wish.setId(), wish.contentSha256())) {
                continue;
            }
            iterator.remove();
            ServerPlayer player = server.getPlayerList().getPlayer(pending.getKey());
            if (player != null) {
                completeWorkshopAdd(server, player, wish);
            }
        }
    }

    /** Puts the chosen chart of a set that has just arrived into the playlist it was picked for. */
    private static void completeWorkshopAdd(MinecraftServer server, ServerPlayer player, PendingWorkshopAdd wish) {
        ServerLevel level = server.getLevel(wish.dimension());
        Optional<MusicBattleTracks.Track> chart = workshopChart(wish.setId(), wish.chartKey());
        if (level == null) {
            return;
        }
        if (chart.isEmpty()) {
            refuseWorkshopAdd(player, wish.panelPos(), wish.setId(),
                    Component.translatable("music_battle.l10n.server.track_unavailable"));
            return;
        }
        // The panel's chunk is not loaded again for this: a DJ who walked so far away that it unloaded hears why.
        if (!level.hasChunkAt(wish.panelPos())
                || !(level.getBlockEntity(wish.panelPos()) instanceof DjPanelBlockEntity panel)) {
            refuseWorkshopAdd(player, wish.panelPos(), wish.setId(),
                    Component.translatable("screen.music_battle.dj_playlist.workshop.panel_gone"));
            return;
        }
        if (panel.getPlaylist().size() >= DjPlaylistEntry.MAX_ENTRIES) {
            refuseWorkshopAdd(player, wish.panelPos(), wish.setId(),
                    Component.translatable("screen.music_battle.dj_playlist.workshop.full"));
            return;
        }
        addWorkshopChart(player, panel, wish.panelPos(), chart.get());
    }

    /** A Workshop chart in the server catalogue or a DJ's cache, unless its set is withdrawn. */
    private static Optional<MusicBattleTracks.Track> workshopChart(long setId, String chartKey) {
        String chartId = ru.gora.musicbattle.workshop.WorkshopLayout.chartId(setId, chartKey);
        return trackById(chartId)
                .filter(track -> !ru.gora.musicbattle.server.workshop.ServerBeatmaps.hiddenFromVotes(track));
    }

    /** The same row the playlist's own Add makes, so the chart behaves as any server track from here on. */
    private static void addWorkshopChart(ServerPlayer player, DjPanelBlockEntity panel, BlockPos panelPos,
                                         MusicBattleTracks.Track track) {
        panel.addPlaylistEntry(track.id(), 0);
        PacketDistributor.sendToPlayer(player, new ru.gora.musicbattle.network.DjWorkshopAddResultPayload(panelPos,
                track.workshopSetId(), ru.gora.musicbattle.network.DjWorkshopAddResultPayload.ADDED,
                Component.translatable("screen.music_battle.dj_playlist.workshop.added", track.title())));
    }

    private static void refuseWorkshopAdd(ServerPlayer player, BlockPos panelPos, long setId, Component reason) {
        PacketDistributor.sendToPlayer(player, new ru.gora.musicbattle.network.DjWorkshopAddResultPayload(panelPos,
                Math.max(0L, setId), ru.gora.musicbattle.network.DjWorkshopAddResultPayload.REFUSED, reason));
    }

    public static void editPlaylistEffect(ServerPlayer player, ru.gora.musicbattle.network.EditDjPlaylistEffectPayload payload) {
        if (!validPanelAccess(player, payload.panelPos())
                || !(player.level().getBlockEntity(payload.panelPos()) instanceof DjPanelBlockEntity panel)) return;
        if (!payload.entryId().equals(panel.getActivePlaylistEntryId())) {
            // The queue moved on between the click and the packet arriving. Saying so, and sending the panel back,
            // is what lets the editor rebase; dropping it silently is how an edit disappeared without a word.
            player.displayClientMessage(Component.translatable("screen.music_battle.dj_playlist.fx_conflict"), false);
            openPanel(player, payload.panelPos());
            return;
        }
        DjPlaylistEntry entry = panel.findPlaylistEntry(payload.entryId());
        if (entry == null || entry.fxMode() == DjPlaylistEntry.FxMode.INHERIT) return;
        DjPlaylistEntry edited = entry.editEffect(payload.index(), payload.expectedMode(), payload.expectedCount(), payload.expected(), payload.effect());
        if (edited == null) {
            player.displayClientMessage(Component.translatable("screen.music_battle.dj_playlist.fx_conflict"), false);
            openPanel(player, payload.panelPos());
            return;
        }
        panel.replacePlaylistEntry(edited);
        requestAudienceRefresh();
        // Refresh the backing panel without replacing any nested editor.
        openPanel(player, payload.panelPos());
    }

    public static void editPlaylistMixer(ServerPlayer player, ru.gora.musicbattle.network.EditDjPlaylistMixerPayload payload) {
        if (!validPanelAccess(player, payload.panelPos())
                || !(player.level().getBlockEntity(payload.panelPos()) instanceof DjPanelBlockEntity panel)
                || !payload.entryId().equals(panel.getActivePlaylistEntryId())) return;
        DjPlaylistEntry entry = panel.findPlaylistEntry(payload.entryId());
        DjPlaylistEntry edited = entry == null ? null : entry.editMixer(payload.expected(), payload.replacement());
        if (edited == null) {
            player.displayClientMessage(Component.translatable("screen.music_battle.dj_playlist.fx_conflict"), false);
        } else {
            panel.replacePlaylistEntry(edited);
            // The rack keeps what was last dialled in, so every row that follows the rack plays it too. Without
            // this the DJ's work lived only on the row that happened to be playing when they made it.
            panel.setVisualEffects(payload.replacement().trackFxEnabled(), payload.replacement().djFxEnabled(),
                    payload.replacement().effects(), panel.getPlaybackPositionTicks(player.serverLevel().getGameTime()));
            requestAudienceRefresh();
        }
        openPanel(player, payload.panelPos());
    }

    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        processDeferredLiveControls(server);
        processAssetTransfers(server);
        DjAudiencePreloadQueue.tick(server);
        tickDjCrowd(server);
        if (!PENDING_WORKSHOP_ADDS.isEmpty() && server.getTickCount() % 10 == 0) {
            tickPendingWorkshopAdds(server);
        }
        tickPreloadPlans(server);
        boolean fullAudienceUpdate = server.getTickCount() % 5 == 0 || audienceRefreshRequested;
        audienceRefreshRequested = false;
        if (!fullAudienceUpdate) {
            // Audio/source arbitration is intentionally batched, but camera
            // control is tiny and must feel live. Broadcast only changed
            // transforms on the four ticks between full audience updates.
            updateLiveAudienceCameras(server);
            return;
        }
        for (ServerLevel level : server.getAllLevels()) {
            Map<BlockPos, Integer> activeSpeakers = new HashMap<>();
            List<ActiveDjSession> sessions = new ArrayList<>();
            for (BlockPos panelPos : loadedPanelPositions(level).stream()
                    .sorted(Comparator.comparingLong(BlockPos::asLong)).toList()) {
                if (!(level.getBlockEntity(panelPos) instanceof DjPanelBlockEntity panel)) {
                    continue;
                }
                pruneConnections(level, panel);
                ActiveDjSession session = preparePlayback(level, panel, activeSpeakers);
                if (session != null) {
                    sessions.add(session);
                }
            }

            Map<UUID, DjClaim> claims = new HashMap<>();
            Map<UUID, DjClaim> currentClaims = new HashMap<>();
            for (ServerPlayer player : level.players()) {
                // A real battle always wins over ambient DJ playback. This also keeps
                // participant/spectator FX sessions from being overwritten in overlap zones.
                if (BattleManager.hasActiveBattleContext(player.getUUID())) {
                    continue;
                }
                for (ActiveDjSession session : sessions) {
                    SessionAudience currentAudience = AUDIENCES.get(session.panel().getPlaybackId());
                    AudioSourceKey currentSource = currentAudience == null
                            ? null : currentAudience.sources.get(player.getUUID());
                    AudioSource source = loudestSource(level, player, session.speakers(), currentSource);
                    if (source == null) {
                        continue;
                    }
                    double distance = player.distanceToSqr(source.position());
                    // Speaker gain decides among sources of one installation;
                    // track gain must also participate when two DJ panels overlap.
                    float audibleGain = Math.max(0.0F, session.track().volume()) * source.effectiveVolume();
                    DjClaim candidate = new DjClaim(session, source, distance, audibleGain);
                    DjClaim previous = claims.get(player.getUUID());
                    if (previous == null || audibleGain > previous.audibleGain() + 0.0001F
                            || Math.abs(audibleGain - previous.audibleGain()) <= 0.0001F
                            && (distance < previous.distanceSquared() - 0.0001D
                            || Math.abs(distance - previous.distanceSquared()) <= 0.0001D
                            && session.panel().getBlockPos().asLong() < previous.session().panel().getBlockPos().asLong())) {
                        claims.put(player.getUUID(), candidate);
                    }
                    if (currentAudience != null && currentAudience.audiblePlayers.contains(player.getUUID())) {
                        DjClaim current = currentClaims.get(player.getUUID());
                        if (current == null || audibleGain > current.audibleGain() + 0.0001F
                                || Math.abs(audibleGain - current.audibleGain()) <= 0.0001F
                                && session.panel().getBlockPos().asLong() < current.session().panel().getBlockPos().asLong()) {
                            currentClaims.put(player.getUUID(), candidate);
                        }
                    }
                }
            }
            // Retain the panel that was audible on the preceding tick until a
            // competitor becomes at least ~5% louder. This prevents restarts at
            // an overlap seam while still guaranteeing loudness priority.
            for (Map.Entry<UUID, DjClaim> entry : currentClaims.entrySet()) {
                DjClaim best = claims.get(entry.getKey());
                DjClaim current = entry.getValue();
                if (best != null && current.audibleGain() >= best.audibleGain() * 0.95F) {
                    claims.put(entry.getKey(), current);
                }
            }
            for (ActiveDjSession session : sessions) {
                Map<UUID, AudioSource> listeners = new HashMap<>();
                for (Map.Entry<UUID, DjClaim> entry : claims.entrySet()) {
                    if (entry.getValue().session() == session) {
                        listeners.put(entry.getKey(), entry.getValue().source());
                    }
                }
                updateAudience(level, session, listeners);
            }
            SpeakerNetwork.setDjActiveStates(level, activeSpeakers);
        }
    }

    private static void updateLiveAudienceCameras(MinecraftServer server) {
        for (Map.Entry<UUID, SessionAudience> entry : List.copyOf(AUDIENCES.entrySet())) {
            UUID playbackId = entry.getKey();
            SessionAudience session = entry.getValue();
            ServerLevel level = session.level;
            if (!(level.getBlockEntity(session.panelPos) instanceof DjPanelBlockEntity panel)
                    || !panel.isPlaying()
                    || !playbackId.equals(panel.getPlaybackId())) {
                continue;
            }
            CameraSnapshot cameraSnapshot = selectedCameraSnapshot(level, panel);
            if (cameraSnapshot.equals(session.cameraSnapshot)) {
                continue;
            }
            session.cameraSnapshot = cameraSnapshot;
            session.cameraRevision = nextCameraRevision(session.cameraRevision);
            for (UUID playerId : List.copyOf(session.audiblePlayers)) {
                ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                if (player != null && player.level() == level) {
                    sendAudienceCamera(player, playbackId, cameraSnapshot, session.cameraRevision);
                }
            }
        }
    }

    /**
     * Mobs inside the radius of a playing DJ speaker hop on the beat, exactly like the battle
     * crowd. This runs outside the five-tick audience pass: a beat-accurate hop cannot wait
     * four ticks, so the work per mob stays at one cached rhythm sample and integer arithmetic.
     */
    private static void tickDjCrowd(MinecraftServer server) {
        for (Map.Entry<UUID, SessionAudience> entry : AUDIENCES.entrySet()) {
            UUID playbackId = entry.getKey();
            SessionAudience session = entry.getValue();
            ServerLevel level = session.level;
            if (level.getServer() != server || !level.hasChunkAt(session.panelPos)
                    || !(level.getBlockEntity(session.panelPos) instanceof DjPanelBlockEntity panel)
                    || !panel.isPlaying() || !playbackId.equals(panel.getPlaybackId())) {
                continue;
            }
            // One mob can stand inside several speakers of the same installation.
            Set<Integer> moved = null;
            for (BlockPos speakerPos : panel.getLinkedSpeakers()) {
                if (!validLinkedSpeaker(level, panel, speakerPos)
                        || !(level.getBlockEntity(speakerPos) instanceof SpeakerBlockEntity speaker)) {
                    continue;
                }
                Vec3 center = Vec3.atCenterOf(speakerPos).add(0.0D, 0.65D, 0.0D);
                double radius = speaker.getSoundRadius();
                if (radius <= 0.0D) {
                    continue;
                }
                AABB bounds = new AABB(center.x - radius, center.y - radius, center.z - radius,
                        center.x + radius, center.y + radius, center.z + radius);
                for (Mob mob : level.getEntitiesOfClass(Mob.class, bounds, DjPlaybackManager::danceableMob)) {
                    if (mob.distanceToSqr(center) > radius * radius) {
                        continue;
                    }
                    if (moved == null) {
                        moved = new HashSet<>();
                    }
                    if (moved.add(mob.getId())) {
                        jumpOnDjBeat(level, mob, playbackId);
                    }
                }
            }
        }
    }

    /**
     * Walks each panel's preload plan, one track at a time and in playlist order. Only a single
     * asset per panel is ever handed to {@link DjAudiencePreloadQueue}, so the queue's round-robin
     * stays between panels instead of interleaving several songs of the same queue.
     */
    private static void tickPreloadPlans(MinecraftServer server) {
        for (Map.Entry<UUID, SessionAudience> entry : AUDIENCES.entrySet()) {
            UUID playbackId = entry.getKey();
            SessionAudience session = entry.getValue();
            ServerLevel level = session.level;
            if (level.getServer() != server || !level.hasChunkAt(session.panelPos)
                    || !(level.getBlockEntity(session.panelPos) instanceof DjPanelBlockEntity panel)
                    || !panel.isPlaying() || !playbackId.equals(panel.getPlaybackId())) {
                continue;
            }
            PreloadPlan plan = PRELOAD_PLANS.get(panelKey(level, session.panelPos));
            if (plan == null || !playbackId.equals(plan.playbackId)
                    || plan.playlistRevision != panel.getPlaylistRevision()) {
                // A new song, a new tempo variant or an edited queue replaces the plan wholesale.
                rebuildPreloadPlan(level, panel);
                continue;
            }
            advancePreloadPlan(level, panel, session, plan);
        }
    }

    /**
     * Rebuilds the ordered list of assets that follow the active entry. The active track is left
     * out on purpose: the per-listener path in {@link #updateAudience} already delivers it.
     */
    private static void rebuildPreloadPlan(ServerLevel level, DjPanelBlockEntity panel) {
        PanelKey key = panelKey(level, panel.getBlockPos());
        UUID playbackId = panel.getPlaybackId();
        if (playbackId == null || !panel.isPlaying()) {
            PRELOAD_PLANS.remove(key);
            return;
        }
        List<DjPlaylistEntry> playlist = panel.getPlaylist();
        UUID activeEntryId = panel.getActivePlaylistEntryId();
        int start = 0;
        if (activeEntryId != null) {
            for (int index = 0; index < playlist.size(); index++) {
                if (activeEntryId.equals(playlist.get(index).entryId())) {
                    start = index + 1;
                    break;
                }
            }
        }
        List<String> trackIds = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        // One asset per track id: a queue may legitimately hold the same song several times.
        seen.add(panel.getActiveTrackId());
        for (int index = start; index < playlist.size(); index++) {
            String trackId = playlist.get(index).trackId();
            if (seen.add(trackId) && trackById(trackId).isPresent()) {
                trackIds.add(trackId);
            }
        }
        if (trackIds.isEmpty()) {
            PRELOAD_PLANS.remove(key);
            return;
        }
        PRELOAD_PLANS.put(key, new PreloadPlan(playbackId, panel.getPlaylistRevision(),
                normalizeSpeed(panel.getPlaybackSpeed()), List.copyOf(trackIds)));
    }

    private static void advancePreloadPlan(ServerLevel level, DjPanelBlockEntity panel,
                                           SessionAudience session, PreloadPlan plan) {
        long now = level.getGameTime();
        if (plan.index >= plan.trackIds.size() || now < plan.retryAfter) {
            return;
        }
        if (plan.transferring) {
            // Finished means delivered to every recipient, or cancelled as stale; either way the
            // queue no longer owns this asset and the next one may start.
            if (!DjAudiencePreloadQueue.isRequestActive(level.dimension(), panel.getBlockPos(),
                    plan.trackIds.get(plan.index), plan.speed)) {
                plan.nextEntry();
            }
            return;
        }
        Optional<MusicBattleTracks.Track> track = trackById(plan.trackIds.get(plan.index));
        if (track.isEmpty()) {
            plan.nextEntry();
            return;
        }
        Optional<DjAudioPreparation.PreparedAudio> prepared = DjAudioPreparation.ready(track.get(), plan.speed);
        if (prepared.isEmpty()) {
            // The offer has to name the exact file and hash the transfer will send, so the tempo
            // variant must exist before anybody is asked about it.
            plan.retryAfter = now + PRELOAD_RETRY_DELAY_TICKS;
            DjAudioPreparation.prepare(track.get(), plan.speed).exceptionally(exception -> {
                MusicBattleMod.LOGGER.debug("Unable to prepare queued DJ preload '{}'.",
                        track.get().id(), unwrapCompletion(exception));
                return null;
            });
            return;
        }
        if (!plan.offered) {
            sendPreloadOffers(level, panel, session, plan, track.get(), prepared.get());
            return;
        }
        if (!plan.pendingAnswers.isEmpty() && now < plan.deadline) {
            return;
        }
        startPlanTransfer(level, panel, session, plan, track.get());
    }

    /** Asks every audible listener whether it already holds this asset. */
    private static void sendPreloadOffers(ServerLevel level, DjPanelBlockEntity panel,
                                          SessionAudience session, PreloadPlan plan,
                                          MusicBattleTracks.Track track,
                                          DjAudioPreparation.PreparedAudio prepared) {
        List<ServerPlayer> listeners = new ArrayList<>();
        for (UUID playerId : session.audiblePlayers) {
            ServerPlayer player = level.getServer().getPlayerList().getPlayer(playerId);
            if (player != null && player.connection != null && player.level() == level) {
                listeners.add(player);
            }
        }
        if (listeners.isEmpty()) {
            // Nobody can hear this panel yet; keep the plan on this entry until somebody can.
            plan.retryAfter = level.getGameTime() + PRELOAD_RETRY_DELAY_TICKS;
            return;
        }
        plan.beginOffer(transferFileName(prepared.audio().fileName()), prepared.sha256(),
                level.getGameTime() + PRELOAD_ANSWER_TIMEOUT_TICKS);
        DjPreloadOfferPayload offer = new DjPreloadOfferPayload(
                panel.getBlockPos(), track.id(), plan.fileName, plan.sha256);
        for (ServerPlayer player : listeners) {
            plan.offeredTo.add(player.getUUID());
            plan.pendingAnswers.add(player.getUUID());
            PacketDistributor.sendToPlayer(player, offer);
        }
    }

    private static void startPlanTransfer(ServerLevel level, DjPanelBlockEntity panel,
                                          SessionAudience session, PreloadPlan plan,
                                          MusicBattleTracks.Track track) {
        // Listeners that answered "have" are done. A listener that never answered is still sent
        // the asset: the timeout only stops one silent client from blocking the whole queue.
        Set<UUID> recipients = new HashSet<>(plan.offeredTo);
        recipients.removeAll(plan.confirmed);
        recipients.retainAll(session.audiblePlayers);
        if (recipients.isEmpty()) {
            plan.nextEntry();
            return;
        }
        DjAudiencePreloadQueue.RequestResult result = DjAudiencePreloadQueue.request(level,
                panel.getBlockPos(), track, plan.speed, panel.getActiveTrackId(), recipients);
        switch (result.result()) {
            case QUEUED, ALREADY_QUEUED -> plan.transferring = true;
            // A per-asset cooldown is transient, so retry this entry instead of skipping it.
            case COOLDOWN -> plan.retryAfter = level.getGameTime() + PRELOAD_RETRY_DELAY_TICKS;
            default -> plan.nextEntry();
        }
    }

    /** Accepts a preload answer only for the exact offer this panel sent to this listener. */
    public static void confirmPreloadAnswer(ServerPlayer player, DjPreloadAnswerPayload payload) {
        if (player == null || payload == null || payload.panelPos() == null
                || !(player.level() instanceof ServerLevel level)) {
            return;
        }
        PreloadPlan plan = PRELOAD_PLANS.get(panelKey(level, payload.panelPos()));
        if (plan == null || !plan.offered || plan.index >= plan.trackIds.size()
                || !plan.trackIds.get(plan.index).equals(payload.trackId())
                || !plan.fileName.equals(payload.fileName())
                || !plan.sha256.equalsIgnoreCase(payload.sha256())) {
            return;
        }
        UUID playerId = player.getUUID();
        SessionAudience session = AUDIENCES.get(plan.playbackId);
        // Nothing a client says may change another client's transfer, so the sender must be an
        // audible listener of this very panel and must be one the offer was actually sent to.
        if (session == null || session.level != level || !session.audiblePlayers.contains(playerId)
                || !plan.offeredTo.contains(playerId)) {
            return;
        }
        plan.pendingAnswers.remove(playerId);
        if (payload.have()) {
            plan.confirmed.add(playerId);
        } else {
            plan.confirmed.remove(playerId);
        }
    }

    /** The battle crowd rules, minus the balls: their own travel() already dances to the beat. */
    private static boolean danceableMob(Mob mob) {
        return mob.isAlive() && !mob.isNoAi() && mob.onGround()
                && !(mob instanceof ru.gora.musicbattle.entity.SmileyBallEntity)
                && !BattleManager.isEntityInBattle(mob);
    }

    private static void jumpOnDjBeat(ServerLevel level, Mob mob, UUID playbackId) {
        MusicMobRhythm.Beat beat = MusicMobRhythm.sample(level, mob.position(), null);
        // An audible battle outranks DJ music, so only this session's own beat moves its crowd.
        if (beat == null || !playbackId.equals(beat.sourceId())) {
            return;
        }
        // Same grid as BattleManager.shouldCrowdJump; the DJ beat already includes playback speed.
        int beatTicks = Math.max(2, (int) Math.round(1200.0D / Mth.clamp(beat.bpm(), 20.0D, 400.0D)));
        int offset = Math.floorMod(mob.getId() * 7, beatTicks);
        if (Math.floorMod((int) Math.round(beat.trackTicks()) + offset, beatTicks) == 0) {
            BattleCrowdSafety.jump(mob);
        }
    }

    public static void onServerStopping(ServerStoppingEvent event) {
        DjAudiencePreloadQueue.onServerStopping(event.getServer());
        PENDING_AUDIO_INTENTS.clear();
        SPEED_REQUEST_REVISIONS.clear();
        PRELOAD_PLANS.clear();
        PENDING_WORKSHOP_ADDS.clear();
        CACHED_WORKSHOP_TRACKS.clear();
        DjAudioPreparation.cleanup();
        for (UUID playbackId : new ArrayList<>(AUDIENCES.keySet())) {
            stopAudience(event.getServer(), playbackId);
        }
        AUDIENCES.clear();
        LOADED_PANELS.clear();
        ASSET_FINGERPRINTS.clear();
        ASSET_TRANSFER_QUEUE.clear();
        PREVIEW_REQUEST_AFTER.clear();
        CAMERA_PREVIEW_REQUEST_AFTER.clear();
        LIVE_CONTROL_AFTER.clear();
        DEFERRED_LIVE_CONTROL.clear();
        audienceRefreshRequested = false;
    }

    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        UUID playerId = event.getEntity().getUUID();
        DjAudiencePreloadQueue.onPlayerLoggedOut(playerId);
        PENDING_WORKSHOP_ADDS.remove(playerId);
        PENDING_AUDIO_INTENTS.entrySet().removeIf(entry -> playerId.equals(entry.getValue().requesterId));
        PREVIEW_REQUEST_AFTER.keySet().removeIf(key -> key.playerId().equals(playerId));
        CAMERA_PREVIEW_REQUEST_AFTER.keySet().removeIf(key -> key.playerId().equals(playerId));
        LIVE_CONTROL_AFTER.keySet().removeIf(key -> key.playerId().equals(playerId));
        DEFERRED_LIVE_CONTROL.keySet().removeIf(key -> key.playerId().equals(playerId));
        SPEED_REQUEST_REVISIONS.keySet().removeIf(key -> key.playerId().equals(playerId));
        resetAudiencePresence(playerId, true);
    }

    public static void onPlayerRespawned(PlayerEvent.PlayerRespawnEvent event) {
        resetAudiencePresence(event.getEntity().getUUID(), false);
    }

    public static void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        resetAudiencePresence(event.getEntity().getUUID(), false);
    }

    private static void resetAudiencePresence(UUID playerId, boolean discardAssetState) {
        for (SessionAudience session : AUDIENCES.values()) {
            session.sources.remove(playerId);
            session.audiblePlayers.remove(playerId);
            if (discardAssetState) {
                // A later login with the same UUID may use a different machine
                // or an empty cache, so it must be a new asset recipient too.
                session.assetDeliveredPlayers.remove(playerId);
                session.assetTransfersInFlight.remove(playerId);
                session.assetRetryAfter.remove(playerId);
            }
        }
    }

    private static ActiveDjSession preparePlayback(ServerLevel level, DjPanelBlockEntity panel,
                                                   Map<BlockPos, Integer> activeSpeakers) {
        if (!panel.isPlaying() || panel.getPlaybackId() == null) {
            return null;
        }
        Optional<MusicBattleTracks.Track> selected = trackById(panel.getActiveTrackId());
        if (selected.isEmpty()) {
            // A published/local track can disappear while its queue row is the current cursor.
            // Preserve that cursor long enough to advance to the next still-available row instead
            // of stopPlayback() clearing it and stranding every valid song after the stale one.
            PlaylistFollowUp missingTrackFollowUp = nextPlaybackTarget(panel);
            if (missingTrackFollowUp != null && !validSpeakers(level, panel).isEmpty()) {
                startFollowUp(level, panel, missingTrackFollowUp,
                        normalizeSpeed(panel.getPlaybackSpeed()), Integer.MAX_VALUE);
                return null;
            }
            stopPlayback(level, panel);
            return null;
        }
        MusicBattleTracks.Track track = selected.get();
        int elapsed = panel.getPlaybackPositionTicks(level.getGameTime());
        float speed = normalizeSpeed(panel.getPlaybackSpeed());
        int runtimeDuration = scaledTick(track.durationTicks(), speed);
        // Published/local tracks can disappear while a persistent queue still references them.
        // Skip unavailable entries instead of letting one stale id block every valid song after it.
        PlaylistFollowUp followUp = nextPlaybackTarget(panel);
        // The audience transfer itself is driven by the ordered preload plan, one asset at a
        // time. Only the tempo conversion is warmed here, so the swap is not stalled by ffmpeg.
        if (followUp != null && elapsed >= Math.max(0, runtimeDuration - 200)) {
            DjAudioPreparation.prepare(followUp.track(), speed).exceptionally(exception -> {
                MusicBattleMod.LOGGER.debug("Unable to prewarm next DJ playlist entry '{}'.",
                        followUp.track().id(), unwrapCompletion(exception));
                return null;
            });
        }
        if (DjAudioPreparation.ready(track, speed).isEmpty()) {
            // This is mainly a recovery guard for a persisted DJ session after
            // server restart. Normal user actions prepare the variant before
            // changing the panel state, so updateAudience never runs ffmpeg.
            int resumeTick = Mth.clamp(elapsed, 0, Math.max(0, runtimeDuration));
            pausePlayback(level, panel, runtimeDuration);
            queuePlaybackStart(level, panel, track, false, resumeTick,
                    panel.getPlaybackSpeed(), null, true, null);
            return null;
        }
        if (elapsed >= runtimeDuration) {
            PendingAudioIntent pending = PENDING_AUDIO_INTENTS.get(panelKey(level, panel.getBlockPos()));
            if (pending != null && pending.kind == AudioIntentKind.PLAYBACK_START && pending.matches(panel)) {
                pausePlaybackPreservingPendingStart(level, panel, runtimeDuration);
                return null;
            }
            if (followUp != null && !validSpeakers(level, panel).isEmpty()) {
                startFollowUp(level, panel, followUp, speed, runtimeDuration);
                return null;
            }
            pausePlaybackPreservingPendingStart(level, panel, runtimeDuration);
            return null;
        }

        List<BlockPos> speakers = validSpeakers(level, panel);
        for (BlockPos speakerPos : speakers) {
            activeSpeakers.merge(speakerPos, effectiveBpm(track.bpm(), speed), Math::max);
        }
        return new ActiveDjSession(panel, track, speakers, elapsed);
    }

    /**
     * Chooses what follows a finished song: the same row again, the next queue row, or a loop
     * back to the first row. Returns {@code null} when the queue is simply over.
     */
    private static PlaylistFollowUp nextPlaybackTarget(DjPanelBlockEntity panel) {
        DjPanelBlockEntity.PlaylistRepeat repeat = panel.getPlaylistRepeat();
        if (repeat == DjPanelBlockEntity.PlaylistRepeat.ONE) {
            // Repeating one song is an explicit request, so it also applies with auto-advance off.
            DjPlaylistEntry active = panel.findPlaylistEntry(panel.getActivePlaylistEntryId());
            if (active != null) {
                Optional<MusicBattleTracks.Track> activeEntryTrack = trackById(active.trackId());
                if (activeEntryTrack.isPresent()) {
                    return new PlaylistFollowUp(activeEntryTrack.get(), active.entryId(),
                            clampSourceCue(activeEntryTrack.get(), active.startSourceTicks()));
                }
            } else {
                // A song started from the mixer instead of the queue repeats from its beginning.
                Optional<MusicBattleTracks.Track> activeTrack = trackById(panel.getActiveTrackId());
                if (activeTrack.isPresent()) {
                    return new PlaylistFollowUp(activeTrack.get(), null, 0);
                }
            }
        }
        if (!panel.isPlaylistAutoAdvance()) {
            return null;
        }
        // Published/local tracks can disappear while a persistent queue still references them.
        // Skip unavailable entries instead of letting one stale id block every valid song after it.
        DjPlaylistEntry next = panel.nextPlaylistEntry(PLAYABLE_PLAYLIST_ENTRY);
        if (next == null && repeat == DjPanelBlockEntity.PlaylistRepeat.ALL) {
            // ALL loops the queue itself, so it also wraps the detached end-of-queue cursor left
            // behind when the playing row was removed. That cursor has no successor by design,
            // but the DJ asked for the whole playlist to repeat, so the first row is the successor.
            next = panel.firstPlaylistEntry(PLAYABLE_PLAYLIST_ENTRY);
        }
        if (next == null) {
            return null;
        }
        DjPlaylistEntry target = next;
        return trackById(target.trackId())
                .map(track -> new PlaylistFollowUp(track, target.entryId(),
                        clampSourceCue(track, target.startSourceTicks())))
                .orElse(null);
    }

    /** Starts the chosen follow-up row, pausing instead while its tempo variant is prepared. */
    private static void startFollowUp(ServerLevel level, DjPanelBlockEntity panel,
                                      PlaylistFollowUp followUp, float speed, int pauseDuration) {
        int runtimeCue = Math.max(0, Math.round(followUp.sourceCue() / speed));
        if (DjAudioPreparation.ready(followUp.track(), speed).isPresent()) {
            invalidateAudioIntent(level, panel.getBlockPos());
            applyPlaybackStart(level, panel, followUp.track(), false,
                    runtimeCue, speed, null, followUp.playlistEntryId());
            return;
        }
        queuePlaybackStart(level, panel, followUp.track(), false,
                runtimeCue, speed, null, true, followUp.playlistEntryId());
        pausePlaybackPreservingPendingStart(level, panel, pauseDuration);
    }

    private static void updateAudience(ServerLevel level, ActiveDjSession activeSession,
                                       Map<UUID, AudioSource> listeners) {
        DjPanelBlockEntity panel = activeSession.panel();
        MusicBattleTracks.Track track = activeSession.track();
        int elapsed = activeSession.elapsed();
        UUID playbackId = panel.getPlaybackId();
        if (playbackId == null) {
            return;
        }
        float speed = normalizeSpeed(panel.getPlaybackSpeed());
        Optional<DjAudioPreparation.PreparedAudio> preparedAudio = DjAudioPreparation.ready(track, speed);
        if (preparedAudio.isEmpty()) {
            // A prepared variant may have been invalidated by an on-disk track
            // replacement between the five-tick prepare/update phases. Pause
            // safely and rebuild instead of blocking the server thread.
            int resumeTick = Mth.clamp(elapsed, 0,
                    scaledTick(track.durationTicks(), panel.getPlaybackSpeed()));
            pausePlayback(level, panel);
            queuePlaybackStart(level, panel, track, false, resumeTick,
                    panel.getPlaybackSpeed(), null, true, null);
            return;
        }
        MusicBattleTracks.AudioAsset audioAsset = preparedAudio.get().audio();
        if (!validAudioAsset(audioAsset.path(), audioAsset.fileName())) {
            MusicBattleMod.LOGGER.warn("Stopping DJ playback for unsafe audio asset '{}'.", audioAsset.path());
            stopPlayback(level, panel);
            return;
        }
        AssetFingerprint fingerprint = new AssetFingerprint(preparedAudio.get().size(),
                preparedAudio.get().modified(), preparedAudio.get().sha256());

        SessionAudience existingSession = AUDIENCES.get(playbackId);
        if (existingSession != null
                && (!existingSession.trackSnapshot.equals(track)
                || !existingSession.audioFileName.equals(transferFileName(audioAsset.fileName()))
                || !existingSession.audioHash.equals(fingerprint.sha256()))) {
            // Publishing/replacing a track under the same id changes both the
            // asset cache key and potentially its FX timeline. Give the updated
            // content a fresh playback identity so no client can combine old FX
            // state with a new hash (or vice versa).
            stopAudience(level.getServer(), playbackId);
            panel.startPlayback(track.id(), level.getGameTime(), 0, panel.getActivePlaylistEntryId());
            return;
        }
        SessionAudience session = AUDIENCES.computeIfAbsent(playbackId,
                ignored -> new SessionAudience(level, panel.getBlockPos(), track,
                        transferFileName(audioAsset.fileName()), fingerprint.sha256()));
        boolean dspChanged = session.panelDspRevision != panel.getDspRevision();
        session.panelDspRevision = panel.getDspRevision();
        boolean visualFxChanged = session.panelVisualFxRevision != panel.getVisualFxRevision();
        session.panelVisualFxRevision = panel.getVisualFxRevision();
        Set<UUID> currentlyAudible = new HashSet<>();
        if (listeners.size() >= 4 && panel.getLastOperator() != null) {
            ServerPlayer dj = level.getServer().getPlayerList().getPlayer(panel.getLastOperator());
            if (dj != null) {
                MusicBattleAdvancements.grant(dj, "crowd_pleaser");
            }
        }
        // updateAudience itself runs on the server's five-tick cadence. World
        // game time may have a different modulo after a save/restart, which can
        // otherwise make this equality unreachable forever.
        boolean periodicRefresh = level.getServer().getTickCount() % 100 == 0;
        CameraSnapshot cameraSnapshot = selectedCameraSnapshot(level, panel);
        boolean cameraChanged = !cameraSnapshot.equals(session.cameraSnapshot);
        if (cameraChanged) {
            session.cameraSnapshot = cameraSnapshot;
            session.cameraRevision = nextCameraRevision(session.cameraRevision);
        }
        for (Map.Entry<UUID, AudioSource> listener : listeners.entrySet()) {
            ServerPlayer player = level.getServer().getPlayerList().getPlayer(listener.getKey());
            if (player == null || player.level() != level) {
                continue;
            }
            AudioSource source = listener.getValue();
            currentlyAudible.add(player.getUUID());
            AudioSourceKey previous = session.sources.put(player.getUUID(), source.key());
            // Keep listeners in the session while they are outside the radius so
            // the client can fade FX smoothly instead of receiving a hard stop.
            // Returning to this installation (including after another DJ won the
            // overlap arbitration) must still resend its complete session state.
            boolean newListener = previous == null;
            boolean enteredRange = session.audiblePlayers.add(player.getUUID());
            boolean needsSessionSync = newListener || enteredRange;
            boolean sourceChanged = !needsSessionSync && !source.key().equals(previous);
            UUID playerId = player.getUUID();
            AssetTransferStatus transferStatus = session.assetTransfersInFlight.get(playerId);
            if (transferStatus != null && level.getGameTime() >= transferStatus.deadline) {
                session.assetTransfersInFlight.remove(playerId);
                session.assetRetryAfter.put(playerId, level.getGameTime() + ASSET_RETRY_DELAY_TICKS);
                transferStatus = null;
            }
            if (!session.assetDeliveredPlayers.contains(playerId)
                    && transferStatus == null
                    && level.getGameTime() >= session.assetRetryAfter.getOrDefault(playerId, 0L)) {
                DjAudiencePreloadQueue.cancelActiveTrack(track.id(), List.of(playerId));
                UUID transferToken = UUID.randomUUID();
                session.assetTransfersInFlight.put(playerId, new AssetTransferStatus(
                        transferToken, AssetTransferPhase.LOADING,
                        level.getGameTime() + ASSET_TRANSFER_TIMEOUT_TICKS));
                sendAudioAsset(player, playbackId, session, transferToken, track.id(),
                        audioAsset.fileName(), audioAsset.path(), fingerprint.sha256());
            }
            if (needsSessionSync || visualFxChanged || periodicRefresh) {
                sendFxSession(player, panel, track, elapsed);
            }
            if (needsSessionSync || sourceChanged || dspChanged || periodicRefresh) {
                sendMusic(player, panel, track, audioAsset, fingerprint.sha256(), source, elapsed);
            }
            if (needsSessionSync || cameraChanged || periodicRefresh) {
                sendAudienceCamera(player, playbackId, cameraSnapshot, session.cameraRevision);
            }
        }
        if (DjVisualEffects.needsAudienceTargets(composedEffects(panel, track))) {
            sendDjAudience(level, playbackId, activeSession.speakers(), listeners.keySet());
        }

        long unavailableRevision = -1L;
        for (UUID playerId : new HashSet<>(session.audiblePlayers)) {
            if (currentlyAudible.contains(playerId)) {
                continue;
            }
            session.audiblePlayers.remove(playerId);
            ServerPlayer player = level.getServer().getPlayerList().getPlayer(playerId);
            if (player != null && player.level() == level) {
                if (unavailableRevision < 0L) {
                    session.cameraRevision = nextCameraRevision(session.cameraRevision);
                    unavailableRevision = session.cameraRevision;
                }
                BlockPos selectedCameraPos = session.cameraSnapshot == null
                        ? BlockPos.ZERO : session.cameraSnapshot.cameraPos();
                PacketDistributor.sendToPlayer(player, DjAudienceCameraPayload.unavailable(
                        playbackId, selectedCameraPos, unavailableRevision));
            }
            // Do not keep streaming a no-longer-selected DJ source. Apart from
            // wasting bandwidth, two DJ sessions sending the same cache key can
            // interleave chunks and force the client to reject both transfers.
            session.assetTransfersInFlight.remove(playerId);
            session.assetRetryAfter.remove(playerId);
        }
    }

    private static CameraSnapshot selectedCameraSnapshot(ServerLevel level, DjPanelBlockEntity panel) {
        BlockPos selectedCameraPos = panel.getSelectedCameraPos();
        if (!validLinkedCamera(level, panel, selectedCameraPos)) {
            return CameraSnapshot.unavailable(selectedCameraPos);
        }
        BattleCameraBlockEntity camera = (BattleCameraBlockEntity) level.getBlockEntity(selectedCameraPos);
        if (camera == null) {
            return CameraSnapshot.unavailable(selectedCameraPos);
        }
        Vec3 position = camera.getCameraPosition();
        if (!Double.isFinite(position.x) || !Double.isFinite(position.y) || !Double.isFinite(position.z)) {
            return CameraSnapshot.unavailable(selectedCameraPos);
        }
        return new CameraSnapshot(true, selectedCameraPos.immutable(),
                Mth.clamp(position.x, -30_000_000.0D, 30_000_000.0D),
                Mth.clamp(position.y, -30_000_000.0D, 30_000_000.0D),
                Mth.clamp(position.z, -30_000_000.0D, 30_000_000.0D),
                finiteYaw(camera.getCameraYaw()),
                finitePitch(camera.getCameraPitch()),
                finiteFov(camera.getCameraFov()));
    }

    private static void sendAudienceCamera(ServerPlayer player, UUID playbackId,
                                           CameraSnapshot snapshot, long revision) {
        PacketDistributor.sendToPlayer(player, new DjAudienceCameraPayload(
                playbackId, snapshot.available(), snapshot.cameraPos(),
                snapshot.x(), snapshot.y(), snapshot.z(),
                snapshot.yaw(), snapshot.pitch(), snapshot.fov(), revision));
    }

    private static long nextCameraRevision(long revision) {
        return revision == Long.MAX_VALUE ? Long.MAX_VALUE : Math.max(0L, revision) + 1L;
    }

    private static float finiteYaw(float value) {
        return Float.isFinite(value) ? Mth.wrapDegrees(value) : 0.0F;
    }

    private static float finitePitch(float value) {
        return Float.isFinite(value) ? Mth.clamp(value, -89.0F, 89.0F) : 0.0F;
    }

    private static float finiteFov(float value) {
        return Float.isFinite(value)
                ? Mth.clamp(value, 30.0F, 110.0F)
                : BattleCameraBlockEntity.DEFAULT_FOV;
    }

    /** Supplies PLAYER_HIGHLIGHT spectator/entity targets for standalone FX. */
    private static void sendDjAudience(ServerLevel level, UUID playbackId, List<BlockPos> speakers,
                                       Set<UUID> listenerIds) {
        if (listenerIds.isEmpty()) {
            return;
        }
        DjAudienceTargets targets = collectDjAudienceTargets(level, speakers, listenerIds);
        BattleAudiencePayload payload = new BattleAudiencePayload(
                playbackId, targets.playerEntityIds(), targets.mobEntityIds());
        for (UUID listenerId : listenerIds) {
            ServerPlayer listener = level.getServer().getPlayerList().getPlayer(listenerId);
            if (listener != null && listener.level() == level) {
                PacketDistributor.sendToPlayer(listener, payload);
            }
        }
    }

    /** Shared authoritative targets for audible listeners and an off-radius DJ monitor. */
    private static DjAudienceTargets collectDjAudienceTargets(ServerLevel level, List<BlockPos> speakers,
                                                              Set<UUID> listenerIds) {
        List<Integer> playerEntityIds = listenerIds.stream()
                .map(level.getServer().getPlayerList()::getPlayer)
                .filter(player -> player != null && player.level() == level && player.isAlive())
                .map(ServerPlayer::getId)
                .sorted()
                .limit(OpenDjPanelPayload.MAX_ACTIVE_PLAYERS)
                .toList();
        Set<Integer> mobEntityIds = new HashSet<>();
        List<AudienceSphere> coverage = new ArrayList<>(speakers.size());
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        double maxZ = Double.NEGATIVE_INFINITY;
        for (BlockPos speakerPos : speakers) {
            Vec3 center = Vec3.atCenterOf(speakerPos).add(0.0D, 0.65D, 0.0D);
            float radius = SpeakerNetwork.soundRadius(level, speakerPos);
            coverage.add(new AudienceSphere(center, radius));
            minX = Math.min(minX, center.x - radius);
            minY = Math.min(minY, center.y - radius);
            minZ = Math.min(minZ, center.z - radius);
            maxX = Math.max(maxX, center.x + radius);
            maxY = Math.max(maxY, center.y + radius);
            maxZ = Math.max(maxZ, center.z + radius);
        }
        if (!coverage.isEmpty()) {
            AABB bounds = new AABB(minX, minY, minZ, maxX, maxY, maxZ);
            for (Mob mob : level.getEntitiesOfClass(Mob.class, bounds, Mob::isAlive)) {
                for (AudienceSphere sphere : coverage) {
                    if (mob.distanceToSqr(sphere.center()) <= sphere.radius() * sphere.radius()) {
                        mobEntityIds.add(mob.getId());
                        break;
                    }
                }
            }
        }
        return new DjAudienceTargets(playerEntityIds,
                mobEntityIds.stream().sorted().limit(OpenDjPanelPayload.MAX_ACTIVE_MOBS).toList());
    }

    private static void sendFxSession(ServerPlayer player, DjPanelBlockEntity panel,
                                      MusicBattleTracks.Track track, int elapsed) {
        float speed = normalizeSpeed(panel.getPlaybackSpeed());
        List<TrackEffect> effects = composedEffects(panel, track);
        PacketDistributor.sendToPlayer(player, new SpectatorBattlePayload(
                panel.getPlaybackId(),
                scaledTick(track.durationTicks(), speed),
                elapsed,
                effectiveBpm(track.bpm(), speed),
                speed,
                0, 0, -1,
                0.0F, 0.0F, 0.0F,
                0xFF55E6FF, 0xFFFF55C8,
                1.0F, 1.0F, 1.0F, 1.0F, 1.0F, 1.0F,
                1.0F, 1.0F, 1.0F, 1.0F, 1.0F, 1.0F,
                1.0F, 1.0F,
                0, 0, 0, 0, 0, 0,
                List.of(),
                effects
        ));
    }

    private static List<TrackEffect> composedEffects(DjPanelBlockEntity panel,
                                                      MusicBattleTracks.Track track) {
        float speed = normalizeSpeed(panel.getPlaybackSpeed());
        int duration = scaledTick(track.durationTicks(), speed);
        DjPlaylistEntry entry = panel.findPlaylistEntry(panel.getActivePlaylistEntryId());
        if (entry != null && entry.fxMode() != DjPlaylistEntry.FxMode.INHERIT) {
            if (entry.usesLegacyDjEditor()) {
                // Ранг считается вниз от размера списка, как в DjVisualEffects.materializeDj:
                // верхняя строка получает наибольший ранг и рисуется последней, поверх нижних.
                // Старые записи с более чем восемью эффектами идут этим путём, и порядок в них
                // должен читаться так же, как в обычной записи.
                List<TrackEffect> source = entry.effects();
                List<TrackEffect> legacy = new ArrayList<>(source.size());
                for (TrackEffect effect : source) {
                    legacy.add(new DjVisualEffectConfig("entry", true, effect)
                            .materialize(panel.getVisualFxActivationTick(), duration)
                            .withDjOrder(source.size() - legacy.size()));
                }
                return List.copyOf(legacy);
            }
            return switch (entry.fxMode()) {
                case NONE -> List.of();
                case TRACK -> scaledEffects(entry.effects(), speed);
                case DJ -> DjVisualEffects.compose(scaledEffects(entry.mixerChartEffects(), speed),
                        entry.mixer().effects(), entry.mixer().trackFxEnabled(), entry.mixer().djFxEnabled(),
                        panel.getVisualFxActivationTick(), duration);
                default -> List.of();
            };
        }
        return DjVisualEffects.compose(
                scaledEffects(track.effects(), speed), panel.getDjVisualEffects(),
                panel.isTrackVisualFxEnabled(), panel.isDjVisualFxEnabled(),
                panel.getVisualFxActivationTick(), duration);
    }

    private static void sendMusic(ServerPlayer player, DjPanelBlockEntity panel, MusicBattleTracks.Track track,
                                  MusicBattleTracks.AudioAsset audioAsset, String audioHash,
                                  AudioSource source, int elapsed) {
        PacketDistributor.sendToPlayer(player, new SpectatorMusicPayload(
                panel.getPlaybackId(),
                track.id(),
                transferFileName(audioAsset.fileName()),
                audioHash,
                track.volume() * source.volume(),
                normalizeSpeed(panel.getPlaybackSpeed()),
                source.position().x,
                source.position().y,
                source.position().z,
                source.radius(),
                source.lowGainDb(),
                source.midGainDb(),
                source.highGainDb(),
                panel.isDspEnabled(),
                panel.isReverbEnabled(), panel.getReverbWet(), panel.getReverbRoom(), panel.getReverbDamping(),
                panel.isEchoEnabled(), panel.getEchoWet(), panel.getEchoTimeMs(), panel.getEchoFeedback(),
                panel.isLowPassEnabled(), panel.getLowPassCutoffHz(),
                0,
                elapsed
        ));
    }

    private static AudioSource loudestSource(ServerLevel level, ServerPlayer player, List<BlockPos> speakers,
                                             AudioSourceKey currentKey) {
        AudioSource loudest = null;
        AudioSource current = null;
        for (BlockPos speakerPos : speakers) {
            if (!(level.getBlockEntity(speakerPos) instanceof SpeakerBlockEntity speaker)) {
                continue;
            }
            Vec3 position = Vec3.atCenterOf(speakerPos).add(0.0D, 0.65D, 0.0D);
            float radius = speaker.getSoundRadius();
            double distanceSquared = player.distanceToSqr(position);
            if (distanceSquared <= radius * radius) {
                float effectiveVolume = effectiveVolume(speaker.getVolume(), Math.sqrt(distanceSquared), radius);
                AudioSourceKey key = new AudioSourceKey(speakerPos.asLong(), radius, speaker.getVolume(),
                        speaker.getLowGainDb(), speaker.getMidGainDb(), speaker.getHighGainDb());
                AudioSource candidate = new AudioSource(position, radius, speaker.getVolume(),
                        speaker.getLowGainDb(), speaker.getMidGainDb(), speaker.getHighGainDb(),
                        effectiveVolume, key);
                if (key.equals(currentKey)) {
                    current = candidate;
                }
                if (loudest == null || candidate.effectiveVolume() > loudest.effectiveVolume() + 0.0001F
                        || Math.abs(candidate.effectiveVolume() - loudest.effectiveVolume()) <= 0.0001F
                        && key.pos() < loudest.key().pos()) {
                    loudest = candidate;
                }
            }
        }
        // Small hysteresis prevents rapid left/right source flicker on the exact
        // border between two equally loud speaker spheres.
        return current != null && loudest != null
                && current.effectiveVolume() >= loudest.effectiveVolume() * 0.95F ? current : loudest;
    }

    private static void configureSpeaker(ServerPlayer player, DjPanelBlockEntity panel, BlockPos speakerPos,
                                         float radius, float volume,
                                         float lowGainDb, float midGainDb, float highGainDb) {
        if (!validLinkedSpeaker(player.serverLevel(), panel, speakerPos)) {
            return;
        }
        SpeakerBlockEntity speaker = (SpeakerBlockEntity) player.level().getBlockEntity(speakerPos);
        speaker.setSettings(radius, volume, lowGainDb, midGainDb, highGainDb);
        player.displayClientMessage(Component.translatable("message.music_battle.dj_panel.speaker_saved")
                .withStyle(ChatFormatting.GREEN), true);
    }

    private static void applyAudioPreset(ServerPlayer player, DjPanelBlockEntity panel,
                                         ConfigureDjPanelPayload payload) {
        List<BlockPos> targets = payload.applyToAll()
                ? validSpeakers(player.serverLevel(), panel)
                : validLinkedSpeaker(player.serverLevel(), panel, payload.targetPos())
                ? List.of(payload.targetPos()) : List.of();
        if (targets.isEmpty()) {
            return;
        }
        for (BlockPos speakerPos : targets) {
            if (player.level().getBlockEntity(speakerPos) instanceof SpeakerBlockEntity speaker) {
                speaker.setSettings(payload.radius(), payload.volume(), payload.lowGainDb(),
                        payload.midGainDb(), payload.highGainDb());
            }
        }
        panel.setDspSettings(payload.dspEnabled(),
                payload.reverbEnabled(), payload.reverbWet(), payload.reverbRoom(), payload.reverbDamping(),
                payload.echoEnabled(), payload.echoWet(), payload.echoTimeMs(), payload.echoFeedback(),
                payload.lowPassEnabled(), payload.lowPassCutoffHz());
        requestAudienceRefresh();
    }

    private static void resetSpeakers(ServerPlayer player, DjPanelBlockEntity panel,
                                      ConfigureDjPanelPayload payload) {
        List<BlockPos> targets = payload.applyToAll()
                ? validSpeakers(player.serverLevel(), panel)
                : validLinkedSpeaker(player.serverLevel(), panel, payload.targetPos())
                ? List.of(payload.targetPos()) : List.of();
        for (BlockPos speakerPos : targets) {
            if (player.level().getBlockEntity(speakerPos) instanceof SpeakerBlockEntity speaker) {
                speaker.setSettings(SpeakerBlockEntity.DEFAULT_RADIUS, SpeakerBlockEntity.DEFAULT_VOLUME,
                        SpeakerBlockEntity.DEFAULT_EQ_GAIN_DB, SpeakerBlockEntity.DEFAULT_EQ_GAIN_DB,
                        SpeakerBlockEntity.DEFAULT_EQ_GAIN_DB);
            }
        }
        if (payload.applyToAll()) {
            // "Reset the speakers" is read as "put the rig back to how it was": the rack's reverb, echo and low
            // pass, and the DJ's own effect stack, go with them. Resetting one speaker stays about that speaker.
            panel.resetSoundAndEffects(panel.getPlaybackPositionTicks(player.serverLevel().getGameTime()));
        }
        if (!targets.isEmpty() || payload.applyToAll()) {
            requestAudienceRefresh();
        }
    }

    private static void configureSpeakersLive(ServerPlayer player, DjPanelBlockEntity panel,
                                              ConfigureDjPanelPayload payload) {
        if (payload.applyToAll()) {
            for (BlockPos speakerPos : validSpeakers(player.serverLevel(), panel)) {
                SpeakerBlockEntity speaker = (SpeakerBlockEntity) player.level().getBlockEntity(speakerPos);
                speaker.setSettings(payload.radius(), payload.volume(),
                        payload.lowGainDb(), payload.midGainDb(), payload.highGainDb());
            }
            requestAudienceRefresh();
            return;
        }
        if (!validLinkedSpeaker(player.serverLevel(), panel, payload.targetPos())) {
            return;
        }
        SpeakerBlockEntity speaker = (SpeakerBlockEntity) player.level().getBlockEntity(payload.targetPos());
        speaker.setSettings(payload.radius(), payload.volume(),
                payload.lowGainDb(), payload.midGainDb(), payload.highGainDb());
        requestAudienceRefresh();
    }

    private static boolean allowLiveControl(ServerPlayer player, BlockPos panelPos, boolean panelFx) {
        LiveControlKey key = new LiveControlKey(player.getUUID(), player.level().dimension(), panelPos.asLong(), panelFx);
        long now = player.level().getGameTime();
        if (now < LIVE_CONTROL_AFTER.getOrDefault(key, 0L)) {
            return false;
        }
        LIVE_CONTROL_AFTER.put(key, now + 1L);
        DEFERRED_LIVE_CONTROL.remove(key);
        return true;
    }

    private static void deferLiveControl(ServerPlayer player, BlockPos panelPos, boolean panelFx,
                                         ConfigureDjPanelPayload payload) {
        DEFERRED_LIVE_CONTROL.put(new LiveControlKey(
                player.getUUID(), player.level().dimension(), panelPos.asLong(), panelFx), payload);
    }

    private static void discardDeferredLiveControl(ServerPlayer player, BlockPos panelPos, boolean panelFx) {
        if (player != null && panelPos != null) {
            ResourceKey<Level> dimension = player.level().dimension();
            long packedPos = panelPos.asLong();
            DEFERRED_LIVE_CONTROL.keySet().removeIf(key -> key.dimension.equals(dimension)
                    && key.panelPos == packedPos && key.panelFx == panelFx);
        }
    }

    private static void processDeferredLiveControls(MinecraftServer server) {
        for (Map.Entry<LiveControlKey, ConfigureDjPanelPayload> entry
                : new ArrayList<>(DEFERRED_LIVE_CONTROL.entrySet())) {
            LiveControlKey key = entry.getKey();
            ServerPlayer player = server.getPlayerList().getPlayer(key.playerId());
            if (player == null) {
                DEFERRED_LIVE_CONTROL.remove(key);
                continue;
            }
            ServerLevel expectedLevel = server.getLevel(key.dimension());
            if (expectedLevel == null || player.level() != expectedLevel) {
                DEFERRED_LIVE_CONTROL.remove(key);
                continue;
            }
            BlockPos panelPos = BlockPos.of(key.panelPos());
            if (player.level().getGameTime() < LIVE_CONTROL_AFTER.getOrDefault(key, 0L)) {
                continue;
            }
            DEFERRED_LIVE_CONTROL.remove(key);
            if (!validPanelAccess(player, panelPos)
                    || !(player.level().getBlockEntity(panelPos) instanceof DjPanelBlockEntity panel)) {
                continue;
            }
            LIVE_CONTROL_AFTER.put(key, player.level().getGameTime() + 1L);
            ConfigureDjPanelPayload payload = entry.getValue();
            if (key.panelFx()) {
                configurePanelFxLive(panel, payload);
            } else {
                configureSpeakersLive(player, panel, payload);
            }
        }
    }

    private static void configurePanelFxLive(DjPanelBlockEntity panel, ConfigureDjPanelPayload payload) {
        panel.setDspSettings(payload.dspEnabled(),
                payload.reverbEnabled(), payload.reverbWet(), payload.reverbRoom(), payload.reverbDamping(),
                payload.echoEnabled(), payload.echoWet(), payload.echoTimeMs(), payload.echoFeedback(),
                payload.lowPassEnabled(), payload.lowPassCutoffHz());
        requestAudienceRefresh();
    }

    private static void unlinkSpeaker(ServerPlayer player, DjPanelBlockEntity panel, BlockPos speakerPos) {
        if (!validLinkedSpeaker(player.serverLevel(), panel, speakerPos)) {
            return;
        }
        SpeakerBlockEntity speaker = (SpeakerBlockEntity) player.level().getBlockEntity(speakerPos);
        speaker.setDjController(null);
        panel.unlinkSpeaker(speakerPos);
        player.displayClientMessage(Component.translatable("message.music_battle.dj_panel.speaker_unlinked")
                .withStyle(ChatFormatting.YELLOW), true);
    }

    private static void selectCamera(ServerPlayer player, DjPanelBlockEntity panel, BlockPos cameraPos) {
        // Selection is panel metadata and may safely persist while the camera's
        // chunk is unloaded. The audience payload reports it as unavailable
        // until that linked camera becomes loaded again.
        if (cameraPos == null || !panel.getLinkedCameras().contains(cameraPos)) {
            return;
        }
        panel.selectCamera(cameraPos);
        EXPLICIT_CAMERAS.computeIfAbsent(player.serverLevel(), ignored -> new ru.gora.musicbattle.data.ExplicitCameraSelection<>())
                .select(panel.getBlockPos().immutable(), cameraPos.immutable());
        BattleManager.refreshDjBattleCameras(player.serverLevel());
    }

    /** No playback requirement: a booth may direct a battle without playing a second song. */
    static BlockPos selectedBattleCamera(ServerLevel level, java.util.function.Predicate<BlockPos> boothInBattleArea) {
        var selections = EXPLICIT_CAMERAS.get(level);
        if (selections == null) return null;
        return selections.latest(boothInBattleArea, (panelPos, cameraPos) -> level.hasChunkAt(panelPos)
                && level.getBlockEntity(panelPos) instanceof DjPanelBlockEntity panel
                && cameraPos.equals(panel.getSelectedCameraPos())
                && validLinkedCamera(level, panel, cameraPos));
    }

    private static void unlinkCamera(ServerPlayer player, DjPanelBlockEntity panel, BlockPos cameraPos) {
        if (cameraPos == null || !panel.getLinkedCameras().contains(cameraPos)) {
            return;
        }
        panel.unlinkCamera(cameraPos);
        player.displayClientMessage(Component.translatable("message.music_battle.dj_panel.camera_unlinked")
                .withStyle(ChatFormatting.YELLOW), true);
    }

    private static void requestAudiencePreload(ServerPlayer player, DjPanelBlockEntity panel, String trackId) {
        Optional<MusicBattleTracks.Track> selected = trackById(trackId == null ? "" : trackId);
        if (selected.isEmpty()) {
            player.displayClientMessage(Component.translatable("message.music_battle.dj_panel.preload.invalid")
                    .withStyle(ChatFormatting.RED), true);
            return;
        }

        UUID playbackId = panel.getPlaybackId();
        SessionAudience session = playbackId == null ? null : AUDIENCES.get(playbackId);
        boolean liveSessionMatches = session != null
                && session.level == player.serverLevel()
                && session.panelPos.equals(panel.getBlockPos());
        Set<UUID> currentCoverageAudience = new HashSet<>();
        if (!liveSessionMatches) {
            List<BlockPos> speakers = validSpeakers(player.serverLevel(), panel);
            for (ServerPlayer candidate : player.serverLevel().players()) {
                if (candidate.isAlive() && !BattleManager.hasActiveBattleContext(candidate.getUUID())
                        && loudestSource(player.serverLevel(), candidate, speakers, null) != null) {
                    currentCoverageAudience.add(candidate.getUUID());
                }
            }
        }
        Set<UUID> audiblePlayers = DjPreloadAudience.choose(liveSessionMatches,
                liveSessionMatches ? session.audiblePlayers : Set.of(), currentCoverageAudience);
        DjAudiencePreloadQueue.RequestResult result = DjAudiencePreloadQueue.request(
                player.serverLevel(), panel.getBlockPos(), selected.get(), panel.getPlaybackSpeed(),
                panel.getActiveTrackId(), audiblePlayers);

        Component message = switch (result.result()) {
            case QUEUED -> Component.translatable("message.music_battle.dj_panel.preload.queued",
                    selected.get().title(), result.queuedRecipients());
            case ACTIVE_TRACK -> Component.translatable("message.music_battle.dj_panel.preload.active_track");
            case NO_AUDIENCE -> Component.translatable("message.music_battle.dj_panel.preload.no_audience");
            case ALREADY_QUEUED -> Component.translatable("message.music_battle.dj_panel.preload.already_queued");
            case DEDUPLICATED -> Component.translatable("message.music_battle.dj_panel.preload.deduplicated");
            case COOLDOWN -> Component.translatable("message.music_battle.dj_panel.preload.cooldown");
            case INVALID_REQUEST -> Component.translatable("message.music_battle.dj_panel.preload.invalid");
        };
        ChatFormatting color = result.accepted() ? ChatFormatting.AQUA
                : result.result() == DjAudiencePreloadQueue.Result.INVALID_REQUEST
                ? ChatFormatting.RED : ChatFormatting.YELLOW;
        player.displayClientMessage(message.copy().withStyle(color), true);
    }

    private static void startPlayback(ServerPlayer player, DjPanelBlockEntity panel, String trackId,
                                      boolean restart, int requestedStartTicks, float requestedSpeed,
                                      UUID playlistEntryId) {
        Optional<MusicBattleTracks.Track> selected = trackById(trackId == null ? "" : trackId);
        if (selected.isEmpty()) {
            player.displayClientMessage(Component.translatable("message.music_battle.dj_panel.track_missing")
                    .withStyle(ChatFormatting.RED), true);
            return;
        }
        panel.setLastOperator(player.getUUID());
        if (validSpeakers(player.serverLevel(), panel).isEmpty()) {
            player.displayClientMessage(Component.translatable("message.music_battle.dj_panel.no_loaded_speakers")
                    .withStyle(ChatFormatting.RED), true);
            return;
        }
        if (!restart && panel.isPlaying() && selected.get().id().equals(panel.getActiveTrackId())
                && playlistEntryId == null) {
            return;
        }
        panel.selectTrack(selected.get().id());
        float speed = normalizeSpeed(requestedSpeed);
        int startTicks = Math.max(0, requestedStartTicks);
        if (!restart && selected.get().id().equals(panel.getActiveTrackId())
                && playlistEntryId == null) {
            int currentRuntimeTicks = panel.getPlaybackPositionTicks(player.level().getGameTime());
            int currentSourceTicks = Math.max(0, Math.round(currentRuntimeTicks
                    * normalizeSpeed(panel.getPlaybackSpeed())));
            startTicks = Math.max(0, Math.round(currentSourceTicks / speed));
        }
        if (DjAudioPreparation.ready(selected.get(), speed).isPresent()) {
            invalidateAudioIntent(player.serverLevel(), panel.getBlockPos());
            applyPlaybackStart(player.serverLevel(), panel, selected.get(), restart,
                    startTicks, speed, player, playlistEntryId);
            return;
        }
        queuePlaybackStart(player.serverLevel(), panel, selected.get(), restart,
                startTicks, speed, player, false, playlistEntryId);
    }

    private static void queuePlaybackStart(ServerLevel level, DjPanelBlockEntity panel,
                                           MusicBattleTracks.Track track, boolean restart,
                                           int requestedStartTicks, float requestedSpeed,
                                           ServerPlayer requester, boolean recovery,
                                           UUID playlistEntryId) {
        PanelKey key = panelKey(level, panel.getBlockPos());
        UUID token = UUID.randomUUID();
        float speed = normalizeSpeed(requestedSpeed);
        PendingAudioIntent intent = new PendingAudioIntent(token,
                requester == null ? null : requester.getUUID(), panelState(panel),
                AudioIntentKind.PLAYBACK_START, track.id(), playlistEntryId, restart,
                Math.max(0, Math.round(requestedStartTicks * speed)), speed);
        PENDING_AUDIO_INTENTS.put(key, intent);
        if (requester != null && !recovery) {
            requester.displayClientMessage(Component.translatable(
                    "message.music_battle.dj_panel.audio_preparing", speedLabel(speed), track.title())
                    .withStyle(ChatFormatting.YELLOW), true);
        }

        MinecraftServer server = level.getServer();
        DjAudioPreparation.prepare(track, speed).whenComplete((asset, exception) ->
                server.execute(() -> {
                    PendingAudioIntent current = PENDING_AUDIO_INTENTS.get(key);
                    if (current == null || !token.equals(current.token)) {
                        return;
                    }
                    ServerLevel currentLevel = server.getLevel(key.dimension);
                    if (currentLevel == null || !currentLevel.hasChunkAt(key.pos)
                            || !(currentLevel.getBlockEntity(key.pos) instanceof DjPanelBlockEntity currentPanel)
                            || !current.matches(currentPanel)
                            || trackById(track.id()).filter(track::equals).isEmpty()) {
                        PENDING_AUDIO_INTENTS.remove(key, current);
                        return;
                    }
                    ServerPlayer currentRequester = current.requesterId == null ? null
                            : server.getPlayerList().getPlayer(current.requesterId);
                    if (exception != null || asset == null
                            || DjAudioPreparation.ready(track, speed).isEmpty()
                            || validSpeakers(currentLevel, currentPanel).isEmpty()) {
                        PENDING_AUDIO_INTENTS.remove(key, current);
                        reportAudioPreparationFailure(currentRequester, track, speed, exception);
                        refreshPanelIfOpen(currentRequester, key.pos);
                        return;
                    }
                    PENDING_AUDIO_INTENTS.remove(key, current);
                    applyPlaybackStart(currentLevel, currentPanel, track, restart,
                            requestedStartTicks, speed, currentRequester, playlistEntryId);
                    refreshPanelIfOpen(currentRequester, key.pos);
                }));
    }

    private static void applyPlaybackStart(ServerLevel level, DjPanelBlockEntity panel,
                                           MusicBattleTracks.Track track, boolean restart,
                                           int requestedStartTicks, float requestedSpeed,
                                           ServerPlayer requester, UUID playlistEntryId) {
        float speed = normalizeSpeed(requestedSpeed);
        boolean naturalEnd = trackById(panel.getActiveTrackId())
                .map(previousTrack -> panel.getPlaybackPositionTicks(level.getGameTime())
                        >= scaledTick(previousTrack.durationTicks(), panel.getPlaybackSpeed())).orElse(false);
        if (Float.compare(speed, normalizeSpeed(panel.getPlaybackSpeed())) != 0) {
            int activeSourceDuration = trackById(panel.getActiveTrackId())
                    .map(MusicBattleTracks.Track::durationTicks).orElse(0);
            UUID previous = panel.setPlaybackSpeed(speed, level.getGameTime(), activeSourceDuration);
            if (previous != null) {
                stopAudience(level.getServer(), previous, naturalEnd);
            }
        } else {
            UUID previous = panel.getPlaybackId();
            if (previous != null) {
                stopAudience(level.getServer(), previous, naturalEnd);
            }
        }
        int duration = scaledTick(track.durationTicks(), speed);
        int maximumStartTick = Math.max(0, duration - 1);
        int offset = restart ? 0 : Mth.clamp(requestedStartTicks, 0, maximumStartTick);
        panel.selectTrack(track.id());
        panel.startPlayback(track.id(), level.getGameTime(), offset, playlistEntryId);
        rebuildPreloadPlan(level, panel);
        warmSpeedVariants(track);
        requestAudienceRefresh();
        if (requester != null) {
            requester.displayClientMessage(Component.translatable(
                    "message.music_battle.dj_panel.playing", track.title())
                    .withStyle(ChatFormatting.AQUA), true);
        }
    }

    private static void queueSpeedChange(ServerPlayer requester, DjPanelBlockEntity panel,
                                         MusicBattleTracks.Track track, float speed) {
        ServerLevel level = requester.serverLevel();
        PanelKey key = panelKey(level, panel.getBlockPos());
        UUID token = UUID.randomUUID();
        PendingAudioIntent intent = new PendingAudioIntent(token, requester.getUUID(), panelState(panel),
                AudioIntentKind.SPEED_CHANGE, track.id(), panel.getActivePlaylistEntryId(), false, 0, speed);
        PENDING_AUDIO_INTENTS.put(key, intent);
        // Acknowledge the latest requested value immediately. openPanel exposes
        // pending.requestedSpeed while the prepared audio remains unchanged.
        refreshPanelIfOpen(requester, panel.getBlockPos());
        requester.displayClientMessage(Component.translatable(
                "message.music_battle.dj_panel.audio_preparing", speedLabel(speed), track.title())
                .withStyle(ChatFormatting.YELLOW), true);

        MinecraftServer server = level.getServer();
        DjAudioPreparation.prepare(track, speed).whenComplete((asset, exception) ->
                server.execute(() -> {
                    PendingAudioIntent current = PENDING_AUDIO_INTENTS.get(key);
                    if (current == null || !token.equals(current.token)) {
                        return;
                    }
                    ServerLevel currentLevel = server.getLevel(key.dimension);
                    if (currentLevel == null || !currentLevel.hasChunkAt(key.pos)
                            || !(currentLevel.getBlockEntity(key.pos) instanceof DjPanelBlockEntity currentPanel)
                            || !current.matches(currentPanel)
                            || trackById(track.id()).filter(track::equals).isEmpty()) {
                        PENDING_AUDIO_INTENTS.remove(key, current);
                        return;
                    }
                    ServerPlayer currentRequester = server.getPlayerList().getPlayer(current.requesterId);
                    if (exception != null || asset == null
                            || DjAudioPreparation.ready(track, speed).isEmpty()) {
                        PENDING_AUDIO_INTENTS.remove(key, current);
                        reportAudioPreparationFailure(currentRequester, track, speed, exception);
                        refreshPanelIfOpen(currentRequester, key.pos);
                        return;
                    }
                    PENDING_AUDIO_INTENTS.remove(key, current);
                    applyPlaybackSpeed(currentLevel, currentPanel, speed);
                    refreshPanelIfOpen(currentRequester, key.pos);
                }));
    }

    private static void reportAudioPreparationFailure(ServerPlayer player,
                                                      MusicBattleTracks.Track track, float speed,
                                                      Throwable exception) {
        MusicBattleMod.LOGGER.warn("Unable to prepare DJ audio '{}' at x{}; current playback was preserved.",
                track.id(), speedLabel(speed), unwrapCompletion(exception));
        if (player != null) {
            player.displayClientMessage(Component.translatable(
                    "message.music_battle.dj_panel.audio_prepare_failed", track.title(), speedLabel(speed))
                    .withStyle(ChatFormatting.RED), true);
        }
    }

    private static void refreshPanelIfOpen(ServerPlayer player, BlockPos panelPos) {
        if (player != null && validPanelAccess(player, panelPos)) {
            openPanel(player, panelPos);
        }
    }

    private static void pausePlayback(ServerLevel level, DjPanelBlockEntity panel) {
        int duration = trackById(panel.getActiveTrackId())
                .map(track -> scaledTick(track.durationTicks(), panel.getPlaybackSpeed()))
                .orElse(Integer.MAX_VALUE);
        pausePlayback(level, panel, duration);
    }

    private static void pausePlayback(ServerLevel level, DjPanelBlockEntity panel, int duration) {
        invalidatePlaybackStartIntent(level, panel.getBlockPos());
        UUID stoppedId = panel.pausePlayback(level.getGameTime(), Math.max(0, duration));
        if (stoppedId != null) {
            stopAudience(level.getServer(), stoppedId);
        }
        requestAudienceRefresh();
    }

    /** Natural completion of A must not cancel an already-preparing replacement B. */
    private static void pausePlaybackPreservingPendingStart(ServerLevel level, DjPanelBlockEntity panel,
                                                            int duration) {
        PanelKey key = panelKey(level, panel.getBlockPos());
        PendingAudioIntent pending = PENDING_AUDIO_INTENTS.get(key);
        boolean preservePending = pending != null && pending.kind == AudioIntentKind.PLAYBACK_START
                && pending.matches(panel);
        UUID stoppedId = panel.pausePlayback(level.getGameTime(), Math.max(0, duration));
        if (stoppedId != null) {
            stopAudience(level.getServer(), stoppedId, true);
        }
        if (preservePending) {
            PendingAudioIntent current = PENDING_AUDIO_INTENTS.get(key);
            if (current != null && current.token.equals(pending.token)) {
                PENDING_AUDIO_INTENTS.put(key, current.withExpectedState(panelState(panel)));
            }
        }
        requestAudienceRefresh();
    }

    private static void seekPlayback(ServerPlayer player, DjPanelBlockEntity panel,
                                     String trackId, int targetTicks, float requestedSpeed) {
        ServerLevel level = player.serverLevel();
        float clientSpeed = normalizeSpeed(requestedSpeed);
        PanelKey key = panelKey(level, panel.getBlockPos());
        PendingAudioIntent pending = PENDING_AUDIO_INTENTS.get(key);
        if (pending != null && pending.kind == AudioIntentKind.PLAYBACK_START
                && pending.targetTrackId.equals(trackId) && pending.matches(panel)) {
            Optional<MusicBattleTracks.Track> pendingTrack = trackById(trackId);
            if (pendingTrack.isPresent()) {
                // The screen may optimistically expose B's playhead while its
                // audio is still being prepared. Retarget the pending start
                // instead of discarding this seek because authoritative A is
                // still playing for the audience.
                queuePlaybackStart(level, panel, pendingTrack.get(), false,
                        Math.max(0, targetTicks), clientSpeed, player, false, pending.playlistEntryId);
            }
            return;
        }
        if (trackId == null || !trackId.equals(panel.getActiveTrackId())) {
            return;
        }
        Optional<MusicBattleTracks.Track> track = trackById(trackId);
        if (track.isEmpty()) {
            return;
        }
        invalidatePlaybackStartIntent(level, panel.getBlockPos());
        int runtimeDuration = scaledTick(track.get().durationTicks(), panel.getPlaybackSpeed());
        int sourceTargetTicks = Math.max(0, Math.round(targetTicks * clientSpeed));
        int serverTargetTicks = Math.max(0, Math.round(sourceTargetTicks
                / normalizeSpeed(panel.getPlaybackSpeed())));
        UUID previous = panel.seekPlayback(level.getGameTime(), serverTargetTicks, runtimeDuration);
        if (previous != null) {
            stopAudience(level.getServer(), previous);
        }
        requestAudienceRefresh();
    }

    private static void setPlaybackSpeed(ServerPlayer player, DjPanelBlockEntity panel, float requestedSpeed) {
        ServerLevel level = player.serverLevel();
        float speed = normalizeSpeed(requestedSpeed);
        PanelKey key = panelKey(level, panel.getBlockPos());
        PendingAudioIntent pending = PENDING_AUDIO_INTENTS.get(key);
        if (pending != null && pending.kind == AudioIntentKind.PLAYBACK_START
                && pending.matches(panel)) {
            Optional<MusicBattleTracks.Track> pendingTrack = trackById(pending.targetTrackId);
            if (pendingTrack.isPresent()) {
                int startTicks = pending.restart ? 0 : Math.max(0,
                        Math.round(pending.requestedStartSourceTicks / speed));
                // The last speed command must update the queued PLAY rather than
                // replace it with a speed change for the still-audible old track.
                queuePlaybackStart(level, panel, pendingTrack.get(), pending.restart,
                        startTicks, speed, player, false, pending.playlistEntryId);
                refreshPanelIfOpen(player, panel.getBlockPos());
                return;
            }
            PENDING_AUDIO_INTENTS.remove(key, pending);
        }
        if (Float.compare(speed, normalizeSpeed(panel.getPlaybackSpeed())) == 0) {
            invalidateAudioIntent(level, panel.getBlockPos());
            refreshPanelIfOpen(player, panel.getBlockPos());
            return;
        }
        Optional<MusicBattleTracks.Track> active = trackById(panel.getActiveTrackId());
        if (active.isPresent() && DjAudioPreparation.ready(active.get(), speed).isEmpty()) {
            queueSpeedChange(player, panel, active.get(), speed);
            return;
        }
        invalidateAudioIntent(level, panel.getBlockPos());
        applyPlaybackSpeed(level, panel, speed);
        refreshPanelIfOpen(player, panel.getBlockPos());
    }

    private static void applyPlaybackSpeed(ServerLevel level, DjPanelBlockEntity panel, float speed) {
        int sourceDuration = trackById(panel.getActiveTrackId())
                .map(MusicBattleTracks.Track::durationTicks).orElse(0);
        UUID previous = panel.setPlaybackSpeed(speed, level.getGameTime(), sourceDuration);
        if (previous != null) {
            stopAudience(level.getServer(), previous);
        }
        requestAudienceRefresh();
    }

    private static void stopPlayback(ServerLevel level, DjPanelBlockEntity panel) {
        invalidateAudioIntent(level, panel.getBlockPos());
        UUID stoppedId = panel.stopPlayback();
        if (stoppedId != null) {
            stopAudience(level.getServer(), stoppedId);
        }
    }

    private static boolean validPanelAccess(ServerPlayer player, BlockPos panelPos) {
        return player != null
                && panelPos != null
                && player.level().hasChunkAt(panelPos)
                && player.distanceToSqr(Vec3.atCenterOf(panelPos)) <= PANEL_USE_DISTANCE_SQUARED
                && player.level().getBlockEntity(panelPos) instanceof DjPanelBlockEntity;
    }

    private static boolean validLinkedSpeaker(ServerLevel level, DjPanelBlockEntity panel, BlockPos speakerPos) {
        return speakerPos != null
                && panel.getLinkedSpeakers().contains(speakerPos)
                && level.hasChunkAt(speakerPos)
                && level.getBlockEntity(speakerPos) instanceof SpeakerBlockEntity speaker
                && speaker.isControlledBy(panel.getBlockPos());
    }

    private static List<BlockPos> validSpeakers(ServerLevel level, DjPanelBlockEntity panel) {
        return panel.getLinkedSpeakers().stream()
                .filter(pos -> validLinkedSpeaker(level, panel, pos))
                .sorted(Comparator.comparingLong(BlockPos::asLong))
                .toList();
    }

    private static boolean validLinkedCamera(ServerLevel level, DjPanelBlockEntity panel, BlockPos cameraPos) {
        return cameraPos != null
                && panel.getLinkedCameras().contains(cameraPos)
                && level.hasChunkAt(cameraPos)
                && level.getBlockEntity(cameraPos) instanceof BattleCameraBlockEntity;
    }

    private static void pruneConnections(ServerLevel level, DjPanelBlockEntity panel) {
        for (BlockPos speakerPos : panel.getLinkedSpeakers()) {
            if (!level.hasChunkAt(speakerPos)) {
                continue;
            }
            if (!(level.getBlockEntity(speakerPos) instanceof SpeakerBlockEntity speaker)) {
                panel.unlinkSpeaker(speakerPos);
                continue;
            }
            // The speaker is the unlink source of truth. Shift+wire can clear it
            // while the panel chunk is unloaded; restoring from the stale panel
            // list here would silently reconnect it when that panel loads later.
            if (!speaker.isControlledBy(panel.getBlockPos())) {
                panel.unlinkSpeaker(speakerPos);
            }
        }
        for (BlockPos cameraPos : panel.getLinkedCameras()) {
            if (level.hasChunkAt(cameraPos)
                    && !(level.getBlockEntity(cameraPos) instanceof BattleCameraBlockEntity)) {
                panel.unlinkCamera(cameraPos);
            }
        }
    }

    /**
     * Whether a loaded DJ panel is playing or has queued this track. Removing a server beatmap is refused while it
     * is (spec 06 section 9.1): the audience is already downloading its audio.
     */
    public static boolean isTrackInUse(String trackId) {
        if (trackId == null || trackId.isEmpty()) {
            return false;
        }
        for (Map.Entry<ServerLevel, Set<BlockPos>> entry : LOADED_PANELS.entrySet()) {
            ServerLevel level = entry.getKey();
            if (level == null) {
                continue;
            }
            for (BlockPos pos : List.copyOf(entry.getValue())) {
                if (!level.hasChunkAt(pos) || !(level.getBlockEntity(pos) instanceof DjPanelBlockEntity panel)) {
                    continue;
                }
                if (trackId.equals(panel.getActiveTrackId()) || trackId.equals(panel.getSelectedTrackId())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static List<BlockPos> loadedPanelPositions(ServerLevel level) {
        Set<BlockPos> positions = LOADED_PANELS.get(level);
        if (positions == null || positions.isEmpty()) {
            return List.of();
        }
        positions.removeIf(pos -> !level.hasChunkAt(pos)
                || !(level.getBlockEntity(pos) instanceof DjPanelBlockEntity));
        return List.copyOf(positions);
    }

    private static void stopAudienceForPanel(ServerLevel level, BlockPos panelPos) {
        for (Map.Entry<UUID, SessionAudience> entry : new ArrayList<>(AUDIENCES.entrySet())) {
            SessionAudience audience = entry.getValue();
            if (audience.level == level && audience.panelPos.equals(panelPos)) {
                stopAudience(level.getServer(), entry.getKey());
            }
        }
    }

    private static void stopAudience(MinecraftServer server, UUID playbackId) {
        stopAudience(server, playbackId, false);
    }

    private static void stopAudience(MinecraftServer server, UUID playbackId, boolean releaseEffects) {
        SessionAudience session = AUDIENCES.remove(playbackId);
        if (session == null) {
            return;
        }
        PRELOAD_PLANS.remove(panelKey(session.level, session.panelPos));
        ASSET_TRANSFER_QUEUE.removeIf(transfer -> transfer.expectedSession == session);
        session.cameraRevision = nextCameraRevision(session.cameraRevision);
        BlockPos selectedCameraPos = session.cameraSnapshot == null
                ? BlockPos.ZERO : session.cameraSnapshot.cameraPos();
        for (UUID playerId : session.sources.keySet()) {
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            if (player != null) {
                PacketDistributor.sendToPlayer(player, DjAudienceCameraPayload.unavailable(
                        playbackId, selectedCameraPos, session.cameraRevision));
                PacketDistributor.sendToPlayer(player, new SpectatorStopPayload(playbackId, releaseEffects));
            }
        }
    }

    /** Accepts an end-to-end acknowledgement only for the exact live DJ asset. */
    public static void confirmAssetReady(ServerPlayer player, DjAssetReadyPayload payload) {
        SessionAudience session = AUDIENCES.get(payload.playbackId());
        if (session == null || player.level() != session.level
                || !session.sources.containsKey(player.getUUID())
                || !session.trackSnapshot.id().equals(payload.trackId())
                || !session.audioFileName.equals(payload.fileName())
                || !session.audioHash.equalsIgnoreCase(payload.sha256())) {
            return;
        }
        UUID playerId = player.getUUID();
        session.assetDeliveredPlayers.add(playerId);
        session.assetTransfersInFlight.remove(playerId);
        session.assetRetryAfter.remove(playerId);
    }

    private static void sendAudioAsset(ServerPlayer player, UUID playbackId, SessionAudience expectedSession,
                                       UUID transferToken, String trackId, String fileName, Path path, String hash) {
        UUID playerId = player.getUUID();
        if (!validAudioAsset(path, fileName)) {
            MusicBattleMod.LOGGER.warn("Refused unsafe DJ audio asset '{}'.", path);
            failAudioTransfer(playbackId, expectedSession, playerId, transferToken);
            return;
        }
        CompletableFuture<byte[]> sharedLoad = sharedAudioLoad(expectedSession, path, hash);
        sharedLoad.whenComplete((bytes, exception) -> player.server.execute(() -> {
            if (exception != null) {
                if (expectedSession.audioBytesFuture == sharedLoad) {
                    expectedSession.audioBytesFuture = null;
                }
                failAudioTransfer(playbackId, expectedSession, playerId, transferToken);
                return;
            }
            SessionAudience current = AUDIENCES.get(playbackId);
            AssetTransferStatus status = current == null ? null : current.assetTransfersInFlight.get(playerId);
            if (current != expectedSession || status == null || !transferToken.equals(status.token)) {
                return;
            }
            // The shared I/O future already verified length and SHA-256 off-thread.
            if (bytes == null || bytes.length <= 0 || bytes.length > MAX_AUDIO_BYTES
                    || player.connection == null
                    || player.server.getPlayerList().getPlayer(playerId) != player) {
                failAudioTransfer(playbackId, expectedSession, playerId, transferToken);
                return;
            }
            status.phase = AssetTransferPhase.QUEUED;
            status.deadline = current.level.getGameTime() + ASSET_TRANSFER_TIMEOUT_TICKS;
            ASSET_TRANSFER_QUEUE.addLast(new PendingAssetTransfer(
                    playbackId, expectedSession, playerId, transferToken,
                    trackId, transferFileName(fileName), hash, bytes));
        }));
    }

    private static CompletableFuture<byte[]> sharedAudioLoad(SessionAudience session, Path path, String hash) {
        CompletableFuture<byte[]> existing = session.audioBytesFuture;
        if (existing != null) {
            return existing;
        }
        synchronized (session) {
            existing = session.audioBytesFuture;
            if (existing == null) {
                existing = CompletableFuture.supplyAsync(() -> {
                    try {
                        byte[] bytes = Files.readAllBytes(path);
                        if (!validAudioBytes(bytes, hash)) {
                            throw new IOException("DJ audio changed while it was being loaded");
                        }
                        return bytes;
                    } catch (IOException exception) {
                        throw new CompletionException(exception);
                    }
                }, Util.nonCriticalIoPool());
                CompletableFuture<byte[]> created = existing;
                created.whenComplete((bytes, exception) -> {
                    if (exception != null) {
                        MusicBattleMod.LOGGER.warn("Unable to load shared DJ audio asset '{}'.", path, exception);
                    }
                });
                session.audioBytesFuture = created;
            }
            return existing;
        }
    }

    private static void failAudioTransfer(UUID playbackId, SessionAudience expectedSession,
                                          UUID playerId, UUID transferToken) {
        SessionAudience current = AUDIENCES.get(playbackId);
        AssetTransferStatus status = current == null ? null : current.assetTransfersInFlight.get(playerId);
        if (current != expectedSession || status == null || !transferToken.equals(status.token)) {
            return;
        }
        current.assetTransfersInFlight.remove(playerId);
        current.assetRetryAfter.put(playerId, current.level.getGameTime() + ASSET_RETRY_DELAY_TICKS);
    }

    private static void processAssetTransfers(MinecraftServer server) {
        int budget = ASSET_CHUNKS_PER_TICK;
        while (budget-- > 0 && !ASSET_TRANSFER_QUEUE.isEmpty()) {
            PendingAssetTransfer transfer = ASSET_TRANSFER_QUEUE.removeFirst();
            SessionAudience current = AUDIENCES.get(transfer.playbackId);
            AssetTransferStatus status = current == null ? null
                    : current.assetTransfersInFlight.get(transfer.playerId);
            if (current != transfer.expectedSession || status == null
                    || !transfer.transferToken.equals(status.token)
                    || status.phase != AssetTransferPhase.QUEUED) {
                continue;
            }
            ServerPlayer player = server.getPlayerList().getPlayer(transfer.playerId);
            if (player == null || player.connection == null || player.level() != current.level) {
                failAudioTransfer(transfer.playbackId, current, transfer.playerId, transfer.transferToken);
                continue;
            }
            int totalChunks = Math.max(1,
                    (transfer.bytes.length + ASSET_CHUNK_BYTES - 1) / ASSET_CHUNK_BYTES);
            int start = transfer.nextChunk * ASSET_CHUNK_BYTES;
            int end = Math.min(transfer.bytes.length, start + ASSET_CHUNK_BYTES);
            try {
                PacketDistributor.sendToPlayer(player, new TrackAssetChunkPayload(
                        transfer.trackId, "audio", transfer.fileName,
                        transfer.nextChunk, totalChunks, transfer.bytes.length, transfer.hash,
                        Arrays.copyOfRange(transfer.bytes, start, end)
                ));
            } catch (RuntimeException exception) {
                MusicBattleMod.LOGGER.warn("Unable to send DJ audio '{}' to {}.", transfer.fileName,
                        player.getGameProfile().getName(), exception);
                failAudioTransfer(transfer.playbackId, current, transfer.playerId, transfer.transferToken);
                continue;
            }
            transfer.nextChunk++;
            if (transfer.nextChunk < totalChunks) {
                status.deadline = current.level.getGameTime() + ASSET_TRANSFER_TIMEOUT_TICKS;
                ASSET_TRANSFER_QUEUE.addLast(transfer);
            } else {
                status.phase = AssetTransferPhase.WAITING_ACK;
                status.deadline = current.level.getGameTime() + ASSET_ACK_TIMEOUT_TICKS;
            }
        }
    }

    private static boolean validAudioBytes(byte[] bytes, String expectedHash) {
        if (bytes == null || bytes.length <= 0 || bytes.length > MAX_AUDIO_BYTES
                || expectedHash == null || !expectedHash.matches("[A-Fa-f0-9]{64}")) {
            return false;
        }
        try {
            return expectedHash.equalsIgnoreCase(HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void sendPreviewAssetAsync(ServerPlayer player, ServerLevel expectedLevel, BlockPos panelPos,
                                              MusicBattleTracks.Track track, Path path) {
        CompletableFuture.supplyAsync(() -> {
            try {
                byte[] bytes = Files.readAllBytes(path);
                if (bytes.length <= 0 || bytes.length > MAX_PREVIEW_BYTES) {
                    throw new IOException("Invalid DJ preview size: " + bytes.length);
                }
                return bytes;
            } catch (IOException exception) {
                throw new CompletionException(exception);
            }
        }, Util.nonCriticalIoPool()).thenAccept(bytes -> player.server.execute(() -> {
            if (player.connection == null || player.level() != expectedLevel || !validPanelAccess(player, panelPos)) {
                return;
            }
            String fileName = transferFileName(track.previewFile());
            if (!fileName.matches("[A-Za-z0-9._-]{1,128}") || fileName.contains("..")) {
                return;
            }
            String hash;
            try {
                hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            } catch (NoSuchAlgorithmException exception) {
                throw new IllegalStateException("SHA-256 is unavailable", exception);
            }
            int totalChunks = Math.max(1, (bytes.length + ASSET_CHUNK_BYTES - 1) / ASSET_CHUNK_BYTES);
            for (int chunkIndex = 0; chunkIndex < totalChunks; chunkIndex++) {
                int start = chunkIndex * ASSET_CHUNK_BYTES;
                int end = Math.min(bytes.length, start + ASSET_CHUNK_BYTES);
                PacketDistributor.sendToPlayer(player, new TrackAssetChunkPayload(
                        track.id(), "preview", fileName, chunkIndex, totalChunks, bytes.length, hash,
                        Arrays.copyOfRange(bytes, start, end)));
            }
        })).exceptionally(exception -> {
            MusicBattleMod.LOGGER.warn("Unable to send DJ preview asset '{}'.", path, exception);
            return null;
        });
    }

    private static boolean validPreviewAsset(Path path) {
        if (path == null || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        try {
            long size = Files.size(path);
            return size > 0L && size <= MAX_PREVIEW_BYTES;
        } catch (IOException exception) {
            return false;
        }
    }

    private static boolean validAudioAsset(Path path, String fileName) {
        String safeName = transferFileName(fileName);
        if (path == null || !safeName.matches("[A-Za-z0-9._-]{1,128}")
                || !safeName.toLowerCase(java.util.Locale.ROOT).endsWith(".ogg")
                || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        try {
            long size = Files.size(path);
            return size > 0L && size <= MAX_AUDIO_BYTES;
        } catch (IOException exception) {
            return false;
        }
    }

    private static AssetFingerprint fingerprint(Path path) {
        try {
            Path normalized = path.toAbsolutePath().normalize();
            long size = Files.size(normalized);
            long modified = Files.getLastModifiedTime(normalized).toMillis();
            AssetFingerprint cached = ASSET_FINGERPRINTS.get(normalized);
            if (cached != null && cached.size == size && cached.modified == modified) {
                return cached;
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(normalized)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read > 0) {
                        digest.update(buffer, 0, read);
                    }
                }
            }
            AssetFingerprint created = new AssetFingerprint(size, modified,
                    HexFormat.of().formatHex(digest.digest()));
            ASSET_FINGERPRINTS.put(normalized, created);
            return created;
        } catch (IOException | NoSuchAlgorithmException exception) {
            MusicBattleMod.LOGGER.warn("Unable to hash DJ audio asset '{}'.", path, exception);
            return null;
        }
    }

    private static String transferFileName(String fileName) {
        if (fileName == null) {
            return "";
        }
        String normalized = fileName.replace('\\', '/');
        int separator = normalized.lastIndexOf('/');
        return separator >= 0 ? normalized.substring(separator + 1) : normalized;
    }

    private static float effectiveVolume(float sourceVolume, double distance, float radius) {
        if (radius <= 0.0F || distance >= radius) {
            return 0.0F;
        }
        return Math.max(0.0F, sourceVolume) * Mth.clamp(1.0F - (float) distance / radius, 0.0F, 1.0F);
    }

    private static float normalizeSpeed(float speed) {
        if (!Float.isFinite(speed)) {
            return 1.0F;
        }
        if (Math.abs(speed - 2.0F) < 0.25F) {
            return 2.0F;
        }
        if (Math.abs(speed - 1.5F) < 0.25F) {
            return 1.5F;
        }
        return 1.0F;
    }

    private static String speedLabel(float speed) {
        float normalized = normalizeSpeed(speed);
        return normalized >= 1.9F ? "2" : normalized >= 1.4F ? "1.5" : "1";
    }

    private static PanelKey panelKey(ServerLevel level, BlockPos pos) {
        return new PanelKey(level.dimension(), pos.immutable());
    }

    private static PanelState panelState(DjPanelBlockEntity panel) {
        return new PanelState(panel.getPlaybackId(), panel.getActiveTrackId(), panel.getSelectedTrackId(),
                normalizeSpeed(panel.getPlaybackSpeed()), panel.isPlaying(), panel.isPlaybackPaused());
    }

    private static void warmSpeedVariants(MusicBattleTracks.Track track) {
        if (track == null) {
            return;
        }
        // Warm sequentially so background conversion never occupies both DJ
        // audio workers and delays a user-requested play/speed command.
        DjAudioPreparation.prepare(track, 1.0F)
                .thenCompose(ignored -> DjAudioPreparation.prepare(track, 1.5F))
                .thenCompose(ignored -> DjAudioPreparation.prepare(track, 2.0F))
                .exceptionally(exception -> {
            MusicBattleMod.LOGGER.debug("Unable to prewarm DJ speed audio for '{}'.", track.id(),
                    unwrapCompletion(exception));
            return null;
        });
    }

    private static void requestAudienceRefresh() {
        audienceRefreshRequested = true;
    }

    private static void invalidateAudioIntent(ServerLevel level, BlockPos pos) {
        if (level != null && pos != null) {
            PENDING_AUDIO_INTENTS.remove(panelKey(level, pos));
        }
    }

    private static void invalidatePlaybackStartIntent(ServerLevel level, BlockPos pos) {
        if (level == null || pos == null) {
            return;
        }
        PanelKey key = panelKey(level, pos);
        PendingAudioIntent current = PENDING_AUDIO_INTENTS.get(key);
        if (current != null && current.kind == AudioIntentKind.PLAYBACK_START) {
            PENDING_AUDIO_INTENTS.remove(key, current);
        }
    }

    private static void invalidatePlaylistIntent(ServerLevel level, BlockPos pos, UUID entryId) {
        if (level == null || pos == null) {
            return;
        }
        PanelKey key = panelKey(level, pos);
        PendingAudioIntent current = PENDING_AUDIO_INTENTS.get(key);
        if (current != null && current.kind == AudioIntentKind.PLAYBACK_START
                && current.playlistEntryId != null
                && (entryId == null || entryId.equals(current.playlistEntryId))) {
            PENDING_AUDIO_INTENTS.remove(key, current);
        }
    }

    private static Throwable unwrapCompletion(Throwable exception) {
        Throwable current = exception;
        while ((current instanceof CompletionException
                || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static int scaledTick(int tick, float speed) {
        return Math.max(0, Math.round(Math.max(0, tick) / normalizeSpeed(speed)));
    }

    private static int clampSourceCue(MusicBattleTracks.Track track, int sourceTicks) {
        int maximum = track == null ? 0 : Math.max(0, track.durationTicks() - 1);
        return Mth.clamp(Math.max(0, sourceTicks), 0, maximum);
    }

    private static int effectiveBpm(int bpm, float speed) {
        return Mth.clamp(Math.round(Math.max(1, bpm) * normalizeSpeed(speed)), 20, 800);
    }

    private static List<TrackEffect> scaledEffects(List<TrackEffect> effects, float speed) {
        if (effects == null || effects.isEmpty() || normalizeSpeed(speed) == 1.0F) {
            return effects == null ? List.of() : effects;
        }
        List<TrackEffect> scaled = new ArrayList<>(effects.size());
        for (TrackEffect effect : effects) {
            scaled.add(new TrackEffect(
                    effect.type(), effect.lane(), effect.layer(),
                    scaledTick(effect.tick(), speed), scaledTick(effect.durationTicks(), speed),
                    effect.intensity(), effect.softness(), effect.colorA(), effect.colorB(),
                    effect.text(), effect.font(), effect.positionX(), effect.positionY(),
                    effect.animation(), effect.exitAnimation(), effect.variant(), effect.beatReactive(),
                    effect.opacity(), effect.textSpeed(), effect.outlineThickness(), effect.glowStrength(),
                    effect.settings()));
        }
        return List.copyOf(scaled);
    }

    private record AudioSource(Vec3 position, float radius, float volume,
                               float lowGainDb, float midGainDb, float highGainDb,
                               float effectiveVolume, AudioSourceKey key) { }
    private record AudioSourceKey(long pos, float radius, float volume,
                                  float lowGainDb, float midGainDb, float highGainDb) { }
    private record AudienceSphere(Vec3 center, float radius) { }
    private record DjAudienceTargets(List<Integer> playerEntityIds, List<Integer> mobEntityIds) { }
    private record CameraSnapshot(boolean available, BlockPos cameraPos,
                                  double x, double y, double z,
                                  float yaw, float pitch, float fov) {
        private static CameraSnapshot unavailable(BlockPos selectedCameraPos) {
            return new CameraSnapshot(false,
                    selectedCameraPos == null ? BlockPos.ZERO : selectedCameraPos.immutable(),
                    0.0D, 0.0D, 0.0D, 0.0F, 0.0F,
                    BattleCameraBlockEntity.DEFAULT_FOV);
        }
    }
    private record AssetFingerprint(long size, long modified, String sha256) { }
    private record PreviewRequestKey(UUID playerId, ResourceKey<Level> dimension, long panelPos) { }
    private record LiveControlKey(UUID playerId, ResourceKey<Level> dimension, long panelPos, boolean panelFx) { }
    private record SpeedRequestKey(UUID playerId, ResourceKey<Level> dimension, long panelPos) { }
    private record PanelKey(ResourceKey<Level> dimension, BlockPos pos) { }
    private record PendingAudioIntent(UUID token, UUID requesterId, PanelState expectedState,
                                      AudioIntentKind kind, String targetTrackId, UUID playlistEntryId,
                                       boolean restart, int requestedStartSourceTicks,
                                       float requestedSpeed) {
        private PendingAudioIntent withExpectedState(PanelState state) {
            return new PendingAudioIntent(token, requesterId, state, kind, targetTrackId,
                    playlistEntryId, restart, requestedStartSourceTicks, requestedSpeed);
        }

        private boolean matches(DjPanelBlockEntity panel) {
            if (kind == AudioIntentKind.PLAYBACK_START) {
                return expectedState.matches(panel);
            }
            if (Float.compare(expectedState.playbackSpeed,
                    normalizeSpeed(panel.getPlaybackSpeed())) != 0) {
                return false;
            }
            if (!expectedState.activeTrackId.isBlank()) {
                return targetTrackId.equals(panel.getActiveTrackId());
            }
            return panel.getActiveTrackId().isBlank()
                    && targetTrackId.equals(panel.getSelectedTrackId());
        }
    }
    private record PanelState(UUID playbackId, String activeTrackId, String selectedTrackId,
                              float playbackSpeed, boolean playing, boolean paused) {
        private boolean matches(DjPanelBlockEntity panel) {
            return java.util.Objects.equals(playbackId, panel.getPlaybackId())
                    && activeTrackId.equals(panel.getActiveTrackId())
                    && selectedTrackId.equals(panel.getSelectedTrackId())
                    && Float.compare(playbackSpeed, normalizeSpeed(panel.getPlaybackSpeed())) == 0
                    && playing == panel.isPlaying()
                    && paused == panel.isPlaybackPaused();
        }
    }
    private record PlaylistFollowUp(MusicBattleTracks.Track track, UUID playlistEntryId, int sourceCue) { }
    private record ActiveDjSession(DjPanelBlockEntity panel, MusicBattleTracks.Track track,
                                   List<BlockPos> speakers, int elapsed) { }
    private record DjClaim(ActiveDjSession session, AudioSource source, double distanceSquared,
                           float audibleGain) { }

    private enum AssetTransferPhase {
        LOADING,
        QUEUED,
        WAITING_ACK
    }

    private enum AudioIntentKind {
        PLAYBACK_START,
        SPEED_CHANGE
    }

    /** One panel's ordered, one-at-a-time warm-up of the assets its queue will need next. */
    private static final class PreloadPlan {
        private final UUID playbackId;
        private final long playlistRevision;
        private final float speed;
        private final List<String> trackIds;
        private final Set<UUID> offeredTo = new HashSet<>();
        private final Set<UUID> confirmed = new HashSet<>();
        private final Set<UUID> pendingAnswers = new HashSet<>();
        private int index;
        private boolean offered;
        private boolean transferring;
        private String fileName = "";
        private String sha256 = "";
        private long deadline;
        private long retryAfter;

        private PreloadPlan(UUID playbackId, long playlistRevision, float speed, List<String> trackIds) {
            this.playbackId = playbackId;
            this.playlistRevision = playlistRevision;
            this.speed = speed;
            this.trackIds = trackIds;
        }

        private void beginOffer(String fileName, String sha256, long deadline) {
            resetEntry();
            this.offered = true;
            this.fileName = fileName;
            this.sha256 = sha256;
            this.deadline = deadline;
        }

        private void nextEntry() {
            index++;
            resetEntry();
        }

        private void resetEntry() {
            offered = false;
            transferring = false;
            fileName = "";
            sha256 = "";
            deadline = 0L;
            retryAfter = 0L;
            offeredTo.clear();
            confirmed.clear();
            pendingAnswers.clear();
        }
    }

    private static final class AssetTransferStatus {
        private final UUID token;
        private AssetTransferPhase phase;
        private long deadline;

        private AssetTransferStatus(UUID token, AssetTransferPhase phase, long deadline) {
            this.token = token;
            this.phase = phase;
            this.deadline = deadline;
        }
    }

    private static final class PendingAssetTransfer {
        private final UUID playbackId;
        private final SessionAudience expectedSession;
        private final UUID playerId;
        private final UUID transferToken;
        private final String trackId;
        private final String fileName;
        private final String hash;
        private final byte[] bytes;
        private int nextChunk;

        private PendingAssetTransfer(UUID playbackId, SessionAudience expectedSession,
                                     UUID playerId, UUID transferToken, String trackId,
                                     String fileName, String hash, byte[] bytes) {
            this.playbackId = playbackId;
            this.expectedSession = expectedSession;
            this.playerId = playerId;
            this.transferToken = transferToken;
            this.trackId = trackId;
            this.fileName = fileName;
            this.hash = hash;
            this.bytes = bytes;
        }
    }

    private static final class SessionAudience {
        private final ServerLevel level;
        private final BlockPos panelPos;
        private final MusicBattleTracks.Track trackSnapshot;
        private final String audioFileName;
        private final String audioHash;
        private final Map<UUID, AudioSourceKey> sources = new HashMap<>();
        private final Set<UUID> audiblePlayers = new HashSet<>();
        private final Set<UUID> assetDeliveredPlayers = new HashSet<>();
        private final Map<UUID, AssetTransferStatus> assetTransfersInFlight = new HashMap<>();
        private final Map<UUID, Long> assetRetryAfter = new HashMap<>();
        private long panelDspRevision = Long.MIN_VALUE;
        private long panelVisualFxRevision = Long.MIN_VALUE;
        private CameraSnapshot cameraSnapshot;
        private long cameraRevision;
        private volatile CompletableFuture<byte[]> audioBytesFuture;

        private SessionAudience(ServerLevel level, BlockPos panelPos,
                                MusicBattleTracks.Track trackSnapshot, String audioFileName,
                                String audioHash) {
            this.level = level;
            this.panelPos = panelPos;
            this.trackSnapshot = trackSnapshot;
            this.audioFileName = audioFileName;
            this.audioHash = audioHash;
        }
    }
}

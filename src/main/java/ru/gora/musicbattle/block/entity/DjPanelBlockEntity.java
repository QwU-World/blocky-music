package ru.gora.musicbattle.block.entity;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import ru.gora.musicbattle.registry.ModBlockEntities;
import ru.gora.musicbattle.data.DjVisualEffectConfig;
import ru.gora.musicbattle.data.DjVisualEffects;
import ru.gora.musicbattle.data.DjPlaylistEntry;
import ru.gora.musicbattle.data.DjWorkshopSource;
import ru.gora.musicbattle.server.DjPlaybackManager;

/** Persistent DJ installation state. Playback and audience authority stay server-side. */
public final class DjPanelBlockEntity extends BlockEntity {
    public static final int MAX_LINKED_SPEAKERS = 64;
    public static final int MAX_LINKED_CAMERAS = 16;
    /** How many beatmaps the booth's own list may hold. */
    public static final int MAX_LIBRARY_TRACKS = 128;

    private final LinkedHashSet<BlockPos> linkedSpeakers = new LinkedHashSet<>();
    private final LinkedHashSet<BlockPos> linkedCameras = new LinkedHashSet<>();
    private BlockPos selectedSpeakerPos;
    private BlockPos selectedCameraPos;
    private boolean editAllSpeakers;
    private String selectedTrackId = "";
    private String activeTrackId = "";
    private float playbackSpeed = 1.0F;
    private UUID playbackId;
    private long playbackStartedGameTime;
    private int playbackOffsetTicks;
    private boolean playbackPaused;
    private boolean dspEnabled = true;
    private boolean reverbEnabled;
    private float reverbWet = 0.30F;
    private float reverbRoom = 0.65F;
    private float reverbDamping = 0.45F;
    private boolean echoEnabled;
    private float echoWet = 0.25F;
    private float echoTimeMs = 280.0F;
    private float echoFeedback = 0.35F;
    private boolean lowPassEnabled;
    private float lowPassCutoffHz = 12_000.0F;
    private long dspRevision;
    private boolean trackVisualFxEnabled = true;
    private boolean djVisualFxEnabled;
    private List<DjVisualEffectConfig> djVisualEffects = List.of();
    private int visualFxActivationTick;
    private long visualFxRevision;
    private final List<DjPlaylistEntry> playlist = new ArrayList<>();
    private UUID activePlaylistEntryId;
    /**
     * Keeps the queue cursor stable when the entry that is currently playing is removed.
     * A separate flag is required because a detached last entry intentionally has no successor;
     * treating that state like a regular null active id would wrap back to the first entry.
     */
    private boolean detachedPlaylistCursor;
    private UUID detachedPlaylistSuccessorId;
    private boolean playlistAutoAdvance = true;
    private PlaylistRepeat playlistRepeat = PlaylistRepeat.OFF;
    private long playlistRevision;
    /**
     * The beatmaps the DJ put on this booth with "+", in the order they came: the list the panel shows.
     *
     * It used to show every map the server had, which on a server with a busy Workshop meant scrolling past
     * everyone's downloads to find the three songs of the evening. It only says which maps the DJ wants at hand;
     * nothing here reaches the playlist, which the DJ fills from this list with the playlist's own Add button.
     */
    private final List<String> library = new ArrayList<>();
    /**
     * For each Workshop beatmap this booth uses that the server holds only in its {@code workshop_cache}, the set and
     * content hash it was read from. Without them the booth's row stopped resolving after a restart, because the
     * cached charts were known only in memory, and the cache clean-up could delete a set the booth still listed.
     * Kept for exactly the ids the booth still uses ({@link #workshopReferencesChanged}).
     */
    private final Map<String, DjWorkshopSource> workshopSources = new LinkedHashMap<>();

    public DjPanelBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.DJ_PANEL.get(), pos, state);
    }

    public Set<BlockPos> getLinkedSpeakers() {
        return Set.copyOf(linkedSpeakers);
    }

    public Set<BlockPos> getLinkedCameras() {
        return Set.copyOf(linkedCameras);
    }

    public BlockPos getSelectedSpeakerPos() {
        return selectedSpeakerPos;
    }

    public boolean isEditingAllSpeakers() {
        return editAllSpeakers;
    }

    public String getSelectedTrackId() {
        return selectedTrackId;
    }

    public float getPlaybackSpeed() {
        return playbackSpeed;
    }

    public List<DjPlaylistEntry> getPlaylist() {
        return List.copyOf(playlist);
    }

    public UUID getActivePlaylistEntryId() {
        return activePlaylistEntryId;
    }

    public boolean isPlaylistAutoAdvance() {
        return playlistAutoAdvance;
    }

    public PlaylistRepeat getPlaylistRepeat() {
        return playlistRepeat;
    }

    public long getPlaylistRevision() {
        return playlistRevision;
    }

    /** The booth's own beatmap list, oldest first. */
    public List<String> getLibrary() {
        return List.copyOf(library);
    }

    /** The cached Workshop sets behind this booth's {@code ws_} ids, by track id (see {@link DjWorkshopSource}). */
    public Map<String, DjWorkshopSource> getWorkshopSources() {
        return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(workshopSources));
    }

    public boolean isInLibrary(String trackId) {
        return trackId != null && library.contains(trackId);
    }

    /**
     * Puts a beatmap at the end of the booth's list. False when it is already there, the list is full or the id
     * could never name a track. Clients learn about it from the next panel refresh, so this does not sync.
     */
    public boolean addToLibrary(String trackId) {
        if (!usableTrackId(trackId) || library.contains(trackId) || library.size() >= MAX_LIBRARY_TRACKS) {
            return false;
        }
        library.add(trackId);
        workshopReferencesChanged();
        setChanged();
        return true;
    }

    /** Takes a beatmap off the booth's list. Its rows in the playlist, and the song playing, stay as they are. */
    public boolean removeFromLibrary(String trackId) {
        boolean removed = trackId != null && library.remove(trackId);
        if (removed) {
            workshopReferencesChanged();
            setChanged();
        }
        return removed;
    }

    /**
     * Keeps {@link #workshopSources} to the ids this booth still uses and adds the cached set behind any new one,
     * then hands the result to the server's world-wide list, which reads the charts back after a restart and keeps
     * the cache clean-up away from them. Every change to the list, the playlist, the deck or the selection comes
     * through here, so no path can put a cached map on the booth without its source, and a booth from before the
     * sources existed gets them when it loads. Without a server level (a client copy, a check) it only prunes.
     */
    private void workshopReferencesChanged() {
        Set<String> referenced = DjWorkshopSource.referenced(library,
                playlist.stream().map(DjPlaylistEntry::trackId).toList(), activeTrackId, selectedTrackId);
        boolean changed = workshopSources.keySet().retainAll(referenced);
        if (level instanceof ServerLevel serverLevel) {
            // The set this run read each new id from; for a booth saved by 0.1.0-beta.1, which kept ids only, the
            // set is looked up in the cache folders once, unless the catalogue lists the id.
            changed |= ru.gora.musicbattle.server.DjWorkshopCache.adoptSources(workshopSources, referenced,
                    trackId -> ru.gora.musicbattle.server.MusicBattleTracks.byId(trackId).isPresent());
            ru.gora.musicbattle.server.DjWorkshopCache.boothReferences(serverLevel, worldPosition, workshopSources);
        }
        if (changed) {
            setChanged();
        }
    }

    private static boolean usableTrackId(String trackId) {
        return trackId != null && !trackId.isBlank() && trackId.length() <= DjPlaylistEntry.MAX_TRACK_ID_LENGTH;
    }

    public DjPlaylistEntry findPlaylistEntry(UUID entryId) {
        if (entryId == null) {
            return null;
        }
        return playlist.stream().filter(entry -> entry.entryId().equals(entryId)).findFirst().orElse(null);
    }

    public DjPlaylistEntry nextPlaylistEntry() {
        return nextPlaylistEntry(ignored -> true);
    }

    /** Returns the next matching entry without ever wrapping past the end of the queue. */
    public DjPlaylistEntry nextPlaylistEntry(Predicate<DjPlaylistEntry> predicate) {
        if (playlist.isEmpty()) {
            return null;
        }
        int firstCandidate;
        if (detachedPlaylistCursor) {
            if (detachedPlaylistSuccessorId == null) {
                return null;
            }
            firstCandidate = playlistIndex(detachedPlaylistSuccessorId);
        } else if (activePlaylistEntryId == null) {
            firstCandidate = 0;
        } else {
            int activeIndex = playlistIndex(activePlaylistEntryId);
            firstCandidate = activeIndex < 0 ? playlist.size() : activeIndex + 1;
        }
        if (firstCandidate < 0 || firstCandidate >= playlist.size()) {
            return null;
        }
        for (int index = firstCandidate; index < playlist.size(); index++) {
            DjPlaylistEntry candidate = playlist.get(index);
            if (predicate.test(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /** First matching row, used by repeat-all to loop the queue back to its beginning. */
    public DjPlaylistEntry firstPlaylistEntry(Predicate<DjPlaylistEntry> predicate) {
        for (DjPlaylistEntry candidate : playlist) {
            if (predicate.test(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    public DjPlaylistEntry addPlaylistEntry(String trackId, int startSourceTicks) {
        if (playlist.size() >= DjPlaylistEntry.MAX_ENTRIES) {
            return null;
        }
        final DjPlaylistEntry entry;
        try {
            UUID entryId;
            do {
                entryId = UUID.randomUUID();
            } while (findPlaylistEntry(entryId) != null);
            entry = new DjPlaylistEntry(entryId, trackId, startSourceTicks);
        } catch (IllegalArgumentException exception) {
            return null;
        }
        playlist.add(entry);
        if (detachedPlaylistCursor && detachedPlaylistSuccessorId == null) {
            // The playing entry was removed at the end of the queue. A later append is now
            // genuinely after that cursor and should become the next song without wrapping.
            detachedPlaylistSuccessorId = entry.entryId();
        }
        playlistChanged();
        return entry;
    }

    public boolean removePlaylistEntry(UUID entryId) {
        int index = playlistIndex(entryId);
        if (index < 0) {
            return false;
        }
        UUID successorId = index + 1 < playlist.size() ? playlist.get(index + 1).entryId() : null;
        boolean removedActiveEntry = entryId.equals(activePlaylistEntryId);
        boolean removedDetachedSuccessor = detachedPlaylistCursor
                && entryId.equals(detachedPlaylistSuccessorId);
        playlist.remove(index);
        if (removedActiveEntry) {
            activePlaylistEntryId = null;
            detachedPlaylistCursor = true;
            detachedPlaylistSuccessorId = successorId;
        } else if (removedDetachedSuccessor) {
            detachedPlaylistSuccessorId = successorId;
        }
        playlistChanged();
        return true;
    }

    public boolean movePlaylistEntry(UUID entryId, int targetIndex) {
        int sourceIndex = playlistIndex(entryId);
        if (sourceIndex < 0 || targetIndex < 0 || targetIndex >= playlist.size()) {
            return false;
        }
        if (sourceIndex == targetIndex) {
            return true;
        }
        DjPlaylistEntry entry = playlist.remove(sourceIndex);
        playlist.add(targetIndex, entry);
        playlistChanged();
        return true;
    }

    public boolean setPlaylistEntryCue(UUID entryId, int startSourceTicks) {
        int index = playlistIndex(entryId);
        if (index < 0) {
            return false;
        }
        DjPlaylistEntry current = playlist.get(index);
        DjPlaylistEntry replacement = current.withStartSourceTicks(startSourceTicks);
        if (replacement.equals(current)) {
            return true;
        }
        playlist.set(index, replacement);
        playlistChanged();
        return true;
    }

    public void clearPlaylist() {
        if (playlist.isEmpty() && activePlaylistEntryId == null
                && !detachedPlaylistCursor && detachedPlaylistSuccessorId == null) {
            return;
        }
        playlist.clear();
        activePlaylistEntryId = null;
        detachedPlaylistCursor = false;
        detachedPlaylistSuccessorId = null;
        playlistChanged();
    }

    public boolean setPlaylistEntryEffects(UUID entryId, DjPlaylistEntry.FxMode mode, List<ru.gora.musicbattle.data.TrackEffect> effects) {
        int index = playlistIndex(entryId);
        if (index < 0) return false;
        DjPlaylistEntry replacement = playlist.get(index).withEffects(mode, effects);
        return replacePlaylistEntry(replacement);
    }

    public boolean replacePlaylistEntry(DjPlaylistEntry replacement) {
        int index = playlistIndex(replacement.entryId());
        if (index < 0) return false;
        if (replacement.equals(playlist.get(index))) return false;
        DjPlaylistEntry previous = playlist.get(index);
        if (replacement.entryId().equals(activePlaylistEntryId) && level != null
                && replacement.mixer() != null && replacement.mixer().djFxEnabled()
                && !replacement.mixer().effects().isEmpty()
                && (previous.mixer() == null || !previous.mixer().djFxEnabled() || previous.mixer().effects().isEmpty())) {
            visualFxActivationTick = getPlaybackPositionTicks(level.getGameTime());
        }
        playlist.set(index, replacement);
        playlistChanged();
        visualFxRevision++;
        return true;
    }

    public void setPlaylistAutoAdvance(boolean enabled) {
        if (playlistAutoAdvance != enabled) {
            playlistAutoAdvance = enabled;
            playlistChanged();
        }
    }

    public void setPlaylistRepeat(PlaylistRepeat repeat) {
        PlaylistRepeat safe = repeat == null ? PlaylistRepeat.OFF : repeat;
        if (playlistRepeat != safe) {
            playlistRepeat = safe;
            playlistChanged();
        }
    }

    public BlockPos getSelectedCameraPos() {
        return selectedCameraPos;
    }

    public boolean isPlaying() {
        return playbackId != null && !activeTrackId.isBlank();
    }

    public String getActiveTrackId() {
        return activeTrackId;
    }

    public UUID getPlaybackId() {
        return playbackId;
    }

    public long getPlaybackStartedGameTime() {
        return playbackStartedGameTime;
    }

    public boolean isPlaybackPaused() {
        return playbackPaused && !activeTrackId.isBlank();
    }

    public int getPlaybackPositionTicks(long gameTime) {
        if (!isPlaying()) {
            return Math.max(0, playbackOffsetTicks);
        }
        long elapsed = Math.max(0L, gameTime - playbackStartedGameTime);
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0L, playbackOffsetTicks + elapsed));
    }

    public boolean isDspEnabled() {
        return dspEnabled;
    }

    public boolean isReverbEnabled() {
        return reverbEnabled;
    }

    public float getReverbWet() {
        return reverbWet;
    }

    public float getReverbRoom() {
        return reverbRoom;
    }

    public float getReverbDamping() {
        return reverbDamping;
    }

    public boolean isEchoEnabled() {
        return echoEnabled;
    }

    public float getEchoWet() {
        return echoWet;
    }

    public float getEchoTimeMs() {
        return echoTimeMs;
    }

    public float getEchoFeedback() {
        return echoFeedback;
    }

    public boolean isLowPassEnabled() {
        return lowPassEnabled;
    }

    public float getLowPassCutoffHz() {
        return lowPassCutoffHz;
    }

    /** Runtime-only revision used to coalesce panel FX broadcasts on the server tick. */
    public long getDspRevision() {
        return dspRevision;
    }

    public boolean isTrackVisualFxEnabled() {
        return trackVisualFxEnabled;
    }

    public boolean isDjVisualFxEnabled() {
        return djVisualFxEnabled;
    }

    public List<DjVisualEffectConfig> getDjVisualEffects() {
        return djVisualEffects;
    }

    public int getVisualFxActivationTick() {
        return Math.max(0, visualFxActivationTick);
    }

    /** Runtime revision lets the audience manager push visual changes without restarting audio. */
    public long getVisualFxRevision() {
        return visualFxRevision;
    }

    public void setVisualEffects(boolean trackEnabled, boolean djEnabled,
                                 List<DjVisualEffectConfig> effects, int activationTick) {
        List<DjVisualEffectConfig> safeEffects = DjVisualEffects.sanitizeConfigs(effects);
        boolean beginDjEffects = djEnabled && !safeEffects.isEmpty()
                && (!this.djVisualFxEnabled || this.djVisualEffects.isEmpty());
        boolean djStateChanged = this.djVisualFxEnabled != djEnabled
                || !this.djVisualEffects.equals(safeEffects);
        if (this.trackVisualFxEnabled == trackEnabled && !djStateChanged) {
            return;
        }
        this.trackVisualFxEnabled = trackEnabled;
        this.djVisualFxEnabled = djEnabled;
        this.djVisualEffects = safeEffects;
        // Live inspector changes must alter the already-running effect instead
        // of restarting its fade/beat timeline on every slider or text edit.
        // A genuinely new enabled set still begins at the current playhead.
        if (beginDjEffects) {
            this.visualFxActivationTick = Math.max(0, activationTick);
        }
        visualFxRevision++;
        sync();
    }

    /**
     * Puts the rack's own sound back to how it ships: no reverb, no echo, no low pass.
     *
     * The visual effects are not touched. For a while this reset cleared the DJ effect stack as well, and pressing
     * "Reset speakers" wiped a Visual FX tab the DJ had spent an evening building; the owner asked for the speakers'
     * button to stay with sound. The Visual FX tab has its own controls for clearing what it holds.
     */
    public void resetSound() {
        setDspSettings(true, false, 0.30F, 0.65F, 0.45F, false, 0.25F, 280.0F, 0.35F, false, 12_000.0F);
    }

    /**
     * Whoever last started a track here, for as long as the server stays up.
     *
     * Not saved: it exists so an advancement can be handed to the person actually at the decks, and after a restart
     * nobody is.
     */
    private java.util.UUID lastOperatorId;

    /** Remembers who is at the decks. */
    public void setLastOperator(java.util.UUID playerId) {
        lastOperatorId = playerId;
    }

    public java.util.UUID getLastOperator() {
        return lastOperatorId;
    }

    public void setDspSettings(boolean dspEnabled,
                               boolean reverbEnabled, float reverbWet, float reverbRoom, float reverbDamping,
                               boolean echoEnabled, float echoWet, float echoTimeMs, float echoFeedback,
                               boolean lowPassEnabled, float lowPassCutoffHz) {
        float safeReverbWet = unit(reverbWet, 0.30F);
        float safeReverbRoom = unit(reverbRoom, 0.65F);
        float safeReverbDamping = unit(reverbDamping, 0.45F);
        float safeEchoWet = Mth.clamp(finite(echoWet, 0.25F), 0.0F, 0.75F);
        float safeEchoTimeMs = Mth.clamp(finite(echoTimeMs, 280.0F), 40.0F, 1_000.0F);
        float safeEchoFeedback = Mth.clamp(finite(echoFeedback, 0.35F), 0.0F, 0.85F);
        float safeLowPassCutoffHz = Mth.clamp(finite(lowPassCutoffHz, 12_000.0F), 200.0F, 20_000.0F);
        if (this.dspEnabled == dspEnabled
                && this.reverbEnabled == reverbEnabled
                && Float.compare(this.reverbWet, safeReverbWet) == 0
                && Float.compare(this.reverbRoom, safeReverbRoom) == 0
                && Float.compare(this.reverbDamping, safeReverbDamping) == 0
                && this.echoEnabled == echoEnabled
                && Float.compare(this.echoWet, safeEchoWet) == 0
                && Float.compare(this.echoTimeMs, safeEchoTimeMs) == 0
                && Float.compare(this.echoFeedback, safeEchoFeedback) == 0
                && this.lowPassEnabled == lowPassEnabled
                && Float.compare(this.lowPassCutoffHz, safeLowPassCutoffHz) == 0) {
            return;
        }
        this.dspEnabled = dspEnabled;
        this.reverbEnabled = reverbEnabled;
        this.reverbWet = safeReverbWet;
        this.reverbRoom = safeReverbRoom;
        this.reverbDamping = safeReverbDamping;
        this.echoEnabled = echoEnabled;
        this.echoWet = safeEchoWet;
        this.echoTimeMs = safeEchoTimeMs;
        this.echoFeedback = safeEchoFeedback;
        this.lowPassEnabled = lowPassEnabled;
        this.lowPassCutoffHz = safeLowPassCutoffHz;
        dspRevision++;
        sync();
    }

    public boolean linkSpeaker(BlockPos speakerPos) {
        if (speakerPos == null || linkedSpeakers.size() >= MAX_LINKED_SPEAKERS) {
            return false;
        }
        boolean added = linkedSpeakers.add(speakerPos.immutable());
        if (added) {
            if (selectedSpeakerPos == null) {
                selectedSpeakerPos = speakerPos.immutable();
            }
            sync();
        }
        return added;
    }

    public boolean unlinkSpeaker(BlockPos speakerPos) {
        boolean removed = speakerPos != null && linkedSpeakers.remove(speakerPos);
        if (removed) {
            if (speakerPos.equals(selectedSpeakerPos)) {
                selectedSpeakerPos = linkedSpeakers.stream().findFirst().orElse(null);
            }
            sync();
        }
        return removed;
    }

    public boolean selectSpeaker(BlockPos speakerPos) {
        if (speakerPos == null || !linkedSpeakers.contains(speakerPos)) {
            return false;
        }
        BlockPos immutable = speakerPos.immutable();
        if (!immutable.equals(selectedSpeakerPos)) {
            selectedSpeakerPos = immutable;
            sync();
        }
        return true;
    }

    public void setEditingAllSpeakers(boolean editAllSpeakers) {
        if (this.editAllSpeakers != editAllSpeakers) {
            this.editAllSpeakers = editAllSpeakers;
            sync();
        }
    }

    public void selectTrack(String trackId) {
        String safe = trackId == null ? "" : trackId;
        if (!safe.equals(selectedTrackId)) {
            selectedTrackId = safe;
            workshopReferencesChanged();
            sync();
        }
    }

    public boolean linkCamera(BlockPos cameraPos) {
        if (cameraPos == null) {
            return false;
        }
        BlockPos immutable = cameraPos.immutable();
        if (linkedCameras.contains(immutable)) {
            return true;
        }
        if (linkedCameras.size() >= MAX_LINKED_CAMERAS) {
            return false;
        }
        boolean added = linkedCameras.add(immutable);
        if (selectedCameraPos == null) {
            selectedCameraPos = immutable;
        }
        if (added) {
            sync();
        }
        return added;
    }

    public boolean unlinkCamera(BlockPos cameraPos) {
        boolean removed = cameraPos != null && linkedCameras.remove(cameraPos);
        if (removed) {
            if (cameraPos.equals(selectedCameraPos)) {
                selectedCameraPos = linkedCameras.stream().findFirst().orElse(null);
            }
            sync();
        }
        return removed;
    }

    public boolean selectCamera(BlockPos cameraPos) {
        if (cameraPos == null || !linkedCameras.contains(cameraPos)) {
            return false;
        }
        BlockPos immutable = cameraPos.immutable();
        if (!immutable.equals(selectedCameraPos)) {
            selectedCameraPos = immutable;
            sync();
        }
        return true;
    }

    public void startPlayback(String trackId, long gameTime) {
        startPlayback(trackId, gameTime, 0, null);
    }

    public void startPlayback(String trackId, long gameTime, int offsetTicks) {
        startPlayback(trackId, gameTime, offsetTicks, null);
    }

    public void startPlayback(String trackId, long gameTime, int offsetTicks, UUID playlistEntryId) {
        String requestedTrackId = trackId == null ? "" : trackId;
        // Only a manual seek/resume continues the same logical playback. Replaying a playlist
        // entry (including a neighbouring duplicate track id) is a fresh song/FX phase.
        boolean sameManualTrack = playlistEntryId == null && requestedTrackId.equals(activeTrackId);
        boolean continuingTrack = sameManualTrack && Math.max(0, offsetTicks) > 0;
        int previousVisualActivation = visualFxActivationTick;
        activeTrackId = requestedTrackId;
        if (!activeTrackId.isBlank()) {
            selectedTrackId = activeTrackId;
        }
        playbackId = activeTrackId.isBlank() ? null : UUID.randomUUID();
        playbackStartedGameTime = Math.max(0L, gameTime);
        playbackOffsetTicks = activeTrackId.isBlank() ? 0 : Math.max(0, offsetTicks);
        visualFxActivationTick = continuingTrack
                ? Math.min(Math.max(0, previousVisualActivation), playbackOffsetTicks) : 0;
        playbackPaused = false;
        if (!sameManualTrack) {
            DjPlaylistEntry playlistEntry = findPlaylistEntry(playlistEntryId);
            UUID safePlaylistEntryId = playlistEntry != null && playlistEntry.trackId().equals(activeTrackId)
                    ? playlistEntryId : null;
            if (!java.util.Objects.equals(activePlaylistEntryId, safePlaylistEntryId)
                    || detachedPlaylistCursor || detachedPlaylistSuccessorId != null) {
                activePlaylistEntryId = safePlaylistEntryId;
                detachedPlaylistCursor = false;
                detachedPlaylistSuccessorId = null;
                advancePlaylistRevision();
            }
        }
        if (!sameManualTrack) {
            workshopReferencesChanged();
        }
        sync();
    }

    /**
     * Changes the tempo while preserving the same source-audio point. Runtime
     * ticks are shorter at faster speeds, matching the pitch-preserved audio
     * variants and the scaled FX timeline sent by the server.
     */
    public UUID setPlaybackSpeed(float requestedSpeed, long gameTime, int sourceDurationTicks) {
        float safeSpeed = normalizePlaybackSpeed(requestedSpeed);
        if (Float.compare(playbackSpeed, safeSpeed) == 0) {
            return null;
        }
        UUID previous = playbackId;
        int currentRuntimeTicks = getPlaybackPositionTicks(gameTime);
        int sourceTicks = Math.max(0, Math.round(currentRuntimeTicks * playbackSpeed));
        int visualActivationSourceTicks = Math.max(0, Math.round(visualFxActivationTick * playbackSpeed));
        int maximumRuntimeTicks = scaledTicks(sourceDurationTicks, safeSpeed);
        playbackOffsetTicks = Mth.clamp(Math.round(sourceTicks / safeSpeed), 0, maximumRuntimeTicks);
        visualFxActivationTick = Mth.clamp(Math.round(visualActivationSourceTicks / safeSpeed),
                0, playbackOffsetTicks);
        playbackSpeed = safeSpeed;
        if (previous != null && !activeTrackId.isBlank()) {
            playbackId = UUID.randomUUID();
            playbackStartedGameTime = Math.max(0L, gameTime);
            playbackPaused = false;
        } else {
            playbackId = null;
            playbackStartedGameTime = 0L;
            playbackPaused = !activeTrackId.isBlank();
        }
        sync();
        return previous;
    }

    public void resumePlayback(String trackId, long gameTime) {
        String requested = trackId == null ? "" : trackId;
        int offset = requested.equals(activeTrackId) ? playbackOffsetTicks : 0;
        startPlayback(requested, gameTime, offset, null);
    }

    public UUID pausePlayback(long gameTime, int maximumTicks) {
        UUID stoppedId = playbackId;
        playbackOffsetTicks = Mth.clamp(getPlaybackPositionTicks(gameTime), 0, Math.max(0, maximumTicks));
        playbackId = null;
        playbackStartedGameTime = 0L;
        playbackPaused = !activeTrackId.isBlank();
        sync();
        return stoppedId;
    }

    /** Moves the persistent playhead and rotates playback identity if audio is running. */
    public UUID seekPlayback(long gameTime, int targetTicks, int maximumTicks) {
        UUID previous = playbackId;
        playbackOffsetTicks = Mth.clamp(targetTicks, 0, Math.max(0, maximumTicks));
        visualFxActivationTick = Math.min(Math.max(0, visualFxActivationTick), playbackOffsetTicks);
        if (isPlaying()) {
            playbackId = UUID.randomUUID();
            playbackStartedGameTime = Math.max(0L, gameTime);
            playbackPaused = false;
        } else {
            playbackId = null;
            playbackStartedGameTime = 0L;
            playbackPaused = !activeTrackId.isBlank();
        }
        sync();
        return previous;
    }

    public UUID stopPlayback() {
        UUID stoppedId = playbackId;
        activeTrackId = "";
        playbackId = null;
        playbackStartedGameTime = 0L;
        playbackOffsetTicks = 0;
        playbackPaused = false;
        if (activePlaylistEntryId != null || detachedPlaylistCursor || detachedPlaylistSuccessorId != null) {
            activePlaylistEntryId = null;
            detachedPlaylistCursor = false;
            detachedPlaylistSuccessorId = null;
            advancePlaylistRevision();
        }
        sync();
        return stoppedId;
    }

    public void restorePlaybackIdentity() {
        if (!activeTrackId.isBlank() && playbackId == null && !playbackPaused) {
            playbackId = UUID.randomUUID();
            sync();
        }
    }

    private void sync() {
        setChanged();
        if (level != null && !level.isClientSide) {
            BlockState state = getBlockState();
            level.sendBlockUpdated(worldPosition, state, state, 3);
        }
    }

    private int playlistIndex(UUID entryId) {
        if (entryId == null) {
            return -1;
        }
        for (int index = 0; index < playlist.size(); index++) {
            if (entryId.equals(playlist.get(index).entryId())) {
                return index;
            }
        }
        return -1;
    }

    private void playlistChanged() {
        advancePlaylistRevision();
        workshopReferencesChanged();
        sync();
    }

    private void advancePlaylistRevision() {
        playlistRevision = playlistRevision == Long.MAX_VALUE ? 1L : playlistRevision + 1L;
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        long[] speakers = linkedSpeakers.stream().limit(MAX_LINKED_SPEAKERS).mapToLong(BlockPos::asLong).toArray();
        tag.putLongArray("LinkedSpeakers", speakers);
        if (selectedSpeakerPos != null && linkedSpeakers.contains(selectedSpeakerPos)) {
            tag.putLong("SelectedSpeaker", selectedSpeakerPos.asLong());
        }
        long[] cameras = linkedCameras.stream().limit(MAX_LINKED_CAMERAS).mapToLong(BlockPos::asLong).toArray();
        tag.putLongArray("LinkedCameras", cameras);
        if (selectedCameraPos != null && linkedCameras.contains(selectedCameraPos)) {
            tag.putLong("SelectedCamera", selectedCameraPos.asLong());
        }
        tag.putBoolean("EditAllSpeakers", editAllSpeakers);
        tag.putString("SelectedTrack", selectedTrackId);
        tag.putString("ActiveTrack", activeTrackId);
        tag.putFloat("PlaybackSpeed", playbackSpeed);
        if (playbackId != null) {
            tag.putUUID("PlaybackId", playbackId);
        }
        tag.putLong("PlaybackStarted", playbackStartedGameTime);
        tag.putInt("PlaybackOffsetTicks", Math.max(0, playbackOffsetTicks));
        tag.putBoolean("PlaybackPaused", playbackPaused);
        tag.putBoolean("DspEnabled", dspEnabled);
        tag.putBoolean("ReverbEnabled", reverbEnabled);
        tag.putFloat("ReverbWet", reverbWet);
        tag.putFloat("ReverbRoom", reverbRoom);
        tag.putFloat("ReverbDamping", reverbDamping);
        tag.putBoolean("EchoEnabled", echoEnabled);
        tag.putFloat("EchoWet", echoWet);
        tag.putFloat("EchoTimeMs", echoTimeMs);
        tag.putFloat("EchoFeedback", echoFeedback);
        tag.putBoolean("LowPassEnabled", lowPassEnabled);
        tag.putFloat("LowPassCutoffHz", lowPassCutoffHz);
        tag.putBoolean("TrackVisualFxEnabled", trackVisualFxEnabled);
        tag.putBoolean("DjVisualFxEnabled", djVisualFxEnabled);
        tag.putString("DjVisualEffects", DjVisualEffectConfig.encodeList(djVisualEffects));
        tag.putInt("VisualFxActivationTick", Math.max(0, visualFxActivationTick));
        ListTag playlistTag = new ListTag();
        for (DjPlaylistEntry entry : playlist.stream().limit(DjPlaylistEntry.MAX_ENTRIES).toList()) {
            playlistTag.add(entry.toTag());
        }
        tag.put("DjPlaylist", playlistTag);
        if (activePlaylistEntryId != null && findPlaylistEntry(activePlaylistEntryId) != null) {
            tag.putUUID("ActivePlaylistEntry", activePlaylistEntryId);
        }
        tag.putBoolean("DetachedPlaylistCursor", detachedPlaylistCursor);
        if (detachedPlaylistCursor && detachedPlaylistSuccessorId != null
                && findPlaylistEntry(detachedPlaylistSuccessorId) != null) {
            tag.putUUID("DetachedPlaylistSuccessor", detachedPlaylistSuccessorId);
        }
        tag.putBoolean("PlaylistAutoAdvance", playlistAutoAdvance);
        tag.putString("PlaylistRepeat", playlistRepeat.name());
        tag.putLong("PlaylistRevision", Math.max(0L, playlistRevision));
        ListTag libraryTag = new ListTag();
        for (String trackId : library) {
            libraryTag.add(StringTag.valueOf(trackId));
        }
        tag.put("DjLibrary", libraryTag);
        if (!workshopSources.isEmpty()) {
            tag.put(DjWorkshopSource.TAG, DjWorkshopSource.write(workshopSources));
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        linkedSpeakers.clear();
        long[] speakers = tag.getLongArray("LinkedSpeakers");
        for (int index = 0; index < Math.min(speakers.length, MAX_LINKED_SPEAKERS); index++) {
            linkedSpeakers.add(BlockPos.of(speakers[index]));
        }
        selectedSpeakerPos = tag.contains("SelectedSpeaker") ? BlockPos.of(tag.getLong("SelectedSpeaker")) : null;
        if (selectedSpeakerPos == null || !linkedSpeakers.contains(selectedSpeakerPos)) {
            selectedSpeakerPos = linkedSpeakers.stream().findFirst().orElse(null);
        }
        linkedCameras.clear();
        long[] cameras = tag.getLongArray("LinkedCameras");
        for (int index = 0; index < Math.min(cameras.length, MAX_LINKED_CAMERAS); index++) {
            linkedCameras.add(BlockPos.of(cameras[index]));
        }
        selectedCameraPos = tag.contains("SelectedCamera") ? BlockPos.of(tag.getLong("SelectedCamera")) : null;
        if (selectedCameraPos == null || !linkedCameras.contains(selectedCameraPos)) {
            selectedCameraPos = linkedCameras.stream().findFirst().orElse(null);
        }
        editAllSpeakers = tag.contains("EditAllSpeakers") && tag.getBoolean("EditAllSpeakers");
        activeTrackId = tag.getString("ActiveTrack");
        selectedTrackId = tag.contains("SelectedTrack") ? tag.getString("SelectedTrack") : activeTrackId;
        playbackSpeed = tag.contains("PlaybackSpeed")
                ? normalizePlaybackSpeed(tag.getFloat("PlaybackSpeed")) : 1.0F;
        playbackId = tag.hasUUID("PlaybackId") ? tag.getUUID("PlaybackId") : null;
        playbackStartedGameTime = Math.max(0L, tag.getLong("PlaybackStarted"));
        playbackOffsetTicks = Math.max(0, tag.getInt("PlaybackOffsetTicks"));
        playbackPaused = tag.contains("PlaybackPaused") && tag.getBoolean("PlaybackPaused");
        dspEnabled = !tag.contains("DspEnabled") || tag.getBoolean("DspEnabled");
        reverbEnabled = tag.contains("ReverbEnabled") && tag.getBoolean("ReverbEnabled");
        reverbWet = tag.contains("ReverbWet") ? unit(tag.getFloat("ReverbWet"), 0.30F) : 0.30F;
        reverbRoom = tag.contains("ReverbRoom") ? unit(tag.getFloat("ReverbRoom"), 0.65F) : 0.65F;
        reverbDamping = tag.contains("ReverbDamping") ? unit(tag.getFloat("ReverbDamping"), 0.45F) : 0.45F;
        echoEnabled = tag.contains("EchoEnabled") && tag.getBoolean("EchoEnabled");
        echoWet = tag.contains("EchoWet")
                ? Mth.clamp(finite(tag.getFloat("EchoWet"), 0.25F), 0.0F, 0.75F) : 0.25F;
        echoTimeMs = tag.contains("EchoTimeMs")
                ? Mth.clamp(finite(tag.getFloat("EchoTimeMs"), 280.0F), 40.0F, 1_000.0F) : 280.0F;
        echoFeedback = tag.contains("EchoFeedback")
                ? Mth.clamp(finite(tag.getFloat("EchoFeedback"), 0.35F), 0.0F, 0.85F) : 0.35F;
        lowPassEnabled = tag.contains("LowPassEnabled") && tag.getBoolean("LowPassEnabled");
        lowPassCutoffHz = tag.contains("LowPassCutoffHz")
                ? Mth.clamp(finite(tag.getFloat("LowPassCutoffHz"), 12_000.0F), 200.0F, 20_000.0F)
                : 12_000.0F;
        trackVisualFxEnabled = !tag.contains("TrackVisualFxEnabled") || tag.getBoolean("TrackVisualFxEnabled");
        djVisualFxEnabled = tag.contains("DjVisualFxEnabled") && tag.getBoolean("DjVisualFxEnabled");
        djVisualEffects = DjVisualEffects.sanitizeConfigs(DjVisualEffectConfig.decodeList(tag.getString("DjVisualEffects")));
        visualFxActivationTick = Math.max(0, tag.getInt("VisualFxActivationTick"));
        playlist.clear();
        Set<UUID> loadedEntryIds = new HashSet<>();
        ListTag playlistTag = tag.getList("DjPlaylist", Tag.TAG_COMPOUND);
        for (int index = 0; index < Math.min(playlistTag.size(), DjPlaylistEntry.MAX_ENTRIES); index++) {
            CompoundTag entryTag = playlistTag.getCompound(index);
            if (!entryTag.hasUUID("EntryId")) {
                continue;
            }
            UUID entryId = entryTag.getUUID("EntryId");
            String trackId = entryTag.getString("TrackId");
            int startSourceTicks = entryTag.getInt("StartSourceTicks");
            if (!loadedEntryIds.add(entryId) || trackId.isBlank()
                    || trackId.length() > DjPlaylistEntry.MAX_TRACK_ID_LENGTH || startSourceTicks < 0) {
                continue;
            }
            try {
                playlist.add(DjPlaylistEntry.fromTag(entryTag));
            } catch (RuntimeException ignored) {
                // Preserve the song/cue even if an old or damaged FX snapshot cannot load.
                playlist.add(new DjPlaylistEntry(entryId, trackId, startSourceTicks));
            }
        }
        activePlaylistEntryId = tag.hasUUID("ActivePlaylistEntry")
                ? tag.getUUID("ActivePlaylistEntry") : null;
        DjPlaylistEntry activePlaylistEntry = findPlaylistEntry(activePlaylistEntryId);
        if (activePlaylistEntry == null || !activePlaylistEntry.trackId().equals(activeTrackId)) {
            activePlaylistEntryId = null;
        }
        detachedPlaylistCursor = activePlaylistEntryId == null
                && tag.contains("DetachedPlaylistCursor")
                && tag.getBoolean("DetachedPlaylistCursor");
        detachedPlaylistSuccessorId = detachedPlaylistCursor && tag.hasUUID("DetachedPlaylistSuccessor")
                ? tag.getUUID("DetachedPlaylistSuccessor") : null;
        if (detachedPlaylistSuccessorId != null && findPlaylistEntry(detachedPlaylistSuccessorId) == null) {
            // Preserve the detached end-of-queue sentinel instead of wrapping to the first entry.
            detachedPlaylistSuccessorId = null;
        }
        playlistAutoAdvance = !tag.contains("PlaylistAutoAdvance") || tag.getBoolean("PlaylistAutoAdvance");
        // An absent or unknown name keeps the pre-repeat behaviour of older panels.
        playlistRepeat = PlaylistRepeat.byName(tag.getString("PlaylistRepeat"));
        playlistRevision = Math.max(0L, tag.getLong("PlaylistRevision"));
        library.clear();
        if (tag.contains("DjLibrary", Tag.TAG_LIST)) {
            ListTag libraryTag = tag.getList("DjLibrary", Tag.TAG_STRING);
            for (int index = 0; index < libraryTag.size() && library.size() < MAX_LIBRARY_TRACKS; index++) {
                String trackId = libraryTag.getString(index);
                if (usableTrackId(trackId) && !library.contains(trackId)) {
                    library.add(trackId);
                }
            }
        } else if (tag.contains("DjPlaylist", Tag.TAG_LIST)) {
            // A booth saved before it had a list of its own. Its queue, its deck and the card it had selected are maps
            // already chosen for it, so they start the list rather than leaving the panel empty above a playlist that
            // still plays, or with Play aimed at a song the list does not show. The client's copy is loaded from the
            // update tag, which has no playlist, and so stays empty.
            List<String> seeds = new ArrayList<>();
            playlist.forEach(entry -> seeds.add(entry.trackId()));
            seeds.add(activeTrackId);
            seeds.add(selectedTrackId);
            for (String trackId : seeds) {
                if (usableTrackId(trackId) && !library.contains(trackId) && library.size() < MAX_LIBRARY_TRACKS) {
                    library.add(trackId);
                }
            }
        }
        workshopSources.clear();
        workshopSources.putAll(DjWorkshopSource.read(tag, DjWorkshopSource.TAG, DjPlaylistEntry.MAX_TRACK_ID_LENGTH));
        Set<String> referenced = DjWorkshopSource.referenced(library,
                playlist.stream().map(DjPlaylistEntry::trackId).toList(), activeTrackId, selectedTrackId);
        workshopSources.keySet().retainAll(referenced);
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    /**
     * What a client is told about this rack, both in the chunk packet and in every sync() after an edit.
     *
     * The only client reader of this block entity is its renderer, and that needs nothing beyond the playback clock.
     * This used to be the whole saved state, playlist included. Rows set to Track or DJ FX each hold a full copy of
     * a chart, so a long set grew past the 2 MiB that the chunk packet's NbtAccounter allows for one block entity, and
     * every player who then loaded the chunk was disconnected, in single-player too. The DJ gets the playlist and
     * its effects through OpenDjPanelPayload when the panel opens, and the disk copy in saveAdditional keeps all of
     * it, so leaving them out here costs nothing. Because this tag is built fresh from the server's state on every
     * send, a world that already holds an oversized panel becomes enterable again with this build.
     */
    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        // The renderer only asks whether a track is loaded, so a damaged overlong id is cut rather than trusted.
        tag.putString("ActiveTrack", activeTrackId.length() > DjPlaylistEntry.MAX_TRACK_ID_LENGTH
                ? activeTrackId.substring(0, DjPlaylistEntry.MAX_TRACK_ID_LENGTH) : activeTrackId);
        tag.putFloat("PlaybackSpeed", playbackSpeed);
        if (playbackId != null) {
            tag.putUUID("PlaybackId", playbackId);
        }
        tag.putLong("PlaybackStarted", playbackStartedGameTime);
        tag.putInt("PlaybackOffsetTicks", Math.max(0, playbackOffsetTicks));
        tag.putBoolean("PlaybackPaused", playbackPaused);
        tag.putBoolean("DspEnabled", dspEnabled);
        tag.putBoolean("TrackVisualFxEnabled", trackVisualFxEnabled);
        tag.putBoolean("DjVisualFxEnabled", djVisualFxEnabled);
        return tag;
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level instanceof ServerLevel serverLevel) {
            restorePlaybackIdentity();
            DjPlaybackManager.register(serverLevel, worldPosition);
            // Reads this booth's cached Workshop sets back if this run has not yet, and puts the booth on the world's
            // list, which also covers a booth saved before the list existed.
            workshopReferencesChanged();
        }
    }

    @Override
    public void setRemoved() {
        if (level instanceof ServerLevel serverLevel) {
            DjPlaybackManager.unregister(serverLevel, worldPosition);
        }
        super.setRemoved();
    }

    private static float unit(float value, float fallback) {
        return Mth.clamp(finite(value, fallback), 0.0F, 1.0F);
    }

    private static float finite(float value, float fallback) {
        return Float.isFinite(value) ? value : fallback;
    }

    private static float normalizePlaybackSpeed(float speed) {
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

    private static int scaledTicks(int ticks, float speed) {
        return Math.max(0, Math.round(Math.max(0, ticks) / normalizePlaybackSpeed(speed)));
    }

    /** What happens when the current song ends: nothing, replay it, or loop the whole queue. */
    public enum PlaylistRepeat {
        OFF,
        ONE,
        ALL;

        public static PlaylistRepeat byOrdinal(int value) {
            PlaylistRepeat[] values = values();
            return value < 0 || value >= values.length ? OFF : values[value];
        }

        private static PlaylistRepeat byName(String name) {
            for (PlaylistRepeat value : values()) {
                if (value.name().equals(name)) {
                    return value;
                }
            }
            return OFF;
        }
    }
}

package ru.gora.musicbattle.network;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import ru.gora.musicbattle.MusicBattleMod;
import ru.gora.musicbattle.data.DjVisualEffectConfig;
import ru.gora.musicbattle.data.DjPlaylistEntry;
import ru.gora.musicbattle.data.TrackSummary;
import ru.gora.musicbattle.data.TrackEffect;

public record OpenDjPanelPayload(
        BlockPos panelPos,
        List<TrackSummary> tracks,
        List<SpeakerEntry> speakers,
        int activeSpeakerIndex,
        boolean editAllSpeakers,
        List<CameraEntry> cameras,
        int activeCameraIndex,
        boolean playing,
        String activeTrackId,
        String selectedTrackId,
        float playbackSpeed,
        long speedResponseRevision,
        boolean paused,
        int playbackPositionTicks,
        int trackDurationTicks,
        boolean dspEnabled,
        boolean reverbEnabled,
        float reverbWet,
        float reverbRoom,
        float reverbDamping,
        boolean echoEnabled,
        float echoWet,
        float echoTimeMs,
        float echoFeedback,
        boolean lowPassEnabled,
        float lowPassCutoffHz,
        boolean trackVisualFxEnabled,
        boolean djVisualFxEnabled,
        List<DjVisualEffectConfig> djVisualEffects,
        List<DjPlaylistEntry> playlist,
        UUID activePlaylistEntryId,
        boolean playlistAutoAdvance,
        int playlistRepeat,
        long playlistRevision,
        List<TrackEffect> activeVisualEffects,
        int activeVisualBpm,
        UUID playbackId,
        float activeVisualSpeed,
        int activeVisualPositionTicks,
        List<Integer> activePlayerEntityIds,
        List<Integer> activeMobEntityIds,
        /** True when the server had more tracks than the list carries (spec 06 section 9.4). */
        boolean tracksTruncated
) implements CustomPacketPayload {
    public static final int MAX_TRACKS = 512;
    private static final int MAX_SPEAKERS = 64;
    private static final int MAX_CAMERAS = 16;
    /** Highest accepted ordinal of {@code DjPanelBlockEntity.PlaylistRepeat}. */
    private static final int MAX_REPEAT_MODE = 2;
    public static final int MAX_ACTIVE_PLAYERS = 256;
    public static final int MAX_ACTIVE_MOBS = 1024;
    public static final Type<OpenDjPanelPayload> TYPE = new Type<>(MusicBattleMod.id("open_dj_panel"));

    public OpenDjPanelPayload {
        activePlayerEntityIds = playing || paused ? List.copyOf(activePlayerEntityIds) : List.of();
        activeMobEntityIds = playing || paused ? List.copyOf(activeMobEntityIds) : List.of();
    }

    public static final StreamCodec<RegistryFriendlyByteBuf, OpenDjPanelPayload> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public OpenDjPanelPayload decode(RegistryFriendlyByteBuf buffer) {
            BlockPos panelPos = buffer.readBlockPos();
            int trackCount = readCount(buffer, MAX_TRACKS, "tracks");
            List<TrackSummary> tracks = new ArrayList<>(trackCount);
            for (int index = 0; index < trackCount; index++) {
                tracks.add(TrackSummary.read(buffer));
            }
            int speakerCount = readCount(buffer, MAX_SPEAKERS, "speakers");
            List<SpeakerEntry> speakers = new ArrayList<>(speakerCount);
            for (int index = 0; index < speakerCount; index++) {
                speakers.add(new SpeakerEntry(buffer.readBlockPos(), buffer.readFloat(), buffer.readFloat(),
                        buffer.readFloat(), buffer.readFloat(), buffer.readFloat()));
            }
            int activeSpeakerIndex = buffer.readVarInt() - 1;
            if (activeSpeakerIndex < -1 || activeSpeakerIndex >= speakerCount) {
                activeSpeakerIndex = -1;
            }
            boolean editAllSpeakers = buffer.readBoolean();
            int cameraCount = readCount(buffer, MAX_CAMERAS, "cameras");
            List<CameraEntry> cameras = new ArrayList<>(cameraCount);
            for (int index = 0; index < cameraCount; index++) {
                cameras.add(new CameraEntry(buffer.readBlockPos(), buffer.readBoolean(),
                        buffer.readDouble(), buffer.readDouble(), buffer.readDouble(),
                        buffer.readFloat(), buffer.readFloat(), buffer.readFloat()));
            }
            int activeCameraIndex = buffer.readVarInt() - 1;
            if (activeCameraIndex < -1 || activeCameraIndex >= cameraCount) {
                activeCameraIndex = -1;
            }
            boolean playing = buffer.readBoolean();
            String activeTrackId = buffer.readUtf(255);
            String selectedTrackId = buffer.readUtf(255);
            float playbackSpeed = buffer.readFloat();
            long speedResponseRevision = buffer.readVarLong();
            boolean paused = buffer.readBoolean();
            int playbackPositionTicks = buffer.readVarInt();
            int trackDurationTicks = buffer.readVarInt();
            boolean dspEnabled = buffer.readBoolean();
            boolean reverbEnabled = buffer.readBoolean();
            float reverbWet = buffer.readFloat();
            float reverbRoom = buffer.readFloat();
            float reverbDamping = buffer.readFloat();
            boolean echoEnabled = buffer.readBoolean();
            float echoWet = buffer.readFloat();
            float echoTimeMs = buffer.readFloat();
            float echoFeedback = buffer.readFloat();
            boolean lowPassEnabled = buffer.readBoolean();
            float lowPassCutoffHz = buffer.readFloat();
            boolean trackVisualFxEnabled = buffer.readBoolean();
            boolean djVisualFxEnabled = buffer.readBoolean();
            List<DjVisualEffectConfig> djVisualEffects = DjVisualEffectConfig.readList(buffer);
            int playlistCount = readCount(buffer, DjPlaylistEntry.MAX_ENTRIES, "playlist");
            List<DjPlaylistEntry> playlist = new ArrayList<>(playlistCount);
            for (int index = 0; index < playlistCount; index++) {
                playlist.add(DjPlaylistEntry.read(buffer));
            }
            UUID activePlaylistEntryId = buffer.readBoolean() ? buffer.readUUID() : null;
            boolean playlistAutoAdvance = buffer.readBoolean();
            int playlistRepeat = buffer.readVarInt();
            long playlistRevision = buffer.readVarLong();
            int effectCount = readCount(buffer, TrackEffect.MAX_EFFECTS, "active effects");
            List<TrackEffect> activeEffects = new ArrayList<>(effectCount);
            for (int index = 0; index < effectCount; index++) {
                activeEffects.add(TrackEffect.read(buffer));
            }
            int activeVisualBpm = buffer.readVarInt();
            UUID playbackId = buffer.readBoolean() ? buffer.readUUID() : null;
            float activeVisualSpeed = buffer.readFloat();
            int activeVisualPositionTicks = buffer.readVarInt();
            List<Integer> activePlayerEntityIds = readEntityIds(buffer, MAX_ACTIVE_PLAYERS, "active players");
            List<Integer> activeMobEntityIds = readEntityIds(buffer, MAX_ACTIVE_MOBS, "active mobs");
            if (activeVisualBpm < 1 || activeVisualBpm > 800 || !Float.isFinite(activeVisualSpeed)
                    || activeVisualSpeed < 1.0F || activeVisualSpeed > 2.0F || activeVisualPositionTicks < 0) {
                throw new IllegalArgumentException("Invalid DJ monitor timing");
            }
            if (speedResponseRevision < 0L || playbackPositionTicks < 0 || trackDurationTicks < 0
                    || playlistRevision < 0L
                    || playlistRepeat < 0 || playlistRepeat > MAX_REPEAT_MODE) {
                throw new IllegalArgumentException("Invalid DJ panel state bounds");
            }
            return new OpenDjPanelPayload(panelPos, List.copyOf(tracks), List.copyOf(speakers),
                    activeSpeakerIndex, editAllSpeakers, List.copyOf(cameras), activeCameraIndex,
                    playing, activeTrackId, selectedTrackId, playbackSpeed, speedResponseRevision, paused,
                    playbackPositionTicks, trackDurationTicks, dspEnabled, reverbEnabled,
                    reverbWet, reverbRoom, reverbDamping,
                    echoEnabled, echoWet, echoTimeMs, echoFeedback,
                    lowPassEnabled, lowPassCutoffHz,
                    trackVisualFxEnabled, djVisualFxEnabled, djVisualEffects,
                    List.copyOf(playlist), activePlaylistEntryId, playlistAutoAdvance, playlistRepeat,
                    playlistRevision,
                    List.copyOf(activeEffects), activeVisualBpm, playbackId, activeVisualSpeed, activeVisualPositionTicks,
                    activePlayerEntityIds, activeMobEntityIds, buffer.readBoolean());
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buffer, OpenDjPanelPayload payload) {
            buffer.writeBlockPos(payload.panelPos());
            int trackCount = Math.min(payload.tracks().size(), MAX_TRACKS);
            buffer.writeVarInt(trackCount);
            for (int index = 0; index < trackCount; index++) {
                payload.tracks().get(index).write(buffer);
            }
            int speakerCount = Math.min(payload.speakers().size(), MAX_SPEAKERS);
            buffer.writeVarInt(speakerCount);
            for (int index = 0; index < speakerCount; index++) {
                SpeakerEntry entry = payload.speakers().get(index);
                buffer.writeBlockPos(entry.pos());
                buffer.writeFloat(entry.radius());
                buffer.writeFloat(entry.volume());
                buffer.writeFloat(entry.lowGainDb());
                buffer.writeFloat(entry.midGainDb());
                buffer.writeFloat(entry.highGainDb());
            }
            int activeSpeakerIndex = payload.activeSpeakerIndex() >= 0
                    && payload.activeSpeakerIndex() < speakerCount ? payload.activeSpeakerIndex() : -1;
            buffer.writeVarInt(activeSpeakerIndex + 1);
            buffer.writeBoolean(payload.editAllSpeakers());
            int cameraCount = Math.min(payload.cameras().size(), MAX_CAMERAS);
            buffer.writeVarInt(cameraCount);
            for (int index = 0; index < cameraCount; index++) {
                CameraEntry entry = payload.cameras().get(index);
                buffer.writeBlockPos(entry.pos());
                buffer.writeBoolean(entry.available());
                buffer.writeDouble(entry.x());
                buffer.writeDouble(entry.y());
                buffer.writeDouble(entry.z());
                buffer.writeFloat(entry.yaw());
                buffer.writeFloat(entry.pitch());
                buffer.writeFloat(entry.fov());
            }
            int activeCameraIndex = payload.activeCameraIndex() >= 0
                    && payload.activeCameraIndex() < cameraCount ? payload.activeCameraIndex() : -1;
            buffer.writeVarInt(activeCameraIndex + 1);
            buffer.writeBoolean(payload.playing());
            buffer.writeUtf(payload.activeTrackId() == null ? "" : payload.activeTrackId(), 255);
            buffer.writeUtf(payload.selectedTrackId() == null ? "" : payload.selectedTrackId(), 255);
            buffer.writeFloat(payload.playbackSpeed());
            buffer.writeVarLong(Math.max(0L, payload.speedResponseRevision()));
            buffer.writeBoolean(payload.paused());
            buffer.writeVarInt(Math.max(0, payload.playbackPositionTicks()));
            buffer.writeVarInt(Math.max(0, payload.trackDurationTicks()));
            buffer.writeBoolean(payload.dspEnabled());
            buffer.writeBoolean(payload.reverbEnabled());
            buffer.writeFloat(payload.reverbWet());
            buffer.writeFloat(payload.reverbRoom());
            buffer.writeFloat(payload.reverbDamping());
            buffer.writeBoolean(payload.echoEnabled());
            buffer.writeFloat(payload.echoWet());
            buffer.writeFloat(payload.echoTimeMs());
            buffer.writeFloat(payload.echoFeedback());
            buffer.writeBoolean(payload.lowPassEnabled());
            buffer.writeFloat(payload.lowPassCutoffHz());
            buffer.writeBoolean(payload.trackVisualFxEnabled());
            buffer.writeBoolean(payload.djVisualFxEnabled());
            DjVisualEffectConfig.writeList(buffer, payload.djVisualEffects());
            int playlistCount = Math.min(payload.playlist().size(), DjPlaylistEntry.MAX_ENTRIES);
            buffer.writeVarInt(playlistCount);
            for (int index = 0; index < playlistCount; index++) {
                DjPlaylistEntry entry = payload.playlist().get(index);
                // Inactive rows need metadata only. Only one editable chart travels in this packet.
                (entry.entryId().equals(payload.activePlaylistEntryId()) ? entry
                        : entry.withEffects(entry.fxMode(), List.of())).write(buffer);
            }
            UUID activePlaylistEntryId = payload.activePlaylistEntryId();
            boolean activeEntryPresent = activePlaylistEntryId != null
                    && payload.playlist().stream().limit(playlistCount)
                    .anyMatch(entry -> activePlaylistEntryId.equals(entry.entryId()));
            buffer.writeBoolean(activeEntryPresent);
            if (activeEntryPresent) {
                buffer.writeUUID(activePlaylistEntryId);
            }
            buffer.writeBoolean(payload.playlistAutoAdvance());
            buffer.writeVarInt(Math.max(0, Math.min(MAX_REPEAT_MODE, payload.playlistRepeat())));
            buffer.writeVarLong(Math.max(0L, payload.playlistRevision()));
            int effectCount = Math.min(payload.activeVisualEffects().size(), TrackEffect.MAX_EFFECTS);
            buffer.writeVarInt(effectCount);
            for (int index = 0; index < effectCount; index++) {
                payload.activeVisualEffects().get(index).write(buffer);
            }
            buffer.writeVarInt(payload.activeVisualBpm());
            buffer.writeBoolean(payload.playbackId() != null);
            if (payload.playbackId() != null) buffer.writeUUID(payload.playbackId());
            buffer.writeFloat(payload.activeVisualSpeed());
            buffer.writeVarInt(payload.activeVisualPositionTicks());
            writeEntityIds(buffer, payload.activePlayerEntityIds(), MAX_ACTIVE_PLAYERS);
            writeEntityIds(buffer, payload.activeMobEntityIds(), MAX_ACTIVE_MOBS);
            buffer.writeBoolean(payload.tracksTruncated() || trackCount < payload.tracks().size());
        }
    };

    private static List<Integer> readEntityIds(RegistryFriendlyByteBuf buffer, int maximum, String label) {
        int count = readCount(buffer, maximum, label);
        List<Integer> ids = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            int id = buffer.readVarInt();
            if (id < 0) {
                throw new IllegalArgumentException("Invalid DJ monitor entity id: " + id);
            }
            ids.add(id);
        }
        return List.copyOf(ids);
    }

    private static void writeEntityIds(RegistryFriendlyByteBuf buffer, List<Integer> ids, int maximum) {
        int count = Math.min(maximum, ids.size());
        buffer.writeVarInt(count);
        for (int index = 0; index < count; index++) {
            int id = ids.get(index);
            if (id < 0) {
                throw new IllegalArgumentException("Invalid DJ monitor entity id: " + id);
            }
            buffer.writeVarInt(id);
        }
    }

    private static int readCount(RegistryFriendlyByteBuf buffer, int maximum, String label) {
        int count = buffer.readVarInt();
        if (count < 0 || count > maximum) {
            throw new IllegalArgumentException("Invalid DJ panel " + label + " count: " + count);
        }
        return count;
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public record SpeakerEntry(BlockPos pos, float radius, float volume,
                               float lowGainDb, float midGainDb, float highGainDb) {
    }

    public record CameraEntry(BlockPos pos, boolean available,
                              double x, double y, double z,
                              float yaw, float pitch, float fov) {
    }
}

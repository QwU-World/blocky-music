package ru.gora.musicbattle.network;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import ru.gora.musicbattle.MusicBattleMod;
import ru.gora.musicbattle.data.DjVisualEffectConfig;

public record ConfigureDjPanelPayload(
        BlockPos panelPos,
        Action action,
        BlockPos targetPos,
        float radius,
        float volume,
        float lowGainDb,
        float midGainDb,
        float highGainDb,
        String trackId,
        boolean applyToAll,
        int seekTicks,
        float playbackSpeed,
        long requestRevision,
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
        List<DjVisualEffectConfig> djVisualEffects
) implements CustomPacketPayload {
    public static final Type<ConfigureDjPanelPayload> TYPE = new Type<>(MusicBattleMod.id("configure_dj_panel"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ConfigureDjPanelPayload> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public ConfigureDjPanelPayload decode(RegistryFriendlyByteBuf buffer) {
            BlockPos panelPos = buffer.readBlockPos();
            Action action = Action.byOrdinal(buffer.readVarInt());
            BlockPos targetPos = buffer.readBlockPos();
            float radius = buffer.readFloat();
            float volume = buffer.readFloat();
            float lowGainDb = buffer.readFloat();
            float midGainDb = buffer.readFloat();
            float highGainDb = buffer.readFloat();
            String trackId = buffer.readUtf(255);
            boolean applyToAll = buffer.readBoolean();
            int seekTicks = buffer.readVarInt();
            float playbackSpeed = buffer.readFloat();
            long requestRevision = buffer.readVarLong();
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
            boolean trackVisualFxEnabled = true;
            boolean djVisualFxEnabled = false;
            List<DjVisualEffectConfig> djVisualEffects = List.of();
            if (action == Action.APPLY_VISUAL_FX) {
                trackVisualFxEnabled = buffer.readBoolean();
                djVisualFxEnabled = buffer.readBoolean();
                djVisualEffects = DjVisualEffectConfig.readList(buffer);
            }
            return new ConfigureDjPanelPayload(
                    panelPos, action, targetPos, radius, volume, lowGainDb, midGainDb, highGainDb,
                    trackId, applyToAll, seekTicks, playbackSpeed, requestRevision, dspEnabled,
                    reverbEnabled, reverbWet, reverbRoom, reverbDamping,
                    echoEnabled, echoWet, echoTimeMs, echoFeedback,
                    lowPassEnabled, lowPassCutoffHz,
                    trackVisualFxEnabled, djVisualFxEnabled, djVisualEffects
            );
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buffer, ConfigureDjPanelPayload payload) {
            buffer.writeBlockPos(payload.panelPos());
            buffer.writeVarInt(payload.action().ordinal());
            buffer.writeBlockPos(payload.targetPos() == null ? BlockPos.ZERO : payload.targetPos());
            buffer.writeFloat(payload.radius());
            buffer.writeFloat(payload.volume());
            buffer.writeFloat(payload.lowGainDb());
            buffer.writeFloat(payload.midGainDb());
            buffer.writeFloat(payload.highGainDb());
            buffer.writeUtf(payload.trackId() == null ? "" : payload.trackId(), 255);
            buffer.writeBoolean(payload.applyToAll());
            buffer.writeVarInt(Math.max(0, payload.seekTicks()));
            buffer.writeFloat(payload.playbackSpeed());
            buffer.writeVarLong(Math.max(0L, payload.requestRevision()));
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
            if (payload.action() == Action.APPLY_VISUAL_FX) {
                buffer.writeBoolean(payload.trackVisualFxEnabled());
                buffer.writeBoolean(payload.djVisualFxEnabled());
                DjVisualEffectConfig.writeList(buffer, payload.djVisualEffects());
            }
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public enum Action {
        LIVE_SPEAKER,
        UNLINK_SPEAKER,
        PLAY_RESUME,
        PAUSE,
        RESTART,
        SEEK,
        REFRESH,
        LIVE_PANEL_FX,
        SELECT_CAMERA,
        UNLINK_CAMERA,
        SELECT_SPEAKER,
        SET_EDIT_ALL,
        SELECT_TRACK,
        SET_SPEED,
        APPLY_VISUAL_FX,
        PRELOAD_AUDIENCE,
        APPLY_AUDIO_PRESET,
        RESET_SPEAKERS;

        private static Action byOrdinal(int value) {
            Action[] values = values();
            if (value < 0 || value >= values.length) {
                throw new IllegalArgumentException("Invalid DJ panel action: " + value);
            }
            return values[value];
        }
    }
}

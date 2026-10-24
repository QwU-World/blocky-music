package ru.gora.musicbattle.network;

import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import ru.gora.musicbattle.MusicBattleMod;

public record SpectatorMusicPayload(UUID battleId, String trackId, String audioFile, String audioSha256, float volume, float speedMultiplier,
                                    double x, double y, double z, float radius,
                                    float lowGainDb, float midGainDb, float highGainDb,
                                    boolean dspEnabled,
                                    boolean reverbEnabled, float reverbWet, float reverbRoom, float reverbDamping,
                                    boolean echoEnabled, float echoWet, float echoTimeMs, float echoFeedback,
                                    boolean lowPassEnabled, float lowPassCutoffHz,
                                    int delayTicks, int skipTicks) implements CustomPacketPayload {
    public static final Type<SpectatorMusicPayload> TYPE = new Type<>(MusicBattleMod.id("spectator_music"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SpectatorMusicPayload> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public SpectatorMusicPayload decode(RegistryFriendlyByteBuf buffer) {
            return new SpectatorMusicPayload(buffer.readUUID(), buffer.readUtf(255), buffer.readUtf(255), buffer.readUtf(64),
                    buffer.readFloat(), buffer.readFloat(), buffer.readDouble(), buffer.readDouble(), buffer.readDouble(), buffer.readFloat(),
                    buffer.readFloat(), buffer.readFloat(), buffer.readFloat(),
                    buffer.readBoolean(),
                    buffer.readBoolean(), buffer.readFloat(), buffer.readFloat(), buffer.readFloat(),
                    buffer.readBoolean(), buffer.readFloat(), buffer.readFloat(), buffer.readFloat(),
                    buffer.readBoolean(), buffer.readFloat(),
                    buffer.readVarInt(), buffer.readVarInt());
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buffer, SpectatorMusicPayload payload) {
            buffer.writeUUID(payload.battleId());
            buffer.writeUtf(payload.trackId(), 255);
            buffer.writeUtf(payload.audioFile(), 255);
            buffer.writeUtf(payload.audioSha256(), 64);
            buffer.writeFloat(payload.volume());
            buffer.writeFloat(payload.speedMultiplier());
            buffer.writeDouble(payload.x());
            buffer.writeDouble(payload.y());
            buffer.writeDouble(payload.z());
            buffer.writeFloat(payload.radius());
            buffer.writeFloat(payload.lowGainDb());
            buffer.writeFloat(payload.midGainDb());
            buffer.writeFloat(payload.highGainDb());
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
            buffer.writeVarInt(payload.delayTicks());
            buffer.writeVarInt(payload.skipTicks());
        }
    };

    /** Compatibility constructor for battle audio, which has no DJ panel effects. */
    public SpectatorMusicPayload(UUID battleId, String trackId, String audioFile, String audioSha256,
                                 float volume, float speedMultiplier,
                                 double x, double y, double z, float radius,
                                 float lowGainDb, float midGainDb, float highGainDb,
                                 int delayTicks, int skipTicks) {
        this(battleId, trackId, audioFile, audioSha256, volume, speedMultiplier,
                x, y, z, radius, lowGainDb, midGainDb, highGainDb,
                false,
                false, 0.30F, 0.65F, 0.45F,
                false, 0.25F, 280.0F, 0.35F,
                false, 12_000.0F,
                delayTicks, skipTicks);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

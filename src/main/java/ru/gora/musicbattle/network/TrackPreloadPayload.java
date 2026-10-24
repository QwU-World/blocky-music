package ru.gora.musicbattle.network;

import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import ru.gora.musicbattle.MusicBattleMod;

public record TrackPreloadPayload(
        UUID battleId,
        String trackId,
        String title,
        String chartFile,
        int chartBytes,
        String chartSha256,
        String audioFile,
        int audioBytes,
        String audioSha256
) implements CustomPacketPayload {
    public static final Type<TrackPreloadPayload> TYPE = new Type<>(MusicBattleMod.id("track_preload"));

    public static final StreamCodec<RegistryFriendlyByteBuf, TrackPreloadPayload> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public TrackPreloadPayload decode(RegistryFriendlyByteBuf buffer) {
            return new TrackPreloadPayload(
                    buffer.readUUID(),
                    buffer.readUtf(255),
                    buffer.readUtf(128),
                    buffer.readUtf(255),
                    buffer.readVarInt(),
                    buffer.readUtf(64),
                    buffer.readUtf(255),
                    buffer.readVarInt(),
                    buffer.readUtf(64)
            );
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buffer, TrackPreloadPayload payload) {
            buffer.writeUUID(payload.battleId());
            buffer.writeUtf(payload.trackId(), 255);
            buffer.writeUtf(payload.title(), 128);
            buffer.writeUtf(payload.chartFile(), 255);
            buffer.writeVarInt(Math.max(0, payload.chartBytes()));
            buffer.writeUtf(payload.chartSha256(), 64);
            buffer.writeUtf(payload.audioFile(), 255);
            buffer.writeVarInt(Math.max(0, payload.audioBytes()));
            buffer.writeUtf(payload.audioSha256(), 64);
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

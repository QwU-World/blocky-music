package ru.gora.musicbattle.network;

import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import ru.gora.musicbattle.MusicBattleMod;

public record TrackReadyPayload(UUID battleId, String trackId, String chartFile, String audioFile) implements CustomPacketPayload {
    public static final Type<TrackReadyPayload> TYPE = new Type<>(MusicBattleMod.id("track_ready"));

    public static final StreamCodec<RegistryFriendlyByteBuf, TrackReadyPayload> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public TrackReadyPayload decode(RegistryFriendlyByteBuf buffer) {
            return new TrackReadyPayload(buffer.readUUID(), buffer.readUtf(255), buffer.readUtf(255), buffer.readUtf(255));
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buffer, TrackReadyPayload payload) {
            buffer.writeUUID(payload.battleId());
            buffer.writeUtf(payload.trackId(), 255);
            buffer.writeUtf(payload.chartFile(), 255);
            buffer.writeUtf(payload.audioFile(), 255);
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

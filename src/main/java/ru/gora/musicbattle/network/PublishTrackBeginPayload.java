package ru.gora.musicbattle.network;

import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import ru.gora.musicbattle.MusicBattleMod;

public record PublishTrackBeginPayload(
        UUID uploadId,
        String trackId,
        int chartBytes,
        String chartSha256,
        int audioBytes,
        String audioSha256,
        int previewBytes,
        String previewSha256
) implements CustomPacketPayload {
    public static final Type<PublishTrackBeginPayload> TYPE = new Type<>(MusicBattleMod.id("publish_track_begin"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PublishTrackBeginPayload> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public PublishTrackBeginPayload decode(RegistryFriendlyByteBuf buffer) {
            return new PublishTrackBeginPayload(
                    buffer.readUUID(),
                    buffer.readUtf(96),
                    buffer.readVarInt(),
                    buffer.readUtf(64),
                    buffer.readVarInt(),
                    buffer.readUtf(64),
                    buffer.readVarInt(),
                    buffer.readUtf(64)
            );
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buffer, PublishTrackBeginPayload payload) {
            buffer.writeUUID(payload.uploadId());
            buffer.writeUtf(payload.trackId(), 96);
            buffer.writeVarInt(payload.chartBytes());
            buffer.writeUtf(payload.chartSha256(), 64);
            buffer.writeVarInt(payload.audioBytes());
            buffer.writeUtf(payload.audioSha256(), 64);
            buffer.writeVarInt(payload.previewBytes());
            buffer.writeUtf(payload.previewSha256(), 64);
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

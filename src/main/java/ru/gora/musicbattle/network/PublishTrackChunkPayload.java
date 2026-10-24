package ru.gora.musicbattle.network;

import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import ru.gora.musicbattle.MusicBattleMod;

public record PublishTrackChunkPayload(UUID uploadId, String assetKind, int chunkIndex, byte[] data) implements CustomPacketPayload {
    public static final int MAX_CHUNK_BYTES = 24 * 1024;
    public static final Type<PublishTrackChunkPayload> TYPE = new Type<>(MusicBattleMod.id("publish_track_chunk"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PublishTrackChunkPayload> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public PublishTrackChunkPayload decode(RegistryFriendlyByteBuf buffer) {
            return new PublishTrackChunkPayload(buffer.readUUID(), buffer.readUtf(8), buffer.readVarInt(), buffer.readByteArray(MAX_CHUNK_BYTES));
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buffer, PublishTrackChunkPayload payload) {
            buffer.writeUUID(payload.uploadId());
            buffer.writeUtf(payload.assetKind(), 8);
            buffer.writeVarInt(payload.chunkIndex());
            buffer.writeByteArray(payload.data());
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

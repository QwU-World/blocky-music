package ru.gora.musicbattle.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import ru.gora.musicbattle.MusicBattleMod;

public record TrackAssetChunkPayload(
        String trackId,
        String assetKind,
        String fileName,
        int chunkIndex,
        int totalChunks,
        int totalBytes,
        String sha256,
        byte[] data
) implements CustomPacketPayload {
    public static final Type<TrackAssetChunkPayload> TYPE = new Type<>(MusicBattleMod.id("track_asset_chunk"));
    private static final int MAX_CHUNK_BYTES = 64 * 1024;

    public static final StreamCodec<RegistryFriendlyByteBuf, TrackAssetChunkPayload> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public TrackAssetChunkPayload decode(RegistryFriendlyByteBuf buffer) {
            return new TrackAssetChunkPayload(
                    buffer.readUtf(255),
                    buffer.readUtf(16),
                    buffer.readUtf(255),
                    buffer.readVarInt(),
                    buffer.readVarInt(),
                    buffer.readVarInt(),
                    buffer.readUtf(64),
                    buffer.readByteArray(MAX_CHUNK_BYTES)
            );
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buffer, TrackAssetChunkPayload payload) {
            buffer.writeUtf(payload.trackId(), 255);
            buffer.writeUtf(payload.assetKind(), 16);
            buffer.writeUtf(payload.fileName(), 255);
            buffer.writeVarInt(payload.chunkIndex());
            buffer.writeVarInt(payload.totalChunks());
            buffer.writeVarInt(payload.totalBytes());
            buffer.writeUtf(payload.sha256(), 64);
            buffer.writeByteArray(payload.data());
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

package ru.gora.musicbattle.network;

import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import ru.gora.musicbattle.MusicBattleMod;

/**
 * One 24 KiB slice of a {@code .blockymap} travelling from a player to the server, the same
 * shape as the existing publish upload. Both ends read and write the file off the main thread.
 */
public record ServerBeatmapChunkPayload(UUID transferId, int chunkIndex, byte[] data) implements CustomPacketPayload {
    public static final Type<ServerBeatmapChunkPayload> TYPE = new Type<>(MusicBattleMod.id("server_beatmap_chunk"));
    /** 24 KiB, well inside the 32 KiB a custom payload may carry after compression. */
    public static final int MAX_CHUNK_BYTES = 24 * 1024;

    public static final StreamCodec<RegistryFriendlyByteBuf, ServerBeatmapChunkPayload> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public ServerBeatmapChunkPayload decode(RegistryFriendlyByteBuf buffer) {
            UUID transferId = buffer.readUUID();
            int chunkIndex = buffer.readVarInt();
            int length = buffer.readVarInt();
            if (length < 0 || length > MAX_CHUNK_BYTES) {
                throw new IllegalArgumentException("chunk out of range");
            }
            byte[] data = new byte[length];
            buffer.readBytes(data);
            return new ServerBeatmapChunkPayload(transferId, chunkIndex, data);
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buffer, ServerBeatmapChunkPayload payload) {
            buffer.writeUUID(payload.transferId());
            buffer.writeVarInt(Math.max(0, payload.chunkIndex()));
            buffer.writeVarInt(payload.data().length);
            buffer.writeBytes(payload.data());
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

package ru.gora.musicbattle.network;

import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import ru.gora.musicbattle.MusicBattleMod;

/** The player gave up on an upload (closed the screen, cancelled). The server drops the partial file. */
public record ServerBeatmapTransferCancelPayload(UUID transferId) implements CustomPacketPayload {
    public static final Type<ServerBeatmapTransferCancelPayload> TYPE =
            new Type<>(MusicBattleMod.id("server_beatmap_cancel"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ServerBeatmapTransferCancelPayload> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public ServerBeatmapTransferCancelPayload decode(RegistryFriendlyByteBuf buffer) {
            return new ServerBeatmapTransferCancelPayload(buffer.readUUID());
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buffer, ServerBeatmapTransferCancelPayload payload) {
            buffer.writeUUID(payload.transferId());
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

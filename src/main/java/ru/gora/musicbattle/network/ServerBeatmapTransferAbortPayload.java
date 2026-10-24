package ru.gora.musicbattle.network;

import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import ru.gora.musicbattle.MusicBattleMod;

/** The server dropped a running upload; {@code reason} is a {@code music_battle.workshop.error.*} code. */
public record ServerBeatmapTransferAbortPayload(UUID transferId, String reason) implements CustomPacketPayload {
    public static final Type<ServerBeatmapTransferAbortPayload> TYPE =
            new Type<>(MusicBattleMod.id("server_beatmap_abort"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ServerBeatmapTransferAbortPayload> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public ServerBeatmapTransferAbortPayload decode(RegistryFriendlyByteBuf buffer) {
            return new ServerBeatmapTransferAbortPayload(buffer.readUUID(), buffer.readUtf(64));
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buffer, ServerBeatmapTransferAbortPayload payload) {
            buffer.writeUUID(payload.transferId());
            buffer.writeUtf(payload.reason() == null ? "" : payload.reason(), 64);
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

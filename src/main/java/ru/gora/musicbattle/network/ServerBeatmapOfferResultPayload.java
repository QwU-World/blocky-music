package ru.gora.musicbattle.network;

import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import ru.gora.musicbattle.MusicBattleMod;

/**
 * The server's answer to an offer: {@link #HAVE} when it already holds that content,
 * {@link #NEED} to ask for the bytes, {@link #DENIED} with a reason the client renders as
 * {@code music_battle.workshop.error.<reason>}.
 */
public record ServerBeatmapOfferResultPayload(UUID transferId, byte verdict, String reason, int chunkBytes)
        implements CustomPacketPayload {
    public static final Type<ServerBeatmapOfferResultPayload> TYPE =
            new Type<>(MusicBattleMod.id("server_beatmap_offer_result"));
    public static final byte HAVE = 0;
    public static final byte NEED = 1;
    public static final byte DENIED = 2;

    public static final StreamCodec<RegistryFriendlyByteBuf, ServerBeatmapOfferResultPayload> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public ServerBeatmapOfferResultPayload decode(RegistryFriendlyByteBuf buffer) {
            return new ServerBeatmapOfferResultPayload(buffer.readUUID(), buffer.readByte(), buffer.readUtf(64),
                    buffer.readVarInt());
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buffer, ServerBeatmapOfferResultPayload payload) {
            buffer.writeUUID(payload.transferId());
            buffer.writeByte(payload.verdict());
            buffer.writeUtf(payload.reason() == null ? "" : payload.reason(), 64);
            buffer.writeVarInt(Math.max(0, payload.chunkBytes()));
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

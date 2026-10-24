package ru.gora.musicbattle.network;

import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import ru.gora.musicbattle.MusicBattleMod;

/**
 * "Add to server": the client offers a package it has installed (spec 06 section 9.1). Nothing is uploaded yet —
 * the server answers {@code have}, {@code need} or {@code denied} in {@link ServerBeatmapOfferResultPayload}.
 */
public record ServerBeatmapOfferPayload(UUID transferId, long setId, int revision, String contentSha256, long size)
        implements CustomPacketPayload {
    public static final Type<ServerBeatmapOfferPayload> TYPE = new Type<>(MusicBattleMod.id("server_beatmap_offer"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ServerBeatmapOfferPayload> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public ServerBeatmapOfferPayload decode(RegistryFriendlyByteBuf buffer) {
            return new ServerBeatmapOfferPayload(buffer.readUUID(), buffer.readVarLong(), buffer.readVarInt(),
                    buffer.readUtf(64), buffer.readVarLong());
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buffer, ServerBeatmapOfferPayload payload) {
            buffer.writeUUID(payload.transferId());
            buffer.writeVarLong(Math.max(0L, payload.setId()));
            buffer.writeVarInt(Math.max(0, payload.revision()));
            buffer.writeUtf(payload.contentSha256() == null ? "" : payload.contentSha256(), 64);
            buffer.writeVarLong(Math.max(0L, payload.size()));
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

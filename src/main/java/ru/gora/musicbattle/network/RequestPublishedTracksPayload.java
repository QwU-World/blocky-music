package ru.gora.musicbattle.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import ru.gora.musicbattle.MusicBattleMod;

public record RequestPublishedTracksPayload() implements CustomPacketPayload {
    public static final Type<RequestPublishedTracksPayload> TYPE = new Type<>(MusicBattleMod.id("request_published_tracks"));
    public static final StreamCodec<RegistryFriendlyByteBuf, RequestPublishedTracksPayload> STREAM_CODEC = StreamCodec.of(
            (buffer, payload) -> { },
            buffer -> new RequestPublishedTracksPayload()
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

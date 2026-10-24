package ru.gora.musicbattle.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import ru.gora.musicbattle.MusicBattleMod;

public record DeletePublishedTrackPayload(String trackId) implements CustomPacketPayload {
    public static final Type<DeletePublishedTrackPayload> TYPE = new Type<>(MusicBattleMod.id("delete_published_track"));
    public static final StreamCodec<RegistryFriendlyByteBuf, DeletePublishedTrackPayload> STREAM_CODEC = StreamCodec.of(
            (buffer, payload) -> buffer.writeUtf(payload.trackId(), 96),
            buffer -> new DeletePublishedTrackPayload(buffer.readUtf(96))
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

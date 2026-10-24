package ru.gora.musicbattle.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import ru.gora.musicbattle.MusicBattleMod;

public record DeletePublishedTrackResultPayload(boolean success, Component message, String trackId) implements CustomPacketPayload {
    public static final Type<DeletePublishedTrackResultPayload> TYPE = new Type<>(MusicBattleMod.id("delete_published_track_result"));
    public static final StreamCodec<RegistryFriendlyByteBuf, DeletePublishedTrackResultPayload> STREAM_CODEC = StreamCodec.of(
            (buffer, payload) -> {
                buffer.writeBoolean(payload.success());
                ComponentSerialization.TRUSTED_STREAM_CODEC.encode(buffer, payload.message());
                buffer.writeUtf(payload.trackId(), 96);
            },
            buffer -> new DeletePublishedTrackResultPayload(buffer.readBoolean(), ComponentSerialization.TRUSTED_STREAM_CODEC.decode(buffer), buffer.readUtf(96))
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

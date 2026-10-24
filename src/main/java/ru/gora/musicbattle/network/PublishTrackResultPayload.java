package ru.gora.musicbattle.network;

import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import ru.gora.musicbattle.MusicBattleMod;

public record PublishTrackResultPayload(UUID uploadId, boolean success, Component message, String publishedTrackId) implements CustomPacketPayload {
    public static final Type<PublishTrackResultPayload> TYPE = new Type<>(MusicBattleMod.id("publish_track_result"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PublishTrackResultPayload> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public PublishTrackResultPayload decode(RegistryFriendlyByteBuf buffer) {
            return new PublishTrackResultPayload(buffer.readUUID(), buffer.readBoolean(), ComponentSerialization.TRUSTED_STREAM_CODEC.decode(buffer), buffer.readUtf(128));
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buffer, PublishTrackResultPayload payload) {
            buffer.writeUUID(payload.uploadId());
            buffer.writeBoolean(payload.success());
            ComponentSerialization.TRUSTED_STREAM_CODEC.encode(buffer, payload.message());
            buffer.writeUtf(payload.publishedTrackId(), 128);
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

package ru.gora.musicbattle.network;

import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import ru.gora.musicbattle.MusicBattleMod;

public record PublishTrackFinishPayload(UUID uploadId) implements CustomPacketPayload {
    public static final Type<PublishTrackFinishPayload> TYPE = new Type<>(MusicBattleMod.id("publish_track_finish"));
    public static final StreamCodec<RegistryFriendlyByteBuf, PublishTrackFinishPayload> STREAM_CODEC = StreamCodec.ofMember(
            (payload, buffer) -> buffer.writeUUID(payload.uploadId()),
            buffer -> new PublishTrackFinishPayload(buffer.readUUID())
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

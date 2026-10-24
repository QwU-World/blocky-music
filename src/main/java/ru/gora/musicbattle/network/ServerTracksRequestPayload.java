package ru.gora.musicbattle.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import ru.gora.musicbattle.MusicBattleMod;

/**
 * The Server tab outside a battle asks for every track this server offers; the answer is {@link ServerTracksPayload}.
 */
public record ServerTracksRequestPayload() implements CustomPacketPayload {
    public static final Type<ServerTracksRequestPayload> TYPE = new Type<>(MusicBattleMod.id("server_tracks_request"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ServerTracksRequestPayload> STREAM_CODEC = StreamCodec.of(
            (buffer, payload) -> { },
            buffer -> new ServerTracksRequestPayload()
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

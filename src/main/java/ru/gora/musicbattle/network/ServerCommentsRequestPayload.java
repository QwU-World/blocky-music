package ru.gora.musicbattle.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import ru.gora.musicbattle.MusicBattleMod;

/**
 * One page of the server's own comments. {@code key} is {@code ws_<setId>} or
 * {@code track_<trackId>}; the server checks its grammar and existence before it ever becomes a path.
 */
public record ServerCommentsRequestPayload(String key, long beforeId, int limit) implements CustomPacketPayload {
    public static final Type<ServerCommentsRequestPayload> TYPE = new Type<>(MusicBattleMod.id("server_comments_request"));
    public static final int MAX_KEY = 140;

    public static final StreamCodec<RegistryFriendlyByteBuf, ServerCommentsRequestPayload> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public ServerCommentsRequestPayload decode(RegistryFriendlyByteBuf buffer) {
            return new ServerCommentsRequestPayload(buffer.readUtf(MAX_KEY), buffer.readVarLong(), buffer.readVarInt());
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buffer, ServerCommentsRequestPayload payload) {
            buffer.writeUtf(payload.key() == null ? "" : payload.key(), MAX_KEY);
            buffer.writeVarLong(Math.max(0L, payload.beforeId()));
            buffer.writeVarInt(Math.max(1, payload.limit()));
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

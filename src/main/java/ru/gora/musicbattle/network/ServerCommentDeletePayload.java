package ru.gora.musicbattle.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import ru.gora.musicbattle.MusicBattleMod;

/** Deletes one server comment: its author always, anyone holding the moderation right as well. */
public record ServerCommentDeletePayload(String key, long commentId) implements CustomPacketPayload {
    public static final Type<ServerCommentDeletePayload> TYPE = new Type<>(MusicBattleMod.id("server_comment_delete"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ServerCommentDeletePayload> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public ServerCommentDeletePayload decode(RegistryFriendlyByteBuf buffer) {
            return new ServerCommentDeletePayload(buffer.readUtf(ServerCommentsRequestPayload.MAX_KEY), buffer.readVarLong());
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buffer, ServerCommentDeletePayload payload) {
            buffer.writeUtf(payload.key() == null ? "" : payload.key(), ServerCommentsRequestPayload.MAX_KEY);
            buffer.writeVarLong(Math.max(0L, payload.commentId()));
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

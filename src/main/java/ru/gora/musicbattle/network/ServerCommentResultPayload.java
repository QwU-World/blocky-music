package ru.gora.musicbattle.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import ru.gora.musicbattle.MusicBattleMod;

/** The outcome of a post, delete or report, with the key so the open view knows whether to reload. */
public record ServerCommentResultPayload(boolean success, Component message, String key) implements CustomPacketPayload {
    public static final Type<ServerCommentResultPayload> TYPE = new Type<>(MusicBattleMod.id("server_comment_result"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ServerCommentResultPayload> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public ServerCommentResultPayload decode(RegistryFriendlyByteBuf buffer) {
            return new ServerCommentResultPayload(buffer.readBoolean(),
                    ComponentSerialization.STREAM_CODEC.decode(buffer),
                    buffer.readUtf(ServerCommentsRequestPayload.MAX_KEY));
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buffer, ServerCommentResultPayload payload) {
            buffer.writeBoolean(payload.success());
            ComponentSerialization.STREAM_CODEC.encode(buffer, payload.message());
            buffer.writeUtf(payload.key() == null ? "" : payload.key(), ServerCommentsRequestPayload.MAX_KEY);
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

package ru.gora.musicbattle.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import ru.gora.musicbattle.MusicBattleMod;

/** A new server comment. The server strips control characters and {@code §} and enforces 1 to 500 characters. */
public record ServerCommentPostPayload(String key, String text) implements CustomPacketPayload {
    public static final Type<ServerCommentPostPayload> TYPE = new Type<>(MusicBattleMod.id("server_comment_post"));
    /** The wire cap is generous; the server trims and then refuses anything over 500 characters. */
    public static final int MAX_TEXT = 2000;

    public static final StreamCodec<RegistryFriendlyByteBuf, ServerCommentPostPayload> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public ServerCommentPostPayload decode(RegistryFriendlyByteBuf buffer) {
            return new ServerCommentPostPayload(buffer.readUtf(ServerCommentsRequestPayload.MAX_KEY), buffer.readUtf(MAX_TEXT));
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buffer, ServerCommentPostPayload payload) {
            buffer.writeUtf(payload.key() == null ? "" : payload.key(), ServerCommentsRequestPayload.MAX_KEY);
            buffer.writeUtf(payload.text() == null ? "" : payload.text(), MAX_TEXT);
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

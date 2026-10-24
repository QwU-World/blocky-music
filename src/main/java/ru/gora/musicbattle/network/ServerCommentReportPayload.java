package ru.gora.musicbattle.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import ru.gora.musicbattle.MusicBattleMod;

/** Reports a server comment to the operators; stored in {@code comments_meta/reports.json} and announced to OPs. */
public record ServerCommentReportPayload(String key, long commentId, String reason) implements CustomPacketPayload {
    public static final Type<ServerCommentReportPayload> TYPE = new Type<>(MusicBattleMod.id("server_comment_report"));
    public static final int MAX_REASON = 200;

    public static final StreamCodec<RegistryFriendlyByteBuf, ServerCommentReportPayload> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public ServerCommentReportPayload decode(RegistryFriendlyByteBuf buffer) {
            return new ServerCommentReportPayload(buffer.readUtf(ServerCommentsRequestPayload.MAX_KEY),
                    buffer.readVarLong(), buffer.readUtf(MAX_REASON));
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buffer, ServerCommentReportPayload payload) {
            buffer.writeUtf(payload.key() == null ? "" : payload.key(), ServerCommentsRequestPayload.MAX_KEY);
            buffer.writeVarLong(Math.max(0L, payload.commentId()));
            buffer.writeUtf(payload.reason() == null ? "" : payload.reason(), MAX_REASON);
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

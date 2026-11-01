package ru.gora.musicbattle.network;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import ru.gora.musicbattle.MusicBattleMod;

/**
 * One page of server comments, newest first. Each row carries the author's signed skin
 * textures when the server had them, so {@code ui.SkinHead} can draw a secure head; on an offline-mode server
 * {@code onlineMode} is false and the screen says that names are not verified.
 */
public record ServerCommentsPagePayload(
        String key,
        int total,
        List<Row> comments,
        long nextBeforeId,
        boolean canPost,
        boolean canModerate,
        boolean onlineMode,
        String note
) implements CustomPacketPayload {
    public static final Type<ServerCommentsPagePayload> TYPE = new Type<>(MusicBattleMod.id("server_comments_page"));
    public static final int MAX_ROWS = 20;

    /** One comment as the screen draws it. A deleted one keeps its row with an empty body. */
    public record Row(long id, UUID authorUuid, String authorName, String textures, String signature,
                      long createdAt, boolean deleted, String text, boolean own) {
    }

    public static final StreamCodec<RegistryFriendlyByteBuf, ServerCommentsPagePayload> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public ServerCommentsPagePayload decode(RegistryFriendlyByteBuf buffer) {
            String key = buffer.readUtf(ServerCommentsRequestPayload.MAX_KEY);
            int total = buffer.readVarInt();
            int count = Math.min(buffer.readVarInt(), MAX_ROWS);
            List<Row> rows = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                rows.add(new Row(buffer.readVarLong(), buffer.readUUID(), buffer.readUtf(16), buffer.readUtf(4096),
                        buffer.readUtf(1024), buffer.readVarLong(), buffer.readBoolean(), buffer.readUtf(500),
                        buffer.readBoolean()));
            }
            return new ServerCommentsPagePayload(key, total, List.copyOf(rows), buffer.readVarLong(),
                    buffer.readBoolean(), buffer.readBoolean(), buffer.readBoolean(), buffer.readUtf(64));
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buffer, ServerCommentsPagePayload payload) {
            buffer.writeUtf(payload.key() == null ? "" : payload.key(), ServerCommentsRequestPayload.MAX_KEY);
            buffer.writeVarInt(Math.max(0, payload.total()));
            int count = Math.min(payload.comments().size(), MAX_ROWS);
            buffer.writeVarInt(count);
            for (int i = 0; i < count; i++) {
                Row row = payload.comments().get(i);
                buffer.writeVarLong(Math.max(0L, row.id()));
                buffer.writeUUID(row.authorUuid());
                buffer.writeUtf(row.authorName(), 16);
                buffer.writeUtf(row.textures(), 4096);
                buffer.writeUtf(row.signature(), 1024);
                buffer.writeVarLong(Math.max(0L, row.createdAt()));
                buffer.writeBoolean(row.deleted());
                buffer.writeUtf(row.text(), 500);
                buffer.writeBoolean(row.own());
            }
            buffer.writeVarLong(Math.max(0L, payload.nextBeforeId()));
            buffer.writeBoolean(payload.canPost());
            buffer.writeBoolean(payload.canModerate());
            buffer.writeBoolean(payload.onlineMode());
            buffer.writeUtf(payload.note() == null ? "" : payload.note(), 64);
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

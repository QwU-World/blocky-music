package ru.gora.musicbattle.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import ru.gora.musicbattle.MusicBattleMod;

/** One page of the Server beatmaps tab (spec 06 section 9.1). An empty cursor means "from the start". */
public record ServerBeatmapsRequestPayload(String cursor, String query) implements CustomPacketPayload {
    public static final Type<ServerBeatmapsRequestPayload> TYPE = new Type<>(MusicBattleMod.id("server_beatmaps_request"));
    public static final int MAX_CURSOR = 64;
    public static final int MAX_QUERY = 200;

    public static final StreamCodec<RegistryFriendlyByteBuf, ServerBeatmapsRequestPayload> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public ServerBeatmapsRequestPayload decode(RegistryFriendlyByteBuf buffer) {
            return new ServerBeatmapsRequestPayload(buffer.readUtf(MAX_CURSOR), buffer.readUtf(MAX_QUERY));
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buffer, ServerBeatmapsRequestPayload payload) {
            buffer.writeUtf(payload.cursor() == null ? "" : payload.cursor(), MAX_CURSOR);
            buffer.writeUtf(payload.query() == null ? "" : payload.query(), MAX_QUERY);
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

package ru.gora.musicbattle.network;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import ru.gora.musicbattle.MusicBattleMod;

/**
 * One page of server beatmaps (the cards carry their charts, the explicit flag
 * and whether a cover exists, so the tab draws the same card as Browse without asking the website).
 */
public record ServerBeatmapsPagePayload(List<Item> items, String nextCursor, boolean canManage)
        implements CustomPacketPayload {
    public static final Type<ServerBeatmapsPagePayload> TYPE = new Type<>(MusicBattleMod.id("server_beatmaps_page"));
    public static final int MAX_ITEMS = 50;
    private static final int MAX_CHARTS = 16;

    /** One difficulty of a server beatmap. */
    public record Chart(String key, String name, double stars) {
    }

    /** One server beatmap as the tab shows it. */
    public record Item(
            long setId,
            int revision,
            String contentSha256,
            String title,
            String artist,
            String mapper,
            List<Chart> charts,
            boolean explicit,
            boolean hasCover,
            String addedBy,
            long addedAt,
            boolean withdrawn,
            boolean unverified
    ) {
    }

    public static final StreamCodec<RegistryFriendlyByteBuf, ServerBeatmapsPagePayload> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public ServerBeatmapsPagePayload decode(RegistryFriendlyByteBuf buffer) {
            int count = Math.min(buffer.readVarInt(), MAX_ITEMS);
            List<Item> items = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                long setId = buffer.readVarLong();
                int revision = buffer.readVarInt();
                String contentSha256 = buffer.readUtf(64);
                String title = buffer.readUtf(128);
                String artist = buffer.readUtf(128);
                String mapper = buffer.readUtf(32);
                int chartCount = Math.min(buffer.readVarInt(), MAX_CHARTS);
                List<Chart> charts = new ArrayList<>(chartCount);
                for (int c = 0; c < chartCount; c++) {
                    charts.add(new Chart(buffer.readUtf(32), buffer.readUtf(64), buffer.readDouble()));
                }
                items.add(new Item(setId, revision, contentSha256, title, artist, mapper, List.copyOf(charts),
                        buffer.readBoolean(), buffer.readBoolean(), buffer.readUtf(32), buffer.readVarLong(),
                        buffer.readBoolean(), buffer.readBoolean()));
            }
            return new ServerBeatmapsPagePayload(List.copyOf(items), buffer.readUtf(64), buffer.readBoolean());
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buffer, ServerBeatmapsPagePayload payload) {
            int count = Math.min(payload.items().size(), MAX_ITEMS);
            buffer.writeVarInt(count);
            for (int i = 0; i < count; i++) {
                Item item = payload.items().get(i);
                buffer.writeVarLong(item.setId());
                buffer.writeVarInt(item.revision());
                buffer.writeUtf(item.contentSha256(), 64);
                buffer.writeUtf(item.title(), 128);
                buffer.writeUtf(item.artist(), 128);
                buffer.writeUtf(item.mapper(), 32);
                int chartCount = Math.min(item.charts().size(), MAX_CHARTS);
                buffer.writeVarInt(chartCount);
                for (int c = 0; c < chartCount; c++) {
                    Chart chart = item.charts().get(c);
                    buffer.writeUtf(chart.key(), 32);
                    buffer.writeUtf(chart.name(), 64);
                    buffer.writeDouble(chart.stars());
                }
                buffer.writeBoolean(item.explicit());
                buffer.writeBoolean(item.hasCover());
                buffer.writeUtf(item.addedBy(), 32);
                buffer.writeVarLong(Math.max(0L, item.addedAt()));
                buffer.writeBoolean(item.withdrawn());
                buffer.writeBoolean(item.unverified());
            }
            buffer.writeUtf(payload.nextCursor() == null ? "" : payload.nextCursor(), 64);
            buffer.writeBoolean(payload.canManage());
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

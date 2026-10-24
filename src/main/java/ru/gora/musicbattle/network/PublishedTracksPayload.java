package ru.gora.musicbattle.network;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import ru.gora.musicbattle.MusicBattleMod;

public record PublishedTracksPayload(int usedSlots, int maxSlots, boolean canPublish, List<Entry> tracks) implements CustomPacketPayload {
    public static final int MAX_TRACKS = 64;
    public static final Type<PublishedTracksPayload> TYPE = new Type<>(MusicBattleMod.id("published_tracks"));
    public static final StreamCodec<RegistryFriendlyByteBuf, PublishedTracksPayload> STREAM_CODEC = StreamCodec.of(
            (buffer, payload) -> {
                buffer.writeVarInt(payload.usedSlots());
                buffer.writeVarInt(payload.maxSlots());
                buffer.writeBoolean(payload.canPublish());
                int count = Math.min(MAX_TRACKS, payload.tracks().size());
                buffer.writeVarInt(count);
                for (int i = 0; i < count; i++) {
                    Entry entry = payload.tracks().get(i);
                    buffer.writeUtf(entry.publishedId(), 96);
                    buffer.writeUtf(entry.sourceTrackId(), 96);
                    buffer.writeUtf(entry.title(), 128);
                    buffer.writeUtf(entry.description(), 1024);
                    buffer.writeVarInt(entry.bpm());
                }
            },
            buffer -> {
                int used = buffer.readVarInt();
                int max = buffer.readVarInt();
                boolean canPublish = buffer.readBoolean();
                int count = Math.max(0, Math.min(MAX_TRACKS, buffer.readVarInt()));
                List<Entry> entries = new ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    entries.add(new Entry(buffer.readUtf(96), buffer.readUtf(96), buffer.readUtf(128), buffer.readUtf(1024), buffer.readVarInt()));
                }
                return new PublishedTracksPayload(used, max, canPublish, List.copyOf(entries));
            }
    );

    public PublishedTracksPayload {
        tracks = tracks == null ? List.of() : List.copyOf(tracks);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public record Entry(String publishedId, String sourceTrackId, String title, String description, int bpm) {
    }
}

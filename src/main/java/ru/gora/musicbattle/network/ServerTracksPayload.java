package ru.gora.musicbattle.network;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import ru.gora.musicbattle.MusicBattleMod;
import ru.gora.musicbattle.data.TrackSummary;

/**
 * Every track this server offers, in the rows a battle's vote carries: its Workshop sets' charts, the tracks its
 * operator put there and the tracks its players published with the editor. The Server tab used to learn the last two
 * kinds only from a battle's vote, so outside a battle a server full of editor-made maps showed an empty tab.
 */
public record ServerTracksPayload(List<TrackSummary> tracks, boolean truncated) implements CustomPacketPayload {
    public static final Type<ServerTracksPayload> TYPE = new Type<>(MusicBattleMod.id("server_tracks"));
    public static final int MAX_TRACKS = OpenVotePayload.MAX_TRACKS;

    public ServerTracksPayload {
        tracks = tracks == null ? List.of() : List.copyOf(tracks);
    }

    public static final StreamCodec<RegistryFriendlyByteBuf, ServerTracksPayload> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public ServerTracksPayload decode(RegistryFriendlyByteBuf buffer) {
            int count = Math.min(buffer.readVarInt(), MAX_TRACKS);
            List<TrackSummary> tracks = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                tracks.add(TrackSummary.read(buffer));
            }
            return new ServerTracksPayload(tracks, buffer.readBoolean());
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buffer, ServerTracksPayload payload) {
            int count = Math.min(payload.tracks().size(), MAX_TRACKS);
            buffer.writeVarInt(count);
            for (int i = 0; i < count; i++) {
                payload.tracks().get(i).write(buffer);
            }
            buffer.writeBoolean(payload.truncated() || count < payload.tracks().size());
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

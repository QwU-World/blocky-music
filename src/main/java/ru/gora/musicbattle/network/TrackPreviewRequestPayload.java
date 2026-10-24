package ru.gora.musicbattle.network;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import ru.gora.musicbattle.MusicBattleMod;

public record TrackPreviewRequestPayload(UUID battleId, List<String> trackIds) implements CustomPacketPayload {
    public static final Type<TrackPreviewRequestPayload> TYPE = new Type<>(MusicBattleMod.id("track_preview_request"));
    private static final int MAX_TRACKS = 8;

    public static final StreamCodec<RegistryFriendlyByteBuf, TrackPreviewRequestPayload> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public TrackPreviewRequestPayload decode(RegistryFriendlyByteBuf buffer) {
            UUID battleId = buffer.readUUID();
            int count = Math.min(buffer.readVarInt(), MAX_TRACKS);
            List<String> trackIds = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                trackIds.add(buffer.readUtf(255));
            }
            return new TrackPreviewRequestPayload(battleId, List.copyOf(trackIds));
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buffer, TrackPreviewRequestPayload payload) {
            buffer.writeUUID(payload.battleId());
            int count = Math.min(payload.trackIds().size(), MAX_TRACKS);
            buffer.writeVarInt(count);
            for (int i = 0; i < count; i++) {
                buffer.writeUtf(payload.trackIds().get(i), 255);
            }
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

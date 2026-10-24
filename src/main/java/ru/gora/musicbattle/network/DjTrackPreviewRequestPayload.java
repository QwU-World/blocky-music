package ru.gora.musicbattle.network;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import ru.gora.musicbattle.MusicBattleMod;

/** Requests thumbnails for the currently visible DJ track cards. */
public record DjTrackPreviewRequestPayload(BlockPos panelPos, List<String> trackIds)
        implements CustomPacketPayload {
    private static final int MAX_TRACKS = 8;
    public static final Type<DjTrackPreviewRequestPayload> TYPE =
            new Type<>(MusicBattleMod.id("dj_track_preview_request"));

    public static final StreamCodec<RegistryFriendlyByteBuf, DjTrackPreviewRequestPayload> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public DjTrackPreviewRequestPayload decode(RegistryFriendlyByteBuf buffer) {
                    BlockPos panelPos = buffer.readBlockPos();
                    int count = buffer.readVarInt();
                    if (count < 0 || count > MAX_TRACKS) {
                        throw new IllegalArgumentException("Invalid DJ preview request count: " + count);
                    }
                    List<String> ids = new ArrayList<>(count);
                    for (int index = 0; index < count; index++) {
                        ids.add(buffer.readUtf(255));
                    }
                    return new DjTrackPreviewRequestPayload(panelPos, List.copyOf(ids));
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buffer, DjTrackPreviewRequestPayload payload) {
                    buffer.writeBlockPos(payload.panelPos());
                    int count = Math.min(MAX_TRACKS, payload.trackIds().size());
                    buffer.writeVarInt(count);
                    for (int index = 0; index < count; index++) {
                        buffer.writeUtf(payload.trackIds().get(index), 255);
                    }
                }
            };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

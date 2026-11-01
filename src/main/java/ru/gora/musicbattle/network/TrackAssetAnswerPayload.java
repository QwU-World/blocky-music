package ru.gora.musicbattle.network;

import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import ru.gora.musicbattle.MusicBattleMod;

/**
 * The answer to {@link TrackAssetOfferPayload}. It is computed from an in-memory hash index only: a payload
 * handler never hashes a file, so a battle start can never stall the render thread.
 */
public record TrackAssetAnswerPayload(UUID battleId, String trackId, String assetKind, String fileName, String sha256,
                                      boolean have) implements CustomPacketPayload {
    public static final Type<TrackAssetAnswerPayload> TYPE = new Type<>(MusicBattleMod.id("track_asset_answer"));

    public static final StreamCodec<RegistryFriendlyByteBuf, TrackAssetAnswerPayload> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public TrackAssetAnswerPayload decode(RegistryFriendlyByteBuf buffer) {
            return new TrackAssetAnswerPayload(buffer.readUUID(), buffer.readUtf(128), buffer.readUtf(16),
                    buffer.readUtf(255), buffer.readUtf(64), buffer.readBoolean());
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buffer, TrackAssetAnswerPayload payload) {
            buffer.writeUUID(payload.battleId());
            buffer.writeUtf(payload.trackId(), 128);
            buffer.writeUtf(payload.assetKind(), 16);
            buffer.writeUtf(payload.fileName(), 255);
            buffer.writeUtf(payload.sha256(), 64);
            buffer.writeBoolean(payload.have());
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

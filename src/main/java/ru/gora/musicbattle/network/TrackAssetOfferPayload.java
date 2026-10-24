package ru.gora.musicbattle.network;

import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import ru.gora.musicbattle.MusicBattleMod;

/**
 * Before a battle pushes a file, it asks whether the player already has exactly these bytes (spec 06 section 9.4).
 * A player who owns the set from the Workshop answers {@code have} and the audio is never sent again.
 */
public record TrackAssetOfferPayload(UUID battleId, String trackId, String assetKind, String fileName, String sha256)
        implements CustomPacketPayload {
    public static final Type<TrackAssetOfferPayload> TYPE = new Type<>(MusicBattleMod.id("track_asset_offer"));

    public static final StreamCodec<RegistryFriendlyByteBuf, TrackAssetOfferPayload> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public TrackAssetOfferPayload decode(RegistryFriendlyByteBuf buffer) {
            return new TrackAssetOfferPayload(buffer.readUUID(), buffer.readUtf(128), buffer.readUtf(16),
                    buffer.readUtf(255), buffer.readUtf(64));
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buffer, TrackAssetOfferPayload payload) {
            buffer.writeUUID(payload.battleId());
            buffer.writeUtf(payload.trackId(), 128);
            buffer.writeUtf(payload.assetKind(), 16);
            buffer.writeUtf(payload.fileName(), 255);
            buffer.writeUtf(payload.sha256(), 64);
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

package ru.gora.musicbattle.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import ru.gora.musicbattle.MusicBattleMod;

/** Asks one DJ listener whether the next queued track is already in its local cache. */
public record DjPreloadOfferPayload(
        BlockPos panelPos,
        String trackId,
        String fileName,
        String sha256
) implements CustomPacketPayload {
    public static final Type<DjPreloadOfferPayload> TYPE = new Type<>(MusicBattleMod.id("dj_preload_offer"));

    public static final StreamCodec<RegistryFriendlyByteBuf, DjPreloadOfferPayload> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public DjPreloadOfferPayload decode(RegistryFriendlyByteBuf buffer) {
            return new DjPreloadOfferPayload(
                    buffer.readBlockPos(),
                    buffer.readUtf(128),
                    buffer.readUtf(128),
                    buffer.readUtf(64)
            );
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buffer, DjPreloadOfferPayload payload) {
            buffer.writeBlockPos(payload.panelPos());
            buffer.writeUtf(payload.trackId(), 128);
            buffer.writeUtf(payload.fileName(), 128);
            buffer.writeUtf(payload.sha256(), 64);
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

package ru.gora.musicbattle.network;

import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import ru.gora.musicbattle.MusicBattleMod;

/** Confirms that a standalone DJ audio asset was verified and stored by the client. */
public record DjAssetReadyPayload(
        UUID playbackId,
        String trackId,
        String fileName,
        String sha256
) implements CustomPacketPayload {
    public static final Type<DjAssetReadyPayload> TYPE = new Type<>(MusicBattleMod.id("dj_asset_ready"));

    public static final StreamCodec<RegistryFriendlyByteBuf, DjAssetReadyPayload> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public DjAssetReadyPayload decode(RegistryFriendlyByteBuf buffer) {
            return new DjAssetReadyPayload(
                    buffer.readUUID(),
                    buffer.readUtf(128),
                    buffer.readUtf(128),
                    buffer.readUtf(64)
            );
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buffer, DjAssetReadyPayload payload) {
            buffer.writeUUID(payload.playbackId());
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

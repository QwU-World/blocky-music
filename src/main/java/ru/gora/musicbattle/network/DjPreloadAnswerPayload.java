package ru.gora.musicbattle.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import ru.gora.musicbattle.MusicBattleMod;

/**
 * Answers a {@link DjPreloadOfferPayload}. The server trusts this only for the listener that
 * sent it, and only when it repeats the exact offer that was sent to that listener.
 */
public record DjPreloadAnswerPayload(
        BlockPos panelPos,
        String trackId,
        String fileName,
        String sha256,
        boolean have
) implements CustomPacketPayload {
    public static final Type<DjPreloadAnswerPayload> TYPE = new Type<>(MusicBattleMod.id("dj_preload_answer"));

    public static final StreamCodec<RegistryFriendlyByteBuf, DjPreloadAnswerPayload> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public DjPreloadAnswerPayload decode(RegistryFriendlyByteBuf buffer) {
            return new DjPreloadAnswerPayload(
                    buffer.readBlockPos(),
                    buffer.readUtf(128),
                    buffer.readUtf(128),
                    buffer.readUtf(64),
                    buffer.readBoolean()
            );
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buffer, DjPreloadAnswerPayload payload) {
            buffer.writeBlockPos(payload.panelPos());
            buffer.writeUtf(payload.trackId(), 128);
            buffer.writeUtf(payload.fileName(), 128);
            buffer.writeUtf(payload.sha256(), 64);
            buffer.writeBoolean(payload.have());
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

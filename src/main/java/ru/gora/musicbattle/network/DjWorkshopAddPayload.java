package ru.gora.musicbattle.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import ru.gora.musicbattle.MusicBattleMod;

/**
 * A DJ chose one chart of a Workshop set in the Workshop picker. When the server already holds that set the chart
 * joins the panel's playlist at once; otherwise the server answers in {@link DjWorkshopAddResultPayload} whether the
 * DJ may bring it through "Add to server", and adds the chart once that transfer has installed it.
 */
public record DjWorkshopAddPayload(BlockPos panelPos, long setId, int revision, String contentSha256, String chartKey)
        implements CustomPacketPayload {
    public static final Type<DjWorkshopAddPayload> TYPE = new Type<>(MusicBattleMod.id("dj_workshop_add"));

    public static final StreamCodec<RegistryFriendlyByteBuf, DjWorkshopAddPayload> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public DjWorkshopAddPayload decode(RegistryFriendlyByteBuf buffer) {
            return new DjWorkshopAddPayload(buffer.readBlockPos(), buffer.readVarLong(), buffer.readVarInt(),
                    buffer.readUtf(64), buffer.readUtf(32));
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buffer, DjWorkshopAddPayload payload) {
            buffer.writeBlockPos(payload.panelPos());
            buffer.writeVarLong(Math.max(0L, payload.setId()));
            buffer.writeVarInt(Math.max(0, payload.revision()));
            buffer.writeUtf(payload.contentSha256() == null ? "" : payload.contentSha256(), 64);
            buffer.writeUtf(payload.chartKey() == null ? "" : payload.chartKey(), 32);
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

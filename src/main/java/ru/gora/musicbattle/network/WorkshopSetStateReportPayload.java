package ru.gora.musicbattle.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import ru.gora.musicbattle.MusicBattleMod;

/**
 * A client tells the server what the Workshop says about a set it holds: {@code current},
 * {@code update_available}, {@code withdrawn} or {@code audio_removed}. Advisory and rate-limited: a server
 * without egress learns about a takedown this way and hides the set from votes until an operator acts.
 */
public record WorkshopSetStateReportPayload(long setId, String contentSha256, String state) implements CustomPacketPayload {
    public static final Type<WorkshopSetStateReportPayload> TYPE = new Type<>(MusicBattleMod.id("workshop_set_state"));

    public static final StreamCodec<RegistryFriendlyByteBuf, WorkshopSetStateReportPayload> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public WorkshopSetStateReportPayload decode(RegistryFriendlyByteBuf buffer) {
            return new WorkshopSetStateReportPayload(buffer.readVarLong(), buffer.readUtf(64), buffer.readUtf(32));
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buffer, WorkshopSetStateReportPayload payload) {
            buffer.writeVarLong(Math.max(0L, payload.setId()));
            buffer.writeUtf(payload.contentSha256() == null ? "" : payload.contentSha256(), 64);
            buffer.writeUtf(payload.state() == null ? "" : payload.state(), 32);
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

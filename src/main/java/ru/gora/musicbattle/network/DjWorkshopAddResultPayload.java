package ru.gora.musicbattle.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import ru.gora.musicbattle.MusicBattleMod;

/**
 * The server's answer to {@link DjWorkshopAddPayload}: {@link #ADDED} when the chart is in the playlist now,
 * {@link #UPLOAD} when the DJ may bring the set and the client should start "Add to server", {@link #SEND_INSTALLED}
 * when the server will ask for the DJ's installed copy to cache it, as for a battle vote, {@link #REFUSED} with the
 * reason. The message is a component so each client reads it in its own language.
 */
public record DjWorkshopAddResultPayload(BlockPos panelPos, long setId, byte verdict, Component message)
        implements CustomPacketPayload {
    public static final Type<DjWorkshopAddResultPayload> TYPE = new Type<>(MusicBattleMod.id("dj_workshop_add_result"));
    public static final byte ADDED = 0;
    public static final byte UPLOAD = 1;
    public static final byte REFUSED = 2;
    /** Sent just before the transfer's {@code need}, so the client knows which installed set to repack. */
    public static final byte SEND_INSTALLED = 3;

    public static final StreamCodec<RegistryFriendlyByteBuf, DjWorkshopAddResultPayload> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public DjWorkshopAddResultPayload decode(RegistryFriendlyByteBuf buffer) {
            return new DjWorkshopAddResultPayload(buffer.readBlockPos(), buffer.readVarLong(), buffer.readByte(),
                    ComponentSerialization.STREAM_CODEC.decode(buffer));
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buffer, DjWorkshopAddResultPayload payload) {
            buffer.writeBlockPos(payload.panelPos());
            buffer.writeVarLong(Math.max(0L, payload.setId()));
            buffer.writeByte(payload.verdict());
            ComponentSerialization.STREAM_CODEC.encode(buffer, payload.message());
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

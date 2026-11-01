package ru.gora.musicbattle.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import ru.gora.musicbattle.MusicBattleMod;

/**
 * What this server offers the Workshop screens: sent on login and again
 * after a config change or a grant, so the hidden "Server beatmaps" tab, the "Server comments" button and the
 * INSTALLED vote category appear exactly when the server really has them.
 */
public record WorkshopServerInfoPayload(
        boolean serverBeatmaps,
        boolean serverComments,
        boolean allowPlayerMaps,
        boolean onlineMode,
        boolean canManage,
        boolean canModerate
) implements CustomPacketPayload {
    public static final Type<WorkshopServerInfoPayload> TYPE = new Type<>(MusicBattleMod.id("workshop_server_info"));

    public static final StreamCodec<RegistryFriendlyByteBuf, WorkshopServerInfoPayload> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public WorkshopServerInfoPayload decode(RegistryFriendlyByteBuf buffer) {
            return new WorkshopServerInfoPayload(buffer.readBoolean(), buffer.readBoolean(), buffer.readBoolean(),
                    buffer.readBoolean(), buffer.readBoolean(), buffer.readBoolean());
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buffer, WorkshopServerInfoPayload payload) {
            buffer.writeBoolean(payload.serverBeatmaps());
            buffer.writeBoolean(payload.serverComments());
            buffer.writeBoolean(payload.allowPlayerMaps());
            buffer.writeBoolean(payload.onlineMode());
            buffer.writeBoolean(payload.canManage());
            buffer.writeBoolean(payload.canModerate());
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

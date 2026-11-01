package ru.gora.musicbattle.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import ru.gora.musicbattle.MusicBattleMod;

/**
 * Asks the server to switch a camera block's follow mode while its editor is open. The server checks it against the
 * same editor session as every other camera edit and answers with {@link CameraFollowStatePayload}.
 */
public record CameraFollowModePayload(BlockPos blockPos, boolean followPlayers) implements CustomPacketPayload {
    public static final Type<CameraFollowModePayload> TYPE = new Type<>(MusicBattleMod.id("camera_follow_mode"));

    public static final StreamCodec<RegistryFriendlyByteBuf, CameraFollowModePayload> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public CameraFollowModePayload decode(RegistryFriendlyByteBuf buffer) {
            return new CameraFollowModePayload(buffer.readBlockPos(), buffer.readBoolean());
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buffer, CameraFollowModePayload payload) {
            buffer.writeBlockPos(payload.blockPos());
            buffer.writeBoolean(payload.followPlayers());
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

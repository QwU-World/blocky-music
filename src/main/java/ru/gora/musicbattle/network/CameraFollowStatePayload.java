package ru.gora.musicbattle.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import ru.gora.musicbattle.MusicBattleMod;

/**
 * The server's word on a camera block's follow mode, for the editor that is setting the camera up: sent as the
 * editor opens and after every switch, so the hint panel shows what the block really holds.
 */
public record CameraFollowStatePayload(BlockPos blockPos, boolean followPlayers) implements CustomPacketPayload {
    public static final Type<CameraFollowStatePayload> TYPE = new Type<>(MusicBattleMod.id("camera_follow_state"));

    public static final StreamCodec<RegistryFriendlyByteBuf, CameraFollowStatePayload> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public CameraFollowStatePayload decode(RegistryFriendlyByteBuf buffer) {
            return new CameraFollowStatePayload(buffer.readBlockPos(), buffer.readBoolean());
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buffer, CameraFollowStatePayload payload) {
            buffer.writeBlockPos(payload.blockPos());
            buffer.writeBoolean(payload.followPlayers());
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

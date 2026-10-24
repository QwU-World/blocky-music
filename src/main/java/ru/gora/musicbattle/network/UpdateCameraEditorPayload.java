package ru.gora.musicbattle.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import ru.gora.musicbattle.MusicBattleMod;

public record UpdateCameraEditorPayload(
        BlockPos blockPos,
        double x,
        double y,
        double z,
        float yaw,
        float pitch,
        float fov,
        boolean resetToPlacement
) implements CustomPacketPayload {
    public static final Type<UpdateCameraEditorPayload> TYPE = new Type<>(MusicBattleMod.id("update_camera_editor"));

    public static final StreamCodec<RegistryFriendlyByteBuf, UpdateCameraEditorPayload> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public UpdateCameraEditorPayload decode(RegistryFriendlyByteBuf buffer) {
            return new UpdateCameraEditorPayload(
                    buffer.readBlockPos(),
                    buffer.readDouble(),
                    buffer.readDouble(),
                    buffer.readDouble(),
                    buffer.readFloat(),
                    buffer.readFloat(),
                    buffer.readFloat(),
                    buffer.readBoolean()
            );
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buffer, UpdateCameraEditorPayload payload) {
            buffer.writeBlockPos(payload.blockPos());
            buffer.writeDouble(payload.x());
            buffer.writeDouble(payload.y());
            buffer.writeDouble(payload.z());
            buffer.writeFloat(payload.yaw());
            buffer.writeFloat(payload.pitch());
            buffer.writeFloat(payload.fov());
            buffer.writeBoolean(payload.resetToPlacement());
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

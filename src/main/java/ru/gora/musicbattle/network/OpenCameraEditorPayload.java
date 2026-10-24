package ru.gora.musicbattle.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import ru.gora.musicbattle.MusicBattleMod;

public record OpenCameraEditorPayload(
        BlockPos blockPos,
        int cameraEntityId,
        double x,
        double y,
        double z,
        float yaw,
        float pitch,
        float fov,
        double playerX,
        double playerY,
        double playerZ,
        float playerYaw,
        float playerPitch,
        boolean resetResponse
) implements CustomPacketPayload {
    public static final Type<OpenCameraEditorPayload> TYPE = new Type<>(MusicBattleMod.id("open_camera_editor"));

    public static final StreamCodec<RegistryFriendlyByteBuf, OpenCameraEditorPayload> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public OpenCameraEditorPayload decode(RegistryFriendlyByteBuf buffer) {
            return new OpenCameraEditorPayload(
                    buffer.readBlockPos(),
                    buffer.readVarInt(),
                    buffer.readDouble(),
                    buffer.readDouble(),
                    buffer.readDouble(),
                    buffer.readFloat(),
                    buffer.readFloat(),
                    buffer.readFloat(),
                    buffer.readDouble(),
                    buffer.readDouble(),
                    buffer.readDouble(),
                    buffer.readFloat(),
                    buffer.readFloat(),
                    buffer.readBoolean()
            );
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buffer, OpenCameraEditorPayload payload) {
            buffer.writeBlockPos(payload.blockPos());
            buffer.writeVarInt(payload.cameraEntityId());
            buffer.writeDouble(payload.x());
            buffer.writeDouble(payload.y());
            buffer.writeDouble(payload.z());
            buffer.writeFloat(payload.yaw());
            buffer.writeFloat(payload.pitch());
            buffer.writeFloat(payload.fov());
            buffer.writeDouble(payload.playerX());
            buffer.writeDouble(payload.playerY());
            buffer.writeDouble(payload.playerZ());
            buffer.writeFloat(payload.playerYaw());
            buffer.writeFloat(payload.playerPitch());
            buffer.writeBoolean(payload.resetResponse());
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

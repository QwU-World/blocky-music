package ru.gora.musicbattle.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import ru.gora.musicbattle.MusicBattleMod;

/** Server-authoritative transform used by the small live monitor in the DJ panel. */
public record DjCameraPreviewPayload(
        BlockPos panelPos,
        BlockPos cameraPos,
        boolean available,
        long serverGameTime,
        double x,
        double y,
        double z,
        float yaw,
        float pitch,
        float fov
) implements CustomPacketPayload {
    public static final Type<DjCameraPreviewPayload> TYPE =
            new Type<>(MusicBattleMod.id("dj_camera_preview"));

    public static final StreamCodec<RegistryFriendlyByteBuf, DjCameraPreviewPayload> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public DjCameraPreviewPayload decode(RegistryFriendlyByteBuf buffer) {
                    return new DjCameraPreviewPayload(
                            buffer.readBlockPos(), buffer.readBlockPos(), buffer.readBoolean(), buffer.readVarLong(),
                            buffer.readDouble(), buffer.readDouble(), buffer.readDouble(),
                            buffer.readFloat(), buffer.readFloat(), buffer.readFloat());
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buffer, DjCameraPreviewPayload payload) {
                    buffer.writeBlockPos(payload.panelPos());
                    buffer.writeBlockPos(payload.cameraPos());
                    buffer.writeBoolean(payload.available());
                    buffer.writeVarLong(Math.max(0L, payload.serverGameTime()));
                    buffer.writeDouble(payload.x());
                    buffer.writeDouble(payload.y());
                    buffer.writeDouble(payload.z());
                    buffer.writeFloat(payload.yaw());
                    buffer.writeFloat(payload.pitch());
                    buffer.writeFloat(payload.fov());
                }
            };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

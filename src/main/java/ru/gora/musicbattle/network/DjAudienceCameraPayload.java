package ru.gora.musicbattle.network;

import java.util.Objects;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.util.Mth;
import ru.gora.musicbattle.MusicBattleMod;

/**
 * Server-authoritative DJ camera selected for listeners currently inside the
 * installation's audible coverage.
 */
public record DjAudienceCameraPayload(
        UUID playbackId,
        boolean available,
        BlockPos cameraPos,
        double x,
        double y,
        double z,
        float yaw,
        float pitch,
        float fov,
        long revision
) implements CustomPacketPayload {
    private static final double WORLD_COORDINATE_LIMIT = 30_000_000.0D;

    public static final Type<DjAudienceCameraPayload> TYPE =
            new Type<>(MusicBattleMod.id("dj_audience_camera"));

    public static final StreamCodec<RegistryFriendlyByteBuf, DjAudienceCameraPayload> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public DjAudienceCameraPayload decode(RegistryFriendlyByteBuf buffer) {
                    return new DjAudienceCameraPayload(
                            buffer.readUUID(),
                            buffer.readBoolean(),
                            buffer.readBlockPos(),
                            buffer.readDouble(),
                            buffer.readDouble(),
                            buffer.readDouble(),
                            buffer.readFloat(),
                            buffer.readFloat(),
                            buffer.readFloat(),
                            buffer.readVarLong());
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buffer, DjAudienceCameraPayload payload) {
                    buffer.writeUUID(payload.playbackId());
                    buffer.writeBoolean(payload.available());
                    buffer.writeBlockPos(payload.cameraPos());
                    buffer.writeDouble(payload.x());
                    buffer.writeDouble(payload.y());
                    buffer.writeDouble(payload.z());
                    buffer.writeFloat(payload.yaw());
                    buffer.writeFloat(payload.pitch());
                    buffer.writeFloat(payload.fov());
                    buffer.writeVarLong(payload.revision());
                }
            };

    public DjAudienceCameraPayload {
        playbackId = Objects.requireNonNull(playbackId, "playbackId");
        cameraPos = cameraPos == null ? BlockPos.ZERO : cameraPos.immutable();
        x = finiteCoordinate(x);
        y = finiteCoordinate(y);
        z = finiteCoordinate(z);
        yaw = Float.isFinite(yaw) ? Mth.wrapDegrees(yaw) : 0.0F;
        pitch = Float.isFinite(pitch) ? Mth.clamp(pitch, -89.0F, 89.0F) : 0.0F;
        fov = Float.isFinite(fov) ? Mth.clamp(fov, 30.0F, 110.0F) : 70.0F;
        revision = Math.max(0L, revision);
    }

    public static DjAudienceCameraPayload unavailable(UUID playbackId, BlockPos selectedCameraPos,
                                                       long revision) {
        return new DjAudienceCameraPayload(playbackId, false,
                selectedCameraPos == null ? BlockPos.ZERO : selectedCameraPos,
                0.0D, 0.0D, 0.0D, 0.0F, 0.0F, 70.0F, revision);
    }

    private static double finiteCoordinate(double value) {
        return Double.isFinite(value)
                ? Mth.clamp(value, -WORLD_COORDINATE_LIMIT, WORLD_COORDINATE_LIMIT)
                : 0.0D;
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

package ru.gora.musicbattle.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import ru.gora.musicbattle.MusicBattleMod;

/** Stateless request for the currently selected DJ monitor camera transform. */
public record DjCameraPreviewRequestPayload(BlockPos panelPos, BlockPos cameraPos)
        implements CustomPacketPayload {
    public static final Type<DjCameraPreviewRequestPayload> TYPE =
            new Type<>(MusicBattleMod.id("dj_camera_preview_request"));

    public static final StreamCodec<RegistryFriendlyByteBuf, DjCameraPreviewRequestPayload> STREAM_CODEC =
            StreamCodec.of(
                    (buffer, payload) -> {
                        buffer.writeBlockPos(payload.panelPos());
                        buffer.writeBlockPos(payload.cameraPos());
                    },
                    buffer -> new DjCameraPreviewRequestPayload(buffer.readBlockPos(), buffer.readBlockPos())
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

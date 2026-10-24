package ru.gora.musicbattle.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import ru.gora.musicbattle.MusicBattleMod;

/** Opens the existing camera editor through a nearby linked DJ panel. */
public record EditDjCameraRequestPayload(BlockPos panelPos, BlockPos cameraPos)
        implements CustomPacketPayload {
    public static final Type<EditDjCameraRequestPayload> TYPE =
            new Type<>(MusicBattleMod.id("edit_dj_camera_request"));

    public static final StreamCodec<RegistryFriendlyByteBuf, EditDjCameraRequestPayload> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, EditDjCameraRequestPayload::panelPos,
                    BlockPos.STREAM_CODEC, EditDjCameraRequestPayload::cameraPos,
                    EditDjCameraRequestPayload::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

package ru.gora.musicbattle.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import ru.gora.musicbattle.MusicBattleMod;

public record CloseCameraEditorPayload(BlockPos blockPos) implements CustomPacketPayload {
    public static final Type<CloseCameraEditorPayload> TYPE = new Type<>(MusicBattleMod.id("close_camera_editor"));

    public static final StreamCodec<RegistryFriendlyByteBuf, CloseCameraEditorPayload> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public CloseCameraEditorPayload decode(RegistryFriendlyByteBuf buffer) {
            return new CloseCameraEditorPayload(buffer.readBlockPos());
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buffer, CloseCameraEditorPayload payload) {
            buffer.writeBlockPos(payload.blockPos());
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

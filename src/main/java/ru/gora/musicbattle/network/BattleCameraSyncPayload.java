package ru.gora.musicbattle.network;

import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import ru.gora.musicbattle.MusicBattleMod;

/** Authoritative live state of the block camera currently used by a battle. */
public record BattleCameraSyncPayload(
        UUID battleId,
        int cameraEntityId,
        double x,
        double y,
        double z,
        float yaw,
        float pitch,
        float fov,
        long revision
) implements CustomPacketPayload {
    public static final Type<BattleCameraSyncPayload> TYPE =
            new Type<>(MusicBattleMod.id("battle_camera_sync"));

    public static final StreamCodec<RegistryFriendlyByteBuf, BattleCameraSyncPayload> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public BattleCameraSyncPayload decode(RegistryFriendlyByteBuf buffer) {
            return new BattleCameraSyncPayload(
                    buffer.readUUID(),
                    buffer.readVarInt(),
                    buffer.readDouble(),
                    buffer.readDouble(),
                    buffer.readDouble(),
                    buffer.readFloat(),
                    buffer.readFloat(),
                    buffer.readFloat(),
                    buffer.readVarLong()
            );
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buffer, BattleCameraSyncPayload payload) {
            buffer.writeUUID(payload.battleId());
            buffer.writeVarInt(payload.cameraEntityId());
            buffer.writeDouble(payload.x());
            buffer.writeDouble(payload.y());
            buffer.writeDouble(payload.z());
            buffer.writeFloat(payload.yaw());
            buffer.writeFloat(payload.pitch());
            buffer.writeFloat(payload.fov());
            buffer.writeVarLong(payload.revision());
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

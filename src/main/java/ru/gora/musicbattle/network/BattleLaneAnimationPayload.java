package ru.gora.musicbattle.network;

import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import ru.gora.musicbattle.MusicBattleMod;

/** Raw key state used only for participant animation, including presses with no nearby note. */
public record BattleLaneAnimationPayload(UUID battleId, int owner, int lane, boolean down)
        implements CustomPacketPayload {
    public static final Type<BattleLaneAnimationPayload> TYPE =
            new Type<>(MusicBattleMod.id("battle_lane_animation"));
    public static final StreamCodec<RegistryFriendlyByteBuf, BattleLaneAnimationPayload> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public BattleLaneAnimationPayload decode(RegistryFriendlyByteBuf buffer) {
                    return new BattleLaneAnimationPayload(buffer.readUUID(), buffer.readVarInt(),
                            buffer.readVarInt(), buffer.readBoolean());
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buffer, BattleLaneAnimationPayload payload) {
                    buffer.writeUUID(payload.battleId());
                    buffer.writeVarInt(payload.owner());
                    buffer.writeVarInt(payload.lane());
                    buffer.writeBoolean(payload.down());
                }
            };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

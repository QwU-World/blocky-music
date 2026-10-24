package ru.gora.musicbattle.network;

import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import ru.gora.musicbattle.MusicBattleMod;

public record BattleActionPayload(
        UUID battleId,
        int owner,
        int lane,
        int noteIndex,
        int noteTick,
        boolean hit,
        boolean complete,
        int score,
        int combo,
        int leftScore,
        int rightScore
) implements CustomPacketPayload {
    public static final Type<BattleActionPayload> TYPE = new Type<>(MusicBattleMod.id("battle_action"));

    public static final StreamCodec<RegistryFriendlyByteBuf, BattleActionPayload> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public BattleActionPayload decode(RegistryFriendlyByteBuf buffer) {
            return new BattleActionPayload(
                    buffer.readUUID(),
                    buffer.readVarInt(),
                    buffer.readVarInt(),
                    buffer.readVarInt(),
                    buffer.readVarInt(),
                    buffer.readBoolean(),
                    buffer.readBoolean(),
                    buffer.readVarInt(),
                    buffer.readVarInt(),
                    buffer.readVarInt(),
                    buffer.readVarInt()
            );
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buffer, BattleActionPayload payload) {
            buffer.writeUUID(payload.battleId());
            buffer.writeVarInt(payload.owner());
            buffer.writeVarInt(payload.lane());
            buffer.writeVarInt(payload.noteIndex());
            buffer.writeVarInt(payload.noteTick());
            buffer.writeBoolean(payload.hit());
            buffer.writeBoolean(payload.complete());
            buffer.writeVarInt(payload.score());
            buffer.writeVarInt(payload.combo());
            buffer.writeVarInt(payload.leftScore());
            buffer.writeVarInt(payload.rightScore());
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

package ru.gora.musicbattle.network;

import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import ru.gora.musicbattle.MusicBattleMod;

/**
 * Something a battle participant did to a note, as the server judged it, with that side's score and combo after it.
 *
 * <p>{@code showAtTick} is the battle tick the action belongs to. A mob plays a few ticks ahead of the battle
 * (BattleManager's AI_LEAD_TICKS), so its actions usually arrive before their tick, and a client holds each of them
 * until its battle clock gets there: that is how a mob's hit lands on its note however the server's ticks fall. A
 * player's action carries {@link #SHOW_ON_ARRIVAL} and is shown as it comes, as every action used to be.
 */
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
        int rightScore,
        int showAtTick
) implements CustomPacketPayload {
    /**
     * No tick to wait for: the action is shown the moment it arrives. Below every battle tick, the count-in included,
     * so no clock is ever behind it.
     */
    public static final int SHOW_ON_ARRIVAL = Integer.MIN_VALUE;

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
            buffer.writeVarInt(payload.showAtTick());
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

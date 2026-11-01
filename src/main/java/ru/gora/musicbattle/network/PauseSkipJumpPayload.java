package ru.gora.musicbattle.network;

import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import ru.gora.musicbattle.MusicBattleMod;

/**
 * A battle has just skipped a long rest: the server's clock went from {@code fromTick} to {@code toTick} in one step.
 * Sent to everyone watching the battle. A player's client moves its own clock by the same number of ticks
 * (BattleSkipClock#clientEpochAfterJump) and starts the song again there; a spectator only starts the song again.
 * It used to arrive as a {@link PauseSkipStatusPayload} carrying the target alone, which told the client where the
 * server meant to be but not how far it had gone, so every skip left the client a fraction of a tick out of step.
 */
public record PauseSkipJumpPayload(UUID battleId, int fromTick, int toTick) implements CustomPacketPayload {
    public static final Type<PauseSkipJumpPayload> TYPE = new Type<>(MusicBattleMod.id("pause_skip_jump"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PauseSkipJumpPayload> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public PauseSkipJumpPayload decode(RegistryFriendlyByteBuf buffer) {
            return new PauseSkipJumpPayload(buffer.readUUID(), buffer.readVarInt(), buffer.readVarInt());
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buffer, PauseSkipJumpPayload payload) {
            buffer.writeUUID(payload.battleId());
            buffer.writeVarInt(payload.fromTick());
            buffer.writeVarInt(payload.toTick());
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

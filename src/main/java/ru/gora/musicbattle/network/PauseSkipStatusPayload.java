package ru.gora.musicbattle.network;

import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import ru.gora.musicbattle.MusicBattleMod;

public record PauseSkipStatusPayload(UUID battleId, boolean available, int votes, int required, int targetTick, boolean applied) implements CustomPacketPayload {
    public static final Type<PauseSkipStatusPayload> TYPE = new Type<>(MusicBattleMod.id("pause_skip_status"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PauseSkipStatusPayload> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public PauseSkipStatusPayload decode(RegistryFriendlyByteBuf buffer) {
            return new PauseSkipStatusPayload(buffer.readUUID(), buffer.readBoolean(), buffer.readVarInt(), buffer.readVarInt(), buffer.readVarInt(), buffer.readBoolean());
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buffer, PauseSkipStatusPayload payload) {
            buffer.writeUUID(payload.battleId());
            buffer.writeBoolean(payload.available());
            buffer.writeVarInt(payload.votes());
            buffer.writeVarInt(payload.required());
            buffer.writeVarInt(payload.targetTick());
            buffer.writeBoolean(payload.applied());
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

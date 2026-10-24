package ru.gora.musicbattle.network;

import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import ru.gora.musicbattle.MusicBattleMod;

public record PauseSkipVotePayload(UUID battleId) implements CustomPacketPayload {
    public static final Type<PauseSkipVotePayload> TYPE = new Type<>(MusicBattleMod.id("pause_skip_vote"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PauseSkipVotePayload> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public PauseSkipVotePayload decode(RegistryFriendlyByteBuf buffer) {
            return new PauseSkipVotePayload(buffer.readUUID());
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buffer, PauseSkipVotePayload payload) {
            buffer.writeUUID(payload.battleId());
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

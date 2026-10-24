package ru.gora.musicbattle.network;

import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import ru.gora.musicbattle.MusicBattleMod;

/** The challenger's answer: send the challenge, as Player 1 when {@code challengerFirst}, otherwise as Player 2. */
public record ChallengeSidePayload(UUID targetId, boolean challengerFirst) implements CustomPacketPayload {
    public static final Type<ChallengeSidePayload> TYPE = new Type<>(MusicBattleMod.id("challenge_side"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ChallengeSidePayload> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public ChallengeSidePayload decode(RegistryFriendlyByteBuf buffer) {
            return new ChallengeSidePayload(buffer.readUUID(), buffer.readBoolean());
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buffer, ChallengeSidePayload payload) {
            buffer.writeUUID(payload.targetId());
            buffer.writeBoolean(payload.challengerFirst());
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

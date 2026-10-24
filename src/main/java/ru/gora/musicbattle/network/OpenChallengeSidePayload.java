package ru.gora.musicbattle.network;

import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import ru.gora.musicbattle.MusicBattleMod;

/** Asks a challenger which side to take before their challenge goes out; answered by {@link ChallengeSidePayload}. */
public record OpenChallengeSidePayload(UUID targetId, String targetName) implements CustomPacketPayload {
    public static final Type<OpenChallengeSidePayload> TYPE = new Type<>(MusicBattleMod.id("open_challenge_side"));

    public static final StreamCodec<RegistryFriendlyByteBuf, OpenChallengeSidePayload> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public OpenChallengeSidePayload decode(RegistryFriendlyByteBuf buffer) {
            return new OpenChallengeSidePayload(buffer.readUUID(), buffer.readUtf(64));
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buffer, OpenChallengeSidePayload payload) {
            buffer.writeUUID(payload.targetId());
            buffer.writeUtf(payload.targetName(), 64);
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

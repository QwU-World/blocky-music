package ru.gora.musicbattle.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import ru.gora.musicbattle.MusicBattleMod;

/**
 * Tells everyone who can see a battle participant to draw a microphone in its main hand, or to stop.
 *
 * <p>Rendering only: the participant's real item is never touched, so nothing can be lost or duplicated if the
 * battle ends badly. The server repeats it while the battle lasts and the client lets it lapse on its own, so a
 * viewer who arrives late still sees it and a missed "stop" cannot leave a microphone behind.
 */
public record BattleMicrophonePayload(int entityId, boolean holding) implements CustomPacketPayload {
    public static final Type<BattleMicrophonePayload> TYPE = new Type<>(MusicBattleMod.id("battle_microphone"));

    public static final StreamCodec<RegistryFriendlyByteBuf, BattleMicrophonePayload> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public BattleMicrophonePayload decode(RegistryFriendlyByteBuf buffer) {
            return new BattleMicrophonePayload(buffer.readVarInt(), buffer.readBoolean());
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buffer, BattleMicrophonePayload payload) {
            buffer.writeVarInt(payload.entityId());
            buffer.writeBoolean(payload.holding());
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

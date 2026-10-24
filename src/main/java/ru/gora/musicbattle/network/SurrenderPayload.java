package ru.gora.musicbattle.network;

import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import ru.gora.musicbattle.MusicBattleMod;

public record SurrenderPayload(UUID battleId) implements CustomPacketPayload {
    public static final Type<SurrenderPayload> TYPE = new Type<>(MusicBattleMod.id("surrender"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SurrenderPayload> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public SurrenderPayload decode(RegistryFriendlyByteBuf buffer) {
            return new SurrenderPayload(buffer.readUUID());
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buffer, SurrenderPayload payload) {
            buffer.writeUUID(payload.battleId());
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

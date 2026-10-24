package ru.gora.musicbattle.network;

import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.chat.Component;
import ru.gora.musicbattle.MusicBattleMod;

public record BattleStopPayload(UUID battleId, Component message) implements CustomPacketPayload {
    public static final Type<BattleStopPayload> TYPE = new Type<>(MusicBattleMod.id("battle_stop"));

    public BattleStopPayload(UUID battleId, String message) {
        this(battleId, Component.literal(message));
    }

    public static final StreamCodec<RegistryFriendlyByteBuf, BattleStopPayload> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public BattleStopPayload decode(RegistryFriendlyByteBuf buffer) {
            return new BattleStopPayload(buffer.readUUID(), ComponentSerialization.TRUSTED_STREAM_CODEC.decode(buffer));
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buffer, BattleStopPayload payload) {
            buffer.writeUUID(payload.battleId());
            ComponentSerialization.TRUSTED_STREAM_CODEC.encode(buffer, payload.message());
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

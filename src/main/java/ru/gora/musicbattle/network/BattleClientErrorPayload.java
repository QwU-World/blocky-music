package ru.gora.musicbattle.network;

import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import ru.gora.musicbattle.MusicBattleMod;

public record BattleClientErrorPayload(UUID battleId, String reason) implements CustomPacketPayload {
    public static final Type<BattleClientErrorPayload> TYPE = new Type<>(MusicBattleMod.id("battle_client_error"));
    private static final int MAX_REASON_LENGTH = 240;

    public static final StreamCodec<RegistryFriendlyByteBuf, BattleClientErrorPayload> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public BattleClientErrorPayload decode(RegistryFriendlyByteBuf buffer) {
            return new BattleClientErrorPayload(buffer.readUUID(), buffer.readUtf(240));
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buffer, BattleClientErrorPayload payload) {
            buffer.writeUUID(payload.battleId());
            buffer.writeUtf(truncate(payload.reason()), MAX_REASON_LENGTH);
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static String truncate(String value) {
        String text = value == null ? "unknown client error" : value;
        return text.length() <= MAX_REASON_LENGTH ? text : text.substring(0, MAX_REASON_LENGTH);
    }
}

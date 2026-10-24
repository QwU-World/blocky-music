package ru.gora.musicbattle.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import ru.gora.musicbattle.MusicBattleMod;

public record ConfigureRapGodPayload(
        int entityId,
        String displayName,
        String skinName,
        boolean slimSkin,
        boolean pushableWhenStationary,
        boolean stationary,
        boolean invulnerable,
        boolean glowing,
        boolean randomVotes
) implements CustomPacketPayload {
    public static final Type<ConfigureRapGodPayload> TYPE = new Type<>(MusicBattleMod.id("configure_rap_god"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ConfigureRapGodPayload> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public ConfigureRapGodPayload decode(RegistryFriendlyByteBuf buffer) {
            return new ConfigureRapGodPayload(
                    buffer.readVarInt(),
                    buffer.readUtf(64),
                    buffer.readUtf(32),
                    buffer.readBoolean(),
                    buffer.readBoolean(),
                    buffer.readBoolean(),
                    buffer.readBoolean(),
                    buffer.readBoolean(),
                    buffer.readBoolean()
            );
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buffer, ConfigureRapGodPayload payload) {
            buffer.writeVarInt(payload.entityId());
            buffer.writeUtf(payload.displayName(), 64);
            buffer.writeUtf(payload.skinName(), 32);
            buffer.writeBoolean(payload.slimSkin());
            buffer.writeBoolean(payload.pushableWhenStationary());
            buffer.writeBoolean(payload.stationary());
            buffer.writeBoolean(payload.invulnerable());
            buffer.writeBoolean(payload.glowing());
            buffer.writeBoolean(payload.randomVotes());
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

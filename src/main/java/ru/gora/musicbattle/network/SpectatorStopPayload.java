package ru.gora.musicbattle.network;

import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import ru.gora.musicbattle.MusicBattleMod;

/** Clears a spectator/preload session without ending the server-side battle. */
public record SpectatorStopPayload(UUID battleId, boolean releaseEffects) implements CustomPacketPayload {
    public SpectatorStopPayload(UUID battleId) { this(battleId, false); }
    public static final Type<SpectatorStopPayload> TYPE = new Type<>(MusicBattleMod.id("spectator_stop"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SpectatorStopPayload> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public SpectatorStopPayload decode(RegistryFriendlyByteBuf buffer) {
            return new SpectatorStopPayload(buffer.readUUID(), buffer.readBoolean());
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buffer, SpectatorStopPayload payload) {
            buffer.writeUUID(payload.battleId());
            buffer.writeBoolean(payload.releaseEffects());
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

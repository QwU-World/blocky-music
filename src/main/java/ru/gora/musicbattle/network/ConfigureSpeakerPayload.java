package ru.gora.musicbattle.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import ru.gora.musicbattle.MusicBattleMod;

public record ConfigureSpeakerPayload(BlockPos pos, float radius, float volume,
                                      float lowGainDb, float midGainDb, float highGainDb) implements CustomPacketPayload {
    public static final Type<ConfigureSpeakerPayload> TYPE = new Type<>(MusicBattleMod.id("configure_speaker"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ConfigureSpeakerPayload> STREAM_CODEC = new StreamCodec<>() {
        @Override public ConfigureSpeakerPayload decode(RegistryFriendlyByteBuf buffer) {
            return new ConfigureSpeakerPayload(buffer.readBlockPos(), buffer.readFloat(), buffer.readFloat(),
                    buffer.readFloat(), buffer.readFloat(), buffer.readFloat());
        }
        @Override public void encode(RegistryFriendlyByteBuf buffer, ConfigureSpeakerPayload payload) {
            buffer.writeBlockPos(payload.pos());
            buffer.writeFloat(payload.radius());
            buffer.writeFloat(payload.volume());
            buffer.writeFloat(payload.lowGainDb());
            buffer.writeFloat(payload.midGainDb());
            buffer.writeFloat(payload.highGainDb());
        }
    };
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}

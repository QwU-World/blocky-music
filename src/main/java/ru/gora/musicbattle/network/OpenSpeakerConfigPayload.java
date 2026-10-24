package ru.gora.musicbattle.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import ru.gora.musicbattle.MusicBattleMod;

public record OpenSpeakerConfigPayload(BlockPos pos, float radius, float volume,
                                       float lowGainDb, float midGainDb, float highGainDb) implements CustomPacketPayload {
    public static final Type<OpenSpeakerConfigPayload> TYPE = new Type<>(MusicBattleMod.id("open_speaker_config"));
    public static final StreamCodec<RegistryFriendlyByteBuf, OpenSpeakerConfigPayload> STREAM_CODEC = new StreamCodec<>() {
        @Override public OpenSpeakerConfigPayload decode(RegistryFriendlyByteBuf buffer) {
            return new OpenSpeakerConfigPayload(buffer.readBlockPos(), buffer.readFloat(), buffer.readFloat(),
                    buffer.readFloat(), buffer.readFloat(), buffer.readFloat());
        }
        @Override public void encode(RegistryFriendlyByteBuf buffer, OpenSpeakerConfigPayload payload) {
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

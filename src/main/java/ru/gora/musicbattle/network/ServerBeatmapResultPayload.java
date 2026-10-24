package ru.gora.musicbattle.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import ru.gora.musicbattle.MusicBattleMod;

/** The outcome of an add or remove, as a component so the receiving client picks its own language. */
public record ServerBeatmapResultPayload(boolean success, Component message, long setId) implements CustomPacketPayload {
    public static final Type<ServerBeatmapResultPayload> TYPE = new Type<>(MusicBattleMod.id("server_beatmap_result"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ServerBeatmapResultPayload> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public ServerBeatmapResultPayload decode(RegistryFriendlyByteBuf buffer) {
            return new ServerBeatmapResultPayload(buffer.readBoolean(),
                    ComponentSerialization.STREAM_CODEC.decode(buffer), buffer.readVarLong());
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buffer, ServerBeatmapResultPayload payload) {
            buffer.writeBoolean(payload.success());
            ComponentSerialization.STREAM_CODEC.encode(buffer, payload.message());
            buffer.writeVarLong(Math.max(0L, payload.setId()));
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

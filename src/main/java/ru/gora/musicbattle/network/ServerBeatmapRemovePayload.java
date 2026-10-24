package ru.gora.musicbattle.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import ru.gora.musicbattle.MusicBattleMod;

/** "Remove from server" on a card, for a player who holds {@code music_battle.server_beatmaps.manage}. */
public record ServerBeatmapRemovePayload(long setId) implements CustomPacketPayload {
    public static final Type<ServerBeatmapRemovePayload> TYPE = new Type<>(MusicBattleMod.id("server_beatmap_remove"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ServerBeatmapRemovePayload> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public ServerBeatmapRemovePayload decode(RegistryFriendlyByteBuf buffer) {
            return new ServerBeatmapRemovePayload(buffer.readVarLong());
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buffer, ServerBeatmapRemovePayload payload) {
            buffer.writeVarLong(Math.max(0L, payload.setId()));
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

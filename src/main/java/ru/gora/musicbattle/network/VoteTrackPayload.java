package ru.gora.musicbattle.network;

import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import ru.gora.musicbattle.MusicBattleMod;

public record VoteTrackPayload(UUID battleId, String trackId, String mobSkill, boolean unifiedNotes, boolean deadlyParty, float speedMultiplier) implements CustomPacketPayload {
    public static final Type<VoteTrackPayload> TYPE = new Type<>(MusicBattleMod.id("vote_track"));

    public static final StreamCodec<RegistryFriendlyByteBuf, VoteTrackPayload> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public VoteTrackPayload decode(RegistryFriendlyByteBuf buffer) {
            return new VoteTrackPayload(buffer.readUUID(), buffer.readUtf(255), buffer.readUtf(16), buffer.readBoolean(), buffer.readBoolean(), buffer.readFloat());
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buffer, VoteTrackPayload payload) {
            buffer.writeUUID(payload.battleId());
            buffer.writeUtf(payload.trackId(), 255);
            buffer.writeUtf(payload.mobSkill(), 16);
            buffer.writeBoolean(payload.unifiedNotes());
            buffer.writeBoolean(payload.deadlyParty());
            buffer.writeFloat(payload.speedMultiplier());
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

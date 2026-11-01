package ru.gora.musicbattle.network;

import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import ru.gora.musicbattle.MusicBattleMod;

/**
 * A vote for one of the voter's own installed sets. The server checks
 * {@code workshop.allowPlayerMaps}, then either already holds that content or asks the voter for the package over
 * the same chunk channel as "Add to server", into {@code <world>/blocky_music/workshop_cache/}.
 */
public record VoteInstalledPayload(UUID battleId, long setId, int revision, String contentSha256, String chartKey,
                                   String mobSkill, boolean unifiedNotes, boolean deadlyParty, float speedMultiplier)
        implements CustomPacketPayload {
    public static final Type<VoteInstalledPayload> TYPE = new Type<>(MusicBattleMod.id("vote_installed"));

    public static final StreamCodec<RegistryFriendlyByteBuf, VoteInstalledPayload> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public VoteInstalledPayload decode(RegistryFriendlyByteBuf buffer) {
            return new VoteInstalledPayload(buffer.readUUID(), buffer.readVarLong(), buffer.readVarInt(),
                    buffer.readUtf(64), buffer.readUtf(32), buffer.readUtf(16), buffer.readBoolean(),
                    buffer.readBoolean(), buffer.readFloat());
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buffer, VoteInstalledPayload payload) {
            buffer.writeUUID(payload.battleId());
            buffer.writeVarLong(Math.max(0L, payload.setId()));
            buffer.writeVarInt(Math.max(0, payload.revision()));
            buffer.writeUtf(payload.contentSha256() == null ? "" : payload.contentSha256(), 64);
            buffer.writeUtf(payload.chartKey() == null ? "" : payload.chartKey(), 32);
            buffer.writeUtf(payload.mobSkill() == null ? "" : payload.mobSkill(), 16);
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

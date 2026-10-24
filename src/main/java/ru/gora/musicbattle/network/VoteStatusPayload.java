package ru.gora.musicbattle.network;

import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import ru.gora.musicbattle.MusicBattleMod;

/**
 * What an open vote screen learns while the vote runs: another voter's choice, and always the
 * server's ticks left, so the countdown re-syncs whenever anyone votes. When the vote closes the
 * server sends one without a voter ({@link #closed}): zero ticks left, and whether everyone chose.
 */
public record VoteStatusPayload(
        UUID battleId,
        String voterName,
        String trackTitle,
        boolean unifiedNotes,
        boolean deadlyParty,
        float speedMultiplier,
        int remainingTicks,
        boolean everyoneVoted
) implements CustomPacketPayload {
    public static final Type<VoteStatusPayload> TYPE = new Type<>(MusicBattleMod.id("vote_status"));

    public static final StreamCodec<RegistryFriendlyByteBuf, VoteStatusPayload> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public VoteStatusPayload decode(RegistryFriendlyByteBuf buffer) {
            return new VoteStatusPayload(
                    buffer.readUUID(),
                    buffer.readUtf(128),
                    buffer.readUtf(128),
                    buffer.readBoolean(),
                    buffer.readBoolean(),
                    buffer.readFloat(),
                    buffer.readVarInt(),
                    buffer.readBoolean()
            );
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buffer, VoteStatusPayload payload) {
            buffer.writeUUID(payload.battleId());
            buffer.writeUtf(payload.voterName(), 128);
            buffer.writeUtf(payload.trackTitle(), 128);
            buffer.writeBoolean(payload.unifiedNotes());
            buffer.writeBoolean(payload.deadlyParty());
            buffer.writeFloat(payload.speedMultiplier());
            buffer.writeVarInt(Math.max(0, payload.remainingTicks()));
            buffer.writeBoolean(payload.everyoneVoted());
        }
    };

    /** The vote has closed: the countdown ends now, whatever the client's own clock says. */
    public static VoteStatusPayload closed(UUID battleId, boolean everyoneVoted) {
        return new VoteStatusPayload(battleId, "", "", false, false, 1.0F, 0, everyoneVoted);
    }

    /** False for {@link #closed} updates, which carry only the timer and must not clear a shown choice. */
    public boolean carriesVote() {
        return !voterName.isEmpty() || !trackTitle.isEmpty();
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

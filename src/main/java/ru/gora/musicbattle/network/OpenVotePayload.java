package ru.gora.musicbattle.network;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import ru.gora.musicbattle.MusicBattleMod;
import ru.gora.musicbattle.data.TrackSummary;

public record OpenVotePayload(
        UUID battleId,
        String opponentName,
        boolean leftSide,
        boolean mobBattle,
        boolean solo,
        /** Server ticks left in the vote when this was sent; the screen counts down from it. */
        int remainingTicks,
        List<TrackSummary> tracks,
        /** True when the server had more tracks than fit: the screen says so instead of quietly showing fewer. */
        boolean truncated
) implements CustomPacketPayload {
    public static final Type<OpenVotePayload> TYPE = new Type<>(MusicBattleMod.id("open_vote"));
    public static final int MAX_TRACKS = 512;

    public static final StreamCodec<RegistryFriendlyByteBuf, OpenVotePayload> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public OpenVotePayload decode(RegistryFriendlyByteBuf buffer) {
            UUID battleId = buffer.readUUID();
            String opponentName = buffer.readUtf(128);
            boolean leftSide = buffer.readBoolean();
            boolean mobBattle = buffer.readBoolean();
            boolean solo = buffer.readBoolean();
            int remainingTicks = buffer.readVarInt();
            int count = Math.min(buffer.readVarInt(), MAX_TRACKS);
            List<TrackSummary> tracks = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                tracks.add(TrackSummary.read(buffer));
            }
            return new OpenVotePayload(battleId, opponentName, leftSide, mobBattle, solo, remainingTicks,
                    List.copyOf(tracks), buffer.readBoolean());
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buffer, OpenVotePayload payload) {
            buffer.writeUUID(payload.battleId());
            buffer.writeUtf(payload.opponentName(), 128);
            buffer.writeBoolean(payload.leftSide());
            buffer.writeBoolean(payload.mobBattle());
            buffer.writeBoolean(payload.solo());
            buffer.writeVarInt(Math.max(0, payload.remainingTicks()));
            int count = Math.min(payload.tracks().size(), MAX_TRACKS);
            buffer.writeVarInt(count);
            for (int i = 0; i < count; i++) {
                payload.tracks().get(i).write(buffer);
            }
            buffer.writeBoolean(payload.truncated() || count < payload.tracks().size());
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

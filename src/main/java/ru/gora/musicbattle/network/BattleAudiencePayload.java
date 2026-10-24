package ru.gora.musicbattle.network;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import ru.gora.musicbattle.MusicBattleMod;

public record BattleAudiencePayload(
        UUID battleId,
        List<Integer> spectatorEntityIds,
        List<Integer> mobEntityIds
) implements CustomPacketPayload {
    private static final int MAX_SPECTATORS = 256;
    private static final int MAX_MOBS = 1024;

    public static final Type<BattleAudiencePayload> TYPE = new Type<>(MusicBattleMod.id("battle_audience"));

    public static final StreamCodec<RegistryFriendlyByteBuf, BattleAudiencePayload> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public BattleAudiencePayload decode(RegistryFriendlyByteBuf buffer) {
            UUID battleId = buffer.readUUID();
            List<Integer> spectatorEntityIds = readEntityIds(buffer, MAX_SPECTATORS, "spectators");
            List<Integer> mobEntityIds = readEntityIds(buffer, MAX_MOBS, "mobs");
            return new BattleAudiencePayload(battleId, spectatorEntityIds, mobEntityIds);
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buffer, BattleAudiencePayload payload) {
            buffer.writeUUID(payload.battleId());
            writeEntityIds(buffer, payload.spectatorEntityIds(), MAX_SPECTATORS);
            writeEntityIds(buffer, payload.mobEntityIds(), MAX_MOBS);
        }
    };

    private static List<Integer> readEntityIds(RegistryFriendlyByteBuf buffer, int maximum, String label) {
        int count = buffer.readVarInt();
        if (count < 0 || count > maximum) {
            throw new IllegalArgumentException("Invalid battle audience " + label + " count: " + count);
        }
        List<Integer> entityIds = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            entityIds.add(buffer.readVarInt());
        }
        return List.copyOf(entityIds);
    }

    private static void writeEntityIds(RegistryFriendlyByteBuf buffer, List<Integer> entityIds, int maximum) {
        int count = Math.min(maximum, entityIds.size());
        buffer.writeVarInt(count);
        for (int index = 0; index < count; index++) {
            buffer.writeVarInt(entityIds.get(index));
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

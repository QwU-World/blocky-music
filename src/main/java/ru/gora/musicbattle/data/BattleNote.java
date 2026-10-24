package ru.gora.musicbattle.data;

import net.minecraft.network.RegistryFriendlyByteBuf;

public record BattleNote(int owner, int lane, int tick, int durationTicks) {
    public BattleNote {
        owner = owner <= 0 ? 0 : 1;
        lane = Math.max(0, Math.min(3, lane));
        tick = Math.max(0, tick);
        durationTicks = Math.max(0, durationTicks);
    }

    public BattleNote(int owner, int lane, int tick) {
        this(owner, lane, tick, 0);
    }

    public static BattleNote read(RegistryFriendlyByteBuf buffer) {
        return new BattleNote(buffer.readVarInt(), buffer.readVarInt(), buffer.readVarInt(), buffer.readVarInt());
    }

    public void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeVarInt(this.owner);
        buffer.writeVarInt(this.lane);
        buffer.writeVarInt(this.tick);
        buffer.writeVarInt(this.durationTicks);
    }
}

package ru.gora.musicbattle.network;

import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import ru.gora.musicbattle.MusicBattleMod;
import ru.gora.musicbattle.data.NoteAppearance;
import ru.gora.musicbattle.data.NoteSpeed;

/** A presentation update only: it never restarts a battle or replaces score/notes. */
public record BattleAppearancePayload(UUID battleId, int owner, float speed, NoteAppearance appearance) implements CustomPacketPayload {
    public static final Type<BattleAppearancePayload> TYPE = new Type<>(MusicBattleMod.id("battle_appearance"));
    public BattleAppearancePayload {
        owner = Math.max(0, Math.min(1, owner));
        speed = NoteSpeed.clamp(speed);
        appearance = appearance == null ? NoteAppearance.solid(0xFFFF77A9) : appearance;
    }
    public static final StreamCodec<RegistryFriendlyByteBuf, BattleAppearancePayload> STREAM_CODEC = new StreamCodec<>() {
        @Override public BattleAppearancePayload decode(RegistryFriendlyByteBuf buffer) {
            return new BattleAppearancePayload(buffer.readUUID(), buffer.readVarInt(), buffer.readFloat(), NoteAppearanceCodec.read(buffer));
        }
        @Override public void encode(RegistryFriendlyByteBuf buffer, BattleAppearancePayload payload) {
            buffer.writeUUID(payload.battleId()); buffer.writeVarInt(payload.owner()); buffer.writeFloat(payload.speed());
            NoteAppearanceCodec.write(buffer, payload.appearance());
        }
    };
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}

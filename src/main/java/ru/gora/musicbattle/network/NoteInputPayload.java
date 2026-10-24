package ru.gora.musicbattle.network;

import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import ru.gora.musicbattle.MusicBattleMod;

public record NoteInputPayload(UUID battleId, int lane, int clientTick, boolean down, int noteIndex) implements CustomPacketPayload {
    public static final Type<NoteInputPayload> TYPE = new Type<>(MusicBattleMod.id("note_input"));

    public static final StreamCodec<RegistryFriendlyByteBuf, NoteInputPayload> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public NoteInputPayload decode(RegistryFriendlyByteBuf buffer) {
            return new NoteInputPayload(buffer.readUUID(), buffer.readVarInt(), buffer.readVarInt(), buffer.readBoolean(), buffer.readVarInt());
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buffer, NoteInputPayload payload) {
            buffer.writeUUID(payload.battleId());
            buffer.writeVarInt(payload.lane());
            buffer.writeVarInt(payload.clientTick());
            buffer.writeBoolean(payload.down());
            buffer.writeVarInt(payload.noteIndex());
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

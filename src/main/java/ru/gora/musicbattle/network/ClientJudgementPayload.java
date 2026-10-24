package ru.gora.musicbattle.network;

import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import ru.gora.musicbattle.MusicBattleMod;

public record ClientJudgementPayload(
        UUID battleId,
        int sequence,
        int kind,
        int lane,
        int noteIndex,
        int clientTick,
        int score,
        int combo,
        int mistakes
) implements CustomPacketPayload {
    public static final int NOTE_HIT = 0;
    public static final int HOLD_COMPLETE = 1;
    public static final int NOTE_MISS = 2;
    public static final int BAD_PRESS = 3;
    public static final int HOLD_BREAK = 4;

    public static final Type<ClientJudgementPayload> TYPE = new Type<>(MusicBattleMod.id("client_judgement"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ClientJudgementPayload> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public ClientJudgementPayload decode(RegistryFriendlyByteBuf buffer) {
            return new ClientJudgementPayload(
                    buffer.readUUID(),
                    buffer.readVarInt(),
                    buffer.readVarInt(),
                    buffer.readVarInt(),
                    buffer.readVarInt(),
                    buffer.readVarInt(),
                    buffer.readVarInt(),
                    buffer.readVarInt(),
                    buffer.readVarInt()
            );
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buffer, ClientJudgementPayload payload) {
            buffer.writeUUID(payload.battleId());
            buffer.writeVarInt(payload.sequence());
            buffer.writeVarInt(payload.kind());
            buffer.writeVarInt(payload.lane());
            buffer.writeVarInt(payload.noteIndex());
            buffer.writeVarInt(payload.clientTick());
            buffer.writeVarInt(payload.score());
            buffer.writeVarInt(payload.combo());
            buffer.writeVarInt(payload.mistakes());
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

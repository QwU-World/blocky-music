package ru.gora.musicbattle.network;

import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import ru.gora.musicbattle.MusicBattleMod;
import ru.gora.musicbattle.data.DjPlaylistEntry;

/** Bounded, server-authoritative mutations for one DJ panel playlist. */
public record ConfigureDjPlaylistPayload(
        BlockPos panelPos,
        Action action,
        UUID entryId,
        String trackId,
        int startSourceTicks,
        int targetIndex,
        boolean autoAdvance,
        int repeatMode
) implements CustomPacketPayload {
    /** Highest accepted ordinal of {@code DjPanelBlockEntity.PlaylistRepeat}. */
    private static final int MAX_REPEAT_MODE = 2;
    public static final Type<ConfigureDjPlaylistPayload> TYPE =
            new Type<>(MusicBattleMod.id("configure_dj_playlist"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ConfigureDjPlaylistPayload> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public ConfigureDjPlaylistPayload decode(RegistryFriendlyByteBuf buffer) {
                    BlockPos panelPos = buffer.readBlockPos();
                    Action action = Action.byOrdinal(buffer.readVarInt());
                    UUID entryId = buffer.readBoolean() ? buffer.readUUID() : null;
                    String trackId = buffer.readUtf(DjPlaylistEntry.MAX_TRACK_ID_LENGTH);
                    int startSourceTicks = buffer.readVarInt();
                    int targetIndex = buffer.readVarInt();
                    boolean autoAdvance = buffer.readBoolean();
                    int repeatMode = buffer.readVarInt();
                    if (startSourceTicks < 0 || targetIndex < -1
                            || targetIndex >= DjPlaylistEntry.MAX_ENTRIES
                            || repeatMode < 0 || repeatMode > MAX_REPEAT_MODE) {
                        throw new IllegalArgumentException("Invalid DJ playlist command bounds");
                    }
                    return new ConfigureDjPlaylistPayload(panelPos, action, entryId, trackId,
                            startSourceTicks, targetIndex, autoAdvance, repeatMode);
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buffer, ConfigureDjPlaylistPayload payload) {
                    buffer.writeBlockPos(payload.panelPos());
                    buffer.writeVarInt(payload.action().ordinal());
                    buffer.writeBoolean(payload.entryId() != null);
                    if (payload.entryId() != null) {
                        buffer.writeUUID(payload.entryId());
                    }
                    buffer.writeUtf(payload.trackId() == null ? "" : payload.trackId(),
                            DjPlaylistEntry.MAX_TRACK_ID_LENGTH);
                    buffer.writeVarInt(Math.max(0, payload.startSourceTicks()));
                    buffer.writeVarInt(MthBound.index(payload.targetIndex()));
                    buffer.writeBoolean(payload.autoAdvance());
                    buffer.writeVarInt(Math.max(0, Math.min(MAX_REPEAT_MODE, payload.repeatMode())));
                }
            };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public enum Action {
        ADD,
        REMOVE,
        MOVE,
        CLEAR,
        SET_CUE,
        PLAY_ENTRY,
        SET_AUTO_ADVANCE,
        REFRESH,
        FX_TRACK,
        FX_DJ,
        FX_NONE,
        SET_REPEAT;

        private static Action byOrdinal(int value) {
            Action[] values = values();
            if (value < 0 || value >= values.length) {
                throw new IllegalArgumentException("Invalid DJ playlist action: " + value);
            }
            return values[value];
        }
    }

    /** Keeps malformed local callers from emitting an unbounded index. */
    private static final class MthBound {
        private MthBound() {
        }

        private static int index(int value) {
            return Math.max(-1, Math.min(DjPlaylistEntry.MAX_ENTRIES - 1, value));
        }
    }
}

package ru.gora.musicbattle.network;

import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import ru.gora.musicbattle.MusicBattleMod;
import ru.gora.musicbattle.data.DjPlaylistMixer;

/** Bounded atomic controls update; the chart snapshot remains server-owned. */
public record EditDjPlaylistMixerPayload(BlockPos panelPos, UUID entryId,
                                          DjPlaylistMixer expected, DjPlaylistMixer replacement) implements CustomPacketPayload {
    public static final Type<EditDjPlaylistMixerPayload> TYPE = new Type<>(MusicBattleMod.id("edit_dj_playlist_mixer"));
    public static final StreamCodec<RegistryFriendlyByteBuf, EditDjPlaylistMixerPayload> STREAM_CODEC = new StreamCodec<>() {
        public EditDjPlaylistMixerPayload decode(RegistryFriendlyByteBuf b) {
            return new EditDjPlaylistMixerPayload(b.readBlockPos(),b.readUUID(),DjPlaylistMixer.read(b),DjPlaylistMixer.read(b));
        }
        public void encode(RegistryFriendlyByteBuf b, EditDjPlaylistMixerPayload p) {
            b.writeBlockPos(p.panelPos); b.writeUUID(p.entryId); p.expected.write(b); p.replacement.write(b);
        }
    };
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}

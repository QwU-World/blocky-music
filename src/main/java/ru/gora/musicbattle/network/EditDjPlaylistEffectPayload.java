package ru.gora.musicbattle.network;

import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import ru.gora.musicbattle.MusicBattleMod;
import ru.gora.musicbattle.data.TrackEffect;
import ru.gora.musicbattle.data.DjPlaylistEntry;

/** One bounded edit, never a client-supplied entire chart. A null effect deletes a row. */
public record EditDjPlaylistEffectPayload(BlockPos panelPos, UUID entryId, int index, TrackEffect effect,
                                          DjPlaylistEntry.FxMode expectedMode, int expectedCount, TrackEffect expected) implements CustomPacketPayload {
    public static final Type<EditDjPlaylistEffectPayload> TYPE = new Type<>(MusicBattleMod.id("edit_dj_playlist_effect"));
    public static final StreamCodec<RegistryFriendlyByteBuf, EditDjPlaylistEffectPayload> STREAM_CODEC = new StreamCodec<>() {
        public EditDjPlaylistEffectPayload decode(RegistryFriendlyByteBuf b) {
            BlockPos pos=b.readBlockPos(); UUID id=b.readUUID(); int index=b.readVarInt();
            if(index < 0 || index >= TrackEffect.MAX_EFFECTS) throw new IllegalArgumentException("Invalid FX row");
            TrackEffect effect=b.readBoolean()?TrackEffect.read(b):null;
            var mode=DjPlaylistEntry.FxMode.read(b.readVarInt()); int count=b.readVarInt();
            if(count<0||count>TrackEffect.MAX_EFFECTS)throw new IllegalArgumentException("Invalid expected FX count");
            return new EditDjPlaylistEffectPayload(pos,id,index,effect,mode,count,b.readBoolean()?TrackEffect.read(b):null);
        }
        public void encode(RegistryFriendlyByteBuf b, EditDjPlaylistEffectPayload p) {
            b.writeBlockPos(p.panelPos); b.writeUUID(p.entryId); b.writeVarInt(p.index);
            b.writeBoolean(p.effect!=null); if(p.effect!=null)p.effect.write(b);
            b.writeVarInt(p.expectedMode.ordinal());b.writeVarInt(p.expectedCount);
            b.writeBoolean(p.expected!=null);if(p.expected!=null)p.expected.write(b);
        }
    };
    public Type<? extends CustomPacketPayload> type(){return TYPE;}
}

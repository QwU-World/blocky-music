package ru.gora.musicbattle.network;

import java.util.List;
import net.minecraft.network.FriendlyByteBuf;
import ru.gora.musicbattle.data.NoteAppearance;

final class NoteAppearanceCodec {
    private NoteAppearanceCodec() {}
    static NoteAppearance read(FriendlyByteBuf buffer) {
        return new NoteAppearance(buffer.readVarInt(), List.of(buffer.readInt(), buffer.readInt(), buffer.readInt(), buffer.readInt()));
    }
    static void write(FriendlyByteBuf buffer, NoteAppearance appearance) {
        buffer.writeVarInt(appearance.skin());
        for (int lane=0; lane<4; lane++) buffer.writeInt(appearance.color(lane));
    }
}

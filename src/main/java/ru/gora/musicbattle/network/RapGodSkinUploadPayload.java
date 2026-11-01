package ru.gora.musicbattle.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import ru.gora.musicbattle.MusicBattleMod;
import ru.gora.musicbattle.data.NpcSkinPng;

/**
 * A skin from a file for the test NPC, from the configuration screen to the server. An empty {@code png} takes the
 * file skin off. The client has already re-encoded the picture through the game's PNG writer, so it fits one payload;
 * the server checks it again with {@link NpcSkinPng#check} before the NPC wears it, and the NPC's synched data then
 * carries it to every client that sees the NPC.
 */
public record RapGodSkinUploadPayload(int entityId, byte[] png) implements CustomPacketPayload {
    public static final Type<RapGodSkinUploadPayload> TYPE = new Type<>(MusicBattleMod.id("rap_god_skin_upload"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RapGodSkinUploadPayload> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public RapGodSkinUploadPayload decode(RegistryFriendlyByteBuf buffer) {
            int entityId = buffer.readVarInt();
            int length = buffer.readVarInt();
            if (length < 0 || length > NpcSkinPng.MAX_BYTES) {
                throw new IllegalArgumentException("NPC skin of " + length + " bytes");
            }
            byte[] png = new byte[length];
            buffer.readBytes(png);
            return new RapGodSkinUploadPayload(entityId, png);
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buffer, RapGodSkinUploadPayload payload) {
            if (payload.png().length > NpcSkinPng.MAX_BYTES) {
                throw new IllegalArgumentException("NPC skin of " + payload.png().length + " bytes");
            }
            buffer.writeVarInt(payload.entityId());
            buffer.writeVarInt(payload.png().length);
            buffer.writeBytes(payload.png());
        }
    };

    /** Takes the NPC's file skin off. */
    public static RapGodSkinUploadPayload remove(int entityId) {
        return new RapGodSkinUploadPayload(entityId, new byte[0]);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

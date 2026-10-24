package ru.gora.musicbattle.network;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import ru.gora.musicbattle.MusicBattleMod;
import ru.gora.musicbattle.data.NoteAppearance;

public record SettingsPayload(
        float noteSpeed,
        int panelColor,
        float panelOpacity,
        float laneOpacity,
        float panelWidthScale,
        float panelHeightScale,
        float noteWidthScale,
        float noteHeightScale,
        List<String> keyLabels,
        NoteAppearance appearance
) implements CustomPacketPayload {
    public SettingsPayload(float noteSpeed, int panelColor, float panelOpacity, float laneOpacity,
                           float panelWidthScale, float panelHeightScale, float noteWidthScale,
                           float noteHeightScale, List<String> keyLabels) {
        this(noteSpeed, panelColor, panelOpacity, laneOpacity, panelWidthScale, panelHeightScale,
                noteWidthScale, noteHeightScale, keyLabels, NoteAppearance.solid(panelColor));
    }
    public static final Type<SettingsPayload> TYPE = new Type<>(MusicBattleMod.id("settings"));
    private static final int LANE_COUNT = 4;

    public static final StreamCodec<RegistryFriendlyByteBuf, SettingsPayload> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public SettingsPayload decode(RegistryFriendlyByteBuf buffer) {
            List<String> labels = new ArrayList<>(LANE_COUNT);
            float speed = buffer.readFloat();
            int panelColor = buffer.readInt();
            float panelOpacity = buffer.readFloat();
            float laneOpacity = buffer.readFloat();
            float panelWidthScale = buffer.readFloat();
            float panelHeightScale = buffer.readFloat();
            float noteWidthScale = buffer.readFloat();
            float noteHeightScale = buffer.readFloat();
            for (int i = 0; i < LANE_COUNT; i++) {
                labels.add(buffer.readUtf(32));
            }
            return new SettingsPayload(speed, panelColor, panelOpacity, laneOpacity, panelWidthScale, panelHeightScale, noteWidthScale, noteHeightScale, List.copyOf(labels), NoteAppearanceCodec.read(buffer));
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buffer, SettingsPayload payload) {
            buffer.writeFloat(payload.noteSpeed());
            buffer.writeInt(payload.panelColor());
            buffer.writeFloat(payload.panelOpacity());
            buffer.writeFloat(payload.laneOpacity());
            buffer.writeFloat(payload.panelWidthScale());
            buffer.writeFloat(payload.panelHeightScale());
            buffer.writeFloat(payload.noteWidthScale());
            buffer.writeFloat(payload.noteHeightScale());
            for (int i = 0; i < LANE_COUNT; i++) {
                String label = i < payload.keyLabels().size() ? payload.keyLabels().get(i) : "";
                buffer.writeUtf(label, 32);
            }
            NoteAppearanceCodec.write(buffer, payload.appearance());
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

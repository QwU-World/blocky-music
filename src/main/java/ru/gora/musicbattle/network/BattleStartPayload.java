package ru.gora.musicbattle.network;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import ru.gora.musicbattle.MusicBattleMod;
import ru.gora.musicbattle.data.BattleNote;
import ru.gora.musicbattle.data.NoteAppearance;

public record BattleStartPayload(
        UUID battleId,
        int durationTicks,
        /**
         * Milliseconds from this packet arriving to the instant the battle begins, worked out per player so that
         * every client reaches tick zero together: the server subtracts each player's own one-way delay from one
         * shared lead. Before this the countdown began when the packet happened to arrive and the chart began when
         * that machine's ogg happened to start decoding, so two players were playing a chart offset by a tenth of
         * a second or more, and their scores could not agree.
         */
        int startDelayMillis,
        int bpm,
        String trackId,
        String title,
        String description,
        float volume,
        float speedMultiplier,
        String audioFile,
        String previewFile,
        String leftName,
        String rightName,
        float ownSpeed,
        float opponentSpeed,
        int ownColor,
        int opponentColor,
        float ownPanelOpacity,
        float ownLaneOpacity,
        float ownPanelWidthScale,
        float ownPanelHeightScale,
        float ownNoteWidthScale,
        float ownNoteHeightScale,
        float opponentPanelOpacity,
        float opponentLaneOpacity,
        float opponentPanelWidthScale,
        float opponentPanelHeightScale,
        float opponentNoteWidthScale,
        float opponentNoteHeightScale,
        float leftSpeed,
        float rightSpeed,
        List<String> ownKeyLabels,
        List<String> opponentKeyLabels,
        boolean leftSide,
        int leftEntityId,
        int rightEntityId,
        int opponentEntityId,
        int cameraEntityId,
        float cameraYaw,
        float cameraPitch,
        float cameraFov,
        List<BattleNote> notes,
        NoteAppearance ownAppearance,
        NoteAppearance opponentAppearance
) implements CustomPacketPayload {
    public static final Type<BattleStartPayload> TYPE = new Type<>(MusicBattleMod.id("battle_start"));
    private static final int MAX_NOTES = 4096;
    private static final int LANE_COUNT = 4;

    public static final StreamCodec<RegistryFriendlyByteBuf, BattleStartPayload> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public BattleStartPayload decode(RegistryFriendlyByteBuf buffer) {
            UUID battleId = buffer.readUUID();
            int durationTicks = buffer.readVarInt();
            int startDelayMillis = buffer.readVarInt();
            int bpm = buffer.readVarInt();
            String trackId = buffer.readUtf(255);
            String title = buffer.readUtf(128);
            String description = buffer.readUtf(1024);
            float volume = buffer.readFloat();
            float speedMultiplier = buffer.readFloat();
            String audioFile = buffer.readUtf(255);
            String previewFile = buffer.readUtf(255);
            String leftName = buffer.readUtf(128);
            String rightName = buffer.readUtf(128);
            float ownSpeed = buffer.readFloat();
            float opponentSpeed = buffer.readFloat();
            int ownColor = buffer.readInt();
            int opponentColor = buffer.readInt();
            float ownPanelOpacity = buffer.readFloat();
            float ownLaneOpacity = buffer.readFloat();
            float ownPanelWidthScale = buffer.readFloat();
            float ownPanelHeightScale = buffer.readFloat();
            float ownNoteWidthScale = buffer.readFloat();
            float ownNoteHeightScale = buffer.readFloat();
            float opponentPanelOpacity = buffer.readFloat();
            float opponentLaneOpacity = buffer.readFloat();
            float opponentPanelWidthScale = buffer.readFloat();
            float opponentPanelHeightScale = buffer.readFloat();
            float opponentNoteWidthScale = buffer.readFloat();
            float opponentNoteHeightScale = buffer.readFloat();
            float leftSpeed = buffer.readFloat();
            float rightSpeed = buffer.readFloat();
            List<String> ownKeyLabels = readKeyLabels(buffer);
            List<String> opponentKeyLabels = readKeyLabels(buffer);
            boolean leftSide = buffer.readBoolean();
            int leftEntityId = buffer.readVarInt();
            int rightEntityId = buffer.readVarInt();
            int opponentEntityId = buffer.readVarInt();
            int cameraEntityId = buffer.readVarInt();
            float cameraYaw = buffer.readFloat();
            float cameraPitch = buffer.readFloat();
            float cameraFov = buffer.readFloat();
            int count = Math.min(buffer.readVarInt(), MAX_NOTES);
            List<BattleNote> notes = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                notes.add(BattleNote.read(buffer));
            }
            return new BattleStartPayload(
                    battleId,
                    durationTicks,
                    startDelayMillis,
                    bpm,
                    trackId,
                    title,
                    description,
                    volume,
                    speedMultiplier,
                    audioFile,
                    previewFile,
                    leftName,
                    rightName,
                    ownSpeed,
                    opponentSpeed,
                    ownColor,
                    opponentColor,
                    ownPanelOpacity,
                    ownLaneOpacity,
                    ownPanelWidthScale,
                    ownPanelHeightScale,
                    ownNoteWidthScale,
                    ownNoteHeightScale,
                    opponentPanelOpacity,
                    opponentLaneOpacity,
                    opponentPanelWidthScale,
                    opponentPanelHeightScale,
                    opponentNoteWidthScale,
                    opponentNoteHeightScale,
                    leftSpeed,
                    rightSpeed,
                    ownKeyLabels,
                    opponentKeyLabels,
                    leftSide,
                    leftEntityId,
                    rightEntityId,
                    opponentEntityId,
                    cameraEntityId,
                    cameraYaw,
                    cameraPitch,
                    cameraFov,
                    List.copyOf(notes),
                    NoteAppearanceCodec.read(buffer),
                    NoteAppearanceCodec.read(buffer)
            );
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buffer, BattleStartPayload payload) {
            buffer.writeUUID(payload.battleId());
            buffer.writeVarInt(payload.durationTicks());
            buffer.writeVarInt(payload.startDelayMillis());
            buffer.writeVarInt(payload.bpm());
            buffer.writeUtf(payload.trackId(), 255);
            buffer.writeUtf(payload.title(), 128);
            buffer.writeUtf(payload.description(), 1024);
            buffer.writeFloat(payload.volume());
            buffer.writeFloat(payload.speedMultiplier());
            buffer.writeUtf(payload.audioFile(), 255);
            buffer.writeUtf(payload.previewFile(), 255);
            buffer.writeUtf(payload.leftName(), 128);
            buffer.writeUtf(payload.rightName(), 128);
            buffer.writeFloat(payload.ownSpeed());
            buffer.writeFloat(payload.opponentSpeed());
            buffer.writeInt(payload.ownColor());
            buffer.writeInt(payload.opponentColor());
            buffer.writeFloat(payload.ownPanelOpacity());
            buffer.writeFloat(payload.ownLaneOpacity());
            buffer.writeFloat(payload.ownPanelWidthScale());
            buffer.writeFloat(payload.ownPanelHeightScale());
            buffer.writeFloat(payload.ownNoteWidthScale());
            buffer.writeFloat(payload.ownNoteHeightScale());
            buffer.writeFloat(payload.opponentPanelOpacity());
            buffer.writeFloat(payload.opponentLaneOpacity());
            buffer.writeFloat(payload.opponentPanelWidthScale());
            buffer.writeFloat(payload.opponentPanelHeightScale());
            buffer.writeFloat(payload.opponentNoteWidthScale());
            buffer.writeFloat(payload.opponentNoteHeightScale());
            buffer.writeFloat(payload.leftSpeed());
            buffer.writeFloat(payload.rightSpeed());
            writeKeyLabels(buffer, payload.ownKeyLabels());
            writeKeyLabels(buffer, payload.opponentKeyLabels());
            buffer.writeBoolean(payload.leftSide());
            buffer.writeVarInt(payload.leftEntityId());
            buffer.writeVarInt(payload.rightEntityId());
            buffer.writeVarInt(payload.opponentEntityId());
            buffer.writeVarInt(payload.cameraEntityId());
            buffer.writeFloat(payload.cameraYaw());
            buffer.writeFloat(payload.cameraPitch());
            buffer.writeFloat(payload.cameraFov());
            int count = Math.min(payload.notes().size(), MAX_NOTES);
            buffer.writeVarInt(count);
            for (int i = 0; i < count; i++) {
                payload.notes().get(i).write(buffer);
            }
            NoteAppearanceCodec.write(buffer, payload.ownAppearance());
            NoteAppearanceCodec.write(buffer, payload.opponentAppearance());
        }
    };

    private static List<String> readKeyLabels(RegistryFriendlyByteBuf buffer) {
        List<String> labels = new ArrayList<>(LANE_COUNT);
        for (int i = 0; i < LANE_COUNT; i++) {
            labels.add(buffer.readUtf(32));
        }
        return List.copyOf(labels);
    }

    private static void writeKeyLabels(RegistryFriendlyByteBuf buffer, List<String> labels) {
        for (int i = 0; i < LANE_COUNT; i++) {
            String label = i < labels.size() ? labels.get(i) : "";
            buffer.writeUtf(label, 32);
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

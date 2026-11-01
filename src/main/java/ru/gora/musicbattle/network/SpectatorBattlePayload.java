package ru.gora.musicbattle.network;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import ru.gora.musicbattle.MusicBattleMod;
import ru.gora.musicbattle.data.BattleNote;
import ru.gora.musicbattle.data.ChartBeat;
import ru.gora.musicbattle.data.NoteAppearance;
import ru.gora.musicbattle.data.TrackEffect;

public record SpectatorBattlePayload(
        UUID battleId,
        int durationTicks,
        int startTick,
        int bpm,
        float speedMultiplier,
        int leftEntityId,
        int rightEntityId,
        int cameraEntityId,
        float cameraYaw,
        float cameraPitch,
        float cameraFov,
        int leftColor,
        int rightColor,
        float leftPanelOpacity,
        float leftLaneOpacity,
        float leftPanelWidthScale,
        float leftPanelHeightScale,
        float leftNoteWidthScale,
        float leftNoteHeightScale,
        float rightPanelOpacity,
        float rightLaneOpacity,
        float rightPanelWidthScale,
        float rightPanelHeightScale,
        float rightNoteWidthScale,
        float rightNoteHeightScale,
        float leftSpeed,
        float rightSpeed,
        int leftScore,
        int rightScore,
        int leftCombo,
        int rightCombo,
        int leftHeldMask,
        int rightHeldMask,
        List<BattleNote> notes,
        List<TrackEffect> effects,
        NoteAppearance leftAppearance,
        NoteAppearance rightAppearance,
        /**
         * The notes settled by {@code startTick} that a panel cannot tell from the chart alone: those that ended less
         * than the hit window before it (BattleManager.Battle#spectatorJoin). Older ones it takes as over itself.
         */
        List<Integer> settledNotes,
        /** The holds being held on {@code startTick}. */
        List<Integer> startedNotes,
        /**
         * A mob's actions from the ticks it has played ahead of {@code startTick}, in the order it made them, each for
         * the panel to hold until its tick, as every panel already watching does.
         */
        List<BattleActionPayload> leadActions,
        /**
         * Where beat 1 falls on this playback's clock, in ms ({@code ChartBeat}), already divided by a sped-up battle's
         * speed: the battle's {@code effectiveOffsetMs}, or the DJ song's offset at the panel's speed. 0 for a chart
         * without one. The watcher's beat visuals count their beats from it, as the fighters' do.
         */
        int beatOffsetMs
) implements CustomPacketPayload {
    /** A panel without the battle's notes (a DJ session), or a refresh of one: nothing settled, held or ahead. */
    public SpectatorBattlePayload(UUID battleId, int durationTicks, int startTick, int bpm, float speedMultiplier,
            int leftEntityId, int rightEntityId, int cameraEntityId, float cameraYaw, float cameraPitch, float cameraFov,
            int leftColor, int rightColor, float leftPanelOpacity, float leftLaneOpacity, float leftPanelWidthScale,
            float leftPanelHeightScale, float leftNoteWidthScale, float leftNoteHeightScale, float rightPanelOpacity,
            float rightLaneOpacity, float rightPanelWidthScale, float rightPanelHeightScale, float rightNoteWidthScale,
            float rightNoteHeightScale, float leftSpeed, float rightSpeed, int leftScore, int rightScore,
            int leftCombo, int rightCombo, int leftHeldMask, int rightHeldMask, List<BattleNote> notes,
            List<TrackEffect> effects, NoteAppearance leftAppearance, NoteAppearance rightAppearance, int beatOffsetMs) {
        this(battleId, durationTicks, startTick, bpm, speedMultiplier, leftEntityId, rightEntityId, cameraEntityId,
                cameraYaw, cameraPitch, cameraFov, leftColor, rightColor, leftPanelOpacity, leftLaneOpacity,
                leftPanelWidthScale, leftPanelHeightScale, leftNoteWidthScale, leftNoteHeightScale, rightPanelOpacity,
                rightLaneOpacity, rightPanelWidthScale, rightPanelHeightScale, rightNoteWidthScale, rightNoteHeightScale,
                leftSpeed, rightSpeed, leftScore, rightScore, leftCombo, rightCombo, leftHeldMask, rightHeldMask, notes,
                effects, leftAppearance, rightAppearance, List.of(), List.of(), List.of(), beatOffsetMs);
    }

    public SpectatorBattlePayload(UUID battleId, int durationTicks, int startTick, int bpm, float speedMultiplier,
            int leftEntityId, int rightEntityId, int cameraEntityId, float cameraYaw, float cameraPitch, float cameraFov,
            int leftColor, int rightColor, float leftPanelOpacity, float leftLaneOpacity, float leftPanelWidthScale,
            float leftPanelHeightScale, float leftNoteWidthScale, float leftNoteHeightScale, float rightPanelOpacity,
            float rightLaneOpacity, float rightPanelWidthScale, float rightPanelHeightScale, float rightNoteWidthScale,
            float rightNoteHeightScale, float leftSpeed, float rightSpeed, int leftScore, int rightScore,
            int leftCombo, int rightCombo, int leftHeldMask, int rightHeldMask, List<BattleNote> notes, List<TrackEffect> effects,
            int beatOffsetMs) {
        this(battleId, durationTicks, startTick, bpm, speedMultiplier, leftEntityId, rightEntityId, cameraEntityId,
                cameraYaw, cameraPitch, cameraFov, leftColor, rightColor, leftPanelOpacity, leftLaneOpacity,
                leftPanelWidthScale, leftPanelHeightScale, leftNoteWidthScale, leftNoteHeightScale, rightPanelOpacity,
                rightLaneOpacity, rightPanelWidthScale, rightPanelHeightScale, rightNoteWidthScale, rightNoteHeightScale,
                leftSpeed, rightSpeed, leftScore, rightScore, leftCombo, rightCombo, leftHeldMask, rightHeldMask, notes,
                effects, NoteAppearance.solid(leftColor), NoteAppearance.solid(rightColor), beatOffsetMs);
    }
    public static final Type<SpectatorBattlePayload> TYPE = new Type<>(MusicBattleMod.id("spectator_battle"));
    /**
     * A shared-notes battle sends every note of the map once for each side, so a map at the server's 4096-note cap
     * arrives here as twice that. At 4096 a spectator silently lost the second half of a long shared chart.
     */
    private static final int MAX_NOTES = 8192;
    /** A mob plays three ticks ahead (BattleManager.AI_LEAD_TICKS): a handful of actions, two mobs at most. */
    private static final int MAX_LEAD_ACTIONS = 256;

    public static final StreamCodec<RegistryFriendlyByteBuf, SpectatorBattlePayload> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public SpectatorBattlePayload decode(RegistryFriendlyByteBuf buffer) {
            UUID battleId = buffer.readUUID();
            int durationTicks = buffer.readVarInt();
            int startTick = buffer.readVarInt();
            int bpm = buffer.readVarInt();
            float speedMultiplier = buffer.readFloat();
            int leftEntityId = buffer.readVarInt();
            int rightEntityId = buffer.readVarInt();
            int cameraEntityId = buffer.readVarInt();
            float cameraYaw = buffer.readFloat();
            float cameraPitch = buffer.readFloat();
            float cameraFov = buffer.readFloat();
            int leftColor = buffer.readInt();
            int rightColor = buffer.readInt();
            float leftPanelOpacity = buffer.readFloat();
            float leftLaneOpacity = buffer.readFloat();
            float leftPanelWidthScale = buffer.readFloat();
            float leftPanelHeightScale = buffer.readFloat();
            float leftNoteWidthScale = buffer.readFloat();
            float leftNoteHeightScale = buffer.readFloat();
            float rightPanelOpacity = buffer.readFloat();
            float rightLaneOpacity = buffer.readFloat();
            float rightPanelWidthScale = buffer.readFloat();
            float rightPanelHeightScale = buffer.readFloat();
            float rightNoteWidthScale = buffer.readFloat();
            float rightNoteHeightScale = buffer.readFloat();
            float leftSpeed = buffer.readFloat();
            float rightSpeed = buffer.readFloat();
            int leftScore = Math.max(0, buffer.readVarInt());
            int rightScore = Math.max(0, buffer.readVarInt());
            int leftCombo = Math.max(0, buffer.readVarInt());
            int rightCombo = Math.max(0, buffer.readVarInt());
            int leftHeldMask = buffer.readVarInt() & 15;
            int rightHeldMask = buffer.readVarInt() & 15;
            int count = Math.min(buffer.readVarInt(), MAX_NOTES);
            List<BattleNote> notes = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                notes.add(BattleNote.read(buffer));
            }
            int effectCount = Math.min(buffer.readVarInt(), TrackEffect.MAX_EFFECTS);
            List<TrackEffect> effects = new ArrayList<>(effectCount);
            for (int i = 0; i < effectCount; i++) {
                effects.add(TrackEffect.read(buffer));
            }
            return new SpectatorBattlePayload(battleId, durationTicks, startTick, bpm, speedMultiplier,
                    leftEntityId, rightEntityId, cameraEntityId, cameraYaw, cameraPitch, cameraFov,
                    leftColor, rightColor, leftPanelOpacity, leftLaneOpacity, leftPanelWidthScale,
                    leftPanelHeightScale, leftNoteWidthScale, leftNoteHeightScale, rightPanelOpacity,
                    rightLaneOpacity, rightPanelWidthScale, rightPanelHeightScale, rightNoteWidthScale,
                    rightNoteHeightScale, leftSpeed, rightSpeed, leftScore, rightScore, leftCombo, rightCombo,
                    leftHeldMask, rightHeldMask,
                    List.copyOf(notes), List.copyOf(effects), NoteAppearanceCodec.read(buffer), NoteAppearanceCodec.read(buffer),
                    readIndices(buffer), readIndices(buffer), readLeadActions(buffer),
                    ChartBeat.clampOffsetMs(buffer.readVarInt()));
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buffer, SpectatorBattlePayload payload) {
            buffer.writeUUID(payload.battleId());
            buffer.writeVarInt(payload.durationTicks());
            buffer.writeVarInt(payload.startTick());
            buffer.writeVarInt(payload.bpm());
            buffer.writeFloat(payload.speedMultiplier());
            buffer.writeVarInt(payload.leftEntityId());
            buffer.writeVarInt(payload.rightEntityId());
            buffer.writeVarInt(payload.cameraEntityId());
            buffer.writeFloat(payload.cameraYaw());
            buffer.writeFloat(payload.cameraPitch());
            buffer.writeFloat(payload.cameraFov());
            buffer.writeInt(payload.leftColor());
            buffer.writeInt(payload.rightColor());
            buffer.writeFloat(payload.leftPanelOpacity());
            buffer.writeFloat(payload.leftLaneOpacity());
            buffer.writeFloat(payload.leftPanelWidthScale());
            buffer.writeFloat(payload.leftPanelHeightScale());
            buffer.writeFloat(payload.leftNoteWidthScale());
            buffer.writeFloat(payload.leftNoteHeightScale());
            buffer.writeFloat(payload.rightPanelOpacity());
            buffer.writeFloat(payload.rightLaneOpacity());
            buffer.writeFloat(payload.rightPanelWidthScale());
            buffer.writeFloat(payload.rightPanelHeightScale());
            buffer.writeFloat(payload.rightNoteWidthScale());
            buffer.writeFloat(payload.rightNoteHeightScale());
            buffer.writeFloat(payload.leftSpeed());
            buffer.writeFloat(payload.rightSpeed());
            buffer.writeVarInt(Math.max(0, payload.leftScore()));
            buffer.writeVarInt(Math.max(0, payload.rightScore()));
            buffer.writeVarInt(Math.max(0, payload.leftCombo()));
            buffer.writeVarInt(Math.max(0, payload.rightCombo()));
            buffer.writeVarInt(payload.leftHeldMask() & 15);
            buffer.writeVarInt(payload.rightHeldMask() & 15);
            int count = Math.min(payload.notes().size(), MAX_NOTES);
            buffer.writeVarInt(count);
            for (int i = 0; i < count; i++) {
                payload.notes().get(i).write(buffer);
            }
            int effectCount = Math.min(payload.effects().size(), TrackEffect.MAX_EFFECTS);
            buffer.writeVarInt(effectCount);
            for (int i = 0; i < effectCount; i++) {
                payload.effects().get(i).write(buffer);
            }
            NoteAppearanceCodec.write(buffer, payload.leftAppearance());
            NoteAppearanceCodec.write(buffer, payload.rightAppearance());
            writeIndices(buffer, payload.settledNotes());
            writeIndices(buffer, payload.startedNotes());
            int leadCount = Math.min(payload.leadActions().size(), MAX_LEAD_ACTIONS);
            buffer.writeVarInt(leadCount);
            for (int i = 0; i < leadCount; i++) {
                BattleActionPayload.STREAM_CODEC.encode(buffer, payload.leadActions().get(i));
            }
            buffer.writeVarInt(ChartBeat.clampOffsetMs(payload.beatOffsetMs()));
        }
    };

    private static List<Integer> readIndices(RegistryFriendlyByteBuf buffer) {
        int count = Math.min(buffer.readVarInt(), MAX_NOTES);
        List<Integer> indices = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            indices.add(buffer.readVarInt());
        }
        return List.copyOf(indices);
    }

    private static void writeIndices(RegistryFriendlyByteBuf buffer, List<Integer> indices) {
        int count = Math.min(indices.size(), MAX_NOTES);
        buffer.writeVarInt(count);
        for (int i = 0; i < count; i++) {
            buffer.writeVarInt(indices.get(i));
        }
    }

    private static List<BattleActionPayload> readLeadActions(RegistryFriendlyByteBuf buffer) {
        int count = Math.min(buffer.readVarInt(), MAX_LEAD_ACTIONS);
        List<BattleActionPayload> actions = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            actions.add(BattleActionPayload.STREAM_CODEC.decode(buffer));
        }
        return List.copyOf(actions);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

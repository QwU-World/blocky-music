package ru.gora.musicbattle.item;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;

/** Compact persistent state for the wire's panel mode or mixed pending endpoint selection. */
final class SpeakerWireSelection {
    static final int MAX_PENDING_SPEAKERS = 64;
    static final int MAX_PENDING_CAMERAS = 16;

    private static final String LEGACY_KIND = "DjWireKind";
    private static final String LEGACY_POS = "DjWirePos";
    private static final String LEGACY_DIMENSION = "DjWireDimension";
    private static final String SELECTION_DIMENSION = "DjWireSelectionDimension";
    private static final String SELECTED_SPEAKERS = "DjWireSelectedSpeakers";
    private static final String SELECTED_CAMERAS = "DjWireSelectedCameras";

    private SpeakerWireSelection() {
    }

    static Selection empty() {
        return new Selection("", null, List.of(), List.of());
    }

    static boolean shouldConsumeIgnoredTarget(boolean sneaking, Selection selection) {
        return sneaking && selection != null && !selection.isEmpty();
    }

    static Selection load(CompoundTag tag) {
        if (tag == null) {
            return empty();
        }
        String legacyKind = tag.getString(LEGACY_KIND);
        String dimension = tag.contains(SELECTION_DIMENSION)
                ? tag.getString(SELECTION_DIMENSION) : tag.getString(LEGACY_DIMENSION);
        if ("panel".equals(legacyKind)) {
            return new Selection(dimension, BlockPos.of(tag.getLong(LEGACY_POS)), List.of(), List.of());
        }

        List<BlockPos> speakers = positions(tag.getLongArray(SELECTED_SPEAKERS), MAX_PENDING_SPEAKERS);
        List<BlockPos> cameras = positions(tag.getLongArray(SELECTED_CAMERAS), MAX_PENDING_CAMERAS);
        if ("speaker".equals(legacyKind) && speakers.size() < MAX_PENDING_SPEAKERS) {
            speakers = appendUnique(speakers, BlockPos.of(tag.getLong(LEGACY_POS)), MAX_PENDING_SPEAKERS);
        } else if ("camera".equals(legacyKind) && cameras.size() < MAX_PENDING_CAMERAS) {
            cameras = appendUnique(cameras, BlockPos.of(tag.getLong(LEGACY_POS)), MAX_PENDING_CAMERAS);
        }
        return new Selection(dimension, null, speakers, cameras);
    }

    static void save(CompoundTag tag, Selection selection) {
        tag.remove(LEGACY_KIND);
        tag.remove(LEGACY_POS);
        tag.remove(LEGACY_DIMENSION);
        tag.remove(SELECTION_DIMENSION);
        tag.remove(SELECTED_SPEAKERS);
        tag.remove(SELECTED_CAMERAS);
        if (selection == null || selection.isEmpty()) {
            return;
        }
        if (selection.hasPanel()) {
            tag.putString(LEGACY_KIND, "panel");
            tag.putLong(LEGACY_POS, selection.panel().asLong());
            tag.putString(LEGACY_DIMENSION, selection.dimension());
            return;
        }
        tag.putString(SELECTION_DIMENSION, selection.dimension());
        tag.putLongArray(SELECTED_SPEAKERS,
                selection.speakers().stream().mapToLong(BlockPos::asLong).toArray());
        tag.putLongArray(SELECTED_CAMERAS,
                selection.cameras().stream().mapToLong(BlockPos::asLong).toArray());
    }

    private static List<BlockPos> positions(long[] packed, int limit) {
        LinkedHashSet<BlockPos> unique = new LinkedHashSet<>();
        for (long value : packed) {
            if (unique.size() >= limit) break;
            unique.add(BlockPos.of(value).immutable());
        }
        return List.copyOf(unique);
    }

    private static List<BlockPos> appendUnique(List<BlockPos> values, BlockPos position, int limit) {
        LinkedHashSet<BlockPos> unique = new LinkedHashSet<>(values);
        if (unique.size() < limit) unique.add(position.immutable());
        return List.copyOf(unique);
    }

    enum Kind { SPEAKER, CAMERA }
    enum Change { ADDED, REMOVED, DIMENSION_RESET_ADDED, LIMIT }
    record Toggle(Selection selection, Change change) {}

    record Selection(String dimension, BlockPos panel, List<BlockPos> speakers, List<BlockPos> cameras) {
        Selection {
            dimension = dimension == null ? "" : dimension;
            panel = panel == null ? null : panel.immutable();
            speakers = sanitize(speakers, MAX_PENDING_SPEAKERS);
            cameras = sanitize(cameras, MAX_PENDING_CAMERAS);
            if (panel != null) {
                speakers = List.of();
                cameras = List.of();
            } else if (speakers.isEmpty() && cameras.isEmpty()) {
                dimension = "";
            }
        }

        boolean hasPanel() { return panel != null; }
        boolean hasRemotes() { return !speakers.isEmpty() || !cameras.isEmpty(); }
        boolean isEmpty() { return !hasPanel() && !hasRemotes(); }
        int speakerCount() { return speakers.size(); }
        int cameraCount() { return cameras.size(); }

        Selection withPanel(String targetDimension, BlockPos targetPanel) {
            return new Selection(targetDimension, targetPanel, List.of(), List.of());
        }

        Selection withoutRemote(Kind kind, BlockPos position) {
            if (hasPanel() || position == null) return this;
            List<BlockPos> nextSpeakers = new ArrayList<>(speakers);
            List<BlockPos> nextCameras = new ArrayList<>(cameras);
            (kind == Kind.SPEAKER ? nextSpeakers : nextCameras).remove(position);
            return new Selection(dimension, null, nextSpeakers, nextCameras);
        }

        Toggle toggleRemote(Kind kind, String targetDimension, BlockPos position) {
            boolean resetDimension = !dimension.isBlank() && !dimension.equals(targetDimension);
            List<BlockPos> nextSpeakers = resetDimension || hasPanel()
                    ? new ArrayList<>() : new ArrayList<>(speakers);
            List<BlockPos> nextCameras = resetDimension || hasPanel()
                    ? new ArrayList<>() : new ArrayList<>(cameras);
            List<BlockPos> target = kind == Kind.SPEAKER ? nextSpeakers : nextCameras;
            if (target.remove(position)) {
                return new Toggle(new Selection(targetDimension, null, nextSpeakers, nextCameras), Change.REMOVED);
            }
            int limit = kind == Kind.SPEAKER ? MAX_PENDING_SPEAKERS : MAX_PENDING_CAMERAS;
            if (target.size() >= limit) {
                return new Toggle(this, Change.LIMIT);
            }
            target.add(position.immutable());
            return new Toggle(new Selection(targetDimension, null, nextSpeakers, nextCameras),
                    resetDimension ? Change.DIMENSION_RESET_ADDED : Change.ADDED);
        }

        private static List<BlockPos> sanitize(List<BlockPos> positions, int limit) {
            if (positions == null || positions.isEmpty()) return List.of();
            LinkedHashSet<BlockPos> unique = new LinkedHashSet<>();
            for (BlockPos position : positions) {
                if (position == null || unique.size() >= limit) continue;
                unique.add(position.immutable());
            }
            return List.copyOf(unique);
        }
    }
}

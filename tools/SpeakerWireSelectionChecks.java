package ru.gora.musicbattle.item;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;

/** Verifies the persistent multi-selection carried by the wire item. */
public final class SpeakerWireSelectionChecks {
    private static int checks;

    public static void main(String[] args) {
        toggleMixedSelection();
        resetAcrossDimensions();
        enforceLimits();
        roundTripAndPanelMode();
        migrateLegacyEndpoint();
        protectAirClearFromBlockFallback();
        System.out.println("Speaker wire selection checks PASS: " + checks);
    }

    private static void toggleMixedSelection() {
        SpeakerWireSelection.Selection state = SpeakerWireSelection.empty();
        var first = state.toggleRemote(SpeakerWireSelection.Kind.SPEAKER, "minecraft:overworld", new BlockPos(1, 2, 3));
        check(first.change() == SpeakerWireSelection.Change.ADDED, "first speaker is added");
        var second = first.selection().toggleRemote(SpeakerWireSelection.Kind.CAMERA, "minecraft:overworld", new BlockPos(4, 5, 6));
        check(second.selection().speakerCount() == 1 && second.selection().cameraCount() == 1,
                "speakers and cameras share one selection");
        var removed = second.selection().toggleRemote(SpeakerWireSelection.Kind.SPEAKER,
                "minecraft:overworld", new BlockPos(1, 2, 3));
        check(removed.change() == SpeakerWireSelection.Change.REMOVED, "second click toggles a remote off");
        check(removed.selection().speakerCount() == 0 && removed.selection().cameraCount() == 1,
                "toggle removes only the clicked endpoint");
    }

    private static void resetAcrossDimensions() {
        SpeakerWireSelection.Selection state = SpeakerWireSelection.empty()
                .toggleRemote(SpeakerWireSelection.Kind.SPEAKER, "minecraft:overworld", BlockPos.ZERO).selection();
        var changed = state.toggleRemote(SpeakerWireSelection.Kind.CAMERA, "minecraft:the_nether", BlockPos.ZERO);
        check(changed.change() == SpeakerWireSelection.Change.DIMENSION_RESET_ADDED,
                "entering another dimension starts a new selection");
        check(changed.selection().speakerCount() == 0 && changed.selection().cameraCount() == 1,
                "old-dimension endpoints are discarded");
        check("minecraft:the_nether".equals(changed.selection().dimension()), "new dimension is stored");
    }

    private static void enforceLimits() {
        SpeakerWireSelection.Selection state = SpeakerWireSelection.empty();
        for (int index = 0; index < SpeakerWireSelection.MAX_PENDING_SPEAKERS; index++) {
            var result = state.toggleRemote(SpeakerWireSelection.Kind.SPEAKER,
                    "minecraft:overworld", new BlockPos(index, 0, 0));
            check(result.change() == SpeakerWireSelection.Change.ADDED, "speaker below limit is accepted");
            state = result.selection();
        }
        var overflow = state.toggleRemote(SpeakerWireSelection.Kind.SPEAKER,
                "minecraft:overworld", new BlockPos(999, 0, 0));
        check(overflow.change() == SpeakerWireSelection.Change.LIMIT, "speaker selection is bounded");
        check(overflow.selection().speakerCount() == SpeakerWireSelection.MAX_PENDING_SPEAKERS,
                "limit does not mutate selection");
    }

    private static void roundTripAndPanelMode() {
        SpeakerWireSelection.Selection selected = SpeakerWireSelection.empty()
                .toggleRemote(SpeakerWireSelection.Kind.SPEAKER, "minecraft:overworld", new BlockPos(2, 3, 4)).selection()
                .toggleRemote(SpeakerWireSelection.Kind.CAMERA, "minecraft:overworld", new BlockPos(5, 6, 7)).selection();
        CompoundTag tag = new CompoundTag();
        SpeakerWireSelection.save(tag, selected);
        SpeakerWireSelection.Selection loaded = SpeakerWireSelection.load(tag);
        check(loaded.equals(selected), "mixed selection survives item data round-trip");

        SpeakerWireSelection.Selection panel = selected.withPanel("minecraft:overworld", new BlockPos(8, 9, 10));
        check(panel.hasPanel() && !panel.hasRemotes(), "panel mode replaces pending endpoints");
        SpeakerWireSelection.save(tag, panel);
        check(SpeakerWireSelection.load(tag).equals(panel), "panel mode survives item data round-trip");
    }

    private static void migrateLegacyEndpoint() {
        CompoundTag legacy = new CompoundTag();
        legacy.putString("DjWireKind", "camera");
        legacy.putLong("DjWirePos", new BlockPos(11, 12, 13).asLong());
        legacy.putString("DjWireDimension", "minecraft:overworld");
        SpeakerWireSelection.Selection loaded = SpeakerWireSelection.load(legacy);
        check(!loaded.hasPanel() && loaded.cameraCount() == 1, "old single camera becomes a pending selection");
        SpeakerWireSelection.save(legacy, loaded);
        check(legacy.getString("DjWireKind").isBlank(), "legacy remote marker is removed after save");
        check(SpeakerWireSelection.load(legacy).equals(loaded), "migrated selection remains readable");
    }

    private static void protectAirClearFromBlockFallback() {
        SpeakerWireSelection.Selection selected = SpeakerWireSelection.empty()
                .toggleRemote(SpeakerWireSelection.Kind.SPEAKER,
                        "minecraft:overworld", BlockPos.ZERO).selection();
        check(SpeakerWireSelection.shouldConsumeIgnoredTarget(true, selected),
                "sneaking on an unrelated block consumes the block path and preserves selection");
        check(!SpeakerWireSelection.shouldConsumeIgnoredTarget(false, selected),
                "ordinary unrelated block use keeps the normal interaction pipeline");
        check(!SpeakerWireSelection.shouldConsumeIgnoredTarget(true, SpeakerWireSelection.empty()),
                "empty wire does not consume unrelated block use");
    }

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
}

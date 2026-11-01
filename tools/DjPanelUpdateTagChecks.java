import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.network.FriendlyByteBuf;
import ru.gora.musicbattle.block.entity.DjPanelBlockEntity;
import ru.gora.musicbattle.data.DjPlaylistEntry;
import ru.gora.musicbattle.data.DjVisualEffectConfig;
import ru.gora.musicbattle.data.TrackEffect;
import ru.gora.musicbattle.data.TrackEffectSettings;

/**
 * Builds the largest DJ panel the limits allow and measures its update tag the way a client measures it.
 *
 * The chunk packet reads every block entity tag through FriendlyByteBuf.readNbt, which gives it an NbtAccounter of
 * 2 MiB and disconnects the player when the tag counts higher. A panel whose saved playlist went past that used to
 * be unenterable, because the update tag was the saved state. The check fills 64 rows with 512-effect charts, every
 * setting set away from its default, and asserts both halves: the saved tag really is far over the limit (so the
 * case is a real one), and the update tag sent to clients stays far below it while still carrying the playback
 * clock the renderer draws from.
 */
public final class DjPanelUpdateTagChecks {
    private static final long CHUNK_TAG_LIMIT = 2_097_152L;
    /** A sixty-fourth of the limit: generous for a handful of fields, and nowhere near a kick. */
    private static final long UPDATE_TAG_BUDGET = CHUNK_TAG_LIMIT / 64L;
    private static int failures;

    public static void main(String[] args) throws Exception {
        DjPanelBlockEntity panel = blankPanel();
        String longTrackId = "t".repeat(DjPlaylistEntry.MAX_TRACK_ID_LENGTH);
        List<TrackEffect> chart = worstCaseChart();
        List<DjVisualEffectConfig> rack = new ArrayList<>();
        for (int index = 0; index < DjVisualEffectConfig.MAX_CONFIGS; index++) {
            rack.add(new DjVisualEffectConfig("preset_" + index + "_" + "p".repeat(48), true, chart.get(index)));
        }
        panel.setVisualEffects(true, true, rack, 0);
        for (int index = 0; index < DjPanelBlockEntity.MAX_LINKED_SPEAKERS; index++) {
            panel.linkSpeaker(new BlockPos(30_000_000 - index, 319, -30_000_000 + index));
        }
        for (int index = 0; index < DjPanelBlockEntity.MAX_LINKED_CAMERAS; index++) {
            panel.linkCamera(new BlockPos(-30_000_000 + index, -64, 30_000_000 - index));
        }
        boolean everyIdTaken = true;
        for (int index = 0; index < DjPanelBlockEntity.MAX_LIBRARY_TRACKS; index++) {
            String id = String.format("%03d", index) + "l".repeat(DjPlaylistEntry.MAX_TRACK_ID_LENGTH - 3);
            everyIdTaken &= panel.addToLibrary(id);
        }
        check(everyIdTaken, "the booth's list takes its full " + DjPanelBlockEntity.MAX_LIBRARY_TRACKS
                + " maps of the longest id");
        check(!panel.addToLibrary("one_more"), "a full list refuses one more map");
        boolean everyRowFull = true;
        for (int row = 0; row < DjPlaylistEntry.MAX_ENTRIES; row++) {
            DjPlaylistEntry added = panel.addPlaylistEntry(longTrackId, 1_000_000 + row);
            // A legacy DJ row carries the most data a row can: its own 512 effects, a mixer, and a 512-effect chart.
            everyRowFull &= added != null && panel.replacePlaylistEntry(new DjPlaylistEntry(added.entryId(),
                    added.trackId(), added.startSourceTicks(), DjPlaylistEntry.FxMode.DJ, chart, null, chart, true));
        }
        check(everyRowFull, "every row takes a full 512-effect copy plus a 512-effect chart");
        check(panel.getPlaylist().size() == DjPlaylistEntry.MAX_ENTRIES, "worst-case panel holds every row");
        panel.startPlayback(longTrackId, 9_000_000L, 1_234, panel.getPlaylist().get(5).entryId());

        CompoundTag saved = saveAdditional(panel);
        long savedUsage = accountedUsage(saved);
        System.out.println("saved tag: " + savedUsage + " accounted bytes");
        check(savedUsage > CHUNK_TAG_LIMIT * 4, "the saved worst case is far over the chunk limit, so this is a real case");
        check(rejectedByChunkReader(saved), "the chunk reader rejects the saved state, as the old update tag did");

        CompoundTag update = panel.getUpdateTag(null);
        long updateUsage = accountedUsage(update);
        System.out.println("update tag: " + updateUsage + " accounted bytes of " + CHUNK_TAG_LIMIT);
        check(updateUsage < UPDATE_TAG_BUDGET, "the update tag stays far below the 2 MiB chunk limit");
        check(!rejectedByChunkReader(update), "the chunk reader accepts the worst-case update tag");
        for (String heavy : List.of("DjPlaylist", "DjVisualEffects", "LinkedSpeakers", "LinkedCameras", "DjLibrary")) {
            check(!update.contains(heavy), "the update tag leaves out " + heavy);
        }

        DjPanelBlockEntity client = blankPanel();
        loadAdditional(client, roundTrip(update));
        sameClock(panel, client, "playing");
        check(client.getLibrary().isEmpty(), "the client's copy of a booth carries no list: the panel payload brings it");
        long later = 9_000_000L + 4_321L;
        panel.pausePlayback(later, Integer.MAX_VALUE);
        loadAdditional(client, roundTrip(panel.getUpdateTag(null)));
        sameClock(panel, client, "paused");
        panel.resumePlayback(longTrackId, later + 100L);
        loadAdditional(client, roundTrip(panel.getUpdateTag(null)));
        sameClock(panel, client, "resumed");
        panel.stopPlayback();
        loadAdditional(client, roundTrip(panel.getUpdateTag(null)));
        sameClock(panel, client, "stopped");

        DjPanelBlockEntity reloaded = blankPanel();
        loadAdditional(reloaded, saved);
        check(reloaded.getPlaylist().equals(saveSnapshot(saved)), "saved playlist reloads unchanged");
        check(reloaded.getPlaylist().size() == DjPlaylistEntry.MAX_ENTRIES
                && reloaded.getPlaylist().getFirst().mixerChartEffects().size() == TrackEffect.MAX_EFFECTS,
                "disk keeps every row and every copied effect");
        check(reloaded.getLinkedSpeakers().size() == DjPanelBlockEntity.MAX_LINKED_SPEAKERS
                && reloaded.getDjVisualEffects().size() == DjVisualEffectConfig.MAX_CONFIGS,
                "disk keeps speakers and the rack's DJ effects");
        check(reloaded.getLibrary().equals(panel.getLibrary()), "disk keeps the booth's list, every map in its order");
        libraryChecks();
        resetChecks(chart);

        if (failures > 0) {
            System.out.println(failures + " DJ panel update tag check(s) failed");
            System.exit(1);
        }
        System.out.println("DJ panel update tag checks passed");
    }

    private static void sameClock(DjPanelBlockEntity server, DjPanelBlockEntity client, String state) {
        boolean same = server.isPlaying() == client.isPlaying()
                && server.isPlaybackPaused() == client.isPlaybackPaused()
                && Float.compare(server.getPlaybackSpeed(), client.getPlaybackSpeed()) == 0;
        for (long time : new long[] {0L, 9_000_000L, 9_004_321L, 9_010_000L}) {
            same &= server.getPlaybackPositionTicks(time) == client.getPlaybackPositionTicks(time);
        }
        check(same, "the client copy draws the same playhead as the server when " + state);
    }

    /** Every setting moved off its default, the overlay text as long as it may be, and a full outline palette. */
    private static List<TrackEffect> worstCaseChart() {
        JsonObject settings = new JsonObject();
        TrackEffectSettings.DEFAULT.writeJson(settings);
        JsonObject filled = new JsonObject();
        for (Map.Entry<String, JsonElement> entry : settings.entrySet()) {
            JsonElement value = entry.getValue();
            if (value instanceof JsonPrimitive primitive && primitive.isBoolean()) {
                filled.addProperty(entry.getKey(), !primitive.getAsBoolean());
            } else if (value instanceof JsonPrimitive primitive && primitive.isString()) {
                filled.addProperty(entry.getKey(), "\u20ac".repeat(4096));
            } else if (value instanceof JsonPrimitive primitive && primitive.isNumber()) {
                filled.addProperty(entry.getKey(), primitive.getAsDouble() * 0.987654321 + 0.123456789);
            } else {
                filled.add(entry.getKey(), value);
            }
        }
        JsonArray colors = new JsonArray();
        for (int index = 0; index < TrackEffectSettings.MAX_OBJECT_OUTLINE_COLORS; index++) {
            colors.add(0xFF000000 | (0x123456 * (index + 1)));
        }
        filled.add("objectOutlineColors", colors);
        filled.addProperty("objectOutlineColorSpeed", 3.14159265);
        TrackEffectSettings full = TrackEffectSettings.readJson(filled);
        TrackEffect.Type[] types = TrackEffect.Type.values();
        List<TrackEffect> chart = new ArrayList<>(TrackEffect.MAX_EFFECTS);
        for (int index = 0; index < TrackEffect.MAX_EFFECTS; index++) {
            chart.add(new TrackEffect(types[index % types.length], 0, 1_000_000 + index * 997, 123_456, 0.987654F)
                    .withSettings(full)
                    .withTimeline(index % TrackEffect.MAX_FX_ROWS, 1_000_000 + index * 997, 123_456)
                    .withLayer(TrackEffect.FX_LAYER_COUNT - 1));
        }
        return List.copyOf(chart);
    }

    /** Writes the tag as the network does and reads it back under an unlimited accounter, returning its count. */
    private static long accountedUsage(CompoundTag tag) {
        ByteBuf buffer = Unpooled.buffer();
        try {
            new FriendlyByteBuf(buffer).writeNbt(tag);
            NbtAccounter accounter = NbtAccounter.unlimitedHeap();
            FriendlyByteBuf.readNbt(buffer, accounter);
            return accounter.getUsage();
        } finally {
            buffer.release();
        }
    }

    /** The exact call ClientboundLevelChunkPacketData.BlockEntityInfo makes for each block entity. */
    private static boolean rejectedByChunkReader(CompoundTag tag) {
        ByteBuf buffer = Unpooled.buffer();
        try {
            new FriendlyByteBuf(buffer).writeNbt(tag);
            FriendlyByteBuf.readNbt(buffer);
            return false;
        } catch (RuntimeException tooBig) {
            return true;
        } finally {
            buffer.release();
        }
    }

    private static CompoundTag roundTrip(CompoundTag tag) {
        ByteBuf buffer = Unpooled.buffer();
        try {
            new FriendlyByteBuf(buffer).writeNbt(tag);
            return FriendlyByteBuf.readNbt(buffer);
        } finally {
            buffer.release();
        }
    }

    private static List<DjPlaylistEntry> saveSnapshot(CompoundTag saved) {
        List<DjPlaylistEntry> rows = new ArrayList<>();
        var list = saved.getList("DjPlaylist", net.minecraft.nbt.Tag.TAG_COMPOUND);
        for (int index = 0; index < list.size(); index++) {
            rows.add(DjPlaylistEntry.fromTag(list.getCompound(index)));
        }
        return rows;
    }

    /**
     * A panel without a level or registry, the state loadAdditional gives a newly placed block.
     *
     * The real constructor asks the block entity registry for its type, which only exists inside a running game, so
     * the object is allocated directly and given the collections its field initialisers would have created.
     */
    private static DjPanelBlockEntity blankPanel() throws Exception {
        Field unsafeField = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        Object unsafe = unsafeField.get(null);
        DjPanelBlockEntity panel = (DjPanelBlockEntity) unsafe.getClass()
                .getMethod("allocateInstance", Class.class).invoke(unsafe, DjPanelBlockEntity.class);
        field("linkedSpeakers").set(panel, new LinkedHashSet<BlockPos>());
        field("linkedCameras").set(panel, new LinkedHashSet<BlockPos>());
        field("playlist").set(panel, new ArrayList<DjPlaylistEntry>());
        field("library").set(panel, new ArrayList<String>());
        field("workshopSources").set(panel, new java.util.LinkedHashMap<String, ru.gora.musicbattle.data.DjWorkshopSource>());
        loadAdditional(panel, new CompoundTag());
        return panel;
    }

    /**
     * The booth's own list: maps join it once and in order, leave it without touching the queue, and a booth saved
     * before the list existed starts with the maps it was already using instead of an empty panel.
     */
    private static void libraryChecks() throws Exception {
        DjPanelBlockEntity panel = blankPanel();
        check(panel.addToLibrary("alpha") && panel.addToLibrary("beta") && !panel.addToLibrary("alpha"),
                "a map joins the list once");
        check(!panel.addToLibrary("") && !panel.addToLibrary(null)
                && !panel.addToLibrary("x".repeat(DjPlaylistEntry.MAX_TRACK_ID_LENGTH + 1)),
                "an id that could never name a track is refused");
        DjPlaylistEntry queued = panel.addPlaylistEntry("alpha", 0);
        check(panel.removeFromLibrary("alpha") && !panel.removeFromLibrary("alpha"), "a map leaves the list once");
        check(panel.getLibrary().equals(List.of("beta")), "the rest of the list keeps its order");
        check(panel.getPlaylist().size() == 1 && panel.getPlaylist().getFirst().equals(queued),
                "taking a map off the list leaves its playlist row alone");
        check(panel.getPlaylist().stream().noneMatch(entry -> entry.trackId().equals("beta")),
                "putting a map on the list does not queue it");

        DjPanelBlockEntity legacy = blankPanel();
        legacy.addPlaylistEntry("row_one", 0);
        legacy.addPlaylistEntry("row_two", 0);
        legacy.addPlaylistEntry("row_one", 40);
        legacy.startPlayback("on_deck", 100L);
        // The card selected for the next song, which is not the one playing.
        legacy.selectTrack("picked_card");
        CompoundTag old = saveAdditional(legacy);
        old.remove("DjLibrary");
        DjPanelBlockEntity migrated = blankPanel();
        loadAdditional(migrated, old);
        check(migrated.getLibrary().equals(List.of("row_one", "row_two", "on_deck", "picked_card")),
                "a booth saved before the list starts it with its queue, its deck and its selected card, once each");
        DjPanelBlockEntity emptied = blankPanel();
        CompoundTag none = saveAdditional(emptied);
        DjPanelBlockEntity reloadedEmpty = blankPanel();
        loadAdditional(reloadedEmpty, none);
        check(reloadedEmpty.getLibrary().isEmpty(), "a list emptied on purpose stays empty after a reload");
    }

    /**
     * "Reset speakers" with every speaker selected: the rack's reverb, echo and low pass go back to how the booth
     * ships, and the Visual FX tab stays exactly as the DJ left it. For a while this reset also cleared the DJ effect
     * stack, and a tab built over an evening was gone with one press of a button about speakers.
     */
    private static void resetChecks(List<TrackEffect> chart) throws Exception {
        DjPanelBlockEntity panel = blankPanel();
        panel.setVisualEffects(false, true, List.of(new DjVisualEffectConfig("strobe", true, chart.get(0)),
                new DjVisualEffectConfig("sweep", false, chart.get(1))), 40);
        panel.setDspSettings(false, true, 0.9F, 0.2F, 0.1F, true, 0.6F, 600.0F, 0.7F, true, 900.0F);
        List<DjVisualEffectConfig> effects = panel.getDjVisualEffects();
        long fxRevision = panel.getVisualFxRevision();
        int activation = panel.getVisualFxActivationTick();
        long dspRevision = panel.getDspRevision();
        panel.resetSound();
        check(panel.isDspEnabled() && !panel.isReverbEnabled() && !panel.isEchoEnabled() && !panel.isLowPassEnabled()
                        && Float.compare(panel.getReverbWet(), 0.30F) == 0 && Float.compare(panel.getEchoTimeMs(), 280.0F) == 0
                        && Float.compare(panel.getLowPassCutoffHz(), 12_000.0F) == 0 && panel.getDspRevision() != dspRevision,
                "resetting every speaker puts the rack's reverb, echo and low pass back");
        check(!panel.isTrackVisualFxEnabled() && panel.isDjVisualFxEnabled() && effects.size() == 2
                        && panel.getDjVisualEffects().equals(effects) && panel.getVisualFxRevision() == fxRevision
                        && panel.getVisualFxActivationTick() == activation,
                "resetting the speakers leaves the Visual FX tab exactly as it was");
    }

    private static Field field(String name) throws NoSuchFieldException {
        Field field = DjPanelBlockEntity.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static CompoundTag saveAdditional(DjPanelBlockEntity panel) throws Exception {
        Method save = DjPanelBlockEntity.class.getDeclaredMethod("saveAdditional", CompoundTag.class, HolderLookup.Provider.class);
        save.setAccessible(true);
        CompoundTag tag = new CompoundTag();
        save.invoke(panel, tag, null);
        return tag;
    }

    private static void loadAdditional(DjPanelBlockEntity panel, CompoundTag tag) throws Exception {
        Method load = DjPanelBlockEntity.class.getDeclaredMethod("loadAdditional", CompoundTag.class, HolderLookup.Provider.class);
        load.setAccessible(true);
        load.invoke(panel, tag, null);
    }

    private static void check(boolean condition, String message) {
        if (condition) {
            System.out.println("PASS " + message);
        } else {
            failures++;
            System.out.println("FAIL " + message);
        }
    }
}

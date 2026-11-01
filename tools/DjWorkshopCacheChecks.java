package ru.gora.musicbattle.server;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import ru.gora.musicbattle.block.entity.DjPanelBlockEntity;
import ru.gora.musicbattle.data.DjPlaylistEntry;
import ru.gora.musicbattle.data.DjWorkshopSource;
import ru.gora.musicbattle.server.workshop.ServerBeatmapTransfers;

/**
 * Workshop beatmaps a DJ booth plays from the server's workshop_cache must keep working like server ones (A01):
 *
 * <ul>
 *   <li>the booth keeps the set and content hash beside each cached {@code ws_} id it still uses, in its NBT, and
 *       drops them once the id is off its list, its playlist, its deck and its selection;</li>
 *   <li>after a restart (memory cleared) the chart is read back from the cache through the set a booth names, and
 *       without that record it is not found, which is the bug this fixes;</li>
 *   <li>the world-wide record of every booth's sets survives a save and a load;</li>
 *   <li>the cache clean-up never deletes a set a booth uses, and still evicts the others;</li>
 *   <li>a booth saved by 0.1.0-beta.1 (ids only, no record anywhere) finds its sets in the cache folders when it
 *       loads, keeps them from then on and protects them; the folders a world had before the upgrade stay
 *       protected too, because such a booth may sit in a chunk nobody has loaded yet;</li>
 *   <li>that search is only for a booth's own ids and runs once per set per run: a lookup by id (what a client's
 *       payload reaches, with an id of its choosing) never reads the cache folders, and ids of a set already
 *       searched cost a map lookup.</li>
 * </ul>
 *
 * Runs against the real classes; the server-only half of the booth's hook (adding a source while a server level is
 * set) is pinned by a source rule and proven in a running game.
 */
public final class DjWorkshopCacheChecks {
    private static final String SHA_A = "a".repeat(64);
    private static final String SHA_B = "b".repeat(64);
    private static final String SHA_C = "c".repeat(64);
    private static final String SHA_E = "e".repeat(64);
    private static final String SHA_F = "f".repeat(64);
    private static int failures;
    private static int passes;

    public static void main(String[] args) throws Exception {
        Path root = args.length > 0 ? Path.of(args[0]) : Path.of(".");
        Path temp = Files.createTempDirectory("dj-workshop-cache");
        try {
            boothKeepsSources();
            damagedSourcesDropped();
            cachedChartsComeBack(temp.resolve("restart"));
            worldRecordRoundTrips();
            evictionSparesBooths(temp.resolve("evict"));
            oldBoothsRecovered(temp.resolve("legacy"));
            preUpgradeFoldersKept(temp.resolve("inventory"));
            setIds();
            sourceRules(root);
        } finally {
            deleteTree(temp);
        }
        System.out.println(passes + " passed, " + failures + " failed");
        if (failures > 0) {
            System.exit(1);
        }
    }

    // ------------------------------------------------------------------------------------------- the booth's NBT

    private static void boothKeepsSources() throws Exception {
        DjPanelBlockEntity panel = blankPanel();
        check(panel.addToLibrary("ws_7_hard"), "a cached Workshop map joins the booth's list");
        check(panel.addToLibrary("server_song"), "a server map joins the booth's list");
        DjPlaylistEntry row = panel.addPlaylistEntry("ws_8_easy", 0);
        check(row != null, "a cached Workshop map joins the playlist");
        Map<String, DjWorkshopSource> injected = new LinkedHashMap<>();
        injected.put("ws_7_hard", new DjWorkshopSource(7, SHA_A));
        injected.put("ws_8_easy", new DjWorkshopSource(8, SHA_B));
        injected.put("ws_9_gone", new DjWorkshopSource(9, SHA_C));
        sources(panel).putAll(injected);
        referencesChanged(panel);
        check(panel.getWorkshopSources().keySet().equals(Set.of("ws_7_hard", "ws_8_easy")),
                "the booth keeps sources only for ids it uses: " + panel.getWorkshopSources().keySet());

        CompoundTag saved = saveAdditional(panel);
        check(saved.contains(DjWorkshopSource.TAG, Tag.TAG_LIST), "the saved booth carries " + DjWorkshopSource.TAG);
        ListTag list = saved.getList(DjWorkshopSource.TAG, Tag.TAG_COMPOUND);
        boolean exact = list.size() == 2;
        for (int index = 0; index < list.size(); index++) {
            CompoundTag entry = list.getCompound(index);
            DjWorkshopSource expected = injected.get(entry.getString("Track"));
            exact &= expected != null && entry.getLong("Set") == expected.setId()
                    && entry.getString("Sha").equals(expected.contentSha256());
        }
        check(exact, "each saved entry holds the id, the set id and the content hash: " + list);

        DjPanelBlockEntity restarted = blankPanel();
        loadAdditional(restarted, saved);
        check(restarted.getWorkshopSources().equals(Map.of("ws_7_hard", new DjWorkshopSource(7, SHA_A),
                        "ws_8_easy", new DjWorkshopSource(8, SHA_B))),
                "a booth loaded after a restart knows where its cached maps came from: " + restarted.getWorkshopSources());

        check(restarted.removeFromLibrary("ws_7_hard"), "x takes the cached map off the list");
        check(!restarted.getWorkshopSources().containsKey("ws_7_hard"),
                "its source goes with it once nothing on the booth uses the id");
        check(restarted.getWorkshopSources().containsKey("ws_8_easy"),
                "a map only in the playlist keeps its source: the row still has to play");
        restarted.startPlayback("ws_8_easy", 100L, 0, restarted.getPlaylist().getFirst().entryId());
        restarted.clearPlaylist();
        check(restarted.getWorkshopSources().containsKey("ws_8_easy"),
                "the song on the deck keeps its source after the playlist is cleared");
        restarted.startPlayback("server_song", 200L);
        check(restarted.getWorkshopSources().isEmpty(),
                "once the deck and the selection move on, nothing holds the set: " + restarted.getWorkshopSources());
        check(!saveAdditional(restarted).contains(DjWorkshopSource.TAG),
                "a booth with no cached maps writes no source list");

        DjPanelBlockEntity old = blankPanel();
        CompoundTag legacy = new CompoundTag();
        ListTag library = new ListTag();
        library.add(net.minecraft.nbt.StringTag.valueOf("ws_7_hard"));
        legacy.put("DjLibrary", library);
        loadAdditional(old, legacy);
        check(old.getLibrary().equals(List.of("ws_7_hard")) && old.getWorkshopSources().isEmpty(),
                "a booth saved before the sources existed loads its list as before");

        check(DjWorkshopSource.referenced(List.of("a"), List.of("b"), "c", "d").equals(Set.of("a", "b", "c", "d")),
                "list, playlist, deck and selection all count as uses");
    }

    private static void damagedSourcesDropped() throws Exception {
        CompoundTag tag = new CompoundTag();
        ListTag list = new ListTag();
        list.add(entry("ws_7_hard", 7, SHA_A));
        list.add(entry("ws_9_other", 7, SHA_A));
        list.add(entry("ws_7_bad", 7, "not-a-hash"));
        list.add(entry("ws_0_zero", 0, SHA_A));
        list.add(entry("ws_7_" + "x".repeat(DjPlaylistEntry.MAX_TRACK_ID_LENGTH), 7, SHA_A));
        list.add(entry("ws_7_hard", 7, SHA_B));
        tag.put(DjWorkshopSource.TAG, list);
        Map<String, DjWorkshopSource> read = DjWorkshopSource.read(tag, DjWorkshopSource.TAG,
                DjPlaylistEntry.MAX_TRACK_ID_LENGTH);
        check(read.equals(Map.of("ws_7_hard", new DjWorkshopSource(7, SHA_A))),
                "a damaged entry cannot point a beatmap at another set, a bad hash or an overlong id: " + read);
    }

    // ------------------------------------------------------------------------------------------- after a restart

    private static void cachedChartsComeBack(Path world) throws Exception {
        Path storage = world.resolve("blocky_music");
        Path set = storage.resolve(ServerBeatmapTransfers.CACHE_DIRECTORY).resolve(SHA_A).resolve("workshop")
                .resolve("ws_42");
        Files.createDirectories(set);
        Files.writeString(set.resolve("ws_42_hard.json"), """
                {"id": "ws_42_hard", "title": "Cached song", "bpm": 120, "durationTicks": 400,
                 "notes": [{"owner": 0, "lane": 1, "tick": 20, "durationTicks": 0}]}
                """, StandardCharsets.UTF_8);
        // A newer cached revision of the same set also holds the chart: the booth's record must win over a search.
        Path newer = writeChart(storage, SHA_E, 42, "ws_42_hard", "Newer copy");
        Files.setLastModifiedTime(newer.getParent(), java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 60_000L));
        useWorld(storage);
        DjWorkshopCache.onServerStopping();
        Function<String, DjWorkshopSource> booths = id -> id.equals("ws_42_hard") ? new DjWorkshopSource(42, SHA_A) : null;
        Optional<MusicBattleTracks.Track> back = DjWorkshopCache.track("ws_42_hard", booths);
        check(back.isPresent() && back.get().title().equals("Cached song"),
                "the chart a booth names is read back from workshop_cache after a restart, from the set it names");
        check(DjWorkshopCache.sourceOf("ws_42_hard").equals(Optional.of(new DjWorkshopSource(42, SHA_A))),
                "and the server knows which set it came from, for the next booth that adds it");
        check(DjWorkshopCache.tracks().stream().anyMatch(track -> track.id().equals("ws_42_hard")),
                "the chart is in the panel's track list again");

        DjWorkshopCache.onServerStopping();
        DjWorkshopCache.remember(42, SHA_A);
        Optional<MusicBattleTracks.Track> arrived = DjWorkshopCache.track("ws_42_hard", id -> null);
        check(arrived.isPresent() && arrived.get().title().equals("Cached song"),
                "a set that arrives (remember) is found without any booth record, and no search replaces it");

        DjWorkshopCache.onServerStopping();
        check(DjWorkshopCache.track("ws_42_hard", id -> null).isEmpty() && DjWorkshopCache.tracks().isEmpty()
                        && searchedSets().isEmpty(),
                "a lookup by id that nothing has on record never searches the cache folders, because any client "
                        + "payload naming a track reaches it with an id of the client's choosing");
        for (int i = 0; i < 500; i++) {
            DjWorkshopCache.track("ws_42_spam" + i, id -> null);
        }
        check(DjWorkshopCache.tracks().isEmpty() && searchedSets().isEmpty(),
                "500 made-up ids of a cached set read nothing from disk and leave nothing behind");
        check(DjWorkshopCache.sourceFor("ws_42_nosuch", id -> false).isEmpty() && DjWorkshopCache.tracks().isEmpty(),
                "a booth id that none of its set's folders holds finds nothing and reads no folder in");
        Optional<DjWorkshopSource> searched = DjWorkshopCache.sourceFor("ws_42_hard", id -> false);
        Optional<MusicBattleTracks.Track> adopted = DjWorkshopCache.track("ws_42_hard", id -> null);
        check(searched.equals(Optional.of(new DjWorkshopSource(42, SHA_E)))
                        && adopted.isPresent() && adopted.get().title().equals("Newer copy"),
                "with no record anywhere (a 0.1.0-beta.1 booth), the booth's own id finds the chart in the newest "
                        + "cached folder that holds it: " + searched + " " + adopted.map(MusicBattleTracks.Track::title));
        writeChart(storage, SHA_A, 42, "ws_42_late", "Written after the search");
        check(DjWorkshopCache.sourceFor("ws_42_late", id -> false).isEmpty(),
                "a set's cache folders are searched once per run however many of its ids are asked for");
        for (int i = 0; i < 500; i++) {
            DjWorkshopCache.sourceFor("ws_42_spam" + i, id -> false);
        }
        check(searchedSets().equals(Set.of(42L)),
                "ids of a set already searched cost a map lookup: the search record holds one entry per set, "
                        + "not per id: " + searchedSets());
        DjWorkshopCache.onServerStopping();
        check(DjWorkshopCache.sourceFor("ws_42_late", id -> false).equals(Optional.of(new DjWorkshopSource(42, SHA_A))),
                "the next run searches the set again and finds a chart only an older folder holds");
        Files.delete(set.resolve("ws_42_late.json"));
        Files.delete(newer.resolve("ws_42").resolve("ws_42_hard.json"));
        Files.delete(newer.resolve("ws_42"));
        Files.delete(newer);
        Files.delete(newer.getParent());

        DjWorkshopCache.onServerStopping();
        check(DjWorkshopCache.sourceFor("ws_44_hard", id -> false).isEmpty(), "an id no cached folder holds is not found");
        writeChart(storage, SHA_F, 44, "ws_44_hard", "Appeared later");
        check(DjWorkshopCache.sourceFor("ws_44_hard", id -> false).isEmpty(),
                "the cache folders are searched once per set per run, not on every tick that asks for it");
        DjWorkshopCache.onServerStopping();
        check(DjWorkshopCache.sourceFor("ws_44_hard", id -> false).isPresent()
                        && DjWorkshopCache.track("ws_44_hard", id -> null).isPresent(), "the next run searches again");

        DjWorkshopCache.onServerStopping();
        Function<String, DjWorkshopSource> missing = id -> new DjWorkshopSource(43, SHA_B);
        check(DjWorkshopCache.track("ws_43_hard", missing).isEmpty(), "a set gone from the cache resolves to nothing");
        Path late = storage.resolve(ServerBeatmapTransfers.CACHE_DIRECTORY).resolve(SHA_B).resolve("workshop")
                .resolve("ws_43");
        Files.createDirectories(late);
        Files.writeString(late.resolve("ws_43_hard.json"), """
                {"id": "ws_43_hard", "title": "Late", "bpm": 120, "durationTicks": 400, "notes": []}
                """, StandardCharsets.UTF_8);
        check(DjWorkshopCache.track("ws_43_hard", missing).isEmpty(),
                "a miss is read from disk once per run, not on every tick that asks for it");
        DjWorkshopCache.remember(43, SHA_B);
        check(DjWorkshopCache.track("ws_43_hard", missing).isPresent(), "its arrival reads it again");
        DjWorkshopCache.onServerStopping();
        useWorld(null);
    }

    private static void worldRecordRoundTrips() throws Exception {
        Class<?> type = Class.forName("ru.gora.musicbattle.server.DjWorkshopCache$References");
        Constructor<?> constructor = type.getDeclaredConstructor();
        constructor.setAccessible(true);
        Object references = constructor.newInstance();
        Method put = type.getDeclaredMethod("put", String.class, Map.class);
        Method remove = type.getDeclaredMethod("remove", String.class);
        Method sourceOf = type.getDeclaredMethod("sourceOf", String.class);
        Method allSources = type.getDeclaredMethod("allSources");
        Method save = type.getDeclaredMethod("save", CompoundTag.class, HolderLookup.Provider.class);
        Method load = type.getDeclaredMethod("load", CompoundTag.class, HolderLookup.Provider.class);
        for (Method method : List.of(put, remove, sourceOf, allSources, save, load)) {
            method.setAccessible(true);
        }
        Map<String, DjWorkshopSource> booth = Map.of("ws_7_hard", new DjWorkshopSource(7, SHA_A));
        check((boolean) put.invoke(references, "minecraft:overworld|1", booth), "a booth's sets join the record");
        check(!(boolean) put.invoke(references, "minecraft:overworld|1", booth), "the same sets change nothing");
        put.invoke(references, "minecraft:the_nether|2", Map.of("ws_8_easy", new DjWorkshopSource(8, SHA_B)));
        CompoundTag tag = (CompoundTag) save.invoke(references, new CompoundTag(), null);
        Object loaded = load.invoke(null, tag, null);
        check(new DjWorkshopSource(7, SHA_A).equals(sourceOf.invoke(loaded, "ws_7_hard"))
                        && new DjWorkshopSource(8, SHA_B).equals(sourceOf.invoke(loaded, "ws_8_easy")),
                "the world's record of every booth's sets survives a save and a load");
        check(((List<?>) allSources.invoke(loaded)).size() == 2, "both sets are protected after the load");
        check((boolean) put.invoke(loaded, "minecraft:overworld|1", Map.of()), "a booth with no cached maps leaves");
        check((boolean) remove.invoke(loaded, "minecraft:the_nether|2"), "a broken booth leaves");
        check(((List<?>) allSources.invoke(loaded)).isEmpty(), "and its sets no longer hold the clean-up back");
    }

    // ------------------------------------------------------------------------------------------- the clean-up

    private static void evictionSparesBooths(Path world) throws Exception {
        Path storage = world.resolve("blocky_music");
        Path cache = storage.resolve(ServerBeatmapTransfers.CACHE_DIRECTORY);
        String newest = "d".repeat(64);
        long now = System.currentTimeMillis();
        int age = 4;
        for (String sha : List.of(SHA_A, SHA_B, SHA_C, newest)) {
            Path folder = cache.resolve(sha).resolve("workshop").resolve("ws_1");
            Files.createDirectories(folder);
            Files.write(folder.resolve("ws_1.ogg"), new byte[64 * 1024]);
            Files.setLastModifiedTime(cache.resolve(sha), java.nio.file.attribute.FileTime.fromMillis(now - age-- * 60_000L));
        }
        Method evict = ServerBeatmapTransfers.class.getDeclaredMethod("evictCache", Path.class, String.class, long.class);
        evict.setAccessible(true);
        ServerBeatmapTransfers.protectCache(Set.of(SHA_A, SHA_B));
        evict.invoke(null, storage, newest, 1L);
        check(Files.isDirectory(cache.resolve(SHA_A)) && Files.isDirectory(cache.resolve(SHA_B)),
                "the clean-up keeps the oldest sets because DJ booths use them");
        check(!Files.exists(cache.resolve(SHA_C)), "it still evicts a set no booth uses");
        check(Files.isDirectory(cache.resolve(newest)), "and never the set that just arrived");
        ServerBeatmapTransfers.protectCache(Set.of());
        evict.invoke(null, storage, newest, 1L);
        check(!Files.exists(cache.resolve(SHA_A)) && !Files.exists(cache.resolve(SHA_B)),
                "once no booth uses them they are evicted like any other");
    }

    // ------------------------------------------------------------------------------------------- 0.1.0-beta.1 booths

    /**
     * The upgrade path: 0.1.0-beta.1 let a DJ without map rights bring a cached set onto a booth and kept the set
     * only in memory, so its booths hold {@code ws_} ids in DjLibrary and the playlist and nothing else. Loaded under
     * this version, such a booth must find each set in the cache folders, keep it in its own NBT, resolve and play
     * the row, and keep the clean-up away from the folder.
     */
    private static void oldBoothsRecovered(Path world) throws Exception {
        Path storage = world.resolve("blocky_music");
        Path cache = storage.resolve(ServerBeatmapTransfers.CACHE_DIRECTORY);
        long now = System.currentTimeMillis();
        Path holder = writeChart(storage, SHA_A, 42, "ws_42_hard", "Old booth song");
        Files.setLastModifiedTime(holder.getParent(), java.nio.file.attribute.FileTime.fromMillis(now - 600_000L));
        // A newer cached revision of set 42 without that chart, and an unrelated set: neither may be picked.
        Path otherRevision = writeChart(storage, SHA_B, 42, "ws_42_easy", "Other revision");
        Files.setLastModifiedTime(otherRevision.getParent(), java.nio.file.attribute.FileTime.fromMillis(now - 60_000L));
        Path unrelated = writeChart(storage, SHA_C, 9, "ws_9_x", "Unrelated");
        Files.setLastModifiedTime(unrelated.getParent(), java.nio.file.attribute.FileTime.fromMillis(now - 900_000L));
        for (String sha : List.of(SHA_A, SHA_B, SHA_C)) {
            Files.write(cache.resolve(sha).resolve("workshop").resolve("pad.bin"), new byte[64 * 1024]);
        }
        useWorld(storage);
        DjWorkshopCache.onServerStopping();

        DjPanelBlockEntity before = blankPanel();
        before.addToLibrary("ws_42_hard");
        before.addPlaylistEntry("ws_42_hard", 0);
        CompoundTag saved = saveAdditional(before);
        saved.remove(DjWorkshopSource.TAG);
        check(saved.contains("DjLibrary") && !saved.contains(DjWorkshopSource.TAG),
                "the booth is saved the 0.1.0-beta.1 way: ids in its list and playlist, no sources");
        DjPanelBlockEntity panel = blankPanel();
        loadAdditional(panel, saved);
        check(panel.getWorkshopSources().isEmpty() && panel.getPlaylist().size() == 1,
                "it loads with its rows and no sources");

        // What the booth's onLoad does on a server (workshopReferencesChanged, pinned below by a source rule).
        Set<String> referenced = DjWorkshopSource.referenced(panel.getLibrary(),
                panel.getPlaylist().stream().map(DjPlaylistEntry::trackId).toList(), null, null);
        check(!DjWorkshopCache.adoptSources(sources(panel), referenced, id -> true) && panel.getWorkshopSources().isEmpty(),
                "an id the catalogue lists is not searched for in the cache");
        check(DjWorkshopCache.adoptSources(sources(panel), referenced, id -> false),
                "loaded on a server, the booth adopts a source for its row");
        check(panel.getWorkshopSources().equals(Map.of("ws_42_hard", new DjWorkshopSource(42, SHA_A))),
                "the source is the cached folder that holds the chart, not a newer revision without it: "
                        + panel.getWorkshopSources());
        CompoundTag resaved = saveAdditional(panel);
        check(DjWorkshopSource.read(resaved, DjWorkshopSource.TAG, DjPlaylistEntry.MAX_TRACK_ID_LENGTH)
                        .equals(Map.of("ws_42_hard", new DjWorkshopSource(42, SHA_A))),
                "and saves it, so the next restart reads it from the booth's own NBT");
        Optional<MusicBattleTracks.Track> row = DjWorkshopCache.track("ws_42_hard", id -> null);
        check(row.isPresent() && row.get().title().equals("Old booth song"), "the old booth's row resolves again");

        Object references = newReferences();
        Method put = referencesMethod("put", String.class, Map.class);
        put.invoke(references, "minecraft:overworld|5", panel.getWorkshopSources());
        Method publish = DjWorkshopCache.class.getDeclaredMethod("publishProtection", referencesType());
        publish.setAccessible(true);
        publish.invoke(null, references);
        String newest = "d".repeat(64);
        Files.createDirectories(cache.resolve(newest));
        Method evict = ServerBeatmapTransfers.class.getDeclaredMethod("evictCache", Path.class, String.class, long.class);
        evict.setAccessible(true);
        evict.invoke(null, storage, newest, 1L);
        check(Files.isRegularFile(holder.resolve("ws_42").resolve("ws_42_hard.json")),
                "the clean-up over quota keeps the folder the old booth adopted");
        check(!Files.exists(cache.resolve(SHA_B)) && !Files.exists(cache.resolve(SHA_C)),
                "and still evicts the folders no booth uses");
        ServerBeatmapTransfers.protectCache(Set.of());
        DjWorkshopCache.onServerStopping();
        useWorld(null);
    }

    /**
     * An old booth whose chunk nobody loads after the upgrade cannot adopt anything yet. The folders a world had when
     * it first started with this version are kept for it (0.1.0-beta.1 never evicted them either), once per world;
     * what arrives afterwards is subject to the quota as before.
     */
    private static void preUpgradeFoldersKept(Path world) throws Exception {
        Path storage = world.resolve("blocky_music");
        Path cache = storage.resolve(ServerBeatmapTransfers.CACHE_DIRECTORY);
        writeChart(storage, SHA_A, 42, "ws_42_hard", "Before the upgrade");
        Files.createDirectories(cache.resolve("not-a-hash"));
        Method folders = DjWorkshopCache.class.getDeclaredMethod("cachedFolders", Path.class);
        folders.setAccessible(true);
        List<?> found = (List<?>) folders.invoke(null, storage);
        check(found.equals(List.of(SHA_A)), "the inventory lists the cached set folders only: " + found);
        check(((List<?>) folders.invoke(null, world.resolve("nowhere"))).isEmpty(), "a world without a cache lists none");

        Object references = newReferences();
        Field inventoried = referencesType().getDeclaredField("inventoried");
        inventoried.setAccessible(true);
        check(!(boolean) inventoried.get(references), "a world that never ran this version has no inventory yet");
        Method inventory = referencesMethod("inventory", java.util.Collection.class);
        check((int) inventory.invoke(references, found) == 1 && (boolean) inventoried.get(references),
                "the first start takes it");
        Method save = referencesMethod("save", CompoundTag.class, HolderLookup.Provider.class);
        Method load = referencesMethod("load", CompoundTag.class, HolderLookup.Provider.class);
        Method hashes = referencesMethod("protectedHashes");
        Object loaded = load.invoke(null, save.invoke(references, new CompoundTag(), null), null);
        check((boolean) inventoried.get(loaded) && ((Set<?>) hashes.invoke(loaded)).equals(Set.of(SHA_A)),
                "it survives a save and a load, so later starts neither retake it nor forget it");
        check(!(boolean) inventoried.get(load.invoke(null, new CompoundTag(), null)),
                "a record saved without one is inventoried on its next start");

        String later = "9".repeat(64);
        String newest = "8".repeat(64);
        writeChart(storage, later, 50, "ws_50_x", "Arrived after");
        Files.write(cache.resolve(later).resolve("workshop").resolve("pad.bin"), new byte[64 * 1024]);
        Files.write(cache.resolve(SHA_A).resolve("workshop").resolve("pad.bin"), new byte[64 * 1024]);
        Files.createDirectories(cache.resolve(newest));
        Method publish = DjWorkshopCache.class.getDeclaredMethod("publishProtection", referencesType());
        publish.setAccessible(true);
        publish.invoke(null, loaded);
        Method evict = ServerBeatmapTransfers.class.getDeclaredMethod("evictCache", Path.class, String.class, long.class);
        evict.setAccessible(true);
        evict.invoke(null, storage, newest, 1L);
        check(Files.isDirectory(cache.resolve(SHA_A)), "a folder from before the upgrade is kept by the clean-up");
        check(!Files.exists(cache.resolve(later)), "one that arrived afterwards is evicted as before");
        ServerBeatmapTransfers.protectCache(Set.of());
    }

    private static void setIds() throws Exception {
        Method setIdOf = DjWorkshopCache.class.getDeclaredMethod("setIdOf", String.class);
        setIdOf.setAccessible(true);
        Map<String, Long> cases = new LinkedHashMap<>();
        cases.put("ws_42_hard", 42L);
        cases.put("ws_9223372036854775807_a", Long.MAX_VALUE);
        cases.put("ws_x_hard", 0L);
        cases.put("ws_42", 0L);
        cases.put("ws_42_", 0L);
        cases.put("ws__hard", 0L);
        cases.put("server_song", 0L);
        boolean all = true;
        for (Map.Entry<String, Long> entry : cases.entrySet()) {
            all &= ((long) setIdOf.invoke(null, entry.getKey())) == entry.getValue();
        }
        check(all, "only ws_<digits>_<chart> names a set to search for");
    }

    // ------------------------------------------------------------------------------------------- source rules

    private static void sourceRules(Path root) throws Exception {
        Path main = root.resolve("src/main/java/ru/gora/musicbattle");
        String panel = Files.readString(main.resolve("block/entity/DjPanelBlockEntity.java"));
        check(body(panel, "public void onLoad()").contains("workshopReferencesChanged()"),
                "a booth that loads reads its cached sets back and joins the world's record");
        String hook = body(panel, "private void workshopReferencesChanged()");
        int server = hook.indexOf("level instanceof ServerLevel");
        check(server > 0 && hook.indexOf("DjWorkshopCache.adoptSources(", server) > server
                        && hook.contains("MusicBattleTracks.byId(") && hook.contains("DjWorkshopCache.boothReferences"),
                "on a server the booth adopts each new id's source (searching the cache for an old booth's, unless "
                        + "the catalogue lists it) and reports its sets");
        for (String mutator : List.of("public boolean addToLibrary(String trackId)", "public boolean removeFromLibrary(String trackId)",
                "private void playlistChanged()", "public void selectTrack(String trackId)",
                "public void startPlayback(String trackId, long gameTime, int offsetTicks, UUID playlistEntryId)")) {
            check(body(panel, mutator).contains("workshopReferencesChanged()"), mutator + " reports the booth's uses");
        }
        String manager = Files.readString(main.resolve("server/DjPlaybackManager.java"));
        check(body(manager, "public static Optional<MusicBattleTracks.Track> trackById(String id)")
                .contains("DjWorkshopCache.track("), "trackById falls back to the cache record");
        check(body(manager, "public static void destroyPanel(ServerLevel level, BlockPos pos)")
                .contains("DjWorkshopCache.forgetBooth"), "breaking a booth releases its sets");
        String mod = Files.readString(main.resolve("MusicBattleMod.java"));
        check(mod.contains("DjWorkshopCache::onServerStarted"), "the server reads every booth's sets back once started");
        String cacheCode = Files.readString(main.resolve("server/DjWorkshopCache.java"));
        String started = body(cacheCode, "public static void onServerStarted(ServerStartedEvent event)");
        check(started.contains("if (!references.inventoried)") && started.contains("references.inventory(")
                        && started.indexOf("references.inventory(") < started.indexOf("publishProtection("),
                "the first start of a world takes the pre-upgrade inventory once, before protecting");
        check(body(cacheCode, "private static void publishProtection(References references)").contains("protectedHashes()"),
                "the protection covers the booths' sets and the pre-upgrade folders");
        String transfers = Files.readString(main.resolve("server/workshop/ServerBeatmapTransfers.java"));
        check(body(transfers, "static void evictCache(Path worldStorage, String keep, long quota)").contains("protectedCache"),
                "the clean-up reads the protected sets");
    }

    // ------------------------------------------------------------------------------------------- helpers

    /** {@code <storage>/workshop_cache/<sha>/workshop/ws_<set>/<chart>.json}; returns the {@code <sha>/workshop} folder. */
    private static Path writeChart(Path storage, String sha, long set, String chart, String title) throws Exception {
        Path installed = storage.resolve(ServerBeatmapTransfers.CACHE_DIRECTORY).resolve(sha).resolve("workshop");
        Path folder = installed.resolve("ws_" + set);
        Files.createDirectories(folder);
        Files.writeString(folder.resolve(chart + ".json"), "{\"id\": \"" + chart + "\", \"title\": \"" + title
                + "\", \"bpm\": 120, \"durationTicks\": 400, \"notes\": []}", StandardCharsets.UTF_8);
        return installed;
    }

    @SuppressWarnings("unchecked")
    private static Set<Long> searchedSets() throws Exception {
        Field field = DjWorkshopCache.class.getDeclaredField("SEARCHED");
        field.setAccessible(true);
        return Set.copyOf(((Map<Long, ?>) field.get(null)).keySet());
    }

    private static Class<?> referencesType() throws Exception {
        return Class.forName("ru.gora.musicbattle.server.DjWorkshopCache$References");
    }

    private static Object newReferences() throws Exception {
        Constructor<?> constructor = referencesType().getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }

    private static Method referencesMethod(String name, Class<?>... types) throws Exception {
        Method method = referencesType().getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method;
    }

    private static CompoundTag entry(String track, long set, String sha) {
        CompoundTag tag = new CompoundTag();
        tag.putString("Track", track);
        tag.putLong("Set", set);
        tag.putString("Sha", sha);
        return tag;
    }

    /** The text of the method that starts with {@code signature}, braces balanced. */
    private static String body(String source, String signature) {
        int start = source.indexOf(signature);
        if (start < 0) {
            return "";
        }
        int open = source.indexOf('{', start);
        int depth = 0;
        for (int index = open; index < source.length(); index++) {
            char c = source.charAt(index);
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return source.substring(start, index + 1);
            }
        }
        return "";
    }

    @SuppressWarnings("unchecked")
    private static Map<String, DjWorkshopSource> sources(DjPanelBlockEntity panel) throws Exception {
        return (Map<String, DjWorkshopSource>) field("workshopSources").get(panel);
    }

    private static void referencesChanged(DjPanelBlockEntity panel) throws Exception {
        Method method = DjPanelBlockEntity.class.getDeclaredMethod("workshopReferencesChanged");
        method.setAccessible(true);
        method.invoke(panel);
    }

    private static void useWorld(Path storage) throws Exception {
        Field active = MusicBattleTracks.class.getDeclaredField("activeWorldStorageDirectory");
        active.setAccessible(true);
        active.set(null, storage);
    }

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
        field("workshopSources").set(panel, new LinkedHashMap<String, DjWorkshopSource>());
        loadAdditional(panel, new CompoundTag());
        return panel;
    }

    private static Field field(String name) throws Exception {
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

    private static void deleteTree(Path root) throws Exception {
        if (!Files.exists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static void check(boolean condition, String message) {
        if (condition) {
            passes++;
            System.out.println("PASS " + message);
        } else {
            failures++;
            System.out.println("FAIL " + message);
        }
    }
}

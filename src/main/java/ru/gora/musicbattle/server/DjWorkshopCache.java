package ru.gora.musicbattle.server;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import ru.gora.musicbattle.MusicBattleMod;
import ru.gora.musicbattle.data.DjPlaylistEntry;
import ru.gora.musicbattle.data.DjWorkshopSource;
import ru.gora.musicbattle.server.workshop.ServerBeatmapTransfers;
import ru.gora.musicbattle.workshop.WorkshopLayout;

/**
 * Workshop beatmaps a DJ booth plays from the server's {@code workshop_cache}: sets a DJ without map rights brought
 * from their own installed copy (or a battle vote cached), which never enter the catalogue.
 *
 * <p>They used to live only in a map in memory. After a restart a booth still listed {@code ws_42_hard}, but nothing
 * on the server knew which cached folder held it, so its row stopped resolving, and the cache clean-up was free to
 * delete a set a booth still listed. Now each booth keeps the set and content hash beside every such id
 * ({@link DjWorkshopSource}, in its own NBT), and this class keeps the same pairs for every booth in a world
 * {@link SavedData} — a booth in an unloaded chunk still counts. From that it reads the charts back from disk when
 * the server has started, when a booth loads, and when a lookup misses, and it tells
 * {@link ServerBeatmapTransfers#protectCache} which cached folders the clean-up must leave alone.
 *
 * <p>A booth saved by 0.1.0-beta.1 has {@code ws_} rows but no record of where they came from, and the world has no
 * list yet. When such a booth loads, the cache folders of each of its sets are searched once per run
 * ({@link #recover}), and the set found there becomes the booth's record from then on. Only a booth's own ids are
 * searched for: a lookup by id ({@link #track}), which is what a client's payload reaches, never touches the disk
 * for an id nothing has on record, so made-up ids cost the server a map lookup. Every cached folder that was already
 * there when a world first starts with this version stays protected as well ({@link References#inventory}): a booth
 * from before the upgrade whose chunk nobody has loaded yet still counts, and 0.1.0-beta.1 never deleted anything
 * from the cache anyway.
 */
public final class DjWorkshopCache {
    /** Charts read from the cache, by chart id. */
    private static final Map<String, MusicBattleTracks.Track> TRACKS = new HashMap<>();
    /** Which cached set each of those charts was read from. */
    private static final Map<String, DjWorkshopSource> SOURCES = new HashMap<>();
    /** Sets already read from disk this run, found or not, so a lookup that misses every tick reads nothing. */
    private static final Set<DjWorkshopSource> READ = new HashSet<>();
    /**
     * Sets whose cache folders were searched this run ({@link #recover}), found or not: for each, the chart ids those
     * folders hold and the newest folder holding each. Keyed by set id, so one set is read from disk once per run
     * however many of its ids are asked for; only ids a booth itself holds are searched for, so it stays small.
     */
    private static final Map<Long, Map<String, String>> SEARCHED = new HashMap<>();
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    private DjWorkshopCache() {
    }

    /** A chart read from the cache: memory first, then the set a booth said it came from. */
    static Optional<MusicBattleTracks.Track> track(MinecraftServer server, String id) {
        return track(id, server == null ? trackId -> null : trackId -> References.get(server).sourceOf(trackId));
    }

    /**
     * {@link #track(MinecraftServer, String)} with the booths' record as a lookup. A set is read from disk at most
     * once per run here, found or not, because DJ code asks for the same id every tick; an arrival reads it again
     * through {@link #remember}. An id with no record is never searched for here ({@link #recover} is for a booth's
     * own ids), because this is what every client payload naming a track reaches.
     */
    static Optional<MusicBattleTracks.Track> track(String id,
                                                    java.util.function.Function<String, DjWorkshopSource> booths) {
        if (id == null || id.isEmpty()) {
            return Optional.empty();
        }
        MusicBattleTracks.Track known = TRACKS.get(id);
        if (known != null || !id.startsWith("ws_")) {
            return Optional.ofNullable(known);
        }
        DjWorkshopSource source = booths.apply(id);
        if (source == null) {
            // Nothing on record names it. A client can send any id here (select, add, play), so this never searches
            // the cache folders: a booth saved by 0.1.0-beta.1 adopts its own ids when it loads (adoptSources).
            return Optional.empty();
        }
        if (READ.contains(source)) {
            return Optional.empty();
        }
        remember(source.setId(), source.contentSha256());
        return Optional.ofNullable(TRACKS.get(id));
    }

    /**
     * The cached set a booth should keep beside {@code trackId}: the one this run read it from, or, for a {@code ws_}
     * id nothing on record names and the catalogue does not list, the one {@link #recover} finds in the cache folders.
     */
    static Optional<DjWorkshopSource> sourceFor(String trackId, Predicate<String> listed) {
        DjWorkshopSource known = trackId == null ? null : SOURCES.get(trackId);
        if (known != null) {
            return Optional.of(known);
        }
        if (trackId == null || !trackId.startsWith(WorkshopLayout.SET_FOLDER_PREFIX) || listed.test(trackId)) {
            return Optional.empty();
        }
        return recover(trackId);
    }

    /**
     * Adds to a booth's {@code sources} the cached set behind every id in {@code referenced} that has none, as
     * {@link #sourceFor} finds it; true when something was added. A booth from 0.1.0-beta.1 gets its record here
     * the first time it loads, so it is saved and protected from then on.
     */
    public static boolean adoptSources(Map<String, DjWorkshopSource> sources, Collection<String> referenced,
                                       Predicate<String> listed) {
        boolean changed = false;
        for (String trackId : referenced) {
            if (!sources.containsKey(trackId)) {
                Optional<DjWorkshopSource> source = sourceFor(trackId, listed);
                if (source.isPresent()) {
                    sources.put(trackId, source.get());
                    changed = true;
                }
            }
        }
        return changed;
    }

    /**
     * Looks through {@code workshop_cache/<sha>/workshop/ws_<setId>/} for a set holding {@code trackId}: a booth saved
     * by 0.1.0-beta.1 kept only the id, so the cache folders are the only place left that knows the content hash.
     * Each set's folders are read once per run, found or not ({@link #SEARCHED}), so a booth with many rows of one set
     * costs one search. When several cached revisions hold the chart, the newest folder wins (the one the clean-up
     * would keep longest). The set found is read like an arrival ({@link #remember}).
     */
    static Optional<DjWorkshopSource> recover(String trackId) {
        long setId = setIdOf(trackId);
        if (setId <= 0) {
            return Optional.empty();
        }
        DjWorkshopSource known = SOURCES.get(trackId);
        if (known != null) {
            return Optional.of(known);
        }
        Map<String, String> holders = SEARCHED.get(setId);
        if (holders == null) {
            holders = searchSet(setId);
            SEARCHED.put(setId, holders);
        }
        String sha = holders.get(trackId);
        if (sha == null || READ.contains(new DjWorkshopSource(setId, sha))) {
            return Optional.empty();
        }
        remember(setId, sha);
        MusicBattleMod.LOGGER.info("Blocky Music: DJ beatmap {} found again in the Workshop cache ({})",
                trackId, sha.substring(0, 12));
        return Optional.ofNullable(SOURCES.get(trackId));
    }

    /** Every chart id the cached folders of one set hold, each with the newest folder that holds it; empty for none. */
    private static Map<String, String> searchSet(long setId) {
        Path worldStorage = MusicBattleTracks.worldStorageDirectory();
        if (worldStorage == null) {
            return Map.of();
        }
        Path root = worldStorage.resolve(ServerBeatmapTransfers.CACHE_DIRECTORY).toAbsolutePath().normalize();
        List<Path> candidates = new ArrayList<>();
        try (var stream = Files.newDirectoryStream(root)) {
            for (Path folder : stream) {
                if (SHA256.matcher(String.valueOf(folder.getFileName())).matches()
                        && Files.isDirectory(folder.resolve(WorkshopLayout.INSTALLED_DIRECTORY_NAME)
                        .resolve(WorkshopLayout.setFolderName(setId)), LinkOption.NOFOLLOW_LINKS)) {
                    candidates.add(folder);
                }
            }
        } catch (IOException | SecurityException exception) {
            return Map.of();
        }
        candidates.sort(Comparator.comparingLong(DjWorkshopCache::modifiedMillis).reversed()
                .thenComparing(folder -> String.valueOf(folder.getFileName())));
        Map<String, String> holders = new HashMap<>();
        for (Path folder : candidates) {
            String sha = String.valueOf(folder.getFileName());
            for (MusicBattleTracks.Track track : ServerBeatmapTransfers.cachedCharts(setId, sha)) {
                holders.putIfAbsent(track.id(), sha);
            }
        }
        return holders.isEmpty() ? Map.of() : holders;
    }

    /** The set id of {@code ws_<setId>_<chart>}, or 0 when the id is not one. */
    static long setIdOf(String trackId) {
        String prefix = WorkshopLayout.SET_FOLDER_PREFIX;
        if (trackId == null || !trackId.startsWith(prefix)) {
            return 0;
        }
        int end = trackId.indexOf('_', prefix.length());
        if (end <= prefix.length() || end > prefix.length() + 19 || end == trackId.length() - 1) {
            return 0;
        }
        try {
            return Long.parseLong(trackId.substring(prefix.length(), end));
        } catch (NumberFormatException exception) {
            return 0;
        }
    }

    private static long modifiedMillis(Path folder) {
        try {
            return Files.getLastModifiedTime(folder).toMillis();
        } catch (IOException ignored) {
            return 0L;
        }
    }

    /** Every chart read from the cache this run. */
    static Collection<MusicBattleTracks.Track> tracks() {
        return TRACKS.values();
    }

    /** The cached set a chart was read from, for a booth to keep beside its id; empty for a catalogue track. */
    public static Optional<DjWorkshopSource> sourceOf(String trackId) {
        return trackId == null ? Optional.empty() : Optional.ofNullable(SOURCES.get(trackId));
    }

    /** Reads every chart of one cached set from disk (again, if it was read before). */
    static void remember(long setId, String contentSha256) {
        if (!DjWorkshopSource.valid(setId, contentSha256)) {
            return;
        }
        DjWorkshopSource source = new DjWorkshopSource(setId, contentSha256);
        READ.add(source);
        for (MusicBattleTracks.Track track : ServerBeatmapTransfers.cachedCharts(setId, contentSha256)) {
            TRACKS.put(track.id(), track);
            SOURCES.put(track.id(), source);
        }
    }

    /**
     * A booth's Workshop references, as it holds them now; called by the booth whenever its list, playlist, deck or
     * selection changes and when it loads. A set it names that this run has not read yet is read here, which is
     * what brings a booth's rows back after a restart even before anyone opens it.
     */
    public static void boothReferences(ServerLevel level, BlockPos pos, Map<String, DjWorkshopSource> sources) {
        MinecraftServer server = level.getServer();
        for (DjWorkshopSource source : sources.values()) {
            if (!READ.contains(source)) {
                remember(source.setId(), source.contentSha256());
            }
        }
        References references = References.get(server);
        if (references.put(boothKey(level.dimension(), pos), sources)) {
            publishProtection(references);
        }
    }

    /** The booth is gone for good (broken or replaced): its sets no longer hold the cache clean-up back. */
    static void forgetBooth(ServerLevel level, BlockPos pos) {
        References references = References.get(level.getServer());
        if (references.remove(boothKey(level.dimension(), pos))) {
            publishProtection(references);
        }
    }

    /** The world is up: every set a booth in it lists is read back from the cache, loaded chunk or not. */
    public static void onServerStarted(ServerStartedEvent event) {
        References references = References.get(event.getServer());
        if (!references.inventoried) {
            int kept = references.inventory(cachedFolders(MusicBattleTracks.worldStorageDirectory()));
            if (kept > 0) {
                MusicBattleMod.LOGGER.info("Blocky Music: {} Workshop cache folder(s) from before DJ booths kept their "
                        + "sets stay protected from the cache clean-up", kept);
            }
        }
        int sets = 0;
        for (DjWorkshopSource source : references.allSources()) {
            if (!READ.contains(source)) {
                remember(source.setId(), source.contentSha256());
                sets++;
            }
        }
        publishProtection(references);
        if (sets > 0) {
            MusicBattleMod.LOGGER.info("Blocky Music: {} cached Workshop set(s) of DJ booths found again, {} beatmap(s)",
                    sets, TRACKS.size());
        }
    }

    static void onServerStopping() {
        TRACKS.clear();
        SOURCES.clear();
        READ.clear();
        SEARCHED.clear();
        ServerBeatmapTransfers.protectCache(Set.of());
    }

    private static void publishProtection(References references) {
        ServerBeatmapTransfers.protectCache(references.protectedHashes());
    }

    /** The content hashes of every set folder in {@code <worldStorage>/workshop_cache}; empty when there is none. */
    static List<String> cachedFolders(Path worldStorage) {
        List<String> hashes = new ArrayList<>();
        if (worldStorage == null) {
            return hashes;
        }
        Path root = worldStorage.resolve(ServerBeatmapTransfers.CACHE_DIRECTORY).toAbsolutePath().normalize();
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            return hashes;
        }
        try (var stream = Files.newDirectoryStream(root)) {
            for (Path folder : stream) {
                String name = String.valueOf(folder.getFileName());
                if (SHA256.matcher(name).matches() && Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)) {
                    hashes.add(name);
                }
            }
        } catch (IOException | SecurityException ignored) {
            // An unreadable cache protects nothing extra; the booths' own records still do.
        }
        hashes.sort(null);
        return hashes;
    }

    static String boothKey(ResourceKey<Level> dimension, BlockPos pos) {
        return dimension.location() + "|" + pos.asLong();
    }

    /**
     * Every booth's cached Workshop references in one world, kept with the overworld's data. A booth's own NBT is only
     * readable while its chunk is loaded; the clean-up and a restart need the whole world's.
     */
    static final class References extends SavedData {
        static final String DATA_NAME = "blocky_music_dj_workshop_sets";
        private final Map<String, Map<String, DjWorkshopSource>> booths = new LinkedHashMap<>();
        /**
         * Cache folders that were there when this world first started with booth records (0.1.0-beta.1 or older kept
         * none). A booth from then may sit in a chunk nobody has loaded since, so its sets cannot be known; those
         * folders are kept as 0.1.0-beta.1 kept them, and only what arrives later is subject to the quota.
         */
        private final Set<String> preUpgrade = new java.util.TreeSet<>();
        /** Whether {@link #preUpgrade} has been taken; it is taken once per world. */
        boolean inventoried;

        static References get(MinecraftServer server) {
            return server.overworld().getDataStorage().computeIfAbsent(
                    new SavedData.Factory<>(References::new, References::load), DATA_NAME);
        }

        /** True when the booth's entry changed. An empty map removes it. */
        boolean put(String booth, Map<String, DjWorkshopSource> sources) {
            if (sources.isEmpty()) {
                return remove(booth);
            }
            Map<String, DjWorkshopSource> copy = new LinkedHashMap<>(sources);
            if (copy.equals(booths.get(booth))) {
                return false;
            }
            booths.put(booth, copy);
            setDirty();
            return true;
        }

        boolean remove(String booth) {
            if (booths.remove(booth) == null) {
                return false;
            }
            setDirty();
            return true;
        }

        DjWorkshopSource sourceOf(String trackId) {
            for (Map<String, DjWorkshopSource> booth : booths.values()) {
                DjWorkshopSource source = booth.get(trackId);
                if (source != null) {
                    return source;
                }
            }
            return null;
        }

        /** Takes the one-time list of cache folders from before the upgrade; returns how many there were. */
        int inventory(Collection<String> cachedHashes) {
            preUpgrade.clear();
            preUpgrade.addAll(cachedHashes);
            inventoried = true;
            setDirty();
            return preUpgrade.size();
        }

        /** The cached folders the clean-up must keep: every booth's sets and the folders from before the upgrade. */
        Set<String> protectedHashes() {
            Set<String> hashes = new HashSet<>(preUpgrade);
            for (DjWorkshopSource source : allSources()) {
                hashes.add(source.contentSha256());
            }
            return hashes;
        }

        List<DjWorkshopSource> allSources() {
            Set<DjWorkshopSource> all = new HashSet<>();
            booths.values().forEach(booth -> all.addAll(booth.values()));
            return new ArrayList<>(all);
        }

        @Override
        public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
            ListTag list = new ListTag();
            booths.forEach((booth, sources) -> {
                CompoundTag entry = new CompoundTag();
                entry.putString("Booth", booth);
                entry.put(DjWorkshopSource.TAG, DjWorkshopSource.write(sources));
                list.add(entry);
            });
            tag.put("Booths", list);
            if (inventoried) {
                ListTag kept = new ListTag();
                preUpgrade.forEach(sha -> kept.add(net.minecraft.nbt.StringTag.valueOf(sha)));
                tag.put("PreUpgradeCache", kept);
            }
            return tag;
        }

        static References load(CompoundTag tag, HolderLookup.Provider registries) {
            References references = new References();
            ListTag list = tag.getList("Booths", Tag.TAG_COMPOUND);
            for (int index = 0; index < list.size(); index++) {
                CompoundTag entry = list.getCompound(index);
                String booth = entry.getString("Booth");
                Map<String, DjWorkshopSource> sources = DjWorkshopSource.read(entry, DjWorkshopSource.TAG,
                        DjPlaylistEntry.MAX_TRACK_ID_LENGTH);
                if (!booth.isBlank() && !sources.isEmpty()) {
                    references.booths.put(booth, sources);
                }
            }
            if (tag.contains("PreUpgradeCache", Tag.TAG_LIST)) {
                references.inventoried = true;
                ListTag kept = tag.getList("PreUpgradeCache", Tag.TAG_STRING);
                for (int index = 0; index < kept.size(); index++) {
                    String sha = kept.getString(index);
                    if (SHA256.matcher(sha).matches()) {
                        references.preUpgrade.add(sha);
                    }
                }
            }
            return references;
        }
    }
}

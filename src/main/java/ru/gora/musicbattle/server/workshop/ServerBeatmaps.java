package ru.gora.musicbattle.server.workshop;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.server.MinecraftServer;
import ru.gora.musicbattle.MusicBattleMod;
import ru.gora.musicbattle.server.MusicBattleServerConfig;
import ru.gora.musicbattle.server.MusicBattleTracks;
import ru.gora.musicbattle.workshop.BeatmapManifest;
import ru.gora.musicbattle.workshop.BeatmapPackage;
import ru.gora.musicbattle.workshop.BeatmapPackageException;
import ru.gora.musicbattle.workshop.WorkshopLayout;
import ru.gora.musicbattle.workshop.WorkshopSignature;

/**
 * The Workshop sets installed on this server (spec 06 section 9.1).
 *
 * <p>Sets live in {@code <world>/blocky_music/workshop/ws_<setId>/}, written by
 * {@link BeatmapPackage#install(Path, Path)}, and become part of the server catalogue through
 * {@link MusicBattleTracks#loadDirectory(Path, MusicBattleTracks.Source)} with
 * {@link MusicBattleTracks.Source#WORKSHOP}. This class owns everything the manifest does not say: who added a set,
 * when, whether a client has confirmed it is still available, and the quotas.
 *
 * <p>The index is {@code <world>/blocky_music/server_beatmaps.index} — JSON content under a name that does not end
 * in {@code .json}, because the track loader reads every {@code *.json} at depth two under {@code workshop/} as a
 * chart and would otherwise turn the index into a bogus track named "index".
 *
 * <p>Threading (review of 06 section 9.2, applied to the index as well): every field here is owned by the server
 * thread. A change builds an immutable snapshot with a version and hands it to one dedicated single-thread
 * executor, which writes {@code <file>.<uuid>.tmp}, fsyncs and moves it atomically; a snapshot older than the last
 * written version is skipped, so two writes can never land out of order. {@link #shutdown} drains it.
 */
public final class ServerBeatmaps {
    /** JSON content, deliberately not a {@code *.json} name (see the class comment). */
    public static final String INDEX_FILE_NAME = "server_beatmaps.index";
    public static final int FORMAT_VERSION = 1;
    private static final long MAX_INDEX_BYTES = 8L * 1024 * 1024;

    /** Why an offer was refused. The client turns these into {@code music_battle.workshop.error.<code>} text. */
    public static final String DENIED_NO_PERMISSION = "no_permission";
    public static final String DENIED_DISABLED = "server_beatmaps_disabled";
    public static final String DENIED_EXTERNAL_AUDIO = "external_audio";
    public static final String DENIED_QUOTA = "server_quota";
    public static final String DENIED_UNSIGNED = "unsigned_package";
    public static final String DENIED_WITHDRAWN = "withdrawn";
    public static final String DENIED_BUSY = "transfer_busy";
    public static final String DENIED_INVALID = "invalid_package";

    /** One row of the index. {@code addedAt} and {@code verifiedAt} are epoch milliseconds. */
    public record Entry(
            long setId,
            int revision,
            String contentSha256,
            String title,
            String artist,
            String mapper,
            String audioPolicy,
            boolean hasCover,
            boolean explicit,
            List<Chart> charts,
            String addedByUuid,
            String addedByName,
            long addedAt,
            long sizeBytes,
            boolean withdrawn,
            long verifiedAt
    ) {
        public Entry {
            charts = List.copyOf(charts);
        }

        public String folderName() {
            return WorkshopLayout.setFolderName(setId);
        }

        /** A set no client has confirmed for seven days is shown as unverified (spec 08 A5.1). */
        public boolean unverified(long nowMillis) {
            return verifiedAt <= 0 || nowMillis - verifiedAt > 7L * 24 * 60 * 60 * 1000L;
        }
    }

    /** One difficulty as the vote screen and the Server beatmaps tab show it. */
    public record Chart(String key, String name, double stars) {
    }

    /** The two quota values, in one place so the {@code server-beatmaps} check can run without a config spec. */
    record Limits(int maxSets, long quotaBytes) {
        static Limits fromConfig() {
            return new Limits(MusicBattleServerConfig.serverBeatmapLimit(), MusicBattleServerConfig.serverBeatmapQuotaBytes());
        }
    }

    private static volatile Limits limitsOverride;

    static void overrideLimits(Limits value) {
        limitsOverride = value;
    }

    static Limits limits() {
        Limits override = limitsOverride;
        return override != null ? override : Limits.fromConfig();
    }

    private static final Map<Long, Entry> ENTRIES = new LinkedHashMap<>();
    private static final AtomicLong VERSION = new AtomicLong();
    private static volatile MinecraftServer boundServer;
    private static volatile Path indexFile;
    private static ExecutorService writer;
    private static long writtenVersion;

    private ServerBeatmaps() {
    }

    // ---------------------------------------------------------------- lifecycle

    /** Server thread. Reads the index of the world that is starting; a missing or broken file starts empty. */
    public static synchronized void bind(MinecraftServer server) {
        boundServer = server;
        ENTRIES.clear();
        VERSION.set(0);
        writtenVersion = 0;
        Path storage = MusicBattleTracks.worldStorageDirectory();
        indexFile = storage == null ? null : storage.resolve(INDEX_FILE_NAME).toAbsolutePath().normalize();
        if (indexFile == null) {
            return;
        }
        try {
            Files.createDirectories(indexFile.getParent());
            read();
        } catch (IOException | RuntimeException exception) {
            MusicBattleMod.LOGGER.warn("Unable to read the Blocky Music server beatmaps index.", exception);
        }
        reconcile();
    }

    /** Server thread. Drains the writer so the last snapshot is on disk before the world closes. */
    public static synchronized void shutdown() {
        ExecutorService pending = writer;
        writer = null;
        boundServer = null;
        if (pending != null) {
            pending.shutdown();
            try {
                if (!pending.awaitTermination(10, TimeUnit.SECONDS)) {
                    MusicBattleMod.LOGGER.warn("The Blocky Music server beatmaps index was still writing at shutdown.");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        ENTRIES.clear();
        indexFile = null;
    }

    public static boolean bound() {
        return indexFile != null;
    }

    /** Only for the checks: binds the index to a folder without a running server. */
    static synchronized void bindForTest(Path worldStorage) {
        boundServer = null;
        ENTRIES.clear();
        VERSION.set(0);
        writtenVersion = 0;
        indexFile = worldStorage == null ? null : worldStorage.resolve(INDEX_FILE_NAME).toAbsolutePath().normalize();
        if (indexFile == null) {
            return;
        }
        try {
            Files.createDirectories(indexFile.getParent());
            read();
        } catch (IOException | RuntimeException exception) {
            MusicBattleMod.LOGGER.warn("Unable to read the Blocky Music server beatmaps index.", exception);
        }
    }

    /** Only for the checks: waits until the queued index write has landed. */
    static void drainForTest() {
        ExecutorService pending = writer;
        if (pending == null) {
            return;
        }
        try {
            pending.submit(() -> {
            }).get(10, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // A drained queue that threw has already logged; the check reads what is on disk.
        }
    }

    /** Only for the checks: records an entry exactly as a finished install would, without the catalogue reload. */
    static synchronized void putForTest(Entry entry) {
        ENTRIES.put(entry.setId(), entry);
        persist();
    }

    // ---------------------------------------------------------------- reads

    public static synchronized List<Entry> all() {
        List<Entry> entries = new ArrayList<>(ENTRIES.values());
        entries.sort(Comparator.comparing((Entry entry) -> entry.title().toLowerCase(Locale.ROOT))
                .thenComparingLong(Entry::setId));
        return List.copyOf(entries);
    }

    public static synchronized Optional<Entry> bySetId(long setId) {
        return Optional.ofNullable(ENTRIES.get(setId));
    }

    public static synchronized Optional<Entry> byContentSha256(String contentSha256) {
        if (contentSha256 == null || contentSha256.isBlank()) {
            return Optional.empty();
        }
        return ENTRIES.values().stream().filter(entry -> entry.contentSha256().equals(contentSha256)).findFirst();
    }

    /** True when exactly this content is already installed: the {@code have} answer of an offer. */
    public static boolean has(long setId, String contentSha256) {
        return bySetId(setId).filter(entry -> entry.contentSha256().equals(contentSha256)).isPresent();
    }

    public static synchronized long totalBytes() {
        return ENTRIES.values().stream().mapToLong(Entry::sizeBytes).sum();
    }

    public static synchronized int size() {
        return ENTRIES.size();
    }

    /**
     * A catalogue row for the vote screen and the DJ picker, with the workshop identity filled in from the index
     * (spec 06 section 9.4): the set id, its content hash and the mapper's name, so the row can carry a source chip
     * and a client can answer a battle asset offer from what it already owns.
     */
    public static ru.gora.musicbattle.data.TrackSummary summary(MusicBattleTracks.Track track) {
        long setId = track.workshopSetId();
        if (setId <= 0) {
            return track.summary();
        }
        Entry entry = bySetId(setId).orElse(null);
        return entry == null ? track.summary() : track.summary(entry.contentSha256(), entry.mapper());
    }

    /** Withdrawn server beatmaps are kept on disk but never offered in a vote (spec 08 A5.1). */
    public static boolean hiddenFromVotes(MusicBattleTracks.Track track) {
        long setId = track.workshopSetId();
        return setId > 0 && bySetId(setId).map(Entry::withdrawn).orElse(false);
    }

    /** The chart ids of a set the vote screen may offer, empty when the set is withdrawn or missing. */
    public static synchronized List<String> chartIds(long setId) {
        Entry entry = ENTRIES.get(setId);
        if (entry == null || entry.withdrawn()) {
            return List.of();
        }
        List<String> ids = new ArrayList<>(entry.charts().size());
        for (Chart chart : entry.charts()) {
            ids.add(WorkshopLayout.chartId(setId, chart.key()));
        }
        return List.copyOf(ids);
    }

    // ---------------------------------------------------------------- install

    /**
     * Whether a fresh set would fit. Checked before the server answers {@code need}, so a player never uploads
     * megabytes into a refusal.
     */
    public static synchronized Optional<String> quotaRefusal(long incomingBytes) {
        if (ENTRIES.size() >= limits().maxSets()) {
            return Optional.of(DENIED_QUOTA);
        }
        if (totalBytes() + Math.max(0L, incomingBytes) > limits().quotaBytes()) {
            return Optional.of(DENIED_QUOTA);
        }
        return Optional.empty();
    }

    /**
     * Installs package bytes into the world (blocking: call it off the server thread).
     *
     * <p>Spec 08 A5.3 in full: the archive carries a workshop signature over exactly the offered
     * {@code (setId, revision, contentSha256)}; the manifest agrees with the offer; the package validates and
     * unpacks through {@link BeatmapPackage}, which stages into a temporary root and swaps the finished folder in
     * with one rename; sets with {@code external} audio are refused, because the server has no audio to play.
     * Returns the new index entry; the caller applies it on the server thread with {@link #applyInstalled}.
     */
    public static Entry install(Path archive, long setId, int revision, String contentSha256,
                                UUID addedBy, String addedByName, Path worldStorage) throws IOException {
        if (!WorkshopSignature.verifyPackage(archive, setId, revision, contentSha256)) {
            throw new BeatmapPackageException(BeatmapPackageException.Reason.INVALID_MANIFEST, DENIED_UNSIGNED);
        }
        BeatmapManifest manifest = BeatmapPackage.readManifest(archive);
        String refusal = refusalFor(manifest, setId, revision, contentSha256);
        if (refusal != null) {
            throw new BeatmapPackageException(BeatmapPackageException.Reason.INVALID_MANIFEST, refusal);
        }
        BeatmapPackage.InstallResult result = BeatmapPackage.install(archive, worldStorage);
        List<Chart> charts = new ArrayList<>();
        for (BeatmapManifest.Chart chart : manifest.charts()) {
            charts.add(new Chart(chart.key(), chart.difficulty(), 0.0));
        }
        long bytes = folderBytes(result.directory());
        return new Entry(setId, revision, contentSha256, manifest.title(), manifest.artist(), manifest.mapper().name(),
                manifest.audio().policy().id(), manifest.hasCover(), false, charts,
                addedBy == null ? "" : addedBy.toString(), addedByName == null ? "" : addedByName,
                System.currentTimeMillis(), bytes, false, System.currentTimeMillis());
    }

    /**
     * Everything the manifest itself has to satisfy once the signature has been accepted: it must describe exactly
     * the offered set, and a set whose players bring their own audio can never live on a server, because the server
     * would have nothing to play. Returns the refusal code, or null when the package may be installed.
     */
    static String refusalFor(BeatmapManifest manifest, long setId, int revision, String contentSha256) {
        if (manifest.setId() != setId || manifest.revision() != revision
                || !manifest.contentSha256().equals(contentSha256)) {
            return DENIED_INVALID;
        }
        return manifest.audio().policy() == BeatmapManifest.AudioPolicy.INCLUDED ? null : DENIED_EXTERNAL_AUDIO;
    }

    /** Server thread: records a finished install, reloads the catalogue and persists the index. */
    public static synchronized void applyInstalled(Entry entry) {
        ENTRIES.put(entry.setId(), entry);
        persist();
        MusicBattleTracks.reloadServerCatalog();
    }

    // ---------------------------------------------------------------- remove and takedowns

    /** Why a removal was refused, or empty when it went through. Server thread. */
    public static synchronized Optional<String> remove(long setId) throws IOException {
        Entry entry = ENTRIES.get(setId);
        if (entry == null) {
            return Optional.of("not_installed");
        }
        for (String chartId : chartIds(setId)) {
            if (ru.gora.musicbattle.server.BattleManager.isTrackInUse(chartId)
                    || ru.gora.musicbattle.server.DjPlaybackManager.isTrackInUse(chartId)) {
                return Optional.of("in_use");
            }
        }
        Path root = MusicBattleTracks.serverBeatmapsDirectory();
        Path folder = root.resolve(entry.folderName()).toAbsolutePath().normalize();
        if (!folder.getParent().equals(root)) {
            return Optional.of("unsafe_path");
        }
        if (Files.isSymbolicLink(folder)) {
            Files.delete(folder);
        } else {
            BeatmapPackage.deleteTree(folder, root);
        }
        ENTRIES.remove(setId);
        persist();
        MusicBattleTracks.reloadServerCatalog();
        return Optional.empty();
    }

    /**
     * A client told the server this content is gone from the workshop (spec 08 A5.1). Advisory: the set stays on
     * disk, is hidden from votes and is shown as withdrawn until an operator removes or keeps it.
     */
    public static synchronized boolean markWithdrawn(long setId, String contentSha256, boolean withdrawn) {
        Entry entry = ENTRIES.get(setId);
        if (entry == null || !entry.contentSha256().equals(contentSha256) || entry.withdrawn() == withdrawn) {
            return false;
        }
        ENTRIES.put(setId, new Entry(entry.setId(), entry.revision(), entry.contentSha256(), entry.title(), entry.artist(),
                entry.mapper(), entry.audioPolicy(), entry.hasCover(), entry.explicit(), entry.charts(), entry.addedByUuid(),
                entry.addedByName(), entry.addedAt(), entry.sizeBytes(), withdrawn,
                withdrawn ? entry.verifiedAt() : System.currentTimeMillis()));
        persist();
        return true;
    }

    /** A client confirmed this content is still available: refreshes the seven-day "verified" clock. */
    public static synchronized boolean markVerified(long setId, String contentSha256) {
        Entry entry = ENTRIES.get(setId);
        if (entry == null || !entry.contentSha256().equals(contentSha256)) {
            return false;
        }
        ENTRIES.put(setId, new Entry(entry.setId(), entry.revision(), entry.contentSha256(), entry.title(), entry.artist(),
                entry.mapper(), entry.audioPolicy(), entry.hasCover(), entry.explicit(), entry.charts(), entry.addedByUuid(),
                entry.addedByName(), entry.addedAt(), entry.sizeBytes(), false, System.currentTimeMillis()));
        persist();
        return true;
    }

    // ---------------------------------------------------------------- index file

    /** Drops rows whose folder is gone and adds folders the index never heard of (a hand-copied set). */
    private static void reconcile() {
        Path root = MusicBattleTracks.serverBeatmapsDirectory();
        boolean changed = ENTRIES.keySet().removeIf(setId ->
                !Files.isDirectory(root.resolve(WorkshopLayout.setFolderName(setId)), LinkOption.NOFOLLOW_LINKS));
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            if (changed) {
                persist();
            }
            return;
        }
        try (var stream = Files.newDirectoryStream(root)) {
            for (Path folder : stream) {
                String name = String.valueOf(folder.getFileName());
                if (!name.startsWith(WorkshopLayout.SET_FOLDER_PREFIX)
                        || !Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                Path manifestFile = folder.resolve(WorkshopLayout.MANIFEST_FILE_NAME);
                if (!Files.isRegularFile(manifestFile, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                BeatmapManifest manifest = BeatmapManifest.parse(
                        new String(Files.readAllBytes(manifestFile), StandardCharsets.UTF_8));
                if (ENTRIES.containsKey(manifest.setId())) {
                    continue;
                }
                List<Chart> charts = new ArrayList<>();
                for (BeatmapManifest.Chart chart : manifest.charts()) {
                    charts.add(new Chart(chart.key(), chart.difficulty(), 0.0));
                }
                ENTRIES.put(manifest.setId(), new Entry(manifest.setId(), manifest.revision(), manifest.contentSha256(),
                        manifest.title(), manifest.artist(), manifest.mapper().name(), manifest.audio().policy().id(),
                        manifest.hasCover(), false, charts, "", "", Files.getLastModifiedTime(manifestFile).toMillis(),
                        folderBytes(folder), false, 0L));
                changed = true;
                MusicBattleMod.LOGGER.info("Adopted the Blocky Music server beatmap '{}' found on disk.", name);
            }
        } catch (IOException | RuntimeException exception) {
            MusicBattleMod.LOGGER.warn("Unable to scan the Blocky Music server beatmaps folder.", exception);
        }
        if (changed) {
            persist();
        }
    }

    private static void read() throws IOException {
        Path file = indexFile;
        if (file == null || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > MAX_INDEX_BYTES) {
            return;
        }
        JsonElement element = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
        if (!element.isJsonObject()) {
            return;
        }
        JsonObject root = element.getAsJsonObject();
        if (!root.has("sets") || !root.get("sets").isJsonArray()) {
            return;
        }
        for (JsonElement item : root.getAsJsonArray("sets")) {
            if (!item.isJsonObject()) {
                continue;
            }
            JsonObject object = item.getAsJsonObject();
            long setId = longOf(object, "setId", 0);
            if (setId <= 0) {
                continue;
            }
            List<Chart> charts = new ArrayList<>();
            if (object.has("charts") && object.get("charts").isJsonArray()) {
                for (JsonElement chart : object.getAsJsonArray("charts")) {
                    if (!chart.isJsonObject()) {
                        continue;
                    }
                    JsonObject chartObject = chart.getAsJsonObject();
                    String key = stringOf(chartObject, "key", "");
                    if (key.isEmpty()) {
                        continue;
                    }
                    charts.add(new Chart(key, stringOf(chartObject, "name", key), doubleOf(chartObject, "stars", 0.0)));
                }
            }
            ENTRIES.put(setId, new Entry(setId, (int) longOf(object, "revision", 1), stringOf(object, "contentSha256", ""),
                    stringOf(object, "title", ""), stringOf(object, "artist", ""), stringOf(object, "mapper", ""),
                    stringOf(object, "audioPolicy", "included"), booleanOf(object, "hasCover"), booleanOf(object, "explicit"),
                    charts, stringOf(object, "addedByUuid", ""), stringOf(object, "addedByName", ""),
                    longOf(object, "addedAt", 0), longOf(object, "sizeBytes", 0), booleanOf(object, "withdrawn"),
                    longOf(object, "verifiedAt", 0)));
        }
    }

    /** Builds the snapshot on the server thread and queues exactly one ordered write. */
    private static void persist() {
        Path file = indexFile;
        if (file == null) {
            return;
        }
        JsonObject root = new JsonObject();
        root.addProperty("format", FORMAT_VERSION);
        JsonArray sets = new JsonArray();
        for (Entry entry : ENTRIES.values()) {
            JsonObject object = new JsonObject();
            object.addProperty("setId", entry.setId());
            object.addProperty("revision", entry.revision());
            object.addProperty("contentSha256", entry.contentSha256());
            object.addProperty("title", entry.title());
            object.addProperty("artist", entry.artist());
            object.addProperty("mapper", entry.mapper());
            object.addProperty("audioPolicy", entry.audioPolicy());
            object.addProperty("hasCover", entry.hasCover());
            object.addProperty("explicit", entry.explicit());
            JsonArray charts = new JsonArray();
            for (Chart chart : entry.charts()) {
                JsonObject chartObject = new JsonObject();
                chartObject.addProperty("key", chart.key());
                chartObject.addProperty("name", chart.name());
                chartObject.addProperty("stars", chart.stars());
                charts.add(chartObject);
            }
            object.add("charts", charts);
            object.addProperty("addedByUuid", entry.addedByUuid());
            object.addProperty("addedByName", entry.addedByName());
            object.addProperty("addedAt", entry.addedAt());
            object.addProperty("sizeBytes", entry.sizeBytes());
            object.addProperty("withdrawn", entry.withdrawn());
            object.addProperty("verifiedAt", entry.verifiedAt());
            sets.add(object);
        }
        root.add("sets", sets);
        byte[] bytes = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(root)
                .getBytes(StandardCharsets.UTF_8);
        long version = VERSION.incrementAndGet();
        writer().execute(() -> writeSnapshot(file, bytes, version));
    }

    private static synchronized void writeSnapshot(Path file, byte[] bytes, long version) {
        if (version <= writtenVersion) {
            return;
        }
        try {
            ru.gora.musicbattle.workshop.api.AtomicFiles.write(file, bytes, false);
            writtenVersion = version;
        } catch (IOException exception) {
            MusicBattleMod.LOGGER.warn("Unable to write the Blocky Music server beatmaps index.", exception);
        }
    }

    private static synchronized ExecutorService writer() {
        if (writer == null) {
            writer = Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "blocky-music-server-beatmaps");
                thread.setDaemon(true);
                return thread;
            });
        }
        return writer;
    }

    static long folderBytes(Path folder) {
        try (var stream = Files.walk(folder, 2)) {
            return stream.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)).mapToLong(path -> {
                try {
                    return Files.size(path);
                } catch (IOException ignored) {
                    return 0L;
                }
            }).sum();
        } catch (IOException ignored) {
            return 0L;
        }
    }

    private static String stringOf(JsonObject object, String key, String fallback) {
        return object.has(key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsString() : fallback;
    }

    private static long longOf(JsonObject object, String key, long fallback) {
        try {
            return object.has(key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsLong() : fallback;
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static double doubleOf(JsonObject object, String key, double fallback) {
        try {
            return object.has(key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsDouble() : fallback;
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static boolean booleanOf(JsonObject object, String key) {
        try {
            return object.has(key) && object.get(key).isJsonPrimitive() && object.get(key).getAsBoolean();
        } catch (RuntimeException ignored) {
            return false;
        }
    }
}

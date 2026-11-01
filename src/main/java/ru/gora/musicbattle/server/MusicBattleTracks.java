package ru.gora.musicbattle.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import ru.gora.musicbattle.MusicBattleMod;
import ru.gora.musicbattle.audio.NativeAudio;
import ru.gora.musicbattle.audio.codec.PcmStream;
import ru.gora.musicbattle.audio.codec.VorbisDecoder;
import ru.gora.musicbattle.data.BattleNote;
import ru.gora.musicbattle.data.ChartBeat;
import ru.gora.musicbattle.data.TrackEffect;
import ru.gora.musicbattle.data.TrackEffectSettings;
import ru.gora.musicbattle.data.TrackSummary;

public final class MusicBattleTracks {
    public static final String DIRECTORY_NAME = "music_battle_tracks";
    public static final String PERSONAL_DIRECTORY_NAME = "my_tracks";
    /** See {@link #temporaryDirectory}. */
    public static final String TEMPORARY_DIRECTORY_NAME = ".temp";
    public static final String SERVER_DIRECTORY_NAME = "server_tracks";
    public static final String USER_SERVER_DIRECTORY_NAME = "user_tracks";
    public static final String WORLD_STORAGE_DIRECTORY_NAME = "blocky_music";
    private static final String WORLD_STORAGE_MIGRATION_MARKER = ".world_server_storage_migrated";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    /** The most notes one chart holds; the battle payload carries no more, and the editor refuses to save more. */
    public static final int MAX_NOTES = 4096;
    private static final String FNF_PARTIES_SWAPPED = "fnfPartiesSwapped";
    private static final String PLAYER_PARTY_ORDER_VERSION = "playerPartyOrderVersion";
    private static final int CURRENT_PLAYER_PARTY_ORDER_VERSION = 2;
    private static final Map<Path, CachedOggDuration> OGG_DURATION_CACHE = new ConcurrentHashMap<>();
    private static final Map<Path, CachedOggInfo> OGG_INFO_CACHE = new ConcurrentHashMap<>();
    private static final Map<Path, CachedSpeedCopyMark> SPEED_COPY_MARKS = new ConcurrentHashMap<>();
    /** How long a failed conversion is remembered before anything asks for that file again. */
    private static final long SPEED_VARIANT_RETRY_NANOS = TimeUnit.MINUTES.toNanos(5);
    private static final Map<Path, SpeedVariantFailure> SPEED_VARIANT_FAILURES = new ConcurrentHashMap<>();
    /** Targets a worker is building, so two battles on one track never convert over each other. */
    private static final Set<Path> SPEED_VARIANTS_IN_FLIGHT = ConcurrentHashMap.newKeySet();
    private static volatile ExecutorService speedVariantWorker;
    /**
     * How many conversions may be queued or running before a vote stops adding to them. Two players voting two
     * speeds in each of two battles fit; anything beyond that is a backlog no battle would live to hear.
     */
    public static final int SPEED_VARIANT_VOTE_QUEUE_LIMIT = 4;
    /** How long a stopping server gives the worker to stop its conversion and delete its temporary file. */
    private static final long SPEED_VARIANT_STOP_SECONDS = 5;
    /**
     * A temporary file older than this belongs to no running conversion, since every conversion is stopped after
     * {@link #SPEED_VARIANT_TIMEOUT_SECONDS}; it is a leftover of a game that was killed before it could clean up.
     */
    private static final long STALE_PART_MILLIS = TimeUnit.MINUTES.toMillis(10);
    /**
     * The longest one sped-up copy may take. A four-minute song takes about a second and a half on a desktop, so this
     * only ends a conversion that something has gone badly wrong with.
     */
    private static final int SPEED_VARIANT_TIMEOUT_SECONDS = 60;
    /** Identification, comment and setup: the packets of a Vorbis stream before its audio. */
    private static final int VORBIS_HEADER_PACKETS = 3;

    private record SpeedVariantFailure(SpeedRefusal refusal, String reason, long atNanos) {
    }

    private static volatile List<Track> cachedLocalTracks = List.of();
    private static volatile List<Track> cachedServerTracks = List.of();
    private static volatile List<Track> cachedUserServerTracks = List.of();
    private static volatile List<Track> cachedWorkshopTracks = List.of();
    private static volatile List<Track> cachedServerCatalog = List.of();
    private static volatile boolean serverCatalogLoaded;
    private static volatile Path activeWorldStorageDirectory;

    /**
     * Where a catalogue entry came from. {@link #loadDirectory} takes it instead of the old
     * {@code userTrack} flag, so a folder is never read "as" something else and the vote screen can chip the rows.
     * Client state never reaches this: the installed sets of {@link ru.gora.musicbattle.workshop.WorkshopCatalog}
     * are a separate, client-only list (the {@code workshop-catalog-isolation} check).
     */
    public enum Source {
        /** {@code server_tracks}: what the operator put there. */
        SERVER,
        /** {@code user_tracks}: published by a player through the legacy publish flow. */
        USER,
        /** {@code <world>/blocky_music/workshop/ws_<setId>}: a Workshop set added to this server. */
        WORKSHOP
    }

    private MusicBattleTracks() {
    }

    public static Path tracksRootDirectory() {
        return FMLPaths.GAMEDIR.get().resolve(DIRECTORY_NAME).toAbsolutePath().normalize();
    }

    /**
     * {@code music_battle_tracks/.temp}: the mod's own temporary folder, inside the Minecraft folder. Everything the mod
     * keeps on disk only for a moment goes here (a picture made for "Publish to server", a song converted before it is
     * checked), never into the system's temporary folder, so nothing of the mod's is written outside the Minecraft
     * folder. The leading dot keeps the track scan out of it. {@link #clearTemporaryDirectory} empties it when the
     * game starts, which removes whatever a killed game left behind.
     */
    public static Path temporaryDirectory() {
        return tracksRootDirectory().resolve(TEMPORARY_DIRECTORY_NAME).toAbsolutePath().normalize();
    }

    /**
     * Deletes the files directly in {@link #temporaryDirectory} (not links, not folders): nothing in it outlives the
     * launch that made it, so at the next start all of it is a leftover. Failures are ignored; a leftover is harmless.
     */
    public static int clearTemporaryDirectory() {
        Path directory = temporaryDirectory();
        if (!Files.isDirectory(directory, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return 0;
        }
        int deleted = 0;
        // Its own folder, not a speed copy's: those are cleared by NativeAudio.deleteStaleTemporaryFiles.
        try (java.util.stream.Stream<Path> files = Files.list(directory)) {
            for (Path file : files.toList()) {
                try {
                    if (Files.isRegularFile(file, java.nio.file.LinkOption.NOFOLLOW_LINKS) && Files.deleteIfExists(file)) {
                        deleted++;
                    }
                } catch (IOException ignored) {
                    // Held open by another program: the next start tries again.
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // An unreadable folder is reported by whatever needs it next.
        }
        return deleted;
    }

    public static Path tracksDirectory() {
        return tracksRootDirectory().resolve(PERSONAL_DIRECTORY_NAME).toAbsolutePath().normalize();
    }

    public static Path serverTracksDirectory() {
        Path worldStorage = activeWorldStorageDirectory;
        return (worldStorage == null ? legacyServerTracksDirectory() : worldStorage.resolve(SERVER_DIRECTORY_NAME))
                .toAbsolutePath().normalize();
    }

    public static Path userServerTracksDirectory() {
        Path worldStorage = activeWorldStorageDirectory;
        return (worldStorage == null ? legacyUserServerTracksDirectory() : worldStorage.resolve(USER_SERVER_DIRECTORY_NAME))
                .toAbsolutePath().normalize();
    }

    public static Path rawDirectory() {
        return FMLPaths.GAMEDIR.get().resolve("music_battle_raw").toAbsolutePath().normalize();
    }

    /**
     * A path the way a person should see it: relative to the Minecraft folder, with forward slashes.
     * A path from outside the game directory keeps only its last segment. No chat line, screen or
     * error message shows the folders above the instance, whichever machine it came from.
     */
    public static String displayPath(Path path) {
        if (path == null) {
            return "";
        }
        Path game = FMLPaths.GAMEDIR.get().toAbsolutePath().normalize();
        Path absolute = path.toAbsolutePath().normalize();
        Path shown = absolute.startsWith(game) ? game.relativize(absolute) : absolute.getFileName();
        return shown == null ? "" : shown.toString().replace('\\', '/');
    }

    /**
     * A relative path read the way {@link #displayPath} writes it, against the Minecraft folder: the file it names
     * there, or {@code null} when it names none (or would leave the folder). A box that shows a saved song as
     * music_battle_tracks/my_tracks/x/x.ogg must find that song again.
     */
    public static Path gameFolderFile(Path relative) {
        if (relative == null || relative.isAbsolute()) {
            return null;
        }
        Path game = FMLPaths.GAMEDIR.get().toAbsolutePath().normalize();
        Path file = game.resolve(relative).toAbsolutePath().normalize();
        return file.startsWith(game) && !file.equals(game) && Files.isRegularFile(file) ? file : null;
    }

    /**
     * An absolute path starts the text or follows a space, a quote, a bracket, '=' or a ':' that does not open a web
     * address ("Failed:/Users/x"); a file: URI ("file:///Users/x/a.ogg") is a path too, and is folded whole. Never a
     * slash inside a word: the '/' of an already relative path such as music_battle_tracks/song.ogg, or the '//' of a
     * web address, is not the start of a path, and folding it there ate the folder name.
     */
    private static final java.util.regex.Pattern ABSOLUTE_PATH_IN_TEXT =
            java.util.regex.Pattern.compile("(?:(?<![\\w.~:/\\\\-])|(?<=:)(?!//))(?:(?i:file:)(?://)?)?"
                    + "(?:/?[A-Za-z]:[\\\\/]|/)(?:[^\\s\"'<>|*?]+[\\\\/])*[^\\s\"'<>|*?]*");

    /**
     * An exception message with every absolute path in it folded to {@link #displayPath}. The
     * java.nio exceptions carry the full path in their message, and those messages are what the
     * screens print after "ERR:". Same rule for text that is about to travel to another player.
     */
    public static String safeMessage(Throwable error) {
        if (error == null) {
            return "";
        }
        String message = error.getMessage();
        if (message == null || message.isBlank()) {
            return error.getClass().getSimpleName();
        }
        return safeText(message);
    }

    /**
     * Folds every absolute path in a piece of text the way {@link #displayPath} does. A folder name may hold a space
     * ("Application Support", "C:\Users\John Smith"), and where a path ends is then a guess, so the folders this
     * machine knows are folded by their exact text first: the Minecraft folder to nothing (what is below it stays), and
     * every folder above it and the home folder to a bare '/', which the general rule then folds to the file name.
     */
    public static String safeText(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        text = foldKnownFolders(text);
        java.util.regex.Matcher matcher = ABSOLUTE_PATH_IN_TEXT.matcher(text);
        StringBuilder out = new StringBuilder(text.length());
        while (matcher.find()) {
            String found = matcher.group();
            if (found.regionMatches(true, 0, "file:", 0, 5)) {
                // file:///Users/x, file:///C:/x and file:/x name the file system's own path after the scheme.
                found = found.substring(5).replaceFirst("^/+(?=[A-Za-z]:[\\\\/])", "").replaceFirst("^/{2,}", "/");
            }
            String shown;
            try {
                Path path = Path.of(found);
                // C:\Users\x\y.ogg read on macOS or Linux is one odd file name to Path.of, not an absolute path.
                shown = path.isAbsolute() ? displayPath(path) : lastPathSegment(found);
            } catch (RuntimeException invalid) {
                shown = "";
            }
            matcher.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(shown));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static String lastPathSegment(String path) {
        String[] parts = path.split("[\\\\/]+");
        return parts.length == 0 ? "" : parts[parts.length - 1];
    }

    /** One folder this machine knows, as text: {@code inside} for the Minecraft folder, else a folder above it or home. */
    private record KnownFolder(java.util.regex.Pattern pattern, boolean inside) {}

    private record KnownFolders(String key, List<KnownFolder> folders) {}

    private static volatile KnownFolders knownFolders;

    private static String foldKnownFolders(String text) {
        Path game;
        try {
            game = FMLPaths.GAMEDIR.get();
        } catch (RuntimeException notLoaded) {
            game = null;
        }
        String home = System.getProperty("user.home", "");
        String key = game + "\u0000" + home;
        KnownFolders known = knownFolders;
        if (known == null || !known.key().equals(key)) {
            known = new KnownFolders(key, knownFoldersOf(game, home));
            knownFolders = known;
        }
        for (KnownFolder folder : known.folders()) {
            java.util.regex.Matcher matcher = folder.pattern().matcher(text);
            StringBuilder out = null;
            int from = 0;
            while (from <= text.length() && matcher.find(from)) {
                if (out == null) {
                    out = new StringBuilder(text.length());
                }
                out.append(text, from, matcher.start());
                int end = matcher.end();
                if (folder.inside()) {
                    // What is below the Minecraft folder stays, with forward slashes as displayPath writes it.
                    int tail = end;
                    while (tail < text.length() && !Character.isWhitespace(text.charAt(tail))
                            && "\"'<>|*?".indexOf(text.charAt(tail)) < 0) {
                        tail++;
                    }
                    out.append(text.substring(end, tail).replace('\\', '/'));
                    from = tail;
                } else {
                    // A folder above it leaves '/' to start a path, which the general rule folds to the file name.
                    out.append(matcher.group(1) == null ? "" : "/");
                    from = end;
                }
            }
            if (out != null) {
                out.append(text, from, text.length());
                text = out.toString();
            }
        }
        return text;
    }

    /** The longest folder first, so the Minecraft folder is folded before the folders that hold it. */
    private static List<KnownFolder> knownFoldersOf(Path game, String home) {
        Map<String, Boolean> roots = new java.util.LinkedHashMap<>();
        List<Path> above = new ArrayList<>();
        if (game != null) {
            Path absolute = game.toAbsolutePath().normalize();
            for (Path form : withRealPath(absolute)) {
                addForms(roots, form, true);
                for (Path parent = form.getParent(); parent != null && parent.getParent() != null; parent = parent.getParent()) {
                    above.add(parent);
                }
            }
        }
        if (!home.isBlank()) {
            try {
                Path homePath = Path.of(home);
                if (homePath.isAbsolute()) {
                    above.addAll(withRealPath(homePath.normalize()));
                }
            } catch (RuntimeException invalid) {
                // A user.home Path.of rejects names nothing to fold.
            }
        }
        for (Path folder : above) {
            addForms(roots, folder, false);
        }
        List<Map.Entry<String, Boolean>> sorted = new ArrayList<>(roots.entrySet());
        sorted.sort(Comparator.comparingInt((Map.Entry<String, Boolean> entry) -> entry.getKey().length()).reversed());
        List<KnownFolder> folders = new ArrayList<>(sorted.size());
        for (Map.Entry<String, Boolean> entry : sorted) {
            // Starts where a path may start (also after ':' or as a file: address), ends at a separator or where the
            // name stops, so /Users/bob never folds the front of /Users/bobby.
            folders.add(new KnownFolder(java.util.regex.Pattern.compile(
                    "(?<![\\w.~/\\\\%-])(?:file:[\\\\/]*)?" + entry.getKey() + "(?![\\w.~%-])([\\\\/]+)?",
                    java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.UNICODE_CASE), entry.getValue()));
        }
        return folders;
    }

    private static List<Path> withRealPath(Path path) {
        List<Path> forms = new ArrayList<>(2);
        forms.add(path);
        try {
            Path real = path.toRealPath();
            if (!real.equals(path)) {
                forms.add(real); // /var/folders/... is /private/var/folders/... on a Mac, and messages use either.
            }
        } catch (IOException | RuntimeException missing) {
            // A folder that does not exist yet is named in messages only as written.
        }
        return forms;
    }

    /** The folder's text with either separator between its names, and its file: URI spelling (a space as %20). */
    private static void addForms(Map<String, Boolean> roots, Path folder, boolean inside) {
        List<String> spellings = new ArrayList<>(2);
        spellings.add(folder.toString().replace('\\', '/'));
        try {
            String uri = folder.toUri().getRawPath();
            if (uri != null) {
                spellings.add(uri.replaceFirst("^/(?=[A-Za-z]:)", ""));
            }
        } catch (RuntimeException | java.io.IOError invalid) {
            // No URI spelling for this folder.
        }
        for (String spelling : spellings) {
            boolean rooted = spelling.startsWith("/");
            List<String> names = new ArrayList<>();
            for (String name : spelling.split("/+")) {
                if (!name.isEmpty()) {
                    names.add(java.util.regex.Pattern.quote(name));
                }
            }
            if (names.isEmpty()) {
                continue;
            }
            String regex = (rooted ? "[\\\\/]+" : "") + String.join("[\\\\/]+", names);
            // The Minecraft folder wins over the same text as a folder above it.
            roots.merge(regex, inside, Boolean::logicalOr);
        }
    }

    public static Path ensureTracksDirectory() {
        Path directory = tracksDirectory();
        try {
            Path root = tracksRootDirectory();
            Files.createDirectories(root);
            Files.createDirectories(directory);
            Files.createDirectories(rawDirectory());
            migrateLegacyLayout(root);
            repairAccidentallyNestedLayout(root);
            removeGeneratedPracticeTrack(legacyServerTracksDirectory());
        } catch (IOException exception) {
            MusicBattleMod.LOGGER.warn("Unable to create music battle tracks directory '{}'.", directory, exception);
        }
        return directory;
    }

    public static List<Track> loadTracks() {
        Path directory = ensureTracksDirectory();
        cachedLocalTracks = loadDirectory(directory, Source.SERVER);
        return cachedLocalTracks;
    }

    public static synchronized List<Track> reloadServerCatalog() {
        if (activeWorldStorageDirectory == null) {
            clearServerCatalog();
            return cachedServerCatalog;
        }
        ensureWorldTracksDirectory();
        cachedServerTracks = loadDirectory(serverTracksDirectory(), Source.SERVER);
        cachedUserServerTracks = loadDirectory(userServerTracksDirectory(), Source.USER);
        cachedWorkshopTracks = loadDirectory(serverBeatmapsDirectory(), Source.WORKSHOP);
        List<Track> combined = new ArrayList<>(
                cachedServerTracks.size() + cachedUserServerTracks.size() + cachedWorkshopTracks.size());
        combined.addAll(cachedWorkshopTracks);
        combined.addAll(cachedServerTracks);
        combined.addAll(cachedUserServerTracks);
        combined.sort(Comparator.comparing(Track::title, String.CASE_INSENSITIVE_ORDER).thenComparing(Track::id));
        cachedServerCatalog = List.copyOf(combined);
        serverCatalogLoaded = true;
        return cachedServerCatalog;
    }

    /** The world's server beatmaps: {@code <world>/blocky_music/workshop}, the folder {@code BeatmapPackage} installs into. */
    public static Path serverBeatmapsDirectory() {
        Path worldStorage = activeWorldStorageDirectory;
        return worldStorage == null
                ? tracksRootDirectory().resolve(ru.gora.musicbattle.workshop.WorkshopLayout.INSTALLED_DIRECTORY_NAME)
                        .toAbsolutePath().normalize()
                : ru.gora.musicbattle.workshop.WorkshopLayout.installedDirectory(worldStorage);
    }

    /** {@code <world>/blocky_music}, or null when no world is bound (client menus, dedicated server before start). */
    public static Path worldStorageDirectory() {
        return activeWorldStorageDirectory;
    }

    /** Server beatmaps as their own list (the {@code /musicbattle workshop list} command and the vote chips). */
    public static List<Track> workshopTracks() {
        if (!serverCatalogLoaded) {
            reloadServerCatalog();
        }
        return cachedWorkshopTracks;
    }

    public static void onServerAboutToStart(ServerAboutToStartEvent event) {
        bindServerStorage(event.getServer());
    }

    public static void onServerStopped(ServerStoppedEvent event) {
        releaseServerStorage(event.getServer());
        stopSpeedVariantWorker();
    }

    /**
     * Stops the speed-audio worker together with its server, and waits a moment for it.
     *
     * <p>The worker is a daemon thread, so a dedicated server would otherwise exit underneath a running conversion
     * and leave its temporary file in the track folder for good. Interrupting the worker makes the conversion stop
     * within milliseconds and delete that file first. In single player the next world starts a fresh worker on its
     * first request.
     */
    public static void stopSpeedVariantWorker() {
        ExecutorService worker;
        synchronized (MusicBattleTracks.class) {
            worker = speedVariantWorker;
            speedVariantWorker = null;
        }
        if (worker == null) {
            return;
        }
        worker.shutdownNow();
        try {
            if (!worker.awaitTermination(SPEED_VARIANT_STOP_SECONDS, TimeUnit.SECONDS)) {
                MusicBattleMod.LOGGER.warn("A Blocky Music speed audio conversion was still stopping at shutdown.");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        // The queued conversions were dropped without running, and a target left here would read as being made for
        // as long as the game stays open, so nothing would ever ask for it again.
        SPEED_VARIANTS_IN_FLIGHT.clear();
    }

    public static List<Track> serverTracks() {
        if (!serverCatalogLoaded) {
            reloadServerCatalog();
        }
        return cachedServerTracks;
    }

    public static List<Track> userServerTracks() {
        if (!serverCatalogLoaded) {
            reloadServerCatalog();
        }
        return cachedUserServerTracks;
    }

    private static synchronized void bindServerStorage(MinecraftServer server) {
        Path worldStorage = worldStorageDirectory(server);
        if (worldStorage.equals(activeWorldStorageDirectory) && serverCatalogLoaded) {
            return;
        }
        activeWorldStorageDirectory = worldStorage;
        clearServerCatalog();
        try {
            ensureTracksDirectory();
            ensureWorldTracksDirectory();
            migrateLegacyServerStorage(worldStorage);
            removeGeneratedPracticeTrack(serverTracksDirectory());
            reloadServerCatalog();
            MusicBattleMod.LOGGER.info("Blocky Music server tracks are bound to world storage '{}'.", worldStorage);
        } catch (IOException exception) {
            MusicBattleMod.LOGGER.error("Unable to prepare Blocky Music tracks inside world folder '{}'.", worldStorage, exception);
        }
    }

    private static synchronized void releaseServerStorage(MinecraftServer server) {
        Path stoppingStorage = worldStorageDirectory(server);
        if (!stoppingStorage.equals(activeWorldStorageDirectory)) {
            return;
        }
        activeWorldStorageDirectory = null;
        clearServerCatalog();
        OGG_DURATION_CACHE.clear();
        OGG_INFO_CACHE.clear();
    }

    private static Path worldStorageDirectory(MinecraftServer server) {
        Path worldRoot = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        return worldRoot.resolve(WORLD_STORAGE_DIRECTORY_NAME).toAbsolutePath().normalize();
    }

    private static void ensureWorldTracksDirectory() {
        Path worldStorage = activeWorldStorageDirectory;
        if (worldStorage == null) {
            return;
        }
        try {
            Files.createDirectories(worldStorage);
            Files.createDirectories(serverTracksDirectory());
            Files.createDirectories(userServerTracksDirectory());
            Files.createDirectories(serverBeatmapsDirectory());
        } catch (IOException exception) {
            MusicBattleMod.LOGGER.warn("Unable to create Blocky Music world track directories in '{}'.", worldStorage, exception);
        }
    }

    private static void clearServerCatalog() {
        cachedServerTracks = List.of();
        cachedUserServerTracks = List.of();
        cachedWorkshopTracks = List.of();
        cachedServerCatalog = List.of();
        serverCatalogLoaded = false;
    }

    private static Path legacyServerTracksDirectory() {
        return tracksRootDirectory().resolve(SERVER_DIRECTORY_NAME).toAbsolutePath().normalize();
    }

    private static Path legacyUserServerTracksDirectory() {
        return tracksRootDirectory().resolve(USER_SERVER_DIRECTORY_NAME).toAbsolutePath().normalize();
    }

    /**
     * Every chart under {@code directory} (depth 2), tagged with where it came from.
     * Only {@code *.json} files are charts, so nothing else may carry that extension at depth 1 or 2 — the server
     * beatmaps index therefore lives outside this folder, as {@code server_beatmaps.index}.
     *
     * <p>{@code ws_} ids are reserved for Workshop sets: a chart with such an id found in {@code server_tracks},
     * {@code user_tracks} or {@code my_tracks} is skipped with a warning, so a copied workshop folder can never
     * shadow the real set through the first-match {@link #byId}.
     */
    public static List<Track> loadDirectory(Path directory, Source source) {
        List<Track> tracks = new ArrayList<>();
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (var stream = Files.find(directory, 2, (path, attributes) -> attributes.isRegularFile())) {
            // A set copied by hand from a client's Workshop folder - which the server adopts - brings the client's
            // card sidecar along; read as a chart it listed the set a second time as a bogus track without notes.
            stream.filter(path -> path.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".json")
                            && !path.getFileName().toString().equals(
                                    ru.gora.musicbattle.workshop.WorkshopLayout.CARD_SIDECAR_FILE_NAME))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString(), String.CASE_INSENSITIVE_ORDER))
                    .forEach(path -> readTrack(path, directory, source).ifPresent(track -> {
                        if (source != Source.WORKSHOP
                                && track.id().startsWith(ru.gora.musicbattle.workshop.WorkshopLayout.SET_FOLDER_PREFIX)) {
                            MusicBattleMod.LOGGER.warn(
                                    "Skipping track '{}' in '{}': ids starting with 'ws_' belong to Workshop sets.",
                                    track.id(), displayPath(directory));
                            return;
                        }
                        tracks.add(track);
                    }));
        } catch (IOException exception) {
            MusicBattleMod.LOGGER.warn("Unable to list music battle tracks in '{}'.", directory, exception);
        }
        return List.copyOf(tracks);
    }

    public static List<Track> availableTracks() {
        if (!serverCatalogLoaded) {
            return reloadServerCatalog();
        }
        return cachedServerCatalog;
    }

    public static Optional<Track> byId(String id) {
        return availableTracks().stream().filter(track -> track.id().equals(id)).findFirst();
    }

    public static Optional<Path> audioPath(Track track) {
        return resolveAsset(track.audioFile(), track.id(), track.sourceDirectory());
    }

    public static Optional<Path> definitionPath(Track track) {
        Path root = track.sourceDirectory().toAbsolutePath().normalize();
        Path definition = track.definitionPath().toAbsolutePath().normalize();
        return definition.startsWith(root) && Files.isRegularFile(definition) ? Optional.of(definition) : Optional.empty();
    }

    /**
     * The audio for a speed, converting the track if that has not been done yet.
     *
     * <p>The conversion runs on the calling thread and takes a second or more, so this belongs on a worker thread
     * only. The battle path uses {@link #battleAudio} and {@link #requestSpeedVariant} instead, which never wait for
     * a conversion.
     */
    public static Optional<AudioAsset> audioAsset(Track track, float speedMultiplier) {
        Optional<Path> original = audioPath(track);
        if (original.isEmpty()) {
            return Optional.empty();
        }
        float speed = normalizeSpeed(speedMultiplier);
        if (speed <= 1.01F) {
            return Optional.of(new AudioAsset(track.audioFile(), original.get()));
        }

        try {
            Path target = speedVariantPath(original.get(), speed);
            createSpeedVariant(original.get(), target, speed);
            return Optional.of(variantAsset(track, target));
        } catch (IOException exception) {
            MusicBattleMod.LOGGER.warn("Unable to prepare Blocky Music speed audio for track '{}'.", track.id(), exception);
            return Optional.empty();
        }
    }

    /**
     * The audio a battle will really play, and - when the speed the players voted for is not ready - why it is
     * playing at normal speed instead.
     *
     * @param asset the file to send, or {@code null} when even the unmodified audio could not be resolved
     * @param speed the speed this asset actually plays at, which the rest of the battle has to be scaled to
     * @param refusal why the requested speed was given up on, or {@code null} when it was honoured
     * @param detail the technical reason behind the refusal, for the log only - a player gets a translated line
     */
    public record BattleAudio(AudioAsset asset, float speed, SpeedRefusal refusal, String detail) {
        public boolean speedRefused() {
            return refusal != null;
        }
    }

    /**
     * Why a battle plays at normal speed although its players voted for more. Each one tells the players something
     * different, because what the server owner can do about it is different: wait for the next battle, or read the
     * server log.
     */
    public enum SpeedRefusal {
        /** The sped-up copy is being made right now and was not finished in the time the battle waits for it. */
        PREPARING("music_battle.l10n.server.speed_audio_preparing"),
        /** The conversion failed (a damaged song file, a full disk); the reason is in the server log. */
        CONVERSION_FAILED("music_battle.l10n.server.speed_audio_failed");

        private final String messageKey;

        SpeedRefusal(String messageKey) {
            this.messageKey = messageKey;
        }

        /** The chat line the players are shown, with the requested speed as its one argument. */
        public String messageKey() {
            return messageKey;
        }
    }

    /**
     * Picks the audio for a battle from the files that already exist.
     *
     * <p>This runs on the server thread while a battle is being set up, so it never converts anything: a conversion
     * takes a second or more and the whole server would stand still for all of it. The sped-up copy is built in the
     * background from {@link #requestSpeedVariant}, which the vote asks for as soon as somebody picks a speed. A
     * battle the two of them already agreed to is never refused over the speed - it loses the modifier and is told
     * why.
     */
    public static BattleAudio battleAudio(Track track, float speedMultiplier) {
        Optional<Path> original = audioPath(track);
        if (original.isEmpty()) {
            // Nothing to fall back to: a map whose audio file is missing is broken rather than too fast.
            return new BattleAudio(null, 1.0F, null, null);
        }
        float speed = normalizeSpeed(speedMultiplier);
        AudioAsset normal = new AudioAsset(track.audioFile(), original.get());
        if (speed <= 1.01F) {
            return new BattleAudio(normal, 1.0F, null, null);
        }
        Optional<AudioAsset> prepared = preparedAudio(track, speed);
        if (prepared.isPresent()) {
            return new BattleAudio(prepared.get(), speed, null, null);
        }
        // Not ready. Ask for it anyway: the battle waits a few seconds for a copy that is on its way, and the next
        // battle on this track and speed has it waiting either way.
        SpeedVariantFailure failure = requestSpeedVariantFailure(track, speed);
        return failure == null
                ? new BattleAudio(normal, 1.0F, SpeedRefusal.PREPARING, null)
                : new BattleAudio(normal, 1.0F, failure.refusal(), failure.reason());
    }

    /** The audio for a speed only if it is already on disk; it never starts a conversion. */
    public static Optional<AudioAsset> preparedAudio(Track track, float speedMultiplier) {
        Optional<Path> original = audioPath(track);
        if (original.isEmpty()) {
            return Optional.empty();
        }
        float speed = normalizeSpeed(speedMultiplier);
        if (speed <= 1.01F) {
            return Optional.of(new AudioAsset(track.audioFile(), original.get()));
        }
        Path target = speedVariantPath(original.get(), speed);
        return speedVariantReady(original.get(), target, speed)
                ? Optional.of(variantAsset(track, target))
                : Optional.empty();
    }

    /**
     * Starts building the sped-up copy of a track on a worker thread, and says when that cannot work.
     *
     * <p>Called when a speed is voted for, which leaves the conversion the length of the vote to finish in, and
     * again when a battle starts without it, so a server that was asked late still ends up with the file. The
     * same target is only ever built once at a time, and a machine that cannot build it is remembered for a
     * while rather than searched for again on every battle.
     *
     * <p>This is the vote's request, and it is skipped while {@link #SPEED_VARIANT_VOTE_QUEUE_LIMIT} conversions are
     * already waiting; the battle's own request in {@link #battleAudio} is never capped.
     *
     * @return why this machine cannot produce the variant, or {@code null} when it exists, is being made or the
     *         queue is full
     */
    public static SpeedRefusal requestSpeedVariant(Track track, float speedMultiplier) {
        if (SPEED_VARIANTS_IN_FLIGHT.size() >= SPEED_VARIANT_VOTE_QUEUE_LIMIT) {
            // A vote is only a head start, so a full queue simply skips it: the battle asks again when it starts,
            // and that request is never refused. Without the cap, votes from many battles, or from a client that
            // votes for everything, would line up conversions without end.
            return null;
        }
        SpeedVariantFailure failure = requestSpeedVariantFailure(track, speedMultiplier);
        return failure == null ? null : failure.refusal();
    }

    private static SpeedVariantFailure requestSpeedVariantFailure(Track track, float speedMultiplier) {
        float speed = normalizeSpeed(speedMultiplier);
        Optional<Path> original = audioPath(track);
        if (speed <= 1.01F || original.isEmpty()) {
            return null;
        }
        Path source = original.get();
        Path target = speedVariantPath(source, speed);
        if (speedVariantReady(source, target, speed)) {
            return null;
        }
        SpeedVariantFailure failure = SPEED_VARIANT_FAILURES.get(target);
        if (failure != null) {
            if (System.nanoTime() - failure.atNanos() < SPEED_VARIANT_RETRY_NANOS) {
                return failure;
            }
            SPEED_VARIANT_FAILURES.remove(target, failure);
        }
        if (!SPEED_VARIANTS_IN_FLIGHT.add(target)) {
            return null;
        }
        speedVariantWorker().execute(() -> {
            try {
                createSpeedVariant(source, target, speed);
                SPEED_VARIANT_FAILURES.remove(target);
            } catch (IOException | RuntimeException exception) {
                MusicBattleMod.LOGGER.warn("Unable to prepare Blocky Music speed audio for track '{}'.", track.id(), exception);
                SPEED_VARIANT_FAILURES.put(target, new SpeedVariantFailure(
                        SpeedRefusal.CONVERSION_FAILED, safeMessage(exception), System.nanoTime()));
            } finally {
                SPEED_VARIANTS_IN_FLIGHT.remove(target);
            }
        });
        return null;
    }

    private static ExecutorService speedVariantWorker() {
        ExecutorService existing = speedVariantWorker;
        if (existing != null) {
            return existing;
        }
        synchronized (MusicBattleTracks.class) {
            if (speedVariantWorker == null) {
                // One conversion at a time, because two only fight over the same processor and disk, and a daemon
                // thread, because a half-finished conversion must never hold the game open on shutdown.
                speedVariantWorker = Executors.newSingleThreadExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "blocky-music-speed-audio");
                    thread.setDaemon(true);
                    thread.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 2));
                    return thread;
                });
            }
            return speedVariantWorker;
        }
    }

    private static AudioAsset variantAsset(Track track, Path target) {
        String relativeName = track.sourceDirectory()
                .relativize(target.toAbsolutePath().normalize()).toString().replace('\\', '/');
        return new AudioAsset(relativeName, target);
    }

    /**
     * Whether the sped-up copy on disk is finished, newer than its source, made by this build's converter and really at
     * this speed. A copy an older build made (ffmpeg's atempo, or this converter before its timeline matched the game's)
     * is stale, so the next battle that asks for it has it made again, once: the new copy carries the mark.
     */
    private static boolean speedVariantReady(Path source, Path target, float speed) {
        try {
            return Files.isRegularFile(target)
                    && Files.size(target) > 0L
                    && Files.getLastModifiedTime(target).compareTo(Files.getLastModifiedTime(source)) >= 0
                    && isValidSpeedVariant(source, target, speed);
        } catch (IOException exception) {
            return false;
        }
    }

    /**
     * Makes the sped-up copy, pitch kept, with the mod's own converter ({@link NativeAudio}). It works on the calling
     * thread, so it belongs on a worker thread - never on the server thread.
     */
    public static void createSpeedVariant(Path source, Path target, float speedMultiplier) throws IOException {
        float speed = normalizeSpeed(speedMultiplier);
        if (speed <= 1.01F) {
            return;
        }
        source = source.toAbsolutePath().normalize();
        target = target.toAbsolutePath().normalize();
        if (speedVariantReady(source, target, speed)) {
            return;
        }

        // Leftovers of the old fixed-name temporary files, which a stopped conversion used to leave behind.
        Files.deleteIfExists(target.resolveSibling(target.getFileName() + ".tmp"));
        Files.deleteIfExists(target.resolveSibling(target.getFileName() + ".tmp.ogg"));
        // The converter's own temporary files that a killed server left here. The converter clears its leftovers in
        // any folder after an hour; next to a battle's song they go after ten minutes, since no copy takes that long.
        NativeAudio.deleteStaleTemporaryFiles(target.getParent(), STALE_PART_MILLIS);
        NativeAudio.convertToVorbis(source, target, speed, SPEED_VARIANT_TIMEOUT_SECONDS);
        // Checked here rather than on the next battle, so a copy of the wrong length, or without this build's mark,
        // is a reported failure with a reason instead of a file that is silently rebuilt every time.
        String problem = speedVariantProblem(source, target, speed);
        if (problem != null) {
            Files.deleteIfExists(target);
            throw new IOException("The converter wrote a copy that " + problem + ".");
        }
    }

    public static Optional<Path> previewPath(Track track) {
        return resolveAsset(track.previewFile(), track.id(), track.sourceDirectory());
    }

    public static int readOggDurationTicks(Path oggFile) throws IOException {
        Path normalized = oggFile.toAbsolutePath().normalize();
        long size = Files.size(normalized);
        long modified = Files.getLastModifiedTime(normalized).toMillis();
        CachedOggDuration cached = OGG_DURATION_CACHE.get(normalized);
        if (cached != null && cached.size() == size && cached.modified() == modified) {
            return cached.durationTicks();
        }

        OggInfo info = readCachedOggInfo(normalized);
        if (info.cutOff) {
            // The callers also use this to check that a song arrived or was uploaded whole.
            throw new IOException("Truncated OGG page");
        }
        if (info.sampleRate <= 0 || info.lastGranulePosition <= 0) {
            throw new IOException("Unable to read OGG duration");
        }

        double seconds = info.playedSamples() / (double) info.sampleRate;
        int durationTicks = Math.max(10 * 20, (int) Math.ceil(seconds * 20.0D));
        OGG_DURATION_CACHE.put(normalized, new CachedOggDuration(size, modified, durationTicks));
        return durationTicks;
    }

    private static Path speedVariantPath(Path source, float speedMultiplier) {
        Path fileName = source.getFileName();
        String name = fileName == null ? "track.ogg" : fileName.toString();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String suffix = normalizeSpeed(speedMultiplier) >= 1.9F ? "_x2.ogg" : "_x15.ogg";
        Path parent = source.getParent();
        return (parent == null ? Path.of(base + suffix) : parent.resolve(base + suffix)).toAbsolutePath().normalize();
    }

    private static boolean isValidSpeedVariant(Path source, Path target, float speed) {
        return speedVariantProblem(source, target, speed) == null;
    }

    /**
     * What is wrong with a sped-up copy, or null when it is this build's copy of the source at this speed. The lengths
     * are compared as the game plays them ({@link OggInfo#playedSamples}): a song whose granule positions do not start
     * at zero, such as a stream recording, used to have its correct copy rejected as the wrong speed.
     */
    private static String speedVariantProblem(Path source, Path target, float speed) {
        try {
            OggInfo sourceInfo = readCachedOggInfo(source);
            OggInfo targetInfo = readCachedOggInfo(target);
            if (sourceInfo.sampleRate <= 0 || sourceInfo.playedSamples() <= 0
                    || targetInfo.sampleRate <= 0 || targetInfo.playedSamples() <= 0) {
                return "has no length that can be read";
            }
            int mark = speedCopyVersion(target);
            if (mark != NativeAudio.SPEED_COPY_VERSION) {
                return "is not marked as this build's (" + NativeAudio.SPEED_COPY_FIELD + "=" + mark + ", expected "
                        + NativeAudio.SPEED_COPY_VERSION + ")";
            }
            double sourceSeconds = sourceInfo.playedSamples() / (double) sourceInfo.sampleRate;
            double targetSeconds = targetInfo.playedSamples() / (double) targetInfo.sampleRate;
            double actualSpeed = sourceSeconds / Math.max(0.001D, targetSeconds);
            return Math.abs(actualSpeed - speed) <= Math.max(0.04D, speed * 0.03D)
                    ? null
                    : "plays at x" + String.format(java.util.Locale.ROOT, "%.3f", actualSpeed) + " instead of x" + speed;
        } catch (IOException exception) {
            return "cannot be read (" + safeMessage(exception) + ")";
        }
    }

    private static float normalizeSpeed(float speed) {
        if (speed >= 1.9F) {
            return 2.0F;
        }
        return speed >= 1.4F ? 1.5F : 1.0F;
    }

    private static Optional<Path> resolveAsset(String fileName, String trackId, Path sourceDirectory) {
        if (!isSafeAssetName(fileName)) {
            return Optional.empty();
        }

        Path directory = sourceDirectory.toAbsolutePath().normalize();
        Path asset = directory.resolve(fileName).toAbsolutePath().normalize();
        if (asset.startsWith(directory) && Files.isRegularFile(asset)) {
            return Optional.of(asset);
        }
        if (isSafeAssetName(trackId) && !fileName.contains("/") && !fileName.contains("\\")) {
            Path nested = directory.resolve(trackId).resolve(fileName).toAbsolutePath().normalize();
            if (nested.startsWith(directory) && Files.isRegularFile(nested)) {
                return Optional.of(nested);
            }
        }
        return Optional.empty();
    }

    private static Optional<Track> readTrack(Path path, Path sourceDirectory, Source source) {
        try {
            JsonObject root = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
            migrateLegacyFnfParties(path, root);
            String id = safeId(string(root, "id", stripExtension(path.getFileName().toString())));
            if (id.isBlank() || id.length() > 128) {
                if (id.length() > 128) {
                    MusicBattleMod.LOGGER.warn("Ignoring track '{}' because its normalized id exceeds 128 characters.", path);
                }
                return Optional.empty();
            }

            String title = string(root, "title", id);
            String description = string(root, "description", "");
            int bpm = clamp(integer(root, "bpm", 120), 20, 400);
            int offsetMs = ChartBeat.readOffsetMs(root);
            float volume = clampFloat(number(root, "volume", 1.0F), 0.0F, 2.0F);
            String audioFile = string(root, "audio", "");
            String previewFile = string(root, "preview", "");
            String publishedBy = string(root, "publishedBy", "");
            boolean disableStageBars = root.has("disableStageBars") && root.get("disableStageBars").getAsBoolean();
            boolean hideStandardDimming = root.has("hideStandardDimming") && root.get("hideStandardDimming").getAsBoolean();
            audioFile = normalizeAssetReference(path, id, audioFile, sourceDirectory);
            previewFile = normalizeAssetReference(path, id, previewFile, sourceDirectory);
            List<BattleNote> notes = readNotes(root);
            List<TrackEffect> effects = readEffects(root);
            int durationTicks = integer(root, "durationTicks", suggestedDuration(notes));
            Optional<Path> audio = resolveAsset(audioFile, id, sourceDirectory);
            if (audio.isPresent()) {
                try {
                    durationTicks = Math.max(durationTicks, readOggDurationTicks(audio.get()));
                } catch (IOException exception) {
                    MusicBattleMod.LOGGER.warn("Unable to read OGG duration for '{}'.", audio.get(), exception);
                }
            }

            return Optional.of(new Track(id, title, description, bpm, offsetMs, Math.max(20 * 10, durationTicks), volume, audioFile, previewFile, publishedBy,
                    disableStageBars, hideStandardDimming, notes, effects,
                    sourceDirectory.toAbsolutePath().normalize(), path.toAbsolutePath().normalize(), source));
        } catch (Exception exception) {
            MusicBattleMod.LOGGER.warn("Unable to read music battle track '{}'.", path, exception);
            return Optional.empty();
        }
    }

    private static void migrateLegacyFnfParties(Path path, JsonObject root) {
        if (integer(root, PLAYER_PARTY_ORDER_VERSION, 0) >= CURRENT_PLAYER_PARTY_ORDER_VERSION
                || !root.has("notes") || !root.get("notes").isJsonArray()) {
            return;
        }

        boolean legacyFnfMarker = root.has(FNF_PARTIES_SWAPPED)
                && root.get(FNF_PARTIES_SWAPPED).isJsonPrimitive()
                && root.get(FNF_PARTIES_SWAPPED).getAsBoolean();
        boolean recognizableFnfImport = string(root, "description", "").startsWith("Imported from FNF chart:");
        if (!legacyFnfMarker && !recognizableFnfImport) {
            return;
        }

        // Version 1 accidentally inverted the imported FNF parties.  Only maps
        // that still identify themselves as an FNF import are reversed back.
        // A map whose author already corrected/customized its metadata keeps
        // its current notes and is merely versioned.
        if (legacyFnfMarker && recognizableFnfImport) {
            for (JsonElement element : root.getAsJsonArray("notes")) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject note = element.getAsJsonObject();
                int owner = integer(note, "owner", -1);
                if (owner == 0 || owner == 1) {
                    note.addProperty("owner", 1 - owner);
                }
            }
        }
        root.addProperty(PLAYER_PARTY_ORDER_VERSION, CURRENT_PLAYER_PARTY_ORDER_VERSION);

        Path temporary = path.resolveSibling(path.getFileName() + ".party-migration.tmp");
        Path backup = path.resolveSibling(path.getFileName() + ".party-order-v1.bak");
        try {
            if (!Files.exists(backup)) {
                Files.copy(path, backup);
            }
            Files.writeString(temporary, GSON.toJson(root));
            try {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
            MusicBattleMod.LOGGER.info("Migrated Blocky Music player-party order to v{} for '{}'.",
                    CURRENT_PLAYER_PARTY_ORDER_VERSION, path);
        } catch (IOException exception) {
            MusicBattleMod.LOGGER.warn("Unable to persist FNF party-order migration for '{}'.", path, exception);
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
            }
        }
    }

    private static List<BattleNote> readNotes(JsonObject root) {
        List<BattleNote> notes = new ArrayList<>();
        if (!root.has("notes") || !root.get("notes").isJsonArray()) {
            return notes;
        }

        JsonArray array = root.getAsJsonArray("notes");
        if (array.size() > MAX_NOTES) {
            // The editor refuses to save a chart this long, so this is a file from before that rule or written by
            // hand. Say so where an administrator or a bug report will see it: the ending is not played.
            MusicBattleMod.LOGGER.warn("Blocky Music chart '{}' has {} notes; only the first {} are used.",
                    root.has("id") && root.get("id").isJsonPrimitive() ? root.get("id").getAsString() : "?",
                    array.size(), MAX_NOTES);
        }
        for (JsonElement element : array) {
            if (notes.size() >= MAX_NOTES || !element.isJsonObject()) {
                break;
            }
            JsonObject note = element.getAsJsonObject();
            notes.add(new BattleNote(
                    integer(note, "owner", 0),
                    integer(note, "lane", 0),
                    integer(note, "tick", 0),
                    integer(note, "durationTicks", 0)
            ));
        }
        notes.sort(Comparator.comparingInt(BattleNote::tick).thenComparingInt(BattleNote::owner).thenComparingInt(BattleNote::lane));
        return List.copyOf(notes);
    }

    private static List<TrackEffect> readEffects(JsonObject root) {
        List<TrackEffect> effects = new ArrayList<>();
        if (!root.has("effects") || !root.get("effects").isJsonArray()) {
            return effects;
        }
        int fxRowsPerLayer = integer(root, "fxRowsPerLayer", TrackEffect.LEGACY_FX_ROWS_PER_LAYER);
        if (fxRowsPerLayer != TrackEffect.FX_ROWS_PER_LAYER) {
            fxRowsPerLayer = TrackEffect.LEGACY_FX_ROWS_PER_LAYER;
        }
        for (JsonElement element : root.getAsJsonArray("effects")) {
            if (effects.size() >= TrackEffect.MAX_EFFECTS || !element.isJsonObject()) {
                break;
            }
            JsonObject effect = element.getAsJsonObject();
            String storedType = string(effect, "type", "flash");
            // The retired glitch effect has no replacement. Drop those rows instead of letting
            // Type.byId fall back to FLASH and turn them into full-intensity flashes.
            if ("glitch".equalsIgnoreCase(storedType)) {
                continue;
            }
            boolean removedColor = "color".equalsIgnoreCase(storedType);
            TrackEffect.Type effectType = TrackEffect.Type.byId(storedType);
            TrackEffect.FxPosition position = TrackEffect.migrateFxPosition(
                    integer(effect, "lane", 0), integer(effect, "layer", 0), fxRowsPerLayer);
            int colorA = TrackEffectSettings.migrateLegacyColor(effectType, effect,
                    integer(effect, "colorA", 0xFFFFFFFF));
            effects.add(new TrackEffect(
                    effectType,
                    position.lane(),
                    position.layer(),
                    integer(effect, "tick", 0),
                    integer(effect, "durationTicks", 0),
                    number(effect, "intensity", 1.0F),
                    number(effect, "softness", 0.65F),
                    colorA,
                    integer(effect, "colorB", 0xFFFF55C8),
                    string(effect, "text", "Blocky Music"),
                    string(effect, "font", "minecraft:default"),
                    number(effect, "positionX", 0.5F),
                    number(effect, "positionY", 0.32F),
                    string(effect, "animation", "letters"),
                    string(effect, "exitAnimation", "none"),
                    removedColor ? "steady" : string(effect, "variant", "both"),
                    effect.has("beatReactive") && effect.get("beatReactive").getAsBoolean(),
                    number(effect, "opacity", 1.0F),
                    number(effect, "textSpeed", 1.0F),
                    number(effect, "outlineThickness", effectType == TrackEffect.Type.TEXT ? 0.0F : 2.0F),
                    number(effect, "glowStrength", 1.0F),
                    TrackEffectSettings.readJson(effect)
            ));
        }
        effects.sort(Comparator.comparingInt(TrackEffect::tick).thenComparing(effect -> effect.type().id()));
        return List.copyOf(effects);
    }

    private static String normalizeAssetReference(Path trackJson, String trackId, String fileName, Path sourceDirectory) {
        if (!isSafeAssetName(fileName) || resolveAsset(fileName, trackId, sourceDirectory).isPresent()) {
            return fileName;
        }
        Path directory = sourceDirectory.toAbsolutePath().normalize();
        Path sibling = trackJson.getParent() == null ? null : trackJson.getParent().resolve(fileName).toAbsolutePath().normalize();
        if (sibling != null && sibling.startsWith(directory) && Files.isRegularFile(sibling)) {
            return directory.relativize(sibling).toString().replace('\\', '/');
        }
        return fileName;
    }

    private static void migrateLegacyLayout(Path root) throws IOException {
        Path marker = root.resolve(".layout_v2_migrated");
        if (Files.exists(marker)) {
            return;
        }
        Path target = FMLEnvironment.dist == Dist.DEDICATED_SERVER ? serverTracksDirectory() : tracksDirectory();
        try (var entries = Files.list(root)) {
            for (Path entry : entries.toList()) {
                String name = entry.getFileName().toString();
                if (name.equals(PERSONAL_DIRECTORY_NAME)
                        || name.equals(SERVER_DIRECTORY_NAME)
                        || name.equals(USER_SERVER_DIRECTORY_NAME)
                        || name.equals(".server_cache")
                        || name.startsWith("workshop") || name.startsWith(".workshop")
                        || name.startsWith(".layout_v2")
                        || Files.isSymbolicLink(entry)) {
                    continue;
                }
                if (Files.isRegularFile(entry)) {
                    String lower = name.toLowerCase(java.util.Locale.ROOT);
                    if (!name.equalsIgnoreCase("practice_beat.json")
                            && (lower.endsWith(".ogg") || lower.endsWith(".png") || lower.endsWith(".json") && isTrackJson(entry))) {
                        Files.copy(entry, target.resolve(name), StandardCopyOption.REPLACE_EXISTING);
                    }
                    continue;
                }
                if (Files.isDirectory(entry) && containsTrackJson(entry)) {
                    copyLegacyTrackDirectory(entry, target.resolve(name));
                }
            }
        }
        Files.writeString(marker, "Blocky Music track layout v2\n");
    }

    /**
     * Repairs the accidental layout produced when a complete
     * music_battle_tracks folder was copied into my_tracks. Existing canonical
     * maps always win. Conflicting copies are moved to a hidden recovery
     * directory instead of being overwritten or deleted.
     */
    private static void repairAccidentallyNestedLayout(Path root) throws IOException {
        Path personal = tracksDirectory();
        Path recovery = root.resolve(".layout_recovery").toAbsolutePath().normalize();
        moveNestedEntries(personal.resolve(PERSONAL_DIRECTORY_NAME), personal,
                recovery.resolve("nested_my_tracks"), false);
        moveNestedEntries(personal.resolve(SERVER_DIRECTORY_NAME), legacyServerTracksDirectory(),
                recovery.resolve("nested_server_tracks"), true);
        moveNestedEntries(personal.resolve(USER_SERVER_DIRECTORY_NAME), legacyUserServerTracksDirectory(),
                recovery.resolve("nested_user_tracks"), false);
    }

    private static void migrateLegacyServerStorage(Path worldStorage) throws IOException {
        Path globalRoot = tracksRootDirectory();
        Path marker = globalRoot.resolve(WORLD_STORAGE_MIGRATION_MARKER).toAbsolutePath().normalize();
        if (Files.isRegularFile(marker)) {
            return;
        }
        Path normalizedWorldStorage = worldStorage.toAbsolutePath().normalize();
        copyDirectoryMerging(legacyServerTracksDirectory(), normalizedWorldStorage.resolve(SERVER_DIRECTORY_NAME));
        copyDirectoryMerging(legacyUserServerTracksDirectory(), normalizedWorldStorage.resolve(USER_SERVER_DIRECTORY_NAME));
        Files.writeString(marker,
                "Legacy server tracks were imported into " + normalizedWorldStorage + System.lineSeparator(),
                java.nio.charset.StandardCharsets.UTF_8);
        MusicBattleMod.LOGGER.info("Imported legacy global Blocky Music server tracks into '{}'; originals were kept as a backup.", normalizedWorldStorage);
    }

    private static void copyDirectoryMerging(Path source, Path destination) throws IOException {
        source = source.toAbsolutePath().normalize();
        destination = destination.toAbsolutePath().normalize();
        if (!Files.isDirectory(source) || Files.isSymbolicLink(source)) {
            return;
        }
        Files.createDirectories(destination);
        try (var entries = Files.list(source)) {
            for (Path entry : entries.toList()) {
                String name = entry.getFileName().toString();
                if (name.startsWith(".") || Files.isSymbolicLink(entry)) {
                    continue;
                }
                Path targetEntry = destination.resolve(name).toAbsolutePath().normalize();
                if (!targetEntry.startsWith(destination)) {
                    throw new IOException("Unsafe legacy Blocky Music migration path");
                }
                // A complete track already present in the world always wins;
                // never merge files from a legacy track into it.
                if (Files.exists(targetEntry)) {
                    continue;
                }
                if (Files.isDirectory(entry)) {
                    copyMissingDirectory(entry, targetEntry);
                } else if (Files.isRegularFile(entry)) {
                    copyFilePreservingWhenPossible(entry, targetEntry);
                }
            }
        }
    }

    private static void copyMissingDirectory(Path source, Path destination) throws IOException {
        Files.createDirectories(destination);
        try (var paths = Files.walk(source, 4)) {
            for (Path path : paths.toList()) {
                if (path.equals(source) || Files.isSymbolicLink(path)) {
                    continue;
                }
                Path relative = source.relativize(path);
                if (containsHiddenPathPart(relative)) {
                    continue;
                }
                Path target = destination.resolve(relative).toAbsolutePath().normalize();
                if (!target.startsWith(destination)) {
                    throw new IOException("Unsafe legacy Blocky Music migration path");
                }
                if (Files.isDirectory(path)) {
                    Files.createDirectories(target);
                } else if (Files.isRegularFile(path)) {
                    copyFilePreservingWhenPossible(path, target);
                }
            }
        }
    }

    private static void copyFilePreservingWhenPossible(Path source, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        try {
            Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES);
        } catch (UnsupportedOperationException exception) {
            Files.copy(source, target);
        }
    }

    private static boolean containsHiddenPathPart(Path relative) {
        for (Path part : relative) {
            if (part.getFileName().toString().startsWith(".")) {
                return true;
            }
        }
        return false;
    }

    private static void moveNestedEntries(Path nested, Path canonical, Path recovery, boolean recoverPracticeTrack) throws IOException {
        nested = nested.toAbsolutePath().normalize();
        canonical = canonical.toAbsolutePath().normalize();
        recovery = recovery.toAbsolutePath().normalize();
        Path root = tracksRootDirectory();
        if (!nested.startsWith(root) || !canonical.startsWith(root) || !recovery.startsWith(root)
                || !Files.isDirectory(nested) || Files.isSymbolicLink(nested)) {
            return;
        }
        try (var entries = Files.list(nested)) {
            for (Path entry : entries.toList()) {
                if (Files.isSymbolicLink(entry)) {
                    continue;
                }
                String name = entry.getFileName().toString();
                Path destination = canonical.resolve(name).toAbsolutePath().normalize();
                boolean obsoletePractice = recoverPracticeTrack && name.equalsIgnoreCase("practice_beat.json");
                if (!obsoletePractice && destination.startsWith(canonical) && !Files.exists(destination)) {
                    moveWithoutReplacing(entry, destination);
                    MusicBattleMod.LOGGER.info("Restored misplaced Blocky Music entry '{}' to '{}'.", entry, destination);
                } else {
                    Path recoveryTarget = uniqueRecoveryTarget(recovery, name);
                    moveWithoutReplacing(entry, recoveryTarget);
                    MusicBattleMod.LOGGER.info("Preserved conflicting misplaced Blocky Music entry '{}' in '{}'.", entry, recoveryTarget);
                }
            }
        }
        try (var remaining = Files.list(nested)) {
            if (remaining.findAny().isEmpty()) {
                Files.deleteIfExists(nested);
            }
        }
    }

    private static Path uniqueRecoveryTarget(Path recovery, String name) throws IOException {
        Files.createDirectories(recovery);
        Path candidate = recovery.resolve(name).toAbsolutePath().normalize();
        if (!candidate.startsWith(recovery)) {
            throw new IOException("Unsafe Blocky Music recovery path");
        }
        int suffix = 2;
        while (Files.exists(candidate)) {
            candidate = recovery.resolve(name + "_" + suffix++).toAbsolutePath().normalize();
        }
        return candidate;
    }

    private static void moveWithoutReplacing(Path source, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target);
        }
    }

    private static boolean containsTrackJson(Path directory) throws IOException {
        try (var stream = Files.find(directory, 2, (path, attributes) -> attributes.isRegularFile())) {
            return stream.anyMatch(path -> path.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".json") && isTrackJson(path));
        }
    }

    private static boolean isTrackJson(Path path) {
        try {
            JsonObject root = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
            return root.has("notes") && root.get("notes").isJsonArray();
        } catch (Exception ignored) {
            return false;
        }
    }

    private static void copyLegacyTrackDirectory(Path source, Path target) throws IOException {
        try (var stream = Files.walk(source, 3)) {
            for (Path path : stream.toList()) {
                if (Files.isSymbolicLink(path)) {
                    continue;
                }
                Path relative = source.relativize(path);
                Path destination = target.resolve(relative).toAbsolutePath().normalize();
                if (!destination.startsWith(target.toAbsolutePath().normalize())) {
                    continue;
                }
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else if (Files.isRegularFile(path)) {
                    String lower = path.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
                    if (lower.endsWith(".json") || lower.endsWith(".ogg") || lower.endsWith(".png")) {
                        Files.createDirectories(destination.getParent());
                        Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
        }
    }

    private static void removeGeneratedPracticeTrack(Path directory) throws IOException {
        Path practice = directory.resolve("practice_beat.json");
        if (!Files.isRegularFile(practice)) {
            return;
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(practice)).getAsJsonObject();
            boolean generated = "practice_beat".equals(string(root, "id", ""))
                    && "Practice Beat".equals(string(root, "title", ""))
                    && "Built-in warmup chart. Add OGG tracks here or export from the editor."
                    .equals(string(root, "description", ""));
            if (generated) {
                Files.deleteIfExists(practice);
                MusicBattleMod.LOGGER.info("Removed the obsolete generated Practice Beat track");
            }
        } catch (RuntimeException exception) {
            MusicBattleMod.LOGGER.warn("Unable to inspect obsolete Practice Beat definition '{}'.", practice, exception);
        }
    }

    private static int suggestedDuration(List<BattleNote> notes) {
        int last = notes.stream().mapToInt(note -> note.tick() + note.durationTicks()).max().orElse(20 * 20);
        return Math.max(20 * 10, last + 5 * 20);
    }

    private static String safeId(String input) {
        return input == null ? "" : input.toLowerCase().replaceAll("[^a-z0-9_\\-]", "_");
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static boolean isSafeAssetName(String fileName) {
        return fileName != null
                && !fileName.isBlank()
                && !Path.of(fileName).isAbsolute()
                && !fileName.contains("..");
    }

    private static String string(JsonObject object, String key, String fallback) {
        return object.has(key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsString() : fallback;
    }

    private static int integer(JsonObject object, String key, int fallback) {
        try {
            return object.has(key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsInt() : fallback;
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private static float number(JsonObject object, String key, float fallback) {
        try {
            return object.has(key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsFloat() : fallback;
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static float clampFloat(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    /**
     * The converter's mark on a sped-up copy ({@link NativeAudio#speedCopyVersion}), read once for each version of the
     * file, since the server thread asks at the start of every battle.
     */
    private static int speedCopyVersion(Path copy) throws IOException {
        Path normalized = copy.toAbsolutePath().normalize();
        long size = Files.size(normalized);
        long modified = Files.getLastModifiedTime(normalized).toMillis();
        CachedSpeedCopyMark cached = SPEED_COPY_MARKS.get(normalized);
        if (cached != null && cached.size() == size && cached.modified() == modified) {
            return cached.version();
        }
        int version = NativeAudio.speedCopyVersion(normalized);
        SPEED_COPY_MARKS.put(normalized, new CachedSpeedCopyMark(size, modified, version));
        return version;
    }

    private static OggInfo readCachedOggInfo(Path oggFile) throws IOException {
        Path normalized = oggFile.toAbsolutePath().normalize();
        long size = Files.size(normalized);
        long modified = Files.getLastModifiedTime(normalized).toMillis();
        CachedOggInfo cached = OGG_INFO_CACHE.get(normalized);
        if (cached != null && cached.size() == size && cached.modified() == modified) {
            return cached.info();
        }
        OggInfo info = readOggInfo(normalized);
        OGG_INFO_CACHE.put(normalized, new CachedOggInfo(size, modified, info));
        return info;
    }

    /**
     * The sample rate and granule positions of an Ogg Vorbis file, read from its page headers without decoding
     * anything. Only the Vorbis stream's own pages count, up to its end-of-stream page: the game plays neither a link
     * chained after it nor a page of another stream. A file cut off in the middle of a page ends at its last whole
     * page, where the game, and the converter after it, stop playing it, and says so ({@link OggInfo#cutOff}): its
     * speed copy is judged by that length (before, it was refused as unreadable although the converter had made it
     * right), while {@link #readOggDurationTicks}, which also checks that a song arrived whole, still refuses it.
     */
    private static OggInfo readOggInfo(Path oggFile) throws IOException {
        int sampleRate = -1;
        int shortBlock = 0;
        int longBlock = 0;
        int serial = 0;
        long lastGranulePosition = -1L;
        long firstAudioGranule = -1L;
        long packetsEnded = 0L;
        long audioPacketsBeforeGranule = 0L;
        boolean firstPacketChecked = false;
        boolean cutOff = false;

        try (InputStream input = Files.newInputStream(oggFile)) {
            while (true) {
                byte[] header = input.readNBytes(27);
                if (header.length == 0) {
                    break;
                }
                if (header.length != 27 && firstPacketChecked) {
                    cutOff = true;
                    break;
                }
                if (header.length != 27 || header[0] != 'O' || header[1] != 'g' || header[2] != 'g' || header[3] != 'S') {
                    throw new IOException("Invalid OGG page");
                }

                long granulePosition = readLittleEndianLong(header, 6);
                int pageSerial = (header[14] & 0xFF) | (header[15] & 0xFF) << 8 | (header[16] & 0xFF) << 16
                        | (header[17] & 0xFF) << 24;
                int segments = header[26] & 0xFF;
                byte[] lacing = input.readNBytes(segments);
                if (lacing.length != segments) {
                    if (firstPacketChecked) {
                        cutOff = true;
                        break;
                    }
                    throw new IOException("Truncated OGG segment table");
                }

                int payloadLength = 0;
                for (byte segment : lacing) {
                    payloadLength += segment & 0xFF;
                }

                byte[] payload = input.readNBytes(payloadLength);
                if (payload.length != payloadLength) {
                    if (firstPacketChecked) {
                        cutOff = true;
                        break;
                    }
                    throw new IOException("Truncated OGG payload");
                }
                if (firstPacketChecked && pageSerial != serial) {
                    continue;
                }
                serial = pageSerial;

                if (!firstPacketChecked) {
                    firstPacketChecked = true;
                    sampleRate = readVorbisSampleRate(payload);
                    // The two block sizes, as powers of two in one byte of the identification header.
                    if (payload.length > 28) {
                        shortBlock = 1 << (payload[28] & 0x0F);
                        longBlock = 1 << ((payload[28] >> 4) & 0x0F);
                    }
                }
                for (byte segment : lacing) {
                    if ((segment & 0xFF) < 255) {
                        packetsEnded++;
                    }
                }

                if (granulePosition >= 0) {
                    lastGranulePosition = granulePosition;
                    if (firstAudioGranule < 0L && packetsEnded > VORBIS_HEADER_PACKETS) {
                        firstAudioGranule = granulePosition;
                        audioPacketsBeforeGranule = packetsEnded - VORBIS_HEADER_PACKETS;
                    }
                }
                if ((header[5] & 0x04) != 0) {
                    break;
                }
            }
        }

        // Where the game's playback starts on the granule scale. The audio packets up to the first page with a
        // granule position bring the stream at least (packets - 1) half short blocks and at most as many half long
        // blocks past its first sample. A first granule position beyond that reach means the stream does not start at
        // zero (a recording cut from a broadcast): Minecraft plays it from its first sample all the same, so its
        // length is counted from there, to within half that range. Every encoder's own files start at zero, and for
        // them nothing changes.
        long startOffset = 0L;
        long exactPlayed = -1L;
        if (firstAudioGranule >= 0L && audioPacketsBeforeGranule > 0L) {
            long steps = audioPacketsBeforeGranule - 1L;
            long leastReach = steps * (shortBlock / 2);
            long mostReach = steps * (longBlock / 2);
            if (firstAudioGranule > mostReach) {
                startOffset = firstAudioGranule - (leastReach + mostReach) / 2L;
            }
        }
        // That estimate holds only for a file whose granule positions follow its packets, which every encoder's own
        // files do. A stream rip does not: its positions start where the broadcast was, or follow the container's
        // clock after a cut with ffmpeg's "-c copy", and the estimate was measured 0.38 s short to 0.78 s long on
        // such files, as the game plays more or less than the last position says. Whether the positions follow the
        // packets is read from the packets' headers (no audio is decoded); when they do not, the file is decoded once
        // on the timeline Minecraft plays (VorbisDecoder.Timeline.MINECRAFT, which counts exactly the samples the
        // game's JOrbisAudioStream gives), and the result is cached with the file's size and date like the rest.
        if (!cutOff && lastGranulePosition > 0 && !granulesMatchPackets(oggFile)) {
            exactPlayed = playedByMinecraft(oggFile);
        }
        return new OggInfo(sampleRate, lastGranulePosition, startOffset, cutOff, exactPlayed);
    }

    private static boolean granulesMatchPackets(Path oggFile) {
        try (InputStream input = new BufferedInputStream(Files.newInputStream(oggFile), 1 << 16)) {
            return VorbisDecoder.granulesMatchPackets(input);
        } catch (IOException | RuntimeException exception) {
            return false;
        }
    }

    /** The samples Minecraft plays from an Ogg Vorbis file, by decoding it; -1 when it cannot be decoded. */
    private static long playedByMinecraft(Path oggFile) {
        try (InputStream input = new BufferedInputStream(Files.newInputStream(oggFile), 1 << 16);
             PcmStream stream = VorbisDecoder.open(input, VorbisDecoder.Timeline.MINECRAFT)) {
            float[][] buffer = new float[stream.channels()][8192];
            long played = 0L;
            int n;
            while ((n = stream.read(buffer, 0, buffer[0].length)) > 0) {
                played += n;
            }
            return played;
        } catch (IOException | RuntimeException exception) {
            return -1L;
        }
    }

    private static int readVorbisSampleRate(byte[] payload) throws IOException {
        if (payload.length < 16
                || payload[0] != 1
                || payload[1] != 'v'
                || payload[2] != 'o'
                || payload[3] != 'r'
                || payload[4] != 'b'
                || payload[5] != 'i'
                || payload[6] != 's') {
            throw new IOException("Only Vorbis OGG files are supported");
        }
        return (payload[12] & 0xFF)
                | ((payload[13] & 0xFF) << 8)
                | ((payload[14] & 0xFF) << 16)
                | ((payload[15] & 0xFF) << 24);
    }

    private static long readLittleEndianLong(byte[] bytes, int offset) {
        long value = 0L;
        for (int i = 7; i >= 0; i--) {
            value = (value << 8) | (bytes[offset + i] & 0xFFL);
        }
        return value;
    }

    public record Track(
            String id,
            String title,
            String description,
            int bpm,
            /** Where beat 1 falls in the audio ({@link ChartBeat}); note ticks stay absolute either way. */
            int offsetMs,
            int durationTicks,
            float volume,
            String audioFile,
            String previewFile,
            String publishedBy,
            boolean disableStageBars,
            boolean hideStandardDimming,
            List<BattleNote> notes,
            List<TrackEffect> effects,
            Path sourceDirectory,
            Path definitionPath,
            Source source
    ) {
        /** For callers that build a track by hand: such a track has its first beat at the start of the audio. */
        public Track(String id, String title, String description, int bpm, int durationTicks, float volume,
                     String audioFile, String previewFile, String publishedBy, boolean disableStageBars,
                     boolean hideStandardDimming, List<BattleNote> notes, List<TrackEffect> effects,
                     Path sourceDirectory, Path definitionPath, Source source) {
            this(id, title, description, bpm, 0, durationTicks, volume, audioFile, previewFile, publishedBy,
                    disableStageBars, hideStandardDimming, notes, effects, sourceDirectory, definitionPath, source);
        }

        public boolean userTrack() {
            return source == Source.USER;
        }

        /** The set id of a Workshop chart ({@code ws_<setId>_<key>}), 0 for every other source. */
        public long workshopSetId() {
            if (source != Source.WORKSHOP || !id.startsWith(ru.gora.musicbattle.workshop.WorkshopLayout.SET_FOLDER_PREFIX)) {
                return 0L;
            }
            int end = id.indexOf('_', ru.gora.musicbattle.workshop.WorkshopLayout.SET_FOLDER_PREFIX.length());
            String digits = end < 0
                    ? id.substring(ru.gora.musicbattle.workshop.WorkshopLayout.SET_FOLDER_PREFIX.length())
                    : id.substring(ru.gora.musicbattle.workshop.WorkshopLayout.SET_FOLDER_PREFIX.length(), end);
            try {
                long value = Long.parseLong(digits);
                return value > 0 ? value : 0L;
            } catch (NumberFormatException ignored) {
                return 0L;
            }
        }

        public TrackSummary summary() {
            return summary("", "");
        }

        /** With the workshop identity the caller looked up in the server-beatmaps index. */
        public TrackSummary summary(String contentSha256, String mapper) {
            TrackSummary.Source wire = switch (source) {
                case USER -> TrackSummary.Source.USER;
                case WORKSHOP -> TrackSummary.Source.WORKSHOP;
                case SERVER -> TrackSummary.Source.SERVER;
            };
            return new TrackSummary(id, title, description, bpm, durationTicks, previewFile, publishedBy, wire,
                    workshopSetId(), contentSha256, mapper);
        }
    }

    public record AudioAsset(String fileName, Path path) {
    }

    /**
     * @param startOffset the granule position the game's playback starts at, 0 for a stream that starts at zero
     * @param cutOff whether the file ends in the middle of a page, as an interrupted download or copy does
     * @param exactPlayed for a stream that does not start at zero, the samples the game plays, counted by decoding;
     *     -1 otherwise, where the last granule position says it exactly
     */
    private record OggInfo(int sampleRate, long lastGranulePosition, long startOffset, boolean cutOff,
                           long exactPlayed) {
        /** The song's length as the game plays it. */
        long playedSamples() {
            return exactPlayed >= 0L ? exactPlayed : lastGranulePosition - startOffset;
        }
    }

    private record CachedOggDuration(long size, long modified, int durationTicks) {
    }

    private record CachedOggInfo(long size, long modified, OggInfo info) {
    }

    private record CachedSpeedCopyMark(long size, long modified, int version) {
    }
}

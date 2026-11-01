package ru.gora.musicbattle.workshop;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The client's installed workshop sets: everything under {@code music_battle_tracks/workshop/ws_<setId>/} with a valid
 * {@code beatmap.meta}. It is the source of truth for "installed" (like lazer's realm), indexed by set id and by
 * {@code contentSha256}.
 *
 * <p><b>Isolation:</b> this is client-only state. Nothing here references MusicBattleTracks,
 * and installed sets are never added to its {@code availableTracks()}: in singleplayer the integrated server shares
 * the JVM, and a leak would turn every player's downloads into server tracks. The {@code workshop-catalog-isolation}
 * check proves it.
 *
 * <p>Pure {@code java.nio} and Gson. {@link #refresh()} does blocking IO (directory listing and one small manifest
 * read per set): call it off the render thread. Reads of the snapshot are cheap and thread-safe.
 */
public final class WorkshopCatalog {
    private static final Pattern SET_FOLDER = Pattern.compile("ws_[1-9][0-9]{0,18}");
    /** The card sidecar is small JSON; anything bigger is ignored rather than parsed. */
    static final long MAX_SIDECAR_BYTES = 512L * 1024;

    /** One installed set as found on disk. */
    public record InstalledSet(
            BeatmapManifest manifest,
            Path directory,
            String contentSha256,
            Instant installedAt,
            boolean audioPresent,
            boolean coverPresent,
            JsonObject card,
            Instant cardFetchedAt,
            boolean previewPresent
    ) {
        public long setId() {
            return manifest.setId();
        }

        public int revision() {
            return manifest.revision();
        }

        /** The offline card data, or empty when the set was installed without it (drop, inbox). */
        public Optional<JsonObject> cardJson() {
            return Optional.ofNullable(card).map(JsonObject::deepCopy);
        }

        public Path coverFile() {
            return directory.resolve(WorkshopLayout.coverFileName(manifest.setId()));
        }

        public Path audioFile() {
            return directory.resolve(WorkshopLayout.audioFileName(manifest.setId()));
        }

        public Path previewFile() {
            return directory.resolve(WorkshopLayout.PREVIEW_SIDECAR_FILE_NAME);
        }
    }

    /** A set folder that could not be read, for logs and a future "broken installs" hint. */
    public record Problem(String folderName, String reason) {
    }

    private record Snapshot(List<InstalledSet> sets, Map<Long, InstalledSet> bySetId, Map<String, InstalledSet> byContent,
                            List<Problem> problems) {
        static final Snapshot EMPTY = new Snapshot(List.of(), Map.of(), Map.of(), List.of());
    }

    private final Path tracksRoot;
    private volatile Snapshot snapshot = Snapshot.EMPTY;
    private volatile long generation;

    public WorkshopCatalog(Path tracksRoot) {
        this.tracksRoot = tracksRoot.toAbsolutePath().normalize();
    }

    public Path tracksRoot() {
        return tracksRoot;
    }

    public Path installedDirectory() {
        return WorkshopLayout.installedDirectory(tracksRoot);
    }

    /** Rescans the installed folder. Blocking IO. Returns the number of installed sets. */
    public synchronized int refresh() {
        Path installed = installedDirectory();
        List<InstalledSet> sets = new ArrayList<>();
        List<Problem> problems = new ArrayList<>();
        if (Files.isDirectory(installed, LinkOption.NOFOLLOW_LINKS)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(installed)) {
                for (Path folder : stream) {
                    String name = String.valueOf(folder.getFileName());
                    if (!SET_FOLDER.matcher(name).matches()) {
                        continue;
                    }
                    if (!Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)) {
                        problems.add(new Problem(name, "not a plain folder"));
                        continue;
                    }
                    try {
                        sets.add(read(folder));
                    } catch (IOException | RuntimeException exception) {
                        problems.add(new Problem(name, String.valueOf(exception.getMessage())));
                    }
                }
            } catch (IOException exception) {
                problems.add(new Problem(WorkshopLayout.INSTALLED_DIRECTORY_NAME, String.valueOf(exception.getMessage())));
            }
        }
        sets.sort(Comparator.comparingLong(InstalledSet::setId));
        Map<Long, InstalledSet> bySetId = new LinkedHashMap<>();
        Map<String, InstalledSet> byContent = new HashMap<>();
        for (InstalledSet set : sets) {
            bySetId.put(set.setId(), set);
            byContent.putIfAbsent(set.contentSha256(), set);
        }
        snapshot = new Snapshot(List.copyOf(sets), Collections.unmodifiableMap(bySetId), Collections.unmodifiableMap(byContent),
                List.copyOf(problems));
        generation++;
        return sets.size();
    }

    private static InstalledSet read(Path folder) throws IOException {
        Path manifestFile = folder.resolve(WorkshopLayout.MANIFEST_FILE_NAME);
        if (!Files.isRegularFile(manifestFile, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("no " + WorkshopLayout.MANIFEST_FILE_NAME);
        }
        if (Files.size(manifestFile) > BeatmapManifest.MAX_MANIFEST_BYTES) {
            throw new IOException("manifest too large");
        }
        BeatmapManifest manifest = BeatmapManifest.parse(new String(Files.readAllBytes(manifestFile), StandardCharsets.UTF_8));
        if (!manifest.folderName().equals(String.valueOf(folder.getFileName()))) {
            throw new IOException("manifest belongs to " + manifest.folderName());
        }
        Instant installedAt = Files.getLastModifiedTime(manifestFile).toInstant();
        boolean audio = regularFile(folder.resolve(WorkshopLayout.audioFileName(manifest.setId())));
        boolean cover = manifest.hasCover() && regularFile(folder.resolve(WorkshopLayout.coverFileName(manifest.setId())));
        JsonObject card = readCard(folder.resolve(WorkshopLayout.CARD_SIDECAR_FILE_NAME), manifest.setId());
        Instant fetchedAt = null;
        if (card != null && card.has("fetchedAt") && card.get("fetchedAt").isJsonPrimitive()) {
            try {
                fetchedAt = Instant.parse(card.get("fetchedAt").getAsString());
            } catch (RuntimeException ignored) {
                fetchedAt = null;
            }
        }
        boolean preview = regularFile(folder.resolve(WorkshopLayout.PREVIEW_SIDECAR_FILE_NAME));
        return new InstalledSet(manifest, folder, manifest.contentSha256(), installedAt, audio, cover, card, fetchedAt, preview);
    }

    /** The sidecar only counts when it is a JSON object about this very set. */
    private static JsonObject readCard(Path file, long setId) {
        try {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > MAX_SIDECAR_BYTES) {
                return null;
            }
            byte[] bytes;
            try (InputStream in = Files.newInputStream(file)) {
                bytes = in.readNBytes((int) MAX_SIDECAR_BYTES + 1);
            }
            JsonElement element = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
            if (!element.isJsonObject()) {
                return null;
            }
            JsonObject object = element.getAsJsonObject();
            if (!object.has("id") || !object.get("id").isJsonPrimitive() || object.get("id").getAsLong() != setId) {
                return null;
            }
            return object;
        } catch (IOException | RuntimeException exception) {
            return null;
        }
    }

    private static boolean regularFile(Path path) {
        return Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS);
    }

    /** Installed sets ordered by set id. */
    public List<InstalledSet> all() {
        return snapshot.sets();
    }

    public int size() {
        return snapshot.sets().size();
    }

    /** Increments on every refresh; screens compare it to know when to rebuild their lists. */
    public long generation() {
        return generation;
    }

    public Optional<InstalledSet> bySetId(long setId) {
        return Optional.ofNullable(snapshot.bySetId().get(setId));
    }

    /** The hash index: which installed set has exactly this content (its fingerprint). */
    public Optional<InstalledSet> byContentSha256(String contentSha256) {
        return contentSha256 == null ? Optional.empty() : Optional.ofNullable(snapshot.byContent().get(contentSha256));
    }

    public boolean isInstalled(long setId) {
        return snapshot.bySetId().containsKey(setId);
    }

    /** True when exactly this revision and content is installed. */
    public boolean isInstalled(long setId, int revision, String contentSha256) {
        InstalledSet set = snapshot.bySetId().get(setId);
        return set != null && set.revision() == revision && set.contentSha256().equals(contentSha256);
    }

    public List<Problem> problems() {
        return snapshot.problems();
    }

    /**
     * Rebuilds a {@code .blockymap} from an installed set: the original
     * {@code beatmap.meta}, every declared file re-hashed against the manifest, and the site's {@code beatmap.sig}
     * when the set was installed from a signed package. There is no package cache and no second download; a file
     * that no longer matches its declared hash aborts the repack instead of shipping altered bytes.
     *
     * <p>The entries are written in manifest order with a fixed time, so repacking the same folder twice produces
     * the same archive. Transfer identity stays {@code contentSha256}, never the hash of these bytes.
     *
     * <p>Blocking IO and hashing: never on the render thread.
     */
    public Path repack(long setId, Path targetDirectory) throws IOException {
        InstalledSet set = bySetId(setId).orElseThrow(() -> new IOException("set " + setId + " is not installed"));
        BeatmapManifest manifest = set.manifest();
        Path manifestFile = set.directory().resolve(WorkshopLayout.MANIFEST_FILE_NAME);
        byte[] manifestBytes = Files.readAllBytes(manifestFile);
        Files.createDirectories(targetDirectory);
        Path archive = targetDirectory.resolve(manifest.folderName() + "-" + manifest.revision() + WorkshopLayout.PACKAGE_EXTENSION);
        Files.deleteIfExists(archive);
        try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(Files.newOutputStream(archive,
                java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE))) {
            writeEntry(zip, WorkshopLayout.MANIFEST_FILE_NAME, manifestBytes);
            for (BeatmapManifest.FileEntry entry : manifest.files()) {
                Path file = set.directory().resolve(entry.path()).normalize();
                if (!file.getParent().equals(set.directory()) || !regularFile(file)) {
                    throw new IOException("missing " + entry.path());
                }
                byte[] bytes = Files.readAllBytes(file);
                if (bytes.length != entry.size() || !sha256(bytes).equals(entry.sha256())) {
                    throw new IOException(entry.path() + " no longer matches the manifest");
                }
                writeEntry(zip, entry.path(), bytes);
            }
            Path signature = set.directory().resolve(WorkshopLayout.SIGNATURE_FILE_NAME);
            if (regularFile(signature) && Files.size(signature) == WorkshopSignature.SIGNATURE_BYTES) {
                writeEntry(zip, WorkshopLayout.SIGNATURE_FILE_NAME, Files.readAllBytes(signature));
            }
        } catch (IOException | RuntimeException exception) {
            Files.deleteIfExists(archive);
            throw exception instanceof IOException io ? io : new IOException(exception);
        }
        return archive;
    }

    /**
     * Whether a chunk of {@code chunkLength} bytes ends a stream of a {@link #repack}ed package for a receiver that
     * was never told the size. Any chunk that is not full is the last one.
     *
     * <p>A server knows the size of an "Add to server" package from the offer, but not that of an installed-map vote:
     * the voter repacks only once the server asks for the bytes, so the vote carries no size and the end has to be
     * visible in the stream itself.
     */
    public static boolean endsTransfer(int chunkLength, int chunkSize) {
        return chunkSize > 0 && chunkLength < chunkSize;
    }

    /**
     * Whether the sender has to add one empty chunk after {@code size} bytes for {@link #endsTransfer} to ever be
     * true: exactly the packages that are a whole number of chunks.
     *
     * <p>A repacked package of, say, exactly 48 KiB is two full chunks and nothing else, so a receiver waiting for a
     * short chunk waits for ever - the vote hung until the voting closed without it, and the player's one transfer
     * slot stayed taken for the rest of the session.
     */
    public static boolean needsEndChunk(long size, int chunkSize) {
        return chunkSize > 0 && size % chunkSize == 0;
    }

    /** Whether the installed copy still carries the site's signature, so it may be offered to a server. */
    public boolean signed(long setId) {
        return bySetId(setId).filter(set -> {
            Path signature = set.directory().resolve(WorkshopLayout.SIGNATURE_FILE_NAME);
            try {
                return regularFile(signature) && Files.size(signature) == WorkshopSignature.SIGNATURE_BYTES;
            } catch (IOException ignored) {
                return false;
            }
        }).isPresent();
    }

    private static void writeEntry(java.util.zip.ZipOutputStream zip, String name, byte[] bytes) throws IOException {
        java.util.zip.ZipEntry entry = new java.util.zip.ZipEntry(name);
        entry.setTime(0L);
        zip.putNextEntry(entry);
        zip.write(bytes);
        zip.closeEntry();
    }

    private static String sha256(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    /**
     * Deletes one installed set folder (strictly below the installed root, links removed, not followed) and
     * refreshes. Returns false when it was not installed. Blocking IO.
     */
    public synchronized boolean delete(long setId) throws IOException {
        Path installed = installedDirectory();
        Path folder = installed.resolve(WorkshopLayout.setFolderName(setId)).normalize();
        if (!folder.getParent().equals(installed) || !Files.exists(folder, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        if (Files.isSymbolicLink(folder)) {
            Files.delete(folder);
        } else {
            BeatmapPackage.deleteTree(folder, installed);
        }
        refresh();
        return true;
    }
}

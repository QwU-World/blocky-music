package ru.gora.musicbattle.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.storage.LevelResource;
import ru.gora.musicbattle.MusicBattleMod;
import ru.gora.musicbattle.data.TrackSummary;
import ru.gora.musicbattle.server.MusicBattleTracks;

public final class ClientTrackAssets {
    private static final int MAX_CHUNK_BYTES = 64 * 1024;
    private static final int MAX_TOTAL_CHUNKS = 4096;
    private static final int MAX_AUDIO_BYTES = 128 * 1024 * 1024;
    private static final int MAX_PREVIEW_BYTES = 8 * 1024 * 1024;
    private static final int MAX_CHART_BYTES = 2 * 1024 * 1024;
    private static final int MAX_PREVIEW_DIMENSION = 2048;
    private static final long MAX_PREVIEW_PIXELS = 4_194_304L;
    private static final Map<String, TransferState> TRANSFERS = new HashMap<>();
    private static final Map<Path, String> VERIFIED_FILES = new HashMap<>();

    private ClientTrackAssets() {
    }

    public static void acceptChunk(String trackId, String assetKind, String fileName, int chunkIndex, int totalChunks, int totalBytes, String sha256, byte[] data) {
        int maxBytes = maxAssetBytes(assetKind);
        String storedFileName = storedFileName(fileName);
        if (!safeId(trackId) || !safeKind(assetKind) || !safeFileName(fileName)
                || !safeStoredFileName(storedFileName) || !validExtension(assetKind, storedFileName)
                || !safeHash(sha256) || data == null || data.length <= 0 || data.length > MAX_CHUNK_BYTES
                || chunkIndex < 0 || totalChunks <= 0 || totalChunks > MAX_TOTAL_CHUNKS || chunkIndex >= totalChunks
                || totalBytes <= 0 || totalBytes > maxBytes
                || totalChunks < (totalBytes + MAX_CHUNK_BYTES - 1) / MAX_CHUNK_BYTES) {
            MusicBattleMod.LOGGER.warn("Rejected invalid Blocky Music asset chunk metadata for track '{}'.", trackId);
            return;
        }

        Path root = cacheRoot();
        Path directory = trackDirectory(trackId);
        Path target = directory.resolve(storedFileName).toAbsolutePath().normalize();
        Path temporary = directory.resolve(storedFileName + ".download").toAbsolutePath().normalize();
        if (!directory.startsWith(root) || !target.startsWith(directory) || !temporary.startsWith(directory)
                || hasSymbolicLink(root, directory) || Files.isSymbolicLink(target) || Files.isSymbolicLink(temporary)) {
            MusicBattleMod.LOGGER.warn("Rejected unsafe Blocky Music cache path for track '{}'.", trackId);
            return;
        }

        String key = transferKey(trackId, assetKind, storedFileName);
        TransferState state = TRANSFERS.computeIfAbsent(key, ignored -> new TransferState(assetKind, totalChunks, totalBytes, sha256, temporary, target));
        if (!state.matches(assetKind, totalChunks, totalBytes, sha256) || chunkIndex != state.nextChunk) {
            TRANSFERS.remove(key);
            cleanup(temporary);
            if (chunkIndex != 0) {
                return;
            }
            state = new TransferState(assetKind, totalChunks, totalBytes, sha256, temporary, target);
            TRANSFERS.put(key, state);
        }
        if (state.receivedBytes + data.length > state.totalBytes) {
            rejectTransfer(key, state, "received more bytes than declared");
            return;
        }

        try {
            if (chunkIndex == 0) {
                Files.createDirectories(directory);
                if (hasSymbolicLink(root, directory)) {
                    rejectTransfer(key, state, "cache directory contains a symbolic link");
                    return;
                }
                cleanup(temporary);
            }
            try (OutputStream output = Files.newOutputStream(temporary, StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE)) {
                output.write(data);
            }
            state.nextChunk++;
            state.receivedBytes += data.length;
            if (state.nextChunk >= totalChunks) {
                completeTransfer(key, trackId, storedFileName, state);
            }
        } catch (IOException exception) {
            TRANSFERS.remove(key);
            cleanup(temporary);
            MusicBattleMod.LOGGER.warn("Unable to receive Blocky Music asset '{}'.", target, exception);
        }
    }

    public static Optional<Path> cachedAsset(String trackId, String fileName) {
        return cachedAsset(trackId, fileName, "");
    }

    public static Optional<Path> cachedAsset(String trackId, String fileName, String expectedHash) {
        if (!safeId(trackId) || !safeFileName(fileName) || !expectedHash.isBlank() && !safeHash(expectedHash)) {
            return Optional.empty();
        }
        String storedFileName = storedFileName(fileName);
        Path directory = trackDirectory(trackId);
        if (safeStoredFileName(storedFileName)) {
            Path downloaded = directory.resolve(storedFileName).toAbsolutePath().normalize();
            if (downloaded.startsWith(directory) && validatedCachedFile(downloaded, expectedHash)) {
                return Optional.of(downloaded);
            }
        }
        return Optional.empty();
    }

    /**
     * Content hash → file, for answering a battle asset offer.
     *
     * <p>The index is built off the render thread from data that already exists, so no file is ever hashed to
     * answer an offer: every cached transfer has its verified {@code .sha256} sidecar, and every installed Workshop
     * set declares each file's size and hash in its manifest. A payload handler only reads the finished map — the
     * review's rule that "a payload handler never calls {@code cachedAsset} and never hashes a file".
     */
    private static volatile Map<String, Path> HASH_INDEX = Map.of();
    private static volatile boolean hashIndexRefreshing;

    /** The file with exactly this content, if the player already has it. Cheap; render-thread safe. */
    public static Optional<Path> byContentHash(String sha256) {
        if (sha256 == null || !safeHash(sha256)) {
            return Optional.empty();
        }
        Path path = HASH_INDEX.get(sha256.toLowerCase(Locale.ROOT));
        return path != null && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) ? Optional.of(path) : Optional.empty();
    }

    /**
     * Puts a file the player already has where a battle looks for it, and says whether it is there now.
     *
     * Two different questions were being asked about the same file and they disagreed. A battle asks the server for
     * a chart and a song by track and file name, and looks for them in this server's cache folder. The server first
     * offers them and asks "do you already have this content?", which is answered from the index - and the index
     * also covers every map installed from the Workshop. So a player who had installed the map answered yes, the
     * server politely skipped the transfer, and the battle then waited for a file that was never going to appear in
     * the folder it was watching: the five-minute wait the owner saw as an endless download. With the index empty
     * the opposite happened and a song already on disk was sent across again.
     *
     * Copying it into place closes both: the answer stays honest, and the battle finds what it was promised. A copy
     * rather than a link because the cache is pruned on its own schedule and must never reach into the catalogue,
     * and because a link is not portable across the systems this mod runs on.
     */
    public static boolean materialiseFromContent(String trackId, String fileName, String sha256) {
        if (cachedAsset(trackId, fileName, sha256).isPresent()) {
            return true;
        }
        if (sha256 == null || sha256.isBlank() || !safeId(trackId) || !safeFileName(fileName)) {
            return false;
        }
        Optional<Path> held = byContentHash(sha256);
        if (held.isEmpty()) {
            return false;
        }
        String storedFileName = storedFileName(fileName);
        if (!safeStoredFileName(storedFileName)) {
            return false;
        }
        Path directory = trackDirectory(trackId);
        Path target = directory.resolve(storedFileName).toAbsolutePath().normalize();
        if (!target.startsWith(directory)) {
            return false;
        }
        Path temporary = target.resolveSibling(storedFileName + ".adopt");
        try {
            Files.createDirectories(directory);
            Files.copy(held.get(), temporary, StandardCopyOption.REPLACE_EXISTING);
            // The same verification the transfer path applies, because the index could be stale by now.
            if (!sha256.equalsIgnoreCase(sha256(temporary))) {
                cleanup(temporary);
                MusicBattleMod.LOGGER.warn("A copy of Blocky Music '{}' no longer matches its hash; leaving it to the server.",
                        fileName);
                refreshHashIndex();
                return false;
            }
            moveReplacing(temporary, target);
            writeHashSidecar(target, sha256);
            VERIFIED_FILES.put(target, sha256.toLowerCase(Locale.ROOT));
            MusicBattleMod.LOGGER.info("Used the copy of '{}' this player already had instead of downloading it again.",
                    fileName);
            return true;
        } catch (IOException exception) {
            cleanup(temporary);
            MusicBattleMod.LOGGER.warn("Unable to reuse the local copy of Blocky Music '{}'.", fileName, exception);
            return false;
        }
    }

    /** True when the index knows this content. The answer to an asset offer, computed from memory only. */
    public static boolean hasContent(String sha256) {
        return byContentHash(sha256).isPresent();
    }

    /** Rebuilds the index off the render thread. Called after a catalogue refresh and after a transfer completes. */
    public static void refreshHashIndex() {
        if (hashIndexRefreshing) {
            return;
        }
        hashIndexRefreshing = true;
        Util.nonCriticalIoPool().execute(() -> {
            try {
                HASH_INDEX = buildHashIndex();
            } catch (RuntimeException exception) {
                MusicBattleMod.LOGGER.debug("Unable to rebuild the Blocky Music content index: {}", exception.toString());
            } finally {
                hashIndexRefreshing = false;
            }
        });
    }

    private static Map<String, Path> buildHashIndex() {
        Map<String, Path> index = new java.util.HashMap<>();
        // Files this server already sent us, each with the hash the transfer verified.
        Path cache = cacheRoot();
        if (Files.isDirectory(cache, LinkOption.NOFOLLOW_LINKS)) {
            try (var stream = Files.walk(cache, 3)) {
                stream.filter(path -> path.getFileName().toString().endsWith(".sha256")).forEach(sidecar -> {
                    try {
                        String hash = Files.readString(sidecar, StandardCharsets.US_ASCII).trim().toLowerCase(Locale.ROOT);
                        String name = sidecar.getFileName().toString();
                        Path target = sidecar.resolveSibling(name.substring(0, name.length() - ".sha256".length()));
                        if (safeHash(hash) && Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                            index.putIfAbsent(hash, target);
                        }
                    } catch (IOException | RuntimeException ignored) {
                        // A broken sidecar simply leaves that file out of the index.
                    }
                });
            } catch (IOException ignored) {
                // An unreadable cache folder means an empty half of the index, never a failure.
            }
        }
        // Installed Workshop sets: their manifests already declare every file's size and hash.
        ru.gora.musicbattle.workshop.WorkshopCatalog catalog =
                ru.gora.musicbattle.client.workshop.WorkshopClient.get().catalog();
        for (var set : catalog.all()) {
            for (var entry : set.manifest().files()) {
                Path file = set.directory().resolve(entry.path()).normalize();
                try {
                    if (file.getParent().equals(set.directory()) && Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                            && Files.size(file) == entry.size()) {
                        index.putIfAbsent(entry.sha256().toLowerCase(Locale.ROOT), file);
                    }
                } catch (IOException ignored) {
                    // A file that vanished between the scan and here is simply not indexed.
                }
            }
        }
        return Map.copyOf(index);
    }

    public static boolean isTransferActive(String trackId, String assetKind, String fileName) {
        if (!safeId(trackId) || !safeKind(assetKind) || !safeFileName(fileName)) {
            return false;
        }
        return TRANSFERS.containsKey(transferKey(trackId, assetKind, storedFileName(fileName)));
    }

    public static void cacheTrackSummaries(Iterable<TrackSummary> tracks) {
        Util.nonCriticalIoPool().execute(() -> {
            Path cache = cacheRoot().resolve("track_info_cache.json").toAbsolutePath().normalize();
            Path temporary = cache.resolveSibling(cache.getFileName() + ".tmp");
            try {
                Files.createDirectories(cache.getParent());
                JsonArray array = new JsonArray();
                for (TrackSummary track : tracks) {
                    JsonObject object = new JsonObject();
                    object.addProperty("id", track.id());
                    object.addProperty("title", track.title());
                    object.addProperty("description", track.description());
                    object.addProperty("bpm", track.bpm());
                    object.addProperty("durationTicks", track.durationTicks());
                    object.addProperty("previewFile", track.previewFile());
                    object.addProperty("publishedBy", track.publishedBy());
                    object.addProperty("userTrack", track.userTrack());
                    array.add(object);
                }
                JsonObject root = new JsonObject();
                root.addProperty("version", 4);
                root.add("tracks", array);
                Files.writeString(temporary, root.toString(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                moveReplacing(temporary, cache);
            } catch (IOException exception) {
                cleanup(temporary);
                MusicBattleMod.LOGGER.warn("Unable to write Blocky Music track info cache '{}'.", cache, exception);
            }
        });
    }

    public static float transferProgress(String trackId, String assetKind, String fileName) {
        return transferProgress(trackId, assetKind, fileName, "");
    }

    public static float transferProgress(String trackId, String assetKind, String fileName, String expectedHash) {
        if (!safeId(trackId) || !safeKind(assetKind) || !safeFileName(fileName)) {
            return 0.0F;
        }
        if (cachedAsset(trackId, fileName, expectedHash).isPresent()) {
            return 1.0F;
        }
        TransferState state = TRANSFERS.get(transferKey(trackId, assetKind, storedFileName(fileName)));
        if (state == null || state.totalBytes <= 0 || !expectedHash.isBlank() && !state.sha256.equalsIgnoreCase(expectedHash)) {
            return 0.0F;
        }
        return Math.min(1.0F, Math.max(0.0F, state.receivedBytes / (float) state.totalBytes));
    }

    private static void completeTransfer(String key, String trackId, String storedFileName, TransferState state) throws IOException {
        TRANSFERS.remove(key);
        if (state.receivedBytes != state.totalBytes || !state.sha256.equalsIgnoreCase(sha256(state.temporary))
                || !validAssetContents(state.assetKind, state.temporary)) {
            cleanup(state.temporary);
            MusicBattleMod.LOGGER.warn("Discarded invalid Blocky Music {} '{}' for track '{}'.", state.assetKind, storedFileName, trackId);
            return;
        }
        moveReplacing(state.temporary, state.target);
        writeHashSidecar(state.target, state.sha256);
        VERIFIED_FILES.put(state.target, state.sha256.toLowerCase(Locale.ROOT));
        MusicBattleMod.LOGGER.info("Received verified Blocky Music {} '{}' for track '{}' from server ({} bytes).", state.assetKind, storedFileName, trackId, state.receivedBytes);
        ClientBattleController.onTrackAssetReady(trackId, state.assetKind, storedFileName, state.sha256);
    }

    private static void rejectTransfer(String key, TransferState state, String reason) {
        TRANSFERS.remove(key);
        cleanup(state.temporary);
        MusicBattleMod.LOGGER.warn("Rejected Blocky Music asset transfer: {}.", reason);
    }

    private static boolean validatedCachedFile(Path target, String expectedHash) {
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(target)) {
            return false;
        }
        String storedHash = VERIFIED_FILES.get(target);
        if (storedHash == null) {
            Path sidecar = hashSidecar(target);
            try {
                storedHash = Files.readString(sidecar, StandardCharsets.US_ASCII).trim().toLowerCase(Locale.ROOT);
                if (!safeHash(storedHash) || !storedHash.equalsIgnoreCase(sha256(target)) || !validAssetContents(extensionKind(target), target)) {
                    return false;
                }
                VERIFIED_FILES.put(target, storedHash);
            } catch (IOException exception) {
                return false;
            }
        }
        return expectedHash.isBlank() || storedHash.equalsIgnoreCase(expectedHash);
    }

    private static void writeHashSidecar(Path target, String hash) throws IOException {
        Path sidecar = hashSidecar(target);
        Path temporary = sidecar.resolveSibling(sidecar.getFileName() + ".tmp");
        Files.writeString(temporary, hash.toLowerCase(Locale.ROOT), StandardCharsets.US_ASCII,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        moveReplacing(temporary, sidecar);
    }

    private static Path hashSidecar(Path target) {
        return target.resolveSibling(target.getFileName() + ".sha256");
    }

    private static Path cacheRoot() {
        return MusicBattleTracks.tracksRootDirectory().resolve(".server_cache").resolve(serverNamespace()).toAbsolutePath().normalize();
    }

    private static Path trackDirectory(String trackId) {
        return cacheRoot().resolve(trackId).toAbsolutePath().normalize();
    }

    private static String serverNamespace() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.getSingleplayerServer() != null) {
            String worldIdentity = minecraft.getSingleplayerServer().getWorldPath(LevelResource.ROOT)
                    .toAbsolutePath().normalize().toString().toLowerCase(Locale.ROOT);
            String hash = sha256(worldIdentity.getBytes(StandardCharsets.UTF_8));
            return "local_" + hash.substring(0, 18);
        }
        String address = minecraft.getCurrentServer() == null ? "unknown-server" : minecraft.getCurrentServer().ip;
        String hash = sha256(address.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
        return hash.substring(0, 24);
    }

    private static String transferKey(String trackId, String assetKind, String fileName) {
        return serverNamespace() + "/" + trackId + "/" + assetKind + "/" + storedFileName(fileName);
    }

    private static String storedFileName(String fileName) {
        if (fileName == null) {
            return "";
        }
        String normalized = fileName.replace('\\', '/');
        int separator = normalized.lastIndexOf('/');
        return separator >= 0 ? normalized.substring(separator + 1) : normalized;
    }

    private static boolean safeId(String value) {
        return value != null && value.matches("[a-z0-9_-]{1,128}");
    }

    private static boolean safeKind(String value) {
        return "audio".equals(value) || "preview".equals(value) || "chart".equals(value);
    }

    private static boolean safeFileName(String fileName) {
        if (fileName == null || fileName.isBlank() || fileName.length() > 255 || fileName.contains("..") || fileName.indexOf('\0') >= 0) {
            return false;
        }
        String normalized = fileName.replace('\\', '/');
        return !normalized.startsWith("/") && !normalized.matches("^[A-Za-z]:.*") && normalized.chars().noneMatch(Character::isISOControl);
    }

    private static boolean safeStoredFileName(String fileName) {
        return fileName != null && fileName.matches("[A-Za-z0-9._-]{1,128}") && !fileName.contains("..");
    }

    private static boolean safeHash(String hash) {
        return hash != null && hash.matches("[A-Fa-f0-9]{64}");
    }

    private static boolean validExtension(String kind, String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        return switch (kind) {
            case "audio" -> lower.endsWith(".ogg");
            case "preview" -> lower.endsWith(".png");
            case "chart" -> lower.endsWith(".json");
            default -> false;
        };
    }

    private static String extensionKind(Path path) {
        String lower = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (lower.endsWith(".ogg")) {
            return "audio";
        }
        if (lower.endsWith(".png")) {
            return "preview";
        }
        if (lower.endsWith(".json")) {
            return "chart";
        }
        return "";
    }

    private static int maxAssetBytes(String kind) {
        return switch (kind) {
            case "audio" -> MAX_AUDIO_BYTES;
            case "preview" -> MAX_PREVIEW_BYTES;
            case "chart" -> MAX_CHART_BYTES;
            default -> -1;
        };
    }

    private static boolean validAssetContents(String kind, Path path) throws IOException {
        if ("audio".equals(kind)) {
            try (InputStream input = Files.newInputStream(path)) {
                byte[] signature = input.readNBytes(4);
                if (signature.length != 4 || signature[0] != 'O' || signature[1] != 'g' || signature[2] != 'g' || signature[3] != 'S') {
                    return false;
                }
            }
            try {
                return ru.gora.musicbattle.server.MusicBattleTracks.readOggDurationTicks(path) > 0;
            } catch (IOException exception) {
                return false;
            }
        }
        if ("preview".equals(kind)) {
            try (InputStream input = Files.newInputStream(path)) {
                byte[] header = input.readNBytes(24);
                byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
                if (header.length < 24) {
                    return false;
                }
                for (int index = 0; index < png.length; index++) {
                    if (header[index] != png[index]) {
                        return false;
                    }
                }
                if (header[12] != 'I' || header[13] != 'H' || header[14] != 'D' || header[15] != 'R') {
                    return false;
                }
                int width = readInt(header, 16);
                int height = readInt(header, 20);
                return width > 0 && height > 0 && width <= MAX_PREVIEW_DIMENSION && height <= MAX_PREVIEW_DIMENSION
                        && (long) width * height <= MAX_PREVIEW_PIXELS;
            }
        }
        if ("chart".equals(kind)) {
            JsonObject root = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
            if (!root.has("id") || !root.get("id").isJsonPrimitive() || !root.has("notes") || !root.get("notes").isJsonArray()) {
                return false;
            }
            JsonArray notes = root.getAsJsonArray("notes");
            return notes.size() <= 4096;
        }
        return false;
    }

    private static int readInt(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFF) << 24
                | (bytes[offset + 1] & 0xFF) << 16
                | (bytes[offset + 2] & 0xFF) << 8
                | bytes[offset + 3] & 0xFF;
    }

    private static boolean hasSymbolicLink(Path root, Path directory) {
        if (Files.isSymbolicLink(root)) {
            return true;
        }
        Path current = root;
        for (Path segment : root.relativize(directory)) {
            current = current.resolve(segment);
            if (Files.isSymbolicLink(current)) {
                return true;
            }
        }
        return false;
    }

    private static String sha256(Path path) throws IOException {
        MessageDigest digest = sha256Digest();
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String sha256(byte[] bytes) {
        return HexFormat.of().formatHex(sha256Digest().digest(bytes));
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void moveReplacing(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void cleanup(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
        }
    }

    private static final class TransferState {
        private final String assetKind;
        private final int totalChunks;
        private final int totalBytes;
        private final String sha256;
        private final Path temporary;
        private final Path target;
        private int nextChunk;
        private int receivedBytes;

        private TransferState(String assetKind, int totalChunks, int totalBytes, String sha256, Path temporary, Path target) {
            this.assetKind = assetKind;
            this.totalChunks = totalChunks;
            this.totalBytes = totalBytes;
            this.sha256 = sha256;
            this.temporary = temporary;
            this.target = target;
        }

        private boolean matches(String assetKind, int totalChunks, int totalBytes, String sha256) {
            return this.assetKind.equals(assetKind) && this.totalChunks == totalChunks && this.totalBytes == totalBytes
                    && this.sha256.equalsIgnoreCase(sha256);
        }
    }
}

package ru.gora.musicbattle.workshop;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import ru.gora.musicbattle.workshop.BeatmapPackageException.Reason;

/**
 * Validates a {@code .blockymap} archive against its manifest and installs it atomically into
 * {@code music_battle_tracks/workshop/ws_<setId>/}. Same safety rules as the existing untrusted-map pipelines
 * (TrackPublicationManager on the server, ClientTrackAssets on the client), restated here without Minecraft
 * classes so they can be tested standalone:
 * <ul>
 *     <li>the archive is a regular file, not a symbolic link, within {@link #MAX_ARCHIVE_BYTES};</li>
 *     <li>at most {@link #MAX_ENTRIES} entries, flat lowercase names from a whitelist: no directories, no
 *     absolute paths, no {@code ..}, no separators of either kind, only json/ogg/png plus the manifest;</li>
 *     <li>every entry is declared in the manifest and every declared file is present; the only exception is an
 *     optional {@link WorkshopLayout#SIGNATURE_FILE_NAME}, which is allowed, not installed and not yet verified;</li>
 *     <li>each file is streamed with a hard cap at its declared size and must match its SHA-256 exactly,
 *     so the zip's own size fields are never trusted;</li>
 *     <li>content checks: Ogg Vorbis with a readable duration close to the declared one, PNG within
 *     2048x2048 and 4,194,304 px, chart JSON whose id and asset references match the installed folder;</li>
 *     <li>files are unpacked into a fresh staging folder next to the installed root and moved in with one
 *     rename; a failure at any step leaves the previous install untouched and no partial folder.</li>
 * </ul>
 * Not wired into anything yet: see the package documentation and docs/WORKSHOP.md.
 */
public final class BeatmapPackage {
    /** Manifest, audio, cover, signature and one file per chart. */
    public static final int MAX_ENTRIES = BeatmapManifest.MAX_CHARTS + 4;
    /** Everything a set may hold, plus room for zip headers. */
    public static final long MAX_ARCHIVE_BYTES = BeatmapManifest.MAX_AUDIO_BYTES + BeatmapManifest.MAX_COVER_BYTES
            + BeatmapManifest.MAX_CHARTS * BeatmapManifest.MAX_CHART_BYTES + BeatmapManifest.MAX_MANIFEST_BYTES + 1024L * 1024;
    /** A declared Vorbis duration may differ from the decoded one by this much (granule rounding, encoder padding). */
    static final long DURATION_TOLERANCE_MS = 1000;

    private static final int MAX_NOTES = 4096;
    private static final int MAX_EFFECTS = 512;
    private static final int MAX_COVER_DIMENSION = 2048;
    private static final long MAX_COVER_PIXELS = 4_194_304L;
    private static final Pattern SAFE_ENTRY_NAME = Pattern.compile("[a-z0-9_-]{1,120}\\.(?:json|ogg|png)");

    public enum Outcome {
        INSTALLED,
        /** A different version of the same set was installed and has been swapped out. */
        REPLACED,
        /** The identical manifest is installed and every declared file is present with its size; nothing written. */
        ALREADY_INSTALLED
    }

    public record InstallResult(Outcome outcome, BeatmapManifest manifest, Path directory) {
    }

    private BeatmapPackage() {
    }

    /**
     * Reads and validates only the manifest and the entry names, without unpacking. Enough for a
     * confirmation prompt ("install X by Y?") before {@link #install}.
     */
    public static BeatmapManifest readManifest(Path archive) throws IOException {
        requireArchiveFile(archive);
        try (ZipFile zip = open(archive)) {
            Map<String, ZipEntry> entries = safeEntries(zip);
            BeatmapManifest manifest = parseManifest(readManifestBytes(zip, entries));
            requireDeclaredEntries(manifest, entries);
            return manifest;
        }
    }

    /**
     * Installs {@code archive} under {@code tracksRoot} (the {@code music_battle_tracks} folder). Blocking
     * file IO and hashing up to about 190 MiB: call it off the render thread.
     */
    public static InstallResult install(Path archive, Path tracksRoot) throws IOException {
        requireArchiveFile(archive);
        Path installedRoot = WorkshopLayout.installedDirectory(tracksRoot);
        Path stagingRoot = WorkshopLayout.stagingDirectory(tracksRoot);
        try (ZipFile zip = open(archive)) {
            Map<String, ZipEntry> entries = safeEntries(zip);
            byte[] manifestBytes = readManifestBytes(zip, entries);
            BeatmapManifest manifest = parseManifest(manifestBytes);
            requireDeclaredEntries(manifest, entries);

            prepareRoot(installedRoot);
            prepareRoot(stagingRoot);
            Path destination = installedRoot.resolve(manifest.folderName()).normalize();
            if (!destination.getParent().equals(installedRoot)) {
                throw new BeatmapPackageException(Reason.UNSAFE_DESTINATION, manifest.folderName());
            }
            if (isInstalled(destination, manifest)) {
                return new InstallResult(Outcome.ALREADY_INSTALLED, manifest, destination);
            }

            Path staging = Files.createTempDirectory(stagingRoot, "set_");
            try {
                for (BeatmapManifest.FileEntry file : manifest.files()) {
                    extract(zip, entries.get(file.path()), staging.resolve(file.path()), file);
                }
                validateContents(manifest, staging);
                Files.write(staging.resolve(WorkshopLayout.MANIFEST_FILE_NAME), manifestBytes, StandardOpenOption.CREATE_NEW);
                // The site's signature travels with the set (spec 08 A5.3): without it an installed copy could never
                // be offered to a server again, because a repack would carry no proof of where it came from. It is
                // not a chart, audio or cover name, so neither the loader nor the catalog scan ever reads it.
                ZipEntry signature = entries.get(WorkshopLayout.SIGNATURE_FILE_NAME);
                if (signature != null) {
                    try (InputStream input = zip.getInputStream(signature)) {
                        byte[] bytes = input.readNBytes(WorkshopSignature.SIGNATURE_BYTES + 1);
                        if (bytes.length == WorkshopSignature.SIGNATURE_BYTES) {
                            Files.write(staging.resolve(WorkshopLayout.SIGNATURE_FILE_NAME), bytes, StandardOpenOption.CREATE_NEW);
                        }
                    }
                }
                Outcome outcome = commit(staging, destination, stagingRoot);
                return new InstallResult(outcome, manifest, destination);
            } finally {
                // After a successful commit the staging folder no longer exists and this does nothing.
                // A failed cleanup must not replace the reason the install failed.
                try {
                    deleteTree(staging, stagingRoot);
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static void requireArchiveFile(Path archive) throws IOException {
        if (archive == null || !Files.isRegularFile(archive, LinkOption.NOFOLLOW_LINKS)) {
            throw new BeatmapPackageException(Reason.NOT_A_FILE, archive == null ? "" : BeatmapPackageException.describe(String.valueOf(archive.getFileName())));
        }
        if (Files.size(archive) > MAX_ARCHIVE_BYTES) {
            throw new BeatmapPackageException(Reason.ARCHIVE_TOO_LARGE, Files.size(archive) + " bytes");
        }
    }

    private static ZipFile open(Path archive) throws IOException {
        try {
            return new ZipFile(archive.toFile(), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException exception) {
            throw new BeatmapPackageException(Reason.CORRUPT_ARCHIVE, "not a readable zip", exception);
        }
    }

    private static Map<String, ZipEntry> safeEntries(ZipFile zip) throws IOException {
        if (zip.size() > MAX_ENTRIES) {
            throw new BeatmapPackageException(Reason.TOO_MANY_ENTRIES, zip.size() + " entries, the cap is " + MAX_ENTRIES);
        }
        Map<String, ZipEntry> entries = new HashMap<>();
        try {
            Enumeration<? extends ZipEntry> enumeration = zip.entries();
            while (enumeration.hasMoreElements()) {
                ZipEntry entry = enumeration.nextElement();
                String name = entry.getName();
                boolean safe = !entry.isDirectory()
                        && (WorkshopLayout.MANIFEST_FILE_NAME.equals(name) || WorkshopLayout.SIGNATURE_FILE_NAME.equals(name)
                        || SAFE_ENTRY_NAME.matcher(name).matches());
                if (!safe) {
                    throw new BeatmapPackageException(Reason.UNSAFE_ENTRY_NAME, BeatmapPackageException.describe(name));
                }
                if (entries.put(name, entry) != null) {
                    throw new BeatmapPackageException(Reason.DUPLICATE_ENTRY, name);
                }
            }
        } catch (IllegalStateException | IllegalArgumentException exception) {
            throw new BeatmapPackageException(Reason.CORRUPT_ARCHIVE, "unreadable central directory", exception);
        }
        return entries;
    }

    private static byte[] readManifestBytes(ZipFile zip, Map<String, ZipEntry> entries) throws IOException {
        ZipEntry entry = entries.get(WorkshopLayout.MANIFEST_FILE_NAME);
        if (entry == null) {
            throw new BeatmapPackageException(Reason.MISSING_MANIFEST, WorkshopLayout.MANIFEST_FILE_NAME);
        }
        try (InputStream input = zip.getInputStream(entry)) {
            byte[] bytes = input.readNBytes(BeatmapManifest.MAX_MANIFEST_BYTES + 1);
            if (bytes.length > BeatmapManifest.MAX_MANIFEST_BYTES) {
                throw new BeatmapPackageException(Reason.FILE_TOO_LARGE, WorkshopLayout.MANIFEST_FILE_NAME);
            }
            return bytes;
        } catch (BeatmapPackageException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new BeatmapPackageException(Reason.CORRUPT_ARCHIVE, WorkshopLayout.MANIFEST_FILE_NAME, exception);
        }
    }

    private static BeatmapManifest parseManifest(byte[] bytes) throws BeatmapPackageException {
        try {
            return BeatmapManifest.parse(strictUtf8(bytes));
        } catch (CharacterCodingException exception) {
            throw new BeatmapPackageException(Reason.INVALID_MANIFEST, "not UTF-8", exception);
        }
    }

    private static void requireDeclaredEntries(BeatmapManifest manifest, Map<String, ZipEntry> entries) throws BeatmapPackageException {
        Set<String> declared = new HashSet<>();
        for (BeatmapManifest.FileEntry file : manifest.files()) {
            declared.add(file.path());
            if (!entries.containsKey(file.path())) {
                throw new BeatmapPackageException(Reason.MISSING_ENTRY, file.path());
            }
        }
        for (String name : entries.keySet()) {
            if (!name.equals(WorkshopLayout.MANIFEST_FILE_NAME) && !name.equals(WorkshopLayout.SIGNATURE_FILE_NAME)
                    && !declared.contains(name)) {
                throw new BeatmapPackageException(Reason.UNEXPECTED_ENTRY, name);
            }
        }
    }

    private static void prepareRoot(Path root) throws IOException {
        if (Files.isSymbolicLink(root)) {
            throw new BeatmapPackageException(Reason.UNSAFE_DESTINATION, String.valueOf(root.getFileName()) + " is a symbolic link");
        }
        Files.createDirectories(root);
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new BeatmapPackageException(Reason.UNSAFE_DESTINATION, String.valueOf(root.getFileName()) + " is not a directory");
        }
    }

    private static boolean isInstalled(Path destination, BeatmapManifest manifest) {
        try {
            Path manifestFile = destination.resolve(WorkshopLayout.MANIFEST_FILE_NAME);
            if (Files.isSymbolicLink(destination) || !Files.isRegularFile(manifestFile, LinkOption.NOFOLLOW_LINKS)
                    || Files.size(manifestFile) > BeatmapManifest.MAX_MANIFEST_BYTES) {
                return false;
            }
            if (!parseManifest(Files.readAllBytes(manifestFile)).equals(manifest)) {
                return false;
            }
            for (BeatmapManifest.FileEntry file : manifest.files()) {
                Path path = destination.resolve(file.path());
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) != file.size()) {
                    return false;
                }
            }
            return true;
        } catch (IOException exception) {
            return false;
        }
    }

    private static void extract(ZipFile zip, ZipEntry entry, Path target, BeatmapManifest.FileEntry expected) throws IOException {
        MessageDigest digest = sha256();
        long written = 0;
        // CREATE_NEW refuses an existing file or link at the target instead of writing through it.
        try (InputStream input = zip.getInputStream(entry);
             OutputStream output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                written += read;
                if (written > expected.size()) {
                    throw new BeatmapPackageException(Reason.SIZE_MISMATCH, expected.path() + " is larger than declared");
                }
                digest.update(buffer, 0, read);
                output.write(buffer, 0, read);
            }
        } catch (BeatmapPackageException exception) {
            throw exception;
        } catch (java.util.zip.ZipException exception) {
            throw new BeatmapPackageException(Reason.CORRUPT_ARCHIVE, expected.path(), exception);
        }
        if (written != expected.size()) {
            throw new BeatmapPackageException(Reason.SIZE_MISMATCH, expected.path() + " is smaller than declared");
        }
        if (!HexFormat.of().formatHex(digest.digest()).equals(expected.sha256())) {
            throw new BeatmapPackageException(Reason.HASH_MISMATCH, expected.path());
        }
    }

    private static void validateContents(BeatmapManifest manifest, Path staging) throws IOException {
        long setId = manifest.setId();
        String audioFile = WorkshopLayout.audioFileName(setId);
        if (manifest.audio().policy() == BeatmapManifest.AudioPolicy.INCLUDED) {
            long durationMs = oggVorbisDurationMs(staging.resolve(audioFile));
            if (Math.abs(durationMs - manifest.audio().durationMs()) > DURATION_TOLERANCE_MS) {
                throw new BeatmapPackageException(Reason.INVALID_AUDIO, "decoded " + durationMs + " ms, declared " + manifest.audio().durationMs() + " ms");
            }
        }
        String coverFile = WorkshopLayout.coverFileName(setId);
        if (manifest.hasCover()) {
            validateCover(staging.resolve(coverFile));
        }
        String expectedAudio = WorkshopLayout.catalogReference(setId, audioFile);
        String expectedPreview = manifest.hasCover() ? WorkshopLayout.catalogReference(setId, coverFile) : "";
        for (BeatmapManifest.Chart chart : manifest.charts()) {
            validateChart(staging.resolve(WorkshopLayout.chartFileName(setId, chart.key())),
                    WorkshopLayout.chartId(setId, chart.key()), expectedAudio, expectedPreview);
        }
    }

    /**
     * Structural check that lets the existing loader read the chart unchanged: the id is the file name,
     * asset paths point into the set folder (for sets with external audio, to where the player's file must go),
     * note and effect counts stay within the loader's caps. Per-note and per-effect range sanitising stays
     * with TrackPublicationManager's rules and is a later phase (docs/WORKSHOP.md).
     */
    private static void validateChart(Path chart, String expectedId, String expectedAudio, String expectedPreview) throws IOException {
        JsonObject root;
        try {
            String text = strictUtf8(Files.readAllBytes(chart));
            if (!BeatmapManifest.nestingWithin(text, BeatmapManifest.MAX_JSON_DEPTH)) {
                throw new BeatmapPackageException(Reason.INVALID_CHART, expectedId + ": nested too deep");
            }
            JsonElement element = JsonParser.parseString(text);
            if (!element.isJsonObject()) {
                throw new BeatmapPackageException(Reason.INVALID_CHART, expectedId + ": root is not an object");
            }
            root = element.getAsJsonObject();
        } catch (CharacterCodingException | RuntimeException exception) {
            throw new BeatmapPackageException(Reason.INVALID_CHART, expectedId + ": not UTF-8 JSON", exception);
        }
        if (!expectedId.equals(string(root, "id")) || !expectedAudio.equals(string(root, "audio"))
                || !expectedPreview.equals(string(root, "preview"))) {
            throw new BeatmapPackageException(Reason.INVALID_CHART, expectedId + ": id, audio or preview does not match the set folder");
        }
        JsonElement notes = root.get("notes");
        if (notes == null || !notes.isJsonArray() || notes.getAsJsonArray().size() > MAX_NOTES) {
            throw new BeatmapPackageException(Reason.INVALID_CHART, expectedId + ": notes must be an array of at most " + MAX_NOTES);
        }
        JsonElement effects = root.get("effects");
        if (effects != null && (!effects.isJsonArray() || ((JsonArray) effects).size() > MAX_EFFECTS)) {
            throw new BeatmapPackageException(Reason.INVALID_CHART, expectedId + ": effects must be an array of at most " + MAX_EFFECTS);
        }
    }

    private static String string(JsonObject root, String key) {
        JsonElement element = root.get(key);
        return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isString() ? element.getAsString() : null;
    }

    /**
     * Walks the Ogg pages like MusicBattleTracks.readOggInfo: the first packet must be a Vorbis identification
     * header (Opus and anything else is refused), the last granule position gives the length. This is a
     * container check, not a decode; the game's decoder remains the final word.
     */
    static long oggVorbisDurationMs(Path ogg) throws IOException {
        long sampleRate = -1;
        long lastGranule = -1;
        boolean first = true;
        try (InputStream input = Files.newInputStream(ogg)) {
            while (true) {
                byte[] header = input.readNBytes(27);
                if (header.length == 0) {
                    break;
                }
                if (header.length != 27 || header[0] != 'O' || header[1] != 'g' || header[2] != 'g' || header[3] != 'S') {
                    throw new BeatmapPackageException(Reason.INVALID_AUDIO, "not an Ogg page");
                }
                int segments = header[26] & 0xFF;
                byte[] lacing = input.readNBytes(segments);
                if (lacing.length != segments) {
                    throw new BeatmapPackageException(Reason.INVALID_AUDIO, "truncated Ogg page");
                }
                int payloadLength = 0;
                for (byte segment : lacing) {
                    payloadLength += segment & 0xFF;
                }
                byte[] payload = input.readNBytes(payloadLength);
                if (payload.length != payloadLength) {
                    throw new BeatmapPackageException(Reason.INVALID_AUDIO, "truncated Ogg payload");
                }
                if (first) {
                    first = false;
                    if (payload.length < 16 || payload[0] != 1 || payload[1] != 'v' || payload[2] != 'o' || payload[3] != 'r'
                            || payload[4] != 'b' || payload[5] != 'i' || payload[6] != 's') {
                        throw new BeatmapPackageException(Reason.INVALID_AUDIO, "only Ogg Vorbis is accepted");
                    }
                    sampleRate = ByteBuffer.wrap(payload, 12, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt() & 0xFFFFFFFFL;
                }
                long granule = ByteBuffer.wrap(header, 6, 8).order(java.nio.ByteOrder.LITTLE_ENDIAN).getLong();
                if (granule >= 0) {
                    lastGranule = granule;
                }
            }
        }
        if (sampleRate <= 0 || lastGranule <= 0 || lastGranule > Long.MAX_VALUE / 1000L) {
            throw new BeatmapPackageException(Reason.INVALID_AUDIO, "no readable duration");
        }
        return lastGranule * 1000L / sampleRate;
    }

    private static void validateCover(Path png) throws IOException {
        byte[] header;
        try (InputStream input = Files.newInputStream(png)) {
            header = input.readNBytes(24);
        }
        byte[] signature = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
        if (header.length < 24 || header[12] != 'I' || header[13] != 'H' || header[14] != 'D' || header[15] != 'R') {
            throw new BeatmapPackageException(Reason.INVALID_COVER, "not a PNG");
        }
        for (int i = 0; i < signature.length; i++) {
            if (header[i] != signature[i]) {
                throw new BeatmapPackageException(Reason.INVALID_COVER, "not a PNG");
            }
        }
        long width = ByteBuffer.wrap(header, 16, 4).getInt() & 0xFFFFFFFFL;
        long height = ByteBuffer.wrap(header, 20, 4).getInt() & 0xFFFFFFFFL;
        if (width == 0 || height == 0 || width > MAX_COVER_DIMENSION || height > MAX_COVER_DIMENSION || width * height > MAX_COVER_PIXELS) {
            throw new BeatmapPackageException(Reason.INVALID_COVER, width + "x" + height);
        }
    }

    /**
     * One rename puts the staged set in place. A previous version is first renamed out of the installed
     * folder into staging, so the installed folder never shows a mix of both; if the second rename fails the
     * previous version is renamed back.
     */
    private static Outcome commit(Path staging, Path destination, Path stagingRoot) throws IOException {
        if (!Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
            move(staging, destination);
            return Outcome.INSTALLED;
        }
        if (Files.isSymbolicLink(destination) || !Files.isDirectory(destination, LinkOption.NOFOLLOW_LINKS)) {
            throw new BeatmapPackageException(Reason.UNSAFE_DESTINATION, destination.getFileName() + " is not a plain folder");
        }
        Path retired = stagingRoot.resolve("replaced_" + UUID.randomUUID());
        move(destination, retired);
        try {
            move(staging, destination);
        } catch (IOException exception) {
            try {
                move(retired, destination);
            } catch (IOException restore) {
                exception.addSuppressed(restore);
            }
            throw exception;
        }
        try {
            deleteTree(retired, stagingRoot);
        } catch (IOException ignored) {
            // The new version is already in place; a leftover under staging is harmless and removed next time.
        }
        return Outcome.REPLACED;
    }

    private static void move(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target);
        }
    }

    /** Deletes a tree strictly below {@code allowedRoot}, never the root itself; links are removed, not followed. */
    public static void deleteTree(Path target, Path allowedRoot) throws IOException {
        Path root = allowedRoot.toAbsolutePath().normalize();
        Path normalized = target.toAbsolutePath().normalize();
        if (!normalized.startsWith(root) || normalized.equals(root) || !Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (var stream = Files.walk(normalized)) {
            List<Path> paths = stream.sorted(Comparator.reverseOrder()).toList();
            for (Path path : paths) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static String strictUtf8(byte[] bytes) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}

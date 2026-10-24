package ru.gora.musicbattle.client;

import static ru.gora.musicbattle.client.ClientTranslations.text;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.net.URI;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import net.minecraft.client.resources.language.I18n;
import ru.gora.musicbattle.data.BattleNote;
import ru.gora.musicbattle.data.ChartBeat;
import ru.gora.musicbattle.data.TrackEffect;
import ru.gora.musicbattle.server.MusicBattleTracks;

public final class ClientTrackExporter {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /**
     * The most notes a saved chart may hold. The track loader reads no more than this and battles carry no more, so a
     * longer chart would come back from disk without its ending and the next save would make that permanent.
     */
    public static final int MAX_NOTES = MusicBattleTracks.MAX_NOTES;

    private ClientTrackExporter() {
    }

    /**
     * A failure the player can fix, carried as a translation key so it reaches them in their own language. The
     * message is translated only when something asks for it, which keeps the checks that look at {@link #key()}
     * free of a running game.
     */
    public static class Problem extends IOException {
        private final String key;
        private final transient Object[] args;

        public Problem(String key, Object... args) {
            super(key);
            this.key = key;
            this.args = args == null ? new Object[0] : args.clone();
        }

        public String key() {
            return key;
        }

        @Override
        public String getMessage() {
            return I18n.get(key, args);
        }
    }

    /** How many notes over {@link #MAX_NOTES} a chart is, or zero when it fits. */
    public static int notesOverLimit(int noteCount) {
        return Math.max(0, noteCount - MAX_NOTES);
    }

    /**
     * What the player typed into a file box, made into something worth handing to the file system.
     *
     * <p>Explorer's "Copy as path" wraps the path in double quotes, and a path copied out of a terminal often comes in
     * single ones; neither is ever part of a real file name, so one matching pair around the whole value is dropped.
     */
    public static String cleanPathInput(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '"' || first == '\'') && last == first) {
                value = value.substring(1, value.length() - 1).trim();
            }
        }
        return value;
    }

    /** Whether a cleaned value is a web address rather than a file, which no box here can download. */
    public static boolean isWebAddress(String cleaned) {
        if (cleaned == null) {
            return false;
        }
        String lower = cleaned.toLowerCase(Locale.ROOT);
        if (lower.startsWith("file:")) {
            return false;
        }
        return lower.startsWith("www.") || lower.matches("^[a-z][a-z0-9+.\\-]*://.*");
    }

    /** {@link #parsePath(String, Function)} with this machine's own file system. */
    public static Path parsePath(String cleaned) {
        return parsePath(cleaned, Path::of);
    }

    /**
     * A cleaned value as a path, or {@code null} when it cannot be one.
     *
     * <p>On Windows {@code Path.of} throws for {@code < > " | ? *} and for a colon anywhere but after the drive letter,
     * so a pasted URL or a quoted path used to crash the game from inside the render loop, where the editor resolves
     * its preview image every frame. Every caller treats {@code null} as "not a file". The parser is a parameter only
     * so the check can stand in for the Windows file system on a machine that does not have one.
     */
    public static Path parsePath(String cleaned, Function<String, Path> parser) {
        if (cleaned == null || cleaned.isBlank() || isWebAddress(cleaned)) {
            return null;
        }
        try {
            if (cleaned.regionMatches(true, 0, "file:", 0, 5)) {
                // A file dragged out of a browser's download list arrives as a file: URI.
                return Path.of(URI.create(cleaned.replace(" ", "%20")));
            }
            return parser.apply(cleaned);
        } catch (IllegalArgumentException | java.nio.file.FileSystemNotFoundException exception) {
            // InvalidPathException is the IllegalArgumentException Windows throws; a bad file: URI throws the plain one.
            return null;
        }
    }

    /**
     * The same check as {@link #parsePath(String)}, but as the reason the player gets when a file was required:
     * a web address and an unusable path each say what to do instead of "not found".
     */
    public static Path requirePath(String cleaned) throws Problem {
        if (isWebAddress(cleaned)) {
            throw new Problem("music_battle.l10n.editor.path_web_address");
        }
        Path path = parsePath(cleaned);
        if (path == null) {
            throw new Problem("music_battle.l10n.editor.path_unusable", cleaned == null ? "" : cleaned);
        }
        return path;
    }

    /** A copy or a hand-built chart without a first-beat offset; it writes exactly what it wrote before offsets. */
    public static Path export(String rawId, String title, String description, int bpm, float volume, String audioPath, String previewPath,
                              boolean disableStageBars, boolean hideStandardDimming,
                              List<BattleNote> notes, List<TrackEffect> effects) throws IOException {
        return export(rawId, title, description, bpm, 0, volume, audioPath, previewPath, disableStageBars, hideStandardDimming,
                notes, effects);
    }

    public static Path export(String rawId, String title, String description, int bpm, int offsetMs, float volume,
                              String audioPath, String previewPath, boolean disableStageBars, boolean hideStandardDimming,
                              List<BattleNote> notes, List<TrackEffect> effects) throws IOException {
        String id = safeId(rawId);
        if (id.isBlank()) {
            throw new IOException("Track id is empty");
        }

        // Refused before anything touches the disk: the loader would keep only the first MAX_NOTES of them, so the
        // ending would be gone the next time the chart was opened, and saving it then would lose it for good.
        int over = notesOverLimit(notes.size());
        if (over > 0) {
            throw new Problem("music_battle.l10n.editor.too_many_notes_save", notes.size(), MAX_NOTES, over);
        }
        // Both files are checked before either is copied, so a missing image can no longer leave a folder holding
        // only the song and no chart - which the library does not list and the next save had to work around.
        Path audioSource = assetSource(audioPath, ".ogg");
        Path previewSource = assetSource(previewPath, ".png");

        Path rootDirectory = MusicBattleTracks.ensureTracksDirectory();
        Path directory = rootDirectory.resolve(id).toAbsolutePath().normalize();
        if (!directory.startsWith(rootDirectory)) {
            throw new IOException("Unsafe track folder");
        }
        Files.createDirectories(directory);
        String audioFile = copyAsset(rootDirectory, directory, id, audioSource, ".ogg");
        String previewFile = copyAsset(rootDirectory, directory, id + "_preview", previewSource, ".png");

        List<BattleNote> sorted = notes.stream()
                .sorted(Comparator.comparingInt(BattleNote::tick).thenComparingInt(BattleNote::owner).thenComparingInt(BattleNote::lane))
                .toList();
        int lastNoteTick = sorted.stream().mapToInt(note -> note.tick() + note.durationTicks()).max().orElse(20 * 20);
        int lastEffectTick = effects.stream().mapToInt(TrackEffect::endTick).max().orElse(0);
        int durationTicks = Math.max(10 * 20, Math.max(lastNoteTick, lastEffectTick) + 5 * 20);

        JsonObject root = new JsonObject();
        root.addProperty("id", id);
        root.addProperty("title", title == null || title.isBlank() ? id : title.trim());
        String normalizedDescription = description == null ? "" : description.trim();
        root.addProperty("description", normalizedDescription);
        root.addProperty("playerPartyOrderVersion", 2);
        root.addProperty("bpm", Math.max(20, Math.min(400, bpm)));
        ChartBeat.writeOffsetMs(root, offsetMs);
        root.addProperty("volume", Math.max(0.0F, Math.min(2.0F, volume)));
        root.addProperty("durationTicks", durationTicks);
        root.addProperty("audio", audioFile);
        root.addProperty("preview", previewFile);
        root.addProperty("disableStageBars", disableStageBars);
        root.addProperty("hideStandardDimming", hideStandardDimming);
        root.addProperty("fxRowsPerLayer", TrackEffect.FX_ROWS_PER_LAYER);
        JsonArray array = new JsonArray();
        for (BattleNote note : sorted) {
            JsonObject object = new JsonObject();
            object.addProperty("owner", note.owner());
            object.addProperty("lane", note.lane());
            object.addProperty("tick", note.tick());
            object.addProperty("durationTicks", note.durationTicks());
            array.add(object);
        }
        root.add("notes", array);
        JsonArray effectArray = new JsonArray();
        effects.stream().sorted(Comparator.comparingInt(TrackEffect::tick).thenComparing(effect -> effect.type().id())).forEach(effect -> {
            JsonObject object = new JsonObject();
            object.addProperty("type", effect.type().id());
            object.addProperty("lane", effect.lane());
            object.addProperty("layer", effect.layer());
            object.addProperty("tick", effect.tick());
            object.addProperty("durationTicks", effect.durationTicks());
            object.addProperty("intensity", effect.intensity());
            object.addProperty("softness", effect.softness());
            object.addProperty("colorA", effect.colorA());
            object.addProperty("colorB", effect.colorB());
            object.addProperty("text", effect.text());
            object.addProperty("font", effect.font());
            object.addProperty("positionX", effect.positionX());
            object.addProperty("positionY", effect.positionY());
            object.addProperty("animation", effect.animation());
            object.addProperty("exitAnimation", effect.exitAnimation());
            object.addProperty("variant", effect.variant());
            object.addProperty("beatReactive", effect.beatReactive());
            object.addProperty("opacity", effect.opacity());
            object.addProperty("textSpeed", effect.textSpeed());
            object.addProperty("outlineThickness", effect.outlineThickness());
            object.addProperty("glowStrength", effect.glowStrength());
            effect.settings().writeJson(object);
            effectArray.add(object);
        });
        root.add("effects", effectArray);

        Path output = directory.resolve(id + ".json").toAbsolutePath().normalize();
        if (!output.startsWith(directory)) {
            throw new IOException("Unsafe output path");
        }
        // Written beside the chart and moved over it, so a full disk or a crash mid-write leaves the previous save
        // in place instead of a half-written file the loader skips.
        Path temporary = output.resolveSibling(output.getFileName() + ".tmp");
        Files.writeString(temporary, GSON.toJson(root));
        try {
            Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING);
        }
        return output;
    }

    public static Path exportRenamed(String previousId, String rawId, String title, String description, int bpm, int offsetMs, float volume,
                                     String audioPath, String previewPath, boolean disableStageBars, boolean hideStandardDimming,
                                     List<BattleNote> notes, List<TrackEffect> effects) throws IOException {
        String oldId = safeId(previousId);
        String newId = safeId(rawId);
        Path root = MusicBattleTracks.ensureTracksDirectory();
        Path newDirectory = root.resolve(newId).toAbsolutePath().normalize();
        if (!oldId.equals(newId) && Files.isDirectory(newDirectory)) {
            try (var files = Files.list(newDirectory)) {
                if (files.anyMatch(path -> path.getFileName().toString().toLowerCase().endsWith(".json"))) {
                    throw new IOException(text("music_battle.l10n.client.017") + newId + text("music_battle.l10n.client.018"));
                }
            }
        }
        Path output = export(rawId, title, description, bpm, offsetMs, volume, audioPath, previewPath,
                disableStageBars, hideStandardDimming, notes, effects);
        if (!oldId.isBlank() && !oldId.equals(newId)) {
            deleteTrack(oldId);
        }
        return output;
    }

    public static String normalizeId(String input) {
        return safeId(input);
    }

    public static void deleteTrack(String id) throws IOException {
        String safe = safeId(id);
        if (safe.isBlank()) {
            throw new IOException("Track id is empty");
        }
        Path root = MusicBattleTracks.ensureTracksDirectory();
        Path directory = root.resolve(safe).toAbsolutePath().normalize();
        if (Files.isDirectory(directory) && directory.startsWith(root)) {
            try (var stream = Files.walk(directory)) {
                for (Path path : stream.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
            return;
        }
        Files.deleteIfExists(root.resolve(safe + ".json").toAbsolutePath().normalize());
    }

    /** The file a box points at, checked to exist and to be the right kind, or {@code null} when the box is empty. */
    private static Path assetSource(String rawPath, String requiredExtension) throws IOException {
        String cleaned = cleanPathInput(rawPath);
        if (cleaned.isEmpty()) {
            return null;
        }
        Path source = resolveRawOrAbsolute(requirePath(cleaned));
        if (!Files.isRegularFile(source)) {
            throw new IOException("Asset not found: " + MusicBattleTracks.displayPath(source));
        }
        String lower = source.getFileName().toString().toLowerCase(Locale.ROOT);
        boolean preview = ".png".equals(requiredExtension);
        if (preview ? !ClientPreviewProcessor.isSupported(source) : !lower.endsWith(requiredExtension)) {
            throw new IOException("Asset must be " + requiredExtension + ": " + MusicBattleTracks.displayPath(source));
        }
        return source;
    }

    /*
     * The 1.5x and 2x copies of the song are not made here any more. Making them needs ffmpeg, which the mod does not
     * ship and most players do not have, so every save of a chart with a song failed without it; with it, the two
     * encodes held the render thread for up to a minute each on the first save. Nothing on the client reads those
     * copies: battles play from the server's catalogue, and the server builds the copy it needs on its own worker
     * (MusicBattleTracks.requestSpeedVariant) when a speed is voted for, falling back to 1x with a chat line when it
     * cannot.
     */
    private static String copyAsset(Path rootDirectory, Path directory, String id, Path source, String requiredExtension) throws IOException {
        if (source == null) {
            return "";
        }
        boolean preview = ".png".equals(requiredExtension);
        String fileName = id + requiredExtension;
        Path target = directory.resolve(fileName).toAbsolutePath().normalize();
        if (!target.startsWith(directory)) {
            throw new IOException("Unsafe asset path");
        }
        if (!preview && Files.exists(target) && Files.isSameFile(source, target)) {
            return rootDirectory.relativize(target).toString().replace('\\', '/');
        }
        if (preview) {
            ClientPreviewProcessor.normalizeToPng(source, target);
        } else {
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return rootDirectory.relativize(target).toString().replace('\\', '/');
    }

    private static Path resolveRawOrAbsolute(Path path) {
        if (!path.isAbsolute()) {
            Path raw = MusicBattleTracks.rawDirectory().resolve(path).toAbsolutePath().normalize();
            if (Files.exists(raw)) {
                return raw;
            }
            path = MusicBattleTracks.tracksDirectory().resolve(path);
        }
        return path.toAbsolutePath().normalize();
    }

    private static String safeId(String input) {
        String normalized = input == null ? "" : input.toLowerCase().trim().replaceAll("[^a-z0-9_\\-]", "_");
        return normalized.length() <= 96 ? normalized : normalized.substring(0, 96);
    }
}

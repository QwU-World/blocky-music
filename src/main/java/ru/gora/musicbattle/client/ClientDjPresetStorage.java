package ru.gora.musicbattle.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import net.neoforged.fml.loading.FMLPaths;
import ru.gora.musicbattle.MusicBattleMod;
import ru.gora.musicbattle.block.entity.SpeakerBlockEntity;
import ru.gora.musicbattle.data.DjVisualEffectConfig;
import ru.gora.musicbattle.server.MusicBattleTracks;
import ru.gora.musicbattle.data.DjVisualEffects;

/**
 * Client-local DJ preset library stored directly below the active Minecraft game directory.
 *
 * <p>Display names never become path fragments. Each file is addressed by a SHA-256 digest of
 * its normalized name, which keeps Cyrillic and other user-facing names intact without allowing
 * path traversal. Presets are written to a sibling temporary file and atomically replaced when
 * the filesystem supports it.</p>
 */
public final class ClientDjPresetStorage {
    public static final String DIRECTORY_NAME = "music_battle_dj_presets";
    public static final int MAX_DISPLAY_NAME_CODE_POINTS = 64;
    public static final int MAX_PRESETS_PER_CATEGORY = 128;
    public static final int MAX_PRESET_FILE_BYTES = 262_144;

    private static final int FORMAT_VERSION = 1;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final LinkOption[] NO_FOLLOW = {LinkOption.NOFOLLOW_LINKS};

    private ClientDjPresetStorage() {
    }

    /** Returns the requested Minecraft-relative storage root without creating it. */
    public static Path rootDirectory() {
        Path gameDirectory = FMLPaths.GAMEDIR.get().toAbsolutePath().normalize();
        Path root = gameDirectory.resolve(DIRECTORY_NAME).toAbsolutePath().normalize();
        if (!root.startsWith(gameDirectory)) {
            throw new IllegalStateException("DJ preset directory escaped the Minecraft game directory");
        }
        return root;
    }

    public static synchronized List<EffectSetPreset> listEffectSets() {
        return list(Category.EFFECT_SET, ClientDjPresetStorage::readEffectSet);
    }

    public static synchronized Optional<EffectSetPreset> loadEffectSet(String displayName) {
        return load(Category.EFFECT_SET, displayName, ClientDjPresetStorage::readEffectSet);
    }

    public static synchronized boolean saveEffectSet(String displayName, boolean trackFxEnabled,
                                                     boolean djFxEnabled,
                                                     List<DjVisualEffectConfig> configs) {
        final EffectSetPreset preset;
        try {
            preset = new EffectSetPreset(displayName, trackFxEnabled, djFxEnabled, configs);
        } catch (IllegalArgumentException exception) {
            MusicBattleMod.LOGGER.warn("Refused invalid Blocky Music DJ effect preset name.", exception);
            return false;
        }

        JsonObject root = baseJson(Category.EFFECT_SET, preset.name());
        root.addProperty("trackFxEnabled", preset.trackFxEnabled());
        root.addProperty("djFxEnabled", preset.djFxEnabled());
        JsonArray effects = new JsonArray();
        preset.configs().forEach(config -> effects.add(config.toJson()));
        root.add("effects", effects);
        return save(Category.EFFECT_SET, preset.name(), root);
    }

    public static synchronized boolean deleteEffectSet(String displayName) {
        return delete(Category.EFFECT_SET, displayName);
    }

    public static synchronized List<EqualizerPreset> listEqualizers() {
        return list(Category.EQUALIZER, ClientDjPresetStorage::readEqualizer);
    }

    public static synchronized Optional<EqualizerPreset> loadEqualizer(String displayName) {
        return load(Category.EQUALIZER, displayName, ClientDjPresetStorage::readEqualizer);
    }

    public static synchronized boolean saveEqualizer(String displayName, float lowGainDb,
                                                     float midGainDb, float highGainDb) {
        final EqualizerPreset preset;
        try {
            preset = new EqualizerPreset(displayName, lowGainDb, midGainDb, highGainDb);
        } catch (IllegalArgumentException exception) {
            MusicBattleMod.LOGGER.warn("Refused invalid Blocky Music DJ equalizer preset name.", exception);
            return false;
        }

        JsonObject root = baseJson(Category.EQUALIZER, preset.name());
        root.addProperty("lowGainDb", preset.lowGainDb());
        root.addProperty("midGainDb", preset.midGainDb());
        root.addProperty("highGainDb", preset.highGainDb());
        return save(Category.EQUALIZER, preset.name(), root);
    }

    public static synchronized boolean deleteEqualizer(String displayName) {
        return delete(Category.EQUALIZER, displayName);
    }

    public static synchronized List<AudioPreset> listAudioPresets() {
        return list(Category.DJ_AUDIO, ClientDjPresetStorage::readAudioPreset);
    }

    public static synchronized Optional<AudioPreset> loadAudioPreset(String displayName) {
        return load(Category.DJ_AUDIO, displayName, ClientDjPresetStorage::readAudioPreset);
    }

    public static synchronized boolean saveAudioPreset(String displayName,
                                                        float radius, float volume,
                                                        float lowGainDb, float midGainDb, float highGainDb,
                                                        boolean dspEnabled,
                                                        boolean reverbEnabled, float reverbWet,
                                                        float reverbRoom, float reverbDamping,
                                                        boolean echoEnabled, float echoWet,
                                                        float echoTimeMs, float echoFeedback,
                                                        boolean lowPassEnabled, float lowPassCutoffHz) {
        final AudioPreset preset;
        try {
            preset = new AudioPreset(displayName, radius, volume, lowGainDb, midGainDb, highGainDb,
                    dspEnabled, reverbEnabled, reverbWet, reverbRoom, reverbDamping,
                    echoEnabled, echoWet, echoTimeMs, echoFeedback,
                    lowPassEnabled, lowPassCutoffHz);
        } catch (IllegalArgumentException exception) {
            MusicBattleMod.LOGGER.warn("Refused invalid Blocky Music DJ audio preset.", exception);
            return false;
        }

        JsonObject root = baseJson(Category.DJ_AUDIO, preset.name());
        root.addProperty("radius", preset.radius());
        root.addProperty("volume", preset.volume());
        root.addProperty("lowGainDb", preset.lowGainDb());
        root.addProperty("midGainDb", preset.midGainDb());
        root.addProperty("highGainDb", preset.highGainDb());
        root.addProperty("dspEnabled", preset.dspEnabled());
        root.addProperty("reverbEnabled", preset.reverbEnabled());
        root.addProperty("reverbWet", preset.reverbWet());
        root.addProperty("reverbRoom", preset.reverbRoom());
        root.addProperty("reverbDamping", preset.reverbDamping());
        root.addProperty("echoEnabled", preset.echoEnabled());
        root.addProperty("echoWet", preset.echoWet());
        root.addProperty("echoTimeMs", preset.echoTimeMs());
        root.addProperty("echoFeedback", preset.echoFeedback());
        root.addProperty("lowPassEnabled", preset.lowPassEnabled());
        root.addProperty("lowPassCutoffHz", preset.lowPassCutoffHz());
        return save(Category.DJ_AUDIO, preset.name(), root);
    }

    public static synchronized boolean deleteAudioPreset(String displayName) {
        return delete(Category.DJ_AUDIO, displayName);
    }

    /**
     * Cleans a display name while preserving normal Unicode (including Cyrillic and emoji).
     * Invalid filename/control characters are converted to a single separating space.
     */
    public static String sanitizeDisplayName(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Preset name is required");
        }
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFC);
        StringBuilder result = new StringBuilder(Math.min(normalized.length(), 96));
        boolean separatorPending = false;
        int codePointCount = 0;
        for (int offset = 0; offset < normalized.length();) {
            int codePoint = normalized.codePointAt(offset);
            offset += Character.charCount(codePoint);
            boolean separator = Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)
                    || Character.isISOControl(codePoint) || Character.getType(codePoint) == Character.FORMAT
                    || isUnsafeNameCharacter(codePoint);
            if (separator) {
                separatorPending = result.length() > 0;
                continue;
            }
            if (codePointCount >= MAX_DISPLAY_NAME_CODE_POINTS) {
                break;
            }
            if (separatorPending && codePointCount < MAX_DISPLAY_NAME_CODE_POINTS) {
                result.append(' ');
                codePointCount++;
            }
            separatorPending = false;
            if (codePointCount >= MAX_DISPLAY_NAME_CODE_POINTS) {
                break;
            }
            result.appendCodePoint(codePoint);
            codePointCount++;
        }
        String safe = result.toString().strip();
        if (safe.isEmpty()) {
            throw new IllegalArgumentException("Preset name is empty after sanitizing");
        }
        return safe;
    }

    /**
     * Identity used by preset filenames and UI comparisons. The returned text is safe for
     * display and normalized consistently across case and Unicode compatibility forms.
     */
    public static String canonicalDisplayName(String value) {
        return Normalizer.normalize(sanitizeDisplayName(value), Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
    }

    /** Safe identity comparison for UI input; invalid or empty names never match. */
    public static boolean samePresetName(String first, String second) {
        try {
            return canonicalDisplayName(first).equals(canonicalDisplayName(second));
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static boolean isUnsafeNameCharacter(int codePoint) {
        return codePoint == '/' || codePoint == '\\' || codePoint == ':' || codePoint == '*'
                || codePoint == '?' || codePoint == '"' || codePoint == '<' || codePoint == '>'
                || codePoint == '|';
    }

    private static <T extends NamedPreset> List<T> list(Category category, PresetReader<T> reader) {
        try {
            Path directory = ensureCategoryDirectory(category);
            List<T> presets = new ArrayList<>();
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory, "*.json")) {
                for (Path entry : entries) {
                    if (presets.size() >= MAX_PRESETS_PER_CATEGORY || !isCategoryFile(category, entry)) {
                        continue;
                    }
                    try {
                        T preset = reader.read(entry);
                        if (preset != null && targetPath(directory, category, preset.name()).equals(entry.normalize())) {
                            presets.add(preset);
                        } else {
                            MusicBattleMod.LOGGER.warn("Ignored mismatched Blocky Music DJ preset '{}'.", entry);
                        }
                    } catch (Exception exception) {
                        MusicBattleMod.LOGGER.warn("Ignored unreadable Blocky Music DJ preset '{}'.", entry, exception);
                    }
                }
            }
            presets.sort(Comparator.comparing(preset -> canonicalName(preset.name())));
            return List.copyOf(presets);
        } catch (Exception exception) {
            MusicBattleMod.LOGGER.warn("Unable to list Blocky Music DJ {} presets.", category.kind, exception);
            return List.of();
        }
    }

    private static <T extends NamedPreset> Optional<T> load(Category category, String displayName,
                                                             PresetReader<T> reader) {
        try {
            String safeName = sanitizeDisplayName(displayName);
            Path directory = ensureCategoryDirectory(category);
            Path target = targetPath(directory, category, safeName);
            if (!Files.isRegularFile(target, NO_FOLLOW) || Files.isSymbolicLink(target)) {
                return Optional.empty();
            }
            T preset = reader.read(target);
            if (preset == null || !canonicalName(preset.name()).equals(canonicalName(safeName))) {
                MusicBattleMod.LOGGER.warn("Ignored mismatched Blocky Music DJ preset '{}'.", target);
                return Optional.empty();
            }
            return Optional.of(preset);
        } catch (Exception exception) {
            MusicBattleMod.LOGGER.warn("Unable to load Blocky Music DJ {} preset '{}'.",
                    category.kind, displayName, exception);
            return Optional.empty();
        }
    }

    private static boolean save(Category category, String displayName, JsonObject json) {
        Path temporary = null;
        try {
            Path directory = ensureCategoryDirectory(category);
            Path target = targetPath(directory, category, displayName);
            boolean targetExists = Files.exists(target, NO_FOLLOW);
            if (targetExists && (Files.isSymbolicLink(target) || !Files.isRegularFile(target, NO_FOLLOW))) {
                throw new IOException("Preset target is not a regular file: " + MusicBattleTracks.displayPath(target));
            }
            if (!targetExists && countCategoryFiles(directory, category) >= MAX_PRESETS_PER_CATEGORY) {
                throw new IOException("Preset limit reached for " + category.kind);
            }

            byte[] bytes = GSON.toJson(json).getBytes(StandardCharsets.UTF_8);
            if (bytes.length > MAX_PRESET_FILE_BYTES) {
                throw new IOException("Preset JSON exceeds " + MAX_PRESET_FILE_BYTES + " bytes");
            }
            temporary = Files.createTempFile(directory, "." + category.prefix, ".tmp");
            Files.write(temporary, bytes, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            moveAtomically(temporary, target);
            temporary = null;
            return true;
        } catch (Exception exception) {
            MusicBattleMod.LOGGER.warn("Unable to save Blocky Music DJ {} preset '{}'.",
                    category.kind, displayName, exception);
            return false;
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static boolean delete(Category category, String displayName) {
        try {
            String safeName = sanitizeDisplayName(displayName);
            Path directory = ensureCategoryDirectory(category);
            Path target = targetPath(directory, category, safeName);
            if (!Files.exists(target, NO_FOLLOW)) {
                return false;
            }
            if (Files.isSymbolicLink(target) || !Files.isRegularFile(target, NO_FOLLOW)) {
                throw new IOException("Refused to delete a non-regular preset target: " + MusicBattleTracks.displayPath(target));
            }
            return Files.deleteIfExists(target);
        } catch (Exception exception) {
            MusicBattleMod.LOGGER.warn("Unable to delete Blocky Music DJ {} preset '{}'.",
                    category.kind, displayName, exception);
            return false;
        }
    }

    private static EffectSetPreset readEffectSet(Path path) throws IOException {
        JsonObject root = readRoot(path, Category.EFFECT_SET);
        requireExactFields(root, "formatVersion", "kind", "name", "trackFxEnabled", "djFxEnabled", "effects");
        String name = requiredString(root, "name");
        boolean trackFxEnabled = requiredBoolean(root, "trackFxEnabled");
        boolean djFxEnabled = requiredBoolean(root, "djFxEnabled");
        JsonArray array = requiredArray(root, "effects");
        if (array.size() > DjVisualEffectConfig.MAX_CONFIGS) {
            throw new IOException("DJ effect preset exceeds the supported effect count");
        }
        List<DjVisualEffectConfig> configs = new ArrayList<>();
        for (JsonElement element : array) {
            if (!element.isJsonObject()) {
                throw new IOException("DJ effect preset contains a non-object effect entry");
            }
            configs.add(readStrictEffect(element.getAsJsonObject()));
        }
        return new EffectSetPreset(name, trackFxEnabled, djFxEnabled, configs);
    }

    private static EqualizerPreset readEqualizer(Path path) throws IOException {
        JsonObject root = readRoot(path, Category.EQUALIZER);
        requireExactFields(root, "formatVersion", "kind", "name", "lowGainDb", "midGainDb", "highGainDb");
        return new EqualizerPreset(requiredString(root, "name"),
                requiredEqGain(root, "lowGainDb"),
                requiredEqGain(root, "midGainDb"),
                requiredEqGain(root, "highGainDb"));
    }

    private static AudioPreset readAudioPreset(Path path) throws IOException {
        JsonObject root = readRoot(path, Category.DJ_AUDIO);
        requireExactFields(root, "formatVersion", "kind", "name",
                "radius", "volume", "lowGainDb", "midGainDb", "highGainDb",
                "dspEnabled", "reverbEnabled", "reverbWet", "reverbRoom", "reverbDamping",
                "echoEnabled", "echoWet", "echoTimeMs", "echoFeedback",
                "lowPassEnabled", "lowPassCutoffHz");
        return new AudioPreset(requiredString(root, "name"),
                requiredRange(root, "radius", 2.0F, 64.0F),
                requiredRange(root, "volume", 0.0F, 2.0F),
                requiredEqGain(root, "lowGainDb"),
                requiredEqGain(root, "midGainDb"),
                requiredEqGain(root, "highGainDb"),
                requiredBoolean(root, "dspEnabled"),
                requiredBoolean(root, "reverbEnabled"),
                requiredRange(root, "reverbWet", 0.0F, 1.0F),
                requiredRange(root, "reverbRoom", 0.0F, 1.0F),
                requiredRange(root, "reverbDamping", 0.0F, 1.0F),
                requiredBoolean(root, "echoEnabled"),
                requiredRange(root, "echoWet", 0.0F, 0.75F),
                requiredRange(root, "echoTimeMs", 40.0F, 1_000.0F),
                requiredRange(root, "echoFeedback", 0.0F, 0.85F),
                requiredBoolean(root, "lowPassEnabled"),
                requiredRange(root, "lowPassCutoffHz", 200.0F, 20_000.0F));
    }

    private static JsonObject readRoot(Path path, Category category) throws IOException {
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(readBoundedUtf8(path));
        } catch (RuntimeException exception) {
            throw new IOException("Invalid preset JSON", exception);
        }
        if (!parsed.isJsonObject()) {
            throw new IOException("Preset root is not an object");
        }
        JsonObject root = parsed.getAsJsonObject();
        if (requiredInteger(root, "formatVersion") != FORMAT_VERSION
                || !category.kind.equals(requiredString(root, "kind"))) {
            throw new IOException("Unsupported or mismatched preset format");
        }
        return root;
    }

    private static String readBoundedUtf8(Path path) throws IOException {
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, NO_FOLLOW)) {
            throw new IOException("Preset is not a regular file: " + MusicBattleTracks.displayPath(path));
        }
        byte[] bytes;
        try (InputStream input = Files.newInputStream(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            bytes = input.readNBytes(MAX_PRESET_FILE_BYTES + 1);
        }
        if (bytes.length > MAX_PRESET_FILE_BYTES) {
            throw new IOException("Preset exceeds " + MAX_PRESET_FILE_BYTES + " bytes");
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (java.nio.charset.CharacterCodingException exception) {
            throw new IOException("Preset is not valid UTF-8", exception);
        }
    }

    private static JsonObject baseJson(Category category, String displayName) {
        JsonObject root = new JsonObject();
        root.addProperty("formatVersion", FORMAT_VERSION);
        root.addProperty("kind", category.kind);
        root.addProperty("name", displayName);
        return root;
    }

    private static Path ensureCategoryDirectory(Category category) throws IOException {
        Path root = rootDirectory();
        ensureDirectDirectory(root);
        Path directory = root.resolve(category.directory).normalize();
        if (!directory.getParent().equals(root)) {
            throw new IOException("DJ preset category escaped its root");
        }
        ensureDirectDirectory(directory);
        return directory;
    }

    private static void ensureDirectDirectory(Path directory) throws IOException {
        try {
            Files.createDirectory(directory);
        } catch (FileAlreadyExistsException ignored) {
        }
        if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory, NO_FOLLOW)) {
            throw new IOException("Refused unsafe DJ preset directory: " + MusicBattleTracks.displayPath(directory));
        }
    }

    private static Path targetPath(Path directory, Category category, String displayName) {
        String fileName = category.prefix + digest(canonicalDisplayName(displayName)) + ".json";
        Path target = directory.resolve(fileName).normalize();
        if (!target.getParent().equals(directory)) {
            throw new IllegalStateException("DJ preset target escaped its category directory");
        }
        return target;
    }

    private static boolean isCategoryFile(Category category, Path path) {
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, NO_FOLLOW)) {
            return false;
        }
        String fileName = path.getFileName().toString();
        int expectedLength = category.prefix.length() + 64 + ".json".length();
        if (fileName.length() != expectedLength || !fileName.startsWith(category.prefix)
                || !fileName.endsWith(".json")) {
            return false;
        }
        int hashEnd = category.prefix.length() + 64;
        for (int index = category.prefix.length(); index < hashEnd; index++) {
            char character = fileName.charAt(index);
            if (!(character >= '0' && character <= '9' || character >= 'a' && character <= 'f')) {
                return false;
            }
        }
        return true;
    }

    private static int countCategoryFiles(Path directory, Category category) throws IOException {
        int count = 0;
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory, "*.json")) {
            for (Path entry : entries) {
                if (isCategoryFile(category, entry) && ++count >= MAX_PRESETS_PER_CATEGORY) {
                    break;
                }
            }
        }
        return count;
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String canonicalName(String displayName) {
        return canonicalDisplayName(displayName);
    }

    private static String digest(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String requiredString(JsonObject object, String key) throws IOException {
        JsonElement element = object.get(key);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw new IOException("Missing or non-string preset field: " + key);
        }
        String value;
        try {
            value = element.getAsString();
        } catch (RuntimeException exception) {
            throw new IOException("Invalid string preset field: " + key, exception);
        }
        if (value.isBlank()) {
            throw new IOException("Missing preset field: " + key);
        }
        return value;
    }

    private static boolean requiredBoolean(JsonObject object, String key) throws IOException {
        JsonElement element = object.get(key);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isBoolean()) {
            throw new IOException("Missing or non-boolean preset field: " + key);
        }
        return element.getAsBoolean();
    }

    private static int requiredInteger(JsonObject object, String key) throws IOException {
        JsonElement element = object.get(key);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw new IOException("Missing or non-numeric preset field: " + key);
        }
        try {
            double value = element.getAsDouble();
            if (!Double.isFinite(value) || value != Math.rint(value)
                    || value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
                throw new IOException("Invalid integer preset field: " + key);
            }
            return (int) value;
        } catch (RuntimeException exception) {
            throw new IOException("Invalid integer preset field: " + key, exception);
        }
    }

    private static float requiredFiniteFloat(JsonObject object, String key) throws IOException {
        JsonElement element = object.get(key);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw new IOException("Missing or non-numeric preset field: " + key);
        }
        try {
            float value = element.getAsFloat();
            if (!Float.isFinite(value)) {
                throw new IOException("Non-finite preset field: " + key);
            }
            return value;
        } catch (RuntimeException exception) {
            throw new IOException("Invalid numeric preset field: " + key, exception);
        }
    }

    private static float requiredEqGain(JsonObject object, String key) throws IOException {
        float value = requiredFiniteFloat(object, key);
        if (value < SpeakerBlockEntity.MIN_EQ_GAIN_DB
                || value > SpeakerBlockEntity.MAX_EQ_GAIN_DB) {
            throw new IOException("Out-of-range equalizer preset field: " + key);
        }
        return value;
    }

    private static float requiredRange(JsonObject object, String key, float minimum, float maximum)
            throws IOException {
        float value = requiredFiniteFloat(object, key);
        if (value < minimum || value > maximum) {
            throw new IOException("Out-of-range DJ audio preset field: " + key);
        }
        return value;
    }

    private static JsonArray requiredArray(JsonObject object, String key) throws IOException {
        JsonElement element = object.get(key);
        if (element == null || !element.isJsonArray()) {
            throw new IOException("Missing or non-array preset field: " + key);
        }
        return element.getAsJsonArray();
    }

    private static void requireExactFields(JsonObject object, String... fields) throws IOException {
        if (object.size() != fields.length) {
            throw new IOException("Preset contains missing or unsupported fields");
        }
        for (String field : fields) {
            if (!object.has(field)) {
                throw new IOException("Missing preset field: " + field);
            }
        }
    }

    private static DjVisualEffectConfig readStrictEffect(JsonObject object) throws IOException {
        final DjVisualEffectConfig config;
        try {
            config = DjVisualEffectConfig.fromJson(object);
        } catch (RuntimeException exception) {
            throw new IOException("Invalid DJ effect entry", exception);
        }
        JsonObject canonical = config.toJson();
        for (String key : object.keySet()) {
            if (!canonical.has(key)) {
                throw new IOException("DJ effect entry contains unsupported field: " + key);
            }
        }
        for (String key : canonical.keySet()) {
            JsonElement actual = object.get(key);
            JsonElement expected = canonical.get(key);
            // Newly-added settings arrays are optional for backwards-compatible
            // v1 effect presets. When present, they are still compared strictly.
            if (actual == null && (expected.isJsonArray()
                    || key.equals("hideParticles") || key.equals("hideFirstPersonHand") || key.equals("hideHud")
                    || key.equals("hideBlockyMusicHud"))) {
                continue;
            }
            if (!sameStrictValue(actual, expected)) {
                throw new IOException("Invalid DJ effect field: " + key);
            }
        }
        return config;
    }

    private static boolean sameStrictValue(JsonElement actual, JsonElement expected) {
        if (actual == null || expected == null) {
            return false;
        }
        if (expected.isJsonArray()) {
            if (!actual.isJsonArray() || actual.getAsJsonArray().size() != expected.getAsJsonArray().size()) {
                return false;
            }
            for (int index = 0; index < expected.getAsJsonArray().size(); index++) {
                if (!sameStrictValue(actual.getAsJsonArray().get(index), expected.getAsJsonArray().get(index))) {
                    return false;
                }
            }
            return true;
        }
        if (expected.isJsonObject()) {
            if (!actual.isJsonObject() || actual.getAsJsonObject().size() != expected.getAsJsonObject().size()) {
                return false;
            }
            for (String key : expected.getAsJsonObject().keySet()) {
                if (!sameStrictValue(actual.getAsJsonObject().get(key), expected.getAsJsonObject().get(key))) {
                    return false;
                }
            }
            return true;
        }
        if (expected.isJsonNull()) {
            return actual.isJsonNull();
        }
        if (!actual.isJsonPrimitive() || !expected.isJsonPrimitive()) {
            return false;
        }
        var actualPrimitive = actual.getAsJsonPrimitive();
        var expectedPrimitive = expected.getAsJsonPrimitive();
        try {
            if (expectedPrimitive.isBoolean()) {
                return actualPrimitive.isBoolean()
                        && actualPrimitive.getAsBoolean() == expectedPrimitive.getAsBoolean();
            }
            if (expectedPrimitive.isString()) {
                return actualPrimitive.isString()
                        && actualPrimitive.getAsString().equals(expectedPrimitive.getAsString());
            }
            if (!expectedPrimitive.isNumber() || !actualPrimitive.isNumber()) {
                return false;
            }
            double actualNumber = actualPrimitive.getAsDouble();
            double expectedNumber = expectedPrimitive.getAsDouble();
            if (expectedPrimitive.getAsNumber() instanceof Float) {
                // Gson writes the shortest decimal for a float (for example 0.1), not its
                // expanded double value (0.10000000149...). Validate at the model's actual
                // precision so files we just saved remain readable, without relaxing colors
                // or other integer fields or accepting non-finite/out-of-range controls.
                return Double.isFinite(actualNumber) && Double.isFinite(expectedNumber)
                        && Float.compare(actualPrimitive.getAsFloat(), expectedPrimitive.getAsFloat()) == 0;
            }
            return Double.isFinite(actualNumber) && Double.isFinite(expectedNumber)
                    && Double.compare(actualNumber, expectedNumber) == 0;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static float clampEq(float value) {
        if (!Float.isFinite(value)) {
            return SpeakerBlockEntity.DEFAULT_EQ_GAIN_DB;
        }
        return Math.max(SpeakerBlockEntity.MIN_EQ_GAIN_DB,
                Math.min(SpeakerBlockEntity.MAX_EQ_GAIN_DB, value));
    }

    public sealed interface NamedPreset permits EffectSetPreset, EqualizerPreset, AudioPreset {
        String name();
    }

    public record EffectSetPreset(String name, boolean trackFxEnabled, boolean djFxEnabled,
                                  List<DjVisualEffectConfig> configs) implements NamedPreset {
        public EffectSetPreset {
            name = sanitizeDisplayName(name);
            configs = DjVisualEffects.sanitizeConfigs(configs);
        }

        public EffectApplication applyToCurrentModes(boolean currentTrackFxEnabled,
                                                     boolean currentDjFxEnabled) {
            return new EffectApplication(currentTrackFxEnabled, currentDjFxEnabled, configs);
        }
    }

    public record EffectApplication(boolean trackFxEnabled, boolean djFxEnabled,
                                    List<DjVisualEffectConfig> configs) {
        public EffectApplication {
            configs = DjVisualEffects.sanitizeConfigs(configs);
        }
    }

    public record EqualizerPreset(String name, float lowGainDb, float midGainDb,
                                  float highGainDb) implements NamedPreset {
        public EqualizerPreset {
            name = sanitizeDisplayName(name);
            lowGainDb = clampEq(lowGainDb);
            midGainDb = clampEq(midGainDb);
            highGainDb = clampEq(highGainDb);
        }
    }

    public record AudioPreset(String name,
                              float radius, float volume,
                              float lowGainDb, float midGainDb, float highGainDb,
                              boolean dspEnabled,
                              boolean reverbEnabled, float reverbWet, float reverbRoom, float reverbDamping,
                              boolean echoEnabled, float echoWet, float echoTimeMs, float echoFeedback,
                              boolean lowPassEnabled, float lowPassCutoffHz) implements NamedPreset {
        public AudioPreset {
            name = sanitizeDisplayName(name);
            radius = clamp(radius, 2.0F, 64.0F, SpeakerBlockEntity.DEFAULT_RADIUS);
            volume = clamp(volume, 0.0F, 2.0F, SpeakerBlockEntity.DEFAULT_VOLUME);
            lowGainDb = clampEq(lowGainDb);
            midGainDb = clampEq(midGainDb);
            highGainDb = clampEq(highGainDb);
            reverbWet = clamp(reverbWet, 0.0F, 1.0F, 0.30F);
            reverbRoom = clamp(reverbRoom, 0.0F, 1.0F, 0.65F);
            reverbDamping = clamp(reverbDamping, 0.0F, 1.0F, 0.45F);
            echoWet = clamp(echoWet, 0.0F, 0.75F, 0.25F);
            echoTimeMs = clamp(echoTimeMs, 40.0F, 1_000.0F, 280.0F);
            echoFeedback = clamp(echoFeedback, 0.0F, 0.85F, 0.35F);
            lowPassCutoffHz = clamp(lowPassCutoffHz, 200.0F, 20_000.0F, 12_000.0F);
        }
    }

    private static float clamp(float value, float minimum, float maximum, float fallback) {
        float safe = Float.isFinite(value) ? value : fallback;
        return Math.max(minimum, Math.min(maximum, safe));
    }

    @FunctionalInterface
    private interface PresetReader<T extends NamedPreset> {
        T read(Path path) throws IOException;
    }

    private enum Category {
        EFFECT_SET("effects", "effect_set", "effect-"),
        EQUALIZER("equalizer", "equalizer", "equalizer-"),
        DJ_AUDIO("audio", "dj-audio", "audio-");

        private final String directory;
        private final String kind;
        private final String prefix;

        Category(String directory, String kind, String prefix) {
            this.directory = directory;
            this.kind = kind;
            this.prefix = prefix;
        }
    }
}

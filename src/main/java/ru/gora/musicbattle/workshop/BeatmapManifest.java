package ru.gora.musicbattle.workshop;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import ru.gora.musicbattle.workshop.BeatmapPackageException.Reason;

/**
 * The manifest of a workshop beatmap set ({@link WorkshopLayout#MANIFEST_FILE_NAME} inside a
 * {@code .blockymap}). One set = one song with its cover, author and one or more charts (difficulties);
 * the same split as an osu! beatmapset and its beatmaps. Format and field rules: docs/WORKSHOP.md.
 *
 * <p>{@link #parse} reads the format number first, so a manifest from a newer format is refused as
 * {@link Reason#UNSUPPORTED_FORMAT} even when the rest of its shape has changed. Unknown fields are
 * ignored within the same format number, which leaves room for additive changes.
 *
 * <p>Limits mirror what the mod already enforces for published tracks and downloaded assets
 * (TrackPublicationManager, ClientTrackAssets): chart 2 MiB, Ogg Vorbis 128 MiB, cover PNG 8 MiB,
 * title 128, uploader name 32.
 *
 * <p>{@link WorkshopLayout#SIGNATURE_FILE_NAME} is never declared here: it is not a file of the set, and
 * {@link #contentSha256()} covers declared files only, so signing a package does not change its content identity.
 */
public record BeatmapManifest(
        int format,
        long setId,
        int revision,
        String title,
        String artist,
        Mapper mapper,
        Audio audio,
        List<Chart> charts,
        List<FileEntry> files
) {
    public static final int FORMAT_VERSION = 1;
    public static final int MAX_CHARTS = 16;
    public static final int MAX_MANIFEST_BYTES = 64 * 1024;
    public static final long MAX_CHART_BYTES = 2L * 1024 * 1024;
    public static final long MAX_AUDIO_BYTES = 128L * 1024 * 1024;
    public static final long MAX_COVER_BYTES = 8L * 1024 * 1024;
    public static final int MAX_TITLE_LENGTH = 128;
    public static final int MAX_ARTIST_LENGTH = 128;
    public static final int MAX_MAPPER_NAME_LENGTH = 32;
    public static final int MAX_DIFFICULTY_LENGTH = 64;
    /** Two hours, the same ceiling as a chart's tick range (144000 ticks). */
    public static final long MAX_DURATION_MS = 2L * 60 * 60 * 1000;
    static final int MAX_JSON_DEPTH = 32;

    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /** How the song travels: inside the archive, or supplied by the player (docs/WORKSHOP.md). */
    public enum AudioPolicy {
        /** The archive carries the Ogg Vorbis file. */
        INCLUDED("included"),
        /** The archive has no audio; the player supplies a local file that must match {@link Audio#sha256()}. */
        EXTERNAL("external");

        private final String id;

        AudioPolicy(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }

        static Optional<AudioPolicy> byId(String id) {
            for (AudioPolicy policy : values()) {
                if (policy.id.equals(id)) {
                    return Optional.of(policy);
                }
            }
            return Optional.empty();
        }
    }

    /** The author: a qwu.world account id and the name shown at the time of packaging. */
    public record Mapper(long accountId, String name) {
    }

    /** The song. Size, hash and duration are declared even for sets with external audio, so a local file can be checked. */
    public record Audio(AudioPolicy policy, long size, String sha256, long durationMs) {
    }

    /** One difficulty. Its file name and track id are derived: {@link WorkshopLayout#chartFileName}. */
    public record Chart(String key, String difficulty) {
    }

    /** One file of the archive other than the manifest itself: a flat name, its exact size and SHA-256. */
    public record FileEntry(String path, long size, String sha256) {
    }

    public BeatmapManifest {
        charts = List.copyOf(Objects.requireNonNull(charts, "charts"));
        files = List.copyOf(Objects.requireNonNull(files, "files"));
    }

    public String folderName() {
        return WorkshopLayout.setFolderName(setId);
    }

    public Optional<FileEntry> file(String path) {
        return files.stream().filter(entry -> entry.path().equals(path)).findFirst();
    }

    public boolean hasCover() {
        return file(WorkshopLayout.coverFileName(setId)).isPresent();
    }

    /**
     * The identity of the playable content: SHA-256 over every declared file (path, size, hash, sorted by
     * path) plus the audio reference, which matters most for sets with external audio, whose audio is not a file here.
     * Title or revision alone do not change it. Intended as the key a server and its players compare.
     */
    public String contentSha256() {
        StringBuilder text = new StringBuilder("blockymap-content-v1\n");
        files.stream().sorted(Comparator.comparing(FileEntry::path)).forEach(entry -> text
                .append("file ").append(entry.path()).append(' ').append(entry.size()).append(' ').append(entry.sha256()).append('\n'));
        text.append("audio ").append(audio.policy().id()).append(' ').append(audio.size()).append(' ').append(audio.sha256()).append('\n');
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    /** Pretty-printed JSON in a fixed field order. {@code parse(toJson())} returns an equal manifest. */
    public String toJson() {
        JsonObject root = new JsonObject();
        root.addProperty("format", format);
        root.addProperty("setId", setId);
        root.addProperty("revision", revision);
        root.addProperty("title", title);
        root.addProperty("artist", artist);
        JsonObject mapperJson = new JsonObject();
        mapperJson.addProperty("accountId", mapper.accountId());
        mapperJson.addProperty("name", mapper.name());
        root.add("mapper", mapperJson);
        JsonObject audioJson = new JsonObject();
        audioJson.addProperty("policy", audio.policy().id());
        audioJson.addProperty("size", audio.size());
        audioJson.addProperty("sha256", audio.sha256());
        audioJson.addProperty("durationMs", audio.durationMs());
        root.add("audio", audioJson);
        JsonArray chartsJson = new JsonArray();
        for (Chart chart : charts) {
            JsonObject chartJson = new JsonObject();
            chartJson.addProperty("key", chart.key());
            chartJson.addProperty("difficulty", chart.difficulty());
            chartsJson.add(chartJson);
        }
        root.add("charts", chartsJson);
        JsonArray filesJson = new JsonArray();
        for (FileEntry entry : files) {
            JsonObject fileJson = new JsonObject();
            fileJson.addProperty("path", entry.path());
            fileJson.addProperty("size", entry.size());
            fileJson.addProperty("sha256", entry.sha256());
            filesJson.add(fileJson);
        }
        root.add("files", filesJson);
        return GSON.toJson(root);
    }

    /** Parses and {@linkplain #validate() validates}. Never returns an invalid manifest. */
    public static BeatmapManifest parse(String json) throws BeatmapPackageException {
        if (json == null || !nestingWithin(json, MAX_JSON_DEPTH)) {
            throw invalid("nested deeper than " + MAX_JSON_DEPTH + " levels");
        }
        JsonObject root;
        try {
            JsonElement element = JsonParser.parseString(json);
            if (!element.isJsonObject()) {
                throw invalid("root is not an object");
            }
            root = element.getAsJsonObject();
        } catch (RuntimeException exception) {
            throw new BeatmapPackageException(Reason.INVALID_MANIFEST, "not JSON", exception);
        }
        long format = requireLong(root, "format");
        if (format > FORMAT_VERSION) {
            throw new BeatmapPackageException(Reason.UNSUPPORTED_FORMAT, "format " + format + ", this build reads " + FORMAT_VERSION);
        }
        JsonObject mapperJson = requireObject(root, "mapper");
        JsonObject audioJson = requireObject(root, "audio");
        String policyId = requireString(audioJson, "policy");
        AudioPolicy policy = AudioPolicy.byId(policyId)
                .orElseThrow(() -> invalid("unknown audio policy " + BeatmapPackageException.describe(policyId)));
        List<Chart> charts = new ArrayList<>();
        for (JsonObject chartJson : requireObjects(root, "charts", MAX_CHARTS)) {
            charts.add(new Chart(requireString(chartJson, "key"), requireString(chartJson, "difficulty")));
        }
        List<FileEntry> files = new ArrayList<>();
        for (JsonObject fileJson : requireObjects(root, "files", MAX_CHARTS + 2)) {
            files.add(new FileEntry(requireString(fileJson, "path"), requireLong(fileJson, "size"), requireString(fileJson, "sha256")));
        }
        return new BeatmapManifest(
                toInt(format, "format"),
                requireLong(root, "setId"),
                toInt(requireLong(root, "revision"), "revision"),
                requireString(root, "title"),
                requireString(root, "artist"),
                new Mapper(requireLong(mapperJson, "accountId"), requireString(mapperJson, "name")),
                new Audio(policy, requireLong(audioJson, "size"), requireString(audioJson, "sha256"), requireLong(audioJson, "durationMs")),
                charts,
                files
        ).validate();
    }

    /** Checks every rule of the format. Returns {@code this}, so construction and validation can chain. */
    public BeatmapManifest validate() throws BeatmapPackageException {
        if (format > FORMAT_VERSION) {
            throw new BeatmapPackageException(Reason.UNSUPPORTED_FORMAT, "format " + format + ", this build reads " + FORMAT_VERSION);
        }
        if (format < 1) {
            throw invalid("format must be at least 1");
        }
        if (setId <= 0 || revision < 1) {
            throw invalid("setId and revision must be positive");
        }
        requireText(title, "title", MAX_TITLE_LENGTH, false);
        requireText(artist, "artist", MAX_ARTIST_LENGTH, true);
        if (mapper == null || mapper.accountId() <= 0) {
            throw invalid("mapper account must be positive");
        }
        requireText(mapper.name(), "mapper name", MAX_MAPPER_NAME_LENGTH, false);
        if (audio == null || audio.policy() == null || !validHash(audio.sha256())) {
            throw invalid("audio needs a policy and a lowercase SHA-256");
        }
        if (audio.size() <= 0 || audio.size() > MAX_AUDIO_BYTES) {
            throw new BeatmapPackageException(Reason.FILE_TOO_LARGE, "audio size " + audio.size());
        }
        if (audio.durationMs() <= 0 || audio.durationMs() > MAX_DURATION_MS) {
            throw invalid("audio duration out of range");
        }
        if (charts.isEmpty() || charts.size() > MAX_CHARTS) {
            throw invalid("a set has 1 to " + MAX_CHARTS + " charts");
        }
        Set<String> keys = new HashSet<>();
        for (Chart chart : charts) {
            if (chart == null || chart.key() == null || !WorkshopLayout.CHART_KEY.matcher(chart.key()).matches() || !keys.add(chart.key())) {
                throw invalid("chart keys must be unique [a-z0-9_-]{1," + WorkshopLayout.MAX_CHART_KEY_LENGTH + "}");
            }
            requireText(chart.difficulty(), "difficulty", MAX_DIFFICULTY_LENGTH, false);
        }

        String audioFile = WorkshopLayout.audioFileName(setId);
        String coverFile = WorkshopLayout.coverFileName(setId);
        Set<String> paths = new HashSet<>();
        for (FileEntry entry : files) {
            if (entry == null || entry.path() == null || !paths.add(entry.path())) {
                throw invalid("file paths must be unique");
            }
            String path = entry.path();
            long cap;
            if (path.equals(audioFile)) {
                cap = MAX_AUDIO_BYTES;
            } else if (path.equals(coverFile)) {
                cap = MAX_COVER_BYTES;
            } else if (charts.stream().anyMatch(chart -> WorkshopLayout.chartFileName(setId, chart.key()).equals(path))) {
                cap = MAX_CHART_BYTES;
            } else {
                // Only derived names are allowed: no directories, no other extensions, nothing to execute.
                throw invalid("file " + BeatmapPackageException.describe(path) + " is not a chart, the audio or the cover of this set");
            }
            if (entry.size() > cap) {
                throw new BeatmapPackageException(Reason.FILE_TOO_LARGE, path + " declares " + entry.size() + " bytes, the cap is " + cap);
            }
            if (entry.size() <= 0 || !validHash(entry.sha256())) {
                throw invalid(path + " needs a positive size and a lowercase SHA-256");
            }
        }
        for (Chart chart : charts) {
            if (!paths.contains(WorkshopLayout.chartFileName(setId, chart.key()))) {
                throw invalid("chart " + chart.key() + " has no file entry");
            }
        }
        Optional<FileEntry> audioEntry = file(audioFile);
        if (audio.policy() == AudioPolicy.INCLUDED) {
            if (audioEntry.isEmpty() || audioEntry.get().size() != audio.size() || !audioEntry.get().sha256().equals(audio.sha256())) {
                throw invalid("a set with included audio carries its audio file with the declared size and hash");
            }
        } else if (audioEntry.isPresent()) {
            // External audio never travels inside a package served by qwu.world.
            throw invalid("a set with external audio must not carry an audio file");
        }
        return this;
    }

    private static boolean validHash(String value) {
        return value != null && SHA256.matcher(value).matches();
    }

    private static void requireText(String value, String field, int maxLength, boolean blankAllowed) throws BeatmapPackageException {
        if (value == null || value.length() > maxLength || !blankAllowed && value.isBlank()) {
            throw invalid(field + " must be " + (blankAllowed ? "" : "non-blank and ") + "at most " + maxLength + " characters");
        }
        for (int i = 0; i < value.length(); i++) {
            if (Character.isISOControl(value.charAt(i))) {
                throw invalid(field + " contains control characters");
            }
        }
    }

    private static BeatmapPackageException invalid(String detail) {
        return new BeatmapPackageException(Reason.INVALID_MANIFEST, detail);
    }

    private static JsonObject requireObject(JsonObject root, String key) throws BeatmapPackageException {
        JsonElement element = root.get(key);
        if (element == null || !element.isJsonObject()) {
            throw invalid(key + " must be an object");
        }
        return element.getAsJsonObject();
    }

    private static List<JsonObject> requireObjects(JsonObject root, String key, int maxSize) throws BeatmapPackageException {
        JsonElement element = root.get(key);
        if (element == null || !element.isJsonArray()) {
            throw invalid(key + " must be an array");
        }
        JsonArray array = element.getAsJsonArray();
        if (array.size() > maxSize) {
            throw invalid(key + " has more than " + maxSize + " items");
        }
        List<JsonObject> objects = new ArrayList<>(array.size());
        for (JsonElement item : array) {
            if (!item.isJsonObject()) {
                throw invalid(key + " items must be objects");
            }
            objects.add(item.getAsJsonObject());
        }
        return objects;
    }

    private static String requireString(JsonObject root, String key) throws BeatmapPackageException {
        JsonElement element = root.get(key);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw invalid(key + " must be a string");
        }
        return element.getAsString();
    }

    /** A whole JSON number that fits a long; {@code 1.5}, {@code "1"} and overflow are refused rather than rounded. */
    private static long requireLong(JsonObject root, String key) throws BeatmapPackageException {
        JsonElement element = root.get(key);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw invalid(key + " must be a number");
        }
        try {
            JsonPrimitive primitive = element.getAsJsonPrimitive();
            return primitive.getAsBigDecimal().longValueExact();
        } catch (ArithmeticException | NumberFormatException exception) {
            throw invalid(key + " must be a whole number");
        }
    }

    private static int toInt(long value, String key) throws BeatmapPackageException {
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw invalid(key + " is out of range");
        }
        return (int) value;
    }

    /**
     * Gson's tree parser recurses once per nesting level and has no limit of its own, so a few kilobytes of
     * {@code [[[[} would end in StackOverflowError. Real manifests nest 3 levels and charts about 4.
     */
    static boolean nestingWithin(String json, int maxDepth) {
        int depth = 0;
        boolean inString = false;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (inString) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    inString = false;
                }
            } else if (c == '"') {
                inString = true;
            } else if (c == '{' || c == '[') {
                if (++depth > maxDepth) {
                    return false;
                }
            } else if (c == '}' || c == ']') {
                depth--;
            }
        }
        return true;
    }
}

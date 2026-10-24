package ru.gora.musicbattle.server.workshop;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.properties.Property;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import ru.gora.musicbattle.MusicBattleMod;
import ru.gora.musicbattle.server.MusicBattleServerConfig;
import ru.gora.musicbattle.server.MusicBattleTracks;
import ru.gora.musicbattle.workshop.api.AtomicFiles;

/**
 * Comments stored on this Minecraft server (spec 06 section 9.2, amended by 08 A5.2).
 *
 * <p>One JSON document per map under {@code <world>/blocky_music/comments/<key>.json}, where {@code key} is
 * {@code ws_<setId>} for a Workshop set (all revisions share one thread) or {@code track_<trackId>} for a legacy
 * server or player track. Everything a player sends is checked before it ever becomes a path: the key must match
 * {@link #KEY}, a {@code track_} key must name a track in the server catalogue, the file is resolved as
 * {@code commentsDir.resolve(key + ".json").normalize()} and its parent must be exactly the comments folder.
 * Reads never create a file — only a post does, and then at most
 * {@link MusicBattleServerConfig#serverCommentsNewDocumentsPerDay()} new ones per player per day and
 * {@link MusicBattleServerConfig#serverCommentsMaxDocuments()} per world.
 *
 * <p>Reports, mutes and exports live in a sibling folder {@code comments_meta/} and their names never come from a
 * player name or free text: {@code reports.json}, {@code mutes.json} and
 * {@code exports/<uuid>-<yyyyMMdd-HHmmss>.json}.
 *
 * <p>Threading: every field here belongs to the server thread. A change builds an immutable JSON snapshot carrying
 * the document's version and hands it to one dedicated single-thread executor, which writes
 * {@code <file>.<uuid>.tmp}, fsyncs and moves it with {@code ATOMIC_MOVE}; a snapshot older than the last written
 * version for that file is skipped, so two writes of one document can never land out of order. A document with a
 * pending write is never evicted from the LRU of {@value #CACHE_SIZE}. {@link #shutdown} drains the executor.
 */
public final class ServerComments {
    public static final Pattern KEY = Pattern.compile("ws_[1-9][0-9]{0,18}|track_[a-z0-9_-]{1,128}");
    public static final String COMMENTS_DIRECTORY = "comments";
    public static final String META_DIRECTORY = "comments_meta";
    public static final String REPORTS_FILE = "reports.json";
    public static final String MUTES_FILE = "mutes.json";
    public static final String EXPORTS_DIRECTORY = "exports";
    public static final int FORMAT_VERSION = 1;
    public static final int MAX_TEXT = 500;
    public static final int MAX_PAGE = 20;
    public static final int CACHE_SIZE = 64;
    /** A document with more comments than this refuses new ones rather than growing without bound. */
    public static final int MAX_COMMENTS_PER_DOCUMENT = 5000;
    /**
     * The command permission level at which a source with no player behind it moderates. It is the level the
     * {@code /musicbattle comments} subtree already gates on, so reaching the command and being trusted by it are
     * the same bar for the console.
     */
    public static final int MODERATOR_PERMISSION_LEVEL = 2;
    private static final long MAX_DOCUMENT_BYTES = 4L * 1024 * 1024;
    private static final DateTimeFormatter EXPORT_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    /** Why a post or delete was refused; the client renders {@code music_battle.workshop.error.<code>}. */
    public static final String ERROR_DISABLED = "server_comments_disabled";
    public static final String ERROR_BAD_KEY = "comment_key_invalid";
    public static final String ERROR_UNKNOWN_TRACK = "comment_key_unknown";
    public static final String ERROR_EMPTY = "comment_empty";
    public static final String ERROR_TOO_LONG = "comment_too_long";
    public static final String ERROR_RATE = "comment_rate_limited";
    public static final String ERROR_MUTED = "comment_muted";
    public static final String ERROR_QUOTA = "comment_quota";
    public static final String ERROR_PERMISSION = "comment_no_permission";
    public static final String ERROR_MISSING = "comment_missing";
    public static final String ERROR_NOT_AUTHOR = "comment_not_author";
    public static final String ERROR_FAILED = "comment_failed";

    /** One stored comment. {@code textures}/{@code signature} are the author's signed skin, when the server had one. */
    public record Comment(
            long id,
            UUID authorUuid,
            String authorName,
            String textures,
            String signature,
            boolean onlineMode,
            long createdAt,
            long editedAt,
            String text,
            boolean deleted,
            String deletedBy
    ) {
    }

    /** One page as the client sees it. */
    public record Page(String key, int total, List<Comment> comments, long nextBeforeId, boolean canPost,
                       boolean canModerate, boolean onlineMode, String note) {
    }

    /** A live mute. {@code until} is epoch milliseconds, {@link Long#MAX_VALUE} for "until lifted". */
    public record Mute(UUID uuid, String name, long until, String by) {
    }

    private static final class Document {
        private final String key;
        private long nextId = 1;
        private final List<Comment> comments = new ArrayList<>();
        private long version;
        private int pendingWrites;

        private Document(String key) {
            this.key = key;
        }
    }

    /** Access-ordered so the eldest entry is the least recently used; a pending write pins its document. */
    private static final Map<String, Document> CACHE = new LinkedHashMap<>(CACHE_SIZE, 0.75F, true);
    private static final Map<UUID, long[]> RATE = new HashMap<>();
    private static final Map<UUID, List<Long>> HOURLY = new HashMap<>();
    private static final Map<UUID, List<Long>> NEW_DOCUMENTS = new HashMap<>();
    private static final Map<UUID, Mute> MUTES = new LinkedHashMap<>();
    private static final Map<Path, Long> WRITTEN_VERSION = new HashMap<>();

    /**
     * Every server-config value this class reads, in one place. The {@code server-comments} check overrides it so
     * the rules can be exercised without a loaded config spec; production always reads {@link MusicBattleServerConfig}.
     */
    record Limits(boolean enabled, int retentionDays, int minPermissionLevel, int rateSeconds, int perHour,
                  int newDocumentsPerDay, int maxDocuments) {
        static Limits fromConfig() {
            return new Limits(MusicBattleServerConfig.serverCommentsEnabled(),
                    MusicBattleServerConfig.serverCommentsRetentionDays(),
                    MusicBattleServerConfig.serverCommentsMinPermissionLevel(),
                    MusicBattleServerConfig.serverCommentsRateLimitSeconds(),
                    MusicBattleServerConfig.serverCommentsRateLimitPerHour(),
                    MusicBattleServerConfig.serverCommentsNewDocumentsPerDay(),
                    MusicBattleServerConfig.serverCommentsMaxDocuments());
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

    private static volatile MinecraftServer boundServer;
    private static volatile Path commentsDirectory;
    private static volatile Path metaDirectory;
    private static ExecutorService writer;
    private static int documentCount;

    private ServerComments() {
    }

    // ---------------------------------------------------------------- lifecycle

    public static synchronized void bind(MinecraftServer server) {
        boundServer = server;
        CACHE.clear();
        RATE.clear();
        HOURLY.clear();
        NEW_DOCUMENTS.clear();
        MUTES.clear();
        WRITTEN_VERSION.clear();
        Path storage = MusicBattleTracks.worldStorageDirectory();
        commentsDirectory = storage == null ? null : storage.resolve(COMMENTS_DIRECTORY).toAbsolutePath().normalize();
        metaDirectory = storage == null ? null : storage.resolve(META_DIRECTORY).toAbsolutePath().normalize();
        if (commentsDirectory == null) {
            return;
        }
        try {
            Files.createDirectories(commentsDirectory);
            Files.createDirectories(metaDirectory);
        } catch (IOException exception) {
            MusicBattleMod.LOGGER.warn("Unable to create the Blocky Music server comment folders.", exception);
        }
        documentCount = countDocuments();
        readMutes();
        applyRetention();
        if (limits().enabled()) {
            MusicBattleMod.LOGGER.info(
                    "Blocky Music server comments are on ({} maps, retention {}). You are responsible for them; "
                            + "/musicbattle comments disable turns them off.",
                    documentCount,
                    limits().retentionDays() == 0
                            ? "unlimited" : limits().retentionDays() + " days");
        }
    }

    public static synchronized void shutdown() {
        ExecutorService pending = writer;
        writer = null;
        boundServer = null;
        if (pending != null) {
            pending.shutdown();
            try {
                if (!pending.awaitTermination(10, TimeUnit.SECONDS)) {
                    MusicBattleMod.LOGGER.warn("Blocky Music server comments were still writing at shutdown.");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        CACHE.clear();
        commentsDirectory = null;
        metaDirectory = null;
    }

    public static boolean available() {
        return commentsDirectory != null && limits().enabled();
    }

    public static boolean onlineMode() {
        MinecraftServer server = boundServer;
        return server != null && server.usesAuthentication();
    }

    // ---------------------------------------------------------------- keys and paths

    /** The full key rule of 08 A5.2: grammar first, then existence for {@code track_} keys. */
    public static Optional<String> keyError(String key) {
        if (key == null || !KEY.matcher(key).matches()) {
            return Optional.of(ERROR_BAD_KEY);
        }
        if (key.startsWith("track_")) {
            String trackId = key.substring("track_".length());
            if (MusicBattleTracks.availableTracks().stream().noneMatch(track -> track.id().equals(trackId))) {
                return Optional.of(ERROR_UNKNOWN_TRACK);
            }
        }
        return Optional.empty();
    }

    /** The document file, or empty when the key does not resolve to a direct child of the comments folder. */
    static Optional<Path> fileOf(String key) {
        Path directory = commentsDirectory;
        if (directory == null || key == null || !KEY.matcher(key).matches()) {
            return Optional.empty();
        }
        Path file = directory.resolve(key + ".json").normalize();
        return file.getParent() != null && file.getParent().equals(directory) ? Optional.of(file) : Optional.empty();
    }

    // ---------------------------------------------------------------- reads

    /** One page, newest first. Never creates a file. {@code beforeId} of 0 means "from the newest". */
    public static synchronized Page page(ServerPlayer viewer, String key, long beforeId, int limit) {
        return page(viewer == null ? null : viewer.getUUID(),
                viewer != null && WorkshopPermissions.canModerateComments(viewer),
                viewer == null ? Optional.of(ERROR_PERMISSION) : canPost(viewer), key, beforeId, limit);
    }

    /** The player-free core, so the rules can be checked without a running server. */
    static synchronized Page page(UUID viewer, boolean canModerate, Optional<String> postRefusal, String key,
                                  long beforeId, int limit) {
        int size = Math.max(1, Math.min(MAX_PAGE, limit));
        if (!available()) {
            return new Page(key, 0, List.of(), 0, false, canModerate, onlineMode(), ERROR_DISABLED);
        }
        Optional<String> error = keyError(key);
        if (error.isPresent()) {
            return new Page(key, 0, List.of(), 0, false, canModerate, onlineMode(), error.get());
        }
        Document document = load(key, false);
        List<Comment> visible = document == null ? List.of() : document.comments;
        List<Comment> page = new ArrayList<>(size);
        long next = 0;
        for (int i = visible.size() - 1; i >= 0; i--) {
            Comment comment = visible.get(i);
            if (beforeId > 0 && comment.id() >= beforeId) {
                continue;
            }
            if (page.size() >= size) {
                next = comment.id() + 1;
                break;
            }
            page.add(comment);
        }
        return new Page(key, visible.size(), List.copyOf(page), next, postRefusal.isEmpty(), canModerate,
                onlineMode(), "");
    }

    /** Why this player may not post right now, or empty. Checked before the composer is even enabled. */
    public static synchronized Optional<String> canPost(ServerPlayer player) {
        if (!available()) {
            return Optional.of(ERROR_DISABLED);
        }
        if (player == null) {
            return Optional.of(ERROR_PERMISSION);
        }
        int level = limits().minPermissionLevel();
        if (level > 0 && !player.hasPermissions(level)) {
            return Optional.of(ERROR_PERMISSION);
        }
        Mute mute = MUTES.get(player.getUUID());
        if (mute != null && mute.until() > System.currentTimeMillis()) {
            return Optional.of(ERROR_MUTED);
        }
        return Optional.empty();
    }

    // ---------------------------------------------------------------- writes

    /** Posts one comment. Returns the error code, or empty on success. Server thread. */
    public static synchronized Optional<String> post(ServerPlayer player, String key, String rawText) {
        Optional<String> refusal = canPost(player);
        if (refusal.isPresent()) {
            return refusal;
        }
        Property textures = player.getGameProfile().getProperties().get("textures").stream().findFirst().orElse(null);
        return post(player.getUUID(), player.getGameProfile().getName(),
                textures == null ? "" : textures.value(),
                textures == null || textures.signature() == null ? "" : textures.signature(), key, rawText);
    }

    /** The player-free core of a post: everything but the permission and mute checks the caller already ran. */
    static synchronized Optional<String> post(UUID uuid, String name, String textures, String signature,
                                              String key, String rawText) {
        if (!available()) {
            return Optional.of(ERROR_DISABLED);
        }
        Optional<String> keyError = keyError(key);
        if (keyError.isPresent()) {
            return keyError;
        }
        String text = sanitize(rawText);
        if (text.isEmpty()) {
            return Optional.of(ERROR_EMPTY);
        }
        if (text.length() > MAX_TEXT) {
            return Optional.of(ERROR_TOO_LONG);
        }
        long now = System.currentTimeMillis();
        long[] last = RATE.get(uuid);
        long waitMs = limits().rateSeconds() * 1000L;
        if (last != null && now - last[0] < waitMs) {
            return Optional.of(ERROR_RATE);
        }
        List<Long> hour = HOURLY.computeIfAbsent(uuid, ignored -> new ArrayList<>());
        hour.removeIf(stamp -> now - stamp > 60L * 60 * 1000);
        if (hour.size() >= limits().perHour()) {
            return Optional.of(ERROR_RATE);
        }

        boolean exists = fileOf(key).filter(file -> Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)).isPresent()
                || CACHE.containsKey(key);
        if (!exists) {
            if (documentCount >= limits().maxDocuments()) {
                return Optional.of(ERROR_QUOTA);
            }
            List<Long> created = NEW_DOCUMENTS.computeIfAbsent(uuid, ignored -> new ArrayList<>());
            created.removeIf(stamp -> now - stamp > 24L * 60 * 60 * 1000);
            if (created.size() >= limits().newDocumentsPerDay()) {
                return Optional.of(ERROR_QUOTA);
            }
            created.add(now);
        }

        Document document = load(key, true);
        if (document == null) {
            return Optional.of(ERROR_FAILED);
        }
        if (document.comments.size() >= MAX_COMMENTS_PER_DOCUMENT) {
            return Optional.of(ERROR_QUOTA);
        }
        document.comments.add(new Comment(document.nextId++, uuid, name, textures == null ? "" : textures,
                signature == null ? "" : signature, onlineMode(), now, 0L, text, false, ""));
        if (!exists) {
            documentCount++;
        }
        RATE.put(uuid, new long[]{now});
        hour.add(now);
        persist(key, document);
        return Optional.empty();
    }

    /** Deletes one comment: its author always, anyone with the moderation right as well. */
    public static synchronized Optional<String> delete(ServerPlayer player, String key, long commentId) {
        return delete(player == null ? null : player.getUUID(),
                player == null ? "server" : player.getGameProfile().getName(),
                player != null && WorkshopPermissions.canModerateComments(player), key, commentId);
    }

    /**
     * Deletes one comment on behalf of a command source, so a thread can also be moderated from the server
     * console. The console, RCON, a command block and a function have no player behind them, so there is no
     * author to match and no PermissionAPI node to ask: the command permission level is the only authority such a
     * source carries, and {@link #MODERATOR_PERMISSION_LEVEL} is the bar the command tree set before dispatching
     * here. A source that does have a player keeps the player rule above, so a moderation node a permissions mod
     * denied still refuses even for an operator.
     */
    public static synchronized Optional<String> delete(CommandSourceStack source, String key, long commentId) {
        if (source == null) {
            return Optional.of(ERROR_PERMISSION);
        }
        ServerPlayer player = source.getPlayer();
        if (player != null) {
            return delete(player, key, commentId);
        }
        // The name is what the operators read back in deletedBy, and it matches what purge and mute already
        // record for the same source.
        return delete(null, source.getTextName(), source.hasPermission(MODERATOR_PERMISSION_LEVEL), key, commentId);
    }

    /** The player-free core of a delete. */
    static synchronized Optional<String> delete(UUID uuid, String name, boolean moderator, String key, long commentId) {
        if (!available()) {
            return Optional.of(ERROR_DISABLED);
        }
        Optional<String> keyError = keyError(key);
        if (keyError.isPresent()) {
            return keyError;
        }
        Document document = load(key, false);
        if (document == null) {
            return Optional.of(ERROR_MISSING);
        }
        for (int i = 0; i < document.comments.size(); i++) {
            Comment comment = document.comments.get(i);
            if (comment.id() != commentId || comment.deleted()) {
                continue;
            }
            boolean author = uuid != null && comment.authorUuid().equals(uuid);
            if (!author && !moderator) {
                return Optional.of(ERROR_NOT_AUTHOR);
            }
            document.comments.set(i, new Comment(comment.id(), comment.authorUuid(), comment.authorName(), "", "",
                    comment.onlineMode(), comment.createdAt(), System.currentTimeMillis(), "", true,
                    name == null ? "server" : name));
            persist(key, document);
            return Optional.empty();
        }
        return Optional.of(ERROR_MISSING);
    }

    /** Deletes every comment by one player across the cached and stored documents (OP command). */
    public static synchronized int purge(UUID uuid, String by) {
        int removed = 0;
        for (String key : documentKeys()) {
            Document document = load(key, false);
            if (document == null) {
                continue;
            }
            boolean changed = false;
            for (int i = 0; i < document.comments.size(); i++) {
                Comment comment = document.comments.get(i);
                if (!comment.authorUuid().equals(uuid) || comment.deleted()) {
                    continue;
                }
                document.comments.set(i, new Comment(comment.id(), comment.authorUuid(), comment.authorName(), "", "",
                        comment.onlineMode(), comment.createdAt(), System.currentTimeMillis(), "", true, by));
                changed = true;
                removed++;
            }
            if (changed) {
                persist(key, document);
            }
        }
        return removed;
    }

    /** A player's report about a comment: appended to {@code comments_meta/reports.json} for the operators. */
    public static synchronized boolean report(ServerPlayer reporter, String key, long commentId, String reason) {
        return reporter != null && report(reporter.getUUID(), reporter.getGameProfile().getName(), key, commentId, reason);
    }

    /** The player-free core of a report. */
    static synchronized boolean report(UUID uuid, String name, String key, long commentId, String reason) {
        if (!available() || uuid == null || keyError(key).isPresent()) {
            return false;
        }
        Path meta = metaDirectory;
        if (meta == null) {
            return false;
        }
        JsonArray reports = readArray(meta.resolve(REPORTS_FILE));
        JsonObject entry = new JsonObject();
        entry.addProperty("key", key);
        entry.addProperty("commentId", commentId);
        entry.addProperty("reporterUuid", uuid.toString());
        entry.addProperty("reporterName", name);
        entry.addProperty("reason", sanitize(reason).substring(0, Math.min(200, sanitize(reason).length())));
        entry.addProperty("at", Instant.now().toString());
        reports.add(entry);
        JsonObject root = new JsonObject();
        root.addProperty("format", FORMAT_VERSION);
        root.add("reports", reports);
        writeMeta(meta.resolve(REPORTS_FILE), root);
        return true;
    }

    public static synchronized void mute(UUID uuid, String name, long untilMillis, String by) {
        MUTES.put(uuid, new Mute(uuid, name == null ? "" : name, untilMillis, by == null ? "" : by));
        writeMutes();
    }

    public static synchronized boolean unmute(UUID uuid) {
        boolean removed = MUTES.remove(uuid) != null;
        if (removed) {
            writeMutes();
        }
        return removed;
    }

    public static synchronized List<Mute> mutes() {
        long now = System.currentTimeMillis();
        return MUTES.values().stream().filter(mute -> mute.until() > now).toList();
    }

    /**
     * Writes every comment by one player to {@code comments_meta/exports/<uuid>-<stamp>.json} for a data request.
     * The name comes from the UUID and the clock, never from anything the player typed.
     */
    public static synchronized Optional<Path> export(UUID uuid) {
        Path meta = metaDirectory;
        if (meta == null) {
            return Optional.empty();
        }
        JsonArray items = new JsonArray();
        for (String key : documentKeys()) {
            Document document = load(key, false);
            if (document == null) {
                continue;
            }
            for (Comment comment : document.comments) {
                if (!comment.authorUuid().equals(uuid)) {
                    continue;
                }
                JsonObject object = new JsonObject();
                object.addProperty("key", key);
                object.addProperty("id", comment.id());
                object.addProperty("authorName", comment.authorName());
                object.addProperty("createdAt", comment.createdAt());
                object.addProperty("editedAt", comment.editedAt());
                object.addProperty("text", comment.text());
                object.addProperty("deleted", comment.deleted());
                items.add(object);
            }
        }
        JsonObject root = new JsonObject();
        root.addProperty("format", FORMAT_VERSION);
        root.addProperty("uuid", uuid.toString());
        root.addProperty("exportedAt", Instant.now().toString());
        root.add("comments", items);
        Path file = meta.resolve(EXPORTS_DIRECTORY)
                .resolve(uuid + "-" + EXPORT_STAMP.format(Instant.now()) + ".json").normalize();
        if (!file.getParent().equals(meta.resolve(EXPORTS_DIRECTORY).normalize())) {
            return Optional.empty();
        }
        try {
            Files.createDirectories(file.getParent());
        } catch (IOException exception) {
            return Optional.empty();
        }
        writeMeta(file, root);
        return Optional.of(file);
    }

    // ---------------------------------------------------------------- text rules

    /**
     * The section sign is removed and every control character becomes a space, never an escape: a comment can never
     * colour the chat or a screen, and a stray newline can never split a row. Runs of whitespace then collapse the
     * way a chat line does.
     */
    public static String sanitize(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(Math.min(value.length(), MAX_TEXT * 2));
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '§') {
                continue;
            }
            // A control character becomes a space instead of vanishing, so a newline can never glue two words
            // together and can never split a row; the collapse below folds the runs that leaves behind.
            out.append(Character.isISOControl(c) ? ' ' : c);
        }
        return out.toString().strip().replaceAll("\\s{2,}", " ");
    }

    // ---------------------------------------------------------------- documents

    private static Document load(String key, boolean create) {
        Document cached = CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        Path file = fileOf(key).orElse(null);
        if (file == null) {
            return null;
        }
        boolean present = Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS);
        if (!present && !create) {
            return null;
        }
        Document document = new Document(key);
        if (present) {
            try {
                if (Files.size(file) <= MAX_DOCUMENT_BYTES) {
                    readInto(document, Files.readString(file, StandardCharsets.UTF_8));
                }
            } catch (IOException | RuntimeException exception) {
                MusicBattleMod.LOGGER.warn("Unable to read Blocky Music server comments '{}'.", key, exception);
            }
        }
        CACHE.put(key, document);
        trimCache();
        return document;
    }

    private static void readInto(Document document, String json) {
        JsonElement element = JsonParser.parseString(json);
        if (!element.isJsonObject()) {
            return;
        }
        JsonObject root = element.getAsJsonObject();
        document.nextId = Math.max(1, root.has("nextId") && root.get("nextId").isJsonPrimitive()
                ? root.get("nextId").getAsLong() : 1);
        if (!root.has("comments") || !root.get("comments").isJsonArray()) {
            return;
        }
        for (JsonElement item : root.getAsJsonArray("comments")) {
            if (!item.isJsonObject()) {
                continue;
            }
            JsonObject object = item.getAsJsonObject();
            UUID uuid;
            try {
                uuid = UUID.fromString(object.get("authorUuid").getAsString());
            } catch (RuntimeException ignored) {
                continue;
            }
            JsonObject textures = object.has("authorTextures") && object.get("authorTextures").isJsonObject()
                    ? object.getAsJsonObject("authorTextures") : null;
            document.comments.add(new Comment(
                    object.has("id") ? object.get("id").getAsLong() : document.nextId++,
                    uuid,
                    string(object, "authorName"),
                    textures == null ? "" : string(textures, "value"),
                    textures == null ? "" : string(textures, "signature"),
                    object.has("onlineMode") && object.get("onlineMode").getAsBoolean(),
                    object.has("createdAt") ? object.get("createdAt").getAsLong() : 0L,
                    object.has("editedAt") ? object.get("editedAt").getAsLong() : 0L,
                    string(object, "text"),
                    object.has("deleted") && object.get("deleted").getAsBoolean(),
                    string(object, "deletedBy")));
        }
        document.comments.sort(Comparator.comparingLong(Comment::id));
        for (Comment comment : document.comments) {
            document.nextId = Math.max(document.nextId, comment.id() + 1);
        }
    }

    private static void persist(String key, Document document) {
        Path file = fileOf(key).orElse(null);
        if (file == null) {
            return;
        }
        JsonObject root = new JsonObject();
        root.addProperty("format", FORMAT_VERSION);
        root.addProperty("key", key);
        root.addProperty("nextId", document.nextId);
        JsonArray comments = new JsonArray();
        for (Comment comment : document.comments) {
            JsonObject object = new JsonObject();
            object.addProperty("id", comment.id());
            object.addProperty("authorUuid", comment.authorUuid().toString());
            object.addProperty("authorName", comment.authorName());
            if (!comment.textures().isEmpty()) {
                JsonObject textures = new JsonObject();
                textures.addProperty("value", comment.textures());
                textures.addProperty("signature", comment.signature());
                object.add("authorTextures", textures);
            }
            object.addProperty("onlineMode", comment.onlineMode());
            object.addProperty("createdAt", comment.createdAt());
            if (comment.editedAt() > 0) {
                object.addProperty("editedAt", comment.editedAt());
            }
            object.addProperty("text", comment.text());
            if (comment.deleted()) {
                object.addProperty("deleted", true);
                object.addProperty("deletedBy", comment.deletedBy());
            }
            comments.add(object);
        }
        root.add("comments", comments);
        byte[] bytes = json(root);
        long version = ++document.version;
        document.pendingWrites++;
        writer().execute(() -> {
            write(file, bytes, version);
            synchronized (ServerComments.class) {
                document.pendingWrites--;
            }
        });
    }

    private static synchronized void write(Path file, byte[] bytes, long version) {
        Long written = WRITTEN_VERSION.get(file);
        if (written != null && written >= version) {
            return;
        }
        try {
            AtomicFiles.write(file, bytes, false);
            WRITTEN_VERSION.put(file, version);
        } catch (IOException exception) {
            MusicBattleMod.LOGGER.warn("Unable to write Blocky Music server comments '{}'.", file.getFileName(), exception);
        }
    }

    /** Evicts the least recently used documents; one with a queued write stays until the write lands. */
    private static void trimCache() {
        Iterator<Map.Entry<String, Document>> iterator = CACHE.entrySet().iterator();
        while (CACHE.size() > CACHE_SIZE && iterator.hasNext()) {
            Map.Entry<String, Document> entry = iterator.next();
            if (entry.getValue().pendingWrites == 0) {
                iterator.remove();
            }
        }
    }

    private static List<String> documentKeys() {
        Path directory = commentsDirectory;
        List<String> keys = new ArrayList<>(CACHE.keySet());
        if (directory == null || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            return keys;
        }
        try (var stream = Files.newDirectoryStream(directory, "*.json")) {
            for (Path file : stream) {
                String name = String.valueOf(file.getFileName());
                String key = name.substring(0, name.length() - ".json".length());
                if (KEY.matcher(key).matches() && !keys.contains(key)) {
                    keys.add(key);
                }
            }
        } catch (IOException exception) {
            MusicBattleMod.LOGGER.warn("Unable to list Blocky Music server comment documents.", exception);
        }
        return keys;
    }

    private static int countDocuments() {
        return documentKeys().size();
    }

    /** Applies {@code serverComments.retentionDays} at bind time; 0 keeps everything. */
    private static void applyRetention() {
        int days = limits().retentionDays();
        if (days <= 0) {
            return;
        }
        long cutoff = System.currentTimeMillis() - days * 24L * 60 * 60 * 1000;
        for (String key : documentKeys()) {
            Document document = load(key, false);
            if (document == null) {
                continue;
            }
            if (document.comments.removeIf(comment -> comment.createdAt() > 0 && comment.createdAt() < cutoff)) {
                persist(key, document);
            }
        }
    }

    // ---------------------------------------------------------------- meta files

    private static void readMutes() {
        Path meta = metaDirectory;
        if (meta == null) {
            return;
        }
        for (JsonElement element : readArray(meta.resolve(MUTES_FILE))) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject object = element.getAsJsonObject();
            try {
                UUID uuid = UUID.fromString(object.get("uuid").getAsString());
                long until = object.has("until") ? object.get("until").getAsLong() : 0L;
                if (until > System.currentTimeMillis()) {
                    MUTES.put(uuid, new Mute(uuid, string(object, "name"), until, string(object, "by")));
                }
            } catch (RuntimeException ignored) {
                // A corrupt row is dropped instead of failing the whole mute list.
            }
        }
    }

    private static void writeMutes() {
        Path meta = metaDirectory;
        if (meta == null) {
            return;
        }
        JsonArray array = new JsonArray();
        for (Mute mute : MUTES.values()) {
            JsonObject object = new JsonObject();
            object.addProperty("uuid", mute.uuid().toString());
            object.addProperty("name", mute.name());
            object.addProperty("until", mute.until());
            object.addProperty("by", mute.by());
            array.add(object);
        }
        JsonObject root = new JsonObject();
        root.addProperty("format", FORMAT_VERSION);
        root.add("mutes", array);
        writeMeta(meta.resolve(MUTES_FILE), root);
    }

    private static JsonArray readArray(Path file) {
        try {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > MAX_DOCUMENT_BYTES) {
                return new JsonArray();
            }
            JsonElement element = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            if (!element.isJsonObject()) {
                return new JsonArray();
            }
            JsonObject root = element.getAsJsonObject();
            for (String field : List.of("reports", "mutes")) {
                if (root.has(field) && root.get(field).isJsonArray()) {
                    return root.getAsJsonArray(field);
                }
            }
        } catch (IOException | RuntimeException ignored) {
            return new JsonArray();
        }
        return new JsonArray();
    }

    private static void writeMeta(Path file, JsonObject root) {
        byte[] bytes = json(root);
        writer().execute(() -> {
            try {
                Files.createDirectories(file.getParent());
                AtomicFiles.write(file, bytes, false);
            } catch (IOException exception) {
                MusicBattleMod.LOGGER.warn("Unable to write '{}'.", file.getFileName(), exception);
            }
        });
    }

    private static byte[] json(JsonObject root) {
        return new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(root)
                .getBytes(StandardCharsets.UTF_8);
    }

    private static synchronized ExecutorService writer() {
        if (writer == null) {
            writer = Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "blocky-music-server-comments");
                thread.setDaemon(true);
                return thread;
            });
        }
        return writer;
    }

    private static String string(JsonObject object, String key) {
        return object.has(key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsString() : "";
    }

    /** Only for the checks: drains the write queue so a file on disk can be inspected. */
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

    /** Only for the checks: the folders without a running server. */
    static void bindForTest(Path comments, Path meta) {
        commentsDirectory = comments == null ? null : comments.toAbsolutePath().normalize();
        metaDirectory = meta == null ? null : meta.toAbsolutePath().normalize();
        CACHE.clear();
        documentCount = commentsDirectory == null ? 0 : countDocuments();
        RATE.clear();
        HOURLY.clear();
        NEW_DOCUMENTS.clear();
        MUTES.clear();
        WRITTEN_VERSION.clear();
    }

    /** Only for the checks: how many documents the cache holds right now. */
    static int cachedDocuments() {
        return CACHE.size();
    }

    /** Only for the checks: the parsed locale-independent name of a key's file. */
    static String fileName(String key) {
        return key == null ? "" : key.toLowerCase(Locale.ROOT) + ".json";
    }
}

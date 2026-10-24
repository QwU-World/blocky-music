package ru.gora.musicbattle.workshop.api;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.LongSupplier;
import ru.gora.musicbattle.workshop.WorkshopCatalog;
import ru.gora.musicbattle.workshop.WorkshopLayout;
import ru.gora.musicbattle.workshop.api.WorkshopDtos.InstalledEntry;
import ru.gora.musicbattle.workshop.api.WorkshopDtos.InstalledResult;

/**
 * Asks the workshop which installed sets have updates or were withdrawn (spec 02 section 2.4, 06 section 8.5) and
 * remembers the last answer per set in {@code music_battle_tracks/.workshop_state.json}. Automatic runs happen at most
 * once per 10 minutes; local files are never deleted because of an answer.
 *
 * <p>The remembered state also decides transfers (spec 08 A5.1): a set may be offered to a server or other players
 * only when its last successful check is at most 7 days old and says {@code current} or {@code update_available}
 * for exactly the installed content.
 */
public final class InstalledCheck {
    public static final long MIN_INTERVAL_MS = 10 * 60_000L;
    public static final long TRANSFER_MAX_AGE_MS = 7L * 24 * 60 * 60_000L;
    /** The answer for a set that is installed at the revision the workshop currently serves. */
    public static final String STATE_CURRENT = "current";
    static final int BATCH = 1000;

    /** One remembered answer. {@code state}: current, update_available, withdrawn, audio_removed, unknown. */
    public record Entry(long setId, int revision, String contentSha256, String state, Integer latestRevision, String availability,
                        String reason, long checkedAtMs) {
        public boolean updateAvailable() {
            return "update_available".equals(state);
        }

        public boolean withdrawn() {
            return "withdrawn".equals(state) || "audio_removed".equals(state);
        }
    }

    public enum TransferVerdict {
        ALLOWED,
        WITHDRAWN,
        AUDIO_REMOVED,
        /** Never checked, content changed since, or the check is older than 7 days: "Check this map online first". */
        CHECK_FIRST
    }

    private final WorkshopApi api;
    private final WorkshopCatalog catalog;
    private final Path stateFile;
    private final LongSupplier clock;
    private volatile Map<Long, Entry> entries = Map.of();
    private long lastAttemptMs = Long.MIN_VALUE;
    private String lastAttemptFingerprint = "";
    private volatile boolean loaded;

    public InstalledCheck(WorkshopApi api, WorkshopCatalog catalog, LongSupplier clock) {
        this.api = api;
        this.catalog = catalog;
        this.stateFile = catalog.tracksRoot().resolve(WorkshopLayout.STATE_FILE_NAME);
        this.clock = clock;
    }

    public Path stateFile() {
        return stateFile;
    }

    /**
     * Runs when the last automatic attempt is older than 10 minutes, or as soon as the installed sets are no longer
     * the ones that attempt covered. Returns true when a request was made.
     *
     * <p>The second half is what makes "open the Workshop once and they can be played here" true. A map that
     * arrived from a file, a friend or the inbox has no remembered answer at all, so {@link #transferVerdict} says
     * {@code CHECK_FIRST} and a battle leaves it out; while the gate only counted minutes, opening the Workshop
     * right after installing it changed nothing and the player had no way to fix that. An attempt that fails is not
     * counted either, for the same reason: it left every one of those maps unanswered.
     */
    public boolean maybeRun(CancelToken cancel) throws WorkshopApiException {
        synchronized (this) {
            long now = clock.getAsLong();
            String fingerprint = installedFingerprint();
            if (lastAttemptMs != Long.MIN_VALUE && now - lastAttemptMs < MIN_INTERVAL_MS
                    && fingerprint.equals(lastAttemptFingerprint)) {
                return false;
            }
            lastAttemptMs = now;
            lastAttemptFingerprint = fingerprint;
        }
        try {
            runNow(cancel);
        } catch (WorkshopApiException failure) {
            synchronized (this) {
                // An attempt that failed covers nothing, so the gate above must not count it: the player is told to
                // open the Workshop and have their maps checked, and the next open has to really try again. The
                // attempt is only recorded first so two threads cannot ask at once.
                lastAttemptMs = Long.MIN_VALUE;
            }
            throw failure;
        }
        return true;
    }

    /** Which sets, with which content, an attempt covered; a change here is worth one request of its own. */
    private String installedFingerprint() {
        StringBuilder fingerprint = new StringBuilder();
        for (WorkshopCatalog.InstalledSet set : catalog.all()) {
            fingerprint.append(set.setId()).append(':').append(set.contentSha256()).append('\n');
        }
        return fingerprint.toString();
    }

    /** Checks every installed set now (the "check first" path before a transfer). Blocking. */
    public synchronized Map<Long, Entry> runNow(CancelToken cancel) throws WorkshopApiException {
        ensureLoaded();
        List<WorkshopCatalog.InstalledSet> installed = catalog.all();
        Map<Long, Entry> next = new HashMap<>(entries);
        next.keySet().retainAll(installed.stream().map(WorkshopCatalog.InstalledSet::setId).toList());
        for (int start = 0; start < installed.size(); start += BATCH) {
            List<WorkshopCatalog.InstalledSet> batch = installed.subList(start, Math.min(installed.size(), start + BATCH));
            List<InstalledEntry> request = new ArrayList<>();
            for (WorkshopCatalog.InstalledSet set : batch) {
                request.add(new InstalledEntry(set.setId(), set.revision(), set.contentSha256()));
            }
            List<InstalledResult> results = api.installedCheck(request, cancel);
            long now = clock.getAsLong();
            for (InstalledResult result : results) {
                WorkshopCatalog.InstalledSet set = batch.stream().filter(s -> s.setId() == result.setId()).findFirst().orElse(null);
                if (set == null || result.state() == null) {
                    continue;
                }
                next.put(set.setId(), new Entry(set.setId(), set.revision(), set.contentSha256(), result.state(),
                        result.latestRevision(), result.availability(), result.reason(), now));
            }
        }
        entries = Collections.unmodifiableMap(next);
        lastAttemptFingerprint = installedFingerprint();
        save();
        return entries;
    }

    /**
     * Remembers that the workshop served exactly this content for this set just now, as a download does.
     *
     * <p>A download ticket says everything an installed-check answer says, and says it seconds ago: the workshop
     * handed over this very revision and {@code contentSha256}. Recording it is what makes a map playable the
     * moment it finishes installing - without it a fresh download had no answer, counted as {@code CHECK_FIRST},
     * and could not be chosen in a battle until some later check happened to run.
     */
    public void rememberCurrent(long setId, int revision, String contentSha256) {
        if (setId <= 0 || contentSha256 == null || contentSha256.isEmpty()) {
            return;
        }
        synchronized (this) {
            ensureLoaded();
            Map<Long, Entry> next = new HashMap<>(entries);
            next.put(setId, new Entry(setId, revision, contentSha256, STATE_CURRENT, revision, null, null, clock.getAsLong()));
            entries = Collections.unmodifiableMap(next);
            save();
        }
    }

    public Optional<Entry> entry(long setId) {
        ensureLoaded();
        return Optional.ofNullable(entries.get(setId));
    }

    public Map<Long, Entry> entries() {
        ensureLoaded();
        return entries;
    }

    /** Spec 08 A5.1. */
    public TransferVerdict transferVerdict(long setId, String contentSha256) {
        Entry entry = entry(setId).orElse(null);
        if (entry == null || !entry.contentSha256().equals(contentSha256)) {
            return TransferVerdict.CHECK_FIRST;
        }
        if ("withdrawn".equals(entry.state())) {
            return TransferVerdict.WITHDRAWN;
        }
        if ("audio_removed".equals(entry.state())) {
            return TransferVerdict.AUDIO_REMOVED;
        }
        if (clock.getAsLong() - entry.checkedAtMs() > TRANSFER_MAX_AGE_MS) {
            return TransferVerdict.CHECK_FIRST;
        }
        return STATE_CURRENT.equals(entry.state()) || "update_available".equals(entry.state())
                ? TransferVerdict.ALLOWED : TransferVerdict.CHECK_FIRST;
    }

    private void ensureLoaded() {
        if (loaded) {
            return;
        }
        synchronized (this) {
            if (loaded) {
                return;
            }
            Map<Long, Entry> read = new HashMap<>();
            try {
                if (Files.isRegularFile(stateFile, LinkOption.NOFOLLOW_LINKS) && Files.size(stateFile) <= 8L * 1024 * 1024) {
                    JsonElement element = JsonParser.parseString(Files.readString(stateFile, StandardCharsets.UTF_8));
                    JsonObject sets = element.getAsJsonObject().getAsJsonObject("sets");
                    for (Map.Entry<String, JsonElement> item : sets.entrySet()) {
                        try {
                            Entry entry = WorkshopDtos.GSON.fromJson(item.getValue(), Entry.class);
                            if (entry != null && entry.setId() > 0 && entry.contentSha256() != null && entry.state() != null) {
                                read.put(entry.setId(), entry);
                            }
                        } catch (RuntimeException ignored) {
                            // Skip one unreadable entry, keep the rest.
                        }
                    }
                }
            } catch (IOException | RuntimeException ignored) {
                read.clear();
            }
            entries = Collections.unmodifiableMap(read);
            loaded = true;
        }
    }

    private void save() {
        JsonObject root = new JsonObject();
        root.addProperty("format", 1);
        JsonObject sets = new JsonObject();
        for (Entry entry : entries.values()) {
            sets.add(String.valueOf(entry.setId()), WorkshopDtos.GSON.toJsonTree(entry));
        }
        root.add("sets", sets);
        try {
            AtomicFiles.write(stateFile, new GsonBuilder().setPrettyPrinting().create().toJson(root).getBytes(StandardCharsets.UTF_8), false);
        } catch (IOException ignored) {
            // The answer stays in memory for this session.
        }
    }
}

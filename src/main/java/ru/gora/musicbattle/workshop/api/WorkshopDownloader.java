package ru.gora.musicbattle.workshop.api;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;
import ru.gora.musicbattle.workshop.BeatmapManifest;
import ru.gora.musicbattle.workshop.BeatmapPackage;
import ru.gora.musicbattle.workshop.BeatmapPackageException;
import ru.gora.musicbattle.workshop.WorkshopCatalog;
import ru.gora.musicbattle.workshop.WorkshopLayout;
import ru.gora.musicbattle.workshop.api.WorkshopDtos.DownloadTicket;

/**
 * Downloads and installs workshop sets (spec 06 section 8.1, 08 A5.5).
 *
 * <ul>
 *   <li>One global queue, at most {@value #MAX_CONCURRENT} downloads at a time, one job per set.</li>
 *   <li>{@code POST /download} → stream the signed same-origin URL into
 *       {@code .workshop_downloads/<setId>-<revision>.part} with a cap of {@code size + 1 MiB} (and the package ceiling),
 *       SHA-256 while streaming, compared with {@code packageSha256}; the size must match exactly. A drive that
 *       cannot hold the package twice over, once as the archive and once unpacked, is refused before the first byte
 *       with {@link WorkshopApi#DISK_FULL} instead of a code about the network.</li>
 *   <li>The manifest inside must name the same set, revision and {@code contentSha256} as the ticket; then
 *       {@link BeatmapPackage#install} runs on the install executor (the game passes {@code Util.nonCriticalIoPool()}).</li>
 *   <li>After install the set counts as installed at once: the catalogue is rescanned, the fresh workshop answer is
 *       remembered and INSTALLED is published before the optional extras. Then {@code ws_<setId>/workshop-card.json}
 *       (the set detail + {@code fetchedAt}) and {@code ws_<setId>/preview.ogg} are fetched; the package is kept in
 *       {@code .workshop_packages/<setId>-<revision>.blockymap} for {@value #KEEP_PACKAGES_DAYS} days (older
 *       revisions of the set are removed at once).</li>
 *   <li>429 → FAILED({@code rate_limited}, Retry-After); 410 → UNAVAILABLE; cancel deletes the {@code .part}.</li>
 * </ul>
 * The {@link WorkshopCatalog} (installed manifests) is the source of truth for INSTALLED; job states only cover sets in
 * flight or the last failure. Listener callbacks run on worker threads.
 */
public final class WorkshopDownloader {
    public static final int MAX_CONCURRENT = 2;
    public static final long SIZE_SLACK_BYTES = 1024L * 1024;
    public static final int KEEP_PACKAGES_DAYS = 7;
    public static final long MAX_PREVIEW_BYTES = 8L * 1024 * 1024;
    private static final Pattern PACKAGE_NAME = Pattern.compile("([1-9][0-9]{0,18})-([1-9][0-9]{0,9})\\.blockymap");

    public enum State {
        NOT_INSTALLED, QUEUED, DOWNLOADING, INSTALLING, INSTALLED, UPDATE_AVAILABLE, UNAVAILABLE, FAILED
    }

    /** A set's state for its Install button. {@code failureCode} maps to {@code music_battle.workshop.error.<code>}. */
    public record Status(State state, long receivedBytes, long totalBytes, String failureCode, long retryAfterSeconds) {
        public static Status of(State state) {
            return new Status(state, 0, 0, null, -1);
        }

        /** 0..1 while downloading. */
        public float progress() {
            return totalBytes <= 0 ? 0F : (float) Math.min(1.0, (double) receivedBytes / totalBytes);
        }
    }

    public interface Listener {
        void statusChanged(long setId, Status status);

        default void installed(long setId, BeatmapManifest manifest, BeatmapPackage.Outcome outcome) {
        }

        default void failed(long setId, String code, long retryAfterSeconds) {
        }

        /**
         * The player cancelled this download, from the queue or mid-stream. A cancellation is not a failure - no
         * toast, no last outcome - but it is still the end of the job, and whoever asked for the download has to
         * hear about it: something waiting for {@link #installed} or {@link #failed} would wait forever.
         */
        default void cancelled(long setId) {
        }
    }

    private final class Job {
        final long setId;
        final CancelToken cancel = new CancelToken();
        volatile Status status = Status.of(State.QUEUED);
        volatile Path partFile;

        Job(long setId) {
            this.setId = setId;
        }
    }

    private final WorkshopApi api;
    private final WorkshopCatalog catalog;
    private final InstalledCheck installedCheck;
    private final Executor networkExecutor;
    private final Executor installExecutor;
    private final LongSupplier clock;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Deque<Job> queue = new ArrayDeque<>();
    private final Map<Long, Job> jobs = new HashMap<>();
    private final Map<Long, Status> lastOutcome = new HashMap<>();
    private int running;

    public WorkshopDownloader(WorkshopApi api, WorkshopCatalog catalog, InstalledCheck installedCheck, Executor networkExecutor,
                              Executor installExecutor, LongSupplier clock) {
        this.api = api;
        this.catalog = catalog;
        this.installedCheck = installedCheck;
        this.networkExecutor = networkExecutor;
        this.installExecutor = installExecutor;
        this.clock = clock;
    }

    public void addListener(Listener listener) {
        listeners.add(listener);
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    public Path downloadsDirectory() {
        return catalog.tracksRoot().resolve(WorkshopLayout.DOWNLOADS_DIRECTORY_NAME);
    }

    public Path packagesDirectory() {
        return catalog.tracksRoot().resolve(WorkshopLayout.PACKAGES_DIRECTORY_NAME);
    }

    /** The kept package of an installed revision, if still there. */
    public Path keptPackage(long setId, int revision) {
        return packagesDirectory().resolve(setId + "-" + revision + WorkshopLayout.PACKAGE_EXTENSION);
    }

    /** The state an Install button shows. */
    public synchronized Status status(long setId) {
        Job job = jobs.get(setId);
        if (job != null) {
            return job.status;
        }
        if (catalog.isInstalled(setId)) {
            if (installedCheck != null && installedCheck.entry(setId).map(InstalledCheck.Entry::updateAvailable).orElse(false)) {
                return Status.of(State.UPDATE_AVAILABLE);
            }
            return Status.of(State.INSTALLED);
        }
        return lastOutcome.getOrDefault(setId, Status.of(State.NOT_INSTALLED));
    }

    public synchronized boolean active(long setId) {
        return jobs.containsKey(setId);
    }

    /** Queues a download; does nothing when the set is already queued or in flight. */
    public void enqueue(long setId) {
        if (setId <= 0) {
            throw new IllegalArgumentException("setId");
        }
        Job job;
        synchronized (this) {
            if (jobs.containsKey(setId)) {
                return;
            }
            job = new Job(setId);
            jobs.put(setId, job);
            lastOutcome.remove(setId);
            queue.addLast(job);
        }
        publish(job, job.status);
        pump();
    }

    /** Cancels a queued or running download; the {@code .part} file is deleted. Installing cannot be cancelled. */
    public void cancel(long setId) {
        Job job;
        boolean wasQueued;
        synchronized (this) {
            job = jobs.get(setId);
            if (job == null || job.status.state() == State.INSTALLING) {
                return;
            }
            wasQueued = queue.remove(job);
            if (wasQueued) {
                jobs.remove(setId);
            }
        }
        job.cancel.cancel();
        if (wasQueued) {
            // A running job reports itself through fail(); one that never started has no worker to do it.
            listeners.forEach(l -> l.statusChanged(setId, status(setId)));
            listeners.forEach(l -> l.cancelled(setId));
        }
    }

    /** Cancels everything (game shutdown). */
    public void cancelAll() {
        List<Long> ids;
        synchronized (this) {
            ids = List.copyOf(jobs.keySet());
        }
        ids.forEach(this::cancel);
    }

    private void pump() {
        while (true) {
            Job next;
            synchronized (this) {
                if (running >= MAX_CONCURRENT || queue.isEmpty()) {
                    return;
                }
                next = queue.pollFirst();
                running++;
            }
            Job job = next;
            try {
                networkExecutor.execute(() -> run(job));
            } catch (RuntimeException rejected) {
                synchronized (this) {
                    running--;
                }
                finish(job, new Status(State.FAILED, 0, 0, WorkshopApiException.NETWORK_ERROR, -1), null);
                return;
            }
        }
    }

    private void run(Job job) {
        DownloadTicket ticket;
        try {
            job.cancel.throwIfCancelled();
            setStatus(job, new Status(State.DOWNLOADING, 0, 0, null, -1));
            ticket = api.requestDownload(job.setId, job.cancel);
            if (ticket.size() > BeatmapPackage.MAX_ARCHIVE_BYTES) {
                throw new WorkshopApiException(WorkshopApiException.TOO_LARGE, "package of " + ticket.size() + " bytes");
            }
            Path downloads = downloadsDirectory();
            AtomicFiles.createDirectories(downloads, false);
            // The ticket says how big the package is, so a download that cannot possibly fit is refused now, with
            // "free some space", instead of filling the drive and failing at ninety per cent with something that
            // reads like a network problem.
            WorkshopApi.requireRoomForPackage(downloads, ticket.size());
            Path part = downloads.resolve(job.setId + "-" + ticket.revision() + ".part");
            job.partFile = part;
            long total = ticket.size();
            long cap = Math.min(total + SIZE_SLACK_BYTES, BeatmapPackage.MAX_ARCHIVE_BYTES + SIZE_SLACK_BYTES);
            long[] lastPublished = {0};
            WorkshopApi.DownloadResult result = api.download(ticket.url(), part, cap, received -> {
                if (received - lastPublished[0] >= 256 * 1024 || received == total) {
                    lastPublished[0] = received;
                    setStatus(job, new Status(State.DOWNLOADING, received, total, null, -1));
                }
            }, job.cancel);
            if (result.bytes() != total) {
                throw new WorkshopApiException(WorkshopApiException.SIZE_MISMATCH, result.bytes() + " bytes, expected " + total);
            }
            if (!result.sha256().equals(ticket.packageSha256())) {
                throw new WorkshopApiException(WorkshopApiException.HASH_MISMATCH, "package hash differs from the ticket");
            }
            job.cancel.throwIfCancelled();
        } catch (WorkshopApiException exception) {
            deletePart(job);
            releaseSlot();
            fail(job, exception);
            return;
        } catch (IOException | RuntimeException exception) {
            deletePart(job);
            releaseSlot();
            fail(job, new WorkshopApiException(WorkshopApiException.NETWORK_ERROR, "download failed", exception));
            return;
        }
        releaseSlot();
        setStatus(job, new Status(State.INSTALLING, ticket.size(), ticket.size(), null, -1));
        DownloadTicket installTicket = ticket;
        CompletableFuture.runAsync(() -> install(job, installTicket), installExecutor).exceptionally(error -> {
            deletePart(job);
            finish(job, new Status(State.FAILED, 0, 0, "install_failed", -1), null);
            return null;
        });
    }

    private void install(Job job, DownloadTicket ticket) {
        Path part = job.partFile;
        BeatmapPackage.InstallResult result;
        try {
            BeatmapManifest manifest = BeatmapPackage.readManifest(part);
            if (manifest.setId() != job.setId || manifest.revision() != ticket.revision()
                    || !manifest.contentSha256().equals(ticket.contentSha256())) {
                throw new WorkshopApiException(WorkshopApiException.MANIFEST_MISMATCH, "package does not match the ticket");
            }
            result = BeatmapPackage.install(part, catalog.tracksRoot());
        } catch (BeatmapPackageException exception) {
            deletePart(job);
            finish(job, new Status(State.FAILED, 0, 0, packageErrorCode(exception.reason()), -1), null);
            listeners.forEach(l -> l.failed(job.setId, packageErrorCode(exception.reason()), -1));
            return;
        } catch (WorkshopApiException exception) {
            deletePart(job);
            fail(job, exception);
            return;
        } catch (IOException | RuntimeException exception) {
            deletePart(job);
            finish(job, new Status(State.FAILED, 0, 0, "install_failed", -1), null);
            listeners.forEach(l -> l.failed(job.setId, "install_failed", -1));
            return;
        }
        // The order matters. Keeping the package is a local move of the file we already have, so it happens while
        // the .part is still ours; everything after it is what the player is waiting for. The card and the preview
        // clip are another two round trips to the site, and fetching them first left a finished map sitting on
        // "Installing..." - and out of the catalogue, so out of a battle - until the network answered.
        keepPackage(job, ticket.revision());
        catalog.refresh();
        if (installedCheck != null) {
            installedCheck.rememberCurrent(job.setId, ticket.revision(), ticket.contentSha256());
        }
        finish(job, Status.of(State.INSTALLED), result);
        if (writeSidecars(job.setId, result.directory())) {
            // The offline card and the preview are read from disk by the catalogue, not kept in memory.
            catalog.refresh();
        }
    }

    /** {@code music_battle.workshop.error.package_<reason>}. */
    public static String packageErrorCode(BeatmapPackageException.Reason reason) {
        return "package_" + reason.name().toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * Card detail and preview clip next to the installed set. Failures leave the set installed without them.
     * Returns true when a file was written, so the caller knows whether the catalogue has something new to read.
     */
    boolean writeSidecars(long setId, Path setDirectory) {
        boolean wrote = false;
        try {
            JsonObject detail = api.setJson(setId, CancelToken.none());
            if (detail == null || !detail.has("id") || detail.get("id").getAsLong() != setId) {
                return false;
            }
            JsonObject card = detail.deepCopy();
            card.remove("schema");
            card.remove("viewer");
            card.remove("can");
            card.remove("cannot");
            card.remove("descriptionMd");
            card.addProperty("fetchedAt", Instant.ofEpochMilli(clock.getAsLong()).toString());
            AtomicFiles.write(setDirectory.resolve(WorkshopLayout.CARD_SIDECAR_FILE_NAME),
                    new GsonBuilder().disableHtmlEscaping().create().toJson(card).getBytes(StandardCharsets.UTF_8), false);
            wrote = true;
            if (card.has("preview") && card.get("preview").isJsonObject()) {
                JsonObject preview = card.getAsJsonObject("preview");
                if (preview.has("ogg") && preview.get("ogg").isJsonPrimitive()) {
                    byte[] ogg = api.fetchBytes(preview.get("ogg").getAsString(), MAX_PREVIEW_BYTES, CancelToken.none());
                    if (ogg.length >= 4 && ogg[0] == 'O' && ogg[1] == 'g' && ogg[2] == 'g' && ogg[3] == 'S') {
                        AtomicFiles.write(setDirectory.resolve(WorkshopLayout.PREVIEW_SIDECAR_FILE_NAME), ogg, false);
                    }
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // Offline card data is optional (sets without it sort last and show no pill).
        }
        return wrote;
    }

    private void keepPackage(Job job, int revision) {
        Path part = job.partFile;
        try {
            Path packages = packagesDirectory();
            AtomicFiles.createDirectories(packages, false);
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(packages)) {
                for (Path file : stream) {
                    var m = PACKAGE_NAME.matcher(String.valueOf(file.getFileName()));
                    if (m.matches() && Long.parseLong(m.group(1)) == job.setId && Integer.parseInt(m.group(2)) != revision) {
                        Files.deleteIfExists(file);
                    }
                }
            }
            AtomicFiles.move(part, keptPackage(job.setId, revision));
        } catch (IOException | RuntimeException exception) {
            deletePart(job);
        }
        prunePackages();
    }

    /** Deletes kept packages older than {@value #KEEP_PACKAGES_DAYS} days and leftover {@code .part} files not in flight. */
    public void prunePackages() {
        long cutoff = clock.getAsLong() - KEEP_PACKAGES_DAYS * 24L * 60 * 60 * 1000;
        try {
            Path packages = packagesDirectory();
            if (Files.isDirectory(packages, LinkOption.NOFOLLOW_LINKS)) {
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(packages)) {
                    for (Path file : stream) {
                        if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                                && PACKAGE_NAME.matcher(String.valueOf(file.getFileName())).matches()
                                && Files.getLastModifiedTime(file).toMillis() < cutoff) {
                            Files.deleteIfExists(file);
                        }
                    }
                }
            }
        } catch (IOException ignored) {
            // Retried next time.
        }
    }

    /** Removes {@code .part} files left by a crash. Call once at startup, before any download. */
    public void cleanStaleParts() {
        Path downloads = downloadsDirectory();
        if (!Files.isDirectory(downloads, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(downloads, "*.part")) {
            for (Path file : stream) {
                boolean inFlight;
                synchronized (this) {
                    inFlight = jobs.values().stream().anyMatch(job -> file.equals(job.partFile));
                }
                if (!inFlight && Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                    Files.deleteIfExists(file);
                }
            }
        } catch (IOException ignored) {
            // Retried next start.
        }
    }

    private void fail(Job job, WorkshopApiException exception) {
        State state;
        String code = exception.code();
        if (code.equals(WorkshopApiException.CANCELLED)) {
            finish(job, null, null);
            listeners.forEach(l -> l.cancelled(job.setId));
            return;
        }
        if (exception.status() == 410 || code.equals(WorkshopApiException.UNAVAILABLE)) {
            state = State.UNAVAILABLE;
        } else {
            state = State.FAILED;
        }
        Status status = new Status(state, 0, 0, code, exception.retryAfterSeconds());
        finish(job, status, null);
        listeners.forEach(l -> l.failed(job.setId, code, exception.retryAfterSeconds()));
    }

    private void finish(Job job, Status outcome, BeatmapPackage.InstallResult result) {
        synchronized (this) {
            jobs.remove(job.setId, job);
            if (outcome != null && (outcome.state() == State.FAILED || outcome.state() == State.UNAVAILABLE)) {
                lastOutcome.put(job.setId, outcome);
            } else {
                lastOutcome.remove(job.setId);
            }
        }
        Status now = status(job.setId);
        listeners.forEach(l -> l.statusChanged(job.setId, now));
        if (result != null) {
            listeners.forEach(l -> l.installed(job.setId, result.manifest(), result.outcome()));
        }
        pump();
    }

    private void releaseSlot() {
        synchronized (this) {
            running = Math.max(0, running - 1);
        }
        pump();
    }

    private void setStatus(Job job, Status status) {
        job.status = status;
        publish(job, status);
    }

    private void publish(Job job, Status status) {
        Objects.requireNonNull(status);
        listeners.forEach(l -> l.statusChanged(job.setId, status));
    }

    private static void deletePart(Job job) {
        Path part = job.partFile;
        if (part != null) {
            try {
                Files.deleteIfExists(part);
            } catch (IOException ignored) {
                // cleanStaleParts removes it next start.
            }
        }
    }
}

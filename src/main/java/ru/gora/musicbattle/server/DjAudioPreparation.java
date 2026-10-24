package ru.gora.musicbattle.server;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import ru.gora.musicbattle.MusicBattleMod;

/**
 * Shared, off-thread preparation of pitch-preserved DJ audio variants.
 *
 * <p>This cache is deliberately DJ-only. Battle playback keeps its existing
 * loading rules, while DJ start/speed/preload paths share one conversion for
 * the same source revision and tempo.</p>
 */
final class DjAudioPreparation {
    private static final long MAX_AUDIO_BYTES = 96L * 1024L * 1024L;
    private static final Object LOCK = new Object();
    private static final Map<AssetKey, Work> WORK = new HashMap<>();
    private static final AtomicInteger THREAD_ID = new AtomicInteger();
    private static ExecutorService executor;
    private static long generation;

    private DjAudioPreparation() {
    }

    static CompletableFuture<PreparedAudio> prepare(MusicBattleTracks.Track track,
                                                     float requestedSpeed) {
        float speed = normalizeSpeed(requestedSpeed);
        AssetKey key;
        try {
            key = key(track, speed);
        } catch (IOException exception) {
            return CompletableFuture.failedFuture(exception);
        }
        synchronized (LOCK) {
            Work existing = WORK.get(key);
            if (existing != null) {
                return existing.result;
            }
            ExecutorService workerPool = executor();
            long workGeneration = generation;
            CompletableFuture<PreparedAudio> result = new CompletableFuture<>();
            Work work = new Work(result);
            WORK.put(key, work);
            work.task = workerPool.submit(() -> {
                try {
                    MusicBattleTracks.AudioAsset asset = MusicBattleTracks.audioAsset(track, speed)
                            .orElseThrow(() -> new IOException("Unable to create DJ speed audio"));
                    PreparedAudio prepared = inspect(asset);
                    synchronized (LOCK) {
                        if (generation != workGeneration || WORK.get(key) != work) {
                            result.cancel(false);
                            return;
                        }
                    }
                    result.complete(prepared);
                } catch (Throwable exception) {
                    synchronized (LOCK) {
                        WORK.remove(key, work);
                    }
                    result.completeExceptionally(exception);
                }
            });
            return result;
        }
    }

    /** Returns only an already-prepared asset; it never launches ffmpeg. */
    static Optional<PreparedAudio> ready(MusicBattleTracks.Track track, float requestedSpeed) {
        float speed = normalizeSpeed(requestedSpeed);
        AssetKey key;
        try {
            key = key(track, speed);
        } catch (IOException exception) {
            return Optional.empty();
        }
        synchronized (LOCK) {
            Work work = WORK.get(key);
            if (work == null || !work.result.isDone() || work.result.isCompletedExceptionally()
                    || work.result.isCancelled()) {
                return Optional.empty();
            }
            return Optional.ofNullable(work.result.getNow(null));
        }
    }

    static void cleanup() {
        ExecutorService oldExecutor;
        synchronized (LOCK) {
            generation++;
            for (Work work : WORK.values()) {
                if (work.task != null) {
                    work.task.cancel(true);
                }
                work.result.cancel(false);
            }
            WORK.clear();
            oldExecutor = executor;
            executor = null;
        }
        if (oldExecutor != null) {
            oldExecutor.shutdownNow();
        }
    }

    private static AssetKey key(MusicBattleTracks.Track track, float speed) throws IOException {
        if (track == null) {
            throw new IOException("Missing DJ track");
        }
        Path source = MusicBattleTracks.audioPath(track)
                .orElseThrow(() -> new IOException("DJ track has no audio file"))
                .toAbsolutePath().normalize();
        if (Files.isSymbolicLink(source)) {
            throw new IOException("Symbolic DJ audio is not supported");
        }
        BasicFileAttributes attributes = Files.readAttributes(
                source, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() || attributes.size() <= 0L) {
            throw new IOException("Invalid DJ audio source");
        }
        return new AssetKey(track.id(), source, attributes.size(),
                attributes.lastModifiedTime().toMillis(), Math.round(speed * 100.0F));
    }

    private static PreparedAudio inspect(MusicBattleTracks.AudioAsset asset) throws IOException {
        Path path = asset.path().toAbsolutePath().normalize();
        if (Files.isSymbolicLink(path)) {
            throw new IOException("Symbolic DJ audio is not supported");
        }
        BasicFileAttributes before = Files.readAttributes(
                path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!before.isRegularFile() || before.size() <= 0L || before.size() > MAX_AUDIO_BYTES) {
            throw new IOException("Invalid prepared DJ audio size");
        }
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IOException("SHA-256 is unavailable", exception);
        }
        byte[] buffer = new byte[64 * 1024];
        long readBytes = 0L;
        try (InputStream input = Files.newInputStream(path)) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                    readBytes += read;
                }
            }
        }
        BasicFileAttributes after = Files.readAttributes(
                path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (readBytes != before.size() || after.size() != before.size()
                || !after.lastModifiedTime().equals(before.lastModifiedTime())) {
            throw new IOException("Prepared DJ audio changed while hashing");
        }
        return new PreparedAudio(asset, before.size(), before.lastModifiedTime().toMillis(),
                HexFormat.of().formatHex(digest.digest()));
    }

    private static ExecutorService executor() {
        if (executor == null || executor.isShutdown()) {
            ThreadFactory factory = runnable -> {
                Thread thread = new Thread(runnable,
                        "BlockyMusic-DJ-Audio-" + THREAD_ID.incrementAndGet());
                thread.setDaemon(true);
                thread.setUncaughtExceptionHandler((ignored, exception) ->
                        MusicBattleMod.LOGGER.error("Uncaught DJ audio preparation failure.", exception));
                return thread;
            };
            executor = Executors.newFixedThreadPool(2, factory);
        }
        return executor;
    }

    private static float normalizeSpeed(float speed) {
        if (!Float.isFinite(speed)) {
            return 1.0F;
        }
        if (Math.abs(speed - 2.0F) < 0.25F) {
            return 2.0F;
        }
        if (Math.abs(speed - 1.5F) < 0.25F) {
            return 1.5F;
        }
        return 1.0F;
    }

    private record AssetKey(String trackId, Path source, long sourceSize,
                            long sourceModified, int speedPercent) {
    }

    static record PreparedAudio(MusicBattleTracks.AudioAsset audio, long size,
                                long modified, String sha256) {
    }

    private static final class Work {
        private final CompletableFuture<PreparedAudio> result;
        private volatile Future<?> task;

        private Work(CompletableFuture<PreparedAudio> result) {
            this.result = result;
        }
    }
}

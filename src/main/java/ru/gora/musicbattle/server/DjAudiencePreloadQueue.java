package ru.gora.musicbattle.server;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.PacketDistributor;
import ru.gora.musicbattle.MusicBattleMod;
import ru.gora.musicbattle.block.entity.DjPanelBlockEntity;
import ru.gora.musicbattle.network.TrackAssetChunkPayload;

/**
 * A low-priority, server-side queue used to warm the audio cache of the
 * spectators that can currently hear a DJ installation.
 *
 * <p>The queue deliberately sends only {@link TrackAssetChunkPayload} packets.
 * It never sends a music/FX session packet, so completing a preload cannot
 * start, stop, seek or otherwise replace the track that a client is hearing.</p>
 *
 * <p>All public methods must be called from the server thread. Conversion is
 * shared with the other DJ paths on dedicated workers; reading and hashing are
 * performed on Minecraft's non-critical I/O pool.</p>
 */
public final class DjAudiencePreloadQueue {
    private static final int ASSET_CHUNK_BYTES = 60 * 1024;
    private static final int CHUNKS_PER_TICK = 4;
    private static final long MAX_AUDIO_BYTES = 96L * 1024L * 1024L;
    private static final long REQUEST_COOLDOWN_TICKS = 40L;
    private static final long RECIPIENT_COOLDOWN_TICKS = 1_200L;
    private static final long STALE_REQUEST_TICKS = 6_000L;

    private static final ArrayDeque<PendingTransfer> TRANSFERS = new ArrayDeque<>();
    private static final Map<UUID, RequestState> REQUESTS = new HashMap<>();
    private static final Map<RequestKey, UUID> ACTIVE_REQUESTS = new HashMap<>();
    private static final Map<RequestKey, Long> REQUEST_AFTER = new HashMap<>();
    private static final Map<RecipientAssetKey, UUID> ACTIVE_RECIPIENTS = new HashMap<>();
    private static final Map<RecipientAssetKey, Long> RECIPIENT_AFTER = new HashMap<>();
    private static final Map<AssetKey, CompletableFuture<LoadedAsset>> SHARED_LOADS = new HashMap<>();

    private static volatile MinecraftServer ownerServer;

    private DjAudiencePreloadQueue() {
    }

    /**
     * Starts a background audio-only transfer for a snapshot of the audible
     * audience. The caller remains responsible for deciding which players are
     * spectators and currently inside the panel/speaker network.
     *
     * @param activeTrackId the panel's currently active track, or an empty
     *                      string when the panel is idle
     */
    public static RequestResult request(ServerLevel level, BlockPos panelPos,
                                        MusicBattleTracks.Track track, float speedMultiplier,
                                        String activeTrackId, Collection<UUID> audiblePlayers) {
        if (level == null || panelPos == null || track == null || audiblePlayers == null) {
            return new RequestResult(Result.INVALID_REQUEST, 0, 0);
        }
        MinecraftServer server = level.getServer();
        bind(server);

        BlockPos immutablePanelPos = panelPos.immutable();
        if (!(level.getBlockEntity(immutablePanelPos) instanceof DjPanelBlockEntity panel)) {
            return new RequestResult(Result.INVALID_REQUEST, 0, 0);
        }

        // The catalogue first, then the charts DJs brought from the Workshop cache, so those preload to listeners too.
        Optional<MusicBattleTracks.Track> canonicalTrack = DjPlaybackManager.trackById(track.id());
        if (canonicalTrack.isEmpty() || !safeTrackId(track.id())) {
            return new RequestResult(Result.INVALID_REQUEST, 0, 0);
        }
        MusicBattleTracks.Track selectedTrack = canonicalTrack.get();
        String panelActiveTrack = panel.getActiveTrackId();
        if (selectedTrack.id().equals(activeTrackId) || selectedTrack.id().equals(panelActiveTrack)) {
            return new RequestResult(Result.ACTIVE_TRACK, audiblePlayers.size(), 0);
        }

        float speed = normalizeSpeed(speedMultiplier);
        AssetKey assetKey = new AssetKey(selectedTrack.id(), speedKey(speed));
        RequestKey requestKey = new RequestKey(level.dimension(), immutablePanelPos.asLong(), assetKey);
        long now = server.getTickCount();

        UUID activeRequestId = ACTIVE_REQUESTS.get(requestKey);
        if (activeRequestId != null) {
            RequestState activeRequest = REQUESTS.get(activeRequestId);
            int pending = activeRequest == null ? 0 : activeRequest.pendingRecipients.size();
            return new RequestResult(Result.ALREADY_QUEUED, audiblePlayers.size(), pending);
        }
        if (REQUEST_AFTER.getOrDefault(requestKey, Long.MIN_VALUE) > now) {
            return new RequestResult(Result.COOLDOWN, audiblePlayers.size(), 0);
        }

        Set<RecipientAssetKey> recipients = new HashSet<>();
        int considered = 0;
        for (UUID playerId : new HashSet<>(audiblePlayers)) {
            if (playerId == null) {
                continue;
            }
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            if (player == null || player.connection == null || player.level() != level) {
                continue;
            }
            considered++;
            RecipientAssetKey recipient = new RecipientAssetKey(playerId, assetKey);
            if (!ACTIVE_RECIPIENTS.containsKey(recipient)
                    && RECIPIENT_AFTER.getOrDefault(recipient, Long.MIN_VALUE) <= now) {
                recipients.add(recipient);
            }
        }
        if (considered == 0) {
            return new RequestResult(Result.NO_AUDIENCE, 0, 0);
        }
        if (recipients.isEmpty()) {
            return new RequestResult(Result.DEDUPLICATED, considered, 0);
        }

        UUID requestId = UUID.randomUUID();
        RequestState request = new RequestState(requestId, requestKey, new HashSet<>(recipients), now);
        REQUESTS.put(requestId, request);
        ACTIVE_REQUESTS.put(requestKey, requestId);
        REQUEST_AFTER.put(requestKey, now + REQUEST_COOLDOWN_TICKS);
        for (RecipientAssetKey recipient : recipients) {
            ACTIVE_RECIPIENTS.put(recipient, requestId);
        }

        CompletableFuture<LoadedAsset> load = sharedLoad(server, assetKey, selectedTrack, speed);
        load.whenComplete((asset, exception) -> {
            if (ownerServer == server) {
                server.execute(() -> {
                    if (ownerServer == server) {
                        finishLoading(server, requestId, asset, exception);
                    }
                });
            }
        });
        return new RequestResult(Result.QUEUED, considered, recipients.size());
    }

    /** Sends at most four background chunks, in round-robin order. */
    public static void tick(MinecraftServer server) {
        if (server == null) {
            return;
        }
        bind(server);
        long now = server.getTickCount();
        purgeExpired(now);
        cancelStaleRequests(now);

        int budget = CHUNKS_PER_TICK;
        while (budget > 0 && !TRANSFERS.isEmpty()) {
            PendingTransfer transfer = TRANSFERS.removeFirst();
            RequestState request = REQUESTS.get(transfer.requestId);
            if (request == null || !request.pendingRecipients.contains(transfer.recipient)
                    || !transfer.requestId.equals(ACTIVE_RECIPIENTS.get(transfer.recipient))) {
                continue;
            }

            ServerLevel panelLevel = server.getLevel(request.key.dimension);
            if (panelLevel == null
                    || !(panelLevel.getBlockEntity(BlockPos.of(request.key.panelPos)) instanceof DjPanelBlockEntity)) {
                cancelRequest(request, false, now);
                continue;
            }

            ServerPlayer player = server.getPlayerList().getPlayer(transfer.recipient.playerId);
            if (player == null || player.connection == null) {
                finishRecipient(request, transfer.recipient, false, now);
                continue;
            }

            int totalChunks = transfer.asset.totalChunks();
            int start = transfer.nextChunk * ASSET_CHUNK_BYTES;
            int end = Math.min(transfer.asset.bytes.length, start + ASSET_CHUNK_BYTES);
            try {
                PacketDistributor.sendToPlayer(player, new TrackAssetChunkPayload(
                        transfer.asset.trackId,
                        "audio",
                        transfer.asset.fileName,
                        transfer.nextChunk,
                        totalChunks,
                        transfer.asset.bytes.length,
                        transfer.asset.sha256,
                        Arrays.copyOfRange(transfer.asset.bytes, start, end)
                ));
            } catch (RuntimeException exception) {
                MusicBattleMod.LOGGER.warn("Unable to preload DJ audio '{}' for {}.",
                        transfer.asset.fileName, player.getGameProfile().getName(), exception);
                finishRecipient(request, transfer.recipient, false, now);
                continue;
            }

            budget--;
            request.lastProgressTick = now;
            transfer.nextChunk++;
            if (transfer.nextChunk < totalChunks) {
                TRANSFERS.addLast(transfer);
            } else {
                finishRecipient(request, transfer.recipient, true, now);
            }
        }
    }

    /** Removes pending data for a player; a later login may use an empty cache. */
    public static void onPlayerLoggedOut(UUID playerId) {
        if (playerId == null) {
            return;
        }
        TRANSFERS.removeIf(transfer -> transfer.recipient.playerId.equals(playerId));
        RECIPIENT_AFTER.keySet().removeIf(key -> key.playerId.equals(playerId));
        long now = ownerServer == null ? 0L : ownerServer.getTickCount();
        for (RequestState request : new ArrayList<>(REQUESTS.values())) {
            List<RecipientAssetKey> matches = request.pendingRecipients.stream()
                    .filter(key -> key.playerId.equals(playerId)).toList();
            for (RecipientAssetKey recipient : matches) {
                finishRecipient(request, recipient, false, now);
            }
        }
    }

    /** Cancels every preload owned by one DJ panel, for example when it breaks. */
    public static void cancelPanel(ResourceKey<Level> dimension, BlockPos panelPos) {
        if (dimension == null || panelPos == null) {
            return;
        }
        long packedPos = panelPos.asLong();
        long now = ownerServer == null ? 0L : ownerServer.getTickCount();
        for (RequestState request : new ArrayList<>(REQUESTS.values())) {
            if (request.key.dimension.equals(dimension) && request.key.panelPos == packedPos) {
                cancelRequest(request, false, now);
            }
        }
    }

    /**
     * Cancels a target that has become active before its background transfer
     * finished. Call this before sending the active track's own asset chunks.
     */
    public static void cancelActiveTrack(String trackId, Collection<UUID> audiencePlayers) {
        if (trackId == null || trackId.isBlank() || audiencePlayers == null || audiencePlayers.isEmpty()) {
            return;
        }
        Set<UUID> audience = Set.copyOf(audiencePlayers);
        long now = ownerServer == null ? 0L : ownerServer.getTickCount();
        TRANSFERS.removeIf(transfer -> transfer.recipient.asset.trackId.equals(trackId)
                && audience.contains(transfer.recipient.playerId));
        for (RequestState request : new ArrayList<>(REQUESTS.values())) {
            List<RecipientAssetKey> matches = request.pendingRecipients.stream()
                    .filter(key -> key.asset.trackId.equals(trackId) && audience.contains(key.playerId))
                    .toList();
            for (RecipientAssetKey recipient : matches) {
                finishRecipient(request, recipient, false, now);
            }
        }
    }

    /**
     * Reports whether this panel still has a running background transfer of one asset. The
     * sequential driver uses it to decide when its current plan entry is finished, whether the
     * request completed normally or was cancelled as stale.
     */
    public static boolean isRequestActive(ResourceKey<Level> dimension, BlockPos panelPos,
                                          String trackId, float speedMultiplier) {
        if (dimension == null || panelPos == null || trackId == null) {
            return false;
        }
        UUID requestId = ACTIVE_REQUESTS.get(new RequestKey(dimension, panelPos.asLong(),
                new AssetKey(trackId, speedKey(speedMultiplier))));
        return requestId != null && REQUESTS.containsKey(requestId);
    }

    /** Clears all strong references before a server/world is replaced. */
    public static void onServerStopping(MinecraftServer server) {
        if (server == null || ownerServer == null || ownerServer == server) {
            ownerServer = null;
            clear();
        }
    }

    private static CompletableFuture<LoadedAsset> sharedLoad(MinecraftServer server, AssetKey key,
                                                              MusicBattleTracks.Track track, float speed) {
        CompletableFuture<LoadedAsset> existing = SHARED_LOADS.get(key);
        if (existing != null) {
            return existing;
        }
        CompletableFuture<LoadedAsset> created = DjAudioPreparation.prepare(track, speed)
                .thenApplyAsync(prepared -> {
                    try {
                        return loadAsset(track, prepared.audio());
                    } catch (IOException exception) {
                        throw new CompletionException(exception);
                    }
                }, Util.nonCriticalIoPool());
        SHARED_LOADS.put(key, created);
        created.whenComplete((asset, exception) -> {
            if (ownerServer == server) {
                server.execute(() -> {
                    if (ownerServer == server) {
                        SHARED_LOADS.remove(key, created);
                    }
                });
            }
        });
        return created;
    }

    private static LoadedAsset loadAsset(MusicBattleTracks.Track track,
                                         MusicBattleTracks.AudioAsset audio) throws IOException {
        Path path = audio.path().toAbsolutePath().normalize();
        String fileName = transferFileName(audio.fileName());
        if (!safeFileName(fileName)
                || !fileName.toLowerCase(Locale.ROOT).endsWith(".ogg")
                || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(path)) {
            throw new IOException("Unsafe DJ preload audio asset");
        }

        BasicFileAttributes before = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        long size = before.size();
        if (!before.isRegularFile() || size <= 0L || size > MAX_AUDIO_BYTES || size > Integer.MAX_VALUE) {
            throw new IOException("Invalid DJ preload audio size: " + size);
        }

        byte[] bytes = new byte[(int) size];
        MessageDigest digest = sha256Digest();
        int offset = 0;
        try (InputStream input = Files.newInputStream(path)) {
            while (offset < bytes.length) {
                int read = input.read(bytes, offset, bytes.length - offset);
                if (read < 0) {
                    break;
                }
                if (read > 0) {
                    digest.update(bytes, offset, read);
                    offset += read;
                }
            }
            if (offset != bytes.length || input.read() >= 0) {
                throw new IOException("DJ preload audio changed size while being read");
            }
        }

        BasicFileAttributes after = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (after.size() != before.size()
                || !after.lastModifiedTime().equals(before.lastModifiedTime())
                || bytes.length < 4 || bytes[0] != 'O' || bytes[1] != 'g'
                || bytes[2] != 'g' || bytes[3] != 'S'
                || MusicBattleTracks.readOggDurationTicks(path) <= 0) {
            throw new IOException("DJ preload audio failed OGG/stability validation");
        }

        String hash = HexFormat.of().formatHex(digest.digest());
        if (!hash.matches("[a-f0-9]{64}")
                || !hash.equals(HexFormat.of().formatHex(sha256Digest().digest(bytes)))) {
            throw new IOException("DJ preload audio failed SHA-256 validation");
        }
        return new LoadedAsset(track.id(), fileName, hash, bytes);
    }

    private static void finishLoading(MinecraftServer server, UUID requestId,
                                      LoadedAsset asset, Throwable exception) {
        RequestState request = REQUESTS.get(requestId);
        if (request == null) {
            return;
        }
        long now = server.getTickCount();
        if (exception != null || asset == null || !request.key.asset.trackId.equals(asset.trackId)) {
            MusicBattleMod.LOGGER.warn("Unable to prepare background DJ preload for track '{}'.",
                    request.key.asset.trackId, unwrap(exception));
            cancelRequest(request, false, now);
            return;
        }

        request.lastProgressTick = now;
        for (RecipientAssetKey recipient : new ArrayList<>(request.pendingRecipients)) {
            ServerPlayer player = server.getPlayerList().getPlayer(recipient.playerId);
            if (player == null || player.connection == null) {
                finishRecipient(request, recipient, false, now);
            } else {
                TRANSFERS.addLast(new PendingTransfer(requestId, recipient, asset));
            }
        }
    }

    private static void finishRecipient(RequestState request, RecipientAssetKey recipient,
                                        boolean delivered, long now) {
        if (!request.pendingRecipients.remove(recipient)) {
            return;
        }
        ACTIVE_RECIPIENTS.remove(recipient, request.id);
        if (delivered) {
            request.deliveredRecipients++;
            RECIPIENT_AFTER.put(recipient, now + RECIPIENT_COOLDOWN_TICKS);
        } else {
            request.failedRecipients++;
        }
        if (request.pendingRecipients.isEmpty()) {
            completeRequest(request);
        }
    }

    private static void completeRequest(RequestState request) {
        REQUESTS.remove(request.id, request);
        ACTIVE_REQUESTS.remove(request.key, request.id);
        MusicBattleMod.LOGGER.debug("DJ audience preload '{}' finished: {} delivered, {} skipped/failed.",
                request.key.asset.trackId, request.deliveredRecipients, request.failedRecipients);
    }

    private static void cancelRequest(RequestState request, boolean delivered, long now) {
        TRANSFERS.removeIf(transfer -> transfer.requestId.equals(request.id));
        for (RecipientAssetKey recipient : new ArrayList<>(request.pendingRecipients)) {
            finishRecipient(request, recipient, delivered, now);
        }
        REQUESTS.remove(request.id, request);
        ACTIVE_REQUESTS.remove(request.key, request.id);
    }

    private static void cancelStaleRequests(long now) {
        for (RequestState request : new ArrayList<>(REQUESTS.values())) {
            if (now - request.lastProgressTick > STALE_REQUEST_TICKS) {
                MusicBattleMod.LOGGER.warn("Cancelled stale DJ audience preload for track '{}'.",
                        request.key.asset.trackId);
                cancelRequest(request, false, now);
            }
        }
    }

    private static void purgeExpired(long now) {
        REQUEST_AFTER.entrySet().removeIf(entry -> entry.getValue() <= now);
        RECIPIENT_AFTER.entrySet().removeIf(entry -> entry.getValue() <= now);
    }

    private static void bind(MinecraftServer server) {
        if (ownerServer == server) {
            return;
        }
        ownerServer = null;
        clear();
        ownerServer = server;
    }

    private static void clear() {
        TRANSFERS.clear();
        REQUESTS.clear();
        ACTIVE_REQUESTS.clear();
        REQUEST_AFTER.clear();
        ACTIVE_RECIPIENTS.clear();
        RECIPIENT_AFTER.clear();
        SHARED_LOADS.clear();
    }

    private static String transferFileName(String fileName) {
        if (fileName == null) {
            return "";
        }
        String normalized = fileName.replace('\\', '/');
        int separator = normalized.lastIndexOf('/');
        return separator >= 0 ? normalized.substring(separator + 1) : normalized;
    }

    private static boolean safeTrackId(String trackId) {
        return trackId != null && trackId.matches("[a-z0-9_-]{1,128}");
    }

    private static boolean safeFileName(String fileName) {
        return fileName != null && fileName.matches("[A-Za-z0-9._-]{1,128}") && !fileName.contains("..");
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

    private static int speedKey(float speed) {
        return Math.round(normalizeSpeed(speed) * 100.0F);
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static Throwable unwrap(Throwable throwable) {
        if (throwable == null) {
            return new IOException("Unknown DJ preload error");
        }
        Throwable current = throwable;
        while ((current instanceof CompletionException || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    public enum Result {
        QUEUED,
        ACTIVE_TRACK,
        NO_AUDIENCE,
        ALREADY_QUEUED,
        DEDUPLICATED,
        COOLDOWN,
        INVALID_REQUEST
    }

    /** Immediate result suitable for an action-bar/UI response to the DJ. */
    public record RequestResult(Result result, int audienceCount, int queuedRecipients) {
        public boolean accepted() {
            return result == Result.QUEUED;
        }
    }

    private record AssetKey(String trackId, int speedPercent) {
    }

    private record RequestKey(ResourceKey<Level> dimension, long panelPos, AssetKey asset) {
    }

    private record RecipientAssetKey(UUID playerId, AssetKey asset) {
    }

    private static final class LoadedAsset {
        private final String trackId;
        private final String fileName;
        private final String sha256;
        private final byte[] bytes;

        private LoadedAsset(String trackId, String fileName, String sha256, byte[] bytes) {
            this.trackId = trackId;
            this.fileName = fileName;
            this.sha256 = sha256;
            this.bytes = bytes;
        }

        private int totalChunks() {
            return Math.max(1, (bytes.length + ASSET_CHUNK_BYTES - 1) / ASSET_CHUNK_BYTES);
        }
    }

    private static final class RequestState {
        private final UUID id;
        private final RequestKey key;
        private final Set<RecipientAssetKey> pendingRecipients;
        private long lastProgressTick;
        private int deliveredRecipients;
        private int failedRecipients;

        private RequestState(UUID id, RequestKey key, Set<RecipientAssetKey> pendingRecipients,
                             long lastProgressTick) {
            this.id = id;
            this.key = key;
            this.pendingRecipients = pendingRecipients;
            this.lastProgressTick = lastProgressTick;
        }
    }

    private static final class PendingTransfer {
        private final UUID requestId;
        private final RecipientAssetKey recipient;
        private final LoadedAsset asset;
        private int nextChunk;

        private PendingTransfer(UUID requestId, RecipientAssetKey recipient, LoadedAsset asset) {
            this.requestId = requestId;
            this.recipient = recipient;
            this.asset = asset;
        }
    }
}

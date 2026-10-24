package ru.gora.musicbattle.server.workshop;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import ru.gora.musicbattle.MusicBattleMod;
import ru.gora.musicbattle.network.ServerBeatmapChunkPayload;
import ru.gora.musicbattle.network.ServerBeatmapOfferPayload;
import ru.gora.musicbattle.network.ServerBeatmapOfferResultPayload;
import ru.gora.musicbattle.network.ServerBeatmapResultPayload;
import ru.gora.musicbattle.network.ServerBeatmapTransferAbortPayload;
import ru.gora.musicbattle.network.VoteInstalledPayload;
import ru.gora.musicbattle.server.BattleManager;
import ru.gora.musicbattle.server.MusicBattleServerConfig;
import ru.gora.musicbattle.server.MusicBattleTracks;
import ru.gora.musicbattle.workshop.BeatmapPackage;
import ru.gora.musicbattle.workshop.WorkshopLayout;

/**
 * Packages travelling from a player's client to this server (spec 06 sections 9.1 and 9.4): "Add to server", a
 * vote for a set only the voter has installed, and a DJ's playlist pick of such a set.
 *
 * <p>One transfer per player at a time. The offer is answered before a single byte moves: {@code have} when the
 * server already holds that content, {@code denied} with a reason (permission, config, quota, a set with external
 * audio, a withdrawn set), otherwise {@code need}. The bytes arrive in
 * {@value ru.gora.musicbattle.network.ServerBeatmapChunkPayload#MAX_CHUNK_BYTES}-byte chunks, are appended by one
 * dedicated executor (never the server thread), and only a package whose signature, manifest and size all match the
 * offer is installed — into a temporary root first, so a failure leaves nothing behind (08 A5.3).
 *
 * <p>An "Add to server" offer carries the size, so this server knows when the bytes are all here. A vote or a DJ
 * pick does not — the sender repacks the package only after being asked for it — so a vote stream ends with the first chunk that
 * is not full, and the sender adds an empty one when the package is a whole number of chunks
 * ({@link ru.gora.musicbattle.workshop.WorkshopCatalog#endsTransfer}).
 *
 * <p>A transfer the sender never finishes is ended here rather than left in place: a vote is stopped at the next
 * chunk once its voting has closed, and a session nothing has arrived for in a minute is swept by the next transfer
 * that starts. Without that sweep one leftover kept answering every later offer and vote from that player with
 * "one transfer at a time" until they reconnected.
 */
public final class ServerBeatmapTransfers {
    /** Uploads in progress: {@code <world>/blocky_music/.workshop_uploads/<transferId>.blockymap}. */
    public static final String UPLOAD_DIRECTORY = ".workshop_uploads";
    /** Sets fetched from a voter, one root per content hash so revisions never collide (review of 06 section 9.4). */
    public static final String CACHE_DIRECTORY = "workshop_cache";
    /** {@code music_battle.workshop.error.transfer_timed_out}: nothing arrived for {@link #STALE_TRANSFER_MS}. */
    public static final String ABORT_TIMED_OUT = "transfer_timed_out";
    /** {@code music_battle.workshop.error.vote_closed}: the voting ended while the package was still arriving. */
    public static final String ABORT_VOTE_CLOSED = "vote_closed";
    /**
     * How long a transfer may go without a single chunk before this server gives up on it. Chunks are 24 KiB, so
     * even a very slow link sends one every few seconds; a whole minute of silence means the sender is gone.
     */
    static final long STALE_TRANSFER_MS = 60_000;
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    /** What the finished package is for. */
    public enum Purpose {
        /** "Add to server": installed into the world's server beatmaps and listed for everyone. */
        SERVER_BEATMAP,
        /** An INSTALLED vote: cached for this battle only, never listed. */
        VOTE,
        /**
         * A DJ's playlist pick of a set only the DJ has installed: cached exactly like a vote, under the same server
         * setting, so a DJ without map rights can still play a Workshop map on a server that accepts player maps.
         */
        DJ
    }

    private static final class Session {
        private final UUID transferId;
        private final UUID playerId;
        private final Purpose purpose;
        private final long setId;
        private final int revision;
        private final String contentSha256;
        private final long size;
        private final Path file;
        private final VoteInstalledPayload vote;
        private int nextChunk;
        private long received;
        private boolean finishing;
        /**
         * Set on the server thread when the transfer is dropped, read by writes still queued on the transfer executor.
         * The sender pushes every chunk at once, so writes are usually still waiting when a transfer is aborted, and
         * each one would create the deleted file again and leave a partial package in the upload folder.
         */
        private volatile boolean dropped;
        /** When the last chunk arrived, so a sender that stopped talking can be told apart from a slow one. */
        private long activeAtMs;

        private Session(UUID transferId, UUID playerId, Purpose purpose, long setId, int revision, String contentSha256,
                        long size, Path file, VoteInstalledPayload vote) {
            this.transferId = transferId;
            this.playerId = playerId;
            this.purpose = purpose;
            this.setId = setId;
            this.revision = revision;
            this.contentSha256 = contentSha256;
            this.size = size;
            this.file = file;
            this.vote = vote;
        }
    }

    private static final Map<UUID, Session> BY_TRANSFER = new HashMap<>();
    private static final Map<UUID, UUID> BY_PLAYER = new HashMap<>();
    private static ExecutorService io;

    private ServerBeatmapTransfers() {
    }

    // ---------------------------------------------------------------- lifecycle

    public static synchronized void shutdown() {
        for (Session session : List.copyOf(BY_TRANSFER.values())) {
            session.dropped = true;
            deleteQuietly(session.file);
        }
        BY_TRANSFER.clear();
        BY_PLAYER.clear();
        ExecutorService pending = io;
        io = null;
        if (pending != null) {
            pending.shutdown();
            try {
                if (!pending.awaitTermination(10, TimeUnit.SECONDS)) {
                    MusicBattleMod.LOGGER.warn("A Blocky Music package transfer was still running at shutdown.");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** A player who left takes their half-finished upload with them. */
    public static void onPlayerLeft(UUID playerId) {
        UUID transferId = BY_PLAYER.remove(playerId);
        Session session = transferId == null ? null : BY_TRANSFER.get(transferId);
        if (session != null) {
            drop(session);
            if (session.vote != null) {
                // Their vote would otherwise hold the battle for the whole 30-second extension, waiting for a
                // package that is not coming any more.
                BattleManager.cancelInstalledVote(session.vote.battleId(), playerId);
            }
        }
    }

    // ---------------------------------------------------------------- offers

    /** "Add to server" (spec 06 section 9.1). Server thread. */
    public static void offer(ServerPlayer player, ServerBeatmapOfferPayload payload) {
        if (!WorkshopPermissions.canManageServerBeatmaps(player)) {
            deny(player, payload.transferId(), payload.setId(), ServerBeatmaps.DENIED_NO_PERMISSION);
            return;
        }
        if (!ServerBeatmaps.bound()) {
            deny(player, payload.transferId(), payload.setId(), ServerBeatmaps.DENIED_DISABLED);
            return;
        }
        if (!valid(payload.setId(), payload.revision(), payload.contentSha256(), payload.size())) {
            deny(player, payload.transferId(), payload.setId(), ServerBeatmaps.DENIED_INVALID);
            return;
        }
        if (ServerBeatmaps.has(payload.setId(), payload.contentSha256())) {
            PacketDistributor.sendToPlayer(player, new ServerBeatmapOfferResultPayload(payload.transferId(),
                    ServerBeatmapOfferResultPayload.HAVE, "", ServerBeatmapChunkPayload.MAX_CHUNK_BYTES));
            ServerBeatmaps.markVerified(payload.setId(), payload.contentSha256());
            return;
        }
        Optional<String> quota = ServerBeatmaps.quotaRefusal(payload.size());
        if (quota.isPresent()) {
            deny(player, payload.transferId(), payload.setId(), quota.get());
            return;
        }
        begin(player, payload.transferId(), Purpose.SERVER_BEATMAP, payload.setId(), payload.revision(),
                payload.contentSha256(), payload.size(), null);
    }

    /**
     * A vote for a set only the voter has (spec 06 section 9.4). Server thread. Returns the message to show when
     * the vote cannot be taken at all, or empty when it was accepted (possibly pending a transfer).
     */
    public static Optional<Component> voteInstalled(ServerPlayer player, VoteInstalledPayload payload) {
        if (!MusicBattleServerConfig.allowPlayerMaps()) {
            return Optional.of(Component.translatable("music_battle.l10n.server.installed_votes_disabled"));
        }
        if (!BattleManager.isVoting(payload.battleId())) {
            return Optional.of(Component.translatable("music_battle.l10n.server.track_unavailable"));
        }
        if (!valid(payload.setId(), payload.revision(), payload.contentSha256(), 1)
                || !WorkshopLayout.chartKeyValid(payload.chartKey())) {
            return Optional.of(Component.translatable("music_battle.l10n.server.track_unavailable"));
        }
        // Already here as a server beatmap or in the cache: no transfer at all.
        Optional<MusicBattleTracks.Track> known = findChart(payload.setId(), payload.contentSha256(), payload.chartKey());
        if (known.isPresent()) {
            BattleManager.applyInstalledVote(player, payload.battleId(), known.get(), payload.mobSkill(),
                    payload.unifiedNotes(), payload.deadlyParty(), payload.speedMultiplier());
            return Optional.empty();
        }
        if (ServerBeatmaps.bySetId(payload.setId())
                .filter(entry -> !entry.contentSha256().equals(payload.contentSha256())).isPresent()) {
            return Optional.of(Component.translatable("music_battle.l10n.server.installed_vote_other_revision"));
        }
        if (!BattleManager.markInstalledVotePending(payload.battleId(), player.getUUID())) {
            return Optional.of(Component.translatable("music_battle.l10n.server.track_unavailable"));
        }
        begin(player, UUID.randomUUID(), Purpose.VOTE, payload.setId(), payload.revision(), payload.contentSha256(),
                ru.gora.musicbattle.workshop.BeatmapPackage.MAX_ARCHIVE_BYTES, payload);
        return Optional.empty();
    }

    /**
     * A DJ's pick of a set only the DJ has installed. The caller has checked the panel, the server setting and the
     * identity of the package, and has told the client which set to repack; this only starts the transfer, which
     * ends in {@link ru.gora.musicbattle.server.DjPlaybackManager#workshopCached} or, refused, in
     * {@link ru.gora.musicbattle.server.DjPlaybackManager#workshopTransferFailed}. Server thread.
     */
    public static void djInstalled(ServerPlayer player, long setId, int revision, String contentSha256) {
        if (!valid(setId, revision, contentSha256, 1)) {
            deny(player, UUID.randomUUID(), setId, ServerBeatmaps.DENIED_INVALID);
            return;
        }
        begin(player, UUID.randomUUID(), Purpose.DJ, setId, revision, contentSha256, BeatmapPackage.MAX_ARCHIVE_BYTES, null);
    }

    private static void begin(ServerPlayer player, UUID transferId, Purpose purpose, long setId, int revision,
                              String contentSha256, long size, VoteInstalledPayload vote) {
        sweepStale(player.server);
        if (BY_PLAYER.containsKey(player.getUUID())) {
            deny(player, transferId, setId, ServerBeatmaps.DENIED_BUSY);
            if (vote != null) {
                BattleManager.cancelInstalledVote(vote.battleId(), player.getUUID());
            }
            return;
        }
        Path root = uploadRoot();
        if (root == null) {
            deny(player, transferId, setId, ServerBeatmaps.DENIED_DISABLED);
            return;
        }
        if (BY_TRANSFER.isEmpty()) {
            sweepUploads(root);
        }
        Path file = root.resolve(transferId + WorkshopLayout.PACKAGE_EXTENSION).toAbsolutePath().normalize();
        if (!file.getParent().equals(root)) {
            deny(player, transferId, setId, ServerBeatmaps.DENIED_INVALID);
            return;
        }
        deleteQuietly(file);
        Session session = new Session(transferId, player.getUUID(), purpose, setId, revision, contentSha256, size, file, vote);
        session.activeAtMs = System.currentTimeMillis();
        BY_TRANSFER.put(transferId, session);
        BY_PLAYER.put(player.getUUID(), transferId);
        PacketDistributor.sendToPlayer(player, new ServerBeatmapOfferResultPayload(transferId,
                ServerBeatmapOfferResultPayload.NEED, "", ServerBeatmapChunkPayload.MAX_CHUNK_BYTES));
    }

    // ---------------------------------------------------------------- chunks

    /** Server thread: bookkeeping here, the append itself on the transfer executor. */
    public static void acceptChunk(ServerPlayer player, ServerBeatmapChunkPayload payload) {
        Session session = BY_TRANSFER.get(payload.transferId());
        if (session == null || !session.playerId.equals(player.getUUID()) || session.finishing) {
            return;
        }
        if (session.vote != null && !BattleManager.isVoting(session.vote.battleId())) {
            // The voting this package was for is over, so the rest of it would arrive too late to be voted with. A
            // battle is probably already playing over this same connection, and pushing the remaining megabytes
            // through it would only make that battle stutter; the voter is owed the reason instead.
            abort(player, session, ABORT_VOTE_CLOSED);
            return;
        }
        session.activeAtMs = System.currentTimeMillis();
        byte[] data = payload.data();
        if (payload.chunkIndex() != session.nextChunk
                || data.length > ServerBeatmapChunkPayload.MAX_CHUNK_BYTES
                || session.received + data.length > session.size
                || session.received + data.length > BeatmapPackage.MAX_ARCHIVE_BYTES) {
            abort(player, session, ServerBeatmaps.DENIED_INVALID);
            return;
        }
        if (data.length == 0 && session.received <= 0) {
            // Nothing at all is not a package. Refusing it keeps the empty chunk purely an end marker.
            abort(player, session, ServerBeatmaps.DENIED_INVALID);
            return;
        }
        session.nextChunk++;
        session.received += data.length;
        // An empty chunk says the sender has no more bytes, whatever the offer promised. A vote or a DJ pick
        // promised no size at all, so there any chunk that is not full is the end as well.
        boolean last = data.length == 0 || session.received >= session.size
                || session.purpose != Purpose.SERVER_BEATMAP
                        && ru.gora.musicbattle.workshop.WorkshopCatalog.endsTransfer(data.length,
                                ServerBeatmapChunkPayload.MAX_CHUNK_BYTES);
        if (last) {
            session.finishing = true;
        }
        MinecraftServer server = player.server;
        io().execute(() -> {
            if (session.dropped) {
                return;
            }
            try {
                try (OutputStream output = Files.newOutputStream(session.file, StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND, StandardOpenOption.WRITE)) {
                    output.write(data);
                }
                if (session.dropped) {
                    // Dropped while this chunk was being written, after drop() had already deleted the file.
                    deleteQuietly(session.file);
                    return;
                }
            } catch (IOException exception) {
                MusicBattleMod.LOGGER.warn("Unable to write an incoming Blocky Music package.", exception);
                server.execute(() -> abort(player, session, ServerBeatmaps.DENIED_INVALID));
                return;
            }
            if (last) {
                finish(server, player, session);
            }
        });
    }

    /**
     * Drops the transfers nothing has arrived for in {@link #STALE_TRANSFER_MS}. A client that crashed, was killed or
     * simply stopped sending leaves its session behind, and because a player may have only one transfer at a time,
     * that one leftover answered every later offer and every later vote with "one transfer at a time" until they
     * reconnected. Nothing ticks in here, so a starting transfer sweeps first: that is exactly the moment the busy
     * answer would otherwise be wrong. A session being installed is left alone — its file is in use.
     */
    private static void sweepStale(MinecraftServer server) {
        long now = System.currentTimeMillis();
        for (Session session : List.copyOf(BY_TRANSFER.values())) {
            if (session.finishing || now - session.activeAtMs < STALE_TRANSFER_MS) {
                continue;
            }
            MusicBattleMod.LOGGER.info("Dropped a Blocky Music package transfer that stopped halfway ({} of {} bytes).",
                    session.received, session.size);
            ServerPlayer owner = server.getPlayerList().getPlayer(session.playerId);
            if (owner != null) {
                abort(owner, session, ABORT_TIMED_OUT);
                continue;
            }
            drop(session);
            if (session.vote != null) {
                BattleManager.cancelInstalledVote(session.vote.battleId(), session.playerId);
            }
        }
    }

    /** The player cancelled; a partial file is removed. Server thread. */
    public static void cancel(ServerPlayer player, UUID transferId) {
        Session session = BY_TRANSFER.get(transferId);
        if (session == null || !session.playerId.equals(player.getUUID())) {
            return;
        }
        drop(session);
        if (session.vote != null) {
            BattleManager.cancelInstalledVote(session.vote.battleId(), player.getUUID());
        }
        // A client cancels only when it could not read or repack its copy, usually because the set is not installed
        // there at all; a DJ waiting on the playlist line is owed that rather than a silent end.
        ru.gora.musicbattle.server.DjPlaybackManager.workshopTransferFailed(player, session.setId,
                Component.translatable("screen.music_battle.dj_playlist.workshop.not_sent"));
    }

    // ---------------------------------------------------------------- finishing

    /** Transfer executor: validate and install, then hop back to the server thread to apply the result. */
    private static void finish(MinecraftServer server, ServerPlayer player, Session session) {
        Path worldStorage = MusicBattleTracks.worldStorageDirectory();
        if (worldStorage == null) {
            server.execute(() -> abort(player, session, ServerBeatmaps.DENIED_DISABLED));
            return;
        }
        try {
            if (session.purpose == Purpose.SERVER_BEATMAP) {
                ServerBeatmaps.Entry entry = ServerBeatmaps.install(session.file, session.setId, session.revision,
                        session.contentSha256, session.playerId, player.getGameProfile().getName(), worldStorage);
                server.execute(() -> {
                    drop(session);
                    ServerBeatmaps.applyInstalled(entry);
                    PacketDistributor.sendToPlayer(player, new ServerBeatmapResultPayload(true,
                            Component.translatable("music_battle.l10n.server.server_beatmap_added", entry.title()),
                            entry.setId()));
                });
                return;
            }
            Path cacheRoot = cacheRootFor(worldStorage, session.contentSha256);
            if (!ru.gora.musicbattle.workshop.WorkshopSignature.verifyPackage(session.file, session.setId,
                    session.revision, session.contentSha256)) {
                throw new ru.gora.musicbattle.workshop.BeatmapPackageException(
                        ru.gora.musicbattle.workshop.BeatmapPackageException.Reason.INVALID_MANIFEST,
                        ServerBeatmaps.DENIED_UNSIGNED);
            }
            var manifest = BeatmapPackage.readManifest(session.file);
            if (manifest.setId() != session.setId || manifest.revision() != session.revision
                    || !manifest.contentSha256().equals(session.contentSha256)
                    || manifest.audio().policy() != ru.gora.musicbattle.workshop.BeatmapManifest.AudioPolicy.INCLUDED) {
                throw new ru.gora.musicbattle.workshop.BeatmapPackageException(
                        ru.gora.musicbattle.workshop.BeatmapPackageException.Reason.INVALID_MANIFEST,
                        ServerBeatmaps.DENIED_INVALID);
            }
            Files.createDirectories(cacheRoot);
            BeatmapPackage.install(session.file, cacheRoot);
            evictCache(worldStorage, session.contentSha256);
            server.execute(() -> {
                drop(session);
                if (session.purpose == Purpose.DJ) {
                    ru.gora.musicbattle.server.DjPlaybackManager.workshopCached(player, session.setId,
                            session.contentSha256);
                    return;
                }
                VoteInstalledPayload vote = session.vote;
                if (vote == null) {
                    return;
                }
                if (!BattleManager.isVoting(vote.battleId())) {
                    // The window closed between the last chunk and this hop back to the server thread; the longer
                    // case is caught in acceptChunk. applyInstalledVote would drop the vote without a word, and the
                    // package is cached by now, so the map is ready for the next vote and the player hears why this
                    // one did not count.
                    PacketDistributor.sendToPlayer(player, new ServerBeatmapResultPayload(false,
                            Component.translatable("music_battle.l10n.server.installed_vote_too_late"), session.setId));
                    return;
                }
                Optional<MusicBattleTracks.Track> chart = findChart(session.setId, session.contentSha256, vote.chartKey());
                if (chart.isEmpty()) {
                    BattleManager.cancelInstalledVote(vote.battleId(), player.getUUID());
                    PacketDistributor.sendToPlayer(player, new ServerBeatmapResultPayload(false,
                            Component.translatable("music_battle.l10n.server.track_unavailable"), session.setId));
                    return;
                }
                BattleManager.applyInstalledVote(player, vote.battleId(), chart.get(), vote.mobSkill(),
                        vote.unifiedNotes(), vote.deadlyParty(), vote.speedMultiplier());
            });
        } catch (IOException | RuntimeException exception) {
            MusicBattleMod.LOGGER.warn("Refused an incoming Blocky Music package from {}: {}",
                    player.getGameProfile().getName(), MusicBattleTracks.safeMessage(exception));
            String reason = exception instanceof ru.gora.musicbattle.workshop.BeatmapPackageException packageException
                    ? packageException.getMessage() : ServerBeatmaps.DENIED_INVALID;
            server.execute(() -> abort(player, session, reason.contains(ServerBeatmaps.DENIED_UNSIGNED)
                    ? ServerBeatmaps.DENIED_UNSIGNED : ServerBeatmaps.DENIED_INVALID));
        }
    }

    // ---------------------------------------------------------------- cache

    /** {@code <world>/blocky_music/workshop_cache/<contentSha256>} — its own root, so revisions never collide. */
    static Path cacheRootFor(Path worldStorage, String contentSha256) {
        Path root = worldStorage.resolve(CACHE_DIRECTORY).toAbsolutePath().normalize();
        Path folder = root.resolve(contentSha256).toAbsolutePath().normalize();
        if (!folder.getParent().equals(root) || !SHA256.matcher(contentSha256).matches()) {
            throw new IllegalArgumentException("unsafe cache root");
        }
        return folder;
    }

    /** Least-recently-used eviction down to {@code workshop.cacheQuotaBytes}; the newest entry is never dropped. */
    static void evictCache(Path worldStorage, String keep) {
        Path root = worldStorage.resolve(CACHE_DIRECTORY).toAbsolutePath().normalize();
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        List<Path> folders = new ArrayList<>();
        try (var stream = Files.newDirectoryStream(root)) {
            for (Path folder : stream) {
                if (Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)
                        && !String.valueOf(folder.getFileName()).equals(keep)) {
                    folders.add(folder);
                }
            }
        } catch (IOException exception) {
            return;
        }
        long total = ServerBeatmaps.folderBytes(root);
        long quota = MusicBattleServerConfig.cacheQuotaBytes();
        if (total <= quota) {
            return;
        }
        folders.sort(Comparator.comparingLong(folder -> {
            try {
                return Files.getLastModifiedTime(folder).toMillis();
            } catch (IOException ignored) {
                return 0L;
            }
        }));
        for (Path folder : folders) {
            if (total <= quota) {
                return;
            }
            long size = ServerBeatmaps.folderBytes(folder);
            try {
                BeatmapPackage.deleteTree(folder, root);
                total -= size;
            } catch (IOException exception) {
                MusicBattleMod.LOGGER.warn("Unable to evict a cached Blocky Music package.", exception);
            }
        }
    }

    /**
     * The chart of a set the server can already play: a server beatmap, or a cached package fetched from a voter.
     * Cached charts are loaded on demand and never enter {@code availableTracks()}.
     */
    public static Optional<MusicBattleTracks.Track> findChart(long setId, String contentSha256, String chartKey) {
        String chartId = WorkshopLayout.chartKeyValid(chartKey) ? WorkshopLayout.chartId(setId, chartKey) : "";
        if (chartId.isEmpty()) {
            return Optional.empty();
        }
        if (ServerBeatmaps.has(setId, contentSha256)) {
            return MusicBattleTracks.workshopTracks().stream().filter(track -> track.id().equals(chartId)).findFirst();
        }
        return cachedCharts(setId, contentSha256).stream().filter(track -> track.id().equals(chartId)).findFirst();
    }

    /** Every chart of a set cached from a voter or a DJ, read from disk; empty when that content is not cached. */
    public static List<MusicBattleTracks.Track> cachedCharts(long setId, String contentSha256) {
        Path worldStorage = MusicBattleTracks.worldStorageDirectory();
        if (worldStorage == null || !SHA256.matcher(contentSha256 == null ? "" : contentSha256).matches()) {
            return List.of();
        }
        Path folder = worldStorage.resolve(CACHE_DIRECTORY).resolve(contentSha256)
                .resolve(WorkshopLayout.INSTALLED_DIRECTORY_NAME).toAbsolutePath().normalize();
        if (!Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        return MusicBattleTracks.loadDirectory(folder, MusicBattleTracks.Source.WORKSHOP).stream()
                .filter(track -> track.workshopSetId() == setId).toList();
    }

    // ---------------------------------------------------------------- helpers

    private static boolean valid(long setId, int revision, String contentSha256, long size) {
        return setId > 0 && revision > 0 && contentSha256 != null && SHA256.matcher(contentSha256).matches()
                && size > 0 && size <= BeatmapPackage.MAX_ARCHIVE_BYTES;
    }

    private static void deny(ServerPlayer player, UUID transferId, long setId, String reason) {
        PacketDistributor.sendToPlayer(player, new ServerBeatmapOfferResultPayload(transferId,
                ServerBeatmapOfferResultPayload.DENIED, reason, 0));
        tellDj(player, setId, reason);
    }

    /**
     * A DJ waiting for this set on the playlist line hears the refusal there too; the toast alone left the line
     * saying "Sending..." with nothing to remove or retry. Does nothing when no DJ pick waits for this set.
     */
    private static void tellDj(ServerPlayer player, long setId, String reason) {
        ru.gora.musicbattle.server.DjPlaybackManager.workshopTransferFailed(player, setId,
                Component.translatable(ru.gora.musicbattle.workshop.api.WorkshopApiException.translationKey(reason)));
    }

    private static void abort(ServerPlayer player, Session session, String reason) {
        if (session.dropped) {
            // A write or an install that failed after the transfer was already dropped: the player has been told and
            // the vote released once, and a second abort would only contradict the first.
            return;
        }
        drop(session);
        PacketDistributor.sendToPlayer(player, new ServerBeatmapTransferAbortPayload(session.transferId, reason));
        if (session.vote != null) {
            BattleManager.cancelInstalledVote(session.vote.battleId(), session.playerId);
        }
        tellDj(player, session.setId, reason);
    }

    private static void drop(Session session) {
        session.dropped = true;
        BY_TRANSFER.remove(session.transferId);
        BY_PLAYER.remove(session.playerId, session.transferId);
        deleteQuietly(session.file);
    }

    private static Path uploadRoot() {
        Path worldStorage = MusicBattleTracks.worldStorageDirectory();
        if (worldStorage == null) {
            return null;
        }
        Path root = worldStorage.resolve(UPLOAD_DIRECTORY).toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
        } catch (IOException exception) {
            MusicBattleMod.LOGGER.warn("Unable to create the Blocky Music upload folder.", exception);
            return null;
        }
        return root;
    }

    /**
     * Deletes every package left in the upload folder. Only called while no transfer is open, so each file there is
     * the remains of a server that crashed or was killed mid-upload; nothing else would ever remove them.
     */
    private static void sweepUploads(Path root) {
        try (var leftovers = Files.newDirectoryStream(root, "*" + WorkshopLayout.PACKAGE_EXTENSION)) {
            for (Path leftover : leftovers) {
                deleteQuietly(leftover);
            }
        } catch (IOException ignored) {
            // The folder was just created or checked; an unreadable one fails the transfer itself with a reason.
        }
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // A leftover in the upload folder is harmless; the next transfer with that id overwrites it.
        }
    }

    private static synchronized ExecutorService io() {
        if (io == null) {
            io = Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "blocky-music-package-transfer");
                thread.setDaemon(true);
                return thread;
            });
        }
        return io;
    }
}

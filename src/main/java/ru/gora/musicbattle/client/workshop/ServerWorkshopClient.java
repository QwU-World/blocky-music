package ru.gora.musicbattle.client.workshop;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.PacketDistributor;
import ru.gora.musicbattle.MusicBattleMod;
import ru.gora.musicbattle.client.ClientTrackAssets;
import ru.gora.musicbattle.client.ui.CoverCache;
import ru.gora.musicbattle.client.workshop.layout.BattleTracks;
import ru.gora.musicbattle.data.TrackSummary;
import ru.gora.musicbattle.network.ServerBeatmapChunkPayload;
import ru.gora.musicbattle.network.ServerBeatmapOfferPayload;
import ru.gora.musicbattle.network.ServerBeatmapOfferResultPayload;
import ru.gora.musicbattle.network.ServerBeatmapRemovePayload;
import ru.gora.musicbattle.network.ServerBeatmapResultPayload;
import ru.gora.musicbattle.network.ServerBeatmapTransferAbortPayload;
import ru.gora.musicbattle.network.ServerBeatmapTransferCancelPayload;
import ru.gora.musicbattle.network.ServerBeatmapsPagePayload;
import ru.gora.musicbattle.network.ServerBeatmapsRequestPayload;
import ru.gora.musicbattle.network.ServerCommentDeletePayload;
import ru.gora.musicbattle.network.ServerCommentPostPayload;
import ru.gora.musicbattle.network.ServerCommentReportPayload;
import ru.gora.musicbattle.network.ServerCommentResultPayload;
import ru.gora.musicbattle.network.ServerCommentsPagePayload;
import ru.gora.musicbattle.network.ServerCommentsRequestPayload;
import ru.gora.musicbattle.network.ServerTracksPayload;
import ru.gora.musicbattle.network.ServerTracksRequestPayload;
import ru.gora.musicbattle.network.TrackPreviewRequestPayload;
import ru.gora.musicbattle.network.WorkshopServerInfoPayload;
import ru.gora.musicbattle.network.WorkshopSetStateReportPayload;
import ru.gora.musicbattle.workshop.WorkshopCatalog;
import ru.gora.musicbattle.workshop.api.CancelToken;
import ru.gora.musicbattle.workshop.api.InstalledCheck;
import ru.gora.musicbattle.workshop.api.WorkshopApiException;

/**
 * The client's side of the Minecraft server's Workshop features (spec 06 sections 9.1 and 9.2): it registers the
 * {@link WorkshopServerHooks} the finished screens already call, uploads a package when the server asks for one,
 * and reports what the Workshop says about the sets this player has installed.
 *
 * <p>Transfers respect 08 A5.1 in full: nothing is offered for a set whose last installed-check says
 * {@code withdrawn} or {@code audio_removed}, or whose check is older than seven days. The client runs one check
 * first when it can; offline, it refuses with "Check this map online first".
 *
 * <p>Render thread, except the repack and the chunk reads, which run on the Workshop's IO executor.
 */
public final class ServerWorkshopClient {
    private static final int MAX_CHUNK_BYTES = ServerBeatmapChunkPayload.MAX_CHUNK_BYTES;

    /** The one upload in flight (the server allows one per player). */
    private record Upload(UUID transferId, long setId, int revision, String contentSha256, Path archive, long size) {
    }

    private static volatile WorkshopServerInfoPayload info;
    /** Read by the chunk loop on the Workshop IO thread, written on the render thread: an abort has to be seen. */
    private static volatile Upload pending;
    private static Path pendingArchive;
    private static long pendingSetId;
    private static int pendingRevision;
    private static String pendingContentSha = "";
    private static Consumer<List<CardModel>> pageCallback;
    private static Consumer<String> pageError;
    private static Consumer<BattleTracks> tracksCallback;
    private static Consumer<String> actionCallback;
    private static ServerCommentsView commentsView;
    private static boolean lastPageCanManage;

    /**
     * The battle id a picture request carries when no battle asked for it: the Server tab outside a battle. A battle's
     * own id is random, so the nil id can never be mistaken for one.
     */
    public static final UUID NO_BATTLE = new UUID(0L, 0L);
    /** How often the cache on disk is looked at again for a picture that is on its way, rather than on every frame. */
    private static final long PICTURE_RECHECK_MS = 500L;
    /** Pictures of the server's tracks already on disk, by track id; render thread only. */
    private static final Map<String, Path> trackPictures = new HashMap<>();
    private static final Map<String, Long> pictureLookups = new HashMap<>();
    /** Tracks whose picture was asked for on this server, so a card does not ask again on every frame. */
    private static final Set<String> picturesAsked = new HashSet<>();

    private ServerWorkshopClient() {
    }

    // ---------------------------------------------------------------- server info

    /** The server told us what it offers; this is what un-hides the tab and the button. */
    public static void acceptServerInfo(WorkshopServerInfoPayload payload) {
        info = payload;
        if (payload.serverBeatmaps() || payload.serverComments()) {
            WorkshopServerHooks.setServerBeatmaps(BEATMAPS);
            WorkshopServerHooks.setServerComments(COMMENTS);
        } else {
            WorkshopServerHooks.clear();
        }
        ClientTrackAssets.refreshHashIndex();
        reportInstalledStates();
    }

    /** Leaving a server forgets everything about it. */
    public static void clear() {
        info = null;
        pending = null;
        pendingArchive = null;
        pageCallback = null;
        pageError = null;
        tracksCallback = null;
        actionCallback = null;
        commentsView = null;
        // Another server may give the same track id another picture, so nothing found or asked for here carries over.
        trackPictures.clear();
        pictureLookups.clear();
        picturesAsked.clear();
        WorkshopServerHooks.clear();
    }

    /**
     * The picture the server keeps for one of its tracks, for the Server tab outside a battle; null until it is here.
     *
     * <p>It reuses the battle picker's request with {@link #NO_BATTLE} in place of a battle, asked once per track per
     * server, and the answer arrives as the same asset offer a battle's pictures do. Until it is on disk the cache is
     * looked at again every half second, never on every frame.
     */
    public static ResourceLocation trackPicture(BattleTracks.Track row) {
        if (row == null || row.previewFile().isEmpty()) {
            return null;
        }
        Path file = trackPictures.get(row.id());
        if (file == null) {
            long now = System.currentTimeMillis();
            Long next = pictureLookups.get(row.id());
            if (next != null && now < next) {
                return null;
            }
            pictureLookups.put(row.id(), now + PICTURE_RECHECK_MS);
            file = ClientTrackAssets.cachedAsset(row.id(), row.previewFile()).orElse(null);
            if (file == null) {
                Minecraft minecraft = Minecraft.getInstance();
                if (minecraft.getConnection() != null && picturesAsked.add(row.id())
                        && !ClientTrackAssets.isTransferActive(row.id(), "preview", row.previewFile())) {
                    PacketDistributor.sendToServer(new TrackPreviewRequestPayload(NO_BATTLE, List.of(row.id())));
                }
                return null;
            }
            trackPictures.put(row.id(), file);
        }
        return CoverCache.getFile(file);
    }

    public static boolean allowPlayerMaps() {
        WorkshopServerInfoPayload current = info;
        return current != null && current.allowPlayerMaps();
    }

    public static boolean onlineMode() {
        WorkshopServerInfoPayload current = info;
        return current == null || current.onlineMode();
    }

    public static boolean canManage() {
        WorkshopServerInfoPayload current = info;
        return current != null && current.canManage();
    }

    public static boolean canModerate() {
        WorkshopServerInfoPayload current = info;
        return current != null && current.canModerate();
    }

    /** The open server-comments view, so a page or a result reaches it. */
    public static void setCommentsView(ServerCommentsView view) {
        commentsView = view;
    }

    // ---------------------------------------------------------------- hooks

    private static final WorkshopServerHooks.ServerBeatmaps BEATMAPS = new WorkshopServerHooks.ServerBeatmaps() {
        @Override
        public boolean available() {
            WorkshopServerInfoPayload current = info;
            return current != null && current.serverBeatmaps();
        }

        @Override
        public void request(String cursor, String query, Consumer<List<CardModel>> onPage, Consumer<String> onError) {
            pageCallback = onPage;
            pageError = onError;
            PacketDistributor.sendToServer(new ServerBeatmapsRequestPayload(cursor == null ? "" : cursor,
                    query == null ? "" : query));
        }

        @Override
        public void requestTracks(Consumer<BattleTracks> onTracks) {
            tracksCallback = onTracks;
            PacketDistributor.sendToServer(new ServerTracksRequestPayload());
        }

        @Override
        public boolean canManage() {
            return ServerWorkshopClient.canManage();
        }

        @Override
        public void remove(long setId, Consumer<String> onResult) {
            actionCallback = onResult;
            PacketDistributor.sendToServer(new ServerBeatmapRemovePayload(setId));
        }
    };

    private static final WorkshopServerHooks.ServerComments COMMENTS = new WorkshopServerHooks.ServerComments() {
        @Override
        public boolean available() {
            WorkshopServerInfoPayload current = info;
            return current != null && current.serverComments();
        }

        @Override
        public void request(String key, Long beforeId, Consumer<Object> onPage, Consumer<String> onError) {
            PacketDistributor.sendToServer(new ServerCommentsRequestPayload(key, beforeId == null ? 0L : beforeId,
                    ServerCommentsPagePayload.MAX_ROWS));
        }

        @Override
        public void post(String key, String text, Consumer<String> onResult) {
            actionCallback = onResult;
            PacketDistributor.sendToServer(new ServerCommentPostPayload(key, text));
        }

        @Override
        public void delete(String key, long commentId, Consumer<String> onResult) {
            actionCallback = onResult;
            PacketDistributor.sendToServer(new ServerCommentDeletePayload(key, commentId));
        }

        @Override
        public void report(String key, long commentId, String reason, Consumer<String> onResult) {
            actionCallback = onResult;
            PacketDistributor.sendToServer(new ServerCommentReportPayload(key, commentId, reason));
        }
    };

    // ---------------------------------------------------------------- incoming

    /** The server's whole track list, read the way a battle's vote list is read. */
    public static void acceptServerTracks(ServerTracksPayload payload) {
        Consumer<BattleTracks> callback = tracksCallback;
        tracksCallback = null;
        if (callback == null) {
            return;
        }
        List<BattleTracks.Track> rows = new ArrayList<>(payload.tracks().size());
        for (TrackSummary track : payload.tracks()) {
            BattleTracks.Kind kind = switch (track.source()) {
                case WORKSHOP -> BattleTracks.Kind.WORKSHOP;
                case USER -> BattleTracks.Kind.PLAYERS;
                case SERVER -> BattleTracks.Kind.SERVER;
                case INSTALLED -> null;
            };
            if (kind == null) {
                continue;
            }
            String chartKey = kind == BattleTracks.Kind.WORKSHOP ? BattleTracks.chartKeyOf(track.id(), track.setId()) : "";
            rows.add(new BattleTracks.Track(track.id(), kind, track.setId(), chartKey, track.title(), track.description(),
                    track.publishedBy(), track.mapper(), track.contentSha256(), track.bpm(), track.durationTicks(),
                    track.previewFile()));
        }
        callback.accept(new BattleTracks(rows, payload.truncated()));
    }

    public static void acceptServerBeatmapsPage(ServerBeatmapsPagePayload payload) {
        lastPageCanManage = payload.canManage();
        Consumer<List<CardModel>> callback = pageCallback;
        pageCallback = null;
        pageError = null;
        if (callback == null) {
            return;
        }
        List<CardModel> cards = new ArrayList<>(payload.items().size());
        for (ServerBeatmapsPagePayload.Item item : payload.items()) {
            cards.add(CardModel.ofServerBeatmap(item));
        }
        callback.accept(List.copyOf(cards));
    }

    public static void acceptServerBeatmapResult(ServerBeatmapResultPayload payload) {
        Consumer<String> callback = actionCallback;
        actionCallback = null;
        if (callback != null) {
            callback.accept(payload.success() ? "" : payload.message().getString());
        }
        WorkshopToasts.show(Component.translatable(ru.gora.musicbattle.client.workshop.layout.WorkshopStrings.KEY + "server.title"), payload.message());
    }

    public static void acceptServerCommentsPage(ServerCommentsPagePayload payload) {
        ServerCommentsView view = commentsView;
        if (view != null) {
            view.acceptPage(payload);
        }
    }

    public static void acceptServerCommentResult(ServerCommentResultPayload payload) {
        Consumer<String> callback = actionCallback;
        actionCallback = null;
        if (callback != null) {
            callback.accept(payload.success() ? "" : payload.message().getString());
        }
        ServerCommentsView view = commentsView;
        if (view != null) {
            view.acceptResult(payload);
        }
        if (!payload.success()) {
            WorkshopToasts.show(Component.translatable(ru.gora.musicbattle.client.workshop.layout.WorkshopStrings.KEY + "server.title"), payload.message());
        }
    }

    // ---------------------------------------------------------------- add to server

    /**
     * "Add to server" from an installed card. Runs the withdrawn rules first, then repacks and offers; the server
     * answers whether it needs the bytes.
     */
    public static void addToServer(WorkshopScreen screen, long setId) {
        WorkshopClient client = WorkshopClient.get();
        WorkshopCatalog.InstalledSet set = client.catalog().bySetId(setId).orElse(null);
        if (set == null) {
            return;
        }
        if (set.manifest().audio().policy() != ru.gora.musicbattle.workshop.BeatmapManifest.AudioPolicy.INCLUDED) {
            screen.showError("external_audio");
            return;
        }
        if (!client.catalog().signed(setId)) {
            screen.showError("unsigned_package");
            return;
        }
        if (pending != null) {
            screen.showError("transfer_busy");
            return;
        }
        client.io().execute(() -> {
            InstalledCheck.TransferVerdict verdict = client.installedCheck().transferVerdict(setId, set.contentSha256());
            if (verdict == InstalledCheck.TransferVerdict.CHECK_FIRST && client.networkAllowed()) {
                try {
                    client.installedCheck().runNow(CancelToken.none());
                    verdict = client.installedCheck().transferVerdict(setId, set.contentSha256());
                } catch (WorkshopApiException ignored) {
                    // Offline: the verdict below refuses with "check this map online first".
                }
            }
            if (verdict != InstalledCheck.TransferVerdict.ALLOWED) {
                String code = switch (verdict) {
                    case WITHDRAWN -> "withdrawn";
                    case AUDIO_REMOVED -> "audio_removed";
                    default -> "check_online_first";
                };
                screen.later(() -> screen.showError(code));
                return;
            }
            try {
                Path archive = client.catalog().repack(setId, uploadsDirectory(client));
                long size = Files.size(archive);
                UUID transferId = UUID.randomUUID();
                screen.later(() -> {
                    pending = new Upload(transferId, setId, set.revision(), set.contentSha256(), archive, size);
                    PacketDistributor.sendToServer(new ServerBeatmapOfferPayload(transferId, setId, set.revision(),
                            set.contentSha256(), size));
                });
            } catch (IOException exception) {
                MusicBattleMod.LOGGER.warn("Unable to repack a Blocky Music set for this server.", exception);
                screen.later(() -> screen.showError("install_failed"));
            }
        });
    }

    /** Remembers what an INSTALLED vote offered, so a later {@code need} knows which package to send. */
    public static void rememberVoteUpload(long setId, int revision, String contentSha256) {
        pendingSetId = setId;
        pendingRevision = revision;
        pendingContentSha = contentSha256 == null ? "" : contentSha256;
        pendingArchive = null;
    }

    public static void acceptServerBeatmapOfferResult(ServerBeatmapOfferResultPayload payload) {
        switch (payload.verdict()) {
            case ServerBeatmapOfferResultPayload.HAVE -> {
                dropPending();
                WorkshopToasts.show(Component.translatable(ru.gora.musicbattle.client.workshop.layout.WorkshopStrings.KEY + "server.title"),
                        Component.translatable(ru.gora.musicbattle.client.workshop.layout.WorkshopStrings.KEY + "server.already_there"));
            }
            case ServerBeatmapOfferResultPayload.NEED -> upload(payload.transferId());
            default -> {
                dropPending();
                WorkshopToasts.problem(Component.translatable(ru.gora.musicbattle.client.workshop.layout.WorkshopStrings.KEY + "server.title"), WorkshopText.error(payload.reason()));
            }
        }
    }

    public static void acceptServerBeatmapAbort(ServerBeatmapTransferAbortPayload payload) {
        dropPending();
        WorkshopToasts.problem(Component.translatable(ru.gora.musicbattle.client.workshop.layout.WorkshopStrings.KEY + "server.title"), WorkshopText.error(payload.reason()));
    }

    /** Streams the package in 24 KiB chunks off the render thread (spec 06 section 9.1). */
    private static void upload(UUID transferId) {
        WorkshopClient client = WorkshopClient.get();
        Upload upload = pending;
        Path archive = upload != null ? upload.archive() : pendingArchive;
        long setId = upload != null ? upload.setId() : pendingSetId;
        int revision = upload != null ? upload.revision() : pendingRevision;
        String contentSha = upload != null ? upload.contentSha256() : pendingContentSha;
        if (archive == null) {
            // An INSTALLED vote: the package is repacked only now, when the server actually asks for it.
            client.io().execute(() -> {
                try {
                    Path built = client.catalog().repack(setId, uploadsDirectory(client));
                    Minecraft.getInstance().execute(() -> {
                        pendingArchive = built;
                        pending = new Upload(transferId, setId, revision, contentSha, built, sizeOf(built));
                        streamChunks(client, transferId, built);
                    });
                } catch (IOException exception) {
                    MusicBattleMod.LOGGER.warn("Unable to repack a Blocky Music set for this vote.", exception);
                    PacketDistributor.sendToServer(new ServerBeatmapTransferCancelPayload(transferId));
                }
            });
            return;
        }
        streamChunks(client, transferId, archive);
    }

    /** Whether this upload is still the one the server is waiting for; an aborted or replaced one is not. */
    private static boolean uploading(UUID transferId) {
        Upload current = pending;
        return current != null && current.transferId().equals(transferId);
    }

    private static void streamChunks(WorkshopClient client, UUID transferId, Path archive) {
        client.io().execute(() -> {
            try (InputStream input = Files.newInputStream(archive)) {
                byte[] buffer = new byte[MAX_CHUNK_BYTES];
                int index = 0;
                int read;
                long sent = 0;
                while ((read = input.readNBytes(buffer, 0, buffer.length)) > 0) {
                    if (!uploading(transferId)) {
                        // The server has given up on this transfer - a vote whose voting closed, or a package it
                        // refused - and everything still to come would be dropped on arrival. That connection is
                        // carrying the battle too, so stopping is better than filling it for nothing.
                        return;
                    }
                    byte[] chunk = read == buffer.length ? buffer.clone() : java.util.Arrays.copyOf(buffer, read);
                    int chunkIndex = index++;
                    sent += read;
                    Minecraft.getInstance().execute(() ->
                            PacketDistributor.sendToServer(new ServerBeatmapChunkPayload(transferId, chunkIndex, chunk)));
                }
                if (uploading(transferId) && WorkshopCatalog.needsEndChunk(sent, MAX_CHUNK_BYTES)) {
                    // A vote tells the server no size, so it reads the end of the package from the first chunk that
                    // is not full. A package of whole chunks has none of its own, and without this empty one the
                    // transfer never finished: the vote was dropped when the voting closed and the player's one
                    // transfer slot stayed taken.
                    int endIndex = index;
                    Minecraft.getInstance().execute(() ->
                            PacketDistributor.sendToServer(new ServerBeatmapChunkPayload(transferId, endIndex, new byte[0])));
                }
            } catch (IOException exception) {
                MusicBattleMod.LOGGER.warn("Unable to send a Blocky Music package to this server.", exception);
                Minecraft.getInstance().execute(() ->
                        PacketDistributor.sendToServer(new ServerBeatmapTransferCancelPayload(transferId)));
            } finally {
                Minecraft.getInstance().execute(ServerWorkshopClient::dropPending);
            }
        });
    }

    private static void dropPending() {
        Upload upload = pending;
        pending = null;
        Path archive = upload == null ? pendingArchive : upload.archive();
        pendingArchive = null;
        if (archive == null) {
            return;
        }
        WorkshopClient.get().io().execute(() -> {
            try {
                Files.deleteIfExists(archive);
            } catch (IOException ignored) {
                // A leftover in the temporary folder is cleaned on the next repack.
            }
        });
    }

    private static long sizeOf(Path file) {
        try {
            return Files.size(file);
        } catch (IOException ignored) {
            return 0L;
        }
    }

    private static Path uploadsDirectory(WorkshopClient client) throws IOException {
        Path directory = client.catalog().tracksRoot().resolve(".workshop_offers");
        Files.createDirectories(directory);
        return directory;
    }

    // ---------------------------------------------------------------- takedown reports

    /**
     * Tells the server what the Workshop last said about every installed set it might hold (spec 08 A5.1), so a
     * server without egress still learns about a takedown. Advisory and rate-limited on the server.
     */
    public static void reportInstalledStates() {
        WorkshopServerInfoPayload current = info;
        if (current == null || !current.serverBeatmaps()) {
            return;
        }
        WorkshopClient client = WorkshopClient.get();
        for (InstalledCheck.Entry entry : client.installedCheck().entries().values()) {
            if (entry.state() == null || entry.state().isEmpty()) {
                continue;
            }
            PacketDistributor.sendToServer(new WorkshopSetStateReportPayload(entry.setId(), entry.contentSha256(),
                    entry.state()));
        }
    }

    /** What a view of server comments must be able to take. */
    public interface ServerCommentsView {
        void acceptPage(ServerCommentsPagePayload payload);

        void acceptResult(ServerCommentResultPayload payload);
    }
}

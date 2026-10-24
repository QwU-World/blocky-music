package ru.gora.musicbattle.server.workshop;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import ru.gora.musicbattle.MusicBattleMod;
import ru.gora.musicbattle.data.TrackSummary;
import ru.gora.musicbattle.network.ServerBeatmapsPagePayload;
import ru.gora.musicbattle.network.ServerBeatmapsRequestPayload;
import ru.gora.musicbattle.network.ServerBeatmapResultPayload;
import ru.gora.musicbattle.network.ServerCommentResultPayload;
import ru.gora.musicbattle.network.ServerCommentsPagePayload;
import ru.gora.musicbattle.network.ServerCommentsRequestPayload;
import ru.gora.musicbattle.network.ServerTracksPayload;
import ru.gora.musicbattle.network.WorkshopServerInfoPayload;
import ru.gora.musicbattle.network.WorkshopSetStateReportPayload;
import ru.gora.musicbattle.server.MusicBattleServerConfig;

/**
 * The server half of the Workshop screens (spec 06 sections 9.1 and 9.2): lifecycle, the feature announcement
 * every client needs before it may show the Server beatmaps tab or the Server comments button, and the handlers
 * the payload registrar calls. Server thread throughout; the blocking work lives in {@link ServerBeatmapTransfers},
 * {@link ServerBeatmaps} and {@link ServerComments}.
 */
public final class ServerWorkshop {
    /** How many set-state reports one player may send per minute (advisory data, so cheap to rate-limit). */
    private static final int REPORTS_PER_MINUTE = 20;
    private static final Map<UUID, long[]> REPORT_RATE = new HashMap<>();
    private static final long TRACKS_MIN_INTERVAL_MS = 1000L;
    /** When each player last had the server's track list; server thread only. */
    private static final Map<UUID, Long> TRACKS_ASKED = new HashMap<>();

    private ServerWorkshop() {
    }

    // ---------------------------------------------------------------- lifecycle

    /**
     * Registered after {@code MusicBattleTracks.onServerAboutToStart}, so the world folder is already bound.
     * {@link #worldStorage} does not rely on that order: it falls back to the level path if the tracks are not
     * bound yet, so a different listener order can never leave the server without its Workshop state.
     */
    public static void onServerAboutToStart(ServerAboutToStartEvent event) {
        worldStorage(event.getServer());
        ServerBeatmaps.bind(event.getServer());
        ServerComments.bind(event.getServer());
    }

    /** {@code <world>/blocky_music}, computed from the server when the track loader has not bound it yet. */
    private static java.nio.file.Path worldStorage(MinecraftServer server) {
        java.nio.file.Path bound = ru.gora.musicbattle.server.MusicBattleTracks.worldStorageDirectory();
        if (bound != null) {
            return bound;
        }
        // The tracks bind on the same event; if this listener ran first, do it now so nothing depends on the order.
        ru.gora.musicbattle.server.MusicBattleTracks.onServerAboutToStart(new ServerAboutToStartEvent(server));
        return ru.gora.musicbattle.server.MusicBattleTracks.worldStorageDirectory();
    }

    public static void onServerStopping(ServerStoppingEvent event) {
        ServerBeatmapTransfers.shutdown();
        ServerBeatmaps.shutdown();
        ServerComments.shutdown();
        REPORT_RATE.clear();
        TRACKS_ASKED.clear();
    }

    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        WorkshopPermissions.get(player.server).touch(player.getUUID(), player.getGameProfile().getName());
        sendInfo(player);
        if (MusicBattleServerConfig.serverCommentsEnabled() && player.hasPermissions(2)) {
            player.sendSystemMessage(Component.translatable("music_battle.l10n.server.comments_operator_notice",
                            MusicBattleServerConfig.serverCommentsRetentionDays())
                    .withStyle(ChatFormatting.GRAY));
        }
    }

    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        ServerBeatmapTransfers.onPlayerLeft(event.getEntity().getUUID());
        REPORT_RATE.remove(event.getEntity().getUUID());
        TRACKS_ASKED.remove(event.getEntity().getUUID());
    }

    /** Tells one client what this server offers. Sent on login and after any config or permission change. */
    public static void sendInfo(ServerPlayer player) {
        PacketDistributor.sendToPlayer(player, new WorkshopServerInfoPayload(
                ServerBeatmaps.bound(),
                ServerComments.available(),
                MusicBattleServerConfig.allowPlayerMaps(),
                ServerComments.onlineMode(),
                WorkshopPermissions.canManageServerBeatmaps(player),
                WorkshopPermissions.canModerateComments(player)));
    }

    public static void sendInfoToAll(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            sendInfo(player);
        }
    }

    // ---------------------------------------------------------------- server tracks

    /**
     * The Server tab outside a battle: every track this server would offer a vote, built the way the vote builds its
     * rows. A request within a second of the last one is dropped, because each answer can carry hundreds of rows and
     * the tab asks again on every search.
     */
    public static void requestTracks(ServerPlayer player) {
        long now = System.currentTimeMillis();
        Long last = TRACKS_ASKED.get(player.getUUID());
        if (last != null && now - last < TRACKS_MIN_INTERVAL_MS) {
            return;
        }
        TRACKS_ASKED.put(player.getUUID(), now);
        List<ru.gora.musicbattle.server.MusicBattleTracks.Track> tracks =
                ru.gora.musicbattle.server.MusicBattleTracks.availableTracks();
        List<TrackSummary> rows = new ArrayList<>(Math.min(tracks.size(), ServerTracksPayload.MAX_TRACKS));
        int offered = 0;
        for (ru.gora.musicbattle.server.MusicBattleTracks.Track track : tracks) {
            if (ServerBeatmaps.hiddenFromVotes(track)) {
                continue;
            }
            offered++;
            if (rows.size() < ServerTracksPayload.MAX_TRACKS) {
                rows.add(ServerBeatmaps.summary(track));
            }
        }
        PacketDistributor.sendToPlayer(player, new ServerTracksPayload(rows, offered > rows.size()));
    }

    // ---------------------------------------------------------------- server beatmaps

    /** One page of the Server beatmaps tab. The cursor is the set id to continue after. */
    public static void requestPage(ServerPlayer player, ServerBeatmapsRequestPayload payload) {
        List<ServerBeatmaps.Entry> entries = ServerBeatmaps.all();
        String query = payload.query() == null ? "" : payload.query().strip().toLowerCase(Locale.ROOT);
        long after = parseCursor(payload.cursor());
        boolean started = after <= 0;
        long now = System.currentTimeMillis();
        List<ServerBeatmapsPagePayload.Item> items = new ArrayList<>();
        String nextCursor = "";
        for (ServerBeatmaps.Entry entry : entries) {
            if (!started) {
                started = entry.setId() == after;
                continue;
            }
            if (!query.isEmpty() && !matches(entry, query)) {
                continue;
            }
            if (items.size() >= ServerBeatmapsPagePayload.MAX_ITEMS) {
                nextCursor = String.valueOf(items.get(items.size() - 1).setId());
                break;
            }
            List<ServerBeatmapsPagePayload.Chart> charts = new ArrayList<>();
            for (ServerBeatmaps.Chart chart : entry.charts()) {
                charts.add(new ServerBeatmapsPagePayload.Chart(chart.key(), chart.name(), chart.stars()));
            }
            items.add(new ServerBeatmapsPagePayload.Item(entry.setId(), entry.revision(), entry.contentSha256(),
                    entry.title(), entry.artist(), entry.mapper(), List.copyOf(charts), entry.explicit(),
                    entry.hasCover(), entry.addedByName(), entry.addedAt(), entry.withdrawn(), entry.unverified(now)));
        }
        PacketDistributor.sendToPlayer(player, new ServerBeatmapsPagePayload(List.copyOf(items), nextCursor,
                WorkshopPermissions.canManageServerBeatmaps(player)));
    }

    private static boolean matches(ServerBeatmaps.Entry entry, String query) {
        return entry.title().toLowerCase(Locale.ROOT).contains(query)
                || entry.artist().toLowerCase(Locale.ROOT).contains(query)
                || entry.mapper().toLowerCase(Locale.ROOT).contains(query);
    }

    private static long parseCursor(String cursor) {
        try {
            return cursor == null || cursor.isBlank() ? 0L : Long.parseLong(cursor.trim());
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    /** "Remove from server" from a card. */
    public static void remove(ServerPlayer player, long setId) {
        if (!WorkshopPermissions.canManageServerBeatmaps(player)) {
            PacketDistributor.sendToPlayer(player, new ServerBeatmapResultPayload(false,
                    Component.translatable("music_battle.l10n.server.server_beatmap_no_permission"), setId));
            return;
        }
        try {
            Optional<String> refusal = ServerBeatmaps.remove(setId);
            PacketDistributor.sendToPlayer(player, new ServerBeatmapResultPayload(refusal.isEmpty(),
                    refusal.map(reason -> Component.translatable("music_battle.l10n.server.server_beatmap_remove_" + reason))
                            .orElseGet(() -> Component.translatable("music_battle.l10n.server.server_beatmap_removed")),
                    setId));
        } catch (java.io.IOException exception) {
            MusicBattleMod.LOGGER.warn("Unable to remove the Blocky Music server beatmap {}.", setId, exception);
            PacketDistributor.sendToPlayer(player, new ServerBeatmapResultPayload(false,
                    Component.translatable("music_battle.l10n.server.server_beatmap_remove_failed"), setId));
        }
    }

    /** A client's installed-check answer about a set this server holds (spec 08 A5.1). Advisory, rate-limited. */
    public static void acceptStateReport(ServerPlayer player, WorkshopSetStateReportPayload payload) {
        long now = System.currentTimeMillis();
        long[] window = REPORT_RATE.computeIfAbsent(player.getUUID(), ignored -> new long[]{now, 0});
        if (now - window[0] > 60_000L) {
            window[0] = now;
            window[1] = 0;
        }
        if (++window[1] > REPORTS_PER_MINUTE) {
            return;
        }
        String state = payload.state() == null ? "" : payload.state();
        boolean withdrawn = "withdrawn".equals(state) || "audio_removed".equals(state);
        boolean available = "current".equals(state) || "update_available".equals(state);
        if (!withdrawn && !available) {
            return;
        }
        boolean changed = withdrawn
                ? ServerBeatmaps.markWithdrawn(payload.setId(), payload.contentSha256(), true)
                : ServerBeatmaps.markVerified(payload.setId(), payload.contentSha256());
        if (available) {
            // The player has this Workshop set installed and has just told the server it checks out.
            ru.gora.musicbattle.server.MusicBattleAdvancements.grant(player, "fresh_from_the_workshop");
        }
        if (withdrawn && changed) {
            MusicBattleMod.LOGGER.warn("Blocky Music set {} was reported as {} by {}; it is hidden from votes until "
                            + "an operator runs /musicbattle workshop remove or keep.",
                    payload.setId(), state, player.getGameProfile().getName());
            announceToOperators(player.server, Component.translatable("music_battle.l10n.server.server_beatmap_withdrawn",
                    payload.setId()).withStyle(ChatFormatting.RED));
        }
    }

    // ---------------------------------------------------------------- server comments

    public static void requestComments(ServerPlayer player, ServerCommentsRequestPayload payload) {
        ServerComments.Page page = ServerComments.page(player, payload.key(), payload.beforeId(), payload.limit());
        List<ServerCommentsPagePayload.Row> rows = new ArrayList<>(page.comments().size());
        for (ServerComments.Comment comment : page.comments()) {
            rows.add(new ServerCommentsPagePayload.Row(comment.id(), comment.authorUuid(), comment.authorName(),
                    comment.textures(), comment.signature(), comment.createdAt(), comment.deleted(), comment.text(),
                    comment.authorUuid().equals(player.getUUID())));
        }
        PacketDistributor.sendToPlayer(player, new ServerCommentsPagePayload(page.key(), page.total(), List.copyOf(rows),
                page.nextBeforeId(), page.canPost(), page.canModerate(), page.onlineMode(), page.note()));
    }

    public static void postComment(ServerPlayer player, String key, String text) {
        Optional<String> error = ServerComments.post(player, key, text);
        sendCommentResult(player, key, error);
    }

    public static void deleteComment(ServerPlayer player, String key, long commentId) {
        Optional<String> error = ServerComments.delete(player, key, commentId);
        sendCommentResult(player, key, error);
    }

    public static void reportComment(ServerPlayer player, String key, long commentId, String reason) {
        boolean stored = ServerComments.report(player, key, commentId, reason);
        if (stored) {
            announceToOperators(player.server, Component.translatable("music_battle.l10n.server.comment_reported",
                    player.getGameProfile().getName(), key, commentId).withStyle(ChatFormatting.YELLOW));
        }
        sendCommentResult(player, key, stored ? Optional.empty() : Optional.of(ServerComments.ERROR_FAILED));
    }

    private static void sendCommentResult(ServerPlayer player, String key, Optional<String> error) {
        PacketDistributor.sendToPlayer(player, new ServerCommentResultPayload(error.isEmpty(),
                error.map(code -> Component.translatable("music_battle.workshop.error." + code))
                        .orElseGet(() -> Component.translatable("music_battle.l10n.server.comment_ok")),
                key));
    }

    private static void announceToOperators(MinecraftServer server, Component message) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.hasPermissions(2)) {
                player.sendSystemMessage(message);
            }
        }
        server.sendSystemMessage(message);
    }
}

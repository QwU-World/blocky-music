package ru.gora.musicbattle.command;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import ru.gora.musicbattle.server.BattleManager;
import ru.gora.musicbattle.server.MusicBattleServerConfig;
import ru.gora.musicbattle.server.MusicBattleTracks;
import ru.gora.musicbattle.server.TrackPublicationManager;
import ru.gora.musicbattle.server.workshop.ServerBeatmaps;
import ru.gora.musicbattle.server.workshop.ServerComments;
import ru.gora.musicbattle.server.workshop.ServerWorkshop;
import ru.gora.musicbattle.server.workshop.WorkshopPermissions;

/**
 * {@code /musicbattle}. Wave M3 added the Workshop side (spec 06 section 9.1): {@code workshop},
 * {@code access} (which replaces {@code publishaccess}, kept as an alias), {@code published} for the admin removal
 * gap and {@code comments} for the server's own comments.
 *
 * <p>Who may run what: {@code access}, {@code published remove} and every {@code comments} subcommand but
 * {@code delete} need {@code source.hasPermission(2)} and nothing else — never the allow-list, never a permission
 * node, so an allow-listed player can never widen their own rights. {@code workshop} and
 * {@code comments delete} accept the matching PermissionAPI node as well.
 */
public final class MusicBattleCommands {
    private static final int PAGE_SIZE = 10;

    private MusicBattleCommands() {
    }

    public static void register(RegisterCommandsEvent event) {
        register(event.getDispatcher());
    }

    private static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("musicbattle")
                .then(Commands.literal("accept")
                        .then(Commands.argument("challenger", StringArgumentType.word())
                                .executes(context -> accept(
                                        context.getSource(),
                                        StringArgumentType.getString(context, "challenger")
                                ))))
                .then(Commands.literal("decline")
                        .executes(context -> BattleManager.decline(context.getSource())))
                .then(Commands.literal("tracks")
                        .executes(context -> tracks(context.getSource())))
                .then(Commands.literal("reload")
                        .requires(source -> source.hasPermission(2))
                        .executes(context -> reload(context.getSource())))
                .then(workshop())
                .then(access("access"))
                // The pre-M3 name still works, so nobody's server scripts break.
                .then(access("publishaccess"))
                .then(published())
                .then(comments()));
    }

    // ---------------------------------------------------------------- workshop

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> workshop() {
        return Commands.literal("workshop")
                .requires(MusicBattleCommands::mayManageBeatmaps)
                .then(Commands.literal("list")
                        .executes(context -> listBeatmaps(context.getSource(), 1))
                        .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                .executes(context -> listBeatmaps(context.getSource(), IntegerArgumentType.getInteger(context, "page")))))
                .then(Commands.literal("info")
                        .then(Commands.argument("setId", LongArgumentType.longArg(1))
                                .executes(context -> beatmapInfo(context.getSource(), LongArgumentType.getLong(context, "setId")))))
                .then(Commands.literal("remove")
                        .then(Commands.argument("setId", LongArgumentType.longArg(1))
                                .executes(context -> removeBeatmap(context.getSource(), LongArgumentType.getLong(context, "setId")))))
                .then(Commands.literal("keep")
                        .then(Commands.argument("setId", LongArgumentType.longArg(1))
                                .executes(context -> keepBeatmap(context.getSource(), LongArgumentType.getLong(context, "setId")))))
                .then(Commands.literal("add")
                        .then(Commands.argument("setId", LongArgumentType.longArg(1))
                                .executes(context -> addBeatmap(context.getSource(), LongArgumentType.getLong(context, "setId")))));
    }

    private static boolean mayManageBeatmaps(CommandSourceStack source) {
        if (source.hasPermission(2)) {
            return true;
        }
        ServerPlayer player = source.getPlayer();
        return player != null && WorkshopPermissions.canManageServerBeatmaps(player);
    }

    private static int listBeatmaps(CommandSourceStack source, int page) {
        List<ServerBeatmaps.Entry> entries = ServerBeatmaps.all();
        if (entries.isEmpty()) {
            source.sendSuccess(() -> Component.translatable("music_battle.l10n.server.server_beatmaps_empty"), false);
            return 0;
        }
        int pages = Math.max(1, (entries.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int current = Math.min(page, pages);
        long now = System.currentTimeMillis();
        source.sendSuccess(() -> Component.translatable("music_battle.l10n.server.server_beatmaps_header",
                entries.size(), current, pages), false);
        for (int i = (current - 1) * PAGE_SIZE; i < Math.min(entries.size(), current * PAGE_SIZE); i++) {
            ServerBeatmaps.Entry entry = entries.get(i);
            String flags = entry.withdrawn() ? " [withdrawn]" : entry.unverified(now) ? " [unverified]" : "";
            source.sendSuccess(() -> Component.literal("- " + entry.setId() + " | " + entry.title() + " | "
                    + entry.artist() + " | " + entry.charts().size() + " ch" + flags), false);
        }
        return entries.size();
    }

    private static int beatmapInfo(CommandSourceStack source, long setId) {
        Optional<ServerBeatmaps.Entry> entry = ServerBeatmaps.bySetId(setId);
        if (entry.isEmpty()) {
            source.sendFailure(Component.translatable("music_battle.l10n.server.server_beatmap_missing", setId));
            return 0;
        }
        ServerBeatmaps.Entry found = entry.get();
        long now = System.currentTimeMillis();
        source.sendSuccess(() -> Component.literal(found.title() + " — " + found.artist()), false);
        source.sendSuccess(() -> Component.literal("set " + found.setId() + " r" + found.revision()
                + " | " + found.contentSha256().substring(0, Math.min(12, found.contentSha256().length()))
                + " | " + found.sizeBytes() + " bytes"), false);
        source.sendSuccess(() -> Component.literal("mapper " + found.mapper()
                + " | added by " + (found.addedByName().isEmpty() ? "?" : found.addedByName())), false);
        for (ServerBeatmaps.Chart chart : found.charts()) {
            source.sendSuccess(() -> Component.literal("  - " + chart.key() + " (" + chart.name() + ")"), false);
        }
        if (found.withdrawn()) {
            source.sendSuccess(() -> Component.translatable("music_battle.l10n.server.server_beatmap_withdrawn", setId)
                    .withStyle(ChatFormatting.RED), false);
        } else if (found.unverified(now)) {
            source.sendSuccess(() -> Component.translatable("music_battle.l10n.server.server_beatmap_unverified", setId)
                    .withStyle(ChatFormatting.YELLOW), false);
        }
        return 1;
    }

    private static int removeBeatmap(CommandSourceStack source, long setId) {
        try {
            Optional<String> refusal = ServerBeatmaps.remove(setId);
            if (refusal.isPresent()) {
                source.sendFailure(Component.translatable("music_battle.l10n.server.server_beatmap_remove_" + refusal.get()));
                return 0;
            }
            source.sendSuccess(() -> Component.translatable("music_battle.l10n.server.server_beatmap_removed"), true);
            ServerWorkshop.sendInfoToAll(source.getServer());
            return 1;
        } catch (IOException exception) {
            source.sendFailure(Component.translatable("music_battle.l10n.server.server_beatmap_remove_failed"));
            return 0;
        }
    }

    /** Clears a withdrawn marking a client reported, for an operator who decided to keep the set. */
    private static int keepBeatmap(CommandSourceStack source, long setId) {
        Optional<ServerBeatmaps.Entry> entry = ServerBeatmaps.bySetId(setId);
        if (entry.isEmpty()) {
            source.sendFailure(Component.translatable("music_battle.l10n.server.server_beatmap_missing", setId));
            return 0;
        }
        ServerBeatmaps.markWithdrawn(setId, entry.get().contentSha256(), false);
        source.sendSuccess(() -> Component.translatable("music_battle.l10n.server.server_beatmap_kept", setId), true);
        return 1;
    }

    /** Only a server with {@code workshop.allowServerDownloads} may fetch a set itself (spec 06 section 9.1). */
    private static int addBeatmap(CommandSourceStack source, long setId) {
        if (!MusicBattleServerConfig.allowServerDownloads()) {
            source.sendFailure(Component.translatable("music_battle.l10n.server.server_downloads_disabled"));
            return 0;
        }
        source.sendFailure(Component.translatable("music_battle.l10n.server.server_download_not_available", setId));
        return 0;
    }

    // ---------------------------------------------------------------- access

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> access(String name) {
        return Commands.literal(name)
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("grant")
                        .then(Commands.argument("player", GameProfileArgument.gameProfile())
                                .executes(context -> setAccess(context, WorkshopPermissions.Right.PUBLISH, true))
                                .then(Commands.argument("right", StringArgumentType.word())
                                        .suggests((context, builder) -> suggestRights(builder))
                                        .executes(context -> setAccess(context, right(context), true)))))
                .then(Commands.literal("revoke")
                        .then(Commands.argument("player", GameProfileArgument.gameProfile())
                                .executes(context -> setAccess(context, WorkshopPermissions.Right.PUBLISH, false))
                                .then(Commands.argument("right", StringArgumentType.word())
                                        .suggests((context, builder) -> suggestRights(builder))
                                        .executes(context -> setAccess(context, right(context), false)))))
                .then(Commands.literal("check")
                        .then(Commands.argument("player", GameProfileArgument.gameProfile())
                                .executes(MusicBattleCommands::checkAccess)))
                .then(Commands.literal("list")
                        .executes(context -> listAccess(context.getSource())));
    }

    private static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggestRights(
            com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
        for (WorkshopPermissions.Right value : WorkshopPermissions.Right.values()) {
            builder.suggest(value.id());
        }
        return builder.buildFuture();
    }

    private static WorkshopPermissions.Right right(CommandContext<CommandSourceStack> context) {
        return WorkshopPermissions.Right.byId(StringArgumentType.getString(context, "right"))
                .orElse(WorkshopPermissions.Right.PUBLISH);
    }

    private static int setAccess(CommandContext<CommandSourceStack> context, WorkshopPermissions.Right right, boolean grant)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        Collection<GameProfile> profiles = GameProfileArgument.getGameProfiles(context, "player");
        WorkshopPermissions permissions = WorkshopPermissions.get(source.getServer());
        int changed = 0;
        for (GameProfile profile : profiles) {
            boolean applied = grant
                    ? permissions.grant(profile.getId(), profile.getName(), right, source.getTextName(), System.currentTimeMillis())
                    : permissions.revoke(profile.getId(), right);
            if (applied) {
                changed++;
            }
            String key = applied
                    ? (grant ? "music_battle.l10n.server.access_granted" : "music_battle.l10n.server.access_revoked")
                    : (grant ? "music_battle.l10n.server.access_already_granted" : "music_battle.l10n.server.access_already_revoked");
            source.sendSuccess(() -> Component.translatable(key, profile.getName(), right.id()), true);
            WorkshopPermissions.refreshCommands(source.getServer(), profile.getId());
            ServerPlayer online = source.getServer().getPlayerList().getPlayer(profile.getId());
            if (online != null) {
                ServerWorkshop.sendInfo(online);
                if (right == WorkshopPermissions.Right.PUBLISH) {
                    TrackPublicationManager.sendPublishedTracks(online);
                }
            }
        }
        return changed;
    }

    private static int checkAccess(CommandContext<CommandSourceStack> context)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        MinecraftServer server = source.getServer();
        WorkshopPermissions permissions = WorkshopPermissions.get(server);
        int found = 0;
        for (GameProfile profile : GameProfileArgument.getGameProfiles(context, "player")) {
            Optional<WorkshopPermissions.Entry> entry = permissions.entry(profile.getId());
            boolean operator = server.getProfilePermissions(profile) >= 2 || server.isSingleplayerOwner(profile);
            StringBuilder rights = new StringBuilder(operator ? "operator" : "");
            entry.ifPresent(value -> {
                for (WorkshopPermissions.Right right : value.rights()) {
                    rights.append(rights.isEmpty() ? "" : ", ").append(right.id());
                }
            });
            String shown = rights.isEmpty() ? "-" : rights.toString();
            source.sendSuccess(() -> Component.translatable("music_battle.l10n.server.access_check",
                    profile.getName(), shown), false);
            found++;
        }
        return found;
    }

    private static int listAccess(CommandSourceStack source) {
        List<WorkshopPermissions.Entry> entries = WorkshopPermissions.get(source.getServer()).all();
        if (entries.isEmpty()) {
            source.sendSuccess(() -> Component.translatable("music_battle.l10n.server.access_list_empty"), false);
            return 0;
        }
        source.sendSuccess(() -> Component.translatable("music_battle.l10n.server.access_list_header", entries.size()), false);
        for (WorkshopPermissions.Entry entry : entries) {
            source.sendSuccess(() -> WorkshopPermissions.describe(entry), false);
        }
        return entries.size();
    }

    // ---------------------------------------------------------------- published (legacy user tracks)

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> published() {
        return Commands.literal("published")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("list")
                        .executes(context -> listPublished(context.getSource(), null))
                        .then(Commands.argument("player", GameProfileArgument.gameProfile())
                                .executes(context -> listPublished(context.getSource(),
                                        GameProfileArgument.getGameProfiles(context, "player").iterator().next()))))
                .then(Commands.literal("remove")
                        .then(Commands.argument("id", StringArgumentType.word())
                                .executes(context -> removePublished(context.getSource(),
                                        StringArgumentType.getString(context, "id")))));
    }

    private static int listPublished(CommandSourceStack source, GameProfile owner) {
        String prefix = owner == null ? "" : "user_" + owner.getId().toString().replace("-", "").substring(0, 12) + "_";
        List<MusicBattleTracks.Track> tracks = MusicBattleTracks.userServerTracks().stream()
                .filter(track -> prefix.isEmpty() || track.id().startsWith(prefix))
                .toList();
        if (tracks.isEmpty()) {
            source.sendSuccess(() -> Component.translatable("music_battle.l10n.server.published_empty"), false);
            return 0;
        }
        for (MusicBattleTracks.Track track : tracks) {
            source.sendSuccess(() -> Component.literal("- " + track.id() + " | " + track.title() + " | "
                    + (track.publishedBy().isEmpty() ? "?" : track.publishedBy())), false);
        }
        return tracks.size();
    }

    /** The admin removal gap: an operator can delete a player's published track without being its owner. */
    private static int removePublished(CommandSourceStack source, String trackId) {
        String id = trackId.toLowerCase(Locale.ROOT);
        Optional<MusicBattleTracks.Track> track = MusicBattleTracks.userServerTracks().stream()
                .filter(candidate -> candidate.id().equals(id)).findFirst();
        if (track.isEmpty()) {
            source.sendFailure(Component.translatable("music_battle.l10n.server.published_missing", id));
            return 0;
        }
        Path root = MusicBattleTracks.userServerTracksDirectory();
        Path folder = track.get().sourceDirectory().equals(root)
                ? root.resolve(id) : track.get().sourceDirectory();
        try {
            ru.gora.musicbattle.workshop.BeatmapPackage.deleteTree(folder, root);
            java.nio.file.Files.deleteIfExists(root.resolve(id + ".json"));
            MusicBattleTracks.reloadServerCatalog();
            source.sendSuccess(() -> Component.translatable("music_battle.l10n.server.published_removed", id), true);
            return 1;
        } catch (IOException exception) {
            source.sendFailure(Component.translatable("music_battle.l10n.server.published_remove_failed", id));
            return 0;
        }
    }

    // ---------------------------------------------------------------- server comments

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> comments() {
        return Commands.literal("comments")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("delete")
                        .then(Commands.argument("key", StringArgumentType.word())
                                .then(Commands.argument("id", LongArgumentType.longArg(1))
                                        .executes(context -> deleteComment(context.getSource(),
                                                StringArgumentType.getString(context, "key"),
                                                LongArgumentType.getLong(context, "id"))))))
                .then(Commands.literal("purge")
                        .then(Commands.argument("player", GameProfileArgument.gameProfile())
                                .executes(context -> purgeComments(context, GameProfileArgument.getGameProfiles(context, "player")))))
                .then(Commands.literal("mute")
                        .then(Commands.argument("player", GameProfileArgument.gameProfile())
                                .then(Commands.argument("minutes", IntegerArgumentType.integer(0))
                                        .executes(context -> mute(context, IntegerArgumentType.getInteger(context, "minutes"))))))
                .then(Commands.literal("unmute")
                        .then(Commands.argument("player", GameProfileArgument.gameProfile())
                                .executes(MusicBattleCommands::unmute)))
                .then(Commands.literal("export")
                        .then(Commands.argument("player", GameProfileArgument.gameProfile())
                                .executes(MusicBattleCommands::exportComments)))
                .then(Commands.literal("enable")
                        .executes(context -> setComments(context.getSource(), true)))
                .then(Commands.literal("disable")
                        .executes(context -> setComments(context.getSource(), false)));
    }

    private static int deleteComment(CommandSourceStack source, String key, long commentId) {
        // The source, not its player: the console and RCON have no player behind them, and passing the null player
        // lost the moderator flag, so every console delete was refused as "not the author".
        Optional<String> error = ServerComments.delete(source, key, commentId);
        if (error.isPresent()) {
            source.sendFailure(Component.translatable("music_battle.workshop.error." + error.get()));
            return 0;
        }
        source.sendSuccess(() -> Component.translatable("music_battle.l10n.server.comment_deleted", commentId), true);
        return 1;
    }

    private static int purgeComments(CommandContext<CommandSourceStack> context, Collection<GameProfile> profiles) {
        CommandSourceStack source = context.getSource();
        int removed = 0;
        for (GameProfile profile : profiles) {
            removed += ServerComments.purge(profile.getId(), source.getTextName());
        }
        int total = removed;
        source.sendSuccess(() -> Component.translatable("music_battle.l10n.server.comments_purged", total), true);
        return removed;
    }

    private static int mute(CommandContext<CommandSourceStack> context, int minutes)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        long until = minutes <= 0 ? Long.MAX_VALUE : System.currentTimeMillis() + minutes * 60_000L;
        for (GameProfile profile : GameProfileArgument.getGameProfiles(context, "player")) {
            ServerComments.mute(profile.getId(), profile.getName(), until, source.getTextName());
            source.sendSuccess(() -> Component.translatable("music_battle.l10n.server.comments_muted",
                    profile.getName(), minutes), true);
        }
        return 1;
    }

    private static int unmute(CommandContext<CommandSourceStack> context)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        int changed = 0;
        for (GameProfile profile : GameProfileArgument.getGameProfiles(context, "player")) {
            if (ServerComments.unmute(profile.getId())) {
                changed++;
            }
            source.sendSuccess(() -> Component.translatable("music_battle.l10n.server.comments_unmuted",
                    profile.getName()), true);
        }
        return changed;
    }

    private static int exportComments(CommandContext<CommandSourceStack> context)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        int exported = 0;
        for (GameProfile profile : GameProfileArgument.getGameProfiles(context, "player")) {
            Optional<Path> file = ServerComments.export(profile.getId());
            if (file.isEmpty()) {
                source.sendFailure(Component.translatable("music_battle.l10n.server.comments_export_failed",
                        profile.getName()));
                continue;
            }
            String shown = MusicBattleTracks.displayPath(file.get());
            source.sendSuccess(() -> Component.translatable("music_battle.l10n.server.comments_exported",
                    profile.getName(), shown), true);
            exported++;
        }
        return exported;
    }

    private static int setComments(CommandSourceStack source, boolean enabled) {
        MusicBattleServerConfig.setServerCommentsEnabled(enabled);
        source.sendSuccess(() -> Component.translatable(enabled
                ? "music_battle.l10n.server.comments_enabled" : "music_battle.l10n.server.comments_disabled"), true);
        ServerWorkshop.sendInfoToAll(source.getServer());
        return 1;
    }

    // ---------------------------------------------------------------- existing commands

    private static int accept(CommandSourceStack source, String challengerId) {
        try {
            return BattleManager.accept(source, UUID.fromString(challengerId));
        } catch (IllegalArgumentException exception) {
            source.sendFailure(Component.translatable("music_battle.l10n.server.challenge_uuid_invalid"));
            return 0;
        }
    }

    private static int tracks(CommandSourceStack source) {
        var tracks = MusicBattleTracks.availableTracks();
        source.sendSuccess(() -> Component.translatable("music_battle.l10n.server.track_directories",
                MusicBattleTracks.displayPath(MusicBattleTracks.serverTracksDirectory()),
                MusicBattleTracks.displayPath(MusicBattleTracks.userServerTracksDirectory())), false);
        for (var track : tracks) {
            source.sendSuccess(() -> Component.literal("- " + track.id() + " | " + track.title() + " | BPM " + track.bpm()), false);
        }
        return tracks.isEmpty() ? 0 : 1;
    }

    private static int reload(CommandSourceStack source) {
        int count = MusicBattleTracks.reloadServerCatalog().size();
        source.sendSuccess(() -> Component.translatable("music_battle.l10n.server.tracks_reloaded", count), true);
        return 1;
    }
}

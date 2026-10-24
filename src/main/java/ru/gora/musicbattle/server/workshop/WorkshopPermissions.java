package ru.gora.musicbattle.server.workshop;

import com.mojang.authlib.GameProfile;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.neoforge.server.permission.PermissionAPI;
import net.neoforged.neoforge.server.permission.events.PermissionGatherEvent;
import net.neoforged.neoforge.server.permission.nodes.PermissionNode;
import net.neoforged.neoforge.server.permission.nodes.PermissionTypes;
import ru.gora.musicbattle.MusicBattleMod;

/**
 * The server's allow-list, replacing {@code TrackPublishPermissions} (spec 06 section 9.1).
 *
 * <p>One entry per player: {@code {uuid, lastKnownName, grantedBy, grantedAt, rights}} with rights out of
 * {@link Right}. The old {@code blocky_music_publish_permissions} SavedData (a bare list of UUIDs) is migrated on
 * first load into entries holding only {@link Right#PUBLISH}, so nobody loses access on update.
 *
 * <p>Three NeoForge PermissionAPI nodes are registered in {@link #gatherNodes}; their default resolver is
 * "operator level 2, or the singleplayer owner, or on this list", which is what {@link #canPublish},
 * {@link #canManageServerBeatmaps} and {@link #canModerateComments} ask. Granting and revoking is a different
 * matter: those commands need {@code source.hasPermission(2)} and nothing else, so an allow-listed player can
 * never widen their own rights.
 */
public final class WorkshopPermissions extends SavedData {
    /** The new store. The legacy name is read once for migration and then left alone as a backup. */
    public static final String DATA_NAME = "blocky_music_workshop_permissions";
    public static final String LEGACY_DATA_NAME = "blocky_music_publish_permissions";

    private static final String ENTRIES_TAG = "entries";
    private static final String LEGACY_PLAYERS_TAG = "players";
    private static final int MAX_NAME = 32;

    /** The rights an operator can hand out. The id is what commands and the save file use. */
    public enum Right {
        /** Publish a legacy user track on this server (the old allow-list). */
        PUBLISH("publish"),
        /** Add and remove server beatmaps ({@code music_battle.server_beatmaps.manage}). */
        SERVER_BEATMAPS("server_beatmaps"),
        /** Delete and purge other players' server comments ({@code music_battle.server_comments.moderate}). */
        SERVER_COMMENTS_MODERATE("server_comments_moderate");

        private final String id;

        Right(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }

        public static Optional<Right> byId(String value) {
            if (value == null) {
                return Optional.empty();
            }
            String lower = value.toLowerCase(Locale.ROOT);
            for (Right right : values()) {
                if (right.id.equals(lower)) {
                    return Optional.of(right);
                }
            }
            return Optional.empty();
        }
    }

    /** One allow-list row. {@code grantedAt} is epoch milliseconds, 0 when unknown (migrated rows). */
    public record Entry(UUID uuid, String lastKnownName, String grantedBy, long grantedAt, List<Right> rights) {
        public Entry {
            rights = List.copyOf(rights);
        }

        public boolean has(Right right) {
            return rights.contains(right);
        }
    }

    public static final PermissionNode<Boolean> PUBLISH_NODE = node("publish", Right.PUBLISH);
    public static final PermissionNode<Boolean> SERVER_BEATMAPS_NODE = node("server_beatmaps.manage", Right.SERVER_BEATMAPS);
    public static final PermissionNode<Boolean> SERVER_COMMENTS_NODE = node("server_comments.moderate", Right.SERVER_COMMENTS_MODERATE);

    private final Map<UUID, Entry> entries = new LinkedHashMap<>();

    public static WorkshopPermissions get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(() -> migrated(server), WorkshopPermissions::load), DATA_NAME);
    }

    // ---------------------------------------------------------------- permission nodes

    private static PermissionNode<Boolean> node(String name, Right right) {
        return new PermissionNode<>(MusicBattleMod.MOD_ID, name, PermissionTypes.BOOLEAN,
                (player, uuid, context) -> defaultAllows(player, uuid, right));
    }

    /** Registered on the game bus; nodes must exist before any {@link PermissionAPI} query. */
    public static void gatherNodes(PermissionGatherEvent.Nodes event) {
        event.addNodes(PUBLISH_NODE, SERVER_BEATMAPS_NODE, SERVER_COMMENTS_NODE);
    }

    /**
     * Operator level 2, the singleplayer owner, or an allow-list entry. Works for offline players too: the profile
     * cache gives the name the operator level is stored against.
     */
    private static boolean defaultAllows(ServerPlayer player, UUID uuid, Right right) {
        MinecraftServer server = player == null ? null : player.server;
        if (server == null) {
            server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
        }
        if (server == null || uuid == null) {
            return false;
        }
        GameProfile profile = profileOf(server, uuid);
        if (profile != null && (server.getProfilePermissions(profile) >= 2 || server.isSingleplayerOwner(profile))) {
            return true;
        }
        return get(server).has(uuid, right);
    }

    private static GameProfile profileOf(MinecraftServer server, UUID uuid) {
        ServerPlayer online = server.getPlayerList().getPlayer(uuid);
        if (online != null) {
            return online.getGameProfile();
        }
        return server.getProfileCache() == null ? new GameProfile(uuid, "")
                : server.getProfileCache().get(uuid).orElse(new GameProfile(uuid, ""));
    }

    private static boolean nodeAllows(ServerPlayer player, PermissionNode<Boolean> node, Right right) {
        if (player == null) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(PermissionAPI.getPermission(player, node));
        } catch (RuntimeException notRegistered) {
            // A handler that does not know the node (an early call, or a stripped server) falls back to the rule
            // the default resolver encodes, so permissions never fail open or hard.
            return player.hasPermissions(2) || get(player.server).has(player.getUUID(), right);
        }
    }

    public static boolean canPublish(ServerPlayer player) {
        return nodeAllows(player, PUBLISH_NODE, Right.PUBLISH);
    }

    public static boolean canManageServerBeatmaps(ServerPlayer player) {
        return nodeAllows(player, SERVER_BEATMAPS_NODE, Right.SERVER_BEATMAPS);
    }

    public static boolean canModerateComments(ServerPlayer player) {
        return nodeAllows(player, SERVER_COMMENTS_NODE, Right.SERVER_COMMENTS_MODERATE);
    }

    // ---------------------------------------------------------------- store

    public boolean has(UUID uuid, Right right) {
        Entry entry = entries.get(uuid);
        return entry != null && entry.has(right);
    }

    public Optional<Entry> entry(UUID uuid) {
        return Optional.ofNullable(entries.get(uuid));
    }

    public List<Entry> all() {
        return List.copyOf(entries.values());
    }

    /** Adds {@code right} to that player. Returns false when they already had it. */
    public boolean grant(UUID uuid, String lastKnownName, Right right, String grantedBy, long nowMillis) {
        Entry existing = entries.get(uuid);
        List<Right> rights = new ArrayList<>(existing == null ? List.of() : existing.rights());
        if (rights.contains(right)) {
            if (existing != null && !limit(lastKnownName).equals(existing.lastKnownName())) {
                entries.put(uuid, new Entry(uuid, limit(lastKnownName), existing.grantedBy(), existing.grantedAt(), rights));
                setDirty();
            }
            return false;
        }
        rights.add(right);
        entries.put(uuid, new Entry(uuid, limit(lastKnownName), limit(grantedBy),
                existing == null || existing.grantedAt() == 0 ? nowMillis : existing.grantedAt(), rights));
        setDirty();
        return true;
    }

    /** Removes {@code right}; the entry disappears once it holds no rights. Returns false when it was not held. */
    public boolean revoke(UUID uuid, Right right) {
        Entry existing = entries.get(uuid);
        if (existing == null || !existing.has(right)) {
            return false;
        }
        List<Right> rights = new ArrayList<>(existing.rights());
        rights.remove(right);
        if (rights.isEmpty()) {
            entries.remove(uuid);
        } else {
            entries.put(uuid, new Entry(uuid, existing.lastKnownName(), existing.grantedBy(), existing.grantedAt(), rights));
        }
        setDirty();
        return true;
    }

    /** Keeps the shown name current when an allow-listed player joins. */
    public void touch(UUID uuid, String name) {
        Entry existing = entries.get(uuid);
        if (existing == null || limit(name).isEmpty() || limit(name).equals(existing.lastKnownName())) {
            return;
        }
        entries.put(uuid, new Entry(uuid, limit(name), existing.grantedBy(), existing.grantedAt(), existing.rights()));
        setDirty();
    }

    /** After a grant or revoke the target's command tree may have changed. */
    public static void refreshCommands(MinecraftServer server, UUID uuid) {
        ServerPlayer online = server.getPlayerList().getPlayer(uuid);
        if (online != null) {
            server.getCommands().sendCommands(online);
        }
    }

    public static Component describe(Entry entry) {
        StringBuilder rights = new StringBuilder();
        for (Right right : entry.rights()) {
            rights.append(rights.isEmpty() ? "" : ", ").append(right.id());
        }
        String name = entry.lastKnownName().isEmpty() ? entry.uuid().toString() : entry.lastKnownName();
        return Component.literal("- " + name + " [" + rights + "]");
    }

    // ---------------------------------------------------------------- persistence

    /**
     * The first load of the new store reads the old one without creating it ({@code get}, not
     * {@code computeIfAbsent}): every allow-listed player keeps {@link Right#PUBLISH}. The old file stays on disk
     * untouched as a backup.
     */
    private static WorkshopPermissions migrated(MinecraftServer server) {
        WorkshopPermissions permissions = new WorkshopPermissions();
        try {
            Legacy legacy = server.overworld().getDataStorage()
                    .get(new SavedData.Factory<>(Legacy::new, Legacy::load), LEGACY_DATA_NAME);
            if (legacy == null || legacy.players.isEmpty()) {
                return permissions;
            }
            for (UUID uuid : legacy.players) {
                permissions.entries.put(uuid, new Entry(uuid, "", "", 0L, List.of(Right.PUBLISH)));
            }
            permissions.setDirty();
            MusicBattleMod.LOGGER.info("Migrated {} Blocky Music publish permissions into the workshop allow-list.",
                    permissions.entries.size());
        } catch (RuntimeException exception) {
            MusicBattleMod.LOGGER.warn("Unable to migrate the legacy Blocky Music publish allow-list.", exception);
        }
        return permissions;
    }

    /** The shape of the retired {@code blocky_music_publish_permissions} file: a list of UUID strings. */
    private static final class Legacy extends SavedData {
        private final List<UUID> players = new ArrayList<>();

        private static Legacy load(CompoundTag tag, HolderLookup.Provider registries) {
            Legacy legacy = new Legacy();
            for (Tag entry : tag.getList(LEGACY_PLAYERS_TAG, Tag.TAG_STRING)) {
                try {
                    legacy.players.add(UUID.fromString(entry.getAsString()));
                } catch (IllegalArgumentException ignored) {
                    // A corrupt id in the old file is dropped rather than failing the migration.
                }
            }
            return legacy;
        }

        @Override
        public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
            ListTag list = new ListTag();
            players.forEach(uuid -> list.add(StringTag.valueOf(uuid.toString())));
            tag.put(LEGACY_PLAYERS_TAG, list);
            return tag;
        }
    }

    private static WorkshopPermissions load(CompoundTag tag, HolderLookup.Provider registries) {
        WorkshopPermissions permissions = new WorkshopPermissions();
        ListTag list = tag.getList(ENTRIES_TAG, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            UUID uuid;
            try {
                uuid = UUID.fromString(entry.getString("uuid"));
            } catch (IllegalArgumentException ignored) {
                continue;
            }
            List<Right> rights = new ArrayList<>();
            ListTag rightTags = entry.getList("rights", Tag.TAG_STRING);
            for (Tag right : rightTags) {
                Right.byId(right.getAsString()).ifPresent(value -> {
                    if (!rights.contains(value)) {
                        rights.add(value);
                    }
                });
            }
            if (rights.isEmpty()) {
                continue;
            }
            permissions.entries.put(uuid, new Entry(uuid, limit(entry.getString("lastKnownName")),
                    limit(entry.getString("grantedBy")), entry.getLong("grantedAt"), rights));
        }
        return permissions;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Entry entry : entries.values()) {
            CompoundTag row = new CompoundTag();
            row.putString("uuid", entry.uuid().toString());
            row.putString("lastKnownName", entry.lastKnownName());
            row.putString("grantedBy", entry.grantedBy());
            row.putLong("grantedAt", entry.grantedAt());
            ListTag rights = new ListTag();
            for (Right right : entry.rights()) {
                rights.add(StringTag.valueOf(right.id()));
            }
            row.put("rights", rights);
            list.add(row);
        }
        tag.put(ENTRIES_TAG, list);
        return tag;
    }

    private static String limit(String value) {
        String safe = value == null ? "" : value.strip();
        return safe.length() <= MAX_NAME ? safe : safe.substring(0, MAX_NAME);
    }
}

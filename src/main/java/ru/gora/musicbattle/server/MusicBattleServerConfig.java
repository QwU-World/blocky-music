package ru.gora.musicbattle.server;

import net.neoforged.neoforge.common.ModConfigSpec;
import net.minecraft.server.MinecraftServer;

/** Server/world-specific synchronization settings for Blocky Music battles. */
public final class MusicBattleServerConfig {
    public static final ModConfigSpec SPEC;
    private static final ModConfigSpec.BooleanValue WAIT_FOR_SPECTATOR_DOWNLOADS;

    // Workshop.
    private static final ModConfigSpec.BooleanValue ALLOW_SERVER_DOWNLOADS;
    private static final ModConfigSpec.BooleanValue ALLOW_PLAYER_MAPS;
    private static final ModConfigSpec.LongValue CACHE_QUOTA_BYTES;
    private static final ModConfigSpec.IntValue SERVER_BEATMAP_LIMIT;
    private static final ModConfigSpec.LongValue SERVER_BEATMAP_QUOTA_BYTES;

    // Server comments.
    private static final ModConfigSpec.BooleanValue COMMENTS_ENABLED;
    private static final ModConfigSpec.IntValue COMMENTS_RETENTION_DAYS;
    private static final ModConfigSpec.IntValue COMMENTS_MIN_PERMISSION_LEVEL;
    private static final ModConfigSpec.IntValue COMMENTS_RATE_LIMIT_SECONDS;
    private static final ModConfigSpec.IntValue COMMENTS_RATE_LIMIT_PER_HOUR;
    private static final ModConfigSpec.IntValue COMMENTS_RATE_LIMIT_NEW_DOCUMENTS;
    private static final ModConfigSpec.IntValue COMMENTS_MAX_DOCUMENTS;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        builder.push("battle_sync");
        WAIT_FOR_SPECTATOR_DOWNLOADS = builder
                .comment(
                        "Wait for every player currently inside the audible battle area to download the beatmap before starting.",
                        "Connected speaker chains are included. Participants are always synchronized even when this option is false."
                )
                .define("waitForSpectatorDownloads", true);
        builder.pop();

        builder.push("workshop");
        ALLOW_SERVER_DOWNLOADS = builder
                .comment(
                        "Reserved for a later build. This build never downloads beatmaps on the server and never contacts the",
                        "Workshop from the server, whatever this is set to: it only changes which refusal",
                        "'/musicbattle workshop add <setId>' answers with. Beatmaps reach this server from a player's game",
                        "(Add to server in the Workshop) or from a player who brings one to a battle or a DJ booth.",
                        "(The one web service the mod's server side can reach is Mojang's: giving the test NPC the skin of a",
                        "player who is not online asks Mojang's profile service for that name, unless Minecraft's own",
                        "profile cache already knows it.)"
                )
                .define("allowServerDownloads", false);
        ALLOW_PLAYER_MAPS = builder
                .comment(
                        "Let players vote for a beatmap that is installed only on their own client.",
                        "The server fetches the package from the voter and plays it to everyone, and accepts only packages",
                        "carrying a valid Workshop signature, which is why this can be on by default now that signing has",
                        "shipped. Maps come from the Workshop and live on each player's own machine; publishing to a server",
                        "is the extra, so a server that turns this off can only play what it has published itself.",
                        "A single-player world ignores this setting: there is nobody there but the owner."
                )
                .define("allowPlayerMaps", true);
        CACHE_QUOTA_BYTES = builder
                .comment("Disk budget for packages fetched from voters (blocky_music/workshop_cache). Least recently used sets are evicted.")
                .defineInRange("cacheQuotaBytes", 2L * 1024 * 1024 * 1024, 64L * 1024 * 1024, 512L * 1024 * 1024 * 1024);
        SERVER_BEATMAP_LIMIT = builder
                .comment("How many Workshop sets may be installed on this server.")
                .defineInRange("maxServerBeatmaps", 200, 0, 10000);
        SERVER_BEATMAP_QUOTA_BYTES = builder
                .comment("Disk budget for installed server beatmaps (blocky_music/workshop).")
                .defineInRange("serverBeatmapsQuotaBytes", 8L * 1024 * 1024 * 1024, 64L * 1024 * 1024, 1024L * 1024 * 1024 * 1024);
        builder.pop();

        builder.push("serverComments");
        COMMENTS_ENABLED = builder
                .comment(
                        "Comments stored on this server, next to the Workshop's own. They never leave this world folder,",
                        "and the operator is responsible for them. Turn them off with /musicbattle comments disable."
                )
                .define("enabled", true);
        COMMENTS_RETENTION_DAYS = builder
                .comment("Delete comments older than this many days. 0 keeps them forever; the legal text recommends 180.")
                .defineInRange("retentionDays", 0, 0, 3650);
        COMMENTS_MIN_PERMISSION_LEVEL = builder
                .comment("Operator level a player needs before they may post. 0 means everyone on the server.")
                .defineInRange("minPermissionLevel", 0, 0, 4);
        COMMENTS_RATE_LIMIT_SECONDS = builder
                .comment("Seconds a player must wait between two comments.")
                .defineInRange("rateLimitSeconds", 10, 0, 3600);
        COMMENTS_RATE_LIMIT_PER_HOUR = builder
                .comment("Comments one player may post per hour.")
                .defineInRange("rateLimitPerHour", 20, 1, 1000);
        COMMENTS_RATE_LIMIT_NEW_DOCUMENTS = builder
                .comment("How many maps one player may start a comment thread on per day.")
                .defineInRange("rateLimitNewDocumentsPerDay", 5, 1, 1000);
        COMMENTS_MAX_DOCUMENTS = builder
                .comment("How many commented maps this world may hold in total.")
                .defineInRange("maxDocuments", 10000, 1, 1000000);
        builder.pop();

        SPEC = builder.build();
    }

    private MusicBattleServerConfig() {
    }

    public static boolean waitForSpectatorDownloads() {
        return WAIT_FOR_SPECTATOR_DOWNLOADS.get();
    }

    public static boolean allowServerDownloads() {
        return ALLOW_SERVER_DOWNLOADS.get();
    }

    public static boolean allowPlayerMaps() {
        // Your own world always plays your own maps: the setting exists so a shared server can refuse packages from
        // its players, and there are no other players here. It also rescues a world whose config file was written
        // while the default was off, which is how a fresh world could end up unable to start a battle at all.
        if (!dedicated()) {
            return true;
        }
        return ALLOW_PLAYER_MAPS.get();
    }

    /** True on a server other people join; false in a single-player world or a LAN game opened from one. */
    private static boolean dedicated() {
        MinecraftServer server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
        return server != null && server.isDedicatedServer();
    }

    public static long cacheQuotaBytes() {
        return CACHE_QUOTA_BYTES.get();
    }

    public static int serverBeatmapLimit() {
        return SERVER_BEATMAP_LIMIT.get();
    }

    public static long serverBeatmapQuotaBytes() {
        return SERVER_BEATMAP_QUOTA_BYTES.get();
    }

    public static boolean serverCommentsEnabled() {
        return COMMENTS_ENABLED.get();
    }

    public static void setServerCommentsEnabled(boolean value) {
        COMMENTS_ENABLED.set(value);
        COMMENTS_ENABLED.save();
    }

    public static int serverCommentsRetentionDays() {
        return COMMENTS_RETENTION_DAYS.get();
    }

    public static int serverCommentsMinPermissionLevel() {
        return COMMENTS_MIN_PERMISSION_LEVEL.get();
    }

    public static int serverCommentsRateLimitSeconds() {
        return COMMENTS_RATE_LIMIT_SECONDS.get();
    }

    public static int serverCommentsRateLimitPerHour() {
        return COMMENTS_RATE_LIMIT_PER_HOUR.get();
    }

    public static int serverCommentsNewDocumentsPerDay() {
        return COMMENTS_RATE_LIMIT_NEW_DOCUMENTS.get();
    }

    public static int serverCommentsMaxDocuments() {
        return COMMENTS_MAX_DOCUMENTS.get();
    }
}

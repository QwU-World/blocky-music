package ru.gora.musicbattle.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.loading.FMLPaths;
import ru.gora.musicbattle.MusicBattleMod;

/**
 * Every player's battle record - wins, flawless wins, losses, surrenders - kept by UUID in one JSON file in the game
 * folder and written after each change, so the win milestones (see {@code data/BattleWinMilestones}) and the
 * microphone's tooltip read the same count after a restart. It is one table for the game folder, not one per world:
 * in single player a record follows the player from world to world, and a milestone already passed is granted in a
 * new world with the next win there.
 *
 * <p>A file that cannot be read is moved aside rather than written over. It used to be read once, and a broken one
 * - a hand edit, a disk that filled up mid-write before the write became atomic - either threw out of the win that
 * tried to record itself or left an empty table that the next save turned into the only copy, losing everyone's
 * count to one bad character. Checked by tools/BattleRulesChecks.java against a throwaway game folder.
 */
public final class PlayerBattleStats {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Map<UUID, Stats> STATS = new HashMap<>();
    private static boolean loaded;
    /** Set when an unreadable file could not be moved aside: this session keeps its counts in memory only. */
    private static boolean saveBlocked;

    private PlayerBattleStats() {
    }

    public static synchronized Stats get(UUID playerId, String name) {
        ensureLoaded();
        Stats stats = STATS.computeIfAbsent(playerId, ignored -> new Stats());
        if (name != null && !name.isBlank()) {
            stats.name = name;
        }
        return stats.copy();
    }

    public static synchronized Stats get(ServerPlayer player) {
        return get(player.getUUID(), player.getGameProfile().getName());
    }

    /** Counts a win and returns the player's wins including it, so a milestone check needs no second lookup. */
    public static int recordWin(ServerPlayer player, boolean fullCombo) {
        return recordWin(player.getUUID(), player.getGameProfile().getName(), fullCombo);
    }

    /** {@link #recordWin(ServerPlayer, boolean)} by the player's id, with the name to keep beside the count. */
    public static synchronized int recordWin(UUID playerId, String name, boolean fullCombo) {
        ensureLoaded();
        Stats stats = mutable(playerId, name);
        stats.wins = increment(stats.wins);
        if (fullCombo) {
            stats.fullComboWins = increment(stats.fullComboWins);
        }
        save();
        return stats.wins;
    }

    public static void recordLoss(ServerPlayer player) {
        recordLoss(player.getUUID(), player.getGameProfile().getName());
    }

    public static synchronized void recordLoss(UUID playerId, String name) {
        ensureLoaded();
        Stats stats = mutable(playerId, name);
        stats.losses = increment(stats.losses);
        save();
    }

    public static void recordSurrender(ServerPlayer player) {
        recordSurrender(player.getUUID(), player.getGameProfile().getName());
    }

    public static synchronized void recordSurrender(UUID playerId, String name) {
        ensureLoaded();
        Stats stats = mutable(playerId, name);
        stats.losses = increment(stats.losses);
        stats.surrenders = increment(stats.surrenders);
        save();
    }

    private static int increment(int value) {
        return value == Integer.MAX_VALUE ? value : value + 1;
    }

    private static Stats mutable(UUID playerId, String name) {
        Stats stats = STATS.computeIfAbsent(playerId, ignored -> new Stats());
        if (name != null && !name.isBlank()) {
            stats.name = name;
        }
        return stats;
    }

    private static void ensureLoaded() {
        if (loaded) {
            return;
        }
        loaded = true;
        Path path = statsPath();
        if (!Files.isRegularFile(path)) {
            return;
        }
        JsonObject players;
        try {
            JsonObject root = GSON.fromJson(Files.readString(path), JsonObject.class);
            if (root == null) {
                // An empty file holds nobody's record; the next save simply fills it.
                return;
            }
            if (!root.has("players") || !root.get("players").isJsonObject()) {
                throw new IllegalStateException("no \"players\" object");
            }
            players = root.getAsJsonObject("players");
        } catch (IOException | RuntimeException exception) {
            setAside(path, exception);
            return;
        }
        for (Map.Entry<String, JsonElement> entry : players.entrySet()) {
            try {
                UUID id = UUID.fromString(entry.getKey());
                JsonObject object = entry.getValue().getAsJsonObject();
                Stats stats = new Stats();
                stats.name = string(object, "name", "");
                stats.wins = integer(object, "wins", 0);
                stats.fullComboWins = integer(object, "fullComboWins", 0);
                stats.losses = integer(object, "losses", 0);
                stats.surrenders = integer(object, "surrenders", 0);
                STATS.put(id, stats);
            } catch (RuntimeException exception) {
                MusicBattleMod.LOGGER.warn("Skipping an unreadable Blocky Music stats entry '{}'.", entry.getKey(), exception);
            }
        }
    }

    /** Keeps an unreadable stats file for whoever wants to repair it, and starts a fresh one beside it. */
    private static void setAside(Path path, Exception reason) {
        String stamp = path.getFileName() + ".unreadable-" + System.currentTimeMillis();
        Path aside = path.resolveSibling(stamp);
        // A copy set aside earlier in the same millisecond must not make this move fail and stop every later save.
        for (int copy = 1; Files.exists(aside); copy++) {
            aside = path.resolveSibling(stamp + "-" + copy);
        }
        try {
            Files.move(path, aside);
            MusicBattleMod.LOGGER.warn("Blocky Music player stats '{}' could not be read and were moved to '{}'.", path, aside, reason);
        } catch (IOException moveFailure) {
            saveBlocked = true;
            MusicBattleMod.LOGGER.warn("Blocky Music player stats '{}' could not be read or moved aside; this session's counts stay in memory.",
                    path, reason);
        }
    }

    private static void save() {
        if (saveBlocked) {
            return;
        }
        Path path = statsPath();
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        try {
            Files.createDirectories(path.getParent());
            JsonObject players = new JsonObject();
            for (Map.Entry<UUID, Stats> entry : STATS.entrySet()) {
                Stats stats = entry.getValue();
                JsonObject object = new JsonObject();
                object.addProperty("name", stats.name);
                object.addProperty("wins", stats.wins);
                object.addProperty("fullComboWins", stats.fullComboWins);
                object.addProperty("losses", stats.losses);
                object.addProperty("surrenders", stats.surrenders);
                players.add(entry.getKey().toString(), object);
            }
            JsonObject root = new JsonObject();
            root.addProperty("version", 1);
            root.add("players", players);
            Files.writeString(temporary, GSON.toJson(root));
            try {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                // Some file systems cannot swap a file in one step. Without this every save failed there, and no win
                // was ever kept past a restart; a plain replace still writes the whole table.
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
            }
            MusicBattleMod.LOGGER.warn("Unable to write Blocky Music player stats '{}'.", path, exception);
        }
    }

    private static Path statsPath() {
        return FMLPaths.GAMEDIR.get().resolve("music_battle_player_stats.json").toAbsolutePath().normalize();
    }

    private static String string(JsonObject object, String key, String fallback) {
        return object.has(key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsString() : fallback;
    }

    private static int integer(JsonObject object, String key, int fallback) {
        if (!object.has(key) || !object.get(key).isJsonPrimitive()) {
            return fallback;
        }
        try {
            return Math.max(0, object.get(key).getAsInt());
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    public static final class Stats {
        private String name = "";
        private int wins;
        private int fullComboWins;
        private int losses;
        private int surrenders;

        private Stats copy() {
            Stats copy = new Stats();
            copy.name = this.name;
            copy.wins = this.wins;
            copy.fullComboWins = this.fullComboWins;
            copy.losses = this.losses;
            copy.surrenders = this.surrenders;
            return copy;
        }

        public String name() {
            return name;
        }

        public int wins() {
            return wins;
        }

        public int fullComboWins() {
            return fullComboWins;
        }

        public int losses() {
            return losses;
        }

        public int surrenders() {
            return surrenders;
        }
    }
}

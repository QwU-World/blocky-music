package ru.gora.musicbattle.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.loading.FMLPaths;
import ru.gora.musicbattle.MusicBattleMod;

public final class PlayerBattleStats {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Map<UUID, Stats> STATS = new HashMap<>();
    private static boolean loaded;

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

    public static synchronized void recordWin(ServerPlayer player, boolean fullCombo) {
        ensureLoaded();
        Stats stats = mutable(player);
        stats.wins++;
        if (fullCombo) {
            stats.fullComboWins++;
        }
        save();
    }

    public static synchronized void recordLoss(ServerPlayer player) {
        ensureLoaded();
        mutable(player).losses++;
        save();
    }

    public static synchronized void recordSurrender(ServerPlayer player) {
        ensureLoaded();
        Stats stats = mutable(player);
        stats.losses++;
        stats.surrenders++;
        save();
    }

    private static Stats mutable(ServerPlayer player) {
        Stats stats = STATS.computeIfAbsent(player.getUUID(), ignored -> new Stats());
        stats.name = player.getGameProfile().getName();
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
        try {
            JsonObject root = GSON.fromJson(Files.readString(path), JsonObject.class);
            if (root == null || !root.has("players") || !root.get("players").isJsonObject()) {
                return;
            }
            JsonObject players = root.getAsJsonObject("players");
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
                } catch (RuntimeException ignored) {
                }
            }
        } catch (IOException exception) {
            MusicBattleMod.LOGGER.warn("Unable to read Blocky Music player stats '{}'.", path, exception);
        }
    }

    private static void save() {
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
            Files.move(temporary, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
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
        return object.has(key) && object.get(key).isJsonPrimitive() ? Math.max(0, object.get(key).getAsInt()) : fallback;
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

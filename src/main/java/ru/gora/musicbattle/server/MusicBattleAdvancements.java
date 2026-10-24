package ru.gora.musicbattle.server;

import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import ru.gora.musicbattle.MusicBattleMod;

/**
 * Hands out the advancements the game itself cannot notice.
 *
 * <p>Placing a speaker, wiring one in, pointing the microphone at somebody, crafting the smiley's egg: Minecraft
 * already has triggers for all of those, and those advancements are pure data. Winning a battle without missing a
 * note, or playing a set to four people at once, are things only this mod knows about, so their files carry the
 * impossible trigger and are awarded from here instead. That avoids registering a trigger type of our own, which
 * would mean a codec, a registry entry and a network change for no gain.
 *
 * <p>A missing advancement is not an error: a server may have replaced or removed the mod's data pack, and a player
 * who is mid-battle when that happens should not see an exception for it.
 */
public final class MusicBattleAdvancements {
    /** The criterion name every impossible-trigger file in this mod uses. */
    public static final String CODE = "code";

    private MusicBattleAdvancements() {
    }

    /** Awards one of the mod's advancements, by the path under {@code data/music_battle/advancement/}. */
    public static void grant(ServerPlayer player, String path) {
        if (player == null) {
            return;
        }
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        AdvancementHolder holder = server.getAdvancements().get(MusicBattleMod.id(path));
        if (holder == null) {
            MusicBattleMod.LOGGER.debug("Blocky Music advancement '{}' is not loaded.", path);
            return;
        }
        player.getAdvancements().award(holder, CODE);
    }
}

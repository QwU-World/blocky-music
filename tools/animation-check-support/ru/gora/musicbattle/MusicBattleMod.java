package ru.gora.musicbattle;

import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Launch boundary only: tests use the real animation classes without registering a game mod. */
public final class MusicBattleMod {
    public static final String MOD_ID = "music_battle";
    public static final Logger LOGGER = LoggerFactory.getLogger("BlockyMusicAnimationChecks");

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }
}

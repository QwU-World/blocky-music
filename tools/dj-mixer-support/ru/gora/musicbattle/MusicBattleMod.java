package ru.gora.musicbattle;
import net.minecraft.resources.ResourceLocation;
/** Test-only namespace/logging boundary; never initializes a NeoForge mod container. */
public final class MusicBattleMod {
    public static final String MOD_ID = "music_battle";
    public static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(MOD_ID);
    public static ResourceLocation id(String path) { return ResourceLocation.fromNamespaceAndPath(MOD_ID,path); }
}

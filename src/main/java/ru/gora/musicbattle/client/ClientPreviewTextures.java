package ru.gora.musicbattle.client;

import com.mojang.blaze3d.platform.NativeImage;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import ru.gora.musicbattle.MusicBattleMod;

public final class ClientPreviewTextures {
    private static final Map<String, ResourceLocation> CACHE = new HashMap<>();

    private ClientPreviewTextures() {
    }

    public static ResourceLocation get(String key, Path path) {
        if (path == null || !Files.isRegularFile(path)) {
            return null;
        }
        String cacheKey = key + "|" + path.toAbsolutePath().normalize();
        ResourceLocation cached = CACHE.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        try (InputStream input = Files.newInputStream(path)) {
            NativeImage image = NativeImage.read(input);
            ResourceLocation location = MusicBattleMod.id("track_preview/" + Integer.toHexString(cacheKey.hashCode()));
            Minecraft.getInstance().getTextureManager().register(location, new DynamicTexture(image));
            CACHE.put(cacheKey, location);
            return location;
        } catch (Exception ignored) {
            return null;
        }
    }
}

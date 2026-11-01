package ru.gora.musicbattle.client;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.yggdrasil.ProfileResult;
import com.mojang.blaze3d.platform.NativeImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import com.mojang.blaze3d.vertex.PoseStack;
import ru.gora.musicbattle.MusicBattleMod;
import ru.gora.musicbattle.data.NpcSkinPng;
import ru.gora.musicbattle.entity.TestRapGodMasterEntity;

public final class TestRapGodMasterRenderer extends HumanoidMobRenderer<TestRapGodMasterEntity, PlayerModel<TestRapGodMasterEntity>> {
    /** The NPC's own outfit, worn whenever it has no other skin or its skin cannot be drawn. */
    public static final ResourceLocation DEFAULT_TEXTURE = MusicBattleMod.id("textures/entity/test_rap_god_master.png");
    private static final long SKIN_RETRY_DELAY_MS = 30_000L;
    private static final Map<UUID, SkinLoad> SKIN_TEXTURES = new ConcurrentHashMap<>();
    /**
     * How many skins from files stay uploaded at once. Each is 16 KiB of texture; an NPC whose skin was dropped is
     * simply decoded again from its synched bytes the next time it is drawn.
     */
    private static final int MAX_FILE_SKINS = 64;
    /** Skins from files by SHA-256, least recently drawn first; a null texture marks bytes that could not be decoded. */
    private static final LinkedHashMap<String, FileSkin> FILE_SKINS = new LinkedHashMap<>(16, 0.75F, true);
    /** Set only while the configuration screen draws its preview of the NPC, and only for that NPC. */
    private static Preview preview;
    private final PlayerModel<TestRapGodMasterEntity> classicModel;
    private final PlayerModel<TestRapGodMasterEntity> slimModel;

    public TestRapGodMasterRenderer(EntityRendererProvider.Context context) {
        super(context, new PlayerModel<>(context.bakeLayer(ModelLayers.PLAYER), false), 0.5F);
        classicModel = model;
        slimModel = new PlayerModel<>(context.bakeLayer(ModelLayers.PLAYER_SLIM), true);
        addLayer(new ItemInHandLayer<>(this, context.getItemInHandRenderer()));
    }

    @Override
    public void render(TestRapGodMasterEntity entity, float entityYaw, float partialTicks, PoseStack poseStack, MultiBufferSource buffer, int packedLight) {
        Preview shown = previewOf(entity);
        boolean slim = shown != null ? shown.slim() : entity.isSlimSkin();
        model = slim ? slimModel : classicModel;
        super.render(entity, entityYaw, partialTicks, poseStack, buffer, packedLight);
    }

    @Override
    public ResourceLocation getTextureLocation(TestRapGodMasterEntity entity) {
        Preview shown = previewOf(entity);
        if (shown != null) {
            return shown.texture() == null ? DEFAULT_TEXTURE : shown.texture();
        }
        // A skin from a file comes first: the server takes the nickname off whenever it accepts a file, so both are
        // only ever set together for the moment it takes the next entity data to arrive.
        String fileSkin = entity.getSkinFileHash();
        if (!fileSkin.isEmpty()) {
            ResourceLocation texture = fileSkinTexture(fileSkin, entity.getSkinFilePng());
            return texture == null ? DEFAULT_TEXTURE : texture;
        }
        return entity.getSkinUuid()
                .map(TestRapGodMasterRenderer::profileSkinTexture)
                .orElse(DEFAULT_TEXTURE);
    }

    /** The preview in the configuration screen is the model alone; the name has a field of its own right next to it. */
    @Override
    protected void renderNameTag(TestRapGodMasterEntity entity, Component name, PoseStack poseStack, MultiBufferSource buffer,
                                 int packedLight, float partialTick) {
        if (previewOf(entity) != null) {
            return;
        }
        super.renderNameTag(entity, name, poseStack, buffer, packedLight, partialTick);
    }

    /**
     * Draws the NPC into a screen, turned towards the mouse like the player in the inventory, as it would look with
     * this texture and arm type. The settings it has in the world are left alone: the override lives only for the
     * length of this call, and only for this NPC.
     *
     * @param texture what the preview wears, or null for the NPC's own outfit
     */
    public static void renderPreview(GuiGraphics graphics, TestRapGodMasterEntity npc, ResourceLocation texture, boolean slim,
                                     int x1, int y1, int x2, int y2, int scale, float mouseX, float mouseY) {
        preview = new Preview(npc, texture, slim);
        try {
            InventoryScreen.renderEntityInInventoryFollowsMouse(graphics, x1, y1, x2, y2, scale, 0.0625F, mouseX, mouseY, npc);
        } finally {
            preview = null;
        }
    }

    private static Preview previewOf(TestRapGodMasterEntity entity) {
        Preview shown = preview;
        return shown != null && shown.npc() == entity ? shown : null;
    }

    /**
     * The texture of a skin from a file, uploaded the first time it is asked for and then kept by its hash, so ten NPCs
     * in the same skin share one texture and the configuration screen's preview is the very texture the NPC wears once
     * the server has the file. Render thread only.
     *
     * @return the texture, or null when the bytes are not a skin (the caller draws the default outfit)
     */
    public static ResourceLocation fileSkinTexture(String sha256, byte[] png) {
        if (!NpcSkinPng.isSha256(sha256) || png == null) {
            return null;
        }
        FileSkin cached = FILE_SKINS.get(sha256);
        if (cached == null) {
            cached = new FileSkin(uploadFileSkin(sha256, png));
            FILE_SKINS.put(sha256, cached);
            trimFileSkins();
        }
        return cached.texture();
    }

    private static ResourceLocation uploadFileSkin(String sha256, byte[] png) {
        // The server checked the file before the NPC wore it; it is checked again because this client is about to hand
        // it to a native decoder, and because the hash it is filed under has to be the hash of these very bytes.
        NpcSkinPng.Result check = NpcSkinPng.check(png);
        if (!check.ok() || !sha256.equals(NpcSkinPng.sha256(png))) {
            MusicBattleMod.LOGGER.warn("Test NPC skin {} is not a usable skin ({}); drawing the default outfit", sha256, check.problem());
            return null;
        }
        try (NativeImage decoded = NativeImage.read(new ByteArrayInputStream(png))) {
            int width = decoded.getWidth();
            int height = decoded.getHeight();
            int[] pixels = new int[width * height];
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    pixels[y * width + x] = decoded.getPixelRGBA(x, y);
                }
            }
            int[] skin = NpcSkinPng.normalize(pixels, width, height);
            NativeImage image = new NativeImage(NpcSkinPng.WIDTH, NpcSkinPng.HEIGHT, false);
            for (int y = 0; y < NpcSkinPng.HEIGHT; y++) {
                for (int x = 0; x < NpcSkinPng.WIDTH; x++) {
                    image.setPixelRGBA(x, y, skin[y * NpcSkinPng.WIDTH + x]);
                }
            }
            ResourceLocation location = MusicBattleMod.id("npc_skin/" + sha256);
            Minecraft.getInstance().getTextureManager().register(location, new DynamicTexture(image));
            return location;
        } catch (IOException | RuntimeException exception) {
            MusicBattleMod.LOGGER.warn("Could not decode test NPC skin {}", sha256, exception);
            return null;
        }
    }

    private static void trimFileSkins() {
        Iterator<Map.Entry<String, FileSkin>> eldest = FILE_SKINS.entrySet().iterator();
        while (FILE_SKINS.size() > MAX_FILE_SKINS && eldest.hasNext()) {
            ResourceLocation texture = eldest.next().getValue().texture();
            eldest.remove();
            if (texture != null) {
                Minecraft.getInstance().getTextureManager().release(texture);
            }
        }
    }

    /** The skin of a Minecraft account, fetched from Mojang the first time; the default outfit until it arrives. */
    public static ResourceLocation profileSkinTexture(UUID uuid) {
        long now = Util.getMillis();
        SkinLoad load = SKIN_TEXTURES.compute(uuid, (ignored, current) -> {
            if (current == null || current.failed(now)) {
                return new SkinLoad(loadSkin(uuid), now + SKIN_RETRY_DELAY_MS);
            }
            return current;
        });
        return load.future().getNow(DEFAULT_TEXTURE);
    }

    private static CompletableFuture<ResourceLocation> loadSkin(UUID uuid) {
        Minecraft minecraft = Minecraft.getInstance();
        return CompletableFuture.supplyAsync(() -> minecraft.getMinecraftSessionService().fetchProfile(uuid, true), Util.nonCriticalIoPool())
                .thenComposeAsync(result -> {
                    ProfileResult profileResult = result;
                    GameProfile profile = profileResult == null ? null : profileResult.profile();
                    return profile == null
                            ? CompletableFuture.completedFuture(DEFAULT_TEXTURE)
                            : minecraft.getSkinManager().getOrLoad(profile).thenApply(skin -> skin.texture());
                }, minecraft)
                .exceptionally(ignored -> DEFAULT_TEXTURE);
    }

    /** Failed Mojang/profile requests are retried instead of caching the NPC fallback forever. */
    private record SkinLoad(CompletableFuture<ResourceLocation> future, long retryAtMillis) {
        private boolean failed(long now) {
            return now >= retryAtMillis && future.isDone() && DEFAULT_TEXTURE.equals(future.getNow(DEFAULT_TEXTURE));
        }
    }

    private record FileSkin(ResourceLocation texture) {
    }

    private record Preview(TestRapGodMasterEntity npc, ResourceLocation texture, boolean slim) {
    }
}

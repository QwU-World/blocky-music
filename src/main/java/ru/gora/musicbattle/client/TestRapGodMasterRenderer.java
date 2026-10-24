package ru.gora.musicbattle.client;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.yggdrasil.ProfileResult;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.resources.ResourceLocation;
import com.mojang.blaze3d.vertex.PoseStack;
import ru.gora.musicbattle.MusicBattleMod;
import ru.gora.musicbattle.entity.TestRapGodMasterEntity;

public final class TestRapGodMasterRenderer extends HumanoidMobRenderer<TestRapGodMasterEntity, PlayerModel<TestRapGodMasterEntity>> {
    private static final ResourceLocation TEXTURE = MusicBattleMod.id("textures/entity/test_rap_god_master.png");
    private static final long SKIN_RETRY_DELAY_MS = 30_000L;
    private static final Map<UUID, SkinLoad> SKIN_TEXTURES = new ConcurrentHashMap<>();
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
        model = entity.isSlimSkin() ? slimModel : classicModel;
        super.render(entity, entityYaw, partialTicks, poseStack, buffer, packedLight);
    }

    @Override
    public ResourceLocation getTextureLocation(TestRapGodMasterEntity entity) {
        return entity.getSkinUuid()
                .map(TestRapGodMasterRenderer::getSkinTexture)
                .orElse(TEXTURE);
    }

    private static ResourceLocation getSkinTexture(UUID uuid) {
        long now = Util.getMillis();
        SkinLoad load = SKIN_TEXTURES.compute(uuid, (ignored, current) -> {
            if (current == null || current.failed(now)) {
                return new SkinLoad(loadSkin(uuid), now + SKIN_RETRY_DELAY_MS);
            }
            return current;
        });
        return load.future().getNow(TEXTURE);
    }

    private static CompletableFuture<ResourceLocation> loadSkin(UUID uuid) {
        Minecraft minecraft = Minecraft.getInstance();
        return CompletableFuture.supplyAsync(() -> minecraft.getMinecraftSessionService().fetchProfile(uuid, true), Util.nonCriticalIoPool())
                .thenComposeAsync(result -> {
                    ProfileResult profileResult = result;
                    GameProfile profile = profileResult == null ? null : profileResult.profile();
                    return profile == null
                            ? CompletableFuture.completedFuture(TEXTURE)
                            : minecraft.getSkinManager().getOrLoad(profile).thenApply(skin -> skin.texture());
                }, minecraft)
                .exceptionally(ignored -> TEXTURE);
    }

    /** Failed Mojang/profile requests are retried instead of caching the NPC fallback forever. */
    private record SkinLoad(CompletableFuture<ResourceLocation> future, long retryAtMillis) {
        private boolean failed(long now) {
            return now >= retryAtMillis && future.isDone() && TEXTURE.equals(future.getNow(TEXTURE));
        }
    }
}

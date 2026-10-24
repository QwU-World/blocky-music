package ru.gora.musicbattle.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.resources.ResourceLocation;
import ru.gora.musicbattle.entity.SmileyBallEntity;

/** Keeps the normal living-entity render path, including the mod's model effects. */
public final class SmileyBallRenderer extends MobRenderer<SmileyBallEntity, SmileyBallModel> {
    private static final ResourceLocation TEXTURE =
            ResourceLocation.withDefaultNamespace("textures/misc/white.png");

    public SmileyBallRenderer(EntityRendererProvider.Context context) {
        super(context, new SmileyBallModel(), 0.43F);
    }

    @Override
    public ResourceLocation getTextureLocation(SmileyBallEntity entity) {
        return TEXTURE;
    }

    @Override
    protected void scale(SmileyBallEntity entity, PoseStack poses, float partialTick) {
        // This procedural model does not inherit the vanilla ageable model's baby scaling.
        float scale = entity.getAgeScale();
        poses.scale(scale, scale, scale);
    }
}

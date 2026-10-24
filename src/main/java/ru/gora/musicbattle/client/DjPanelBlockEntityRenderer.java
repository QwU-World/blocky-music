package ru.gora.musicbattle.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import ru.gora.musicbattle.block.DjPanelBlock;
import ru.gora.musicbattle.block.entity.DjPanelBlockEntity;

public final class DjPanelBlockEntityRenderer implements BlockEntityRenderer<DjPanelBlockEntity> {
    public DjPanelBlockEntityRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(DjPanelBlockEntity panel, float partialTick, PoseStack poseStack,
                       MultiBufferSource bufferSource, int packedLight, int packedOverlay) {
        Direction facing = panel.getBlockState().hasProperty(DjPanelBlock.FACING)
                ? panel.getBlockState().getValue(DjPanelBlock.FACING)
                : Direction.NORTH;
        boolean showAnimation = panel.isPlaying() || panel.isPlaybackPaused();
        float animationSeconds = panel.getLevel() == null
                ? 0.0F
                : (panel.getPlaybackPositionTicks(panel.getLevel().getGameTime())
                        + (panel.isPlaying() ? partialTick : 0.0F)) / 20.0F;
        BattleCameraModel.renderDjBlock(poseStack, bufferSource, packedLight, packedOverlay,
                facing, showAnimation, animationSeconds);
    }

    /** The rack's lid and cables lean out of the block while it plays, so the box it is judged by is bigger too. */
    @Override
    public AABB getRenderBoundingBox(DjPanelBlockEntity panel) {
        return new AABB(panel.getBlockPos()).inflate(0.5D);
    }
}

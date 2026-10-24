package ru.gora.musicbattle.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import ru.gora.musicbattle.block.BattleCameraBlock;
import ru.gora.musicbattle.block.entity.BattleCameraBlockEntity;

public final class BattleCameraBlockEntityRenderer implements BlockEntityRenderer<BattleCameraBlockEntity> {
    public BattleCameraBlockEntityRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(BattleCameraBlockEntity camera, float partialTick, PoseStack poseStack,
                       MultiBufferSource bufferSource, int packedLight, int packedOverlay) {
        Direction facing = camera.getBlockState().hasProperty(BattleCameraBlock.FACING)
                ? camera.getBlockState().getValue(BattleCameraBlock.FACING)
                : Direction.NORTH;
        BattleCameraModel.renderBlock(
                poseStack,
                bufferSource,
                packedLight,
                packedOverlay,
                facing,
                camera.getPlacementAnimationSeconds(partialTick)
        );
    }

    /**
     * The camera's tripod and head reach past the block it stands in, and its placement clip swings them further.
     * Judged by the block alone, the whole thing disappears at the angle where that block leaves the view.
     */
    @Override
    public AABB getRenderBoundingBox(BattleCameraBlockEntity camera) {
        return new AABB(camera.getBlockPos()).inflate(0.5D);
    }
}

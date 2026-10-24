package ru.gora.musicbattle.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.core.BlockPos;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import ru.gora.musicbattle.block.SpeakerBlock;
import ru.gora.musicbattle.block.SpeakerPairing;
import ru.gora.musicbattle.block.entity.SpeakerBlockEntity;
import ru.gora.musicbattle.registry.ModBlocks;

public final class SpeakerBlockEntityRenderer implements BlockEntityRenderer<SpeakerBlockEntity> {
    public SpeakerBlockEntityRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(SpeakerBlockEntity speaker, float partialTick, PoseStack poseStack,
                       MultiBufferSource bufferSource, int packedLight, int packedOverlay) {
        Direction facing = facingOf(speaker);
        boolean active = speaker.getBlockState().hasProperty(SpeakerBlock.ACTIVE)
                && speaker.getBlockState().getValue(SpeakerBlock.ACTIVE);
        float elapsedSeconds = speaker.getLevel() == null
                ? 0.0F
                : (speaker.getLevel().getGameTime() + partialTick) / 20.0F;
        // The supplied animation lasts 0.5 seconds. Advancing one animation loop per beat
        // locks the cone/body movement to the effective (speed-adjusted) battle BPM.
        float animationSeconds = elapsedSeconds * (speaker.getActiveBpm() / 60.0F) * 0.5F;
        boolean horizontal = isHorizontal(speaker);
        BattleCameraModel.renderSpeakerBlock(poseStack, bufferSource, packedLight, packedOverlay,
                facing, active, animationSeconds, horizontal);
    }

    @Override
    public boolean shouldRenderOffScreen(SpeakerBlockEntity speaker) {
        return true;
    }

    /**
     * A speaker is two blocks tall, and only the lower one holds the block entity.
     *
     * Without this the game decides whether to draw the cabinet by testing the single block the entity sits in, so
     * turning until that one block leaves the view makes the whole speaker vanish while half of it is still on
     * screen. The box covers both blocks, plus a little for the pulse the beat animation gives the body.
     */
    @Override
    public AABB getRenderBoundingBox(SpeakerBlockEntity speaker) {
        BlockPos pos = speaker.getBlockPos();
        return new AABB(pos)
                .minmax(new AABB(SpeakerPairing.extensionPos(isHorizontal(speaker), facingOf(speaker), pos)))
                .inflate(0.125D);
    }

    private static Direction facingOf(SpeakerBlockEntity speaker) {
        return speaker.getBlockState().hasProperty(SpeakerBlock.FACING)
                ? speaker.getBlockState().getValue(SpeakerBlock.FACING)
                : Direction.NORTH;
    }

    private static boolean isHorizontal(SpeakerBlockEntity speaker) {
        return speaker.getBlockState().is(ModBlocks.HORIZONTAL_SPEAKER.get());
    }
}

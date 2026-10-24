package ru.gora.musicbattle.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import ru.gora.musicbattle.registry.ModItems;

public final class SpeakerItemRenderer extends BlockEntityWithoutLevelRenderer {
    public SpeakerItemRenderer() {
        super(Minecraft.getInstance().getBlockEntityRenderDispatcher(), Minecraft.getInstance().getEntityModels());
    }

    @Override
    public void renderByItem(ItemStack stack, ItemDisplayContext displayContext, PoseStack poseStack,
                             MultiBufferSource bufferSource, int packedLight, int packedOverlay) {
        BattleCameraModel.renderSpeakerItem(poseStack, bufferSource, packedLight, packedOverlay,
                stack.is(ModItems.HORIZONTAL_SPEAKER.get()));
    }
}

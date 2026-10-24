package ru.gora.musicbattle.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import ru.gora.musicbattle.registry.ModItems;

/** Renders the battle microphone while the animated player model's hand pose is still active. */
final class BattleMicrophoneLayer extends RenderLayer<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> {
    BattleMicrophoneLayer(RenderLayerParent<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> parent) {
        super(parent);
    }

    @Override
    public void render(PoseStack pose, MultiBufferSource buffers, int packedLight, AbstractClientPlayer player,
                       float limbSwing, float limbSwingAmount, float partialTick, float ageInTicks,
                       float netHeadYaw, float headPitch) {
        if (!ClientBattleController.shouldRenderBattleMicrophone(player)
                || player.getMainHandItem().is(ModItems.MICROPHONE.get())
                || player.getOffhandItem().is(ModItems.MICROPHONE.get())
                || player.getMainHandItem().is(ModItems.SOLO_MICROPHONE.get())
                || player.getOffhandItem().is(ModItems.SOLO_MICROPHONE.get())) {
            return;
        }

        pose.pushPose();
        getParentModel().translateToHand(HumanoidArm.RIGHT, pose);
        pose.mulPose(Axis.XP.rotationDegrees(-90.0F));
        pose.mulPose(Axis.YP.rotationDegrees(180.0F));
        pose.translate(1.0F / 16.0F, 0.125F, -0.625F);
        Minecraft.getInstance().getItemRenderer().renderStatic(
                player,
                new ItemStack(ModItems.MICROPHONE.get()),
                ItemDisplayContext.THIRD_PERSON_RIGHT_HAND,
                false,
                pose,
                buffers,
                player.level(),
                packedLight,
                OverlayTexture.NO_OVERLAY,
                player.getId()
        );
        pose.popPose();
    }
}

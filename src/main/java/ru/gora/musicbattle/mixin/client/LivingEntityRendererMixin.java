package ru.gora.musicbattle.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;
import ru.gora.musicbattle.client.ClientHumanoidAnimatorBridge;
import ru.gora.musicbattle.client.ClientMobFallbackAnimator;
import ru.gora.musicbattle.client.ClientBattleController;

@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererMixin<T extends LivingEntity, M extends EntityModel<T>> {
    @Shadow
    protected M model;

    @ModifyArgs(
            method = "render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/model/EntityModel;setupAnim(Lnet/minecraft/world/entity/Entity;FFFFF)V"
            )
    )
    private void musicBattle$suppressParticipantLocomotion(
            Args args,
            T entity,
            float entityYaw,
            float partialTicks,
            PoseStack poseStack,
            MultiBufferSource buffer,
            int packedLight
    ) {
        if (ClientBattleController.suppressParticipantLocomotion(entity)) {
            // limbSwing + limbSwingAmount. Participants are locked to arena
            // anchors; stale interpolation must not animate them as walking.
            args.set(1, 0.0F);
            args.set(2, 0.0F);
        }
    }

    @Inject(
            method = "render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/vertex/PoseStack;pushPose()V",
                    ordinal = 0,
                    shift = At.Shift.AFTER
            )
    )
    private void musicBattle$applyFallbackMobBounce(
            T entity,
            float entityYaw,
            float partialTicks,
            PoseStack poseStack,
            MultiBufferSource buffer,
            int packedLight,
            CallbackInfo callbackInfo
    ) {
        if (!(entity instanceof AbstractClientPlayer)) {
            ClientMobFallbackAnimator.applyPreparedBounce(entity, poseStack, partialTicks);
        }
    }

    @Inject(
            method = "render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/model/EntityModel;setupAnim(Lnet/minecraft/world/entity/Entity;FFFFF)V",
                    shift = At.Shift.AFTER
            )
    )
    private void musicBattle$applyHumanoidBattleAnimationAfterModelSetup(
            T entity,
            float entityYaw,
            float partialTicks,
            PoseStack poseStack,
            MultiBufferSource buffer,
            int packedLight,
            CallbackInfo callbackInfo
    ) {
        if (!(entity instanceof AbstractClientPlayer) && this.model instanceof HumanoidModel<?> humanoidModel) {
            ClientHumanoidAnimatorBridge.applyPreparedAnimation(entity, humanoidModel);
        }
    }

    @Inject(
            method = "render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/model/EntityModel;setupAnim(Lnet/minecraft/world/entity/Entity;FFFFF)V",
                    shift = At.Shift.AFTER
            )
    )
    private void musicBattle$beginEntityFxMask(
            T entity,
            float entityYaw,
            float partialTicks,
            PoseStack poseStack,
            MultiBufferSource buffer,
            int packedLight,
            CallbackInfo callbackInfo
    ) {
        // Start before getRenderType is evaluated. Invisible bodies can still have
        // render layers, and nested renderers must not inherit an outer entity bit.
        ClientBattleController.beginFxEntityMask(entity, buffer, partialTicks);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Inject(
            method = "render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/MultiBufferSource;getBuffer(Lnet/minecraft/client/renderer/RenderType;)Lcom/mojang/blaze3d/vertex/VertexConsumer;",
                    ordinal = 0,
                    shift = At.Shift.BEFORE
            )
    )
    private void musicBattle$renderHighlightInAnimatedPose(
            T entity,
            float entityYaw,
            float partialTicks,
            PoseStack poseStack,
            MultiBufferSource buffer,
            int packedLight,
            CallbackInfo callbackInfo
    ) {
        LivingEntityRenderer renderer = (LivingEntityRenderer) (Object) this;
        ClientBattleController.renderModelHighlightInRenderer(entity, this.model, poseStack, buffer,
                renderer.getTextureLocation(entity), packedLight, partialTicks);
    }

    @ModifyArgs(
            method = "render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/model/EntityModel;renderToBuffer(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;III)V",
                    ordinal = 0
            )
    )
    private void musicBattle$applyFullBrightHighlightModel(
            Args args,
            T entity,
            float entityYaw,
            float partialTicks,
            PoseStack poseStack,
            MultiBufferSource buffer,
            int packedLight
    ) {
        args.set(2, ClientBattleController.applyModelHighlightLight(entity, partialTicks, (int) args.get(2)));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Inject(
            method = "render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/model/EntityModel;renderToBuffer(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;III)V",
                    ordinal = 0,
                    shift = At.Shift.AFTER
            )
    )
    private void musicBattle$finishHighlightMaskAfterBaseModel(
            T entity,
            float entityYaw,
            float partialTicks,
            PoseStack poseStack,
            MultiBufferSource buffer,
            int packedLight,
            CallbackInfo callbackInfo
    ) {
        ClientBattleController.finishModelHighlightMask(entity, this.model, buffer);
    }

    @Inject(
            method = "render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/vertex/PoseStack;popPose()V",
                    ordinal = 0,
                    shift = At.Shift.BEFORE
            )
    )
    private void musicBattle$finishEntityFxMask(
            T entity,
            float entityYaw,
            float partialTicks,
            PoseStack poseStack,
            MultiBufferSource buffer,
            int packedLight,
            CallbackInfo callbackInfo
    ) {
        ClientBattleController.finishFxEntityMask(entity, buffer);
    }

    @Inject(
            method = "render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At("TAIL")
    )
    private void musicBattle$restoreHumanoidModelAfterRender(
            T entity,
            float entityYaw,
            float partialTicks,
            PoseStack poseStack,
            MultiBufferSource buffer,
            int packedLight,
            CallbackInfo callbackInfo
    ) {
        if (!(entity instanceof AbstractClientPlayer) && this.model instanceof HumanoidModel<?> humanoidModel) {
            ClientHumanoidAnimatorBridge.finishPreparedAnimation(entity, humanoidModel);
        }
    }
}

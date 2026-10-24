package ru.gora.musicbattle.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import java.util.ArrayDeque;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;
import ru.gora.musicbattle.client.ClientBattleController;

/** Adds vanilla and NeoForge armor models to the player's ordered highlight. */
@Mixin(HumanoidArmorLayer.class)
public abstract class HumanoidArmorLayerMixin {
    @Unique
    private static final ThreadLocal<ArrayDeque<LivingEntity>> MUSIC_BATTLE$ARMOR_ENTITIES =
            ThreadLocal.withInitial(ArrayDeque::new);
    @Unique
    private static final ThreadLocal<ArrayDeque<Float>> MUSIC_BATTLE$ARMOR_PARTIAL_TICKS =
            ThreadLocal.withInitial(ArrayDeque::new);
    @Unique
    private static final ThreadLocal<ArrayDeque<Boolean>> MUSIC_BATTLE$ARMOR_PIECE_GLOW_SUBMITTED =
            ThreadLocal.withInitial(ArrayDeque::new);

    @Inject(
            method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/world/entity/LivingEntity;FFFFFF)V",
            at = @At("HEAD")
    )
    private void musicBattle$beginArmorRender(
            PoseStack poseStack,
            MultiBufferSource buffer,
            int packedLight,
            LivingEntity livingEntity,
            float limbSwing,
            float limbSwingAmount,
            float partialTicks,
            float ageInTicks,
            float netHeadYaw,
            float headPitch,
            CallbackInfo callbackInfo
    ) {
        MUSIC_BATTLE$ARMOR_ENTITIES.get().push(livingEntity);
        MUSIC_BATTLE$ARMOR_PARTIAL_TICKS.get().push(partialTicks);
    }

    @Inject(
            method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/world/entity/LivingEntity;FFFFFF)V",
            at = @At("RETURN")
    )
    private void musicBattle$endArmorRender(
            PoseStack poseStack,
            MultiBufferSource buffer,
            int packedLight,
            LivingEntity livingEntity,
            float limbSwing,
            float limbSwingAmount,
            float partialTicks,
            float ageInTicks,
            float netHeadYaw,
            float headPitch,
            CallbackInfo callbackInfo
    ) {
        ArrayDeque<LivingEntity> entities = MUSIC_BATTLE$ARMOR_ENTITIES.get();
        ArrayDeque<Float> partialTickStack = MUSIC_BATTLE$ARMOR_PARTIAL_TICKS.get();
        if (!entities.isEmpty() && entities.peek() == livingEntity) {
            entities.pop();
            if (!partialTickStack.isEmpty()) {
                partialTickStack.pop();
            }
        }
    }

    @Inject(
            method = "renderArmorPiece(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/entity/EquipmentSlot;ILnet/minecraft/client/model/HumanoidModel;FFFFFF)V",
            at = @At("HEAD")
    )
    private void musicBattle$beginArmorPiece(
            PoseStack poseStack,
            MultiBufferSource buffer,
            LivingEntity livingEntity,
            EquipmentSlot slot,
            int packedLight,
            HumanoidModel<?> armorModel,
            float limbSwing,
            float limbSwingAmount,
            float partialTick,
            float ageInTicks,
            float netHeadYaw,
            float headPitch,
            CallbackInfo callbackInfo
    ) {
        MUSIC_BATTLE$ARMOR_PIECE_GLOW_SUBMITTED.get().push(Boolean.FALSE);
    }

    @Inject(
            method = "renderArmorPiece(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/entity/EquipmentSlot;ILnet/minecraft/client/model/HumanoidModel;FFFFFF)V",
            at = @At("RETURN")
    )
    private void musicBattle$endArmorPiece(
            PoseStack poseStack,
            MultiBufferSource buffer,
            LivingEntity livingEntity,
            EquipmentSlot slot,
            int packedLight,
            HumanoidModel<?> armorModel,
            float limbSwing,
            float limbSwingAmount,
            float partialTick,
            float ageInTicks,
            float netHeadYaw,
            float headPitch,
            CallbackInfo callbackInfo
    ) {
        ArrayDeque<Boolean> contexts = MUSIC_BATTLE$ARMOR_PIECE_GLOW_SUBMITTED.get();
        if (!contexts.isEmpty()) {
            contexts.pop();
        }
    }

    @Inject(
            method = "renderModel(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/client/model/Model;ILnet/minecraft/resources/ResourceLocation;)V",
            at = @At("HEAD")
    )
    private void musicBattle$renderArmorHighlight(
            PoseStack poseStack,
            MultiBufferSource buffer,
            int packedLight,
            Model armorModel,
            int dyeColor,
            ResourceLocation texture,
            CallbackInfo callbackInfo
    ) {
        LivingEntity entity = musicBattle$currentArmorEntity();
        if (entity == null || !musicBattle$claimArmorPieceGlow()) return;
        ClientBattleController.renderModelHighlightInRenderer(entity, armorModel, poseStack, buffer,
                texture, packedLight, musicBattle$currentArmorPartialTick());
    }

    @ModifyArgs(
            method = "renderModel(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/client/model/Model;ILnet/minecraft/resources/ResourceLocation;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/model/Model;renderToBuffer(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;III)V"
            )
    )
    private void musicBattle$applyFullBrightArmor(Args args) {
        LivingEntity entity = musicBattle$currentArmorEntity();
        if (entity == null) return;
        args.set(2, ClientBattleController.applyModelHighlightLight(
                entity, musicBattle$currentArmorPartialTick(), (int) args.get(2)));
    }

    @Inject(
            method = "renderModel(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/client/model/Model;ILnet/minecraft/resources/ResourceLocation;)V",
            at = @At("RETURN")
    )
    private void musicBattle$finishArmorHighlight(
            PoseStack poseStack,
            MultiBufferSource buffer,
            int packedLight,
            Model armorModel,
            int dyeColor,
            ResourceLocation texture,
            CallbackInfo callbackInfo
    ) {
        LivingEntity entity = musicBattle$currentArmorEntity();
        if (entity != null) {
            ClientBattleController.finishModelHighlightMask(entity, armorModel, buffer);
        }
    }

    @Unique
    private static LivingEntity musicBattle$currentArmorEntity() {
        ArrayDeque<LivingEntity> entities = MUSIC_BATTLE$ARMOR_ENTITIES.get();
        return entities.isEmpty() ? null : entities.peek();
    }

    @Unique
    private static float musicBattle$currentArmorPartialTick() {
        ArrayDeque<Float> partialTicks = MUSIC_BATTLE$ARMOR_PARTIAL_TICKS.get();
        return partialTicks.isEmpty() ? 0.0F : partialTicks.peek();
    }

    @Unique
    private static boolean musicBattle$claimArmorPieceGlow() {
        ArrayDeque<Boolean> submitted = MUSIC_BATTLE$ARMOR_PIECE_GLOW_SUBMITTED.get();
        if (submitted.isEmpty()) {
            return true;
        }
        if (submitted.peek()) {
            return false;
        }
        // One armor slot can render several material textures (leather base +
        // overlay), but all of them use the same visible model geometry.
        submitted.pop();
        submitted.push(Boolean.TRUE);
        return true;
    }
}

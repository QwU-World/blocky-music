package ru.gora.musicbattle.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import ru.gora.musicbattle.client.ClientBattleMicrophone;

/**
 * A battle fighter is drawn with a microphone in the main hand, seen by everyone else and in the fighter's own third
 * person. Players and humanoid mobs all draw their hands through this layer. Only what is drawn changes.
 */
@Mixin(ItemInHandLayer.class)
public abstract class ItemInHandLayerMixin {
    @WrapOperation(
            method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/world/entity/LivingEntity;FFFFFF)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;getMainHandItem()Lnet/minecraft/world/item/ItemStack;"))
    private ItemStack musicBattle$battleMicrophone(LivingEntity entity, Operation<ItemStack> original) {
        return ClientBattleMicrophone.mainHand(entity, original.call(entity));
    }
}

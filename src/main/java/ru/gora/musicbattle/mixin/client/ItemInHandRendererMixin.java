package ru.gora.musicbattle.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import ru.gora.musicbattle.client.ClientBattleMicrophone;

/**
 * The fighter's own first-person hand shows the battle microphone too. The swap is made where the renderer picks
 * up the held item each tick, so the usual lowering and raising of the hand plays when the battle starts and ends.
 */
@Mixin(ItemInHandRenderer.class)
public abstract class ItemInHandRendererMixin {
    @WrapOperation(
            method = "tick()V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;getMainHandItem()Lnet/minecraft/world/item/ItemStack;"))
    private ItemStack musicBattle$battleMicrophone(LocalPlayer player, Operation<ItemStack> original) {
        return ClientBattleMicrophone.mainHand(player, original.call(player));
    }
}

package ru.gora.musicbattle.mixin.client;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.world.InteractionHand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ru.gora.musicbattle.client.ClientBattleMicrophone;

/**
 * The arm holding the battle microphone takes the plain holding pose. The pose is chosen from the real item, so an
 * empty hand would hang the microphone at the player's side, and a bow or crossbow would aim it.
 */
@Mixin(PlayerRenderer.class)
public abstract class PlayerRendererMixin {
    @Inject(method = "getArmPose", at = @At("HEAD"), cancellable = true)
    private static void musicBattle$battleMicrophonePose(AbstractClientPlayer player, InteractionHand hand,
                                                        CallbackInfoReturnable<HumanoidModel.ArmPose> cir) {
        if (hand == InteractionHand.MAIN_HAND && ClientBattleMicrophone.holdsMicrophone(player)) {
            cir.setReturnValue(HumanoidModel.ArmPose.ITEM);
        }
    }
}

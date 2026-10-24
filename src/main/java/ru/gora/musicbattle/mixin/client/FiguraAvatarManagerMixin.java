package ru.gora.musicbattle.mixin.client;

import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ru.gora.musicbattle.client.ClientBattleController;

/** Optional Figura bridge: battle participants use their vanilla player model and skin. */
@Pseudo
@Mixin(targets = "org.figuramc.figura.avatar.AvatarManager", remap = false)
public abstract class FiguraAvatarManagerMixin {
    @Inject(
            method = "getAvatar(Lnet/minecraft/world/entity/Entity;)Lorg/figuramc/figura/avatar/Avatar;",
            at = @At("HEAD"),
            cancellable = true,
            require = 0,
            remap = false
    )
    private static void musicBattle$useVanillaAvatarDuringBattle(Entity entity, CallbackInfoReturnable<Object> callback) {
        if (ClientBattleController.shouldForceVanillaPlayer(entity)) {
            callback.setReturnValue(null);
        }
    }
}

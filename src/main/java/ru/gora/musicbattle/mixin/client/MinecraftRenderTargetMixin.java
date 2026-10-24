package ru.gora.musicbattle.mixin.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ru.gora.musicbattle.client.DjCameraPreviewRenderer;

/** Redirects only the nested DJ monitor pass; the real main target is never mutated. */
@Mixin(Minecraft.class)
abstract class MinecraftRenderTargetMixin {
    @Inject(method = "getMainRenderTarget", at = @At("HEAD"), cancellable = true)
    private void musicBattle$redirectDjMonitorTarget(CallbackInfoReturnable<RenderTarget> callback) {
        RenderTarget target = DjCameraPreviewRenderer.redirectedMainTarget();
        if (target != null) {
            callback.setReturnValue(target);
        }
    }
}

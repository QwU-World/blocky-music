package ru.gora.musicbattle.mixin.client;

import net.minecraft.client.KeyboardHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.gora.musicbattle.client.ClientInputIsolation;

@Mixin(KeyboardHandler.class)
public abstract class KeyboardHandlerMixin {
    @Inject(method = "keyPress", at = @At("HEAD"), cancellable = true)
    private void musicBattle$isolateParticipantKeys(long windowPointer, int key, int scanCode, int action, int modifiers, CallbackInfo callback) {
        if (ClientInputIsolation.handleBattleKey(key, scanCode, action)) {
            callback.cancel();
        }
    }

    @Inject(
            method = "keyPress",
            at = @At(value = "INVOKE", target = "Lnet/neoforged/neoforge/client/ClientHooks;onKeyInput(IIII)V"),
            cancellable = true
    )
    private void musicBattle$hideEditorKeysFromGlobalHandlers(long windowPointer, int key, int scanCode, int action, int modifiers, CallbackInfo callback) {
        if (ClientInputIsolation.suppressGlobalKeyEvent(key)) {
            callback.cancel();
        }
    }
}

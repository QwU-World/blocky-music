package ru.gora.musicbattle.mixin.client;

import java.nio.file.Path;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.neoforged.neoforge.client.ClientHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.gora.musicbattle.client.ClientInputIsolation;
import ru.gora.musicbattle.client.workshop.WorkshopClientHooks;

@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {
    @Unique
    private boolean musicBattle$suppressCurrentGlobalMouseEvent;

    @Inject(method = "onPress", at = @At("HEAD"), cancellable = true)
    private void musicBattle$isolateParticipantMouseButtons(long windowPointer, int button, int action, int modifiers, CallbackInfo callback) {
        if (ClientInputIsolation.handleBattleMouseButton(button, action)) {
            callback.cancel();
        }
    }

    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
    private void musicBattle$blockParticipantMouseWheel(long windowPointer, double xOffset, double yOffset, CallbackInfo callback) {
        if (ClientInputIsolation.blockBattleMouseWheel()) {
            callback.cancel();
        }
    }

    @Inject(method = "turnPlayer", at = @At("HEAD"), cancellable = true)
    private void musicBattle$lockParticipantCamera(double movementTime, CallbackInfo callback) {
        if (ClientInputIsolation.blockBattleCameraTurn()) {
            callback.cancel();
        }
    }

    @Redirect(
            method = "onPress",
            at = @At(value = "INVOKE", target = "Lnet/neoforged/neoforge/client/ClientHooks;onMouseButtonPre(III)Z")
    )
    private boolean musicBattle$hideMouseFromGlobalPreHandlers(int button, int action, int modifiers) {
        musicBattle$suppressCurrentGlobalMouseEvent = ClientInputIsolation.suppressGlobalMouseButtonEvent();
        if (musicBattle$suppressCurrentGlobalMouseEvent) {
            // False lets the active Screen receive the click while omitting the
            // global mouse event used by ordinary mod bindings.
            return false;
        }
        return ClientHooks.onMouseButtonPre(button, action, modifiers);
    }

    @Redirect(
            method = "onPress",
            at = @At(value = "INVOKE", target = "Lnet/neoforged/neoforge/client/ClientHooks;onMouseButtonPost(III)V")
    )
    private void musicBattle$hideMouseFromGlobalPostHandlers(int button, int action, int modifiers) {
        if (!musicBattle$suppressCurrentGlobalMouseEvent) {
            ClientHooks.onMouseButtonPost(button, action, modifiers);
        }
        musicBattle$suppressCurrentGlobalMouseEvent = false;
    }

    /**
     * {@code .blockymap} files dropped anywhere (in the world, the pause menu, the title screen) go to the Workshop
     * install flow; screens that handle drops themselves are left alone. Other files reach the screen as before.
     */
    @Inject(method = "onDrop", at = @At("HEAD"), cancellable = true)
    private void musicBattle$installDroppedWorkshopPackages(long windowPointer, List<Path> paths, int failedCount, CallbackInfo callback) {
        List<Path> rest = WorkshopClientHooks.interceptDrop(paths);
        if (rest == null) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen != null && !rest.isEmpty()) {
            minecraft.screen.onFilesDrop(rest);
        }
        if (failedCount > 0) {
            SystemToast.onFileDropFailure(minecraft, failedCount);
        }
        callback.cancel();
    }
}

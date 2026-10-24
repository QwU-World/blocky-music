package ru.gora.musicbattle.mixin.client;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.gora.musicbattle.client.CameraEditorClient;
import ru.gora.musicbattle.client.ClientBattleController;

/**
 * Applies Blocky Music's exact optical camera position after vanilla has added
 * an entity eye offset. Spectator mode keeps the local player as camera entity
 * so movement, gameplay and coverage/volume checks still follow their body;
 * the editor similarly uses its ArmorStand only as an authoritative handle.
 */
@Mixin(Camera.class)
public abstract class CameraMixin {
    @Shadow
    private boolean detached;

    @Shadow
    protected abstract void setPosition(Vec3 position);

    @Inject(method = "setup", at = @At("TAIL"))
    private void musicBattle$applySpectatorViewPosition(BlockGetter level, Entity entity,
                                                        boolean detached, boolean reverse,
                                                        float partialTick, CallbackInfo ci) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.gameRenderer == null
                || minecraft.gameRenderer.getMainCamera() != (Object) this) {
            return;
        }
        Vec3 editorOverride = CameraEditorClient.cameraPositionOverride();
        if (editorOverride != null) {
            // The temporary ArmorStand is only a server-authoritative camera
            // handle. Its eye height must not alter the optical transform that
            // is stored by the block camera and shared with DJ previews.
            setPosition(editorOverride);
            return;
        }
        Vec3 activeOverride = ClientBattleController.activeBattleCameraPositionOverride(partialTick);
        if (activeOverride != null) {
            setPosition(activeOverride);
            return;
        }
        Vec3 override = ClientBattleController.spectatorCameraPositionOverride(partialTick);
        if (override != null) {
            setPosition(override);
            // Render the local player's model in the remote shot even though
            // the player remains Minecraft's camera entity for movement.
            this.detached = true;
        }
    }
}

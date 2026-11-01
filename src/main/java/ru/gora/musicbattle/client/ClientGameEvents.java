package ru.gora.musicbattle.client;

import net.neoforged.bus.api.EventPriority;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPauseChangeEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RenderArmEvent;
import net.neoforged.neoforge.client.event.RenderHandEvent;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.RenderLivingEvent;
import net.neoforged.neoforge.client.event.RenderNameTagEvent;
import net.neoforged.neoforge.client.event.RenderPlayerEvent;
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.event.SelectMusicEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.client.event.sound.PlayStreamingSourceEvent;
import ru.gora.musicbattle.MusicBattleMod;

@EventBusSubscriber(modid = MusicBattleMod.MOD_ID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class ClientGameEvents {
    private ClientGameEvents() {
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onRenderFrame(RenderFrameEvent.Pre event) {
        ClientBattleController.beginRenderFrame();
    }

    @SubscribeEvent
    public static void onClientTickPre(ClientTickEvent.Pre event) {
        ClientInputIsolation.tick();
        ClientModelGlowEngine.prepareStencilTarget();
        CameraEditorClient.preTick();
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        ClientBattleController.handleSpectatorCameraKey();
        ru.gora.musicbattle.client.workshop.WorkshopClientHooks.tick();
        ClientBattleController.tick();
        ClientTrackPublisher.tick();
        CameraEditorClient.tick();
    }

    /**
     * At the end of the frame in which the game paused or resumed, the frame Minecraft paused or resumed every sound
     * in: the battle clock stops and starts with the music here, not up to a tick later in the client tick.
     */
    @SubscribeEvent
    public static void onClientPauseChanged(ClientPauseChangeEvent.Post event) {
        ClientBattleController.onGamePauseChanged(event.isPaused());
    }

    /**
     * Sooner still, where Minecraft really stops and starts the sounds: right after the pause menu is set up, and right
     * after a menu closes onto the game. Last of every listener, nearest those instants; see
     * ClientBattleController#onScreenShown and #onScreenClosing.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onScreenShown(ScreenEvent.Init.Post event) {
        ClientBattleController.onScreenShown(event.getScreen());
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onScreenOpeningLast(ScreenEvent.Opening event) {
        ClientBattleController.onScreenOpening(event.getNewScreen());
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onScreenClosing(ScreenEvent.Closing event) {
        ClientBattleController.onScreenClosing(event.getScreen());
    }

    @SubscribeEvent
    public static void onClientPlayerClone(ClientPlayerNetworkEvent.Clone event) {
        CameraEditorClient.resetForWorldTransition();
        ClientBattleController.resetForWorldTransition();
    }

    @SubscribeEvent
    public static void onClientLogin(ClientPlayerNetworkEvent.LoggingIn event) {
        // What this player already has, worked out once on joining rather than when something happens to ask.
        // The index is how the client answers "do you have this song?" during a battle's preparation, and an index
        // that has never been built answers no - so a map they installed from the Workshop was downloaded all over
        // again, from the server, while they waited.
        ClientTrackAssets.refreshHashIndex();
    }

    @SubscribeEvent
    public static void onClientLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        CameraEditorClient.resetForWorldTransition();
        ClientBattleController.resetForWorldTransition();
        ClientTrackPublisher.reset();
        // The Workshop forgets everything the server told it: the tab hides, the button greys out again.
        ru.gora.musicbattle.client.workshop.WorkshopClientHooks.onDisconnect();
        ru.gora.musicbattle.client.ui.SkinHead.clear();
    }

    @SubscribeEvent
    public static void onStreamingSoundStarted(PlayStreamingSourceEvent event) {
        ClientBattleController.onStreamingSoundStarted(event.getSound(), event.getChannel());
        TrackEditorScreen.onStreamingSoundStarted(event.getSound());
        TrackGameplayPreviewScreen.onStreamingSoundStarted(event.getSound());
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void onSelectMusic(SelectMusicEvent event) {
        if (ClientBattleController.shouldSuppressVanillaMusic()) {
            // NeoForge treats null as an explicit request to stop the current
            // situational track. Once battle audio is no longer audible, the
            // normal selection is returned on the very next music tick.
            event.overrideMusic(null);
        }
    }

    @SubscribeEvent
    public static void onCameraAngles(ViewportEvent.ComputeCameraAngles event) {
        if (!CameraEditorClient.applyCameraAngles(event)) {
            ClientBattleController.applyCameraAngles(event);
        }
    }

    @SubscribeEvent
    public static void onFov(ViewportEvent.ComputeFov event) {
        if (!CameraEditorClient.applyFov(event)) {
            ClientBattleController.applyFovPulse(event);
        }
    }

    @SubscribeEvent
    public static void onMouseScroll(InputEvent.MouseScrollingEvent event) {
        CameraEditorClient.onMouseScroll(event);
    }

    @SubscribeEvent
    public static void onScreenOpening(ScreenEvent.Opening event) {
        CameraEditorClient.onScreenOpening(event);
    }

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        CameraEditorClient.render(event);
    }

    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        ClientBattleController.addOptionsButton(event);
        ru.gora.musicbattle.client.workshop.WorkshopClientHooks.addTitleButton(event);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onRenderHandEarly(RenderHandEvent event) {
        ClientBattleController.hideHand(event);
        CameraEditorClient.hideHand(event);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void onRenderHand(RenderHandEvent event) {
        ClientBattleController.hideHand(event);
        CameraEditorClient.hideHand(event);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onRenderArmEarly(RenderArmEvent event) {
        ClientBattleController.hideArm(event);
        CameraEditorClient.hideArm(event);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void onRenderArm(RenderArmEvent event) {
        ClientBattleController.hideArm(event);
        CameraEditorClient.hideArm(event);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onRenderGuiLayer(RenderGuiLayerEvent.Pre event) {
        ClientBattleController.hideGuiLayer(event);
    }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (DjCameraPreviewRenderer.isRenderingPreview()) {
            // The nested camera must stay isolated from normal battle panels/model FX, but it
            // still needs its own block-only source. It is composited later in the GUI effect
            // order, never baked into the raw camera target here.
            if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
                DjCameraPreviewRenderer.captureObjectOutlineBlocks();
            }
            // AFTER_LEVEL draws the real spectator panels into the isolated camera target.
            // The controller uses the scoped draft state to omit them when HIDE_BATTLE_PANELS
            // is active; no regular player-camera panel pass is entered from here.
            ClientBattleController.renderWorldPanels(event);
            return;
        }
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_SKY) {
            ClientModelGlowEngine.beginFrame();
            ClientFxShaderEngine.beginFrame();
        }
        ClientBattleController.renderWorldObjectOutline(event);
        ClientBattleController.renderWorldPanels(event);
    }

    @SubscribeEvent
    public static void onRenderPlayer(RenderPlayerEvent.Pre event) {
        ClientBattleController.applyPlayerAnimation(event);
    }

    @SubscribeEvent
    public static void onRenderPlayerPost(RenderPlayerEvent.Post event) {
        if (DjCameraPreviewRenderer.isRenderingPreview()) {
            return;
        }
        ClientBattleController.renderPlayerEffects(event);
    }

    @SubscribeEvent
    public static void onRenderLiving(RenderLivingEvent.Pre<?, ?> event) {
        ClientBattleController.applyHumanoidAnimation(event);
    }

    @SubscribeEvent
    public static void onRenderLivingPost(RenderLivingEvent.Post<?, ?> event) {
        if (DjCameraPreviewRenderer.isRenderingPreview()) {
            return;
        }
        ClientBattleController.renderLivingEffects(event);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onRenderNameTag(RenderNameTagEvent event) {
        if (ClientBattleController.shouldHideEntityNameTags()) {
            event.setCanRender(TriState.FALSE);
        }
    }

}

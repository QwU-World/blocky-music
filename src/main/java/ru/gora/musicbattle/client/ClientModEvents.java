package ru.gora.musicbattle.client;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;
import net.neoforged.neoforge.client.event.RegisterColorHandlersEvent;
import java.io.IOException;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.client.resources.PlayerSkin;
import ru.gora.musicbattle.MusicBattleMod;
import ru.gora.musicbattle.client.ui.RoundedImage;
import ru.gora.musicbattle.client.ui.UiSdf;
import ru.gora.musicbattle.registry.ModBlockEntities;
import ru.gora.musicbattle.registry.ModEntities;
import ru.gora.musicbattle.registry.ModItems;

@EventBusSubscriber(modid = MusicBattleMod.MOD_ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ClientModEvents {
    private ClientModEvents() {
    }

    @SubscribeEvent
    public static void setupClient(FMLClientSetupEvent event) {
        ClientPlayerAnimatorBridge.register(event);
        // What a killed game left in the mod's temporary folder (a picture for "Publish to server", a song being
        // converted): nothing in it outlives the launch that made it.
        int leftovers = ru.gora.musicbattle.server.MusicBattleTracks.clearTemporaryDirectory();
        if (leftovers > 0) {
            MusicBattleMod.LOGGER.info("Blocky Music removed {} leftover temporary file(s).", leftovers);
        }
    }

    @SubscribeEvent
    public static void registerGuiLayers(RegisterGuiLayersEvent event) {
        event.registerAboveAll(MusicBattleMod.id("battle_overlay"), ClientBattleController::render);
    }

    @SubscribeEvent
    public static void registerKeyMappings(RegisterKeyMappingsEvent event) {
        MusicBattleKeys.register(event);
    }

    @SubscribeEvent
    public static void registerItemColors(RegisterColorHandlersEvent.Item event) {
        // Keep the custom summon behavior, but use Minecraft's two-layer spawn-egg model.
        event.register((stack, tintIndex) -> tintIndex == 0 ? 0xFF7751A3 : 0xFF23212B,
                ModItems.TEST_RAP_GOD_MASTER.get());
    }

    @SubscribeEvent
    public static void registerShaders(RegisterShadersEvent event) throws IOException {
        ClientFxShaderEngine.registerShaders(event);
        // Workshop UI toolkit. Both are guarded: a shader a driver rejects leaves flat vanilla fills, never a failed reload.
        UiSdf.register(event);
        RoundedImage.register(event);
    }

    @SubscribeEvent
    public static void registerEntityRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(ModEntities.TEST_RAP_GOD_MASTER.get(), TestRapGodMasterRenderer::new);
        event.registerEntityRenderer(ModEntities.SMILEY_BALL.get(), SmileyBallRenderer::new);
        event.registerBlockEntityRenderer(ModBlockEntities.BATTLE_CAMERA.get(), BattleCameraBlockEntityRenderer::new);
        event.registerBlockEntityRenderer(ModBlockEntities.SPEAKER.get(), SpeakerBlockEntityRenderer::new);
        event.registerBlockEntityRenderer(ModBlockEntities.DJ_PANEL.get(), DjPanelBlockEntityRenderer::new);
    }

    @SubscribeEvent
    public static void addEntityLayers(EntityRenderersEvent.AddLayers event) {
        for (PlayerSkin.Model skin : event.getSkins()) {
            PlayerRenderer renderer = event.getSkin(skin);
            if (renderer != null) {
                renderer.addLayer(new BattleMicrophoneLayer(renderer));
            }
        }
    }

    @SubscribeEvent
    public static void registerClientExtensions(RegisterClientExtensionsEvent event) {
        event.registerItem(new IClientItemExtensions() {
            private final BattleCameraItemRenderer renderer = new BattleCameraItemRenderer();

            @Override
            public BattleCameraItemRenderer getCustomRenderer() {
                return renderer;
            }
        }, ModItems.BATTLE_CAMERA.get());
        event.registerItem(new IClientItemExtensions() {
            private final SpeakerItemRenderer renderer = new SpeakerItemRenderer();

            @Override
            public SpeakerItemRenderer getCustomRenderer() {
                return renderer;
            }
        }, ModItems.SPEAKER.get(), ModItems.HORIZONTAL_SPEAKER.get());
        event.registerItem(new IClientItemExtensions() {
            private final DjPanelItemRenderer renderer = new DjPanelItemRenderer();

            @Override
            public DjPanelItemRenderer getCustomRenderer() {
                return renderer;
            }
        }, ModItems.DJ_PANEL.get());
        event.registerItem(new IClientItemExtensions() {
            private final SpeakerWireItemRenderer renderer = new SpeakerWireItemRenderer();

            @Override
            public SpeakerWireItemRenderer getCustomRenderer() {
                return renderer;
            }
        }, ModItems.SPEAKER_WIRE.get());
    }
}

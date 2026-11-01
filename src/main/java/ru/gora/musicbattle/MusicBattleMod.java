package ru.gora.musicbattle;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.CreativeModeTabs;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.gora.musicbattle.command.MusicBattleCommands;
import ru.gora.musicbattle.network.MusicBattleNetworking;
import ru.gora.musicbattle.registry.ModCreativeTabs;
import ru.gora.musicbattle.registry.ModBlocks;
import ru.gora.musicbattle.registry.ModBlockEntities;
import ru.gora.musicbattle.registry.ModEntities;
import ru.gora.musicbattle.registry.ModItems;
import ru.gora.musicbattle.registry.ModSounds;
import ru.gora.musicbattle.server.BattleManager;
import ru.gora.musicbattle.server.CameraEditorManager;
import ru.gora.musicbattle.server.MusicBattleTracks;
import ru.gora.musicbattle.server.MusicBattleServerConfig;
import ru.gora.musicbattle.server.DjPlaybackManager;
import ru.gora.musicbattle.server.workshop.ServerWorkshop;
import ru.gora.musicbattle.server.workshop.WorkshopPermissions;

@Mod(MusicBattleMod.MOD_ID)
public final class MusicBattleMod {
    public static final String MOD_ID = "music_battle";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public MusicBattleMod(IEventBus modEventBus, ModContainer modContainer) {
        ModBlocks.register(modEventBus);
        ModBlockEntities.register(modEventBus);
        ModItems.register(modEventBus);
        ModEntities.register(modEventBus);
        ModSounds.register(modEventBus);
        ModCreativeTabs.register(modEventBus);
        modEventBus.addListener(ModEntities::registerAttributes);
        modEventBus.addListener(MusicBattleNetworking::register);
        modEventBus.addListener(this::addCreativeItems);
        modContainer.registerConfig(ModConfig.Type.SERVER, MusicBattleServerConfig.SPEC);

        MusicBattleTracks.loadTracks();

        NeoForge.EVENT_BUS.addListener(MusicBattleCommands::register);
        NeoForge.EVENT_BUS.addListener(WorkshopPermissions::gatherNodes);
        NeoForge.EVENT_BUS.addListener(MusicBattleTracks::onServerAboutToStart);
        // After the tracks are bound to the world folder: the workshop index lives next to them.
        NeoForge.EVENT_BUS.addListener(ServerWorkshop::onServerAboutToStart);
        NeoForge.EVENT_BUS.addListener(ServerWorkshop::onServerStopping);
        NeoForge.EVENT_BUS.addListener(ServerWorkshop::onPlayerLoggedIn);
        NeoForge.EVENT_BUS.addListener(ServerWorkshop::onPlayerLoggedOut);
        NeoForge.EVENT_BUS.addListener(MusicBattleTracks::onServerStopped);
        NeoForge.EVENT_BUS.addListener(BattleManager::onServerTick);
        NeoForge.EVENT_BUS.addListener(BattleManager::onServerStopping);
        NeoForge.EVENT_BUS.addListener(BattleManager::onEntityJoinLevel);
        NeoForge.EVENT_BUS.addListener(BattleManager::onPlayerLoggedIn);
        NeoForge.EVENT_BUS.addListener(BattleManager::onPlayerLoggedOut);
        NeoForge.EVENT_BUS.addListener(BattleManager::onPlayerChangedDimension);
        NeoForge.EVENT_BUS.addListener(BattleManager::onLivingIncomingDamage);
        NeoForge.EVENT_BUS.addListener(BattleManager::onEntityInteractSpecific);
        NeoForge.EVENT_BUS.addListener(BattleManager::onEntityInteract);
        NeoForge.EVENT_BUS.addListener(DjPlaybackManager::onServerTick);
        NeoForge.EVENT_BUS.addListener(DjPlaybackManager::onPlayerLoggedOut);
        NeoForge.EVENT_BUS.addListener(DjPlaybackManager::onPlayerRespawned);
        NeoForge.EVENT_BUS.addListener(DjPlaybackManager::onPlayerChangedDimension);
        NeoForge.EVENT_BUS.addListener(DjPlaybackManager::onServerStopping);
        NeoForge.EVENT_BUS.addListener(ru.gora.musicbattle.server.DjWorkshopCache::onServerStarted);
        NeoForge.EVENT_BUS.addListener(CameraEditorManager::onServerTick);
        NeoForge.EVENT_BUS.addListener(CameraEditorManager::onPlayerLoggedOut);
        NeoForge.EVENT_BUS.addListener(CameraEditorManager::onServerStopping);
    }

    private void addCreativeItems(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES
                || event.getTabKey() == CreativeModeTabs.INGREDIENTS) {
            event.accept(ModItems.MICROPHONE.get());
            event.accept(ModItems.MUSIC_LINK.get());
            event.accept(ModItems.TEST_RAP_GOD_MASTER.get());
            event.accept(ModItems.SMILEY_BALL_SPAWN_EGG.get());
            event.accept(ModItems.RAP_GOD_CONFIG_WAND.get());
            event.accept(ModItems.BATTLE_CAMERA.get());
            event.accept(ModItems.SPEAKER.get());
            event.accept(ModItems.HORIZONTAL_SPEAKER.get());
            event.accept(ModItems.DJ_PANEL.get());
            event.accept(ModItems.SPEAKER_WIRE.get());
        }
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }
}

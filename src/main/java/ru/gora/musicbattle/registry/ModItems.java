package ru.gora.musicbattle.registry;

import java.util.function.Supplier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.BlockItem;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;
import ru.gora.musicbattle.MusicBattleMod;
import ru.gora.musicbattle.item.MicrophoneItem;
import ru.gora.musicbattle.item.MusicLinkItem;
import ru.gora.musicbattle.item.RapGodConfigWandItem;
import ru.gora.musicbattle.item.SoloMicrophoneItem;
import ru.gora.musicbattle.item.TestRapGodMasterItem;
import ru.gora.musicbattle.item.SpeakerWireItem;
import ru.gora.musicbattle.item.SmileyBallSpawnEggItem;

public final class ModItems {
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MusicBattleMod.MOD_ID);

    public static final Supplier<Item> MICROPHONE = ITEMS.registerItem(
            "microphone",
            MicrophoneItem::new,
            new Item.Properties().stacksTo(1)
    );

    public static final Supplier<Item> SOLO_MICROPHONE = ITEMS.registerItem(
            "solo_microphone",
            SoloMicrophoneItem::new,
            new Item.Properties().stacksTo(1)
    );

    public static final Supplier<Item> MUSIC_LINK = ITEMS.registerItem(
            "music_link",
            MusicLinkItem::new,
            new Item.Properties().stacksTo(1)
    );

    public static final Supplier<Item> TEST_RAP_GOD_MASTER = ITEMS.registerItem(
            "test_rap_god_master",
            TestRapGodMasterItem::new,
            new Item.Properties().stacksTo(16)
    );

    public static final Supplier<Item> RAP_GOD_CONFIG_WAND = ITEMS.registerItem(
            "rap_god_config_wand",
            RapGodConfigWandItem::new,
            new Item.Properties().stacksTo(1)
    );

    public static final Supplier<BlockItem> BATTLE_CAMERA = ITEMS.registerSimpleBlockItem(
            ModBlocks.BATTLE_CAMERA,
            new Item.Properties()
    );

    public static final Supplier<BlockItem> SPEAKER = ITEMS.registerSimpleBlockItem(
            ModBlocks.SPEAKER,
            new Item.Properties()
    );

    public static final Supplier<BlockItem> HORIZONTAL_SPEAKER = ITEMS.registerSimpleBlockItem(
            ModBlocks.HORIZONTAL_SPEAKER,
            new Item.Properties()
    );

    public static final Supplier<BlockItem> DJ_PANEL = ITEMS.registerSimpleBlockItem(
            ModBlocks.DJ_PANEL,
            new Item.Properties()
    );

    public static final Supplier<Item> SPEAKER_WIRE = ITEMS.registerItem(
            "speaker_wire",
            SpeakerWireItem::new,
            new Item.Properties().stacksTo(1)
    );

    public static final Supplier<Item> SMILEY_BALL_SPAWN_EGG = ITEMS.registerItem(
            "smiley_ball_spawn_egg",
            SmileyBallSpawnEggItem::new,
            new Item.Properties()
    );

    private ModItems() {
    }

    public static void register(IEventBus eventBus) {
        ITEMS.register(eventBus);
    }
}

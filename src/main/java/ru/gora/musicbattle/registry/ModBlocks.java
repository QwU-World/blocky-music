package ru.gora.musicbattle.registry;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;
import ru.gora.musicbattle.MusicBattleMod;
import ru.gora.musicbattle.block.BattleCameraBlock;
import ru.gora.musicbattle.block.SpeakerBlock;
import ru.gora.musicbattle.block.HorizontalSpeakerBlock;
import ru.gora.musicbattle.block.DjPanelBlock;

public final class ModBlocks {
    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MusicBattleMod.MOD_ID);

    public static final DeferredBlock<BattleCameraBlock> BATTLE_CAMERA = BLOCKS.registerBlock(
            "battle_camera",
            BattleCameraBlock::new,
            BlockBehaviour.Properties.ofFullCopy(Blocks.OBSERVER).strength(2.0F).noOcclusion()
    );

    public static final DeferredBlock<SpeakerBlock> SPEAKER = BLOCKS.registerBlock(
            "speaker",
            SpeakerBlock::new,
            BlockBehaviour.Properties.ofFullCopy(Blocks.NOTE_BLOCK).strength(2.5F).noOcclusion()
    );

    public static final DeferredBlock<HorizontalSpeakerBlock> HORIZONTAL_SPEAKER = BLOCKS.registerBlock(
            "horizontal_speaker",
            HorizontalSpeakerBlock::new,
            BlockBehaviour.Properties.ofFullCopy(Blocks.NOTE_BLOCK).strength(2.5F).noOcclusion()
    );

    public static final DeferredBlock<DjPanelBlock> DJ_PANEL = BLOCKS.registerBlock(
            "dj_panel",
            DjPanelBlock::new,
            BlockBehaviour.Properties.ofFullCopy(Blocks.JUKEBOX).strength(2.5F)
    );

    private ModBlocks() {
    }

    public static void register(IEventBus eventBus) {
        BLOCKS.register(eventBus);
    }
}

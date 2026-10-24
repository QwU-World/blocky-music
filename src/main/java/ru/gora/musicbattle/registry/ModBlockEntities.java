package ru.gora.musicbattle.registry;

import java.util.function.Supplier;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;
import ru.gora.musicbattle.MusicBattleMod;
import ru.gora.musicbattle.block.entity.BattleCameraBlockEntity;
import ru.gora.musicbattle.block.entity.SpeakerBlockEntity;
import ru.gora.musicbattle.block.entity.DjPanelBlockEntity;

public final class ModBlockEntities {
    private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, MusicBattleMod.MOD_ID);

    public static final Supplier<BlockEntityType<BattleCameraBlockEntity>> BATTLE_CAMERA = BLOCK_ENTITIES.register(
            "battle_camera",
            () -> BlockEntityType.Builder.of(BattleCameraBlockEntity::new, ModBlocks.BATTLE_CAMERA.get()).build(null)
    );

    public static final Supplier<BlockEntityType<SpeakerBlockEntity>> SPEAKER = BLOCK_ENTITIES.register(
            "speaker",
            () -> BlockEntityType.Builder.of(SpeakerBlockEntity::new,
                    ModBlocks.SPEAKER.get(), ModBlocks.HORIZONTAL_SPEAKER.get()).build(null)
    );

    public static final Supplier<BlockEntityType<DjPanelBlockEntity>> DJ_PANEL = BLOCK_ENTITIES.register(
            "dj_panel",
            () -> BlockEntityType.Builder.of(DjPanelBlockEntity::new, ModBlocks.DJ_PANEL.get()).build(null)
    );

    private ModBlockEntities() {
    }

    public static void register(IEventBus eventBus) {
        BLOCK_ENTITIES.register(eventBus);
    }
}

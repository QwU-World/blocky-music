package ru.gora.musicbattle.registry;

import java.util.function.Supplier;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.registries.DeferredRegister;
import ru.gora.musicbattle.MusicBattleMod;
import ru.gora.musicbattle.entity.TestRapGodMasterEntity;
import ru.gora.musicbattle.entity.SmileyBallEntity;

public final class ModEntities {
    private static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(Registries.ENTITY_TYPE, MusicBattleMod.MOD_ID);

    public static final Supplier<EntityType<TestRapGodMasterEntity>> TEST_RAP_GOD_MASTER = ENTITIES.register(
            "test_rap_god_master",
            () -> EntityType.Builder.of(TestRapGodMasterEntity::new, MobCategory.CREATURE)
                    .sized(0.6F, 1.8F)
                    .clientTrackingRange(12)
                    .build("test_rap_god_master")
    );

    public static final Supplier<EntityType<SmileyBallEntity>> SMILEY_BALL = ENTITIES.register(
            "smiley_ball",
            () -> EntityType.Builder.of(SmileyBallEntity::new, MobCategory.CREATURE)
                    .sized(0.8F, 0.8F)
                    .clientTrackingRange(12)
                    .updateInterval(1)
                    .build("smiley_ball")
    );

    private ModEntities() {
    }

    public static void register(IEventBus eventBus) {
        ENTITIES.register(eventBus);
    }

    public static void registerAttributes(EntityAttributeCreationEvent event) {
        event.put(TEST_RAP_GOD_MASTER.get(), TestRapGodMasterEntity.createAttributes().build());
        event.put(SMILEY_BALL.get(), SmileyBallEntity.createAttributes().build());
    }
}

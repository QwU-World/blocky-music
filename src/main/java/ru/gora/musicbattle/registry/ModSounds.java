package ru.gora.musicbattle.registry;

import java.util.function.Supplier;
import net.minecraft.core.registries.Registries;
import net.minecraft.sounds.SoundEvent;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;
import ru.gora.musicbattle.MusicBattleMod;

public final class ModSounds {
    private static final DeferredRegister<SoundEvent> SOUNDS =
            DeferredRegister.create(Registries.SOUND_EVENT, MusicBattleMod.MOD_ID);

    public static final Supplier<SoundEvent> SMILEY_BALL_HURT = register("smiley_ball_hurt");
    public static final Supplier<SoundEvent> SMILEY_BALL_DEATH = register("smiley_ball_death");

    private ModSounds() {
    }

    private static Supplier<SoundEvent> register(String name) {
        return SOUNDS.register(name, () -> SoundEvent.createVariableRangeEvent(MusicBattleMod.id(name)));
    }

    public static void register(IEventBus eventBus) {
        SOUNDS.register(eventBus);
    }
}

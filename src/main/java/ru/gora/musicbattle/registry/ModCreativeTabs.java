package ru.gora.musicbattle.registry;

import java.util.function.Supplier;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;
import ru.gora.musicbattle.MusicBattleMod;

public final class ModCreativeTabs {
    private static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MusicBattleMod.MOD_ID);

    public static final Supplier<CreativeModeTab> MUSIC_BATTLE = TABS.register("music_battle", () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.music_battle"))
            .icon(() -> new ItemStack(ModItems.MICROPHONE.get()))
            .displayItems((parameters, output) -> {
                output.accept(ModItems.MICROPHONE.get());
                output.accept(ModItems.MUSIC_LINK.get());
                output.accept(ModItems.TEST_RAP_GOD_MASTER.get());
                output.accept(ModItems.SMILEY_BALL_SPAWN_EGG.get());
                output.accept(ModItems.RAP_GOD_CONFIG_WAND.get());
                output.accept(ModItems.BATTLE_CAMERA.get());
                output.accept(ModItems.SPEAKER.get());
                output.accept(ModItems.HORIZONTAL_SPEAKER.get());
                output.accept(ModItems.DJ_PANEL.get());
                output.accept(ModItems.SPEAKER_WIRE.get());
            })
            .build());

    private ModCreativeTabs() {
    }

    public static void register(IEventBus eventBus) {
        TABS.register(eventBus);
    }
}

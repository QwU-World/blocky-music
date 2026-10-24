package ru.gora.musicbattle.client;

import net.minecraft.client.OptionInstance;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/** Bridges Blocky Music's own persisted volume to Minecraft's sound options UI. */
public final class ClientSoundOptions {
    private static final OptionInstance<Integer> MUSIC_VOLUME = new OptionInstance<>(
            "music_battle.sound.volume",
            OptionInstance.cachedConstantTooltip(Component.translatable("music_battle.sound.volume.tooltip")),
            ClientSoundOptions::volumeLabel,
            new OptionInstance.IntRange(0, 200),
            configuredPercent(),
            percent -> ClientBattleSettings.setMusicVolume(percent / 100.0F)
    );

    private ClientSoundOptions() {
    }

    public static OptionInstance<Integer> musicVolumeOption() {
        // The custom Blocky Music screen writes the same setting. Refresh the
        // vanilla widget's backing value every time this screen is opened.
        int configured = configuredPercent();
        if (MUSIC_VOLUME.get() != configured) {
            MUSIC_VOLUME.set(configured);
        }
        return MUSIC_VOLUME;
    }

    private static int configuredPercent() {
        return Mth.clamp(Math.round(ClientBattleSettings.musicVolume() * 100.0F), 0, 200);
    }

    private static Component volumeLabel(Component caption, Integer percent) {
        return percent <= 0
                ? CommonComponents.optionStatus(caption, false)
                : Component.translatable("options.percent_value", caption, percent);
    }
}

package ru.gora.musicbattle.client.workshop;

import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import ru.gora.musicbattle.client.workshop.layout.Tr;

/**
 * The game's {@link Tr}: Minecraft's current language flattened to strings, because the Workshop layout measures and
 * cuts strings with the TrueType fonts. Missing keys come back as the key itself, which makes a forgotten translation
 * obvious in game and in the {@code localization} check.
 */
public final class WorkshopTr implements Tr {
    public static final WorkshopTr INSTANCE = new WorkshopTr();

    private WorkshopTr() {
    }

    @Override
    public String get(String key, Object... args) {
        if (!Language.getInstance().has(key)) {
            return key;
        }
        return Component.translatable(key, args).getString();
    }
}

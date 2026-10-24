package ru.gora.musicbattle.client.workshop;

import java.util.Locale;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import ru.gora.musicbattle.workshop.api.WorkshopApiException;

/** Shared Workshop wording: error codes to translated text, sizes with {@code Locale.ROOT} numbers. */
public final class WorkshopText {
    private WorkshopText() {
    }

    /** {@code music_battle.workshop.error.<code>}, or a generic line naming the code when this build has no text for it. */
    public static Component error(String code) {
        String key = WorkshopApiException.translationKey(code);
        if (Language.getInstance().has(key)) {
            return Component.translatable(key);
        }
        return Component.translatable("music_battle.workshop.error.unknown", code == null ? "?" : code);
    }

    /** "7.3 MiB" style sizes; numbers never use the player's locale separators. */
    public static String size(long bytes) {
        if (bytes < 0) {
            return "?";
        }
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024L * 1024) {
            return String.format(Locale.ROOT, "%.1f KiB", bytes / 1024.0);
        }
        return String.format(Locale.ROOT, "%.1f MiB", bytes / (1024.0 * 1024.0));
    }
}

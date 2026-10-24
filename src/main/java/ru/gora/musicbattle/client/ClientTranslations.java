package ru.gora.musicbattle.client;

import net.minecraft.locale.Language;

/** Resolves string-only UI labels at use time, without consuming format placeholders. */
public final class ClientTranslations {
    private ClientTranslations() { }
    public static String text(String key) {
        return Language.getInstance().getOrDefault(key);
    }
}

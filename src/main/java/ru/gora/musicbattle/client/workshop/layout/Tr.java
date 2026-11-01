package ru.gora.musicbattle.client.workshop.layout;

/**
 * Translated text for the pure layout model. In game this wraps Minecraft's language ({@code Component.translatable}
 * flattened to a string, because the layout measures strings); the {@code workshop-screens} check loads the same
 * {@code lang/*.json} files. Keys live under {@code screen.music_battle.workshop.}.
 */
@FunctionalInterface
public interface Tr {
    /** The translation for {@code key}, with {@code %s} arguments substituted; the key itself when it is missing. */
    String get(String key, Object... args);

    /** Russian plural forms need a language hint; the key {@code …workshop.plural_rule} holds "ru" or "one_other". */
    default boolean russianPlurals() {
        return "ru".equals(get(WorkshopStrings.KEY + "plural_rule"));
    }
}

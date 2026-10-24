package ru.gora.musicbattle.workshop.api;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Device labels the website shows on the approval page ("Minecraft 1.21.1 · NeoForge · macOS"). The server accepts
 * letters, digits, spaces and {@code ._()-·}, 1 to 80 characters; anything else is replaced here so a label never
 * fails validation.
 */
public final class ClientLabels {
    public static final int MAX_LENGTH = 80;

    private ClientLabels() {
    }

    public static String forGame(String osName) {
        String os = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        String shortOs = os.contains("win") ? "Windows" : os.contains("mac") ? "macOS" : os.contains("linux") ? "Linux" : "Desktop";
        return sanitize("Minecraft 1.21.1 · NeoForge · " + shortOs);
    }

    public static String sanitize(String label) {
        String value = label == null ? "" : Normalizer.normalize(label, Normalizer.Form.NFC);
        StringBuilder out = new StringBuilder();
        value.codePoints().forEach(cp -> {
            boolean allowed = Character.isLetterOrDigit(cp) || cp == ' ' || cp == '.' || cp == '_' || cp == '(' || cp == ')'
                    || cp == '-' || cp == 0xB7;
            out.appendCodePoint(allowed ? cp : ' ');
        });
        String collapsed = out.toString().replaceAll("\\s+", " ").trim();
        if (collapsed.isEmpty()) {
            return "Minecraft";
        }
        return collapsed.length() > MAX_LENGTH ? collapsed.substring(0, MAX_LENGTH).trim() : collapsed;
    }
}

package ru.gora.musicbattle.client.ui;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Optional;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;

/**
 * Opening links and copying text from Workshop screens. Every URL is parsed and limited
 * to http(s) before the vanilla confirmation dialog, because {@code ConfirmLinkScreen.confirmLinkNow(Screen,
 * String)} and {@code Util.OS.openUri(String)} do not check the scheme.
 *
 * <p>First-party links ({@code https://qwu.world} and its subdomains) use the trusted dialog; links written by
 * other people (comments, descriptions) use the untrusted one with vanilla's warning and respect the "Web Links"
 * chat option. After the dialog Minecraft returns to {@code parent} with {@code setScreen}, which clears pushed
 * GUI layers: pass the bottom Workshop screen and keep page state in fields.
 */
public final class LinkActions {
    public static final int MAX_URL_LENGTH = 2048;
    public static final String FIRST_PARTY_DOMAIN = ru.gora.musicbattle.workshop.api.MediaUrl.FIRST_PARTY_DOMAIN;

    private LinkActions() {
    }

    /** The URL as a URI when it is an absolute http(s) link with a host and no control characters. */
    public static Optional<URI> parse(String url) {
        if (url == null || url.isEmpty() || url.length() > MAX_URL_LENGTH) {
            return Optional.empty();
        }
        for (int i = 0; i < url.length(); i++) {
            char c = url.charAt(i);
            if (c < 0x20 || c == 0x7F || Character.isWhitespace(c)) {
                return Optional.empty();
            }
        }
        try {
            URI uri = Util.parseAndValidateUntrustedUri(url);
            return uri.getHost() == null || uri.getHost().isEmpty() ? Optional.empty() : Optional.of(uri);
        } catch (URISyntaxException | RuntimeException e) {
            return Optional.empty();
        }
    }

    /**
     * https on qwu.world or a subdomain, with no user info (which could disguise the real host). The same rule decides
     * which account pictures the Workshop fetches ({@code MediaUrl}).
     */
    public static boolean isFirstParty(URI uri) {
        return ru.gora.musicbattle.workshop.api.MediaUrl.isFirstParty(uri);
    }

    /**
     * Opens a first-party link through the trusted confirmation. Returns false, doing nothing, when the URL is
     * invalid or not first-party.
     */
    public static boolean openTrusted(Screen parent, String url) {
        Optional<URI> uri = parse(url).filter(LinkActions::isFirstParty);
        uri.ifPresent(value -> ConfirmLinkScreen.confirmLinkNow(parent, value, true));
        return uri.isPresent();
    }

    /**
     * Opens a link written by someone else through the untrusted confirmation. Returns false, doing nothing, when
     * the URL is invalid or the player turned web links off in chat settings.
     */
    public static boolean openUntrusted(Screen parent, String url) {
        if (!Minecraft.getInstance().options.chatLinks().get()) {
            return false;
        }
        Optional<URI> uri = parse(url);
        uri.ifPresent(value -> ConfirmLinkScreen.confirmLinkNow(parent, value, false));
        return uri.isPresent();
    }

    /** Copies non-empty text to the clipboard (Minecraft ignores empty strings). */
    public static boolean copy(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        Minecraft.getInstance().keyboardHandler.setClipboard(text);
        return true;
    }
}

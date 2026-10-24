package ru.gora.musicbattle.client.workshop;

import java.io.IOException;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import ru.gora.musicbattle.MusicBattleMod;
import ru.gora.musicbattle.client.ui.LinkActions;
import ru.gora.musicbattle.client.ui.Palette;
import ru.gora.musicbattle.client.ui.UiIcon;

/**
 * The first-run network disclosure (spec 06 section 4, Modrinth rule 1.11): what the Workshop contacts, what it sends,
 * links to the Terms and the Privacy policy. Nothing reaches qwu.world or Mojang (beyond vanilla) before "Allow".
 * "Not now" is remembered too, so the Workshop opens offline (installed maps, drops and the inbox still work) and the
 * question comes back only from the Workshop's network toggle.
 */
public final class WorkshopConsentScreen extends WorkshopDialogScreen {
    public static final String PRIVACY_URL = "https://qwu.world/privacy#blocky-music-workshop";
    private static final String KEY = "screen.music_battle.workshop.consent.";

    private final Runnable onAllow;
    private final Runnable onDecline;

    public WorkshopConsentScreen(Screen parent, Runnable onAllow, Runnable onDecline) {
        super(parent, Component.translatable(KEY + "title"), UiIcon.INFO, Palette.ACCENT);
        this.onAllow = onAllow;
        this.onDecline = onDecline;
        addParagraph(Component.translatable(KEY + "intro"));
        addParagraph(Component.translatable(KEY + "workshop_host"));
        addParagraph(Component.translatable(KEY + "mojang_session"));
        addParagraph(Component.translatable(KEY + "mojang_profiles"));
        addParagraph(Component.translatable(KEY + "sent"));
        addParagraph(Component.translatable(KEY + "never_sent"));
        addParagraph(Component.translatable(KEY + "offline"));
        addLink(Component.translatable(KEY + "terms"), UiIcon.EXTERNAL_LINK, () -> open(WorkshopClient.get().api().pageUrl("/legal/terms")));
        addLink(Component.translatable(KEY + "privacy"), UiIcon.EXTERNAL_LINK, () -> open(PRIVACY_URL));
        addAction(Component.translatable(KEY + "decline"), WorkshopButton.Kind.SECONDARY, null, () -> answer(false));
        addAction(Component.translatable(KEY + "allow"), WorkshopButton.Kind.PRIMARY, UiIcon.CHECK, () -> answer(true));
        setCancelAction(() -> answer(false));
    }

    private void open(String url) {
        if (!LinkActions.openTrusted(this, url)) {
            LinkActions.openUntrusted(this, url);
        }
    }

    private void answer(boolean allowed) {
        try {
            WorkshopClient.get().setNetworkConsent(allowed);
        } catch (IOException exception) {
            MusicBattleMod.LOGGER.warn("Unable to save the Workshop network choice: {}", exception.getMessage());
            setStatus(Component.translatable(KEY + "save_failed"), Palette.DANGER);
            relayout();
            return;
        }
        ru.gora.musicbattle.client.ui.HandCursor.reset();
        (allowed ? onAllow : onDecline).run();
    }
}

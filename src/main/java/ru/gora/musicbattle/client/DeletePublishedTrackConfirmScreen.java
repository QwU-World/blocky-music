package ru.gora.musicbattle.client;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import ru.gora.musicbattle.client.ui.Palette;
import ru.gora.musicbattle.client.ui.UiIcon;
import ru.gora.musicbattle.client.workshop.WorkshopButton;
import ru.gora.musicbattle.client.workshop.WorkshopDialogScreen;
import ru.gora.musicbattle.server.MusicBattleTracks;

/**
 * "Delete the published copy from the server?" in the Workshop's dialog look. The server answers asynchronously, so
 * the dialog only sends the request and returns to the library; the result arrives there as a message.
 */
public final class DeletePublishedTrackConfirmScreen extends WorkshopDialogScreen {
    private final String sourceTrackId;

    public DeletePublishedTrackConfirmScreen(Screen parent, MusicBattleTracks.Track track) {
        this(parent, track.title(), track.id());
    }

    public DeletePublishedTrackConfirmScreen(Screen parent, String trackTitle, String sourceTrackId) {
        super(parent, Component.translatable("music_battle.l10n.client.049"), UiIcon.TRASH, Palette.DANGER);
        this.sourceTrackId = sourceTrackId;
        addParagraph(Component.translatable("music_battle.l10n.client.052"));
        addParagraph(Component.translatable("screen.music_battle.library.confirm_track", trackTitle));
        addParagraph(Component.translatable("music_battle.l10n.client.053"));
        addParagraph(Component.translatable("music_battle.l10n.client.054"));
        addAction(Component.translatable("music_battle.l10n.client.051"), WorkshopButton.Kind.SECONDARY, null,
                this::onClose);
        addAction(Component.translatable("music_battle.l10n.client.050"), WorkshopButton.Kind.DANGER, UiIcon.TRASH,
                this::confirm);
        setCancelAction(this::onClose);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private void confirm() {
        ClientTrackPublisher.deletePublished(sourceTrackId);
        onClose();
    }
}

package ru.gora.musicbattle.client;

import java.io.IOException;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import ru.gora.musicbattle.client.ui.Palette;
import ru.gora.musicbattle.client.ui.UiIcon;
import ru.gora.musicbattle.client.workshop.WorkshopButton;
import ru.gora.musicbattle.client.workshop.WorkshopDialogScreen;
import ru.gora.musicbattle.server.MusicBattleTracks;

/**
 * "Delete this track from your computer?" in the Workshop's dialog look. Deleting touches the local files only, so
 * the dialog says what goes and what stays before the player commits to it.
 */
public final class DeleteLocalTrackConfirmScreen extends WorkshopDialogScreen {
    private final MusicBattleTracks.Track track;

    public DeleteLocalTrackConfirmScreen(Screen parent, MusicBattleTracks.Track track) {
        super(parent, Component.translatable("music_battle.l10n.client.042"), UiIcon.TRASH, Palette.DANGER);
        this.track = track;
        addParagraph(Component.translatable("music_battle.l10n.client.045"));
        addParagraph(Component.translatable("screen.music_battle.library.confirm_track", track.title()));
        addParagraph(Component.translatable("music_battle.l10n.client.046"));
        addParagraph(Component.translatable("music_battle.l10n.client.047"));
        addAction(Component.translatable("music_battle.l10n.client.044"), WorkshopButton.Kind.SECONDARY, null,
                this::onClose);
        addAction(Component.translatable("music_battle.l10n.client.043"), WorkshopButton.Kind.DANGER, UiIcon.TRASH,
                this::confirm);
        setCancelAction(this::onClose);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private void confirm() {
        try {
            ClientTrackExporter.deleteTrack(track.id());
            onClose();
        } catch (IOException exception) {
            // A failed delete keeps the dialog open: the reason belongs next to the button that caused it, and the
            // track library underneath would show the track as if nothing had happened.
            String message = exception.getMessage();
            setStatus(message == null || message.isBlank()
                    ? Component.translatable("music_battle.l10n.client.048")
                    : Component.literal(MusicBattleTracks.safeMessage(exception)), Palette.DANGER);
            relayout();
        }
    }
}

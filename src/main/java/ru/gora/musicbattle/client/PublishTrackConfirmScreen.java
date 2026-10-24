package ru.gora.musicbattle.client;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import ru.gora.musicbattle.client.ui.Palette;
import ru.gora.musicbattle.client.ui.UiIcon;
import ru.gora.musicbattle.client.workshop.WorkshopButton;
import ru.gora.musicbattle.client.workshop.WorkshopDialogScreen;
import ru.gora.musicbattle.server.MusicBattleTracks;

/**
 * The last step of publishing to the Minecraft server you are on, in the Workshop's dialog look: uploading makes the
 * track visible to everyone in the world, which is why it is worth one more yes.
 */
public final class PublishTrackConfirmScreen extends WorkshopDialogScreen {
    private final MusicBattleTracks.Track track;

    public PublishTrackConfirmScreen(Screen parent, MusicBattleTracks.Track track) {
        super(parent, Component.translatable("music_battle.l10n.client.060"), UiIcon.UPLOAD, Palette.ACCENT);
        this.track = track;
        addParagraph(Component.translatable("music_battle.l10n.client.062"));
        addParagraph(Component.translatable("screen.music_battle.library.confirm_track", track.title()));
        addParagraph(Component.translatable("music_battle.l10n.client.063"));
        addAction(Component.translatable("music_battle.l10n.client.051"), WorkshopButton.Kind.SECONDARY, null,
                this::onClose);
        addAction(Component.translatable("music_battle.l10n.client.061"), WorkshopButton.Kind.PRIMARY, UiIcon.UPLOAD,
                this::confirm);
        setCancelAction(this::onClose);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private void confirm() {
        ClientTrackPublisher.publish(track);
        onClose();
    }
}

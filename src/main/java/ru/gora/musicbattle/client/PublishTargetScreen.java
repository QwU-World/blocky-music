package ru.gora.musicbattle.client;

import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import ru.gora.musicbattle.client.ui.Palette;
import ru.gora.musicbattle.client.ui.UiIcon;
import ru.gora.musicbattle.client.workshop.WorkshopButton;
import ru.gora.musicbattle.client.workshop.WorkshopDialogScreen;
import ru.gora.musicbattle.server.MusicBattleTracks;

/**
 * Where does this track go: to the Minecraft server you are on (the existing publish flow), or to the Workshop as a
 * beatmap (spec 06 section 5, "Publish to Workshop" on local rows of the track library)?
 */
public final class PublishTargetScreen extends WorkshopDialogScreen {
    PublishTargetScreen(TrackLibraryScreen parent, MusicBattleTracks.Track track) {
        super(parent, Component.translatable("screen.music_battle.library.publish_title", track.title()), UiIcon.UPLOAD,
                Palette.ACCENT);
        addParagraph(Component.translatable("screen.music_battle.library.publish_intro"));
        addParagraph(Component.translatable("screen.music_battle.library.publish_server_note"));
        addParagraph(Component.translatable("screen.music_battle.library.publish_workshop_note"));
        WorkshopButton server = addAction(Component.translatable("screen.music_battle.library.publish_server"),
                WorkshopButton.Kind.SECONDARY, UiIcon.UPLOAD, () -> {
                    onClose();
                    parent.publishToServer(track);
                });
        server.setEnabled(parent.publishToServerAllowed());
        addAction(Component.translatable("screen.music_battle.library.publish_workshop"), WorkshopButton.Kind.PRIMARY,
                UiIcon.MUSIC, () -> {
                    onClose();
                    parent.publishToWorkshop(track);
                });
        addAction(CommonComponents.GUI_CANCEL, WorkshopButton.Kind.SECONDARY, null, this::onClose);
        setCancelAction(this::onClose);
    }
}

package ru.gora.musicbattle.client.workshop;

import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import ru.gora.musicbattle.client.ui.LinkActions;
import ru.gora.musicbattle.client.ui.Palette;
import ru.gora.musicbattle.client.ui.UiIcon;
import ru.gora.musicbattle.client.workshop.layout.WorkshopStrings;

/**
 * The ⋯ menu of the set page (spec 06 section 6.2): Report, Copy link, and for an installed set Open folder, Copy to
 * my tracks, Choose audio file and Delete local copy.
 */
public final class WorkshopMoreScreen extends WorkshopDialogScreen {
    WorkshopMoreScreen(WorkshopScreen screen, WorkshopClient client, long setId, String title, boolean installed,
                       boolean needsAudio, boolean canUpdate) {
        super(screen, Component.translatable(WorkshopStrings.KEY + "more.title", title), UiIcon.SETTINGS, Palette.ACCENT);
        addParagraph(Component.translatable(WorkshopStrings.KEY + "more.intro"));
        addAction(Component.translatable(WorkshopStrings.KEY + "more.report"), WorkshopButton.Kind.SECONDARY, UiIcon.FLAG, () -> {
            onClose();
            screen.showDialog(WorkshopReportScreen.forSet(screen, client, setId, title));
        });
        addAction(Component.translatable(WorkshopStrings.KEY + "more.copy_link"), WorkshopButton.Kind.SECONDARY, UiIcon.COPY, () -> {
            LinkActions.copy(client.api().pageUrl("/beatmapsets/" + setId));
            onClose();
            screen.showMessage(Component.translatable(WorkshopStrings.KEY + "more.copied"),
                    Component.translatable(WorkshopStrings.KEY + "more.copied_detail"));
        });
        if (canUpdate) {
            addAction(Component.translatable(WorkshopStrings.KEY + "more.update"), WorkshopButton.Kind.SECONDARY, UiIcon.UPLOAD,
                    () -> {
                        onClose();
                        screen.openUpload(setId);
                    });
        }
        if (installed) {
            addAction(Component.translatable(WorkshopStrings.KEY + "more.open_folder"), WorkshopButton.Kind.SECONDARY,
                    UiIcon.FOLDER_OPEN, () -> {
                        onClose();
                        InstalledActions.openFolder(client, setId);
                    });
            addAction(Component.translatable(WorkshopStrings.KEY + "more.copy_to_tracks"), WorkshopButton.Kind.SECONDARY,
                    UiIcon.COPY, () -> {
                        onClose();
                        InstalledActions.copyToMyTracks(screen, client, setId);
                    });
            if (needsAudio) {
                addAction(Component.translatable(WorkshopStrings.KEY + "more.choose_audio"), WorkshopButton.Kind.PRIMARY,
                        UiIcon.MUSIC, () -> {
                            onClose();
                            InstalledActions.chooseAudio(screen, client, setId);
                        });
            }
            // Spec 06 section 9.1: only with the server's permission, and only for a signed, still-available set.
            if (WorkshopServerHooks.serverBeatmapsAvailable() && ServerWorkshopClient.canManage()) {
                addAction(Component.translatable(WorkshopStrings.KEY + "more.add_to_server"), WorkshopButton.Kind.PRIMARY,
                        UiIcon.UPLOAD, () -> {
                            onClose();
                            ServerWorkshopClient.addToServer(screen, setId);
                        });
            }
            addAction(Component.translatable(WorkshopStrings.KEY + "more.delete"), WorkshopButton.Kind.DANGER, UiIcon.TRASH, () -> {
                onClose();
                InstalledActions.confirmDelete(screen, client, setId, title);
            });
        }
        if (WorkshopServerHooks.serverBeatmapsAvailable() && ServerWorkshopClient.canManage()) {
            addAction(Component.translatable(WorkshopStrings.KEY + "more.remove_from_server"), WorkshopButton.Kind.DANGER,
                    UiIcon.TRASH, () -> {
                        onClose();
                        WorkshopServerHooks.ServerBeatmaps hook = WorkshopServerHooks.serverBeatmaps();
                        if (hook != null) {
                            hook.remove(setId, error -> {
                            });
                        }
                    });
        }
        addAction(CommonComponents.GUI_CANCEL, WorkshopButton.Kind.SECONDARY, null, this::onClose);
        setCancelAction(this::onClose);
    }
}

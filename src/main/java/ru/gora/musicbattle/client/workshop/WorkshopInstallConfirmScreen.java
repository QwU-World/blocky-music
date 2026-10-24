package ru.gora.musicbattle.client.workshop;

import java.util.stream.Collectors;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import ru.gora.musicbattle.client.ui.Palette;
import ru.gora.musicbattle.client.ui.UiIcon;
import ru.gora.musicbattle.workshop.BeatmapManifest;
import ru.gora.musicbattle.workshop.WorkshopCatalog;

/**
 * "Install this beatmap?" for a dropped file or an inbox package that would replace another revision (spec 06
 * section 8.3): title, artist, mapper, charts, size, and whether it is already installed or replaces revision N.
 */
public final class WorkshopInstallConfirmScreen extends WorkshopDialogScreen {
    private final WorkshopDrops.Pending pending;

    WorkshopInstallConfirmScreen(Screen parent, WorkshopDrops.Pending pending) {
        super(parent, Component.translatable("screen.music_battle.workshop.install.title"), UiIcon.DOWNLOAD, Palette.ACCENT);
        this.pending = pending;
        BeatmapManifest manifest = pending.manifest();
        addParagraph(Component.translatable("screen.music_battle.workshop.install.set", manifest.title(), manifest.artist()));
        addParagraph(Component.translatable("screen.music_battle.workshop.install.mapper", manifest.mapper().name()));
        String charts = manifest.charts().stream().map(BeatmapManifest.Chart::difficulty).collect(Collectors.joining(", "));
        addParagraph(Component.translatable("screen.music_battle.workshop.install.charts", String.valueOf(manifest.charts().size()), charts));
        addParagraph(Component.translatable("screen.music_battle.workshop.install.size", WorkshopText.size(pending.size())));
        if (manifest.audio().policy() == BeatmapManifest.AudioPolicy.EXTERNAL) {
            addParagraph(Component.translatable("screen.music_battle.workshop.install.external_audio"));
        }
        WorkshopCatalog.InstalledSet installed = pending.installed();
        boolean identical = installed != null && installed.contentSha256().equals(manifest.contentSha256())
                && installed.revision() == manifest.revision();
        if (identical) {
            addParagraph(Component.translatable("screen.music_battle.workshop.install.already_installed"));
            addAction(CommonComponents.GUI_OK, WorkshopButton.Kind.PRIMARY, null, this::onClose);
        } else {
            if (installed != null) {
                addParagraph(Component.translatable("screen.music_battle.workshop.install.replaces",
                        String.valueOf(installed.revision()), String.valueOf(manifest.revision())));
            }
            addAction(CommonComponents.GUI_CANCEL, WorkshopButton.Kind.SECONDARY, null, this::onClose);
            addAction(Component.translatable(installed == null ? "screen.music_battle.workshop.install.install"
                    : "screen.music_battle.workshop.install.replace"), WorkshopButton.Kind.PRIMARY, UiIcon.DOWNLOAD, this::install);
        }
        setCancelAction(this::onClose);
    }

    private void install() {
        WorkshopDrops.confirm(pending);
        onClose();
    }
}

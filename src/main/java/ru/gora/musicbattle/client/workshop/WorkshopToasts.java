package ru.gora.musicbattle.client.workshop;

import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;
import ru.gora.musicbattle.workshop.BeatmapManifest;
import ru.gora.musicbattle.workshop.BeatmapPackage;
import ru.gora.musicbattle.workshop.api.WorkshopApiException;
import ru.gora.musicbattle.workshop.api.WorkshopDownloader;

/** Workshop toasts ("Installed <Title>", "Too many downloads"). Safe from any thread: work hops to the render thread. */
public final class WorkshopToasts {
    private static final SystemToast.SystemToastId INSTALLED = new SystemToast.SystemToastId(5000L);
    private static final SystemToast.SystemToastId PROBLEM = new SystemToast.SystemToastId(7000L);

    private WorkshopToasts() {
    }

    public static void show(Component title, Component body) {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> SystemToast.add(minecraft.getToasts(), INSTALLED, title, body));
    }

    public static void problem(Component title, Component body) {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> SystemToast.addOrUpdate(minecraft.getToasts(), PROBLEM, title, body));
    }

    public static void installed(BeatmapManifest manifest, BeatmapPackage.Outcome outcome) {
        String key = switch (outcome) {
            case INSTALLED -> "message.music_battle.workshop.installed";
            case REPLACED -> "message.music_battle.workshop.updated";
            case ALREADY_INSTALLED -> "message.music_battle.workshop.already_installed";
        };
        show(Component.translatable(key, manifest.title()),
                Component.translatable("message.music_battle.workshop.installed_detail", manifest.artist(),
                        String.valueOf(manifest.revision())));
    }

    /** Downloader events as toasts. */
    static final class DownloadToasts implements WorkshopDownloader.Listener {
        @Override
        public void statusChanged(long setId, WorkshopDownloader.Status status) {
        }

        @Override
        public void installed(long setId, BeatmapManifest manifest, BeatmapPackage.Outcome outcome) {
            WorkshopToasts.installed(manifest, outcome);
        }

        @Override
        public void failed(long setId, String code, long retryAfterSeconds) {
            if (WorkshopApiException.RATE_LIMITED.equals(code)) {
                long minutes = Math.max(1, (retryAfterSeconds + 59) / 60);
                problem(Component.translatable("message.music_battle.workshop.rate_limited_title"),
                        Component.translatable("message.music_battle.workshop.rate_limited", String.format(Locale.ROOT, "%d", minutes)));
                return;
            }
            problem(Component.translatable("message.music_battle.workshop.download_failed"),
                    WorkshopText.error(code));
        }
    }
}

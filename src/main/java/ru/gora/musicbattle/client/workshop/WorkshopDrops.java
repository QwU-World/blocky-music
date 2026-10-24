package ru.gora.musicbattle.client.workshop;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import ru.gora.musicbattle.MusicBattleMod;
import ru.gora.musicbattle.client.ClientBattleController;
import ru.gora.musicbattle.workshop.BeatmapManifest;
import ru.gora.musicbattle.workshop.BeatmapPackage;
import ru.gora.musicbattle.workshop.BeatmapPackageException;
import ru.gora.musicbattle.workshop.WorkshopCatalog;
import ru.gora.musicbattle.workshop.WorkshopLayout;
import ru.gora.musicbattle.workshop.api.WorkshopDownloader;

/**
 * Install from a file (spec 06 sections 5 and 8.3): {@code .blockymap} files dropped on the game window and packages
 * in {@code music_battle_tracks/workshop_inbox/}. One install point for both.
 *
 * <ul>
 *   <li><b>Drop:</b> the manifest is read off-thread ({@code BeatmapPackage.readManifest}), then a confirmation shows
 *       title, artist, mapper, charts, size and "already installed" / "replaces revision N"; Install runs
 *       {@code BeatmapPackage.install} off-thread and shows a toast. An invalid file shows the reason
 *       ({@code music_battle.workshop.error.package_<reason>}). {@code .osz} on the Workshop screen gets a hint.</li>
 *   <li><b>Inbox:</b> a new set installs right away with a toast; a package that would replace a different installed
 *       revision waits for a confirmation shown when no battle is running and no other screen is in the way; an
 *       identical package is simply removed; an invalid one moves to {@code rejected/} with a reason file.</li>
 *   <li><b>Refused before it is opened:</b> a chosen or dropped file that is not a {@code .blockymap} at all, and a
 *       file dialog that could not be opened, say so through {@link #pickRefused}. Silence there reads as a dead
 *       button.</li>
 * </ul>
 * Dialogs are queued and shown one at a time from the client tick, never during battle input isolation.
 */
public final class WorkshopDrops {
    enum Source {
        DROP, INBOX
    }

    /**
     * Why a file the player chose or dropped was never opened. The wording fits any of the pickers, because
     * {@link WorkshopFiles} refuses a package, a song and a cover image the same three ways.
     */
    public enum Refusal {
        /** The extension is not one this picker accepts: on some systems its filter is a hint, not a rule. */
        WRONG_TYPE("file.refused_type"),
        /** A folder, a link, a device, or a file that disappeared between the choice and the read. */
        UNREADABLE("file.refused_unreadable"),
        /** The system file dialog could not be opened at all, so there was nothing to choose from. */
        PICKER_UNAVAILABLE("file.refused_unavailable");

        private final String key;

        Refusal(String key) {
            this.key = key;
        }
    }

    /** A validated package waiting for the player's decision. */
    record Pending(Source source, Path file, BeatmapManifest manifest, long size, WorkshopCatalog.InstalledSet installed) {
    }

    /** A message dialog waiting to be shown. */
    record Message(Component title, Component body) {
    }

    private static final Queue<Object> QUEUE = new ConcurrentLinkedQueue<>();
    /** Inbox files already queued or declined in this session: never asked twice. */
    private static final Set<Path> INBOX_ASKED = ConcurrentHashMap.newKeySet();

    private WorkshopDrops() {
    }

    static boolean isPackage(Path path) {
        return path != null && String.valueOf(path.getFileName()).toLowerCase(Locale.ROOT).endsWith(WorkshopLayout.PACKAGE_EXTENSION);
    }

    static boolean isOsuArchive(Path path) {
        String name = path == null ? "" : String.valueOf(path.getFileName()).toLowerCase(Locale.ROOT);
        return name.endsWith(".osz") || name.endsWith(".olz");
    }

    /**
     * Files dropped on a screen or the world (render thread). Handles the {@code .blockymap} ones and returns the paths
     * it did not handle.
     */
    public static List<Path> onFilesDropped(Screen screen, List<Path> paths) {
        List<Path> rest = new ArrayList<>();
        boolean osuHint = false;
        boolean wrongType = false;
        WorkshopClient client = null;
        for (Path path : paths) {
            if (isPackage(path)) {
                if (client == null) {
                    client = WorkshopClient.get();
                }
                validateDropped(client, path);
            } else {
                if (isOsuArchive(path) && screen instanceof WorkshopScreen) {
                    osuHint = true;
                } else if (screen instanceof WorkshopScreen) {
                    // On the Workshop screen a dropped file was meant to be a map, so saying nothing is wrong. Other
                    // screens pass their drops on to the game, which has its own uses for them.
                    wrongType = true;
                }
                rest.add(path);
            }
        }
        if (osuHint) {
            QUEUE.add(new Message(Component.translatable("screen.music_battle.workshop.drop.osz_title"),
                    Component.translatable("screen.music_battle.workshop.drop.osz_hint")));
        }
        if (wrongType) {
            // Once per drop, like the hint above: five unrelated files are one mistake, not five dialogs to dismiss.
            pickRefused(Refusal.WRONG_TYPE);
        }
        return rest;
    }

    /**
     * Says why nothing happened. "Import .blockymap...", "Choose audio file" and a dropped file can all end before
     * the file is ever opened, and silence there reads as a broken button: the player chose something and the game
     * did nothing at all. Shown through the same dialog queue as every other install message.
     */
    public static void pickRefused(Refusal reason) {
        if (reason == null) {
            return;
        }
        QUEUE.add(new Message(Component.translatable("screen.music_battle.workshop.file.refused_title"),
                Component.translatable("screen.music_battle.workshop." + reason.key)));
    }

    private static void validateDropped(WorkshopClient client, Path file) {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            QUEUE.add(invalid(String.valueOf(file.getFileName()), BeatmapPackageException.Reason.NOT_A_FILE));
            return;
        }
        client.readManifest(file).whenComplete((manifest, error) -> Minecraft.getInstance().execute(() -> {
            if (error == null) {
                QUEUE.add(new Pending(Source.DROP, file, manifest, size(file), client.catalog().bySetId(manifest.setId()).orElse(null)));
                return;
            }
            Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
            if (cause instanceof BeatmapPackageException packageError) {
                QUEUE.add(invalid(String.valueOf(file.getFileName()), packageError.reason()));
            } else {
                QUEUE.add(new Message(Component.translatable("screen.music_battle.workshop.install.failed_title"),
                        Component.translatable("music_battle.workshop.error.install_failed")));
            }
        }));
    }

    private static Message invalid(String fileName, BeatmapPackageException.Reason reason) {
        return new Message(Component.translatable("screen.music_battle.workshop.install.invalid_title", fileName),
                WorkshopText.error(WorkshopDownloader.packageErrorCode(reason)));
    }

    private static long size(Path file) {
        try {
            return Files.size(file);
        } catch (IOException exception) {
            return -1;
        }
    }

    /** One inbox package (Workshop IO thread). */
    static void processInboxFile(WorkshopClient client, Path file) {
        if (INBOX_ASKED.contains(file)) {
            return;
        }
        BeatmapManifest manifest;
        try {
            manifest = BeatmapPackage.readManifest(file);
        } catch (BeatmapPackageException exception) {
            reject(client, file, exception);
            return;
        } catch (IOException exception) {
            return; // Unreadable right now (locked, still copying): the next scan retries.
        }
        Optional<WorkshopCatalog.InstalledSet> installed = client.catalog().bySetId(manifest.setId());
        if (installed.isPresent() && installed.get().contentSha256().equals(manifest.contentSha256())
                && installed.get().revision() == manifest.revision()) {
            try {
                client.inbox().consume(file);
            } catch (IOException ignored) {
                INBOX_ASKED.add(file);
            }
            WorkshopToasts.show(Component.translatable("message.music_battle.workshop.already_installed", manifest.title()), null);
            return;
        }
        if (installed.isPresent()) {
            INBOX_ASKED.add(file);
            QUEUE.add(new Pending(Source.INBOX, file, manifest, size(file), installed.get()));
            return;
        }
        try {
            BeatmapPackage.InstallResult result = BeatmapPackage.install(file, client.catalog().tracksRoot());
            // Refreshed here and not only through refreshInstalledState, because the rest of this very scan asks the
            // catalogue whether a set is installed, and a second copy of the same map must not install twice.
            client.catalog().refresh();
            client.inbox().consume(file);
            // The hash index and the Workshop's verdict on this set also have to catch up before it can be chosen in
            // a battle, and the player has no way to ask for that themselves.
            client.refreshInstalledState();
            WorkshopToasts.installed(result.manifest(), result.outcome());
        } catch (BeatmapPackageException exception) {
            reject(client, file, exception);
        } catch (IOException exception) {
            INBOX_ASKED.add(file);
            MusicBattleMod.LOGGER.warn("Workshop inbox install failed: {}", exception.getMessage());
        }
    }

    private static void reject(WorkshopClient client, Path file, BeatmapPackageException exception) {
        String code = WorkshopDownloader.packageErrorCode(exception.reason());
        try {
            client.inbox().reject(file, code, exception.getMessage());
        } catch (IOException moveFailed) {
            INBOX_ASKED.add(file);
        }
        WorkshopToasts.show(Component.translatable("message.music_battle.workshop.inbox_rejected", String.valueOf(file.getFileName())),
                WorkshopText.error(code));
    }

    /** Client tick (render thread): shows the next queued dialog when nothing is in the way. */
    static void tick() {
        Object next = QUEUE.peek();
        if (next == null) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        Screen screen = minecraft.screen;
        if (ClientBattleController.blocksParticipantInput() || screen instanceof WorkshopDialogScreen) {
            return;
        }
        if (next instanceof Pending pending && pending.source() == Source.INBOX
                && !(screen == null || screen instanceof WorkshopUi || screen instanceof TitleScreen)) {
            return;
        }
        QUEUE.poll();
        if (next instanceof Pending pending) {
            minecraft.setScreen(new WorkshopInstallConfirmScreen(screen, pending));
        } else if (next instanceof Message message) {
            minecraft.setScreen(new WorkshopMessageScreen(screen, message.title(), message.body()));
        }
    }

    /** The player pressed Install (render thread). */
    static void confirm(Pending pending) {
        WorkshopClient client = WorkshopClient.get();
        client.install(pending.file()).whenComplete((result, error) -> {
            if (error == null) {
                if (pending.source() == Source.INBOX) {
                    try {
                        client.inbox().consume(pending.file());
                        INBOX_ASKED.remove(pending.file());
                    } catch (IOException ignored) {
                        // Stays in INBOX_ASKED, so it is not offered again this session.
                    }
                }
                // Same as the inbox path: a map installed from a file is only playable once the catalogue, the hash
                // index and the installed-check know about it.
                client.refreshInstalledState();
                WorkshopToasts.installed(result.manifest(), result.outcome());
                return;
            }
            Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
            Minecraft.getInstance().execute(() -> {
                if (cause instanceof BeatmapPackageException packageError) {
                    if (pending.source() == Source.INBOX) {
                        client.io().execute(() -> reject(client, pending.file(), packageError));
                    }
                    QUEUE.add(invalid(String.valueOf(pending.file().getFileName()), packageError.reason()));
                } else {
                    QUEUE.add(new Message(Component.translatable("screen.music_battle.workshop.install.failed_title"),
                            Component.translatable("music_battle.workshop.error.install_failed")));
                }
            });
        });
    }
}

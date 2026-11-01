package ru.gora.musicbattle.client.workshop;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import net.minecraft.Util;
import net.minecraft.network.chat.Component;
import ru.gora.musicbattle.MusicBattleMod;
import ru.gora.musicbattle.client.workshop.layout.WorkshopStrings;
import ru.gora.musicbattle.server.MusicBattleTracks;
import ru.gora.musicbattle.workshop.BeatmapManifest;
import ru.gora.musicbattle.workshop.WorkshopCatalog;
import ru.gora.musicbattle.workshop.WorkshopLayout;
import ru.gora.musicbattle.workshop.api.CancelToken;
import ru.gora.musicbattle.workshop.api.InstalledCheck;
import ru.gora.musicbattle.workshop.api.WorkshopApi;
import ru.gora.musicbattle.workshop.api.WorkshopApiException;

/**
 * What the Installed tab and the set page do with a set on disk: open its folder,
 * copy it into {@code my_tracks}, choose the audio file of a bring-your-own-audio set, and delete the local copy.
 *
 * <p>"Copy to my tracks" is a transfer like handing a set to a server: it is refused for a withdrawn set or one whose
 * last installed-check is older than seven days, and it checks online first when it can.
 */
public final class InstalledActions {
    private static final long MAX_COPY_BYTES = 256L * 1024 * 1024;

    private InstalledActions() {
    }

    public static void openFolder(WorkshopClient client, long setId) {
        client.catalog().bySetId(setId).ifPresent(set -> client.io().execute(() -> {
            try {
                Util.getPlatform().openPath(set.directory());
            } catch (RuntimeException exception) {
                MusicBattleMod.LOGGER.warn("Unable to open the set folder: {}", exception.getMessage());
            }
        }));
    }

    public static void confirmDelete(WorkshopScreen screen, WorkshopClient client, long setId, String title) {
        screen.showDialog(WorkshopConfirmScreen.danger(screen,
                Component.translatable(WorkshopStrings.KEY + "delete.title"),
                Component.translatable(WorkshopStrings.KEY + "delete.body", title),
                Component.translatable(WorkshopStrings.KEY + "delete.confirm"),
                () -> client.io().execute(() -> {
                    try {
                        boolean removed = client.catalog().delete(setId);
                        screen.later(() -> {
                            screen.onCatalogChanged();
                            screen.showMessage(Component.translatable(WorkshopStrings.KEY
                                    + (removed ? "delete.done" : "delete.missing")), Component.literal(title));
                        });
                    } catch (IOException exception) {
                        screen.later(() -> screen.showError("install_failed"));
                    }
                })));
    }

    /**
     * Copies an installed set into {@code my_tracks} as {@code copy_<setId>_<key>} folders, rewriting the audio and
     * cover references so the copies stand on their own.
     */
    public static void copyToMyTracks(WorkshopScreen screen, WorkshopClient client, long setId) {
        WorkshopCatalog.InstalledSet set = client.catalog().bySetId(setId).orElse(null);
        if (set == null) {
            return;
        }
        InstalledCheck.TransferVerdict verdict = client.installedCheck().transferVerdict(setId, set.contentSha256());
        if (verdict != InstalledCheck.TransferVerdict.ALLOWED) {
            if (verdict == InstalledCheck.TransferVerdict.CHECK_FIRST && client.networkAllowed()) {
                screen.run(() -> {
                    try {
                        client.installedCheck().runNow(CancelToken.none());
                        screen.later(() -> {
                            if (client.installedCheck().transferVerdict(setId, set.contentSha256())
                                    == InstalledCheck.TransferVerdict.ALLOWED) {
                                copyNow(screen, client, set);
                            } else {
                                refuse(screen, verdict);
                            }
                        });
                    } catch (WorkshopApiException exception) {
                        screen.later(() -> refuse(screen, InstalledCheck.TransferVerdict.CHECK_FIRST));
                    }
                });
                return;
            }
            refuse(screen, verdict);
            return;
        }
        copyNow(screen, client, set);
    }

    private static void refuse(WorkshopScreen screen, InstalledCheck.TransferVerdict verdict) {
        String key = switch (verdict) {
            case WITHDRAWN -> "transfer.withdrawn";
            case AUDIO_REMOVED -> "transfer.audio_removed";
            default -> "transfer.check_first";
        };
        screen.showMessage(Component.translatable(WorkshopStrings.KEY + "transfer.refused"),
                Component.translatable(WorkshopStrings.KEY + key));
    }

    private static void copyNow(WorkshopScreen screen, WorkshopClient client, WorkshopCatalog.InstalledSet set) {
        client.io().execute(() -> {
            try {
                List<String> copied = copyCharts(set);
                screen.later(() -> screen.showMessage(Component.translatable(WorkshopStrings.KEY + "copy.done"),
                        Component.translatable(WorkshopStrings.KEY + "copy.done_detail", String.valueOf(copied.size()),
                                String.join(", ", copied))));
            } catch (WorkshopApiException refused) {
                // A full drive has its own answer ("free some space"), which the generic one below does not give:
                // this copies the song of every chart, so it is the one local action big enough to fill a disk.
                MusicBattleMod.LOGGER.warn("Copy to my tracks refused: {}", refused.getMessage());
                screen.later(() -> screen.showError(refused.code()));
            } catch (IOException exception) {
                MusicBattleMod.LOGGER.warn("Copy to my tracks failed: {}", MusicBattleTracks.safeMessage(exception));
                screen.later(() -> screen.showError("install_failed"));
            }
        });
    }

    private static List<String> copyCharts(WorkshopCatalog.InstalledSet set) throws IOException {
        BeatmapManifest manifest = set.manifest();
        Path myTracks = MusicBattleTracks.ensureTracksDirectory();
        // The song is copied once per chart, so a two-difficulty set needs it twice over; asking for the room first
        // keeps a full drive from leaving half a copy behind with "the map could not be installed" as its reason.
        WorkshopApi.requireRoom(myTracks, copyBytes(set));
        List<String> ids = new ArrayList<>();
        for (BeatmapManifest.Chart chart : manifest.charts()) {
            String id = ("copy_" + manifest.setId() + "_" + chart.key()).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_\\-]", "_");
            Path directory = myTracks.resolve(id).toAbsolutePath().normalize();
            if (!directory.startsWith(myTracks)) {
                throw new IOException("unsafe copy target");
            }
            Files.createDirectories(directory);
            Path source = set.directory().resolve(WorkshopLayout.chartFileName(manifest.setId(), chart.key()));
            if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS) || Files.size(source) > BeatmapManifest.MAX_CHART_BYTES) {
                continue;
            }
            JsonObject root = JsonParser.parseString(Files.readString(source, StandardCharsets.UTF_8)).getAsJsonObject();
            root.addProperty("id", id);
            root.addProperty("title", manifest.title() + " (" + chart.difficulty() + ")");
            Path audio = set.directory().resolve(WorkshopLayout.audioFileName(manifest.setId()));
            if (Files.isRegularFile(audio, LinkOption.NOFOLLOW_LINKS) && Files.size(audio) <= MAX_COPY_BYTES) {
                Path target = directory.resolve(id + ".ogg");
                Files.copy(audio, target, StandardCopyOption.REPLACE_EXISTING);
                root.addProperty("audio", id + "/" + id + ".ogg");
            } else {
                root.addProperty("audio", "");
            }
            Path cover = set.coverFile();
            if (Files.isRegularFile(cover, LinkOption.NOFOLLOW_LINKS)) {
                Path target = directory.resolve(id + "_preview.png");
                Files.copy(cover, target, StandardCopyOption.REPLACE_EXISTING);
                root.addProperty("preview", id + "/" + id + "_preview.png");
            } else {
                root.addProperty("preview", "");
            }
            Files.writeString(directory.resolve(id + ".json"), root.toString(), StandardCharsets.UTF_8);
            ids.add(id);
        }
        return ids;
    }

    /**
     * "Choose audio file" for a set with external audio (the checks themselves are
     * {@link ChosenAudio}): an Ogg Vorbis file whose SHA-256 matches is taken as it is; any other song
     * the mod reads (MP3, FLAC, WAV, M4A, Opus and the rest) is converted to Ogg Vorbis by the mod itself, off the
     * render thread, with a "Converting the song" message; then the length must match within ±1.5 s.
     */
    public static void chooseAudio(WorkshopScreen screen, WorkshopClient client, long setId) {
        WorkshopCatalog.InstalledSet set = client.catalog().bySetId(setId).orElse(null);
        if (set == null) {
            return;
        }
        Path picked = WorkshopFiles.chooseAudio();
        if (picked == null) {
            return;
        }
        String shown = String.valueOf(picked.getFileName());
        // Not on the Workshop IO thread: a conversion takes a second or two, and the inbox and installed-check queue
        // behind that thread.
        Util.nonCriticalIoPool().execute(() -> {
            try {
                BeatmapManifest.Audio declared = set.manifest().audio();
                Path target = set.directory().resolve(WorkshopLayout.audioFileName(setId));
                ChosenAudio.Result result = ChosenAudio.adopt(picked, target, declared.sha256(), declared.durationMs(),
                        () -> screen.showMessage(Component.translatable(WorkshopStrings.KEY + "audio.converting_title"),
                                Component.translatable(WorkshopStrings.KEY + "audio.converting", shown)));
                switch (result.kind()) {
                    case EXACT, BY_DURATION -> {
                        client.catalog().refresh();
                        ru.gora.musicbattle.client.ClientTrackAssets.refreshHashIndex();
                        String key = result.kind() == ChosenAudio.Kind.EXACT ? "audio.added_exact"
                                : result.converted() ? "audio.added_converted" : "audio.added_duration";
                        screen.later(() -> {
                            screen.onCatalogChanged();
                            screen.showMessage(Component.translatable(WorkshopStrings.KEY + "audio.added_title"),
                                    Component.translatable(WorkshopStrings.KEY + key));
                        });
                    }
                    case MISMATCH -> screen.later(() -> screen.showMessage(
                            Component.translatable(WorkshopStrings.KEY + "audio.mismatch_title"),
                            Component.translatable(WorkshopStrings.KEY + "audio.mismatch")));
                    case UNSUPPORTED -> screen.later(() -> screen.showMessage(
                            Component.translatable(WorkshopStrings.KEY + "audio.unsupported_title"),
                            result.formatName() == null
                                    ? Component.translatable(WorkshopStrings.KEY + "audio.unsupported", shown)
                                    : Component.translatable(WorkshopStrings.KEY + "audio.unsupported_named", shown,
                                            result.formatName())));
                    case CONVERSION_FAILED -> {
                        MusicBattleMod.LOGGER.warn("Converting the chosen song failed: {}", result.reason());
                        screen.later(() -> screen.showMessage(
                                Component.translatable(WorkshopStrings.KEY + "audio.convert_failed_title"),
                                Component.translatable(WorkshopStrings.KEY + "audio.convert_failed", shown,
                                        String.valueOf(result.reason()))));
                    }
                }
            } catch (IOException | RuntimeException exception) {
                MusicBattleMod.LOGGER.warn("Choosing audio failed: {}", MusicBattleTracks.safeMessage(exception));
                screen.later(() -> screen.showError("install_failed"));
            }
        });
    }

    /** Roughly what {@link #copyCharts} will write: the song and the cover once per chart. */
    private static long copyBytes(WorkshopCatalog.InstalledSet set) {
        long perChart = 0;
        try {
            Path audio = set.directory().resolve(WorkshopLayout.audioFileName(set.manifest().setId()));
            if (Files.isRegularFile(audio, LinkOption.NOFOLLOW_LINKS)) {
                perChart += Files.size(audio);
            }
            if (Files.isRegularFile(set.coverFile(), LinkOption.NOFOLLOW_LINKS)) {
                perChart += Files.size(set.coverFile());
            }
        } catch (IOException ignored) {
            // Nothing to ask for: the copy itself will report what it cannot read.
        }
        return perChart * Math.max(1, set.manifest().charts().size());
    }

    /** SHA-256 of a file, streamed. */
    public static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
                byte[] buffer = new byte[65_536];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IOException("SHA-256 is unavailable", exception);
        }
    }
}

package ru.gora.musicbattle.client;

import static ru.gora.musicbattle.client.ClientTranslations.text;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;
import ru.gora.musicbattle.server.MusicBattleTracks;
import ru.gora.musicbattle.network.PublishTrackBeginPayload;
import ru.gora.musicbattle.network.PublishTrackChunkPayload;
import ru.gora.musicbattle.network.PublishTrackFinishPayload;
import ru.gora.musicbattle.network.PublishTrackResultPayload;
import ru.gora.musicbattle.network.DeletePublishedTrackPayload;
import ru.gora.musicbattle.network.DeletePublishedTrackResultPayload;
import ru.gora.musicbattle.network.PublishedTracksPayload;
import ru.gora.musicbattle.network.RequestPublishedTracksPayload;

public final class ClientTrackPublisher {
    private static final int CHUNKS_PER_TICK = 4;
    private static UUID uploadId;
    private static CompletableFuture<Preparation> preparationFuture;
    private static List<UploadAsset> assets = List.of();
    private static List<Path> temporaryFiles = List.of();
    private static int assetIndex;
    private static long totalBytes;
    private static long sentBytes;
    private static State state = State.IDLE;
    private static String status = "";
    private static boolean success;
    private static List<PublishedTracksPayload.Entry> publishedTracks = List.of();
    private static int usedPublishedSlots;
    private static int maxPublishedSlots = 32;
    private static boolean publishedTracksLoading;
    private static boolean canPublish;
    private static String preparationNotice = "";

    private ClientTrackPublisher() {
    }

    public static void publish(MusicBattleTracks.Track track) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.getConnection() == null) {
            status = text("music_battle.l10n.client.019");
            success = false;
            return;
        }
        if (isBusy()) {
            status = text("music_battle.l10n.client.020");
            success = false;
            return;
        }
        if (!canPublish) {
            status = text("music_battle.l10n.client.021");
            success = false;
            return;
        }
        UUID currentUploadId = UUID.randomUUID();
        uploadId = currentUploadId;
        state = State.PREPARING;
        preparationNotice = "";
        status = text("music_battle.l10n.client.022");
        success = false;
        preparationNotice = "";
        sentBytes = 0L;
        totalBytes = 0L;
        preparationFuture = CompletableFuture.supplyAsync(() -> {
            try {
                return prepare(track, currentUploadId);
            } catch (IOException exception) {
                throw new CompletionException(exception);
            }
        }, Util.nonCriticalIoPool());
    }

    public static void deletePublished(MusicBattleTracks.Track track) {
        deletePublished(track.id());
    }

    public static void deletePublished(String sourceTrackId) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.getConnection() == null) {
            status = text("music_battle.l10n.client.023");
            success = false;
            return;
        }
        if (isBusy()) {
            status = text("music_battle.l10n.client.024");
            success = false;
            return;
        }
        state = State.WAITING;
        status = text("music_battle.l10n.client.025");
        success = false;
        PacketDistributor.sendToServer(new DeletePublishedTrackPayload(sourceTrackId));
    }

    public static void acceptDeleteResult(DeletePublishedTrackResultPayload payload) {
        state = payload.success() ? State.DONE : State.FAILED;
        success = payload.success();
        status = payload.message().getString();
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null) {
            minecraft.player.displayClientMessage(Component.literal("Blocky Music: ").append(payload.message()), false);
        }
    }

    public static void requestPublishedTracks() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.getConnection() == null) {
            publishedTracks = List.of();
            usedPublishedSlots = 0;
            publishedTracksLoading = false;
            status = text("music_battle.l10n.client.026");
            success = false;
            return;
        }
        publishedTracksLoading = true;
        PacketDistributor.sendToServer(new RequestPublishedTracksPayload());
    }

    public static void acceptPublishedTracks(PublishedTracksPayload payload) {
        publishedTracks = payload.tracks();
        usedPublishedSlots = Math.max(0, payload.usedSlots());
        maxPublishedSlots = Math.max(1, payload.maxSlots());
        canPublish = payload.canPublish();
        publishedTracksLoading = false;
    }

    public static List<PublishedTracksPayload.Entry> publishedTracks() {
        return publishedTracks;
    }

    public static int usedPublishedSlots() {
        return usedPublishedSlots;
    }

    public static int maxPublishedSlots() {
        return maxPublishedSlots;
    }

    public static boolean publishedTracksLoading() {
        return publishedTracksLoading;
    }

    public static boolean canPublish() {
        return canPublish;
    }

    public static void tick() {
        if (state == State.PREPARING && preparationFuture != null && preparationFuture.isDone()) {
            try {
                beginUpload(preparationFuture.join());
            } catch (CompletionException exception) {
                fail(exception.getCause() == null ? exception.getMessage() : exception.getCause().getMessage());
            }
        }
        if (state != State.UPLOADING) {
            return;
        }
        try {
            for (int sent = 0; sent < CHUNKS_PER_TICK && assetIndex < assets.size(); sent++) {
                UploadAsset asset = assets.get(assetIndex);
                byte[] data = asset.input.readNBytes(PublishTrackChunkPayload.MAX_CHUNK_BYTES);
                if (data.length <= 0) {
                    throw new IOException(text("music_battle.l10n.client.027") + asset.kind);
                }
                PacketDistributor.sendToServer(new PublishTrackChunkPayload(uploadId, asset.kind, asset.nextChunk++, data));
                sentBytes += data.length;
                asset.sentBytes += data.length;
                if (asset.sentBytes >= asset.size) {
                    asset.input.close();
                    assetIndex++;
                }
            }
            status = uploadStatus(text("music_battle.l10n.client.028") + Math.round(progress() * 100.0F) + "%");
            if (assetIndex >= assets.size()) {
                closeInputs();
                PacketDistributor.sendToServer(new PublishTrackFinishPayload(uploadId));
                state = State.WAITING;
                status = uploadStatus(text("music_battle.l10n.client.029"));
            }
        } catch (IOException exception) {
            fail(exception.getMessage());
        }
    }

    public static void acceptResult(PublishTrackResultPayload payload) {
        if (uploadId == null || !uploadId.equals(payload.uploadId())) {
            return;
        }
        closeInputs();
        state = payload.success() ? State.DONE : State.FAILED;
        success = payload.success();
        status = preparationNotice.isBlank() ? payload.message().getString() : preparationNotice + " | " + payload.message().getString();
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null) {
            minecraft.player.displayClientMessage(Component.literal("Blocky Music: ").append(payload.message()), false);
        }
    }

    public static void reset() {
        closeInputs();
        CompletableFuture<Preparation> abandoned = preparationFuture;
        if (abandoned != null) {
            abandoned.thenAccept(ClientTrackPublisher::cleanupPreparation);
        }
        uploadId = null;
        preparationFuture = null;
        assets = List.of();
        assetIndex = 0;
        totalBytes = 0L;
        sentBytes = 0L;
        state = State.IDLE;
        status = "";
        success = false;
        preparationNotice = "";
    }

    public static boolean isBusy() {
        return state == State.PREPARING || state == State.UPLOADING || state == State.WAITING;
    }

    public static String status() {
        return status;
    }

    public static boolean succeeded() {
        return success;
    }

    public static float progress() {
        if (state == State.WAITING || state == State.DONE) {
            return 1.0F;
        }
        return totalBytes <= 0L ? 0.0F : Math.min(1.0F, sentBytes / (float) totalBytes);
    }

    private static Preparation prepare(MusicBattleTracks.Track track, UUID currentUploadId) throws IOException {
        Path localRoot = MusicBattleTracks.tracksDirectory().toAbsolutePath().normalize();
        if (!track.sourceDirectory().toAbsolutePath().normalize().equals(localRoot)) {
            throw new IOException(text("music_battle.l10n.client.030"));
        }
        Path chart = MusicBattleTracks.definitionPath(track).orElseThrow(() -> new IOException(text("music_battle.l10n.client.031")));
        List<PreparedAsset> prepared = new ArrayList<>();
        String previewNotice = "";
        prepared.add(prepareAsset("chart", chart));
        if (!track.audioFile().isBlank()) {
            prepared.add(prepareAsset("audio", MusicBattleTracks.audioPath(track).orElseThrow(() -> new IOException(text("music_battle.l10n.client.032")))));
        }
        if (!track.previewFile().isBlank()) {
            Path previewPath = MusicBattleTracks.previewPath(track).orElseThrow(() -> new IOException(text("music_battle.l10n.client.033")));
            ClientPreviewProcessor.ProcessedPreview preview = ClientPreviewProcessor.processForPublication(previewPath);
            PreparedAsset preparedPreview;
            try {
                preparedPreview = prepareAsset("preview", preview.path(), true);
            } catch (IOException exception) {
                Files.deleteIfExists(preview.path());
                throw exception;
            }
            prepared.add(preparedPreview);
            previewNotice = text("music_battle.l10n.client.034") + preview.sourceFormat() + " " + preview.originalWidth() + "x" + preview.originalHeight()
                    + ": " + formatBytes(preview.sourceBytes()) + " → " + formatBytes(preview.outputBytes());
        }
        return new Preparation(currentUploadId, track.id(), List.copyOf(prepared), previewNotice);
    }

    private static PreparedAsset prepareAsset(String kind, Path path) throws IOException {
        return prepareAsset(kind, path, false);
    }

    private static PreparedAsset prepareAsset(String kind, Path path, boolean temporary) throws IOException {
        long size = Files.size(path);
        if (size <= 0L || size > Integer.MAX_VALUE) {
            throw new IOException(text("music_battle.l10n.client.035") + kind);
        }
        return new PreparedAsset(kind, path, (int) size, sha256(path), temporary);
    }

    private static void beginUpload(Preparation preparation) {
        if (uploadId == null || !uploadId.equals(preparation.uploadId)) {
            cleanupPreparation(preparation);
            return;
        }
        PreparedAsset chart = preparation.asset("chart").orElseThrow();
        PreparedAsset audio = preparation.asset("audio").orElse(null);
        PreparedAsset preview = preparation.asset("preview").orElse(null);
        List<UploadAsset> opened = new ArrayList<>();
        try {
            for (PreparedAsset asset : preparation.assets) {
                opened.add(new UploadAsset(asset.kind, asset.size, Files.newInputStream(asset.path)));
            }
        } catch (IOException exception) {
            for (UploadAsset asset : opened) {
                try {
                    asset.input.close();
                } catch (IOException ignored) {
                }
            }
            fail(exception.getMessage());
            cleanupPreparation(preparation);
            return;
        }
        PacketDistributor.sendToServer(new PublishTrackBeginPayload(
                uploadId,
                preparation.trackId,
                chart.size,
                chart.sha256,
                audio == null ? 0 : audio.size,
                audio == null ? "" : audio.sha256,
                preview == null ? 0 : preview.size,
                preview == null ? "" : preview.sha256
        ));
        assets = List.copyOf(opened);
        preparationNotice = preparation.previewNotice;
        temporaryFiles = preparation.assets.stream().filter(PreparedAsset::temporary).map(PreparedAsset::path).toList();
        totalBytes = preparation.assets.stream().mapToLong(asset -> asset.size).sum();
        sentBytes = 0L;
        assetIndex = 0;
        preparationFuture = null;
        state = State.UPLOADING;
        status = preparationNotice.isBlank()
                ? text("music_battle.l10n.client.036")
                : preparationNotice + text("music_battle.l10n.client.037");
    }

    private static void fail(String reason) {
        boolean cancelServerUpload = state == State.UPLOADING && uploadId != null;
        closeInputs();
        state = State.FAILED;
        success = false;
        status = reason == null || reason.isBlank() ? text("music_battle.l10n.client.038") : reason;
        if (cancelServerUpload) {
            PacketDistributor.sendToServer(new PublishTrackFinishPayload(uploadId));
        }
    }

    private static void closeInputs() {
        for (UploadAsset asset : assets) {
            try {
                asset.input.close();
            } catch (IOException ignored) {
            }
        }
        assets = List.of();
        for (Path temporary : temporaryFiles) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
            }
        }
        temporaryFiles = List.of();
    }

    private static void cleanupPreparation(Preparation preparation) {
        for (PreparedAsset asset : preparation.assets) {
            if (asset.temporary) {
                try {
                    Files.deleteIfExists(asset.path);
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static String uploadStatus(String phase) {
        return preparationNotice.isBlank() ? phase : preparationNotice + " | " + phase;
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024L) return bytes + text("music_battle.l10n.client.039");
        if (bytes < 1024L * 1024L) return String.format(java.util.Locale.ROOT, text("music_battle.l10n.client.040"), String.format(java.util.Locale.ROOT, "%.1f", bytes / 1024.0D));
        return String.format(java.util.Locale.ROOT, text("music_battle.l10n.client.041"), String.format(java.util.Locale.ROOT, "%.2f", bytes / (1024.0D * 1024.0D)));
    }

    private static String sha256(Path path) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private enum State {
        IDLE,
        PREPARING,
        UPLOADING,
        WAITING,
        DONE,
        FAILED
    }

    private record Preparation(UUID uploadId, String trackId, List<PreparedAsset> assets, String previewNotice) {
        private Optional<PreparedAsset> asset(String kind) {
            return assets.stream().filter(asset -> asset.kind.equals(kind)).findFirst();
        }
    }

    private record PreparedAsset(String kind, Path path, int size, String sha256, boolean temporary) {
    }

    private static final class UploadAsset {
        private final String kind;
        private final int size;
        private final InputStream input;
        private int nextChunk;
        private int sentBytes;

        private UploadAsset(String kind, int size, InputStream input) {
            this.kind = kind;
            this.size = size;
            this.input = input;
        }
    }
}

package ru.gora.musicbattle.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import net.minecraft.Util;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import ru.gora.musicbattle.MusicBattleMod;
import ru.gora.musicbattle.network.PublishTrackBeginPayload;
import ru.gora.musicbattle.network.PublishTrackChunkPayload;
import ru.gora.musicbattle.network.PublishTrackResultPayload;
import ru.gora.musicbattle.network.DeletePublishedTrackResultPayload;
import ru.gora.musicbattle.network.PublishedTracksPayload;

public final class TrackPublicationManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int MAX_CHART_BYTES = 2 * 1024 * 1024;
    private static final int MAX_AUDIO_BYTES = 128 * 1024 * 1024;
    private static final int MAX_PREVIEW_BYTES = 8 * 1024 * 1024;
    private static final int MAX_NOTES = 4096;
    private static final int MAX_PLAYER_TRACKS = 32;
    private static final Map<UUID, UploadSession> UPLOADS = new HashMap<>();
    private static final Map<UUID, UUID> UPLOAD_BY_PLAYER = new HashMap<>();
    private static final Set<UUID> FINISHING_PLAYERS = new HashSet<>();

    private TrackPublicationManager() {
    }

    public static void deletePublished(ServerPlayer player, String rawTrackId) {
        String originalId = safeId(rawTrackId);
        if (!safeTrackId(originalId)) {
            sendDeleteResult(player, false, Component.translatable("music_battle.l10n.server.delete_invalid_id"), originalId);
            return;
        }
        String ownerPrefix = ownerPrefix(player.getUUID());
        String publishedId = publishedId(ownerPrefix, originalId);
        Path root = MusicBattleTracks.userServerTracksDirectory().toAbsolutePath().normalize();
        Path directory = root.resolve(publishedId).toAbsolutePath().normalize();
        Path definition = directory.resolve(publishedId + ".json").toAbsolutePath().normalize();
        if (!directory.startsWith(root) || directory.equals(root)) {
            sendDeleteResult(player, false, Component.translatable("music_battle.l10n.server.delete_unsafe_path"), originalId);
            return;
        }
        try {
            if (!Files.isRegularFile(definition, LinkOption.NOFOLLOW_LINKS)) {
                sendDeleteResult(player, false, Component.translatable("music_battle.l10n.server.delete_missing"), originalId);
                return;
            }
            JsonObject rootJson = JsonParser.parseString(Files.readString(definition, StandardCharsets.UTF_8)).getAsJsonObject();
            String owner = string(rootJson, "publishedByUuid", "");
            if (!player.getUUID().toString().equals(owner)) {
                sendDeleteResult(player, false, Component.translatable("music_battle.l10n.server.delete_not_owner"), originalId);
                return;
            }
            deleteDirectory(directory, root);
            MusicBattleTracks.reloadServerCatalog();
            sendDeleteResult(player, true, Component.translatable("music_battle.l10n.server.delete_success"), originalId);
            sendPublishedTracks(player);
        } catch (IOException | RuntimeException exception) {
            MusicBattleMod.LOGGER.warn("Unable to delete published music battle track '{}' for '{}'.", publishedId, player.getGameProfile().getName(), exception);
            sendDeleteResult(player, false, Component.translatable("music_battle.l10n.server.delete_failed"), originalId);
        }
    }

    private static void sendDeleteResult(ServerPlayer player, boolean success, Component message, String trackId) {
        PacketDistributor.sendToPlayer(player, new DeletePublishedTrackResultPayload(success, message, trackId));
    }

    public static void sendPublishedTracks(ServerPlayer player) {
        String ownerUuid = player.getUUID().toString();
        String ownerPrefix = ownerPrefix(player.getUUID());
        String prefix = "user_" + ownerPrefix + "_";
        Path root = MusicBattleTracks.userServerTracksDirectory().toAbsolutePath().normalize();
        List<PublishedTracksPayload.Entry> entries = new ArrayList<>();
        try {
            Files.createDirectories(root);
            try (var stream = Files.find(root, 2, (path, attributes) -> attributes.isRegularFile()
                    && path.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".json"))) {
                for (Path definition : stream.toList()) {
                    try {
                        JsonObject json = JsonParser.parseString(Files.readString(definition, StandardCharsets.UTF_8)).getAsJsonObject();
                        if (!ownerUuid.equals(string(json, "publishedByUuid", ""))) {
                            continue;
                        }
                        String publishedId = safeId(string(json, "id", ""));
                        if (!publishedId.startsWith(prefix) || !safeTrackId(publishedId)) {
                            continue;
                        }
                        String sourceId = publishedId.substring(prefix.length());
                        entries.add(new PublishedTracksPayload.Entry(
                                publishedId,
                                sourceId,
                                limit(string(json, "title", sourceId), 128),
                                limit(string(json, "description", ""), 1024),
                                clamp(integer(json, "bpm", 120), 20, 400)
                        ));
                    } catch (IOException | RuntimeException exception) {
                        MusicBattleMod.LOGGER.warn("Unable to read published track metadata '{}'.", definition, exception);
                    }
                }
            }
        } catch (IOException exception) {
            MusicBattleMod.LOGGER.warn("Unable to list tracks published by '{}'.", player.getGameProfile().getName(), exception);
        }
        entries.sort(Comparator.comparing(PublishedTracksPayload.Entry::title, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(PublishedTracksPayload.Entry::publishedId));
        PacketDistributor.sendToPlayer(player, new PublishedTracksPayload(
                entries.size(), MAX_PLAYER_TRACKS, ru.gora.musicbattle.server.workshop.WorkshopPermissions.canPublish(player), List.copyOf(entries)));
    }

    public static void begin(ServerPlayer player, PublishTrackBeginPayload payload) {
        if (!ru.gora.musicbattle.server.workshop.WorkshopPermissions.canPublish(player)) {
            cancelPlayer(player.getUUID());
            sendResult(player, payload.uploadId(), false,
                    Component.translatable("music_battle.l10n.server.publish_no_permission"), "");
            return;
        }
        if (FINISHING_PLAYERS.contains(player.getUUID())) {
            sendResult(player, payload.uploadId(), false, Component.translatable("music_battle.l10n.server.publish_pending"), "");
            return;
        }
        cancelPlayer(player.getUUID());
        if (!safeTrackId(payload.trackId())
                || !validAsset(payload.chartBytes(), payload.chartSha256(), MAX_CHART_BYTES, true)
                || !validAsset(payload.audioBytes(), payload.audioSha256(), MAX_AUDIO_BYTES, false)
                || !validAsset(payload.previewBytes(), payload.previewSha256(), MAX_PREVIEW_BYTES, false)) {
            sendResult(player, payload.uploadId(), false, Component.translatable("music_battle.l10n.server.publish_invalid_files"), "");
            return;
        }
        try {
            Path directory = uploadRoot().resolve(payload.uploadId().toString()).toAbsolutePath().normalize();
            if (!directory.startsWith(uploadRoot()) || Files.isSymbolicLink(directory)) {
                throw new LocalizedValidationException("music_battle.l10n.server.validation_unsafe_upload");
            }
            deleteDirectory(directory, uploadRoot());
            Files.createDirectories(directory);
            UploadSession session = new UploadSession(
                    payload.uploadId(),
                    player.getUUID(),
                    player.getGameProfile().getName(),
                    payload.trackId(),
                    directory,
                    new ExpectedAsset("chart", payload.chartBytes(), payload.chartSha256(), directory.resolve("chart.json")),
                    optionalAsset("audio", payload.audioBytes(), payload.audioSha256(), directory.resolve("audio.ogg")),
                    optionalAsset("preview", payload.previewBytes(), payload.previewSha256(), directory.resolve("preview.png"))
            );
            UPLOADS.put(payload.uploadId(), session);
            UPLOAD_BY_PLAYER.put(player.getUUID(), payload.uploadId());
        } catch (IOException exception) {
            MusicBattleMod.LOGGER.warn("Unable to start track publication for {}.", player.getGameProfile().getName(), exception);
            sendResult(player, payload.uploadId(), false, Component.translatable("music_battle.l10n.server.publish_temp_failed"), "");
        }
    }

    public static void acceptChunk(ServerPlayer player, UUID uploadId, String assetKind, int chunkIndex, byte[] data) {
        UploadSession session = UPLOADS.get(uploadId);
        if (session == null || !session.playerId.equals(player.getUUID())) {
            return;
        }
        ExpectedAsset asset = session.asset(assetKind);
        int expectedChunks = asset == null ? 0 : (asset.totalBytes + PublishTrackChunkPayload.MAX_CHUNK_BYTES - 1) / PublishTrackChunkPayload.MAX_CHUNK_BYTES;
        int expectedLength = asset == null || chunkIndex < 0 || chunkIndex >= expectedChunks
                ? -1
                : Math.min(PublishTrackChunkPayload.MAX_CHUNK_BYTES, asset.totalBytes - chunkIndex * PublishTrackChunkPayload.MAX_CHUNK_BYTES);
        if (asset == null || data == null || data.length <= 0 || data.length > PublishTrackChunkPayload.MAX_CHUNK_BYTES
                || chunkIndex != asset.nextChunk || chunkIndex >= expectedChunks || data.length != expectedLength
                || asset.receivedBytes + data.length > asset.totalBytes) {
            fail(player, session, Component.translatable("music_battle.l10n.server.publish_chunk_order"));
            return;
        }
        try {
            if (chunkIndex == 0) {
                Files.deleteIfExists(asset.path);
            }
            try (OutputStream output = Files.newOutputStream(asset.path, StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE)) {
                output.write(data);
            }
            asset.nextChunk++;
            asset.receivedBytes += data.length;
        } catch (IOException exception) {
            MusicBattleMod.LOGGER.warn("Unable to receive published track chunk from {}.", player.getGameProfile().getName(), exception);
            fail(player, session, Component.translatable("music_battle.l10n.server.publish_write_failed"));
        }
    }

    public static void finish(ServerPlayer player, UUID uploadId) {
        UploadSession session = UPLOADS.get(uploadId);
        if (session == null || !session.playerId.equals(player.getUUID())) {
            return;
        }
        if (!ru.gora.musicbattle.server.workshop.WorkshopPermissions.canPublish(player)) {
            fail(player, session, Component.translatable("music_battle.l10n.server.publish_access_revoked"));
            return;
        }
        UPLOADS.remove(session.uploadId);
        UPLOAD_BY_PLAYER.remove(session.playerId, session.uploadId);
        FINISHING_PLAYERS.add(session.playerId);
        Util.nonCriticalIoPool().execute(() -> {
            boolean success = false;
            Component message;
            String publishedId = "";
            try {
                verify(session.chart);
                if (session.audio != null) {
                    verify(session.audio);
                    validateOgg(session.audio.path);
                }
                if (session.preview != null) {
                    verify(session.preview);
                    validatePng(session.preview.path);
                }
                publishedId = install(session);
                MusicBattleTracks.reloadServerCatalog();
                success = true;
                message = Component.translatable("music_battle.l10n.server.publish_success");
            } catch (IOException | RuntimeException exception) {
                MusicBattleMod.LOGGER.warn("Unable to publish track '{}' from {}.", session.trackId, session.playerName, exception);
                message = Component.translatable("music_battle.l10n.server.publish_rejected", safeMessage(exception));
            } finally {
                try {
                    deleteDirectory(session.directory, uploadRoot());
                } catch (IOException ignored) {
                }
            }
            boolean completedSuccessfully = success;
            Component completedMessage = message;
            String completedTrackId = publishedId;
            player.server.execute(() -> {
                FINISHING_PLAYERS.remove(session.playerId);
                if (player.connection != null) {
                    sendResult(player, uploadId, completedSuccessfully, completedMessage, completedTrackId);
                    if (completedSuccessfully) {
                        sendPublishedTracks(player);
                    }
                }
            });
        });
    }

    public static void cancelPlayer(UUID playerId) {
        UUID uploadId = UPLOAD_BY_PLAYER.remove(playerId);
        UploadSession session = uploadId == null ? null : UPLOADS.remove(uploadId);
        if (session != null) {
            try {
                deleteDirectory(session.directory, uploadRoot());
            } catch (IOException ignored) {
            }
        }
    }

    private static String install(UploadSession session) throws IOException {
        JsonObject uploaded = JsonParser.parseString(Files.readString(session.chart.path, StandardCharsets.UTF_8)).getAsJsonObject();
        String originalId = safeId(string(uploaded, "id", ""));
        if (!originalId.equals(session.trackId)) {
            throw new LocalizedValidationException("music_battle.l10n.server.validation_track_id");
        }
        String ownerPrefix = ownerPrefix(session.playerId);
        String publishedId = publishedId(ownerPrefix, originalId);
        enforceTrackLimit(ownerPrefix, publishedId);

        JsonObject result = new JsonObject();
        result.addProperty("id", publishedId);
        String title = limit(string(uploaded, "title", originalId), 128);
        result.addProperty("title", title.isBlank() ? originalId : title);
        result.addProperty("description", limit(string(uploaded, "description", ""), 1024));
        result.addProperty("bpm", clamp(integer(uploaded, "bpm", 120), 20, 400));
        ru.gora.musicbattle.data.ChartBeat.writeOffsetMs(result, ru.gora.musicbattle.data.ChartBeat.readOffsetMs(uploaded));
        result.addProperty("volume", clamp(number(uploaded, "volume", 1.0F), 0.0F, 2.0F));
        result.addProperty("publishedBy", limit(session.playerName, 32));
        result.addProperty("publishedByUuid", session.playerId.toString());
        result.addProperty("disableStageBars", uploaded.has("disableStageBars") && uploaded.get("disableStageBars").getAsBoolean());
        result.addProperty("hideStandardDimming", uploaded.has("hideStandardDimming") && uploaded.get("hideStandardDimming").getAsBoolean());
        result.addProperty("fxRowsPerLayer", ru.gora.musicbattle.data.TrackEffect.FX_ROWS_PER_LAYER);

        JsonArray notes = sanitizeNotes(uploaded);
        int duration = clamp(integer(uploaded, "durationTicks", suggestedDuration(notes)), 20 * 10, 20 * 60 * 120);
        result.addProperty("durationTicks", duration);
        result.add("notes", notes);
        result.add("effects", sanitizeEffects(uploaded));

        Path userRoot = MusicBattleTracks.userServerTracksDirectory().toAbsolutePath().normalize();
        Path staging = userRoot.resolve(".install_" + session.uploadId).toAbsolutePath().normalize();
        Path destination = userRoot.resolve(publishedId).toAbsolutePath().normalize();
        if (!staging.startsWith(userRoot) || !destination.startsWith(userRoot)) {
            throw new LocalizedValidationException("music_battle.l10n.server.validation_unsafe_destination");
        }
        deleteDirectory(staging, userRoot);
        Files.createDirectories(staging);
        if (session.audio != null) {
            String audioName = publishedId + ".ogg";
            Files.copy(session.audio.path, staging.resolve(audioName), StandardCopyOption.REPLACE_EXISTING);
            result.addProperty("audio", publishedId + "/" + audioName);
        } else {
            result.addProperty("audio", "");
        }
        if (session.preview != null) {
            String previewName = publishedId + "_preview.png";
            Files.copy(session.preview.path, staging.resolve(previewName), StandardCopyOption.REPLACE_EXISTING);
            result.addProperty("preview", publishedId + "/" + previewName);
        } else {
            result.addProperty("preview", "");
        }
        Files.writeString(staging.resolve(publishedId + ".json"), GSON.toJson(result), StandardCharsets.UTF_8);
        deleteDirectory(destination, userRoot);
        moveDirectory(staging, destination);
        return publishedId;
    }

    private static JsonArray sanitizeNotes(JsonObject root) throws IOException {
        if (!root.has("notes") || !root.get("notes").isJsonArray()) {
            throw new LocalizedValidationException("music_battle.l10n.server.validation_no_notes");
        }
        JsonArray source = root.getAsJsonArray("notes");
        if (source.size() > MAX_NOTES) {
            throw new LocalizedValidationException("music_battle.l10n.server.validation_too_many_notes");
        }
        JsonArray notes = new JsonArray();
        for (JsonElement element : source) {
            if (!element.isJsonObject()) {
                throw new LocalizedValidationException("music_battle.l10n.server.validation_corrupted_note");
            }
            JsonObject input = element.getAsJsonObject();
            int owner = integer(input, "owner", -1);
            int lane = integer(input, "lane", -1);
            int tick = integer(input, "tick", -1);
            int duration = integer(input, "durationTicks", 0);
            if (owner < 0 || owner > 1 || lane < 0 || lane > 3 || tick < 0 || tick > 20 * 60 * 120 || duration < 0 || duration > 20 * 60 * 30) {
                throw new LocalizedValidationException("music_battle.l10n.server.validation_note_range");
            }
            JsonObject note = new JsonObject();
            note.addProperty("owner", owner);
            note.addProperty("lane", lane);
            note.addProperty("tick", tick);
            note.addProperty("durationTicks", duration);
            notes.add(note);
        }
        return notes;
    }

    private static JsonArray sanitizeEffects(JsonObject root) throws IOException {
        JsonArray effects = new JsonArray();
        if (!root.has("effects")) {
            return effects;
        }
        if (!root.get("effects").isJsonArray()) {
            throw new LocalizedValidationException("music_battle.l10n.server.validation_corrupted_effect_track");
        }
        JsonArray source = root.getAsJsonArray("effects");
        int fxRowsPerLayer = integer(root, "fxRowsPerLayer", ru.gora.musicbattle.data.TrackEffect.LEGACY_FX_ROWS_PER_LAYER);
        if (fxRowsPerLayer != ru.gora.musicbattle.data.TrackEffect.LEGACY_FX_ROWS_PER_LAYER
                && fxRowsPerLayer != ru.gora.musicbattle.data.TrackEffect.FX_ROWS_PER_LAYER) {
            throw new LocalizedValidationException("music_battle.l10n.server.validation_fx_grid");
        }
        if (source.size() > ru.gora.musicbattle.data.TrackEffect.MAX_EFFECTS) {
            throw new LocalizedValidationException("music_battle.l10n.server.validation_too_many_effects");
        }
        for (JsonElement element : source) {
            if (!element.isJsonObject()) {
                throw new LocalizedValidationException("music_battle.l10n.server.validation_corrupted_effect");
            }
            JsonObject input = element.getAsJsonObject();
            String type = string(input, "type", "");
            // The retired glitch effect has no replacement. Published charts silently lose those
            // rows rather than being rejected as an unknown effect or downgraded to a flash.
            if ("glitch".equalsIgnoreCase(type)) {
                continue;
            }
            boolean removedColor = "color".equalsIgnoreCase(type);
            ru.gora.musicbattle.data.TrackEffect.Type parsed = ru.gora.musicbattle.data.TrackEffect.Type.byId(type);
            if (!parsed.id().equals(type) && !removedColor) {
                throw new LocalizedValidationException("music_battle.l10n.server.validation_unknown_effect");
            }
            int tick = integer(input, "tick", -1);
            int sourceLane = integer(input, "lane", 0);
            int sourceLayer = integer(input, "layer", 0);
            int effectDuration = integer(input, "durationTicks", 0);
            float intensity = number(input, "intensity", 1.0F);
            float softness = number(input, "softness", 0.65F);
            int colorA = ru.gora.musicbattle.data.TrackEffectSettings.migrateLegacyColor(parsed, input,
                    integer(input, "colorA", 0xFFFFFFFF));
            int colorB = integer(input, "colorB", 0xFFFF55C8);
            String text = string(input, "text", "Blocky Music");
            String font = string(input, "font", "minecraft:default");
            float positionX = number(input, "positionX", 0.5F);
            float positionY = number(input, "positionY", 0.32F);
            String animation = string(input, "animation", "letters");
            String exitAnimation = string(input, "exitAnimation", "none");
            String variant = removedColor ? "steady" : string(input, "variant", "both");
            boolean beatReactive = input.has("beatReactive") && input.get("beatReactive").getAsBoolean();
            float opacity = number(input, "opacity", 1.0F);
            float textSpeed = number(input, "textSpeed", 1.0F);
            float outlineThickness = number(input, "outlineThickness", parsed == ru.gora.musicbattle.data.TrackEffect.Type.TEXT ? 0.0F : 2.0F);
            float glowStrength = number(input, "glowStrength", 1.0F);
            ru.gora.musicbattle.data.TrackEffectSettings settings = ru.gora.musicbattle.data.TrackEffectSettings.readJson(input);
            if (sourceLane < 0 || sourceLane >= fxRowsPerLayer || sourceLayer < 0 || sourceLayer > 7 || tick < 0 || tick > 20 * 60 * 120 || effectDuration < 0 || effectDuration > 20 * 60 * 30
                    || !Float.isFinite(intensity) || intensity < 0.1F || intensity > 5.0F
                    || !Float.isFinite(softness) || softness < 0.0F || softness > 1.0F
                    || !Float.isFinite(positionX) || positionX < 0.0F || positionX > 1.0F
                    || !Float.isFinite(positionY) || positionY < 0.0F || positionY > 1.0F
                    || !Float.isFinite(opacity) || opacity < 0.0F || opacity > 1.0F
                    || !Float.isFinite(textSpeed) || textSpeed < 0.1F || textSpeed > 5.0F
                    || !Float.isFinite(outlineThickness) || outlineThickness < (parsed == ru.gora.musicbattle.data.TrackEffect.Type.TEXT ? 0.0F : 0.5F) || outlineThickness > 5.0F
                    || !Float.isFinite(glowStrength) || glowStrength < 0.0F || glowStrength > 5.0F
                    || text.length() > 160 || font.length() > 40 || animation.length() > 24 || exitAnimation.length() > 24 || variant.length() > 24) {
                throw new LocalizedValidationException("music_battle.l10n.server.validation_effect_range");
            }
            ru.gora.musicbattle.data.TrackEffect.FxPosition position = ru.gora.musicbattle.data.TrackEffect.migrateFxPosition(
                    sourceLane, sourceLayer, fxRowsPerLayer);
            JsonObject effect = new JsonObject();
            effect.addProperty("type", parsed.id());
            effect.addProperty("lane", position.lane());
            effect.addProperty("layer", position.layer());
            effect.addProperty("tick", tick);
            effect.addProperty("durationTicks", effectDuration);
            effect.addProperty("intensity", intensity);
            effect.addProperty("softness", softness);
            effect.addProperty("colorA", colorA);
            effect.addProperty("colorB", colorB);
            effect.addProperty("text", text);
            effect.addProperty("font", font);
            effect.addProperty("positionX", positionX);
            effect.addProperty("positionY", positionY);
            effect.addProperty("animation", animation);
            effect.addProperty("exitAnimation", exitAnimation);
            effect.addProperty("variant", variant);
            effect.addProperty("beatReactive", beatReactive);
            effect.addProperty("opacity", opacity);
            effect.addProperty("textSpeed", textSpeed);
            effect.addProperty("outlineThickness", outlineThickness);
            effect.addProperty("glowStrength", glowStrength);
            settings.writeJson(effect);
            effects.add(effect);
        }
        return effects;
    }

    private static int suggestedDuration(JsonArray notes) {
        int last = 20 * 20;
        for (JsonElement element : notes) {
            JsonObject note = element.getAsJsonObject();
            last = Math.max(last, integer(note, "tick", 0) + integer(note, "durationTicks", 0));
        }
        return Math.max(20 * 10, last + 5 * 20);
    }

    private static void enforceTrackLimit(String ownerPrefix, String replacingId) throws IOException {
        Path root = MusicBattleTracks.userServerTracksDirectory();
        try (var stream = Files.list(root)) {
            long count = stream.filter(Files::isDirectory)
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.startsWith("user_" + ownerPrefix + "_") && !name.equals(replacingId))
                    .count();
            if (count >= MAX_PLAYER_TRACKS) {
                throw new LocalizedValidationException("music_battle.l10n.server.validation_track_limit", MAX_PLAYER_TRACKS);
            }
        }
    }

    private static void verify(ExpectedAsset asset) throws IOException {
        if (asset.receivedBytes != asset.totalBytes || !Files.isRegularFile(asset.path, LinkOption.NOFOLLOW_LINKS)
                || Files.size(asset.path) != asset.totalBytes || !asset.sha256.equalsIgnoreCase(sha256(asset.path))) {
            throw new LocalizedValidationException("music_battle.l10n.server.validation_hash", asset.kind);
        }
    }

    private static void validateOgg(Path path) throws IOException {
        try (InputStream input = Files.newInputStream(path)) {
            byte[] header = input.readNBytes(4);
            if (header.length != 4 || header[0] != 'O' || header[1] != 'g' || header[2] != 'g' || header[3] != 'S') {
                throw new LocalizedValidationException("music_battle.l10n.server.validation_not_vorbis");
            }
        }
        MusicBattleTracks.readOggDurationTicks(path);
    }

    private static void validatePng(Path path) throws IOException {
        try (InputStream input = Files.newInputStream(path)) {
            byte[] header = input.readNBytes(24);
            byte[] signature = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
            if (header.length < 24) {
                throw new LocalizedValidationException("music_battle.l10n.server.validation_png_short");
            }
            for (int i = 0; i < signature.length; i++) {
                if (header[i] != signature[i]) {
                    throw new LocalizedValidationException("music_battle.l10n.server.validation_not_png");
                }
            }
            int width = readInt(header, 16);
            int height = readInt(header, 20);
            if (width <= 0 || height <= 0 || width > 2048 || height > 2048 || (long) width * height > 4_194_304L) {
                throw new LocalizedValidationException("music_battle.l10n.server.validation_png_dimensions");
            }
        }
    }

    private static ExpectedAsset optionalAsset(String kind, int bytes, String hash, Path path) {
        return bytes <= 0 ? null : new ExpectedAsset(kind, bytes, hash, path);
    }

    private static boolean validAsset(int bytes, String hash, int max, boolean required) {
        if (bytes == 0 && !required) {
            return hash == null || hash.isBlank();
        }
        return bytes > 0 && bytes <= max && safeHash(hash);
    }

    private static void fail(ServerPlayer player, UploadSession session, Component message) {
        UUID uploadId = session.uploadId;
        removeSession(session);
        sendResult(player, uploadId, false, message, "");
    }

    private static void removeSession(UploadSession session) {
        UPLOADS.remove(session.uploadId);
        UPLOAD_BY_PLAYER.remove(session.playerId, session.uploadId);
        try {
            deleteDirectory(session.directory, uploadRoot());
        } catch (IOException ignored) {
        }
    }

    private static void sendResult(ServerPlayer player, UUID uploadId, boolean success, Component message, String trackId) {
        PacketDistributor.sendToPlayer(player, new PublishTrackResultPayload(uploadId, success, message, trackId));
        if (success) {
            ru.gora.musicbattle.server.MusicBattleAdvancements.grant(player, "own_track");
        }
    }

    private static Path uploadRoot() throws IOException {
        Path root = MusicBattleTracks.tracksRootDirectory().resolve(".uploads").toAbsolutePath().normalize();
        Files.createDirectories(root);
        return root;
    }

    private static void deleteDirectory(Path directory, Path allowedRoot) throws IOException {
        Path root = allowedRoot.toAbsolutePath().normalize();
        Path target = directory.toAbsolutePath().normalize();
        if (!target.startsWith(root) || target.equals(root) || !Files.exists(target)) {
            return;
        }
        try (var stream = Files.walk(target)) {
            for (Path path : stream.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static void moveDirectory(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target);
        }
    }

    private static String sha256(Path path) throws IOException {
        MessageDigest digest = sha256Digest();
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

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static boolean safeTrackId(String value) {
        return value != null && value.matches("[a-z0-9_-]{1,96}");
    }

    private static boolean safeHash(String value) {
        return value != null && value.matches("[A-Fa-f0-9]{64}");
    }

    private static String safeId(String value) {
        return value == null ? "" : value.toLowerCase().trim().replaceAll("[^a-z0-9_-]", "_");
    }

    private static String ownerPrefix(UUID ownerId) {
        return ownerId.toString().replace("-", "").substring(0, 12);
    }

    private static String publishedId(String ownerPrefix, String originalId) {
        String value = safeId("user_" + ownerPrefix + "_" + originalId);
        return value.length() <= 96 ? value : value.substring(0, 96);
    }

    private static String string(JsonObject root, String key, String fallback) {
        return root.has(key) && root.get(key).isJsonPrimitive() ? root.get(key).getAsString() : fallback;
    }

    private static int integer(JsonObject root, String key, int fallback) {
        try {
            return root.has(key) && root.get(key).isJsonPrimitive() ? root.get(key).getAsInt() : fallback;
        } catch (RuntimeException exception) {
            return fallback;
        }
    }

    private static float number(JsonObject root, String key, float fallback) {
        try {
            return root.has(key) && root.get(key).isJsonPrimitive() ? root.get(key).getAsFloat() : fallback;
        } catch (RuntimeException exception) {
            return fallback;
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String limit(String value, int max) {
        String safe = value == null ? "" : value.strip();
        return safe.length() <= max ? safe : safe.substring(0, max);
    }

    private static Component safeMessage(Exception exception) {
        if (exception instanceof LocalizedValidationException validation) {
            return validation.message.copy();
        }
        String value = exception.getMessage();
        return value == null || value.isBlank()
                ? Component.translatable("music_battle.l10n.server.validation_invalid_data")
                : Component.literal(limit(value, 180));
    }

    /** Keeps known validation text unresolved until the receiving client chooses its language. */
    private static final class LocalizedValidationException extends IOException {
        private final Component message;

        private LocalizedValidationException(String key, Object... arguments) {
            super(key);
            message = Component.translatable(key, arguments);
        }
    }

    private static int readInt(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFF) << 24
                | (bytes[offset + 1] & 0xFF) << 16
                | (bytes[offset + 2] & 0xFF) << 8
                | bytes[offset + 3] & 0xFF;
    }

    private static final class UploadSession {
        private final UUID uploadId;
        private final UUID playerId;
        private final String playerName;
        private final String trackId;
        private final Path directory;
        private final ExpectedAsset chart;
        private final ExpectedAsset audio;
        private final ExpectedAsset preview;

        private UploadSession(UUID uploadId, UUID playerId, String playerName, String trackId, Path directory,
                              ExpectedAsset chart, ExpectedAsset audio, ExpectedAsset preview) {
            this.uploadId = uploadId;
            this.playerId = playerId;
            this.playerName = playerName;
            this.trackId = trackId;
            this.directory = directory;
            this.chart = chart;
            this.audio = audio;
            this.preview = preview;
        }

        private ExpectedAsset asset(String kind) {
            return switch (kind) {
                case "chart" -> chart;
                case "audio" -> audio;
                case "preview" -> preview;
                default -> null;
            };
        }
    }

    private static final class ExpectedAsset {
        private final String kind;
        private final int totalBytes;
        private final String sha256;
        private final Path path;
        private int nextChunk;
        private int receivedBytes;

        private ExpectedAsset(String kind, int totalBytes, String sha256, Path path) {
            this.kind = kind;
            this.totalBytes = totalBytes;
            this.sha256 = sha256;
            this.path = path;
        }
    }
}

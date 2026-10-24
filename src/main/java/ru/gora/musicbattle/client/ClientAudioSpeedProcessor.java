package ru.gora.musicbattle.client;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.neoforged.fml.loading.FMLPaths;

public final class ClientAudioSpeedProcessor {
    private ClientAudioSpeedProcessor() {
    }

    public static Path prepare(String trackId, String audioFile, Path source, float speedMultiplier) throws IOException {
        float speed = normalizeSpeed(speedMultiplier);
        if (speed <= 1.01F) {
            return source;
        }

        Path target = targetPath(trackId, audioFile, speed);
        if (Files.isRegularFile(target)
                && Files.size(target) > 0L
                && Files.getLastModifiedTime(target).compareTo(Files.getLastModifiedTime(source)) >= 0) {
            return target;
        }

        // The encoder is chosen by the shared helper, so this works with an ffmpeg built without libvorbis too.
        ClientFfmpeg.convertToVorbis(source, target, "atempo=" + filterSpeed(speed), 45);
        return target;
    }

    private static Path targetPath(String trackId, String audioFile, float speed) {
        String safeTrack = safeName(trackId);
        String safeAudio = safeName(stripExtension(Path.of(audioFile).getFileName().toString()));
        String suffix = speed >= 1.9F ? "x2" : "x15";
        return FMLPaths.GAMEDIR.get()
                .resolve("music_battle_tracks")
                .resolve(safeTrack)
                .resolve("speed_cache")
                .resolve(safeAudio + "_" + suffix + ".ogg")
                .toAbsolutePath()
                .normalize();
    }

    private static float normalizeSpeed(float speed) {
        if (speed >= 1.9F) {
            return 2.0F;
        }
        return speed >= 1.4F ? 1.5F : 1.0F;
    }

    private static String filterSpeed(float speed) {
        return speed >= 1.9F ? "2.0" : "1.5";
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static String safeName(String value) {
        String safe = value == null ? "track" : value.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9_\\-]", "_");
        return safe.isBlank() ? "track" : safe;
    }
}

package ru.gora.musicbattle.client;

import java.io.IOException;
import java.nio.file.Path;
import ru.gora.musicbattle.audio.FfmpegVorbis;

/**
 * The client's way into {@link FfmpegVorbis}, which finds ffmpeg and picks the Vorbis encoder for both sides.
 *
 * <p>What this adds is the client's own reading of "there is no ffmpeg": a {@link ClientTrackExporter.Problem} with a
 * translated message, so the editor and the osu! importer can tell a player what is missing rather than show a stack
 * trace. That type is client-only, which is why the shared helper cannot throw it itself.
 *
 * <p>Nothing here is required: a player who never converts anything never needs ffmpeg.
 */
public final class ClientFfmpeg {
    private ClientFfmpeg() {
    }

    /**
     * There is no ffmpeg on this computer. Its own type, because the callers do different things with it than with a
     * conversion that failed: the osu! importer keeps the notes and the original file, and tells the player that
     * this, not their map, is what stopped the song from coming along.
     */
    public static final class MissingException extends ClientTrackExporter.Problem {
        public MissingException() {
            super("music_battle.l10n.editor.ffmpeg_missing");
        }
    }

    /** The ffmpeg to use. Throws {@link MissingException} when there is none, with a message worth showing a player. */
    public static Path resolve() throws IOException {
        try {
            return FfmpegVorbis.locate();
        } catch (FfmpegVorbis.MissingException exception) {
            MissingException missing = new MissingException();
            missing.initCause(exception);
            throw missing;
        }
    }

    /**
     * Converts a file to Ogg Vorbis with whichever encoder this ffmpeg has. It waits for ffmpeg, so it must never run
     * on the render thread.
     *
     * @param audioFilter an ffmpeg audio filter, or {@code null} for a plain conversion
     */
    public static void convertToVorbis(Path source, Path target, String audioFilter, int timeoutSeconds) throws IOException {
        try {
            FfmpegVorbis.convert(source, target, audioFilter, timeoutSeconds);
        } catch (FfmpegVorbis.MissingException exception) {
            MissingException missing = new MissingException();
            missing.initCause(exception);
            throw missing;
        }
    }
}

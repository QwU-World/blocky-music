package ru.gora.musicbattle.client.workshop;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.ThreadLocalRandom;
import ru.gora.musicbattle.audio.NativeAudio;
import ru.gora.musicbattle.audio.codec.UnsupportedFormatException;
import ru.gora.musicbattle.server.MusicBattleTracks;

/**
 * The song a player picks for a Workshop beatmap published without its audio ("Choose audio file"). An Ogg Vorbis
 * file the game plays as it is is taken as it is: its SHA-256 matching the
 * beatmap's is an exact match, otherwise its length must match within {@link #DURATION_TOLERANCE_MS}. Any other file
 * (MP3, FLAC, WAV, M4A, Opus and everything else {@link NativeAudio} reads, or an Ogg the game would not play) is first
 * converted to Ogg Vorbis with the mod's own converter, into the mod's temporary folder inside the Minecraft folder, and
 * then has to pass the same length check: a converted file never has the original's checksum.
 *
 * <p>Blocking file work and a conversion of a second or two: call it on a worker, never the render thread. No
 * Minecraft screen classes, so the native-audio check runs it on real files.
 */
public final class ChosenAudio {
    /** A local audio file may differ from the declared duration by this much and still be accepted. */
    public static final long DURATION_TOLERANCE_MS = 1500;
    /** How long one song may take to convert; the editor allows the same. */
    public static final int CONVERSION_TIMEOUT_SECONDS = 120;

    public enum Kind {
        /** The Ogg file is the beatmap's own, byte for byte. */
        EXACT,
        /** Its length matches (an Ogg file as it is, or the file converted). */
        BY_DURATION,
        /** Neither the checksum nor the length matches: nothing was written. */
        MISMATCH,
        /** A format the mod does not read: nothing was written. */
        UNSUPPORTED,
        /** The mod reads the format but the file could not be converted: nothing was written. */
        CONVERSION_FAILED
    }

    /**
     * What happened. {@code converted} says the song went through the converter; {@code formatName} names what an
     * unsupported file turned out to be, when the decoder could tell; {@code reason} is the converter's message for
     * {@link Kind#CONVERSION_FAILED}, already free of the folders above the Minecraft folder.
     */
    public record Result(Kind kind, boolean converted, String formatName, String reason) {
        public boolean accepted() {
            return kind == Kind.EXACT || kind == Kind.BY_DURATION;
        }
    }

    private ChosenAudio() {
    }

    /** True when the picked file has to be converted before it can be checked (it is not an Ogg the game plays). */
    public static boolean needsConversion(Path picked) {
        return !NativeAudio.playsAsIs(picked);
    }

    /**
     * Checks {@code picked} against the beatmap's declared song and, when it matches, puts it at {@code target}.
     * {@code converting} runs once, before a conversion starts, so the screen can say so; it may be null.
     */
    public static Result adopt(Path picked, Path target, String declaredSha256, long declaredDurationMs, Runnable converting)
            throws IOException {
        if (!needsConversion(picked)) {
            boolean exact = declaredSha256 != null && InstalledActions.sha256(picked).equalsIgnoreCase(declaredSha256);
            if (!exact && !durationMatches(picked, declaredDurationMs)) {
                return new Result(Kind.MISMATCH, false, null, null);
            }
            Files.createDirectories(target.toAbsolutePath().normalize().getParent());
            Files.copy(picked, target, StandardCopyOption.REPLACE_EXISTING);
            return new Result(exact ? Kind.EXACT : Kind.BY_DURATION, false, null, null);
        }
        if (converting != null) {
            converting.run();
        }
        Path temporary = MusicBattleTracks.temporaryDirectory().resolve("chosen-song-"
                + Long.toUnsignedString(ThreadLocalRandom.current().nextLong()) + ".ogg");
        try {
            try {
                NativeAudio.convertToVorbis(picked, temporary, 1.0F, CONVERSION_TIMEOUT_SECONDS);
            } catch (UnsupportedFormatException unsupported) {
                return new Result(Kind.UNSUPPORTED, true, unsupported.formatName(), null);
            } catch (IOException failed) {
                return new Result(Kind.CONVERSION_FAILED, true, null, MusicBattleTracks.safeMessage(failed));
            }
            if (!durationMatches(temporary, declaredDurationMs)) {
                return new Result(Kind.MISMATCH, true, null, null);
            }
            Files.createDirectories(target.toAbsolutePath().normalize().getParent());
            try {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException notAtomic) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return new Result(Kind.BY_DURATION, true, null, null);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static boolean durationMatches(Path ogg, long declaredDurationMs) {
        try {
            long duration = MusicBattleTracks.readOggDurationTicks(ogg) * 50L;
            return Math.abs(duration - declaredDurationMs) <= DURATION_TOLERANCE_MS;
        } catch (IOException unreadable) {
            return false;
        }
    }
}

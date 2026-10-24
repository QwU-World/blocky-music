package ru.gora.musicbattle.audio;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import net.neoforged.fml.loading.FMLPaths;
import ru.gora.musicbattle.MusicBattleMod;

/**
 * Every conversion to Ogg Vorbis the mod makes goes through here: the 1.5x/2x copies a speed vote asks for on the
 * server, and the editor's and the osu! importer's MP3-to-OGG on the client.
 *
 * <p>The four of them used to hard-code {@code -acodec libvorbis}. Current Homebrew ffmpeg (9.x) is built without
 * libvorbis and only has FFmpeg's own encoder, {@code vorbis}, so on such a machine every conversion failed in under
 * a second with "Unknown encoder 'libvorbis'" while ffmpeg was plainly installed. This class asks the binary which
 * encoders it has, once, and picks libvorbis when it is there (it sounds better) and the native encoder otherwise.
 * The native one is marked experimental, so it needs {@code -strict experimental}, and it can only write stereo,
 * so it needs {@code -ac 2} or a mono song fails to open the encoder.
 *
 * <p>It lives outside both the client and the server packages because both sides load it: nothing here may touch a
 * client class, or a dedicated server would fail to start a conversion. Locating ffmpeg is here too, because two
 * copies of that search had already drifted apart once.
 */
public final class FfmpegVorbis {
    /** Long enough for a cold start of a large binary, short enough not to hold a worker for long. */
    private static final int PROBE_TIMEOUT_SECONDS = 10;
    /** ffmpeg's report is kept for the log and a status line; anything past this is noise from a broken input. */
    private static final int MAX_OUTPUT_BYTES = 256 * 1024;
    /** A killed process is normally gone at once; this only bounds the wait for one the system is slow to reap. */
    private static final int STOP_WAIT_SECONDS = 5;

    private static volatile Path cachedBinary;
    /** What each binary can encode, so the listing is read once per binary and not before every conversion. */
    private static final Map<Path, Encoder> ENCODERS = new ConcurrentHashMap<>();

    private FfmpegVorbis() {
    }

    /** Which Vorbis encoder a given ffmpeg offers. */
    public enum Encoder {
        /** The Xiph library: the better-sounding choice, and what most Windows and Linux builds carry. */
        LIBVORBIS("libvorbis"),
        /** FFmpeg's own encoder: experimental and stereo-only, but present in every build. */
        NATIVE("vorbis"),
        /** Neither: this ffmpeg cannot write Vorbis at all. */
        NONE("");

        private final String ffmpegName;

        Encoder(String ffmpegName) {
            this.ffmpegName = ffmpegName;
        }

        /** The name ffmpeg knows this encoder by, for {@code -c:a}. */
        public String ffmpegName() {
            return ffmpegName;
        }
    }

    /** There is no ffmpeg on this machine. Its own type, because a player is told something different for it. */
    public static class MissingException extends IOException {
        public MissingException() {
            super("ffmpeg was not found. Install it, or put it next to the game in ffmpeg/bin/.");
        }
    }

    /** This ffmpeg runs, but was built with no Vorbis encoder at all. A failed conversion as far as a player knows. */
    public static class NoEncoderException extends IOException {
        public NoEncoderException(Path binary) {
            super("ffmpeg at '" + binary + "' has no Vorbis encoder (neither libvorbis nor vorbis).");
        }
    }

    /**
     * The encoder to use, read from the output of {@code ffmpeg -hide_banner -encoders}.
     *
     * <p>Each encoder line is a six-character flag column whose first letter is the media type, then the name. The
     * legend above the list has the same shape with "=" for a name, so it can never match an encoder.
     */
    public static Encoder chooseEncoder(String encodersListing) {
        boolean nativeVorbis = false;
        if (encodersListing != null) {
            for (String line : encodersListing.split("\\R")) {
                String[] tokens = line.trim().split("\\s+");
                if (tokens.length < 2 || tokens[0].length() != 6 || tokens[0].charAt(0) != 'A') {
                    continue;
                }
                if (tokens[1].equals(Encoder.LIBVORBIS.ffmpegName())) {
                    return Encoder.LIBVORBIS;
                }
                if (tokens[1].equals(Encoder.NATIVE.ffmpegName())) {
                    nativeVorbis = true;
                }
            }
        }
        return nativeVorbis ? Encoder.NATIVE : Encoder.NONE;
    }

    /** The codec part of the command line for an encoder. */
    public static List<String> codecArguments(Encoder encoder) {
        return switch (encoder) {
            case LIBVORBIS -> List.of("-c:a", "libvorbis");
            case NATIVE -> List.of("-c:a", "vorbis", "-strict", "experimental", "-ac", "2");
            case NONE -> throw new IllegalArgumentException("There is no Vorbis encoder to build arguments for.");
        };
    }

    /**
     * Everything after the binary for one conversion. The format is named rather than guessed from the target's
     * extension, because the target is a temporary file whose name does not end in .ogg.
     *
     * @param audioFilter an ffmpeg audio filter such as {@code atempo=2.0}, or {@code null} for none
     */
    public static List<String> conversionArguments(Encoder encoder, Path source, Path target, String audioFilter) {
        List<String> arguments = new ArrayList<>(List.of("-y", "-hide_banner", "-loglevel", "error",
                "-i", source.toString(), "-vn"));
        if (audioFilter != null && !audioFilter.isBlank()) {
            arguments.add("-filter:a");
            arguments.add(audioFilter);
        }
        arguments.addAll(codecArguments(encoder));
        arguments.add("-f");
        arguments.add("ogg");
        arguments.add(target.toString());
        return arguments;
    }

    /**
     * Converts a file to Ogg Vorbis with this machine's ffmpeg, waiting for it: call it on a worker thread only.
     *
     * <p>The result only replaces {@code target} once it is a finished Ogg Vorbis stream, so a failed or stopped
     * conversion never leaves a half-written file where a player or the battle would pick it up.
     *
     * @throws MissingException when there is no ffmpeg
     * @throws IOException when ffmpeg is there but the conversion did not produce a Vorbis file; the message is
     *     English and meant for the log and a status line, not for chat
     */
    public static void convert(Path source, Path target, String audioFilter, int timeoutSeconds) throws IOException {
        Path binary = locate();
        Encoder encoder = encoder(binary);
        if (encoder == Encoder.NONE) {
            throw new NoEncoderException(binary);
        }
        convert(binary, encoder, source, target, audioFilter, timeoutSeconds);
    }

    /** {@link #convert(Path, Path, String, int)} with the binary and encoder chosen by the caller. */
    public static void convert(Path binary, Encoder encoder, Path source, Path target, String audioFilter,
                               int timeoutSeconds) throws IOException {
        target = target.toAbsolutePath().normalize();
        Path directory = target.getParent();
        Files.createDirectories(directory);
        // A unique name, because the battle and a DJ panel can ask for the same copy at once, and a name that does
        // not end in .ogg, because the editor's Audio button lists every .ogg in the folder it converts into.
        Path temporary = Files.createTempFile(directory, "." + target.getFileName() + ".", ".part");
        try {
            List<String> command = new ArrayList<>();
            command.add(binary.toString());
            command.addAll(conversionArguments(encoder, source.toAbsolutePath().normalize(), temporary, audioFilter));
            Result result = run(command, timeoutSeconds);
            if (result.timedOut()) {
                throw new IOException("ffmpeg took longer than " + timeoutSeconds + " s and was stopped.");
            }
            if (result.exitCode() != 0) {
                throw new IOException("ffmpeg (" + encoder.ffmpegName() + ") failed with exit code "
                        + result.exitCode() + ": " + (result.output().isBlank() ? "no output" : result.output()));
            }
            if (!isOggVorbis(temporary)) {
                throw new IOException("ffmpeg (" + encoder.ffmpegName() + ") did not write an Ogg Vorbis file.");
            }
            try {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException leftover) {
                // Windows can still hold the file a moment after ffmpeg is gone. Throwing from here would replace the
                // reason the conversion failed with a complaint about a temporary file nobody asked about.
            }
        }
    }

    /**
     * Whether a file starts like an Ogg Vorbis stream: an Ogg page whose first packet is a Vorbis identification
     * header. It says nothing about the length; callers that care compare durations themselves.
     */
    public static boolean isOggVorbis(Path file) {
        try (InputStream input = Files.newInputStream(file)) {
            byte[] head = input.readNBytes(27 + 255 + 7);
            if (head.length < 28 || head[0] != 'O' || head[1] != 'g' || head[2] != 'g' || head[3] != 'S') {
                return false;
            }
            int packet = 27 + (head[26] & 0xFF);
            return head.length >= packet + 7 && head[packet] == 1
                    && new String(head, packet + 1, 6, StandardCharsets.US_ASCII).equals("vorbis");
        } catch (IOException exception) {
            return false;
        }
    }

    /**
     * The Vorbis encoder this binary has, asking it the first time and remembering the answer for the session.
     * A probe that could not run is not remembered, so a binary that was busy once is asked again next time.
     */
    public static Encoder encoder(Path binary) throws IOException {
        Encoder known = ENCODERS.get(binary);
        if (known != null) {
            return known;
        }
        Result result = run(List.of(binary.toString(), "-hide_banner", "-encoders"), PROBE_TIMEOUT_SECONDS);
        if (result.timedOut() || result.exitCode() != 0) {
            throw new IOException("ffmpeg at '" + binary + "' could not list its encoders.");
        }
        Encoder chosen = chooseEncoder(result.output());
        ENCODERS.put(binary, chosen);
        MusicBattleMod.LOGGER.info("Blocky Music converts to Ogg Vorbis with ffmpeg's '{}' encoder ({}).",
                chosen == Encoder.NONE ? "none" : chosen.ffmpegName(), binary);
        return chosen;
    }

    /** The ffmpeg to use, remembered for the session once found. */
    public static Path locate() throws MissingException {
        Path known = cachedBinary;
        if (known != null) {
            return known;
        }
        for (Path candidate : candidates()) {
            if (runs(candidate)) {
                Path chosen = candidate.isAbsolute() ? candidate.toAbsolutePath().normalize() : candidate;
                cachedBinary = chosen;
                MusicBattleMod.LOGGER.info("Blocky Music is using ffmpeg at '{}'.", chosen);
                return chosen;
            }
        }
        throw new MissingException();
    }

    private static boolean windows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("win");
    }

    /**
     * Where to look, in the order a player would expect it to be found. A game started from a launcher rarely
     * carries the shell's PATH, so the places each system really keeps ffmpeg come before the bare name.
     */
    private static List<Path> candidates() {
        String name = windows() ? "ffmpeg.exe" : "ffmpeg";
        List<Path> candidates = new ArrayList<>();
        Path gameDirectory = gameDirectory();
        if (gameDirectory != null) {
            candidates.add(gameDirectory.resolve("ffmpeg").resolve("bin").resolve(name));
            candidates.add(gameDirectory.resolve("ffmpeg").resolve(name));
            candidates.add(gameDirectory.resolve("music_battle_raw").resolve(name));
            candidates.add(gameDirectory.resolve("music_battle_raw").resolve("ffmpeg").resolve("bin").resolve(name));
            candidates.add(gameDirectory.resolve(name));
            candidates.add(gameDirectory.resolve("mods").resolve(name));
        }
        if (windows()) {
            candidates.add(Path.of("C:\\ffmpeg\\bin\\ffmpeg.exe"));
            candidates.add(Path.of("C:\\Program Files\\ffmpeg\\bin\\ffmpeg.exe"));
            candidates.add(Path.of("C:\\Program Files (x86)\\ffmpeg\\bin\\ffmpeg.exe"));
        } else {
            candidates.add(Path.of("/opt/homebrew/bin/ffmpeg"));
            candidates.add(Path.of("/usr/local/bin/ffmpeg"));
            candidates.add(Path.of("/usr/bin/ffmpeg"));
            candidates.add(Path.of("/snap/bin/ffmpeg"));
        }
        // Last: whatever the shell would find, so a broken PATH entry never wins over a working binary above.
        candidates.add(Path.of(name));
        return candidates;
    }

    /** The game folder, or none when this runs outside a loaded game (the regression harness). */
    private static Path gameDirectory() {
        try {
            return FMLPaths.GAMEDIR.get().toAbsolutePath().normalize();
        } catch (RuntimeException | LinkageError exception) {
            return null;
        }
    }

    /** A candidate is only believed once it has answered {@code -version}. */
    private static boolean runs(Path candidate) {
        if (candidate.isAbsolute() && !Files.isRegularFile(candidate)) {
            return false;
        }
        try {
            Result result = run(List.of(candidate.toString(), "-version"), PROBE_TIMEOUT_SECONDS);
            return !result.timedOut() && result.exitCode() == 0;
        } catch (IOException exception) {
            return false;
        }
    }

    private record Result(int exitCode, boolean timedOut, String output) {
    }

    /**
     * Kills a process and gives it a moment to be gone. destroyForcibly only asks; until the process has exited,
     * Windows keeps its output file open and the caller's cleanup cannot delete it.
     */
    private static void stop(Process process) {
        process.destroyForcibly();
        try {
            process.waitFor(STOP_WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Runs a process to the end or the timeout. Its output is drained on a separate thread while it runs: a process
     * that fills the pipe stops until somebody reads it, and reading only after waiting would turn a chatty failure
     * into a timeout.
     */
    private static Result run(List<String> command, int timeoutSeconds) throws IOException {
        Process process;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
        } catch (IOException exception) {
            throw new IOException("ffmpeg could not be started: " + exception.getMessage(), exception);
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Thread drain = Thread.ofVirtual().start(() -> {
            byte[] buffer = new byte[8192];
            try (InputStream input = process.getInputStream()) {
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    synchronized (output) {
                        if (output.size() < MAX_OUTPUT_BYTES) {
                            output.write(buffer, 0, Math.min(read, MAX_OUTPUT_BYTES - output.size()));
                        }
                    }
                }
            } catch (IOException ignored) {
                // The stream closes when the process is destroyed; what was read so far is all there is.
            }
        });
        try {
            if (!process.waitFor(Math.max(1, timeoutSeconds), TimeUnit.SECONDS)) {
                stop(process);
                return new Result(-1, true, "");
            }
            drain.join(TimeUnit.SECONDS.toMillis(2));
        } catch (InterruptedException exception) {
            // The catch has cleared the interrupt, which lets stop() wait; it is restored for the caller afterwards.
            stop(process);
            Thread.currentThread().interrupt();
            throw new IOException("The conversion was interrupted.", exception);
        }
        String text;
        synchronized (output) {
            text = output.toString(StandardCharsets.UTF_8).trim();
        }
        return new Result(process.exitValue(), false, text);
    }
}

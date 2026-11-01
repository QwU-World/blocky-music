package ru.gora.musicbattle.audio;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import ru.gora.musicbattle.audio.codec.AudioDecoders;
import ru.gora.musicbattle.audio.codec.AudioFormatException;
import ru.gora.musicbattle.audio.codec.ChannelDownmix;
import ru.gora.musicbattle.audio.codec.PcmSource;
import ru.gora.musicbattle.audio.codec.PcmStream;
import ru.gora.musicbattle.audio.codec.PeakLimiter;
import ru.gora.musicbattle.audio.codec.SincResampler;
import ru.gora.musicbattle.audio.codec.TimeStretch;
import ru.gora.musicbattle.audio.codec.UnsupportedFormatException;
import ru.gora.musicbattle.audio.codec.VorbisDecoder;
import ru.gora.musicbattle.audio.codec.VorbisEncoder;

/**
 * Every conversion to Ogg Vorbis the mod makes goes through here, in plain Java inside the game: the 1.5x/2x copies a
 * speed vote asks for on the server, and the songs in the other formats {@link AudioDecoders} reads (MP3, WAV, FLAC and
 * more) that the chart editor and the osu! importer bring in on the client. Nobody has to install anything, and a dedicated server converts the same way a player's game does.
 *
 * <p>One conversion is: decode by the file's content ({@link AudioDecoders}; an Ogg Vorbis song on exactly the
 * samples Minecraft plays of it), replace samples that are not numbers, fold more than two channels into stereo,
 * bring anything above 48 kHz down to 48 kHz, change the tempo when asked ({@link TimeStretch}), and encode
 * ({@link VorbisEncoder}). The encoder reads its input twice, so the file is decoded twice; decoding is a small part of
 * the time, and nothing the size of the song is held in memory.
 *
 * <p>It lives outside both the client and the server packages because both sides call it: nothing here may touch a
 * Minecraft class, or a dedicated server would fail to load it.
 */
public final class NativeAudio {
    /** A ceiling for callers that name none: far beyond any real song, short of holding a worker for good. */
    public static final int DEFAULT_TIMEOUT_SECONDS = 120;
    /**
     * A temporary file of a conversion older than this belongs to no running conversion (every one is stopped after
     * its timeout, two minutes at most); it is a leftover of a game that was killed part way through one.
     */
    public static final long STALE_TEMPORARY_MILLIS = TimeUnit.HOURS.toMillis(1);
    /** A target that Windows still holds open (the game playing it, a player downloading it) is retried this often. */
    private static final int MOVE_ATTEMPTS = 10;
    private static final long MOVE_RETRY_MILLIS = 100L;
    /**
     * The temporary files: {@code .<target name>.<random number>.part}, next to the target. The name does not end in
     * .ogg, because the editor's Audio button lists every .ogg in the folder it converts into, and the dot in front
     * hides it from people browsing the folder.
     */
    private static final String TEMPORARY_SUFFIX = ".part";
    private static final Pattern TEMPORARY_NAME = Pattern.compile("\\..+\\.[0-9]+\\.part");
    /** A sample beyond this is damage, not music (36 dB over full scale); the stretcher and encoder use the same bound. */
    private static final float MAX_SAMPLE = 64f;
    /**
     * The Vorbis comment every sped-up copy carries, with {@link #SPEED_COPY_VERSION} as its value. A server keeps the
     * copies it made for as long as the song does not change, so without it a copy made by an older build (ffmpeg's
     * atempo, or this converter before its timeline matched the game's) would be played for good, early or smeared.
     */
    public static final String SPEED_COPY_FIELD = "BLOCKYMUSIC_STRETCH";
    /**
     * Raise this with any change that makes a sped-up copy come out different in time or sound: the decoder's
     * timeline, the stretcher, the encoder. A server then treats every copy with another value, or none, as stale and
     * makes it again the next time a battle asks for it.
     */
    public static final int SPEED_COPY_VERSION = 2;
    /**
     * How often a sped-up copy is encoded again when the first encode still went over full scale, each time turned
     * down by the overshoot measured (see {@link #keepBelowFullScale}).
     */
    private static final int PEAK_RETRIES = 3;
    /** A retry is turned down by the measured peak and this much more, so it lands just under full scale. */
    private static final float PEAK_RETRY_MARGIN = 0.98f;

    private NativeAudio() {
    }

    /** {@link #convertToVorbis(Path, Path, float, int)} with {@link #DEFAULT_TIMEOUT_SECONDS}. */
    public static void convertToVorbis(Path source, Path target, float speed) throws IOException {
        convertToVorbis(source, target, speed, DEFAULT_TIMEOUT_SECONDS);
    }

    /**
     * Converts a song to Ogg Vorbis, playing {@code speed} times as fast (1 is a plain conversion). It works on the
     * calling thread, so call it on a worker only; a four-minute song takes one to two seconds.
     *
     * <p>The result only replaces {@code target} once it is a finished Ogg Vorbis stream, so a failed or stopped
     * conversion never leaves a half-written file where a player or the battle would pick it up. Interrupting the
     * thread stops the conversion within milliseconds and deletes the temporary file; the interrupt stays set. The
     * finished file gets the permissions any new file in that folder gets. Temporary files that a killed game left in
     * the folder are deleted once they are an hour old.
     *
     * <p>A copy at another speed than 1 is marked as this build's ({@link #SPEED_COPY_FIELD}).
     *
     * @throws UnsupportedFormatException when the song is in a format no decoder in {@link AudioDecoders} reads
     * @throws AudioFormatException when it is in one they read, but damaged, or holds no sound at all
     * @throws IOException when the files cannot be read or written, the conversion took longer than
     *     {@code timeoutSeconds}, or it was interrupted ({@link InterruptedIOException}); also when the converter
     *     itself failed unexpectedly, with that failure as the cause, so the caller's log has it once. Messages are
     *     English, for the log and a status line
     */
    public static void convertToVorbis(Path source, Path target, float speed, int timeoutSeconds) throws IOException {
        if (!(speed >= TimeStretch.MIN_SPEED && speed <= TimeStretch.MAX_SPEED)) {
            throw new IllegalArgumentException("Speed must be between " + TimeStretch.MIN_SPEED + " and "
                    + TimeStretch.MAX_SPEED + ", not " + speed);
        }
        try {
            convert(source.toAbsolutePath().normalize(), target.toAbsolutePath().normalize(), speed, timeoutSeconds);
        } catch (RuntimeException unexpected) {
            // Every failure the converter knows about is an IOException with a reason. Anything else is a bug, and the
            // callers only expect IOExceptions: unwrapped, it would end a worker without a word and leave the editor
            // saying "converting" for good.
            throw new IOException("Blocky Music's converter failed unexpectedly (" + unexpected.getClass().getSimpleName()
                    + "); the details are in the log.", unexpected);
        }
    }

    private static void convert(Path input, Path output, float speed, int timeoutSeconds) throws IOException {
        // Before any file is created: a song the mod cannot read fails here, with nothing to clean up.
        AudioDecoders.Kind kind = AudioDecoders.detect(input);
        Path directory = output.getParent();
        Files.createDirectories(directory);
        deleteStaleTemporaryFiles(directory, STALE_TEMPORARY_MILLIS);
        Path temporary = createTemporary(directory, output.getFileName().toString());
        try {
            long started = System.nanoTime();
            long deadline = started + TimeUnit.SECONDS.toNanos(Math.max(1, timeoutSeconds));
            VorbisEncoder.Settings settings = new VorbisEncoder.Settings();
            if (speed != 1.0F) {
                settings.comment(SPEED_COPY_FIELD, Integer.toString(SPEED_COPY_VERSION));
            }
            long frames = encode(temporary, () -> pipeline(input, kind, speed, deadline, timeoutSeconds, 1f), settings);
            if (frames == 0L) {
                // A WAV with an empty data chunk, or an Ogg cut off right after its headers: an empty song would pass
                // for a converted one, and the beatmap would be silent with nothing said about why.
                throw new AudioFormatException("The file holds no sound (it decodes to no samples at all).");
            }
            if (!isOggVorbis(temporary)) {
                throw new IOException("The conversion did not produce an Ogg Vorbis file.");
            }
            if (speed != 1.0F) {
                keepBelowFullScale(temporary, frames, settings, started, deadline,
                        gain -> pipeline(input, kind, speed, deadline, timeoutSeconds, gain));
            }
            move(temporary, output);
        } finally {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException leftover) {
                // Windows can hold a file a moment longer than the stream that wrote it. Throwing from here would
                // replace the reason the conversion failed with a complaint about a temporary file nobody asked about;
                // the next conversion into this folder deletes it once it is old.
            }
        }
    }

    /**
     * A new, empty temporary file next to {@code target}, named like this class's own, so that a game killed before it
     * is used up leaves nothing behind for good ({@link #deleteStaleTemporaryFiles}). For a caller that has to keep
     * bytes on disk for a conversion it starts right away, such as the osu! importer with a map's song.
     */
    public static Path createTemporaryFile(Path target) throws IOException {
        Path output = target.toAbsolutePath().normalize();
        Files.createDirectories(output.getParent());
        return createTemporary(output.getParent(), output.getFileName().toString());
    }

    /**
     * A new, empty temporary file next to the target. Not {@link Files#createTempFile}: that creates it readable by its
     * owner only, and the finished file, which is this one moved into place, would keep that, so a server's other
     * users or a backup tool could not read the songs. A plain new file gets the folder's usual permissions.
     */
    private static Path createTemporary(Path directory, String targetName) throws IOException {
        for (int attempt = 0; ; attempt++) {
            Path candidate = directory.resolve("." + targetName + "."
                    + Long.toUnsignedString(ThreadLocalRandom.current().nextLong()) + TEMPORARY_SUFFIX);
            try {
                return Files.createFile(candidate);
            } catch (FileAlreadyExistsException taken) {
                if (attempt >= 100) {
                    throw taken;
                }
            }
        }
    }

    /**
     * Deletes the temporary files of conversions into {@code directory} that are older than {@code olderThanMillis}:
     * a game killed during a conversion leaves its file behind, and nothing else would ever remove it. Only this
     * class's own temporary files are touched, by their name. A failure is ignored, since another leftover is no worse
     * than this one, and an unreadable folder fails the conversion that follows with a reason worth reporting.
     */
    public static void deleteStaleTemporaryFiles(Path directory, long olderThanMillis) {
        long cutoff = System.currentTimeMillis() - olderThanMillis;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory,
                entry -> TEMPORARY_NAME.matcher(String.valueOf(entry.getFileName())).matches())) {
            for (Path file : files) {
                try {
                    if (Files.isRegularFile(file) && Files.getLastModifiedTime(file).toMillis() < cutoff) {
                        Files.deleteIfExists(file);
                    }
                } catch (IOException ignored) {
                    // See the comment above: the conversion does not depend on it.
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // The same: a folder that cannot be listed is reported by the conversion itself.
        }
    }

    private static long encode(Path file, PcmSource pcm, VorbisEncoder.Settings settings) throws IOException {
        try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(file), 1 << 16)) {
            return VorbisEncoder.encode(pcm, out, settings);
        }
    }

    /** The song's decoding for one encode of a copy, turned down by the given gain after the limiter. */
    @FunctionalInterface
    private interface LimitedPipeline {
        PcmStream open(float gain) throws IOException;
    }

    /**
     * Makes sure a 1.5x or 2x copy does not go over full scale where the game plays it. The limiter holds the copy at
     * {@link PeakLimiter#CEILING} before it is encoded, and that keeps the encoder's overshoot under full scale for
     * nearly every song; but a brick-walled master (a 320 kb/s MP3 with thousands of samples at full scale) still had
     * 12 and 13 samples over it in its copies, 0.16 dB over. So the finished copy is decoded as the game decodes it,
     * and while it peaks over full scale it is encoded again, turned down as a whole by that overshoot (a fraction of
     * a decibel), at most {@link #PEAK_RETRIES} times. Lowering the limiter's ceiling instead did not help: the
     * overshoot is the encoder's noise at the stretcher's grain joins, where the copy is under the ceiling already,
     * and it follows the level of the whole copy, not the ceiling. A song that stays under on the first encode (nearly
     * all) costs one decode and nothing else. A retry is only begun when there is time for it before the conversion's
     * deadline; one that fails or comes out no better leaves the copy made before it.
     */
    private static void keepBelowFullScale(Path copy, long frames, VorbisEncoder.Settings settings, long started,
                                           long deadline, LimitedPipeline pipeline) throws IOException {
        float peak = encodedPeak(copy);
        float gain = 1f;
        for (int attempt = 0; attempt < PEAK_RETRIES && peak > 1.0f; attempt++) {
            long now = System.nanoTime();
            if (deadline - now < 2 * (now - started)) {
                return;
            }
            float tried = gain / peak * PEAK_RETRY_MARGIN;
            Path retry = createTemporary(copy.getParent(), copy.getFileName().toString());
            try {
                long again = encode(retry, () -> pipeline.open(tried), settings);
                float retryPeak = again == frames && isOggVorbis(retry) ? encodedPeak(retry) : Float.POSITIVE_INFINITY;
                if (retryPeak < peak) {
                    Files.move(retry, copy, StandardCopyOption.REPLACE_EXISTING);
                    peak = retryPeak;
                    gain = tried;
                } else {
                    // The encoder came out louder this time; try from a little further down.
                    gain = tried;
                }
            } catch (InterruptedIOException stopped) {
                throw stopped;
            } catch (IOException failed) {
                // The deadline, most likely: the copy already made stands.
                return;
            } finally {
                try {
                    Files.deleteIfExists(retry);
                } catch (IOException leftover) {
                    // Deleted with the other leftovers once it is old; see deleteStaleTemporaryFiles.
                }
            }
        }
    }

    /** The largest sample of an Ogg Vorbis file as the game plays it ({@link VorbisDecoder.Timeline#MINECRAFT}). */
    static float encodedPeak(Path file) throws IOException {
        try (PcmStream stream = VorbisDecoder.open(new java.io.BufferedInputStream(Files.newInputStream(file), 1 << 16),
                VorbisDecoder.Timeline.MINECRAFT)) {
            float[][] buffer = new float[stream.channels()][4096];
            float peak = 0f;
            int read;
            while ((read = stream.read(buffer, 0, 4096)) >= 0) {
                for (float[] channel : buffer) {
                    for (int i = 0; i < read; i++) {
                        float a = Math.abs(channel[i]);
                        if (a > peak) {
                            peak = a;
                        }
                    }
                }
            }
            return peak;
        }
    }

    /** One decoding of the song, as the encoder will see it: stereo or mono, at most 48 kHz, at the asked speed. */
    private static PcmStream pipeline(Path input, AudioDecoders.Kind kind, float speed, long deadline,
                                      int timeoutSeconds, float gain) throws IOException {
        PcmStream stream = AudioDecoders.open(input, kind);
        try {
            stream = new Cleaned(stream);
            stream = ChannelDownmix.toStereo(stream, kind.layout());
            stream = SincResampler.to48k(stream);
            stream = TimeStretch.wrap(stream, speed);
            if (speed != 1f) {
                // The 1.5x and 2x copies: their grain joins make the encoder overshoot more than the song itself
                // does, so their peaks are held below full scale (see PeakLimiter).
                stream = PeakLimiter.wrap(stream);
                if (gain < 1f) {
                    stream = new Gain(stream, gain);
                }
            }
            return new Deadline(stream, deadline, timeoutSeconds);
        } catch (RuntimeException exception) {
            stream.close();
            throw exception;
        }
    }

    /**
     * Whether Minecraft plays this file as it is, so it needs no conversion: Ogg Vorbis from its first byte with one or
     * two channels. The game refuses Vorbis with more channels than that (a 5.1 file stays silent), and an Ogg song
     * behind an ID3 tag, so both are converted like any other format.
     */
    public static boolean playsAsIs(Path file) {
        try (InputStream input = Files.newInputStream(file)) {
            return playsAsIs(input.readNBytes(27 + 255 + 12));
        } catch (IOException exception) {
            return false;
        }
    }

    /** {@link #playsAsIs(Path)} for the first bytes of a file already in memory. */
    public static boolean playsAsIs(byte[] head) {
        if (!isOggVorbis(head)) {
            return false;
        }
        int packet = 27 + (head[26] & 0xFF);
        int channels = head.length > packet + 11 ? head[packet + 11] & 0xFF : 0;
        return channels == 1 || channels == 2;
    }

    /**
     * The {@link #SPEED_COPY_FIELD} mark of a sped-up copy, or 0 when it has none, or is not Ogg Vorbis at all, or
     * cannot be read. Only the headers are read.
     */
    public static int speedCopyVersion(Path file) {
        try (InputStream input = new java.io.BufferedInputStream(Files.newInputStream(file))) {
            String prefix = SPEED_COPY_FIELD + "=";
            for (String field : VorbisDecoder.comments(input)) {
                // Field names are case-insensitive in Vorbis comments.
                if (field.regionMatches(true, 0, prefix, 0, prefix.length())) {
                    try {
                        return Integer.parseInt(field.substring(prefix.length()).trim());
                    } catch (NumberFormatException damaged) {
                        return 0;
                    }
                }
            }
            return 0;
        } catch (IOException | RuntimeException unreadable) {
            return 0;
        }
    }

    /**
     * Whether a file starts like an Ogg Vorbis stream: an Ogg page whose first packet is a Vorbis identification
     * header. It says nothing about the length; callers that care compare durations themselves.
     */
    public static boolean isOggVorbis(Path file) {
        try (InputStream input = Files.newInputStream(file)) {
            return isOggVorbis(input.readNBytes(27 + 255 + 7));
        } catch (IOException exception) {
            return false;
        }
    }

    /** {@link #isOggVorbis(Path)} for the first bytes of a file already in memory. */
    public static boolean isOggVorbis(byte[] head) {
        if (head == null || head.length < 28 || head[0] != 'O' || head[1] != 'g' || head[2] != 'g' || head[3] != 'S') {
            return false;
        }
        int packet = 27 + (head[26] & 0xFF);
        return head.length >= packet + 7 && head[packet] == 1
                && new String(head, packet + 1, 6, StandardCharsets.US_ASCII).equals("vorbis");
    }

    /**
     * Replaces the target with the finished file. An atomic move where the file system has one, so nobody ever reads
     * half a file. Windows refuses to replace a file another program has open (the game playing the old copy, the
     * server sending it to a player), so there the move is tried again for about a second before giving up.
     */
    private static void move(Path temporary, Path target) throws IOException {
        for (int attempt = 1; ; attempt++) {
            try {
                try {
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException exception) {
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
                }
                return;
            } catch (FileSystemException exception) {
                boolean busy = exception instanceof AccessDeniedException
                        || exception.getClass() == FileSystemException.class;
                if (!busy || !windows() || attempt >= MOVE_ATTEMPTS) {
                    throw exception;
                }
                try {
                    Thread.sleep(MOVE_RETRY_MILLIS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    InterruptedIOException stopped = new InterruptedIOException("The conversion was interrupted.");
                    stopped.addSuppressed(exception);
                    throw stopped;
                }
            }
        }
    }

    private static boolean windows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("win");
    }

    /**
     * Takes a sample that is not a number, or beyond {@link #MAX_SAMPLE}, as silence, right where it leaves the
     * decoder. A damaged float WAV can hold one, and the resampler, the stretcher and the encoder all carry state from
     * sample to sample: one NaN there used to spoil the onsets of the rest of the song in the stretcher.
     */
    /** The whole stream times a constant, for a copy encoded again because it went over full scale. */
    private static final class Gain implements PcmStream {
        private final PcmStream source;
        private final float gain;

        Gain(PcmStream source, float gain) {
            this.source = source;
            this.gain = gain;
        }

        @Override
        public int sampleRate() {
            return source.sampleRate();
        }

        @Override
        public int channels() {
            return source.channels();
        }

        @Override
        public long frameCountHint() {
            return source.frameCountHint();
        }

        @Override
        public int read(float[][] out, int offset, int frames) throws IOException {
            int read = source.read(out, offset, frames);
            for (int c = 0, channels = source.channels(); c < channels && read > 0; c++) {
                float[] channel = out[c];
                for (int i = offset, end = offset + read; i < end; i++) {
                    channel[i] *= gain;
                }
            }
            return read;
        }

        @Override
        public void close() throws IOException {
            source.close();
        }
    }

    private static final class Cleaned implements PcmStream {
        private final PcmStream source;

        Cleaned(PcmStream source) {
            this.source = source;
        }

        @Override
        public int sampleRate() {
            return source.sampleRate();
        }

        @Override
        public int channels() {
            return source.channels();
        }

        @Override
        public long frameCountHint() {
            return source.frameCountHint();
        }

        @Override
        public int read(float[][] out, int offset, int frames) throws IOException {
            int read = source.read(out, offset, frames);
            for (int c = 0, channels = source.channels(); c < channels && read > 0; c++) {
                float[] channel = out[c];
                for (int i = offset, end = offset + read; i < end; i++) {
                    if (!(Math.abs(channel[i]) <= MAX_SAMPLE)) {
                        channel[i] = 0f;
                    }
                }
            }
            return read;
        }

        @Override
        public void close() throws IOException {
            source.close();
        }
    }

    /** Stops the conversion once its time is up. Checked on every read, which the encoder makes every few blocks. */
    private static final class Deadline implements PcmStream {
        private final PcmStream source;
        private final long deadline;
        private final int timeoutSeconds;

        Deadline(PcmStream source, long deadline, int timeoutSeconds) {
            this.source = source;
            this.deadline = deadline;
            this.timeoutSeconds = timeoutSeconds;
        }

        @Override
        public int sampleRate() {
            return source.sampleRate();
        }

        @Override
        public int channels() {
            return source.channels();
        }

        @Override
        public long frameCountHint() {
            return source.frameCountHint();
        }

        @Override
        public int read(float[][] out, int offset, int frames) throws IOException {
            if (System.nanoTime() - deadline > 0L) {
                throw new IOException("The conversion took longer than " + timeoutSeconds + " s and was stopped.");
            }
            return source.read(out, offset, frames);
        }

        @Override
        public void close() throws IOException {
            source.close();
        }
    }
}

package ru.gora.musicbattle.audio.codec;

import java.io.Closeable;
import java.io.IOException;

/**
 * Decoded audio, read a piece at a time so a fifteen-minute song never has to sit in memory whole.
 *
 * <p>Samples are planar floats, nominally in [-1, 1]: {@code out[channel][offset + i]}. Everything in this package is
 * plain Java with no Minecraft classes, so the regression checks run it outside the game and a dedicated server runs
 * it without the client's native libraries.
 *
 * <p>Long loops check {@link Thread#isInterrupted()} and throw {@link java.io.InterruptedIOException}: the server
 * stops a conversion by interrupting its worker when it shuts down, and the temporary file is only deleted once the
 * conversion has noticed.
 */
public interface PcmStream extends Closeable {
    int sampleRate();

    int channels();

    /** The number of frames the container promises, or -1 when only reading to the end can tell. */
    default long frameCountHint() {
        return -1L;
    }

    /**
     * Reads up to {@code frames} frames into {@code out[c][offset..]}.
     *
     * @return how many frames were read, never 0 when {@code frames > 0}, or -1 at the end of the audio
     * @throws AudioFormatException when the data turns out to be damaged part way through
     */
    int read(float[][] out, int offset, int frames) throws IOException;
}

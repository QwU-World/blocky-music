package ru.gora.musicbattle.client;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.function.BooleanSupplier;
import java.util.concurrent.CancellationException;
import javax.sound.sampled.AudioFormat;
import net.minecraft.client.sounds.AudioStream;

/** Frame-accurate PCM seek/padding, preserving decoder chunks that overrun a seek. */
final class CalibratedAudioStream implements AudioStream {
    private final AudioStream delegate;
    private final int frameSize;
    private long silenceBytes;
    private ByteBuffer pending;

    CalibratedAudioStream(AudioStream delegate, double startSeconds, BooleanSupplier stopped) throws IOException {
        this.delegate = delegate;
        AudioFormat format = delegate.getFormat();
        frameSize = format.getFrameSize();
        if (frameSize <= 0 || !Float.isFinite(format.getFrameRate()) || format.getFrameRate() <= 0.0F) {
            throw new IOException("Invalid PCM format for calibration");
        }
        long frames = Math.round(Math.abs(startSeconds) * format.getFrameRate());
        long bytes = Math.multiplyExact(frames, frameSize);
        if (stopped.getAsBoolean()) {
            throw new CancellationException("Calibrated audio was stopped");
        }
        if (startSeconds < 0.0D) {
            silenceBytes = bytes;
            return;
        }
        while (bytes > 0L) {
            if (stopped.getAsBoolean()) {
                throw new CancellationException("Calibrated audio was stopped");
            }
            ByteBuffer decoded = delegate.read((int) Math.min(bytes, 65_536L));
            if (decoded == null || !decoded.hasRemaining()) {
                break;
            }
            int skip = (int) Math.min(bytes, decoded.remaining());
            decoded.position(decoded.position() + skip);
            bytes -= skip;
            if (decoded.hasRemaining()) {
                pending = decoded;
            }
        }
        if (stopped.getAsBoolean()) {
            throw new CancellationException("Calibrated audio was stopped");
        }
    }

    @Override
    public AudioFormat getFormat() {
        return delegate.getFormat();
    }

    @Override
    public ByteBuffer read(int size) throws IOException {
        if (silenceBytes == 0L && (pending == null || !pending.hasRemaining())) {
            return delegate.read(size);
        }
        int alignedSize = Math.max(frameSize, size - Math.floorMod(size, frameSize));
        ByteBuffer output = ByteBuffer.allocateDirect(alignedSize).order(ByteOrder.nativeOrder());
        int silence = (int) Math.min(silenceBytes, alignedSize);
        // Direct buffers are zero-initialized; decoded OGG uses signed PCM.
        output.position(silence);
        silenceBytes -= silence;
        while (output.hasRemaining()) {
            if (pending == null || !pending.hasRemaining()) {
                pending = delegate.read(output.remaining());
                if (pending == null || !pending.hasRemaining()) {
                    break;
                }
            }
            int count = Math.min(output.remaining(), pending.remaining());
            int previousLimit = pending.limit();
            pending.limit(pending.position() + count);
            output.put(pending);
            pending.limit(previousLimit);
        }
        output.flip();
        return output;
    }

    @Override
    public void close() throws IOException {
        pending = null;
        delegate.close();
    }
}

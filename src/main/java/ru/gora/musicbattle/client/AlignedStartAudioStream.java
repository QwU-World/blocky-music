package ru.gora.musicbattle.client;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.function.DoubleSupplier;
import javax.sound.sampled.AudioFormat;
import net.minecraft.client.sounds.AudioStream;
import ru.gora.musicbattle.data.MusicStartAlignment;

/**
 * The battle song's stream, lined up with the battle clock at the moment it starts to play.
 *
 * <p>Minecraft reads a stream for the first time on the sound thread just before it starts the source, after the song
 * has already been decoded up to the tick it was asked for. That decode is what made every seek late (see
 * {@link MusicStartAlignment}); this stream asks for the battle tick again at that first read and drops, or pads with
 * silence, the difference, then tells its owner which battle tick its first sample belongs to and when it was lined
 * up, so the owner can learn how long a stream takes from here to the speakers.
 */
public final class AlignedStartAudioStream implements AudioStream {
    /** Hears how a stream was lined up, once, on the sound thread. */
    @FunctionalInterface
    public interface Listener {
        /**
         * {@code firstSampleTick} is the battle tick the stream's first sample now belongs to, and
         * {@code alignedAtNanos} the {@link System#nanoTime()} at which the battle tick was read for it.
         */
        void aligned(double firstSampleTick, long alignedAtNanos);
    }

    private final AudioStream delegate;
    private final double requestedTick;
    private final float pitch;
    private final DoubleSupplier battleTick;
    private final Listener listener;
    private final int frameSize;
    private boolean aligned;
    private long silenceBytes;
    private ByteBuffer pending;

    /**
     * {@code requestedTick} is the battle tick the stream was opened at and {@code pitch} the rate its source plays at.
     * {@code battleTick} gives the battle tick the first sample should belong to if the stream starts now - the clock,
     * plus however long a stream takes to reach the speakers - or NaN when there is no clock to follow, in which case
     * the stream plays from where it was opened.
     */
    public AlignedStartAudioStream(AudioStream delegate, double requestedTick, float pitch, DoubleSupplier battleTick,
                                   Listener listener) {
        this.delegate = delegate;
        this.requestedTick = requestedTick;
        this.pitch = pitch;
        this.battleTick = battleTick;
        this.listener = listener;
        this.frameSize = Math.max(1, delegate.getFormat().getFrameSize());
    }

    @Override
    public AudioFormat getFormat() {
        return delegate.getFormat();
    }

    @Override
    public ByteBuffer read(int size) throws IOException {
        if (!aligned) {
            aligned = true;
            align();
        }
        if (silenceBytes == 0L && (pending == null || !pending.hasRemaining())) {
            return delegate.read(size);
        }
        int alignedSize = Math.max(frameSize, size - Math.floorMod(size, frameSize));
        // Direct buffers start zeroed, which is silence in signed PCM.
        ByteBuffer output = ByteBuffer.allocateDirect(alignedSize).order(ByteOrder.nativeOrder());
        int silence = (int) Math.min(silenceBytes, alignedSize);
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

    private void align() throws IOException {
        // The instant goes with the tick it was read for: the time from here to the source starting is what the owner
        // measures, and the drop below is part of that time.
        double tick = battleTick.getAsDouble();
        long alignedAt = System.nanoTime();
        MusicStartAlignment.Plan plan = MusicStartAlignment.plan(requestedTick, tick, pitch, delegate.getFormat().getFrameRate());
        long skipBytes = plan.skipFrames() * frameSize;
        while (skipBytes > 0L) {
            ByteBuffer decoded = delegate.read((int) Math.min(skipBytes, 65_536L));
            if (decoded == null || !decoded.hasRemaining()) {
                break;
            }
            int skip = (int) Math.min(skipBytes, decoded.remaining());
            decoded.position(decoded.position() + skip);
            skipBytes -= skip;
            if (decoded.hasRemaining()) {
                pending = decoded;
            }
        }
        silenceBytes = plan.silenceFrames() * frameSize;
        listener.aligned(plan.startTick(), alignedAt);
    }

    @Override
    public void close() throws IOException {
        pending = null;
        delegate.close();
    }
}

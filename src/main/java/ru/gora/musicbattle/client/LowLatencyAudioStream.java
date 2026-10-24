package ru.gora.musicbattle.client;

import java.io.IOException;
import java.nio.ByteBuffer;
import javax.sound.sampled.AudioFormat;
import net.minecraft.client.sounds.AudioStream;

/**
 * Marker/delegating stream used by Blocky Music's live DJ audio path.
 *
 * <p>Minecraft normally keeps four one-second streaming buffers queued. That is useful for
 * ordinary sounds, but it also means EQ and DSP changes cannot be heard until several seconds of
 * already processed PCM have played. {@code ChannelMixin} recognizes this wrapper and keeps a
 * much shorter safety queue for streams whose processing is deliberately editable while playing.</p>
 */
public final class LowLatencyAudioStream implements AudioStream {
    private final AudioStream delegate;

    public LowLatencyAudioStream(AudioStream delegate) {
        this.delegate = delegate;
    }

    @Override
    public AudioFormat getFormat() {
        return delegate.getFormat();
    }

    @Override
    public ByteBuffer read(int size) throws IOException {
        return delegate.read(size);
    }

    @Override
    public void close() throws IOException {
        delegate.close();
    }
}

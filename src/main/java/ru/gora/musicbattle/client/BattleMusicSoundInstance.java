package ru.gora.musicbattle.client;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.DoubleSupplier;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;

/**
 * The song a battle participant hears: an {@link ExternalOggSoundInstance} whose stream starts in step with the
 * battle clock however long it took to open ({@link AlignedStartAudioStream}), and which remembers the battle tick its
 * first sample belongs to, so the once-a-second drift check compares the clock with where the song really is, and when
 * the stream was lined up, so the controller can learn how long a stream takes from there to the speakers.
 */
final class BattleMusicSoundInstance extends ExternalOggSoundInstance {
    private final int requestedTick;
    private final DoubleSupplier battleTick;
    /** The battle tick of the stream's first sample, once the stream has decided it; NaN until then. */
    private volatile double startTick = Double.NaN;
    /** When the stream was lined up, on {@link System#nanoTime()}; zero until then. */
    private volatile long alignedAtNanos;

    BattleMusicSoundInstance(String trackId, String fileName, Path file, int requestedTick, float pitch, float volume,
                             DoubleSupplier battleTick, Consumer<String> errorCallback) {
        super(trackId, fileName, file, Math.round(Math.max(0, requestedTick) * Math.max(0.1F, pitch)), true,
                0.0D, 0.0D, 0.0D, SoundInstance.Attenuation.NONE, volume, pitch, errorCallback);
        this.requestedTick = Math.max(0, requestedTick);
        this.battleTick = battleTick;
    }

    @Override
    public CompletableFuture<AudioStream> getStream(SoundBufferLibrary soundBuffers, Sound sound, boolean looping) {
        return super.getStream(soundBuffers, sound, looping).thenApply(stream ->
                new AlignedStartAudioStream(stream, requestedTick, getPitch(), battleTick, (tick, alignedAt) -> {
                    // The tick first: whoever sees the instant set may read the tick straight after it.
                    startTick = tick;
                    alignedAtNanos = alignedAt;
                }));
    }

    /** The battle tick the stream's first sample belongs to, or NaN while the stream has not been read yet. */
    double startTick() {
        return startTick;
    }

    /** When the stream was lined up with the battle clock, on {@link System#nanoTime()}, or zero before that. */
    long alignedAtNanos() {
        return alignedAtNanos;
    }
}

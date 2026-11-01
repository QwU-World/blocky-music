package ru.gora.musicbattle.client;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractSoundInstance;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.resources.sounds.TickableSoundInstance;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.FloatSampleSource;
import net.minecraft.client.sounds.JOrbisAudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.valueproviders.SampledFloat;
import ru.gora.musicbattle.MusicBattleMod;
import ru.gora.musicbattle.data.CalibrationTiming;

public class ExternalOggSoundInstance extends AbstractSoundInstance implements TickableSoundInstance {
    private static final SampledFloat ONE = random -> 1.0F;

    /** Opens the Ogg Vorbis bytes to decode; called on the sound IO pool each time the stream starts. */
    @FunctionalInterface
    public interface AudioSource {
        InputStream open() throws IOException;
    }

    private final AudioSource audio;
    private final String description;
    private final int skipTicks;
    private final int audioOffsetMs;
    /** Seconds of the decoded song skipped before the first sample is heard; only interface previews set it. */
    private final double startSeconds;
    private final Consumer<String> errorCallback;
    private final ThreeBandEqualizer equalizer = new ThreeBandEqualizer();
    private final LiveDjAudioEffects djEffects = new LiveDjAudioEffects();
    private volatile boolean equalizerEnabled;
    private volatile boolean djEffectsAttached;
    private volatile boolean stopRequested;

    public ExternalOggSoundInstance(String trackId, String fileName, Path file, int skipTicks) {
        this(trackId, fileName, file, skipTicks, true, 0.0D, 0.0D, 0.0D, SoundInstance.Attenuation.NONE, 1.0F);
    }

    public ExternalOggSoundInstance(String trackId, String fileName, Path file, int skipTicks, boolean relative, double x, double y, double z, SoundInstance.Attenuation attenuation, float volume) {
        this(trackId, fileName, file, skipTicks, relative, x, y, z, attenuation, volume, null);
    }

    public ExternalOggSoundInstance(String trackId, String fileName, Path file, int skipTicks, boolean relative, double x, double y, double z, SoundInstance.Attenuation attenuation, float volume, Consumer<String> errorCallback) {
        this(trackId, fileName, file, skipTicks, relative, x, y, z, attenuation, volume, 1.0F, errorCallback);
    }

    public ExternalOggSoundInstance(String trackId, String fileName, Path file, int skipTicks, boolean relative, double x, double y, double z, SoundInstance.Attenuation attenuation, float volume, float pitch, Consumer<String> errorCallback) {
        super(externalId(trackId, fileName), SoundSource.PLAYERS, SoundInstance.createUnseededRandom());
        this.audio = () -> Files.newInputStream(file);
        this.description = String.valueOf(file);
        this.skipTicks = Math.max(0, skipTicks);
        this.audioOffsetMs = ClientBattleSettings.audioOffsetMs();
        this.startSeconds = 0.0D;
        this.errorCallback = errorCallback;
        this.volume = volume;
        this.pitch = Math.max(0.1F, pitch);
        this.looping = false;
        this.delay = 0;
        this.attenuation = attenuation;
        this.relative = relative;
        this.x = x;
        this.y = y;
        this.z = z;
        this.sound = new Sound(this.location, ONE, ONE, 1, Sound.Type.FILE, true, false, 16);
    }

    /**
     * A non-positional stream for interface sounds (Workshop previews): any sound source, no battle
     * calibration offset and no start skip, so wall-clock progress matches what is heard.
     */
    protected ExternalOggSoundInstance(ResourceLocation id, SoundSource source, AudioSource audio, String description,
                                       float volume, Consumer<String> errorCallback) {
        this(id, source, audio, description, volume, 0L, errorCallback);
    }

    /**
     * The same interface stream, heard from {@code startMs} into the song: the decoded frames before it are skipped
     * (frame-exact, as a battle's start skip is), so the first sample played is the one at {@code startMs}.
     */
    protected ExternalOggSoundInstance(ResourceLocation id, SoundSource source, AudioSource audio, String description,
                                       float volume, long startMs, Consumer<String> errorCallback) {
        super(id, source, SoundInstance.createUnseededRandom());
        this.audio = audio;
        this.description = description;
        this.skipTicks = 0;
        this.audioOffsetMs = 0;
        this.startSeconds = Math.max(0L, startMs) / 1000.0D;
        this.errorCallback = errorCallback;
        this.volume = volume;
        this.pitch = 1.0F;
        this.looping = false;
        this.delay = 0;
        this.attenuation = SoundInstance.Attenuation.NONE;
        this.relative = true;
        this.x = 0.0D;
        this.y = 0.0D;
        this.z = 0.0D;
        this.sound = new Sound(this.location, ONE, ONE, 1, Sound.Type.FILE, true, false, 16);
    }

    public void setVolume(float volume) {
        this.volume = volume;
    }

    @Override
    public float getVolume() {
        return isCalibrationMuted() ? 0.0F : super.getVolume();
    }

    @Override
    public boolean canStartSilent() {
        // Transport may restart while an offset is edited. Keep its clock alive
        // at zero local gain so leaving calibration restores the current track.
        return isCalibrationMuted() || super.canStartSilent();
    }

    protected static boolean isCalibrationMuted() {
        return Minecraft.getInstance().screen instanceof MusicBattleCalibrationScreen;
    }

    /**
     * Prevents an asynchronously decoded stream from starting after its sound
     * instance has already been removed from the controller.
     */
    public void requestStop() {
        stopRequested = true;
    }

    public void setEqualizer(float lowGainDb, float midGainDb, float highGainDb) {
        equalizer.setGains(lowGainDb, midGainDb, highGainDb);
        equalizerEnabled = true;
    }

    /** Atomically changes panel-level streaming effects without reopening the OGG stream. */
    public void setDjEffects(boolean masterEnabled,
                             boolean reverbEnabled, float reverbWet, float reverbRoom, float reverbDamping,
                             boolean echoEnabled, float echoWet, float echoTimeMs, float echoFeedback,
                             boolean lowPassEnabled, float lowPassCutoffHz) {
        djEffects.setSettings(masterEnabled,
                reverbEnabled, reverbWet, reverbRoom, reverbDamping,
                echoEnabled, echoWet, echoTimeMs, echoFeedback,
                lowPassEnabled, lowPassCutoffHz);
        // Publish the initialized settings before the async stream builder is
        // allowed to attach the wrapper. Later calls only replace the wrapper's
        // own volatile settings snapshot and never rebuild the stream.
        djEffectsAttached = true;
    }

    @Override
    public boolean isStopped() {
        return stopRequested;
    }

    @Override
    public void tick() {
        // Keeping the instance tickable lets Minecraft apply live volume changes to its channel.
    }

    @Override
    public WeighedSoundEvents resolve(SoundManager manager) {
        return new WeighedSoundEvents(this.location, null);
    }

    @Override
    public CompletableFuture<AudioStream> getStream(SoundBufferLibrary soundBuffers, Sound sound, boolean looping) {
        return CompletableFuture.supplyAsync(() -> {
            InputStream input = null;
            AudioStream stream = null;
            try {
                throwIfStopRequested();
                input = this.audio.open();
                throwIfStopRequested();
                JOrbisAudioStream decoded = new JOrbisAudioStream(input);
                input = null;
                stream = decoded;
                throwIfStopRequested();
                // Keep the deepest currently-owned stream reachable by the
                // catch block. A malformed AudioFormat can make a wrapper
                // constructor fail; the decoder/input must still be closed.
                FloatSampleSource processed = equalizerEnabled
                        ? equalizer.wrap(decoded)
                        : decoded;
                stream = processed;
                // Speaker EQ is deliberately first. The following effect chain
                // belongs to the DJ panel and is therefore shared by every
                // linked speaker without replacing its individual EQ settings.
                if (djEffectsAttached) {
                    stream = djEffects.wrap(processed);
                }
                throwIfStopRequested();
                stream = new CalibratedAudioStream(stream,
                        CalibrationTiming.sourceAudioSeconds(skipTicks, pitch, audioOffsetMs) + startSeconds, () -> stopRequested);
                if (startSeconds > 0.0D) {
                    MusicBattleMod.LOGGER.debug("Blocky Music preview '{}' starts at {} ms: {} decoded frames skipped at {} Hz.",
                            this.description, Math.round(startSeconds * 1000.0D),
                            Math.round(startSeconds * stream.getFormat().getFrameRate()), stream.getFormat().getFrameRate());
                }
                throwIfStopRequested();
                AudioStream ready = this instanceof SpectatorOggSoundInstance
                        ? new LowLatencyAudioStream(stream)
                        : stream;
                stream = null;
                return ready;
            } catch (CancellationException exception) {
                if (stream != null) {
                    try {
                        stream.close();
                    } catch (IOException ignored) {
                    }
                }
                if (input != null) {
                    try {
                        input.close();
                    } catch (IOException ignored) {
                    }
                }
                throw exception;
            } catch (Exception exception) {
                if (stream != null) {
                    try {
                        stream.close();
                    } catch (IOException closeException) {
                        exception.addSuppressed(closeException);
                    }
                }
                if (input != null) {
                    try {
                        input.close();
                    } catch (IOException ignored) {
                    }
                }
                MusicBattleMod.LOGGER.warn("Unable to open Blocky Music audio '{}'.", this.description, exception);
                if (this.errorCallback != null) {
                    try {
                        this.errorCallback.accept(exception.getClass().getSimpleName() + ": " + exception.getMessage());
                    } catch (RuntimeException ignored) {
                    }
                }
                throw new CompletionException(exception);
            }
        }, Util.nonCriticalIoPool());
    }

    private void throwIfStopRequested() {
        if (stopRequested) {
            throw new CancellationException("External OGG playback was stopped");
        }
    }

    private static ResourceLocation externalId(String trackId, String fileName) {
        String safe = Integer.toHexString((trackId + ":" + fileName).toLowerCase().hashCode());
        return MusicBattleMod.id("external/" + safe);
    }
}

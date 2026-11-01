package ru.gora.musicbattle.client.ui;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundSource;
import net.neoforged.neoforge.client.event.sound.PlayStreamingSourceEvent;
import ru.gora.musicbattle.MusicBattleMod;
import ru.gora.musicbattle.client.ExternalOggSoundInstance;

/**
 * One Workshop audio preview at a time: a non-positional Ogg Vorbis stream through the
 * mod's existing {@link ExternalOggSoundInstance} path, from a file or from bytes held in memory.
 *
 * <ul>
 *   <li>Progress for the ring is wall clock since the channel actually started ({@code PlayStreamingSourceEvent}),
 *       because a channel exposes no playback position.</li>
 *   <li>Stopping calls {@code SoundManager.stop} directly: a tickable sound's {@code isStopped()} is not polled
 *       while a pause screen pauses singleplayer.</li>
 *   <li>Plays in {@link SoundSource#MASTER}, so players with Music at 0 still hear previews; the caller passes the
 *       Workshop preview volume. Vanilla background music stops while a preview starts.</li>
 * </ul>
 * Callers stop the preview when the Workshop closes, on tab or search changes and when the set page closes.
 * Render thread only, except the streaming event.
 */
public final class PreviewPlayer {
    public static final float DEFAULT_VOLUME = 0.6F;
    public static final long DEFAULT_DURATION_MS = 30_000L;
    /** Give up if the decoder has not started the channel by then (unreadable file, audio device gone). */
    static final long START_TIMEOUT_NANOS = 10_000_000_000L;
    /** Previews are short clips; anything bigger is refused before it reaches the decoder. */
    public static final long MAX_PREVIEW_BYTES = 8L * 1024 * 1024;

    private static volatile PreviewSound current;
    /** The key of the last preview that ended because it could not be played; cleared when that key starts again. */
    private static volatile String failedKey;

    private PreviewPlayer() {
    }

    /** Plays an Ogg Vorbis file. Replaces any preview that is playing. */
    public static void play(String key, Path file, long durationMs, float volume) {
        play(key, file, durationMs, volume, 0L);
    }

    /**
     * Plays an Ogg Vorbis file from {@code startMs} into it, as the site's upload page plays the chosen 30-second
     * window: the frames before it are decoded and skipped, so {@link #progress} runs over the window, not the song.
     */
    public static void play(String key, Path file, long durationMs, float volume, long startMs) {
        start(new PreviewSound(key, () -> Files.newInputStream(file), String.valueOf(file), durationMs, volume, startMs));
    }

    /** Plays Ogg Vorbis bytes from memory. Replaces any preview that is playing. */
    public static void play(String key, byte[] ogg, long durationMs, float volume) {
        if (ogg == null || ogg.length == 0 || ogg.length > MAX_PREVIEW_BYTES) {
            return;
        }
        start(new PreviewSound(key, () -> new ByteArrayInputStream(ogg), "preview bytes " + key, durationMs, volume, 0L));
    }

    private static void start(PreviewSound sound) {
        stop();
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.getMusicManager().stopPlaying();
        if (sound.key.equals(failedKey)) {
            failedKey = null;
        }
        current = sound;
        sound.requestedNanos = Util.getNanos();
        minecraft.getSoundManager().play(sound);
    }

    /** Stops whatever is playing. */
    public static void stop() {
        PreviewSound sound = current;
        current = null;
        if (sound != null) {
            sound.requestStop();
            Minecraft.getInstance().getSoundManager().stop(sound);
        }
    }

    /** Stops the preview only if it is the one for {@code key}. */
    public static void stop(String key) {
        if (isCurrent(key)) {
            stop();
        }
    }

    public static boolean isCurrent(String key) {
        PreviewSound sound = current;
        return sound != null && sound.key.equals(key);
    }

    /** True while a preview for {@code key} is loading or playing. */
    public static boolean isPlaying(String key) {
        return isCurrent(key);
    }

    /** True while the preview for {@code key} is still decoding its first buffers. */
    public static boolean isLoading(String key) {
        PreviewSound sound = current;
        return sound != null && sound.key.equals(key) && sound.startedNanos == 0L;
    }

    public static void setVolume(float volume) {
        PreviewSound sound = current;
        if (sound != null) {
            sound.setVolume(Math.max(0.0F, Math.min(1.0F, volume)));
        }
    }

    /** 0..1 of the clip heard so far for {@code key}; 0 when another or no preview plays, or while loading. */
    public static float progress(String key) {
        PreviewSound sound = current;
        if (sound == null || !sound.key.equals(key)) {
            return 0.0F;
        }
        return progress(sound.startedNanos, Util.getNanos(), sound.durationMs);
    }

    /** Pure progress maths: elapsed wall clock over duration, clamped; 0 before the channel starts. */
    public static float progress(long startedNanos, long nowNanos, long durationMs) {
        if (startedNanos == 0L || durationMs <= 0L) {
            return 0.0F;
        }
        double elapsedMs = (nowNanos - startedNanos) / 1_000_000.0;
        return (float) Math.max(0.0, Math.min(1.0, elapsedMs / durationMs));
    }

    /** Once per frame ({@link UiClientEvents}): ends finished, failed or stuck previews. */
    public static void update() {
        PreviewSound sound = current;
        if (sound == null) {
            return;
        }
        long now = Util.getNanos();
        boolean finished = sound.startedNanos != 0L && progress(sound.startedNanos, now, sound.durationMs) >= 1.0F;
        boolean stuck = sound.startedNanos == 0L && now - sound.requestedNanos > START_TIMEOUT_NANOS;
        if (finished || stuck || sound.failed.get()) {
            if (stuck || sound.failed.get()) {
                failedKey = sound.key;
            }
            stop();
        }
    }

    /**
     * Whether the preview for {@code key} ended because it could not be played (the decoder refused it, or its channel
     * never started), once: the answer is consumed, so a screen that says so says it once.
     */
    public static boolean takeFailure(String key) {
        if (key != null && key.equals(failedKey)) {
            failedKey = null;
            return true;
        }
        return false;
    }

    /** Game bus, sound thread: the channel for a preview has started playing. */
    public static void onStreamingStarted(PlayStreamingSourceEvent event) {
        if (event.getSound() instanceof PreviewSound sound) {
            if (sound != current) {
                event.getChannel().stop();
            } else {
                sound.startedNanos = Util.getNanos();
            }
        }
    }

    static final class PreviewSound extends ExternalOggSoundInstance {
        final String key;
        final long durationMs;
        final AtomicBoolean failed;
        volatile long startedNanos;
        volatile long requestedNanos;

        PreviewSound(String key, AudioSource audio, String description, long durationMs, float volume, long startMs) {
            this(key, audio, description, durationMs, volume, startMs, new AtomicBoolean());
        }

        private PreviewSound(String key, AudioSource audio, String description, long durationMs, float volume, long startMs,
                             AtomicBoolean failed) {
            super(MusicBattleMod.id("workshop_preview/" + Integer.toHexString(key.toLowerCase(Locale.ROOT).hashCode())),
                    SoundSource.MASTER, audio, description, Math.max(0.0F, Math.min(1.0F, volume)), Math.max(0L, startMs),
                    message -> failed.set(true));
            this.key = key;
            this.durationMs = durationMs > 0 ? durationMs : DEFAULT_DURATION_MS;
            this.failed = failed;
        }
    }
}

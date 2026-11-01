package ru.gora.musicbattle.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.ChannelAccess;
import ru.gora.musicbattle.mixin.client.SoundEngineAccessor;
import ru.gora.musicbattle.mixin.client.SoundManagerAccessor;

/**
 * Where a battle song's source really is: read on the sound thread from OpenAL - the bytes of the buffers it has
 * given back (mixin/client/ChannelMixin) and its offset into the ones still queued - and handed back with the instant it
 * was read. ClientBattleController asks once a client tick, for the song of the battle it plays or of the one it
 * watches (a battle or a DJ booth), and gives the readings to that song's SongDriftAnchor.
 */
public final class BattleSongPosition {
    /** A channel that can tell how much of its stream it has played. */
    public interface Source {
        /** Seconds of its stream this source has played, NaN when it has none or is not playing. Sound thread only. */
        double musicbattle$playedSeconds();
    }

    /** One reading: {@code playedSeconds} of {@code sound}'s stream had been played at {@code atNanos}. */
    public record Reading(SoundInstance sound, double playedSeconds, long atNanos) {
    }

    private static volatile Reading latest;

    private BattleSongPosition() {
    }

    /** Asks the sound thread where {@code sound} is; the answer comes as {@link #take} some time later. */
    static void request(Minecraft minecraft, SoundInstance sound) {
        if (minecraft == null || sound == null
                || !(minecraft.getSoundManager() instanceof SoundManagerAccessor manager)
                || !(manager.musicbattle$soundEngine() instanceof SoundEngineAccessor engine)) {
            return;
        }
        ChannelAccess.ChannelHandle handle = engine.musicbattle$channels().get(sound);
        if (handle == null || handle.isStopped()) {
            return;
        }
        handle.execute(channel -> {
            if (channel instanceof Source source) {
                double played = source.musicbattle$playedSeconds();
                long at = System.nanoTime();
                if (Double.isFinite(played)) {
                    latest = new Reading(sound, played, at);
                }
            }
        });
    }

    /** The newest reading of {@code sound} since the last call, or null; a reading of another sound is dropped. */
    static Reading take(SoundInstance sound) {
        Reading reading = latest;
        latest = null;
        return reading != null && reading.sound() == sound ? reading : null;
    }

    /** Drops a reading still on its way, for a song that has just been replaced or paused. */
    static void clear() {
        latest = null;
    }
}

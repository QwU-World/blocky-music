package ru.gora.musicbattle.data;

/**
 * When a battle's own song, refused by the sound engine, is asked for again.
 *
 * <p>Minecraft's SoundEngine takes a sound or skips it the moment it is played: with the master volume at 0 it skips
 * every sound, and with the sound's own volume at 0 (its category, the Players slider, or Blocky Music's music slider,
 * which sets the instance's volume) it skips one that cannot start silent. A skipped sound gets no channel and never
 * starts. A battle used to play its song once, so a battle that began while any of those was at 0 stayed silent to the
 * end, however soon the volume was turned up: the song had no stream to follow, so the once-a-second re-seek never
 * fired either. A DJ booth's listener already came back; the battle now does the same.
 *
 * <p>The song is asked for again once, on the first tick nothing silences it any more, at the place the battle's clock
 * has reached. Never while it is still silenced (each retry would be skipped in turn), and never for a refusal that had
 * another cause, which a later seek or song asks again for anyway.
 *
 * <p>Pure, so tools/SongDriftChecks.java checks the rule without a game.
 */
public final class MusicStreamRetry {
    private MusicStreamRetry() {
    }

    /**
     * Whether the engine skips the song at these volumes: the master volume, the volume of the song's category, and the
     * instance's own volume (the song's level times Blocky Music's music slider).
     */
    public static boolean silenced(float master, float category, float instance) {
        return !(master > 0.0F) || !(category > 0.0F) || !(instance > 0.0F);
    }

    /**
     * Whether to play the song again now.
     *
     * @param refused           the last play of the song got no channel
     * @param refusedSilenced   and it was silenced when it was played, which is why
     * @param silencedNow       it would still be silenced now
     * @param playingNow        the engine holds a channel for the song now
     */
    public static boolean retryNow(boolean refused, boolean refusedSilenced, boolean silencedNow, boolean playingNow) {
        return refused && refusedSilenced && !silencedNow && !playingNow;
    }
}

package ru.gora.musicbattle.data;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.function.Predicate;

/**
 * The decisions a DJ booth makes on its own, with nobody pressing anything: whether the server still looks after it,
 * what follows a finished song, and what becomes of a start whose audio was prepared in the background.
 * {@code DjPlaybackManager} reads the facts from the world and asks here; the rules are kept free of Minecraft so
 * {@code python3 tools/run-check.py dj-continuity} can replay the evening that went quiet.
 *
 * <p>That evening: a booth plays, its DJ flies off to other chunks, comes back later, and the booth is silent. It
 * happened several times and never on purpose. The server keeps a list of loaded booths and visits it every five
 * ticks; a booth joins the list when its block entity loads and leaves it when the block entity is removed. The visit
 * also struck off every booth whose chunk was not fully loaded at that moment. While a booth played, its chunk stayed
 * full: the passes over live sessions read its block entity every tick, and each read renewed a short ticket. When the
 * booth stopped by itself - its queue over, with nobody there - the chunk the player left behind was demoted below full
 * status, and the next visit struck the booth off. Its block entity is removed only if the player goes on far enough
 * for the chunk to unload; a player who comes back before that promotes the same chunk again, with the same block
 * entity and no second {@code onLoad}, so the booth was never visited again. Play on its panel then changed the panel
 * and nothing else - no audience, no next song - until the chunk happened to unload completely or the server
 * restarted. How far the player had flown decided it, and only a booth that had stopped while they were away could
 * do it, which is why it could not be repeated on purpose. A booth now stays on the list while its chunk is demoted,
 * is skipped while it is, and is visited again as soon as the chunk is full ({@link #boothsToVisit}). A scratch
 * dedicated-server probe replayed it: under the old rule the booth came back unregistered and Play reached no
 * audience; under this one it stayed registered and Play did. The read that kept a playing booth's chunk full was the
 * live camera pass, and that pass now asks whether the chunk is loaded first, like the others, so it no longer keeps
 * the chunk full: a playing booth's chunk may now be demoted mid-song as soon as its DJ is out of range. The booth
 * then waits in place, still registered, and once its chunk is full again it plays on from its clock or moves on to
 * the next row.
 *
 * <p>Two smaller ways a booth went quiet, both after a chunk really was loaded afresh, as after a restart. The booth's
 * chunk is often full a moment before the chunk its speakers stand in, because chunks load nearest the player first.
 * A song that was over by then, or whose audio had to be prepared again, went on to the next one only when a speaker
 * was loaded, so the booth paused instead - and a paused booth never starts again by itself. Speakers decide who
 * hears a booth, not whether it plays, so the booth's own starts now ask only whether any are wired to it
 * ({@link #afterSong}, {@link #startHasSpeakers}). And when the next song's audio was not ready, the booth paused at
 * the end of the old one and waited for the preparation; if its chunk went away in that moment, the waiting start was
 * thrown away with it and the booth stayed paused for good. Such a start is now kept until the booth is loaded again
 * ({@link #preparedStart}).
 */
public final class DjQueueContinuity {
    private DjQueueContinuity() {
    }

    /**
     * The registered booths to visit on this pass, in the order they were given. A booth whose chunk is not full stays
     * registered and is not visited: its block entity is still the one a promoted chunk will hand back, and nothing
     * would register it again. {@code boothThere} is asked only about full chunks, because asking a demoted chunk for
     * its block entity loads it; a full chunk with no booth left in it is the one registration that is dropped.
     *
     * @param registered  booths whose block entities have loaded and not been removed; dropped ones are removed from it
     * @param chunkFull   the booth's chunk is loaded at full status right now
     * @param boothThere  the block entity at that position is still a DJ booth
     */
    public static <P> List<P> boothsToVisit(Collection<P> registered, Predicate<P> chunkFull, Predicate<P> boothThere) {
        List<P> visit = new ArrayList<>();
        Iterator<P> iterator = registered.iterator();
        while (iterator.hasNext()) {
            P booth = iterator.next();
            if (!chunkFull.test(booth)) {
                continue;
            }
            if (boothThere.test(booth)) {
                visit.add(booth);
            } else {
                iterator.remove();
            }
        }
        return visit;
    }

    /** What a booth does when its song is over, or when the song it was playing is gone from the server. */
    public enum AfterSong {
        /** Start what the queue names next: the next row, the first row under repeat-all, the same song under repeat-one. */
        PLAY_NEXT,
        /** Stay paused at the end of the finished song until a DJ presses something. */
        REST
    }

    /**
     * @param hasNext       the queue or the repeat mode names a song to play next
     * @param wiredSpeakers speakers wired to the booth, whether their chunks are loaded or not. Not the loaded ones:
     *                      a booth whose speakers have simply not loaded yet still has a show to keep going, while a
     *                      booth with no speakers at all has nobody who could ever hear the next song.
     */
    public static AfterSong afterSong(boolean hasNext, int wiredSpeakers) {
        return hasNext && wiredSpeakers > 0 ? AfterSong.PLAY_NEXT : AfterSong.REST;
    }

    /** What becomes of a start whose audio was being prepared, now that the preparation is over. */
    public enum PreparedStart {
        /** Start the song now. */
        APPLY,
        /** Keep the start until the booth's chunk is loaded again, then decide afresh. */
        PARK,
        /** Forget it without a word: the booth moved on, or the song changed, while the audio was being prepared. */
        DROP,
        /** Forget it and tell whoever asked that the audio could not be prepared. */
        FAIL
    }

    /**
     * @param boothLoaded the booth's chunk is loaded right now
     * @param stillWanted the booth is still in the state the start was queued for, and the song is unchanged
     * @param audioReady  the preparation finished and its audio is there
     * @param speakers    the start has the speakers it needs ({@link #startHasSpeakers})
     */
    public static PreparedStart preparedStart(boolean boothLoaded, boolean stillWanted, boolean audioReady,
                                              boolean speakers) {
        if (!boothLoaded) {
            // Nothing about the booth can be read while its chunk is away, so nothing about the start is settled:
            // dropping it here is what left a booth paused for good at the end of the song before.
            return PreparedStart.PARK;
        }
        if (!stillWanted) {
            return PreparedStart.DROP;
        }
        return audioReady && speakers ? PreparedStart.APPLY : PreparedStart.FAIL;
    }

    /**
     * Whether a start has the speakers it needs. A DJ who pressed Play is standing at the panel, so a loaded speaker
     * is asked of them, as it always was, and its absence is reported to them. The booth's own starts follow
     * {@link #afterSong}: a wired speaker is enough.
     *
     * @param automatic      the booth started it itself - the next song, or a song resumed after a restart - rather
     *                       than a DJ at the panel
     * @param wiredSpeakers  speakers wired to the booth
     * @param loadedSpeakers wired speakers whose chunks are loaded
     */
    public static boolean startHasSpeakers(boolean automatic, int wiredSpeakers, int loadedSpeakers) {
        return automatic ? wiredSpeakers > 0 : loadedSpeakers > 0;
    }

    /**
     * Where Play / resume picks up the song already on the deck: where it stands, unless it has played to its end.
     * A booth whose song ended - by itself, or while everyone was away, or before a restart - keeps the deck at the
     * end and paused. Play then resumed at the very end, the song was over again in the same tick and nothing was
     * heard, so the booth looked dead until the DJ found Restart. A finished song now starts again from the top.
     *
     * @param positionTicks where the deck stands, in runtime ticks at the new speed
     * @param endTicks      the song's length in runtime ticks at the new speed
     */
    public static int resumeTicks(int positionTicks, int endTicks) {
        int position = Math.max(0, positionTicks);
        return endTicks > 0 && position >= endTicks ? 0 : position;
    }
}

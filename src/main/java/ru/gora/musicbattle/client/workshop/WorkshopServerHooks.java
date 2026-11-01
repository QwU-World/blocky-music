package ru.gora.musicbattle.client.workshop;

import java.util.List;
import java.util.function.Consumer;

/**
 * Where the Minecraft server's Workshop features plug into the Workshop screens. While nothing is registered, the
 * "Server beatmaps" tab stays hidden and the "Server comments" button is drawn disabled, so the screens need no
 * separate code for a server without these features.
 *
 * <p>Registration happens on the render thread when the client joins a server that reports the feature, and
 * {@link #clear()} runs on disconnect.
 */
public final class WorkshopServerHooks {
    /** Paged server beatmaps (payload {@code ServerBeatmapsRequest} → {@code ServerBeatmapsPage}). */
    public interface ServerBeatmaps {
        boolean available();

        /** Asks the server for one page; the callbacks run on the render thread. */
        void request(String cursor, String query, Consumer<List<CardModel>> onPage, Consumer<String> onError);

        /**
         * Asks the server for every track it offers - its Workshop sets' charts, its operator's tracks and the tracks
         * its players published with the editor - as a battle's vote would list them; the callback runs on the render
         * thread.
         */
        void requestTracks(Consumer<ru.gora.musicbattle.client.workshop.layout.BattleTracks> onTracks);

        /** Whether this player may remove server beatmaps (permission {@code music_battle.server_beatmaps.manage}). */
        boolean canManage();

        void remove(long setId, Consumer<String> onResult);
    }

    /** Comments stored on the Minecraft server ({@code ws_<setId>} or {@code track_<trackId>} keys). */
    public interface ServerComments {
        boolean available();

        void request(String key, Long beforeId, Consumer<Object> onPage, Consumer<String> onError);

        void post(String key, String text, Consumer<String> onResult);

        void delete(String key, long commentId, Consumer<String> onResult);

        void report(String key, long commentId, String reason, Consumer<String> onResult);
    }

    private static volatile ServerBeatmaps beatmaps;
    private static volatile ServerComments comments;

    private WorkshopServerHooks() {
    }

    public static void setServerBeatmaps(ServerBeatmaps value) {
        beatmaps = value;
    }

    public static void setServerComments(ServerComments value) {
        comments = value;
    }

    /** Forgets both hooks (leaving a server). */
    public static void clear() {
        beatmaps = null;
        comments = null;
    }

    public static ServerBeatmaps serverBeatmaps() {
        return beatmaps;
    }

    public static ServerComments serverComments() {
        return comments;
    }

    public static boolean serverBeatmapsAvailable() {
        ServerBeatmaps hook = beatmaps;
        return hook != null && hook.available();
    }

    public static boolean serverCommentsAvailable() {
        ServerComments hook = comments;
        return hook != null && hook.available();
    }
}

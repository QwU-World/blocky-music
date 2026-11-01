package ru.gora.musicbattle.client.workshop;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import ru.gora.musicbattle.network.ServerCommentsPagePayload.Row;

/**
 * How the comments section folds one page of server comments into the ones it already shows.
 *
 * <p>A page answer does not say which request it answers. A Workshop set's page asks for the first page twice when it
 * is reopened (once when the section opens, once more when the set's details arrive and it enters server mode again),
 * and both answers used to be appended, so every comment was listed twice. A row is now kept once by its id, the
 * newer copy wins (a delete arrives as the same id with {@code deleted} set), and the list stays newest first, the
 * order the server pages in.
 */
public final class ServerCommentRows {
    private ServerCommentRows() {
    }

    public static List<Row> merge(List<Row> shown, List<Row> page) {
        Map<Long, Row> byId = new LinkedHashMap<>();
        for (Row row : shown) {
            byId.put(row.id(), row);
        }
        for (Row row : page) {
            byId.put(row.id(), row);
        }
        List<Row> merged = new ArrayList<>(byId.values());
        merged.sort(Comparator.comparingLong(Row::id).reversed());
        return List.copyOf(merged);
    }

    /**
     * The cursor for "more comments" after a page arrives. It belongs to the page that reached furthest back: a second
     * answer to the first page must not move it forward again over a page that is already shown. An empty page ends the
     * list, so its cursor (0, nothing older) is taken as it is.
     */
    public static long nextBeforeId(List<Row> shown, List<Row> page, long pageNextBeforeId, long currentNextBeforeId) {
        if (page.isEmpty() || shown.isEmpty()) {
            return pageNextBeforeId;
        }
        return oldest(page) <= oldest(shown) ? pageNextBeforeId : currentNextBeforeId;
    }

    private static long oldest(List<Row> rows) {
        long min = Long.MAX_VALUE;
        for (Row row : rows) {
            min = Math.min(min, row.id());
        }
        return min;
    }
}

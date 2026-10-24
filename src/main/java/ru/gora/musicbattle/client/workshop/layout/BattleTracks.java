package ru.gora.musicbattle.client.workshop.layout;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import ru.gora.musicbattle.client.ui.TextStyle;
import ru.gora.musicbattle.workshop.WorkshopLayout;

/**
 * Everything one battle can vote for, in the shape the Workshop's Server tab lists it while the Workshop is that
 * battle's map picker.
 *
 * <p>The vote payload carries three kinds of rows: the charts of the Workshop sets installed on the server, the
 * tracks its operator put there outside the Workshop (with the host's own tracks among them), and the tracks its
 * players published with the mod's own editor. The Server tab knows only the first kind by itself, so a server whose
 * maps were made in the editor - the ordinary first session - opened a battle with nothing to choose and no word why.
 * This groups the Workshop rows by set, keeps each track of the other two kinds as an entry of its own with an id the
 * card grid cannot mistake for a Workshop set's, and answers which vote id a chosen Workshop chart has in this battle.
 *
 * <p>Pure data, like the rest of this package: {@code check-vote-layout} builds one from plain rows.
 */
public final class BattleTracks {
    /** What a row is, which is also what its quiet badge says. */
    public enum Kind {
        /** A chart of a Workshop set installed on the server. */
        WORKSHOP,
        /** A track the server's operator put there outside the Workshop. */
        SERVER,
        /** A track a player published on this server with the mod's own editor. */
        PLAYERS
    }

    /**
     * One row of the vote payload, in the words the picker needs. {@code id} is what a vote names; {@code setId} and
     * {@code chartKey} are a Workshop chart's place in its set and stay empty for the other kinds.
     */
    public record Track(String id, Kind kind, long setId, String chartKey, String title, String description,
                        String publishedBy, String mapper, String contentSha256, int bpm, int durationTicks,
                        String previewFile) {
        public Track {
            id = id == null ? "" : id;
            kind = kind == null ? Kind.SERVER : kind;
            chartKey = chartKey == null ? "" : chartKey;
            title = title == null || title.isBlank() ? id : title;
            description = description == null ? "" : description;
            publishedBy = publishedBy == null ? "" : publishedBy;
            mapper = mapper == null ? "" : mapper;
            contentSha256 = contentSha256 == null ? "" : contentSha256;
            previewFile = previewFile == null ? "" : previewFile;
        }
    }

    /**
     * One card of the picker: a Workshop set with every chart of it this battle lists, or one track of the server's
     * own. {@code cardId} is negative, which no Workshop set id ever is, so the card grid's per-card state, the hit
     * test and the vote can all tell an entry from a set by its id alone.
     */
    public record Entry(long cardId, Kind kind, List<Track> rows) {
        public Entry {
            rows = List.copyOf(rows);
            if (rows.isEmpty()) {
                throw new IllegalArgumentException("an entry needs a row");
            }
        }

        public Track first() {
            return rows.get(0);
        }

        public String title() {
            return first().title();
        }

        /** The Workshop set this entry is, or 0 for one of the server's own tracks. */
        public long setId() {
            return kind == Kind.WORKSHOP ? first().setId() : 0L;
        }

        /** The id a vote for one of the server's own tracks names; empty for a Workshop set, voted for by chart. */
        public String trackId() {
            return kind == Kind.WORKSHOP ? "" : first().id();
        }

        /** The charts of a Workshop set this battle lists, in the order the server sent them. */
        public List<String> chartKeys() {
            List<String> keys = new ArrayList<>(rows.size());
            for (Track row : rows) {
                if (!row.chartKey().isEmpty()) {
                    keys.add(row.chartKey());
                }
            }
            return List.copyOf(keys);
        }
    }

    public static final BattleTracks NONE = new BattleTracks(List.of(), false);

    private final List<Entry> entries;
    private final Map<Long, Entry> byCardId = new HashMap<>();
    private final Map<Long, Entry> bySet = new HashMap<>();
    private final boolean truncated;
    private final boolean playerTracks;

    /**
     * @param truncated whether the server had more tracks than its vote payload could carry; the picker says so
     *                  instead of letting a short list pass for the whole one
     */
    public BattleTracks(List<Track> tracks, boolean truncated) {
        this.truncated = truncated;
        Map<Long, List<Track>> sets = new LinkedHashMap<>();
        // The order of first appearance is the order the server sent, which is the order every list here keeps.
        List<Object> order = new ArrayList<>();
        boolean players = false;
        for (Track track : tracks) {
            if (track == null || track.id().isEmpty()) {
                continue;
            }
            players |= track.kind() == Kind.PLAYERS;
            if (track.kind() == Kind.WORKSHOP && track.setId() > 0 && !track.chartKey().isEmpty()) {
                List<Track> rows = sets.get(track.setId());
                if (rows == null) {
                    rows = new ArrayList<>();
                    sets.put(track.setId(), rows);
                    order.add(track.setId());
                }
                rows.add(track);
            } else {
                // A Workshop row without a set it can be matched to is still a track this battle can vote for by its
                // id, so it is offered as one of the server's own rather than dropped.
                order.add(track.kind() == Kind.WORKSHOP ? withKind(track, Kind.SERVER) : track);
            }
        }
        this.playerTracks = players;
        List<Entry> built = new ArrayList<>(order.size());
        long next = -1L;
        for (Object item : order) {
            Entry entry = item instanceof Long setId
                    ? new Entry(next, Kind.WORKSHOP, sets.get(setId))
                    : new Entry(next, ((Track) item).kind(), List.of((Track) item));
            next--;
            built.add(entry);
            byCardId.put(entry.cardId(), entry);
            if (entry.kind() == Kind.WORKSHOP) {
                bySet.put(entry.setId(), entry);
            }
        }
        this.entries = List.copyOf(built);
    }

    private static Track withKind(Track track, Kind kind) {
        return new Track(track.id(), kind, 0L, "", track.title(), track.description(), track.publishedBy(),
                track.mapper(), track.contentSha256(), track.bpm(), track.durationTicks(), track.previewFile());
    }

    public List<Entry> entries() {
        return entries;
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public boolean truncated() {
        return truncated;
    }

    /** Whether the server carries a track one of its players published: the rows the warning about their work is for. */
    public boolean hasPlayerTracks() {
        return playerTracks;
    }

    /** The entry behind a card id, or null when the id is a Workshop set's rather than one of these. */
    public Entry entry(long cardId) {
        return cardId < 0 ? byCardId.get(cardId) : null;
    }

    /** The entry of a Workshop set this battle lists, or null when it lists none of its charts. */
    public Entry workshopSet(long setId) {
        return bySet.get(setId);
    }

    /** The id this battle's vote knows a Workshop chart by, or null when the battle does not list that chart. */
    public String voteId(long setId, String chartKey) {
        Entry entry = bySet.get(setId);
        if (entry == null || chartKey == null || chartKey.isEmpty()) {
            return null;
        }
        for (Track row : entry.rows()) {
            if (row.chartKey().equals(chartKey)) {
                return row.id();
            }
        }
        return null;
    }

    /** Whether one of the server's own tracks with this id is in the battle's list. */
    public boolean hasTrack(String trackId) {
        if (trackId == null || trackId.isEmpty()) {
            return false;
        }
        for (Entry entry : entries) {
            if (entry.kind() != Kind.WORKSHOP && entry.trackId().equals(trackId)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether an entry answers a search: its title, description, publisher, mapper or id holds the words, ignoring
     * case. The Workshop's own cards are searched by the server; these rows never reach it, so they are searched here.
     */
    public static boolean matches(Entry entry, String query) {
        String needle = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        if (needle.isEmpty()) {
            return true;
        }
        for (Track row : entry.rows()) {
            for (String field : new String[] {row.title(), row.description(), row.publishedBy(), row.mapper(), row.id()}) {
                if (field.toLowerCase(Locale.ROOT).contains(needle)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The chart key in a Workshop chart's track id ({@code ws_42_hard} is set 42's {@code hard}), or empty. */
    public static String chartKeyOf(String trackId, long setId) {
        if (trackId == null || setId <= 0) {
            return "";
        }
        String prefix = WorkshopLayout.setFolderName(setId) + "_";
        return trackId.startsWith(prefix) && trackId.length() > prefix.length() ? trackId.substring(prefix.length()) : "";
    }

    /**
     * The Server tab as a battle's map picker. It shares the Installed tab's chrome, whose Import and Open inbox
     * buttons and "Installed" sort are about the player's own copies and have nothing to do with choosing a map for a
     * vote; at 854x480 they took a third of the height, leaving a card and a half in view. They are left out, what
     * followed them moves up into their room, and the sort bar says what a press on a card does - the cards carry no
     * button of their own that would. The listing view applies this to the layout {@link ListingLayout} computed, and
     * {@code check-vote-layout} to the same one.
     */
    public static Layout pickerChrome(Layout full) {
        Rect field = full.rect("listing.search.field");
        Rect importButton = full.rect("listing.tool.import");
        Rect inbox = full.rect("listing.tool.inbox");
        float toolsBottom = Math.max(importButton.bottom(), inbox.bottom());
        float lift = importButton.empty() || field.empty() ? 0F : Math.max(0F, toolsBottom - field.bottom());
        Rect installedSort = full.rect("listing.sort.installed");
        float slide = installedSort.empty() ? 0F : installedSort.w() + Metrics.OPTION_GAP;
        Layout out = new Layout(full.measurer(), full.tr());
        for (Layout.Box box : full.boxes()) {
            String id = box.id();
            if (id.startsWith("listing.tool.") || id.equals("listing.sort.installed")
                    || id.startsWith("listing.sort.installed.")) {
                continue;
            }
            Rect rect = box.rect();
            if (id.equals(ListingLayout.SEARCH)) {
                rect = new Rect(rect.x(), rect.y(), rect.w(), Math.max(0F, rect.h() - lift));
            } else if (!rect.empty() && rect.y() >= toolsBottom - 0.01F && lift > 0F) {
                rect = new Rect(rect.x(), rect.y() - lift, rect.w(), rect.h());
            }
            // The sorts after "Installed" on its line close the gap it leaves, each with its own words and caret.
            if (slide > 0F && id.startsWith("listing.sort.") && !id.equals("listing.sort.label")) {
                String rest = id.substring("listing.sort.".length());
                int dot = rest.indexOf('.');
                Rect button = full.rect("listing.sort." + (dot < 0 ? rest : rest.substring(0, dot)));
                if (Math.abs(button.y() - installedSort.y()) < 1F && button.x() > installedSort.x()) {
                    rect = new Rect(rect.x() - slide, rect.y(), rect.w(), rect.h());
                }
            }
            out.put(new Layout.Box(id, box.parent(), box.kind(), rect, box.text(), box.style(), box.floating()));
        }
        out.setContentHeight(Math.max(0F, full.contentHeight() - lift));
        Rect sortBar = out.rect(ListingLayout.SORT_BAR);
        if (!sortBar.empty()) {
            float last = sortBar.x();
            for (Layout.Box box : out.boxes()) {
                if (box.parent().equals(ListingLayout.SORT_BAR) && box.kind() == Layout.Kind.BUTTON
                        && Math.abs(box.rect().y() - (sortBar.y() + 2F)) < 2F) {
                    last = Math.max(last, box.rect().right());
                }
            }
            float pad = sortBar.w() < Metrics.COMPACT_WIDTH ? Metrics.PAD_SM : Metrics.PAD;
            String hint = full.tr().get(WorkshopStrings.KEY + "picker.hint");
            float room = sortBar.right() - pad - last - Metrics.GAP;
            if (TextFit.width(full.measurer(), hint, TextStyle.CAPTION) <= room) {
                out.labelRight("listing.picker.hint", ListingLayout.SORT_BAR, sortBar.right() - pad,
                        ShellLayout.capY(TextStyle.CAPTION, sortBar.y() + 2F, Metrics.OPTION_H), room, hint,
                        TextStyle.CAPTION);
            }
        }
        return out;
    }
}

package ru.gora.musicbattle.client.workshop.layout;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The listing's filter rows and sort tabs, with the website's semantics: the
 * same rows, the same option ids, the same defaults and the same sort visibility rules. Pure: the screen turns these
 * into buttons, the layout check walks them, and {@code WorkshopApi.SearchQuery} is built from the same ids.
 */
public final class Filters {
    public static final String CATEGORY_DEFAULT = "any";
    public static final String AUDIO_ANY = "any";
    /** Genre and language ids of the workshop (01 section 3); 0 means "Any" and is left out of the request. */
    public static final int[] GENRES = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14};
    public static final int[] LANGUAGES = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14};

    /** Sort fields in toolbar order; {@code featured} and {@code relevance} are only shown sometimes. */
    public static final List<String> SORTS = List.of("title", "artist", "difficulty", "featured", "updated", "rating",
            "downloads", "favourites", "relevance");
    /** The Installed tab sorts locally. */
    public static final List<String> INSTALLED_SORTS = List.of("title", "artist", "installed", "difficulty");

    public enum RowKind {
        SINGLE, MULTI
    }

    /** One option of a filter row. {@code id} is what the API receives; {@code label} is already translated. */
    public record Option(String id, String label) {
    }

    public record Row(String id, RowKind kind, String label, List<Option> options, boolean advanced) {
    }

    private Filters() {
    }

    /** The category options a viewer sees (Favourites and My maps need an account but stay visible; see the review). */
    public static List<Option> categories(Tr tr) {
        List<Option> out = new ArrayList<>();
        for (String id : List.of("any", "featured", "community", "wip", "favourites", "mine")) {
            out.add(new Option(id, tr.get(WorkshopStrings.KEY + "filter.category." + id)));
        }
        return out;
    }

    /** Every filter row in display order. {@code advanced} rows hide behind "More filters". */
    public static List<Row> rows(Tr tr, boolean serverFilter) {
        List<Row> rows = new ArrayList<>();
        List<Option> general = new ArrayList<>();
        general.add(new Option("library", tr.get(WorkshopStrings.KEY + "filter.general.library")));
        if (serverFilter) {
            general.add(new Option("server", tr.get(WorkshopStrings.KEY + "filter.general.server")));
        }
        rows.add(new Row("general", RowKind.MULTI, tr.get(WorkshopStrings.KEY + "filter.general"), general, false));
        rows.add(new Row("category", RowKind.SINGLE, tr.get(WorkshopStrings.KEY + "filter.category"), categories(tr), false));
        List<Option> genres = new ArrayList<>();
        genres.add(new Option("0", tr.get(WorkshopStrings.KEY + "filter.any")));
        for (int id : GENRES) {
            genres.add(new Option(String.valueOf(id), WorkshopStrings.genre(tr, id)));
        }
        rows.add(new Row("genre", RowKind.SINGLE, tr.get(WorkshopStrings.KEY + "filter.genre"), genres, true));
        List<Option> languages = new ArrayList<>();
        languages.add(new Option("0", tr.get(WorkshopStrings.KEY + "filter.any")));
        for (int id : LANGUAGES) {
            languages.add(new Option(String.valueOf(id), WorkshopStrings.language(tr, id)));
        }
        rows.add(new Row("language", RowKind.SINGLE, tr.get(WorkshopStrings.KEY + "filter.language"), languages, true));
        rows.add(new Row("audio", RowKind.SINGLE, tr.get(WorkshopStrings.KEY + "filter.audio"), List.of(
                new Option("any", tr.get(WorkshopStrings.KEY + "filter.any")),
                new Option("included", tr.get(WorkshopStrings.KEY + "filter.audio.included")),
                new Option("external", tr.get(WorkshopStrings.KEY + "filter.audio.external"))), true));
        rows.add(new Row("extra", RowKind.MULTI, tr.get(WorkshopStrings.KEY + "filter.extra"), List.of(
                new Option("fx", tr.get(WorkshopStrings.KEY + "filter.extra.fx")),
                new Option("twoside", tr.get(WorkshopStrings.KEY + "filter.extra.twoside"))), true));
        rows.add(new Row("explicit", RowKind.SINGLE, tr.get(WorkshopStrings.KEY + "filter.explicit"), List.of(
                new Option("hide", tr.get(WorkshopStrings.KEY + "filter.explicit.hide")),
                new Option("show", tr.get(WorkshopStrings.KEY + "filter.explicit.show"))), false));
        return rows;
    }

    /** Sort fields visible for this query and category. */
    public static List<String> visibleSorts(String query, String category) {
        List<String> out = new ArrayList<>();
        for (String field : SORTS) {
            if (field.equals("relevance") && (query == null || query.isBlank())) {
                continue;
            }
            if (field.equals("featured") && !("any".equals(category) || "featured".equals(category))) {
                continue;
            }
            out.add(field);
        }
        return out;
    }

    public static String defaultSort(String query, String category) {
        if (query != null && !query.isBlank()) {
            return "relevance";
        }
        return "featured".equals(category) ? "featured" : "updated";
    }

    /** The active sort, falling back to the default when the current one is not visible any more. */
    public static String effectiveSort(String query, String category, String sort) {
        return sort != null && visibleSorts(query, category).contains(sort) ? sort : defaultSort(query, category);
    }

    /** Multi-select values in a stable order for the request (dot-joined by the API client). */
    public static List<String> ordered(Set<String> values, String... order) {
        Set<String> wanted = new LinkedHashSet<>(values);
        List<String> out = new ArrayList<>();
        for (String id : order) {
            if (wanted.contains(id)) {
                out.add(id);
            }
        }
        return out;
    }
}

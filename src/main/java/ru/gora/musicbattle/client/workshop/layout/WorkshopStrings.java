package ru.gora.musicbattle.client.workshop.layout;

import java.util.List;
import java.util.Locale;

/**
 * Wording and number formatting shared by the Workshop screens and their layout check: key prefixes, counts, sizes,
 * durations, star ratings and relative dates. Pure: every number uses {@link Locale#ROOT} and every word comes from
 * {@link Tr} (no Cyrillic in Java literals).
 */
public final class WorkshopStrings {
    /** Every screen key starts here. */
    public static final String KEY = "screen.music_battle.workshop.";
    /** Status, genre, language, report and error keys. */
    public static final String DATA = "music_battle.workshop.";
    /** What separates the parts of a card's statistics line; the card drops a part whole, never cuts it. */
    public static final String STATS_SEPARATOR = "  ";
    /** The roles the site marks a commenter with (admin, mod, curator). */
    public static final List<String> COMMENT_ROLES = List.of("admin", "mod", "curator");

    private WorkshopStrings() {
    }

    /** "1,234" style counts up to 9 999, then "12K", "1.2M". */
    public static String count(long value) {
        if (value < 0) {
            return "0";
        }
        if (value < 10_000) {
            return String.format(Locale.ROOT, "%d", value);
        }
        if (value < 1_000_000) {
            return String.format(Locale.ROOT, "%.1fK", value / 1000.0).replace(".0K", "K");
        }
        return String.format(Locale.ROOT, "%.1fM", value / 1_000_000.0).replace(".0M", "M");
    }

    /** "m:ss" for lengths, "h:mm:ss" past an hour. */
    public static String duration(long millis) {
        long seconds = Math.max(0, millis) / 1000L;
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long rest = seconds % 60;
        return hours > 0
                ? String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, rest)
                : String.format(Locale.ROOT, "%d:%02d", minutes, rest);
    }

    /** "7.0 MiB" style sizes. */
    public static String size(long bytes) {
        if (bytes < 0) {
            return "?";
        }
        if (bytes < 1024) {
            return String.format(Locale.ROOT, "%d B", bytes);
        }
        if (bytes < 1024L * 1024) {
            return String.format(Locale.ROOT, "%.1f KiB", bytes / 1024.0);
        }
        return String.format(Locale.ROOT, "%.1f MiB", bytes / (1024.0 * 1024.0));
    }

    /** Star ratings are floored to two decimals, never rounded up ("6.9999" shows 6.99). */
    public static String stars(double value) {
        return String.format(Locale.ROOT, "%.2f", Math.floor(Math.max(0, value) * 100.0 + 1e-9) / 100.0);
    }

    /** A rating average like "8.21", or a dash when there is none. */
    public static String rating(Double value) {
        return value == null ? "—" : String.format(Locale.ROOT, "%.2f", value);
    }

    public static String percent(double fraction) {
        return String.format(Locale.ROOT, "%d%%", Math.round(Math.max(0, Math.min(1, fraction)) * 100));
    }

    /** Russian one/few/many, English one/many; both languages carry all three keys so the key sets stay identical. */
    public static String plural(Tr tr, String keyBase, long value) {
        String form;
        long n = Math.abs(value);
        if (tr.russianPlurals()) {
            long mod10 = n % 10;
            long mod100 = n % 100;
            if (mod10 == 1 && mod100 != 11) {
                form = "one";
            } else if (mod10 >= 2 && mod10 <= 4 && (mod100 < 12 || mod100 > 14)) {
                form = "few";
            } else {
                form = "many";
            }
        } else {
            form = n == 1 ? "one" : "many";
        }
        return tr.get(keyBase + "." + form, count(value));
    }

    /**
     * A card's statistics line: its downloads, its favourites and how long ago it was updated (left out when unknown),
     * each count in its plural form, apart by {@link #STATS_SEPARATOR}. Cards used one form for every count, so
     * Russian read «1234 загрузок» and «1 загрузок» and English "1 downloads", while the beatmap page counted right.
     */
    public static String cardStats(Tr tr, long downloads, long favourites, String updatedAt, long nowMillis) {
        StringBuilder out = new StringBuilder();
        out.append(plural(tr, KEY + "card.downloads", downloads));
        out.append(STATS_SEPARATOR).append(plural(tr, KEY + "card.favourites", favourites));
        if (updatedAt != null && !updatedAt.isEmpty()) {
            out.append(STATS_SEPARATOR).append(relative(tr, updatedAt, nowMillis));
        }
        return out.toString();
    }

    /** "5 hours ago" from an ISO timestamp; an unreadable or future date falls back to "just now". */
    public static String relative(Tr tr, String isoTimestamp, long nowMillis) {
        long at = epochMillis(isoTimestamp);
        if (at <= 0) {
            return tr.get(KEY + "time.unknown");
        }
        long seconds = Math.max(0, (nowMillis - at) / 1000L);
        if (seconds < 60) {
            return tr.get(KEY + "time.now");
        }
        if (seconds < 3600) {
            return plural(tr, KEY + "time.minutes", seconds / 60);
        }
        if (seconds < 86_400) {
            return plural(tr, KEY + "time.hours", seconds / 3600);
        }
        if (seconds < 2_592_000L) {
            return plural(tr, KEY + "time.days", seconds / 86_400);
        }
        if (seconds < 31_536_000L) {
            return plural(tr, KEY + "time.months", seconds / 2_592_000L);
        }
        return plural(tr, KEY + "time.years", seconds / 31_536_000L);
    }

    /** Milliseconds of an ISO-8601 instant, or 0 when it cannot be read (never throws). */
    public static long epochMillis(String isoTimestamp) {
        if (isoTimestamp == null || isoTimestamp.length() < 10) {
            return 0L;
        }
        try {
            return java.time.Instant.parse(isoTimestamp).toEpochMilli();
        } catch (RuntimeException ignored) {
            try {
                return java.time.LocalDate.parse(isoTimestamp.substring(0, 10)).atStartOfDay(java.time.ZoneOffset.UTC)
                        .toInstant().toEpochMilli();
            } catch (RuntimeException alsoBad) {
                return 0L;
            }
        }
    }

    /** {@code music_battle.workshop.status.<status>}; unknown values keep their code. */
    public static String status(Tr tr, String status) {
        String safe = status == null ? "" : status.toLowerCase(Locale.ROOT).replaceAll("[^a-z_]", "");
        String label = tr.get(DATA + "status." + safe);
        return label.equals(DATA + "status." + safe) ? safe.toUpperCase(Locale.ROOT) : label.toUpperCase(Locale.ROOT);
    }

    public static String genre(Tr tr, int id) {
        return tr.get(DATA + "genre." + id);
    }

    public static String language(Tr tr, int id) {
        return tr.get(DATA + "language." + id);
    }

    /** Upper case for pills and badges, without a locale surprise (Turkish i). */
    public static String upper(String text) {
        return text == null ? "" : text.toUpperCase(Locale.ROOT);
    }

    /**
     * A commenter's role badge in the player's language, upper case as badges are, from the account view's own words
     * ({@code account.role.<role>}); null for a role this version does not know. The badges used to be the site's
     * codes upper-cased, so a Russian page read "MOD" and "CURATOR" beside the names.
     */
    public static String roleBadge(Tr tr, String role) {
        String code = role == null ? "" : role.toLowerCase(Locale.ROOT);
        return COMMENT_ROLES.contains(code) ? upper(tr.get(KEY + "account.role." + code)) : null;
    }
}

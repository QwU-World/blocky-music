package ru.gora.musicbattle.workshop.api;

/**
 * Turns the picture and audio addresses the Workshop answers with into something that can be fetched.
 *
 * <p>The API sends paths, not addresses: a cover is {@code /files/covers/<sha>/card@1x.jpg} and a preview is
 * {@code /files/previews/<sha>-<ms>.ogg}, both relative to the Workshop origin, exactly as the website's own pages
 * use them. Requests need an absolute address, and the picture cache had no way to make one, so every cover in the
 * in-game screens stayed blank while installed maps, read from disk, drew fine.
 *
 * <p>Account pictures are the other case: they live on qwu.world, a different host, and arrive absolute. They are
 * passed through untouched.
 *
 * <p>Pure string work, so the rule is checked without a window (tools/WorkshopApiChecks.java).
 */
public final class MediaUrl {
    private MediaUrl() {
    }

    /**
     * {@code origin} joined to a path, an absolute address unchanged, or null when there is nothing to fetch.
     *
     * <p>A protocol-relative address ({@code //other.host/x.jpg}) is refused rather than joined: it points at
     * someone else's host, and the Workshop never sends one.
     */
    public static String absolute(String origin, String pathOrUrl) {
        if (pathOrUrl == null) {
            return null;
        }
        String url = pathOrUrl.trim();
        if (url.isEmpty()) {
            return null;
        }
        if (url.charAt(0) != '/') {
            return url;
        }
        if (url.startsWith("//")) {
            return null;
        }
        String base = trimTrailingSlashes(origin);
        return base == null ? null : base + url;
    }

    /** The origin without its trailing slashes, or null when there is nothing usable. */
    public static String trimTrailingSlashes(String origin) {
        if (origin == null) {
            return null;
        }
        String value = origin.trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value.isEmpty() ? null : value;
    }
}

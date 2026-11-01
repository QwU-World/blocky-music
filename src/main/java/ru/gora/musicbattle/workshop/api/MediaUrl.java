package ru.gora.musicbattle.workshop.api;

/**
 * Turns the picture and audio addresses the Workshop answers with into something that can be fetched.
 *
 * <p>The API sends paths, not addresses: a cover is {@code /files/covers/<sha>/card@1x.jpg} and a preview is
 * {@code /files/previews/<sha>-<ms>.ogg}, both relative to the Workshop origin, exactly as the website's own pages
 * use them. Requests need an absolute address, and the picture cache had no way to make one, so every cover in the
 * in-game screens stayed blank while installed maps, read from disk, drew fine.
 *
 * <p>Account pictures are the other case: they live on qwu.world, a different host, and arrive absolute. Such an
 * address is fetched only when it is https on qwu.world or one of its subdomains ({@link #isFirstParty}, the rule the
 * Workshop's links use too), or on the Workshop origin itself; any other host gets the placeholder, because the first-run
 * screen names these hosts and no other.
 *
 * <p>Pure string work, so the rule is checked without a window (tools/WorkshopApiChecks.java).
 */
public final class MediaUrl {
    /** QwU World's own domain: https on it or a subdomain is first party (links, account pictures). */
    public static final String FIRST_PARTY_DOMAIN = "qwu.world";

    private MediaUrl() {
    }

    /** https on qwu.world or a subdomain, with no user info (which could disguise the real host). */
    public static boolean isFirstParty(java.net.URI uri) {
        if (uri == null || !"https".equalsIgnoreCase(uri.getScheme()) || uri.getRawUserInfo() != null) {
            return false;
        }
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(java.util.Locale.ROOT);
        return host.equals(FIRST_PARTY_DOMAIN) || host.endsWith("." + FIRST_PARTY_DOMAIN);
    }

    /**
     * {@code origin} joined to a path, a first-party absolute address unchanged, or null when there is nothing to
     * fetch.
     *
     * <p>A protocol-relative address ({@code //other.host/x.jpg}) is refused rather than joined: it points at
     * someone else's host, and the Workshop never sends one. So is an absolute address on any host but QwU World's
     * ({@link #isFirstParty}) or the Workshop origin: the screens then draw the placeholder.
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
            return allowedAbsolute(origin, url) ? url : null;
        }
        if (url.startsWith("//")) {
            return null;
        }
        String base = trimTrailingSlashes(origin);
        return base == null ? null : base + url;
    }

    /** An absolute address the client may fetch: first party, or exactly the Workshop origin's scheme, host and port. */
    private static boolean allowedAbsolute(String origin, String url) {
        java.net.URI uri;
        try {
            uri = new java.net.URI(url);
        } catch (java.net.URISyntaxException | RuntimeException invalid) {
            return false;
        }
        if (!uri.isAbsolute() || uri.getHost() == null || uri.getRawUserInfo() != null) {
            return false;
        }
        if (isFirstParty(uri)) {
            return true;
        }
        String base = trimTrailingSlashes(origin);
        if (base == null) {
            return false;
        }
        try {
            java.net.URI home = new java.net.URI(base);
            return home.getScheme() != null && home.getScheme().equalsIgnoreCase(uri.getScheme())
                    && home.getHost() != null && home.getHost().equalsIgnoreCase(uri.getHost())
                    && home.getPort() == uri.getPort();
        } catch (java.net.URISyntaxException | RuntimeException invalid) {
            return false;
        }
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

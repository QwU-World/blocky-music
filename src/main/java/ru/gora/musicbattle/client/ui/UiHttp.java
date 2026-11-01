package ru.gora.musicbattle.client.ui;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLLoader;
import ru.gora.musicbattle.MusicBattleMod;

/**
 * Blocking HTTP for the UI toolkit (covers, skin-head name lookups). Call off the render thread.
 *
 * <p>Uses {@link HttpURLConnection} opened with Minecraft's proxy: the game's proxy is SOCKS or none, and
 * {@code java.net.http.HttpClient} ignores SOCKS proxies and would connect directly. Connect timeout 10 s,
 * read (idle) timeout 30 s, no redirects, no caches, a response size cap, http and https only.
 *
 * <p>Nothing here decides whether the player allowed network access: callers only pass URLs after the
 * Workshop's network consent.
 */
public final class UiHttp {
    public static final int CONNECT_TIMEOUT_MS = 10_000;
    public static final int READ_TIMEOUT_MS = 30_000;
    private static volatile String userAgent;

    private UiHttp() {
    }

    /** A non-200 response. */
    public static final class StatusException extends IOException {
        private final int status;

        StatusException(int status, String url) {
            super("HTTP " + status + " for " + url);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }

    public static byte[] get(String url, long maxBytes) throws IOException {
        return request("GET", url, null, null, maxBytes);
    }

    public static byte[] postJson(String url, String json, long maxBytes) throws IOException {
        return request("POST", url, json.getBytes(StandardCharsets.UTF_8), "application/json", maxBytes);
    }

    /** Parses and checks an http(s) URL with a host. */
    public static URI checkedUri(String url) throws IOException {
        try {
            URI uri = new URI(url);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!scheme.equals("https") && !scheme.equals("http") || uri.getHost() == null) {
                throw new IOException("Only absolute http(s) URLs are fetched: " + url);
            }
            return uri;
        } catch (URISyntaxException | IllegalArgumentException e) {
            throw new IOException("Invalid URL: " + url, e);
        }
    }

    private static byte[] request(String method, String url, byte[] body, String contentType, long maxBytes) throws IOException {
        URI uri = checkedUri(url);
        URLConnection raw = uri.toURL().openConnection(proxy());
        if (!(raw instanceof HttpURLConnection connection)) {
            throw new IOException("Not an HTTP connection: " + url);
        }
        try {
            connection.setRequestMethod(method);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            connection.setRequestProperty("User-Agent", userAgent());
            connection.setRequestProperty("Accept-Encoding", "identity");
            if (body != null) {
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", contentType);
                connection.setFixedLengthStreamingMode(body.length);
                try (OutputStream out = connection.getOutputStream()) {
                    out.write(body);
                }
            }
            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) {
                throw new StatusException(status, url);
            }
            long declared = connection.getContentLengthLong();
            if (declared > maxBytes) {
                throw new IOException("Response too large (" + declared + " bytes) for " + url);
            }
            try (InputStream in = connection.getInputStream()) {
                return readLimited(in, maxBytes, url);
            }
        } finally {
            connection.disconnect();
        }
    }

    static byte[] readLimited(InputStream in, long maxBytes, String url) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[16_384];
        long total = 0;
        int read;
        while ((read = in.read(buffer)) != -1) {
            total += read;
            if (total > maxBytes) {
                throw new IOException("Response larger than " + maxBytes + " bytes for " + url);
            }
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    static Proxy proxy() {
        Proxy proxy = Minecraft.getInstance().getProxy();
        return proxy == null ? Proxy.NO_PROXY : proxy;
    }

    /** {@code BlockyMusic/<modVersion> (Minecraft 1.21.1; NeoForge <version>; <os>)}. */
    public static String userAgent() {
        String cached = userAgent;
        if (cached == null) {
            String modVersion = ModList.get().getModContainerById(MusicBattleMod.MOD_ID)
                    .map(container -> container.getModInfo().getVersion().toString()).orElse("dev");
            String neoForge;
            try {
                neoForge = FMLLoader.versionInfo().neoForgeVersion();
            } catch (RuntimeException e) {
                neoForge = "unknown";
            }
            String os = System.getProperty("os.name", "unknown").replaceAll("[^A-Za-z0-9 ._-]", "");
            cached = "BlockyMusic/" + modVersion + " (Minecraft 1.21.1; NeoForge " + neoForge + "; " + os + ")";
            userAgent = cached;
        }
        return cached;
    }
}

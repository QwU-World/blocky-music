package ru.gora.musicbattle.workshop.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import ru.gora.musicbattle.workshop.api.WorkshopDtos.AuthResult;
import ru.gora.musicbattle.workshop.api.WorkshopDtos.Challenge;
import ru.gora.musicbattle.workshop.api.WorkshopDtos.CommentsPage;
import ru.gora.musicbattle.workshop.api.WorkshopDtos.DeviceCodes;
import ru.gora.musicbattle.workshop.api.WorkshopDtos.DownloadTicket;
import ru.gora.musicbattle.workshop.api.WorkshopDtos.FavouriteState;
import ru.gora.musicbattle.workshop.api.WorkshopDtos.InstalledEntry;
import ru.gora.musicbattle.workshop.api.WorkshopDtos.InstalledResult;
import ru.gora.musicbattle.workshop.api.WorkshopDtos.LookupMatch;
import ru.gora.musicbattle.workshop.api.WorkshopDtos.Me;
import ru.gora.musicbattle.workshop.api.WorkshopDtos.Meta;
import ru.gora.musicbattle.workshop.api.WorkshopDtos.RatingSummary;
import ru.gora.musicbattle.workshop.api.WorkshopDtos.SearchPage;
import ru.gora.musicbattle.workshop.api.WorkshopDtos.SetCard;
import ru.gora.musicbattle.workshop.api.WorkshopDtos.SetDetail;
import ru.gora.musicbattle.workshop.api.WorkshopDtos.SetHistory;
import ru.gora.musicbattle.workshop.api.WorkshopDtos.Upload;

/**
 * Blocking client for the workshop HTTP API v1 (spec 02, 06 section 4). Pure JDK and Gson; call it off the render
 * thread.
 *
 * <ul>
 *   <li><b>Consent first:</b> every request checks {@code networkAllowed} before opening anything; without consent
 *       it fails with {@link WorkshopApiException#CONSENT_REQUIRED} and nothing leaves the machine.</li>
 *   <li><b>Transport:</b> {@link HttpURLConnection} opened with the game's proxy (Minecraft's proxy is SOCKS or none,
 *       and {@code java.net.http.HttpClient} would ignore SOCKS). Connect timeout 10 s, read (idle) timeout 30 s,
 *       no redirects, no caches, default trust store.</li>
 *   <li><b>Origin:</b> the base URL is {@value #DEFAULT_BASE_URL}, overridable with the system property
 *       {@value #BASE_URL_PROPERTY} for development (plain http only for loopback hosts). Server-provided URLs
 *       (download links, previews) must resolve to the same origin, otherwise the call is refused.</li>
 *   <li><b>Tokens:</b> the workshop bearer token is attached only to {@code /api/} requests on the workshop origin,
 *       never to files and never in URLs. This class never sees the Minecraft access token.</li>
 *   <li><b>Responses:</b> every JSON body must carry {@code "schema": 1} (spec 02 section 1), otherwise
 *       {@link WorkshopApiException#SCHEMA_MISMATCH}; errors become {@link WorkshopApiException} with the API code and
 *       {@code Retry-After}.</li>
 *   <li><b>Drive, not network:</b> a download reads from a socket and writes to a drive, and the JDK throws the same
 *       {@link IOException} for both. The write side is wrapped, so a drive with no room left fails with
 *       {@link #DISK_FULL} and any other local write failure with {@link #DISK_ERROR}; neither is ever reported as
 *       a connection problem.</li>
 * </ul>
 */
public final class WorkshopApi {
    public static final String DEFAULT_BASE_URL = "https://blocky-music.qwu.world";
    public static final String BASE_URL_PROPERTY = "blockymusic.workshop.url";
    public static final int SCHEMA = 1;
    public static final int CONNECT_TIMEOUT_MS = 10_000;
    public static final int READ_TIMEOUT_MS = 30_000;
    public static final long MAX_JSON_BYTES = 4L * 1024 * 1024;
    static final int STREAMING_THRESHOLD_BYTES = 1024 * 1024;
    /**
     * The drive holding the download ran out of room: the player has to free space, not retry. Local code, so it
     * lives here rather than among the API's own codes in {@link WorkshopApiException}.
     */
    public static final String DISK_FULL = "disk_full";
    /** The download could not be written for another local reason: a read-only folder, a lock, a removed drive. */
    public static final String DISK_ERROR = "disk_error";
    /**
     * Room left free beyond a download. Minecraft keeps writing its world save, its log and its own caches while a
     * set installs, so filling the drive to the last byte would take the game down with it.
     */
    public static final long DISK_MARGIN_BYTES = 16L * 1024 * 1024;
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    /** Opens a connection; tests wrap it to see the proxy and the URL. */
    @FunctionalInterface
    public interface ConnectionOpener {
        URLConnection open(URL url, Proxy proxy) throws IOException;
    }

    /** Supplies the current workshop access token, or null when signed out. */
    @FunctionalInterface
    public interface TokenSource {
        String currentAccessToken();
    }

    public record Options(URI baseUri, Supplier<Proxy> proxy, String userAgent, BooleanSupplier networkAllowed,
                          ConnectionOpener opener, int connectTimeoutMs, int readTimeoutMs) {
        public Options {
            Objects.requireNonNull(baseUri, "baseUri");
            proxy = proxy == null ? () -> Proxy.NO_PROXY : proxy;
            userAgent = userAgent == null || userAgent.isBlank() ? "BlockyMusic/dev" : userAgent;
            networkAllowed = networkAllowed == null ? () -> false : networkAllowed;
            opener = opener == null ? URL::openConnection : opener;
            connectTimeoutMs = connectTimeoutMs <= 0 ? CONNECT_TIMEOUT_MS : connectTimeoutMs;
            readTimeoutMs = readTimeoutMs <= 0 ? READ_TIMEOUT_MS : readTimeoutMs;
        }

        public static Options of(URI baseUri, Supplier<Proxy> proxy, String userAgent, BooleanSupplier networkAllowed) {
            return new Options(baseUri, proxy, userAgent, networkAllowed, null, 0, 0);
        }

        public Options withOpener(ConnectionOpener value) {
            return new Options(baseUri, proxy, userAgent, networkAllowed, value, connectTimeoutMs, readTimeoutMs);
        }

        public Options withTimeouts(int connectMs, int readMs) {
            return new Options(baseUri, proxy, userAgent, networkAllowed, opener, connectMs, readMs);
        }
    }

    /** Raw response of a non-JSON call. */
    public record RawResponse(int status, Map<String, List<String>> headers, byte[] body) {
    }

    /** A finished streaming download. */
    public record DownloadResult(Path file, long bytes, String sha256) {
    }

    private final Options options;
    private final URI base;
    private volatile TokenSource tokenSource = () -> null;
    /**
     * The device code of the last code sign-in started here, kept so that abandoning it can be reported. The code
     * sign-in's state machine deliberately never shows the code to a screen, and the screen is where the player gives
     * up, so the one place that sees both the code and the request to drop it is this client.
     */
    private final AtomicReference<String> startedDeviceCode = new AtomicReference<>();

    public WorkshopApi(Options options) {
        this.options = options;
        this.base = normalizeBase(options.baseUri());
    }

    /** The base URL from {@value #BASE_URL_PROPERTY} when set and valid, else {@value #DEFAULT_BASE_URL}. */
    public static URI configuredBaseUri() {
        String override = System.getProperty(BASE_URL_PROPERTY);
        if (override != null && !override.isBlank()) {
            try {
                return parseBaseUrl(override.trim());
            } catch (IllegalArgumentException ignored) {
                // An invalid override falls back to production rather than to nothing.
            }
        }
        return URI.create(DEFAULT_BASE_URL);
    }

    /** Checks a base URL: https anywhere, http only on loopback, no user info, query or fragment. */
    public static URI parseBaseUrl(String value) {
        URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("invalid workshop URL", exception);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (host.isEmpty() || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IllegalArgumentException("workshop URL needs a host and no user info, query or fragment");
        }
        boolean loopback = host.equals("localhost") || host.equals("127.0.0.1") || host.equals("[::1]") || host.equals("::1");
        if (!scheme.equals("https") && !(scheme.equals("http") && loopback)) {
            throw new IllegalArgumentException("workshop URL must be https (http only for loopback)");
        }
        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        if (!path.isEmpty() && !path.equals("/")) {
            throw new IllegalArgumentException("workshop URL must be an origin without a path");
        }
        return URI.create(scheme + "://" + uri.getRawAuthority());
    }

    private static URI normalizeBase(URI uri) {
        return parseBaseUrl(uri.toString());
    }

    public URI baseUri() {
        return base;
    }

    public void setTokenSource(TokenSource source) {
        this.tokenSource = source == null ? () -> null : source;
    }

    public boolean networkAllowed() {
        return options.networkAllowed().getAsBoolean();
    }

    /** A site page URL (terms, a set page, the /link page) on the workshop origin. */
    public String pageUrl(String path) {
        return base + (path.startsWith("/") ? path : "/" + path);
    }

    /**
     * Resolves a path ({@code /api/...}, {@code /files/...}) or an absolute URL from a response. Absolute URLs must
     * have exactly the workshop origin.
     */
    public URI resolve(String pathOrUrl) throws WorkshopApiException {
        if (pathOrUrl == null || pathOrUrl.isEmpty() || pathOrUrl.length() > 4096) {
            throw new WorkshopApiException(WorkshopApiException.BAD_URL, "empty or overlong URL");
        }
        for (int i = 0; i < pathOrUrl.length(); i++) {
            char c = pathOrUrl.charAt(i);
            if (c < 0x21 || c == 0x7F || c == '\\') {
                throw new WorkshopApiException(WorkshopApiException.BAD_URL, "URL with control characters");
            }
        }
        URI uri;
        try {
            if (pathOrUrl.startsWith("/") && !pathOrUrl.startsWith("//")) {
                uri = new URI(base + pathOrUrl);
            } else {
                uri = new URI(pathOrUrl);
            }
        } catch (URISyntaxException exception) {
            throw new WorkshopApiException(WorkshopApiException.BAD_URL, "invalid URL", exception);
        }
        if (!sameOrigin(uri)) {
            throw new WorkshopApiException(WorkshopApiException.BAD_URL, "URL outside the workshop origin");
        }
        return uri.normalize();
    }

    public boolean sameOrigin(URI uri) {
        if (uri == null || uri.getScheme() == null || uri.getRawUserInfo() != null) {
            return false;
        }
        return uri.getScheme().equalsIgnoreCase(base.getScheme())
                && String.valueOf(uri.getHost()).equalsIgnoreCase(String.valueOf(base.getHost()))
                && effectivePort(uri) == effectivePort(base);
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() >= 0) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    // ================================================================ low level

    public JsonObject getJson(String path, boolean auth, CancelToken cancel) throws WorkshopApiException {
        return json("GET", path, null, auth, cancel);
    }

    public JsonObject sendJson(String method, String path, JsonElement body, boolean auth, CancelToken cancel)
            throws WorkshopApiException {
        return json(method, path, body, auth, cancel);
    }

    private JsonObject json(String method, String path, JsonElement body, boolean auth, CancelToken cancel)
            throws WorkshopApiException {
        byte[] bytes = body == null ? null : WorkshopDtos.GSON.toJson(body).getBytes(StandardCharsets.UTF_8);
        RawResponse response = exchange(method, resolve(path), bytes, bytes == null ? null : "application/json", null,
                auth, MAX_JSON_BYTES, cancel);
        if (response.status() == 204) {
            return null;
        }
        return parseJsonBody(response);
    }

    private static JsonObject parseJsonBody(RawResponse response) throws WorkshopApiException {
        JsonObject object = parseObject(response.body());
        if (object == null) {
            throw new WorkshopApiException(WorkshopApiException.BAD_RESPONSE, response.status(), -1, null, "response is not a JSON object");
        }
        requireSchema(object, response.status());
        return object;
    }

    private static JsonObject parseObject(byte[] body) {
        if (body == null || body.length == 0) {
            return null;
        }
        try {
            JsonElement element = JsonParser.parseString(new String(body, StandardCharsets.UTF_8));
            return element.isJsonObject() ? element.getAsJsonObject() : null;
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static void requireSchema(JsonObject object, int status) throws WorkshopApiException {
        JsonElement schema = object.get("schema");
        if (schema == null || !schema.isJsonPrimitive() || !schema.getAsJsonPrimitive().isNumber()
                || schema.getAsDouble() != SCHEMA) {
            throw new WorkshopApiException(WorkshopApiException.SCHEMA_MISMATCH, status, -1, null,
                    "unsupported response schema " + schema);
        }
    }

    /**
     * One HTTP exchange with the full body read (bounded). Non-2xx statuses throw. {@code extraHeaders} must not carry
     * authorization; that is added here and only for {@code /api/} on the workshop origin.
     */
    RawResponse exchange(String method, URI uri, byte[] body, String contentType, Map<String, String> extraHeaders,
                         boolean auth, long maxBytes, CancelToken cancel) throws WorkshopApiException {
        CancelToken token = cancel == null ? CancelToken.none() : cancel;
        if (method.equals("PATCH")) {
            return patch(uri, body, contentType, auth, maxBytes, token);
        }
        HttpURLConnection connection = open(method, uri, auth, token);
        try {
            if (extraHeaders != null) {
                for (Map.Entry<String, String> header : extraHeaders.entrySet()) {
                    if (!header.getKey().equalsIgnoreCase("Authorization")) {
                        connection.setRequestProperty(header.getKey(), header.getValue());
                    }
                }
            }
            if (body != null) {
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", contentType);
                // Small bodies are buffered: in streaming mode HttpURLConnection drops the error body of a 401.
                if (body.length > STREAMING_THRESHOLD_BYTES) {
                    connection.setFixedLengthStreamingMode(body.length);
                }
                try (OutputStream out = connection.getOutputStream()) {
                    out.write(body);
                }
            } else if (!method.equals("GET") && !method.equals("DELETE")) {
                connection.setDoOutput(true);
                connection.getOutputStream().close();
            }
            int status = connection.getResponseCode();
            checkStatus(status, connection.getHeaderField("Retry-After"), connection::getErrorStream, maxBytes);
            long declared = connection.getContentLengthLong();
            if (declared > maxBytes) {
                throw new WorkshopApiException(WorkshopApiException.TOO_LARGE, status, -1, null, "response of " + declared + " bytes");
            }
            byte[] bytes;
            try (InputStream in = connection.getInputStream()) {
                bytes = readLimited(in, maxBytes);
            }
            token.throwIfCancelled();
            return new RawResponse(status, connection.getHeaderFields(), bytes);
        } catch (IOException exception) {
            throw translate(exception, token);
        } finally {
            token.unbind();
            connection.disconnect();
        }
    }

    /**
     * {@link HttpURLConnection} cannot send PATCH, so PATCH goes through {@code java.net.http.HttpClient} with the same
     * consent, origin, token, timeout and redirect rules. That client cannot use a SOCKS proxy; with one configured
     * the call is refused ({@code proxy_unsupported}) instead of silently connecting directly.
     */
    private RawResponse patch(URI uri, byte[] body, String contentType, boolean auth, long maxBytes, CancelToken token)
            throws WorkshopApiException {
        token.throwIfCancelled();
        if (!options.networkAllowed().getAsBoolean()) {
            throw new WorkshopApiException(WorkshopApiException.CONSENT_REQUIRED, "network access was not allowed");
        }
        if (!sameOrigin(uri)) {
            throw new WorkshopApiException(WorkshopApiException.BAD_URL, "URL outside the workshop origin");
        }
        Proxy proxy = options.proxy().get();
        java.net.ProxySelector selector;
        if (proxy == null || proxy.type() == Proxy.Type.DIRECT) {
            selector = java.net.http.HttpClient.Builder.NO_PROXY;
        } else if (proxy.type() == Proxy.Type.HTTP && proxy.address() instanceof java.net.InetSocketAddress address) {
            selector = java.net.ProxySelector.of(address);
        } else {
            throw new WorkshopApiException("proxy_unsupported", "PATCH cannot use a SOCKS proxy");
        }
        java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(options.connectTimeoutMs()))
                .followRedirects(java.net.http.HttpClient.Redirect.NEVER)
                .proxy(selector)
                .build();
        java.net.http.HttpRequest.Builder request = java.net.http.HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMillis(options.readTimeoutMs() * 2L))
                .header("User-Agent", options.userAgent())
                .header("Accept", "application/json")
                .method("PATCH", java.net.http.HttpRequest.BodyPublishers.ofByteArray(body == null ? new byte[0] : body));
        if (contentType != null) {
            request.header("Content-Type", contentType);
        }
        if (auth && String.valueOf(uri.getRawPath()).startsWith("/api/")) {
            String bearer = tokenSource.currentAccessToken();
            if (bearer != null && !bearer.isEmpty()) {
                request.header("Authorization", "Bearer " + bearer);
            }
        }
        java.util.concurrent.CompletableFuture<java.net.http.HttpResponse<InputStream>> future =
                client.sendAsync(request.build(), java.net.http.HttpResponse.BodyHandlers.ofInputStream());
        token.bind(() -> future.cancel(true));
        try {
            java.net.http.HttpResponse<InputStream> response = future.get();
            int status = response.statusCode();
            try (InputStream in = response.body()) {
                checkStatus(status, response.headers().firstValue("Retry-After").orElse(null), () -> in, maxBytes);
                byte[] bytes = readLimited(in, maxBytes);
                token.throwIfCancelled();
                return new RawResponse(status, response.headers().map(), bytes);
            }
        } catch (IOException exception) {
            throw translate(exception, token);
        } catch (java.util.concurrent.CancellationException | InterruptedException exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new WorkshopApiException(WorkshopApiException.CANCELLED, "cancelled", exception);
        } catch (java.util.concurrent.ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof java.net.http.HttpTimeoutException) {
                throw new WorkshopApiException(WorkshopApiException.TIMEOUT, "timed out", cause);
            }
            throw new WorkshopApiException(WorkshopApiException.NETWORK_ERROR, "network error", cause);
        } finally {
            token.unbind();
        }
    }

    @FunctionalInterface
    private interface StreamSupplier {
        InputStream get() throws IOException;
    }

    private HttpURLConnection open(String method, URI uri, boolean auth, CancelToken token) throws WorkshopApiException {
        token.throwIfCancelled();
        if (!options.networkAllowed().getAsBoolean()) {
            throw new WorkshopApiException(WorkshopApiException.CONSENT_REQUIRED, "network access was not allowed");
        }
        if (!sameOrigin(uri)) {
            throw new WorkshopApiException(WorkshopApiException.BAD_URL, "URL outside the workshop origin");
        }
        try {
            Proxy proxy = options.proxy().get();
            URLConnection raw = options.opener().open(uri.toURL(), proxy == null ? Proxy.NO_PROXY : proxy);
            if (!(raw instanceof HttpURLConnection connection)) {
                throw new WorkshopApiException(WorkshopApiException.BAD_URL, "not an HTTP URL");
            }
            connection.setRequestMethod(method);
            connection.setConnectTimeout(options.connectTimeoutMs());
            connection.setReadTimeout(options.readTimeoutMs());
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            connection.setRequestProperty("User-Agent", options.userAgent());
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Accept-Encoding", "identity");
            if (auth && String.valueOf(uri.getRawPath()).startsWith("/api/")) {
                String bearer = tokenSource.currentAccessToken();
                if (bearer != null && !bearer.isEmpty()) {
                    connection.setRequestProperty("Authorization", "Bearer " + bearer);
                }
            }
            token.bind(connection::disconnect);
            return connection;
        } catch (WorkshopApiException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw new WorkshopApiException(WorkshopApiException.NETWORK_ERROR, "could not open the connection", exception);
        }
    }

    private static void checkStatus(int status, String retryAfterHeader, StreamSupplier errorBody, long maxBytes) throws IOException {
        if (status >= 200 && status < 300) {
            return;
        }
        long retryAfter = parseRetryAfter(retryAfterHeader);
        if (status >= 300 && status < 400) {
            throw new WorkshopApiException(WorkshopApiException.UNEXPECTED_REDIRECT, status, retryAfter, null, "redirect refused");
        }
        byte[] body = new byte[0];
        try {
            InputStream err = errorBody.get();
            if (err != null) {
                try (err) {
                    body = readLimited(err, Math.min(maxBytes, 256L * 1024));
                }
            }
        } catch (IOException ignored) {
            // The status alone is enough to report.
        }
        JsonObject object = parseObject(body);
        String code = null;
        String message = null;
        String field = null;
        if (object != null && object.has("schema")) {
            requireSchema(object, status);
            JsonElement error = object.get("error");
            if (error != null && error.isJsonObject()) {
                JsonObject e = error.getAsJsonObject();
                code = string(e, "code");
                message = string(e, "message");
                field = string(e, "field");
                if (retryAfter < 0 && e.has("retryAfter") && e.get("retryAfter").isJsonPrimitive()) {
                    try {
                        retryAfter = Math.max(0, (long) Math.ceil(e.get("retryAfter").getAsDouble()));
                    } catch (RuntimeException ignored) {
                        retryAfter = -1;
                    }
                }
            }
        }
        if (code != null && !code.matches("[a-z0-9_]{1,64}")) {
            code = null;
        }
        throw new WorkshopApiException(code, status, retryAfter, field, message == null ? "HTTP " + status : message);
    }

    private static String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : null;
    }

    /** {@code Retry-After}: delta seconds or an HTTP date; -1 when absent or unreadable. */
    static long parseRetryAfter(String value) {
        if (value == null || value.isBlank()) {
            return -1;
        }
        String v = value.trim();
        try {
            return Math.max(0, Long.parseLong(v));
        } catch (NumberFormatException ignored) {
            // Not delta seconds.
        }
        try {
            ZonedDateTime at = ZonedDateTime.parse(v, DateTimeFormatter.RFC_1123_DATE_TIME);
            return Math.max(0, Duration.between(ZonedDateTime.now(at.getZone()), at).getSeconds());
        } catch (RuntimeException ignored) {
            return -1;
        }
    }

    private static WorkshopApiException translate(IOException exception, CancelToken token) {
        if (exception instanceof WorkshopApiException api) {
            return api;
        }
        if (token.isCancelled()) {
            return new WorkshopApiException(WorkshopApiException.CANCELLED, "cancelled", exception);
        }
        if (exception instanceof SocketTimeoutException || exception instanceof InterruptedIOException) {
            return new WorkshopApiException(WorkshopApiException.TIMEOUT, "timed out", exception);
        }
        return new WorkshopApiException(WorkshopApiException.NETWORK_ERROR, "network error", exception);
    }

    static byte[] readLimited(InputStream in, long maxBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[16_384];
        long total = 0;
        int read;
        while ((read = in.read(buffer)) != -1) {
            total += read;
            if (total > maxBytes) {
                throw new WorkshopApiException(WorkshopApiException.TOO_LARGE, "response larger than " + maxBytes + " bytes");
            }
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    /**
     * Streams a same-origin file to {@code target} (created or truncated), hashing with SHA-256 while it arrives.
     * More than {@code maxBytes} (announced or received) aborts with {@link WorkshopApiException#TOO_LARGE}. A drive
     * that cannot hold the announced size is refused before the first byte is written, and a write that fails anyway
     * ends in {@link #DISK_FULL} or {@link #DISK_ERROR} rather than a network code. The partial file is left for the
     * caller to delete. No bearer token is sent.
     */
    public DownloadResult download(String url, Path target, long maxBytes, LongConsumer progress, CancelToken cancel)
            throws WorkshopApiException {
        CancelToken token = cancel == null ? CancelToken.none() : cancel;
        URI uri = resolve(url);
        HttpURLConnection connection = open("GET", uri, false, token);
        try {
            connection.setRequestProperty("Accept", "application/octet-stream, application/zip, audio/ogg");
            int status = connection.getResponseCode();
            checkStatus(status, connection.getHeaderField("Retry-After"), connection::getErrorStream, 256L * 1024);
            long declared = connection.getContentLengthLong();
            if (declared > maxBytes) {
                throw new WorkshopApiException(WorkshopApiException.TOO_LARGE, status, -1, null, "announced " + declared + " bytes");
            }
            // The server announces the size, so a hopeless download can be refused now instead of at 90 per cent.
            requireRoom(target, declared);
            MessageDigest digest = sha256();
            long total = 0;
            if (Files.isSymbolicLink(target)) {
                throw new WorkshopApiException(WorkshopApiException.BAD_URL, "download target is a symbolic link");
            }
            try (InputStream in = connection.getInputStream();
                 OutputStream out = openTarget(target)) {
                byte[] buffer = new byte[65_536];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    token.throwIfCancelled();
                    total += read;
                    if (total > maxBytes) {
                        throw new WorkshopApiException(WorkshopApiException.TOO_LARGE, status, -1, null, "more than " + maxBytes + " bytes");
                    }
                    digest.update(buffer, 0, read);
                    out.write(buffer, 0, read);
                    if (progress != null) {
                        progress.accept(total);
                    }
                }
            }
            token.throwIfCancelled();
            return new DownloadResult(target, total, HexFormat.of().formatHex(digest.digest()));
        } catch (IOException exception) {
            throw translate(exception, token);
        } finally {
            token.unbind();
            connection.disconnect();
        }
    }

    /**
     * Usable bytes on the drive that holds {@code path}, or -1 when the drive does not say. The path itself usually
     * does not exist yet, so this walks up to the first parent the file system knows; a store that reports no total
     * size reports nothing worth refusing a download over.
     */
    public static long usableSpace(Path path) {
        for (Path at = path == null ? null : path.toAbsolutePath(); at != null; at = at.getParent()) {
            try {
                FileStore store = Files.getFileStore(at);
                return store.getTotalSpace() <= 0 ? -1 : store.getUsableSpace();
            } catch (IOException | RuntimeException ignored) {
                // Most often this parent does not exist yet; the one above it will.
            }
        }
        return -1;
    }

    /**
     * Refuses with {@link #DISK_FULL} when the drive behind {@code path} plainly cannot hold {@code bytes} plus
     * {@link #DISK_MARGIN_BYTES}. A drive that says nothing about its size lets the download go ahead, because a
     * write failure then says more than a guess would.
     */
    public static void requireRoom(Path path, long bytes) throws WorkshopApiException {
        if (bytes <= 0) {
            return;
        }
        long needed = bytes + DISK_MARGIN_BYTES;
        long usable = usableSpace(path);
        if (usable >= 0 && usable < needed) {
            throw new WorkshopApiException(DISK_FULL, "needs " + needed + " bytes, the drive has " + usable);
        }
    }

    /**
     * The check a download queue makes before it fetches a set: a package is unpacked next to the archive it arrived
     * in and the archive is kept afterwards, so the drive has to hold the package twice over, not once.
     */
    public static void requireRoomForPackage(Path directory, long packageBytes) throws WorkshopApiException {
        requireRoom(directory, packageBytes > 0 && packageBytes < Long.MAX_VALUE / 2 ? packageBytes * 2 : packageBytes);
    }

    /** Opens the download file, counting a refusal to open it against the drive rather than the network. */
    private static OutputStream openTarget(Path target) throws WorkshopApiException {
        try {
            return new TargetFile(Files.newOutputStream(target, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS), target);
        } catch (IOException failure) {
            throw diskFailure(failure, target, 0);
        }
    }

    /**
     * Tells a drive with no room left from a folder that cannot be written at all, which the player has to hear
     * apart: one asks for space to be freed, the other for nothing the player can do twice. The platform spells
     * {@code ENOSPC} and an exceeded quota out in the message, and a drive that is full also reports less room left
     * than the chunk we were writing, while a read-only or locked folder reports plenty and another reason.
     */
    private static WorkshopApiException diskFailure(IOException failure, Path target, long wanted) {
        long usable = usableSpace(target);
        String reason = String.valueOf(failure.getMessage()).toLowerCase(Locale.ROOT);
        boolean full = reason.contains("no space") || reason.contains("quota")
                || (usable >= 0 && usable < Math.max(wanted, 1));
        return new WorkshopApiException(full ? DISK_FULL : DISK_ERROR,
                (full ? "no room left for " : "could not write ") + target.getFileName() + " (usable " + usable + " bytes)",
                failure);
    }

    /**
     * The file half of {@link #download}. Its failures are the drive's, not the socket's, and arrive as the same
     * {@link IOException}, so they are turned into {@link WorkshopApiException} here: {@link #translate} passes those
     * through untouched instead of reading them as a network error and telling the player to check a connection that
     * is working. {@code close} is wrapped too, since that is where a buffered last chunk fails.
     */
    private static final class TargetFile extends OutputStream {
        private final OutputStream out;
        private final Path path;

        TargetFile(OutputStream out, Path path) {
            this.out = out;
            this.path = path;
        }

        @Override
        public void write(int b) throws IOException {
            try {
                out.write(b);
            } catch (IOException failure) {
                throw diskFailure(failure, path, 1);
            }
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            try {
                out.write(bytes, offset, length);
            } catch (IOException failure) {
                throw diskFailure(failure, path, length);
            }
        }

        @Override
        public void flush() throws IOException {
            try {
                out.flush();
            } catch (IOException failure) {
                throw diskFailure(failure, path, 1);
            }
        }

        @Override
        public void close() throws IOException {
            try {
                out.close();
            } catch (IOException failure) {
                throw diskFailure(failure, path, 1);
            }
        }
    }

    /** A small same-origin file into memory (preview clips). No bearer token is sent. */
    public byte[] fetchBytes(String url, long maxBytes, CancelToken cancel) throws WorkshopApiException {
        return exchange("GET", resolve(url), null, null, Map.of("Accept", "*/*"), false, maxBytes, cancel).body();
    }

    static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    static boolean isSha256(String value) {
        return value != null && SHA256.matcher(value).matches();
    }

    // ================================================================ catalogue (02 section 2)

    /** Search parameters, 02 section 2.1. Null or empty values are left out. */
    public record SearchQuery(String q, String category, List<String> general, Integer genre, Integer language, String audio,
                              List<String> extra, Boolean nsfw, String sort, String cursor, Integer limit) {
        public static SearchQuery of(String q) {
            return new SearchQuery(q, null, null, null, null, null, null, null, null, null, null);
        }

        public SearchQuery withCursor(String value) {
            return new SearchQuery(q, category, general, genre, language, audio, extra, nsfw, sort, value, limit);
        }

        String toQueryString() {
            Map<String, String> params = new LinkedHashMap<>();
            put(params, "q", q);
            put(params, "s", category);
            put(params, "c", general == null || general.isEmpty() ? null : String.join(".", general));
            put(params, "g", genre == null ? null : String.valueOf(genre));
            put(params, "l", language == null ? null : String.valueOf(language));
            put(params, "a", audio);
            put(params, "e", extra == null || extra.isEmpty() ? null : String.join(".", extra));
            put(params, "nsfw", nsfw == null ? null : String.valueOf(nsfw));
            put(params, "sort", sort);
            put(params, "cursor", cursor);
            put(params, "limit", limit == null ? null : String.valueOf(Math.max(1, Math.min(50, limit))));
            StringBuilder out = new StringBuilder();
            for (Map.Entry<String, String> entry : params.entrySet()) {
                out.append(out.length() == 0 ? '?' : '&').append(entry.getKey()).append('=')
                        .append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
            }
            return out.toString();
        }

        private static void put(Map<String, String> params, String key, String value) {
            if (value != null && !value.isEmpty()) {
                params.put(key, value);
            }
        }
    }

    public SearchPage search(SearchQuery query, CancelToken cancel) throws WorkshopApiException {
        return WorkshopDtos.read(getJson("/api/v1/beatmapsets" + query.toQueryString(), true, cancel), SearchPage.class);
    }

    public SetDetail set(long setId, CancelToken cancel) throws WorkshopApiException {
        return WorkshopDtos.read(setJson(setId, cancel), SetDetail.class);
    }

    /** The raw set detail, for the offline card sidecar. */
    public JsonObject setJson(long setId, CancelToken cancel) throws WorkshopApiException {
        return getJson("/api/v1/beatmapsets/" + positive(setId), true, cancel);
    }

    public SetHistory history(long setId, CancelToken cancel) throws WorkshopApiException {
        return WorkshopDtos.read(getJson("/api/v1/beatmapsets/" + positive(setId) + "/history", true, cancel), SetHistory.class);
    }

    /** {@code POST /api/v1/beatmapsets/cards} (08 A5.5), at most 50 ids. */
    public List<SetCard> cards(List<Long> ids, CancelToken cancel) throws WorkshopApiException {
        JsonArray array = new JsonArray();
        ids.stream().limit(50).forEach(id -> array.add(new JsonPrimitive(id)));
        JsonObject body = new JsonObject();
        body.add("ids", array);
        JsonObject response = sendJson("POST", "/api/v1/beatmapsets/cards", body, true, cancel);
        return readList(response, "items", SetCard.class);
    }

    public DownloadTicket requestDownload(long setId, CancelToken cancel) throws WorkshopApiException {
        DownloadTicket ticket = WorkshopDtos.read(sendJson("POST", "/api/v1/beatmapsets/" + positive(setId) + "/download", null,
                true, cancel), DownloadTicket.class);
        if (ticket.url() == null || ticket.size() <= 0 || !isSha256(ticket.packageSha256()) || !isSha256(ticket.contentSha256())
                || ticket.revision() < 1) {
            throw new WorkshopApiException(WorkshopApiException.BAD_RESPONSE, "incomplete download ticket");
        }
        return ticket;
    }

    /** {@code POST /api/v1/beatmapsets/installed-check}, at most 1000 entries per call. */
    public List<InstalledResult> installedCheck(List<InstalledEntry> sets, CancelToken cancel) throws WorkshopApiException {
        JsonArray array = new JsonArray();
        for (InstalledEntry entry : sets.subList(0, Math.min(1000, sets.size()))) {
            JsonObject item = new JsonObject();
            item.addProperty("setId", entry.setId());
            item.addProperty("revision", entry.revision());
            item.addProperty("contentSha256", entry.contentSha256());
            array.add(item);
        }
        JsonObject body = new JsonObject();
        body.add("sets", array);
        return readList(sendJson("POST", "/api/v1/beatmapsets/installed-check", body, true, cancel), "results", InstalledResult.class);
    }

    /** {@code POST /api/v1/beatmapsets/lookup} by content hash, at most 100. */
    public List<LookupMatch> lookupContent(List<String> contentSha256, CancelToken cancel) throws WorkshopApiException {
        JsonArray array = new JsonArray();
        contentSha256.stream().limit(100).forEach(array::add);
        JsonObject body = new JsonObject();
        body.add("contentSha256", array);
        return readList(sendJson("POST", "/api/v1/beatmapsets/lookup", body, true, cancel), "matches", LookupMatch.class);
    }

    public FavouriteState favourite(long setId, boolean favourite, CancelToken cancel) throws WorkshopApiException {
        return WorkshopDtos.read(sendJson(favourite ? "PUT" : "DELETE", "/api/v1/beatmapsets/" + positive(setId) + "/favourite",
                null, true, cancel), FavouriteState.class);
    }

    public RatingSummary rating(long setId, CancelToken cancel) throws WorkshopApiException {
        return WorkshopDtos.read(getJson("/api/v1/beatmapsets/" + positive(setId) + "/rating", true, cancel), RatingSummary.class);
    }

    /** {@code rating} 1..10, or null to remove the rating. */
    public RatingSummary rate(long setId, Integer rating, CancelToken cancel) throws WorkshopApiException {
        JsonObject body = null;
        if (rating != null) {
            body = new JsonObject();
            body.addProperty("rating", Math.max(1, Math.min(10, rating)));
        }
        return WorkshopDtos.read(sendJson(rating == null ? "DELETE" : "PUT", "/api/v1/beatmapsets/" + positive(setId) + "/rating",
                body, true, cancel), RatingSummary.class);
    }

    public Meta meta(CancelToken cancel) throws WorkshopApiException {
        return WorkshopDtos.read(getJson("/api/v1/meta", false, cancel), Meta.class);
    }

    /**
     * One person's public profile: the picture, the roles, the day they joined and what they have published.
     * {@code user} is an id or a username; the server decides what a signed-out reader is allowed to see.
     */
    public WorkshopDtos.Profile profile(String user, CancelToken cancel) throws WorkshopApiException {
        String ref = URLEncoder.encode(user == null ? "" : user, StandardCharsets.UTF_8);
        return WorkshopDtos.read(getJson("/api/v1/users/" + ref, true, cancel), WorkshopDtos.Profile.class);
    }

    // ================================================================ comments (04 section 2)

    public CommentsPage comments(String commentableType, long commentableId, long parentId, String sort, String cursor,
                                 CancelToken cancel) throws WorkshopApiException {
        StringBuilder path = new StringBuilder("/api/v1/comments?commentableType=")
                .append(URLEncoder.encode(commentableType, StandardCharsets.UTF_8))
                .append("&commentableId=").append(positive(commentableId))
                .append("&parentId=").append(Math.max(0, parentId));
        if (sort != null) {
            path.append("&sort=").append(URLEncoder.encode(sort, StandardCharsets.UTF_8));
        }
        if (cursor != null) {
            path.append("&cursor=").append(URLEncoder.encode(cursor, StandardCharsets.UTF_8));
        }
        return WorkshopDtos.read(getJson(path.toString(), true, cancel), CommentsPage.class);
    }

    /** {@code POST /api/v1/comments}, parsed. */
    public WorkshopDtos.CommentMutation comment(long setId, Long parentId, String body, CancelToken cancel) throws WorkshopApiException {
        return WorkshopDtos.read(postComment("beatmapset", setId, parentId, body, cancel), WorkshopDtos.CommentMutation.class);
    }

    /** {@code DELETE /api/v1/comments/{id}} (own comment; staff use the website). */
    public WorkshopDtos.CommentMutation deleteComment(long commentId, CancelToken cancel) throws WorkshopApiException {
        return WorkshopDtos.read(sendJson("DELETE", "/api/v1/comments/" + positive(commentId), null, true, cancel),
                WorkshopDtos.CommentMutation.class);
    }

    /** {@code POST|DELETE /api/v1/comments/{id}/vote}. */
    public WorkshopDtos.VoteResult voteComment(long commentId, boolean vote, CancelToken cancel) throws WorkshopApiException {
        return WorkshopDtos.read(sendJson(vote ? "POST" : "DELETE", "/api/v1/comments/" + positive(commentId) + "/vote", null, true, cancel),
                WorkshopDtos.VoteResult.class);
    }

    /**
     * {@code POST /api/v1/reports {targetType, targetId, reason, details}} (04 section 6.1). {@code targetType}:
     * beatmapset, comment or user.
     */
    public JsonObject report(String targetType, long targetId, String reason, String details, CancelToken cancel)
            throws WorkshopApiException {
        JsonObject body = new JsonObject();
        body.addProperty("targetType", targetType);
        body.addProperty("targetId", positive(targetId));
        body.addProperty("reason", reason);
        if (details != null && !details.isBlank()) {
            body.addProperty("details", details);
        }
        return sendJson("POST", "/api/v1/reports", body, true, cancel);
    }

    /** {@code GET /api/v1/notifications?cursor&limit} (signed in). */
    public WorkshopDtos.NotificationsPage notifications(String cursor, int limit, CancelToken cancel) throws WorkshopApiException {
        StringBuilder path = new StringBuilder("/api/v1/notifications?limit=").append(Math.max(1, Math.min(50, limit)));
        if (cursor != null && !cursor.isEmpty()) {
            path.append("&cursor=").append(URLEncoder.encode(cursor, StandardCharsets.UTF_8));
        }
        return WorkshopDtos.read(getJson(path.toString(), true, cancel), WorkshopDtos.NotificationsPage.class);
    }

    /** {@code POST /api/v1/notifications/read {all: true}}. */
    public void markAllNotificationsRead(CancelToken cancel) throws WorkshopApiException {
        JsonObject body = new JsonObject();
        body.addProperty("all", true);
        sendJson("POST", "/api/v1/notifications/read", body, true, cancel);
    }

    public JsonObject postComment(String commentableType, long commentableId, Long parentId, String body, CancelToken cancel)
            throws WorkshopApiException {
        JsonObject json = new JsonObject();
        json.addProperty("commentableType", commentableType);
        json.addProperty("commentableId", commentableId);
        if (parentId != null && parentId > 0) {
            json.addProperty("parentId", parentId);
        }
        json.addProperty("body", body);
        return sendJson("POST", "/api/v1/comments", json, true, cancel);
    }

    // ================================================================ account and auth (02 section 4)

    public Me me(CancelToken cancel) throws WorkshopApiException {
        return WorkshopDtos.read(getJson("/api/v1/me", true, cancel), Me.class);
    }

    /**
     * {@code POST /api/v1/me/rules-acceptance}: the one-time acceptance of the Workshop Terms and the Upload Rules.
     * Every confirmation of the panel is sent explicitly, because the server records which ones were ticked as the
     * evidence of the acceptance; sending them all is only valid when the player really ticked them all in the wizard.
     */
    public WorkshopDtos.Rules acceptRules(String kind, String termsVersion, String rulesVersion, List<String> confirmations,
                                         CancelToken cancel) throws WorkshopApiException {
        JsonObject body = new JsonObject();
        body.addProperty("kind", kind);
        body.addProperty("termsVersion", termsVersion);
        body.addProperty("rulesVersion", rulesVersion);
        JsonObject ticked = new JsonObject();
        for (String key : confirmations) {
            ticked.addProperty(key, true);
        }
        body.add("confirmations", ticked);
        JsonElement answer = sendJson("POST", "/api/v1/me/rules-acceptance", body, true, cancel);
        JsonElement rules = answer != null && answer.isJsonObject() ? answer.getAsJsonObject().get("rules") : null;
        return WorkshopDtos.read(rules, WorkshopDtos.Rules.class);
    }

    /** {@code POST /api/v1/auth/device {clientLabel, remember}}. */
    public DeviceCodes startDevice(String clientLabel, boolean remember, CancelToken cancel) throws WorkshopApiException {
        JsonObject body = new JsonObject();
        body.addProperty("clientLabel", clientLabel);
        body.addProperty("remember", remember);
        DeviceCodes codes = deviceCodes(sendJson("POST", "/api/v1/auth/device", body, false, cancel));
        startedDeviceCode.set(codes.deviceCode());
        return codes;
    }

    public AuthResult pollDevice(String deviceCode, CancelToken cancel) throws WorkshopApiException {
        JsonObject body = new JsonObject();
        body.addProperty("deviceCode", deviceCode);
        return WorkshopDtos.read(sendJson("POST", "/api/v1/auth/device/token", body, false, cancel), AuthResult.class);
    }

    /**
     * {@code POST /api/v1/auth/device/cancel {deviceCode}}: the player gave up on a code sign-in. Until the service
     * hears this, the code keeps one of the few waiting slots its address range is allowed, so two friends behind
     * one connection who each open and close the sign-in could lock both of them out. The answer is always 204,
     * whether or not the code was still waiting.
     */
    public void cancelDevice(String deviceCode, CancelToken cancel) throws WorkshopApiException {
        JsonObject body = new JsonObject();
        body.addProperty("deviceCode", deviceCode);
        sendJson("POST", "/api/v1/auth/device/cancel", body, false, cancel);
    }

    /**
     * Reports the last code sign-in started through {@link #startDevice} as abandoned, at most once; does nothing when
     * there is none. A code that was already approved, refused or expired is left as it was by the service, so a
     * cancel racing a sign-in that has just gone through costs nothing.
     */
    public void cancelStartedDevice(CancelToken cancel) throws WorkshopApiException {
        String code = startedDeviceCode.getAndSet(null);
        if (code != null) {
            cancelDevice(code, cancel);
        }
    }

    public AuthResult refresh(String refreshToken, CancelToken cancel) throws WorkshopApiException {
        JsonObject body = new JsonObject();
        body.addProperty("refreshToken", refreshToken);
        return WorkshopDtos.read(sendJson("POST", "/api/v1/auth/refresh", body, false, cancel), AuthResult.class);
    }

    /** {@code POST /api/v1/auth/revoke}: with the bearer (current family) and, when given, a token to revoke. */
    public void revoke(String token, CancelToken cancel) throws WorkshopApiException {
        JsonObject body = new JsonObject();
        if (token != null) {
            body.addProperty("token", token);
        }
        sendJson("POST", "/api/v1/auth/revoke", body, true, cancel);
    }

    public Challenge mcChallenge(String purpose, String mcUuid, String mcName, CancelToken cancel) throws WorkshopApiException {
        JsonObject body = new JsonObject();
        body.addProperty("purpose", purpose);
        body.addProperty("mcUuid", mcUuid);
        body.addProperty("mcName", mcName);
        Challenge challenge = WorkshopDtos.read(sendJson("POST", "/api/v1/mc/challenges", body, false, cancel), Challenge.class);
        if (challenge.challengeId() == null || !challenge.challengeId().matches("ch_[a-z2-7]{24}")) {
            throw new WorkshopApiException(WorkshopApiException.BAD_RESPONSE, "malformed challenge id");
        }
        return challenge;
    }

    public AuthResult mcVerify(String challengeId, String clientSaltB64, String deviceLabel, CancelToken cancel)
            throws WorkshopApiException {
        if (challengeId == null || !challengeId.matches("ch_[a-z2-7]{24}")) {
            throw new WorkshopApiException(WorkshopApiException.BAD_RESPONSE, "malformed challenge id");
        }
        JsonObject body = new JsonObject();
        body.addProperty("clientSalt", clientSaltB64);
        if (deviceLabel != null) {
            body.addProperty("deviceLabel", deviceLabel);
        }
        return WorkshopDtos.read(sendJson("POST", "/api/v1/mc/challenges/" + challengeId + "/verify", body, false, cancel),
                AuthResult.class);
    }

    public DeviceCodes mcLinkRequest(String mcProofId, String clientLabel, CancelToken cancel) throws WorkshopApiException {
        JsonObject body = new JsonObject();
        body.addProperty("mcProofId", mcProofId);
        body.addProperty("clientLabel", clientLabel);
        return deviceCodes(sendJson("POST", "/api/v1/mc/link-requests", body, false, cancel));
    }

    /** Polls a link request; {@code confirm} is sent only after the player confirmed in game. */
    public AuthResult mcLinkToken(String deviceCode, boolean confirm, CancelToken cancel) throws WorkshopApiException {
        JsonObject body = new JsonObject();
        body.addProperty("deviceCode", deviceCode);
        if (confirm) {
            body.addProperty("confirm", true);
        }
        return WorkshopDtos.read(sendJson("POST", "/api/v1/mc/link-requests/token", body, false, cancel), AuthResult.class);
    }

    /** {@code DELETE /api/v1/me/minecraft/{uuid}} with an {@code mc_session} token for that UUID. */
    public void unlinkMinecraft(String uuid, CancelToken cancel) throws WorkshopApiException {
        if (uuid == null || !uuid.matches("[0-9a-fA-F-]{32,36}")) {
            throw new WorkshopApiException(WorkshopApiException.BAD_URL, "malformed uuid");
        }
        sendJson("DELETE", "/api/v1/me/minecraft/" + uuid, null, true, cancel);
    }

    private static DeviceCodes deviceCodes(JsonObject json) throws WorkshopApiException {
        DeviceCodes codes = WorkshopDtos.read(json, DeviceCodes.class);
        if (codes.deviceCode() == null || codes.userCode() == null || codes.expiresIn() <= 0) {
            throw new WorkshopApiException(WorkshopApiException.BAD_RESPONSE, "incomplete device codes");
        }
        return codes;
    }

    // ================================================================ uploads (02 section 3)

    public Upload createUpload(Long setId, CancelToken cancel) throws WorkshopApiException {
        JsonObject body = new JsonObject();
        if (setId != null) {
            body.addProperty("setId", setId);
        }
        return readUpload(sendJson("POST", "/api/v1/uploads", body, true, cancel));
    }

    public Upload upload(String uploadId, CancelToken cancel) throws WorkshopApiException {
        return readUpload(getJson("/api/v1/uploads/" + uploadId(uploadId), true, cancel));
    }

    public Upload patchUpload(String uploadId, JsonObject metadata, CancelToken cancel) throws WorkshopApiException {
        JsonObject body = new JsonObject();
        body.add("metadata", metadata);
        return readUpload(sendJson("PATCH", "/api/v1/uploads/" + uploadId(uploadId), body, true, cancel));
    }

    /** {@code GET /api/v1/uploads}: the caller's uploads still in progress (drafts). */
    public List<Upload> uploads(CancelToken cancel) throws WorkshopApiException {
        return readList(getJson("/api/v1/uploads", true, cancel), "items", Upload.class);
    }

    /** Upload answers are {@code {upload: {...}}} on the server; older fakes answer with the upload itself. */
    private static Upload readUpload(JsonObject response) throws WorkshopApiException {
        if (response != null && response.has("upload") && response.get("upload").isJsonObject()) {
            return WorkshopDtos.read(response.getAsJsonObject("upload"), Upload.class);
        }
        return WorkshopDtos.read(response, Upload.class);
    }

    /** Uploads a slot body from memory; files above a few MiB should use {@link #putSlotFile}. */
    public JsonObject putSlot(String uploadId, String slot, byte[] bytes, String contentType, String fileName, CancelToken cancel)
            throws WorkshopApiException {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("X-File-Name", URLEncoder.encode(fileName == null ? "file" : fileName, StandardCharsets.UTF_8).replace("+", "%20"));
        RawResponse response = exchange("PUT", resolve("/api/v1/uploads/" + uploadId(uploadId) + "/slots/"
                + URLEncoder.encode(slot, StandardCharsets.UTF_8)), bytes, contentType, headers, true, MAX_JSON_BYTES, cancel);
        return parseJsonBody(response);
    }

    /**
     * Streams a slot body from disk with byte progress ({@code progress} receives bytes sent so far, from the calling
     * thread). Same origin, bearer and error rules as every other call; the body is never held in memory.
     */
    public JsonObject putSlotFile(String uploadId, String slot, Path file, String contentType, LongConsumer progress,
                                  CancelToken cancel) throws WorkshopApiException {
        CancelToken token = cancel == null ? CancelToken.none() : cancel;
        URI uri = resolve("/api/v1/uploads/" + uploadId(uploadId) + "/slots/" + URLEncoder.encode(slot, StandardCharsets.UTF_8));
        long size;
        try {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                throw new WorkshopApiException(WorkshopApiException.NETWORK_ERROR, "not a readable file");
            }
            size = Files.size(file);
        } catch (IOException exception) {
            if (exception instanceof WorkshopApiException api) {
                throw api;
            }
            throw new WorkshopApiException(WorkshopApiException.NETWORK_ERROR, "could not read the file", exception);
        }
        HttpURLConnection connection = open("PUT", uri, true, token);
        try {
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", contentType);
            connection.setRequestProperty("X-File-Name",
                    URLEncoder.encode(String.valueOf(file.getFileName()), StandardCharsets.UTF_8).replace("+", "%20"));
            connection.setFixedLengthStreamingMode(size);
            long sent = 0;
            try (InputStream in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS); OutputStream out = connection.getOutputStream()) {
                byte[] buffer = new byte[65_536];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    token.throwIfCancelled();
                    out.write(buffer, 0, read);
                    sent += read;
                    if (progress != null) {
                        progress.accept(sent);
                    }
                }
            }
            int status = connection.getResponseCode();
            checkStatus(status, connection.getHeaderField("Retry-After"), connection::getErrorStream, MAX_JSON_BYTES);
            byte[] bytes;
            try (InputStream in = connection.getInputStream()) {
                bytes = readLimited(in, MAX_JSON_BYTES);
            }
            token.throwIfCancelled();
            return parseJsonBody(new RawResponse(status, connection.getHeaderFields(), bytes));
        } catch (IOException exception) {
            throw translate(exception, token);
        } finally {
            token.unbind();
            connection.disconnect();
        }
    }

    public JsonObject putSlotFile(String uploadId, String slot, Path file, String contentType, CancelToken cancel)
            throws WorkshopApiException {
        try {
            return putSlot(uploadId, slot, Files.readAllBytes(file), contentType, String.valueOf(file.getFileName()), cancel);
        } catch (IOException exception) {
            if (exception instanceof WorkshopApiException api) {
                throw api;
            }
            throw new WorkshopApiException(WorkshopApiException.NETWORK_ERROR, "could not read the file", exception);
        }
    }

    public JsonObject submitUpload(String uploadId, String termsVersion, CancelToken cancel) throws WorkshopApiException {
        JsonObject body = new JsonObject();
        body.addProperty("termsVersion", termsVersion);
        body.addProperty("confirmRights", true);
        body.addProperty("confirmAge", true);
        return sendJson("POST", "/api/v1/uploads/" + uploadId(uploadId) + "/submit", body, true, cancel);
    }

    public void cancelUpload(String uploadId, CancelToken cancel) throws WorkshopApiException {
        sendJson("DELETE", "/api/v1/uploads/" + uploadId(uploadId), null, true, cancel);
    }

    private static String uploadId(String id) throws WorkshopApiException {
        if (id == null || !id.matches("up_[A-Za-z0-9_-]{1,64}")) {
            throw new WorkshopApiException(WorkshopApiException.BAD_URL, "malformed upload id");
        }
        return id;
    }

    private static long positive(long id) throws WorkshopApiException {
        if (id <= 0) {
            throw new WorkshopApiException(WorkshopApiException.BAD_URL, "id must be positive");
        }
        return id;
    }

    private static <T> List<T> readList(JsonObject response, String key, Class<T> type) throws WorkshopApiException {
        if (response == null || !response.has(key) || !response.get(key).isJsonArray()) {
            throw new WorkshopApiException(WorkshopApiException.BAD_RESPONSE, "missing " + key);
        }
        List<T> out = new ArrayList<>();
        for (JsonElement element : response.getAsJsonArray(key)) {
            out.add(WorkshopDtos.read(element, type));
        }
        return out;
    }
}

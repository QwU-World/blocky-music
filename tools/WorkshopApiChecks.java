import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import ru.gora.musicbattle.workshop.BeatmapPackage;
import ru.gora.musicbattle.workshop.ServerIdHash;
import ru.gora.musicbattle.workshop.WorkshopCatalog;
import ru.gora.musicbattle.workshop.WorkshopLayout;
import ru.gora.musicbattle.workshop.api.CancelToken;
import ru.gora.musicbattle.workshop.api.InstalledCheck;
import ru.gora.musicbattle.workshop.api.MinecraftSignIn;
import ru.gora.musicbattle.workshop.api.RefreshTokenStore;
import ru.gora.musicbattle.workshop.api.MediaUrl;
import ru.gora.musicbattle.workshop.api.WorkshopApi;
import ru.gora.musicbattle.workshop.api.WorkshopApiException;
import ru.gora.musicbattle.workshop.api.WorkshopConfig;
import ru.gora.musicbattle.workshop.api.WorkshopDownloader;
import ru.gora.musicbattle.workshop.api.WorkshopDtos;
import ru.gora.musicbattle.workshop.api.WorkshopInbox;
import ru.gora.musicbattle.workshop.api.WorkshopSession;

/**
 * Workshop HTTP client, downloader, session storage, consent and the end-of-stream rule a package transfer shares
 * with the server, against a local JDK HttpServer fake ({@code workshop-api}). No qwu.world, no
 * Mojang, no Minecraft classes. What it does not prove: real TLS, real proxies, the game's executors and screens.
 */
public final class WorkshopApiChecks {
    private static int checks;
    private static final String ACCESS = "bmw_at_" + "A".repeat(43);
    private static final String REFRESH_1 = "bmw_rt_" + "B".repeat(43);
    private static final String REFRESH_2 = "bmw_rt_" + "C".repeat(43);
    /** Stands in for the game's Minecraft access token: it must never reach the workshop. */
    private static final String MC_TOKEN = "mc-access-token-SENTINEL-7f3a";

    public static void main(String[] args) throws Exception {
        Path temp = Files.createTempDirectory("blocky-workshop-api-");
        try {
            baseUrls();
            mediaUrls();
            consentGateAndProxy(temp);
            schemaAndErrors();
            dtoMapping();
            patchTransport();
        uploadAnswersWithNulls();
            transferChunking();
            downloads(temp.resolve("downloads"));
            diskSpace(temp.resolve("disk"));
            installedCheck(temp.resolve("installed-check"));
            sessionFile(temp.resolve("session"));
            minecraftTokenNeverSent(temp.resolve("mc"));
            configFile(temp.resolve("config"));
            inbox(temp.resolve("inbox"));
        } finally {
            deleteTree(temp);
        }
        System.out.println("Workshop API checks passed: " + checks);
    }

    /**
     * Covers and previews arrive as site paths and have to be joined to the Workshop origin before anything can
     * fetch them. Getting this wrong is silent: the screens simply draw no cover, which is how it shipped once.
     */
    static void mediaUrls() {
        String origin = "https://blocky-music.qwu.world";
        check("https://blocky-music.qwu.world/files/covers/abc/card@1x.jpg"
                        .equals(MediaUrl.absolute(origin, "/files/covers/abc/card@1x.jpg")),
                "a cover path is joined to the workshop origin");
        check("https://blocky-music.qwu.world/files/previews/abc-38700.ogg"
                        .equals(MediaUrl.absolute(origin, "/files/previews/abc-38700.ogg")),
                "a preview path is joined to the workshop origin");
        check("https://qwu.world/avatars/3-bee3a7f6ae9a.jpg"
                        .equals(MediaUrl.absolute(origin, "https://qwu.world/avatars/3-bee3a7f6ae9a.jpg")),
                "an account picture on qwu.world is passed through untouched");
        check("https://cdn.qwu.world/avatars/3.jpg".equals(MediaUrl.absolute(origin, "https://cdn.qwu.world/avatars/3.jpg")),
                "an account picture on a qwu.world subdomain is fetched");
        check("https://blocky-music.qwu.world/files/a.jpg".equals(MediaUrl.absolute(origin, "https://blocky-music.qwu.world/files/a.jpg")),
                "an absolute address on the Workshop origin is fetched");
        check("http://127.0.0.1:8787/files/a.jpg".equals(MediaUrl.absolute("http://127.0.0.1:8787", "http://127.0.0.1:8787/files/a.jpg")),
                "a test origin's own absolute addresses are fetched");
        // Off-host account pictures (consent-change M1): the first-run screen names qwu.world and no other host, so an
        // avatar_url anywhere else draws the placeholder rather than being fetched.
        for (String offHost : new String[] {"https://evil.example/avatar.png", "http://qwu.world/avatars/3.jpg",
                "https://qwu.world.evil.example/a.jpg", "https://evilqwu.world/a.jpg", "https://user@qwu.world/a.jpg",
                "https://user@evil.example/a.jpg", "ftp://qwu.world/a.jpg", "javascript:alert(1)", "data:image/png;base64,AAAA",
                "https://127.0.0.1:8787/files/a.jpg", "http://127.0.0.1:8788/files/a.jpg"}) {
            String origin2 = offHost.contains("127.0.0.1") ? "http://127.0.0.1:8787" : origin;
            check(MediaUrl.absolute(origin2, offHost) == null, "an account picture off QwU World's hosts is not fetched: " + offHost);
        }
        check(MediaUrl.absolute(origin + "/", "/files/covers/abc/card@1x.jpg").equals(
                        "https://blocky-music.qwu.world/files/covers/abc/card@1x.jpg"),
                "a trailing slash on the origin does not double up");
        check(MediaUrl.absolute(origin, "//evil.example/x.jpg") == null,
                "a protocol-relative address is refused, not joined");
        check(MediaUrl.absolute(null, "/files/covers/abc/card@1x.jpg") == null,
                "without an origin a path is not guessed at");
        check(MediaUrl.absolute(origin, null) == null && MediaUrl.absolute(origin, "  ") == null,
                "nothing to fetch stays nothing");
    }

    // ---------------------------------------------------------------- helpers

    static void check(boolean ok, String label) {
        checks++;
        if (!ok) {
            throw new AssertionError(label);
        }
    }

    static WorkshopApi api(FakeWorkshopServer server, boolean consent) {
        return new WorkshopApi(WorkshopApi.Options.of(URI.create(server.base()), () -> Proxy.NO_PROXY, "BlockyMusic/test (Minecraft 1.21.1; NeoForge test; Test OS)",
                () -> consent).withTimeouts(2_000, 3_000));
    }

    static WorkshopApiException expectError(String label, ThrowingRunnable action) {
        try {
            action.run();
        } catch (WorkshopApiException exception) {
            return exception;
        } catch (Exception exception) {
            throw new AssertionError(label + ": unexpected " + exception, exception);
        }
        throw new AssertionError(label + ": no error");
    }

    @FunctionalInterface
    interface ThrowingRunnable {
        void run() throws Exception;
    }

    static void waitFor(String label, java.util.function.BooleanSupplier condition, long timeoutMs) throws InterruptedException {
        long end = System.currentTimeMillis() + timeoutMs;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > end) {
                throw new AssertionError("timed out waiting: " + label);
            }
            Thread.sleep(10);
        }
    }

    static String error(String code) {
        return "{\"schema\":1,\"error\":{\"code\":\"" + code + "\",\"message\":\"x\"}}";
    }

    // ---------------------------------------------------------------- base URL

    static void baseUrls() {
        check(WorkshopApi.parseBaseUrl("https://blocky-music.qwu.world").toString().equals("https://blocky-music.qwu.world"), "https base");
        check(WorkshopApi.parseBaseUrl("https://blocky-music.qwu.world/").toString().equals("https://blocky-music.qwu.world"), "trailing slash");
        check(WorkshopApi.parseBaseUrl("http://127.0.0.1:4455").toString().equals("http://127.0.0.1:4455"), "loopback http");
        for (String bad : List.of("http://blocky-music.qwu.world", "https://x.qwu.world/api", "https://u:p@qwu.world", "ftp://qwu.world",
                "https://qwu.world?x=1", "notaurl")) {
            try {
                WorkshopApi.parseBaseUrl(bad);
                throw new AssertionError("accepted base " + bad);
            } catch (IllegalArgumentException expected) {
                checks++;
            }
        }
        String previous = System.getProperty(WorkshopApi.BASE_URL_PROPERTY);
        try {
            System.clearProperty(WorkshopApi.BASE_URL_PROPERTY);
            check(WorkshopApi.configuredBaseUri().toString().equals(WorkshopApi.DEFAULT_BASE_URL), "default base URL");
            System.setProperty(WorkshopApi.BASE_URL_PROPERTY, "http://127.0.0.1:9999");
            check(WorkshopApi.configuredBaseUri().toString().equals("http://127.0.0.1:9999"), "system property override");
            System.setProperty(WorkshopApi.BASE_URL_PROPERTY, "http://evil.example");
            check(WorkshopApi.configuredBaseUri().toString().equals(WorkshopApi.DEFAULT_BASE_URL), "invalid override falls back to production");
        } finally {
            if (previous == null) {
                System.clearProperty(WorkshopApi.BASE_URL_PROPERTY);
            } else {
                System.setProperty(WorkshopApi.BASE_URL_PROPERTY, previous);
            }
        }
    }

    // ---------------------------------------------------------------- consent and proxy

    static void consentGateAndProxy(Path temp) throws Exception {
        try (FakeWorkshopServer server = new FakeWorkshopServer()) {
            server.on("GET", "/api/v1/meta", r -> FakeWorkshopServer.Response.json(200, "{\"schema\":1,\"genres\":[],\"languages\":[]}"));
            WorkshopApi offline = api(server, false);
            WorkshopApiException refused = expectError("no consent", () -> offline.meta(CancelToken.none()));
            check(refused.code().equals(WorkshopApiException.CONSENT_REQUIRED), "without consent: consent_required");
            expectError("no consent download", () -> offline.download("/files/x", temp.resolve("x.part"), 10, null, CancelToken.none()));
            check(server.snapshot().isEmpty(), "without consent nothing reaches the server");

            Proxy socks = new Proxy(Proxy.Type.SOCKS, InetSocketAddress.createUnresolved("proxy.invalid", 1080));
            AtomicReference<Proxy> seen = new AtomicReference<>();
            WorkshopApi proxied = new WorkshopApi(WorkshopApi.Options.of(URI.create(server.base()), () -> socks, "BlockyMusic/test", () -> true)
                    .withOpener((URL url, Proxy proxy) -> {
                        seen.set(proxy);
                        return url.openConnection(Proxy.NO_PROXY);
                    }));
            proxied.meta(CancelToken.none());
            check(seen.get() == socks, "the game's proxy object is passed to openConnection");
            FakeWorkshopServer.Request request = server.requests("GET", "/api/v1/meta").get(0);
            check("BlockyMusic/test".equals(request.header("User-Agent")), "User-Agent is sent");
            check(request.header("Authorization") == null, "no Authorization without a session");
        }
    }

    // ---------------------------------------------------------------- schema and error mapping

    static void schemaAndErrors() throws Exception {
        try (FakeWorkshopServer server = new FakeWorkshopServer()) {
            WorkshopApi api = api(server, true);
            server.on("GET", "/api/v1/beatmapsets/1", r -> FakeWorkshopServer.Response.json(200, "{\"schema\":2,\"id\":1}"));
            server.on("GET", "/api/v1/beatmapsets/2", r -> FakeWorkshopServer.Response.json(200, "{\"id\":2}"));
            server.on("GET", "/api/v1/beatmapsets/3", r -> FakeWorkshopServer.Response.json(404, "{\"schema\":3,\"error\":{\"code\":\"not_found\"}}"));
            check(expectError("schema 2", () -> api.set(1, CancelToken.none())).code().equals(WorkshopApiException.SCHEMA_MISMATCH), "schema 2 refused");
            check(expectError("no schema", () -> api.set(2, CancelToken.none())).code().equals(WorkshopApiException.SCHEMA_MISMATCH), "missing schema refused");
            check(expectError("error schema 3", () -> api.set(3, CancelToken.none())).code().equals(WorkshopApiException.SCHEMA_MISMATCH),
                    "error body with another schema refused");

            int[][] statuses = {{401, 11}, {403, 12}, {404, 13}, {410, 14}, {429, 15}, {503, 16}};
            String[] codes = {"token_expired", "forbidden", "not_found", "unavailable", "rate_limited", "maintenance"};
            for (int i = 0; i < statuses.length; i++) {
                int status = statuses[i][0];
                long id = statuses[i][1];
                String code = codes[i];
                String[] headers = status == 429 ? new String[]{"Retry-After", "17"} : new String[0];
                String body = status == 503 ? "{\"schema\":1,\"error\":{\"code\":\"maintenance\",\"message\":\"m\",\"retryAfter\":30}}" : error(code);
                server.on("GET", "/api/v1/beatmapsets/" + id, r -> FakeWorkshopServer.Response.json(status, body, headers));
                WorkshopApiException e = expectError("status " + status, () -> api.set(id, CancelToken.none()));
                check(e.status() == status && e.code().equals(code), "status " + status + " maps to " + code + " (got " + e.code() + ")");
                check(e.translationKey().equals("music_battle.workshop.error." + code), "translation key for " + code);
                if (status == 429) {
                    check(e.retryAfterSeconds() == 17, "429 Retry-After header read");
                }
                if (status == 503) {
                    check(e.retryAfterSeconds() == 30, "503 retryAfter from the body");
                }
            }
            server.on("GET", "/api/v1/beatmapsets/20", r -> new FakeWorkshopServer.Response(502, Map.of("Content-Type", "text/html"),
                    "<html>bad gateway</html>".getBytes(StandardCharsets.UTF_8), 0, 0, -1));
            check(expectError("502", () -> api.set(20, CancelToken.none())).code().equals(WorkshopApiException.SERVER_ERROR), "bare 502 → server_error");
            server.on("GET", "/api/v1/beatmapsets/21", r -> new FakeWorkshopServer.Response(302, Map.of("Location", server.base() + "/api/v1/redirected"),
                    new byte[0], 0, 0, -1));
            check(expectError("302", () -> api.set(21, CancelToken.none())).code().equals(WorkshopApiException.UNEXPECTED_REDIRECT), "redirect refused");
            check(server.requests("GET", "/api/v1/redirected").isEmpty(), "redirect not followed");
            server.on("GET", "/api/v1/beatmapsets/22", r -> FakeWorkshopServer.Response.json(429, error("rate_limited"), "Retry-After", "Wed, 21 Oct 2099 07:28:00 GMT"));
            check(expectError("429 date", () -> api.set(22, CancelToken.none())).retryAfterSeconds() > 1_000_000, "Retry-After HTTP date read");

            check(expectError("foreign url", () -> api.fetchBytes("https://evil.example/files/x", 100, CancelToken.none())).code()
                    .equals(WorkshopApiException.BAD_URL), "absolute URL on another origin refused");
            check(expectError("protocol-relative", () -> api.fetchBytes("//evil.example/files/x", 100, CancelToken.none())).code()
                    .equals(WorkshopApiException.BAD_URL), "protocol-relative URL refused");

            server.on("GET", "/api/v1/beatmapsets/23", r -> FakeWorkshopServer.Response.json(200, "{\"schema\":1,\"id\":23}").slow(4, 400));
            CancelToken cancel = new CancelToken();
            new Thread(() -> {
                try {
                    Thread.sleep(150);
                } catch (InterruptedException ignored) {
                }
                cancel.cancel();
            }).start();
            long started = System.currentTimeMillis();
            check(expectError("cancel json", () -> api.set(23, cancel)).code().equals(WorkshopApiException.CANCELLED), "a JSON call can be cancelled");
            check(System.currentTimeMillis() - started < 2_500, "cancel returns promptly");
        }
    }

    // ---------------------------------------------------------------- DTOs

    static void dtoMapping() throws Exception {
        String card = WorkshopFixtures.detailJson(42, 3, "a".repeat(64), "/files/previews/abc-60000.ogg");
        WorkshopDtos.SetDetail detail = WorkshopDtos.read(JsonParser.parseString(card), WorkshopDtos.SetDetail.class);
        check(detail.id() == 42 && detail.revision() == 3 && detail.mapper().username().equals("dragon4ik"), "detail core fields");
        check(detail.charts().get(0).starsRight() == null && detail.charts().get(0).stars() == 2.5, "nullable chart stars");
        check(detail.rating() == null && detail.covers() == null && detail.preview().ogg().endsWith(".ogg"), "nullable card parts");
        check(detail.previewStartMs() == 60000 && "own_set".equals(detail.cannot().get("rate")), "detail extras");
        WorkshopDtos.InstalledResult result = WorkshopDtos.read(JsonParser.parseString(
                "{\"setId\":5,\"state\":\"withdrawn\",\"latestRevision\":null,\"availability\":\"disabled\",\"reason\":\"copyright\"}"),
                WorkshopDtos.InstalledResult.class);
        check(result.latestRevision() == null && result.state().equals("withdrawn"), "installed-check result with null revision");
        WorkshopDtos.AuthResult grant = WorkshopDtos.read(JsonParser.parseString("{\"status\":\"signed_in\",\"accessToken\":\"" + ACCESS
                + "\",\"refreshToken\":\"" + REFRESH_1 + "\",\"expiresIn\":3600,\"account\":{\"id\":17,\"username\":\"u\"}}"), WorkshopDtos.AuthResult.class);
        check(!grant.toString().contains(ACCESS) && !grant.toString().contains(REFRESH_1), "AuthResult.toString redacts tokens");
        WorkshopDtos.DeviceCodes codes = new WorkshopDtos.DeviceCodes("bmw_dc_secret", "BDFG-HJKL", "u", "u?code", 600, 5);
        check(!codes.toString().contains("bmw_dc_secret"), "DeviceCodes.toString hides the device code");
        check(!new RefreshTokenStore.Stored(1, REFRESH_1, "").toString().contains(REFRESH_1), "Stored.toString redacts the refresh token");
        // Terms section 3.1: /me carries the two acceptances side by side, and an older server carries neither.
        WorkshopDtos.Me me = WorkshopDtos.read(JsonParser.parseString("{\"account\":{\"id\":17,\"username\":\"u\"},"
                + "\"rules\":{\"accepted\":true,\"acceptedAt\":\"2026-09-10T10:00:00Z\",\"termsVersion\":\"2026-09-01\","
                + "\"rulesVersion\":\"2\",\"needsReaccept\":false},"
                + "\"participation\":{\"accepted\":true,\"acceptedAt\":\"2026-09-10T10:00:00Z\",\"termsVersion\":\"2026-09-01\","
                + "\"rulesVersion\":\"1\",\"needsReaccept\":true,\"changes\":\"Community Rules 2\"}}"), WorkshopDtos.Me.class);
        check(me.rules().accepted() && !me.rules().pending(), "the uploader's acceptance is read from /me");
        check(me.participation().accepted() && me.participation().pending()
                && "Community Rules 2".equals(me.participation().changes()),
                "a participation acceptance given for an older version is still owed");
        WorkshopDtos.Me bare = WorkshopDtos.read(JsonParser.parseString("{\"account\":{\"id\":17}}"), WorkshopDtos.Me.class);
        check(bare.rules() == null && bare.participation() == null, "a server that says nothing about the rules leaves both null");
        WorkshopApi.SearchQuery query = new WorkshopApi.SearchQuery("stars>5 neuro", "featured", List.of("library"), 7, null, "included",
                List.of("fx", "twoside"), false, "rating_desc", null, 80);
        try (FakeWorkshopServer server = new FakeWorkshopServer()) {
            server.on("POST", "/api/v1/me/rules-acceptance", r -> FakeWorkshopServer.Response.json(200,
                    "{\"schema\":1,\"rules\":{\"accepted\":true,\"acceptedAt\":\"2026-09-18T09:00:00Z\","
                            + "\"termsVersion\":\"2026-09-01\",\"rulesVersion\":\"2\",\"needsReaccept\":false}}"));
            WorkshopApi accepting = api(server, true);
            WorkshopSession accepted = new WorkshopSession(accepting,
                    new RefreshTokenStore(Files.createTempDirectory("bm-accept").resolve("auth.json")), System::currentTimeMillis);
            accepted.accept(grant(ACCESS, null, 3600), WorkshopSession.Kind.DEVICE, false);
            WorkshopDtos.Rules given = accepted.authorized(a -> a.acceptRules("participation", "2026-09-01", "2",
                    List.of("terms", "age"), CancelToken.none()));
            check(given.accepted() && !given.pending(), "the acceptance answer says the rules are given");
            String body = server.requests("POST", "/api/v1/me/rules-acceptance").get(0).bodyText();
            check(body.contains("\"kind\":\"participation\"") && body.contains("\"terms\":true") && body.contains("\"age\":true"),
                    "the acceptance records which boxes were ticked: " + body);
        }
        try (FakeWorkshopServer server = new FakeWorkshopServer()) {
            server.on("GET", "/api/v1/beatmapsets", r -> FakeWorkshopServer.Response.json(200, "{\"schema\":1,\"items\":[],\"total\":0,\"cursor\":null}"));
            WorkshopDtos.SearchPage page = api(server, true).search(query, CancelToken.none());
            check(page.items().isEmpty() && page.cursor() == null, "empty search page");
            String sent = server.requests("GET", "/api/v1/beatmapsets").get(0).query();
            check(sent.contains("q=stars%3E5+neuro") && sent.contains("s=featured") && sent.contains("c=library") && sent.contains("g=7")
                    && sent.contains("e=fx.twoside") && sent.contains("nsfw=false") && sent.contains("limit=50") && !sent.contains("l="),
                    "search parameters as the website reads them: " + sent);
        }
    }

    // ---------------------------------------------------------------- PATCH

    static void patchTransport() throws Exception {
        try (FakeWorkshopServer server = new FakeWorkshopServer()) {
            server.on("PATCH", "/api/v1/uploads/up_abc", r -> FakeWorkshopServer.Response.json(200,
                    "{\"schema\":1,\"id\":\"up_abc\",\"status\":\"open\",\"validation\":{\"ok\":true,\"errors\":[],\"warnings\":[]}}"));
            WorkshopApi api = api(server, true);
            WorkshopSession session = new WorkshopSession(api, new RefreshTokenStore(Files.createTempDirectory("bm-patch").resolve("auth.json")), System::currentTimeMillis);
            session.accept(grant(ACCESS, null, 3600), WorkshopSession.Kind.DEVICE, false);
            JsonObject metadata = new JsonObject();
            metadata.addProperty("title", "T");
            WorkshopDtos.Upload upload = session.authorized(a -> a.patchUpload("up_abc", metadata, CancelToken.none()));
            check(upload.validation().ok(), "PATCH answer parsed");
            FakeWorkshopServer.Request request = server.requests("PATCH", "/api/v1/uploads/up_abc").get(0);
            check(("Bearer " + ACCESS).equals(request.header("Authorization")) && request.bodyText().contains("\"title\":\"T\""), "PATCH carries bearer and body");

            Proxy socks = new Proxy(Proxy.Type.SOCKS, InetSocketAddress.createUnresolved("proxy.invalid", 1080));
            WorkshopApi viaSocks = new WorkshopApi(WorkshopApi.Options.of(URI.create(server.base()), () -> socks, "BlockyMusic/test", () -> true));
            int before = server.snapshot().size();
            check(expectError("patch socks", () -> viaSocks.patchUpload("up_abc", metadata, CancelToken.none())).code().equals("proxy_unsupported"),
                    "PATCH refuses to bypass a SOCKS proxy");
            check(server.snapshot().size() == before, "no direct connection when the proxy is SOCKS");
        }
    }

    /**
     * The service's answer to POST /api/v1/uploads for a new set, as src/uploads/store.ts writes it: "current" and
     * "result" are null, and a slot's "probe" may be null. Gson refused a JSON null for a JsonObject component, so every
     * first upload from the game ended in "unexpected answer" (found by a hidden upload against a local copy of the
     * service). A component that is neither null nor an object is still refused.
     */
    static void uploadAnswersWithNulls() throws Exception {
        String answer = "{\"schema\":1,\"upload\":{\"id\":\"up_new\",\"setId\":null,\"status\":\"open\",\"stage\":null,"
                + "\"errorCode\":null,\"limits\":{\"audioBytes\":67108864,\"coverBytes\":12582912,\"chartBytes\":2097152,"
                + "\"charts\":16,\"durationMs\":[10000,900000]},\"slots\":[{\"slot\":\"audio\",\"size\":5,\"sha256\":null,"
                + "\"contentType\":\"audio/ogg\",\"fileName\":\"a.ogg\",\"probe\":null}],\"metadata\":{\"title\":\"\","
                + "\"previewStartMs\":null},\"validation\":{\"ok\":false,\"errors\":[{\"code\":\"title_required\","
                + "\"field\":\"title\"}],\"warnings\":[],\"reviewRequired\":false,\"audioDurationMs\":null},"
                + "\"current\":null,\"result\":null}}";
        try (FakeWorkshopServer server = new FakeWorkshopServer()) {
            server.on("POST", "/api/v1/uploads", r -> FakeWorkshopServer.Response.json(201, answer));
            server.on("PATCH", "/api/v1/uploads/up_new", r -> FakeWorkshopServer.Response.json(200, answer));
            WorkshopApi api = api(server, true);
            WorkshopDtos.Upload created = api.createUpload(null, CancelToken.none());
            check("up_new".equals(created.id()) && created.current() == null && created.result() == null
                    && created.slots().get(0).probe() == null && created.metadata().has("previewStartMs")
                    && created.metadata().get("previewStartMs").isJsonNull(),
                    "a new set's upload answer with null current/result/probe is read; metadata keeps its own nulls");
            WorkshopDtos.Upload patched = api.patchUpload("up_new", new JsonObject(), CancelToken.none());
            check(patched.current() == null && !patched.validation().ok(), "the PATCH answer with nulls is read too");
        }
        try (FakeWorkshopServer server = new FakeWorkshopServer()) {
            server.on("POST", "/api/v1/uploads", r -> FakeWorkshopServer.Response.json(201,
                    "{\"schema\":1,\"upload\":{\"id\":\"up_bad\",\"status\":\"open\",\"current\":\"not an object\"}}"));
            check(expectError("upload with a string current", () -> api(server, true).createUpload(null, CancelToken.none()))
                    .code().equals(WorkshopApiException.BAD_RESPONSE), "an object component that is a string is still refused");
        }
    }

    static WorkshopDtos.AuthResult grant(String access, String refresh, long expiresIn) {
        return new WorkshopDtos.AuthResult("signed_in", access, "Bearer", expiresIn, refresh, refresh == null ? null : 2592000L, List.of("upload"),
                new WorkshopDtos.AccountSummary(17, "dragon4ik", "Dragon4ik", null), null, null, null, null);
    }

    // ---------------------------------------------------------------- the end of a package stream

    /**
     * An "Add to server" offer announces its size, so the server counts bytes; a vote cannot, because the voter
     * repacks the package only once the server asks for it. There the end of the stream is the first chunk that is
     * not full, which a package of whole chunks does not have: it needs an empty chunk after it. Without that marker
     * such a vote never finished - it was dropped when the voting closed, and the voter's one transfer slot stayed
     * taken for the rest of the session.
     */
    static void transferChunking() {
        int chunk = 24 * 1024;
        check(WorkshopCatalog.endsTransfer(chunk - 1, chunk) && WorkshopCatalog.endsTransfer(0, chunk),
                "a chunk that is not full ends the stream");
        check(!WorkshopCatalog.endsTransfer(chunk, chunk), "a full chunk is never the end");
        check(WorkshopCatalog.needsEndChunk(chunk, chunk) && WorkshopCatalog.needsEndChunk(2L * chunk, chunk),
                "a package of whole chunks needs an empty chunk after it");
        check(!WorkshopCatalog.needsEndChunk(chunk + 1, chunk) && !WorkshopCatalog.needsEndChunk(chunk - 1, chunk),
                "a package whose last chunk is short ends itself");
        check(!WorkshopCatalog.endsTransfer(chunk, 0) && !WorkshopCatalog.needsEndChunk(chunk, 0),
                "without a chunk size there is nothing to conclude");
    }

    // ---------------------------------------------------------------- downloads

    static void downloads(Path root) throws Exception {
        Files.createDirectories(root);
        try (FakeWorkshopServer server = new FakeWorkshopServer()) {
            WorkshopApi api = api(server, true);
            WorkshopSession session = new WorkshopSession(api, new RefreshTokenStore(root.resolve("auth").resolve("auth.json")), System::currentTimeMillis);
            session.accept(grant(ACCESS, null, 3600), WorkshopSession.Kind.DEVICE, false);
            WorkshopCatalog catalog = new WorkshopCatalog(root.resolve("music_battle_tracks"));
            InstalledCheck installedCheck = new InstalledCheck(api, catalog, System::currentTimeMillis);
            ExecutorService network = Executors.newFixedThreadPool(2);
            ExecutorService install = Executors.newSingleThreadExecutor();
            AtomicLong clock = new AtomicLong(System.currentTimeMillis());
            WorkshopDownloader downloader = new WorkshopDownloader(api, catalog, installedCheck, network, install, clock::get);
            Map<Long, WorkshopDownloader.Status> last = new ConcurrentHashMap<>();
            Map<Long, String> failures = new ConcurrentHashMap<>();
            Set<Long> installed = ConcurrentHashMap.newKeySet();
            Map<Long, Boolean> playableWhenInstalled = new ConcurrentHashMap<>();
            Map<Long, Integer> cancellations = new ConcurrentHashMap<>();
            AtomicInteger concurrent = new AtomicInteger();
            AtomicInteger maxConcurrent = new AtomicInteger();
            downloader.addListener(new WorkshopDownloader.Listener() {
                @Override
                public void statusChanged(long setId, WorkshopDownloader.Status status) {
                    last.put(setId, status);
                }

                @Override
                public void installed(long setId, ru.gora.musicbattle.workshop.BeatmapManifest manifest, BeatmapPackage.Outcome outcome) {
                    // What the game can do with the set at the moment it is told the download finished. Both halves
                    // matter: the catalogue is what a battle lists, and the installed-check verdict is what lets the
                    // map travel to a server. Asked here rather than afterwards, because "afterwards" is
                    // the wait this order exists to remove.
                    playableWhenInstalled.put(setId, catalog.isInstalled(setId)
                            && installedCheck.transferVerdict(setId, manifest.contentSha256())
                                    == InstalledCheck.TransferVerdict.ALLOWED);
                    installed.add(setId);
                }

                @Override
                public void failed(long setId, String code, long retryAfterSeconds) {
                    failures.put(setId, code + "/" + retryAfterSeconds);
                }

                @Override
                public void cancelled(long setId) {
                    cancellations.merge(setId, 1, Integer::sum);
                }
            });
            try {
                // --- success, with sidecars and a kept package
                WorkshopFixtures.Built set42 = WorkshopFixtures.build(42, 1, "Test Song");
                byte[] previewOgg = WorkshopFixtures.ogg(44100, 44100);
                serveSet(server, 42, set42, "/files/packages/42/1.blockymap", set42.packageSha256(), set42.bytes(), false);
                server.on("GET", "/api/v1/beatmapsets/42", r -> FakeWorkshopServer.Response.json(200,
                        WorkshopFixtures.detailJson(42, 1, set42.manifest().contentSha256(), "/files/previews/abc-60000.ogg")));
                server.on("GET", "/files/previews/abc-60000.ogg", r -> FakeWorkshopServer.Response.bytes(200, previewOgg));
                downloader.enqueue(42);
                // Both callbacks, because they are published one after the other and the assertions below read what
                // each of them left behind.
                waitFor("set 42 installed", () -> last.containsKey(42L) && last.get(42L).state() == WorkshopDownloader.State.INSTALLED
                        && installed.contains(42L), 15_000);
                check(installed.contains(42L), "installed listener fired");
                check(Boolean.TRUE.equals(playableWhenInstalled.get(42L)),
                        "INSTALLED means playable already: in the catalogue and allowed to travel");
                check(catalog.isInstalled(42, 1, set42.manifest().contentSha256()), "catalog shows the downloaded set");
                check(catalog.byContentSha256(set42.manifest().contentSha256()).isPresent(), "hash index finds the set");
                check(InstalledCheck.STATE_CURRENT.equals(installedCheck.entry(42).orElseThrow().state())
                                && server.requests("POST", "/api/v1/beatmapsets/installed-check").isEmpty(),
                        "the ticket answers the installed-check for this set, without a request of its own");
                Path setDir = WorkshopLayout.installedDirectory(catalog.tracksRoot()).resolve("ws_42");
                // The card and the preview clip are two more round trips to the site and the set does not wait for
                // them, but they do have to arrive, and the catalogue has to read them once they do.
                waitFor("set 42 sidecars", () -> catalog.bySetId(42).map(set -> set.card() != null && set.previewPresent()).orElse(false), 15_000);
                JsonObject card = JsonParser.parseString(Files.readString(setDir.resolve(WorkshopLayout.CARD_SIDECAR_FILE_NAME))).getAsJsonObject();
                check(card.get("id").getAsLong() == 42 && card.has("fetchedAt") && card.has("charts") && card.has("previewStartMs"),
                        "workshop-card.json holds the detail and fetchedAt");
                check(!card.has("viewer") && !card.has("can") && !card.has("cannot") && !card.has("schema"), "sidecar drops viewer-specific fields");
                check(java.util.Arrays.equals(Files.readAllBytes(setDir.resolve(WorkshopLayout.PREVIEW_SIDECAR_FILE_NAME)), previewOgg), "preview.ogg saved");
                check(Files.isRegularFile(downloader.keptPackage(42, 1)), "package kept in .workshop_packages");
                check(listFiles(downloader.downloadsDirectory()).isEmpty(), "no .part left after install");
                for (FakeWorkshopServer.Request r : server.snapshot()) {
                    if (r.path().startsWith("/files/")) {
                        check(r.header("Authorization") == null, "no bearer on file requests: " + r.path());
                    }
                }
                check(("Bearer " + ACCESS).equals(server.requests("POST", "/api/v1/beatmapsets/42/download").get(0).header("Authorization")),
                        "bearer on the API download request");

                // --- bad SHA
                WorkshopFixtures.Built set43 = WorkshopFixtures.build(43, 1, "Bad Hash");
                serveSet(server, 43, set43, "/files/packages/43/1.blockymap", "0".repeat(64), set43.bytes(), false);
                downloader.enqueue(43);
                waitFor("43 failed", () -> failures.containsKey(43L), 15_000);
                check(failures.get(43L).startsWith(WorkshopApiException.HASH_MISMATCH), "bad package SHA → hash_mismatch");
                check(!catalog.isInstalled(43) && downloader.status(43).state() == WorkshopDownloader.State.FAILED, "bad SHA not installed");
                check(listFiles(downloader.downloadsDirectory()).isEmpty(), "bad SHA .part deleted");

                // --- oversize stream (no Content-Length)
                WorkshopFixtures.Built set44 = WorkshopFixtures.build(44, 1, "Oversize");
                byte[] huge = new byte[set44.bytes().length + (int) WorkshopDownloader.SIZE_SLACK_BYTES + 200_000];
                server.on("POST", "/api/v1/beatmapsets/44/download", r -> FakeWorkshopServer.Response.json(200, ticket("/files/packages/44/1.blockymap",
                        set44.bytes().length, set44.packageSha256(), set44.manifest().contentSha256(), 1)));
                server.on("GET", "/files/packages/44/1.blockymap", r -> FakeWorkshopServer.Response.bytes(200, huge).declared(0));
                downloader.enqueue(44);
                waitFor("44 failed", () -> failures.containsKey(44L), 15_000);
                check(failures.get(44L).startsWith(WorkshopApiException.TOO_LARGE), "stream beyond size + 1 MiB → too_large");
                check(listFiles(downloader.downloadsDirectory()).isEmpty(), "oversize .part deleted");

                // --- oversize announced
                WorkshopFixtures.Built set45 = WorkshopFixtures.build(45, 1, "Announced");
                server.on("POST", "/api/v1/beatmapsets/45/download", r -> FakeWorkshopServer.Response.json(200, ticket("/files/packages/45/1.blockymap",
                        set45.bytes().length, set45.packageSha256(), set45.manifest().contentSha256(), 1)));
                server.on("GET", "/files/packages/45/1.blockymap", r -> FakeWorkshopServer.Response.bytes(200, huge));
                downloader.enqueue(45);
                waitFor("45 failed", () -> failures.containsKey(45L), 15_000);
                check(failures.get(45L).startsWith(WorkshopApiException.TOO_LARGE), "announced Content-Length beyond the cap → too_large");

                // --- size mismatch (short body)
                WorkshopFixtures.Built set46 = WorkshopFixtures.build(46, 1, "Short");
                server.on("POST", "/api/v1/beatmapsets/46/download", r -> FakeWorkshopServer.Response.json(200, ticket("/files/packages/46/1.blockymap",
                        set46.bytes().length + 10, set46.packageSha256(), set46.manifest().contentSha256(), 1)));
                server.on("GET", "/files/packages/46/1.blockymap", r -> FakeWorkshopServer.Response.bytes(200, set46.bytes()));
                downloader.enqueue(46);
                waitFor("46 failed", () -> failures.containsKey(46L), 15_000);
                check(failures.get(46L).startsWith(WorkshopApiException.SIZE_MISMATCH), "short body → size_mismatch");

                // --- manifest mismatch
                WorkshopFixtures.Built set47 = WorkshopFixtures.build(47, 1, "Mismatch");
                server.on("POST", "/api/v1/beatmapsets/47/download", r -> FakeWorkshopServer.Response.json(200, ticket("/files/packages/47/1.blockymap",
                        set47.bytes().length, set47.packageSha256(), "f".repeat(64), 1)));
                server.on("GET", "/files/packages/47/1.blockymap", r -> FakeWorkshopServer.Response.bytes(200, set47.bytes()));
                downloader.enqueue(47);
                waitFor("47 failed", () -> failures.containsKey(47L), 15_000);
                check(failures.get(47L).startsWith(WorkshopApiException.MANIFEST_MISMATCH) && !catalog.isInstalled(47), "package for another content → manifest_mismatch");

                // --- 429 and 410
                server.on("POST", "/api/v1/beatmapsets/48/download", r -> FakeWorkshopServer.Response.json(429, error("rate_limited"), "Retry-After", "120"));
                downloader.enqueue(48);
                waitFor("48 failed", () -> failures.containsKey(48L), 15_000);
                check(failures.get(48L).equals("rate_limited/120"), "429 → rate_limited with Retry-After for the toast");
                check(downloader.status(48).state() == WorkshopDownloader.State.FAILED && downloader.status(48).retryAfterSeconds() == 120, "429 status");
                server.on("POST", "/api/v1/beatmapsets/49/download", r -> FakeWorkshopServer.Response.json(410,
                        "{\"schema\":1,\"error\":{\"code\":\"unavailable\",\"message\":\"x\"},\"availabilityInfo\":{\"state\":\"disabled\"}}"));
                downloader.enqueue(49);
                waitFor("49 failed", () -> failures.containsKey(49L), 15_000);
                check(downloader.status(49).state() == WorkshopDownloader.State.UNAVAILABLE, "410 → UNAVAILABLE");

                // --- download URL on another origin
                server.on("POST", "/api/v1/beatmapsets/50/download", r -> FakeWorkshopServer.Response.json(200, ticket("https://evil.example/files/packages/50/1.blockymap",
                        1000, "a".repeat(64), "b".repeat(64), 1)));
                downloader.enqueue(50);
                waitFor("50 failed", () -> failures.containsKey(50L), 15_000);
                check(failures.get(50L).startsWith(WorkshopApiException.BAD_URL), "foreign download URL refused");

                // --- cancel mid-download
                WorkshopFixtures.Built set51 = WorkshopFixtures.build(51, 1, "Slow");
                byte[] slowBody = new byte[3 * 1024 * 1024];
                System.arraycopy(set51.bytes(), 0, slowBody, 0, set51.bytes().length);
                server.on("POST", "/api/v1/beatmapsets/51/download", r -> FakeWorkshopServer.Response.json(200, ticket("/files/packages/51/1.blockymap",
                        slowBody.length, WorkshopFixtures.sha256(slowBody), set51.manifest().contentSha256(), 1)));
                server.on("GET", "/files/packages/51/1.blockymap", r -> FakeWorkshopServer.Response.bytes(200, slowBody).slow(64 * 1024, 40));
                downloader.enqueue(51);
                waitFor("51 downloading", () -> {
                    WorkshopDownloader.Status s = last.get(51L);
                    return s != null && s.state() == WorkshopDownloader.State.DOWNLOADING && s.receivedBytes() > 0;
                }, 15_000);
                check(Files.exists(root.resolve("music_battle_tracks").resolve(WorkshopLayout.DOWNLOADS_DIRECTORY_NAME).resolve("51-1.part")), ".part exists while downloading");
                downloader.cancel(51);
                waitFor("51 cancelled", () -> !downloader.active(51), 10_000);
                check(downloader.status(51).state() == WorkshopDownloader.State.NOT_INSTALLED, "cancel returns to NOT_INSTALLED");
                check(!failures.containsKey(51L), "cancel is not reported as a failure");
                // The one report a cancellation makes. An install request from the site holds its file until the
                // download it started ends, and a cancellation is one of those endings.
                waitFor("51 reported cancelled", () -> cancellations.containsKey(51L), 5_000);
                check(cancellations.get(51L) == 1, "a cancelled download reports cancelled exactly once");
                waitFor("51 part deleted", () -> listFiles(downloader.downloadsDirectory()).isEmpty(), 5_000);
                check(!catalog.isInstalled(51), "cancelled set not installed");

                // --- at most two downloads at once
                for (long id = 60; id < 64; id++) {
                    WorkshopFixtures.Built built = WorkshopFixtures.build(id, 1, "Parallel " + id);
                    long setId = id;
                    server.on("POST", "/api/v1/beatmapsets/" + id + "/download", r -> FakeWorkshopServer.Response.json(200, ticket("/files/packages/" + setId + "/1.blockymap",
                            built.bytes().length, built.packageSha256(), built.manifest().contentSha256(), 1)));
                    server.on("GET", "/files/packages/" + id + "/1.blockymap", r -> {
                        int now = concurrent.incrementAndGet();
                        maxConcurrent.accumulateAndGet(now, Math::max);
                        try {
                            Thread.sleep(250);
                        } finally {
                            concurrent.decrementAndGet();
                        }
                        return FakeWorkshopServer.Response.bytes(200, built.bytes());
                    });
                    server.on("GET", "/api/v1/beatmapsets/" + id, r -> FakeWorkshopServer.Response.json(404, error("not_found")));
                }
                for (long id = 60; id < 64; id++) {
                    downloader.enqueue(id);
                    downloader.enqueue(id);
                }
                waitFor("parallel installed", () -> catalog.isInstalled(60) && catalog.isInstalled(61) && catalog.isInstalled(62) && catalog.isInstalled(63), 20_000);
                check(maxConcurrent.get() <= WorkshopDownloader.MAX_CONCURRENT && maxConcurrent.get() >= 1, "at most two downloads at once (max " + maxConcurrent.get() + ")");
                check(server.requests("POST", "/api/v1/beatmapsets/60/download").size() == 1, "one job per set (enqueue twice → one download)");
                check(Files.notExists(WorkshopLayout.installedDirectory(catalog.tracksRoot()).resolve("ws_60").resolve(WorkshopLayout.CARD_SIDECAR_FILE_NAME)),
                        "no sidecar when the detail is unavailable");

                // --- revision update replaces the kept package and prunes old ones
                WorkshopFixtures.Built set42r2 = WorkshopFixtures.build(42, 2, "Test Song");
                serveSet(server, 42, set42r2, "/files/packages/42/2.blockymap", set42r2.packageSha256(), set42r2.bytes(), true);
                server.on("GET", "/api/v1/beatmapsets/42", r -> FakeWorkshopServer.Response.json(200,
                        WorkshopFixtures.detailJson(42, 2, set42r2.manifest().contentSha256(), null)));
                last.remove(42L);
                downloader.enqueue(42);
                waitFor("42 r2", () -> catalog.isInstalled(42, 2, set42r2.manifest().contentSha256()), 15_000);
                check(Files.isRegularFile(downloader.keptPackage(42, 2)) && Files.notExists(downloader.keptPackage(42, 1)), "older kept revision removed");
                Path stale = downloader.packagesDirectory().resolve("7-1.blockymap");
                Files.write(stale, new byte[]{1});
                Files.setLastModifiedTime(stale, FileTime.fromMillis(clock.get() - 8L * 24 * 3600 * 1000));
                Path fresh = downloader.packagesDirectory().resolve("8-1.blockymap");
                Files.write(fresh, new byte[]{1});
                downloader.prunePackages();
                check(Files.notExists(stale) && Files.exists(fresh), "packages older than 7 days pruned, recent kept");
                Path leftover = downloader.downloadsDirectory().resolve("99-1.part");
                Files.write(leftover, new byte[]{1});
                downloader.cleanStaleParts();
                check(Files.notExists(leftover), "stale .part from a crash removed");
            } finally {
                network.shutdownNow();
                install.shutdownNow();
            }
        }
    }

    static void serveSet(FakeWorkshopServer server, long setId, WorkshopFixtures.Built built, String path, String ticketSha, byte[] body,
                         boolean replaceRoute) {
        server.on("POST", "/api/v1/beatmapsets/" + setId + "/download", r -> FakeWorkshopServer.Response.json(200,
                ticket(path, body.length, ticketSha, built.manifest().contentSha256(), built.manifest().revision())));
        server.on("GET", path, r -> FakeWorkshopServer.Response.bytes(200, body));
    }

    static String ticket(String url, long size, String packageSha, String contentSha, int revision) {
        return "{\"schema\":1,\"url\":\"" + url + "?exp=1&sig=x\",\"size\":" + size + ",\"packageSha256\":\"" + packageSha
                + "\",\"contentSha256\":\"" + contentSha + "\",\"revision\":" + revision + ",\"fileName\":\"x.blockymap\",\"expiresAt\":\"2026-09-17T10:05:00Z\"}";
    }

    static List<Path> listFiles(Path dir) {
        try (var stream = Files.list(dir)) {
            return stream.toList();
        } catch (IOException exception) {
            return List.of();
        }
    }

    // ---------------------------------------------------------------- the drive, not the network

    /**
     * A download reads from a socket and writes to a drive, and the JDK throws the same {@link IOException} for
     * both. Reported as one, a full disk told the player to check a connection that was working, which is wrong and
     * no help either; the write side has codes of its own, and a package that plainly will not fit is refused before
     * the first byte rather than at ninety per cent.
     */
    static void diskSpace(Path root) throws Exception {
        Files.createDirectories(root);
        check(WorkshopApi.usableSpace(root.resolve("not").resolve("there").resolve("x.part")) > 0,
                "free space is read from the first parent folder that exists");
        check(WorkshopApi.usableSpace(Path.of("")) >= -1, "an empty path answers something, never throws");
        WorkshopApiException refused = expectError("no drive is that big",
                () -> WorkshopApi.requireRoom(root.resolve("x.part"), Long.MAX_VALUE / 4));
        check(refused.code().equals(WorkshopApi.DISK_FULL), "a package no drive could hold is refused as disk_full");
        boolean allowed = true;
        try {
            WorkshopApi.requireRoom(root.resolve("x.part"), 4096);
            WorkshopApi.requireRoomForPackage(root, 4096);
            WorkshopApi.requireRoom(root, 0);
        } catch (WorkshopApiException unexpected) {
            allowed = false;
        }
        check(allowed, "a few kilobytes, and an unannounced size, are not refused");
        long usable = WorkshopApi.usableSpace(root);
        if (usable > 1L << 30) {
            // A package is kept as an archive and unpacked next to it, so the drive has to hold it twice over. The
            // slack keeps another process writing a few megabytes meanwhile from deciding this.
            long onceButNotTwice = usable - WorkshopApi.DISK_MARGIN_BYTES - (64L << 20);
            WorkshopApi.requireRoom(root, onceButNotTwice);
            WorkshopApiException tooMuch = expectError("twice over",
                    () -> WorkshopApi.requireRoomForPackage(root, onceButNotTwice));
            check(tooMuch.code().equals(WorkshopApi.DISK_FULL), "a package that fits once but not unpacked as well is refused");
        }
        Path notADirectory = root.resolve("blocked");
        Files.write(notADirectory, new byte[]{1});
        try (FakeWorkshopServer server = new FakeWorkshopServer()) {
            server.on("GET", "/files/packages/70/1.blockymap", r -> FakeWorkshopServer.Response.bytes(200, new byte[2048]));
            WorkshopApi api = api(server, true);
            WorkshopApiException write = expectError("unwritable target", () -> api.download("/files/packages/70/1.blockymap",
                    notADirectory.resolve("x.part"), 1L << 20, null, CancelToken.none()));
            check(write.code().equals(WorkshopApi.DISK_ERROR), "a target that cannot be written is disk_error, not a network code");
        }
    }

    // ---------------------------------------------------------------- installed-check

    static void installedCheck(Path root) throws Exception {
        Path tracks = root.resolve("music_battle_tracks");
        for (long id : new long[]{1, 2, 3}) {
            Path archive = WorkshopFixtures.write(WorkshopFixtures.build(id, 1, "Set " + id), root.resolve(id + ".blockymap"));
            BeatmapPackage.install(archive, tracks);
        }
        WorkshopCatalog catalog = new WorkshopCatalog(tracks);
        catalog.refresh();
        check(catalog.size() == 3, "three sets installed for installed-check");
        try (FakeWorkshopServer server = new FakeWorkshopServer()) {
            AtomicLong clock = new AtomicLong(1_000_000_000_000L);
            String answer = "{\"schema\":1,\"results\":[{\"setId\":1,\"state\":\"current\",\"latestRevision\":1,\"availability\":\"ok\",\"reason\":null},"
                    + "{\"setId\":2,\"state\":\"update_available\",\"latestRevision\":3,\"availability\":\"ok\",\"reason\":null},"
                    + "{\"setId\":3,\"state\":\"withdrawn\",\"latestRevision\":null,\"availability\":\"disabled\",\"reason\":\"copyright\"}]}";
            server.on("POST", "/api/v1/beatmapsets/installed-check", r -> FakeWorkshopServer.Response.json(200, answer));
            WorkshopApi api = api(server, true);
            InstalledCheck check = new InstalledCheck(api, catalog, clock::get);
            check(check.maybeRun(CancelToken.none()), "first run requests");
            check(!check.maybeRun(CancelToken.none()), "second run within 10 minutes skipped");
            clock.addAndGet(InstalledCheck.MIN_INTERVAL_MS + 1);
            check(check.maybeRun(CancelToken.none()), "run again after 10 minutes");
            check(server.requests("POST", "/api/v1/beatmapsets/installed-check").size() == 2, "exactly two installed-check requests");
            String body = server.requests("POST", "/api/v1/beatmapsets/installed-check").get(0).bodyText();
            check(body.contains("\"setId\":1") && body.contains(catalog.bySetId(1).orElseThrow().contentSha256()), "request lists (setId, revision, contentSha256)");
            check(check.entry(2).orElseThrow().updateAvailable() && check.entry(3).orElseThrow().withdrawn(), "states remembered");
            String sha1 = catalog.bySetId(1).orElseThrow().contentSha256();
            check(check.transferVerdict(1, sha1) == InstalledCheck.TransferVerdict.ALLOWED, "current + fresh → transfer allowed");
            check(check.transferVerdict(3, catalog.bySetId(3).orElseThrow().contentSha256()) == InstalledCheck.TransferVerdict.WITHDRAWN, "withdrawn → no transfer");
            check(check.transferVerdict(1, "0".repeat(64)) == InstalledCheck.TransferVerdict.CHECK_FIRST, "other content → check first");

            // A map that arrived from a file, a friend or the inbox has no answer at all, and a battle leaves it out
            // until it gets one. The game tells the player to open the Workshop for that, so the ten-minute gate has
            // to let the sets it has never asked about through, or the hint is a lie for ten minutes.
            BeatmapPackage.install(WorkshopFixtures.write(WorkshopFixtures.build(4, 1, "Set 4"), root.resolve("4.blockymap")), tracks);
            catalog.refresh();
            check(check.maybeRun(CancelToken.none()), "a set installed since the last answer is checked at once");
            List<FakeWorkshopServer.Request> asked = server.requests("POST", "/api/v1/beatmapsets/installed-check");
            check(asked.size() == 3 && asked.get(2).bodyText().contains("\"setId\":4"), "that request asks about the new set");
            check(!check.maybeRun(CancelToken.none()), "with nothing new installed the ten minutes still hold");

            // The downloader's own path needs no request at all: a download ticket already said which revision and
            // which content the workshop served, seconds ago, which is everything an answer carries.
            String sha4 = catalog.bySetId(4).orElseThrow().contentSha256();
            check(check.transferVerdict(4, sha4) == InstalledCheck.TransferVerdict.CHECK_FIRST,
                    "a set the workshop said nothing about may not travel");
            check.rememberCurrent(4, 1, sha4);
            check(check.transferVerdict(4, sha4) == InstalledCheck.TransferVerdict.ALLOWED,
                    "the ticket of a just-finished download makes the set playable at once");
            check(check.transferVerdict(4, "0".repeat(64)) == InstalledCheck.TransferVerdict.CHECK_FIRST,
                    "and only for the content that was downloaded");
            check(server.requests("POST", "/api/v1/beatmapsets/installed-check").size() == 3,
                    "remembering the ticket asks the workshop nothing");

            // An attempt that failed answered nothing, so it must not hold the gate shut for ten minutes either:
            // the player is told they can fix this by opening the Workshop, and the next open has to really ask.
            clock.addAndGet(InstalledCheck.MIN_INTERVAL_MS + 1);
            server.on("POST", "/api/v1/beatmapsets/installed-check", r -> FakeWorkshopServer.Response.json(500, error("internal_error")));
            expectError("the workshop is having trouble", () -> check.maybeRun(CancelToken.none()));
            server.on("POST", "/api/v1/beatmapsets/installed-check", r -> FakeWorkshopServer.Response.json(200, answer));
            check(check.maybeRun(CancelToken.none()), "a failed attempt does not count as this ten minutes' attempt");

            InstalledCheck reloaded = new InstalledCheck(api, catalog, clock::get);
            check(reloaded.entry(3).orElseThrow().withdrawn() && Files.isRegularFile(reloaded.stateFile()), ".workshop_state.json persists answers");
            clock.addAndGet(InstalledCheck.TRANSFER_MAX_AGE_MS + 1);
            check(reloaded.transferVerdict(1, sha1) == InstalledCheck.TransferVerdict.CHECK_FIRST, "answer older than 7 days → check first");
            WorkshopDownloader downloader = new WorkshopDownloader(api, catalog, reloaded, Runnable::run, Runnable::run, clock::get);
            check(downloader.status(2).state() == WorkshopDownloader.State.UPDATE_AVAILABLE, "update_available shows as UPDATE_AVAILABLE");
            check(downloader.status(1).state() == WorkshopDownloader.State.INSTALLED && downloader.status(99).state() == WorkshopDownloader.State.NOT_INSTALLED,
                    "INSTALLED / NOT_INSTALLED come from the catalog");
        }
    }

    // ---------------------------------------------------------------- session file

    static void sessionFile(Path root) throws Exception {
        Path authFile = RefreshTokenStore.defaultPath(root.resolve("music_battle_tracks"));
        try (FakeWorkshopServer server = new FakeWorkshopServer()) {
            AtomicReference<String> current = new AtomicReference<>(REFRESH_1);
            server.on("POST", "/api/v1/auth/refresh", r -> {
                if (r.bodyText().contains(current.get())) {
                    current.set(REFRESH_2);
                    return FakeWorkshopServer.Response.json(200, "{\"schema\":1,\"accessToken\":\"bmw_at_" + "D".repeat(43)
                            + "\",\"tokenType\":\"Bearer\",\"expiresIn\":3600,\"refreshToken\":\"" + REFRESH_2 + "\",\"refreshExpiresIn\":2592000,\"scope\":[]}");
                }
                return FakeWorkshopServer.Response.json(401, error("invalid_grant"));
            });
            server.on("GET", "/api/v1/me", r -> FakeWorkshopServer.Response.json(200,
                    "{\"schema\":1,\"account\":{\"id\":17,\"username\":\"dragon4ik\",\"displayName\":\"D\",\"avatarUrl\":null,\"role\":\"user\",\"curator\":false,\"lang\":\"ru\"},"
                            + "\"minecraft\":[],\"notificationsUnread\":0}"));
            server.on("POST", "/api/v1/auth/revoke", r -> FakeWorkshopServer.Response.empty(204));
            WorkshopApi api = api(server, true);
            AtomicLong clock = new AtomicLong(System.currentTimeMillis());
            RefreshTokenStore store = new RefreshTokenStore(authFile);
            WorkshopSession session = new WorkshopSession(api, store, clock::get);

            session.accept(grant(ACCESS, REFRESH_1, 3600), WorkshopSession.Kind.DEVICE, false);
            check(!store.exists(), "no refresh file without Remember me");
            session.accept(grant(ACCESS, REFRESH_1, 3600), WorkshopSession.Kind.DEVICE, true);
            check(store.exists() && session.state().remembered(), "refresh file written with Remember me");
            String content = Files.readString(authFile);
            check(content.contains(REFRESH_1) && !content.contains(ACCESS) && content.contains("\"v\":1"), "session.json holds the refresh token only");
            posixOwnerOnly(authFile, "session.json (and its .account folder) after sign-in");

            WorkshopSession restarted = new WorkshopSession(api, store, clock::get);
            check(restarted.restore(CancelToken.none()) && restarted.signedIn(), "restore signs in with the stored refresh token");
            check(restarted.state().account() != null && restarted.state().account().username().equals("dragon4ik"), "restore loads the account from /me");
            String rotated = Files.readString(authFile);
            check(rotated.contains(REFRESH_2) && !rotated.contains(REFRESH_1), "refresh rotation writes the new token");
            posixOwnerOnly(authFile, "session.json after rotation");
            check(listFiles(authFile.getParent()).size() == 1, "no temporary files left next to session.json");

            // One session per API: a session installs itself as its API's token source.
            WorkshopSession stale = new WorkshopSession(api(server, true), new RefreshTokenStore(root.resolve("stale").resolve("auth.json")), clock::get);
            new RefreshTokenStore(root.resolve("stale").resolve("auth.json")).write(17, REFRESH_1, java.time.Instant.now());
            WorkshopApiException reuse = expectError("reused refresh", () -> stale.refresh(CancelToken.none()));
            check(reuse.code().equals("invalid_grant") && Files.notExists(root.resolve("stale").resolve("auth.json")) && !stale.signedIn(),
                    "invalid_grant deletes the file and signs out (" + reuse.code() + "/" + reuse.status() + ")");

            clock.addAndGet(3_600_000L);
            check(restarted.state().kind() == WorkshopSession.Kind.DEVICE, "still a device session after expiry");
            WorkshopDtos.Me me = restarted.authorized(a -> a.me(CancelToken.none()));
            check(me.account().id() == 17, "authorized() refreshes an expired device token first");
            List<FakeWorkshopServer.Request> refreshes = server.requests("POST", "/api/v1/auth/refresh");
            check(refreshes.size() >= 2, "expired token triggered a refresh");

            restarted.signOut(CancelToken.none());
            FakeWorkshopServer.Request revoke = server.requests("POST", "/api/v1/auth/revoke").get(0);
            check(revoke.header("Authorization") != null && revoke.bodyText().contains("bmw_rt_"), "sign-out revokes with bearer and the refresh token");
            check(!store.exists() && !restarted.signedIn(), "sign-out deletes session.json and forgets the session");

            for (FakeWorkshopServer.Request request : server.snapshot()) {
                String auth = request.header("Authorization");
                if (auth != null) {
                    check(request.path().startsWith("/api/"), "bearer only on /api/");
                }
                check(!request.query().contains("bmw_at_") && !request.query().contains("bmw_rt_"), "tokens never in URLs");
            }
        }
        Path game = Path.of("/games/instance");
        Path remembered = RefreshTokenStore.defaultPath(game.resolve("music_battle_tracks"));
        check(remembered.equals(Path.of("/games/instance/music_battle_tracks/.account/session.json")),
                "Remember me lives in music_battle_tracks/.account/session.json: " + remembered);
        check(remembered.normalize().startsWith(game), "the refresh token file stays inside the game directory");
    }

    static void posixOwnerOnly(Path file, String label) throws IOException {
        if (!java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            return;
        }
        Set<PosixFilePermission> perms = Files.getPosixFilePermissions(file);
        check(perms.equals(PosixFilePermissions.fromString("rw-------")), label + " is 0600 (was " + PosixFilePermissions.toString(perms) + ")");
        Set<PosixFilePermission> dir = Files.getPosixFilePermissions(file.getParent());
        check(dir.equals(PosixFilePermissions.fromString("rwx------")), label + " folder is 0700");
    }

    // ---------------------------------------------------------------- the Minecraft token stays local

    static void minecraftTokenNeverSent(Path root) throws Exception {
        try (FakeWorkshopServer workshop = new FakeWorkshopServer(); FakeWorkshopServer mojang = new FakeWorkshopServer()) {
            Map<String, byte[]> nonces = new ConcurrentHashMap<>();
            Map<String, String> joined = new ConcurrentHashMap<>();
            UUID uuid = UUID.fromString("853c80ef-3c37-49fd-aa49-938b674adae6");
            workshop.on("POST", "/api/v1/mc/challenges", r -> {
                byte[] nonce = new byte[32];
                new SecureRandom().nextBytes(nonce);
                String id = "ch_" + "abcdefghijklmnopqrstuvwx";
                nonces.put(id, nonce);
                return FakeWorkshopServer.Response.json(201, "{\"schema\":1,\"challengeId\":\"" + id + "\",\"nonce\":\""
                        + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(nonce) + "\",\"scheme\":\"bmw-join-v1\",\"expiresAt\":\"x\"}");
            });
            workshop.on("POST", "/api/v1/mc/challenges/ch_abcdefghijklmnopqrstuvwx/verify", r -> {
                JsonObject body = JsonParser.parseString(r.bodyText()).getAsJsonObject();
                byte[] salt = java.util.Base64.getUrlDecoder().decode(body.get("clientSalt").getAsString());
                String expected = ServerIdHash.compute(nonces.get("ch_abcdefghijklmnopqrstuvwx"), salt, uuid);
                if (!expected.equals(joined.get("serverId"))) {
                    return FakeWorkshopServer.Response.json(401, error("mc_not_verified"));
                }
                return FakeWorkshopServer.Response.json(200, "{\"schema\":1,\"status\":\"signed_in\",\"accessToken\":\"" + ACCESS
                        + "\",\"tokenType\":\"Bearer\",\"expiresIn\":43200,\"scope\":[],\"account\":{\"id\":17,\"username\":\"dragon4ik\"},"
                        + "\"minecraft\":{\"uuid\":\"" + uuid + "\",\"name\":\"jeb_\"}}");
            });
            workshop.on("GET", "/api/v1/me", r -> FakeWorkshopServer.Response.json(200, "{\"schema\":1,\"account\":{\"id\":17,\"username\":\"dragon4ik\"}}"));
            mojang.on("POST", "/session/minecraft/join", r -> FakeWorkshopServer.Response.empty(204));
            WorkshopApi api = api(workshop, true);
            WorkshopSession session = new WorkshopSession(api, new RefreshTokenStore(root.resolve("auth.json")), System::currentTimeMillis);
            MinecraftSignIn.Joiner joiner = (profileId, serverId) -> {
                joined.put("serverId", serverId);
                try {
                    java.net.HttpURLConnection c = (java.net.HttpURLConnection) URI.create(mojang.base() + "/session/minecraft/join").toURL().openConnection();
                    c.setRequestMethod("POST");
                    c.setDoOutput(true);
                    c.setRequestProperty("Content-Type", "application/json");
                    c.getOutputStream().write(("{\"accessToken\":\"" + MC_TOKEN + "\",\"selectedProfile\":\"" + profileId + "\",\"serverId\":\"" + serverId + "\"}")
                            .getBytes(StandardCharsets.UTF_8));
                    c.getResponseCode();
                    c.disconnect();
                } catch (IOException exception) {
                    throw new MinecraftSignIn.JoinFailure(MinecraftSignIn.JoinFailure.Kind.UNAVAILABLE, "fake mojang down");
                }
            };
            MinecraftSignIn signIn = new MinecraftSignIn(api, session, joiner, new SecureRandom(), System::currentTimeMillis, "Minecraft 1.21.1 · NeoForge · Test");
            MinecraftSignIn.ProofResult result = signIn.prove(new MinecraftSignIn.Player(uuid, "jeb_"), MinecraftSignIn.PURPOSE_SESSION, CancelToken.none());
            check(result.outcome() == MinecraftSignIn.Outcome.SIGNED_IN && session.signedIn(), "proof against the fake backend signs in");
            session.authorized(a -> a.me(CancelToken.none()));
            check(mojang.snapshot().stream().anyMatch(r -> r.bodyText().contains(MC_TOKEN)), "the Minecraft token went to (fake) Mojang");
            for (FakeWorkshopServer.Request request : workshop.snapshot()) {
                check(!request.everything().contains(MC_TOKEN), "Minecraft access token absent from workshop request " + request.path());
            }
            check(workshop.snapshot().size() >= 3, "challenge, verify and /me were captured");
            FakeWorkshopServer.Request verify = workshop.requests("POST", "/api/v1/mc/challenges/ch_abcdefghijklmnopqrstuvwx/verify").get(0);
            check(!verify.bodyText().contains("serverId") && verify.bodyText().contains("clientSalt"), "verify sends the salt, never a serverId");
        }
    }

    // ---------------------------------------------------------------- consent file

    static void configFile(Path root) throws Exception {
        Path file = root.resolve("config").resolve(WorkshopConfig.FILE_NAME);
        WorkshopConfig config = WorkshopConfig.load(file);
        check(!config.networkAllowed() && !config.consentAnswered(), "no file → no consent");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{\"other\":{\"keep\":true},\"consent\":{\"network\":true,\"version\":0}}");
        config.reload();
        check(!config.networkAllowed(), "an older consent version does not count");
        check(WorkshopConfig.NETWORK_CONSENT_VERSION == 2, "0.2.0-beta.2 asks once more: the disclosure changed (version 2)");
        // Everyone who answered the version 1 screen (0.1.0-beta.1 and builds before this one) sees the new one once,
        // whether they allowed or declined.
        for (boolean answer : new boolean[] {true, false}) {
            Files.writeString(file, "{\"consent\":{\"network\":" + answer + ",\"at\":\"2026-10-01T10:00:00Z\",\"version\":1}}");
            config.reload();
            check(!config.consentAnswered() && !config.networkAllowed(), "a version 1 answer (" + answer + ") is asked again");
        }
        for (boolean answer : new boolean[] {true, false}) {
            Files.writeString(file, "{\"consent\":{\"network\":" + answer + ",\"at\":\"2026-11-01T10:00:00Z\",\"version\":2}}");
            config.reload();
            check(config.consentAnswered() && config.networkAllowed() == answer, "a version 2 answer (" + answer + ") is final");
        }
        Files.writeString(file, "{\"other\":{\"keep\":true},\"consent\":{\"network\":true,\"version\":0}}");
        config.reload();
        config.setNetworkConsent(true, java.time.Instant.parse("2026-09-17T10:00:00Z"));
        JsonObject saved = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        check(saved.getAsJsonObject("consent").get("network").getAsBoolean() && saved.getAsJsonObject("consent").get("version").getAsInt() == WorkshopConfig.NETWORK_CONSENT_VERSION
                && saved.getAsJsonObject("consent").get("at").getAsString().equals("2026-09-17T10:00:00Z"), "consent saved in the 06 section 4 shape");
        check(saved.getAsJsonObject("other").get("keep").getAsBoolean(), "unknown fields kept");
        check(WorkshopConfig.load(file).networkAllowed(), "consent reads back");
        config.setNetworkConsent(false, java.time.Instant.now());
        check(!WorkshopConfig.load(file).networkAllowed() && WorkshopConfig.load(file).consentAnswered(), "revoked consent: no network, question answered");
        check(listFiles(file.getParent()).size() == 1, "atomic write leaves no temporary file");
        Files.writeString(file, "{not json");
        check(!WorkshopConfig.load(file).networkAllowed(), "corrupt file → no consent");
    }

    // ---------------------------------------------------------------- inbox

    static void inbox(Path root) throws Exception {
        Path tracks = root.resolve("music_battle_tracks");
        AtomicLong clock = new AtomicLong(System.currentTimeMillis() + 10_000);
        WorkshopInbox inbox = new WorkshopInbox(tracks, clock::get);
        inbox.ensureDirectory();
        Path good = WorkshopFixtures.write(WorkshopFixtures.build(70, 1, "Inbox"), inbox.directory().resolve("Some Map.blockymap"));
        Files.writeString(inbox.directory().resolve("notes.txt"), "ignored");
        check(inbox.scan().isEmpty(), "first sight of a file is not ready (still settling)");
        check(inbox.scan().equals(List.of(good)), "unchanged file becomes ready on the next scan");
        Files.write(good, new byte[]{1, 2, 3}, java.nio.file.StandardOpenOption.APPEND);
        check(inbox.scan().isEmpty(), "a growing file is not ready");
        check(inbox.scan().size() == 1, "ready again once stable");
        Path rejected = inbox.reject(good, "package_corrupt_archive", "not a zip");
        check(Files.notExists(good) && Files.isRegularFile(rejected) && rejected.getParent().equals(inbox.rejectedDirectory()), "rejected package moved to rejected/");
        String reason = Files.readString(rejected.resolveSibling(rejected.getFileName() + ".txt"));
        check(reason.contains("package_corrupt_archive") && reason.contains("not a zip"), "reason file written");
        Path again = WorkshopFixtures.write(WorkshopFixtures.build(70, 1, "Inbox"), inbox.directory().resolve("Some Map.blockymap"));
        Path second = inbox.reject(again, "package_corrupt_archive", null);
        check(!second.equals(rejected) && Files.exists(rejected), "a second rejection never overwrites the first");
        try {
            inbox.consume(tracks.resolve("elsewhere.blockymap"));
            throw new AssertionError("consumed a file outside the inbox");
        } catch (IOException expected) {
            checks++;
        }
    }

    static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}

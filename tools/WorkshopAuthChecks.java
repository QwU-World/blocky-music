import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.Proxy;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import ru.gora.musicbattle.workshop.ServerIdHash;
import ru.gora.musicbattle.workshop.api.AutoSignInGate;
import ru.gora.musicbattle.workshop.api.CancelToken;
import ru.gora.musicbattle.workshop.api.ClientLabels;
import ru.gora.musicbattle.workshop.api.DeviceFlow;
import ru.gora.musicbattle.workshop.api.MinecraftSignIn;
import ru.gora.musicbattle.workshop.api.RefreshTokenStore;
import ru.gora.musicbattle.workshop.api.WorkshopApi;
import ru.gora.musicbattle.workshop.api.WorkshopConfig;
import ru.gora.musicbattle.workshop.api.WorkshopSession;

/**
 * Workshop sign-in ({@code workshop-auth}): the {@code bmw-join-v1} vectors,
 * the device-flow state machine (pending, slow_down, denied, expiry, "This isn't me"), the Minecraft proof protocol, the
 * link flow that never confirms on its own, the one-automatic-attempt gate, the automatic sign-in only for a Minecraft
 * account that signed in on this computer before (and not after it signed out), and the rule that only
 * {@code client/workshop/MinecraftProof.java} reads the game's access token. Fake clock, local fake backend; no Minecraft.
 */
public final class WorkshopAuthChecks {
    private static int checks;
    private static final String ACCESS = "bmw_at_" + "A".repeat(43);
    private static final String REFRESH = "bmw_rt_" + "B".repeat(43);
    private static final UUID JEB = UUID.fromString("853c80ef-3c37-49fd-aa49-938b674adae6");
    private static final String CHALLENGE = "ch_abcdefghijklmnopqrstuvwx";
    private static final String PROOF = "mp_abcdefghijklmnopqrstuvwx";

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args.length > 0 ? args[0] : ".").toAbsolutePath().normalize();
        Path temp = Files.createTempDirectory("blocky-workshop-auth-");
        try {
            serverIdVectors();
            clientLabels();
            deviceFlow(temp.resolve("device"));
            minecraftProof(temp.resolve("proof"));
            linkFlow(temp.resolve("link"));
            autoGate();
            rememberedAutoSignIn(temp.resolve("remembered"));
            accessTokenSingleReader(root);
        } finally {
            deleteTree(temp);
        }
        System.out.println("Workshop auth checks passed: " + checks);
    }

    static void check(boolean ok, String label) {
        checks++;
        if (!ok) {
            throw new AssertionError(label);
        }
    }

    static WorkshopApi api(FakeWorkshopServer server) {
        return new WorkshopApi(WorkshopApi.Options.of(URI.create(server.base()), () -> Proxy.NO_PROXY, "BlockyMusic/test", () -> true)
                .withTimeouts(2_000, 3_000));
    }

    static String error(String code, Integer retryAfter) {
        return "{\"schema\":1,\"error\":{\"code\":\"" + code + "\",\"message\":\"x\"" + (retryAfter == null ? "" : ",\"retryAfter\":" + retryAfter) + "}}";
    }

    // ---------------------------------------------------------------- bmw-join-v1

    static void serverIdVectors() {
        check(ServerIdHash.minecraftHexSha1("Notch").equals("4ed1f46bbe04bc756bcb17c0c7ce3e4632f06a48"), "Notch vector");
        check(ServerIdHash.minecraftHexSha1("jeb_").equals("-7c9d5b0044c130109a5d7b5fb5c317c02b4e28c1"), "jeb_ vector (negative)");
        check(ServerIdHash.minecraftHexSha1("simon").equals("88e16a1019277b15d58faf0541e11910eb756f6"), "simon vector (no zero padding)");
        byte[] salt = new byte[32];
        String[][] vectors = {
                {"00", "67eac8f8fe8778bfc6c2deadc466d61f4e619da5"},
                {"01", "-5401c282e10596d54024179ca588d5f90a92f24"},
                {"03", "edc8ddd5ade6ba1068810973d8a8c33f9669d02"},
        };
        for (String[] vector : vectors) {
            byte[] nonce = new byte[32];
            java.util.Arrays.fill(nonce, (byte) Integer.parseInt(vector[0], 16));
            check(ServerIdHash.compute(nonce, salt, JEB).equals(vector[1]), "bmw-join-v1 vector nonce 0x" + vector[0]);
        }
        check(HexFormat.of().formatHex(ServerIdHash.uuidBytes(JEB)).equals("853c80ef3c3749fdaa49938b674adae6"), "UUID bytes big-endian");
        for (int[] sizes : new int[][]{{31, 32}, {32, 33}}) {
            try {
                ServerIdHash.compute(new byte[sizes[0]], new byte[sizes[1]], JEB);
                throw new AssertionError("accepted wrong sizes");
            } catch (IllegalArgumentException expected) {
                checks++;
            }
        }
    }

    static void clientLabels() {
        check(ClientLabels.forGame("Mac OS X").equals("Minecraft 1.21.1 · NeoForge · macOS"), "game label");
        check(ClientLabels.sanitize("<script>alert(1)</script>").matches("[\\p{L}\\p{N} ._()\\-·]{1,80}"), "labels sanitized to the server's alphabet");
        check(ClientLabels.sanitize("").equals("Minecraft") && ClientLabels.sanitize("x".repeat(200)).length() == 80, "empty and long labels");
    }

    // ---------------------------------------------------------------- device flow

    static void deviceFlow(Path root) throws Exception {
        try (FakeWorkshopServer server = new FakeWorkshopServer()) {
            AtomicReference<String> next = new AtomicReference<>("pending");
            server.on("POST", "/api/v1/auth/device", r -> FakeWorkshopServer.Response.json(200,
                    "{\"schema\":1,\"deviceCode\":\"bmw_dc_" + "D".repeat(43) + "\",\"userCode\":\"BDFG-HJKL\",\"verificationUri\":\"" + server.base()
                            + "/link\",\"verificationUriComplete\":\"" + server.base() + "/link?code=BDFGHJKL\",\"expiresIn\":600,\"interval\":5}"));
            server.on("POST", "/api/v1/auth/device/token", r -> switch (next.get()) {
                case "pending" -> FakeWorkshopServer.Response.json(400, error("authorization_pending", null));
                case "slow_down" -> FakeWorkshopServer.Response.json(400, error("slow_down", 10), "Retry-After", "10");
                case "denied" -> FakeWorkshopServer.Response.json(400, error("access_denied", null));
                case "expired" -> FakeWorkshopServer.Response.json(400, error("expired_token", null));
                default -> FakeWorkshopServer.Response.json(200, "{\"schema\":1,\"status\":\"signed_in\",\"accessToken\":\"" + ACCESS
                        + "\",\"tokenType\":\"Bearer\",\"expiresIn\":3600,\"refreshToken\":\"" + REFRESH + "\",\"refreshExpiresIn\":2592000,"
                        + "\"scope\":[\"upload\"],\"account\":{\"id\":17,\"username\":\"dragon4ik\",\"displayName\":\"D\",\"avatarUrl\":null}}");
            });
            server.on("POST", "/api/v1/auth/revoke", r -> FakeWorkshopServer.Response.empty(204));
            AtomicLong clock = new AtomicLong(1_000_000L);
            WorkshopApi api = api(server);
            RefreshTokenStore store = new RefreshTokenStore(root.resolve("auth.json"));
            WorkshopSession session = new WorkshopSession(api, store, clock::get);
            DeviceFlow flow = new DeviceFlow(api, session, clock::get);

            flow.start("Minecraft 1.21.1 · NeoForge · macOS", true, CancelToken.none());
            JsonObject startBody = JsonParser.parseString(server.requests("POST", "/api/v1/auth/device").get(0).bodyText()).getAsJsonObject();
            check(startBody.get("remember").getAsBoolean() && startBody.get("clientLabel").getAsString().startsWith("Minecraft 1.21.1"),
                    "start sends clientLabel and remember as its own field");
            DeviceFlow.Snapshot snap = flow.snapshot();
            check(snap.phase() == DeviceFlow.Phase.WAITING && "BDFG-HJKL".equals(snap.userCode()) && snap.intervalSeconds() == 5, "WAITING with the code");
            check(!snap.toString().contains("bmw_dc_"), "snapshot never exposes the device code");

            check(flow.poll(CancelToken.none()) == DeviceFlow.Phase.WAITING && tokenPolls(server) == 0, "no poll before the interval");
            clock.addAndGet(5_000);
            check(flow.poll(CancelToken.none()) == DeviceFlow.Phase.WAITING && tokenPolls(server) == 1, "authorization_pending keeps waiting");
            next.set("slow_down");
            clock.addAndGet(5_000);
            flow.poll(CancelToken.none());
            check(flow.snapshot().intervalSeconds() == 10 && tokenPolls(server) == 2, "slow_down raises the interval to 10 s");
            next.set("pending");
            clock.addAndGet(5_000);
            flow.poll(CancelToken.none());
            check(tokenPolls(server) == 2, "slow_down interval respected (no poll after 5 s)");
            clock.addAndGet(5_000);
            flow.poll(CancelToken.none());
            check(tokenPolls(server) == 3, "poll after the longer interval");

            next.set("signed_in");
            clock.addAndGet(10_000);
            check(flow.poll(CancelToken.none()) == DeviceFlow.Phase.SIGNED_IN, "signed_in → SIGNED_IN (awaiting Continue / This isn't me)");
            check(session.signedIn() && session.state().account().username().equals("dragon4ik"), "session signed in with the account");
            check(store.exists() && Files.readString(store.file()).contains(REFRESH), "Remember me stored the refresh token");
            clock.addAndGet(60_000);
            check(flow.poll(CancelToken.none()) == DeviceFlow.Phase.SIGNED_IN && tokenPolls(server) == 4, "no polling after sign-in");
            flow.confirm();
            check(flow.phase() == DeviceFlow.Phase.CONFIRMED && session.signedIn(), "Continue → CONFIRMED");

            // "This isn't me"
            flow.start("Minecraft", false, CancelToken.none());
            clock.addAndGet(5_000);
            check(flow.poll(CancelToken.none()) == DeviceFlow.Phase.SIGNED_IN, "second sign-in");
            check(!store.exists(), "without Remember me the refresh file is removed");
            flow.notMe(CancelToken.none());
            check(flow.phase() == DeviceFlow.Phase.REVOKED && !session.signedIn(), "This isn't me → REVOKED and signed out");
            FakeWorkshopServer.Request revoke = server.requests("POST", "/api/v1/auth/revoke").get(0);
            check(("Bearer " + ACCESS).equals(revoke.header("Authorization")), "This isn't me revokes the new token family on the server");

            // denied, expired, local expiry, cancel
            next.set("denied");
            flow.start("Minecraft", false, CancelToken.none());
            clock.addAndGet(5_000);
            check(flow.poll(CancelToken.none()) == DeviceFlow.Phase.DENIED && "access_denied".equals(flow.snapshot().errorCode()), "access_denied → DENIED");
            next.set("expired");
            flow.start("Minecraft", false, CancelToken.none());
            clock.addAndGet(5_000);
            check(flow.poll(CancelToken.none()) == DeviceFlow.Phase.EXPIRED, "expired_token → EXPIRED");
            next.set("pending");
            flow.start("Minecraft", false, CancelToken.none());
            int before = tokenPolls(server);
            clock.addAndGet(601_000);
            check(flow.poll(CancelToken.none()) == DeviceFlow.Phase.EXPIRED && tokenPolls(server) == before, "local expiry without a request");
            flow.start("Minecraft", false, CancelToken.none());
            flow.cancel();
            clock.addAndGet(5_000);
            check(flow.poll(CancelToken.none()) == DeviceFlow.Phase.CANCELLED && tokenPolls(server) == before, "cancel stops polling");
            check(!session.signedIn(), "denied/expired/cancelled flows never sign in");
        }
    }

    static int tokenPolls(FakeWorkshopServer server) {
        return server.requests("POST", "/api/v1/auth/device/token").size();
    }

    // ---------------------------------------------------------------- Minecraft proof

    /** A fake backend that recomputes the serverId like the real verify step and compares it with what was joined. */
    static final class FakeMojangBackend {
        final FakeWorkshopServer server;
        final ConcurrentLinkedQueue<String> joined = new ConcurrentLinkedQueue<>();
        final List<byte[]> salts = new ArrayList<>();
        volatile byte[] nonce;
        volatile String verifyStatus = "signed_in";
        volatile String nonceOverride;

        FakeMojangBackend(FakeWorkshopServer server) {
            this.server = server;
            server.on("POST", "/api/v1/mc/challenges", r -> {
                byte[] fresh = new byte[32];
                new SecureRandom().nextBytes(fresh);
                nonce = fresh;
                String encoded = nonceOverride != null ? nonceOverride : Base64.getUrlEncoder().withoutPadding().encodeToString(fresh);
                // A hostile backend also offers a serverId of its choosing; the mod must ignore it.
                return FakeWorkshopServer.Response.json(201, "{\"schema\":1,\"challengeId\":\"" + CHALLENGE + "\",\"nonce\":\"" + encoded
                        + "\",\"scheme\":\"bmw-join-v1\",\"serverId\":\"-deadbeef\",\"expiresAt\":\"x\"}");
            });
            server.on("POST", "/api/v1/mc/challenges/" + CHALLENGE + "/verify", r -> {
                JsonObject body = JsonParser.parseString(r.bodyText()).getAsJsonObject();
                byte[] salt = Base64.getUrlDecoder().decode(body.get("clientSalt").getAsString());
                synchronized (salts) {
                    salts.add(salt);
                }
                String expected = ServerIdHash.compute(nonce, salt, JEB);
                if (salt.length != 32 || !expected.equals(joined.peek() == null ? null : lastJoined())) {
                    return FakeWorkshopServer.Response.json(401, error("mc_not_verified", null));
                }
                return switch (verifyStatus) {
                    case "signed_in" -> FakeWorkshopServer.Response.json(200, "{\"schema\":1,\"status\":\"signed_in\",\"accessToken\":\"" + ACCESS
                            + "\",\"tokenType\":\"Bearer\",\"expiresIn\":43200,\"scope\":[],\"account\":{\"id\":17,\"username\":\"dragon4ik\"},"
                            + "\"minecraft\":{\"uuid\":\"" + JEB + "\",\"name\":\"jeb_\"}}");
                    case "proven" -> FakeWorkshopServer.Response.json(200, "{\"schema\":1,\"status\":\"proven\",\"mcProofId\":\"" + PROOF + "\",\"linkedTo\":null}");
                    default -> FakeWorkshopServer.Response.json(200, "{\"schema\":1,\"status\":\"" + verifyStatus + "\",\"mcProofId\":\"" + PROOF + "\"}");
                };
            });
        }

        String lastJoined() {
            String last = null;
            for (String value : joined) {
                last = value;
            }
            return last;
        }
    }

    static void minecraftProof(Path root) throws Exception {
        try (FakeWorkshopServer server = new FakeWorkshopServer()) {
            FakeMojangBackend backend = new FakeMojangBackend(server);
            AtomicLong clock = new AtomicLong(5_000_000L);
            WorkshopApi api = api(server);
            WorkshopSession session = new WorkshopSession(api, new RefreshTokenStore(root.resolve("auth.json")), clock::get);
            AtomicInteger joins = new AtomicInteger();
            AtomicReference<MinecraftSignIn.JoinFailure> failNext = new AtomicReference<>();
            MinecraftSignIn.Joiner joiner = (profileId, serverId) -> {
                joins.incrementAndGet();
                MinecraftSignIn.JoinFailure failure = failNext.getAndSet(null);
                if (failure != null) {
                    throw failure;
                }
                check(profileId.equals(JEB), "join for the player's own UUID");
                backend.joined.add(serverId);
            };
            MinecraftSignIn signIn = new MinecraftSignIn(api, session, joiner, new SecureRandom(), clock::get, "Minecraft 1.21.1 · NeoForge · Test");
            MinecraftSignIn.Player player = new MinecraftSignIn.Player(JEB, "jeb_");

            MinecraftSignIn.ProofResult result = signIn.prove(player, MinecraftSignIn.PURPOSE_SESSION, CancelToken.none());
            check(result.outcome() == MinecraftSignIn.Outcome.SIGNED_IN, "session proof signs in (" + result.errorCode() + ")");
            check(session.signedIn() && session.state().kind() == WorkshopSession.Kind.MINECRAFT && "jeb_".equals(session.state().minecraft().name()),
                    "Minecraft session state");
            check(!backend.joined.contains("-deadbeef"), "a server-offered serverId is never joined");
            JsonObject challengeBody = JsonParser.parseString(server.requests("POST", "/api/v1/mc/challenges").get(0).bodyText()).getAsJsonObject();
            check(challengeBody.get("purpose").getAsString().equals("session") && challengeBody.get("mcUuid").getAsString().equals(JEB.toString())
                    && challengeBody.get("mcName").getAsString().equals("jeb_"), "challenge body {purpose, mcUuid, mcName}");
            JsonObject verifyBody = JsonParser.parseString(server.requests("POST", "/api/v1/mc/challenges/" + CHALLENGE + "/verify").get(0).bodyText()).getAsJsonObject();
            check(verifyBody.has("clientSalt") && !verifyBody.has("serverId") && verifyBody.get("deviceLabel").getAsString().contains("NeoForge"),
                    "verify body {clientSalt, deviceLabel}, no serverId");

            session.clearLocal();
            signIn.prove(player, MinecraftSignIn.PURPOSE_SESSION, CancelToken.none());
            check(!java.util.Arrays.equals(backend.salts.get(0), backend.salts.get(1)), "a fresh client salt per attempt");

            backend.verifyStatus = "not_linked";
            session.clearLocal();
            result = signIn.prove(player, MinecraftSignIn.PURPOSE_SESSION, CancelToken.none());
            check(result.outcome() == MinecraftSignIn.Outcome.NOT_LINKED && PROOF.equals(signIn.reusableProofId(JEB)) && !session.signedIn(),
                    "not_linked keeps the proof id for a link request");
            clock.addAndGet(MinecraftSignIn.PROOF_REUSE_MS + 1);
            check(signIn.reusableProofId(JEB) == null, "proof id expires after 10 minutes");
            backend.verifyStatus = "auto_sign_in_disabled";
            check(signIn.prove(player, MinecraftSignIn.PURPOSE_SESSION, CancelToken.none()).outcome() == MinecraftSignIn.Outcome.AUTO_SIGN_IN_DISABLED,
                    "auto_sign_in_disabled reported");

            int joinsBefore = joins.get();
            int verifiesBefore = server.requests("POST", "/api/v1/mc/challenges/" + CHALLENGE + "/verify").size();
            backend.nonceOverride = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[31]);
            result = signIn.prove(player, MinecraftSignIn.PURPOSE_SESSION, CancelToken.none());
            check(result.outcome() == MinecraftSignIn.Outcome.FAILED && joins.get() == joinsBefore, "a short nonce is refused before joining");
            backend.nonceOverride = null;

            failNext.set(new MinecraftSignIn.JoinFailure(MinecraftSignIn.JoinFailure.Kind.INVALID_SESSION, "offline"));
            result = signIn.prove(player, MinecraftSignIn.PURPOSE_SESSION, CancelToken.none());
            check(result.outcome() == MinecraftSignIn.Outcome.FAILED && "mc_invalid_session".equals(result.errorCode()), "invalid session mapped");
            check(server.requests("POST", "/api/v1/mc/challenges/" + CHALLENGE + "/verify").size() == verifiesBefore, "no verify after a failed join");
            failNext.set(new MinecraftSignIn.JoinFailure(MinecraftSignIn.JoinFailure.Kind.INSUFFICIENT_PRIVILEGES, "child"));
            check("mc_insufficient_privileges".equals(signIn.prove(player, MinecraftSignIn.PURPOSE_SESSION, CancelToken.none()).errorCode()),
                    "multiplayer disabled mapped");

            backend.verifyStatus = "signed_in";
            server.on("POST", "/api/v1/mc/challenges/" + CHALLENGE + "/verify", r -> FakeWorkshopServer.Response.json(503, error("mojang_unavailable", 30), "Retry-After", "30"));
            result = signIn.prove(player, MinecraftSignIn.PURPOSE_SESSION, CancelToken.none());
            check("mojang_unavailable".equals(result.errorCode()) && result.retryAfterSeconds() == 30, "mojang_unavailable with Retry-After");
        }
    }

    // ---------------------------------------------------------------- link flow

    static void linkFlow(Path root) throws Exception {
        try (FakeWorkshopServer server = new FakeWorkshopServer()) {
            FakeMojangBackend backend = new FakeMojangBackend(server);
            backend.verifyStatus = "not_linked";
            AtomicReference<String> linkState = new AtomicReference<>("pending");
            server.on("POST", "/api/v1/mc/link-requests", r -> FakeWorkshopServer.Response.json(200,
                    "{\"schema\":1,\"deviceCode\":\"bmw_dc_" + "L".repeat(43) + "\",\"userCode\":\"QRST-VWXZ\",\"verificationUri\":\"u\","
                            + "\"verificationUriComplete\":\"u?code=QRSTVWXZ\",\"expiresIn\":600,\"interval\":5}"));
            server.on("POST", "/api/v1/mc/link-requests/token", r -> {
                boolean confirm = r.bodyText().contains("\"confirm\":true");
                if (confirm) {
                    return "approved".equals(linkState.get())
                            ? FakeWorkshopServer.Response.json(200, "{\"schema\":1,\"status\":\"linked\",\"accessToken\":\"" + ACCESS
                            + "\",\"tokenType\":\"Bearer\",\"expiresIn\":43200,\"scope\":[],\"account\":{\"id\":17,\"username\":\"dragon4ik\"},"
                            + "\"minecraft\":{\"uuid\":\"" + JEB + "\",\"name\":\"jeb_\"}}")
                            : FakeWorkshopServer.Response.json(400, error("authorization_pending", null));
                }
                return "approved".equals(linkState.get())
                        ? FakeWorkshopServer.Response.json(200, "{\"schema\":1,\"status\":\"approved\",\"approvedBy\":{\"username\":\"dragon4ik\",\"displayName\":\"D\",\"avatarUrl\":null}}")
                        : FakeWorkshopServer.Response.json(400, error("authorization_pending", null));
            });
            AtomicLong clock = new AtomicLong(9_000_000L);
            WorkshopApi api = api(server);
            WorkshopSession session = new WorkshopSession(api, new RefreshTokenStore(root.resolve("auth.json")), clock::get);
            java.util.Set<UUID> remembered = java.util.concurrent.ConcurrentHashMap.newKeySet();
            MinecraftSignIn.Memory memory = new MinecraftSignIn.Memory() {
                @Override
                public boolean remembered(UUID account) {
                    return remembered.contains(account);
                }

                @Override
                public void remember(UUID account) {
                    remembered.add(account);
                }

                @Override
                public void forget(UUID account) {
                    remembered.remove(account);
                }
            };
            MinecraftSignIn signIn = new MinecraftSignIn(api, session, (id, serverId) -> backend.joined.add(serverId), new SecureRandom(), clock::get,
                    "Minecraft", memory);
            MinecraftSignIn.Player player = new MinecraftSignIn.Player(JEB, "jeb_");

            check(signIn.prove(player, MinecraftSignIn.PURPOSE_SESSION, CancelToken.none()).outcome() == MinecraftSignIn.Outcome.NOT_LINKED, "not linked yet");
            check(remembered.isEmpty(), "a not_linked answer remembers nothing");
            int challenges = server.requests("POST", "/api/v1/mc/challenges").size();
            check(signIn.startLink(player, CancelToken.none()) == MinecraftSignIn.LinkPhase.WAITING, "link request created");
            check(server.requests("POST", "/api/v1/mc/challenges").size() == challenges, "the fresh proof id is reused (no second join)");
            JsonObject linkBody = JsonParser.parseString(server.requests("POST", "/api/v1/mc/link-requests").get(0).bodyText()).getAsJsonObject();
            check(linkBody.get("mcProofId").getAsString().equals(PROOF), "link request carries mcProofId");
            check(signIn.reusableProofId(JEB) == null, "a proof id is used once");

            check(signIn.pollLink(CancelToken.none()) == MinecraftSignIn.LinkPhase.WAITING && linkPolls(server) == 0, "link polling waits for the interval");
            clock.addAndGet(5_000);
            check(signIn.pollLink(CancelToken.none()) == MinecraftSignIn.LinkPhase.WAITING && linkPolls(server) == 1, "pending link");
            linkState.set("approved");
            clock.addAndGet(5_000);
            check(signIn.pollLink(CancelToken.none()) == MinecraftSignIn.LinkPhase.AWAITING_CONFIRMATION, "website approval → awaiting in-game confirmation");
            check(signIn.linkSnapshot().approvedBy().username().equals("dragon4ik"), "confirmation shows the approving account");
            check(!session.signedIn(), "not signed in before the in-game confirmation");
            clock.addAndGet(60_000);
            check(signIn.pollLink(CancelToken.none()) == MinecraftSignIn.LinkPhase.AWAITING_CONFIRMATION && linkPolls(server) == 2,
                    "no further polling while awaiting confirmation");
            check(server.snapshot().stream().noneMatch(r -> r.bodyText().contains("\"confirm\"")), "confirm is never sent without the player");
            check(signIn.confirmLink(CancelToken.none()) == MinecraftSignIn.LinkPhase.LINKED, "Link → LINKED");
            check(server.requests("POST", "/api/v1/mc/link-requests/token").stream().filter(r -> r.bodyText().contains("\"confirm\":true")).count() == 1,
                    "exactly one confirm:true, after the player confirmed");
            check(session.signedIn() && session.state().kind() == WorkshopSession.Kind.MINECRAFT, "linked → signed in");
            check(remembered.equals(java.util.Set.of(JEB)), "a link that signs in remembers the Minecraft account for the next launch");
            remembered.clear();

            // Cancel in the confirmation: nothing is confirmed.
            session.clearLocal();
            linkState.set("pending");
            backend.verifyStatus = "proven";
            int confirmsBefore = confirms(server);
            check(signIn.startLink(player, CancelToken.none()) == MinecraftSignIn.LinkPhase.WAITING, "second link proves with purpose link");
            JsonObject lastChallenge = JsonParser.parseString(lastOf(server.requests("POST", "/api/v1/mc/challenges")).bodyText()).getAsJsonObject();
            check(lastChallenge.get("purpose").getAsString().equals("link"), "without a reusable proof the link flow proves with purpose link");
            linkState.set("approved");
            clock.addAndGet(5_000);
            check(signIn.pollLink(CancelToken.none()) == MinecraftSignIn.LinkPhase.AWAITING_CONFIRMATION, "approved again");
            signIn.cancelLink();
            check(signIn.linkSnapshot().phase() == MinecraftSignIn.LinkPhase.CANCELLED, "No → CANCELLED");
            check(signIn.confirmLink(CancelToken.none()) == MinecraftSignIn.LinkPhase.CANCELLED && confirms(server) == confirmsBefore,
                    "a cancelled link can never be confirmed");
            check(!session.signedIn(), "cancelled link does not sign in");
            check(remembered.isEmpty(), "a cancelled link remembers nothing");
        }
    }

    static int linkPolls(FakeWorkshopServer server) {
        return server.requests("POST", "/api/v1/mc/link-requests/token").size();
    }

    static int confirms(FakeWorkshopServer server) {
        return (int) server.requests("POST", "/api/v1/mc/link-requests/token").stream().filter(r -> r.bodyText().contains("\"confirm\":true")).count();
    }

    static FakeWorkshopServer.Request lastOf(List<FakeWorkshopServer.Request> list) {
        return list.get(list.size() - 1);
    }

    // ---------------------------------------------------------------- automatic attempt

    static void autoGate() {
        AutoSignInGate gate = new AutoSignInGate();
        check(gate.tryStart(false, true, false, false, true) == AutoSignInGate.Decision.NO_CONSENT && !gate.attempted(), "no consent: not spent");
        check(gate.tryStart(true, true, true, false, true) == AutoSignInGate.Decision.LOGIN_IN_PROGRESS && !gate.attempted(), "server login in progress: not spent");
        check(gate.tryStart(true, false, false, false, true) == AutoSignInGate.Decision.NOT_MICROSOFT_ACCOUNT, "offline account skipped");
        check(gate.tryStart(true, true, false, true, true) == AutoSignInGate.Decision.ALREADY_SIGNED_IN, "already signed in skipped");
        check(gate.tryStart(true, true, false, false, false) == AutoSignInGate.Decision.NOT_REMEMBERED && !gate.attempted(),
                "an account that never signed in here: skipped, not spent");
        check(gate.tryStart(true, true, false, false, true) == AutoSignInGate.Decision.STARTED && gate.attempted(), "first real attempt starts");
        check(gate.tryStart(true, true, false, false, true) == AutoSignInGate.Decision.ALREADY_ATTEMPTED, "at most one automatic attempt per launch");
    }

    /**
     * Owner decision 01.11.2026: the automatic sign-in is only for a Minecraft account that signed in to the Workshop on
     * this computer before. Played against the fake backend over three launches, each a fresh gate, session and sign-in
     * reading the same config file: an unknown account sends nothing at all; a manual Minecraft sign-in remembers it,
     * so the next launch signs in by itself; a sign-out forgets it, so the launch after that sends nothing again.
     * Turning network access off keeps it.
     */
    static void rememberedAutoSignIn(Path root) throws Exception {
        Path configFile = root.resolve("config").resolve(WorkshopConfig.FILE_NAME);
        try (FakeWorkshopServer server = new FakeWorkshopServer()) {
            FakeMojangBackend backend = new FakeMojangBackend(server);
            AtomicLong clock = new AtomicLong(7_000_000L);
            WorkshopApi api = api(server);
            MinecraftSignIn.Player player = new MinecraftSignIn.Player(JEB, "jeb_");
            AtomicInteger joins = new AtomicInteger();
            MinecraftSignIn.Joiner joiner = (profileId, serverId) -> {
                joins.incrementAndGet();
                backend.joined.add(serverId);
            };

            // Launch 1: this Minecraft account has never signed in here.
            WorkshopConfig config = WorkshopConfig.load(configFile);
            config.setNetworkConsent(true, java.time.Instant.parse("2026-11-01T10:00:00Z"));
            WorkshopSession session = new WorkshopSession(api, new RefreshTokenStore(root.resolve("auth1.json")), clock::get);
            MinecraftSignIn signIn = new MinecraftSignIn(api, session, joiner, new SecureRandom(), clock::get, "Minecraft", config);
            AutoSignInGate gate = new AutoSignInGate();
            check(!signIn.remembered(JEB), "a fresh computer remembers no Minecraft account");
            AutoSignInGate.Decision decision = signIn.autoDecision(gate, player, true, true, false);
            check(decision == AutoSignInGate.Decision.NOT_REMEMBERED, "unknown account: the automatic sign-in does not start (" + decision + ")");
            check(server.snapshot().isEmpty() && joins.get() == 0, "unknown account: no challenge, no join, nothing sent at all");
            check(signIn.autoDecision(gate, null, true, true, false) == AutoSignInGate.Decision.NOT_REMEMBERED,
                    "an account name Mojang would refuse is never remembered");
            // The player presses "Sign in with Minecraft".
            check(signIn.prove(player, MinecraftSignIn.PURPOSE_SESSION, CancelToken.none()).outcome() == MinecraftSignIn.Outcome.SIGNED_IN,
                    "the manual sign-in works");
            check(signIn.remembered(JEB) && WorkshopConfig.load(configFile).remembered(JEB), "a manual Minecraft sign-in remembers the account on disk");
            JsonObject saved = JsonParser.parseString(Files.readString(configFile)).getAsJsonObject();
            check(saved.getAsJsonArray("autoSignIn").size() == 1 && saved.getAsJsonArray("autoSignIn").get(0).getAsString().equals(JEB.toString())
                    && saved.getAsJsonObject("consent").get("network").getAsBoolean(), "kept by UUID next to the consent");

            // Launch 2: the session is gone (no refresh token kept), the account is remembered.
            int challengesBefore = server.requests("POST", "/api/v1/mc/challenges").size();
            config = WorkshopConfig.load(configFile);
            session = new WorkshopSession(api, new RefreshTokenStore(root.resolve("auth2.json")), clock::get);
            signIn = new MinecraftSignIn(api, session, joiner, new SecureRandom(), clock::get, "Minecraft", config);
            gate = new AutoSignInGate();
            check(signIn.autoDecision(gate, player, true, true, false) == AutoSignInGate.Decision.STARTED, "the next launch starts the automatic sign-in");
            check(signIn.prove(player, MinecraftSignIn.PURPOSE_SESSION, CancelToken.none()).outcome() == MinecraftSignIn.Outcome.SIGNED_IN
                    && session.signedIn(), "and signs in by itself");
            check(server.requests("POST", "/api/v1/mc/challenges").size() == challengesBefore + 1, "with exactly one challenge");
            check(signIn.autoDecision(gate, player, true, true, false) == AutoSignInGate.Decision.ALREADY_ATTEMPTED, "still once per launch");

            // Turning network access off is not a sign-out.
            config.setNetworkConsent(false, java.time.Instant.parse("2026-11-01T11:00:00Z"));
            check(WorkshopConfig.load(configFile).remembered(JEB), "turning network access off keeps the remembered account");
            check(signIn.autoDecision(new AutoSignInGate(), player, config.networkAllowed(), true, false) == AutoSignInGate.Decision.NO_CONSENT,
                    "and with it off nothing starts");
            config.setNetworkConsent(true, java.time.Instant.parse("2026-11-01T11:05:00Z"));

            // The player signs out.
            signIn.forget(JEB);
            session.signOut(CancelToken.none());
            check(!WorkshopConfig.load(configFile).remembered(JEB), "an explicit sign-out forgets the account");

            // Launch 3: nothing is sent again.
            int before = server.snapshot().size();
            int joinsBefore = joins.get();
            config = WorkshopConfig.load(configFile);
            session = new WorkshopSession(api, new RefreshTokenStore(root.resolve("auth3.json")), clock::get);
            signIn = new MinecraftSignIn(api, session, joiner, new SecureRandom(), clock::get, "Minecraft", config);
            check(signIn.autoDecision(new AutoSignInGate(), player, true, true, false) == AutoSignInGate.Decision.NOT_REMEMBERED,
                    "after a sign-out the next launch does not start it");
            check(server.snapshot().size() == before && joins.get() == joinsBefore, "after a sign-out: no request at all");

            check(WorkshopConfig.load(configFile).consentAnswered() && WorkshopConfig.load(configFile).networkAllowed(), "consent kept throughout");
            // A damaged list counts nothing, and the remembered set survives unrelated saves.
            Files.writeString(configFile, "{\"consent\":{\"network\":true,\"version\":" + WorkshopConfig.NETWORK_CONSENT_VERSION
                    + "},\"autoSignIn\":[\"not-a-uuid\",7,{\"x\":1}]}");
            config = WorkshopConfig.load(configFile);
            check(!config.remembered(JEB), "entries that are not UUIDs do not count");
            config.remember(JEB);
            config.setNumber("previewVolume", 0.5);
            check(WorkshopConfig.load(configFile).remembered(JEB), "the list survives another option being saved");
            check(!new MinecraftSignIn(api, session, joiner, new SecureRandom(), clock::get, "Minecraft").remembered(JEB),
                    "without a memory nothing is remembered");
        }
    }

    // ---------------------------------------------------------------- the access token has one reader

    static void accessTokenSingleReader(Path root) throws IOException {
        Path sources = root.resolve("src/main/java");
        check(Files.isDirectory(sources), "mod sources found at " + sources);
        List<String> readers = new ArrayList<>();
        List<String> joiners = new ArrayList<>();
        Pattern accessToken = Pattern.compile("\\bgetAccessToken\\s*\\(");
        Pattern joinServer = Pattern.compile("\\bjoinServer\\s*\\(");
        try (Stream<Path> walk = Files.walk(sources)) {
            for (Path file : walk.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
                String text = stripComments(Files.readString(file));
                String rel = sources.relativize(file).toString().replace('\\', '/');
                if (accessToken.matcher(text).find()) {
                    readers.add(rel);
                }
                if (joinServer.matcher(text).find()) {
                    joiners.add(rel);
                }
            }
        }
        String only = "ru/gora/musicbattle/client/workshop/MinecraftProof.java";
        check(readers.equals(List.of(only)), "getAccessToken() is referenced only in MinecraftProof: " + readers);
        check(joiners.equals(List.of(only)), "joinServer() is called only in MinecraftProof: " + joiners);
        String proof = stripComments(Files.readString(sources.resolve(only)));
        Matcher m = accessToken.matcher(proof);
        int count = 0;
        while (m.find()) {
            count++;
        }
        check(count == 1, "MinecraftProof reads the token exactly once");
        check(Pattern.compile("joinServer\\s*\\(\\s*profileId\\s*,\\s*user\\.getAccessToken\\(\\)\\s*,\\s*serverId\\s*\\)").matcher(proof).find(),
                "the token goes straight into joinServer");
        check(!proof.matches("(?s).*LOGGER[^;]*getAccessToken.*"), "the token is never logged");
    }

    static String stripComments(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\n]*", "");
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

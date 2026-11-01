package ru.gora.musicbattle.workshop.api;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;
import ru.gora.musicbattle.workshop.ServerIdHash;
import ru.gora.musicbattle.workshop.api.WorkshopDtos.ApprovedBy;
import ru.gora.musicbattle.workshop.api.WorkshopDtos.AuthResult;
import ru.gora.musicbattle.workshop.api.WorkshopDtos.Challenge;
import ru.gora.musicbattle.workshop.api.WorkshopDtos.DeviceCodes;
import ru.gora.musicbattle.workshop.api.WorkshopDtos.MinecraftRef;

/**
 * Minecraft proof, automatic sign-in and account linking.
 * Pure protocol; the only Minecraft-specific step, {@code joinServer}, sits behind {@link Joiner}, which
 * the client implements in {@code client.workshop.MinecraftProof}, the one class that touches the game's access token.
 *
 * <p>The {@code serverId} is always computed here from the backend nonce plus 32 fresh random bytes of our own
 * ({@link ServerIdHash}); a string from the network is never joined. The link flow never sends {@code confirm: true}
 * on its own: after the website approval it waits in {@link LinkPhase#AWAITING_CONFIRMATION} until the player
 * confirms "Link Minecraft name to QwU World @username?" ({@link #confirmLink}). Blocking network calls; callers
 * serialise proofs on one executor.
 *
 * <p>The automatic sign-in when the Workshop opens is only for a Minecraft account this computer remembers
 * ({@link Memory}): one that signed in here with its Minecraft proof before, and has not signed out since. For any
 * other account {@link #autoDecision} says {@link AutoSignInGate.Decision#NOT_REMEMBERED} and nothing is sent.
 */
public final class MinecraftSignIn {
    public static final String PURPOSE_SESSION = "session";
    public static final String PURPOSE_LINK = "link";
    /** A proof id from {@code not_linked}/{@code auto_sign_in_disabled} may start a link request this long. */
    public static final long PROOF_REUSE_MS = 10 * 60_000L;
    private static final Pattern MC_NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");
    private static final Pattern PROOF_ID = Pattern.compile("mp_[a-z2-7]{24}");

    /** Calls {@code MinecraftSessionService.joinServer(profileId, <game token>, serverId)}. */
    @FunctionalInterface
    public interface Joiner {
        void join(UUID profileId, String serverId) throws JoinFailure;
    }

    /**
     * The Minecraft accounts that signed in to the Workshop on this computer (the game keeps them in
     * {@code config/music_battle-workshop.json}, see {@link WorkshopConfig}).
     */
    public interface Memory {
        Memory NONE = new Memory() {
            @Override
            public boolean remembered(UUID account) {
                return false;
            }

            @Override
            public void remember(UUID account) {
            }

            @Override
            public void forget(UUID account) {
            }
        };

        boolean remembered(UUID account);

        void remember(UUID account) throws java.io.IOException;

        void forget(UUID account) throws java.io.IOException;
    }

    /** Why {@code joinServer} failed, mapped from authlib's exceptions. */
    public static final class JoinFailure extends Exception {
        public enum Kind {
            /** Mojang unreachable or 5xx: retry later. */
            UNAVAILABLE,
            /** Offline/cracked/dev session or an expired Minecraft token: "restart the launcher". */
            INVALID_SESSION,
            /** Multiplayer disabled for this Microsoft account. */
            INSUFFICIENT_PRIVILEGES,
            BANNED,
            /** Anything else, including Mojang's 429. */
            OTHER
        }

        private final Kind kind;

        public JoinFailure(Kind kind, String message) {
            super(message);
            this.kind = kind;
        }

        public Kind kind() {
            return kind;
        }

        public String code() {
            return switch (kind) {
                case UNAVAILABLE -> "mojang_unavailable";
                case INVALID_SESSION -> "mc_invalid_session";
                case INSUFFICIENT_PRIVILEGES -> "mc_insufficient_privileges";
                case BANNED -> "mc_multiplayer_banned";
                case OTHER -> "mc_join_failed";
            };
        }
    }

    public record Player(UUID uuid, String name) {
        public Player {
            Objects.requireNonNull(uuid, "uuid");
            if (name == null || !MC_NAME.matcher(name).matches()) {
                throw new IllegalArgumentException("not a Minecraft account name");
            }
        }
    }

    public enum Outcome {
        SIGNED_IN, NOT_LINKED, AUTO_SIGN_IN_DISABLED, PROVEN, FAILED
    }

    /** Result of one proof; {@code errorCode} maps to {@code music_battle.workshop.error.<code>}. */
    public record ProofResult(Outcome outcome, String mcProofId, String linkedTo, String errorCode, long retryAfterSeconds) {
        static ProofResult failed(String code, long retryAfter) {
            return new ProofResult(Outcome.FAILED, null, null, code, retryAfter);
        }
    }

    public enum LinkPhase {
        IDLE, PROVING, WAITING, AWAITING_CONFIRMATION, LINKING, LINKED, DENIED, EXPIRED, CANCELLED, FAILED
    }

    public record LinkSnapshot(LinkPhase phase, String userCode, String verificationUri, String verificationUriComplete,
                               long expiresAtMs, int intervalSeconds, ApprovedBy approvedBy, MinecraftRef minecraft,
                               String linkedTo, String errorCode) {
    }

    private final WorkshopApi api;
    private final WorkshopSession session;
    private final Joiner joiner;
    private final SecureRandom random;
    private final LongSupplier clock;
    private final String deviceLabel;
    private final Memory memory;

    private String reusableProofId;
    private UUID reusableProofUuid;
    private long reusableProofAtMs;

    private LinkPhase linkPhase = LinkPhase.IDLE;
    private DeviceCodes linkCodes;
    private String linkDeviceCode;
    private long linkExpiresAtMs;
    private int linkIntervalSeconds;
    private long linkNextPollAtMs;
    private ApprovedBy approvedBy;
    private MinecraftRef linkedMinecraft;
    private String linkedTo;
    private String linkError;
    private int linkGeneration;
    /** The Minecraft account the running link proved, remembered once the link signs it in. */
    private UUID linkPlayer;

    /** Without a memory: nothing is remembered, so the automatic sign-in never starts. */
    public MinecraftSignIn(WorkshopApi api, WorkshopSession session, Joiner joiner, SecureRandom random, LongSupplier clock,
                           String deviceLabel) {
        this(api, session, joiner, random, clock, deviceLabel, Memory.NONE);
    }

    public MinecraftSignIn(WorkshopApi api, WorkshopSession session, Joiner joiner, SecureRandom random, LongSupplier clock,
                           String deviceLabel, Memory memory) {
        this.memory = memory == null ? Memory.NONE : memory;
        this.api = api;
        this.session = session;
        this.joiner = joiner;
        this.random = random;
        this.clock = clock;
        this.deviceLabel = ClientLabels.sanitize(deviceLabel);
    }

    /**
     * Whether the automatic sign-in of this launch may start for {@code player} (null when the game's account name is
     * not one Mojang accepts). Checked before anything is sent: an account this computer does not remember, or none at
     * all, is {@link AutoSignInGate.Decision#NOT_REMEMBERED}. Only {@link AutoSignInGate.Decision#STARTED} spends the
     * attempt, and the caller then runs {@link #prove} with {@link #PURPOSE_SESSION}.
     */
    public AutoSignInGate.Decision autoDecision(AutoSignInGate gate, Player player, boolean consent, boolean microsoftAccount,
                                                boolean loginInProgress) {
        boolean known = player != null && memory.remembered(player.uuid());
        return gate.tryStart(consent, microsoftAccount, loginInProgress, session.signedIn(), known);
    }

    /** True when this Minecraft account signed in here before and has not signed out since. */
    public boolean remembered(UUID account) {
        return account != null && memory.remembered(account);
    }

    /**
     * An explicit sign-out: the next launch no longer signs this Minecraft account in by itself. Turning network
     * access off is not a sign-out and keeps it.
     */
    public void forget(UUID account) {
        try {
            memory.forget(account);
        } catch (java.io.IOException exception) {
            // The file could not be written. The entry stays on disk; the session itself is signed out regardless.
        }
    }

    private void rememberAccount(UUID account) {
        if (account == null) {
            return;
        }
        try {
            memory.remember(account);
        } catch (java.io.IOException exception) {
            // Not remembered means only that the next launch asks the player to sign in again.
        }
    }

    /**
     * challenge → join → verify. For {@link #PURPOSE_SESSION} a {@code signed_in} answer signs the session in;
     * {@code not_linked} and {@code auto_sign_in_disabled} keep the proof id for a link request (10 minutes).
     */
    public ProofResult prove(Player player, String purpose, CancelToken cancel) {
        if (!PURPOSE_SESSION.equals(purpose) && !PURPOSE_LINK.equals(purpose)) {
            throw new IllegalArgumentException("purpose");
        }
        Challenge challenge;
        try {
            challenge = api.mcChallenge(purpose, dashed(player.uuid()), player.name(), cancel);
        } catch (WorkshopApiException exception) {
            return ProofResult.failed(exception.code(), exception.retryAfterSeconds());
        }
        byte[] nonce = decodeB64url(challenge.nonce());
        if (nonce == null || nonce.length != ServerIdHash.NONCE_BYTES
                || (challenge.scheme() != null && !ServerIdHash.SCHEME.equals(challenge.scheme()))) {
            return ProofResult.failed(WorkshopApiException.BAD_RESPONSE, -1);
        }
        byte[] salt = new byte[ServerIdHash.SALT_BYTES];
        random.nextBytes(salt);
        String serverId = ServerIdHash.compute(nonce, salt, player.uuid());
        try {
            joiner.join(player.uuid(), serverId);
        } catch (JoinFailure failure) {
            return ProofResult.failed(failure.code(), -1);
        }
        AuthResult result;
        try {
            result = api.mcVerify(challenge.challengeId(), Base64.getUrlEncoder().withoutPadding().encodeToString(salt), deviceLabel, cancel);
        } catch (WorkshopApiException exception) {
            return ProofResult.failed(exception.code(), exception.retryAfterSeconds());
        }
        String status = String.valueOf(result.status());
        switch (status) {
            case "signed_in" -> {
                if (!PURPOSE_SESSION.equals(purpose)) {
                    return ProofResult.failed(WorkshopApiException.BAD_RESPONSE, -1);
                }
                try {
                    session.accept(result, WorkshopSession.Kind.MINECRAFT, false);
                } catch (WorkshopApiException exception) {
                    return ProofResult.failed(exception.code(), -1);
                }
                rememberAccount(player.uuid());
                return new ProofResult(Outcome.SIGNED_IN, null, null, null, -1);
            }
            case "not_linked", "auto_sign_in_disabled", "proven" -> {
                if (result.mcProofId() == null || !PROOF_ID.matcher(result.mcProofId()).matches()) {
                    return ProofResult.failed(WorkshopApiException.BAD_RESPONSE, -1);
                }
                synchronized (this) {
                    reusableProofId = result.mcProofId();
                    reusableProofUuid = player.uuid();
                    reusableProofAtMs = clock.getAsLong();
                }
                Outcome outcome = status.equals("not_linked") ? Outcome.NOT_LINKED
                        : status.equals("proven") ? Outcome.PROVEN : Outcome.AUTO_SIGN_IN_DISABLED;
                return new ProofResult(outcome, result.mcProofId(), result.linkedTo(), null, -1);
            }
            default -> {
                return ProofResult.failed(WorkshopApiException.BAD_RESPONSE, -1);
            }
        }
    }

    /** The proof id a link request can still use for this player, if any. */
    public synchronized String reusableProofId(UUID uuid) {
        if (reusableProofId == null || !uuid.equals(reusableProofUuid) || clock.getAsLong() - reusableProofAtMs > PROOF_REUSE_MS) {
            return null;
        }
        return reusableProofId;
    }

    public synchronized LinkSnapshot linkSnapshot() {
        return new LinkSnapshot(linkPhase, linkCodes == null ? null : linkCodes.userCode(),
                linkCodes == null ? null : linkCodes.verificationUri(), linkCodes == null ? null : linkCodes.verificationUriComplete(),
                linkExpiresAtMs, linkIntervalSeconds, approvedBy, linkedMinecraft, linkedTo, linkError);
    }

    /** "Link this Minecraft account": reuses a fresh proof or proves with purpose {@code link}, then requests codes. */
    public LinkPhase startLink(Player player, CancelToken cancel) {
        int generation;
        String proofId;
        synchronized (this) {
            generation = ++linkGeneration;
            resetLink();
            linkPlayer = player.uuid();
            linkPhase = LinkPhase.PROVING;
            proofId = reusableProofId(player.uuid());
            // A proof id is single use: a link request consumes it.
            reusableProofId = null;
        }
        if (proofId == null) {
            ProofResult proof = prove(player, PURPOSE_LINK, cancel);
            synchronized (this) {
                reusableProofId = null;
                if (generation != linkGeneration) {
                    return linkPhase;
                }
                if (proof.outcome() != Outcome.PROVEN) {
                    return finishLink(LinkPhase.FAILED, proof.errorCode() == null ? WorkshopApiException.BAD_RESPONSE : proof.errorCode());
                }
                linkedTo = proof.linkedTo();
            }
            proofId = proof.mcProofId();
        }
        DeviceCodes codes;
        try {
            codes = api.mcLinkRequest(proofId, deviceLabel, cancel);
        } catch (WorkshopApiException exception) {
            synchronized (this) {
                return generation == linkGeneration ? finishLink(LinkPhase.FAILED, exception.code()) : linkPhase;
            }
        }
        synchronized (this) {
            if (generation != linkGeneration) {
                return linkPhase;
            }
            linkCodes = codes;
            linkDeviceCode = codes.deviceCode();
            long now = clock.getAsLong();
            linkExpiresAtMs = now + codes.expiresIn() * 1000L;
            linkIntervalSeconds = Math.max(1, codes.interval());
            linkNextPollAtMs = now + linkIntervalSeconds * 1000L;
            linkPhase = LinkPhase.WAITING;
            return linkPhase;
        }
    }

    /** Polls the link request when due. Never confirms. */
    public LinkPhase pollLink(CancelToken cancel) {
        String code;
        int generation;
        synchronized (this) {
            if (linkPhase != LinkPhase.WAITING) {
                return linkPhase;
            }
            long now = clock.getAsLong();
            if (now >= linkExpiresAtMs) {
                return finishLink(LinkPhase.EXPIRED, "expired_token");
            }
            if (now < linkNextPollAtMs) {
                return linkPhase;
            }
            linkNextPollAtMs = now + linkIntervalSeconds * 1000L;
            code = linkDeviceCode;
            generation = linkGeneration;
        }
        AuthResult result;
        try {
            result = api.mcLinkToken(code, false, cancel);
        } catch (WorkshopApiException exception) {
            synchronized (this) {
                if (generation != linkGeneration || linkPhase != LinkPhase.WAITING) {
                    return linkPhase;
                }
                return switch (exception.code()) {
                    case "authorization_pending" -> linkPhase;
                    case "slow_down" -> {
                        linkIntervalSeconds = (int) Math.min(60, Math.max(linkIntervalSeconds + 5L, exception.retryAfterSeconds()));
                        linkNextPollAtMs = clock.getAsLong() + linkIntervalSeconds * 1000L;
                        yield linkPhase;
                    }
                    case "access_denied" -> finishLink(LinkPhase.DENIED, exception.code());
                    case "expired_token" -> finishLink(LinkPhase.EXPIRED, exception.code());
                    default -> exception.status() == 429 || exception.status() >= 500 || exception.status() == 0
                            ? linkPhase : finishLink(LinkPhase.FAILED, exception.code());
                };
            }
        }
        synchronized (this) {
            if (generation != linkGeneration || linkPhase != LinkPhase.WAITING) {
                return linkPhase;
            }
            if (!"approved".equals(result.status())) {
                return finishLink(LinkPhase.FAILED, WorkshopApiException.BAD_RESPONSE);
            }
            approvedBy = result.approvedBy();
            linkPhase = LinkPhase.AWAITING_CONFIRMATION;
            return linkPhase;
        }
    }

    /** The in-game "Link" button after the approval. Only this sends {@code confirm: true}. */
    public LinkPhase confirmLink(CancelToken cancel) {
        String code;
        int generation;
        synchronized (this) {
            if (linkPhase != LinkPhase.AWAITING_CONFIRMATION) {
                return linkPhase;
            }
            linkPhase = LinkPhase.LINKING;
            code = linkDeviceCode;
            generation = linkGeneration;
        }
        AuthResult result;
        try {
            result = api.mcLinkToken(code, true, cancel);
        } catch (WorkshopApiException exception) {
            synchronized (this) {
                return generation == linkGeneration ? finishLink(LinkPhase.FAILED, exception.code()) : linkPhase;
            }
        }
        if (!"linked".equals(result.status()) || !result.hasAccessToken()) {
            synchronized (this) {
                return finishLink(LinkPhase.FAILED, WorkshopApiException.BAD_RESPONSE);
            }
        }
        try {
            session.accept(result, WorkshopSession.Kind.MINECRAFT, false);
        } catch (WorkshopApiException exception) {
            synchronized (this) {
                return finishLink(LinkPhase.FAILED, exception.code());
            }
        }
        UUID proven;
        synchronized (this) {
            linkedMinecraft = result.minecraft();
            linkDeviceCode = null;
            linkPhase = LinkPhase.LINKED;
            proven = linkPlayer;
        }
        // Linking ends signed in with this Minecraft account's proof, the same as a Minecraft sign-in.
        rememberAccount(proven);
        synchronized (this) {
            return linkPhase;
        }
    }

    /** Cancel or "No" in the confirmation: nothing is confirmed, the request simply expires on the server. */
    public synchronized void cancelLink() {
        if (linkPhase != LinkPhase.LINKED && linkPhase != LinkPhase.IDLE) {
            linkGeneration++;
            finishLink(LinkPhase.CANCELLED, null);
        }
    }

    private void resetLink() {
        linkPhase = LinkPhase.IDLE;
        linkCodes = null;
        linkDeviceCode = null;
        linkExpiresAtMs = 0;
        linkIntervalSeconds = 0;
        linkNextPollAtMs = 0;
        approvedBy = null;
        linkedMinecraft = null;
        linkedTo = null;
        linkError = null;
    }

    private LinkPhase finishLink(LinkPhase phase, String code) {
        linkPhase = phase;
        linkError = code;
        linkDeviceCode = null;
        return phase;
    }

    static String dashed(UUID uuid) {
        return uuid.toString().toLowerCase(Locale.ROOT);
    }

    static byte[] decodeB64url(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_-]{1,128}")) {
            return null;
        }
        try {
            return Base64.getUrlDecoder().decode(value);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }
}

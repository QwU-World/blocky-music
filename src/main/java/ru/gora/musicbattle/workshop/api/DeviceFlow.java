package ru.gora.musicbattle.workshop.api;

import java.util.function.LongSupplier;
import ru.gora.musicbattle.workshop.api.WorkshopDtos.AccountSummary;
import ru.gora.musicbattle.workshop.api.WorkshopDtos.AuthResult;
import ru.gora.musicbattle.workshop.api.WorkshopDtos.DeviceCodes;

/**
 * Device-code sign-in for the mod (RFC 8628). A state machine a screen drives:
 *
 * <pre>
 * IDLE -start-> WAITING -poll: authorization_pending-> WAITING
 *                       -poll: slow_down (+5 s)------> WAITING
 *                       -poll: signed_in-------------> SIGNED_IN -confirm-> CONFIRMED
 *                                                                -notMe---> REVOKED (tokens revoked, file deleted)
 *                       -poll: access_denied---------> DENIED
 *                       -poll: expired_token / local expiry -> EXPIRED
 *                       -cancel----------------------> CANCELLED
 * </pre>
 *
 * {@link #poll()} never calls the server before the current interval has passed. The session is signed in as soon as
 * the server answers {@code signed_in}; the screen then shows "Signed in as @username. Continue · This isn't me"
 * (A1.2), and "This isn't me" revokes. Blocking network calls: run {@link #start} and {@link #poll} off the render thread.
 */
public final class DeviceFlow {
    public enum Phase {
        IDLE, WAITING, SIGNED_IN, CONFIRMED, DENIED, EXPIRED, CANCELLED, REVOKED, FAILED;

        public boolean finished() {
            return this == CONFIRMED || this == DENIED || this == EXPIRED || this == CANCELLED || this == REVOKED || this == FAILED;
        }
    }

    /** What the device screen shows. Never holds the device code or a token. */
    public record Snapshot(Phase phase, String userCode, String verificationUri, String verificationUriComplete, long expiresAtMs,
                           int intervalSeconds, AccountSummary account, String errorCode) {
    }

    private final WorkshopApi api;
    private final WorkshopSession session;
    private final LongSupplier clock;
    private Phase phase = Phase.IDLE;
    private String deviceCode;
    private DeviceCodes codes;
    private long expiresAtMs;
    private int intervalSeconds;
    private long nextPollAtMs;
    private boolean remember;
    private AccountSummary account;
    private String errorCode;
    private int generation;

    public DeviceFlow(WorkshopApi api, WorkshopSession session, LongSupplier clock) {
        this.api = api;
        this.session = session;
        this.clock = clock;
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(phase, codes == null ? null : codes.userCode(), codes == null ? null : codes.verificationUri(),
                codes == null ? null : codes.verificationUriComplete(), expiresAtMs, intervalSeconds, account, errorCode);
    }

    public synchronized Phase phase() {
        return phase;
    }

    /** Requests codes. {@code remember} asks the website to offer a refresh token ("Remember me on this computer"). */
    public void start(String clientLabel, boolean remember, CancelToken cancel) throws WorkshopApiException {
        int started;
        synchronized (this) {
            started = ++generation;
            reset();
            this.remember = remember;
        }
        DeviceCodes received;
        try {
            received = api.startDevice(ClientLabels.sanitize(clientLabel), remember, cancel);
        } catch (WorkshopApiException exception) {
            fail(started, exception.code());
            throw exception;
        }
        synchronized (this) {
            if (started != generation) {
                return;
            }
            codes = received;
            deviceCode = received.deviceCode();
            long now = clock.getAsLong();
            expiresAtMs = now + received.expiresIn() * 1000L;
            intervalSeconds = Math.max(1, received.interval());
            nextPollAtMs = now + intervalSeconds * 1000L;
            phase = Phase.WAITING;
        }
    }

    /** True when {@link #poll} would contact the server now. */
    public synchronized boolean pollDue() {
        return phase == Phase.WAITING && clock.getAsLong() >= nextPollAtMs;
    }

    /** Polls once when due; returns the phase afterwards. Local expiry needs no request. */
    public Phase poll(CancelToken cancel) {
        String code;
        int polled;
        synchronized (this) {
            if (phase != Phase.WAITING) {
                return phase;
            }
            long now = clock.getAsLong();
            if (now >= expiresAtMs) {
                phase = Phase.EXPIRED;
                errorCode = "expired_token";
                deviceCode = null;
                return phase;
            }
            if (now < nextPollAtMs) {
                return phase;
            }
            nextPollAtMs = now + intervalSeconds * 1000L;
            code = deviceCode;
            polled = generation;
        }
        AuthResult result;
        try {
            result = api.pollDevice(code, cancel);
        } catch (WorkshopApiException exception) {
            synchronized (this) {
                if (polled != generation || phase != Phase.WAITING) {
                    return phase;
                }
                switch (exception.code()) {
                    case "authorization_pending" -> {
                    }
                    case "slow_down" -> {
                        long suggested = exception.retryAfterSeconds();
                        intervalSeconds = (int) Math.min(60, Math.max(intervalSeconds + 5L, suggested));
                        nextPollAtMs = clock.getAsLong() + intervalSeconds * 1000L;
                    }
                    case "access_denied" -> finish(Phase.DENIED, exception.code());
                    case "expired_token" -> finish(Phase.EXPIRED, exception.code());
                    case "invalid_grant" -> finish(Phase.FAILED, exception.code());
                    default -> {
                        // Network trouble or a 429/5xx: keep waiting and back off like slow_down.
                        if (exception.status() == 429 || exception.status() >= 500 || exception.status() == 0) {
                            long wait = Math.max(intervalSeconds, exception.retryAfterSeconds());
                            nextPollAtMs = clock.getAsLong() + Math.min(60, wait) * 1000L;
                        } else {
                            finish(Phase.FAILED, exception.code());
                        }
                    }
                }
                return phase;
            }
        }
        synchronized (this) {
            if (polled != generation || phase != Phase.WAITING) {
                return phase;
            }
        }
        if (!"signed_in".equals(result.status()) || !result.hasAccessToken()) {
            synchronized (this) {
                finish(Phase.FAILED, WorkshopApiException.BAD_RESPONSE);
                return phase;
            }
        }
        try {
            session.accept(result, WorkshopSession.Kind.DEVICE, remember);
        } catch (WorkshopApiException exception) {
            synchronized (this) {
                finish(Phase.FAILED, exception.code());
                return phase;
            }
        }
        synchronized (this) {
            account = result.account();
            deviceCode = null;
            phase = Phase.SIGNED_IN;
            return phase;
        }
    }

    /** "Continue": the player recognises the account. */
    public synchronized void confirm() {
        if (phase == Phase.SIGNED_IN) {
            phase = Phase.CONFIRMED;
        }
    }

    /** "This isn't me": revokes the tokens that were just issued and forgets them. */
    public void notMe(CancelToken cancel) {
        synchronized (this) {
            if (phase != Phase.SIGNED_IN) {
                return;
            }
        }
        session.signOut(cancel);
        synchronized (this) {
            phase = Phase.REVOKED;
            account = null;
        }
    }

    public synchronized void cancel() {
        if (phase == Phase.WAITING || phase == Phase.IDLE) {
            generation++;
            finish(Phase.CANCELLED, null);
        }
    }

    private void reset() {
        phase = Phase.IDLE;
        deviceCode = null;
        codes = null;
        account = null;
        errorCode = null;
        expiresAtMs = 0;
        intervalSeconds = 0;
        nextPollAtMs = 0;
    }

    private synchronized void fail(int started, String code) {
        if (started == generation) {
            finish(Phase.FAILED, code);
        }
    }

    private void finish(Phase value, String code) {
        phase = value;
        errorCode = code;
        deviceCode = null;
    }
}

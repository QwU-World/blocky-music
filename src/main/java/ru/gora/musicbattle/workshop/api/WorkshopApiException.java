package ru.gora.musicbattle.workshop.api;

import java.io.IOException;
import java.util.Locale;

/**
 * A failed workshop call with a stable {@link #code()} a screen maps to {@code music_battle.workshop.error.<code>}.
 * Server errors keep the API's code (spec 02 section 1); local failures use the codes below. Messages are English,
 * for logs only, and never contain tokens.
 */
public final class WorkshopApiException extends IOException {
    /** No network consent yet (spec 06 section 4): nothing was sent. */
    public static final String CONSENT_REQUIRED = "consent_required";
    public static final String NETWORK_ERROR = "network_error";
    public static final String TIMEOUT = "timeout";
    public static final String CANCELLED = "cancelled";
    /** The response carries another {@code schema} than this build reads: "Update the mod". */
    public static final String SCHEMA_MISMATCH = "schema_mismatch";
    public static final String BAD_RESPONSE = "bad_response";
    /** A URL outside the workshop origin, or not http(s). */
    public static final String BAD_URL = "bad_url";
    public static final String UNEXPECTED_REDIRECT = "unexpected_redirect";
    /** More bytes than allowed arrived (or were announced). */
    public static final String TOO_LARGE = "too_large";
    public static final String HASH_MISMATCH = "hash_mismatch";
    public static final String SIZE_MISMATCH = "size_mismatch";
    public static final String MANIFEST_MISMATCH = "manifest_mismatch";
    public static final String NOT_SIGNED_IN = "not_signed_in";
    public static final String SERVER_ERROR = "server_error";
    public static final String RATE_LIMITED = "rate_limited";
    public static final String UNAVAILABLE = "unavailable";

    private final String code;
    private final int status;
    private final long retryAfterSeconds;
    private final String field;

    public WorkshopApiException(String code, int status, long retryAfterSeconds, String field, String message) {
        super(message == null || message.isEmpty() ? code : message);
        this.code = code == null || code.isEmpty() ? fromStatus(status) : code;
        this.status = status;
        this.retryAfterSeconds = retryAfterSeconds;
        this.field = field;
    }

    public WorkshopApiException(String code, String message) {
        this(code, 0, -1, null, message);
    }

    public WorkshopApiException(String code, String message, Throwable cause) {
        this(code, 0, -1, null, message);
        initCause(cause);
    }

    /** API error code or local code; lower-case snake case. */
    public String code() {
        return code;
    }

    /** HTTP status, or 0 when the failure happened before a response. */
    public int status() {
        return status;
    }

    /** Seconds from {@code Retry-After} (or the body's {@code retryAfter}), or -1. */
    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }

    /** The request field a 422 refers to, or null. */
    public String field() {
        return field;
    }

    /** Translation key for this error, spec 06 section 11. */
    public String translationKey() {
        return translationKey(code);
    }

    public static String translationKey(String code) {
        String safe = code == null ? "unknown" : code.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
        return "music_battle.workshop.error." + safe;
    }

    /** The code the API would use for a bare status (proxies and crashes answer without our JSON). */
    public static String fromStatus(int status) {
        return switch (status) {
            case 400 -> "bad_request";
            case 401 -> "unauthorized";
            case 403 -> "forbidden";
            case 404 -> "not_found";
            case 409 -> "conflict";
            case 410 -> UNAVAILABLE;
            case 413 -> TOO_LARGE;
            case 422 -> "invalid_request";
            case 429 -> RATE_LIMITED;
            default -> status >= 500 ? SERVER_ERROR : status == 0 ? NETWORK_ERROR : BAD_RESPONSE;
        };
    }

    /** Bearer failures that a refresh or a new proof can fix. */
    public boolean isTokenProblem() {
        return status == 401 && (code.equals("token_expired") || code.equals("token_revoked") || code.equals("invalid_token")
                || code.equals("unauthorized"));
    }
}

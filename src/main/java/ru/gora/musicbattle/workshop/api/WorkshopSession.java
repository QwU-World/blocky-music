package ru.gora.musicbattle.workshop.api;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.LongSupplier;
import ru.gora.musicbattle.workshop.api.WorkshopDtos.AccountSummary;
import ru.gora.musicbattle.workshop.api.WorkshopDtos.AuthResult;
import ru.gora.musicbattle.workshop.api.WorkshopDtos.Me;
import ru.gora.musicbattle.workshop.api.WorkshopDtos.MinecraftRef;

/**
 * The mod's workshop sign-in (spec 06 section 7, 02 section 4). The access token lives in memory only; a refresh
 * token (device sign-in with "remember me") goes to {@link RefreshTokenStore}. Refresh tokens rotate on every use:
 * the new one is written to disk before the call returns, and {@code invalid_grant} deletes the file.
 *
 * <p>A session installs itself as its {@link WorkshopApi}'s token source: use one session per API instance. Thread-safe.
 * Network methods block; call them off the render thread. Listeners run on the calling thread.
 */
public final class WorkshopSession {
    /** Refresh this long before the access token expires. */
    static final long EXPIRY_MARGIN_MS = 60_000L;

    public enum Kind {
        NONE,
        /** Device code sign-in: 1 h access token, optional rotating refresh token. */
        DEVICE,
        /** Minecraft proof: 12 h access token bound to the UUID, not refreshable (prove again). */
        MINECRAFT
    }

    /** What screens show. Never holds a token. */
    public record State(Kind kind, AccountSummary account, MinecraftRef minecraft, long accessExpiresAtMs, boolean remembered) {
        public static final State SIGNED_OUT = new State(Kind.NONE, null, null, 0L, false);

        public boolean signedIn() {
            return kind != Kind.NONE && account != null;
        }
    }

    @FunctionalInterface
    public interface ApiCall<T> {
        T call(WorkshopApi api) throws WorkshopApiException;
    }

    private final WorkshopApi api;
    private final RefreshTokenStore store;
    private final LongSupplier clock;
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private volatile String accessToken;
    private volatile State state = State.SIGNED_OUT;

    public WorkshopSession(WorkshopApi api, RefreshTokenStore store, LongSupplier clock) {
        this.api = api;
        this.store = store;
        this.clock = clock;
        api.setTokenSource(this::currentAccessToken);
    }

    public WorkshopApi api() {
        return api;
    }

    public RefreshTokenStore store() {
        return store;
    }

    public State state() {
        return state;
    }

    public boolean signedIn() {
        return state.signedIn() && accessToken != null;
    }

    /**
     * Replaces the cached account summary with a fresh one, normally the answer to {@code GET /me}.
     *
     * The summary is taken once, when the session starts, and the picture and the display name go stale from then on:
     * a player who changed either on qwu.world kept seeing the old one until they signed out. Anything that already
     * holds a fresh copy passes it through here instead.
     */
    public void rememberAccount(AccountSummary account) {
        if (account == null || !state.signedIn() || account.id() <= 0) {
            return;
        }
        AccountSummary current = state.account();
        if (current != null && current.id() == account.id() && java.util.Objects.equals(current.username(), account.username())
                && java.util.Objects.equals(current.displayName(), account.displayName())
                && java.util.Objects.equals(current.avatarUrl(), account.avatarUrl())) {
            return;
        }
        // Deliberately not on the session's own monitor: a token refresh holds that across a network call with a
        // thirty-second read timeout, and this is called from the render thread. It only rewrites the state record,
        // which is volatile, so its own lock is enough.
        synchronized (accountLock) {
            state = new State(state.kind(), account, state.minecraft(), state.accessExpiresAtMs(), state.remembered());
        }
        fire();
    }

    public void addListener(Runnable listener) {
        listeners.add(listener);
    }

    public void removeListener(Runnable listener) {
        listeners.remove(listener);
    }

    /** The bearer for {@link WorkshopApi}; null when signed out or expired. Not for anything else. */
    String currentAccessToken() {
        String token = accessToken;
        if (token == null) {
            return null;
        }
        return clock.getAsLong() < state.accessExpiresAtMs() ? token : null;
    }

    /**
     * Accepts tokens from a device sign-in, a refresh, a Minecraft proof or a link. A refresh token is stored only
     * when {@code remember} is true (the player ticked "Remember me"); otherwise any stored file is deleted.
     */
    public synchronized void accept(AuthResult grant, Kind kind, boolean remember) throws WorkshopApiException {
        if (grant == null || !grant.hasAccessToken() || kind == Kind.NONE) {
            throw new WorkshopApiException(WorkshopApiException.BAD_RESPONSE, "no access token in the answer");
        }
        long now = clock.getAsLong();
        long expiresAt = now + Math.max(60L, grant.expiresIn()) * 1000L;
        boolean remembered = false;
        AccountSummary account = grant.account() != null ? grant.account() : state.account();
        if (kind == Kind.DEVICE && remember && grant.refreshToken() != null) {
            try {
                store.write(account == null ? 0 : account.id(), grant.refreshToken(), Instant.ofEpochMilli(now));
                remembered = true;
            } catch (IOException exception) {
                // A token we could not persist must not be presented later in a stale form.
                store.delete();
            }
        } else {
            store.delete();
        }
        accessToken = grant.accessToken();
        state = new State(kind, account, kind == Kind.MINECRAFT ? grant.minecraft() : null, expiresAt, remembered);
        fire();
    }

    /**
     * Uses the stored refresh token, if any, to get a new access token and rotates the stored one. Returns true when
     * signed in afterwards. {@code invalid_grant} deletes the file and signs out; network trouble keeps the file.
     */
    public synchronized boolean refresh(CancelToken cancel) throws WorkshopApiException {
        Optional<RefreshTokenStore.Stored> stored = store.read();
        if (stored.isEmpty()) {
            return false;
        }
        AuthResult grant;
        try {
            grant = api.refresh(stored.get().refreshToken(), cancel);
        } catch (WorkshopApiException exception) {
            if (exception.code().equals("invalid_grant") || exception.status() == 401) {
                store.delete();
                clearLocal();
            }
            throw exception;
        }
        if (!grant.hasAccessToken() || grant.refreshToken() == null) {
            store.delete();
            throw new WorkshopApiException(WorkshopApiException.BAD_RESPONSE, "refresh answer without tokens");
        }
        long now = clock.getAsLong();
        try {
            store.write(stored.get().qwuUserId(), grant.refreshToken(), Instant.ofEpochMilli(now));
        } catch (IOException exception) {
            store.delete();
        }
        accessToken = grant.accessToken();
        AccountSummary account = state.account();
        state = new State(Kind.DEVICE, account, null, now + Math.max(60L, grant.expiresIn()) * 1000L, store.exists());
        if (account == null) {
            try {
                Me me = api.me(cancel);
                if (me.account() != null) {
                    state = new State(Kind.DEVICE, new AccountSummary(me.account().id(), me.account().username(),
                            me.account().displayName(), me.account().avatarUrl()), null, state.accessExpiresAtMs(), state.remembered());
                }
            } catch (WorkshopApiException exception) {
                if (exception.isTokenProblem()) {
                    clearLocal();
                    throw exception;
                }
            }
        }
        fire();
        return signedIn();
    }

    /** Restores a remembered sign-in on first use. True when signed in afterwards. */
    public boolean restore(CancelToken cancel) throws WorkshopApiException {
        if (signedIn()) {
            return true;
        }
        return store.exists() && refresh(cancel);
    }

    /**
     * Runs an authenticated call: refreshes an expiring device token first, and on a token failure refreshes once
     * and retries. Without a usable token it fails with {@link WorkshopApiException#NOT_SIGNED_IN}.
     */
    public <T> T authorized(ApiCall<T> call) throws WorkshopApiException {
        ensureFresh();
        if (currentAccessToken() == null) {
            throw new WorkshopApiException(WorkshopApiException.NOT_SIGNED_IN, "sign in first");
        }
        try {
            return call.call(api);
        } catch (WorkshopApiException exception) {
            if (!exception.isTokenProblem()) {
                throw exception;
            }
            synchronized (this) {
                accessToken = null;
            }
            if (state.kind() == Kind.DEVICE && store.exists() && refresh(CancelToken.none())) {
                return call.call(api);
            }
            clearLocal();
            throw exception;
        }
    }

    private void ensureFresh() throws WorkshopApiException {
        State current = state;
        if (current.kind() == Kind.DEVICE && accessToken != null && store.exists()
                && clock.getAsLong() > current.accessExpiresAtMs() - EXPIRY_MARGIN_MS) {
            refresh(CancelToken.none());
        }
    }

    /**
     * Signs out: revokes the current token family on the server (and the stored refresh token), deletes the file
     * and forgets everything locally, even when the network call fails.
     */
    public void signOut(CancelToken cancel) {
        Optional<RefreshTokenStore.Stored> stored = store.read();
        try {
            if (accessToken != null || stored.isPresent()) {
                api.revoke(stored.map(RefreshTokenStore.Stored::refreshToken).orElse(null), cancel);
            }
        } catch (WorkshopApiException ignored) {
            // Local sign-out still happens; the server expires the family on its own.
        } finally {
            store.delete();
            clearLocal();
        }
    }

    /** Forgets the in-memory sign-in without touching the server or the file. */
    public synchronized void clearLocal() {
        boolean changed = accessToken != null || state.kind() != Kind.NONE;
        accessToken = null;
        state = State.SIGNED_OUT;
        if (changed) {
            fire();
        }
    }

    /**
     * Bumped whenever who is signed in changes.
     *
     * A screen that was drawn for a signed-out reader keeps the permissions it was given - no composer, no rating,
     * no favourite - until it is told otherwise. Comparing this against the number it last drew with is how a page
     * notices that the person signed in while it was open.
     */
    private volatile long stateVersion;
    /** Guards the account summary alone; see {@link #rememberAccount}. */
    private final Object accountLock = new Object();

    /** The current identity generation; see {@link #stateVersion}. */
    public long stateVersion() {
        return stateVersion;
    }

    private void fire() {
        stateVersion++;
        for (Runnable listener : listeners) {
            try {
                listener.run();
            } catch (RuntimeException ignored) {
                // A broken listener must not break sign-in.
            }
        }
    }
}

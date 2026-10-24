package ru.gora.musicbattle.client.workshop;

import com.mojang.authlib.exceptions.AuthenticationException;
import com.mojang.authlib.exceptions.AuthenticationUnavailableException;
import com.mojang.authlib.exceptions.ForcedUsernameChangeException;
import com.mojang.authlib.exceptions.InsufficientPrivilegesException;
import com.mojang.authlib.exceptions.InvalidCredentialsException;
import com.mojang.authlib.exceptions.UserBannedException;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.minecraft.client.gui.screens.ConnectScreen;
import ru.gora.musicbattle.MusicBattleMod;
import ru.gora.musicbattle.mixin.client.MinecraftConnectionAccessor;
import ru.gora.musicbattle.workshop.api.AutoSignInGate;
import ru.gora.musicbattle.workshop.api.CancelToken;
import ru.gora.musicbattle.workshop.api.MinecraftSignIn;

/**
 * The Minecraft side of the workshop proof (spec 06 section 7.2, research sections 1.4 to 1.6, 2.4).
 *
 * <p><b>This is the only class in the mod that reads {@code User.getAccessToken()}.</b> The token goes straight into
 * {@code MinecraftSessionService.joinServer} (authlib posts it to Mojang's session server) and nowhere else: never
 * stored, logged or given to {@code WorkshopApi}. The {@code workshop-auth} check scans the sources for any other
 * reference, and {@code workshop-api} captures every workshop request to prove the token is absent.
 *
 * <p>All proofs run on one single-thread executor, so our joins never overlap. No proof starts while a server
 * connection is being set up ({@code ConnectScreen} or a pending login connection), because a join landing between
 * vanilla's join and the server's {@code hasJoined} would kick the player. At most one automatic attempt per launch,
 * made lazily when the Workshop first opens; one automatic retry after 5 s when the join was superseded. Server
 * packets never start any of this.
 */
public final class MinecraftProof implements MinecraftSignIn.Joiner {
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "Blocky Music Workshop sign-in");
        thread.setDaemon(true);
        return thread;
    });
    private static final AutoSignInGate AUTO_GATE = new AutoSignInGate();
    static final MinecraftProof JOINER = new MinecraftProof();

    private MinecraftProof() {
    }

    /** The serialised executor every proof and link step runs on. */
    public static ExecutorService executor() {
        return EXECUTOR;
    }

    /** A Microsoft account, the only kind Mojang's session server can verify. */
    public static boolean microsoftAccount() {
        return Minecraft.getInstance().getUser().getType() == User.Type.MSA;
    }

    public static MinecraftSignIn.Player player() {
        User user = Minecraft.getInstance().getUser();
        return new MinecraftSignIn.Player(user.getProfileId(), user.getName());
    }

    /** The player for the Workshop, or null when this account's name is not one Mojang would accept. */
    public static MinecraftSignIn.Player playerOrNull() {
        try {
            return player();
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    /** True while a server connection is being established; proofs must wait. */
    public static boolean loginInProgress() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof ConnectScreen) {
            return true;
        }
        return ((MinecraftConnectionAccessor) minecraft).musicBattle$pendingConnection() != null;
    }

    @Override
    public void join(UUID profileId, String serverId) throws MinecraftSignIn.JoinFailure {
        if (loginInProgress()) {
            throw new MinecraftSignIn.JoinFailure(MinecraftSignIn.JoinFailure.Kind.OTHER, "a server login is in progress");
        }
        Minecraft minecraft = Minecraft.getInstance();
        User user = minecraft.getUser();
        if (!profileId.equals(user.getProfileId())) {
            throw new MinecraftSignIn.JoinFailure(MinecraftSignIn.JoinFailure.Kind.INVALID_SESSION, "the account changed");
        }
        try {
            minecraft.getMinecraftSessionService().joinServer(profileId, user.getAccessToken(), serverId);
        } catch (AuthenticationUnavailableException exception) {
            throw new MinecraftSignIn.JoinFailure(MinecraftSignIn.JoinFailure.Kind.UNAVAILABLE, "session servers unavailable");
        } catch (InvalidCredentialsException exception) {
            throw new MinecraftSignIn.JoinFailure(MinecraftSignIn.JoinFailure.Kind.INVALID_SESSION, "invalid session");
        } catch (InsufficientPrivilegesException exception) {
            throw new MinecraftSignIn.JoinFailure(MinecraftSignIn.JoinFailure.Kind.INSUFFICIENT_PRIVILEGES, "multiplayer disabled");
        } catch (UserBannedException | ForcedUsernameChangeException exception) {
            throw new MinecraftSignIn.JoinFailure(MinecraftSignIn.JoinFailure.Kind.BANNED, "multiplayer blocked");
        } catch (AuthenticationException exception) {
            throw new MinecraftSignIn.JoinFailure(MinecraftSignIn.JoinFailure.Kind.OTHER, "join failed");
        } catch (RuntimeException exception) {
            throw new MinecraftSignIn.JoinFailure(MinecraftSignIn.JoinFailure.Kind.OTHER, "join failed");
        }
    }

    /**
     * The one automatic sign-in of this launch, when the Workshop opens (render thread). {@code done} runs on the
     * sign-in executor with the result, or not at all when no attempt started.
     */
    static void autoSignInOnce(WorkshopClient client, Consumer<MinecraftSignIn.ProofResult> done) {
        AutoSignInGate.Decision decision = AUTO_GATE.tryStart(client.networkAllowed(), microsoftAccount(), loginInProgress(),
                client.session().signedIn());
        if (decision != AutoSignInGate.Decision.STARTED) {
            return;
        }
        MinecraftSignIn.Player player;
        try {
            player = player();
        } catch (IllegalArgumentException invalidName) {
            return;
        }
        CompletableFuture.runAsync(() -> {
            MinecraftSignIn.ProofResult result = client.signIn().prove(player, MinecraftSignIn.PURPOSE_SESSION, CancelToken.none());
            if ("mc_not_verified".equals(result.errorCode())) {
                // Another join (the player connecting somewhere, another mod) superseded ours: one retry.
                try {
                    Thread.sleep(5_000L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (!loginInProgress()) {
                    result = client.signIn().prove(player, MinecraftSignIn.PURPOSE_SESSION, CancelToken.none());
                }
            }
            MusicBattleMod.LOGGER.info("Workshop Minecraft sign-in: {}", result.outcome()
                    + (result.errorCode() == null ? "" : " (" + result.errorCode() + ")"));
            if (done != null) {
                done.accept(result);
            }
        }, EXECUTOR);
    }

    /** A player-initiated proof (Account view), on the serialised executor. */
    public static CompletableFuture<MinecraftSignIn.ProofResult> proveNow(WorkshopClient client, String purpose) {
        return CompletableFuture.supplyAsync(() -> {
            if (!client.networkAllowed()) {
                return new MinecraftSignIn.ProofResult(MinecraftSignIn.Outcome.FAILED, null, null, "consent_required", -1);
            }
            if (loginInProgress()) {
                return new MinecraftSignIn.ProofResult(MinecraftSignIn.Outcome.FAILED, null, null, "login_in_progress", -1);
            }
            return client.signIn().prove(player(), purpose, CancelToken.none());
        }, EXECUTOR);
    }
}

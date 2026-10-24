package ru.gora.musicbattle.client.workshop;

import java.io.IOException;
import java.net.Proxy;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.neoforged.fml.loading.FMLPaths;
import ru.gora.musicbattle.MusicBattleMod;
import ru.gora.musicbattle.client.ClientBattleController;
import ru.gora.musicbattle.client.ui.UiHttp;
import ru.gora.musicbattle.server.MusicBattleTracks;
import ru.gora.musicbattle.workshop.BeatmapManifest;
import ru.gora.musicbattle.workshop.BeatmapPackage;
import ru.gora.musicbattle.workshop.WorkshopCatalog;
import ru.gora.musicbattle.workshop.api.CancelToken;
import ru.gora.musicbattle.workshop.api.ClientLabels;
import ru.gora.musicbattle.workshop.api.DeviceFlow;
import ru.gora.musicbattle.workshop.api.InstalledCheck;
import ru.gora.musicbattle.workshop.api.MinecraftSignIn;
import ru.gora.musicbattle.workshop.api.RefreshTokenStore;
import ru.gora.musicbattle.workshop.api.WorkshopApi;
import ru.gora.musicbattle.workshop.api.WorkshopApiException;
import ru.gora.musicbattle.workshop.api.WorkshopConfig;
import ru.gora.musicbattle.workshop.api.WorkshopDownloader;
import ru.gora.musicbattle.workshop.api.WorkshopInbox;
import ru.gora.musicbattle.workshop.api.WorkshopSession;

/**
 * The game's Workshop wiring (spec 06 sections 4, 5, 7, 8): one instance per client holding the consent file, the API
 * client with Minecraft's proxy, the session, the catalog, installed-check, the downloader and the inbox, plus the
 * executors they run on. Screens (M2b) take everything from here.
 *
 * <p>Threads: downloads on a two-thread pool, installs on {@code Util.nonCriticalIoPool()}, small IO (catalog scans,
 * inbox, installed-check, session restore) on one "Workshop IO" thread, Minecraft proofs on {@link MinecraftProof}'s
 * single thread. Listener callbacks are moved to the render thread with {@code Minecraft.execute}.
 *
 * <p>Nothing here contacts the network before consent: {@link WorkshopApi} refuses every request until
 * {@link WorkshopConfig#networkAllowed()} is true.
 */
public final class WorkshopClient {
    /** Inbox scan period while the game runs (owner decision 17.09.2026), in client ticks. */
    static final int INBOX_SCAN_TICKS = 100;
    private static volatile WorkshopClient instance;

    private final WorkshopConfig config;
    private final WorkshopApi api;
    private final WorkshopSession session;
    private final MinecraftSignIn signIn;
    private final DeviceFlow deviceFlow;
    private final WorkshopCatalog catalog;
    private final InstalledCheck installedCheck;
    private final WorkshopDownloader downloader;
    private final WorkshopInbox inbox;
    private final ExecutorService io;
    private final ExecutorService network;
    private final ExecutorService ui;
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean scanRunning = new AtomicBoolean();
    private final AtomicBoolean startupCheckDone = new AtomicBoolean();
    private final AtomicBoolean firstOpenDone = new AtomicBoolean();
    private final AtomicBoolean participationAsked = new AtomicBoolean();
    private volatile ru.gora.musicbattle.workshop.api.WorkshopDtos.Meta meta;
    private volatile ru.gora.musicbattle.workshop.api.WorkshopDtos.Rules participation;
    private int tickCounter;

    private WorkshopClient() {
        Path tracksRoot = MusicBattleTracks.tracksRootDirectory();
        config = WorkshopConfig.load(FMLPaths.CONFIGDIR.get().resolve(WorkshopConfig.FILE_NAME));
        api = new WorkshopApi(WorkshopApi.Options.of(WorkshopApi.configuredBaseUri(), WorkshopClient::proxy, UiHttp.userAgent(),
                config::networkAllowed));
        // Covers and avatars arrive as site paths; this is what they are joined to.
        ru.gora.musicbattle.client.ui.CoverCache.origin(api.baseUri().toString());
        session = new WorkshopSession(api, new RefreshTokenStore(RefreshTokenStore.defaultPath(tracksRoot)), System::currentTimeMillis);
        String label = ClientLabels.forGame(System.getProperty("os.name"));
        signIn = new MinecraftSignIn(api, session, MinecraftProof.JOINER, new SecureRandom(), System::currentTimeMillis, label);
        deviceFlow = new DeviceFlow(api, session, System::currentTimeMillis);
        catalog = new WorkshopCatalog(tracksRoot);
        installedCheck = new InstalledCheck(api, catalog, System::currentTimeMillis);
        io = Executors.newSingleThreadExecutor(named("Blocky Music Workshop IO"));
        AtomicInteger uiCounter = new AtomicInteger();
        ui = Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "Blocky Music Workshop request " + uiCounter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        AtomicInteger counter = new AtomicInteger();
        network = Executors.newFixedThreadPool(WorkshopDownloader.MAX_CONCURRENT, runnable -> {
            Thread thread = new Thread(runnable, "Blocky Music Workshop download " + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        downloader = new WorkshopDownloader(api, catalog, installedCheck, network, Util.nonCriticalIoPool(), System::currentTimeMillis);
        downloader.addListener(new WorkshopToasts.DownloadToasts());
        downloader.addListener(new WorkshopRequests.RequestListener(this));
        downloader.addListener(new WorkshopDownloader.Listener() {
            @Override
            public void statusChanged(long setId, WorkshopDownloader.Status status) {
            }

            @Override
            public void installed(long setId, BeatmapManifest manifest, BeatmapPackage.Outcome outcome) {
                // The index of local content by hash is what lets a battle play the copy on this disk instead of
                // fetching the same audio from the server all over again. The downloader cannot refresh it itself:
                // it is built to run without Minecraft classes, and this index is a client thing.
                ru.gora.musicbattle.client.ClientTrackAssets.refreshHashIndex();
            }
        });
        inbox = new WorkshopInbox(tracksRoot, System::currentTimeMillis);
    }

    public static WorkshopClient get() {
        WorkshopClient current = instance;
        if (current == null) {
            synchronized (WorkshopClient.class) {
                current = instance;
                if (current == null) {
                    current = new WorkshopClient();
                    instance = current;
                }
            }
        }
        return current;
    }

    private static java.util.concurrent.ThreadFactory named(String name) {
        return runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        };
    }

    private static Proxy proxy() {
        Proxy proxy = Minecraft.getInstance().getProxy();
        return proxy == null ? Proxy.NO_PROXY : proxy;
    }

    public WorkshopConfig config() {
        return config;
    }

    public WorkshopApi api() {
        return api;
    }

    public WorkshopSession session() {
        return session;
    }

    public MinecraftSignIn signIn() {
        return signIn;
    }

    public DeviceFlow deviceFlow() {
        return deviceFlow;
    }

    public WorkshopCatalog catalog() {
        return catalog;
    }

    public InstalledCheck installedCheck() {
        return installedCheck;
    }

    public WorkshopDownloader downloader() {
        return downloader;
    }

    public WorkshopInbox inbox() {
        return inbox;
    }

    /** The executor for small Workshop IO (never the render thread). */
    public ExecutorService io() {
        return io;
    }

    /** Two threads for screen requests (search, set detail, comments), so a slow call never blocks the others. */
    public ExecutorService requests() {
        return ui;
    }

    /** Preview volume from {@code config/music_battle-workshop.json} (spec review of 06 section 6). */
    public float previewVolume() {
        return (float) Math.max(0.0, Math.min(1.0, config.number("previewVolume", ru.gora.musicbattle.client.ui.PreviewPlayer.DEFAULT_VOLUME)));
    }

    public void setPreviewVolume(float value) {
        try {
            config.setNumber("previewVolume", Math.max(0.0F, Math.min(1.0F, value)));
        } catch (IOException exception) {
            MusicBattleMod.LOGGER.warn("Unable to save the Workshop preview volume: {}", exception.getMessage());
        }
    }

    /** {@code GET /api/v1/meta}, fetched once per session (genres, limits, terms version, report reasons). */
    public ru.gora.musicbattle.workshop.api.WorkshopDtos.Meta meta() {
        return meta;
    }

    /** Loads {@code /meta} in the background when it is missing; {@code done} runs on the calling executor. */
    public void ensureMeta(Runnable done) {
        if (meta != null || !networkAllowed()) {
            if (done != null) {
                done.run();
            }
            return;
        }
        ui.execute(() -> {
            try {
                meta = api.meta(CancelToken.none());
            } catch (WorkshopApiException exception) {
                MusicBattleMod.LOGGER.debug("Workshop meta failed: {}", exception.code());
            }
            if (done != null) {
                done.run();
            }
        });
    }

    /**
     * Asks the site for its metadata again.
     *
     * {@link #ensureMeta} fetches once per session and swallows a failure, which is right for a listing that can do
     * without it - but the upload acceptance cannot: it is recorded against document versions, and without them
     * there is nothing honest to send. Anything that needs them can ask for another go.
     */
    public void refreshMeta() {
        if (!networkAllowed()) {
            return;
        }
        ui.execute(() -> {
            try {
                meta = api.meta(CancelToken.none());
            } catch (WorkshopApiException exception) {
                MusicBattleMod.LOGGER.debug("Workshop meta failed again: {}", exception.code());
            }
        });
    }

    public boolean networkAllowed() {
        return config.networkAllowed();
    }

    // ---------------------------------------------------------------- the acceptance of the rules (Terms 3.1)

    /**
     * The lighter acceptance (Workshop Terms and Community Rules) as {@code GET /api/v1/me} last reported it, or null
     * while nothing has said anything about it. Every screen reads the same answer, so a player is asked once.
     */
    public ru.gora.musicbattle.workshop.api.WorkshopDtos.Rules participation() {
        return participation;
    }

    /** True once the workshop has said this account still owes the acceptance a comment or a rating needs. */
    public boolean participationPending() {
        ru.gora.musicbattle.workshop.api.WorkshopDtos.Rules value = participation;
        return value != null && value.pending();
    }

    /** Keeps what a {@code /me} answer or a fresh acceptance said; null (an older server) changes nothing. */
    public void rememberParticipation(ru.gora.musicbattle.workshop.api.WorkshopDtos.Rules value) {
        if (value != null) {
            participation = value;
            participationAsked.set(true);
        }
    }

    /**
     * The server refused an action for the acceptance: that is an answer too, and the screens may say so. What
     * {@code /me} already told us is kept (when it was accepted, whether a document moved and what changed), because
     * the dialog shows that line; only the verdict moves to "still owed".
     */
    public void participationRefused() {
        ru.gora.musicbattle.workshop.api.WorkshopDtos.Rules known = participation;
        participation = known == null
                ? ru.gora.musicbattle.workshop.api.WorkshopDtos.Rules.UNKNOWN
                : new ru.gora.musicbattle.workshop.api.WorkshopDtos.Rules(false, known.acceptedAt(), known.termsVersion(),
                        known.rulesVersion(), known.needsReaccept(), known.changes());
        participationAsked.set(true);
    }

    /** Forgets the acceptance state on sign-out, so the next account is not judged by the previous one's answer. */
    public void forgetParticipation() {
        participation = null;
        participationAsked.set(false);
    }

    /**
     * Reads {@code /me} once per session so a screen can offer the acceptance before the player writes anything and is
     * refused. Quiet by design: a failure leaves the state unknown and nothing is shown. {@code done} runs on the
     * request executor, whether or not the call succeeded.
     */
    public void ensureParticipation(Runnable done) {
        if (!session.signedIn() || !networkAllowed() || !participationAsked.compareAndSet(false, true)) {
            if (done != null) {
                done.run();
            }
            return;
        }
        ui.execute(() -> {
            try {
                ru.gora.musicbattle.workshop.api.WorkshopDtos.Me me = session.authorized(api -> api.me(CancelToken.none()));
                rememberParticipation(me.participation());
            } catch (WorkshopApiException exception) {
                MusicBattleMod.LOGGER.debug("Workshop rules state unavailable: {}", exception.code());
            }
            if (done != null) {
                done.run();
            }
        });
    }

    // ---------------------------------------------------------------- entry points

    /**
     * Opens the Workshop (key, title screen, settings). The consent screen comes first the first time, and also when a
     * helper request is waiting for network access the player has not granted (spec 07 section 8): a click on the site
     * would otherwise sit in the inbox with nothing to explain it.
     */
    public static void open(Screen parent) {
        WorkshopClient client = get();
        Minecraft minecraft = Minecraft.getInstance();
        if (!client.config.consentAnswered() || (!client.networkAllowed() && WorkshopRequests.hasPending())) {
            minecraft.setScreen(new WorkshopConsentScreen(parent, () -> minecraft.setScreen(new WorkshopScreen(parent)),
                    () -> minecraft.setScreen(new WorkshopScreen(parent))));
            return;
        }
        minecraft.setScreen(new WorkshopScreen(parent));
    }

    /** Stores the consent answer (render thread). */
    public void setNetworkConsent(boolean allowed) throws IOException {
        config.setNetworkConsent(allowed, Instant.now());
        if (!allowed) {
            downloader.cancelAll();
        }
        WorkshopRequests.onConsentAnswered(this, allowed);
    }

    /** Called by a Workshop screen when it opens: inbox scan, lazy sign-in, installed-check (at most every 10 min). */
    void onWorkshopOpened() {
        scanInbox();
        if (!networkAllowed()) {
            return;
        }
        io.execute(() -> {
            try {
                session.restore(CancelToken.none());
            } catch (WorkshopApiException exception) {
                MusicBattleMod.LOGGER.debug("Workshop session restore failed: {}", exception.code());
            }
            refreshInstalledStateNow();
        });
        if (firstOpenDone.compareAndSet(false, true)) {
            MinecraftProof.autoSignInOnce(this, null);
        }
    }

    /**
     * Rescans the installed sets and asks the workshop about the ones no answer covers yet. Queued on the Workshop IO
     * thread, so it is safe to call from anywhere.
     *
     * <p>A map that arrived from a file, a friend or the inbox carries no installed-check answer, and without one a
     * battle may not offer it (spec 08 A5.1). The player can do nothing about that themselves, so whatever installs a
     * map locally asks for this straight away instead of leaving it to the next time the Workshop happens to open.
     */
    public void refreshInstalledState() {
        io.execute(this::refreshInstalledStateNow);
    }

    /** The Workshop IO thread's half of {@link #refreshInstalledState}. */
    private void refreshInstalledStateNow() {
        catalog.refresh();
        ru.gora.musicbattle.client.ClientTrackAssets.refreshHashIndex();
        if (!networkAllowed() || catalog.size() == 0) {
            return;
        }
        try {
            installedCheck.maybeRun(CancelToken.none());
        } catch (WorkshopApiException exception) {
            MusicBattleMod.LOGGER.debug("Workshop installed-check failed: {}", exception.code());
        }
    }

    // ---------------------------------------------------------------- ticking

    /** Client tick (render thread): startup work once, then an inbox scan every 5 seconds. */
    public void tick() {
        if (started.compareAndSet(false, true)) {
            io.execute(this::startup);
        }
        WorkshopDrops.tick();
        if (++tickCounter >= INBOX_SCAN_TICKS) {
            tickCounter = 0;
            if (!ClientBattleController.blocksParticipantInput()) {
                scanInbox();
            }
        }
    }

    private void startup() {
        try {
            // MusicBattleMod already ran the one-time track layout migration at construction.
            inbox.ensureDirectory();
        } catch (IOException | RuntimeException exception) {
            MusicBattleMod.LOGGER.warn("Unable to prepare the Workshop folders: {}", exception.getMessage());
        }
        catalog.refresh();
        downloader.cleanStaleParts();
        downloader.prunePackages();
        scanInboxNow();
        if (networkAllowed() && catalog.size() > 0 && startupCheckDone.compareAndSet(false, true)) {
            try {
                installedCheck.maybeRun(CancelToken.none());
            } catch (WorkshopApiException exception) {
                MusicBattleMod.LOGGER.debug("Workshop installed-check at start failed: {}", exception.code());
            }
        }
    }

    /** Queues one inbox scan unless one is already running. */
    public void scanInbox() {
        if (scanRunning.compareAndSet(false, true)) {
            io.execute(() -> {
                try {
                    scanInboxNow();
                } finally {
                    scanRunning.set(false);
                }
            });
        }
    }

    private void scanInboxNow() {
        for (Path file : inbox.scan()) {
            WorkshopDrops.processInboxFile(this, file);
        }
        // Helper requests (spec 07 section 8): tiny JSON files asking the mod to download a set itself.
        for (Path file : inbox.scanRequests()) {
            WorkshopRequests.process(this, file);
        }
    }

    /** Installs a validated package off-thread and refreshes the catalog; completes on the install pool. */
    CompletableFuture<BeatmapPackage.InstallResult> install(Path archive) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                BeatmapPackage.InstallResult result = BeatmapPackage.install(archive, catalog.tracksRoot());
                catalog.refresh();
                return result;
            } catch (IOException exception) {
                throw new java.util.concurrent.CompletionException(exception);
            }
        }, Util.nonCriticalIoPool());
    }

    /** Reads a package manifest off-thread. */
    CompletableFuture<BeatmapManifest> readManifest(Path archive) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return BeatmapPackage.readManifest(archive);
            } catch (IOException exception) {
                throw new java.util.concurrent.CompletionException(exception);
            }
        }, Util.nonCriticalIoPool());
    }
}

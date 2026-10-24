package ru.gora.musicbattle.client.workshop;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import ru.gora.musicbattle.MusicBattleMod;
import ru.gora.musicbattle.workshop.BeatmapManifest;
import ru.gora.musicbattle.workshop.BeatmapPackage;
import ru.gora.musicbattle.workshop.api.WorkshopApiException;
import ru.gora.musicbattle.workshop.api.WorkshopDownloader;
import ru.gora.musicbattle.workshop.api.WorkshopRequest;

/**
 * Install requests from the QwU Workshop Helper (spec 07 section 8, wave I1): {@code <setId>.blockymap-request} files
 * in {@code music_battle_tracks/workshop_inbox/}, written when the player clicks "Install in game" on the site or
 * opens a {@code blockymusic://install/<id>} link.
 *
 * <p>The helper only writes the file. Everything that reaches the network happens here, through the normal
 * {@link WorkshopDownloader}, so a request obeys the same consent, the same queue and the same limits as pressing
 * Install in the game:
 *
 * <ul>
 *   <li><b>No consent yet.</b> The file stays where it is and {@link #hasPending()} turns true, so the consent screen
 *       comes up the next time the Workshop opens; one toast per session says so. Nothing is downloaded.</li>
 *   <li><b>Already installed.</b> The request is deleted, a toast says so, and the set page opens if the Workshop is
 *       on screen.</li>
 *   <li><b>Otherwise.</b> The set is queued for download. The existing download toasts report it. When it installs,
 *       the request file is deleted; when it fails for good it moves to {@code rejected/} with the reason. A network
 *       failure is retried on a later scan, at most {@value #MAX_ATTEMPTS} times.</li>
 *   <li><b>Cancelled.</b> Pressing Install again stops the download, and that answers the request: the file is
 *       deleted, so the next click on the site starts the same set over from scratch.</li>
 *   <li><b>Invalid file.</b> Straight to {@code rejected/} with a {@code .txt} reason, like a broken package.</li>
 * </ul>
 *
 * <p>Requests written while the game was closed are picked up by the startup scan, so a click on the site works
 * whether Minecraft is running or not.
 */
public final class WorkshopRequests {
    /** How many times a request survives a network failure before it is rejected. */
    static final int MAX_ATTEMPTS = 3;
    /** How long a set waits after a failure before the scan offers its request again. */
    static final long RETRY_DELAY_MS = 60_000;

    /** Set ids whose download this class started, and the request file waiting for the outcome. */
    private static final Map<Long, Path> IN_FLIGHT = new ConcurrentHashMap<>();
    /** Set id → when the scan may try it again, after a failure that might pass. */
    private static final Map<Long, Long> RETRY_AFTER = new ConcurrentHashMap<>();
    private static final Map<Long, Integer> ATTEMPTS = new ConcurrentHashMap<>();
    /** Requests held back because network consent is missing. */
    private static final Set<Path> WAITING_FOR_CONSENT = ConcurrentHashMap.newKeySet();
    /** Request files that could not be deleted or moved: never offered twice in one session. */
    private static final Set<Path> GIVEN_UP = ConcurrentHashMap.newKeySet();
    private static final AtomicBoolean CONSENT_TOAST_SHOWN = new AtomicBoolean();

    private WorkshopRequests() {
    }

    /** Whether a request is waiting for the player to allow network access. */
    public static boolean hasPending() {
        return !WAITING_FOR_CONSENT.isEmpty();
    }

    /**
     * The consent answer changed (render thread). "Allow" releases everything that was waiting. "Do not allow" rejects
     * it: keeping the requests would put the consent screen in front of the player every single time they open the
     * Workshop, and a reason file in {@code rejected/} says plainly why nothing was installed.
     */
    static void onConsentAnswered(WorkshopClient client, boolean allowed) {
        List<Path> waiting = List.copyOf(WAITING_FOR_CONSENT);
        WAITING_FOR_CONSENT.clear();
        CONSENT_TOAST_SHOWN.set(false);
        if (allowed) {
            client.scanInbox();
            return;
        }
        if (!waiting.isEmpty()) {
            client.io().execute(() -> {
                for (Path file : waiting) {
                    reject(client, file, WorkshopApiException.CONSENT_REQUIRED, "network access was declined");
                }
            });
        }
    }

    /** One request file (Workshop IO thread). */
    static void process(WorkshopClient client, Path file) {
        if (GIVEN_UP.contains(file) || IN_FLIGHT.containsValue(file)) {
            return;
        }
        WorkshopRequest request;
        try {
            request = WorkshopRequest.read(file, System.currentTimeMillis());
        } catch (WorkshopRequest.InvalidException invalid) {
            reject(client, file, invalid.reason().code(), invalid.getMessage());
            return;
        } catch (IOException exception) {
            return; // Unreadable right now (still being written, locked): the next scan retries.
        }
        long setId = request.setId();
        Long notBefore = RETRY_AFTER.get(setId);
        if (notBefore != null && System.currentTimeMillis() < notBefore) {
            return;
        }
        if (!client.networkAllowed()) {
            WAITING_FOR_CONSENT.add(file);
            if (CONSENT_TOAST_SHOWN.compareAndSet(false, true)) {
                WorkshopToasts.show(Component.translatable("message.music_battle.workshop.request_waiting", String.valueOf(setId)),
                        Component.translatable("music_battle.workshop.error.consent_required"));
            }
            return;
        }
        WAITING_FOR_CONSENT.remove(file);
        WorkshopDownloader downloader = client.downloader();
        WorkshopDownloader.Status status = downloader.status(setId);
        switch (status.state()) {
            case INSTALLED -> {
                consume(client, file);
                WorkshopToasts.show(Component.translatable("message.music_battle.workshop.request_installed", String.valueOf(setId)),
                        Component.translatable("message.music_battle.workshop.request_open_hint"));
                openSetPage(setId);
            }
            case QUEUED, DOWNLOADING, INSTALLING -> {
                // A download is already running for this set (the player pressed Install, or an earlier request):
                // let it finish and drop the duplicate request.
                consume(client, file);
                openSetPage(setId);
            }
            default -> {
                IN_FLIGHT.put(setId, file);
                MusicBattleMod.LOGGER.info("Workshop: installing set {} from a helper request ({})", setId, request.source());
                downloader.enqueue(setId);
                openSetPage(setId);
            }
        }
    }

    /** The set page, when a Workshop screen is already open (render thread hop). */
    private static void openSetPage(long setId) {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> {
            if (minecraft.screen instanceof WorkshopScreen screen) {
                screen.openSetPage(setId, null);
            }
        });
    }

    private static void consume(WorkshopClient client, Path file) {
        try {
            client.inbox().consume(file);
        } catch (IOException exception) {
            GIVEN_UP.add(file);
            MusicBattleMod.LOGGER.warn("Workshop: could not remove the request {}: {}", file.getFileName(), exception.getMessage());
        }
    }

    private static void reject(WorkshopClient client, Path file, String code, String detail) {
        try {
            client.inbox().reject(file, code, detail);
        } catch (IOException exception) {
            GIVEN_UP.add(file);
        }
        WorkshopToasts.problem(Component.translatable("message.music_battle.workshop.inbox_rejected", String.valueOf(file.getFileName())),
                WorkshopText.error(code));
    }

    /** Whether a failure is worth another scan, or whether the request should be rejected now. */
    static boolean retryable(String code) {
        return WorkshopApiException.NETWORK_ERROR.equals(code)
                || WorkshopApiException.TIMEOUT.equals(code)
                || WorkshopApiException.SERVER_ERROR.equals(code)
                || WorkshopApiException.RATE_LIMITED.equals(code);
    }

    /**
     * Turns the downloader's outcome for a requested set into the request file's fate. Registered once by
     * {@link WorkshopClient}; events for downloads the player started by hand are ignored, because only sets this
     * class queued are in {@link #IN_FLIGHT}.
     */
    static final class RequestListener implements WorkshopDownloader.Listener {
        private final WorkshopClient client;

        RequestListener(WorkshopClient client) {
            this.client = client;
        }

        @Override
        public void statusChanged(long setId, WorkshopDownloader.Status status) {
        }

        /**
         * A cancelled download is the third ending a request can have, next to {@link #installed} and {@link #failed}.
         * Without it the set would stay in {@link #IN_FLIGHT} and {@link #process} would refuse its request file for the
         * rest of the session, so the site's Install button looked dead for that one set.
         *
         * <p>A cancellation is an answer, not a failure: the player pressed Install a second time to stop this very
         * download, so the request has served its purpose and the file goes. Retrying it would start the download again
         * on the next scan, five seconds later, against the player's own decision, and {@code rejected/} is for
         * packages the mod could not use, not for a choice the player made. Nothing about the set is remembered, so a
         * new click on the site writes a new request and it is processed normally.
         */
        @Override
        public void cancelled(long setId) {
            Path file = IN_FLIGHT.get(setId);
            if (file == null) {
                return;
            }
            ATTEMPTS.remove(setId);
            RETRY_AFTER.remove(setId);
            boolean consentWithdrawn = !client.networkAllowed();
            MusicBattleMod.LOGGER.info("Workshop: the download of set {} from a helper request was cancelled", setId);
            client.io().execute(() -> {
                if (consentWithdrawn) {
                    // Revoking network consent cancels everything in flight through cancelAll, and there the player is
                    // owed the same reason file that the refused requests get, rather than a silent disappearance.
                    reject(client, file, WorkshopApiException.CONSENT_REQUIRED, "network access was declined");
                } else {
                    consume(client, file);
                }
                // Released only once the file is really gone, so a scan running in parallel cannot pick the same
                // request up in between and queue the download that was just stopped.
                IN_FLIGHT.remove(setId, file);
            });
        }

        @Override
        public void installed(long setId, BeatmapManifest manifest, BeatmapPackage.Outcome outcome) {
            Path file = IN_FLIGHT.remove(setId);
            if (file == null) {
                return;
            }
            ATTEMPTS.remove(setId);
            RETRY_AFTER.remove(setId);
            client.io().execute(() -> consume(client, file));
        }

        @Override
        public void failed(long setId, String code, long retryAfterSeconds) {
            Path file = IN_FLIGHT.remove(setId);
            if (file == null) {
                return;
            }
            int attempts = ATTEMPTS.merge(setId, 1, Integer::sum);
            if (retryable(code) && attempts < MAX_ATTEMPTS) {
                long delay = retryAfterSeconds > 0 ? retryAfterSeconds * 1000L : RETRY_DELAY_MS;
                RETRY_AFTER.put(setId, System.currentTimeMillis() + delay);
                MusicBattleMod.LOGGER.info("Workshop: set {} from a helper request failed ({}), retrying in {} s", setId, code, delay / 1000);
                return;
            }
            ATTEMPTS.remove(setId);
            RETRY_AFTER.remove(setId);
            client.io().execute(() -> reject(client, file, code, "the download failed after " + attempts + " attempt(s)"));
        }
    }
}

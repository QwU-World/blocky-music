package ru.gora.musicbattle.workshop.api;

import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;
import ru.gora.musicbattle.workshop.api.WorkshopDtos.Upload;
import ru.gora.musicbattle.workshop.api.WorkshopDtos.UploadResult;

/**
 * Publishes a map from the game (spec 06 section 6.5, 02 section 3), the way lazer's submission screen runs its stage
 * rows (research {@code osu-lazer-ui.md} section 5.3):
 *
 * <ol>
 *   <li><b>Preparing</b>: {@code POST /uploads} (with {@code setId} for an update) and {@code PATCH} the metadata.</li>
 *   <li><b>Uploading files</b>: every slot streamed from disk with byte progress ({@code PUT /slots/<slot>}).</li>
 *   <li><b>Processing on server</b>: a fresh {@code GET} (validation), {@code POST /submit}, then polling while the
 *       server reports stages {@code preparing}, {@code audio} and {@code package}.</li>
 *   <li><b>Finishing</b>: stage {@code finishing} until {@code submitted} with the result.</li>
 * </ol>
 *
 * Cancel works until the submit is accepted (the upload is then deleted on the server); after that the server finishes
 * on its own. {@link #run} blocks: call it on a worker thread. {@link #snapshot()} is safe from any thread.
 */
public final class WorkshopUploader {
    public static final long POLL_INTERVAL_MS = 1_000L;
    public static final long POLL_TIMEOUT_MS = 10 * 60_000L;

    public enum Stage {
        PREPARING, UPLOADING, PROCESSING, FINISHING
    }

    public enum StageState {
        PENDING, RUNNING, DONE, FAILED, CANCELLED
    }

    /** One file for a slot ({@code audio}, {@code cover} or {@code chart:<key>}). */
    public record SlotFile(String slot, Path file, String contentType) {
    }

    /** What to publish. {@code metadata} follows 02 section 3 ({@code charts}, {@code audio}, {@code cover} included). */
    public record Request(Long setId, List<SlotFile> files, JsonObject metadata, String termsVersion) {
    }

    public record SlotProgress(String slot, String fileName, long sent, long total, boolean done) {
        public float fraction() {
            return total <= 0 ? (done ? 1F : 0F) : (float) Math.min(1.0, (double) sent / total);
        }
    }

    /** What the stage rows show. {@code errorCode} maps to {@code music_battle.workshop.error.<code>}. */
    public record Snapshot(List<StageState> stages, List<SlotProgress> slots, String errorCode, String errorField, String uploadId,
                           UploadResult result, boolean running, boolean cancellable) {
        public StageState state(Stage stage) {
            return stages.get(stage.ordinal());
        }

        public boolean succeeded() {
            return result != null;
        }

        public float uploadFraction() {
            long total = 0;
            long sent = 0;
            for (SlotProgress slot : slots) {
                total += Math.max(0, slot.total());
                sent += Math.max(0, Math.min(slot.sent(), slot.total()));
            }
            return total <= 0 ? 0F : (float) ((double) sent / total);
        }
    }

    /** Waits between polls; tests pass a fake. */
    @FunctionalInterface
    public interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    private final WorkshopSession session;
    private final LongSupplier clock;
    private final Sleeper sleeper;
    private final CancelToken cancel = new CancelToken();
    private final StageState[] stages = {StageState.PENDING, StageState.PENDING, StageState.PENDING, StageState.PENDING};
    private final List<SlotProgress> slots = new ArrayList<>();
    private String errorCode;
    private String errorField;
    private String uploadId;
    private UploadResult result;
    private boolean running;
    private boolean submitted;

    public WorkshopUploader(WorkshopSession session, LongSupplier clock, Sleeper sleeper) {
        this.session = session;
        this.clock = clock;
        this.sleeper = sleeper;
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(List.of(stages), List.copyOf(slots), errorCode, errorField, uploadId, result, running,
                running && !submitted);
    }

    /** Runs the whole publish. Returns the final snapshot. */
    public Snapshot run(Request request) {
        synchronized (this) {
            if (running) {
                throw new IllegalStateException("already running");
            }
            running = true;
            slots.clear();
            for (SlotFile file : request.files()) {
                long size = -1;
                try {
                    size = Files.isRegularFile(file.file(), LinkOption.NOFOLLOW_LINKS) ? Files.size(file.file()) : -1;
                } catch (java.io.IOException ignored) {
                    size = -1;
                }
                slots.add(new SlotProgress(file.slot(), String.valueOf(file.file().getFileName()), 0, size, false));
            }
        }
        Stage stage = Stage.PREPARING;
        try {
            begin(stage);
            Upload upload = session.authorized(api -> api.createUpload(request.setId(), cancel));
            synchronized (this) {
                uploadId = upload.id();
            }
            session.authorized(api -> api.patchUpload(upload.id(), request.metadata(), cancel));
            done(stage);

            stage = Stage.UPLOADING;
            begin(stage);
            for (int i = 0; i < request.files().size(); i++) {
                SlotFile file = request.files().get(i);
                int index = i;
                session.authorized(api -> api.putSlotFile(upload.id(), file.slot(), file.file(), file.contentType(),
                        sent -> progress(index, sent, false), cancel));
                progress(index, -1, true);
            }
            done(stage);

            stage = Stage.PROCESSING;
            begin(stage);
            Upload checked = session.authorized(api -> api.upload(upload.id(), cancel));
            if (checked.validation() != null && !checked.validation().ok() && checked.validation().errors() != null
                    && !checked.validation().errors().isEmpty()) {
                WorkshopDtos.ValidationIssue first = checked.validation().errors().get(0);
                throw new WorkshopApiException(first.code(), 422, -1, first.field(), "validation");
            }
            cancel.throwIfCancelled();
            session.authorized(api -> api.submitUpload(upload.id(), request.termsVersion(), cancel));
            synchronized (this) {
                submitted = true;
            }
            long deadline = clock.getAsLong() + POLL_TIMEOUT_MS;
            while (true) {
                Upload now = session.authorized(api -> api.upload(upload.id(), CancelToken.none()));
                String status = now.status() == null ? "" : now.status();
                if ("submitted".equals(status) && now.result() != null) {
                    done(Stage.PROCESSING);
                    begin(Stage.FINISHING);
                    done(Stage.FINISHING);
                    synchronized (this) {
                        result = now.result();
                    }
                    break;
                }
                if (!"submitting".equals(status)) {
                    String code = now.errorCode() != null ? now.errorCode() : "failed".equals(status) ? "internal_error" : status;
                    throw new WorkshopApiException(code == null || code.isEmpty() ? "internal_error" : code, "upload " + status);
                }
                if ("finishing".equals(now.stage()) && stateOf(Stage.PROCESSING) == StageState.RUNNING) {
                    done(Stage.PROCESSING);
                    stage = Stage.FINISHING;
                    begin(stage);
                }
                if (clock.getAsLong() > deadline) {
                    throw new WorkshopApiException(WorkshopApiException.TIMEOUT, "the server did not finish in time");
                }
                sleeper.sleep(POLL_INTERVAL_MS);
            }
        } catch (WorkshopApiException exception) {
            fail(stage, exception.code(), exception.field());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            fail(stage, WorkshopApiException.CANCELLED, null);
        } catch (RuntimeException exception) {
            fail(stage, "internal_error", null);
        } finally {
            synchronized (this) {
                running = false;
            }
        }
        return snapshot();
    }

    /** Stops before the submit is accepted and deletes the draft on the server. After submit it does nothing. */
    public void cancel() {
        String id;
        synchronized (this) {
            if (!running || submitted) {
                return;
            }
            id = uploadId;
        }
        cancel.cancel();
        if (id != null) {
            try {
                session.authorized(api -> {
                    api.cancelUpload(id, CancelToken.none());
                    return null;
                });
            } catch (WorkshopApiException ignored) {
                // The draft expires on the server after 24 hours anyway.
            }
        }
    }

    private synchronized StageState stateOf(Stage stage) {
        return stages[stage.ordinal()];
    }

    private synchronized void begin(Stage stage) {
        stages[stage.ordinal()] = StageState.RUNNING;
    }

    private synchronized void done(Stage stage) {
        stages[stage.ordinal()] = StageState.DONE;
    }

    private synchronized void progress(int index, long sent, boolean finished) {
        SlotProgress old = slots.get(index);
        long value = finished ? Math.max(old.total(), old.sent()) : sent;
        slots.set(index, new SlotProgress(old.slot(), old.fileName(), value, old.total(), finished));
    }

    private synchronized void fail(Stage stage, String code, String field) {
        boolean cancelled = WorkshopApiException.CANCELLED.equals(code) || cancel.isCancelled();
        stages[stage.ordinal()] = cancelled ? StageState.CANCELLED : StageState.FAILED;
        errorCode = cancelled ? WorkshopApiException.CANCELLED : code;
        errorField = field;
    }
}

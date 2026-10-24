package ru.gora.musicbattle.workshop.api;

/**
 * Cancels one workshop call or download from another thread. Cancelling disconnects the open connection, so a
 * blocked read returns at once; the call then fails with {@link WorkshopApiException#CANCELLED}.
 */
public final class CancelToken {
    private volatile boolean cancelled;
    private volatile Runnable onCancel;

    public void cancel() {
        cancelled = true;
        Runnable action = onCancel;
        if (action != null) {
            action.run();
        }
    }

    public boolean isCancelled() {
        return cancelled;
    }

    void bind(Runnable action) {
        onCancel = action;
        if (cancelled) {
            action.run();
        }
    }

    void unbind() {
        onCancel = null;
    }

    void throwIfCancelled() throws WorkshopApiException {
        if (cancelled) {
            throw new WorkshopApiException(WorkshopApiException.CANCELLED, "cancelled");
        }
    }

    /** A token nobody cancels. */
    public static CancelToken none() {
        return new CancelToken();
    }
}

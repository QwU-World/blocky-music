package ru.gora.musicbattle.workshop;

import java.io.IOException;
import java.util.Objects;

/**
 * Why a workshop package was refused. The {@link Reason} is the stable part a future screen maps to a
 * translation key; the detail is for logs and never contains an absolute path, only entry names from the
 * archive (escaped and shortened, since they are untrusted).
 */
public final class BeatmapPackageException extends IOException {
    public enum Reason {
        /** The archive is missing, a directory or a symbolic link. */
        NOT_A_FILE,
        ARCHIVE_TOO_LARGE,
        CORRUPT_ARCHIVE,
        TOO_MANY_ENTRIES,
        /** Absolute path, {@code ..}, a separator, a directory entry or characters outside the whitelist. */
        UNSAFE_ENTRY_NAME,
        DUPLICATE_ENTRY,
        /** A safe name the manifest does not declare. */
        UNEXPECTED_ENTRY,
        /** Declared in the manifest, absent from the archive. */
        MISSING_ENTRY,
        MISSING_MANIFEST,
        INVALID_MANIFEST,
        /** The manifest was written for a newer package format than this build reads. */
        UNSUPPORTED_FORMAT,
        /** A declared size above the per-kind cap, or a manifest above its cap. */
        FILE_TOO_LARGE,
        /** The bytes in the archive are not exactly the declared size. */
        SIZE_MISMATCH,
        HASH_MISMATCH,
        INVALID_AUDIO,
        INVALID_COVER,
        INVALID_CHART,
        /** The installed or staging folder is a symbolic link, or a path escapes its root. */
        UNSAFE_DESTINATION
    }

    private final Reason reason;

    public BeatmapPackageException(Reason reason, String detail) {
        super(Objects.requireNonNull(reason, "reason") + (detail == null || detail.isEmpty() ? "" : ": " + detail));
        this.reason = reason;
    }

    public BeatmapPackageException(Reason reason, String detail, Throwable cause) {
        this(reason, detail);
        initCause(cause);
    }

    public Reason reason() {
        return reason;
    }

    /** An untrusted name made safe for a log line: printable ASCII only, at most 64 characters. */
    static String describe(String untrusted) {
        if (untrusted == null) {
            return "<null>";
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < untrusted.length() && out.length() < 64; i++) {
            char c = untrusted.charAt(i);
            out.append(c >= 0x20 && c < 0x7F ? c : '?');
        }
        return untrusted.length() > 64 ? out + "..." : out.toString();
    }
}

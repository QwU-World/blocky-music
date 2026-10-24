package ru.gora.musicbattle.workshop.api;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Locale;
import java.util.regex.Pattern;
import ru.gora.musicbattle.workshop.WorkshopLayout;

/**
 * A {@code <setId>.blockymap-request} file in {@code music_battle_tracks/workshop_inbox/} (spec 07 section 8, wave I1).
 *
 * <p>The QwU Workshop Helper writes one when the player clicks "Install in game" on the site or opens a
 * {@code blockymusic://install/<setId>} link. The helper downloads nothing and needs no account: all it says is
 * "the player asked for this set". The mod then does the real work through {@link WorkshopDownloader}, under its own
 * network consent, so a request can never make the game fetch something the player did not agree to.
 *
 * <pre>{@code {"v":1,"setId":42,"source":"helper","at":"2026-09-18T12:00:00Z"}}</pre>
 *
 * <p>Everything here is defensive: the file comes from outside the game. A request is accepted only when it is a small
 * regular file (never a symbolic link), parses as one JSON object, declares {@code v == 1} and carries a set id that
 * matches {@link #SET_ID} — the same shape the site and {@code WorkshopApi} use. The set id in the file name must
 * agree with the one inside, so a request can never be renamed into a different set. Nothing else in the file is
 * trusted: {@code source} and {@code at} are read for the log only.
 */
public final class WorkshopRequest {
    /** Cap on the file: a legitimate request is under 200 bytes. */
    public static final long MAX_BYTES = 4096;
    /** The version this mod understands. A newer one is refused with {@link Reason#UNSUPPORTED_VERSION}. */
    public static final int VERSION = 1;
    /** Positive, no leading zero, at most 19 digits so it always fits a {@code long}. */
    public static final Pattern SET_ID = Pattern.compile("[1-9][0-9]{0,18}");
    /** How long a request is kept before it is treated as stale and rejected (a week). */
    public static final long MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000;

    /** Why a request file was refused; each maps to {@code music_battle.workshop.error.<code>}. */
    public enum Reason {
        NOT_A_FILE("request_not_a_file"),
        TOO_LARGE("request_too_large"),
        MALFORMED("request_malformed"),
        UNSUPPORTED_VERSION("request_unsupported_version"),
        BAD_SET_ID("request_bad_set_id"),
        STALE("request_stale");

        private final String code;

        Reason(String code) {
            this.code = code;
        }

        /** The lang key suffix under {@code music_battle.workshop.error.}. */
        public String code() {
            return code;
        }
    }

    /** A refused request, with the reason and a detail for the {@code rejected/} text file and the log. */
    public static final class InvalidException extends IOException {
        private final Reason reason;

        public InvalidException(Reason reason, String detail) {
            super(detail);
            this.reason = reason;
        }

        public Reason reason() {
            return reason;
        }
    }

    private final long setId;
    private final String source;
    private final String at;

    private WorkshopRequest(long setId, String source, String at) {
        this.setId = setId;
        this.source = source;
        this.at = at;
    }

    public long setId() {
        return setId;
    }

    /** Who wrote the request ({@code "helper"}); never used for a decision, only for the log. */
    public String source() {
        return source;
    }

    /** When the helper wrote it, as it wrote it; never used for a decision, only for the log. */
    public String at() {
        return at;
    }

    /** Whether a file name is a request the inbox should offer. */
    public static boolean isRequestFile(Path file) {
        if (file == null) {
            return false;
        }
        String name = String.valueOf(file.getFileName()).toLowerCase(Locale.ROOT);
        return name.endsWith(WorkshopLayout.REQUEST_EXTENSION) && !name.startsWith(".");
    }

    /**
     * Reads and validates one request file.
     *
     * @param file  a file in the inbox
     * @param nowMs the clock, for the staleness check
     * @throws InvalidException when the file is not a request this mod will act on
     * @throws IOException      when the file cannot be read right now (still being written, locked): retry later
     */
    public static WorkshopRequest read(Path file, long nowMs) throws IOException {
        BasicFileAttributes attributes;
        try {
            attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException exception) {
            throw new InvalidException(Reason.NOT_A_FILE, "unreadable: " + exception.getMessage());
        }
        if (!attributes.isRegularFile()) {
            throw new InvalidException(Reason.NOT_A_FILE, "not a regular file");
        }
        if (attributes.size() > MAX_BYTES) {
            throw new InvalidException(Reason.TOO_LARGE, attributes.size() + " bytes, the cap is " + MAX_BYTES);
        }
        if (nowMs - attributes.lastModifiedTime().toMillis() > MAX_AGE_MS) {
            throw new InvalidException(Reason.STALE, "older than " + (MAX_AGE_MS / (24 * 60 * 60 * 1000)) + " days");
        }
        byte[] bytes = Files.readAllBytes(file);
        if (bytes.length > MAX_BYTES) {
            throw new InvalidException(Reason.TOO_LARGE, bytes.length + " bytes, the cap is " + MAX_BYTES);
        }
        WorkshopRequest request = parse(new String(bytes, StandardCharsets.UTF_8));
        long fromName = setIdFromFileName(file);
        if (fromName > 0 && fromName != request.setId) {
            throw new InvalidException(Reason.BAD_SET_ID,
                    "the file name says " + fromName + " and the file says " + request.setId);
        }
        return request;
    }

    /** Parses the JSON body of a request. Package-visible shape checks live here so tests can reach them. */
    public static WorkshopRequest parse(String json) throws InvalidException {
        JsonElement root;
        try {
            root = JsonParser.parseString(json);
        } catch (JsonParseException | StackOverflowError exception) {
            throw new InvalidException(Reason.MALFORMED, "not JSON");
        }
        if (root == null || !root.isJsonObject()) {
            throw new InvalidException(Reason.MALFORMED, "not a JSON object");
        }
        JsonObject object = root.getAsJsonObject();
        int version = intOrZero(object, "v");
        if (version != VERSION) {
            throw new InvalidException(Reason.UNSUPPORTED_VERSION, "v=" + version + ", this mod reads v=" + VERSION);
        }
        String setId = primitiveAsString(object, "setId");
        if (setId == null || !SET_ID.matcher(setId).matches()) {
            throw new InvalidException(Reason.BAD_SET_ID, "setId=" + setId);
        }
        long parsed;
        try {
            parsed = Long.parseLong(setId);
        } catch (NumberFormatException exception) {
            throw new InvalidException(Reason.BAD_SET_ID, "setId=" + setId + " does not fit a long");
        }
        return new WorkshopRequest(parsed, shortText(object, "source"), shortText(object, "at"));
    }

    /** {@code 42.blockymap-request} → 42; 0 when the name says nothing usable. */
    public static long setIdFromFileName(Path file) {
        String name = String.valueOf(file.getFileName());
        if (!name.toLowerCase(Locale.ROOT).endsWith(WorkshopLayout.REQUEST_EXTENSION)) {
            return 0;
        }
        String stem = name.substring(0, name.length() - WorkshopLayout.REQUEST_EXTENSION.length());
        if (!SET_ID.matcher(stem).matches()) {
            return 0;
        }
        try {
            return Long.parseLong(stem);
        } catch (NumberFormatException exception) {
            return 0;
        }
    }

    private static int intOrZero(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            return 0;
        }
        try {
            return value.getAsInt();
        } catch (NumberFormatException exception) {
            return 0;
        }
    }

    /**
     * The set id as text, whether the helper wrote it as a JSON number or a string. Gson keeps a number's digits
     * verbatim, so a 19-digit id never loses precision on the way through a {@code double}.
     */
    private static String primitiveAsString(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive()) {
            return null;
        }
        if (value.getAsJsonPrimitive().isBoolean()) {
            return null;
        }
        return value.getAsString();
    }

    /** A short, log-safe copy of a free-text field: never used for a decision. */
    private static String shortText(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            return "";
        }
        String text = value.getAsString();
        StringBuilder safe = new StringBuilder();
        for (int i = 0; i < text.length() && safe.length() < 64; i++) {
            char character = text.charAt(i);
            if (character >= ' ' && character != 127) {
                safe.append(character);
            }
        }
        return safe.toString();
    }

    @Override
    public String toString() {
        return "WorkshopRequest[setId=" + setId + ", source=" + source + ", at=" + at + "]";
    }
}

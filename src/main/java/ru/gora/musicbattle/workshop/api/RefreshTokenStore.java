package ru.gora.musicbattle.workshop.api;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The optional "remember me" refresh token (spec 06 section 7.1 as changed by the architect for wave M2b). It lives
 * <b>inside</b> the game directory, next to the rest of the mod's files, so nothing is written outside the Minecraft
 * folder: {@code music_battle_tracks/.account/session.json}. The folder is not a track folder (the catalogue loaders
 * never scan the {@code music_battle_tracks} root), and the dot keeps it out of casual sight.
 *
 * <p>Written only when the player ticked "Remember me". Contents
 * {@code {"v":1,"qwuUserId":17,"refreshToken":"bmw_rt_...","createdAt":"..."}}: no access token, no Minecraft token.
 * POSIX {@code 0600} (folder {@code 0700}) where the file system supports it, written atomically; deleted on sign-out
 * and on {@code invalid_grant}.
 */
public final class RefreshTokenStore {
    /** Folder under {@code music_battle_tracks}. */
    public static final String DIRECTORY_NAME = ".account";
    public static final String FILE_NAME = "session.json";
    private static final Pattern REFRESH_TOKEN = Pattern.compile("bmw_rt_[A-Za-z0-9_-]{43}");

    /** What the file holds. {@code toString()} never prints the token. */
    public record Stored(long qwuUserId, String refreshToken, String createdAt) {
        @Override
        public String toString() {
            return "Stored[qwuUserId=" + qwuUserId + ", refreshToken=<redacted>, createdAt=" + createdAt + "]";
        }
    }

    private final Path file;

    public RefreshTokenStore(Path file) {
        this.file = file.toAbsolutePath().normalize();
    }

    public Path file() {
        return file;
    }

    /** {@code <tracksRoot>/.account/session.json}, where {@code tracksRoot} is the game's {@code music_battle_tracks}. */
    public static Path defaultPath(Path tracksRoot) {
        return tracksRoot.toAbsolutePath().normalize().resolve(DIRECTORY_NAME).resolve(FILE_NAME);
    }

    public boolean exists() {
        return Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS);
    }

    public Optional<Stored> read() {
        try {
            if (!exists() || Files.size(file) > 16 * 1024) {
                return Optional.empty();
            }
            JsonElement element = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            if (!element.isJsonObject()) {
                return Optional.empty();
            }
            JsonObject object = element.getAsJsonObject();
            if (object.get("v") == null || object.get("v").getAsInt() != 1) {
                return Optional.empty();
            }
            String token = object.get("refreshToken").getAsString();
            if (!REFRESH_TOKEN.matcher(token).matches()) {
                return Optional.empty();
            }
            long userId = object.has("qwuUserId") ? object.get("qwuUserId").getAsLong() : 0;
            String createdAt = object.has("createdAt") ? object.get("createdAt").getAsString() : "";
            return Optional.of(new Stored(userId, token, createdAt));
        } catch (IOException | RuntimeException exception) {
            return Optional.empty();
        }
    }

    public void write(long qwuUserId, String refreshToken, Instant now) throws IOException {
        if (refreshToken == null || !REFRESH_TOKEN.matcher(refreshToken).matches()) {
            throw new IOException("not a refresh token");
        }
        JsonObject object = new JsonObject();
        object.addProperty("v", 1);
        object.addProperty("qwuUserId", qwuUserId);
        object.addProperty("refreshToken", refreshToken);
        object.addProperty("createdAt", now.toString());
        AtomicFiles.write(file, WorkshopDtos.GSON.toJson(object).getBytes(StandardCharsets.UTF_8), true);
    }

    public void delete() {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // Best effort; a leftover rotated token is refused by the server anyway.
        }
    }
}

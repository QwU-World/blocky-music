package ru.gora.musicbattle.workshop.api;

import com.google.gson.GsonBuilder;
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

/**
 * {@code config/music_battle-workshop.json}: the network consent (spec 06 section 4) and later Workshop options.
 *
 * <pre>{"consent": {"network": true, "at": "2026-09-17T10:00:00Z", "version": 1}}</pre>
 *
 * Consent counts only when {@code network} is true and {@code version} is at least {@link #NETWORK_CONSENT_VERSION}
 * (a later disclosure change bumps the version and asks again). Unknown fields are kept on save. Writes are atomic
 * (temporary file + {@code ATOMIC_MOVE}). A missing or unreadable file means no consent.
 */
public final class WorkshopConfig {
    public static final String FILE_NAME = "music_battle-workshop.json";
    public static final int NETWORK_CONSENT_VERSION = 1;
    private static final long MAX_BYTES = 256L * 1024;

    private final Path file;
    private JsonObject root = new JsonObject();
    private volatile boolean networkAllowed;

    private WorkshopConfig(Path file) {
        this.file = file.toAbsolutePath().normalize();
    }

    /** Reads the file; problems leave an empty config (no consent) and are not fatal. */
    public static WorkshopConfig load(Path file) {
        WorkshopConfig config = new WorkshopConfig(file);
        config.reload();
        return config;
    }

    public Path file() {
        return file;
    }

    public synchronized void reload() {
        JsonObject loaded = new JsonObject();
        try {
            if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) && Files.size(file) <= MAX_BYTES) {
                JsonElement element = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
                if (element.isJsonObject()) {
                    loaded = element.getAsJsonObject();
                }
            }
        } catch (IOException | RuntimeException ignored) {
            loaded = new JsonObject();
        }
        root = loaded;
        networkAllowed = computeNetworkAllowed(root);
    }

    private static boolean computeNetworkAllowed(JsonObject root) {
        try {
            JsonObject consent = root.has("consent") && root.get("consent").isJsonObject() ? root.getAsJsonObject("consent") : null;
            return consent != null
                    && consent.has("network") && consent.get("network").isJsonPrimitive() && consent.get("network").getAsBoolean()
                    && consent.has("version") && consent.get("version").isJsonPrimitive()
                    && consent.get("version").getAsInt() >= NETWORK_CONSENT_VERSION;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    /** True once the player accepted the current network disclosure. Cheap; safe from any thread. */
    public boolean networkAllowed() {
        return networkAllowed;
    }

    /** True when a consent entry exists at all (accepted or declined), so the first-run screen is not shown again. */
    public synchronized boolean consentAnswered() {
        JsonObject consent = root.has("consent") && root.get("consent").isJsonObject() ? root.getAsJsonObject("consent") : null;
        return consent != null && consent.has("version") && consent.get("version").isJsonPrimitive()
                && consent.get("version").getAsInt() >= NETWORK_CONSENT_VERSION;
    }

    public synchronized Optional<Instant> consentAt() {
        try {
            JsonObject consent = root.getAsJsonObject("consent");
            return Optional.of(Instant.parse(consent.get("at").getAsString()));
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    public synchronized void setNetworkConsent(boolean allowed, Instant at) throws IOException {
        JsonObject copy = root.deepCopy();
        JsonObject consent = new JsonObject();
        consent.addProperty("network", allowed);
        consent.addProperty("at", at.toString());
        consent.addProperty("version", NETWORK_CONSENT_VERSION);
        copy.add("consent", consent);
        save(copy);
        root = copy;
        networkAllowed = allowed;
    }

    /** An integer option, or the fallback. */
    public synchronized double number(String key, double fallback) {
        try {
            return root.has(key) ? root.get(key).getAsDouble() : fallback;
        } catch (RuntimeException exception) {
            return fallback;
        }
    }

    public synchronized void setNumber(String key, double value) throws IOException {
        JsonObject copy = root.deepCopy();
        copy.addProperty(key, value);
        save(copy);
        root = copy;
    }

    private void save(JsonObject value) throws IOException {
        byte[] bytes = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(value)
                .getBytes(StandardCharsets.UTF_8);
        AtomicFiles.write(file, bytes, false);
    }
}

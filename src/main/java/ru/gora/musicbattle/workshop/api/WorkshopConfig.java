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
 * {@code config/music_battle-workshop.json}: the network consent and later Workshop options.
 *
 * <pre>{"consent": {"network": true, "at": "2026-09-17T10:00:00Z", "version": 2},
 *  "autoSignIn": ["853c80ef-3c37-49fd-aa49-938b674adae6"]}</pre>
 *
 * Consent counts only when {@code network} is true and {@code version} is at least {@link #NETWORK_CONSENT_VERSION}
 * (a later disclosure change bumps the version and asks again). Unknown fields are kept on save. Writes are atomic
 * (temporary file + {@code ATOMIC_MOVE}). A missing or unreadable file means no consent.
 *
 * <p>{@code autoSignIn} lists the Minecraft accounts (by UUID) that have signed in to the Workshop with their Minecraft
 * account on this computer. Only those are signed in again automatically when the Workshop opens; a Minecraft account
 * that never signed in here sends nothing on its own (owner decision 01.11.2026). A sign-out removes the entry;
 * turning network access off does not. This is the {@link MinecraftSignIn.Memory} of the game.
 */
public final class WorkshopConfig implements MinecraftSignIn.Memory {
    public static final String FILE_NAME = "music_battle-workshop.json";
    /**
     * 2 since 0.2.0-beta.2: the first-run screen now names the automatic sign-in, qwu.world for account pictures, the
     * versions in the User-Agent, what works offline and the switch that turns network access off again. Everybody who
     * answered version 1 is asked once more.
     */
    public static final int NETWORK_CONSENT_VERSION = 2;
    private static final String AUTO_SIGN_IN = "autoSignIn";
    /** More than anyone plays on one computer; a damaged file cannot grow the list without end. */
    private static final int MAX_REMEMBERED_ACCOUNTS = 32;
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

    // ---------------------------------------------------------------- Minecraft accounts that signed in here

    /** True when this Minecraft account has signed in to the Workshop on this computer and has not signed out since. */
    @Override
    public synchronized boolean remembered(java.util.UUID account) {
        return account != null && rememberedAccounts(root).contains(account.toString().toLowerCase(java.util.Locale.ROOT));
    }

    /** Keeps a Minecraft account that has just signed in with its Minecraft proof. */
    @Override
    public synchronized void remember(java.util.UUID account) throws IOException {
        if (account == null) {
            return;
        }
        String id = account.toString().toLowerCase(java.util.Locale.ROOT);
        java.util.List<String> accounts = rememberedAccounts(root);
        if (accounts.contains(id)) {
            return;
        }
        accounts.add(id);
        while (accounts.size() > MAX_REMEMBERED_ACCOUNTS) {
            accounts.remove(0);
        }
        saveAccounts(accounts);
    }

    /** Forgets a Minecraft account (an explicit sign-out). */
    @Override
    public synchronized void forget(java.util.UUID account) throws IOException {
        if (account == null) {
            return;
        }
        java.util.List<String> accounts = rememberedAccounts(root);
        if (accounts.remove(account.toString().toLowerCase(java.util.Locale.ROOT))) {
            saveAccounts(accounts);
        }
    }

    private void saveAccounts(java.util.List<String> accounts) throws IOException {
        JsonObject copy = root.deepCopy();
        com.google.gson.JsonArray array = new com.google.gson.JsonArray();
        accounts.forEach(array::add);
        copy.add(AUTO_SIGN_IN, array);
        save(copy);
        root = copy;
    }

    /** The remembered UUIDs in their saved order; anything that is not a UUID is dropped. */
    private static java.util.List<String> rememberedAccounts(JsonObject root) {
        java.util.List<String> accounts = new java.util.ArrayList<>();
        try {
            if (root.has(AUTO_SIGN_IN) && root.get(AUTO_SIGN_IN).isJsonArray()) {
                for (JsonElement element : root.getAsJsonArray(AUTO_SIGN_IN)) {
                    if (!element.isJsonPrimitive()) {
                        continue;
                    }
                    try {
                        String id = java.util.UUID.fromString(element.getAsString()).toString();
                        if (!accounts.contains(id)) {
                            accounts.add(id);
                        }
                    } catch (IllegalArgumentException notUuid) {
                        // A hand edit gone wrong: that entry simply does not count.
                    }
                }
            }
        } catch (RuntimeException damaged) {
            accounts.clear();
        }
        return accounts;
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

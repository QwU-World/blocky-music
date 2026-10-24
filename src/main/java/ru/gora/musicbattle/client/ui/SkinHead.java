package ru.gora.musicbattle.client.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.yggdrasil.ProfileResult;
import com.mojang.util.UndashedUuid;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import javax.annotation.Nullable;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.resources.ResourceLocation;
import ru.gora.musicbattle.MusicBattleMod;

/**
 * Heads of server-comment authors (spec 06 section 9.3 as amended by the 17 September 2026 review; owner rule:
 * by nickname, Steve without a licensed account).
 *
 * <ol>
 *   <li>Textures with a signature that Minecraft verifies as secure: that skin.</li>
 *   <li>Otherwise (offline-mode servers, version 3 UUIDs, missing or invalid textures) by name. A name that is not
 *       {@code [A-Za-z0-9_]{1,16}} is Steve. Names are looked up in batches of up to 10 through the game's proxy
 *       ({@code POST api.minecraftservices.com/minecraft/profile/lookup/bulk/byname}); found names are cached
 *       for 24 hours, missing names for 1 hour, errors not at all. A missing or demo account is Steve. A found
 *       account is fetched from the session server with signed textures and loaded through the SkinManager
 *       (a profile without a skin gets vanilla's default for its UUID).</li>
 * </ol>
 * Steve is {@code DefaultPlayerSkin.getDefaultTexture()}. While loading, on transient errors and whenever
 * {@code networkAllowed} is false, the head is Steve and nothing is requested. Render thread, except the
 * internal IO work.
 */
public final class SkinHead {
    public static final String BULK_LOOKUP_URL = "https://api.minecraftservices.com/minecraft/profile/lookup/bulk/byname";
    public static final int NAMES_PER_REQUEST = 10;
    static final long FOUND_TTL_MS = TimeUnit.HOURS.toMillis(24);
    static final long MISSING_TTL_MS = TimeUnit.HOURS.toMillis(1);
    static final long ERROR_RETRY_MS = TimeUnit.MINUTES.toMillis(1);
    static final int MAX_RESOLVED = 256;
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");
    private static final long MAX_RESPONSE_BYTES = 256 * 1024;

    /** An author as stored with a server comment: UUID, name, and the textures property if the server had one. */
    public record Author(UUID id, String name, @Nullable String textures, @Nullable String signature) {
        String cacheKey() {
            return id + "/" + name + "/" + (textures == null ? 0 : textures.hashCode()) + "/" + (signature == null ? 0 : signature.hashCode());
        }
    }

    enum NameState {
        FOUND, MISSING, DEMO, ERROR
    }

    record NameResult(NameState state, @Nullable UUID id, long expiresAtMillis) {
    }

    private static final Map<String, Resolution> RESOLVED = new LinkedHashMap<>(64, 0.75F, true);
    private static final Map<String, NameResult> NAME_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, CompletableFuture<NameResult>> NAME_WAITING = new ConcurrentHashMap<>();
    private static final Queue<String> NAME_QUEUE = new ConcurrentLinkedQueue<>();
    private static final AtomicBoolean NAME_WORKER = new AtomicBoolean();
    private static PlayerSkin steve;

    private SkinHead() {
    }

    public static boolean isValidName(String name) {
        return name != null && NAME.matcher(name).matches();
    }

    public static PlayerSkin steve() {
        PlayerSkin skin = steve;
        if (skin == null) {
            skin = new PlayerSkin(DefaultPlayerSkin.getDefaultTexture(), null, null, null, PlayerSkin.Model.WIDE, false);
            steve = skin;
        }
        return skin;
    }

    /** The skin to draw for this author right now: Steve until resolved. Never null. */
    public static PlayerSkin skin(Author author, boolean networkAllowed) {
        if (author == null || !networkAllowed) {
            return steve();
        }
        long now = System.currentTimeMillis();
        String key = author.cacheKey();
        Resolution resolution = RESOLVED.get(key);
        if (resolution == null || (resolution.done && resolution.retryAtMillis != 0 && now >= resolution.retryAtMillis)) {
            resolution = new Resolution();
            RESOLVED.put(key, resolution);
            trimResolved();
            Resolution target = resolution;
            resolve(author).whenComplete((skin, failure) -> {
                if (failure != null || skin == null) {
                    target.retryAtMillis = System.currentTimeMillis() + ERROR_RETRY_MS;
                } else {
                    target.skin = skin;
                    // Re-resolve now and then (the name cache answers without a request until it expires).
                    target.retryAtMillis = System.currentTimeMillis() + MISSING_TTL_MS;
                }
                target.done = true;
            });
        }
        PlayerSkin resolved = resolution.skin;
        return resolved == null ? steve() : resolved;
    }

    /** Draws a head (face plus hat layer) at float coordinates, rounded when the image shader is available. */
    public static void draw(GuiGraphics g, Author author, boolean networkAllowed, float x, float y, float size, float radius) {
        drawSkin(g, skin(author, networkAllowed), x, y, size, radius);
    }

    public static void drawSkin(GuiGraphics g, PlayerSkin skin, float x, float y, float size, float radius) {
        PlayerSkin chosen = skin == null ? steve() : skin;
        ResourceLocation texture = chosen.texture();
        if (RoundedImage.available()) {
            float s = 1.0F / 64.0F;
            RoundedImage.draw(g, texture, x, y, size, size, 8 * s, 8 * s, 16 * s, 16 * s,
                    radius, radius, radius, radius, 0.0F, 0xFFFFFFFF, 0xFFFFFFFF);
            RoundedImage.draw(g, texture, x, y, size, size, 40 * s, 8 * s, 48 * s, 16 * s,
                    radius, radius, radius, radius, 0.0F, 0xFFFFFFFF, 0xFFFFFFFF);
        } else {
            g.flush();
            PlayerFaceRenderer.draw(g, chosen, Math.round(x), Math.round(y), Math.max(1, Math.round(size)));
            UiDrawStats.image();
        }
    }

    /** Forgets resolved heads (the name cache survives, it has its own expiry). */
    public static void clear() {
        RESOLVED.clear();
    }

    private static void trimResolved() {
        var it = RESOLVED.entrySet().iterator();
        while (RESOLVED.size() > MAX_RESOLVED && it.hasNext()) {
            it.next();
            it.remove();
        }
    }

    private static final class Resolution {
        volatile PlayerSkin skin;
        volatile boolean done;
        volatile long retryAtMillis;
    }

    // ---- resolution chain (IO threads) ----------------------------------------------------------------------

    private static CompletableFuture<PlayerSkin> resolve(Author author) {
        Minecraft minecraft = Minecraft.getInstance();
        if (author.signature() != null && !author.signature().isEmpty() && author.textures() != null && author.id() != null) {
            GameProfile profile = new GameProfile(author.id(), author.name() == null ? "" : author.name());
            profile.getProperties().put("textures", new Property("textures", author.textures(), author.signature()));
            return minecraft.getSkinManager().getOrLoad(profile)
                    .thenCompose(skin -> skin != null && skin.secure() ? CompletableFuture.completedFuture(skin) : byName(author.name()));
        }
        return byName(author.name());
    }

    private static CompletableFuture<PlayerSkin> byName(String name) {
        if (!isValidName(name)) {
            return CompletableFuture.completedFuture(steve());
        }
        return lookupName(name).thenCompose(result -> switch (result.state()) {
            case MISSING, DEMO -> CompletableFuture.completedFuture(steve());
            case ERROR -> CompletableFuture.failedFuture(new IllegalStateException("name lookup failed"));
            case FOUND -> CompletableFuture.supplyAsync(
                    () -> Minecraft.getInstance().getMinecraftSessionService().fetchProfile(result.id(), true),
                    Util.nonCriticalIoPool()).thenCompose(SkinHead::skinFromProfile);
        });
    }

    private static CompletableFuture<PlayerSkin> skinFromProfile(ProfileResult result) {
        if (result == null || result.profile() == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("profile fetch failed"));
        }
        return Minecraft.getInstance().getSkinManager().getOrLoad(result.profile());
    }

    static CompletableFuture<NameResult> lookupName(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        NameResult cached = NAME_CACHE.get(lower);
        if (cached != null && cached.expiresAtMillis() > System.currentTimeMillis()) {
            return CompletableFuture.completedFuture(cached);
        }
        CompletableFuture<NameResult> fresh = new CompletableFuture<>();
        CompletableFuture<NameResult> existing = NAME_WAITING.putIfAbsent(lower, fresh);
        if (existing != null) {
            return existing;
        }
        NAME_QUEUE.add(lower);
        if (NAME_WORKER.compareAndSet(false, true)) {
            // A short delay lets the rest of a comment page queue its names into the same request.
            CompletableFuture.runAsync(SkinHead::drainNames,
                    CompletableFuture.delayedExecutor(60, TimeUnit.MILLISECONDS, Util.nonCriticalIoPool()));
        }
        return fresh;
    }

    private static void drainNames() {
        try {
            while (true) {
                List<String> batch = new ArrayList<>(NAMES_PER_REQUEST);
                String next;
                while (batch.size() < NAMES_PER_REQUEST && (next = NAME_QUEUE.poll()) != null) {
                    if (!batch.contains(next)) {
                        batch.add(next);
                    }
                }
                if (batch.isEmpty()) {
                    break;
                }
                Map<String, NameResult> results = requestBatch(batch);
                for (String name : batch) {
                    NameResult result = results.get(name);
                    if (result.state() != NameState.ERROR) {
                        NAME_CACHE.put(name, result);
                    }
                    CompletableFuture<NameResult> waiting = NAME_WAITING.remove(name);
                    if (waiting != null) {
                        waiting.complete(result);
                    }
                }
            }
        } finally {
            NAME_WORKER.set(false);
            if (!NAME_QUEUE.isEmpty() && NAME_WORKER.compareAndSet(false, true)) {
                CompletableFuture.runAsync(SkinHead::drainNames, Util.nonCriticalIoPool());
            }
        }
    }

    /** One bulk request; every name gets a result (ERROR for all when the request fails). */
    static Map<String, NameResult> requestBatch(List<String> names) {
        long now = System.currentTimeMillis();
        Map<String, NameResult> results = new LinkedHashMap<>();
        try {
            JsonArray body = new JsonArray();
            names.forEach(body::add);
            byte[] response = UiHttp.postJson(BULK_LOOKUP_URL, body.toString(), MAX_RESPONSE_BYTES);
            parseBulkResponse(new String(response, StandardCharsets.UTF_8), names, now, results);
        } catch (Exception e) {
            MusicBattleMod.LOGGER.debug("Skin head name lookup failed: {}", e.toString());
            results.clear();
            for (String name : names) {
                results.put(name, new NameResult(NameState.ERROR, null, 0L));
            }
        }
        return results;
    }

    /** Fills {@code results} for every requested (lower-case) name from a bulk lookup response body. */
    static void parseBulkResponse(String json, List<String> names, long nowMillis, Map<String, NameResult> results) {
        JsonElement root = JsonParser.parseString(json);
        if (!root.isJsonArray()) {
            throw new IllegalArgumentException("bulk lookup did not return an array");
        }
        for (JsonElement element : root.getAsJsonArray()) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject object = element.getAsJsonObject();
            if (!object.has("name") || !object.has("id")) {
                continue;
            }
            String lower = object.get("name").getAsString().toLowerCase(Locale.ROOT);
            if (!names.contains(lower)) {
                continue;
            }
            boolean demo = object.has("demo") && object.get("demo").getAsBoolean();
            UUID id = UndashedUuid.fromStringLenient(object.get("id").getAsString());
            results.put(lower, demo
                    ? new NameResult(NameState.DEMO, id, nowMillis + FOUND_TTL_MS)
                    : new NameResult(NameState.FOUND, id, nowMillis + FOUND_TTL_MS));
        }
        for (String name : names) {
            results.putIfAbsent(name, new NameResult(NameState.MISSING, null, nowMillis + MISSING_TTL_MS));
        }
    }
}

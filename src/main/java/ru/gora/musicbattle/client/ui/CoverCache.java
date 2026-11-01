package ru.gora.musicbattle.client.ui;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.TextureUtil;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import org.lwjgl.stb.STBImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import ru.gora.musicbattle.MusicBattleMod;
import ru.gora.musicbattle.server.MusicBattleTracks;

/**
 * Session-wide cache of cover textures.
 *
 * <ol>
 *   <li>Render thread: {@link #get} returns a live texture id, or null until one is ready. Never a released id.</li>
 *   <li>IO pool: disk cache ({@code music_battle_tracks/.workshop_cache/covers/}) or HTTP, then PNG or JPEG
 *       decoding with STB (Minecraft's own reader accepts PNG only), at most 4 loads at a time.</li>
 *   <li>Render thread, {@link #pump} once per frame: at most 4 uploads, LINEAR filtering, clamped, CPU copy
 *       freed right after upload.</li>
 *   <li>Least recently used textures are released once GPU memory passes 64 MiB, but never one drawn in
 *       the last second. Everything else stays until {@link #clear} (when the Workshop closes for good; not
 *       from {@code Screen.removed()}, which also runs when a link dialog opens).</li>
 * </ol>
 *
 * <p>A URL is only fetched because a caller passed it; network consent is the caller's job. Callers should also
 * wait until a card has been visible for about 500 ms before asking (osu! does), so fast scrolling fetches nothing.
 */
public final class CoverCache {
    public static final long GPU_BUDGET_BYTES = 64L * 1024 * 1024;
    public static final long DISK_BUDGET_BYTES = 256L * 1024 * 1024;
    public static final int MAX_UPLOADS_PER_FRAME = 4;
    public static final int MAX_CONCURRENT_LOADS = 4;
    public static final long MAX_SOURCE_BYTES = 16L * 1024 * 1024;
    public static final int MAX_DIMENSION = 4096;
    static final long KEEP_ALIVE_NANOS = 1_000_000_000L;
    static final long PENDING_STALE_NANOS = 2_000_000_000L;
    static final long RETRY_AFTER_NANOS = 30_000_000_000L;

    public enum State {
        MISSING, QUEUED, LOADING, READY, FAILED
    }

    private static final Map<String, Entry> ENTRIES = new LinkedHashMap<>(64, 0.75F, true);
    private static final ArrayDeque<Entry> PENDING = new ArrayDeque<>();
    private static final ConcurrentLinkedQueue<Result> RESULTS = new ConcurrentLinkedQueue<>();
    private static final AtomicBoolean DISK_TRIMMED = new AtomicBoolean();
    private static int generation;
    private static int inFlight;
    private static long gpuBytes;
    private static int nextTextureId;

    /**
     * Where a site-relative picture path belongs, as {@code https://host[:port]}.
     *
     * The Workshop API answers with paths, not addresses: a cover is
     * {@code /files/covers/<sha>/card@1x.jpg}. Only this cache turned them into requests, and it could not,
     * because a fetch needs an absolute address - so every cover in the Workshop screens stayed blank while
     * installed maps, which are read from disk, drew fine. The Workshop client hands the origin over when it
     * starts; an address that is already absolute is fetched only when it is https on qwu.world (or a subdomain) or on
     * this origin, and anything else draws the placeholder ({@code MediaUrl.absolute}).
     */
    private static volatile String origin;

    private CoverCache() {
    }

    /** Sets the origin that site-relative paths are joined to. Called once, when the Workshop client starts. */
    public static void origin(String value) {
        origin = ru.gora.musicbattle.workshop.api.MediaUrl.trimTrailingSlashes(value);
    }

    /** An http(s) URL, or a path the Workshop answered with. Render thread. */
    public static ResourceLocation get(String url) {
        if (url == null || url.isEmpty()) {
            return null;
        }
        String absolute = absolute(url);
        if (absolute == null) {
            return null;
        }
        return touch("url:" + absolute, absolute, null, null);
    }

    /** A fetchable address, or null when there is nothing to fetch. The rule itself lives in {@code MediaUrl}. */
    static String absolute(String url) {
        return ru.gora.musicbattle.workshop.api.MediaUrl.absolute(origin, url);
    }

    /** Cover from a local image file (installed sets). Render thread. */
    public static ResourceLocation getFile(Path file) {
        if (file == null) {
            return null;
        }
        Path normal = file.toAbsolutePath().normalize();
        return touch("file:" + normal, null, normal, null);
    }

    /** An image from the resource packs, decoded and uploaded like a cover (LINEAR). Render thread. */
    public static ResourceLocation getResource(ResourceLocation image) {
        if (image == null) {
            return null;
        }
        return touch("res:" + image, null, null, image);
    }

    public static State state(String url) {
        Entry entry = url == null ? null : ENTRIES.get("url:" + url);
        return entry == null ? State.MISSING : entry.state;
    }

    public static long gpuBytes() {
        return gpuBytes;
    }

    public static int textureCount() {
        int count = 0;
        for (Entry entry : ENTRIES.values()) {
            if (entry.state == State.READY) {
                count++;
            }
        }
        return count;
    }

    private static ResourceLocation touch(String key, String url, Path file, ResourceLocation resource) {
        long now = Util.getNanos();
        Entry entry = ENTRIES.get(key);
        if (entry == null || (entry.state == State.FAILED && now - entry.failedAt > RETRY_AFTER_NANOS)) {
            if (entry == null) {
                entry = new Entry(key, url, file, resource);
                ENTRIES.put(key, entry);
            }
            entry.state = State.QUEUED;
            PENDING.addLast(entry);
        }
        entry.lastUsed = now;
        return entry.state == State.READY ? entry.location : null;
    }

    /** Once per frame on the render thread ({@link UiClientEvents}): uploads, evicts, starts loads. */
    public static void pump() {
        if (ENTRIES.isEmpty() && RESULTS.isEmpty()) {
            return;
        }
        long now = Util.getNanos();
        int uploads = 0;
        Result result;
        while ((result = RESULTS.peek()) != null) {
            if (result.image != null && uploads >= MAX_UPLOADS_PER_FRAME) {
                break;
            }
            RESULTS.poll();
            if (result.generation != generation) {
                closeQuietly(result.image);
                continue;
            }
            inFlight--;
            Entry entry = ENTRIES.get(result.key);
            if (entry == null || entry.state != State.LOADING) {
                closeQuietly(result.image);
                continue;
            }
            if (result.image == null) {
                entry.state = State.FAILED;
                entry.failedAt = now;
                continue;
            }
            upload(entry, result.image);
            uploads++;
        }
        evict(now);
        startLoads(now);
    }

    private static void upload(Entry entry, NativeImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        CoverTexture texture;
        try {
            texture = new CoverTexture(image);
        } catch (RuntimeException e) {
            closeQuietly(image);
            entry.state = State.FAILED;
            entry.failedAt = Util.getNanos();
            MusicBattleMod.LOGGER.warn("Could not upload Workshop cover {}", entry.key, e);
            return;
        }
        ResourceLocation location = MusicBattleMod.id("workshop_cover/" + Integer.toUnsignedString(nextTextureId++, 36));
        Minecraft.getInstance().getTextureManager().register(location, texture);
        entry.location = location;
        entry.bytes = 4L * width * height;
        entry.state = State.READY;
        gpuBytes += entry.bytes;
    }

    private static void evict(long now) {
        Iterator<Entry> it = ENTRIES.values().iterator(); // access order: least recently used first
        while (it.hasNext()) {
            Entry entry = it.next();
            boolean stale = now - entry.lastUsed > KEEP_ALIVE_NANOS;
            if (entry.state == State.READY && stale && gpuBytes > GPU_BUDGET_BYTES) {
                release(entry);
                it.remove();
            } else if (entry.state == State.FAILED && now - entry.failedAt > RETRY_AFTER_NANOS && stale) {
                it.remove();
            }
        }
    }

    private static void startLoads(long now) {
        while (inFlight < MAX_CONCURRENT_LOADS && !PENDING.isEmpty()) {
            Entry entry = PENDING.pollLast(); // most recently requested first: what is on screen now
            if (entry.state != State.QUEUED || ENTRIES.get(entry.key) != entry) {
                continue;
            }
            if (now - entry.lastUsed > PENDING_STALE_NANOS) {
                ENTRIES.remove(entry.key); // scrolled away before its turn; asked again if it comes back
                continue;
            }
            entry.state = State.LOADING;
            inFlight++;
            int gen = generation;
            String key = entry.key;
            String url = entry.url;
            Path file = entry.file;
            ResourceLocation resource = entry.resource;
            CompletableFuture.runAsync(() -> RESULTS.add(load(key, url, file, resource, gen)), Util.nonCriticalIoPool());
        }
    }

    /** Releases every cover texture and forgets queued work. Call when the Workshop closes for good. */
    public static void clear() {
        for (Entry entry : ENTRIES.values()) {
            if (entry.state == State.READY) {
                release(entry);
            }
        }
        ENTRIES.clear();
        PENDING.clear();
        generation++;
        inFlight = 0;
        gpuBytes = 0;
    }

    private static void release(Entry entry) {
        if (entry.location != null) {
            Minecraft.getInstance().getTextureManager().release(entry.location);
            gpuBytes -= entry.bytes;
            entry.location = null;
        }
        entry.state = State.MISSING;
    }

    // ---- IO pool --------------------------------------------------------------------------------------------

    private static Result load(String key, String url, Path file, ResourceLocation resource, int gen) {
        try {
            byte[] bytes = url != null ? fetch(url) : file != null ? readLocal(file) : readResource(resource);
            return new Result(key, gen, decode(bytes));
        } catch (Exception | OutOfMemoryError e) {
            MusicBattleMod.LOGGER.debug("Workshop cover {} failed: {}", key, e.toString());
            return new Result(key, gen, null);
        }
    }

    private static byte[] readLocal(Path file) throws IOException {
        if (Files.size(file) > MAX_SOURCE_BYTES) {
            throw new IOException("Cover file too large: " + file);
        }
        return Files.readAllBytes(file);
    }

    private static byte[] readResource(ResourceLocation location) throws IOException {
        var resource = Minecraft.getInstance().getResourceManager().getResource(location)
                .orElseThrow(() -> new IOException("No such resource: " + location));
        try (InputStream in = resource.open()) {
            return UiHttp.readLimited(in, MAX_SOURCE_BYTES, location.toString());
        }
    }

    private static byte[] fetch(String url) throws IOException {
        Path directory = diskCacheDirectory();
        Path cached = directory.resolve(sha256(url) + ".img");
        if (Files.isRegularFile(cached)) {
            try {
                Files.setLastModifiedTime(cached, FileTime.fromMillis(System.currentTimeMillis()));
            } catch (IOException ignored) {
                // A read-only cache still serves.
            }
            return readLocal(cached);
        }
        byte[] bytes = UiHttp.get(url, MAX_SOURCE_BYTES);
        try {
            Files.createDirectories(directory);
            Path temp = directory.resolve(cached.getFileName() + "." + UUID.randomUUID() + ".tmp");
            Files.write(temp, bytes);
            try {
                Files.move(temp, cached, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temp);
            }
            if (DISK_TRIMMED.compareAndSet(false, true)) {
                trimDiskCache(directory);
            }
        } catch (IOException e) {
            MusicBattleMod.LOGGER.debug("Workshop cover cache write failed: {}", e.toString());
        }
        return bytes;
    }

    public static Path diskCacheDirectory() {
        return MusicBattleTracks.tracksRootDirectory().resolve(".workshop_cache").resolve("covers");
    }

    /** Deletes the least recently used cached files until the folder fits {@link #DISK_BUDGET_BYTES}. */
    static void trimDiskCache(Path directory) {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> stream = Files.list(directory)) {
            stream.filter(path -> path.getFileName().toString().endsWith(".img")).forEach(files::add);
        } catch (IOException e) {
            return;
        }
        long total = 0;
        List<long[]> info = new ArrayList<>();
        for (int i = 0; i < files.size(); i++) {
            try {
                long size = Files.size(files.get(i));
                long modified = Files.getLastModifiedTime(files.get(i)).toMillis();
                total += size;
                info.add(new long[] {modified, size, i});
            } catch (IOException ignored) {
                // Vanished while listing.
            }
        }
        info.sort((a, b) -> Long.compare(a[0], b[0]));
        for (long[] item : info) {
            if (total <= DISK_BUDGET_BYTES) {
                break;
            }
            try {
                Files.deleteIfExists(files.get((int) item[2]));
                total -= item[1];
            } catch (IOException ignored) {
                // In use elsewhere; try the next one.
            }
        }
    }

    static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** PNG or JPEG (anything STB reads) to an RGBA NativeImage, refusing images above {@link #MAX_DIMENSION}. */
    static NativeImage decode(byte[] bytes) throws IOException {
        ByteBuffer buffer = MemoryUtil.memAlloc(bytes.length);
        try {
            buffer.put(bytes).flip();
            int width;
            int height;
            ByteBuffer pixels;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                IntBuffer w = stack.mallocInt(1);
                IntBuffer h = stack.mallocInt(1);
                IntBuffer channels = stack.mallocInt(1);
                if (!STBImage.stbi_info_from_memory(buffer, w, h, channels)) {
                    throw new IOException("Unsupported image: " + STBImage.stbi_failure_reason());
                }
                width = w.get(0);
                height = h.get(0);
                if (width <= 0 || height <= 0 || width > MAX_DIMENSION || height > MAX_DIMENSION) {
                    throw new IOException("Image dimensions " + width + "x" + height + " out of range");
                }
                pixels = STBImage.stbi_load_from_memory(buffer, w, h, channels, 4);
                if (pixels == null) {
                    throw new IOException("Could not decode image: " + STBImage.stbi_failure_reason());
                }
            }
            try {
                pixels.order(ByteOrder.LITTLE_ENDIAN);
                NativeImage image = new NativeImage(NativeImage.Format.RGBA, width, height, false);
                try {
                    // STB gives R,G,B,A bytes; NativeImage's "RGBA" int is the little-endian read of those bytes.
                    for (int y = 0; y < height; y++) {
                        int row = y * width;
                        for (int x = 0; x < width; x++) {
                            image.setPixelRGBA(x, y, pixels.getInt((row + x) * 4));
                        }
                    }
                } catch (RuntimeException e) {
                    image.close();
                    throw e;
                }
                return image;
            } finally {
                STBImage.stbi_image_free(pixels);
            }
        } finally {
            MemoryUtil.memFree(buffer);
        }
    }

    private static void closeQuietly(NativeImage image) {
        if (image != null) {
            image.close();
        }
    }

    private static final class Entry {
        final String key;
        final String url;
        final Path file;
        final ResourceLocation resource;
        State state = State.MISSING;
        ResourceLocation location;
        long bytes;
        long lastUsed;
        long failedAt;

        Entry(String key, String url, Path file, ResourceLocation resource) {
            this.key = key;
            this.url = url;
            this.file = file;
            this.resource = resource;
        }
    }

    private record Result(String key, int generation, NativeImage image) {
    }

    /** A plain GL texture: LINEAR, clamped, no mipmaps; the CPU copy is freed by the upload. */
    static final class CoverTexture extends AbstractTexture {
        CoverTexture(NativeImage image) {
            TextureUtil.prepareImage(this.getId(), image.getWidth(), image.getHeight());
            // level, x, y, skipX, skipY, width, height, blur, clamp, mipmap, autoClose
            image.upload(0, 0, 0, 0, 0, image.getWidth(), image.getHeight(), true, true, false, true);
            this.blur = true;
        }

        @Override
        public void load(ResourceManager manager) {
        }
    }
}

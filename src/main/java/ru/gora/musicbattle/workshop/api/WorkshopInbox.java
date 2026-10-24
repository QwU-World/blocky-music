package ru.gora.musicbattle.workshop.api;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.LongSupplier;
import ru.gora.musicbattle.workshop.WorkshopLayout;

/**
 * {@code music_battle_tracks/workshop_inbox/}: packages a player saved there, or the QwU installer copied there after a
 * double click (spec 06 section 5, docs/WORKSHOP.md owner decision of 17.09.2026). A scan is a cheap directory listing;
 * a file is offered for install only once its size and modification time have stayed the same for one scan and it is
 * at least {@value #SETTLE_MS} ms old, so a copy in progress is never read. Invalid packages move to
 * {@code rejected/} with a {@code .txt} reason next to them.
 *
 * <p>Two kinds of file are picked up, and {@link #scan()} and {@link #scanRequests()} keep them apart:
 * <ul>
 *   <li>{@code *.blockymap} — a package, installed straight from the file;</li>
 *   <li>{@code *.blockymap-request} — a {@link WorkshopRequest}: the helper asking the mod to download a set.
 *       These are tiny and written with a temporary file and a rename, so they are complete the moment they appear
 *       and the settle wait would only make a click on the site feel slow. They are offered at once instead.</li>
 * </ul>
 */
public final class WorkshopInbox {
    static final long SETTLE_MS = 1500;
    public static final int MAX_FILES_PER_SCAN = 32;
    /** Cap on requests offered per scan, so a folder full of them cannot flood the download queue. */
    public static final int MAX_REQUESTS_PER_SCAN = 8;

    private record Seen(long size, long modifiedMs) {
    }

    private final Path tracksRoot;
    private final LongSupplier clock;
    private final Map<Path, Seen> seen = new HashMap<>();

    public WorkshopInbox(Path tracksRoot, LongSupplier clock) {
        this.tracksRoot = tracksRoot.toAbsolutePath().normalize();
        this.clock = clock;
    }

    public Path directory() {
        return tracksRoot.resolve(WorkshopLayout.INBOX_DIRECTORY_NAME);
    }

    public Path rejectedDirectory() {
        return directory().resolve(WorkshopLayout.INBOX_REJECTED_DIRECTORY_NAME);
    }

    /** Creates the inbox folder (so players can find it). */
    public void ensureDirectory() throws IOException {
        Path inbox = directory();
        if (Files.isSymbolicLink(inbox)) {
            throw new IOException("the inbox is a symbolic link");
        }
        Files.createDirectories(inbox);
    }

    /** Settled packages ready to install, oldest first. Blocking directory listing. */
    public synchronized List<Path> scan() {
        Path inbox = directory();
        List<Path> ready = new ArrayList<>();
        if (!Files.isDirectory(inbox, LinkOption.NOFOLLOW_LINKS)) {
            seen.clear();
            return ready;
        }
        Map<Path, Seen> now = new HashMap<>();
        List<Object[]> candidates = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(inbox)) {
            for (Path file : stream) {
                String name = String.valueOf(file.getFileName()).toLowerCase(Locale.ROOT);
                if (!name.endsWith(WorkshopLayout.PACKAGE_EXTENSION) || name.startsWith(".")) {
                    continue;
                }
                BasicFileAttributes attributes;
                try {
                    attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                } catch (IOException exception) {
                    continue;
                }
                if (!attributes.isRegularFile()) {
                    continue;
                }
                Seen current = new Seen(attributes.size(), attributes.lastModifiedTime().toMillis());
                now.put(file, current);
                Seen previous = seen.get(file);
                if (current.equals(previous) && clock.getAsLong() - current.modifiedMs() >= SETTLE_MS) {
                    candidates.add(new Object[]{file, current.modifiedMs()});
                }
            }
        } catch (IOException exception) {
            return ready;
        }
        seen.clear();
        seen.putAll(now);
        candidates.sort((a, b) -> Long.compare((Long) a[1], (Long) b[1]));
        for (Object[] candidate : candidates) {
            if (ready.size() >= MAX_FILES_PER_SCAN) {
                break;
            }
            ready.add((Path) candidate[0]);
        }
        return ready;
    }

    /**
     * Request files waiting in the inbox, oldest first (spec 07 section 8). No settle wait: the helper writes them
     * atomically, so one is complete as soon as it is there, and a click on the site should not wait two scans.
     * Blocking directory listing.
     */
    public List<Path> scanRequests() {
        Path inbox = directory();
        List<Path> ready = new ArrayList<>();
        if (!Files.isDirectory(inbox, LinkOption.NOFOLLOW_LINKS)) {
            return ready;
        }
        List<Object[]> candidates = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(inbox)) {
            for (Path file : stream) {
                if (!WorkshopRequest.isRequestFile(file)) {
                    continue;
                }
                BasicFileAttributes attributes;
                try {
                    attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                } catch (IOException exception) {
                    continue;
                }
                if (!attributes.isRegularFile()) {
                    continue;
                }
                candidates.add(new Object[]{file, attributes.lastModifiedTime().toMillis()});
            }
        } catch (IOException exception) {
            return ready;
        }
        candidates.sort((a, b) -> Long.compare((Long) a[1], (Long) b[1]));
        for (Object[] candidate : candidates) {
            if (ready.size() >= MAX_REQUESTS_PER_SCAN) {
                break;
            }
            ready.add((Path) candidate[0]);
        }
        return ready;
    }

    /** Forgets a file (after it was installed, deleted or rejected). */
    public synchronized void forget(Path file) {
        seen.remove(file);
    }

    /** Deletes an installed (or already installed) package from the inbox. */
    public void consume(Path file) throws IOException {
        requireInInbox(file);
        Files.deleteIfExists(file);
        forget(file);
    }

    /**
     * Moves a package or a request to {@code rejected/} under a free name and writes {@code <name>.txt} with the
     * reason code and an English explanation. Returns the new location.
     */
    public Path reject(Path file, String reasonCode, String detail) throws IOException {
        requireInInbox(file);
        Path rejected = rejectedDirectory();
        if (Files.isSymbolicLink(rejected)) {
            throw new IOException("rejected/ is a symbolic link");
        }
        Files.createDirectories(rejected);
        String name = String.valueOf(file.getFileName());
        String extension = knownExtension(name);
        String stem = name.substring(0, name.length() - extension.length());
        Path target = rejected.resolve(name);
        for (int i = 2; Files.exists(target, LinkOption.NOFOLLOW_LINKS) || Files.exists(reasonFile(target), LinkOption.NOFOLLOW_LINKS); i++) {
            target = rejected.resolve(stem + " (" + i + ")" + extension);
        }
        AtomicFiles.move(file, target);
        forget(file);
        String text = "Blocky Music could not install this package.\n"
                + "Reason: " + (reasonCode == null ? "unknown" : reasonCode) + "\n"
                + (detail == null || detail.isEmpty() ? "" : "Detail: " + detail + "\n")
                + "Time: " + Instant.ofEpochMilli(clock.getAsLong()) + "\n";
        AtomicFiles.write(reasonFile(target), text.getBytes(StandardCharsets.UTF_8), false);
        return target;
    }

    static Path reasonFile(Path rejectedPackage) {
        return rejectedPackage.resolveSibling(rejectedPackage.getFileName() + ".txt");
    }

    /** The inbox extension a name carries, so " (2)" lands before it and not after it. */
    private static String knownExtension(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(WorkshopLayout.REQUEST_EXTENSION)) {
            return name.substring(name.length() - WorkshopLayout.REQUEST_EXTENSION.length());
        }
        if (lower.endsWith(WorkshopLayout.PACKAGE_EXTENSION)) {
            return name.substring(name.length() - WorkshopLayout.PACKAGE_EXTENSION.length());
        }
        return "";
    }

    private void requireInInbox(Path file) throws IOException {
        Path normalized = file.toAbsolutePath().normalize();
        if (!directory().equals(normalized.getParent())) {
            throw new IOException("not a file in the inbox");
        }
    }
}

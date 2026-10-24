package ru.gora.musicbattle.workshop.api;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Crash-safe small-file writes: a temporary file in the same folder, fsync, then one {@code ATOMIC_MOVE} over the
 * target. With {@code ownerOnly} the temporary file is created with POSIX {@code 0600} (and new parent folders with
 * {@code 0700}), so the secret never exists with wider permissions, not even for a moment. On Windows the user's
 * default ACL applies.
 */
public final class AtomicFiles {
    static final Set<PosixFilePermission> OWNER_FILE = PosixFilePermissions.fromString("rw-------");
    static final Set<PosixFilePermission> OWNER_DIR = PosixFilePermissions.fromString("rwx------");

    private AtomicFiles() {
    }

    public static boolean posix() {
        return FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
    }

    public static void write(Path target, byte[] bytes, boolean ownerOnly) throws IOException {
        Path file = target.toAbsolutePath().normalize();
        Path parent = file.getParent();
        createDirectories(parent, ownerOnly);
        if (Files.isSymbolicLink(file)) {
            throw new IOException("refusing to write through a symbolic link: " + file.getFileName());
        }
        Path temp = parent.resolve("." + file.getFileName() + "." + UUID.randomUUID() + ".tmp");
        List<FileAttribute<?>> attributes = new ArrayList<>();
        boolean posix = posix();
        if (ownerOnly && posix) {
            attributes.add(PosixFilePermissions.asFileAttribute(OWNER_FILE));
        }
        try {
            try (FileChannel channel = FileChannel.open(temp,
                    Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE), attributes.toArray(FileAttribute[]::new))) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            if (ownerOnly && posix) {
                Files.setPosixFilePermissions(temp, OWNER_FILE);
            }
            try {
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /** Creates missing folders; with {@code ownerOnly} on POSIX, folders created here get {@code 0700}. */
    static void createDirectories(Path directory, boolean ownerOnly) throws IOException {
        if (Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (!ownerOnly || !posix()) {
            Files.createDirectories(directory);
            return;
        }
        List<Path> missing = new ArrayList<>();
        for (Path p = directory; p != null && !Files.exists(p, LinkOption.NOFOLLOW_LINKS); p = p.getParent()) {
            missing.add(0, p);
        }
        for (Path p : missing) {
            Files.createDirectory(p, PosixFilePermissions.asFileAttribute(OWNER_DIR));
        }
    }

    /** Moves a file over another; atomic where the file system allows. */
    static void move(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}

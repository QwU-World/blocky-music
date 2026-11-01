package ru.gora.musicbattle.workshop;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Enumeration;
import java.util.HexFormat;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Verification of {@link WorkshopLayout#SIGNATURE_FILE_NAME}.
 *
 * <p>The site signs every package it serves with Ed25519 over
 * <pre>bmw-sig-v1\n&lt;setId&gt;\n&lt;revision&gt;\n&lt;contentSha256&gt;\n</pre>
 * (the private half is {@code PACKAGE_SIGNING_KEY} in the workshop {@code .env}). The signature is the raw 64 bytes,
 * it is never declared in the manifest and {@link BeatmapManifest#contentSha256()} excludes it, so signing a package
 * does not change its content identity and the same bytes verify on every server.
 *
 * <p>A Minecraft server installs client-supplied package bytes only when this says yes: without a signature anybody
 * could forge {@code ws_<any id>} with any audio and push it to the other players. That is also why
 * {@code workshop.allowPlayerMaps} can default to true: the key below is pinned, so only packages the Workshop signed
 * are installed. A build whose {@link #PUBLIC_KEY_BASE64} were empty would verify nothing and refuse them all.
 *
 * <p>Pure JDK: {@code Signature.getInstance("Ed25519")} (JDK 15+) and {@code java.util.zip}.
 */
public final class WorkshopSignature {
    /**
     * The site's public key, raw 32 bytes, base64 — the second line printed by the workshop's
     * {@code npm run keys:package}.
     *
     * <p>Pinned on 18.09.2026: this is the public half of the Workshop's {@code PACKAGE_SIGNING_KEY}. The private half
     * lives only on the Workshop server and is not in any repository. With the key pinned, {@link #configured()} is
     * true and {@link #verify} accepts a package only when its signature checks out against this key. If the
     * constant were ever blank, {@link #configured()} would be false, every {@link #verify} would return false, and
     * no server would accept a package a player supplies.
     */
    public static final String PUBLIC_KEY_BASE64 = "zVs+GHUCwhW0ypQSEoFq0CBi/jpIDGkfaRGKged9lDE=";

    /** The raw Ed25519 signature is exactly this long. */
    public static final int SIGNATURE_BYTES = 64;
    /** An Ed25519 public key is exactly this long, raw. */
    public static final int PUBLIC_KEY_BYTES = 32;
    /** The DER prefix that turns raw 32 bytes into an X.509 {@code SubjectPublicKeyInfo} the JDK can read. */
    private static final byte[] SPKI_ED25519_PREFIX =
            HexFormat.of().parseHex("302a300506032b6570032100");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    private static volatile PublicKey pinned;
    private static volatile boolean pinnedResolved;

    private WorkshopSignature() {
    }

    /** Whether this build has a pinned key at all. False means every verification fails, on purpose. */
    public static boolean configured() {
        return pinnedKey() != null;
    }

    /** The exact bytes the site signs. Package-private inputs are validated: a bad one throws. */
    public static byte[] message(long setId, int revision, String contentSha256) {
        if (setId <= 0 || revision <= 0) {
            throw new IllegalArgumentException("setId and revision must be positive");
        }
        if (contentSha256 == null || !SHA256.matcher(contentSha256).matches()) {
            throw new IllegalArgumentException("contentSha256 must be 64 lowercase hex characters");
        }
        return ("bmw-sig-v1\n" + setId + "\n" + revision + "\n" + contentSha256 + "\n").getBytes(StandardCharsets.UTF_8);
    }

    /** Verifies a raw 64-byte signature against the pinned key. Never throws: an unusable input is simply false. */
    public static boolean verify(byte[] signature, long setId, int revision, String contentSha256) {
        PublicKey key = pinnedKey();
        if (key == null || signature == null || signature.length != SIGNATURE_BYTES) {
            return false;
        }
        byte[] message;
        try {
            message = message(setId, revision, contentSha256);
        } catch (IllegalArgumentException invalid) {
            return false;
        }
        try {
            Signature ed25519 = Signature.getInstance("Ed25519");
            ed25519.initVerify(key);
            ed25519.update(message);
            return ed25519.verify(signature);
        } catch (GeneralSecurityException | RuntimeException exception) {
            return false;
        }
    }

    /**
     * Reads {@code beatmap.sig} out of a package without unpacking anything else. Empty when the entry is missing
     * or is not exactly {@link #SIGNATURE_BYTES} bytes.
     */
    public static Optional<byte[]> read(Path archive) {
        if (archive == null || !Files.isRegularFile(archive, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        try (ZipFile zip = new ZipFile(archive.toFile(), StandardCharsets.UTF_8)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (!WorkshopLayout.SIGNATURE_FILE_NAME.equals(entry.getName()) || entry.isDirectory()) {
                    continue;
                }
                try (InputStream input = zip.getInputStream(entry)) {
                    byte[] bytes = input.readNBytes(SIGNATURE_BYTES + 1);
                    return bytes.length == SIGNATURE_BYTES ? Optional.of(bytes) : Optional.empty();
                }
            }
        } catch (IOException | RuntimeException exception) {
            return Optional.empty();
        }
        return Optional.empty();
    }

    /**
     * The whole rule a server applies to bytes a client sent: the archive carries a signature the
     * pinned key accepts for exactly the offered {@code (setId, revision, contentSha256)}.
     */
    public static boolean verifyPackage(Path archive, long setId, int revision, String contentSha256) {
        return read(archive).filter(signature -> verify(signature, setId, revision, contentSha256)).isPresent();
    }

    private static PublicKey pinnedKey() {
        if (!pinnedResolved) {
            synchronized (WorkshopSignature.class) {
                if (!pinnedResolved) {
                    pinned = parse(PUBLIC_KEY_BASE64);
                    pinnedResolved = true;
                }
            }
        }
        return pinned;
    }

    /** Raw 32 bytes, base64 → an Ed25519 public key; null for anything else (including the empty placeholder). */
    static PublicKey parse(String base64) {
        if (base64 == null || base64.isBlank()) {
            return null;
        }
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(base64.trim());
        } catch (IllegalArgumentException invalid) {
            return null;
        }
        if (raw.length != PUBLIC_KEY_BYTES) {
            return null;
        }
        byte[] der = new byte[SPKI_ED25519_PREFIX.length + PUBLIC_KEY_BYTES];
        System.arraycopy(SPKI_ED25519_PREFIX, 0, der, 0, SPKI_ED25519_PREFIX.length);
        System.arraycopy(raw, 0, der, SPKI_ED25519_PREFIX.length, PUBLIC_KEY_BYTES);
        try {
            return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(der));
        } catch (GeneralSecurityException | RuntimeException exception) {
            return null;
        }
    }

    /** Test seam: verification against a key other than the pinned one (the {@code server-beatmaps} check). */
    public static boolean verifyWith(String publicKeyBase64, byte[] signature, long setId, int revision, String contentSha256) {
        PublicKey key = parse(publicKeyBase64);
        if (key == null || signature == null || signature.length != SIGNATURE_BYTES) {
            return false;
        }
        try {
            Signature ed25519 = Signature.getInstance("Ed25519");
            ed25519.initVerify(key);
            ed25519.update(message(setId, revision, contentSha256));
            return ed25519.verify(signature);
        } catch (GeneralSecurityException | RuntimeException exception) {
            return false;
        }
    }
}

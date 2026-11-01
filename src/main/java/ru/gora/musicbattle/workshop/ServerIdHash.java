package ru.gora.musicbattle.workshop;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import java.util.UUID;

/**
 * {@code bmw-join-v1}: the Minecraft-style {@code serverId} the mod passes to {@code joinServer} and the workshop
 * backend recomputes for {@code hasJoined}.
 *
 * <pre>
 * serverId = mcHex( SHA1( "blocky-music-workshop/mc-join/v1" || 0x00 || nonce(32) || clientSalt(32) || uuid(16) ) )
 * </pre>
 *
 * The client always contributes 32 random bytes, so neither the backend nor anyone impersonating it can choose
 * the resulting hash (a string received from the network is never joined verbatim). {@link #minecraftHex} is
 * vanilla's {@code new BigInteger(digest).toString(16)}: signed, base 16, no zero padding. Pure JDK.
 */
public final class ServerIdHash {
    public static final String SCHEME = "bmw-join-v1";
    public static final String DOMAIN_TAG = "blocky-music-workshop/mc-join/v1";
    public static final int NONCE_BYTES = 32;
    public static final int SALT_BYTES = 32;

    private ServerIdHash() {
    }

    public static String compute(byte[] nonce, byte[] clientSalt, UUID profileId) {
        Objects.requireNonNull(profileId, "profileId");
        if (nonce == null || nonce.length != NONCE_BYTES) {
            throw new IllegalArgumentException("nonce must be " + NONCE_BYTES + " bytes");
        }
        if (clientSalt == null || clientSalt.length != SALT_BYTES) {
            throw new IllegalArgumentException("clientSalt must be " + SALT_BYTES + " bytes");
        }
        MessageDigest sha1 = sha1();
        sha1.update(DOMAIN_TAG.getBytes(StandardCharsets.US_ASCII));
        sha1.update((byte) 0);
        sha1.update(nonce);
        sha1.update(clientSalt);
        sha1.update(uuidBytes(profileId));
        return minecraftHex(sha1.digest());
    }

    /** A digest read as a signed two's-complement integer, printed in base 16 with a leading '-' when negative. */
    public static String minecraftHex(byte[] digest) {
        return new BigInteger(digest).toString(16);
    }

    /** SHA-1 of UTF-8 text in Minecraft hex, for the published vectors ({@code Notch}, {@code jeb_}, {@code simon}). */
    public static String minecraftHexSha1(String text) {
        return minecraftHex(sha1().digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    public static byte[] uuidBytes(UUID uuid) {
        return ByteBuffer.allocate(16).putLong(uuid.getMostSignificantBits()).putLong(uuid.getLeastSignificantBits()).array();
    }

    private static MessageDigest sha1() {
        try {
            return MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-1 is unavailable", exception);
        }
    }
}

package io.loomvault.core.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Hashing helpers.
 *
 * <p>SHA-256 is used in three places, all of which care about stability rather than security:
 * response-cache keys, definition fingerprints (archived next to every execution so a replay uses
 * the graph that actually ran), and cache-busting identifiers. A JDK built-in keeps the engine
 * dependency-free.</p>
 */
public final class Hashing {

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private Hashing() {
    }

    /** Lowercase hex SHA-256 of the UTF-8 bytes of {@code input}. */
    public static String sha256Hex(String input) {
        return sha256Hex(input == null ? new byte[0] : input.getBytes(StandardCharsets.UTF_8));
    }

    /** Lowercase hex SHA-256 of {@code input}. */
    public static String sha256Hex(byte[] input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return toHex(digest.digest(input == null ? new byte[0] : input));
        } catch (NoSuchAlgorithmException ex) {
            // SHA-256 is mandated by the Java platform; this cannot happen on a conforming JVM.
            throw new IllegalStateException("SHA-256 is not available on this JVM", ex);
        }
    }

    /** First {@code length} hex characters of the digest — handy for log correlation. */
    public static String shortHash(String input, int length) {
        String full = sha256Hex(input);
        int safe = Math.clamp(length, 4, full.length());
        return full.substring(0, safe);
    }

    private static String toHex(byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            out[i * 2] = HEX[v >>> 4];
            out[i * 2 + 1] = HEX[v & 0x0F];
        }
        return new String(out);
    }
}

package aussie.core.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Utility for cryptographically secure hashing of sensitive identifiers.
 *
 * <p>Uses SHA-256 to generate collision-resistant hashes suitable for
 * rate limiting keys, logging, and security events where the original
 * value should not be exposed but must be deterministically identifiable.
 */
public final class SecureHash {

    private static final int MAX_HEX_CHARS = 64;

    private SecureHash() {}

    /**
     * Return a truncated SHA-256 hex digest of the input string.
     *
     * <p>Uses the first {@code hexChars} characters of the full hex digest.
     * 16 hex characters = 64 bits of entropy, sufficient for rate limit keying
     * while keeping cache keys compact.
     *
     * @param input    the string to hash
     * @param hexChars number of hex characters to return (1–64)
     * @return truncated hex digest
     * @throws IllegalArgumentException if hexChars is less than 1 or greater than 64
     */
    public static String truncatedSha256(String input, int hexChars) {
        if (hexChars < 1 || hexChars > MAX_HEX_CHARS) {
            throw new IllegalArgumentException("hexChars must be between 1 and " + MAX_HEX_CHARS + ", got " + hexChars);
        }
        try {
            final var digest = MessageDigest.getInstance("SHA-256");
            final var hashBytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            final var fullHex = HexFormat.of().formatHex(hashBytes);
            return fullHex.substring(0, hexChars);
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError("SHA-256 must be available per Java spec", e);
        }
    }
}

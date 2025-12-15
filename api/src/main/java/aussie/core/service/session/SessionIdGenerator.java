package aussie.core.service.session;

import java.security.SecureRandom;
import java.util.Base64;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Generate cryptographically secure session IDs.
 *
 * <p>Session IDs are 32 bytes (256 bits) of random data encoded as
 * URL-safe Base64. This provides sufficient entropy to prevent
 * session ID guessing attacks.
 */
@ApplicationScoped
public class SessionIdGenerator {

    private static final int SESSION_ID_BYTES = 32; // 256 bits
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    /**
     * Generate a new cryptographically secure session ID.
     *
     * @return A URL-safe Base64 encoded session ID (43 characters)
     */
    public String generate() {
        byte[] bytes = new byte[SESSION_ID_BYTES];
        SECURE_RANDOM.nextBytes(bytes);
        return ENCODER.encodeToString(bytes);
    }
}

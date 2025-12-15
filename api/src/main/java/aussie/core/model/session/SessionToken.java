package aussie.core.model.session;

import java.time.Instant;
import java.util.Set;

/**
 * Represents a JWS token generated from a session for downstream services.
 *
 * <p>Session tokens are short-lived JWS tokens that Aussie generates and
 * includes in requests forwarded to downstream services. This allows
 * downstream services to verify the user's identity without accessing
 * the session store directly.
 *
 * @param token The signed JWS token string
 * @param expiresAt Token expiration timestamp
 * @param sessionId Reference to the parent session
 * @param claims Set of claim names included in the token
 */
public record SessionToken(String token, Instant expiresAt, String sessionId, Set<String> claims) {

    /**
     * Checks if the token has expired.
     */
    public boolean isExpired() {
        return expiresAt != null && Instant.now().isAfter(expiresAt);
    }
}

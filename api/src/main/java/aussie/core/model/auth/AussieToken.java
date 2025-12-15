package aussie.core.model.auth;

import java.time.Instant;
import java.util.Map;

/**
 * A signed JWS token issued by Aussie for backend services.
 *
 * <p>The JWS may be empty in degraded mode when token issuance fails but
 * authentication succeeded. Check {@link #hasToken()} before using the JWS.
 *
 * @param jws       the signed JWT string (to be used as Bearer token), may be empty
 * @param subject   the subject claim from the original token
 * @param expiresAt when this token expires
 * @param claims    forwarded claims from the original token
 */
public record AussieToken(String jws, String subject, Instant expiresAt, Map<String, Object> claims) {
    public AussieToken {
        if (jws == null) {
            jws = "";
        }
        if (subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("Subject cannot be null or blank");
        }
        if (expiresAt == null) {
            throw new IllegalArgumentException("ExpiresAt cannot be null");
        }
        if (claims == null) {
            claims = Map.of();
        }
    }

    /**
     * Check if this token has a valid JWS to forward to backends.
     *
     * @return true if the JWS is present and non-empty
     */
    public boolean hasToken() {
        return !jws.isBlank();
    }
}

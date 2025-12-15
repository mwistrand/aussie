package aussie.core.model.auth;

import java.time.Instant;
import java.util.Map;

/**
 * Result of validating an incoming bearer token.
 */
public sealed interface TokenValidationResult {

    /**
     * Token was successfully validated.
     *
     * @param subject   the token subject (sub claim)
     * @param issuer    the token issuer (iss claim)
     * @param claims    all claims from the token
     * @param expiresAt when the token expires
     */
    record Valid(String subject, String issuer, Map<String, Object> claims, Instant expiresAt)
            implements TokenValidationResult {
        public Valid {
            if (subject == null || subject.isBlank()) {
                throw new IllegalArgumentException("Subject cannot be null or blank");
            }
            if (issuer == null || issuer.isBlank()) {
                throw new IllegalArgumentException("Issuer cannot be null or blank");
            }
            if (claims == null) {
                claims = Map.of();
            }
        }
    }

    /**
     * Token validation failed.
     *
     * @param reason description of why validation failed
     */
    record Invalid(String reason) implements TokenValidationResult {}

    /**
     * No token was provided in the request.
     */
    record NoToken() implements TokenValidationResult {}
}

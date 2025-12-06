package aussie.core.model;

import java.time.Duration;
import java.util.Set;

/**
 * Configuration for Aussie's JWS token issuance.
 *
 * @param issuer          Aussie's issuer claim for outbound JWS tokens
 * @param keyId           current signing key ID (for key rotation)
 * @param tokenTtl        TTL for issued JWS tokens
 * @param forwardedClaims claims to forward from the original token
 */
public record JwsConfig(String issuer, String keyId, Duration tokenTtl, Set<String> forwardedClaims) {

    public JwsConfig {
        if (issuer == null || issuer.isBlank()) {
            throw new IllegalArgumentException("Issuer cannot be null or blank");
        }
        if (keyId == null || keyId.isBlank()) {
            throw new IllegalArgumentException("Key ID cannot be null or blank");
        }
        if (tokenTtl == null) {
            tokenTtl = Duration.ofMinutes(5);
        }
        if (forwardedClaims == null) {
            forwardedClaims = Set.of("sub", "email", "name");
        }
    }

    /**
     * Default configuration for development/testing.
     */
    public static JwsConfig defaults() {
        return new JwsConfig(
                "aussie-gateway", "v1", Duration.ofMinutes(5), Set.of("sub", "email", "name", "groups", "roles"));
    }
}

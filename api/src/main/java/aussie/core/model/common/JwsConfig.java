package aussie.core.model.common;

import java.time.Duration;
import java.util.Set;

/**
 * Configuration for Aussie's JWS token issuance.
 *
 * @param issuer          Aussie's issuer claim for outbound JWS tokens
 * @param keyId           current signing key ID (for key rotation)
 * @param tokenTtl        default TTL for issued JWS tokens
 * @param maxTokenTtl     maximum allowed TTL (tokens with longer expiry are clamped)
 * @param forwardedClaims claims to forward from the original token
 */
public record JwsConfig(
        String issuer, String keyId, Duration tokenTtl, Duration maxTokenTtl, Set<String> forwardedClaims) {

    private static final Duration DEFAULT_TTL = Duration.ofMinutes(5);
    private static final Duration DEFAULT_MAX_TTL = Duration.ofHours(24);

    public JwsConfig {
        if (issuer == null || issuer.isBlank()) {
            throw new IllegalArgumentException("Issuer cannot be null or blank");
        }
        if (keyId == null || keyId.isBlank()) {
            throw new IllegalArgumentException("Key ID cannot be null or blank");
        }
        if (tokenTtl == null) {
            tokenTtl = DEFAULT_TTL;
        }
        if (maxTokenTtl == null) {
            maxTokenTtl = DEFAULT_MAX_TTL;
        }
        if (forwardedClaims == null) {
            forwardedClaims = Set.of("sub", "email", "name");
        }
    }

    /**
     * Constructor for backward compatibility (without maxTokenTtl).
     */
    public JwsConfig(String issuer, String keyId, Duration tokenTtl, Set<String> forwardedClaims) {
        this(issuer, keyId, tokenTtl, DEFAULT_MAX_TTL, forwardedClaims);
    }

    /**
     * Calculate the effective TTL, clamping to the maximum allowed.
     *
     * @param requestedTtl the requested TTL (e.g., from incoming token)
     * @return the effective TTL (minimum of requested and max)
     */
    public Duration effectiveTtl(Duration requestedTtl) {
        if (requestedTtl == null) {
            return tokenTtl;
        }
        // Use the minimum of requested TTL and max TTL
        if (requestedTtl.compareTo(maxTokenTtl) > 0) {
            return maxTokenTtl;
        }
        return requestedTtl;
    }

    /**
     * Default configuration for development/testing.
     */
    public static JwsConfig defaults() {
        return new JwsConfig(
                "aussie-gateway",
                "v1",
                DEFAULT_TTL,
                DEFAULT_MAX_TTL,
                Set.of("sub", "email", "name", "groups", "roles", "effective_permissions"));
    }
}

package aussie.core.model.common;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;

/**
 * Configuration for Aussie's JWS token issuance.
 *
 * @param issuer          Aussie's issuer claim for outbound JWS tokens
 * @param keyId           current signing key ID (for key rotation)
 * @param tokenTtl        default TTL for issued JWS tokens
 * @param maxTokenTtl     maximum allowed TTL (tokens with longer expiry are clamped)
 * @param forwardedClaims claims to forward from the original token
 * @param defaultAudience default audience claim when not specified per-route
 * @param requireAudience whether to require an audience claim in all tokens
 */
public record JwsConfig(
        String issuer,
        String keyId,
        Duration tokenTtl,
        Duration maxTokenTtl,
        Set<String> forwardedClaims,
        Optional<String> defaultAudience,
        boolean requireAudience) {

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
        if (tokenTtl.isZero() || tokenTtl.isNegative()) {
            throw new IllegalArgumentException("Token TTL must be positive");
        }
        if (maxTokenTtl.isZero() || maxTokenTtl.isNegative()) {
            throw new IllegalArgumentException("Maximum token TTL must be positive");
        }
        if (forwardedClaims == null) {
            forwardedClaims = Set.of("sub", "email", "name");
        }
        if (defaultAudience == null) {
            defaultAudience = Optional.empty();
        }
    }

    /**
     * Constructor for backward compatibility (without audience configuration).
     */
    public JwsConfig(
            String issuer, String keyId, Duration tokenTtl, Duration maxTokenTtl, Set<String> forwardedClaims) {
        this(issuer, keyId, tokenTtl, maxTokenTtl, forwardedClaims, Optional.empty(), false);
    }

    /**
     * Constructor for backward compatibility (without maxTokenTtl and audience).
     */
    public JwsConfig(String issuer, String keyId, Duration tokenTtl, Set<String> forwardedClaims) {
        this(issuer, keyId, tokenTtl, DEFAULT_MAX_TTL, forwardedClaims, Optional.empty(), false);
    }

    /** Calculate the effective TTL without exceeding the configured or source lifetime. */
    public Duration effectiveTtl(Duration requestedTtl) {
        final var effective = tokenTtl.compareTo(maxTokenTtl) <= 0 ? tokenTtl : maxTokenTtl;
        return requestedTtl != null && requestedTtl.compareTo(effective) < 0 ? requestedTtl : effective;
    }

    /**
     * Resolve the effective audience for a route.
     *
     * <p>
     * Priority:
     * <ol>
     *   <li>Route-specific audience (if provided)</li>
     *   <li>Default audience from configuration</li>
     *   <li>Service ID (if requireAudience is true and no audience found)</li>
     * </ol>
     *
     * @param routeAudience route-specific audience
     * @param serviceId     fallback service ID
     * @return the effective audience, or empty if none required
     */
    public Optional<String> resolveAudience(Optional<String> routeAudience, String serviceId) {
        // Route-specific audience takes priority
        if (routeAudience.isPresent()) {
            return routeAudience;
        }

        // Fall back to default audience
        if (defaultAudience.isPresent()) {
            return defaultAudience;
        }

        // If audience is required but not configured, use service ID
        if (requireAudience && serviceId != null && !serviceId.isBlank()) {
            return Optional.of(serviceId);
        }

        return Optional.empty();
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
                Set.of("sub", "email", "name", "groups", "roles", "effective_permissions"),
                Optional.empty(),
                false);
    }
}

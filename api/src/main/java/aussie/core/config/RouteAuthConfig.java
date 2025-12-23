package aussie.core.config;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;

/**
 * Configuration for per-route authentication.
 *
 * <p>
 * Platform teams can configure TTL limits at multiple levels:
 * <ul>
 * <li>Global: {@code jws.max-token-ttl} - Maximum TTL for any issued token</li>
 * <li>Default: {@code jws.token-ttl} - Default TTL when not specified</li>
 * </ul>
 *
 * <p>
 * Token TTL is clamped to the most restrictive of: incoming token expiry
 * and global max TTL.
 */
@ConfigMapping(prefix = "aussie.auth.route-auth")
public interface RouteAuthConfig {

    /**
     * Enable per-route authentication.
     */
    @WithDefault("false")
    boolean enabled();

    /**
     * Configured token providers.
     */
    @WithName("providers")
    Map<String, TokenProviderProperties> providers();

    /**
     * JWS token issuance configuration.
     */
    JwsProperties jws();

    /**
     * Configuration for an external token provider.
     */
    interface TokenProviderProperties {

        /**
         * Expected issuer claim (iss).
         */
        String issuer();

        /**
         * JWKS endpoint URI for key retrieval.
         */
        @WithName("jwks-uri")
        String jwksUri();

        /**
         * Optional OIDC discovery endpoint.
         */
        @WithName("discovery-uri")
        Optional<String> discoveryUri();

        /**
         * Allowed audience claim values.
         */
        @WithDefault("")
        Set<String> audiences();

        /**
         * How often to refresh JWKS keys.
         */
        @WithName("key-refresh-interval")
        @WithDefault("PT1H")
        Duration keyRefreshInterval();

        /**
         * Claims mapping (external name -> internal name).
         */
        @WithName("claims-mapping")
        @WithDefault("")
        Map<String, String> claimsMapping();
    }

    /**
     * Configuration for JWS token issuance.
     */
    interface JwsProperties {

        /**
         * Aussie's issuer claim for outbound JWS tokens.
         */
        @WithDefault("aussie-gateway")
        String issuer();

        /**
         * Current signing key ID (for key rotation).
         */
        @WithName("key-id")
        @WithDefault("v1")
        String keyId();

        /**
         * Default TTL for issued JWS tokens.
         *
         * <p>
         * This is the default TTL used when no provider-specific or
         * incoming token TTL is available.
         */
        @WithName("token-ttl")
        @WithDefault("PT5M")
        Duration tokenTtl();

        /**
         * Maximum allowed TTL for any issued token.
         *
         * <p>
         * Platform teams can use this to enforce a hard limit on token
         * lifetime. Tokens with longer expiry from the IdP will be clamped
         * to this value.
         *
         * <p>
         * If not set, defaults to 24 hours (PT24H).
         */
        @WithName("max-token-ttl")
        @WithDefault("PT24H")
        Duration maxTokenTtl();

        /**
         * Claims to forward from the original token.
         */
        @WithName("forwarded-claims")
        @WithDefault("sub,email,name,groups,roles,effective_permissions")
        Set<String> forwardedClaims();

        /**
         * RSA private key for signing (PEM format, base64 encoded).
         * Required when route-auth is enabled.
         */
        @WithName("signing-key")
        Optional<String> signingKey();

        /**
         * Default audience claim for issued tokens.
         *
         * <p>
         * Used when an endpoint does not specify a per-route audience.
         * If empty, no audience claim is added to tokens for endpoints
         * without explicit audience configuration.
         */
        @WithName("default-audience")
        Optional<String> defaultAudience();

        /**
         * Whether to require an audience claim in all issued tokens.
         *
         * <p>
         * When true, tokens will always include an audience claim,
         * using either the endpoint-specific audience or the default audience.
         * If no default is configured and the endpoint has no audience,
         * the service ID is used as the audience.
         */
        @WithName("require-audience")
        @WithDefault("false")
        boolean requireAudience();
    }
}

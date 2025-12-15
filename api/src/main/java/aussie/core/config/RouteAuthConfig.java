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
         * TTL for issued JWS tokens.
         */
        @WithName("token-ttl")
        @WithDefault("PT5M")
        Duration tokenTtl();

        /**
         * Claims to forward from the original token.
         */
        @WithName("forwarded-claims")
        @WithDefault("sub,email,name,groups,roles")
        Set<String> forwardedClaims();

        /**
         * RSA private key for signing (PEM format, base64 encoded).
         * Required when route-auth is enabled.
         */
        @WithName("signing-key")
        Optional<String> signingKey();
    }
}

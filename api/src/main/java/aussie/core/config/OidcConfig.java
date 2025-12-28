package aussie.core.config;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;

/**
 * Configuration mapping for OIDC token exchange.
 *
 * <p>Configuration prefix: {@code aussie.auth.oidc}
 *
 * <p>This configuration controls how Aussie exchanges authorization codes
 * for tokens with identity providers, including session creation and
 * refresh token handling.
 */
@ConfigMapping(prefix = "aussie.auth.oidc")
public interface OidcConfig {

    /**
     * Token exchange configuration.
     */
    @WithName("token-exchange")
    TokenExchangeConfig tokenExchange();

    /**
     * Token exchange specific configuration.
     */
    interface TokenExchangeConfig {

        /**
         * Enable OIDC token exchange.
         *
         * <p>When disabled, the /auth/oidc/token endpoint will return
         * a feature disabled error.
         *
         * @return true if token exchange is enabled (default: false)
         */
        @WithDefault("false")
        boolean enabled();

        /**
         * Create an Aussie session after successful token exchange.
         *
         * <p>When true and an ID token is returned, Aussie creates a session
         * from the token claims. When false, tokens are returned directly
         * without session creation.
         *
         * @return true to create session (default: true)
         */
        @WithDefault("true")
        @WithName("create-session")
        boolean createSession();

        /**
         * Provider name for token exchange.
         *
         * <p>Available providers: default, or custom SPI name.
         *
         * @return Provider name (default: default)
         */
        @WithDefault("default")
        String provider();

        /**
         * IdP token endpoint URL.
         *
         * <p>The OAuth2/OIDC token endpoint where authorization codes
         * are exchanged for tokens.
         *
         * @return Token endpoint URL
         */
        @WithName("token-endpoint")
        Optional<String> tokenEndpoint();

        /**
         * OAuth2 client ID.
         *
         * @return Client ID
         */
        @WithName("client-id")
        Optional<String> clientId();

        /**
         * OAuth2 client secret.
         *
         * <p>For confidential clients, this secret is used to authenticate
         * with the token endpoint.
         *
         * @return Client secret
         */
        @WithName("client-secret")
        Optional<String> clientSecret();

        /**
         * Client authentication method.
         *
         * <p>How to authenticate with the token endpoint:
         * <ul>
         *   <li>client_secret_basic - HTTP Basic auth (recommended)</li>
         *   <li>client_secret_post - Credentials in form body</li>
         * </ul>
         *
         * @return Auth method (default: client_secret_basic)
         */
        @WithName("client-auth-method")
        @WithDefault("client_secret_basic")
        String clientAuthMethod();

        /**
         * Default scopes to request.
         *
         * <p>Space-separated list of OAuth2 scopes. The "openid" scope
         * is required for ID token responses.
         *
         * @return Scopes to request
         */
        @WithDefault("openid profile email")
        Set<String> scopes();

        /**
         * HTTP timeout for token exchange requests.
         *
         * @return Timeout duration (default: 10 seconds)
         */
        @WithDefault("PT10S")
        Duration timeout();

        /**
         * Expected issuer claim for token validation.
         *
         * <p>If set, ID tokens must have this issuer claim.
         *
         * @return Expected issuer
         */
        Optional<String> issuer();

        /**
         * JWKS URI for token validation.
         *
         * <p>URL to fetch signing keys for ID token validation.
         *
         * @return JWKS endpoint URL
         */
        @WithName("jwks-uri")
        Optional<String> jwksUri();

        /**
         * Expected audience claims for token validation.
         *
         * <p>ID tokens must include one of these audiences.
         *
         * @return Allowed audiences
         */
        Optional<Set<String>> audiences();

        /**
         * Refresh token configuration.
         */
        @WithName("refresh-token")
        RefreshTokenConfig refreshToken();
    }

    /**
     * Refresh token storage configuration.
     */
    interface RefreshTokenConfig {

        /**
         * Store refresh tokens in Aussie.
         *
         * <p>When true, refresh tokens are stored and can be used
         * for automatic token renewal.
         *
         * @return true to store refresh tokens (default: true)
         */
        @WithDefault("true")
        boolean store();

        /**
         * Default TTL for stored refresh tokens.
         *
         * <p>Used when the token response doesn't include expiry info.
         *
         * @return Default TTL (default: 7 days)
         */
        @WithName("default-ttl")
        @WithDefault("PT168H")
        Duration defaultTtl();

        /**
         * Redis key prefix for refresh tokens.
         *
         * @return Key prefix (default: aussie:oidc:refresh:)
         */
        @WithName("key-prefix")
        @WithDefault("aussie:oidc:refresh:")
        String keyPrefix();
    }
}

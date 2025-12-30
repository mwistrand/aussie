package aussie.core.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

/**
 * Configuration mapping for token translation.
 *
 * <p>Configuration prefix: {@code aussie.auth.token-translation}
 *
 * <p>Token translation allows mapping external IdP token claims to Aussie's
 * internal authorization model. This is useful when your IdP uses custom
 * claim structures (roles in scopes, groups in custom claims, etc.).
 *
 * <h2>Example Configuration</h2>
 * <pre>
 * # Enable token translation
 * aussie.auth.token-translation.enabled=true
 *
 * # Select translation provider (default extracts from roles/permissions claims)
 * aussie.auth.token-translation.provider=default
 * </pre>
 *
 * <h2>Environment Variables</h2>
 * <pre>
 * AUSSIE_AUTH_TOKEN_TRANSLATION_ENABLED=true
 * AUSSIE_AUTH_TOKEN_TRANSLATION_PROVIDER=keycloak
 * </pre>
 */
@ConfigMapping(prefix = "aussie.auth.token-translation")
public interface TokenTranslationConfig {

    /**
     * Enable token translation.
     *
     * <p>When disabled (default), the standard claim extraction is used:
     * roles from the "roles" claim and permissions from the "permissions" claim.
     *
     * <p>When enabled, the configured provider performs the translation,
     * allowing custom claim structures to be mapped.
     *
     * @return true if token translation is enabled (default: false)
     */
    @WithDefault("false")
    boolean enabled();

    /**
     * Provider name for token translation.
     *
     * <p>Available providers:
     * <ul>
     *   <li>default - Extracts from standard "roles" and "permissions" claims</li>
     *   <li>Custom SPI implementations</li>
     * </ul>
     *
     * <p>Provider selection order:
     * <ol>
     *   <li>This configured provider name</li>
     *   <li>Highest priority available provider</li>
     * </ol>
     *
     * @return Provider name (default: default)
     */
    @WithDefault("default")
    String provider();

    /**
     * Cache configuration for translated claims.
     */
    Cache cache();

    /**
     * Cache settings for token translation results.
     */
    interface Cache {
        /**
         * TTL for cached translation results in seconds.
         *
         * <p>Since token translation is deterministic per token, results can be cached
         * for the lifetime of the token. However, a shorter TTL allows for quicker
         * updates when role mappings change.
         *
         * @return cache TTL in seconds (default: 300 = 5 minutes)
         */
        @WithDefault("300")
        int ttlSeconds();

        /**
         * Maximum number of cached translation results.
         *
         * @return maximum cache size (default: 10000)
         */
        @WithDefault("10000")
        long maxSize();
    }
}

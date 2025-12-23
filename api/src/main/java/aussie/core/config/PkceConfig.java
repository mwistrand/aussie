package aussie.core.config;

import java.time.Duration;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

/**
 * Configuration mapping for PKCE (Proof Key for Code Exchange) support.
 *
 * <p>Configuration prefix: {@code aussie.auth.pkce}
 *
 * <p>PKCE provides protection against authorization code interception attacks,
 * particularly important for SPAs and mobile applications where client secrets
 * cannot be securely stored.
 */
@ConfigMapping(prefix = "aussie.auth.pkce")
public interface PkceConfig {

    /**
     * Enable PKCE support.
     *
     * <p>When enabled, Aussie will accept and process PKCE parameters
     * in authorization requests.
     *
     * @return true if PKCE is enabled (default: true)
     */
    @WithDefault("true")
    boolean enabled();

    /**
     * Require PKCE for all authorization requests.
     *
     * <p>When true, authorization requests without valid PKCE parameters
     * will be rejected with a 400 error. When false, PKCE is optional
     * but recommended.
     *
     * @return true if PKCE is required (default: true)
     */
    @WithDefault("true")
    boolean required();

    /**
     * Challenge TTL (time-to-live).
     *
     * <p>How long a PKCE challenge remains valid after the authorization
     * request is initiated. Should be long enough for user authentication
     * but short enough to limit the window for attacks.
     *
     * @return Challenge duration (default: 10 minutes)
     */
    @WithDefault("PT10M")
    Duration challengeTtl();

    /**
     * Storage configuration for PKCE challenges.
     */
    StorageConfig storage();

    /**
     * Storage configuration options.
     */
    interface StorageConfig {

        /**
         * Storage provider name.
         *
         * <p>Available providers: redis, memory, or custom SPI name.
         *
         * @return Provider name (default: redis)
         */
        @WithDefault("redis")
        String provider();

        /**
         * Redis-specific configuration.
         */
        RedisConfig redis();

        /**
         * Redis storage configuration.
         */
        interface RedisConfig {

            /**
             * Key prefix for PKCE challenges in Redis.
             *
             * @return Key prefix (default: aussie:pkce:)
             */
            @WithDefault("aussie:pkce:")
            String keyPrefix();
        }
    }
}

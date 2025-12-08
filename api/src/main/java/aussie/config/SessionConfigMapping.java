package aussie.config;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

/**
 * Configuration mapping for session management.
 *
 * <p>Configuration prefix: {@code aussie.session}
 */
@ConfigMapping(prefix = "aussie.session")
public interface SessionConfigMapping {

    /**
     * Enable session management.
     *
     * <p>When disabled, Aussie uses the existing Authorization header flow
     * and session endpoints are not available.
     *
     * @return true if sessions are enabled (default: true)
     */
    @WithDefault("true")
    boolean enabled();

    /**
     * Cookie configuration.
     */
    CookieConfig cookie();

    /**
     * Session TTL (time-to-live).
     *
     * @return Session duration (default: 8 hours)
     */
    @WithDefault("PT8H")
    Duration ttl();

    /**
     * Idle timeout - invalidate session after inactivity.
     *
     * @return Idle duration (default: 30 minutes)
     */
    @WithDefault("PT30M")
    Duration idleTimeout();

    /**
     * Enable sliding expiration.
     *
     * <p>When enabled, the session TTL is refreshed on each request,
     * effectively extending the session as long as the user is active.
     *
     * @return true if sliding expiration is enabled (default: true)
     */
    @WithDefault("true")
    boolean slidingExpiration();

    /**
     * ID generation configuration.
     */
    IdGenerationConfig idGeneration();

    /**
     * Storage configuration.
     */
    StorageConfig storage();

    /**
     * JWS token configuration for downstream services.
     */
    JwsConfig jws();

    /**
     * Cookie configuration options.
     */
    interface CookieConfig {

        /**
         * Session cookie name.
         *
         * @return Cookie name (default: aussie_session)
         */
        @WithDefault("aussie_session")
        String name();

        /**
         * Cookie path.
         *
         * @return Cookie path (default: /)
         */
        @WithDefault("/")
        String path();

        /**
         * Cookie domain.
         *
         * <p>If not set, defaults to the request domain.
         *
         * @return Cookie domain (optional)
         */
        Optional<String> domain();

        /**
         * Mark cookie as secure (HTTPS only).
         *
         * @return true if secure (default: true)
         */
        @WithDefault("true")
        boolean secure();

        /**
         * Mark cookie as HttpOnly (not accessible via JavaScript).
         *
         * @return true if HttpOnly (default: true)
         */
        @WithDefault("true")
        boolean httpOnly();

        /**
         * SameSite attribute.
         *
         * @return SameSite value: Strict, Lax, or None (default: Lax)
         */
        @WithDefault("Lax")
        String sameSite();
    }

    /**
     * Session ID generation configuration.
     */
    interface IdGenerationConfig {

        /**
         * Maximum retries for session ID collision.
         *
         * <p>If a generated session ID already exists in storage,
         * the system will retry up to this many times before failing.
         *
         * @return Max retry attempts (default: 3)
         */
        @WithDefault("3")
        int maxRetries();
    }

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
             * Key prefix for session data in Redis.
             *
             * @return Key prefix (default: aussie:session:)
             */
            @WithDefault("aussie:session:")
            String keyPrefix();
        }
    }

    /**
     * JWS token configuration for downstream services.
     */
    interface JwsConfig {

        /**
         * Enable JWS token generation for downstream services.
         *
         * @return true if JWS tokens are enabled (default: true)
         */
        @WithDefault("true")
        boolean enabled();

        /**
         * JWS token TTL (should be short-lived).
         *
         * @return Token duration (default: 5 minutes)
         */
        @WithDefault("PT5M")
        Duration ttl();

        /**
         * JWS issuer claim.
         *
         * @return Issuer value (default: aussie-gateway)
         */
        @WithDefault("aussie-gateway")
        String issuer();

        /**
         * JWS audience claim (optional).
         *
         * @return Audience value (optional)
         */
        Optional<String> audience();

        /**
         * Claims to include in JWS token from session.
         *
         * @return List of claim names (default: sub, email, name, roles)
         */
        @WithDefault("sub,email,name,roles")
        List<String> includeClaims();
    }
}

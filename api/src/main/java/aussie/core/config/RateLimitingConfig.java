package aussie.core.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

import aussie.core.model.ratelimit.RateLimitAlgorithm;

/**
 * Configuration mapping for rate limiting.
 *
 * <p>Configuration prefix: {@code aussie.rate-limiting}
 *
 * <p>Platform teams configure rate limiting via environment variables.
 * Service teams cannot override the algorithm or platform maximum.
 *
 * <h2>Environment Variables</h2>
 * <ul>
 *   <li>{@code AUSSIE_RATE_LIMITING_ENABLED} - Enable/disable rate limiting</li>
 *   <li>{@code AUSSIE_RATE_LIMITING_ALGORITHM} - Algorithm: BUCKET, FIXED_WINDOW, SLIDING_WINDOW</li>
 *   <li>{@code AUSSIE_RATE_LIMITING_PLATFORM_MAX_REQUESTS_PER_WINDOW} - Maximum ceiling</li>
 *   <li>{@code AUSSIE_RATE_LIMITING_PLATFORM_MAX_WINDOW_SECONDS} - Maximum window duration</li>
 * </ul>
 */
@ConfigMapping(prefix = "aussie.rate-limiting")
public interface RateLimitingConfig {

    /**
     * Enable or disable rate limiting globally.
     *
     * @return true if rate limiting is enabled (default: true)
     */
    @WithDefault("true")
    boolean enabled();

    /**
     * Rate limiting algorithm.
     *
     * <p><b>Platform teams only.</b> Set via environment variable.
     * Service teams cannot override this setting.
     *
     * @return the algorithm (default: BUCKET)
     */
    @WithDefault("BUCKET")
    RateLimitAlgorithm algorithm();

    /**
     * Platform-wide maximum requests per window.
     *
     * <p><b>Platform teams only.</b> Service and endpoint limits cannot exceed
     * this value. Set to a generous value; defaults to effectively unlimited.
     *
     * @return maximum requests per window (default: Long.MAX_VALUE)
     */
    @WithDefault("9223372036854775807")
    long platformMaxRequestsPerWindow();

    /**
     * Platform-wide maximum window duration in seconds.
     *
     * <p><b>Platform teams only.</b> Service and endpoint window durations cannot exceed
     * this value. Set to a generous value; defaults to effectively unlimited.
     *
     * @return maximum window seconds (default: Long.MAX_VALUE)
     */
    @WithDefault("9223372036854775807")
    long platformMaxWindowSeconds();

    /**
     * Default requests per window for services without explicit configuration.
     *
     * @return default requests per window (default: 100)
     */
    @WithDefault("100")
    long defaultRequestsPerWindow();

    /**
     * Default time window duration in seconds.
     *
     * @return window duration in seconds (default: 60)
     */
    @WithDefault("60")
    long windowSeconds();

    /**
     * Default burst capacity for token bucket algorithm.
     *
     * @return burst capacity (default: 100)
     */
    @WithDefault("100")
    long burstCapacity();

    /**
     * Include X-RateLimit-* headers in responses.
     *
     * @return true to include headers (default: true)
     */
    @WithDefault("true")
    boolean includeHeaders();

    /**
     * WebSocket rate limiting configuration.
     */
    WebSocketRateLimitConfig websocket();

    /**
     * Redis backend configuration.
     */
    RedisConfig redis();

    /**
     * Behavior when the configured rate limit backend (Redis) is unavailable.
     */
    FallbackConfig fallback();

    /**
     * Redis-specific rate limiting configuration.
     */
    interface RedisConfig {

        /**
         * Enable Redis as the rate limiting backend.
         *
         * <p>When enabled and Redis is available, Redis will be used for
         * distributed rate limiting. When disabled or unavailable, falls
         * back to in-memory.
         *
         * @return true to use Redis backend (default: false)
         */
        @WithDefault("false")
        boolean enabled();
    }

    /**
     * Fallback behavior for the distributed rate limiter when the
     * remote backend cannot be reached.
     */
    interface FallbackConfig {

        /**
         * Behavior when the Redis backend errors.
         *
         * <p>{@code LOCAL_BUCKET} (default) keeps coarse local protection by
         * routing the request through the in-memory limiter using the same
         * effective rate limit. {@code DENY} fails the request closed (429).
         * {@code ALLOW} preserves the legacy fail-open behavior and is intended
         * only for dev/test profiles.
         *
         * @return the fallback behavior (default: {@code LOCAL_BUCKET})
         */
        @WithDefault("LOCAL_BUCKET")
        RateLimitFallbackBehavior behavior();
    }

    /**
     * Choices for {@link FallbackConfig#behavior()}.
     */
    enum RateLimitFallbackBehavior {
        /** Use the in-memory limiter as a coarse local backup. */
        LOCAL_BUCKET,
        /** Fail closed: reject the request with 429. */
        DENY,
        /** Fail open: allow the request (legacy behavior; dev/test only). */
        ALLOW
    }

    /**
     * WebSocket-specific rate limiting configuration.
     */
    interface WebSocketRateLimitConfig {

        /**
         * Connection establishment rate limiting.
         */
        ConnectionConfig connection();

        /**
         * Message throughput rate limiting.
         */
        MessageConfig message();

        /**
         * WebSocket connection rate limit configuration.
         */
        interface ConnectionConfig {

            /**
             * Enable connection rate limiting.
             *
             * @return true if enabled (default: true)
             */
            @WithDefault("true")
            boolean enabled();

            /**
             * Maximum new connections per window.
             *
             * @return connections per window (default: 10)
             */
            @WithDefault("10")
            long requestsPerWindow();

            /**
             * Time window in seconds.
             *
             * @return window seconds (default: 60)
             */
            @WithDefault("60")
            long windowSeconds();

            /**
             * Burst capacity for connection attempts.
             *
             * @return burst capacity (default: 5)
             */
            @WithDefault("5")
            long burstCapacity();
        }

        /**
         * WebSocket message rate limit configuration.
         */
        interface MessageConfig {

            /**
             * Enable message rate limiting.
             *
             * @return true if enabled (default: true)
             */
            @WithDefault("true")
            boolean enabled();

            /**
             * Maximum messages per window per connection.
             *
             * @return messages per window (default: 100)
             */
            @WithDefault("100")
            long requestsPerWindow();

            /**
             * Time window in seconds (typically shorter for messages).
             *
             * @return window seconds (default: 1)
             */
            @WithDefault("1")
            long windowSeconds();

            /**
             * Burst capacity for message throughput.
             *
             * @return burst capacity (default: 50)
             */
            @WithDefault("50")
            long burstCapacity();
        }
    }
}

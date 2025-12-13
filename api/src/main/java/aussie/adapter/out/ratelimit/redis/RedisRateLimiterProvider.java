package aussie.adapter.out.ratelimit.redis;

import io.quarkus.redis.datasource.ReactiveRedisDataSource;

import aussie.core.port.out.RateLimiter;
import aussie.spi.RateLimiterProvider;

/**
 * Redis-based rate limiter provider for distributed deployments.
 *
 * <p>This provider has higher priority than in-memory (10 vs 0) and is
 * selected automatically when Redis is available and configured.
 *
 * <p>Availability depends on:
 * <ul>
 *   <li>Redis data source being configured in the application</li>
 *   <li>Redis being reachable</li>
 * </ul>
 *
 * <p>When not available, falls back to in-memory rate limiting.
 */
public final class RedisRateLimiterProvider implements RateLimiterProvider {

    private static final int PRIORITY = 10;
    private static final String NAME = "redis";

    private final ReactiveRedisDataSource redisDataSource;
    private final boolean enabled;
    private final boolean redisConfigured;

    /**
     * Creates a new Redis provider with configuration.
     *
     * @param redisDataSource the Redis data source
     * @param enabled whether rate limiting is enabled
     * @param redisConfigured whether Redis is configured for rate limiting
     */
    public RedisRateLimiterProvider(ReactiveRedisDataSource redisDataSource, boolean enabled, boolean redisConfigured) {
        this.redisDataSource = redisDataSource;
        this.enabled = enabled;
        this.redisConfigured = redisConfigured;
    }

    /**
     * Default constructor for ServiceLoader.
     *
     * <p>When loaded via ServiceLoader, configuration must be injected
     * separately via the loader.
     */
    public RedisRateLimiterProvider() {
        this.redisDataSource = null;
        this.enabled = true;
        this.redisConfigured = false;
    }

    @Override
    public int priority() {
        return PRIORITY;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean isAvailable() {
        return redisConfigured && redisDataSource != null;
    }

    @Override
    public RateLimiter createRateLimiter() {
        if (redisDataSource == null) {
            throw new IllegalStateException(
                    "Provider not configured. Use RateLimiterProviderLoader for proper initialization.");
        }
        return new RedisRateLimiter(redisDataSource, enabled);
    }

    /**
     * Creates a configured provider instance.
     *
     * @param redisDataSource the Redis data source
     * @param enabled whether rate limiting is enabled
     * @param redisConfigured whether Redis is configured for rate limiting
     * @return the configured provider
     */
    public static RedisRateLimiterProvider configured(
            ReactiveRedisDataSource redisDataSource, boolean enabled, boolean redisConfigured) {
        return new RedisRateLimiterProvider(redisDataSource, enabled, redisConfigured);
    }
}

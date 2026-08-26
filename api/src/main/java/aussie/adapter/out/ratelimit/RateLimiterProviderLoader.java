package aussie.adapter.out.ratelimit;

import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;

import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import org.jboss.logging.Logger;

import aussie.adapter.out.ratelimit.memory.InMemoryRateLimiter;
import aussie.adapter.out.ratelimit.memory.InMemoryRateLimiterProvider;
import aussie.adapter.out.ratelimit.redis.RedisRateLimiter;
import aussie.adapter.out.ratelimit.redis.RedisRateLimiterProvider;
import aussie.core.config.RateLimitingConfig;
import aussie.core.port.out.Metrics;
import aussie.core.port.out.RateLimiter;
import aussie.core.service.ratelimit.AlgorithmRegistry;
import aussie.spi.RateLimiterProvider;

/**
 * CDI producer for the rate limiter.
 *
 * <p>Selects the appropriate rate limiter implementation based on configuration
 * and configuration:
 * <ul>
 *   <li>Redis (priority 10) - Used when Redis is configured</li>
 *   <li>In-memory (priority 0) - Used otherwise and for an explicit local fallback</li>
 * </ul>
 *
 * <p>When rate limiting is disabled, returns a no-op implementation.
 */
@ApplicationScoped
public class RateLimiterProviderLoader {

    private static final Logger LOG = Logger.getLogger(RateLimiterProviderLoader.class);

    private final RateLimitingConfig config;
    private final AlgorithmRegistry algorithmRegistry;
    private final Instance<ReactiveRedisDataSource> redisDataSource;
    private final Instance<Metrics> metricsInstance;

    @Inject
    public RateLimiterProviderLoader(
            RateLimitingConfig config,
            AlgorithmRegistry algorithmRegistry,
            Instance<ReactiveRedisDataSource> redisDataSource,
            Instance<Metrics> metricsInstance) {
        this.config = config;
        this.algorithmRegistry = algorithmRegistry;
        this.redisDataSource = redisDataSource;
        this.metricsInstance = metricsInstance;
    }

    /**
     * Produces the rate limiter instance for CDI injection.
     *
     * @return the configured rate limiter
     */
    @Produces
    @ApplicationScoped
    public RateLimiter produceRateLimiter() {
        if (!config.enabled()) {
            LOG.info("Rate limiting is disabled, using NoOpRateLimiter");
            return NoOpRateLimiter.getInstance();
        }

        if (!algorithmRegistry.isAvailable(config.algorithm())) {
            throw new IllegalStateException("Unsupported rate-limit algorithm: " + config.algorithm());
        }

        final var rateLimiter = createRateLimiter();
        LOG.infov(
                "Rate limiting enabled with algorithm={0}, defaultLimit={1}/{2}s",
                config.algorithm(), config.defaultRequestsPerWindow(), config.windowSeconds());

        return rateLimiter;
    }

    /**
     * Disposes the rate limiter, shutting down any cleanup executors.
     */
    void disposeRateLimiter(@Disposes RateLimiter rateLimiter) {
        if (rateLimiter instanceof InMemoryRateLimiter inMemory) {
            inMemory.shutdown();
        } else if (rateLimiter instanceof RedisRateLimiter redis) {
            redis.shutdown();
        }
    }

    private RateLimiter createRateLimiter() {
        if (config.redis().enabled()) {
            final var redisProvider = createRedisProvider()
                    .filter(RateLimiterProvider::isAvailable)
                    .orElseThrow(() -> new IllegalStateException("Redis rate limiting is configured but unavailable"));
            LOG.infov(
                    "Using rate limiter provider: {0} (fallback={1})",
                    redisProvider.name(), config.fallback().behavior());
            return redisProvider.createRateLimiter();
        }

        final var inMemoryProvider = createInMemoryProvider();
        LOG.infov("Using rate limiter provider: {0}", inMemoryProvider.name());
        return inMemoryProvider.createRateLimiter();
    }

    private Optional<RateLimiterProvider> createRedisProvider() {
        if (!config.redis().enabled()) {
            LOG.debug("Redis rate limiting not enabled in configuration");
            return Optional.empty();
        }

        if (!redisDataSource.isResolvable()) {
            LOG.warn("Redis rate limiting enabled but ReactiveRedisDataSource not available");
            return Optional.empty();
        }

        try {
            final var ds = redisDataSource.get();
            final var metrics = metricsInstance.isResolvable() ? metricsInstance.get() : null;
            final var fallback =
                    config.fallback().behavior() == RateLimitingConfig.RateLimitFallbackBehavior.LOCAL_BUCKET
                            ? createInMemoryProvider().createRateLimiter()
                            : null;
            return Optional.of(RedisRateLimiterProvider.configured(
                    ds,
                    config.enabled(),
                    true,
                    config.algorithm(),
                    fallback,
                    config.fallback().behavior(),
                    metrics));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to initialize Redis rate limiter", e);
        }
    }

    private RateLimiterProvider createInMemoryProvider() {
        return InMemoryRateLimiterProvider.configured(
                algorithmRegistry, config.algorithm(), config.enabled(), config.windowSeconds());
    }
}

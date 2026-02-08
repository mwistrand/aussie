package aussie.adapter.out.ratelimit.memory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

import io.smallrye.mutiny.Uni;
import org.jboss.logging.Logger;

import aussie.core.model.ratelimit.AlgorithmRegistry;
import aussie.core.model.ratelimit.EffectiveRateLimit;
import aussie.core.model.ratelimit.RateLimitAlgorithm;
import aussie.core.model.ratelimit.RateLimitDecision;
import aussie.core.model.ratelimit.RateLimitKey;
import aussie.core.model.ratelimit.RateLimitState;
import aussie.core.port.out.RateLimiter;

/**
 * In-memory rate limiter implementation.
 *
 * <p>Store rate limit state in a concurrent hash map. Suitable for
 * single-instance deployments or development/testing.
 *
 * <p>Limitations:
 * <ul>
 *   <li>State is not shared across instances</li>
 *   <li>State is lost on restart</li>
 * </ul>
 *
 * <p>Stale entries are automatically cleaned up after 2x the window duration
 * to prevent unbounded memory growth. Staleness is based on last access time,
 * not the algorithm's internal timestamp.
 *
 * <p>For production multi-instance deployments, use Redis-based rate limiting.
 */
public final class InMemoryRateLimiter implements RateLimiter {

    private static final Logger LOG = Logger.getLogger(InMemoryRateLimiter.class);
    private static final long CLEANUP_INTERVAL_SECONDS = 60;

    private final ConcurrentMap<String, TimestampedState> states;
    private final AlgorithmRegistry algorithmRegistry;
    private final RateLimitAlgorithm algorithm;
    private final boolean enabled;
    private final long windowMillis;
    private final LongSupplier clock;
    private final ScheduledExecutorService cleanupExecutor;

    /**
     * Create a new in-memory rate limiter.
     *
     * @param algorithmRegistry the algorithm registry
     * @param algorithm         the algorithm to use
     * @param enabled           whether rate limiting is enabled
     * @param windowSeconds     the rate limit window duration in seconds
     */
    public InMemoryRateLimiter(
            AlgorithmRegistry algorithmRegistry, RateLimitAlgorithm algorithm, boolean enabled, long windowSeconds) {
        this(algorithmRegistry, algorithm, enabled, windowSeconds, System::currentTimeMillis);
    }

    /**
     * Create a new in-memory rate limiter with a custom clock.
     *
     * @param algorithmRegistry the algorithm registry
     * @param algorithm         the algorithm to use
     * @param enabled           whether rate limiting is enabled
     * @param windowSeconds     the rate limit window duration in seconds
     * @param clock             supplies the current time in epoch milliseconds
     */
    InMemoryRateLimiter(
            AlgorithmRegistry algorithmRegistry,
            RateLimitAlgorithm algorithm,
            boolean enabled,
            long windowSeconds,
            LongSupplier clock) {
        this.states = new ConcurrentHashMap<>();
        this.algorithmRegistry = algorithmRegistry;
        this.algorithm = algorithm;
        this.enabled = enabled;
        this.windowMillis = windowSeconds * 1000;
        this.clock = clock;

        this.cleanupExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            var t = new Thread(r, "ratelimit-cleanup");
            t.setDaemon(true);
            return t;
        });

        cleanupExecutor.scheduleAtFixedRate(
                this::cleanupStaleEntries, CLEANUP_INTERVAL_SECONDS, CLEANUP_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    @Override
    public Uni<RateLimitDecision> checkAndConsume(RateLimitKey key, EffectiveRateLimit limit) {
        if (!enabled) {
            return Uni.createFrom().item(RateLimitDecision.allow());
        }

        final var cacheKey = key.toCacheKey();
        final var handler = algorithmRegistry.getHandler(algorithm);
        final var nowMillis = clock.getAsLong();

        final var decision = computeDecision(cacheKey, handler, limit, nowMillis);
        return Uni.createFrom().item(decision);
    }

    @Override
    public Uni<RateLimitDecision> getStatus(RateLimitKey key, EffectiveRateLimit limit) {
        if (!enabled) {
            return Uni.createFrom().item(RateLimitDecision.allow());
        }

        final var cacheKey = key.toCacheKey();
        final var handler = algorithmRegistry.getHandler(algorithm);
        final var nowMillis = clock.getAsLong();
        final var wrapper = states.get(cacheKey);
        final var currentState = wrapper != null ? wrapper.state() : null;

        final var status = handler.getStatus(currentState, limit, nowMillis);
        return Uni.createFrom().item(status);
    }

    @Override
    public Uni<Void> reset(RateLimitKey key) {
        final var cacheKey = key.toCacheKey();
        states.remove(cacheKey);
        return Uni.createFrom().voidItem();
    }

    @Override
    public Uni<Void> removeKeysMatching(String pattern) {
        states.keySet().removeIf(key -> key.contains(pattern));
        return Uni.createFrom().voidItem();
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Return the current number of tracked rate limit buckets.
     *
     * <p>Useful for monitoring and testing.
     *
     * @return the number of active buckets
     */
    public int getBucketCount() {
        return states.size();
    }

    /**
     * Clears all rate limit state.
     *
     * <p>Primarily for testing purposes.
     */
    public void clear() {
        states.clear();
    }

    /**
     * Shuts down the cleanup executor.
     */
    public void shutdown() {
        cleanupExecutor.shutdown();
        try {
            if (!cleanupExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                cleanupExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            cleanupExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Run stale entry cleanup immediately. Package-private for testing.
     */
    void runCleanup() {
        cleanupStaleEntries();
    }

    private void cleanupStaleEntries() {
        final var now = clock.getAsLong();
        final var staleThreshold = now - (windowMillis * 2);
        final var sizeBefore = states.size();

        states.entrySet().removeIf(entry -> entry.getValue().lastAccessMillis() < staleThreshold);

        final var removed = sizeBefore - states.size();
        if (removed > 0) {
            LOG.debugf("Cleaned up %d stale rate limit entries, %d remaining", removed, states.size());
        }
    }

    private RateLimitDecision computeDecision(
            String cacheKey,
            aussie.core.model.ratelimit.RateLimitAlgorithmHandler handler,
            EffectiveRateLimit limit,
            long nowMillis) {

        final var result = new RateLimitDecision[1];

        states.compute(cacheKey, (k, current) -> {
            final var currentState = current != null ? current.state() : null;
            final var decision = handler.checkAndConsume(currentState, limit, nowMillis);
            result[0] = decision;
            return new TimestampedState(decision.newState(), nowMillis);
        });

        return result[0];
    }

    /**
     * Wraps rate limit state with a last-access timestamp for stale entry detection.
     */
    record TimestampedState(RateLimitState state, long lastAccessMillis) {}
}

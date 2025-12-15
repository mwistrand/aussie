package aussie.adapter.out.ratelimit.memory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import io.smallrye.mutiny.Uni;

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
 * <p>
 * Stores rate limit state in a concurrent hash map. Suitable for
 * single-instance deployments or development/testing.
 *
 * <p>
 * Limitations:
 * <ul>
 * <li>State is not shared across instances</li>
 * <li>State is lost on restart</li>
 * <li>No automatic cleanup of stale entries</li>
 * </ul>
 *
 * <p>
 * For production multi-instance deployments, use Redis-based rate limiting.
 */
public final class InMemoryRateLimiter implements RateLimiter {

    private final ConcurrentMap<String, RateLimitState> states;
    private final AlgorithmRegistry algorithmRegistry;
    private final RateLimitAlgorithm algorithm;
    private final boolean enabled;

    /**
     * Creates a new in-memory rate limiter.
     *
     * @param algorithmRegistry the algorithm registry
     * @param algorithm         the algorithm to use
     * @param enabled           whether rate limiting is enabled
     */
    public InMemoryRateLimiter(AlgorithmRegistry algorithmRegistry, RateLimitAlgorithm algorithm, boolean enabled) {
        this.states = new ConcurrentHashMap<>();
        this.algorithmRegistry = algorithmRegistry;
        this.algorithm = algorithm;
        this.enabled = enabled;
    }

    @Override
    public Uni<RateLimitDecision> checkAndConsume(RateLimitKey key, EffectiveRateLimit limit) {
        if (!enabled) {
            return Uni.createFrom().item(RateLimitDecision.allow());
        }

        final var cacheKey = key.toCacheKey();
        final var handler = algorithmRegistry.getHandler(algorithm);
        final var nowMillis = System.currentTimeMillis();

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
        final var nowMillis = System.currentTimeMillis();
        final var currentState = states.get(cacheKey);

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
     * Returns the current number of tracked rate limit buckets.
     *
     * <p>
     * Useful for monitoring and testing.
     *
     * @return the number of active buckets
     */
    public int getBucketCount() {
        return states.size();
    }

    /**
     * Clears all rate limit state.
     *
     * <p>
     * Primarily for testing purposes.
     */
    public void clear() {
        states.clear();
    }

    private RateLimitDecision computeDecision(
            String cacheKey,
            aussie.core.model.ratelimit.RateLimitAlgorithmHandler handler,
            EffectiveRateLimit limit,
            long nowMillis) {

        // Atomic compute to handle concurrent requests
        final var result = new RateLimitDecision[1];

        states.compute(cacheKey, (k, currentState) -> {
            final var decision = handler.checkAndConsume(currentState, limit, nowMillis);
            result[0] = decision;
            return decision.newState();
        });

        return result[0];
    }
}

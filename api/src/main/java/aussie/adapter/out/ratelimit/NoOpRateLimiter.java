package aussie.adapter.out.ratelimit;

import io.smallrye.mutiny.Uni;

import aussie.core.model.ratelimit.EffectiveRateLimit;
import aussie.core.model.ratelimit.RateLimitDecision;
import aussie.core.model.ratelimit.RateLimitKey;
import aussie.core.port.out.RateLimiter;

/**
 * A no-op rate limiter that allows all requests.
 *
 * <p>Used when rate limiting is disabled or as a fallback when no
 * provider is available.
 */
public final class NoOpRateLimiter implements RateLimiter {

    private static final NoOpRateLimiter INSTANCE = new NoOpRateLimiter();

    private NoOpRateLimiter() {}

    /**
     * Return the singleton instance.
     *
     * @return the no-op rate limiter
     */
    public static NoOpRateLimiter getInstance() {
        return INSTANCE;
    }

    @Override
    public Uni<RateLimitDecision> checkAndConsume(RateLimitKey key, EffectiveRateLimit limit) {
        return Uni.createFrom().item(RateLimitDecision.allow());
    }

    @Override
    public Uni<RateLimitDecision> getStatus(RateLimitKey key, EffectiveRateLimit limit) {
        return Uni.createFrom().item(RateLimitDecision.allow());
    }

    @Override
    public Uni<Void> reset(RateLimitKey key) {
        return Uni.createFrom().voidItem();
    }

    @Override
    public Uni<Void> removeKeysMatching(String pattern) {
        return Uni.createFrom().voidItem();
    }

    @Override
    public boolean isEnabled() {
        return false;
    }
}

package aussie.core.port.out;

import io.smallrye.mutiny.Uni;

import aussie.core.model.ratelimit.EffectiveRateLimit;
import aussie.core.model.ratelimit.RateLimitDecision;
import aussie.core.model.ratelimit.RateLimitKey;

/**
 * Port interface for rate limiting operations.
 *
 * <p>Implementations handle the actual rate limit checking and state management,
 * whether in-memory, Redis-based, or other storage backends.
 *
 * <p>All operations are non-blocking and return reactive types.
 */
public interface RateLimiter {

    /**
     * Check if a request is allowed and consume a token if so.
     *
     * <p>This is an atomic operation that:
     * <ol>
     *   <li>Retrieves current rate limit state</li>
     *   <li>Applies the configured algorithm to determine if allowed</li>
     *   <li>Updates state (consumes token or records request)</li>
     *   <li>Returns the decision with updated state</li>
     * </ol>
     *
     * @param key the rate limit key identifying the bucket
     * @param limit the effective rate limit to apply
     * @return a decision indicating whether the request is allowed
     */
    Uni<RateLimitDecision> checkAndConsume(RateLimitKey key, EffectiveRateLimit limit);

    /**
     * Get the current rate limit status without consuming a token.
     *
     * <p>Useful for reporting current limits in response headers or
     * status endpoints.
     *
     * @param key the rate limit key
     * @param limit the effective rate limit
     * @return the current status
     */
    Uni<RateLimitDecision> getStatus(RateLimitKey key, EffectiveRateLimit limit);

    /**
     * Reset the rate limit for a specific key.
     *
     * <p>This is typically used for administrative purposes or testing.
     *
     * @param key the rate limit key to reset
     * @return completion signal
     */
    Uni<Void> reset(RateLimitKey key);

    /**
     * Remove all rate limit keys matching a pattern.
     *
     * <p>Used for cleanup, such as when a WebSocket connection closes.
     *
     * @param pattern the key pattern to match (e.g., connection ID)
     * @return completion signal
     */
    Uni<Void> removeKeysMatching(String pattern);

    /**
     * Check if rate limiting is enabled.
     *
     * @return true if rate limiting is active
     */
    boolean isEnabled();
}

package aussie.core.model;

/**
 * Handler interface for rate limiting algorithm implementations.
 *
 * <p>Each algorithm (token bucket, fixed window, sliding window) implements
 * this interface to provide its specific rate limiting logic.
 *
 * <p>Implementations must be:
 * <ul>
 *   <li>Thread-safe</li>
 *   <li>Stateless (state is passed in and returned)</li>
 *   <li>Deterministic given the same inputs</li>
 * </ul>
 */
public interface RateLimitAlgorithmHandler {

    /**
     * Returns the algorithm type this handler implements.
     *
     * @return the algorithm type
     */
    RateLimitAlgorithm algorithm();

    /**
     * Check if a request is allowed and compute the new state.
     *
     * @param currentState the current rate limit state (may be null for first request)
     * @param limit the effective rate limit configuration
     * @param nowMillis the current timestamp in milliseconds
     * @return the decision including the new state
     */
    RateLimitDecision checkAndConsume(RateLimitState currentState, EffectiveRateLimit limit, long nowMillis);

    /**
     * Create the initial state for a new rate limit bucket.
     *
     * @param limit the effective rate limit configuration
     * @param nowMillis the current timestamp in milliseconds
     * @return the initial state
     */
    RateLimitState createInitialState(EffectiveRateLimit limit, long nowMillis);

    /**
     * Get the current status without consuming a token.
     *
     * @param currentState the current rate limit state (may be null)
     * @param limit the effective rate limit configuration
     * @param nowMillis the current timestamp in milliseconds
     * @return the current status
     */
    default RateLimitDecision getStatus(RateLimitState currentState, EffectiveRateLimit limit, long nowMillis) {
        if (currentState == null) {
            final var initialState = createInitialState(limit, nowMillis);
            return createAllowedDecision(initialState, limit, nowMillis);
        }
        return computeStatus(currentState, limit, nowMillis);
    }

    /**
     * Compute the status for existing state without consuming.
     *
     * @param currentState the current state (not null)
     * @param limit the rate limit configuration
     * @param nowMillis current timestamp
     * @return the status decision
     */
    RateLimitDecision computeStatus(RateLimitState currentState, EffectiveRateLimit limit, long nowMillis);

    /**
     * Create an allowed decision with the given state.
     *
     * @param state the current state
     * @param limit the rate limit configuration
     * @param nowMillis current timestamp
     * @return an allowed decision
     */
    default RateLimitDecision createAllowedDecision(RateLimitState state, EffectiveRateLimit limit, long nowMillis) {
        final var resetAt = computeResetTime(nowMillis, limit.windowSeconds());
        return RateLimitDecision.allow(
                state.remaining(),
                limit.requestsPerWindow(),
                limit.windowSeconds(),
                resetAt,
                (int) (limit.requestsPerWindow() - state.remaining()),
                state);
    }

    /**
     * Compute the reset time for the current window.
     *
     * @param nowMillis current timestamp
     * @param windowSeconds window duration
     * @return reset time as Instant
     */
    default java.time.Instant computeResetTime(long nowMillis, long windowSeconds) {
        final var windowMillis = windowSeconds * 1000;
        final var windowStart = (nowMillis / windowMillis) * windowMillis;
        final var resetMillis = windowStart + windowMillis;
        return java.time.Instant.ofEpochMilli(resetMillis);
    }
}
